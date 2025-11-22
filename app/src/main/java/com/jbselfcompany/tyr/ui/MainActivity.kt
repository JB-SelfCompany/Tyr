package com.jbselfcompany.tyr.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.jbselfcompany.tyr.R
import com.jbselfcompany.tyr.TyrApplication
import com.jbselfcompany.tyr.databinding.ActivityMainBinding
import com.jbselfcompany.tyr.service.ServiceStatus
import com.jbselfcompany.tyr.service.ServiceStatusListener
import com.jbselfcompany.tyr.service.YggmailService
import com.jbselfcompany.tyr.ui.onboarding.OnboardingActivity
import com.jbselfcompany.tyr.ui.settings.SettingsActivity
import com.jbselfcompany.tyr.utils.PermissionManager

/**
 * Main activity displaying service status and mail configuration.
 * Shows SMTP/IMAP connection information for DeltaChat.
 */
class MainActivity : AppCompatActivity(), ServiceStatusListener {

    private lateinit var binding: ActivityMainBinding
    private val configRepository by lazy { TyrApplication.instance.configRepository }

    private var yggmailService: YggmailService? = null
    private var serviceBound = false

    // Permission launcher for notification permission (Android 13+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // After notification permission, request battery optimization exclusion
            requestBatteryOptimizationIfNeeded()
        } else {
            // Still request battery optimization even if notification was denied
            requestBatteryOptimizationIfNeeded()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as YggmailService.LocalBinder
            yggmailService = binder.getService()
            serviceBound = true

            yggmailService?.addStatusListener(this@MainActivity)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            yggmailService?.removeStatusListener(this@MainActivity)
            yggmailService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if onboarding is needed
        if (!configRepository.isOnboardingCompleted()) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        setupUI()
        bindService()

        // Request permissions if needed (only on first launch)
        requestPermissionsIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        // Show permission warnings if any permissions are missing
        showPermissionWarnings()
    }

    override fun onDestroy() {
        super.onDestroy()
        unbindService()
    }

    private fun setupUI() {
        // Service control button
        binding.buttonToggleService.setOnClickListener {
            if (YggmailService.isRunning) {
                YggmailService.stop(this)
            } else {
                YggmailService.start(this)
            }
        }

        // Copy mail address button
        binding.buttonCopyAddress.setOnClickListener {
            val address = configRepository.getMailAddress()
            if (!address.isNullOrEmpty()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Mail Address", address)
                clipboard.setPrimaryClip(clip)
                com.google.android.material.snackbar.Snackbar.make(
                    binding.root,
                    R.string.address_copied,
                    com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
                ).show()
            }
        }

        // Setup instructions card
        binding.cardInstructions.setOnClickListener {
            showInstructionsDialog()
        }

        updateUI()
    }

    private fun bindService() {
        val intent = Intent(this, YggmailService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun unbindService() {
        if (serviceBound) {
            yggmailService?.removeStatusListener(this)
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    private fun updateUI() {
        val mailAddress = configRepository.getMailAddress()
        val isRunning = YggmailService.isRunning

        // Mail configuration
        if (!mailAddress.isNullOrEmpty()) {
            binding.textMailAddress.text = mailAddress
            binding.textMailAddress.visibility = View.VISIBLE
            binding.buttonCopyAddress.visibility = View.VISIBLE
        } else {
            binding.textMailAddress.visibility = View.GONE
            binding.buttonCopyAddress.visibility = View.GONE
        }

        // SMTP/IMAP info
        binding.textSmtpServer.text = getString(R.string.smtp_server, "127.0.0.1", "1025")
        binding.textImapServer.text = getString(R.string.imap_server, "127.0.0.1", "1143")

        // Service button
        if (isRunning) {
            binding.buttonToggleService.text = getString(R.string.stop_service)
            binding.buttonToggleService.setIconResource(R.drawable.ic_stop)
        } else {
            binding.buttonToggleService.text = getString(R.string.start_service)
            binding.buttonToggleService.setIconResource(R.drawable.ic_play_arrow)
        }
    }

    private fun showInstructionsDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.deltachat_setup)
            .setMessage(R.string.deltachat_instructions)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onStatusChanged(status: ServiceStatus, error: String?) {
        runOnUiThread {
            when (status) {
                ServiceStatus.STARTING -> {
                    binding.textStatus.text = getString(R.string.status_starting)
                    binding.textStatus.setTextColor(getColor(R.color.status_starting))
                    binding.progressBar.visibility = View.VISIBLE
                }
                ServiceStatus.RUNNING -> {
                    binding.textStatus.text = getString(R.string.status_running)
                    binding.textStatus.setTextColor(getColor(R.color.status_running))
                    binding.progressBar.visibility = View.GONE
                    // Update mail address when service starts
                    updateUI()
                }
                ServiceStatus.STOPPING -> {
                    binding.textStatus.text = getString(R.string.status_stopping)
                    binding.textStatus.setTextColor(getColor(R.color.status_stopping))
                    binding.progressBar.visibility = View.VISIBLE
                }
                ServiceStatus.STOPPED -> {
                    binding.textStatus.text = getString(R.string.status_stopped)
                    binding.textStatus.setTextColor(getColor(R.color.status_stopped))
                    binding.progressBar.visibility = View.GONE
                }
                ServiceStatus.ERROR -> {
                    binding.textStatus.text = getString(R.string.status_error, error ?: "")
                    binding.textStatus.setTextColor(getColor(R.color.status_error))
                    binding.progressBar.visibility = View.GONE
                }
            }
            updateUI()
        }
    }

    /**
     * Request permissions if needed (first launch only)
     */
    private fun requestPermissionsIfNeeded() {
        // Check if this is the first time requesting permissions
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val permissionsRequested = prefs.getBoolean("permissions_requested", false)

        if (!permissionsRequested) {
            // Mark as requested
            prefs.edit().putBoolean("permissions_requested", true).apply()

            // Request notification permission first (Android 13+)
            if (!PermissionManager.hasNotificationPermission(this)) {
                PermissionManager.requestNotificationPermission(this, notificationPermissionLauncher)
            } else {
                // If notification permission is already granted, request battery optimization
                requestBatteryOptimizationIfNeeded()
            }
        }
    }

    /**
     * Request battery optimization exclusion if needed
     */
    private fun requestBatteryOptimizationIfNeeded() {
        if (!PermissionManager.isBatteryOptimizationDisabled(this)) {
            PermissionManager.requestBatteryOptimizationExclusion(this)
        }
    }

    /**
     * Show Snackbar warnings for missing permissions (like in Mimir app)
     * Called in onResume() to check permissions every time activity becomes visible
     */
    private fun showPermissionWarnings() {
        // Check if notifications are allowed (Android 8.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!areNotificationsEnabled()) {
                val root = findViewById<View>(android.R.id.content)
                Snackbar.make(root, getString(R.string.allow_notifications_snack), Snackbar.LENGTH_INDEFINITE)
                    .setAction(getString(R.string.allow)) {
                        try {
                            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                            }
                            startActivity(intent)
                        } catch (e: ActivityNotFoundException) {
                            e.printStackTrace()
                        }
                    }
                    .setTextMaxLines(3)
                    .show()
            }
        }

        // Check if app is battery optimized
        if (!isBatteryOptimizationDisabled()) {
            val root = findViewById<View>(android.R.id.content)
            Snackbar.make(root, getString(R.string.add_to_power_exceptions), Snackbar.LENGTH_INDEFINITE)
                .setAction(getString(R.string.allow)) {
                    val action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    try {
                        val intent = Intent(action, Uri.parse("package:$packageName"))
                        startActivity(intent)
                    } catch (e: ActivityNotFoundException) {
                        e.printStackTrace()
                        // Fallback: open the generic battery-settings screen
                        try {
                            startActivity(Intent(action))
                        } catch (ex: ActivityNotFoundException) {
                            ex.printStackTrace()
                        }
                    }
                }
                .setTextMaxLines(3)
                .show()
        }
    }

    /**
     * Check if notifications are enabled for this app
     */
    private fun areNotificationsEnabled(): Boolean {
        return NotificationManagerCompat.from(this).areNotificationsEnabled()
    }

    /**
     * Check if battery optimization is disabled for this app
     */
    private fun isBatteryOptimizationDisabled(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } else {
            true // Battery optimization doesn't exist on Android 5 and below
        }
    }
}
