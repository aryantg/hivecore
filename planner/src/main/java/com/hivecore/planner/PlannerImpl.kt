package com.hivecore.planner

import com.hivecore.core.Catalog
import com.hivecore.core.ClosedChooser
import com.hivecore.core.ColType
import com.hivecore.core.Confidence
import com.hivecore.core.Dag
import com.hivecore.core.Op
import com.hivecore.core.OpClass
import com.hivecore.core.OpRegistry
import com.hivecore.core.PlannedQuestion
import com.hivecore.core.Planner
import com.hivecore.core.RouteDecision
import com.hivecore.core.SLog
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Failure of [PlannerImpl.plan]; the message is shown as the refusal. */
open class PlanFailed(message: String) : Exception(message)

/** T1 found no pattern for the question: one of the spec 7.2 reasons for the facade to ask T2. */
class NoPatternFits(message: String) : PlanFailed(message)

/**
 * Question → DAG (swarm plan §5.1). The LLM only ever answers closed questions; every option comes from the pattern
 * library, the catalogs' vocabulary or substrings of the question, so nothing free-form reaches the plan.
 *
 * 1. Pattern: options = text patterns whose `requires` ops are covered by the route's cues (plus the patterns' own cue
 *    words found in the question), plus "none". "none" (or no compatible pattern) → [PlanFailed] listing what the
 *    swarm can do.
 * 2. Slots, in the order measure/count/doc → dimension → the rest. Candidates per type (see [Candidates]); a slot with one
 *    candidate is bound without a model call (confidence 1.0); several → a closed choice. No candidate: an optional slot
 *    stays unbound, a slot with a default takes it, a required slot fails the plan.
 * 3. Grounding ([PlanGrounding.check]): every bound value traces to the question text or catalog vocabulary.
 * 4. Binding ([Patterns.bind]) with catalog checks (owner tables, groupable dims, confidential metrics).
 *
 * Confidence = min over the decisions. When one is below [Confidence.LOW] (split votes) the result is still a success:
 * the DAG of the leading reading plus `clarification` = the top two readings (empty DAG when the leading reading cannot
 * be bound). [clock] gives "today" for date phrases.
 */
