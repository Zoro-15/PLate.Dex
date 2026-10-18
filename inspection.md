# PLate.Launcher — Codebase Inspection & Remediation Plan

> **Mode:** Ponytail (Ultra) — Focus: Zero fluff, eliminate deadweight, solve root architectural issues with minimal moving parts.

---

## Executive Codebase Audit

| Metric | Measurement / Finding |
|---|---|
| **Core Monolith** | `PerfectServer.kt` is **6,741 lines** (364 KB) containing 3 distinct OS services (`DockService`, `NotificationService`, `DockTileService`) and static state. |
| **Bloat & "Snake Oil"** | `PerformanceFragment.kt` is **3,281 lines** of non-functional/dangerous runtime shell tweaks (`setprop debug.thermal...` to 95°C). |
| **Dead Code** | `DesktopWidgetManager.kt` is 100% disabled via `LauncherActivity.WIDGETS_ENABLED = false` due to crashes. |
| **Redundant Subsystems** | 3 separate privilege runners: `RootManager` (`su`), `ShizukoManager` (AIDL), and `ShellServer`/`ShellManager` (TCP socket on `127.0.0.1:7171`). |
| **Heavy Dependencies** | `play-services-cast-framework` & `mediarouter` add ~3 MB APK bloat for a single dialog that cannot stream screen content. |
| **Production Test Suite** | `com.plate.launcher.qa.*` (7 files, 54 KB) runs an entire sandbox UI testing engine in a `:qa` process inside the user APK. |
| **Binary Assets** | 622 KB of raw binary assets (`default_easter_egg.mp4`, `default_loading_media.gif`) bundled directly in `res/raw/`. |

---

## 4-Stage Remediation Plan

```
Stage 1: Bloat Elimination
 ├── Remove fake performance tweaks & dangerous scripts
 ├── Strip Google Cast SDK & unused dependencies
 ├── Remove QA test suite from production build
 └── Evict unneeded binary media

Stage 2: Architectural Decoupling
 ├── Split PerfectServer.kt into separate service files
 ├── Unify privilege execution (kill ShellServer socket duplicate)
 └── Eliminate volatile static cross-service bus

Stage 3: Performance & Memory Optimization
 ├── Remove blocking main-thread I/O & root commands
 ├── Consolidate ExoPlayer instances into a shared engine
 ├── Optimize icon/app drawer loading with background paging
 └── Audit WindowManager view attach/detach churn

Stage 4: Logic Hardening & Loose Ends
 ├── Resolve or cleanly remove Desktop AppWidget host
 ├── Clean remaining legacy 'Youki' hardcoded paths
 ├── Prevent display rotation/orientation state leaks
 └── Establish strict service lifecycle boundaries
```

---

### Stage 1: Purge Bloat & Extraneous Features
- [x] 1. **Delete `PerformanceFragment.kt` (3,281 lines) & Associated Tabs**:
   - Shell scripts attempting to set read-only properties (`ro.*`) or disable thermal throttling (`setprop debug.thermal...`) deleted.
   - Removed Tab 1 from `PluginsFragment.kt` and updated `MainActivity.kt` routing.
- [x] 2. **Remove Google Play Services Cast Framework**:
   - Dropped `play-services-cast-framework` and `mediarouter` from `app/build.gradle`.
   - Deleted `CastManager.kt`, `CastPickerActivity.kt`, and `CastOptionsProvider.kt`.
   - Cast button in dock mapped to Android's native `Settings.ACTION_CAST_SETTINGS` (zero dependencies, works on all ROMs).
   - Removed Cast components and metadata from `AndroidManifest.xml`.
- [x] 3. **Remove In-APK QA Test Engine (`com.plate.launcher.qa.*`)**:
   - Deleted all 7 files from `qa/` (`QaSandboxActivity`, `QaVirtualDisplayHost`, `QaTestOrchestrator`, etc.).
   - Removed `:qa` process entries from `AndroidManifest.xml`.
   - Removed QA triggers and QA layouts/drawables.
