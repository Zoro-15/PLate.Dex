package com.plate.launcher.utils

import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.Notification
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.UserManager
import android.view.Display
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import com.plate.launcher.R
import com.plate.launcher.models.App
import com.plate.launcher.models.AppTask
import com.plate.launcher.models.DockApp
import java.io.File

object AppUtils {
    const val PINNED_LIST = "pinned.lst"
    const val DOCK_PINNED_LIST = "dock_pinned.lst"
    const val DESKTOP_LIST = "desktop.lst"
    @Volatile var currentApp = ""

    /**
     * Which packages are genuinely alive right now (foreground or
     * background), as of the last dock refresh — Shizuku-only (via
     * ShizukoManager.getRunningPackages(), a real dumpsys-backed live-process
     * check), null whenever Shizuku detection is off/unavailable. Distinct
     * from [currentApp] (a single package — whichever one is foreground) and
     * from the dock's own task list (a 24h *usage history*, not a liveness
     * signal — see getRecentTasks's kdoc): this is the set DockAppAdapter
     * checks membership against to decide whether an icon gets no indicator,
     * a background dot, or (if it's also [currentApp]) the foreground line.
     * A recently-used-but-now-closed app still gets an icon in the dock, it
     * just won't be in this set, so it draws no indicator at all.
     */
    @Volatile var trulyRunningPackages: Set<String>? = null

    /** Sorts [apps] by [App.name], case-sensitively. */
    private fun sortAppsByNameCaseSensitive(apps: List<App>): List<App> =
        apps.sortedBy { it.name }

    // Unbounded Memory Growth — fixed below: this used to be a plain ConcurrentHashMap
    // with no upper limit — on a device with 300 apps, every icon at
    // DENSITY_XXXHIGH resolution can reach tens of KB, so the total
    // accumulates to tens of MB unnecessarily. Converting it to an LruCache
    // with a 200-item cap protects things even if some other place forgets to
    // call clearIconCache() when packages change in the future — this is an
    // extra defense on top of invalidateAppsCache().
    private val iconCache = object : android.util.LruCache<String, Drawable>(200) {}
    fun clearIconCache() = iconCache.evictAll()
    fun getInstalledPackages(context: Context): List<App> {
        val apps = ArrayList<App>()
        val packages = context.packageManager.getInstalledPackages(0)
        packages.forEach { packageInfo ->
            // Gap 30: applicationInfo can be null on Android 13+ for some packages
            val appInfo = packageInfo.applicationInfo ?: return@forEach
            try {
                apps.add(App(
                    appInfo.loadLabel(context.packageManager).toString(),
                    appInfo.packageName,
                    appInfo.loadIcon(context.packageManager)
                ))
            } catch (e: Exception) {}
        }
        return sortAppsByNameCaseSensitive(apps)
    }

    fun getInstalledApps(context: Context): ArrayList<App> {
        val apps = ArrayList<App>()
        val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        var appsInfo = mutableListOf<LauncherActivityInfo>()
        for (profile in userManager.userProfiles) appsInfo.addAll(
            launcherApps.getActivityList(
                null,
                profile
            )
        )

        appsInfo = appsInfo.sortedWith(compareBy { it.label.toString() }).toMutableList()

        for (appInfo in appsInfo) {
            val pkg = appInfo.componentName.packageName
            val icon = iconCache.get(pkg) ?: appInfo.getIcon(android.util.DisplayMetrics.DENSITY_XXXHIGH).also {
                iconCache.put(pkg, it)
            }
            apps.add(App(appInfo.label.toString(), pkg, icon, appInfo.componentName, appInfo.user))
        }
        return apps
    }

    fun getPinnedApps(context: Context, type: String): ArrayList<App> {
        val file = File(context.filesDir, type)
        val apps = ArrayList<App>()
        val appsInfo = mutableListOf<LauncherActivityInfo>()
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
        if (file.exists()) {
            for (line in file.readLines()) {
                if (line.isBlank()) continue
                val info = line.split(" ")
                if (info.size < 2) continue
                val packageName = info[0]
                val serial = info[1].toLongOrNull() ?: continue
                val userHandle = userManager.getUserForSerialNumber(serial)
                val list = launcherApps.getActivityList(packageName, userHandle)
                if (list.isNullOrEmpty()) unpinApp(context, packageName, type)
                appsInfo.addAll(list)
            }
        }

        for (appInfo in appsInfo) {
            apps.add(
                App(
                    appInfo.label.toString(),
                    appInfo.componentName.packageName,
                    appInfo.getIcon(android.util.DisplayMetrics.DENSITY_XXXHIGH),
                    appInfo.componentName,
                    appInfo.user
                )
            )
        }
        return apps
    }

