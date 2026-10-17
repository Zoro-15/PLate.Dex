package com.plate.launcher.server

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

// ثغرة 61: كان يستمع على 0.0.0.0 — أي جهاز على الشبكة يقدر يتصل
// ثغرة 62: لم يكن هناك أي authentication
// ثغرة 70: getUid() كان يشغّل process جديد في كل استدعاء
// ثغرة 73: stdout و stderr كانت تُقرآن تسلسلياً → deadlock محتمل
object ShellServer {

    private const val PORT  = 7171
    private const val MAGIC = "YOUKI_SHELL_V1"

    // session token يتولّد عشوائياً عند كل تشغيل — يُرسَل لـ ShellManager عبر broadcast
    private val SESSION_TOKEN: String = run {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        bytes.joinToString("") { "%02x".format(it) }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val uid = getUid()   // استدعاء واحد فقط — ثغرة 70
        println("[$MAGIC] ShellServer starting... uid=$uid")

        if (uid != 0 && uid != 2000) {
            System.err.println("[$MAGIC] ERROR: Must run as root (0) or shell (2000), got uid=$uid")
            System.exit(1)
        }

        println("[$MAGIC] Running as uid=$uid")
        println("[$MAGIC] Listening on localhost:$PORT...")

        try {
            // ثغرة 61: bind على localhost فقط — مش 0.0.0.0
            val server = ServerSocket(PORT, 50, InetAddress.getByName("127.0.0.1"))
            println("[$MAGIC] Ready!")

            // أرسل الـ token مع الـ broadcast عشان ShellManager يعرفه
            Runtime.getRuntime().exec(arrayOf(
                "am", "broadcast",
                "-a", "com.plate.launcher.SHELL_SERVER_READY",
                "--es", "token", SESSION_TOKEN,
                "--receiver-include-background"
            ))

            while (true) {
                try {
                    val client = server.accept()
                    Thread { handleClient(client) }.start()
                } catch (e: Exception) {
                    System.err.println("[$MAGIC] Accept error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            System.err.println("[$MAGIC] Fatal: ${e.message}")
            System.exit(1)
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(socket.getOutputStream(), true)

            writer.println(MAGIC)

            // ثغرة 62: تحقق من الـ session token قبل أي أمر
            val tokenLine = reader.readLine() ?: return
            if (!tokenLine.startsWith("TOKEN:") || tokenLine.removePrefix("TOKEN:") != SESSION_TOKEN) {
                writer.println("ERR:Invalid token")
                return
            }
            writer.println("TOKEN_OK")

            while (!socket.isClosed) {
                val line = reader.readLine() ?: break
                when {
                    line == "PING" -> writer.println("PONG")
                    line == "UID"  -> writer.println("uid=${getUid()}")
                    line == "EXIT" -> { writer.println("BYE"); break }
                    line.startsWith("CMD:") -> {
                        val cmd = line.removePrefix("CMD:")
                        val result = runCommand(cmd)
                        writer.println(result)
                        writer.println("__DONE__")
                    }
                    else -> writer.println("ERR:Unknown command")
                }
            }
        } catch (e: Exception) {
            System.err.println("[$MAGIC] Client error: ${e.message}")
        } finally {
            try { socket.close() } catch (e: Exception) {}
        }
    }

    private fun runCommand(cmd: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))

            // ثغرة 73: قراءة stdout و stderr بالتوازي لتجنب deadlock
            var stdout = ""
            var stderr = ""
            val stdoutThread = Thread { stdout = process.inputStream.bufferedReader().readText() }
            val stderrThread = Thread { stderr = process.errorStream.bufferedReader().readText() }
            stdoutThread.start()
            stderrThread.start()
            process.waitFor(10, TimeUnit.SECONDS)
            stdoutThread.join(2000)
            stderrThread.join(2000)

            buildString {
                if (stdout.isNotBlank()) append(stdout.trim())
                if (stderr.isNotBlank()) { if (isNotEmpty()) append("\n"); append(stderr.trim()) }
                if (isEmpty()) append("(no output)")
            }
        } catch (e: Exception) { "ERROR: ${e.message}" }
    }

    private fun getUid(): Int = try {
        Runtime.getRuntime().exec(arrayOf("id", "-u"))
            .inputStream.bufferedReader().readText().trim().toInt()
    } catch (e: Exception) { -1 }
}
