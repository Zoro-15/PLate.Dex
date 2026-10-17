package com.plate.launcher.activities

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButtonToggleGroup
import com.plate.launcher.R
import com.plate.launcher.services.ACTION_CURSOR_CLICK
import com.plate.launcher.services.ACTION_CURSOR_MOVE
import com.plate.launcher.services.ACTION_CURSOR_STATUS
import com.plate.launcher.services.ACTION_QUERY_CURSOR_STATUS
import com.plate.launcher.services.DOCK_SERVICE_ACTION
import com.plate.launcher.services.EXTRA_BUTTON
import com.plate.launcher.services.EXTRA_CURSOR_AVAILABLE
import com.plate.launcher.services.EXTRA_DX
import com.plate.launcher.services.EXTRA_DY
import com.plate.launcher.utils.DeviceUtils
import com.plate.launcher.utils.ShizukoManager
import com.plate.launcher.utils.UhidManager

/**
 * Trackpad screen — turns the phone's own screen into a real laptop-style
 * touchpad, not a row of separate buttons:
 *
 *   ┌──────────────────────────────┐
 *   │                               │
 *   │      movement surface         │  drag = move cursor
 *   │   tap = click, double-tap =   │  tap = click, double-tap = dbl-click
 *   │        double click           │
 *   │                               │
 *   ├───────────────┬───────────────┤  divider
 *   │  left click   │  right click  │  click zone — taps only, no drag
 *   └───────────────┴───────────────┘
 *
 * Two independent things vary with mode, everything else (the gestures
 * above) stays identical:
 *
 *  - DIRECT: a screen/TV is plugged straight into the phone (HDMI/USB-C)
 *    and the phone itself is rendering YoukiDex's desktop UI onto it (the
 *    "Samsung DeX" scenario). Gestures move/click a cursor overlay drawn on
 *    that secondary display by DockService (CursorOverlayManager +
 *    AccessibilityService.dispatchGesture) — the phone screen itself is
 *    pure input, it never shows a cursor.
 *
 *  - OTG: the phone is plugged into a separate PC over USB, acting as a USB
 *    HID mouse for that PC (UhidManager, same as `scrcpy --otg`). The
 *    "output" here is a monitor attached to the *other* device entirely —
 *    the phone has no visibility into it and just streams HID reports.
 *
 * "uhid_mode_enabled" (AdvancedPreferences) only opened/closed the raw UHID
 * channel — sendMouseMove/sendMouseClick existed and worked but nothing
 * ever called them. Likewise DockService could draw a cursor and dispatch
 * gestures but had no input source driving it. This screen is the missing
 * input surface for both.
 *
 * Entry point: AdvancedPreferences' "Open Trackpad" row, right under the
 * OTG mode switch. Defaults to DIRECT mode if a secondary display is
 * currently attached, otherwise OTG — either can be switched at the top.
 */
class TrackpadActivity : BaseFontScaleActivity() {

    private val uhid by lazy { UhidManager.getInstance(this) }
    private lateinit var statusTv: TextView
    private lateinit var modeToggle: MaterialButtonToggleGroup

    private enum class Mode { DIRECT, OTG }
    private var mode = Mode.DIRECT

    // DIRECT mode only: whether DockService currently has a real cursor
    // overlay showing on a secondary display. Starts false (not true) —
    // "unknown yet" and "known unavailable" both mean the same thing here:
    // don't accept drags until DockService has actually confirmed it's
    // there. Gates wireMovementSurface/wireClickZone so a drag never
    // silently goes nowhere; see the receiver below and its Kdoc for how
    // this gets set.
    private var cursorAvailable = false
    private var cursorStatusReceiver: BroadcastReceiver? = null
    private val statusTimeoutHandler = Handler(Looper.getMainLooper())

