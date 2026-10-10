package com.hivecore.planner

import com.hivecore.core.Canon
import com.hivecore.core.Catalog
import com.hivecore.core.Dag
import com.hivecore.core.JsonSchema
import com.hivecore.core.Op
import com.hivecore.core.OpClass
import com.hivecore.core.OpRegistry
import com.hivecore.core.PlanCheck
import com.hivecore.core.PlanCompiler
import com.hivecore.core.TaskSpec
import com.hivecore.engine.Lineage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A plan that passed (or failed) the compiler, with the hash every refusal is logged against (spec 7.4). */
data class CompiledPlan(val dag: Dag, val check: PlanCheck) {
    val ok: Boolean get() = check.ok
    val planHash: String get() = check.planHash
}

/**
 * Spec 7.4: pure validation of a DAG before anything is sent, every check driven by [OpRegistry].
 *
 *  - ids unique, the output id exists, every input exists and comes earlier (tasks are topologically ordered);
 *    a back reference that closes a loop is reported as a cycle
 *  - arity per op from the registry (SHARE, PHRASE and VERIFY keep the reduce engine's wider ranges); table ops
 *    (JOIN, RATIO, …) read tables, which AGG and the table reduces produce; a RATIO/DIFF/SHARE/RECONCILE `key` must be
 *    in an AGG input's declared `expect.dims` when it declares them
 *  - AGG and DOC carry a non-blank `q` and an owner that is one of [catalogs]; other owner-class ops need an owner
 *  - every op is one the swarm executes ([ops]; REDUCE ops always run on the orchestrator)
 *  - a declared `axis` names a split axis and (op, axis) has a merge kind in the registry
 *  - a declared `cap` exists in the capability catalog ([capabilities])
 *  - no task carries `exec` (a node may not ask for the cloud)
 *  - private lineage never reaches a non-owner node: `privacy = private` is inherited by every consumer; such a task
 *    runs on the one owner of that data (owner set and equal), an LLM/VLM task over it declares `replicas: 1`, and a
 *    reduce over it needs the owner to be the orchestrator ([selfId], when known)
 *  - a confidential column is only averaged or counted (AGG `expect` against the owner's catalog)
 *  - every `schema` is a valid JSON Schema 2020-12 ([JsonSchema] subset) without `$ref`
 *  - budget: ≤ [MAX_TASKS] tasks and ≤ [MAX_LLM_PER_NODE] LLM calls per phone (AGG/DOC and LLM/VLM-class tasks on
 *    their owner; unowned LLM/VLM tasks count together as one unknown phone)
 *
 * planHash = sha256(canonical(dag) + patternsVersion + modelHashes), computed even when the plan is rejected so the
 * refusal can be logged against it. perOwner lists each owner's AGG/DOC sub-questions in task order. The constructor
 * lambdas are the defaults for [validate]; [compile] takes them per call.
 */
