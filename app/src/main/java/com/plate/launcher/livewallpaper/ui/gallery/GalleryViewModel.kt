package com.plate.launcher.livewallpaper.ui.gallery

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.plate.launcher.R
import com.plate.launcher.livewallpaper.data.VideoFile
import com.plate.launcher.livewallpaper.data.VideoFileStore
import com.plate.launcher.livewallpaper.data.WallpaperConfig
import com.plate.launcher.livewallpaper.data.WallpaperConfigRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * GalleryViewModel — prepares all the data for the main gallery screen.
 *
 * No video playback is allowed here (by design) — we only display a simple
 * VideoFile that the Adapter converts into a thumbnail WebP image via Coil.
 * The ViewModel itself knows nothing about rendering, it only manages the
 * lists and state.
 */
class GalleryViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = WallpaperConfigRepository.get(app)

    private val _videos = MutableStateFlow<List<VideoFile>>(emptyList())
    val videos: StateFlow<List<VideoFile>> = _videos.asStateFlow()

    val activeConfig: StateFlow<WallpaperConfig?> = repo.observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val isLoading = MutableStateFlow(false)
    val errorMessage = MutableStateFlow<String?>(null)

    init { refresh() }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            isLoading.value = true
            _videos.value = VideoFileStore.listVideos(getApplication())
            isLoading.value = false
        }
    }

    /** Imports a video the user picked from the system picker into the app's folder */
    fun importVideo(sourceUri: Uri, displayName: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            isLoading.value = true
            val imported = VideoFileStore.importVideo(getApplication(), sourceUri, displayName)
            isLoading.value = false
            if (imported != null) {
                refresh()
            } else {
                errorMessage.value = getApplication<Application>().getString(R.string.gallery_import_video_failed)
            }
        }
    }

    /**
     * Adds a video that already exists in the app's folder without copying it (from InternalFilePicker).
     * Called when the user picks a file directly from within YoukiDEX_Wallpapers.
     */
    fun addVideoFile(file: java.io.File, displayName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val video = VideoFileStore.addExistingFile(file, displayName)
            if (video != null) refresh()
            else errorMessage.value = getApplication<Application>().getString(R.string.gallery_add_video_failed)
        }
    }

    /** Activates a wallpaper immediately from the gallery screen (a quick apply without opening the Editor) */
    fun applyWallpaper(video: VideoFile) {
        viewModelScope.launch(Dispatchers.IO) { repo.setActive(video.uri) }
    }

    fun deleteVideo(video: VideoFile) {
        viewModelScope.launch(Dispatchers.IO) {
            VideoFileStore.delete(video.uri)
            repo.delete(video.uri)
            refresh()
        }
    }
}
