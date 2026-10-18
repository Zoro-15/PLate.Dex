package com.plate.launcher.fragments

import android.content.Intent
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.plate.launcher.R

/**
 * BetaPreferences — hidden until Developer Mode is unlocked (7 taps on the
 * version number in Help & About; see HelpAboutPreferences.handleVersionTap
 * and PreferencesFragment.refreshBetaVisibility). Houses the
 * customization/debugging tools that used to live in the regular Advanced
 * screen but are meant for people actively testing/tweaking the app rather
 * than everyday users: overriding the bundled loading-screen media, Strict
 * debug mode, and the QA self-test runner.
 */
class BetaPreferences : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_beta, rootKey)
    }
}

