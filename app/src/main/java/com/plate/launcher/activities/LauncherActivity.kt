package com.plate.launcher.activities

import android.annotation.SuppressLint
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.preference.PreferenceManager
import coil.load
import com.plate.launcher.livewallpaper.data.WallpaperPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import com.google.android.material.button.MaterialButton
import com.plate.launcher.R
import com.plate.launcher.models.App
import com.plate.launcher.services.ACTION_LAUNCH_APP
import com.plate.launcher.services.DESKTOP_APP_PINNED
import com.plate.launcher.services.DOCK_SERVICE_ACTION
import com.plate.launcher.services.DOCK_SERVICE_CONNECTED
import com.plate.launcher.utils.AppUtils
import com.plate.launcher.utils.Utils
import kotlin.math.abs

const val LAUNCHER_ACTION  = "launcher_action"
const val LAUNCHER_RESUMED = "launcher_resumed"
const val LAUNCHER_PAUSED  = "launcher_paused"

/**
 * The desktop. Hosts app icons, freely positioned and dragged in pixel
 * space (snapped to a fixed-size visual grid for tidiness, not confined to
 * a bounded columns×rows grid — see loadDesktopApps()/findFreeCell()).
 *
 * Widget flow:
 *   - Long-press on empty space → context menu → "Add widget"
 *   - System widget picker (ACTION_APPWIDGET_PICK) opens.
 *   - onActivityResult: if the widget needs configuration, launch configure.
 *   - After configure (or directly if no config needed): addWidgetToDesktop().
 *   - Widgets persist across restarts via DesktopWidgetManager's SharedPrefs helpers.
 */
