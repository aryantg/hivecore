package com.hivecore.runtime.models

import android.content.Context
import com.hivecore.core.ModelManifest
import com.hivecore.core.ModelRuntime
import com.hivecore.core.ModelSession
import com.hivecore.core.SLog
import com.qualcomm.qti.QnnDelegate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * AI Hub `.tflite` assets on the HTP: LiteRT 1.4.2 `Interpreter` + Qualcomm QNN delegate 2.50.0 (swarm plan §4A.2,
 * rules learned on the iQOO 15):
 *  - `setLibraryPath` takes the FILE `<nativeLibraryDir>/libQnnHtp.so` (dlopen of the folder fails);
 *    `setSkelLibraryDir(nativeLibraryDir)`; compiled graphs are cached in `filesDir/qnn_cache`, keyed by a model token
 *    (the manifest sha256), so only the first load compiles (D-FINE: 2 min, then milliseconds).
 *  - `System.loadLibrary("qnn_delegate_jni")` before any static QnnDelegate call.
 *  - FP16 (HTP_PRECISION_FP16) for float models: manifest `preprocess.htpPrecision = "fp16"` or backend `npu_fp16`.
 *  - backend `cpu` runs on XNNPACK (never compared with the NPU, §4A.3).
 * One interpreter per `.tflite` file in the manifest; graphs are named by file stem.
 */
