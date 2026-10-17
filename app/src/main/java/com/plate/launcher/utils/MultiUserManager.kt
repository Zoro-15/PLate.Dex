package com.plate.launcher.utils

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.plate.launcher.R
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import java.io.File

/**
 * MultiUserManager — multi-user management
 *
 * All functions here are suspend — called from lifecycleScope in the Fragment.
 * This guarantees:
 *  - no race conditions (every job is cancelled before a new one starts)
 *  - no nested callbacks
 *  - no floating Handler.postDelayed
 */
@SuppressLint("MissingPermission")
object MultiUserManager {

    private const val TAG = "PLateMultiUser"

    data class YoukiUser(
        val id: Int,
        val name: String,
        val isCurrentUser: Boolean,
        val isRunning: Boolean,
        val avatarPath: String? = null
    )

    // ─────────────────────────────────────────────────────────────
    //  Privilege
    // ─────────────────────────────────────────────────────────────

    fun hasPrivilege(context: Context): Boolean {
        val shizuku = ShizukoManager.getInstance(context)
        val root    = RootManager.getInstance(context)
        return shizuku.hasPermission || root.isAvailable
    }

    // ─────────────────────────────────────────────────────────────
    //  Reading users — suspend
    // ─────────────────────────────────────────────────────────────

    suspend fun listUsers(context: Context): List<YoukiUser> {
        val shizuku = ShizukoManager.getInstance(context)
        val root    = RootManager.getInstance(context)
        val hasPriv = shizuku.hasPermission || root.isAvailable

        // A single attempt: pm list users → parseUsers (empty if the raw output is empty/unparsable)
        suspend fun tryOnce(): List<YoukiUser> = suspendCancellableCoroutine { cont ->
            fun deliver(raw: String) {
                if (!cont.isActive) return
                val parsed = if (raw.isNotBlank()) parseUsers(context, raw) else emptyList()
                cont.resume(parsed) {}
            }
            when {
                shizuku.hasPermission -> shizuku.runShell("pm list users") { deliver(it) }
                root.isAvailable      -> root.runShell("pm list users")    { deliver(it) }
                else -> if (cont.isActive) cont.resume(emptyList()) {}
            }
        }

        // FIX: removed the fake "current user" fallback.
        // FIX: the first call to Shizuku.newProcess() right after the binder
        // connects can fail/return empty (a race condition in the binder's
        // timing) — even if hasPermission=true.
        // Fix: retry (up to 3 times) with increasing delay before treating it
        // as a genuine EmptyResult.
        var result = tryOnce()
        var attempt = 0
        while (result.isEmpty() && hasPriv && attempt < 2) {
            delay(300L * (attempt + 1)) // 300ms then 600ms
            result = tryOnce()
            attempt++
        }

        return result.ifEmpty { listUsersViaApi(context) }
    }

    private fun parseUsers(context: Context, raw: String): List<YoukiUser> {
        val currentId = getCurrentUserId()
        val result    = mutableListOf<YoukiUser>()
        // Format: UserInfo{10:John:230} running  — or  UserInfo{10:Work:Profile:404010}
        // FIX: (.*?) non-greedy instead of [^:}]+ — supports names containing ":"
        // since the flags at the end of the brace never contain ":", so
        // backtracking naturally stops at the last ":" before "}".
        val regex = Regex("""UserInfo\{(\d+):(.*?):[^:{}]*\}(\s*running)?""")
        regex.findAll(raw).forEach { m ->
            val id      = m.groupValues[1].toIntOrNull() ?: return@forEach
            val name    = m.groupValues[2].trim()
            val running = m.groupValues[3].isNotBlank()
            result += YoukiUser(id, name, id == currentId, running, getUserAvatarPath(context, id))
        }
        return result.sortedBy { it.id }
    }

