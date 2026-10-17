package com.plate.launcher.activities

import android.app.Activity
import android.graphics.ImageDecoder
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.plate.launcher.utils.UserMediaResolver
import com.plate.launcher.R

/**
 * EasterEggActivity — v6
 *
 * FIX (freeze/black screen with no way out):
 *  1) Removed dispatchTouchEvent — it was intercepting *every* touch event at
 *     the system level (even system buttons/gestures), so if MediaPlayer
 *     failed silently, the app became completely locked with no interactive
 *     way out.
 *  2) Fail-safe timer: if the video hasn't started within 4 seconds (for any
 *     reason) → automatic exit instead of staying on a black screen forever.
 *  3) Full try/catch around MediaPlayer — any exception = immediate exit
 *     instead of a crash or hang.
 *  4) setOnPreparedListener/setOnErrorListener before prepareAsync().
 *
 * FIX (v6 — exit by swipe, not by tap):
 *  tap-to-exit used to cause accidental exits on fast/repeated taps
 *  (e.g. if the user tapped quickly while the video screen moved due to an
 *  animation).
 *  Now: GestureDetector.onFling — exit only on a clear horizontal swipe
 *  (right or left), exactly like the "back" gesture on modern Android,
 *  + the traditional back button still works.
 */
class EasterEggActivity : Activity(), SurfaceHolder.Callback {

    // Global font scale — see AppFontScaleUtils.kt. This Activity extends
    // plain Activity (not AppCompatActivity), so it can't inherit from
    // BaseFontScaleActivity — instead we call the same applyToViewHierarchy
    // helper directly from onContentChanged(), which every other screen in
    // the app now uses instead of the old wrapContext()/attachBaseContext()
    // approach that used to corrupt width/height once the percentage was
    // anything other than 100%.
    override fun onContentChanged() {
        super.onContentChanged()
        com.plate.launcher.utils.AppFontScaleUtils.applyToViewHierarchy(
            window?.decorView?.findViewById(android.R.id.content)
        )
    }

    private var mediaPlayer: MediaPlayer? = null
    private var videoUri: Uri? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var exiting = false
    private var started = false
    private lateinit var surfaceView: SurfaceView

    // ── "Yuki" contributor unlock: 1 CONTINUOUS minute watching the Easter
    // Egg — "متواصلة مش متقطعة": pausing/backgrounding the screen for even a
    // moment cancels the pending callback and de-arms the timer (see
    // onPause/onResume below), so the next onResume starts a genuinely
    // fresh 1-minute wait rather than resuming a partial one — this can't
    // be gamed by leaving the screen open in the background and hopping
    // back in and out. A plain Handler.postDelayed on mainHandler is
    // enough here (rather than something like elapsedRealtime-based
    // tracking): it only runs while this Activity is actually resumed —
    // the same Handler already gets fully cleared in onPause/onDestroy/
    // exitEgg, so there's no separate cleanup path to keep in sync. See
    // HelpAboutPreferences.kt for where this flag unlocks the third,
    // separate "GF developer" row in the Developers card (visually and
    // logically distinct from the 7-tap Developer Mode unlock — this flag
    // alone doesn't grant developer mode itself).
    private val watchStreakRunnable = Runnable {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        if (!prefs.getBoolean(PREF_YUKI_UNLOCKED, false)) {
            prefs.edit().putBoolean(PREF_YUKI_UNLOCKED, true).apply()
        }
    }
    private var watchStreakArmed = false

    // Minimum horizontal swipe distance and speed to count as an "exit swipe"
    // (not an incidental tap or a small movement)
    private val SWIPE_MIN_DISTANCE = 80   // roughly dp
    private val SWIPE_MIN_VELOCITY = 200  // px/s

    private lateinit var gestureDetector: GestureDetector

