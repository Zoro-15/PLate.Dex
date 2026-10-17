package com.plate.launcher.qa

import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * QaLogWriter — writes the self-test run's log to
 * /storage/emulated/0/Download/YoukiDex/, as a plain human-readable text
 * file (one file per run, timestamped) plus a rolling "latest.txt" that
 * always points at the most recent run for convenience.
 *
 * Requires WRITE access to shared storage. On API 29+ this path is only
 * writable without a runtime permission via scoped-storage-friendly APIs;
 * since this is a Download/<AppName>/ path (not app-private), the caller
 * is expected to hold MANAGE_EXTERNAL_STORAGE or have gone through
 * MediaStore — this class assumes that's already granted.
 */
object QaLogWriter {

    private const val TAG = "QaLogWriter"
    private const val DIR_NAME = "PLateLauncher"

    private fun targetDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DIR_NAME)

    /** Creates a new timestamped run file and returns it, ready to append to. */
    fun startRun(): File? {
        return try {
            val dir = targetDir()
            if (!dir.exists() && !dir.mkdirs()) {
                Log.e(TAG, "Failed to create $dir")
                return null
            }
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val file = File(dir, "qa_log_$stamp.txt")
            file.writeText(
                buildString {
                    append("PLate.Launcher self-test run\n")
                    append("Started: ${Date()}\n")
                    append("=".repeat(60)).append("\n\n")
                }
            )
            file
        } catch (e: Exception) {
            Log.e(TAG, "startRun failed", e)
            null
        }
    }

    /** Appends one target's result line(s) to the run file. */
    fun appendResult(file: File?, result: QaResult) {
        if (file == null) return
        try {
            file.appendText(
                buildString {
                    append("[${result.outcome}] ")
                    append("${result.target.kind}: ${result.target.displayName} ")
                    append("(${result.durationMs}ms)\n")
                    if (result.detail != null) append("  detail: ${result.detail}\n")
                    if (result.stackTrace != null) {
                        append("  stacktrace:\n")
                        result.stackTrace.lineSequence().forEach { append("    $it\n") }
                    }
                    append("\n")
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "appendResult failed", e)
        }
    }

    /** Writes the final summary block and mirrors the file to latest.txt. */
    fun finishRun(file: File?, results: List<QaResult>) {
        if (file == null) return
        try {
            val counts = QaOutcome.entries.associateWith { outcome -> results.count { it.outcome == outcome } }
            file.appendText(
                buildString {
                    append("=".repeat(60)).append("\n")
                    append("Summary: ${results.size} targets probed\n")
                    counts.forEach { (outcome, count) -> if (count > 0) append("  $outcome: $count\n") }
                    append("Finished: ${Date()}\n")
                }
            )
            File(targetDir(), "latest.txt").writeText(file.readText())
        } catch (e: Exception) {
            Log.e(TAG, "finishRun failed", e)
        }
    }
}
