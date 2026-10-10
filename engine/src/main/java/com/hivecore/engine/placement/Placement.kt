package com.hivecore.engine.placement

import com.hivecore.core.Canon
import com.hivecore.core.LinkKind
import com.hivecore.core.ModelEntry
import com.hivecore.core.OpClass
import com.hivecore.core.OpRegistry
import com.hivecore.core.Profile
import com.hivecore.engine.SchedulerImpl
import com.hivecore.runtime.catalog.ModelCatalog
import kotlinx.serialization.Serializable

/**
 * Placement solver (spec 8.2): which phone loads which catalog build for a job, before any chunk is sent. A pure
 * function: same inputs (profiles in any order) → same assignments, so a placement is auditable.
 *
 *  1. Candidates per capability: eligible phones (plus `heavyOk` for a HEAVY capability) plus the orchestrator
 *     ([self], whatever its flags); the phone must advertise the capability in `Profile.capabilities` with a build
 *     the catalog knows (same sha256 when both are pinned, P12) and not be cooling down for it; a HEAVY or
 *     blob-carrying capability never goes over Bluetooth or the relay (5.6).
 *  2. Cost of a capability on a phone = `(loadMsP50 if its big slot holds a different model, else 0)
 *     + units × unitMs(model on that phone) + queueDepth × 3000 ms`, where `unitMs` is the phone's BENCH p50 for
 *     that build or the catalog's `unitMsP50`.
 *  3. Greedy list scheduling: capabilities by total demand, largest first; each capability goes to the phone with
 *     the lowest cost and then to every further phone that still shortens its makespan and would do at least one
 *     unit (units shared in proportion to speed), keeping one big model per phone per job; the busy time a phone already holds from earlier
 *     capabilities counts; ties are broken by `sha256(nodeId + capability)`.
 *  4. LAN phones come first for blob-carrying capabilities ([blobCaps]); for prompt-in/JSON-out capabilities cost
 *     decides and a WAN phone wins a tie, so LAN phones stay free for the work that moves bytes.
 *  5. Output: `[{nodeId, capability, modelId, sha256, swap}]` plus the expected swap cost (sum of the loads).
 */
object Placement {
    /** One phone's share of one capability. [units] = its expected share of the demand; [expectedMs] = its finish. */
    @Serializable data class Assignment(
        val nodeId: String,
        val capability: String,
        val modelId: String,
        val sha256: String,
        val swap: Boolean,
        val units: Int = 0,
        val expectedMs: Long = 0,
    )

    data class Outcome(
        val assignments: List<Assignment>,
        /** Σ loadMsP50 over the assignments that swap a big model (shown in the plan panel). */
        val swapCostMs: Long,
        /** Capabilities with no candidate phone at all (a capability gap). */
        val gaps: List<String>,
    ) {
        val makespanMs: Long get() = assignments.maxOfOrNull { it.expectedMs } ?: 0L
    }

    /** Spec 20.2: 3000 ms per queued task. */
    const val QUEUE_MS_PER_TASK = 3000L

    /** Capabilities of HEAVY ops (charger and cool required; `heavyOk` phones only). */
    val DEFAULT_HEAVY: Set<String> = OpRegistry.entries.values.filter { it.cls == OpClass.HEAVY }.mapNotNull { it.capability }.toSet()

    /** Capabilities whose steps carry blobs (images, audio, video): LAN preferred, never Bluetooth or relay. */
    val DEFAULT_BLOB: Set<String> = OpRegistry.entries.values
        .filter { it.inputShape in setOf("blobs", "image", "audio", "video") }.mapNotNull { it.capability }.toSet()

    private class Cand(val p: Profile, val entry: ModelEntry, val swap: Boolean, val unitMs: Double, val lan: Boolean, val tie: String) {
        val loadMs: Double get() = if (swap) entry.loadMsP50.toDouble() else 0.0
        val fixed: Double get() = loadMs + p.queueDepth * QUEUE_MS_PER_TASK
    }

