package com.hivecore.ui.ports

import kotlinx.serialization.Serializable

/**
 * Spec section 17 "Refusals in plain words": every refusal and every partial result is one sentence a non-developer
 * understands ("HR's phone left before its private rows were processed"), plus the plan hash and the step for the
 * "details" expander. Ask and Jobs render it with the shared `RefusalCard`.
 */
@Serializable
data class Refusal(
    val sentence: String,
    val planHash: String? = null,
    val step: String? = null,
    /** optional technical detail (error class, node) shown under the hash in the expander */
    val detail: String? = null,
) {
    /** The expander lines: "plan 9f2c41ab77e0", "step EXTRACT (t3)", detail. Empty when nothing is known. */
    val detailLines: List<String> get() = listOfNotNull(
        planHash?.takeIf { it.isNotBlank() }?.let { "plan ${it.take(16)}" },
        step?.takeIf { it.isNotBlank() }?.let { "step $it" },
        detail?.takeIf { it.isNotBlank() },
    )
}
