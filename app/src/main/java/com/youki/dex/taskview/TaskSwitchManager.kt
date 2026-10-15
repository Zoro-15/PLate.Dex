package com.youki.dex.taskview

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import com.youki.dex.models.AppTask
import com.youki.dex.services.PerfectServer
import com.youki.dex.utils.AppUtils
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

    private fun isValidUserApp(pkg: String?, launcherPkg: String): Boolean {
        if (pkg.isNullOrBlank()) return false
        if (pkg == context.packageName || pkg == launcherPkg) return false
        if (pkg.contains("launcher", ignoreCase = true)) return false
        if (pkg.contains("quickstep", ignoreCase = true)) return false
        if (pkg.contains("systemui", ignoreCase = true)) return false
        if (pkg.contains("com.android.system", ignoreCase = true)) return false
        return try {
            packageManager.getLaunchIntentForPackage(pkg) != null
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Retrieve ONLY genuinely active / running apps in this session.
     * Prevents dumping 24h history of closed apps and eliminates empty tiles.
     */
    fun getTasks(max: Int = 8): ArrayList<AppTask> {
        val launcherPkg = AppUtils.getCurrentLauncher(packageManager)
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
        val isSystemApp = AppUtils.isSystemApp(context, context.packageName)
        if (isSystemApp) {
            val running = AppUtils.getRunningTasks(activityManager, packageManager, max)
                .filter { isValidUserApp(it.packageName, launcherPkg) }
            if (running.isNotEmpty()) return ArrayList(running)
        }

        // 3. Collect active packages from interactive windows and current session tracking
        val activePkgs = LinkedHashSet<String>()

        // 3a. Foreground app
        val fgPkg = try {
            activityManager.getRunningTasks(1).firstOrNull()?.topActivity?.packageName
        } catch (e: Exception) {
            null
        } ?: AppUtils.currentApp

        if (isValidUserApp(fgPkg, launcherPkg)) {
            activePkgs.add(fgPkg!!)
        }

        // 3b. Interactive windows (freeform / split-screen)
        try {
            PerfectServer.dock?.windows?.forEach { win ->
                val p = win.root?.packageName?.toString()
                if (isValidUserApp(p, launcherPkg)) {
                    activePkgs.add(p!!)
                }
            }
        } catch (e: Exception) {}

        // 3c. Tracked session apps (apps opened during this launcher session)
        PerfectServer.dock?.let { dockService ->
            synchronized(dockService.activeSessionApps) {
                dockService.activeSessionApps.reversed().forEach { pkg ->
                    if (isValidUserApp(pkg, launcherPkg)) {
                        activePkgs.add(pkg)
                    }
                }
            }
        }

        // 3d. If Shizuku is available, ensure background session apps are genuinely alive
        if (shizuku.hasPermission) {
            val livePkgs = shizuku.getRunningPackages().toSet()
            if (livePkgs.isNotEmpty()) {
                activePkgs.retainAll { pkg ->
                    pkg == fgPkg || livePkgs.contains(pkg) || PerfectServer.dock?.windows?.any { it.root?.packageName == pkg } == true
                }
            }
        }

        // If no apps have been opened in this session, return empty so "No open tasks" shows
        if (activePkgs.isEmpty()) {
            return ArrayList()
        }

        val result = ArrayList<AppTask>()
        for (pkg in activePkgs) {
            if (result.size >= max) break
            try {
                val label = AppUtils.getPackageLabel(context, pkg)
                val icon = AppUtils.getAppIcon(context, pkg) ?: continue
                result.add(AppTask(-1, label, pkg, icon))
            } catch (e: Exception) {}
        }

        return result
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

    fun selectNextTaskView() = taskViewOverlay.selectNext()
    fun selectPreviousTaskView() = taskViewOverlay.selectPrevious()
    fun commitTaskViewSelection() = taskViewOverlay.commitSelection()
    fun closeCurrentTaskView() = taskViewOverlay.closeCurrent()

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
                PerfectServer.dock?.removeSessionApp(task.packageName)
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
                PerfectServer.dock?.clearSessionApps()
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
