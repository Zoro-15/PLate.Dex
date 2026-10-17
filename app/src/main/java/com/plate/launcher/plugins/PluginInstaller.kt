package com.plate.launcher.plugins

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.plate.launcher.R
import com.plate.launcher.utils.RootManager
import com.plate.launcher.utils.ShizukoManager
import kotlinx.coroutines.*
import java.io.File
import java.util.zip.ZipInputStream

/**
 * PluginInstaller — installs one or more mods in parallel.
 *
 * The logic is exactly like Vexiro:
 *  1) Copies the ZIP to a temp folder (because Shell can't read content:// URIs)
 *  2) Runs the "unzip" command via Shizuku/Root (same approach as Vexiro)
 *  3) If there's no shell → uses ZipInputStream as a fallback
 *  4) Runs install.sh after extraction if it exists
 *  5) Sets the permissions on the scripts
 */
object PluginInstaller {

    // ── Installation states ───────────────────────────────────────────────────
    sealed class InstallState {
        object Queued                           : InstallState()
        data class Progress(val step: String, val pct: Int) : InstallState()
        data class Success(val plugin: YoukiPlugin)         : InstallState()
        data class Failed (val reason: String)              : InstallState()
    }

    // ── Install multiple in parallel ─────────────────────────────────────────
    suspend fun installAll(
        context:  Context,
        uris:     List<Uri>,
        onUpdate: (uri: Uri, state: InstallState) -> Unit
    ) = coroutineScope {
        uris.map { uri ->
            async(Dispatchers.IO) {
                installOne(context, uri) { state ->
                    CoroutineScope(Dispatchers.Main).launch { onUpdate(uri, state) }
                }
            }
        }.awaitAll()
    }

