package com.plate.launcher.livewallpaper.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * YoukiWallpaperDatabase — a SQLiteOpenHelper version without Room/KAPT
 *
 * We replaced Room with plain SQLiteOpenHelper because the on-device build
 * (rv2ide) doesn't support the libsqlitejdbc library Room needs during compile.
 *
 * The data already on the device in youki_wallpaper.db is fully preserved;
 * onUpgrade handles any necessary migration.
 */
class YoukiWallpaperDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "youki_wallpaper.db"
        const val DATABASE_VERSION = 4  // v4: added COL_MAX_FPS

        const val TABLE = "wallpaper_configs"

        // ── Table columns (matching Room's exact names) ───────────────────────────
        const val COL_VIDEO_URI              = "videoUri"
        const val COL_SCALE_MODE             = "scaleMode"
        const val COL_FREE_SCALE_X           = "freeScaleX"
        const val COL_FREE_SCALE_Y           = "freeScaleY"
        const val COL_TRANSLATE_X            = "translateX"
        const val COL_TRANSLATE_Y            = "translateY"
        const val COL_ROTATION_DEG           = "rotationDeg"
        const val COL_FLIP_H                 = "flipHorizontal"
        const val COL_FLIP_V                 = "flipVertical"
        const val COL_FREE_SCALE_X_L         = "freeScaleXLandscape"
        const val COL_FREE_SCALE_Y_L         = "freeScaleYLandscape"
        const val COL_TRANSLATE_X_L          = "translateXLandscape"
        const val COL_TRANSLATE_Y_L          = "translateYLandscape"
        const val COL_ROTATION_DEG_L         = "rotationDegLandscape"
        const val COL_FLIP_H_L               = "flipHorizontalLandscape"
        const val COL_FLIP_V_L               = "flipVerticalLandscape"
        const val COL_BG_COLOR               = "backgroundColor"
        const val COL_COLOR_CORRECTION       = "colorCorrectionEnabled"
        const val COL_BRIGHTNESS             = "brightness"
        const val COL_CONTRAST               = "contrast"
        const val COL_SATURATION             = "saturation"
        // Column kept in the schema (SQLite DROP COLUMN needs a full table
        // rebuild) but no longer read into WallpaperConfig or written to by
        // WallpaperConfigDao — see WallpaperConfig.kt's comment on this
        // field's removal. Existing rows keep old values; new rows get
        // SQL_CREATE's DEFAULT 60 and nothing ever updates it afterward.
        const val COL_FPS_LIMIT              = "fpsLimit"
        const val COL_PLAYBACK_SPEED         = "playbackSpeed"
        const val COL_MAX_FPS                = "maxFps"
        const val COL_MUTED                  = "muted"
        const val COL_AUDIO_PITCH            = "audioPitch"
        const val COL_PLAYBACK_SPEED_L       = "playbackSpeedLandscape"
        const val COL_MUTED_L                = "mutedLandscape"
        const val COL_AUDIO_PITCH_L          = "audioPitchLandscape"
        const val COL_IS_ACTIVE              = "isActive"
        const val COL_CREATED_AT             = "createdAt"

        private const val SQL_CREATE = """
            CREATE TABLE IF NOT EXISTS $TABLE (
                $COL_VIDEO_URI          TEXT PRIMARY KEY NOT NULL,
                $COL_SCALE_MODE         TEXT NOT NULL DEFAULT 'COVER',
                $COL_FREE_SCALE_X       REAL NOT NULL DEFAULT 1.0,
                $COL_FREE_SCALE_Y       REAL NOT NULL DEFAULT 1.0,
                $COL_TRANSLATE_X        REAL NOT NULL DEFAULT 0.0,
                $COL_TRANSLATE_Y        REAL NOT NULL DEFAULT 0.0,
                $COL_ROTATION_DEG       REAL NOT NULL DEFAULT 0.0,
                $COL_FLIP_H             INTEGER NOT NULL DEFAULT 0,
                $COL_FLIP_V             INTEGER NOT NULL DEFAULT 0,
                $COL_FREE_SCALE_X_L     REAL NOT NULL DEFAULT 1.0,
                $COL_FREE_SCALE_Y_L     REAL NOT NULL DEFAULT 1.0,
                $COL_TRANSLATE_X_L      REAL NOT NULL DEFAULT 0.0,
                $COL_TRANSLATE_Y_L      REAL NOT NULL DEFAULT 0.0,
                $COL_ROTATION_DEG_L     REAL NOT NULL DEFAULT 0.0,
                $COL_FLIP_H_L           INTEGER NOT NULL DEFAULT 0,
                $COL_FLIP_V_L           INTEGER NOT NULL DEFAULT 0,
                $COL_BG_COLOR           TEXT NOT NULL DEFAULT '#FF000000',
                $COL_COLOR_CORRECTION   INTEGER NOT NULL DEFAULT 0,
                $COL_BRIGHTNESS         REAL NOT NULL DEFAULT 1.0,
                $COL_CONTRAST           REAL NOT NULL DEFAULT 1.0,
                $COL_SATURATION         REAL NOT NULL DEFAULT 1.0,
                $COL_FPS_LIMIT          INTEGER NOT NULL DEFAULT 60,
                $COL_PLAYBACK_SPEED     REAL NOT NULL DEFAULT 1.0,
                $COL_MAX_FPS            INTEGER NOT NULL DEFAULT 0,
                $COL_MUTED              INTEGER NOT NULL DEFAULT 1,
                $COL_AUDIO_PITCH        REAL NOT NULL DEFAULT 1.0,
                $COL_PLAYBACK_SPEED_L   REAL NOT NULL DEFAULT 1.0,
                $COL_MUTED_L            INTEGER NOT NULL DEFAULT 1,
                $COL_AUDIO_PITCH_L      REAL NOT NULL DEFAULT 1.0,
                $COL_IS_ACTIVE          INTEGER NOT NULL DEFAULT 0,
                $COL_CREATED_AT         INTEGER NOT NULL DEFAULT 0
            )
        """

        @Volatile private var instance: YoukiWallpaperDatabase? = null

        fun get(context: Context): YoukiWallpaperDatabase =
            instance ?: synchronized(this) {
                instance ?: YoukiWallpaperDatabase(context.applicationContext)
                    .also { instance = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(SQL_CREATE)
    }

    /**
     * A safe migration that adds the new columns without deleting any data.
     * Handles old Room databases (v1, v2) with the same table.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Make sure the table already exists
        db.execSQL(SQL_CREATE)

        // Columns added in v2
        val v2Columns = listOf(
            COL_FREE_SCALE_X_L   to "REAL NOT NULL DEFAULT 1.0",
            COL_FREE_SCALE_Y_L   to "REAL NOT NULL DEFAULT 1.0",
            COL_TRANSLATE_X_L    to "REAL NOT NULL DEFAULT 0.0",
            COL_TRANSLATE_Y_L    to "REAL NOT NULL DEFAULT 0.0",
            COL_ROTATION_DEG_L   to "REAL NOT NULL DEFAULT 0.0",
            COL_FLIP_H_L         to "INTEGER NOT NULL DEFAULT 0",
            COL_FLIP_V_L         to "INTEGER NOT NULL DEFAULT 0"
        )

        // Columns added in v3
        val v3Columns = listOf(
            COL_PLAYBACK_SPEED_L to "REAL NOT NULL DEFAULT 1.0",
            COL_MUTED_L          to "INTEGER NOT NULL DEFAULT 1",
            COL_AUDIO_PITCH      to "REAL NOT NULL DEFAULT 1.0",
            COL_AUDIO_PITCH_L    to "REAL NOT NULL DEFAULT 1.0"
        )

        // Columns added in v4
        val v4Columns = listOf(
            COL_MAX_FPS to "INTEGER NOT NULL DEFAULT 0"
        )

        val columnsToAdd = when {
            oldVersion < 2 -> v2Columns + v3Columns + v4Columns
            oldVersion < 3 -> v3Columns + v4Columns
            oldVersion < 4 -> v4Columns
            else           -> emptyList()
        }

        // Add each new column safely (ignored if the column already exists)
        columnsToAdd.forEach { (col, def) ->
            try {
                db.execSQL("ALTER TABLE $TABLE ADD COLUMN $col $def")
            } catch (_: Exception) { /* Column already exists — ignore */ }
        }
    }
}
