package com.jbselfcompany.tyr.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.jbselfcompany.tyr.R
import com.jbselfcompany.tyr.TyrApplication
import com.jbselfcompany.tyr.databinding.FragmentSettingsBinding
import com.jbselfcompany.tyr.service.YggmailService
import com.jbselfcompany.tyr.ui.SettingsAdapter
import com.jbselfcompany.tyr.ui.AboutActivity
import com.jbselfcompany.tyr.ui.MainActivity
import com.jbselfcompany.tyr.ui.logs.LogsActivity
import com.jbselfcompany.tyr.utils.BackupManager
import com.jbselfcompany.tyr.utils.UpdateChecker
import com.jbselfcompany.tyr.data.ConfigRepository

/**
 * Settings fragment for managing Yggdrasil peers and service configuration.
 * Embedded in MainActivity for instant tab switching (no Activity transition delay).
 * Direct port of SettingsActivity logic to Fragment lifecycle.
 */
class SettingsFragment : Fragment(), SettingsAdapter.Listener {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val configRepository by lazy { TyrApplication.instance.configRepository }
    private lateinit var adapter: SettingsAdapter
    private val settingsItems = mutableListOf<SettingsAdapter.Item>()

    companion object {
        // Setting IDs
        private const val ID_HEADER_SERVICE = 1
        private const val ID_AUTO_START = 2
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
        private const val ID_HEADER_UPDATES = 22
        private const val ID_UPDATE_CHECK_ENABLED = 23
        private const val ID_UPDATE_CHECK_INTERVAL = 24
        private const val ID_UPDATE_CHECK_NOW = 25

        // Chat settings
        private const val ID_HEADER_CHAT = 26
        private const val ID_ACCEPT_NON_CONTACTS = 27
    }

