package com.youki.dex.livewallpaper.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.File

/**
 * VideoFileStore — reads and imports wallpapers (both live video and static images)
 * from the app and user wallpaper folders:
 *   1) YoukiDEX_Wallpapers/   ← wallpapers the user picked manually
 *   2) YoukiDex/Wallpapers/   ← files downloaded from WorkshopFragment
 *   3) App-specific external / internal storage fallback
 */
object VideoFileStore {

    private const val TAG = "VideoFileStore"

    // The user's wallpapers folder (the original)
    const val FOLDER_NAME = "YoukiDEX_Wallpapers"

    val VIDEO_EXTS = setOf("mp4", "mkv", "webm", "3gp", "mov")
    val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")
    val ALL_EXTS   = VIDEO_EXTS + IMAGE_EXTS

    fun isImage(uriString: String): Boolean {
        val clean = Uri.parse(uriString).path ?: uriString
        val ext = clean.substringAfterLast('.', "").lowercase().substringBefore('?')
        return ext in IMAGE_EXTS
    }

    fun isImage(context: Context, uriString: String): Boolean {
        if (isImage(uriString)) return true
        val clean = Uri.parse(uriString).path ?: uriString
        val ext = clean.substringAfterLast('.', "").lowercase().substringBefore('?')
        if (ext in VIDEO_EXTS) return false
        return try {
            val mime = context.contentResolver.getType(Uri.parse(uriString))
            mime?.startsWith("image/") == true
        } catch (_: Exception) {
            false
        }
    }

    /** Strips characters unsafe for filesystem filenames and caps the length. */
    private fun sanitizeFilename(name: String): String {
        val cleaned = name.trim().replace(Regex("""[/\\:*?"<>|\x00-\x1F]"""), "_")
        return cleaned.take(200).ifBlank { "wallpaper_${System.currentTimeMillis()}" }
    }

    // The downloads folder from WorkshopFragment
    private const val DOWNLOADS_ROOT       = "YoukiDex"
    private const val DOWNLOADS_WALLPAPERS = "Wallpapers"

    /** The user's wallpapers folder with storage fallback if public downloads is restricted */
    fun folder(context: Context? = null): File {
        val publicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            FOLDER_NAME
        )
        if (context == null) {
            if (!publicDir.exists()) publicDir.mkdirs()
            return publicDir
        }

        try {
            if ((publicDir.exists() || publicDir.mkdirs()) && isDirWritable(publicDir)) {
                return publicDir
            }
        } catch (_: Exception) {}

        // Fallback 1: app-specific external storage
        val extAppDir = context.getExternalFilesDir(FOLDER_NAME)
        if (extAppDir != null && (extAppDir.exists() || extAppDir.mkdirs()) && isDirWritable(extAppDir)) {
            return extAppDir
        }