    @SuppressLint("NewApi")
    private fun listUsersViaApi(context: Context): List<YoukiUser> {
        return try {
            val um      = context.getSystemService(Context.USER_SERVICE) as android.os.UserManager
            val current = getCurrentUserId()
            @Suppress("UNCHECKED_CAST")
            val users   = um.javaClass
                .getMethod("getUsers", Boolean::class.java)
                .invoke(um, true) as List<*>
            users.mapNotNull { info ->
                val cls  = info?.javaClass ?: return@mapNotNull null
                val id   = cls.getField("id").getInt(info)
                val name = cls.getField("name").get(info) as? String ?: context.getString(R.string.mu_default_user_name, id)
                YoukiUser(id, name, id == current, false, getUserAvatarPath(context, id))
            }.sortedBy { it.id }
        } catch (e: Exception) {
            Log.e(TAG, "listUsersViaApi: ${e.message}")
            emptyList()
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Diagnostics — a full report on why reading users failed
    //  Called only from the "Diagnostics" button in the UI on UiState.EmptyResult
    // ─────────────────────────────────────────────────────────────

    fun diagnoseUserListing(context: Context): String = buildString {
        val shizuku = ShizukoManager.getInstance(context)
        val root    = RootManager.getInstance(context)

        appendLine(context.getString(R.string.mu_diag_status))
        appendLine(context.getString(R.string.mu_diag_shizuku, shizuku.isAvailable, shizuku.hasPermission))
        appendLine(context.getString(R.string.mu_diag_root, root.isAvailable))
        appendLine(context.getString(R.string.mu_diag_current_user, getCurrentUserId()))
        appendLine()
        appendLine(context.getString(R.string.mu_diag_pm_list_header))
        when {
            shizuku.hasPermission -> appendLine(shizuku.diagnose("pm list users"))
            root.isAvailable      -> appendLine(root.diagnose("pm list users"))
            else                  -> appendLine(context.getString(R.string.mu_diag_no_privilege))
        }
        appendLine()
        appendLine(context.getString(R.string.mu_diag_api_header))
        val api = listUsersViaApi(context)
        if (api.isEmpty()) {
            appendLine(context.getString(R.string.mu_diag_api_empty, TAG))
        } else {
            api.forEach { appendLine("  id=${it.id}  name=\"${it.name}\"  current=${it.isCurrentUser}") }
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Creating a user — suspend
    // ─────────────────────────────────────────────────────────────

    suspend fun createUser(
        context: Context,
        name: String
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val safeName = name.sanitize()
        val raw = runPrivileged(context, "pm create-user \"$safeName\"")
        val id = extractCreatedUserId(raw)
        if (id != null) Pair(true,  context.getString(R.string.mu_create_user_success, id))
        else            Pair(false, context.getString(R.string.mu_create_user_failed, raw))
    }

    // ─────────────────────────────────────────────────────────────
    //  Provisioning the new user — suspend with progress
    // ─────────────────────────────────────────────────────────────

    suspend fun createAndProvisionUser(
        context: Context,
        name: String,
        onProgress: suspend (step: Int, total: Int, msg: String) -> Unit
    ): Triple<Boolean, Int, String> = withContext(Dispatchers.IO) {

        // Step 1: create the user
        onProgress(0, 1, context.getString(R.string.mu_create_user_progress, name))
        val safeName = name.sanitize()
        val createRaw = runPrivileged(context, "pm create-user \"$safeName\"")
        val userId = extractCreatedUserId(createRaw)
            ?: return@withContext Triple(false, -1, context.getString(R.string.mu_provision_failed_create, createRaw))

        // Step 2: provision
        val (ok, log) = provisionUser(context, userId, onProgress)
        Triple(ok, userId, "ID: $userId\n\n$log")
    }

    suspend fun provisionUser(
        context: Context,
        userId: Int,
        onProgress: suspend (step: Int, total: Int, msg: String) -> Unit
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {

        val shizuku = ShizukoManager.getInstance(context)
        val root    = RootManager.getInstance(context)
        if (!shizuku.hasPermission && !root.isAvailable) {
            return@withContext Pair(false, context.getString(R.string.mu_provision_needs_privilege, userId))
        }

        val pkg       = context.packageName

        val SERVICE   = "$pkg/com.plate.launcher.services.DockService"
        val ADMIN_RCV = "$pkg/.DeviceAdminReceiver"

        val steps = listOf(
            "pm install-existing --user $userId $pkg"                                      to context.getString(R.string.mu_provision_step_install_app),
            "pm install-existing --user $userId moe.shizuku.privileged.api"               to context.getString(R.string.mu_provision_step_install_shizuku),
            "pm grant --user $userId $pkg android.permission.WRITE_SECURE_SETTINGS"       to "WRITE_SECURE_SETTINGS",
            "pm grant --user $userId $pkg android.permission.WRITE_SETTINGS"              to "WRITE_SETTINGS",
            "pm grant --user $userId $pkg android.permission.POST_NOTIFICATIONS"          to "POST_NOTIFICATIONS",
            "pm grant --user $userId $pkg android.permission.PACKAGE_USAGE_STATS"         to "PACKAGE_USAGE_STATS",
            "pm grant --user $userId $pkg android.permission.READ_MEDIA_IMAGES"           to "READ_MEDIA_IMAGES",
            "pm grant --user $userId $pkg android.permission.READ_MEDIA_VIDEO"            to "READ_MEDIA_VIDEO",
            "appops set --user $userId $pkg SYSTEM_ALERT_WINDOW allow"                    to "SYSTEM_ALERT_WINDOW",
            "settings put --user $userId secure enabled_accessibility_services $SERVICE"  to "Accessibility Service",
            "settings put --user $userId secure accessibility_enabled 1"                 to context.getString(R.string.mu_provision_step_accessibility),
            "settings put --user $userId secure enabled_notification_listeners $SERVICE" to "Notification Listener",
            "dpm set-active-admin --user $userId $ADMIN_RCV"                             to "Device Admin",
            "appops set --user $userId $pkg REQUEST_INSTALL_PACKAGES allow"              to "REQUEST_INSTALL_PACKAGES"
        )

        val log   = StringBuilder()
        val total = steps.size

        steps.forEachIndexed { i, (cmd, desc) ->
            onProgress(i + 1, total, desc)
            val result = runPrivileged(context, cmd)
            val ok     = result.isSuccess()
            log.appendLine("$desc: ${if (ok) "OK" else "Warning"}")
            if (!ok && result.isNotBlank() && result != "(no output)") {
                log.appendLine("  ← $result")
                // If the first step (installing the app) fails, stop everything
                if (i == 0) return@withContext Pair(false, context.getString(R.string.mu_provision_failed_install, result))
            }
        }

        Pair(true, log.toString())
    }

    // ─────────────────────────────────────────────────────────────
    //  Deleting a user — suspend
    // ─────────────────────────────────────────────────────────────

    suspend fun removeUser(
        context: Context,
        userId: Int
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (userId == 0) return@withContext Pair(false, context.getString(R.string.mu_remove_user_root_blocked))
        val raw = runPrivileged(context, "pm remove-user $userId")
        val ok  = raw.contains("Success", ignoreCase = true) || raw.isSuccess()
        Pair(ok, if (ok) context.getString(R.string.mu_remove_user_success, userId) else context.getString(R.string.mu_remove_user_failed, raw))
    }

    // ─────────────────────────────────────────────────────────────
    //  Changing the photo — suspend
    // ─────────────────────────────────────────────────────────────

    suspend fun setUserAvatar(
        context: Context,
        userId: Int,
        bitmap: Bitmap
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        saveUserAvatar(context, userId, bitmap)
        val applied = DeviceUtils.setUserIcon(context, userId, bitmap)
        Pair(applied, if (applied) context.getString(R.string.mu_avatar_applied_system) else context.getString(R.string.mu_avatar_saved_locally))
    }

    /**
     * Single entry point for "the user picked an image/GIF file from the
     * gallery as their avatar" — used by onboarding and every other avatar
     * picker in the app, so the GIF-vs-still decision lives in one place
     * rather than being re-detected at each call site.
     *
     * - GIF (sniffed from the file's magic bytes, not the filename/MIME —
     *   content providers don't reliably report GIF as the MIME type):
     *   saves the animated file via [saveUserAvatarGif] for in-app display,
     *   *and* decodes its first frame as a still [Bitmap] to hand to
     *   [setUserAvatar] — Android's system user icon has no animated
     *   concept, so that first frame is what shows up outside this app
     *   (system Settings, the lock screen switcher, etc).
     * - Anything else: decoded as a normal still image, existing
     *   [setUserAvatar] path, no GIF file involved.
     */
    suspend fun setUserAvatarFromPickedMedia(
        context: Context,
        userId: Int,
        uri: android.net.Uri
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) { null } ?: return@withContext Pair(false, context.getString(R.string.mu_avatar_saved_locally))

        if (isGif(bytes)) {
            val firstFrame = decodeFirstGifFrame(bytes)
                ?: return@withContext Pair(false, context.getString(R.string.mu_avatar_saved_locally))
            saveUserAvatarGif(context, userId, bytes)
            val (applied, msg) = setUserAvatar(context, userId, firstFrame)
            // setUserAvatar's own message is about the still frame being
            // applied system-wide; true either way, since the animated
            // version is already saved for this app's own UI regardless of
            // whether the system-icon part succeeded.
            Pair(applied, msg)
        } else {
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return@withContext Pair(false, context.getString(R.string.mu_avatar_saved_locally))
            deleteUserAvatarGif(context, userId) // switching from an animated to a still avatar
            setUserAvatar(context, userId, bmp)
        }
    }

    /**
     * Counterpart to [setUserAvatarFromPickedMedia] for the still-image
     * path once the person has framed it in the circular crop screen
     * (see AvatarAutoCrop) — takes the already-cropped square
     * [bitmap] instead of a raw picked [android.net.Uri], so the crop
     * step only has to happen once regardless of which screen (onboarding
     * or the Users list) triggered the picker.
     */
    suspend fun setUserAvatarFromCroppedBitmap(
        context: Context,
        userId: Int,
        bitmap: Bitmap
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        deleteUserAvatarGif(context, userId) // switching from an animated to a still avatar
        setUserAvatar(context, userId, bitmap)
    }

    /** GIF magic bytes: "GIF87a" or "GIF89a". */
    fun isGif(bytes: ByteArray): Boolean =
        bytes.size >= 6 &&
            bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() &&
            bytes[3] == '8'.code.toByte() &&
            (bytes[4] == '7'.code.toByte() || bytes[4] == '9'.code.toByte()) &&
            bytes[5] == 'a'.code.toByte()

    private fun decodeFirstGifFrame(gifBytes: ByteArray): Bitmap? = try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val source = android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(gifBytes))
            val drawable = android.graphics.ImageDecoder.decodeDrawable(source) { decoder, _, _ ->
                decoder.isDecodeAsAlphaMaskEnabled = false
            }
            (drawable as? android.graphics.drawable.AnimatedImageDrawable)?.let { anim ->
                val bmp = Bitmap.createBitmap(anim.intrinsicWidth, anim.intrinsicHeight, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bmp)
                anim.setBounds(0, 0, anim.intrinsicWidth, anim.intrinsicHeight)
                anim.draw(canvas)
                bmp
            } ?: BitmapFactory.decodeByteArray(gifBytes, 0, gifBytes.size) // static single-frame GIF
        } else {
            // Pre-API 28: no AnimatedImageDrawable — BitmapFactory reads a
            // GIF's first frame as a plain still image, which is exactly
            // the fallback this needs.
            BitmapFactory.decodeByteArray(gifBytes, 0, gifBytes.size)
        }
    } catch (e: Exception) {
        Log.e(TAG, "decodeFirstGifFrame: ${e.message}")
        null
    }

    // ─────────────────────────────────────────────────────────────
    //  Switching between users — suspend
    // ─────────────────────────────────────────────────────────────

    suspend fun switchToUser(
        context: Context,
        userId: Int
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        // Attempt 1: IActivityManager reflection
        if (switchViaSystemApi(userId)) return@withContext Pair(true, context.getString(R.string.mu_switch_user_success, userId))
        // Attempt 2: shell
        val raw = runPrivileged(context, "am switch-user $userId")
        val ok  = raw.isSuccess()
        Pair(ok, if (ok) context.getString(R.string.mu_switch_user_success, userId) else context.getString(R.string.mu_switch_user_failed, raw))
    }

    private fun switchViaSystemApi(userId: Int): Boolean {
        return try {
            val sm  = Class.forName("android.os.ServiceManager")
            val b   = sm.getMethod("getService", String::class.java)
                .invoke(null, "activity") as? android.os.IBinder ?: return false
            val iam = Class.forName("android.app.IActivityManager\$Stub")
                .getMethod("asInterface", android.os.IBinder::class.java)
                .invoke(null, b) ?: return false
            iam.javaClass.getMethod("switchUser", Int::class.java).invoke(iam, userId)
            true
        } catch (e: Exception) { false }
    }

    // ─────────────────────────────────────────────────────────────
    //  The profile photo
    // ─────────────────────────────────────────────────────────────

    fun saveUserAvatar(context: Context, userId: Int, bitmap: Bitmap): Boolean {
        return try {
            val dir  = File(context.filesDir, "user_avatars").also { it.mkdirs() }
            File(dir, "user_$userId.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 0, it)
            }
            true
        } catch (e: Exception) { Log.e(TAG, "saveUserAvatar: ${e.message}"); false }
    }

    /**
     * Saves an animated GIF as the user's avatar — "بروفايلك جيفت". Kept as
     * its own file next to user_$userId.png rather than replacing it:
     * - The static PNG is still what's registered as the *system* user icon
     *   (see [setUserAvatar]/[DeviceUtils.setUserIcon]) — Android's
     *   UserManager has no concept of an animated icon, so a still frame is
     *   always required there regardless of what this app shows in its own
     *   UI.
     * - Every in-app avatar surface (onboarding, settings header, the
     *   Users list, the user switcher popup) prefers this GIF when present
     *   — see [hasAnimatedAvatar]/[getUserAvatarGifPath] — and only falls
     *   back to the static PNG when it isn't.
     */
    fun saveUserAvatarGif(context: Context, userId: Int, gifBytes: ByteArray): Boolean {
        return try {
            val dir = File(context.filesDir, "user_avatars").also { it.mkdirs() }
            File(dir, "user_${userId}_animated.gif").writeBytes(gifBytes)
            true
        } catch (e: Exception) { Log.e(TAG, "saveUserAvatarGif: ${e.message}"); false }
    }

    fun getUserAvatarGifPath(context: Context, userId: Int): String? {
        val f = File(context.filesDir, "user_avatars/user_${userId}_animated.gif")
        return if (f.exists()) f.absolutePath else null
    }

    fun hasAnimatedAvatar(context: Context, userId: Int): Boolean =
        getUserAvatarGifPath(context, userId) != null

    fun deleteUserAvatarGif(context: Context, userId: Int) {
        try { File(context.filesDir, "user_avatars/user_${userId}_animated.gif").delete() }
        catch (e: Exception) { Log.w(TAG, "deleteUserAvatarGif: ${e.message}") }
    }

    fun getUserAvatarPath(context: Context, userId: Int): String? {
        val f = File(context.filesDir, "user_avatars/user_$userId.png")
        return if (f.exists()) f.absolutePath else null
    }

    fun deleteUserAvatar(context: Context, userId: Int) {
        try { File(context.filesDir, "user_avatars/user_$userId.png").delete() }
        catch (e: Exception) { Log.w(TAG, "deleteUserAvatar: ${e.message}") }
        // A leftover GIF pointing at a deleted static avatar would make
        // hasAnimatedAvatar() report true for an avatar the user just
        // removed — clear both together.
        deleteUserAvatarGif(context, userId)
    }

    fun loadUserAvatar(context: Context, userId: Int): Bitmap? {
        getUserAvatarPath(context, userId)?.let { path ->
            val f = File(path)
            if (f.exists()) return BitmapFactory.decodeFile(path)
        }
        return try {
            val um = context.getSystemService(Context.USER_SERVICE) as android.os.UserManager
            android.os.UserManager::class.java
                .getMethod("getUserIcon", Int::class.java)
                .invoke(um, userId) as? Bitmap
        } catch (e: Exception) { Log.w(TAG, "loadUserAvatar: ${e.message}"); null }
    }

    // ─────────────────────────────────────────────────────────────
    //  The current user
    // ─────────────────────────────────────────────────────────────

    fun getCurrentUserId(): Int = try {
        android.os.UserHandle::class.java.getMethod("myUserId").invoke(null) as Int
    } catch (e: Exception) { Log.w(TAG, "getCurrentUserId: ${e.message}"); 0 }

    // ─────────────────────────────────────────────────────────────
    //  Internal helpers
    // ─────────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────────
    //  Callback wrappers — for older files (PerfectServer, UserSwitcherPopup)
    //  Preserves the old API with no changes needed to any other file
    // ─────────────────────────────────────────────────────────────

    fun listUsers(context: Context, onResult: (List<YoukiUser>) -> Unit) {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            val list = try { listUsers(context) } catch (e: Exception) { emptyList() }
            onResult(list)
        }
    }

