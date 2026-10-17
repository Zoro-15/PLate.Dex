package com.plate.launcher.livewallpaper.service

import android.app.Presentation
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.Display
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import coil.load
import com.plate.launcher.livewallpaper.data.WallpaperPreferences
import java.io.File

/**
 * SecondaryDisplayWallpaperPresentation — shows the active wallpaper
 * on an external/secondary display via Presentation, matching the desktop canvas.
 */
class SecondaryDisplayWallpaperPresentation(
    outerContext: Context,
    display: Display
) : Presentation(outerContext, display) {

    private lateinit var imageView: ImageView
    private lateinit var textureView: TextureView
    private var player: ExoPlayer? = null
    private var currentPath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        imageView = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }

        textureView = TextureView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }

        root.addView(imageView)
        root.addView(textureView)
        setContentView(root)

        applyWallpaper()
    }

    private fun applyWallpaper() {
        val (path, type) = WallpaperPreferences.getActiveWallpaper(context)
        if (path.isNullOrBlank()) {
            imageView.visibility = View.GONE
            textureView.visibility = View.GONE
            player?.stop()
            currentPath = null
            return
        }

        if (type == WallpaperPreferences.TYPE_IMAGE) {
            player?.stop()
            textureView.visibility = View.GONE
            imageView.visibility = View.VISIBLE
            if (currentPath != path) {
                currentPath = path
                val uri = Uri.parse(path)
                if (uri.scheme == "file" || uri.scheme == null) {
                    val file = File(uri.path ?: path)
                    if (file.exists()) {
                        imageView.load(file)
                    } else {
                        imageView.load(uri)
                    }
                } else {
                    imageView.load(uri)
                }
            }
        } else {
            imageView.visibility = View.GONE
            textureView.visibility = View.VISIBLE
            if (currentPath != path) {
                currentPath = path
                val p = player ?: ExoPlayer.Builder(context).build().also {
                    player = it
                    it.repeatMode = Player.REPEAT_MODE_ONE
                    it.volume = 0f
                    it.setVideoTextureView(textureView)
                }
                p.setMediaItem(MediaItem.fromUri(Uri.parse(path)))
                p.prepare()
                p.playWhenReady = true
            } else {
                player?.play()
            }
        }
    }

    override fun show() {
        super.show()
        applyWallpaper()
    }

    override fun dismiss() {
        player?.release()
        player = null
        super.dismiss()
    }
}
