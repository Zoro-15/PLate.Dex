package com.youki.dex.livewallpaper.service

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.net.Uri
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import com.youki.dex.livewallpaper.data.VideoFileStore
import com.youki.dex.livewallpaper.data.WallpaperConfig
import com.youki.dex.livewallpaper.data.WallpaperConfigRepository
import com.youki.dex.livewallpaper.engine.VideoEngine
import com.youki.dex.livewallpaper.gl.WallpaperGLRenderer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * SecondaryDisplayWallpaperPresentation — shows the same live wallpaper video
 * on a secondary/external display via the Android [Presentation] API.
 *
 * ┌────────────────────────────────────────────────────────────────────────┐
 * │  Why this exists (and why it's a separate class from                   │
 * │  YoukiGLWallpaperService, not a change to it):                         │
 * │                                                                        │
 * │  GitHub issue #15 report: "The wallpaper on the secondary screen is    │
 * │  not working! only on my cell phone." This is a real platform limit,   │
 * │  documented by Google itself (AOSP source.android.com, "System         │
 * │  decorations support"): Android's WallpaperService framework doesn't   │
 * │  natively support per-display wallpapers — "Android 10 doesn't provide │
 * │  direct platform support for selecting wallpapers for individual       │
 * │  screens," and if the wallpaper engine shown on the default display    │
 * │  doesn't support multiple displays, the system just shows the default  │
 * │  (static) wallpaper on secondary displays instead. That's exactly what │
 * │  was being reported — not a bug in YoukiGLWallpaperService's code, but  │
 * │  the ceiling of what WallpaperService itself can do.                   │
 * │                                                                        │
 * │  The fix isn't a patch to the wallpaper service (there's no fix inside │
 * │  that API for this), it's a different mechanism entirely: the          │
 * │  Presentation API, Android's own recommended way to put custom content │
 * │  on a secondary display (used for things like PresentationActivity     │
 * │  demos, dual-screen POS displays, etc.) — a Dialog subclass tied to a  │
 * │  specific Display, backed by a real SurfaceView/SurfaceHolder, which   │
 * │  is exactly what WallpaperGLEngine was already built to drive (see its │
 * │  own doc comment — it only ever needed a raw SurfaceHolder, never a    │
 * │  real WallpaperService).                                                │
 * │                                                                        │
 * │  Result: this reuses WallpaperGLEngine + WallpaperGLRenderer +         │
 * │  VideoEngine completely unchanged — same GL rendering, same video      │
 * │  decoding, same config/transform/orientation logic — just hosted by a  │
 * │  Presentation's SurfaceView instead of a WallpaperService.Engine's     │
 * │  SurfaceHolder. It's shown *underneath* the desktop's own dock/icons/  │
 * │  windows on the secondary display (see how PerfectServer's secondary-  │
 * │  display window is added at a higher z-order) rather than being an     │
 * │  actual system wallpaper on that display, which — per the AOSP note    │
 * │  above — Android does not support at all right now.                    │
 * └────────────────────────────────────────────────────────────────────────┘
 *
 * Usage (called from the launcher/service that manages the secondary display,
 * e.g. PerfectServer, when a secondary display is attached):
 *   val presentation = SecondaryDisplayWallpaperPresentation(context, display)
 *   presentation.show()
 *   ...
 *   presentation.dismiss()   // when the secondary display goes away
 */
