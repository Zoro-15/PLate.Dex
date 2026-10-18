# PLate.Launcher — Bloat Audit & Feature Proposal

> **Skill Policy:** Ponytail (Ultra) — "The best code is the code never written. Question the request: push back on any part the need does not justify."

---

## 1. Bloat & Deadweight Inventory (Status: 100% Purged)

### A. The "Kernel & Thermal Tweaks" Engine (`PerformanceFragment.kt`) ✅ (Deleted)
* **Path:** `app/src/main/java/com/plate/launcher/fragments/PerformanceFragment.kt`
* **Removed:** **3,281 lines** (~135 KB) + 41 KB XML layout (`fragment_performance.xml`).
* **Why it was bloat:** Dangerous scripts attempting to set read-only properties (`ro.*`), killing thermal daemons, and raising thermal limits to 95°C.

### B. Bundled QA Test Runner in Production APK (`com.plate.launcher.qa.*`) ✅ (Deleted)
* **Path:** `app/src/main/java/com/plate/launcher/qa/` (all 7 files)
* **Removed:** ~54 KB source code + `:qa` process entries in `AndroidManifest.xml` + QA layouts and animators.
* **Why it was bloat:** Internal developer integration tests packaged in the production end-user release APK.

### C. Google Play Services Cast Framework & MediaRouter ✅ (Deleted)
* **Dependencies removed:** `com.google.android.gms:play-services-cast-framework:21.4.0` & `androidx.mediarouter:mediarouter:1.7.0`.
* **Classes deleted:** `CastManager.kt`, `CastPickerActivity.kt`, `CastOptionsProvider.kt` in `cast/`.
* **Replacement:** Native zero-dependency `Settings.ACTION_CAST_SETTINGS` intent. Saves ~3 MB DEX and works on de-Googled devices.

### D. Duplicate Localhost TCP Shell Server (`ShellServer.kt` / `ShellManager.kt`) ✅ (Deleted)
* **Path:** `app/src/main/java/com/plate/launcher/server/`
* **Removed:** ~320 lines and local socket on port 7171.
* **Consolidation:** Unified through direct `ShizukoManager` (AIDL) and `RootManager` (`su`).

### E. Dead Desktop Widget Engine (`DesktopWidgetManager.kt`) ✅ (Deleted)
* **Path:** `app/src/main/java/com/plate/launcher/livewallpaper/DesktopWidgetManager.kt`
* **Removed:** 430 lines of dead code + cleaned `WIDGETS_ENABLED = false` code and listeners in `LauncherActivity.kt`.

### F. Bundled Raw Binary Media (`res/raw/`) ✅ (Deleted)
* **Files deleted:** `default_easter_egg.mp4` (395 KB), `default_loading_media.gif` (227 KB), `EasterEggActivity.kt` (367 lines), `activity_easter_egg.xml`, and `UserMediaResolver.kt`.
* **Replacement:** Standard `CircularProgressIndicator` vector components.

### G. In-App Web Scraper Browser & Sources Database (`WorkshopFragment.kt`) ✅ (Deleted)
* **Files deleted:**
  * `WorkshopFragment.kt` (1,391 lines)
  * `WorkshopSourcesActivity.kt` (380 lines)
  * `workshop/data/` (Room/SQLite source models, database, and DAO)
  * Associated layouts: `fragment_workshop.xml`, `fragment_workshop_debug.xml`, `fragment_workshop_detail.xml`, `activity_workshop_sources.xml`, `dialog_add_workshop_source.xml`, `item_workshop_asset.xml`, `item_workshop_source.xml`, `item_downloaded_file.xml`, `item_downloaded_file_grid.xml`.
  * Removed unneeded dependencies from `app/build.gradle`: `androidx.room:room-runtime`, `androidx.room:room-ktx`, `com.squareup.okhttp3:okhttp`, `org.jsoup:jsoup`, `org.jetbrains.kotlinx:kotlinx-serialization-json`.
* **Why it was bloat:** Launchers should consume local media or system file pickers (`ACTION_OPEN_DOCUMENT`). An ad-blocking web browser scraper with custom JS injection is brittle, high-maintenance, and adds excessive dependencies.

---

## 2. Senior Developer Pushback (Ultra Mode)

A launcher's core value proposition is **speed, responsiveness, multitasking, and stability**. 

1. **Stop feature creep:** A desktop launcher does not need to be a kernel manager, an ad-blocking web browser, a root module installer, and a Chromecast media player. Every unrelated subsystem adds background memory consumption, GC pauses, crash surfaces, and maintenance drag.
2. **Embrace the Android platform:** Use standard system contracts. Need casting? Open `Settings.ACTION_CAST_SETTINGS`. Need fonts or wallpapers? Let the user pick from storage using Android's Storage Access Framework (`ACTION_OPEN_DOCUMENT`) or the built-in Wallpaper Gallery. Need root? Use standard Shizuku or `su`, not a custom TCP daemon.
3. **The 6,700-line God file (`PerfectServer.kt`) is technical debt:** Successfully decomposed into dedicated services: `DockService.kt`, `NotificationService.kt`, and `DockTileService.kt`.

---

## 3. Implemented High-Impact Addon Features

The following two core multitasking addons have been implemented directly in the codebase:

### 1. Hardware Window Snapping Shortcuts ✅ (Implemented)
* **Shortcuts:**
  * `Win + Left` / `Alt + Left`: Tile active window to the left 50% (`tiled-left`).
  * `Win + Right` / `Alt + Right`: Tile active window to the right 50% (`tiled-right`).
  * `Win + Up` / `Alt + Up`: Maximize active window (`maximized`).
  * `Win + Down` / `Alt + Down`: Restore active window to standard centered bounds (`standard`).
  * `Win + Alt + Up`: Tile active window to top half (`tiled-top`).
  * `Win + Alt + Down`: Tile active window to bottom half (`tiled-bottom`).
  * Holding `Shift` simultaneously launches a fresh instance in that tiled partition.
* **Code Location:** [`DockService.onKeyEvent`](file:///c:/Users/ok/Documents/Plate.Launcher/app/src/main/java/com/plate/launcher/services/DockService.kt#L1482). Zero external dependencies.

### 2. Native Multi-Display Resolution & DPI Quick-Switch Presets ✅ (Implemented)
* **Capabilities:**
  * Detects all connected screens (`DeviceUtils.getDisplays()`).
  * Prompts for target display selection if external monitors are plugged in (or targets `Display.DEFAULT_DISPLAY` automatically).
  * 1-tap desktop presets setting both Resolution and DPI simultaneously:
    * `1920 × 1080 @ 160 DPI` (FHD Desktop)
    * `2560 × 1440 @ 200 DPI` (QHD Desktop)
    * `3840 × 2160 @ 280 DPI` (4K UHD)
    * `2560 × 1080 @ 160 DPI` (21:9 Ultrawide)
    * `720p @ 140 DPI` (Compact HD)
    * `Default (Reset to Native)`
    * Custom resolution & DPI input (`<w>x<h>@<dpi>`)
* **Execution:** Asynchronous background dispatch (`Dispatchers.IO`) via Shizuku (`wm size/density -d <displayId>`), Root fallback, or reflection for Display 0.
* **Code Location:** [`AdvancedPreferences.kt`](file:///c:/Users/ok/Documents/Plate.Launcher/app/src/main/java/com/plate/launcher/fragments/AdvancedPreferences.kt#L420) under Display Resolution.
