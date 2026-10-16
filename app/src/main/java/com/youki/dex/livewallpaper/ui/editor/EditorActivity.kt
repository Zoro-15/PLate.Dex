package com.youki.dex.livewallpaper.ui.editor

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.Surface
import androidx.activity.viewModels
import androidx.fragment.app.commitNow
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import coil.ImageLoader
import coil.decode.VideoFrameDecoder
import com.google.android.material.snackbar.Snackbar
import com.jaredrummler.android.colorpicker.ColorPickerDialogListener
import com.youki.dex.R
import com.youki.dex.livewallpaper.data.WallpaperConfig
import com.youki.dex.livewallpaper.engine.VideoEngine
import com.youki.dex.livewallpaper.service.YoukiGLWallpaperService
import com.youki.dex.livewallpaper.ui.gallery.GalleryAdapter
import com.youki.dex.livewallpaper.ui.gallery.GalleryViewModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * EditorActivity — container and customization screen (Phase 6, Part 2).
 *
 * Flow:
 *   1. Receives videoUri from GalleryActivity.
 *   2. Loads/creates a WallpaperConfig via EditorViewModel.
 *   3. Runs [VideoEngine] locally (separate from the actual wallpaper service)
 *      and passes it to the same [WallpaperGLRenderer] used by the Service.
 *   4. Any slider change is reflected immediately in the preview (the exact
 *      same rendering engine).
 *   5. The "Apply" button saves to Room and activates this video as the
 *      actual live wallpaper.
 *
 * [ColorPickerDialogListener] is implemented here specifically (not in
 * UnifiedEditorFragment) because com.jaredrummler.android.colorpicker.ColorPickerDialog
 * internally looks up the listener via `getActivity() as? ColorPickerDialogListener`
 * (confirmed in the library's own source), not via the parent fragment — if it
 * were only implemented on the fragment, the library would throw an
 * IllegalStateException when the dialog is actually opened.
 *
 * ── ADAPTIVE LAYOUT (single XML, two independent groups) ──
 * activity_lw_editor.xml contains BOTH original stable designs at once,
 * each in its own top-level FrameLayout (ed_portrait_group / ed_landscape_group),
 * both starting as GONE. applyOrientationLayout() decides which one is VISIBLE
 * by comparing the actual current width vs height of ed_root — taller than
 * wide → portrait group (fullscreen preview + drag sheet), wider than tall →
 * landscape group (video grid | preview+settings column) — exactly the same
 * rule Android itself used to pick layout/ vs layout-land/, just evaluated
 * live so it reacts instantly to real rotation, folding, or multi-window
 * resizing without recreating the Activity.
 *
 * FIX (glitch/freeze opening the Editor, esp. on mid/low-end devices): each
 * group used to declare its OWN WallpaperPreviewView directly in the XML
 * (ed_preview_portrait AND ed_preview_landscape) — two separate
 * GLSurfaceViews sitting in the tree at the same time, one merely
 * visibility=GONE. GONE only skips measure/layout/draw; it does NOT stop
 * GLSurfaceView.onAttachedToWindow() from firing, which spins up a real GL
 * thread + EGL context immediately once the view is inflated. So every time
 * the Editor opened, TWO full GL contexts were created and left rendering
 * simultaneously in the background — double the GPU/memory pressure of what
 * was actually shown, which is exactly what presents as a glitch/freeze on
 * weaker hardware. The fix: only two empty *containers*
 * (ed_preview_container_portrait / ed_preview_container_landscape) live in
 * the XML now. A single [WallpaperPreviewView] is created once in code and
 * moved (re-parented) into whichever container is active — see
 * moveSharedPreviewInto(). Never more than one GL context exists at a time.
 */
class EditorActivity : com.youki.dex.activities.BaseFontScaleActivity(), ColorPickerDialogListener {

    private val viewModel: EditorViewModel by viewModels()
    /** Powers ed_land_video_grid (landscape group only) — the same
     *  GalleryViewModel used on the main gallery screen, so any recently
     *  imported video shows up here too with no duplicated logic. */
    private val galleryViewModel: GalleryViewModel by viewModels()

    /** Whichever group (portrait/landscape) is currently active — set inside
     *  applyOrientationLayout() before any other setup function runs, so
     *  every other function below can rely on it instead of re-deriving it. */
    private var isLandscapeGroupActive = false

    /** The single shared preview surface, created once in [onCreate] and
     *  re-parented into whichever container (ed_preview_container_portrait
     *  or ed_preview_container_landscape) matches the active group — see
     *  moveSharedPreviewInto(). Exactly one GLSurfaceView/EGL context exists
     *  for the lifetime of this Activity, never two. */
    private lateinit var previewView: WallpaperPreviewView
    private lateinit var freeGestureOverlay: FreeGestureOverlay
    private lateinit var freeHint: android.widget.TextView
    private lateinit var videoEngine: VideoEngine
    private var activePreviewSurface: Surface? = null

    // ── Portrait-group-only state (drag sheet) ──
    private var editorSheet: android.widget.LinearLayout? = null
    /** The minimum height the sheet always stays visible at (handle only), preventing it from
     *  disappearing completely so the user can't bring it back */
    private var sheetMinVisiblePx = 0f
    /** Maximum downward offset (= total sheet height minus the minimum visible height) */
    private var sheetMaxTranslationY = 0f

    private var videoUri: String = ""
    /** The first touch in Free mode hides the hint, and it doesn't reappear for this session */
    private var freeHintDismissed = false

    /**
     * Determines the current screen orientation (portrait/landscape) from the
     * actual resource configuration, not from intermediate pixel dimensions —
     * this matches exactly what Android itself decides to display, and stays
     * correct even before any GL Surface measurement. Used for
     * audioPlaybackFor() (separate portrait/landscape audio settings) — kept
     * independent from which UI group is currently shown.
     */
    private val currentOrientation: WallpaperConfig.Orientation
        get() = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
            WallpaperConfig.Orientation.LANDSCAPE else WallpaperConfig.Orientation.PORTRAIT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lw_editor)

        // dead black screen with no reaction when opening the Editor — fixed below:
        // the old check `intent.getStringExtra(EXTRA_VIDEO_URI) ?: run { finish(); return }`
        // only caught a *missing* extra (null). If the extra was present but
        // blank/invalid (e.g. "" from a stale/deleted file, a race in the
        // caller, or a malformed content:// URI), videoUri became "" and the
        // Activity carried on: setContentView() had already drawn an empty
        // shell, then VideoEngine/ExoPlayer silently failed to prepare a
        // player for an empty URI with no visible error — exactly the "black
        // screen, no crash, no reaction" symptom. We now also reject a blank
        // URI, and — critically — actually tell the user why we're bailing
        // out instead of finishing silently, so it doesn't look frozen/dead.
        val uriExtra = intent.getStringExtra(EXTRA_VIDEO_URI)
        if (uriExtra.isNullOrBlank()) {
            android.widget.Toast.makeText(
                this, R.string.lw_editor_invalid_video, android.widget.Toast.LENGTH_SHORT
            ).show()
            finish()
            return
        }
        videoUri = uriExtra
        val videoName = intent.getStringExtra(EXTRA_VIDEO_NAME) ?: ""

        videoEngine = VideoEngine(this)

        // Bug fix — glitch/freeze — duplicate GL contexts. create the ONE shared
        // preview surface here, once, instead of letting each group's XML
        // inflate its own copy. It starts unparented; moveSharedPreviewInto()
        // attaches it to whichever container is active as soon as
        // bindPortraitGroup()/bindLandscapeGroup() runs.
        previewView = WallpaperPreviewView(this)

        // observeConfig() reads previewView, which only exists after the
        // first bindPortraitGroup()/bindLandscapeGroup() call inside
        // applyOrientationLayout() — and that first call happens
        // asynchronously (waits for ed_root's first real layout pass, see
        // its kdoc). Passing it as a callback here guarantees observeConfig()
        // only starts once previewView is actually initialized, instead of
        // racing it.
        applyOrientationLayout(videoName, onFirstBind = {
            // Note: the previously used setupTabs() (ViewPager2 +
            // TabLayoutMediator) has been fully removed. UnifiedEditorFragment
            // (a single scrollable list instead of separate tabs) is added
            // programmatically via attachUnifiedFragment() — see its kdoc for
            // why it isn't declared via android:name on the XML container.
            observeConfig()
            viewModel.load(videoUri)
        })
    }

    /**
     * The single entry point deciding which of the two independent groups
     * (ed_portrait_group / ed_landscape_group) is shown, and wiring up every
     * view reference + listener for that group only. Re-run automatically
     * whenever ed_root's actual measured size changes shape (portrait vs
     * landscape), via a persistent addOnLayoutChangeListener — no Activity
     * recreation, no separate resource file.
     */
    private fun applyOrientationLayout(videoName: String, onFirstBind: () -> Unit) {
        val root = findViewById<android.view.View>(R.id.ed_root)

        var lastWasLandscape: Boolean? = null
        var firstBindDone = false

        fun setupForCurrentShape() {
            val w = root.width
            val h = root.height
            if (w <= 0 || h <= 0) return // not measured yet — wait for next pass
            val isLandscape = w > h
            if (isLandscape == lastWasLandscape) return // shape unchanged, nothing to do
            lastWasLandscape = isLandscape
            isLandscapeGroupActive = isLandscape

            if (isLandscape) {
                bindLandscapeGroup(videoName)
            } else {
                bindPortraitGroup(videoName)
            }

            if (!firstBindDone) {
                firstBindDone = true
                onFirstBind()
            }
        }

        root.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val newShapeIsLandscape = (right - left) > (bottom - top)
            val oldShapeIsLandscape = (oldRight - oldLeft) > (oldBottom - oldTop)
            if (newShapeIsLandscape != oldShapeIsLandscape || lastWasLandscape == null) {
                setupForCurrentShape()
            }
        }
        root.post { setupForCurrentShape() }
    }

    /**
     * Activates ed_portrait_group: fullscreen preview + hand-draggable
     * settings sheet on top. Mirrors the original stable portrait design
     * 1:1 — no aspect-ratio container, no percentage sizing, direct
     * full-area touch.
     */
    private fun bindPortraitGroup(videoName: String) {
        findViewById<android.view.View>(R.id.ed_portrait_group).visibility = android.view.View.VISIBLE
        findViewById<android.view.View>(R.id.ed_landscape_group).visibility = android.view.View.GONE

        setSupportActionBar(findViewById(R.id.ed_toolbar_portrait))
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.ed_toolbar_portrait)
            .setNavigationOnClickListener { finish() }
        supportActionBar?.title = videoName.ifBlank { getString(R.string.lw_editor_title) }

        moveSharedPreviewInto(R.id.ed_preview_container_portrait)
        freeGestureOverlay = findViewById(R.id.ed_free_gesture_overlay_portrait)
        freeHint = findViewById(R.id.ed_free_hint_portrait)

        bindPreviewSurface()
        attachUnifiedFragment(R.id.ed_unified_fragment_portrait)
        setupBottomSheet()
        setupFreeGestures()

        findViewById<android.view.View>(R.id.ed_fab_apply_portrait).setOnClickListener {
            viewModel.applyWallpaper { requestSetLiveWallpaper(R.id.ed_fab_apply_portrait) }
        }
    }

    /**
     * Activates ed_landscape_group: left video grid + right column with a
     * full-width preview (match_parent, no percentage/cropping) and settings
     * below it. Mirrors the original stable landscape design 1:1.
     */
    private fun bindLandscapeGroup(videoName: String) {
        findViewById<android.view.View>(R.id.ed_landscape_group).visibility = android.view.View.VISIBLE
        findViewById<android.view.View>(R.id.ed_portrait_group).visibility = android.view.View.GONE

        setSupportActionBar(findViewById(R.id.ed_toolbar_landscape))
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.ed_toolbar_landscape)
            .setNavigationOnClickListener { finish() }
        supportActionBar?.title = videoName.ifBlank { getString(R.string.lw_editor_title) }

        moveSharedPreviewInto(R.id.ed_preview_container_landscape)
        freeGestureOverlay = findViewById(R.id.ed_free_gesture_overlay_landscape)
        freeHint = findViewById(R.id.ed_free_hint_landscape)

        bindPreviewSurface()
        attachUnifiedFragment(R.id.ed_unified_fragment_landscape)
        setupLandscapeVideoGrid()
        setupFreeGestures()

        findViewById<android.view.View>(R.id.ed_fab_apply_landscape).setOnClickListener {
            viewModel.applyWallpaper { requestSetLiveWallpaper(R.id.ed_fab_apply_landscape) }
        }
    }

    /**
     * Re-parents the single shared [previewView] into [containerId] (either
     * ed_preview_container_portrait or ed_preview_container_landscape),
     * removing it from wherever it currently sits first.
     *
     * This is the actual fix for the duplicate-GL-context glitch: a
     * GLSurfaceView only exists once, ever, for this Activity's lifetime.
     * Moving it via removeView()+addView() does NOT tear down or recreate
     * its EGL context/GL thread (that only happens on onPause/onResume via
     * GLSurfaceView's own lifecycle) — it's a plain ViewGroup reparent, so
     * switching groups (e.g. rotating the device) stays just as instant as
     * it was when each group had its own instance, but without ever paying
     * for two GL contexts at once.
     */
    private fun moveSharedPreviewInto(containerId: Int) {
        (previewView.parent as? android.view.ViewGroup)?.removeView(previewView)
        findViewById<android.widget.FrameLayout>(containerId).addView(
            previewView,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    /**
     * Adds a single [UnifiedEditorFragment] instance into [containerId] (a
     * plain FrameLayout, not an auto-inflating FragmentContainerView), first
     * removing any previous instance from the other group's container. This
     * guarantees only ONE UnifiedEditorFragment is ever alive at a time —
     * critical because it observes the shared [EditorViewModel] directly, so
     * two simultaneous instances (one per group, both created automatically
     * if the XML declared android:name on both FragmentContainerViews) would
     * both react to every config change at once, one of them invisibly in
     * the background. Reusing a single fragment instance across a group
     * switch (rather than replacing it every time) also avoids rebuilding
     * its whole view hierarchy — and any related flicker — on every
     * portrait/landscape flip.
     */
    private fun attachUnifiedFragment(containerId: Int) {
        val fm = supportFragmentManager
        val existing = fm.findFragmentByTag(UNIFIED_FRAGMENT_TAG)

        if (existing != null && existing.view?.parent?.let { (it as? android.view.View)?.id } == containerId) {
            // Already attached to the correct container (e.g. re-entrant
            // call for the same group) — nothing to do.
            return
        }

        fm.commitNow {
            if (existing != null) remove(existing)
            add(containerId, UnifiedEditorFragment::class.java, null, UNIFIED_FRAGMENT_TAG)
        }
    }

    /**
     * Wires the currently active previewView (whichever group just bound it)
     * to VideoEngine or loads the static image — same mechanism as before, just extracted into a
     * shared helper since both groups need it identically.
     *
     * FIX (Freeze — GLSurfaceView EGL Context Recreation): this callback
     * fires every time GLSurfaceView recreates its EGL context (after every
     * onResume(), a group switch, returning from settings...). Each time,
     * VideoEngine must be rebound to the new Surface — otherwise ExoPlayer
     * keeps writing to an old Surface that the new GL thread doesn't read
     * from.
     */
    private fun bindPreviewSurface() {
        previewView.setOnSurfaceReady { surface ->
            activePreviewSurface = surface
            applyCurrentMedia(surface)
        }
    }

    private fun applyCurrentMedia(surface: Surface? = activePreviewSurface ?: videoEngine.currentSurface) {
        val isImage = com.youki.dex.livewallpaper.data.VideoFileStore.isImage(this, videoUri)
        if (isImage) {
            videoEngine.release()
            previewView.renderer.clearCurrentFrame()
            previewView.renderer.setImageUri(this, android.net.Uri.parse(videoUri)) { w, h ->
                previewView.requestRender()
            }
        } else {
            previewView.renderer.clearImage()
            val targetSurface = surface ?: activePreviewSurface ?: videoEngine.currentSurface
            if (targetSurface != null) {
                val audio = viewModel.config.value.audioPlaybackFor(currentOrientation)
                videoEngine.prepare(
                    videoUri = videoUri,
                    outputSurface = targetSurface,
                    muted = true,
                    playbackSpeed = audio.speed,
                    audioPitch = audio.pitch,
                    onSizeChanged = { w, h ->
                        previewView.renderer.updateVideoSize(w, h)
                        previewView.requestRender()
                    }
                )
            }
        }
    }

    /**
     * The left column in the landscape group: a video grid (the exact same
     * [GalleryAdapter] used on the main gallery screen, with no duplicated
     * logic). Tapping another video here **does not open a new screen** — it
     * immediately swaps the previewed video within the same EditorActivity
     * (reusing the same VideoEngine/Renderer), exactly like the reference
     * "Browse Wallpapers" behavior where the list and the preview always
     * share the same window.
     *
     * Column width itself stays flexible (0dp + weight in the XML, no fixed
     * dp) and the grid's own span count is recalculated live from its actual
     * measured width — more columns appear automatically as the window gets
     * wider (desktop/tablet use), instead of a fixed count wasting space or
     * crowding thumbnails. No fixed empty edges: padding is small and fixed,
     * the flexible part is the column/grid sizing itself.
     */
    private fun setupLandscapeVideoGrid() {
        val grid = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.ed_land_video_grid)

        val imageLoader = ImageLoader.Builder(this)
            .components { add(VideoFrameDecoder.Factory()) }
            .build()

        val adapter = GalleryAdapter(
            imageLoader = imageLoader,
            activeVideoUri = { videoUri }, // "active" here means: the video currently previewed in this editor
            onClick = { video ->
                if (video.uri != videoUri) switchPreviewedVideo(video.uri, video.displayName)
            },
            onLongClick = { /* No context menu in this compact context — delete/rename stays on the main gallery screen only */ }
        )

        // Dynamic span count: calculate based on the grid's actual width so
        // columns increase automatically as space allows, instead of a fixed
        // count that wastes space on wide windows or crowds a narrow one.
        // Each thumbnail is at least 120dp wide.
        val minColumnWidthPx = (120 * resources.displayMetrics.density).toInt()
        val layoutManager = GridLayoutManager(this, 2) // start with 2, updated after measure
        grid.layoutManager = layoutManager

        grid.addOnLayoutChangeListener { v, left, _, right, _, _, _, _, _ ->
            val gridWidth = right - left
            if (gridWidth > 0) {
                val spanCount = (gridWidth / minColumnWidthPx).coerceAtLeast(1)
                if (layoutManager.spanCount != spanCount) {
                    layoutManager.spanCount = spanCount
                }
            }
        }

        grid.adapter = adapter

        galleryViewModel.videos.onEach { adapter.submitList(it) }.launchIn(lifecycleScope)
    }

    /**
     * Switches the currently previewed video without recreating the Activity —
     * loads the WallpaperConfig for the new video (or a fresh default one if
     * none exists) via the same [EditorViewModel.load], then re-prepares
     * [VideoEngine] on the existing Surface (no need to wait for
     * onSurfaceReady again).
     */
    private fun switchPreviewedVideo(newUri: String, newName: String) {
        videoUri = newUri
        supportActionBar?.title = newName.ifBlank { getString(R.string.lw_editor_title) }
        viewModel.load(newUri)
        applyCurrentMedia()
    }

    /**
     * Formally asks Android to activate [YoukiGLWallpaperService] as the
     * current live wallpaper.
     *
     * Not always called anymore: Android (a security decision from Google
     * itself, with no official API to bypass it) forces the system
     * confirmation screen every time ACTION_CHANGE_LIVE_WALLPAPER is
     * explicitly requested — regardless of anything the app does. But this
     * Intent is really only needed once: to activate
     * [YoukiGLWallpaperService] as the current live wallpaper for the first
     * time (or if the user has temporarily switched it to another app). If
     * it's already the currently active wallpaper (the common case: the user
     * is tweaking settings/switching videos within the same app and presses
     * "Apply" repeatedly), then saving to Room alone is enough — the already
     * running Service watches observeActive() and picks up any change
     * immediately (a new videoUri or new settings), so there's no need
     * whatsoever to re-"set" a wallpaper that's already active. See
     * isYoukiWallpaperCurrentlyActive().
     *
     * [anchorViewId] is whichever FAB (portrait or landscape group) triggered
     * this, used only to anchor the Snackbar to a currently-visible view.
     */
    private fun requestSetLiveWallpaper(anchorViewId: Int) {
        if (isYoukiWallpaperCurrentlyActive()) {
            // The service is already running and watching the database —
            // saving to Room (which already happened before this function was
            // called, see applyWallpaper) is enough on its own for the change
            // to take effect immediately, with no Intent or system screen needed.
            // No finish() here on purpose: the user stays inside the editor
            // and can keep adjusting and pressing "Apply" repeatedly, without
            // being forced back to the main gallery list after every tap —
            // this is a deliberate difference from the first-time path below
            // (which finishes the screen after returning from the system
            // screen, since that case really is an "initial setup" that
            // deserves returning to the list).
            Snackbar.make(findViewById(anchorViewId), R.string.lw_applied, Snackbar.LENGTH_SHORT).show()
            return
        }

        val component = ComponentName(this, YoukiGLWallpaperService::class.java)
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
        }
        if (intent.resolveActivity(packageManager) != null) {
            setWallpaperLauncher.launch(intent)
        } else {
            // Some devices (rarely) don't support this Intent — fallback
            try {
                startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            } catch (e: Exception) {
                Snackbar.make(
                    findViewById(anchorViewId),
                    R.string.lw_apply_unsupported,
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Checks whether [YoukiGLWallpaperService] is exactly the live wallpaper
     * currently active at the system level (not just isActive=1 in our
     * internal database — this is a real check via
     * WallpaperManager.getWallpaperInfo(), the only reliable source for
     * knowing "are we really the wallpaper running right now").
     * getWallpaperInfo() returns null if the current wallpaper is static (a
     * regular image) rather than live, so the safe comparison here handles
     * both cases.
     */
    private fun isYoukiWallpaperCurrentlyActive(): Boolean {
        val info = WallpaperManager.getInstance(this).wallpaperInfo ?: return false
        return info.packageName == packageName &&
            info.serviceName == YoukiGLWallpaperService::class.java.name
    }

    private val setWallpaperLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
            // Returns here whether the user confirmed or canceled from the
            // Android system screen — the data is already saved to the DB
            // from applyWallpaper, we're just notifying the user.
            val anchor = if (isLandscapeGroupActive) R.id.ed_fab_apply_landscape else R.id.ed_fab_apply_portrait
            Snackbar.make(findViewById(anchor), R.string.lw_applied, Snackbar.LENGTH_SHORT).show()
            finish()
        }

    /**
     * Builds manual sheet dragging via an [android.view.View.OnTouchListener]
     * on the handle only, instead of
     * [com.google.android.material.bottomsheet.BottomSheetBehavior]. See the
     * full explanation for this replacement in the comment in
     * activity_lw_editor.xml above the ed_sheet element — in short:
     * BottomSheetBehavior structurally always "settles" into one of only 3
     * fixed states when the finger is lifted, and there's no official setting
     * to disable this. Here translationY is updated directly 1:1 with the
     * finger position, and stays exactly where the user left it with no
     * automatic "jump" — full freedom between 0 (the sheet fully open from
     * the top of the screen) and sheetMaxTranslationY (the sheet nearly
     * hidden, only the handle visible).
     *
     * Portrait-group-only — the landscape group has no ed_sheet/ed_drag_handle
     * at all, since there's enough room to show settings fixed below the
     * preview without needing to partially hide it.
     */
    private fun setupBottomSheet() {
        val sheet = findViewById<android.widget.LinearLayout>(R.id.ed_sheet)
        editorSheet = sheet
        val dragHandle = findViewById<android.view.View>(R.id.ed_drag_handle)
        val root = findViewById<android.view.View>(R.id.ed_root)

        // The minimum that stays visible = the handle's height alone (~32dp),
        // actually computed after the first layout pass instead of an assumed
        // dp value, so it stays accurate on any screen density.
        dragHandle.post {
            sheetMinVisiblePx = dragHandle.height.toFloat().coerceAtLeast(1f)
        }

        // Minimum movement threshold (the same system setting every Android UI
        // uses to distinguish incidental touch/tap from a real drag)
        val touchSlopPx = android.view.ViewConfiguration.get(this).scaledTouchSlop.toFloat()

        var dragStartRawY = 0f
        var dragStartTranslationY = 0f
        /** The max (absolute) distance the finger moved during this touch — used to distinguish a tap from a real drag */
        var maxMovementDuringTouch = 0f

        dragHandle.setOnTouchListener { v, event ->
            val currentSheet = editorSheet ?: return@setOnTouchListener false
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    dragStartRawY = event.rawY
                    dragStartTranslationY = currentSheet.translationY
                    maxMovementDuringTouch = 0f
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    // sheetMaxTranslationY is recomputed here (not just once)
                    // because it depends on the sheet's actual current height,
                    // which changes as UnifiedEditorFragment's content changes
                    // (e.g. Free mode sections appearing/disappearing
                    // previously), so the maximum stays correct at all times.
                    sheetMaxTranslationY =
                        (currentSheet.height - sheetMinVisiblePx).coerceAtLeast(0f)

                    val deltaY = event.rawY - dragStartRawY
                    maxMovementDuringTouch = maxOf(maxMovementDuringTouch, kotlin.math.abs(deltaY))

                    // Bug fix — excessive sensitivity — any touch used to raise the
                    // whole sheet.
                    // We only actually move the sheet once the movement
                    // exceeds the touch slop threshold. Before this fix, every
                    // movement, however small, was applied immediately to
                    // translationY, so any slight finger tremor "felt too
                    // sensitive." Now the actual drag only starts once this
                    // small threshold is exceeded (the same behavior as any
                    // standard RecyclerView/ScrollView on Android).
                    if (maxMovementDuringTouch > touchSlopPx) {
                        val newTranslationY =
                            (dragStartTranslationY + deltaY).coerceIn(0f, sheetMaxTranslationY)
                        currentSheet.translationY = newTranslationY
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    // The core fix: performClick() (which triggers the
                    // automatic open/collapse toggle via onClickListener
                    // below) is only called if the total movement during this
                    // touch didn't exceed the threshold — i.e. it was really
                    // a "tap" and not a drag. Previously it was called
                    // unconditionally every time, so any real drag was
                    // automatically followed by a full animate() jump on top
                    // of the manual movement the user had just made — which
                    // is exactly what looked like "it jumps all the way up
                    // even though I tried to hold it at a certain position."
                    if (maxMovementDuringTouch <= touchSlopPx) {
                        v.performClick()
                    }
                    true
                }
                else -> false
            }
        }

        // A simple tap (without dragging) on the handle: toggle between fully
        // open and collapsed, as a quick alternative for anyone who doesn't
        // want to drag manually every time.
        dragHandle.setOnClickListener {
            val currentSheet = editorSheet ?: return@setOnClickListener
            sheetMaxTranslationY = (currentSheet.height - sheetMinVisiblePx).coerceAtLeast(0f)
            val isMostlyOpen = currentSheet.translationY < sheetMaxTranslationY / 2f
            currentSheet.animate()
                .translationY(if (isMostlyOpen) sheetMaxTranslationY else 0f)
                .setDuration(220L)
                .start()
        }

        // The default state when opening the editor: the sheet is mostly
        // collapsed (keeping the preview almost fully visible), in the same
        // spirit as the previous STATE_COLLAPSED setting but without any
        // restriction on movement afterward — the user is free to drag it to
        // any height.
        root.post {
            val currentSheet = editorSheet ?: return@post
            sheetMaxTranslationY = (currentSheet.height - sheetMinVisiblePx).coerceAtLeast(0f)
            currentSheet.translationY = sheetMaxTranslationY * 0.55f
        }
    }

    /** Any settings change (from any tab) updates the Renderer directly + ExoPlayer speed/pitch */
    private fun observeConfig() {
        viewModel.config.onEach { config ->
            previewView.renderer.updateConfig(config)
            // Fully decoupled from landscape: the preview applies the
            // speed/pitch for the current orientation only — the same read
            // source used by the actual service (audioPlaybackFor), so
            // there's no difference between what the user sees in the
            // preview and what will actually run on the live wallpaper.
            val audio = config.audioPlaybackFor(currentOrientation)
            videoEngine.setSpeed(audio.speed)
            videoEngine.setPitch(audio.pitch)
            previewView.requestRender()
            updateFreeOverlayVisibility(config)
        }.launchIn(lifecycleScope)
    }

    /**
     * Wires [FreeGestureOverlay] to [WallpaperConfig] updates in Free mode.
     *
     * Every movement delta (drag/pinch/rotate) is translated directly into
     * scale/translate/rotation values for the current screen orientation
     * (portrait/landscape) only — with no intermediate slider. The current
     * values are read from the live [EditorViewModel.config] so movements
     * accumulate correctly on top of each other (rather than on top of an old
     * value stored locally that may have drifted), and are written only to
     * the current orientation's field set — so an edit in portrait never
     * leaks into landscape and vice versa.
     */
    private fun setupFreeGestures() {
        freeGestureOverlay.onGestureStateChanged = { active ->
            if (active && !freeHintDismissed) {
                freeHintDismissed = true
                freeHint.visibility = android.view.View.GONE
            }
        }

        freeGestureOverlay.onGesture = { dx, dy, scaleFactor, rotationDeltaDeg ->
            viewModel.updateFreeTransform(currentOrientation) { current ->
                // Min/max bounds prevent shrinking the video to a point or
                // enlarging it so much it escapes the screen entirely
                val newScaleX = (current.scaleX * scaleFactor).coerceIn(0.2f, 3.0f)
                val newScaleY = (current.scaleY * scaleFactor).coerceIn(0.2f, 3.0f)
                val newTranslateX = (current.translateX + dx).coerceIn(-1f, 1f)
                // FIX: dy on Android is positive = downward, but in OpenGL
                // positive = upward → we invert it
                val newTranslateY = (current.translateY - dy).coerceIn(-1f, 1f)
                var newRotation = (current.rotationDeg + rotationDeltaDeg) % 360f
                if (newRotation < 0f) newRotation += 360f

                current.copy(
                    scaleX = newScaleX,
                    scaleY = newScaleY,
                    translateX = newTranslateX,
                    translateY = newTranslateY,
                    rotationDeg = newRotation
                )
            }
        }
    }

    /** Shows/hides the Free touch overlay + its hint based on the current scaling mode */
    private fun updateFreeOverlayVisibility(config: WallpaperConfig) {
        val isFree = config.scaleMode == WallpaperConfig.ScaleMode.FREE.name
        freeGestureOverlay.visibility = if (isFree) android.view.View.VISIBLE else android.view.View.GONE
        freeHint.visibility = if (isFree && !freeHintDismissed) android.view.View.VISIBLE else android.view.View.GONE
    }

    override fun onPause() {
        super.onPause()
        videoEngine.pause()
        previewView.onPause()
    }

    override fun onResume() {
        super.onResume()
        previewView.onResume()
        videoEngine.resume()
    }

    override fun onDestroy() {
        super.onDestroy()
        // FIX: the correct teardown order in Editor:
        //   1. Stop the video first (no new frames reach the Surface)
        //   2. Stop the GL thread by setting RENDERMODE back to
        //      WHEN_DIRTY, then one final onPause to stop the render
        //      thread for good
        //   3. Then release the renderer (safe because the GL thread has stopped)
        activePreviewSurface = null
        videoEngine.release()
        previewView.renderMode = android.opengl.GLSurfaceView.RENDERMODE_WHEN_DIRTY
        previewView.renderer.release()
    }

    /**
     * Called by the com.jaredrummler.android.colorpicker library when a color
     * is selected (whether via the free RGB sliders, the HSV wheel, or direct
     * Hex entry). [color] here is a full ARGB Int, ready to convert to the Hex
     * String stored in [WallpaperConfig.backgroundColor] — the same
     * "#FF000000" format originally used for the default black color value.
     */
    override fun onColorSelected(dialogId: Int, color: Int) {
        if (dialogId == COLOR_PICKER_DIALOG_ID) {
            viewModel.update { it.copy(backgroundColor = String.format("#%08X", color)) }
        }
    }

    override fun onDialogDismissed(dialogId: Int) {
        // No action needed — dismissing without a selection leaves the current color as is.
    }

    companion object {
        const val EXTRA_VIDEO_URI  = "extra_video_uri"
        const val EXTRA_VIDEO_NAME = "extra_video_name"
        /** Distinguishes the background color Dialog from any other ColorPickerDialog that might be added later on this same screen */
        const val COLOR_PICKER_DIALOG_ID = 101
        /** Stable tag ensuring only one UnifiedEditorFragment instance is ever
         *  attached, regardless of which group (portrait/landscape) is active. */
        private const val UNIFIED_FRAGMENT_TAG = "unified_editor_fragment"
    }
}
