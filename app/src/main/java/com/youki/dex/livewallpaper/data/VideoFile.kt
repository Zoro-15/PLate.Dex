package com.youki.dex.livewallpaper.data

import android.net.Uri
import java.io.File

/**
 * VideoFile — represents a video that actually exists on disk (name, path, size).
 *
 * Deliberately separate from [WallpaperConfig]: this class holds "facts about
 * the file", while WallpaperConfig holds "settings the user chose". Separating
 * them prevents data duplication and makes it easier to re-scan the folder
 * without touching the saved settings.
 */
data class VideoFile(
    val uri: String,       // content:// or file:// — used as a unique identifier with WallpaperConfig
    val displayName: String,
    val sizeBytes: Long
) {
    val isImage: Boolean
        get() = VideoFileStore.isImage(uri)

    companion object {
        // Resolved issue: dead black screen, no crash, when opening the Editor. this
        // used to be `file.toURI().toString()` — plain java.io.File.toURI(),
        // which for an absolute path like
        // /storage/emulated/0/Download/YoukiDEX_Wallpapers/x.mp4 produces
        // "file:/storage/..." (ONE slash after the scheme, not the RFC-correct
        // "file:///storage/..."). ExoPlayer's MediaItem.fromUri() parses this
        // with android.net.Uri, which can fail to resolve that malformed form
        // into a readable local path on some Android versions/devices — the
        // player then simply never reaches STATE_READY and never fires
        // onPlayerError either, so nothing is drawn and nothing is logged: a
        // dead black screen with zero feedback. Uri.fromFile() is the
        // Android-native way to turn a File into a URI and always emits the
        // correct "file:///..." form that ExoPlayer/MediaMetadataRetriever/
        // MediaStore all reliably understand.
        // FIX #2 (ExoPlayer ERROR_CODE_TIMEOUT on filenames with spaces/
        // special characters, e.g. "itachi and kisame in the rain.mp4"):
        // Uri.fromFile() (FIX #1 above) correctly produces "file:///..." with
        // three slashes, but it does NOT percent-encode the path — a raw
        // space or other reserved character in the file name makes the
        // resulting string an invalid URI per RFC 3986. MediaItem.fromUri()
        // parses it anyway on some devices/OS versions, but the native
        // player can then time out trying to resolve it instead of failing
        // fast, which is exactly the ERROR_CODE_TIMEOUT observed. Building
        // the Uri via Uri.Builder().path(...) instead makes Uri responsible
        // for encoding: it escapes spaces and other reserved characters
        // correctly while leaving normal path separators intact, so the
        // final string is always a well-formed file:// URI regardless of
        // what characters are in the filename.
        fun fromFile(file: File): VideoFile = VideoFile(
            uri = Uri.Builder().scheme("file").path(file.absolutePath).build().toString(),
            displayName = file.nameWithoutExtension,
            sizeBytes = file.length()
        )
    }
}
