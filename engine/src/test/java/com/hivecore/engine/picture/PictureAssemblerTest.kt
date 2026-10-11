package com.hivecore.engine.picture

import com.hivecore.core.Op
import com.hivecore.core.TaskSpec
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PictureAssemblerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val gen = TaskSpec("i", Op.GEN_IMAGE, buildJsonObject { put("prompt", "my team, clean modern flat illustration"); put("seed", 7) })
    private val render = TaskSpec("p", Op.RENDER, buildJsonObject { put("kind", "picture"); put("subject", "my team") }, inputs = listOf("i"))

    private fun drawnOutput(sha: String): JsonElement = buildJsonObject {
        put("op", "GEN_IMAGE"); put("model", "sd15"); put("blocked", false)
        putJsonArray("items") { add(buildJsonObject { put("seed", 7); put("sha256", sha); put("w", 512); put("h", 512) }) }
    }

    @Test fun thePictureIsCopiedBesideTheDecksAndTheSentenceStatesTheFacts() {
        val blobs = tmp.newFolder("blobs"); val out = tmp.newFolder("decks")
        val sha = "ab".repeat(32)
        File(blobs, sha).writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(PictureAssembler.isPictureStep(render))
        val p = PictureAssembler.assemble(render, listOf(gen to drawnOutput(sha)), { File(blobs, it) }, out)
        assertTrue(p.drawn)
        assertEquals(File(out, "picture-${sha.take(12)}.png").absolutePath, p.file)
        assertEquals(3, File(p.file!!).length())
        assertEquals("my team", p.subject)
        assertEquals(7L, p.seed)
        assertEquals("An illustration of my team, drawn from your words on a phone of the swarm, 512×512, seed 7. It is generated art: no photo or table of yours went into it.", p.summary)
        // the artifact round-trips through the RENDER output
        val json = p.toJson()
        assertTrue(Picture.isPicture(json))
        assertEquals(p, Picture.fromJson(json))
        assertNull(Picture.fromJson(buildJsonObject { put("kind", "deck") }))
    }

    @Test fun aMissingBlobOrABlockedPromptGivesAnExplainedBlank() {
        val missing = PictureAssembler.assemble(render, listOf(gen to drawnOutput("cd".repeat(32))), { null }, null)
        assertFalse(missing.drawn)
        assertEquals("No picture of my team: the picture did not reach this phone.", missing.summary)
        val blocked = buildJsonObject { put("op", "GEN_IMAGE"); put("blocked", true); put("stage", "prompt"); put("category", "violence"); putJsonArray("items") {} }
        val b = PictureAssembler.assemble(render, listOf(gen to blocked), { null }, null)
        assertNotNull(b.blocked)
        assertTrue(b.summary, b.summary.contains("safety filter (violence)"))
    }

    @Test fun theSubjectFallsBackToThePromptsFirstClause() {
        val noSubject = TaskSpec("p", Op.RENDER, buildJsonObject { put("kind", "picture") }, inputs = listOf("i"))
        val p = PictureAssembler.assemble(noSubject, listOf(gen to drawnOutput("ef".repeat(32))), { null }, null)
        assertEquals("my team", p.subject)
    }
}
