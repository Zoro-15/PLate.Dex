package com.plate.launcher.utils

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.*
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.lang.reflect.Method

// Gap 2: floating CoroutineScopes — now a scope is passed in from outside, or serviceScope is used
// Gap 28: grantAll had no timeout on commands — now every command has a 5-second max
// Gap 29: callbacks with no cleanup — destroy() clears them, and the caller is responsible for nulling them out
class ShizukoManager private constructor(private val context: Context) {

    companion object {
        const val REQUEST_CODE = 0x5317
        @Volatile private var instance: ShizukoManager? = null
        fun getInstance(ctx: Context) =
            instance ?: synchronized(this) {
                instance ?: ShizukoManager(ctx.applicationContext).also { instance = it }
            }

        /**
         * Single source of truth for the full "grant everything" command list.
         * Used by BOTH ShizukoManager.grantAll() and RootManager.grantAll(),
         * so the two privilege paths (Shizuku / root) share ONE implementation
         * instead of two hand-copied Kotlin lists that could drift apart.
         *
         * Includes the freeform/desktop-windowing commands (previously a
         * separate PerfectServer.enableFreeformWindowing() call) so root OR
         * Shizuku both cover it the instant privilege is obtained.
         */
        fun buildGrantAllCommands(pkg: String): List<String> {
            val sdkInt = Build.VERSION.SDK_INT
            val notifService = "$pkg/com.plate.launcher.services.NotificationService"
            val adminRcv     = "$pkg/.DeviceAdminReceiver"

            return buildList {
                add("pm grant $pkg android.permission.WRITE_SECURE_SETTINGS")
                add("pm grant $pkg android.permission.WRITE_SETTINGS")
                // WRITE_SETTINGS is a special "appop" permission — Settings.System.canWrite()
                // (what MainActivity's hasWriteSystemSettings() checks) reads the appops
                // state, not the manifest-grant state, so `pm grant` alone silently no-ops
                // on many ROMs and the dialog keeps showing it as ungranted. `appops set`
                // is what actually flips canWrite() to true.
                add("appops set $pkg WRITE_SETTINGS allow")
                add("pm grant $pkg android.permission.PACKAGE_USAGE_STATS")
                add("pm grant $pkg android.permission.REQUEST_INSTALL_PACKAGES")
                add("pm grant $pkg android.permission.MANAGE_USERS")
                add("pm grant $pkg android.permission.CREATE_USERS")
                add("pm grant $pkg android.permission.INTERACT_ACROSS_USERS")
                add("pm grant $pkg android.permission.INTERACT_ACROSS_USERS_FULL")
                if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
                    add("pm grant $pkg android.permission.POST_NOTIFICATIONS")
                    add("pm grant $pkg android.permission.READ_MEDIA_IMAGES")
                    add("pm grant $pkg android.permission.READ_MEDIA_VIDEO")
                    add("device_config put privacy_sandbox app_allow_packages_to_use_system_overlay $pkg")
                } else {
                    add("pm grant $pkg android.permission.READ_EXTERNAL_STORAGE")
                    add("pm grant $pkg android.permission.WRITE_EXTERNAL_STORAGE")
                }
                add("appops set $pkg SYSTEM_ALERT_WINDOW allow")
                add("appops set $pkg READ_MEDIA_VISUAL_USER_SELECTED allow")
                add("settings put secure enabled_notification_listeners $notifService")
                add("dpm set-active-admin $adminRcv")

                // ── Bluetooth (Android 12+ runtime permission) — MainActivity's
                // updatePermissionsStatus() checks BLUETOOTH_CONNECT via
                // hasBluetoothPermission(); appops/pm grant covers it the same
                // way as the other runtime permissions above.
                if (sdkInt >= Build.VERSION_CODES.S) {
                    add("pm grant $pkg android.permission.BLUETOOTH_CONNECT")
                    add("pm grant $pkg android.permission.BLUETOOTH_SCAN")
                }

                // ── MANAGE_EXTERNAL_STORAGE ("All files access", Android 11+) —
                // MainActivity's hasManageExternalStorage() checks this via
                // Environment.isExternalStorageManager(), which reads an appops
                // flag, not a pm-grantable permission — appops set is the correct
                // (and only) way to flip it without the user visiting Settings.
                if (sdkInt >= Build.VERSION_CODES.R) {
                    add("appops set $pkg MANAGE_EXTERNAL_STORAGE allow")
                }

                // ── "Overlays in Settings" toggle — MainActivity's
                // settingsOverlaysAllowed checks DeviceUtils.SETTING_OVERLAYS
                // ("secure_overlay_settings"); without this the dialog keeps
                // showing it as missing even though every other grant succeeded.
                add("settings put secure secure_overlay_settings 1")

                // NOTE: Accessibility service (DockService) is deliberately
                // NOT auto-enabled here. Writing WRITE_SECURE_SETTINGS itself
                // ends up flipping accessibility on as an OS-level side
                // effect for some of the other grants above, and that
                // auto-on behavior is unwanted — the person may want to keep
                // it off until they explicitly turn it on themselves (e.g.
                // from LauncherActivity's own enableService() flow). If this
                // is ever revisited, see the removed block that used to add
                // "settings put secure enabled_accessibility_services ..."
                // and "settings put secure accessibility_enabled 1" here.

                // ── Freeform / desktop windowing — merged in from the old
                // PerfectServer.enableFreeformWindowing(), now part of the
                // single grant-all batch so root OR Shizuku both cover it the
                // instant privilege is obtained.
                add("settings put global enable_freeform_support 1")
                add("settings put global force_desktop_mode_on_external_displays 0")
                if (sdkInt >= 31) {
                    add("wm set-multi-window-config --freeformWindowManagement true")
                }
                // Android 11+ developer-option equivalent of "Force activities to
                // be resizable" — without this, apps declaring
                // resizeableActivity=false get pushed back to fullscreen even
                // inside a freeform task.
                add("settings put global development_force_resizable_activities 1")
                // Android 15 (API 35): Desktop Windowing Mode. Without these,
                // freeform windows on API 35 render without the system caption bar.
                if (sdkInt == 35) {
                    add("wm set-multi-window-config --supportsDesktopWindowing true")
                    add("wm set-multi-window-config --enableDesktopMode true")
                }
            }
        }
    }

    // FIX: instead of a single slot → Maps support multiple listeners by key
    // Each component registers with its own key and removes itself on close without evicting the others
    private val boundListeners   = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()
    private val grantedListeners = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()
    private val deniedListeners  = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()
    private val unboundListeners = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()
    private val logListeners     = java.util.concurrent.ConcurrentHashMap<String, (String) -> Unit>()

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Registers an onBound listener.
     * FIX: if Shizuku is already connected, the callback fires immediately on
     * the Main thread (fixes the "opened the Fragment and found Shizuku
     * already connected but the UI wasn't updated" issue)
     */
    fun addOnBoundListener(key: String, cb: () -> Unit) {
        boundListeners[key] = cb
        if (isAvailable) mainHandler.post { cb() }
    }
    fun removeOnBoundListener(key: String)  { boundListeners.remove(key) }

    fun addOnGrantedListener(key: String, cb: () -> Unit) { grantedListeners[key] = cb }
    fun removeOnGrantedListener(key: String) { grantedListeners.remove(key) }

    fun addOnDeniedListener(key: String, cb: () -> Unit) { deniedListeners[key] = cb }
    fun removeOnDeniedListener(key: String)  { deniedListeners.remove(key) }

    fun addOnUnboundListener(key: String, cb: () -> Unit) { unboundListeners[key] = cb }
    fun removeOnUnboundListener(key: String) { unboundListeners.remove(key) }

    // Compat: for old code that uses var directly — it gets routed to the map
    @Deprecated("Use addOnBoundListener/removeOnBoundListener")
    var onLog:     ((String) -> Unit)? = null
    @Deprecated("Use addOnGrantedListener/removeOnGrantedListener")
    var onGranted: (() -> Unit)?       = null
    @Deprecated("Use addOnDeniedListener/removeOnDeniedListener")
    var onDenied:  (() -> Unit)?       = null
    @Deprecated("Use addOnBoundListener/removeOnBoundListener")
    var onBound:   (() -> Unit)?       = null
    @Deprecated("Use addOnUnboundListener/removeOnUnboundListener")
    var onUnbound: (() -> Unit)?       = null

    // FIX: cache the connection state instead of calling pingBinder() every time
    // pingBinder() returns false if the Fragment opened before the binder connected
    @Volatile private var _binderAlive: Boolean = false
    @Volatile private var _permissionGranted: Boolean = false

    // Internal scope for operations that don't have an external scope
    private val internalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        _binderAlive = true
        // Update the permission state immediately
        _permissionGranted = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) { false }

        internalScope.launch(Dispatchers.Main) {
            boundListeners.values.forEach { it() }
            onBound?.invoke()
            when {
                _permissionGranted -> internalScope.launch { grantAll() }
                else -> try { Shizuku.requestPermission(REQUEST_CODE) } catch (e: Exception) {}
            }
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        _binderAlive = false
        _permissionGranted = false
        internalScope.launch(Dispatchers.Main) {
            unboundListeners.values.forEach { it() }
            onUnbound?.invoke()
        }
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, result ->
            _permissionGranted = result == PackageManager.PERMISSION_GRANTED
            if (_permissionGranted) {
                internalScope.launch { grantAll() }
            } else {
                internalScope.launch(Dispatchers.Main) {
                    deniedListeners.values.forEach { it() }
                    onDenied?.invoke()
                }
            }
        }

    init {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)

        // Multi-process binder relay: enableMultiProcessSupport(true) (called
        // in App's init block, see its kdoc) only tells the PROVIDER process
        // (the main process, where ShizukuProvider actually lives per the
        // manifest — it has no android:process override) to broadcast the
        // binder out to other declared processes when it receives one. A
        // non-provider process — this app's ":qa" process, where
        // QaSandboxActivity/QaVirtualDisplayHost run — still has to actively
        // ask to receive that broadcast; it doesn't arrive unprompted. This
        // is that ask, and it's safe to call from the main/provider process
        // too (it's simply a no-op there, since that process already has the
        // binder directly from the provider).
        rikka.shizuku.ShizukuProvider.requestBinderForNonProviderProcess(context)
    }

    val isAvailable: Boolean
        get() {
            // If the cache says connected, trust it — but if it says not connected, check one more time
            if (_binderAlive) return true
            val alive = try { Shizuku.pingBinder() } catch (e: Exception) { false }
            if (alive) _binderAlive = true
            return alive
        }

    val hasPermission: Boolean
        get() {
            if (!isAvailable) return false
            // If the cache says granted, trust it directly without a Binder call
            if (_permissionGranted) return true
            val granted = try {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } catch (e: Exception) { false }
            if (granted) _permissionGranted = true
            return granted
        }

    /**
     * Requests Shizuku permission, or — if Shizuku isn't currently running/bound — tries to
     * actively help the user get there instead of silently doing nothing (the original bug:
     * !isAvailable -> {} meant tapping "Grant" had zero visible effect for the common
     * first-time-user case where Shizuku is installed but not yet started/paired).
     *
     * Priority: if Shizuku is available, request its permission directly (unchanged). If
     * not, but the Shizuku app is installed, open it so the user can start/pair the
     * service themselves (same as tapping the app icon manually). If Shizuku isn't
     * installed at all, fall back to checking/requesting Root via RootManager — this is
     * the explicit "search for root or Shizuku" fallback behavior.
     */
    fun requestPermission() {
        when {
            hasPermission -> internalScope.launch { grantAll() }
            isAvailable -> try { Shizuku.requestPermission(REQUEST_CODE) } catch (e: Exception) {}
            isShizukuAppInstalled() -> openShizukuApp()
            else -> RootManager.getInstance(context).requestPermission()
        }
    }

    private fun isShizukuAppInstalled(): Boolean = try {
        context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** Opens the Shizuku app's own launcher Activity so the user can start/pair the service themselves, since we can't start it programmatically from here. */
    private fun openShizukuApp() {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            if (intent != null) {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        } catch (e: Exception) { /* nothing more we can do from here if this fails */ }
    }

    private suspend fun grantAll() {
        if (!hasPermission) return
        val pkg = context.packageName
        val cmds = buildGrantAllCommands(pkg)

        // Gap 28: every command has a 5-second timeout — never freezes
        for (cmd in cmds) {
            try { withTimeout(5_000L) { shell(cmd) } } catch (e: TimeoutCancellationException) {
                log("Command timed out: $cmd")
            }
        }

        verifyFreeformFlags()

        withContext(Dispatchers.Main) {
            grantedListeners.values.forEach { it() }
            onGranted?.invoke() // compat
        }
    }

    /**
     * Reads back the freeform/desktop-mode settings we just wrote and logs whether they
     * actually stuck. Some OEM ROMs silently reject `settings put` on certain global keys —
     * this turns that into a visible log line instead of a mystery "still no caption bar"
     * report from a user.
     */
    private fun verifyFreeformFlags() {
        try {
            val freeform = android.provider.Settings.Global.getInt(
                context.contentResolver, "enable_freeform_support", -1)
            log("Verify: enable_freeform_support=$freeform (expected 1)")
            if (Build.VERSION.SDK_INT >= 35) {
                val resizable = android.provider.Settings.Global.getInt(
                    context.contentResolver, "development_force_resizable_activities", -1)
                log("Verify: development_force_resizable_activities=$resizable (expected 1)")
            }
        } catch (e: Exception) {
            log("verifyFreeformFlags error: ${e.message}")
        }
    }

    // Cache the Method to avoid reflection on every call
    private var newProcessMethod: Method? = null

    @Suppress("DiscouragedPrivateApi")
    // Internal visibility so UhidManager (same package group — utils/) can
    // open a long-lived process (cat > /dev/uhid) without routing through
    // the shell() helper which drains stdout and destroys the process.
    internal fun newProcessPublic(cmd: String) = newProcess(cmd)

    private fun newProcess(cmd: String): ShizukuRemoteProcess {
        val m = newProcessMethod ?: Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        ).also { it.isAccessible = true; newProcessMethod = it }
        @Suppress("UNCHECKED_CAST")
        return m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as ShizukuRemoteProcess
    }

    // ─────────────────────────────────────────────────────────────
    //  Shell execution — none of these functions use waitFor()/exitValue()
    //  because ShizukuRemoteProcess randomly throws "process hasn't exited".
    //  readText() on inputStream blocks automatically until the process closes
    //  stdout, and that's enough — we don't need exitCode in the normal path.
    // ─────────────────────────────────────────────────────────────

    private suspend fun shell(cmd: String): String = withContext(Dispatchers.IO) {
        if (!hasPermission) return@withContext ""
        try {
            val p = newProcess(cmd)
            // FIX: no waitFor/exitValue — readText() blocks until the process ends naturally
            val stderr = Thread { runCatching { p.errorStream.use { it.bufferedReader().readText() } } }
            stderr.start()
            val out = p.inputStream.bufferedReader().readText().trim()
            stderr.join(500)
            runCatching { p.destroy() }
            out
        } catch (e: Exception) { "" }
    }

    fun runShell(cmd: String, scope: CoroutineScope = internalScope, onResult: (String) -> Unit) {
        if (!hasPermission) { onResult(""); return }
        scope.launch(Dispatchers.IO) {
            val r = shell(cmd)
            withContext(Dispatchers.Main) { onResult(r) }
        }
    }

    /** Blocking version — call only from a background thread (IO dispatcher). */
    fun runShellSync(cmd: String): String? {
        if (!hasPermission) return null
        return try {
            val p = newProcess(cmd)
            // FIX: same approach — no waitFor/exitValue
            val stderr = Thread { runCatching { p.errorStream.use { it.bufferedReader().readText() } } }
            stderr.start()
            val out = p.inputStream.bufferedReader().readText().trim()
            stderr.join(500)
            runCatching { p.destroy() }
            out
        } catch (e: Exception) { null }
    }

    /**
     * Diagnostics — same approach (no waitFor/exitValue) but shows stdout/stderr/exception
     * instead of silently swallowing the error. Used only from the "Diagnostics" screen.
     */
    fun diagnose(cmd: String): String {
        if (!isAvailable)   return "Shizuku: binder not connected (isAvailable = false)"
        if (!hasPermission) return "Shizuku: connected but without permission (hasPermission = false)"
        return try {
            val p = newProcess(cmd)
            var err = ""
            val tErr = Thread { runCatching { err = p.errorStream.bufferedReader().readText() } }
            tErr.start()
            val out = p.inputStream.bufferedReader().readText().trim()
            tErr.join(500)
            runCatching { p.destroy() }
            buildString {
                append("stdout (${out.length} chars):\n")
                append(if (out.isBlank()) "(empty)" else out)
                if (err.isNotBlank()) { append("\n\nstderr:\n"); append(err) }
            }
        } catch (e: Exception) {
            "Exception in newProcess(\"$cmd\"):\n${e.javaClass.simpleName}: ${e.message}"
        }
    }


    fun grantWriteSecureSettings(pkg: String, scope: CoroutineScope = internalScope, onResult: (String) -> Unit) =
        runShell("pm grant $pkg android.permission.WRITE_SECURE_SETTINGS", scope, onResult)

    fun grantWriteSettings(pkg: String, scope: CoroutineScope = internalScope, onResult: (String) -> Unit) =
        runShell("pm grant $pkg android.permission.WRITE_SETTINGS", scope, onResult)

    // ─────────────────────────────────────────────────────────────
    //  Detects the active (Resumed) app + apps running in the background via
    //  dumpsys, instead of getRunningTasks(), which has been restricted to
    //  system apps only since Android Lollipop. Only called if hasPermission.
    // ─────────────────────────────────────────────────────────────

    private val resumedActivityRegex = Regex("""u0\s+([\w.]+)/""")

    /**
     * Returns the package name of the currently active app (Foreground/Resumed).
     * Blocking — call it only from an IO dispatcher (same restriction as runShellSync).
     */
    fun getForegroundPackage(): String? {
        val out = runShellSync("dumpsys activity activities | grep mResumedActivity") ?: return null
        return resumedActivityRegex.find(out)?.groupValues?.get(1)
    }

    /**
     * Returns the package names of every app that currently has a running Task
     * (foreground + background) — an unrestricted alternative to
     * ActivityManager.getRunningTasks() for regular apps.
     * Blocking — call it only from an IO dispatcher.
     */
    fun getRunningPackages(): List<String> {
        val out = runShellSync("dumpsys activity activities | grep 'Run #'") ?: return emptyList()
        return resumedActivityRegex.findAll(out)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
    }

    fun destroy() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        _binderAlive = false
        _permissionGranted = false
        boundListeners.clear()
        grantedListeners.clear()
        deniedListeners.clear()
        unboundListeners.clear()
        logListeners.clear()
        onLog = null; onGranted = null; onDenied = null; onBound = null; onUnbound = null
        internalScope.cancel()
    }

    private fun log(msg: String) = onLog?.invoke(msg)
}
