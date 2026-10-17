package com.plate.launcher.activities

import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.plate.launcher.utils.AppFontScaleUtils

/**
 * Shared base Activity for the "App Wide Text Size" feature (v65 mechanism).
 *
 * Previously every Activity overrode attachBaseContext() and wrapped the
 * Context with a modified Configuration.fontScale via
 * AppFontScaleUtils.wrapContext(). That approach repeatedly corrupted
 * width/height/density readings once the percentage differed from 100%,
 * even with no secondary display involved — see AppFontScaleUtils.kt for the
 * full explanation.
 *
 * Fix: don't touch Configuration/Context at all. onContentChanged() is
 * called automatically by Android right after the Activity's content view
 * finishes inflating — no matter where setContentView()/setContentFragment()
 * is called from — so this is a single, reliable place to walk the freshly
 * inflated view tree and scale each TextView's text size directly in place.
 *
 * Any Activity that wants the app-wide text size setting applied should
 * extend this instead of AppCompatActivity directly. Activities that already
 * have their own base class can instead call
 * AppFontScaleUtils.applyToViewHierarchy(...) manually from their own
 * onContentChanged() override — see MainActivity for an example.
 *
 * ANDROID 15/16 DESKTOP WINDOWING FIX:
 * Starting at targetSdk 35, Android forces edge-to-edge — the app is expected
 * to draw behind (and cooperate with) all system bars, INCLUDING the caption
 * bar that Android 15 QPR1+/16 draws at the top of every freeform window
 * (desktop windowing). An app that never calls setDecorFitsSystemWindows(false)
 * is left in a legacy/undefined layout mode under the new enforcement, which on
 * several OEM skins manifests as the caption bar failing to draw or being
 * clipped out — this is what was happening across every screen opened via
 * AppUtils.makeActivityOptions()'s freeform windowing mode (PerfectServer's
 * dock, launched apps, settings, etc.), since practically every Activity in
 * this app extends this base class.
 *
 * This is unrelated to windowNoTitle/windowActionBar (those control the old
 * AppCompat ActionBar, not the system-drawn desktop caption bar) and unrelated
 * to PerfectServer's TYPE_APPLICATION_OVERLAY windows (a completely separate
 * windowing path — this fix only affects normal Activity windows).
 *
 * UNIVERSAL EDGE-TO-EDGE PATTERN (how every major app — Instagram, WhatsApp,
 * etc. — actually does this, and what's implemented below):
 * The window itself draws edge-to-edge (background extends up behind the
 * status bar/cutout — no visible seam), but the actual content root is always
 * padded down by the real system-bar/cutout inset, so header rows, buttons,
 * and text never sit underneath the status bar and are never clipped or
 * unreachable. This is not a user-configurable choice: an app should never
 * let its own controls become partially hidden behind the status bar, so
 * there's exactly one correct behavior here, applied globally.
 */
open class BaseFontScaleActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Opt in to edge-to-edge cooperation so the system's caption bar (and
        // other system bars) render correctly instead of being left in a
        // legacy layout state. No-op cost on phones/fullscreen — only matters
        // when the Activity is actually running as a freeform/desktop window.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            // Explicitly request the caption bar stay shown — on some OEM
            // builds it is left hidden by default once the app opts in to
            // edge-to-edge, since nothing was telling the system otherwise.
            insetsController.show(WindowInsetsCompat.Type.captionBar())
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // The window background is left to draw all the way up behind the
        // status bar/cutout (true edge-to-edge, no seam) — only the content
        // root gets padded, and only on the top edge, by however tall the
        // status bar/cutout actually is on this specific device. Real device
        // insets are used instead of a fixed dp guess because notch/punch-
        // hole heights vary a lot across OEMs.
        val contentRoot = window.decorView.findViewById<android.view.View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(contentRoot) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(view.paddingLeft, bars.top, view.paddingRight, view.paddingBottom)
            insets
        }
    }

    override fun onContentChanged() {
        super.onContentChanged()
        AppFontScaleUtils.applyToViewHierarchy(
            window?.decorView?.findViewById(android.R.id.content)
        )
    }
}
