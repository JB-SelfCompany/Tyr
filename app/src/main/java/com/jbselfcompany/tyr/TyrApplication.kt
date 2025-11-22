package com.jbselfcompany.tyr

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.jbselfcompany.tyr.data.ConfigRepository

/**
 * Application class for Tyr.
 * Initializes global application state and notification channels.
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

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize configuration repository
        configRepository = ConfigRepository(this)

        // Create notification channels
        createNotificationChannels()
    }

    /**
     * Create notification channels for Android O and above
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // Service notification channel
            val serviceChannel = NotificationChannel(
                CHANNEL_ID_SERVICE,
                getString(R.string.notification_channel_service),
                NotificationManager.IMPORTANCE_LOW
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
