package com.hivecore.core

import android.database.Cursor
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteProgram
import java.io.File

// Plan 6 "core/SqlDb.kt" — frozen after Wave 0.
interface SqlDb {
    fun exec(sql: String, args: List<Any?> = emptyList())
    fun query(sql: String, args: List<Any?> = emptyList()): List<Map<String, Any?>>   // values: Long | Double | String | null
    fun <T> tx(block: () -> T): T
}

/**
 * [SqlDb] over the framework SQLite. Arguments are bound by type ([SQLiteProgram.bindLong] / bindDouble / bindString /
 * bindNull), never as Array<String>, so `WHERE salary > ?` compares numbers, not text. Queries go through
 * [SQLiteDatabase.rawQueryWithFactory] (the factory binds on the SQLiteQuery before the cursor runs); statements go
 * through [SQLiteDatabase.compileStatement]. The JVM twin is JdbcSqlDb in src/test; both return identical rows.
 */
class AndroidSqlDb(val db: SQLiteDatabase) : SqlDb {

    companion object {
        fun open(file: File): AndroidSqlDb {
            file.parentFile?.mkdirs()
            val db = SQLiteDatabase.openOrCreateDatabase(file, null)
            // Not execSQL("PRAGMA journal_mode=WAL"): that PRAGMA returns a row and execSQL throws on row-returning
            // statements (it crashed the first install). The framework helpers do the same thing safely.
            db.enableWriteAheadLogging()
            db.setForeignKeyConstraintsEnabled(true)
            return AndroidSqlDb(db)
        }
        fun inMemory(): AndroidSqlDb = AndroidSqlDb(SQLiteDatabase.create(null))

        fun bindAll(program: SQLiteProgram, args: List<Any?>) {
            args.forEachIndexed { i, a ->
                val idx = i + 1
                when (a) {
                    null -> program.bindNull(idx)
                    is Long -> program.bindLong(idx, a)
                    is Int -> program.bindLong(idx, a.toLong())
                    is Short -> program.bindLong(idx, a.toLong())
                    is Byte -> program.bindLong(idx, a.toLong())
                    is Boolean -> program.bindLong(idx, if (a) 1L else 0L)
                    is Double -> program.bindDouble(idx, a)
                    is Float -> program.bindDouble(idx, a.toDouble())
                    is String -> program.bindString(idx, a)
                    is ByteArray -> program.bindBlob(idx, a)
                    else -> program.bindString(idx, a.toString())
                }
            }
        }
    }

    override fun exec(sql: String, args: List<Any?>) {
        val st = db.compileStatement(sql)
        try {
            bindAll(st, args)
            st.execute()
        } finally {
            st.close()
        }
    }

    override fun query(sql: String, args: List<Any?>): List<Map<String, Any?>> {
        val cursor = db.rawQueryWithFactory(
            { _, driver, editTable, query ->
                bindAll(query, args)
                SQLiteCursor(driver, editTable, query)
            },
            sql, null, ""   // selectionArgs: none (bound by type above); editTable: unused by read-only cursors
        )
        cursor.use { c ->
            val names = c.columnNames
            val out = ArrayList<Map<String, Any?>>(c.count)
            while (c.moveToNext()) {
                val row = LinkedHashMap<String, Any?>(names.size)
                for (i in names.indices) {
                    row[names[i]] = when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                        Cursor.FIELD_TYPE_STRING -> c.getString(i)
                        Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)   // not part of the contract; never produced by our tables
                        else -> c.getString(i)
                    }
                }
                out.add(row)
            }
            return out
        }
    }

    override fun <T> tx(block: () -> T): T {
        db.beginTransaction()
        try {
            val r = block()
            db.setTransactionSuccessful()
            return r
        } finally {
            db.endTransaction()
        }
    }

    fun close() = db.close()
}
