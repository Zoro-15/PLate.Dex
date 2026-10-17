package com.plate.launcher.qa

import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap

/**
 * QaResultBus — in-memory handoff between [QaVirtualDisplayHost] (which
 * actually drives a target and can crash while doing it) and
 * [QaTestOrchestrator] (which is waiting for that outcome). Both run in the
 * same ":qa" process, so a plain in-memory channel is enough — no IPC/
 * Binder/Intent-extras round trip needed, which matters here specifically
 * because the whole point of QaVirtualDisplayHost is to keep working even
 * when the thing it's driving throws from an unexpected callback; a normal
 * onActivityResult return can't be relied on for that.
 *
 * One [Channel] per in-flight run (keyed by a run token the orchestrator
 * generates once per [QaTestOrchestrator.run] call), so results from a
 * stale/leftover host instance from a previous run can never be mistaken
 * for the current one.
 */
object QaResultBus {

    /** One retry note plus the final outcome for a single target's probe. */
    data class ProbeOutcome(
        val displayName: String,
        val qualifiedClassName: String,
        val outcome: QaOutcome,
        val detail: String?,
        val stackTrace: String?,
        val restarted: Boolean,
    )

    private val channels = ConcurrentHashMap<String, Channel<ProbeOutcome>>()
    private val restartCounts = ConcurrentHashMap<String, Int>()

    /** Call once per [QaTestOrchestrator.run] before launching any probes for that run. */
    fun openRun(runToken: String) {
        channels[runToken] = Channel(capacity = Channel.BUFFERED)
    }

    /** Call once the run is fully done to release its channel. */
    fun closeRun(runToken: String) {
        channels.remove(runToken)?.close()
    }

    /** Logged by QaVirtualDisplayHost when attempt 1 fails and it's about to auto-restart. */
    fun recordRestart(runToken: String, displayName: String, throwable: Throwable) {
        val key = "$runToken:$displayName"
        restartCounts[key] = (restartCounts[key] ?: 0) + 1
        android.util.Log.w(
            "QaResultBus",
            "[$displayName] restarted after: ${throwable::class.java.simpleName}: ${throwable.message}"
        )
    }

    /** Whether [displayName] needed an automatic restart during this run. */
    fun wasRestarted(runToken: String, displayName: String): Boolean =
        (restartCounts["$runToken:$displayName"] ?: 0) > 0

    /** Called by QaVirtualDisplayHost right before it finishes itself, win or lose. */
    fun recordFinal(
        runToken: String,
        displayName: String,
        qualifiedClassName: String,
        outcome: QaOutcome,
        detail: String?,
        stackTrace: String?,
    ) {
        val outcomeRecord = ProbeOutcome(
            displayName = displayName,
            qualifiedClassName = qualifiedClassName,
            outcome = outcome,
            detail = detail,
            stackTrace = stackTrace,
            restarted = wasRestarted(runToken, displayName),
        )
        // trySend: this can run from a Handler callback after a crash, not
        // necessarily a coroutine — a suspending send would need a scope
        // that may no longer be valid at that point.
        channels[runToken]?.trySend(outcomeRecord)
    }

    /** Awaited by the orchestrator right after launching one probe host. */
    suspend fun awaitResult(runToken: String): ProbeOutcome? =
        channels[runToken]?.receive()
}
