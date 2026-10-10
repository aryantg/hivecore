package com.hivecore.app.approval

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.hivecore.app.HiveCoreGraph
import com.hivecore.core.PendingApproval
import com.hivecore.core.SLog
import com.hivecore.trust.ApprovalKeys
import com.hivecore.trust.ApprovalStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.security.Signature
import com.hivecore.ui.design.ActionRow
import com.hivecore.ui.design.CardTone
import com.hivecore.ui.design.HiveAction
import com.hivecore.ui.design.HiveCard
import com.hivecore.ui.design.HiveCoreTheme
import com.hivecore.ui.design.HiveIcons
import com.hivecore.ui.design.HiveSpacing
import com.hivecore.ui.design.HiveTextStyles
import com.hivecore.ui.design.InlineNotice
import com.hivecore.ui.design.Stat
import com.hivecore.ui.design.StatTile
import com.hivecore.ui.design.StatusPill
import com.hivecore.ui.design.StepItem
import com.hivecore.ui.design.StepList
import com.hivecore.ui.design.Tone
import com.hivecore.ui.design.ButtonKind
import com.hivecore.ui.design.HiveButton
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.style.TextAlign

/**
 * Spec 7.6 / 17: the owner's one screen and one fingerprint for a plan. A [FragmentActivity] because
 * `BiometricPrompt` needs one; the manifest gives it `showWhenLocked` / `turnScreenOn` so the notification's
 * full-screen intent lands over the lock screen.
 *
 * Shows the pending item named by the `jobId` extra (or the oldest pending one), the requester, the numbered
 * figures and a countdown, and:
 *  - Approve: `BiometricPrompt` with a `CryptoObject(Signature)` over the per-use approval key; on success the
 *    signature over the store's canonical bytes resolves `Approved(sig, "approval")`, which the owner handler
 *    puts into `PLAN_CONSENT_RES`.
 *  - Decline (button, the prompt's negative button, or the user cancelling the prompt): resolves `Declined`.
 *  - `KeyPermanentlyInvalidatedException` (fingerprint enrolment changed): the key is re-created, this request is
 *    declined, HELLO is re-broadcast with the new key, and the owner is told to ask again.
 */
