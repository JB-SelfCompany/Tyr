package com.jbselfcompany.tyr.chat.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.jbselfcompany.tyr.R
import com.jbselfcompany.tyr.chat.data.ChatContact
import com.jbselfcompany.tyr.chat.data.ChatMessage
import com.jbselfcompany.tyr.databinding.ItemChatContactBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatContactAdapter(
    private val onContactClick: (ChatContact) -> Unit,
    private val onContactLongClick: (ChatContact) -> Unit
) : RecyclerView.Adapter<ChatContactAdapter.ViewHolder>() {

    private val items = mutableListOf<ContactItem>()

    data class ContactItem(
        val contact: ChatContact,
        val lastMessage: ChatMessage?,
        val unreadCount: Int
    )

    fun submitList(newItems: List<ContactItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    inner class ViewHolder(private val binding: ItemChatContactBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ContactItem) {
            val ctx = binding.root.context
            val displayName = item.contact.name.ifBlank { item.contact.address }
            binding.textContactName.text = displayName

            val last = item.lastMessage
            if (item.contact.isPending) {
                // Pending contact: show label instead of last message preview
                binding.textLastMessage.text = ctx.getString(R.string.chat_pending_label)
                binding.textLastTime.text = if (last != null) formatTime(last.timestamp) else ""
                binding.imageMessageStatus.visibility = View.GONE
            } else if (last != null) {
                val preview = buildPreviewText(ctx, last)
                binding.textLastMessage.text = if (last.isSent) {
                    ctx.getString(R.string.chat_last_message_you, preview)
                } else {
                    preview
                }
                binding.textLastTime.text = formatTime(last.timestamp)

                // Show send status icon only for outgoing messages that have been confirmed sent
                if (last.isSent) {
                    when (last.status) {
                        ChatMessage.STATUS_SENDING -> {
                            // Not yet confirmed — hide the icon, matching the inner chat view
                            binding.imageMessageStatus.visibility = View.GONE
                        }
                        ChatMessage.STATUS_ERROR -> {
                            binding.imageMessageStatus.visibility = View.VISIBLE
                            binding.imageMessageStatus.setImageResource(R.drawable.ic_error)
                            binding.imageMessageStatus.alpha = 1f
                            binding.imageMessageStatus.contentDescription =
                                ctx.getString(R.string.chat_message_error_desc)
                        }
                        else -> { // STATUS_SENT — delivery confirmed
                            binding.imageMessageStatus.visibility = View.VISIBLE
                            binding.imageMessageStatus.setImageResource(R.drawable.ic_check_single)
                            binding.imageMessageStatus.alpha = 1.0f
                            binding.imageMessageStatus.contentDescription =
                                ctx.getString(R.string.chat_message_status_desc)
                        }
                    }
                } else {
                    binding.imageMessageStatus.visibility = View.GONE
                }
            } else {
                binding.textLastMessage.text = ""
                binding.textLastTime.text = ""
                binding.imageMessageStatus.visibility = View.GONE
            }

            if (item.unreadCount > 0) {
                binding.badgeUnread.text = item.unreadCount.toString()
                binding.badgeUnread.visibility = View.VISIBLE
            } else {
                binding.badgeUnread.visibility = View.GONE
            }

            // Avatar: first letter of name/address
            val initial = displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            binding.textAvatar.text = initial

            binding.root.setOnClickListener { onContactClick(item.contact) }
            binding.root.setOnLongClickListener {
                if (!item.contact.isPending) onContactLongClick(item.contact)
                true
            }
        }

        /**
         * Builds the preview text for the last message.
         * For messages with attachments, shows an emoji + file name (or generic label).
         * For text-only messages, shows the message body.
         */
        private fun buildPreviewText(ctx: android.content.Context, msg: ChatMessage): String =
            if (msg.hasAttachment) {
                val isImage = msg.attachmentMimeType?.startsWith("image/") == true
                val name = msg.attachmentName
                if (isImage) {
                    if (!name.isNullOrBlank()) {
                        "\uD83D\uDDBC $name" // 🖼️
                    } else {
                        ctx.getString(R.string.chat_preview_photo)
                    }
                } else {
                    if (!name.isNullOrBlank()) {
                        "\uD83D\uDCCE $name" // 📎
                    } else {
                        ctx.getString(R.string.chat_preview_file)
                    }
                }
            } else {
                msg.body
            }
    }

    private fun formatTime(ts: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - ts
        return when {
            diff < 24 * 60 * 60 * 1000L -> SimpleDateFormat(
                "HH:mm",
                Locale.getDefault()
            ).format(Date(ts))
            else -> SimpleDateFormat("dd.MM", Locale.getDefault()).format(Date(ts))
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemChatContactBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size
}
