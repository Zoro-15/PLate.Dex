package com.plate.launcher.services

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.PendingIntent.CanceledException
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.view.isVisible
import com.plate.launcher.utils.AppUtils.makeActivityOptions
import com.plate.launcher.utils.RootManager
import com.plate.launcher.utils.ShizukoManager
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.divider.MaterialDividerItemDecoration
import com.plate.launcher.R
import com.plate.launcher.activities.LAUNCHER_ACTION
import com.plate.launcher.adapters.NotificationAdapter
import com.plate.launcher.adapters.NotificationAdapter.OnNotificationClickListener
import com.plate.launcher.utils.AppUtils
import com.plate.launcher.utils.ColorUtils
import com.plate.launcher.utils.DeviceUtils
import com.plate.launcher.utils.Utils

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
                    com.plate.launcher.utils.AppUtils.isSystemApp(applicationContext, packageName) || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> {
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
