package com.plate.launcher.utils

import android.content.Context
import android.widget.ImageView
import android.widget.TextView
import androidx.preference.PreferenceManager

/**
 * A shared utility to scale icon size and font size up/down in the dock and
 * the app grid screen, plus the custom Badge logic (a general number shown on
 * every icon) with automatic number conversion to the current language's
 * format (e.g. Arabic-Indic numerals ١٢٣ for Arabic).
 */
object IconScaleUtils {

    private const val KEY_ICON_SCALE = "icon_scale_percent"
    private const val KEY_FONT_SCALE = "label_font_scale_percent"
    private const val KEY_BADGE_ENABLED = "custom_badge_enabled"
    private const val KEY_BADGE_TEXT = "custom_badge_text"

    const val ICON_SCALE_DEFAULT = 100
    const val ICON_SCALE_MIN = 50
    const val ICON_SCALE_MAX = 150

    const val FONT_SCALE_DEFAULT = 100
    const val FONT_SCALE_MIN = 50
    const val FONT_SCALE_MAX = 150

    // Base dimensions as defined in the original XML (before any scaling)
    private const val BASE_DOCK_ICON_DP = 44
    private const val BASE_GRID_ICON_DP = 48
    private const val BASE_DOCK_LABEL_SP = 9f
    private const val BASE_GRID_LABEL_SP = 12f

    fun getIconScalePercent(context: Context): Int =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getInt(KEY_ICON_SCALE, ICON_SCALE_DEFAULT)
            .coerceIn(ICON_SCALE_MIN, ICON_SCALE_MAX)

    fun setIconScalePercent(context: Context, percent: Int) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putInt(KEY_ICON_SCALE, percent.coerceIn(ICON_SCALE_MIN, ICON_SCALE_MAX))
            .apply()
    }

    fun getFontScalePercent(context: Context): Int =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getInt(KEY_FONT_SCALE, FONT_SCALE_DEFAULT)
            .coerceIn(FONT_SCALE_MIN, FONT_SCALE_MAX)

    fun setFontScalePercent(context: Context, percent: Int) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putInt(KEY_FONT_SCALE, percent.coerceIn(FONT_SCALE_MIN, FONT_SCALE_MAX))
            .apply()
    }

    /** Applies the scaled icon size (in pixels) to a dock/grid icon's ImageView. */
    fun applyIconSize(imageView: ImageView, context: Context, isDockIcon: Boolean) {
        val baseDp = if (isDockIcon) BASE_DOCK_ICON_DP else BASE_GRID_ICON_DP
        val scale = getIconScalePercent(context) / 100f
        val sizePx = Utils.dpToPx(context, (baseDp * scale).toInt())
        val lp = imageView.layoutParams
        if (lp != null) {
            lp.width = sizePx
            lp.height = sizePx
            imageView.layoutParams = lp
        }
    }

    /** Applies the scaled label font size (app name) to a TextView. */
    fun applyLabelFontSize(textView: TextView, context: Context, isDockLabel: Boolean) {
        val baseSp = if (isDockLabel) BASE_DOCK_LABEL_SP else BASE_GRID_LABEL_SP
        val scale = getFontScalePercent(context) / 100f
        textView.textSize = baseSp * scale
    }

    // ── Custom Badge (a general number on every icon) ────────────────────────────

    fun isCustomBadgeEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(KEY_BADGE_ENABLED, false)

    fun setCustomBadgeEnabled(context: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(KEY_BADGE_ENABLED, enabled)
            .apply()
    }

    fun getCustomBadgeRawText(context: Context): String =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getString(KEY_BADGE_TEXT, "") ?: ""

    fun setCustomBadgeRawText(context: Context, text: String) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(KEY_BADGE_TEXT, text.trim())
            .apply()
    }

    /**
     * Returns the badge text ready for display. Always shown with Latin
     * numerals (0-9) regardless of the device's language — to keep the
     * number's appearance consistent across every language.
     */
    fun getLocalizedBadgeDisplayText(context: Context): String {
        return getCustomBadgeRawText(context)
    }
}