    // Fail-safe: if playback hasn't started within 4 seconds, exit automatically
    private val failSafe = Runnable {
        if (!started) exitEgg()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        makeFullscreen()
        setContentView(R.layout.activity_easter_egg)

        val root        = findViewById<View>(android.R.id.content)
        surfaceView     = findViewById(R.id.egg_surface)
        val imageView   = findViewById<ImageView>(R.id.egg_image)
        val noMediaTv   = findViewById<TextView>(R.id.egg_no_media_tv)
        val hintTv      = findViewById<TextView>(R.id.egg_hint_tv)
        // hardcoded Arabic text — fixed below: this text used to be fixed Arabic regardless
        // of the device/app language. Now it's read from strings.xml
        // (swipe_left_right_to_exit), so it's translated automatically based on
        // the current language, just like the rest of the UI text.
        hintTv.text = getString(R.string.swipe_left_right_to_exit)

        // ── Exit: horizontal swipe (right/left) only — like the back gesture ──
        val minDistancePx = (SWIPE_MIN_DISTANCE * resources.displayMetrics.density)
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent,
                velocityX: Float, velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (isExitSwipe(
                        dx, dy, velocityX, minDistancePx, SWIPE_MIN_VELOCITY.toFloat()
                    )
                ) {
                    exitEgg()
                    return true
                }
                return false
            }
        })
        val touchListener = View.OnTouchListener { _, ev -> gestureDetector.onTouchEvent(ev) }
        root.setOnTouchListener(touchListener)
        imageView.setOnTouchListener(touchListener)
        noMediaTv.setOnTouchListener(touchListener)

        // Exit hint after two seconds (shared by the image/GIF, video, and
        // no-media paths below).
        mainHandler.postDelayed({
            hintTv.animate().alpha(0.65f).setDuration(800).start()
        }, 2000)

        // ── Branch: the person's override from Beta settings, if any,
        // otherwise the bundled default video (see UserMediaResolver) —
        // preferred over the bundled default GIF here specifically, since a
        // real video suits this full-screen surprise better than a loop.
        val userMedia = if (UserMediaResolver.hasOverride(this)) {
            UserMediaResolver.get(this)
        } else {
            UserMediaResolver.defaultEasterEggMedia(this)
        }

        // An IMAGE or GIF pick is a completely separate, simpler path: no
        // MediaPlayer, no SurfaceHolder callback, no async "prepare" step
        // that can fail — so none of the fail-safe-timer/error-listener
        // machinery below applies to it, it's shown directly. A VIDEO pick
        // falls through to the existing SurfaceView + MediaPlayer path.
        if (userMedia.kind != UserMediaResolver.MediaKind.VIDEO) {
            surfaceView.visibility = View.GONE
            showImageOrGif(imageView, userMedia.uri)
            return
        }

        surfaceView.setOnTouchListener(touchListener)
        surfaceView.holder.addCallback(this)
        videoUri = userMedia.uri

        // Fail-safe — 4 seconds (video path only)
        mainHandler.postDelayed(failSafe, 4000)
    }

    /**
     * Shows a still image or animated GIF full-screen. Uses platform
     * ImageDecoder (API 28+) the same way QaSandboxActivity's loading
     * overlay does, for the same reason: this project has no existing
     * Glide/Coil dependency to reuse, and ImageDecoder handles both plain
     * images and animated GIFs without adding one.
     */
    private fun showImageOrGif(imageView: ImageView, uri: Uri) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(contentResolver, uri)
                val drawable = ImageDecoder.decodeDrawable(source)
                imageView.setImageDrawable(drawable)
                (drawable as? android.graphics.drawable.AnimatedImageDrawable)?.start()
            } else {
                imageView.setImageURI(uri)
            }
            imageView.visibility = View.VISIBLE
        } catch (e: Exception) {
            // Bad/unreachable user Uri — same "never leave the user stuck"
            // principle as the video path's fail-safe: exit cleanly instead
            // of showing a broken/blank Easter egg screen.
            exitEgg()
        }
    }

    companion object {
        const val PREF_YUKI_UNLOCKED = "easter_egg_yuki_watch_unlocked"
        private const val WATCH_STREAK_MS = 1 * 60 * 1000L // 1 continuous minute
    }

    // ── SurfaceHolder.Callback ────────────────────────────────────

    override fun surfaceCreated(holder: SurfaceHolder) {
        try {
            val uri = videoUri ?: run { exitEgg(); return }
            mediaPlayer = MediaPlayer().apply {
                setOnPreparedListener { mp ->
                    started = true
                    mainHandler.removeCallbacks(failSafe)
                    fitSurfaceToVideo(mp.videoWidth, mp.videoHeight)
                    isLooping = true
                    start()
                }
                setOnErrorListener { _, _, _ ->
                    exitEgg(); true
                }
                setDataSource(this@EasterEggActivity, uri)
                setDisplay(holder)
                prepareAsync()
            }
        } catch (e: Exception) {
            // Any setup error (file not found, unsupported format...) → safe exit
            exitEgg()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        // Nothing — SurfaceView handles resizing automatically
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        releasePlayer()
    }

    // ── Lifecycle ─────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        runCatching { mediaPlayer?.takeIf { started && !it.isPlaying }?.start() }
        // Arm/re-arm the continuous-watch timer. Deliberately posted fresh
        // every onResume rather than "resumed from a saved remaining
        // duration" — any trip through onPause (backgrounding, a phone
        // call, the screen locking) means the streak wasn't continuous, so
        // it has to restart from zero, not pick back up where it left off.
        if (!watchStreakArmed) {
            watchStreakArmed = true
            mainHandler.postDelayed(watchStreakRunnable, WATCH_STREAK_MS)
        }
    }

    override fun onPause() {
        super.onPause()
        runCatching { mediaPlayer?.takeIf { it.isPlaying }?.pause() }
        // Break in the streak — cancel and de-arm so onResume starts a
        // genuinely fresh 1-minute count rather than assuming continuity.
        mainHandler.removeCallbacks(watchStreakRunnable)
        watchStreakArmed = false
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        releasePlayer()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { exitEgg() }

    // ── Helpers ───────────────────────────────────────────────────

    private fun releasePlayer() {
        mediaPlayer?.runCatching {
            if (started && isPlaying) stop()
            release()
        }
        mediaPlayer = null
    }

    /**
     * Adjusts the SurfaceView's dimensions so they preserve the video's
     * original aspect ratio (letterbox/pillarbox) — the remaining empty space
     * shows as black (the FrameLayout's background) instead of stretching the
     * video to fill the screen.
     */
    private fun fitSurfaceToVideo(videoW: Int, videoH: Int) {
        val parent = surfaceView.parent as? View ?: return
        val screenW = parent.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val screenH = parent.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels

        val target = fitSurfaceToVideoSize(videoW, videoH, screenW, screenH) ?: return

        surfaceView.layoutParams = (surfaceView.layoutParams as FrameLayout.LayoutParams).apply {
            width  = target[0]
            height = target[1]
            gravity = Gravity.CENTER
        }
        surfaceView.requestLayout()
    }

    private fun makeFullscreen() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { c ->
                c.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                c.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        }
    }

    private fun exitEgg() {
        if (exiting) return
        exiting = true
        mainHandler.removeCallbacksAndMessages(null)
        releasePlayer()
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, R.anim.fade_out)
    }

    // ── Video-surface aspect fit / swipe-to-exit helpers ───────────────────

    /**
     * Computes the surface size that fits [videoW]x[videoH] inside
     * [screenW]x[screenH] preserving aspect ratio (letterbox/pillarbox).
     */
    private fun fitSurfaceToVideoSize(videoW: Int, videoH: Int, screenW: Int, screenH: Int): IntArray? {
        if (videoW <= 0 || videoH <= 0) return null
        val videoRatio = videoW.toFloat() / videoH.toFloat()
        val screenRatio = screenW.toFloat() / screenH.toFloat()
        return if (videoRatio > screenRatio) {
            // Video is relatively wider -> fill full width, black bars top/bottom
            intArrayOf(screenW, (screenW / videoRatio).toInt())
        } else {
            // Video is relatively taller -> fill full height, black bars left/right
            intArrayOf((screenH * videoRatio).toInt(), screenH)
        }
    }

    private fun isExitSwipe(dx: Float, dy: Float, velocityX: Float, minDistancePx: Float, minVelocity: Float): Boolean =
        kotlin.math.abs(dx) > kotlin.math.abs(dy) &&
        kotlin.math.abs(dx) > minDistancePx &&
        kotlin.math.abs(velocityX) > minVelocity
}
