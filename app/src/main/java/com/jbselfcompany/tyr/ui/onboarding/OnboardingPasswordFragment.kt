package com.jbselfcompany.tyr.ui.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.jbselfcompany.tyr.databinding.FragmentOnboardingPasswordBinding

/**
 * Password setup fragment for onboarding
 */
class OnboardingPasswordFragment : Fragment() {

    private var _binding: FragmentOnboardingPasswordBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOnboardingPasswordBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /**
     * Get password entered by user
     */
    fun getPassword(): String {
        return binding.editPassword.text.toString()
    }

    /**
     * Get confirm password entered by user
     */
    fun getConfirmPassword(): String {
        return binding.editConfirmPassword.text.toString()
    }
}