- [x] 4. **Purge Bundled Raw Media & Easter Egg**:
   - Deleted `res/raw/default_easter_egg.mp4` (395 KB) and `res/raw/default_loading_media.gif` (227 KB).
   - Deleted `EasterEggActivity.kt` and removed from `AndroidManifest.xml`.
   - Replaced GIF with `CircularProgressIndicator` in `fragment_workshop.xml`.

---

### Stage 2: Architectural Decoupling & Refactoring
- [x] 1. **Deconstruct `PerfectServer.kt` into Standard Android Components**:
   - `services/DockService.kt`: Accessibility service controlling dock overlay, input handling, and desktop multitasking.
   - `services/NotificationService.kt`: NotificationListenerService handling notifications and status indicators.
   - `services/DockTileService.kt`: Quick Settings tile toggle.
   - Monolithic `PerfectServer.kt` removed from git.
- [x] 2. **Consolidate Privilege Execution into a Single Gateway**:
   - Deleted `ShellServer.kt` and `ShellManager.kt` (redundant TCP localhost socket).
   - Unified privilege execution cleanly across `ShizukoManager.kt` (AIDL) and `RootManager.kt` (`su`).

---

### Stage 3: Performance, Memory & UI Smoothness
- [x] 1. **Eliminate Main-Thread Shell & Disk Execution**:
   - `AdvancedPreferences.kt` root check converted to asynchronous coroutine on `Dispatchers.IO` using `RootManager.isAvailable`.
- [x] 2. **ExoPlayer Resource Management**:
   - `LauncherActivity.kt`: Added `onStop()` release of `wallpaperPlayer` and clear of cached wallpaper path to avoid HW decoder starvation, reattaching cleanly in `onResume()`.
   - `SecondaryDisplayWallpaperPresentation.kt`: Added `onDetachedFromWindow()` and `onStop()` release of player to prevent leaking decoder hardware when displays disconnect.

---

### Stage 4: Polish Logic, Connect Loose Ends & Modernize
- [x] 1. **Resolve Desktop Widgets (`DesktopWidgetManager`)**:
   - Cleanly excised dead `DesktopWidgetManager.kt` and purged disabled listeners and `WIDGETS_ENABLED` flag code from `LauncherActivity.kt`.
- [x] 2. **Eradicate Residual Upstream References**:
   - `WorkshopFragment.kt`: Updated `DOWNLOADS_ROOT` to `"PLateLauncher"`, updated temp file prefixes.
   - `VideoFileStore.kt`: Updated `DOWNLOADS_ROOT` to `"PLateLauncher"` and added legacy fallback scanner.
   - `shortcuts.xml`: Updated `@mipmap/ic_youki` to `@mipmap/ic_launcher`.
- [x] 3. **Display Rotation & Orientation Fixes**:
   - Verified and added `DeviceUtils.freezeRotation(false)` into `AppUtils.stopDexAndLaunchOtherHome` so rotation lock is always released on DEX teardown.

---

## Addon Implementation Progress

- [x] **Addon 1 (Hardware Window Snapping)**: Implemented in [`DockService.onKeyEvent`](file:///c:/Users/ok/Documents/Plate.Launcher/app/src/main/java/com/plate/launcher/services/DockService.kt). Handles `Win/Alt + Arrow` shortcuts for `tiled-left`, `tiled-right`, `maximized`, `standard`, and `Win+Alt+Arrow` for `tiled-top`/`tiled-bottom`.
- [x] **Addon 2 (Multi-Display Resolution & DPI Presets)**: Implemented in [`AdvancedPreferences.kt`](file:///c:/Users/ok/Documents/Plate.Launcher/app/src/main/java/com/plate/launcher/fragments/AdvancedPreferences.kt#L420). Adds multi-display detection and unified 1-tap resolution + DPI desktop presets via Shizuku/Root/Reflection.

