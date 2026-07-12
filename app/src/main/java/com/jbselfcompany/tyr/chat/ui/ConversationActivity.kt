package com.jbselfcompany.tyr.chat.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.jbselfcompany.tyr.R
import com.jbselfcompany.tyr.TyrApplication
import com.jbselfcompany.tyr.chat.data.ChatMessage
import com.jbselfcompany.tyr.chat.data.ChatRepository
import com.jbselfcompany.tyr.chat.network.ImapFetcher
import com.jbselfcompany.tyr.chat.network.SmtpSender
import com.jbselfcompany.tyr.databinding.ActivityConversationBinding
import com.jbselfcompany.tyr.service.YggmailService
import com.jbselfcompany.tyr.ui.BaseActivity
import java.io.File
import java.io.FileOutputStream

class ConversationActivity : BaseActivity() {

    companion object {
        const val EXTRA_CONTACT_ADDRESS = "extra_contact_address"
        const val EXTRA_CONTACT_NAME = "extra_contact_name"
        private const val POLL_INTERVAL_MS = 20_000L
        private const val ATTACHMENTS_DIR = "attachments"

        /**
         * Address of the contact whose conversation is currently visible on screen.
         * Set on onResume, cleared on onPause. YggmailService reads this to suppress
         * notifications and mark messages read when the conversation is open.
         */
        @Volatile
        var activeChatAddress: String? = null
            private set
    }

    private lateinit var binding: ActivityConversationBinding
    private val configRepository by lazy { TyrApplication.instance.configRepository }
    private lateinit var chatRepository: ChatRepository
    private lateinit var messageAdapter: ChatMessageAdapter

    private lateinit var contactAddress: String
    private lateinit var myAddress: String

    private var replyToText: String? = null

    private val pollHandler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            fetchNewMessages()
            pollHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    // Guard against concurrent IMAP fetches (timer + broadcast firing together)
    private val isFetchInProgress = java.util.concurrent.atomic.AtomicBoolean(false)

