package com.jbselfcompany.tyr.chat.network

import android.util.Base64
import com.jbselfcompany.tyr.utils.TyrLogger
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Minimal plain-SMTP client for sending chat messages via localhost:1025.
 * Adds X-Tyr-Chat: 1 header to distinguish from DeltaChat messages.
 * Adds X-Tyr-Read-Receipt: <uid> header for read receipts.
 * Supports MIME multipart/mixed for file attachments.
 */
class SmtpSender(
    private val host: String = "127.0.0.1",
    private val port: Int = 1025
) {
    companion object {
        private const val TAG = "SmtpSender"
        const val CHAT_HEADER = "X-Tyr-Chat: 1"
        private const val CHUNK_SIZE = 57  // 57 bytes → 76 base64 chars (standard line length)
    }

    data class AttachmentData(
        val file: File,
        val name: String,
        val mimeType: String
    )

    sealed class Result {
        /** @param dateHeaderTimestamp epoch-ms of the Date: header written into the message,
         *  or -1 for receipt/non-timestamped sends. Used by the caller to store the exact
         *  timestamp that the recipient will parse back as X-Tyr-Delivery-Timestamp. */
        data class Success(val dateHeaderTimestamp: Long = -1L) : Result()
        data class Error(val message: String) : Result()
    }

    fun send(
        fromAddress: String,
        password: String,
        toAddress: String,
        body: String,
        readReceiptForUid: Long = -1L,
        readReceiptTimestamp: Long = -1L,
        deliveryReceiptTimestamp: Long = -1L,
        senderNickname: String = "",
        attachment: AttachmentData? = null
    ): Result {
        val isReceipt = readReceiptForUid > 0 || readReceiptTimestamp > 0 || deliveryReceiptTimestamp > 0
        TyrLogger.d(TAG, "Sending ${if (isReceipt) "receipt" else "message"} to $toAddress" +
            if (attachment != null) " with attachment ${attachment.name}" else "")
        return try {
            Socket(host, port).use { socket ->
                // Extended timeout: go-smtp goroutine scheduling on Android (via gomobile)
                // can delay the 235 AUTH response well beyond 60s even on localhost.
                socket.soTimeout = 300_000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = PrintWriter(socket.getOutputStream(), true)

                fun readLine(): String = reader.readLine() ?: ""

                fun writeLine(line: String) { writer.println(line) }

                fun expectCode(code: String): Boolean {
                    var line = readLine()
                    while (line.length >= 4 && line[3] == '-') {
                        line = readLine()
                    }
                    return line.startsWith(code)
                }

                if (!expectCode("220")) {
                    TyrLogger.w(TAG, "SMTP greeting failed from $host:$port")
                    return Result.Error("SMTP greeting failed")
                }

                writeLine("EHLO android-tyr")
                if (!expectCode("250")) return Result.Error("EHLO failed")

                writeLine("AUTH LOGIN")
                val authPrompt1 = readLine()
                if (!authPrompt1.startsWith("334")) return Result.Error("AUTH LOGIN prompt failed")

                val userB64 = Base64.encodeToString(fromAddress.toByteArray(), Base64.NO_WRAP)
                writeLine(userB64)
                val authPrompt2 = readLine()
                if (!authPrompt2.startsWith("334")) return Result.Error("AUTH username failed")

                val passB64 = Base64.encodeToString(password.toByteArray(), Base64.NO_WRAP)
                writeLine(passB64)
                if (!expectCode("235")) {
                    TyrLogger.w(TAG, "SMTP AUTH failed for $fromAddress")
                    return Result.Error("AUTH password failed")
                }

                writeLine("MAIL FROM:<$fromAddress>")
                if (!expectCode("250")) return Result.Error("MAIL FROM failed")

                writeLine("RCPT TO:<$toAddress>")
                if (!expectCode("250")) return Result.Error("RCPT TO failed")

                writeLine("DATA")
                if (!expectCode("354")) return Result.Error("DATA command failed")

                // Capture the timestamp NOW — this is the value the recipient will parse
                // from the Date: header and echo back as X-Tyr-Delivery-Timestamp.
                // It must match what we store in the DB, so we capture it here rather than
                // before SmtpSender.send() is called (AUTH can take many seconds on Android
                // due to gomobile goroutine scheduling, causing a > 5 s drift from any
                // timestamp captured by the caller before the send starts).
                val dateHeaderTs = Date()
                val dateStr = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).format(dateHeaderTs)
                writeLine("From: <$fromAddress>")
                writeLine("To: <$toAddress>")
                writeLine("Subject: Tyr Chat")
                writeLine("Date: $dateStr")
                writeLine("MIME-Version: 1.0")
                writeLine(CHAT_HEADER)
                if (senderNickname.isNotBlank()) {
                    // RFC 2047: encode non-ASCII display names so the header stays
                    // 7-bit clean. Raw UTF-8 bytes in headers cause mojibake on the
                    // receiving side because IMAP is read back as ISO-8859-1.
                    val encodedNickname = if (senderNickname.any { it.code > 127 }) {
                        val b64 = Base64.encodeToString(
                            senderNickname.toByteArray(Charsets.UTF_8), Base64.NO_WRAP
                        )
                        "=?UTF-8?B?$b64?="
                    } else {
                        senderNickname
                    }
                    writeLine("X-Tyr-Nickname: $encodedNickname")
                }
                if (readReceiptForUid > 0) {
                    writeLine("X-Tyr-Read-Receipt: $readReceiptForUid")
                }
                if (readReceiptTimestamp > 0) {
                    writeLine("X-Tyr-Read-Receipt-Timestamp: $readReceiptTimestamp")
                }
                if (deliveryReceiptTimestamp > 0) {
                    writeLine("X-Tyr-Delivery-Timestamp: $deliveryReceiptTimestamp")
                }

                if (attachment != null && attachment.file.exists()) {
                    // MIME multipart/mixed with text + attachment
                    val boundary = "tyrbound_${UUID.randomUUID().toString().replace("-", "")}"
                    writeLine("Content-Type: multipart/mixed; boundary=\"$boundary\"")
                    writeLine("")

                    writeLine("--$boundary")
                    writeLine("Content-Type: text/plain; charset=UTF-8")
                    writeLine("Content-Transfer-Encoding: 8bit")
                    writeLine("")
                    body.lines().forEach { line ->
                        if (line.startsWith(".")) writeLine(".$line") else writeLine(line)
                    }
                    writeLine("")

                    writeLine("--$boundary")
                    val safeName = attachment.name.replace("\"", "'")
                    writeLine("Content-Type: ${attachment.mimeType}; name=\"$safeName\"")
                    writeLine("Content-Transfer-Encoding: base64")
                    writeLine("Content-Disposition: attachment; filename=\"$safeName\"")
                    writeLine("")

                    // Stream file in chunks — avoids loading the entire photo into memory
                    val buf = ByteArray(CHUNK_SIZE)
                    attachment.file.inputStream().use { fis ->
                        var read: Int
                        while (fis.read(buf).also { read = it } != -1) {
                            val encoded = Base64.encodeToString(buf, 0, read, Base64.NO_WRAP)
                            writeLine(encoded)
                        }
                    }
                    writeLine("")
                    writeLine("--$boundary--")
                } else {
                    writeLine("Content-Type: text/plain; charset=UTF-8")
                    writeLine("")
                    body.lines().forEach { line ->
                        if (line.startsWith(".")) writeLine(".$line") else writeLine(line)
                    }
                }

                writeLine(".")
                if (!expectCode("250")) return Result.Error("Message DATA failed")
                socket.soTimeout = 30_000  // reset for QUIT

                writeLine("QUIT")
                readLine() // 221 Bye

                TyrLogger.i(TAG, "Sent ${if (isReceipt) "receipt" else "message"} to $toAddress successfully")
                Result.Success(dateHeaderTimestamp = dateHeaderTs.time)
            }
        } catch (e: Exception) {
            TyrLogger.e(TAG, "SMTP send error to $toAddress", e)
            Result.Error(e.message ?: "Unknown error")
        }
    }
}