    /**
     * The spec's `Placement.solve`: [demand] = capability → estimated units; [catalog] = the catalog entries;
     * [self] = the orchestrator. [heavyCaps] and [blobCaps] classify capabilities (defaults from the op registry);
     * [cooldowns] (plus the cooldowns in each profile, checked against [nowMs]) excludes phones; [exclude] = (node,
     * capability) pairs already asked in this job; [busyBig] = node → big model id already held for this job.
     */
    fun solve(
        demand: Map<String, Int>,
        profiles: List<Profile>,
        catalog: List<ModelEntry>,
        self: String,
        heavyCaps: Set<String> = DEFAULT_HEAVY,
        blobCaps: Set<String> = DEFAULT_BLOB,
        cooldowns: CooldownTable? = null,
        nowMs: Long? = null,
        exclude: Set<Pair<String, String>> = emptySet(),
        busyBig: Map<String, String> = emptyMap(),
    ): List<Assignment> = plan(demand, profiles, catalog, self, heavyCaps, blobCaps, cooldowns, nowMs, exclude, busyBig).assignments

    fun plan(
        demand: Map<String, Int>,
        profiles: List<Profile>,
        catalog: List<ModelEntry>,
        self: String,
        heavyCaps: Set<String> = DEFAULT_HEAVY,
        blobCaps: Set<String> = DEFAULT_BLOB,
        cooldowns: CooldownTable? = null,
        nowMs: Long? = null,
        exclude: Set<Pair<String, String>> = emptySet(),
        busyBig: Map<String, String> = emptyMap(),
    ): Outcome {
        val cat = ModelCatalog(catalog)
        val ps = SchedulerImpl.normalise(profiles)
        val wanted = demand.filterValues { it > 0 }
        val cands = wanted.keys.associateWith { cap -> candidates(cap, ps, cat, self, cap in heavyCaps, cap in blobCaps, cooldowns, nowMs, exclude) }
        val gaps = wanted.keys.filter { cands.getValue(it).isEmpty() }.sorted()
        val order = wanted.keys.filter { cands.getValue(it).isNotEmpty() }.sortedWith(
            compareByDescending<String> { cap -> wanted.getValue(cap) * (cat.preferred(cap)?.unitMsP50 ?: 1) }.thenBy { it })

        val busy = HashMap<String, Double>()                    // nodeId → ms already scheduled in this job
        val bigModelOf = HashMap(busyBig)                       // nodeId → big model id held for this job
        val out = ArrayList<Assignment>()
        val unplaced = ArrayList<String>()
        var swapCost = 0L
        for (cap in order) {
            val units = wanted.getValue(cap)
            val blob = cap in blobCaps
            fun cost(c: Cand): Double = c.fixed + (busy[c.p.nodeId] ?: 0.0) + units * c.unitMs
            val rank: Comparator<Cand> = if (blob)
                compareByDescending<Cand> { it.lan }.thenBy { cost(it) }.thenBy { it.tie }
            else
                compareBy<Cand> { cost(it) }.thenByDescending { !it.lan }.thenBy { it.tie }
            val chosen = ArrayList<Cand>()
            for (c in cands.getValue(cap).sortedWith(rank)) {
                val held = bigModelOf[c.p.nodeId]
                if (c.entry.big && held != null && held != c.entry.modelId) continue
                if (chosen.isEmpty()) { chosen += c; continue }
                val before = makespan(units, chosen, busy)
                val after = makespan(units, chosen + c, busy)
                val ownUnits = (after.first - c.fixed - (busy[c.p.nodeId] ?: 0.0)) / c.unitMs
                if (after.first < before.first && ownUnits >= 1.0) chosen += c
            }
            if (chosen.isEmpty()) { unplaced += cap; continue }       // every candidate's big slot is taken by another model
            val (span, shares) = makespan(units, chosen, busy)
            for ((c, share) in chosen.zip(shares)) {
                if (share <= 0 && chosen.size > 1) continue
                val expected = c.fixed + (busy[c.p.nodeId] ?: 0.0) + share * c.unitMs
                busy[c.p.nodeId] = expected
                if (c.entry.big) bigModelOf[c.p.nodeId] = c.entry.modelId
                if (c.swap) swapCost += c.entry.loadMsP50
                out += Assignment(c.p.nodeId, cap, c.entry.modelId, c.entry.sha256, c.swap, share, Math.round(maxOf(expected, 0.0)))
            }
            check(span >= 0.0)
        }
        return Outcome(out, swapCost, (gaps + unplaced).distinct().sorted())
    }