    private var backupPassword: String = ""
    private var backupIncludeDatabase: Boolean = true

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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        createSettingsItems()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupRecyclerView() {
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.addItemDecoration(
            DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL)
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

        // Chat Settings Section
        settingsItems.add(
            SettingsAdapter.Item(
                id = ID_HEADER_CHAT,
                titleRes = R.string.chat_settings,
                type = SettingsAdapter.ItemType.HEADER
            )
        )
        settingsItems.add(
            SettingsAdapter.Item(
                id = ID_ACCEPT_NON_CONTACTS,
                titleRes = R.string.chat_accept_non_contacts,
                descriptionRes = R.string.chat_accept_non_contacts_description,
                type = SettingsAdapter.ItemType.SWITCH,
                checked = configRepository.getAcceptMessagesFromNonContacts()
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

        // Updates Settings Section
        settingsItems.add(
            SettingsAdapter.Item(
                id = ID_HEADER_UPDATES,
                titleRes = R.string.updates_settings,
                type = SettingsAdapter.ItemType.HEADER
            )
        )
        settingsItems.add(
            SettingsAdapter.Item(
                id = ID_UPDATE_CHECK_ENABLED,
                titleRes = R.string.update_check_enabled,
                descriptionRes = R.string.update_check_enabled_description,
                type = SettingsAdapter.ItemType.SWITCH,
                checked = configRepository.isUpdateCheckEnabled()
            )
        )
        settingsItems.add(
            SettingsAdapter.Item(
                id = ID_UPDATE_CHECK_INTERVAL,
                titleRes = R.string.update_check_interval,
                descriptionRes = R.string.update_check_interval_description,
                type = SettingsAdapter.ItemType.PLAIN
            )
        )
        settingsItems.add(
            SettingsAdapter.Item(
                id = ID_UPDATE_CHECK_NOW,
                titleRes = R.string.update_check_now,
                descriptionRes = R.string.update_check_now_description,
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

    override fun onSwitchToggled(id: Int, isChecked: Boolean) {
        when (id) {
            ID_AUTO_START -> {
                configRepository.setAutoStartEnabled(isChecked)
            }
            ID_ENABLE_LOG_COLLECTION -> {
                configRepository.setLogCollectionEnabled(isChecked)
                val msgRes = if (isChecked) R.string.log_collection_enabled else R.string.log_collection_disabled_snackbar
                Snackbar.make(requireView(), msgRes, Snackbar.LENGTH_SHORT).show()
            }
            ID_UPDATE_CHECK_ENABLED -> {
                configRepository.setUpdateCheckEnabled(isChecked)
            }
            ID_ACCEPT_NON_CONTACTS -> {
                configRepository.setAcceptMessagesFromNonContacts(isChecked)
            }
        }
    }

    override fun onItemClicked(id: Int) {
        when (id) {
            ID_CONFIGURE_PEERS -> startActivity(Intent(requireContext(), com.jbselfcompany.tyr.ui.PeersActivity::class.java))
            ID_CHANGE_PASSWORD -> showChangePasswordDialog()
            ID_REGENERATE_KEYS -> showRegenerateKeysDialog()
            ID_BACKUP_RESTORE -> showBackupRestoreOptions()
            ID_LANGUAGE -> showLanguageDialog()
            ID_THEME -> showThemeDialog()
            ID_COLLECT_LOGS -> startActivity(Intent(requireContext(), LogsActivity::class.java))
            ID_CLEAR_LOGS -> showClearLogsDialog()
            ID_UPDATE_CHECK_INTERVAL -> showUpdateIntervalDialog()
            ID_UPDATE_CHECK_NOW -> checkForUpdatesNow()
            ID_ABOUT_APP -> startActivity(Intent(requireContext(), AboutActivity::class.java))
        }
    }

    private fun showRestartDialog() {
        MaterialAlertDialogBuilder(requireContext())
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

        // Soft stop service (gracefully disconnect peers first)
        YggmailService.softStop(requireContext())

        // Wait for service to stop, then restart (6 seconds delay)
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isAdded) return@postDelayed
            if (!YggmailService.isRunning) {
                // Service stopped successfully, now start it
                YggmailService.start(requireContext())

                // Wait for service to start
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!isAdded) return@postDelayed
                    checkServiceRestartedFromSettings()
                }, 2000)
            } else {
                // Service still running, retry
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!isAdded) return@postDelayed
                    restartService()
                }, 1000)
            }
        }, 6000)
    }

    private fun checkServiceRestartedFromSettings() {
        if (!isAdded) return
        if (YggmailService.isRunning) {
            // Service restarted successfully
            showLoadingOverlay(false)

            Snackbar.make(
                requireView(),
                R.string.service_restarted,
                Snackbar.LENGTH_LONG
            ).show()
        } else {
            // Wait a bit more
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isAdded) return@postDelayed
                checkServiceRestartedFromSettings()
            }, 1000)
        }
    }

    private fun showChangePasswordDialog() {
        val dialogView = requireActivity().layoutInflater.inflate(R.layout.dialog_change_password, null)
        val editNewPassword = dialogView.findViewById<TextInputEditText>(R.id.edit_new_password)
        val editConfirmPassword = dialogView.findViewById<TextInputEditText>(R.id.edit_confirm_password)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.change_password)
            .setView(dialogView)
            .setPositiveButton(R.string.change) { _, _ ->
                val newPassword = editNewPassword.text.toString()
                val confirmPassword = editConfirmPassword.text.toString()

                when {
                    newPassword.isEmpty() -> {
                        Toast.makeText(requireContext(), R.string.error_password_empty, Toast.LENGTH_SHORT).show()
                    }
                    newPassword.length < 6 -> {
                        Toast.makeText(requireContext(), R.string.error_password_short, Toast.LENGTH_SHORT).show()
                    }
                    newPassword != confirmPassword -> {
                        Toast.makeText(requireContext(), R.string.error_password_mismatch, Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        try {
                            configRepository.savePassword(newPassword)
                            Toast.makeText(requireContext(), R.string.password_changed, Toast.LENGTH_SHORT).show()

                            // Apply new password to running service without restart
                            TyrApplication.instance.yggmailServiceBinder?.getService()?.hotReloadPassword()
                        } catch (e: Exception) {
                            Toast.makeText(requireContext(), R.string.error_save_password, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showRegenerateKeysDialog() {
        MaterialAlertDialogBuilder(requireContext())
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
            YggmailService.stop(requireContext())

            // Wait for service to stop, then regenerate
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isAdded) return@postDelayed
                if (!YggmailService.isRunning) {
                    performKeyRegeneration(wasServiceRunning)
                } else {
                    // Service still running, retry
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!isAdded) return@postDelayed
                        regenerateKeys()
                    }, 1000)
                }
            }, 2000)
        } else {
            performKeyRegeneration(wasServiceRunning)
        }
    }

    private fun performKeyRegeneration(wasServiceRunning: Boolean) {
        if (!isAdded) return

        // Delete database
        val success = YggmailService.deleteDatabase(requireContext())

        if (success) {
            // Clear saved keys from config
            configRepository.clearKeys()

            if (wasServiceRunning) {
                // Update loading text for restart
                if (_binding != null) {
                    binding.loadingText.text = getString(R.string.restarting_service)
                }

                // Start service to generate new keys
                YggmailService.start(requireContext())

                // Wait for service to start and stabilize
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!isAdded) return@postDelayed
                    checkServiceRestarted()
                }, 2000)
            } else {
                // Service was not running, just show success
                showLoadingOverlay(false)
                Snackbar.make(
                    requireView(),
                    R.string.keys_regenerated,
                    Snackbar.LENGTH_LONG
                ).show()
            }
        } else {
            showLoadingOverlay(false)
            Snackbar.make(
                requireView(),
                R.string.error_regenerate_keys,
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    private fun checkServiceRestarted() {
        if (!isAdded) return
        if (YggmailService.isRunning) {
            // Service restarted successfully
            showLoadingOverlay(false)

            Snackbar.make(
                requireView(),
                R.string.keys_regenerated,
                Snackbar.LENGTH_LONG
            ).show()
        } else {
            // Wait a bit more
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isAdded) return@postDelayed
                checkServiceRestarted()
            }, 1000)
        }
    }

    private fun showLoadingOverlay(show: Boolean, text: String? = null) {
        if (_binding == null) return

        binding.loadingOverlay.visibility = if (show) View.VISIBLE else View.GONE

        if (show && text != null) {
            binding.loadingText.text = text
        } else if (!show) {
            binding.loadingText.text = getString(R.string.restarting_service)
        }

        // Disable RecyclerView interaction while loading
        binding.recyclerView.isEnabled = !show
    }

    private fun showBackupRestoreOptions() {
        MaterialAlertDialogBuilder(requireContext())
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
        val dialogView = requireActivity().layoutInflater.inflate(R.layout.dialog_create_backup, null)
        val editPassword = dialogView.findViewById<TextInputEditText>(R.id.edit_backup_password)
        val editConfirmPassword = dialogView.findViewById<TextInputEditText>(R.id.edit_confirm_backup_password)
        val checkboxIncludeDb = dialogView.findViewById<MaterialCheckBox>(R.id.checkbox_include_database)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.create_backup)
            .setView(dialogView)
            .setPositiveButton(R.string.create_backup) { _, _ ->
                val password = editPassword.text.toString()
                val confirmPassword = editConfirmPassword.text.toString()
                val includeDatabase = checkboxIncludeDb.isChecked

                when {
                    password.isEmpty() -> {
                        Toast.makeText(requireContext(), R.string.error_backup_password_empty, Toast.LENGTH_SHORT).show()
                    }
                    password.length < 8 -> {
                        Toast.makeText(requireContext(), R.string.error_backup_password_short, Toast.LENGTH_SHORT).show()
                    }
                    password != confirmPassword -> {
                        Toast.makeText(requireContext(), R.string.error_backup_password_mismatch, Toast.LENGTH_SHORT).show()
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

    private fun performBackup(uri: Uri) {
        try {
            requireContext().contentResolver.openOutputStream(uri)?.use { outputStream ->
                val success = BackupManager.createBackup(
                    context = requireContext(),
                    outputStream = outputStream,
                    backupPassword = backupPassword,
                    includeDatabase = backupIncludeDatabase
                )

                if (success) {
                    Toast.makeText(requireContext(), R.string.backup_created, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(requireContext(), R.string.backup_failed, Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), R.string.backup_failed, Toast.LENGTH_SHORT).show()
        } finally {
            backupPassword = ""
        }
    }

    private fun selectBackupFile() {
        restoreBackupLauncher.launch(arrayOf("*/*"))
    }

    private fun showRestoreBackupDialog(uri: Uri) {
        val dialogView = requireActivity().layoutInflater.inflate(R.layout.dialog_restore_backup, null)
        val editPassword = dialogView.findViewById<TextInputEditText>(R.id.edit_restore_password)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.restore_warning_title)
            .setMessage(R.string.restore_warning)
            .setView(dialogView)
            .setPositiveButton(R.string.restore_backup) { _, _ ->
                val password = editPassword.text.toString()

                if (password.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.error_backup_password_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                performRestore(uri, password)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun performRestore(uri: Uri, password: String) {
        try {
            requireContext().contentResolver.openInputStream(uri)?.use { inputStream ->
                val success = BackupManager.restoreBackup(
                    context = requireContext(),
                    inputStream = inputStream,
                    backupPassword = password
                )

                if (success) {
                    Toast.makeText(requireContext(), R.string.backup_restored, Toast.LENGTH_LONG).show()

                    if (YggmailService.isRunning) {
                        showRestartDialog()
                    }
                } else {
                    Toast.makeText(requireContext(), R.string.error_invalid_backup_password, Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), R.string.restore_failed, Toast.LENGTH_SHORT).show()
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

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.select_language)
            .setSingleChoiceItems(languageNames, selectedIndex) { dialog, which ->
                val selectedLanguage = languages[which]
                if (selectedLanguage != currentLanguage) {
                    configRepository.setLanguage(selectedLanguage)
                    dialog.dismiss()
                    requireActivity().recreate()
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

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.select_theme)
            .setSingleChoiceItems(themeNames, selectedIndex) { dialog, which ->
                val selectedTheme = themes[which]
                if (selectedTheme != currentTheme) {
                    configRepository.setTheme(selectedTheme)
                    dialog.dismiss()
                    applyTheme(selectedTheme)
                } else {
                    dialog.dismiss()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
        MaterialAlertDialogBuilder(requireContext())
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
            Toast.makeText(requireContext(), R.string.logs_cleared, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), R.string.error_clearing_logs, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showUpdateIntervalDialog() {
        val intervals = intArrayOf(
            ConfigRepository.UPDATE_INTERVAL_ON_START,
            ConfigRepository.UPDATE_INTERVAL_DAILY,
            ConfigRepository.UPDATE_INTERVAL_WEEKLY
        )
        val labels = arrayOf(
            getString(R.string.update_interval_on_start),
            getString(R.string.update_interval_daily),
            getString(R.string.update_interval_weekly)
        )
        val current = configRepository.getUpdateCheckIntervalHours()
        val selectedIndex = intervals.indexOfFirst { it == current }.takeIf { it >= 0 } ?: 1

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.select_update_interval)
            .setSingleChoiceItems(labels, selectedIndex) { dialog, which ->
                configRepository.setUpdateCheckIntervalHours(intervals[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun checkForUpdatesNow() {
        showLoadingOverlay(true, getString(R.string.update_checking))

        Thread {
            val info = UpdateChecker(requireContext()).checkForUpdates()
            configRepository.setLastUpdateCheckTime(System.currentTimeMillis())

            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                showLoadingOverlay(false)
                if (info == null) {
                    Toast.makeText(requireContext(), R.string.update_check_failed, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                if (!info.hasUpdate) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.update_no_updates, info.currentVersion),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@runOnUiThread
                }
                showUpdateDialog(info)
            }
        }.start()
    }

    private fun showUpdateDialog(info: UpdateChecker.UpdateInfo) {
        if (!isAdded) return
        val message = if (info.isFdroidInstall) {
            getString(R.string.update_available_fdroid, info.latestVersion, info.currentVersion)
        } else {
            getString(R.string.update_available_github, info.latestVersion, info.currentVersion)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.update_available_title)
            .setMessage(message)
            .setPositiveButton(R.string.update_download) { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.releaseUrl)))
            }
            .setNeutralButton(R.string.update_skip_version) { _, _ ->
                configRepository.setDismissedUpdateVersion(info.latestVersion)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
