package com.jbselfcompany.tyr.chat.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.jbselfcompany.tyr.R
import com.jbselfcompany.tyr.chat.data.ChatMessage
import com.jbselfcompany.tyr.databinding.ItemMessageReceivedBinding
import com.jbselfcompany.tyr.databinding.ItemMessageSentBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ChatMessageAdapter(
    private val context: Context,
    private val onReplySwipe: ((ChatMessage) -> Unit)? = null,
    private val onAttachmentClick: ((ChatMessage) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class ChatListItem {
        data class MessageItem(val msg: ChatMessage) : ChatListItem()
        data class DateItem(val label: String) : ChatListItem()
    }

    private val rawMessages = mutableListOf<ChatMessage>()
    private val items = mutableListOf<ChatListItem>()

    companion object {
        const val VIEW_SENT = 1
        const val VIEW_RECEIVED = 2
        const val VIEW_DATE_SEPARATOR = 3

        // Context menu item IDs — extend here to add more actions (forward, delete, reply, etc.)
        private const val MENU_ITEM_COPY = 1

        /**
         * Parses email-style quoted text from a message body.
         * Returns Pair(quoteText, actualText) or Pair(null, body) if no quote found.
         */
        fun parseQuote(body: String): Pair<String?, String> {
            if (!body.startsWith("> ")) return Pair(null, body)
            val separatorIdx = body.indexOf("\n\n")
            if (separatorIdx < 0) return Pair(null, body)
            val quotePart = body.substring(0, separatorIdx)
            val actualText = body.substring(separatorIdx + 2)
            if (actualText.isBlank()) return Pair(null, body)
            val quoteLines = quotePart.split("\n")
            if (quoteLines.all { it.startsWith("> ") }) {
                val quoteText = quoteLines.joinToString("\n") { it.removePrefix("> ") }
                return Pair(quoteText, actualText)
            }
            return Pair(null, body)
        }
    }

    private fun formatDateLabel(ts: Long): String {
        val msgCal = Calendar.getInstance().apply { timeInMillis = ts }
        val todayCal = Calendar.getInstance()
        return when {
            msgCal.get(Calendar.YEAR) == todayCal.get(Calendar.YEAR) &&
            msgCal.get(Calendar.DAY_OF_YEAR) == todayCal.get(Calendar.DAY_OF_YEAR) ->
                context.getString(R.string.chat_date_today)
            msgCal.get(Calendar.YEAR) == todayCal.get(Calendar.YEAR) &&
            msgCal.get(Calendar.DAY_OF_YEAR) == todayCal.get(Calendar.DAY_OF_YEAR) - 1 ->
                context.getString(R.string.chat_date_yesterday)
            else ->
                SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(Date(ts))
        }
    }

    private fun buildItems() {
        items.clear()
        var lastDate: String? = null
        for (msg in rawMessages) {
            val dateLabel = formatDateLabel(msg.timestamp)
            if (dateLabel != lastDate) {
                items.add(ChatListItem.DateItem(dateLabel))
                lastDate = dateLabel
            }
            items.add(ChatListItem.MessageItem(msg))
        }
    }

    fun submitList(newMessages: List<ChatMessage>) {
        rawMessages.clear()
        rawMessages.addAll(newMessages)
        buildItems()
        notifyDataSetChanged()
    }

    fun addMessage(message: ChatMessage) {
        rawMessages.add(message)
        buildItems()
        notifyDataSetChanged()
    }

    fun updateMessageStatus(messageId: Long, status: Int) {
        val idx = rawMessages.indexOfFirst { it.id == messageId }
        if (idx >= 0) {
            rawMessages[idx] = rawMessages[idx].copy(status = status)
            buildItems()
            notifyDataSetChanged()
        }
    }

    fun getItem(position: Int): ChatMessage =
        (items[position] as ChatListItem.MessageItem).msg

    override fun getItemViewType(position: Int) = when (val item = items[position]) {
        is ChatListItem.MessageItem -> if (item.msg.isSent) VIEW_SENT else VIEW_RECEIVED
        is ChatListItem.DateItem -> VIEW_DATE_SEPARATOR
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        when (viewType) {
            VIEW_SENT -> SentViewHolder(
                ItemMessageSentBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
            VIEW_RECEIVED -> ReceivedViewHolder(
                ItemMessageReceivedBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
            else -> DateSeparatorViewHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.item_date_separator, parent, false)
            )
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ChatListItem.MessageItem -> when (holder) {
                is SentViewHolder -> holder.bind(item.msg)
                is ReceivedViewHolder -> holder.bind(item.msg)
            }
            is ChatListItem.DateItem -> (holder as DateSeparatorViewHolder).bind(item.label)
        }
    }

    override fun getItemCount() = items.size

    inner class SentViewHolder(private val binding: ItemMessageSentBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(msg: ChatMessage) {
            val (quote, text) = parseQuote(msg.body)

            // Body: hide if empty and there's an attachment
            if (text.isNotBlank()) {
                binding.textMessageBody.visibility = View.VISIBLE
                binding.textMessageBody.text = text
            } else {
                binding.textMessageBody.visibility = View.GONE
            }

            binding.textMessageTime.text = formatTime(msg.timestamp)

            // Quote
            if (quote != null) {
                binding.layoutQuote.visibility = View.VISIBLE
                binding.textQuote.text = quote
            } else {
                binding.layoutQuote.visibility = View.GONE
            }

            // Attachment
            bindAttachment(
                msg,
                binding.layoutAttachment,
                binding.imageAttachmentThumb,
                binding.layoutFileInfo,
                binding.textAttachmentName,
                binding.textAttachmentSize
            )

            when (msg.status) {
                ChatMessage.STATUS_SENDING -> {
                    binding.imageStatus.visibility = View.INVISIBLE
                }
                ChatMessage.STATUS_ERROR -> {
                    binding.imageStatus.setImageResource(R.drawable.ic_error)
                    binding.imageStatus.alpha = 1f
                    binding.imageStatus.visibility = View.VISIBLE
                }
                else -> {
                    binding.imageStatus.setImageResource(R.drawable.ic_check_single)
                    binding.imageStatus.alpha = 0.8f
                    binding.imageStatus.visibility = View.VISIBLE
                }
            }

            if (msg.hasAttachment) {
                binding.layoutAttachment.setOnClickListener { onAttachmentClick?.invoke(msg) }
            }

            binding.root.setOnClickListener {
                showMessageMenu(binding.root, msg.body)
            }
        }
    }

    inner class ReceivedViewHolder(private val binding: ItemMessageReceivedBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(msg: ChatMessage) {
            val (quote, text) = parseQuote(msg.body)

            if (text.isNotBlank()) {
                binding.textMessageBody.visibility = View.VISIBLE
                binding.textMessageBody.text = text
            } else {
                binding.textMessageBody.visibility = View.GONE
            }

            binding.textMessageTime.text = formatTime(msg.timestamp)

            if (quote != null) {
                binding.layoutQuote.visibility = View.VISIBLE
                binding.textQuote.text = quote
            } else {
                binding.layoutQuote.visibility = View.GONE
            }

            // Attachment
            bindAttachment(
                msg,
                binding.layoutAttachment,
                binding.imageAttachmentThumb,
                binding.layoutFileInfo,
                binding.textAttachmentName,
                binding.textAttachmentSize
            )

            if (msg.hasAttachment) {
                binding.layoutAttachment.setOnClickListener { onAttachmentClick?.invoke(msg) }
            }

            binding.root.setOnClickListener {
                showMessageMenu(binding.root, msg.body)
            }
        }
    }

    /**
     * Shows a popup context menu anchored to the tapped message view.
     * Additional actions (forward, delete, reply, etc.) can be added here later.
     */
    private fun showMessageMenu(anchor: View, body: String) {
        val popup = PopupMenu(anchor.context, anchor)
        popup.menu.add(0, MENU_ITEM_COPY, 0, anchor.context.getString(R.string.chat_message_menu_copy))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_ITEM_COPY -> {
                    copyMessageText(anchor.context, body)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun copyMessageText(ctx: Context, body: String) {
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("message", body))
        Toast.makeText(ctx, ctx.getString(R.string.chat_message_copied), Toast.LENGTH_SHORT).show()
    }

    private fun bindAttachment(
        msg: ChatMessage,
        layoutAttachment: View,
        imageThumb: ImageView,
        layoutFileInfo: View,
        textName: TextView,
        textSize: TextView
    ) {
        if (!msg.hasAttachment || msg.attachmentPath.isNullOrEmpty()) {
            layoutAttachment.visibility = View.GONE
            return
        }

        layoutAttachment.visibility = View.VISIBLE
        val isImage = msg.attachmentMimeType?.startsWith("image/") == true

        if (isImage) {
            imageThumb.visibility = View.VISIBLE
            layoutFileInfo.visibility = View.GONE
            val file = File(msg.attachmentPath)
            if (file.exists()) {
                val targetPx = (220 * context.resources.displayMetrics.density).toInt()
                val bitmap = loadBitmapWithExifCorrection(file, targetPx)
                imageThumb.setImageBitmap(bitmap)
            }
        } else {
            imageThumb.visibility = View.GONE
            layoutFileInfo.visibility = View.VISIBLE
            textName.text = msg.attachmentName ?: ""
            textSize.text = formatFileSize(msg.attachmentSizeBytes)
        }
    }

    inner class DateSeparatorViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val textDate: TextView = view.findViewById(R.id.text_date)
        fun bind(label: String) {
            textDate.text = label
        }
    }

    private fun loadBitmapWithExifCorrection(file: File, targetPx: Int): android.graphics.Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        opts.inSampleSize = calculateInSampleSize(opts, targetPx, targetPx)
        opts.inJustDecodeBounds = false
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null

        val rotation = try {
            val exif = ExifInterface(file.absolutePath)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (_: Exception) { 0f }

        return if (rotation != 0f) {
            val matrix = Matrix().apply { postRotate(rotation) }
            android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                .also { if (it !== bitmap) bitmap.recycle() }
        } else {
            bitmap
        }
    }

    private fun formatTime(ts: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))

    private fun calculateInSampleSize(opts: BitmapFactory.Options, reqW: Int, reqH: Int): Int {
        var inSampleSize = 1
        val (h, w) = opts.outHeight to opts.outWidth
        if (h > reqH || w > reqW) {
            val halfH = h / 2
            val halfW = w / 2
            while (halfH / inSampleSize >= reqH && halfW / inSampleSize >= reqW) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun formatFileSize(bytes: Long): String {
        return if (bytes >= 1024 * 1024) {
            context.getString(R.string.chat_attachment_size_mb, bytes / (1024.0 * 1024.0))
        } else {
            context.getString(R.string.chat_attachment_size_kb, bytes / 1024)
        }
    }
}
