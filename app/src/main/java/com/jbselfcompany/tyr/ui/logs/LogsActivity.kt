package com.jbselfcompany.tyr.ui.logs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import com.jbselfcompany.tyr.R
import com.jbselfcompany.tyr.databinding.ActivityLogsBinding
import com.jbselfcompany.tyr.ui.BaseActivity
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Activity for viewing and sharing application logs
 */
class LogsActivity : BaseActivity() {

    private lateinit var binding: ActivityLogsBinding
    private var logsText = ""

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

            // Keep last 1000 lines to avoid too much data
            if (logLines.size > 1000) {
                logLines.subList(0, logLines.size - 1000).clear()
            }

            logsText = logLines.joinToString("\n")
            binding.textLogs.text = if (logsText.isEmpty()) {
                getString(R.string.no_logs_available)
            } else {
                logsText
            }

        } catch (e: Exception) {
            binding.textLogs.text = getString(R.string.error_loading_logs, e.message)
            logsText = ""
        }
    }

    private fun setupListeners() {
        binding.fabShare.setOnClickListener {
            if (logsText.isNotEmpty()) {
                shareLogs()
            } else {
                Toast.makeText(this, R.string.no_logs_to_share, Toast.LENGTH_SHORT).show()
            }
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

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_logs, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_refresh -> {
                loadLogs()
                Toast.makeText(this, R.string.restart, Toast.LENGTH_SHORT).show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
