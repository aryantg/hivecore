package com.hivecore.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.hivecore.app.approval.ApprovalBanner
import com.hivecore.core.SLog
import com.hivecore.ui.HiveCoreShell
import com.hivecore.ui.ScreenRegistry
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = HiveCoreGraph.get(this)
        setContent {
            androidx.compose.foundation.layout.Box {
                HiveCoreShell(registry = ScreenRegistry(Features.screens(graph)))
                // spec 17: the pending-approval card is the fallback when the notification does not surface
                graph.ports.approval?.let { androidx.compose.foundation.layout.Column { ApprovalBanner(it, this@MainActivity) } }
            }
            graph.consent?.let { ConsentSheetHost(it) }
        }
        handleInvite(intent, graph)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleInvite(intent, HiveCoreGraph.get(this))
    }

    /** `swarm://join?...` (spec 6.2): the invite link lands on the Swarm screen's join path. */
    private fun handleInvite(intent: Intent?, graph: HiveCoreGraph) {
        val link = intent?.dataString?.takeIf { it.startsWith("swarm://join") } ?: return
        SLog.d("Main", "invite link received")
        graph.scope.launch { graph.swarmPort.join(link).onFailure { SLog.w("Main", "join: ${it.message}") } }
    }
}
