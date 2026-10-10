package com.hivecore.core

import java.io.File
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.Types

/**
 * [SqlDb] over sqlite-jdbc for JVM unit tests. Typed binds (setLong / setDouble / setString / setNull) and the
 * same value mapping as AndroidSqlDb: INTEGER → Long, REAL → Double, TEXT → String, NULL → null.
 */
class JdbcSqlDb(val conn: Connection) : SqlDb {

    companion object {
        fun inMemory(): JdbcSqlDb = JdbcSqlDb(DriverManager.getConnection("jdbc:sqlite::memory:"))
        fun open(file: File): JdbcSqlDb {
            file.parentFile?.mkdirs()
            return JdbcSqlDb(DriverManager.getConnection("jdbc:sqlite:" + file.absolutePath.replace('\\', '/')))
        }

        fun bindAll(st: PreparedStatement, args: List<Any?>) {
            args.forEachIndexed { i, a ->
                val idx = i + 1
                when (a) {
                    null -> st.setNull(idx, Types.NULL)
                    is Long -> st.setLong(idx, a)
                    is Int -> st.setLong(idx, a.toLong())
                    is Short -> st.setLong(idx, a.toLong())
                    is Byte -> st.setLong(idx, a.toLong())
                    is Boolean -> st.setLong(idx, if (a) 1L else 0L)
                    is Double -> st.setDouble(idx, a)
                    is Float -> st.setDouble(idx, a.toDouble())
                    is String -> st.setString(idx, a)
                    is ByteArray -> st.setBytes(idx, a)
                    else -> st.setString(idx, a.toString())
                }
            }
        }
    }

    override fun exec(sql: String, args: List<Any?>) {
        conn.prepareStatement(sql).use { st ->
            bindAll(st, args)
            st.execute()
        }
    }

    override fun query(sql: String, args: List<Any?>): List<Map<String, Any?>> {
        conn.prepareStatement(sql).use { st ->
            bindAll(st, args)
            st.executeQuery().use { rs ->
                val md = rs.metaData
                val names = (1..md.columnCount).map { md.getColumnLabel(it) }
                val out = ArrayList<Map<String, Any?>>()
                while (rs.next()) {
                    val row = LinkedHashMap<String, Any?>(names.size)
                    for (i in names.indices) {
                        row[names[i]] = when (val v = rs.getObject(i + 1)) {
                            null -> null
                            is Int -> v.toLong()
                            is Long -> v
                            is Short -> v.toLong()
                            is Double -> v
                            is Float -> v.toDouble()
                            is BigDecimal -> v.toDouble()
                            is String -> v
                            is ByteArray -> v
                            else -> v.toString()
                        }
                    }
                    out.add(row)
                }
                return out
            }
        }
    }

    override fun <T> tx(block: () -> T): T {
        val prev = conn.autoCommit
        conn.autoCommit = false
        try {
            val r = block()
            conn.commit()
            return r
        } catch (t: Throwable) {
            conn.rollback()
            throw t
        } finally {
            conn.autoCommit = prev
        }
    }

    fun close() = conn.close()
}