class SecondaryDisplayWallpaperPresentation(
    outerContext: Context,
    display: Display
) : Presentation(outerContext, display) {

    companion object { private const val TAG = "SecondaryDisplayWallpaper" }

    private val presentationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var surfaceView: SurfaceView
    private lateinit var glEngine: WallpaperGLEngine
    private lateinit var renderer: WallpaperGLRenderer
    private lateinit var videoEngine: VideoEngine

    private var currentVideoUri: String? = null
    private var isVisible = false
    private var pendingSurface: Surface? = null
    private var pendingVideoUri: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        surfaceView = SurfaceView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(surfaceView)

        videoEngine = VideoEngine(context)

        // Same renderer/engine pairing as YoukiGLWallpaperService — see that
        // class's onCreate() for why onFrameAvailable drives rendering
        // directly instead of a separate timing loop.
        renderer = WallpaperGLRenderer(
            onSurfaceReady = { surface -> onGLSurfaceReady(surface) },
            onFrameAvailable = {
                if (isVisible) glEngine.requestRender()
            }
        )
        glEngine = WallpaperGLEngine(renderer)

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                glEngine.surfaceCreated(holder)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                val previousOrientation =
                    WallpaperConfig.orientationFor(renderer.screenWidth, renderer.screenHeight)
                glEngine.surfaceChanged(width, height)
                val newOrientation = WallpaperConfig.orientationFor(width, height)
                if (newOrientation != previousOrientation) {
                    val audio = renderer.config.audioPlaybackFor(newOrientation)
                    videoEngine.setMuted(audio.muted)
                    videoEngine.setSpeed(audio.speed)
                    videoEngine.setPitch(audio.pitch)
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                glEngine.surfaceDestroyed()
            }
        })

        observeActiveConfig()
    }

    private fun onGLSurfaceReady(surface: Surface) {
        pendingSurface = surface
        val uri = pendingVideoUri ?: return
        applyMedia(uri, surface)
    }

    private fun applyMedia(uri: String, surface: Surface?) {
        if (uri.isBlank()) return
        if (VideoFileStore.isImage(context, uri)) {
            videoEngine.release()
            renderer.clearCurrentFrame()
            renderer.setImageUri(context, Uri.parse(uri)) { _, _ ->
                glEngine.requestRender()
            }
        } else {
            renderer.clearImage()
            if (surface != null) {
                startVideo(uri, surface)
            }
        }
    }

    /** Same fallback logic as YoukiGLWallpaperService.observeActiveConfig — see that method's doc comment. */
    private fun observeActiveConfig() {
        val repo = WallpaperConfigRepository.get(context)
        presentationScope.launch {
            repo.getActiveOrFallback()
            repo.observeActive().collectLatest { config ->
                if (config == null) return@collectLatest
                renderer.updateConfig(config)
                glEngine.maxFps = config.maxFps

                val isImage = VideoFileStore.isImage(context, config.videoUri)
                if (!isImage) {
                    val orientation = WallpaperConfig.orientationFor(renderer.screenWidth, renderer.screenHeight)
                    val audio = config.audioPlaybackFor(orientation)
                    videoEngine.setMuted(audio.muted)
                    videoEngine.setSpeed(audio.speed)
                    videoEngine.setPitch(audio.pitch)
                }

                if (config.videoUri != currentVideoUri) {
                    currentVideoUri = config.videoUri
                    pendingVideoUri = config.videoUri
                    applyMedia(config.videoUri, pendingSurface)
                }
                glEngine.requestRender()
            }
        }
    }

    private fun startVideo(uri: String, surface: Surface) {
        if (uri.isBlank()) return
        val config = renderer.config
        val orientation = WallpaperConfig.orientationFor(renderer.screenWidth, renderer.screenHeight)
        val audio = config.audioPlaybackFor(orientation)
        renderer.clearCurrentFrame()
        videoEngine.prepare(
            videoUri = uri,
            outputSurface = surface,
            muted = audio.muted,
            playbackSpeed = audio.speed,
            audioPitch = audio.pitch,
            onSizeChanged = { w, h ->
                renderer.updateVideoSize(w, h)
                glEngine.requestRender()
            },
            onPlaybackError = { error ->
                Log.e(TAG, "Video playback failed on secondary display: $uri — ${error.message}")
            }
        )
        glEngine.requestRender()
    }

    override fun show() {
        super.show()
        isVisible = true
        glEngine.onResume()
        videoEngine.resume()
        glEngine.requestRender()
    }

    override fun dismiss() {
        isVisible = false
        videoEngine.pause()
        glEngine.onPause()
        presentationScope.cancel()
        // Same teardown order as YoukiGLWallpaperService.onDestroy() and for
        // the same reason: stop video first, then the GL thread (ensures the
        // last drawFrame finishes), then release the renderer's GL resources.
        videoEngine.release()
        glEngine.release()
        renderer.release()
        super.dismiss()
    }
}
