package com.plate.launcher.livewallpaper.data

// Room annotations removed — we use SQLiteOpenHelper directly

/**
 * WallpaperConfig — the single source of truth for every video wallpaper.
 *
 * Each row = the settings for one video, tied to [videoUri] as its unique
 * identifier. This object is read by the Service at draw time, and by the
 * Editor screen when editing — so there's no more scattered SharedPreferences
 * anywhere.
 */
data class WallpaperConfig(
    /** The video's path/URI — this is itself the unique identifier (Wallpaper ID) */
    val videoUri: String,

    // ── Phase 2: the free transform matrix (MVP Matrix) ─────────────────────────
    /** FIT / COVER / STRETCH / FREE */
    val scaleMode: String = ScaleMode.COVER.name,

    // Free Mode only (ignored in every other mode) — these are the "Portrait" values:
    val freeScaleX: Float = 1f,
    val freeScaleY: Float = 1f,
    val translateX: Float = 0f,   // -1f..1f, a fraction of the screen width
    val translateY: Float = 0f,   // -1f..1f, a fraction of the screen height
    val rotationDeg: Float = 0f,  // 0..360
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,

    // ── Free Mode settings for "Landscape" — fully independent ──────────
    //
    // Why separate fields instead of computing a ratio automatically when the
    // device rotates? Because the user explicitly requested it: adjusting the
    // position in portrait mode (say, nudging a character three times to get
    // it just right) must never affect landscape at all, and vice versa. Each
    // orientation is a fully independent "settings page," starting by default
    // with clean Cover/centered values (1f/0f/0f) the first time it's saved,
    // exactly like the default portrait values above — with no inheritance
    // between the two at all.
    val freeScaleXLandscape: Float = 1f,
    val freeScaleYLandscape: Float = 1f,
    val translateXLandscape: Float = 0f,
    val translateYLandscape: Float = 0f,
    val rotationDegLandscape: Float = 0f,
    val flipHorizontalLandscape: Boolean = false,
    val flipVerticalLandscape: Boolean = false,

    // ── Phase 3: colors and filters ───────────────────────────────────────────
    /** The background color behind the video (Fit mode) in #AARRGGBB format */
    val backgroundColor: String = "#FF000000",
    val colorCorrectionEnabled: Boolean = false,
    val brightness: Float = 1f,   // 0f..2f   (1f = no change)
    val contrast:   Float = 1f,   // 0f..2f
    val saturation: Float = 1f,   // 0f..2f

    // ── Phase 4: performance ─────────────────────────────────────────────────
    // Full separation between the two orientations (Portrait/Landscape):
    // opening the video in landscape starts with completely clean settings, as
    // if it were being opened for the first time — with no inheritance from
    // the portrait settings at all, exactly like the Free Transform pattern
    // above. Every old field (playbackSpeed/muted) stays as-is and is read
    // as the "Portrait" setting, and its Landscape-suffixed counterpart was
    // added alongside it. (fpsLimit itself was removed entirely — see
    // WallpaperConfigDao's own comment on COL_FPS_LIMIT for why the
    // database column stays but nothing reads it into this class anymore.)
    val playbackSpeed: Float = 1f, // 0.25f..3.0f — Portrait only
    // Max draw rate in FPS, or 0 for uncapped. NOT a reintroduction of the
    // removed fpsLimit field (see this class's own comment further down on
    // why that one was deleted) — that one filtered incoming video frames
    // and dropped real ones, causing stutter. This one throttles the draw
    // call itself (delays it, never drops a frame) — see
    // WallpaperGLEngine.maxFps's doc comment for the exact mechanism.
    // Global to the whole engine rather than per-orientation, matching how
    // the Editor's own slider (fragment_ed_unified.xml) presents it as one
    // "Performance" setting, not two.
    val maxFps: Int = 0,
    val muted: Boolean = true,     // Portrait only

    // Audio pitch — a setting fully independent from speed, adjusted manually
    // by the user instead of being automatically tied to playbackSpeed.
    val audioPitch: Float = 1f,            // 0.5f..2f — Portrait only (1f = normal, lower = deeper)

    val playbackSpeedLandscape: Float = 1f, // Landscape only — fully independent
    val mutedLandscape: Boolean = true,     // Landscape only
    val audioPitchLandscape: Float = 1f,    // Landscape only
    val isActive: Boolean = false, // the wallpaper currently applied to the screen
    val createdAt: Long = System.currentTimeMillis()
) {
    enum class ScaleMode { FIT, COVER, STRETCH, FREE }

    /** The screen's orientation at draw time — determines which set of fields (portrait/landscape) is used */
    enum class Orientation { PORTRAIT, LANDSCAPE }

    /**
     * Groups the 7 Free Mode values (whether portrait or landscape) into one
     * place — makes it easier to pass them to [TransformMatrix] and
     * [FreeGestureOverlay] without repeating the same 7 parameters in every
     * function, and prevents accidentally mixing up the two orientations' values.
     */
    data class FreeTransform(
        val scaleX: Float,
        val scaleY: Float,
        val translateX: Float,
        val translateY: Float,
        val rotationDeg: Float,
        val flipHorizontal: Boolean,
        val flipVertical: Boolean
    )

    /** Reads the correct set of Free Mode values based on the screen's current orientation */
    fun freeTransformFor(orientation: Orientation): FreeTransform = when (orientation) {
        Orientation.PORTRAIT -> FreeTransform(
            freeScaleX, freeScaleY, translateX, translateY,
            rotationDeg, flipHorizontal, flipVertical
        )
        Orientation.LANDSCAPE -> FreeTransform(
            freeScaleXLandscape, freeScaleYLandscape, translateXLandscape, translateYLandscape,
            rotationDegLandscape, flipHorizontalLandscape, flipVerticalLandscape
        )
    }

    /** Returns a new Config copy after writing [transform] to the correct set of fields only (the other set is left untouched) */
    fun withFreeTransform(orientation: Orientation, transform: FreeTransform): WallpaperConfig =
        when (orientation) {
            Orientation.PORTRAIT -> copy(
                freeScaleX = transform.scaleX, freeScaleY = transform.scaleY,
                translateX = transform.translateX, translateY = transform.translateY,
                rotationDeg = transform.rotationDeg,
                flipHorizontal = transform.flipHorizontal, flipVertical = transform.flipVertical
            )
            Orientation.LANDSCAPE -> copy(
                freeScaleXLandscape = transform.scaleX, freeScaleYLandscape = transform.scaleY,
                translateXLandscape = transform.translateX, translateYLandscape = transform.translateY,
                rotationDegLandscape = transform.rotationDeg,
                flipHorizontalLandscape = transform.flipHorizontal, flipVerticalLandscape = transform.flipVertical
            )
        }

    /**
     * Groups the audio/speed settings for a single orientation — same
     * philosophy as [FreeTransform]: each orientation has its own fully
     * independent copy of (speed, mute, pitch), so nothing is inherited
     * between Portrait and Landscape.
     */
    data class AudioPlayback(
        val speed: Float,
        val muted: Boolean,
        val pitch: Float
    )

    /** Reads the correct audio/speed settings based on the screen's current orientation */
    fun audioPlaybackFor(orientation: Orientation): AudioPlayback = when (orientation) {
        Orientation.PORTRAIT -> AudioPlayback(playbackSpeed, muted, audioPitch)
        Orientation.LANDSCAPE -> AudioPlayback(playbackSpeedLandscape, mutedLandscape, audioPitchLandscape)
    }

    /** Returns a new copy after writing [audio] to the correct orientation's set of fields only */
    fun withAudioPlayback(orientation: Orientation, audio: AudioPlayback): WallpaperConfig =
        when (orientation) {
            Orientation.PORTRAIT -> copy(
                playbackSpeed = audio.speed, muted = audio.muted, audioPitch = audio.pitch
            )
            Orientation.LANDSCAPE -> copy(
                playbackSpeedLandscape = audio.speed, mutedLandscape = audio.muted, audioPitchLandscape = audio.pitch
            )
        }

    companion object {
        /** Clean default settings for a new video that has no saved settings yet */
        fun default(videoUri: String) = WallpaperConfig(videoUri = videoUri)

        /** Determines the orientation from the actual screen dimensions at draw time — more accurate than relying on Configuration */
        fun orientationFor(screenWidth: Int, screenHeight: Int): Orientation =
            if (screenWidth > screenHeight) Orientation.LANDSCAPE else Orientation.PORTRAIT
    }
}
