package com.jbselfcompany.tyr.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.jbselfcompany.tyr.utils.TyrLogger
import androidx.core.app.NotificationCompat
import com.jbselfcompany.tyr.R
import com.jbselfcompany.tyr.TyrApplication
import com.jbselfcompany.tyr.receiver.MaintenanceReceiver
import com.jbselfcompany.tyr.ui.MainActivity
import com.jbselfcompany.tyr.chat.data.ChatRepository
import com.jbselfcompany.tyr.chat.network.ImapFetcher
import com.jbselfcompany.tyr.chat.network.SmtpSender
import uniffi.yggmail_mobile.YggmailMobile
import uniffi.yggmail_mobile.YggmailConfig
import uniffi.yggmail_mobile.YggmailState

import uniffi.yggmail_mobile.YggmailException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that runs Yggmail server.
 * Manages lifecycle of Yggmail service and provides status updates.
 *
 * Battery optimization: Uses timed WakeLock with periodic renewal
 * to balance connectivity and power consumption.
 */
class YggmailService : Service() {

    companion object {
        private const val TAG = "YggmailService"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.jbselfcompany.tyr.START"
        const val ACTION_STOP = "com.jbselfcompany.tyr.STOP"
        const val ACTION_SOFT_STOP = "com.jbselfcompany.tyr.SOFT_STOP"
        const val ACTION_RECONNECT_PEERS = "com.jbselfcompany.tyr.RECONNECT_PEERS"
        const val ACTION_MAINTENANCE_CHECK = "com.jbselfcompany.tyr.MAINTENANCE_CHECK"
        const val ACTION_NEW_CHAT_MESSAGES = "com.jbselfcompany.tyr.NEW_CHAT_MESSAGES"
        private const val CHAT_POLL_INTERVAL_MS = 2 * 60 * 1000L // 2 minutes
        private const val CHAT_NOTIFICATION_BASE_ID = 2000
        // ponytail: reduced from 10s to 5s — faster reconnection after startup, still enough for init
        private const val STARTUP_GRACE_PERIOD_MS = 5_000L

        /**
         * Check if service is currently running
         */
        @Volatile
        var isRunning = false
            private set

        /**
         * Running service instance for direct API access (e.g., sendChatMessage).
         * Null when service is not running.
         */
        @Volatile
        var instance: YggmailService? = null
            private set

        /**
         * Start the Yggmail service
         */
        fun start(context: Context) {
            val intent = Intent(context, YggmailService::class.java).apply {
                action = ACTION_START
            }
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                // Android 12+ may throw ForegroundServiceStartNotAllowedException
                // if app is in background or doesn't meet other foreground service requirements
                TyrLogger.e(TAG,"Failed to start foreground service", e)
                // Service will not start, but we don't crash the app
            }
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
         * Soft stop the Yggmail service (gracefully disconnect peers first)
         */
        fun softStop(context: Context) {
            val intent = Intent(context, YggmailService::class.java).apply {
                action = ACTION_SOFT_STOP
            }
            context.startService(intent)
        }

        /**
         * Cancel the chat notification for a specific sender.
         * Call this when the user opens a conversation to dismiss the notification.
         */
        fun cancelChatNotificationForSender(context: Context, senderAddress: String) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(CHAT_NOTIFICATION_BASE_ID + senderAddress.hashCode())
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

    // Coroutine scope for I/O-bound operations (IMAP polling, DB access)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Shared ChatRepository instance — created once, reuses the same SQLite connection
    private val chatRepository by lazy { ChatRepository(this) }

    // Service state
    @Volatile private var serviceStartTime = 0L
    private var yggmailMobile: YggmailMobile? = null
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
    private val statusListeners = java.util.concurrent.CopyOnWriteArrayList<ServiceStatusListener>()

    // Connection status tracking
    private var lastConnectionStatus: String? = null
    private var connectionCheckRunnable: Runnable? = null

    // Background chat polling (initialized on serviceThread in onCreate)
    private lateinit var chatPollHandler: Handler
    @Volatile private var chatPollRunnable: Runnable? = null

    // Battery optimization state
    // Start as active so initial peer connections use 5s QUIC keepalive.
    // Doze Mode receiver will switch to inactive when the device enters Doze.
    // onPause() deliberately does NOT call setAppActive(false) — see MainActivity.
    private var isAppActive = true // Track if app is in foreground
    private var isCharging = false // Track if device is charging
    private var isDozing = false // Track if device is in Doze Mode

    // Battery optimization: Track active send operations
    private var activeSendOperations = java.util.concurrent.atomic.AtomicInteger(0)
    private var lastSendActivity = System.currentTimeMillis()

    // Guard against concurrent IMAP poll runs (e.g. timer + onNewMail firing together)
    private val isFetchInProgress = java.util.concurrent.atomic.AtomicBoolean(false)

    // Doze Mode and Battery receivers
    private val dozeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                    val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                    val wasDozing = isDozing
                    isDozing = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        powerManager.isDeviceIdleMode
                    } else {
                        false
                    }

