package com.hivecore.core

/**
 * Spec section 18.4: one table drives the compiler, the splitter, the merger and the executors.
 *
 * `{op, class, capability?, axes: {shard?: merge, chunk?: merge, fanout?: merge}, arity, inputShape, outputShape,
 * replicas, judge?}`. Adding an op means adding a row here (plus a splitter rule if it needs one and an executor);
 * nothing else changes. [OpClasses] and [Mergeability] are the legacy views over the same table and are kept for the
 * adopted engine code; a test pins that they agree with the registry.
 */
data class OpInfo(
    val op: Op,
    val cls: OpClass,
    /** catalog capability the op needs (spec section 4.3), null for REDUCE/OWNER ops with no model */
    val capability: String?,
    val shard: MergeKind?,
    val chunk: MergeKind?,
    val fanout: MergeKind?,
    /** how many upstream inputs the op takes */
    val arity: IntRange,
    /** "items" | "blobs" | "text" | "audio" | "video" | "image" | "table" | "tables" | "json" | "prompt" */
    val inputShape: String,
    /** "json" | "table" | "list" | "text" | "image" | "transcript" | "picks" */
    val outputShape: String,
    /** replicas per data-free attempt: 2 for LLM/VLM (compared), else 1 */
    val replicas: Int,
    /** the op that scores this op's output as a local judge (spec section 12.1), if any */
    val judge: Op? = null,
    /**
     * The fewest runs a chunk of this op may settle on when the decision model rates it easy (decision point B):
     * equal to [replicas], so no LLM / VLM op drops to a single run until its row says so.
     */
    val minReplicas: Int = replicas,
) {
    val axes: Map<String, MergeKind> get() = buildMap {
        shard?.let { put("shard", it) }; chunk?.let { put("chunk", it) }; fanout?.let { put("fanout", it) }
    }
    /** The merge kind for an axis name ("shard" | "chunk" | "fanout"), null when the op cannot split along it. */
    fun mergeFor(axis: String): MergeKind? = axes[axis.lowercase()]
}

object OpRegistry {
    private const val ONE = 1

    private fun capabilityOf(op: Op): String? = when (op) {
        Op.OCR -> "ocr"
        Op.EMBED, Op.KNN -> "embed"
        Op.DETECT -> "detect"
        Op.SEGMENT -> "segment"
        Op.IMG_CLASSIFY, Op.TAG -> "classify"
        Op.IQA, Op.SCORE_IMAGE -> "iqa"
        Op.ASR -> "asr"
        Op.VLM_ASK -> "vlm"
        Op.GEN_IMAGE, Op.GEN_VARIANTS -> "gen.image"
        Op.SUPERRES -> "superres"
        Op.UPSCALE -> "upscale"
        Op.FIELDS, Op.EXTRACT, Op.METADATA, Op.DOC_CLASSIFY, Op.TEXT_CLASSIFY, Op.LLM_MAP, Op.EVAL, Op.SYNTH_GEN -> "llm.json"
        Op.SUMMARIZE, Op.GEN_TEXT, Op.PHRASE -> "llm.text"
        Op.INFER, Op.LAYOUT, Op.REDACT, Op.VERIFY, Op.BENCH, Op.IMG_PREPROC -> null
        else -> null
    }

    private fun arityOf(op: Op): IntRange = when (op) {
        Op.RATIO, Op.DIFF, Op.RECONCILE -> 2..2
        Op.TREND, Op.RANK, Op.THRESHOLD, Op.SHARE -> 1..1
        Op.JOIN -> 2..Int.MAX_VALUE
        Op.RENDER -> 1..Int.MAX_VALUE
        Op.AGG, Op.DOC, Op.FRAME_SAMPLE, Op.VAD_SPLIT -> 0..0
        else -> 0..Int.MAX_VALUE
    }

    private fun inputShapeOf(op: Op): String = when (op) {
        Op.AGG -> "table"; Op.DOC -> "text"
        Op.JOIN, Op.RATIO, Op.DIFF, Op.SHARE, Op.TREND, Op.RANK, Op.THRESHOLD, Op.RECONCILE, Op.RENDER, Op.TRACK_AGG -> "tables"
        Op.OCR, Op.IMG_CLASSIFY, Op.DETECT, Op.SEGMENT, Op.TAG, Op.IQA, Op.VLM_ASK, Op.IMG_PREPROC, Op.SCORE_IMAGE, Op.LAYOUT -> "blobs"
        Op.ASR, Op.VAD_SPLIT -> "audio"; Op.FRAME_SAMPLE -> "video"; Op.SUPERRES, Op.UPSCALE -> "image"
        Op.SUMMARIZE, Op.EXTRACT, Op.PHRASE -> "text"
        Op.GEN_IMAGE, Op.GEN_VARIANTS, Op.GEN_TEXT, Op.SYNTH_GEN -> "prompt"
        Op.KNN, Op.CLUSTER -> "json"
        else -> "items"
    }

    private fun outputShapeOf(op: Op): String = when (op) {
        Op.AGG, Op.JOIN, Op.RATIO, Op.DIFF, Op.SHARE, Op.TREND, Op.RANK, Op.THRESHOLD, Op.RECONCILE, Op.TRACK_AGG -> "table"
        Op.RENDER, Op.DOC, Op.GEN_TEXT, Op.PHRASE -> "text"
        Op.ASR -> "transcript"; Op.SUMMARIZE, Op.EXTRACT -> "picks"
        Op.GEN_IMAGE, Op.GEN_VARIANTS, Op.SUPERRES, Op.UPSCALE -> "image"
        Op.OCR, Op.EMBED, Op.DETECT, Op.SEGMENT, Op.TAG, Op.IQA, Op.KNN, Op.CLUSTER, Op.VAD_SPLIT, Op.FRAME_SAMPLE -> "list"
        else -> "json"
    }

    private fun judgeOf(op: Op): Op? = when (op) {
        Op.EXTRACT, Op.SUMMARIZE, Op.FIELDS, Op.LLM_MAP, Op.GEN_TEXT -> Op.VERIFY
        Op.GEN_IMAGE, Op.GEN_VARIANTS -> Op.SCORE_IMAGE
        else -> null
    }

    val entries: Map<Op, OpInfo> = Op.entries.associateWith { op ->
        val cls = OpClasses.of(op)
        OpInfo(
            op = op, cls = cls, capability = capabilityOf(op),
            shard = Mergeability.of(op), chunk = Mergeability.chunkKind(op), fanout = Mergeability.fanOutKind(op),
            arity = arityOf(op), inputShape = inputShapeOf(op), outputShape = outputShapeOf(op),
            replicas = if (cls == OpClass.LLM || cls == OpClass.VLM) 2 else ONE, judge = judgeOf(op),
        )
    }

    operator fun get(op: Op): OpInfo = entries.getValue(op)
    fun byName(name: String): OpInfo? = Op.entries.firstOrNull { it.name == name }?.let { entries[it] }
    /** Every capability named by any op; the compiler checks each against the catalog. */
    val capabilities: Set<String> = entries.values.mapNotNull { it.capability }.toSet()
    /** Ops that may run on a node (anything that is not OWNER or REDUCE). */
    val nodeOps: Set<Op> = entries.filterValues { it.cls != OpClass.OWNER && it.cls != OpClass.REDUCE }.keys
}