        // Fallback 2: internal storage
        val internalDir = File(context.filesDir, FOLDER_NAME)
        if (!internalDir.exists()) internalDir.mkdirs()
        return internalDir
    }

    private fun isDirWritable(dir: File): Boolean {
        return try {
            if (!dir.exists() && !dir.mkdirs()) return false
            val testFile = File(dir, ".test_${System.currentTimeMillis()}")
            if (testFile.createNewFile()) {
                testFile.delete()
                true
            } else {
                dir.canWrite()
            }
        } catch (_: Exception) {
            false
        }
    }

    /** The WorkshopFragment downloads folder */
    private fun downloadsFolder(): File =
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "$DOWNLOADS_ROOT/$DOWNLOADS_WALLPAPERS"
        )

    /** Scans wallpapers across public, workshop, and app-specific directories */
    fun listVideos(context: Context? = null): List<VideoFile> {
        val searchDirs = mutableListOf<File>()
        context?.let { searchDirs.add(folder(it)) } ?: searchDirs.add(folder())
        searchDirs.add(downloadsFolder())
        context?.getExternalFilesDir(FOLDER_NAME)?.let { searchDirs.add(it) }
        context?.let { searchDirs.add(File(it.filesDir, FOLDER_NAME)) }

        val validDirs = searchDirs.filter { it.exists() && it.isDirectory }.distinctBy { it.absolutePath }

        // Automatically detect and fix any previously imported files missing an extension
        for (dir in validDirs) {
            repairExtensionlessFiles(dir)
        }

        val allFiles = mutableListOf<File>()
        for (dir in validDirs) {
            val files = dir.listFiles { f -> f.isFile && f.extension.lowercase() in ALL_EXTS }
            if (files != null) {
                allFiles.addAll(files)
            }
        }

        return allFiles
            .distinctBy { it.name }
            .sortedByDescending { it.lastModified() }
            .map { VideoFile.fromFile(it) }
    }

    /**
     * Inspects files without an extension and restores their extension using file headers.
     * Fixes files previously imported without an extension.
     */
    private fun repairExtensionlessFiles(dir: File) {
        try {
            val extensionless = dir.listFiles { f -> f.isFile && f.extension.isBlank() } ?: return
            for (file in extensionless) {
                val header = ByteArray(16)
                val read = file.inputStream().use { it.read(header) }
                if (read >= 4) {
                    val ext = detectExtensionFromHeader(header)
                    if (ext != null) {
                        val newFile = File(dir, "${file.name}.$ext")
                        if (!newFile.exists()) {
                            file.renameTo(newFile)
                            Log.d(TAG, "Restored extension for file ${file.name} -> ${newFile.name}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error inspecting extensionless files in $dir", e)
        }
    }

    private fun detectExtensionFromHeader(header: ByteArray): String? {
        // MP4: 'ftyp' at offset 4
        if (header.size >= 8 && header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() &&
            header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte()) {
            return "mp4"
        }
        // MKV / WebM
        if (header.size >= 4 && (header[0].toInt() and 0xFF) == 0x1A &&
            (header[1].toInt() and 0xFF) == 0x45 &&
            (header[2].toInt() and 0xFF) == 0xDF &&
            (header[3].toInt() and 0xFF) == 0xA3) {
            return "mkv"
        }
        // JPEG: FF D8 FF
        if (header.size >= 3 && (header[0].toInt() and 0xFF) == 0xFF &&
            (header[1].toInt() and 0xFF) == 0xD8 &&
            (header[2].toInt() and 0xFF) == 0xFF) {
            return "jpg"
        }
        // PNG: 89 50 4E 47
        if (header.size >= 4 && (header[0].toInt() and 0xFF) == 0x89 &&
            header[1] == 'P'.code.toByte() && header[2] == 'N'.code.toByte() && header[3] == 'G'.code.toByte()) {
            return "png"
        }
        // GIF: GIF
        if (header.size >= 3 && header[0] == 'G'.code.toByte() && header[1] == 'I'.code.toByte() && header[2] == 'F'.code.toByte()) {
            return "gif"
        }
        // WEBP: RIFF....WEBP
        if (header.size >= 12 && header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() &&
            header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte() &&
            header[8] == 'W'.code.toByte() && header[9] == 'E'.code.toByte() &&
            header[10] == 'B'.code.toByte() && header[11] == 'P'.code.toByte()) {
            return "webp"
        }
        return null
    }

    /**
     * Copies a wallpaper (video or image) picked via SAF into the wallpapers folder.
     * Uses resilient target directory fallbacks (public downloads -> app external -> app internal).
     */
    fun importVideo(context: Context, sourceUri: Uri, suggestedName: String?): VideoFile? {
        val resolver: ContentResolver = context.contentResolver
        val mimeType = resolver.getType(sourceUri)
        val extFromMime = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)

        var safeName = sanitizeFilename(suggestedName?.takeIf { it.isNotBlank() }
            ?: "wallpaper_${System.currentTimeMillis()}")

        // Ensure the filename has an appropriate extension
        val currentExt = safeName.substringAfterLast('.', "")
        if (currentExt.isBlank() || currentExt !in ALL_EXTS) {
            val resolvedExt = extFromMime ?: if (mimeType?.startsWith("image/") == true) "jpg" else "mp4"
            safeName = "$safeName.$resolvedExt"
        }

        val candidateDirs = mutableListOf<File>()
        candidateDirs.add(folder(context))
        context.getExternalFilesDir(FOLDER_NAME)?.let { candidateDirs.add(it) }
        candidateDirs.add(File(context.filesDir, FOLDER_NAME))

        val distinctDirs = candidateDirs.distinctBy { it.absolutePath }
        for (targetDir in distinctDirs) {
            try {
                if (!targetDir.exists() && !targetDir.mkdirs()) continue
                var destFile = File(targetDir, safeName)

                // Resolve name collision by suffixing counter
                val baseName = destFile.nameWithoutExtension
                val ext = if (destFile.extension.isNotBlank()) ".${destFile.extension}" else ""
                var counter = 1
                while (destFile.exists()) {
                    destFile = File(targetDir, "${baseName}_$counter$ext")
                    counter++
                }

                val copiedBytes = resolver.openInputStream(sourceUri)?.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output, bufferSize = 64 * 1024)
                    }
                } ?: -1L

                if (copiedBytes > 0 && destFile.exists() && destFile.length() > 0) {
                    Log.d(TAG, "Successfully imported wallpaper: ${destFile.absolutePath} (${destFile.length()} bytes)")
                    return VideoFile.fromFile(destFile)
                } else {
                    destFile.delete()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Write attempt failed in $targetDir for $sourceUri: ${e.message}")
            }
        }
        Log.e(TAG, "All target directories failed to import wallpaper from $sourceUri")
        return null
    }

    /**
     * Adds a media file that already exists on disk (without copying) — used
     * when the user picks a file from within the app's own folders via InternalFilePicker.
     */
    fun addExistingFile(file: File, displayName: String): VideoFile? {
        return try {
            if (!file.exists() || !file.isFile) return null
            VideoFile.fromFile(file)
        } catch (e: Exception) {
            null
        }
    }

    fun delete(videoUri: String): Boolean {
        return try {
            File(Uri.parse(videoUri).path ?: return false).delete()
        } catch (e: Exception) {
            false
        }
    }
}

