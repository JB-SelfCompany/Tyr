package com.jbselfcompany.tyr.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.RecyclerView
import com.jbselfcompany.tyr.R

class SettingsAdapter(
    private val items: List<Item>,
    private val listener: Listener
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_SWITCH = 1
        private const val TYPE_PLAIN = 2
    }

    data class Item(
        val id: Int,
        @StringRes val titleRes: Int,
        @StringRes val descriptionRes: Int = 0,
        val type: ItemType,
        var checked: Boolean = false
    )

    enum class ItemType {
        HEADER,
        SWITCH,
        PLAIN
    }

    interface Listener {
        fun onSwitchToggled(id: Int, isChecked: Boolean)
        fun onItemClicked(id: Int)
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position].type) {
            ItemType.HEADER -> TYPE_HEADER
            ItemType.SWITCH -> TYPE_SWITCH
            ItemType.PLAIN -> TYPE_PLAIN
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> {
                val view = inflater.inflate(R.layout.row_settings_header, parent, false)
                HeaderVH(view)
            }
            TYPE_SWITCH -> {
                val view = inflater.inflate(R.layout.row_settings_switch, parent, false)
                SwitchVH(view)
            }
            else -> {
                val view = inflater.inflate(R.layout.row_settings_plain, parent, false)
                PlainVH(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        when (holder) {
            is HeaderVH -> holder.bind(item)
            is SwitchVH -> holder.bind(item, listener)
            is PlainVH -> holder.bind(item, listener)
        }
    }

    override fun getItemCount(): Int = items.size

    class HeaderVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.headerTitle)

        fun bind(item: Item) {
            title.setText(item.titleRes)
        }
    }

    class SwitchVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.settingsTitle)
        private val description: TextView = itemView.findViewById(R.id.settingsDescription)
        private val switch: SwitchCompat = itemView.findViewById(R.id.settingsSwitch)

        fun bind(item: Item, listener: Listener) {
            title.setText(item.titleRes)
            if (item.descriptionRes != 0) {
                description.setText(item.descriptionRes)
                description.visibility = View.VISIBLE
            } else {
                description.visibility = View.GONE
            }

            // Set switch state without triggering listener
            switch.setOnCheckedChangeListener(null)
            switch.isChecked = item.checked

            // Set listener for switch changes
            switch.setOnCheckedChangeListener { _, isChecked ->
                item.checked = isChecked
                listener.onSwitchToggled(item.id, isChecked)
            }

            // Make entire row clickable to toggle switch
            itemView.setOnClickListener {
                switch.isChecked = !switch.isChecked
            }
        }
    }

    class PlainVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.settingsTitle)
        private val description: TextView = itemView.findViewById(R.id.settingsDescription)

        fun bind(item: Item, listener: Listener) {
            title.setText(item.titleRes)
            if (item.descriptionRes != 0) {
                description.setText(item.descriptionRes)
                description.visibility = View.VISIBLE
            } else {
                description.visibility = View.GONE
            }

            itemView.setOnClickListener {
                listener.onItemClicked(item.id)
            }
        }
    }
}