class PlannerImpl(
    private val chooser: ClosedChooser,
    private val patterns: Patterns,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val k: Int = 3,
    /** ops some phone of the swarm runs right now (null = unknown): a pattern's `optional_op` nodes are dropped for the rest */
    private val availableOps: () -> Set<Op>? = { null },
) : Planner {

    private fun explicitGrouping(q: QText, c: Candidate): Boolean {
        val s = c.span ?: return false
        return s.first > 0 && q.words[s.first - 1].lowercase() in GROUPING_WORDS
    }

    private data class Picked<T>(val value: T?, val confidence: Double, val top: List<T>, val anyVote: Boolean)

    override suspend fun plan(question: String, route: RouteDecision, catalogs: List<Catalog>): Result<PlannedQuestion> {
        val q = QText(question)
        val today = Instant.ofEpochMilli(clock()).atZone(zone).toLocalDate()
        val routeOps = route.cues.map { it.op }.toSet()
        val compatible = patterns.textPatterns.filter { compatible(it, routeOps, q) }
        if (compatible.isEmpty()) return Result.failure(NoPatternFits(nothingFits()))

        val confidences = ArrayList<Double>()
        var clarification: List<String> = emptyList()

        // 1. pattern
        val options: List<Pattern?> = compatible + listOf(null)
        val pp = ask("Which plan fits the question \"${question.trim()}\"?", options, { it?.let { p -> "${p.id}: ${p.title}" } ?: "none: none of these fits" })
        if (!pp.anyVote) return fail("could not choose a plan for this question (no valid answer from the model)")
        confidences += pp.confidence
        val pattern = (pp.value ?: pp.top.first()) ?: return Result.failure(NoPatternFits(nothingFits()))
        if (pp.value == null || pp.confidence < Confidence.LOW) {
            clarification = pp.top.take(2).map { it?.title ?: "none of these" }
            if (clarification.size < 2) clarification = clarification + compatible.map { it.title }.filter { it !in clarification }.take(2 - clarification.size)
        }
        // a heavy step the pattern cannot do without (the picture of make_image) needs a phone that runs it right now
        availableOps()?.let { ops ->
            pattern.dag.firstOrNull { !it.optionalOp && OpRegistry[it.op].cls == OpClass.HEAVY && it.op !in ops }?.let { return fail(unavailable(it.op)) }
        }

        // 2. slots
        val slots = LinkedHashMap<String, SlotValue>()
        val spans = HashMap<String, IntRange>()
        val cands = Candidates(q, catalogs, route.owners, today)
        for (spec in ordered(pattern)) {
            val list = cands.of(spec, pattern, slots, spans)
            when {
                list.isEmpty() && spec.default != null -> slots[spec.name] = cands.default(spec)
                list.isEmpty() && spec.optional -> {}
                list.isEmpty() -> return fail(missing(pattern, spec))
                list.size == 1 && !spec.optional -> bindOne(list[0], spec, slots, spans)
                list.size == 1 && spec.type != SlotTypes.DIMENSION -> bindOne(list[0], spec, slots, spans)
                // "… by region", "for each region": the grouping is stated outright, no closed choice needed
                list.size == 1 && explicitGrouping(q, list[0]) -> bindOne(list[0], spec, slots, spans)
                else -> {
                    // an optional dimension the question mentions is still a closed choice against "none"
                    val opts: List<Candidate?> = if (spec.optional) list + listOf(null) else list
                    val prompt = "In the question \"${question.trim()}\", which is ${spec.prompt.ifEmpty { spec.name }}?"
                    val p = ask(prompt, opts, { it?.label ?: "none: not stated" })
                    if (!p.anyVote) return fail("could not fill ${spec.name} for ${pattern.id} (no valid answer from the model)")
                    confidences += p.confidence
                    val lead = p.value ?: p.top.first()
                    if ((p.value == null || p.confidence < Confidence.LOW) && clarification.isEmpty()) {
                        clarification = p.top.take(2).map { c -> reading(pattern, slots, spec.name, c?.value) }
                    }
                    if (lead != null) bindOne(lead, spec, slots, spans)
                    else if (!spec.optional) return fail(missing(pattern, spec))
                }
            }
        }

        val confidence = confidences.minOrNull() ?: 1.0
        fun clarify(dag: Dag?) = Result.success(PlannedQuestion(dag ?: Dag(pattern.id, patterns.version, emptyList(), ""), confidence, clarification))

        // 3. grounding
        val errors = PlanGrounding.check(question, slots, catalogs, today)
        if (errors.isNotEmpty()) {
            SLog.w(TAG, "ungrounded: ${errors.joinToString("; ")}")
            return if (clarification.isNotEmpty()) clarify(null) else fail("I can't tie this plan to the question: ${errors.first()}")
        }
        // 4. bind
        val dag = patterns.bind(pattern, slots, catalogs, availableOps())
        if (dag.isFailure) {
            val why = dag.exceptionOrNull()?.message ?: "bind failed"
            return if (clarification.isNotEmpty()) clarify(null) else fail("I can't build that plan: $why")
        }
        SLog.d(TAG, "plan ${pattern.id} conf=${"%.2f".format(confidence)} slots=${slots.mapValues { it.value.value }}")
        return if (clarification.isNotEmpty()) clarify(dag.getOrThrow())
        else Result.success(PlannedQuestion(dag.getOrThrow(), confidence))
    }

    private fun bindOne(c: Candidate, spec: SlotSpec, slots: MutableMap<String, SlotValue>, spans: MutableMap<String, IntRange>) {
        slots[spec.name] = c.value
        c.span?.let { spans[spec.name] = it }
    }

    private fun reading(p: Pattern, bound: Map<String, SlotValue>, name: String, v: SlotValue?): String {
        val slots = LinkedHashMap(bound)
        if (v != null) slots[name] = v else slots.remove(name)
        val unbound = p.slots.map { it.name }.filter { it !in slots && p.slots.first { s -> s.name == it }.optional.not() }
        val withPlaceholders = unbound.fold(p.reading.ifEmpty { p.title }) { acc, s -> acc.replace("{$s}", "…") }
        return Patterns.text(withPlaceholders, slots, null, emptySet())
    }

    private suspend fun <T> ask(question: String, options: List<T>, render: (T) -> String): Picked<T> {
        if (options.size == 1) return Picked(options[0], 1.0, options, true)
        val d = chooser.choose(question, options, render, k)
        val ranked = d.ranked().ifEmpty { listOfNotNull(d.value) }
        return Picked(d.value, d.confidence, ranked, d.agreeing > 0 || d.value != null)
    }

    private fun compatible(p: Pattern, routeOps: Set<Op>, q: QText): Boolean {
        val covered = routeOps + patterns.textPatterns.filter { it.cues.any { c -> q.find(c) != null } }.mapNotNull { it.requires.firstOrNull() }
        return p.requires.isNotEmpty() && covered.containsAll(p.requires)
    }

    private fun ordered(p: Pattern): List<SlotSpec> {
        val first = setOf(SlotTypes.MEASURE, SlotTypes.COUNT, SlotTypes.DOC, SlotTypes.ITEMS)
        return p.slots.filter { it.type in first } + p.slots.filter { it.type == SlotTypes.DIMENSION } +
            p.slots.filter { it.type !in first && it.type != SlotTypes.DIMENSION }
    }

    private fun missing(p: Pattern, s: SlotSpec): String = when (s.type) {
        SlotTypes.MEASURE -> "I can't find an amount any phone holds in the question (needed for ${p.id}: ${s.prompt})"
        SlotTypes.COUNT -> "I can't find what to count in the question (needed for ${p.id}: ${s.prompt})"
        SlotTypes.DIMENSION -> "I can't find a grouping both phones can use in the question (needed for ${p.id}: ${s.prompt})"
        SlotTypes.DOC -> "I can't find a shared, non-confidential document named in the question"
        SlotTypes.TEXT -> "I can't tell what to draw: say what the picture should show, for example \"Create an image of a pharmacy counter at dawn\"."
        else -> "the question does not state ${s.prompt.ifEmpty { s.name }} (needed for ${p.id})"
    }

    private fun unavailable(op: Op): String = when (op) {
        Op.GEN_IMAGE, Op.GEN_VARIANTS -> "No phone of the swarm can draw right now: a picture needs a phone on the charger with the image model loaded " +
            "(the Models page shows which phone has it). Charts of your own figures don't need it: ask for a pitch deck."
        else -> "No phone of the swarm runs ${op.name.lowercase().replace('_', ' ')} right now: it needs a phone on the charger with its model loaded."
    }

    private fun nothingFits(): String =
        "That isn't something the swarm can work out. It can answer: " + patterns.textPatterns.joinToString("; ") { it.title } + "."

    private fun fail(msg: String): Result<PlannedQuestion> = Result.failure(PlanFailed(msg))

    companion object {
        const val TAG = "SwarmPlanner"
        private val GROUPING_WORDS = setOf("by", "each", "per", "every")
    }
}

