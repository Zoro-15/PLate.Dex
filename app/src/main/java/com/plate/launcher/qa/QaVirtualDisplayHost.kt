package com.plate.launcher.qa

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.plate.launcher.R
import com.plate.launcher.utils.RootManager
import com.plate.launcher.utils.ShizukoManager

/**
 * QaVirtualDisplayHost — isolation boundary for ACTIVITY-kind probes,
 * using a VirtualDisplay instead of a SYSTEM_ALERT_WINDOW overlay.
 *
 * ## How it works:
 * Android allows creating a fake [VirtualDisplay] — a second display with
 * no physical existence, but the system treats it as a fully real display
 * (onCreate, WindowManager, everything). The target Activity is launched
 * on this display via [ActivityOptions.setLaunchDisplayId], so it actually
 * runs but is never visible to the user — because it's on a display no one
 * is looking at.
 *
 * ## Requirement — Root or Shizuku only:
 * Creating a VirtualDisplay requires CAPTURE_VIDEO_OUTPUT, a signature-level
 * permission not granted to a normal app. Fix: grant it via Root or Shizuku
 * before use. If neither Root nor Shizuku is available → [QaOutcome.SKIPPED]
 * with a clear reason. There is no overlay fallback — the old approach was
 * a poor user experience and this replaces it entirely.
 *
 * ## Fallback chain within VirtualDisplay:
 * ```
 * Root available → am display create (shell command, most compatible)
 *     ↓ fails or Root unavailable
 * Shizuku available → DisplayManager.createVirtualDisplay() after pm grant
 *     ↓ fails
 * SKIPPED + clear reason in the result
 * ```
 *
 * ## Cross-process crash detection (inherited from the original design):
 * The target runs in the main process (not ":qa") — a crash there isn't
 * visible as a Throwable here. [MainProcessWatchdog] polls every
 * [MAIN_PROCESS_POLL_MS] and tracks the PID (not just the name), since the
 * system may relaunch the same process name under a new PID.
 *
 * ## One automatic restart (inherited):
 * First attempt fails → logged + one automatic retry → CRASH if the second
 * attempt also fails.
 */
class QaVirtualDisplayHost : Activity() {

    companion object {
        private const val TAG = "QaVirtualDisplayHost"
        const val EXTRA_QUALIFIED_CLASS_NAME = "qa_target_class"
        const val EXTRA_DISPLAY_NAME         = "qa_target_display_name"
        const val EXTRA_RUN_TOKEN            = "qa_run_token"

        private const val ATTEMPT_SETTLE_MS    = 400L
        private const val WATCHDOG_MS          = 3_500L
        private const val MAIN_PROCESS_POLL_MS = 100L

        // Logical dimensions for the VirtualDisplay — no physical meaning
        private const val VDISPLAY_WIDTH  = 1080
        private const val VDISPLAY_HEIGHT = 1920
        private const val VDISPLAY_DPI    = 320
    }

    // ── State ────────────────────────────────────────────────────────────────
    private val mainHandler = Handler(Looper.getMainLooper())
    private var finished    = false

    private lateinit var runToken          : String
    private lateinit var displayName       : String
    private lateinit var qualifiedClassName: String

    private var previousUncaughtHandler: Thread.UncaughtExceptionHandler? = null
    private var mainProcessWatchdog    : MainProcessWatchdog?              = null

    /** Bumped on every attempt so a stale watchdog Runnable from a
     *  previous (already-restarted) attempt can recognize it's outdated
     *  and no-op instead of force-timing-out a still-in-progress retry. */
    private var watchdogGeneration = 0

    private var virtualDisplay  : VirtualDisplay? = null
    private var imageReader     : ImageReader?     = null
    private var virtualDisplayId: Int              = -1

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setWindowAnimations(0)
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
        window.setBackgroundDrawableResource(android.R.color.black)

