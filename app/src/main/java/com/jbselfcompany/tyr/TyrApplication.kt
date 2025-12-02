package com.jbselfcompany.tyr

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.jbselfcompany.tyr.data.ConfigRepository
import com.jbselfcompany.tyr.receiver.NetworkChangeReceiver
import com.jbselfcompany.tyr.utils.LocaleHelper

/**
 * Application class for Tyr.
 * Initializes global application state and notification channels.
 *
 * Battery optimization: Registers NetworkCallback with 15-second delay
 * to avoid unnecessary network monitoring during app startup
 */
class TyrApplication : Application() {

    companion object {
        const val CHANNEL_ID_SERVICE = "yggmail_service"
        const val CHANNEL_ID_MAIL = "mail_notifications"

        lateinit var instance: TyrApplication
            private set
    }

    lateinit var configRepository: ConfigRepository
        private set

    var yggmailServiceBinder: com.jbselfcompany.tyr.service.YggmailService.LocalBinder? = null

    private var networkCallback: NetworkChangeReceiver? = null

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize configuration repository
        configRepository = ConfigRepository(this)

        // Apply theme preference
        LocaleHelper.applyTheme(this)

        // Create notification channels
        createNotificationChannels()

        // Register network callback after 15-second delay (battery optimization)
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed({
            networkCallback = NetworkChangeReceiver(this)
            networkCallback?.register()
        }, 15000) // 15 seconds delay
    }

    override fun attachBaseContext(base: Context) {
        // Apply language preference before attaching base context
        super.attachBaseContext(LocaleHelper.applyLanguage(base))
    }

    override fun onTerminate() {
        // Unregister network callback
        networkCallback?.unregister()
        super.onTerminate()
    }

    /**
     * Create notification channels for Android O and above
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // Service notification channel (battery optimized with IMPORTANCE_MIN)
            val serviceChannel = NotificationChannel(
                CHANNEL_ID_SERVICE,
                getString(R.string.notification_channel_service),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = getString(R.string.notification_channel_service_desc)
                setShowBadge(false)
            }

            // Mail notification channel
            val mailChannel = NotificationChannel(
                CHANNEL_ID_MAIL,
                getString(R.string.notification_channel_mail),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.notification_channel_mail_desc)
                setShowBadge(true)
            }

            notificationManager.createNotificationChannel(serviceChannel)
            notificationManager.createNotificationChannel(mailChannel)
        }
    }
}