/** A slot candidate: the value, its closed-question label and the question words it came from. */
data class Candidate(val value: SlotValue, val label: String, val span: IntRange?)

/** The question as normalised words (lowercase, singular-insensitive) for whole-word phrase search. */
class QText(val raw: String) {
    /** the question's words as written */
    val words: List<String> = SPLIT.split(raw).filter { it.isNotEmpty() }
    private val norm = words.map { norm(it.lowercase()) }

    /** First word span where [phrase] occurs as whole words (plural-insensitive), or null. */
    fun find(phrase: String, avoid: Collection<IntRange> = emptyList()): IntRange? {
        val p = SPLIT.split(phrase.lowercase()).filter { it.isNotEmpty() }.map(::norm)
        if (p.isEmpty()) return null
        for (i in 0..norm.size - p.size) {
            if ((p.indices).all { norm[i + it] == p[it] }) {
                val r = i until i + p.size
                if (avoid.none { it.first <= r.last && r.first <= it.last }) return r
            }
        }
        return null
    }

    /** The question's own words for [span] ("Revenue" → "revenue"). */
    fun text(span: IntRange): String = words.subList(span.first, span.last + 1).joinToString(" ")

    companion object {
        private val SPLIT = Regex("[^\\p{L}\\p{N}]+")
        fun norm(w: String): String = when {
            w.endsWith("ies") && w.length > 4 -> w.dropLast(3) + "y"
            w.endsWith("s") && !w.endsWith("ss") && w.length > 3 -> w.dropLast(1)
            else -> w
        }
    }
}

