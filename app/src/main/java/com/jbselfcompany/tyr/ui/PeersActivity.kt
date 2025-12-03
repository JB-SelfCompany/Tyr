package com.jbselfcompany.tyr.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.jbselfcompany.tyr.R
import com.jbselfcompany.tyr.TyrApplication
import com.jbselfcompany.tyr.data.ConfigRepository
import com.jbselfcompany.tyr.databinding.ActivityPeersBinding
import com.jbselfcompany.tyr.service.ServiceStatus
import com.jbselfcompany.tyr.service.ServiceStatusListener
import com.jbselfcompany.tyr.service.YggmailService

class PeersActivity : BaseActivity(), ServiceStatusListener {

    private lateinit var binding: ActivityPeersBinding
    private val configRepository by lazy { TyrApplication.instance.configRepository }
    private val peers = mutableListOf<String>()
    private lateinit var adapter: PeerAdapter
    private val mainHandler = Handler(Looper.getMainLooper())

    private var yggmailService: YggmailService? = null
    private var serviceBound = false
    private var wasServiceRunning = false
    private var hasUnsavedChanges = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as YggmailService.LocalBinder
            yggmailService = binder.getService()
            serviceBound = true
            yggmailService?.addStatusListener(this@PeersActivity)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            yggmailService?.removeStatusListener(this@PeersActivity)
            yggmailService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityPeersBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Store if service was running when we entered the activity
        wasServiceRunning = YggmailService.isRunning

        setupSwitch()
        setupRecyclerView()
        setupAddButton()
        setupApplyButton()
        loadPeers()
        bindToService()
    }

    override fun onDestroy() {
        super.onDestroy()
        unbindFromService()
    }

    private fun bindToService() {
        if (YggmailService.isRunning) {
            val intent = Intent(this, YggmailService::class.java)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun unbindFromService() {
        if (serviceBound) {
            yggmailService?.removeStatusListener(this)
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    private fun setupSwitch() {
        // Check if we should use default peers
        val useDefaultPeers = configRepository.isUsingDefaultPeers()
        binding.switchUseDefault.isChecked = useDefaultPeers
        updateCustomPeersVisibility(useDefaultPeers)

        binding.switchUseDefault.setOnCheckedChangeListener { _, isChecked ->
            updateCustomPeersVisibility(isChecked)

            if (isChecked) {
                // Switch to default peers
                configRepository.setUseDefaultPeers(true)
            } else {
                // Use custom peers
                if (peers.isEmpty()) {
                    // If no custom peers, start with an empty list
                    // User will add their own custom peers
                }
                savePeers()
            }

            // Show Apply Changes button when switching peers
            hasUnsavedChanges = true
            updateApplyButtonVisibility()
        }
    }

    private fun setupRecyclerView() {
        adapter = PeerAdapter(peers) { position ->
            removePeer(position)
        }

        binding.recyclerPeers.layoutManager = LinearLayoutManager(this)
        binding.recyclerPeers.adapter = adapter
        binding.recyclerPeers.addItemDecoration(
            DividerItemDecoration(this, DividerItemDecoration.VERTICAL)
        )
    }

    private fun setupAddButton() {
        binding.btnAddPeer.setOnClickListener {
            showAddPeerDialog()
        }
    }

    private fun setupApplyButton() {
        binding.btnApplyChanges.setOnClickListener {
            applyPeerChanges()
        }
    }

    private fun updateApplyButtonVisibility() {
        binding.btnApplyChanges.visibility = if (hasUnsavedChanges) View.VISIBLE else View.GONE
    }

    private fun loadPeers() {
        peers.clear()

        // Only load custom peers if not using defaults
        if (!configRepository.isUsingDefaultPeers()) {
            peers.addAll(configRepository.getCustomPeers())
        }

        adapter.notifyDataSetChanged()
    }

    private fun updateCustomPeersVisibility(useDefault: Boolean) {
        binding.customPeersContainer.visibility = if (useDefault) View.GONE else View.VISIBLE
    }

    private fun showAddPeerDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_peer, null)
        val editPeerUrl = dialogView.findViewById<TextInputEditText>(R.id.edit_peer_url)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_peer)
            .setView(dialogView)
            .setPositiveButton(R.string.add) { _, _ ->
                val peerUrl = editPeerUrl.text.toString().trim()

                if (peerUrl.isEmpty()) {
                    Toast.makeText(this, R.string.error_peers_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (peerUrl.contains("\n")) {
                    Toast.makeText(this, "Peer URL cannot contain newlines", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (peers.contains(peerUrl)) {
                    Toast.makeText(this, "Peer already exists", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                peers.add(peerUrl)
                adapter.notifyItemInserted(peers.size - 1)
                savePeers()

                hasUnsavedChanges = true
                updateApplyButtonVisibility()

                Toast.makeText(this, R.string.peers_saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun removePeer(position: Int) {
        if (position < 0 || position >= peers.size) return

        peers.removeAt(position)
        adapter.notifyItemRemoved(position)
        savePeers()

        hasUnsavedChanges = true
        updateApplyButtonVisibility()

        Toast.makeText(this, R.string.peers_saved, Toast.LENGTH_SHORT).show()
    }

    private fun savePeers() {
        if (peers.isNotEmpty()) {
            configRepository.savePeers(peers)
        }
    }

    private fun applyPeerChanges() {
        if (!wasServiceRunning) {
            // Service was not running, just close the activity
            hasUnsavedChanges = false
            Toast.makeText(this, R.string.peers_applied, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Show loading overlay
        showLoadingOverlay(true)

        // Stop the service first
        YggmailService.stop(this)

        // Wait for service to stop, then restart
        mainHandler.postDelayed({
            if (!YggmailService.isRunning) {
                // Service stopped, now restart
                YggmailService.start(this)

                // Wait for service to start and stabilize
                mainHandler.postDelayed({
                    checkServiceRestarted()
                }, 2000)
            } else {
                // Service still running, retry
                mainHandler.postDelayed({
                    applyPeerChanges()
                }, 1000)
            }
        }, 2000)
    }

    private fun checkServiceRestarted() {
        if (YggmailService.isRunning) {
            // Service restarted successfully
            showLoadingOverlay(false)
            hasUnsavedChanges = false
            updateApplyButtonVisibility()

            Snackbar.make(
                binding.root,
                R.string.peers_applied,
                Snackbar.LENGTH_LONG
            ).show()
        } else {
            // Wait a bit more
            mainHandler.postDelayed({
                checkServiceRestarted()
            }, 1000)
        }
    }

    private fun showLoadingOverlay(show: Boolean) {
        binding.loadingOverlay.visibility = if (show) View.VISIBLE else View.GONE

        // Disable all interactive elements while loading
        binding.switchUseDefault.isEnabled = !show
        binding.btnAddPeer.isEnabled = !show
        binding.btnApplyChanges.isEnabled = !show
        binding.recyclerPeers.isEnabled = !show
    }

    override fun onStatusChanged(status: ServiceStatus, error: String?) {
        mainHandler.post {
            if (error != null) {
                showLoadingOverlay(false)
                Snackbar.make(
                    binding.root,
                    getString(R.string.error_applying_peers) + ": $error",
                    Snackbar.LENGTH_LONG
                ).show()
            }
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
