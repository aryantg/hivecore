package com.hivecore.core

import com.hivecore.core.Op

/**
 * Swarm plan §4B.2: how the outputs of the chunks of one split op are recombined. Every merge runs on the commander,
 * in chunk-index order, so the result never depends on which phone ran which chunk (R5).
 *
 * Change request (core): this enum and [Mergeability] belong next to [Op] in core/Swarm.kt once the integrator
 * freezes the splitting contract; they live here until then.
 */
enum class MergeKind {
    /** shard outputs concatenated in item order (the `items` lists) — equal to the unsplit run */
    CONCAT,
    /** ordered chunks of one input with overlap removed (text sentences, transcript segments, image tiles) — equal to the unsplit run */
    STITCH,
    /** pairwise extractive reduce: the merge picks sentence ids from its children — deterministic, not equal to one pass */
    TREE,
    /** count / sum / min / max / histogram, mean via sum + count; AggTable partials merged by group key — equal to the unsplit run */
    MONOID,
    /** merge of per-chunk top-k (score desc, id asc) — equal to the unsplit run */
    TOPK,
    /** fan-out of one input: majority with confidence = agreeing / runs */
    VOTE,
    /** fan-out of one input: candidates ordered by a deterministic score */
    RANK,
}

/** The split axis an input implies (§4B.1). */
enum class SplitAxis { SHARD, CHUNK, FANOUT }

/**
 * Which ops may be split and how their chunk outputs merge (§4B.2). [of] is the op's declared merge kind for any
 * split (null = the op never splits, not even across inputs: reduce ops, owner-only ops, CLUSTER which needs every
 * vector at once). [withinInput] says whether one large input (a long text, a recording, an image, a table) may be cut
 * into chunks for this op; ops that are one model generation (GEN_TEXT, GEN_IMAGE, VLM_ASK, METADATA …) parallelise
 * only across inputs (shards) or across seeds/permutations (fan-out).
 */
object Mergeability {

    fun of(op: Op): MergeKind? = when (op) {
        Op.OCR, Op.FIELDS, Op.IMG_CLASSIFY, Op.DETECT, Op.SEGMENT, Op.TAG, Op.IQA, Op.VLM_ASK, Op.INFER, Op.EMBED,
        Op.TEXT_CLASSIFY, Op.LAYOUT, Op.DOC_CLASSIFY, Op.METADATA, Op.REDACT, Op.EVAL, Op.BENCH, Op.SYNTH_GEN,
        Op.SYNTH_VALIDATE, Op.IMG_PREPROC, Op.GEN_IMAGE, Op.GEN_VARIANTS, Op.GEN_TEXT, Op.VERIFY, Op.LLM_MAP -> MergeKind.CONCAT
        Op.ASR, Op.SUPERRES, Op.UPSCALE -> MergeKind.STITCH
        Op.SUMMARIZE, Op.EXTRACT -> MergeKind.TREE
        Op.AGG -> MergeKind.MONOID
        Op.KNN -> MergeKind.TOPK
        Op.SCORE_IMAGE, Op.RANK -> MergeKind.RANK
        Op.DOC, Op.JOIN, Op.RATIO, Op.DIFF, Op.SHARE, Op.TREND, Op.THRESHOLD, Op.RECONCILE, Op.RENDER, Op.PHRASE,
        Op.CLUSTER, Op.VAD_SPLIT, Op.FRAME_SAMPLE, Op.TRACK_AGG -> null
    }

    /** One large input may be cut into chunks for [op]; the merge kind used then is [chunkKind]. */
    fun withinInput(op: Op): Boolean = chunkKind(op) != null

    /**
     * Merge kind when one input is chunked: sentence-level text ops stitch (overlap de-duplicated by sentence id),
     * DETECT/IMG_CLASSIFY/TAG/IQA over a video's frame ranges and OCR over a document's pages concatenate.
     */
    fun chunkKind(op: Op): MergeKind? = when (op) {
        Op.ASR, Op.SUPERRES, Op.UPSCALE, Op.TEXT_CLASSIFY, Op.EMBED -> MergeKind.STITCH
        Op.SUMMARIZE, Op.EXTRACT -> MergeKind.TREE
        Op.AGG -> MergeKind.MONOID
        Op.KNN -> MergeKind.TOPK
        Op.DETECT, Op.IMG_CLASSIFY, Op.TAG, Op.IQA, Op.SEGMENT, Op.OCR, Op.REDACT -> MergeKind.CONCAT
        else -> null
    }

    /** Fan-out (ensemble) merge: closed choices vote, scored candidates rank, generators concatenate per seed. */
    fun fanOutKind(op: Op): MergeKind? = when (op) {
        Op.TEXT_CLASSIFY, Op.DOC_CLASSIFY, Op.VLM_ASK, Op.VERIFY, Op.EVAL, Op.LLM_MAP -> MergeKind.VOTE
        Op.SCORE_IMAGE, Op.RANK -> MergeKind.RANK
        Op.GEN_IMAGE, Op.GEN_VARIANTS, Op.GEN_TEXT, Op.SYNTH_GEN -> MergeKind.CONCAT
        else -> null
    }
}
