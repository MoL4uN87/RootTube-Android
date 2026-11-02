package com.github.libretube.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.text.method.LinkMovementMethodCompat
import androidx.core.text.parseAsHtml
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.paging.PagingDataAdapter
import com.github.libretube.R
import com.github.libretube.api.obj.Comment
import com.github.libretube.databinding.CommentsRowBinding
import com.github.libretube.extensions.formatShort
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.ThemeHelper
import com.github.libretube.ui.adapters.callbacks.DiffUtilItemCallback
import com.github.libretube.ui.viewholders.CommentViewHolder
import com.github.libretube.ui.models.CommentsTranslationViewModel
import com.github.libretube.util.HtmlParser
import com.github.libretube.util.LinkHandler
import com.github.libretube.util.TextUtils

class CommentsPagingAdapter(
    private val isReplies: Boolean,
    private val channelAvatar: String?,
    private val commentTranslations: CommentsTranslationViewModel,
    private val handleLink: (url: String) -> Unit,
    private val saveToClipboard: (Comment) -> Unit,
    private val navigateToChannel: (Comment) -> Unit,
    private val navigateToReplies: ((Comment, String?) -> Unit)? = null,
) : PagingDataAdapter<Comment, CommentViewHolder>(
    DiffUtilItemCallback(
        areItemsTheSame = { oldItem, newItem -> oldItem.commentId == newItem.commentId },
        areContentsTheSame = { _, _ -> true },
    )
) {

    private var clickEventConsumedByLinkHandler = false

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CommentViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        val binding = CommentsRowBinding.inflate(layoutInflater, parent, false)
        return CommentViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CommentViewHolder, position: Int) {
        holder.binding.apply {
            val comment = getItem(position)!!
            commentAuthor.text = comment.author
            commentAuthor.setBackgroundResource(
                if (comment.channelOwner) R.drawable.comment_channel_owner_bg else 0
            )
            commentInfos.text = comment.commentedTimeMillis?.let {
                TextUtils.formatRelativeDate(it)
            } ?: comment.commentedTime

            commentText.movementMethod = LinkMovementMethodCompat.getInstance()
            val linkHandler = LinkHandler {
                clickEventConsumedByLinkHandler = true
                handleLink.invoke(it)
            }
            val translatedMode = commentTranslations.enabled.value == true
            val result = if (translatedMode) commentTranslations.resultFor(comment) else null
            if (result is CommentsTranslationViewModel.Result.Translated) {
                commentText.text = result.text
            } else {
                commentText.text = comment.commentText?.replace("</a>", "</a> ")
                    ?.parseAsHtml(tagHandler = HtmlParser(linkHandler))
            }

            translationStatus.isVisible = translatedMode && comment.commentText?.isNotBlank() == true &&
                (result == null || result == CommentsTranslationViewModel.Result.Failed)
            if (translationStatus.isVisible) {
                translationStatus.setText(
                    if (result == CommentsTranslationViewModel.Result.Failed)
                        R.string.comments_translate_unavailable
                    else R.string.comments_translate_loading
                )
            }
            if (translatedMode && result == null) {
                commentTranslations.request(comment)
            }

            ImageHelper.loadImage(comment.thumbnail, commentorImage, true)
            likesTextView.text = comment.likeCount.formatShort()

            if (comment.creatorReplied && !channelAvatar.isNullOrBlank()) {
                ImageHelper.loadImage(channelAvatar, creatorReplyImageView, true)
                creatorReplyImageView.isVisible = true
            } else {
                creatorReplyImageView.setImageDrawable(null)
                creatorReplyImageView.isVisible = false
            }

            verifiedImageView.isVisible = comment.verified
            pinnedImageView.isVisible = comment.pinned
            heartedImageView.isVisible = comment.hearted
            repliesCount.isVisible = !isReplies && comment.repliesPage != null
            repliesCount.text = if (comment.replyCount > 0) comment.replyCount.formatShort() else null

            commentorImage.setOnClickListener {
                navigateToChannel(comment)
            }

            if (isReplies) {
                // highlight the comment that is being replied to
                if (position == 0) {
                    root.setBackgroundColor(
                        ThemeHelper.getThemeColor(
                            root.context,
                            com.google.android.material.R.attr.colorSurface
                        )
                    )
                } else {
                    root.background = AppCompatResources.getDrawable(
                        root.context,
                        R.drawable.rounded_ripple
                    )
                    commentorImage.updateLayoutParams<ViewGroup.MarginLayoutParams> { leftMargin = 58 }
                }
            } else {
                val onClickListener = View.OnClickListener {
                    if (clickEventConsumedByLinkHandler) {
                        clickEventConsumedByLinkHandler = false
                        return@OnClickListener
                    }
                    navigateToReplies?.invoke(comment, channelAvatar)
                }
                root.setOnClickListener(onClickListener)
                commentText.setOnClickListener(onClickListener)
            }

            root.setOnLongClickListener {
                saveToClipboard(
                    if (translatedMode && result is CommentsTranslationViewModel.Result.Translated)
                        comment.copy(commentText = result.text)
                    else comment
                )
                true
            }
        }
    }
}