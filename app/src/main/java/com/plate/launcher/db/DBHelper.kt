package com.plate.launcher.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.provider.BaseColumns
import com.plate.launcher.db.DatabaseContract.LaunchModesTable

// Gap 63: cursor leak on update — the cursor wasn't closed inside the if branch
// Gap 64: onUpgrade was empty — we added a basic migration
// Gap 71: cursor leak when count == 0 in getLaunchMode
// Gap 75: DBHelper used to be created as a new instance on every call — now it's a singleton
class DBHelper private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_VERSION = 2
        private const val DATABASE_NAME = "smartdock.db"
        private const val SQL_CREATE_MODES_ENTRIES =
            "CREATE TABLE ${LaunchModesTable.TABLE_NAME} (" +
            "${BaseColumns._ID} INTEGER PRIMARY KEY NOT NULL," +
            "${LaunchModesTable.COLUMN_PACKAGE_NAME} TEXT NOT NULL," +
            "${LaunchModesTable.COLUMN_LAUNCH_MODE} TEXT NOT NULL)"

        // Gap 75: singleton — instead of creating a new instance on every call
        @Volatile private var instance: DBHelper? = null
        fun getInstance(ctx: Context): DBHelper =
            instance ?: synchronized(this) {
                instance ?: DBHelper(ctx.applicationContext).also { instance = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(SQL_CREATE_MODES_ENTRIES)
    }

    // Gap 64: a real migration instead of an empty body
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // We'll try adding new columns here if any come up in the future
            // For now we just make sure the table exists
            try { db.execSQL(SQL_CREATE_MODES_ENTRIES) } catch (e: Exception) {}
        }
    }

    fun saveLaunchMode(app: String, mode: String) {
        try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put(LaunchModesTable.COLUMN_PACKAGE_NAME, app)
                put(LaunchModesTable.COLUMN_LAUNCH_MODE, mode)
            }
            val selection = "${LaunchModesTable.COLUMN_PACKAGE_NAME} = ?"
            val selectionArgs = arrayOf(app)

            // Gap 63: using use{} guarantees the cursor closes in every case
            db.query(LaunchModesTable.TABLE_NAME, null, selection, selectionArgs, null, null, null)
                ?.use { cursor ->
                    if (cursor.count > 0) db.update(LaunchModesTable.TABLE_NAME, values, selection, selectionArgs)
                    else db.insert(LaunchModesTable.TABLE_NAME, null, values)
                }
        } catch (e: Exception) {}
    }

    fun getLaunchMode(app: String): String? {
        return try {
            val db = readableDatabase
            val selection = "${LaunchModesTable.COLUMN_PACKAGE_NAME} = ?"

            // Gap 71: using use{} guarantees the cursor closes even if count == 0
            db.query(LaunchModesTable.TABLE_NAME, null, selection, arrayOf(app), null, null, null)
                ?.use { cursor ->
                    if (cursor.count > 0 && cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(LaunchModesTable.COLUMN_LAUNCH_MODE)
                        if (index >= 0) cursor.getString(index) else null
                    } else null
                }
        } catch (e: Exception) { null }
    }
}
