package com.youki.dex.livewallpaper.ui.gallery

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.decode.VideoFrameDecoder
import coil.load
import coil.request.videoFrameMillis
import com.youki.dex.R
import com.youki.dex.livewallpaper.data.VideoFile

/**
 * GalleryAdapter — displays the video card grid as thumbnail images only.
 *
 * ┌────────────────────────────────────────────────────────────────────────┐
 * │  No video playback is allowed here — this is a deliberate engineering  │
 * │  decision from the original design. Coil + VideoFrameDecoder captures  │
 * │  a single frame from the video and caches it as a Bitmap, so scrolling │
 * │  through the grid stays smooth with no MediaPlayer/ExoPlayer running   │
 * │  behind it.                                                            │
 * └────────────────────────────────────────────────────────────────────────┘
 */
class GalleryAdapter(
    private val imageLoader: ImageLoader,
    private val activeVideoUri: () -> String?,
    private val onClick: (VideoFile) -> Unit,
    private val onLongClick: (VideoFile) -> Unit
) : ListAdapter<VideoFile, GalleryAdapter.VH>(DIFF) {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.lw_item_thumb)
        val name: TextView   = view.findViewById(R.id.lw_item_name)
        val activeBadge: View = view.findViewById(R.id.lw_item_active_badge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_lw_video, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val video = getItem(position)
        holder.name.text = video.displayName

        val isImg = video.isImage
        holder.thumb.load(Uri.parse(video.uri), imageLoader) {
            crossfade(true)
            if (!isImg) {
                videoFrameMillis(500) // a frame from half a second in — usually not a black frame
            }
            placeholder(if (isImg) R.drawable.ic_image else R.drawable.ic_movie)
            error(if (isImg) R.drawable.ic_image else R.drawable.ic_movie)
        }

        holder.activeBadge.visibility =
            if (video.uri == activeVideoUri()) View.VISIBLE else View.GONE

        holder.itemView.setOnClickListener { onClick(video) }
        holder.itemView.setOnLongClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            onLongClick(video)
            true
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<VideoFile>() {
            override fun areItemsTheSame(old: VideoFile, new: VideoFile) = old.uri == new.uri
            override fun areContentsTheSame(old: VideoFile, new: VideoFile) = old == new
        }
    }
}