/** Date phrases (the vocabulary of the intent prompt's date rules) → month range "YYYY-MM". */
object Periods {
    private val MONTHS = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september",
        "october", "november", "december")

    /** Every vocabulary phrase found in [q] (whole words), first occurrence order, with its range. */
    fun find(q: QText, today: LocalDate): List<Pair<IntRange, PeriodSlot>> {
        val out = ArrayList<Pair<IntRange, PeriodSlot>>()
        val taken = ArrayList<IntRange>()
        // year spans first ("FY2024-26", "2024 to 2026", "FY2025"): they contain the plain years the vocabulary would find
        for ((span, slot) in yearSpans(q)) { taken += span; out += span to slot }
        for (phrase in vocabulary(q)) {
            val span = q.find(phrase, taken) ?: continue
            val slot = resolve(q.text(span), today) ?: continue
            taken += span
            out += span to slot
        }
        return out.sortedBy { it.first.first }
    }

    private fun vocabulary(q: QText): List<String> {
        val fixed = listOf("year to date", "this year", "last year", "this quarter", "last quarter", "this month", "last month") +
            (1..4).map { "q$it" } + MONTHS.filter { it != "may" }   // "may" is too often the verb
        val years = q.words.filter { it.length == 4 && it.all(Char::isDigit) && it.startsWith("20") }.distinct()
        return fixed + years
    }

    private val YEAR4 = Regex("(fy)?(20\\d\\d)")
    private val YEAR2 = Regex("(fy)?(\\d\\d)")
    private val RANGE_JOIN = setOf("to", "and", "through", "till", "until")

    private class Year(val y: Int, val fiscal: Boolean)

    private fun year(w: String, allowShort: Boolean): Year? {
        val s = w.lowercase()
        YEAR4.matchEntire(s)?.let { return Year(it.groupValues[2].toInt(), it.groupValues[1].isNotEmpty()) }
        if (!allowShort) return null
        YEAR2.matchEntire(s)?.let { return Year(2000 + it.groupValues[2].toInt(), it.groupValues[1].isNotEmpty()) }
        return null
    }

    /**
     * "FY2024-26", "FY24-26", "2024-2026", "2024 to 2026" and a lone "FY2025": a span of years, fiscal (April to March,
     * named by the year it ends in) when either end says "FY". The words come from [QText], so a hyphen is a word break.
     */
    fun yearSpans(q: QText): List<Pair<IntRange, PeriodSlot>> {
        val out = ArrayList<Pair<IntRange, PeriodSlot>>()
        var i = 0
        val w = q.words
        while (i < w.size) {
            val a = year(w[i], allowShort = w[i].lowercase().startsWith("fy"))
            if (a == null) { i++; continue }
            var j = i + 1
            if (j < w.size && w[j].lowercase() in RANGE_JOIN) j++
            val b = if (j < w.size) year(w[j], allowShort = true) else null
            val span = if (b != null && b.y >= a.y) i..j else if (a.fiscal) i..i else { i++; continue }
            val end = b?.takeIf { span.last == j } ?: a
            val fiscal = a.fiscal || end.fiscal
            val slot = if (fiscal) PeriodSlot(q.text(span), "%04d-04".format(a.y - 1), "%04d-03".format(end.y))
                else PeriodSlot(q.text(span), "%04d-01".format(a.y), "%04d-12".format(end.y))
            out += span to slot
            i = span.last + 1
        }
        return out
    }

    /** Range for a vocabulary phrase relative to [today], or null when it isn't one. */
    fun resolve(phrase: String, today: LocalDate): PeriodSlot? {
        yearSpans(QText(phrase)).singleOrNull()?.takeIf { (s, _) -> s.first == 0 && s.last == QText(phrase).words.size - 1 }?.let { return it.second.copy(phrase = phrase.trim()) }
        val p = phrase.trim().lowercase()
        val y = today.year; val m = today.monthValue
        fun ym(yy: Int, mm: Int) = "%04d-%02d".format(yy, mm)
        fun slot(a: String, b: String) = PeriodSlot(phrase.trim(), a, b)
        val q0 = (m - 1) / 3
        return when {
            p == "this year" -> slot(ym(y, 1), ym(y, 12))
            p == "year to date" -> slot(ym(y, 1), ym(y, m))
            p == "last year" -> slot(ym(y - 1, 1), ym(y - 1, 12))
            p == "this month" -> slot(ym(y, m), ym(y, m))
            p == "last month" -> if (m == 1) slot(ym(y - 1, 12), ym(y - 1, 12)) else slot(ym(y, m - 1), ym(y, m - 1))
            p == "this quarter" -> slot(ym(y, q0 * 3 + 1), ym(y, q0 * 3 + 3))
            p == "last quarter" -> if (q0 == 0) slot(ym(y - 1, 10), ym(y - 1, 12)) else slot(ym(y, q0 * 3 - 2), ym(y, q0 * 3))
            p.length == 2 && p[0] == 'q' && p[1] in '1'..'4' -> { val qq = p[1] - '1'; slot(ym(y, qq * 3 + 1), ym(y, qq * 3 + 3)) }
            p in MONTHS -> { val mm = MONTHS.indexOf(p) + 1; slot(ym(y, mm), ym(y, mm)) }
            p.length == 4 && p.all(Char::isDigit) && p.startsWith("20") -> { val yy = p.toInt(); slot(ym(yy, 1), ym(yy, 12)) }
            else -> null
        }
    }
}

