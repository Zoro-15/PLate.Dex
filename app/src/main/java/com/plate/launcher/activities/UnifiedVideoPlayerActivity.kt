package com.plate.launcher.activities

import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.shape.ShapeAppearanceModel
import com.plate.launcher.R

class UnifiedVideoPlayerActivity : com.plate.launcher.activities.BaseFontScaleActivity() {

    companion object {
        const val EXTRA_TITLE  = "extra_video_title"
        const val EXTRA_URI    = "extra_video_uri"
    }

    private lateinit var player     : ExoPlayer
    private lateinit var playerView : PlayerView
    private lateinit var btnBack    : MaterialButton
    private lateinit var tvTitle    : TextView
    private lateinit var btnPlay    : MaterialButton

    // ══════════════════════════════════════════════════════════════════════
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        setContentView(R.layout.activity_video_player)
        bindViews()
        setupPlayer()
    }

    private fun bindViews() {
        playerView = findViewById(R.id.vp_player_view)
        btnBack    = findViewById(R.id.vp_btn_back)
        tvTitle    = findViewById(R.id.vp_title)
        btnPlay    = findViewById(R.id.vp_btn_play_pause)

        tvTitle.text = intent.getStringExtra(EXTRA_TITLE) ?: ""
        playerView.useController = false
        playerView.resizeMode    = AspectRatioFrameLayout.RESIZE_MODE_FIT

        // Initial shape = circle (playing)
        btnPlay.shapeAppearanceModel =
            ShapeAppearanceModel.builder(this, R.style.ShapeAppearance_Vp_PlayBtn_Circle, 0).build()

        // Tapping the screen = play/pause
        playerView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                if (player.isPlaying) player.pause() else player.play()
            }
            true
        }

        btnBack.setOnClickListener { finish() }

        btnPlay.setOnClickListener {
            if (player.isPlaying) player.pause() else player.play()
        }
    }

    // ── Player — plays with no end ──────────────────────────────────────────
    private fun setupPlayer() {
        val uri = resolveUri() ?: run { finish(); return }
        player = ExoPlayer.Builder(this).build().also { exo ->
            playerView.player = exo
            exo.setMediaItem(MediaItem.fromUri(uri))
            exo.repeatMode    = Player.REPEAT_MODE_ONE   // infinite loop
            exo.playWhenReady = true
            exo.prepare()
            exo.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) = morphBtn(playing)
            })
        }
    }

    // ── Play/Pause button: circle = playing, squircle = paused ────────────────────
    private fun morphBtn(playing: Boolean) {
        btnPlay.animate().scaleX(0.80f).scaleY(0.80f).setDuration(80).withEndAction {
            btnPlay.icon = getDrawable(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow
            )
            val style = if (playing)
                R.style.ShapeAppearance_Vp_PlayBtn_Circle
            else
                R.style.ShapeAppearance_Vp_PlayBtn_Squircle
            btnPlay.shapeAppearanceModel =
                ShapeAppearanceModel.builder(this, style, 0).build()
            btnPlay.animate().scaleX(1f).scaleY(1f)
                .setDuration(160).setInterpolator(DecelerateInterpolator(2.5f)).start()
        }.start()
    }



    // ── Utils ─────────────────────────────────────────────────────────────
    private fun resolveUri(): android.net.Uri? {
        intent.getStringExtra(EXTRA_URI)?.let { return android.net.Uri.parse(it) }
        return intent.data
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onResume()  { super.onResume();  player.play(); hideSystemBars() }
    override fun onPause()   { super.onPause();   player.pause() }
    override fun onDestroy() { super.onDestroy(); player.release() }
}
