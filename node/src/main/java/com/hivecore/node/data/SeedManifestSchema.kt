package com.hivecore.node.data

import com.hivecore.core.ColType
import com.hivecore.core.ColumnDef
import com.hivecore.core.DocDef
import com.hivecore.core.NodeRole
import com.hivecore.core.Schema
import com.hivecore.core.TableDef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The [Schema] a role would hold right after seeding, read from `seed/seed_manifest.json` alone (names, row counts,
 * flags and aliases): what Demo mode's scripted phones show on the Data screen and hand the planner, so the demo and
 * the real seed never drift apart. [DataStore.seedFromManifest] applies the same file to the real store.
 */
object SeedManifestSchema {
    private val json = Json { ignoreUnknownKeys = true }

    fun schema(role: NodeRole, manifestJson: String): Schema {
        val root = json.parseToJsonElement(manifestJson).jsonObject
        val roleObj = root["roles"]?.jsonObject?.get(role.name)?.jsonObject ?: return Schema(role, emptyList(), emptyList())
        val tables = root["tables"]?.jsonObject.orEmpty()
        val docs = root["docs"]?.jsonObject.orEmpty()
        fun strs(o: Map<String, kotlinx.serialization.json.JsonElement>, k: String) = o[k]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        fun flag(o: Map<String, kotlinx.serialization.json.JsonElement>, k: String) = o[k]?.jsonPrimitive?.booleanOrNull == true
        val tableDefs = strs(roleObj, "tables").mapNotNull { name ->
            val t = tables[name]?.jsonObject ?: return@mapNotNull null
            TableDef(
                name = name,
                columns = t["columns"]?.jsonArray?.map { c ->
                    val o = c.jsonObject
                    ColumnDef(o.getValue("name").jsonPrimitive.content, ColType.valueOf(o["type"]?.jsonPrimitive?.content ?: "TEXT"),
                        confidential = flag(o, "confidential"), identifier = flag(o, "identifier"), groupable = flag(o, "groupable"), aliases = strs(o, "aliases"))
                } ?: emptyList(),
                rowCount = t["rows"]?.jsonPrimitive?.longOrNull ?: 0L,
                aliases = strs(t, "aliases"),
            )
        }
        val docDefs = strs(roleObj, "docs").mapNotNull { name ->
            val d = docs[name]?.jsonObject ?: return@mapNotNull null
            val units = d["units"]?.jsonPrimitive?.intOrNull ?: 0
            DocDef("doc_" + name.substringBeforeLast('.').lowercase(), name, d["kind"]?.jsonPrimitive?.content ?: name.substringAfterLast('.'),
                units, d["unitLabel"]?.jsonPrimitive?.content ?: "unit", d["chunks"]?.jsonPrimitive?.intOrNull ?: ((units + 4) / 5), flag(d, "confidential"))
        }
        return Schema(role, tableDefs, docDefs)
    }
}
