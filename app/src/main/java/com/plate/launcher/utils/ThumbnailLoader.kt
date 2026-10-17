package com.plate.launcher.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import android.view.View
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * ThumbnailLoader — loads thumbnail images/videos for RecyclerView items
 * without freezing the UI thread and without spawning a raw new Thread for
 * every item.
 *
 * FIX (Crash — MediaMetadataRetriever.finalize() timed out after 30s):
 *
 * Root cause:
 *   MediaMetadataRetriever.setDataSource() calls native code that opens the
 *   file and reads its headers. If the file is corrupt, on a slow network, or
 *   the URI is complex (content:// from an external provider),
 *   native_finalize() can hang for up to 30 seconds — and the JVM Finalizer
 *   Daemon crashes the app once that timeout elapses.
 *
 *   Using .use {} or try/catch isn't enough: even if close() was called
 *   correctly, the native thread can still remain stuck inside
 *   native_finalize() afterward. withTimeoutOrNull() can't stop a blocking
 *   native call — the coroutine ends but the native thread stays hanging in
 *   the background.
 *
 * The fix:
 *   We run MediaMetadataRetriever inside a separate FutureTask with
 *   .get(timeout) — if the time is exceeded we call future.cancel(true) and
 *   ignore the result. The native thread might stay stuck a little longer,
 *   but away from the Finalizer Daemon that triggers the crash.
 */
object ThumbnailLoader {

    private val executor: ExecutorService = Executors.newFixedThreadPool(4)

    // A separate executor with just a single thread for MediaMetadataRetriever —
    // prevents a buildup of stuck native threads when browsing large folders
    private val mmrExecutor: ExecutorService = Executors.newFixedThreadPool(2)

    private val cache: LruCache<String, Bitmap> =
        object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int = bitmap.byteCount / 1024
        }

    private val imageExts = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
    private val videoExts = setOf("mp4", "mkv", "webm", "mov", "3gp", "avi")

    fun isImage(ext: String) = ext.lowercase() in imageExts
    fun isVideo(ext: String) = ext.lowercase() in videoExts
    fun isPreviewable(ext: String) = isImage(ext) || isVideo(ext)

    fun load(file: File, tagHolder: View, onReady: (Bitmap) -> Unit) {
        val ext = file.extension.lowercase()
        if (!isPreviewable(ext)) return

        val cacheKey = "${file.absolutePath}:${file.lastModified()}"
        cache.get(cacheKey)?.let { onReady(it); return }

        val tag = file.absolutePath
        tagHolder.tag = tag
        executor.execute {
            val bmp = try {
                if (isImage(ext)) {
                    BitmapFactory.decodeFile(
                        file.absolutePath,
                        BitmapFactory.Options().apply { inSampleSize = 4 }
                    )
                } else {
                    // FIX: we run MediaMetadataRetriever inside a FutureTask with a strict
                    // timeout. If native_finalize() hangs, it hangs inside the mmrExecutor
                    // thread, away from the Finalizer Daemon — no crash for the app.
                    extractVideoThumbnailSafely(file.absolutePath)
                }
            } catch (_: Exception) { null }

            if (bmp != null) {
                cache.put(cacheKey, bmp)
                tagHolder.post {
                    if (tagHolder.tag == tag) onReady(bmp)
                }
            }
        }
    }

    /** A public interface to call from WorkshopFragment and others */
    fun loadVideoFrameSync(file: File): Bitmap? = extractVideoThumbnailSafely(file.absolutePath)

    /**
     * Extracts a thumbnail with a strict 6-second timeout.
     * If the time runs out: cancels the Future and returns null — with no crash.
     */
    private fun extractVideoThumbnailSafely(path: String): Bitmap? {
        val future = FutureTask<Bitmap?> {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(path)
                mmr.getFrameAtTime(500_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally {
                // Explicit release() before it's left to the Finalizer
                try { mmr.release() } catch (_: Exception) {}
            }
        }
        mmrExecutor.execute(future)
        return try {
            future.get(6, TimeUnit.SECONDS)
        } catch (_: Exception) {
            future.cancel(true)
            null
        }
    }
}
