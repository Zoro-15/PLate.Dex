package com.plate.launcher.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File

/**
 * Handles saving/loading the settings header cover photo and the current user's avatar.
 * Avatar storage delegates to MultiUserManager so the same bitmap is used everywhere
 * (settings header, user switcher, onboarding, etc.) without any duplicate copies.
 */
object SettingsHeaderController {

    private const val TAG = "SettingsHeaderController"
    private const val COVER_PHOTO_FILENAME = "settings_cover_photo.jpg"

    // ── Cover photo ─────────────────────────────────────────────────────────

    fun saveCoverPhoto(context: Context, bitmap: Bitmap): Boolean {
        return try {
            val file = getCoverPhotoFile(context)
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "saveCoverPhoto: ${e.message}")
            false
        }
    }

    fun loadCoverPhoto(context: Context): Bitmap? {
        return try {
            val file = getCoverPhotoFile(context)
            if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
        } catch (e: Exception) {
            Log.e(TAG, "loadCoverPhoto: ${e.message}")
            null
        }
    }

    private fun getCoverPhotoFile(context: Context): File =
        File(context.filesDir, COVER_PHOTO_FILENAME)

    // ── Current user avatar (delegates to MultiUserManager) ─────────────────

    /**
     * Loads the current user's *static* avatar only. Kept for callers that
     * specifically need a still [Bitmap] (e.g. as a fallback source before
     * a picker overwrites it). For displaying an avatar in the UI, prefer
     * [com.plate.launcher.utils.AvatarDisplay.showOn] instead — it shows the
     * animated GIF when the user has set one ("بروفايلك جيفت"), which this
     * method alone can't represent.
     */
    fun loadCurrentUserAvatar(context: Context): Bitmap? {
        val userId = MultiUserManager.getCurrentUserId()
        return MultiUserManager.loadUserAvatar(context, userId)
    }
}
