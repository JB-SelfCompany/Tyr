package com.jbselfcompany.tyr.chat.network

import android.util.Base64
import com.jbselfcompany.tyr.chat.data.ChatMessage
import com.jbselfcompany.tyr.utils.TyrLogger
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import java.nio.charset.Charset

/**
 * Minimal plain-IMAP client for fetching chat messages from localhost:1143.
 * Only returns messages that have the X-Tyr-Chat: 1 header.
 * Supports MIME multipart/mixed for incoming file attachments.
 *
 * Large IMAP literals (> LARGE_LITERAL_THRESHOLD bytes) are streamed to a
 * temporary file instead of being held in memory as a String.  This prevents
 * OOM crashes when receiving multi-megabyte attachments on Android.
 */
class ImapFetcher(
    private val host: String = "127.0.0.1",
    private val port: Int = 1143,
    private val cacheDir: File? = null
) {
    companion object {
        private const val TAG = "ImapFetcher"
        private const val CHAT_HEADER_KEY = "x-tyr-chat"
        private const val CHAT_HEADER_VALUE = "1"
        private const val READ_RECEIPT_HEADER_KEY = "x-tyr-read-receipt"
        private const val READ_RECEIPT_TIMESTAMP_KEY = "x-tyr-read-receipt-timestamp"
        private const val DELIVERY_RECEIPT_TIMESTAMP_KEY = "x-tyr-delivery-timestamp"
        private const val NICKNAME_HEADER_KEY = "x-tyr-nickname"

        /** Literals larger than this are written to a temp file rather than kept in a String. */
        private const val LARGE_LITERAL_THRESHOLD = 512L * 1024L  // 512 KB (Long to avoid Int overflow on size comparison)

        /** Sentinel prefix in fetchLines for a large literal stored on disk. */
        private const val LITERAL_FILE_PREFIX = "LITERAL_FILE:"

        /** Sentinel prefix in fetchLines for a small literal kept in memory. */
        private const val LITERAL_PREFIX = "LITERAL:"

        /**
         * Number of base64 lines decoded per chunk.
         * 4 lines × 76 chars/line = 304 chars → 228 decoded bytes.
         * Must be a multiple of 4 so each chunk is self-contained (no padding issues).
         */
        private const val BASE64_CHUNK_LINES = 4096  // ~300 KB decoded per chunk
    }

    data class ReadReceiptByTimestamp(val senderAddr: String, val originalTimestamp: Long)
    data class NicknameUpdate(val senderAddr: String, val nickname: String)

    data class FetchResult(
        val messages: List<ChatMessage>,
        val readReceiptUids: List<Long>,
        val readReceiptTimestamps: List<ReadReceiptByTimestamp> = emptyList(),
        val deliveryReceiptTimestamps: List<ReadReceiptByTimestamp> = emptyList(),
        val nicknameUpdates: List<NicknameUpdate> = emptyList()
    )

    sealed class Result {
        data class Success(val data: FetchResult) : Result()
        data class Error(val message: String) : Result()
    }

    private var tagCounter = 1
    private fun nextTag() = "t${tagCounter++}"

    fun fetchNewMessages(
        address: String,
        password: String,
        sinceUid: Long = 0L,
        attachmentsDir: File? = null
    ): Result {
        return try {
            TyrLogger.d(TAG, "Connecting to IMAP $host:$port (sinceUid=$sinceUid)")
            Socket(host, port).use { socket ->
                // Large messages (photos, files) stream megabytes of base64 over the
                // IMAP socket.  120 s gives room for a 4–5 MB attachment at even very
                // low throughput.
                socket.soTimeout = 120_000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1), 256 * 1024)
                val writer = PrintWriter(socket.getOutputStream(), true)
                tagCounter = 1

                // Track temp files created for large literals so we can delete them when done.
                val tempFiles = mutableListOf<File>()

                // Reads one line from the IMAP stream (strips trailing CR).
                fun readLine(): String? = reader.readLine()

                // Reads exactly n bytes from the stream as a String (ISO-8859-1 so
                // byte values are preserved 1-to-1 for later base64 processing).
                // Per RFC 3501 §4.3, an IMAP server terminates a literal with CRLF
                // immediately after the last byte.  BufferedReader.readLine() — which
                // we call for the *next* protocol line — already strips that CRLF, so
                // we do NOT consume any extra bytes here.  The next readLine() call
                // will consume whatever follows the literal (the CRLF terminator or
                // the next protocol line token like " BODY[TEXT]...").
                fun readLiteralBytes(n: Int): String {
                    val buf = CharArray(n)
                    var read = 0
                    while (read < n) {
                        val got = reader.read(buf, read, n - read)
                        if (got < 0) throw java.io.IOException("IMAP stream closed inside literal (wanted $n bytes, got $read)")
                        read += got
                    }
                    return String(buf)
                }

                /**
                 * For large literals: stream directly from the IMAP reader into a temp file.
                 * Uses a char[] read loop with a fixed buffer to avoid allocating a single
                 * n-char array.  Returns the temp File.
                 *
                 * The file is written in ISO-8859-1 (one byte per char) which preserves
                 * the base64 ASCII content exactly.
                 */
                fun readLiteralToFile(n: Long): File {
                    val tempDir = cacheDir ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp")
                    tempDir.mkdirs()
                    val tempFile = File.createTempFile("imap_literal_", ".tmp", tempDir)
                    tempFiles.add(tempFile)
                    TyrLogger.d(TAG, "Large literal $n bytes → temp file ${tempFile.name}")
                    val charBuf = CharArray(64 * 1024)  // 64 KB read buffer
                    var remaining = n
                    FileOutputStream(tempFile).use { fos ->
                        while (remaining > 0) {
                            val toRead = minOf(charBuf.size.toLong(), remaining).toInt()
                            val got = reader.read(charBuf, 0, toRead)
                            if (got < 0) throw java.io.IOException(
                                "IMAP stream closed inside large literal (wanted $n bytes, remaining $remaining)"
                            )
                            // Write each char as one byte (ISO-8859-1: all values 0–255 map 1:1)
                            for (ci in 0 until got) {
                                fos.write(charBuf[ci].code and 0xFF)
                            }
                            remaining -= got
                        }
                    }
                    TyrLogger.d(TAG, "Large literal written: size=${tempFile.length()} path=${tempFile.absolutePath}")
                    return tempFile
                }

                // Simple command: collects text lines until the tagged response.
                // Safe for SELECT, LOGIN, LOGOUT — these never contain IMAP literals.
                fun cmd(command: String): List<String> {
                    val tag = nextTag()
                    writer.println("$tag $command")
                    val lines = mutableListOf<String>()
                    while (true) {
                        val line = readLine()
                            ?: throw java.io.IOException("IMAP connection closed unexpectedly reading response to: $command")
                        lines.add(line)
                        if (line.startsWith("$tag OK") || line.startsWith("$tag NO") ||
                            line.startsWith("$tag BAD")) break
                    }
                    return lines
                }

                readLine() ?: throw java.io.IOException("IMAP connection closed before greeting")

                val loginTag = nextTag()
                writer.println("$loginTag LOGIN \"${address.escapeImap()}\" \"${password.escapeImap()}\"")
                val loginLines = mutableListOf<String>()
                while (true) {
                    val l = readLine()
                        ?: throw java.io.IOException("IMAP connection closed during LOGIN")
                    loginLines.add(l)
                    if (l.startsWith("$loginTag OK") || l.startsWith("$loginTag NO") || l.startsWith("$loginTag BAD")) break
                }
                if (loginLines.last().startsWith("$loginTag NO") ||
                    loginLines.last().startsWith("$loginTag BAD") ||
                    loginLines.none { it.contains("OK", ignoreCase = true) }) {
                    try { cmd("LOGOUT") } catch (_: Exception) {}
                    TyrLogger.w(TAG, "IMAP LOGIN failed for $address")
                    return Result.Error("IMAP LOGIN failed")
                }

                val selectResp = cmd("SELECT TyrChat")
                if (selectResp.none { it.contains("OK", ignoreCase = true) }) {
                    try { cmd("LOGOUT") } catch (_: Exception) {}
                    TyrLogger.d(TAG, "TyrChat mailbox not yet available, no messages to fetch")
                    return Result.Success(FetchResult(emptyList(), emptyList()))
                }

                val existsLine = selectResp.firstOrNull { it.contains("EXISTS") }
                val existsCount = existsLine
                    ?.let { Regex("""(\d+)\s+EXISTS""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                    ?: 0
                TyrLogger.i(TAG, "TyrChat EXISTS=$existsCount sinceUid=$sinceUid")
                if (existsCount == 0) {
                    try { cmd("LOGOUT") } catch (_: Exception) {}
                    TyrLogger.d(TAG, "TyrChat mailbox empty, nothing to fetch")
                    return Result.Success(FetchResult(emptyList(), emptyList()))
                }

                // UID FETCH — stream-parsed to correctly handle IMAP literal syntax {N}.
                val uidRange = if (sinceUid > 0) "${sinceUid + 1}:*" else "1:*"
                TyrLogger.i(TAG, "Fetching UID range $uidRange from TyrChat")
                val fetchTag = nextTag()
                writer.println(
                    "$fetchTag UID FETCH $uidRange (UID FLAGS " +
                    "BODY.PEEK[HEADER.FIELDS (FROM TO DATE X-TYR-CHAT X-TYR-READ-RECEIPT " +
                    "X-TYR-READ-RECEIPT-TIMESTAMP X-TYR-DELIVERY-TIMESTAMP X-TYR-NICKNAME CONTENT-TYPE)] " +
                    "BODY.PEEK[TEXT])"
                )

                // Literal-aware line reader: if a line ends with {N} the next N bytes
                // are read as a single blob.
                // - Small literals (≤ LARGE_LITERAL_THRESHOLD): kept in memory as
                //   "LITERAL:<content>" — same as before.
                // - Large literals (> LARGE_LITERAL_THRESHOLD): written to a temp file
                //   and referenced as "LITERAL_FILE:<path>" — avoids holding megabytes
                //   of base64 text in the JVM heap.
                // Returns null when the tag-completion line has been consumed.
                val fetchLines = mutableListOf<String>()
                var fetchDone = false
                while (!fetchDone) {
                    val line = readLine()
                        ?: throw java.io.IOException("IMAP connection closed while reading FETCH response")
                    if (line.startsWith("$fetchTag OK") || line.startsWith("$fetchTag NO") ||
                        line.startsWith("$fetchTag BAD")) {
                        TyrLogger.d(TAG, "FETCH complete: $line (lines=${fetchLines.size})")
                        fetchDone = true
                        break
                    }
                    // Check for IMAP literal: line ends with {<digits>} optionally followed by whitespace
                    val literalMatch = Regex("""\{(\d+)\}\s*$""").find(line)
                    if (literalMatch != null) {
                        // Parse as Long to avoid Int overflow for very large literals (>2 GB).
                        val literalSize = literalMatch.groupValues[1].toLongOrNull() ?: 0L
                        fetchLines.add(line)
                        TyrLogger.d(TAG, "IMAP literal: $literalSize bytes announced on: ${line.take(80)}")
                        if (literalSize > LARGE_LITERAL_THRESHOLD) {
                            // Stream to temp file — avoid building a huge String in memory.
                            val tempFile = readLiteralToFile(literalSize)
                            fetchLines.add("$LITERAL_FILE_PREFIX${tempFile.absolutePath}")
                            TyrLogger.d(TAG, "Large literal streamed: size=$literalSize path=${tempFile.absolutePath}")
                        } else {
                            // Small literal — keep in memory as before.
                            val literalContent = readLiteralBytes(literalSize.toInt())
                            fetchLines.add("$LITERAL_PREFIX$literalContent")
                            TyrLogger.d(TAG, "Literal read: chars=${literalContent.length}")
                        }
                    } else {
                        fetchLines.add(line)
                    }
                }
                TyrLogger.i(TAG, "FETCH response: ${fetchLines.size} lines received")

                val fetchResult = try {
                    parseFetchResponseFull(fetchLines, address, attachmentsDir)
                } finally {
                    // Always delete temp files, even if parsing threw an exception.
                    for (tf in tempFiles) {
                        if (tf.exists()) {
                            tf.delete()
                        }
                    }
                }

                try { cmd("LOGOUT") } catch (_: Exception) {}

                TyrLogger.i(TAG, "Fetch complete: ${fetchResult.messages.size} messages, " +
                    "${fetchResult.readReceiptUids.size} read receipts, " +
                    "${fetchResult.deliveryReceiptTimestamps.size} delivery receipts")
                Result.Success(fetchResult)
            }
        } catch (e: Exception) {
            TyrLogger.e(TAG, "IMAP fetch error", e)
            Result.Error(e.message ?: "Unknown error")
        }
    }

    private fun String.escapeImap(): String = replace("\\", "\\\\").replace("\"", "\\\"")

    /**
     * Deletes all messages sent FROM [senderAddress] in the TyrChat mailbox on the local
     * IMAP server.  Uses UID SEARCH so it finds even messages that arrived after the last
     * backup was taken and therefore are not present in the local SQLite DB.
     *
     * This is the root fix for deleted chats reappearing: once the messages are gone from
     * the IMAP store they can never be re-fetched, regardless of sinceUid or DB state.
     *
     * @return true if the operation succeeded (including "nothing to delete"), false on error.
     */
    fun deleteMessagesBySender(
        myAddress: String,
        password: String,
        senderAddress: String
    ): Boolean {
        return try {
            TyrLogger.d(TAG, "IMAP purge: connecting to $host:$port to delete messages from $senderAddress")
            Socket(host, port).use { socket ->
                socket.soTimeout = 30_000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
                val writer = PrintWriter(socket.getOutputStream(), true)
                var tc = 1
                fun nextTag() = "t${tc++}"
                fun readLine(): String? = reader.readLine()
                fun cmd(command: String): List<String> {
                    val tag = nextTag()
                    writer.println("$tag $command")
                    val lines = mutableListOf<String>()
                    while (true) {
                        val line = readLine()
                            ?: throw java.io.IOException("IMAP connection closed during: $command")
                        lines.add(line)
                        if (line.startsWith("$tag OK") || line.startsWith("$tag NO") ||
                            line.startsWith("$tag BAD")) break
                    }
                    return lines
                }

                readLine() ?: return@use false

                val loginTag = nextTag()
                writer.println("$loginTag LOGIN \"${myAddress.escapeImap()}\" \"${password.escapeImap()}\"")
                val loginLines = mutableListOf<String>()
                while (true) {
                    val l = readLine() ?: break
                    loginLines.add(l)
                    if (l.startsWith("$loginTag OK") || l.startsWith("$loginTag NO") ||
                        l.startsWith("$loginTag BAD")) break
                }
                if (loginLines.none { it.contains("OK", ignoreCase = true) }) {
                    TyrLogger.w(TAG, "IMAP purge: LOGIN failed for $myAddress")
                    return@use false
                }

                val selectResp = cmd("SELECT TyrChat")
                if (selectResp.none { it.contains("OK", ignoreCase = true) }) {
                    try { cmd("LOGOUT") } catch (_: Exception) {}
                    TyrLogger.d(TAG, "IMAP purge: TyrChat mailbox not yet present, nothing to delete")
                    return@use true
                }

                // UID SEARCH FROM "senderAddress" — finds ALL messages from this sender,
                // including those not in our local DB (e.g. arrived after last backup).
                val searchResp = cmd("UID SEARCH FROM \"${senderAddress.escapeImap()}\"")
                val searchLine = searchResp.firstOrNull { it.startsWith("* SEARCH") } ?: ""
                val uids = searchLine.removePrefix("* SEARCH").trim()
                    .split(" ")
                    .mapNotNull { it.trim().toLongOrNull() }
                    .filter { it > 0 }

                TyrLogger.i(TAG, "IMAP purge: found ${uids.size} message(s) from $senderAddress")

                if (uids.isNotEmpty()) {
                    val uidSet = uids.joinToString(",")
                    cmd("UID STORE $uidSet +FLAGS (\\Deleted)")
                    cmd("EXPUNGE")
                    TyrLogger.i(TAG, "IMAP purge: deleted ${uids.size} message(s) from $senderAddress")
                }

                try { cmd("LOGOUT") } catch (_: Exception) {}
                true
            }
        } catch (e: Exception) {
            TyrLogger.e(TAG, "IMAP purge error for $senderAddress", e)
            false
        }
    }

    private fun parseFetchResponseFull(
        lines: List<String>,
        myAddress: String,
        attachmentsDir: File?
    ): FetchResult {
        val messages = mutableListOf<ChatMessage>()
        val readReceiptUids = mutableListOf<Long>()
        val readReceiptTimestamps = mutableListOf<ReadReceiptByTimestamp>()
        val deliveryReceiptTimestamps = mutableListOf<ReadReceiptByTimestamp>()
        val nicknameUpdates = mutableListOf<NicknameUpdate>()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]

            if (!line.startsWith("*") || !line.contains("FETCH", ignoreCase = true)) {
                i++
                continue
            }

            val uidMatch = Regex("""UID\s+(\d+)""", RegexOption.IGNORE_CASE).find(line)
            var uid = uidMatch?.groupValues?.get(1)?.toLongOrNull() ?: -1L

            var from = ""
            var to = ""
            var timestamp = System.currentTimeMillis()
            var isTyrChat = false
            var readReceiptForUid = -1L
            var readReceiptForTimestamp = -1L
            var deliveryReceiptForTimestamp = -1L
            var senderNickname = ""
            var contentType = ""

            // bodyLines is only populated for small messages (no LITERAL_FILE).
            // For large body literals we use bodyLiteralFile instead.
            val bodyLines = mutableListOf<String>()
            var bodyLiteralFile: File? = null

            // Whether we are currently accumulating lines from a BODY[HEADER.FIELDS] section.
            // Reset to false by an empty line (end-of-headers) or a new BODY section announcement.
            var collectingHeader = line.contains("BODY[HEADER", ignoreCase = true) && line.contains("{")
            var collectingBody = false

            // Applies one header line to the current message fields.
            // Called for both inline (non-literal) lines and for lines split out of a LITERAL: blob.
            fun applyHeaderLine(l: String) {
                val lower = l.lowercase()
                if (lower.isNotEmpty() && (lower[0] == ' ' || lower[0] == '\t')) {
                    // Folded header continuation — only Content-Type matters for multipart boundary
                    if (contentType.isNotEmpty() && !lower.trimStart().startsWith("x-tyr")) {
                        contentType += " " + l.trim()
                    }
                } else when {
                    lower.startsWith("from:") ->
                        from = extractEmailAddr(l.substringAfter(":").trim())
                    lower.startsWith("to:") ->
                        to = extractEmailAddr(l.substringAfter(":").trim())
                    lower.startsWith("date:") ->
                        timestamp = parseImapDate(l.substringAfter(":").trim())
                    lower.startsWith("$CHAT_HEADER_KEY:") ->
                        isTyrChat = l.substringAfter(":").trim() == CHAT_HEADER_VALUE
                    lower.startsWith("$READ_RECEIPT_HEADER_KEY:") ->
                        readReceiptForUid = l.substringAfter(":").trim().toLongOrNull() ?: -1L
                    lower.startsWith("$READ_RECEIPT_TIMESTAMP_KEY:") ->
                        readReceiptForTimestamp = l.substringAfter(":").trim().toLongOrNull() ?: -1L
                    lower.startsWith("$DELIVERY_RECEIPT_TIMESTAMP_KEY:") ->
                        deliveryReceiptForTimestamp = l.substringAfter(":").trim().toLongOrNull() ?: -1L
                    lower.startsWith("$NICKNAME_HEADER_KEY:") ->
                        senderNickname = decodeHeaderValue(l.substringAfter(":").trim())
                    lower.startsWith("content-type:") ->
                        contentType = l.substringAfter(":").trim()
                }
            }

            i++
            while (i < lines.size) {
                val l = lines[i]

                // Plain end-of-record markers (present on the non-literal small-message path)
                if (l == ")" || l.matches(Regex("t\\d+ (OK|NO|BAD).*"))) break

                when {
                    // ── Large literal stored on disk ──────────────────────────────────────
                    l.startsWith(LITERAL_FILE_PREFIX) -> {
                        val filePath = l.removePrefix(LITERAL_FILE_PREFIX)
                        val literalFile = File(filePath)
                        when {
                            collectingHeader -> {
                                // Header stored in file: rare for small headers, but handle it.
                                // Read line-by-line without loading the whole file.
                                literalFile.bufferedReader(Charsets.ISO_8859_1).use { br ->
                                    var hLine = br.readLine()
                                    while (hLine != null) {
                                        if (hLine.isEmpty()) {
                                            collectingHeader = false
                                        } else {
                                            applyHeaderLine(hLine)
                                        }
                                        hLine = br.readLine()
                                    }
                                }
                            }
                            collectingBody -> {
                                // Body stored in file — remember it for streaming MIME parse.
                                bodyLiteralFile = literalFile
                                TyrLogger.d(TAG, "uid=$uid: body literal file set: ${literalFile.name} size=${literalFile.length()}")
                            }
                            else -> {
                                TyrLogger.w(TAG, "Unexpected LITERAL_FILE before section announcement for uid=$uid")
                            }
                        }
                        i++
                        continue
                    }

                    // ── Small literal kept in memory ──────────────────────────────────────
                    l.startsWith(LITERAL_PREFIX) -> {
                        val blob = l.removePrefix(LITERAL_PREFIX)
                        when {
                            collectingHeader -> {
                                // Header literal: split on newlines and process each header line.
                                // RFC 5322 headers end with a blank line (\r\n\r\n); hitting that
                                // empty line turns off collectingHeader.
                                for (hLine in blob.split('\n')) {
                                    val stripped = hLine.trimEnd('\r')
                                    if (stripped.isEmpty()) {
                                        collectingHeader = false
                                    } else {
                                        applyHeaderLine(stripped)
                                    }
                                }
                            }
                            collectingBody -> {
                                // Body literal: split on newlines; each line feeds parseMimeMultipart.
                                for (bLine in blob.split('\n')) {
                                    bodyLines.add(bLine.trimEnd('\r'))
                                }
                            }
                            else -> {
                                TyrLogger.w(TAG, "Unexpected LITERAL before section announcement for uid=$uid")
                            }
                        }
                        i++
                        continue
                    }
                }

                when {
                    uid < 0 && l.trim().matches(Regex("UID\\s+\\d+", RegexOption.IGNORE_CASE)) -> {
                        uid = l.trim().removePrefix("UID ").trim().toLongOrNull() ?: -1L
                    }
                    l.contains("BODY[HEADER", ignoreCase = true) && l.contains("{") -> {
                        collectingHeader = true
                        collectingBody = false
                    }
                    l.contains("BODY[TEXT]", ignoreCase = true) && l.contains("{") -> {
                        collectingBody = true
                        collectingHeader = false
                    }
                    // go-imap closes the FETCH record with " )" (leading space) after a literal
                    l.trimStart() == ")" -> break
                    l.isEmpty() && collectingHeader -> {
                        collectingHeader = false
                    }
                    collectingHeader -> applyHeaderLine(l)
                    collectingBody -> bodyLines.add(l)
                }
                i++
            }

            // Process receipts
            if (readReceiptForUid > 0) readReceiptUids.add(readReceiptForUid)
            if (readReceiptForTimestamp > 0 && from.isNotEmpty()) {
                readReceiptTimestamps.add(ReadReceiptByTimestamp(from.lowercase(), readReceiptForTimestamp))
            }
            if (deliveryReceiptForTimestamp > 0 && from.isNotEmpty()) {
                deliveryReceiptTimestamps.add(ReadReceiptByTimestamp(from.lowercase(), deliveryReceiptForTimestamp))
            }

            val isSignalingMessage = readReceiptForUid > 0 || readReceiptForTimestamp > 0 || deliveryReceiptForTimestamp > 0
            TyrLogger.d(TAG, "Parsed FETCH record: uid=$uid from=$from isTyrChat=$isTyrChat " +
                "isSignaling=$isSignalingMessage contentType='$contentType' bodyLines=${bodyLines.size} " +
                "bodyLiteralFile=${bodyLiteralFile?.name}")
            if (!isTyrChat) {
                TyrLogger.d(TAG, "uid=$uid: not a TyrChat message (missing X-Tyr-Chat: 1 header), skipping")
            }
            if (isTyrChat && uid > 0 && from.isNotEmpty() && !isSignalingMessage) {
                val isSent = from.equals(myAddress.trim(), ignoreCase = true)

                val lowerCT = contentType.lowercase()
                TyrLogger.i(TAG, "uid=$uid: processing TyrChat message from=$from isSent=$isSent " +
                    "contentType='$contentType' bodyLines=${bodyLines.size} bodyFile=${bodyLiteralFile?.name}")

                val (textBody, attachPath, attachName, attachMime, attachSize) = when {
                    lowerCT.startsWith("multipart/") && bodyLiteralFile != null -> {
                        // Large multipart body: stream-parse from file — never load whole file into memory.
                        TyrLogger.i(TAG, "uid=$uid: streaming MIME multipart from file, size=${bodyLiteralFile!!.length()}")
                        parseMimeMultipartFromFile(bodyLiteralFile!!, contentType, uid, attachmentsDir)
                    }
                    lowerCT.startsWith("multipart/") -> {
                        TyrLogger.i(TAG, "uid=$uid: parsing MIME multipart in memory, bodyLines=${bodyLines.size}")
                        parseMimeMultipart(bodyLines, contentType, uid, attachmentsDir)
                    }
                    bodyLiteralFile != null -> {
                        // Plain text stored in file (unlikely but handle gracefully).
                        val raw = bodyLiteralFile!!.readText(Charsets.ISO_8859_1).trim()
                        val body = if (lowerCT.contains("utf-8") || lowerCT.contains("utf8"))
                            reinterpretAsUtf8(raw) else raw
                        MimeParsed(body, null, null, null, 0L)
                    }
                    else -> {
                        val raw = bodyLines.joinToString("\n").trim()
                        val body = if (lowerCT.contains("utf-8") || lowerCT.contains("utf8"))
                            reinterpretAsUtf8(raw) else raw
                        MimeParsed(body, null, null, null, 0L)
                    }
                }
                TyrLogger.i(TAG, "uid=$uid: parsed — textBody.len=${textBody.length} " +
                    "attachPath=$attachPath attachName=$attachName attachMime=$attachMime attachSize=$attachSize")

                messages.add(
                    ChatMessage(
                        imapUid = uid,
                        fromAddr = from.lowercase(),
                        toAddr = to.lowercase(),
                        body = textBody,
                        timestamp = timestamp,
                        isRead = isSent,
                        isSent = isSent,
                        status = ChatMessage.STATUS_SENT,
                        attachmentPath = attachPath,
                        attachmentName = attachName,
                        attachmentMimeType = attachMime,
                        attachmentSizeBytes = attachSize
                    )
                )
                if (!isSent && senderNickname.isNotBlank()) {
                    nicknameUpdates.add(NicknameUpdate(from.lowercase(), senderNickname))
                }
            }
        }

        return FetchResult(messages, readReceiptUids, readReceiptTimestamps, deliveryReceiptTimestamps, nicknameUpdates)
    }

    /**
     * Decodes an email header value that may contain RFC 2047 encoded words
     * (=?UTF-8?B?base64?= or =?UTF-8?Q?qp?=).
     *
     * Falls back to re-interpreting ISO-8859-1 bytes as UTF-8 for legacy
     * messages where raw UTF-8 was written directly into the header (pre-fix).
     */
    private fun decodeHeaderValue(raw: String): String {
        // RFC 2047 encoded word pattern
        val regex = Regex("=\\?([^?]+)\\?([BbQq])\\?([^?]*)\\?=")
        if (raw.contains("=?") && raw.contains("?=")) {
            val decoded = regex.replace(raw) { match ->
                try {
                    val charset = Charset.forName(match.groupValues[1])
                    val encoding = match.groupValues[2].uppercase()
                    val encoded = match.groupValues[3]
                    when (encoding) {
                        "B" -> String(Base64.decode(encoded, Base64.DEFAULT), charset)
                        "Q" -> {
                            // Quoted-printable: underscore → space, =XX → byte
                            val qpBytes = mutableListOf<Byte>()
                            var i = 0
                            while (i < encoded.length) {
                                when {
                                    encoded[i] == '_' -> { qpBytes.add(0x20); i++ }
                                    encoded[i] == '=' && i + 2 < encoded.length -> {
                                        qpBytes.add(encoded.substring(i + 1, i + 3).toInt(16).toByte())
                                        i += 3
                                    }
                                    else -> { qpBytes.add(encoded[i].code.toByte()); i++ }
                                }
                            }
                            String(qpBytes.toByteArray(), charset)
                        }
                        else -> match.value
                    }
                } catch (e: Exception) {
                    match.value
                }
            }
            if (decoded != raw) return decoded
        }
        // Legacy fallback: raw was written as UTF-8 but read back as ISO-8859-1.
        // Re-interpret: ISO-8859-1 chars → bytes → UTF-8 string.
        return try {
            val bytes = raw.toByteArray(Charsets.ISO_8859_1)
            if (bytes.any { it < 0 }) String(bytes, Charsets.UTF_8) else raw
        } catch (e: Exception) {
            raw
        }
    }

    private data class MimeParsed(
        val body: String,
        val attachPath: String?,
        val attachName: String?,
        val attachMime: String?,
        val attachSize: Long
    )

    /**
     * Streaming MIME multipart parser for large body literals stored in a temp file.
     *
     * The file is read line-by-line with a BufferedReader.  For base64 attachment parts
     * the lines are decoded in chunks and written directly to the output file via
     * FileOutputStream — at no point is the full attachment content held in memory.
     *
     * Peak memory per call: O(chunk size) ≈ BASE64_CHUNK_LINES × 76 bytes ≈ ~300 KB
     * for the line accumulation buffer, plus the decoded chunk ByteArray (~230 KB).
     */
    private fun parseMimeMultipartFromFile(
        bodyFile: File,
        contentType: String,
        uid: Long,
        attachmentsDir: File?
    ): MimeParsed {
        val boundaryMatch = Regex("""boundary=["]?([^";]+)["]?""", RegexOption.IGNORE_CASE)
            .find(contentType)
        val boundary = boundaryMatch?.groupValues?.get(1)?.trim() ?: run {
            TyrLogger.e(TAG, "uid=$uid: no boundary found in contentType='$contentType' (file path)")
            return MimeParsed("", null, null, null, 0L)
        }
        TyrLogger.d(TAG, "uid=$uid: streaming MIME boundary='$boundary' from file ${bodyFile.name}")

        var textBody = ""
        var attachPath: String? = null
        var attachName: String? = null
        var attachMime: String? = null
        var attachSize = 0L

        // State machine: track which MIME part we're in and what we've read so far.
        var inPart = false
        var headersDone = false
        var partCT = ""
        var partCTE = ""
        var partCD = ""
        var lastPartHeader = ""
        var partIdx = 0

        // For text/plain part: accumulate lines (text bodies are small).
        var collectingText = false
        val textLines = mutableListOf<String>()

        // For base64 attachment part: write decoded output to file in chunks.
        var collectingBase64 = false
        var outFile: File? = null
        var outStream: FileOutputStream? = null
        var b64LineBuffer = mutableListOf<String>()  // accumulate BASE64_CHUNK_LINES lines then flush

        fun flushBase64Chunk() {
            if (b64LineBuffer.isEmpty() || outStream == null) return
            val chunk = b64LineBuffer.joinToString("")
            b64LineBuffer = mutableListOf()
            if (chunk.isBlank()) return
            val decoded = Base64.decode(chunk, Base64.DEFAULT)
            outStream!!.write(decoded)
        }

        fun finishBase64Part() {
            flushBase64Chunk()
            outStream?.flush()
            outStream?.close()
            outStream = null
            if (outFile != null) {
                attachSize = outFile!!.length()
                attachPath = outFile!!.absolutePath
                TyrLogger.i(TAG, "uid=$uid: saved attachment '$attachName' $attachSize bytes → $attachPath")
            }
        }

        fun startNewPart() {
            // Finish any in-progress part.
            if (collectingBase64) finishBase64Part()
            collectingText = false
            collectingBase64 = false
            textLines.clear()
            // Reset part headers.
            partCT = ""; partCTE = ""; partCD = ""; lastPartHeader = ""
            headersDone = false
            inPart = true
            partIdx++
        }

        bodyFile.bufferedReader(Charsets.ISO_8859_1).use { br ->
            var lineStr = br.readLine()
            while (lineStr != null) {
                val trimmed = lineStr.trimEnd()
                when {
                    trimmed == "--$boundary" -> {
                        startNewPart()
                    }
                    trimmed == "--$boundary--" -> {
                        if (collectingBase64) finishBase64Part()
                        if (collectingText) {
                            val raw = textLines.joinToString("\n").trim()
                            textBody = if (partCT.lowercase().let { it.contains("utf-8") || it.contains("utf8") })
                                reinterpretAsUtf8(raw) else raw
                        }
                        break
                    }
                    inPart && !headersDone -> {
                        val lower = lineStr.lowercase()
                        when {
                            lineStr.isEmpty() -> {
                                // Blank line = end of part headers; determine what to collect.
                                headersDone = true
                                lastPartHeader = ""
                                val partCTLower = partCT.lowercase()
                                TyrLogger.d(TAG, "uid=$uid part[$partIdx]: CT='$partCT' CTE='$partCTE' CD='$partCD'")
                                when {
                                    partCTLower.startsWith("text/plain") -> {
                                        collectingText = true
                                    }
                                    attachmentsDir != null && (
                                        partCTLower.startsWith("image/") ||
                                        partCTLower.startsWith("application/") ||
                                        partCTLower.startsWith("video/") ||
                                        partCD.lowercase().startsWith("attachment")
                                    ) && partCTE.equals("base64", ignoreCase = true) -> {
                                        // Extract filename
                                        val nameMatch = Regex("""filename=["]?([^";]+)["]?""", RegexOption.IGNORE_CASE)
                                            .find(partCD.ifBlank { partCT })
                                        val fileName = nameMatch?.groupValues?.get(1)?.trim()
                                            ?: "attachment_$uid"
                                        attachName = fileName
                                        attachMime = partCT.substringBefore(";").trim()
                                        attachmentsDir.mkdirs()
                                        outFile = File(attachmentsDir, "${uid}_$fileName")
                                        outStream = FileOutputStream(outFile!!)
                                        collectingBase64 = true
                                        b64LineBuffer = mutableListOf()
                                        TyrLogger.i(TAG, "uid=$uid part[$partIdx]: streaming base64 attachment '$fileName' → ${outFile!!.name}")
                                    }
                                    else -> {
                                        TyrLogger.d(TAG, "uid=$uid part[$partIdx]: unhandled CT='$partCT' CTE='$partCTE' attachmentsDir=${attachmentsDir != null}")
                                    }
                                }
                            }
                            // Folded header continuation
                            lineStr.isNotEmpty() && (lineStr[0] == ' ' || lineStr[0] == '\t') -> {
                                val continuation = lineStr.trim()
                                when (lastPartHeader) {
                                    "ct"  -> partCT  += " $continuation"
                                    "cte" -> partCTE += " $continuation"
                                    "cd"  -> partCD  += " $continuation"
                                }
                            }
                            lower.startsWith("content-type:") -> {
                                partCT = lineStr.substringAfter(":").trim()
                                lastPartHeader = "ct"
                            }
                            lower.startsWith("content-transfer-encoding:") -> {
                                partCTE = lineStr.substringAfter(":").trim()
                                lastPartHeader = "cte"
                            }
                            lower.startsWith("content-disposition:") -> {
                                partCD = lineStr.substringAfter(":").trim()
                                lastPartHeader = "cd"
                            }
                            else -> lastPartHeader = ""
                        }
                    }
                    inPart && headersDone -> {
                        when {
                            collectingText -> textLines.add(lineStr)
                            collectingBase64 -> {
                                val stripped = lineStr.trimEnd()
                                if (stripped.isNotEmpty()) {
                                    b64LineBuffer.add(stripped)
                                    if (b64LineBuffer.size >= BASE64_CHUNK_LINES) {
                                        flushBase64Chunk()
                                    }
                                }
                            }
                        }
                    }
                }
                lineStr = br.readLine()
            }
        }

        // Handle file that ended without closing boundary (truncated message).
        if (collectingBase64) finishBase64Part()
        if (collectingText && textBody.isEmpty()) {
            val raw = textLines.joinToString("\n").trim()
            textBody = if (partCT.lowercase().let { it.contains("utf-8") || it.contains("utf8") })
                reinterpretAsUtf8(raw) else raw
        }

        return MimeParsed(textBody, attachPath, attachName, attachMime, attachSize)
    }

    private fun parseMimeMultipart(
        bodyLines: List<String>,
        contentType: String,
        uid: Long,
        attachmentsDir: File?
    ): MimeParsed {
        // Extract boundary from Content-Type: multipart/mixed; boundary="xyz"
        val boundaryMatch = Regex("""boundary=["]?([^";]+)["]?""", RegexOption.IGNORE_CASE)
            .find(contentType)
        val boundary = boundaryMatch?.groupValues?.get(1)?.trim() ?: run {
            TyrLogger.e(TAG, "uid=$uid: no boundary found in contentType='$contentType'")
            return MimeParsed(bodyLines.joinToString("\n").trim(), null, null, null, 0L)
        }
        TyrLogger.d(TAG, "uid=$uid: MIME boundary='$boundary' bodyLines=${bodyLines.size}")
        // Log first few lines of body for debugging boundary detection
        if (bodyLines.isNotEmpty()) {
            TyrLogger.d(TAG, "uid=$uid: first body line: '${bodyLines[0].take(80)}'")
        }

        // Split on MIME boundaries
        val parts = mutableListOf<List<String>>()
        var currentPart = mutableListOf<String>()
        var inPart = false

        for (line in bodyLines) {
            val trimmed = line.trimEnd()
            when {
                trimmed == "--$boundary" -> {
                    if (inPart && currentPart.isNotEmpty()) {
                        parts.add(currentPart.toList())
                    }
                    currentPart = mutableListOf()
                    inPart = true
                }
                trimmed == "--$boundary--" -> {
                    if (inPart && currentPart.isNotEmpty()) {
                        parts.add(currentPart.toList())
                    }
                    break
                }
                inPart -> currentPart.add(line)
            }
        }
        // Save last part even if closing boundary was missing (truncated message)
        if (inPart && currentPart.isNotEmpty() && parts.lastOrNull() !== currentPart) {
            parts.add(currentPart.toList())
        }
        TyrLogger.i(TAG, "uid=$uid: found ${parts.size} MIME parts (boundary='$boundary')")
        if (parts.isEmpty()) {
            TyrLogger.e(TAG, "uid=$uid: ZERO parts found — boundary mismatch? " +
                "Expected '--$boundary' as first body line but got: '${bodyLines.firstOrNull()?.take(80)}'")
        }

        var textBody = ""
        var attachPath: String? = null
        var attachName: String? = null
        var attachMime: String? = null
        var attachSize = 0L

        for ((partIdx, part) in parts.withIndex()) {
            // Parse part headers
            var partCT = ""
            var partCTE = ""  // Content-Transfer-Encoding
            var partCD = ""   // Content-Disposition
            var headersDone = false
            val partBodyLines = mutableListOf<String>()

            // Track which part-header was last assigned so folded continuation
            // lines (starting with space or tab) are appended to the right field.
            var lastPartHeader = ""
            for (line in part) {
                if (!headersDone) {
                    val lower = line.lowercase()
                    when {
                        line.isEmpty() -> {
                            headersDone = true
                            lastPartHeader = ""
                        }
                        // Folded header continuation line
                        line.isNotEmpty() && (line[0] == ' ' || line[0] == '\t') -> {
                            val continuation = line.trim()
                            when (lastPartHeader) {
                                "ct"  -> partCT  += " $continuation"
                                "cte" -> partCTE += " $continuation"
                                "cd"  -> partCD  += " $continuation"
                            }
                        }
                        lower.startsWith("content-type:") -> {
                            partCT = line.substringAfter(":").trim()
                            lastPartHeader = "ct"
                        }
                        lower.startsWith("content-transfer-encoding:") -> {
                            partCTE = line.substringAfter(":").trim()
                            lastPartHeader = "cte"
                        }
                        lower.startsWith("content-disposition:") -> {
                            partCD = line.substringAfter(":").trim()
                            lastPartHeader = "cd"
                        }
                        else -> lastPartHeader = ""
                    }
                } else {
                    partBodyLines.add(line)
                }
            }

            val partCTLower = partCT.lowercase()
            TyrLogger.d(TAG, "uid=$uid part[$partIdx]: CT='$partCT' CTE='$partCTE' " +
                "CD='$partCD' bodyLines=${partBodyLines.size}")
            when {
                partCTLower.startsWith("text/plain") -> {
                    // Determine whether the part declares UTF-8 (or compatible) so we can
                    // re-interpret the ISO-8859-1 socket bytes correctly.
                    val declaredUtf8 = partCTLower.contains("utf-8") || partCTLower.contains("utf8")
                    textBody = if (partCTE.equals("quoted-printable", ignoreCase = true)) {
                        decodeQuotedPrintable(partBodyLines.joinToString("\n"))
                    } else if (declaredUtf8) {
                        reinterpretAsUtf8(partBodyLines.joinToString("\n").trim())
                    } else {
                        partBodyLines.joinToString("\n").trim()
                    }
                    TyrLogger.d(TAG, "uid=$uid part[$partIdx]: text/plain decoded, len=${textBody.length}")
                }
                attachmentsDir != null && (
                    partCTLower.startsWith("image/") ||
                    partCTLower.startsWith("application/") ||
                    partCTLower.startsWith("video/") ||
                    partCD.lowercase().startsWith("attachment")
                ) -> {
                    // Extract filename
                    val nameMatch = Regex("""filename=["]?([^";]+)["]?""", RegexOption.IGNORE_CASE)
                        .find(partCD.ifBlank { partCT })
                    val fileName = nameMatch?.groupValues?.get(1)?.trim()
                        ?: "attachment_$uid"
                    TyrLogger.i(TAG, "uid=$uid part[$partIdx]: attachment '$fileName' " +
                        "CTE='$partCTE' b64Lines=${partBodyLines.size} attachmentsDir=$attachmentsDir")

                    // Decode base64 attachment
                    if (partCTE.equals("base64", ignoreCase = true) && partBodyLines.isNotEmpty()) {
                        try {
                            val b64 = partBodyLines.joinToString("").trim()
                            TyrLogger.d(TAG, "uid=$uid: decoding base64, total b64 chars=${b64.length}")
                            val bytes = Base64.decode(b64, Base64.DEFAULT)
                            attachmentsDir.mkdirs()
                            val outFile = File(attachmentsDir, "${uid}_$fileName")
                            outFile.writeBytes(bytes)
                            attachPath = outFile.absolutePath
                            attachName = fileName
                            attachMime = partCT.substringBefore(";").trim()
                            attachSize = bytes.size.toLong()
                            TyrLogger.i(TAG, "uid=$uid: saved attachment '$fileName' " +
                                "${bytes.size} bytes → $attachPath")
                        } catch (e: Exception) {
                            TyrLogger.e(TAG, "uid=$uid: failed to decode attachment '$fileName'", e)
                        }
                    } else {
                        TyrLogger.w(TAG, "uid=$uid part[$partIdx]: attachment skipped — " +
                            "CTE='$partCTE' (expected base64) or partBodyLines empty=${partBodyLines.isEmpty()}")
                    }
                }
                else -> {
                    TyrLogger.d(TAG, "uid=$uid part[$partIdx]: unhandled part CT='$partCT' " +
                        "(attachmentsDir=${attachmentsDir != null})")
                }
            }
        }

        return MimeParsed(textBody, attachPath, attachName, attachMime, attachSize)
    }

    /**
     * Decodes a quoted-printable encoded string that was read from an ISO-8859-1 socket stream.
     * Collects decoded bytes first, then interprets them as UTF-8 so that multi-byte Cyrillic
     * (and other non-ASCII) sequences are correctly reconstructed.
     */
    private fun decodeQuotedPrintable(input: String): String {
        val bytes = mutableListOf<Byte>()
        var i = 0
        while (i < input.length) {
            when {
                input[i] == '=' && i + 2 < input.length && input[i + 1] != '\n' -> {
                    val hex = input.substring(i + 1, i + 3)
                    if (hex.all { it.isDigit() || it in 'A'..'F' || it in 'a'..'f' }) {
                        bytes.add(hex.toInt(16).toByte())
                        i += 3
                    } else {
                        // Soft line break (=\n) or malformed — pass through as ISO-8859-1 byte
                        bytes.add(input[i].code.toByte())
                        i++
                    }
                }
                else -> {
                    bytes.add(input[i].code.toByte())
                    i++
                }
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8).trim()
    }

    /**
     * Re-decodes a String that was read from an ISO-8859-1 stream as UTF-8.
     * Each char in the input maps 1:1 to a byte (ISO-8859-1 contract), so we
     * extract those bytes and re-interpret them with the correct charset.
     */
    private fun reinterpretAsUtf8(iso: String): String {
        val bytes = ByteArray(iso.length) { iso[it].code.toByte() }
        return String(bytes, Charsets.UTF_8)
    }

    private fun extractEmailAddr(raw: String): String {
        val angleStart = raw.indexOf('<')
        val angleEnd = raw.indexOf('>')
        return if (angleStart >= 0 && angleEnd > angleStart) {
            raw.substring(angleStart + 1, angleEnd).trim().lowercase()
        } else {
            raw.trim().lowercase()
        }
    }

    private fun parseImapDate(dateStr: String): Long {
        val formats = listOf(
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm:ss z"
        )
        for (fmt in formats) {
            try {
                return java.text.SimpleDateFormat(fmt, java.util.Locale.US).parse(dateStr)?.time
                    ?: continue
            } catch (_: Exception) {}
        }
        return System.currentTimeMillis()
    }
}
