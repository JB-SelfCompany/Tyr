package com.jbselfcompany.tyr.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.jbselfcompany.tyr.R

class PeerAdapter(
    private val peers: MutableList<String>,
    private val onRemove: (Int) -> Unit
) : RecyclerView.Adapter<PeerAdapter.PeerViewHolder>() {

    inner class PeerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val peerText: TextView = itemView.findViewById(R.id.peer_text)
        val btnRemove: MaterialButton = itemView.findViewById(R.id.btn_remove)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PeerViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_peer, parent, false)
        return PeerViewHolder(view)
    }

    override fun onBindViewHolder(holder: PeerViewHolder, position: Int) {
        holder.peerText.text = peers[position]
        holder.btnRemove.setOnClickListener {
            onRemove(holder.adapterPosition)
        }
    }

    override fun getItemCount(): Int = peers.size
}
