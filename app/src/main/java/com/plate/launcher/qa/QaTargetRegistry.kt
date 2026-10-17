package com.plate.launcher.qa

/**
 * QaTargetRegistry — the fixed list of Activities/Fragments the self-test
 * probes, one run item per class.
 *
 * Deliberately a hand-maintained list, not classpath/reflection scanning
 * (e.g. no Dexter/Reflections-style "find all subclasses of Activity").
 * Three reasons:
 *   1. Some Activities are launch-mode-sensitive or expect specific Intent
 *      extras (see [QaTargetKind] usage in the orchestrator) — a generic
 *      scanner would instantiate them wrong and produce false-positive
 *      crashes that have nothing to do with a real bug.
 *   2. A few classes are intentionally excluded (see EXCLUDED below) —
 *      scanning would silently include them again on the next refactor.
 *   3. Reflection-based classpath scanning also tends to pull in test
 *      doubles/generated classes; an explicit list keeps the report
 *      exactly matching what a human would call "the app's screens."
 *
 * Keep this in sync manually when Activities/Fragments are added or
 * removed — that's a one-line diff, and it's reviewed the same as any
 * other code change, unlike a scanner's output.
 */
object QaTargetRegistry {

    private const val PKG = "com.plate.launcher"

    // Activities intentionally NOT probed, with the reason:
    //  - SplashActivity: routes/finishes itself immediately based on first-run
    //    state; probing it either no-ops or fights the real splash logic.
    //  - ShortcutLauncherActivity / SecondaryLauncherActivity: require a
    //    specific launching Intent (app shortcut / secondary display) to behave
    //    meaningfully; instantiating them bare mostly tests Android's own
    //    Activity plumbing, not our code.
    //  - UnifiedVideoPlayerActivity: needs a real media Uri extra or it just
    //    shows its own "nothing to play" state — that's not a useful signal
    //    from this scripted probe (still worth testing, just manually with
    //    a real video).

    val activities: List<QaTarget> = listOf(
        "MainActivity",
        "DesktopOverlayActivity",
        "EasterEggActivity",
        "WorkshopSourcesActivity",
        "BaseFontScaleActivity",
    ).map { QaTarget(it, "$PKG.activities.$it", QaTargetKind.ACTIVITY) }

    // Fragments hosted inside PreferencesFragment's screen; each is a
    // PreferenceFragmentCompat and is probed by attaching it to a throwaway
    // FragmentManager inside the sandbox host, not by launching a real
    // Activity around it.
    val fragments: List<QaTarget> = listOf(
        "AdvancedPreferences",
        "AppMenuPreferences",
        "AppearancePreferences",
        "BetaPreferences",
        "DefaultAppsPreferences",
        "DockPreferences",
        "HelpAboutPreferences",
        "HotCornersPreferences",
        "KeyboardPreferences",
        "MultiUserFragment",
        "NotificationPreferences",
        "PerformanceFragment",
        "PluginStoreFragment",
        "PluginsFragment",
        "SoundsPreferences",
        "WorkshopFragment",
    ).map { QaTarget(it, "$PKG.fragments.$it", QaTargetKind.FRAGMENT) }

    /** Full scripted run order: activities first, then fragments. */
    fun allTargets(): List<QaTarget> = activities + fragments
}
