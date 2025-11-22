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
import com.jbselfcompany.tyr.mobile.LogCallback
import com.jbselfcompany.tyr.mobile.YggmailService as MobileYggmailService
import java.io.File

/**
 * Foreground service that runs Yggmail server.
 * Manages lifecycle of Yggmail service and provides status updates.
 */
class YggmailService : Service(), LogCallback {

    companion object {
        private const val TAG = "YggmailService"
        private const val NOTIFICATION_ID = 1001

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
        stopYggmail()

        serviceThread.quitSafely()

        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }

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
            yggmailService = MobileYggmailService(dbPath, smtpAddr, imapAddr).apply {
                setLogCallback(this@YggmailService)
            }

            // Initialize (creates/loads keys)
            yggmailService?.initialize()
            Log.d(TAG, "Yggmail initialized")

            // Set password
            yggmailService?.setPassword(password)
            Log.d(TAG, "Password configured")

            // Save mail address for display
            val mailAddress = yggmailService?.mailAddress ?: ""
            val publicKey = yggmailService?.publicKey ?: ""
            configRepository.saveMailAddress(mailAddress)
            configRepository.savePublicKey(publicKey)
            Log.i(TAG, "Mail address: $mailAddress")

            // Start with configured peers
            yggmailService?.start(peers, multicastEnabled, ".*")
            Log.i(TAG, "Yggmail service started successfully")

            // Acquire wake lock
            wakeLock?.acquire()

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

            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }

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
            ServiceStatus.STARTING -> getString(R.string.notification_status_starting)
            ServiceStatus.RUNNING -> getString(R.string.notification_status_running)
            ServiceStatus.STOPPING -> getString(R.string.notification_status_stopping)
            ServiceStatus.STOPPED -> getString(R.string.notification_status_stopped)
            ServiceStatus.ERROR -> lastError ?: getString(R.string.notification_status_error)
        }

        return NotificationCompat.Builder(this, TyrApplication.CHANNEL_ID_SERVICE)
            .setContentTitle(getString(R.string.service_status))
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(status == ServiceStatus.RUNNING || status == ServiceStatus.STARTING)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /**
     * LogCallback implementation for Yggmail logs
     */
    override fun onLog(level: String?, tag: String?, message: String?) {
        val logTag = "YggmailService"
        val logMessage = "[$tag] $message"

        when (level?.uppercase()) {
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
