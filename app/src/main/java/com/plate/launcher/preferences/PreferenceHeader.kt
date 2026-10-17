package com.plate.launcher.preferences

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.plate.launcher.R

/**
 * YoukiDex · Material 3 — PreferenceHeader
 * ═══════════════════════════════════════════
 * Settings list item with colored icon container (Android 12 style)
 *  [ Icon ]  Title      >
 *           Subtitle
 */
class PreferenceHeader(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {
    init {
        layoutResource = R.layout.preference_header
        isSingleLineTitle = true
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val iconContainer = holder.findViewById(R.id.ng_icon_container) as? FrameLayout
        val iconView = holder.findViewById(R.id.ng_icon) as? ImageView

        iconView?.apply {
            if (icon != null) {
                setImageDrawable(icon)
                visibility = View.VISIBLE
                // FIX: Use Material You primary container color for icon tint
                val ctx = context
                val tintColor = try {
                    if (com.google.android.material.color.DynamicColors.isDynamicColorAvailable()) {
                        com.plate.launcher.utils.ColorUtils.getThemeColors(ctx, false)[0]
                    } else {
                        ctx.getColor(R.color.ng_primary)
                    }
                } catch (e: Exception) {
                    ctx.getColor(R.color.ng_primary)
                }
                setColorFilter(tintColor, android.graphics.PorterDuff.Mode.SRC_ATOP)
            } else {
                visibility = View.GONE
                iconContainer?.visibility = View.GONE
            }
        }

        // Apply dynamic container background color
        iconContainer?.apply {
            val ctx = context
            try {
                val bgColor = if (com.google.android.material.color.DynamicColors.isDynamicColorAvailable()) {
                    // Use primaryContainer with 25% alpha for subtle tint
                    val pc = com.plate.launcher.utils.ColorUtils.getThemeColors(ctx, true)[2] // colorPrimaryContainer dark
                    android.graphics.Color.argb(
                        80, // ~30% alpha
                        android.graphics.Color.red(pc),
                        android.graphics.Color.green(pc),
                        android.graphics.Color.blue(pc)
                    )
                } else {
                    ctx.getColor(R.color.ng_secondary_container)
                }
                background?.setColorFilter(bgColor, android.graphics.PorterDuff.Mode.SRC_IN)
            } catch (e: Exception) {}
        }

        // Summary
        val summaryView = holder.findViewById(android.R.id.summary) as? TextView
        summaryView?.apply {
            val s = summary
            if (!s.isNullOrEmpty()) {
                text = s
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }

        // Soft entrance animation — each row fades + rises in slightly on first bind,
        // staggered by its position so the list feels like it's settling into place
        // rather than a hard cut. Only plays once per row (tracked via a tag on the
        // itemView) — RecyclerView rebinds visible rows on things like scroll/theme
        // changes, and re-animating every rebind would look like the whole screen keeps
        // twitching rather than a one-time entrance.
        val itemView = holder.itemView
        if (itemView.getTag(R.id.preference_row_animated_tag) != true) {
            itemView.setTag(R.id.preference_row_animated_tag, true)
            itemView.alpha = 0f
            itemView.translationY = 24f
            val position = holder.bindingAdapterPosition.coerceAtLeast(0)
            itemView.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay((position * 28L).coerceAtMost(280L))
                .setDuration(220L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }
}
