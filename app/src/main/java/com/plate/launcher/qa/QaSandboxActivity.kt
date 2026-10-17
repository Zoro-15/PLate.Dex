package com.plate.launcher.qa

import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.plate.launcher.R
import com.plate.launcher.utils.UserMediaResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * QaSandboxActivity — hosts the full self-test run.
 *
 * IMPORTANT: this Activity is declared in AndroidManifest.xml with
 * android:process=":qa" — it runs in its own separate Android process,
 * not the user's live app process. That's the actual isolation boundary:
 * if a probed screen triggers a native crash, ANR, or StackOverflow that
 * even a coroutine timeout can't catch, only the ":qa" process dies. The
 * user's foreground launcher keeps running untouched. See
 * QaTestOrchestrator's kdoc for the full isolation model, including the
 * per-target try/catch + timeout layer *within* this process, and
 * QaVirtualDisplayHost's kdoc for how each individual probed screen is
 * itself launched and isolated via VirtualDisplay.
 *
 * UI: this loading screen's own window is opaque (see
 * activity_qa_sandbox.xml's background) and is the only thing the user
 * sees for the whole run: the user's picked GIF/image in the middle (falls back to the
 * built-in spinner if nothing was picked or it fails to load — see
 * [bindLoadingMedia]), the file currently being probed, and a live count of
 * problems found so far. Live-updates from [QaTestOrchestrator.progress]
 * while the run is in flight, then swaps to a summary dialog with
 * pass/fail counts and the saved log path (Download/YoukiDex/).
 */
class QaSandboxActivity : AppCompatActivity() {

    private lateinit var orchestrator: QaTestOrchestrator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qa_sandbox)

        val currentTargetView = findViewById<TextView>(R.id.qa_current_target)
        val counterView = findViewById<TextView>(R.id.qa_progress_counter)
        val issuesView = findViewById<TextView>(R.id.qa_issues_counter)
        val progressBar = findViewById<ProgressBar>(R.id.qa_progress_bar)
        val mediaView = findViewById<ImageView>(R.id.qa_loading_media)
        val videoView = findViewById<VideoView>(R.id.qa_loading_video)
        val spinnerView = findViewById<ImageView>(R.id.qa_loading_spinner)

        // Spinner always starts running immediately — it's the baseline
        // state, not a fallback shown only after a failed load. If the
        // user's picked media loads successfully, bindLoadingMedia() hides
        // it; otherwise it just keeps spinning, so there's never a frame
        // with nothing animating on screen.
        (spinnerView.drawable as? Animatable)?.start()

        bindLoadingMedia(mediaView, videoView, spinnerView)

        orchestrator = QaTestOrchestrator(this)

        lifecycleScope.launch {
            orchestrator.progress.collect { progress ->
                if (progress.total == 0) return@collect
                currentTargetView.text = if (progress.currentTarget.isNotEmpty()) {
                    getString(R.string.qa_checking_target, progress.currentTarget)
                } else {
                    getString(R.string.qa_finishing_up)
                }
                counterView.text = "${progress.currentIndex} / ${progress.total}"
                progressBar.max = progress.total
                progressBar.progress = progress.currentIndex

                // Live-updating "problems found so far" — recomputed from
                // the results collected up to this point, not just shown
                // once at the end, so it climbs in real time as each probe
                // reports back.
                val issuesSoFar = progress.results.count { it.outcome != QaOutcome.PASS }
                issuesView.text = getString(R.string.qa_issues_found, issuesSoFar)

                if (progress.currentIndex == progress.total && progress.currentTarget.isEmpty()) {
                    showSummary(progress.results)
                }
            }
        }

        lifecycleScope.launch {
            try {
                orchestrator.run(applicationContext)
            } catch (t: Throwable) {
                // The orchestrator already catches per-target failures (see
                // its kdoc) — reaching here means something broke in the
                // run loop itself, not a probed target.
                Log.e("QaSandboxActivity", "Self-test run failed unexpectedly", t)
                finish()
            }
        }
    }

    /**
     * Shows the loading-screen media on top of the spinner: the person's
     * override if they've set one in Beta settings, otherwise the bundled
     * default GIF (see [UserMediaResolver] — it never returns null). If
     * decoding fails for any reason, the already-running XML-driven spinner
     * is simply left as the entire loading screen, exactly as before.
     */
    private fun bindLoadingMedia(mediaView: ImageView, videoView: VideoView, spinnerView: ImageView) {
        val selection = UserMediaResolver.get(this)

        when (selection.kind) {
            UserMediaResolver.MediaKind.VIDEO -> {
                try {
                    videoView.setVideoURI(selection.uri)
                    videoView.setOnPreparedListener { mp ->
                        mp.isLooping = true
                        mp.setVolume(0f, 0f)
                        videoView.visibility = View.VISIBLE
                        spinnerView.visibility = View.GONE
                    }
                    videoView.setOnErrorListener { _, _, _ -> true } // swallow — spinner stays as fallback
                    videoView.start()
                } catch (e: Exception) {
                    // Leave the already-running spinner as-is.
                }
            }
            UserMediaResolver.MediaKind.IMAGE, UserMediaResolver.MediaKind.GIF -> {
                lifecycleScope.launch {
                    val drawable = withContext(Dispatchers.IO) {
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                val source = ImageDecoder.createSource(contentResolver, selection.uri)
                                ImageDecoder.decodeDrawable(source) { decoder, _, _ ->
                                    decoder.setMemorySizePolicy(ImageDecoder.MEMORY_POLICY_LOW_RAM)
                                }
                            } else {
                                null // handled below via setImageURI fallback
                            }
                        } catch (e: Exception) {
                            null
                        }
                    }

                    if (drawable != null) {
                        mediaView.setImageDrawable(drawable)
                        (drawable as? AnimatedImageDrawable)?.start()
                        mediaView.visibility = View.VISIBLE
                        spinnerView.visibility = View.GONE
                    } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                        // Pre-API 28 fallback: static image render, no animation.
                        try {
                            mediaView.setImageURI(selection.uri)
                            mediaView.visibility = View.VISIBLE
                            spinnerView.visibility = View.GONE
                        } catch (e: Exception) {
                            // Leave the spinner running.
                        }
                    }
                    // else: decode failed on API 28+ — leave the spinner running.
                }
            }
        }
    }

    private fun showSummary(results: List<QaResult>) {
        val issuesFound = results.count { it.outcome != QaOutcome.PASS }
        val counts = QaOutcome.entries.associateWith { outcome -> results.count { it.outcome == outcome } }
        val message = buildString {
            append(getString(R.string.qa_summary_header, results.size))
            append("\n")
            append(getString(R.string.qa_issues_found, issuesFound))
            append("\n\n")
            counts.forEach { (outcome, count) ->
                if (count > 0) append("$outcome: $count\n")
            }
            append("\n")
            append(getString(R.string.qa_log_saved_to, "Download/PLateLauncher/"))
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.qa_summary_title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(R.string.ok) { _, _ -> finish() }
            .show()
    }
}
