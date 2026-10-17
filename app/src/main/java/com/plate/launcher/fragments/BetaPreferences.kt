package com.plate.launcher.fragments

import android.content.Intent
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.plate.launcher.R

/**
 * BetaPreferences — hidden until Developer Mode is unlocked (7 taps on the
 * version number in Help & About; see HelpAboutPreferences.handleVersionTap
 * and PreferencesFragment.refreshBetaVisibility). Houses the
 * customization/debugging tools that used to live in the regular Advanced
 * screen but are meant for people actively testing/tweaking the app rather
 * than everyday users: overriding the bundled loading-screen media, Strict
 * debug mode, and the QA self-test runner.
 */
class BetaPreferences : PreferenceFragmentCompat() {

    // Persists a read permission grant so the picked Uri survives app/device
    // restarts (otherwise it's only valid for this process's lifetime).
    // Writes through UserMediaResolver — the same store EasterEggActivity
    // and QaSandboxActivity both read from, so a change made here is picked
    // up everywhere, not just on the loading screen.
    private val pickLoadingGifLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val ctx = requireContext()
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                // Some providers don't support persistable grants; the Uri
                // will still work for this session, just not after a restart.
            }
            val mime = ctx.contentResolver.getType(uri)
            val kind = when {
                mime?.startsWith("image/gif") == true -> com.plate.launcher.utils.UserMediaResolver.MediaKind.GIF
                mime?.startsWith("image/") == true -> com.plate.launcher.utils.UserMediaResolver.MediaKind.IMAGE
                mime?.startsWith("video/") == true -> com.plate.launcher.utils.UserMediaResolver.MediaKind.VIDEO
                else -> null
            }
            if (kind != null) {
                com.plate.launcher.utils.UserMediaResolver.set(ctx, uri, kind)
            }
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_beta, rootKey)

        // ── QA self-test: opens every screen in an isolated ":qa" process to
        // hunt crashes/silent failures. Confirmed via dialog first since it
        // takes a few minutes and is disruptive (screens flash on/off).
        findPreference<Preference>("qa_run_self_test")!!.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.qa_self_test_confirm_title)
                .setMessage(R.string.qa_self_test_confirm_message)
                .setPositiveButton(R.string.qa_self_test_confirm_start) { _, _ ->
                    startActivity(
                        Intent(requireContext(), com.plate.launcher.qa.QaSandboxActivity::class.java)
                    )
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }

        // ── Loading media used by the self-test's "please wait" screen and
        // EasterEggActivity. Leaving it unset falls back to the bundled
        // default GIF/video (see UserMediaResolver) rather than the old
        // spinner-only/no-media fallback — this is purely an override for
        // people who want to swap in something else while testing.
        findPreference<Preference>("qa_change_loading_gif")!!.setOnPreferenceClickListener {
            pickLoadingGifLauncher.launch(arrayOf("image/*", "video/*"))
            true
        }
    }
}
