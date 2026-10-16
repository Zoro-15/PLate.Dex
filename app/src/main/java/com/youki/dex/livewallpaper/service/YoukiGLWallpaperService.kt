package com.youki.dex.livewallpaper.service

import android.net.Uri
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import android.view.Surface
import com.youki.dex.livewallpaper.data.VideoFileStore
import com.youki.dex.livewallpaper.data.WallpaperConfig
import com.youki.dex.livewallpaper.data.WallpaperConfigRepository
import com.youki.dex.livewallpaper.engine.VideoEngine
import com.youki.dex.livewallpaper.gl.WallpaperGLRenderer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * YoukiGLWallpaperService — the actual service registered with the system as a live wallpaper.
 *
 * ┌────────────────────────────────────────────────────────────────────────┐
 * │  Ties all phases together here:                                        │
 * │   • Phase 5 (Room): watches [WallpaperConfigRepository.observeActive]  │
 * │     so any change from the Editor screen is reflected "live" without   │
 * │     a manual restart.                                                   │
 * │   • Phases 1+2+3 (GL): [WallpaperGLRenderer] draws the frame with the  │
 * │     correct transform and colors on every cycle.                       │
 * │   • Phase 4 (Performance): [VideoEngine] controls the speed, and       │
 * │     WallpaperGLEngine.maxFps throttles the requestRender() rate.       │
 * └────────────────────────────────────────────────────────────────────────┘
 */
class YoukiGLWallpaperService : WallpaperService() {

    companion object {
        private const val TAG = "YoukiGLWallpaperService"
    }

    override fun onCreateEngine(): Engine = GLEngine()

    inner class GLEngine : Engine() {

        private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private lateinit var glEngine: WallpaperGLEngine
        private lateinit var renderer: WallpaperGLRenderer
        private lateinit var videoEngine: VideoEngine

        private var currentVideoUri: String? = null
        private var isVisible = false

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)

            videoEngine = VideoEngine(this@YoukiGLWallpaperService)

            // Previously broken (frame throttling): previously there was an FpsLimiter that
            // filtered onFrameAvailable by comparing timestamps against an
            // "fpsLimit" the user set from the Editor — meaning real video
            // frames that actually arrived were being dropped if the chosen
            // number was lower than the video's actual frame rate. Now
            // onFrameAvailable is the single source of truth: any new actual
            // video frame = an immediate render request, with no artificial
            // intermediate counter/filter. Speed and the actual frame rate are
            // controlled only via the video's own playback speed
            // (VideoEngine.setSpeed), not by throttling the draw calls.
            renderer = WallpaperGLRenderer(
                onSurfaceReady = { surface -> onGLSurfaceReady(surface) },
                onFrameAvailable = {
                    if (isVisible) {
                        glEngine.requestRender()
                    }
                }
            )

            // Fix for Crash #2 — NPE in SurfaceView.onAttachedToWindow: we no
            // longer use GLSurfaceView at all here — replaced it with
            // [WallpaperGLEngine], which manages EGL manually with no
            // dependency on a real View/Window.
            glEngine = WallpaperGLEngine(renderer)

            val filter = android.content.IntentFilter(WallpaperConfigRepository.ACTION_WALLPAPER_CHANGED)
            registerReceiver(wallpaperReceiver, filter)

