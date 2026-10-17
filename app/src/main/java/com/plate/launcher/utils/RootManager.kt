package com.plate.launcher.utils

import android.content.Context
import kotlinx.coroutines.*
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

// Fix 31: isAvailable used to run su every time — now cached for 60s
// Fix 32: was a fire-and-forget CoroutineScope — now scope is passed in
// Fix 40: executeRoot had no timeout — now 10s max
// Fix 41: stdout/stderr were read sequentially -> deadlock risk — now parallel
// Fix 45: onLog lambda had no cleanup — documented with a warning
class RootManager private constructor(private val context: Context) {

    companion object {
        @Volatile private var instance: RootManager? = null
        fun getInstance(ctx: Context) =
            instance ?: synchronized(this) {
                instance ?: RootManager(ctx.applicationContext).also { instance = it }
            }
    }

    // Fix 45: set to null when the caller is destroyed to avoid a memory leak
    var onLog: ((String) -> Unit)? = null

    // Listener registry for "root permission just granted" — callers (e.g.
    // onboarding screens) can subscribe by key instead of polling isAvailable.
    private val grantedListeners = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()
    fun addOnGrantedListener(key: String, cb: () -> Unit) { grantedListeners[key] = cb }
    fun removeOnGrantedListener(key: String) { grantedListeners.remove(key) }

    // Fix 31: cache isAvailable's result for 60s instead of shelling out to su every call
    @Volatile private var cachedAvailable: Boolean? = null
    @Volatile private var cacheTime: Long = 0L
    private val CACHE_TTL_MS = 60_000L

    val isAvailable: Boolean
        get() {
            val now = System.currentTimeMillis()
            cachedAvailable?.let { if (now - cacheTime < CACHE_TTL_MS) return it }
            val result = checkRoot()
            cachedAvailable = result
            cacheTime = now
            return result
        }

    private fun checkRoot(): Boolean {
        if (File("/data/adb/magisk").exists() || File("/data/adb/ksu").exists())
            log("Magisk/KernelSU directory found")
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            // Fix 41: parallel stdout/stderr reads
            var output = ""
            val t = Thread { output = p.inputStream.bufferedReader().readText() }
            t.start()
            p.errorStream.bufferedReader().readText()
            p.waitFor(5, TimeUnit.SECONDS)
            t.join(1000)
            val granted = output.contains("uid=0")
            log(if (granted) "Root verified (uid=0)" else "su not root: $output")
            granted
        } catch (e: Exception) {
            val paths = arrayOf("/sbin/su", "/system/sbin/su", "/system/bin/su",
                "/system/xbin/su", "/su/bin/su", "/magisk/.core/bin/su")
            val found = paths.any { File(it).canExecute() }
            log(if (found) "Root via static path" else "No root found")
            found
        }
    }

    // Fix 32: accepts an external scope instead of spinning up a fire-and-forget one — default scope kept for backward compatibility
    private val internalScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
    )

    /** Marks whether the auto-grant batch has already run this process lifetime. */
    @Volatile private var alreadyGrantedThisSession = false

    fun runShell(command: String, scope: CoroutineScope = internalScope, onResult: (String) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val output = executeRoot(command)
            withContext(Dispatchers.Main) { onResult(output) }
        }
    }

    fun runShellSync(command: String): String? =
        try { executeRoot(command) } catch (e: Exception) { null }

    /**
     * "Auto Root" — runs the same permission grant-all batch Shizuku's
     * auto-grant runs, via su. Safe to call multiple times; only actually
     * executes once per process lifetime unless [force] is set.
     */
    fun requestPermission(scope: CoroutineScope = internalScope, force: Boolean = false) {
        if (!isAvailable) return
        if (!force && alreadyGrantedThisSession) return
        scope.launch(Dispatchers.IO) { grantAll() }
    }

    private suspend fun grantAll() {
        if (!isAvailable) return
        val pkg = context.packageName
        val cmds = ShizukoManager.buildGrantAllCommands(pkg)

        for (cmd in cmds) {
            try {
                withTimeout(12_000L) {
                    withContext(Dispatchers.IO) { executeRoot(cmd) }
                }
            } catch (e: Exception) {
                log("Root command failed/timed out: $cmd (${e.message})")
            }
        }
        alreadyGrantedThisSession = true

        withContext(Dispatchers.Main) {
            grantedListeners.values.forEach { it() }
        }
    }

    /**
     * Diagnostics — returns a clear report of running a command via su.
     * Used only from the "Diagnostics" screen.
     */
    fun diagnose(cmd: String): String {
        if (!isAvailable) return "✗ Root: su not available (isAvailable = false)"
        val out = executeRoot(cmd)
        return "stdout/stderr:\n${out.ifBlank { "(empty)" }}"
    }

    fun executeRoot(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("$command\n")
            os.writeBytes("exit\n")
            os.flush()
            os.close()

            // Fix 41: parallel reads to avoid deadlock
            var stdout = ""
            var stderr = ""
            val t1 = Thread { stdout = process.inputStream.bufferedReader().readText().trim() }
            val t2 = Thread { stderr = process.errorStream.bufferedReader().readText().trim() }
            t1.start(); t2.start()
            // Fix 40: 10 second timeout
            process.waitFor(10, TimeUnit.SECONDS)
            t1.join(2000); t2.join(2000)

            buildString {
                if (stdout.isNotBlank()) append(stdout)
                if (stderr.isNotBlank()) { if (isNotEmpty()) append("\n"); append(stderr) }
                if (isEmpty()) append("(no output)")
            }
        } catch (e: Exception) {
            log("Root execution error: ${e.message}")
            "error"
        }
    }

    fun grantWriteSecureSettings(packageName: String, scope: CoroutineScope = internalScope, onResult: (String) -> Unit) {
        log("Granting WRITE_SECURE_SETTINGS via root to $packageName")
        runShell("pm grant $packageName android.permission.WRITE_SECURE_SETTINGS", scope, onResult)
    }

    // invalidate cache when needed (e.g. after installing Magisk)
    fun invalidateCache() { cachedAvailable = null }

    private fun log(msg: String) = onLog?.invoke(msg)
}
