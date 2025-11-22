package com.jbselfcompany.tyr.ui.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.jbselfcompany.tyr.TyrApplication
import com.jbselfcompany.tyr.data.ConfigRepository
import com.jbselfcompany.tyr.databinding.FragmentOnboardingPeersBinding

/**
 * Peers configuration fragment for onboarding
 */
class OnboardingPeersFragment : Fragment() {

    private var _binding: FragmentOnboardingPeersBinding? = null
    private val binding get() = _binding!!

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

        // Pre-fill with default peers (this fragment is no longer used in onboarding)
        val defaultPeers = ConfigRepository.DEFAULT_PEERS.joinToString("\n")
        binding.editPeers.setText(defaultPeers)

        // Setup multicast checkbox
        binding.checkboxMulticast.setOnCheckedChangeListener { _, isChecked ->
            TyrApplication.instance.configRepository.setMulticastEnabled(isChecked)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /**
     * Get list of peers entered by user
     */
    fun getPeers(): List<String> {
        val peersText = binding.editPeers.text.toString()
        return peersText.split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
}