            observeActiveConfig()
        }

        private val wallpaperReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                reloadActive()
            }
        }

        private fun reloadActive() {
            val repo = WallpaperConfigRepository.get(this@YoukiGLWallpaperService)
            serviceScope.launch {
                val config = repo.getActive() ?: repo.getActiveOrFallback() ?: return@launch
                applyConfig(config)
            }
        }

        private fun applyConfig(config: WallpaperConfig) {
            renderer.updateConfig(config)
            glEngine.maxFps = config.maxFps

            val isImage = VideoFileStore.isImage(this@YoukiGLWallpaperService, config.videoUri)
            if (!isImage) {
                val orientation = WallpaperConfig.orientationFor(renderer.screenWidth, renderer.screenHeight)
                val audio = config.audioPlaybackFor(orientation)
                videoEngine.setMuted(audio.muted)
                videoEngine.setSpeed(audio.speed)
                videoEngine.setPitch(audio.pitch)
            }

            currentVideoUri = config.videoUri
            pendingVideoUri = config.videoUri
            applyMedia(config.videoUri, pendingSurface)
            glEngine.requestRender()
        }

        /**
         * Called the moment the GL context becomes ready — here we pass the Surface to ExoPlayer.
         */
        private fun onGLSurfaceReady(surface: Surface) {
            pendingSurface = surface
            val uri = pendingVideoUri ?: return
            applyMedia(uri, surface)
        }

        private var pendingSurface: Surface? = null
        private var pendingVideoUri: String? = null

        private fun applyMedia(uri: String, surface: Surface?) {
            if (uri.isBlank()) return
            if (VideoFileStore.isImage(this@YoukiGLWallpaperService, uri)) {
                videoEngine.release()
                renderer.clearCurrentFrame()
                renderer.setImageUri(this@YoukiGLWallpaperService, Uri.parse(uri)) { _, _ ->
                    glEngine.requestRender()
                }
            } else {
                renderer.clearImage()
                if (surface != null) {
                    startVideo(uri, surface)
                }
            }
        }

        private fun observeActiveConfig() {
            val repo = WallpaperConfigRepository.get(this@YoukiGLWallpaperService)
            serviceScope.launch {
                repo.getActiveOrFallback() // ensures isActive=1 exists before watching
                repo.observeActive().collectLatest { config ->
                    if (config == null) return@collectLatest
                    applyConfig(config)
                }
            }
        }

        private fun startVideo(uri: String, surface: Surface) {
            if (uri.isBlank()) return
            val config = renderer.config
            val orientation = WallpaperConfig.orientationFor(renderer.screenWidth, renderer.screenHeight)
            val audio = config.audioPlaybackFor(orientation)
            // Previously broken (black screen until app restart): switching videos reuses
            // the same SurfaceTexture (see WallpaperGLRenderer.clearCurrentFrame
            // for the full explanation) — clear any stale frame state left by
            // the previous video before the new player starts writing to it.
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
                    // Before: any playback error (deleted file, an invalidated
                    // URI, an unsupported codec...) used to disappear silently
                    // and leave the user on infinite loading with no
                    // explanation at all. Now at least we see it in Logcat
                    // immediately: filter by "VideoEngine" or "GLEngine" to
                    // catch the real cause.
                    android.util.Log.e("GLEngine", "Video playback failed: $uri — ${error.message}")
                }
            )
            // Don't wait passively for the new player's first onFrameAvailable
            // — request a render now too, so a stuck/delayed first callback
            // doesn't leave the screen showing the previous (or a black) frame.
            glEngine.requestRender()
        }

        // startRenderLoop() (the separate delay() loop) was removed entirely —
        // see the full explanation in the onFrameAvailable comment above in
        // onCreate(). Rendering is now driven exclusively by actual incoming
        // video frames, filtered by the fpsLimiter rate, with no independent
        // timing loop that could drift over time.

        // ── Live wallpaper lifecycle ──────────────────────────────────────────────

        override fun onVisibilityChanged(visible: Boolean) {
            isVisible = visible
            if (visible) {
                glEngine.onResume()
                videoEngine.resume()
                // Request an immediate render the moment it becomes visible,
                // without waiting for the first new video frame (which might be
                // slightly delayed), so the screen doesn't stay stuck on the
                // last state (usually blank) when returning from the background.
                glEngine.requestRender()
            } else {
                videoEngine.pause()
                glEngine.onPause()
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            val previousOrientation = WallpaperConfig.orientationFor(renderer.screenWidth, renderer.screenHeight)
            glEngine.surfaceChanged(width, height)
            val newOrientation = WallpaperConfig.orientationFor(width, height)

            // Fully decoupled from landscape: when the user actually rotates
            // the device (not just a simple remeasure at the same orientation),
            // we immediately apply the audio/speed/pitch settings for the new
            // orientation — exactly as if the video were being opened fresh
            // with its own independent settings, instead of staying on the old
            // orientation's settings until the next DB update.
            if (newOrientation != previousOrientation) {
                val audio = renderer.config.audioPlaybackFor(newOrientation)
                videoEngine.setMuted(audio.muted)
                videoEngine.setSpeed(audio.speed)
                videoEngine.setPitch(audio.pitch)
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            glEngine.surfaceCreated(holder)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            glEngine.surfaceDestroyed()
        }

        override fun onDestroy() {
            super.onDestroy()
            try { unregisterReceiver(wallpaperReceiver) } catch (_: Exception) {}
            serviceScope.cancel()
            // Patched: Crash — wrong teardown order. the old code used to release
            // renderer before glEngine — but glEngine still uses renderer (via
            // onDrawFrame) while it's being released. The correct order:
            //   1. Stop video playback first (no more frames)
            //   2. Stop the GL engine (ensures the last drawFrame has finished)
            //   3. Then release the renderer (GL resources are now safe to release)
            videoEngine.release()
            glEngine.release()   // calls surfaceDestroyed then quitSafely with a latch
            renderer.release()   // safe now: no active GL thread remains
        }
    }
}
