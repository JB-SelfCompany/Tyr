package com.jbselfcompany.tyr.ui

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.jbselfcompany.tyr.R
import com.jbselfcompany.tyr.TyrApplication
import com.jbselfcompany.tyr.data.ConfigRepository
import com.jbselfcompany.tyr.databinding.ActivityPeersBinding

class PeersActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPeersBinding
    private val configRepository by lazy { TyrApplication.instance.configRepository }
    private val peers = mutableListOf<String>()
    private lateinit var adapter: PeerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityPeersBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setupSwitch()
        setupRecyclerView()
        setupAddButton()
        loadPeers()
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

        Toast.makeText(this, R.string.peers_saved, Toast.LENGTH_SHORT).show()
    }

    private fun savePeers() {
        if (peers.isNotEmpty()) {
            configRepository.savePeers(peers)
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
