package com.jbselfcompany.tyr.utils

import android.content.Context
import com.jbselfcompany.tyr.utils.TyrLogger
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
import java.util.concurrent.Executors

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

    // Bounded thread pool — prevents unbounded thread creation per HTTP connection
    private val clientExecutor = Executors.newFixedThreadPool(4)

    // Store tokens with their creation time
    private val tokens = ConcurrentHashMap<String, Long>()

    fun start() {
        if (running) {
            TyrLogger.w(TAG,"Server already running")
            return
        }

        try {
            serverSocket = ServerSocket()
            serverSocket?.reuseAddress = true
            serverSocket?.bind(InetSocketAddress("127.0.0.1", PORT))
            running = true

            serverThread = Thread({
                TyrLogger.i(TAG,"Autoconfig server started on port $PORT")

                while (running) {
                    try {
                        val socket = serverSocket?.accept()
                        socket?.let { clientExecutor.execute { handleClient(it) } }
                    } catch (e: Exception) {
                        if (running) {
                            TyrLogger.e(TAG,"Error accepting connection", e)
                        }
                    }
                }
            }, "AutoconfigServer").also { it.start() }
        } catch (e: Exception) {
            TyrLogger.e(TAG,"Failed to start server", e)
            running = false
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
            serverSocket = null
            serverThread?.interrupt()
            serverThread = null
            clientExecutor.shutdown()
            TyrLogger.i(TAG,"Autoconfig server stopped")
        } catch (e: Exception) {
            TyrLogger.e(TAG,"Error stopping server", e)
        }
    }

    fun isRunning(): Boolean = running

    fun generateToken(): String {
        cleanExpiredTokens()

        val token = UUID.randomUUID().toString().replace("-", "")
        tokens[token] = System.currentTimeMillis()
        return token
    }

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
        val encodedPassword = java.net.URLEncoder.encode(password, "UTF-8")

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

    private fun cleanExpiredTokens() {
        val now = System.currentTimeMillis()
        tokens.entries.removeIf { (_, timestamp) ->
            now - timestamp > TOKEN_EXPIRY_MS
        }
    }

    private fun isValidToken(token: String?): Boolean {
        if (token == null) return false

        val timestamp = tokens[token] ?: return false
        val now = System.currentTimeMillis()

        return (now - timestamp) <= TOKEN_EXPIRY_MS
    }

    private fun handleClient(socket: Socket) {
        // Called from clientExecutor thread pool — no extra thread needed
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)

            val requestLine = reader.readLine() ?: ""
            TyrLogger.d(TAG,"Request: $requestLine")

            // Read headers (we don't need them, but we must consume them)
            var line: String?
            do {
                line = reader.readLine()
            } while (!line.isNullOrEmpty())

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
            TyrLogger.e(TAG,"Error handling client", e)
        }
    }

    private fun handleNewEmailRequest(path: String, writer: OutputStreamWriter) {
        try {
            val token = extractToken(path)

            if (!isValidToken(token)) {
                send401(writer, "Invalid or expired token")
                return
            }

            val configRepository = TyrApplication.instance.configRepository
            val email = configRepository.getMailAddress()
            val password = configRepository.getPassword()

            if (email.isNullOrEmpty() || password.isNullOrEmpty()) {
                send500(writer, "Account not configured")
                return
            }

            val json = JSONObject()
            json.put("email", email)
            json.put("password", password)

            val responseBody = json.toString()

            writer.write("HTTP/1.1 200 OK\r\n")
            writer.write("Content-Type: application/json\r\n")
            writer.write("Content-Length: ${responseBody.toByteArray(StandardCharsets.UTF_8).size}\r\n")
            writer.write("Connection: close\r\n")
            writer.write("\r\n")
            writer.write(responseBody)

            tokens.remove(token)

            TyrLogger.i(TAG,"Served autoconfig for $email")
        } catch (e: Exception) {
            TyrLogger.e(TAG,"Error handling /new_email", e)
            send500(writer, "Internal server error")
        }
    }

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

    private fun send400(writer: OutputStreamWriter) {
        writer.write("HTTP/1.1 400 Bad Request\r\n")
        writer.write("Content-Length: 0\r\n")
        writer.write("Connection: close\r\n")
        writer.write("\r\n")
    }

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

    private fun send404(writer: OutputStreamWriter) {
        writer.write("HTTP/1.1 404 Not Found\r\n")
        writer.write("Content-Length: 0\r\n")
        writer.write("Connection: close\r\n")
        writer.write("\r\n")
    }

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
