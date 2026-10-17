package com.plate.launcher.qa

import android.content.Context
import android.util.Log
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.plate.launcher.R
import com.plate.launcher.utils.RootManager
import com.plate.launcher.utils.ShizukoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * QaTestOrchestrator — runs every [QaTarget] one at a time inside the
 * isolated ":qa" sandbox process, catching failures per-target so a single
 * crash never stops the rest of the run (per product decision: log the
 * failure and keep going — seeing every issue in one session matters more
 * than stopping at the first one).
 *
 * Isolation model:
 *  - This class, and the Activity that hosts it ([QaSandboxActivity]), run
 *    in the ":qa" process declared in AndroidManifest.xml. That's a real,
 *    separate Linux process/Dalvik-ART heap from the user's live app
 *    process. A native crash, ANR, or StackOverflow here kills *this*
 *    process only — the user's foreground app process is untouched.
 *  - Within that isolated process, each target ALSO gets a per-target
 *    try/catch + coroutine timeout, so one hung/misbehaving screen
 *    doesn't stall the whole run. Process isolation protects the user's
 *    app; the per-target timeout protects the run's own completeness.
 *
 * ## v3 update — VirtualDisplay instead of overlay:
 * ACTIVITY-kind targets are now launched via [QaVirtualDisplayHost]
 * (VirtualDisplay) instead of the old overlay-based host (SYSTEM_ALERT_WINDOW
 * overlay). The gating condition changed from "overlay permission granted"
 * to "Root or Shizuku available" — without either, there is no fallback
 * path, and the result is reported SKIPPED immediately with a clear reason.
 */
