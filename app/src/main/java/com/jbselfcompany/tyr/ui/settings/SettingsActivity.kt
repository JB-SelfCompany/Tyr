package com.jbselfcompany.tyr.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.jbselfcompany.tyr.R
import com.jbselfcompany.tyr.TyrApplication
import com.jbselfcompany.tyr.databinding.ActivitySettingsBinding
import com.jbselfcompany.tyr.service.YggmailService
import com.jbselfcompany.tyr.ui.SettingsAdapter
import com.jbselfcompany.tyr.ui.AboutActivity
import com.jbselfcompany.tyr.ui.BaseActivity
import com.jbselfcompany.tyr.ui.logs.LogsActivity
import com.jbselfcompany.tyr.utils.BackupManager
import com.jbselfcompany.tyr.data.ConfigRepository

/**
 * Settings activity for managing Yggdrasil peers and service configuration
 * Redesigned with RecyclerView adapter pattern inspired by Mimir
 */
class SettingsActivity : BaseActivity(), SettingsAdapter.Listener {

    private lateinit var binding: ActivitySettingsBinding
    private val configRepository by lazy { TyrApplication.instance.configRepository }
    private lateinit var adapter: SettingsAdapter
    private val settingsItems = mutableListOf<SettingsAdapter.Item>()

    companion object {
        // Setting IDs
        private const val ID_HEADER_SERVICE = 1
        private const val ID_AUTO_START = 2
        private const val ID_MULTICAST = 3
        private const val ID_HEADER_NETWORK = 4
        private const val ID_CONFIGURE_PEERS = 5
        private const val ID_HEADER_SECURITY = 6
        private const val ID_CHANGE_PASSWORD = 7
        private const val ID_REGENERATE_KEYS = 8
        private const val ID_BACKUP_RESTORE = 9
        private const val ID_HEADER_APPEARANCE = 10
        private const val ID_LANGUAGE = 11
        private const val ID_THEME = 12
        private const val ID_HEADER_DEBUG = 13
        private const val ID_ENABLE_LOG_COLLECTION = 14
        private const val ID_COLLECT_LOGS = 15
        private const val ID_CLEAR_LOGS = 16
        private const val ID_HEADER_ABOUT = 17
        private const val ID_ABOUT_APP = 18
    }

