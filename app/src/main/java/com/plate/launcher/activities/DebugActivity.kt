package com.plate.launcher.activities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.plate.launcher.R
import com.plate.launcher.utils.AppUtils


class DebugActivity : com.plate.launcher.activities.BaseFontScaleActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dialog = MaterialAlertDialogBuilder(this)
        dialog.setTitle(R.string.something_wrong)
        val report = intent.getStringExtra("report") ?: run { finish(); return }
        dialog.setMessage(report)
        // "Copy" — just puts the report on the clipboard so it can be pasted
        // anywhere (a GitHub Issue, a Discord message, an email...), rather
        // than only ever being saved to a local file no one else can see.
        dialog.setPositiveButton(R.string.copy_log) { _, _ ->
            copyReportToClipboard(report)
            finish()
        }
        // "Open Issue" — copies the report the same way, then opens the
        // GitHub Issues page directly so it's one tap away from being pasted
        // into a new bug report. GitHub's "New issue" form has no query
        // param for prefilling the body from an external link without a
        // repo-side issue template wired up for it, so the copy+navigate
        // combo is the reliable cross-repo way to get the report in front
        // of the user right where they need to paste it.
        dialog.setNeutralButton(R.string.open_github_issue) { _, _ ->
            copyReportToClipboard(report)
            AppUtils.openUrl(this, "https://github.com/Zoro-15/PLate.Dex/issues")
            finish()
        }
        dialog.setNegativeButton(R.string.open_again) { _, _ ->
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
        dialog.setCancelable(false)
        dialog.create().show()
    }

    private fun copyReportToClipboard(report: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("PLate.Dex crash log", report))
        // No need to show a toast on Android 13+ (API 33+) — the system
        // already shows its own "Copied to clipboard" confirmation there,
        // and showing both would be redundant.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, R.string.log_copied_toast, Toast.LENGTH_SHORT).show()
        }
    }
}