/** Slot candidates from catalog vocabulary and question substrings only (swarm plan §5.1 step 2). */
class Candidates(private val q: QText, catalogs: List<Catalog>, routeOwners: List<String>, private val today: LocalDate) {
    /** route owners first, then node id: a deterministic preference when two phones hold the same table */
    private val cats = catalogs.sortedWith(compareBy<Catalog> { it.node !in routeOwners }.thenBy { it.node })

    fun of(spec: SlotSpec, p: Pattern, bound: Map<String, SlotValue>, spans: Map<String, IntRange>): List<Candidate> {
        val used = spans.values.toList()
        return when (spec.type) {
            SlotTypes.MEASURE -> measures(used)
            SlotTypes.COUNT -> counts(used).filter { c -> bound.values.none { it is CountSlot && it.table == (c.value as CountSlot).table } }
            SlotTypes.DIMENSION -> dimensions(spec, p, bound, used)
            SlotTypes.PERIOD -> Periods.find(q, today).filter { (s, _) -> used.none { overlaps(it, s) } }.map { (s, v) -> Candidate(v, v.phrase, s) }
            SlotTypes.K -> numbers(used)
            SlotTypes.ORDER -> words(ORDER_WORDS, used)
            SlotTypes.CMP -> words(CMP_WORDS, used)
            SlotTypes.DOC -> docs(used)
            SlotTypes.TEXT -> subject(used)
            else -> emptyList()
        }
    }

    /**
     * The request's own words after its asking phrase ("please create an image of my team" → "my team"): the leading
     * politeness, verbs, articles and picture nouns are dropped, the rest is the subject as written. One candidate, or
     * none when nothing is left ("draw something").
     */
    private fun subject(used: List<IntRange>): List<Candidate> {
        val w = q.words
        var start = 0
        while (start < w.size && w[start].lowercase() in ASK_LEAD) start++
        var end = w.size
        while (end > start && (w[end - 1].lowercase() in TRAIL || used.any { end - 1 in it })) end--
        if (start >= end) return emptyList()
        val span = start until end
        if (used.any { overlaps(it, span) }) return emptyList()
        val text = q.text(span)
        return listOf(Candidate(TextSlot(text), text, span))
    }

    fun default(spec: SlotSpec): SlotValue {
        val d = spec.default!!
        return if (spec.type == SlotTypes.K) IntSlot("", d.toInt()) else WordSlot("", d)
    }

    private fun measures(used: List<IntRange>): List<Candidate> {
        val seen = HashSet<String>()
        val out = ArrayList<Candidate>()
        for (c in cats) for (t in c.tables) for (col in t.columns) {
            if (col.type == ColType.TEXT || col.identifier) continue
            val span = earliest(listOf(col.name) + col.aliases, used) ?: continue
            if (!seen.add("${t.name}.${col.name}")) continue
            val phrase = q.text(span)
            out += Candidate(MeasureSlot(phrase, t.name, col.name, c.node, col.confidential, t.columns.any { it.name == Patterns.MONTH }),
                "$phrase (${t.name}.${col.name} on ${c.nodeName})", span)
        }
        return out.sortedBy { it.span!!.first }
    }

    private fun counts(used: List<IntRange>): List<Candidate> {
        val seen = HashSet<String>()
        val out = ArrayList<Candidate>()
        for (c in cats) for (t in c.tables) {
            val span = earliest(listOf(t.name) + t.aliases, used) ?: continue
            if (!seen.add(t.name)) continue
            val phrase = q.text(span)
            out += Candidate(CountSlot(phrase, t.name, c.node, t.columns.any { it.name == Patterns.MONTH }),
                "$phrase (rows of ${t.name} on ${c.nodeName})", span)
        }
        return out.sortedBy { it.span!!.first }
    }

