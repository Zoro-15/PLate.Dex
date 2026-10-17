# PLate.Launcher Migration Guide (Youki DEX → PLate.Launcher)

> **Context for Agent:** You are executing the full refactoring from `com.youki.dex` / "Youki DEX" to `com.plate.launcher` / "PLate.Launcher". Follow `SKILL.md` (lazy senior dev mode: exact necessary changes, zero fluff, zero breaking regressions). Execute this in two distinct phases.

---

## ⚠️ Crucial Rule: Configuration Backup Backward Compatibility

Users who backed up their preferences in Youki DEX (`.sdp` files) **MUST** be able to import their configuration directly into PLate.Launcher without errors or missing settings.

1. **Do NOT touch or rename existing SharedPreferences keys.**
   - All keys stored in XML preferences and code (e.g., `enable_pill_time`, `dock_background_alpha`, `swap_nav_pin`, `nav_long_actions`, `app_menu_columns`, `pill_height`, `pill_transparency`, etc.) must remain character-for-character identical.
   - The `.sdp` backup format in `Utils.kt` operates on raw key-value lines (`$type|||$key|||$value`). As long as preference keys are unchanged, restoring an old `.sdp` restores every setting.
2. **Handle File Naming in Restore:**
   - Old backups are named `com.youki.dex_backup_YYYY-MM-DD_HH-mm-ss.sdp`.
   - New backups will be named `com.plate.launcher_backup_YYYY-MM-DD_HH-mm-ss.sdp`.
   - `AdvancedPreferences.kt` uses `Intent.ACTION_OPEN_DOCUMENT` with mime type `*/*`. Ensure `restorePreferences` accepts any valid `.sdp` stream regardless of whether the file name starts with `com.youki.dex` or `com.plate.launcher`.

---

# Phase 1: Codebase & Package Architecture Migration

This phase migrates the package identity, directory tree, build configuration, and class references.

### 1.1 Directory Relocation (Preserve Git History)
Use `git mv` so Git retains the complete history for all 100+ files:
```powershell
New-Item -ItemType Directory -Path "app\src\main\java\com\plate\launcher" -Force
git mv app/src/main/java/com/youki/dex/* app/src/main/java/com/plate/launcher/
Remove-Item -Path "app\src\main\java\com\youki" -Recurse -Force
```

### 1.2 Gradle Configuration (`app/build.gradle`)
Update namespace and application ID:
```groovy
// In android { ... }
namespace "com.plate.launcher"

defaultConfig {
    applicationId "com.plate.launcher"
    // keep other configs intact
}

applicationVariants.all { variant ->
    variant.outputs.all {
        outputFileName = "PLateLauncher-v${variant.versionName}-${variant.buildType.name}.apk"
    }
}
```

### 1.3 Batch Package & Import Replacement in Source Code
Across all files in `app/src/main/java/com/plate/launcher/**`:
- Replace `package com.youki.dex` → `package com.plate.launcher`
- Replace `import com.youki.dex.` → `import com.plate.launcher.`
- Replace generated R-class imports: `com.youki.dex.R` → `com.plate.launcher.R`

### 1.4 XML Resource Class References (CRITICAL)
Custom Android Views and Preferences in XML do NOT automatically rename when Kotlin files move. You must update the fully-qualified class names:
1. **Preferences XML (`app/src/main/res/xml/preferences_*.xml`):**
   - `<com.youki.dex.preferences.SliderPreference` → `<com.plate.launcher.preferences.SliderPreference`
   - `<com.youki.dex.preferences.NavActionChooserPreference` → `<com.plate.launcher.preferences.NavActionChooserPreference`
   - `<com.youki.dex.preferences.AppChooserPreference` → `<com.plate.launcher.preferences.AppChooserPreference`
   - `<com.youki.dex.preferences.AppChooserMultiplePreference` → `<com.plate.launcher.preferences.AppChooserMultiplePreference`
   - `<com.youki.dex.preferences.SoundFilePreference` → `<com.plate.launcher.preferences.SoundFilePreference`
   - `<com.youki.dex.preferences.FileChooserPreference` → `<com.plate.launcher.preferences.FileChooserPreference`
   - `<com.youki.dex.preferences.PreferenceHeader` → `<com.plate.launcher.preferences.PreferenceHeader`
   - Fragment attributes in `preferences_main.xml`:
     `android:fragment="com.youki.dex.fragments.*"` → `android:fragment="com.plate.launcher.fragments.*"`
     `app:fragment="com.youki.dex.fragments.*"` → `app:fragment="com.plate.launcher.fragments.*"`