    // Mouse buttons per the HID report descriptor in UhidManager: bit0=left, bit1=right.
    private val BUTTON_LEFT: Byte = 0x01
    private val BUTTON_RIGHT: Byte = 0x02

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trackpad)

        statusTv = findViewById(R.id.trackpad_status_tv)
        modeToggle = findViewById(R.id.trackpad_mode_toggle)
        findViewById<ImageButton>(R.id.trackpad_back_btn).setOnClickListener { finish() }

        // Default to whichever mode is actually usable right now: a secondary
        // display attached means DIRECT will work immediately; otherwise
        // OTG is the only one that stands a chance (still requires a PC on
        // the other end of the cable, but that can't be checked from here).
        val hasSecondaryDisplay = DeviceUtils.getSecondaryDisplay(this) != null
        mode = if (hasSecondaryDisplay) Mode.DIRECT else Mode.OTG
        modeToggle.check(if (mode == Mode.DIRECT) R.id.trackpad_mode_direct_btn else R.id.trackpad_mode_otg_btn)
        modeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            mode = if (checkedId == R.id.trackpad_mode_direct_btn) Mode.DIRECT else Mode.OTG
            onModeChanged()
        }

        wireMovementSurface()
        wireClickZone()
        registerCursorStatusReceiver()
        onModeChanged()
    }

    /**
     * Listens for DockService's ACTION_CURSOR_STATUS broadcasts — the fix
     * for the "drag on nothing" bug: previously this screen had no way to
     * find out that the accessibility service was never enabled (so
     * DockService's cursor overlay was never created in the first place)
     * or that the secondary display disconnected mid-drag, and just kept
     * silently accepting touches that went nowhere. Now [cursorAvailable]
     * gates whether DIRECT-mode touches do anything at all, and the UI
     * reflects the real state instead of assuming success.
     */
    private fun registerCursorStatusReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getStringExtra("action") != ACTION_CURSOR_STATUS) return
                statusTimeoutHandler.removeCallbacksAndMessages(null) // a real answer arrived — cancel any pending timeout
                cursorAvailable = intent.getBooleanExtra(EXTRA_CURSOR_AVAILABLE, false)
                if (mode == Mode.DIRECT) updateDirectModeStatusText()
            }
        }
        ContextCompat.registerReceiver(
            this, receiver, IntentFilter(DOCK_SERVICE_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        cursorStatusReceiver = receiver
    }

    override fun onDestroy() {
        super.onDestroy()
        statusTimeoutHandler.removeCallbacksAndMessages(null)
        try { cursorStatusReceiver?.let { unregisterReceiver(it) } } catch (e: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        // Status can go stale while the user was away (display (dis)connected,
        // UHID stopped from the settings switch, etc.) — refresh on return.
        onModeChanged()
    }

    private fun onModeChanged() {
        when (mode) {
            Mode.DIRECT -> {
                if (!DeviceUtils.isAccessibilityServiceEnabled(this)) {
                    // DockService (an AccessibilityService) never even
                    // started in this case — it has no receiver listening
                    // for our query at all, so there's no broadcast to wait
                    // for. Tell the user immediately and definitively
                    // rather than showing "Connecting…" forever.
                    cursorAvailable = false
                    statusTv.text = getString(R.string.trackpad_status_accessibility_needed)
                    Toast.makeText(this, R.string.trackpad_status_accessibility_needed, Toast.LENGTH_LONG).show()
                    return
                }
                // Ask DockService for the real current state rather than
                // assuming — it may already be showing a cursor (fast
                // path) or may take a moment to attach to a display that
                // was just plugged in.
                cursorAvailable = false
                statusTv.text = getString(R.string.trackpad_status_connecting)
                sendBroadcast(
                    Intent(DOCK_SERVICE_ACTION).setPackage(packageName)
                        .putExtra("action", ACTION_QUERY_CURSOR_STATUS)
                )
                // Safety net: the query above assumes DockService is alive
                // to answer it. If no ACTION_CURSOR_STATUS reply shows up
                // within the timeout (service crashed, got killed, or some
                // OEM background restriction ate the broadcast), stop
                // showing "Connecting…" forever and tell the truth instead
                // of leaving the user staring at a spinner-that-never-was.
                statusTimeoutHandler.removeCallbacksAndMessages(null)
                statusTimeoutHandler.postDelayed({
                    if (!cursorAvailable) updateDirectModeStatusText()
                }, CURSOR_STATUS_TIMEOUT_MS)
            }
            Mode.OTG -> ensureUhidStarted()
        }
    }

    private fun updateDirectModeStatusText() {
        val hasDisplay = DeviceUtils.getSecondaryDisplay(this) != null
        statusTv.text = getString(
            when {
                cursorAvailable -> R.string.trackpad_status_connected
                !hasDisplay -> R.string.trackpad_status_no_display
                else -> R.string.trackpad_status_error
            }
        )
    }

    /**
     * Starts UHID if it isn't already running — mirrors the switch logic in
     * AdvancedPreferences (Shizuku preferred, root as fallback) so opening
     * this screen directly still works even if the user never flipped the
     * settings switch first.
     */
    private fun ensureUhidStarted() {
        if (uhid.isRunning) {
            statusTv.text = getString(R.string.trackpad_status_connected)
            return
        }
        statusTv.text = getString(R.string.trackpad_status_connecting)
        val shizuku = ShizukoManager.getInstance(this)
        val onReady = {
            statusTv.text = getString(R.string.trackpad_status_connected)
        }
        val onError = { err: String ->
            statusTv.text = getString(R.string.trackpad_status_error)
            Toast.makeText(this, getString(R.string.uhid_mode_summary) + ": $err", Toast.LENGTH_LONG).show()
        }
        if (shizuku.hasPermission) {
            uhid.start(shizuku, onReady = onReady, onError = onError)
        } else {
            uhid.startAsRoot(onReady = onReady, onError = onError)
        }
    }

    // ── Movement surface: drag = move, tap = left click, double-tap = double click ──

    /**
     * GestureDetector — not hand-rolled tap/double-tap timing — disambiguates
     * a plain tap, a double-tap, and the start of a drag using the same
     * thresholds Android uses everywhere else (ViewConfiguration's
     * double-tap timeout/slop under the hood), so this surface "feels"
     * exactly as responsive as the rest of the OS instead of using an
     * arbitrary hand-picked delay.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun wireMovementSurface() {
        val surface = findViewById<View>(R.id.trackpad_surface)
        val hint = findViewById<TextView>(R.id.trackpad_hint_tv)

        // Density scales raw pixel drag deltas down to HID/gesture "counts" —
        // a trackpad drag covers far more screen pixels than a mouse should
        // visually travel, so without this the cursor would fly across the
        // secondary display from a small thumb movement.
        val density = resources.displayMetrics.density
        var lastX = 0f
        var lastY = 0f
        var dragging = false

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                sendClick("left")
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                sendClick("double")
                return true
            }
            // onScroll fires continuously during a drag, once the touch slop
            // is exceeded — this is what turns "hasn't been recognized as a
            // tap/double-tap yet" into an actual cursor-move drag.
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                dragging = true
                // GestureDetector reports scroll as "distance moved since the
                // last onScroll", already the exact per-frame delta we want
                // — and its sign convention is inverted relative to a plain
                // finger-direction delta (it's "how far the content should
                // scroll", not "which way the finger moved"), so negate it
                // to get the mouse-cursor-should-move-with-the-finger feel.
                sendMove((-distanceX / density).toInt(), (-distanceY / density).toInt())
                return true
            }
        })

        surface.setOnTouchListener { _, event ->
            // Gate: in DIRECT mode with no confirmed cursor, refuse the
            // touch outright instead of forwarding drags that would just
            // vanish into a dead broadcast — this is the actual fix for
            // the "drag on nothing, no feedback" bug. OTG mode has no such
            // gate: UhidManager's own isRunning check inside
            // sendMouseMove/sendMouseClick already covers it.
            if (mode == Mode.DIRECT && !cursorAvailable) {
                if (event.action == MotionEvent.ACTION_DOWN) {
                    Toast.makeText(this, statusTv.text, Toast.LENGTH_SHORT).show()
                }
                return@setOnTouchListener true
            }
            gestureDetector.onTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = false
                    lastX = event.x; lastY = event.y
                    hint.visibility = View.GONE
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) hint.visibility = View.VISIBLE
                }
            }
            true
        }
    }

    // ── Click zone: bottom strip, split left/right, taps only (no drag) ──

    private fun wireClickZone() {
        findViewById<View>(R.id.trackpad_left_click_zone).setOnClickListener {
            if (mode == Mode.DIRECT && !cursorAvailable) {
                Toast.makeText(this, statusTv.text, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sendClick("left")
        }
        findViewById<View>(R.id.trackpad_right_click_zone).setOnClickListener {
            if (mode == Mode.DIRECT && !cursorAvailable) {
                Toast.makeText(this, statusTv.text, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sendClick("right")
        }
    }

    // ── Output: routes to UHID (OTG) or DockService's cursor overlay (DIRECT) ──

    private fun sendMove(dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        when (mode) {
            // sendMouseMove clamps to HID's -127..127 relative range itself.
            Mode.OTG -> uhid.sendMouseMove(dx, dy)
            Mode.DIRECT -> sendBroadcast(
                Intent(DOCK_SERVICE_ACTION)
                    .setPackage(packageName)
                    .putExtra("action", ACTION_CURSOR_MOVE)
                    .putExtra(EXTRA_DX, dx)
                    .putExtra(EXTRA_DY, dy)
            )
        }
    }

    /** [kind]: "left", "right", or "double". */
    private fun sendClick(kind: String) {
        when (mode) {
            Mode.OTG -> when (kind) {
                "left" -> uhid.sendMouseClick(BUTTON_LEFT)
                "right" -> uhid.sendMouseClick(BUTTON_RIGHT)
                // No native "double click" HID report — a real mouse's
                // double-click is physically two presses too, so two
                // sendMouseClick calls with a short gap between them
                // (matched to the OS's own double-click window) is exactly
                // what a real double-clicking mouse would send.
                "double" -> {
                    uhid.sendMouseClick(BUTTON_LEFT)
                    findViewById<View>(R.id.trackpad_surface).postDelayed(
                        { uhid.sendMouseClick(BUTTON_LEFT) },
                        DOUBLE_CLICK_GAP_MS
                    )
                }
            }
            Mode.DIRECT -> sendBroadcast(
                Intent(DOCK_SERVICE_ACTION)
                    .setPackage(packageName)
                    .putExtra("action", ACTION_CURSOR_CLICK)
                    .putExtra(EXTRA_BUTTON, kind)
            )
        }
    }

    // Deliberately NOT stopping UHID, and NOT telling DockService to hide
    // its cursor, in onDestroy/onPause:
    //  - OTG: the whole point is the HID device stays registered with the
    //    PC (so it doesn't disappear from the OS's device list) even after
    //    backing out of this screen. Stopping is still available from
    //    AdvancedPreferences' "uhid_mode_enabled" switch.
    //  - DIRECT: the cursor overlay's lifecycle is owned by DockService's
    //    own display-attach/detach listener, not by this screen being open
    //    — leaving it running lets the user back out to check something on
    //    the phone and return without the cursor jumping or disappearing.

    companion object {
        private const val DOUBLE_CLICK_GAP_MS = 100L
        // How long to wait for DockService's ACTION_CURSOR_STATUS reply
        // before giving up and showing an error instead of "Connecting…"
        // forever — covers the service having crashed, been killed, or an
        // OEM background-broadcast restriction eating the query.
        private const val CURSOR_STATUS_TIMEOUT_MS = 2000L
    }
}