    fun pinApp(context: Context, app: App, type: String) {
        val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
        val file = File(context.filesDir, type)
        file.appendText("${app.packageName} ${userManager.getSerialNumberForUser(app.userHandle)}\n")
    }

    fun unpinApp(context: Context, packageName: String, type: String) {
        val file = File(context.filesDir, type)
        val updatedList = file.readLines().filter { it.split(" ")[0] != packageName }
        if (updatedList.isNotEmpty()) file.writeText(updatedList.joinToString("\n") + "\n")
        else {
            file.writeText("")
        }
    }

    fun moveApp(context: Context, app: App, type: String, direction: Int) {
        val file = File(context.filesDir, type)
        val lines = file.readLines().toMutableList()

        val lineIndex = lines.indexOfFirst { it.split(" ")[0] == app.packageName }

        if (lineIndex != -1) {
            if (direction == 0 && lineIndex > 0) {
                val line = lines.removeAt(lineIndex)
                lines.add(lineIndex - 1, line)
            } else if (direction == 1 && lineIndex < lines.size - 1) {
                val line = lines.removeAt(lineIndex)
                lines.add(lineIndex + 1, line)
            }

            file.writeText(lines.joinToString("\n") + "\n")
        }
    }

    fun isPinned(context: Context, app: App, type: String): Boolean {
        val file = File(context.filesDir, type)
        if (!file.exists()) return false
        file.readLines().forEach { line ->
            if (line.split(" ")[0] == app.packageName) return true
        }
        return false
    }

    fun isGame(packageManager: PackageManager, packageName: String): Boolean {
        return try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                info.category == ApplicationInfo.CATEGORY_GAME
            } else {
                info.flags and ApplicationInfo.FLAG_IS_GAME == ApplicationInfo.FLAG_IS_GAME
            }
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun getCurrentLauncher(packageManager: PackageManager): String {
        val intent = Intent(Intent.ACTION_MAIN)
        intent.addCategory(Intent.CATEGORY_HOME)
        val resolveInfo = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolveInfo?.activityInfo?.packageName ?: ""
    }

    fun setWindowMode(activityManager: ActivityManager, taskId: Int, mode: Int) {
        try {
            val setWindowMode = activityManager.javaClass.getMethod(
                "setTaskWindowingMode",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType
            )
            setWindowMode.invoke(activityManager, taskId, mode, false)
        } catch (e: Exception) {
        }
    }

    fun getRunningTasks(
        activityManager: ActivityManager, packageManager: PackageManager, max: Int
    ): ArrayList<AppTask> {
        val tasksInfo = activityManager.getRunningTasks(max)
        if (tasksInfo.isNullOrEmpty()) return ArrayList()
        currentApp = tasksInfo[0].baseActivity?.packageName ?: ""
        val launcherPkg = getCurrentLauncher(packageManager)

        val appTasks = ArrayList<AppTask>()
        for (taskInfo in tasksInfo) {
            try {
                val basePkg = taskInfo.baseActivity?.packageName ?: continue
                val topActivity = taskInfo.topActivity ?: continue

                if (basePkg.contains("com.android.systemui") ||
                    basePkg.contains("com.google.android.packageinstaller") ||
                    topActivity.className == "com.android.quickstep.RecentsActivity") continue

                if (topActivity.className != "com.plate.launcher.activities.MainActivity" &&
                    topActivity.className != "com.plate.launcher.activities.DebugActivity" &&
                    topActivity.packageName == launcherPkg) continue

                appTasks.add(AppTask(
                    taskInfo.id,
                    packageManager.getActivityInfo(topActivity, 0).loadLabel(packageManager).toString(),
                    topActivity.packageName,
                    packageManager.getActivityIcon(topActivity)
                ))
            } catch (e: PackageManager.NameNotFoundException) {}
        }
        return appTasks
    }

