package com.plate.launcher.utils

import android.content.Context
import android.graphics.PixelFormat
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import com.plate.launcher.R

/**
 * Draws a small arrow cursor as a system overlay on a given [Display] and
 * lets it be moved by relative deltas — the visual half of "phone screen
 * acts as a trackpad for a display directly connected to this phone"
 * (as opposed to UhidManager, which is for a *separate* PC over USB OTG).
 *
 * The cursor itself is purely visual (a TYPE_APPLICATION_OVERLAY window,
 * FLAG_NOT_TOUCHABLE so it never intercepts input meant for whatever is
 * under it). Actually clicking whatever the cursor is over is a separate
 * step — DockService.dispatchCursorClick() uses AccessibilityService's
 * dispatchGesture() at the cursor's current (x, y), since a
 * FLAG_NOT_TOUCHABLE overlay window has no way to inject touches itself.
 *
 * One instance = one cursor on one display. DockService owns the instance
 * tied to the secondary display and forwards trackpad deltas into it.
 */
class CursorOverlayManager(private val context: Context, private val display: Display) {

    // FIX: crash risk — createDisplayContext() throws IllegalArgumentException
    // if [display] has already gone invalid by the time this constructor
    // runs (classic race: the whole point of calling this is a display was
    // JUST attached/detached — see onDisplayAdded/onDisplayRemoved in
    // DockService — so a hotplug racing this exact moment is the expected
    // case, not a rare edge case). This ran completely unguarded before:
    // a property initializer throwing means the constructor itself throws,
    // and both call sites (forSecondaryDisplay below, and
    // DockService.startCursorOverlayIfNeeded) called it with no try/catch
    // of their own — an accessibility service crashing here would have
    // taken the whole dock down with it. windowManager is nullable now;
    // isShowing/show()/moveBy() all treat a null windowManager as "not
    // available", the same as any other overlay-permission-missing case.
    private val windowManager: WindowManager? = try {
        val displayContext = context.createDisplayContext(display)
        displayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    } catch (e: Exception) {
        null
    }

    private var cursorView: ImageView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    /** Current cursor position, in the target display's pixel space (top-left origin). */
    var x: Int = 0
        private set
    var y: Int = 0
        private set

    val isShowing: Boolean get() = cursorView != null

    /** Adds the cursor view, centered on the target display. */
    fun show() {
        val wm = windowManager ?: return // constructor's try/catch already failed — nothing to add a view to
        if (isShowing) return

        val metrics = DeviceUtils.getDisplayMetrics(context, display.displayId)
        x = metrics.widthPixels / 2
        y = metrics.heightPixels / 2

        val iv = ImageView(context).apply {
            setImageResource(R.drawable.ic_cursor_arrow)
            // Full opacity, no tinting — the drawable itself is a plain
            // black-with-white-outline arrow, visible on any background.
        }

        val lp = WindowManager.LayoutParams().apply {
            format = PixelFormat.TRANSLUCENT
            // NOT_FOCUSABLE + NOT_TOUCHABLE: the cursor is purely visual,
            // it must never itself intercept a touch/gesture meant for the
            // window underneath it — that's what dispatchGesture() targets
            // separately, at this window's last reported (x, y).
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            width = Utils.dpToPx(context, CURSOR_SIZE_DP)
            height = Utils.dpToPx(context, CURSOR_SIZE_DP)
            gravity = Gravity.TOP or Gravity.START
            this.x = this@CursorOverlayManager.x
            this.y = this@CursorOverlayManager.y
        }

        try {
            wm.addView(iv, lp)
            cursorView = iv
            layoutParams = lp
        } catch (e: Exception) {
            // Overlay permission missing, or the display went away between
            // the caller's check and this call — leave cursorView null so
            // isShowing correctly reports "not showing" and callers can
            // retry later rather than crash.
        }
    }

    /**
     * Moves the cursor by a relative pixel delta, clamped to the target
     * display's bounds. Returns the new (x, y) so the caller (DockService)
     * can pass the same coordinates into dispatchGesture() for clicks.
     */
    fun moveBy(dx: Int, dy: Int): Pair<Int, Int> {
        val wm = windowManager ?: return x to y
        val view = cursorView ?: return x to y
        val lp = layoutParams ?: return x to y
        val metrics = DeviceUtils.getDisplayMetrics(context, display.displayId)

        x = (x + dx).coerceIn(0, metrics.widthPixels)
        y = (y + dy).coerceIn(0, metrics.heightPixels)
        lp.x = x
        lp.y = y
        try {
            wm.updateViewLayout(view, lp)
        } catch (e: Exception) {
            // Display detached mid-drag — swallow; the next moveBy() call
            // (or show()'s own try/catch) will surface the failure state.
        }
        return x to y
    }

    /** Removes the cursor view. Safe to call even if not currently showing. */
    fun dismiss() {
        cursorView?.let {
            try { windowManager?.removeView(it) } catch (e: Exception) {}
        }
        cursorView = null
        layoutParams = null
    }

    companion object {
        private const val CURSOR_SIZE_DP = 24

        /**
         * Convenience for callers that only have a Context — resolves the
         * attached secondary display (if any) and builds a manager for it.
         * Returns null if no secondary display is currently attached.
         */
        fun forSecondaryDisplay(context: Context): CursorOverlayManager? {
            val display = DeviceUtils.getSecondaryDisplay(context) ?: return null
            return CursorOverlayManager(context, display)
        }
    }
}
