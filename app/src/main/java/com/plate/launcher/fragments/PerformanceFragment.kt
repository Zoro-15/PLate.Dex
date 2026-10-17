package com.plate.launcher.fragments

import android.content.Context
import android.os.Bundle
import android.view.*
import com.google.android.material.materialswitch.MaterialSwitch
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.plate.launcher.R
import com.plate.launcher.utils.RootManager
import com.plate.launcher.utils.ShizukoManager

class PerformanceFragment : Fragment() {

    private lateinit var fpsCurrentTv:    TextView
    private lateinit var thermalStatusTv: TextView
    private lateinit var zramCurrentTv:   TextView

    // Tweak toggles
    private lateinit var tweakAmoledSwitch: MaterialSwitch
    private lateinit var tweakAudioSwitch: MaterialSwitch
    private lateinit var tweakDiskioSwitch: MaterialSwitch
    private lateinit var tweakConnectivitySwitch: MaterialSwitch
    private lateinit var tweakThermalkillerSwitch: MaterialSwitch
    private lateinit var tweakMtkSwitch: MaterialSwitch
    private lateinit var tweakHardcoreSwitch: MaterialSwitch
    private lateinit var tweakC2dSwitch: MaterialSwitch
    private lateinit var tweakFpsinjectorSwitch: MaterialSwitch
    private lateinit var tweakTouchSwitch: MaterialSwitch
    private lateinit var tweakBatterySwitch: MaterialSwitch

    companion object {
        private const val PREFS_TWEAKS = "youki_tweaks"
        private const val KEY_AMOLED       = "tweak_amoled"
        private const val KEY_AUDIO        = "tweak_audio"
        private const val KEY_DISKIO       = "tweak_diskio"
        private const val KEY_CONNECTIVITY = "tweak_connectivity"
        private const val KEY_THERMALKILL  = "tweak_thermalkiller"
        private const val KEY_MTK          = "tweak_mtk"
        private const val KEY_HARDCORE     = "tweak_hardcore"
        private const val KEY_C2D          = "tweak_c2d"
        private const val KEY_FPSINJECTOR  = "tweak_fpsinjector"
        private const val KEY_TOUCH        = "tweak_touch"
        private const val KEY_BATTERY      = "tweak_battery"
    }

    enum class ThermalMode(val labelRes: Int) {
        BALANCED   (R.string.thermal_mode_balanced),
        PERFORMANCE(R.string.thermal_mode_performance),
        GAMING     (R.string.thermal_mode_gaming),
        POWERSAVE  (R.string.thermal_mode_powersave)
    }

    private var detectedMaxHz: Int = 60

    private val zramValues by lazy { listOf(
        "${getString(R.string.off)} (0)" to 0L,
        "256 MB"    to 268435456L,
        "512 MB"    to 536870912L,
        "1 GB"      to 1073741824L,
        "2 GB"      to 2147483648L,
        "3 GB"      to 3221225472L,
        "4 GB"      to 4294967296L,
        "6 GB"      to 6442450944L,
        "8 GB"      to 8589934592L
    ) }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_performance, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val ctx     = requireContext()
        val root    = RootManager.getInstance(ctx)
        val shizuku = ShizukoManager.getInstance(ctx)
        val hasPriv = root.isAvailable || shizuku.hasPermission

        fpsCurrentTv    = view.findViewById(R.id.fps_current_tv)
        thermalStatusTv = view.findViewById(R.id.thermal_status_tv)
        zramCurrentTv   = view.findViewById(R.id.zram_current_tv)

        // Show warning banner if no privilege
        view.findViewById<View>(R.id.perf_no_privilege).visibility =
            if (hasPriv) View.GONE else View.VISIBLE

        // Disable entire content section — same pattern as root_category in AdvancedPreferences
        val content = view.findViewById<ViewGroup>(R.id.perf_content)
        if (!hasPriv) {
            content.alpha = 0.4f
            disableGroup(content)
            return
        }

        view.findViewById<View>(R.id.fps_row).setOnClickListener    { showFpsDialog() }
        view.findViewById<View>(R.id.thermal_row).setOnClickListener { showThermalDialog() }

        // ZRAM needs Root — if Root is absent, we visually disable the row without a dialog
        if (root.isAvailable) {
            view.findViewById<View>(R.id.zram_row).setOnClickListener { showZramDialog() }
        } else {
            view.findViewById<View>(R.id.zram_row).also { row ->
                row.alpha       = 0.38f
                row.isEnabled   = false
                row.isClickable = false
            }
        }

