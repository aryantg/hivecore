package com.hivecore.ui.demo

import com.hivecore.engine.deck.DeckFormat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Demo mode's scripted results for the Kavira table recipes: one item per stockist, brand, branch or language, each
 * shaped like a real `LLM_MAP` output (`{"item", "output": {...}}`) and worded from the seeded figures in
 * [KaviraFigures], so the Jobs screen's result sheet reads as a live run would. Deterministic.
 */
object KaviraDemoJobs {
    /** How many items a recipe fans out to in the scripted swarm (null = not a Kavira recipe). */
    fun items(recipeId: String?): Int? = when (recipeId) {
        "stockist_letters" -> KaviraFigures.STOCKISTS.size
        "brand_reviews" -> KaviraFigures.BRANDS.size
        "branch_review_packs" -> KaviraFigures.BRANCHES.size
        "launch_messages" -> KaviraFigures.BRANCHES.size
        "launch_kit" -> 4
        else -> null
    }

    fun summary(recipeId: String?, phones: Int): String? {
        val where = if (phones == 1) "on 1 phone" else "across $phones phones"
        return when (recipeId) {
            "stockist_letters" -> "${KaviraFigures.STOCKISTS.size} stockists → ${KaviraFigures.STOCKISTS.size} letters $where; ${KaviraFigures.STOCKISTS.count { it.paymentDays > 50 }} carry a payment reminder"
            "brand_reviews" -> "${KaviraFigures.BRANDS.size} brands → ${KaviraFigures.BRANDS.size} one-page reviews $where"
            "branch_review_packs" -> "3 branches → 3 Monday review packs $where"
            "launch_messages" -> "3 branches → 3 WhatsApp messages (Kannada, Tamil, Malayalam) $where"
            "launch_kit" -> "1 brief → 4 poster candidates and the detail-aid copy $where"
            else -> null
        }
    }

    /** The merged `{stages: {<stage>: {op, items}}}` a finished run would carry; null for other recipes. */
    fun result(recipeId: String?): JsonObject? {
        val (stage, items) = when (recipeId) {
            "stockist_letters" -> "letter" to KaviraFigures.STOCKISTS.map { letter(it) }
            "brand_reviews" -> "review" to KaviraFigures.BRANDS.map { review(it) }
            "branch_review_packs" -> "pack" to KaviraFigures.BRANCHES.map { pack(it) }
            "launch_messages" -> "message" to KaviraFigures.BRANCHES.map { message(it) }
            else -> return null
        }
        return buildJsonObject {
            put("stages", buildJsonObject { put(stage, buildJsonObject { put("op", "LLM_MAP"); put("items", JsonArray(items)) }) })
        }
    }

    private fun money(v: Long) = DeckFormat.compact(v.toDouble(), money = true)
    private fun pct(a: Long, b: Long) = DeckFormat.pct(a.toDouble(), b.toDouble())

    private fun item(key: String, output: JsonObject) = buildJsonObject { put("item", key); put("output", output) }

    private fun letter(s: KaviraFigures.Stockist): JsonObject {
        val growth = pct(s.fy2025, s.fy2026)
        val trend = when {
            growth == null -> "your first full year with us"
            growth.startsWith("+") -> "up $growth on FY2025 (${money(s.fy2025)})"
            else -> "$growth on FY2025 (${money(s.fy2025)})"
        }
        return item("${s.id} · ${s.town} · ${s.branch}", buildJsonObject {
            put("greeting", "Dear ${s.town} team at ${s.id},")
            put("body", "Thank you for another year of partnership with Kavira Pharmaceuticals. In FY2026 your purchases came to ${money(s.fy2026)}, $trend. " +
                "Your average payment time was ${s.paymentDays} days.")
            put("payment_note", if (s.paymentDays > 50) "A small request: settling invoices within 45 days would help us keep every brand in stock for you through the monsoon season."
                else "We appreciate how promptly your invoices are settled; it keeps our supply to you steady.")
            put("closing", "With warm regards, the ${s.branch} branch of Kavira Pharmaceuticals")
        })
    }

    private fun review(b: KaviraFigures.Brand): JsonObject {
        val g = pct(b.fy2025, b.fy2026)
        val headline = when {
            b.fy2026 == 0L -> "${b.brand}: no sales in FY2026"
            b.fy2025 == 0L -> "${b.brand}: first full year, ${money(b.fy2026)}"
            g != null && g.startsWith("+") -> "${b.brand} grew $g to ${money(b.fy2026)}"
            else -> "${b.brand} slipped $g to ${money(b.fy2026)}"
        }
        val series = listOf("FY2024" to b.fy2024, "FY2025" to b.fy2025, "FY2026" to b.fy2026).filter { it.second > 0 }
        return item("${b.brand} · ${b.therapy}", buildJsonObject {
            put("headline", headline)
            put("performance", "Net sales: " + series.joinToString(", ") { "${it.first} ${money(it.second)}" } + ". " +
                (if (b.units2026 > 0) "FY2026 moved ${DeckFormat.compact(b.units2026.toDouble(), money = false)} units. " else "") +
                "The figures come straight from the Sales phone's monthly table; the trend is the one to plan FY2027 stock and promotion around.")
            putJsonArray("recommendations") {
                add(JsonPrimitive(if (g != null && g.startsWith("+")) "Protect the growth: keep stockist cover ahead of the seasonal peak" else "Review pricing and promotion against the FY2025 run-rate"))
                add(JsonPrimitive("Brief the medical reps on the FY2026 figures at the next Monday review"))
                add(JsonPrimitive("Agree FY2027 targets per branch before the April cycle"))
            }
        })
    }

