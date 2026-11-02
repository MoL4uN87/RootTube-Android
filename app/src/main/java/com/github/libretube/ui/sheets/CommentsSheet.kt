package com.github.libretube.ui.sheets

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.activity.addCallback
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.fragment.app.commit
import androidx.fragment.app.replace
import androidx.fragment.app.setFragmentResult
import com.github.libretube.R
import com.github.libretube.databinding.CommentsSheetBinding
import com.github.libretube.ui.fragments.CommentsMainFragment
import com.github.libretube.ui.models.CommonPlayerViewModel
import com.github.libretube.ui.models.CommentsViewModel
import com.github.libretube.ui.models.CommentsTranslationViewModel

class CommentsSheet : ExpandablePlayerSheet(R.layout.comments_sheet) {
    private var _binding: CommentsSheetBinding? = null
    val binding get() = _binding!!

    private val commonPlayerViewModel: CommonPlayerViewModel by activityViewModels()
    private val commentsViewModel: CommentsViewModel by activityViewModels()
    private val commentTranslations: CommentsTranslationViewModel by viewModels()

    private val backPressedCallback by lazy(LazyThreadSafetyMode.NONE) {
        (dialog as ComponentDialog?)?.onBackPressedDispatcher?.addCallback(owner = viewLifecycleOwner, enabled = false) {
            if (childFragmentManager.backStackEntryCount > 0) {
                childFragmentManager.popBackStack()
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = CommentsSheetBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        childFragmentManager.setFragmentResultListener(DISMISS_SHEET_REQUEST_KEY, viewLifecycleOwner) { _, _ ->
            dismiss()
        }

        // forward requests to open links to the parent fragment
        childFragmentManager.setFragmentResultListener(HANDLE_LINK_REQUEST_KEY, viewLifecycleOwner) { key, result ->
            parentFragment?.setFragmentResult(key, result)
        }

        binding.btnBack.setOnClickListener {
            if (childFragmentManager.backStackEntryCount > 0) {
                childFragmentManager.popBackStack()
            }
        }

        binding.btnClose.setOnClickListener { dismiss() }

        binding.btnTranslateComments.setOnClickListener { commentTranslations.toggle() }
        binding.btnOpenYandex.setOnClickListener { openVideoInYandex() }
        commentTranslations.enabled.observe(viewLifecycleOwner) { translated ->
            binding.btnTranslateComments.text = getString(
                if (translated) R.string.comments_translate_original
                else R.string.comments_translate_ru
            )
            binding.btnTranslateComments.tooltipText = getString(
                if (translated) R.string.comments_translate_original_tooltip
                else R.string.comments_translate_tooltip
            )
        }

        childFragmentManager.commit {
            replace<CommentsMainFragment>(R.id.commentFragContainer, args = arguments)
        }
    }

    /**
     * The free Alice translation in Yandex Browser is a browser-only feature,
     * not a public API. Open the same video in the official browser so the user
     * can use its built-in page/selection translation; nothing is scraped.
     */
    private fun openVideoInYandex() {
        val videoId = commentsViewModel.videoIdLiveData.value
        if (videoId == null || !videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) {
            Toast.makeText(
                requireContext(),
                R.string.comments_yandex_no_video,
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val videoUrl = Uri.Builder()
            .scheme("https")
            .authority("m.youtube.com")
            .path("/watch")
            .appendQueryParameter("v", videoId)
            .build()
        val intent = Intent(Intent.ACTION_VIEW, videoUrl)
            .setPackage("com.yandex.browser")
            .addCategory(Intent.CATEGORY_BROWSABLE)
        try {
            startActivity(intent)
            Toast.makeText(
                requireContext(),
                R.string.comments_yandex_tip,
                Toast.LENGTH_LONG
            ).show()
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                requireContext(),
                R.string.comments_yandex_not_installed,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onStart() {
        super.onStart()
        // remove internal padding from the bottomsheet
        // https://github.com/material-components/material-components-android/issues/3389#issuecomment-2049028605
        dialog?.window?.apply {
            setFlags(
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )
        }
    }


    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun getSheetMaxHeightPx() = commonPlayerViewModel.maxSheetHeightPx

    override fun getDragHandle() = binding.dragHandle

    override fun getBottomSheet() = binding.standardBottomSheet

    fun updateFragmentInfo(showBackButton: Boolean, title: String) {
        binding.btnBack.isVisible = showBackButton
        binding.commentsTitle.text = title
        backPressedCallback?.isEnabled = showBackButton
    }

    companion object {
        const val HANDLE_LINK_REQUEST_KEY = "handle_link_request_key"
        const val DISMISS_SHEET_REQUEST_KEY = "dismiss_sheet_request_key"
    }
}
