package com.hivecore.planner

import com.hivecore.core.Catalog
import com.hivecore.core.CatalogTable
import com.hivecore.core.ColType
import com.hivecore.core.Dag
import com.hivecore.core.Op
import com.hivecore.core.TaskSpec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Swarm plan §5.1: the versioned decomposition patterns (assets/swarm/patterns.json) and slot binding → core Dag.

/** Slot types; the planner fills each from catalog vocabulary or question substrings only. */
object SlotTypes {
    /** a numeric, non-identifier column named in the question (name or alias) */
    const val MEASURE = "measure"
    /** a table named in the question (name or alias): what is counted */
    const val COUNT = "count"
    /** a groupable column named in the question */
    const val DIMENSION = "dimension"
    /** a date phrase from the fixed vocabulary ("this year", "Q3", "October", "2026", ...) */
    const val PERIOD = "period"
    /** a small integer written in the question ("top 2") */
    const val K = "k"
    /** "desc" | "asc" from a cue word ("highest", "lowest") */
    const val ORDER = "order"
    /** "gt" | "lt" from a cue word ("above", "below") */
    const val CMP = "cmp"
    /** a document named in the question, from a catalog's doc list */
    const val DOC = "doc"
    /** released items (blob hashes or case ids): bound by the bulk request, never parsed from text */
    const val ITEMS = "items"
}

@Serializable data class SlotSpec(
    val name: String,
    val type: String,
    val optional: Boolean = false,
    /** value used when the question has no candidate (k, order, cmp only) */
    val default: String? = null,
    /** what the slot means, shown in the closed question */
    val prompt: String = "",
)

/**
 * One node template. `{slot}` in [q] and in string [params] is replaced by the slot's value; `[ ... ]` in [q] is an
 * optional fragment, dropped when a slot inside it is unbound. A param string that is exactly `{slot}` becomes a
 * number for k slots and is removed when the slot is unbound.
 */
@Serializable data class NodeTemplate(
    val id: String,
    val op: Op,
    val q: String? = null,
    @SerialName("in") val inputs: List<String> = emptyList(),
    val params: JsonObject = JsonObject(emptyMap()),
    /** AGG: measure slot (table, column and owner come from it) */
    val measure: String? = null,
    /** AGG: count slot (table and owner come from it) */
    val count: String? = null,
    /** AGG with a fixed table (bulk patterns): table, column and the slot whose owner runs it */
    val table: String? = null,
    val column: String? = null,
    @SerialName("owner_of") val ownerOf: String? = null,
    /** AGG metric; default sum for a measure, count for a count */
    val metric: String? = null,
    /** AGG group-by: slot names (dimension slots) or literal column names */
    val dims: List<String> = emptyList(),
    /** AGG: period slot applied when the table has a month column */
    val period: String? = null,
    /** DOC: doc slot */
    val doc: String? = null,
    /** node is dropped when any of these optional slots is unbound (and inputs pointing at it are removed) */
    val needs: List<String> = emptyList(),
    /** expand once per item of this items slot (ids get 1-based suffixes; `{item}` is the item) */
    val each: String? = null,
)

@Serializable data class Pattern(
    val id: String,
    val title: String,
    /** "{numerator} per {denominator} by {by}": how a reading is said back in a clarification */
    val reading: String = "",
    /** operators the route's cues must cover for the pattern to be offered */
    val requires: List<Op> = emptyList(),
    /** cue words that imply requires[0] when the router supplied no cue */
    val cues: List<String> = emptyList(),
    /** bulk patterns are bound from a request (items), never chosen from question text */
    val bulk: Boolean = false,
    val slots: List<SlotSpec>,
    val dag: List<NodeTemplate>,
    val output: String,
)

@Serializable data class PatternLibrary(val version: String, val patterns: List<Pattern>)

/** A filled slot. [phrase] is the question substring it came from ("" = pattern default or bulk request). */
sealed interface SlotValue {
    val phrase: String
    /** what `{slot}` becomes in params */
    val value: String
}
data class MeasureSlot(override val phrase: String, val table: String, val column: String, val owner: String,
    val confidential: Boolean = false, val timed: Boolean = false) : SlotValue { override val value get() = column }
data class CountSlot(override val phrase: String, val table: String, val owner: String, val timed: Boolean = false) : SlotValue {
    override val value get() = table }
