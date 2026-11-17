package com.jbselfcompany.tyr.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.jbselfcompany.tyr.service.YggmailService
import com.jbselfcompany.tyr.utils.TyrLogger

/**
 * Broadcast receiver for periodic maintenance tasks.
 * Works with AlarmManager for Doze Mode compatibility.
 *
 * Battery optimization: Instead of continuous WakeLock renewal,
 * we use AlarmManager to wake up periodically (every 15 minutes)
 * for lightweight maintenance tasks.
 */
class MaintenanceReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "MaintenanceReceiver"
        private const val ACTION_MAINTENANCE = "com.jbselfcompany.tyr.ACTION_MAINTENANCE"
        private const val MAINTENANCE_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes

        /**
         * Schedule periodic maintenance using AlarmManager.
         * Compatible with Doze Mode via setExactAndAllowWhileIdle().
         */
        fun scheduleMaintenance(context: Context) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val intent = Intent(context, MaintenanceReceiver::class.java).apply {
                    action = ACTION_MAINTENANCE
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                // Calculate next trigger time
                val triggerTime = System.currentTimeMillis() + MAINTENANCE_INTERVAL_MS

                // Check if we can schedule exact alarms (Android 12+)
                val canScheduleExactAlarms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    alarmManager.canScheduleExactAlarms()
                } else {
                    true
                }

                if (!canScheduleExactAlarms) {
                    TyrLogger.w(
                        TAG,
                        "Cannot schedule exact alarms - permission not granted. Using inexact alarm."
                    )
                    // Fallback to inexact alarm (will work but less precise)
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                    TyrLogger.d(TAG, "Scheduled maintenance in ~15 minutes (inexact)")
                    return
                }

                // Use setExactAndAllowWhileIdle for Doze Mode compatibility
                // This guarantees execution even in Doze Mode (up to 9 times per 15 min window)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                    TyrLogger.d(TAG, "Scheduled maintenance in 15 minutes (Doze-compatible)")
                } else {
                    alarmManager.setExact(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                    TyrLogger.d(TAG, "Scheduled maintenance in 15 minutes")
                }
            } catch (e: SecurityException) {
                // Android 12+ may throw SecurityException if SCHEDULE_EXACT_ALARM not granted
                TyrLogger.e(
                    TAG,
                    "SecurityException scheduling maintenance - exact alarm permission not granted",
                    e
                )
                // Service will continue to work, just without precise maintenance scheduling
            } catch (e: Exception) {
                TyrLogger.e(TAG, "Error scheduling maintenance", e)
            }
        }

        /**
         * Cancel scheduled maintenance.
         */
        fun cancelMaintenance(context: Context) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val intent = Intent(context, MaintenanceReceiver::class.java).apply {
                    action = ACTION_MAINTENANCE
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.cancel(pendingIntent)
                TyrLogger.d(TAG, "Maintenance scheduling cancelled")
            } catch (e: Exception) {
                TyrLogger.e(TAG, "Error cancelling maintenance", e)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_MAINTENANCE) {
            return
        }

        TyrLogger.d(TAG, "Maintenance task triggered")

        if (!YggmailService.isRunning) {
            TyrLogger.d(TAG, "Service not running, skipping maintenance")
            scheduleMaintenance(context)
            return
        }

        // Delegate peer check to the running service via intent.
        // This avoids the bindService async + WakeLock timing mismatch:
        // bindService callbacks fire after onReceive returns, so any WakeLock
        // acquired here would be released before the callback executes.
        // YggmailService runs as a foreground service and handles the work
        // on its own HandlerThread with proper lifecycle management.
        try {
            val serviceIntent = Intent(context, YggmailService::class.java).apply {
                action = YggmailService.ACTION_MAINTENANCE_CHECK
            }
            context.startService(serviceIntent)
            TyrLogger.d(TAG, "Maintenance check delegated to service")
        } catch (e: Exception) {
            TyrLogger.e(TAG, "Error sending maintenance intent to service", e)
        }

        // Reschedule next maintenance
        scheduleMaintenance(context)
    }
}
