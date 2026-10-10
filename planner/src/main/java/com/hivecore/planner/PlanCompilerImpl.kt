package com.hivecore.planner

import com.hivecore.core.Canon
import com.hivecore.core.Catalog
import com.hivecore.core.Dag
import com.hivecore.core.Op
import com.hivecore.core.OpClass
import com.hivecore.core.OpClasses
import com.hivecore.core.PlanCheck
import com.hivecore.core.PlanCompiler
import com.hivecore.core.TaskSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Swarm plan §5.2: pure validation of a DAG before anything is sent.
 *
 *  - ids unique, the output id exists, every input exists and comes earlier (tasks are topologically ordered);
 *    a back reference that closes a loop is reported as a cycle
 *  - op arity and input types: table ops (JOIN, RATIO, …) read tables, which AGG and the table reduces produce;
 *    a RATIO/DIFF/SHARE/RECONCILE `key` must be in an AGG input's declared `expect.dims` when it declares them
 *  - AGG and DOC carry a non-blank `q` and an owner that is one of [catalogs]; other owner-class ops need an owner
 *  - budget: ≤ [MAX_TASKS] tasks and ≤ [MAX_LLM_PER_NODE] LLM calls per phone (AGG/DOC and LLM/VLM-class tasks on
 *    their owner; unowned LLM/VLM tasks count together as one unknown phone)
 *
 * planHash = sha256(canonical(dag) + patternsVersion + modelHash), computed even when the plan is rejected so the
 * refusal can be logged against it. perOwner lists each owner's AGG/DOC sub-questions in task order.
 */
class PlanCompilerImpl : PlanCompiler {

    private enum class Kind { TABLE, DOC, TEXT, ANY }

    override fun validate(dag: Dag, catalogs: List<Catalog>, modelHash: String): PlanCheck {
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
            val cls = OpClasses.of(t.op)
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
            val cls = OpClasses.of(t.op)
            val callsLlm = t.op == Op.AGG || t.op == Op.DOC || cls == OpClass.LLM || cls == OpClass.VLM
            if (!callsLlm) continue
            val where = t.owner ?: UNASSIGNED
            llm[where] = (llm[where] ?: 0) + 1
        }
        for ((n, c) in llm) if (c > MAX_LLM_PER_NODE)
            errors += "$c LLM calls on ${if (n == UNASSIGNED) "unassigned workers" else "'$n'"} exceed the budget of $MAX_LLM_PER_NODE"

        val perOwner = LinkedHashMap<String, MutableList<String>>()
        for (t in tasks) if ((t.op == Op.AGG || t.op == Op.DOC) && !t.owner.isNullOrBlank()) {
            val q = (t.params["q"] as? JsonPrimitive)?.content ?: continue
            perOwner.getOrPut(t.owner!!) { ArrayList() } += q
        }
        return PlanCheck(errors.isEmpty(), errors, Canon.planHash(dag, modelHash), perOwner)
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
            Op.AGG, Op.DOC -> n == 0
            Op.JOIN -> n >= 2
            Op.RATIO, Op.DIFF, Op.RECONCILE -> n == 2
            Op.SHARE -> n == 1 || n == 2
            Op.TREND, Op.RANK, Op.THRESHOLD, Op.PHRASE -> n == 1
            Op.RENDER, Op.VERIFY -> n >= 1
            else -> true
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
        private const val UNASSIGNED = "*"
        private val TABLE_OPS = setOf(Op.JOIN, Op.RATIO, Op.DIFF, Op.SHARE, Op.TREND, Op.RANK, Op.THRESHOLD, Op.RECONCILE)
        private val KEYED_OPS = setOf(Op.JOIN, Op.RATIO, Op.DIFF, Op.SHARE, Op.RECONCILE)
    }
}