    private val createBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        uri?.let { performBackup(it) }
    }

    private val restoreBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { showRestoreBackupDialog(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setupRecyclerView()
        createSettingsItems()
    }

    private fun setupRecyclerView() {
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.addItemDecoration(
            DividerItemDecoration(this, DividerItemDecoration.VERTICAL)
        )
    }

    private fun createSettingsItems() {
        settingsItems.clear()

        // Service Settings Section
        settingsItems.add(
            SettingsAdapter.Item(
                id = ID_HEADER_SERVICE,
                titleRes = R.string.service_settings,
                type = SettingsAdapter.ItemType.HEADER
            )
        )
        settingsItems.add(
            SettingsAdapter.Item(
                id = ID_AUTO_START,
                titleRes = R.string.auto_start_on_boot,
                descriptionRes = R.string.auto_start_description,
                type = SettingsAdapter.ItemType.SWITCH,
                checked = configRepository.isAutoStartEnabled()
            )
        )
        settingsItems.add(
            SettingsAdapter.Item(
                id = ID_MULTICAST,
                titleRes = R.string.multicast_discovery,
                descriptionRes = R.string.multicast_discovery_description,
                type = SettingsAdapter.ItemType.SWITCH,
                checked = configRepository.isMulticastEnabled()
            )
        )

        // Network Settings Section
        settingsItems.add(
            SettingsAdapter.Item(
                id = ID_HEADER_NETWORK,
                titleRes = R.string.network_settings,
                type = SettingsAdapter.ItemType.HEADER
            )
        )
        settingsItems.add(
            SettingsAdapter.Item(
                id = ID_CONFIGURE_PEERS,
                titleRes = R.string.configure_peers_title,
                descriptionRes = R.string.configure_peers_description,
                type = SettingsAdapter.ItemType.PLAIN
            )
        )

        // Security Settings Section
        settingsItems.add(
            SettingsAdapter.Item(
                id = ID_HEADER_SECURITY,
                titleRes = R.string.security_settings,
                type = SettingsAdapter.ItemType.HEADER
            )
        )
        settingsItems.add(
            SettingsAdapter.Item(
                id = ID_CHANGE_PASSWORD,
                titleRes = R.string.change_password,
                descriptionRes = R.string.change_password_description,
                type = SettingsAdapter.ItemType.PLAIN
            )
        )
        settingsItems.add(
            SettingsAdapter.Item(
                id = ID_REGENERATE_KEYS,
                titleRes = R.string.regenerate_keys,
                descriptionRes = R.string.regenerate_keys_description,
                type = SettingsAdapter.ItemType.PLAIN
            )
        )
        settingsItems.add(
            SettingsAdapter.Item(
                id = ID_BACKUP_RESTORE,
                titleRes = R.string.backup_restore,
                descriptionRes = R.string.backup_restore_description,
                type = SettingsAdapter.ItemType.PLAIN
            )
        )

        // Appearance Settings Section
        settingsItems.add(
            SettingsAdapter.Item(
                id = ID_HEADER_APPEARANCE,
                titleRes = R.string.appearance_settings,
                type = SettingsAdapter.ItemType.HEADER
            )
        )
        settingsItems.add(
            SettingsAdapter.Item(
                id = ID_LANGUAGE,
                titleRes = R.string.language,
                descriptionRes = R.string.language_description,
                type = SettingsAdapter.ItemType.PLAIN
            )
        )
        settingsItems.add(
            SettingsAdapter.Item(
                id = ID_THEME,
                titleRes = R.string.theme,
                descriptionRes = R.string.theme_description,
                type = SettingsAdapter.ItemType.PLAIN
            )
        )

        // Debug Settings Section
        settingsItems.add(
            SettingsAdapter.Item(
                id = ID_HEADER_DEBUG,
                titleRes = R.string.debug_settings,
                type = SettingsAdapter.ItemType.HEADER
            )
        )
        settingsItems.add(
            SettingsAdapter.Item(
                id = ID_ENABLE_LOG_COLLECTION,
                titleRes = R.string.enable_log_collection,
                descriptionRes = R.string.enable_log_collection_description,
                type = SettingsAdapter.ItemType.SWITCH,
                checked = configRepository.isLogCollectionEnabled()
            )
        )
        settingsItems.add(
            SettingsAdapter.Item(
                id = ID_COLLECT_LOGS,
                titleRes = R.string.collect_logs,
                descriptionRes = R.string.collect_logs_description,
                type = SettingsAdapter.ItemType.PLAIN
            )
        )
        settingsItems.add(
            SettingsAdapter.Item(
                id = ID_CLEAR_LOGS,
                titleRes = R.string.clear_logs,
                descriptionRes = R.string.clear_logs_description,
                type = SettingsAdapter.ItemType.PLAIN
            )
        )

        // About Settings Section
        settingsItems.add(
            SettingsAdapter.Item(
                id = ID_HEADER_ABOUT,
                titleRes = R.string.about_settings,
                type = SettingsAdapter.ItemType.HEADER
            )
        )
        settingsItems.add(
            SettingsAdapter.Item(
                id = ID_ABOUT_APP,
                titleRes = R.string.about_app,
                descriptionRes = R.string.about_app_description,
                type = SettingsAdapter.ItemType.PLAIN
            )
        )

        adapter = SettingsAdapter(settingsItems, this)
        binding.recyclerView.adapter = adapter
    }

    // SettingsAdapter.Listener implementation
    override fun onSwitchToggled(id: Int, isChecked: Boolean) {
        when (id) {
            ID_AUTO_START -> {
                configRepository.setAutoStartEnabled(isChecked)
            }
            ID_MULTICAST -> {
                configRepository.setMulticastEnabled(isChecked)
                if (YggmailService.isRunning) {
                    showRestartDialog()
                }
            }
            ID_ENABLE_LOG_COLLECTION -> {
                configRepository.setLogCollectionEnabled(isChecked)
                if (YggmailService.isRunning) {
                    showRestartDialog()
                }
            }
        }
    }

    override fun onItemClicked(id: Int) {
        when (id) {
            ID_CONFIGURE_PEERS -> startActivity(Intent(this, com.jbselfcompany.tyr.ui.PeersActivity::class.java))
            ID_CHANGE_PASSWORD -> showChangePasswordDialog()
            ID_REGENERATE_KEYS -> showRegenerateKeysDialog()
            ID_BACKUP_RESTORE -> showBackupRestoreOptions()
            ID_LANGUAGE -> showLanguageDialog()
            ID_THEME -> showThemeDialog()
            ID_COLLECT_LOGS -> startActivity(Intent(this, LogsActivity::class.java))
            ID_CLEAR_LOGS -> showClearLogsDialog()
            ID_ABOUT_APP -> startActivity(Intent(this, AboutActivity::class.java))
        }
    }

    private fun showRestartDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.restart_required)
            .setMessage(R.string.restart_required_message)
            .setPositiveButton(R.string.restart_now) { _, _ ->
                restartService()
            }
            .setNegativeButton(R.string.restart_later, null)
            .show()
    }

    private fun restartService() {
        // Show loading overlay
        showLoadingOverlay(true, getString(R.string.restarting_service))

        // Stop service
        YggmailService.stop(this)

        // Wait for service to stop, then restart (6 seconds delay)
        Handler(Looper.getMainLooper()).postDelayed({
            if (!YggmailService.isRunning) {
                // Service stopped successfully, now start it
                YggmailService.start(this)

                // Wait for service to start
                Handler(Looper.getMainLooper()).postDelayed({
                    checkServiceRestartedFromSettings()
                }, 2000)
            } else {
                // Service still running, retry
                Handler(Looper.getMainLooper()).postDelayed({
                    restartService()
                }, 1000)
            }
        }, 6000)
    }

    private fun checkServiceRestartedFromSettings() {
        if (YggmailService.isRunning) {
            // Service restarted successfully
            showLoadingOverlay(false)

            Snackbar.make(
                binding.root,
                R.string.service_restarted,
                Snackbar.LENGTH_LONG
            ).show()
        } else {
            // Wait a bit more
            Handler(Looper.getMainLooper()).postDelayed({
                checkServiceRestartedFromSettings()
            }, 1000)
        }
    }

    private fun showChangePasswordDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_change_password, null)
        val editNewPassword = dialogView.findViewById<TextInputEditText>(R.id.edit_new_password)
        val editConfirmPassword = dialogView.findViewById<TextInputEditText>(R.id.edit_confirm_password)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.change_password)
            .setView(dialogView)
            .setPositiveButton(R.string.change) { _, _ ->
                val newPassword = editNewPassword.text.toString()
                val confirmPassword = editConfirmPassword.text.toString()

                when {
                    newPassword.isEmpty() -> {
                        Toast.makeText(this, R.string.error_password_empty, Toast.LENGTH_SHORT).show()
                    }
                    newPassword.length < 6 -> {
                        Toast.makeText(this, R.string.error_password_short, Toast.LENGTH_SHORT).show()
                    }
                    newPassword != confirmPassword -> {
                        Toast.makeText(this, R.string.error_password_mismatch, Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        try {
                            configRepository.savePassword(newPassword)
                            Toast.makeText(this, R.string.password_changed, Toast.LENGTH_SHORT).show()

                            if (YggmailService.isRunning) {
                                showRestartDialog()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(this, R.string.error_save_password, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showRegenerateKeysDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.regenerate_keys)
            .setMessage(R.string.regenerate_keys_message)
            .setPositiveButton(R.string.regenerate) { _, _ ->
                regenerateKeys()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun regenerateKeys() {
        val wasServiceRunning = YggmailService.isRunning

        // Show loading overlay
        showLoadingOverlay(true, getString(R.string.regenerating_keys))

        // Stop service if running
        if (wasServiceRunning) {
            YggmailService.stop(this)

            // Wait for service to stop, then regenerate
            Handler(Looper.getMainLooper()).postDelayed({
                if (!YggmailService.isRunning) {
                    performKeyRegeneration(wasServiceRunning)
                } else {
                    // Service still running, retry
                    Handler(Looper.getMainLooper()).postDelayed({
                        regenerateKeys()
                    }, 1000)
                }
            }, 2000)
        } else {
            performKeyRegeneration(wasServiceRunning)
        }
    }

    private fun performKeyRegeneration(wasServiceRunning: Boolean) {
        // Delete database
        val success = YggmailService.deleteDatabase(this)

        if (success) {
            // Clear saved keys from config
            configRepository.clearKeys()

            if (wasServiceRunning) {
                // Update loading text for restart
                binding.loadingText.text = getString(R.string.restarting_service)

                // Start service to generate new keys
                YggmailService.start(this)

                // Wait for service to start and stabilize
                Handler(Looper.getMainLooper()).postDelayed({
                    checkServiceRestarted()
                }, 2000)
            } else {
                // Service was not running, just show success
                showLoadingOverlay(false)
                Snackbar.make(
                    binding.root,
                    R.string.keys_regenerated,
                    Snackbar.LENGTH_LONG
                ).show()
            }
        } else {
            showLoadingOverlay(false)
            Snackbar.make(
                binding.root,
                R.string.error_regenerate_keys,
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    private fun checkServiceRestarted() {
        if (YggmailService.isRunning) {
            // Service restarted successfully
            showLoadingOverlay(false)

            Snackbar.make(
                binding.root,
                R.string.keys_regenerated,
                Snackbar.LENGTH_LONG
            ).show()
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
        } else if (!show) {
            binding.loadingText.text = getString(R.string.restarting_service)
        }

        // Disable RecyclerView interaction while loading
        binding.recyclerView.isEnabled = !show
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

    private fun showBackupRestoreOptions() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.backup_restore)
            .setItems(arrayOf(
                getString(R.string.create_backup),
                getString(R.string.restore_backup)
            )) { _, which ->
                when (which) {
                    0 -> showCreateBackupDialog()
                    1 -> selectBackupFile()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showCreateBackupDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_create_backup, null)
        val editPassword = dialogView.findViewById<TextInputEditText>(R.id.edit_backup_password)
        val editConfirmPassword = dialogView.findViewById<TextInputEditText>(R.id.edit_confirm_backup_password)
        val checkboxIncludeDb = dialogView.findViewById<MaterialCheckBox>(R.id.checkbox_include_database)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.create_backup)
            .setView(dialogView)
            .setPositiveButton(R.string.create_backup) { _, _ ->
                val password = editPassword.text.toString()
                val confirmPassword = editConfirmPassword.text.toString()
                val includeDatabase = checkboxIncludeDb.isChecked

                when {
                    password.isEmpty() -> {
                        Toast.makeText(this, R.string.error_backup_password_empty, Toast.LENGTH_SHORT).show()
                    }
                    password.length < 8 -> {
                        Toast.makeText(this, R.string.error_backup_password_short, Toast.LENGTH_SHORT).show()
                    }
                    password != confirmPassword -> {
                        Toast.makeText(this, R.string.error_backup_password_mismatch, Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        backupPassword = password
                        backupIncludeDatabase = includeDatabase
                        createBackupLauncher.launch(BackupManager.generateBackupFilename())
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private var backupPassword: String = ""
    private var backupIncludeDatabase: Boolean = true

    private fun performBackup(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                val success = BackupManager.createBackup(
                    context = this,
                    outputStream = outputStream,
                    backupPassword = backupPassword,
                    includeDatabase = backupIncludeDatabase
                )

                if (success) {
                    Toast.makeText(this, R.string.backup_created, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, R.string.backup_failed, Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, R.string.backup_failed, Toast.LENGTH_SHORT).show()
        } finally {
            backupPassword = ""
        }
    }

    private fun selectBackupFile() {
        restoreBackupLauncher.launch(arrayOf("*/*"))
    }

    private fun showRestoreBackupDialog(uri: Uri) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_restore_backup, null)
        val editPassword = dialogView.findViewById<TextInputEditText>(R.id.edit_restore_password)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.restore_warning_title)
            .setMessage(R.string.restore_warning)
            .setView(dialogView)
            .setPositiveButton(R.string.restore_backup) { _, _ ->
                val password = editPassword.text.toString()

                if (password.isEmpty()) {
                    Toast.makeText(this, R.string.error_backup_password_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                performRestore(uri, password)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun performRestore(uri: Uri, password: String) {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val success = BackupManager.restoreBackup(
                    context = this,
                    inputStream = inputStream,
                    backupPassword = password
                )

                if (success) {
                    Toast.makeText(this, R.string.backup_restored, Toast.LENGTH_LONG).show()

                    if (YggmailService.isRunning) {
                        showRestartDialog()
                    }
                } else {
                    Toast.makeText(this, R.string.error_invalid_backup_password, Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, R.string.restore_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showLanguageDialog() {
        val languages = arrayOf(
            ConfigRepository.LANGUAGE_SYSTEM,
            ConfigRepository.LANGUAGE_ENGLISH,
            ConfigRepository.LANGUAGE_RUSSIAN
        )

        val languageNames = languages.map { lang ->
            when (lang) {
                ConfigRepository.LANGUAGE_SYSTEM -> getString(R.string.language_system)
                ConfigRepository.LANGUAGE_ENGLISH -> getString(R.string.language_english)
                ConfigRepository.LANGUAGE_RUSSIAN -> getString(R.string.language_russian)
                else -> lang
            }
        }.toTypedArray()

        val currentLanguage = configRepository.getLanguage()
        val selectedIndex = languages.indexOf(currentLanguage).takeIf { it >= 0 } ?: 0

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.select_language)
            .setSingleChoiceItems(languageNames, selectedIndex) { dialog, which ->
                val selectedLanguage = languages[which]
                if (selectedLanguage != currentLanguage) {
                    configRepository.setLanguage(selectedLanguage)
                    dialog.dismiss()
                    showRestartAppDialog()
                } else {
                    dialog.dismiss()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showThemeDialog() {
        val themes = arrayOf(
            ConfigRepository.THEME_SYSTEM,
            ConfigRepository.THEME_LIGHT,
            ConfigRepository.THEME_DARK
        )

        val themeNames = themes.map { theme ->
            when (theme) {
                ConfigRepository.THEME_SYSTEM -> getString(R.string.theme_system)
                ConfigRepository.THEME_LIGHT -> getString(R.string.theme_light)
                ConfigRepository.THEME_DARK -> getString(R.string.theme_dark)
                else -> theme
            }
        }.toTypedArray()

        val currentTheme = configRepository.getTheme()
        val selectedIndex = themes.indexOf(currentTheme).takeIf { it >= 0 } ?: 0

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.select_theme)
            .setSingleChoiceItems(themeNames, selectedIndex) { dialog, which ->
                val selectedTheme = themes[which]
                if (selectedTheme != currentTheme) {
                    configRepository.setTheme(selectedTheme)
                    dialog.dismiss()
                    showRestartAppDialog()
                } else {
                    dialog.dismiss()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showRestartAppDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.restart_required)
            .setMessage(R.string.restart_app_required)
            .setPositiveButton(R.string.restart_now) { _, _ ->
                restartApp()
            }
            .setNegativeButton(R.string.restart_later, null)
            .show()
    }

    private fun restartApp() {
        // Show loading overlay
        showLoadingOverlay(true, getString(R.string.restart_app_required))

        // Short delay to show the overlay, then restart the app
        Handler(Looper.getMainLooper()).postDelayed({
            // Get the launch intent for MainActivity
            val intent = Intent(this, com.jbselfcompany.tyr.ui.MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(intent)
            finishAffinity() // Close all activities

            // Exit the process gracefully
            Runtime.getRuntime().exit(0)
        }, 500)
    }

    private fun applyTheme(theme: String) {
        val mode = when (theme) {
            ConfigRepository.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            ConfigRepository.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    private fun showClearLogsDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_logs)
            .setMessage(R.string.clear_logs_confirmation)
            .setPositiveButton(R.string.ok) { _, _ ->
                clearLogs()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun clearLogs() {
        try {
            // Clear the logcat buffer
            Runtime.getRuntime().exec("logcat -c")
            Toast.makeText(this, R.string.logs_cleared, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.error_clearing_logs, Toast.LENGTH_SHORT).show()
        }
    }
}
