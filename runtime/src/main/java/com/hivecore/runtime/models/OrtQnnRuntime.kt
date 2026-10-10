package com.hivecore.runtime.models

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import com.hivecore.core.ModelManifest
import com.hivecore.core.ModelRuntime
import com.hivecore.core.ModelSession
import com.hivecore.core.SLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * AI Hub `precompiled_qnn_onnx` assets (Whisper, Stable Diffusion): ONNX Runtime 1.29 + QNN EP (swarm plan §4A.2).
 *  - QNN EP with `backend_path` = `<nativeLibraryDir>/libQnnHtp.so` (the app's QAIRT 2.50), `htp_performance_mode`
 *    burst, and `session.disable_cpu_ep_fallback` = 1 unless [cpuFallback] (a silent CPU fallback would break the
 *    NPU-only comparison rule).
 *  - w8a16 graphs have uint16 IO, which the Java API cannot create. When `<graph>_q.onnx` exists next to the graph
 *    (a CPU-only QuantizeLinear adapter generated from AI Hub's metadata.json by tools/fetch_aihub_models.py), the
 *    caller's real-valued inputs for those tensors go through the adapter, and its uint16 OnnxTensors are fed to
 *    the unmodified EPContext graph. Rewriting the EPContext graph itself fails ("Input/Output name not found").
 *  - Outputs are read as raw bytes (uint16 outputs too); dequantize with the manifest's outputSpec scale/zeroPoint.
 * Graphs: every `.onnx` file of the manifest except `*_q.onnx` / `*_f32.onnx`, named by file stem.
 */
class OrtQnnRuntime(
    private val nativeLibraryDir: String,
    private val cpuFallback: Boolean = false,
) : ModelRuntime {
    override val id: String = ID

    override fun supports(m: ModelManifest): Boolean = m.runtime == ID

    override suspend fun load(m: ModelManifest, dir: File): Result<ModelSession> = withContext(Dispatchers.IO) {
        runCatching {
            val t0 = System.currentTimeMillis()
            val env = OrtEnvironment.getEnvironment()
            val onnx = m.files.filter { it.endsWith(".onnx") && !it.endsWith("_q.onnx") && !it.endsWith("_f32.onnx") }.map { File(dir, it) }
            require(onnx.isNotEmpty()) { "${m.id}: no .onnx graph in ${m.files}" }
            val cpu = m.backend.equals("cpu", ignoreCase = true)
            val graphs = LinkedHashMap<String, Graph>()
            try {
                for (f in onnx) {
                    val opts = OrtSession.SessionOptions().apply {
                        setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING)
                        if (!cpu) {
                            addQnn(mapOf("backend_path" to File(nativeLibraryDir, "libQnnHtp.so").absolutePath, "htp_performance_mode" to "burst"))
                            if (!cpuFallback) addConfigEntry("session.disable_cpu_ep_fallback", "1")
                        }
                    }
                    val main = env.createSession(f.absolutePath, opts)
                    val qf = File(f.parentFile, f.nameWithoutExtension + "_q.onnx")
                    val adapter = if (qf.isFile) runCatching { env.createSession(qf.absolutePath, OrtSession.SessionOptions()) }
                        .getOrElse { main.close(); throw it } else null
                    graphs[f.nameWithoutExtension] = Graph(f.nameWithoutExtension, main, adapter, m)
                }
            } catch (t: Throwable) {
                graphs.values.forEach { it.close() }; throw t
            }
            Session(m, env, graphs, System.currentTimeMillis() - t0).also { SLog.d(TAG, "${m.id} loaded ${graphs.keys} in ${it.loadMs} ms") }
        }
    }

    override suspend fun run(session: ModelSession, inputs: Map<String, Any>): Result<Map<String, Any>> = withContext(Dispatchers.IO) {
        runCatching {
            val s = session as? Session ?: throw IllegalArgumentException("not an ORT session")
            s.run(inputs)
        }
    }

    internal class Graph(val name: String, val main: OrtSession, val adapter: OrtSession?, m: ModelManifest) {
        /** Inputs the adapter quantizes: original name → adapter input name (`<name>_f32`). */
        val adapted: Map<String, String> = adapter?.inputNames?.filter { it.endsWith("_f32") }?.associateBy { it.removeSuffix("_f32") } ?: emptyMap()
        val inputs: List<TensorDesc> = main.inputInfo.entries.map { (n, v) -> desc(n, v.info as TensorInfo, m, true) }
        val outputs: List<TensorDesc> = main.outputInfo.entries.map { (n, v) -> desc(n, v.info as TensorInfo, m, false) }

        private fun desc(n: String, ti: TensorInfo, m: ModelManifest, input: Boolean): TensorDesc {
            val spec = specOf(m, name, n, input)
            val dtype = when (ti.type) {
                OnnxJavaType.FLOAT -> "float32"; OnnxJavaType.FLOAT16 -> "float16"; OnnxJavaType.INT32 -> "int32"
                OnnxJavaType.INT64 -> "int64"; OnnxJavaType.UINT8 -> "uint8"; OnnxJavaType.INT8 -> "int8"; OnnxJavaType.INT16 -> "int16"
                OnnxJavaType.DOUBLE -> "float64"; OnnxJavaType.BOOL -> "bool"
                else -> spec?.dtype ?: "uint16"   // UNKNOWN: AI Hub w8a16 IO is uint16
            }
            return TensorDesc(n, dtype, ti.shape.toList(), name, spec?.scale, spec?.zeroPoint)
        }

        fun close() { runCatching { main.close() }; runCatching { adapter?.close() } }
    }

    class Session internal constructor(
        override val manifest: ModelManifest,
        private val env: OrtEnvironment,
        private val g: Map<String, Graph>,
        override val loadMs: Long,
    ) : TensorSession {
        override val graphs: List<String> = g.keys.toList()
        override fun inputs(graph: String?) = g.getValue(graph ?: graphs.first()).inputs
        override fun outputs(graph: String?) = g.getValue(graph ?: graphs.first()).outputs

        @Synchronized
        internal fun run(inputs: Map<String, Any>): Map<String, Any> {
            val graph = g.getValue(TensorSession.graphOf(inputs, graphs))
            val owned = ArrayList<AutoCloseable>()
            try {
                val feeds = LinkedHashMap<String, OnnxTensor>()
                val adapterFeeds = LinkedHashMap<String, OnnxTensor>()
                graph.inputs.forEachIndexed { i, d ->
                    val v = TensorSession.pick(inputs, d, i) ?: throw IllegalArgumentException("${manifest.id}/${graph.name}: missing input '${d.name}'")
                    val shape = d.shape.map { if (it < 0) 1L else it }.toLongArray()
                    val viaAdapter = graph.adapted[d.name]
                    if (viaAdapter != null) {
                        // real values → float32 → adapter QuantizeLinear → uint16
                        adapterFeeds[viaAdapter] = OnnxTensor.createTensor(env, TensorIo.direct(TensorIo.toBytes(v, "float32")), shape, OnnxJavaType.FLOAT).also { owned += it }
                    } else {
                        val type = javaType(d.dtype) ?: throw IllegalArgumentException(
                            "${manifest.id}/${graph.name}: input '${d.name}' is ${d.dtype}, which ORT Java cannot create; needs ${graph.name}_q.onnx")
                        val bytes = TensorIo.toBytes(v, d.dtype)
                        require(bytes.size.toLong() == d.numBytes) { "${manifest.id}: input '${d.name}' has ${bytes.size} bytes, expected ${d.numBytes}" }
                        feeds[d.name] = OnnxTensor.createTensor(env, TensorIo.direct(bytes), shape, type).also { owned += it }
                    }
                }
                if (adapterFeeds.isNotEmpty()) {
                    val q = graph.adapter!!.run(adapterFeeds).also { owned += it }
                    q.forEach { (n, value) -> feeds[n] = value as OnnxTensor }
                }
                val result = LinkedHashMap<String, Any>()
                graph.main.run(feeds).use { r ->
                    r.forEach { (n, value) ->
                        val b = (value as OnnxTensor).byteBuffer
                        result[n] = ByteArray(b.remaining()).also { b.get(it) }
                    }
                }
                return result
            } finally {
                owned.asReversed().forEach { runCatching { it.close() } }
            }
        }

        override fun close() { g.values.forEach { it.close() } }
    }

    companion object {
        const val ID = "ort_qnn"
        private const val TAG = "OrtQnn"

        fun create(context: Context, cpuFallback: Boolean = false) = OrtQnnRuntime(context.applicationInfo.nativeLibraryDir, cpuFallback)

        fun javaType(dtype: String): OnnxJavaType? = when (dtype) {
            "float32" -> OnnxJavaType.FLOAT; "float16" -> OnnxJavaType.FLOAT16; "int32" -> OnnxJavaType.INT32
            "int64" -> OnnxJavaType.INT64; "uint8" -> OnnxJavaType.UINT8; "int8" -> OnnxJavaType.INT8; "int16" -> OnnxJavaType.INT16
            "float64" -> OnnxJavaType.DOUBLE; "bool" -> OnnxJavaType.BOOL
            else -> null
        }

        /** The manifest's inputSpec/outputSpec entry for a tensor (keys `<name>` or `<graph>.<name>`). */
        internal fun specOf(m: ModelManifest, graph: String, name: String, input: Boolean): TensorDesc? =
            ManifestSpecs.parse(if (input) m.inputSpec else m.outputSpec).firstOrNull { it.name == name && (it.graph.isEmpty() || it.graph == graph) }
    }
}
