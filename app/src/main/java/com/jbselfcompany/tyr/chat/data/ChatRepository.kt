package com.jbselfcompany.tyr.chat.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import com.jbselfcompany.tyr.chat.data.ChatDbHelper.Companion.COL_ADDRESS
import com.jbselfcompany.tyr.chat.data.ChatDbHelper.Companion.COL_ADDED_AT
import com.jbselfcompany.tyr.chat.data.ChatDbHelper.Companion.COL_BODY
import com.jbselfcompany.tyr.chat.data.ChatDbHelper.Companion.COL_FROM_ADDR
import com.jbselfcompany.tyr.chat.data.ChatDbHelper.Companion.COL_ID
import com.jbselfcompany.tyr.chat.data.ChatDbHelper.Companion.COL_IMAP_UID
import com.jbselfcompany.tyr.chat.data.ChatDbHelper.Companion.COL_IS_DECLINED
import com.jbselfcompany.tyr.chat.data.ChatDbHelper.Companion.COL_IS_PENDING
import com.jbselfcompany.tyr.chat.data.ChatDbHelper.Companion.COL_IS_READ
import com.jbselfcompany.tyr.chat.data.ChatDbHelper.Companion.COL_IS_SENT
import com.jbselfcompany.tyr.chat.data.ChatDbHelper.Companion.COL_NAME
import com.jbselfcompany.tyr.chat.data.ChatDbHelper.Companion.COL_ATTACHMENT_MIME
import com.jbselfcompany.tyr.chat.data.ChatDbHelper.Companion.COL_ATTACHMENT_NAME
import com.jbselfcompany.tyr.chat.data.ChatDbHelper.Companion.COL_ATTACHMENT_PATH
import com.jbselfcompany.tyr.chat.data.ChatDbHelper.Companion.COL_ATTACHMENT_SIZE
import com.jbselfcompany.tyr.chat.data.ChatDbHelper.Companion.COL_STATUS
import com.jbselfcompany.tyr.chat.data.ChatDbHelper.Companion.COL_TIMESTAMP
import com.jbselfcompany.tyr.chat.data.ChatDbHelper.Companion.COL_TO_ADDR
import com.jbselfcompany.tyr.chat.data.ChatDbHelper.Companion.TABLE_CONTACTS
import com.jbselfcompany.tyr.chat.data.ChatDbHelper.Companion.TABLE_MESSAGES

class ChatRepository(context: Context) {

    private val db = ChatDbHelper(context)

    // ---- Contacts ----