    fun getRecentTasks(context: Context, max: Int): ArrayList<AppTask> {
        val ignoredApps =
            listOf<String>(context.packageName, getCurrentLauncher(context.packageManager))

        // FIX v2: the previous issue was that IMPORTANCE_CACHED (400) filters
        // out many apps on modern Android — especially when memory is low or
        // the device is pressuring processes. Fix: we use UsageStats directly
        // as the single source for ordering with no runningProcesses filter,
        // because UsageStats keeps the last-used time even if the app was
        // stopped from memory. This means the dock shows "recently used apps"
        // instead of "only currently running" — which is the correct behavior
        // for a DEX launcher.

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        // Time window: the last 24 hours — enough to cover daily usage sessions
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 24 * 60 * 60 * 1000L

        val usageStats = usm.queryUsageStats(
            UsageStatsManager.INTERVAL_BEST, startTime, endTime
        )
            // NOTE: intentionally a plain Kotlin sortedWith rather than any
            // native/JNI round-trip — this sorts full android.app.usage.UsageStats
            // objects directly (not an (id, value) pair list), immediately
            // followed by two more sortedWith-chained filters over the
            // same objects. A native round-trip would mean extracting
            // (lastTimeUsed, opaque_id) pairs, sorting there, then
            // re-associating ids back to UsageStats objects here anyway —
            // overhead for a sort over a small (~day's worth of) list, with
            // no computational logic actually saved.
            .sortedWith(compareByDescending { it.lastTimeUsed })
            .filterNot { ignoredApps.contains(it.packageName) }
            // Exclude system apps with no UI (they don't show up in the launcher)
            .filter { stat ->
                try { isLaunchable(context, stat.packageName) } catch (e: Exception) { false }
            }

        val appTasks = ArrayList<AppTask>()

        // Update currentApp from the most recent app in UsageStats
        if (usageStats.isNotEmpty())
            currentApp = usageStats[0].packageName

        for (stat in usageStats) {
            val app = stat.packageName
            try {
                appTasks.add(
                    AppTask(
                        -1,
                        getPackageLabel(context, app),
                        app,
                        context.packageManager.getApplicationIcon(app)
                    )
                )
            } catch (e: PackageManager.NameNotFoundException) {
                // The app was removed — ignore it
            }
            if (appTasks.size >= max) break
        }
        return appTasks
    }

