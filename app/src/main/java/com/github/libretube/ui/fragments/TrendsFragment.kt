package com.github.libretube.ui.fragments

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.github.libretube.R
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.TrendingCategory
import com.github.libretube.constants.IntentData
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.databinding.FragmentTrendsBinding
import com.github.libretube.databinding.FragmentTrendsContentBinding
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.extensions.serializable
import com.github.libretube.helpers.LocaleHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.ViewedChannelsHelper
import com.github.libretube.ui.adapters.TopViewedChannelsAdapter
import com.github.libretube.ui.adapters.VideoCardsAdapter
import com.github.libretube.ui.base.DynamicLayoutManagerFragment
import com.github.libretube.ui.models.TrendsViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest

class TrendsFragment : Fragment(R.layout.fragment_trends) {
    private var _binding: FragmentTrendsBinding? = null
    private val binding get() = _binding!!
    private var pagerCallback: ViewPager2.OnPageChangeCallback? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentTrendsBinding.bind(view)

        val categories = MediaServiceRepository.instance.getTrendingCategories() +
            TrendingCategory.TOP_MY_CHANNELS

        val adapter = TrendsAdapter(this, categories)
        binding.pager.adapter = adapter


        if (categories.size <= 1) binding.tabLayout.isGone = true
        TabLayoutMediator(binding.tabLayout, binding.pager) { tab, position ->
            val category = categories[position]
            tab.text = getString(category.titleRes)
        }.attach()

        val initialCategory = PreferenceHelper.getString(
            PreferenceKeys.TRENDING_CATEGORY,
            TrendingCategory.GAMING.name
        ).let { runCatching { TrendingCategory.valueOf(it) }.getOrDefault(TrendingCategory.GAMING) }

        val initialTabIndex = if (arguments?.getBoolean(ARG_OPEN_TOP_CHANNELS) == true) {
            categories.indexOf(TrendingCategory.TOP_MY_CHANNELS).coerceAtLeast(0)
        } else {
            categories.indexOf(initialCategory).coerceAtLeast(0)
        }
        val initialTab = binding.tabLayout.getTabAt(initialTabIndex)
        binding.tabLayout.selectTab(initialTab)
        binding.pager.setCurrentItem(initialTabIndex, false)

        pagerCallback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                binding.trendingRegion.isGone = categories[position].isPersonalRanking ||
                    categories[position].searchQuery != null
            }
        }.also { binding.pager.registerOnPageChangeCallback(it) }
        binding.trendingRegion.isGone = categories[initialTabIndex].isPersonalRanking ||
            categories[initialTabIndex].searchQuery != null

        binding.trendingRegion.setOnClickListener {
            showChangeRegionDialog(requireContext()) {
                adapter.getFragmentAt(binding.pager.currentItem)?.also {
                    it.refreshTrending()
                }
            }
        }
    }

    fun openTopChannels() {
        val currentBinding = _binding ?: return
        val lastTabIndex = (currentBinding.pager.adapter?.itemCount ?: 0) - 1
        if (lastTabIndex >= 0) currentBinding.pager.setCurrentItem(lastTabIndex, true)
    }

    override fun onDestroyView() {
        pagerCallback?.let { binding.pager.unregisterOnPageChangeCallback(it) }
        pagerCallback = null
        binding.pager.adapter = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val ARG_OPEN_TOP_CHANNELS = "roottube_open_top_channels"

        fun showChangeRegionDialog(context: Context, onPositiveButtonClick: () -> Unit) {
            val currentRegionPref = PreferenceHelper.getTrendingRegion(context)

            val countries = LocaleHelper.getAvailableCountries()
            var selected = countries.indexOfFirst { it.code == currentRegionPref }
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.region)
                .setSingleChoiceItems(
                    countries.map { it.name }.toTypedArray(),
                    selected
                ) { _, checked ->
                    selected = checked
                }
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.okay) { _, _ ->
                    PreferenceHelper.putString(PreferenceKeys.REGION, countries[selected].code)
                    onPositiveButtonClick()
                }
                .show()
        }
    }
}

