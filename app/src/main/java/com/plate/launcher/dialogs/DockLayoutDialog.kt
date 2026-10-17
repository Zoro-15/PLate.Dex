package com.plate.launcher.dialogs

import android.content.Context
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.plate.launcher.R

class DockLayoutDialog(context: Context) : MaterialAlertDialogBuilder(context) {
    init {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val editor = sharedPreferences.edit()
        setTitle(R.string.choose_dock_layout)
        val layout = sharedPreferences.getInt("dock_layout", -1)
        setSingleChoiceItems(R.array.layouts, layout) { _, which ->
            // Dock layout presets: 0 = minimal, 1 = default, 2 = "swipe" (gesture) profile.
            val enableNav          = which != 0
            val enableQs           = which != 0
            val appMenuFullscreen  = which != 2
            val showNotifications  = which != 0
            val enableQsPin        = which != 2
            val maxRunningApps          = when (which) { 0 -> "4"; 1 -> "10"; else -> "15" }
            val maxRunningAppsLandscape = when (which) { 0 -> "8"; 1 -> "10"; else -> "15" }
            val dockActivationArea = if (which == 2) "5" else "25"
            // Swipe mode removed (left a small ghost-touch window after hiding
            // the dock) — every layout preset, including the gesture profile,
            // now uses handle mode.
            val activationMethod   = "handle"

            editor.putBoolean("enable_nav_back", enableNav)
            editor.putBoolean("enable_nav_home", enableNav)
            editor.putBoolean("enable_nav_recents", enableNav)
            editor.putBoolean("enable_qs_wifi", enableQs)
            editor.putBoolean("enable_qs_vol", enableQs)
            editor.putBoolean("enable_qs_date", enableQs)
            editor.putBoolean("enable_qs_notif", enableQs)
            // app_menu_fullscreen: controls the MENU layout only (fullscreen grid vs floating panel)
            // NOT whether apps launch windowed — all profiles default to windowed now
            editor.putBoolean("app_menu_fullscreen", appMenuFullscreen)
            // All profiles default to windowed (freeform) — user can change per-app via long press
            editor.putString("launch_mode", "standard")
            // Games: windowed by default in all profiles
            editor.putBoolean("launch_games_fullscreen", false)
            editor.putString("max_running_apps", maxRunningApps)
            editor.putString("max_running_apps_landscape", maxRunningAppsLandscape)
            editor.putString("dock_activation_area", dockActivationArea)
            editor.putInt("dock_layout", which)
            editor.putString("activation_method", activationMethod)
            editor.putBoolean("show_notifications", showNotifications)
            editor.putBoolean("enable_qs_pin", enableQsPin)
            editor.apply()
        }
        setPositiveButton(R.string.ok, null)
        show()
    }
}
