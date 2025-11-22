package com.jbselfcompany.tyr.ui.settings

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.jbselfcompany.tyr.R
import com.jbselfcompany.tyr.TyrApplication
import com.jbselfcompany.tyr.databinding.ActivitySettingsBinding
import com.jbselfcompany.tyr.service.YggmailService
import com.jbselfcompany.tyr.ui.SettingsAdapter
import com.jbselfcompany.tyr.ui.AboutActivity
import com.jbselfcompany.tyr.ui.logs.LogsActivity

/**
 * Settings activity for managing Yggdrasil peers and service configuration
 * Redesigned with RecyclerView adapter pattern inspired by Mimir
 */
class SettingsActivity : AppCompatActivity(), SettingsAdapter.Listener {

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
        private const val ID_HEADER_DEBUG = 9
        private const val ID_COLLECT_LOGS = 10
        private const val ID_HEADER_ABOUT = 11
        private const val ID_ABOUT_APP = 12
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
                id = ID_COLLECT_LOGS,
                titleRes = R.string.collect_logs,
                descriptionRes = R.string.collect_logs_description,
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
        }
    }

    override fun onItemClicked(id: Int) {
        when (id) {
            ID_CONFIGURE_PEERS -> startActivity(Intent(this, com.jbselfcompany.tyr.ui.PeersActivity::class.java))
            ID_CHANGE_PASSWORD -> showChangePasswordDialog()
            ID_REGENERATE_KEYS -> showRegenerateKeysDialog()
            ID_COLLECT_LOGS -> startActivity(Intent(this, LogsActivity::class.java))
            ID_ABOUT_APP -> startActivity(Intent(this, AboutActivity::class.java))
        }
    }

    private fun showRestartDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.restart_required)
            .setMessage(R.string.restart_required_message)
            .setPositiveButton(R.string.restart_now) { _, _ ->
                // Stop and restart service manually with longer delay
                YggmailService.stop(this)
                // Wait for service to stop, then restart (increased to 6 seconds)
                Handler(Looper.getMainLooper()).postDelayed({
                    YggmailService.start(this)
                }, 6000) // 6 seconds delay to ensure ports are fully released
            }
            .setNegativeButton(R.string.restart_later, null)
            .show()
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
                        configRepository.savePassword(newPassword)
                        Toast.makeText(this, R.string.password_changed, Toast.LENGTH_SHORT).show()

                        if (YggmailService.isRunning) {
                            showRestartDialog()
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
        // Stop service if running
        if (YggmailService.isRunning) {
            YggmailService.stop(this)

            // Wait for service to stop, then regenerate
            Handler(Looper.getMainLooper()).postDelayed({
                performKeyRegeneration()
            }, 6000) // 6 seconds delay to ensure service is fully stopped
        } else {
            performKeyRegeneration()
        }
    }

    private fun performKeyRegeneration() {
        // Delete database
        val success = YggmailService.deleteDatabase(this)

        if (success) {
            // Clear saved keys from config
            configRepository.clearKeys()

            Toast.makeText(this, R.string.keys_regenerated, Toast.LENGTH_LONG).show()

            // Start service to generate new keys
            YggmailService.start(this)
        } else {
            Toast.makeText(this, R.string.error_regenerate_keys, Toast.LENGTH_SHORT).show()
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
}
