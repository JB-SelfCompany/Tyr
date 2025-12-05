package com.jbselfcompany.tyr.ui

import android.view.ContextMenu
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.RecyclerView
import com.jbselfcompany.tyr.R
import com.jbselfcompany.tyr.data.PeerInfo

class PeerAdapter(
    private val peers: MutableList<PeerInfo>,
    private val onEdit: (Int) -> Unit,
    private val onRemove: (Int) -> Unit,
    private val onToggle: (Int, Boolean) -> Unit
) : RecyclerView.Adapter<PeerAdapter.PeerViewHolder>() {

    private var contextMenuPosition: Int = -1

    fun getContextMenuPosition(): Int = contextMenuPosition

    inner class PeerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView),
        View.OnCreateContextMenuListener {
        val peerText: TextView = itemView.findViewById(R.id.peer_text)
        val peerSwitch: SwitchCompat = itemView.findViewById(R.id.peer_switch)
        val peerTypeLabel: TextView = itemView.findViewById(R.id.peer_type_label)

        init {
            itemView.setOnCreateContextMenuListener(this)
        }

        override fun onCreateContextMenu(
            menu: ContextMenu?,
            v: View?,
            menuInfo: ContextMenu.ContextMenuInfo?
        ) {
            contextMenuPosition = adapterPosition
            menu?.apply {
                setHeaderTitle(peerText.text)
                add(0, MENU_EDIT, 0, itemView.context.getString(R.string.edit_peer))
                add(0, MENU_DELETE, 1, itemView.context.getString(R.string.delete_peer))
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PeerViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_peer, parent, false)
        return PeerViewHolder(view)
    }

    override fun onBindViewHolder(holder: PeerViewHolder, position: Int) {
        val peer = peers[position]

        holder.peerText.text = peer.uri
        holder.peerSwitch.isChecked = peer.isEnabled

        // Show tag label for all peers
        holder.peerTypeLabel.visibility = View.VISIBLE
        holder.peerTypeLabel.text = when (peer.tag) {
            PeerInfo.PeerTag.DEFAULT -> holder.itemView.context.getString(R.string.peer_tag_default)
            PeerInfo.PeerTag.MULTICAST -> holder.itemView.context.getString(R.string.peer_tag_multicast)
            PeerInfo.PeerTag.CUSTOM -> holder.itemView.context.getString(R.string.peer_tag_custom)
        }

        // Set up switch listener
        holder.peerSwitch.setOnCheckedChangeListener(null)
        holder.peerSwitch.setOnCheckedChangeListener { _, isChecked ->
            onToggle(holder.adapterPosition, isChecked)
        }

        // Prevent switch from triggering context menu
        holder.peerSwitch.setOnLongClickListener { true }

        // Set up long press listener for context menu
        holder.itemView.setOnLongClickListener {
            false // Return false to allow context menu creation
        }
    }

    override fun getItemCount(): Int = peers.size

    companion object {
        const val MENU_EDIT = 1
        const val MENU_DELETE = 2
    }
}
