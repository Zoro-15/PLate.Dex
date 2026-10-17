package com.plate.launcher.livewallpaper.data

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceManager

object WallpaperPreferences {
    const val PREF_WALLPAPER_PATH = "desktop_wallpaper_path"
    const val PREF_WALLPAPER_TYPE = "desktop_wallpaper_type"
    const val TYPE_IMAGE = "IMAGE"
    const val TYPE_VIDEO = "VIDEO"

    const val ACTION_WALLPAPER_CHANGED = "com.plate.launcher.WALLPAPER_CHANGED"

    fun getPrefs(context: Context): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context)

    fun getActiveWallpaper(context: Context): Pair<String?, String> {
        val sp = getPrefs(context)
        val path = sp.getString(PREF_WALLPAPER_PATH, null)
        val type = sp.getString(PREF_WALLPAPER_TYPE, TYPE_IMAGE) ?: TYPE_IMAGE
        return path to type
    }

    fun setWallpaper(context: Context, path: String, isImage: Boolean) {
        val type = if (isImage) TYPE_IMAGE else TYPE_VIDEO
        getPrefs(context).edit {
            putString(PREF_WALLPAPER_PATH, path)
            putString(PREF_WALLPAPER_TYPE, type)
        }
        context.sendBroadcast(
            Intent(ACTION_WALLPAPER_CHANGED).setPackage(context.packageName)
        )
    }
}
