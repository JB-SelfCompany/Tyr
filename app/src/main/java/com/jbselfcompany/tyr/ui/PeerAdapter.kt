package com.jbselfcompany.tyr.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.jbselfcompany.tyr.R
import com.jbselfcompany.tyr.data.PeerInfo

class PeerAdapter(
    private val peers: MutableList<PeerInfo>,
    private val onRemove: (Int) -> Unit,
    private val onToggle: (Int, Boolean) -> Unit
) : RecyclerView.Adapter<PeerAdapter.PeerViewHolder>() {

    inner class PeerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val peerText: TextView = itemView.findViewById(R.id.peer_text)
        val peerCheckbox: CheckBox = itemView.findViewById(R.id.peer_checkbox)
        val peerTypeLabel: TextView = itemView.findViewById(R.id.peer_type_label)
        val btnRemove: MaterialButton = itemView.findViewById(R.id.btn_remove)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PeerViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_peer, parent, false)
        return PeerViewHolder(view)
    }

    override fun onBindViewHolder(holder: PeerViewHolder, position: Int) {
        val peer = peers[position]

        holder.peerText.text = peer.uri
        holder.peerCheckbox.isChecked = peer.isEnabled

        // Show tag label for all peers
        holder.peerTypeLabel.visibility = View.VISIBLE
        holder.peerTypeLabel.text = when (peer.tag) {
            PeerInfo.PeerTag.DEFAULT -> holder.itemView.context.getString(R.string.peer_tag_default)
            PeerInfo.PeerTag.MULTICAST -> holder.itemView.context.getString(R.string.peer_tag_multicast)
            PeerInfo.PeerTag.CUSTOM -> holder.itemView.context.getString(R.string.peer_tag_custom)
        }

        holder.peerCheckbox.setOnCheckedChangeListener(null)
        holder.peerCheckbox.setOnCheckedChangeListener { _, isChecked ->
            onToggle(holder.adapterPosition, isChecked)
        }

        holder.btnRemove.setOnClickListener {
            onRemove(holder.adapterPosition)
        }
    }

    override fun getItemCount(): Int = peers.size
}
