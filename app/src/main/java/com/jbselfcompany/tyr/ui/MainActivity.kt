package com.jbselfcompany.tyr.ui

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
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
import com.jbselfcompany.tyr.chat.ui.ChatFragment
import com.jbselfcompany.tyr.ui.settings.SettingsFragment
import com.jbselfcompany.tyr.utils.AutoconfigServer
import com.jbselfcompany.tyr.utils.NetworkStatsMonitor
import com.jbselfcompany.tyr.utils.UpdateChecker
import com.jbselfcompany.tyr.data.PeerInfo
import com.jbselfcompany.tyr.utils.PermissionManager
import com.jbselfcompany.tyr.chat.data.ChatContact
import com.jbselfcompany.tyr.chat.data.ChatRepository
import android.graphics.Bitmap
import android.graphics.Color
import android.widget.ImageView
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import java.io.File
import java.io.FileOutputStream
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.jbselfcompany.tyr.utils.SecurePreferences
import com.jbselfcompany.tyr.utils.TyrLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Main activity displaying service status and mail configuration.
 * Shows SMTP/IMAP connection information for DeltaChat.
 */
class MainActivity : BaseActivity(), ServiceStatusListener {

    companion object {
        const val EXTRA_TAB = "extra_tab"
        const val TAB_CHAT = "chat"
    }

    private lateinit var binding: ActivityMainBinding
    private val configRepository by lazy { TyrApplication.instance.configRepository }
    private val autoconfigServer by lazy { AutoconfigServer(this) }
    private val networkStatsMonitor by lazy { NetworkStatsMonitor(this) }

    private var yggmailService: YggmailService? = null
    private var serviceBound = false
    private var updateCheckDoneThisSession = false

