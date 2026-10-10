package com.hivecore.engine.placement

import com.hivecore.core.Cooldown
import com.hivecore.core.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CooldownTableTest {
    @Test fun tenMinutesPerNodeAndCapabilityPublishedInTheProfile() {
        var now = 1_000_000L
        val t = CooldownTable({ now })
        assertEquals(600_000L, t.durationMs)
        t.mark("b2", "vlm")
        assertTrue(t.isDown("b2", "vlm")); assertFalse(t.isDown("b2", "llm.json")); assertFalse(t.isDown("a1", "vlm"))
        assertEquals(listOf(Cooldown("vlm", 1_600_000L)), t.of("b2"))
        now += 599_999; assertTrue(t.isDown("b2", "vlm"))
        now += 1; assertFalse(t.isDown("b2", "vlm")); assertTrue(t.of("b2").isEmpty()); assertTrue(t.all().isEmpty())
    }

    @Test fun laterMarksExtendNeverShortenAndProfilesCount() {
        var now = 0L
        val t = CooldownTable({ now }, durationMs = 100)
        t.mark("n", "asr", untilTs = 500)
        t.mark("n", "asr")                                       // now + 100 = 100 < 500: keeps 500
        assertEquals(500L, t.of("n").single().untilTs)
        val peer = Profile("p", 0, cooldowns = listOf(Cooldown("ocr", 50)))
        assertTrue(t.isDown("p", "ocr", peer))
        now = 50; assertFalse(t.isDown("p", "ocr", peer))
        t.clear("n", "asr"); assertFalse(t.isDown("n", "asr"))
    }
}
