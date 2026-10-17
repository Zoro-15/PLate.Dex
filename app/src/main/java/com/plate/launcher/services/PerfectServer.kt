package com.plate.launcher.services

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Notification
import android.app.PendingIntent
import android.app.PendingIntent.CanceledException
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.ActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.Display
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener
import android.view.ViewOutlineProvider
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.Animation
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.core.widget.addTextChangedListener
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.divider.MaterialDividerItemDecoration
import com.plate.launcher.R
import com.plate.launcher.activities.LAUNCHER_ACTION
import com.plate.launcher.activities.LAUNCHER_RESUMED
import com.plate.launcher.activities.LauncherActivity
import com.plate.launcher.activities.MainActivity
import com.plate.launcher.adapters.AppActionsAdapter
import com.plate.launcher.adapters.AppAdapter
import com.plate.launcher.adapters.AppAdapter.OnAppClickListener
import com.plate.launcher.adapters.AppShortcutAdapter
import com.plate.launcher.adapters.AppTaskAdapter
import com.plate.launcher.adapters.DisplaysAdapter
import com.plate.launcher.adapters.DockAppAdapter
import com.plate.launcher.adapters.DockAppAdapter.OnDockAppClickListener
import com.plate.launcher.adapters.NotificationAdapter
import com.plate.launcher.adapters.NotificationAdapter.OnNotificationClickListener
import com.plate.launcher.db.DBHelper
import com.plate.launcher.models.Action
import com.plate.launcher.models.App
import com.plate.launcher.models.AppTask
import com.plate.launcher.models.DockApp
import com.plate.launcher.preferences.NAV_LONG_ACTIONS
import com.plate.launcher.receivers.BatteryStatsReceiver
import com.plate.launcher.receivers.SoundEventsReceiver
import com.plate.launcher.utils.AppUtils
import com.plate.launcher.utils.AppUtils.makeActivityOptions
import com.plate.launcher.utils.ColorUtils
import com.plate.launcher.utils.CursorOverlayManager
import com.plate.launcher.utils.DeepShortcutManager
import com.plate.launcher.utils.DeviceUtils
import com.plate.launcher.utils.IconPackUtils
import com.plate.launcher.utils.MultiUserManager
import com.plate.launcher.utils.OnSwipeListener
import com.plate.launcher.utils.RootManager
import com.plate.launcher.utils.ShizukoManager
import com.plate.launcher.utils.Utils
import com.plate.launcher.utils.UserSwitcherPopup
import com.plate.launcher.widgets.HoverInterceptorLayout
import java.nio.charset.StandardCharsets
import java.net.URLEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.hardware.display.DisplayManager


// ╔══════════════════════════════════════════════════════════════╗
// ║            ⚡  سيرفر الكمال — منسق الخدمات ⚡               ║
// ║  يربط DockService ↔ NotificationService ↔ DockTileService   ║
// ║  ويحل مشكلة عدم تزامن الألوان بين الخدمات                  ║
// ╚══════════════════════════════════════════════════════════════╝
internal object PerfectServer {

    /** مرجع مباشر للخدمات — يُعيَّن في onCreate ويُصفَّر في onDestroy */
    @Volatile var dock: DockService? = null
    @Volatile var notifications: NotificationService? = null

    /**
     * مزامنة كاملة للألوان بين الخدمتين فوراً.
     * يُستدعى من:
     *  - DockService.applyBubbleColors()
     *  - NotificationService.onSharedPreferenceChanged() عند تغيير لون
     */
    fun syncColors(prefs: android.content.SharedPreferences, context: android.content.Context) {
        val colors    = com.plate.launcher.utils.ColorUtils.getMainColors(prefs, context)
        val mainColor = colors[0]
        val mainAlpha = colors[1]
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            notifications?.applyColorSync(mainColor, mainAlpha)
        }
    }

    /** مزامنة لون الفقاعة على زر الإشعارات في الدوك من NotificationService */
    fun syncBubbleToDoc(prefs: android.content.SharedPreferences, context: android.content.Context) {
        val color = com.plate.launcher.utils.ColorUtils.getBubbleColor(prefs, context)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            dock?.applyNotifBtnColor(color)
        }
    }
}



// ════════════════════════════════════════════════════════════════
// 1️⃣  DockService  —  خدمة الإمكانية (النواة الأساسية)
// ════════════════════════════════════════════════════════════════
const val DOCK_SERVICE_CONNECTED = "service_connected"
const val ACTION_TAKE_SCREENSHOT = "take_screenshot"
const val ACTION_LAUNCH_APP = "launch_app"
const val ACTION_REFRESH_USER_PROFILE = "refresh_user_profile"
const val DESKTOP_APP_PINNED = "desktop_app_pinned"
const val DOCK_SERVICE_ACTION = "dock_service_action"
// Virtual-mouse actions on the DOCK_SERVICE_ACTION channel — sent by
// TrackpadActivity (running on the phone's own screen) to DockService
// (which owns the secondary display's CursorOverlayManager). See
// CursorOverlayManager's doc comment for why this is a separate path from
// UhidManager's USB-OTG mouse.
const val ACTION_CURSOR_MOVE = "cursor_move"
const val ACTION_CURSOR_CLICK = "cursor_click"
const val EXTRA_DX = "dx"
const val EXTRA_DY = "dy"
const val EXTRA_BUTTON = "button"
// Cursor availability broadcast — DockService -> TrackpadActivity, the
// reverse direction of ACTION_CURSOR_MOVE/CLICK. Fixes a real "drag on
// nothing" bug: without this, if the accessibility service was never
// enabled, or the secondary display disconnected mid-session, the phone
// screen kept accepting drags with zero feedback because
// moveCursorBy/dispatchCursorClick just silently no-op on a null overlay.
const val ACTION_CURSOR_STATUS = "cursor_status"
const val ACTION_QUERY_CURSOR_STATUS = "query_cursor_status"
const val EXTRA_CURSOR_AVAILABLE = "cursor_available"
// Gesture timings for dispatchCursorClick — kept as named constants rather
// than inline magic numbers since "double" dispatches two of these back to
// back and the gap between them has to land inside the OS's own
// double-tap window to actually register as a double-click.
private const val TAP_DURATION_MS = 60L
private const val LONG_PRESS_DURATION_MS = 600L
private const val DOUBLE_TAP_GAP_MS = 100L

