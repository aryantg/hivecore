package com.hivecore.app

import com.hivecore.core.LlmBackend
import com.hivecore.core.fakes.FakeLlmEngine
import com.hivecore.core.fakes.FakeModelPool
import com.hivecore.core.fakes.FakeModelRegistry
import com.hivecore.engine.TaskRunnerImpl
import com.hivecore.node.textai.LlmMapExecutor
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Spec 18.2: the task runner's executor registry never holds a class from the cloud package. */
class CloudBoundaryTest {
    @Test fun executorRegistryHoldsNoCloudClass() {
        val llm = FakeLlmEngine(LlmBackend.CPU)
        val registry = FakeModelRegistry()
        val env = SwarmGraph.ExecutorEnv(com.hivecore.core.fakes.FakeClosedChooser(), llm, { com.hivecore.core.Catalog("self", "self", emptyList()) },
            com.hivecore.core.JdbcSqlDb.inMemory(), com.hivecore.core.fakes.FakeSchemaSource(com.hivecore.core.NodeRole.SALES), "self")
        val executors = SwarmExecutors.build(env, SwarmExecutors.Seams(pool = FakeModelPool(registry), registry = registry, outDir = File("build/tmp/boundary"))) + LlmMapExecutor(llm)
        val runner = TaskRunnerImpl("self")
        executors.forEach { runner.register(it) }
        val names = runner.executorClassNames()
        assertTrue(names.toString(), names.isNotEmpty())
        assertTrue(names.toString(), names.none { it.startsWith("com.hivecore.cloud") })
        // and the registry holds the ops the phases need
        assertTrue(executors.flatMap { it.ops }.containsAll(listOf(com.hivecore.core.Op.LLM_MAP, com.hivecore.core.Op.EXTRACT)))
    }
}
