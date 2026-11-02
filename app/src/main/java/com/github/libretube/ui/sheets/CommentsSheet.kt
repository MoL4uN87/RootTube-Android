package com.github.libretube.ui.sheets

import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
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
        val prompt = commentTranslations.prepareAlice(comment) ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setClassName("com.yandex.browser", "com.yandex.browser.ShareFileAliceChatActivity")
            putExtra(Intent.EXTRA_TEXT, prompt)
        }
        try {
            startActivity(intent)
            awaitingAliceReturn = true
        } catch (_: ActivityNotFoundException) {
            commentTranslations.clearPendingAlice()
            Toast.makeText(
                requireContext(),
                R.string.comments_translate_alice_missing_browser,
                Toast.LENGTH_LONG
            ).show()
        } catch (_: SecurityException) {
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
        if (awaitingAliceReturn && commentTranslations.pendingAlice != null) {
            awaitingAliceReturn = false
            // Wait until the sheet regains focus; clipboard reads are explicit
            // and are performed only if the user taps Paste in the dialog.
            _binding?.root?.post {
                if (isAdded && _binding != null) showAlicePasteDialog()
            }
        }
    }

    private fun showAlicePasteDialog() {
        if (commentTranslations.pendingAlice == null || !isAdded) return
        val context = requireContext()
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.comments_translate_alice_paste_title)
            .setMessage(R.string.comments_translate_alice_paste_instructions)
            .setNegativeButton(R.string.comments_translate_alice_cancel) { _, _ ->
                commentTranslations.clearPendingAlice()
            }
            .setPositiveButton(R.string.comments_translate_alice_paste, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                // The user must explicitly request this clipboard read.
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                    as ClipboardManager
                val text = clipboard.primaryClip?.let { clip ->
                    if (clip.itemCount > 0) clip.getItemAt(0)
                        .coerceToText(context)?.toString()?.trim() else null
                }
                val pending = commentTranslations.pendingAlice
                if (text.isNullOrBlank() || text == pending?.source ||
                    text.length > 5000 ||
                    text.contains("Переведи следующий комментарий YouTube")
                ) {
                    Toast.makeText(
                        context,
                        R.string.comments_translate_alice_paste_invalid,
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }
                // User confirms the text really came from Alice: never silently
                // overwrite a comment with an unrelated clipboard entry.
                AlertDialog.Builder(context)
                    .setTitle(R.string.comments_translate_alice_confirm_title)
                    .setMessage(text.take(650))
                    .setNegativeButton(R.string.comments_translate_alice_cancel, null)
                    .setPositiveButton(R.string.comments_translate_alice_apply) { _, _ ->
                        if (commentTranslations.applyCopiedAlice(text)) {
                            dialog.dismiss()
                        } else {
                            Toast.makeText(
                                context,
                                R.string.comments_translate_alice_paste_invalid,
                                Toast.LENGTH_LONG
                            ).show()
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
