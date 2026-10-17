package com.plate.launcher.utils

import android.content.SharedPreferences
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator

/**
 * DockPositionUtils — single source of truth for "is the dock docked to the
 * top or bottom of the screen", and everything that depends on it:
 * WindowManager gravity, show/hide slide direction, and where satellite
 * windows (app menu, notification panel, power menu, context menus, toasts)
 * should sit relative to the dock.
 *
 * Restructured (user request — "remove left/right entirely, replace the
 * position dropdown with a single top/bottom toggle button, and restructure
 * this properly"): this used to support all four screen edges via a
 * BOTTOM/TOP/LEFT/RIGHT enum, including a vertical-dock content-rotation
 * scheme and a whole re-orientable RelativeLayout structure — that turned
 * out too unstable in practice (see the dock.xml/PerfectServer.kt history)
 * and left/right has been dropped rather than kept half-working. Every
 * function below is written for exactly two states now; there is no
 * "isVertical" concept left anywhere in this class or its callers.
 */
object DockPositionUtils {

    enum class Position { BOTTOM, TOP }

    /** Reads the "dock_position" preference (a simple boolean-backed toggle now, not a 4-way picker). Defaults to BOTTOM — matches every previous hardcoded behavior exactly, so existing users see no change unless they opt in. */
    fun get(prefs: SharedPreferences): Position =
        if (prefs.getString("dock_position", "bottom") == "top") Position.TOP else Position.BOTTOM

    fun isTop(prefs: SharedPreferences): Boolean = get(prefs) == Position.TOP

    /** Flips the stored preference to the other of the two positions and returns the new value — the entire implementation of the new toggle button. */
    fun toggle(prefs: SharedPreferences): Position {
        val next = if (get(prefs) == Position.TOP) Position.BOTTOM else Position.TOP
        prefs.edit().putString("dock_position", if (next == Position.TOP) "top" else "bottom").apply()
        return next
    }

    /** WindowManager gravity for the dock window itself, combined with the existing horizontal/round-dock centering logic. */
    fun dockGravity(position: Position, centered: Boolean): Int = when (position) {
        Position.BOTTOM -> Gravity.BOTTOM or (if (centered) Gravity.CENTER_HORIZONTAL else Gravity.START)
        Position.TOP    -> Gravity.TOP    or (if (centered) Gravity.CENTER_HORIZONTAL else Gravity.START)
    }

    /**
     * Show/hide slide animation for the dock, sliding in/out from the edge
     * it's docked to (bottom dock ↔ slides to/from bottom, top dock ↔
     * slides to/from top).
     *
     * [show] = true to animate in (appear), false to animate out (hide).
     * [distancePx] = how far to translate, same role the old fixed
     * "hideTransY" 8dp offset played — kept small and directional rather
     * than a full off-screen slide, matching the existing subtle dock
     * show/hide feel.
     */
    fun animateDockVisibility(
        view: View,
        position: Position,
        show: Boolean,
        distancePx: Float,
        durationMs: Long = 180L,
        onEnd: (() -> Unit)? = null
    ) {
        view.animate().cancel()
        val anim = view.animate().setDuration(durationMs)

        if (show) {
            // Coming from off-position, translating toward 0 into place.
            view.translationY = if (position == Position.TOP) -distancePx else distancePx
            view.scaleX = 0.90f; view.scaleY = 0.90f; view.alpha = 0f
            anim.scaleX(1f).scaleY(1f).alpha(1f)
                .translationY(0f)
                .setInterpolator(DecelerateInterpolator(2f))
        } else {
            val ty = if (position == Position.TOP) -distancePx else distancePx
            anim.scaleX(0.90f).scaleY(0.90f).alpha(0f)
                .translationY(ty)
                .setInterpolator(AccelerateInterpolator(2f))
        }

        anim.withEndAction { onEnd?.invoke() }.start()
    }

    /**
     * Which edge a satellite window that sits flush against the dock (app
     * menu, notification panel, power menu, context menus, toasts) should
     * anchor to and slide in/out from — always the same edge the dock
     * itself is docked to.
     */
    fun satelliteGravity(position: Position): Int = when (position) {
        Position.TOP    -> Gravity.TOP
        Position.BOTTOM -> Gravity.BOTTOM
    }
}