    fun isSystemApp(context: Context, app: String): Boolean {
        return try {
            val appInfo = context.packageManager.getApplicationInfo(app, 0)
            appInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun isLaunchable(context: Context, app: String): Boolean {
        val resolveInfo = context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(app), 0
        )
        return resolveInfo.isNotEmpty()
    }

    fun getPackageLabel(context: Context, packageName: String): String {
        try {
            val packageManager = context.packageManager
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            return packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
        }
        return ""
    }

    fun getAppIcon(context: Context, app: String): Drawable {
        return try {
            context.packageManager.getApplicationIcon(app)
        } catch (e: PackageManager.NameNotFoundException) {
            AppCompatResources.getDrawable(context, android.R.drawable.sym_def_app_icon)!!
        }
    }

    /**
     * Gathers the Framework-derived inputs (display metrics, status/nav
     * bar heights, scale factor preference) and computes the launch
     * bounds rect for the given windowing [mode].
     */
    private fun makeLaunchBounds(
        context: Context, mode: String, dockHeight: Int, displayId: Int = Display.DEFAULT_DISPLAY
    ): Rect {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        var left = 0
        var top = 0
        var right = 0
        var bottom = 0
        // Fix 19: only one call to getDisplayMetrics
        val dm           = DeviceUtils.getDisplayMetrics(context, displayId)
        val deviceWidth  = dm.widthPixels
        val deviceHeight = dm.heightPixels
        // Fix 20: only one call to getStatusBarHeight
        val statusHeight = DeviceUtils.getStatusBarHeight(context)
        val navHeight    = DeviceUtils.getNavBarHeight(context)
        val diff         = (dockHeight - navHeight).coerceAtLeast(0)
        val usableHeight = if (DeviceUtils.shouldApplyNavbarFix())
            deviceHeight - diff - statusHeight
        else deviceHeight - dockHeight - statusHeight
        val scaleFactor = sharedPreferences.getString("scale_factor", "1.0")!!.toFloat()
        when (mode) {
            "standard" -> {
                left = (deviceWidth / (5 * scaleFactor)).toInt()
                top = ((usableHeight + statusHeight) / (7 * scaleFactor)).toInt()
                right = deviceWidth - left
                bottom = usableHeight + dockHeight - top
            }

            "maximized" -> {
                right = deviceWidth
                bottom = usableHeight
            }

            "portrait" -> {
                left = deviceWidth / 3
                top = usableHeight / 15
                right = deviceWidth - left
                bottom = usableHeight + dockHeight - top
            }

            "tiled-left" -> {
                right = deviceWidth / 2
                bottom = usableHeight
            }

            "tiled-top" -> {
                right = deviceWidth
                bottom = (usableHeight + statusHeight) / 2
            }

            "tiled-right" -> {
                left = deviceWidth / 2
                right = deviceWidth
                bottom = usableHeight
            }

            "tiled-bottom" -> {
                right = deviceWidth
                top = (usableHeight + statusHeight) / 2
                bottom = usableHeight + statusHeight
            }
        }
        return Rect(left, top, right, bottom)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════════════
    // WINDOWING MODE — Android 8 (API 26) to Android 16 (API 36)
    // ═══════════════════════════════════════════════════════════════════════
    // Google did not remove FREEFORM mode (5) in Android 16 — it still exists.
    // The issue: the way to request it changed across API levels:
    //
    //   API 26-27 → setLaunchStackId(2)        [Stack-based, pre-Pie]
    //   API 28-35 → setLaunchWindowingMode(5)   [reflection on public method]
    //   API 36    → same method but Google added setLaunchAdjacentFlagOverride
    //               as an additional hint. setLaunchBounds still works.
    //
    // No Shizuku needed — we try each approach in order until one succeeds.
    // ═══════════════════════════════════════════════════════════════════════

    private const val WINDOWING_MODE_FULLSCREEN = 1
    private const val WINDOWING_MODE_FREEFORM   = 5

    fun makeActivityOptions(
        context: Context, mode: String, dockHeight: Int, displayId: Int
    ): ActivityOptions {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        val secondary = sp.getBoolean("prefer_last_display", false)
        val display = when {
            displayId != Display.DEFAULT_DISPLAY -> displayId
            secondary -> DeviceUtils.getSecondaryDisplay(context)?.displayId ?: displayId
            else -> displayId
        }

        val options = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= 26) options.setLaunchDisplayId(display)

        // FULLSCREEN — simple, works on all API levels
        if (mode == "fullscreen") {
            setWindowingMode(options, WINDOWING_MODE_FULLSCREEN)
            return options
        }

        // HarmonyOS 5+: freeform windowing is not supported — return basic options with bounds only
        if (DeviceUtils.isPureHarmonyOS()) {
            return options
        }

        // FREEFORM — setLaunchBounds must be called before setWindowingMode on all API levels
        val bounds = makeLaunchBounds(context, mode, dockHeight, display)
        options.setLaunchBounds(bounds)
        setWindowingMode(options, WINDOWING_MODE_FREEFORM)

        // NOTE: setWindowingMode(options, WINDOWING_MODE_FREEFORM) was already called
        // above (right after setLaunchBounds) — it already tries the exact same
        // "setLaunchWindowingMode" call via reflection (see the setWindowingMode()
        // helper below), which is the correct approach: ActivityOptions#setLaunchWindowingMode(int)
        // is a hidden/@SystemApi method, not part of the public SDK, so calling it
        // directly (as a previous version of this code did here) fails to compile.
        // No second attempt is needed — reflection already covers API 28+.

        // Android 16 (API 36): Google added setLaunchAdjacentFlagOverride
        // as an additional hint to the system to open the window in floating mode
        if (Build.VERSION.SDK_INT >= 36) {
            try {
                val m = ActivityOptions::class.java.getDeclaredMethod(
                    "setLaunchAdjacentFlagOverride", Boolean::class.javaPrimitiveType)
                m.isAccessible = true
                m.invoke(options, true)
            } catch (e: Exception) {}
            // Android 16: force freeform via hidden flag as extra guarantee
            try {
                val f = ActivityOptions::class.java.getDeclaredField("mLaunchWindowingMode")
                f.isAccessible = true
                f.set(options, WINDOWING_MODE_FREEFORM)
            } catch (e: Exception) {}
        }

        return options
    }

    /**
     * Opens a URL in the user's default browser.
     * Centralizes what used to be duplicated per-fragment startActivity(ACTION_VIEW)
     * calls (GitHub links, Discord, docs, wiki, "open in browser"...). None of the
     * duplicates had a try/catch, so on any device without a browser app — locked-down
     * kiosk devices, some enterprise ROMs, or an install where the user removed every
     * browser — tapping any of those buttons (including the crash-report/"help" links
     * in DebugActivity, ironically) would crash the app instead of just failing quietly.
     */
    fun openUrl(context: Context, url: String) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: android.content.ActivityNotFoundException) {
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.no_browser_available),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Stops the DEX dock service (broadcasts DOCK_SERVICE_ACTION with
     * "disable_self", handled by DockService) and switches to the device's
     * next-available default launcher, if there is one other than this app
     * itself.
     *
     * Extracted from two identical copies: PerfectServer.kt's
     * DockTileService.onClick (Quick Settings tile) and
     * ShortcutLauncherActivity — the latter's own comment said outright
     * "same logic as disable_self in DockTileService", i.e. the duplication
     * was already known, just never consolidated.
     *
     * [launchOther] is how the caller starts the other launcher's home
     * Intent — PerfectServer's caller uses its own launchActivity() (which
     * adds freeform ActivityOptions), ShortcutLauncherActivity used a plain
     * startActivity(); passing it in keeps that difference instead of
     * silently changing either caller's behavior.
     */
    fun stopDexAndLaunchOtherHome(context: Context, launchOther: (Intent) -> Unit) {
        DeviceUtils.freezeRotation(false)
        context.sendBroadcast(
            Intent(com.plate.launcher.services.DOCK_SERVICE_ACTION)
                .setPackage(context.packageName)
                .putExtra("action", "disable_self")
        )
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val target = context.packageManager.queryIntentActivities(homeIntent, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .firstOrNull()
        if (target != null) {
            launchOther(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setPackage(target.activityInfo.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }
    }

    /**
     * True if [packageName] already has a running process — used to detect
     * a cold start (no existing process, first-ever launch or fully killed)
     * vs. bringing an already-running app back to front. See
     * PerfectServer.launchApp's own comment on why this distinction matters:
     * ActivityOptions.setLaunchBounds()/setLaunchWindowingMode() are
     * unreliable on a genuine cold start specifically — the system creates
     * the new Task at a default size before the requested freeform bounds
     * reliably take effect, which is why a first-ever app launch can open
     * at roughly half-screen even with "Maximized" selected as the default,
     * while relaunching the same (already-running) app afterward opens at
     * the correct size — see GitHub issue "app launching maximized" (the
     * bug report that led to this function existing).
     */
    fun isAppProcessRunning(context: Context, packageName: String): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return activityManager.runningAppProcesses?.any { it.processName == packageName } ?: false
    }

    /**
     * Opens a system settings screen (or any other system settings Intent) as a freeform window
     * instead of full-screen — used to be inconsistent across the project:
     * dock buttons (PerfectServer, via launchApp) already went through
     * makeActivityOptions() and opened freeform correctly, but the same
     * settings screens opened from MainActivity/LauncherActivity/
     * ShortcutLauncherActivity/preference fragments called startActivity()
     * directly with no ActivityOptions at all, so they always launched
     * full-screen regardless of the user's dock/DEX windowing preference.
     *
     * Any Activity or Service can call this the same way — it only needs a
     * Context (dockHeight defaults to 0, which is correct for anything that
     * isn't the dock itself; the dock passes its real height explicitly).
     */
    @JvmOverloads
    /**
     * FIX (Discord/user report — "the permission buttons don't go anywhere"):
     * this used to launch every Settings.ACTION_* screen (Accessibility,
     * Notification Listener, App Details, Display, etc.) inside a freeform
     * floating window via makeActivityOptions(context, "freeform", ...) —
     * the same windowing mode used for regular apps opened from the dock.
     * System Settings screens are not regular apps: several OEM ROMs
     * (Samsung One UI, MIUI, HarmonyOS, and others) specifically reject or
     * silently reposition/ignore forced freeform bounds for the Settings
     * package, especially for security-sensitive screens like Accessibility
     * and Notification Listener — the launch either silently no-ops, opens
     * off-screen, or gets pushed behind the dock's own overlay window with
     * no visible error. From the user's side this looks exactly like
     * "I tapped the button and nothing happened", even though the button,
     * the click listener, and the Intent were all correct.
     *
     * Settings screens should always be given the full screen to render
     * predictably on every device, so this now unconditionally launches
     * fullscreen — dockHeight/displayId are still respected for which
     * display to use, just not for freeform bounds.
     */
    fun openSystemSettings(
        context: Context,
        intent: Intent,
        dockHeight: Int = 0,
        displayId: Int = Display.DEFAULT_DISPLAY
    ) {
        val launchIntent = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = makeActivityOptions(context, "fullscreen", dockHeight, displayId)
        // Not every Settings.ACTION_* screen exists on every OEM skin/ROM
        // (customized MIUI/EMUI/budget-device builds are the common case
        // that drops one of these) — this single function fans out to ~19
        // call sites across the whole app (accessibility, notification
        // access, display, sound, airplane mode, etc.), so an unguarded
        // ActivityNotFoundException here could crash the app from almost
        // any settings button in the UI, not just one screen.
        //
        // GitHub issue #15: some external displays (e.g. USB-C portable
        // touch monitors) reject launchDisplayId with a SecurityException
        // ("Permission Denial: ... with launchDisplayId=N") because the app
        // isn't allowed to place activities on that particular display —
        // this varies by device/monitor and isn't something we can detect
        // in advance. Previously this crashed the whole launcher. Now: on
        // SecurityException, retry once with plain launchDisplayId-less
        // options (falls back to the default display) instead of crashing.
        try {
            context.startActivity(launchIntent, options.toBundle())
        } catch (e: android.content.ActivityNotFoundException) {
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.settings_screen_unavailable),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        } catch (e: SecurityException) {
            try {
                val fallbackOptions = makeActivityOptions(context, "fullscreen", dockHeight, Display.DEFAULT_DISPLAY)
                context.startActivity(launchIntent, fallbackOptions.toBundle())
            } catch (e2: Exception) {
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.settings_screen_unavailable),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * Sets the windowing mode — tries all available approaches in order:
     *
     *   1. setLaunchStackId(2)              → API 26-27
     *   2. getMethod("setLaunchWindowingMode") → API 28+ public
     *   3. getDeclaredMethod(...)              → API 28+ package-private
     *   4. field "mWindowingMode" directly      → last resort on Android 16
     */
    private fun setWindowingMode(options: ActivityOptions, mode: Int) {
        if (Build.VERSION.SDK_INT < 28) {
            try {
                val m = ActivityOptions::class.java.getMethod(
                    "setLaunchStackId", Int::class.javaPrimitiveType)
                m.invoke(options, if (mode == WINDOWING_MODE_FREEFORM) 2 else 1)
            } catch (e: Exception) {}
            return
        }
        // Attempt 1: public getMethod — most compatible, API 28-35
        try {
            val m = ActivityOptions::class.java.getMethod(
                "setLaunchWindowingMode", Int::class.javaPrimitiveType)
            m.invoke(options, mode)
            return
        } catch (e: Exception) {}
        // Attempt 2: getDeclaredMethod — bypasses package-private
        try {
            val m = ActivityOptions::class.java.getDeclaredMethod(
                "setLaunchWindowingMode", Int::class.javaPrimitiveType)
            m.isAccessible = true
            m.invoke(options, mode)
            return
        } catch (e: Exception) {}
        // Attempt 3: internal field — last resort on some OEMs and Android 16
        for (name in listOf("mWindowingMode", "mLaunchWindowingMode", "windowingMode")) {
            try {
                val f = ActivityOptions::class.java.getDeclaredField(name)
                f.isAccessible = true
                f.set(options, mode)
                return
            } catch (e: Exception) {}
        }
    }

    fun buildShellLaunchCommand(
        context: Context, packageName: String,
        mode: String, dockHeight: Int, displayId: Int
    ): String {
        val bounds = makeLaunchBounds(context, mode, dockHeight, displayId)
        val wm = if (mode == "fullscreen") WINDOWING_MODE_FULLSCREEN else WINDOWING_MODE_FREEFORM
        val b = "${bounds.left} ${bounds.top} ${bounds.right} ${bounds.bottom}"
        val component = context.packageManager
            .getLaunchIntentForPackage(packageName)
            ?.component?.flattenToString() ?: return ""
        return "am start -n $component --windowingMode $wm --display $displayId --launch-bounds \"$b\""
    }

    fun resizeTask(context: Context, mode: String, taskId: Int, dockHeight: Int) {
        if (taskId < 0) return
        val bounds    = makeLaunchBounds(context, mode, dockHeight)
        val modeCmd   = "am task set-windowing-mode $taskId 5"
        val resizeCmd = "am task resize $taskId ${bounds.left} ${bounds.top} ${bounds.right} ${bounds.bottom}"

        // Gap 16: we read stderr instead of the unreliable exitValue()
        try {
            Runtime.getRuntime().exec(arrayOf("sh", "-c", modeCmd)).waitFor()
            Thread.sleep(150)
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", resizeCmd))
            p.waitFor()
            val stderr = p.errorStream.bufferedReader().readText().trim()
            if (stderr.isEmpty()) return
        } catch (e: Exception) {}


        try {
            val shizuku = ShizukoManager.getInstance(context)
            if (shizuku.hasPermission) {
                shizuku.runShellSync(modeCmd)
                Thread.sleep(200)
                shizuku.runShellSync(resizeCmd)
                return
            }
        } catch (e: Exception) {}

        DeviceUtils.runAsRoot(modeCmd)
        Thread.sleep(150)
        DeviceUtils.runAsRoot(resizeCmd)
    }

    /**
     * Fully closes (kills, not minimizes) the task identified by [taskId].
     * Discord report ("what about closing apps? ... I want to disable them
     * so when I switch back to my phone launcher I don't have to switch
     * them back"): the only "close" behavior previously available moved a
     * window off-screen (minimize) rather than ending the task, so
     * switching to the phone's own launcher still left every "closed" app
     * running in the background. Same three-tier shell fallback pattern
     * as resizeTask above (plain shell → ShellManager → Shizuku → root),
     * since removing another app's task the same way needs elevated
     * access — `am task remove` targeting a task this app doesn't own is
     * not something a normal app-level ActivityManager.removeTask() call
     * can do without a signature-level permission (see this project's own
     * SecurityException fix for the same reasoning around
     * setLaunchDisplayId, in PerfectServer.launchApp).
     */
    fun closeTask(context: Context, taskId: Int) {
        if (taskId < 0) return
        val cmd = "am task remove $taskId"

        try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            p.waitFor()
            if (p.exitValue() == 0) return
        } catch (e: Exception) {}


        try {
            val shizuku = ShizukoManager.getInstance(context)
            if (shizuku.hasPermission) {
                shizuku.runShellSync(cmd)
                return
            }
        } catch (e: Exception) {}

        DeviceUtils.runAsRoot(cmd)
    }

    private fun execShizukuSync(shizuku: ShizukoManager, cmd: String) {
        try {
            val m = rikka.shizuku.Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
            ).also { it.isAccessible = true }
            val proc = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as rikka.shizuku.ShizukuRemoteProcess
            proc.inputStream.bufferedReader().readText()
            proc.errorStream.bufferedReader().readText()
            proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) {}
    }

    fun containsTask(apps: ArrayList<DockApp>, task: AppTask): Int {
        for (i in apps.indices) {
            if (apps[i].packageName == task.packageName) return i
        }
        return -1
    }

    fun isMediaNotification(notification: Notification): Boolean {
        // Gap 83: extras can be null on old notifications
        return try {
            notification.extras?.get(Notification.EXTRA_TEMPLATE)
                ?.toString() == "android.app.Notification\$MediaStyle"
        } catch (e: Exception) { false }
    }

    fun uninstallApp(context: Context, packageName: String) {
        if (isSystemApp(context, packageName))
            DeviceUtils.runAsRoot("pm uninstall --user 0 $packageName")
        else context.startActivity(
            Intent(
                Intent.ACTION_UNINSTALL_PACKAGE,
                "package:$packageName".toUri()
            )
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    //  Dock Size Presets 
    data class DockSizeConfig(
        val dockHeightDp: Int,
        val iconSizeDp: Int,
        val gridSizeDp: Int,
 /** true (PC mode — ) */
        val useSystemDensity: Boolean
    ) {
        fun toHeightPx(context: Context) =
            if (useSystemDensity) Utils.dpToPxSystem(dockHeightDp)
            else Utils.dpToPx(context, dockHeightDp)

        fun toGridSizePx(context: Context) =
            if (useSystemDensity) Utils.dpToPxSystem(gridSizeDp)
            else Utils.dpToPx(context, gridSizeDp)

        fun toIconSizePx(context: Context) =
            if (useSystemDensity) Utils.dpToPxSystem(iconSizeDp)
            else Utils.dpToPx(context, iconSizeDp)
    }

    fun getDockSizeConfig(
        context: Context,
        prefs: android.content.SharedPreferences
    ): DockSizeConfig = when (prefs.getString("dock_size_preset", "normal")) {
        "small" -> DockSizeConfig(40, 34, 42, false)
        "pc"    -> DockSizeConfig(30, 24, 32, true)   // Display Size
        else    -> DockSizeConfig(
            prefs.getString("dock_height", "56")!!.toInt(),
            50, 52, false
        )
    }

    /**
     * 1.15: Shizuku — Display REAL app tasks on the Dock
     * Uses `am stack list` via Shizuku to get actual windowed tasks
     * including freeform/multi-window tasks invisible to normal getRunningTasks()
     */
    fun getTasksViaShizuku(
        context: Context,
        packageManager: PackageManager,
        max: Int
    ): ArrayList<AppTask>? {
        val shizuku = ShizukoManager.getInstance(context)
        if (!shizuku.hasPermission) return null

        val launcherPkg = getCurrentLauncher(packageManager)
        val selfPkg = context.packageName
        val result = ArrayList<AppTask>()

            // Try am stack list, or fallback to modern Android 12-15 `cmd activity tasks`
            var output = shizuku.runShellSync("am stack list") ?: ""

            if (output.isBlank() || output.contains("Unknown command", ignoreCase = true) || output.contains("Error", ignoreCase = true)) {
                output = shizuku.runShellSync("cmd activity tasks") ?: ""
            }

            // Parse lines across Android 9 to 15:
            // Android 12-15: Task id #368 ... cmp=com.pkg/.Activity
            // Android 9-11:  taskId=368: com.pkg/com.activity
            // Legacy:        taskId=42 bounds=[...] baseIntent=com.pkg/.Activity
            val taskIdRegex = Regex("""(?:taskId=|Task id #|Task\{[a-f0-9]+ #)(\d+)""")
            val pkgRegex = Regex("""(?:taskId=\d+:\s*|baseIntent=|ComponentInfo\{|cmp=|A=)([a-zA-Z0-9_.]+)""")

            val seenPkgs = mutableSetOf<String>()
            for (line in output.lines()) {
                if (result.size >= max) break
                val taskId = taskIdRegex.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                val pkg = pkgRegex.find(line)?.groupValues?.get(1) ?: continue
                if (pkg == selfPkg || pkg == launcherPkg ||
                    pkg.contains("launcher", ignoreCase = true) ||
                    pkg.contains("quickstep", ignoreCase = true) ||
                    pkg.contains("systemui", ignoreCase = true)
                ) continue
                if (seenPkgs.contains(pkg)) continue
                seenPkgs.add(pkg)

                try {
                    val intent = packageManager.getLaunchIntentForPackage(pkg) ?: continue
                    val actInfo = intent.component?.let {
                        packageManager.getActivityInfo(it, 0)
                    }
                    val label = actInfo?.loadLabel(packageManager)?.toString()
                        ?: packageManager.getApplicationLabel(
                            packageManager.getApplicationInfo(pkg, 0)
                        ).toString()
                    val icon = try {
                        packageManager.getApplicationIcon(pkg)
                    } catch (e: Exception) { continue }

                    result.add(AppTask(taskId, label, pkg, icon))
                } catch (e: Exception) { continue }
            }
        } catch (e: Exception) {
            return null
        }

        return if (result.isEmpty()) null else result
    }
}