class TrendsAdapter(fragment: Fragment, private val categories: List<TrendingCategory>) :
    FragmentStateAdapter(fragment) {
    private val fragments: MutableList<TrendsContentFragment?> =
        MutableList(categories.size) { null }

    override fun createFragment(position: Int): Fragment {
        val trendContentFragment = TrendsContentFragment().apply {
            arguments = bundleOf(
                IntentData.category to categories[position]
            )
        }
        fragments[position] = trendContentFragment
        return trendContentFragment
    }

    override fun getItemCount(): Int {
        return categories.size
    }

    fun getFragmentAt(position: Int): TrendsContentFragment? {
        return fragments[position]
    }
}

class TrendsContentFragment : DynamicLayoutManagerFragment(R.layout.fragment_trends_content) {
    private var _binding: FragmentTrendsContentBinding? = null
    private val binding get() = _binding!!
    private val viewModel: TrendsViewModel by activityViewModels()

    private var _category: TrendingCategory? = null
    private val category get() = _category!!

    override fun setLayoutManagers(gridItems: Int) {
        _binding?.recview?.layoutManager = GridLayoutManager(
            context, if (_category?.isPersonalRanking == true) 1 else gridItems
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentTrendsContentBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        _category = requireArguments()
            .serializable<TrendingCategory>(IntentData.category)!!

        if (category.isPersonalRanking) {
            showPersonalRanking()
            return
        }

        val adapter = VideoCardsAdapter()
        binding.recview.adapter = adapter
        binding.recview.layoutManager?.onRestoreInstanceState(viewModel.recyclerViewState)

        viewModel.trendingVideos.observe(viewLifecycleOwner) { categoryMap ->
            val videos = categoryMap[category] ?: return@observe

            toggleLoadingIndicator(false)

            adapter.submitList(videos.streams)
            binding.emptyState.setText(R.string.roottube_trends_no_results)
            binding.emptyState.isVisible = videos.streams.isEmpty()

            val trendingRegion = PreferenceHelper.getTrendingRegion(requireContext())
            if (category.searchQuery == null && videos.streams.isEmpty() &&
                videos.region == trendingRegion
            ) {
                Snackbar.make(
                    requireParentFragment().requireView(),
                    R.string.change_region,
                    Snackbar.LENGTH_LONG
                )
                    .setAction(R.string.change) {
                        TrendsFragment.showChangeRegionDialog(requireContext()) {
                            refreshTrending()
                        }
                    }
                    .show()
            }
        }

        binding.homeRefresh.isEnabled = true
        binding.homeRefresh.setOnRefreshListener {
            refreshTrending()
        }

        binding.recview.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                viewModel.recyclerViewState = recyclerView.layoutManager?.onSaveInstanceState()
            }
        })

        lifecycleScope.launch {
            // every time the user navigates to the fragment for the selected category,
            // fetch the trends for the selected category if they're not yet cached or if the value
            // for trending region has been changed
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                val trendingRegion = PreferenceHelper.getTrendingRegion(requireContext())
                val trendingVideos = viewModel.trendingVideos.value.orEmpty()[category]
                if (trendingVideos == null ||
                    (category.searchQuery == null && trendingVideos.region != trendingRegion)
                ) {
                    refreshTrending()
                }
            }
        }
    }

    private fun showPersonalRanking() {
        val adapter = TopViewedChannelsAdapter()
        binding.recview.adapter = adapter
        binding.recview.layoutManager = GridLayoutManager(context, 1)
        binding.progressBar.isGone = true
        binding.homeRefresh.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DatabaseHolder.Database.viewedChannelDao().observeAll().collectLatest { channels ->
                    adapter.submitList(ViewedChannelsHelper.rank(channels))
                    _binding?.emptyState?.isVisible = channels.isEmpty()
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // manually restore the recyclerview state due to https://github.com/material-components/material-components-android/issues/3473
        if (!category.isPersonalRanking) {
            binding.recview.layoutManager?.onRestoreInstanceState(viewModel.recyclerViewState)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun toggleLoadingIndicator(show: Boolean) {
        binding.recview.alpha = if (show) 0.3f else 1.0f
        if (show) binding.emptyState.isGone = true
        binding.progressBar.isGone = !show
        if (!show) binding.homeRefresh.isRefreshing = false
    }

    fun refreshTrending() {
        if (category.isPersonalRanking) return
        toggleLoadingIndicator(true)
        viewModel.fetchTrending(requireContext(), category)
    }
}
