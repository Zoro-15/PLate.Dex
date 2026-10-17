package com.plate.launcher.livewallpaper.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * WallpaperConfigDao — a pure SQLite version with no Room
 *
 * Keeps the exact same interface (suspend functions + Flow) so no code
 * changes are needed in the Repository or the Fragments.
 */
class WallpaperConfigDao(context: Context) {

    private val db = YoukiWallpaperDatabase.get(context)

    // ── Read a single row ────────────────────────────────────────────────────────

    suspend fun get(videoUri: String): WallpaperConfig? = withContext(Dispatchers.IO) {
        db.readableDatabase.query(
            YoukiWallpaperDatabase.TABLE, null,
            "${YoukiWallpaperDatabase.COL_VIDEO_URI} = ?", arrayOf(videoUri),
            null, null, null
        )?.use { it.toConfigOrNull() }
    }

    suspend fun getActive(): WallpaperConfig? = withContext(Dispatchers.IO) {
        db.readableDatabase.query(
            YoukiWallpaperDatabase.TABLE, null,
            "${YoukiWallpaperDatabase.COL_IS_ACTIVE} = 1", null,
            null, null, null, "1"
        )?.use { it.toConfigOrNull() }
    }

    suspend fun getMostRecent(): WallpaperConfig? = withContext(Dispatchers.IO) {
        db.readableDatabase.query(
            YoukiWallpaperDatabase.TABLE, null,
            null, null, null, null,
            "${YoukiWallpaperDatabase.COL_CREATED_AT} DESC", "1"
        )?.use { it.toConfigOrNull() }
    }

    // ── Flow (emits once, then is re-invoked manually on change) ─────────
    // Note: plain SQLite has no built-in observer like Room,
    // so we emit the current value immediately and keep the Flow open.
    // The Repository uses a StateFlow to distribute updates to the UI.

    fun observe(videoUri: String): Flow<WallpaperConfig?> = callbackFlow {
        trySend(get(videoUri))
        awaitClose {}
    }.flowOn(Dispatchers.IO)

    fun observeActive(): Flow<WallpaperConfig?> = callbackFlow {
        trySend(getActive())
        awaitClose {}
    }.flowOn(Dispatchers.IO)

    fun observeAll(): Flow<List<WallpaperConfig>> = callbackFlow {
        trySend(getAllSync())
        awaitClose {}
    }.flowOn(Dispatchers.IO)

    // ── Write / delete ──────────────────────────────────────────────────────────

