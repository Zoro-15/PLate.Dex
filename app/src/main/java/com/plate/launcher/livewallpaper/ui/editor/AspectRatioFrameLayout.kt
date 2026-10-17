package com.plate.launcher.livewallpaper.ui.editor

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * AspectRatioFrameLayout — a simple container that enforces a fixed
 * width:height ratio (like 16:9) on itself, computed automatically from
 * whatever width is actually available (a small phone or a large tablet) —
 * this is exactly what "automatically detects the dimensions" means, instead
 * of a fixed pixel value forced in manually.
 *
 * A lightweight alternative to androidx.constraintlayout.widget.ConstraintLayout
 * (not already added as a dependency in this project) — no need to add a
 * whole library just to lock in a single aspect ratio in one place.
 *
 * Usage: layout_width="match_parent", the height is computed automatically =
 * the actual measured width × (ratioHeight / ratioWidth), recomputed
 * automatically on any size change (device rotation, a tablet with a
 * different width, split screen, etc.).
 */
class AspectRatioFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    /** The width ratio, defaults to 16 (can be changed programmatically later if needed) */
    var ratioWidth: Float = 16f
    /** The height ratio, defaults to 9 */
    var ratioHeight: Float = 9f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        if (width > 0 && ratioWidth > 0f) {
            val calculatedHeight = (width * (ratioHeight / ratioWidth)).toInt()
            val newHeightSpec = MeasureSpec.makeMeasureSpec(calculatedHeight, MeasureSpec.EXACTLY)
            super.onMeasure(widthMeasureSpec, newHeightSpec)
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
}