    // Receive instant refresh when the background service delivers a new message or
    // processes a delivery receipt — avoids waiting for the 20-second poll cycle.
    //
    // The service already inserted the message into the DB before broadcasting, so
    // there is no need to do another IMAP fetch here: doing so would compute
    // sinceUid=<already-inserted uid>, get an empty range, set changed=false, and
    // never call loadMessages() — leaving the photo invisible in the UI.
    // Instead we reload directly from the DB and then check for any status changes
    // (delivery receipts) that the service wrote but the adapter doesn't know about.
    private val newMessageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == YggmailService.ACTION_NEW_CHAT_MESSAGES) {
                // The service has already done the IMAP fetch and DB insert before
                // broadcasting. Just reload from DB — triggering another IMAP fetch
                // here would create a concurrent download of the same large message
                // that is still being streamed by the service, causing ANR.
                runOnUiThread { loadMessages() }
            }
        }
    }

    private val attachmentsDir: File
        get() = (getExternalFilesDir(null) ?: filesDir).let {
            File(it, ATTACHMENTS_DIR).also { dir -> dir.mkdirs() }
        }

    // --- Photo picker launcher ---

    private val pickPhotoLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { handlePickedPhoto(it) }
    }

    // --- Permission launcher ---

    private val requestMediaPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.any { it }) {
            launchPhotoPicker()
        } else {
            Snackbar.make(binding.root, R.string.chat_attachment_error, Snackbar.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConversationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        contactAddress = intent.getStringExtra(EXTRA_CONTACT_ADDRESS) ?: run {
            finish()
            return
        }
        val contactName = intent.getStringExtra(EXTRA_CONTACT_NAME) ?: ""
        myAddress = configRepository.getMailAddress() ?: ""

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = contactName.ifBlank { contactAddress }

        chatRepository = ChatRepository(this)
        messageAdapter = ChatMessageAdapter(
            context = this,
            onReplySwipe = { msg ->
                val (_, actualText) = ChatMessageAdapter.parseQuote(msg.body)
                startReply(actualText)
            },
            onAttachmentClick = { msg ->
                openAttachment(msg)
            }
        )

        binding.recyclerMessages.apply {
            layoutManager = LinearLayoutManager(this@ConversationActivity).apply {
                stackFromEnd = true
            }
            adapter = messageAdapter
        }

        setupSwipeToReply()

        binding.buttonSend.setOnClickListener { sendMessage() }
        binding.buttonCancelReply.setOnClickListener { cancelReply() }
        binding.buttonAttach.setOnClickListener { onAttachButtonClick() }

        binding.editMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else false
        }

        val isPending = chatRepository.isPendingContact(contactAddress)
        if (isPending) {
            showPendingBanner()
        } else {
            hidePendingBanner()
            chatRepository.markConversationRead(myAddress, contactAddress)
            // Dismiss chat notification for this contact now that the conversation is open
            YggmailService.cancelChatNotificationForSender(this, contactAddress)
        }

        loadMessages()

        if (YggmailService.isRunning) {
            pollHandler.post(pollRunnable)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_conversation, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            R.id.action_contact_profile -> { showContactProfile(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showContactProfile() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_contact_profile, null)
        val emailText = dialogView.findViewById<android.widget.TextView>(R.id.text_contact_email)
        val nameEdit = dialogView.findViewById<TextInputEditText>(R.id.edit_contact_name)

        emailText.text = contactAddress
        val contact = chatRepository.getContact(contactAddress)
        nameEdit.setText(contact?.name ?: "")

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.chat_contact_profile)
            .setView(dialogView)
            .setPositiveButton(R.string.save) { _, _ ->
                val newName = nameEdit.text.toString().trim()
                chatRepository.updateContactName(contactAddress, newName)
                supportActionBar?.title = newName.ifBlank { contactAddress }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        // Mark this conversation as active so the service suppresses notifications
        // and marks incoming messages as read while the chat is on screen.
        activeChatAddress = contactAddress

        // Re-mark read and dismiss any notification that arrived while we were paused.
        val isPending = chatRepository.isPendingContact(contactAddress)
        if (!isPending) {
            chatRepository.markConversationRead(myAddress, contactAddress)
            YggmailService.cancelChatNotificationForSender(this, contactAddress)
        }

        // Register for instant refresh when the service delivers a message or processes
        // a delivery receipt — supplements the 20-second poll for immediate UI updates.
        val filter = IntentFilter(YggmailService.ACTION_NEW_CHAT_MESSAGES)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(newMessageReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(newMessageReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        activeChatAddress = null
        try { unregisterReceiver(newMessageReceiver) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        pollHandler.removeCallbacks(pollRunnable)
    }

    private fun onAttachButtonClick() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_IMAGES
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            launchPhotoPicker()
        } else {
            requestMediaPermission.launch(arrayOf(permission))
        }
    }

    private fun launchPhotoPicker() {
        pickPhotoLauncher.launch("image/*")
    }

    private fun handlePickedPhoto(uri: Uri) {
        val maxSizeMB = configRepository.getCachedMaxMessageSizeMB()
        val maxSizeBytes = maxSizeMB * 1024L * 1024L

        // Validate MIME type — must be an image
        val mimeType = contentResolver.getType(uri)
        if (mimeType == null || !mimeType.startsWith("image/")) {
            Snackbar.make(binding.root, R.string.chat_photo_invalid, Snackbar.LENGTH_SHORT).show()
            return
        }

        // Get file size
        val fileSize = contentResolver.openFileDescriptor(uri, "r")?.use {
            it.statSize
        } ?: 0L

        if (fileSize > maxSizeBytes) {
            Snackbar.make(
                binding.root,
                getString(R.string.chat_file_too_large, maxSizeMB),
                Snackbar.LENGTH_LONG
            ).show()
            return
        }

        val ext = mimeType.substringAfter("image/").takeIf { it.isNotBlank() } ?: "jpg"
        val displayName = getFileDisplayName(uri) ?: "photo.$ext"

        // Copy photo to app's private attachments dir
        val destFile = File(attachmentsDir, "${System.currentTimeMillis()}_$displayName")
        try {
            val inputStream = contentResolver.openInputStream(uri)
            if (inputStream == null) {
                Snackbar.make(binding.root, R.string.chat_attachment_error, Snackbar.LENGTH_SHORT).show()
                return
            }
            inputStream.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            Snackbar.make(binding.root, R.string.chat_attachment_error, Snackbar.LENGTH_SHORT).show()
            return
        }
        // Verify the file was actually written before attempting to send it.
        if (!destFile.exists() || destFile.length() == 0L) {
            Snackbar.make(binding.root, R.string.chat_attachment_error, Snackbar.LENGTH_SHORT).show()
            return
        }

        sendMessageWithAttachment(destFile, displayName, mimeType, fileSize)
    }

    private fun getFileDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIdx >= 0 && cursor.moveToFirst()) cursor.getString(nameIdx) else null
            }
        } catch (_: Exception) { null }
    }

    private fun sendMessageWithAttachment(
        file: File,
        displayName: String,
        mimeType: String,
        sizeBytes: Long
    ) {
        if (!YggmailService.isRunning) {
            Snackbar.make(binding.root, R.string.error_service_not_running, Snackbar.LENGTH_SHORT).show()
            return
        }

        val inputText = binding.editMessage.text.toString().trim()
        val replyQuote = replyToText
        val body = if (replyQuote != null) {
            val quotedLines = replyQuote.lines().joinToString("\n") { "> $it" }
            "$quotedLines\n\n$inputText"
        } else {
            inputText
        }

        binding.editMessage.isEnabled = false
        binding.buttonSend.isEnabled = false
        binding.buttonAttach.isEnabled = false

        val optimisticMsg = ChatMessage(
            fromAddr = myAddress,
            toAddr = contactAddress,
            body = body,
            timestamp = System.currentTimeMillis(),
            isRead = true,
            isSent = true,
            status = ChatMessage.STATUS_SENDING,
            attachmentPath = file.absolutePath,
            attachmentName = displayName,
            attachmentMimeType = mimeType,
            attachmentSizeBytes = sizeBytes
        )
        val localId = chatRepository.insertMessage(optimisticMsg)
        val msgWithId = optimisticMsg.copy(id = localId)
        messageAdapter.addMessage(msgWithId)
        binding.recyclerMessages.scrollToPosition(messageAdapter.itemCount - 1)
        binding.editMessage.setText("")
        binding.editMessage.isEnabled = true
        binding.buttonSend.isEnabled = true
        binding.buttonAttach.isEnabled = true

        if (replyQuote != null) cancelReply()

        Thread {
            val password = configRepository.getPassword()
            if (password == null) {
                runOnUiThread {
                    chatRepository.updateMessageStatus(localId, ChatMessage.STATUS_ERROR)
                    messageAdapter.updateMessageStatus(localId, ChatMessage.STATUS_ERROR)
                }
                return@Thread
            }
            val myNickname = configRepository.getNickname()
            val result = SmtpSender().send(
                fromAddress = myAddress,
                password = password,
                toAddress = contactAddress,
                body = body,
                senderNickname = myNickname,
                attachment = SmtpSender.AttachmentData(file, displayName, mimeType)
            )
            runOnUiThread {
                if (result is SmtpSender.Result.Error) {
                    chatRepository.updateMessageStatus(localId, ChatMessage.STATUS_ERROR)
                    messageAdapter.updateMessageStatus(localId, ChatMessage.STATUS_ERROR)
                    Snackbar.make(
                        binding.root,
                        getString(R.string.chat_send_error, result.message),
                        Snackbar.LENGTH_LONG
                    ).show()
                } else if (result is SmtpSender.Result.Success) {
                    // Store the exact timestamp that was written into the Date: header.
                    // The recipient parses this value and echoes it back as
                    // X-Tyr-Delivery-Timestamp. Matching in updateSentMessageStatus-
                    // NearTimestamp uses a ±5 s tolerance; using the Date: header
                    // timestamp directly (instead of a pre-send snapshot) guarantees
                    // an exact match even when AUTH takes many seconds on Android.
                    if (result.dateHeaderTimestamp > 0) {
                        chatRepository.updateMessageTimestamp(localId, result.dateHeaderTimestamp)
                    }
                    // Status stays SENDING until delivery receipt arrives.
                }
            }
        }.start()
    }

    private fun openAttachment(msg: ChatMessage) {
        val path = msg.attachmentPath ?: return
        val file = File(path)
        if (!file.exists()) {
            Snackbar.make(binding.root, R.string.chat_attachment_error, Snackbar.LENGTH_SHORT).show()
            return
        }
        if (msg.attachmentMimeType?.startsWith("image/") == true) {
            showImagePreview(file)
        } else {
            try {
                val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, msg.attachmentMimeType ?: "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, msg.attachmentName))
            } catch (e: Exception) {
                Snackbar.make(binding.root, R.string.chat_attachment_error, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun showImagePreview(file: File) {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val view = layoutInflater.inflate(R.layout.dialog_image_preview, null)
        val imageView = view.findViewById<ZoomableImageView>(R.id.image_preview_full)
        val closeButton = view.findViewById<android.widget.ImageButton>(R.id.button_close_preview)

        Thread {
            val bitmap = loadBitmapWithExifCorrection(file)
            runOnUiThread {
                imageView.setImageBitmap(bitmap)
                imageView.post { imageView.resetMatrix() }
            }
        }.start()

        closeButton.setOnClickListener { dialog.dismiss() }
        dialog.setCanceledOnTouchOutside(false)
        dialog.setContentView(view)
        dialog.show()
    }

    private fun loadBitmapWithExifCorrection(file: File): android.graphics.Bitmap? {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
        val density = resources.displayMetrics.density
        val screenW = (resources.displayMetrics.widthPixels / density).toInt()
        val screenH = (resources.displayMetrics.heightPixels / density).toInt()
        val targetPx = (maxOf(screenW, screenH) * density).toInt()
        val sampleOpts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(opts, targetPx, targetPx)
        }
        val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath, sampleOpts) ?: return null

        val rotation = try {
            val exif = androidx.exifinterface.media.ExifInterface(file.absolutePath)
            when (exif.getAttributeInt(
                androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
            )) {
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (_: Exception) { 0f }

        return if (rotation != 0f) {
            val matrix = android.graphics.Matrix().apply { postRotate(rotation) }
            android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                .also { if (it !== bitmap) bitmap.recycle() }
        } else {
            bitmap
        }
    }

    private fun calculateInSampleSize(opts: android.graphics.BitmapFactory.Options, reqW: Int, reqH: Int): Int {
        var inSampleSize = 1
        val h = opts.outHeight
        val w = opts.outWidth
        if (h > reqH || w > reqW) {
            val halfH = h / 2
            val halfW = w / 2
            while (halfH / inSampleSize >= reqH && halfW / inSampleSize >= reqW) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun setupSwipeToReply() {
        val swipeCallback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {

            override fun getSwipeDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                return if (viewHolder is ChatMessageAdapter.SentViewHolder ||
                           viewHolder is ChatMessageAdapter.ReceivedViewHolder) {
                    ItemTouchHelper.RIGHT
                } else 0
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ) = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val msg = messageAdapter.getItem(position)
                    val (_, actualText) = ChatMessageAdapter.parseQuote(msg.body)
                    startReply(actualText)
                    messageAdapter.notifyItemChanged(position)
                }
            }

            override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder) = 0.2f

            override fun getSwipeEscapeVelocity(defaultValue: Float) = defaultValue * 0.5f

            override fun onChildDraw(
                c: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX > 0) {
                    val itemView = viewHolder.itemView
                    val swipeFraction = (dX / itemView.width).coerceIn(0f, 1f)

                    val replyIcon = ContextCompat.getDrawable(
                        recyclerView.context, R.drawable.ic_reply
                    )
                    if (replyIcon != null) {
                        val iconSize = (24 * recyclerView.context.resources.displayMetrics.density).toInt()
                        val iconMargin = (16 * recyclerView.context.resources.displayMetrics.density).toInt()
                        val iconTop = itemView.top + (itemView.height - iconSize) / 2
                        val iconLeft = itemView.left + iconMargin
                        val iconRight = itemView.left + iconMargin + iconSize
                        val iconBottom = iconTop + iconSize

                        val alpha = (swipeFraction * 2f).coerceIn(0f, 1f)
                        replyIcon.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                        replyIcon.alpha = (alpha * 255).toInt()

                        val tv = TypedValue()
                        itemView.context.theme.resolveAttribute(
                            android.R.attr.colorPrimary, tv, true
                        )
                        replyIcon.colorFilter = PorterDuffColorFilter(tv.data, PorterDuff.Mode.SRC_IN)
                        replyIcon.draw(c)
                    }
                }

                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }
        }

        ItemTouchHelper(swipeCallback).attachToRecyclerView(binding.recyclerMessages)
    }

    private fun startReply(quoteText: String) {
        replyToText = quoteText
        binding.layoutReplyPreview.visibility = View.VISIBLE
        binding.textReplyPreview.text = quoteText
        binding.editMessage.requestFocus()
    }

    private fun cancelReply() {
        replyToText = null
        binding.layoutReplyPreview.visibility = View.GONE
        binding.textReplyPreview.text = ""
    }

    private fun showPendingBanner() {
        binding.layoutPendingBanner.visibility = View.VISIBLE
        binding.editMessage.isEnabled = false
        binding.buttonSend.isEnabled = false
        binding.buttonAttach.isEnabled = false

        binding.buttonAccept.setOnClickListener { acceptContact() }
        binding.buttonDecline.setOnClickListener { declineContact() }
    }

    private fun hidePendingBanner() {
        binding.layoutPendingBanner.visibility = View.GONE
        binding.editMessage.isEnabled = true
        binding.buttonSend.isEnabled = true
        binding.buttonAttach.isEnabled = true
    }

    private fun acceptContact() {
        chatRepository.acceptPendingContact(contactAddress)
        chatRepository.markConversationRead(myAddress, contactAddress)
        YggmailService.cancelChatNotificationForSender(this, contactAddress)
        hidePendingBanner()
    }

    private fun declineContact() {
        chatRepository.declineContact(contactAddress)
        finish()
    }

    private fun loadMessages() {
        // If the conversation is visible, mark all incoming messages as read and
        // cancel any pending notification before reloading — this handles the case
        // where the broadcast receiver fires while the activity is on screen.
        val isPending = chatRepository.isPendingContact(contactAddress)
        if (!isPending) {
            chatRepository.markConversationRead(myAddress, contactAddress)
            YggmailService.cancelChatNotificationForSender(this, contactAddress)
        }
        val messages = chatRepository.getConversation(myAddress, contactAddress)
        messageAdapter.submitList(messages)
        val itemCount = messageAdapter.itemCount
        if (itemCount > 0) {
            binding.recyclerMessages.scrollToPosition(itemCount - 1)
        }
    }

    private fun sendMessage() {
        if (!YggmailService.isRunning) {
            Snackbar.make(binding.root, R.string.error_service_not_running, Snackbar.LENGTH_SHORT).show()
            return
        }

        val inputText = binding.editMessage.text.toString().trim()
        if (inputText.isEmpty()) return

        val replyQuote = replyToText
        val body = if (replyQuote != null) {
            val quotedLines = replyQuote.lines().joinToString("\n") { "> $it" }
            "$quotedLines\n\n$inputText"
        } else {
            inputText
        }

        binding.editMessage.isEnabled = false
        binding.buttonSend.isEnabled = false

        val optimisticMsg = ChatMessage(
            fromAddr = myAddress,
            toAddr = contactAddress,
            body = body,
            timestamp = System.currentTimeMillis(),
            isRead = true,
            isSent = true,
            status = ChatMessage.STATUS_SENDING
        )
        val localId = chatRepository.insertMessage(optimisticMsg)
        val msgWithId = optimisticMsg.copy(id = localId)
        messageAdapter.addMessage(msgWithId)
        binding.recyclerMessages.scrollToPosition(messageAdapter.itemCount - 1)
        binding.editMessage.setText("")
        binding.editMessage.isEnabled = true
        binding.buttonSend.isEnabled = true

        if (replyQuote != null) cancelReply()

        Thread {
            val password = configRepository.getPassword()
            if (password == null) {
                runOnUiThread {
                    Snackbar.make(binding.root, R.string.error_service_not_running, Snackbar.LENGTH_SHORT).show()
                }
                return@Thread
            }
            val myNickname = configRepository.getNickname()
            val result = SmtpSender().send(
                fromAddress = myAddress,
                password = password,
                toAddress = contactAddress,
                body = body,
                senderNickname = myNickname
            )
            runOnUiThread {
                if (result is SmtpSender.Result.Error) {
                    chatRepository.updateMessageStatus(localId, ChatMessage.STATUS_ERROR)
                    messageAdapter.updateMessageStatus(localId, ChatMessage.STATUS_ERROR)
                    Snackbar.make(
                        binding.root,
                        getString(R.string.chat_send_error, result.message),
                        Snackbar.LENGTH_LONG
                    ).show()
                } else if (result is SmtpSender.Result.Success) {
                    // Store the exact timestamp written into the Date: header so the
                    // delivery-receipt timestamp match is exact (the recipient echoes
                    // the Date: value back as X-Tyr-Delivery-Timestamp).
                    if (result.dateHeaderTimestamp > 0) {
                        chatRepository.updateMessageTimestamp(localId, result.dateHeaderTimestamp)
                    }
                    // Status stays SENDING until delivery receipt arrives.
                }
            }
        }.start()
    }

    private fun fetchNewMessages() {
        if (!YggmailService.isRunning) return
        if (!isFetchInProgress.compareAndSet(false, true)) {
            return
        }
        val password = configRepository.getPassword() ?: run { isFetchInProgress.set(false); return }

        Thread {
            try {
            val sinceUid = chatRepository.getMaxImapUid()
            val result = ImapFetcher(cacheDir = cacheDir).fetchNewMessages(
                address = myAddress,
                password = password,
                sinceUid = sinceUid,
                attachmentsDir = attachmentsDir
            )
            if (result is ImapFetcher.Result.Success) {
                var changed = false

                for (receipt in result.data.deliveryReceiptTimestamps) {
                    chatRepository.updateSentMessageStatusNearTimestamp(
                        myAddress, receipt.senderAddr, receipt.originalTimestamp,
                        ChatMessage.STATUS_SENT, ChatMessage.STATUS_SENDING
                    )
                    changed = true
                }
                val newlyReceived = mutableListOf<ChatMessage>()
                for (msg in result.data.messages) {
                    if (!chatRepository.imapUidExists(msg.imapUid)) {
                        chatRepository.insertMessage(msg)
                        if (!msg.isSent) newlyReceived.add(msg)
                        changed = true
                    }
                }

                for (update in result.data.nicknameUpdates) {
                    val contact = chatRepository.getContact(update.senderAddr)
                    if (contact != null && contact.name.isBlank()) {
                        chatRepository.updateContactName(update.senderAddr, update.nickname)
                        if (update.senderAddr.equals(contactAddress, ignoreCase = true)) {
                            runOnUiThread {
                                supportActionBar?.title = update.nickname
                            }
                        }
                        changed = true
                    }
                }

                // Always reload from DB after a successful fetch.  The service may
                // have inserted rows (including photo attachments) before this fetch
                // started, so changed=false doesn't mean the view is up to date.
                val isPending = chatRepository.isPendingContact(contactAddress)
                if (!isPending) {
                    chatRepository.markConversationRead(myAddress, contactAddress)
                }
                runOnUiThread { loadMessages() }

                if (newlyReceived.isNotEmpty()) {
                    sendDeliveryReceipts(newlyReceived, password)
                }
            }
            } finally {
                isFetchInProgress.set(false)
            }
        }.start()
    }

    private fun sendDeliveryReceipts(messages: List<ChatMessage>, password: String) {
        Thread {
            for (msg in messages) {
                SmtpSender().send(
                    fromAddress = myAddress,
                    password = password,
                    toAddress = msg.fromAddr,
                    body = "",
                    deliveryReceiptTimestamp = msg.timestamp
                )
            }
        }.start()
    }
}
