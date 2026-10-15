package com.youki.dex.taskview

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import com.youki.dex.models.AppTask
import com.youki.dex.services.PerfectServer
import com.youki.dex.utils.AppUtils
import com.youki.dex.utils.DeviceUtils
import com.youki.dex.utils.ShizukoManager

/**
 * Manages the task stack for:
 * 1. Quick app switching (Alt+< / Alt+>)
 * 2. Compact floating dialog (Ctrl+Tab / Alt+Tab)
 * 3. Desktop Task View overview of genuinely open apps
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
     * Retrieve ONLY genuinely running / active apps.
     * Prevents dumping 24h history of closed apps.
     */
    fun getTasks(max: Int = 12): ArrayList<AppTask> {
        val launcherPkg = AppUtils.getCurrentLauncher(packageManager)
        val selfPkg = context.packageName
        val shizuku = ShizukoManager.getInstance(context)
        val shell = com.youki.dex.server.ShellManager

        // 1. Try Shizuku / Shell am stack list — returns real windowed tasks
        if (shell.isAvailable || shizuku.hasPermission) {
            val viaShizuku = AppUtils.getTasksViaShizuku(context, packageManager, max)
            if (!viaShizuku.isNullOrEmpty()) {
                return viaShizuku
            }
        }

        // 2. Identify packages actually alive in memory
        val runningPkgs: Set<String> = when {
            shizuku.hasPermission -> shizuku.getRunningPackages().toSet()
            else -> {
                try {
                    activityManager.runningAppProcesses
                        ?.map { it.processName }
                        ?.toSet() ?: emptySet()
                } catch (e: Exception) {
                    emptySet()
                }
            }
        }

        // 3. If system app, use getRunningTasks
        val isSystemApp = AppUtils.isSystemApp(context, selfPkg)
        if (isSystemApp) {
            val running = AppUtils.getRunningTasks(activityManager, packageManager, max)
            if (running.isNotEmpty()) return running
        }

        // 4. Fallback: Query UsageStatsManager but strictly for running/recently active apps
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager
        if (usm != null) {
            val endTime = System.currentTimeMillis()
            // Tight 20-minute window if running process list cannot be determined
            val windowMs = if (runningPkgs.isNotEmpty()) 3 * 60 * 60 * 1000L else 20 * 60 * 1000L
            val startTime = endTime - windowMs

            val stats = usm.queryUsageStats(
                android.app.usage.UsageStatsManager.INTERVAL_BEST, startTime, endTime
            )
                .sortedWith(compareByDescending { it.lastTimeUsed })
                .filterNot { it.packageName == selfPkg || it.packageName == launcherPkg || it.packageName.contains("systemui") }
                .filter { stat ->
                    if (runningPkgs.isNotEmpty()) {
                        runningPkgs.contains(stat.packageName)
                    } else {
                        (endTime - stat.lastTimeUsed) < 15 * 60 * 1000L
                    }
                }

            val result = ArrayList<AppTask>()
            for (stat in stats) {
                if (result.size >= max) break
                try {
                    val intent = packageManager.getLaunchIntentForPackage(stat.packageName) ?: continue
                    val label = AppUtils.getPackageLabel(context, stat.packageName)
                    val icon = AppUtils.getAppIcon(context, stat.packageName)
                    result.add(AppTask(-1, label, stat.packageName, icon))
                } catch (e: Exception) {}
            }

            if (result.isNotEmpty()) return result
        }

        return ArrayList()
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
     * Shows compact floating dialog (triggered by Ctrl+Tab or Alt+Tab).
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
     * Desktop Task View (Overview of genuinely running apps).
     */
    fun isTaskViewShowing(): Boolean = taskViewOverlay.isShowing()

    fun showTaskView(launchAppFn: (AppTask) -> Unit) {
        val tasks = getTasks(max = 12)
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
        val shell = com.youki.dex.server.ShellManager
        val shizuku = ShizukoManager.getInstance(context)
        val cmd = "am force-stop ${task.packageName}"

        try {
            if (shell.isAvailable) {
                shell.exec(context, cmd) {}
            } else if (shizuku.hasPermission) {
                shizuku.runShellSync(cmd)
            } else {
                DeviceUtils.runAsRoot(cmd)
            }
        } catch (e: Exception) {}

        try {
            activityManager.killBackgroundProcesses(task.packageName)
        } catch (e: Exception) {}

        // If the closed app was in the foreground, minimize to Home!
        if (task.packageName == AppUtils.currentApp) {
            PerfectServer.dock?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        }
    }

    private fun clearAllTasks() {
        val shell = com.youki.dex.server.ShellManager
        val shizuku = ShizukoManager.getInstance(context)
        val launcherPkg = AppUtils.getCurrentLauncher(packageManager)

        for (task in cachedTasks) {
            if (task.packageName == context.packageName || task.packageName == launcherPkg) continue
            val cmd = "am force-stop ${task.packageName}"
            try {
                if (shell.isAvailable) {
                    shell.exec(context, cmd) {}
                } else if (shizuku.hasPermission) {
                    shizuku.runShellSync(cmd)
                } else {
                    DeviceUtils.runAsRoot(cmd)
                }
            } catch (e: Exception) {}

            try {
                activityManager.killBackgroundProcesses(task.packageName)
            } catch (e: Exception) {}
        }

        // Return to home desktop so open apps vanish immediately!
        PerfectServer.dock?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
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