2. **Layouts (`app/src/main/res/layout/*.xml`):**
   - `<com.youki.dex.widgets.HoverInterceptorLayout` → `<com.plate.launcher.widgets.HoverInterceptorLayout` (in `dock.xml`)
   - `<com.youki.dex.widgets.LayoutToggleButton` → `<com.plate.launcher.widgets.LayoutToggleButton` (in `fragment_workshop.xml`)
   - `<com.youki.dex.livewallpaper.ui.editor.FreeGestureOverlay` → `<com.plate.launcher.livewallpaper.ui.editor.FreeGestureOverlay`
   - `<com.youki.dex.livewallpaper.ui.editor.AspectRatioFrameLayout` → `<com.plate.launcher.livewallpaper.ui.editor.AspectRatioFrameLayout`
3. **Shortcuts (`app/src/main/res/xml/shortcuts.xml`):**
   - `android:targetPackage="com.plate.launcher"`
   - `android:targetClass="com.plate.launcher.activities.ShortcutLauncherActivity"`

### 1.5 Manifest & Proguard
1. **`app/src/main/AndroidManifest.xml`:**
   - Provider authorities: update `com.youki.dex.fileprovider` → `com.plate.launcher.fileprovider`
   - Cast metadata: `android:value="com.plate.launcher.cast.CastOptionsProvider"`
   - Remove any hardcoded `"com.youki.dex"` references.
2. **`app/proguard-rules.pro`:**
   - Update keep rules from `com.youki.dex.**` to `com.plate.launcher.**`.

### 1.6 Phase 1 Verification
Run check:
```powershell
git grep "com.youki.dex"
```
Output should be completely empty (0 results).

---

# Phase 2: Branding, System Permissions & Backward Compatibility

This phase handles user-facing strings, system-level ADB/Shizuku commands, and verifies backup restoration.

### 2.1 User-Facing Strings & Branding (`app/src/main/res/values/strings.xml`)
Update brand labels while keeping XML string keys identical:
- `<string name="app_name">PLate.Launcher</string>`
- `shell_terminal`: "PLate Shell Terminal"
- `default_launcher_desc`: "Set PLate.Launcher as your default home screen launcher so the desktop loads automatically."
- `youki_dex_its_free_and_open_`: "PLate.Launcher is free and open source. Contribute on GitHub"
- `start_message`: "PLate.Launcher started"
- `enable_access_help`: "Find and enable PLate.Launcher service"
- `youki_sounds`: "PLate Sounds"
- `plugins_subtitle`: "Extend PLate.Launcher capabilities"
- `lw_service_label`: "PLate.Launcher Live Wallpaper"
- `onboarding_welcome_title`: "Welcome to PLate.Launcher"
- `onboarding_support_title`: "Enjoying PLate.Launcher?"
- `trackpad_status_accessibility_needed`: "Enable PLate.Launcher's accessibility service first (Settings)"
- `notification_access_message`: "PLate.Launcher needs notification access to work correctly..."
- `accessibility_service_message`: "PLate.Launcher needs the Accessibility service to work..."

### 2.2 System ADB & Permission Strings
Update package references in user-facing setup instructions:
- In `strings.xml` (`write_secure_desc`):
  `pm grant com.plate.launcher android.permission.WRITE_SECURE_SETTINGS`
- In `ShizukoManager.kt` / `RootManager.kt` / `AppUtils.kt`:
  Ensure any self-package references use `context.packageName` dynamically rather than hardcoded strings.

### 2.3 Verify Backup / Restore Compatibility
Inspect `Utils.kt`:
- Verify `backupPreferences()` writes clean `$type|||$key|||$value` lines using `context.packageName + "_backup_" + ... + ".sdp"`.
- Verify `restorePreferences()` continues parsing any incoming Uri with `BufferedReader` splitting by `|||` and writing to `PreferenceManager.getDefaultSharedPreferences(context)`.
- Verify no key renaming occurred between old version and new version.

### 2.4 Final Sanity Audit & Git Check
1. Execute:
   ```powershell
   git grep -i "youki"
   ```
   Filter out upstream author credits (e.g. mrYouki in About/License), and ensure all app code, namespaces, and package identifiers are clean.
2. Stage and commit:
   ```powershell
   git status
   ```
