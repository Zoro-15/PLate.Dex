package com.plate.launcher.utils

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.plate.launcher.R
import com.plate.launcher.activities.UnifiedVideoPlayerActivity
import java.io.File

/**
 * VideoUtils — the unified interface for playing video across the whole app.
 *
 * Instead of every screen inventing its own way to play video (VideoView
 * here, a Dialog there, ACTION_VIEW somewhere else), all the code now goes
 * through this one place.
 *
 * Usage:
 *   VideoUtils.play(context, file)
 *   VideoUtils.play(context, uri, "Video title")
 */
object VideoUtils {

    /** Supported video extensions */
    val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "mov", "3gp", "avi", "flv", "ts", "m4v")

    /** Is the file a video? */
    fun isVideo(file: File) = file.extension.lowercase() in VIDEO_EXTENSIONS
    fun isVideo(filename: String) = filename.substringAfterLast('.').lowercase() in VIDEO_EXTENSIONS

    /**
     * Plays a video from a File — converts it to a URI automatically via FileProvider
     *
     * @param context  Any Context (Activity, Fragment.requireContext(), etc.)
     * @param file     The video file
     * @param title    The title shown in the top bar (optional, uses the file name if not specified)
     */
    fun play(context: Context, file: File, title: String? = null) {
        val uri = try {
            FileProvider.getUriForFile(
                context, "${context.packageName}.provider", file)
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, context.getString(R.string.cannot_read_file), android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        play(context, uri, title ?: file.nameWithoutExtension)
    }

    /**
     * Plays a video directly from a URI (if you already have one ready)
     *
     * @param context  Any Context
     * @param uri      The video's URI (content:// or file://)
     * @param title    The title shown in the top bar
     */
    fun play(context: Context, uri: android.net.Uri, title: String = "") {
        context.startActivity(
            Intent(context, UnifiedVideoPlayerActivity::class.java).apply {
                // We pass the URI both as a String and as data together for compatibility with FileProvider
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(UnifiedVideoPlayerActivity.EXTRA_URI, uri.toString())
                putExtra(UnifiedVideoPlayerActivity.EXTRA_TITLE, title)
            }
        )
    }
}
