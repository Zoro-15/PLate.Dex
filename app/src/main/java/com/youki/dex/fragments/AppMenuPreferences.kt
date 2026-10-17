package com.youki.dex.fragments

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.youki.dex.R
import com.youki.dex.preferences.FileChooserPreference
import com.youki.dex.preferences.SliderPreference
import com.google.android.material.slider.LabelFormatter
import com.youki.dex.utils.DeviceUtils

private const val MENU_REQUEST_CODE = 4
private const val USER_REQUEST_CODE = 5

class AppMenuPreferences : PreferenceFragmentCompat() {
    private lateinit var menuIconPref: FileChooserPreference
    private lateinit var userIconPref: FileChooserPreference
    private var pendingMenuIconPref: FileChooserPreference? = null
    private var pendingUserIconPref: FileChooserPreference? = null

    override fun onCreatePreferences(arg0: Bundle?, arg1: String?) {
        setPreferencesFromResource(R.xml.preferences_app_menu, arg1)
        menuIconPref = findPreference("menu_icon_uri")!!
        menuIconPref.setOnPreferenceClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT)
                .setType("image/*")
                .addCategory(Intent.CATEGORY_OPENABLE)
            startActivityForResult(intent, 9001)
            pendingMenuIconPref = menuIconPref
            false
        }
        userIconPref = findPreference("user_icon_uri")!!
        userIconPref.setOnPreferenceClickListener {
            val intent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT)
                .setType("image/*")
                .addCategory(android.content.Intent.CATEGORY_OPENABLE)
            startActivityForResult(intent, 9002)
            pendingUserIconPref = userIconPref
            false
        }

        // FIX: Always show user icon & name options (not only for system apps).
        // Auto-populate from system if no custom value set yet.
        userIconPref.isVisible = true
        // FIX: key changed from a separate "user_name" to "app_display_name" —
        // the exact key OnboardingPrefs.getDisplayName()/setDisplayName() read
        // and write. Before this, editing the name here and typing a name in
        // onboarding's profile step were two completely independent values;
        // whichever screen last wrote "won", and every other place reading the
        // name (the power menu, user-switcher popup) only ever saw one of the
        // two depending on which internal call it happened to use. Single
        // source of truth now: this field, onboarding, and every display site
        // all read/write "app_display_name".
        val userNamePref = findPreference<EditTextPreference>("app_display_name")!!
        userNamePref.isVisible = true

        // Auto-fill from system if the field is empty
        val ctx = requireContext()
        val sharedPrefs = userNamePref.sharedPreferences
        if (sharedPrefs != null) {
            val savedName = sharedPrefs.getString("app_display_name", "")
            if (savedName.isNullOrEmpty()) {
                val systemName = DeviceUtils.getUserName(ctx)
                if (!systemName.isNullOrEmpty()) {
                    sharedPrefs.edit().putString("app_display_name", systemName).apply()
                    userNamePref.text = systemName
                }
            }
        }

        val heightPreference = findPreference<Preference>("app_menu_height")
        val widthPreference = findPreference<Preference>("app_menu_width")
        val centerPreference = findPreference<Preference>("center_app_menu")
        val fullscreenPreference = findPreference<Preference>("app_menu_fullscreen")
        val sharedPreferences = fullscreenPreference!!.sharedPreferences
        heightPreference!!.isEnabled =
            !sharedPreferences!!.getBoolean(fullscreenPreference.key, false)
        widthPreference!!.isEnabled = !sharedPreferences.getBoolean(fullscreenPreference.key, false)
        centerPreference!!.isEnabled =
            !sharedPreferences.getBoolean(fullscreenPreference.key, false)
        fullscreenPreference.setOnPreferenceChangeListener { _, newValue ->
            val checked = newValue as Boolean
            heightPreference.isEnabled = !checked
            widthPreference.isEnabled = !checked
            centerPreference.isEnabled = !checked
            true
        }

        val menuHeight: EditTextPreference = findPreference("app_menu_height")!!
        menuHeight.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_NUMBER
            editText.imeOptions = EditorInfo.IME_ACTION_GO
        }

        menuHeight.setOnPreferenceChangeListener { _, newValue ->
            val value = newValue as String
            value.isNotEmpty() && value.toInt() > 100
        }

        val menuWidth: EditTextPreference = findPreference("app_menu_width")!!
        menuWidth.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_NUMBER
            editText.imeOptions = EditorInfo.IME_ACTION_GO
        }

        menuWidth.setOnPreferenceChangeListener { _, newValue ->
            val value = newValue as String
            value.isNotEmpty() && value.toInt() > 100
        }

        val columns: EditTextPreference = findPreference("num_columns")!!
        columns.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_NUMBER
            editText.imeOptions = EditorInfo.IME_ACTION_GO
        }

        columns.setOnPreferenceChangeListener { _, newValue ->
            val value = newValue as String
            value.isNotEmpty() && value.toInt() > 1
        }

        userNamePref.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            editText.imeOptions = EditorInfo.IME_ACTION_GO
        }

        val appMenuAlphaPref = findPreference<SliderPreference>("app_menu_opacity")
        val curAppMenuVal = (appMenuAlphaPref?.sharedPreferences?.getString("app_menu_opacity", "230")?.toFloatOrNull() ?: 230f).toInt()
        val initMenuPct = (curAppMenuVal / 255f * 100).toInt()
        appMenuAlphaPref?.summary = "$initMenuPct%"
        appMenuAlphaPref?.setOnDialogShownListener(object : SliderPreference.OnDialogShownListener {
            override fun onDialogShown() {
                val slider = appMenuAlphaPref.slider ?: return
                slider.isTickVisible = false
                slider.labelBehavior = LabelFormatter.LABEL_GONE
                slider.stepSize = 1f
                slider.valueFrom = 0f
                slider.valueTo = 255f
                slider.value =
                    (appMenuAlphaPref.sharedPreferences?.getString("app_menu_opacity", "230")?.toFloatOrNull() ?: 230f)
                        .coerceIn(slider.valueFrom, slider.valueTo)
                slider.addOnChangeListener { _, value, _ ->
                    val pct = (value / 255f * 100).toInt()
                    appMenuAlphaPref.summary = "$pct%"
                    appMenuAlphaPref.sharedPreferences?.edit()?.putString("app_menu_opacity", value.toInt().toString())?.apply()
                }
            }
        })
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK) {
            val openUri = data?.data ?: return
            try {
                requireContext().contentResolver.takePersistableUriPermission(
                    openUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            when (requestCode) {
                MENU_REQUEST_CODE, 9001 -> {
                    val pref = pendingMenuIconPref ?: menuIconPref
                    pref.callChangeListener(openUri.toString())
                    pref.setFile(openUri.toString())
                    pendingMenuIconPref = null
                }
                USER_REQUEST_CODE, 9002 -> {
                    val pref = pendingUserIconPref ?: userIconPref
                    pref.callChangeListener(openUri.toString())
                    pref.setFile(openUri.toString())
                    pendingUserIconPref = null
                }
            }
        }
    }
}
