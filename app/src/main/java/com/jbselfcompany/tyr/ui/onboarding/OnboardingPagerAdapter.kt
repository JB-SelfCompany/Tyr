package com.jbselfcompany.tyr.ui.onboarding

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class OnboardingPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = 3

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> OnboardingWelcomeFragment()
            1 -> OnboardingPasswordFragment()
            2 -> OnboardingPeersFragment()
            else -> throw IllegalArgumentException("Invalid position: $position")
        }
    }
}