    fun switchToUser(context: Context, userId: Int, onResult: (Boolean, String) -> Unit) {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            val (ok, msg) = try { switchToUser(context, userId) }
                            catch (e: Exception) { Pair(false, context.getString(R.string.mu_switch_user_generic_failed)) }
            onResult(ok, msg)
        }
    }

    // Always called from Dispatchers.IO
    private fun runPrivileged(context: Context, cmd: String): String {
        val shizuku = ShizukoManager.getInstance(context)
        val root    = RootManager.getInstance(context)
        return when {
            shizuku.hasPermission -> shizuku.runShellSync(cmd) ?: ""
            root.isAvailable      -> root.runShellSync(cmd)    ?: ""
            else                  -> "no_privilege"
        }
    }

    private fun String.isSuccess(): Boolean {
        if (isBlank() || this == "(no output)") return true
        val lower = lowercase()
        return !lower.contains("exception") &&
               !lower.contains("failed:")   &&
               !lower.contains("unknown command") &&
               !lower.contains("no_privilege") &&
               !lower.startsWith("error:")
    }

    private fun String.sanitize(): String =
        replace(Regex("""["'\\`$;|&<>(){}]"""), "").take(64)

    private fun extractCreatedUserId(raw: String): Int? =
        Regex("""created user id (\d+)""").find(raw)?.groupValues?.get(1)?.toIntOrNull()
}
