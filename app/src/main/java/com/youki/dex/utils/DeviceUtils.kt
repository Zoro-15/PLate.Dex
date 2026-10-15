package com.youki.dex.utils

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import android.os.Build
import java.util.Collections
import android.os.UserHandle
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.youki.dex.services.DockService
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import android.os.UserManager
import androidx.core.net.toUri

object DeviceUtils {
    const val DISPLAY_SIZE = "display_density_forced"
    const val ICON_BLACKLIST = "icon_blacklist"
    const val HEADS_UP_ENABLED = "heads_up_notifications_enabled"
    const val ENABLE_TASKBAR = "enable_taskbar"
    const val SETTING_OVERLAYS = "secure_overlay_settings"
    private const val SERVICE_NAME = "com.youki.dex/com.youki.dex.services.DockService"
    private const val ENABLED_ACCESSIBILITY_SERVICES = "enabled_accessibility_services"

    /**
     * Returns a root shell Process.
     *
     * Modern Magisk (v20+) and KernelSU inject `su` into PATH at runtime
     * and do NOT place it at a static path. We therefore prefer the
     * PATH-based lookup first, falling back to static paths for older roots.
     */
    @get:Throws(IOException::class)
    val rootAccess: Process
        get() {
            // 1. PATH-based su — works with Magisk v20+, KernelSU, APatch
            try {
                val test = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                val out = test.inputStream.bufferedReader().readText()
                test.waitFor()
                if (out.contains("uid=0")) return Runtime.getRuntime().exec("su")
            } catch (e: Exception) {}

            // 2. Static paths — legacy roots (Magisk ≤ v15, SuperSU, etc.)
            val paths = arrayOf(
                "/sbin/su", "/system/sbin/su", "/system/bin/su",
                "/system/xbin/su", "/su/bin/su", "/magisk/.core/bin/su"
            )
            for (path in paths) {
                if (File(path).canExecute()) return Runtime.getRuntime().exec(path)
            }
            // Last resort — will throw if not present
            return Runtime.getRuntime().exec("su")
        }

    fun runAsRoot(command: String): String {
        // Gap 21: used to open a new su process for every command — now it delegates to RootManager
        // But since RootManager needs a Context, we keep the same implementation here
        // But we add a timeout to avoid freezing — Gap 40
        val output = StringBuilder()
        try {
            val process = rootAccess
            val os = DataOutputStream(process.outputStream)
            os.writeBytes(command + "\n")
            os.writeBytes("exit\n")
            os.flush()
            os.close()
            // Gap 41: parallel reading to avoid deadlock on stdout/stderr
            var stdoutText = ""
            val stdoutThread = Thread {
                stdoutText = process.inputStream.bufferedReader().readText()
            }
            stdoutThread.start()
            process.errorStream.bufferedReader().readText()  // drain stderr
            process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            stdoutThread.join(2000)
            output.append(stdoutText)
        } catch (e: Exception) {
            return "error"
        }
        return output.toString().trimEnd('\n')
    }

    // ─────────────────────────────────────────────────────────────
    //  Huawei / HarmonyOS Detection
    // ─────────────────────────────────────────────────────────────

