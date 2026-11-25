package com.photototalk.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class SavedScan(val id: Long, val text: String, val photoPath: String, val script: String, val createdAt: Long)

class HistoryDb(context: Context) : SQLiteOpenHelper(context, "photo_to_talk.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE scans (id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, photo_path TEXT NOT NULL, script TEXT NOT NULL, created_at INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    fun add(text: String, path: String, script: String): Long = writableDatabase.insertOrThrow("scans", null, ContentValues().apply {
        put("text", text); put("photo_path", path); put("script", script); put("created_at", System.currentTimeMillis())
    })
    fun all(): List<SavedScan> {
        val result = mutableListOf<SavedScan>()
        readableDatabase.rawQuery("SELECT id, text, photo_path, script, created_at FROM scans ORDER BY created_at DESC", null).use { c ->
            while (c.moveToNext()) result.add(SavedScan(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4)))
        }
        return result
    }
    fun delete(id: Long) = writableDatabase.delete("scans", "id=?", arrayOf(id.toString()))
}
