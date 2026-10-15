package com.youki.dex.taskview

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.content.res.Configuration
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.youki.dex.R
import com.youki.dex.models.AppTask
import com.youki.dex.utils.Utils

/**
 * Built-in Desktop Task View matching the reference UI:
 * Floating window cards with top-right close circle, bottom app icon badge,
 * and bottom floating "Clear All Tasks" pill button over translucent wallpaper backdrop.
 */
class TaskViewOverlay(
    private val context: Context,
    private val windowManager: WindowManager
) {

    private var currentView: View? = null
    private var adapter: TaskViewAdapter? = null
    private var currentTasks: ArrayList<AppTask> = ArrayList()
    private val interpolator = DecelerateInterpolator(1.8f)

    fun isShowing(): Boolean = currentView != null && currentView?.windowToken != null

    fun show(
        tasks: ArrayList<AppTask>,
        onSelectTask: (AppTask) -> Unit,
        onCloseTask: (AppTask) -> Unit,
        onClearAll: () -> Unit
    ) {
        if (isShowing()) return

        currentTasks = ArrayList(tasks)
        val view = LayoutInflater.from(context).inflate(R.layout.overlay_task_view, null)

        val params = Utils.makeWindowParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
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

        val root = view.findViewById<View>(R.id.task_view_root)
        val gridRv = view.findViewById<RecyclerView>(R.id.task_view_grid_rv)
        val emptyLayout = view.findViewById<View>(R.id.task_view_empty_layout)
        val clearAllBtn = view.findViewById<View>(R.id.task_view_clear_all_btn)

        // Close when tapping empty wallpaper backdrop
        root.setOnClickListener {
            dismiss()
        }

        // Layout manager: horizontal row for <= 4 apps (matching reference),
        // or compact 2-row grid when 5+ apps are running
        val orientation = context.resources.configuration.orientation
        if (currentTasks.size <= 4) {
            gridRv.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        } else {
            val spanCount = if (orientation == Configuration.ORIENTATION_LANDSCAPE) 2 else 2
            gridRv.layoutManager = GridLayoutManager(context, spanCount, GridLayoutManager.HORIZONTAL, false)
        }

        adapter = TaskViewAdapter(
            context,
            currentTasks,
            snapshots = null,
            onTaskClick = { task, tileView ->
                tileView.animate()
                    .scaleX(1.05f)
                    .scaleY(1.05f)
                    .setDuration(110)
                    .setListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            dismiss()
                            onSelectTask(task)
                        }
                    })
                    .start()
            },
            onTaskClose = { task, position ->
                onCloseTask(task)
                adapter?.removeTaskAt(position)
                updateEmptyState(view)
            }
        )
        gridRv.adapter = adapter

        clearAllBtn.setOnClickListener {
            onClearAll()
            adapter?.clearAll()
            updateEmptyState(view)
            view.postDelayed({ dismiss() }, 200L)
        }

        updateEmptyState(view)

        // Entrance animation
        view.alpha = 0f
        gridRv.translationY = Utils.dpToPx(context, 20).toFloat()
        gridRv.scaleX = 0.96f
        gridRv.scaleY = 0.96f
        clearAllBtn.translationY = Utils.dpToPx(context, 16).toFloat()
        clearAllBtn.alpha = 0f

        view.animate()
            .alpha(1f)
            .setDuration(180)
            .start()

        gridRv.animate()
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(220)
            .setInterpolator(interpolator)
            .start()

        clearAllBtn.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(240)
            .setInterpolator(interpolator)
            .start()
    }

    private fun updateEmptyState(rootView: View) {
        val gridRv = rootView.findViewById<RecyclerView>(R.id.task_view_grid_rv)
        val emptyLayout = rootView.findViewById<View>(R.id.task_view_empty_layout)
        val clearAllBtn = rootView.findViewById<View>(R.id.task_view_clear_all_btn)

        if (currentTasks.isEmpty()) {
            gridRv.visibility = View.GONE
            emptyLayout.visibility = View.VISIBLE
            clearAllBtn.visibility = View.GONE
        } else {
            gridRv.visibility = View.VISIBLE
            emptyLayout.visibility = View.GONE
            clearAllBtn.visibility = View.VISIBLE
        }
    }

    fun dismiss() {
        val view = currentView ?: return
        if (view.windowToken == null) {
            currentView = null
            return
        }

        view.animate()
            .alpha(0f)
            .scaleX(0.97f)
            .scaleY(0.97f)
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
        currentView?.let {
            try {
                if (it.windowToken != null) windowManager.removeView(it)
            } catch (e: Exception) {}
        }
        currentView = null
    }
}