    fun addContact(contact: ChatContact): Long {
        val addr = contact.address.trim().lowercase()
        val cv = ContentValues().apply {
            put(COL_ADDRESS, addr)
            put(COL_NAME, contact.name.trim())
            put(COL_ADDED_AT, contact.addedAt)
            put(COL_IS_PENDING, 0)
            put(COL_IS_DECLINED, 0)
        }
        // CONFLICT_REPLACE removes an existing row (including declined block records) and
        // inserts the new one, allowing a previously-deleted contact to be re-added cleanly.
        return db.writableDatabase.insertWithOnConflict(
            TABLE_CONTACTS, null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    /** Add a contact that requires user approval before replying. */
    fun addPendingContact(contact: ChatContact): Long {
        val cv = ContentValues().apply {
            put(COL_ADDRESS, contact.address.trim().lowercase())
            put(COL_NAME, contact.name.trim())
            put(COL_ADDED_AT, contact.addedAt)
            put(COL_IS_PENDING, 1)
        }
        return db.writableDatabase.insertWithOnConflict(
            TABLE_CONTACTS, null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    /** Mark a pending contact as accepted (clears isPending flag). */
    fun acceptPendingContact(address: String) {
        val cv = ContentValues().apply { put(COL_IS_PENDING, 0) }
        db.writableDatabase.update(
            TABLE_CONTACTS, cv,
            "$COL_ADDRESS = ?", arrayOf(address.trim().lowercase())
        )
    }

    fun isPendingContact(address: String): Boolean {
        db.readableDatabase.query(
            TABLE_CONTACTS, arrayOf(COL_IS_PENDING),
            "$COL_ADDRESS = ?", arrayOf(address.trim().lowercase()),
            null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) return cursor.getInt(0) == 1
        }
        return false
    }

    fun getAllContacts(): List<ChatContact> {
        val result = mutableListOf<ChatContact>()
        db.readableDatabase.query(
            TABLE_CONTACTS, null,
            "$COL_IS_DECLINED = 0", null, null, null, "$COL_ADDED_AT ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) result.add(cursor.toContact())
        }
        return result
    }

    fun getContact(address: String): ChatContact? {
        db.readableDatabase.query(
            TABLE_CONTACTS, null,
            "$COL_ADDRESS = ?", arrayOf(address.trim().lowercase()),
            null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) return cursor.toContact()
        }
        return null
    }

    fun updateContactName(address: String, name: String) {
        val cv = ContentValues().apply { put(COL_NAME, name.trim()) }
        db.writableDatabase.update(
            TABLE_CONTACTS, cv,
            "$COL_ADDRESS = ?", arrayOf(address.trim().lowercase())
        )
    }

    fun deleteContact(address: String) {
        val addr = address.trim().lowercase()
        // Mark as declined instead of physically deleting the row. This keeps a block record
        // so that pollInboxForNewMessages() silently drops any future messages from this address
        // (isContactDeclined() is checked before contactExists()). Without this, a post-backup
        // delete followed by a poll fetching old UIDs would re-create the contact via addPendingContact().
        val cv = ContentValues().apply { put(COL_IS_DECLINED, 1) }
        val updated = db.writableDatabase.update(TABLE_CONTACTS, cv, "$COL_ADDRESS = ?", arrayOf(addr))
        if (updated == 0) {
            // Contact row didn't exist (e.g. deleted from outside normal flow) — insert a block placeholder
            // so messages from this address are still suppressed on the next poll.
            val insert = ContentValues().apply {
                put(COL_ADDRESS, addr)
                put(COL_NAME, "")
                put(COL_ADDED_AT, System.currentTimeMillis())
                put(COL_IS_PENDING, 0)
                put(COL_IS_DECLINED, 1)
            }
            db.writableDatabase.insertWithOnConflict(
                TABLE_CONTACTS, null, insert, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
            )
        }
        db.writableDatabase.delete(
            TABLE_MESSAGES,
            "$COL_FROM_ADDR = ? OR $COL_TO_ADDR = ?",
            arrayOf(addr, addr)
        )
    }

    /**
     * Decline a pending contact: marks the contact row as declined (so the poller
     * ignores future messages from this address) and deletes all their messages.
     * The contact row is kept as a block record — getAllContacts() filters it out.
     */
    fun declineContact(address: String) {
        val addr = address.trim().lowercase()
        val cv = ContentValues().apply { put(COL_IS_DECLINED, 1) }
        db.writableDatabase.update(TABLE_CONTACTS, cv, "$COL_ADDRESS = ?", arrayOf(addr))
        db.writableDatabase.delete(
            TABLE_MESSAGES,
            "$COL_FROM_ADDR = ? OR $COL_TO_ADDR = ?",
            arrayOf(addr, addr)
        )
    }

    fun isContactDeclined(address: String): Boolean {
        db.readableDatabase.query(
            TABLE_CONTACTS, arrayOf(COL_IS_DECLINED),
            "$COL_ADDRESS = ?", arrayOf(address.trim().lowercase()),
            null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) return cursor.getInt(0) == 1
        }
        return false
    }

    fun contactExists(address: String): Boolean =
        getContact(address) != null

    // ---- Messages ----

    fun insertMessage(message: ChatMessage): Long {
        val cv = ContentValues().apply {
            put(COL_IMAP_UID, message.imapUid)
            put(COL_FROM_ADDR, message.fromAddr)
            put(COL_TO_ADDR, message.toAddr)
            put(COL_BODY, message.body)
            put(COL_TIMESTAMP, message.timestamp)
            put(COL_IS_READ, if (message.isRead) 1 else 0)
            put(COL_IS_SENT, if (message.isSent) 1 else 0)
            put(COL_STATUS, message.status)
            put(COL_ATTACHMENT_PATH, message.attachmentPath)
            put(COL_ATTACHMENT_NAME, message.attachmentName)
            put(COL_ATTACHMENT_MIME, message.attachmentMimeType)
            put(COL_ATTACHMENT_SIZE, message.attachmentSizeBytes)
        }
        return db.writableDatabase.insert(TABLE_MESSAGES, null, cv)
    }

    fun deleteMessage(id: Long) {
        db.writableDatabase.delete(TABLE_MESSAGES, "$COL_ID = ?", arrayOf(id.toString()))
    }

    fun updateMessageTimestamp(messageId: Long, timestamp: Long) {
        val cv = ContentValues().apply { put(COL_TIMESTAMP, timestamp) }
        db.writableDatabase.update(TABLE_MESSAGES, cv, "$COL_ID = ?", arrayOf(messageId.toString()))
    }

    fun updateMessageStatus(messageId: Long, status: Int) {
        val cv = ContentValues().apply { put(COL_STATUS, status) }
        db.writableDatabase.update(TABLE_MESSAGES, cv, "$COL_ID = ?", arrayOf(messageId.toString()))
    }

    fun updateMessageStatusByImapUid(imapUid: Long, status: Int) {
        if (imapUid < 0) return
        val cv = ContentValues().apply { put(COL_STATUS, status) }
        db.writableDatabase.update(TABLE_MESSAGES, cv, "$COL_IMAP_UID = ?", arrayOf(imapUid.toString()))
    }

    /**
     * Update status of a sent message identified by (sender=myAddr, recipient=peerAddr, timestamp≈ts).
     * Used for delivery receipts that carry the original message timestamp instead of an IMAP UID.
     * Tolerance is ±5 seconds; only the single closest row by timestamp is updated to avoid
     * mis-attributing the status when two messages are sent in quick succession.
     *
     * @param maxCurrentStatus Only updates messages whose current status <= maxCurrentStatus.
     * Pass ChatMessage.STATUS_SENDING for delivery receipts (don't downgrade STATUS_SENT).
     */
    fun updateSentMessageStatusNearTimestamp(
        myAddr: String, peerAddr: String, timestamp: Long, status: Int,
        maxCurrentStatus: Int = Int.MAX_VALUE
    ) {
        val my = myAddr.trim().lowercase()
        val peer = peerAddr.trim().lowercase()
        val toleranceMs = 5_000L
        val statusClause = if (maxCurrentStatus < Int.MAX_VALUE) " AND $COL_STATUS <= $maxCurrentStatus" else ""
        // Update only the single nearest row to avoid tagging the wrong message when two
        // outgoing messages have timestamps within the tolerance window.
        val sql = "UPDATE $TABLE_MESSAGES SET $COL_STATUS = ? " +
            "WHERE $COL_ID = (" +
            "SELECT $COL_ID FROM $TABLE_MESSAGES " +
            "WHERE $COL_FROM_ADDR = ? AND $COL_TO_ADDR = ? AND $COL_IS_SENT = 1 " +
            "AND ABS($COL_TIMESTAMP - ?) <= $toleranceMs" + statusClause +
            " ORDER BY ABS($COL_TIMESTAMP - ?) ASC LIMIT 1)"
        db.writableDatabase.execSQL(sql, arrayOf(status, my, peer, timestamp, timestamp))
    }

    fun getConversation(myAddress: String, peerAddress: String): List<ChatMessage> {
        val result = mutableListOf<ChatMessage>()
        val my = myAddress.trim().lowercase()
        val peer = peerAddress.trim().lowercase()
        db.readableDatabase.query(
            TABLE_MESSAGES, null,
            "($COL_FROM_ADDR = ? AND $COL_TO_ADDR = ?) OR ($COL_FROM_ADDR = ? AND $COL_TO_ADDR = ?)",
            arrayOf(my, peer, peer, my),
            null, null, "$COL_TIMESTAMP ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) result.add(cursor.toMessage())
        }
        return result
    }

    fun getLastMessage(myAddress: String, peerAddress: String): ChatMessage? {
        val my = myAddress.trim().lowercase()
        val peer = peerAddress.trim().lowercase()
        db.readableDatabase.query(
            TABLE_MESSAGES, null,
            "($COL_FROM_ADDR = ? AND $COL_TO_ADDR = ?) OR ($COL_FROM_ADDR = ? AND $COL_TO_ADDR = ?)",
            arrayOf(my, peer, peer, my),
            null, null, "$COL_TIMESTAMP DESC", "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) return cursor.toMessage()
        }
        return null
    }

    fun getUnreadCount(myAddress: String, peerAddress: String): Int {
        val peer = peerAddress.trim().lowercase()
        val my = myAddress.trim().lowercase()
        db.readableDatabase.query(
            TABLE_MESSAGES, arrayOf("COUNT(*)"),
            "$COL_FROM_ADDR = ? AND $COL_TO_ADDR = ? AND $COL_IS_READ = 0",
            arrayOf(peer, my),
            null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) return cursor.getInt(0)
        }
        return 0
    }

