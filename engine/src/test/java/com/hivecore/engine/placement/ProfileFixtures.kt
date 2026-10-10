package com.hivecore.engine.placement

import com.hivecore.core.CapabilityState
import com.hivecore.core.Cooldown
import com.hivecore.core.Link
import com.hivecore.core.LinkKind
import com.hivecore.core.LlmBackend
import com.hivecore.core.ModelEntry
import com.hivecore.core.Profile
import com.hivecore.runtime.catalog.ModelCatalog

/**
 * Recorded-style profiles of a five-phone swarm (spec 4.6) over the shipped catalog's builds, for the solver and
 * protocol tests: a1 the orchestrator on LAN with the LLM warm, b2 a LAN phone on the charger with the VLM warm,
 * c3 a remote phone on a direct WAN link with the LLM warm, d4 a Bluetooth-only phone, e5 a phone in use.
 */
object ProfileFixtures {
    const val T0 = 1_760_000_000_000L
    const val SHA_LLM = "49777377c3de9027f39110d8e4607d7b92ebf03a7a2050e699040f009d59f5de"
    const val SHA_GGUF = "aa8353e0d0fca3a0041828701e90db7635197400f040676d11d7798665fa316e"
    const val SHA_VLM = "d84954f0928a68e77f56872ba102573b2c1c54ed1e6ed66f1d7c6203bb37090b"
    const val SHA_ASR = "81f4361dc0f9a0d0fc972857b0d29a5c36bb41f5ae647fb5f880e226be417a12"
    const val SHA_DET = "de23026bb5f52c8e5a710a14fc001866f63655157ef5768deb945d3b0ac048da"
    const val SHA_SD = "362e373e616dd49ac7693ac2faf11355dcc5b42caf261f06efd5b33079731235"
    const val SHA_SR = "1388799e279e0af27be40ad197b36a5fb110c80a33286380df6dfeba876c4161"

    val LLM = ModelEntry("qwen3-4b-npu", "llm.json", "qairt", SHA_LLM, 3_194_863_646, 6000, 4000, 4096, true)
    val LLM_TEXT = LLM.copy(capability = "llm.text")
    val GGUF = ModelEntry("qwen2.5-coder-1.5b-instruct-q4_0", "llm.json", "llama_cpp", SHA_GGUF, 1_066_227_264, 3000, 6000, 2048, true)
    val GGUF_TEXT = GGUF.copy(capability = "llm.text")
    val VLM = ModelEntry("qwen3vl", "vlm", "qairt", SHA_VLM, 4_362_076_160, 8000, 1500, 4096, true)
    val ASR = ModelEntry("whisper_tiny", "asr", "ort_qnn", SHA_ASR, 118_489_088, 1500, 100, 448, false)
    val OCR = ModelEntry("mlkit_text_latin", "ocr", "mlkit", "unpinned", 0, 300, 250, 0, false)
    val DET = ModelEntry("yolox_w8a8", "detect", "litert_qnn", SHA_DET, 9_437_184, 300, 40, 0, false)
    val SD = ModelEntry("sd15", "gen.image", "ort_qnn", SHA_SD, 1_107_296_256, 7000, 9000, 77, true)
    val SR = ModelEntry("real_esrgan_w8a8", "superres", "litert_qnn", SHA_SR, 2_097_152, 1200, 50, 0, false)
    val UPSCALE = SR.copy(capability = "upscale", unitMsP50 = 3000)

    val entries: List<ModelEntry> = listOf(LLM, LLM_TEXT, GGUF, GGUF_TEXT, VLM, ASR, OCR, DET, SD, SR, UPSCALE)
    val catalog = ModelCatalog(entries, "fixture-1")

    private fun state(e: ModelEntry, warm: Boolean) = CapabilityState(e.capability, e.modelId, e.sha256, warm)

    /** Orchestrator: LAN, on battery at 72 %, LLM warm in the big slot, detect warm, the VLM installed but cold. */
    val a1 = Profile("a1", T0, backend = LlmBackend.NPU, tokPerSec = 21.0, idle = true, batteryPct = 72, thermal = 0,
        workerOptIn = true, eligible = true, heavyOk = false, queueDepth = 0, big = "llm", warm = listOf("qwen3-4b-npu", "yolox_w8a8"),
        p50 = mapOf("qwen3-4b-npu" to 3900.0, "yolox_w8a8" to 38.0), link = null,
        capabilities = listOf(state(LLM, true), state(LLM_TEXT, true), state(GGUF, false), state(GGUF_TEXT, false), state(VLM, false),
            state(OCR, true), state(DET, true), state(SR, false), state(UPSCALE, false)))

    /** LAN phone on the charger (heavyOk): VLM warm, LLM cold, whisper and sd15 installed. */
    val b2 = Profile("b2", T0 - 4_000, backend = LlmBackend.NPU, idle = true, charging = true, batteryPct = 90, thermal = 1,
        workerOptIn = true, eligible = true, heavyOk = true, queueDepth = 0, big = "vlm", warm = listOf("qwen3vl"),
        p50 = mapOf("qwen3vl" to 1400.0, "qwen3-4b-npu" to 4050.0, "yolox_w8a8" to 41.0), link = Link(LinkKind.LAN, 48_000, 6),
        capabilities = listOf(state(LLM, false), state(LLM_TEXT, false), state(VLM, true), state(ASR, false), state(OCR, true),
            state(DET, false), state(SD, false), state(SR, false), state(UPSCALE, false)))

    /** Remote phone on a direct WAN link: LLM warm, not on the charger, sd15 installed but no heavyOk. */
    val c3 = Profile("c3", T0 - 11_000, backend = LlmBackend.NPU, idle = true, batteryPct = 64, thermal = 0,
        workerOptIn = true, eligible = true, heavyOk = false, queueDepth = 0, big = "llm", warm = listOf("qwen3-4b-npu"),
        p50 = mapOf("qwen3-4b-npu" to 4200.0), link = Link(LinkKind.WAN_DIRECT, 6_000, 120),
        capabilities = listOf(state(LLM, true), state(LLM_TEXT, true), state(GGUF, false), state(GGUF_TEXT, false), state(VLM, false),
            state(OCR, true), state(DET, false), state(SD, false)))

    /** Bluetooth-only phone: eligible, LLM cold, small models only. */
    val d4 = Profile("d4", T0 - 2_000, backend = LlmBackend.NPU, idle = true, batteryPct = 55, thermal = 0,
        workerOptIn = true, eligible = true, heavyOk = false, queueDepth = 0, big = "none", warm = emptyList(),
        link = Link(LinkKind.BT, 1_200, 50),
        capabilities = listOf(state(LLM, false), state(LLM_TEXT, false), state(OCR, true), state(DET, false)))

    /** A phone in its owner's hands: not eligible whatever it holds. */
    val e5 = Profile("e5", T0 - 1_000, backend = LlmBackend.NPU, idle = false, batteryPct = 80, workerOptIn = true, eligible = false,
        heavyOk = false, big = "vlm", warm = listOf("qwen3vl"), link = Link(LinkKind.LAN, 40_000, 8),
        capabilities = listOf(state(LLM, false), state(VLM, true), state(DET, false)))

    val all: List<Profile> = listOf(a1, b2, c3, d4, e5)

    fun cooled(p: Profile, capability: String, untilTs: Long = T0 + 600_000) = p.copy(cooldowns = p.cooldowns + Cooldown(capability, untilTs))
}
