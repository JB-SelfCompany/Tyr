package com.jbselfcompany.tyr.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jbselfcompany.tyr.R
import com.jbselfcompany.tyr.TyrApplication
import com.jbselfcompany.tyr.ui.MainActivity
import mobile.LogCallback
import mobile.YggmailService as MobileYggmailService
import java.io.File

/**
 * Foreground service that runs Yggmail server.
 * Manages lifecycle of Yggmail service and provides status updates.
 *
 * Battery optimization: Uses timed WakeLock with periodic renewal
 * to balance connectivity and power consumption.
 */
class YggmailService : Service(), LogCallback {

    companion object {
        private const val TAG = "YggmailService"
        private const val NOTIFICATION_ID = 1001

        // WakeLock constants for battery optimization
        // Increased timeout to 30 minutes with 25-minute renewal for better battery efficiency
        // Yggmail's adaptive heartbeat handles connection maintenance
        private const val WAKELOCK_TIMEOUT_MS = 30 * 60 * 1000L // 30 minutes
        private const val WAKELOCK_RENEWAL_MS = 25 * 60 * 1000L  // Renew every 25 minutes

        const val ACTION_START = "com.jbselfcompany.tyr.START"
        const val ACTION_STOP = "com.jbselfcompany.tyr.STOP"

        /**
         * Check if service is currently running
         */
        var isRunning = false
            private set

        /**
         * Start the Yggmail service
         */
        fun start(context: Context) {
            val intent = Intent(context, YggmailService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        /**
         * Stop the Yggmail service
         */
        fun stop(context: Context) {
            val intent = Intent(context, YggmailService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        /**
         * Delete the Yggmail database to regenerate keys
         */
        fun deleteDatabase(context: Context): Boolean {
            val dbFile = File(context.filesDir, "yggmail.db")
            return if (dbFile.exists()) {
                dbFile.delete()
            } else {
                true // Already doesn't exist
            }
        }
    }

    // Service state
    private var yggmailService: MobileYggmailService? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val configRepository by lazy { TyrApplication.instance.configRepository }

    // Threading
    private lateinit var serviceThread: HandlerThread
    private lateinit var serviceHandler: Handler
    private val mainHandler = Handler(Looper.getMainLooper())

    // Notification
    private lateinit var notificationManager: NotificationManager

    // Service status
    private var serviceStatus = ServiceStatus.STOPPED
    private var lastError: String? = null
    private val statusListeners = mutableListOf<ServiceStatusListener>()

    // WakeLock renewal
    private var wakeLockRenewalRunnable: Runnable? = null
    private var isAppActive = false // Track if app is in foreground

    // Mail activity monitoring for adaptive heartbeat
    // No periodic polling needed - yggmail library handles adaptive heartbeat internally
    // We only notify on actual SMTP/IMAP activity via setAppActive() and notifyMailActivity()

    // Binder for local service binding
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): YggmailService = this@YggmailService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")

        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create service thread
        serviceThread = HandlerThread("YggmailServiceThread").apply { start() }
        serviceHandler = Handler(serviceThread.looper)

        // Acquire wake lock
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Tyr::YggmailService"
        ).apply {
            setReferenceCounted(false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand: ${intent?.action}")

        when (intent?.action) {
            ACTION_START -> {
                if (!isRunning) {
                    startForegroundWithNotification()
                    startYggmail()
                } else {
                    Log.w(TAG, "Service already running, ignoring START action")
                }
            }
            ACTION_STOP -> {
                stopYggmail()
                stopSelf()
            }
            else -> {
                // Service restarted by system
                if (!isRunning) {
                    startForegroundWithNotification()
                    startYggmail()
                }
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy")

        // Cancel WakeLock renewal
        wakeLockRenewalRunnable?.let { serviceHandler.removeCallbacks(it) }

        stopYggmail()

        serviceThread.quitSafely()

        releaseWakeLock()

        // Ensure notification is removed
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        notificationManager.cancel(NOTIFICATION_ID)

        super.onDestroy()
    }

    /**
     * Start foreground service with notification
     */
    private fun startForegroundWithNotification() {
        val notification = createNotification(ServiceStatus.STARTING)
        startForeground(NOTIFICATION_ID, notification)
    }

    /**
     * Start Yggmail service on background thread
     */
    private fun startYggmail() {
        serviceHandler.post {
            startYggmailSync()
        }
    }

    /**
     * Synchronous start logic (called from handler thread)
     */
    private fun startYggmailSync() {
        try {
            Log.i(TAG, "Starting Yggmail service...")
            updateStatus(ServiceStatus.STARTING)

            // Get configuration
            val password = configRepository.getPassword()
            if (password.isNullOrEmpty()) {
                throw IllegalStateException("Password not configured")
            }

            val peers = configRepository.getPeersString()
            val multicastEnabled = configRepository.isMulticastEnabled()

            // Database path
            val dbPath = File(filesDir, "yggmail.db").absolutePath
            Log.d(TAG, "Database path: $dbPath")

            // SMTP and IMAP addresses (localhost only, for DeltaChat)
            val smtpAddr = "127.0.0.1:1025"
            val imapAddr = "127.0.0.1:1143"

            // Create Yggmail service
            // Always set LogCallback, but onLog() will check if logging is enabled
            yggmailService = mobile.Mobile.newYggmailService(dbPath, smtpAddr, imapAddr).apply {
                setLogCallback(this@YggmailService)
            }

            // Initialize (creates/loads keys)
            yggmailService?.initialize()
            Log.d(TAG, "Yggmail initialized")

            // Set password
            yggmailService?.setPassword(password)
            Log.d(TAG, "Password configured")

            // Save mail address for display
            val mailAddress = yggmailService?.getMailAddress() ?: ""
            val publicKey = yggmailService?.getPublicKey() ?: ""
            configRepository.saveMailAddress(mailAddress)
            configRepository.savePublicKey(publicKey)
            Log.i(TAG, "Mail address: $mailAddress")

            // Start with configured peers
            yggmailService?.start(peers, multicastEnabled, ".*")
            Log.i(TAG, "Yggmail service started successfully")

            // Acquire wake lock with timeout and start periodic renewal
            acquireWakeLockWithTimeout()
            scheduleWakeLockRenewal()

            isRunning = true
            updateStatus(ServiceStatus.RUNNING)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Yggmail service", e)
            lastError = e.message
            updateStatus(ServiceStatus.ERROR)
            mainHandler.post {
                stopSelf()
            }
        }
    }

    /**
     * Stop Yggmail service on background thread
     */
    private fun stopYggmail() {
        serviceHandler.post {
            stopYggmailSync()
        }
    }

    /**
     * Synchronous stop logic (called from handler thread)
     */
    private fun stopYggmailSync() {
        try {
            Log.i(TAG, "Stopping Yggmail service...")
            updateStatus(ServiceStatus.STOPPING)

            // Cancel WakeLock renewal
            wakeLockRenewalRunnable?.let { serviceHandler.removeCallbacks(it) }

            // Stop and close service
            yggmailService?.stop()
            Thread.sleep(500) // Give time for stop to process

            yggmailService?.close()
            Thread.sleep(500) // Give time for close to process

            yggmailService = null

            // Force garbage collection to help release Go resources
            System.gc()
            System.runFinalization()

            // Wait for ports to be fully released (increased to 3000ms)
            Thread.sleep(3000)

            releaseWakeLock()

            isRunning = false
            updateStatus(ServiceStatus.STOPPED)

            // Remove foreground notification when stopped
            mainHandler.post {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
            }

            Log.i(TAG, "Yggmail service stopped")

        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Yggmail service", e)
            lastError = e.message
            updateStatus(ServiceStatus.ERROR)
        }
    }

    /**
     * Acquire WakeLock with timeout for battery optimization
     */
    private fun acquireWakeLockWithTimeout() {
        try {
            wakeLock?.let { lock ->
                if (lock.isHeld) {
                    lock.release()
                }
                lock.acquire(WAKELOCK_TIMEOUT_MS)
                Log.d(TAG, "WakeLock acquired with ${WAKELOCK_TIMEOUT_MS / 1000}s timeout")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring WakeLock", e)
        }
    }

    /**
     * Release WakeLock safely
     */
    private fun releaseWakeLock() {
        try {
            wakeLock?.let { lock ->
                if (lock.isHeld) {
                    lock.release()
                    Log.d(TAG, "WakeLock released")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock", e)
        }
    }

    /**
     * Schedule periodic WakeLock renewal to maintain service while optimizing battery
     */
    private fun scheduleWakeLockRenewal() {
        wakeLockRenewalRunnable = Runnable {
            if (isRunning) {
                Log.d(TAG, "Renewing WakeLock")
                acquireWakeLockWithTimeout()
                scheduleWakeLockRenewal() // Schedule next renewal
            }
        }

        wakeLockRenewalRunnable?.let {
            serviceHandler.postDelayed(it, WAKELOCK_RENEWAL_MS)
        }
    }

    /**
     * Update service status and notification
     */
    private fun updateStatus(status: ServiceStatus) {
        serviceStatus = status

        mainHandler.post {
            // Update notification
            val notification = createNotification(status)
            notificationManager.notify(NOTIFICATION_ID, notification)

            // Notify listeners
            statusListeners.forEach { it.onStatusChanged(status, lastError) }
        }
    }

    /**
     * Create notification for current service status
     * Optimized for low battery usage with PRIORITY_MIN
     */
    private fun createNotification(status: ServiceStatus): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val statusText = when (status) {
            ServiceStatus.STARTING -> getString(R.string.service_starting)
            ServiceStatus.RUNNING -> getString(R.string.service_running)
            ServiceStatus.STOPPING -> getString(R.string.service_stopping)
            ServiceStatus.STOPPED -> getString(R.string.service_stopped)
            ServiceStatus.ERROR -> lastError ?: getString(R.string.service_error)
        }

        return NotificationCompat.Builder(this, TyrApplication.CHANNEL_ID_SERVICE)
            .setContentTitle(statusText)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentIntent(pendingIntent)
            .setOngoing(status == ServiceStatus.RUNNING || status == ServiceStatus.STARTING)
            .setPriority(NotificationCompat.PRIORITY_MIN) // Optimized: was PRIORITY_LOW
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false) // Hide timestamp for cleaner notification
            .build()
    }

    /**
     * LogCallback implementation for Yggmail logs
     * Only logs if log collection is enabled in settings
     */
    override fun onLog(level: String, tag: String, message: String) {
        // Check if log collection is enabled
        if (!configRepository.isLogCollectionEnabled()) {
            return
        }

        val logTag = "YggmailService"
        val logMessage = "[$tag] $message"

        when (level.uppercase()) {
            "ERROR", "E" -> Log.e(logTag, logMessage)
            "WARN", "W" -> Log.w(logTag, logMessage)
            "INFO", "I" -> Log.i(logTag, logMessage)
            "DEBUG", "D" -> Log.d(logTag, logMessage)
            "VERBOSE", "V" -> Log.v(logTag, logMessage)
            else -> Log.d(logTag, logMessage)
        }
    }

    /**
     * Add service status listener
     */
    fun addStatusListener(listener: ServiceStatusListener) {
        statusListeners.add(listener)
        // Immediately notify with current status
        listener.onStatusChanged(serviceStatus, lastError)
    }

    /**
     * Remove service status listener
     */
    fun removeStatusListener(listener: ServiceStatusListener) {
        statusListeners.remove(listener)
    }

    /**
     * Get current service status
     */
    fun getStatus(): ServiceStatus = serviceStatus

    /**
     * Get last error message
     */
    fun getLastError(): String? = lastError

    /**
     * Get peer connection information from native Yggmail service
     */
    fun getPeerConnections(): List<PeerConnectionInfo>? {
        return try {
            val jsonString = yggmailService?.getPeerConnectionsJSON() ?: return null
            parsePeerConnectionsJSON(jsonString)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting peer connections", e)
            null
        }
    }

    /**
     * Parse JSON string to list of PeerConnectionInfo
     */
    private fun parsePeerConnectionsJSON(json: String): List<PeerConnectionInfo> {
        if (json.isEmpty() || json == "[]") {
            return emptyList()
        }

        val peers = mutableListOf<PeerConnectionInfo>()
        try {
            // Simple JSON parsing without external library
            // New format: [{"uri":"tls://...","up":true,"inbound":false,"lastError":"","key":"...","uptime":120,"latencyMs":45,"rxBytes":1024,"txBytes":2048,"rxRate":10,"txRate":20},...]
            val jsonArray = json.trim().removeSurrounding("[", "]")
            if (jsonArray.isEmpty()) return emptyList()

            // Split by },{
            val peerObjects = jsonArray.split("},")
            for (peerStr in peerObjects) {
                var obj = peerStr.trim()
                if (!obj.startsWith("{")) obj = "{$obj"
                if (!obj.endsWith("}")) obj = "$obj}"

                // Extract fields from new format
                val uri = extractJSONString(obj, "uri")
                val up = extractJSONBoolean(obj, "up")
                val inbound = extractJSONBoolean(obj, "inbound")
                val lastError = extractJSONString(obj, "lastError")
                val key = extractJSONString(obj, "key")
                val uptime = extractJSONLong(obj, "uptime")
                val latencyMs = extractJSONLong(obj, "latencyMs")
                val rxBytes = extractJSONLong(obj, "rxBytes")
                val txBytes = extractJSONLong(obj, "txBytes")
                val rxRate = extractJSONLong(obj, "rxRate")
                val txRate = extractJSONLong(obj, "txRate")

                if (uri.isNotEmpty()) {
                    peers.add(PeerConnectionInfo(
                        uri = uri,
                        up = up,
                        inbound = inbound,
                        lastError = lastError,
                        key = key,
                        uptime = uptime,
                        latencyMs = latencyMs,
                        rxBytes = rxBytes,
                        txBytes = txBytes,
                        rxRate = rxRate,
                        txRate = txRate
                    ))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing peer connections JSON: $json", e)
        }
        return peers
    }

    private fun extractJSONString(json: String, key: String): String {
        val pattern = """"$key":"([^"]*)"""".toRegex()
        return pattern.find(json)?.groupValues?.get(1) ?: ""
    }

    private fun extractJSONInt(json: String, key: String): Int {
        val pattern = """"$key":(\d+)""".toRegex()
        return pattern.find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun extractJSONLong(json: String, key: String): Long {
        val pattern = """"$key":(\d+)""".toRegex()
        return pattern.find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    }

    private fun extractJSONBoolean(json: String, key: String): Boolean {
        val pattern = """"$key":(true|false)""".toRegex()
        return pattern.find(json)?.groupValues?.get(1) == "true"
    }

    /**
     * Data class for peer connection information
     */
    data class PeerConnectionInfo(
        val uri: String,           // Peer URI (e.g., "tls://1.2.3.4:7743")
        val up: Boolean,           // Connection is active
        val inbound: Boolean,      // True if peer initiated connection
        val lastError: String,     // Last error message (empty if no error)
        val key: String,           // Peer's public key (hex)
        val uptime: Long,          // Connection uptime in seconds
        val latencyMs: Long,       // Latency in milliseconds
        val rxBytes: Long,         // Received bytes
        val txBytes: Long,         // Transmitted bytes
        val rxRate: Long,          // Receive rate (bytes/sec)
        val txRate: Long           // Transmit rate (bytes/sec)
    ) {
        // Helper properties for backward compatibility
        val host: String
            get() = extractHostFromUri(uri)

        val port: Int
            get() = extractPortFromUri(uri)

        val connected: Boolean
            get() = up

        private fun extractHostFromUri(uri: String): String {
            return try {
                // Extract host from URI like "tls://1.2.3.4:7743" or "tcp://[::1]:7743"
                val withoutScheme = uri.substringAfter("://")
                if (withoutScheme.startsWith("[")) {
                    // IPv6 address
                    withoutScheme.substringAfter("[").substringBefore("]")
                } else {
                    // IPv4 address or hostname
                    withoutScheme.substringBefore(":")
                }
            } catch (e: Exception) {
                uri
            }
        }

        private fun extractPortFromUri(uri: String): Int {
            return try {
                uri.substringAfterLast(":").toIntOrNull() ?: 0
            } catch (e: Exception) {
                0
            }
        }
    }

    /**
     * Notify service that app is in foreground (active)
     * This enables more aggressive heartbeat for better responsiveness
     * When app is active, we acquire WakeLock more aggressively
     */
    fun setAppActive(active: Boolean) {
        try {
            isAppActive = active
            yggmailService?.setActive(active)
            Log.d(TAG, "App activity state set to: $active")

            // When app becomes active, immediately acquire WakeLock for better responsiveness
            if (active && isRunning) {
                serviceHandler.post {
                    acquireWakeLockWithTimeout()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting app activity state", e)
        }
    }

    /**
     * Notify service about mail activity (sending/receiving)
     * This triggers aggressive mode for immediate delivery
     */
    fun notifyMailActivity() {
        try {
            yggmailService?.recordMailActivity()
            Log.d(TAG, "Mail activity recorded")
        } catch (e: Exception) {
            Log.e(TAG, "Error recording mail activity", e)
        }
    }

    /**
     * Hot reload peers without restarting the entire service
     * Uses Yggdrasil Core's AddPeer/RemovePeer for live updates without reconnection
     */
    fun hotReloadPeers() {
        serviceHandler.post {
            try {
                Log.i(TAG, "Hot reloading peers...")

                // Get updated configuration
                val peers = configRepository.getPeersString()
                val multicastEnabled = configRepository.isMulticastEnabled()

                // Update peers using Yggdrasil Core's AddPeer/RemovePeer
                // This approach doesn't close the transport, avoiding ErrClosed errors
                yggmailService?.updatePeers(peers, multicastEnabled, ".*")

                Log.i(TAG, "Peers updated successfully using live configuration")

            } catch (e: Exception) {
                Log.e(TAG, "Error updating peers", e)
            }
        }
    }
}

/**
 * Service status enum
 */
enum class ServiceStatus {
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED,
    ERROR
}

/**
 * Interface for listening to service status changes
 */
interface ServiceStatusListener {
    fun onStatusChanged(status: ServiceStatus, error: String?)
}
