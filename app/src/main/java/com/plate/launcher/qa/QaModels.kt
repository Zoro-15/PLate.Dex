package com.plate.launcher.qa

/**
 * QaModels — shared data types for the in-app self-test ("QA sandbox") tool.
 *
 * This is a QA/diagnostics feature, not a testing framework replacement:
 * it walks every known Activity/Fragment, instantiates each one, drives it
 * through a short scripted interaction (rapid start/stop, config-change
 * simulation, common lifecycle edges), and records what happened.
 *
 * The orchestrator/host UI (see [com.plate.launcher.qa.QaSandboxActivity],
 * [com.plate.launcher.qa.QaTestOrchestrator]) itself runs in a *separate,
 * isolated* ":qa" process (see AndroidManifest.xml). ACTIVITY-kind targets,
 * however, are deliberately launched into the app's real main process, the
 * same one they run in for an actual user — see
 * [com.plate.launcher.qa.QaVirtualDisplayHost]'s kdoc, "CROSS-PROCESS CRASH
 * DETECTION," for why that's intentional and how a crash there (which
 * ":qa"'s own in-process exception handler cannot see or catch) is still
 * detected and reported instead of surfacing as a real crash to the user.
 */

/** What kind of component a single probe targets. */
enum class QaTargetKind { ACTIVITY, FRAGMENT }

/** Final outcome of probing one target. */
enum class QaOutcome { PASS, SOFT_FAILURE, CRASH, TIMEOUT, SKIPPED }

/**
 * One item in the scripted run — corresponds to a single Activity or
 * Fragment class discovered in the app. Built once at the start of a run
 * from a static registry (see [QaTargetRegistry]), not via classpath
 * scanning — see that file's kdoc for why.
 */
data class QaTarget(
    val displayName: String,
    val qualifiedClassName: String,
    val kind: QaTargetKind,
)

/** Result of running a single [QaTarget] through the scripted probe. */
data class QaResult(
    val target: QaTarget,
    val outcome: QaOutcome,
    val durationMs: Long,
    val detail: String? = null,
    val stackTrace: String? = null,
)

/**
 * Live progress snapshot published by [QaTestOrchestrator] while a run is
 * in flight, consumed by the loading overlay UI.
 */
data class QaProgress(
    val currentIndex: Int,
    val total: Int,
    val currentTarget: String,
    val results: List<QaResult>,
)