                    if (wasDozing != isDozing) {
                        TyrLogger.d(TAG,"[Battery] Doze mode changed: $isDozing")
                        updateNativeServicePowerState()
                        // When exiting Doze with app active, QUIC connections were closed
                        // during Doze (60s keepAlive → 5s keepAlive transition). Force
                        // immediate peer reconnection instead of waiting for yggdrasil's
                        // internal reconnect backoff timer.
                        if (!isDozing && isAppActive) {
                            TyrLogger.d(TAG,"[Reconnect] Exiting Doze, forcing immediate peer reconnection")
                            hotReloadPeers()
                        }
                    }
                }
            }
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    isCharging = true
                    TyrLogger.d(TAG,"[Battery] Device charging started")
                    updateNativeServicePowerState()
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    isCharging = false
                    TyrLogger.d(TAG,"[Battery] Device on battery")
                    updateNativeServicePowerState()
                }
            }
        }
    }

    // ponytail: callbacks removed — UniFFI 0.31.1 doesn't support callback interface in UDL.
    // IMAP polling (every 2 min) catches new messages. Re-add callbacks when UniFFI is upgraded.

    // Mail activity monitoring for adaptive heartbeat
    // No periodic polling needed - yggmail library handles adaptive heartbeat internally
    // We only notify on actual SMTP/IMAP activity via setAppActive()

    // Binder for local service binding
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): YggmailService = this@YggmailService
    }

    override fun onCreate() {
        super.onCreate()
        TyrLogger.d(TAG,"Service onCreate")
        instance = this

        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        serviceThread = HandlerThread("YggmailServiceThread").apply { start() }
        serviceHandler = Handler(serviceThread.looper)
        // Use the same background thread for chat polling to avoid tying up main looper
        chatPollHandler = Handler(serviceThread.looper)

        // Acquire wake lock (will be used only for critical operations, not continuously)
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Tyr::YggmailService"
        ).apply {
            setReferenceCounted(false)
        }

        // Register Doze Mode receiver for battery optimization
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val dozeFilter = IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            registerReceiver(dozeReceiver, dozeFilter)
            TyrLogger.d(TAG,"[Battery] Doze Mode receiver registered")
        }

        // Register battery charging receiver
        val batteryFilter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(batteryReceiver, batteryFilter)
        TyrLogger.d(TAG,"[Battery] Battery receiver registered")

        // Check initial charging state
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        batteryIntent?.let {
            val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
            TyrLogger.d(TAG,"[Battery] Initial charging state: $isCharging")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TyrLogger.d(TAG,"Service onStartCommand: ${intent?.action}")

        when (intent?.action) {
            ACTION_START -> {
                if (!isRunning) {
                    startForegroundWithNotification()
                    startYggmail()
                } else {
                    TyrLogger.w(TAG,"Service already running, ignoring START action")
                }
            }
            ACTION_STOP -> {
                // Post stopSelf() to happen AFTER stopYggmail completes on serviceHandler thread
                // This prevents race condition where onDestroy() is called while stop is in progress
                serviceHandler.post {
                    stopYggmailSync()
                    // Call stopSelf on main thread after cleanup completes
                    mainHandler.post {
                        stopSelf()
                    }
                }
            }
            ACTION_SOFT_STOP -> {
                // Soft stop - gracefully disconnect peers first, then stop
                serviceHandler.post {
                    performSoftStopSync()
                    // Call stopSelf on main thread after cleanup completes
                    mainHandler.post {
                        stopSelf()
                    }
                }
            }
            ACTION_RECONNECT_PEERS -> {
                // Triggered by network change (WiFi <-> Mobile switch)
                if (isRunning) {
                    val timeSinceStart = System.currentTimeMillis() - serviceStartTime
                    if (serviceStartTime > 0 && timeSinceStart < STARTUP_GRACE_PERIOD_MS) {
                        TyrLogger.d(TAG,"Ignoring reconnect request during startup grace period (${timeSinceStart}ms since start)")
                    } else {
                        TyrLogger.i(TAG,"Network changed - reconnecting peers")
                        hotReloadPeers()
                    }
                }
            }
            ACTION_MAINTENANCE_CHECK -> {
                // Triggered by MaintenanceReceiver — check peers and reconnect if needed
                if (isRunning) {
                    val timeSinceStart = System.currentTimeMillis() - serviceStartTime
                    if (serviceStartTime > 0 && timeSinceStart < STARTUP_GRACE_PERIOD_MS) {
                        TyrLogger.d(TAG,"Ignoring maintenance check during startup grace period (${timeSinceStart}ms since start)")
                    } else {
                        serviceHandler.post {
                            try {
                                val connections = getPeerConnections()
                                val hasConnectedPeer = connections?.any { it.up } == true
                                if (!hasConnectedPeer) {
                                    TyrLogger.w(TAG,"Maintenance: no connected peers — triggering reconnection")
                                    hotReloadPeers()
                                } else {
                                    TyrLogger.d(TAG,"Maintenance: peers OK (${connections?.count { it.up }} connected)")
                                }
                            } catch (e: Exception) {
                                TyrLogger.e(TAG,"Error during maintenance check", e)
                            }
                        }
                    }
                }
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
        TyrLogger.d(TAG,"Service onDestroy")
        instance = null

        // Cancel maintenance scheduling
        MaintenanceReceiver.cancelMaintenance(this)

        // Unregister receivers
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                unregisterReceiver(dozeReceiver)
                TyrLogger.d(TAG,"[Battery] Doze Mode receiver unregistered")
            }
        } catch (e: Exception) {
            TyrLogger.e(TAG,"Error unregistering doze receiver", e)
        }

        try {
            unregisterReceiver(batteryReceiver)
            TyrLogger.d(TAG,"[Battery] Battery receiver unregistered")
        } catch (e: Exception) {
            TyrLogger.e(TAG,"Error unregistering battery receiver", e)
        }

        // Only stop if not already stopped (prevent duplicate stop calls)
        if (isRunning) {
            TyrLogger.w(TAG,"Service destroyed while still running - forcing cleanup")
            // Post to handler and wait for completion to avoid race conditions
            val latch = java.util.concurrent.CountDownLatch(1)
            serviceHandler.post {
                try {
                    stopYggmailSync()
                } finally {
                    latch.countDown()
                }
            }
            // Wait up to 5 seconds for cleanup to complete
            try {
                latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                TyrLogger.e(TAG,"Interrupted while waiting for service cleanup", e)
            }
        }

        // Now safe to quit the thread
        serviceThread.quitSafely()
        try {
            // Wait for thread to actually terminate (max 2 seconds)
            serviceThread.join(2000)
        } catch (e: InterruptedException) {
            TyrLogger.e(TAG,"Interrupted while waiting for service thread termination", e)
        }

        releaseWakeLock()

        // Ensure notification is removed
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        notificationManager.cancel(NOTIFICATION_ID)

        // Cancel all coroutines launched in this service
        serviceScope.cancel()

        super.onDestroy()
    }

    /**
     * Start foreground service with notification
     */
    private fun startForegroundWithNotification() {
        try {
            val notification = createNotification(ServiceStatus.STARTING)
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            // Handle potential exceptions from startForeground()
            // (e.g., on Android 12+ if foreground service type restrictions are violated)
            TyrLogger.e(TAG,"Failed to start foreground with notification", e)
            // Update status to ERROR and stop service
            lastError = "Failed to start foreground service: ${e.message}"
            updateStatus(ServiceStatus.ERROR)
            stopSelf()
        }
    }

    /**
     * Start Yggmail service on background thread
     */
    private fun startYggmail() {
        serviceHandler.post {
            if (isRunning) return@post
            startYggmailSync()
        }
    }

    /**
     * Synchronous start logic (called from handler thread)
     */
    private fun startYggmailSync() {
        try {
            TyrLogger.i(TAG, "Starting Yggmail service...")
            updateStatus(ServiceStatus.STARTING)

            val password = configRepository.getPassword()
            if (password.isNullOrEmpty()) {
                throw IllegalStateException("Password not configured")
            }

            val peersStr = configRepository.getPeersString() ?: ""
            val peersList = if (peersStr.isBlank()) emptyList() else peersStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val dbPath = File(filesDir, "yggmail.db").absolutePath
            TyrLogger.d(TAG, "Database path: $dbPath")

            // Compute SHA-256 password hash for SMTP/IMAP authentication
            val passwordHash = MessageDigest.getInstance("SHA-256")
                .digest(password.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

            // Create config with all parameters in one go
            val config = YggmailConfig(
                privateKey = "",  // auto-generated by Rust when empty
                peers = peersList,
                listen = emptyList(),  // use default
                groupPassword = "",
                databasePath = dbPath,
                smtpPort = 1025.toUShort(),
                imapPort = 1143.toUShort(),
                passwordHash = passwordHash  // SHA-256 hash for SMTP/IMAP auth
            )

            // Create instance (auto-inits tracing)
            val mobile = YggmailMobile()

            // Start with config 
            mobile.start(config)

            // Set password after start so the running instance picks it up
            mobile.setPassword(password)

            yggmailMobile = mobile

            // Save mail address for display
            val state = mobile.getState()
            configRepository.saveMailAddress(state.address)
            configRepository.savePublicKey(state.publicKey)
            TyrLogger.i(TAG, "Mail address: ${state.address}")

            // Start SMTP and IMAP servers for DeltaChat/TyrChat
            mobile.startSmtp(1025.toUShort(), passwordHash)
            mobile.startImap(1143.toUShort(), passwordHash)
            TyrLogger.i(TAG, "SMTP+IMAP servers started")

            // Update native service with current power state
            updateNativeServicePowerState()

            isRunning = true
            serviceStartTime = System.currentTimeMillis()
            updateStatus(ServiceStatus.RUNNING)

            startConnectionStatusCheck()
            startChatPolling()
            MaintenanceReceiver.scheduleMaintenance(this@YggmailService)

        } catch (e: YggmailException) {
            TyrLogger.e(TAG, "Failed to start Yggmail service", e)
            lastError = e.message
            updateStatus(ServiceStatus.ERROR)
            mainHandler.post { stopSelf() }
        } catch (e: Exception) {
            TyrLogger.e(TAG, "Failed to start Yggmail service", e)
            lastError = e.message
            updateStatus(ServiceStatus.ERROR)
            mainHandler.post { stopSelf() }
        }
    }

    /**
     * Synchronous stop logic (called from handler thread)
     * Thread-safe with proper exception handling for native library cleanup
     * Includes comprehensive panic/crash recovery for native library issues
     */
    private fun stopYggmailSync() {
        stopConnectionStatusCheck()
        stopChatPolling()

        synchronized(this) {
            if (!isRunning) {
                TyrLogger.w(TAG, "Service already stopped")
                return
            }
            isRunning = false
            serviceStartTime = 0L
            updateStatus(ServiceStatus.STOPPING)
            TyrLogger.i(TAG, "Stopping Yggmail service...")
        }

        try {
            acquireWakeLockForOperation("shutdown", 20_000)
            yggmailMobile?.stop()
            yggmailMobile = null
            TyrLogger.i(TAG, "Yggmail service stopped")
        } catch (e: Exception) {
            TyrLogger.e(TAG, "Error stopping service", e)
            lastError = e.message
            updateStatus(ServiceStatus.ERROR)
        } finally {
            releaseWakeLock()
            yggmailMobile = null
            updateStatus(ServiceStatus.STOPPED)
            mainHandler.post {
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } else {
                        @Suppress("DEPRECATION")
                        stopForeground(true)
                    }
                } catch (e: Exception) {
                    TyrLogger.e(TAG, "Error removing notification", e)
                }
            }
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
                    TyrLogger.d(TAG,"WakeLock released")
                }
            }
        } catch (e: Exception) {
            TyrLogger.e(TAG,"Error releasing WakeLock", e)
        }
    }

    /**
     * Update native service with current power state for adaptive battery optimization.
     * This informs the yggmail library about device state so it can adjust:
     * - QUIC keep-alive intervals (60s on battery, 15s when charging, 5s when active)
     * - IMAP heartbeat intervals (3-29min on battery, more frequent when charging)
     */
    private fun updateNativeServicePowerState() {
        serviceHandler.post {
            try {
                yggmailMobile?.let { mobile ->
                    mobile.setActive(isAppActive && !isDozing)
                    mobile.setCharging(isCharging)
                    TyrLogger.d(TAG, "[Battery] Power state updated - Active: ${isAppActive && !isDozing}, Charging: $isCharging")
                }
            } catch (e: Exception) {
                TyrLogger.e(TAG, "Error updating power state", e)
            }
        }
    }

    /**
     * Acquire WakeLock for a critical operation only.
     * Battery optimization: WakeLock is NOT held continuously, only for specific operations.
     *
     * @param operation Name of the operation for logging
     * @param durationMs Maximum duration to hold the lock (default 30 seconds)
     */
    private fun acquireWakeLockForOperation(operation: String, durationMs: Long = 30_000) {
        try {
            wakeLock?.let { lock ->
                if (lock.isHeld) {
                    lock.release()
                }
                lock.acquire(durationMs)
                TyrLogger.d(TAG,"[Battery] WakeLock acquired for $operation (${durationMs}ms)")
            }
        } catch (e: Exception) {
            TyrLogger.e(TAG,"Error acquiring WakeLock", e)
        }
    }

    /**
     * Notify that a message send operation started.
     * Acquires brief WakeLock for the operation.
     * Battery optimization: WakeLock only for 30 seconds, not continuously.
     */
    fun notifyMessageSendStarted() {
        activeSendOperations.incrementAndGet()
        lastSendActivity = System.currentTimeMillis()

        serviceHandler.post {
            acquireWakeLockForOperation("message_send", 30_000)
        }
    }

    /**
     * Notify that a message send operation completed.
     * WakeLock will automatically release after timeout.
     */
    fun notifyMessageSendCompleted() {
        activeSendOperations.decrementAndGet()
        TyrLogger.d(TAG,"[Battery] Message send completed, active operations: ${activeSendOperations.get()}")
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
     * Get connection status based on peer connections.
     * When running, returns the number of connected peers for display in the notification.
     */
    private fun getConnectionStatus(): String {
        return when (serviceStatus) {
            ServiceStatus.STARTING -> getString(R.string.connection_connecting)
            ServiceStatus.STOPPING -> getString(R.string.service_stopping)
            ServiceStatus.STOPPED -> getString(R.string.connection_offline)
            ServiceStatus.ERROR -> lastError ?: getString(R.string.service_error)
            ServiceStatus.RUNNING -> {
                val connections = getPeerConnections()
                val connected = connections?.filter { it.up } ?: emptyList()
                val connectedCount = connected.size
                if (connectedCount > 0) {
                    val avgLatency = connected.map { it.latencyMs }.filter { it > 0 }.let {
                        if (it.isNotEmpty()) it.average().toLong() else 0L
                    }
                    if (avgLatency > 0) {
                        getString(R.string.notification_peers_connected_ping, connectedCount, avgLatency)
                    } else {
                        getString(R.string.notification_peers_connected, connectedCount)
                    }
                } else {
                    getString(R.string.notification_no_connection)
                }
            }
        }
    }

    /**
     * Start periodic connection status check to update notification
     * ponytail: checks every 10s instead of 30s for faster peer-loss detection
     */
    private fun startConnectionStatusCheck() {
        stopConnectionStatusCheck() // Clear any existing checks

        connectionCheckRunnable = object : Runnable {
            override fun run() {
                try {
                    val currentStatus = getConnectionStatus()
                    if (currentStatus != lastConnectionStatus) {
                        lastConnectionStatus = currentStatus
                        // Update notification on main thread
                        mainHandler.post {
                            val notification = createNotification(serviceStatus)
                            notificationManager.notify(NOTIFICATION_ID, notification)
                        }
                    }
                } catch (e: Exception) {
                    TyrLogger.e(TAG,"Error checking connection status", e)
                }

                // Schedule next check
                if (serviceStatus == ServiceStatus.RUNNING) {
                    mainHandler.postDelayed(this, 10_000)
                }
            }
        }

        // Start first check after 5 seconds (give time for connections to establish)
        mainHandler.postDelayed(connectionCheckRunnable!!, 5_000)
    }

    /**
     * Stop periodic connection status check
     */
    private fun stopConnectionStatusCheck() {
        connectionCheckRunnable?.let {
            mainHandler.removeCallbacks(it)
        }
        connectionCheckRunnable = null
        lastConnectionStatus = null
    }

    /**
     * Create notification for current service status
     * Optimized for low battery usage with PRIORITY_MIN
     * Shows connection status based on peer connections instead of service status
     */
    private fun createNotification(status: ServiceStatus): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val statusText = getConnectionStatus()

        return NotificationCompat.Builder(this, TyrApplication.CHANNEL_ID_SERVICE)
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentIntent(pendingIntent)
            .setOngoing(status == ServiceStatus.RUNNING || status == ServiceStatus.STARTING)
            .setPriority(NotificationCompat.PRIORITY_MIN) // Optimized: was PRIORITY_LOW
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false) // Hide timestamp for cleaner notification
            .build()
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
            val jsonString = yggmailMobile?.getPeerConnectionsJson() ?: return null
            parsePeerConnectionsJSON(jsonString)
        } catch (e: Exception) {
            TyrLogger.e(TAG,"Error getting peer connections", e)
            null
        }
    }

    /**
     * Parse JSON string to list of PeerConnectionInfo
     */
    private fun parsePeerConnectionsJSON(json: String): List<PeerConnectionInfo> {
        if (json.isEmpty() || json == "[]") return emptyList()
        val peers = mutableListOf<PeerConnectionInfo>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (obj.optString("uri").isNotEmpty()) {
                    peers.add(PeerConnectionInfo(
                        uri = obj.optString("uri"),
                        up = obj.optBoolean("up"),
                        inbound = obj.optBoolean("inbound"),
                        lastError = obj.optString("lastError", ""),
                        key = obj.optString("key", ""),
                        uptime = obj.optLong("uptime", 0L),
                        latencyMs = obj.optLong("latencyMs", 0L),
                        rxBytes = obj.optLong("rxBytes", 0L),
                        txBytes = obj.optLong("txBytes", 0L),
                        rxRate = obj.optLong("rxRate", 0L),
                        txRate = obj.optLong("txRate", 0L)
                    ))
                }
            }
        } catch (e: Exception) {
            TyrLogger.e(TAG, "Error parsing peer connections JSON: $json", e)
        }
        return peers
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
     * Notify service that app is in foreground (active).
     * This triggers more responsive network intervals in the native library.
     * Battery optimization: Updates native library power state for adaptive behavior.
     */
    fun setAppActive(active: Boolean) {
        try {
            val wasEffectivelyActive = isAppActive && !isDozing
            isAppActive = active
            TyrLogger.d(TAG,"[Battery] App activity state changed to: $active")

            // Update native service with new power state
            updateNativeServicePowerState()

            // When becoming effectively active (e.g. app opened after Doze exit),
            // QUIC keepAlive changed (60s→5s), closing existing connections.
            // Force immediate peer reconnection instead of waiting for internal backoff.
            val isNowEffectivelyActive = isAppActive && !isDozing
            if (isNowEffectivelyActive && !wasEffectivelyActive) {
                TyrLogger.d(TAG,"[Reconnect] Became active, forcing immediate peer reconnection")
                hotReloadPeers()
            }

        } catch (e: Exception) {
            TyrLogger.e(TAG,"Error setting app activity state", e)
        }
    }


    /**
     * Attempt to retry sending all queued messages for [destination].
     * Delegates to Go layer: resets backoff counters and triggers immediate
     * queue processing for this peer. [messageId] is informational only —
     * the Go queue processes all pending messages per destination together.
     * Returns true if the destination address is valid and retry was triggered.
     */
    fun retrySendMessage(destination: String, messageId: Int): Boolean {
        if (!isRunning) return false
        val latch = java.util.concurrent.CountDownLatch(1)
        var result = false
        serviceHandler.post {
            try {
                hotReloadPeers()
                result = true
            } catch (e: Exception) {
                TyrLogger.e(TAG,"retrySendMessage failed for $destination", e)
            } finally {
                latch.countDown()
            }
        }
        latch.await(5, TimeUnit.SECONDS)
        TyrLogger.d(TAG,"retrySendMessage: dest=$destination id=$messageId triggered=$result")
        return result
    }

    fun sendChatMessage(from: String, to: String, body: String, readReceiptUid: Long): String? {
        val latch = CountDownLatch(1)
        var error: String? = null
        serviceScope.launch {
            try {
                SmtpSender().send(from, configRepository.getPassword() ?: "", to, body, 0L)
            } catch (e: Exception) {
                error = e.message
                TyrLogger.e(TAG, "sendChatMessage failed", e)
            } finally {
                latch.countDown()
            }
        }
        latch.await(10, TimeUnit.SECONDS)
        return error
    }

    /**
     * Hot reload peers without restarting the entire service
     * Uses Yggdrasil Core's AddPeer/RemovePeer for live updates without reconnection
     */
    fun hotReloadPeers() {
        serviceHandler.post {
            try {
                TyrLogger.i(TAG,"Hot reloading peers...")

                // Get updated configuration and convert to JSON array
                val peersStr = configRepository.getPeersString()

                val peersJsonArray = if (peersStr.isNullOrBlank()) "[]"
                    else JSONArray(peersStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }).toString()
                yggmailMobile?.updatePeers(peersJsonArray)

                TyrLogger.i(TAG,"Peers updated successfully using live configuration")

            } catch (e: Exception) {
                TyrLogger.e(TAG,"Error updating peers", e)
            }
        }
    }

    /**
     * Hot reload password without restarting the entire service
     */
    fun hotReloadPassword() {
        serviceHandler.post {
            try {
                TyrLogger.i(TAG, "Hot reloading password...")
                val password = configRepository.getPassword() ?: return@post
                yggmailMobile?.setPassword(password)
                TyrLogger.i(TAG, "Password updated successfully in running service")
            } catch (e: Exception) {
                TyrLogger.e(TAG, "Error updating password", e)
            }
        }
    }

    /**
     * Synchronous soft stop (must be called from service handler thread)
     */
    private fun performSoftStopSync() {
        try {
            TyrLogger.i(TAG,"Performing soft stop...")
            updateStatus(ServiceStatus.STOPPING)

            // First, gracefully disconnect all peers by updating to empty peer list
            yggmailMobile?.updatePeers("[]")
            TyrLogger.i(TAG,"All peers disconnected gracefully")

            // Now perform normal stop
            stopYggmailSync()

        } catch (e: Exception) {
            TyrLogger.e(TAG,"Error during soft stop, falling back to normal stop", e)
            // Fallback to normal stop if soft stop fails
            stopYggmailSync()
        }
    }

    // setPeerBatchingParams, findAvailablePeersAsync, getAvailableRegions removed from UniFFI API

    /**
     * Soft stop: Gracefully disconnect peers before stopping the service
     * This method disconnects all peers cleanly to avoid ErrClosed errors in logs
     *
     * Unlike immediate stop, this approach:
     * - First disconnects all peers using updatePeers("[]") - empty JSON array
     * - Gives time for graceful disconnection
     * - Then performs normal service shutdown
     * - Avoids ErrClosed errors in logs
     */
    fun softStop() {
        serviceHandler.post {
            performSoftStopSync()
        }
    }

    // ---- Background chat polling ----

    private fun startChatPolling() {
        stopChatPolling()
        chatPollRunnable = object : Runnable {
            override fun run() {
                if (isRunning) {
                    pollInboxForNewMessages()
                    chatPollHandler.postDelayed(this, CHAT_POLL_INTERVAL_MS)
                }
            }
        }
        // First poll after grace period so service has time to connect peers,
        // subsequent polls use full interval
        chatPollHandler.postDelayed(chatPollRunnable!!, STARTUP_GRACE_PERIOD_MS)
    }

    private fun stopChatPolling() {
        if (!::chatPollHandler.isInitialized) return
        chatPollRunnable?.let { chatPollHandler.removeCallbacks(it) }
        chatPollRunnable = null
    }

    private fun pollInboxForNewMessages() {
        if (!isFetchInProgress.compareAndSet(false, true)) {
            TyrLogger.i(TAG, "Chat poll: fetch already in progress, skipping")
            return
        }
        val address = configRepository.getMailAddress() ?: run { isFetchInProgress.set(false); return }
        val password = configRepository.getPassword() ?: run { isFetchInProgress.set(false); return }

        serviceScope.launch {
            try {
                // Use the watermark (max of DB-derived UID and the persisted high-water mark).
                // The persisted value never decreases even when messages/contacts are deleted,
                // so removing a contact cannot cause its old IMAP messages to be re-fetched.
                val dbMaxUid = chatRepository.getMaxImapUid()
                val watermark = configRepository.getLastSeenImapUid()
                val sinceUid = maxOf(dbMaxUid, watermark)
                TyrLogger.i(TAG, "Chat poll: starting fetch sinceUid=$sinceUid (db=$dbMaxUid watermark=$watermark) address=$address")
                val attachmentsDir = File(
                    getExternalFilesDir(null) ?: filesDir, "attachments"
                ).also { it.mkdirs() }
                val result = ImapFetcher(cacheDir = cacheDir).fetchNewMessages(address, password, sinceUid, attachmentsDir)
                when (result) {
                    is ImapFetcher.Result.Error -> {
                        TyrLogger.e(TAG, "Chat poll: IMAP fetch error: ${result.message}")
                    }
                    is ImapFetcher.Result.Success -> {
                        TyrLogger.i(TAG, "Chat poll: fetch returned ${result.data.messages.size} messages, " +
                            "${result.data.deliveryReceiptTimestamps.size} delivery receipts, " +
                            "${result.data.readReceiptTimestamps.size} read receipts")

                        // Advance the watermark to the highest UID seen in this batch so that
                        // deleting contacts/messages later cannot roll back the fetch position.
                        val maxFetchedUid = result.data.messages.maxOfOrNull { it.imapUid } ?: -1L
                        if (maxFetchedUid > 0) configRepository.advanceLastSeenImapUid(maxFetchedUid)

                        val newMessages = mutableListOf<com.jbselfcompany.tyr.chat.data.ChatMessage>()
                        val acceptNonContacts = configRepository.getAcceptMessagesFromNonContacts()
                        val nicknameMap = result.data.nicknameUpdates.associate { it.senderAddr to it.nickname }

                        // Insert incoming messages we haven't seen yet
                        for (msg in result.data.messages) {
                            TyrLogger.d(TAG, "Chat poll: processing msg uid=${msg.imapUid} " +
                                "from=${msg.fromAddr} isSent=${msg.isSent} " +
                                "hasAttachment=${msg.hasAttachment} attachMime=${msg.attachmentMimeType} " +
                                "attachSize=${msg.attachmentSizeBytes} attachPath=${msg.attachmentPath}")
                            if (msg.isSent) {
                                TyrLogger.d(TAG, "Chat poll: skipping own sent msg uid=${msg.imapUid}")
                                continue
                            }
                            if (chatRepository.imapUidExists(msg.imapUid)) {
                                TyrLogger.d(TAG, "Chat poll: uid=${msg.imapUid} already in DB, skipping")
                                continue
                            }
                            if (chatRepository.isContactDeclined(msg.fromAddr)) {
                                TyrLogger.d(TAG, "Chat poll: from=${msg.fromAddr} is declined, skipping")
                                continue
                            }
                            when {
                                chatRepository.contactExists(msg.fromAddr) -> {
                                    TyrLogger.i(TAG, "Chat poll: inserting msg uid=${msg.imapUid} " +
                                        "from=${msg.fromAddr} hasAttachment=${msg.hasAttachment}")
                                    chatRepository.insertMessage(msg)
                                    newMessages.add(msg)
                                }
                                acceptNonContacts -> {
                                    // Store as pending contact with nickname if available
                                    TyrLogger.i(TAG, "Chat poll: new contact from=${msg.fromAddr}, " +
                                        "adding as pending and inserting msg uid=${msg.imapUid}")
                                    chatRepository.addPendingContact(
                                        com.jbselfcompany.tyr.chat.data.ChatContact(
                                            address = msg.fromAddr, name = nicknameMap[msg.fromAddr] ?: ""
                                        )
                                    )
                                    chatRepository.insertMessage(msg.copy(isRead = false))
                                    newMessages.add(msg)
                                }
                                else -> {
                                    // Sender not in contacts and acceptNonContacts=false.
                                    // Message is intentionally dropped per user settings.
                                    TyrLogger.w(TAG, "Chat poll: DROPPED msg uid=${msg.imapUid} " +
                                        "from=${msg.fromAddr} — sender not in contacts and " +
                                        "acceptNonContacts=false. Go to Contacts and add this address, " +
                                        "or enable 'Accept messages from unknown senders' in Settings.")
                                }
                            }
                        }

                        // Apply nickname updates to known contacts with blank names
                        for ((senderAddr, nickname) in nicknameMap) {
                            val contact = chatRepository.getContact(senderAddr)
                            if (contact != null && contact.name.isBlank()) {
                                chatRepository.updateContactName(senderAddr, nickname)
                            }
                        }

                        // Delivery receipts → single checkmark (only if still SENDING)
                        var deliveryReceiptsApplied = false
                        for (receipt in result.data.deliveryReceiptTimestamps) {
                            chatRepository.updateSentMessageStatusNearTimestamp(
                                address, receipt.senderAddr, receipt.originalTimestamp,
                                com.jbselfcompany.tyr.chat.data.ChatMessage.STATUS_SENT,
                                com.jbselfcompany.tyr.chat.data.ChatMessage.STATUS_SENDING
                            )
                            deliveryReceiptsApplied = true
                        }
                        // Broadcast whenever anything changed so ConversationActivity reloads
                        // immediately — previously only fired when newMessages was non-empty,
                        // which meant delivery-receipt status updates (SENDING→SENT) were
                        // invisible until the user manually triggered a reload.
                        if (deliveryReceiptsApplied) {
                            sendBroadcast(Intent(ACTION_NEW_CHAT_MESSAGES))
                            TyrLogger.d(TAG, "Chat poll: delivery receipts applied, broadcast sent")
                        }
                        if (newMessages.isNotEmpty()) {
                            showChatNotifications(newMessages, chatRepository)
                            sendBroadcast(Intent(ACTION_NEW_CHAT_MESSAGES))
                            TyrLogger.i(TAG, "Chat poll: ${newMessages.size} new message(s) inserted, broadcast sent")

                            // Send automatic delivery receipts so sender gets single checkmark
                            for (msg in newMessages) {
                                try {
                                    SmtpSender().send(
                                        fromAddress = address,
                                        password = password,
                                        toAddress = msg.fromAddr,
                                        body = "",
                                        deliveryReceiptTimestamp = msg.timestamp
                                    )
                                } catch (e: Exception) {
                                    TyrLogger.w(TAG, "Failed to send delivery receipt to ${msg.fromAddr}", e)
                                }
                            }
                        } else if (result.data.messages.isEmpty() && result.data.deliveryReceiptTimestamps.isEmpty()) {
                            TyrLogger.d(TAG, "Chat poll: nothing new")
                        }
                    }
                }
            } catch (e: Exception) {
                TyrLogger.e(TAG, "Error polling inbox for chat messages", e)
            } finally {
                isFetchInProgress.set(false)
            }
        }
    }

    private fun showChatNotifications(
        newMessages: List<com.jbselfcompany.tyr.chat.data.ChatMessage>,
        chatRepository: ChatRepository
    ) {
        val address = configRepository.getMailAddress() ?: return
        val bySender = newMessages.groupBy { it.fromAddr }
        for ((fromAddr, messages) in bySender) {
            // If the user is currently viewing this conversation, suppress the notification
            // and mark the messages as read immediately instead.
            if (fromAddr.equals(
                    com.jbselfcompany.tyr.chat.ui.ConversationActivity.activeChatAddress,
                    ignoreCase = true
                )
            ) {
                chatRepository.markConversationRead(address, fromAddr)
                TyrLogger.d(TAG, "Chat poll: suppressed notification for open conversation ($fromAddr)")
                continue
            }
            val contact = chatRepository.getContact(fromAddr)
            val senderName = if (!contact?.name.isNullOrBlank()) contact!!.name else fromAddr

            val conversationIntent = Intent(
                this,
                com.jbselfcompany.tyr.chat.ui.ConversationActivity::class.java
            ).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(
                    com.jbselfcompany.tyr.chat.ui.ConversationActivity.EXTRA_CONTACT_ADDRESS,
                    fromAddr
                )
                putExtra(
                    com.jbselfcompany.tyr.chat.ui.ConversationActivity.EXTRA_CONTACT_NAME,
                    contact?.name ?: ""
                )
            }
            val pendingIntent = PendingIntent.getActivity(
                this,
                CHAT_NOTIFICATION_BASE_ID + fromAddr.hashCode(),
                conversationIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val title = if (messages.size == 1)
                getString(R.string.notification_new_message_from, senderName)
            else
                getString(R.string.notification_new_messages_from, messages.size, senderName)
            val lastMsg = messages.last()
            val content = when {
                lastMsg.body.isNotBlank() -> lastMsg.body.take(100)
                lastMsg.hasAttachment -> {
                    val isImage = lastMsg.attachmentMimeType?.startsWith("image/") == true
                    val name = lastMsg.attachmentName
                    if (isImage) {
                        if (!name.isNullOrBlank()) "\uD83D\uDDBC $name" else getString(R.string.chat_preview_photo)
                    } else {
                        if (!name.isNullOrBlank()) "\uD83D\uDCCE $name" else getString(R.string.chat_preview_file)
                    }
                }
                else -> "\u2026"
            }

            val notification = androidx.core.app.NotificationCompat.Builder(this, TyrApplication.CHANNEL_ID_CHAT)
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setContentTitle(title)
                .setContentText(content)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .build()

            notificationManager.notify(CHAT_NOTIFICATION_BASE_ID + fromAddr.hashCode(), notification)
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
