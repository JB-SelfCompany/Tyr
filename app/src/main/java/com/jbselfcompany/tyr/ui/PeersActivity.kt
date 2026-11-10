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
import android.widget.PopupMenu
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.DividerItemDecoration
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.jbselfcompany.tyr.R
import com.jbselfcompany.tyr.TyrApplication
import com.jbselfcompany.tyr.data.ConfigRepository
import com.jbselfcompany.tyr.data.PeerInfo
import com.jbselfcompany.tyr.databinding.ActivityPeersBinding
import com.jbselfcompany.tyr.service.ServiceStatus
import com.jbselfcompany.tyr.service.ServiceStatusListener
import com.jbselfcompany.tyr.service.YggmailService
import com.jbselfcompany.tyr.utils.PeerCatalog

class PeersActivity : BaseActivity(), ServiceStatusListener {

    private lateinit var binding: ActivityPeersBinding
    private val configRepository by lazy { TyrApplication.instance.configRepository }
    private val peers = mutableListOf<PeerInfo>()
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

    override fun onContextItemSelected(item: android.view.MenuItem): Boolean {
        val position = adapter.getContextMenuPosition()
        return when (item.itemId) {
            PeerAdapter.MENU_EDIT -> {
                editPeer(position)
                true
            }
            PeerAdapter.MENU_DELETE -> {
                showDeleteConfirmation(position)
                true
            }
            else -> super.onContextItemSelected(item)
        }
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


    private fun setupRecyclerView() {
        adapter = PeerAdapter(
            peers = peers,
            onEdit = { position -> editPeer(position) },
            onRemove = { position -> showDeleteConfirmation(position) },
            onToggle = { position, enabled -> togglePeer(position, enabled) }
        )

        binding.recyclerPeers.layoutManager = LinearLayoutManager(this)
        binding.recyclerPeers.adapter = adapter
        binding.recyclerPeers.addItemDecoration(
            DividerItemDecoration(this, DividerItemDecoration.VERTICAL)
        )

        registerForContextMenu(binding.recyclerPeers)
    }

    private fun setupAddButton() {
        binding.btnAddPeer.setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menuInflater.inflate(R.menu.menu_add_peer, popup.menu)
            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.action_add_manually -> {
                        showAddPeerDialog()
                        true
                    }
                    R.id.action_pick_peer -> {
                        pickBestPeer()
                        true
                    }
                    R.id.action_choose_from_list -> {
                        chooseFromCatalog()
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }
    }

    /** The whole public peer list: pick a country (or all), then tick any number of its peers. */
    private fun chooseFromCatalog() {
        val loading = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.peer_list_title)
            .setMessage(R.string.peer_pick_loading)
            .setNegativeButton(R.string.cancel, null)
            .show()
        Thread({
            val result = runCatching { PeerCatalog.byCountry(PeerCatalog.fetch(this)) }
            mainHandler.post {
                if (!loading.isShowing || isFinishing) return@post // cancelled meanwhile
                loading.dismiss()
                result.fold(
                    onSuccess = ::showCatalogCountries,
                    onFailure = { Toast.makeText(this, getString(R.string.peer_list_failed, it.message ?: ""), Toast.LENGTH_LONG).show() }
                )
            }
        }, "peer-catalog").start()
    }

