package com.jbselfcompany.tyr.ui.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.jbselfcompany.tyr.databinding.FragmentOnboardingPeersBinding

/**
 * Peers configuration fragment for onboarding (default peers toggle + manual entry).
 * LAN peer discovery was removed with the Rust yggmail-ng library (no native support).
 */
class OnboardingPeersFragment : Fragment() {

    private var _binding: FragmentOnboardingPeersBinding? = null
    private val binding get() = _binding!!

    private var useDefaultPeers = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOnboardingPeersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.switchUseDefault.setOnCheckedChangeListener { _, isChecked ->
            useDefaultPeers = isChecked
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /** Whether the user chose to use default peers. */
    fun isUsingDefaultPeers(): Boolean = useDefaultPeers

    /** Manually entered peer URL, or null if empty. */
    fun getManualPeerUrl(): String? {
        val url = binding.editManualPeer.text?.toString()?.trim()
        return if (url.isNullOrBlank()) null else url
    }

    /** A valid selection is either the default-peers toggle or a manually entered peer. */
    fun hasValidSelection(): Boolean = useDefaultPeers || !getManualPeerUrl().isNullOrBlank()
}
