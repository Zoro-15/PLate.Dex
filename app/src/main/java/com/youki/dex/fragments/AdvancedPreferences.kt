package com.youki.dex.fragments

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.LayoutInflater
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.LabelFormatter
import com.youki.dex.R
import com.youki.dex.preferences.SliderPreference
import com.youki.dex.utils.AppUtils
import com.youki.dex.utils.DeviceUtils
import com.youki.dex.utils.Utils
import androidx.core.content.edit

private const val SAVE_REQUEST_CODE = 236
private const val OPEN_REQUEST_CODE = 632

class AdvancedPreferences : PreferenceFragmentCompat() {
    private var rootAvailable = false

    override fun onCreatePreferences(arg0: Bundle?, arg1: String?) {
        setPreferencesFromResource(R.xml.preferences_advanced, arg1)
        // FIX: dead click handler — the "Notification Listener" row itself
        // (see preferences_advanced.xml's own "FIX: صلاحية الإشعارات — يفتح
        // الصفحة الصحيحة مباشرةً" comment right above its <Preference>
        // definition) was written and wired up correctly, but in the wrong
        // fragment: PermissionsPreferences.onPreferenceTreeClick(), a
        // fragment that loads this exact same XML resource but is never
        // actually reachable from any Settings entry point (its only
        // reference anywhere in the codebase is QaTargetRegistry, an
        // internal QA test-target list, not real navigation). Tapping this
        // row on the actual screen the user reaches did nothing. Moved
        // here, onto the fragment that's really shown.
        findPreference<Preference>("notification_listener_permission")?.setOnPreferenceClickListener {
            AppUtils.openSystemSettings(requireContext(), Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            true
        }
        val preferLastDisplay = findPreference<Preference>("prefer_last_display")
        preferLastDisplay!!.isVisible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        var hasWriteSettingsPermission = DeviceUtils.hasWriteSettingsPermission(requireContext())
        preferLastDisplay.setOnPreferenceClickListener {
            showAccessibilityDialog(requireContext())
            true
        }
        findPreference<Preference>("custom_display_size")!!.setOnPreferenceClickListener {
            showDisplaySizeDialog(requireContext())
            true
        }
        findPreference<Preference>("custom_display_resolution")!!.setOnPreferenceClickListener {
            showDisplayResolutionDialog(requireContext())
            true
        }
        findPreference<Preference>("soft_reboot")!!.setOnPreferenceClickListener {
            DeviceUtils.softReboot()
            false
        }

        findPreference<Preference>("share_display_info")!!.setOnPreferenceClickListener {
            val displayInfo = StringBuilder()
            DeviceUtils.getDisplays(requireContext()).forEach { displayInfo.appendLine(it)  }
            startActivity(
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, displayInfo.toString())
                    .setType("text/plain")
            )
            false
        }

        val hideNav = findPreference<SwitchPreferenceCompat>("hide_nav_buttons")
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
        hideNav?.isChecked = prefs.getBoolean("hide_nav_buttons", false)
        val result = DeviceUtils.runAsRoot("cat /system/build.prop")
        rootAvailable = result != "error"

        findPreference<Preference>("root_category")!!.isEnabled = rootAvailable
        if (rootAvailable && !hasWriteSettingsPermission) {
            DeviceUtils.grantPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
            hasWriteSettingsPermission = DeviceUtils.hasWriteSettingsPermission(requireContext())
        }

        val shizuku = com.youki.dex.utils.ShizukoManager.getInstance(requireContext())
        val hasPrivilege = hasWriteSettingsPermission || rootAvailable || shizuku.hasPermission
        findPreference<Preference>("secure_category")?.isEnabled = hasPrivilege
        if (hasPrivilege) {
            findPreference<Preference>("secure_category")!!.isEnabled = true

            findPreference<Preference>("status_icon_blacklist")!!.setOnPreferenceClickListener {
                showIBDialog(requireContext())
                false
            }
            val disableHeadsUp = findPreference<SwitchPreferenceCompat>("disable_heads_up")!!
            disableHeadsUp.isChecked = DeviceUtils.getGlobalSetting(
                requireContext(),
                DeviceUtils.HEADS_UP_ENABLED,
                1
            ) == 0
            disableHeadsUp.setOnPreferenceChangeListener { _, isChecked ->
                DeviceUtils.putGlobalSetting(
                    requireContext(),
                    DeviceUtils.HEADS_UP_ENABLED,
                    if (isChecked as Boolean) 0 else 1
                )
            }
        }

        val hideStatusBar = findPreference<SwitchPreferenceCompat>("hide_status_bar")

        hideNav?.setOnPreferenceChangeListener { _, newValue ->
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
                .edit()
                .putBoolean("hide_nav_buttons", newValue as Boolean)
                .apply()
            DeviceUtils.updateImmersivePolicy(requireContext())
            true
        }

        hideStatusBar?.setOnPreferenceChangeListener { _, newValue ->
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
                .edit()
                .putBoolean("hide_status_bar", newValue as Boolean)
                .apply()
            DeviceUtils.updateImmersivePolicy(requireContext())
            true
        }

            //ROM specific settings
            if (DeviceUtils.isBliss() && Build.VERSION.SDK_INT > Build.VERSION_CODES.R) {
                val disableTaskbar = findPreference<SwitchPreferenceCompat>("disable_taskbar")!!
                disableTaskbar.isVisible = true
                disableTaskbar.isChecked =
                    DeviceUtils.runAsRoot("settings get system ${DeviceUtils.ENABLE_TASKBAR}") == "0"
                disableTaskbar.setOnPreferenceChangeListener { _, isChecked ->
                    return@setOnPreferenceChangeListener DeviceUtils.runAsRoot("settings put system ${DeviceUtils.ENABLE_TASKBAR} ${if (isChecked as Boolean) "0" else "1"}") != "error"
                }
            }

        findPreference<Preference>("backup_preferences")!!.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                startActivityForResult(
                    Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*").putExtra(
                            Intent.EXTRA_TITLE,
                            requireContext().packageName + "_backup_" + Utils.currentDateString + ".sdp"
                        ),
                    SAVE_REQUEST_CODE
                )
                false
            }
        findPreference<Preference>("restore_preferences")!!.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*"),
                    OPEN_REQUEST_CODE
                )
                false
            }
        val dockHeight = findPreference<SliderPreference>("dock_height")!!
        dockHeight.setOnDialogShownListener(object : SliderPreference.OnDialogShownListener {
            override fun onDialogShown() {
                val slider = dockHeight.slider
                slider.isTickVisible = false
                slider.labelBehavior = LabelFormatter.LABEL_GONE
                slider.stepSize = 1f
                // FIX (crash): valueFrom/valueTo must be set BEFORE value.
                // MaterialSlider validates `value` against the current
                // valueFrom/valueTo the moment it's assigned — setting value
                // first (while valueFrom/valueTo still held old/default
                // bounds) let an out-of-range stored value (e.g. 49 from a
                // build where the range used to start lower) get accepted,
                // then crash as soon as valueFrom/valueTo were tightened
                // afterward: IllegalStateException "Slider value(49.0) must
                // be greater or equal to valueFrom(50.0)". Setting the
                // bounds first, then coercing the restored value into that
                // range, means an old/out-of-range saved value is silently
                // clamped instead of crashing the settings screen.
                slider.valueFrom = 50f
                slider.valueTo = 70f
                slider.value =
                    (dockHeight.sharedPreferences?.getString(dockHeight.key, "56")?.toFloatOrNull() ?: 56f)
                        .coerceIn(slider.valueFrom, slider.valueTo)
                slider.addOnChangeListener { _, value, _
                    ->
                    dockHeight.sharedPreferences?.edit {
                        putString(dockHeight.key, value.toInt().toString())
                    }
                }
            }
        })

        val windowScale: EditTextPreference = findPreference("scale_factor")!!
        windowScale.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            editText.imeOptions = EditorInfo.IME_ACTION_GO
        }

        windowScale.setOnPreferenceChangeListener { _, newValue ->
            val value = newValue as String
            value.isNotEmpty() && value.toFloat() > 0
        }

        val iconPadding: EditTextPreference = findPreference("icon_padding")!!
        iconPadding.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_NUMBER
            editText.imeOptions = EditorInfo.IME_ACTION_GO
        }
        iconPadding.setOnPreferenceChangeListener { _, newValue ->
            (newValue as String).isNotEmpty()
        }

        val editorPreviewScale: EditTextPreference? = findPreference("editor_preview_scale")
        editorPreviewScale?.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_NUMBER
            editText.imeOptions = EditorInfo.IME_ACTION_GO
        }
        editorPreviewScale?.setOnPreferenceChangeListener { _, newValue ->
            val v = (newValue as String).toIntOrNull()
            v != null && v in 40..100
        }

        // ── Workshop Sources ─────────────────────────────────────────────
        findPreference<Preference>("manage_workshop_sources")?.setOnPreferenceClickListener {
            startActivity(
                Intent(requireContext(), com.youki.dex.activities.WorkshopSourcesActivity::class.java)
            )
            true
        }

        // ── OTG / UHID mode ───────────────────────────────────────────────
        findPreference<SwitchPreferenceCompat>("uhid_mode_enabled")
            ?.setOnPreferenceChangeListener { _, newValue ->
                val enable = newValue as Boolean
                val uhid   = com.youki.dex.utils.UhidManager.getInstance(requireContext())
                val shizuku = com.youki.dex.utils.ShizukoManager.getInstance(requireContext())
                if (enable) {
                    if (shizuku.hasPermission) {
                        uhid.start(
                            shizuku,
                            onReady = {
                                com.google.android.material.snackbar.Snackbar
                                    .make(requireView(), "OTG HID mode active", 2000).show()
                            },
                            onError = { err ->
                                com.google.android.material.snackbar.Snackbar
                                    .make(requireView(), "UHID error: $err", 4000).show()
                                // revert the switch if startup failed
                                findPreference<SwitchPreferenceCompat>("uhid_mode_enabled")
                                    ?.isChecked = false
                            }
                        )
                    } else {
                        // Fall back to root if Shizuku not available
                        uhid.startAsRoot(
                            onReady = {
                                com.google.android.material.snackbar.Snackbar
                                    .make(requireView(), "OTG HID mode active (root)", 2000).show()
                            },
                            onError = { err ->
                                com.google.android.material.snackbar.Snackbar
                                    .make(requireView(), "UHID error (no Shizuku/root?): $err", 4000).show()
                                findPreference<SwitchPreferenceCompat>("uhid_mode_enabled")
                                    ?.isChecked = false
                            }
                        )
                    }
                } else {
                    uhid.stop()
                    com.google.android.material.snackbar.Snackbar
                        .make(requireView(), "OTG HID mode stopped", 2000).show()
                }
                true
            }

        // "Open Trackpad" — separate from the switch above so the user can
        // jump straight to the touch surface; TrackpadActivity starts UHID
        // itself if the switch above hasn't been flipped on yet.
        findPreference<Preference>("uhid_open_trackpad")?.setOnPreferenceClickListener {
            startActivity(
                Intent(requireContext(), com.youki.dex.activities.TrackpadActivity::class.java)
            )
            true
        }

    }

    // ══════════════════════════════════════════════════════════════════
    //  IWindowManager Reflection — no Shizuku/Shell needed
    // ══════════════════════════════════════════════════════════════════

    private fun getWindowManager(): Any? {
        return try {
            val smClass = Class.forName("android.os.ServiceManager")
            val binder  = smClass.getMethod("checkService", String::class.java)
                .invoke(null, "window") as android.os.IBinder
            val stubClass = Class.forName("android.view.IWindowManager\$Stub")
            stubClass.getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
        } catch (e: Exception) { null }
    }

    private fun applyDisplayDensity(dpi: Int): Boolean {
        return try {
            val wm = getWindowManager() ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                wm.javaClass.getMethod(
                    "setForcedDisplayDensityForUser",
                    Int::class.java, Int::class.java, Int::class.java
                ).invoke(wm, 0, dpi, android.os.Process.myUid() / 100000)
            } else {
                wm.javaClass.getMethod(
                    "setForcedDisplayDensity",
                    Int::class.java, Int::class.java
                ).invoke(wm, 0, dpi)
            }
            true
        } catch (e: Exception) { false }
    }

    private fun clearDisplayDensity(): Boolean {
        return try {
            val wm = getWindowManager() ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                wm.javaClass.getMethod(
                    "clearForcedDisplayDensityForUser",
                    Int::class.java, Int::class.java
                ).invoke(wm, 0, android.os.Process.myUid() / 100000)
            } else {
                wm.javaClass.getMethod("clearForcedDisplayDensity", Int::class.java)
                    .invoke(wm, 0)
            }
            true
        } catch (e: Exception) { false }
    }

    private fun parseResolutionString(text: String): Pair<Int, Int>? {
        val parts = text.split('x', 'X', '×')
        if (parts.size != 2) return null
        val w = parts[0].trim().toIntOrNull() ?: return null
        val h = parts[1].trim().toIntOrNull() ?: return null
        return if (w > 0 && h > 0) w to h else null
    }

    /** FIX (v8→fix7): setForcedDisplaySize expects NATURAL (portrait) coordinates.
     *  Users input PC-style landscape: 1920×1080 (wide × tall).
     *  On a portrait phone, natural width = short side (1080), natural height = long side (1920).
     *  Passing w=1920 inverts the display. Fix: always pass minOf(w,h) as naturalW, maxOf as naturalH.
     */
    private fun applyDisplaySize(width: Int, height: Int): Boolean {
        return try {
            val wm = getWindowManager() ?: return false
            val (naturalW, naturalH) = minOf(width, height) to maxOf(width, height)
            wm.javaClass.getMethod(
                "setForcedDisplaySize",
                Int::class.java, Int::class.java, Int::class.java
            ).invoke(wm, 0, naturalW, naturalH)
            true
        } catch (e: Exception) { false }
    }

    private fun clearDisplaySize(): Boolean {
        return try {
            val wm = getWindowManager() ?: return false
            wm.javaClass.getMethod("clearForcedDisplaySize", Int::class.java).invoke(wm, 0)
            true
        } catch (e: Exception) { false }
    }

    // ── DPI Dialog ────────────────────────────────────────────────────
    private fun showDisplaySizeDialog(context: Context) {
        val dialog = MaterialAlertDialogBuilder(context)
        dialog.setTitle(R.string.custom_display_size_title)
        val view      = LayoutInflater.from(context).inflate(R.layout.dialog_display_size, null)
        val contentEt = view.findViewById<EditText>(R.id.display_size_et)
        contentEt.setText(
            DeviceUtils.getSecureSetting(context, DeviceUtils.DISPLAY_SIZE, "").let {
                if (it.isEmpty()) "" else it
            }
        )
        dialog.setPositiveButton(R.string.ok) { _, _ ->
            val value = contentEt.text.toString().trim()
            if (value.isEmpty() || value == "0") {
                val ok = clearDisplayDensity()
                android.widget.Toast.makeText(
                    context,
                    if (ok) "Display density reset!" else "Failed to reset density",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } else {
                val dpi = value.toIntOrNull()
                if (dpi != null && dpi > 0) {
                    val ok = applyDisplayDensity(dpi)
                    android.widget.Toast.makeText(
                        context,
                        if (ok) "Display density set to $dpi DPI!" else "Failed — check WRITE_SECURE_SETTINGS",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        dialog.setNegativeButton(getString(R.string.cancel), null)
        dialog.setView(view)
        dialog.show()
    }

    // ── Resolution Dialog (e.g. 1920×1080 / 16:9) ────────────────────
    private fun showDisplayResolutionDialog(context: Context) {
        val presets = arrayOf(
            "Default (reset)",
            "1920 × 1080  (16:9 FHD)",
            "2560 × 1440  (16:9 QHD)",
            "1280 × 720   (16:9 HD)",
            "1600 × 900   (16:9)",
            "Custom…"
        )
        val presetValues = arrayOf(
            null,
            Pair(1920, 1080),
            Pair(2560, 1440),
            Pair(1280, 720),
            Pair(1600, 900),
            null  // custom
        )

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.custom_display_resolution_title)
            .setItems(presets) { _, which ->
                when {
                    which == 0 -> {
                        val ok = clearDisplaySize()
                        android.widget.Toast.makeText(
                            context,
                            if (ok) "Resolution reset to default!" else "Failed to reset resolution",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    which == presets.size - 1 -> showCustomResolutionDialog(context)
                    else -> {
                        val (w, h) = presetValues[which]!!
                        val ok = applyDisplaySize(w, h)
                        android.widget.Toast.makeText(
                            context,
                            if (ok) "Resolution set to ${w}×${h}!" else "Failed — check WRITE_SECURE_SETTINGS",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showCustomResolutionDialog(context: Context) {
        val view    = LayoutInflater.from(context).inflate(R.layout.dialog_display_size, null)
        val inputEt = view.findViewById<EditText>(R.id.display_size_et)
        inputEt.hint = "e.g. 1920x1080"
        inputEt.inputType = android.text.InputType.TYPE_CLASS_TEXT

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.custom_display_resolution_title)
            .setView(view)
            .setPositiveButton(R.string.ok) { _, _ ->
                val text = inputEt.text.toString().trim()
                val parsed = parseResolutionString(text)
                if (parsed != null) {
                    val (w, h) = parsed
                    val ok = applyDisplaySize(w, h)
                    android.widget.Toast.makeText(
                        context,
                        if (ok) "Resolution set to ${w}×${h}!" else "Failed — check WRITE_SECURE_SETTINGS",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }
                android.widget.Toast.makeText(
                    context, "Invalid format. Use: 1920x1080", android.widget.Toast.LENGTH_LONG
                ).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showIBDialog(context: Context) {
        val dialog = MaterialAlertDialogBuilder(context)
        dialog.setTitle(R.string.icon_blacklist)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_icon_blacklist, null)
        val contentEt = view.findViewById<EditText>(R.id.icon_blacklist_et)
        contentEt.setText(DeviceUtils.getSecureSetting(context, DeviceUtils.ICON_BLACKLIST, ""))
        dialog.setPositiveButton(R.string.ok) { _, _ ->
            DeviceUtils.putSecureSetting(
                context,
                DeviceUtils.ICON_BLACKLIST, contentEt.text.toString()
            )
        }
        dialog.setNegativeButton(getString(R.string.cancel), null)
        dialog.setView(view)
        dialog.show()
    }

    private fun showRebootDialog(context: Context, softReboot: Boolean) {
        val dialog = MaterialAlertDialogBuilder(context)
        dialog.setTitle(getString(R.string.reboot_required_title))
        dialog.setMessage(getString(R.string.reboot_required_text))
        dialog.setPositiveButton(getString(R.string.ok)) { _, _ ->
            if (softReboot) DeviceUtils.softReboot() else DeviceUtils.reboot()
        }
        dialog.setNegativeButton(getString(R.string.cancel), null)
        dialog.show()
    }

    private fun showAccessibilityDialog(context: Context) {
        val dialog = MaterialAlertDialogBuilder(context)
        dialog.setTitle(R.string.restart)
        dialog.setMessage(R.string.restart_accessibility)
        dialog.setNegativeButton(getString(R.string.cancel), null)
        dialog.setPositiveButton(getString(R.string.restart)) { _, _ ->
            // FIX: used to send the user to Android's own Accessibility Settings
            // screen to manually toggle the service off/on — leaving that screen
            // and returning to LauncherActivity was what triggered the crash /
            // "please enable" bounce-back loop. Now handled entirely in-app via
            // the same enableService()/disableService() pair LauncherActivity
            // already uses on first launch — no navigation away from the app.
            DeviceUtils.restartServiceProgrammatically(context)
        }
        dialog.show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        if (resultCode == Activity.RESULT_OK) {
            val data = intent?.data ?: return
            val appContext = context?.applicationContext ?: return
            // Gap 35: I/O on the IO thread — not the UI thread
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                if (requestCode == SAVE_REQUEST_CODE)
                    Utils.backupPreferences(appContext, data)
                else if (requestCode == OPEN_REQUEST_CODE)
                    Utils.restorePreferences(appContext, data)
            }
        }
    }
}