class ApprovalActivity : FragmentActivity() {
    private var jobId by mutableStateOf<String?>(null)
    private var message by mutableStateOf<String?>(null)
    private var busy by mutableStateOf(false)
    private val graph by lazy { HiveCoreGraph.get(this) }
    private val store: ApprovalStore? get() = graph.ports.approval
    private val keys: ApprovalKeys? get() = graph.ports.approvalKeys
    private val controller by lazy { store?.let { PlanApprovalController(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        jobId = intent?.getStringExtra(EXTRA_JOB_ID)
        SLog.d(TAG, "onCreate jobId=${jobId?.take(8)} store=${store != null}")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            ApprovalTheme {
                val s = store
                val flow = remember(s) { s?.pending ?: MutableStateFlow(emptyList()) }
                val pending by flow.collectAsState()
                val item = remember(pending, jobId) { controller?.current(jobId) }
                ApprovalScreen(
                    item = item,
                    view = item?.let { controller?.view(it) },
                    message = message,
                    busy = busy,
                    canSign = s != null && keys?.exists() == true,
                    canEnroll = keys?.canEnroll() == true,
                    onApprove = { approve(it) },
                    onDecline = { decline(it, "button") },
                    onEnroll = { startEnroll() },
                    onClose = { finish() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        jobId = intent.getStringExtra(EXTRA_JOB_ID)
        message = null
    }

    private fun approve(item: PendingApproval) {
        val c = controller ?: run { message = "No approval store is wired (the mesh phase is off)"; return }
        val k = keys ?: run { message = "No approval key"; return }
        if (busy) return
        val signature = try {
            Signature.getInstance("SHA256withECDSA").apply { initSign(k.privateKey()) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            onKeyInvalidated(c, k, item, e); return
        } catch (e: Exception) {
            SLog.e(TAG, "approval key unusable", e)
            message = "Approval key unavailable: ${e.message ?: e.javaClass.simpleName}"
            return
        }
        busy = true
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                busy = false
                val sig = result.cryptoObject?.signature
                if (sig == null) {
                    SLog.e(TAG, "biometric success without a CryptoObject signature")
                    message = "Could not sign the approval; try again"
                    return
                }
                val problem = c.approve(item) { bytes -> sig.run { update(bytes); sign() } }
                if (problem == null) finish() else message = problem
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                busy = false
                when (errorCode) {
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON, BiometricPrompt.ERROR_USER_CANCELED -> decline(item, "prompt error $errorCode")
                    // Lockout, hardware busy, timeout: the request stays pending so the owner can retry or decline.
                    else -> message = "Fingerprint check did not complete ($errString). Try again or decline."
                }
            }

            override fun onAuthenticationFailed() { /* not recognised; the prompt stays open */ }
        }
        val v = c.view(item)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Approve ${v.requester}'s request")
            .setSubtitle(v.header)
            .setDescription(v.figures.mapIndexed { i, f -> "${i + 1}. $f" }.joinToString("\n"))
            .setNegativeButtonText("Decline")
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setConfirmationRequired(false)
            .build()
        BiometricPrompt(this, mainExecutor, callback).authenticate(info, BiometricPrompt.CryptoObject(signature))
    }

    private fun decline(item: PendingApproval, why: String) {
        controller?.decline(item, why)
        finish()
    }

    private fun onKeyInvalidated(c: PlanApprovalController, k: ApprovalKeys, item: PendingApproval, e: KeyPermanentlyInvalidatedException) {
        SLog.w(TAG, "approval key permanently invalidated: $e")
        val pub = k.recreate()
        c.decline(item, "key invalidated")
        message = if (pub != null)
            "Your fingerprint enrolment changed, so the approval key was re-created and announced to the other phones. This request was declined; ask again."
        else
            "Your fingerprint enrolment changed and no fingerprint is enrolled now. Enroll one, then ask again."
        graph.ports.rebroadcastHello?.let { re -> graph.scope.launch { runCatching { re() }.onFailure { SLog.w(TAG, "HELLO re-broadcast failed: ${it.message}") } } }
    }

    private fun startEnroll() {
        try { startActivity(keys?.enrollIntent() ?: return) } catch (e: Exception) {
            SLog.e(TAG, "enroll intent failed", e); message = "Open Settings > Security to enroll a fingerprint"
        }
    }

    companion object {
        const val EXTRA_JOB_ID = "jobId"
        private const val TAG = "ApprovalActivity"

        fun intent(context: Context, jobId: String?): Intent = Intent(context, ApprovalActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { if (jobId != null) putExtra(EXTRA_JOB_ID, jobId) }
    }
}


/** The approval screen sits on the app's one theme, over a plain background (it shows over the lock screen). */
@Composable
private fun ApprovalTheme(content: @Composable () -> Unit) {
    HiveCoreTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
    }
}

@Composable
internal fun ApprovalScreen(
    item: PendingApproval?,
    view: ApprovalView?,
    message: String?,
    busy: Boolean,
    canSign: Boolean,
    canEnroll: Boolean,
    onApprove: (PendingApproval) -> Unit,
    onDecline: (PendingApproval) -> Unit,
    onEnroll: () -> Unit,
    onClose: () -> Unit,
) {
    Scaffold { inner ->
        Column(
            modifier = Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(HiveSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(HiveSpacing.md, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(HiveIcons.lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(HiveSpacing.iconXl))
            if (item == null || view == null) {
                Text("Nothing pending", style = MaterialTheme.typography.headlineSmall)
                Text("The approval request already ended or was answered elsewhere.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                message?.let { InlineNotice(it) }
                HiveButton(HiveAction("Close", HiveIcons.close, onClick = onClose), ButtonKind.Outlined)
                return@Column
            }
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(item.jobId) { while (true) { now = System.currentTimeMillis(); delay(1_000L) } }
            val remaining = ((item.deadlineAt - now) / 1000L).coerceAtLeast(0L)

            Text("Approve ${view.requester}'s request", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(view.header, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            HiveCard(tone = CardTone.Tonal) {
                StepList(view.figures.map { StepItem(it) })
                view.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            if (view.isPlan) {
                StatusPill("One fingerprint approves all ${view.figures.size} figure${if (view.figures.size == 1) "" else "s"}; nothing else", Tone.Accent, icon = HiveIcons.check)
                Text("plan ${view.planHash!!.take(12)}", style = HiveTextStyles.monoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Only aggregates leave this phone; rows never do.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            StatTile(Stat(if (remaining > 0) "$remaining s" else "Expired", "left to answer", if (remaining <= 10) Tone.Danger else Tone.Neutral, HiveIcons.history), large = true)
            if (!canSign) {
                InlineNotice(if (canEnroll) "The approval key is missing; it is created when the app starts with a fingerprint enrolled."
                    else "No fingerprint is enrolled on this phone, so this request cannot be approved.", Tone.Danger, icon = HiveIcons.warning)
                if (!canEnroll) HiveButton(HiveAction("Enroll a fingerprint", HiveIcons.lock, onClick = onEnroll), ButtonKind.Text)
            }
            message?.let { InlineNotice(it, Tone.Danger, icon = HiveIcons.warning) }
            ActionRow(HiveAction("Approve", HiveIcons.check, enabled = canSign && !busy && remaining > 0) { onApprove(item) },
                listOf(HiveAction("Decline", HiveIcons.close, enabled = !busy, destructive = true) { onDecline(item) }))
        }
    }
}

/**
 * The in-app fallback when the notification does not surface (spec 17): a card under the top bar naming the oldest
 * pending request with a button that opens [ApprovalActivity]. Nothing is drawn when nothing is pending.
 */
@Composable
fun ApprovalBanner(store: ApprovalStore, context: Context) {
    val pending by store.pending.collectAsState()
    val first = pending.firstOrNull() ?: return
    HiveCard(Modifier.padding(horizontal = HiveSpacing.screenH, vertical = HiveSpacing.xs), tone = CardTone.Warn,
        onClick = { context.startActivity(ApprovalActivity.intent(context, first.jobId)) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(HiveIcons.lock, contentDescription = null, modifier = Modifier.size(HiveSpacing.icon))
            Spacer(Modifier.width(HiveSpacing.sm))
            Column(Modifier.weight(1f)) {
                Text("${first.requester} asks for figures from this phone", style = MaterialTheme.typography.titleSmall)
                Text(first.description.lineSequence().firstOrNull().orEmpty(), style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            if (pending.size > 1) StatusPill("+${pending.size - 1}", Tone.Warn)
        }
        Spacer(Modifier.height(HiveSpacing.sm))
        ActionRow(HiveAction("Review", HiveIcons.check) { context.startActivity(ApprovalActivity.intent(context, first.jobId)) })
    }
}
