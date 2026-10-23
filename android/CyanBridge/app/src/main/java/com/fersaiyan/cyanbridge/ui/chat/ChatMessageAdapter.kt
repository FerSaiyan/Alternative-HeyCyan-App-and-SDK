package com.fersaiyan.cyanbridge.ui.chat

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.shared.chat.ChatMessage
import com.fersaiyan.cyanbridge.shared.chat.ChatRole
import com.fersaiyan.cyanbridge.databinding.ItemMessageReceivedBinding
import com.fersaiyan.cyanbridge.databinding.ItemMessageSentBinding

class ChatMessageAdapter : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(DIFF_CALLBACK) {

    private var userBubbleColor: Int? = null
    private var assistantBubbleColor: Int? = null

    fun applyAppearance(userColor: Int, assistantColor: Int) {
        userBubbleColor = userColor
        assistantBubbleColor = assistantColor
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position).role) {
            ChatRole.USER -> TYPE_SENT
            ChatRole.ASSISTANT -> TYPE_RECEIVED
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_SENT -> {
                val binding = ItemMessageSentBinding.inflate(inflater, parent, false)
                SentVH(binding)
            }
            TYPE_RECEIVED -> {
                val binding = ItemMessageReceivedBinding.inflate(inflater, parent, false)
                ReceivedVH(binding)
            }
            else -> error("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is SentVH -> holder.bind(item, userBubbleColor)
            is ReceivedVH -> holder.bind(item, assistantBubbleColor)
        }
    }

    class SentVH(private val binding: ItemMessageSentBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ChatMessage, bubbleColor: Int?) {
            binding.textMessage.text = item.content
            val color = bubbleColor ?: ContextCompat.getColor(itemView.context, R.color.cyan_accent)
            tintBubble(binding.textMessage, color)
        }
    }

    class ReceivedVH(private val binding: ItemMessageReceivedBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ChatMessage, bubbleColor: Int?) {
            binding.textMessage.text = item.content
            val color = bubbleColor ?: ContextCompat.getColor(itemView.context, R.color.card_bg)
            tintBubble(binding.textMessage, color)
        }
    }

    companion object {
        private const val TYPE_SENT = 1
        private const val TYPE_RECEIVED = 2

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean =
                oldItem == newItem
        }

        private fun tintBubble(textView: TextView, bubbleColor: Int) {
            textView.backgroundTintList = ColorStateList.valueOf(bubbleColor)
            val textColor = if (ColorUtils.calculateLuminance(bubbleColor) > 0.6) {
                Color.BLACK
            } else {
                Color.WHITE
            }
            textView.setTextColor(textColor)
        }
    }
}