    /** Returns true if running on a Huawei/Honor device */
    fun isHuaweiDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        return manufacturer.contains("huawei") || brand.contains("huawei") ||
               brand.contains("honor")
    }

    /**
     * Returns true if running on HarmonyOS (pure — no Android layer).
     * HarmonyOS 4.3+ removes the Android base layer, so Android-specific APIs
     * like freeform windowing and some AccessibilityService internals will crash.
     * We detect this by checking the system property "ro.build.version.emui"
     * and the HarmonyOS version property.
     */
    fun isHarmonyOS(): Boolean {
        return try {
            val harmonyVersion = getSystemProperty("hw_sc.build.platform.version")
            val emuiVersion = getSystemProperty("ro.build.version.emui")
            !harmonyVersion.isNullOrEmpty() || (!emuiVersion.isNullOrEmpty() &&
                    emuiVersion.lowercase().contains("harmonyos"))
        } catch (e: Exception) { false }
    }

    /**
     * Returns true if on HarmonyOS >= 5, which is fully independent of Android.
     * On these devices freeform windowing and some overlay types crash.
     */
    fun isPureHarmonyOS(): Boolean {
        if (!isHuaweiDevice()) return false
        return try {
            val prop = getSystemProperty("hw_sc.build.platform.version") ?: return false
            val major = prop.trim().split(".").firstOrNull()?.toIntOrNull() ?: return false
            major >= 5
        } catch (e: Exception) {
            // Fallback: check if the typical Android freeform setting key is missing/blocked
            try {
                val value = getSystemProperty("ro.config.hw_freeform_support")
                value != null && value == "false"
            } catch (e: Exception) { false }
        }
    }

    private fun getSystemProperty(key: String): String? {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("get", String::class.java, String::class.java)
            val result = method.invoke(null, key, "") as? String
            result?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) { null }
    }

    fun hideStatusBar(context: android.content.Context, hide: Boolean) {
        try {
            android.provider.Settings.Global.putString(
                context.contentResolver,
                "policy_control",
                if (hide) "immersive.full=*" else "null"
            )
        } catch (e: Exception) {}
    }

    @Volatile
    private var hasSetOrientationPermission: Boolean? = null

    fun freezeRotation(landscape: Boolean) {
        if (hasSetOrientationPermission == false) return
        try {
            val rotation = if (landscape) android.view.Surface.ROTATION_90 else android.view.Surface.ROTATION_0
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "window")
            val stub = Class.forName("android.view.IWindowManager${"$"}Stub")
            val asInterface = stub.getMethod("asInterface", android.os.IBinder::class.java)
            val wm = asInterface.invoke(null, binder)
            if (landscape) {
                val freeze = wm.javaClass.getMethod("freezeRotation", Int::class.javaPrimitiveType)
                freeze.invoke(wm, rotation)
            } else {
                val thaw = wm.javaClass.getMethod("thawRotation")
                thaw.invoke(wm)
            }
            hasSetOrientationPermission = true
        } catch (e: Throwable) {
            val cause = e.cause ?: e
            if (cause is SecurityException || e is SecurityException ||
                cause.message?.contains("SET_ORIENTATION", ignoreCase = true) == true
            ) {
                hasSetOrientationPermission = false
            }
        }
    }

    //Device control
    fun lockScreen(context: Context) {
        val devicePolicyManager =
            context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        try {
            devicePolicyManager.lockNow()
        } catch (e: SecurityException) {
        }
    }

    fun sendKeyEvent(keycode: Int) {
        runAsRoot("input keyevent $keycode")
    }

    fun softReboot() {
        runAsRoot("setprop ctl.restart zygote")
    }

    fun reboot(context: Context? = null) {
        // FIX: "am start -a android.intent.action.REBOOT" launches an Activity
        // matching that intent filter — but no Activity on stock Android
        // actually registers for REBOOT/REQUEST_SHUTDOWN (PowerManagerService
        // handles them internally, not via am start), so this silently did
        // nothing even with full root/Shizuku shell access — the command
        // itself was wrong, not the permission check. The plain shell command
        // "reboot" is what actually works from a root or Shizuku (ADB-level)
        // shell.
        val cmd = "reboot"
        // FIX: يدعم Shizuku إذا متاح، وإلا يرجع للـ Root
        if (context != null) {
            val shizuku = ShizukoManager.getInstance(context)
            if (shizuku.hasPermission) { shizuku.runShell(cmd) {}; return }
        }
        runAsRoot(cmd)
    }

    fun shutdown(context: Context? = null) {
        // FIX: same root cause as reboot() above — "am start -a ...SHUTDOWN"
        // launches an Activity that doesn't exist to catch it. "reboot -p"
        // (power off) is the real shell equivalent.
        val cmd = "reboot -p"
        // FIX: يدعم Shizuku إذا متاح، وإلا يرجع للـ Root
        if (context != null) {
            val shizuku = ShizukoManager.getInstance(context)
            if (shizuku.hasPermission) { shizuku.runShell(cmd) {}; return }
        }
        runAsRoot(cmd)
    }

    fun toggleVolume(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            AudioManager.ADJUST_SAME,
            AudioManager.FLAG_SHOW_UI
        )
    }

    // Strong references to prevent GC from collecting MediaPlayer during playback
    private val activePlayers = Collections.synchronizedSet(mutableSetOf<MediaPlayer>())

    fun playEventSound(context: Context, event: String) {
        // Read absolute path from SharedPrefs (new version stores a path, not a URI)
        val filePath = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(event, null)
            ?.takeIf { it.isNotBlank() }
            ?: return

        // Check that the file exists before attempting playback
        val file = File(filePath)
        if (!file.exists() || !file.canRead()) {
            Log.w("YoukiDex", "Sound file not found or not readable: $filePath")
            return
        }

        try {
            val mp = MediaPlayer()
            activePlayers.add(mp) // ← add before prepareAsync to guarantee the reference is held

            try {
                mp.apply {
                    // AudioAttributes (API 21+) instead of the deprecated setAudioStreamType
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        setAudioStreamType(AudioManager.STREAM_MUSIC)
                    }

                    // Direct path → no ContentResolver or URI permissions needed
                    setDataSource(filePath)

                    setOnPreparedListener { player ->
                        player.start()
                    }

                    setOnCompletionListener { player ->
                        activePlayers.remove(player) // ← remove after completion
                        player.release()
                    }

                    setOnErrorListener { player, what, extra ->
                        Log.e("YoukiDex", "MediaPlayer error [$event]: what=$what extra=$extra")
                        activePlayers.remove(player)
                        player.release()
                        true
                    }

                    prepareAsync() // ← async: does not block the UI thread
                }
            } catch (setupError: Exception) {
                // Fix for Memory Leak: if setDataSource or any setup before
                // prepareAsync() threw an exception (a corrupt file, an
                // unsupported format...), mp was already inside activePlayers
                // and no listener had fired yet to remove and release it — it
                // stayed hanging in memory forever. Every failed sound = a
                // permanently leaked MediaPlayer instance.
                Log.e("YoukiDex", "playEventSound setup failed [$event]: ${setupError.message}")
                activePlayers.remove(mp)
                try { mp.release() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e("YoukiDex", "playEventSound failed [$event]: ${e.message}")
        }
    }

    fun putSystemSetting(context: Context, key: String, value: String): Boolean {
        return try {
            Settings.System.putString(context.contentResolver, key, value)
            true
        } catch (e: SecurityException) {
            Log.e("YoukiDex", "putSystemSetting failed [$key]: ${e.message}")
            false
        } catch (e: IllegalArgumentException) {
            // Fix for full app crash: modern Android rejects any key not on the
            // Settings.System allowlist by throwing an IllegalArgumentException
            // ("You cannot keep your settings in the secure settings") instead
            // of the previously expected SecurityException only. Without this
            // catch, any call with a disallowed key used to crash the entire
            // app instead of failing silently in a way the caller could handle
            // (e.g. by showing the user a message).
            Log.e("YoukiDex", "putSystemSetting rejected [$key]: ${e.message}")
            false
        }
    }

    fun getSystemSetting(context: Context, key: String, default: String = ""): String {
        return try {
            Settings.System.getString(context.contentResolver, key) ?: default
        } catch (e: Exception) {
            default
        }
    }

    fun getSecureSetting(context: Context, setting: String, defaultValue: Int): Int {
        return try {
            Settings.Secure.getInt(context.contentResolver, setting)
        } catch (e: Exception) {
            defaultValue
        }
    }

    fun getSecureSetting(context: Context, setting: String, defaultValue: String): String {
        return try {
            val value = Settings.Secure.getString(context.contentResolver, setting)
            value ?: defaultValue
        } catch (e: Exception) {
            defaultValue
        }
    }

    fun putSecureSetting(context: Context, setting: String, value: String): Boolean {
        return try {
            Settings.Secure.putString(context.contentResolver, setting, value)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    fun putGlobalSetting(context: Context, setting: String, value: Int): Boolean {
        return try {
            Settings.Global.putInt(context.contentResolver, setting, value)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    fun getGlobalSetting(context: Context, setting: String, defaultValue: Int): Int {
        return try {
            Settings.Global.getInt(context.contentResolver, setting)
        } catch (e: Exception) {
            defaultValue
        }
    }

    //Device info
    @SuppressLint("InternalInsetResource", "DiscouragedApi")
    fun getStatusBarHeight(context: Context): Int {
        var result = 0
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            result = context.resources.getDimensionPixelSize(resourceId)
        }
        return result
    }

    // FIX: getNavBarHeight was flaky on Android 15+ — "sometimes the dock
    // shows fully, sometimes part of it is cut off under the gesture area"
    // (user report). Root cause: the WindowInsets query below can transiently
    // return 0 or throw (called before the window's insets have settled,
    // called from a context whose WindowManager isn't fully attached yet,
    // etc.), and the old code treated that failure identically to "this
    // device genuinely has a 0px nav bar" — falling back to the `dimen`
    // lookup, which the comment below already documents as ALWAYS wrong
    // (returns 0) on 15+ gesture nav. So a single flaky call at the wrong
    // moment made the dock believe it had the full screen height available
    // and undershoot the gesture-area clearance for that layout pass.
    // Caching the last known-good non-zero reading and preferring it over
    // the 0px `dimen` fallback (only on 15+, only when the live query
    // itself failed) turns "one bad reading breaks this layout pass" into
    // "one bad reading reuses the last good one" — self-healing the moment
    // any later call succeeds, since the cache keeps updating.
    @Volatile
    private var lastKnownNavBarHeight: Int = 0

    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    fun getNavBarHeight(context: Context): Int {
        // On Android 15+ with gesture navigation, navigation_bar_height dimen returns 0
        // because the gesture bar has no height. But TYPE_APPLICATION_OVERLAY windows still
        // draw UNDER the gesture area, blocking touches. Use WindowInsets to get the real value.
        if (android.os.Build.VERSION.SDK_INT >= 35) {
            try {
                // getSystemService(Class) is nullable by its own official
                // signature (<T> getSystemService(Class<T>): T?) — was
                // being force-used here with no null check at all. Caught
                // implicitly by the surrounding try/catch as an NPE before
                // (Kotlin/Android NPEs are still Exceptions), so this
                // wasn't a live crash, but it silently fell straight
                // through to the stale-reading fallback below on every
                // call where the service happened to come back null,
                // which is exactly the kind of hidden failure mode this
                // whole fix is about closing — make it explicit instead of
                // relying on exception-catching to paper over a null.
                val wm = context.getSystemService(android.view.WindowManager::class.java)
                val metrics = wm?.currentWindowMetrics
                val insets = metrics?.windowInsets?.getInsets(
                    android.view.WindowInsets.Type.navigationBars()
                )
                if (insets != null && insets.bottom > 0) {
                    lastKnownNavBarHeight = insets.bottom
                    return insets.bottom
                }
            } catch (e: Exception) {}
            // Query failed or returned 0 this time — on 15+ that's the flaky
            // case described above, not necessarily "no nav bar", so prefer
            // the last good reading over the dimen fallback that's known to
            // always read 0 here.
            if (lastKnownNavBarHeight > 0) return lastKnownNavBarHeight
        }
        var result = 0
        val resourceId =
            context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        if (resourceId > 0) {
            result = context.resources.getDimensionPixelSize(resourceId)
        }
        return result
    }

    fun getUserName(context: Context): String? {
        val um = context.getSystemService(Context.USER_SERVICE) as UserManager
        try {
            return um.userName
        } catch (e: Exception) {
        }
        return null
    }

    fun getUserIcon(context: Context): Bitmap? {
        val um = context.getSystemService(Context.USER_SERVICE) as UserManager
        var userIcon: Bitmap? = null
        try {
            val getUserIcon = um.javaClass.getMethod("getUserIcon", Int::class.javaPrimitiveType)
            val myUserId = UserHandle::class.java.getMethod("myUserId")
            val id = myUserId.invoke(UserHandle::class.java) as Int
            userIcon = getUserIcon.invoke(um, id) as Bitmap?
            if (userIcon != null) userIcon = Utils.getCircularBitmap(userIcon)
        } catch (e: Exception) {
        }
        return userIcon
    }

    /**
     * Changes the user's photo at the system level (needs MANAGE_USERS or shell).
     * Called via Shizuku/Root from MultiUserManager.
     */
    /**
     * Changes the user's photo at the system level.
     * Tries 3 methods: Shizuku shell ← Root ← UserManager reflection
     */
    fun setUserIcon(context: Context, userId: Int, bitmap: Bitmap): Boolean {
        val tmp = java.io.File(context.cacheDir, "youki_icon_tmp.png")
        return try {
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val path = tmp.absolutePath

            // Method 1: Shizuku — pm set-user-icon
            val shizuku = ShizukoManager.getInstance(context)
            if (shizuku.hasPermission) {
                val latch = java.util.concurrent.CountDownLatch(1)
                var ok = false
                shizuku.runShell("pm set-user-icon $userId $path") { raw ->
                    ok = !raw.contains("error", ignoreCase = true) &&
                         !raw.contains("Exception", ignoreCase = true)
                    latch.countDown()
                }
                latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
                if (ok) return true
            }

            // Method 2: Root
            val root = RootManager.getInstance(context)
            if (root.isAvailable) {
                val raw = root.runShellSync("pm set-user-icon $userId $path")
                if (raw != null && !raw.contains("error", ignoreCase = true)) return true
            }

            // Method 3: UserManager reflection (system app)
            val um = context.getSystemService(Context.USER_SERVICE) as UserManager
            val method = um.javaClass.getMethod(
                "setUserIcon",
                Int::class.javaPrimitiveType,
                Bitmap::class.java
            )
            method.invoke(um, userId, bitmap)
            true
        } catch (e: Exception) {
            android.util.Log.e("DeviceUtils", "setUserIcon failed: ${e.message}")
            false
        } finally {
            tmp.delete()
        }
    }

    fun getDisplays(context: Context, category: String? = null): Array<Display> {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return dm.getDisplays(category)
    }

    /**
     * GitHub issue #15 ("The wallpaper on the secondary screen is not
     * working! only on my cell phone" / general secondary-display detection
     * complaints — Portable Touch Monitor via USB-C/HDMI cable): this used
     * to only look at DisplayManager.DISPLAY_CATEGORY_PRESENTATION. That
     * category is NOT a general "all connected external displays" list —
     * it's a narrower platform classification that, depending on the OEM/
     * ROM/Android version, some real wired external monitors (USB-C-to-HDMI
     * portable touch monitors in particular) simply never get added to,
     * even though the same display shows up fine in the plain getDisplays()
     * list and DisplayManager clearly knows about it. When that category
     * came back empty, getSecondaryDisplay() returned null — the app
     * behaved as if no secondary display existed at all, even with a cable
     * plugged in and a monitor lit up, which is the "app doesn't create a
     * screen or a preview for a new screen" behavior being reported.
     *
     * Fix: fall back to scanning every currently-known display
     * (DisplayManager.getDisplays(), unfiltered) and pick the first one
     * that isn't the built-in default display, if the PRESENTATION-category
     * query comes back empty. This catches externally connected displays
     * that Android tracks but doesn't classify as "presentation" displays.
     */
    fun getSecondaryDisplay(context: Context): Display? {
        val presentationDisplays = getDisplays(context, DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        if (presentationDisplays.isNotEmpty()) return presentationDisplays[0]

        return getDisplays(context).firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
    }

    /**
     * v63 — full rewrite. The previous version read Display.getMetrics(),
     * which Android itself documents as deprecated and which — especially
     * on DEX/desktop multi-window setups like this app targets — is not
     * guaranteed to reflect the screen's *current* rotation/size. That
     * unreliability is what caused width/height to come back swapped,
     * which corrupted every window size/position calculation downstream
     * (dock, app menu, launched app windows).
     *
     * Fix: read dimensions directly from the platform, from a single
     * always-current source — WindowManager.currentWindowMetrics — instead
     * of the legacy Display object. This is Android's own recommended
     * replacement for Display.getMetrics()/getRealMetrics() since API 30,
     * specifically because it always reflects the actual current window/
     * display bounds, including on foldables, multi-window, and secondary
     * displays. No manual "correction" logic of any kind is applied here —
     * whatever the platform reports is what we use, as-is.
     *
     * Note: this used to also re-derive a fresh createConfigurationContext()
     * here to guard against a stale fontScale Configuration on long-lived
     * Service contexts (PerfectServer/NotificationService). That's no longer
     * needed — as of v65 those Services never wrap their Context with a
     * modified Configuration at all (see AppFontScaleUtils.kt), so `context`
     * here is never carrying stale font-scale/density state to begin with.
     */
    fun getDisplayMetrics(
        context: Context,
        displayId: Int = Display.DEFAULT_DISPLAY
    ): DisplayMetrics {
        val metrics = DisplayMetrics()

        try {
            // Get a context bound to the requested display so WindowManager
            // reports that display's bounds, not necessarily the default one.
            val displayContext = if (displayId == Display.DEFAULT_DISPLAY) {
                context
            } else {
                val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                val targetDisplay = displayManager.getDisplay(displayId)
                if (targetDisplay != null) context.createDisplayContext(targetDisplay) else context
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val wm = displayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val bounds = wm.currentWindowMetrics.bounds
                metrics.widthPixels = bounds.width()
                metrics.heightPixels = bounds.height()
                metrics.density = displayContext.resources.displayMetrics.density
                metrics.densityDpi = displayContext.resources.displayMetrics.densityDpi
            } else {
                val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                val targetDisplay = displayManager.getDisplay(displayId)
                if (targetDisplay != null) {
                    @Suppress("DEPRECATION")
                    targetDisplay.getRealMetrics(metrics)
                } else {
                    val wm = displayContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                    @Suppress("DEPRECATION")
                    wm?.defaultDisplay?.getRealMetrics(metrics)
                }
            }
        } catch (e: Throwable) {
            // Fallback only if the modern API is somehow unavailable —
            // still no manual width/height "correction" logic here.
            try {
                val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                @Suppress("DEPRECATION")
                dm.getDisplay(displayId)?.getRealMetrics(metrics)
            } catch (t: Throwable) {
                val dm = context.resources.displayMetrics
                metrics.setTo(dm)
            }
        }

        if (metrics.widthPixels == 0 || metrics.heightPixels == 0) {
            val dm = context.resources.displayMetrics
            metrics.setTo(dm)
        }

        return metrics
    }

    /**
     * The width actually usable for rendering an overlay window — the raw
     * display width from [getDisplayMetrics] minus the left+right system bar
     * / display cutout insets. getDisplayMetrics() reports the full physical
     * width including areas an overlay window can't necessarily render into
     * (rounded corners, cutouts, side navigation bars on some devices);
     * width calculations meant to fit *within* the visible screen — like the
     * round dock's CENTER_HORIZONTAL sizing — should use this instead, or
     * they can end up wider than what's actually visible and appear to
     * overflow the screen edge.
     */
    fun getUsableDisplayWidth(context: Context, displayId: Int = Display.DEFAULT_DISPLAY): Int {
        val metrics = getDisplayMetrics(context, displayId)
        // FIX: currentWindowMetrics/WindowInsets.Type are API 30+; on this
        // project's minSdk 26, calling them on an older device throws
        // NoSuchMethodError/NoClassDefFoundError — an Error, not an
        // Exception, so a plain catch(Exception) would NOT have caught it
        // and this would crash instead of falling back.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return metrics.widthPixels
        return try {
            val displayContext = if (displayId == Display.DEFAULT_DISPLAY) {
                context
            } else {
                val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                val targetDisplay = displayManager.getDisplay(displayId)
                if (targetDisplay != null) context.createDisplayContext(targetDisplay) else context
            }
            val wm = displayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val insets = wm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout()
            )
            (metrics.widthPixels - insets.left - insets.right).coerceAtLeast(1)
        } catch (e: Throwable) {
            metrics.widthPixels
        }
    }

    fun getDisplayContext(context: Context, secondary: Boolean = false): Context {
        if (!secondary) return context
        val secondaryDisplay = getSecondaryDisplay(context)
        return if (secondaryDisplay != null) context.createDisplayContext(secondaryDisplay) else context
    }

    @SuppressLint("PrivateApi")
    fun getSystemProp(prop: String): String {
        val systemPropertiesClass = Class.forName("android.os.SystemProperties")
        val getMethod = systemPropertiesClass.getMethod("get", String::class.java)

        return getMethod.invoke(null, prop) as String
    }

    fun isBliss(): Boolean {
        return getSystemProp("ro.bliss.version").isNotEmpty()
    }

    fun shouldApplyNavbarFix(): Boolean {
        return Build.VERSION.SDK_INT > 31 && isNavbarEnabled()
    }

    fun isNavbarEnabled(): Boolean {
        return getSystemProp("qemu.hw.mainkeys") != "1"
    }

    //Permissions
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        for (enabledService in enabledServices) {
            val serviceInfo = enabledService.resolveInfo.serviceInfo
            if (serviceInfo.packageName == context.packageName && serviceInfo.name == DockService::class.java.name) {
                return true
            }
        }
        return false
    }

    fun hasStoragePermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun requestStoragePermissions(context: Activity) {
        ActivityCompat.requestPermissions(
            context,
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
            8
        )
    }

    fun hasWriteSettingsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED
    }

    // MANAGE_EXTERNAL_STORAGE (All Files Access)
    fun hasManageExternalStorage(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
        android.os.Environment.isExternalStorageManager()

    fun requestManageExternalStorage(context: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = ("package:" + context.packageName).toUri()
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    // POST_NOTIFICATIONS (Android 13+)
    fun hasPostNotificationsPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * FIX (Discord report — "the button does nothing"): once a runtime
     * permission (POST_NOTIFICATIONS, BLUETOOTH_CONNECT/SCAN,
     * READ_MEDIA_IMAGES/VIDEO) has been denied with "Don't ask again" (or
     * denied twice on newer Android versions), the system silently refuses
     * to show the permission dialog again — ActivityCompat.requestPermissions
     * just immediately calls back as denied with no UI at all. From the
     * user's side that looks exactly like "I tapped Grant and nothing
     * happened".
     *
     * shouldShowRequestPermissionRationale() alone can't tell "never asked
     * yet" apart from "permanently denied" — it returns false for BOTH. So
     * we track our own "have we ever asked for this one" flag in prefs:
     * the very first time, we go ahead and call requestPermissions (rationale
     * being false is expected and fine here). On any later call, if it's
     * still not granted and rationale is (still) false, that combination
     * can only mean permanently denied — so route to the app's own System
     * Settings → Permissions page instead, the only place left that can
     * actually grant it.
     */
    private fun requestRuntimePermissionOrOpenSettings(
        activity: Activity, permissions: Array<String>, requestCode: Int, prefsKey: String
    ) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        val alreadyAsked = prefs.getBoolean(prefsKey, false)
        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
        }
        val canShowRationale = permissions.any {
            ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
        }
        if (alreadyAsked && !allGranted && !canShowRationale) {
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = ("package:" + activity.packageName).toUri()
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            return
        }
        prefs.edit().putBoolean(prefsKey, true).apply()
        ActivityCompat.requestPermissions(activity, permissions, requestCode)
    }

    fun requestPostNotifications(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestRuntimePermissionOrOpenSettings(
                activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 42, "asked_post_notifications"
            )
        }
    }

    // BLUETOOTH_CONNECT (Android 12+)
    fun hasBluetoothPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED

    fun requestBluetoothPermissions(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestRuntimePermissionOrOpenSettings(
                activity,
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN),
                43, "asked_bluetooth"
            )
        }
    }

    // WRITE_SETTINGS (Modify system settings)
    fun hasWriteSystemSettings(context: Context): Boolean =
        Settings.System.canWrite(context)

    fun requestWriteSystemSettings(context: Activity) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = ("package:" + context.packageName).toUri()
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    // READ_MEDIA (Android 13+)
    fun hasReadMediaPermissions(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    fun requestReadMediaPermissions(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestRuntimePermissionOrOpenSettings(
                activity,
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO),
                44, "asked_read_media"
            )
        } else {
            requestRuntimePermissionOrOpenSettings(
                activity, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 44, "asked_read_media"
            )
        }
    }

    // Root Access
    fun isRootAvailable(context: Context): Boolean =
        RootManager.getInstance(context).isAvailable

    fun grantPermission(permission: String): Boolean {
        val result = runAsRoot("pm grant com.youki.dex $permission")
        return result.isEmpty()
    }

    fun grantOverlayPermissions(context: Activity) {
        context.startActivityForResult(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                ("package:" + context.packageName).toUri()
            ),
            8
        )
    }

    // ─────────────────────────────────────────────────────────────
    //  Device Admin
    // ─────────────────────────────────────────────────────────────

    fun requestDeviceAdminPermissions(context: Activity) {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        intent.putExtra(
            DevicePolicyManager.EXTRA_DEVICE_ADMIN,
            ComponentName(context, com.youki.dex.DeviceAdminReceiver::class.java)
        )
        context.startActivityForResult(intent, 10)
    }

    fun isDeviceAdminEnabled(context: Context): Boolean {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(context, com.youki.dex.DeviceAdminReceiver::class.java)
            dpm.isAdminActive(admin)
        } catch (e: Exception) { false }
    }

    fun hasRecentAppsPermission(context: Context): Boolean {
        return AppUtils.isSystemApp(context, context.packageName) || checkAppOpsPermission(
            context,
            AppOpsManager.OPSTR_GET_USAGE_STATS
        )
    }

    private fun checkAppOpsPermission(context: Context, permission: String): Boolean {
        val packageManager = context.packageManager
        val applicationInfo: ApplicationInfo = try {
            packageManager.getApplicationInfo(context.packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            return false
        }
        val appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOpsManager.checkOpNoThrow(
            permission,
            applicationInfo.uid,
            applicationInfo.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    //Service control
    fun enableService(context: Context) {
        val services = getSecureSetting(context, ENABLED_ACCESSIBILITY_SERVICES, "")
        if (!services.contains(SERVICE_NAME)) {
            val newServices: String =
                if (services.isEmpty()) SERVICE_NAME else "$services:$SERVICE_NAME"
            putSecureSetting(context, ENABLED_ACCESSIBILITY_SERVICES, newServices)
        }
        // FIX: appending to enabled_accessibility_services alone isn't enough —
        // the master accessibility_enabled toggle also has to be 1, or the whole
        // accessibility subsystem stays off and onServiceConnected() never fires.
        // This was the actual reason the shortcut→LauncherActivity path silently
        // fell through to routeToAccessibilitySettings() ("اللفة الطويلة") even
        // right after writing the service name — this call alone used to look
        // successful but the service never actually bound.
        putSecureSetting(context, "accessibility_enabled", "1")
    }

    fun disableService(context: Context) {
        val services = getSecureSetting(context, ENABLED_ACCESSIBILITY_SERVICES, "")
        if (!services.contains(SERVICE_NAME)) return
        var newServices = ""
        if (services.contains("$SERVICE_NAME:")) newServices = services.replace(
            "$SERVICE_NAME:",
            ""
        ) else if (services.contains(":$SERVICE_NAME")) newServices = services.replace(
            ":$SERVICE_NAME",
            ""
        ) else if (services.contains(SERVICE_NAME)) newServices = services.replace(SERVICE_NAME, "")
        putSecureSetting(context, ENABLED_ACCESSIBILITY_SERVICES, newServices)
    }

    /**
     * Restarts DockService programmatically — disable then re-enable, with a
     * short delay in between so Android's accessibility subsystem actually
     * tears the old binding down before the new one is requested (going
     * straight from disable to enable in the same tick can be a no-op on
     * some ROMs since the write hasn't propagated yet). No trip through
     * Android Settings needed; DockService's own DOCK_SERVICE_CONNECTED
     * broadcast (see PerfectServer.onServiceConnected) tells any listening
     * screen (e.g. LauncherActivity) the instant it's back up.
     */
    fun restartServiceProgrammatically(context: Context, onDone: (() -> Unit)? = null) {
        disableService(context)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            enableService(context)
            onDone?.invoke()
        }, 400)
    }

    fun canDrawOverOtherApps(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    @Suppress("DEPRECATION")
    fun isServiceRunning(context: Context, serviceName: Class<*>): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in activityManager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceName.name == service.service.className) {
                return true
            }
        }
        return false
    }

    fun getSettingsOverlaysAllowed(context: Context): Boolean {
        return getSecureSetting(context, SETTING_OVERLAYS, 0) == 1
    }
}