    private fun candidates(cap: String, ps: List<Profile>, cat: ModelCatalog, self: String, heavy: Boolean, blob: Boolean,
                           cooldowns: CooldownTable?, now: Long?, exclude: Set<Pair<String, String>>): List<Cand> {
        val builds = cat.forCapability(cap)
        return ps.mapNotNull { p ->
            if ((p.nodeId to cap) in exclude) return@mapNotNull null
            val remote = p.nodeId != self
            if (remote && !p.eligible) return@mapNotNull null
            if (heavy && remote && !p.heavyOk) return@mapNotNull null
            val link = p.link
            if ((heavy || blob) && remote && link != null && !link.carriesBlobs) return@mapNotNull null
            if (cooldowns?.isDown(p.nodeId, cap, p) == true) return@mapNotNull null
            if (cooldowns == null && now != null && p.cooldowns.any { it.capability == cap && it.untilTs > now }) return@mapNotNull null
            val states = p.capabilities.filter { it.capability == cap }
            val pair = builds.firstNotNullOfOrNull { e ->
                states.firstOrNull { s -> s.modelId == e.modelId && !(ModelCatalog.isHex64(s.sha256) && ModelCatalog.isHex64(e.sha256) && !s.sha256.equals(e.sha256, true)) }
                    ?.let { e to it }
            } ?: return@mapNotNull null
            val (e, s) = pair
            val swap = e.big && !s.warm
            val unit = p.p50[e.modelId] ?: e.unitMsP50.toDouble()
            val lan = !remote || link == null || link.kind == LinkKind.LAN || link.kind == LinkKind.P2P
            Cand(p, e, swap, maxOf(1.0, unit), lan, Canon.sha256Hex(p.nodeId + cap))
        }
    }

    /**
     * Water-filling makespan of [units] over [cs]: the time T at which the phones, each starting after its fixed
     * cost (load, queue, earlier work), have together done the units; returns T and each phone's integer share
     * (largest remainder, summing to [units]). A phone whose fixed cost exceeds T gets no share.
     */
    private fun makespan(units: Int, cs: List<Cand>, busy: Map<String, Double>): Pair<Double, List<Int>> {
        val fixed = cs.map { it.fixed + (busy[it.p.nodeId] ?: 0.0) }
        val order = cs.indices.sortedBy { fixed[it] }
        var t = 0.0
        var active = 0
        for (k in order.indices) {
            val set = order.take(k + 1)
            val rate = set.sumOf { 1.0 / cs[it].unitMs }
            val cand = (units + set.sumOf { fixed[it] / cs[it].unitMs }) / rate
            val next = order.getOrNull(k + 1)
            if (next == null || fixed[next] >= cand) { t = cand; active = k + 1; break }
        }
        val raw = cs.indices.map { i -> if (i in order.take(active)) maxOf(0.0, (t - fixed[i]) / cs[i].unitMs) else 0.0 }
        val floor = raw.map { it.toInt() }
        var left = units - floor.sum()
        val byRemainder = cs.indices.sortedWith(compareByDescending<Int> { raw[it] - floor[it] }.thenBy { cs[it].tie })
        val shares = floor.toMutableList()
        var i = 0
        while (left > 0 && byRemainder.isNotEmpty()) { shares[byRemainder[i % byRemainder.size]]++; left--; i++ }
        return t to shares
    }
}