class DockService : AccessibilityService(), OnSharedPreferenceChangeListener, OnTouchListener,
    OnAppClickListener, OnDockAppClickListener {

    // Service-scoped coroutine scope — cancelled automatically in onDestroy()
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    // PERF: Cache interpolators as class-level vals — avoids instantiating a new
    // object on every animation call. On budget CPUs (T606, Helio G35) this alone
    // shaves ~2-4ms per frame by eliminating repeated GC pressure from short-lived
    // interpolator allocations inside tight animation loops.
    private val interpSpringIn   by lazy { android.view.animation.PathInterpolator(0.22f, 1.0f, 0.36f, 1.0f) }
    private val interpEmphasized by lazy { android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1.0f) }
    private val interpExit       by lazy { android.view.animation.PathInterpolator(0.3f, 0f, 0.8f, 0.15f) }
    private val interpAccel      by lazy { android.view.animation.AccelerateInterpolator(1.5f) }
    private val interpSpringBtn  by lazy { android.view.animation.PathInterpolator(0.34f, 1.56f, 0.64f, 1f) }
    private val interpAccelDecel by lazy { android.view.animation.AccelerateDecelerateInterpolator() }
    // ── Windows 11 Fluent Motion interpolators ──
    // Enter: fast rise + gentle overshoot (Start menu / panel pop-up from taskbar)
    private val interpWin11Enter by lazy { android.view.animation.PathInterpolator(0.1f, 0.9f, 0.2f, 1.0f) }
    // Exit:  quick fade-down (dismiss snaps back to taskbar)
    private val interpWin11Exit  by lazy { android.view.animation.PathInterpolator(0.4f, 0f, 0.6f, 0f) }

    private var orientation = -1
    private var displayListener: DisplayManager.DisplayListener? = null
    // GitHub issue #15 ("wallpaper on secondary screen not working") — see
    // SecondaryDisplayWallpaperPresentation's own doc comment for the full
    // story. Lifecycle is tied to the secondary display's actual presence
    // (onDisplayAdded/onDisplayRemoved below), not to the "prefer_last_display"
    // dock-placement preference — the live wallpaper should show on whatever
    // secondary display exists, independent of where the dock itself lives.
    private var secondaryDisplayPresentation:
        com.plate.launcher.livewallpaper.service.SecondaryDisplayWallpaperPresentation? = null
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var activityManager: ActivityManager
    private lateinit var appsBtn: ImageView
    private lateinit var appsBtnCenter: ImageView
    private lateinit var backBtn: ImageView
    private lateinit var homeBtn: ImageView
    private lateinit var recentBtn: ImageView
    private lateinit var assistBtn: ImageView
    private lateinit var bluetoothBtn: ImageView
    private lateinit var wifiBtn: ImageView
    private lateinit var batteryBtn: TextView
    private lateinit var volumeBtn: ImageView
    private lateinit var pinBtn: ImageView
    private lateinit var hidePillsBtn: ImageView
    private lateinit var wallpaperBtn: ImageView
    private lateinit var userBtn: ImageView
    private lateinit var castBtn: android.widget.ImageView
    private var castManager: com.plate.launcher.cast.CastManager? = null

    private lateinit var statusArea: android.widget.LinearLayout
    private lateinit var systemTray: android.widget.LinearLayout
    private lateinit var actionBtnsGroup: android.widget.LinearLayout
    private lateinit var navBtnsGroup: android.widget.LinearLayout
    private lateinit var navPanel: android.widget.LinearLayout
    private lateinit var pinPill: android.widget.LinearLayout
    private lateinit var centerGroup: android.widget.LinearLayout

    // Floating corner status pills
    private var timePillView: View? = null
    private var timePillTv: TextClock? = null
    private var datePillTv: TextClock? = null
    private var batteryPillView: View? = null
    private var batteryPillTv: TextView? = null
    private var pillWifiIv: ImageView? = null
    private var pillSignalIv: ImageView? = null
    private var pillBatteryIv: ImageView? = null
    private var timePillParams: WindowManager.LayoutParams? = null
    private var batteryPillParams: WindowManager.LayoutParams? = null
    private var timeTickReceiver: BroadcastReceiver? = null
    private var arePillsHidden = false

    private lateinit var notificationBtn: TextView
    private lateinit var searchTv: TextView
    private lateinit var searchIcon: ImageView
    private lateinit var topRightCorner: Button
    private lateinit var bottomRightCorner: Button
    private lateinit var dockHandle: Button
    private lateinit var appMenu: LinearLayout
    private lateinit var searchLayout: LinearLayout
    private var powerMenu: LinearLayout? = null
    private lateinit var searchEntry: LinearLayout
    private lateinit var dockLayout: RelativeLayout
    private lateinit var windowManager: WindowManager
    private lateinit var appsSeparator: View
    private var appMenuVisible = false

    // 1.15: Cache installed apps for faster menu loading
    @Volatile private var cachedInstalledApps: ArrayList<com.plate.launcher.models.App>? = null
    private var powerMenuVisible = false
    // ✅ FIX: debounce — prevents re-open when tapping the same button that just closed the panel
    private var appMenuLastClosedAt  = 0L
    private var powerMenuLastClosedAt = 0L
    private var qsPanelLastClosedAt  = 0L
    private val PANEL_DEBOUNCE_MS    = 350L
    // ✅ Clean animation: postDelayed بدل AnimatorListenerAdapter — يُلغى بـ removeCallbacks لو فُتحت القائمة من جديد
    private val hideMenuRunnable = Runnable {
        try { windowManager.removeView(appMenu) } catch (e: Exception) {}
        appMenu.alpha = 1f; appMenu.scaleX = 1f
        appMenu.scaleY = 1f; appMenu.translationY = 0f
    }
    private var isPinned = false
    private var systemApp = false
    private var secondary = false
    private lateinit var dockLayoutParams: WindowManager.LayoutParams
    /** Last width successfully applied to the dock — a sanity fallback for recomputeDockShape(); see its comment. */
    private var lastGoodDockWidth: Int = 0
    private lateinit var searchEt: EditText
    private lateinit var tasksGv: RecyclerView
    private lateinit var favoritesGv: RecyclerView
    private lateinit var appsGv: RecyclerView
    private lateinit var wifiManager: WifiManager
    private lateinit var batteryReceiver: BatteryStatsReceiver
    private lateinit var soundEventsReceiver: SoundEventsReceiver
    private var launcherReceiver: BroadcastReceiver? = null
    private var dockActionReceiver: BroadcastReceiver? = null
    // Direct-display virtual mouse (see CursorOverlayManager's doc comment):
    // draws a cursor on the secondary display and dispatches real gestures
    // at its position, driven by relative deltas broadcast from
    // TrackpadActivity ("cursor_move"/"cursor_click" on DOCK_SERVICE_ACTION).
    // Independent of "secondary" (dock placement preference) — this cursor
    // tracks whatever secondary display is physically attached right now.
    private var cursorOverlay: CursorOverlayManager? = null
    private var notificationServiceReceiver: BroadcastReceiver? = null
    private var wallpaperReceiver: BroadcastReceiver? = null
    private var packageReceiver: BroadcastReceiver? = null
    private lateinit var gestureDetector: GestureDetector
    private lateinit var db: DBHelper
    private lateinit var dockHandler: Handler
    private lateinit var dock: HoverInterceptorLayout
    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var pinnedApps: ArrayList<App>
    private lateinit var dateTv: TextClock
    private var maxApps = 0
    private var maxAppsLandscape = 0
    private lateinit var context: Context
    private lateinit var tasks: ArrayList<AppTask>
    @Volatile private var lastUpdate: Long = 0 // FIX #12: @Volatile prevents race condition between Main and IO threads
    private var dockHeight: Int = 0
    private var dockMargin: Int = 0
    private lateinit var handleLayoutParams: WindowManager.LayoutParams
    // Field (not local to onServiceConnected like it originally was) so
    // refreshForDisplayChange can reuse the same LayoutParams object when
    // moving topRightCorner/bottomRightCorner to a different display's
    // WindowManager — see that function's own comment for why the move
    // itself (not just this field) was the actual bug fix.
    private lateinit var cornersLayoutParams: WindowManager.LayoutParams
    private lateinit var launcherApps: LauncherApps
    private var iconPackUtils: IconPackUtils? = null
    // Quick Settings Panel
    private var qsPanel: LinearLayout? = null
    private var qsPanelVisible = false
    private var qsPanelAnimating = false
    // Resource Monitor
    private var resourceMonitorTv: TextView? = null
    private var resourceHandler: Handler? = null
    private var prevCpuTotal = 0L
    private var prevCpuIdle = 0L
    private var lastCpuValue = 0
    override fun onCreate() {
        super.onCreate()
        PerfectServer.dock = this  // ✅ سجّل في المنسق
        db = DBHelper.getInstance(this)
        activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        sharedPreferences.registerOnSharedPreferenceChangeListener(this)
        secondary = sharedPreferences.getBoolean("prefer_last_display", false)
        context = DeviceUtils.getDisplayContext(this, secondary)
        windowManager = context.getSystemService(WINDOW_SERVICE) as WindowManager
        wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        launcherApps = getSystemService(LAUNCHER_APPS_SERVICE) as LauncherApps
        dockHandler = Handler(Looper.getMainLooper())
        if (sharedPreferences.getString("icon_pack", "").orEmpty().isNotEmpty()) {
            iconPackUtils = IconPackUtils(this)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        // FIX: Android 13 / One UI 5.1 crash — TYPE_APPLICATION_OVERLAY throws
        // without SYSTEM_ALERT_WINDOW permission even from an AccessibilityService.
        // Guard here to avoid "Not working" crash loop that drains battery.
        if (!android.provider.Settings.canDrawOverlays(this)) {
            android.util.Log.e("DockService", "SYSTEM_ALERT_WINDOW not granted — aborting. Tell user to grant Overlay permission first.")
            sendBroadcast(android.content.Intent("com.plate.launcher.OVERLAY_PERMISSION_MISSING").setPackage(packageName))
            return
        }

        Utils.startupTime = System.currentTimeMillis()
        systemApp = AppUtils.isSystemApp(context, packageName)
        // Enable freeform windowing system-wide so ALL apps open as windows
        // regardless of which profile (phone/tablet/PC) the user chose
        enableFreeformWindowing()
        maxApps = sharedPreferences.getString("max_running_apps", "10")?.toDoubleOrNull()?.toInt() ?: 10
        maxAppsLandscape = sharedPreferences.getString("max_running_apps_landscape", "10")?.toDoubleOrNull()?.toInt() ?: 10
        orientation = resources.configuration.orientation

        //Create the dock
        dock = LayoutInflater.from(
            ContextThemeWrapper(
                context,
                R.style.AppTheme_Dock
            )
        ).inflate(R.layout.dock, null) as HoverInterceptorLayout
        dockLayout = dock.findViewById(R.id.dock_layout)
        dockHandle = LayoutInflater.from(context).inflate(R.layout.dock_handle, null) as Button
        appsBtn = dock.findViewById(R.id.apps_btn)
        appsBtnCenter = dock.findViewById(R.id.apps_btn_center)
        tasksGv = dock.findViewById(R.id.apps_lv)
        // Icons row is always horizontal now — the dock only supports
        // top/bottom (see DockPositionUtils restructure), so there's no
        // vertical/column arrangement to switch into anymore.
        val layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        tasksGv.layoutManager = layoutManager
        backBtn = dock.findViewById(R.id.back_btn)
        homeBtn = dock.findViewById(R.id.home_btn)
        recentBtn = dock.findViewById(R.id.recents_btn)
        assistBtn = dock.findViewById(R.id.assist_btn)
        notificationBtn = dock.findViewById(R.id.notifications_btn)
        pinBtn   = dock.findViewById(R.id.pin_btn)
        hidePillsBtn = dock.findViewById(R.id.hide_pills_btn)
        pinPill  = dock.findViewById(R.id.pin_pill)
        wallpaperBtn = dock.findViewById(R.id.wallpaper_btn)
        userBtn = dock.findViewById(R.id.user_btn)
        castBtn = dock.findViewById(R.id.cast_btn)
        statusArea = dock.findViewById(R.id.status_area)
        systemTray = dock.findViewById(R.id.system_tray)
        actionBtnsGroup = dock.findViewById(R.id.action_btns_group)
        navBtnsGroup    = dock.findViewById(R.id.nav_btns_group)
        navPanel        = dock.findViewById(R.id.nav_panel)
        centerGroup     = dock.findViewById(R.id.center_group)
        bluetoothBtn = dock.findViewById(R.id.bluetooth_btn)
        wifiBtn = dock.findViewById(R.id.wifi_btn)
        volumeBtn = dock.findViewById(R.id.volume_btn)
        batteryBtn = dock.findViewById(R.id.battery_btn)
        dateTv = dock.findViewById(R.id.date_btn)
        resourceMonitorTv = dock.findViewById(R.id.resource_monitor_tv)

        // Show/hide resource monitor based on preference
        if (sharedPreferences.getBoolean("show_resource_monitor", false)) {
            resourceMonitorTv?.visibility = View.VISIBLE
            startResourceMonitor()
        }
        // Discord report ("the dock hides and appears when the courser hover
        // over its location and if there is a way to not make it do that"):
        // this listener used to be unconditional — every dock had this
        // hover-to-reveal behavior with no way to turn it off. Gated behind
        // "dock_hover_show_hide" (default true, preserving the original
        // behavior for anyone who doesn't touch the new setting).
        dock.setOnHoverListener { _, event ->
            if (!sharedPreferences.getBoolean("dock_hover_show_hide", true))
                return@setOnHoverListener false
            if (event.action == MotionEvent.ACTION_HOVER_ENTER) {
                if (dockLayout.isGone) showDock()
            } else if (event.action == MotionEvent.ACTION_HOVER_EXIT) if (dockLayout.isVisible) {
                hideDock(500)
            }
            false
        }
        gestureDetector = GestureDetector(context, object : OnSwipeListener() {
            override fun onSwipe(direction: Direction): Boolean {
                if (direction == Direction.UP) {
                    if (!isPinned) pinDock() else if (!appMenuVisible) showAppMenu()
                } else if (direction == Direction.DOWN) {
                    if (appMenuVisible) hideAppMenu() else unpinDock()
                } else if (direction == Direction.LEFT) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
                return true
            }
        })
        dock.setOnTouchListener(this)
        dockLayout.setOnTouchListener(this)
        dockHandle.alpha = sharedPreferences.getString("handle_opacity", "0.5")?.toFloatOrNull() ?: 0.5f
        dockHandle.setOnClickListener { pinDock() }

        // Central animation function for all buttons
        // POLISH: ViewPropertyAnimator runs on the RenderThread → zero jank.
        // XML-based Animation runs on the main thread and chains two separate listeners
        // which introduces a visible gap between press and release.
        // We now do press + action + release in one smooth Animator chain.
        // Haptic feedback makes every tap feel physical and premium.
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
            context.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
        else
            @Suppress("DEPRECATION") context.getSystemService(android.os.Vibrator::class.java)

        fun haptic(view: View) {
            view.performHapticFeedback(
                android.view.HapticFeedbackConstants.VIRTUAL_KEY,
                android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
        }

        fun animateBtn(view: View, action: () -> Unit) {
            haptic(view)
            view.animate().cancel()
            // PERF: reduced 60→40ms press, 280→180ms release for budget CPUs (T606/G35).
            // Uses cached interpolators — no per-call object allocation.
            view.animate()
                .scaleX(0.82f).scaleY(0.82f).alpha(0.65f)
                .setDuration(40)
                .setInterpolator(interpAccel)
                .withEndAction {
                    action()
                    view.animate()
                        .scaleX(1f).scaleY(1f).alpha(1f)
                        .setDuration(180)
                        .setInterpolator(interpSpringBtn)
                        .start()
                }.start()
        }

        appsBtn.setOnClickListener { animateBtn(it) { toggleAppMenu() } }
        appsBtn.setOnLongClickListener {
            launchApp("freeform", null, Intent(Settings.ACTION_APPLICATION_SETTINGS))
            true
        }
        appsBtnCenter.setOnClickListener { animateBtn(it) { toggleAppMenu() } }
        appsBtnCenter.setOnLongClickListener {
            launchApp("freeform", null, Intent(Settings.ACTION_APPLICATION_SETTINGS))
            true
        }
        assistBtn.setOnClickListener { animateBtn(it) { launchAssistant() } }

        backBtn.setOnClickListener { animateBtn(it) { performGlobalAction(GLOBAL_ACTION_BACK) } }
        backBtn.setOnLongClickListener {
            performNavAction("enable_nav_back")
            true
        }
        homeBtn.setOnClickListener { animateBtn(it) { performGlobalAction(GLOBAL_ACTION_HOME) } }
        homeBtn.setOnLongClickListener {
            performNavAction("enable_nav_home")
            true
        }
        recentBtn.setOnClickListener { animateBtn(it) { performGlobalAction(GLOBAL_ACTION_RECENTS) } }
        recentBtn.setOnLongClickListener {
            performNavAction("enable_nav_recents")
            true
        }

        notificationBtn.setOnClickListener {
            animateBtn(it) {
                // FIX: dead setting — "enable_notif_panel" ("Notification
                // panel", preferences_notification.xml) was defined, shown
                // in Settings, defaulting to true, but nothing anywhere in
                // the codebase ever read it — it had zero effect. The
                // similarly-named-but-different "enable_qs_notif" (a
                // separate key, preferences_dock.xml) only controls whether
                // the notification button itself is visible on the dock —
                // it says nothing about whether the panel it opens is
                // allowed to open. Checked here, alongside enable_qs_notif,
                // so a user who disables "Notification panel" gets the
                // panel itself gated closed, not just relying on the
                // (different) button-visibility setting.
                if (sharedPreferences.getBoolean("enable_qs_notif", true) &&
                    sharedPreferences.getBoolean("enable_notif_panel", true)) {
                    toggleNotificationPanel(!Utils.notificationPanelVisible)
                } else performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            }
        }
        pinBtn.setOnClickListener { animateBtn(it) { togglePin() } }
        hidePillsBtn.setOnClickListener { animateBtn(it) { togglePillsVisibility() } }

        // ── Wallpaper button ──────────────────────────────────────────────────
        // Opens the app chosen by the user in settings
        // If none is set, opens the default wallpaper picker
        wallpaperBtn.setOnClickListener {
            animateBtn(it) {
                val pkg = sharedPreferences.getString("app_wallpaper", "").orEmpty()
                if (pkg.isNotEmpty()) {
                    launchApp(null, pkg)
                } else {
                    // FIX: was Intent.ACTION_SET_WALLPAPER — Android's generic
                    // system wallpaper-picker intent, which launches whatever
                    // wallpaper-chooser app/activity the OS decides on (often a
                    // separate system component entirely outside this app),
                    // completely disconnected from this app's own live
                    // wallpaper gallery/editor. Opening GalleryActivity
                    // directly instead makes the button consistently open
                    // *this app's* wallpaper picker, not a random system one.
                    launchApp("standard", null,
                        Intent(this, com.plate.launcher.livewallpaper.ui.gallery.GalleryActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
                }
            }
        }
        wallpaperBtn.setOnLongClickListener {
            // Long press → open wallpaper button settings directly
            launchApp("standard", null, Intent(this, com.plate.launcher.activities.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
            true
        }

        // ── User button → opens combined Power + Users menu ──────────────────
        userBtn.setOnClickListener {
            animateBtn(it) {
                // FIX: dead setting — "enable_power_menu" ("Built in power
                // menu: use a custom power menu instead of the system one",
                // preferences_advanced.xml, defaults to false) was defined
                // and shown in Settings, but showPowerMenu() below was
                // called unconditionally — the custom menu always appeared
                // regardless of the switch, and there was no way to ever
                // get the actual system power dialog this setting's own
                // description promises as the alternative. GLOBAL_ACTION_
                // POWER_DIALOG is the standard AccessibilityService action
                // for that — DockService already is one (see its class
                // declaration).
                if (sharedPreferences.getBoolean("enable_power_menu", false)) {
                    showPowerMenu()
                } else {
                    performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
                }
            }
        }

        // ── Load user icon for dock button ────────────────────────────────────
        refreshUserButton()

        // ── Cast button ───────────────────────────────────────────────────────
        castManager = com.plate.launcher.cast.CastManager(context).also { mgr ->
            mgr.init()
            mgr.onStateChanged = { isConnected ->
                // Show the cast button whenever a device is available or connected
                castBtn.visibility = if (mgr.isAvailable) View.VISIBLE else View.GONE
                castBtn.setImageResource(
                    if (isConnected) R.drawable.ic_cast_connected else R.drawable.ic_screen
                )
                castBtn.alpha = if (isConnected) 1f else 0.6f
            }
        }
        castBtn.setOnClickListener {
            animateBtn(it) {
                if (castManager?.isConnected == true) {
                    castManager?.disconnect()
                } else {
                    castManager?.showCastPicker(context)
                }
            }
        }

        // ── Quick Settings individual toggles ─────────────────────────────
        // Bluetooth toggle: ضغطة واحدة = toggle، ضغطة مطولة = إعدادات Bluetooth
        bluetoothBtn.isClickable = true
        bluetoothBtn.setOnClickListener {
            animateBtn(it) { toggleBluetooth() }
        }
        bluetoothBtn.setOnLongClickListener {
            openBluetoothSettings(); true
        }

        // WiFi toggle: ضغطة واحدة = toggle مباشر بدون panel، ضغطة مطولة = إعدادات WiFi
        wifiBtn.isClickable = true
        wifiBtn.setOnClickListener {
            animateBtn(it) { toggleWifiDirect() }
        }
        wifiBtn.setOnLongClickListener {
            launchApp("freeform", null, Intent(Settings.ACTION_WIFI_SETTINGS)); true
        }

        // Volume وBattery يبقوا بالـ statusArea كما هم
        volumeBtn.isClickable    = false
        batteryBtn.isClickable   = false

        // ✅ FIX 4: statusArea يفتح QS بس لو الضغطة مش فوق BT أو WiFi
        // استخدمنا setOnTouchListener عشان نفلتر قبل ما الـ click يطلع
        statusArea.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                val hitBt  = isTouchOnView(bluetoothBtn, event)
                val hitWifi = isTouchOnView(wifiBtn, event)
                if (!hitBt && !hitWifi) {
                    toggleNotificationPanel(true)
                }
            }
            false // لا نستهلك الحدث عشان BT/WiFi يستلموا click اللي عندهم
        }
        statusArea.setOnLongClickListener { toggleNotificationPanel(true); true }

        // ── Clock: no action ─────────────────────────────────────────────
        // ── Clock: no action (SmartDock style) ──────────────────────────────
        dateTv.isClickable = false

        dockHeight =
            Utils.dpToPx(context, sharedPreferences.getString("dock_height", "56")?.toIntOrNull() ?: 56)
        val isRoundOnStartup = sharedPreferences.getBoolean("round_dock", false)

        val displayIdStartup = if (secondary)
            DeviceUtils.getSecondaryDisplay(context)?.displayId ?: Display.DEFAULT_DISPLAY
        else
            Display.DEFAULT_DISPLAY
        val displayWidthStartup = DeviceUtils.getDisplayMetrics(context, displayIdStartup).widthPixels
        dockMargin = Utils.dpToPx(context, 8)

        dockLayoutParams = Utils.makeWindowParams(
            if (isRoundOnStartup) displayWidthStartup - 2 * dockMargin else -1,
            dockHeight, context, secondary
        )
        dockLayoutParams.screenOrientation =
            if (sharedPreferences.getBoolean("lock_landscape", true))
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        // Dock can be docked to the top or bottom of the screen via the
        // "dock_position" preference — see DockPositionUtils for the
        // single source of truth this and every other call site reads from.
        val dockPosition = com.plate.launcher.utils.DockPositionUtils.get(sharedPreferences)
        if (isRoundOnStartup) {
            dockLayoutParams.gravity =
                com.plate.launcher.utils.DockPositionUtils.dockGravity(dockPosition, centered = true)
            dockLayoutParams.y = dockMargin
        } else {
            dockLayoutParams.gravity =
                com.plate.launcher.utils.DockPositionUtils.dockGravity(dockPosition, centered = false)
            dockLayoutParams.y = 0
        }
        // Hide status bar via WindowManager flags — stronger than policy_control
        // FLAG_HARDWARE_ACCELERATED must be set BEFORE addView() — required for blur to work
        // Android 16+: FLAG_FULLSCREEN causes touch occlusion — use Shizuku instead
        dockLayoutParams.flags = dockLayoutParams.flags or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (Build.VERSION.SDK_INT >= 35) {
            dockLayoutParams.flags = dockLayoutParams.flags or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            dockLayoutParams.setFitInsetsTypes(0)
        } else {
            dockLayoutParams.flags = dockLayoutParams.flags or
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        }
        // Prevent dock from moving/resizing when keyboard appears
        dockLayoutParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        // blur requires TRANSLUCENT format — already set in makeWindowParams
        // Dock appears immediately when service starts
        safeAddView(dock, dockLayoutParams)

        // FIX: dock position not accounting for the gesture-area safe
        // margin, inconsistently ("sometimes it shows fully, sometimes
        // part of it is cut off" — user report). Root cause: on 15+,
        // setFitInsetsTypes(0) just above makes the dock ignore the
        // system's own insets entirely, so the ONLY thing that could keep
        // it clear of the gesture area is DeviceUtils.getNavBarHeight() —
        // but the initial y/gravity set above never calls it at all, and
        // DeviceUtils.getNavBarHeight is itself flakiest at exactly this
        // moment (see its own doc comment) — right as the service just
        // connected and the window's insets may not have settled yet — so
        // whether the dock ends up safely positioned or not ends up
        // depending on boot-to-boot timing, not anything the user did.
        //
        // Post a one-shot re-layout after the dock's first real frame
        // (view tree fully attached, insets settled by then) so the
        // bottom-docked position gets nudged up by a stable
        // getNavBarHeight() reading instead of relying on the
        // startup-time guess above — self-healing exactly the case the
        // cache fix in getNavBarHeight can't cover on its own (no prior
        // good reading to fall back on this early). Skipped for a
        // top-docked or floating/rounded dock: gravity=TOP has no gesture
        // bar to clear, and the rounded/centered case already gets its
        // margin from dockMargin above, not the nav bar.
        if (Build.VERSION.SDK_INT >= 35 && !isRoundOnStartup && dockPosition != com.plate.launcher.utils.DockPositionUtils.Position.TOP) {
            dock.viewTreeObserver.addOnGlobalLayoutListener(object :
                android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    dock.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    val navHeight = DeviceUtils.getNavBarHeight(context)
                    if (navHeight > 0 && dockLayoutParams.y != navHeight) {
                        dockLayoutParams.y = navHeight
                        try { windowManager.updateViewLayout(dock, dockLayoutParams) } catch (e: Exception) {}
                    }
                }
            })
        }

        // Fix for blank gap over the keyboard / bottom of screen unusable: on
        // Android 15+ (SDK 35+) setFitInsetsTypes(0) above makes the dock
        // ignore ALL insets, including the IME (keyboard) inset — this was
        // intentional so the dock doesn't get resized/pushed by
        // SOFT_INPUT_ADJUST_NOTHING, but it also means the dock has no idea
        // the keyboard opened at all. Result: the dock stays pinned at its
        // original y position, which the keyboard then opens underneath/
        // behind — visually indistinguishable from a large blank gap "as if
        // preparing to run" (the reported bug), since nothing is actually
        // drawn in that band by either the dock or the keyboard.
        //
        // Fix: keep SOFT_INPUT_ADJUST_NOTHING (no resize) but explicitly
        // listen for IME insets and shift the dock's own Y position up by
        // the keyboard's height while it's visible, restoring it when the
        // keyboard closes. This keeps the "don't resize the dock" behavior
        // the original code wanted while ensuring the dock is never
        // physically overlapped/hidden by the keyboard.
        if (Build.VERSION.SDK_INT >= 30) {
            val originalDockY = dockLayoutParams.y
            dock.setOnApplyWindowInsetsListener { view, insets ->
                val imeInsets = insets.getInsets(android.view.WindowInsets.Type.ime())
                val imeVisible = insets.isVisible(android.view.WindowInsets.Type.ime())
                val newY = if (imeVisible) originalDockY + imeInsets.bottom else originalDockY
                if (dockLayoutParams.y != newY) {
                    dockLayoutParams.y = newY
                    try { windowManager.updateViewLayout(view, dockLayoutParams) } catch (e: Exception) {}
                }
                insets
            }
        }

        //Hot corners
        topRightCorner = Button(context)
        topRightCorner.setBackgroundResource(R.drawable.corner_background)
        bottomRightCorner = Button(context)
        bottomRightCorner.setBackgroundResource(R.drawable.corner_background)
        topRightCorner.setOnHoverListener(HotCornersHoverListener("enable_corner_top_right"))
        bottomRightCorner.setOnHoverListener(HotCornersHoverListener("enable_corner_bottom_right"))
        updateCorners()
        // Customization point: the touch/hover-trigger width of the corner
        // strip, previously a fixed 2dp (a very thin target — easy to miss
        // on larger/desktop-class displays this app targets). Configurable
        // from 2 to 24dp via "hot_corners_width" — see preferences_hot_corners.xml.
        val cornerWidthDp = sharedPreferences.getString("hot_corners_width", "2")?.toIntOrNull() ?: 2
        cornersLayoutParams = Utils.makeWindowParams(
            Utils.dpToPx(context, cornerWidthDp), -2, context,
            secondary
        )
        cornersLayoutParams.gravity = Gravity.TOP or Gravity.END
        safeAddView(topRightCorner, cornersLayoutParams)
        cornersLayoutParams.gravity = Gravity.BOTTOM or Gravity.END
        safeAddView(bottomRightCorner, cornersLayoutParams)

        //App menu
        appMenu = LayoutInflater.from(ContextThemeWrapper(context, R.style.AppTheme_Dock))
            .inflate(R.layout.apps_menu, null) as LinearLayout
        searchEntry = appMenu.findViewById(R.id.search_entry)
        searchEt = appMenu.findViewById(R.id.menu_et)
        appsGv = appMenu.findViewById(R.id.menu_applist_lv)
        appsGv.setHasFixedSize(true)
        appsGv.layoutManager = GridLayoutManager(context, 5)
        appsGv.itemAnimator = null  // ✅ PERF: يمنع pop-in animation على الأيقونات لما تُحمَّل async
        favoritesGv = appMenu.findViewById(R.id.fav_applist_lv)
        favoritesGv.layoutManager = GridLayoutManager(context, 5)
        favoritesGv.itemAnimator = null  // ✅ PERF: نفس الشيء للمفضلة
        searchLayout = appMenu.findViewById(R.id.search_layout)
        searchTv = appMenu.findViewById(R.id.search_tv)
        searchIcon = appMenu.findViewById(R.id.search_icon)
        appsSeparator = appMenu.findViewById(R.id.apps_separator)
        searchTv.setOnClickListener {
            val query = searchEt.text.toString()
            if (query.length > 1) {
                // ✅ FIX: لو في نص → نفتح البحث على جوجل (السلوك الصحيح)
                try {
                    launchApp(
                        null, null,
                        Intent(
                            Intent.ACTION_VIEW,
                            ("https://www.google.com/search?q=" +
                                    URLEncoder.encode(query, StandardCharsets.UTF_8.name())).toUri()
                        )
                    )
                } catch (e: Exception) {}
                hideAppMenu()
            } else {
                // ما في نص → بس نعطي focus للـ EditText وينزل الكيبورد
                searchEt.requestFocus()
                val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                        as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(searchEt, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }

        // ✅ FIX: الضغط على أي مكان في الصف (الأيقونة + النص) يفتح البحث
        searchLayout.setOnClickListener { searchTv.performClick() }

        searchEt.addTextChangedListener { text ->
            if (text != null) {
                val appAdapter = appsGv.adapter as? AppAdapter ?: return@addTextChangedListener
                appAdapter.filter(text.toString())
                if (text.length > 1) {
                    searchLayout.visibility = View.VISIBLE
                    searchTv.text =
                        getString(R.string.search_for) + " \"" + text + "\" " + getString(R.string.on_google)
                    toggleFavorites(false)
                } else {
                    searchLayout.visibility = View.GONE
                    toggleFavorites(
                        AppUtils.getPinnedApps(
                            context,
                            AppUtils.PINNED_LIST
                        ).isNotEmpty()
                    )
                }
            }
        }

        searchEt.setOnKeyListener { _, code, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (code == KeyEvent.KEYCODE_ENTER) {
                    // ✅ Enter = يخفي الكيبورد بس — المستخدم يختار التطبيق بنفسه
                    val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                            as android.view.inputmethod.InputMethodManager
                    imm.hideSoftInputFromWindow(searchEt.windowToken, 0)
                    true
                } else if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    appsGv.requestFocus()
                    false
                } else false
            } else false
        }

        updateAppMenu()

        // إغلاق appMenu عند الضغط خارج حدوده — نتحقق أن اللمسة
        // تقع خارج الـ window bounds بالكامل (ACTION_OUTSIDE = خارج الـ WindowManager window)
        appMenu.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                hideAppMenu()
            }
            false
        }

        //Dock handle
        handleLayoutParams = Utils.makeWindowParams(
            Utils.dpToPx(context, 22), -2, context,
            secondary
        )
        updateHandlePositionValues()

        //Listen for launcher messages
        launcherReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.getStringExtra("action")) {
                    LAUNCHER_RESUMED -> pinDock()
                    ACTION_LAUNCH_APP -> {
                        val pkg = intent.getStringExtra("app") ?: return@onReceive
                        launchApp(intent.getStringExtra("mode"), pkg)
                    }
                }
            }
        }
        ContextCompat.registerReceiver(this, launcherReceiver, IntentFilter(LAUNCHER_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)

        // Receiver for DeX mode enable/disable commands — register BEFORE broadcasting CONNECTED
        dockActionReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.getStringExtra("action")) {
                    "enable_dock" -> {
                        try {
                            if (dock.windowToken == null)
                                windowManager.addView(dock, dockLayoutParams)
                        } catch (e: Exception) {}
                        dock.visibility = android.view.View.VISIBLE
                        // FIX: pinDock() must be called to also show dockLayout (the actual
                        // visible bar inside the outer dock container). Without this, the
                        // outer HoverInterceptorLayout is VISIBLE but the inner bar stays GONE,
                        // so the dock appears invisible on desktop entry / service reconnect.
                        pinDock()
                        sendBroadcast(Intent(DOCK_SERVICE_ACTION)
                            .setPackage(packageName)
                            .putExtra("action", ACTION_SHOW_NOTIFICATION_BAR))
                    }
                    "disable_self" -> {
                        try { if (appMenuVisible) hideAppMenu() } catch (e: Exception) {}
                        try { if (qsPanelVisible) toggleQsPanel() } catch (e: Exception) {}
                        dock.visibility = android.view.View.GONE
                        sendBroadcast(Intent(DOCK_SERVICE_ACTION)
                            .setPackage(packageName)
                            .putExtra("action", ACTION_HIDE_NOTIFICATION_BAR))
                    }
                    ACTION_REFRESH_USER_PROFILE -> {
                        // تُبث من MultiUserFragment بعد حفظ صورة جديدة أو تبديل مستخدم —
                        // تحدّث زر المستخدم بالدوك فورًا بدون إعادة تشغيل التطبيق
                        refreshUserButton()
                    }
                    ACTION_CURSOR_MOVE -> {
                        val dx = intent.getIntExtra(EXTRA_DX, 0)
                        val dy = intent.getIntExtra(EXTRA_DY, 0)
                        moveCursorBy(dx, dy)
                    }
                    ACTION_CURSOR_CLICK -> {
                        val button = intent.getStringExtra(EXTRA_BUTTON) ?: "left"
                        dispatchCursorClick(button)
                    }
                    ACTION_QUERY_CURSOR_STATUS -> {
                        // TrackpadActivity just opened (or came back to the
                        // foreground) and wants to know right away, rather
                        // than waiting for the next start/stop event —
                        // which might be a while if nothing changes.
                        broadcastCursorStatus(cursorOverlay?.isShowing == true)
                    }
                }
            }
        }
        ContextCompat.registerReceiver(this, dockActionReceiver, IntentFilter(DOCK_SERVICE_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)

        // Tell the launcher the service has connected + activate notifications immediately
        sendBroadcast(
            Intent(DOCK_SERVICE_ACTION)
                .setPackage(packageName)
                .putExtra("action", DOCK_SERVICE_CONNECTED)
        )
        // Activate notifications right away — don't wait for LauncherActivity
        sendBroadcast(Intent(DOCK_SERVICE_ACTION)
            .setPackage(packageName)
            .putExtra("action", ACTION_SHOW_NOTIFICATION_BAR))

        //Register receivers
        notificationServiceReceiver = object : BroadcastReceiver() {
            override fun onReceive(p1: Context, intent: Intent) {
                when (intent.getStringExtra("action")) {
                    NOTIFICATION_COUNT_CHANGED -> {
                        val count = intent.getIntExtra("count", 0)
                        if (count > 0) {
                            notificationBtn.text = count.toString()
                        } else {
                            notificationBtn.text = ""
                        }
                    }

                    ACTION_TAKE_SCREENSHOT -> takeScreenshot()
                }
            }
        }
        ContextCompat.registerReceiver(this, notificationServiceReceiver,
            IntentFilter(NOTIFICATION_SERVICE_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        batteryReceiver = BatteryStatsReceiver(
            context,
            batteryBtn,
            sharedPreferences.getBoolean("show_battery_level", false)
        )
        batteryReceiver.onBatteryChanged = { _, _ ->
            updateBatteryPillContent()
        }
        // FIX #3: ACTION_BATTERY_CHANGED is a sticky broadcast — must use RECEIVER_EXPORTED
        ContextCompat.registerReceiver(
            this, batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_EXPORTED
        )
        updateBatteryBtn()
        soundEventsReceiver = SoundEventsReceiver()
        val soundEventsFilter = IntentFilter()
        soundEventsFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        soundEventsFilter.addAction(Intent.ACTION_POWER_CONNECTED)
        ContextCompat.registerReceiver(
            this,
            soundEventsReceiver,
            soundEventsFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        wallpaperReceiver = object : BroadcastReceiver() {
            override fun onReceive(p1: Context, intent: Intent) {
                // Delay lets Material You finish computing new palette before we read it
                dockHandler.postDelayed({
                    // Invalidate the DynamicColors cache so applyTheme() picks up the new palette
                    com.plate.launcher.utils.ColorUtils.invalidateDynamicColorCache()
                    applyTheme()
                    applyBubbleColors()
                }, 800)
            }
        }
        val wallpaperFilter = IntentFilter().apply {
            addAction(Intent.ACTION_WALLPAPER_CHANGED)
            // Material You regenerates palette on these events
            addAction("android.intent.action.OVERLAY_CHANGED")
            addAction("com.android.server.wm.theme.THEME_CHANGED")
        }
        ContextCompat.registerReceiver(this, wallpaperReceiver, wallpaperFilter, ContextCompat.RECEIVER_NOT_EXPORTED)

        val filter = IntentFilter(Intent.ACTION_PACKAGE_FULLY_REMOVED)
        filter.addDataScheme("package")
        packageReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                // 1.15: Invalidate app cache so next menu open reflects new packages
                invalidateAppsCache()
                loadPinnedApps()
                updateRunningTasks()
                updateAppMenu(true)
            }
        }
        ContextCompat.registerReceiver(this, packageReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        // ── ShellManager: init client + launch server ────────────────────────
        // ShellServer يشتغل على localhost:7171 — بدون AIDL بعد الإطلاق
        com.plate.launcher.server.ShellManager.init(this)
        com.plate.launcher.server.ShellManager.launchViaShizuku(this)
        com.plate.launcher.server.ShellManager.launchViaRoot(this)

        //Play startup sound
        DeviceUtils.playEventSound(context, "startup_sound")

        updateNavigationBar()
        updateQuickSettings()
        updateDockShape()
        updateStatusPills()
        applyTheme()
        updateMenuIcon()
        loadPinnedApps()
        placeRunningApps()
        safeAddView(dockHandle, handleLayoutParams)
        if (sharedPreferences.getBoolean("pin_dock", true))
            pinDock()
        else
            Toast.makeText(context, R.string.start_message, Toast.LENGTH_LONG).show()

        // FIX: مشكلة الرزلوشن — تغيير الدقة/DPI من إعدادات النظام لا يُطلق onConfigurationChanged
        // الحل: نستمع لـ DisplayManager مباشرة فيشتغل الدوك صح بعد أي تغيير في الشاشة
        val dm = getSystemService(DISPLAY_SERVICE) as DisplayManager
        displayListener = object : DisplayManager.DisplayListener {
            // 1.15: Improved switching between displays
            // لما تُضاف شاشة أو تُحذف → نعيد بناء الـ dock على الشاشة الصح
            override fun onDisplayAdded(displayId: Int) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (sharedPreferences.getBoolean("prefer_last_display", false)) {
                        // Secondary display appeared → move dock there
                        refreshForDisplayChange()
                    }
                    startSecondaryDisplayWallpaperIfNeeded()
                    startCursorOverlayIfNeeded()
                }
            }
            override fun onDisplayRemoved(displayId: Int) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (sharedPreferences.getBoolean("prefer_last_display", false)) {
                        // Secondary display gone → fall back to primary
                        refreshForDisplayChange()
                    }
                    stopSecondaryDisplayWallpaper()
                    stopCursorOverlay()
                }
            }
            override fun onDisplayChanged(displayId: Int) {
                // أي تغيير في الشاشة (رزلوشن، DPI، إضاءة) → نعيد حساب كل شي
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    // FIX: single atomic updateViewLayout() — see the comment
                    // in onConfigurationChanged for why this used to visibly
                    // stretch the dock for a frame when called as two
                    // separate updateDockHeight()+updateDockShape() calls.
                    recomputeDockHeight()
                    recomputeDockShape()
                    safeUpdateViewLayout(dock, dockLayoutParams)
                    applyTheme()
                    if (::tasksGv.isInitialized) updateRunningTasks(true)
                }
            }
        }
        dm.registerDisplayListener(displayListener, android.os.Handler(android.os.Looper.getMainLooper()))
        // Cover the case where a secondary display is already attached when
        // the service starts (onDisplayAdded only fires for displays that
        // appear *after* we register the listener).
        startSecondaryDisplayWallpaperIfNeeded()
        startCursorOverlayIfNeeded()
        // Discord report ("the app switches my navigation to the 3 buttons
        // automatically for some reason"): this used to unconditionally
        // call hideStatusBar(context, true) here regardless of the
        // "hide_status_bar" setting read later at line ~2531 — so even a
        // user who turned that setting off still got policy_control's
        // immersive.full=* applied once at startup. immersive.full affects
        // both bars together (status + navigation), and on some devices/
        // ROMs, requesting it while gesture navigation is active makes the
        // system fall back to the 3-button navigation bar as part of
        // honoring the immersive request — which is what was being
        // reported as an unwanted automatic switch. Now this respects the
        // same setting from the start instead of forcing it on unconditionally.
        DeviceUtils.hideStatusBar(context, sharedPreferences.getBoolean("hide_status_bar", false))
    }

    private fun getAppActions(app: App): ArrayList<Action> {
        val actions = ArrayList<Action>()
        if (DeepShortcutManager.hasHostPermission(context)) {
            if (!DeepShortcutManager.getShortcuts(app.packageName, context).isNullOrEmpty())
                actions.add(Action(R.drawable.ic_shortcuts, getString(R.string.shortcuts)))
        }
        actions.add(Action(R.drawable.ic_manage, getString(R.string.manage)))
        actions.add(Action(R.drawable.ic_launch_mode, getString(R.string.open_as)))
        if (DeviceUtils.getDisplays(this).size > 1)
            actions.add(Action(R.drawable.ic_add_to_desktop, getString(R.string.launch_in)))
        if (AppUtils.isPinned(context, app, AppUtils.PINNED_LIST))
            actions.add(Action(R.drawable.ic_remove_favorite, getString(R.string.remove)))
        if (getPinActions(app).isNotEmpty())
            actions.add(Action(R.drawable.ic_pin, getString(R.string.add_to)))
        // Stop button — shows bottom sheet with options
        actions.add(Action(R.drawable.ic_hide, getString(R.string.force_stop_dex)))

        // ── 1.15: Snap + Close Window — available when app has running tasks
        val dockApp = app as? com.plate.launcher.models.DockApp
        if (dockApp != null && dockApp.tasks.isNotEmpty()) {
            actions.add(Action(R.drawable.ic_crop_free, getString(R.string.snap)))
            actions.add(Action(R.drawable.ic_zoom_out_map, getString(R.string.close_window)))
        }

        return actions
    }

    /** Shows a bottom-sheet style window with stop options for an app */
    private fun showStopBottomSheet(app: App) {
        val view = LayoutInflater.from(context).inflate(R.layout.task_list, null)
        val layoutParams = Utils.makeWindowParams(-2, -2, context, secondary, fitNavInsets = true)
        ColorUtils.applyMainColor(context, sharedPreferences, view)
        layoutParams.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
        layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH

        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE)
                removePopup(view)
            false
        }

        val actionsLv = view.findViewById<ListView>(R.id.tasks_lv)
        val stopActions = ArrayList<Action>()
        stopActions.add(Action(R.drawable.ic_hide, getString(R.string.force_stop_dex)))
        if (sharedPreferences.getBoolean("allow_app_freeze", false))
            stopActions.add(Action(R.drawable.ic_freeze, getString(R.string.freeze)))

        actionsLv.adapter = AppActionsAdapter(context, stopActions)
        actionsLv.setOnItemClickListener { adapterView, _, position, _ ->
            val action = adapterView.getItemAtPosition(position) as Action
            removePopup(view)
            when (action.text) {
                getString(R.string.force_stop_dex) -> {
                    val shell = com.plate.launcher.server.ShellManager
                    val cmd = "am force-stop ${app.packageName}"
                    if (shell.isAvailable) {
                        shell.exec(context, cmd) { result ->
                            android.widget.Toast.makeText(context, result.take(60), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        com.plate.launcher.utils.ShizukoManager.getInstance(context).runShell(cmd) { result ->
                            android.widget.Toast.makeText(context, result.take(60), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                getString(R.string.freeze) -> {
                    val status = DeviceUtils.runAsRoot("pm disable ${app.packageName}")
                    if (status != "error")
                        android.widget.Toast.makeText(context, R.string.app_frozen, android.widget.Toast.LENGTH_SHORT).show()
                    else
                        android.widget.Toast.makeText(context, R.string.something_wrong, android.widget.Toast.LENGTH_SHORT).show()
                    if (appMenuVisible) hideAppMenu()
                }
            }
        }
        addPopup(view, layoutParams)
    }

    private fun getPinActions(app: App): ArrayList<Action> {
        val actions = ArrayList<Action>()
        if (!AppUtils.isPinned(context, app, AppUtils.PINNED_LIST))
            actions.add(Action(R.drawable.ic_add_favorite, getString(R.string.favorites)))
        val isPinnedToDesktop = AppUtils.isPinned(context, app, AppUtils.DESKTOP_LIST)
        if (!isPinnedToDesktop)
            actions.add(Action(R.drawable.ic_add_to_desktop, getString(R.string.desktop)))
        // Discord report ("and how to unpin apps"): this used to only ever
        // add the "Dock" (pin) action when the app was NOT pinned, with no
        // opposite action added when it WAS pinned — so once an app was
        // pinned to the dock, no menu item existed anywhere to undo it.
        // favorites/desktop already had this two-way pattern (see the
        // separate "remove"/desktop-unpin handling further down in
        // onDockAppClicked); dock was the one missing its other half.
        if (!AppUtils.isPinned(context, app, AppUtils.DOCK_PINNED_LIST))
            actions.add(Action(R.drawable.ic_pin, getString(R.string.dock)))
        else
            actions.add(Action(R.drawable.ic_unpin, getString(R.string.remove_from_dock)))

        // Discord report ("what about closing apps? ... I want to disable
        // them so when I switch back to my phone launcher I don't have to
        // switch them back"): only shown when the app actually has a
        // running task — no point offering to close something that isn't
        // running, and findRunningTaskId returning -1 for it would make
        // the click handler's AppUtils.closeTask call a silent no-op anyway.
        if (findRunningTaskId(app.packageName) != -1)
            actions.add(Action(R.drawable.ic_close, getString(R.string.close)))

        return actions
    }

    override fun onDockAppClicked(app: DockApp, anchor: View) {
        val tasks = app.tasks
        if (tasks.size == 1) {
            val taskId = tasks[0].id
            if (taskId == -1) {
                launchApp(null, app.packageName)
            } else {
                // Toggle minimize: if app is in foreground → hide it, otherwise → show it
                val runningTasks = activityManager.getRunningTasks(1)
                val foregroundPackage = runningTasks.firstOrNull()?.topActivity?.packageName
                if (foregroundPackage == app.packageName) {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                } else {
                    activityManager.moveTaskToFront(taskId, 0)
                }
            }
            // auto-pin/unpin after direct launch only
            if (getDefaultLaunchMode(app.packageName) == "fullscreen") {
                if (isPinned && sharedPreferences.getBoolean("auto_unpin", true)) unpinDock()
            } else {
                if (!isPinned && sharedPreferences.getBoolean("auto_pin", true)) pinDock()
            }
        } else if (tasks.size > 1) {
            val view = LayoutInflater.from(context).inflate(R.layout.task_list, null)
            val layoutParams = Utils.makeWindowParams(-2, -2, context, secondary, fitNavInsets = true)
            ColorUtils.applyMainColor(context, sharedPreferences, view)
            layoutParams.gravity = Gravity.BOTTOM or Gravity.START
            layoutParams.flags = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL   // FIX: prevent blocking touches outside this popup
                    or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
            layoutParams.y = Utils.dpToPx(context, 2) + dockHeight
            val location = IntArray(2)
            anchor.getLocationOnScreen(location)
            layoutParams.x = location[0]
            view.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    removePopup(view)
                }
                false
            }
            val tasksLv = view.findViewById<ListView>(R.id.tasks_lv)
            tasksLv.adapter = AppTaskAdapter(context, tasks)
            tasksLv.setOnItemClickListener { adapterView, _, position, _ ->
                activityManager.moveTaskToFront(
                    (adapterView.getItemAtPosition(position) as AppTask).id, 0
                )
                removePopup(view)
            }
            addPopup(view, layoutParams)
        } else {
            launchApp(getDefaultLaunchMode(app.packageName), app.packageName)
            // auto-pin/unpin after launch only
            if (getDefaultLaunchMode(app.packageName) == "fullscreen") {
                if (isPinned && sharedPreferences.getBoolean("auto_unpin", true)) unpinDock()
            } else {
                if (!isPinned && sharedPreferences.getBoolean("auto_pin", true)) pinDock()
            }
        }
    }

    override fun onDockAppLongClicked(app: DockApp, view: View) {
        showDockAppContextMenu(app, view)
    }

    override fun onAppClicked(app: App, item: View) {
        if (app.packageName == "$packageName.calc") {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("results", app.name))
            Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
        } else launchApp(null, app.packageName, null, app)
    }

    override fun onAppLongClicked(app: App, view: View) {
        if (app.packageName != "$packageName.calc") {
            showAppContextMenu(app, view)
        }
    }

    // FIX: Debounce TYPE_WINDOWS_CHANGED — fires dozens of times per second during
    // animations. Without this, updateRunningTasks() is hammered even though its
    // own 500ms guard only skips adapter updates, NOT the wifi/bluetooth icon refresh
    // and DockAppAdapter creation path (when recreateAdapter=true). Tracking the
    // last event time here gives a true event-level gate.
    private var lastWindowChangeTime = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            val now = System.currentTimeMillis()
            // FIX: Samsung One UI 5.1 fires TYPE_WINDOWS_CHANGED hundreds of times per second.
            // Old code called freezeRotation() BEFORE the debounce check = massive battery drain.
            // Increased debounce 250ms → 600ms and moved rotation lock INSIDE the debounce gate.
            if (now - lastWindowChangeTime < 600) return
            lastWindowChangeTime = now

            // Re-apply rotation lock — now only runs once per 600ms max
            if (sharedPreferences.getBoolean("lock_landscape", true)) {
                DeviceUtils.freezeRotation(true)
            }
            if (Build.VERSION.SDK_INT >= 28) {
                if (event.windowChanges.and(AccessibilityEvent.WINDOWS_CHANGE_REMOVED) == AccessibilityEvent.WINDOWS_CHANGE_REMOVED ||
                    event.windowChanges.and(AccessibilityEvent.WINDOWS_CHANGE_ADDED) == AccessibilityEvent.WINDOWS_CHANGE_ADDED
                )
                    updateRunningTasks()
            } else {
                updateRunningTasks()
            }
        } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // FIX: في freeform mode عندك نوافذ متعددة في نفس الوقت
            // TYPE_WINDOW_STATE_CHANGED يطلق لما تنقر على نافذة وتعطيها الـ focus
            // هذا يسمح لنا نعرف أي تطبيق "نشط" الآن حتى في الـ freeform
            val pkg = event.packageName?.toString() ?: return
            if (pkg.isNotEmpty()
                && pkg != packageName
                && pkg != AppUtils.getCurrentLauncher(packageManager)
                && !pkg.contains("com.android.systemui")
            ) {
                if (AppUtils.currentApp != pkg) {
                    AppUtils.currentApp = pkg
                    // أعد رسم الـ dock ليظهر الخط العريض تحت التطبيق النشط الجديد
                    // GUARD: AccessibilityService can fire events before onServiceConnected
                    // finishes inflating the dock view (findViewById for tasksGv happens
                    // later in onServiceConnected). Without this check, a very early
                    // TYPE_WINDOW_STATE_CHANGED event crashes with
                    // "lateinit property tasksGv has not been initialized" — timing-
                    // dependent, so it mostly surfaces on real devices under release
                    // build speed rather than in a debug session.
                    if (::tasksGv.isInitialized) {
                        (tasksGv.adapter as? DockAppAdapter)?.notifyDataSetChanged()
                    }
                }
            }
        } else if (sharedPreferences.getBoolean(
                "custom_toasts",
                false
            ) && event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED && event.parcelableData !is Notification && event.text.isNotEmpty()
        ) {
            val text = event.text[0].toString()
            val app = event.packageName.toString()
            showToast(app, text)
        }
    }

    private fun showToast(app: String, text: String) {
        val layoutParams = Utils.makeWindowParams(-2, -2, context, secondary)
        // FIX: toast used to always anchor to the bottom of the screen —
        // correct for a bottom dock, but wrong for a top dock, where the
        // toast should sit just below the dock instead of far away at the
        // opposite edge of the screen.
        val toastDockPosition = com.plate.launcher.utils.DockPositionUtils.get(sharedPreferences)
        if (toastDockPosition == com.plate.launcher.utils.DockPositionUtils.Position.TOP) {
            layoutParams.gravity = Gravity.TOP or Gravity.CENTER
            layoutParams.y = dock.measuredHeight + Utils.dpToPx(context, 4)
        } else {
            layoutParams.gravity = Gravity.BOTTOM or Gravity.CENTER
            layoutParams.y = dock.measuredHeight + Utils.dpToPx(context, 4)
        }
        val toast = LayoutInflater.from(context).inflate(R.layout.toast, null)
        ColorUtils.applyMainColor(context, sharedPreferences, toast)
        val textTv = toast.findViewById<TextView>(R.id.toast_tv)
        val iconIv = toast.findViewById<ImageView>(R.id.toast_iv)
        textTv.text = text
        val notificationIcon = AppUtils.getAppIcon(context, app)
        iconIv.setImageDrawable(notificationIcon)
        ColorUtils.applyColor(iconIv, ColorUtils.getDrawableDominantColor(notificationIcon))
        toast.alpha = 0f
        toast.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        toast.animate().alpha(1f).setDuration(150)
            .setInterpolator(AccelerateDecelerateInterpolator())
        // FIX #13: Reuse dockHandler instead of creating a new Handler per toast
        dockHandler.postDelayed({
            toast.animate().alpha(0f).setDuration(600)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        toast.setLayerType(View.LAYER_TYPE_NONE, null)
                        removePopup(toast)
                    }
                })
        }, 5000)
        // Fix for overlay outlives Close DEX: this used to be a raw
        // windowManager.addView with cleanup relying entirely on the 5s
        // delayed callback above. If the service was destroyed before that
        // callback fired (Close DEX tapped right after a toast appeared),
        // dockHandler died with it, the callback never ran, and the toast
        // view was left attached to WindowManager with nothing left to
        // remove it — same class of bug as appMenu, just a narrower timing
        // window. Routing through addPopup means cleanupAllPopups() (already
        // called first thing in onDestroy()) sweeps it up unconditionally,
        // regardless of whether the delayed callback got a chance to run.
        addPopup(toast, layoutParams)
    }

    override fun onInterrupt() {}

    //Handle keyboard shortcuts
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) {
            if (event.isAltPressed) {
                if (event.keyCode == KeyEvent.KEYCODE_L && sharedPreferences.getBoolean(
                        "enable_lock_desktop",
                        true
                    )
                )
                    lockScreen()
                else if (event.keyCode == KeyEvent.KEYCODE_P && sharedPreferences.getBoolean(
                        "enable_open_settings",
                        true
                    )
                )
                    launchApp("freeform", null, Intent(Settings.ACTION_SETTINGS))
                else if (event.keyCode == KeyEvent.KEYCODE_T && sharedPreferences.getBoolean(
                        "enable_open_terminal",
                        false
                    )
                )
                    launchApp(null, sharedPreferences.getString("app_terminal", "com.termux").orEmpty())
                else if (event.keyCode == KeyEvent.KEYCODE_Q && sharedPreferences.getBoolean(
                        "enable_expand_notifications",
                        true
                    )
                )
                    performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
                else if (event.keyCode == KeyEvent.KEYCODE_W && sharedPreferences.getBoolean(
                        "enable_toggle_pin",
                        true
                    )
                )
                    togglePin()
                else if (event.keyCode == KeyEvent.KEYCODE_M && sharedPreferences.getBoolean(
                        "enable_open_music",
                        true
                    )
                )
                    launchApp(null, sharedPreferences.getString("app_music", "").orEmpty())
                else if (event.keyCode == KeyEvent.KEYCODE_B && sharedPreferences.getBoolean(
                        "enable_open_browser",
                        true
                    )
                )
                    launchApp(null, sharedPreferences.getString("app_browser", "").orEmpty())
                else if (event.keyCode == KeyEvent.KEYCODE_A && sharedPreferences.getBoolean(
                        "enable_open_assist",
                        true
                    )
                )
                    launchApp(null, sharedPreferences.getString("app_assistant", "").orEmpty())
                else if (event.keyCode == KeyEvent.KEYCODE_R && sharedPreferences.getBoolean(
                        "enable_open_rec",
                        true
                    )
                )
                    launchApp(null, sharedPreferences.getString("app_rec", "").orEmpty())
                else if (event.keyCode == KeyEvent.KEYCODE_D)
                    startActivity(
                        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                else if (event.keyCode == KeyEvent.KEYCODE_O) {
                    toggleSoftKeyboard()
                } else if (event.keyCode == KeyEvent.KEYCODE_F12)
                    DeviceUtils.softReboot()
                //Window management
                else if (event.keyCode == KeyEvent.KEYCODE_F3) {
                    if (tasks.isNotEmpty()) {
                        val task = tasks[0]
                        AppUtils.resizeTask(
                            context, "portrait", task.id, dockHeight
                        )
                    }
                } else if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    if (tasks.isNotEmpty()) {
                        val task = tasks[0]
                        if (event.isShiftPressed)
                            launchApp(
                                "maximized",
                                task.packageName,
                                newInstance = true,
                                rememberMode = false
                            )
                        else
                            AppUtils.resizeTask(
                                context, "maximized", task.id, dockHeight
                            )
                    }
                } else if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (tasks.isNotEmpty()) {
                        val task = tasks[0]
                        if (event.isShiftPressed)
                            launchApp(
                                "tiled-left",
                                task.packageName,
                                newInstance = true,
                                rememberMode = false
                            )
                        else
                            AppUtils.resizeTask(
                                context, "tiled-left", task.id, dockHeight
                            )
                        return true
                    }
                } else if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (tasks.isNotEmpty()) {
                        val task = tasks[0]
                        if (event.isShiftPressed)
                            launchApp(
                                "tiled-right",
                                task.packageName,
                                newInstance = true,
                                rememberMode = false
                            )
                        else
                            AppUtils.resizeTask(
                                context, "tiled-right", task.id, dockHeight
                            )
                        return true
                    }
                } else if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (tasks.isNotEmpty()) {
                        val task = tasks[0]
                        if (event.isShiftPressed)
                            launchApp(
                                "standard",
                                task.packageName,
                                newInstance = true,
                                rememberMode = false
                            )
                        else
                            AppUtils.resizeTask(
                                context, "standard", task.id, dockHeight
                            )
                    }
                } else if (event.isShiftPressed) {
                    val index = when (event.keyCode) {
                        KeyEvent.KEYCODE_1 -> 0
                        KeyEvent.KEYCODE_2 -> 1
                        KeyEvent.KEYCODE_3 -> 2
                        KeyEvent.KEYCODE_4 -> 3
                        KeyEvent.KEYCODE_N -> 4
                        else -> -1
                    }
                    if (index == 4 && sharedPreferences.getBoolean("enable_new_instance", true)) {
                        if (tasks.isNotEmpty()) {
                            val task = tasks[0]
                            launchApp(null, task.packageName, newInstance = true)
                        }
                    } else if (index != -1 && sharedPreferences.getBoolean("enable_tiling", true)) {
                        val displays = DeviceUtils.getDisplays(this)
                        if (tasks.isNotEmpty() && displays.size > index) {
                            val task = tasks[0]
                            launchApp(null, task.packageName, displayId = displays[index].displayId)
                        }
                    }
                }
            } else {
                if (event.keyCode == KeyEvent.KEYCODE_CTRL_RIGHT && sharedPreferences.getBoolean(
                        "enable_ctrl_back",
                        true
                    )
                ) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    return true
                } else if (event.keyCode == KeyEvent.KEYCODE_MENU && sharedPreferences.getBoolean(
                        "enable_menu_recents",
                        false
                    )
                ) {
                    performGlobalAction(GLOBAL_ACTION_RECENTS)
                    return true
                } else if (event.keyCode == KeyEvent.KEYCODE_F10 && sharedPreferences.getBoolean(
                        "enable_f10",
                        true
                    )
                ) {
                    performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
                    return true
                } else if ((event.keyCode == KeyEvent.KEYCODE_HOME || event.keyCode == KeyEvent.KEYCODE_META_LEFT) && sharedPreferences.getBoolean(
                        "enable_open_menu",
                        true
                    )
                ) {
                    toggleAppMenu()
                    return true
                }
            }
        }

        return super.onKeyEvent(event)
    }

    private fun toggleSoftKeyboard() {
        if (Build.VERSION.SDK_INT < 30) {
            val im = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            im.showInputMethodPicker()
        } else {
            // Android 30+: استخدام SoftKeyboardController من AccessibilityService
            // SHOW_MODE_AUTO = النظام يقرر (وضع افتراضي)
            // SHOW_MODE_HIDDEN = يخفي الكيبورد دائماً
            // Toggle بين الوضعين: لو مخفي → نظيف (Auto)، لو Auto → مخفي
            val kc = softKeyboardController
            kc.setShowMode(
                if (kc.showMode == SHOW_MODE_HIDDEN) SHOW_MODE_AUTO
                else SHOW_MODE_HIDDEN
            )
        }
    }

    private fun togglePin() {
        if (isPinned) unpinDock() else pinDock()
    }

    private fun togglePillsVisibility() {
        arePillsHidden = !arePillsHidden
        updatePillsVisibility()
    }

    private fun updatePillsVisibility() {
        if (::hidePillsBtn.isInitialized) {
            hidePillsBtn.setImageResource(
                if (arePillsHidden) R.drawable.ic_visibility_off else R.drawable.ic_visibility
            )
        }
        val vis = if (arePillsHidden) View.GONE else View.VISIBLE
        timePillView?.visibility = vis
        batteryPillView?.visibility = vis
    }

    private fun showDock() {
        dock.visibility = View.VISIBLE
        dockHandle.visibility = View.GONE

        if (dockLayoutParams.height != dockHeight) {
            dockLayoutParams.height = dockHeight
            safeUpdateViewLayout(dock, dockLayoutParams)
        }

        dockHandler.removeCallbacksAndMessages(null)
        updateRunningTasks()
        dockLayout.visibility = View.VISIBLE
        dockLayout.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        // Feature request: show animation now slides in from whichever edge
        // the dock is actually docked to (was previously always a vertical
        // translationY slide regardless of position — see this function's
        // own prior comment noting the intent but never wiring it up).
        val dockPosition = com.plate.launcher.utils.DockPositionUtils.get(sharedPreferences)
        val showTransPx = Utils.dpToPx(context, 12).toFloat()
        com.plate.launcher.utils.DockPositionUtils.animateDockVisibility(
            dockLayout, dockPosition, show = true, distancePx = showTransPx, durationMs = 200
        ) {
            dockLayout.setLayerType(View.LAYER_TYPE_NONE, null)
        }
    }

    fun pinDock() {
        isPinned = true
        pinBtn.setImageResource(R.drawable.ic_pin)
        if (dockLayout.isGone)
            showDock()
        // Android 16+: hide status bar via Shizuku since FLAG_FULLSCREEN is blocked
        if (Build.VERSION.SDK_INT >= 35) {
            val shizuku = com.plate.launcher.utils.ShizukoManager.getInstance(context)
            if (shizuku.hasPermission) {
                shizuku.runShell("service call StatusBar 1") {}
            }
        }
    }

    private fun unpinDock() {
        pinBtn.setImageResource(R.drawable.ic_unpin)
        isPinned = false
        if (dockLayout.isVisible)
            hideDock(500)
        // Android 16+: restore status bar
        if (Build.VERSION.SDK_INT >= 35) {
            val shizuku = com.plate.launcher.utils.ShizukoManager.getInstance(context)
            if (shizuku.hasPermission) {
                shizuku.runShell("service call StatusBar 2") {}
            }
        }
    }

    private fun hideDock(delay: Int) {
        dockHandler.removeCallbacksAndMessages(null)
        dockHandler.postDelayed({
            if (!isPinned) {
                // Fix for "الإشعارات تبقى بمنطقة فارغة لا يمكن لمسها": this used to
                // only ever touch dockLayout/dock — if the notification popup
                // (notificationLayout) or the full notifications/QS drawer
                // (notificationPanel) happened to be open when the dock
                // auto-hid, neither ever got closed. Their windows stayed
                // registered with WindowManager, invisible against the
                // wallpaper but still very much there and still intercepting
                // touches in their old screen area — a dead zone with nothing
                // visibly explaining why taps there did nothing. Closing both
                // here means whatever triggers the dock to hide also reliably
                // closes anything it was showing.
                if (Utils.notificationPanelVisible) {
                    sendBroadcast(
                        Intent(DOCK_SERVICE_ACTION)
                            .setPackage(packageName)
                            .putExtra("action", ACTION_HIDE_NOTIFICATION_PANEL)
                    )
                }
                sendBroadcast(
                    Intent(DOCK_SERVICE_ACTION)
                        .setPackage(packageName)
                        .putExtra("action", ACTION_DISMISS_NOTIFICATION_IMMEDIATE)
                )

                dockLayout.animate().cancel()
                dockLayout.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                // Feature request: hide animation now slides out toward
                // whichever edge the dock is docked to — see showDock()'s
                // matching comment for the same intent-existed-but-was-
                // never-wired-up history on this exact line.
                val dockPosition = com.plate.launcher.utils.DockPositionUtils.get(sharedPreferences)
                val hideTransPx = com.plate.launcher.utils.Utils.dpToPx(context, 8).toFloat()
                com.plate.launcher.utils.DockPositionUtils.animateDockVisibility(
                    dockLayout, dockPosition, show = false, distancePx = hideTransPx, durationMs = 180
                ) {
                    dockLayout.visibility = View.GONE
                    dockLayout.scaleX = 1f; dockLayout.scaleY = 1f; dockLayout.alpha = 1f
                    dockLayout.translationX = 0f; dockLayout.translationY = 0f
                    dockLayout.setLayerType(View.LAYER_TYPE_NONE, null)
                    // Swipe mode removed: it left a small (dock_activation_area) window
                    // still holding its bounds after hiding, which stole touches from
                    // whatever was underneath even though nothing was visible there.
                    // Handle mode is the only mode now — it fully GONEs the dock window
                    // and shows a small, actually-visible handle button instead.
                    dock.visibility = View.GONE
                    dockHandle.visibility = View.VISIBLE
                }
            }
        }, delay.toLong())
    }

    private fun getDefaultLaunchMode(app: String?): String {
        if (app == null) return "standard"
        // FIX: dead setting — "always_floating" ("Override launch mode and
        // always open apps in floating window mode", preferences_dock.xml)
        // was defined, shown in Settings, and readable/writable via its
        // SwitchPreferenceCompat, but nothing anywhere in the codebase ever
        // called sharedPreferences.getBoolean("always_floating", ...) — the
        // switch had zero effect no matter how the user set it. Checked
        // first, before even remember_launch_mode, matching "Override" in
        // the setting's own description: it's meant to win over every
        // other launch-mode source, not just be one more input to them.
        if (sharedPreferences.getBoolean("always_floating", false))
            return "standard" // "standard" is this app's name for a floating/freeform window — see makeLaunchBounds
        // If user remembered a specific mode for this app, respect it
        val remembered: String? = db.getLaunchMode(app)
        if (sharedPreferences.getBoolean("remember_launch_mode", true) && remembered != null)
            return remembered
        // Games: only go fullscreen if user explicitly enabled it (default OFF now)
        if (AppUtils.isGame(packageManager, app)
            && sharedPreferences.getBoolean("launch_games_fullscreen", false))
            return "fullscreen"
        // Default is always windowed — "standard" = freeform window
        return sharedPreferences.getString("launch_mode", "standard") ?: "standard"
    }

    /**
     * Attempts to run [shellCmd] (an "am start --display ..." command, see
     * AppUtils.buildShellLaunchCommand) with elevated shell access, trying
     * ShellManager → Shizuku → Root in that order — the same fallback chain
     * AppUtils.resizeTask uses for its own shell commands. Unlike
     * resizeTask's own "am task resize"/"am task set-windowing-mode"
     * commands, this one is NOT attempted via a plain unprivileged
     * Runtime.exec("sh", "-c", ...) first: launching an Activity on a
     * secondary display specifically needs elevated shell (uid=2000+) or
     * root — a normal-uid `sh -c "am start --display N"` would just hit the
     * same SecurityException this function exists to work around, so
     * trying it first would only waste time before falling through anyway.
     * Returns true if the command was actually sent through one of the
     * three paths (not a guarantee the launch itself succeeded — `am
     * start`'s own stdout/stderr isn't parsed here, matching resizeTask's
     * same not-actually-checking-the-result approach for its two commands).
     */
    /**
     * Finds the taskId of [packageName]'s currently running task, or -1 if
     * it isn't running. Extracted as a shared helper — this same
     * getRunningTasks(1)-then-match-topActivity pattern was previously
     * duplicated inline at two other call sites in this file (the
     * minimize-vs-restore toggle, and launchApp's cold-start resize fix)
     * without either one being reusable for a third (closeTask's own need
     * for this same lookup, added alongside this function).
     */
    private fun findRunningTaskId(packageName: String): Int {
        val runningTasks = activityManager.getRunningTasks(1)
        return runningTasks.firstOrNull()
            ?.takeIf { it.topActivity?.packageName == packageName }
            ?.id ?: -1
    }

    private fun tryLaunchViaShell(shellCmd: String): Boolean {
        try {
            val shell = com.plate.launcher.server.ShellManager
            if (shell.isAvailable) {
                shell.execSync(shellCmd)
                return true
            }
        } catch (e: Exception) {}

        try {
            val shizuku = ShizukoManager.getInstance(this)
            if (shizuku.hasPermission) {
                shizuku.runShellSync(shellCmd)
                return true
            }
        } catch (e: Exception) {}

        return try {
            DeviceUtils.runAsRoot(shellCmd)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun launchApp(
        mode: String?,
        packageName: String?,
        intent: Intent? = null,
        app: App? = null,
        displayId: Int = Display.DEFAULT_DISPLAY,
        newInstance: Boolean = false,
        rememberMode: Boolean = true
    ) {
        var launchMode = mode
        if (launchMode == null)
            launchMode = getDefaultLaunchMode(packageName)
        else
            if (rememberMode && sharedPreferences.getBoolean(
                    "remember_launch_mode",
                    true
                ) && packageName != null
            )
                db.saveLaunchMode(packageName, launchMode)

        val options = AppUtils.makeActivityOptions(context, launchMode, dockHeight, displayId)

        //Used only for work apps
        if (app != null && app.userHandle != Process.myUserHandle())
            launcherApps.startMainActivity(
                app.componentName,
                app.userHandle,
                null,
                options.toBundle()
            )
        else {
            val launchIntent: Intent? = if (intent == null && packageName != null)
                packageManager.getLaunchIntentForPackage(packageName)
            else
                intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            if (launchIntent == null)
                return

            if (newInstance)
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)

            // GitHub issue #15: ActivityOptions.setLaunchDisplayId (used by
            // makeActivityOptions above, in `options`) throws
            // SecurityException from ActivityTaskManagerService on a
            // secondary display the caller doesn't own — a signature-level
            // permission (INTERNAL_SYSTEM_WINDOW/ADD_TRUSTED_DISPLAY) a
            // normal app can't hold, no matter how correct the launch
            // bounds are. AppUtils.buildShellLaunchCommand already existed
            // for exactly this (same `am start --display` pattern
            // AppUtils.resizeTask uses for `am task resize`, running as
            // shell/root instead of this app's own uid) but was never
            // actually called from here — this app's own reported crash was
            // reachable through the one code path that didn't use it yet.
            // Falls back to the normal startActivity() below if no shell
            // access is available, rather than silently failing to launch
            // the app at all.
            if (displayId != Display.DEFAULT_DISPLAY && packageName != null) {
                val shellCmd = AppUtils.buildShellLaunchCommand(
                    context, packageName, launchMode, dockHeight, displayId
                )
                if (shellCmd.isNotEmpty() && tryLaunchViaShell(shellCmd)) {
                    return
                }
                // No shell access, or the command failed — fall through to
                // startActivity() below. It will very likely hit the same
                // SecurityException on a real secondary display, but this
                // preserves the previous behavior for anyone who somehow
                // had it "working" some other way rather than introducing a
                // new silent no-op.
            }

            // See AppUtils.isAppProcessRunning's own doc comment for the
            // full explanation. Short version: ActivityOptions'
            // setLaunchBounds/setLaunchWindowingMode above are unreliable
            // specifically on a cold start (no existing process) — the
            // system briefly creates the new Task at a default size before
            // the requested freeform bounds take effect, so "Maximized"
            // (or any non-standard mode) can silently open at roughly
            // half-screen the very first time an app is launched. This
            // check must run BEFORE startActivity, since startActivity is
            // what creates the process this function checks for.
            val wasAlreadyRunning = packageName != null &&
                AppUtils.isAppProcessRunning(this@DockService, packageName)

            // FIX: startActivity() had no error handling at all — on a slow
            // device (user report: BLU M10L Pro, 3GB RAM, Unisoc T310) a
            // launch can fail partway through at the system level (OOM
            // kill, ANR during Task creation, transient
            // ActivityNotFoundException/SecurityException) without
            // startActivity() itself throwing anything synchronously. The
            // function used to just fall through and run the rest of
            // launchApp() (pinning, orientation lock, etc.) as if the
            // launch had succeeded, leaving an empty/half-created Task
            // behind — which is exactly what shows up in Recent Apps as a
            // transparent window with just the wallpaper and the app's
            // icon overlaid (no first frame was ever drawn into it, so the
            // system falls back to its default empty-task representation).
            try {
                context.startActivity(launchIntent, options.toBundle())
            } catch (e: Exception) {
                // ActivityNotFoundException, SecurityException, or any
                // other launch-time failure — surface it instead of
                // silently proceeding as if nothing went wrong, and skip
                // the resize-retry below since there's no Task to resize.
                android.util.Log.e("DockService", "launchApp failed for $packageName: ${e.message}")
                Toast.makeText(
                    this@DockService,
                    getString(R.string.something_wrong),
                    Toast.LENGTH_SHORT
                ).show()
                return
            }

            if (!wasAlreadyRunning && packageName != null && launchMode != "fullscreen") {
                // 400ms: long enough for the system to finish creating the
                // Task on a genuine cold start (150ms was already the gap
                // AppUtils.resizeTask uses between its own two shell
                // commands, set-windowing-mode then resize — see that
                // function — so 400ms here is a deliberately larger margin
                // for the slower "new process + new Task" case specifically,
                // not just "switch windowing mode on an existing Task").
                // Not a fixed fix for every device/timing — see this
                // change's own commit message for that caveat — but doing
                // nothing left the bug fully unaddressed.
                checkTaskCreatedOrRetry(packageName, launchMode, attempt = 1)
            }
        }

        if (appMenuVisible)
            hideAppMenu()

        // If lock_landscape is enabled, lock rotation at system level
        if (sharedPreferences.getBoolean("lock_landscape", true)) {
            DeviceUtils.freezeRotation(true)
        }

        if (launchMode == "fullscreen" && sharedPreferences.getBoolean("auto_unpin", true)) {
            if (isPinned)
                unpinDock()
        } else {
            if (!isPinned && sharedPreferences.getBoolean("auto_pin", true))
                pinDock()
        }
        //Hack to ensure the launched app is already on the top of the stack
        dockHandler.postDelayed({ updateRunningTasks() }, 1200)

        if (Utils.notificationPanelVisible)
            toggleNotificationPanel(false)
    }

    /**
     * Confirms a just-launched app actually got a Task on top of the stack,
     * resizing it once found — retries once more before giving up rather
     * than reporting failure after a single check.
     *
     * FIX: the first version of this check only tried once at 400ms and
     * showed an error Toast immediately if no Task was found yet. On a slow
     * device (user report: BLU M10L Pro, 3GB RAM) a perfectly normal launch
     * can easily take longer than 400ms end to end, so that single check
     * was a false-positive machine — reporting "failed to open" for apps
     * that were just... still loading. [attempt] 1 checks at 400ms and
     * retries; [attempt] 2 checks at a further 800ms (1200ms total from
     * launch) and only then reports failure — long enough to cover a slow
     * cold start without still reporting real failures (crash,
     * OOM-killed process) so late the user's already moved on.
     */
    private fun checkTaskCreatedOrRetry(packageName: String, launchMode: String, attempt: Int) {
        val delayMs = if (attempt == 1) 400L else 800L
        dockHandler.postDelayed({
            val runningTasks = activityManager.getRunningTasks(1)
            val taskId = runningTasks.firstOrNull()
                ?.takeIf { it.topActivity?.packageName == packageName }
                ?.id ?: -1
            when {
                taskId != -1 -> AppUtils.resizeTask(context, launchMode, taskId, dockHeight)
                attempt == 1 -> checkTaskCreatedOrRetry(packageName, launchMode, attempt = 2)
                else -> {
                    // Task resize lookup timed out or running tasks query is restricted by the OS.
                    // Do not show a false-positive "Something went wrong" toast because startActivity
                    // already completed successfully.
                    android.util.Log.d("DockService", "Could not locate Task to resize for $packageName")
                }
            }
        }, delayMs)
    }

    private fun setOrientation() {
        val lockLandscape = sharedPreferences.getBoolean("lock_landscape", true)
        DeviceUtils.freezeRotation(lockLandscape)
        dockLayoutParams.screenOrientation =
            if (lockLandscape)
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        safeUpdateViewLayout(dock, dockLayoutParams)
    }

    private fun toggleAppMenu() {
        if (appMenuVisible) {
            hideAppMenu()
        } else {
            // ✅ FIX: ignore reopen if panel was just closed by outside-touch within debounce window
            val sinceClose = System.currentTimeMillis() - appMenuLastClosedAt
            if (sinceClose < PANEL_DEBOUNCE_MS) return
            showAppMenu()
        }
    }

    fun showAppMenu() {
        if (appMenuVisible) return  // ✅ منع الفتح المكرر
        appMenuVisible = true

        // NEW: allow hiding the search bar entirely (user-requested option) —
        // the row (icon + input) and the "search the web" row both hidden
        // together since search_layout's click just re-triggers the same
        // search entry field.
        val hideSearchBar = sharedPreferences.getBoolean("hide_app_menu_search", false)
        searchEntry.visibility = if (hideSearchBar) View.GONE else View.VISIBLE

        val layoutParams: WindowManager.LayoutParams?
        val displayId =
            if (secondary) DeviceUtils.getSecondaryDisplay(context)?.displayId ?: Display.DEFAULT_DISPLAY else Display.DEFAULT_DISPLAY
        val deviceWidth = DeviceUtils.getDisplayMetrics(context, displayId).widthPixels
        val deviceHeight = DeviceUtils.getDisplayMetrics(context, displayId).heightPixels
        val margins = Utils.dpToPx(context, 2)
        val navHeight = DeviceUtils.getNavBarHeight(context)
        val diff = if (dockHeight - navHeight > 0) dockHeight - navHeight else 0

        val usableHeight =
            if (DeviceUtils.shouldApplyNavbarFix())
                deviceHeight - margins - diff - DeviceUtils.getStatusBarHeight(context)
            else
                deviceHeight - dockHeight - dockMargin - DeviceUtils.getStatusBarHeight(context) - margins
        if (sharedPreferences.getBoolean("app_menu_fullscreen", false)) {
            layoutParams = Utils.makeWindowParams(-1, usableHeight + margins, context, secondary, fitNavInsets = true)
            layoutParams.y = dockHeight + dockMargin
            if (sharedPreferences.getInt("dock_layout", -1) != 0) {
                val padding = Utils.dpToPx(context, 24)
                appMenu.setPadding(padding, padding, padding, padding)
                searchEntry.gravity = Gravity.CENTER
                searchLayout.gravity = Gravity.CENTER
                appsGv.layoutManager = GridLayoutManager(context, 10)
                favoritesGv.layoutManager = GridLayoutManager(context, 10)
            } else {
                appsGv.layoutManager = GridLayoutManager(context, 5)
                favoritesGv.layoutManager = GridLayoutManager(context, 5)
            }
            appMenu.setBackgroundResource(R.drawable.rect)
        } else {
            val configuredWidthDp = sharedPreferences.getString("app_menu_width", "700")?.toIntOrNull() ?: 700
            val width = Utils.dpToPx(context, configuredWidthDp)
            val height = Utils.dpToPx(
                context,
                sharedPreferences.getString("app_menu_height", "580")?.toIntOrNull() ?: 580
            )
            val clampedWidthPx = width.coerceAtMost(deviceWidth - margins * 2)
            layoutParams = Utils.makeWindowParams(
                clampedWidthPx, height.coerceAtMost(usableHeight),
                context, secondary, fitNavInsets = true
            )
            layoutParams.x = margins
            layoutParams.y = dockMargin + dockHeight + margins
            appsGv.layoutManager = GridLayoutManager(
                context,
                sharedPreferences.getString("num_columns", "5")?.toIntOrNull() ?: 5
            )
            favoritesGv.layoutManager = GridLayoutManager(
                context,
                sharedPreferences.getString("num_columns", "5")?.toIntOrNull() ?: 5
            )
            val padding = Utils.dpToPx(context, 10)
            appMenu.setPadding(padding, padding, padding, padding)
            searchEntry.gravity = Gravity.START
            searchLayout.gravity = Gravity.START
            appMenu.setBackgroundResource(R.drawable.round_rect)

            // FIX: the search bar's text/icon sizes (menu_et, search_icon, search_tv)
            // were fixed sp/dp values in apps_menu.xml, so when app_menu_width gets
            // coerceAtMost'd down to fit a small-resolution screen, the window shrinks
            // but the search bar's contents don't — it ends up visually oversized
            // relative to everything else in the shrunk menu ("شريط البحث كبير عكس
            // الاخرين ... متجمد"). Scale relative to how much the actual clamped width
            // differs from the configured base width, so it shrinks/grows proportionally
            // instead of staying frozen at its XML-authored size.
            val scale = (clampedWidthPx.toFloat() / width.toFloat()).coerceIn(0.55f, 1f)
            searchEt.textSize = 17f * scale
            searchIcon.layoutParams = searchIcon.layoutParams.apply {
                this.width = Utils.dpToPx(context, (28 * scale).toInt())
                this.height = Utils.dpToPx(context, (28 * scale).toInt())
            }
            searchTv.textSize = 16f * scale
        }
        layoutParams.flags = (WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
        layoutParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        val halign = if (sharedPreferences.getBoolean(
                "center_app_menu",
                false
            )
        ) Gravity.CENTER_HORIZONTAL else Gravity.START
        // App menu anchors to whichever edge (top or bottom) the dock is docked to.
        val menuDockPosition = com.plate.launcher.utils.DockPositionUtils.get(sharedPreferences)
        val satelliteGravity = com.plate.launcher.utils.DockPositionUtils.satelliteGravity(menuDockPosition)
        layoutParams.gravity = satelliteGravity or halign
        // درج التطبيقات يتبع لون الفقاعة بشفافية مستقلة
        val bubbleColor = ColorUtils.getBubbleColor(sharedPreferences, this)
        appMenu.background?.setColorFilter(bubbleColor, android.graphics.PorterDuff.Mode.SRC_ATOP)
        appMenu.background?.alpha = getAppMenuAlpha()
        ColorUtils.applyColor(appsSeparator, ColorUtils.getMainColors(sharedPreferences, this)[4])
        // Apply custom font if set
        com.plate.launcher.utils.FontManager.applyIfSet(context, appMenu)
        // FontManager.applyIfSet يضيف OnHierarchyChangeListener تلقائياً (v2)
        // ✅ CRASH FIX: لو القائمة لسا في الويندو (خلال animation الخروج الـ 180ms) → updateViewLayout بس
        // السبب: hideAppMenu تحط appMenuVisible=false فوراً، لكن الـ View ما يُزال إلا بعد 180ms
        // لو ضغط الزر خلال هذي الـ 180ms → showAppMenu تشتغل والـ View لسا موجودة → IllegalStateException
        if (appMenu.isAttachedToWindow) {
            windowManager.updateViewLayout(appMenu, layoutParams)
        } else {
            windowManager.addView(appMenu, layoutParams)
        }

        //Load apps
        updateAppMenu()
        loadFavoriteApps()

        // ── أفاتار المستخدم: دائري مرتبط بنظام المستخدمين — بدون بيضة وبدون ID ──
        val avatarIv   = appMenu.findViewById<ImageView>(R.id.avatar_iv)
        val userNameTv = appMenu.findViewById<android.widget.TextView>(R.id.user_name_tv)
        val userChip   = appMenu.findViewById<android.view.View>(R.id.user_chip_container)

        // شكل دائري نظيف بالكود
        avatarIv.background = null
        avatarIv.foreground = null
        avatarIv.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        avatarIv.clipToOutline = true

        // الضغط يفتح popup تعدد المستخدمين
        userChip?.setOnClickListener {
            UserSwitcherPopup.show(
                context    = context,
                anchor     = userChip,
                dockColor  = ColorUtils.getMainColors(sharedPreferences, this)[0],
                bubbleColor = getBubbleColor(), // ← FIX: نمرر لون الفقاعة عشان الـ popup يتطابق
                onAddUserRequested = {
                    toggleAppMenu()
                    launchApp("standard", null,
                        Intent(this, MainActivity::class.java)
                            .putExtra("open_fragment", "multi_user")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                                or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT))
                }
            )
        }

        // تحميل اسم وصورة المستخدم الحالي — يقرأ الاسم الموحّد من OnboardingPrefs
        // (نفس القيمة اللي بتتكتب في شاشة الترحيب أو من App Menu Settings)
        run {
            val name = com.plate.launcher.utils.OnboardingPrefs.getDisplayName(context)
            if (!name.isNullOrEmpty()) userNameTv.text = name
        }
        run {
            // AvatarDisplay is the single shared "does this user have an
            // animated GIF avatar, else fall back to the static one"
            // helper — every other avatar surface in the app already goes
            // through it (see its kdoc). This chip and the power menu's
            // pmAvatar below used to bypass it and call
            // MultiUserManager.loadUserAvatar() (static-only) directly,
            // which is why a GIF avatar picked in onboarding showed up
            // here frozen on its first frame, uncropped/square, instead of
            // animated and circular like everywhere else.
            val currentUserId = com.plate.launcher.utils.MultiUserManager.getCurrentUserId()
            val shown = com.plate.launcher.utils.AvatarDisplay.showOn(avatarIv, context, currentUserId)
            if (!shown) {
                // AvatarDisplay found nothing for this user at all — same
                // legacy/system fallback chain as before.
                val legacyUri = sharedPreferences.getString("user_icon_uri", "default")
                val legacyIcon = if (legacyUri != "default") {
                    try { Utils.getCircularBitmap(Utils.getBitmapFromUri(context, Uri.parse(legacyUri))) }
                    catch (e: Exception) { null }
                } else null
                val icon = legacyIcon ?: try { DeviceUtils.getUserIcon(context) } catch (e: Exception) { null }
                if (icon != null) avatarIv.setImageBitmap(icon)
                else avatarIv.setImageResource(R.drawable.ic_user)
            }
        }

        // ✅ ANIM: setListener(null) قبل cancel — يمنع onAnimationCancel القديم يشتغل ويخرب الحالة
        appMenu.animate().setListener(null).cancel()
        // نلغي أي removeView معلّق من hide سابق
        appMenu.removeCallbacks(hideMenuRunnable)
        // ── Android/Pixel Enter: أنيميشن أندرويد الأصلي — scale + fade من الشريط ──
        val enterTransY = Utils.dpToPx(context, 12).toFloat()
        appMenu.alpha = 0f
        appMenu.scaleX = 0.92f
        appMenu.scaleY = 0.92f
        appMenu.pivotX = appMenu.width / 2f
        if (menuDockPosition == com.plate.launcher.utils.DockPositionUtils.Position.TOP) {
            appMenu.translationY = -enterTransY
            appMenu.pivotY = 0f
        } else {
            appMenu.translationY = enterTransY
            appMenu.pivotY = appMenu.height.toFloat()
        }
        appMenu.animate()
            .alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setDuration(300)
            .setInterpolator(interpEmphasized)
            .setListener(null)
            .start()

        //Work around android showing the ime system ui bar
        val softwareKeyboard =
            context.resources.configuration.keyboard == Configuration.KEYBOARD_NOKEYS
        val tabletMode = sharedPreferences.getInt("dock_layout", -1) == 1

        searchEt.showSoftInputOnFocus = softwareKeyboard || tabletMode
        searchEt.requestFocus()
    }

    fun hideAppMenu() {
        if (!appMenuVisible) return  // ✅ منع الإخفاء المكرر
        appMenuVisible = false
        appMenuLastClosedAt = System.currentTimeMillis()
        searchEt.setText("")
        val adapter = appsGv.adapter
        if (adapter is AppAdapter) {
            adapter.filter("")
        }
        // ✅ ANIM: setListener(null) قبل cancel — يمنع onAnimationCancel يشتغل ويخرب الحالة
        appMenu.animate().setListener(null).cancel()
        // ── Android/Pixel Exit: يرجع للشريط (اتجاه عكسي حسب موضع الدوك) ──
        val exitTransY = Utils.dpToPx(context, 10).toFloat()
        val exitDockPosition = com.plate.launcher.utils.DockPositionUtils.get(sharedPreferences)
        appMenu.pivotX = appMenu.width / 2f
        val exitAnim = appMenu.animate()
            .alpha(0f).scaleX(0.92f).scaleY(0.92f)
            .setDuration(200)
            .setInterpolator(interpExit)
            .setListener(null)
        if (exitDockPosition == com.plate.launcher.utils.DockPositionUtils.Position.TOP) {
            appMenu.pivotY = 0f
            exitAnim.translationY(-exitTransY)
        } else {
            appMenu.pivotY = appMenu.height.toFloat()
            exitAnim.translationY(exitTransY)
        }
        exitAnim.start()
        // ✅ إزالة القائمة بعد انتهاء الأنيميشن — postDelayed بدل AnimatorListenerAdapter
        // removeCallbacks في showAppMenu يلغي هذا لو فُتحت القائمة قبل انتهاء الـ 180ms
        appMenu.postDelayed(hideMenuRunnable, 180)
    }

    private suspend fun fetchInstalledApps(): ArrayList<App> = withContext(Dispatchers.Default) {
        // 1.15: Return cache if available for instant menu open
        cachedInstalledApps?.let { return@withContext it }
        val apps = AppUtils.getInstalledApps(context)
        cachedInstalledApps = apps
        return@withContext apps
    }

    /** Invalidate apps cache — called on package install/uninstall */
    private fun invalidateAppsCache() {
        cachedInstalledApps = null
        // 🔧 FIX (Memory Leak + Stale Icon Bug): iconCache في AppUtils كانت
        // ConcurrentHashMap بدون أي تنظيف — أيقونات كل تطبيق تُحمَّل بدقة
        // DENSITY_XXXHIGH (أعلى دقة، أكبر حجم بالذاكرة) وتبقى محفوظة للأبد
        // حتى لو التطبيق أُزيل من الجهاز. الأسوأ: لو أُعيد تثبيت تطبيق بنفس
        // اسم الـ package لكن أيقونة مختلفة (تحديث كبير مثلاً)، iconCache.getOrPut
        // كانت ترجّع الأيقونة القديمة المخزّنة بدل الجديدة — أيقونة خاطئة تُعرض
        // للمستخدم. الحل: امسح iconCache كلما تغيّرت حزم التطبيقات المثبتة —
        // نفس اللحظة اللي أصلاً invalidateAppsCache() تُستدعى فيها.
        AppUtils.clearIconCache()
    }

    private fun updateAppMenu(recreateAdapter: Boolean = false) {
        serviceScope.launch(Dispatchers.Default) {
            val hiddenApps = sharedPreferences.getStringSet(
                "hidden_apps_grid",
                setOf()
            ).orEmpty()
            val apps = fetchInstalledApps().filterNot { hiddenApps.contains(it.packageName) }

            withContext(Dispatchers.Main) {
                val menuFullscreen = sharedPreferences.getBoolean("app_menu_fullscreen", false)
                val phoneLayout = sharedPreferences.getInt("dock_layout", -1) == 0
                val existingAdapter = appsGv.adapter
                if (existingAdapter is AppAdapter && !recreateAdapter) {
                    existingAdapter.updateApps(apps)
                } else {
                    appsGv.adapter = AppAdapter(
                        context, apps, this@DockService,
                        menuFullscreen && !phoneLayout, iconPackUtils
                    )
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showAppContextMenu(app: App, anchor: View) {
        // Cast to DockApp to access tasks for snap/close actions
        val dockApp = app as? com.plate.launcher.models.DockApp
        val view = LayoutInflater.from(context).inflate(R.layout.task_list, null)
        val layoutParams = makeContextMenuParams()
        ColorUtils.applyMainColor(context, sharedPreferences, view)
        layoutParams.gravity = Gravity.START or Gravity.TOP
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        layoutParams.x = location[0]
        layoutParams.y = location[1] + Utils.dpToPx(context, anchor.measuredHeight / 2)
        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE)
                removePopup(view)

            false
        }
        val actionsLv = view.findViewById<ListView>(R.id.tasks_lv)
        actionsLv.adapter = AppActionsAdapter(context, getAppActions(app))
        actionsLv.setOnItemClickListener { adapterView, _, position, _ ->
            if (adapterView.getItemAtPosition(position) is Action) {
                val action = adapterView.getItemAtPosition(position) as Action
                if (action.text == getString(R.string.manage)) {
                    val actions = ArrayList<Action>()
                    actions.add(Action(R.drawable.ic_arrow_back, ""))
                    actions.add(Action(R.drawable.ic_info, getString(R.string.app_info)))
                    if (sharedPreferences.getBoolean("enable_app_hiding_grid", false))
                        actions.add(
                            Action(
                                R.drawable.ic_hide,
                                getString(R.string.hide)
                            )
                        )
                    if (!AppUtils.isSystemApp(
                            context,
                            app.packageName
                        ) || sharedPreferences.getBoolean("allow_sysapp_uninstall", false)
                    ) actions.add(Action(R.drawable.ic_uninstall, getString(R.string.uninstall)))
                    if (sharedPreferences.getBoolean("allow_app_freeze", false))
                        actions.add(
                            Action(
                                R.drawable.ic_freeze,
                                getString(R.string.freeze)
                            )
                        )
                    actionsLv.adapter = AppActionsAdapter(context, actions)
                } else if (action.text == getString(R.string.shortcuts)) {
                    actionsLv.adapter = AppShortcutAdapter(
                        context,
                        DeepShortcutManager.getShortcuts(app.packageName, context) ?: emptyList()
                    )
                } else if (action.text == "") {
                    actionsLv.adapter = AppActionsAdapter(context, getAppActions(app))
                } else if (action.text == getString(R.string.open_as)) {
                    val actions = ArrayList<Action>()
                    actions.add(Action(R.drawable.ic_arrow_back, ""))
                    actions.add(Action(R.drawable.ic_standard, getString(R.string.standard)))
                    actions.add(Action(R.drawable.ic_maximized, getString(R.string.maximized)))
                    actions.add(Action(R.drawable.ic_portrait, getString(R.string.portrait)))
                    actions.add(Action(R.drawable.ic_fullscreen, getString(R.string.fullscreen)))
                    actionsLv.adapter = AppActionsAdapter(context, actions)
                } else if (action.text == getString(R.string.add_to)) {
                    val actions = ArrayList<Action>()
                    actions.add(Action(R.drawable.ic_arrow_back, ""))
                    actions.addAll(getPinActions(app))
                    actionsLv.adapter = AppActionsAdapter(context, actions)
                } else if (action.text == getString(R.string.launch_in)) {
                    actionsLv.adapter = DisplaysAdapter(context, DeviceUtils.getDisplays(this))
                } else if (action.text == getString(R.string.app_info)) {
                    launchApp("freeform", null, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData("package:${app.packageName}".toUri())
                    )
                    removePopup(view)
                } else if (action.text == getString(R.string.hide)) {
                    val savedApps = sharedPreferences.getStringSet(
                        "hidden_apps_grid",
                        setOf()
                    ).orEmpty()
                    val hiddenApps = mutableSetOf<String>()
                    hiddenApps.addAll(savedApps)
                    hiddenApps.add(app.packageName)

                    sharedPreferences.edit {
                        putStringSet("hidden_apps_grid", hiddenApps)
                    }

                    if (AppUtils.isPinned(this, app, AppUtils.PINNED_LIST))
                        AppUtils.unpinApp(this, app.packageName, AppUtils.PINNED_LIST)
                    if (AppUtils.isPinned(this, app, AppUtils.DOCK_PINNED_LIST))
                        AppUtils.unpinApp(this, app.packageName, AppUtils.DOCK_PINNED_LIST)
                    if (AppUtils.isPinned(this, app, AppUtils.DESKTOP_LIST))
                        AppUtils.unpinApp(this, app.packageName, AppUtils.DESKTOP_LIST)
                    updateAppMenu()
                    loadFavoriteApps()
                    removePopup(view)
                } else if (action.text == getString(R.string.uninstall)) {
                    AppUtils.uninstallApp(this, app.packageName)
                    if (appMenuVisible)
                        hideAppMenu()
                    removePopup(view)
                } else if (action.text == getString(R.string.freeze)) {
                    val status = DeviceUtils.runAsRoot("pm disable ${app.packageName}")
                    if (status != "error") Toast.makeText(
                        context,
                        R.string.app_frozen,
                        Toast.LENGTH_SHORT
                    ).show() else Toast.makeText(
                        context,
                        R.string.something_wrong,
                        Toast.LENGTH_SHORT
                    ).show()
                    removePopup(view)
                    if (appMenuVisible) hideAppMenu()
                } else if (action.text == getString(R.string.force_stop_dex)) {
                    removePopup(view)
                    showStopBottomSheet(app)
                } else if (action.text == getString(R.string.favorites)) {
                    AppUtils.pinApp(context, app, AppUtils.PINNED_LIST)
                    removePopup(view)
                    loadFavoriteApps()
                } else if (action.text == getString(R.string.remove)) {
                    AppUtils.unpinApp(context, app.packageName, AppUtils.PINNED_LIST)
                    removePopup(view)
                    loadFavoriteApps()
                } else if (action.text == getString(R.string.desktop)) {
                    AppUtils.pinApp(context, app, AppUtils.DESKTOP_LIST)
                    sendBroadcast(
                        Intent(DOCK_SERVICE_ACTION)
                            .setPackage(packageName)
                            .putExtra("action", DESKTOP_APP_PINNED)
                    )
                    removePopup(view)
                } else if (action.text == getString(R.string.dock)) {
                    AppUtils.pinApp(context, app, AppUtils.DOCK_PINNED_LIST)
                    loadPinnedApps()
                    updateRunningTasks()
                    removePopup(view)
                } else if (action.text == getString(R.string.remove_from_dock)) {
                    AppUtils.unpinApp(context, app.packageName, AppUtils.DOCK_PINNED_LIST)
                    loadPinnedApps()
                    updateRunningTasks()
                    removePopup(view)
                } else if (action.text == getString(R.string.close)) {
                    val taskId = findRunningTaskId(app.packageName)
                    if (taskId != -1) {
                        AppUtils.closeTask(context, taskId)
                        // Same 400ms cold-start-safe delay pattern
                        // launchApp uses for its own post-launch task
                        // lookup — here it's just giving `am task remove`
                        // time to actually finish before the dock refreshes
                        // its running-apps list, rather than racing it.
                        dockHandler.postDelayed({ updateRunningTasks(true) }, 400)
                    }
                    removePopup(view)
                } else if (action.text == getString(R.string.standard)) {
                    removePopup(view)
                    launchApp("standard", app.packageName, null, app, newInstance = true)
                } else if (action.text == getString(R.string.maximized)) {
                    removePopup(view)
                    launchApp("maximized", app.packageName, null, app, newInstance = true)
                } else if (action.text == getString(R.string.portrait)) {
                    removePopup(view)
                    launchApp("portrait", app.packageName, null, app, newInstance = true)
                } else if (action.text == getString(R.string.fullscreen)) {
                    removePopup(view)
                    launchApp("fullscreen", app.packageName, null, app, newInstance = true)

                // ── 1.15: Snap submenu ────────────────────────────────────────
                } else if (action.text == getString(R.string.snap)) {
                    val snapActions = ArrayList<Action>()
                    snapActions.add(Action(R.drawable.ic_arrow_back, ""))
                    snapActions.add(Action(R.drawable.ic_expand_left, getString(R.string.left)))
                    snapActions.add(Action(R.drawable.ic_expand_right, getString(R.string.right)))
                    snapActions.add(Action(R.drawable.ic_expand_up_circle, getString(R.string.top)))
                    snapActions.add(Action(R.drawable.ic_expand_down_circle, getString(R.string.bottom)))
                    actionsLv.adapter = AppActionsAdapter(context, snapActions)

                } else if (action.text == getString(R.string.left) && dockApp != null && dockApp.tasks.isNotEmpty()) {
                    removePopup(view)
                    AppUtils.resizeTask(context, "tiled-left", dockApp.tasks[0].id, dockHeight)

                } else if (action.text == getString(R.string.right) && dockApp != null && dockApp.tasks.isNotEmpty()) {
                    removePopup(view)
                    AppUtils.resizeTask(context, "tiled-right", dockApp.tasks[0].id, dockHeight)

                } else if (action.text == getString(R.string.top) && dockApp != null && dockApp.tasks.isNotEmpty()) {
                    removePopup(view)
                    AppUtils.resizeTask(context, "tiled-top", dockApp.tasks[0].id, dockHeight)

                } else if (action.text == getString(R.string.bottom) && dockApp != null && dockApp.tasks.isNotEmpty()) {
                    removePopup(view)
                    AppUtils.resizeTask(context, "tiled-bottom", dockApp.tasks[0].id, dockHeight)

                // ── 1.15: Close Window ────────────────────────────────────────
                } else if (action.text == getString(R.string.close_window) && dockApp != null && dockApp.tasks.isNotEmpty()) {
                    removePopup(view)
                    val taskId = dockApp.tasks[0].id
                    // ShellManager: socket على localhost بدون AIDL
                    val shell = com.plate.launcher.server.ShellManager
                    if (shell.isAvailable) {
                        shell.exec(context, "am task remove $taskId") { updateRunningTasks() }
                    } else {
                        // fallback: Root
                        val root = RootManager.getInstance(context)
                        if (root.isAvailable)
                            root.runShell("am task remove $taskId") { updateRunningTasks() }
                    }
                }
            } else if (Build.VERSION.SDK_INT > 24 && adapterView.getItemAtPosition(position) is ShortcutInfo) {
                val shortcut = adapterView.getItemAtPosition(position) as ShortcutInfo
                removePopup(view)
                DeepShortcutManager.startShortcut(shortcut, context)
            } else if (Build.VERSION.SDK_INT > 28 && adapterView.getItemAtPosition(position) is Display) {
                val display = adapterView.getItemAtPosition(position) as Display
                removePopup(view)
                launchApp(
                    null,
                    app.packageName,
                    null,
                    app,
                    display.displayId,
                    sharedPreferences.getBoolean("launch_new_instance_secondary", true)
                )
            }
        }
        addPopup(view, layoutParams)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showDockAppContextMenu(app: App, anchor: View) {
        // Cast once — DockApp (running apps) has tasks; pinned App doesn't
        val dockApp = app as? com.plate.launcher.models.DockApp
        val view = LayoutInflater.from(context).inflate(R.layout.pin_entry, null)
        val pinLayout = view.findViewById<LinearLayout>(R.id.pin_entry_pin)
        val layoutParams = Utils.makeWindowParams(-2, -2, context, secondary, fitNavInsets = true)
        view.setBackgroundResource(R.drawable.round_rect)
        ColorUtils.applyMainColor(context, sharedPreferences, view)
        // FIX: this always anchored to the bottom of the screen, assuming
        // a bottom dock — for a top dock, the menu needs to drop down
        // below the dock instead, or it opens far away at the opposite
        // edge of the screen instead of right next to the tapped icon.
        val ctxMenuDockPosition = com.plate.launcher.utils.DockPositionUtils.get(sharedPreferences)
        if (ctxMenuDockPosition == com.plate.launcher.utils.DockPositionUtils.Position.TOP) {
            layoutParams.gravity = Gravity.TOP or Gravity.START
        } else {
            layoutParams.gravity = Gravity.BOTTOM or Gravity.START
        }
        layoutParams.flags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        layoutParams.y = Utils.dpToPx(context, 2) + dockHeight
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        layoutParams.x = location[0]
        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE)
                removePopup(view)

            false
        }
        val icon = view.findViewById<ImageView>(R.id.pin_entry_iv)
        ColorUtils.applySecondaryColor(context, sharedPreferences, icon)
        val text = view.findViewById<TextView>(R.id.pin_entry_tv)
        if (AppUtils.isPinned(context, app, AppUtils.DOCK_PINNED_LIST)) {
            icon.setImageResource(R.drawable.ic_unpin)
            text.setText(R.string.unpin)
            val moveLayout = view.findViewById<LinearLayout>(R.id.pin_entry_move)
            moveLayout.visibility = View.VISIBLE
            val moveLeft = view.findViewById<ImageView>(R.id.pin_entry_left)
            val moveRight = view.findViewById<ImageView>(R.id.pin_entry_right)
            ColorUtils.applySecondaryColor(context, sharedPreferences, moveLeft)
            ColorUtils.applySecondaryColor(context, sharedPreferences, moveRight)
            moveLeft.setOnClickListener {
                AppUtils.moveApp(this, app, AppUtils.DOCK_PINNED_LIST, 0)
                loadPinnedApps()
                updateRunningTasks()
            }
            moveRight.setOnClickListener {
                AppUtils.moveApp(this, app, AppUtils.DOCK_PINNED_LIST, 1)
                loadPinnedApps()
                updateRunningTasks()
            }
        }
        pinLayout.setOnClickListener {
            if (AppUtils.isPinned(context, app, AppUtils.DOCK_PINNED_LIST))
                AppUtils.unpinApp(
                    context,
                    app.packageName,
                    AppUtils.DOCK_PINNED_LIST
                ) else
                AppUtils.pinApp(context, app, AppUtils.DOCK_PINNED_LIST)
            loadPinnedApps()
            if (isPinned)
                updateRunningTasks()
            removePopup(view)
        }
        addPopup(view, layoutParams)
    }

    // Bug fix — Lifecycle. Track all floating popup windows so we can clean them up
    // properly in onDestroy(). Without this, popups can outlive their creator and
    // cause "View not attached to window manager" crashes or ghost windows.
    private val activePopups = mutableListOf<View>()

    /** Add a popup to WindowManager and track it for proper lifecycle cleanup */
    /**
     * Safe wrapper around windowManager.addView().
     * WHY THIS EXISTS: SYSTEM_ALERT_WINDOW ("display over other apps") can be
     * revoked by the user at any moment while the service is running — not
     * just checked once at startup. If that happens, every subsequent
     * addView() throws WindowManager.BadTokenException and crashes the
     * process. Before this fix, ~10 call sites across the file called
     * addView() directly with no guard at all.
     * Returns true if the view was actually added.
     */
    private fun safeAddView(view: View, params: WindowManager.LayoutParams): Boolean {
        if (!Settings.canDrawOverlays(this)) return false
        return try {
            if (view.windowToken == null) windowManager.addView(view, params)
            true
        } catch (e: Exception) {
            // BadTokenException (permission revoked mid-session) or
            // IllegalStateException (view already added) — either way,
            // nothing left for the caller to safely do here.
            false
        }
    }

    /**
     * Safe wrapper around windowManager.updateViewLayout().
     * WHY THIS EXISTS: throws IllegalArgumentException if the view is not
     * currently attached (e.g. it was removed by a concurrent close/cleanup
     * path, or the permission was revoked and the view was never re-added).
     * Several call sites (dock resize/theme/orientation updates) can fire
     * from timers or config-change callbacks that don't know the current
     * attachment state.
     */
    private fun safeUpdateViewLayout(view: View, params: WindowManager.LayoutParams) {
        if (view.windowToken == null) return
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: Exception) { /* view detached between the check and the call — ignore */ }
    }

    private fun addPopup(view: View, params: WindowManager.LayoutParams) {
        if (safeAddView(view, params)) activePopups.add(view)
    }

    /** Remove a popup from WindowManager and stop tracking it */
    private fun removePopup(view: View) {
        activePopups.remove(view)
        try { windowManager.removeView(view) } catch (e: Exception) {}
    }

    private fun cleanupAllPopups() {
        activePopups.toList().forEach { v ->
            try { windowManager.removeView(v) } catch (e: Exception) {}
        }
        activePopups.clear()
    }

    /**
     * FIX (Flags): Context menus are overlays that detect outside touches to
     * dismiss themselves but DON'T need to steal focus from whatever app is
     * running. FLAG_NOT_FOCUSABLE is correct here.
     *
     * However, FLAG_WATCH_OUTSIDE_TOUCH ONLY works when paired with
     * FLAG_NOT_TOUCH_MODAL on API 26+. Without FLAG_NOT_TOUCH_MODAL, outside
     * touches are consumed by the window and apps below never receive them.
     *
     * This helper builds consistent params for all dismissible context menus.
     */
    private fun makeContextMenuParams(fitNavInsets: Boolean = true): WindowManager.LayoutParams {
        val p = Utils.makeWindowParams(-2, -2, context, secondary, fitNavInsets = fitNavInsets)
        p.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or   // ← was missing
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        return p
    }

    override fun onSharedPreferenceChanged(p1: SharedPreferences, preference: String?) {
        if (preference == null)
            return
        if (preference.startsWith("theme") || preference == "bubble_color"
            || preference == "bubble_mode" || preference == "bubble_alpha"
            || preference == "round_dock" || preference == "dock_height"
            || preference == "override_dock_background_alpha"
            || preference == "dock_background_alpha"
            || preference == "enable_app_dock_bg"
            || preference == "app_dock_bg_opacity"
        )
            applyTheme()
        // FIX (user report — "the dock doesn't update live, you have to
        // restart it"): dock_position (top/bottom/left/right) was read
        // correctly by recomputeDockShape()/updateDockShape() — the
        // function that actually lays out and positions the dock window —
        // but wasn't wired up here, so changing it from Settings silently
        // did nothing until the dock happened to be recreated some other
        // way (e.g. toggling round_dock right after, or restarting the
        // service). It affects the dock's shape/position/rotation the same
        // way round_dock already does, so it goes through the same
        // updateDockShape() path.
        else if (preference == "dock_position")
            updateDockShape()
        else if (preference == "menu_icon_uri")
            updateMenuIcon()
        else if (preference.startsWith("icon_")) {
            val iconPack = sharedPreferences.getString("icon_pack", "").orEmpty()
            iconPackUtils = if (iconPack.isNotEmpty()) {
                IconPackUtils(this)
            } else
                null

            updateRunningTasks(true)
            updateAppMenu(true)
            loadFavoriteApps()
        } else if (preference == "tint_indicators") {
            updateRunningTasks(true)
        } else if (preference == "lock_landscape")
            setOrientation()
        else if (preference == "hide_status_bar" || preference == "hide_nav_buttons")
            DeviceUtils.updateImmersivePolicy(context)
        else if (preference == "center_running_apps") {
            placeRunningApps()
            updateRunningTasks()
        } else if (preference == "center_apps_btn") {
            updateCenterAppsBtn()
        } else if (preference == "dock_activation_area")
            updateDockTrigger()
        else if (preference.startsWith("enable_corner_"))
            updateCorners()
        else if (preference.startsWith("enable_nav_")) {
            updateNavigationBar()
        } else if (preference.startsWith("enable_qs_")) {
            updateQuickSettings()
        } else if (preference == "swap_nav_pin") {
            updateNavigationBar()
            updateQuickSettings()
        } else if (preference.startsWith("pill_") || preference.startsWith("enable_pill_")) {
            updateStatusPills()
        } else if (preference == "app_menu_opacity") {
            if (::appMenu.isInitialized) {
                appMenu.background?.alpha = getAppMenuAlpha()
            }
        } else if (preference == "round_dock")
            updateDockShape()
        else if (preference.startsWith("max_running_apps")) {
            maxApps = sharedPreferences.getString("max_running_apps", "10")?.toDoubleOrNull()?.toInt() ?: 10
            maxAppsLandscape =
                sharedPreferences.getString("max_running_apps_landscape", "10")?.toDoubleOrNull()?.toInt() ?: 10
            updateRunningTasks()
        } else if (preference == "activation_method") {
            updateActivationMethod()
        } else if (preference == "handle_opacity")
            dockHandle.alpha = sharedPreferences.getString("handle_opacity", "0.5")?.toFloatOrNull() ?: 0.5f
        else if (preference == "dock_height")
            updateDockHeight()
        else if (preference == "handle_position")
            updateHandlePosition()
        else if (preference == "show_battery_level")
            updateBatteryBtn()
        else if (preference == "show_resource_monitor") {
            if (sharedPreferences.getBoolean("show_resource_monitor", false)) {
                resourceMonitorTv?.visibility = View.VISIBLE
                startResourceMonitor()
            } else {
                resourceMonitorTv?.visibility = View.GONE
                stopResourceMonitor()
            }
        }
    }

    // Swipe mode (and its dock_activation_area setting) was removed — this window
    // is only ever full dockHeight (shown) or fully GONE (hidden) now, so there's
    // no separate "trigger area" to resize anymore. Kept as a no-op so the
    // dock_activation_area preference listener still has somewhere to call.
    private fun updateDockTrigger() {}

    private fun updateActivationMethod() {
        // Swipe mode removed — see hideDock() for why. Handle mode only now.
        if (!isPinned) {
            dock.visibility = View.GONE
            dockHandle.visibility = View.VISIBLE
        }
    }

    /**
     * 1.15: Improved display switching
     * يُستدعى لما تُضاف شاشة أو تُحذف → يُعيد بناء context + dock على الشاشة الصح
     */
    /**
     * Shows the live wallpaper on the secondary display via [Presentation],
     * if one is attached and nothing is showing there yet. See
     * SecondaryDisplayWallpaperPresentation's doc comment for why this is a
     * separate Presentation rather than relying on WallpaperService itself
     * (Android doesn't support per-display wallpapers — GitHub issue #15).
     */
    private fun startSecondaryDisplayWallpaperIfNeeded() {
        if (secondaryDisplayPresentation != null) return // already showing
        val display = DeviceUtils.getSecondaryDisplay(this) ?: return
        try {
            secondaryDisplayPresentation =
                com.plate.launcher.livewallpaper.service.SecondaryDisplayWallpaperPresentation(this, display)
                    .also { it.show() }
        } catch (e: Exception) {
            // Display could disappear between the null-check above and show()
            // (race with a hot-unplug), or the platform could refuse the
            // Presentation for some OEM-specific reason — either way, fail
            // quietly and let the next onDisplayAdded retry.
            secondaryDisplayPresentation = null
        }
    }

    /** Tears down the secondary-display wallpaper Presentation, if one is showing. */
    private fun stopSecondaryDisplayWallpaper() {
        secondaryDisplayPresentation?.let {
            try { it.dismiss() } catch (e: Exception) {}
        }
        secondaryDisplayPresentation = null
    }

    /** Shows the virtual-mouse cursor on the secondary display, if one is attached. */
    private fun startCursorOverlayIfNeeded() {
        if (cursorOverlay?.isShowing == true) {
            broadcastCursorStatus(true)
            return
        }
        cursorOverlay = com.plate.launcher.utils.CursorOverlayManager.forSecondaryDisplay(this)
            ?.also { it.show() }
        // .show() has its own internal try/catch and silently leaves the
        // manager in a not-showing state on failure (missing overlay
        // permission, display detached mid-call, etc.) — check isShowing
        // rather than just "cursorOverlay != null" so TrackpadActivity
        // gets told the truth, not just "a manager object exists".
        broadcastCursorStatus(cursorOverlay?.isShowing == true)
    }

    /** Removes the virtual-mouse cursor, if one is showing. */
    private fun stopCursorOverlay() {
        cursorOverlay?.dismiss()
        cursorOverlay = null
        broadcastCursorStatus(false)
    }

    /**
     * Tells TrackpadActivity whether drags/taps on it will currently
     * actually do anything in DIRECT mode. Sent whenever availability
     * changes (start/stop above) and once immediately in response to
     * ACTION_QUERY_CURSOR_STATUS, so a freshly-opened TrackpadActivity
     * doesn't have to guess — the two also-broken cases this closes:
     * accessibility service was never enabled (cursorOverlay stays null
     * forever, nothing ever calls startCursorOverlayIfNeeded/stop to
     * "announce" that), and mid-session disconnect while the phone screen
     * was already accepting drags.
     */
    private fun broadcastCursorStatus(available: Boolean) {
        sendBroadcast(
            Intent(DOCK_SERVICE_ACTION).setPackage(packageName)
                .putExtra("action", ACTION_CURSOR_STATUS)
                .putExtra(EXTRA_CURSOR_AVAILABLE, available)
        )
    }

    /** Moves the virtual-mouse cursor by a relative delta from a TrackpadActivity drag. */
    private fun moveCursorBy(dx: Int, dy: Int) {
        cursorOverlay?.moveBy(dx, dy)
    }

    /**
     * Simulates a click at the cursor's current position on the secondary
     * display via AccessibilityService.dispatchGesture() — the cursor
     * overlay itself is FLAG_NOT_TOUCHABLE (purely visual), so this is the
     * only way to actually interact with whatever is under it.
     *
     * [kind]:
     *  - "left"   → a single short tap
     *  - "right"  → a long-press (600ms) — touchscreens conventionally use
     *               this as the secondary/context-menu action, there's no
     *               direct touch equivalent of a physical right button
     *  - "double" → two short taps back-to-back, exactly like a real
     *               double-click is physically two presses, not one
     *               longer or different gesture. Dispatched as two
     *               separate dispatchGesture() calls (not one
     *               GestureDescription with two strokes) so each tap is
     *               indistinguishable from a real one to whatever app is
     *               listening for double-tap.
     */
    private fun dispatchCursorClick(kind: String) {
        val overlay = cursorOverlay ?: return
        when (kind) {
            "double" -> {
                dispatchSingleTap(overlay.x, overlay.y, TAP_DURATION_MS)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    dispatchSingleTap(overlay.x, overlay.y, TAP_DURATION_MS)
                }, DOUBLE_TAP_GAP_MS)
            }
            "right" -> dispatchSingleTap(overlay.x, overlay.y, LONG_PRESS_DURATION_MS)
            else -> dispatchSingleTap(overlay.x, overlay.y, TAP_DURATION_MS)
        }
    }

    private fun dispatchSingleTap(x: Int, y: Int, durationMs: Long) {
        val path = android.graphics.Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()
        try {
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            // canPerformGestures could be false on a stale accessibility_service.xml
            // the user hasn't re-granted after an app update — fail quietly
            // rather than crash the whole dock service over a missed click.
        }
    }

    private fun refreshForDisplayChange() {
        try {
            val newSecondary = sharedPreferences.getBoolean("prefer_last_display", false)
            if (newSecondary == secondary) {
                // Nothing to move — same display as before, this call was
                // triggered by some other display property changing (e.g.
                // resolution) rather than a display being added/removed.
                // Falls through to the existing per-frame layout recompute
                // below, same as before this fix.
            } else {
                // GitHub issue #15 ("prefer secondary screens" bugs — icons
                // duplicated, apps opening on the wrong screen, HDMI not
                // working at all): this used to only reassign the
                // `windowManager` field itself and call updateViewLayout()
                // on the dock. That does NOT move a View between
                // WindowManager instances — a View added via
                // oldWindowManager.addView() stays owned by that same
                // WindowManager object forever; updateViewLayout() can only
                // resize/reposition it within the display it already
                // belongs to. Reassigning the field just meant every
                // *subsequent* addView() call (audio panel, power menu,
                // notification panel, etc.) went to the new display while
                // the dock/corners/handle already on screen stayed stuck on
                // the old one — which is exactly "duplicated icons" (old
                // dock still visible on one display, new popups appearing
                // on the other) and "HDMI doesn't work" (nothing ever
                // actually left the primary display) as reported.
                val oldWindowManager = windowManager
                secondary = newSecondary
                context = DeviceUtils.getDisplayContext(this, newSecondary)
                // FIX: getSystemService(Class) returns WindowManager? (nullable
                // by its own official signature) but the windowManager field
                // is declared non-null (`lateinit var windowManager:
                // WindowManager`) — assigning the nullable result directly is
                // a compile error (type mismatch), meaning this exact
                // reassignment could never have actually built. Falling back
                // to the WINDOW_SERVICE string-key lookup + cast (same
                // pattern the field's own initial assignment uses elsewhere
                // in this class) if the typed lookup somehow comes back null,
                // rather than crashing display-switch entirely over it.
                windowManager = context.getSystemService(android.view.WindowManager::class.java)
                    ?: (context.getSystemService(WINDOW_SERVICE) as WindowManager)

                // Remove from the OLD WindowManager first — removeView on a
                // WindowManager that doesn't own the view throws
                // IllegalArgumentException, so this must use
                // oldWindowManager specifically, not the just-reassigned
                // `windowManager` field.
                for (view in listOf(dock, topRightCorner, bottomRightCorner, dockHandle)) {
                    try { oldWindowManager.removeView(view) } catch (e: Exception) {}
                }
                // Re-add through the NEW WindowManager, using each view's
                // existing LayoutParams object (already correctly sized —
                // recomputeDockHeight()/recomputeDockShape() below update
                // them in place, same objects, same reasoning as
                // onConfigurationChanged's existing single-atomic-update
                // pattern this function already followed).
                safeAddView(dock, dockLayoutParams)
                safeAddView(topRightCorner, cornersLayoutParams)
                safeAddView(bottomRightCorner, cornersLayoutParams)
                safeAddView(dockHandle, handleLayoutParams)
            }
            // FIX: single atomic updateViewLayout() — see the comment in
            // onConfigurationChanged for why calling updateDockHeight() then
            // updateDockShape() back to back could visibly stretch the dock
            // for a frame.
            recomputeDockHeight()
            recomputeDockShape()
            safeUpdateViewLayout(dock, dockLayoutParams)
            applyTheme()
            updateNavigationBar()
            updateQuickSettings()
            updateStatusPills()
            if (::tasksGv.isInitialized) updateRunningTasks(true)
        } catch (e: Exception) {
            android.util.Log.e("PerfectServer", "refreshForDisplayChange error: ${e.message}")
        }
    }

    /** Computes dockLayoutParams.height from the dock_height preference — does NOT call updateViewLayout(); see onConfigurationChanged. */
    private fun recomputeDockHeight() {
        dockHeight = Utils.dpToPx(context, sharedPreferences.getString("dock_height", "56")?.toIntOrNull() ?: 56)
        // GUARD: onConfigurationChanged can fire before onServiceConnected has
        // finished initializing dockLayoutParams (e.g. overlay permission not
        // yet granted, or a config change racing startup). Bail out safely
        // instead of crashing with UninitializedPropertyAccessException.
        if (!::dockLayoutParams.isInitialized) return
        dockLayoutParams.height = dockHeight
    }

    private fun updateDockHeight() {
        recomputeDockHeight()
        // Fix: always update layout regardless of pin state
        safeUpdateViewLayout(dock, dockLayoutParams)
    }

    private fun placeRunningApps() {
        // apps_lv is now inside LinearLayout (center_group), not directly in RelativeLayout
        val layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        tasksGv.layoutParams = layoutParams

        // Update center_group position inside RelativeLayout
        val groupParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT
        )
        val m = Utils.dpToPx(context, 4)
        groupParams.setMargins(m, 0, m, 0)
        if (sharedPreferences.getBoolean("center_running_apps", true)) {
            groupParams.addRule(RelativeLayout.CENTER_IN_PARENT)
        } else {
            groupParams.addRule(RelativeLayout.END_OF, R.id.nav_panel)
            groupParams.addRule(RelativeLayout.START_OF, R.id.system_tray)
            groupParams.addRule(RelativeLayout.CENTER_VERTICAL)
        }
        val centerGroup = dock.findViewById<LinearLayout>(R.id.center_group)
        centerGroup.layoutParams = groupParams
        centerGroup.requestLayout()
        dockLayout.requestLayout()
        updateCenterAppsBtn()
    }

    private fun updateCenterAppsBtn() {
        val centerMode = sharedPreferences.getBoolean("center_apps_btn", false)
        if (centerMode) {
            // Hide button from nav_panel and show it in center
            appsBtn.visibility = View.GONE
            appsBtnCenter.visibility = if (
                sharedPreferences.getBoolean("enable_nav_apps", true)
            ) View.VISIBLE else View.GONE
            // Maximum 5 apps in center mode
            maxApps = minOf(
                (sharedPreferences.getString("max_running_apps", "10") ?: "10")
                    .toDoubleOrNull()?.toInt() ?: 10,
                5
            )
            maxAppsLandscape = minOf(
                (sharedPreferences.getString("max_running_apps_landscape", "10") ?: "10")
                    .toDoubleOrNull()?.toInt() ?: 10,
                5
            )
        } else {
            // Return button to its original position
            appsBtnCenter.visibility = View.GONE
            appsBtn.visibility = if (
                sharedPreferences.getBoolean("enable_nav_apps", true)
            ) View.VISIBLE else View.GONE
            maxApps = (sharedPreferences.getString("max_running_apps", "10") ?: "10")
                .toDoubleOrNull()?.toInt() ?: 10
            maxAppsLandscape = (sharedPreferences.getString("max_running_apps_landscape", "10") ?: "10")
                .toDoubleOrNull()?.toInt() ?: 10
        }
        updateRunningTasks(true)
    }

    private fun loadPinnedApps() {
        pinnedApps = AppUtils.getPinnedApps(context, AppUtils.DOCK_PINNED_LIST)
    }

    // ── يحمّل أيقونة زر المستخدم بالدوك مع الأولوية الصحيحة:
    // 1) صورة الملتي يوزر المخصصة (لو محفوظة لهذا المستخدم تحديدًا)
    // 2) أيقونة النظام تبع نفس المستخدم (UserManager.getUserIcon عبر MultiUserManager)
    // 3) الـ URI المخصص القديم من الإعدادات العامة (توافقية قديمة)
    // 4) أيقونة الجهاز الافتراضية (DeviceUtils.getUserIcon)
    // تُستدعى عند بدء تشغيل الدوك، وأيضًا كل مرة يتغيّر فيها البروفايل
    // (حفظ صورة جديدة / تبديل مستخدم) عن طريق ACTION_REFRESH_USER_PROFILE —
    // بدون الحاجة لإعادة تشغيل التطبيق.
    private fun refreshUserButton() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val currentUserId = com.plate.launcher.utils.MultiUserManager.getCurrentUserId()
            // AvatarDisplay (see its kdoc) is the shared GIF-aware +
            // circular-clip helper every other avatar surface uses. It does
            // its own decoding internally and must run on the main thread
            // (it touches the ImageView directly and starts the
            // AnimatedImageDrawable) — unlike the old path here, which ran
            // entirely on IO and only ever considered the static avatar,
            // which is why the dock's user button showed a GIF avatar as a
            // frozen, uncropped square instead of animated and circular.
            userBtn.imageTintList = null
            val shown = com.plate.launcher.utils.AvatarDisplay.showOn(userBtn, context, currentUserId)
            if (!shown) {
                serviceScope.launch(Dispatchers.IO) {
                    val icon: android.graphics.Bitmap? = run {
                        // Legacy custom URI (compat)
                        val uri = sharedPreferences.getString("user_icon_uri", "default")
                        if (uri != "default") {
                            try {
                                val bmp = Utils.getBitmapFromUri(context, Uri.parse(uri))
                                Utils.getCircularBitmap(bmp)
                            } catch (e: Exception) { null }
                        } else null
                    } ?: run {
                        // Device default user icon
                        try { DeviceUtils.getUserIcon(context) } catch (e: Exception) { null }
                    }
                    withContext(Dispatchers.Main) {
                        if (icon != null) {
                            userBtn.imageTintList = null   // remove white tint — real photo
                            userBtn.setImageBitmap(icon)
                        } else {
                            userBtn.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
                            userBtn.setImageResource(R.drawable.ic_user)
                        }
                    }
                }
            }
        }
    }

    private fun updateRunningTasks(recreateAdapter: Boolean = false) {
        val now = System.currentTimeMillis()
        if (now - lastUpdate < 500 && !recreateAdapter)
            return
        lastUpdate = now

        // PERF FIX: getRunningTasks() / getRecentTasks() are blocking IPC calls.
        // Moving them to Dispatchers.IO eliminates main-thread stalls (the 300-500ms
        // jank visible when the dock first appears or apps are switched).
        // Only the RecyclerView update is posted back to Main.
        serviceScope.launch {
            val pinnedSnapshot = if (::pinnedApps.isInitialized) ArrayList(pinnedApps) else ArrayList()
            val apps = ArrayList<DockApp>()
            pinnedSnapshot.forEach { pinnedApp ->
                apps.add(DockApp(pinnedApp.name, pinnedApp.packageName, pinnedApp.icon))
            }

            val nApps =
                if (orientation == Configuration.ORIENTATION_PORTRAIT) maxApps else maxAppsLandscape

            // heavy IPC happens here, off the main thread
            val fetchedTasks: ArrayList<AppTask> = withContext(Dispatchers.IO) {
                val useShizukuDetection = sharedPreferences.getBoolean("shizuku_active_app_detection", false)
                val shizuku = com.plate.launcher.utils.ShizukoManager.getInstance(applicationContext)

                if (systemApp) {
                    AppUtils.getRunningTasks(activityManager, packageManager, nApps)
                } else if (useShizukuDetection && shizuku.hasPermission) {
                    // ── Shizuku متاح: نقدر نعرف التطبيق النشط الحقيقي + التطبيقات
                    // الشغالة بالخلفية بدون قيود getRunningTasks() على التطبيقات
                    // العادية. أول شي نجرب نجيب المهام الحقيقية عبر
                    // getTasksViaShizuku (تشمل freeform/multi-window تطبيقات
                    // ما تظهر بـ getRunningTasks() العادية)؛ لو فشلت أو رجعت
                    // فاضية (صلاحية سُحبت بعد الفحص أعلاه، خطأ بتفسير
                    // مخرجات `am stack list` على ROM معيّن، إلخ) نرجع لقائمة
                    // الاستخدام الأخير getRecentTasks كـ fallback — نفس
                    // السلوك القديم قبل هذا التحسين ──
                    val viaShizuku = AppUtils.getTasksViaShizuku(context, packageManager, nApps)
                    val recent = viaShizuku ?: AppUtils.getRecentTasks(context, nApps)
                    shizuku.getForegroundPackage()?.let { fg ->
                        if (fg.isNotEmpty() && fg != packageName &&
                            fg != AppUtils.getCurrentLauncher(packageManager)
                        ) {
                            AppUtils.currentApp = fg
                        }
                    }
                    // Bug fix — dock only showed one app as "running" no matter how
                    // many are actually open. "recent" above is a 24h *usage
                    // history* list when we fell back to getRecentTasks (see its
                    // own kdoc — deliberately "recently used", not "currently
                    // running"), and until now Shizuku was only ever used to
                    // correct which ONE of those apps gets the foreground/bold-line
                    // style — nothing checked whether the rest of that
                    // history list were actually still alive in the
                    // background, so every icon with any task always drew
                    // some indicator regardless of real liveness.
                    //
                    // The icon list itself stays the full history/task list
                    // (getRunningPackages() is deliberately NOT used to
                    // filter fetchedTasks here) — recently-used apps should
                    // stay visible in the dock even once closed, just
                    // without any running indicator. What Shizuku actually
                    // corrects is per-app liveness for the indicator dot/line
                    // itself: see AppUtils.trulyRunningPackages below, read
                    // by DockAppAdapter to decide dot vs. line vs. nothing.
                    shizuku.getRunningPackages().let { running ->
                        if (running.isNotEmpty()) {
                            AppUtils.trulyRunningPackages = running.toSet()
                        }
                        // Empty result (unexpected dumpsys format on this
                        // ROM, transient failure, etc.) — leave whatever
                        // trulyRunningPackages already held rather than
                        // wiping every indicator out from under a
                        // momentary hiccup.
                    }
                    recent
                } else {
                    // Shizuku detection off (or unavailable) — no real
                    // liveness signal to show per-app, so clear any stale
                    // set from a previous session/toggle rather than letting
                    // DockAppAdapter draw indicators based on data that's no
                    // longer being refreshed.
                    AppUtils.trulyRunningPackages = null
                    AppUtils.getRecentTasks(context, nApps)
                }
            }

            // Build the dock app list (pure in-memory, fast)
            if (systemApp) {
                for (j in 1..fetchedTasks.size) {
                    val task = fetchedTasks[fetchedTasks.size - j]
                    val index = AppUtils.containsTask(apps, task)
                    if (index != -1) apps[index].addTask(task)
                    else apps.add(DockApp(task))
                }
            } else {
                fetchedTasks.reversed().forEach { task ->
                    if (AppUtils.containsTask(apps, task) == -1)
                        apps.add(DockApp(task))
                }
            }

            // Back to Main for all UI updates
            withContext(Dispatchers.Main) {
                tasks = fetchedTasks


                // Let RecyclerView size itself naturally along its scroll axis (width, always horizontal now).
                tasksGv.layoutParams?.width = ViewGroup.LayoutParams.WRAP_CONTENT
                val adapter = tasksGv.adapter
                if (adapter is DockAppAdapter && !recreateAdapter)
                    adapter.updateApps(apps)
                else
                    tasksGv.adapter = DockAppAdapter(context, apps, this@DockService, iconPackUtils, dockHeight)

                // NOTE: WifiManager.isWifiEnabled is deprecated on API 29+, but there is
                // no safe alternative that works on all API levels without requesting
                // location permission — this is a deliberate, permanent choice, not a gap.
                @Suppress("DEPRECATION")
                wifiBtn.setImageResource(
                    if (wifiManager.isWifiEnabled) R.drawable.ic_wifi_on else R.drawable.ic_wifi_off)
                val bluetoothAdapter = bluetoothManager.adapter
                if (bluetoothAdapter != null)
                    bluetoothBtn.setImageResource(
                        if (bluetoothAdapter.isEnabled) R.drawable.ic_bluetooth else R.drawable.ic_bluetooth_off)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        orientation = newConfig.orientation
        // GUARD: this can fire before onServiceConnected finishes setting up
        // the dock (overlay permission not granted yet, or a config change
        // racing startup). Nothing below is safe to touch until dockLayoutParams
        // exists, so bail out — onServiceConnected will apply the current
        // config once it finishes initializing anyway.
        if (!::dockLayoutParams.isInitialized) {
            com.plate.launcher.utils.StrictDebugMode.logFailure(
                this, "DockService.onConfigurationChanged",
                "Fired before dockLayoutParams was initialized (dock not attached yet) — skipped safely."
            )
            return
        }
        // Display or DPI changed — recalculate sizes.
        // FIX: updateDockHeight() and updateDockShape() each used to call
        // windowManager.updateViewLayout() separately — updateDockHeight()
        // first (with the OLD width/gravity still on dockLayoutParams at that
        // point), then updateDockShape() right after (changing width AND
        // gravity together, e.g. START -> CENTER_HORIZONTAL for a round
        // dock). Two separate updateViewLayout() calls back to back is not
        // atomic: the WindowManager can briefly render the first update (new
        // height, but still the OLD width/gravity) before the second lands a
        // moment later — visible as the dock appearing to stretch/elongate
        // toward one edge for a frame instead of just resizing in place
        // (reported when toggling airplane mode, which also fires a config
        // change). Now both functions only compute values into
        // dockLayoutParams' fields, and a single updateViewLayout() call at
        // the end applies the fully-computed final state in one atomic step.
        recomputeDockHeight()
        recomputeDockShape()
        safeUpdateViewLayout(dock, dockLayoutParams)
        // FIX: Re-apply theme on ANY config change (orientation, uiMode dark/light,
        // density). Without this, colors stay stale when night mode or display
        // settings change at runtime. applyTheme() uses 'this' (live service context)
        // so DynamicColors always picks up the freshest Material You palette.
        applyTheme()
        if (::tasksGv.isInitialized) updateRunningTasks(true)
    }

    /** Computes dockLayoutParams.width/gravity/y from the round_dock preference — does NOT call updateViewLayout(); see onConfigurationChanged. */
    /**
     * Re-orients the dock's actual layout between horizontal (row, for a
     * top/bottom dock) and vertical (column, for a left/right dock) —
     * matching how Windows' taskbar behaves when docked to a side edge:
     * icons/buttons stay upright, only their arrangement and the dock's
     * own alignment within its RelativeLayout change.
     *
     * Three top-level LinearLayout groups sit inside dock_layout
     * (nav_panel, center_group, system_tray), each with its own nested
     * LinearLayout "pill" groups (action_btns_group, nav_btns_group,
     * status_area) — every one of them needs both its `orientation` and
     * its RelativeLayout alignment (start/end ↔ top/bottom) flipped
     * together, or the pieces would end up correctly stacked internally
     * but positioned in the wrong part of a vertical dock (e.g. nav_panel
     * still glued to the left edge of a dock that's now running down the
     * right edge of the screen).
     */
    private fun recomputeDockShape() {
        val isRound  = sharedPreferences.getBoolean("round_dock", false)

        dockLayout.setBackgroundResource(if (isRound) R.drawable.round_rect else R.drawable.rect)
        ColorUtils.applyMainColor(context, sharedPreferences, dockLayout)

        // 1.15: Override dock background alpha if user enabled it
        if (sharedPreferences.getBoolean("override_dock_background_alpha", false)) {
            val alpha = sharedPreferences.getString("dock_background_alpha", "255")?.toIntOrNull() ?: 255
            dockLayout.background?.alpha = alpha
        }

        val margin = Utils.dpToPx(context, 8)
        val displayId = if (secondary)
            DeviceUtils.getSecondaryDisplay(context)?.displayId ?: Display.DEFAULT_DISPLAY
        else
            Display.DEFAULT_DISPLAY
        val dockPosition = com.plate.launcher.utils.DockPositionUtils.get(sharedPreferences)

        // FIX: was DeviceUtils.getDisplayMetrics().widthPixels, which reports
        // the RAW physical display width (via currentWindowMetrics.bounds) —
        // including areas under display cutouts/rounded corners/side bars
        // that an overlay window can't actually render into. For the
        // non-round dock (MATCH_PARENT) that mismatch is invisible — the
        // system clips it automatically. But for the round dock, width is
        // computed manually as displayWidth - 2*margin and then explicitly
        // centered — if displayWidth was already larger than the actually
        // usable render area, this could still end up wider than what's
        // visually available, appearing to overflow the screen edge
        // ("الدوك بيزيد عرضاً أفقياً... أطول من حواف الشاشة"). Subtracting
        // the system window insets gives the true usable width instead.
        val displayWidth = DeviceUtils.getUsableDisplayWidth(context, displayId)

        // FIX: rare cases where the dock briefly "shrinks from both sides"
        // (looking too narrow/compressed symmetrically, not tied to left or
        // right specifically) — this points at getUsableDisplayWidth()
        // occasionally returning a bad transient value, most likely because
        // it was called at a moment the system's own WindowInsets/display
        // metrics were mid-update (e.g. right during a rotation, font-scale
        // change, or resolution change) and returned a stale/partial
        // measurement instead of the final settled one. Guard against
        // applying an implausible width: if the computed value is
        // drastically smaller than what we last successfully applied (more
        // than a 40% drop in one step — real screens don't change size that
        // fast), ignore this measurement and reuse the last known-good width
        // instead of visibly shrinking the dock to a wrong size for a frame.
        val safeDisplayWidth = if (lastGoodDockWidth > 0 && displayWidth < lastGoodDockWidth * 6 / 10) {
            lastGoodDockWidth
        } else {
            lastGoodDockWidth = displayWidth
            displayWidth
        }

        if (isRound) {
            // GitHub issue #15 ("prefer secondary screens: when activating
            // rounded corners on the dock, it also causes some bugs, the
            // icons are all placed on both the right and left"): this
            // branch computed width from getUsableDisplayWidth() (which
            // already subtracts left+right insets combined) but never
            // compensated for an ASYMMETRIC inset (insets.left != insets.right
            // — common on external/secondary displays with an off-center
            // cutout or camera). Gravity.CENTER_HORIZONTAL centers the view
            // within the full raw display width, not the usable area, so an
            // asymmetric inset pushed the dock off-center — the reported
            // "icons on both the right and left" gap. The non-round branch
            // below already computes leftInset for exactly this reason;
            // reuse the same logic here and fold it into the centering x.
            val displayContext = DeviceUtils.getDisplayContext(context, secondary)
            val leftInset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val insetWm = displayContext.getSystemService(WINDOW_SERVICE) as WindowManager
                    insetWm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                        android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout()
                    ).left
                } catch (e: Throwable) { 0 }
            } else 0
            dockLayoutParams.width   = safeDisplayWidth - 2 * margin
            dockLayoutParams.gravity =
                com.plate.launcher.utils.DockPositionUtils.dockGravity(dockPosition, centered = true)
            dockLayoutParams.x       = leftInset
            dockLayoutParams.y       = margin
        } else {
            // Discord report ("The dock isn't covering the left area and is
            // cut off" — CMF Phone 2 Pro, Android 16): MATCH_PARENT +
            // Gravity.START previously relied on x defaulting to 0 with no
            // explicit compensation for a left-side inset/cutout, unlike
            // the round-dock branch above, which already computes usable
            // width (and implicitly centers around any asymmetric insets)
            // via getUsableDisplayWidth. On a device where the system
            // reports a nonzero left inset (a cutout, punch-hole camera
            // area, or gesture-nav edge reservation this particular device/
            // Android 16 build treats differently), x=0 could sit partly
            // under that inset instead of starting exactly at the usable
            // area's left edge — same "not covering the left area" this
            // report described.
            val displayContext = DeviceUtils.getDisplayContext(context, secondary)
            val leftInset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val insetWm = displayContext.getSystemService(WINDOW_SERVICE) as WindowManager
                    insetWm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                        android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout()
                    ).left
                } catch (e: Throwable) { 0 }
            } else 0
            dockLayoutParams.width   = WindowManager.LayoutParams.MATCH_PARENT
            dockLayoutParams.gravity =
                com.plate.launcher.utils.DockPositionUtils.dockGravity(dockPosition, centered = false)
            dockLayoutParams.x       = leftInset
            dockLayoutParams.y       = 0
        }
    }

    private fun updateDockShape() {
        recomputeDockShape()
        safeUpdateViewLayout(dock, dockLayoutParams)
    }

    private fun updateNavigationBar() {
        val centerMode = sharedPreferences.getBoolean("center_apps_btn", false)
        val appsEnabled = sharedPreferences.getBoolean("enable_nav_apps", true)
        // Prevent two buttons showing at once — only one visible based on state
        if (centerMode) {
            appsBtn.visibility = View.GONE
            appsBtnCenter.visibility = if (appsEnabled) View.VISIBLE else View.GONE
        } else {
            appsBtnCenter.visibility = View.GONE
            appsBtn.visibility = if (appsEnabled) View.VISIBLE else View.GONE
        }
        backBtn.visibility =
            if (sharedPreferences.getBoolean("enable_nav_back", true)) View.VISIBLE else View.GONE
        homeBtn.visibility =
            if (sharedPreferences.getBoolean("enable_nav_home", true)) View.VISIBLE else View.GONE
        recentBtn.visibility =
            if (sharedPreferences.getBoolean("enable_nav_recents", true)) View.VISIBLE else View.GONE
        assistBtn.visibility =
            if (sharedPreferences.getBoolean("enable_nav_assist", false)) View.VISIBLE else View.GONE

        // FIX: إخفاء الـ pill container كاملاً لما كل أزراره GONE
        val navAnyVisible = listOf(backBtn, homeBtn, recentBtn, assistBtn)
            .any { it.visibility == View.VISIBLE }
        navBtnsGroup.visibility = if (navAnyVisible) View.VISIBLE else View.GONE

        val actionAnyVisible = listOf(userBtn, wallpaperBtn, castBtn)
            .any { it.visibility == View.VISIBLE }
        actionBtnsGroup.visibility = if (actionAnyVisible) View.VISIBLE else View.GONE

        updateNavPinPositions()
    }

    private fun updateQuickSettings() {
        notificationBtn.visibility =
            if (sharedPreferences.getBoolean("enable_qs_notif", true)) View.VISIBLE else View.GONE
        bluetoothBtn.visibility =
            if (sharedPreferences.getBoolean("enable_qs_bluetooth", false)) View.VISIBLE else View.GONE
        batteryBtn.visibility =
            if (sharedPreferences.getBoolean("enable_qs_battery", false)) View.VISIBLE else View.GONE
        wifiBtn.visibility =
            if (sharedPreferences.getBoolean("enable_qs_wifi", true)) View.VISIBLE else View.GONE
        val pinVisible = sharedPreferences.getBoolean("enable_qs_pin", true)
        pinBtn.visibility = if (pinVisible) View.VISIBLE else View.GONE
        if (::hidePillsBtn.isInitialized) {
            val pillsEnabled = sharedPreferences.getBoolean("enable_pill_time", false) ||
                               sharedPreferences.getBoolean("enable_pill_battery", false)
            hidePillsBtn.visibility = if (pillsEnabled) View.VISIBLE else View.GONE
        }
        if (::pinPill.isInitialized) {
            val pillAnyVisible = (pinBtn.visibility == View.VISIBLE) ||
                                 (::hidePillsBtn.isInitialized && hidePillsBtn.visibility == View.VISIBLE)
            pinPill.visibility = if (pillAnyVisible) View.VISIBLE else View.GONE
        }
        volumeBtn.visibility =
            if (sharedPreferences.getBoolean("enable_qs_vol", true)) View.VISIBLE else View.GONE
        wallpaperBtn.visibility =
            if (sharedPreferences.getBoolean("enable_qs_wallpaper", false)) View.VISIBLE else View.GONE
        userBtn.visibility =
            if (sharedPreferences.getBoolean("enable_qs_user", true)) View.VISIBLE else View.GONE
        val actionAnyVisible = listOf(userBtn, wallpaperBtn, castBtn)
            .any { it.visibility == View.VISIBLE }
        actionBtnsGroup.visibility = if (actionAnyVisible) View.VISIBLE else View.GONE
        dateTv.visibility =
            if (sharedPreferences.getBoolean("enable_qs_date", true)) View.VISIBLE else View.GONE

        // FIX: إخفاء statusArea pill كاملاً لما كل أزرار الـ QS تصير GONE
        val qsAnyVisible = listOf(
            notificationBtn, bluetoothBtn, batteryBtn, wifiBtn,
            volumeBtn, wallpaperBtn
        ).any { it.visibility == View.VISIBLE }
        statusArea.visibility = if (qsAnyVisible) View.VISIBLE else View.GONE

        // FIX 2: إخفاء system_tray كاملاً لما statusArea + dateTv + pinPill + navBtnsGroup كلهم GONE
        val pinInTrayVisible = (::pinPill.isInitialized && pinPill.parent == systemTray && pinPill.visibility == View.VISIBLE)
        val navInTrayVisible = (::navBtnsGroup.isInitialized && navBtnsGroup.parent == systemTray && navBtnsGroup.visibility == View.VISIBLE)
        val trayAnyVisible = qsAnyVisible || dateTv.visibility == View.VISIBLE || pinInTrayVisible || navInTrayVisible
        systemTray.visibility = if (trayAnyVisible) View.VISIBLE else View.GONE

        updateNavPinPositions()
    }

    private fun updateNavPinPositions() {
        if (!::navPanel.isInitialized || !::pinPill.isInitialized || !::navBtnsGroup.isInitialized) return
        val swapped = sharedPreferences.getBoolean("swap_nav_pin", false)
        if (swapped) {
            // navBtnsGroup moves to systemTray (first child in systemTray)
            if (navBtnsGroup.parent != systemTray) {
                (navBtnsGroup.parent as? ViewGroup)?.removeView(navBtnsGroup)
                systemTray.addView(navBtnsGroup, 0)
            }
            // pinPill moves to navPanel (after actionBtnsGroup)
            if (pinPill.parent != navPanel) {
                (pinPill.parent as? ViewGroup)?.removeView(pinPill)
                val idx = navPanel.indexOfChild(actionBtnsGroup).let { if (it >= 0) it + 1 else navPanel.childCount }
                navPanel.addView(pinPill, idx)
            }
        } else {
            // Normal: navBtnsGroup in navPanel (after actionBtnsGroup)
            if (navBtnsGroup.parent != navPanel) {
                (navBtnsGroup.parent as? ViewGroup)?.removeView(navBtnsGroup)
                val idx = navPanel.indexOfChild(actionBtnsGroup).let { if (it >= 0) it + 1 else navPanel.childCount }
                navPanel.addView(navBtnsGroup, idx)
            }
            // pinPill in systemTray (first child in systemTray)
            if (pinPill.parent != systemTray) {
                (pinPill.parent as? ViewGroup)?.removeView(pinPill)
                systemTray.addView(pinPill, 0)
            }
        }
    }

    private fun updateBatteryPillContent() {
        val level = if (::batteryReceiver.isInitialized) batteryReceiver.level else 100
        val isCharging = ::batteryReceiver.isInitialized && batteryReceiver.isCharging

        // 1. Battery percentage text (embedded inside capsule)
        batteryPillTv?.text = "$level"

        // 2. Battery icon: charging mode (boundary thunderbolt) vs low battery (<20% red) vs solid white capsule
        val batteryRes = when {
            isCharging -> R.drawable.stat_sys_battery_charge
            level < 20 -> R.drawable.stat_sys_battery_low
            else -> R.drawable.stat_sys_battery
        }
        pillBatteryIv?.setImageResource(batteryRes)

        val textColor = when {
            level < 20 -> Color.WHITE
            else -> Color.BLACK
        }
        batteryPillTv?.setTextColor(textColor)

        // 3. Wi-Fi state: highlight when connected, unhighlight (alpha 0.35f) when not connected
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val isWifiConnected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val net = cm?.activeNetwork
                val caps = cm?.getNetworkCapabilities(net)
                caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
            } else {
                @Suppress("DEPRECATION")
                cm?.getNetworkInfo(android.net.ConnectivityManager.TYPE_WIFI)?.isConnected == true
            }
            pillWifiIv?.alpha = if (isWifiConnected) 1.0f else 0.35f
        } catch (e: Exception) {
            pillWifiIv?.alpha = 1.0f
        }

        // 4. Cellular signal state:
        try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
            val isAirplaneMode = Settings.Global.getInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
            val hasSim = tm?.simState == android.telephony.TelephonyManager.SIM_STATE_READY
            pillSignalIv?.alpha = if (hasSim && !isAirplaneMode) 1.0f else 0.35f
        } catch (e: Exception) {
            pillSignalIv?.alpha = 1.0f
        }
    }

    private fun updateStatusPills() {
        val showTime = sharedPreferences.getBoolean("enable_pill_time", false)
        val showBattery = sharedPreferences.getBoolean("enable_pill_battery", false)

        val opacity = (sharedPreferences.getString("pill_transparency", "0.9")?.toFloatOrNull() ?: 0.9f)
            .coerceIn(0.1f, 1f)
        val heightDp = (sharedPreferences.getString("pill_height", "30")?.toIntOrNull() ?: 30)
            .coerceIn(20, 50)
        val timeLengthDp = (sharedPreferences.getString("pill_time_length", "75")?.toIntOrNull() ?: 75)
            .coerceIn(40, 300)
        val batteryLengthDp = (sharedPreferences.getString("pill_battery_length", "90")?.toIntOrNull() ?: 90)
            .coerceIn(40, 240)

        val sideMarginDp = (sharedPreferences.getString("pill_side_margin", "4")?.toIntOrNull() ?: 4)
            .coerceIn(0, 50)
        val heightPx = Utils.dpToPx(context, heightDp)
        val timeMinWidthPx = Utils.dpToPx(context, timeLengthDp)
        val batteryMinWidthPx = Utils.dpToPx(context, batteryLengthDp)
        val cornerRadius = heightPx / 2f
        val topInsetPx = Utils.dpToPx(context, 4)
        val sideInsetPx = Utils.dpToPx(context, sideMarginDp)

        val textSizeSp = (sharedPreferences.getString("pill_text_size", "12")?.toFloatOrNull() ?: 12f)
            .coerceIn(8f, 22f)
        val textWeightLevel = (sharedPreferences.getString("pill_text_weight", "4")?.toIntOrNull() ?: 4)
            .coerceIn(1, 5)

        fun applyPillTextStyling(tv: TextView?) {
            tv ?: return
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            val typeface = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val weight = when (textWeightLevel) {
                    1 -> 400
                    2 -> 500
                    3 -> 700
                    4, 5 -> 900
                    else -> 700
                }
                android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, weight, false)
            } else {
                when (textWeightLevel) {
                    1 -> android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
                    2 -> android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                    3 -> android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
                    4, 5 -> android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD)
                    else -> android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
                }
            }
            tv.typeface = typeface
            tv.paint.isFakeBoldText = (textWeightLevel == 5)
        }

        // Time Pill (Top-Left)
        if (showTime) {
            if (timePillView == null) {
                timePillView = LayoutInflater.from(ContextThemeWrapper(context, R.style.AppTheme_Dock))
                    .inflate(R.layout.pill_time, null)
                timePillTv = timePillView!!.findViewById(R.id.pill_time_tv)
                datePillTv = timePillView!!.findViewById(R.id.pill_date_tv)
            }
            applyPillTextStyling(timePillTv)
            applyPillTextStyling(datePillTv)
            timePillView?.alpha = opacity
            val timeContainer = timePillView?.findViewById<View>(R.id.pill_time_container)
            timeContainer?.minimumWidth = timeMinWidthPx
            timeContainer?.minimumHeight = heightPx

            if (timePillParams == null) {
                timePillParams = Utils.makeWindowParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    context, secondary
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                }
            }
            timePillParams?.apply {
                flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    fitInsetsTypes = 0
                }
                x = sideInsetPx
                y = topInsetPx
                height = WindowManager.LayoutParams.WRAP_CONTENT
                width = WindowManager.LayoutParams.WRAP_CONTENT
            }

            if (timePillView?.windowToken == null) {
                safeAddView(timePillView!!, timePillParams!!)
            } else {
                safeUpdateViewLayout(timePillView!!, timePillParams!!)
            }
            timePillView?.visibility = if (arePillsHidden) View.GONE else View.VISIBLE
        } else {
            timePillView?.let { v ->
                if (v.windowToken != null) {
                    try { windowManager.removeView(v) } catch (e: Exception) {}
                }
            }
        }

        // Battery / Status Pill (Top-Right)
        if (showBattery) {
            if (batteryPillView == null) {
                batteryPillView = LayoutInflater.from(ContextThemeWrapper(context, R.style.AppTheme_Dock))
                    .inflate(R.layout.pill_battery, null)
                batteryPillTv = batteryPillView!!.findViewById(R.id.pill_battery_tv)
                pillWifiIv = batteryPillView!!.findViewById(R.id.pill_wifi_iv)
                pillSignalIv = batteryPillView!!.findViewById(R.id.pill_signal_iv)
                pillBatteryIv = batteryPillView!!.findViewById(R.id.pill_battery_iv)
            }
            batteryPillView?.alpha = opacity
            val batteryContainer = batteryPillView?.findViewById<View>(R.id.pill_battery_container)
            batteryContainer?.minimumWidth = batteryMinWidthPx
            batteryContainer?.minimumHeight = heightPx

            // Bold & spaceful scaling matching Android 16 QPR2 design language (Image 2)
            val iconH = (heightDp * 0.50f).coerceIn(12f, 26f).toInt()
            val signalWidthDp = (iconH * 1.33f).toInt()
            val wifiWidthDp = (iconH * 1.25f).toInt()
            val batteryWidthDp = (iconH * 1.86f).toInt()
            val iconGapDp = (heightDp * 0.33f).coerceIn(8f, 16f).toInt()

            val iconHeightPx = Utils.dpToPx(context, iconH)
            val signalWidthPx = Utils.dpToPx(context, signalWidthDp)
            val wifiWidthPx = Utils.dpToPx(context, wifiWidthDp)
            val batteryWidthPx = Utils.dpToPx(context, batteryWidthDp)
            val iconGapPx = Utils.dpToPx(context, iconGapDp)

            (pillSignalIv?.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.width = signalWidthPx
                lp.height = iconHeightPx
                lp.marginEnd = iconGapPx
                pillSignalIv?.layoutParams = lp
            }
            (pillWifiIv?.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.width = wifiWidthPx
                lp.height = iconHeightPx
                lp.marginEnd = iconGapPx
                pillWifiIv?.layoutParams = lp
            }
            (pillBatteryIv?.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.width = batteryWidthPx
                lp.height = iconHeightPx
                pillBatteryIv?.layoutParams = lp
            }

            val batteryTextSizeSp = (iconH * 0.58f).coerceIn(7.5f, 13f)
            batteryPillTv?.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, batteryTextSizeSp)
            val pillTypeface = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, 900, false)
            } else {
                android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD)
            }
            batteryPillTv?.typeface = pillTypeface

            val bgBattery = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                this.cornerRadius = cornerRadius
                setColor(Color.BLACK)
            }
            batteryContainer?.background = bgBattery

            updateBatteryPillContent()

            if (batteryPillParams == null) {
                batteryPillParams = Utils.makeWindowParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    context, secondary
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                }
            }
            batteryPillParams?.apply {
                flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    fitInsetsTypes = 0
                }
                x = sideInsetPx
                y = topInsetPx
                height = WindowManager.LayoutParams.WRAP_CONTENT
                width = WindowManager.LayoutParams.WRAP_CONTENT
            }

            if (batteryPillView?.windowToken == null) {
                safeAddView(batteryPillView!!, batteryPillParams!!)
            } else {
                safeUpdateViewLayout(batteryPillView!!, batteryPillParams!!)
            }
            batteryPillView?.visibility = if (arePillsHidden) View.GONE else View.VISIBLE
        } else {
            batteryPillView?.let { v ->
                if (v.windowToken != null) {
                    try { windowManager.removeView(v) } catch (e: Exception) {}
                }
            }
        }

        // Register time & connectivity receiver if either pill is active
        if ((showTime || showBattery) && timeTickReceiver == null) {
            timeTickReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    updateBatteryPillContent()
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_DATE_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                @Suppress("DEPRECATION")
                addAction(android.net.ConnectivityManager.CONNECTIVITY_ACTION)
                addAction(android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION)
                addAction(android.net.wifi.WifiManager.NETWORK_STATE_CHANGED_ACTION)
            }
            ContextCompat.registerReceiver(this, timeTickReceiver!!, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } else if (!showTime && !showBattery && timeTickReceiver != null) {
            try { unregisterReceiver(timeTickReceiver) } catch (e: Exception) {}
            timeTickReceiver = null
        }
    }

    private fun launchAssistant() {
        // Google Assistant directly — no customization
        val assistIntent = packageManager.getLaunchIntentForPackage("com.google.android.googlequicksearchbox")
        if (assistIntent != null) {
            assistIntent.action = Intent.ACTION_ASSIST
            assistIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { context.startActivity(assistIntent) } catch (e: Exception) {}
        } else {
            try {
                startActivity(Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: ActivityNotFoundException) {}
        }
    }

    private fun openBluetoothSettings() {
        launchApp("freeform", null, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }

    /**
     * Toggle Bluetooth مباشرة:
     * - Android 12+: يستخدم BluetoothAdapter.enable()/disable() — يحتاج BLUETOOTH_CONNECT
     * - لو فشل وعندنا Root/Shizuku: يستخدم shell "svc bluetooth enable/disable"
     * - fallback أخير: يفتح Bluetooth Settings (بدل system panel)
     */
    @SuppressLint("MissingPermission")
    private fun toggleBluetooth() {
        val adapter = bluetoothManager.adapter ?: return
        val targetEnabled = !adapter.isEnabled

        // محاولة 1: BluetoothAdapter مباشرة (يشتغل لو عندك BLUETOOTH_CONNECT)
        try {
            if (targetEnabled) adapter.enable() else adapter.disable()
            // حدّث الأيقونة بعد لحظة بسيطة (الـ adapter يحتاج وقت)
            android.os.Handler(mainLooper).postDelayed({
                bluetoothBtn.setImageResource(
                    if (adapter.isEnabled) R.drawable.ic_bluetooth else R.drawable.ic_bluetooth_off
                )
            }, 800)
            return
        } catch (e: SecurityException) {
            // مش عندنا BLUETOOTH_CONNECT — نجرب Root/Shizuku
        }

        // محاولة 2: Root
        val root = RootManager.getInstance(this)
        if (root.isAvailable) {
            val cmd = if (targetEnabled) "svc bluetooth enable" else "svc bluetooth disable"
            root.runShell(cmd) {
                android.os.Handler(mainLooper).postDelayed({
                    bluetoothBtn.setImageResource(
                        if (adapter.isEnabled) R.drawable.ic_bluetooth else R.drawable.ic_bluetooth_off
                    )
                }, 800)
            }
            return
        }

        // محاولة 3: Shizuku
        val shizuku = ShizukoManager.getInstance(this)
        if (shizuku.hasPermission) {
            val cmd = if (targetEnabled) "svc bluetooth enable" else "svc bluetooth disable"
            shizuku.runShell(cmd) {}
            android.os.Handler(mainLooper).postDelayed({
                bluetoothBtn.setImageResource(
                    if (adapter.isEnabled) R.drawable.ic_bluetooth else R.drawable.ic_bluetooth_off
                )
            }, 800)
            return
        }

        // Fallback: فتح إعدادات Bluetooth (بدل system panel مزعج!)
        openBluetoothSettings()
    }

    /**
     * Toggle WiFi مباشرة بدون فتح Settings.Panel (اللي كان ينزل وعلى الشاشة):
     * - Android < 10: setEnabled مباشرة
     * - Android 10+: Root أو Shizuku → "svc wifi enable/disable"
     * - Fallback: Settings.ACTION_WIFI_SETTINGS (صفحة إعدادات عادية، مش panel مزعج)
     */
    @Suppress("DEPRECATION")
    private fun toggleWifiDirect() {
        val targetEnabled = !wifiManager.isWifiEnabled

        // Android 9 وأقل — setEnabled مباشر لا يزال يشتغل
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            wifiManager.isWifiEnabled = targetEnabled
            wifiBtn.setImageResource(
                if (targetEnabled) R.drawable.ic_wifi_on else R.drawable.ic_wifi_off
            )
            return
        }

        // Android 10+: نحتاج Root أو Shizuku
        val cmd = if (targetEnabled) "svc wifi enable" else "svc wifi disable"

        val root = RootManager.getInstance(this)
        if (root.isAvailable) {
            wifiBtn.setImageResource(
                if (targetEnabled) R.drawable.ic_wifi_on else R.drawable.ic_wifi_off
            )
            root.runShell(cmd) {
                android.os.Handler(mainLooper).postDelayed({
                    wifiBtn.setImageResource(
                        if (wifiManager.isWifiEnabled) R.drawable.ic_wifi_on else R.drawable.ic_wifi_off
                    )
                }, 1000)
            }
            return
        }

        val shizuku = ShizukoManager.getInstance(this)
        if (shizuku.hasPermission) {
            wifiBtn.setImageResource(
                if (targetEnabled) R.drawable.ic_wifi_on else R.drawable.ic_wifi_off
            )
            shizuku.runShell(cmd) {}
            android.os.Handler(mainLooper).postDelayed({
                wifiBtn.setImageResource(
                    if (wifiManager.isWifiEnabled) R.drawable.ic_wifi_on else R.drawable.ic_wifi_off
                )
            }, 1000)
            return
        }

        // Fallback نظيف: فتح صفحة WiFi العادية (مش الـ panel المزعج!)
        launchApp("freeform", null, Intent(Settings.ACTION_WIFI_SETTINGS))
    }

    /** يرجع true لو نقطة اللمسة تقع فوق الـ view المعطى (بالإحداثيات المطلقة للشاشة) */
    private fun isTouchOnView(view: View, event: android.view.MotionEvent): Boolean {
        if (view.visibility != View.VISIBLE) return false
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return event.rawX >= loc[0] && event.rawX <= loc[0] + view.width &&
               event.rawY >= loc[1] && event.rawY <= loc[1] + view.height
    }

    private fun showPowerMenu() {
        if (powerMenuVisible) return
        // ✅ FIX: ignore reopen if power menu was just closed by outside-touch
        val sinceClose = System.currentTimeMillis() - powerMenuLastClosedAt
        if (sinceClose < PANEL_DEBOUNCE_MS) return
        powerMenuVisible = true

        powerMenu = LayoutInflater.from(
            ContextThemeWrapper(context, R.style.AppTheme_Dock)
        ).inflate(R.layout.power_menu, null) as LinearLayout
        com.plate.launcher.utils.AppFontScaleUtils.applyToViewHierarchy(powerMenu)

        val powerMenuDockPosition = com.plate.launcher.utils.DockPositionUtils.get(sharedPreferences)
        val powerMenuSatelliteGravity = com.plate.launcher.utils.DockPositionUtils.satelliteGravity(powerMenuDockPosition)
        val layoutParams = Utils.makeWindowParams(Utils.dpToPx(context, 240), -2, context, secondary)
        layoutParams.flags = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
        layoutParams.gravity = powerMenuSatelliteGravity or Gravity.START
        layoutParams.x = Utils.dpToPx(context, 8)
        layoutParams.y = dockHeight + Utils.dpToPx(context, 8)

        powerMenu!!.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) hidePowerMenu()
            false
        }

        // ── Power option click listeners ──────────────────────────────────────
        powerMenu!!.findViewById<android.view.View>(R.id.item_settings)?.setOnClickListener {
            hidePowerMenu()
            launchApp("standard", null,
                android.content.Intent(this, com.plate.launcher.activities.MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        or android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                        or android.content.Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT))
        }
        powerMenu!!.findViewById<android.view.View>(R.id.item_lock)?.setOnClickListener {
            hidePowerMenu(); lockScreen()
        }
        powerMenu!!.findViewById<android.view.View>(R.id.item_power_off)?.setOnClickListener {
            hidePowerMenu(); DeviceUtils.shutdown(context)
        }
        powerMenu!!.findViewById<android.view.View>(R.id.item_restart)?.setOnClickListener {
            hidePowerMenu(); DeviceUtils.reboot(context)
        }
        powerMenu!!.findViewById<android.view.View>(R.id.item_close_youki)?.setOnClickListener {
            hidePowerMenu()
            com.plate.launcher.App.cancelRestartAlarm(context)
            com.plate.launcher.App.intentionalShutdown = true
            restorePhoneDisplaySettings()
            // Previously broken ("إغلاق التطبيق حرفيًا مازال يبقى يشتغل" — Close DEX left the
            // process alive): disableSelf() (the previous fix here) only
            // tears down THIS AccessibilityService (DockService itself) —
            // it does nothing to NotificationService (a separate
            // system-bound NotificationListenerService), DockTileService,
            // any open Activity (MainActivity/LauncherActivity/etc still on
            // the back stack), or any other component sharing this same
            // process. All of those keep running untouched, which is
            // exactly "close" not actually closing. There's also no
            // version of "gracefully stop every one of those individually"
            // that's actually reliable — Android gives no API to force-stop
            // arbitrary in-process Services/Activities from inside the
            // process itself the way "am force-stop" does from outside it.
            //
            // The one thing that's actually guaranteed to leave nothing
            // running: killProcess() on the whole process, unconditionally.
            //
            // FIX #2 (this still didn't work even with killProcess() posted
            // via dockHandler.postDelayed()): disableSelf() can start tearing
            // this Service down essentially immediately — its Looper/message
            // queue is not guaranteed to still be servicing posted callbacks
            // by the time a delayed one would fire, so the killProcess()
            // call could silently never run at all. Using a plain
            // Thread.sleep on a background thread instead of a Handler
            // callback doesn't depend on this Service's own Looper surviving
            // disableSelf() at all — it only needs the process itself to
            // still be alive, which killProcess() guarantees regardless.
            disableSelf()
            Thread {
                try { Thread.sleep(400) } catch (e: InterruptedException) {}
                try { android.os.Process.killProcess(android.os.Process.myPid()) } catch (e: Exception) {}
            }.start()
        }
        powerMenu!!.findViewById<android.view.View>(R.id.item_restart_youki)?.setOnClickListener {
            hidePowerMenu()
            restorePhoneDisplaySettings()
            dockHandler.postDelayed({
                // FIX: was launching MainActivity (the settings screen — same
                // Activity as the item_settings button above, a copy-paste
                // mistake) instead of LauncherActivity, so "restart Youki"
                // dropped the user into Settings instead of the actual
                // desktop/dock launcher.
                val restartIntent = android.content.Intent(context, com.plate.launcher.activities.LauncherActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                val pending = android.app.PendingIntent.getActivity(
                    context, 11111, restartIntent,
                    android.app.PendingIntent.FLAG_ONE_SHOT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                (context.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager)
                    .set(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        android.os.SystemClock.elapsedRealtime() + 1200, pending)
                try { disableSelf() } catch (e: Exception) {}
                stopSelf()
                dockHandler.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 600)
            }, 800)
        }

        // ── Color theming ─────────────────────────────────────────────────────
        ColorUtils.applyMainColor(context, sharedPreferences, powerMenu!!)
        val isDockLight = ColorUtils.isDockColorLight(sharedPreferences, context)
        val textColor   = if (isDockLight) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        for (id in listOf(R.id.item_settings, R.id.item_lock, R.id.item_close_youki,
                          R.id.item_restart_youki, R.id.item_power_off, R.id.item_restart)) {
            val row = powerMenu!!.findViewById<android.view.ViewGroup?>(id) ?: continue
            for (i in 0 until row.childCount) {
                val child = row.getChildAt(i)
                if (child is android.widget.TextView) {
                    if (id != R.id.item_power_off) child.setTextColor(textColor)
                    else if (isDockLight) child.setTextColor(android.graphics.Color.parseColor("#CC0000"))
                }
                if (child is android.widget.ImageView)
                    child.setColorFilter(textColor, android.graphics.PorterDuff.Mode.SRC_ATOP)
            }
        }

        // ── Users section: current user header ───────────────────────────────
        val pmAvatar      = powerMenu!!.findViewById<android.widget.ImageView?>(R.id.pm_avatar)
        val pmUserName    = powerMenu!!.findViewById<android.widget.TextView?>(R.id.pm_user_name)
        val pmUserId      = powerMenu!!.findViewById<android.widget.TextView?>(R.id.pm_user_id)
        val pmOtherUsers  = powerMenu!!.findViewById<android.widget.LinearLayout?>(R.id.pm_other_users)

        // Apply text color to current user header
        pmUserName?.setTextColor(textColor)
        pmUserId?.setTextColor(if (isDockLight) 0xCC000000.toInt() else 0x66FFFFFF.toInt())

        // Load current user name — unified source (see OnboardingPrefs.getDisplayName)
        val displayName = com.plate.launcher.utils.OnboardingPrefs.getDisplayName(context)
        pmUserName?.text = if (!displayName.isNullOrEmpty()) displayName else "User"
        pmUserId?.text = "ID: ${com.plate.launcher.utils.MultiUserManager.getCurrentUserId()}"

        // Load current user avatar — routed through AvatarDisplay (see its
        // kdoc) so a GIF avatar shows animated + circular here too, exactly
        // like every other avatar surface, instead of falling back to the
        // frozen/uncropped static bitmap this used to load directly.
        if (pmAvatar != null) {
            val currentUserId = com.plate.launcher.utils.MultiUserManager.getCurrentUserId()
            pmAvatar.imageTintList = null
            val shown = com.plate.launcher.utils.AvatarDisplay.showOn(pmAvatar, context, currentUserId)
            if (!shown) {
                val icon: android.graphics.Bitmap? = run {
                    val uri = sharedPreferences.getString("user_icon_uri", "default")
                    if (uri != "default") {
                        try { Utils.getCircularBitmap(Utils.getBitmapFromUri(context, Uri.parse(uri))) }
                        catch (e: Exception) { null }
                    } else null
                } ?: try { DeviceUtils.getUserIcon(context) } catch (e: Exception) { null }
                if (icon != null) pmAvatar.setImageBitmap(icon)
            }
        }

        // ── Users list: other users loaded async ──────────────────────────────
        if (pmOtherUsers != null) {
            com.plate.launcher.utils.MultiUserManager.listUsers(context) { users ->
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (powerMenu == null) return@post   // menu closed before callback
                    val currentId = com.plate.launcher.utils.MultiUserManager.getCurrentUserId()
                    val others = users.filter { it.id != currentId }
                    pmOtherUsers.removeAllViews()
                    if (others.isNotEmpty()) {
                        others.forEach { user ->
                            val row = buildPowerMenuUserRow(user, isDockLight, textColor)
                            pmOtherUsers.addView(row)
                        }
                        pmOtherUsers.visibility = android.view.View.VISIBLE
                    }
                    // else: stays GONE (set in XML) — no gap 🎯
                }
            }
        }

        // Animation direction matches the edge the power menu is anchored to.
        val powerMenuTransDist = Utils.dpToPx(context, 16).toFloat()
        powerMenu!!.alpha = 0f
        powerMenu!!.translationY = if (powerMenuDockPosition == com.plate.launcher.utils.DockPositionUtils.Position.TOP)
            -powerMenuTransDist else powerMenuTransDist
        safeAddView(powerMenu!!, layoutParams)

        // Animate: slide in from the dock's edge + fade in
        powerMenu!!.animate().translationY(0f).alpha(1f).setDuration(180).start()
    }

    /** Builds a compact user row for the power menu (other users). */
    private fun buildPowerMenuUserRow(
        user: com.plate.launcher.utils.MultiUserManager.YoukiUser,
        isDockLight: Boolean,
        textColor: Int
    ): android.widget.LinearLayout {
        val dp = { n: Int -> Utils.dpToPx(context, n) }
        val row = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(40))
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(10), 0)
            isClickable = true
            isFocusable = true
            background = android.util.TypedValue().let { tv ->
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
                    .also { _ -> setBackgroundResource(tv.resourceId) }
            }
        }

        // Avatar (28dp)
        val avatarIv = android.widget.ImageView(context).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(28), dp(28)).also {
                it.marginEnd = dp(10)
            }
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.ic_user)
            if (!isDockLight) imageTintList =
                android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
        }
        // Load avatar — routed through AvatarDisplay so a GIF avatar shows
        // animated + circular here too (see its kdoc); this row previously
        // called MultiUserManager.loadUserAvatar() directly (static-only)
        // and wrapped it in RoundedBitmapDrawableFactory, which is the same
        // bypass as the other avatar surfaces fixed alongside this one.
        try {
            val shown = com.plate.launcher.utils.AvatarDisplay.showOn(avatarIv, context, user.id)
            if (!shown && user.isCurrentUser) {
                val bmp = DeviceUtils.getUserIcon(context)
                if (bmp != null) {
                    val dr = androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
                        .create(context.resources, bmp).also { it.isCircular = true }
                    avatarIv.imageTintList = null
                    avatarIv.setImageDrawable(dr)
                }
            }
        } catch (e: Exception) {}

        // Name
        val nameTv = android.widget.TextView(context).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            text = user.name
            setTextColor(textColor)
            textSize = 13f
            alpha = 0.85f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        row.addView(avatarIv)
        row.addView(nameTv)

        row.setOnClickListener {
            hidePowerMenu()
            com.plate.launcher.utils.MultiUserManager.switchToUser(context, user.id) { _, msg ->
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
        return row
    }

    private fun hidePowerMenu() {
        if (!powerMenuVisible || powerMenu == null) return
        powerMenuVisible = false
        powerMenuLastClosedAt = System.currentTimeMillis()
        val menuToRemove = powerMenu
        powerMenu = null
        // ✅ ANIM: انيميشن إغلاق — اتجاه يتبع موضع الدوك (نفس منطق showPowerMenu)
        menuToRemove?.animate()?.setListener(null)?.cancel()
        val exitDist = Utils.dpToPx(context, 10).toFloat()
        val exitPos = com.plate.launcher.utils.DockPositionUtils.get(sharedPreferences)
        val exitAnim = menuToRemove?.animate()
            ?.alpha(0f)
            ?.setDuration(140)
            ?.setInterpolator(android.view.animation.AccelerateInterpolator(2f))
            ?.withEndAction { try { windowManager.removeView(menuToRemove) } catch (e: Exception) {} }
        if (exitPos == com.plate.launcher.utils.DockPositionUtils.Position.TOP)
            exitAnim?.translationY(exitDist)
        else
            exitAnim?.translationY(-exitDist)
        exitAnim?.start()
    }

    /**
     * Restores the phone display settings (resolution/density) back to
     * their system defaults when YoukiDex is closed or restarted.
     */
    private fun restorePhoneDisplaySettings() {
        try {
            val display = (context.getSystemService(android.content.Context.WINDOW_SERVICE)
                    as? android.view.WindowManager)?.defaultDisplay
            if (display != null) {
                try {
                    val serviceManager = Class.forName("android.os.ServiceManager")
                    val service = serviceManager.getMethod("getService", String::class.java)
                        .invoke(null, "window")
                    val stub = Class.forName("android.view.IWindowManager\$Stub")
                    val iwm = stub.getMethod("asInterface", android.os.IBinder::class.java)
                        .invoke(null, service)
                    iwm?.javaClass?.getMethod("clearForcedDisplaySize", Int::class.java)
                        ?.invoke(iwm, display.displayId)
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {}
    }


    /** Returns bubble color with user alpha — delegates to ColorUtils */
    private fun getBubbleColor(): Int = ColorUtils.getBubbleColor(sharedPreferences, this)

    /** Returns independent app drawer background opacity (0-255, default 230) */
    private fun getAppMenuAlpha(): Int =
        (sharedPreferences.getString("app_menu_opacity", "230")?.toIntOrNull() ?: 230).coerceIn(0, 255)

    /** Applies dynamic bubble color to all dock buttons — single pass, no lag */
    private fun applyBubbleColors() {
        val color = getBubbleColor()
        val alpha = android.graphics.Color.alpha(color)
        // Individual icon tinting (these no longer have their own backgrounds)
        for (btn in listOf(bluetoothBtn, wifiBtn, volumeBtn, appsBtn, userBtn)) {
            btn.background?.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_ATOP)
            btn.background?.alpha = alpha
        }
        notificationBtn.background?.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_ATOP)
        notificationBtn.background?.alpha = alpha
        resourceMonitorTv?.background?.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_ATOP)
        resourceMonitorTv?.background?.alpha = alpha
        dateTv.background?.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_ATOP)
        dateTv.background?.alpha = alpha

        // FIX: Color the merged pill groups (btn_bubble_rect) instead of individual buttons
        val pillGroups = mutableListOf<View>(actionBtnsGroup, navBtnsGroup, statusArea)
        if (::pinPill.isInitialized) pillGroups.add(pinPill)
        for (group in pillGroups) {
            group.background?.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_ATOP)
            group.background?.alpha = alpha
        }

        // FIX: Color user chip pill in apps menu — same bubble color as dock
        appMenu.findViewById<android.view.View?>(R.id.user_chip_container)?.let {
            it.background?.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_ATOP)
            it.background?.alpha = alpha
        }

        // App drawer background (appMenu) — independent opacity
        appMenu.background?.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_ATOP)
        appMenu.background?.alpha = getAppMenuAlpha()

        // App dock background (icons island) — standardized to unified bubble color & master opacity
        if (::centerGroup.isInitialized) {
            val enableAppDockBg = sharedPreferences.getBoolean("enable_app_dock_bg", true)
            if (enableAppDockBg) {
                if (centerGroup.background == null) {
                    centerGroup.background = ContextCompat.getDrawable(context, R.drawable.btn_bubble_rect)?.mutate()
                }
                val padH = Utils.dpToPx(context, 6)
                val padV = Utils.dpToPx(context, 2)
                centerGroup.setPadding(padH, padV, padH, padV)

                centerGroup.background?.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_ATOP)
                centerGroup.background?.alpha = alpha
            } else {
                centerGroup.background = null
                centerGroup.setPadding(0, 0, 0, 0)
            }
        }

        // مزامنة فورية مع NotificationService
        PerfectServer.syncColors(sharedPreferences, this)
    }

    /** يُستدعى من PerfectServer لتطبيق لون الفقاعة على زر الإشعارات في الدوك */
    internal fun applyNotifBtnColor(color: Int) {
        notificationBtn.background?.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_ATOP)
        notificationBtn.background?.alpha = 255
    }

    fun applyTheme() {
        // Reset dock background drawable so setColorFilter starts clean (no stale tint)
        val isDockRound = sharedPreferences.getBoolean("round_dock", false)
        dockLayout.setBackgroundResource(if (isDockRound) R.drawable.round_rect else R.drawable.rect)
        appMenu.setBackgroundResource(if (isDockRound) R.drawable.round_rect else R.drawable.rect)

        // Sync floating/fullscreen window position whenever theme is applied
        updateDockShape()

        // Pass 'this' (the live Service context) NOT the cached 'context' field —
        // DynamicColors.wrapContextIfAvailable needs a fresh context to pick up new Material You colors
        ColorUtils.applyMainColor(this, sharedPreferences, dockLayout)

        // 1.15: Override dock background alpha if user enabled it
        if (sharedPreferences.getBoolean("override_dock_background_alpha", false)) {
            val alpha = sharedPreferences.getString("dock_background_alpha", "255")?.toIntOrNull() ?: 255
            dockLayout.background?.alpha = alpha
        }
        // ✅ قائمة التطبيقات (App Drawer) تتبع لون الفقاعة الموحد بشفافية مستقلة
        val bubbleColor = ColorUtils.getBubbleColor(sharedPreferences, this)
        appMenu.background?.setColorFilter(bubbleColor, android.graphics.PorterDuff.Mode.SRC_ATOP)
        appMenu.background?.alpha = getAppMenuAlpha()
        ColorUtils.applySecondaryColor(this, sharedPreferences, searchEntry)

        // ── Apply custom font (Plugins → خط مخصص) ─────────────────────────────
        com.plate.launcher.utils.FontManager.applyIfSet(context, dockLayout)
        com.plate.launcher.utils.FontManager.applyIfSet(context, appMenu)

        // أيقونات الأزرار بيضاء في XML — لا نحتاج clearColorFilter (applyBubbleColors تغطي الكل)
        notificationBtn.setTextColor(android.graphics.Color.WHITE)

        // زر Apps (Windows 11 logo) — لون Material You أو أبيض
        val winLogoColor = if (sharedPreferences.getString("theme", "material_u") == "material_u"
            && com.google.android.material.color.DynamicColors.isDynamicColorAvailable()) {
            ColorUtils.getThemeColors(this, false)[0]
        } else {
            android.graphics.Color.WHITE
        }
        appsBtn.setColorFilter(winLogoColor, android.graphics.PorterDuff.Mode.SRC_ATOP)
        appsBtnCenter.setColorFilter(winLogoColor, android.graphics.PorterDuff.Mode.SRC_ATOP)

        // تطبيق لون الفقاعة على الكل دفعة واحدة
        applyBubbleColors()
    }





    private fun updateCorners() {
        topRightCorner.visibility = if (sharedPreferences.getBoolean(
                "enable_corner_top_right",
                false
            )
        ) View.VISIBLE else View.GONE
        bottomRightCorner.visibility = if (sharedPreferences.getBoolean(
                "enable_corner_bottom_right",
                false
            )
        ) View.VISIBLE else View.GONE
    }

    private fun updateMenuIcon() {
        val iconUri = sharedPreferences.getString("menu_icon_uri", "default")
        if (iconUri == "default") appsBtn.setImageResource(R.drawable.ic_apps_menu) else {
            try {
                val icon = iconUri?.toUri()
                if (icon != null)
                    appsBtn.setImageURI(icon)
            } catch (e: Exception) {
            }
        }
    }

    private fun updateBatteryBtn() {
        // 🔋 Battery is inside status_area bubble — no separate background needed
        batteryBtn.background = null
        batteryBtn.setTextColor(android.graphics.Color.WHITE)
        batteryReceiver.showLevel = true
        batteryBtn.text = "${batteryReceiver.level}%"
    }

    private fun toggleFavorites(visible: Boolean) {
        favoritesGv.visibility = if (visible) View.VISIBLE else View.GONE
        appsSeparator.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun loadFavoriteApps() {
        // ✅ PERF: disk read + icon load off Main thread
        serviceScope.launch(Dispatchers.IO) {
            val apps = AppUtils.getPinnedApps(context, AppUtils.PINNED_LIST)
            withContext(Dispatchers.Main) {
                toggleFavorites(apps.isNotEmpty())
                val menuFullscreen = sharedPreferences.getBoolean("app_menu_fullscreen", false)
                val phoneLayout = sharedPreferences.getInt("dock_layout", -1) == 0
                favoritesGv.adapter =
                    AppAdapter(context, apps, this@DockService, menuFullscreen && !phoneLayout, iconPackUtils)
            }
        }
    }

    fun takeScreenshot() {
        if (Build.VERSION.SDK_INT >= 28)
            performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        else
            DeviceUtils.sendKeyEvent(KeyEvent.KEYCODE_SYSRQ)
    }

    private fun lockScreen() {
        if (Build.VERSION.SDK_INT >= 28)
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        else
            DeviceUtils.lockScreen(context)
    }

    private fun updateHandlePositionValues() {
        // The dock handle (its show/collapse toggle) follows dock_position
        // — it sits on the same screen edge the dock itself docks to.
        // "handle_position" (start/end) controls its secondary alignment
        // along that edge (left-vs-right for a top/bottom dock).
        val dockPosition = com.plate.launcher.utils.DockPositionUtils.get(sharedPreferences)
        val secondaryStart = sharedPreferences.getString("handle_position", "start") == "start"
        handleLayoutParams.gravity = if (dockPosition == com.plate.launcher.utils.DockPositionUtils.Position.TOP)
            Gravity.TOP or if (secondaryStart) Gravity.START else Gravity.END
        else
            Gravity.BOTTOM or if (secondaryStart) Gravity.START else Gravity.END
        if (!secondaryStart) {
            dockHandle.setBackgroundResource(R.drawable.dock_handle_bg_end)
            dockHandle.setCompoundDrawablesRelativeWithIntrinsicBounds(
                R.drawable.ic_expand_left,
                0,
                0,
                0
            )
        } else {
            dockHandle.setBackgroundResource(R.drawable.dock_handle_bg_start)
            dockHandle.setCompoundDrawablesRelativeWithIntrinsicBounds(
                R.drawable.ic_expand_right,
                0,
                0,
                0
            )
        }
    }

    private fun updateHandlePosition() {
        updateHandlePositionValues()
        safeUpdateViewLayout(dockHandle, handleLayoutParams)
    }

    private fun toggleNotificationPanel(show: Boolean) {
        sendBroadcast(
            Intent(DOCK_SERVICE_ACTION)
                .setPackage(packageName)
                .putExtra(
                    "action",
                    if (show) ACTION_SHOW_NOTIFICATION_PANEL else ACTION_HIDE_NOTIFICATION_PANEL
                )
        )
    }

    override fun onTouch(view: View, motionEvent: MotionEvent): Boolean {
        // Close QS panel if open when dock is touched
        if (motionEvent.action == MotionEvent.ACTION_DOWN && qsPanelVisible) {
            toggleQsPanel()
        }
        gestureDetector.onTouchEvent(motionEvent)
        return false
    }

    // ── Freeform Windowing ───────────────────────────────────────────────────
    // Enables freeform (floating windows) at system level via Shizuku.
    // Without this, Android ignores WINDOWING_MODE_FREEFORM requests in phone mode.
    private fun enableFreeformWindowing() {
        // HarmonyOS 5+ has no Android freeform support — the settings key doesn't exist
        // and writing to it crashes or gets silently rejected. Skip entirely.
        if (DeviceUtils.isPureHarmonyOS()) {
            android.util.Log.d("DockService", "HarmonyOS detected — skipping freeform windowing setup")
            return
        }
        val shizuku = com.plate.launcher.utils.ShizukoManager.getInstance(context)
        serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                if (shizuku.hasPermission) {
                    // FIX #14: Check current value before writing to avoid redundant DB writes on every service start
                    val current = android.provider.Settings.Global.getInt(contentResolver, "enable_freeform_support", 0)
                    if (current != 1)
                        shizuku.runShell("settings put global enable_freeform_support 1") {}
                    // Force desktop mode — makes freeform work in phone layout too
                    shizuku.runShell("settings put global force_desktop_mode_on_external_displays 0") {}
                    // Android 12+: additional freeform flag
                    if (Build.VERSION.SDK_INT >= 31) {
                        shizuku.runShell("wm set-multi-window-config --freeformWindowManagement true") {}
                    }
                    // FIX: Desktop Windowing Mode was gated to `== 35` (Android
                    // 15 only) — without these commands, apps open in freeform
                    // WITHOUT a caption bar (title bar + close/resize buttons),
                    // making the freeform window act like fullscreen with no
                    // way to actually drag/move it (resize still works, since
                    // that goes through `am task resize` directly — see
                    // AppUtils.resizeTask — independent of the caption bar).
                    // User report: BLU M10L Pro, Android 12/13, confirms this
                    // — window resize worked but window movement was
                    // completely broken, a regression from an older version
                    // that (going by user reports) had working movement on
                    // this same class of device. `== 35` meant this fix,
                    // written for Android 15's desktop windowing rollout,
                    // silently never ran on the vast majority of real freeform
                    // devices still on 12/13/14. Widened to match
                    // --freeformWindowManagement's own `>= 31` threshold right
                    // above — these `wm` commands already fail silently
                    // (empty {} callback, no exception surfaced) on any
                    // OEM/API combination that doesn't recognize them, so
                    // broadening this is a pure upside: helps every device
                    // where the flags exist and does nothing where they don't.
                    if (Build.VERSION.SDK_INT >= 31) {
                        shizuku.runShell("wm set-multi-window-config --supportsDesktopWindowing true") {}
                        shizuku.runShell("wm set-multi-window-config --enableDesktopMode true") {}
                    }
                    android.util.Log.d("DockService", "Freeform windowing enabled ✅")
                } else if (com.plate.launcher.utils.DeviceUtils.hasWriteSettingsPermission(context)) {
                    // Fallback: WRITE_SECURE_SETTINGS direct
                    android.provider.Settings.Global.putInt(contentResolver, "enable_freeform_support", 1)
                    android.util.Log.d("DockService", "Freeform enabled via WRITE_SECURE_SETTINGS ✅")
                }
            } catch (e: Exception) {
                android.util.Log.e("DockService", "enableFreeformWindowing error: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel() // Cancel all running coroutines to prevent leaks
        castManager?.destroy()
        DeviceUtils.hideStatusBar(this, false)
        // Previously broken (stuck in landscape until Force Stop): freezeRotation(true) is
        // called every time an app is launched with lock_landscape enabled
        // (see launchApp()/toggleAppMenu() call sites), but nothing was ever
        // calling freezeRotation(false) — that system-level rotation lock
        // (via IWindowManager.freezeRotation, independent of any single
        // Activity) stayed in effect even after this service died, whether
        // from a crash or the user force-stopping it any other way. The
        // device was left rotation-locked at the OS level with no normal way
        // back to portrait until App Info > Force stop cleared the process
        // state entirely. Thawing it here guarantees normal system rotation
        // is restored whenever this service goes away, for any reason.
        DeviceUtils.freezeRotation(false)
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        // FIX: إلغاء تسجيل DisplayListener عشان ما يصير memory leak
        displayListener?.let {
            (getSystemService(DISPLAY_SERVICE) as? DisplayManager)?.unregisterDisplayListener(it)
        }
        stopSecondaryDisplayWallpaper()
        stopCursorOverlay()
        // Unregister all receivers
        try { launcherReceiver?.let { unregisterReceiver(it) } } catch (e: Exception) {}
        try { dockActionReceiver?.let { unregisterReceiver(it) } } catch (e: Exception) {}
        try { notificationServiceReceiver?.let { unregisterReceiver(it) } } catch (e: Exception) {}
        try { wallpaperReceiver?.let { unregisterReceiver(it) } } catch (e: Exception) {}
        try { packageReceiver?.let { unregisterReceiver(it) } } catch (e: Exception) {}
        com.plate.launcher.server.ShellManager.destroy()
        if (::batteryReceiver.isInitialized)
            try { unregisterReceiver(batteryReceiver) } catch (e: Exception) {}
        if (::soundEventsReceiver.isInitialized)
            try { unregisterReceiver(soundEventsReceiver) } catch (e: Exception) {}
        stopResourceMonitor()
        // Fix for Lifecycle: Remove all tracked popup windows first so nothing
        // outlives the service. Then remove core windows in order.
        cleanupAllPopups()
        qsPanel?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }
        powerMenu?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }
        // Previously broken ("إغلاق الديكس ما بيقتل كل العمليات" / overlay stays up): appMenu
        // is its own top-level window (windowManager.addView at showAppMenu),
        // separate from dock/powerMenu/qsPanel — it was never in
        // this cleanup list at all. If the App Menu happened to be open when
        // Close DEX was tapped, that window had nothing left owning it (the
        // whole service was gone) but was never actually removed from
        // WindowManager, so it just sat there on screen indefinitely — the
        // exact "overlay keeps running after Close DEX" symptom. Guarded with
        // isInitialized since appMenu is lateinit and may never have been
        // touched this session (menu never opened).
        if (::appMenu.isInitialized) {
            try { windowManager.removeView(appMenu) } catch (e: Exception) {}
        }
        try { windowManager.removeView(dockHandle) } catch (e: Exception) {}
        try { windowManager.removeView(topRightCorner) } catch (e: Exception) {}
        try { windowManager.removeView(bottomRightCorner) } catch (e: Exception) {}
        timePillView?.let { v -> if (v.windowToken != null) try { windowManager.removeView(v) } catch (e: Exception) {} }
        batteryPillView?.let { v -> if (v.windowToken != null) try { windowManager.removeView(v) } catch (e: Exception) {} }
        try { timeTickReceiver?.let { unregisterReceiver(it) } } catch (e: Exception) {}
        timeTickReceiver = null
        try { windowManager.removeView(dock) } catch (e: Exception) {}
        PerfectServer.dock = null  // ✅ أزل المرجع
        super.onDestroy()
    }

    fun performNavAction(key: String) {
        val action = sharedPreferences.getString("${key}_long_action", "none")
        when (action) {
            NAV_LONG_ACTIONS[0] -> return
            NAV_LONG_ACTIONS[1] -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            NAV_LONG_ACTIONS[2] -> launchAssistant()
            NAV_LONG_ACTIONS[3] -> lockScreen()
            NAV_LONG_ACTIONS[4] -> performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
        }
    }

    private fun toggleQsPanel() {
        //  Prevent repetition during animation — this is the cause of stacking
        if (qsPanelAnimating) return

        if (qsPanelVisible) {
            val panelToClose = qsPanel ?: run {
                qsPanelVisible = false
                qsPanelAnimating = false
                return
            }
            qsPanelVisible = false
            qsPanelAnimating = true
            qsPanelLastClosedAt = System.currentTimeMillis()
            panelToClose.animate().cancel()
            panelToClose.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            panelToClose.pivotX = panelToClose.width.toFloat()
            panelToClose.pivotY = panelToClose.height.toFloat()
            val closeDockPosition = com.plate.launcher.utils.DockPositionUtils.get(sharedPreferences)
            val closeTransPx = com.plate.launcher.utils.Utils.dpToPx(context, 8).toFloat()
            val closeAnimator = panelToClose.animate()
                .scaleX(0.92f).scaleY(0.92f).alpha(0f)
                .setDuration(200)
                .setInterpolator(interpExit)
            if (closeDockPosition == com.plate.launcher.utils.DockPositionUtils.Position.TOP)
                closeAnimator.translationY(-closeTransPx)
            else
                closeAnimator.translationY(closeTransPx)
            closeAnimator
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        try { windowManager.removeView(panelToClose) } catch (e: Exception) {}
                        qsPanel = null; qsPanelAnimating = false
                    }
                    override fun onAnimationCancel(animation: android.animation.Animator) {
                        try { windowManager.removeView(panelToClose) } catch (e: Exception) {}
                        qsPanel = null; qsPanelAnimating = false
                    }
                }).start()
            return
        }
        //  set flags immediately before any async work
        // ✅ FIX: ignore reopen if panel was just closed by outside-touch
        val sinceQsClose = System.currentTimeMillis() - qsPanelLastClosedAt
        if (sinceQsClose < PANEL_DEBOUNCE_MS) { qsPanelAnimating = false; return }
        qsPanelVisible = true
        qsPanelAnimating = true
        val panel = LayoutInflater.from(context).inflate(R.layout.quick_settings_panel, null) as LinearLayout
        qsPanel = panel
        com.plate.launcher.utils.AppFontScaleUtils.applyToViewHierarchy(panel)

        val brightnessSb = panel.findViewById<SeekBar>(R.id.qs_brightness_sb)
        try {
            brightnessSb.progress = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (e: Exception) {}
        brightnessSb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, v: Int, user: Boolean) {
                if (user) Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, v)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Volume slider setup
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val volumeSb = panel.findViewById<SeekBar>(R.id.qs_volume_sb)
        volumeSb.max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        volumeSb.progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        volumeSb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, v: Int, user: Boolean) {
                if (user) audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Both rows — dock mainColor (follows Material You / user theme)
        val qsDockColors  = ColorUtils.getMainColors(sharedPreferences, context)
        val brightnessRow = panel.findViewById<android.view.View>(R.id.qs_brightness_row)
        val volumeRow     = panel.findViewById<android.view.View>(R.id.qs_volume_row)
        panel.background  = null
        for (row in listOf(brightnessRow, volumeRow)) {
            row.background?.setColorFilter(qsDockColors[0], android.graphics.PorterDuff.Mode.SRC_ATOP)
            row.background?.alpha = 255
        }

        // Sliders: Material You accent when material_u, white otherwise
        val isMatU = sharedPreferences.getString("theme", "material_u") == "material_u"
        val sliderColor = if (isMatU && com.google.android.material.color.DynamicColors.isDynamicColorAvailable()) {
            ColorUtils.getThemeColors(context, false)[0] // light colorPrimary — visible on dark bg
        } else {
            android.graphics.Color.WHITE
        }
        for (sb in listOf(brightnessSb, volumeSb)) {
            sb.progressDrawable?.setColorFilter(sliderColor, android.graphics.PorterDuff.Mode.SRC_ATOP)
            sb.thumb?.setColorFilter(sliderColor, android.graphics.PorterDuff.Mode.SRC_ATOP)
        }

        val layoutParams = Utils.makeWindowParams(-2, -2, context, secondary, fitNavInsets = true)
        // Feature request: QS panel keeps its fixed corner (bottom-end) —
        // its *position* never follows dock_position, only its show/hide
        // animation direction does (below), per explicit request: "فقط
        // حركه الانيميشن تبعه ولا تحرك زواياه... خليه في الزاويه الاصليه".
        layoutParams.gravity = Gravity.BOTTOM or Gravity.END
        layoutParams.y = dockHeight + Utils.dpToPx(context, 4)
        layoutParams.x = Utils.dpToPx(context, 8)
        layoutParams.flags = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        panel.setOnTouchListener(null)
        // Animation direction follows dock_position even though the panel's
        // own corner doesn't move — a top dock makes the panel animate in
        // from the top instead of always from the bottom.
        val qsDockPosition = com.plate.launcher.utils.DockPositionUtils.get(sharedPreferences)
        // FIX: set initial state BEFORE addView to prevent 1-frame jump
        val qsPanelTransPx = Utils.dpToPx(context, 10).toFloat()
        panel.scaleX = 0.92f
        panel.scaleY = 0.92f
        panel.alpha = 0f
        panel.translationY = if (qsDockPosition == com.plate.launcher.utils.DockPositionUtils.Position.TOP)
            -qsPanelTransPx else qsPanelTransPx
        panel.pivotX = panel.width.toFloat()
        panel.pivotY = panel.height.toFloat()
        panel.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        safeAddView(panel, layoutParams)
        panel.animate().cancel()
        panel.animate()
            .scaleX(1f).scaleY(1f).alpha(1f).translationX(0f).translationY(0f)
            .setDuration(300)
            .setInterpolator(interpEmphasized)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    panel.setLayerType(View.LAYER_TYPE_NONE, null)
                    qsPanelAnimating = false
                }
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    panel.scaleX = 1f; panel.scaleY = 1f; panel.alpha = 1f; panel.translationY = 0f
                    panel.setLayerType(View.LAYER_TYPE_NONE, null)
                    qsPanelAnimating = false
                }
            }).start()
    }

    private fun getCpuUsage(): Int {
        // Use /proc/stat with fallback for Debug
        return try {
            // FIX #15: use{} ensures file is always closed even on exception
            val line = java.io.RandomAccessFile("/proc/stat", "r").use { it.readLine() }
            val parts = line.trim().split("\\s+".toRegex()).drop(1)
            if (parts.size < 4) return lastCpuValue
            val user    = parts[0].toLong()
            val nice    = parts[1].toLong()
            val system  = parts[2].toLong()
            val idle    = parts[3].toLong()
            val iowait  = parts.getOrNull(4)?.toLong() ?: 0L
            val irq     = parts.getOrNull(5)?.toLong() ?: 0L
            val softirq = parts.getOrNull(6)?.toLong() ?: 0L
            val total   = user + nice + system + idle + iowait + irq + softirq
            val diffTotal = total - prevCpuTotal
            val diffIdle  = (idle + iowait) - prevCpuIdle
            prevCpuTotal  = total
            prevCpuIdle   = idle + iowait
            if (diffTotal <= 0L) lastCpuValue
            else {
                lastCpuValue = ((100L * (diffTotal - diffIdle)) / diffTotal).toInt().coerceIn(0, 100)
                lastCpuValue
            }
        } catch (e: Exception) {
            // Fallback: calculate CPU from pids
            try {
                val pid = android.os.Process.myPid()
                val statFile = java.io.File("/proc/$pid/stat")
                if (statFile.exists()) {
                    val parts = statFile.readText().trim().split("\\s+".toRegex())
                    if (parts.size > 15) {
                        val utime = parts[13].toLong()
                        val stime = parts[14].toLong()
                        val total = utime + stime
                        val diff = (total - prevCpuTotal).coerceAtLeast(0L)
                        prevCpuTotal = total
                        // clock ticks per second ≈ 100; clamp to 0..100
                        lastCpuValue = (diff).toInt().coerceIn(0, 100)
                        lastCpuValue
                    } else lastCpuValue
                } else lastCpuValue
            } catch (e: Exception) { lastCpuValue }
        }
    }

    private fun startResourceMonitor() {
        // 🔧 FIX (Memory/CPU Leak): startResourceMonitor() تُستدعى من مكانين
        // (onCreate عند تفعيل الإعداد مسبقاً، وonSharedPreferenceChanged عند
        // تفعيلها يدوياً). لو استُدعيت مرتين بدون stopResourceMonitor()
        // بينهما — Handler القديم يستمر بعمل postDelayed(this, 2000) للأبد
        // لأن resourceHandler يفقد المرجع القديم ويحتفظ فقط بالجديد.
        // النتيجة: قراءة CPU/RAM مضاعفة كل ثانيتين للأبد + تسريب Handler.
        // الحل: أوقف أي مراقب سابق أولاً قبل بدء واحد جديد.
        stopResourceMonitor()
        val h = Handler(mainLooper)
        resourceHandler = h
        val runnable = object : Runnable {
            override fun run() {
                // Read RAM on main thread normally
                val mi = ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(mi)
                val usedRam = (mi.totalMem - mi.availMem) / (1024 * 1024)
                val ramDisplay = if (usedRam >= 1024) "%.1fG".format(usedRam / 1024f) else "${usedRam}M"
                // CPU on background thread
                serviceScope.launch(Dispatchers.IO) {
                    val cpu = getCpuUsage()
                    withContext(Dispatchers.Main) {
                        resourceMonitorTv?.text = "CPU $cpu%  RAM $ramDisplay"
                    }
                }
                h.postDelayed(this, 2000)
            }
        }
        // baseline reads
        serviceScope.launch(Dispatchers.IO) {
            getCpuUsage()
            Thread.sleep(500)
            getCpuUsage()
        }
        h.postDelayed(runnable, 1200)
    }

    private fun stopResourceMonitor() {
        resourceHandler?.removeCallbacksAndMessages(null)
        resourceHandler = null
    }

    inner class HotCornersHoverListener(val key: String) : View.OnHoverListener {
        // PERF/FIX: single Handler instance — old code created a new Handler on every
        // ACTION_HOVER_ENTER, which allocates a Looper reference and accumulates
        // uncancelled callbacks if the pointer moves in/out rapidly (e.g. hovering
        // along the corner edge). A reused handler with removeCallbacks prevents leaks.
        private val handler = Handler(mainLooper)
        private val triggerRunnable = Runnable {
            performNavAction(key)
        }

        override fun onHover(v: View?, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_HOVER_ENTER -> {
                    if (v?.isHovered == true) {
                        val delay = sharedPreferences
                            .getString("hot_corners_delay", "300")
                            ?.toLongOrNull() ?: 300L
                        handler.removeCallbacks(triggerRunnable)
                        handler.postDelayed(triggerRunnable, delay)
                    }
                }
                MotionEvent.ACTION_HOVER_EXIT -> handler.removeCallbacks(triggerRunnable)
            }
            return false
        }
    }
}


