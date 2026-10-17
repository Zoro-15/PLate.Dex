package com.youki.dex.fragments

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.LabelFormatter
import com.youki.dex.R
import com.youki.dex.preferences.SliderPreference
import androidx.core.content.edit

class DockPreferences : PreferenceFragmentCompat() {
    override fun onCreatePreferences(arg0: Bundle?, arg1: String?) {
        setPreferencesFromResource(R.xml.preferences_dock, arg1)

        // Dock position: single tap-to-toggle button (replaces the old
        // 4-way left/right/top/bottom dropdown, per user request — the
        // dock now only ever docks to the top or bottom of the screen).
        // Tapping flips DockPositionUtils' stored value and immediately
        // refreshes the summary so the current position is always visible
        // without needing to open a picker dialog first.
        val dockPositionToggle = findPreference<Preference>("dock_position_toggle")!!
        fun updateDockPositionSummary() {
            val prefs = dockPositionToggle.sharedPreferences ?: return
            val isTop = com.youki.dex.utils.DockPositionUtils.isTop(prefs)
            dockPositionToggle.summary = getString(
                R.string.dock_position_current,
                getString(if (isTop) R.string.dock_position_top else R.string.dock_position_bottom)
            )
        }
        updateDockPositionSummary()
        dockPositionToggle.setOnPreferenceClickListener {
            val prefs = dockPositionToggle.sharedPreferences
            if (prefs != null) {
                com.youki.dex.utils.DockPositionUtils.toggle(prefs)
                updateDockPositionSummary()
            }
            true
        }

        // Dock height slider
        val dockHeight = findPreference<SliderPreference>("dock_height")!!
        dockHeight.setOnDialogShownListener(object : SliderPreference.OnDialogShownListener {
            override fun onDialogShown() {
                val slider = dockHeight.slider ?: return
                slider.isTickVisible = false
                slider.labelBehavior = LabelFormatter.LABEL_GONE
                slider.stepSize = 1f
                // FIX (crash): valueFrom/valueTo must be set before value —
                // MaterialSlider validates the assigned value against the
                // bounds in effect at that moment, so setting value first
                // can accept an out-of-range stored value, then crash once
                // the real bounds are applied. Set bounds first and clamp.
                slider.valueFrom = 44f
                slider.valueTo = 80f
                slider.value =
                    (dockHeight.sharedPreferences?.getString("dock_height", "48")?.toFloatOrNull() ?: 48f)
                        .coerceIn(slider.valueFrom, slider.valueTo)
                slider.addOnChangeListener { _, value, _ ->
                    dockHeight.sharedPreferences?.edit { putString("dock_height", value.toInt().toString()) }
                }
            }
        })

        // QS button size slider
        val qsBtnSize = findPreference<SliderPreference>("qs_btn_size")
        qsBtnSize?.setOnDialogShownListener(object : SliderPreference.OnDialogShownListener {
            override fun onDialogShown() {
                val slider = qsBtnSize.slider ?: return
                slider.isTickVisible = false
                slider.labelBehavior = LabelFormatter.LABEL_GONE
                slider.stepSize = 1f
                // FIX (crash): same valueFrom/valueTo-before-value ordering
                // as dock_height above — set bounds first, then clamp.
                slider.valueFrom = 28f
                slider.valueTo = 52f
                slider.value =
                    (qsBtnSize.sharedPreferences?.getString("qs_btn_size", "38")?.toFloatOrNull() ?: 38f)
                        .coerceIn(slider.valueFrom, slider.valueTo)
                slider.addOnChangeListener { _, value, _ ->
                    qsBtnSize.sharedPreferences?.edit { putString("qs_btn_size", value.toInt().toString()) }
                }
            }
        })

        // Pill transparency slider
        val pillTransparency = findPreference<SliderPreference>("pill_transparency")
        pillTransparency?.setOnDialogShownListener(object : SliderPreference.OnDialogShownListener {
            override fun onDialogShown() {
                val slider = pillTransparency.slider ?: return
                slider.isTickVisible = false
                slider.labelBehavior = LabelFormatter.LABEL_GONE
                slider.stepSize = 0.05f
                slider.valueFrom = 0.1f
                slider.valueTo = 1f
                slider.value =
                    (pillTransparency.sharedPreferences?.getString("pill_transparency", "0.9")?.toFloatOrNull() ?: 0.9f)
                        .coerceIn(slider.valueFrom, slider.valueTo)
                slider.addOnChangeListener { _, value, _ ->
                    pillTransparency.sharedPreferences?.edit { putString("pill_transparency", String.format(java.util.Locale.US, "%.2f", value)) }
                }
            }
        })

        // Pill height slider
        val pillHeight = findPreference<SliderPreference>("pill_height")
        pillHeight?.setOnDialogShownListener(object : SliderPreference.OnDialogShownListener {
            override fun onDialogShown() {
                val slider = pillHeight.slider ?: return
                slider.isTickVisible = false
                slider.labelBehavior = LabelFormatter.LABEL_GONE
                slider.stepSize = 1f
                slider.valueFrom = 22f
                slider.valueTo = 48f
                slider.value =
                    (pillHeight.sharedPreferences?.getString("pill_height", "30")?.toFloatOrNull() ?: 30f)
                        .coerceIn(slider.valueFrom, slider.valueTo)
                slider.addOnChangeListener { _, value, _ ->
                    pillHeight.sharedPreferences?.edit { putString("pill_height", value.toInt().toString()) }
                }
            }
        })

        // Pill time length slider
        val pillTimeLength = findPreference<SliderPreference>("pill_time_length")
        pillTimeLength?.setOnDialogShownListener(object : SliderPreference.OnDialogShownListener {
            override fun onDialogShown() {
                val slider = pillTimeLength.slider ?: return
                slider.isTickVisible = false
                slider.labelBehavior = LabelFormatter.LABEL_GONE
                slider.stepSize = 5f
                slider.valueFrom = 45f
                slider.valueTo = 260f
                slider.value =
                    (pillTimeLength.sharedPreferences?.getString("pill_time_length", "75")?.toFloatOrNull() ?: 75f)
                        .coerceIn(slider.valueFrom, slider.valueTo)
                slider.addOnChangeListener { _, value, _ ->
                    pillTimeLength.sharedPreferences?.edit { putString("pill_time_length", value.toInt().toString()) }
                }
            }
        })

        // Pill battery/status length slider
        val pillBatteryLength = findPreference<SliderPreference>("pill_battery_length")
        pillBatteryLength?.setOnDialogShownListener(object : SliderPreference.OnDialogShownListener {
            override fun onDialogShown() {
                val slider = pillBatteryLength.slider ?: return
                slider.isTickVisible = false
                slider.labelBehavior = LabelFormatter.LABEL_GONE
                slider.stepSize = 5f
                slider.valueFrom = 70f
                slider.valueTo = 220f
                slider.value =
                    (pillBatteryLength.sharedPreferences?.getString("pill_battery_length", "115")?.toFloatOrNull() ?: 115f)
                        .coerceIn(slider.valueFrom, slider.valueTo)
                slider.addOnChangeListener { _, value, _ ->
                    pillBatteryLength.sharedPreferences?.edit { putString("pill_battery_length", value.toInt().toString()) }
                }
            }
        })

        // Pill text size slider
        val pillTextSize = findPreference<SliderPreference>("pill_text_size")
        pillTextSize?.setOnDialogShownListener(object : SliderPreference.OnDialogShownListener {
            override fun onDialogShown() {
                val slider = pillTextSize.slider ?: return
                slider.isTickVisible = false
                slider.labelBehavior = LabelFormatter.LABEL_GONE
                slider.stepSize = 1f
                slider.valueFrom = 8f
                slider.valueTo = 20f
                slider.value =
                    (pillTextSize.sharedPreferences?.getString("pill_text_size", "12")?.toFloatOrNull() ?: 12f)
                        .coerceIn(slider.valueFrom, slider.valueTo)
                slider.addOnChangeListener { _, value, _ ->
                    pillTextSize.sharedPreferences?.edit { putString("pill_text_size", value.toInt().toString()) }
                }
            }
        })

        // Pill text boldness slider
        val pillTextWeight = findPreference<SliderPreference>("pill_text_weight")
        pillTextWeight?.setOnDialogShownListener(object : SliderPreference.OnDialogShownListener {
            override fun onDialogShown() {
                val slider = pillTextWeight.slider ?: return
                slider.isTickVisible = true
                slider.labelBehavior = LabelFormatter.LABEL_GONE
                slider.stepSize = 1f
                slider.valueFrom = 1f
                slider.valueTo = 5f
                slider.value =
                    (pillTextWeight.sharedPreferences?.getString("pill_text_weight", "4")?.toFloatOrNull() ?: 4f)
                        .coerceIn(slider.valueFrom, slider.valueTo)
                slider.addOnChangeListener { _, value, _ ->
                    pillTextWeight.sharedPreferences?.edit { putString("pill_text_weight", value.toInt().toString()) }
                }
            }
        })

        // Pill side edge margin slider
        val pillSideMargin = findPreference<SliderPreference>("pill_side_margin")
        pillSideMargin?.setOnDialogShownListener(object : SliderPreference.OnDialogShownListener {
            override fun onDialogShown() {
                val slider = pillSideMargin.slider ?: return
                slider.isTickVisible = false
                slider.labelBehavior = LabelFormatter.LABEL_GONE
                slider.stepSize = 1f
                slider.valueFrom = 0f
                slider.valueTo = 30f
                slider.value =
                    (pillSideMargin.sharedPreferences?.getString("pill_side_margin", "4")?.toFloatOrNull() ?: 4f)
                        .coerceIn(slider.valueFrom, slider.valueTo)
                slider.addOnChangeListener { _, value, _ ->
                    pillSideMargin.sharedPreferences?.edit { putString("pill_side_margin", value.toInt().toString()) }
                }
            }
        })

        findPreference<Preference>("auto_pin")!!.setOnPreferenceClickListener {
            showAutopinDialog(requireContext())
            false
        }
        // Swipe activation mode removed: hiding the dock left a small
        // (dock_activation_area) overlay window still holding its bounds,
        // which stole touches from whatever was underneath even though
        // nothing was visible there. Handle mode is the only mode now, so
        // "activation_method" and "dock_activation_area" are no longer
        // user-facing — force the pref to "handle" and hide both rows.
        findPreference<Preference>("activation_method")?.sharedPreferences?.edit {
            putString("activation_method", "handle")
        }
        findPreference<Preference>("activation_method")?.isVisible = false
        val activationArea = findPreference<SliderPreference>("dock_activation_area")
        activationArea?.isVisible = false

        val handleOpacity = findPreference<SliderPreference>("handle_opacity")
        handleOpacity!!.isVisible = true
        handleOpacity.setOnDialogShownListener(object : SliderPreference.OnDialogShownListener {
            override fun onDialogShown() {
                val slider = handleOpacity.slider
                slider.isTickVisible = false
                slider.labelBehavior = LabelFormatter.LABEL_GONE
                slider.stepSize = 0.1f
                // FIX (crash): same valueFrom/valueTo-before-value ordering
                // as dock_height above — set bounds first, then clamp.
                slider.valueFrom = 0.2f
                slider.valueTo = 1f
                slider.value =
                    (handleOpacity.sharedPreferences?.getString(handleOpacity.key, "0.5f")
                        ?.toFloatOrNull() ?: 0.5f)
                        .coerceIn(slider.valueFrom, slider.valueTo)
                slider.addOnChangeListener { _, value, _ ->
                    handleOpacity.sharedPreferences!!.edit {
                        putString(handleOpacity.key, value.toString())
                    }
                }
            }
        })
        val handlePosition = findPreference<Preference>("handle_position")
        handlePosition!!.isVisible = true

        val maxRunningApps: EditTextPreference = findPreference("max_running_apps")!!
        maxRunningApps.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_NUMBER
            editText.imeOptions = EditorInfo.IME_ACTION_GO
        }
        maxRunningApps.setOnPreferenceChangeListener { _, newValue ->
            val value = newValue as String
            value.isNotEmpty() && value.toInt() < 50
        }
    }

    private fun showAutopinDialog(context: Context) {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val editor = sharedPreferences.edit()
        val dialogBuilder = MaterialAlertDialogBuilder(context)
        dialogBuilder.setTitle(R.string.auto_pin_summary)
        val view = layoutInflater.inflate(R.layout.dialog_auto_pin, null)
        val startupSwitch = view.findViewById<MaterialSwitch>(R.id.pin_startup_switch)
        val windowedSwitch = view.findViewById<MaterialSwitch>(R.id.pin_window_switch)
        val fullscreenSwitch = view.findViewById<MaterialSwitch>(R.id.unpin_fullscreen_switch)
        startupSwitch.isChecked = sharedPreferences.getBoolean("pin_dock", true)
        windowedSwitch.isChecked = sharedPreferences.getBoolean("auto_pin", true)
        fullscreenSwitch.isChecked = sharedPreferences.getBoolean("auto_unpin", true)
        startupSwitch.setOnCheckedChangeListener { _, checked ->
            editor.putBoolean(
                "pin_dock",
                checked
            ).apply()
        }
        windowedSwitch.setOnCheckedChangeListener { _, checked ->
            editor.putBoolean(
                "auto_pin",
                checked
            ).commit()
        }
        fullscreenSwitch.setOnCheckedChangeListener { _, checked ->
            editor.putBoolean(
                "auto_unpin",
                checked
            ).commit()
        }
        dialogBuilder.setView(view)
        dialogBuilder.setPositiveButton(R.string.ok, null)
        dialogBuilder.show()
    }
}