class QaTestOrchestrator(
    private val hostActivity: FragmentActivity,
) {
    companion object {
        private const val TAG = "QaTestOrchestrator"
        private const val PER_TARGET_TIMEOUT_MS = 8_000L
    }

    private val _progress = MutableStateFlow(QaProgress(0, 0, "", emptyList()))
    val progress: StateFlow<QaProgress> = _progress.asStateFlow()

    /** Runs the full scripted suite. Safe to call once per orchestrator instance. */
    suspend fun run(context: Context) = withContext(Dispatchers.Main) {
        val runToken = java.util.UUID.randomUUID().toString()
        QaResultBus.openRun(runToken)
        try {
            val targets = QaTargetRegistry.allTargets()
            val results = mutableListOf<QaResult>()
            val logFile = QaLogWriter.startRun()

            targets.forEachIndexed { index, target ->
                _progress.value = QaProgress(index + 1, targets.size, target.displayName, results.toList())

                val result = probeOneTarget(context, target, runToken)
                results += result
                QaLogWriter.appendResult(logFile, result)

                Log.i(TAG, "[${result.outcome}] ${target.displayName} (${result.durationMs}ms)")
            }

            QaLogWriter.finishRun(logFile, results)

            _progress.value = QaProgress(targets.size, targets.size, "", results)
        } finally {
            QaResultBus.closeRun(runToken)
        }
    }

    /** Probes a single target with a hard timeout, catching everything short of process death. */
    private suspend fun probeOneTarget(context: Context, target: QaTarget, runToken: String): QaResult {
        val start = System.currentTimeMillis()
        return try {
            val outcome = withTimeoutOrNull(PER_TARGET_TIMEOUT_MS) {
                runProbeScript(context, target, runToken)
            }
            val duration = System.currentTimeMillis() - start
            when {
                outcome == null -> QaResult(
                    target, QaOutcome.TIMEOUT, duration,
                    detail = context.getString(R.string.qa_error_exceeded_timeout, PER_TARGET_TIMEOUT_MS)
                )
                else -> outcome.toQaResult(target, duration)
            }
        } catch (t: Throwable) {
            val duration = System.currentTimeMillis() - start
            QaResult(
                target = target,
                outcome = QaOutcome.CRASH,
                durationMs = duration,
                detail = t.message,
                stackTrace = t.stackTraceToString(),
            )
        }
    }

    /** Turns a bus outcome (Activity path) or a plain PASS (Fragment path) into the final [QaResult]. */
    private fun ProbeRunOutcome.toQaResult(target: QaTarget, duration: Long): QaResult = when (this) {
        is ProbeRunOutcome.Simple -> QaResult(target, outcome, duration, detail = detail)
        is ProbeRunOutcome.FromBus -> {
            val restartNote = if (bus.restarted) "Recovered after 1 automatic restart. " else ""
            QaResult(
                target = target,
                outcome = bus.outcome,
                durationMs = duration,
                detail = restartNote.takeIf { it.isNotEmpty() }?.plus(bus.detail ?: "") ?: bus.detail,
                stackTrace = bus.stackTrace,
            )
        }
    }

    /**
     * The actual scripted interaction for one target: instantiate it, run
     * it through a couple of common lifecycle edges (quick start/stop,
     * simulated config change), then tear it down. Kept intentionally
     * short and generic — this is a smoke probe for "does this crash on
     * the most common paths", not a full instrumentation test.
     */
    private suspend fun runProbeScript(context: Context, target: QaTarget, runToken: String): ProbeRunOutcome =
        when (target.kind) {
            QaTargetKind.FRAGMENT -> {
                probeFragment(target)
                ProbeRunOutcome.Simple(QaOutcome.PASS)
            }
            QaTargetKind.ACTIVITY -> probeActivity(context, target, runToken)
        }

    private suspend fun probeFragment(target: QaTarget) = withContext(Dispatchers.Main) {
        val clazz = Class.forName(target.qualifiedClassName)
        val fragment = clazz.getDeclaredConstructor().newInstance() as Fragment

        val fm = hostActivity.supportFragmentManager
        val tx = fm.beginTransaction()
        tx.add(android.R.id.content, fragment, "qa_probe_${target.displayName}")
        tx.commitNowAllowingStateLoss()

        // Quick attach → detach, the same rapid on/off toggling this probe
        // is meant to simulate.
        val tx2 = fm.beginTransaction()
        tx2.remove(fragment)
        tx2.commitNowAllowingStateLoss()
    }

    /**
     * Launches [QaVirtualDisplayHost] rather than the target class directly.
     * QaVirtualDisplayHost is the actual isolation boundary for Activity
     * targets: creates a VirtualDisplay via Root or Shizuku, launches the
     * target on it (so it never touches the real screen at all — no
     * overlay, nothing to cover), catches anything the target throws,
     * auto-restarts once on failure, and reports back via [QaResultBus].
     * See its kdoc for the full picture.
     *
     * Gated on Root or Shizuku being available — there is no fallback path.
     * Without either, every ACTIVITY-kind target is reported SKIPPED with a
     * clear reason rather than falling back to a visible overlay.
     */
    private suspend fun probeActivity(context: Context, target: QaTarget, runToken: String): ProbeRunOutcome {
        if (!hasElevatedAccess(context)) {
            return ProbeRunOutcome.Simple(
                QaOutcome.SKIPPED,
                detail = context.getString(R.string.qa_error_no_root_or_shizuku_probe),
            )
        }

        // MULTIPLE_TASK + NEW_TASK: each probe gets its own fresh
        // QaVirtualDisplayHost instance rather than reusing/reordering to an
        // existing one (belt-and-suspenders alongside the manifest's
        // noHistory="true", which already drops each instance from the
        // task stack the moment it loses focus).
        val intent = android.content.Intent(context, QaVirtualDisplayHost::class.java).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            addFlags(android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            putExtra(QaVirtualDisplayHost.EXTRA_QUALIFIED_CLASS_NAME, target.qualifiedClassName)
            putExtra(QaVirtualDisplayHost.EXTRA_DISPLAY_NAME, target.displayName)
            putExtra(QaVirtualDisplayHost.EXTRA_RUN_TOKEN, runToken)
        }
        withContext(Dispatchers.Main) { context.startActivity(intent) }

        val busResult = QaResultBus.awaitResult(runToken)
            ?: return ProbeRunOutcome.Simple(QaOutcome.CRASH) // channel closed early — treat as failure, not a silent pass
        return ProbeRunOutcome.FromBus(busResult)
    }

    /** Root or Shizuku only — there is no overlay fallback anymore. */
    private fun hasElevatedAccess(context: Context): Boolean {
        val root = RootManager.getInstance(context)
        val shizuku = ShizukoManager.getInstance(context)
        return root.isAvailable || shizuku.hasPermission
    }
}

/**
 * Internal result shape bridging the two probe paths: fragments resolve
 * synchronously in-process (no crash isolation needed beyond the existing
 * try/catch, since a fragment can't take the whole ":qa" process down the
 * way a botched Activity window could), while activities go through
 * [QaVirtualDisplayHost] + [QaResultBus] and carry richer detail (whether an
 * automatic restart was needed, stack trace, etc).
 */
private sealed class ProbeRunOutcome {
    data class Simple(val outcome: QaOutcome, val detail: String? = null) : ProbeRunOutcome()
    data class FromBus(val bus: QaResultBus.ProbeOutcome) : ProbeRunOutcome()
}