// ════════════════════════════════════════════════════════════════
// 2️⃣  NotificationService  —  خدمة الإشعارات (مُعاد تصميمها)
// ════════════════════════════════════════════════════════════════
const val ACTION_HIDE_NOTIFICATION_PANEL = "hide_panel"
const val ACTION_SHOW_NOTIFICATION_PANEL = "show_panel"
const val ACTION_DISMISS_NOTIFICATION_IMMEDIATE = "dismiss_notification_immediate"
const val ACTION_HIDE_NOTIFICATION_BAR = "hide_bar"
const val ACTION_SHOW_NOTIFICATION_BAR = "show_bar"
const val NOTIFICATION_COUNT_CHANGED = "count_changed"
const val NOTIFICATION_SERVICE_ACTION = "notification_service_action"
const val ACTION_STOP_NOTIFICATION_SERVICE = "stop_notification_service"

class NotificationService : NotificationListenerService(), OnNotificationClickListener,
    SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var windowManager: WindowManager
    private lateinit var notificationLayout: LinearLayout
    private lateinit var notificationTitleTv: TextView
    private lateinit var notificationTextTv: TextView
    private lateinit var notificationIconIv: ImageView
    private lateinit var notificationCloseBtn: ImageView
    private lateinit var handler: Handler
    private lateinit var sharedPreferences: SharedPreferences
    private var notificationPanel: View? = null
    private var notificationsLv: RecyclerView? = null
    private var cancelAllBtn: ImageButton? = null
    private lateinit var notificationActionsLayout: LinearLayout
    private lateinit var context: Context
    private var notificationArea: LinearLayout? = null
    private var qsAreaRef: LinearLayout? = null  // ✅ ref للـ qs area للتحديث الفوري
    private var preferLastDisplay = false
    private var dockReceiver: DockServiceReceiver? = null
    private var y = 0
    private var margins = 0
    private var dockHeight: Int = 0
    private lateinit var notificationLayoutParams: WindowManager.LayoutParams
    private var actionsHeight = 0
    override fun onCreate() {
        super.onCreate()
        PerfectServer.notifications = this  // ✅ سجّل نفسك في المنسق
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        sharedPreferences.registerOnSharedPreferenceChangeListener(this)
        preferLastDisplay = sharedPreferences.getBoolean("prefer_last_display", false)
        context = DeviceUtils.getDisplayContext(this, preferLastDisplay)
        actionsHeight = Utils.dpToPx(context, 20)
        windowManager = context.getSystemService(WINDOW_SERVICE) as WindowManager
        notificationLayoutParams = Utils.makeWindowParams(
            Utils.dpToPx(context, 300), LinearLayout.LayoutParams.WRAP_CONTENT, context,
            preferLastDisplay
        )
        // FIX: Add FLAG_NOT_TOUCH_MODAL so this window (which is always present in the
        // WindowManager, even when visibility=GONE) doesn't swallow touch events outside
        // its 300dp bounds. Without this flag, the window acts as a modal overlay and
        // blocks taps on whatever sits behind its bounding rectangle.
        notificationLayoutParams.flags = notificationLayoutParams.flags or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        margins = Utils.dpToPx(context, 2)
        dockHeight =
            Utils.dpToPx(context, sharedPreferences.getString("dock_height", "56")!!.toInt())
        // FIX: when round_dock=true the dock floats 8dp above the screen bottom edge.
        // The notification layout must clear this gap or it renders behind the floating dock.
        val dockFloatMargin = if (sharedPreferences.getBoolean("round_dock", false))
            Utils.dpToPx(context, 8) else 0
        // FIX: this used to assume the dock is always at the bottom
        // ("لما الدوك تحت: الإشعار يظهر فوق الدوك") — for a top dock the
        // notification panel needs to drop down below the dock instead of
        // sitting at the bottom of the screen, far from the dock.
        val notifDockPosition = com.plate.launcher.utils.DockPositionUtils.get(sharedPreferences)
        y = run {
            if (notifDockPosition == com.plate.launcher.utils.DockPositionUtils.Position.TOP) {
                dockHeight + dockFloatMargin + margins
            } else if (DeviceUtils.shouldApplyNavbarFix())
                dockHeight - DeviceUtils.getNavBarHeight(context) + dockFloatMargin + margins
            else
                dockHeight + dockFloatMargin + margins
        }
        notificationLayoutParams.x = margins
        val notifVertGravity = if (notifDockPosition == com.plate.launcher.utils.DockPositionUtils.Position.TOP)
            Gravity.TOP else Gravity.BOTTOM
        notificationLayoutParams.gravity = notifVertGravity or if (sharedPreferences.getInt(
                "dock_layout",
                -1
            ) == 0
        ) Gravity.CENTER_HORIZONTAL else Gravity.END
        notificationLayoutParams.y = y
        notificationLayout = LayoutInflater.from(this).inflate(
            R.layout.notification_entry,
            null
        ) as LinearLayout
        val padding = Utils.dpToPx(context, 10)
        notificationLayout.setPadding(padding, padding, padding, padding)
        notificationLayout.setBackgroundResource(R.drawable.round_square)
        // Apply dock theme color to the popup banner — same as dock
        val initColors = ColorUtils.getMainColors(sharedPreferences, this)
        notificationLayout.background?.setColorFilter(initColors[0], android.graphics.PorterDuff.Mode.SRC_ATOP)
        notificationLayout.background?.alpha = initColors[1]
        notificationLayout.visibility = View.GONE
        notificationTitleTv = notificationLayout.findViewById(R.id.notification_title_tv)
        notificationTextTv = notificationLayout.findViewById(R.id.notification_text_tv)
        notificationIconIv = notificationLayout.findViewById(R.id.notification_icon_iv)
        notificationCloseBtn = notificationLayout.findViewById(R.id.notification_close_btn)
        notificationCloseBtn.alpha = 1f
        notificationActionsLayout =
            notificationLayout.findViewById(R.id.notification_actions_layout)
        // FIX: TYPE_APPLICATION_OVERLAY requires runtime permission check.
        // SYSTEM_ALERT_WINDOW in the manifest is necessary but NOT sufficient —
        // the user must also grant it via Settings. Without this guard, the
        // service crashes with BadTokenException (window type 2038 denied).
        if (Settings.canDrawOverlays(this)) {
            windowManager.addView(notificationLayout, notificationLayoutParams)
        }
        handler = Handler(Looper.getMainLooper())
        notificationLayout.alpha = 0f
        notificationLayout.setOnHoverListener { _, event ->
            if (event.action == MotionEvent.ACTION_HOVER_ENTER) {
                handler.removeCallbacksAndMessages(null)
            } else if (event.action == MotionEvent.ACTION_HOVER_EXIT) {
                hideNotification()
            }
            false
        }

        dockReceiver = DockServiceReceiver()
        ContextCompat.registerReceiver(
            this,
            dockReceiver,
            IntentFilter(DOCK_SERVICE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        updateNotificationCount()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        super.onNotificationRemoved(sbn)
        updateNotificationCount()
        if (Utils.notificationPanelVisible)
            updateNotificationPanel()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        // DEX mode — notifications only active in desktop mode
        if (!sharedPreferences.getBoolean("dex_mode_active", false)) return
        updateNotificationCount()
        if (Utils.notificationPanelVisible) {
            updateNotificationPanel()
        } else {
            if (sharedPreferences.getBoolean("show_notifications", true)) {
                val notification = sbn.notification
                val isForegroundService = (notification.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0
                if ((sbn.isOngoing && !sharedPreferences.getBoolean("show_ongoing", false))
                    || isForegroundService
                    || (sbn.packageName == AppUtils.currentApp && sharedPreferences.getBoolean("silence_current", true))
                    || notification.contentView != null
                    || isBlackListed(sbn.packageName)
                )
                    return
                val extras = notification.extras
                var notificationTitle = extras.getString(Notification.EXTRA_TITLE)
                if (notificationTitle == null) notificationTitle =
                    AppUtils.getPackageLabel(context, sbn.packageName)
                val notificationText = extras.getCharSequence(Notification.EXTRA_TEXT)
                // ✅ Refresh color on each notification — stays in sync with dock theme
                val notifColors = ColorUtils.getMainColors(sharedPreferences, this@NotificationService)
                notificationLayout.background?.setColorFilter(notifColors[0], android.graphics.PorterDuff.Mode.SRC_ATOP)
                notificationLayout.background?.alpha = notifColors[1]
                // ✅ sync bubble count color to DockService too
                PerfectServer.syncBubbleToDoc(sharedPreferences, this@NotificationService)

                if (AppUtils.isMediaNotification(notification) && notification.getLargeIcon() != null) {
                    val padding = Utils.dpToPx(context, 0)
                    notificationIconIv.setPadding(padding, padding, padding, padding)
                    notificationIconIv.setImageIcon(notification.getLargeIcon())
                    notificationIconIv.background = null
                } else {
                    notification.smallIcon.setTint(Color.WHITE)
                    notificationIconIv.setBackgroundResource(R.drawable.circle)
                    ColorUtils.applySecondaryColor(
                        context, sharedPreferences,
                        notificationIconIv
                    )
                    val padding = Utils.dpToPx(context, 14)
                    notificationIconIv.setPadding(padding, padding, padding, padding)
                    notificationIconIv.setImageIcon(notification.smallIcon)
                }

                val progress = extras.getInt(Notification.EXTRA_PROGRESS)
                val p = if (progress != 0) " $progress%" else ""
                notificationTitleTv.text = notificationTitle + p
                notificationTextTv.text = notificationText
                val actions = notification.actions
                notificationActionsLayout.removeAllViews()
                if (actions != null) {
                    val actionLayoutParams = LinearLayout.LayoutParams(0, actionsHeight)
                    actionLayoutParams.weight = 1f
                    if (AppUtils.isMediaNotification(notification)) {
                        for (action in actions) {
                            val actionIv = ImageView(this@NotificationService)
                            try {
                                val resources = packageManager
                                    .getResourcesForApplication(sbn.packageName)
                                val drawable = resources.getDrawable(
                                    resources.getIdentifier(
                                        action.icon.toString() + "",
                                        "drawable",
                                        sbn.packageName
                                    )
                                )
                                drawable.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_ATOP)
                                actionIv.setImageDrawable(drawable)
                                actionIv.setOnClickListener {
                                    try {
                                        action.actionIntent.send()
                                    } catch (e: CanceledException) {
                                    }
                                }
                                notificationTextTv.isSingleLine = true
                                notificationActionsLayout.addView(actionIv, actionLayoutParams)
                            } catch (e: PackageManager.NameNotFoundException) {
                            }
                        }
                    } else {
                        // PERF: cache getMainColors — called once instead of per-action
                        val actionTextColor = ColorUtils.manipulateColor(
                            ColorUtils.getMainColors(sharedPreferences, this)[0], 1.6f
                        )
                        for (action in actions) {
                            val actionTv = TextView(context)
                            actionTv.isSingleLine = true
                            actionTv.text = action.title
                            actionTv.setTextColor(actionTextColor)
                            actionTv.setOnClickListener {
                                try {
                                    action.actionIntent.send()
                                    dismissNotificationImmediate()
                                } catch (e: CanceledException) {
                                }
                            }
                            notificationActionsLayout.addView(actionTv, actionLayoutParams)
                        }
                    }
                }
                notificationCloseBtn.setOnClickListener {
                    dismissNotificationImmediate()
                    if (sbn.isClearable)
                        cancelNotification(sbn.key)
                }
                notificationLayout.setOnClickListener {
                    dismissNotificationImmediate()
                    val intent = notification.contentIntent
                    if (intent != null) {
                        try {
                            val options = makeActivityOptions(
                                context, "standard", dockHeight,
                                Display.DEFAULT_DISPLAY
                            )
                            intent.send(context, 0, null, null, null, null, options.toBundle())
                            if (sbn.isClearable) cancelNotification(sbn.key)
                        } catch (e: CanceledException) {
                        }
                    }
                }
                notificationLayout.setOnLongClickListener {
                    val ignoredApps = buildSet {
                        addAll(sharedPreferences.getStringSet("ignored_notifications_popups", emptySet())!!)
                        add(sbn.packageName)
                    }
                    sharedPreferences.edit {
                        putStringSet("ignored_notifications_popups", ignoredApps)
                    }
                    dismissNotificationImmediate()
                    Toast.makeText(
                        this@NotificationService,
                        R.string.silenced_notifications,
                        Toast.LENGTH_LONG
                    )
                        .show()
                    if (sbn.isClearable) cancelNotification(sbn.key)
                    true
                }
                // PERF/FIX: Cancel previous animation safely.
                // setListener(null) BEFORE cancel() prevents the stale hide-animation's
                // onAnimationEnd from firing and resetting visibility/scale mid-flight,
                // which was the root cause of the "jittery quick-press" glitch.
                notificationLayout.animate().setListener(null).cancel()
                notificationLayout.scaleX = 0.88f
                notificationLayout.scaleY = 0.88f
                notificationLayout.alpha = 0f
                // The window may have been torn down by hideNotification()'s removeView()
                // since the last time this ran — re-add it before making it VISIBLE again.
                if (notificationLayout.windowToken == null) {
                    try {
                        windowManager.addView(notificationLayout, notificationLayoutParams)
                    } catch (e: Exception) {
                    }
                }
                notificationLayout.visibility = View.VISIBLE
                // withLayer() replaces manual LAYER_TYPE_HARDWARE management:
                // it enables GPU compositing for the duration and cleans up
                // automatically on both natural end AND cancellation — no leaks.
                notificationLayout.animate()
                    .scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(240)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .withLayer()
                    .setListener(null)
                if (sharedPreferences.getBoolean(
                        "enable_notification_sound",
                        false
                    )
                ) DeviceUtils.playEventSound(this, "notification_sound")
                hideNotification()
            }
        }
    }

    // Immediate dismiss (click/close-button/silence actions) — no fade animation,
    // just GONE + tear down the window so it doesn't sit registered with
    // WindowManager (and its compositor layer) while nothing is shown.
    private fun dismissNotificationImmediate() {
        notificationLayout.visibility = View.GONE
        notificationLayout.alpha = 0f
        if (notificationLayout.windowToken != null) {
            try {
                windowManager.removeView(notificationLayout)
            } catch (e: Exception) {
            }
        }
    }

    private fun hideNotification() {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            // Clear listener before cancel so it won't fire during the cancel call itself
            notificationLayout.animate().setListener(null).cancel()
            notificationLayout.animate()
                .scaleX(0.88f).scaleY(0.88f).alpha(0f)
                .setDuration(180)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withLayer()
                // FIX: 'cancelled' flag prevents onAnimationEnd from resetting
                // visibility/scale when a new show-animation interrupts this one.
                // Without this, the reset (scaleX=1f, GONE) fires mid-flight and
                // corrupts the incoming animation's start state → jitter/glitch.
                .setListener(object : AnimatorListenerAdapter() {
                    private var cancelled = false
                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                    }
                    override fun onAnimationEnd(animation: Animator) {
                        if (cancelled) return
                        notificationLayout.visibility = View.GONE
                        notificationLayout.scaleX = 1f
                        notificationLayout.scaleY = 1f
                        notificationLayout.alpha = 1f
                        // Fully tear down the overlay window when there's no notification to
                        // show — GONE alone still leaves the window registered with
                        // WindowManager (a live compositor layer) for as long as the service
                        // runs. removeView() actually frees it; the guard below matches the
                        // windowToken != null pattern already used at addView() call sites.
                        if (notificationLayout.windowToken != null) {
                            try {
                                windowManager.removeView(notificationLayout)
                            } catch (e: Exception) {
                            }
                        }
                    }
                })
        }, sharedPreferences.getString("notification_display_time", "5")!!.toInt() * 1000L)
    }

    private fun isBlackListed(packageName: String): Boolean {
        val ignoredPackages =
            sharedPreferences.getStringSet("ignored_notifications_popups", setOf("android"))
        return ignoredPackages!!.contains(packageName)
    }

    private val countHandler = Handler(Looper.getMainLooper())
    private val countRunnable = Runnable { doUpdateNotificationCount() }

    private fun updateNotificationCount() {
        countHandler.removeCallbacks(countRunnable)
        countHandler.postDelayed(countRunnable, 300)
    }

    private fun doUpdateNotificationCount() {
        var count = 0
        var cancelableCount = 0
        val notifications = try {
            activeNotifications
        } catch (e: SecurityException) {
            // Service token may be invalid — happens when the listener is not
            // yet fully bound or loses its token mid-flight. Skip silently.
            return
        } ?: return
        for (notification in notifications) {
            if (notification != null && notification.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0) {
                count++
                if (notification.isClearable) cancelableCount++
            }
            if (Utils.notificationPanelVisible) cancelAllBtn?.visibility =
                if (cancelableCount > 0) View.VISIBLE else View.INVISIBLE
        }
        sendBroadcast(
            Intent(NOTIFICATION_SERVICE_ACTION)
                .setPackage(packageName)
                .putExtra("action", NOTIFICATION_COUNT_CHANGED)
                .putExtra("count", count)
        )
    }

    fun showNotificationPanel() {
        // FIX: Panel works in all modes, not just DEX mode
        if (Utils.notificationPanelVisible) return  // already open — don't stack
        val layoutParams = Utils.makeWindowParams(
            Utils.dpToPx(context, 400), -2, context,
            preferLastDisplay, fitNavInsets = true
        )
        // FIX: this panel (Quick Settings, image 3 in the bug report) was
        // always anchored to Gravity.BOTTOM regardless of dock position —
        // for a top dock it needs to drop down from the top instead, to
        // actually sit next to the dock rather than at the opposite edge.
        val qsPanelDockPosition = com.plate.launcher.utils.DockPositionUtils.get(sharedPreferences)
        layoutParams.gravity = (if (qsPanelDockPosition == com.plate.launcher.utils.DockPositionUtils.Position.TOP)
            Gravity.TOP else Gravity.BOTTOM) or Gravity.END
        layoutParams.y = y
        layoutParams.x = margins
        layoutParams.flags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH

        // Use quick_settings_panel.xml — original SmartDock layout with TabLayout
        // FIX: TabLayout requires a Material theme context to inflate correctly.
        // NotificationService's base context has no Material theme → InflateException.
        // Wrapping with ContextThemeWrapper(AppTheme_Dock) fixes this crash.
        notificationPanel = LayoutInflater.from(
            ContextThemeWrapper(context, R.style.AppTheme_Dock)
        ).inflate(R.layout.quick_settings_panel, null)
        com.plate.launcher.utils.AppFontScaleUtils.applyToViewHierarchy(notificationPanel)

        // ── Tab layout (Notifications / Quick Settings) ───────────────────────
        val tabLayout = notificationPanel!!.findViewById<com.google.android.material.tabs.TabLayout>(R.id.qs_tab_layout)
        notificationArea = notificationPanel!!.findViewById(R.id.notifications_layout)
        val quickSettingsArea = notificationPanel!!.findViewById<LinearLayout>(R.id.quick_settings_layout)

        cancelAllBtn = notificationPanel!!.findViewById(R.id.cancel_all_n_btn)
        cancelAllBtn!!.setOnClickListener { cancelAllNotifications() }

        notificationsLv = notificationPanel!!.findViewById(R.id.notification_lv)
        notificationsLv!!.layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)

        // ── Sliders: Volume + Brightness ─────────────────────────────────────
        // FIX: was R.id.volume_seekbar / R.id.brightness_seekbar (non-existent IDs → NPE crash)
        // Correct IDs from quick_settings_panel.xml are qs_volume_sb / qs_brightness_sb
        val volumeSeekbar = notificationPanel!!.findViewById<android.widget.SeekBar>(R.id.qs_volume_sb)
        val brightnessSeekbar = notificationPanel!!.findViewById<android.widget.SeekBar>(R.id.qs_brightness_sb)
        val am = getSystemService(android.media.AudioManager::class.java)
        volumeSeekbar.max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        volumeSeekbar.progress = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        volumeSeekbar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, p, 0)
            }
            override fun onStartTrackingTouch(s: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar) {}
        })
        brightnessSeekbar.max = 255
        try {
            brightnessSeekbar.progress = android.provider.Settings.System.getInt(
                contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS)
        } catch (e: Exception) { brightnessSeekbar.progress = 128 }

        // ── Is auto-brightness enabled? If so we need to turn it off the first time
        // the slider is touched, otherwise the system reverts the value and ignores
        // any manual change (this was the cause of brightness behaving differently
        // from the other tiles, which only control a simple on/off state) ──
        fun ensureManualBrightnessMode() {
            try {
                val mode = android.provider.Settings.System.getInt(
                    contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE)
                if (mode == android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
                    android.provider.Settings.System.putInt(
                        contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                        android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                }
            } catch (e: SecurityException) {
                val shizuku = ShizukoManager.getInstance(applicationContext)
                val root = RootManager.getInstance(applicationContext)
                when {
                    shizuku.hasPermission -> shizuku.runShell("settings put system screen_brightness_mode 0") {}
                    root.isAvailable -> root.runShell("settings put system screen_brightness_mode 0") {}
                }
            }
        }

        // ── Apply the brightness value with a Shizuku/Root fallback if we don't have WRITE_SETTINGS ──
        fun applyBrightness(value: Int) {
            try {
                android.provider.Settings.System.putInt(
                    contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, value)
            } catch (e: SecurityException) {
                val shizuku = ShizukoManager.getInstance(applicationContext)
                val root = RootManager.getInstance(applicationContext)
                when {
                    shizuku.hasPermission -> shizuku.runShell("settings put system screen_brightness $value") {}
                    root.isAvailable -> root.runShell("settings put system screen_brightness $value") {}
                    !android.provider.Settings.System.canWrite(applicationContext) -> {
                        // We have no permission at all — direct the user to the "Modify system settings" grant page
                        try {
                            AppUtils.openSystemSettings(
                                context,
                                android.content.Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                    android.net.Uri.parse("package:$packageName")),
                                dockHeight
                            )
                        } catch (e2: Exception) {}
                    }
                }
            }
        }

        brightnessSeekbar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    ensureManualBrightnessMode()
                    applyBrightness(p)
                }
            }
            override fun onStartTrackingTouch(s: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar) {}
        })

        // ── Customizable QS tile grid (4 slots) ─────────────────────────────────
        // Each slot's TYPE ("wifi" / "bluetooth" / "cafe_mode" / "none") comes from
        // qs_panel_tile_1..4 (set in Advanced Preferences). The wifi/bluetooth toggle
        // logic below is unchanged from the original wifi_tile/bluetooth_tile —
        // same Shizuku/Root/direct-API fallback chain — only lifted into a function
        // so it can be attached to whichever slot the user assigned it to.
        val wm2 = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
        val localBtManager = getSystemService(BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager

        // Each setup*Slot() function below appends its own refresh callback here,
        // so the color-application pass further down can re-run all of them once
        // bubbleCol/iconTint are final (mirrors the old refreshWifiTileState() /
        // refreshBtTileState() calls, generalized for up to 4 dynamic slots).
        val refreshQsTileStates = mutableListOf<() -> Unit>()

        data class QsSlotViews(
            val tile: LinearLayout,
            val icon: android.widget.ImageView,
            val title: android.widget.TextView,
            val subtitle: android.widget.TextView
        )

        fun slotViews(n: Int): QsSlotViews {
            val (tileId, iconId, titleId, subtitleId) = when (n) {
                1 -> arrayOf(R.id.qs_tile_slot_1, R.id.qs_tile_slot_1_icon, R.id.qs_tile_slot_1_title, R.id.qs_tile_slot_1_subtitle)
                2 -> arrayOf(R.id.qs_tile_slot_2, R.id.qs_tile_slot_2_icon, R.id.qs_tile_slot_2_title, R.id.qs_tile_slot_2_subtitle)
                3 -> arrayOf(R.id.qs_tile_slot_3, R.id.qs_tile_slot_3_icon, R.id.qs_tile_slot_3_title, R.id.qs_tile_slot_3_subtitle)
                else -> arrayOf(R.id.qs_tile_slot_4, R.id.qs_tile_slot_4_icon, R.id.qs_tile_slot_4_title, R.id.qs_tile_slot_4_subtitle)
            }
            return QsSlotViews(
                tile = notificationPanel!!.findViewById(tileId),
                icon = notificationPanel!!.findViewById(iconId),
                title = notificationPanel!!.findViewById(titleId),
                subtitle = notificationPanel!!.findViewById(subtitleId)
            )
        }

        // ── WiFi behavior, attached to whichever slot is assigned "wifi" ───────
        fun setupWifiSlot(v: QsSlotViews) {
            v.title.setText(R.string.wi_fi)

            fun applyWifiVisual(enabled: Boolean) {
                val bubbleNow = ColorUtils.getBubbleColor(sharedPreferences, context)
                val tintNow   = if (ColorUtils.isDockColorLight(sharedPreferences, context))
                    android.graphics.Color.BLACK else android.graphics.Color.WHITE

                v.icon.setImageResource(if (enabled) R.drawable.ic_wifi_on else R.drawable.ic_wifi_off)
                v.icon.background?.setColorFilter(
                    if (enabled) bubbleNow else android.graphics.Color.argb(80, 180, 180, 180),
                    android.graphics.PorterDuff.Mode.SRC_ATOP
                )
                v.icon.setColorFilter(
                    if (enabled) tintNow else android.graphics.Color.argb(160, 200, 200, 200),
                    android.graphics.PorterDuff.Mode.SRC_ATOP
                )
                v.tile.alpha = if (enabled) 1f else 0.55f
            }

            fun refreshWifiTileState() {
                val enabled = wm2.isWifiEnabled
                applyWifiVisual(enabled)
                if (enabled) {
                    val ssid = wm2.connectionInfo?.ssid?.replace("\"", "")
                    if (!ssid.isNullOrEmpty() && ssid != "<unknown ssid>") {
                        v.subtitle.text = ssid
                        v.subtitle.visibility = android.view.View.VISIBLE
                    } else {
                        v.subtitle.visibility = android.view.View.GONE
                    }
                } else {
                    v.subtitle.visibility = android.view.View.GONE
                }
            }
            refreshWifiTileState()
            refreshQsTileStates.add { refreshWifiTileState() }

            v.tile.setOnClickListener {
                val nowEnabled = wm2.isWifiEnabled
                val handler = android.os.Handler(android.os.Looper.getMainLooper())

                // ── Method 1: svc wifi via Shizuku or Root (the only reliable method
                // on Android 10+ — writing Settings.Global.WIFI_ON is now silently
                // ignored by the system without throwing an exception, which used to
                // make the code think it succeeded and never actually open settings) ──
                val cmd = if (nowEnabled) "svc wifi disable" else "svc wifi enable"
                val shizuku = ShizukoManager.getInstance(applicationContext)
                val root    = RootManager.getInstance(applicationContext)
                when {
                    shizuku.hasPermission -> {
                        applyWifiVisual(!nowEnabled) // optimistic — we have a reliable way to execute this
                        v.subtitle.visibility = android.view.View.GONE
                        shizuku.runShell(cmd) { _ -> handler.postDelayed({ refreshWifiTileState() }, 800) }
                    }
                    root.isAvailable -> {
                        applyWifiVisual(!nowEnabled)
                        v.subtitle.visibility = android.view.View.GONE
                        root.runShell(cmd) { _ -> handler.postDelayed({ refreshWifiTileState() }, 800) }
                    }
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> {
                        @Suppress("DEPRECATION")
                        wm2.isWifiEnabled = !nowEnabled
                        handler.postDelayed({ refreshWifiTileState() }, 800)
                    }
                    else -> {
                        // ── No Shizuku and no Root: open the WiFi settings page
                        // directly instead of misleading the user about a change that didn't actually happen ──
                        hideNotificationPanel()
                        AppUtils.openSystemSettings(
                            context, android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS), dockHeight
                        )
                    }
                }
            }
            v.tile.setOnLongClickListener {
                AppUtils.openSystemSettings(
                    context, android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS), dockHeight
                )
                hideNotificationPanel(); true
            }
        }

        // ── Bluetooth behavior, attached to whichever slot is assigned "bluetooth" ──
        fun setupBluetoothSlot(v: QsSlotViews) {
            v.title.setText(R.string.bluetooth)

            fun applyBtVisual(enabled: Boolean) {
                val bubbleNow = ColorUtils.getBubbleColor(sharedPreferences, context)
                val tintNow   = if (ColorUtils.isDockColorLight(sharedPreferences, context))
                    android.graphics.Color.BLACK else android.graphics.Color.WHITE

                v.icon.setImageResource(if (enabled) R.drawable.ic_bluetooth else R.drawable.ic_bluetooth_off)
                v.icon.background?.setColorFilter(
                    if (enabled) bubbleNow else android.graphics.Color.argb(80, 180, 180, 180),
                    android.graphics.PorterDuff.Mode.SRC_ATOP
                )
                v.icon.setColorFilter(
                    if (enabled) tintNow else android.graphics.Color.argb(160, 200, 200, 200),
                    android.graphics.PorterDuff.Mode.SRC_ATOP
                )
                v.tile.alpha = if (enabled) 1f else 0.55f
            }

            fun refreshBtTileState() {
                val adapter = localBtManager?.adapter
                val btOn = adapter?.isEnabled == true
                applyBtVisual(btOn)
                v.subtitle.text = if (btOn) adapter?.name ?: "" else ""
                v.subtitle.visibility = if (btOn && !adapter?.name.isNullOrEmpty())
                    android.view.View.VISIBLE else android.view.View.GONE
            }
            refreshBtTileState()
            refreshQsTileStates.add { refreshBtTileState() }

            v.tile.setOnClickListener {
                val adapter = localBtManager?.adapter
                val nowOn   = adapter?.isEnabled == true
                val handler = android.os.Handler(android.os.Looper.getMainLooper())

                // ── Immediate optimistic visual ───────────────────────────────────
                applyBtVisual(!nowOn)
                v.subtitle.visibility = android.view.View.GONE

                // Bug fix — race condition. hideNotificationPanel() used to be called
                // unconditionally right after kicking off enable()/disable(), so
                // the panel's 160ms dismiss animation would start (and the panel
                // View would be removed) before the async Bluetooth toggle had
                // actually completed — the sheet visibly closed "first" and the
                // real state change landed after, looking like the tap did
                // nothing until a beat later. Now we only close the panel here
                // when we're navigating away to a settings screen; for the two
                // async toggle paths (direct adapter call, Shizuku/Root shell)
                // the panel stays open — same behavior as the WiFi tile above —
                // and finishes reflecting the real state via refreshBtTileState()
                // once the toggle actually completes, instead of closing blind.

                // ── Method 1: BluetoothAdapter directly — the first attempt since it's the
                // most reliable method when we actually have BLUETOOTH_CONNECT granted ──
                val handledDirectly = if (adapter != null) {
                    try {
                        if (nowOn) adapter.disable() else adapter.enable()
                        handler.postDelayed({ refreshBtTileState() }, 800)
                        true
                    } catch (e: SecurityException) { false }
                } else false

                if (handledDirectly) {
                    return@setOnClickListener
                }

                // ── Method 2: svc bluetooth via Shizuku or Root ──
                val cmd = if (nowOn) "svc bluetooth disable" else "svc bluetooth enable"
                val shizuku = ShizukoManager.getInstance(applicationContext)
                val root    = RootManager.getInstance(applicationContext)
                when {
                    shizuku.hasPermission -> shizuku.runShell(cmd) { _ -> handler.postDelayed({ refreshBtTileState() }, 800) }
                    root.isAvailable -> root.runShell(cmd) { _ -> handler.postDelayed({ refreshBtTileState() }, 800) }
                    else -> {
                        applyBtVisual(nowOn) // revert to the real state — no actual change happened
                        hideNotificationPanel()
                        AppUtils.openSystemSettings(
                            context, android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS), dockHeight
                        )
                    }
                }
            }
            v.tile.setOnLongClickListener {
                AppUtils.openSystemSettings(
                    context, android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS), dockHeight
                )
                hideNotificationPanel(); true
            }
        }

        // ── Cafe Mode behavior, attached to whichever slot is assigned "cafe_mode" ──
        // Literally sets Settings.System.SCREEN_OFF_TIMEOUT to a huge value (the same
        // developer-options mechanism Android has had forever for "stay awake"), saving
        // the previous timeout first so it can be restored when turned back off.
        fun setupCafeModeSlot(v: QsSlotViews) {
            v.title.setText(R.string.cafe_mode)

            fun applyCafeVisual(enabled: Boolean) {
                val bubbleNow = ColorUtils.getBubbleColor(sharedPreferences, context)
                val tintNow   = if (ColorUtils.isDockColorLight(sharedPreferences, context))
                    android.graphics.Color.BLACK else android.graphics.Color.WHITE

                v.icon.setImageResource(R.drawable.ic_coffee)
                v.icon.background?.setColorFilter(
                    if (enabled) bubbleNow else android.graphics.Color.argb(80, 180, 180, 180),
                    android.graphics.PorterDuff.Mode.SRC_ATOP
                )
                v.icon.setColorFilter(
                    if (enabled) tintNow else android.graphics.Color.argb(160, 200, 200, 200),
                    android.graphics.PorterDuff.Mode.SRC_ATOP
                )
                v.tile.alpha = if (enabled) 1f else 0.55f
                v.subtitle.visibility = android.view.View.GONE
            }

            fun refreshCafeTileState() {
                applyCafeVisual(sharedPreferences.getBoolean("cafe_mode_enabled", false))
            }
            refreshCafeTileState()
            refreshQsTileStates.add { refreshCafeTileState() }
            // Same write path already used for brightness above: WRITE_SETTINGS
            // directly, falling back to Shizuku/Root `settings put system`,
            // falling back to the "grant Modify system settings" page.
            fun writeScreenOffTimeout(value: Int, onDone: () -> Unit) {
                try {
                    android.provider.Settings.System.putInt(
                        contentResolver, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, value)
                    onDone()
                } catch (e: SecurityException) {
                    val shizuku = ShizukoManager.getInstance(applicationContext)
                    val root = RootManager.getInstance(applicationContext)
                    when {
                        shizuku.hasPermission ->
                            shizuku.runShell("settings put system screen_off_timeout $value") { onDone() }
                        root.isAvailable ->
                            root.runShell("settings put system screen_off_timeout $value") { onDone() }
                        !android.provider.Settings.System.canWrite(applicationContext) -> {
                            hideNotificationPanel()
                            try {
                                AppUtils.openSystemSettings(
                                    context,
                                    android.content.Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                        android.net.Uri.parse("package:$packageName")),
                                    dockHeight
                                )
                            } catch (e2: Exception) {}
                        }
                    }
                }
            }

            v.tile.setOnClickListener {
                val nowOn = sharedPreferences.getBoolean("cafe_mode_enabled", false)
                if (!nowOn) {
                    // Turning ON: remember the current timeout first, then set a
                    // practically-infinite one (Integer.MAX_VALUE ms — same trick as
                    // "Stay awake" in Developer Options).
                    val currentTimeout = try {
                        android.provider.Settings.System.getInt(
                            contentResolver, android.provider.Settings.System.SCREEN_OFF_TIMEOUT)
                    } catch (e: Exception) { 30000 } // sane fallback: 30s, the Android default

                    sharedPreferences.edit {
                        putInt("cafe_mode_saved_timeout", currentTimeout)
                        putBoolean("cafe_mode_enabled", true)
                    }
                    applyCafeVisual(true)
                    writeScreenOffTimeout(Int.MAX_VALUE) {
                        Toast.makeText(context, R.string.cafe_mode_on, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    // Turning OFF: restore whatever timeout was saved before we touched it.
                    val restoreValue = sharedPreferences.getInt("cafe_mode_saved_timeout", 30000)
                    sharedPreferences.edit { putBoolean("cafe_mode_enabled", false) }
                    applyCafeVisual(false)
                    writeScreenOffTimeout(restoreValue) {
                        Toast.makeText(context, R.string.cafe_mode_off, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            v.tile.setOnLongClickListener {
                AppUtils.openSystemSettings(
                    context, android.content.Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS), dockHeight
                )
                hideNotificationPanel(); true
            }
        }

        fun setupNoneSlot(v: QsSlotViews) {
            v.tile.visibility = android.view.View.GONE
        }

        // ── Airplane Mode ──────────────────────────────────────────────────────
        fun setupAirplaneModeSlot(v: QsSlotViews) {
            v.title.setText(R.string.airplane_mode)

            fun isAirplaneOn() = android.provider.Settings.Global.getInt(
                contentResolver, android.provider.Settings.Global.AIRPLANE_MODE_ON, 0) != 0

            fun applyAirplaneVisual(enabled: Boolean) {
                val bubbleNow = ColorUtils.getBubbleColor(sharedPreferences, context)
                val tintNow = if (ColorUtils.isDockColorLight(sharedPreferences, context))
                    android.graphics.Color.BLACK else android.graphics.Color.WHITE
                v.icon.setImageResource(R.drawable.ic_airplane)
                v.icon.background?.setColorFilter(
                    if (enabled) bubbleNow else android.graphics.Color.argb(80, 180, 180, 180),
                    android.graphics.PorterDuff.Mode.SRC_ATOP)
                v.icon.setColorFilter(if (enabled) tintNow else android.graphics.Color.argb(160, 200, 200, 200),
                    android.graphics.PorterDuff.Mode.SRC_ATOP)
                v.tile.alpha = if (enabled) 1f else 0.55f
                v.subtitle.visibility = android.view.View.GONE
            }

            applyAirplaneVisual(isAirplaneOn())
            refreshQsTileStates.add { applyAirplaneVisual(isAirplaneOn()) }

            v.tile.setOnClickListener {
                val nowOn = isAirplaneOn()
                val cmd = if (nowOn) "settings put global airplane_mode_on 0 && am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false"
                          else       "settings put global airplane_mode_on 1 && am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true"
                val shizuku = ShizukoManager.getInstance(applicationContext)
                val root = RootManager.getInstance(applicationContext)
                applyAirplaneVisual(!nowOn)
                when {
                    shizuku.hasPermission -> shizuku.runShell(cmd) {}
                    root.isAvailable -> root.runShell(cmd) {}
                    else -> {
                        applyAirplaneVisual(nowOn) // revert
                        AppUtils.openSystemSettings(context, android.content.Intent(android.provider.Settings.ACTION_AIRPLANE_MODE_SETTINGS), dockHeight)
                    }
                }
            }
        }

        // ── Flashlight ─────────────────────────────────────────────────────────
        var isTorchOn = false
        val cameraManager = getSystemService(android.hardware.camera2.CameraManager::class.java)

        fun setupFlashlightSlot(v: QsSlotViews) {
            v.title.setText(R.string.flashlight)

            fun applyTorchVisual(enabled: Boolean) {
                val bubbleNow = ColorUtils.getBubbleColor(sharedPreferences, context)
                val tintNow = if (ColorUtils.isDockColorLight(sharedPreferences, context))
                    android.graphics.Color.BLACK else android.graphics.Color.WHITE
                v.icon.setImageResource(R.drawable.ic_flashlight)
                v.icon.background?.setColorFilter(
                    if (enabled) bubbleNow else android.graphics.Color.argb(80, 180, 180, 180),
                    android.graphics.PorterDuff.Mode.SRC_ATOP)
                v.icon.setColorFilter(if (enabled) tintNow else android.graphics.Color.argb(160, 200, 200, 200),
                    android.graphics.PorterDuff.Mode.SRC_ATOP)
                v.tile.alpha = if (enabled) 1f else 0.55f
                v.subtitle.visibility = android.view.View.GONE
            }

            applyTorchVisual(isTorchOn)
            refreshQsTileStates.add { applyTorchVisual(isTorchOn) }

            v.tile.setOnClickListener {
                try {
                    val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return@setOnClickListener
                    isTorchOn = !isTorchOn
                    cameraManager.setTorchMode(cameraId, isTorchOn)
                    applyTorchVisual(isTorchOn)
                } catch (e: Exception) {
                    isTorchOn = false
                    applyTorchVisual(false)
                }
            }
        }

        // ── Hotspot ────────────────────────────────────────────────────────────
        fun setupHotspotSlot(v: QsSlotViews) {
            v.title.setText(R.string.hotspot)
            val wifiMgr = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)

            @Suppress("DEPRECATION")
            fun isHotspotOn(): Boolean = try {
                val method = wifiMgr.javaClass.getDeclaredMethod("getWifiApState")
                method.isAccessible = true
                val state = method.invoke(wifiMgr) as? Int ?: 0
                state == 13 // WIFI_AP_STATE_ENABLED
            } catch (e: Exception) { false }

            fun applyHotspotVisual(enabled: Boolean) {
                val bubbleNow = ColorUtils.getBubbleColor(sharedPreferences, context)
                val tintNow = if (ColorUtils.isDockColorLight(sharedPreferences, context))
                    android.graphics.Color.BLACK else android.graphics.Color.WHITE
                v.icon.setImageResource(R.drawable.ic_hotspot)
                v.icon.background?.setColorFilter(
                    if (enabled) bubbleNow else android.graphics.Color.argb(80, 180, 180, 180),
                    android.graphics.PorterDuff.Mode.SRC_ATOP)
                v.icon.setColorFilter(if (enabled) tintNow else android.graphics.Color.argb(160, 200, 200, 200),
                    android.graphics.PorterDuff.Mode.SRC_ATOP)
                v.tile.alpha = if (enabled) 1f else 0.55f
                v.subtitle.visibility = android.view.View.GONE
            }

            val hotspotOn = isHotspotOn()
            applyHotspotVisual(hotspotOn)
            refreshQsTileStates.add { applyHotspotVisual(isHotspotOn()) }

            v.tile.setOnClickListener {
                val nowOn = isHotspotOn()
                val cmd = if (nowOn) "svc wifi stopap" else "svc wifi startap"
                val shizuku = ShizukoManager.getInstance(applicationContext)
                val root = RootManager.getInstance(applicationContext)
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                when {
                    shizuku.hasPermission -> {
                        applyHotspotVisual(!nowOn)
                        shizuku.runShell(cmd) { handler.postDelayed({ applyHotspotVisual(isHotspotOn()) }, 1500) }
                    }
                    root.isAvailable -> {
                        applyHotspotVisual(!nowOn)
                        root.runShell(cmd) { handler.postDelayed({ applyHotspotVisual(isHotspotOn()) }, 1500) }
                    }
                    else -> {
                        hideNotificationPanel()
                        AppUtils.openSystemSettings(context, android.content.Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS), dockHeight)
                    }
                }
            }
        }

        // ── Do Not Disturb ─────────────────────────────────────────────────────
        fun setupDndSlot(v: QsSlotViews) {
            v.title.setText(R.string.dnd_mode)
            val notifMgr = getSystemService(android.app.NotificationManager::class.java)

            fun isDndOn() = notifMgr.currentInterruptionFilter != android.app.NotificationManager.INTERRUPTION_FILTER_ALL

            fun applyDndVisual(enabled: Boolean) {
                val bubbleNow = ColorUtils.getBubbleColor(sharedPreferences, context)
                val tintNow = if (ColorUtils.isDockColorLight(sharedPreferences, context))
                    android.graphics.Color.BLACK else android.graphics.Color.WHITE
                v.icon.setImageResource(R.drawable.ic_dnd)
                v.icon.background?.setColorFilter(
                    if (enabled) bubbleNow else android.graphics.Color.argb(80, 180, 180, 180),
                    android.graphics.PorterDuff.Mode.SRC_ATOP)
                v.icon.setColorFilter(if (enabled) tintNow else android.graphics.Color.argb(160, 200, 200, 200),
                    android.graphics.PorterDuff.Mode.SRC_ATOP)
                v.tile.alpha = if (enabled) 1f else 0.55f
                v.subtitle.visibility = android.view.View.GONE
            }

            applyDndVisual(isDndOn())
            refreshQsTileStates.add { applyDndVisual(isDndOn()) }

            v.tile.setOnClickListener {
                try {
                    if (notifMgr.isNotificationPolicyAccessGranted) {
                        val nowOn = isDndOn()
                        notifMgr.setInterruptionFilter(
                            if (nowOn) android.app.NotificationManager.INTERRUPTION_FILTER_ALL
                            else       android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                        applyDndVisual(!nowOn)
                    } else {
                        hideNotificationPanel()
                        AppUtils.openSystemSettings(context,
                            android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS), dockHeight)
                    }
                } catch (e: Exception) {}
            }
        }

        // ── Location ───────────────────────────────────────────────────────────
        fun setupLocationSlot(v: QsSlotViews) {
            v.title.setText(R.string.location_tile)

            fun isLocationOn() = try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    (getSystemService(android.location.LocationManager::class.java))
                        .isLocationEnabled
                } else {
                    @Suppress("DEPRECATION")
                    android.provider.Settings.Secure.getInt(contentResolver,
                        android.provider.Settings.Secure.LOCATION_MODE,
                        android.provider.Settings.Secure.LOCATION_MODE_OFF) !=
                        android.provider.Settings.Secure.LOCATION_MODE_OFF
                }
            } catch (e: Exception) { false }

            fun applyLocationVisual(enabled: Boolean) {
                val bubbleNow = ColorUtils.getBubbleColor(sharedPreferences, context)
                val tintNow = if (ColorUtils.isDockColorLight(sharedPreferences, context))
                    android.graphics.Color.BLACK else android.graphics.Color.WHITE
                v.icon.setImageResource(R.drawable.ic_location)
                v.icon.background?.setColorFilter(
                    if (enabled) bubbleNow else android.graphics.Color.argb(80, 180, 180, 180),
                    android.graphics.PorterDuff.Mode.SRC_ATOP)
                v.icon.setColorFilter(if (enabled) tintNow else android.graphics.Color.argb(160, 200, 200, 200),
                    android.graphics.PorterDuff.Mode.SRC_ATOP)
                v.tile.alpha = if (enabled) 1f else 0.55f
                v.subtitle.visibility = android.view.View.GONE
            }

            applyLocationVisual(isLocationOn())
            refreshQsTileStates.add { applyLocationVisual(isLocationOn()) }

            v.tile.setOnClickListener {
                val nowOn = isLocationOn()
                val cmd = if (nowOn) "settings put secure location_mode 0" else "settings put secure location_mode 3"
                val shizuku = ShizukoManager.getInstance(applicationContext)
                val root = RootManager.getInstance(applicationContext)
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                when {
                    shizuku.hasPermission -> {
                        applyLocationVisual(!nowOn)
                        shizuku.runShell(cmd) { handler.postDelayed({ applyLocationVisual(isLocationOn()) }, 800) }
                    }
                    root.isAvailable -> {
                        applyLocationVisual(!nowOn)
                        root.runShell(cmd) { handler.postDelayed({ applyLocationVisual(isLocationOn()) }, 800) }
                    }
                    else -> {
                        hideNotificationPanel()
                        AppUtils.openSystemSettings(context, android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS), dockHeight)
                    }
                }
            }
        }

        // ── Sound Mode (Sound → Vibrate → Silent cycle) ────────────────────────
        fun setupSoundModeSlot(v: QsSlotViews) {
            v.title.setText(R.string.sound_mode)
            val audioMgr = getSystemService(android.media.AudioManager::class.java)

            fun currentMode() = audioMgr.ringerMode  // NORMAL=2, VIBRATE=1, SILENT=0

            fun applyRingerVisual(mode: Int) {
                val bubbleNow = ColorUtils.getBubbleColor(sharedPreferences, context)
                val tintNow = if (ColorUtils.isDockColorLight(sharedPreferences, context))
                    android.graphics.Color.BLACK else android.graphics.Color.WHITE
                val icon = when (mode) {
                    android.media.AudioManager.RINGER_MODE_NORMAL   -> R.drawable.ic_volume
                    android.media.AudioManager.RINGER_MODE_VIBRATE  -> R.drawable.ic_sound_vibrate
                    else                                              -> R.drawable.ic_sound_silent
                }
                v.icon.setImageResource(icon)
                v.icon.background?.setColorFilter(bubbleNow, android.graphics.PorterDuff.Mode.SRC_ATOP)
                v.icon.setColorFilter(tintNow, android.graphics.PorterDuff.Mode.SRC_ATOP)
                v.tile.alpha = if (mode == android.media.AudioManager.RINGER_MODE_SILENT) 0.55f else 1f
                v.subtitle.visibility = android.view.View.GONE
            }

            applyRingerVisual(currentMode())
            refreshQsTileStates.add { applyRingerVisual(currentMode()) }

            v.tile.setOnClickListener {
                try {
                    val next = when (audioMgr.ringerMode) {
                        android.media.AudioManager.RINGER_MODE_NORMAL  -> android.media.AudioManager.RINGER_MODE_VIBRATE
                        android.media.AudioManager.RINGER_MODE_VIBRATE -> android.media.AudioManager.RINGER_MODE_SILENT
                        else                                             -> android.media.AudioManager.RINGER_MODE_NORMAL
                    }
                    audioMgr.ringerMode = next
                    applyRingerVisual(next)
                } catch (e: SecurityException) {
                    hideNotificationPanel()
                    AppUtils.openSystemSettings(context, android.content.Intent(android.provider.Settings.ACTION_SOUND_SETTINGS), dockHeight)
                }
            }
        }

        // ── Auto Rotate ────────────────────────────────────────────────────────
        fun setupAutoRotateSlot(v: QsSlotViews) {
            v.title.setText(R.string.auto_rotate)

            fun isAutoRotateOn() = android.provider.Settings.System.getInt(
                contentResolver, android.provider.Settings.System.ACCELEROMETER_ROTATION, 0) == 1

            fun applyRotateVisual(enabled: Boolean) {
                val bubbleNow = ColorUtils.getBubbleColor(sharedPreferences, context)
                val tintNow = if (ColorUtils.isDockColorLight(sharedPreferences, context))
                    android.graphics.Color.BLACK else android.graphics.Color.WHITE
                v.icon.setImageResource(if (enabled) R.drawable.ic_screen_rotation_on else R.drawable.ic_screen_rotation_off)
                v.icon.background?.setColorFilter(
                    if (enabled) bubbleNow else android.graphics.Color.argb(80, 180, 180, 180),
                    android.graphics.PorterDuff.Mode.SRC_ATOP)
                v.icon.setColorFilter(if (enabled) tintNow else android.graphics.Color.argb(160, 200, 200, 200),
                    android.graphics.PorterDuff.Mode.SRC_ATOP)
                v.tile.alpha = if (enabled) 1f else 0.55f
                v.subtitle.visibility = android.view.View.GONE
            }

            applyRotateVisual(isAutoRotateOn())
            refreshQsTileStates.add { applyRotateVisual(isAutoRotateOn()) }

            v.tile.setOnClickListener {
                val nowOn = isAutoRotateOn()
                fun write(value: Int) {
                    try {
                        android.provider.Settings.System.putInt(contentResolver,
                            android.provider.Settings.System.ACCELEROMETER_ROTATION, value)
                        applyRotateVisual(value == 1)
                    } catch (e: SecurityException) {
                        val shizuku = ShizukoManager.getInstance(applicationContext)
                        val root = RootManager.getInstance(applicationContext)
                        when {
                            shizuku.hasPermission -> shizuku.runShell("settings put system accelerometer_rotation $value") { applyRotateVisual(value == 1) }
                            root.isAvailable -> root.runShell("settings put system accelerometer_rotation $value") { applyRotateVisual(value == 1) }
                            else -> { hideNotificationPanel(); AppUtils.openSystemSettings(context, android.content.Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS), dockHeight) }
                        }
                    }
                }
                write(if (nowOn) 0 else 1)
            }
        }

        // ── Data Saver ─────────────────────────────────────────────────────────
        // Data Saver QS tile removed — not needed per user request.

        // ── Assign each of the 4 slots based on user preference ────────────────
        // Slots are fixed at 4, but each is fully customizable from Settings →
        // Advanced → Quick Settings Tiles, with 11 available tile types.
        val qsTileRow2 = notificationPanel!!.findViewById<LinearLayout>(R.id.qs_tile_row_2)

        val tileDefaults = mapOf(1 to "wifi", 2 to "bluetooth", 3 to "airplane", 4 to "flashlight")

        for (n in 1..4) {
            val type = sharedPreferences.getString("qs_panel_tile_$n", tileDefaults[n] ?: "none")
            val v = slotViews(n)
            v.tile.visibility = android.view.View.VISIBLE
            v.subtitle.visibility = android.view.View.GONE
            when (type) {
                "wifi"        -> setupWifiSlot(v)
                "bluetooth"   -> setupBluetoothSlot(v)
                "cafe_mode"   -> setupCafeModeSlot(v)
                "airplane"    -> setupAirplaneModeSlot(v)
                "flashlight"  -> setupFlashlightSlot(v)
                "hotspot"     -> setupHotspotSlot(v)
                "dnd"         -> setupDndSlot(v)
                "location"    -> setupLocationSlot(v)
                "sound"       -> setupSoundModeSlot(v)
                "auto_rotate" -> setupAutoRotateSlot(v)
                else          -> setupNoneSlot(v)
            }
        }
        // Collapse the 2nd row entirely if both its slots are "none" — avoids an
        // empty gap when the user only wants a compact 2-tile setup.
        qsTileRow2.visibility =
            if (sharedPreferences.getString("qs_panel_tile_3", "airplane") == "none" &&
                sharedPreferences.getString("qs_panel_tile_4", "flashlight") == "none")
                android.view.View.GONE else android.view.View.VISIBLE

        // Used below for the ColorUtils.applySecondaryColor() pass — collect the
        // tiles that ended up visible (wifi/bluetooth/cafe_mode use search_background,
        // same as before).
        val coloredQsTiles = (1..4).map { slotViews(it) }.filter { it.tile.visibility == android.view.View.VISIBLE }

        // ── Bottom quick-actions row: user can hide it entirely to shrink the panel ──
        val bottomActionsRow = notificationPanel!!.findViewById<LinearLayout>(R.id.qs_bottom_actions_row)
        bottomActionsRow.visibility =
            if (sharedPreferences.getBoolean("enable_qs_bottom_row", true))
                android.view.View.VISIBLE else android.view.View.GONE

        // ── QS Buttons row ────────────────────────────────────────────────────
        val notificationsBtn = notificationPanel!!.findViewById<android.widget.ImageView>(R.id.notifications_btn)
        val orientationBtn   = notificationPanel!!.findViewById<android.widget.ImageView>(R.id.btn_orientation)
        val touchModeBtn     = notificationPanel!!.findViewById<android.widget.ImageView>(R.id.btn_touch_mode)
        val screenshotBtn    = notificationPanel!!.findViewById<android.widget.ImageView>(R.id.btn_screenshot)
        val screencapBtn     = notificationPanel!!.findViewById<android.widget.ImageView>(R.id.btn_screencast)
        val settingsBtn      = notificationPanel!!.findViewById<android.widget.ImageView>(R.id.btn_settings)

        touchModeBtn.setOnClickListener {
            hideNotificationPanel()
            if (sharedPreferences.getBoolean("tablet_mode", false)) {
                Utils.toggleBuiltinNavigation(sharedPreferences.edit(), false)
                sharedPreferences.edit {
                    putBoolean("app_menu_fullscreen", false)
                    putBoolean("tablet_mode", false)
                }
                Toast.makeText(context, R.string.tablet_mode_off, Toast.LENGTH_SHORT).show()
            } else {
                Utils.toggleBuiltinNavigation(sharedPreferences.edit(), true)
                sharedPreferences.edit {
                    putBoolean("app_menu_fullscreen", true)
                    putBoolean("tablet_mode", true)
                }
                Toast.makeText(context, R.string.tablet_mode_on, Toast.LENGTH_SHORT).show()
            }
        }
        orientationBtn.setImageResource(
            if (sharedPreferences.getBoolean("lock_landscape", true))
                R.drawable.ic_screen_rotation_off else R.drawable.ic_screen_rotation_on
        )
        orientationBtn.setOnClickListener {
            sharedPreferences.edit {
                putBoolean("lock_landscape", !sharedPreferences.getBoolean("lock_landscape", true))
            }
            orientationBtn.setImageResource(
                if (sharedPreferences.getBoolean("lock_landscape", true))
                    R.drawable.ic_screen_rotation_off else R.drawable.ic_screen_rotation_on
            )
            // FIX: this used to only write the "lock_landscape" preference —
            // nothing else in the whole app ever read it, so toggling the
            // button changed a value in SharedPreferences that nobody
            // consumed. LauncherActivity was also hard-locked to
            // android:screenOrientation="landscape" in the manifest, which
            // would have silently overridden any setRequestedOrientation()
            // call anyway even if one had existed. Now that the manifest
            // lock is removed, broadcast the change so LauncherActivity can
            // actually apply it.
            sendBroadcast(
                Intent(LAUNCHER_ACTION)
                    .setPackage(packageName)
                    .putExtra("action", "orientation_changed")
            )
        }
        screenshotBtn.setOnClickListener {
            hideNotificationPanel()
            sendBroadcast(
                Intent(NOTIFICATION_SERVICE_ACTION)
                    .setPackage(packageName)
                    .putExtra("action", ACTION_TAKE_SCREENSHOT)
            )
        }
        screencapBtn.setOnClickListener {
            hideNotificationPanel()
            launchApp("standard", sharedPreferences.getString("app_rec", "").orEmpty())
        }
        settingsBtn.setOnClickListener {
            hideNotificationPanel()
            launchApp("standard", packageName)
        }
        notificationsBtn.setImageResource(
            if (sharedPreferences.getBoolean("show_notifications", true))
                R.drawable.ic_notifications else R.drawable.ic_notifications_off
        )
        notificationsBtn.setOnClickListener {
            val showNotifications = sharedPreferences.getBoolean("show_notifications", true)
            sharedPreferences.edit { putBoolean("show_notifications", !showNotifications) }
            notificationsBtn.setImageResource(
                if (!showNotifications) R.drawable.ic_notifications else R.drawable.ic_notifications_off
            )
            if (showNotifications) Toast.makeText(context, R.string.popups_disabled, Toast.LENGTH_LONG).show()
        }

        // ── Tab switching: Notifications ↔ Quick Settings ─────────────────────
        tabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                val pos = tab?.position ?: 0
                notificationArea?.isVisible  = pos == 0
                cancelAllBtn?.isVisible      = pos == 0
                quickSettingsArea.isVisible  = pos == 1
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })
        tabLayout.selectTab(tabLayout.getTabAt(0))

        // ── Apply colors ──────────────────────────────────────────────────────
        ColorUtils.applyMainColor(context, sharedPreferences, notificationPanel!!)
        coloredQsTiles.forEach { ColorUtils.applySecondaryColor(context, sharedPreferences, it.tile) }

        val bubbleCol = ColorUtils.getBubbleColor(sharedPreferences, context)
        val isQsLight = ColorUtils.isDockColorLight(sharedPreferences, context)
        val iconTint  = if (isQsLight) android.graphics.Color.BLACK else android.graphics.Color.WHITE

        // ── Tab indicator: use bubble color instead of default Material purple ─
        tabLayout.setSelectedTabIndicatorColor(bubbleCol)
        tabLayout.setTabTextColors(
            // unselected: slightly dimmed
            android.graphics.Color.argb(
                140,
                android.graphics.Color.red(iconTint),
                android.graphics.Color.green(iconTint),
                android.graphics.Color.blue(iconTint)
            ),
            bubbleCol  // selected tab text = bubble color
        )

        // ── QS bottom buttons row coloring ────────────────────────────────────
        for (iv in listOf(notificationsBtn, orientationBtn, touchModeBtn,
                          screencapBtn, screenshotBtn, settingsBtn)) {
            iv.background?.setColorFilter(bubbleCol, android.graphics.PorterDuff.Mode.SRC_ATOP)
            iv.setColorFilter(iconTint, android.graphics.PorterDuff.Mode.SRC_ATOP)
        }

        // ── QS tile grid coloring (wifi / bluetooth / cafe mode) ───────────────
        // FIX: previously each tile only tinted its own icon using a snapshot of
        // bubbleCol/iconTint taken at setup time (before the theme/font/bubble
        // colors below were necessarily loaded), and the title/subtitle TextViews
        // were never touched at all — they stayed at the XML-hardcoded white,
        // completely deaf to theme, dock color, and bubble color changes. Applying
        // it here — in the same pass as the bottom row buttons, after bubbleCol
        // and iconTint are final — brings the tiles in line with the rest of the
        // panel instead of living in their own fixed-white bubble.
        for (v in coloredQsTiles) {
            v.icon.setColorFilter(iconTint, android.graphics.PorterDuff.Mode.SRC_ATOP)
            v.title.setTextColor(iconTint)
            v.subtitle.setTextColor(iconTint)
        }

        // WiFi & BT & Cafe Mode tiles: re-apply state visuals now that colors are loaded
        // (the on/off dim visuals inside each setup*Slot function may have run
        //  before bubbleCol/iconTint were ready — call the shared refresh once more)
        refreshQsTileStates.forEach { it() }

        // ── Separator for notification list ───────────────────────────────────
        val separator = MaterialDividerItemDecoration(
            ContextThemeWrapper(context, R.style.AppTheme_Dock), LinearLayoutManager.VERTICAL
        )
        separator.isLastItemDecorated = false
        notificationsLv!!.addItemDecoration(separator)

        // FIX: set initial state BEFORE addView to prevent 1-frame jump
        val slideIn = Utils.dpToPx(context, 18).toFloat()
        notificationPanel!!.translationY = slideIn
        notificationPanel!!.alpha = 0f
        // safeAddView() is a private DockService helper and isn't visible here;
        // NotificationService owns its own windowManager, so add directly
        // (guarded the same way safeAddView guards its own addView call).
        if (notificationPanel!!.windowToken == null) {
            try {
                windowManager.addView(notificationPanel!!, layoutParams)
            } catch (e: Exception) {
                // BadTokenException / IllegalStateException — nothing safe to do here
            }
        }
        // ANIM: entry animation
        notificationPanel!!.animate()
            .translationY(0f).alpha(1f)
            .setDuration(220)
            .setInterpolator(android.view.animation.DecelerateInterpolator(2f))
            .setListener(null)
            .start()
        notificationPanel!!.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE
                && (event.y < notificationPanel!!.measuredHeight || event.x < notificationPanel!!.x)
            ) {
                hideNotificationPanel()
            }
            false
        }
        Utils.notificationPanelVisible = true
        updateNotificationPanel()
    }

    private fun launchApp(mode: String, app: String) {
        sendBroadcast(
            Intent(LAUNCHER_ACTION)
                .setPackage(packageName)
                .putExtra("action", ACTION_LAUNCH_APP)
                .putExtra("mode", mode)
                .putExtra("app", app)
        )
    }

    fun hideNotificationPanel() {
        Utils.notificationPanelVisible = false
        val panel = notificationPanel ?: return
        notificationPanel = null
        notificationsLv = null
        cancelAllBtn = null
        qsAreaRef = null  // ✅ صفّر عند الإغلاق
        // ✅ ANIM: انيميشن خروج — يرتد لموضعه الأصلي مع fade out
        val slideOut = Utils.dpToPx(context, 14).toFloat()
        panel.animate().setListener(null).cancel()
        panel.animate()
            .translationY(slideOut).alpha(0f)
            .setDuration(160)
            .setInterpolator(android.view.animation.AccelerateInterpolator(2f))
            .withEndAction { try { windowManager.removeView(panel) } catch (e: Exception) {} }
            .start()
    }

    private fun updateNotificationPanel() {
        val ignoredApps = sharedPreferences.getStringSet("ignored_notifications_panel", setOf())!!
        val notifications =
            activeNotifications.filterNot { ignoredApps.contains(it.packageName) }.sortedWith(
                compareByDescending { AppUtils.isMediaNotification(it.notification) && it.isOngoing })
                .toTypedArray<StatusBarNotification>()
        var adapter = notificationsLv!!.adapter
        if (adapter is NotificationAdapter)
            adapter.updateNotifications(notifications)
        else {
            adapter = NotificationAdapter(
                context,
                notifications,
                this
            )
            notificationsLv!!.adapter = adapter
        }
        val layoutParams = notificationsLv!!.layoutParams
        val count = adapter.itemCount
        if (count > 3) {
            layoutParams.height = Utils.dpToPx(context, 232)
        } else layoutParams.height = -2
        notificationArea!!.visibility = if (count == 0) View.GONE else View.VISIBLE
        notificationsLv!!.layoutParams = layoutParams
    }

    internal inner class DockServiceReceiver : BroadcastReceiver() {
        override fun onReceive(p1: Context, intent: Intent) {
            when (intent.getStringExtra("action")) {
                ACTION_SHOW_NOTIFICATION_PANEL -> showNotificationPanel()
                ACTION_HIDE_NOTIFICATION_PANEL -> hideNotificationPanel()
                ACTION_DISMISS_NOTIFICATION_IMMEDIATE -> dismissNotificationImmediate()
                ACTION_HIDE_NOTIFICATION_BAR -> {
                    // Mark desktop mode as inactive so no new popups appear
                    sharedPreferences.edit { putBoolean("dex_mode_active", false) }
                    // Hide any popup currently on screen
                    hideNotification()
                    // Remove the notification bar window
                    try {
                        if (notificationLayout.windowToken != null)
                            windowManager.removeView(notificationLayout)
                    } catch (e: Exception) {
                        notificationLayout.visibility = android.view.View.GONE
                    }
                }
                ACTION_SHOW_NOTIFICATION_BAR -> {
                    // Mark desktop mode as active so popups are allowed when posted
                    sharedPreferences.edit { putBoolean("dex_mode_active", true) }
                }
                ACTION_STOP_NOTIFICATION_SERVICE -> {
                    // DockService is shutting down — disconnect the notification listener too
                    try { requestUnbind() } catch (e: Exception) {}
                }
            }
        }
    }

    override fun onNotificationClicked(sbn: StatusBarNotification, item: View) {
        val notification = sbn.notification
        if (notification.contentIntent != null) {
            hideNotificationPanel()
            try {
                val options = makeActivityOptions(
                    context, "standard", dockHeight,
                    Display.DEFAULT_DISPLAY
                )
                notification.contentIntent.send(context, 0, null, null, null, null, options.toBundle())
                if (sbn.isClearable) cancelNotification(sbn.key)
            } catch (e: CanceledException) {
            }
        }
    }

    override fun onNotificationLongClicked(notification: StatusBarNotification, item: View) {
        val savedApps = sharedPreferences.getStringSet(
            "ignored_notifications_panel",
            setOf()
        )!!
        val ignoredApps = mutableSetOf<String>()
        ignoredApps.addAll(savedApps)
        ignoredApps.add(notification.packageName)
        sharedPreferences.edit { putStringSet("ignored_notifications_panel", ignoredApps) }
        item.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        Toast.makeText(
            this@NotificationService,
            R.string.silenced_notifications,
            Toast.LENGTH_LONG
        ).show()
        updateNotificationPanel()
    }

    override fun onNotificationCancelClicked(notification: StatusBarNotification, item: View) {
        cancelNotification(notification.key)
    }

    override fun onSharedPreferenceChanged(p0: SharedPreferences?, preference: String?) {
        when (preference) {
            "dock_height" -> updateLayoutParams()
            // ✅ FIX: مزامنة الألوان فوراً عند تغيير أي إعداد لون
            "bubble_mode", "bubble_color", "bubble_color_custom",
            "bubble_alpha", "theme", "bubble_alpha_pct", "round_dock" -> {
                PerfectServer.syncColors(sharedPreferences, this)
            }
        }
    }

    /** يُستدعى من PerfectServer لتطبيق الألوان الجديدة على جميع واجهات الإشعارات */
    internal fun applyColorSync(mainColor: Int, mainAlpha: Int) {
        // ✅ Notification toast banner
        notificationLayout.background?.setColorFilter(mainColor, android.graphics.PorterDuff.Mode.SRC_ATOP)
        notificationLayout.background?.alpha = mainAlpha
        // ✅ Panel notification area (لو مفتوح)
        notificationArea?.background?.setColorFilter(mainColor, android.graphics.PorterDuff.Mode.SRC_ATOP)
        notificationArea?.background?.alpha = 255
    }

    private fun updateLayoutParams() {
        dockHeight =
            Utils.dpToPx(context, sharedPreferences.getString("dock_height", "56")!!.toInt())
        // FIX: floating dock adds 8dp gap — panels must clear this gap too
        val dockFloatMargin = if (sharedPreferences.getBoolean("round_dock", false))
            Utils.dpToPx(context, 8) else 0
        y = run {
            (if (DeviceUtils.shouldApplyNavbarFix())
                dockHeight - DeviceUtils.getNavBarHeight(context)
            else
                dockHeight) + dockFloatMargin + margins
        }

        notificationLayoutParams.y = y
        // Guard: updateViewLayout crashes if the view is not currently attached
        if (notificationLayout.windowToken != null) {
            try {
                windowManager.updateViewLayout(notificationLayout, notificationLayoutParams)
            } catch (e: Exception) {}
        }
    }

    override fun onDestroy() {
        PerfectServer.notifications = null  // ✅ أزل المرجع
        try { dockReceiver?.let { unregisterReceiver(it) } } catch (e: Exception) {}
        dockReceiver = null
        handler.removeCallbacksAndMessages(null)
        try { windowManager.removeView(notificationLayout) } catch (e: Exception) {}
        super.onDestroy()
    }
}


