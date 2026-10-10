package com.hivecore.runtime.models

import com.hivecore.core.ModelSession

/**
 * What the LiteRT and ORT sessions expose beyond [ModelSession]: their graphs (one per model file, by file stem)
 * and tensor descriptions, so callers can build inputs (and INFER can seed them) without opening the model.
 *
 * Run inputs (`ModelRuntime.run`): the optional key [GRAPH] (String) picks the graph by file stem (default: the
 * first). Tensors are keyed by name, `<graph>.<name>`, or input index ("0", "1", …); values are anything
 * [TensorIo.toBytes] accepts. Outputs are output name → raw little-endian [ByteArray], in model output order.
 */
interface TensorSession : ModelSession {
    val graphs: List<String>
    /** Wall time of [com.hivecore.core.ModelRuntime.load] (includes the first HTP compile if not cached). */
    val loadMs: Long
    fun inputs(graph: String? = null): List<TensorDesc>
    fun outputs(graph: String? = null): List<TensorDesc>

    companion object {
        const val GRAPH = "graph"

        /** The value for input [d] (#[idx]) in [inputs], trying its name, `<graph>.<name>`, normalised names, the index. */
        fun pick(inputs: Map<String, Any>, d: TensorDesc, idx: Int): Any? {
            inputs[d.name]?.let { return it }
            inputs["${d.graph}.${d.name}"]?.let { return it }
            val norm = normalize(d.name)
            inputs.entries.firstOrNull { it.key != GRAPH && normalize(it.key.substringAfterLast('.')) == norm }?.let { return it.value }
            return inputs[idx.toString()]
        }

        /** "serving_default_image:0" → "image". */
        fun normalize(n: String): String = n.removePrefix("serving_default_").substringBefore(':').lowercase()

        fun graphOf(inputs: Map<String, Any>, graphs: List<String>): String {
            val g = inputs[GRAPH] as? String ?: return graphs.first()
            return graphs.firstOrNull { it == g || it == g.substringBeforeLast('.') }
                ?: throw IllegalArgumentException("no graph '$g' (have ${graphs.joinToString()})")
        }
    }
}
