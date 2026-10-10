package com.hivecore.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import com.hivecore.ui.design.ActionRow
import com.hivecore.ui.design.HiveAction
import com.hivecore.ui.design.HiveIcons
import com.hivecore.ui.design.HiveShape
import com.hivecore.ui.design.HiveSpacing
import com.hivecore.ui.design.HiveTheme
import com.hivecore.ui.design.StatusPill
import com.hivecore.ui.design.Tone
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
    ModalBottomSheet(onDismissRequest = { consent.answer(ConsentDecision.KEEP_LOCAL) }, shape = HiveShape.sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = HiveSpacing.xl, vertical = HiveSpacing.sm), verticalArrangement = Arrangement.spacedBy(HiveSpacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(HiveIcons.cloud, contentDescription = null, tint = HiveTheme.colors.cloud, modifier = Modifier.size(HiveSpacing.iconLg))
                Spacer(Modifier.width(HiveSpacing.sm))
                Text("Use a cloud model for this step?", style = MaterialTheme.typography.titleLarge)
            }
            Text(r.sentence, style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(HiveSpacing.xs)) {
                StatusPill("step ${r.stepId}", Tone.Neutral)
                StatusPill(r.trigger.replace('_', ' '), Tone.Cloud)
                StatusPill("about ${com.hivecore.cloud.SpendCaps.inr(r.estimateInr)}", Tone.Accent)
            }
            Text("Nothing private is included: only released rows and the prompt leave this phone.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            ActionRow(HiveAction("Allow once", HiveIcons.check) { consent.answer(ConsentDecision.ALLOW_ONCE) },
                listOf(HiveAction("Allow this month", HiveIcons.history) { consent.answer(ConsentDecision.ALLOW_MONTH) },
                    HiveAction("Keep local", HiveIcons.phone) { consent.answer(ConsentDecision.KEEP_LOCAL) }))
            Spacer(Modifier.height(HiveSpacing.xl))
        }
    }
}