open class LauncherActivity : BaseFontScaleActivity(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var backgroundLayout: FrameLayout
    private lateinit var desktopContainer: FrameLayout
    private lateinit var serviceBtn: MaterialButton
    private lateinit var wallpaperImageView: ImageView
    private lateinit var wallpaperVideoView: TextureView
    private var wallpaperPlayer: ExoPlayer? = null
    private var currentAttachedWallpaper: String? = null
    private var wallpaperChangeReceiver: BroadcastReceiver? = null
    private lateinit var prefs: SharedPreferences
    private var dockServiceReceiver: BroadcastReceiver? = null
    private var bgTouchX = 0f
    private var bgTouchY = 0f

    // ─────────────────────────────────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // FIX: this used to `return` before setContentView() when
        // hasWriteSettingsPermission() was false (e.g. the very first launch
        // ever, before AutoShizuku/root has granted anything yet) — since
        // LauncherActivity is the HOME activity, that left the user on a
        // completely blank/black screen with only a dialog and no way
        // forward ("تبكي وتعيط"). The accessibility/dock setup is now always
        // attempted opportunistically, but never blocks the desktop's own
        // UI from being built — setContentView() and the rest of onCreate()
        // always run regardless of accessibility state.
        if (!com.plate.launcher.utils.DeviceUtils.isAccessibilityServiceEnabled(this)) {
            if (com.plate.launcher.utils.DeviceUtils.hasWriteSettingsPermission(this)) {
                // Enable the DockService accessibility service directly — no need
                // to route through Android Settings. enableService() now sets both
                // enabled_accessibility_services AND accessibility_enabled=1 (the
                // second one was the actual reason this used to silently fail and
                // fall through to routeToAccessibilitySettings() below every time).
                // DockService broadcasts DOCK_SERVICE_CONNECTED the instant it
                // binds (see registerReceivers()), so the UI reacts immediately —
                // this timer is only a fallback for the rare case binding genuinely
                // fails (e.g. a ROM that clears the setting on write).
                com.plate.launcher.utils.DeviceUtils.enableService(this)
                android.os.Handler(mainLooper).postDelayed({
                    if (!com.plate.launcher.utils.DeviceUtils.isAccessibilityServiceEnabled(this)) {
                        routeToAccessibilitySettings()
                    }
                }, 800)
            } else {
                // No WRITE_SECURE_SETTINGS yet (e.g. AutoShizuku/root hasn't
                // run) — show the dialog but keep going; the desktop still
                // renders below with its own "service not running" prompt
                // (serviceBtn) as the way forward instead of a dead end.
                routeToAccessibilitySettings()
            }
        }

        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.registerOnSharedPreferenceChangeListener(this)

        setContentView(R.layout.activity_launcher)
        backgroundLayout = findViewById(R.id.ll_background)
        desktopContainer  = findViewById(R.id.desktop_icons_container)
        serviceBtn        = findViewById(R.id.service_btn)
        wallpaperImageView = findViewById(R.id.desktop_wallpaper_image)
        wallpaperVideoView = findViewById(R.id.desktop_wallpaper_video)

        // GitHub issue #21: hiding the status/nav bar was unconditional —
        // no way to opt out. "hide_system_bars" defaults to true so
        // existing users see no behavior change; unchecking it in
        // preferences_appearance.xml skips the hide entirely, keeping both
        // bars visible and letting the desktop layout use its normal
        // (non-fullscreen) inset handling instead.
        if (prefs.getBoolean("hide_system_bars", true)) hideSystemBars()
        applyDockPadding()
        applyStatusBarInsetHandling()

        serviceBtn.setOnClickListener { startActivity(Intent(this, MainActivity::class.java)) }

        backgroundLayout.setOnLongClickListener {
            showDesktopEmptySpaceMenu(bgTouchX, bgTouchY)
            true
        }
        backgroundLayout.setOnTouchListener { _, event ->
            bgTouchX = event.x; bgTouchY = event.y
            false
        }

        registerReceivers()

        desktopContainer.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                desktopContainer.viewTreeObserver.removeOnGlobalLayoutListener(this)
                loadDesktopApps()
            }
        })
    }

    override fun onStart() {
        super.onStart()
        attachDesktopWallpaper()
    }

    private fun routeToAccessibilitySettings() {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.accessibility_service_required))
            .setMessage(getString(R.string.accessibility_service_message))
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                startActivity(
                    Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .setCancelable(true)
            .show()
    }

    override fun onResume() {
        super.onResume()
        // FIX: serviceBtn ("Let's start") defaults to VISIBLE in XML and is
        // only hidden when DockService broadcasts DOCK_SERVICE_CONNECTED.
        // But if LauncherActivity is recreated (config change, PiP exit,
        // fullscreen app return, display resize…) while DockService is
        // already running, it won't re-broadcast — so serviceBtn stays
        // visible forever even though the dock is alive and working, giving
        // the "صدمة نفسية / Let's start on every return" symptom.
        // Fix: check service state directly on every resume and hide the
        // button immediately if the service is already up.
        if (com.plate.launcher.utils.DeviceUtils.isAccessibilityServiceEnabled(this)) {
            serviceBtn.visibility = View.GONE
            // Also ask the dock to re-show itself in case it hid on pause
            sendBroadcast(Intent(DOCK_SERVICE_ACTION).setPackage(packageName).putExtra("action", "enable_dock"))
        }
        attachDesktopWallpaper()
        sendBroadcast(Intent(LAUNCHER_ACTION).setPackage(packageName).putExtra("action", LAUNCHER_RESUMED))
    }

    override fun onPause() {
        super.onPause()
        wallpaperPlayer?.pause()
        sendBroadcast(Intent(LAUNCHER_ACTION).setPackage(packageName).putExtra("action", LAUNCHER_PAUSED))
    }

    override fun onStop() {
        super.onStop()
        wallpaperPlayer?.release()
        wallpaperPlayer = null
        currentAttachedWallpaper = null
    }

    override fun onDestroy() {
        super.onDestroy()
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        try { dockServiceReceiver?.let { unregisterReceiver(it) } } catch (e: Exception) {}
        try { wallpaperChangeReceiver?.let { unregisterReceiver(it) } } catch (e: Exception) {}
        wallpaperPlayer?.release()
        wallpaperPlayer = null
        currentAttachedWallpaper = null
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences, key: String?) {
        if (key == "dock_height") applyDockPadding()
        if (key == "single_line_labels") loadDesktopApps()
        if (key == WallpaperPreferences.PREF_WALLPAPER_PATH || key == WallpaperPreferences.PREF_WALLPAPER_TYPE) {
            attachDesktopWallpaper()
        }
    }


    // ─────────────────────────────────────────────────────────────────────────
    //  Positioning (free pixel placement, snapped to a visual grid — not
    //  confined to a fixed columns×rows count. See the class doc comment:
    //  this replaced a bounded-grid system that silently overlapped icons
    //  once their count exceeded the grid's cell capacity.)
    // ─────────────────────────────────────────────────────────────────────────

    private fun iconSizePx(): Int = Utils.dpToPx(this, 50)

    private fun getGridSize(): Int = Utils.dpToPx(this, 85)

    /** Snap a pixel value to the nearest grid cell. */
    private fun snapToGrid(value: Int): Int {
        val g = getGridSize()
        return ((value + g / 2) / g) * g
    }

    /** Check if two icon positions overlap (within icon bounds). */
    private fun overlaps(x1: Int, y1: Int, x2: Int, y2: Int, iconSizePx: Int): Boolean {
        val margin = (iconSizePx * 0.6f).toInt()
        return abs(x1 - x2) < margin && abs(y1 - y2) < margin
    }

    /** Find a free grid cell starting from (startX, startY) that doesn't overlap existing icons. */
    private fun findFreeCell(
        startX: Int, startY: Int,
        occupiedPositions: List<Pair<Int, Int>>,
        iconSizePx: Int,
    ): Pair<Int, Int> {
        val g = getGridSize()
        // Search in expanding rings from the target cell.
        for (radius in 0..20) {
            val candidates = mutableListOf<Pair<Int, Int>>()
            if (radius == 0) {
                candidates.add(Pair(startX, startY))
            } else {
                for (col in -radius..radius) {
                    candidates.add(Pair(startX + col * g, startY - radius * g))
                    candidates.add(Pair(startX + col * g, startY + radius * g))
                }
                for (row in -radius + 1 until radius) {
                    candidates.add(Pair(startX - radius * g, startY + row * g))
                    candidates.add(Pair(startX + radius * g, startY + row * g))
                }
            }
            for ((cx, cy) in candidates) {
                if (cx < 0 || cy < 0) continue
                val free = occupiedPositions.none { (ox, oy) -> overlaps(cx, cy, ox, oy, iconSizePx) }
                if (free) return Pair(cx, cy)
            }
        }
        return Pair(startX, startY) // fallback
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Loading
    // ─────────────────────────────────────────────────────────────────────────

    fun loadDesktopApps() {
        desktopContainer.removeAllViews()
        val iconSize = iconSizePx()
        val g = getGridSize()
        val occupiedPositions = mutableListOf<Pair<Int, Int>>()

        // 1. App icons
        val apps = AppUtils.getPinnedApps(this, AppUtils.DESKTOP_LIST)
        apps.forEachIndexed { index, app ->
            val savedPos = prefs.getString("deskpos_${app.packageName}", null)
            val pos = if (savedPos != null) {
                val parts = savedPos.split(",")
                val sx = snapToGrid(parts.getOrNull(0)?.toIntOrNull() ?: g)
                val sy = snapToGrid(parts.getOrNull(1)?.toIntOrNull() ?: g)
                findFreeCell(sx, sy, occupiedPositions, iconSize)
            } else {
                findFreeCell(0, index * g, occupiedPositions, iconSize)
            }
            occupiedPositions.add(pos)
            addAppIcon(app, pos.first, pos.second)
        }

    }

    // ─────────────────────────────────────────────────────────────────────────
    //  App icons
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun addAppIcon(app: App, startX: Int, startY: Int) {
        val size = iconSizePx()
        val view = LayoutInflater.from(this).inflate(R.layout.app_entry_large, null)
        view.findViewById<ImageView>(R.id.app_icon_iv)?.setImageDrawable(app.icon)
        view.findViewById<TextView>(R.id.app_name_tv)?.text = app.name

        val lp = FrameLayout.LayoutParams(size, size).apply { leftMargin = startX; topMargin = startY }
        view.layoutParams = lp

        wireDrag(view) { freeX, freeY ->
            prefs.edit { putString("deskpos_${app.packageName}", "$freeX,$freeY") }
        }
        view.setOnClickListener {
            launchApp(app.packageName)
        }
        view.setOnLongClickListener { showAppContextMenu(app, view); true }

        desktopContainer.addView(view)
    }


    // ─────────────────────────────────────────────────────────────────────────
    //  Drag handling (shared between icons and widgets)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Free-drag with grid-snap on release. [onDropped] persists the final
     * (already-snapped, already-collision-checked) pixel position — the
     * caller decides where (app prefs vs widget prefs).
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun wireDrag(view: View, onDropped: (x: Int, y: Int) -> Unit) {
        var hasMoved   = false
        var downRawX   = 0f; var downRawY = 0f
        var startLeft  = 0;  var startTop = 0

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    hasMoved = false
                    downRawX = event.rawX; downRawY = event.rawY
                    val lp = v.layoutParams as FrameLayout.LayoutParams
                    startLeft = lp.leftMargin; startTop = lp.topMargin
                    v.elevation = 12f
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!hasMoved && (abs(dx) > 12 || abs(dy) > 12)) hasMoved = true
                    if (hasMoved) {
                        val lp = v.layoutParams as FrameLayout.LayoutParams
                        lp.leftMargin = (startLeft + dx).toInt().coerceAtLeast(0)
                        lp.topMargin  = (startTop  + dy).toInt().coerceAtLeast(0)
                        v.layoutParams = lp
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.elevation = 0f
                    if (hasMoved) {
                        val lp = v.layoutParams as FrameLayout.LayoutParams
                        val snappedX = snapToGrid(lp.leftMargin).coerceAtLeast(0)
                        val snappedY = snapToGrid(lp.topMargin).coerceAtLeast(0)

                        // Check overlap against every other desktop item.
                        val otherPositions = (0 until desktopContainer.childCount).mapNotNull { i ->
                            val child = desktopContainer.getChildAt(i)
                            if (child == v) null
                            else (child.layoutParams as? FrameLayout.LayoutParams)?.let { it.leftMargin to it.topMargin }
                        }
                        val freePos = findFreeCell(snappedX, snappedY, otherPositions, iconSizePx())

                        lp.leftMargin = freePos.first
                        lp.topMargin  = freePos.second
                        v.layoutParams = lp
                        onDropped(freePos.first, freePos.second)
                    }
                }
            }
            hasMoved
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Menus
    // ─────────────────────────────────────────────────────────────────────────

    private fun showAppContextMenu(app: App, anchor: View) {
        val popup = PopupMenu(this, anchor)
        val infoId = 1; val uninstallId = 2; val removeId = 3
        popup.menu.add(0, infoId,     0, getString(R.string.app_info))
        popup.menu.add(0, uninstallId, 0, getString(R.string.uninstall))
        popup.menu.add(0, removeId,   0, getString(R.string.remove_from_desktop))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                infoId -> AppUtils.openSystemSettings(this, Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", app.packageName, null)
                })
                uninstallId -> startActivity(Intent(Intent.ACTION_DELETE).apply {
                    data = android.net.Uri.fromParts("package", app.packageName, null)
                })
                removeId -> {
                    AppUtils.unpinApp(this, app.packageName, AppUtils.DESKTOP_LIST)
                    prefs.edit { remove("deskpos_${app.packageName}") }
                    loadDesktopApps()
                }
            }
            true
        }
        popup.show()
    }

    private fun showDesktopEmptySpaceMenu(touchX: Float, touchY: Float) {
        val anchor = View(this).apply { layoutParams = FrameLayout.LayoutParams(1, 1) }
        backgroundLayout.addView(anchor)
        anchor.x = touchX; anchor.y = touchY

        val popup = PopupMenu(this, anchor)
        val wallpaperId = 1; val displayId = 2
        popup.menu.add(0, wallpaperId, 0, getString(R.string.change_wallpaper))
        popup.menu.add(0, displayId,   0, getString(R.string.display_settings))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                wallpaperId -> startActivity(
                    Intent(this, com.plate.launcher.livewallpaper.ui.gallery.GalleryActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                displayId  -> AppUtils.openSystemSettings(this, Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS))
            }
            true
        }
        popup.setOnDismissListener { (anchor.parent as? ViewGroup)?.removeView(anchor) }
        popup.show()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun applyDockPadding() {
        val dockHeightDp = prefs.getString("dock_height", "56")?.toIntOrNull() ?: 56
        val dockHeightPx = (dockHeightDp * resources.displayMetrics.density + 0.5f).toInt()
        desktopContainer.setPadding(desktopContainer.paddingLeft, desktopContainer.paddingTop, desktopContainer.paddingRight, dockHeightPx)
    }

    // Patched: "الاج تو اج ... تغطي على الشاشة" — status bar overlapping desktop
    // content. hideSystemBars() hides the status bar by default, but
    // BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE means the user can still pull it
    // back in with a swipe from the top (this is desktop/DEX mode — that's
    // the only way to reach the time/notifications, so it has to stay
    // reachable). When it's showing, it draws as a real overlay on top of
    // whatever's there — nothing reserved space for it, so it visually
    // covered the top row of desktop icons instead of them shifting down
    // out of the way. This listener keeps the desktop container's top
    // padding in sync with the current systemBars() inset live: 0 while
    // the bar is hidden (content uses the full screen, the DEX-mode norm),
    // and the bar's real height the instant a swipe reveals it — no fixed
    // guess baked in, since transient-bar height/behavior can vary by OEM.
    private fun applyStatusBarInsetHandling() {
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(desktopContainer) { view, insets ->
            val topInset = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(view.paddingLeft, topInset, view.paddingRight, view.paddingBottom)
            insets
        }
    }

    private fun registerReceivers() {
        dockServiceReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.getStringExtra("action")) {
                    DOCK_SERVICE_CONNECTED -> {
                        serviceBtn.visibility = View.GONE
                        sendBroadcast(Intent(DOCK_SERVICE_ACTION).setPackage(packageName).putExtra("action", "enable_dock"))
                    }
                    DESKTOP_APP_PINNED -> loadDesktopApps()
                    // FIX: this is the missing consumer for the "lock_landscape"
                    // preference — PerfectServer's orientation button used to
                    // only write the pref (nobody ever read it back), and the
                    // manifest hard-locked this Activity to landscape anyway,
                    // silently overriding any setRequestedOrientation() call
                    // even if one had existed. Both are fixed now: the
                    // manifest lock is removed, and this actually applies it.
                    "orientation_changed" -> applyOrientationLock()
                }
            }
        }
        ContextCompat.registerReceiver(this, dockServiceReceiver, IntentFilter(DOCK_SERVICE_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, dockServiceReceiver, IntentFilter(LAUNCHER_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        wallpaperChangeReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                attachDesktopWallpaper()
            }
        }
        ContextCompat.registerReceiver(
            this,
            wallpaperChangeReceiver,
            IntentFilter(WallpaperPreferences.ACTION_WALLPAPER_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        applyOrientationLock()
    }

    private fun attachDesktopWallpaper() {
        val (path, type) = WallpaperPreferences.getActiveWallpaper(this)
        if (path.isNullOrBlank()) {
            wallpaperImageView.visibility = View.GONE
            wallpaperVideoView.visibility = View.GONE
            wallpaperPlayer?.stop()
            currentAttachedWallpaper = null

            // Initial fallback: migrate any existing active wallpaper from repository
            lifecycleScope.launch(Dispatchers.IO) {
                val repo = com.plate.launcher.livewallpaper.data.WallpaperConfigRepository.get(this@LauncherActivity)
                val active = repo.getActiveOrFallback()
                if (active != null) {
                    val isImg = com.plate.launcher.livewallpaper.data.VideoFileStore.isImage(this@LauncherActivity, active.videoUri)
                    WallpaperPreferences.setWallpaper(this@LauncherActivity, active.videoUri, isImg)
                }
            }
            return
        }

        if (type == WallpaperPreferences.TYPE_IMAGE) {
            wallpaperPlayer?.stop()
            wallpaperVideoView.visibility = View.GONE
            wallpaperImageView.visibility = View.VISIBLE
            if (currentAttachedWallpaper != path) {
                currentAttachedWallpaper = path
                val uri = android.net.Uri.parse(path)
                if (uri.scheme == "file" || uri.scheme == null) {
                    val file = File(uri.path ?: path)
                    if (file.exists()) {
                        wallpaperImageView.load(file)
                    } else {
                        wallpaperImageView.load(uri)
                    }
                } else {
                    wallpaperImageView.load(uri)
                }
            }
        } else {
            wallpaperImageView.visibility = View.GONE
            wallpaperVideoView.visibility = View.VISIBLE
            if (currentAttachedWallpaper != path) {
                currentAttachedWallpaper = path
                val player = wallpaperPlayer ?: ExoPlayer.Builder(this).build().also { p ->
                    wallpaperPlayer = p
                    p.repeatMode = Player.REPEAT_MODE_ONE
                    p.volume = 0f
                    p.setVideoTextureView(wallpaperVideoView)
                }
                val uri = android.net.Uri.parse(path)
                player.setMediaItem(MediaItem.fromUri(uri))
                player.prepare()
                player.playWhenReady = true
            } else {
                wallpaperPlayer?.play()
            }
        }
    }

    /** Reads "lock_landscape" from prefs and actually applies it to this window. */
    private fun applyOrientationLock() {
        requestedOrientation = if (prefs.getBoolean("lock_landscape", true))
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        else
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    @SuppressLint("NewApi")
    private fun hideSystemBars() {
        // Previously broken (content not extending under the display cutout/notch — "الحواف
        // الممنوعة"): two separate problems here.
        //   1. LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES only allows content
        //      to extend into the cutout on the SHORT edge — with
        //      lock_landscape on (the default), the cutout usually sits on
        //      what is now a LONG edge, so SHORT_EDGES leaves it reserved
        //      and the system draws black bars / the app avoids it there.
        //      LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS draws under the cutout
        //      in every orientation, no exceptions.
        //   2. The whole edge-to-edge branch was gated on API 30 (R)+, but
        //      layoutInDisplayCutoutMode (and ALWAYS specifically) has been
        //      available since API 28 (P) — on P/Q devices this code fell
        //      through to the old SYSTEM_UI_FLAG_* branch below, which never
        //      touches layoutInDisplayCutoutMode at all, so the cutout area
        //      stayed reserved by the system regardless of the fullscreen
        //      flags. Lowering the gate to P closes that gap.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.attributes = window.attributes.also {
                it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.also {
                it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        }
    }

    private fun launchApp(pkg: String, mode: String? = null) {
        sendBroadcast(
            Intent(LAUNCHER_ACTION).setPackage(packageName)
                .putExtra("action", ACTION_LAUNCH_APP)
                .putExtra("app", pkg)
                .apply { mode?.let { putExtra("mode", it) } }
        )
    }
}
