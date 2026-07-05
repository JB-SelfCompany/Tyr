package com.jbselfcompany.tyr.ui.onboarding

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.viewpager2.widget.ViewPager2
import com.jbselfcompany.tyr.R
import com.jbselfcompany.tyr.TyrApplication
import com.jbselfcompany.tyr.databinding.ActivityOnboardingBinding
import com.jbselfcompany.tyr.service.YggmailService
import com.jbselfcompany.tyr.ui.BaseActivity
import com.jbselfcompany.tyr.ui.MainActivity

/**
 * Onboarding wizard activity with multiple steps:
 * 1. Welcome screen
 * 2. Password setup
 */
class OnboardingActivity : BaseActivity(), OnRestoreCompletedListener {

    private lateinit var binding: ActivityOnboardingBinding
    private lateinit var adapter: OnboardingPagerAdapter
    private val configRepository by lazy { TyrApplication.instance.configRepository }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If already onboarded, go to main activity
        if (configRepository.isOnboardingCompleted()) {
            navigateToMain()
            return
        }

        binding = ActivityOnboardingBinding.inflate(LayoutInflater.from(this))
        setContentView(binding.root)

        setupViewPager()
        setupButtons()
    }

    private fun setupViewPager() {
        adapter = OnboardingPagerAdapter(this)
        binding.viewPager.adapter = adapter
        binding.viewPager.isUserInputEnabled = false // Disable swipe

        // Setup progress indicator
        binding.progressIndicator.max = adapter.itemCount * 100
        updateProgressIndicator(0)

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateButtons(position)
                updateProgressIndicator(position)
            }
        })
    }

    override fun onRestoreCompleted() {
        // After restore, mark onboarding as completed and go to main activity
        completeOnboarding()
    }

    private fun updateProgressIndicator(position: Int) {
        val progress = ((position + 1) * 100)
        binding.progressIndicator.setProgressCompat(progress, true)
    }

    private fun setupButtons() {
        binding.buttonNext.setOnClickListener {
            if (validateCurrentStep()) {
                val currentPage = binding.viewPager.currentItem
                if (currentPage < adapter.itemCount - 1) {
                    binding.viewPager.currentItem = currentPage + 1
                } else {
                    completeOnboarding()
                }
            }
        }

        binding.buttonBack.setOnClickListener {
            val currentPage = binding.viewPager.currentItem
            if (currentPage > 0) {
                binding.viewPager.currentItem = currentPage - 1
            }
        }
    }

    private fun updateButtons(position: Int) {
        binding.buttonBack.isEnabled = position > 0

        when (position) {
            2 -> {
                binding.buttonNext.text = getString(R.string.finish)
            }
            else -> {
                binding.buttonNext.text = getString(R.string.next)
            }
        }
    }

    private fun validateCurrentStep(): Boolean {
        val currentFragment = supportFragmentManager.findFragmentByTag("f${binding.viewPager.currentItem}")

        return when (currentFragment) {
            is OnboardingPasswordFragment -> {
                val password = currentFragment.getPassword()
                val confirmPassword = currentFragment.getConfirmPassword()

                when {
                    password.isEmpty() -> {
                        Toast.makeText(this, R.string.error_password_empty, Toast.LENGTH_SHORT).show()
                        false
                    }
                    password.length < 6 -> {
                        Toast.makeText(this, R.string.error_password_short, Toast.LENGTH_SHORT).show()
                        false
                    }
                    password != confirmPassword -> {
                        Toast.makeText(this, R.string.error_password_mismatch, Toast.LENGTH_SHORT).show()
                        false
                    }
                    else -> {
                        try {
                            configRepository.savePassword(password)
                            true
                        } catch (e: Exception) {
                            Toast.makeText(this, R.string.error_save_password, Toast.LENGTH_LONG).show()
                            false
                        }
                    }
                }
            }
            is OnboardingPeersFragment -> {
                val useDefault = currentFragment.isUsingDefaultPeers()
                val manualPeerUrl = currentFragment.getManualPeerUrl()

                // Nothing selected at all
                if (!useDefault && manualPeerUrl.isNullOrBlank()) {
                    Toast.makeText(this, R.string.error_no_peers_selected, Toast.LENGTH_SHORT).show()
                    return false
                }

                // Validate and save manually entered peer
                if (!manualPeerUrl.isNullOrBlank()) {
                    if (!com.jbselfcompany.tyr.data.PeerInfo.isValidPeerUrl(manualPeerUrl)) {
                        Toast.makeText(this, R.string.error_invalid_peer_url, Toast.LENGTH_SHORT).show()
                        return false
                    }
                    configRepository.savePeer(
                        com.jbselfcompany.tyr.data.PeerInfo(
                            uri = manualPeerUrl,
                            isEnabled = true,
                            tag = com.jbselfcompany.tyr.data.PeerInfo.PeerTag.CUSTOM
                        )
                    )
                }

                // Use default peers when no manual peer was entered
                if (manualPeerUrl.isNullOrBlank()) {
                    com.jbselfcompany.tyr.data.ConfigRepository.DEFAULT_PEERS.forEach { peerUri ->
                        configRepository.savePeer(
                            com.jbselfcompany.tyr.data.PeerInfo(
                                uri = peerUri,
                                isEnabled = true,
                                tag = com.jbselfcompany.tyr.data.PeerInfo.PeerTag.DEFAULT
                            )
                        )
                    }
                    configRepository.setUseDefaultPeers(true)
                }

                true
            }
            else -> true
        }
    }

    private fun completeOnboarding() {
        // Mark onboarding as completed
        configRepository.setOnboardingCompleted(true)

        // Automatically start the Yggmail service after first setup
        if (!YggmailService.isRunning) {
            YggmailService.start(this)
        }

        // Navigate to main activity
        navigateToMain()
    }

    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    override fun onBackPressed() {
        val currentPage = binding.viewPager.currentItem
        if (currentPage > 0) {
            binding.viewPager.currentItem = currentPage - 1
        } else {
            super.onBackPressed()
        }
    }
}
