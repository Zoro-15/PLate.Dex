package com.youki.dex.taskview

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.youki.dex.R
import com.youki.dex.models.AppTask
import com.youki.dex.utils.Utils

/**
 * Windows / macOS-style floating compact Alt-Tab dialog overlay.
 * Centered on screen, cycles through open tasks, and commits on Alt release or click.
 */
class AltTabOverlay(private val context: Context, private val windowManager: WindowManager) {

    private var currentView: View? = null
    private var tasksList: List<AppTask> = emptyList()
    private var selectedIndex = 0
    private var onCommitCallback: ((AppTask) -> Unit)? = null
    private val interpolator = DecelerateInterpolator(1.8f)
    @Volatile private var isDismissing = false

    fun isShowing(): Boolean = currentView != null && currentView?.windowToken != null && !isDismissing

    fun show(tasks: List<AppTask>, startIndex: Int = 1, onCommit: (AppTask) -> Unit) {
        if (tasks.isEmpty()) return

        tasksList = tasks
        onCommitCallback = onCommit
        selectedIndex = startIndex.coerceIn(0, tasks.size - 1)

        if (isDismissing) {
            currentView?.animate()?.cancel()
            try {
                if (currentView?.windowToken != null) {
                    windowManager.removeView(currentView)
                }
            } catch (e: Exception) {}
            currentView = null
            isDismissing = false
        }

        var view = currentView
        if (view == null || view.windowToken == null) {
            view = LayoutInflater.from(context).inflate(R.layout.dialog_alt_tab, null)
            val displayMetrics = context.resources.displayMetrics
            val maxW = (displayMetrics.widthPixels * 0.85f).toInt()

            val params = Utils.makeWindowParams(
                maxW,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                context
            )
            params.gravity = Gravity.CENTER
            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

            try {
                windowManager.addView(view, params)
                currentView = view
            } catch (e: Exception) {
                return
            }

            // Entrance animation
            view.alpha = 0f
            view.scaleX = 0.92f
            view.scaleY = 0.92f
            view.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(150)
                .setInterpolator(interpolator)
                .start()
        }

        buildTiles(view)
        updateSelection(view)
    }

    private fun buildTiles(rootView: View) {
        val container = rootView.findViewById<LinearLayout>(R.id.alt_tab_items_container)
        container.removeAllViews()

        val inflater = LayoutInflater.from(context)
        tasksList.forEachIndexed { index, task ->
            val tile = inflater.inflate(R.layout.item_alt_tab_tile, container, false)
            val iconIv = tile.findViewById<ImageView>(R.id.alt_tab_tile_icon)
            val titleTv = tile.findViewById<TextView>(R.id.alt_tab_tile_title)

            iconIv.setImageDrawable(task.icon)
            titleTv.text = task.name

            tile.setOnClickListener {
                selectedIndex = index
                updateSelection(rootView)
                commitSelection()
            }

            container.addView(tile)
        }
    }

    fun selectNext() {
        if (tasksList.isEmpty()) return
        selectedIndex = (selectedIndex + 1) % tasksList.size
        currentView?.let { updateSelection(it) }
    }

    fun selectPrevious() {
        if (tasksList.isEmpty()) return
        selectedIndex = if (selectedIndex - 1 < 0) tasksList.size - 1 else selectedIndex - 1
        currentView?.let { updateSelection(it) }
    }

    private fun updateSelection(rootView: View) {
        if (tasksList.isEmpty()) return
        val currentTask = tasksList[selectedIndex]

        val titleTv = rootView.findViewById<TextView>(R.id.alt_tab_active_title)
        titleTv.text = currentTask.name

        val container = rootView.findViewById<LinearLayout>(R.id.alt_tab_items_container)
        val scroll = rootView.findViewById<HorizontalScrollView>(R.id.alt_tab_scroll)

        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            val isSelected = i == selectedIndex
            child.background = ContextCompat.getDrawable(
                context,
                if (isSelected) R.drawable.bg_alt_tab_tile_selected else R.drawable.bg_alt_tab_tile_normal
            )
            child.animate()
                .scaleX(if (isSelected) 1.05f else 1.0f)
                .scaleY(if (isSelected) 1.05f else 1.0f)
                .setDuration(120)
                .start()

            if (isSelected) {
                // Ensure the selected tile is smoothly scrolled into view
                child.post {
                    val targetX = child.left - Utils.dpToPx(context, 40)
                    scroll.smoothScrollTo(targetX.coerceAtLeast(0), 0)
                }
            }
        }
    }

    fun commitSelection() {
        val view = currentView ?: return
        val selectedTask = tasksList.getOrNull(selectedIndex)
        dismiss()
        if (selectedTask != null) {
            onCommitCallback?.invoke(selectedTask)
        }
    }

    fun dismiss() {
        val view = currentView ?: return
        if (view.windowToken == null) {
            currentView = null
            isDismissing = false
            return
        }
        if (isDismissing) return
        isDismissing = true

        view.animate()
            .alpha(0f)
            .scaleX(0.92f)
            .scaleY(0.92f)
            .setDuration(130)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    try {
                        if (view.windowToken != null) {
                            windowManager.removeView(view)
                        }
                    } catch (e: Exception) {}
                    if (currentView == view) {
                        currentView = null
                        isDismissing = false
                    }
                }
            })
            .start()
    }

    fun destroy() {
        isDismissing = false
        currentView?.let {
            it.animate()?.cancel()
            try {
                if (it.windowToken != null) windowManager.removeView(it)
            } catch (e: Exception) {}
        }
        currentView = null
    }
}
