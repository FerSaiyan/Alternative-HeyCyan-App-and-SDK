package com.fersaiyan.cyanbridge.ui.chat

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.shared.chat.ChatThread
import com.fersaiyan.cyanbridge.databinding.ItemChatThreadBinding

class ChatThreadAdapter(
    private val onClick: (ChatThread) -> Unit,
    private val onDelete: (ChatThread) -> Unit,
) : ListAdapter<ChatThread, ChatThreadAdapter.VH>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemChatThreadBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return VH(binding, onClick, onDelete)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    private companion object {
        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ChatThread>() {
            override fun areItemsTheSame(oldItem: ChatThread, newItem: ChatThread): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: ChatThread, newItem: ChatThread): Boolean =
                oldItem == newItem
        }
    }

    class VH(
        private val binding: ItemChatThreadBinding,
        private val onClick: (ChatThread) -> Unit,
        private val onDelete: (ChatThread) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ChatThread) {
            binding.threadTitle.text = item.title
            binding.root.setOnClickListener { onClick(item) }
            binding.root.setOnLongClickListener { view ->
                AlertDialog.Builder(view.context)
                    .setTitle(view.context.getString(R.string.delete_chat_title))
                    .setMessage(view.context.getString(R.string.delete_chat_message, item.title))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        onDelete(item)
                    }
                    .show()
                true
            }
        }
    }
}
