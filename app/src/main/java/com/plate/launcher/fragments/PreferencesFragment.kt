package com.plate.launcher.fragments

import android.content.Intent
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.plate.launcher.R
import com.plate.launcher.livewallpaper.ui.gallery.GalleryActivity

class PreferencesFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_main, rootKey)
        refreshBetaVisibility()

        // Live Wallpaper Gallery — نفس أنيميشن Pixel-style لبقية صفحات الإعدادات
        findPreference<Preference>("lw_open_gallery")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), GalleryActivity::class.java))
            requireActivity().overridePendingTransition(
                R.anim.fragment_open_enter,
                R.anim.fragment_open_exit
            )
            true
        }
    }

    override fun onResume() {
        super.onResume()
        refreshBetaVisibility()
    }

    private fun refreshBetaVisibility() {
        val dev = PreferenceManager.getDefaultSharedPreferences(requireContext())
            .getBoolean("developer_mode_enabled", false)
        findPreference<Preference>("beta_preview")?.isVisible = dev
    }
}
