# Agent Handover: PLate.Launcher Codebase Cleanup & Refactoring

> **Mode:** Ponytail (Ultra) — Smallest working changes, zero bloat, push back on unnecessary features, 100% backward compatibility for `.sdp` preference backups.

---

## 1. Summary of Completed Remediation (Stages 1 – 4)

All 4 stages from [inspection.md](file:///c:/Users/ok/Documents/Plate.Launcher/inspection.md) and [bloat.md](file:///c:/Users/ok/Documents/Plate.Launcher/bloat.md) have been implemented and verified:

### Stage 1: Purge Bloat & Extraneous Modules (Completed)
- **Deleted `PerformanceFragment.kt` (3,281 lines)**: Removed ineffective/dangerous thermal and build.prop shell tweaks. Simplified `PluginsFragment.kt` to directly extend `PluginStoreFragment`. Updated `MainActivity.kt` tab routing.
- **Evicted Google Play Services Cast SDK**: Removed `play-services-cast-framework` and `mediarouter` from `app/build.gradle`. Deleted `com/plate/launcher/cast/` (all 3 files). Mapped Cast button in dock to native `Settings.ACTION_CAST_SETTINGS` (zero APK bloat, works everywhere). Removed Cast elements from `AndroidManifest.xml`.
- **Stripped QA Engine**: Deleted all 7 files in `com/plate/launcher/qa/`. Removed `:qa` sandbox activities from `AndroidManifest.xml`. Removed QA triggers from `BetaPreferences.kt` and `preferences_beta.xml`. Deleted QA layout, animators, and drawables.
- **Purged Raw Binary Media & Easter Egg**: Deleted `default_easter_egg.mp4` (395 KB) and `default_loading_media.gif` (227 KB). Deleted `EasterEggActivity.kt`, its layout, and `UserMediaResolver.kt`. Replaced loading GIF in `fragment_workshop.xml` with `CircularProgressIndicator`.
- **Excised In-App Web Scraper & Sources Database**: Deleted `WorkshopFragment.kt` (1,391 lines), `WorkshopSourcesActivity.kt` (380 lines), SQLite database package `workshop/data/`, and all 9 associated XML layouts. Dropped unused dependencies from `app/build.gradle`: `androidx.room` runtime & ktx, `com.squareup.okhttp3:okhttp`, `org.jsoup:jsoup`, and `org.jetbrains.kotlinx:kotlinx-serialization-json`. Removed `WorkshopFragment` and source management from settings.

### Stage 2: Architectural Decoupling & Service Splitting (Completed)
- **Deconstructed `PerfectServer.kt` Monolith (6,742 lines)**: Split into 3 focused, standard Android service files:
  - [`DockService.kt`](file:///c:/Users/ok/Documents/Plate.Launcher/app/src/main/java/com/plate/launcher/services/DockService.kt): Accessibility service for dock overlay, window management, and hardware key shortcuts.
  - [`NotificationService.kt`](file:///c:/Users/ok/Documents/Plate.Launcher/app/src/main/java/com/plate/launcher/services/NotificationService.kt): Notification listener and quick settings panels.
  - [`DockTileService.kt`](file:///c:/Users/ok/Documents/Plate.Launcher/app/src/main/java/com/plate/launcher/services/DockTileService.kt): Quick Settings tile toggle.
  - Monolithic `PerfectServer.kt` removed from git tracking.
- **Privilege Consolidation**: Deleted redundant TCP socket server (`ShellServer.kt` & `ShellManager.kt`). Unified privileged execution through direct `ShizukoManager` (AIDL) and `RootManager` (`su`).

### Stage 3: Performance & Memory Optimization (Completed)
- **Async Root Checks**: Moved blocking UI-thread `build.prop` inspection in `AdvancedPreferences.kt` to an asynchronous coroutine on `Dispatchers.IO` using `RootManager.isAvailable`.
- **ExoPlayer Lifecycle Management**:
  - `LauncherActivity.kt`: Added `onStop()` to release `wallpaperPlayer` and clear cache when stopped in background, reattaching cleanly in `onResume()`. Prevents HW video decoder exhaustion.
  - `SecondaryDisplayWallpaperPresentation.kt`: Added `onDetachedFromWindow()` and `onStop()` release to guarantee video player and surfaces are freed when external displays disconnect.

### Stage 4: Logic Hardening & Loose Ends (Completed)
- **Excised Desktop Widgets**: Deleted dead `DesktopWidgetManager.kt` and stripped disabled `WIDGETS_ENABLED = false` listeners from `LauncherActivity.kt`.
- **Cleaned Residual Paths & Branding**:
  - `VideoFileStore.kt`: Updated `DOWNLOADS_ROOT` to `"PLateLauncher"` with fallback scanning of legacy `"YoukiDex"` folder so users keep existing wallpapers.
  - `shortcuts.xml`: Changed `@mipmap/ic_youki` to `@mipmap/ic_launcher`.
- **Display Rotation Teardown**: Verified and reinforced `DeviceUtils.freezeRotation(false)` on all DEX teardown paths in `AppUtils.stopDexAndLaunchOtherHome`.

---

## 2. Implemented Addons

- **Addon 1 (Hardware Window Snapping)**:
  - Location: [`DockService.onKeyEvent`](file:///c:/Users/ok/Documents/Plate.Launcher/app/src/main/java/com/plate/launcher/services/DockService.kt#L1482)
  - Shortcuts: `Win/Alt + Arrow` shortcuts for `tiled-left`, `tiled-right`, `maximized`, `standard`, and `Win+Alt+Arrow` for `tiled-top`/`tiled-bottom`.
- **Addon 2 (Multi-Display Resolution & DPI Presets)**:
  - Location: [`AdvancedPreferences.kt`](file:///c:/Users/ok/Documents/Plate.Launcher/app/src/main/java/com/plate/launcher/fragments/AdvancedPreferences.kt#L420)
  - Features: Target display picker (Display 0 vs. External) + unified 1-tap desktop presets (`1080p@160DPI`, `1440p@200DPI`, `4K@280DPI`, `21:9@160DPI`, `720p@140DPI`, Native Reset) running asynchronously on `Dispatchers.IO` via Shizuku (`wm size/density -d <id>`), Root fallback, or reflection.

---

## 3. Invariant Preserved

- **SharedPreferences Compatibility**: All existing keys (`enable_pill_time`, `dock_background_alpha`, `swap_nav_pin`, `nav_long_actions`, `app_menu_columns`, `pill_height`, `pill_transparency`, etc.) remain identical and untampered, ensuring 100% backward compatibility for `.sdp` backups.
