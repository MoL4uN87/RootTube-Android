package com.github.libretube.ui.sheets

import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import android.view.View
import android.view.WindowManager
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
import com.github.libretube.api.obj.Comment
import com.github.libretube.services.AliceScreenRecognitionService
import com.github.libretube.voiceover.AliceOcrBridge

class CommentsSheet : ExpandablePlayerSheet(R.layout.comments_sheet) {
    private var _binding: CommentsSheetBinding? = null
    val binding get() = _binding!!

    private val commonPlayerViewModel: CommonPlayerViewModel by activityViewModels()
    private val commentTranslations: CommentsTranslationViewModel by viewModels()
    private var awaitingAliceReturn = false

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
            commentTranslations.toggleAlice()
            if (commentTranslations.mode.value == CommentsTranslationViewModel.Mode.ALICE) {
                Toast.makeText(
                    requireContext(),
                    R.string.comments_translate_alice_hint,
                    Toast.LENGTH_LONG
                ).show()
            }
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
     * The official Yandex Browser handles this ACTION_SEND intent. It pre-fills
     * the Alice chat but does not provide an API result back to the caller.
     */
    fun requestAliceTranslation(comment: Comment) {
        if (commentTranslations.pendingAlice?.commentId == comment.commentId) {
            showAlicePasteDialog()
            return
        }
        if (!AliceScreenRecognitionService.connected) {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.alice_ocr_settings_title)
                .setMessage(R.string.alice_ocr_settings_message)
                .setPositiveButton(R.string.alice_ocr_settings_open) { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton(R.string.alice_ocr_settings_later) { _, _ ->
                    launchAliceTranslation(comment, enableOcr = false)
                }
                .show()
            return
        }
        launchAliceTranslation(comment, enableOcr = true)
    }

    private fun launchAliceTranslation(comment: Comment, enableOcr: Boolean) {
        val prompt = commentTranslations.prepareAlice(comment) ?: return
        Log.i(TAG, "alice_handoff prepared ocr=$enableOcr")
        val marker = commentTranslations.pendingAlice?.marker ?: return
        if (enableOcr) AliceOcrBridge.arm(marker)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setClassName("com.yandex.browser", "com.yandex.browser.ShareFileAliceChatActivity")
            putExtra(Intent.EXTRA_TEXT, prompt)
        }
        try {
            startActivity(intent)
            awaitingAliceReturn = true
            Log.i(TAG, "alice_handoff activity_started")
        } catch (_: ActivityNotFoundException) {
            AliceOcrBridge.clear()
            commentTranslations.clearPendingAlice()
            Toast.makeText(
                requireContext(),
                R.string.comments_translate_alice_missing_browser,
                Toast.LENGTH_LONG
            ).show()
        } catch (_: SecurityException) {
            AliceOcrBridge.clear()
            commentTranslations.clearPendingAlice()
            Toast.makeText(
                requireContext(),
                R.string.comments_translate_alice_missing_browser,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // Stop any pending automated Back actions as soon as RootTube is back.
        AliceScreenRecognitionService.notifyRootTubeResumed()
        Log.i(TAG, "sheet_resumed awaiting=$awaitingAliceReturn pending=${commentTranslations.pendingAlice != null} binding=${_binding != null}")
        if (awaitingAliceReturn && commentTranslations.pendingAlice != null) {
            awaitingAliceReturn = false
            _binding?.root?.post {
                if (!isAdded || _binding == null) {
                    Log.w(TAG, "sheet_resume_no_binding")
                    return@post
                }
                val marker = commentTranslations.pendingAlice?.marker
                val automaticallyRead = marker?.let { AliceOcrBridge.consume(it) }
                Log.i(TAG, "sheet_ocr_result available=${automaticallyRead != null}")
                if (automaticallyRead != null &&
                    commentTranslations.applyCopiedAlice(automaticallyRead)
                ) {
                    Toast.makeText(
                        requireContext(),
                        R.string.alice_ocr_success,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    // Backwards-compatible manual fallback if OCR could not
                    // safely identify the response.
                    Log.i(TAG, "sheet_show_manual_fallback")
                    showAlicePasteDialog()
                }
            }
        }
    }

    private fun showAlicePasteDialog() {
        if (commentTranslations.pendingAlice == null || !isAdded) return
        val context = requireContext()
        val padding = (20 * resources.displayMetrics.density).toInt()
        val input = EditText(context).apply {
            hint = getString(R.string.comments_translate_alice_input_hint)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2
            maxLines = 7
            setSingleLine(false)
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, 0, padding, 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.comments_translate_alice_paste_title)
            .setMessage(R.string.comments_translate_alice_paste_instructions)
            .setView(container)
            .setNegativeButton(R.string.comments_translate_alice_cancel) { _, _ ->
                commentTranslations.clearPendingAlice()
            }
            .setNeutralButton(R.string.comments_translate_alice_from_clipboard, null)
            .setPositiveButton(R.string.comments_translate_alice_paste, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                // Read the clipboard only on the user's explicit tap.
                val copiedText = try {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                        as ClipboardManager
                    clipboard.primaryClip?.let { clip ->
                        if (clip.itemCount > 0) {
                            clip.getItemAt(0).coerceToText(context)?.toString()
                        } else null
                    }
                } catch (_: SecurityException) {
                    null
                } catch (_: Exception) {
                    null
                }
                if (copiedText.isNullOrBlank()) {
                    Toast.makeText(
                        context,
                        R.string.comments_translate_alice_clipboard_empty,
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    input.setText(copiedText.take(5001))
                    input.setSelection(input.text.length)
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = input.text.toString().trim()
                val pending = commentTranslations.pendingAlice
                val error = when {
                    text.isBlank() -> R.string.comments_translate_alice_input_empty
                    text.length > 5000 -> R.string.comments_translate_alice_input_too_long
                    text == pending?.source ||
                        text.contains("Переведи следующий комментарий YouTube") ->
                        R.string.comments_translate_alice_input_source
                    else -> null
                }
                if (error != null) {
                    input.error = getString(error)
                    return@setOnClickListener
                }
                // Confirm before replacing a comment with copied or pasted text.
                AlertDialog.Builder(context)
                    .setTitle(R.string.comments_translate_alice_confirm_title)
                    .setMessage(text.take(650))
                    .setNegativeButton(R.string.comments_translate_alice_cancel, null)
                    .setPositiveButton(R.string.comments_translate_alice_apply) { _, _ ->
                        if (commentTranslations.applyCopiedAlice(text)) {
                            dialog.dismiss()
                        } else {
                            input.error =
                                getString(R.string.comments_translate_alice_paste_invalid)
                        }
                    }
                    .show()
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
        Log.i(TAG, "sheet_view_destroyed pending=${commentTranslations.pendingAlice != null}")
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
        private const val TAG = "RootTubeAliceOCR"
        const val HANDLE_LINK_REQUEST_KEY = "handle_link_request_key"
        const val DISMISS_SHEET_REQUEST_KEY = "dismiss_sheet_request_key"
    }
}