    // Receives broadcast from YggmailService when new chat messages arrive
    private val newChatReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == YggmailService.ACTION_NEW_CHAT_MESSAGES) {
                updateChatBadge()
                // Refresh the contact list so new messages appear immediately
                // without waiting for ChatFragment's next 30-second poll.
                val chatFrag = supportFragmentManager.findFragmentById(R.id.content_chat)
                if (chatFrag is ChatFragment) {
                    chatFrag.refreshContactList()
                }
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as YggmailService.LocalBinder
            yggmailService = binder.getService()
            serviceBound = true

            // Store binder in TyrApplication for global access
            TyrApplication.instance.yggmailServiceBinder = binder

            yggmailService?.addStatusListener(this@MainActivity)

            // BIND_AUTO_CREATE creates the service via onCreate() without calling onStartCommand().
            // After a process kill + system restart, the service is bound but Yggmail is never
            // initialized. Kick it with an explicit start if it should be running but isn't.
            if (!YggmailService.isRunning && configRepository.isServiceEnabled()) {
                YggmailService.start(this@MainActivity)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            yggmailService?.removeStatusListener(this@MainActivity)
            yggmailService = null
            serviceBound = false
            TyrApplication.instance.yggmailServiceBinder = null
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

        // Handle tab selection from settings tab navigation
        if (intent.getStringExtra(EXTRA_TAB) == TAB_CHAT) {
            binding.bottomNavigation.selectedItemId = R.id.nav_chat
        }

        // Handle deeplink if launched via tyr://open?peer=...
        handleDeeplinkIntent(intent)

        // Don't request permissions automatically on first launch
        // They will be shown as Snackbars in onResume() instead
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Handle tab selection when resumed from settings tab
        if (intent.getStringExtra(EXTRA_TAB) == TAB_CHAT) {
            binding.bottomNavigation.selectedItemId = R.id.nav_chat
        }
        handleDeeplinkIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Show permission warnings if any permissions are missing
        showPermissionWarnings()
        // Notify service that app is active for optimized heartbeat
        yggmailService?.setAppActive(true)
        // Start network monitoring
        startNetworkMonitoring()
        // Update chat badge and listen for new messages
        updateChatBadge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                newChatReceiver,
                IntentFilter(YggmailService.ACTION_NEW_CHAT_MESSAGES),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(newChatReceiver, IntentFilter(YggmailService.ACTION_NEW_CHAT_MESSAGES))
        }
        // Check for updates (once per session, background thread)
        if (!updateCheckDoneThisSession) {
            updateCheckDoneThisSession = true
            checkForUpdatesInBackground()
        }
        // Warn user if Keystore recovery deleted their password
        checkKeystoreRecovery()
    }

    private fun checkKeystoreRecovery() {
        val recoveryPrefs = getSharedPreferences(SecurePreferences.RECOVERY_FLAG_PREFS, Context.MODE_PRIVATE)
        if (recoveryPrefs.getBoolean(SecurePreferences.RECOVERY_FLAG_KEY, false)) {
            recoveryPrefs.edit().remove(SecurePreferences.RECOVERY_FLAG_KEY).apply()
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.keystore_recovery_title)
                .setMessage(R.string.keystore_recovery_message)
                .setPositiveButton(R.string.ok, null)
                .show()
        }
    }

    override fun onPause() {
        super.onPause()
        // Do NOT call setAppActive(false) here — it switches QUIC keep-alive to 60s
        // which causes peer connections to time out while the app is in background.
        // Active state is managed by the Doze Mode receiver in YggmailService.
        // Stop network monitoring to save battery
        stopNetworkMonitoring()
        try { unregisterReceiver(newChatReceiver) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        unbindService()
        // Stop autoconfig server when activity is destroyed
        autoconfigServer.stop()
    }

    private fun setupUI() {
        // Service control button
        binding.buttonToggleService.setOnClickListener {
            if (YggmailService.isRunning) {
                // Perform soft stop instead of immediate stop
                // This gracefully disconnects peers before stopping to avoid ErrClosed errors
                yggmailService?.softStop()
            } else {
                YggmailService.start(this)
            }
        }

        // Setup DeltaChat button with DCACCOUNT link
        binding.buttonSetupDeltachat.setOnClickListener {
            setupDeltaChat()
        }

        // Copy mail address button (legacy support)
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

        // Show QR code button
        binding.buttonShowQr.setOnClickListener {
            val address = configRepository.getMailAddress()
            if (!address.isNullOrEmpty()) {
                showQrCodeDialog(address)
            }
        }

        // DeltaChat setup instructions card
        binding.cardDeltachatSetup.setOnClickListener {
            showDeltaChatInstructionsDialog()
        }

        // Email client setup instructions card
        binding.cardEmailClientSetup.setOnClickListener {
            showEmailClientInstructionsDialog()
        }

        setupBottomNavigation()
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

        // Mail configuration - only show when service is running
        if (!mailAddress.isNullOrEmpty() && isRunning) {
            binding.textMailAddress.text = mailAddress
            binding.textMailAddress.visibility = View.VISIBLE
            binding.buttonSetupDeltachat.visibility = View.VISIBLE
            binding.buttonCopyAddress.visibility = View.VISIBLE
            binding.buttonShowQr.visibility = View.VISIBLE
        } else {
            binding.textMailAddress.visibility = View.GONE
            binding.buttonSetupDeltachat.visibility = View.GONE
            binding.buttonCopyAddress.visibility = View.GONE
            binding.buttonShowQr.visibility = View.GONE
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

    private fun setupDeltaChat() {
        try {
            // Get credentials
            val email = configRepository.getMailAddress()
            val password = configRepository.getPassword()

            if (email.isNullOrEmpty() || password.isNullOrEmpty()) {
                Snackbar.make(
                    binding.root,
                    R.string.dcaccount_error,
                    Snackbar.LENGTH_LONG
                ).show()
                return
            }

            // Generate DCLOGIN URL (simpler, doesn't require HTTPS)
            // DCLOGIN embeds credentials directly in the URI
            val dcloginUrl = autoconfigServer.generateDcloginUrl(email, password)
            TyrLogger.d("MainActivity", "DCLOGIN URL generated")

            // Check if DeltaChat/ArcaneChat is installed (try multiple package names)
            val deltaChatPackages = mapOf(
                "com.b44t.messenger" to "DeltaChat",
                "chat.delta" to "DeltaChat",
                "chat.delta.lite" to "ArcaneChat",
                "com.github.arcanechat" to "ArcaneChat"
            )

            // Find all installed packages
            val installedApps = deltaChatPackages.filter { (packageName, _) ->
                try {
                    packageManager.getPackageInfo(packageName, 0)
                    true
                } catch (e: Exception) {
                    false
                }
            }

            TyrLogger.d("MainActivity", "Installed apps: ${installedApps.keys}")

            when {
                installedApps.isEmpty() -> {
                    // No apps installed - show message
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.deltachat_not_installed_title)
                        .setMessage(R.string.deltachat_not_installed_message)
                        .setPositiveButton(R.string.ok, null)
                        .show()
                }
                installedApps.size == 1 -> {
                    // Only one app installed - open it directly
                    val packageName = installedApps.keys.first()
                    openEmailClient(packageName, dcloginUrl)
                }
                else -> {
                    // Multiple apps installed - show selection dialog
                    showAppSelectionDialog(installedApps, dcloginUrl)
                }
            }
        } catch (e: Exception) {
            TyrLogger.e("MainActivity", "Error setting up DeltaChat", e)
            Snackbar.make(
                binding.root,
                R.string.dcaccount_error,
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    private fun showAppSelectionDialog(apps: Map<String, String>, dcloginUrl: String) {
        val appNames = apps.values.toTypedArray()
        val packageNames = apps.keys.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.select_email_client_title)
            .setItems(appNames) { _, which ->
                val selectedPackage = packageNames[which]
                openEmailClient(selectedPackage, dcloginUrl)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openEmailClient(packageName: String, dcloginUrl: String) {
        try {
            // First, try with package specified
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(dcloginUrl)
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)

            Snackbar.make(
                binding.root,
                R.string.dcaccount_opened,
                Snackbar.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            TyrLogger.w("MainActivity", "Failed to open with package $packageName, trying without", e)
            // Try without package specification
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse(dcloginUrl)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)

                Snackbar.make(
                    binding.root,
                    R.string.dcaccount_opened,
                    Snackbar.LENGTH_SHORT
                ).show()
            } catch (e2: Exception) {
                TyrLogger.e("MainActivity", "Failed to open DCLOGIN URL", e2)
                // Fallback: copy to clipboard
                copyDcloginToClipboard(dcloginUrl)
            }
        }
    }

    private fun copyDcloginToClipboard(dcloginUrl: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("DCLOGIN", dcloginUrl).apply {
            // Mark as sensitive so Android 13+ password managers/keyboards
            // do not preview or persist this clip
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                description.extras = android.os.PersistableBundle().apply {
                    putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
        }
        clipboard.setPrimaryClip(clip)

        Snackbar.make(
            binding.root,
            R.string.dcaccount_copied,
            Snackbar.LENGTH_LONG
        ).show()
    }

    private fun showQrCodeDialog(mailAddress: String) {
        try {
            val mailtoUrl = "mailto:$mailAddress"
            val qrBitmap = generateQrCode(mailtoUrl, 512, 512)

            val dialogView = layoutInflater.inflate(R.layout.dialog_qr_code, null)
            dialogView.findViewById<ImageView>(R.id.imageViewQr).setImageBitmap(qrBitmap)
            dialogView.findViewById<MaterialButton>(R.id.buttonShareQrImage)
                .setOnClickListener { shareQrImage(qrBitmap) }
            dialogView.findViewById<MaterialButton>(R.id.buttonShareMailto)
                .setOnClickListener { shareMailto(mailtoUrl) }
            dialogView.findViewById<MaterialButton>(R.id.buttonShareTyrLink)
                .setOnClickListener { shareTyrLink(mailAddress) }

            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.qr_code_title)
                .setView(dialogView)
                .setPositiveButton(R.string.close, null)
                .show()
        } catch (e: Exception) {
            TyrLogger.e("MainActivity", "Error generating QR code", e)
            Snackbar.make(binding.root, R.string.qr_code_error, Snackbar.LENGTH_SHORT).show()
        }
    }

    /**
     * Share a tyr://open deeplink containing the user's pubkey and an optional peer suggestion.
     * Recipients who have Tyr installed can tap the link to add the sender as a contact in one step.
     */
    private fun shareTyrLink(mailAddress: String) {
        try {
            val pubkey = mailAddress.substringBefore("@")
            val firstEnabledPeer = configRepository.getAllPeersInfo().firstOrNull { it.isEnabled }?.uri
            val tyrUrl = buildString {
                append("tyr://open?pubkey=")
                append(pubkey)
                if (firstEnabledPeer != null) {
                    append("&peer=")
                    append(Uri.encode(firstEnabledPeer))
                }
            }
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, tyrUrl)
                type = "text/plain"
            }
            startActivity(Intent.createChooser(sendIntent, null))
        } catch (e: Exception) {
            TyrLogger.e("MainActivity", "Error sharing Tyr link", e)
            Snackbar.make(binding.root, R.string.qr_code_error, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun shareMailto(mailtoUrl: String) {
        try {
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, mailtoUrl)
                type = "text/plain"
            }
            val shareIntent = Intent.createChooser(sendIntent, null)
            startActivity(shareIntent)
        } catch (e: Exception) {
            TyrLogger.e("MainActivity", "Error sharing mailto URL", e)
            Snackbar.make(
                binding.root,
                R.string.qr_code_error,
                Snackbar.LENGTH_SHORT
            ).show()
        }
    }

    private fun shareQrImage(qrBitmap: Bitmap) {
        try {
            val file = File(cacheDir, "qr_share.png")
            FileOutputStream(file).use { qrBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(sendIntent, null))
        } catch (e: Exception) {
            TyrLogger.e("MainActivity", "Error sharing QR image", e)
            Snackbar.make(binding.root, R.string.qr_code_error, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun generateQrCode(content: String, width: Int, height: Int): Bitmap {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)

        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }

        return bitmap
    }

    private fun showDeltaChatInstructionsDialog() {
        val mailAddress = configRepository.getMailAddress() ?: getString(R.string.your_email_address)

        val instructions = getString(R.string.deltachat_instructions, mailAddress)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.deltachat_setup)
            .setMessage(instructions)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun showEmailClientInstructionsDialog() {
        val mailAddress = configRepository.getMailAddress() ?: getString(R.string.your_email_address)
        val password = getString(R.string.your_password)

        val instructions = getString(R.string.email_client_instructions, mailAddress, password)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.email_client_setup_title)
            .setMessage(instructions)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private var settingsFragmentLoaded = false
    private var chatFragmentLoaded = false

    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    binding.contentHome.visibility = View.VISIBLE
                    binding.contentChat.visibility = View.GONE
                    binding.contentSettings.visibility = View.GONE
                    true
                }
                R.id.nav_chat -> {
                    showChatTab()
                    true
                }
                R.id.nav_settings -> {
                    showSettingsTab()
                    true
                }
                else -> false
            }
        }
    }

    private fun showChatTab() {
        binding.contentHome.visibility = View.GONE
        binding.contentChat.visibility = View.VISIBLE
        binding.contentSettings.visibility = View.GONE
        if (!chatFragmentLoaded) {
            chatFragmentLoaded = true
            // Chat is under maintenance — show a placeholder instead of ChatFragment.
            supportFragmentManager.beginTransaction()
                .replace(R.id.content_chat, MaintenanceFragment())
                .commitNow()
        }
    }

    private fun showSettingsTab() {
        binding.contentHome.visibility = View.GONE
        binding.contentChat.visibility = View.GONE
        binding.contentSettings.visibility = View.VISIBLE
        if (!settingsFragmentLoaded) {
            settingsFragmentLoaded = true
            supportFragmentManager.beginTransaction()
                .replace(R.id.content_settings, SettingsFragment())
                .commitNow()
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

    // Removed automatic permission requests on first launch
    // Permissions are now only shown as Snackbar warnings in onResume()

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

    /**
     * Start network statistics monitoring
     * Only runs when app is in foreground to save battery
     * Latency information comes directly from Yggdrasil transport layer
     */
    private fun startNetworkMonitoring() {
        TyrLogger.d("MainActivity", "Starting network monitoring")
        networkStatsMonitor.start(object : NetworkStatsMonitor.NetworkStatsListener {
            override fun onStatsUpdated(stats: NetworkStatsMonitor.NetworkStats) {
                updateNetworkStatsUI(stats)
            }
        }, enableLatencyMeasurement = true) // Parameter kept for API compatibility
    }

    /**
     * Stop network statistics monitoring
     */
    private fun stopNetworkMonitoring() {
        networkStatsMonitor.stop()
    }

    /**
     * Update UI with network statistics
     */
    private fun updateNetworkStatsUI(stats: NetworkStatsMonitor.NetworkStats) {
        // Connection type
        binding.textConnectionType.text = stats.connectionType

        // Update peers list
        updatePeersList(stats.peers)
    }

    /**
     * Update the list of peers with latency information
     */
    private fun updatePeersList(peers: List<NetworkStatsMonitor.PeerInfo>) {
        // Clear existing views
        binding.peersContainer.removeAllViews()

        if (peers.isEmpty()) {
            // Show "no peers" message
            val noPeersText = android.widget.TextView(this).apply {
                text = getString(R.string.no_active_peer)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                // Use Material3 color that adapts to light/dark theme
                val typedValue = android.util.TypedValue()
                context.theme.resolveAttribute(
                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                    typedValue,
                    true
                )
                setTextColor(typedValue.data)
            }
            binding.peersContainer.addView(noPeersText)
        } else {
            // Add peer info views
            for (peer in peers) {
                val peerView = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = 8.dpToPx()
                        bottomMargin = 8.dpToPx()
                    }
                    setPadding(12.dpToPx(), 12.dpToPx(), 12.dpToPx(), 12.dpToPx())
                    setBackgroundResource(R.drawable.peer_item_background)
                }

                // First row: Peer address and status
                val firstRow = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }

                // Peer host:port
                val peerNameText = android.widget.TextView(this).apply {
                    text = "${peer.host}:${peer.port}"
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        0,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }

                // Connection status
                val statusText = android.widget.TextView(this).apply {
                    text = if (peer.connected) getString(R.string.peer_connected) else getString(R.string.peer_disconnected)
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)

                    // Color code status
                    setTextColor(
                        if (peer.connected)
                            getColor(R.color.status_running)
                        else
                            getColor(R.color.status_error)
                    )
                }

                firstRow.addView(peerNameText)
                firstRow.addView(statusText)

                // Second row: Latency
                val latencyText = android.widget.TextView(this).apply {
                    text = if (peer.latencyMs >= 0) {
                        getString(R.string.peer_latency_format, peer.latencyMs)
                    } else {
                        getString(R.string.peer_latency_unknown)
                    }
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = 4.dpToPx()
                    }
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                    // Use Material3 color that adapts to light/dark theme
                    val typedValue = android.util.TypedValue()
                    context.theme.resolveAttribute(
                        com.google.android.material.R.attr.colorOnSurfaceVariant,
                        typedValue,
                        true
                    )
                    setTextColor(typedValue.data)
                }

                peerView.addView(firstRow)
                peerView.addView(latencyText)

                binding.peersContainer.addView(peerView)
            }
        }
    }

    /**
     * Handle tyr://open deeplink. Supports parameters:
     *   peer=<url>    — Yggdrasil peer URL to add
     *   pubkey=<hex>  — 64-char Ed25519 public key to add as chat contact
     *   name=<str>    — optional display name for the contact
     *
     * Any combination is valid: peer-only, pubkey-only, or both.
     * Shows a confirmation dialog before making any changes.
     */
    private fun handleDeeplinkIntent(intent: Intent?) {
        if (!configRepository.isOnboardingCompleted()) return
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        if (uri.scheme != "tyr" || uri.host != "open") return

        // Clear intent data immediately to prevent replay on configuration change (e.g. rotation)
        setIntent(intent.apply { data = null })

        val peerUrl = uri.getQueryParameter("peer")?.trim()
        val pubkey = uri.getQueryParameter("pubkey")?.trim()
        val contactName = uri.getQueryParameter("name")?.trim()

        val hasValidPeer = !peerUrl.isNullOrBlank() && PeerInfo.isValidPeerUrl(peerUrl)
        val hasValidPubkey = !pubkey.isNullOrBlank() && isValidPubkey(pubkey)

        if (!hasValidPeer && !hasValidPubkey) {
            if (!peerUrl.isNullOrBlank()) {
                Snackbar.make(binding.root, R.string.deeplink_invalid_peer, Snackbar.LENGTH_SHORT).show()
            }
            return
        }

        val peerAlreadyExists = hasValidPeer && configRepository.getAllPeersInfo().any { it.uri == peerUrl }
        val contactAddress = if (hasValidPubkey) "$pubkey@yggmail" else null

        // Prevent adding own address as a contact (same guard as the manual AddContact flow).
        // Without this, opening your own tyr://open link adds yourself as a contact, and
        // deleting it wipes all messages (deleteContact deletes by address which matches every row).
        val isSelfContact = contactAddress?.equals(configRepository.getMailAddress(), ignoreCase = true) == true
        val effectiveHasValidPubkey = hasValidPubkey && !isSelfContact

        val contactAlreadyExists = contactAddress != null && !isSelfContact &&
            ChatRepository(this).contactExists(contactAddress)

        // Nothing new to add
        if ((!hasValidPeer || peerAlreadyExists) && (!effectiveHasValidPubkey || contactAlreadyExists)) {
            Snackbar.make(binding.root, R.string.deeplink_already_configured, Snackbar.LENGTH_SHORT).show()
            return
        }

        val title = if (effectiveHasValidPubkey) R.string.deeplink_add_peer_and_contact_title else R.string.deeplink_add_peer_title
        val message = buildString {
            if (hasValidPeer && !peerAlreadyExists) {
                append(getString(R.string.deeplink_peer_label))
                append("\n")
                append(peerUrl)
            }
            if (effectiveHasValidPubkey && !contactAlreadyExists) {
                if (isNotEmpty()) append("\n\n")
                append(getString(R.string.deeplink_contact_label))
                append("\n")
                if (!contactName.isNullOrBlank()) append("$contactName\n")
                append(contactAddress)
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.add) { _, _ ->
                if (hasValidPeer && !peerAlreadyExists) {
                    configRepository.savePeer(
                        PeerInfo(uri = peerUrl!!, isEnabled = true, tag = PeerInfo.PeerTag.CUSTOM)
                    )
                }
                if (effectiveHasValidPubkey && !contactAlreadyExists) {
                    val name = contactName?.takeIf { it.isNotBlank() } ?: pubkey!!.take(8)
                    ChatRepository(this).addContact(ChatContact(address = contactAddress!!, name = name))
                }
                Snackbar.make(binding.root, R.string.deeplink_added_success, Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Returns true if [key] is a valid 64-char lowercase hex Ed25519 public key. */
    private fun isValidPubkey(key: String): Boolean =
        key.length == 64 && key.all { it in '0'..'9' || it in 'a'..'f' }

    /**
     * Convert dp to pixels
     */
    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }

    /**
     * Check for updates in background — only if conditions are met.
     * Shows a dialog if a newer version is found and user hasn't dismissed it.
     */
    private fun checkForUpdatesInBackground() {
        if (!configRepository.shouldCheckForUpdates()) return

        lifecycleScope.launch(Dispatchers.IO) {
            val info = UpdateChecker(this@MainActivity).checkForUpdates()
            configRepository.setLastUpdateCheckTime(System.currentTimeMillis())

            if (info == null || !info.hasUpdate) return@launch
            if (info.latestVersion == configRepository.getDismissedUpdateVersion()) return@launch

            withContext(Dispatchers.Main) {
                if (!isFinishing && !isDestroyed) showUpdateDialog(info)
            }
        }
    }

    private fun showUpdateDialog(info: UpdateChecker.UpdateInfo) {
        val message = if (info.isFdroidInstall) {
            getString(R.string.update_available_fdroid, info.latestVersion, info.currentVersion)
        } else {
            getString(R.string.update_available_github, info.latestVersion, info.currentVersion)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_available_title)
            .setMessage(message)
            .setPositiveButton(R.string.update_download) { _, _ ->
                startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        Uri.parse(info.releaseUrl)
                    )
                )
            }
            .setNeutralButton(R.string.update_skip_version) { _, _ ->
                configRepository.setDismissedUpdateVersion(info.latestVersion)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Called by ChatFragment after opening/closing conversations to keep badge in sync. */
    fun refreshChatBadge() = updateChatBadge()

    /**
     * Update the unread-chat-count badge on the chat nav item.
     * Counts distinct conversations (not messages) that have unread incoming messages.
     */
    private fun updateChatBadge() {
        // Chat is under maintenance — never show an unread badge on the Chat tab.
        binding.bottomNavigation.removeBadge(R.id.nav_chat)
    }
}
