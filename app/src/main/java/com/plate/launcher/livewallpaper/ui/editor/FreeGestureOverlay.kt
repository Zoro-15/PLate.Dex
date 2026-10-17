package com.plate.launcher.livewallpaper.ui.editor

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * FreeGestureOverlay — a transparent layer over the live preview (GLSurfaceView) in
 * the simplified "Free" mode.
 *
 * ┌────────────────────────────────────────────────────────────────────────┐
 * │  The idea: instead of 5 complex sliders, the user controls with their  │
 * │  finger directly over the video:                                       │
 * │                                                                          │
 * │   • One finger moving  → Pan (moves position: translateX / translateY) │
 * │   • Two fingers spreading/pinching → Pinch (zoom in/out: freeScaleX/Y)  │
 * │   • Two fingers rotating around each other → Rotate (rotationDeg)      │
 * │                                                                          │
 * │  All three work at the same time (natural multi-touch) exactly like    │
 * │  any photo editing app (the usual Pinch-to-zoom + Rotate on mobile).    │
 * └────────────────────────────────────────────────────────────────────────┘
 *
 * This View doesn't draw anything itself (fully transparent) — it just captures
 * touch and reports the relative changes (delta) via [onGesture] for the caller
 * to apply to the current [com.plate.launcher.livewallpaper.data.WallpaperConfig].
 *
 * ── Rebuild notes: why did the sensitivity used to feel "off"? ─────────────
 * 1) When one of two fingers was lifted (ACTION_POINTER_UP), [lastSpan]/[lastAngle]
 *    weren't reset, so the first movement after putting two fingers back down was
 *    compared against values from a completely earlier touch → a sudden jump in
 *    scale/rotation.
 * 2) There was no minimum deadzone for span before dividing scaleFactor =
 *    span/lastSpan, so if the two fingers were very close together, any slight
 *    tremor produced a division by a very small number → a huge zoom in/out jump
 *    from barely any movement.
 * 3) There was no smoothing and no "touch slop" at the start of each touch, so
 *    natural finger tremor (especially on 90Hz/120Hz screens) caused small but
 *    very frequent updates that felt like "oversensitivity."
 *
 * The fix below: a full, strict reset of state on any change in finger count +
 * a minimum deadzone for span + touch slop for the first movement after each
 * (re)start of a touch + light exponential smoothing (EMA) on the delta values
 * sent, with no change to the public onGesture contract (same signature, same
 * meaning) so nothing using it breaks.
 */
class FreeGestureOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    /**
     * Called on every finger movement with "delta" values (the difference since the
     * last call), not absolute values:
     * @param dx horizontal offset difference, as a fraction of the View's width (roughly -1f..1f)
     * @param dy vertical offset difference, as a fraction of the View's height (positive = downward, matching Android's touch system)
     * @param scaleFactor a multiplier for zoom in/out (1f = no change, >1 zoom in, <1 zoom out)
     * @param rotationDeltaDeg rotation angle difference in degrees (positive = clockwise, matching what the user sees with their own eyes)
     */
    var onGesture: ((dx: Float, dy: Float, scaleFactor: Float, rotationDeltaDeg: Float) -> Unit)? = null

    /** Called once when a new touch starts and once when all fingers are lifted — useful for pausing/resuming any helper drawing */
    var onGestureStateChanged: ((active: Boolean) -> Unit)? = null

    private var activePointerCount = 0

    // ── Single-touch state (Pan) ───────────────────────────────────────────
    private var lastX = 0f
    private var lastY = 0f

    // ── Two-finger state (Pinch + Rotate) ──────────────────────────────────────
    private var lastSpan = 0f
    private var lastAngle = 0f

    /**
     * Set every time we reset the reference (a new finger went down, or a finger
     * was lifted and the count changed). The first ACTION_MOVE after that is only
     * consumed to update the "last position" without sending any onGesture — this
     * fully prevents a jump on the very first moment (touch slop).
     */
    private var awaitingFreshReference = true

    // ── Filtering/safety constants ───────────────────────────────────────────
    private companion object {
        /** Minimum pixel distance between two fingers to be considered valid for computing scale/rotation. Below this = fingers nearly touching, where any tremor gives unreasonable values. */
        const val MIN_VALID_SPAN_PX = 24f
        /** Maximum zoom in/out factor allowed within a single frame, as an extra safety guard after the deadzone. */
        const val MAX_SCALE_FACTOR_PER_FRAME = 1.25f
        const val MIN_SCALE_FACTOR_PER_FRAME = 0.8f
        /** Exponential smoothing (EMA) coefficient. The closer to 1, the less smoothing (closer to instant response). 0.55 gives a "live" feel without tremor. */
        const val SMOOTHING_ALPHA = 0.55f
    }

    /** Minimum movement threshold (in pixels) before it's considered an intentional drag, taken from the same system setting every Android UI (ScrollView, RecyclerView...) uses to distinguish incidental touch from a real drag. */
    private val touchSlopPx: Float by lazy { ViewConfiguration.get(context).scaledTouchSlop.toFloat() }

    // Smoothed delta values (EMA) — reset with every new reference start so old values don't "leak" into a new touch
    private var smoothedDx = 0f
    private var smoothedDy = 0f
    private var smoothedScale = 1f
    private var smoothedRotation = 0f

    init {
        // Fully transparent visually, but touch-active (needs a non-null background so
        // onTouchEvent is reliable across all Android devices when using the full touchable area)
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerCount = 1
                resetSingleFingerReference(event.x, event.y)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                activePointerCount = event.pointerCount
                if (activePointerCount >= 2) {
                    resetTwoFingerReference(event)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (activePointerCount >= 2 && event.pointerCount >= 2) {
                    handleTwoFingerMove(event)
                } else if (activePointerCount == 1) {
                    handleSingleFingerMove(event)
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // One finger lifted out of two or more — go back to single-finger Pan
                // mode (or stop everything if none remain), and fully reset the
                // reference (position + span/angle) to avoid any jump or leaking of
                // old values.
                val remainingIndex = if (event.actionIndex == 0) 1 else 0
                activePointerCount = (event.pointerCount - 1).coerceAtLeast(0)

                if (activePointerCount == 1 && remainingIndex < event.pointerCount) {
                    resetSingleFingerReference(
                        event.getX(remainingIndex),
                        event.getY(remainingIndex)
                    )
                }
                // Explicitly reset the two-finger reference regardless of what
                // remains, so an old lastSpan/lastAngle isn't used if two new
                // fingers come down quickly after this.
                lastSpan = 0f
                lastAngle = 0f
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerCount = 0
                lastSpan = 0f
                lastAngle = 0f
                smoothedDx = 0f
                smoothedDy = 0f
                smoothedScale = 1f
                smoothedRotation = 0f
                awaitingFreshReference = true
                onGestureStateChanged?.invoke(false)
            }
        }
        return true
    }

    private fun resetSingleFingerReference(x: Float, y: Float) {
        lastX = x
        lastY = y
        awaitingFreshReference = true
        smoothedDx = 0f
        smoothedDy = 0f
        onGestureStateChanged?.invoke(true)
    }

    private fun resetTwoFingerReference(event: MotionEvent) {
        lastSpan = currentSpan(event)
        lastAngle = currentAngle(event)
        // Reset the Pan reference to the new midpoint so there's no sudden "jump"
        val mid = midpoint(event)
        lastX = mid.first
        lastY = mid.second
        awaitingFreshReference = true
        smoothedDx = 0f
        smoothedDy = 0f
        smoothedScale = 1f
        smoothedRotation = 0f
    }

    private fun handleTwoFingerMove(event: MotionEvent) {
        val span = currentSpan(event)
        val angle = currentAngle(event)
        val mid = midpoint(event)

        // First movement after any reference reset: just store the current values
        // as the new reference without emitting onGesture, fully preventing a
        // jump on the very first moment.
        if (awaitingFreshReference) {
            lastSpan = span
            lastAngle = angle
            lastX = mid.first
            lastY = mid.second
            awaitingFreshReference = false
            return
        }

        // Deadzone: if the two fingers are nearly touching, computing scale/rotation
        // from them is completely unreliable (any slight shake produces huge values)
        // — we ignore this frame for scale/rotation only, but let pan continue.
        val spanIsReliable = lastSpan >= MIN_VALID_SPAN_PX && span >= MIN_VALID_SPAN_PX

        val rawScaleFactor = if (spanIsReliable) {
            (span / lastSpan).coerceIn(MIN_SCALE_FACTOR_PER_FRAME, MAX_SCALE_FACTOR_PER_FRAME)
        } else {
            1f
        }

        var rawRotationDelta = if (spanIsReliable) angle - lastAngle else 0f
        // Normalize the angle difference to the [-180, 180] range to avoid jumps when crossing the 360° boundary
        if (rawRotationDelta > 180f) rawRotationDelta -= 360f
        if (rawRotationDelta < -180f) rawRotationDelta += 360f

        val rawDx = (mid.first - lastX) / width.coerceAtLeast(1)
        val rawDy = (mid.second - lastY) / height.coerceAtLeast(1)

        // Light exponential smoothing (EMA) that absorbs natural finger tremor
        // without any noticeable delay, so the "oversensitivity" feeling
        // disappears while the response stays nearly instant.
        smoothedDx = lerp(smoothedDx, rawDx, SMOOTHING_ALPHA)
        smoothedDy = lerp(smoothedDy, rawDy, SMOOTHING_ALPHA)
        smoothedScale = lerp(smoothedScale, rawScaleFactor, SMOOTHING_ALPHA)
        smoothedRotation = lerp(smoothedRotation, rawRotationDelta, SMOOTHING_ALPHA)

        onGesture?.invoke(smoothedDx, smoothedDy, smoothedScale, smoothedRotation)

        lastSpan = span
        lastAngle = angle
        lastX = mid.first
        lastY = mid.second
    }

    private fun handleSingleFingerMove(event: MotionEvent) {
        // First movement after a single-finger reference reset: just lock in the new reference, without sending anything.
        if (awaitingFreshReference) {
            val totalMove = hypot(event.x - lastX, event.y - lastY)
            // touch slop: movement smaller than the threshold is considered normal
            // touch tremor, not an intentional drag yet; we wait until the movement
            // exceeds this threshold before actually sending onGesture, preventing
            // a "jump" at the start of the touch.
            if (totalMove < touchSlopPx) return
            lastX = event.x
            lastY = event.y
            awaitingFreshReference = false
            return
        }

        val rawDx = (event.x - lastX) / width.coerceAtLeast(1)
        val rawDy = (event.y - lastY) / height.coerceAtLeast(1)

        smoothedDx = lerp(smoothedDx, rawDx, SMOOTHING_ALPHA)
        smoothedDy = lerp(smoothedDy, rawDy, SMOOTHING_ALPHA)

        onGesture?.invoke(smoothedDx, smoothedDy, 1f, 0f)
        lastX = event.x
        lastY = event.y
    }

    private fun lerp(current: Float, target: Float, alpha: Float): Float =
        current + (target - current) * alpha

    private fun midpoint(event: MotionEvent): Pair<Float, Float> {
        val x = (event.getX(0) + event.getX(1)) / 2f
        val y = (event.getY(0) + event.getY(1)) / 2f
        return x to y
    }

    private fun currentSpan(event: MotionEvent): Float {
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return hypot(dx, dy)
    }

    /** The angle of the line between the two fingers in degrees — positive clockwise to match what the user sees */
    private fun currentAngle(event: MotionEvent): Float {
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        return Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    }
}
