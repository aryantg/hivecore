package com.hivecore.runtime.llm

import com.hivecore.core.ChatMsg
import com.hivecore.core.GenStats
import com.hivecore.core.LlmBackend
import com.hivecore.core.NodeRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetsAndStatsTest {
    @Test fun fewShotAssetsHaveThreeParsableShotsPerRole() {
        for (role in NodeRole.values()) {
            val shots = TestAssets.fewShots(role)
            assertEquals(role.name, 3, shots.size)
            shots.forEach { s -> assertTrue("${role.name}: ${s.question}", IntentParser.parse(s.answerJson).isSuccess) }
            // exactly one not_relevant example per role (the cross-department question)
            assertEquals(1, shots.count { IntentParser.parse(it.answerJson).getOrThrow().notRelevant })
        }
        val hr = TestAssets.fewShots(NodeRole.HR)
        val sales = TestAssets.fewShots(NodeRole.SALES)
        assertEquals("""{"skip":[],"intents":[{"table":"employees","metric":"avg","column":"salary","filters":[{"column":"region","op":"eq","value":"South"}]}]}""", hr[0].answerJson)
        // the two-part shot: the other department's measure is skipped, only the headcount part is answered
        assertEquals(1, IntentParser.parse(hr[1].answerJson).getOrThrow().intents.size)
        assertEquals(1, IntentParser.parse(sales[2].answerJson).getOrThrow().intents.size)
        assertTrue(sales[1].answerJson.contains("\"value\":\"2026-03\""))
        val acc = TestAssets.fewShots(NodeRole.ACCOUNTS)
        assertTrue(acc[0].answerJson.contains("\"value\":\"2026-10\""))
        assertTrue(acc[1].answerJson.contains("UNPAID"))
    }

    @Test fun templateAssetHasTheSixTourEntries() {
        val tm = TestAssets.templates()
        val ids = NodeRole.values().flatMap { r -> tm.sentences(r).map { tm.match(it, r)!!.id } }.toSet()
        assertEquals(setOf("Q1", "Q2", "Q3", "Q5", "Q6", "Q7"), ids)
    }

    @Test fun chatTemplateExact() {
        val text = ChatTemplate.qwen(listOf(ChatMsg("system", "S"), ChatMsg("user", "U"), ChatMsg("assistant", "A"), ChatMsg("user", "Q")))
        assertEquals("<|im_start|>system\nS<|im_end|>\n<|im_start|>user\nU<|im_end|>\n<|im_start|>assistant\nA<|im_end|>\n<|im_start|>user\nQ<|im_end|>\n<|im_start|>assistant\n", text)
    }

    @Test fun tokStatsFormat() {
        val s = GenStats(promptTokens = 412, genTokens = 48, prefillMs = 180, decodeMs = 2790, tokPerSec = 17.204)
        assertEquals("prefill 412 tok in 180 ms · 48 tok in 2790 ms · 17.2 tok/s", TokStats.format(s))
        assertEquals("NPU: prefill 412 tok in 180 ms · 48 tok in 2790 ms · 17.2 tok/s", TokStats.format(LlmBackend.NPU, s))
        assertEquals("no backend: prefill 412 tok in 180 ms · 48 tok in 2790 ms · 17.2 tok/s", TokStats.format(null, s))
    }
}