class LiteRtQnnRuntime(
    private val nativeLibraryDir: String,
    private val cacheDir: File,
) : ModelRuntime {
    override val id: String = ID

    override fun supports(m: ModelManifest): Boolean = m.runtime == ID

    override suspend fun load(m: ModelManifest, dir: File): Result<ModelSession> = withContext(Dispatchers.IO) {
        runCatching {
            val t0 = System.currentTimeMillis()
            val files = m.files.filter { it.endsWith(".tflite", ignoreCase = true) }.map { File(dir, it) }
            require(files.isNotEmpty()) { "${m.id}: no .tflite file in ${m.files}" }
            val cpu = m.backend.equals("cpu", ignoreCase = true)
            val fp16 = m.backend.equals("npu_fp16", ignoreCase = true) ||
                m.preprocess.str("htpPrecision").equals("fp16", ignoreCase = true)
            val graphs = LinkedHashMap<String, Graph>()
            try {
                for (f in files) graphs[f.nameWithoutExtension] = open(m, f, cpu, fp16)
            } catch (t: Throwable) {
                graphs.values.forEach { it.close() }; throw t
            }
            val s = Session(m, graphs, System.currentTimeMillis() - t0)
            SLog.d(TAG, "${m.id} loaded (${if (cpu) "cpu" else if (fp16) "htp fp16" else "htp"}) in ${s.loadMs} ms")
            s
        }
    }

    private fun open(m: ModelManifest, f: File, cpu: Boolean, fp16: Boolean): Graph {
        val buf = RandomAccessFile(f, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }
        if (cpu) return Graph(f.nameWithoutExtension, Interpreter(buf, Interpreter.Options().setNumThreads(4).setUseXNNPACK(true)), null)
        ensureJni()
        val opts = QnnDelegate.Options().apply {
            setBackendType(QnnDelegate.Options.BackendType.HTP_BACKEND)
            setSkelLibraryDir(nativeLibraryDir)
            setLibraryPath(File(nativeLibraryDir, "libQnnHtp.so").absolutePath)   // the file, not the folder
            setHtpPerformanceMode(QnnDelegate.Options.HtpPerformanceMode.HTP_PERFORMANCE_BURST)
            if (fp16) setHtpPrecision(QnnDelegate.Options.HtpPrecision.HTP_PRECISION_FP16)
            setCacheDir(this@LiteRtQnnRuntime.cacheDir.apply { mkdirs() }.absolutePath)
            setModelToken((m.sha256.take(12) + "_" + f.nameWithoutExtension).take(48))
            setLogLevel(QnnDelegate.Options.LogLevel.LOG_LEVEL_WARN)
        }
        val delegate = QnnDelegate(opts)
        val interp = try {
            Interpreter(buf, Interpreter.Options().addDelegate(delegate).setNumThreads(1).setUseXNNPACK(false))
        } catch (t: Throwable) { delegate.close(); throw t }
        return Graph(f.nameWithoutExtension, interp, delegate)
    }

    override suspend fun run(session: ModelSession, inputs: Map<String, Any>): Result<Map<String, Any>> = withContext(Dispatchers.IO) {
        runCatching {
            val s = session as? Session ?: throw IllegalArgumentException("not a LiteRT session")
            s.run(inputs)
        }
    }

    internal class Graph(val name: String, val interp: Interpreter, val delegate: QnnDelegate?) {
        val inputs: List<TensorDesc> = (0 until interp.inputTensorCount).map { desc(interp.getInputTensor(it)) }
        val outputs: List<TensorDesc> = (0 until interp.outputTensorCount).map { desc(interp.getOutputTensor(it)) }
        private fun desc(t: org.tensorflow.lite.Tensor): TensorDesc {
            val q = t.quantizationParams()
            return TensorDesc(t.name(), dtypeOf(t.dataType()), t.shape().map { it.toLong() }, name,
                scale = q.scale.toDouble().takeIf { it != 0.0 }, zeroPoint = q.zeroPoint.takeIf { q.scale != 0f })
        }
        fun close() { runCatching { interp.close() }; runCatching { delegate?.close() } }
    }

    class Session internal constructor(
        override val manifest: ModelManifest,
        private val g: Map<String, Graph>,
        override val loadMs: Long,
    ) : TensorSession {
        override val graphs: List<String> = g.keys.toList()
        override fun inputs(graph: String?) = g.getValue(graph ?: graphs.first()).inputs
        override fun outputs(graph: String?) = g.getValue(graph ?: graphs.first()).outputs

        @Synchronized
        internal fun run(inputs: Map<String, Any>): Map<String, Any> {
            val graph = g.getValue(TensorSession.graphOf(inputs, graphs))
            val ins = Array<Any>(graph.inputs.size) { i ->
                val d = graph.inputs[i]
                val v = TensorSession.pick(inputs, d, i) ?: throw IllegalArgumentException("${manifest.id}/${graph.name}: missing input '${d.name}'")
                val bytes = TensorIo.toBytes(v, d.dtype)
                require(bytes.size.toLong() == d.numBytes) { "${manifest.id}: input '${d.name}' has ${bytes.size} bytes, expected ${d.numBytes}" }
                TensorIo.direct(bytes)
            }
            val outBufs = graph.outputs.map { ByteBuffer.allocateDirect(it.numBytes.toInt()).order(ByteOrder.nativeOrder()) }
            val outs = HashMap<Int, Any>().apply { outBufs.forEachIndexed { k, b -> put(k, b) } }
            graph.interp.runForMultipleInputsOutputs(ins, outs)
            val result = LinkedHashMap<String, Any>()
            outBufs.forEachIndexed { k, b ->
                b.rewind()
                val key = graph.outputs[k].name.ifBlank { "out$k" }.let { if (it in result) "$it#$k" else it }
                result[key] = ByteArray(b.remaining()).also { b.get(it) }
            }
            return result
        }

        override fun close() { g.values.forEach { it.close() } }
    }

    companion object {
        const val ID = "litert_qnn"
        private const val TAG = "LiteRtQnn"
        @Volatile private var jniLoaded = false

        @Synchronized fun ensureJni() {
            if (jniLoaded) return
            System.loadLibrary("qnn_delegate_jni")   // static getVersion()/checkCapability() do not load it themselves
            jniLoaded = true
        }

        fun create(context: Context) = LiteRtQnnRuntime(context.applicationInfo.nativeLibraryDir, File(context.filesDir, "qnn_cache"))

        /** "a.b.c" delegate API version (loads the JNI library first). */
        fun delegateVersion(): String = runCatching { ensureJni(); QnnDelegate.getVersion().joinToString(".") }.getOrDefault("?")

        fun dtypeOf(t: DataType): String = when (t) {
            DataType.FLOAT32 -> "float32"; DataType.INT32 -> "int32"; DataType.UINT8 -> "uint8"; DataType.INT64 -> "int64"
            DataType.INT16 -> "int16"; DataType.INT8 -> "int8"; DataType.BOOL -> "bool"; else -> t.name.lowercase()
        }
    }
}