    /** groupable in every table the pattern's AGG nodes using this slot read; literal dims of those nodes excluded */
    private fun dimensions(spec: SlotSpec, p: Pattern, bound: Map<String, SlotValue>, used: List<IntRange>): List<Candidate> {
        val nodes = p.dag.filter { spec.name in it.dims }
        val literal = nodes.flatMap { it.dims }.filter { d -> p.slots.none { it.name == d } }.toSet()
        val tables = nodes.mapNotNull { n ->
            when (val v = bound[n.measure ?: n.count ?: ""]) {
                is MeasureSlot -> v.owner to v.table
                is CountSlot -> v.owner to v.table
                else -> null
            }
        }.distinct()
        val seen = HashSet<String>()
        val out = ArrayList<Candidate>()
        for (c in cats) for (t in c.tables) for (col in t.columns) {
            if (!col.groupable || col.name in literal || col.name in seen) continue
            val ok = tables.all { (owner, table) ->
                cats.firstOrNull { it.node == owner }?.tables?.firstOrNull { it.name == table }?.columns?.any { it.name == col.name && it.groupable } == true
            }
            if (!ok) continue
            val span = earliest(listOf(col.name) + col.aliases, used) ?: continue
            seen += col.name
            out += Candidate(DimensionSlot(q.text(span), col.name), "${q.text(span)} (group by ${col.name})", span)
        }
        return out.sortedBy { it.span!!.first }
    }

    private fun numbers(used: List<IntRange>): List<Candidate> {
        val out = ArrayList<Candidate>()
        q.words.forEachIndexed { i, w ->
            if (used.any { i in it }) return@forEachIndexed
            val n = w.toIntOrNull()?.takeIf { w.length <= 2 && it in 1..99 } ?: NUMBER_WORDS[w.lowercase()] ?: return@forEachIndexed
            if (out.none { (it.value as IntSlot).n == n }) out += Candidate(IntSlot(w, n), "$w ($n)", i..i)
        }
        return out
    }

    private fun words(table: Map<String, String>, used: List<IntRange>): List<Candidate> {
        val out = ArrayList<Candidate>()
        for ((phrase, v) in table) {
            val span = q.find(phrase, used) ?: continue
            if (out.none { (it.value as WordSlot).value == v }) out += Candidate(WordSlot(q.text(span), v), "${q.text(span)} ($v)", span)
        }
        return out.sortedBy { it.span!!.first }
    }

    private fun docs(used: List<IntRange>): List<Candidate> {
        val out = ArrayList<Candidate>()
        for (c in cats) for (d in c.docs) {
            if (out.any { (it.value as DocSlot).doc == d }) continue
            val span = q.find(docPhrase(d), used) ?: continue
            out += Candidate(DocSlot(q.text(span), d, c.node), "$d (on ${c.nodeName})", span)
        }
        return out.sortedBy { it.span!!.first }
    }

    private fun earliest(phrases: List<String>, used: List<IntRange>): IntRange? =
        phrases.mapNotNull { q.find(it, used) }.minWithOrNull(compareBy<IntRange> { it.first }.thenByDescending { it.last })

    private fun overlaps(a: IntRange, b: IntRange) = a.first <= b.last && b.first <= a.last

    companion object {
        val ORDER_WORDS = linkedMapOf("top" to "desc", "highest" to "desc", "best" to "desc", "most" to "desc", "largest" to "desc",
            "biggest" to "desc", "bottom" to "asc", "lowest" to "asc", "worst" to "asc", "least" to "asc", "smallest" to "asc")
        val CMP_WORDS = linkedMapOf("above" to "gt", "exceeds" to "gt", "exceed" to "gt", "more than" to "gt", "higher than" to "gt",
            "greater than" to "gt", "over" to "gt", "below" to "lt", "under" to "lt", "less than" to "lt", "lower than" to "lt")
        val NUMBER_WORDS = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
            "eight" to 8, "nine" to 9, "ten" to 10)
        /** words a picture request opens with, before its subject */
        val ASK_LEAD: Set<String> = setOf("please", "pls", "hey", "hi", "can", "could", "would", "will", "you", "u", "i", "we", "d", "want",
            "need", "like", "to", "just", "now", "me", "us", "a", "an", "the", "some", "new", "another", "nice", "quick", "create", "make",
            "generate", "draw", "render", "produce", "design", "sketch", "paint", "illustrate", "give", "show", "get", "image", "images",
            "picture", "pictures", "illustration", "illustrations", "drawing", "drawings", "artwork", "visual", "visuals", "graphic",
            "graphics", "pic", "pics", "of", "off", "for", "showing", "depicting", "about", "with", "that", "which", "shows", "represents")
        /** words dropped from the end of a subject */
        val TRAIL: Set<String> = setOf("please", "pls", "now", "thanks", "thank", "you")

