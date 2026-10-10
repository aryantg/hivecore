package com.hivecore.ui

import com.hivecore.core.ModelManifest
import com.hivecore.core.ModelRegistry
import com.hivecore.core.fakes.FakeModelPool
import com.hivecore.core.fakes.FakeModelRegistry
import com.hivecore.ui.ports.fakes.FakeModelsPort
import com.hivecore.ui.vm.ModelsViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ModelsViewModelTest {
    private val now = 1_700_000_000_000L
    private fun m(id: String, runtime: String) = ModelManifest(id, "task_$id", runtime, files = listOf("$id.bin"), sha256 = "0".repeat(64), licence = "Apache-2.0", sizeMb = 10)
    private val registry = FakeModelRegistry(listOf(m("yolo11n", "litert_qnn"), m("qwen3-4b-q4", "geniex_llm")), File("models"))
    private val pool = FakeModelPool(registry)
    private val port = FakeModelsPort.sample(now)

    private fun TestScope.vm(reg: ModelRegistry = registry) = ModelsViewModel(port, reg, pool, "self", backgroundScope, StandardTestDispatcher(testScheduler), clock = { now })

    @Test fun rowsGroupByCapabilityWithPerPhoneChipsHashesAndBench() = runTest {
        val vm = vm()
        assertTrue(vm.state.value.loading)
        vm.refresh(); settle()
        pool.with("yolo11n") { }; settle()
        val s = vm.state.value
        assertFalse(s.loading)
        assertEquals(listOf("llm.json", "vlm", "asr", "detect", "embed"), s.groups.map { it.first })
        val qwen = s.groups.first { it.first == "llm.json" }.second.first { it.id == "qwen3-4b-q4" }
        assertTrue(qwen.pinned)
        assertTrue(qwen.installedHere); assertTrue(qwen.loadedHere)
        assertEquals("sha256 a1a1a1a1a1a1", qwen.hashText)
        assertEquals("2.4 GB", qwen.sizeText)
        assertEquals("qairt · big slot · load 4.2 s · 38 ms/unit · 8k context", qwen.specLine)
        assertEquals(listOf("This phone · loaded · p50 38 ms · BENCH 1 h ago", "Sales · installed · p50 41 ms · BENCH 24 h ago"), qwen.chips.map { it.label(now) })
        val small = s.groups.first { it.first == "llm.json" }.second.first { it.id == "qwen3-1.7b-q4" }
        assertFalse(small.pinned)
        assertEquals("This phone · available", small.self!!.label(now))
        assertEquals("Accounts · loaded · p50 24 ms · BENCH 2 h ago", small.chips[1].label(now))
        val yolo = s.groups.first { it.first == "detect" }.second.single()
        assertTrue("the registry marks it installed here", yolo.installedHere)
        assertTrue("the pool marks it loaded here", yolo.loadedHere)
        val whisper = s.groups.first { it.first == "asr" }.second.single()
        assertFalse(whisper.installedHere)
        assertEquals("Sales · loaded", whisper.chips[1].label(now))
    }

    @Test fun actionsGoThroughThePortAndUpdateThisPhone() = runTest {
        val vm = vm()
        vm.refresh(); settle()
        vm.download("whisper-base"); settle()
        assertEquals("Downloaded whisper-base", vm.state.value.message)
        assertTrue(vm.state.value.row(port.catalogState.value.first { it.modelId == "whisper-base" }).installedHere)
        vm.load("whisper-base"); settle()
        assertTrue(vm.state.value.row(port.catalogState.value.first { it.modelId == "whisper-base" }).loadedHere)
        vm.unload("whisper-base"); settle()
        assertFalse(vm.state.value.row(port.catalogState.value.first { it.modelId == "whisper-base" }).loadedHere)
        port.benchP50 = 212.4
        vm.bench("whisper-base"); settle()
        assertEquals("BENCH whisper-base: p50 212 ms", vm.state.value.message)
        assertEquals("This phone · installed · p50 212 ms · BENCH 0 s ago", vm.state.value.row(port.catalogState.value.first { it.modelId == "whisper-base" }).self!!.label(now))
        vm.pin("llm.json", "qwen3-1.7b-q4"); settle()
        assertEquals("qwen3-1.7b-q4", vm.state.value.pinned["llm.json"])
        assertEquals(listOf("download whisper-base", "load whisper-base", "unload whisper-base", "bench whisper-base", "pin llm.json=qwen3-1.7b-q4"), port.actions)
        assertTrue(vm.state.value.busy.isEmpty())

        port.failNext = IllegalStateException("no space left")
        vm.download("gemma3-4b-vlm"); settle()
        assertEquals("Could not download gemma3-4b-vlm: no space left", vm.state.value.message)
    }

    @Test fun registryErrorIsShown() = runTest {
        val broken = object : ModelRegistry {
            override fun installed(): List<ModelManifest> = throw IllegalStateException("files/models unreadable")
            override fun manifest(id: String): ModelManifest? = null
            override fun dir(id: String): File? = null
        }
        val vm = vm(reg = broken)
        vm.refresh(); settle()
        assertTrue(vm.state.value.error!!.contains("unreadable"))
        val empty = vm(reg = FakeModelRegistry())
        empty.refresh(); settle()
        assertNull(empty.state.value.error)
        assertEquals("Big slot: empty", empty.state.value.bigText)
    }
}
