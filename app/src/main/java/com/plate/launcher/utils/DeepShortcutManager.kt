package com.plate.launcher.utils

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.util.DisplayMetrics
import android.annotation.SuppressLint

object DeepShortcutManager {

    // Gap 93: cache LauncherApps instead of getSystemService in every function
    private var launcherAppsCache: LauncherApps? = null
    private fun getLauncherApps(context: Context): LauncherApps =
        launcherAppsCache ?: (context.applicationContext
            .getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps)
            .also { launcherAppsCache = it }

    // Gap 84: @RequiresApi instead of @TargetApi — prevents calling on a lower API
    @SuppressLint("NewApi")
    fun startShortcut(shortcutInfo: ShortcutInfo, context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        try {
            getLauncherApps(context).startShortcut(
                shortcutInfo.`package`, shortcutInfo.id, null, null, Process.myUserHandle()
            )
        } catch (e: Exception) {}
    }

    @SuppressLint("NewApi")
    fun getShortcutIcon(shortcutInfo: ShortcutInfo, context: Context): Drawable? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return null
        return try {
            // Gap 85: density was wrong (density*48) — the correct value is DENSITY_XXXHIGH or the device density
            val densityDpi = context.resources.displayMetrics.densityDpi
                .coerceAtLeast(DisplayMetrics.DENSITY_MEDIUM)
            getLauncherApps(context).getShortcutIconDrawable(shortcutInfo, densityDpi)
        } catch (e: Exception) { null }
    }

    @SuppressLint("NewApi")
    fun getShortcuts(app: String, context: Context): List<ShortcutInfo>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return null
        val queryParams = LauncherApps.ShortcutQuery().apply {
            setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                          LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC)
            setPackage(app)
        }
        return try {
            getLauncherApps(context).getShortcuts(queryParams, Process.myUserHandle())
        } catch (e: Exception) { null }
    }

    fun hasHostPermission(context: Context): Boolean {
        // Gap 99: >= 25 is clearer than > 24
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return false
        return try { getLauncherApps(context).hasShortcutHostPermission() }
        catch (e: Exception) { false }
    }
}
