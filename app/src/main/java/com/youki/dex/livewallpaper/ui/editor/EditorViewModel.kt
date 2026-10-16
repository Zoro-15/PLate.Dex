package com.youki.dex.livewallpaper.ui.editor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.youki.dex.livewallpaper.data.WallpaperConfig
import com.youki.dex.livewallpaper.data.WallpaperConfigRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * EditorViewModel — loads the settings for a single video, and reflects
 * slider changes "live" via the [config] StateFlow that
 * [WallpaperPreviewView.renderer] reads directly, with no database write at
 * all until [save] or [apply].
 *
 * This separation (edit in memory ← explicit save) prevents thousands of DB
 * writes while dragging a single slider, and allows for a simple "Cancel"
 * button (go back with no save).
 */
class EditorViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = WallpaperConfigRepository.get(app)

    private val _config = MutableStateFlow(WallpaperConfig.default(""))
    val config: StateFlow<WallpaperConfig> = _config.asStateFlow()

    private val _isImage = MutableStateFlow(false)
    val isImage: StateFlow<Boolean> = _isImage.asStateFlow()

    private var videoUri: String = ""

    fun load(uri: String) {
        videoUri = uri
        _isImage.value = com.youki.dex.livewallpaper.data.VideoFileStore.isImage(getApplication(), uri)
        viewModelScope.launch {
            _config.value = repo.get(uri)
        }
    }

    /** Updates a single field in memory only — the live preview reacts immediately via StateFlow */
    fun update(transform: (WallpaperConfig) -> WallpaperConfig) {
        _config.value = transform(_config.value)
    }

    /**
     * Updates the Free Mode values (scale/translate/rotate/flip) for
     * [orientation] only, leaving the other orientation's values completely
     * untouched — this is the full separation required between the
     * portrait and landscape settings.
     */
    fun updateFreeTransform(
        orientation: WallpaperConfig.Orientation,
        transform: (WallpaperConfig.FreeTransform) -> WallpaperConfig.FreeTransform
    ) {
        _config.value = _config.value.let { current ->
            current.withFreeTransform(orientation, transform(current.freeTransformFor(orientation)))
        }
    }

    fun save() {
        viewModelScope.launch { repo.save(_config.value) }
    }

    /** Saves the current settings and activates them as the single live wallpaper on the screen */
    fun applyWallpaper(onDone: () -> Unit) {
        viewModelScope.launch {
            repo.save(_config.value)
            repo.setActive(videoUri)
            onDone()
        }
    }

    fun resetToDefaults() {
        _config.value = WallpaperConfig.default(videoUri)
    }

    /** Resets only the Free Mode values for the current orientation (the other orientation's values stay saved as-is) */
    fun resetFreeTransform(orientation: WallpaperConfig.Orientation) {
        _config.value = _config.value.withFreeTransform(
            orientation,
            WallpaperConfig.default("").freeTransformFor(orientation)
        )
    }
}