    private fun pack(br: KaviraFigures.Branch): JsonObject {
        val g = pct(br.fy2025, br.fy2026)
        return item("${br.code} · ${br.city}", buildJsonObject {
            put("summary", "${br.city} closed FY2026 at ${money(br.fy2026)} net sales, ${g ?: "its first full year"} on FY2025 (${money(br.fy2025)}). " +
                "Stockist and retail remain the largest channel; online pharmacy keeps gaining share.")
            putJsonArray("top_issues") {
                add(JsonPrimitive("Stock cover before the monsoon peak for the acute and antifungal brands"))
                add(JsonPrimitive("Collections: review stockists paying slower than 50 days"))
                add(JsonPrimitive("Acneclear Gel detailing coverage among dermatologists"))
            }
            putJsonArray("talking_points") {
                add(JsonPrimitive("FY2026 net sales ${money(br.fy2026)}, $g on the year"))
                add(JsonPrimitive("Every figure in this pack came from this branch's own rows"))
                add(JsonPrimitive("FY2027 targets go out after the April cycle"))
            }
        })
    }

    private fun message(br: KaviraFigures.Branch): JsonObject {
        val (language, text) = when (br.state) {
            "Karnataka" -> "Kannada" to "ಕವಿರಾ ಫಾರ್ಮಾಸ್ಯುಟಿಕಲ್ಸ್‌ನಿಂದ ಹೊಸ ಉತ್ಪನ್ನ: Acneclear Gel (ಅಡಾಪಲೀನ್ 0.1% + ಕ್ಲಿಂಡಮೈಸಿನ್ 1%, 15 ಗ್ರಾಂ ಟ್ಯೂಬ್) ಈಗ ಲಭ್ಯ. " +
                "ಮೊಡವೆ ಚಿಕಿತ್ಸೆಗಾಗಿ ಚರ್ಮರೋಗ ತಜ್ಞರು ಮತ್ತು ವೈದ್ಯರಿಗೆ ಈ ವಾರವೇ ಪರಿಚಯಿಸಿ. ವಿವರಗಳಿಗೆ ನಿಮ್ಮ ಏರಿಯಾ ಸೇಲ್ಸ್ ಮ್ಯಾನೇಜರ್ ಅವರನ್ನು ಸಂಪರ್ಕಿಸಿ."
            "Tamil Nadu" -> "Tamil" to "கவிரா பார்மாசூட்டிகல்ஸ் புதிய தயாரிப்பு: Acneclear Gel (அடாபலீன் 0.1% + கிளிண்டாமைசின் 1%, 15 கிராம் டியூப்) இப்போது கிடைக்கிறது. " +
                "முகப்பரு சிகிச்சைக்காக தோல் மருத்துவர்கள் மற்றும் மருத்துவர்களிடம் இந்த வாரமே அறிமுகப்படுத்துங்கள். விவரங்களுக்கு உங்கள் ஏரியா சேல்ஸ் மேனேஜரை தொடர்பு கொள்ளுங்கள்."
            else -> "Malayalam" to "കവിര ഫാർമസ്യൂട്ടിക്കൽസിന്റെ പുതിയ ഉൽപ്പന്നം: Acneclear Gel (അഡാപലീൻ 0.1% + ക്ലിൻഡാമൈസിൻ 1%, 15 ഗ്രാം ട്യൂബ്) ഇപ്പോൾ ലഭ്യമാണ്. " +
                "മുഖക്കുരു ചികിത്സയ്ക്കായി ത്വക്‌രോഗ വിദഗ്ധരെയും ഡോക്ടർമാരെയും ഈ ആഴ്ച തന്നെ പരിചയപ്പെടുത്തുക. വിവരങ്ങൾക്ക് നിങ്ങളുടെ ഏരിയ സെയിൽസ് മാനേജറെ ബന്ധപ്പെടുക."
        }
        return item("${br.code} · ${br.city} · ${br.state}", buildJsonObject {
            put("language", language)
            put("message", text)
            put("english_gloss", "New from Kavira Pharmaceuticals: Acneclear Gel (adapalene 0.1% + clindamycin 1%, 15 g tube) is now available. " +
                "Introduce it to dermatologists and doctors this week for acne treatment. Contact your area sales manager for details.")
        })
    }
}
