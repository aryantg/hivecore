package com.hivecore.planner

import com.hivecore.core.Canon
import com.hivecore.core.Catalog
import com.hivecore.core.Dag
import com.hivecore.core.NodeRole
import com.hivecore.core.Op
import com.hivecore.core.OpRegistry
import com.hivecore.core.TaskSpec
import com.hivecore.core.fakes.FakeSchemaSource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The spec 7.4 checks driven by [OpRegistry]. */
class PlanCompilerRegistryTest {
    private val catalogs = listOf(
        Catalog.of("hr", "HR", FakeSchemaSource.seed(NodeRole.HR)),
        Catalog.of("sales", "Sales", FakeSchemaSource.seed(NodeRole.SALES)),
    )
    private val caps = setOf("ocr", "llm.json", "llm.text", "embed")
    private val pc = PlanCompilerImpl(ops = { OpRegistry.nodeOps + setOf(Op.AGG, Op.DOC) }, capabilities = { caps }, selfId = "self")

    private val fieldsSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { putJsonObject("vendor") { put("type", "string") }; putJsonObject("total") { put("type", "number") } }
        putJsonArray("required") { add("vendor"); add("total") }
    }

    private fun node(id: String, op: Op, inputs: List<String> = emptyList(), owner: String? = null, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) =
        TaskSpec(id, op, buildJsonObject(build), inputs, owner)

    private fun dag(vararg tasks: TaskSpec) = Dag("t2", "t2-graph-1", tasks.toList(), tasks.last().id)

    private fun errors(d: Dag) = pc.validate(d, catalogs, "m").errors

    private val good = dag(
        node("ocr", Op.OCR) { put("cap", "ocr"); put("input", "items"); put("axis", "shard"); put("privacy", "released") },
        node("fields", Op.FIELDS, listOf("ocr")) { put("cap", "llm.json"); put("axis", "shard"); put("replicas", 2); put("schema", fieldsSchema) },
        node("out", Op.RENDER, listOf("fields")),
    )

    @Test fun validT2GraphPasses() {
        val c = pc.validate(good, catalogs, "m")
        assertTrue(c.errors.toString(), c.ok)
        assertEquals(Canon.planHash(good, "m"), c.planHash)
    }

    @Test fun execIsRejected() {
        val d = dag(node("f", Op.FIELDS) { put("exec", "cloud"); put("schema", fieldsSchema) })
        assertTrue(errors(d).any { it.contains("declares exec") })
    }

    @Test fun opOutsideTheSwarmRegistryIsRejected() {
        val only = PlanCompilerImpl(ops = { setOf(Op.OCR) })
        val d = dag(node("e", Op.EMBED), node("out", Op.RENDER, listOf("e")))
        val errs = only.validate(d, catalogs, "m").errors
        assertTrue(errs.toString(), errs.any { it.contains("op EMBED of 'e' is not in the registry") })
        assertFalse(errs.any { it.contains("RENDER") })   // reduce ops always run on the orchestrator
    }

    @Test fun axisNeedsAMergeKind() {
        val bad = dag(node("s", Op.SUMMARIZE) { put("axis", "fanout") })   // SUMMARIZE has no fan-out merge
        assertTrue(errors(bad).any { it.contains("no merge kind for axis fanout") })
        val unknown = dag(node("o", Op.OCR) { put("axis", "diagonal") })
        assertTrue(errors(unknown).any { it.contains("axis 'diagonal'") })
        val ok = dag(node("s", Op.SUMMARIZE) { put("axis", "chunk") })
        assertFalse(errors(ok).any { it.contains("merge kind") })
    }

    @Test fun capMustExistInTheCatalog() {
        val d = dag(node("v", Op.VLM_ASK) { put("cap", "vlm") })
        assertTrue(errors(d).any { it.contains("needs capability 'vlm', which no phone in the swarm has") })
        assertFalse(PlanCompilerImpl().validate(d, catalogs, "m").errors.any { it.contains("capability") })   // unchecked without a catalog
    }

    @Test fun schemaMustBeValidWithoutRef() {
        val ref = buildJsonObject { put("type", "object"); putJsonObject("properties") { putJsonObject("x") { put("\$ref", "#/\$defs/x") } } }
        val d = dag(node("f", Op.FIELDS) { put("schema", ref) })
        assertTrue(errors(d).any { it.contains("schema is not a valid JSON Schema") && it.contains("references are not allowed") })
        val bogus = dag(node("f", Op.FIELDS) { put("schema", buildJsonObject { put("type", "thing") }) })
        assertTrue(errors(bogus).any { it.contains("unknown type thing") })
    }

    @Test fun privateLineageNeverReachesANonOwnerNode() {
        // OCR over private items with no owner: nowhere safe to run
        val noOwner = dag(node("ocr", Op.OCR) { put("input", "items"); put("privacy", "private") }, node("out", Op.RENDER, listOf("ocr")))
        assertTrue(errors(noOwner).toString(), errors(noOwner).any { it.contains("private lineage of 'ocr'") && it.contains("no owner set") })
        // the derived FIELDS step inherits the privacy; on another phone it is refused
        val moved = dag(
            node("ocr", Op.OCR, owner = "hr") { put("input", "items"); put("privacy", "private") },
            node("fields", Op.FIELDS, listOf("ocr"), owner = "sales") { put("schema", fieldsSchema); put("replicas", 1) },
            node("out", Op.RENDER, listOf("fields")),
        )
        assertTrue(errors(moved).any { it.contains("private lineage of 'fields' would reach a non-owner node") })
        // replicated on the owner: a replica would go elsewhere
        val replicated = dag(
            node("ocr", Op.OCR, owner = "hr") { put("input", "items"); put("privacy", "private") },
            node("fields", Op.FIELDS, listOf("ocr"), owner = "hr") { put("schema", fieldsSchema) },
            node("out", Op.RENDER, listOf("fields")),
        )
        assertTrue(errors(replicated).any { it.contains("would be replicated") })
        // on the owner, unreplicated, reduced on the orchestrator that is the owner: fine
        val self = PlanCompilerImpl(selfId = "hr")
        val fine = dag(
            node("ocr", Op.OCR, owner = "hr") { put("input", "items"); put("privacy", "private") },
            node("fields", Op.FIELDS, listOf("ocr"), owner = "hr") { put("schema", fieldsSchema); put("replicas", 1) },
            node("out", Op.RENDER, listOf("fields")),
        )
        val c = self.validate(fine, catalogs, "m")
        assertTrue(c.errors.toString(), c.ok)
        // the same plan reduced on another orchestrator: private output would travel
        assertTrue(errors(fine).any { it.contains("private lineage of 'out'") })
    }

    @Test fun confidentialColumnOnlyInAvgOrCount() {
        fun agg(metric: String) = node("a", Op.AGG, owner = "hr") {
            put("q", "salary by region")
            putJsonObject("expect") { put("table", "employees"); put("metric", metric); put("measure", "salary"); putJsonArray("dims") { add("region") } }
        }
        assertTrue(errors(dag(agg("sum"))).any { it.contains("employees.salary is confidential") })
        assertFalse(errors(dag(agg("avg"))).any { it.contains("confidential") })
        assertFalse(errors(dag(agg("count"))).any { it.contains("confidential") })
    }

    @Test fun conditionsMustNameAnInputAndParse() {
        val d = dag(node("c", Op.TEXT_CLASSIFY) { put("axis", "fanout") },
            node("out", Op.RENDER, listOf("c")) { putJsonObject("when") { put("c", "if_confidence_below:0.6"); put("zz", "always") } })
        val errs = errors(d)
        assertTrue(errs.toString(), errs.any { it.contains("condition on 'zz'") })
        val bad = dag(node("c", Op.TEXT_CLASSIFY), node("out", Op.RENDER, listOf("c")) { putJsonObject("when") { put("c", "maybe") } })
        assertTrue(errors(bad).any { it.contains("unknown condition 'maybe'") })
    }

    @Test fun llmMapNeedsTemplateAndSchemaAndReplicasAreBounded() {
        assertTrue(errors(dag(node("m", Op.LLM_MAP) { put("schema", fieldsSchema) })).any { it.contains("no 'template'") })
        assertTrue(errors(dag(node("m", Op.LLM_MAP) { put("template", "x {item}") })).any { it.contains("no 'schema'") })
        assertTrue(errors(dag(node("m", Op.LLM_MAP) { put("template", "x"); put("schema", fieldsSchema); put("replicas", 9) })).any { it.contains("replicas must be") })
    }

    @Test fun validateT2RequiresConfidence() {
        assertFalse(pc.validateT2(good, null, catalogs, "m").ok)
        assertTrue(pc.validateT2(good, null, catalogs, "m").check.errors.any { it.contains("no confidence") })
        assertFalse(pc.validateT2(good, 1.5, catalogs, "m").ok)
        assertTrue(pc.validateT2(good, 0.9, catalogs, "m").ok)
        // the hash is computed for a rejected plan too
        assertEquals(64, pc.validateT2(good, null, catalogs, "m").planHash.length)
    }

    @Test fun legacyArityStillHolds() {
        val share = dag(node("a", Op.AGG, owner = "sales") { put("q", "revenue by region") }, node("s", Op.SHARE, listOf("a")) { put("measure", "sum_revenue") })
        assertFalse(errors(share).any { it.contains("has 1 inputs") })
        val ratio = dag(node("a", Op.AGG, owner = "sales") { put("q", "x") }, node("r", Op.RATIO, listOf("a")) { put("key", "region"); put("num", "a"); put("den", "b") })
        assertTrue(errors(ratio).any { it.contains("RATIO 'r' has 1 inputs") })
        assertTrue(errors(dag(node("a", Op.AGG, owner = "sales", inputs = listOf("a")) { put("q", "x") })).any { it.contains("has 1 inputs") || it.contains("takes no inputs") })
        assertTrue(JsonObject(emptyMap()).isEmpty())
    }
}