class PlanCompilerImpl(
    /** ops the swarm can execute (null = every registry op) */
    private val ops: (() -> Set<Op>)? = null,
    /** catalog capabilities of the swarm (null = not checked) */
    private val capabilities: (() -> Set<String>)? = null,
    /** the orchestrator, which runs the reduce ops (null = not checked for private lineage) */
    private val selfId: String? = null,
) : PlanCompiler {

    private enum class Kind { TABLE, DOC, TEXT, ANY }

    override fun validate(dag: Dag, catalogs: List<Catalog>, modelHash: String): PlanCheck =
        compile(dag, catalogs, modelHash, capabilities?.invoke(), ops?.invoke(), selfId).check

    /** [compile] plus the T2-only rule: the plan states its `confidence` (0..1). */
    fun validateT2(dag: Dag, confidence: Double?, catalogs: List<Catalog>, modelHash: String,
                   capabilities: Set<String>? = this.capabilities?.invoke(), ops: Set<Op>? = this.ops?.invoke(), selfId: String? = this.selfId): CompiledPlan {
        val c = compile(dag, catalogs, modelHash, capabilities, ops, selfId)
        val extra = when {
            confidence == null -> "the plan has no confidence"
            confidence.isNaN() || confidence < 0.0 || confidence > 1.0 -> "confidence $confidence is not between 0 and 1"
            else -> null
        } ?: return c
        return c.copy(check = c.check.copy(ok = false, errors = c.check.errors + extra))
    }

    /** Every check, with the swarm's [capabilities] and [ops] given per call (null = not checked / every op). */
    fun compile(dag: Dag, catalogs: List<Catalog>, modelHash: String, capabilities: Set<String>?, ops: Set<Op>?, selfId: String?): CompiledPlan {
        val errors = ArrayList<String>()
        val nodes = catalogs.map { it.node }.toSet()
        val tasks = dag.tasks
        val position = HashMap<String, Int>()
        tasks.forEachIndexed { i, t ->
            if (t.id.isBlank()) errors += "task ${i + 1} has no id"
            else if (position.putIfAbsent(t.id, i) != null) errors += "duplicate task id '${t.id}'"
        }
        if (tasks.isEmpty()) errors += "the plan has no tasks"
        if (dag.output !in position) errors += "output '${dag.output}' is not a task"
        if (tasks.size > MAX_TASKS) errors += "${tasks.size} tasks exceed the budget of $MAX_TASKS"

        // Inputs exist and precede; a later reference is a cycle when it leads back to the task.
        val cycleReported = HashSet<String>()
        tasks.forEachIndexed { i, t ->
            for (inp in t.inputs) {
                val j = position[inp]
                when {
                    inp == t.id -> errors += "cycle: '${t.id}' reads its own output"
                    j == null -> errors += "'${t.id}' reads missing input '$inp'"
                    j > i -> {
                        val loop = reaches(inp, t.id, tasks, position)
                        if (loop) { if (cycleReported.add(listOf(t.id, inp).sorted().joinToString())) errors += "cycle between '${t.id}' and '$inp'" }
                        else errors += "'${t.id}' reads '$inp', which comes later"
                    }
                }
            }
        }

        // Arity, types, params, owners.
        val kinds = HashMap<String, Kind>()
        for (t in tasks) {
            val ins = t.inputs.mapNotNull { id -> tasks.firstOrNull { it.id == id } }
            arity(t)?.let { errors += it }
            val needTables = t.op in TABLE_OPS
            if (needTables) for (inp in ins) {
                val k = kinds[inp.id] ?: kindOf(inp.op)
                if (k != Kind.TABLE && k != Kind.ANY) errors += "${t.op} '${t.id}' needs tables but '${inp.id}' (${inp.op}) is not one"
            }
            keyCheck(t, ins)?.let { errors += it }
            paramCheck(t, ins)?.let { errors += it }
            val cls = OpRegistry[t.op].cls
            if (cls == OpClass.OWNER) {
                when {
                    t.owner.isNullOrBlank() -> errors += "${t.op} '${t.id}' has no owner"
                    t.owner !in nodes -> errors += "${t.op} '${t.id}' owner '${t.owner}' is not a known node"
                }
            } else if (t.owner != null && t.owner !in nodes) errors += "'${t.id}' owner '${t.owner}' is not a known node"
            if ((t.op == Op.AGG || t.op == Op.DOC) && t.inputs.isNotEmpty()) errors += "${t.op} '${t.id}' takes no inputs"
            kinds[t.id] = kindOf(t.op)
        }

        // LLM calls per phone.
        val llm = LinkedHashMap<String, Int>()
        for (t in tasks) {
            val cls = OpRegistry[t.op].cls
            val callsLlm = t.op == Op.AGG || t.op == Op.DOC || cls == OpClass.LLM || cls == OpClass.VLM
            if (!callsLlm) continue
            val where = t.owner ?: UNASSIGNED
            llm[where] = (llm[where] ?: 0) + 1
        }
        for ((n, c) in llm) if (c > MAX_LLM_PER_NODE)
            errors += "$c LLM calls on ${if (n == UNASSIGNED) "unassigned workers" else "'$n'"} exceed the budget of $MAX_LLM_PER_NODE"

        errors += registryChecks(tasks, catalogs, capabilities, ops, selfId)

        val perOwner = LinkedHashMap<String, MutableList<String>>()
        for (t in tasks) if ((t.op == Op.AGG || t.op == Op.DOC) && !t.owner.isNullOrBlank()) {
            val q = (t.params["q"] as? JsonPrimitive)?.content ?: continue
            perOwner.getOrPut(t.owner!!) { ArrayList() } += q
        }
        return CompiledPlan(dag, PlanCheck(errors.isEmpty(), errors, Canon.planHash(dag, modelHash), perOwner))
    }

    private fun str(t: TaskSpec, k: String): String? = (t.params[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** The registry-driven checks of spec 7.4 (see the class comment). */
    private fun registryChecks(tasks: List<TaskSpec>, catalogs: List<Catalog>, caps: Set<String>?, ops: Set<Op>?, selfId: String?): List<String> {
        val errors = ArrayList<String>()
        val lineage = HashMap<String, String>()
        val privOwners = HashMap<String, Set<String?>>()
        for (t in tasks) {
            val info = OpRegistry[t.op]
            val cls = info.cls
            if (T2Params.EXEC in t.params) errors += "'${t.id}' declares exec: a node may not request the cloud"
            if (ops != null && cls != OpClass.REDUCE && t.op !in ops) errors += "op ${t.op} of '${t.id}' is not in the registry of this swarm"
            t.params[T2Params.AXIS]?.let { a ->
                val axis = (a as? JsonPrimitive)?.takeIf { it.isString }?.content?.lowercase()
                if (axis !in T2Params.AXES) errors += "'${t.id}' axis '${axis ?: a}' is not one of ${T2Params.AXES.sorted().joinToString("/")}"
                else if (info.mergeFor(axis!!) == null) errors += "${t.op} '${t.id}' has no merge kind for axis $axis"
            }
            t.params[T2Params.CAP]?.let { c ->
                val cap = (c as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (cap.isNullOrBlank()) errors += "'${t.id}' cap is not a name"
                else if (caps != null && cap !in caps) errors += "'${t.id}' needs capability '$cap', which no phone in the swarm has"
            }
            t.params[T2Params.REPLICAS]?.let { r ->
                val n = (r as? JsonPrimitive)?.content?.toIntOrNull()
                if (n == null || n < 1 || n > MAX_REPLICAS) errors += "'${t.id}' replicas must be 1..$MAX_REPLICAS"
            }
            t.params[T2Params.SCHEMA]?.let { sc ->
                JsonSchema.schemaError(sc)?.let { errors += "'${t.id}' schema is not a valid JSON Schema: $it" }
            }
            (t.params[T2Params.WHEN] as? JsonObject)?.forEach { (from, w) ->
                val s = (w as? JsonPrimitive)?.content ?: ""
                if (from !in t.inputs) errors += "'${t.id}' has a condition on '$from', which it does not read"
                if (EdgeCondition.parse(s) == null) errors += "'${t.id}' edge from '$from': unknown condition '$s'"
            }
            confidentialCheck(t, catalogs)?.let { errors += it }

            // privacy lineage (spec 15.2 step 3): own declaration, inherited from every input
            val own = str(t, T2Params.PRIVACY) ?: Lineage.PUBLIC
            if (own !in LINEAGES) errors += "'${t.id}' privacy '$own' is not public/released/private"
            var lin = if (own in LINEAGES) own else Lineage.PUBLIC
            val owners = HashSet<String?>()
            if (own == Lineage.PRIVATE) owners += t.owner
            for (i in t.inputs) {
                lin = Lineage.strictest(lin, lineage[i] ?: Lineage.PUBLIC)
                privOwners[i]?.let { owners += it }
            }
            lineage[t.id] = lin
            privOwners[t.id] = owners
            if (lin != Lineage.PRIVATE) continue
            val where = if (cls == OpClass.REDUCE) selfId else t.owner
            when {
                cls == OpClass.REDUCE && selfId == null -> {
                    if (owners.size > 1 || null in owners) errors += "private data reaches '${t.id}' from more than one place"
                }
                where == null -> errors += "private lineage of '${t.id}' would reach a non-owner node (no owner set)"
                owners.any { it != where } -> errors += "private lineage of '${t.id}' would reach a non-owner node"
            }
            if (cls == OpClass.LLM || cls == OpClass.VLM) {
                val replicas = (t.params[T2Params.REPLICAS] as? JsonPrimitive)?.content?.toIntOrNull() ?: info.replicas
                val unreplicated = str(t, "replicate") == "none"
                if (!unreplicated && replicas > 1) errors += "private '${t.id}' would be replicated to another phone (declare replicas: 1)"
            }
        }
        return errors
    }

    /** Confidential columns are only averaged or counted (extends the [Patterns.bind] rule to any plan). */
    private fun confidentialCheck(t: TaskSpec, catalogs: List<Catalog>): String? {
        if (t.op != Op.AGG) return null
        val e = t.params["expect"] as? JsonObject ?: return null
        val table = (e["table"] as? JsonPrimitive)?.content ?: return null
        val metric = (e["metric"] as? JsonPrimitive)?.content ?: return null
        val measure = (e["measure"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val col = catalogs.firstOrNull { it.node == t.owner }?.tables?.firstOrNull { it.name == table }?.columns?.firstOrNull { it.name == measure }
            ?: return null
        return if (col.confidential && metric !in Patterns.CONFIDENTIAL_METRICS)
            "AGG '${t.id}': $table.$measure is confidential: only an average or a count is allowed, not $metric" else null
    }

    /** True when [from] reaches [target] by following inputs. */
    private fun reaches(from: String, target: String, tasks: List<TaskSpec>, position: Map<String, Int>): Boolean {
        val seen = HashSet<String>()
        val stack = ArrayDeque(listOf(from))
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (id == target) return true
            if (!seen.add(id)) continue
            position[id]?.let { stack.addAll(tasks[it].inputs) }
        }
        return false
    }

    private fun arity(t: TaskSpec): String? {
        val n = t.inputs.size
        val ok = when (t.op) {
            Op.SHARE -> n == 1 || n == 2
            Op.PHRASE -> n == 1
            Op.VERIFY -> n >= 1
            else -> n in OpRegistry[t.op].arity
        }
        return if (ok) null else "${t.op} '${t.id}' has $n inputs"
    }

    private fun paramCheck(t: TaskSpec, ins: List<TaskSpec>): String? {
        fun has(k: String) = (t.params[k] as? JsonPrimitive)?.content?.isNotBlank() == true
        // Keyless (one figure each, e.g. "revenue per rep this year"): every input is an AGG that declares no dims.
        val keyless = ins.isNotEmpty() && ins.all { i ->
            i.op == Op.AGG && ((i.params["expect"] as? JsonObject)?.get("dims") as? JsonArray)?.isEmpty() == true
        }
        return when (t.op) {
            Op.AGG, Op.DOC -> if (!has("q")) "${t.op} '${t.id}' has no sub-question 'q'" else null
            Op.TREND, Op.RANK -> if (!has("measure")) "${t.op} '${t.id}' has no 'measure'" else null
            Op.THRESHOLD -> when {
                !has("measure") -> "THRESHOLD '${t.id}' has no 'measure'"
                (t.params["op"] as? JsonPrimitive)?.content !in setOf("gt", "gte", "lt", "lte") -> "THRESHOLD '${t.id}' has no valid 'op'"
                t.params["value"] == null && (t.params["reference"] as? JsonPrimitive)?.content != "mean" -> "THRESHOLD '${t.id}' has neither 'value' nor reference \"mean\""
                else -> null
            }
            Op.RATIO, Op.DIFF -> when {
                t.params["key"] == null && !keyless -> "${t.op} '${t.id}' has no 'key'"
                !has("num") || !has("den") -> "${t.op} '${t.id}' needs 'num' and 'den'"
                else -> null
            }
            Op.LLM_MAP -> when {
                !has(T2Params.TEMPLATE) -> "LLM_MAP '${t.id}' has no 'template'"
                t.params[T2Params.SCHEMA] !is JsonObject -> "LLM_MAP '${t.id}' has no 'schema'"
                else -> null
            }
            else -> null
        }
    }

    /** A declared key must be among an AGG input's `expect.dims` (when the AGG declares them). */
    private fun keyCheck(t: TaskSpec, ins: List<TaskSpec>): String? {
        if (t.op !in KEYED_OPS) return null
        val key = when (val k = t.params["key"]) {
            is JsonPrimitive -> listOf(k.content)
            is JsonArray -> k.mapNotNull { (it as? JsonPrimitive)?.content }
            else -> return null
        }
        for (inp in ins) {
            if (inp.op != Op.AGG) continue
            val dims = ((inp.params["expect"] as? JsonObject)?.get("dims") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: continue
            val missing = key.filter { it !in dims }
            if (missing.isNotEmpty()) return "${t.op} '${t.id}' key ${missing.joinToString(", ")} is not a dimension of '${inp.id}'"
        }
        return null
    }

    private fun kindOf(op: Op): Kind = when (op) {
        Op.AGG, Op.JOIN, Op.RATIO, Op.DIFF, Op.SHARE, Op.TREND, Op.RANK, Op.THRESHOLD, Op.RECONCILE -> Kind.TABLE
        Op.DOC -> Kind.DOC
        Op.RENDER, Op.PHRASE -> Kind.TEXT
        else -> Kind.ANY
    }

    companion object {
        const val MAX_TASKS = 12
        const val MAX_LLM_PER_NODE = 6
        const val MAX_REPLICAS = 3
        private const val UNASSIGNED = "*"
        private val LINEAGES = setOf(Lineage.PUBLIC, Lineage.RELEASED, Lineage.PRIVATE)
        private val TABLE_OPS = setOf(Op.JOIN, Op.RATIO, Op.DIFF, Op.SHARE, Op.TREND, Op.RANK, Op.THRESHOLD, Op.RECONCILE)
        private val KEYED_OPS = setOf(Op.JOIN, Op.RATIO, Op.DIFF, Op.SHARE, Op.RECONCILE)
    }
}