data class DimensionSlot(override val phrase: String, val column: String) : SlotValue { override val value get() = column }
data class PeriodSlot(override val phrase: String, val gte: String, val lte: String) : SlotValue { override val value get() = phrase }
data class IntSlot(override val phrase: String, val n: Int) : SlotValue { override val value get() = n.toString() }
/** order ("desc"/"asc") or cmp ("gt"/"lt") */
data class WordSlot(override val phrase: String, override val value: String) : SlotValue
data class DocSlot(override val phrase: String, val doc: String, val owner: String) : SlotValue { override val value get() = doc }
data class ItemsSlot(val items: List<String>, val owner: String?) : SlotValue {
    override val phrase get() = ""
    override val value get() = "${items.size} items"
}

class Patterns(val library: PatternLibrary) {
    val version: String get() = library.version
    val all: List<Pattern> get() = library.patterns
    /** patterns the planner may choose from question text */
    val textPatterns: List<Pattern> get() = library.patterns.filter { !it.bulk }

    fun byId(id: String): Pattern? = library.patterns.firstOrNull { it.id == id }

    /**
     * Bind [pattern] with [slots] into a topologically ordered [Dag]. Checks: required slots bound and of the right
     * type; each AGG table exists in its owner's catalog with numeric measure, groupable dims and (for sum/min/max)
     * a non-confidential column; inputs exist. AGG params = {"q", "expect": {table, metric, measure, dims, period?}}.
     */
    fun bind(pattern: Pattern, slots: Map<String, SlotValue>, catalogs: List<Catalog>): Result<Dag> = runCatching {
        for (s in pattern.slots) {
            val v = slots[s.name]
            if (v == null) { if (!s.optional) fail("slot ${s.name} (${s.type}) is not filled"); continue }
            if (!typeOk(s.type, v)) fail("slot ${s.name}: ${v::class.simpleName} is not a ${s.type}")
        }
        val dropped = HashSet<String>()
        val expanded = HashMap<String, List<String>>()   // template id → task ids
        val tasks = ArrayList<TaskSpec>()
        for (n in pattern.dag) {
            if (n.needs.any { slots[it] == null } || listOfNotNull(n.measure, n.count, n.doc).any { slots[it] == null }) {
                dropped += n.id; continue
            }
            val items: List<String?> = if (n.each != null) {
                val its = (slots[n.each] as? ItemsSlot)?.items ?: fail("node ${n.id}: slot ${n.each} has no items")
                if (its.isEmpty()) fail("node ${n.id}: no items")
                its
            } else listOf(null)
            val ids = ArrayList<String>()
            items.forEachIndexed { idx, item ->
                val id = if (n.each != null) "${n.id}${idx + 1}" else n.id
                val inputs = n.inputs.flatMap { ref ->
                    when {
                        ref in dropped -> emptyList()
                        ref !in expanded -> fail("node ${n.id}: input $ref is not an earlier node")
                        // an each-node fed by an each-node over the same items is paired index by index
                        n.each != null && pattern.dag.first { it.id == ref }.each == n.each -> listOf(expanded.getValue(ref)[idx])
                        else -> expanded.getValue(ref)
                    }
                }
                tasks += withMeasures(node(pattern, n, id, inputs, item, slots, catalogs), tasks)
                ids += id
            }
            expanded[n.id] = ids
        }
        if (pattern.output in dropped) fail("output ${pattern.output} was dropped")
        val out = expanded[pattern.output]?.singleOrNull() ?: fail("output ${pattern.output} is not a single node")
        Dag(pattern.id, version, tasks, out)
    }

    /**
     * RATIO/DIFF/SHARE over AGG inputs: `num`/`den` default to the inputs' result columns (`<metric>_<measure>`, or
     * `n` for a count), the names the IntentCompiler gives them, so a pattern need not spell them out.
     */
    private fun withMeasures(t: TaskSpec, earlier: List<TaskSpec>): TaskSpec {
        if (t.op !in setOf(Op.RATIO, Op.DIFF, Op.SHARE)) return t
        val cols = t.inputs.map { id -> earlier.firstOrNull { it.id == id }?.let(::resultColumn) }
        val extra = LinkedHashMap<String, JsonElement>()
        if ("num" !in t.params) cols.getOrNull(0)?.let { extra["num"] = JsonPrimitive(it) }
        if ("den" !in t.params && t.inputs.size > 1) cols.getOrNull(1)?.let { extra["den"] = JsonPrimitive(it) }
        return if (extra.isEmpty()) t else t.copy(params = JsonObject(t.params + extra))
    }

