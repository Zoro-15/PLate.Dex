package com.plate.launcher.livewallpaper.ui.editor

import android.content.Context
import android.graphics.SurfaceTexture
import android.net.Uri
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import coil.load
import java.io.File

/**
 * WallpaperPreviewView — provides an in-editor desktop canvas previewing
 * static images or video via TextureView, matching LauncherActivity's canvas.
 */
class WallpaperPreviewView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs), TextureView.SurfaceTextureListener {

    private val imageView = ImageView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        scaleType = ImageView.ScaleType.CENTER_CROP
        visibility = View.GONE
    }

    private val textureView = TextureView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        surfaceTextureListener = this@WallpaperPreviewView
        visibility = View.GONE
    }

    private var onSurfaceReadyCallback: ((Surface) -> Unit)? = null
    private var activeSurface: Surface? = null

    init {
        addView(imageView)
        addView(textureView)
    }

    fun setOnSurfaceReady(callback: (Surface) -> Unit) {
        onSurfaceReadyCallback = callback
        activeSurface?.let { callback(it) }
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        val s = Surface(st)
        activeSurface = s
        onSurfaceReadyCallback?.invoke(s)
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        activeSurface?.release()
        activeSurface = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    fun showImage(uriString: String) {
        textureView.visibility = View.GONE
        imageView.visibility = View.VISIBLE
        val uri = Uri.parse(uriString)
        if (uri.scheme == "file" || uri.scheme == null) {
            val file = File(uri.path ?: uriString)
            if (file.exists()) {
                imageView.load(file)
                return
            }
        }
        imageView.load(uri)
    }

    fun showVideo() {
        imageView.visibility = View.GONE
        textureView.visibility = View.VISIBLE
    }

    fun onPause() {}
    fun onResume() {}
}