// ════════════════════════════════════════════════════════════════
// 3️⃣  DockTileService  —  خدمة Quick Settings Tile
// ════════════════════════════════════════════════════════════════
class DockTileService : TileService() {

    override fun onTileAdded() {
        super.onTileAdded()
        resetAndUpdateTile()
    }

    override fun onStartListening() {
        super.onStartListening()
        resetAndUpdateTile()
    }

    private fun resetAndUpdateTile() {
        val isServiceRunning = DeviceUtils.isAccessibilityServiceEnabled(applicationContext)
        if (!isServiceRunning) {
            PreferenceManager.getDefaultSharedPreferences(applicationContext)
                .edit().putBoolean("dex_mode_active", false).apply()
        }
        updateTile()
    }

    // Returns true if NotificationListenerService is granted access
    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(
            applicationContext.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        val cn = ComponentName(applicationContext, NotificationService::class.java)
        return flat.split(":").any {
            try { ComponentName.unflattenFromString(it) == cn } catch (e: Exception) { false }
        }
    }

    override fun onClick() {
        super.onClick()
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val isAccessibilityOn = DeviceUtils.isAccessibilityServiceEnabled(applicationContext)
        val isNotificationOn = isNotificationListenerEnabled()
        val dexActive = prefs.getBoolean("dex_mode_active", false)

        if (!dexActive) {
            // Check notification listener first — show dialog if missing
            if (!isNotificationOn) {
                showDialog(
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Notification access required")
                        .setMessage("PLate.Dex needs notification access to work correctly. You will be redirected to enable it.")
                        .setPositiveButton("OK") { _, _ ->
                            launchActivity(
                                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                        .setNegativeButton("Skip", null)
                        .create()
                )
                return
            }

            if (!isAccessibilityOn) {
                val hasPermission = DeviceUtils.hasWriteSettingsPermission(applicationContext)
                if (hasPermission) {
                    // Enable the accessibility service (exploit), then open the launcher
                    // immediately — no waiting. LauncherActivity listens for
                    // DOCK_SERVICE_CONNECTED and reacts the instant the service is up,
                    // even if that's a fraction of a second after launch.
                    DeviceUtils.enableService(applicationContext)
                    launchActivity(
                        Intent(applicationContext, LauncherActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    )
                } else {
                    // No permission — show dialog before redirecting
                    showDialog(
                        android.app.AlertDialog.Builder(this)
                            .setTitle("Accessibility service required")
                            .setMessage("PLate.Dex needs the Accessibility service to work. You will be redirected to Settings.")
                            .setPositiveButton("OK") { _, _ ->
                                launchActivity(
                                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                            .setNegativeButton("Cancel", null)
                            .create()
                    )
                }
            } else {
                // Both permissions OK — launch normally
                launchActivity(
                    Intent(applicationContext, LauncherActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                )
            }
        } else {
            AppUtils.stopDexAndLaunchOtherHome(this@DockTileService) { intent -> launchActivity(intent) }
        }
        updateTile()
    }

    private fun launchActivity(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                applicationContext, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val dexActive = PreferenceManager
            .getDefaultSharedPreferences(applicationContext)
            .getBoolean("dex_mode_active", false)
        tile.state = if (dexActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (dexActive) "PLate DEX " else "PLate DEX"
        tile.updateTile()
    }
}
