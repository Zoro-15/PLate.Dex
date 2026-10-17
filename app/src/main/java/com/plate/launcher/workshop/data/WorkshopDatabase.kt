package com.plate.launcher.workshop.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * WorkshopDatabase — SQLiteOpenHelper version without Room/KAPT
 *
 * Safe migration: preserves all user sources (isCustom=1) and updates
 * only the default sources (isCustom=0) — no custom sources are ever deleted.
 *
 * Changes from the old version:
 *   - Removed Room/@Database — no KAPT code
 *   - Removed the 7 dead columns (sourceFormat, forceWebView, pageUrlTemplate,
 *     itemContainerSelector, titleSelector, previewImgSelector, downloadLinkSelector)
 *   - Replaced fallbackToDestructiveMigration with a safe manual migration
 */
class WorkshopDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME    = "youki_workshop.db"
        const val DATABASE_VERSION = 19   // FIX: bumped from 18 to remove the MyLiveWallpapers default source

        const val TABLE = "workshop_sources"

        const val COL_SEARCH_URL   = "searchUrlTemplate"
        const val COL_DISPLAY_NAME = "displayName"
        const val COL_CONTENT_TYPE = "contentType"
        const val COL_BROWSE_URL   = "browseUrlTemplate"
        const val COL_IS_CUSTOM    = "isCustom"
        const val COL_IS_ENABLED   = "isEnabled"
        const val COL_ADDED_AT     = "addedAt"

        private const val SQL_CREATE = """
            CREATE TABLE IF NOT EXISTS $TABLE (
                $COL_SEARCH_URL   TEXT PRIMARY KEY NOT NULL,
                $COL_DISPLAY_NAME TEXT NOT NULL,
                $COL_CONTENT_TYPE TEXT NOT NULL,
                $COL_BROWSE_URL   TEXT,
                $COL_IS_CUSTOM    INTEGER NOT NULL DEFAULT 0,
                $COL_IS_ENABLED   INTEGER NOT NULL DEFAULT 1,
                $COL_ADDED_AT     INTEGER NOT NULL DEFAULT 0
            )
        """

        @Volatile private var instance: WorkshopDatabase? = null

        fun get(context: Context): WorkshopDatabase =
            instance ?: synchronized(this) {
                instance ?: WorkshopDatabase(context.applicationContext)
                    .also { instance = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(SQL_CREATE)
        seedDefaults(db)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        // Seed the default sources if the table is empty (first run after data was cleared)
        val count = db.rawQuery("SELECT COUNT(*) FROM $TABLE WHERE $COL_IS_CUSTOM = 0", null)
            ?.use { if (it.moveToFirst()) it.getInt(0) else 0 } ?: 0
        if (count == 0) seedDefaults(db)
    }

    private fun seedDefaults(db: SQLiteDatabase) {
        val now = System.currentTimeMillis()
        DefaultSources.list().forEach { src ->
            val cv = android.content.ContentValues().apply {
                put(COL_SEARCH_URL,   src.searchUrlTemplate)
                put(COL_DISPLAY_NAME, src.displayName)
                put(COL_CONTENT_TYPE, src.contentType)
                put(COL_BROWSE_URL,   src.browseUrlTemplate)
                put(COL_IS_CUSTOM,    0)
                put(COL_IS_ENABLED,   1)
                put(COL_ADDED_AT,     now)
            }
            db.insertWithOnConflict(TABLE, null, cv,
                android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    /**
     * Safe migration from any old Room version:
     *
     * 1. Create a temp table with the new structure (without the dead columns)
     * 2. Copy only the useful columns from the old table
     * 3. Drop the old table and rename the temp one
     *
     * isCustom=1 (user sources) migrate intact 100%
     * isCustom=0 (default sources) also migrate along with their settings (isEnabled)
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS ${TABLE}_new (
                $COL_SEARCH_URL   TEXT PRIMARY KEY NOT NULL,
                $COL_DISPLAY_NAME TEXT NOT NULL,
                $COL_CONTENT_TYPE TEXT NOT NULL,
                $COL_BROWSE_URL   TEXT,
                $COL_IS_CUSTOM    INTEGER NOT NULL DEFAULT 0,
                $COL_IS_ENABLED   INTEGER NOT NULL DEFAULT 1,
                $COL_ADDED_AT     INTEGER NOT NULL DEFAULT 0
            )
        """)

        // Copy the data — dead columns are ignored automatically
        db.execSQL("""
            INSERT OR IGNORE INTO ${TABLE}_new
                ($COL_SEARCH_URL, $COL_DISPLAY_NAME, $COL_CONTENT_TYPE,
                 $COL_BROWSE_URL, $COL_IS_CUSTOM, $COL_IS_ENABLED, $COL_ADDED_AT)
            SELECT
                searchUrlTemplate,
                displayName,
                contentType,
                browseUrlTemplate,
                isCustom,
                isEnabled,
                COALESCE(addedAt, 0)
            FROM $TABLE
        """)

        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        db.execSQL("ALTER TABLE ${TABLE}_new RENAME TO $TABLE")

        // Resolved issue: unnecessary sites. delete LiveWall and WallpaperFlare from existing
        // users' databases where they were previously installed as default sources,
        // since they were removed from DefaultSources.list() as of this version. The
        // isCustom=0 condition protects any source the user added manually with the
        // exact same name — we only delete the non-custom default version.
        if (oldVersion < 18) {
            db.execSQL(
                "DELETE FROM $TABLE WHERE $COL_IS_CUSTOM = 0 AND $COL_DISPLAY_NAME IN ('LiveWall', 'WallpaperFlare')"
            )
        }

        // Previously broken (moved to direct WebView): delete MyLiveWallpapers from existing
        // users' databases now that its custom scraping/ad-bypass workaround is no
        // longer needed. Same isCustom=0 protection as above.
        if (oldVersion < 19) {
            db.execSQL(
                "DELETE FROM $TABLE WHERE $COL_IS_CUSTOM = 0 AND $COL_DISPLAY_NAME = 'MyLiveWallpapers'"
            )
        }
    }
}
