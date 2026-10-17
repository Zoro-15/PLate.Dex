package com.plate.launcher.livewallpaper.ui.editor

import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import com.plate.launcher.R
import com.plate.launcher.livewallpaper.data.WallpaperConfig
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.Locale

/**
 * UnifiedEditorFragment — the single scrollable settings list (Wallpaper Engine
 * style), replacing the previously separate [PositionFragment] and
 * [ColorFragment] (and so [EditorPagerAdapter] and [ViewPager2]/[TabLayout] are
 * no longer used).
 *
 * ┌────────────────────────────────────────────────────────────────────────┐
 * │  Why one fragment instead of two with tabs: all fields (Alignment,     │
 * │  Background color, Brightness/Contrast/Saturation, FPS, Speed, Mute)   │
 * │  are now consecutive rows in a single vertical list (NestedScrollView),│
 * │  exactly like the reference Wallpaper Engine app — no navigating       │
 * │  between separate screens, just one simple scroll.                    │
 * │                                                                          │
 * │  "Alignment" (the screen mode Fit/Cover/Stretch/Free) is no longer 4   │
 * │  separate Chip buttons — it's now a single row showing the current     │
 * │  value, and tapping it opens                                          │
 * │  [MaterialAlertDialogBuilder.setSingleChoiceItems] (a single-choice    │
 * │  list from Material itself, the same style as Alignment in the        │
 * │  reference image).                                                     │
 * │                                                                          │
 * │  "Background color" — a circle reflecting the actual current color,    │
 * │  which opens a full [ColorPickerDialog] (free RGB/HSV + Hex) on tap —  │
 * │  fully free customization of any color, not just the fixed             │
 * │  Black/White/Blue it used to be.                                       │
 * │                                                                          │
 * │  The screen-orientation badge and the Free mode text hint were both    │
 * │  fully removed from the UI (explicit request: "remove the annoying     │
 * │  instructions") — the functional logic behind them (separating         │
 * │  portrait/landscape settings, and enabling Free touch) keeps working   │
 * │  with no change at all; only the on-screen warning text disappeared.  │
 * └────────────────────────────────────────────────────────────────────────┘
 */
class UnifiedEditorFragment : Fragment(R.layout.fragment_ed_unified) {

    private val viewModel: EditorViewModel by activityViewModels()

    /** Prevents a UI resync from the ViewModel from triggering listeners that would write back over themselves */
    private var isApplyingExternalState = false

    private val currentOrientation: WallpaperConfig.Orientation
        get() = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
            WallpaperConfig.Orientation.LANDSCAPE else WallpaperConfig.Orientation.PORTRAIT

