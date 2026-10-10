package com.hivecore.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hivecore.cloud.ConsentDecision
import com.hivecore.cloud.ConsentRequest
import com.hivecore.cloud.ConsentSheet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Spec 15.2 step 6: the consent sheet. The gate calls [ask] from the orchestrator's coroutine; the request is
 * published on [request], the Compose host ([ConsentSheetHost]) shows it, and the user's tap completes the call.
 * One request at a time; the gate waits its turn. A sheet dismissed without a choice keeps the step local.
 */
class CloudConsent : ConsentSheet {
    private val current = MutableStateFlow<ConsentRequest?>(null)
    val request: StateFlow<ConsentRequest?> = current
    private var waiter: CompletableDeferred<ConsentDecision>? = null
    private val turn = Mutex()

    override suspend fun ask(req: ConsentRequest): ConsentDecision = turn.withLock {
        val w = CompletableDeferred<ConsentDecision>()
        synchronized(this) { waiter = w; current.value = req }
        try { w.await() } finally { synchronized(this) { waiter = null; current.value = null } }
    }

    /** The user's answer for the request on screen. */
    fun answer(d: ConsentDecision) { synchronized(this) { waiter?.complete(d) } }
}

/** The Material 3 bottom sheet over [consent]: Allow once / Allow this month / Keep local (spec 17). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsentSheetHost(consent: CloudConsent) {
    val req by consent.request.collectAsState()
    val r = req ?: return
    ModalBottomSheet(onDismissRequest = { consent.answer(ConsentDecision.KEEP_LOCAL) }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("Use a cloud model for this step?", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(r.sentence, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Text("Step ${r.stepId} · ${r.trigger.replace('_', ' ')} · about ${com.hivecore.cloud.SpendCaps.inr(r.estimateInr)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { consent.answer(ConsentDecision.ALLOW_ONCE) }) { Text("Allow once") }
                OutlinedButton(onClick = { consent.answer(ConsentDecision.ALLOW_MONTH) }) { Text("Allow this month") }
            }
            TextButton(onClick = { consent.answer(ConsentDecision.KEEP_LOCAL) }) { Text("Keep local") }
            Spacer(Modifier.height(24.dp))
        }
    }
}
