package com.example.guiliang

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class WorkRecord(
    val id: Long, val containerNo: String, val workNo: String, val soNo: String,
    val workType: String, val workDate: String, val orderUri: String, val photoUri: String,
    val status: String
)

class RecordDb(context: Context) : SQLiteOpenHelper(context, "guiliang.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE records(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            container_no TEXT NOT NULL, work_no TEXT NOT NULL DEFAULT '', so_no TEXT NOT NULL DEFAULT '',
            work_type TEXT NOT NULL DEFAULT '待识别', work_date TEXT NOT NULL,
            order_uri TEXT NOT NULL DEFAULT '', photo_uri TEXT NOT NULL DEFAULT '', status TEXT NOT NULL DEFAULT '待匹配',
        )""")
        db.execSQL("CREATE INDEX idx_work_date ON records(work_date)")
        db.execSQL("CREATE INDEX idx_container ON records(container_no)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun upsertCandidate(container: String, workNo: String, soNo: String, type: String, date: String, uri: String, isOrder: Boolean) {
        val db = writableDatabase
        val existing = if (workNo.isNotBlank() && soNo.isNotBlank()) {
            db.rawQuery("SELECT id, order_uri, photo_uri FROM records WHERE work_no=? AND so_no=? LIMIT 1", arrayOf(workNo, soNo))
        } else {
            // Pair an order and a container photo by container number when the opposite side is still missing.
            val missingSide = if (isOrder) "photo_uri=''" else "order_uri=''"
            db.rawQuery("SELECT id, order_uri, photo_uri FROM records WHERE container_no=? AND $missingSide ORDER BY id DESC LIMIT 1", arrayOf(container))
        }
        var id = -1L; var orderUri = ""; var photoUri = ""
        if (existing.moveToFirst()) { id = existing.getLong(0); orderUri = existing.getString(1); photoUri = existing.getString(2) }
        existing.close()
        if (id < 0) {
            val cv = ContentValues().apply {
                put("container_no", container); put("work_no", workNo); put("so_no", soNo); put("work_type", type)
                put("work_date", date); put(if (isOrder) "order_uri" else "photo_uri", uri)
                put("status", "待匹配")
            }
            db.insert("records", null, cv)
        } else {
            val cv = ContentValues().apply {
                if (isOrder) put("order_uri", uri) else put("photo_uri", uri)
                if (type != "待识别") put("work_type", type)
                put("status", if ((if (isOrder) uri else orderUri).isNotEmpty() && (if (isOrder) photoUri else uri).isNotEmpty()) "已匹配" else "待匹配")
            }
            db.update("records", cv, "id=?", arrayOf(id.toString()))
        }
    }

    fun all(): List<WorkRecord> {
        val out = mutableListOf<WorkRecord>()
        readableDatabase.rawQuery("SELECT id,container_no,work_no,so_no,work_type,work_date,order_uri,photo_uri,status FROM records ORDER BY work_date DESC", null).use { c ->
            while (c.moveToNext()) out += WorkRecord(c.getLong(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5),c.getString(6),c.getString(7),c.getString(8))
        }
        return out
    }
    fun countMonth(month: String) = readableDatabase.rawQuery("SELECT COUNT(*) FROM records WHERE substr(work_date,1,7)=? AND status='已匹配'", arrayOf(month)).use { it.moveToFirst(); it.getInt(0) }
    fun countDay(day: String) = readableDatabase.rawQuery("SELECT COUNT(*) FROM records WHERE work_date=? AND status='已匹配'", arrayOf(day)).use { it.moveToFirst(); it.getInt(0) }
}
