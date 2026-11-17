package com.jbselfcompany.tyr.utils

import android.content.Context
import com.jbselfcompany.tyr.utils.TyrLogger
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

class UpdateChecker(private val context: Context) {

    companion object {
        private const val TAG = "UpdateChecker"
        private const val GITHUB_API_URL =
            "https://api.github.com/repos/JB-SelfCompany/Tyr/releases/latest"
        private val FDROID_INSTALLERS = setOf(
            "org.fdroid.fdroid",
            "org.fdroid.fdroid.privileged"
        )
    }

    data class UpdateInfo(
        val latestVersion: String,
        val currentVersion: String,
        val hasUpdate: Boolean,
        val releaseUrl: String,
        val isFdroidInstall: Boolean
    )

    fun checkForUpdates(): UpdateInfo? {
        return try {
            val url = URL(GITHUB_API_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                TyrLogger.w(TAG, "GitHub API returned ${connection.responseCode}")
                return null
            }

            val response = connection.inputStream.bufferedReader().readText()
            connection.disconnect()

            val json = JSONObject(response)
            val tagName = json.getString("tag_name")
            val htmlUrl = json.getString("html_url")

            val latestVersion = tagName.trimStart('v', 'V')
            val currentVersion = context.packageManager
                .getPackageInfo(context.packageName, 0).versionName ?: ""

            UpdateInfo(
                latestVersion = latestVersion,
                currentVersion = currentVersion,
                hasUpdate = isNewerVersion(latestVersion, currentVersion),
                releaseUrl = htmlUrl,
                isFdroidInstall = isFdroidInstall()
            )
        } catch (e: Exception) {
            TyrLogger.e(TAG, "Error checking for updates", e)
            null
        }
    }

    private fun isNewerVersion(latest: String, current: String): Boolean {
        return try {
            val l = latest.split(".").map { it.trim().toInt() }
            val c = current.split(".").map { it.trim().toInt() }
            val maxLen = maxOf(l.size, c.size)
            for (i in 0 until maxLen) {
                val lv = l.getOrElse(i) { 0 }
                val cv = c.getOrElse(i) { 0 }
                if (lv > cv) return true
                if (lv < cv) return false
            }
            false
        } catch (e: Exception) {
            TyrLogger.e(TAG, "Error comparing versions '$latest' vs '$current'", e)
            false
        }
    }

    fun isFdroidInstall(): Boolean = try {
        val installer = if (android.os.Build.VERSION.SDK_INT >=
            android.os.Build.VERSION_CODES.R
        ) {
            context.packageManager
                .getInstallSourceInfo(context.packageName)
                .installingPackageName
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(context.packageName)
        }
        installer != null && installer in FDROID_INSTALLERS
    } catch (e: Exception) {
        TyrLogger.e(TAG, "Error detecting installer", e)
        false
    }
}
