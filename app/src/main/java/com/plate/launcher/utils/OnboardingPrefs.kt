package com.plate.launcher.utils

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * Storage for first-run onboarding state (see SplashActivity, OnboardingActivity) — whether
 * onboarding has completed. Uses the default SharedPreferences (same store every other
 * preference in this app uses) rather than a separate file, since these are genuinely just
 * more app preferences, not sensitive or high-volume data that would need its own store.
 */
object OnboardingPrefs {
    /**
     * A user-facing display name the app itself remembers, separate from the OS-level
     * Android user account name (DeviceUtils.getUserName() / UserManager.userName is
     * read-only for a regular app — renaming the actual OS user account requires
     * MANAGE_USERS, a system-level permission this app doesn't and shouldn't have).
     * Falls back to DeviceUtils.getUserName() when nothing's been explicitly set here,
     * so a fresh install still shows something sensible before onboarding ever runs.
     */
    fun getDisplayName(context: Context): String? {
        val stored = PreferenceManager.getDefaultSharedPreferences(context).getString(KEY_DISPLAY_NAME, null)
        return stored ?: DeviceUtils.getUserName(context)
    }

    fun setDisplayName(context: Context, name: String) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(KEY_DISPLAY_NAME, name).apply()
    }

    private const val KEY_DISPLAY_NAME = "app_display_name"
    private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"

    fun isOnboardingComplete(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY_ONBOARDING_COMPLETE, false)

    fun setOnboardingComplete(context: Context, complete: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(KEY_ONBOARDING_COMPLETE, complete).apply()
    }
}