        loadCurrentValues()
        setupTweakToggles(view)
    }

    // ─────────────────────────────────────────────────────────────────────────
    private fun disableGroup(group: ViewGroup) {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            child.isEnabled = false
            child.isClickable = false
            if (child is ViewGroup) disableGroup(child)
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────
    private fun showFpsDialog() {
        val shizuku = ShizukoManager.getInstance(requireContext())
        val root    = RootManager.getInstance(requireContext())

        val readCmd = "dumpsys display | grep -oE 'refreshRate=[0-9]+' | grep -oE '[0-9]+' | sort -nu | tail -1"

        fun buildAndShowDialog(maxHz: Int) {
            detectedMaxHz = maxHz

            // A fixed list that always goes up to 240 + Custom
            val allRates = listOf(30, 48, 60, 90, 120, 144, 165, 240)
            val currentHz = fpsCurrentTv.text.toString().replace(" Hz", "").toIntOrNull()

            val items = mutableListOf<String>()
            items.add("Auto")
            allRates.forEach { hz ->
                val mark = if (hz == currentHz) "* " else ""
                items.add("$mark$hz Hz")
            }
            items.add(getString(R.string.custom))

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.frame_rate))
                .setItems(items.toTypedArray()) { _, which ->
                    val sel = items[which].replace("* ", "")
                    when {
                        sel == "Auto"      -> resetFps()
                        sel == getString(R.string.custom) -> showCustomFpsDialog(maxHz)
                        else               -> applyFps(sel.replace(" Hz", ""))
                    }
                }
                .show()
        }

        val readDone = { output: String ->
            val hz = output.trim().toIntOrNull()?.takeIf { it in 30..360 } ?: 60
            requireActivity().runOnUiThread { buildAndShowDialog(hz) }
        }

        when {
            root.isAvailable      -> root.runShell(readCmd, lifecycleScope, readDone)
            shizuku.hasPermission -> shizuku.runShell(readCmd, lifecycleScope, readDone)
            else                  -> buildAndShowDialog(60)
        }
    }

    private fun showCustomFpsDialog(maxHz: Int) {
        val input = android.widget.EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.example_75)
            setPadding(48, 32, 48, 32)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.custom_rate))
            .setView(input)
            .setPositiveButton(getString(R.string.apply)) { _, _ ->
                val hz = input.text.toString().toIntOrNull()
                when {
                    hz == null -> snack(getString(R.string.enter_valid_number))
                    hz < 1     -> snack(getString(R.string.min_value_1hz))
                    else       -> applyFps(hz.toString())
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showThermalDialog() {
        val modes  = ThermalMode.values()
        val labels = modes.map { getString(it.labelRes) }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.perf_thermal_title))
            .setItems(labels) { _, which -> applyThermal(modes[which]) }
            .show()
    }

    private fun showZramDialog() {
        val labels = zramValues.map { it.first }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.perf_zram_title))
            .setItems(labels) { _, which ->
                val (label, bytes) = zramValues[which]
                if (bytes == 0L) disableZram() else applyZram(bytes, label)
            }
            .show()
    }

    private val fpsSystemKeys = arrayOf(
        "peak_refresh_rate", "min_refresh_rate", "max_refresh_rate",
        "user_refresh_rate", "miui_refresh_rate", "thermal_limit_refresh_rate",
    )
    private val fpsSecureKeys = arrayOf("max_refresh_rate", "user_refresh_rate", "miui_refresh_rate")
    private val fpsGlobalKeys = arrayOf("preferred_refresh_rate", "user_preferred_refresh_rate")

    private fun settingsLine(namespace: String, key: String, hz: Int, reset: Boolean): String =
        if (reset) "settings delete $namespace $key 2>/dev/null || true"
        else "settings put $namespace $key $hz 2>/dev/null || true"

    private fun buildFpsCommand(hz: Int, reset: Boolean): String {
        val lines = mutableListOf<String>()
        fpsSystemKeys.forEach { lines += settingsLine("system", it, hz, reset) }
        fpsSecureKeys.forEach { lines += settingsLine("secure", it, hz, reset) }
        fpsGlobalKeys.forEach { lines += settingsLine("global", it, hz, reset) }
        val sfValue = if (reset) 0 else hz
        lines += "service call SurfaceFlinger 1035 i32 $sfValue 2>/dev/null || true"
        return lines.joinToString("\n") + "\n"
    }

    // ── FPS ───────────────────────────────────────────────────────────────────
    private fun applyFps(fps: String) {
        if (fps == "Auto") { resetFps(); return }
        val hz = fps.toIntOrNull() ?: return
        val cmd = buildFpsCommand(hz, false)
        execShell(cmd) {
            fpsCurrentTv.text = "$hz Hz"
            snack(getString(R.string.fps_set_to, hz))
        }
    }

    private fun resetFps() {
        val cmd = buildFpsCommand(0, true)
        execShell(cmd) {
            fpsCurrentTv.text = "Auto"
            snack(getString(R.string.fps_reset))
        }
    }

    // ── Thermal ───────────────────────────────────────────────────────────────
    private fun applyThermal(mode: ThermalMode) {
        execShell(buildThermalCmd(mode)) {
            thermalStatusTv.text = getString(mode.labelRes)
            snack(getString(R.string.performance_mode_colon, getString(mode.labelRes)))
        }
    }

    private fun buildThermalCmd(mode: ThermalMode): String {
        val lines = when (mode) {
            ThermalMode.BALANCED -> arrayOf(
                "settings put global restricted_networking_mode 0 2>/dev/null || true",
                "settings put system thermal_config_index 0 2>/dev/null || true",
                "setprop vendor.thermal.config thermal-normal 2>/dev/null || true",
                "setprop persist.vendor.qti.games.gt.propvhigh 0 2>/dev/null || true",
            )
            ThermalMode.PERFORMANCE -> arrayOf(
                "settings put system thermal_config_index 1 2>/dev/null || true",
                "setprop vendor.thermal.config thermal-camera 2>/dev/null || true",
                "setprop persist.vendor.qti.games.gt.propvhigh 1 2>/dev/null || true",
                "setprop persist.sys.perf.topAppRenderThreadBoost.enable 1 2>/dev/null || true",
            )
            ThermalMode.GAMING -> arrayOf(
                "settings put system thermal_config_index 2 2>/dev/null || true",
                "setprop vendor.thermal.config gaming-cdev-table 2>/dev/null || true",
                "setprop persist.vendor.qti.games.gt.propvhigh 1 2>/dev/null || true",
                "setprop persist.sys.perf.topAppRenderThreadBoost.enable 1 2>/dev/null || true",
                "setprop persist.game_optimizing_service.perf_tune_enable 1 2>/dev/null || true",
            )
            ThermalMode.POWERSAVE -> arrayOf(
                "settings put global low_power 1 2>/dev/null || true",
                "settings put system thermal_config_index 3 2>/dev/null || true",
                "setprop vendor.thermal.config thermal-battery 2>/dev/null || true",
                "setprop persist.vendor.qti.games.gt.propvhigh 0 2>/dev/null || true",
            )
        }
        return lines.joinToString("\n") + "\n"
    }

    // ── ZRAM ──────────────────────────────────────────────────────────────────
    private fun applyZram(bytes: Long, label: String) {
        val cmd = buildZramEnableCommand(bytes)
        execShell(cmd, needsRoot = true) {
            zramCurrentTv.text = label
            snack(getString(R.string.zram_set_to, label))
        }
    }

    private fun buildZramEnableCommand(bytes: Long): String =
        "swapoff /dev/block/zram0 2>/dev/null || true\n" +
        "echo 1 > /sys/block/zram0/reset 2>/dev/null || true\n" +
        "echo $bytes > /sys/block/zram0/disksize 2>/dev/null || true\n" +
        "mkswap /dev/block/zram0 2>/dev/null || true\n" +
        "swapon /dev/block/zram0 2>/dev/null || true\n"

    private fun disableZram() {
        val cmd = buildZramDisableCommand()
        execShell(cmd, needsRoot = true) {
            zramCurrentTv.text = getString(R.string.perf_zram_off)
            snack(getString(R.string.zram_disabled))
        }
    }

    private fun buildZramDisableCommand(): String =
        "swapoff /dev/block/zram0 2>/dev/null || true\n" +
        "echo 0 > /sys/block/zram0/disksize 2>/dev/null || true\n"

    private fun zramBucketLabel(bytes: Long, offLabel: String): String = when {
        bytes == 0L                -> offLabel
        bytes < 536_870_912L       -> "256 MB"
        bytes < 1_073_741_824L     -> "512 MB"
        bytes < 2_147_483_648L     -> "1 GB"
        bytes < 3_221_225_472L     -> "2 GB"
        bytes < 4_294_967_296L     -> "3 GB"
        bytes < 6_442_450_944L     -> "4 GB"
        bytes < 8_589_934_592L     -> "6 GB"
        else                       -> "8 GB"
    }

    // ── Load current values ───────────────────────────────────────────────────
    private fun loadCurrentValues() {
        val ctx     = requireContext()
        val shizuku = ShizukoManager.getInstance(ctx)
        val root    = RootManager.getInstance(ctx)
        if (!shizuku.hasPermission && !root.isAvailable) return

        // FPS — works with Shizuku or Root
        val fpsCmd = "settings get system peak_refresh_rate"
        val onFps: (String) -> Unit = { fps ->
            val hz = fps.trim().toFloatOrNull()?.toInt()
            fpsCurrentTv.text = if (hz != null && hz > 0) "$hz Hz" else "Auto"
        }
        when {
            root.isAvailable      -> root.runShell(fpsCmd, lifecycleScope, onFps)
            shizuku.hasPermission -> shizuku.runShell(fpsCmd, lifecycleScope, onFps)
        }

        // ZRAM — uses Root first (more reliable for /sys/block), then Shizuku as a fallback
        val zramCmd = "cat /sys/block/zram0/disksize 2>/dev/null"
        val onZram: (String) -> Unit = { raw ->
            val bytes = raw.trim().toLongOrNull() ?: 0L
            zramCurrentTv.text = zramBucketLabel(
                bytes, "${getString(R.string.off)} (0)"
            )
        }
        when {
            root.isAvailable      -> root.runShell(zramCmd, lifecycleScope, onZram)
            shizuku.hasPermission -> shizuku.runShell(zramCmd, lifecycleScope, onZram)
        }
    }

    // ── Shell helpers ─────────────────────────────────────────────────────────
    private fun execShell(cmd: String, needsRoot: Boolean = false, onDone: (String) -> Unit) {
        val ctx     = requireContext()
        val shizuku = ShizukoManager.getInstance(ctx)
        val root    = RootManager.getInstance(ctx)

        if (!root.isAvailable && !(shizuku.hasPermission && !needsRoot)) {
            snack(if (needsRoot) getString(R.string.perf_root_required)
                  else           getString(R.string.perf_need_privilege))
            return
        }

        // FIX: Android's cacheDir is often mounted noexec — writing a .sh file and
        // running it with "sh '/data/...'" fails silently on many ROMs because the
        // shell binary refuses to execute files on a noexec mount.
        // Solution: pass commands inline via "sh -c '...'" which never touches the
        // filesystem. We join lines with " ; " so multi-line scripts still run in order.
        val inline = "sh -c '${cmd.trim().replace("\n", " ; ")}'"
        when {
            root.isAvailable      -> root.runShell(inline, lifecycleScope) { onDone(it) }
            shizuku.hasPermission -> shizuku.runShell(inline, lifecycleScope) { onDone(it) }
        }
    }

    private fun snack(msg: String) =
        Snackbar.make(requireView(), msg, Snackbar.LENGTH_SHORT).show()

    // ── Tweak Toggles ─────────────────────────────────────────────────────────
    private fun setupTweakToggles(view: View) {
        val prefs = requireContext().getSharedPreferences(PREFS_TWEAKS, Context.MODE_PRIVATE)

        data class TweakDef(
            val rowId: Int,
            val switchId: Int,
            val key: String,
            val enableCmd: String,
            val disableCmd: String,
            val label: String,
            val needsRoot: Boolean = false
        )

        val tweaks = listOf(
            TweakDef(
                R.id.tweak_amoled_row, R.id.tweak_amoled_switch, KEY_AMOLED,
                enableCmd  = """
(
setprop debug.sf.treat_170m_as_sRGB 1
settings put global surface_flinger.use_color_management true
settings put global surface_flinger.max_virtual_display_dimension 8192
settings put global surface_flinger.protected_contents true
settings put global surface_flinger.has_wide_color_display true
settings put global surface_flinger.force_hwc_copy_for_virtual_displays true
) > /dev/null 2>&1

# Mode WCG (Wide Color Gamut) - BGRX_888
(
settings put global surface_flinger.wcg_composition_dataspace DISPLAY_P3
settings put global surface_flinger.default_composition_pixel_format BGRX_8888
settings put global sf.color_mode 10
settings put system screen_color_level 45000
settings put secure display_color_mode 3
settings put global night_display_color_temperature 6500
settings put global night_display_activated 0
settings put system persist.sys.sf.color_saturation 1.3
) > /dev/null 2>&1
settings put secure display_color_mode 3 > /dev/null 2>&1
    CURRENT_SATURATION=${'$'}(settings get global persist.sys.sf.color_saturation)
    if [ "${'$'}CURRENT_SATURATION" != "1.3" ]; then
        settings put global persist.sys.sf.color_saturation 1.3 > /dev/null 2>&1
    fi
 

#Aktifkan Mode HDR Universal 
(
setprop debug.enable.sglscale 0
setprop debug.egl.native_scaling false
setprop debug.enable.gamed false
setprop debug.game.video.support 1
setprop debug.mediatek.game_pq_enable 1
setprop debug.mediatek.appgamepq 2
setprop debug.mediatek.disp_decompress 1
setprop debug.mediatek.appgamepq_compress 0
setprop debug.sys.osie.neednocompress 1
setprop debug.ui.default_mapper 4
setprop debug.ui.default_gralloc 4
setprop debug.gfx.driver 1
setprop debug.gfx.early_z 1
setprop debug.sf.enable_fb_ubwc 1
setprop debug.gralloc.enable_fb_ubwc 1
setprop debug.gralloc.gfx_ubwc_disable 0
setprop debug.gralloc.map_fb_memory 0
setprop debug.gralloc.disable_ahardware_buffer 0
setprop debug.hwui.8bit_hdr_headroom true
setprop debug.sf.enable_sdr_dimming 1
setprop debug.sf.support_hdr_by_wide_color_gamut 1
setprop debug.mdpcomp.4k2kSplit 1
setprop debug.hwui.use_d2d 1
setprop debug.hwui.force_high_dpi 1
setprop debug.hwui.shadow_renderer graphite
setprop debug.composition.type gpu
setprop debug.mediatek.composition.type gpu
setprop debug.composition.7x27A.type gpu
setprop debug.composition.7x25A.type gpu
setprop debug.composition.8x25.type gpu
setprop debug.hwc.force_gpu 1
setprop debug.sf.disable_hwc 0
setprop debug.sf.enable_hwc_vds 1
setprop debug.sf.disable_hwc_vds 0
setprop debug.hwui.force_gpu_for_2d true
setprop debug.hwui.profile_gpu_render true
setprop debug.hwc.compose_level 1
setprop debug.sf.predict_hwc_composition_strategy 1
setprop debug.sf.gpu_comp_tiling 1
setprop debug.sf.force_cursor_gpu 1
setprop debug.hwui.hwc.skip_validate 1
setprop debug.disable_skip_validate 0
setprop debug.mdpcomp.mixedmode.disable false
setprop debug.mdpcomp.maxpermixer -1
setprop debug.mdpcomp.maxlayer 4
setprop debug.mdpcomp.logs 0
setprop debug.sf.disable_hwc_blit false
setprop debug.hwc.dynThreshold 2.5
setprop debug.hwc.disabletonemapping false
setprop debug.hwc_dump_en 1
) > /dev/null 2>&1

#Aktifkan Mode HDR Device (tran)
(
setprop persist.sys.hdr_dimmer_for_video_supported true
setprop persist.sys.pq.enabled 1
setprop persist.sys.pq.dci.enabled 1
setprop persist.sys.pq.cabc.enabled 1
setprop persist.sys.force_highendgfx true
setprop persist.sys.gallery_hdr_boost_max_factor 2.25
setprop persist.sys.resolutiontuner.enable true
setprop persist.sys.disable_sdr_dimming false
setprop persist.sys.composition.type gpu
setprop persist.sys.force_sw_gles 1
setprop persist.sys.use_gpu_fps_counter 1
setprop persist.sys.hwcomposer.force_gpu 1
setprop persist.sys.rendercomposer.enable true
setprop persist.sys.sf.enable_hwc_vds 1
setprop persist.sys.gpu.working_thread_priority true
setprop persist.sys.force_hw_ui true
setprop persist.sys.ui.hw 1
setprop persist.sys.ui.rendering 1 
setprop persist.sys.gpu.rendering 1
setprop persist.sys.downscale.disable false
setprop persist.sys.colorgamut.mode 1
setprop sys.hwc.mdp_downscale_enable false
setprop sys.perf.schd true
) > /dev/null 2>&1

# KCAL Settings untuk TrueTone iOS (Lebih Colorful dan Saturasi Maksimal)
(
setprop debug.sf.treat_170m_as_sRGB 1
settings put system devices_platform_kcal_ctrl.0_kcal_enable 1
settings put system devices_platform_kcal_ctrl.0_kcal_min 0
settings put system devices_platform_kcal_ctrl.0_kcal_sat 300
settings put system devices_platform_kcal_ctrl.0_kcal_hue 15
settings put system devices_platform_kcal_ctrl.0_kcal_val 290
settings put system devices_platform_kcal_ctrl.0_kcal_cont 280
settings put system devices_platform_kcal_ctrl.0_kcal_red 255
settings put system devices_platform_kcal_ctrl.0_kcal_green 240
settings put system devices_platform_kcal_ctrl.0_kcal_blue 245
) > /dev/null 2>&1

# Pengaturan Format Pixel HD [RGBA_8888]
(
settings put system surface_flinger.default_composition_pixel_format RGBA_8888
settings put system surface_flinger.wcg_composition_pixel_format RGBA_8888
settings put system minui.pixel_format RGBA_8888
setprop debug.egl.changepixelformat RGBA_8888
settings put system graphics.pixelformat RGBA_8888
) > /dev/null 2>&1

# Mode Warna P3 Display untuk Lebih Vibrant
(
setprop debug.sf.color_mode 9
setprop debug.sf.color_format RGBA_8888
settings put system surface_flinger.default_composition_dataspace DISPLAY_P3
settings put system surface_flinger.wcg_composition_dataspace DISPLAY_P3
) > /dev/null 2>&1
setprop debug.sf.native_mode 0 > /dev/null 2>&1

# Saturasi Maksimal & HDR Support
(
setprop debug.sf.color_saturation 2.2
setprop debug.hwui.use_gpu_pixel_buffers false
settings put secure hdr.display true
settings put secure hdr.enable true
settings put secure hdr.autoMode true
settings put secure hdr.photoMode true
settings put secure hdr.videoMode true
settings put secure hdr.brightness_mode 150
settings put secure hdr.contrast 120
settings put secure hdr.saturation 120
settings put secure hdr.sharpness 120
settings put secure hdr.backlight 120
settings put secure hdr.lowLight true
settings put secure hdr.noiseReduction true
settings put secure hdr.imageStabilization true
settings put global surface_flinger.use_color_management true
settings put system display_color_mode 1
) > /dev/null 2>&1

# Nonaktifkan Native Color Mode untuk Performa Maksimal
(
setprop debug.sf.native_mode 0
) > /dev/null 2>&1

# Tambahan untuk Mengurangi Lag dan Menjaga FPS Stabil
(
settings put global surface_flinger.max_virtual_display_dimension 8192
settings put global surface_flinger.force_hwc_copy_for_virtual_displays true
settings put system enable_ramdumps 0
setprop debug.persist.sys.ui.hw true
setprop debug.persist.sys.gpu.2d.enable true
) > /dev/null 2>&1
""".trimIndent(),
                disableCmd = """
(
service call SurfaceFlinger 1022 f 0.0
settings delete secure accessibility_display_daltonizer_enabled 
settings delete secure accessibility_display_daltonizer 
settings delete secure display_color_mode 
settings delete secure reduce_bright_colors_level
settings delete secure reduce_bright_colors_activated 
settings delete secure accessibility_display_daltonizer_enabled
settings delete secure accessibility_display_daltonizer 
# Gunakan Overlay untuk tweak warna lebih lanjut
settings delete global night_display_activated 
settings delete global night_display_color_temperature
# Mengembalikan Nilai Night Light
settings delete global night_display_activated 
settings delete global night_display_color_temperature 
# Mengembalikan KCAL ke Default
setprop debug.sf.treat_170m_as_sRGB ""
settings delete system devices_platform_kcal_ctrl.0_kcal_min
settings delete system devices_platform_kcal_ctrl.0_kcal_enable
settings delete system devices_platform_kcal_ctrl.0_kcal_sat
settings delete system devices_platform_kcal_ctrl.0_kcal_hue
settings delete system devices_platform_kcal_ctrl.0_kcal_val
settings delete system devices_platform_kcal_ctrl.0_kcal_cont
# Mengatur Pixel Format ke Default
settings delete system surface_flinger.default_composition_pixel_format
settings delete system surface_flinger.wcg_composition_pixel_format
settings delete system minui.pixel_format
setprop debug.egl.changepixelformat ""
settings delete system graphics.pixelformat
# Mengembalikan Mode Warna & DataSpace
setprop debug.sf.color_mode ""
setprop debug.sf.color_format ""
settings delete system surface_flinger.default_composition_dataspace
settings delete system surface_flinger.wcg_composition_dataspace
# Mengaktifkan Mode Warna Asli
setprop debug.sf.native_mode ""
# Menonaktifkan Peningkatan Saturasi
setprop debug.sf.color_saturation ""
settings delete global surface_flinger.use_color_management
settings delete global surface_flinger.max_virtual_display_dimension
settings delete global surface_flinger.protected_contents
settings delete global surface_flinger.has_wide_color_display
settings delete global surface_flinger.force_hwc_copy_for_virtual_displays
setprop debug.hwui.use_gpu_pixel_buffers ""
# Mengembalikan Pengaturan HDR
settings delete secure reduce_bright_colors_level
settings delete secure reduce_bright_colors_persist_across_reboots
settings delete system display_color_mode
settings delete system screen_color_level
settings delete secure color.matrix
settings delete system perf.framepacing.enable
settings delete system has_HDR_display
settings delete system has_wide_color_display
settings delete system max_frame_buffer_accquiredaccquired_buffers
settings delete system enable_ramdumps
settings delete secure hdr.display
settings delete secure hdr.enable
settings delete secure hdr.autoMode
settings delete secure hdr.photoMode
settings delete secure hdr.videoMode
settings delete secure hdr.brightness_mode
settings delete secure hdr.contrast
settings delete secure hdr.saturation
settings delete secure hdr.sharpness
settings delete secure hdr.backlight
settings delete secure hdr.imageStabilization
settings delete secure hdr.autoAlign
settings delete secure hdr.noiseReduction
settings delete secure hdr.lowLight
) > /dev/null 2>&1

# Mengembalikan semua pengaturan ke default
(
    settings delete global surface_flinger.use_color_management
    settings delete global surface_flinger.max_virtual_display_dimension
    settings delete global surface_flinger.protected_contents
    settings delete global surface_flinger.has_wide_color_display
    settings delete global surface_flinger.force_hwc_copy_for_virtual_displays

    settings delete global surface_flinger.wcg_composition_dataspace
    settings delete global surface_flinger.default_composition_pixel_format
    settings delete global sf.color_mode
    settings delete system screen_color_level
    settings delete secure display_color_mode
    settings delete global night_display_color_temperature
    settings delete global night_display_activated
    settings delete system persist.sys.sf.color_saturation
) > /dev/null 2>&1
""".trimIndent(),
                label = getString(R.string.tweak_amoled_title), needsRoot = true
            ),
            TweakDef(
                R.id.tweak_audio_row, R.id.tweak_audio_switch, KEY_AUDIO,
                enableCmd  = """
tweak() {
# Tweak pengaturan audio

# Volume kontrol media lebih tinggi
settings put system media_volume 15

# Volume kontrol nada dering lebih tinggi
settings put system ring_volume 10

# Volume alarm maksimal
settings put system alarm_volume 7

# Pengaturan audio latency lebih rendah
settings put global audio_latency_ms 10  # Lebih rendah dari sebelumnya

# Pengaturan pengurangan echo
settings put secure voice_recognition_echo_cancellation_enabled 1

# Pengaturan kualitas suara HD
settings put system enable_hd_audio 1

# Pengaturan bass boost (jika perangkat mendukung)
settings put system audio_bass_boost_level 4  # Lebih tinggi dari sebelumnya

# Pengaturan treble boost (jika perangkat mendukung)
settings put system audio_treble_boost_level 5  # Lebih tinggi dari sebelumnya

# Pengaturan pengurangan noise
settings put global audio_noise_suppression 1

# Pengaturan mode audio gaming
settings put global gaming_audio_mode 1

# Mengaktifkan audio focus
settings put global audio_focus_enabled 1

# Mengaktifkan Sound Efek
settings put system sound_effects_enabled 1

# Mengaktifkan efek SFX (bila ada)
settings put system audio_effects_enabled 1

# Surround sound (jika didukung)
settings put global surround_sound_enabled 1

# Menurunkan latency untuk gaming
settings put global low_latency_mode 1

# Tambahan untuk suara lebih jernih:
# Pengaturan Virtualizer untuk lebih banyak kedalaman suara
settings put global audio_virtualizer_strength 1000

# Pengaturan bass dan treble balance (lebih lembut seperti iPhone)
settings put system audio_bass_balance 0
settings put system audio_treble_balance 0

# Pengaturan clarity boost (suara lebih jernih)
settings put system audio_clarity_boost 1

# Equalizer khusus untuk kejernihan suara
settings put system audio_equalizer "flat" # Pengaturan flat sering digunakan iPhone
}

tweak > /dev/null 2>&1 

# Set untuk debugging audio
setprop debug.audio.silent 1 > /dev/null 2>&1
setprop debug.audio.silent_2 1 > /dev/null 2>&1

# Boost audio dan pengaturan kualitas HD
setprop debug.audio.sound_boost 1 > /dev/null 2>&1
setprop debug.audio.hd_enable 1 > /dev/null 2>&1
setprop debug.audio.ultra_hd 1 > /dev/null 2>&1

# Surround sound ultra HD
setprop debug.audio.surround_sound 1 > /dev/null 2>&1

# Pengaturan tambahan untuk audio
setprop debug.audio.stereo 1 > /dev/null 2>&1
setprop debug.audio.low_latency 1 > /dev/null 2>&1
setprop debug.audio.dynamic_range 1 > /dev/null 2>&1
setprop debug.audio.virtualization 1 > /dev/null 2>&1
setprop debug.audio.echo_cancellation 1 > /dev/null 2>&1
setprop debug.audio.noise_suppression 1 > /dev/null 2>&1
setprop debug.audio.bass_boost 1 > /dev/null 2>&1
setprop debug.audio.treble_boost 1 > /dev/null 2>&1
setprop debug.audio.surround_virtualizer 1 > /dev/null 2>&1

# Mengaktifkan ultra-low latency mode
setprop debug.audio.ultra_low_latency 1 > /dev/null 2>&1
""".trimIndent(),
                disableCmd = """
tweak() {
# Tweak pengaturan audio

# Hapus pengaturan volume kontrol media
settings delete system media_volume 

# Hapus pengaturan volume kontrol nada dering
settings delete system ring_volume 

# Hapus pengaturan volume alarm
settings delete system alarm_volume 

# Hapus pengaturan audio latency
settings delete global audio_latency_ms 

# Hapus pengaturan pengurangan echo
settings delete secure voice_recognition_echo_cancellation_enabled 

# Hapus pengaturan kualitas suara HD
settings delete system enable_hd_audio 

# Hapus pengaturan bass boost
settings delete system audio_bass_boost_level 

# Hapus pengaturan treble boost
settings delete system audio_treble_boost_level 

# Hapus pengaturan pengurangan noise
settings delete global audio_noise_suppression 

# Hapus pengaturan mode audio gaming
settings delete global gaming_audio_mode 

# Hapus pengaturan audio focus
settings delete global audio_focus_enabled 

# Hapus Sound Efek
settings delete system sound_effects_enabled 

# Hapus pengaturan efek SFX
settings delete system audio_effects_enabled 

# Hapus pengaturan surround sound
settings delete global surround_sound_enabled 

# Hapus pengaturan low latency untuk gaming
settings delete global low_latency_mode 

# Hapus tambahan pengaturan untuk suara lebih jernih
settings delete global audio_virtualizer_strength 

# Hapus pengaturan bass dan treble balance
settings delete system audio_bass_balance 
settings delete system audio_treble_balance 

# Hapus pengaturan clarity boost
settings delete system audio_clarity_boost 

# Hapus pengaturan equalizer
settings delete system audio_equalizer 
}

tweak > /dev/null 2>&1 

# Set untuk debugging audio ke mode default
setprop debug.audio.silent 0 > /dev/null 2>&1
setprop debug.audio.silent_2 0 > /dev/null 2>&1

# Set untuk boost audio dan pengaturan kualitas HD ke mode default
setprop debug.audio.sound_boost 0 > /dev/null 2>&1
setprop debug.audio.hd_enable 0 > /dev/null 2>&1
setprop debug.audio.ultra_hd 0 > /dev/null 2>&1

# Surround sound ultra HD ke mode default
setprop debug.audio.surround_sound 0 > /dev/null 2>&1

# Pengaturan tambahan untuk audio ke mode default
setprop debug.audio.stereo 0 > /dev/null 2>&1
setprop debug.audio.low_latency 0 > /dev/null 2>&1
setprop debug.audio.dynamic_range 0 > /dev/null 2>&1
setprop debug.audio.virtualization 0 > /dev/null 2>&1
setprop debug.audio.echo_cancellation 0 > /dev/null 2>&1
setprop debug.audio.noise_suppression 0 > /dev/null 2>&1
setprop debug.audio.bass_boost 0 > /dev/null 2>&1
setprop debug.audio.treble_boost 0 > /dev/null 2>&1
setprop debug.audio.surround_virtualizer 0 > /dev/null 2>&1

# Ultra-low latency mode ke mode default
setprop debug.audio.ultra_low_latency 0 > /dev/null 2>&1
""".trimIndent(),
                label = getString(R.string.tweak_audio_title), needsRoot = false
            ),
            TweakDef(
                R.id.tweak_diskio_row, R.id.tweak_diskio_switch, KEY_DISKIO,
                enableCmd  = """
(
settings put system POWER_SAVE_PRE_HIDE_MODE performance
settings put secure speed_mode_enable 1
settings put system speed_mode 1
settings put global restricted_device_performance_enabled 0
settings put global restricted_device_performance_power_level 0
settings put global game_service_mode 2
settings put global game_mode 2
settings put global game_service_game_process_enable 1
settings put global game_process_boost_enabled 1
) > /dev/null 2>&1

(
#V9.0
settings put global product.gpu.driver 1
setprop debug.hwui.render_dirty_regions false
settings put system use_dithering 0
setprop debug.enabletr true
settings put global vold.umsdirtyratio 15
setprop debug.overlayui.enable 1
setprop debug.gralloc.gfx_ubwc_disable 0
setprop debug.sf.enable_advanced_sf_phase_offset 0
settings put system execution-mode int optimized
settings put global config.low_ram true
) > /dev/null 2>&1

(
#V8.0
settings put global ro.transsion.launcher_boost_scene_support 1
settings put system windowsmgr.max_events_per_sec 120
settings put global logcat.live disable
settings put global persist.sys.surfaceflinger.idle_reduce_framerate_enable true
settings put global persist.vendor.camera.HAL3.enabled 1
settings put global video.accelerate.hw 1
settings put system persist.sys.ui.hw true
settings put global mtk_perf_response_time 1
settings put global mtk_perf_fast_start_win 1
settings put global tran_high_temperature_fps.support 1
settings put global transsion.cpubooster.heavy_loading.support 1
settings put global esports.thermal_config.support 1
settings put global os_game_tp_esports10.support 1
settings put global game_tp_esports20.support 1
settings put global game_tp_optimization.support 1
settings put global game_network_acceleration.support 1
settings put global ro.transsion.disable_sf_sched 1
) > /dev/null 2>&1

#Percepat aplikasi 
(
settings put global iorapd.readahead.enable true
settings put global iorapd.perfetto.enable true
) > /dev/null 2>&1

#NEWV7
(
cmd device_config put activity_manager max_phantom_processes 2147483647
cmd device_config put activity_manager max_cached_processes 256
cmd device_config put activity_manager max_empty_time_millis 43200000
setprop debug.sf.gpu_comp_tiling 1
cmd thermalservice override-status 0
cmd power set-adaptive-power-saver-enabled false
cmd power set-fixed-performance-mode-enabled true
settings put global dropbox:dumpsys:procstats disabled
settings put global dropbox:dumpsys:usagestats disabled  
) > /dev/null 2>&1

#NEWV6
(
setprop debug.biner.use_async_exec 1
setprop debug.hwui.preallocate_frame true
setprop debug.rs.forcerecompile false
setprop debug.extractor.ignore_version 1
setprop debug.hwui.use_smart_batching true
setprop debug.hwui.renderthread_wakeup_ms 0
setprop debug.dls.weight 100
setprop debug.hwui.ffpe true
setprop debug.hwui.layer_cache_key_hashing true
setprop debug.art.max-threads 8
setprop debug.dex2oat.max-threads 4
setprop debug.hwui.use_gl_draw_to_create 1
setprop debug.sf.fault_native_asserts 1
setprop debug.hexcode.use.binary 1
setprop debug.sf.ignore_hwc_physical_display_orientation true
setprop debug.sf.hwc_hotplug_error_via_neg_vsyn false
) > /dev/null 2>&1

#NEWV5
(
setprop debug.hwui.compression.type ETC
setprop debug.hwui.compres.type ETC
settings put system gpu_perf_mode 1
settings put global perf.framepacing.enable false
settings put system purgeable_assets 1
setprop debug.surface_flinger.max_frame_buffer_acquired_buffers 3
) > /dev/null 2>&1

#NEWV4
(
settings put system sound_effects_enabled 1
settings put system background_settle_time 0
settings put system fgservice_min_shown_time 0
settings put system fgservice_min_report_time 0
settings put system fgservice_screen_on_before_time 0
settings put system fgservice_screen_on_after_time 0
settings put system content_provider_retain_time 0
settings put system service_usage_interaction_time 0
settings put system service_max_inactivity 0
settings put system service_bg_start_timeout 0
) > /dev/null 2>&1

#NEWV3
(
settings put system block_sda_queue_scheduler noop
settings put system block_sda_queue_read_ahead_kb 512
settings put system block_sda_queue_rotational 0
settings put system block_sda_queue_iostats 0
settings put system block_sda_queue_add_random 0
settings put system block_sda_queue_rq_affinity 1
settings put system block_sda_queue_nomerges 0
settings put system block_sda_queue_nr_requests 256
setprop debug.sf.disable_backpressure 1
setprop debug.sf.latch_unsignaled 1
setprop debug.sf.enable_hwc_vds 1
setprop debug.sf.early_phase_offset_ns 500000
setprop debug.sf.early_app_phase_offset_ns 500000
setprop debug.sf.early_gl_phase_offset_ns 3000000
setprop debug.sf.early_gl_app_phase_offset_ns 15000000
setprop debug.sf.high_fps_early_phase_offset_ns 6100000
setprop debug.sf.high_fps_early_gl_phase_offset_ns 650000
setprop debug.sf.high_fps_late_app_phase_offset_ns 100000
setprop debug.sf.phase_offset_threshold_for_next_vsync_ns 6100000
) > /dev/null 2>&1

#NEWV2
#Qualcomm
(
setprop debug.com.qc.hardware 1
setprop debug.qc.hardware true
setprop debug.qctwa.preservebuf 1
setprop debug.qctwa.statusbar 1
) > /dev/null 2>&1

#Mediatek
(
settings put global mtk_perf_simple_enable 1
settings put system mtk_perf_simple.enable 1
settings put system mtk_perfservice_support 1
settings put system mtk_sched_boost_enabled 1
settings put global mtk_power_mode_switch 1
) > /dev/null 2>&1

(
setprop debug.performance.tuning 1
setprop debug.egl.hw 1
setprop debug.tool.anrhistory 0
setprop debug.force_hw_ui true
setprop debug.hw2d.force 1
setprop debug.monitor false
setprop debug.logcat.live disable
settings put system logd.kernel false
settings put system config.htc.nocheckin 1
settings put system profiler.launch false
settings put system profilerhung.dumpdobugreport false
settings put system profiler.force_disable_err_rpt 1
settings put system profiler.force_disable_ulog 1
) > /dev/null 2>&1

#BOOSTER TWEAK
(
setprop debug.qcom_kgsl-3d0_adrenoboost enable 
settings put system simple_gpu_algorithm_parameters 1
settings put system simple_gpu_activate 1
) > /dev/null 2>&1

#TWIZZ TWEAK PERF
(
settings put system purgeable_assets 1
settings put system surface_flinger.max_frame_buffer_acquired_buffers 3
settings put system telephony.call_ring.delay 0
settings put system bq.gpu_to_cpu_unsupported 1
settings put system ril.disable.power.collapse 0
settings put system sf.compbypass.enable 0
settings put system ril.enable.a52 1
settings put system ril.enable.a53 0
settings put system hwui.render_dirty_regions false
settings put system NV_FPSLIMIT 120
settings put system NV_POWERMODE 1
settings put system NV_PROFVER 15
settings put system NV_STEREOCTRL 0
settings put system NV_STEREOSEPCHG 0
settings put system NV_STEREOSEP 25
setprop debug.egl.hw 1
setprop debug.composition.type gpu
setprop debug.sf.showcpu 0
setprop debug.sf.showcpu 0
settings put system dalvik.hyperthreading true
settings put system dalvik.multithread true
setprop debug.force_hw_ui true
setprop debug.hw2d.force 1
setprop debug.hw3d.force 1
settings put system com.qc.hardware true
setprop debug.hwui.disable_vsync true
settings put system config.disable.hw_accel false
settings put system product.gpu.driver 1
setprop debug.fb.mode 1
settings put system ui.hw 1
setprop debug.sf.disable_blurs 1
settings put system purgeable_assets 1
settings put system service.lgospd.enable 0
settings put system service.pcsync.enable 0
settings put system use_16bpp_alpha 1
settings put system android.strictmode 0
settings put system config.nocheckin 1
settings put system com.qc.hardware 1
setprop debug.qctwa.preservebuf 1
setprop debug.qctwa.statusbar 1
settings put global data.large_tcp_window_size true
settings put global ril.set.mtu1472 1
settings put system cust.tel.eons 1
settings put system config.hw_fast_dormancy 1
settings put global zygote.preload.enable 1
settings put system device_config.runtime_native.usap_pool_enabled true
settings put global quick_start_support 1
settings put system adb.notify 0
settings put system adb.enable 0
) > /dev/null 2>&1
""".trimIndent(),
                disableCmd = """
(
settings delete system POWER_SAVE_PRE_HIDE_MODE 
settings delete secure speed_mode_enable 
settings delete system speed_mode 
settings delete global restricted_device_performance_enabled 
settings delete global restricted_device_performance_power_level 
settings delete global game_service_mode 
settings delete global game_mode 
settings delete global game_service_game_process_enable 
settings delete global game_process_boost_enabled 
) > /dev/null 2>&1

(
#V9.0 uninstall 
settings delete global product.gpu.driver 
settings delete system use_dithering 
settings delete global vold.umsdirtyratio
setprop debug.overlayui.enable ""
setprop debug.gralloc.gfx_ubwc_disable ""
setprop debug.sf.enable_advanced_sf_phase_offset ""
settings delete system execution-mode int
settings delete global config.low_ram true
) > /dev/null 2>&1

(
#V8.0 Uninstall
settings delete global ro.transsion.launcher_boost_scene_support
settings delete system windowsmgr.max_events_per_sec
settings delete global logcat.live
settings delete global persist.sys.surfaceflinger.idle_reduce_framerate_enable
settings delete global persist.vendor.camera.HAL3.enabled
settings delete global video.accelerate.hw
settings delete system persist.sys.ui.hw
settings delete global mtk_perf_response_time
settings delete global mtk_perf_fast_start_win
settings delete global tran_high_temperature_fps.support
settings delete global transsion.cpubooster.heavy_loading.support
settings delete global esports.thermal_config.support
settings delete global os_game_tp_esports10.support
settings delete global game_tp_esports20.support
settings delete global game_tp_optimization.support
settings delete global game_network_acceleration.support
settings delete global ro.transsion.disable_sf_sched
) > /dev/null 2>&1

# Uninstall iorapd and perfetto settings
(
settings delete global iorapd.readahead.enable
settings delete global iorapd.perfetto.enable
) > /dev/null 2>&1

#NEWV7
(
cmd device_config delete activity_manager max_phantom_processes
cmd device_config delete activity_manager max_cached_processes
cmd device_config delete activity_manager max_empty_time_millis
setprop debug.sf.gpu_comp_tiling ""
cmd thermalservice reset-status
cmd power set-adaptive-power-saver-enabled true
cmd power set-fixed-performance-mode-enabled false
settings delete global dropbox:dumpsys:procstats
settings delete global dropbox:dumpsys:usagestats
) > /dev/null 2>&1

# Uninstall NEWV6 settings
(
setprop debug.biner.use_async_exec ""
setprop debug.hwui.preallocate_frame false
setprop debug.rs.forcerecompile true
setprop debug.extractor.ignore_version ""
setprop debug.hwui.use_smart_batching false
setprop debug.hwui.renderthread_wakeup_ms ""
setprop debug.dls.weight ""
setprop debug.hwui.ffpe false
setprop debug.hwui.layer_cache_key_hashing false
setprop debug.art.max-threads ""
setprop debug.dex2oat.max-threads ""
setprop debug.hwui.use_gl_draw_to_create false
setprop debug.sf.fault_native_asserts false
setprop debug.hexcode.use.binary ""
setprop debug.sf.ignore_hwc_physical_display_orientation false
setprop debug.sf.hwc_hotplug_error_via_neg_vsyn true
) > /dev/null 2>&1

#V5.0 DELETE
(
setprop debug.hwui.compression.type ""
setprop debug.hwui.compres.type ""
setprop debug.surface_flinger.max_frame_buffer_acquired_buffers ""
settings delete system gpu_perf_mode
settings delete global perf.framepacing.enable
settings delete system purgeable_assets
) > /dev/null 2>&1

# Menghapus pengaturan dengan settings
(
settings delete global mtk_perf_simple_enable
settings delete system mtk_perf_simple.enable
settings delete system mtk_perfservice_support
settings delete system mtk_sched_boost_enabled
settings delete global mtk_power_mode_switch
) > /dev/null 2>&1

#V4 DELETE
(
settings delete system background_settle_time
settings delete system fgservice_min_shown_time
settings delete system fgservice_min_report_time
settings delete system fgservice_screen_on_before_time
settings delete system fgservice_screen_on_after_time
settings delete system content_provider_retain_time
settings delete system service_usage_interaction_time
settings delete system service_max_inactivity
settings delete system service_bg_start_timeout
) > /dev/null 2>&1

#V3 DELETE 
(
settings delete system block_sda_queue_scheduler
settings delete system block_sda_queue_read_ahead_kb
settings delete system block_sda_queue_rotational
settings delete system block_sda_queue_iostats
settings delete system block_sda_queue_add_random
settings delete system block_sda_queue_rq_affinity
settings delete system block_sda_queue_nomerges
settings delete system block_sda_queue_nr_requests
setprop debug.sf.disable_backpressure ""
setprop debug.sf.latch_unsignaled ""
setprop debug.sf.enable_hwc_vds ""
setprop debug.sf.early_phase_offset_ns ""
setprop debug.sf.early_app_phase_offset_ns ""
setprop debug.sf.early_gl_phase_offset_ns ""
setprop debug.sf.early_gl_app_phase_offset_ns ""
setprop debug.sf.high_fps_early_phase_offset_ns ""
setprop debug.sf.high_fps_early_gl_phase_offset_ns ""
setprop debug.sf.high_fps_late_app_phase_offset_ns ""
setprop debug.sf.phase_offset_threshold_for_next_vsync_ns ""
) > /dev/null 2>&1

#BOOSTER TWEAK
(
settings delete system simple_gpu_algorithm_parameters
settings delete system simple_gpu_activate
#TWIZZ TWEAK PERF 
settings delete system purgeable_assets
settings delete system surface_flinger.max_frame_buffer_acquired_buffers
settings delete system telephony.call_ring.delay
settings delete system bq.gpu_to_cpu_unsupported
settings delete system ril.disable.power.collapse
settings delete system sf.compbypass.enable
settings delete system ril.enable.a52
settings delete system ril.enable.a53
settings delete system hwui.render_dirty_regions
settings delete system NV_FPSLIMIT
settings delete system NV_POWERMODE
settings delete system NV_PROFVER
settings delete system NV_STEREOCTRL
settings delete system NV_STEREOSEPCHG
settings delete system NV_STEREOSEP
settings delete system dalvik.hyperthreading
settings delete system dalvik.multithread
settings delete system com.qc.hardware
settings delete system config.disable.hw_accel
settings delete system product.gpu.driver
settings delete system config.enable.hw_accel
settings delete system ui.hw
settings delete system purgeable_assets
settings delete system service.lgospd.enable
settings delete system service.pcsync.enable
settings delete system use_16bpp_alpha
settings delete system android.strictmode
settings delete system config.nocheckin
settings delete system com.qc.hardware
settings delete global data.large_tcp_window_size
settings delete global ril.set.mtu1472
settings delete system cust.tel.eons
settings delete system config.hw_fast_dormancy
settings delete global zygote.preload.enable
settings delete system device_config.runtime_native.usap_pool_enabled
settings delete global quick_start_support
) > /dev/null 2>&1
""".trimIndent(),
                label = getString(R.string.tweak_diskio_title), needsRoot = false
            ),
            TweakDef(
                R.id.tweak_connectivity_row, R.id.tweak_connectivity_switch, KEY_CONNECTIVITY,
                enableCmd  = """
(
#Ping Booster 
settings put global private_dns_specifier one.one.one.one
settings put global private_dns_mode hostname
settings put global preferred_network_mode 13
settings put global private_dns_mode hostname
settings put global network_traffic_optimization 1  
settings put global game_traffic_optimization 1  
settings put global restrict_background_data 1  
settings put global mobile_data_always_on 1
device_config put netd_native dot_query_timeout_ms 50  
device_config put netd_native dot_connect_timeout_ms 100
) > /dev/null 2>&1

(
#Wifi Tweak
device_config put wifi badging_threshold_bandwidth_kbps 10000  
device_config put wifi badging_threshold_signal_dbm -65  
device_config put wifi wakeup_enabled false  
device_config put wifi linked_networks_enabled false  
device_config put wifi aggressive_handovers_enabled false  
device_config put wifi avoid_bad_wifi_connections 1
device_config put netd_native dot_connect_timeout_ms 300
device_config put wifi connection_failure_high_thr_percent 25
device_config put netd_native doh 1
device_config put wifi assoc_timeout_high_thr_percent 90
device_config put wifi auth_failure_high_thr_percent 90
device_config put netd_native dot_query_timeout_ms -1
device_config put wifi disconnection_nonlocal_high_thr_percent 85
device_config put wifi assoc_rejection_high_thr_percent 90
device_config put netd_native dot_validation_latency_offset_ms 25
device_config put wifi connection_failure_disconnection_high_thr_percent 85
device_config put netd_native dot_xport_unusable_threshold -1
) > /dev/null 2>&1

(
#Fast connectivity
device_config put connectivity tcp_default_init_rwnd 60  
device_config put connectivity tcp_user_cfg_ack_prioritization_enabled true  
device_config put connectivity tcp_user_cfg_fastopen_enabled true  
device_config put connectivity tcp_user_cfg_delack_enabled true  
device_config put connectivity tcp_user_cfg_westwood_enabled true
) > /dev/null 2>&1

#V4.0
(
settings put system net.ipv4.tcp_timestamps 0
settings put system net.ipv4.tcp_sack 1
settings put system net.ipv4.tcp_tw_recycle 1
settings put system net.ipv4.tcp_tw_reuse 1
settings put system net.ipv4.tcp_window_scaling 1
settings put system net.ipv4.tcp_keepalive_probes 5
settings put system net.ipv4.tcp_fin_timeout 30
settings put system net.ipv4.tcp_keepalive_intvl 30
) > /dev/null 2>&1

(
#MAX DWONLOAD FAST 
settings put global download_manager_max_bytes_over_mobile 21390950
settings put global download_manager_recommended_max_bytes_over_mobile 21390950
) > /dev/null 2>&1

(
#V2WIFIBOOSTER
settings put system ngChannelBondingMode24GHz 1
settings put system ngChannelBondingMode5GHz 1
settings put system ngForce1x1Exception 0
) > /dev/null 2>&1

(
#WIFI LATENCY
settings put system net.ipv4.route.flush 1
settings put system net.ipv4.ip_no_pmtu_disc 0
settings put system net.ipv4.tcp_ecn 0
settings put system net.ipv4.tcp_fack 1
settings put system net.ipv4.tcp_moderate_rcvbuf 1
settings put system net.ipv4.tcp_no_metrics_save 1
settings put system net.ipv4.tcp_rfc1337 1
settings put system net.ipv4.tcp_sack 1
settings put system net.ipv4.tcp_timestamps 1
settings put system net.ipv4.tcp_window_scaling 1
settings put system net.ipv4.tcp_rmem 4096,39000,187000
settings put system net.ipv4.tcp_wmem 4096,39000,187000
settings put system net.ipv4.tcp_mem 187000,187000,187000
settings put system net.tcp.buffersize.default 4096,87380,256960,4096,16384,256960
settings put system net.tcp.buffersize.wifi 4096,87380,256960,4096,16384,256960
settings put system net.tcp.buffersize.umts 4096,87380,256960,4096,16384,256960
settings put system net.tcp.buffersize.gprs 4096,87380,256960,4096,16384,256960
settings put system net.tcp.buffersize.edge 4096,87380,256960,4096,16384,256960
settings put system net.tcp.buffersize.lte 262144,524288,3145728,262144,524288,3145728
settings put system net.tcp.buffersize.hsdpa 6144,262144,1048576,6144,262144,1048576
settings put system net.tcp.buffersize.evdo_b 6144,262144,1048576,6144,262144,1048576
settings put system net.tcp.buffersize.hspa 6144,87380,262144,6144,16384,262144
settings put system ril.enable.fd.plmn.prefix 23402,23410,23411
settings put system ril.fast.dormancy.rule 1
settings put system fast.dormancy 1
settings put system cust.tel.eons 1
) > /dev/null 2>&1

#PingBooster 
(
settings put system net.ipv4.ip_no_pmtu_disc 0
settings put system config.hw_quickpoweron true
settings put system net.ipv4.tcp_ecn 0
settings put system net.ipv4.route.flush 1
settings put system net.core.netdev_max_backlog 5000
settings put system net.core.netdev_budget 2500
settings put system net.ipv4.tcp_fack 1
settings put system net.core.netdev_budget_usecs 250
settings put system net.ipv4.tcp_mem 187000
settings put system net.ipv4.ip_no_pmtu_disc 0
settings put system net.ipv4.route.flush 1
settings put system net.ipv4.tcp_ecn  0
settings put system net.ipv4.tcp_fack 1
) > /dev/null 2>&1
""".trimIndent(),
                disableCmd = """
(
settings delete global private_dns_specifier
settings delete global private_dns_mode 
settings delete system net.ipv4.tcp_timestamps 
settings delete system net.ipv4.tcp_sack 
settings delete system net.ipv4.tcp_tw_recycle 
settings delete system net.ipv4.tcp_tw_reuse 
settings delete system net.ipv4.tcp_window_scaling 
settings delete system net.ipv4.tcp_keepalive_probes 
settings delete system net.ipv4.tcp_fin_timeout 
settings put system net.ipv4.tcp_keepalive_intvl 
settings delete global download_manager_max_bytes_over_mobile 
settings delete global download_manager_recommended_max_bytes_over_mobile
settings delete system gChannelBondingMode24GHz
settings delete system ngChannelBondingMode5GHz
settings delete system ngForce1x1Exception
settings delete system net.ipv4.route.flush
settings delete system net.ipv4.ip_no_pmtu_disc
settings delete system net.ipv4.tcp_ecn
settings delete system net.ipv4.tcp_fack
settings delete system net.ipv4.tcp_moderate_rcvbuf
settings delete system net.ipv4.tcp_no_metrics_save
settings delete system net.ipv4.tcp_rfc1337
settings delete system net.ipv4.tcp_sack
settings delete system net.ipv4.tcp_timestamps
settings delete system net.ipv4.tcp_window_scaling
settings delete system net.ipv4.tcp_rmem
settings delete system net.ipv4.tcp_wmem
settings delete system net.ipv4.tcp_mem
settings delete system net.tcp.buffersize.default
settings delete system net.tcp.buffersize.wifi
settings delete system net.tcp.buffersize.umts
settings delete system net.tcp.buffersize.gprs
settings delete system net.tcp.buffersize.edge
settings delete system net.tcp.buffersize.lte
settings delete system net.tcp.buffersize.hsdpa
settings delete system net.tcp.buffersize.evdo_b
settings delete system net.tcp.buffersize.hspa
settings delete system ril.enable.fd.plmn.prefix
settings delete system ril.fast.dormancy.rule
settings delete system fast.dormancy
settings delete system cust.tel.eons
settings delete system config.hw_quickpoweron
settings delete system net.core.netdev_max_backlog
settings delete system net.core.netdev_budget
settings delete system net.core.netdev_budget_usecs
) > /dev/null 2>&1
(
# Hapus Ping Booster
settings delete global network_traffic_optimization  
settings delete global game_traffic_optimization  
settings delete global restrict_background_data  
settings delete global mobile_data_always_on  
settings delete global private_dns_mode  
settings delete global private_dns_specifier  
device_config delete netd_native dot_query_timeout_ms  
device_config delete netd_native dot_connect_timeout_ms  
) > /dev/null 2>&1

(
# Hapus WiFi Tweak
device_config delete wifi badging_threshold_bandwidth_kbps  
device_config delete wifi badging_threshold_signal_dbm  
device_config delete wifi wakeup_enabled  
device_config delete wifi linked_networks_enabled  
device_config delete wifi aggressive_handovers_enabled  
device_config delete wifi avoid_bad_wifi_connections  
device_config delete netd_native dot_connect_timeout_ms  
device_config delete wifi connection_failure_high_thr_percent  
device_config delete netd_native doh  
device_config delete wifi assoc_timeout_high_thr_percent  
device_config delete wifi auth_failure_high_thr_percent  
device_config delete netd_native dot_query_timeout_ms  
device_config delete wifi disconnection_nonlocal_high_thr_percent  
device_config delete netd_native dns_event_subsample_map  
device_config delete wifi assoc_rejection_high_thr_percent  
device_config delete netd_native dot_validation_latency_offset_ms  
device_config delete wifi connection_failure_disconnection_high_thr_percent  
device_config delete netd_native dot_xport_unusable_threshold  
) > /dev/null 2>&1

(
# Hapus Fast Connectivity
device_config delete connectivity tcp_default_init_rwnd  
device_config delete connectivity tcp_user_cfg_ack_prioritization_enabled  
device_config delete connectivity tcp_user_cfg_fastopen_enabled  
device_config delete connectivity tcp_user_cfg_delack_enabled  
device_config delete connectivity tcp_user_cfg_westwood_enabled  
) > /dev/null 2>&1
""".trimIndent(),
                label = getString(R.string.tweak_connectivity_title), needsRoot = false
            ),
            TweakDef(
                R.id.tweak_thermalkiller_row, R.id.tweak_thermalkiller_switch, KEY_THERMALKILL,
                enableCmd  = """
(
    cmd power set-fixed-performance-mode-enabled true
    settings put system POWER_PERFORMANCE_MODE_OPEN 1
    cmd thermalservice override-status 0
    setprop debug.performance.tuning 1
    setprop debug.sf.perf_mode 1
    setprop debug.thermal.cpu_thermal_throttle.disable 1
    setprop debug.power.throttling.disable 1
    setprop persist.sys.gpu_perf_mode 1
    setprop sys.force_boost_cpu true
    setprop debug.thermal.shutdown.disable 1
    # spoof suhu
    cmd thermalservice inject-temperature CPU light cpu0 120.000
    cmd thermalservice inject-temperature GPU light gpu0 120.000
    setprop debug.gpu.thermal.temp 150
    settings put system battery.temp_high 90
    settings put global vendor.dfps.enable false
    settings put global vendor.smart_dfps.enable false
    settings put system virtual_thermal_thermal_zone false
    settings put global thermal_throttling 0
    settings put global thermal_pwrlevel 0
    #Disabled Thermal No Root (Gimick)
    setprop debug.init.svc.thermald stopped
    setprop debug.init.svc_debug_pid.vendor.thermal-hal-2-0.mtk stopped
    setprop debug.init.svc.thermal_manager stopped
    setprop debug.init.svc.thermal_mnt_hal_service stopped
    setprop debug.init.svc.thermal-engine stopped
    setprop debug.init.svc.vendor.thermal-hal-2-0.mtk stopped
    setprop debug.init.svc.thermal_core stopped
    setprop debug.ro.boottime.thermal_core stopped
    setprop debug.ro.boottime.vendor.thermal-hal-2-0.mtk stopped
    setprop debug.ro.vendor.mtk_thermal_2_0 stopped
    setprop debug.ro.boottime.thermal_core stopped
    setprop debug.ro.boottime.thermald stopped
    setprop debug.ro.boottime.vendor.thermal-hal-2-0.mtk stopped
    setprop debug.ro.vendor.mtk_thermal_2_0 stopped
    #Ultra Gaming Thermal Tuning (Non Root)
settings put system bench_mark_mode 0
setprop debug.thermal_status 0
setprop debug.performance.tuning 3
setprop debug.thermal.cpu_thermal_throttle.disable 1
setprop debug.thermal.ambient_sensor.disable 1
setprop debug.cooling_name_thermal-devfreq 0
setprop debug.pid.sec-thermal-1-0 disabled
setprop debug.thermal_zone.display_hotplug_control 0
setprop debug.thermal_zone.battery_hotplug_control 0
setprop debug.mediatek.appgamepq_compress 0
setprop debug.mediatek.disp_decompress 2
setprop debug.mtk_tflite.target_nnapi 31
setprop debug.thermal_zone.gpu_threshold_temp 95
setprop debug.thermal_zone.cpu_threshold_temp 90
setprop debug.thermal_zone.display_threshold_temp 85
setprop debug.thermal_zone.camera_hotplug_control 0
setprop debug.thermal_zone.battery_threshold_temp 75
setprop debug.thermal_zone.camera_threshold_temp 90
setprop debug.thermal_zone.cpu_hotplug_control 0
setprop debug.thermal_zone.gpu_hotplug_control 0
setprop debug.power.throttling.disable 1
setprop debug.thermal.gpu_shader_clock_throttle.disable 1
setprop debug.thermal.gpu_core_clock_throttle.disable 1
setprop debug.thermal.gpu_power_throttle.disable 1
setprop debug.thermal.gpu_thermal_throttle.disable 1
setprop debug.thermal.gpu_memory_throttle.disable 1
setprop debug.thermal.gpu_fan_control.disable 1
setprop debug.thermal.gpu_boost.disable 0
setprop debug.thermal.gpu_control.disable 1
setprop debug.thermal.gpu_throttle.disabled 1
setprop debug.thermal.backlight.disabled 1
setprop debug.thermal.boost.disabled 0
setprop debug.thermal.inactive_delay.disabled 1
setprop debug.thermal.throttling.disable 1
setprop debug.thermal.profile.disable 1
setprop debug.thermal.throttle_ratio.disable 1
setprop debug.thermal.turbo_ratio_limit.disable 1
setprop debug.thermal.cooling_device_state.disable 1
setprop debug.thermal.dynamic_scheduling.disable 1
setprop debug.thermal.critical_temp.disable 1
setprop debug.thermal.threshold.disable 1
setprop debug.thermal.overheat_protection.disable 1
setprop debug.thermal.alert.disable 1
setprop debug.thermal.fan.disable 1
setprop debug.thermal.shutdown.disable 1
setprop debug.thermal.balance_algorithm -1
setprop debug.thermal.performance_mode.disable 1
setprop debug.thermal.force_fan_on.disable 0
setprop debug.thermal.critical_trip_point.disable 1
setprop debug.thermal.auto_thermal_disable 1
setprop debug.thermal.zone.disabled 1
setprop debug.thermal.trip_point.disabled 1
setprop debug.thermal.suspend.disabled 1
setprop debug.thermal.thermal_policy.disable 1
setprop debug.thermal.fan_disable 1
#Peformance Stability
setprop debug.performance.tuning 1 
setprop debug.egl.force_msaa false
setprop debug.hwui.use_gpu_pixel_buffers 1
setprop debug.hwui.target_cpu_time_percent 10
setprop debug.hwui.render_dirty_regions false
setprop debug.hwui.disable_vsync true
setprop debug.hwui.level 0
setprop debug.kill_allocating_task 0
setprop debug.gralloc.gfx_ubwc_disable 0
setprop debug.rs.default-CPU-driver 1
setprop debug.rs.forcecompat 1
setprop debug.rs.max-threads 8
setprop debug.choreographer.skipwarning 30
setprop debug.choreographer.frametime false
setprop debug.display.allow_non_native_refresh_rate_override 1
setprop debug.display.render_frame_rate_is_physical_refresh_rate 1
setprop debug.sf.use_phase_offsets_as_durations 1
setprop debug.sf.predict_hwc_composition_strategy 0
setprop debug.sf.enable_transaction_tracing false
setprop debug.sf.disable_client_composition_cache 1
setprop debug.sf.gpu_freq_indeks 7
setprop debug.sf.use_frame_rate_priority 1
setprop debug.sf.disable_backpressure 1
setprop debug.sf.enable_gl_backpressure 1
setprop debug.atrace.tags.enableflags 0
setprop debug.cpurend.vsync false
setprop debug.composition.type gpu
setprop debug.checkjni 0
setprop debug.atrace.tags.enableflags 0
setprop debug.gr.numframebuffers 3
) > /dev/null 2>&1
(
#Thermal Unlock
setprop debug.sys.thermal.level 0
setprop debug.sys.thermal.protection 0
setprop debug.sys.thermal.enable_detailed 0
) > /dev/null 2>&1
""".trimIndent(),
                disableCmd = """
(
   cmd thermalservice reset
   cmd power set-fixed-performance-mode-enabled false
   settings put system POWER_PERFORMANCE_MODE_OPEN 0
   settings delete system POWER_PERFORMANCE_MODE_OPEN
   settings delete system battery.temp_high
   settings delete system bench_mark_mode
   settings delete system virtual_thermal_thermal_zone
   settings delete global vendor.dfps.enable
   settings delete global vendor.smart_dfps.enable
   settings delete global thermal_throttling
   settings delete global thermal_pwrlevel
) > /dev/null 2>&1
(
#Disabled Thermal No Root (Gimick)
    setprop debug.init.svc.thermald ""
    setprop debug.init.svc_debug_pid.vendor.thermal-hal-2-0.mtk ""
    setprop debug.init.svc.thermal_manager ""
    setprop debug.init.svc.thermal_mnt_hal_service ""
    setprop debug.init.svc.thermal-engine ""
    setprop debug.init.svc.vendor.thermal-hal-2-0.mtk ""
    setprop debug.init.svc.thermal_core ""
    setprop debug.ro.boottime.thermal_core stopped
    setprop debug.ro.boottime.vendor.thermal-hal-2-0.mtk ""
    setprop debug.ro.vendor.mtk_thermal_2_0 ""
    setprop debug.ro.boottime.thermal_core ""
    setprop debug.ro.boottime.thermald stopped
    setprop debug.ro.boottime.vendor.thermal-hal-2-0.mtk ""
    setprop debug.ro.vendor.mtk_thermal_2_0 ""
    #Ultra Gaming Thermal Tuning (Non Root)
settings put system bench_mark_mode ""
setprop debug.thermal_status ""
setprop debug.performance.tuning ""
setprop debug.thermal.cpu_thermal_throttle.disable ""
setprop debug.thermal.ambient_sensor.disable ""
setprop debug.cooling_name_thermal-devfreq ""
setprop debug.pid.sec-thermal-1-0 ""
setprop debug.thermal_zone.display_hotplug_control 
setprop debug.thermal_zone.battery_hotplug_control
setprop debug.mediatek.appgamepq_compress ""
setprop debug.mediatek.disp_decompress ""
setprop debug.mtk_tflite.target_nnapi ""
setprop debug.thermal_zone.gpu_threshold_temp ""
setprop debug.thermal_zone.cpu_threshold_temp ""
setprop debug.thermal_zone.display_threshold_temp ""
setprop debug.thermal_zone.camera_hotplug_control ""
setprop debug.thermal_zone.battery_threshold_temp ""
setprop debug.thermal_zone.camera_threshold_temp ""
setprop debug.thermal_zone.cpu_hotplug_control ""
setprop debug.thermal_zone.gpu_hotplug_control ""
setprop debug.power.throttling.disable ""
setprop debug.thermal.gpu_shader_clock_throttle.disable ""
setprop debug.thermal.gpu_core_clock_throttle.disable ""
setprop debug.thermal.gpu_power_throttle.disable ""
setprop debug.thermal.gpu_thermal_throttle.disable ""
setprop debug.thermal.gpu_memory_throttle.disable ""
setprop debug.thermal.gpu_fan_control.disable ""
setprop debug.thermal.gpu_boost.disable ""
setprop debug.thermal.gpu_control.disable ""
setprop debug.thermal.gpu_throttle.disabled ""
setprop debug.thermal.backlight.disabled ""
setprop debug.thermal.boost.disabled ""
setprop debug.thermal.inactive_delay.disabled ""
setprop debug.thermal.throttling.disable ""
setprop debug.thermal.profile.disable ""
setprop debug.thermal.throttle_ratio.disable ""
setprop debug.thermal.turbo_ratio_limit.disable ""
setprop debug.thermal.cooling_device_state.disable ""
setprop debug.thermal.dynamic_scheduling.disable ""
setprop debug.thermal.critical_temp.disable ""
setprop debug.thermal.threshold.disable ""
setprop debug.thermal.overheat_protection.disable ""
setprop debug.thermal.alert.disable ""
setprop debug.thermal.fan.disable ""
setprop debug.thermal.shutdown.disable ""
setprop debug.thermal.balance_algorithm ""
setprop debug.thermal.performance_mode.disable ""
setprop debug.thermal.force_fan_on.disable ""
setprop debug.thermal.critical_trip_point.disable ""
setprop debug.thermal.auto_thermal_disable ""
setprop debug.thermal.zone.disabled ""
setprop debug.thermal.trip_point.disabled ""
setprop debug.thermal.suspend.disabled ""
setprop debug.thermal.thermal_policy.disable ""
setprop debug.thermal.fan_disable ""
#Peformance Stability
setprop debug.performance.tuning ""
setprop debug.egl.force_msaa ""
setprop debug.hwui.use_gpu_pixel_buffers ""
setprop debug.hwui.target_cpu_time_percent ""
setprop debug.hwui.render_dirty_regions ""
setprop debug.hwui.disable_vsync ""
setprop debug.hwui.level ""
setprop debug.kill_allocating_task ""
setprop debug.gralloc.gfx_ubwc_disable ""
setprop debug.rs.default-CPU-driver ""
setprop debug.rs.forcecompat ""
setprop debug.rs.max-threads ""
setprop debug.choreographer.skipwarning ""
setprop debug.choreographer.frametime ""
setprop debug.display.allow_non_native_refresh_rate_override ""
setprop debug.display.render_frame_rate_is_physical_refresh_rate ""
setprop debug.sf.use_phase_offsets_as_durations ""
setprop debug.sf.predict_hwc_composition_strategy ""
setprop debug.sf.enable_transaction_tracing ""
setprop debug.sf.disable_client_composition_cache ""
setprop debug.sf.gpu_freq_indeks ""
setprop debug.sf.use_frame_rate_priority ""
setprop debug.sf.disable_backpressure ""
setprop debug.sf.enable_gl_backpressure ""
setprop debug.atrace.tags.enableflags ""
setprop debug.cpurend.vsync ""
setprop debug.composition.type ""
setprop debug.checkjni ""
setprop debug.atrace.tags.enableflags ""
setprop debug.gr.numframebuffers ""
) > /dev/null 2>&1
(
#Thermal Unlock
setprop debug.sys.thermal.level ""
setprop debug.sys.thermal.protection ""
setprop debug.sys.thermal.enable_detailed ""
) > /dev/null 2>&1
""".trimIndent(),
                label = getString(R.string.tweak_thermalkiller_title), needsRoot = true
            ),
            TweakDef(
                R.id.tweak_mtk_row, R.id.tweak_mtk_switch, KEY_MTK,
                enableCmd  = """
(
#V7.0
settings put global force_gpu_rendering 1
settings put global low_power 0
settings put global window_animation_scale 0.5
settings put global transition_animation_scale 0.5
settings put global animator_duration_scale 0.5
#V6.0
# UI & Animation Enhancements
setprop debug.hwui.use_buffer_age false
setprop debug.hwui.use_partial_update false
setprop debug.hwui.drop_shadow_cache_size 12
setprop debug.hwui.fbo_cache_size 12
setprop debug.hwui.gradient_cache_size 2
setprop debug.hwui.layer_cache_size 48
setprop debug.hwui.texture_cache_size 88
setprop debug.sf.high_fps_early_gl_phase_offset_ns -2000000
setprop debug.sf.high_fps_early_phase_offset_ns -4000000
setprop debug.sf.high_fps_late_app_phase_offset_ns 1000000
setprop debug.sf.high_fps_late_sf_phase_offset_ns -2000000
settings put global vendor.dfps.enable false
settings put global vendor.display.default_fps 120
settings put global vendor.display.fod_monitor_default_fps 120
settings put global vendor.display.idle_default_fps 120
settings put global vendor.display.video_or_camera_fps.support true
settings put global vendor.fps.switch.defaul true
settings put global vendor.fps.switch.thermal true
settings put global vendor.display.disable_mitigated_fps 1
settings put global vendor.display.enable_dpps_dynamic_fps 0
#V5.0
# Mematikan fitur yang mengganggu performa
settings put global auto_sync 0
settings put global ble_scan_always_enabled 0
settings put global wifi_scan_always_enabled 0
settings put global hotword_detection_enabled 0
settings put global activity_starts_logging_enabled 0
settings put global network_recommendations_enabled 0
settings put secure adaptive_sleep 0
settings put secure screensaver_enabled 0
settings put secure send_action_app_error 0
settings put system motion_engine 0
settings put system master_motion 0
settings put system air_motion_engine 0
settings put system air_motion_wake_up 0
settings put system send_security_reports 0
settings put system intelligent_sleep_mode 0
settings put system nearby_scanning_enabled 0
settings put system nearby_scanning_permission_allowed 0
# Menonaktifkan layanan Qualcomm yang tidak diperlukan
pm disable com.qualcomm.qti.cne
pm disable com.qualcomm.location.XT
# Menonaktifkan pembatasan termal untuk performa maksimal
cmd thermalservice override-status 0
cmd power set-adaptive-power-saver-enabled false
cmd power set-fixed-performance-mode-enabled true
#V4.0 Infinity X
setprop debug.sf.gpu_freq_indeks 7
settings put global surface_flinger.max_frame_buffer_acquired_buffers 3 
settings put global surface_flinger.use_context_priority true
settings put global surface_flinger.set_touch_timer_ms 0
settings put global surface_flinger.use_content_detection_for_refresh_rate false
settings put global surface_flinger.game_default_frame_rate_override 90
settings put global surface_flinger.enable_frame_rate_override false
#New
setprop debug.performance.tuning 1
settings put global logcat.live disable
settings put global config hw_quickpoweron true
settings put system gsm.lte.ca.support 1
setprop debug.hwui.disable_scissor_opt true
settings put global hwui.texture_cache_size 24
settings put global hwui.texture_cache_flushrate 0.5
settings put global disable_smooth_effect true
setprop debug.composition.type mdp
settings put system sys.composition.type mdp
settings put system gpu_perf_mode 1
#Performa Infinity
settings put system FPSTUNER_SWITCH true
settings put system GPUTUNER_SWITCH true
settings put system CPUTUNER_SWITCH true
settings put system NV_POWERMODE true
setprop debug.gpurend.vsync false
setprop debug.cpurend.vsync false
settings put system hw.accelerated 1
settings put system video.accelerated 1
settings put system game.accelerated 1
settings put system ui.accelerated 1
settings put system enable_hardware_accelerated true
settings put system enable_optimize_refresh_rate true
settings put system lgospd.enable 0
settings put system pcsync.enable 0
settings put system dalvik.hyperthreading true
settings put system dalvik.multithread true
# Rendering and UI Optimizations
setprop debug.sf.disable_client_composition_cache 1
settings put debug.sf.latch_unsignaled 1
setprop debug.sf.disable_backpressure 1
settings put system use_16bpp_alpha 1
#MTK Infinity X
# MTK Performance Boosts
settings put global mtk_perf_fast_start_win1
settings put global mtk_perf_response_time 1
settings put global mtk_perf_simple_start_win 1
setprop debug.mediatek.appgamepq_compress 1
setprop debug.mediatek.disp_decompress 1
setprop debug.mtk_tflite.target_nnapi 29
setprop debug.mtk.aee.feature 1
setprop debug.mediatek.performance 1
setprop debug.mediatek.game_pq_enable 1
setprop debug.mediatek.appgamepq 2
setprop debug.mediatek.high_frame_rate_sf_set_big_core_fps_threshold 120
#InfinityX
settings put system user_refresh_rate 120
settings put system min_refresh_rate 120
settings put system peak_refresh_rate 120
settings put system user_refresh_rate infinity
settings get system user_refresh_rate
setprop debug.gfx.early_z 1
setprop debug.hwui.skip_empty_damage true
setprop debug.qctwa.preservebuf 1
setprop debug.qctwa.preservebuf.comp_level 3
setprop debug.qc.hardware 1
setprop debug.qcom.hw_hmp.min_fps -1
setprop debug.qcom.hw_hmp.max_fps -1
setprop debug.qcom.pil.q6_boost q
setprop debug.qcom.render_effect 0
setprop debug.adreno.force_rast 1
setprop debug.adreno.prefer_native_sync 1
setprop debug.adreno.q2d_decompress 1
setprop debug.rs.qcom.use_fast_math 1
setprop debug.rs.qcom.disable_expand 1
setprop debug.sf.hw 1
setprop debug.hwui.shadow.renderer monothic
setprop debug.gfx.driver.1 com.qualcomm.qti.gpudrivers.kona.api30
setprop debug.power_management_mode pref_max
setprop debug.gfx.driver 1
setprop debug.angle.overlay FPS:Vulkan*PipelineCache*
setprop debug.hwui.target_cpu_time_percent 300
setprop debug.hwui.target_gpu_time_percent 300
setprop debug.hwui.use_hint_manager true
setprop debug.multicore.processing 1
setprop debug.fb.rgb565 1
setprop debug.sf.lag_adj 0
setprop debug.sf.showfps 0
setprop debug.hwui.max_frame_time 35.55
setprop debug.sf.disable_backpressure 1
setprop debug.hbm.direct_render_pixmaps 1
setprop debug.hwui.render_compability true
setprop debug.heat_suppression 0
setprop debug.systemuicompilerfilter speed
setprop debug.sensor.hal 0
setprop debug.hwui.render_quality high
setprop debug.sf.gpu_freq_index 7
setprop debug.sf.cpu_freq_index 7
setprop debug.sf.mem_freq_index 7
setprop debug.egl.force_fxaa false
setprop debug.egl.force_taa false
setprop debug.egl.force_msaa false
setprop debug.egl.force_ssaa false
setprop debug.egl.force_smaa false
setprop debug.egl.force_mlaa false
setprop debug.egl.force_txaa false
setprop debug.egl.force_csaa false
setprop debug.hwui.fps_divisor -1
setprop debug.redroid.fps 120
setprop debug.disable_sched_boost true
setprop debug.gpu.cooling.callback_freq_limit false
setprop debug.cpu.cooling.callback_freq_limit false
setprop debug.rs.default-CPU-driver 1
setprop debug.rs.default-CPU-buffer 65536
setprop debug.hwui.use_hint_manager 1
setprop debug.egl.profiler 0
setprop debug.enable.gamed false
setprop debug.qualcomm.sns.daemon 0
setprop debug.qualcomm.sns.libsensor 1
setprop debug.sf.disable_client_composition_cache 1
setprop debug.sf.disable_client_composition_cache 1
setprop debug.sf.disable_hw_vsync true
setprop debug.hwui.disable_vsync true
setprop debug.egl.hw 1
setprop debug.sf.native_mode 1
setprop debug.gralloc.gfx_ubwc_disable 1
setprop debug.video.accelerate.hw 1
#InfinityCmd
cmd looper_stats disable
cmd power set-adaptive-power-saver-enabled false
cmd power set-fixed-performance-mode-enabled true
cmd power set-mode 0
cmd thermalservice override-status 0
dumpsys deviceidle enable
dumpsys deviceidle force-idle
dumpsys deviceidle step deep
) > /dev/null 2>&1
""".trimIndent(),
                disableCmd = """
(
#V7.0
settings delete global force_gpu_rendering 
settings delete global low_power
settings delete global window_animation_scale
settings delete global transition_animation_scale
settings delete global animator_duration_scale
# UI & Animation Enhancements
setprop debug.hwui.use_buffer_age ""
setprop debug.hwui.use_partial_update ""
setprop debug.hwui.drop_shadow_cache_size ""
setprop debug.hwui.fbo_cache_size ""
setprop debug.hwui.gradient_cache_size ""
setprop debug.hwui.layer_cache_size ""
setprop debug.hwui.texture_cache_size ""
setprop debug.sf.high_fps_early_gl_phase_offset_ns ""
setprop debug.sf.high_fps_early_phase_offset_ns ""
setprop debug.sf.high_fps_late_app_phase_offset_ns ""
setprop debug.sf.high_fps_late_sf_phase_offset_ns ""
settings delete global vendor.dfps.enable
settings delete global vendor.display.default_fps
settings delete global vendor.display.fod_monitor_default_fps
settings delete global vendor.display.idle_default_fps
settings delete global vendor.display.video_or_camera_fps.support
settings delete global vendor.fps.switch.defaul 
settings delete global vendor.fps.switch.thermal 
settings delete global vendor.display.disable_mitigated_fps 
settings delete global vendor.display.enable_dpps_dynamic_fps 
# Mematikan fitur yang mengganggu performa
settings delete global auto_sync 
settings delete global ble_scan_always_enabled 
settings delete global wifi_scan_always_enabled 
settings delete global hotword_detection_enabled 
settings delete global activity_starts_logging_enabled 
settings delete global network_recommendations_enabled 
settings delete secure adaptive_sleep 
settings delete secure screensaver_enabled 
settings delete secure send_action_app_error 
settings  system motion_engine 
settings delete system master_motion 
settings delete system air_motion_engine 
settings delete system air_motion_wake_up 
settings delete system send_security_reports 
settings delete system intelligent_sleep_mode 
settings delete system nearby_scanning_enabled 
settings delete system nearby_scanning_permission_allowed 
# Menonaktifkan layanan Qualcomm yang tidak diperlukan
pm enable com.qualcomm.qti.cne
pm enable com.qualcomm.location.XT
# Menonaktifkan pembatasan termal untuk performa maksimal
cmd power set-adaptive-power-saver-enabled true
cmd power set-fixed-performance-mode-enabled false
setprop debug.sf.gpu_freq_indeks ""
settings delete global surface_flinger.max_frame_buffer_acquired_buffers 3 
settings delete global surface_flinger.use_context_priority true
settings delete global surface_flinger.set_touch_timer_ms 0
settings delete global surface_flinger.use_content_detection_for_refresh_rate false
settings put global surface_flinger.game_default_frame_rate_override 90
settings put global surface_flinger.enable_frame_rate_override false
settings delete global logcat.live 
settings delete global config hw_quickpoweron
settings delete system gsm.lte.ca.support
setprop debug.hwui.disable_scissor_opt ""
settings delete global hwui.texture_cache_size
settings delete global hwui.texture_cache_flushrate
settings delete global disable_smooth_effect 
settings delete system sys.composition.type
settings delete system gpu_perf_mode
#Performa Infinity
settings delete system FPSTUNER_SWITCH
settings delete system GPUTUNER_SWITCH
settings delete system CPUTUNER_SWITCH
settings delete system NV_POWERMODE
settings delete system hw.accelerated
settings delete system video.accelerated
settings delete system game.accelerated
settings delete system ui.accelerated
settings delete system enable_hardware_accelerated
settings delete system enable_optimize_refresh_rate 
settings delete system lgospd.enable
settings delete system pcsync.enable 
settings delete system dalvik.hyperthreading
settings delete system dalvik.multithread 
#MTK Infinity X - Reset to empty
# Rendering and UI Optimizations
setprop debug.sf.disable_client_composition_cache ""
settings delete debug.sf.latch_unsignaled
setprop debug.sf.disable_backpressure ""
settings delete system use_16bpp_alpha
# MTK Performance Boosts
settings delete global mtk_perf_fast_start_win
settings delete global mtk_perf_response_time
settings delete global mtk_perf_simple_start_win
setprop debug.sf.disable_client_composition_cache ""
setprop debug.sf.disable_hw_vsync ""
setprop debug.hwui.disable_vsync ""
setprop debug.egl.hw 0
setprop debug.sf.native_mode 0system ya 
setprop debug.gralloc.gfx_ubwc_disable 0
setprop debug.video.accelerate.hw 0
setprop debug.mediatek.appgamepq_compress ""
setprop debug.mediatek.disp_decompress ""
setprop debug.mtk_tflite.target_nnapi ""
setprop debug.mtk.aee.feature ""
setprop debug.mediatek.performance ""
setprop debug.mediatek.game_pq_enable ""
setprop debug.mediatek.appgamepq ""
setprop debug.mediatek.high_frame_rate_sf_set_big_core_fps_threshold ""
#InfinityX - Reset to empty
settings delete system user_refresh_rate
settings delete system min_refresh_rate 
settings delete system peak_refresh_rate
settings delete system user_refresh_rate 
settings delete system user_refresh_rate
setprop debug.performance.tuning ""
setprop debug.composition.type ""
setprop debug.gfx.early_z ""
setprop debug.hwui.skip_empty_damage ""
setprop debug.qctwa.preservebuf ""
setprop debug.qctwa.preservebuf.comp_level ""
setprop debug.qc.hardware ""
setprop debug.qcom.hw_hmp.min_fps ""
setprop debug.qcom.hw_hmp.max_fps ""
setprop debug.qcom.pil.q6_boost ""
setprop debug.qcom.render_effect ""
setprop debug.adreno.force_rast ""
setprop debug.adreno.prefer_native_sync ""
setprop debug.adreno.q2d_decompress ""
setprop debug.rs.qcom.use_fast_math ""
setprop debug.rs.qcom.disable_expand ""
setprop debug.sf.hw ""
setprop debug.hwui.shadow.renderer ""
setprop debug.gfx.driver.1 ""
setprop debug.power_management_mode ""
setprop debug.gfx.driver ""
setprop debug.angle.overlay ""
setprop debug.hwui.target_cpu_time_percent ""
setprop debug.hwui.target_gpu_time_percent ""
setprop debug.hwui.use_hint_manager ""
setprop debug.multicore.processing ""
setprop debug.fb.rgb565 ""
setprop debug.sf.lag_adj ""
setprop debug.sf.showfps ""
setprop debug.hwui.max_frame_time ""
setprop debug.sf.disable_backpressure ""
setprop debug.hbm.direct_render_pixmaps ""
setprop debug.hwui.render_compability ""
setprop debug.heat_suppression ""
setprop debug.systemuicompilerfilter ""
setprop debug.sensor.hal ""
setprop debug.hwui.render_quality ""
setprop debug.sf.gpu_freq_index ""
setprop debug.sf.cpu_freq_index ""
setprop debug.sf.mem_freq_index ""
setprop debug.egl.force_fxaa ""
setprop debug.egl.force_taa ""
setprop debug.egl.force_msaa ""
setprop debug.egl.force_ssaa ""
setprop debug.egl.force_smaa ""
setprop debug.egl.force_mlaa ""
setprop debug.egl.force_txaa ""
setprop debug.egl.force_csaa ""
setprop debug.gpurend.vsync ""
setprop debug.cpurend.vsync ""
setprop debug.hwui.fps_divisor ""
setprop debug.redroid.fps ""
setprop debug.disable_sched_boost ""
setprop debug.gpu.cooling.callback_freq_limit ""
setprop debug.cpu.cooling.callback_freq_limit ""
setprop debug.rs.default-CPU-driver ""
setprop debug.rs.default-CPU-buffer ""
setprop debug.hwui.use_hint_manager ""
setprop debug.egl.profiler ""
setprop debug.enable.gamed ""
setprop debug.qualcomm.sns.daemon ""
setprop debug.qualcomm.sns.libsensor ""
setprop debug.sf.disable_client_composition_cache ""
#InfinityCmd - Reset to default
cmd looper_stats reset
cmd power set-adaptive-power-saver-enabled true
cmd power set-fixed-performance-mode-enabled false
cmd power set-mode 0
cmd thermalservice reset-status
dumpsys deviceidle disable
) > /dev/null 2>&1
""".trimIndent(),
                label = getString(R.string.tweak_mtk_title), needsRoot = false
            ),
            TweakDef(
                R.id.tweak_hardcore_row, R.id.tweak_hardcore_switch, KEY_HARDCORE,
                enableCmd  = """
disable_thermal() {
    echo "[HardCoreGT] Thermal Service Disabled"
    # matikan thermal
    settings put global thermal_service_enabled 0
    settings put system thermal_mode 0
    settings put system performance_mode 1
    setprop debug.performance.tuning 1
    # Paksakan refresh rate tinggi
    settings put system min_refresh_rate 120
    settings put system peak_refresh_rate 120
# Menonaktifkan layanan Qualcomm yang tidak diperlukan
pm disable com.qualcomm.qti.cne
pm disable com.qualcomm.location.XT
# MTK Performance Boosts
settings put global mtk_perf_fast_start_win1
settings put global mtk_perf_response_time 1
settings put global mtk_perf_simple_start_win 1
setprop debug.mediatek.appgamepq_compress 1
setprop debug.mediatek.disp_decompress 1
setprop debug.mtk_tflite.target_nnapi 29
setprop debug.mtk.aee.feature 1
setprop debug.mediatek.performance 1
setprop debug.mediatek.game_pq_enable 1
setprop debug.mediatek.appgamepq 2
setprop debug.mediatek.high_frame_rate_sf_set_big_core_fps_threshold 120
}
""".trimIndent(),
                disableCmd = """
(
# Mengaktifkan kembali thermal service
settings delete global thermal_service_enabled 
settings delete system thermal_mode 
settings delete system performance_mode 
setprop debug.performance.tuning 0
# Mengembalikan refresh rate ke default
settings delete system min_refresh_rate
settings delete system peak_refresh_rate
# Mengaktifkan kembali layanan Qualcomm
pm enable com.qualcomm.qti.cne
pm enable com.qualcomm.location.XT
# Reset pengaturan performa MTK
settings delete global mtk_perf_fast_start_win1
settings delete global mtk_perf_response_time
settings delete global mtk_perf_simple_start_win
) > /dev/null 2>&1
""".trimIndent(),
                label = getString(R.string.tweak_hardcore_title), needsRoot = false
            ),
            TweakDef(
                R.id.tweak_c2d_row, R.id.tweak_c2d_switch, KEY_C2D,
                enableCmd  = """
(
#Mode C2d
setprop debug.composition.type c2d
setprop debug.mdpcomp.logs 0
setprop debug.hwui.use_buffer_age true
setprop debug.gralloc.enable_fb_ubwc 1
setprop debug.hwui.use_buffer_age true
settings put system ui.accelerate 1
settings put secure service.trim.enable 1
settings put secure hwuserid true
settings put system fw.usetrimsettings true
settings put secure hardware.gps on
settings put system force_hw_vsync 1
setprop debug.performance_hint 1
setprop debug.enable.dtm 1
setprop debug.enable.hw_accel true
setprop debug.surfaceflinger.hardwareacceleration 1
settings put system ui.hw.main 1
settings put system hardware_accelerated_rendering_enabled 1
setprop debug.sf.hw 1
setprop debug.sf.latch_unsignaled 1
setprop debug.enabletr true
setprop debug.overlayui.enable 1
setprop debug.hw3d.force 1
setprop debug.egl.force_msaa false
setprop debug.egl.force_fxaa false
setprop debug.egl.force_taa false
setprop debug.rs.max-threads 8
setprop debug.rs.min-threads 8
setprop debug.rs.min-threads 8
setprop debug.hwui.disable_scissor_opt false
setprop debug.hwui.use_vulkan true
setprop debug.hwui.show_dirty_regions false
setprop debug.zygote.disable_gl_preload true
setprop debug.cpurend.vsync false
setprop debug.surface_flinger.max_frame_buffer_acquired_buffers 3
setprop debug.sf.enable_advanced_sf_phase_offset 1
setprop debug.sf.compbypass.enable 0
setprop debug.sf.enable_hwc_vds 1
setprop debug.sf.latch_unsignaled 1
setprop debug.sf.enable_gl_backpressure 1
setprop debug.sf.disable_backpressure 0
setprop debug.sf.recomputecrop 0
setprop debug.gralloc.enable_fb_ubwc 1
setprop debug.gralloc.disable_ubwc 0
setprop debug.gralloc.gfx_ubwc_disable 0
setprop debug.force_sw_gles 0
setprop debug.zygote.preload.enable 0
setprop debug.hwui.renderer opengl
setprop debug.renderengine.backend opengl
setprop debug.renderthread.skia.reduceopstasksplitting true
settings put secure game_auto_temperature_control 0
settings put secure sem_performance_mode  1
settings put secure refresh_rate_mode 2
settings put system display_refresh_mode 1
settings put system Hardware.Renderer 1
settings put system HardwareAccelerated true
settings put system HardwareRenderer.Accelerated 1
settings put system HardwareRenderer.FrameRenderRequest 999
settings put system HardwareRenderer.CodecImgBits 10000
settings put system HardwareBuffer 32
settings put system gpu_mode host
settings put system gpu_option_mode host
settings put system game.accelerate.hw 1
settings put secure speed_mode_enable 1
settings put secure user_refresh_rate 120
settings put secure miui_refresh_rate 120
settings put system min_refresh_rate 60
settings put system peak_refresh_rate 90
settings put global GPUTUNER_SWITCH true
settings put global zen_mode 0
settings put global wifi_scan_always_enabled 0
settings put global updatable_driver_all_apps 1
settings put global fstrim_mandatory_interval 864000000
setprop debug.sf.high_fps_early_gl_phase_offset_ns 650000
setprop debug.sf.high_fps_late_app_phase_offset_ns 100000
setprop debug.sf.early_phase_offset_ns 500000
setprop debug.sf.early_app_phase_offset_ns 500000
setprop debug.sf.early_gl_phase_offset_ns 3000000
setprop debug.sf.early_gl_app_phase_offset_ns 15000000
setprop debug.display.enable_fixed_fps false
setprop debug.smart_dfps.enable false
setprop debug.sf.showupdates 0
setprop debug.sf.showcpu 0
setprop debug.sf.showbackground 0
setprop debug.sf.showfps 0
setprop debug.atrace.tags.enableflags 0
#V4.0
settings put global cpu_boost true
settings put global performance_mode 1
settings put global game_mode 2
settings put global settings_enable_monitor_phantom_procs false
settings put global gpu_debug_layers 1
settings put global force_gpu_rendering 1
) > /dev/null 2>&1

(
settings put system gpu_perf_mode 1
setprop debug.sf.gpu_freq_indeks 7
settings put system GPUTUNER_SWITCH true
setprop debug.gpurend.vsync false
setprop debug.gfx.driver.1 com.qualcomm.qti.gpudrivers.kona.api30
setprop debug.hwui.target_gpu_time_percent 300
setprop debug.gpu.cooling.callback_freq_limit false
settings put system enable_triple_buffering 1
settings put system swappiness 60
settings put system gpu.use_extended_cache true
settings put system gpu.prefetch_threshold 1
setprop debug.hwui.render_thread boost
setprop debug.hwui.raster_thread 4
settings put system gpu_force_on 1
settings put system gpu_force_render true
) > /dev/null 2>&1

(
#Optimize GPU Mali 
settings put global mali.debug_level 0
settings put global mali.use_l2_cache 1
settings put global mali.dynamic_power 1
) > /dev/null 2>&1

(
#GPU PERF TWEAK V2.0
settings put system ged_smart_boost 500
settings put system boost_upper_bound  write_value 80
settings put system gx_game_mode 1
settings put system enable_game_self_frc_detect 1
settings put system ged_boost_enable 1
settings put system gx_boost_on 1
settings put system boost_gpu_enable 1
settings put system enable_gpu_boost 1
settings put system ged_dvfs_enable 1
settings put system boost_amp 1
settings put system boost_extra 0
settings put system gx_3D_benchmark_on 0 
settings put system gpu_idle 0
) > /dev/null 2>&1

# GPU Perf TWEAK 
(
setprop debug.composition.type gpu
setprop debug.enabletr true
setprop debug.overlayui.enable 1
setprop debug.performance.tuning 1
setprop debug.hw2d.force 1
settings put system composition.type c2d
settings put system sys.ui.hw 1
) > /dev/null 2>&1

(
# GPU Optimization
settings put global gpu.optimize.level 5
settings put global gpu.optimize.load_level 3
settings put global gpu.optimize.driver_version=3
settings put global gpu.optimize.preload 1
settings put global gpu.optimize.purgeable_limit 128
settings put global gpu.optimize.retry_max 6
settings put global gpu.optimize.texture_control true
settings put global gpu.optimize.memory_compaction true
settings put global.gpu.optimize.hires_preload true
settings put global.gpu.optimize.fork_detector true
settings put global gpu.optimize.fork_detector_threshold 5
settings put global.gpu.optimize.max_job_count 4
settings put global.gpu.optimize.max_target_duration 10
settings put global.gpu.optimize.min_target_size 200
) > /dev/null 2>&1

#Optimalisasi Frekuensi GPU
(
settings put global persist.sys.gpu_rendering_mode 1
settings put global persist.sys.enable_gpu_boost true
settings put global persist.vendor.gpu_boost_mode 1
settings put global gpu.tuning.performance_mode 1
) > /dev/null 2>&1

#Confiogurasi GPU Adreno
( 
setprop debug.qti.config.zram true
settings put global config.disable.hw_accel false
settings put global product.gpu.driver 1
settings put global sf.compbypass.enable 0
settings put system video.accelerate.hw 1
setprop debug.pm.dyn_samplingrate 1
setprop debug.com.qc.hardware 1
setprop debug.qc.hardware true
setprop debug.qctwa.preservebuf 1
setprop debug.qctwa.statusbar 1
settings put system dev.pm.dyn_samplingrate1
settings put global config.enable.hw_accel true
) > /dev/null 2>&1
""".trimIndent(),
                disableCmd = """
(
setprop debug.composition.type ""
setprop debug.mdpcomp.logs ""
setprop debug.hwui.use_buffer_age ""
setprop debug.gralloc.enable_fb_ubwc ""
setprop debug.hwui.use_buffer_age 
settings delete system ui.accelerate
settings delete secure service.trim.enable
settings delete secure hwuserid
settings delete system fw.usetrimsettings
settings put secure hardware.gps ""
settings put system force_hw_vsync ""
setprop debug.performance_hint ""
setprop debug.enable.dtm ""
setprop debug.enable.hw_accel ""
setprop debug.surfaceflinger.hardwareacceleration ""
settings put system ui.hw.main ""
settings put system hardware_accelerated_rendering_enabled ""
setproep debug.sf.hw ""
setprop debug.sf.latch_unsignaled ""
setprop debug.enabletr ""
setprop debug.overlayui.enable ""
setprop debug.hw3d.force ""
setprop debug.egl.force_msaa ""
setprop debug.egl.force_fxaa ""
setprop debug.egl.force_taa ""
setprop debug.rs.max-threads ""
setprop debug.rs.min-threads ""
setprop debug.rs.min-threads ""
setprop debug.hwui.disable_scissor_opt ""
setprop debug.hwui.use_vulkan ""
setprop debug.hwui.show_dirty_regions ""
setprop debug.zygote.disable_gl_preload 
setprop debug.cpurend.vsync ""
setprop debug.surface_flinger.max_frame_buffer_acquired_buffers ""
setprop debug.sf.enable_advanced_sf_phase_offset ""
setprop debug.sf.compbypass.enable ""
setprop debug.sf.enable_hwc_vds ""
setprop debug.sf.latch_unsignaled ""
setprop debug.sf.enable_gl_backpressure ""
setprop debug.sf.disable_backpressure ""
setprop debug.sf.recomputecrop ""
setprop debug.gralloc.enable_fb_ubwc ""
setprop debug.gralloc.disable_ubwc ""
setprop debug.gralloc.gfx_ubwc_disable 
setprop debug.force_sw_gles ""
setprop debug.zygote.preload.enable ""
setprop debug.hwui.renderer ""
setprop debug.renderengine.backend skiaglthreaded ""
setprop debug.renderthread.skia.reduceopstasksplitting ""
settings delete secure game_auto_temperature_control 
settings delete secure sem_performance_mode 
settings delete secure refresh_rate_mode 
settings delete system display_refresh_mode 
settings delete system Hardware.Renderer 
settings delete system HardwareAccelerated
settings delete system HardwareRenderer.Accelerated 
settings delete system HardwareRenderer.FrameRenderRequest
settings delete system HardwareRenderer.CodecImgBits 
settings delete system HardwareBuffer 
settings delete system gpu_mode 
settings delete system gpu_option_mode 
settings delete system game.accelerate.hw
settings delete secure speed_mode_enable 
settings delete secure user_refresh_rate
settings delete secure miui_refresh_rate 
settings delete system min_refresh_rate 
settings delete system peak_refresh_rate 
settings delete global GPUTUNER_SWITCH 
settings delete global zen_mode
settings delete global wifi_scan_always_enabled 
settings delete global updatable_driver_all_apps 
settings delete global fstrim_mandatory_interval 
setprop debug.sf.high_fps_early_gl_phase_offset_ns ""
setprop debug.sf.high_fps_late_app_phase_offset_ns ""
setprop debug.sf.early_phase_offset_ns ""
setprop debug.sf.early_app_phase_offset_ns ""
setprop debug.sf.early_gl_phase_offset_ns ""
setprop debug.sf.early_gl_app_phase_offset_ns ""
setprop debug.display.enable_fixed_fps ""
setprop debug.smart_dfps.enable ""
setprop debug.sf.showupdates ""
setprop debug.sf.showcpu ""
setprop debug.sf.showbackground ""
setprop debug.sf.showfps ""
setprop debug.atrace.tags.enableflags ""
#V4.0
settings delete global cpu_boost 
settings delete global performance_mode 
settings delete global game_mode
settings delete global settings_enable_monitor_phantom_procs
settings delete global gpu_debug_layers 
settings delete global force_gpu_rendering 
) > /dev/null 2>&1

(
settings delete system gpu_perf_mode 
setprop debug.sf.gpu_freq_indeks ""
settings delete system GPUTUNER_SWITCH 
setprop debug.gpurend.vsync ""
setprop debug.gfx.driver.¹ com.qualcomm.qti.gpudrivers.kona.api30 ""
setprop debug.hwui.target_gpu_time_percent ""
setprop debug.gpu.cooling.callback_freq_limit ""
settings delete system enable_triple_buffering 
settings delete system swappiness 
settings delete system gpu.use_extended_cache 
settings delete system gpu.prefetch_threshold
setprop debug.hwui.render_thread boost ""
setprop debug.hwui.raster_thread ""
settings delete system gpu_force_on 
settings delete system gpu_force_render 
) > /dev/null 2>&1

(
# Uninstall Mali Optimization
settings delete global mali.debug_level
settings delete global mali.use_l2_cache
settings delete global mali.dynamic_power
) > /dev/null 2>&1

(
# Uninstall GPU Perf Tweak V2.0
settings delete system ged_smart_boost
settings delete system boost_upper_bound
settings delete system gx_game_mode
settings delete system enable_game_self_frc_detect
settings delete system ged_boost_enable
settings delete system gx_boost_on
settings delete system boost_gpu_enable
settings delete system enable_gpu_boost
settings delete system ged_dvfs_enable
settings delete system boost_amp
settings delete system boost_extra
settings delete system gx_3D_benchmark_on
settings delete system gpu_idle
) > /dev/null 2>&1

# GPU Perf TWEAK 
(
setprop debug.composition.type ""
setprop debug.enabletr ""
setprop debug.overlayui.enable ""
setprop debug.performance.tuning ""
setprop debug.hw2d.force 1
settings delete system composition.type 
settings delete system sys.ui.hw 
) > /dev/null 2>&1

(
# Uninstall GPU Optimization
settings delete global gpu.optimize.level
settings delete global gpu.optimize.load_level
settings delete global gpu.optimize.driver_version
settings delete global gpu.optimize.preload
settings delete global gpu.optimize.purgeable_limit
settings delete global gpu.optimize.retry_max
settings delete global gpu.optimize.texture_control
settings delete global gpu.optimize.memory_compaction
settings delete global.gpu.optimize.hires_preload
settings delete global.gpu.optimize.fork_detector
settings delete global gpu.optimize.fork_detector_threshold
settings delete global.gpu.optimize.max_job_count
settings delete global.gpu.optimize.max_target_duration
settings delete global.gpu.optimize.min_target_size
) > /dev/null 2>&1

(
# Uninstall Frequency Optimization
settings delete global persist.sys.gpu_rendering_mode
settings delete global persist.sys.enable_gpu_boost
settings delete global persist.vendor.gpu_boost_mode
settings delete global gpu.tuning.performance_mode
) > /dev/null 2>&1

#Confiogurasi GPU Adreno
( 
setprop debug.qti.config.zram ""
settings delete global config.disable.hw_accel
settings delete global product.gpu.driver ""
settings delete global sf.compbypass.enable ""
settings delete system video.accelerate.hw ""
setprop debug.pm.dyn_samplingrate ""
setprop debug.com.qc.hardware ""
setprop debug.qc.hardware true
setprop debug.qctwa.preservebuf ""
setprop debug.qctwa.statusbar ""
settings delete system dev.pm.dyn_samplingrate""
settings delete global config.enable.hw_accel
) > /dev/null 2>&1
""".trimIndent(),
                label = getString(R.string.tweak_c2d_title), needsRoot = false
            ),
            TweakDef(
                R.id.tweak_fpsinjector_row, R.id.tweak_fpsinjector_switch, KEY_FPSINJECTOR,
                enableCmd  = """
(
# Optimize Refresh Rate
settings put global surface_flinger.use_content_detection_for_refresh_rate false
settings put global media.recorder-max-base-layer-fps 120
settings put global vendor.fps.switch.default true
settings put system vendor.disable_idle_fps true
settings put global vendor.display.default_fps 120
settings put system vendor.display.idle_default_fps 120
settings put system vendor.display.enable_optimize_refresh 1
settings put system vendor.display.video_or_camera_fps.support true
setprop debug.hwui.refresh_rate 120
setprop debug.sf.set_idle_timer_ms 500
setprop debug.sf.latch_unsignaled 1
setprop debug.sf.high_fps_early_phase_offset_ns 2000000
setprop debug.sf.high_fps_late_app_phase_offset_ns 500000
settings put system game_driver_min_frame_rate  120
settings put system game_driver_max_frame_rate  120
settings put system game_driver_power_saving_mode 0
settings put system game_driver_frame_skip_enable 0
settings put system game_driver_vsync_enable 0
settings put system game_driver_gpu_mode 1
settings put system game_driver_gpu_mode 1
settings put system game_driver_fps_limit 120
) > /dev/null 2>&1 &

(
#Fps Injector 
setprop debug.graphics.game_default_frame_rate 120
setprop debug.graphics.game_default_frame_rate.disabled false
setprop persist.sys.gpu_perf_mode 1
setprop debug.mtk.powerhal.hint.bypass 1
setprop persist.sys.surfaceflinger.idle_reduce_framerate_enable false
setprop sys.surfaceflinger.idle_reduce_framerate_enable false
setprop debug.sf.perf_mode 1
settings put global refresh.active 1
setprop debug.hwui.disable_vsync true
setprop debug.performance.profile 1
setprop debug.perf.tuning 1
) > /dev/null 2>&1 &
(
#New Tweak Fps Lock
settings put system user_refresh_rate 120
settings put system fps_limit 120
settings put system max_refresh_rate_for_ui 120
settings put system hwui_refresh_rate 120
settings put system display_refresh_rate 120
settings put system max_refresh_rate_for_gaming 120
#Mengatur margin Fps
settings put system fstb_target_fps_margin_high_fps 20
settings put system fstb_target_fps_margin_low_fps 20
settings put system gcc_fps_margin 10
#remove Refresh rate
settings put system tran_low_battery_60hz_refresh_rate.support 0
# Lock refresh rate to 120 Hz
settings put system vendor.display.refresh_rate 120
settings put system user_refresh_rate 1
settings put system sf.refresh_rate120
settings put secure user_refresh_rate 1
settings put secure miui_refresh_rate 120
settings put system min_frame_rate 120
settings put system max_frame_rate 120
settings put system tran_refresh_mode 120
settings put system last_tran_refresh_mode_in_refresh_setting 120
settings put global min_fps 120
settings put global max_fps 120
settings put system tran_need_recovery_refresh_mode 120
settings put system display_min_refresh_rate 120
settings put system min_refresh_rate 120
settings put system max_refresh_rate 120
settings put system peak_refresh_rate 120
settings put secure refresh_rate_mode 120
settings put system thermal_limit_refresh_rate 120
settings put system NV_FPSLIMIT 120
settings put system fps.limit.is.now locked
) > /dev/null 2>&1 &
""".trimIndent(),
                disableCmd = """
(
# UNINSTALL TWEAK - Optimize Refresh Rate & FPS Injector
settings delete global surface_flinger.use_content_detection_for_refresh_rate
settings delete global media.recorder-max-base-layer-fps
settings delete global vendor.fps.switch.default
settings delete global vendor.display.default_fps
settings delete global refresh.active
settings delete system vendor.disable_idle_fps
settings delete system vendor.display.idle_default_fps
settings delete system vendor.display.enable_optimize_refresh
settings delete system vendor.display.video_or_camera_fps.support
settings delete system game_driver_min_frame_rate
settings delete system game_driver_max_frame_rate
settings delete system game_driver_power_saving_mode
settings delete system game_driver_frame_skip_enable
settings delete system game_driver_vsync_enable
settings delete system game_driver_gpu_mode
settings delete system game_driver_fps_limit
setprop debug.hwui.refresh_rate ""
setprop debug.sf.set_idle_timer_ms ""
setprop debug.sf.latch_unsignaled ""
setprop debug.sf.high_fps_early_phase_offset_ns ""
setprop debug.sf.high_fps_late_app_phase_offset_ns ""
setprop debug.graphics.game_default_frame_rate ""
setprop debug.graphics.game_default_frame_rate.disabled ""
setprop persist.sys.gpu_perf_mode ""
setprop debug.mtk.powerhal.hint.bypass ""
setprop persist.sys.surfaceflinger.idle_reduce_framerate_enable ""
setprop sys.surfaceflinger.idle_reduce_framerate_enable ""
setprop debug.sf.perf_mode ""
setprop debug.hwui.disable_vsync
setprop debug.performance.profile ""
setprop debug.perf.tuning ""
settings delete system user_refresh_rate
settings delete system fps_limit
settings delete system max_refresh_rate_for_ui
settings delete system hwui_refresh_rate 
settings delete system display_refresh_rate 
settings delete system max_refresh_rate_for_gaming
settings delete system user_refresh_rate
settings delete system peak_refresh_rate
settings delete system thermal_limit_refresh_rate
settings delete system max_refresh_rate
settings delete system min_refresh_rate
) > /dev/null 2>&1 &
""".trimIndent(),
                label = getString(R.string.tweak_fpsinjector_title), needsRoot = true
            ),
            TweakDef(
                R.id.tweak_touch_row, R.id.tweak_touch_switch, KEY_TOUCH,
                enableCmd  = """
(
#V24 Nilai Input Touch Iphone
settings put global touch.sampling_boost 1
setprop debug.touch.sampling_boost 1.0
settings put global display_high_refresh_rate true
settings put global display.use_smooth_motion true
settings put global window_animation_scale 0.25
settings put global transition_animation_scale 0.25
settings put global animator_duration_scale 0.25
settings put secure display_density_forced 520
settings put system tap_duration 20
settings put secure pointer_speed 10
) > /dev/null 2>&1

(
#V23.0
settings put secure touch_exploration_enabled 0
settings put global power_mode_performance
setprop debug.windows.mgr.max_event_per_sec 180
settings put global min_pointer_dur 8 
settings put global max.fling_velocity 12000
settings put global min.fling_velocity 8000
setprop debug.view.scroll_friction 10
settings put global block_untrusted_touches 0
) > /dev/null 2>&1

#V21
( 
settings put system devices_virtual_input_input1_polling_rate 240
settings put global touch_sampling_rate 240
settings put global input.sampling_rate 1000
settings put system persist.sys.touch.sampling_boost 1
settings put global input.delay 0
settings put global input.resampling 1
settings put global input.gesture_prediction 0
settings put global input.touch_boost 1
settings put global min.touch.major 0
settings put global min.touch.minor 0
settings put system touch.boost true
settings put system touch.responsive 1
) > /dev/null 2>&1

#V20
(
settings put global touch.pressure.scale 0.1
settings put global touch.size.scale 0.1
settings put global settings_enable_monitor_phantom_procs false
settings put system pointer_speed 5
settings put global surface_flinger.set_idle_timer_ms 0
settings put global surface_flinger.set_touch_timer_ms 0
settings put global surface_flinger.set_display_power_timer_ms 0
setprop debug.sf.latch_unsignaled 1
setprop debug.sf.disable_backpressure 1
setprop debug.sf.multithreaded_present true
settings put global surface_flinger.use_context_priority 1
) > /dev/null 2>&1

(
#V19
settings put system touch_sampling_rate 120
settings put system touch_size_calibration geometric
settings put system touch_stats {"min":1,"max":1}
settings put system touchX_debuggable 1
settings put system touch_boost_threshold 5
settings put system touch_feature_gamemode_enable 1
settings put system touch_input_sensitivity 1
settings put system touch_rate_control 0
settings put system touch_response_rate 1
settings put system touch_sampling_rate_override 1
settings put system touch_sensitivity 1_2
settings put system touch_slop 8
settings put system touch_switch_set_touchscreen 14005
settings put system touch_tap_sensitivity 1
settings put system touchpanel_game_switch_enable 1
) > /dev/null 2>&1

(
#V18
settings put global surface_flinger.start_graphics_allocator_service true
settings put global surface_flinger.running_without_sync_framework true
setprop debug.sf.luma_sampling 0
setprop debug.sf.disable_client_composition_cache 1
setprop debug.sf.disable_backpressure 1
setprop debug.sf.enable_gl_backpressure 0
setprop debug.sf.enable_layer_caching 0
setprop debug.sf.disable_client_composition_cache 1 
setprop debug.sf.enable_gl_backpressure false
setprop debug.sf.enable_hwc_vds 0
setprop debug.sf.hw 0
setprop debug.sf.predict_hwc_composition_strategy 0
setprop debug.sf.use_phase_offsets_as_durations 1
#V17SCREEN.TOUCH
setprop debug.sf.use_phase_offsets_as_durations 1
setprop debug.sf.late.sf.duration 10500000
setprop debug.sf.late.app.duration 16600000
setprop debug.sf.treat_170m_as_sRGB 1
setprop debug.sf.earlyGl.app.duration 16600000
setprop debug.sf.frame_rate_multiple_threshold 120
setprop debug.boot.fps 20
#V16 Faster Touch 
setprop debug.performance.tuning 1
settings put global windowsmgr.support_low_latency_touch true
setprop debug.hwui.render_dirty_regions false
setprop debug.hwui.disable_vsync true
settings put system haptic_feedback_intensity 50
settings put global tactile_feedback_enabled 1
debug.sf.set_touch_timer_ms 100
###
setprop debug.MultitouchSettleInterval 0.01ms
setprop debyg.MultitouchMinDistance 0.01px
setprop debug.TapInterval 0.1ms
settings put global fw.bservice_enable true
settings put global fw.bg_apps_limit 4
settings put global fw.bservice_limit 4
settings put global fw.bservice_age 10000
setprop debug.touch.pressure.scale 0.001
setprop debug.touch_move_opt 1
setprop debug.touch_vsync_opt 1
setprop debug.touch.size.bias 0 
setprop debug.TapSlop1px
settings put global windowsmgr.max_events_per_sec 180
settings put global min_pointer_dur 8
settings put global product.multi_touch_enabled true
settings put global securestorage.knox false
setprop debug.security.mdpp none
setprop debug.security.mdpp.result none
settings put system af.resampler.quality 255
settings put system scrollingcache 3
setprop debug.service.lgospd.enable 0
setprop debug.service.pcsync.enable 0
setprop debug.touch.deviceTypetouchScreen
cmd device_config put input default_key_press_repeat_rate 33
cmd device_config put input filtered_accel_event_rate_hz 240
cmd device_config put input touch_screen_sample_interval_ms 8
cmd device_config put systemui cg_frame_interval_millis 4
cmd device_config put systemui low_power_refresh_rate_millis 0
cmd device_config put systemui low_power_refresh_rate_millis 0
cmd device_config put systemui cg_max_frame_skip 8
settings put system service.touch.tpf 30
settings put system lowThreshold 0
settings put system highThreshold 0
settings put system VirtualKeyQuietTime 0
settings put system KeyRepeatDelay 0
settings put system KeyRepeatTimeout 0
setprop debug.boosterorientnosync 1
settings put global sf.disable_smooth_effect true
settings put secure touch_distance_scale 0
settings put secure view_scroll_friction 0
settings put secure multi_touch_enabled 1
settings put secure assist_touch_gesture_enabled 0
settings put global maximum_obscuring_opacity_for_touch 0.5
settings put system show_touches 0
settings put system vsync.disable.fps.limit 1
settings put system table.framerate 120
setprop debug.touch.deviceType touchScreen
settings put system disable.hwc.delay 1
settings put system Touc_xRotation  360
settings put system touchswipedeadzone 5
settings put secure long_press_timeout 300
settings put secure multi_press_timeout 300
settings put secure touch_size_scale 5
settings put secure show_rotation_suggestions 0
settings put secure touch_size_bias 5
settings put secure touch_exploration_enabled 1
settings put secure touch_orientationAware 1
settings put secure touch_pressure_scale 0.00000125
settings put system touchscreen_hovering 0
settings put system touchscreen_sensitivity_mode 3
settings put system touchscreen_pressure_calibration 1023
settings put system touchscreen_threshold 9
settings put system touchfeature.gamemode.enable true
settings put system r.setframepace 120
settings put system touch_switch_set_touchscreen 14005
settings put system touchpanel_game_switch_enable 1
settings put system touchpanel_oppo_tp_direction 1
settings put system touchpanel_oppo_tp_limit_enable 0
settings put system touchpanel_oplus_tp_limit_enable 0
settings put system touchpanel_oplus_tp_direction 1
settings put system use_dithering 0
settings put system use_dithering false
settings put system qti.inputopts.enable true
settings put system qti.inputopts.movetouchslop 0.1
settings put global DragMinSwitchSpeed 99999.0px/s
settings put global SwipeMaxWidthRatio 1
settings put system MovementSpeedRatio 1
settings put system ZoomSpeedRatio 1
settings put system SwipeTransitionAngleCosine 3.6
settings put system mot.proximity.distance 1
settings put system PointerVelocityControlParameters 1
settings put system device.internal 1
setprop debug.performance.tuning 1
setprop debug.egl.swapinterval 90
settings put secure dev.pm.dyn_samplingrate 1
settings put system touchscreen_sensitivity 10
settings put system touchscreen_min_press_time 50
settings put system touchscreen_hevoring 0
settings put system touchscreen_gesture_mode 1
settings put system touchscreen_sensitivity_threshold 9
settings put system touchscreen_double_tap_speed 75
settings put system touchscreen_sensitivity_scale 1.5
settings put system qti.inputopts.enable true
settings put system qti.inputopts.movetouchslop 0.6
settings put system touch.orientationAware 1
settings put system SurfaceOrientation auto
settings put system touch.size.calibration geometric
settings put system touch.size.isSummed 1
settings put system touch.orientation.calibration auto
settings put system touch.distance.scale auto
settings put system touch.coverage.calibration octagram
settings put system touch.gesturemode spots
settings put system MovementSpeedRatio auto
settings put system pm.dyn_samplingrate 9999999999999999999999999999
settings put system touch.pressure.calibration auto
settings put system scroll.accelerated.hw true
settings put system ui.hwframes 9999999999999999999999999999
settings put system force_high_end_gfx 1
settings put system sf.disable_smooth_effect true
settings put system max_num_touch auto
settings put system maxeventspersec 9999999999999999999999999999
settings put system resampler.quality 255
settings put system touch.sampling rate 720
settings put system adaptive_touch_sensitivity speed
settings put system touch.orientationAware 0
settings put system PressureForID 0.01
settings put system QuietInterval 0.1ms
settings put system MultitouchMinDistance 1px
settings put system AIM_SENSITIVITY_TRANSITION_TIME GRADUAL
settings put system APP_SWITCH_DELAY_TIME false
settings put system AbsoluteXForID SpeedForID
settings put system AccelerationX true
settings put system AccelerationY true
settings put system DoubleTouch OEM
settings put system PowerbuttonTapping 0
settings put system touch.assistant.enabled 0
settings put system type.touch_speed true
settings put system MovementSpeedRatio 0.8
settings put system accuracy.control 100
settings put system view_scroll_friction 10
settings put secure multi_press_timeout 300
settings put global KeyRepeatDelay 0
settings put global KeyRepeatTimeout 0
settings put global LOSS_OF_FOCUS_BY_MOUSE_MOVEMENT DISABLED
settings put global MOUSEX_AIM_LEVEL 95%
) > /dev/null 2>&1
""".trimIndent(),
                disableCmd = """
(
#V23
settings delete global touch.sampling_boost 
setprop debug.touch.sampling_boost ""
settings delete global display_high_refresh_rate 
settings delete global display.use_smooth_motion 
settings delete secure touch_exploration_enabled 
settings delete global power_mode_performance
setprop debug.windows.mgr.max_event_per_sec ""
settings delete global min_pointer_dur 
settings delete global max.fling_velocity 
settings delete global min.fling_velocity 
setprop debug.view.scroll_friction ""
settings delete global block_untrusted_touches 
#V22
settings delete global window_animation_scale
settings delete global transition_animation_scale
settings delete global animator_duration_scale
settings delete secure display_density_forced
settings delete system tap_duration
settings delete system view.scroll_friction
settings delete secure pointer_speed
#V21
settings delete system devices_virtual_input_input1_polling_rate 
settings delete global touch_sampling_rate
settings delete global input.sampling_rate
settings delete system persist.sys.touch.sampling_boost 
settings delete global input.delay 
settings delete global input.resampling 
settings delete global input.gesture_prediction 
settings delete global input.touch_boost
settings delete global min.touch.major 
settings delete global min.touch.minor 
settings delete system touch.boost 
settings delete system touch.responsive 
#V19
settings delete system touch_sampling_rate
settings delete system touch_size_calibration
settings delete system touch_stats
settings delete system touchX_debuggable 
settings delete system touch_boost_threshold 
settings delete system touch_feature_gamemode_enable 
settings delete system touch_input_sensitivity 
settings delete system touch_rate_control 
settings delete system touch_response_rate 
settings delete system touch_sampling_rate_override 
settings delete system touch_sensitivity
settings delete system touch_slop 
settings delete system touch_switch_set_touchscreen
settings delete system touch_tap_sensitivity 
settings delete system touchpanel_game_switch_enable
settings delete global surface_flinger.start_graphics_allocator_service
settings delete global surface_flinger.running_without_sync_framework
setprop debug.sf.luma_sampling ""
setprop debug.sf.disable_client_composition_cache ""
setprop debug.sf.disable_backpressure ""
setprop debug.sf.enable_gl_backpressure ""
setprop debug.sf.enable_layer_caching ""
setprop debug.sf.disable_client_composition_cache ""
setprop debug.sf.enable_gl_backpressure ""
setprop debug.sf.enable_hwc_vds ""
setprop debug.sf.hw ""
setprop debug.sf.predict_hwc_composition_strategy ""
setprop debug.sf.use_phase_offsets_as_durations ""
setprop debug.sf.use_phase_offsets_as_durations ""
setprop debug.sf.late.sf.duration ""
setprop debug.sf.late.app.duration ""
setprop debug.sf.treat_170m_as_sRGB ""
setprop debug.sf.earlyGl.app.duration ""
setprop debug.sf.frame_rate_multiple_threshold ""
setprop debug.boot.fps ""
setprop debug.performance.tuning ""
settings delete system view.scroll_friction
settings delete global windowsmgr.support_low_latency_touch
setprop debug.hwui.render_dirty_regions ""
setprop debug.hwui.disable_vsync ""
settings delete system haptic_feedback_intensity
settings delete global tactile_feedback_enabled
debug.sf.set_touch_timer_ms ""
settings delete global fw.bservice_enable 
settings delete global fw.bg_apps_limit
settings delete global fw.bservice_limit
settings delete global fw.bservice_age 
setprop debug.touch.pressure.scale ""
setprop debug.touch_move_opt ""
setprop debug.touch_vsync_opt ""
# Delete CMD configurations
# Remove input configurations
cmd device_config delete input default_key_press_repeat_rate
cmd device_config delete input filtered_accel_event_rate_hz
cmd device_config delete input touch_screen_sample_interval_ms
# Remove system UI configurations
cmd device_config delete systemui cg_frame_interval_millis
cmd device_config delete systemui low_power_refresh_rate_millis
cmd device_config delete systemui cg_max_frame_skip
# Remove system props
setprop debug.touch.size.bias 
setprop debug.MultitouchSettleInterval 
setprop debug.TapInterval 
setprop debug.TapSlop 
setprop debug.security.mdpp 
setprop debug.security.mdpp.result 
setprop debug.service.lgospd.enable 
setprop debug.service.pcsync.enable 
setprop debug.touch.deviceType 
setprop debug.boosterorientnosync 
setprop debug.performance.tuning 
setprop debug.egl.swapinterval 
# Remove global settings
settings delete global windowsmgr.max_events_per_sec
settings delete global min_pointer_dur
settings delete global product.multi_touch_enabled
settings delete global securestorage.knox
settings delete global sf.disable_smooth_effect
settings delete global block_untrusted_touches
settings delete global KeyRepeatDelay
settings delete global KeyRepeatTimeout
settings delete global window_animation_scale
settings delete global transition_animation_scale
settings delete global animator_duration_scale
settings delete global DragMinSwitchSpeed
settings delete global SwipeMaxWidthRatio
# Remove secure settings
settings delete secure touch_distance_scale
settings delete secure view_scroll_friction
settings delete secure multi_touch_enabled
settings delete secure assist_touch_gesture_enabled
settings delete secure touch_size_scale
settings delete secure show_rotation_suggestions
settings delete secure touch_size_bias
settings delete secure touch_exploration_enabled
settings delete secure touch_orientationAware
settings delete secure touch_pressure_scale
settings delete secure dev.pm.dyn_samplingrate
# Remove system settings
settings delete system af.resampler.quality
settings delete system scrollingcache
settings delete system show_touches
settings delete system vsync.disable.fps.limit
settings delete system table.framerate
settings delete system disable.hwc.delay
settings delete system Touc_xRotation
settings delete system touchswipedeadzone
settings delete system pointer_speed
settings delete system touchscreen_hovering
settings delete system touchscreen_sensitivity_mode
settings delete system touchscreen_pressure_calibration
settings delete system touchscreen_threshold
settings delete system touchfeature.gamemode.enable
settings delete system r.setframepace
settings delete system touch_switch_set_touchscreen
settings delete system touchpanel_game_switch_enable
settings delete system touchpanel_oppo_tp_direction
settings delete system touchpanel_oppo_tp_limit_enable
settings delete system use_dithering
settings delete system qti.inputopts.enable
settings delete system qti.inputopts.movetouchslop
settings delete system MovementSpeedRatio
settings delete system ZoomSpeedRatio
settings delete system SwipeTransitionAngleCosine
settings delete system mot.proximity.distance
settings delete system PointerVelocityControlParameters
settings delete system device.internal
settings delete system touchscreen_min_press_time
settings delete system touchscreen_gesture_mode
settings delete system touchscreen_pointer_speed
settings delete system touchscreen_sensitivity_threshold
settings delete system touchscreen_double_tap_speed
settings delete system touchscreen_sensitivity_scale
settings delete system SurfaceOrientation
settings delete system touch.size.calibration
settings delete system touch.size.scale
settings delete system touch.size.isSummed
settings delete system touch.orientation.calibration
settings delete system touch.distance.scale
settings delete system touch.coverage.calibration
settings delete system touch.pressure.scale
settings delete system touch.gesturemode
settings delete system MultitouchMinDistance
settings delete system scroll.accelerated.hw
settings delete system ui.hwframes
settings delete system force_high_end_gfx
settings delete system max_num_touch
settings delete system view.touch_slop
settings delete system maxeventspersec
settings delete system resampler.quality
settings delete system touch.sampling_rate
settings delete system adaptive_touch_sensitivity
settings delete system PressureForID
settings delete system QuietInterval
settings delete system AIM_SENSITIVITY_TRANSITION_TIME
settings delete system APP_SWITCH_DELAY_TIME
settings delete system AbsoluteXForID
settings delete system AccelerationX
settings delete system AccelerationY
settings delete system DoubleTouch
settings delete system PowerbuttonTapping
settings delete system touch.assistant.enabled
settings delete system type.touch_speed
settings delete system accuracy.control
) > /dev/null 2>&1
""".trimIndent(),
                label = getString(R.string.tweak_touch_title), needsRoot = false
            ),
            TweakDef(
                R.id.tweak_battery_row, R.id.tweak_battery_switch, KEY_BATTERY,
                enableCmd  = """
for app in ${'$'}(cmd package list packages -3 | cut -f 2 -d ":"); do
if [[ ! "${'$'}app" == "me.piebridge.brevent" ]] && [[ ! "${'$'}app" == "com.vexiro.magiks" ]]; then
cmd activity force-stop "${'$'}app"
fi
done
sleep 0.5
echo""
(
settings put global setprop battery.name Li-Po
settings put global setprop battery.cooling_name 'battery'
settings put global setprop battery.def_target 0
settings put global setprop battery.select_higher 0
settings put global setprop battery_target_temp 40
) > /dev/null 2>&1
sleep 0.5
echo""
echo""
(
settings put global config.enable.fast_charge 1
settings put global adb_enabled 1
settings put global usb_mass_storage_enabled 0
settings put global sys_usb_config mtp,adb
settings put secure location_mode 0
settings put global background_data 0
settings put global stay_on_while_plugged_in 0
settings put global adaptive_fast_charging 1
settings put system aod_charging_mode 0
settings put system boost_charging_speed 1
settings put system charging_info_always 0
settings put system wireless_fast_charging 1
settings put system super_fast_charging 1
settings put system_class_power_supply_battery_constant_charge_current_max 5500000
setprop debug.adaptive.fast.charging 1
settings put global adaptive_fast_charging 1
settings put system adaptive_fast_charging 1
settings put system boost_charging_speed 1
settings put system charging_option 1
settings put system force_all_apps_stopped 1
dumpsys deviceidle force-idle
) > /dev/null 2>&1

(
#Penyebab Charger Jadi Slow
settings delete global surface_flinger.set_idle_timer_ms 
 settings delete global surface_flinger.set_touch_timer_ms
 settings delete global surface_flinger.set_display_power_timer_ms
) > /dev/null 2>&1
""".trimIndent(),
                disableCmd = """
(
settings delete global battery.name
settings delete global battery.cooling_name
settings delete global battery.def_target
settings delete global battery.select_higher
settings delete global battery_target_temp
) > /dev/null 2>&1

(
# Reverting fast charging settings to default
settings delete global config.enable.fast_charge 
settings delete system super_fast_charging
settings delete system_class_power_supply_battery_constant_charge_current_max
settings delete global adaptive_fast_charging
settings delete system adaptive_fast_charging
settings delete system boost_charging_speed
settings delete system charging_option
) > /dev/null 2>&1

(
# Reverting force all apps stopped setting to default
settings delete system force_all_apps_stopped
# Reverting device idle settings to default
dumpsys deviceidle unforce
settings delete global device_idle_constants
) > /dev/null 2>&1
""".trimIndent(),
                label = getString(R.string.tweak_battery_title), needsRoot = false
            ),
        )

        for (t in tweaks) {
            val sw = view.findViewById<MaterialSwitch>(t.switchId)

            // Set the saved state without triggering the listener
            sw.setOnCheckedChangeListener(null)
            sw.isChecked = prefs.getBoolean(t.key, false)

            // A single listener — runs the command
            sw.setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean(t.key, isChecked).apply()
                val cmd = if (isChecked) t.enableCmd else t.disableCmd
                execShell(cmd, needsRoot = t.needsRoot) {
                    val state = if (isChecked) getString(R.string.enabled) else getString(R.string.disabled)
                    snack("${t.label} — $state")
                }
            }

            // The row only toggles the switch — the listener is what runs the command
            view.findViewById<View>(t.rowId).setOnClickListener {
                sw.isChecked = !sw.isChecked
            }
        }

        // ── Visually disable Root rows when Root is absent ─────────────────────────────
        // The user doesn't see an active UI for features that won't work — prevents the
        // toggle from moving with no effect
        val hasRoot = RootManager.getInstance(requireContext()).isAvailable
        if (!hasRoot) {
            tweaks.filter { it.needsRoot }.forEach { t ->
                view.findViewById<View>(t.rowId).also { row ->
                    row.alpha       = 0.38f
                    row.isEnabled   = false
                    row.isClickable = false
                }
                view.findViewById<MaterialSwitch>(t.switchId).also { sw ->
                    sw.isEnabled   = false
                    sw.isClickable = false
                }
            }
        }
    }
}
