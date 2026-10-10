package com.hivecore.core.fakes

import com.hivecore.core.AskOutcome
import com.hivecore.core.AskProgress
import com.hivecore.core.Asker
import com.hivecore.core.PendingApproval
import com.hivecore.core.Timing
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

/** Canned [AskOutcome] per exact question; anything else is a NoNode with the configured holdings. */
class FakeAsker(var delayMs: Long = 0) : Asker {
    val answers = HashMap<String, AskOutcome>()
    var holdings: List<Pair<String, List<String>>> = listOf("HR" to listOf("employees", "onboarding_checklist.docx"))
    val asked = mutableListOf<Pair<String, Boolean>>()
    val pending = MutableStateFlow<List<PendingApproval>>(emptyList())
    override val pendingApprovals: StateFlow<List<PendingApproval>> = pending

    override fun ask(question: String, viaVoice: Boolean): Flow<AskProgress> = flow {
        asked.add(question to viaVoice)
        emit(AskProgress.Status("Asking (fake)…"))
        if (delayMs > 0) delay(delayMs)
        emit(AskProgress.Done(answers[question] ?: AskOutcome.NoNode(holdings, Timing(totalMs = delayMs))))
    }
}
