package com.hivecore.node.data

import com.hivecore.core.JdbcSqlDb
import com.hivecore.core.NodeRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.io.InputStream

/** Access to src/test/resources/fixtures and seeded stores on JdbcSqlDb. */
object Fixtures {
    fun stream(name: String): InputStream =
        Fixtures::class.java.classLoader!!.getResourceAsStream("fixtures/$name") ?: error("missing fixture $name")

    fun text(name: String): String = stream(name).use { it.readBytes().toString(Charsets.UTF_8) }

    /** Copies a fixture to a temp file (XlsxReader needs a File for ZipFile). */
    fun file(name: String): File {
        val f = File.createTempFile("fixture_", "_$name")
        f.deleteOnExit()
        stream(name).use { input -> f.outputStream().use { input.copyTo(it) } }
        return f
    }

    val expected: JsonObject by lazy { Json.parseToJsonElement(text("expected.json")).jsonObject }

    fun expectedLong(vararg path: String): Long {
        var e: JsonObject = expected
        for (p in path.dropLast(1)) e = e[p]!!.jsonObject
        return e[path.last()]!!.jsonPrimitive.long
    }

    /** `expected[section]` as group -> (measure, n). */
    fun expectedGroups(section: String, measure: String): Map<String, Pair<Long, Long>> =
        expected[section]!!.jsonObject.entries.associate { (k, v) -> k to (v.jsonObject[measure]!!.jsonPrimitive.long to v.jsonObject["n"]!!.jsonPrimitive.long) }

    /** The seed assets as the app would read them from assets/seed/. */
    val assets = AssetReader { path -> stream(path.removePrefix("seed/")) }

    fun store(role: NodeRole, db: JdbcSqlDb = JdbcSqlDb.inMemory()): DataStore {
        val s = DataStore(db, assets) { role }
        s.migrate()
        s.seedNow(role)
        return s
    }
}