    // ── Install one ZIP ──────────────────────────────────────────────────────
    private fun installOne(
        context: Context,
        uri:     Uri,
        onState: (InstallState) -> Unit
    ) {
        onState(InstallState.Progress(context.getString(R.string.install_step_preparing), 5))

        // 1) Get filename
        val displayName = getDisplayName(context, uri) ?: "plugin.zip"
        if (!displayName.lowercase().endsWith(".zip")) {
            onState(InstallState.Failed(context.getString(R.string.install_error_not_zip))); return
        }

        val pluginId = sanitizeId(displayName.substringBeforeLast('.'))
        val destDir  = PluginManager.pluginDir(context, pluginId)
        if (destDir.exists()) destDir.deleteRecursively()
        destDir.mkdirs()

        // 2) Copy ZIP to private cache (shell can't read content:// URIs)
        onState(InstallState.Progress(context.getString(R.string.install_step_copying), 15))
        val cacheZip = File(context.cacheDir, "install_tmp_$pluginId.zip")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                cacheZip.outputStream().use { input.copyTo(it) }
            } ?: throw Exception(context.getString(R.string.install_error_read_failed))
        } catch (e: Exception) {
            destDir.deleteRecursively()
            onState(InstallState.Failed(context.getString(R.string.install_error_copy_failed, e.message))); return
        }

        // 3) Extract — Shell unzip (like Vexiro) or ZipInputStream as a fallback
        onState(InstallState.Progress(context.getString(R.string.install_step_extracting), 30))
        val extracted = extractZip(context, cacheZip, destDir) { step ->
            onState(InstallState.Progress(step, 55))
        }
        cacheZip.delete()  // clean up the cache

        if (!extracted) {
            destDir.deleteRecursively()
            onState(InstallState.Failed(context.getString(R.string.install_error_extract_failed))); return
        }

        // 4) Validate — any single file existing in the folder is enough
        onState(InstallState.Progress(context.getString(R.string.install_step_validating), 70))
        val hasAnyFile = destDir.walkTopDown()
            .filter { it.isFile }
            .any()

        if (!hasAnyFile) {
            destDir.deleteRecursively()
            onState(InstallState.Failed(context.getString(R.string.install_error_empty_archive)))
            return
        }

        // 5) Fix permissions on the scripts
        listOf("service.sh", "disable.sh", "install.sh", "uninstall.sh").forEach {
            File(destDir, it).takeIf { f -> f.exists() }?.setExecutable(true, false)
        }

        // 6) Run install.sh if it exists (like Magisk modules)
        onState(InstallState.Progress(context.getString(R.string.install_step_running_script), 85))
        val installScript = File(destDir, "install.sh")
        if (installScript.exists()) {
            runScript(context, installScript.absolutePath)
        }

        onState(InstallState.Progress(context.getString(R.string.install_step_done), 100))

        // Read the plugin's data — if it fails, create a default plugin named after the folder
        val plugin = PluginManager.readPlugin(context, destDir)
            ?: YoukiPlugin(
                id = pluginId, name = pluginId, description = "",
                version = "1.0", author = "Unknown", path = destDir.absolutePath
            )

        onState(InstallState.Success(plugin))
    }

    // ── Extract ZIP — shell unzip first, ZipInputStream as fallback ──────────
    private fun extractZip(
        context: Context,
        zipFile: File,
        destDir: File,
        onStep:  (String) -> Unit
    ): Boolean {
        val shizuku = ShizukoManager.getInstance(context)
        val root    = RootManager.getInstance(context)

        // Patched: Critical Security — Zip Slip via Root/Shizuku privileges.
        // The original "unzip" command used to run directly with elevated
        // root/Shizuku privileges with no prior check on the ZIP's contents at
        // all. If the file (from an untrusted external source the user
        // downloaded) contains an entry with a path like
        // "../../../data/..." — real unzip honors relative paths and writes
        // outside destDir, and since this executes with root privileges, the
        // risk is far greater than the fallback path (ZipInputStream only runs
        // with the app's regular permissions). Fix: we scan every file name
        // inside the ZIP first (without extracting, with the app's regular
        // permissions) — if any entry tries to escape destDir (via "../" or an
        // absolute path), we refuse to use the privileged (root) method at all
        // and go straight to the safe fallback that checks every path before
        // writing.
        val zipIsSafe = try {
            ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
                val destCanonical = destDir.canonicalPath + File.separator
                var entry = zip.nextEntry
                var safe = true
                while (entry != null) {
                    val candidate = File(destDir, entry.name).canonicalPath
                    if (!candidate.startsWith(destCanonical)) { safe = false; break }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
                safe
            }
        } catch (e: Exception) { false }

        // Method 1: shell unzip (exactly like Vexiro) — only if we've verified the file is safe
        if (zipIsSafe && (root.isAvailable || shizuku.hasPermission)) {
            onStep(context.getString(R.string.install_step_extracting_shell))
            val cmd = "unzip -o '${zipFile.absolutePath}' -d '${destDir.absolutePath}' 2>&1"

            val result: String? = when {
                root.isAvailable      -> root.runShellSync(cmd)
                shizuku.hasPermission -> shizuku.runShellSync(cmd)
                else                  -> null
            }

            if (result != null) {
                val hadError = result.contains("error",       ignoreCase = true)
                            || result.contains("cannot find", ignoreCase = true)
                            || result.contains("No such file",ignoreCase = true)
                if (!hadError && destDir.listFiles()?.isNotEmpty() == true) {
                    onStep(context.getString(R.string.install_step_extracted_shell_ok))
                    return true
                }
            }
        }

        // Method 2: ZipInputStream fallback (if Shell isn't available)
        onStep(context.getString(R.string.install_step_extracting))
        return try {
            var count = 0
            val destCanonicalPath = destDir.canonicalPath + File.separator
            ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val out = File(destDir, entry.name)
                        // Fix for Critical Security — Zip Slip / Path Traversal:
                        // entry.name comes from an untrusted external ZIP file
                        // (the user downloads it from external sources). If it
                        // contained "../../..", File(destDir, entry.name) used
                        // to write completely outside destDir — potentially
                        // reaching the app's internal SharedPreferences files
                        // or other sensitive files inside the app's sandbox.
                        // This is a well-known vulnerability, historically
                        // documented in many unzip tools (Zip Slip). Fix: we
                        // verify that the final path after normalization
                        // (canonicalPath) actually starts with destDir's path
                        // before any write — any entry trying to escape
                        // outside the plugin's designated folder is silently
                        // ignored.
                        if (!out.canonicalPath.startsWith(destCanonicalPath)) {
                            zip.closeEntry()
                            entry = zip.nextEntry
                            continue
                        }
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                        count++
                        if (count % 3 == 0) onStep(context.getString(R.string.install_step_extracted_count, count))
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            count > 0
        } catch (e: Exception) { false }
    }

    // ── Run a shell script ───────────────────────────────────────────────────
    private fun runScript(context: Context, scriptPath: String) {
        val cmd     = "sh '$scriptPath'"
        val root    = RootManager.getInstance(context)
        val shizuku = ShizukoManager.getInstance(context)
        when {
            root.isAvailable      -> root.runShellSync(cmd)
            shizuku.hasPermission -> shizuku.runShellSync(cmd)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────
    private fun getDisplayName(context: Context, uri: Uri): String? =
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                c.moveToFirst()
                if (idx >= 0) c.getString(idx) else null
            }
        } catch (e: Exception) { null }

    private fun sanitizeId(raw: String): String {
        val cleaned = raw.map { c -> if (c.isLetterOrDigit() && c.code < 128 || c == '_' || c == '-') c else '_' }
            .joinToString("")
        val capped = cleaned.take(64)
        return capped.ifEmpty { "plugin" }
    }
}
