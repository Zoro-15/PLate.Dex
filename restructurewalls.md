# Context: In-Desktop Wallpaper Architecture

- **Root Failure:** Current wallpaper engine runs in an isolated `:wallpaper` process; applying wallpapers fails because in-memory `SharedFlow` pulses never cross the process boundary.
- **Image Failure:** Static images fail because `ContentResolver.openInputStream()` rejects `file://` URIs under modern Scoped Storage, returning null Bitmaps.
- **Over-Engineering:** 600+ lines of OpenGL ES shaders, custom EGL threads, and multi-process sync were added to emulate a desktop background that Android already isolates.
- **Approach 4 Fix:** Decouple PLate.Dex from Android's system `WallpaperService`. Render wallpapers directly on an internal desktop canvas inside `LauncherActivity`.
- **Impact:** Eliminates ~800 lines of brittle GL/IPC boilerplate, drops the separate process, bypasses all Android system confirmation popups, and enables instant media switching.

# Restructure Plan (Approach 4)

## Phase 1: In-Desktop Canvas & Storage (~60 LOC)
- Add a background layer (`ImageView` + `TextureView`) to `activity_launcher.xml` behind `desktop_icons_container`.
- Persist active wallpaper path and media type (`IMAGE` vs `VIDEO`) in default `SharedPreferences`.
- In `LauncherActivity`, attach media on start/resume: load images directly and loop video via a single ExoPlayer instance.

## Phase 2: Direct Apply Flow (~40 LOC)
- In `GalleryActivity` and `EditorActivity`, replace `requestSetLiveWallpaper()` with direct preference persistence.
- Dispatch a local event or reload call on apply so the desktop canvas swaps media instantly without system prompts.

## Phase 3: Purge Redundant Engine (-800+ LOC)
- Remove `YoukiGLWallpaperService` and `android:process=":wallpaper"` from `AndroidManifest.xml`.
- Delete `WallpaperGLRenderer.kt`, `WallpaperGLEngine.kt`, `GLProgram.kt`, `Shaders.kt`, `Quad.kt`, and `TransformMatrix.kt`.
- Retain file import logic (`VideoFileStore.kt`) and management UI (`GalleryActivity`, `EditorActivity`).
