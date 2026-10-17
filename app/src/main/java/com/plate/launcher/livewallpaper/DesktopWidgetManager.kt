package com.plate.launcher.livewallpaper

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager

/**
 * Manages the AppWidget host for the desktop.
 *
 * Widgets are placed on the same free pixel-position desktop as app icons
 * (see LauncherActivity's findFreeCell/snapToGrid). Each widget occupies a
 * colSpan × rowSpan visual footprint sized off the same grid unit used for
 * icon snapping. Position and size preferences are persisted under
 * "widget_<hostId>_*" keys so they survive restarts.
 *
 * Usage from LauncherActivity:
 *   1. Call [start] in onStart / onResume so widget updates flow in.
 *   2. Call [stop] in onStop.
 *   3. Call [release] in onDestroy.
 *   4. Forward Activity results from APPWIDGET_CONFIGURE / PICK flows.
 */
class DesktopWidgetManager(private val context: Context) {

    companion object {
        /** Unique ID for this host — must be stable across restarts. */
        const val APPWIDGET_HOST_ID = 1024
        /** Request code used when launching the widget picker/configure activity. */
        const val REQUEST_PICK_WIDGET   = 2001
        const val REQUEST_CREATE_WIDGET = 2002

        private const val PREFS_KEY_WIDGET_IDS = "desktop_widget_host_ids"

        /** Returns all widget host IDs currently saved on the desktop. */
        fun getSavedWidgetIds(context: Context): Set<String> =
            PreferenceManager.getDefaultSharedPreferences(context)
                .getStringSet(PREFS_KEY_WIDGET_IDS, emptySet()) ?: emptySet()

        /** Persists the grid position for a widget host ID. */
        fun saveWidgetPosition(context: Context, hostId: Int, col: Int, row: Int, colSpan: Int, rowSpan: Int) {
            PreferenceManager.getDefaultSharedPreferences(context).edit {
                putString("widget_${hostId}_pos", "$col,$row")
                putInt("widget_${hostId}_colSpan", colSpan)
                putInt("widget_${hostId}_rowSpan", rowSpan)
                // Keep the saved-IDs set in sync.
                val ids = getSavedWidgetIds(context).toMutableSet()
                ids.add(hostId.toString())
                putStringSet(PREFS_KEY_WIDGET_IDS, ids)
            }
        }

        /** Removes all saved preferences for a widget host ID. */
        fun removeWidgetPrefs(context: Context, hostId: Int) {
            PreferenceManager.getDefaultSharedPreferences(context).edit {
                remove("widget_${hostId}_pos")
                remove("widget_${hostId}_colSpan")
                remove("widget_${hostId}_rowSpan")
                val ids = getSavedWidgetIds(context).toMutableSet()
                ids.remove(hostId.toString())
                putStringSet(PREFS_KEY_WIDGET_IDS, ids)
            }
        }

        /** Returns the saved cell position (col, row) for a widget host ID. */
        fun getSavedPosition(context: Context, hostId: Int): Pair<Int, Int>? {
            val raw = PreferenceManager.getDefaultSharedPreferences(context)
                .getString("widget_${hostId}_pos", null) ?: return null
            val parts = raw.split(",")
            val col = parts.getOrNull(0)?.toIntOrNull() ?: return null
            val row = parts.getOrNull(1)?.toIntOrNull() ?: return null
            return col to row
        }

        /** Returns the saved (colSpan, rowSpan) for a widget host ID. Defaults to 2×2. */
        fun getSavedSpan(context: Context, hostId: Int): Pair<Int, Int> {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val colSpan = prefs.getInt("widget_${hostId}_colSpan", 2)
            val rowSpan = prefs.getInt("widget_${hostId}_rowSpan", 2)
            return colSpan to rowSpan
        }
    }

    private val host = AppWidgetHost(context, APPWIDGET_HOST_ID)
    val manager: AppWidgetManager = AppWidgetManager.getInstance(context)

    /** Start listening for widget updates (call in onStart/onResume). */
    fun start() {
        try {
            host.startListening()
        } catch (e: Exception) {
            // Defensive: system-side widget host state can be inconsistent
            // (e.g. a provider was uninstalled while we were stopped).
        }
    }

    /** Stop listening (call in onStop). */
    fun stop() {
        try {
            host.stopListening()
        } catch (e: Exception) {
            // Defensive: AppWidgetHost.stopListening() can throw NPE deep in
            // AppWidgetServiceImpl when a widget's provider/id state is stale
            // (system-side null Provider.id). Swallow rather than crash the
            // activity on a call whose only job is to unregister listeners.
        }
    }

    /** Release the host (call in onDestroy). */
    fun release() {
        try {
            host.stopListening()
        } catch (e: Exception) {
            // See stop() above.
        }
    }

    /**
     * Allocates a new host ID for a widget that is about to be configured/added.
     * Call before launching the pick or configure intent.
     */
    fun allocateWidgetId(): Int = host.allocateAppWidgetId()

    /**
     * Deletes a host ID, freeing the system's widget resources.
     * Must be called when a widget is removed from the desktop.
     */
    fun deleteWidgetId(hostId: Int) {
        host.deleteAppWidgetId(hostId)
        removeWidgetPrefs(context, hostId)
    }

    /**
     * Creates and returns the [AppWidgetHostView] for an allocated widget ID,
     * sized to fit [colSpan] × [rowSpan] cells of the given cell pixel dimensions.
     *
     * The view is not yet added to any container — the caller (LauncherActivity)
     * places it in the desktop FrameLayout at the correct pixel offset.
     */
    fun createHostView(
        hostId: Int,
        cellWidthPx: Int,
        cellHeightPx: Int,
        colSpan: Int,
        rowSpan: Int,
    ): AppWidgetHostView {
        val info = manager.getAppWidgetInfo(hostId)
            ?: throw IllegalStateException("No AppWidgetProviderInfo for hostId=$hostId (provider likely uninstalled)")
        // host.createView() internally calls setId(hostId) — if two host views
        // ever share a hostId (e.g. a stale id reused after a failed add), the
        // second view's onRestoreInstanceState collides with the first's saved
        // state class, producing "Wrong state class... same id" crashes. Guard
        // by always inflating via the host with a distinct, freshly allocated id.
        val view = host.createView(context, hostId, info)
        view.minimumWidth  = cellWidthPx  * colSpan
        view.minimumHeight = cellHeightPx * rowSpan
        return view
    }

    /**
     * Returns the [AppWidgetProviderInfo] for a given host ID, or null if not found.
     * Useful for showing the widget label or deciding default span.
     */
    fun getInfo(hostId: Int): AppWidgetProviderInfo? = manager.getAppWidgetInfo(hostId)
}
