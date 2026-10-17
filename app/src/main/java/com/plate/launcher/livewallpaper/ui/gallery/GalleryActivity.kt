package com.plate.launcher.livewallpaper.ui.gallery

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.decode.VideoFrameDecoder
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.plate.launcher.R
import com.plate.launcher.livewallpaper.data.VideoFile
import com.plate.launcher.livewallpaper.data.WallpaperPreferences
import com.plate.launcher.livewallpaper.ui.editor.EditorActivity
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class GalleryActivity : com.plate.launcher.activities.BaseFontScaleActivity() {

    private val viewModel: GalleryViewModel by viewModels()
    private lateinit var adapter: GalleryAdapter
    private lateinit var gridLayoutManager: GridLayoutManager

    companion object {
        // Target column width. Real column count is derived from the
        // available width so the grid grows (2 → 4 → 8 ...) on wider
        // screens/windows instead of being stuck at a fixed count.
        private const val COLUMN_TARGET_WIDTH_DP = 180
        private const val MIN_SPAN_COUNT = 2
    }

    /** Computes how many grid columns fit the current window width, with no upper cap. */
    private fun calculateSpanCount(): Int {
        val widthDp = resources.configuration.screenWidthDp
        val computed = widthDp / COLUMN_TARGET_WIDTH_DP
        return computed.coerceAtLeast(MIN_SPAN_COUNT)
    }

    private val pickMedia = registerForActivityResult(object : ActivityResultContracts.GetContent() {
        override fun createIntent(context: android.content.Context, input: String): Intent {
            return super.createIntent(context, input).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "image/*"))
            }
        }
    }) { uri ->
        if (uri != null) {
            val name = queryDisplayName(uri)
            viewModel.importVideo(uri, name)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lw_gallery)

        setSupportActionBar(findViewById(R.id.lw_toolbar))

        val imageLoader = ImageLoader.Builder(this)
            .components { add(VideoFrameDecoder.Factory()) }
            .build()

        adapter = GalleryAdapter(
            imageLoader    = imageLoader,
            activeVideoUri = { viewModel.activeConfig.value?.videoUri },
            onClick        = { video -> openEditor(video) },
            onLongClick    = { video -> showQuickActions(video) }
        )

        gridLayoutManager = GridLayoutManager(this, calculateSpanCount())
        findViewById<RecyclerView>(R.id.lw_grid).apply {
            layoutManager = gridLayoutManager
            adapter = this@GalleryActivity.adapter
        }

        findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.lw_swipe_refresh)
            .setOnRefreshListener { viewModel.refresh() }

        findViewById<View>(R.id.lw_fab_add).setOnClickListener { pickMedia.launch("*/*") }

        observeState()
    }

    // FIX: أنيميشن الرجوع موحّد مع باقي صفحات الإعدادات (Pixel-style)
    override fun finish() {
        super.finish()
        overridePendingTransition(
            R.anim.fragment_close_enter,
            R.anim.fragment_close_exit
        )
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
        adapter.notifyDataSetChanged()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Window/screen width changed (rotation, or a freeform window being
        // resized) — recompute how many columns fit so the grid keeps
        // growing/shrinking instead of staying stuck at its initial count.
        val newSpanCount = calculateSpanCount()
        if (gridLayoutManager.spanCount != newSpanCount) {
            gridLayoutManager.spanCount = newSpanCount
        }
    }

    private fun observeState() {
        viewModel.videos.onEach { list ->
            adapter.submitList(list)
            findViewById<View>(R.id.lw_empty_state).visibility =
                if (list.isEmpty()) View.VISIBLE else View.GONE
        }.launchIn(lifecycleScope)

        viewModel.isLoading.onEach { loading ->
            findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.lw_swipe_refresh)
                .isRefreshing = loading
        }.launchIn(lifecycleScope)

        viewModel.errorMessage.onEach { msg ->
            if (msg != null) Snackbar.make(findViewById(R.id.lw_grid), msg, Snackbar.LENGTH_SHORT).show()
        }.launchIn(lifecycleScope)

        viewModel.activeConfig.onEach { adapter.notifyDataSetChanged() }.launchIn(lifecycleScope)
    }

    private fun openEditor(video: VideoFile) {
        startActivity(Intent(this, EditorActivity::class.java).apply {
            putExtra(EditorActivity.EXTRA_VIDEO_URI, video.uri)
            putExtra(EditorActivity.EXTRA_VIDEO_NAME, video.displayName)
        })
    }

    private fun showQuickActions(video: VideoFile) {
        MaterialAlertDialogBuilder(this)
            .setTitle(video.displayName)
            .setItems(arrayOf(
                getString(R.string.lw_apply),
                getString(R.string.lw_editor_title),
                getString(R.string.lw_delete)
            )) { _, which ->
                when (which) {
                    0 -> {
                        WallpaperPreferences.setWallpaper(this@GalleryActivity, video.uri, video.isImage)
                        viewModel.applyWallpaper(video)
                        Snackbar.make(findViewById(R.id.lw_grid), R.string.lw_applied, Snackbar.LENGTH_SHORT).show()
                    }
                    1 -> openEditor(video)
                    2 -> confirmDelete(video)
                }
            }.show()
    }

    private fun confirmDelete(video: VideoFile) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.lw_delete)
            .setMessage(video.displayName)
            .setPositiveButton(R.string.lw_delete) { _, _ -> viewModel.deleteVideo(video) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun queryDisplayName(uri: Uri): String? {
        val nameFromCursor = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
        val name = nameFromCursor ?: uri.lastPathSegment ?: "wallpaper_${System.currentTimeMillis()}"

        val hasExt = name.substringAfterLast('.', "").isNotBlank() &&
                     name.substringAfterLast('.', "").length in 2..5
        if (hasExt) return name

        val mime = contentResolver.getType(uri)
        val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        return if (!ext.isNullOrBlank()) "$name.$ext" else if (mime?.startsWith("image/") == true) "$name.jpg" else "$name.mp4"
    }
}
