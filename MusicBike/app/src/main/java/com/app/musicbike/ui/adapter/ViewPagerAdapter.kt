package com.app.musicbike.ui.adapter

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.app.musicbike.ui.fragments.DevicesFragment // Import the Devices fragment
import com.app.musicbike.ui.fragments.MusicFragment   // Import the new Music fragment
import com.app.musicbike.ui.fragments.SensorsFragment // Import the new Sensors fragment

class ViewPagerAdapter(fragmentManager: FragmentManager, lifecycle: Lifecycle) :
    FragmentStateAdapter(fragmentManager, lifecycle) {

    // Define the number of tabs/fragments
    private val NUM_TABS = 3

    override fun getItemCount(): Int {
        return NUM_TABS
    }

    override fun createFragment(position: Int): Fragment {
        // Return the Fragment corresponding to the position
        return when (position) {
            0 -> MusicFragment() // First tab
            1 -> SensorsFragment() // Second tab
            2 -> DevicesFragment()  // Third tab
            else -> throw IllegalStateException("Invalid position: $position")
        }
    }
}