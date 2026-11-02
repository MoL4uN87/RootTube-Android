package com.github.libretube.ui.sheets

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
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
import com.github.libretube.ui.models.CommentsTranslationViewModel
import com.github.libretube.ui.models.AliceTranslationClient

class CommentsSheet : ExpandablePlayerSheet(R.layout.comments_sheet) {
    private var _binding: CommentsSheetBinding? = null
    val binding get() = _binding!!

    private val commonPlayerViewModel: CommonPlayerViewModel by activityViewModels()
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
        binding.btnTranslateAlice.setOnClickListener {
            if (commentTranslations.mode.value == CommentsTranslationViewModel.Mode.ALICE) {
                commentTranslations.toggleAlice()
            } else if (AliceTranslationClient.isConfigured(requireContext())) {
                commentTranslations.toggleAlice()
            } else {
                configureAlice()
            }
        }
        binding.btnTranslateAlice.setOnLongClickListener {
            configureAlice()
            true
        }
        commentTranslations.mode.observe(viewLifecycleOwner) { mode ->
            binding.btnTranslateComments.text = getString(
                if (mode == CommentsTranslationViewModel.Mode.RU)
                    R.string.comments_translate_original
                else R.string.comments_translate_ru
            )
            binding.btnTranslateComments.isSelected = mode == CommentsTranslationViewModel.Mode.RU
            binding.btnTranslateAlice.text = getString(
                if (mode == CommentsTranslationViewModel.Mode.ALICE)
                    R.string.comments_translate_alice_active
                else R.string.comments_translate_alice_button
            )
            binding.btnTranslateAlice.isSelected = mode == CommentsTranslationViewModel.Mode.ALICE
        }

        childFragmentManager.commit {
            replace<CommentsMainFragment>(R.id.commentFragContainer, args = arguments)
        }
    }


    /**
     * AI+ uses a user-configured endpoint. The Yandex Cloud API key is never
     * entered into RootTube; only a separately revocable proxy access token.
     */
    private fun configureAlice() {
        val context = requireContext()
        val density = resources.displayMetrics.density
        val padding = (20 * density).toInt()
        val fields = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, (8 * density).toInt(), padding, 0)
        }
        val urlField = EditText(context).apply {
            hint = getString(R.string.comments_translate_alice_config_url)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(AliceTranslationClient.getBaseUrl(context))
        }
        val tokenField = EditText(context).apply {
            hint = getString(R.string.comments_translate_alice_config_token)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        fields.addView(urlField)
        fields.addView(tokenField)

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.comments_translate_alice_config_title)
            .setMessage(R.string.comments_translate_alice_config_message)
            .setView(fields)
            .setNegativeButton(R.string.comments_translate_alice_cancel, null)
            .setPositiveButton(R.string.comments_translate_alice_save, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    AliceTranslationClient.save(
                        context, urlField.text.toString(), tokenField.text.toString()
                    )
                    commentTranslations.toggleAlice()
                    dialog.dismiss()
                } catch (_: Exception) {
                    tokenField.error = getString(R.string.comments_translate_alice_config_error)
                }
            }
        }
        dialog.show()
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