        runToken = intent.getStringExtra(EXTRA_RUN_TOKEN) ?: run { finishSelf(); return }
        displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME) ?: "unknown"
        qualifiedClassName = intent.getStringExtra(EXTRA_QUALIFIED_CLASS_NAME) ?: run {
            reportAndFinish(QaOutcome.SKIPPED, getString(R.string.qa_error_no_target_class))
            return
        }

        // Check for Root or Shizuku before anything else
        val root    = RootManager.getInstance(this)
        val shizuku = ShizukoManager.getInstance(this)

        if (!root.isAvailable && !shizuku.hasPermission) {
            reportAndFinish(
                QaOutcome.SKIPPED,
                durationNote = getString(R.string.qa_error_no_root_or_shizuku)
            )
            return
        }

        // Watchdog: re-armed for every attempt inside installUncaughtHandlerAndLaunch()
        // (not scheduled here) — a single onCreate-time deadline would not
        // account for the automatic restart delay, and could incorrectly
        // TIMEOUT an attempt-2 that was still legitimately in progress.

        // grantCapturePermissionIfNeeded() blocks on a shell round-trip, so it
        // must not run on the main thread — do it on a background thread and
        // hop back before launching, rather than blocking onCreate() itself.
        Thread {
            grantCapturePermissionIfNeeded(root, shizuku)
            mainHandler.post { installUncaughtHandlerAndLaunch(attemptNumber = 1) }
        }.start()
    }

    // ── Permission grant ──────────────────────────────────────────────────────

    /**
     * CAPTURE_VIDEO_OUTPUT is a signature-level permission — the only way to
     * grant it without being a system app is `pm grant` via shell (Root or
     * Shizuku).
     *
     * Uses runShellSync (blocks the calling thread until the command
     * finishes) rather than the async runShell — because createVirtualDisplay()
     * is called right after this returns. If this were async, attempt 1
     * would usually fail with SecurityException before the grant command
     * had actually finished, wasting the one automatic restart for nothing.
     */
    private fun grantCapturePermissionIfNeeded(root: RootManager, shizuku: ShizukoManager) {
        val pkg = packageName
        val alreadyGranted = try {
            checkSelfPermission("android.permission.CAPTURE_VIDEO_OUTPUT") ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) { false }

        if (alreadyGranted) return

        val cmd = "pm grant $pkg android.permission.CAPTURE_VIDEO_OUTPUT"
        val result = when {
            root.isAvailable      -> root.runShellSync(cmd)
            shizuku.hasPermission -> shizuku.runShellSync(cmd)
            else                  -> null
        }
        Log.d(TAG, "CAPTURE_VIDEO_OUTPUT grant result: ${result ?: "(no output)"}")
    }

    // ── VirtualDisplay ────────────────────────────────────────────────────────

    /**
     * Attempts to create a [VirtualDisplay]:
     * 1. Root → `am display create` (shell command, more broadly compatible)
     * 2. Shizuku → [DisplayManager.createVirtualDisplay] directly
     *
     * Returns the display ID on success, or -1 on failure.
     */
    private fun createVirtualDisplay(): Int {
        val root    = RootManager.getInstance(this)
        val shizuku = ShizukoManager.getInstance(this)

        // Attempt 1: Root via am display create
        if (root.isAvailable) {
            val id = tryCreateViaRootShell(root)
            if (id > 0) return id
        }

        // Attempt 2: Shizuku via the DisplayManager API
        if (shizuku.hasPermission) {
            val id = tryCreateViaDisplayManager()
            if (id > 0) return id
        }

        return -1
    }

    /**
     * Root path: `am display create` is available from Android 10+ and returns
     * a display ID directly. `am display create` requires shell or root —
     * RootManager.runShellSync provides that.
     */
    private fun tryCreateViaRootShell(root: RootManager): Int {
        return try {
            val output = root.runShellSync(
                "am display create --width $VDISPLAY_WIDTH --height $VDISPLAY_HEIGHT --density $VDISPLAY_DPI"
            ) ?: return -1

            // Output looks like: "Virtual display created: 3"
            val id = Regex("created:\\s*(\\d+)").find(output)?.groupValues?.get(1)?.toIntOrNull()
            if (id != null) {
                virtualDisplayId = id
                Log.d(TAG, "[$displayName] VirtualDisplay via Root shell: id=$id")
                id
            } else {
                Log.w(TAG, "[$displayName] am display create output unparseable: '$output'")
                -1
            }
        } catch (e: Exception) {
            Log.w(TAG, "[$displayName] am display create failed: ${e.message}")
            -1
        }
    }

    /**
     * Shizuku path: [DisplayManager.createVirtualDisplay] called directly from
     * the app, after we've granted CAPTURE_VIDEO_OUTPUT via Shizuku in
     * [grantCapturePermissionIfNeeded].
     *
     * [ImageReader] is the surface sink — required by the API, but we discard
     * every frame.
     */
    private fun tryCreateViaDisplayManager(): Int {
        return try {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                ?: return -1

            val reader = ImageReader.newInstance(
                VDISPLAY_WIDTH, VDISPLAY_HEIGHT,
                android.graphics.ImageFormat.JPEG, /* maxImages= */ 2
            )
            // Explicitly discard frames — this display is never meant to be seen
            reader.setOnImageAvailableListener(
                { it.acquireLatestImage()?.close() },
                mainHandler
            )

            val vd = dm.createVirtualDisplay(
                "PLateQA_${displayName.replace(" ", "_")}",
                VDISPLAY_WIDTH, VDISPLAY_HEIGHT, VDISPLAY_DPI,
                reader.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            )

            if (vd == null) {
                reader.close()
                Log.w(TAG, "[$displayName] createVirtualDisplay() returned null")
                return -1
            }

            imageReader     = reader
            virtualDisplay  = vd
            val id = vd.display.displayId
            virtualDisplayId = id
            Log.d(TAG, "[$displayName] VirtualDisplay via DisplayManager: id=$id")
            id

        } catch (e: SecurityException) {
            Log.w(TAG, "[$displayName] VirtualDisplay SecurityException (CAPTURE_VIDEO_OUTPUT not granted yet?): ${e.message}")
            -1
        } catch (e: Exception) {
            Log.w(TAG, "[$displayName] VirtualDisplay failed: ${e.message}")
            -1
        }
    }

    private fun destroyVirtualDisplay() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null

        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null

        // Root path: remove the display via shell (am display remove)
        if (virtualDisplayId > 0) {
            val root = RootManager.getInstance(this)
            if (root.isAvailable) {
                try { root.runShellSync("am display remove $virtualDisplayId") } catch (_: Exception) {}
            }
        }
        virtualDisplayId = -1
    }

    // ── Launch ────────────────────────────────────────────────────────────────

    private fun installUncaughtHandlerAndLaunch(attemptNumber: Int) {
        val myGeneration = ++watchdogGeneration
        mainHandler.postDelayed({
            if (watchdogGeneration == myGeneration) {
                reportAndFinish(QaOutcome.TIMEOUT, getString(R.string.qa_error_host_watchdog_timeout, WATCHDOG_MS))
            }
        }, WATCHDOG_MS)

        previousUncaughtHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            restoreUncaughtHandler()
            mainHandler.post { handleAttemptFailure(attemptNumber, throwable, crossProcess = false) }
        }

        val mainProcessName = packageName
        mainProcessWatchdog?.stop()
        mainProcessWatchdog = MainProcessWatchdog(this, mainProcessName) {
            val reason = mainProcessWatchdog?.describeExitReasonIfAvailable()
            val syntheticThrowable = IllegalStateException(
                "Main process ($mainProcessName) disappeared while probing $displayName" +
                    (reason?.let { " — $it" } ?: "")
            )
            handleAttemptFailure(attemptNumber, syntheticThrowable, crossProcess = true)
        }.also { it.start() }

        // Create the VirtualDisplay
        val displayId = createVirtualDisplay()
        if (displayId < 0) {
            restoreUncaughtHandler()
            mainProcessWatchdog?.stop()
            reportAndFinish(
                QaOutcome.SKIPPED,
                durationNote = getString(R.string.qa_error_vdisplay_creation_failed)
            )
            return
        }

        // Launch the target on the VirtualDisplay
        try {
            val clazz = Class.forName(qualifiedClassName)
            val launchIntent = Intent(this, clazz).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                putExtra("qa_probe_mode", true)
            }

            // ActivityOptions.setLaunchDisplayId is available from API 26 — this
            // project's minSdk is exactly 26, so there's no need to check
            // SDK_INT or provide a fallback: without launchDisplayId here,
            // the target would launch on the real screen and defeat the
            // entire purpose of this class.
            val options = ActivityOptions.makeBasic().apply {
                launchDisplayId = displayId
            }
            startActivity(launchIntent, options.toBundle())

            Log.d(TAG, "[$displayName] launched on VirtualDisplay $displayId (attempt $attemptNumber)")

        } catch (t: Throwable) {
            restoreUncaughtHandler()
            mainProcessWatchdog?.stop()
            destroyVirtualDisplay()
            handleAttemptFailure(attemptNumber, t, crossProcess = false)
            return
        }

        // Wait ATTEMPT_SETTLE_MS, then record PASS if everything is fine
        mainHandler.postDelayed({
            if (!finished) {
                restoreUncaughtHandler()
                mainProcessWatchdog?.stop()
                mainProcessWatchdog = null
                finishTargetTask()
                reportAndFinish(QaOutcome.PASS, durationNote = null)
            }
        }, ATTEMPT_SETTLE_MS)
    }

    private fun restoreUncaughtHandler() {
        Thread.setDefaultUncaughtExceptionHandler(previousUncaughtHandler)
    }

    private fun handleAttemptFailure(attemptNumber: Int, t: Throwable, crossProcess: Boolean) {
        mainProcessWatchdog?.stop()
        mainProcessWatchdog = null
        finishTargetTask()
        destroyVirtualDisplay()

        val label = if (crossProcess) "main process crash" else "in-host exception"
        if (attemptNumber == 1) {
            Log.w(TAG, "[$displayName] attempt 1 failed ($label) — restarting once: ${t.message}")
            QaResultBus.recordRestart(runToken, displayName, t)
            watchdogGeneration++ // invalidate attempt-1's watchdog now, before attempt-2 schedules its own
            mainHandler.postDelayed({ installUncaughtHandlerAndLaunch(attemptNumber = 2) }, 150)
        } else {
            reportAndFinish(
                QaOutcome.CRASH,
                durationNote = getString(R.string.qa_error_failed_after_restart, label, t.message ?: ""),
                stackTrace   = t.stackTraceToString(),
            )
        }
    }

    private fun finishTargetTask() {
        try {
            val am     = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val target = ComponentName(this, qualifiedClassName)
            @Suppress("DEPRECATION")
            am.appTasks.forEach { task ->
                if (task.taskInfo?.baseActivity == target) task.finishAndRemoveTask()
            }
        } catch (e: Exception) {
            Log.w(TAG, "finishTargetTask failed for $qualifiedClassName: ${e.message}")
        }
    }

    private fun reportAndFinish(outcome: QaOutcome, durationNote: String? = null, stackTrace: String? = null) {
        if (finished) return
        finished = true
        mainHandler.removeCallbacksAndMessages(null)
        mainProcessWatchdog?.stop()
        mainProcessWatchdog = null
        restoreUncaughtHandler()
        finishTargetTask()
        QaResultBus.recordFinal(runToken, displayName, qualifiedClassName, outcome, durationNote, stackTrace)
        finishSelf()
    }

    private fun finishSelf() {
        destroyVirtualDisplay()
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        mainProcessWatchdog?.stop()
        mainProcessWatchdog = null
        destroyVirtualDisplay()
        restoreUncaughtHandler()
        super.onDestroy()
    }

    // ── MainProcessWatchdog ────────────────────────────────────────────────────
    // Same design as before — the target runs in the main process, not
    // ":qa", so a crash there isn't visible as a Throwable here, only as
    // "the PID disappeared".

    private class MainProcessWatchdog(
        private val activity: Activity,
        private val mainProcessName: String,
        private val onMainProcessGone: () -> Unit,
    ) {
        private val handler    = Handler(Looper.getMainLooper())
        private var stopped    = false
        private var trackedPid : Int? = null

        private val pollTick = object : Runnable {
            override fun run() {
                if (stopped) return
                when (val pid = trackedPid) {
                    null -> findCurrentPid()?.let { trackedPid = it }
                    else -> {
                        if (!isPidAlive(pid)) {
                            stopped = true
                            onMainProcessGone()
                            return
                        }
                    }
                }
                handler.postDelayed(this, MAIN_PROCESS_POLL_MS)
            }
        }

        fun start() {
            trackedPid = findCurrentPid()
            handler.postDelayed(pollTick, MAIN_PROCESS_POLL_MS)
        }

        fun stop() {
            stopped = true
            handler.removeCallbacks(pollTick)
        }

        private fun findCurrentPid(): Int? {
            val am = activity.getSystemService(ACTIVITY_SERVICE) as? ActivityManager ?: return null
            return try { am.runningAppProcesses } catch (_: Exception) { null }
                ?.firstOrNull { it.processName == mainProcessName }?.pid
        }

        private fun isPidAlive(pid: Int): Boolean {
            val am = activity.getSystemService(ACTIVITY_SERVICE) as? ActivityManager ?: return true
            return try { am.runningAppProcesses } catch (_: Exception) { null }
                ?.any { it.pid == pid } ?: true
        }

        fun describeExitReasonIfAvailable(): String? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
            return try {
                val am    = activity.getSystemService(ACTIVITY_SERVICE) as ActivityManager
                val infos = am.getHistoricalProcessExitReasons(activity.packageName, 0, 1)
                infos.firstOrNull()?.let { "reason=${it.reason} desc=${it.description}" }
            } catch (_: Exception) { null }
        }
    }
}