        /** "onboarding_checklist.docx" → "onboarding checklist" */
        fun docPhrase(name: String): String = name.substringBeforeLast('.').replace('_', ' ').replace('-', ' ')
    }
}

/**
 * Grounding (§5.1, generalising `Grounding.pruneExtraIntents`): every slot value must trace to the question text or the
 * catalog vocabulary. Returns the problems (empty = grounded).
 */
object PlanGrounding {
    fun check(question: String, slots: Map<String, SlotValue>, catalogs: List<Catalog>, today: LocalDate): List<String> {
        val q = QText(question)
        val errs = ArrayList<String>()
        fun said(name: String, phrase: String) { if (q.find(phrase) == null) errs += "$name \"$phrase\" is not in the question" }
        for ((name, v) in slots) when (v) {
            is MeasureSlot -> {
                val col = catalogs.firstOrNull { it.node == v.owner }?.tables?.firstOrNull { it.name == v.table }?.columns?.firstOrNull { it.name == v.column }
                when {
                    col == null -> errs += "$name: ${v.table}.${v.column} is not in ${v.owner}'s catalog"
                    col.type == ColType.TEXT || col.identifier -> errs += "$name: ${v.table}.${v.column} is not a measure"
                    !vocab(col.name, col.aliases, v.phrase) -> errs += "$name \"${v.phrase}\" is not a name of ${v.table}.${v.column}"
                    else -> said(name, v.phrase)
                }
            }
            is CountSlot -> {
                val t = catalogs.firstOrNull { it.node == v.owner }?.tables?.firstOrNull { it.name == v.table }
                when {
                    t == null -> errs += "$name: ${v.table} is not in ${v.owner}'s catalog"
                    !vocab(t.name, t.aliases, v.phrase) -> errs += "$name \"${v.phrase}\" is not a name of ${v.table}"
                    else -> said(name, v.phrase)
                }
            }
            is DimensionSlot -> {
                val cols = catalogs.flatMap { c -> c.tables.flatMap { it.columns } }.filter { it.name == v.column && it.groupable }
                if (cols.isEmpty()) errs += "$name: ${v.column} is not a groupable column of any phone"
                else if (cols.none { vocab(it.name, it.aliases, v.phrase) }) errs += "$name \"${v.phrase}\" is not a name of ${v.column}"
                else said(name, v.phrase)
            }
            is PeriodSlot -> {
                val r = Periods.resolve(v.phrase, today)
                if (r == null || r.gte != v.gte || r.lte != v.lte) errs += "$name \"${v.phrase}\" is not ${v.gte}..${v.lte}"
                else said(name, v.phrase)
            }
            is IntSlot -> if (v.phrase.isNotEmpty()) {
                if (v.phrase.toIntOrNull() != v.n && Candidates.NUMBER_WORDS[v.phrase.lowercase()] != v.n) errs += "$name \"${v.phrase}\" is not ${v.n}"
                else said(name, v.phrase)
            }
            is WordSlot -> if (v.phrase.isNotEmpty()) {
                val w = v.phrase.lowercase()
                if (Candidates.ORDER_WORDS[w] != v.value && Candidates.CMP_WORDS[w] != v.value) errs += "$name \"${v.phrase}\" does not mean ${v.value}"
                else said(name, v.phrase)
            }
            is DocSlot -> {
                if (catalogs.none { it.node == v.owner && v.doc in it.docs }) errs += "$name: ${v.doc} is not a shared document of ${v.owner}"
                else said(name, v.phrase)
            }
            is TextSlot -> if (v.phrase.isBlank()) errs += "$name is empty" else said(name, v.phrase)
            is ItemsSlot -> {}
        }
        return errs
    }

    private fun vocab(name: String, aliases: List<String>, phrase: String): Boolean {
        fun n(s: String) = QText(s).words.map { QText.norm(it.lowercase()) }
        val p = n(phrase)
        return (listOf(name) + aliases).any { n(it) == p }
    }
}
