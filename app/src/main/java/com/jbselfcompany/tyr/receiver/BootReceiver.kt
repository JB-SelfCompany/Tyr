package com.jbselfcompany.tyr.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.jbselfcompany.tyr.TyrApplication
import com.jbselfcompany.tyr.service.YggmailService

/**
 * Broadcast receiver that starts Yggmail service on device boot.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Boot completed, checking auto-start configuration")

            val configRepository = TyrApplication.instance.configRepository

            // Check if onboarding is completed and auto-start is enabled
            if (configRepository.isOnboardingCompleted() &&
                configRepository.isAutoStartEnabled() &&
                configRepository.isServiceEnabled()) {

                Log.i(TAG, "Starting Yggmail service on boot")
                YggmailService.start(context)
            } else {
                Log.d(TAG, "Auto-start disabled or onboarding not completed")
            }
        }
    }
}
