package com.plate.launcher.livewallpaper.engine

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.Surface
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer

/**
 * VideoEngine — wraps ExoPlayer to play a video wallpaper with a seamless loop.
 *
 * ┌────────────────────────────────────────────────────────────────────────┐
 * │  Why ExoPlayer and not MediaPlayer?                                    │
 * │  Media3 ExoPlayer supports REPEAT_MODE_ONE with instant replay and no  │
 * │  "bump" or momentary black flash between loops — exactly what the user│
 * │  explicitly asked for (a seamless loop).                              │
 * └────────────────────────────────────────────────────────────────────────┘
 */
class VideoEngine(private val context: Context) {

    companion object { private const val TAG = "VideoEngine" }

    private var player: ExoPlayer? = null
    private var onVideoSizeChanged: ((Int, Int) -> Unit)? = null
    private var onError: ((PlaybackException) -> Unit)? = null

    /**
     * The last Surface passed to [prepare] — read-only from outside. Lets an
     * external caller (such as switching the previewed video in the Editor's
     * landscape grid) call [prepare] again with a new video on the same GL
     * Surface that already exists, instead of waiting for a whole new
     * onSurfaceReady cycle from the system.
     */
    var currentSurface: Surface? = null
        private set

    /** Creates the player and binds it to the external OpenGL surface (from WallpaperGLRenderer) */
    fun prepare(
        videoUri: String,
        outputSurface: Surface,
        muted: Boolean,
        playbackSpeed: Float,
        audioPitch: Float,
        onSizeChanged: (Int, Int) -> Unit,
        onPlaybackError: ((PlaybackException) -> Unit)? = null
    ) {
        release() // make sure no previous player leaks
        currentSurface = outputSurface

        onVideoSizeChanged = onSizeChanged
        onError = onPlaybackError

        Log.d(TAG, "prepare() uri=$videoUri muted=$muted speed=$playbackSpeed pitch=$audioPitch")

        player = ExoPlayer.Builder(context).build().apply {
            setVideoSurface(outputSurface)
            repeatMode = Player.REPEAT_MODE_ONE   // the seamless loop we need

            // Bug fix — no audio output at all on some devices. ExoPlayer without
            // explicit AudioAttributes uses the default AudioFocus behavior
            // (handleAudioFocus=true), which is designed for regular "active"
            // apps. A WallpaperService isn't a foreground Activity, so the
            // automatic AudioFocus request can be silently denied or
            // immediately revoked by the system with no visible error — the
            // result: an audio path that's "correct" in the code but produces
            // no actual sound. Fix: set AudioAttributes with an explicit
            // USAGE_MEDIA type, setHandleAudioBecomingNoisy(false) because a
            // live wallpaper isn't considered media playback that should stop
            // when headphones are unplugged, and setAudioAttributes with its
            // second parameter set to false to fully disable automatic
            // AudioFocus management — audio keeps playing steadily regardless
            // of any other app, which is expected for a screen wallpaper (you
            // don't want a phone call or another music app to "pause" it).
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ false
            )
            setHandleAudioBecomingNoisy(false)

            volume = if (muted) 0f else 1f
            // Audio is now fully decoupled from speed: pitch is an independent
            // setting the user adjusts manually (audioPitch), no longer
            // computed automatically from playbackSpeed. Speed changes both
            // visually and temporally, and the depth (pitch) only changes if
            // the user explicitly changes it via its own setting.
            playbackParameters = PlaybackParameters(playbackSpeed.coerceIn(0.25f, 3f), audioPitch.coerceIn(0.5f, 2f))
            // skipSilenceEnabled explicitly false: leaving it true (or even the
            // implicit default on some versions) used to contribute extra
            // stutter at low pitch, because automatically trimming silent
            // gaps conflicts with Sonic's time-domain resampling on the same
            // audio track.
            skipSilenceEnabled = false

            addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    Log.d(TAG, "onVideoSizeChanged: ${videoSize.width}x${videoSize.height}")
                    onVideoSizeChanged?.invoke(videoSize.width, videoSize.height)
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    val name = when (playbackState) {
                        Player.STATE_IDLE -> "IDLE"
                        Player.STATE_BUFFERING -> "BUFFERING"
                        Player.STATE_READY -> "READY"
                        Player.STATE_ENDED -> "ENDED"
                        else -> "UNKNOWN($playbackState)"
                    }
                    Log.d(TAG, "onPlaybackStateChanged: $name")
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    Log.d(TAG, "onIsPlayingChanged: $isPlaying")
                }

                override fun onPlayerError(error: PlaybackException) {
                    // This is the log that was completely missing before — any
                    // playback failure (a deleted file, an access permission
                    // issue, an unsupported codec...) used to pass by in
                    // complete silence and leave the renderer stuck on
                    // videoWidth=0 forever.
                    Log.e(TAG, "onPlayerError [${error.errorCodeName}]: ${error.message}", error)
                    com.plate.launcher.utils.StrictDebugMode.logFailure(
                        context, "VideoEngine",
                        "ExoPlayer failed for uri=$videoUri: [${error.errorCodeName}] ${error.message}",
                        error
                    )
                    onError?.invoke(error)
                }
            })

            setMediaItem(MediaItem.fromUri(Uri.parse(videoUri)))
            prepare()
            playWhenReady = true
        }
    }

    // ── Playback control (exposed to UnifiedVideoPlayerActivity) ─────────
    fun seekTo(posMs: Long)      { player?.seekTo(posMs) }
    val currentPosition: Long    get() = player?.currentPosition ?: 0L
    val duration: Long           get() = player?.duration?.coerceAtLeast(0L) ?: 0L

    fun setMuted(muted: Boolean) { player?.volume = if (muted) 0f else 1f }

    /** Changes the speed only, keeping the current pitch as-is (untouched) */
    fun setSpeed(speed: Float) {
        val current = player?.playbackParameters ?: PlaybackParameters.DEFAULT
        player?.playbackParameters = PlaybackParameters(speed.coerceIn(0.25f, 3f), current.pitch)
    }

    /** Changes the audio pitch only, keeping the current speed as-is (untouched) */
    fun setPitch(pitch: Float) {
        val current = player?.playbackParameters ?: PlaybackParameters.DEFAULT
        player?.playbackParameters = PlaybackParameters(current.speed, pitch.coerceIn(0.5f, 2f))
    }

    fun pause()  { player?.playWhenReady = false }
    fun resume() { player?.playWhenReady = true }

    fun release() {
        player?.release()
        player = null
    }
}
