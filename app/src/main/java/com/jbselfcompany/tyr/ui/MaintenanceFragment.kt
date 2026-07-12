package com.jbselfcompany.tyr.ui

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.jbselfcompany.tyr.R

/**
 * Placeholder shown in place of a feature that is temporarily disabled.
 * Currently used for the Chat tab while it is under maintenance.
 */
class MaintenanceFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = TextView(requireContext()).apply {
        text = getString(R.string.chat_under_maintenance)
        gravity = Gravity.CENTER
        textSize = 16f
        val pad = (24 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }
}