    /** The displayed label for each [WallpaperConfig.ScaleMode] — used in both the Alignment row and the choice list */
    private fun scaleModeLabel(mode: WallpaperConfig.ScaleMode): Int = when (mode) {
        WallpaperConfig.ScaleMode.FIT     -> R.string.lw_scale_fit
        WallpaperConfig.ScaleMode.COVER   -> R.string.lw_scale_cover
        WallpaperConfig.ScaleMode.STRETCH -> R.string.lw_scale_stretch
        WallpaperConfig.ScaleMode.FREE    -> R.string.lw_scale_free
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val rowAlignment      = view.findViewById<View>(R.id.u_row_alignment)
        val alignmentValue    = view.findViewById<android.widget.TextView>(R.id.u_alignment_value)
        val bgColorSwatch     = view.findViewById<View>(R.id.u_bg_color_swatch)

        val sliderBrightness = view.findViewById<Slider>(R.id.u_slider_brightness)
        val sliderContrast   = view.findViewById<Slider>(R.id.u_slider_contrast)
        val sliderSaturation = view.findViewById<Slider>(R.id.u_slider_saturation)
        val switchColorTuning = view.findViewById<MaterialSwitch>(R.id.u_switch_color_tuning)

        // The "FPS" slider was removed from the logic: rendering now follows the
        // The old FpsLimiter-based FPS slider was removed entirely (not just
        // hidden) — see WallpaperConfig.kt's comment on the removed fpsLimit
        // field for why that implementation was genuinely broken. This is a
        // new, separate Max FPS control (u_slider_max_fps) added afterward:
        // it throttles the draw call itself rather than filtering incoming
        // video frames — see WallpaperGLEngine.maxFps's doc comment.
        val sliderSpeed  = view.findViewById<Slider>(R.id.u_slider_speed)
        val speedValue    = view.findViewById<android.widget.TextView>(R.id.u_speed_value)
        val switchMute    = view.findViewById<MaterialSwitch>(R.id.u_switch_mute)
        val sliderMaxFps  = view.findViewById<Slider>(R.id.u_slider_max_fps)
        val maxFpsValue   = view.findViewById<android.widget.TextView>(R.id.u_max_fps_value)

        // Audio pitch — a slider fully independent from speed, adjusted
        // manually by the user. Bound to an id already present in the layout
        // (see fragment_ed_unified.xml: u_slider_pitch / u_pitch_value).
        val sliderPitch  = view.findViewById<Slider>(R.id.u_slider_pitch)
        val pitchValue    = view.findViewById<android.widget.TextView>(R.id.u_pitch_value)

        val btnReset = view.findViewById<MaterialButton>(R.id.u_btn_reset)
        val sectionPlaybackAudio = view.findViewById<View>(R.id.u_section_playback_audio)

        viewModel.isImage.onEach { isImg ->
            sectionPlaybackAudio?.visibility = if (isImg) View.GONE else View.VISIBLE
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        // The orientation badge (Portrait/Landscape) was removed from the UI per
        // an explicit request — u_orientation_label no longer exists in the XML,
        // and there's no need for any setText here. currentOrientation is still
        // used internally only, to determine which WallpaperConfig.Orientation
        // an edit applies to (see resetFreeTransform below).

        // ── Alignment: tapping the row opens a single-choice list (same style as the reference image) ──
        rowAlignment.setOnClickListener { showAlignmentPicker() }

        // ── Background color: tapping the circle opens the full Color Picker ──
        bgColorSwatch.setOnClickListener { showBackgroundColorPicker() }

        switchColorTuning.setOnCheckedChangeListener { _, checked ->
            if (!isApplyingExternalState) viewModel.update { it.copy(colorCorrectionEnabled = checked) }
        }

        // Every color slider: updates the value, and if the drag actually came
        // from the user (fromUser) and color correction wasn't enabled yet, it
        // gets enabled automatically — without this, moving the slider produces
        // no visible change on the video because the Shader ignores the values
        // entirely while colorCorrectionEnabled=false (see the Switch comment
        // above in the XML), which is exactly what looked like "it doesn't work."
        sliderBrightness.addOnChangeListener { _, v, fromUser ->
            if (fromUser) {
                viewModel.update {
                    it.copy(brightness = v, colorCorrectionEnabled = true)
                }
            }
        }
        sliderContrast.addOnChangeListener { _, v, fromUser ->
            if (fromUser) {
                viewModel.update {
                    it.copy(contrast = v, colorCorrectionEnabled = true)
                }
            }
        }
        sliderSaturation.addOnChangeListener { _, v, fromUser ->
            if (fromUser) {
                viewModel.update {
                    it.copy(saturation = v, colorCorrectionEnabled = true)
                }
            }
        }

        // Fully decoupled from landscape: speed/mute/pitch are now written via
        // withAudioPlayback(currentOrientation, ...) instead of a direct copy()
        // on a shared field — so the edit only applies to the current
        // orientation's field set (Portrait or Landscape), exactly like what
        // already happens with Free Transform via
        // resetFreeTransform/updateFreeTransform below.
        sliderSpeed.addOnChangeListener { _, v, fromUser ->
            speedValue.text = String.format(Locale.US, "%.2fx", v)
            if (fromUser) viewModel.update { cfg ->
                cfg.withAudioPlayback(currentOrientation, cfg.audioPlaybackFor(currentOrientation).copy(speed = v))
            }
        }
        sliderMaxFps.addOnChangeListener { _, v, fromUser ->
            maxFpsValue.text = if (v <= 0f) getString(R.string.lw_max_fps_uncapped)
                                else String.format(Locale.US, "%d FPS", v.toInt())
            // Global setting (not per-orientation, unlike speed/pitch/mute
            // above) — see WallpaperConfig.maxFps's own doc comment for why.
            if (fromUser) viewModel.update { cfg -> cfg.copy(maxFps = v.toInt()) }
        }
        switchMute.setOnCheckedChangeListener { _, checked ->
            if (!isApplyingExternalState) viewModel.update { cfg ->
                cfg.withAudioPlayback(currentOrientation, cfg.audioPlaybackFor(currentOrientation).copy(muted = checked))
            }
        }
        sliderPitch.addOnChangeListener { _, v, fromUser ->
            pitchValue.text = String.format(Locale.US, "%.2fx", v)
            if (fromUser) viewModel.update { cfg ->
                cfg.withAudioPlayback(currentOrientation, cfg.audioPlaybackFor(currentOrientation).copy(pitch = v))
            }
        }

        btnReset.setOnClickListener {
            viewModel.update { cfg ->
                cfg.copy(
                    scaleMode = WallpaperConfig.ScaleMode.COVER.name,
                    backgroundColor = DEFAULT_BACKGROUND_COLOR,
                    colorCorrectionEnabled = false,
                    brightness = 1f, contrast = 1f, saturation = 1f
                ).withAudioPlayback(
                    currentOrientation,
                    WallpaperConfig.AudioPlayback(speed = 1f, muted = true, pitch = 1f)
                )
            }
            viewModel.resetFreeTransform(currentOrientation)
        }

        // ── Sync the UI with the current state (when the screen opens or after a Reset) ──
        viewModel.config.onEach { config ->
            isApplyingExternalState = true

            val mode = runCatching { WallpaperConfig.ScaleMode.valueOf(config.scaleMode) }
                .getOrDefault(WallpaperConfig.ScaleMode.COVER)
            alignmentValue.setText(scaleModeLabel(mode))

            // Actually tint the circle with the current config.backgroundColor
            // (instead of the fixed black color drawn inside the drawable
            // itself), via a tint on the existing background — this preserves
            // the circular shape + thin stroke from swatch_color_black.xml, and
            // only changes the inner fill color.
            runCatching { Color.parseColor(config.backgroundColor) }.getOrNull()?.let { parsed ->
                bgColorSwatch.background?.mutate()
                    ?.setTint(parsed)
            }

            switchColorTuning.isChecked = config.colorCorrectionEnabled

            if (sliderBrightness.value != config.brightness) sliderBrightness.value = config.brightness.coerceIn(0f, 2f)
            if (sliderContrast.value != config.contrast)     sliderContrast.value   = config.contrast.coerceIn(0f, 2f)
            if (sliderSaturation.value != config.saturation) sliderSaturation.value = config.saturation.coerceIn(0f, 2f)
            if (sliderMaxFps.value != config.maxFps.toFloat()) sliderMaxFps.value = config.maxFps.toFloat().coerceIn(0f, 120f)

            // Fully decoupled from landscape: we read from
            // audioPlaybackFor(currentOrientation) instead of
            // config.playbackSpeed/muted directly, so each orientation actually
            // shows its own independent values (also updates automatically on
            // device rotation while the screen is still open, since
            // currentOrientation is read live from resources.configuration on
            // every call).
            val audio = config.audioPlaybackFor(currentOrientation)
            if (sliderSpeed.value != audio.speed) sliderSpeed.value = audio.speed.coerceIn(0.25f, 3f)
            speedValue.text = String.format(Locale.US, "%.2fx", audio.speed)
            switchMute.isChecked = audio.muted
            if (sliderPitch.value != audio.pitch) sliderPitch.value = audio.pitch.coerceIn(0.5f, 2f)
            pitchValue.text = String.format(Locale.US, "%.2fx", audio.pitch)

            isApplyingExternalState = false
        }.launchIn(viewLifecycleOwner.lifecycleScope)
    }

    /**
     * The Alignment mode picker — replaces 4 separate Chip buttons with a single
     * Material choice list (the same [MaterialAlertDialogBuilder] component
     * already used elsewhere in other screens via [R.style.DialogTheme]),
     * matching the "Alignment → Cover" style from the reference Wallpaper Engine image.
     */
    private fun showAlignmentPicker() {
        val modes = WallpaperConfig.ScaleMode.entries.toTypedArray()
        val labels = modes.map { getString(scaleModeLabel(it)) }.toTypedArray()
        val currentMode = runCatching { WallpaperConfig.ScaleMode.valueOf(viewModel.config.value.scaleMode) }
            .getOrDefault(WallpaperConfig.ScaleMode.COVER)
        val checkedIndex = modes.indexOf(currentMode).coerceAtLeast(0)

        MaterialAlertDialogBuilder(requireContext(), R.style.DialogTheme)
            .setTitle(R.string.lw_alignment)
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                viewModel.update { it.copy(scaleMode = modes[which].name) }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Opens [ColorPickerDialog] to customize the live wallpaper's background color.
     *
     * FIX: now uses [ColorPickerDialog.TYPE_PRESETS] instead of TYPE_CUSTOM —
     * this is exactly the ready-made "color system" the user requested (a grid of
     * [ColorPickerDialog.MATERIAL_COLORS], 19 ready-made Material colors to pick
     * with one tap), instead of opening a free RGB/HSV screen directly every
     * time. The "Custom" button built into the presets screen itself (enabled
     * via setAllowCustom(true)) stays available for anyone who wants a color
     * outside the ready-made grid, so no functionality is lost.
     */
    private fun showBackgroundColorPicker() {
        val currentColor = runCatching { Color.parseColor(viewModel.config.value.backgroundColor) }
            .getOrDefault(Color.BLACK)

        ColorPickerDialog.newBuilder()
            .setColor(currentColor)
            .setDialogId(EditorActivity.COLOR_PICKER_DIALOG_ID)
            .setDialogType(ColorPickerDialog.TYPE_PRESETS)
            .setShowAlphaSlider(false)
            .setAllowPresets(true)
            .setAllowCustom(true)
            .show(requireActivity())
    }

    companion object {
        /** Matches the default value in WallpaperConfig.kt exactly (fully opaque black) */
        private const val DEFAULT_BACKGROUND_COLOR = "#FF000000"

        fun newInstance() = UnifiedEditorFragment()
    }
}
