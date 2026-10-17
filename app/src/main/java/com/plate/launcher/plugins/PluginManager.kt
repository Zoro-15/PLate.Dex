package com.plate.launcher.plugins

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject
import java.io.File

// ── Plugin data model ─────────────────────────────────────────────────────────
data class YoukiPlugin(
    val id:           String,
    val name:         String,
    val description:  String,
    val version:      String,
    val author:       String,
    val requiresRoot: Boolean = false,
    val isActive:     Boolean = false,
    val path:         String,         // absolute path to plugin folder
    val iconPath:     String? = null  // optional icon.png
) {
    val serviceScript  get() = File(path, "service.sh")
    val disableScript  get() = File(path, "disable.sh")
    val hasIcon        get() = iconPath != null && File(iconPath).exists()
}

// ── Plugin Manager ────────────────────────────────────────────────────────────
object PluginManager {

    private const val PLUGINS_DIR    = "youki_plugins"
    private const val PREFS_NAME     = "installed_plugins"
    private const val KEY_ACTIVE     = "active"

    // ── Directories ───────────────────────────────────────────────────────────
    fun pluginsRoot(context: Context) =
        File(context.filesDir, PLUGINS_DIR).also { it.mkdirs() }

    fun pluginDir(context: Context, id: String) =
        File(pluginsRoot(context), id)

    // ── List installed plugins ─────────────────────────────────────────────────
    fun listPlugins(context: Context): List<YoukiPlugin> {
        return try {
            val root = pluginsRoot(context)
            root.listFiles()
                ?.filter { it.isDirectory }
                ?.mapNotNull { dir ->
                    try { readPlugin(context, dir) } catch (e: Exception) { null }
                }
                ?.sortedBy { it.name }
                ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    // ── Read plugin from folder ───────────────────────────────────────────────
    fun readPlugin(context: Context, dir: File): YoukiPlugin? {
        if (!dir.exists() || !dir.isDirectory) return null
        val id = dir.name
        return try {
            val meta     = try { readMeta(dir) } catch (e: Exception) { emptyMap() }
            val isActive = try { isActive(context, id) } catch (e: Exception) { false }
            YoukiPlugin(
                id          = id,
                name        = meta["name"]        ?: id,
                description = meta["description"] ?: "",
                version     = meta["version"]     ?: "1.0",
                author      = meta["author"]      ?: "Unknown",
                requiresRoot= meta["requiresRoot"] == "true",
                isActive    = isActive,
                path        = dir.absolutePath,
                iconPath    = try { File(dir, "icon.png").takeIf { it.exists() }?.absolutePath } catch (e: Exception) { null }
            )
        } catch (e: Exception) {
            // Even if everything else fails, create a plugin named after the folder
            try {
                YoukiPlugin(
                    id = id, name = id, description = "", version = "1.0",
                    author = "Unknown", path = dir.absolutePath
                )
            } catch (e: Exception) { null }
        }
    }

    private fun readMeta(dir: File): Map<String, String> {
        // 1) plugin.json
        val jsonFile = File(dir, "plugin.json")
        if (jsonFile.exists()) {
            try {
                val obj = org.json.JSONObject(jsonFile.readText())
                return mapOf(
                    "name"         to (obj.optString("name").takeIf { it.isNotEmpty() } ?: dir.name),
                    "description"  to obj.optString("description"),
                    "version"      to obj.optString("version", "1.0"),
                    "author"       to obj.optString("author", "Unknown"),
                    "requiresRoot" to obj.optString("requiresRoot", "false")
                )
            } catch (e: Exception) {}
        }

        // 2) module.prop (Magisk format)
        val moduleProp = File(dir, "module.prop")
        if (moduleProp.exists()) {
            try {
                val props = moduleProp.readLines()
                    .filter { it.contains("=") }
                    .mapNotNull { parseModulePropLine(it) }
                    .associate { it.first to it.second }
                return mapOf(
                    "name"         to (props["name"]        ?: dir.name),
                    "description"  to (props["description"] ?: ""),
                    "version"      to (props["version"]     ?: props["versionCode"] ?: "1.0"),
                    "author"       to (props["author"]      ?: "Unknown"),
                    "requiresRoot" to "true"   // Magisk mods always require root
                )
            } catch (e: Exception) {}
        }

        // 3) common/*.prop (Vexiro format)
        val common = File(dir, "common")
        if (common.exists()) {
            return mapOf(
                "name"         to common.resolve("name.prop").readSafe().ifEmpty { dir.name },
                "description"  to common.resolve("description.prop").readSafe(),
                "version"      to common.resolve("version.prop").readSafe().ifEmpty { "1.0" },
                "author"       to common.resolve("author.prop").readSafe().ifEmpty { "Unknown" },
                "requiresRoot" to common.resolve("requires_root.prop").readSafe()
            )
        }

        // 4) No meta exists — use the folder name
        return mapOf(
            "name"         to dir.name,
            "description"  to "",
            "version"      to "1.0",
            "author"       to "Unknown",
            "requiresRoot" to "false"
        )
    }

    private fun parseModulePropLine(line: String): Pair<String, String>? {
        val idx = line.indexOf('=')
        if (idx < 0) return null
        return line.substring(0, idx).trim() to line.substring(idx + 1).trim()
    }

    private fun File.readSafe() = try { readText().trim() } catch (e: Exception) { "" }

    // ── Active state ──────────────────────────────────────────────────────────
    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isActive(context: Context, id: String) =
        prefs(context).getBoolean("${id}_$KEY_ACTIVE", false)

    fun setActive(context: Context, id: String, active: Boolean) =
        prefs(context).edit { putBoolean("${id}_$KEY_ACTIVE", active) }

    // ── Uninstall ─────────────────────────────────────────────────────────────
    fun uninstall(context: Context, plugin: YoukiPlugin) {
        try { prefs(context).edit { remove("${plugin.id}_$KEY_ACTIVE") } } catch (e: Exception) {}
        try { File(plugin.path).deleteRecursively() } catch (e: Exception) {}
    }

    // ── Build shell commands ──────────────────────────────────────────────────
    // null  = an "always active" plugin with no script, needs no command
    // String = run this command

    fun buildActivateCmd(plugin: YoukiPlugin): String? {
        return try {
            when {
                plugin.serviceScript.exists()                      -> "sh '${plugin.serviceScript.absolutePath}'"
                File(plugin.path, "customize.sh").exists()         -> "sh '${File(plugin.path, "customize.sh").absolutePath}'"
                File(plugin.path, "post-fs-data.sh").exists()      -> "sh '${File(plugin.path, "post-fs-data.sh").absolutePath}'"
                else -> null   // no script → always active, needs no command
            }
        } catch (e: Exception) { null }
    }

    fun buildDeactivateCmd(plugin: YoukiPlugin): String? {
        return try {
            when {
                plugin.disableScript.exists()               -> "sh '${plugin.disableScript.absolutePath}'"
                File(plugin.path, "remove.sh").exists()     -> "sh '${File(plugin.path, "remove.sh").absolutePath}'"
                else -> null   // no stop script → just save the state
            }
        } catch (e: Exception) { null }
    }
}
