package com.youki.dex.taskview

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.youki.dex.R
import com.youki.dex.models.AppTask
import com.youki.dex.utils.Utils

/**
 * Sleek Linux rice-inspired floating HUD indicator shown when switching apps
 * via keyboard shortcuts (Ctrl+Left, Ctrl+Right, etc.).
 * Displays active app icon, title, direction badge, and workspace-style dots.
 */
class RiceSwitchHud(private val context: Context, private val windowManager: WindowManager) {

    private val handler = Handler(Looper.getMainLooper())
    private var currentView: View? = null
    private var dismissRunnable: Runnable? = null
    private val interpolator = DecelerateInterpolator(1.8f)

    fun show(task: AppTask, currentIndex: Int, totalTasks: Int, direction: Int) {
        handler.removeCallbacksAndMessages(null)

        var view = currentView
        if (view == null || view.windowToken == null) {
            view = LayoutInflater.from(context).inflate(R.layout.hud_linux_rice_switch, null)
            val params = Utils.makeWindowParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                context
            )
            params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            params.y = Utils.dpToPx(context, 72)
            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

            try {
                windowManager.addView(view, params)
                currentView = view
            } catch (e: Exception) {
                return
            }
        }

        // Bind data
        val dirTv = view.findViewById<TextView>(R.id.rice_hud_direction_tv)
        val iconIv = view.findViewById<ImageView>(R.id.rice_hud_icon_iv)
        val titleTv = view.findViewById<TextView>(R.id.rice_hud_title_tv)
        val dotsContainer = view.findViewById<LinearLayout>(R.id.rice_hud_dots_container)

        dirTv.text = if (direction >= 0) "▶ NEXT" else "◀ PREV"
        iconIv.setImageDrawable(task.icon)
        titleTv.text = task.name

        // Render workspace/task dots (up to 8 dots max to keep pill compact)
        dotsContainer.removeAllViews()
        val visibleDots = totalTasks.coerceIn(1, 8)
        val activeDotIndex = if (totalTasks > 0) currentIndex % visibleDots else 0

        val dotMargin = Utils.dpToPx(context, 3)
        for (i in 0 until visibleDots) {
            val dot = View(context)
            val isActive = i == activeDotIndex
            val w = Utils.dpToPx(context, if (isActive) 14 else 6)
            val h = Utils.dpToPx(context, 6)
            val lp = LinearLayout.LayoutParams(w, h).apply {
                marginStart = dotMargin
                marginEnd = dotMargin
            }
            dot.layoutParams = lp
            dot.background = ContextCompat.getDrawable(
                context,
                if (isActive) R.drawable.dot_rice_active else R.drawable.dot_rice_inactive
            )
            dotsContainer.addView(dot)
        }

        // Linux rice entrance micro-animation (smooth slide + scale pop)
        val startTransX = if (direction >= 0) Utils.dpToPx(context, 35).toFloat() else -Utils.dpToPx(context, 35).toFloat()
        view.translationX = startTransX
        view.translationY = 0f
        view.alpha = 0.5f
        view.scaleX = 0.95f
        view.scaleY = 0.95f

        view.animate()
            .translationX(0f)
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(160)
            .setInterpolator(interpolator)
            .setListener(null)
            .start()

        // Auto dismiss after 850ms of inactivity
        dismissRunnable = Runnable { dismiss() }
        handler.postDelayed(dismissRunnable!!, 850L)
    }

    fun dismiss() {
        handler.removeCallbacksAndMessages(null)
        val view = currentView ?: return
        if (view.windowToken == null) {
            currentView = null
            return
        }

        view.animate()
            .alpha(0f)
            .translationY(-Utils.dpToPx(context, 16).toFloat())
            .scaleX(0.96f)
            .scaleY(0.96f)
            .setDuration(160)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    try {
                        if (view.windowToken != null) {
                            windowManager.removeView(view)
                        }
                    } catch (e: Exception) {}
                    if (currentView == view) currentView = null
                }
            })
            .start()
    }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        currentView?.let {
            try {
                if (it.windowToken != null) windowManager.removeView(it)
            } catch (e: Exception) {}
        }
        currentView = null
    }
}