    fun markConversationRead(myAddress: String, peerAddress: String) {
        val peer = peerAddress.trim().lowercase()
        val my = myAddress.trim().lowercase()
        val cv = ContentValues().apply { put(COL_IS_READ, 1) }
        db.writableDatabase.update(
            TABLE_MESSAGES, cv,
            "$COL_FROM_ADDR = ? AND $COL_TO_ADDR = ? AND $COL_IS_READ = 0",
            arrayOf(peer, my)
        )
    }

    fun imapUidExists(uid: Long): Boolean {
        if (uid < 0) return false
        db.readableDatabase.query(
            TABLE_MESSAGES, arrayOf("COUNT(*)"),
            "$COL_IMAP_UID = ?", arrayOf(uid.toString()),
            null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) return cursor.getInt(0) > 0
        }
        return false
    }

    /**
     * Returns the number of distinct conversations that have unread messages.
     * Includes pending contacts (their unread messages count towards the badge).
     */
    fun getUnreadChatCount(myAddress: String): Int {
        val my = myAddress.trim().lowercase()
        db.readableDatabase.query(
            TABLE_MESSAGES,
            arrayOf("COUNT(DISTINCT $COL_FROM_ADDR)"),
            "$COL_TO_ADDR = ? AND $COL_IS_READ = 0 AND $COL_IS_SENT = 0",
            arrayOf(my),
            null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) return cursor.getInt(0)
        }
        return 0
    }

    fun getMaxImapUid(): Long {
        db.readableDatabase.query(
            TABLE_MESSAGES, arrayOf("MAX($COL_IMAP_UID)"),
            "$COL_IMAP_UID >= 0", null, null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        return 0L
    }

    private fun Cursor.toContact() = ChatContact(
        id = getLong(getColumnIndexOrThrow(COL_ID)),
        address = getString(getColumnIndexOrThrow(COL_ADDRESS)),
        name = getString(getColumnIndexOrThrow(COL_NAME)),
        addedAt = getLong(getColumnIndexOrThrow(COL_ADDED_AT)),
        isPending = run {
            val idx = getColumnIndex(COL_IS_PENDING)
            if (idx >= 0) getInt(idx) == 1 else false
        },
        isDeclined = run {
            val idx = getColumnIndex(COL_IS_DECLINED)
            if (idx >= 0) getInt(idx) == 1 else false
        }
    )

    private fun Cursor.toMessage(): ChatMessage {
        val statusIdx = getColumnIndex(COL_STATUS)
        val attachPathIdx = getColumnIndex(COL_ATTACHMENT_PATH)
        val attachNameIdx = getColumnIndex(COL_ATTACHMENT_NAME)
        val attachMimeIdx = getColumnIndex(COL_ATTACHMENT_MIME)
        val attachSizeIdx = getColumnIndex(COL_ATTACHMENT_SIZE)
        return ChatMessage(
            id = getLong(getColumnIndexOrThrow(COL_ID)),
            imapUid = getLong(getColumnIndexOrThrow(COL_IMAP_UID)),
            fromAddr = getString(getColumnIndexOrThrow(COL_FROM_ADDR)),
            toAddr = getString(getColumnIndexOrThrow(COL_TO_ADDR)),
            body = getString(getColumnIndexOrThrow(COL_BODY)),
            timestamp = getLong(getColumnIndexOrThrow(COL_TIMESTAMP)),
            isRead = getInt(getColumnIndexOrThrow(COL_IS_READ)) == 1,
            isSent = getInt(getColumnIndexOrThrow(COL_IS_SENT)) == 1,
            status = if (statusIdx >= 0) getInt(statusIdx) else ChatMessage.STATUS_SENT,
            attachmentPath = if (attachPathIdx >= 0) getString(attachPathIdx) else null,
            attachmentName = if (attachNameIdx >= 0) getString(attachNameIdx) else null,
            attachmentMimeType = if (attachMimeIdx >= 0) getString(attachMimeIdx) else null,
            attachmentSizeBytes = if (attachSizeIdx >= 0) getLong(attachSizeIdx) else 0L
        )
    }
}
