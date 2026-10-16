package com.youki.dex.livewallpaper.ui.editor

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.Surface
import com.youki.dex.livewallpaper.gl.WallpaperGLRenderer

/**
 * WallpaperPreviewView — a regular GLSurfaceView (with its own window) used
 * only inside the Editor screen for the live preview.
 *
 * ┌────────────────────────────────────────────────────────────────────────┐
 * │  Why the same [WallpaperGLRenderer]?                                    │
 * │  To guarantee that what the user sees while editing = exactly what will │
 * │  show up behind the icons after pressing "Apply". Zero difference       │
 * │  between the preview and the result.                                    │
 * └────────────────────────────────────────────────────────────────────────┘
 */
class WallpaperPreviewView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    val renderer: WallpaperGLRenderer = WallpaperGLRenderer(
        onSurfaceReady = { surface -> onSurfaceReadyCallback?.invoke(surface) }
    )

    private var onSurfaceReadyCallback: ((Surface) -> Unit)? = null

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY // the preview needs continuous updates while dragging sliders
    }

    /** Called once after the GL context is ready — here we bind ExoPlayer to the surface */
    fun setOnSurfaceReady(callback: (Surface) -> Unit) {
        onSurfaceReadyCallback = callback
    }
}
