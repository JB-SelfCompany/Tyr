package com.jbselfcompany.tyr.utils

import android.content.Context
import android.util.Log
import com.jbselfcompany.tyr.TyrApplication
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Simple HTTP server for DeltaChat autoconfig endpoint.
 * Provides DCACCOUNT URL support by serving account configuration as JSON.
 */
class AutoconfigServer(private val context: Context) {

    companion object {
        private const val TAG = "AutoconfigServer"
        private const val PORT = 8888
        private const val TOKEN_EXPIRY_MS = 3600000L // 1 hour
    }

    private var serverSocket: ServerSocket? = null
    private var running = false
    private var serverThread: Thread? = null

    // Store tokens with their creation time
    private val tokens = ConcurrentHashMap<String, Long>()

    /**
     * Start the HTTP server on localhost
     */
    fun start() {
        if (running) {
            Log.w(TAG, "Server already running")
            return
        }

        try {
            serverSocket = ServerSocket()
            serverSocket?.reuseAddress = true
            serverSocket?.bind(InetSocketAddress("127.0.0.1", PORT))
            running = true

            serverThread = thread(name = "AutoconfigServer") {
                Log.i(TAG, "Autoconfig server started on port $PORT")

                while (running) {
                    try {
                        val socket = serverSocket?.accept()
                        socket?.let { handleClient(it) }
                    } catch (e: Exception) {
                        if (running) {
                            Log.e(TAG, "Error accepting connection", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server", e)
            running = false
        }
    }

    /**
     * Stop the HTTP server
     */
    fun stop() {
        running = false
        try {
            serverSocket?.close()
            serverSocket = null
            serverThread?.interrupt()
            serverThread = null
            Log.i(TAG, "Autoconfig server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
        }
    }

    /**
     * Check if server is running
     */
    fun isRunning(): Boolean = running

    /**
     * Generate a new token for autoconfig URL
     * @return token string
     */
    fun generateToken(): String {
        // Clean expired tokens
        cleanExpiredTokens()

        val token = UUID.randomUUID().toString().replace("-", "")
        tokens[token] = System.currentTimeMillis()
        return token
    }

    /**
     * Generate DCACCOUNT URL with a new token
     * @return DCACCOUNT URL string
     */
    fun generateDcaccountUrl(): String {
        val token = generateToken()
        return "DCACCOUNT:https://127.0.0.1:$PORT/new_email?t=$token"
    }

    /**
     * Generate DCLOGIN URL with embedded credentials (no HTTP server needed)
     * This is a simpler alternative that doesn't require HTTPS
     *
     * Format: dclogin://user@host/?p=password&v=1&ih=imap_host&ip=imap_port&is=security&sh=smtp_host&sp=smtp_port&ss=security&ic=cert_checks
     *
     * @param email Mail address
     * @param password Account password
     * @return DCLOGIN URL string
     */
    fun generateDcloginUrl(email: String, password: String): String {
        // DCLOGIN format according to DeltaChat specification
        // dclogin://email@domain/?p=password&v=1&ih=imap_host&ip=port&is=security&sh=smtp_host&sp=port&ss=security&ic=cert_checks

        // URL encode the password to handle special characters
        val encodedPassword = java.net.URLEncoder.encode(password, "UTF-8")

        // Build DCLOGIN URL with IMAP and SMTP configuration
        return buildString {
            append("dclogin://")
            append(email)
            append("/?p=")
            append(encodedPassword)
            append("&v=1")
            // IMAP configuration
            append("&ih=127.0.0.1")
            append("&ip=1143")
            append("&is=plain")  // No encryption for localhost
            // SMTP configuration
            append("&sh=127.0.0.1")
            append("&sp=1025")
            append("&ss=plain")  // No encryption for localhost
            // Certificate checks: 0 = automatic
            append("&ic=0")
        }
    }

    /**
     * Clean expired tokens
     */
    private fun cleanExpiredTokens() {
        val now = System.currentTimeMillis()
        tokens.entries.removeIf { (_, timestamp) ->
            now - timestamp > TOKEN_EXPIRY_MS
        }
    }

    /**
     * Validate token
     */
    private fun isValidToken(token: String?): Boolean {
        if (token == null) return false

        val timestamp = tokens[token] ?: return false
        val now = System.currentTimeMillis()

        // Check if token is expired
        return (now - timestamp) <= TOKEN_EXPIRY_MS
    }

    /**
     * Handle HTTP client connection
     */
    private fun handleClient(socket: Socket) {
        thread {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)

                // Read HTTP request line
                val requestLine = reader.readLine() ?: ""
                Log.d(TAG, "Request: $requestLine")

                // Read headers (we don't need them, but we must consume them)
                var line: String?
                do {
                    line = reader.readLine()
                } while (!line.isNullOrEmpty())

                // Parse request
                val parts = requestLine.split(" ")
                if (parts.size >= 2) {
                    val method = parts[0]
                    val path = parts[1]

                    if (method == "GET" && path.startsWith("/new_email")) {
                        handleNewEmailRequest(path, writer)
                    } else {
                        send404(writer)
                    }
                } else {
                    send400(writer)
                }

                writer.flush()
                socket.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error handling client", e)
            }
        }
    }

    /**
     * Handle /new_email endpoint
     */
    private fun handleNewEmailRequest(path: String, writer: OutputStreamWriter) {
        try {
            // Extract token from query string
            val token = extractToken(path)

            if (!isValidToken(token)) {
                send401(writer, "Invalid or expired token")
                return
            }

            // Get mail address and password from config
            val configRepository = TyrApplication.instance.configRepository
            val email = configRepository.getMailAddress()
            val password = configRepository.getPassword()

            if (email.isNullOrEmpty() || password.isNullOrEmpty()) {
                send500(writer, "Account not configured")
                return
            }

            // Build JSON response
            val json = JSONObject()
            json.put("email", email)
            json.put("password", password)

            val responseBody = json.toString()

            // Send HTTP response
            writer.write("HTTP/1.1 200 OK\r\n")
            writer.write("Content-Type: application/json\r\n")
            writer.write("Content-Length: ${responseBody.toByteArray(StandardCharsets.UTF_8).size}\r\n")
            writer.write("Connection: close\r\n")
            writer.write("\r\n")
            writer.write(responseBody)

            // Remove token after use (one-time use)
            tokens.remove(token)

            Log.i(TAG, "Served autoconfig for $email")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling /new_email", e)
            send500(writer, "Internal server error")
        }
    }

    /**
     * Extract token from query string
     */
    private fun extractToken(path: String): String? {
        val queryStart = path.indexOf('?')
        if (queryStart == -1) return null

        val query = path.substring(queryStart + 1)
        val params = query.split('&')

        for (param in params) {
            val keyValue = param.split('=')
            if (keyValue.size == 2 && keyValue[0] == "t") {
                return keyValue[1]
            }
        }

        return null
    }

    /**
     * Send HTTP 400 Bad Request
     */
    private fun send400(writer: OutputStreamWriter) {
        writer.write("HTTP/1.1 400 Bad Request\r\n")
        writer.write("Content-Length: 0\r\n")
        writer.write("Connection: close\r\n")
        writer.write("\r\n")
    }

    /**
     * Send HTTP 401 Unauthorized
     */
    private fun send401(writer: OutputStreamWriter, message: String) {
        val json = JSONObject()
        json.put("error", message)
        val body = json.toString()

        writer.write("HTTP/1.1 401 Unauthorized\r\n")
        writer.write("Content-Type: application/json\r\n")
        writer.write("Content-Length: ${body.toByteArray(StandardCharsets.UTF_8).size}\r\n")
        writer.write("Connection: close\r\n")
        writer.write("\r\n")
        writer.write(body)
    }

    /**
     * Send HTTP 404 Not Found
     */
    private fun send404(writer: OutputStreamWriter) {
        writer.write("HTTP/1.1 404 Not Found\r\n")
        writer.write("Content-Length: 0\r\n")
        writer.write("Connection: close\r\n")
        writer.write("\r\n")
    }

    /**
     * Send HTTP 500 Internal Server Error
     */
    private fun send500(writer: OutputStreamWriter, message: String) {
        val json = JSONObject()
        json.put("error", message)
        val body = json.toString()

        writer.write("HTTP/1.1 500 Internal Server Error\r\n")
        writer.write("Content-Type: application/json\r\n")
        writer.write("Content-Length: ${body.toByteArray(StandardCharsets.UTF_8).size}\r\n")
        writer.write("Connection: close\r\n")
        writer.write("\r\n")
        writer.write(body)
    }
}
