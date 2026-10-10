package com.hivecore.app.demo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.hivecore.core.SLog
import com.hivecore.ui.design.ActionRow
import com.hivecore.ui.design.HiveAction
import com.hivecore.ui.design.HiveCoreTheme
import com.hivecore.ui.design.HiveIcons
import com.hivecore.ui.design.HiveShape
import com.hivecore.ui.design.HiveSpacing
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Scan QR (spec section 17 "pair in the same room"): the camera finds the other phone's `swarm://join?...` code and
 * returns it as [EXTRA_LINK]. "Paste instead" returns [EXTRA_PASTE] so the Swarm screen opens its paste card.
 */
class ScanActivity : ComponentActivity() {
    private var status by mutableStateOf("Point the camera at the QR code on the other phone")
    private val done = AtomicBoolean(false)
    private val analysis = Executors.newSingleThreadExecutor()
    private val scanner by lazy { BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()) }
    private var previewView: PreviewView? = null

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) previewView?.let { startCamera(it) } else status = "Camera permission is off. Paste the invite link instead."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            HiveCoreTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().systemBarsPadding().padding(HiveSpacing.xl), verticalArrangement = Arrangement.spacedBy(HiveSpacing.lg, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Scan to pair", style = MaterialTheme.typography.headlineSmall)
                        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(HiveShape.hero)) {
                            AndroidView(factory = { ctx -> PreviewView(ctx).also { v -> previewView = v; ensureCamera(v) } }, modifier = Modifier.fillMaxSize())
                        }
                        Text(status, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                        ActionRow(secondaries = listOf(
                            HiveAction("Paste instead", HiveIcons.link) { finishWith(null, paste = true) },
                            HiveAction("Cancel", HiveIcons.close) { finishWith(null, paste = false) },
                        ))
                    }
                }
            }
        }
    }

    private fun ensureCamera(v: PreviewView) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera(v)
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun startCamera(v: PreviewView) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            runCatching {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = v.surfaceProvider }
                val analyzer = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analyzer.setAnalyzer(analysis) { proxy ->
                    val media = proxy.image
                    if (media == null || done.get()) { proxy.close(); return@setAnalyzer }
                    scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                        .addOnSuccessListener { codes ->
                            val link = codes.mapNotNull { it.rawValue }.firstOrNull { it.startsWith("swarm://join?") }
                            if (link != null && done.compareAndSet(false, true)) runOnUiThread { finishWith(link, paste = false) }
                            else if (codes.isNotEmpty()) status = "That QR code is not a HiveCore invite"
                        }
                        .addOnCompleteListener { proxy.close() }
                }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analyzer)
            }.onFailure { e -> SLog.e(TAG, "camera failed", e); status = "The camera could not start. Paste the invite link instead." }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun finishWith(link: String?, paste: Boolean) {
        setResult(if (link != null) RESULT_OK else RESULT_CANCELED, Intent().putExtra(EXTRA_LINK, link).putExtra(EXTRA_PASTE, paste))
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        analysis.shutdown()
        runCatching { scanner.close() }
    }

    companion object {
        const val EXTRA_LINK = "link"
        const val EXTRA_PASTE = "paste"
        private const val TAG = "Scan"
    }
}
