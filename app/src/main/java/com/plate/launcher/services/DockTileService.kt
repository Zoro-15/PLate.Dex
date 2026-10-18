package com.plate.launcher.services

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.preference.PreferenceManager
import com.plate.launcher.activities.LauncherActivity
import com.plate.launcher.utils.AppUtils
import com.plate.launcher.utils.DeviceUtils

/**
 * DockTileService — Quick Settings Tile for toggling PLate DEX desktop mode.
 */
class DockTileService : TileService() {

    override fun onTileAdded() {
        super.onTileAdded()
        resetAndUpdateTile()
    }

    override fun onStartListening() {
        super.onStartListening()
        resetAndUpdateTile()
    }

    private fun resetAndUpdateTile() {
        val isServiceRunning = DeviceUtils.isAccessibilityServiceEnabled(applicationContext)
        if (!isServiceRunning) {
            PreferenceManager.getDefaultSharedPreferences(applicationContext)
                .edit().putBoolean("dex_mode_active", false).apply()
        }
        updateTile()
    }

    // Returns true if NotificationListenerService is granted access
    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(
            applicationContext.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        val cn = ComponentName(applicationContext, NotificationService::class.java)
        return flat.split(":").any {
            try { ComponentName.unflattenFromString(it) == cn } catch (e: Exception) { false }
        }
    }

    override fun onClick() {
        super.onClick()
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val isAccessibilityOn = DeviceUtils.isAccessibilityServiceEnabled(applicationContext)
        val isNotificationOn = isNotificationListenerEnabled()
        val dexActive = prefs.getBoolean("dex_mode_active", false)

        if (!dexActive) {
            // Check notification listener first — show dialog if missing
            if (!isNotificationOn) {
                showDialog(
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Notification access required")
                        .setMessage("PLate.Dex needs notification access to work correctly. You will be redirected to enable it.")
                        .setPositiveButton("OK") { _, _ ->
                            launchActivity(
                                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                        .setNegativeButton("Skip", null)
                        .create()
                )
                return
            }

            if (!isAccessibilityOn) {
                val hasPermission = DeviceUtils.hasWriteSettingsPermission(applicationContext)
                if (hasPermission) {
                    // Enable the accessibility service (exploit), then open the launcher
                    // immediately — no waiting. LauncherActivity listens for
                    // DOCK_SERVICE_CONNECTED and reacts the instant the service is up,
                    // even if that's a fraction of a second after launch.
                    DeviceUtils.enableService(applicationContext)
                    launchActivity(
                        Intent(applicationContext, LauncherActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    )
                } else {
                    // No permission — show dialog before redirecting
                    showDialog(
                        android.app.AlertDialog.Builder(this)
                            .setTitle("Accessibility service required")
                            .setMessage("PLate.Dex needs the Accessibility service to work. You will be redirected to Settings.")
                            .setPositiveButton("OK") { _, _ ->
                                launchActivity(
                                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                            .setNegativeButton("Cancel", null)
                            .create()
                    )
                }
            } else {
                // Both permissions OK — launch normally
                launchActivity(
                    Intent(applicationContext, LauncherActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                )
            }
        } else {
            AppUtils.stopDexAndLaunchOtherHome(this@DockTileService) { intent -> launchActivity(intent) }
        }
        updateTile()
    }

    private fun launchActivity(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                applicationContext, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val dexActive = PreferenceManager
            .getDefaultSharedPreferences(applicationContext)
            .getBoolean("dex_mode_active", false)
        tile.state = if (dexActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (dexActive) "PLate DEX " else "PLate DEX"
        tile.updateTile()
    }
}
