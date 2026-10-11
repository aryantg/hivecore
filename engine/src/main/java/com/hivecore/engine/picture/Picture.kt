package com.hivecore.engine.picture

import com.hivecore.core.Canon
import com.hivecore.core.Op
import com.hivecore.core.TaskSpec
import com.hivecore.engine.deck.Deck
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * A picture as the swarm produces it (spec amendment "pictures from a description"): the output of a `RENDER` step
 * with `kind: picture` over a `GEN_IMAGE` step. The chat draws [file]; [summary] is the answer sentence, written by
 * [PictureAssembler] from the step's facts (R2: no model writes it). A picture that was not drawn carries [blocked].
 */
@Serializable data class Picture(
    /** what the user asked for, as they said it ("my team") */
    val subject: String,
    /** the prompt the image model was given */
    val prompt: String,
    /** the PNG on the orchestrator (absolute path), when it reached this phone */
    val file: String? = null,
    val sha256: String? = null,
    val seed: Long? = null,
    val width: Int = 0,
    val height: Int = 0,
    val model: String? = null,
    /** why there is no picture (null = drawn) */
    val blocked: String? = null,
    val summary: String = "",
) {
    val drawn: Boolean get() = file != null

    fun toJson(): JsonElement = JsonObject(mapOf(Deck.KIND_KEY to JsonPrimitive(KIND)) + Canon.json.encodeToJsonElement(serializer(), this).jsonObject)

    companion object {
        const val KIND = "picture"

        fun isPicture(e: JsonElement?): Boolean = (e as? JsonObject)?.get(Deck.KIND_KEY)?.let { (it as? JsonPrimitive)?.content } == KIND

        fun fromJson(e: JsonElement?): Picture? {
            if (!isPicture(e)) return null
            return runCatching { Canon.json.decodeFromJsonElement(serializer(), JsonObject((e as JsonObject) - Deck.KIND_KEY)) }.getOrNull()
        }
    }
}

/**
 * The last step of a picture plan (`RENDER` with `kind: picture`): finds the PNG the `GEN_IMAGE` step published (by
 * sha256 in the blob store), copies it beside the decks as `picture-<hash12>.png` so the file provider can hand it
 * out, and writes the one-sentence answer. Runs on the orchestrator like every reduce.
 */
object PictureAssembler {
    const val KIND = Picture.KIND
    const val PARAM_KIND = Deck.KIND_KEY
    const val PARAM_SUBJECT = "subject"

    fun isPictureStep(spec: TaskSpec): Boolean =
        spec.op == Op.RENDER && (spec.params[PARAM_KIND] as? JsonPrimitive)?.content == KIND

    /**
     * [inputs] are the step's inputs with the step that produced each; [imageFile] finds a PNG by sha256; [outDir]
     * receives the copy (null = the blob store path is used as is).
     */
    fun assemble(spec: TaskSpec, inputs: List<Pair<TaskSpec, JsonElement>>, imageFile: (String) -> File? = { null }, outDir: File? = null): Picture {
        val gen = inputs.firstOrNull { (s, _) -> s.op == Op.GEN_IMAGE || s.op == Op.GEN_VARIANTS }
        val genSpec = gen?.first
        val prompt = genSpec?.let { str(it.params, "prompt") } ?: ""
        val subject = str(spec.params, PARAM_SUBJECT) ?: prompt.substringBefore(',').ifBlank { "the request" }
        val out = gen?.second as? JsonObject
        val model = out?.let { str(it, "model") }
        fun blocked(why: String) = Picture(subject, prompt, model = model, blocked = why, summary = "No picture of $subject: $why.")

        if (out == null) return blocked("no image step reached this phone")
        if ((out["blocked"] as? JsonPrimitive)?.contentOrNull == "true" && (out["stage"] as? JsonPrimitive)?.contentOrNull == "prompt")
            return blocked("the description was held back by the safety filter" + (str(out, "category")?.let { " ($it)" } ?: ""))
        val items = (out["items"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        if (items.isEmpty()) return blocked("the image model produced nothing")
        val drawn = items.firstOrNull { (it["blocked"] as? JsonPrimitive)?.contentOrNull != "true" }
            ?: return blocked("the picture was held back by the safety check")
        val sha = str(drawn, "sha256") ?: return blocked("the picture has no hash")
        val src = imageFile(sha)?.takeIf { it.isFile } ?: return blocked("the picture did not reach this phone")
        val file = if (outDir == null) src else {
            outDir.mkdirs()
            val f = File(outDir, "picture-${sha.take(12)}.png")
            if (!f.isFile || f.length() != src.length()) src.copyTo(f, overwrite = true)
            f
        }
        val seed = (drawn["seed"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
        val w = (drawn["w"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
        val h = (drawn["h"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
        val size = if (w > 0 && h > 0) ", ${w}×${h}" else ""
        val summary = "An illustration of $subject, drawn from your words on a phone of the swarm$size" +
            (seed?.let { ", seed $it" } ?: "") + ". It is generated art: no photo or table of yours went into it."
        return Picture(subject, prompt, file.absolutePath, sha, seed, w, h, model, summary = summary)
    }

    private fun str(p: JsonObject, k: String): String? = (p[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
}
