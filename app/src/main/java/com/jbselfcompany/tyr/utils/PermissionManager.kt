package com.jbselfcompany.tyr.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.jbselfcompany.tyr.R

/**
 * Helper class for managing app permissions
 */
object PermissionManager {

    /**
     * Check if notification permission is granted (Android 13+)
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // On Android 12 and below, notification permission is granted by default
            true
        }
    }

    /**
     * Check if battery optimization is disabled for the app
     */
    fun isBatteryOptimizationDisabled(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            // On Android 5 and below, battery optimization doesn't exist
            true
        }
    }

    /**
     * Request notification permission (Android 13+)
     */
    fun requestNotificationPermission(
        activity: Activity,
        launcher: ActivityResultLauncher<String>
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasNotificationPermission(activity)) {
                // Show explanation dialog
                AlertDialog.Builder(activity)
                    .setTitle(R.string.permission_notification_title)
                    .setMessage(R.string.permission_notification_message)
                    .setPositiveButton(R.string.permission_allow) { _, _ ->
                        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    .setNegativeButton(R.string.permission_deny, null)
                    .show()
            }
        }
    }

    /**
     * Request battery optimization exclusion
     */
    fun requestBatteryOptimizationExclusion(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!isBatteryOptimizationDisabled(activity)) {
                // Show explanation dialog
                AlertDialog.Builder(activity)
                    .setTitle(R.string.permission_battery_title)
                    .setMessage(R.string.permission_battery_message)
                    .setPositiveButton(R.string.permission_settings) { _, _ ->
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${activity.packageName}")
                        }
                        try {
                            activity.startActivity(intent)
                        } catch (e: Exception) {
                            // Fallback to general battery optimization settings
                            val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            activity.startActivity(fallbackIntent)
                        }
                    }
                    .setNegativeButton(R.string.permission_deny, null)
                    .show()
            }
        }
    }

    /**
     * Check if all required permissions are granted
     */
    fun hasAllRequiredPermissions(context: Context): Boolean {
        return hasNotificationPermission(context) && isBatteryOptimizationDisabled(context)
    }
}