    private fun showCatalogCountries(byCountry: Map<String, List<PeerCatalog.Entry>>) {
        val all = PeerCatalog.sorted(byCountry.values.flatten())
        val names = listOf(getString(R.string.peer_list_all_countries)) + byCountry.keys
        val lists = listOf(all) + byCountry.values
        val labels = names.zip(lists) { name, list -> "$name (${list.count { it.up }}/${list.size})" }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.peer_list_title)
            .setItems(labels.toTypedArray()) { _, i -> showCatalogPeers(names[i], lists[i], showCountry = i == 0) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showCatalogPeers(title: String, entries: List<PeerCatalog.Entry>, showCountry: Boolean) {
        // Already in the list: nothing to add, so not offered.
        val offer = entries.filter { e -> peers.none { it.uri == e.uri } }
        if (offer.isEmpty()) {
            Toast.makeText(this, R.string.peer_list_nothing_new, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = offer.map { e ->
            val state = if (!e.up) getString(R.string.peer_list_offline) else e.ms?.let { "$it ms" } ?: ""
            listOfNotNull(e.uri, e.country.takeIf { showCountry }, state.ifEmpty { null }).joinToString(" · ")
        }
        val checked = BooleanArray(offer.size)
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMultiChoiceItems(labels.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton(R.string.add) { _, _ ->
                val chosen = offer.filterIndexed { i, _ -> checked[i] }
                chosen.forEach { e ->
                    val peer = PeerInfo(e.uri, isEnabled = true, tag = PeerInfo.PeerTag.CUSTOM)
                    peers.add(peer)
                    configRepository.savePeer(peer)
                }
                if (chosen.isNotEmpty()) {
                    adapter.notifyDataSetChanged()
                    hasUnsavedChanges = true
                    updateApplyButtonVisibility()
                    Toast.makeText(this, R.string.peers_saved, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Rank public peers on a worker thread; show progress, then the top and an Apply button. */
    private fun pickBestPeer() {
        val service = yggmailService?.takeIf { YggmailService.isRunning }
        if (service == null) {
            Toast.makeText(this, R.string.peer_pick_needs_service, Toast.LENGTH_SHORT).show()
            return
        }
        var result: org.json.JSONObject? = null
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.peer_pick_best)
            .setMessage(R.string.peer_pick_loading)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ -> service.cancelPeerPick() }
            .setPositiveButton(R.string.peer_pick_apply) { _, _ ->
                result?.let { service.applyPeerPick(it); loadPeers() }
            }
            .show()
        val apply = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
        apply.visibility = View.GONE
        Thread({
            val text = try {
                val r = service.runPeerPick { stage, done, total ->
                    val label = getString(if (stage == "provider") R.string.peer_pick_stage_provider else R.string.peer_pick_stage_yggdrasil)
                    mainHandler.post { dialog.setMessage("$label $done/$total") }
                }
                result = r
                describePick(r)
            } catch (e: Exception) {
                getString(R.string.peer_pick_failed, e.message ?: "")
            }
            mainHandler.post {
                dialog.setMessage(text)
                dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).setText(R.string.close)
                apply.visibility = if (result?.optString("switch").isNullOrEmpty()) View.GONE else View.VISIBLE
            }
        }, "peer-pick").start()
    }

    private fun describePick(r: org.json.JSONObject): String {
        val verdict = when (r.optString("verdict")) {
            "switch" -> r.optInt("gain", -1).let {
                if (it >= 0) getString(R.string.peer_pick_switch_gain, it) else getString(R.string.peer_pick_switch)
            }
            "current_best", "small_gain" -> getString(R.string.peer_pick_keep)
            else -> getString(R.string.peer_pick_none)
        }
        val rows = r.optJSONArray("rows") ?: org.json.JSONArray()
        val top = (0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.optBoolean("usable") }.take(5)
            .joinToString("\n") { "• ${it.optString("host")} (${it.optString("country")}) — ${it.optDouble("latencyMs").toLong()} ms" }
        val backup = r.optString("backup").ifEmpty { null }?.let { "\n" + getString(R.string.peer_pick_backup, it) } ?: ""
        return "$verdict\n\n$top$backup"
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
        // Load all peers with enabled/disabled state
        peers.addAll(configRepository.getAllPeersInfo())
        adapter.notifyDataSetChanged()
    }

    private fun showAddPeerDialog() {
        showPeerDialog(title = getString(R.string.add_peer), existingPeer = null)
    }

    private fun editPeer(position: Int) {
        if (position < 0 || position >= peers.size) return

        val peer = peers[position]
        showPeerDialog(
            title = getString(R.string.edit_peer),
            existingPeer = peer,
            onSave = { newPeerUrl ->
                // Update the peer
                val updatedPeer = peer.copy(uri = newPeerUrl)
                peers[position] = updatedPeer

                // Remove old peer and save new one
                configRepository.removePeer(peer.uri)
                configRepository.savePeer(updatedPeer)

                adapter.notifyItemChanged(position)

                hasUnsavedChanges = true
                updateApplyButtonVisibility()

                Toast.makeText(this, R.string.peers_saved, Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun showPeerDialog(title: String, existingPeer: PeerInfo?, onSave: ((String) -> Unit)? = null) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_peer, null)
        val editPeerUrl = dialogView.findViewById<TextInputEditText>(R.id.edit_peer_url)

        // Pre-fill with existing peer URL if editing
        existingPeer?.let {
            editPeerUrl.setText(it.uri)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(dialogView)
            .setPositiveButton(if (existingPeer != null) R.string.save else R.string.add) { _, _ ->
                var peerUrl = editPeerUrl.text.toString().trim()

                if (peerUrl.isEmpty()) {
                    Toast.makeText(this, R.string.error_peers_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (peerUrl.contains("\n")) {
                    Toast.makeText(this, R.string.error_peer_url_newlines, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                // Validate and fix peer URL format
                // Yggdrasil requires full URI with protocol (tcp://, tls://, quic://, etc.)
                if (!peerUrl.contains("://")) {
                    // Auto-add tcp:// prefix if no protocol specified
                    peerUrl = "tcp://$peerUrl"
                    Toast.makeText(this, R.string.peer_prefix_added, Toast.LENGTH_SHORT).show()
                }

                // Validate protocol and URL structure using shared validator
                if (!PeerInfo.isValidPeerUrl(peerUrl)) {
                    Toast.makeText(this, getString(R.string.error_invalid_peer_url), Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }

                // Check for duplicates, excluding the peer being edited
                if (peers.any { it.uri == peerUrl && it.uri != existingPeer?.uri }) {
                    Toast.makeText(this, R.string.peer_already_exists, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (existingPeer != null) {
                    // Edit mode
                    onSave?.invoke(peerUrl)
                } else {
                    // Add mode
                    val newPeer = PeerInfo(peerUrl, isEnabled = true, tag = PeerInfo.PeerTag.CUSTOM)
                    peers.add(newPeer)
                    adapter.notifyItemInserted(peers.size - 1)
                    configRepository.savePeer(newPeer)

                    hasUnsavedChanges = true
                    updateApplyButtonVisibility()

                    Toast.makeText(this, R.string.peers_saved, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDeleteConfirmation(position: Int) {
        if (position < 0 || position >= peers.size) return

        val peer = peers[position]

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_peer)
            .setMessage(getString(R.string.delete_peer_confirmation, peer.uri))
            .setPositiveButton(R.string.delete_peer) { _, _ ->
                removePeer(position)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun removePeer(position: Int) {
        if (position < 0 || position >= peers.size) return

        val peerToRemove = peers[position]
        peers.removeAt(position)
        adapter.notifyItemRemoved(position)
        configRepository.removePeer(peerToRemove.uri)

        hasUnsavedChanges = true
        updateApplyButtonVisibility()

        Toast.makeText(this, R.string.peers_saved, Toast.LENGTH_SHORT).show()
    }

    private fun togglePeer(position: Int, enabled: Boolean) {
        if (position < 0 || position >= peers.size) return

        val peer = peers[position]
        peers[position] = peer.copy(isEnabled = enabled)
        configRepository.setPeerEnabled(peer.uri, enabled)

        hasUnsavedChanges = true
        updateApplyButtonVisibility()

        Toast.makeText(this, if (enabled) R.string.peer_enabled else R.string.peer_disabled, Toast.LENGTH_SHORT).show()
    }

    private fun applyPeerChanges() {
        if (!wasServiceRunning) {
            // Service was not running, just close the activity
            hasUnsavedChanges = false
            Toast.makeText(this, R.string.peers_applied, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Use hot reload instead of stop/start to prevent connection errors
        // This updates peer configuration without closing transport connections
        yggmailService?.hotReloadPeers()

        hasUnsavedChanges = false
        updateApplyButtonVisibility()

        Snackbar.make(
            binding.root,
            R.string.peers_applied,
            Snackbar.LENGTH_LONG
        ).show()
    }

    private fun showLoadingOverlay(show: Boolean) {
        binding.loadingOverlay.visibility = if (show) View.VISIBLE else View.GONE

        // Disable all interactive elements while loading
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
