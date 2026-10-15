package com.youki.dex.taskview

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import com.youki.dex.models.AppTask
import com.youki.dex.services.PerfectServer
import com.youki.dex.utils.AppUtils
import com.youki.dex.utils.DeviceUtils
import com.youki.dex.utils.RootManager
import com.youki.dex.utils.ShizukoManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
     * Retrieve ONLY genuinely active / running apps.
     * Prioritizes foreground window, DockService active tasks, and recent sessions.
     */
    fun getTasks(max: Int = 8): ArrayList<AppTask> {
        val launcherPkg = AppUtils.getCurrentLauncher(packageManager)
        val selfPkg = context.packageName
        val shizuku = ShizukoManager.getInstance(context)
        val shell = com.youki.dex.server.ShellManager

        // 1. Try Shizuku / Shell am stack list — returns real windowed tasks if available
        if (shell.isAvailable || shizuku.hasPermission) {
            val viaShizuku = AppUtils.getTasksViaShizuku(context, packageManager, max)
            if (!viaShizuku.isNullOrEmpty()) {
                return viaShizuku
            }
        }

        // 2. If system app, use getRunningTasks
        val isSystemApp = AppUtils.isSystemApp(context, selfPkg)
        if (isSystemApp) {
            val running = AppUtils.getRunningTasks(activityManager, packageManager, max)
            if (running.isNotEmpty()) return running
        }

        // 3. Authoritative active tasks already held and tracked by DockService
        val dockTasks = PerfectServer.dock?.getActiveTasks()
        val result = if (!dockTasks.isNullOrEmpty()) {
            ArrayList(dockTasks.take(max))
        } else {
            // 4. Query recent tasks using UsageStatsManager (matches dock's logic)
            AppUtils.getRecentTasks(context, max)
        }

        // 5. Ensure the current foreground app is always present and at index 0
        val fgPkg = try {
            activityManager.getRunningTasks(1).firstOrNull()?.topActivity?.packageName
        } catch (e: Exception) {
            null
        } ?: AppUtils.currentApp

        if (!fgPkg.isNullOrEmpty() && fgPkg != selfPkg && fgPkg != launcherPkg && !fgPkg.contains("com.android.systemui")) {
            val existingIdx = result.indexOfFirst { it.packageName == fgPkg }
            if (existingIdx > 0) {
                val fgTask = result.removeAt(existingIdx)
                result.add(0, fgTask)
            } else if (existingIdx == -1) {
                try {
                    val label = AppUtils.getPackageLabel(context, fgPkg)
                    val icon = AppUtils.getAppIcon(context, fgPkg)
                    result.add(0, AppTask(-1, label, fgPkg, icon))
                } catch (e: Exception) {}
            }
        }

        // 6. If Shizuku is running, filter by live packages
        if (shizuku.hasPermission) {
            val livePkgs = shizuku.getRunningPackages().toSet()
            if (livePkgs.isNotEmpty()) {
                val filtered = result.filter { livePkgs.contains(it.packageName) || it.packageName == fgPkg }
                if (filtered.isNotEmpty()) {
                    return ArrayList(filtered.take(max))
                }
            }
        }

        return if (result.size > max) ArrayList(result.take(max)) else result
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
        val tasks = getTasks(max = 8)
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
        val launcherPkg = AppUtils.getCurrentLauncher(packageManager)
        if (task.packageName == context.packageName || task.packageName == launcherPkg) return

        // 1. If this task is the current foreground app, minimize it to home immediately!
        val currentFg = try {
            activityManager.getRunningTasks(1).firstOrNull()?.topActivity?.packageName
        } catch (e: Exception) {
            null
        } ?: AppUtils.currentApp

        if (task.packageName == currentFg) {
            PerfectServer.dock?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        }

        // 2. Perform process / task termination off the UI thread
        CoroutineScope(Dispatchers.IO).launch {
            val shell = com.youki.dex.server.ShellManager
            val shizuku = ShizukoManager.getInstance(context)
            val root = RootManager.getInstance(context)

            val cmdStop = "am force-stop ${task.packageName}"
            val cmdRemove = if (task.id > 0) "am task remove ${task.id}" else null

            try {
                if (shell.isAvailable) {
                    if (cmdRemove != null) shell.exec(context, cmdRemove) {}
                    shell.exec(context, cmdStop) {}
                } else if (shizuku.hasPermission) {
                    if (cmdRemove != null) shizuku.runShellSync(cmdRemove)
                    shizuku.runShellSync(cmdStop)
                } else if (root.isAvailable) {
                    if (cmdRemove != null) root.runShell(cmdRemove) {}
                    root.runShell(cmdStop) {}
                }
            } catch (e: Exception) {}

            try {
                activityManager.killBackgroundProcesses(task.packageName)
            } catch (e: Exception) {}

            withContext(Dispatchers.Main) {
                cachedTasks.removeAll { it.packageName == task.packageName }
                PerfectServer.dock?.updateRunningTasks(true)
            }
        }
    }

    private fun clearAllTasks() {
        val tasksToClose = ArrayList(cachedTasks)
        cachedTasks.clear()

        // 1. Immediately dismiss Task View overlay and return to home desktop
        dismissTaskView()
        PerfectServer.dock?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)

        // 2. Terminate all tasks asynchronously
        CoroutineScope(Dispatchers.IO).launch {
            val shell = com.youki.dex.server.ShellManager
            val shizuku = ShizukoManager.getInstance(context)
            val root = RootManager.getInstance(context)
            val launcherPkg = AppUtils.getCurrentLauncher(packageManager)

            for (task in tasksToClose) {
                if (task.packageName == context.packageName || task.packageName == launcherPkg) continue
                val cmdStop = "am force-stop ${task.packageName}"
                val cmdRemove = if (task.id > 0) "am task remove ${task.id}" else null

                try {
                    if (shell.isAvailable) {
                        if (cmdRemove != null) shell.exec(context, cmdRemove) {}
                        shell.exec(context, cmdStop) {}
                    } else if (shizuku.hasPermission) {
                        if (cmdRemove != null) shizuku.runShellSync(cmdRemove)
                        shizuku.runShellSync(cmdStop)
                    } else if (root.isAvailable) {
                        if (cmdRemove != null) root.runShell(cmdRemove) {}
                        root.runShell(cmdStop) {}
                    }
                } catch (e: Exception) {}

                try {
                    activityManager.killBackgroundProcesses(task.packageName)
                } catch (e: Exception) {}
            }

            withContext(Dispatchers.Main) {
                PerfectServer.dock?.clearRunningTasks()
            }
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
