package com.youki.dex.fragments

import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView.OnItemClickListener
import android.widget.ArrayAdapter
import android.widget.GridView
import android.widget.ViewSwitcher
import androidx.core.widget.addTextChangedListener
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.youki.dex.R
import com.youki.dex.dialogs.DockLayoutDialog
import com.youki.dex.utils.AppUtils
import com.youki.dex.utils.ColorUtils
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import com.google.android.material.slider.LabelFormatter
import com.youki.dex.preferences.SliderPreference
import android.app.Activity
import android.content.Intent
import android.provider.OpenableColumns
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.snackbar.Snackbar
import com.youki.dex.utils.FontManager
import com.youki.dex.utils.FontManager.FontScript

class AppearancePreferences : PreferenceFragmentCompat() {
    private lateinit var mainColorPref: Preference
    private lateinit var bubbleColorPref: Preference
    private lateinit var pickFont: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pickFont = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val uri = result.data?.data ?: return@registerForActivityResult
            val ctx = requireContext()
            val displayName = ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                c.moveToFirst(); if (idx >= 0) c.getString(idx) else null
            } ?: "custom_font.ttf"
            val ext = displayName.substringAfterLast('.', "").lowercase()
            if (ext !in listOf("ttf", "otf")) {
                Snackbar.make(requireView(), getString(R.string.plugin_font_error), Snackbar.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            val path = FontManager.copyFontFromUri(ctx, uri, displayName, FontScript.ARABIC) ?: return@registerForActivityResult
            FontManager.saveFont(ctx, path, displayName, FontScript.ARABIC)
            FontManager.saveFont(ctx, path, displayName, FontScript.LATIN)
            updateFontSummary()
            Snackbar.make(requireView(), getString(R.string.plugin_font_applied), Snackbar.LENGTH_SHORT).show()
        }
    }

    override fun onCreatePreferences(arg0: Bundle?, arg1: String?) {
        setPreferencesFromResource(R.xml.preferences_appearance, arg1)
        mainColorPref   = findPreference("theme_main_color")!!
        bubbleColorPref = findPreference("bubble_color_picker")!!

        // Bubble mode: show custom controls only when mode = "custom"
        val prefs = preferenceManager.sharedPreferences!!
        fun updateBubbleVisibility(mode: String) {
            val isCustom = mode == "custom"
            bubbleColorPref.isVisible = isCustom
        }
        // bubble_alpha is always shown — it affects both material_u and custom modes
        updateBubbleVisibility(prefs.getString("bubble_mode", "material_u") ?: "material_u")

        findPreference<androidx.preference.ListPreference>("bubble_mode")!!
            .setOnPreferenceChangeListener { _, newValue ->
                updateBubbleVisibility(newValue.toString())
                true
            }

        bubbleColorPref.setOnPreferenceClickListener {
            showBubbleColorPickerDialog(requireContext())
            false
        }

        // Bubble opacity — always shown, affects both material_u and custom
        findPreference<Preference>("bubble_opacity_picker")?.let { opacityPref ->
            opacityPref.isVisible = true
            fun updateOpacitySummary() {
                val alpha = prefs.getInt("bubble_alpha", 115)
                val pct = (alpha / 255f * 100).toInt()
                opacityPref.summary = "$pct%"
            }
            updateOpacitySummary()
            opacityPref.setOnPreferenceClickListener {
                showBubbleOpacityDialog(requireContext()) { updateOpacitySummary() }
                false
            }
        }

        // Theme: show custom color only when theme = "custom"
        mainColorPref.setOnPreferenceClickListener {
            showColorPickerDialog(requireContext())
            false
        }
        val currentTheme = prefs.getString("theme", "material_u") ?: "material_u"
        mainColorPref.isVisible = currentTheme == "custom"

        findPreference<androidx.preference.ListPreference>("theme")!!.setOnPreferenceChangeListener { _, newValue ->
            mainColorPref.isVisible = newValue.toString() == "custom"
            true
        }
        findPreference<Preference>("tint_indicators")!!.isVisible = AppUtils.isSystemApp(requireContext(), requireContext().packageName)

        findPreference<Preference>("dock_layout")!!.setOnPreferenceClickListener {
            DockLayoutDialog(requireContext())
            false
        }

        // 1.15: Dock Background Transparency slider
        val dockAlphaPref = findPreference<SliderPreference>("dock_background_alpha")
        dockAlphaPref?.setOnDialogShownListener(object : SliderPreference.OnDialogShownListener {
            override fun onDialogShown() {
                val slider = dockAlphaPref.slider ?: return
                slider.isTickVisible = false
                slider.labelBehavior = com.google.android.material.slider.LabelFormatter.LABEL_GONE
                slider.stepSize = 1f
                // Bounds set before value, then clamped — see the fix note
                // on dock_height in DockPreferences.kt for why this order
                // matters (crash if a stored value ever falls outside the
                // range in effect when it's assigned).
                slider.valueFrom = 0f
                slider.valueTo = 255f
                slider.value =
                    (dockAlphaPref.sharedPreferences?.getString("dock_background_alpha", "255")?.toFloatOrNull() ?: 255f)
                        .coerceIn(slider.valueFrom, slider.valueTo)
                slider.addOnChangeListener { _, value, _ ->
                    dockAlphaPref.sharedPreferences?.edit { putString("dock_background_alpha", value.toInt().toString()) }
                }
            }
        })

        // App Dock Background Transparency slider — interconnected with bubble_alpha
        val appDockAlphaPref = findPreference<SliderPreference>("app_dock_bg_opacity")
        val defaultAppDockAlpha = prefs.getInt("bubble_alpha", 115).toFloat()
        val curAppDockVal = (appDockAlphaPref?.sharedPreferences?.getString("app_dock_bg_opacity", defaultAppDockAlpha.toInt().toString())?.toFloatOrNull() ?: defaultAppDockAlpha).toInt()
        val initPct = (curAppDockVal / 255f * 100).toInt()
        appDockAlphaPref?.summary = "$initPct%"
        appDockAlphaPref?.setOnDialogShownListener(object : SliderPreference.OnDialogShownListener {
            override fun onDialogShown() {
                val slider = appDockAlphaPref.slider ?: return
                slider.isTickVisible = false
                slider.labelBehavior = com.google.android.material.slider.LabelFormatter.LABEL_GONE
                slider.stepSize = 1f
                slider.valueFrom = 0f
                slider.valueTo = 255f
                val defAlpha = prefs.getInt("bubble_alpha", 115).toFloat()
                slider.value =
                    (appDockAlphaPref.sharedPreferences?.getString("app_dock_bg_opacity", defAlpha.toInt().toString())?.toFloatOrNull() ?: defAlpha)
                        .coerceIn(slider.valueFrom, slider.valueTo)
                slider.addOnChangeListener { _, value, _ ->
                    val pct = (value / 255f * 100).toInt()
                    appDockAlphaPref.summary = "$pct%"
                    val alphaInt = value.toInt()
                    appDockAlphaPref.sharedPreferences?.edit {
                        putString("app_dock_bg_opacity", alphaInt.toString())
                        putInt("bubble_alpha", alphaInt)
                    }
                    findPreference<Preference>("bubble_opacity_picker")?.summary = "$pct%"
                }
            }
        })

        // Dock blur radius slider
        val dockBlurPref = findPreference<SliderPreference>("dock_blur_radius")
        dockBlurPref?.setOnDialogShownListener(object : SliderPreference.OnDialogShownListener {
            override fun onDialogShown() {
                val slider = dockBlurPref.slider ?: return
                slider.isTickVisible = false
                slider.labelBehavior = com.google.android.material.slider.LabelFormatter.LABEL_GONE
                slider.stepSize = 2f
                slider.valueFrom = 0f
                slider.valueTo = 60f
                slider.value =
                    (dockBlurPref.sharedPreferences?.getString("dock_blur_radius", "20")?.toFloatOrNull() ?: 20f)
                        .coerceIn(slider.valueFrom, slider.valueTo)
                slider.addOnChangeListener { _, value, _ ->
                    dockBlurPref.sharedPreferences?.edit { putString("dock_blur_radius", value.toInt().toString()) }
                }
            }
        })

        // Icon padding slider (Appearance section)
        val iconPaddingPref = findPreference<SliderPreference>("icon_padding")
        iconPaddingPref?.setOnDialogShownListener(object : SliderPreference.OnDialogShownListener {
            override fun onDialogShown() {
                val slider = iconPaddingPref.slider ?: return
                slider.isTickVisible = false
                slider.labelBehavior = LabelFormatter.LABEL_GONE
                slider.stepSize = 1f
                // Bounds set before value, then clamped — see the fix note
                // on dock_height in DockPreferences.kt.
                slider.valueFrom = 0f
                slider.valueTo = 20f
                slider.value =
                    (iconPaddingPref.sharedPreferences?.getString("icon_padding", "5")?.toFloatOrNull() ?: 5f)
                        .coerceIn(slider.valueFrom, slider.valueTo)
                slider.addOnChangeListener { _, value, _ ->
                    iconPaddingPref.sharedPreferences?.edit { putString("icon_padding", value.toInt().toString()) }
                }
            }
        })

        // ── Icon size slider (50%–150%) ─────────────────────────────────────
        val iconScalePref = findPreference<SliderPreference>("icon_scale_percent")
        iconScalePref?.setOnDialogShownListener(object : SliderPreference.OnDialogShownListener {
            override fun onDialogShown() {
                val slider = iconScalePref.slider ?: return
                slider.isTickVisible = false
                slider.labelBehavior = LabelFormatter.LABEL_FLOATING
                slider.stepSize = 5f
                slider.valueFrom = com.youki.dex.utils.IconScaleUtils.ICON_SCALE_MIN.toFloat()
                slider.valueTo = com.youki.dex.utils.IconScaleUtils.ICON_SCALE_MAX.toFloat()
                slider.value = com.youki.dex.utils.IconScaleUtils.getIconScalePercent(requireContext()).toFloat()
                slider.setLabelFormatter { value -> "${value.toInt()}%" }
                slider.addOnChangeListener { _, value, _ ->
                    com.youki.dex.utils.IconScaleUtils.setIconScalePercent(requireContext(), value.toInt())
                    updateIconScaleSummary()
                }
            }
        })
        updateIconScaleSummary()

        // ── Label font size slider (50%–150%) ───────────────────────────────
        val fontScalePref = findPreference<SliderPreference>("label_font_scale_percent")
        fontScalePref?.setOnDialogShownListener(object : SliderPreference.OnDialogShownListener {
            override fun onDialogShown() {
                val slider = fontScalePref.slider ?: return
                slider.isTickVisible = false
                slider.labelBehavior = LabelFormatter.LABEL_FLOATING
                slider.stepSize = 5f
                slider.valueFrom = com.youki.dex.utils.IconScaleUtils.FONT_SCALE_MIN.toFloat()
                slider.valueTo = com.youki.dex.utils.IconScaleUtils.FONT_SCALE_MAX.toFloat()
                slider.value = com.youki.dex.utils.IconScaleUtils.getFontScalePercent(requireContext()).toFloat()
                slider.setLabelFormatter { value -> "${value.toInt()}%" }
                slider.addOnChangeListener { _, value, _ ->
                    com.youki.dex.utils.IconScaleUtils.setFontScalePercent(requireContext(), value.toInt())
                    updateFontScaleSummary()
                }
            }
        })
        updateFontScaleSummary()

        // ── App-wide font scale slider (70%-160%) — covers every screen in the
        // app, the dock, and the menus, unlike the slider above which only
        // covers app names. See AppFontScaleUtils.kt: as of v65 this scales
        // each TextView directly in place (no Configuration/Context wrapping),
        // so changes can be applied instantly without recreating anything.
        val appFontScalePref = findPreference<SliderPreference>("app_font_scale_percent")
        appFontScalePref?.setOnDialogShownListener(object : SliderPreference.OnDialogShownListener {
            override fun onDialogShown() {
                val slider = appFontScalePref.slider ?: return
                slider.isTickVisible = false
                slider.labelBehavior = LabelFormatter.LABEL_FLOATING
                slider.stepSize = 5f
                slider.valueFrom = com.youki.dex.utils.AppFontScaleUtils.FONT_SCALE_MIN.toFloat()
                slider.valueTo = com.youki.dex.utils.AppFontScaleUtils.FONT_SCALE_MAX.toFloat()
                slider.value = com.youki.dex.utils.AppFontScaleUtils.getFontScalePercent(requireContext()).toFloat()
                slider.setLabelFormatter { value -> "${value.toInt()}%" }
                slider.addOnChangeListener { _, value, _ ->
                    com.youki.dex.utils.AppFontScaleUtils.setFontScalePercent(requireContext(), value.toInt())
                    updateAppFontScaleSummary()
                    // Instantly rescale this very screen too — no Activity
                    // recreation needed since this only touches TextView sizes
                    // directly, not Configuration/Context.
                    com.youki.dex.utils.AppFontScaleUtils.applyToViewHierarchy(view)
                }
            }
        })
        updateAppFontScaleSummary()

        // ── Custom badge (a general number on every icon) ─────────────────────────
        findPreference<Preference>("custom_badge_text")?.let { badgePref ->
            updateCustomBadgeSummary()
            badgePref.setOnPreferenceClickListener {
                showCustomBadgeInput()
                false
            }
        }

        // ── Auto Resolution picker ────────────────────────────────────────
        findPreference<Preference>("desktop_res_value")!!.setOnPreferenceClickListener {
            showAutoResolutionPicker()
            false
        }
        updateResolutionSummary()

        // ── Custom Font ───────────────────────────────────────────────────────
        findPreference<Preference>("custom_font")?.let { fontPref ->
            updateFontSummary()
            fontPref.setOnPreferenceClickListener {
                showFontOptions()
                false
            }
        }
    }

    private fun showBubbleColorPickerDialog(context: Context) {
        val prefs = preferenceManager.sharedPreferences ?: return
        val currentHex = prefs.getString("bubble_color", "#0C0C0C").orEmpty().ifEmpty { "#0C0C0C" }

        val dialog = MaterialAlertDialogBuilder(context)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_color_picker, null)
        val colorPreview = view.findViewById<View>(R.id.color_preview)
        val colorHexEt   = view.findViewById<TextInputEditText>(R.id.color_hex_et)
        val alphaSb      = view.findViewById<Slider>(R.id.color_alpha_sb)
        val redSb        = view.findViewById<Slider>(R.id.color_red_sb)
        val greenSb      = view.findViewById<Slider>(R.id.color_green_sb)
        val blueSb       = view.findViewById<Slider>(R.id.color_blue_sb)
        val viewSwitcher = view.findViewById<ViewSwitcher>(R.id.colors_view_switcher)
        val toggleGroup  = view.findViewById<MaterialButtonToggleGroup>(R.id.colors_btn_toggle)

        // The opacity slider — controls bubble_alpha (0 = fully transparent, 255 = solid)
        alphaSb.visibility = android.view.View.VISIBLE
        alphaSb.valueFrom = 0f
        alphaSb.valueTo = 255f
        alphaSb.stepSize = 1f
        val savedAlpha = prefs.getInt("bubble_alpha", 115)
        alphaSb.value = savedAlpha.toFloat()

        fun updatePreview() {
            val hex = colorHexEt.text.toString()
            val color = ColorUtils.toColor(hex)
            if (color != -1) {
                colorPreview.background?.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_ATOP)
                colorPreview.background?.alpha = alphaSb.value.toInt()
            }
        }

        alphaSb.addOnChangeListener { _, _, _ -> updatePreview() }

        colorHexEt.addTextChangedListener { text ->
            val hex = text.toString()
            var color = -1
            if (hex.length == 7 && ColorUtils.toColor(hex).also { color = it } != -1) {
                colorPreview.background?.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_ATOP)
                colorPreview.background?.alpha = alphaSb.value.toInt()
                redSb.value   = Color.red(color).toFloat()
                greenSb.value = Color.green(color).toFloat()
                blueSb.value  = Color.blue(color).toFloat()
            }
        }
        val onRgbChange = Slider.OnChangeListener { _, _, fromUser ->
            if (fromUser) colorHexEt.setText(ColorUtils.toHexColor(
                Color.rgb(redSb.value.toInt(), greenSb.value.toInt(), blueSb.value.toInt())))
        }
        redSb.addOnChangeListener(onRgbChange)
        greenSb.addOnChangeListener(onRgbChange)
        blueSb.addOnChangeListener(onRgbChange)
        colorHexEt.setText(currentHex)

        val presetsGv = view.findViewById<android.widget.GridView>(R.id.presets_gv)
        presetsGv.adapter = HexColorAdapter(context, context.resources.getStringArray(R.array.default_color_values))
        presetsGv.onItemClickListener = android.widget.AdapterView.OnItemClickListener { adapterView, _, position, _ ->
            colorHexEt.setText(adapterView.getItemAtPosition(position).toString())
            toggleGroup.check(R.id.custom_button)
            viewSwitcher.showNext()
        }
        // FIX: showPrevious/showNext wrap around each other — displayedChild is more correct and stable
        view.findViewById<View>(R.id.custom_button).setOnClickListener { viewSwitcher.displayedChild = 0 }
        view.findViewById<View>(R.id.presets_button).setOnClickListener { viewSwitcher.displayedChild = 1 }

        dialog.setNegativeButton(R.string.cancel, null)
        dialog.setPositiveButton(R.string.ok) { _, _ ->
            val hex = colorHexEt.text.toString()
            if (ColorUtils.toColor(hex) != -1) {
                val alphaVal = alphaSb.value.toInt()
                prefs.edit()
                    .putString("bubble_color", hex)
                    .putInt("bubble_alpha", alphaVal)
                    .putString("app_dock_bg_opacity", alphaVal.toString())
                    .apply()
                findPreference<Preference>("bubble_opacity_picker")?.summary = "${(alphaVal / 255f * 100).toInt()}%"
                findPreference<SliderPreference>("app_dock_bg_opacity")?.summary = "${(alphaVal / 255f * 100).toInt()}%"
            }
        }
        dialog.setView(view)
        dialog.show()
    }
    private fun showBubbleOpacityDialog(context: Context, onSaved: () -> Unit) {
        val prefs = preferenceManager.sharedPreferences!!
        val dialogView = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
        }
        val label = android.widget.TextView(context).apply {
            val alpha = prefs.getInt("bubble_alpha", 115)
            text = "${(alpha / 255f * 100).toInt()}%"
            textSize = 16f
            gravity = android.view.Gravity.CENTER
        }
        val slider = com.google.android.material.slider.Slider(context).apply {
            valueFrom = 0f
            valueTo = 100f
            stepSize = 1f
            value = (prefs.getInt("bubble_alpha", 115) / 255f * 100).toInt().toFloat()
            isTickVisible = false
            labelBehavior = com.google.android.material.slider.LabelFormatter.LABEL_GONE
            addOnChangeListener { _, v, _ -> label.text = "${v.toInt()}%" }
        }
        dialogView.addView(label)
        dialogView.addView(slider)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
            .setTitle(R.string.bubble_opacity_title)
            .setView(dialogView)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                val alpha = (slider.value / 100f * 255).toInt().coerceIn(0, 255)
                prefs.edit()
                    .putInt("bubble_alpha", alpha)
                    .putString("app_dock_bg_opacity", alpha.toString())
                    .apply()
                findPreference<SliderPreference>("app_dock_bg_opacity")?.summary = "${(alpha / 255f * 100).toInt()}%"
                onSaved()
            }
            .show()
    }

    private fun showColorPickerDialog(context: Context) {
        val dialog = MaterialAlertDialogBuilder(context)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_color_picker, null)
        val colorPreview = view.findViewById<View>(R.id.color_preview)
        val colorHexEt = view.findViewById<TextInputEditText>(R.id.color_hex_et)
        val alphaSb = view.findViewById<Slider>(R.id.color_alpha_sb)
        val redSb = view.findViewById<Slider>(R.id.color_red_sb)
        val greenSb = view.findViewById<Slider>(R.id.color_green_sb)
        val blueSb = view.findViewById<Slider>(R.id.color_blue_sb)
        val viewSwitcher = view.findViewById<ViewSwitcher>(R.id.colors_view_switcher)
        val toggleGroup = view.findViewById<MaterialButtonToggleGroup>(R.id.colors_btn_toggle)
        colorHexEt.addTextChangedListener { text ->
            val hexColor = text.toString()
            var color = -1
            if (hexColor.length == 7 && ColorUtils.toColor(hexColor).also { color = it } != -1) {
                colorPreview.background?.setColorFilter(color, PorterDuff.Mode.SRC_ATOP)
                redSb.value = Color.red(color).toFloat()
                greenSb.value = Color.green(color).toFloat()
                blueSb.value = Color.blue(color).toFloat()
            } else colorHexEt.error = getString(R.string.invalid_color)

        }
        alphaSb.addOnChangeListener { _, value, _ -> colorPreview.background?.alpha = value.toInt() }
        val onChangeListener = Slider.OnChangeListener { _, _, fromUser ->
            if (fromUser) colorHexEt.setText(ColorUtils.toHexColor(
                    Color.rgb(redSb.value.toInt(), greenSb.value.toInt(), blueSb.value.toInt())))
        }
        redSb.addOnChangeListener(onChangeListener)
        greenSb.addOnChangeListener(onChangeListener)
        blueSb.addOnChangeListener(onChangeListener)
        dialog.setNegativeButton(R.string.cancel, null)
        dialog.setPositiveButton(R.string.ok) { _, _ ->
            val color = colorHexEt.text.toString()
            if (ColorUtils.toColor(color) != -1) {
                mainColorPref.sharedPreferences!!.edit {
                    putString(mainColorPref.key, color)
                    putInt("theme_main_alpha", alphaSb.value.toInt())
                }
            }
        }
        alphaSb.value = mainColorPref.sharedPreferences!!.getInt("theme_main_alpha", 255).toFloat()
        val hexColor = mainColorPref.sharedPreferences!!.getString(mainColorPref.key, "#212121")
        colorHexEt.setText(hexColor)
        val presetsGv = view.findViewById<GridView>(R.id.presets_gv)
        presetsGv.adapter = HexColorAdapter(context, context.resources.getStringArray(R.array.default_color_values))
        presetsGv.onItemClickListener = OnItemClickListener { adapterView, _, position, _ ->
            colorHexEt.setText(adapterView.getItemAtPosition(position).toString())
            toggleGroup.check(R.id.custom_button)
            viewSwitcher.showNext()
        }
        view.findViewById<View>(R.id.custom_button).setOnClickListener { viewSwitcher.displayedChild = 0 }
        view.findViewById<View>(R.id.presets_button).setOnClickListener { viewSwitcher.displayedChild = 1 }
        dialog.setView(view)
        dialog.show()
    }

    // ── Auto Resolution Picker ────────────────────────────────────────────────
    private fun showAutoResolutionPicker() {
        val prefs   = preferenceManager.sharedPreferences!!
        val presets = arrayOf(
            "Default (reset)",
            "1920 × 1080  (16:9 FHD)",
            "2560 × 1440  (16:9 QHD)",
            "1280 × 720   (16:9 HD)",
            "1600 × 900   (16:9)",
            "Custom…"
        )
        val presetValues = arrayOf("", "1920x1080", "2560x1440", "1280x720", "1600x900", null)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.desktop_res_value_title)
            .setItems(presets) { _, which ->
                when {
                    presetValues[which] == null -> showCustomResInput(prefs)
                    else -> {
                        prefs.edit().putString("desktop_res_value", presetValues[which]).apply()
                        updateResolutionSummary()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showCustomResInput(prefs: android.content.SharedPreferences) {
        val et = android.widget.EditText(requireContext()).apply {
            hint = "e.g. 1920x1080"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            val saved = prefs.getString("desktop_res_value", "")
            if (!saved.isNullOrEmpty()) setText(saved)
            setPadding(48, 24, 48, 0)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.desktop_res_value_title)
            .setView(et)
            .setPositiveButton(R.string.ok) { _, _ ->
                val txt = et.text.toString().trim()
                prefs.edit().putString("desktop_res_value", txt).apply()
                updateResolutionSummary()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateResolutionSummary() {
        val prefs   = preferenceManager.sharedPreferences!!
        val saved   = prefs.getString("desktop_res_value", "").orEmpty()
        val pref    = findPreference<androidx.preference.Preference>("desktop_res_value") ?: return
        pref.summary = if (saved.isBlank())
            getString(R.string.desktop_res_value_summary)
        else
            saved
    }

    private fun updateIconScaleSummary() {
        val pref = findPreference<SliderPreference>("icon_scale_percent") ?: return
        val percent = com.youki.dex.utils.IconScaleUtils.getIconScalePercent(requireContext())
        pref.summary = "$percent%"
    }

    private fun updateFontScaleSummary() {
        val pref = findPreference<SliderPreference>("label_font_scale_percent") ?: return
        val percent = com.youki.dex.utils.IconScaleUtils.getFontScalePercent(requireContext())
        pref.summary = "$percent%"
    }

    private fun updateAppFontScaleSummary() {
        val pref = findPreference<SliderPreference>("app_font_scale_percent") ?: return
        val percent = com.youki.dex.utils.AppFontScaleUtils.getFontScalePercent(requireContext())
        pref.summary = "$percent%"
    }

    private fun updateCustomBadgeSummary() {
        val pref = findPreference<Preference>("custom_badge_text") ?: return
        val raw = com.youki.dex.utils.IconScaleUtils.getCustomBadgeRawText(requireContext())
        pref.summary = if (raw.isEmpty())
            getString(R.string.custom_badge_text_summary)
        else
            com.youki.dex.utils.IconScaleUtils.getLocalizedBadgeDisplayText(requireContext())
    }

    // ── Custom badge input dialog ──────────────────────────────────────────
    // Supports a number from 1 to 3 digits only, and always saves it with
    // Latin numerals (0-9) regardless of the keyboard's language, then shows
    // it to the user translated into their current language's numeral format
    // (via IconScaleUtils.getLocalizedBadgeDisplayText).
    private fun showCustomBadgeInput() {
        val ctx = requireContext()
        val et = android.widget.EditText(ctx).apply {
            hint = getString(R.string.custom_badge_text_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(3))
            setText(com.youki.dex.utils.IconScaleUtils.getCustomBadgeRawText(ctx))
            setPadding(48, 24, 48, 0)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.custom_badge_text_title)
            .setView(et)
            .setPositiveButton(R.string.ok) { _, _ ->
                val txt = et.text.toString().trim()
                if (txt.isNotEmpty() && txt.toIntOrNull() == null) {
                    Snackbar.make(requireView(), getString(R.string.custom_badge_invalid), Snackbar.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                com.youki.dex.utils.IconScaleUtils.setCustomBadgeRawText(ctx, txt)
                updateCustomBadgeSummary()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    internal class HexColorAdapter(private val context: Context, colors: Array<String>) : ArrayAdapter<String>(context, R.layout.color_entry, colors) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            var convertView = convertView
            if (convertView == null) convertView = LayoutInflater.from(context).inflate(R.layout.color_entry, null)
            convertView!!.findViewById<View>(R.id.color_entry_iv).background
                    .setColorFilter(getItem(position)!!.toColorInt(), PorterDuff.Mode.SRC_ATOP)
            return convertView
        }
    }
    private fun updateFontSummary() {
        val fontName = FontManager.getArabicFontName(requireContext())
        findPreference<Preference>("custom_font")?.summary =
            fontName ?: getString(R.string.plugin_font_none)
    }

    private fun showFontOptions() {
        val ctx = requireContext()
        val hasFont = FontManager.hasArabicFont(ctx)
        if (!hasFont) {
            launchFontPicker(); return
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.plugin_font))
            .setItems(arrayOf(getString(R.string.plugin_font_add), getString(R.string.plugin_font_remove))) { _, which ->
                when (which) {
                    0 -> launchFontPicker()
                    1 -> {
                        FontManager.clearFont(ctx, FontScript.ARABIC)
                        FontManager.clearFont(ctx, FontScript.LATIN)
                        updateFontSummary()
                        Snackbar.make(requireView(), getString(R.string.plugin_font_removed), Snackbar.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun launchFontPicker() {
        pickFont.launch(
            android.content.Intent(android.content.Intent.ACTION_GET_CONTENT)
                .setType("*/*")
                .addCategory(android.content.Intent.CATEGORY_OPENABLE)
                .putExtra(
                    android.content.Intent.EXTRA_MIME_TYPES,
                    arrayOf("font/ttf", "font/otf", "application/octet-stream", "application/zip")
                )
        )
    }

    override fun onResume() {
        super.onResume()
        updateFontSummary()
        updateIconScaleSummary()
        updateFontScaleSummary()
        updateAppFontScaleSummary()
        updateCustomBadgeSummary()
    }
}