package com.plate.launcher.workshop.data

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
 * WorkshopSourceDao — a pure SQLite version with no Room
 *
 * The exact same interface as before: no code changes needed in
 * WorkshopFragment or WorkshopSourcesActivity.
 */
class WorkshopSourceDao(context: Context) {

    private val db = WorkshopDatabase.get(context)

    // ── Read ────────────────────────────────────────────────────────────────

    fun observeByType(contentType: String): Flow<List<WorkshopSource>> = callbackFlow {
        trySend(getByTypeSync(contentType))
        awaitClose {}
    }.flowOn(Dispatchers.IO)

    suspend fun getAllEnabled(): List<WorkshopSource> = withContext(Dispatchers.IO) {
        val list = mutableListOf<WorkshopSource>()
        db.readableDatabase.query(
            WorkshopDatabase.TABLE, null,
            "${WorkshopDatabase.COL_IS_ENABLED} = 1", null,
            null, null, null
        )?.use { c -> while (c.moveToNext()) c.toSource()?.let { list.add(it) } }
        list
    }

    fun observeAll(): Flow<List<WorkshopSource>> = callbackFlow {
        trySend(getAllSync())
        awaitClose {}
    }.flowOn(Dispatchers.IO)

    suspend fun count(): Int = withContext(Dispatchers.IO) {
        db.readableDatabase
            .rawQuery("SELECT COUNT(*) FROM ${WorkshopDatabase.TABLE}", null)
            ?.use { if (it.moveToFirst()) it.getInt(0) else 0 } ?: 0
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Deletes only the default sources (isCustom=0) —
     * user sources (isCustom=1) are never touched
     */
    suspend fun deleteDefaultSources() = withContext(Dispatchers.IO) {
        db.writableDatabase.delete(
            WorkshopDatabase.TABLE,
            "${WorkshopDatabase.COL_IS_CUSTOM} = 0",
            null
        )
    }

    suspend fun insertAll(sources: List<WorkshopSource>) = withContext(Dispatchers.IO) {
        val wdb = db.writableDatabase
        wdb.beginTransaction()
        try {
            sources.forEach { src ->
                wdb.insertWithOnConflict(
                    WorkshopDatabase.TABLE, null,
                    src.toContentValues(),
                    android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            wdb.setTransactionSuccessful()
        } finally {
            wdb.endTransaction()
        }
    }

    /** Adds a custom source — IGNORE to prevent duplicates, returns -1 if it already exists */
    suspend fun addCustomSource(source: WorkshopSource): Long = withContext(Dispatchers.IO) {
        db.writableDatabase.insertWithOnConflict(
            WorkshopDatabase.TABLE, null,
            source.toContentValues(),
            android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    suspend fun delete(source: WorkshopSource) = withContext(Dispatchers.IO) {
        db.writableDatabase.delete(
            WorkshopDatabase.TABLE,
            "${WorkshopDatabase.COL_SEARCH_URL} = ?",
            arrayOf(source.searchUrlTemplate)
        )
    }

    suspend fun setEnabled(url: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(WorkshopDatabase.COL_IS_ENABLED, if (enabled) 1 else 0)
        }
        db.writableDatabase.update(
            WorkshopDatabase.TABLE, values,
            "${WorkshopDatabase.COL_SEARCH_URL} = ?",
            arrayOf(url)
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun getAllSync(): List<WorkshopSource> {
        val list = mutableListOf<WorkshopSource>()
        db.readableDatabase.query(
            WorkshopDatabase.TABLE, null,
            null, null, null, null,
            "${WorkshopDatabase.COL_ADDED_AT} DESC"
        )?.use { c -> while (c.moveToNext()) c.toSource()?.let { list.add(it) } }
        return list
    }

    private fun getByTypeSync(contentType: String): List<WorkshopSource> {
        val list = mutableListOf<WorkshopSource>()
        db.readableDatabase.query(
            WorkshopDatabase.TABLE, null,
            "${WorkshopDatabase.COL_CONTENT_TYPE} = ? AND ${WorkshopDatabase.COL_IS_ENABLED} = 1",
            arrayOf(contentType), null, null, null
        )?.use { c -> while (c.moveToNext()) c.toSource()?.let { list.add(it) } }
        return list
    }

    // ── Cursor → WorkshopSource ───────────────────────────────────────────────

    private fun Cursor.toSource(): WorkshopSource? = try {
        WorkshopSource(
            searchUrlTemplate = getString(getColumnIndexOrThrow(WorkshopDatabase.COL_SEARCH_URL)),
            displayName       = getString(getColumnIndexOrThrow(WorkshopDatabase.COL_DISPLAY_NAME)),
            contentType       = getString(getColumnIndexOrThrow(WorkshopDatabase.COL_CONTENT_TYPE)),
            browseUrlTemplate = getString(getColumnIndexOrThrow(WorkshopDatabase.COL_BROWSE_URL)),
            isCustom          = getInt(getColumnIndexOrThrow(WorkshopDatabase.COL_IS_CUSTOM)) != 0,
            isEnabled         = getInt(getColumnIndexOrThrow(WorkshopDatabase.COL_IS_ENABLED)) != 0,
            addedAt           = getLong(getColumnIndexOrThrow(WorkshopDatabase.COL_ADDED_AT))
        )
    } catch (_: Exception) { null }

    // ── WorkshopSource → ContentValues ───────────────────────────────────────

    private fun WorkshopSource.toContentValues() = ContentValues().apply {
        put(WorkshopDatabase.COL_SEARCH_URL,   searchUrlTemplate)
        put(WorkshopDatabase.COL_DISPLAY_NAME, displayName)
        put(WorkshopDatabase.COL_CONTENT_TYPE, contentType)
        put(WorkshopDatabase.COL_BROWSE_URL,   browseUrlTemplate)
        put(WorkshopDatabase.COL_IS_CUSTOM,    if (isCustom) 1 else 0)
        put(WorkshopDatabase.COL_IS_ENABLED,   if (isEnabled) 1 else 0)
        put(WorkshopDatabase.COL_ADDED_AT,     addedAt)
    }
}
