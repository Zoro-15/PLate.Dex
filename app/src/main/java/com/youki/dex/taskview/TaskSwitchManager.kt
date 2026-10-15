package com.youki.dex.taskview

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.preference.PreferenceManager
import com.youki.dex.models.AppTask
import com.youki.dex.utils.AppUtils
import com.youki.dex.utils.ShizukoManager

/**
 * Manages the task stack for:
 * 1. Quick app switching (Ctrl+Left/Right)
 * 2. Alt-Tab compact floating dialog
 * 3. Desktop Task View (overview of running apps matching desktop reference)
 */
class TaskSwitchManager(
    private val context: Context,
    private val activityManager: ActivityManager,
    private val packageManager: PackageManager,
    private val riceSwitchHud: RiceSwitchHud,
    private val altTabOverlay: AltTabOverlay,
    private val taskViewOverlay: TaskViewOverlay
) {

    private var cachedTasks = ArrayList<AppTask>()
    private var currentIndex = 0
    private var lastSwitchTime = 0L

    /**
     * Retrieve the list of active/recent tasks using the best available source:
     * 1. Shizuku am stack list (true windowed tasks)
     * 2. ActivityManager.getRunningTasks (system app)
     * 3. UsageStatsManager (recent apps history)
     */
    fun getTasks(max: Int = 12): ArrayList<AppTask> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val isSystemApp = AppUtils.isSystemApp(context, context.packageName)
        val useShizuku = prefs.getBoolean("shizuku_active_app_detection", false)
        val shizuku = ShizukoManager.getInstance(context)

        return when {
            isSystemApp -> {
                AppUtils.getRunningTasks(activityManager, packageManager, max)
            }
            useShizuku && shizuku.hasPermission -> {
                val viaShizuku = AppUtils.getTasksViaShizuku(context, packageManager, max)
                viaShizuku ?: AppUtils.getRecentTasks(context, max)
            }
            else -> {
                AppUtils.getRecentTasks(context, max)
            }
        }
    }

    /**
     * Switch to previous (-1, Left) or next (+1, Right) recent app with Linux rice HUD.
     */
    fun switchRecentApp(direction: Int, launchAppFn: (AppTask) -> Unit) {
        val now = System.currentTimeMillis()
        val timeout = 1800L

        if (now - lastSwitchTime > timeout || cachedTasks.isEmpty()) {
            cachedTasks = getTasks()
            if (cachedTasks.isEmpty()) return

            val currentPkg = AppUtils.currentApp
            val foundIdx = cachedTasks.indexOfFirst { it.packageName == currentPkg }
            currentIndex = if (foundIdx >= 0) foundIdx else 0
        }

        if (cachedTasks.isEmpty()) return

        currentIndex = if (direction > 0) {
            (currentIndex + 1) % cachedTasks.size
        } else {
            if (currentIndex - 1 < 0) cachedTasks.size - 1 else currentIndex - 1
        }
        lastSwitchTime = now

        val targetTask = cachedTasks[currentIndex]
        switchToTask(targetTask, launchAppFn)

        riceSwitchHud.show(targetTask, currentIndex, cachedTasks.size, direction)
    }

    /**
     * Shows Alt-Tab overlay dialog.
     */
    fun showAltTab(launchAppFn: (AppTask) -> Unit) {
        val tasks = getTasks()
        if (tasks.isEmpty()) return
        cachedTasks = tasks

        val startIdx = if (tasks.size > 1) 1 else 0
        currentIndex = startIdx

        altTabOverlay.show(tasks, startIndex = startIdx) { selectedTask ->
            switchToTask(selectedTask, launchAppFn)
            riceSwitchHud.show(selectedTask, tasks.indexOf(selectedTask).coerceAtLeast(0), tasks.size, 1)
        }
    }

    fun isAltTabShowing(): Boolean = altTabOverlay.isShowing()
    fun selectNextAltTab() = altTabOverlay.selectNext()
    fun selectPreviousAltTab() = altTabOverlay.selectPrevious()
    fun commitAltTab() = altTabOverlay.commitSelection()
    fun dismissAltTab() = altTabOverlay.dismiss()

    /**
     * Desktop Task View (Overview of running apps matching user reference).
     */
    fun isTaskViewShowing(): Boolean = taskViewOverlay.isShowing()

    fun showTaskView(launchAppFn: (AppTask) -> Unit) {
        val tasks = getTasks(max = 16)
        cachedTasks = tasks

        taskViewOverlay.show(
            tasks,
            onSelectTask = { selectedTask ->
                switchToTask(selectedTask, launchAppFn)
            },
            onCloseTask = { taskToClose ->
                closeTask(taskToClose)
            },
            onClearAll = {
                clearAllTasks()
            }
        )
    }

    fun toggleTaskView(launchAppFn: (AppTask) -> Unit) {
        if (taskViewOverlay.isShowing()) {
            taskViewOverlay.dismiss()
        } else {
            showTaskView(launchAppFn)
        }
    }

    fun dismissTaskView(): Boolean {
        if (taskViewOverlay.isShowing()) {
            taskViewOverlay.dismiss()
            return true
        }
        return false
    }

    private fun closeTask(task: AppTask) {
        try {
            activityManager.killBackgroundProcesses(task.packageName)
            val shizuku = ShizukoManager.getInstance(context)
            if (shizuku.hasPermission) {
                shizuku.runShellSync("am force-stop ${task.packageName}")
            }
        } catch (e: Exception) {}
    }

    private fun clearAllTasks() {
        val shizuku = ShizukoManager.getInstance(context)
        val launcherPkg = AppUtils.getCurrentLauncher(packageManager)
        for (task in cachedTasks) {
            if (task.packageName == context.packageName || task.packageName == launcherPkg) continue
            try {
                activityManager.killBackgroundProcesses(task.packageName)
                if (shizuku.hasPermission) {
                    shizuku.runShellSync("am force-stop ${task.packageName}")
                }
            } catch (e: Exception) {}
        }
    }

    /**
     * Brings task to front or launches its intent.
     */
    fun switchToTask(task: AppTask, launchAppFn: (AppTask) -> Unit) {
        if (task.id > 0) {
            try {
                activityManager.moveTaskToFront(task.id, 0)
                AppUtils.currentApp = task.packageName
                return
            } catch (e: Exception) {}
        }
        launchAppFn(task)
        AppUtils.currentApp = task.packageName
    }

    fun destroy() {
        riceSwitchHud.destroy()
        altTabOverlay.destroy()
        taskViewOverlay.destroy()
    }
}