    private fun resultColumn(agg: TaskSpec): String? {
        if (agg.op != Op.AGG) return null
        val e = agg.params["expect"] as? JsonObject ?: return null
        val metric = (e["metric"] as? JsonPrimitive)?.content ?: return null
        val measure = (e["measure"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return if (measure == null) "n" else "${metric}_$measure"
    }

    private fun node(p: Pattern, n: NodeTemplate, id: String, inputs: List<String>, item: String?,
                     slots: Map<String, SlotValue>, catalogs: List<Catalog>): TaskSpec {
        var params = subst(n.params, slots, item)
        var owner: String? = null
        var blobs = emptyList<String>()
        if (item != null && (slots[n.each] as? ItemsSlot) != null) blobs = listOf(item)
        when (n.op) {
            Op.AGG -> {
                val m = n.measure?.let { slots[it] as MeasureSlot }
                val c = n.count?.let { slots[it] as CountSlot }
                val table: String; val column: String?; val metric: String; var timed: Boolean
                when {
                    m != null -> { owner = m.owner; table = m.table; column = m.column; metric = n.metric ?: "sum"; timed = m.timed }
                    c != null -> { owner = c.owner; table = c.table; column = null; metric = n.metric ?: "count"; timed = c.timed }
                    n.table != null -> {
                        owner = (slots[n.ownerOf ?: ""] as? ItemsSlot)?.owner ?: fail("node ${n.id}: no owner for ${n.table}")
                        table = n.table; column = n.column; metric = n.metric ?: (if (n.column == null) "count" else "sum"); timed = false
                    }
                    else -> fail("node ${n.id}: AGG needs a measure, count or table")
                }
                val cat = catalogs.firstOrNull { it.node == owner } ?: fail("node ${n.id}: no catalog for owner $owner")
                val t = cat.tables.firstOrNull { it.name == table } ?: fail("node ${n.id}: ${cat.nodeName} has no table $table")
                if (n.table != null) timed = t.columns.any { it.name == MONTH }
                if (column != null) {
                    val col = t.columns.firstOrNull { it.name == column } ?: fail("node ${n.id}: $table has no column $column")
                    if (col.type == ColType.TEXT || col.identifier) fail("node ${n.id}: $table.$column is not a measure")
                    if (col.confidential && metric !in CONFIDENTIAL_METRICS)
                        fail("$table.$column is confidential: only an average or a count is allowed, not $metric")
                }
                val dims = n.dims.mapNotNull { d ->
                    val spec = p.slots.firstOrNull { it.name == d }
                    if (spec != null) (slots[d] as? DimensionSlot)?.column else d
                }
                for (d in dims) if (!groupable(t, d)) fail("node ${n.id}: $table cannot be grouped by $d")
                val per = n.period?.let { slots[it] as? PeriodSlot }?.takeIf { timed }
                val q = text(n.q ?: fail("node ${n.id}: AGG needs q"), slots, item, if (timed) emptySet() else setOfNotNull(n.period))
                val expect = buildJsonObject {
                    put("table", table); put("metric", metric)
                    put("measure", column?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("dims", JsonArray(dims.map { JsonPrimitive(it) }))
                    if (per != null) put("period", buildJsonObject { put("phrase", per.phrase); put("gte", per.gte); put("lte", per.lte) })
                }
                params = JsonObject(params + mapOf("q" to JsonPrimitive(q), "expect" to expect))
            }
            Op.DOC -> {
                val d = slots[n.doc ?: fail("node ${n.id}: DOC needs a doc slot")] as DocSlot
                owner = d.owner
                val q = text(n.q ?: fail("node ${n.id}: DOC needs q"), slots, item, emptySet())
                params = JsonObject(params + mapOf("q" to JsonPrimitive(q), "doc" to JsonPrimitive(d.doc)))
            }
            else -> if (n.q != null) params = JsonObject(params + mapOf("q" to JsonPrimitive(text(n.q, slots, item, emptySet()))))
        }
        return TaskSpec(id = id, op = n.op, params = params, inputs = inputs, owner = owner, blobs = blobs)
    }

    companion object {
        const val ASSET_PATH = "swarm/patterns.json"
        const val MONTH = "month"
        val CONFIDENTIAL_METRICS = setOf("avg", "count")
        private val json = Json { ignoreUnknownKeys = true }
        private val SLOT = Regex("\\{([a-z_0-9]+)\\}")
        private val FRAGMENT = Regex("\\[([^\\[\\]]*)\\]")

        fun fromJson(text: String): Patterns {
            val lib = json.decodeFromString(PatternLibrary.serializer(), text)
            require(lib.patterns.map { it.id }.toSet().size == lib.patterns.size) { "duplicate pattern id" }
            for (p in lib.patterns) {
                val ids = HashSet<String>()
                for (n in p.dag) {
                    require(ids.add(n.id)) { "${p.id}: duplicate node ${n.id}" }
                    for (i in n.inputs) require(i in ids && i != n.id) { "${p.id}: node ${n.id} input $i is not an earlier node" }
                    val names = p.slots.map { it.name }.toSet()
                    for (s in listOfNotNull(n.measure, n.count, n.doc, n.period, n.each, n.ownerOf) + n.needs)
                        require(s in names) { "${p.id}: node ${n.id} names unknown slot $s" }
                }
                require(p.output in ids) { "${p.id}: output ${p.output} is not a node" }
            }
            return Patterns(lib)
        }

        private fun fail(msg: String): Nothing = throw IllegalArgumentException(msg)

        private fun typeOk(type: String, v: SlotValue) = when (type) {
            SlotTypes.MEASURE -> v is MeasureSlot
            SlotTypes.COUNT -> v is CountSlot
            SlotTypes.DIMENSION -> v is DimensionSlot
            SlotTypes.PERIOD -> v is PeriodSlot
            SlotTypes.K -> v is IntSlot
            SlotTypes.ORDER, SlotTypes.CMP -> v is WordSlot
            SlotTypes.DOC -> v is DocSlot
            SlotTypes.ITEMS -> v is ItemsSlot
            else -> false
        }

        private fun groupable(t: CatalogTable, col: String) = t.columns.any { it.name == col && it.groupable }

        /** Natural-language text: optional fragments, then `{slot}` → phrase (dimension → its phrase), spaces collapsed. */
        fun text(template: String, slots: Map<String, SlotValue>, item: String?, suppress: Set<String>): String {
            fun bound(name: String) = name !in suppress && (name == "item" && item != null || slots[name] != null)
            fun one(name: String) = if (name == "item") item ?: "" else if (name in suppress) "" else slots[name]?.let { it.phrase.ifEmpty { it.value } } ?: ""
            val withFragments = FRAGMENT.replace(template) { m ->
                if (SLOT.findAll(m.groupValues[1]).all { bound(it.groupValues[1]) }) m.groupValues[1] else ""
            }
            return SLOT.replace(withFragments) { one(it.groupValues[1]) }.replace(Regex("\\s+"), " ").trim()
        }

        private fun subst(obj: JsonObject, slots: Map<String, SlotValue>, item: String?): JsonObject {
            val out = LinkedHashMap<String, JsonElement>()
            for ((k, v) in obj) substElement(v, slots, item)?.let { out[k] = it }
            return JsonObject(out)
        }

        private fun substElement(e: JsonElement, slots: Map<String, SlotValue>, item: String?): JsonElement? = when (e) {
            is JsonObject -> subst(e, slots, item)
            is JsonArray -> JsonArray(e.mapNotNull { substElement(it, slots, item) })
            is JsonPrimitive -> if (!e.isString) e else {
                val s = e.content
                val whole = SLOT.matchEntire(s)?.groupValues?.get(1)
                when {
                    whole == "item" -> item?.let { JsonPrimitive(it) }
                    whole != null -> when (val v = slots[whole]) {
                        null -> null
                        is IntSlot -> JsonPrimitive(v.n)
                        else -> JsonPrimitive(v.value)
                    }
                    else -> JsonPrimitive(SLOT.replace(s) { m ->
                        val name = m.groupValues[1]
                        if (name == "item") item ?: "" else slots[name]?.value ?: ""
                    })
                }
            }
        }
    }
}
