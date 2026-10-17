package com.plate.launcher.utils

import android.view.GestureDetector
import android.view.MotionEvent
import kotlin.math.atan2

/**
 * OnSwipeListener — direct port of `getAngle()`/`Direction.fromAngle()`,
 * reverted from window_geometry.rs::swipe_direction back to plain Kotlin.
 * Boundary values (and check order — UP first, RIGHT's range split across
 * the 0/360 wraparound, DOWN next, everything else falls to LEFT) preserved
 * exactly.
 */
open class OnSwipeListener : GestureDetector.SimpleOnGestureListener() {
    override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
        if (e1 != null) {
            val direction = swipeDirection(e1.x, e1.y, e2.x, e2.y)
            return onSwipe(direction)
        }
        return false
    }

    open fun onSwipe(direction: Direction): Boolean {
        return false
    }

    enum class Direction {
        UP,
        DOWN,
        LEFT,
        RIGHT,
    }

    private fun swipeAngle(x1: Float, y1: Float, x2: Float, y2: Float): Double {
        val rad = atan2((y1 - y2).toDouble(), (x2 - x1).toDouble()) + Math.PI
        return (rad * 180.0 / Math.PI + 180.0) % 360.0
    }

    private fun inRange(angle: Double, init: Float, end: Float): Boolean =
        angle >= init && angle < end

    private fun swipeDirection(x1: Float, y1: Float, x2: Float, y2: Float): Direction {
        val angle = swipeAngle(x1, y1, x2, y2)
        return when {
            inRange(angle, 45f, 135f)                              -> Direction.UP
            inRange(angle, 0f, 45f) || inRange(angle, 315f, 360f)   -> Direction.RIGHT
            inRange(angle, 225f, 315f)                              -> Direction.DOWN
            else                                                     -> Direction.LEFT
        }
    }
}
