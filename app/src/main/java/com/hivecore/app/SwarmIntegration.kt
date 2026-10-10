package com.hivecore.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.hivecore.app.demo.ScanActivity
import com.hivecore.core.SLog
import com.hivecore.engine.device.WorkerService

/**
 * The Android halves of the Swarm screen and the result sheet: the camera scanner, the clipboard, the share sheet and
 * the battery exemption request. Bound at the top of `MainActivity.onCreate` (the scanner's launcher must be
 * registered before the activity starts).
 */
object SwarmIntegration {
    fun bind(activity: ComponentActivity, graph: HiveCoreGraph) {
        val swarm = graph.swarm
        val scan = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val link = r.data?.getStringExtra(ScanActivity.EXTRA_LINK)
            when {
                link != null -> swarm.join(link)
                r.data?.getBooleanExtra(ScanActivity.EXTRA_PASTE, false) == true -> swarm.pasteLink()
            }
        }
        swarm.openScanner = { scan.launch(Intent(activity, ScanActivity::class.java)) }
        val copy = { label: String, text: String ->
            activity.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text)) ?: SLog.w(TAG, "no clipboard")
        }
        swarm.copyLink = { copy("HiveCore invite", it) }
        swarm.shareLink = { link ->
            runCatching {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "Join my HiveCore swarm").putExtra(Intent.EXTRA_TEXT, link)
                activity.startActivity(Intent.createChooser(send, "Send invite link"))
            }.onFailure { SLog.e(TAG, "share failed", it) }
        }
        swarm.requestBatteryExemption = { if (!WorkerService.isBatteryExempt(activity)) WorkerService.requestBatteryExemption(activity) }
        graph.jobs.copyText = { copy("HiveCore result", it) }
    }

    private const val TAG = "SwarmIntegration"
}
