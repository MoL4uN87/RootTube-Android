package com.github.libretube.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.R
import com.github.libretube.databinding.ChannelRowBinding
import com.github.libretube.db.obj.ViewedChannel
import com.github.libretube.extensions.formatShort
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.NavigationHelper

/** A channel-row list ranked by subscriber count. */
class TopViewedChannelsAdapter :
    ListAdapter<ViewedChannel, TopViewedChannelsAdapter.ChannelHolder>(DIFF) {

    class ChannelHolder(val binding: ChannelRowBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelHolder =
        ChannelHolder(ChannelRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ChannelHolder, position: Int) {
        val channel = getItem(position)
        holder.binding.apply {
            searchChannelName.text = "${position + 1}. ${channel.name}"
            searchViews.text = if (channel.subscriberCount >= 0) {
                root.context.getString(R.string.subscribers, channel.subscriberCount.formatShort())
            } else {
                root.context.getString(R.string.roottube_trends_unknown_subscribers)
            }
            ImageHelper.loadImage(channel.avatarUrl, searchChannelImage, true)
            root.setOnClickListener {
                NavigationHelper.navigateChannel(root.context, channel.channelId)
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ViewedChannel>() {
            override fun areItemsTheSame(oldItem: ViewedChannel, newItem: ViewedChannel) =
                oldItem.channelId == newItem.channelId

            override fun areContentsTheSame(oldItem: ViewedChannel, newItem: ViewedChannel) =
                oldItem == newItem
        }
    }
}
