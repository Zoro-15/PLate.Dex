package com.plate.launcher.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * The actual "instagram/whatsapp-style" crop surface: shows the picked
 * image full-bleed behind a dimmed mask with a circular hole in it, and
 * lets the person drag / pinch-zoom the image *underneath* that fixed
 * circle to choose what ends up inside it. [exportCroppedBitmap] then
 * bakes exactly what's visible inside the circle into a new square
 * Bitmap — this is the piece that was missing before (avatars were
 * being saved un-cropped, which is why edges sometimes stuck out past
 * the round avatar view instead of always filling it).
 */
class CircleCropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var source: Bitmap? = null
    private val matrix = Matrix()

    // Bounds of the fixed circular "hole" the user is cropping into,
    // computed once we know our own size (see onSizeChanged).
    private var circleCx = 0f
    private var circleCy = 0f
    private var circleRadius = 0f

    private var minScale = 1f
    private var maxScale = 1f
    private var curScale = 1f

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B3000000") // dim outside the circle
    }
    private val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.WHITE
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    // ── Gesture handling: one-finger drag + two-finger pinch ──────────
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            setScale(curScale * detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    })
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false

    fun setBitmap(bmp: Bitmap) {
        source = bmp
        if (width > 0 && height > 0) setupInitialTransform()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Circle takes up most of the view, leaving a little breathing
        // room on the sides so the mask is clearly visible.
        circleRadius = min(w, h) * 0.42f
        circleCx = w / 2f
        circleCy = h / 2f
        source?.let { setupInitialTransform() }
    }

    private fun setupInitialTransform() {
        val bmp = source ?: return
        val diameter = circleRadius * 2f
        // minScale = smallest zoom where the image still fully covers the
        // circle (no transparent gap at any edge, ever).
        minScale = max(diameter / bmp.width, diameter / bmp.height)
        maxScale = minScale * 4f
        curScale = minScale

        matrix.reset()
        matrix.postScale(curScale, curScale)
        val scaledW = bmp.width * curScale
        val scaledH = bmp.height * curScale
        // Center the scaled image over the circle.
        matrix.postTranslate(circleCx - scaledW / 2f, circleCy - scaledH / 2f)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (source == null) return false
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                isDragging = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging && !scaleDetector.isInProgress) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    translateClamped(dx, dy)
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
            }
        }
        invalidate()
        return true
    }

    private fun setScale(newScale: Float, focusX: Float, focusY: Float) {
        val clamped = newScale.coerceIn(minScale, maxScale)
        val factor = clamped / curScale
        curScale = clamped
        matrix.postScale(factor, factor, focusX, focusY)
        clampTranslation()
    }

    private fun translateClamped(dx: Float, dy: Float) {
        matrix.postTranslate(dx, dy)
        clampTranslation()
    }

    /** Keeps the image from being dragged/zoomed so far that a gap would
     *  show inside the crop circle — always clamps back so the circle
     *  stays fully covered by image content. */
    private fun clampTranslation() {
        val bmp = source ?: return
        val bounds = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        matrix.mapRect(bounds)

        var dx = 0f
        var dy = 0f

        val left = circleCx - circleRadius
        val right = circleCx + circleRadius
        val top = circleCy - circleRadius
        val bottom = circleCy + circleRadius

        if (bounds.left > left) dx += left - bounds.left
        if (bounds.right < right) dx += right - bounds.right
        if (bounds.top > top) dy += top - bounds.top
        if (bounds.bottom < bottom) dy += bottom - bounds.bottom

        if (dx != 0f || dy != 0f) matrix.postTranslate(dx, dy)
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = source ?: return

        // Draw the image, then punch a transparent circular hole through a
        // dim mask on top of it — this is what gives the "everything
        // outside the circle is dimmed, only the circle is your crop"
        // look, independent of the final export.
        val layerId = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawBitmap(bmp, matrix, bitmapPaint)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), maskPaint)
        canvas.drawCircle(circleCx, circleCy, circleRadius, holePaint)
        canvas.restoreToCount(layerId)

        canvas.drawCircle(circleCx, circleCy, circleRadius, ringPaint)
    }

    /**
     * Bakes exactly what's currently visible inside the crop circle into a
     * new square [Bitmap] (side length [outputSize]px), sampled straight
     * from the full-resolution source — not a screenshot of the low-res
     * on-screen view — so the saved avatar stays sharp regardless of how
     * small the crop circle was on screen.
     */
    fun exportCroppedBitmap(outputSize: Int = 512): Bitmap? {
        val bmp = source ?: return null
        val result = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        // Map: source bitmap -> on-screen matrix -> circle's top-left in
        // view space -> scaled up/down to outputSize.
        val exportScale = outputSize / (circleRadius * 2f)
        val exportMatrix = Matrix(matrix)
        exportMatrix.postTranslate(-(circleCx - circleRadius), -(circleCy - circleRadius))
        exportMatrix.postScale(exportScale, exportScale)

        canvas.drawBitmap(bmp, exportMatrix, bitmapPaint)
        return result
    }
}
