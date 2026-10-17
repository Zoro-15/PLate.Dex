package com.plate.launcher.utils

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.StrictMode
import android.util.Log
import androidx.preference.PreferenceManager
import com.plate.launcher.activities.DebugActivity

/**
 * StrictDebugMode — a toggleable "make everything loud" mode for bug hunting.
 *
 * The normal crash handler in [com.plate.launcher.App] only catches things that
 * already crash (uncaught exceptions). Most of the bugs reported so far
 * (the dockLayoutParams race, the black-screen editor) never actually threw
 * anywhere near the real problem — they failed silently, or the crash
 * happened seconds/minutes after the actual mistake (StrictMode's whole
 * purpose). This makes those visible on demand:
 *
 *   1. StrictMode ThreadPolicy/VmPolicy — flags disk/network on the main
 *      thread, leaked closables (Cursors, streams, the exact class of bug
 *      that caused the MediaMetadataRetriever finalizer timeout crash),
 *      leaked registered receivers/services, and untagged sockets — the
 *      moment they happen, not later when something else notices.
 *   2. logFailure() — a single call point other classes can use to report
 *      a "soft" failure (a null we didn't expect, a player that never
 *      reached STATE_READY, a request that returned but shouldn't have)
 *      that would normally just be a silently-swallowed log line. When
 *      strict mode is on, these get promoted to the same DebugActivity
 *      error screen a real crash shows, with a clear [SOFT FAILURE] tag,
 *      so a glitch is no longer invisible just because nothing threw.
 *
 * Deliberately opt-in via the "strict_debug_mode" switch in the hidden Beta
 * settings screen (unlocked via Developer Mode — 7 taps on the version
 * number in Help & About), OFF by default: StrictMode has real overhead
 * (every disk read on the main thread pays a penalty logging its own stack
 * trace) and is intentionally noisy, which is exactly wrong for a build
 * someone actually uses day to day.
 */
object StrictDebugMode {

    private const val TAG = "StrictDebugMode"
    private const val PREF_KEY = "strict_debug_mode"

    fun isEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(PREF_KEY, false)

    /** Call once from Application.onCreate(), after the uncaught-exception handler is set up. */
    fun installIfEnabled(app: Application) {
        if (!isEnabled(app)) return
        Log.w(TAG, "Strict debug mode is ON — expect extra overhead and noisy logs/error screens.")

        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .detectCustomSlowCalls()
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) detectResourceMismatches()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) detectUnbufferedIo()
                }
                .penaltyLog()
                // FIX-friendly choice: penaltyDialog() would interrupt every single
                // violation with a blocking system dialog — unusable on a launcher
                // that's supposed to stay interactive. penaltyLog() dumps a full
                // stack trace to Logcat instead, which is what we actually want
                // while chasing a bug (readable via `adb logcat` or "Save log").
                .build()
        )

        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()   // exactly the bug class behind the MMR finalizer timeout crash
                .detectLeakedRegistrationObjects() // unregistered listeners/receivers/services
                .detectActivityLeaks()
                .detectFileUriExposure()
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) detectContentUriWithoutPermission()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) detectCredentialProtectedWhileLocked()
                }
                .penaltyLog()
                .build()
        )
    }

    /**
     * Reports a "soft" failure — something that went silently wrong without
     * throwing (a player stuck in BUFFERING, an unexpected null, a resource
     * that failed to load). No-op unless strict debug mode is on.
     *
     * When it IS on, this routes straight to the same [DebugActivity] error
     * screen a real crash shows, tagged [SOFT FAILURE] so it's clearly not a
     * genuine exception — turning an invisible glitch into something you can
     * see, screenshot, and "Save log" immediately instead of hunting for it
     * in Logcat after the fact.
     */
    fun logFailure(context: Context, source: String, detail: String, throwable: Throwable? = null) {
        Log.e(TAG, "[SOFT FAILURE] $source: $detail", throwable)
        if (!isEnabled(context)) return

        val report = buildString {
            append("[SOFT FAILURE] (not a crash — reported by strict debug mode)\n")
            append("Source: $source\n")
            append("Detail: $detail\n")
            if (throwable != null) {
                append("Exception: $throwable\n")
                for (element in throwable.stackTrace) append(element.toString()).append("\n")
            }
        }
        val intent = Intent(context.applicationContext, DebugActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("report", report)
        }
        context.applicationContext.startActivity(intent)
    }
}
