package com.jbselfcompany.tyr.ui.onboarding

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

/**
 * ViewPager adapter for onboarding wizard
 */
class OnboardingPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = 2

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> OnboardingWelcomeFragment()
            1 -> OnboardingPasswordFragment()
            else -> throw IllegalArgumentException("Invalid position: $position")
        }
    }
}
