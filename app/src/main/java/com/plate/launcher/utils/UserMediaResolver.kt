package com.plate.launcher.utils

import android.content.Context
import android.net.Uri
import androidx.preference.PreferenceManager
import com.plate.launcher.R

/**
 * UserMediaResolver — single source of truth for the "UI customization"
 * media shown on the QA self-test loading screen and EasterEggActivity.
 * The picker itself only lives in the hidden Beta settings screen now (see
 * BetaPreferences.kt — this is a testing/tweaking override, not something
 * onboarding should ask every new user to configure).
 *
 * Three consumers read from here, and only from here:
 *   1. BetaPreferences — writes an override choice, if the person wants one
 *   2. EasterEggActivity — plays it as the swipe-to-exit surprise
 *   3. QaSandboxActivity — shows it on the self-test loading screen
 *
 * Deliberately content-type-tagged rather than "just store a Uri and
 * sniff the mime type on every read": the picker already knows exactly
 * what the user chose (image vs video vs GIF), so storing that decision
 * once here means every consumer branches on a plain enum instead of
 * re-deriving it from a content:// Uri each time (which requires a
 * ContentResolver query, can fail, and duplicates the same sniffing
 * logic three times over).
 *
 * If nothing's been picked/overridden, [get] falls back to the bundled
 * default GIF (res/raw/default_loading_media) rather than returning null —
 * every consumer sees a real Selection either way, so there's no separate
 * "nothing configured" branch to maintain in each of them. [clear] restores
 * that same bundled default rather than leaving the app with no media at
 * all. EasterEggActivity specifically prefers the bundled MP4
 * (default_easter_egg) over the GIF when no override is set, since a real
 * video is a better fit for that full-screen surprise; see its own
 * resolution in EasterEggActivity.
 */
object UserMediaResolver {

    enum class MediaKind { IMAGE, VIDEO, GIF }

    data class Selection(val uri: Uri, val kind: MediaKind)

    private const val KEY_URI = "ui_customization_media_uri"
    private const val KEY_KIND = "ui_customization_media_kind"

    /** The bundled default loading image — used by the QA self-test loading screen and Workshop's own loading spinner when no override is set. */
    fun defaultLoadingMedia(context: Context): Selection =
        Selection(
            Uri.parse("android.resource://${context.packageName}/${R.raw.default_loading_media}"),
            MediaKind.IMAGE
        )

    /** The bundled default video — used by EasterEggActivity when no override is set. */
    fun defaultEasterEggMedia(context: Context): Selection =
        Selection(
            Uri.parse("android.resource://${context.packageName}/${R.raw.default_easter_egg}"),
            MediaKind.VIDEO
        )

    /**
     * Returns the person's override if one is set, otherwise the bundled
     * default loading GIF — never null, so callers that just want "the
     * loading media" (the self-test screen) don't need their own fallback
     * branch. Callers that want a different bundled default when nothing's
     * overridden (EasterEggActivity's video) should check [hasOverride]
     * first and fall back to [defaultEasterEggMedia] themselves instead of
     * using this return value directly.
     */
    fun get(context: Context): Selection {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val uriString = prefs.getString(KEY_URI, null)
        val kindString = prefs.getString(KEY_KIND, null)
        if (uriString != null && kindString != null) {
            try {
                return Selection(Uri.parse(uriString), MediaKind.valueOf(kindString))
            } catch (e: IllegalArgumentException) {
                // Corrupt/old value — fall through to the bundled default below.
            }
        }
        return defaultLoadingMedia(context)
    }

    /** True if the person has explicitly picked their own media (Beta settings), rather than using a bundled default. */
    fun hasOverride(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getString(KEY_URI, null) != null && prefs.getString(KEY_KIND, null) != null
    }

    fun set(context: Context, uri: Uri, kind: MediaKind) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(KEY_URI, uri.toString())
            .putString(KEY_KIND, kind.name)
            .apply()
    }

    /** Removes the person's override, reverting every consumer back to its own bundled default. */
    fun clear(context: Context) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .remove(KEY_URI)
            .remove(KEY_KIND)
            .apply()
    }
}