    suspend fun upsert(config: WallpaperConfig) = withContext(Dispatchers.IO) {
        db.writableDatabase.insertWithOnConflict(
            YoukiWallpaperDatabase.TABLE,
            null,
            config.toContentValues(),
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    suspend fun delete(videoUri: String) = withContext(Dispatchers.IO) {
        db.writableDatabase.delete(
            YoukiWallpaperDatabase.TABLE,
            "${YoukiWallpaperDatabase.COL_VIDEO_URI} = ?",
            arrayOf(videoUri)
        )
    }

    suspend fun clearActiveFlag() = withContext(Dispatchers.IO) {
        val values = ContentValues().apply { put(YoukiWallpaperDatabase.COL_IS_ACTIVE, 0) }
        db.writableDatabase.update(
            YoukiWallpaperDatabase.TABLE, values,
            "${YoukiWallpaperDatabase.COL_IS_ACTIVE} = 1", null
        )
    }

    suspend fun setActive(videoUri: String) {
        clearActiveFlag()
        val existing = get(videoUri) ?: WallpaperConfig.default(videoUri)
        upsert(existing.copy(isActive = true))
    }

    suspend fun getAll(): List<WallpaperConfig> = withContext(Dispatchers.IO) { getAllSync() }

    // ── Private helpers ─────────────────────────────────────────────────────────

    private fun getAllSync(): List<WallpaperConfig> {
        val list = mutableListOf<WallpaperConfig>()
        db.readableDatabase.query(
            YoukiWallpaperDatabase.TABLE, null,
            null, null, null, null,
            "${YoukiWallpaperDatabase.COL_CREATED_AT} DESC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                cursor.toConfig()?.let { list.add(it) }
            }
        }
        return list
    }

    // ── Cursor → WallpaperConfig conversion ───────────────────────────────────────

    private fun Cursor.toConfigOrNull(): WallpaperConfig? =
        if (moveToFirst()) toConfig() else null

    private fun Cursor.toConfig(): WallpaperConfig? = try {
        fun str(col: String)  = getString(getColumnIndexOrThrow(col))
        fun flt(col: String)  = getFloat(getColumnIndexOrThrow(col))
        fun bool(col: String) = getInt(getColumnIndexOrThrow(col)) != 0
        fun int(col: String)  = getInt(getColumnIndexOrThrow(col))
        fun lng(col: String)  = getLong(getColumnIndexOrThrow(col))

        with(YoukiWallpaperDatabase) {
            WallpaperConfig(
                videoUri              = str(COL_VIDEO_URI),
                scaleMode             = str(COL_SCALE_MODE),
                freeScaleX            = flt(COL_FREE_SCALE_X),
                freeScaleY            = flt(COL_FREE_SCALE_Y),
                translateX            = flt(COL_TRANSLATE_X),
                translateY            = flt(COL_TRANSLATE_Y),
                rotationDeg           = flt(COL_ROTATION_DEG),
                flipHorizontal        = bool(COL_FLIP_H),
                flipVertical          = bool(COL_FLIP_V),
                freeScaleXLandscape   = flt(COL_FREE_SCALE_X_L),
                freeScaleYLandscape   = flt(COL_FREE_SCALE_Y_L),
                translateXLandscape   = flt(COL_TRANSLATE_X_L),
                translateYLandscape   = flt(COL_TRANSLATE_Y_L),
                rotationDegLandscape  = flt(COL_ROTATION_DEG_L),
                flipHorizontalLandscape = bool(COL_FLIP_H_L),
                flipVerticalLandscape = bool(COL_FLIP_V_L),
                backgroundColor       = str(COL_BG_COLOR),
                colorCorrectionEnabled= bool(COL_COLOR_CORRECTION),
                brightness            = flt(COL_BRIGHTNESS),
                contrast              = flt(COL_CONTRAST),
                saturation            = flt(COL_SATURATION),
                playbackSpeed         = flt(COL_PLAYBACK_SPEED),
                maxFps                = int(COL_MAX_FPS),
                muted                 = bool(COL_MUTED),
                audioPitch            = flt(COL_AUDIO_PITCH),
                playbackSpeedLandscape= flt(COL_PLAYBACK_SPEED_L),
                mutedLandscape        = bool(COL_MUTED_L),
                audioPitchLandscape   = flt(COL_AUDIO_PITCH_L),
                isActive              = bool(COL_IS_ACTIVE),
                createdAt             = lng(COL_CREATED_AT)
            )
        }
    } catch (_: Exception) { null }

    // ── WallpaperConfig → ContentValues conversion ────────────────────────────────

    private fun WallpaperConfig.toContentValues() = ContentValues().apply {
        with(YoukiWallpaperDatabase) {
            put(COL_VIDEO_URI,             videoUri)
            put(COL_SCALE_MODE,            scaleMode)
            put(COL_FREE_SCALE_X,          freeScaleX)
            put(COL_FREE_SCALE_Y,          freeScaleY)
            put(COL_TRANSLATE_X,           translateX)
            put(COL_TRANSLATE_Y,           translateY)
            put(COL_ROTATION_DEG,          rotationDeg)
            put(COL_FLIP_H,                if (flipHorizontal) 1 else 0)
            put(COL_FLIP_V,                if (flipVertical) 1 else 0)
            put(COL_FREE_SCALE_X_L,        freeScaleXLandscape)
            put(COL_FREE_SCALE_Y_L,        freeScaleYLandscape)
            put(COL_TRANSLATE_X_L,         translateXLandscape)
            put(COL_TRANSLATE_Y_L,         translateYLandscape)
            put(COL_ROTATION_DEG_L,        rotationDegLandscape)
            put(COL_FLIP_H_L,              if (flipHorizontalLandscape) 1 else 0)
            put(COL_FLIP_V_L,              if (flipVerticalLandscape) 1 else 0)
            put(COL_BG_COLOR,              backgroundColor)
            put(COL_COLOR_CORRECTION,      if (colorCorrectionEnabled) 1 else 0)
            put(COL_BRIGHTNESS,            brightness)
            put(COL_CONTRAST,              contrast)
            put(COL_SATURATION,            saturation)
            // COL_FPS_LIMIT intentionally not written anymore — see
            // WallpaperConfig.kt's comment on why the field itself was
            // removed. The database column stays (SQLite DROP COLUMN needs
            // a full table rebuild — not worth the migration risk for a
            // column nothing reads), it just never receives a new value
            // from this point on; existing rows keep whatever was last
            // written before this change.
            put(COL_PLAYBACK_SPEED,        playbackSpeed)
            put(COL_MAX_FPS,                maxFps)
            put(COL_MUTED,                 if (muted) 1 else 0)
            put(COL_AUDIO_PITCH,           audioPitch)
            put(COL_PLAYBACK_SPEED_L,      playbackSpeedLandscape)
            put(COL_MUTED_L,               if (mutedLandscape) 1 else 0)
            put(COL_AUDIO_PITCH_L,         audioPitchLandscape)
            put(COL_IS_ACTIVE,             if (isActive) 1 else 0)
            put(COL_CREATED_AT,            createdAt)
        }
    }
}
