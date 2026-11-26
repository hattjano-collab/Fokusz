package hu.fokusz

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal class SocialUnlockArchive private constructor(context: Context) :
    SQLiteOpenHelper(context, "fokusz_unlock_archive.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE unlocks (id TEXT PRIMARY KEY, started INTEGER NOT NULL, grant_json TEXT NOT NULL, used INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX unlocks_started ON unlocks(started DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun record(id: String, started: Long, json: String, used: Int) {
        val result = writableDatabase.insertWithOnConflict("unlocks", null, ContentValues().apply {
            put("id", id)
            put("started", started)
            put("grant_json", json)
            put("used", used)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        check(result != -1L) { "A feloldási előzményt nem sikerült menteni." }
    }

    fun rows(): List<Pair<String, Int>> = readableDatabase.query(
        "unlocks", arrayOf("grant_json", "used"), null, null, null, null, "started DESC"
    ).use { rows ->
        buildList {
            while (rows.moveToNext()) add(rows.getString(0) to rows.getInt(1))
        }
    }

    companion object {
        @Volatile private var instance: SocialUnlockArchive? = null

        fun get(c: Context): SocialUnlockArchive = instance ?: synchronized(this) {
            instance ?: SocialUnlockArchive(c.applicationContext).also { instance = it }
        }
    }
}
