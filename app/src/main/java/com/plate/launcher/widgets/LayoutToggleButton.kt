package com.plate.launcher.widgets

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
/**
 * A toggle button between List mode (lines) and Grid mode (2x2 squares)
 * Draws the icon directly on the Canvas with no external images
 * and animates when switching
 */
class LayoutToggleButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    // 0f = List mode (lines), 1f = Grid mode (squares)
    private var progress = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        strokeCap = Paint.Cap.ROUND
    }

    private var iconColor = 0xFFFFFFFF.toInt()

    init {
        // Get the color from the theme
        val ta = context.obtainStyledAttributes(intArrayOf(android.R.attr.colorForeground))
        iconColor = ta.getColor(0, 0xFFFFFFFF.toInt())
        ta.recycle()
        isClickable = true
        isFocusable = true
    }

    /**
     * Updates the progress with a smooth animation
     * progress = 0 → List | progress = 1 → Grid
     */
    fun setProgress(target: Float, animate: Boolean = true) {
        if (!animate) { progress = target; invalidate(); return }
        ValueAnimator.ofFloat(progress, target).apply {
            duration = 280
            interpolator = DecelerateInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = w * 0.22f
        val gap = w * 0.12f
        val r   = w * 0.08f          // corner radius for the squares

        paint.color = iconColor

        // Compute the lerp between List and Grid
        val p = progress

        // ── List mode: 3 horizontal lines ─────────────────────────────────────────
        // Each line has: its y center, x_start, x_end, height
        // ── Grid mode: 4 squares 2x2 ──────────────────────────────────────────

        // Compute the positions of the 4 elements
        val halfW = (w - pad * 2 - gap) / 2f
        val halfH = (h - pad * 2 - gap) / 2f

        // Grid rects
        val g = listOf(
            RectF(pad,          pad,          pad + halfW,         pad + halfH),          // top-left
            RectF(pad + halfW + gap, pad,     w - pad,             pad + halfH),          // top-right
            RectF(pad,          pad + halfH + gap, pad + halfW,    h - pad),              // bottom-left
            RectF(pad + halfW + gap, pad + halfH + gap, w - pad,   h - pad)              // bottom-right
        )

        // List rects (3 lines → represented as thin wide rects)
        val lineH = (h - pad * 2) / 5f
        val l = listOf(
            RectF(pad, pad + lineH * 0.5f,  w - pad, pad + lineH * 1.5f),
            RectF(pad, pad + lineH * 2.2f,  w - pad, pad + lineH * 3.2f),
            RectF(pad, pad + lineH * 3.9f,  w - pad, pad + lineH * 4.9f)
        )

        // We draw the 4 squares with a lerp between Grid and List
        // The first element (top-left) → expands to become the first line
        // Elements 1,2,3 fade out gradually and merge

        // Draw the four elements with morphing animation
        val invP = 1f - p  // the inverse of progress

        // Element 1: top-left → top line
        lerpRect(g[0], l[0], invP).let { rect ->
            paint.alpha = 255
            canvas.drawRoundRect(rect, lerp(r, lineH * 0.4f, invP), lerp(r, lineH * 0.4f, invP), paint)
        }

        // Element 2: top-right → fades out (alpha 0 in List, 255 in Grid)
        lerpRect(g[1], midRect(l[0], l[1]), invP).let { rect ->
            paint.alpha = (255 * p).toInt()
            canvas.drawRoundRect(rect, r, r, paint)
        }

        // Element 3: bottom-left → middle line
        lerpRect(g[2], l[1], invP).let { rect ->
            paint.alpha = lerp(255f * p, 255f, invP).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(rect, lerp(r, lineH * 0.4f, invP), lerp(r, lineH * 0.4f, invP), paint)
        }

        // Element 4: bottom-right → bottom line
        lerpRect(g[3], l[2], invP).let { rect ->
            paint.alpha = 255
            canvas.drawRoundRect(rect, lerp(r, lineH * 0.4f, invP), lerp(r, lineH * 0.4f, invP), paint)
        }

        paint.alpha = 255
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun lerpRect(a: RectF, b: RectF, t: Float) = RectF(
        lerp(a.left, b.left, t),
        lerp(a.top,  b.top,  t),
        lerp(a.right, b.right, t),
        lerp(a.bottom, b.bottom, t)
    )

    private fun midRect(a: RectF, b: RectF) = RectF(
        (a.left + b.left) / 2f,
        (a.top  + b.top ) / 2f,
        (a.right + b.right) / 2f,
        (a.bottom + b.bottom) / 2f
    )
}
