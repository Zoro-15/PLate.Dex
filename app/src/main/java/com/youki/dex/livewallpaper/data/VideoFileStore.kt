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
        if (clean.contains('.')) {
            val ext = clean.substringAfterLast('.', "").lowercase().substringBefore('?')
            if (ext in VIDEO_EXTS) return false
            if (ext in IMAGE_EXTS) return true
        }
        val header = readHeader(clean)
        return header != null && isImageHeader(header)
    }

    fun isImage(context: Context?, uriString: String): Boolean {
        if (isImage(uriString)) return true
        val clean = Uri.parse(uriString).path ?: uriString
        if (clean.contains('.')) {
            val ext = clean.substringAfterLast('.', "").lowercase().substringBefore('?')
            if (ext in VIDEO_EXTS) return false
            if (ext in IMAGE_EXTS) return true
        }
        val header = readHeader(uriString, context)
        if (header != null && isVideoHeader(header)) return false
        if (header != null && isImageHeader(header)) return true
        if (context != null) {
            try {
                val mime = context.contentResolver.getType(Uri.parse(uriString))
                if (mime?.startsWith("video/") == true) return false
                if (mime?.startsWith("image/") == true) return true
            } catch (_: Exception) {}
        }
        return false
    }

    private fun readHeader(uriOrPath: String, context: Context? = null): ByteArray? {
        return try {
            val uri = Uri.parse(uriOrPath)
            val stream = if (uri.scheme == "file" || uri.scheme.isNullOrEmpty()) {
                val rawPath = uri.path ?: uriOrPath
                val decodedPath = try { Uri.decode(rawPath) } catch (_: Exception) { rawPath }
                val file = File(decodedPath)
                if (!file.exists() || !file.isFile) {
                    if (context != null && uri.scheme != null) {
                        context.contentResolver.openInputStream(uri)
                    } else null
                } else {
                    file.inputStream()
                }
            } else if (context != null) {
                context.contentResolver.openInputStream(uri) ?: return null
            } else {
                null
            }
            stream?.use { input ->
                val buf = ByteArray(16)
                val read = input.read(buf)
                if (read > 0) buf else null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun isImageHeader(header: ByteArray): Boolean {
        if (header.size < 3) return false
        // JPEG: FF D8 FF
        if ((header[0].toInt() and 0xFF) == 0xFF && (header[1].toInt() and 0xFF) == 0xD8 && (header[2].toInt() and 0xFF) == 0xFF) return true
        // PNG: 89 50 4E 47
        if (header.size >= 4 && (header[0].toInt() and 0xFF) == 0x89 && header[1] == 'P'.code.toByte() && header[2] == 'N'.code.toByte() && header[3] == 'G'.code.toByte()) return true
        // GIF: GIF
        if (header[0] == 'G'.code.toByte() && header[1] == 'I'.code.toByte() && header[2] == 'F'.code.toByte()) return true
        // BMP: BM
        if (header[0] == 'B'.code.toByte() && header[1] == 'M'.code.toByte()) return true
        // WEBP: RIFF....WEBP
        if (header.size >= 12 && header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() && header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte() &&
            header[8] == 'W'.code.toByte() && header[9] == 'E'.code.toByte() && header[10] == 'B'.code.toByte() && header[11] == 'P'.code.toByte()) return true
        return false
    }

    fun isVideoHeader(header: ByteArray): Boolean {
        // MP4: 'ftyp' at offset 4
        if (header.size >= 8 && header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() &&
            header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte()) return true
        // MKV / WebM: 1A 45 DF A3
        if (header.size >= 4 && (header[0].toInt() and 0xFF) == 0x1A &&
            (header[1].toInt() and 0xFF) == 0x45 &&
            (header[2].toInt() and 0xFF) == 0xDF &&
            (header[3].toInt() and 0xFF) == 0xA3) return true
        return false
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

        // Automatically detect and fix any previously imported files missing an extension or misnamed as video
        for (dir in validDirs) {
            repairExtensionlessFiles(dir)
        }

        val allFiles = mutableListOf<File>()
        for (dir in validDirs) {
            val files = dir.listFiles { f ->
                if (!f.isFile) return@listFiles false
                val ext = f.extension.lowercase()
                if (ext in ALL_EXTS) return@listFiles true
                val header = ByteArray(16)
                val read = try { f.inputStream().use { it.read(header) } } catch (_: Exception) { 0 }
                read >= 4 && detectExtensionFromHeader(header) != null
            }
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
     * Inspects extensionless files and restores their extension using file headers.
     * Also recovers any video file that was misnamed as an image.
     */
    private fun repairExtensionlessFiles(dir: File) {
        try {
            val allInDir = dir.listFiles { f -> f.isFile } ?: return
            for (file in allInDir) {
                val ext = file.extension.lowercase()
                val header = ByteArray(16)
                val read = try {
                    file.inputStream().use { it.read(header) }
                } catch (_: Exception) { 0 }
                if (read >= 4) {
                    // Self-healing: if a video was accidentally renamed to an image, restore it to mp4
                    if (ext in IMAGE_EXTS && isVideoHeader(header)) {
                        val newFile = File(dir, "${file.nameWithoutExtension}.mp4")
                        if (!newFile.exists()) {
                            file.renameTo(newFile)
                            Log.d(TAG, "Restored misnamed video ${file.name} -> ${newFile.name}")
                        }
                        continue
                    }

                    if (ext.isBlank()) {
                        val realExt = detectExtensionFromHeader(header)
                        if (realExt != null) {
                            val newFile = File(dir, "${file.name}.$realExt")
                            if (!newFile.exists()) {
                                file.renameTo(newFile)
                                Log.d(TAG, "Restored extension for file ${file.name} -> ${newFile.name}")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error inspecting files in $dir", e)
        }
    }

    fun detectExtensionFromHeader(header: ByteArray): String? {
        if (isVideoHeader(header)) {
            if (header.size >= 8 && header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte()) return "mp4"
            return "mkv"
        }
        if (isImageHeader(header)) {
            if ((header[0].toInt() and 0xFF) == 0xFF && (header[1].toInt() and 0xFF) == 0xD8) return "jpg"
            if (header.size >= 4 && (header[0].toInt() and 0xFF) == 0x89) return "png"
            if (header.size >= 3 && header[0] == 'G'.code.toByte() && header[1] == 'I'.code.toByte() && header[2] == 'F'.code.toByte()) return "gif"
            if (header.size >= 2 && header[0] == 'B'.code.toByte() && header[1] == 'M'.code.toByte()) return "bmp"
            if (header.size >= 12 && header[8] == 'W'.code.toByte() && header[9] == 'E'.code.toByte() && header[10] == 'B'.code.toByte() && header[11] == 'P'.code.toByte()) return "webp"
            return "jpg"
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

        val header = ByteArray(16)
        val read = try {
            resolver.openInputStream(sourceUri)?.use { it.read(header) } ?: 0
        } catch (_: Exception) { 0 }
        val extFromHeader = if (read >= 4) detectExtensionFromHeader(header) else null

        var safeName = sanitizeFilename(suggestedName?.takeIf { it.isNotBlank() }
            ?: "wallpaper_${System.currentTimeMillis()}")

        val currentExt = safeName.substringAfterLast('.', "")
        if (currentExt.isBlank() || currentExt !in ALL_EXTS || (currentExt in VIDEO_EXTS && extFromHeader in IMAGE_EXTS)) {
            val resolvedExt = extFromHeader ?: extFromMime ?: if (mimeType?.startsWith("image/") == true) "jpg" else "mp4"
            val base = if (currentExt in ALL_EXTS) safeName.substringBeforeLast('.') else safeName
            safeName = "$base.$resolvedExt"
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

