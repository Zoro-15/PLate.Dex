package com.plate.launcher.adapters

import android.content.Context
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import com.plate.launcher.R
import com.plate.launcher.models.DockApp
import com.plate.launcher.utils.AppUtils
import com.plate.launcher.utils.ColorUtils
import com.plate.launcher.utils.IconPackUtils
import com.plate.launcher.utils.Utils

class DockAppAdapter(
    private val context: Context, private var apps: ArrayList<DockApp>,
    private val listener: OnDockAppClickListener, private val iconPackUtils: IconPackUtils?,
    private val dockHeight: Int = 0
) : RecyclerView.Adapter<DockAppAdapter.ViewHolder>() {
    private var iconBackground = 0
    private val iconPadding: Int
    private val iconTheming: Boolean
    private val tintIndicators: Boolean
    private val showRunningIndicators: Boolean
    private val isWinStyle: Boolean
    // bind
    private val dominantColorCache = HashMap<String, Int>()

    interface OnDockAppClickListener {
        fun onDockAppClicked(app: DockApp, view: View)
        fun onDockAppLongClicked(app: DockApp, view: View)
    }

    init {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        iconTheming = sharedPreferences.getString("icon_pack", "") != ""
        iconPadding =
            Utils.dpToPx(context, sharedPreferences.getString("icon_padding", "5")?.toIntOrNull() ?: 5)
        tintIndicators = sharedPreferences.getBoolean("tint_indicators", false)
        showRunningIndicators = sharedPreferences.getBoolean("show_running_indicators", true)
        val shape = sharedPreferences.getString("icon_shape", "circle")
        isWinStyle = shape == "win"
        when (shape) {
            "circle" -> iconBackground = R.drawable.circle
            "round_rect" -> iconBackground = R.drawable.round_square
            "win" -> iconBackground = R.drawable.win_square
            "default" -> iconBackground = -1
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, arg1: Int): ViewHolder {
        val itemLayoutView =
            LayoutInflater.from(parent.context).inflate(R.layout.app_task_entry, null)
        if (dockHeight > 0) {
            itemLayoutView.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dockHeight
            )
        }
        return ViewHolder(itemLayoutView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {
        val app = apps[position]
        val size = app.tasks.size
        val isForeground = app.packageName == AppUtils.currentApp

        // When Shizuku detection has populated a real liveness set (see its
        // kdoc — Shizuku-only, null otherwise), an icon only gets an
        // indicator if the app is genuinely alive right now: this is what
        // stops every recently-used app from showing a dot regardless of
        // whether it's actually still running. Without that set (Shizuku
        // off/unavailable), fall back to the old behavior — any app with a
        // task in the dock's list gets an indicator, since there's no way to
        // tell liveness apart from usage history in that case.
        val trulyRunning = AppUtils.trulyRunningPackages
        val isActuallyRunning = trulyRunning?.contains(app.packageName) ?: (size > 0)

        // The actually displayed icon: from the icon pack if enabled and available
        // for this app, otherwise the raw icon. This is the same source that
        // must be used to compute the automatic tint colors (the active-app
        // indicator + icon background) so it matches the actual color shown
        // to the user instead of always falling back to the raw icon.
        val iconDrawable = if (iconPackUtils != null)
            iconPackUtils.getAppThemedIcon(app.packageName)
        else
            app.icon

        // Fully reset the state before anything else
        viewHolder.runningIndicator.visibility = View.GONE
        viewHolder.taskCounter.alpha = 0f
        viewHolder.taskCounter.text = ""

        if (isActuallyRunning && showRunningIndicators) {
            viewHolder.runningIndicator.visibility = View.VISIBLE

            // FIX: a bold line for the app active in the foreground, a small dot for background apps
            val lp = viewHolder.runningIndicator.layoutParams
            if (isForeground) {
                // Active app → bold line 16dp × 3dp
                lp.width = Utils.dpToPx(context, 16)
                lp.height = Utils.dpToPx(context, 3)
            } else {
                // Background app → small dot 4dp × 4dp
                lp.width = Utils.dpToPx(context, 4)
                lp.height = Utils.dpToPx(context, 4)
            }
            viewHolder.runningIndicator.layoutParams = lp

            // Tint the indicator with the color of the actually displayed icon
            // (the icon pack if enabled, otherwise the raw icon) if tint_indicators is enabled
            if (tintIndicators && iconDrawable != null) {
                val appColor = dominantColorCache.getOrPut(app.packageName) {
                    ColorUtils.manipulateColor(ColorUtils.getDrawableDominantColor(iconDrawable), 2f)
                }
                viewHolder.runningIndicator.background?.setColorFilter(
                    appColor, android.graphics.PorterDuff.Mode.SRC_ATOP
                )
            } else {
                viewHolder.runningIndicator.background?.clearColorFilter()
            }

            if (size > 1) {
                viewHolder.taskCounter.text = size.toString()
                viewHolder.taskCounter.alpha = 1f
            }
        }

        viewHolder.iconIv.setImageDrawable(iconDrawable)

        // Scale the icon up/down based on the user's setting
        // FIX: applyLabelFontSize() used to be called here on taskCounter (the
        // window-count badge), even though the dock has no app-name label at
        // all (app_task_entry.xml contains no TextView for an app name) — so
        // the "font size" slider in Settings was actually scaling the counter
        // badge itself up/down instead of it staying at its fixed designed
        // size (9sp), which is what made its effect on the dock look
        // inconsistent or broken. The real label font size (the apps screen)
        // still works correctly in AppAdapter.
        com.plate.launcher.utils.IconScaleUtils.applyIconSize(viewHolder.iconIv, context, isDockIcon = true)

        // Custom badge (a general number) — only shown if no windows are already showing on the same badge
        if (com.plate.launcher.utils.IconScaleUtils.isCustomBadgeEnabled(context)) {
            val badgeText = com.plate.launcher.utils.IconScaleUtils.getLocalizedBadgeDisplayText(context)
            if (badgeText.isNotEmpty() && viewHolder.taskCounter.alpha == 0f) {
                viewHolder.taskCounter.text = badgeText
                viewHolder.taskCounter.alpha = 1f
            }
        }

        if (iconBackground != -1) {
            val pad = if (isWinStyle) Utils.dpToPx(context, 6) else iconPadding
            viewHolder.iconIv.setPadding(pad, pad, pad, pad)
            viewHolder.iconIv.setBackgroundResource(iconBackground)
            if (isWinStyle) {
                viewHolder.iconIv.background?.clearColorFilter()
            } else if (iconDrawable != null) {
                viewHolder.iconIv.background?.setColorFilter(
                    dominantColorCache.getOrPut(app.packageName) {
                        ColorUtils.getDrawableDominantColor(iconDrawable)
                    },
                    android.graphics.PorterDuff.Mode.SRC_ATOP
                )
            }
        }
        viewHolder.bind(app, listener)
        com.plate.launcher.utils.FontManager.applyIfSet(context, viewHolder.itemView)
    }

    override fun getItemCount(): Int {
        return apps.size
    }

    fun updateApps(newApps: ArrayList<DockApp>) {
        apps = newApps
        notifyDataSetChanged()
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        var iconIv: ImageView = itemView.findViewById(R.id.icon_iv)
        var taskCounter: TextView = itemView.findViewById(R.id.task_count_badge)
        var runningIndicator: View = itemView.findViewById(R.id.running_indicator)

        fun bind(app: DockApp, listener: OnDockAppClickListener) {
            // Discord request ("double click with a bluetooth mouse? Will
            // it work? double click to open an app, not only one click"):
            // opt-in via "require_double_click_apps" (default false) — most
            // users are on direct touch, where requiring a double-tap to
            // open anything would be a regression, not an improvement. When
            // enabled, a single click intentionally does nothing (not a
            // half-open/preview state) — a single-click no-op is a clear,
            // learnable behavior; anything else (highlight-then-open,
            // partial animation) would need its own separate design pass
            // this fix isn't attempting.
            val prefs = PreferenceManager.getDefaultSharedPreferences(itemView.context)
            if (prefs.getBoolean("require_double_click_apps", false)) {
                val gestureDetector = android.view.GestureDetector(
                    itemView.context,
                    object : android.view.GestureDetector.SimpleOnGestureListener() {
                        override fun onDoubleTap(e: MotionEvent): Boolean {
                            listener.onDockAppClicked(app, itemView)
                            return true
                        }
                    }
                )
                itemView.setOnClickListener(null)
                itemView.setOnTouchListener { view, event ->
                    // Secondary (right-click) still takes priority, same as
                    // the plain-click path below — checked first so a
                    // right-click on a double-click-enabled icon still opens
                    // the context menu immediately rather than requiring a
                    // second right-click to satisfy the gesture detector.
                    if (event.buttonState == MotionEvent.BUTTON_SECONDARY) {
                        listener.onDockAppLongClicked(app, view)
                        return@setOnTouchListener true
                    }
                    gestureDetector.onTouchEvent(event)
                    view.performClick() // keeps accessibility services (TalkBack) informed a click target exists here
                    true
                }
            } else {
                itemView.setOnClickListener { view -> listener.onDockAppClicked(app, view) }
                itemView.setOnTouchListener { view, event ->
                    if (event.buttonState == MotionEvent.BUTTON_SECONDARY) {
                        listener.onDockAppLongClicked(app, view)
                        return@setOnTouchListener true
                    }
                    false
                }
            }
            itemView.setOnLongClickListener { view ->
                listener.onDockAppLongClicked(app, view)
                true
            }
        }
    }
}
