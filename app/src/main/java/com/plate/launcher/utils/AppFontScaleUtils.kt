package com.plate.launcher.utils

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.preference.PreferenceManager

/**
 * AppFontScaleUtils — scales the font size up/down across **the entire app**,
 * not just app names in the dock/grid (that stays independent in
 * [IconScaleUtils] because it's a different setting in terms of what the user
 * is trying to achieve — "icon label size" versus "every font size in the app").
 *
 * ══ v65 — mechanism change ══
 * Every earlier version of this file (through v64) worked by mutating
 * Configuration.fontScale and handing out a wrapped Context via
 * createConfigurationContext(), applied either in attachBaseContext() for
 * Activities or manually before LayoutInflater.from(context).inflate(...) in
 * PerfectServer/DockService.
 *
 * That's the approach Android's own docs point to, but in practice it kept
 * corrupting width/height/density readings once the percentage was anything
 * other than 100% — including on a single physical display with no secondary
 * screen involved. Changing Configuration.fontScale is treated by the
 * platform as a configuration/screen change, which is a much bigger hammer
 * than "resize some text": it can trigger resource re-selection, density
 * recalculation, and — especially for Views built directly via WindowManager
 * outside a normal Activity lifecycle, like the Dock — an inconsistent
 * Configuration snapshot between whatever created the View and whatever is
 * now trying to measure/place it. That mismatch is what showed up as
 * scrambled width & height.
 *
 * Fix: stop touching Configuration entirely. Apply the scale factor directly
 * to each TextView's text size after the layout is already inflated — the
 * same direct approach [IconScaleUtils] already uses successfully for icon
 * size and label size, which was never affected by this bug. No wrapped
 * Context, no createConfigurationContext, no attachBaseContext trickery.
 * Width/height/density are never touched, so they can't get scrambled.
 *
 * Usage:
 * - Activities: call [applyToViewHierarchy] on the root view from
 *   onContentChanged() (called automatically after setContentView, no matter
 *   where it's invoked) — see BaseFontScaleActivity.
 * - PerfectServer/Dock: call [applyToViewHierarchy] right after each
 *   LayoutInflater.from(context).inflate(...) call, on the inflated root.
 */
object AppFontScaleUtils {

    private const val KEY_APP_FONT_SCALE = "app_font_scale_percent"

    const val FONT_SCALE_DEFAULT = 100
    const val FONT_SCALE_MIN = 70
    const val FONT_SCALE_MAX = 160

    fun getFontScalePercent(context: Context): Int =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getInt(KEY_APP_FONT_SCALE, FONT_SCALE_DEFAULT)
            .coerceIn(FONT_SCALE_MIN, FONT_SCALE_MAX)

    fun setFontScalePercent(context: Context, percent: Int) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putInt(KEY_APP_FONT_SCALE, percent.coerceIn(FONT_SCALE_MIN, FONT_SCALE_MAX))
            .apply()
    }

    /**
     * Walks the given View (and, if it's a ViewGroup, every descendant) and
     * scales each TextView's text size in place by the user's saved
     * percentage. Safe to call repeatedly, including at 100%: each TextView's
     * *original* (100%) size is cached in a tag the first time it's seen, so
     * re-applying a different percentage later always scales from the
     * original XML-defined size — never compounding on top of an
     * already-scaled value.
     *
     * IMPORTANT: this never touches Configuration, density, or Context — it
     * only ever calls TextView.setTextSize(COMPLEX_UNIT_PX, ...) on Views
     * that already exist. That's what keeps width/height math completely
     * unaffected by this feature.
     */
    fun applyToViewHierarchy(root: View?) {
        if (root == null) return
        val percent = getFontScalePercent(root.context)
        applyRecursive(root, percent / 100f)
    }

    private fun applyRecursive(view: View, scale: Float) {
        if (view is TextView) {
            val baseSizePx = baseTextSizePx(view)
            view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, baseSizePx * scale)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyRecursive(view.getChildAt(i), scale)
            }
        }
    }

    // Tag key used to remember each TextView's original (100%) text size in
    // pixels, so re-applying a new percentage later (e.g. the user moves the
    // slider again without recreating the screen) always scales from the
    // *original* XML-defined size rather than an already-scaled value.
    // Fixed hex value (not hashCode()) to match the tag-key convention used
    // elsewhere in the app (FontManager.TAG_WATCHER, TAG_LISTENER, DECOR_TAG)
    // and to guarantee stability across Kotlin/JVM versions.
    private const val TAG_KEY_BASE_SIZE = 0x594F554E // "YOUN"

    private fun baseTextSizePx(textView: TextView): Float {
        val existing = textView.getTag(TAG_KEY_BASE_SIZE) as? Float
        if (existing != null) return existing
        val current = textView.textSize // already in px, as returned by TextView
        textView.setTag(TAG_KEY_BASE_SIZE, current)
        return current
    }
}
