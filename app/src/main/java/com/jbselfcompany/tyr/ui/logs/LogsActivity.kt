package com.jbselfcompany.tyr.ui.logs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.jbselfcompany.tyr.BuildConfig
import com.jbselfcompany.tyr.R
import com.jbselfcompany.tyr.TyrApplication
import com.jbselfcompany.tyr.databinding.ActivityLogsBinding
import com.jbselfcompany.tyr.service.YggmailService
import com.jbselfcompany.tyr.ui.BaseActivity
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

/**
 * Activity for viewing and sharing application logs
 */
class LogsActivity : BaseActivity() {

    private lateinit var binding: ActivityLogsBinding
    private var logsText = ""
    private val configRepository by lazy { TyrApplication.instance.configRepository }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityLogsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        loadLogs()
        setupListeners()
    }

    private fun loadLogs() {
        if (!configRepository.isLogCollectionEnabled()) {
            binding.textLogs.text = getString(R.string.log_collection_disabled)
            logsText = ""
            return
        }

        binding.textLogs.text = getString(R.string.loading_logs)

        thread {
            try {
                // Read logcat output filtered by Tyr and Yggmail
                val process = Runtime.getRuntime().exec("logcat -d -v time")
                val bufferedReader = BufferedReader(InputStreamReader(process.inputStream))

                val logLines = mutableListOf<String>()
                var line: String?

                while (bufferedReader.readLine().also { line = it } != null) {
                    // Filter for Tyr and Yggmail related logs
                    if (line?.contains("Tyr") == true ||
                        line?.contains("Yggmail") == true ||
                        line?.contains("com.jbselfcompany.tyr") == true) {
                        logLines.add(line!!)
                    }
                }

                bufferedReader.close()
                process.destroy()

                // Keep last 1000 lines to avoid too much data
                if (logLines.size > 1000) {
                    logLines.subList(0, logLines.size - 1000).clear()
                }

                val text = logLines.joinToString("\n")

                runOnUiThread {
                    logsText = text
                    binding.textLogs.text = if (text.isEmpty()) {
                        getString(R.string.no_logs_available)
                    } else {
                        text
                    }
                }

            } catch (e: Exception) {
                runOnUiThread {
                    binding.textLogs.text = getString(R.string.error_loading_logs, e.message)
                    logsText = ""
                }
            }
        }
    }

    private fun setupListeners() {
        binding.fabShare.setOnClickListener {
            if (!configRepository.isLogCollectionEnabled()) {
                Toast.makeText(this, R.string.no_logs_to_share, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showExportDialog()
        }

        binding.fabShare.setOnLongClickListener {
            if (logsText.isNotEmpty()) {
                copyToClipboard()
                true
            } else {
                Toast.makeText(this, R.string.no_logs_to_copy, Toast.LENGTH_SHORT).show()
                true
            }
        }
    }

    private fun shareLogs() {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.tyr_logs))
            putExtra(Intent.EXTRA_TEXT, logsText)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_logs)))
    }

    private fun copyToClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(getString(R.string.tyr_logs), logsText)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, R.string.logs_copied, Toast.LENGTH_SHORT).show()
    }

    private fun showExportDialog() {
        val intervals = longArrayOf(10, 30, 60, 24 * 60)
        val intervalNames = arrayOf(
            getString(R.string.interval_10_minutes),
            getString(R.string.interval_30_minutes),
            getString(R.string.interval_60_minutes),
            getString(R.string.interval_24_hours)
        )

        AlertDialog.Builder(this)
            .setTitle(R.string.select_log_interval)
            .setItems(intervalNames) { _, which ->
                exportLogsAsZip(intervals[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun exportLogsAsZip(minutes: Long) {
        // Show loading toast
        Toast.makeText(this, R.string.exporting_logs, Toast.LENGTH_SHORT).show()

        thread {
            try {
                // Collect logs for the specified time period
                val logs = collectLogcat(minutes)

                if (logs.isEmpty()) {
                    runOnUiThread {
                        Toast.makeText(this, R.string.no_logs_to_share, Toast.LENGTH_SHORT).show()
                    }
                    return@thread
                }

                // Create ZIP file
                val timestamp = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(Date())
                val zipFileName = "tyr-logs-$timestamp.zip"
                val zipFile = File(cacheDir, zipFileName)

                ZipOutputStream(FileOutputStream(zipFile)).use { zipOut ->
                    val entry = ZipEntry("tyr-logcat.txt")
                    zipOut.putNextEntry(entry)
                    zipOut.write(logs.joinToString("\n").toByteArray())
                    zipOut.closeEntry()
                }

                // Share the ZIP file
                runOnUiThread {
                    Toast.makeText(this, R.string.logs_exported, Toast.LENGTH_SHORT).show()
                    shareZipFile(zipFile)
                }

            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.error_exporting_logs, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun collectLogcat(minutes: Long): List<String> {
        val process = Runtime.getRuntime().exec("logcat -d -v time")
        val bufferedReader = BufferedReader(InputStreamReader(process.inputStream))

        val now = System.currentTimeMillis()
        val minutesAgo = now - minutes * 60 * 1000

        val logLines = mutableListOf<String>()
        val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

        // Get current year for proper date parsing
        val currentCalendar = Calendar.getInstance()
        val currentYear = currentCalendar.get(Calendar.YEAR)

        var line: String?
        while (bufferedReader.readLine().also { line = it } != null) {
            val currentLine = line ?: continue

            // Filter for Tyr and Yggmail related logs
            if (!currentLine.contains("Tyr") &&
                !currentLine.contains("Yggmail") &&
                !currentLine.contains("com.jbselfcompany.tyr")) {
                continue
            }

            // Try to parse timestamp from the log line
            if (currentLine.length < 18) {
                logLines.add(currentLine)
                continue
            }

            try {
                val timestampStr = currentLine.substring(0, 18)
                val parsedDate = dateFormat.parse(timestampStr)

                if (parsedDate != null) {
                    // Set the year to current year since logcat doesn't include it
                    val logCalendar = Calendar.getInstance()
                    logCalendar.time = parsedDate
                    logCalendar.set(Calendar.YEAR, currentYear)

                    val timestamp = logCalendar.timeInMillis

                    if (timestamp >= minutesAgo) {
                        logLines.add(currentLine)
                    }
                }
            } catch (e: Exception) {
                // If parsing fails, include the line anyway
                logLines.add(currentLine)
            }
        }

        bufferedReader.close()
        return logLines
    }

    private fun shareZipFile(zipFile: File) {
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                zipFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                clipData = ClipData.newRawUri("", uri)
            }

            startActivity(Intent.createChooser(shareIntent, getString(R.string.share_logs)))
        } catch (e: Exception) {
            Toast.makeText(
                this,
                getString(R.string.error_exporting_logs, e.message),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    @Suppress("UNUSED")
    private fun restartServiceAndRefreshLogs() {
        if (!YggmailService.isRunning) {
            // Service not running, just reload logs
            loadLogs()
            Toast.makeText(this, R.string.logs_refreshed, Toast.LENGTH_SHORT).show()
            return
        }

        // Show loading overlay with correct text
        showLoadingOverlay(true, getString(R.string.restarting_service_logs))

        // Stop service
        YggmailService.stop(this)

        // Wait for service to stop, then restart (6 seconds delay)
        Handler(Looper.getMainLooper()).postDelayed({
            if (!YggmailService.isRunning) {
                // Service stopped successfully, now start it
                YggmailService.start(this)

                // Wait for service to start
                Handler(Looper.getMainLooper()).postDelayed({
                    checkServiceRestarted()
                }, 2000)
            } else {
                // Service still running, retry
                Handler(Looper.getMainLooper()).postDelayed({
                    restartServiceAndRefreshLogs()
                }, 1000)
            }
        }, 6000)
    }

    private fun checkServiceRestarted() {
        if (YggmailService.isRunning) {
            // Service restarted successfully
            showLoadingOverlay(false)

            // Reload logs
            loadLogs()
            Toast.makeText(this, R.string.service_restarted, Toast.LENGTH_SHORT).show()
        } else {
            // Wait a bit more
            Handler(Looper.getMainLooper()).postDelayed({
                checkServiceRestarted()
            }, 1000)
        }
    }

    private fun showLoadingOverlay(show: Boolean, text: String? = null) {
        binding.loadingOverlay.visibility = if (show) View.VISIBLE else View.GONE

        if (show && text != null) {
            binding.loadingText.text = text
        }

        // Disable interaction while loading
        binding.fabShare.isEnabled = !show
    }
}
