package com.plate.launcher.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket

/**
 * ShellManager — client for the ShellServer socket on localhost:7171.
 *
 * Reverted from the Rust/native-socket experiment back to a plain Kotlin
 * TCP client talking to ShellServer.kt (launched as a JVM process via
 * `app_process`, see launchViaShizuku/launchViaRoot below). Full
 * replacement for ShizukoManager.runShell() / RootManager.runShell()
 * without AIDL — just a TCP socket on localhost.
 *
 * Usage:
 *   ShellManager.exec(context, "am task remove 42") { result -> ... }
 *   val result = ShellManager.execSync("am task list")
 */
object ShellManager {

    private const val ACTION_READY = "com.plate.launcher.SHELL_SERVER_READY"
    private const val HOST  = "127.0.0.1"
    private const val PORT  = 7171
    private const val MAGIC = "YOUKI_SHELL_V1"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var sessionToken: String? = null
    @Volatile private var isConnected: Boolean = false

    private var readyReceiver: BroadcastReceiver? = null
    private var receiverContext: Context? = null

    // ─── Init: receive the token from ShellServer at launch ─────────────────
    fun init(context: Context) {
        // Unregister any previous registration first (in case init() is
        // called more than once across a service restart) before
        // registering a new one — otherwise every restart stacks another
        // receiver on top of the old one without ever unregistering it,
        // leaking a reference to the old Service/Context and duplicating
        // event delivery.
        destroy()

        val appContext = context.applicationContext
        val filter = IntentFilter(ACTION_READY)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val token = intent?.getStringExtra("token") ?: return
                sessionToken = token
                isConnected = false // force reconnect with new token
                // test connection immediately
                scope.launch { ping() }
            }
        }
        ContextCompat.registerReceiver(
            appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        readyReceiver = receiver
        receiverContext = appContext

        // Try connecting if server was already running before init
        scope.launch { ping() }
    }

    /** Unregisters the Receiver — called from PerfectServer.onDestroy() */
    fun destroy() {
        try {
            readyReceiver?.let { receiverContext?.unregisterReceiver(it) }
        } catch (e: Exception) { /* already unregistered — ignore */ }
        readyReceiver = null
        receiverContext = null
        // Cancel any pending ping()/execSync() work rather than letting it
        // keep running on the IO dispatcher after destroy() — sessionToken/
        // isConnected are no longer trustworthy once the service is gone.
        scope.coroutineContext[Job]?.cancelChildren()
        isConnected = false
    }

    val isAvailable: Boolean get() = sessionToken != null && isConnected

    // ─── Async exec ────────────────────────────────────────────────────────
    fun exec(
        context: Context? = null,
        cmd: String,
        onResult: (String) -> Unit
    ) {
        scope.launch {
            val result = execSync(cmd)
            withContext(Dispatchers.Main) { onResult(result ?: "") }
        }
    }

    // ─── Sync exec (call from IO thread only) ──────────────────────────────
    fun execSync(cmd: String): String? {
        val token = sessionToken ?: return null
        return try {
            Socket(HOST, PORT).use { socket ->
                socket.soTimeout = 10_000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = PrintWriter(socket.getOutputStream(), true)

                // Handshake
                val magic = reader.readLine()
                if (magic != MAGIC) return null

                writer.println("TOKEN:$token")
                val ack = reader.readLine()
                if (ack != "TOKEN_OK") return null

                isConnected = true

                // Send command
                writer.println("CMD:$cmd")

                // Collect output until __DONE__
                val sb = StringBuilder()
                var line: String?
                while (true) {
                    line = reader.readLine() ?: break
                    if (line == "__DONE__") break
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(line)
                }

                writer.println("EXIT")
                sb.toString().trim()
            }
        } catch (e: Exception) {
            isConnected = false
            null
        }
    }

    // ─── Ping to check connection ───────────────────────────────────────────
    private fun ping(): Boolean {
        val token = sessionToken ?: return false
        return try {
            Socket(HOST, PORT).use { socket ->
                socket.soTimeout = 3_000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = PrintWriter(socket.getOutputStream(), true)
                if (reader.readLine() != MAGIC) return false
                writer.println("TOKEN:$token")
                if (reader.readLine() != "TOKEN_OK") return false
                writer.println("PING")
                val pong = reader.readLine()
                writer.println("EXIT")
                (pong == "PONG").also { isConnected = it }
            }
        } catch (e: Exception) {
            isConnected = false
            false
        }
    }

    /**
     * Launches ShellServer via Shizuku (only once, as a bootstrap).
     * After launch, ShellManager talks to it over the socket — no AIDL.
     */
    fun launchViaShizuku(context: Context) {
        if (isAvailable) return // already running
        val shizuku = com.plate.launcher.utils.ShizukoManager.getInstance(context)
        if (!shizuku.hasPermission) return
        val apk = context.packageCodePath
        val cmd = "CLASSPATH=$apk app_process / com.plate.launcher.server.ShellServer"
        shizuku.runShell(cmd) {}
    }

    /** Launches ShellServer via Root. */
    fun launchViaRoot(context: Context) {
        if (isAvailable) return
        val root = com.plate.launcher.utils.RootManager.getInstance(context)
        if (!root.isAvailable) return
        val apk = context.packageCodePath
        val cmd = "CLASSPATH=$apk app_process / com.plate.launcher.server.ShellServer &"
        root.runShell(cmd) {}
    }
}
