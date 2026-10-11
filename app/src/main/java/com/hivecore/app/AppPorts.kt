package com.hivecore.app

import com.hivecore.core.Canon
import com.hivecore.core.Catalog
import com.hivecore.core.Confidence
import com.hivecore.core.JobProgress
import com.hivecore.core.JobRequest
import com.hivecore.core.JobRunner
import com.hivecore.core.KeyValuePrefs
import com.hivecore.core.ModelEntry
import com.hivecore.core.ModelRegistry
import com.hivecore.core.OpClass
import com.hivecore.core.OpRegistry
import com.hivecore.core.AskOutcome
import com.hivecore.core.AskProgress
import com.hivecore.core.Profile
import com.hivecore.core.SLog
import com.hivecore.core.SchemaSource
import com.hivecore.core.TaskSpec
import com.hivecore.engine.Commander
import com.hivecore.engine.EscalationRequest
import com.hivecore.engine.EscalationTrigger
import com.hivecore.engine.Escalator
import com.hivecore.engine.GateOutcome
import com.hivecore.engine.Lineage
import com.hivecore.engine.PlanRenderer
import com.hivecore.engine.ProfileBeacon
import com.hivecore.engine.SwarmNode
import com.hivecore.engine.workload.RecipeDef
import com.hivecore.ledger.Ledger
import com.hivecore.mesh.LanPairPing
import com.hivecore.mesh.MeshJson
import com.hivecore.mesh.MeshRouter
import com.hivecore.mesh.MsgType
import com.hivecore.mesh.PeerStore
import com.hivecore.mesh.RosterStore
import com.hivecore.mesh.Wire
import com.hivecore.node.data.DataStore
import com.hivecore.node.docs.DocStore
import com.hivecore.planner.CapabilitySummary
import com.hivecore.planner.CloudPrices
import com.hivecore.planner.ExecutionChoice
import com.hivecore.planner.ExecutionMode
import com.hivecore.planner.ExecutionOptions
import com.hivecore.planner.TieredPlan
import com.hivecore.planner.TieredPlanner
import com.hivecore.runtime.bench.BenchTable
import com.hivecore.runtime.bench.CapabilityBench
import com.hivecore.runtime.catalog.ModelCatalog
import com.hivecore.runtime.models.CapabilityPool
import com.hivecore.runtime.models.ModelPoolImpl
import com.hivecore.trust.Invite
import com.hivecore.trust.PairingEvent
import com.hivecore.trust.PairingImpl
import com.hivecore.trust.RosterAdmin
import com.hivecore.trust.SasPrompt
import com.hivecore.ui.ports.AnswerBadge
import com.hivecore.ui.ports.AskAnswer
import com.hivecore.ui.ports.AskEvent
import com.hivecore.ui.ports.AskPlan
import com.hivecore.ui.ports.AskPort
import com.hivecore.ui.ports.BenchResult
import com.hivecore.ui.ports.CloudPolicy
import com.hivecore.ui.ports.DataPort
import com.hivecore.ui.ports.InviteView
import com.hivecore.ui.ports.ItemKind
import com.hivecore.ui.ports.JoinRequestView
import com.hivecore.ui.ports.MediaItem
import com.hivecore.ui.ports.MemberView
import com.hivecore.ui.ports.ModeEstimate
import com.hivecore.ui.ports.ModelsPort
import com.hivecore.ui.ports.PairPingView
import com.hivecore.ui.ports.PhoneModelState
import com.hivecore.ui.ports.PingReplyView
import com.hivecore.ui.ports.PlanOptions
import com.hivecore.ui.ports.PlanStepView
import com.hivecore.ui.ports.Refusal
import com.hivecore.ui.ports.Release
import com.hivecore.ui.ports.RosterView
import com.hivecore.ui.ports.RunMode
import com.hivecore.ui.ports.SasView
import com.hivecore.ui.ports.SettingsKeys
import com.hivecore.ui.ports.SettingsPort
import com.hivecore.ui.ports.SwarmPort
import com.hivecore.ui.ports.Visibility
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.UUID

// The real implementations of the UI ports (spec 17) over the engines. Each one is built by `AppWiring` only when its
// phase flag is on; the fakes from `:ui` stay in place otherwise, so the app always runs.

/** Spec 17 (Swarm) over the mesh+trust track: roster, peers from the beacon, invites, join, SAS, admin actions. */
class SwarmPortImpl(
    override val selfId: String,
    private val selfName: () -> String,
    private val router: MeshRouter,
    private val peerStore: PeerStore,
    private val rosterStore: RosterStore,
    private val admin: RosterAdmin,
    private val pairing: PairingImpl,
    private val profiles: () -> List<Profile>,
    private val groupId: () -> String,
    private val scope: CoroutineScope,
    override val warnings: StateFlow<List<String>>,
    /** item 6: the relay side of an invite (opens the token with the relay) */
    private val onInviteOpened: suspend (Invite) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    /** "Ping nearby" over the Wi-Fi; null when the LAN mesh is off (the actions then fail with a plain message) */
    private val pairPing: LanPairPing? = null,
) : SwarmPort {
    private val rosterState = MutableStateFlow(rosterView())
    override val roster: StateFlow<RosterView> = rosterState
    private val peersState = MutableStateFlow<List<Profile>>(emptyList())
    override val peers: StateFlow<List<Profile>> = peersState
    private val joinsState = MutableStateFlow<List<JoinRequestView>>(emptyList())
    override val pendingJoins: StateFlow<List<JoinRequestView>> = joinsState
    private val sasState = MutableStateFlow<List<SasView>>(emptyList())
    override val sasPrompts: StateFlow<List<SasView>> = sasState
    private val prompts = LinkedHashMap<String, SasPrompt>()
    private val pingsState = MutableStateFlow<List<PairPingView>>(emptyList())
    override val pairPings: StateFlow<List<PairPingView>> = pingsState
    private val repliesState = MutableStateFlow<List<PingReplyView>>(emptyList())
    override val pingReplies: StateFlow<List<PingReplyView>> = repliesState

    init {
        scope.launch { pairing.prompts.collect { p -> synchronized(prompts) { prompts[p.handshakeId] = p }; publishSas() } }
        scope.launch {
            pairing.events.collect { e ->
                val peer = when (e) { is PairingEvent.Paired -> e.nodeId; is PairingEvent.Aborted -> e.peerNodeId }
                synchronized(prompts) { prompts.values.removeAll { it.peerNodeId == peer } }
                publishSas(); refresh()
            }
        }
        scope.launch { while (isActive) { refresh(); delay(3_000) } }
        if (pairPing != null) {
            // a ping from a phone we already hold the key of is dropped: joining it would only say "already paired"
            scope.launch { pairPing.requests.collect { reqs -> pingsState.value = reqs.filter { !alreadyPaired(it.payload) }.map { PairPingView(it.id, it.fromName, it.ts) } } }
            scope.launch { pairPing.replies.collect { r -> repliesState.update { (listOf(PingReplyView(r.fromName, r.approved, r.ts)) + it).take(REPLIES_KEPT) } } }
        }
    }

    private fun alreadyPaired(payload: String): Boolean {
        val id = Invite.fromText(payload)?.pubkey?.let { Wire.nodeIdOf(it) } ?: return false
        return id == selfId || peerStore.get(id) != null
    }

    /** Ping nearby: a fresh invite (same token rules as the QR) broadcast to every phone on this Wi-Fi. */
    override suspend fun pingNearby(): Result<InviteView> = runCatching {
        val pp = pairPing ?: throw IllegalStateException("pinging needs the LAN mesh")
        if (!pp.running) throw IllegalStateException("the mesh is not running yet")
        val g = groupId()
        if (rosterStore.current(g) == null) admin.create(g, selfName())?.let { gossip(MsgType.ROSTER, MeshJson.encode(it)) }
        val inv = pairing.startInvite(g)
        runCatching { onInviteOpened(inv) }.onFailure { SLog.w(TAG, "relay invite: ${it.message}") }
        val sent = withContext(Dispatchers.IO) { pp.ping(inv.qrPayload()).getOrThrow() }
        repliesState.value = emptyList()
        SLog.d(TAG, "pinged ${sent.sentTo} addresses")
        InviteView(inv.link(), inv.issuedTs + Invite.TOKEN_TTL_MS)
    }

    override suspend fun answerPing(id: String, approve: Boolean): Result<Unit> {
        val pp = pairPing ?: return Result.failure(IllegalStateException("pinging needs the LAN mesh"))
        val ping = pp.requests.value.firstOrNull { it.id == id } ?: return Result.failure(IllegalStateException("that request is gone"))
        withContext(Dispatchers.IO) { pp.reply(ping, approve) }.onFailure { SLog.w(TAG, "ping reply: ${it.message}") }
        if (!approve) return Result.success(Unit)
        return join(ping.payload).map { }
    }

    private fun publishSas() { sasState.value = synchronized(prompts) { prompts.values.map { SasView(it.handshakeId, it.peerName, it.digits) } } }

    private fun rosterView(): RosterView {
        val r = rosterStore.latest() ?: return RosterView("", 0, "", emptyList())
        return RosterView(r.groupId, r.version, r.admin, r.members.map { MemberView(it.nodeId, liveName(it.nodeId) ?: it.name, it.nodeId == r.admin) })
    }

    /** The roster keeps the name from pairing time; the self name and peers' HELLO names are fresher. */
    private fun liveName(nodeId: String): String? = if (nodeId == selfId) selfName() else router.peerName(nodeId)

    /** Re-reads the roster, the fresh profiles and the pinned-but-not-yet-admitted peers. */
    fun refresh() {
        val view = rosterView()
        rosterState.value = view
        peersState.value = runCatching { profiles().filter { it.nodeId != selfId } }.getOrDefault(emptyList())
        joinsState.value = if (view.adminId == selfId) peerStore.all().filter { p -> view.members.none { it.nodeId == p.nodeId } }
            .map { JoinRequestView(it.nodeId, it.name, it.pinnedAt) } else emptyList()
    }

    override suspend fun createInvite(): Result<InviteView> = runCatching {
        val g = groupId()
        if (rosterStore.current(g) == null) admin.create(g, selfName())?.let { gossip(MsgType.ROSTER, MeshJson.encode(it)) }
        val inv = pairing.startInvite(g)
        runCatching { onInviteOpened(inv) }.onFailure { SLog.w(TAG, "relay invite: ${it.message}") }
        InviteView(inv.link(), inv.issuedTs + Invite.TOKEN_TTL_MS)
    }

    override suspend fun join(link: String): Result<SasView> {
        val inv = Invite.fromText(link) ?: return Result.failure(IllegalArgumentException("that is not an invite link or QR"))
        return pairing.join(inv).map { SasView(it.handshakeId, it.peerName, it.digits) }
    }

    override suspend fun confirmSas(handshakeId: String, matches: Boolean): Result<Unit> {
        val p = synchronized(prompts) { prompts[handshakeId] } ?: return Result.failure(IllegalStateException("that handshake is no longer waiting"))
        return pairing.confirm(p, matches).also { synchronized(prompts) { prompts.remove(handshakeId) }; publishSas() }
    }

    override suspend fun approve(nodeId: String): Result<Unit> = runCatching {
        val p = peerStore.get(nodeId) ?: throw IllegalStateException("that phone is not paired")
        val r = admin.add(groupId(), nodeId, p.pubkey, p.name) ?: throw IllegalStateException("only the admin can approve members")
        gossip(MsgType.ROSTER, MeshJson.encode(r)); refresh()
    }

    override suspend fun revoke(nodeId: String): Result<Unit> = runCatching {
        val r = admin.revoke(groupId(), nodeId) ?: throw IllegalStateException("only the admin can revoke a member (and not itself)")
        gossip(MsgType.REVOKE, MeshJson.encode(r)); router.forget(nodeId); refresh()
    }

    override suspend fun handOver(nodeId: String): Result<Unit> = runCatching {
        val r = admin.handover(groupId(), nodeId) ?: throw IllegalStateException("only the admin can hand the swarm over, to a member")
        gossip(MsgType.ROSTER, MeshJson.encode(r)); refresh()
    }

    /** A newly paired phone (PairingImpl.onPaired): the admin adds it to the roster and gossips the roster. */
    fun onPaired(nodeId: String, pubkey: String, name: String, group: String) {
        val r = admin.add(group, nodeId, pubkey, name)
        if (r != null) scope.launch { gossip(MsgType.ROSTER, MeshJson.encode(r)); refresh() } else refresh()
    }

    private suspend fun gossip(type: MsgType, body: String) { runCatching { router.broadcast(type, body) }.onFailure { SLog.w(TAG, "$type gossip: ${it.message}") } }

    private companion object { const val TAG = "SwarmPort"; const val REPLIES_KEPT = 12 }
}

/** Spec 17 (Models) over the runtime track: the catalog, each phone's state from its profile, load / unload / BENCH / pin. */
class ModelsPortImpl(
    private val selfId: String,
    private val selfName: String,
    private val catalogOf: () -> ModelCatalog,
    private val pool: CapabilityPool,
    private val registry: ModelRegistry,
    private val bench: CapabilityBench,
    private val table: BenchTable,
    private val profiles: () -> List<Profile>,
    private val nameOf: (String) -> String,
    private val prefs: KeyValuePrefs,
    private val scope: CoroutineScope,
    /** fetches a catalog build from its `url` into the models folder (spec 17 "Download"); null = no downloads */
    private val downloader: com.hivecore.runtime.models.ModelDownloader? = null,
) : ModelsPort {
    private val catalogState = MutableStateFlow(catalogOf().entries)
    override val catalog: StateFlow<List<ModelEntry>> = catalogState
    private val phonesState = MutableStateFlow<List<PhoneModelState>>(emptyList())
    override val phones: StateFlow<List<PhoneModelState>> = phonesState
    private val pinnedState = MutableStateFlow(readPins())
    override val pinned: StateFlow<Map<String, String>> = pinnedState

    init { scope.launch { while (isActive) { refresh(); delay(5_000) } } }

    private fun readPins(): Map<String, String> = catalogOf().capabilities.mapNotNull { c -> prefs.getString(PIN + c, null)?.let { c to it } }.toMap()

    /** Rebuilds the per-phone table from the latest profiles (`capabilities[]`, `p50`) and this phone's BENCH table. */
    fun refresh() {
        val cat = catalogOf()
        catalogState.value = cat.entries
        val records = table.records()
        val ps = runCatching { profiles() }.getOrDefault(emptyList())
        val out = ArrayList<PhoneModelState>()
        for (p in ps) {
            val name = if (p.nodeId == selfId) selfName else nameOf(p.nodeId)
            for (e in cat.entries.distinctBy { it.modelId }) {
                val cs = p.capabilities.filter { it.modelId == e.modelId }
                out += PhoneModelState(p.nodeId, name, e.modelId, installed = cs.isNotEmpty(), loaded = cs.any { it.warm },
                    benchP50Ms = p.p50[e.modelId], lastBenchTs = if (p.nodeId == selfId) records[e.modelId]?.ts else null)
            }
        }
        phonesState.value = out
    }

    private fun entry(modelId: String): ModelEntry = catalogOf().entriesOf(modelId).firstOrNull() ?: throw IllegalArgumentException("$modelId is not in the catalog")

    override suspend fun download(modelId: String): Result<Unit> = runCatching {
        val d = downloader ?: throw UnsupportedOperationException("model downloads are off in this build: copy the bundle into the models folder")
        val e = entry(modelId)
        if (e.url == null) throw IllegalStateException("$modelId has no download source in the catalog: copy the bundle into the models folder, or install a catalog with a url")
        d.fetch(e).getOrThrow()
        refresh()
    }

    override suspend fun load(modelId: String): Result<Unit> = runCatching { pool.ensure(entry(modelId)).getOrThrow(); refresh() }

    override suspend fun unload(modelId: String): Result<Unit> = runCatching {
        val p = pool as? ModelPoolImpl ?: throw UnsupportedOperationException("this pool cannot unload")
        if (!p.evict(modelId) && p.bigModel == modelId) p.releaseBig().getOrThrow()
        refresh()
    }

    override suspend fun bench(modelId: String): Result<BenchResult> = runCatching {
        val r = bench.one(entry(modelId), CapabilityBench.DEFAULT_RUNS)
        table.record(listOf(r))
        refresh()
        r.error?.let { throw IllegalStateException(it) }
        BenchResult(modelId, r.p50Ms ?: r.loadMs.toDouble(), r.ts)
    }

    override suspend fun pin(capability: String, modelId: String): Result<Unit> = runCatching {
        require(catalogOf().entry(modelId, capability) != null) { "$modelId does not serve $capability" }
        prefs.putString(PIN + capability, modelId); pinnedState.value = readPins()
    }

    companion object { const val PIN = "model_pin:" }
}

/**
 * Spec 17 (Data) over the owner's stores: private marks and confidential columns live in the DataStore; releases and
 * hidden tables (the data store has no columns for them) live in prefs under [RELEASE] / [HIDDEN]; media is the
 * files in [mediaDir].
 */
class DataPortImpl(
    private val store: DataStore,
    private val docs: DocStore,
    private val prefs: KeyValuePrefs,
    private val mediaDir: File,
) : DataPort {
    private val mediaMarks = HashSet<String>()

    override fun media(): List<MediaItem> = (mediaDir.listFiles() ?: emptyArray()).filter { it.isFile }.sortedBy { it.name }.map { f ->
        val ext = f.extension.lowercase()
        val kind = when (ext) { "jpg", "jpeg", "png", "webp" -> "photo"; "mp3", "wav", "m4a", "ogg" -> "audio"; "mp4", "mkv", "webm" -> "video"; else -> "file" }
        MediaItem(f.name, f.name, kind, f.length())
    }

    override fun visibility(): List<Visibility> {
        val s = store.schema()
        val priv = store.privateTables(); val privDocs = store.privateDocs()
        val tables = s.tables.map { t ->
            Visibility(ItemKind.TABLE, t.name, private = t.name in priv, release = release(ItemKind.TABLE, t.name),
                hidden = prefs.getBoolean(HIDDEN + t.name, false), confidentialColumns = t.columns.filter { it.confidential }.map { it.name }.toSet())
        }
        val docViews = s.docs.map { d -> Visibility(ItemKind.DOC, d.id, private = d.id in privDocs, release = release(ItemKind.DOC, d.id)) }
        val media = media().map { m -> Visibility(ItemKind.MEDIA, m.id, private = prefs.getBoolean(PRIVATE_MEDIA + m.id, false), release = release(ItemKind.MEDIA, m.id)) }
        return tables + docViews + media
    }

    private fun release(kind: ItemKind, id: String): Release? {
        val v = prefs.getString(RELEASE + kind.name + ":" + id, null) ?: return null
        return when {
            v.startsWith("job:") -> Release.ForJob(v.removePrefix("job:"))
            v.startsWith("until:") -> v.removePrefix("until:").toLongOrNull()?.takeIf { it > System.currentTimeMillis() }?.let { Release.Until(it) }
            else -> null
        }
    }

    override fun setPrivate(kind: ItemKind, id: String, on: Boolean) = when (kind) {
        ItemKind.TABLE -> store.setTablePrivate(id, on)
        ItemKind.DOC -> store.setDocPrivate(id, on)
        ItemKind.MEDIA -> prefs.putBoolean(PRIVATE_MEDIA + id, on)
    }

    override fun release(kind: ItemKind, id: String, release: Release?) {
        val v = when (release) { null -> null; is Release.ForJob -> "job:" + release.jobId; is Release.Until -> "until:" + release.untilTs }
        prefs.putString(RELEASE + kind.name + ":" + id, v)
    }

    override fun setConfidentialColumns(table: String, columns: Set<String>) {
        val t = store.schema().tables.firstOrNull { it.name == table } ?: return
        for (c in t.columns) if ((c.name in columns) != c.confidential) store.setConfidential(table, c.name, c.name in columns)
    }

    override fun hideTable(table: String, hidden: Boolean) = prefs.putBoolean(HIDDEN + table, hidden)

    /** Tables kept out of the swarm catalog (the Swarm graph's `hidden`). */
    fun hiddenTables(): Set<String> = store.schema().tables.map { it.name }.filter { prefs.getBoolean(HIDDEN + it, false) }.toSet()

    /** Released media ids for one job (`release = items` of a workload). */
    fun releasedFor(jobId: String): Set<String> = media().map { it.id }.filter { id ->
        when (val r = release(ItemKind.MEDIA, id)) { is Release.ForJob -> r.jobId == jobId; is Release.Until -> true; null -> false }
    }.toSet()

    companion object { const val RELEASE = "release:"; const val HIDDEN = "hidden_table:"; const val PRIVATE_MEDIA = "private_media:" }
}

/** Spec 17 (Settings): the OpenRouter key through the encrypted store (cloud flag), the ledger export, known peers. */
class SettingsPortImpl(
    private val keys: com.hivecore.cloud.CloudKeyStore?,
    private val peerStore: PeerStore?,
    private val router: MeshRouter?,
    private val ledger: Ledger?,
    private val exportDir: File,
) : SettingsPort {
    override fun hasKey(): Boolean = keys?.present == true
    override fun keyHint(): String? = keys?.key()?.takeLast(4)?.let { "…$it" }
    override fun setKey(key: String): Result<Unit> = runCatching {
        val k = keys ?: throw IllegalStateException("the cloud is not part of this build")
        require(key.trim().length >= 16) { "that does not look like an OpenRouter key" }
        k.set(key.trim())
    }
    override fun clearKey() { keys?.set(null) }

    override suspend fun exportLedger(): Result<String> = runCatching {
        val l = ledger ?: throw IllegalStateException("this build keeps no ledger")
        exportDir.mkdirs()
        val f = File(exportDir, "ledger-${System.currentTimeMillis()}.json")
        f.writeText(Canon.canonical(l.exportJson()))
        f.absolutePath
    }

    override fun knownPeers(): List<MemberView> = peerStore?.all()?.map { MemberView(it.nodeId, it.name) } ?: emptyList()

    override suspend fun forgetPeer(nodeId: String): Result<Unit> = runCatching {
        val r = router ?: throw IllegalStateException("the mesh is not part of this build")
        r.forget(nodeId)
    }
}

/**
 * Spec 17 (Ask) over the planner tiers and the graph engine: plan → `TieredPlanner.plan` and
 * `ExecutionOptions.compute` → the two options; run → `SwarmNode.askComposite` with the planned question (a request
 * naming a recipe runs it as a split job instead); "not accurate" → the gate with the user-verdict trigger.
 *
 * Both options run the same plan locally first: the CLOUD option's pre-dispatch of eligible steps is not wired
 * (the gate still re-runs failed steps when the user allowed the cloud), which the option's detail line says.
 */
class AskPortImpl(
    private val selfId: String,
    private val selfName: String,
    private val planner: TieredPlanner,
    private val node: SwarmNode,
    private val jobs: JobRunner,
    private val catalogs: () -> List<Catalog>,
    private val caps: () -> CapabilitySummary,
    private val profiles: () -> List<Profile>,
    private val catalogEntries: () -> List<ModelEntry>,
    private val prices: () -> CloudPrices?,
    private val policy: () -> CloudPolicy,
    private val online: () -> Boolean,
    private val recipes: () -> List<RecipeDef>,
    private val escalator: Escalator,
    private val nameOf: (String) -> String,
    /** the pattern library, for the guide's account of what the swarm can plan (null = not loaded) */
    private val patterns: com.hivecore.planner.Patterns? = null,
) : AskPort {
    private class Held(val plan: TieredPlan, val catalogs: List<Catalog>, val recipe: RecipeDef?, val choice: ExecutionChoice? = null)

    /** What the guide says about the swarm right now: the catalogs, whether any phone draws, the patterns and recipes. */
    private fun facts(): com.hivecore.engine.guide.SwarmFacts {
        val cats = catalogs().sortedBy { it.node != selfId }
        return com.hivecore.engine.guide.SwarmFacts(
            phones = cats.map { c -> com.hivecore.engine.guide.PhoneFacts(if (c.node == selfId) selfName else c.nodeName, c.tables.map { it.name }, c.docs, self = c.node == selfId) },
            canDraw = com.hivecore.core.Op.GEN_IMAGE in caps().ops,
            patterns = patterns?.textPatterns?.associate { it.id to it.title } ?: emptyMap(),
            recipes = recipes().map { it.title },
            examples = com.hivecore.ui.vm.AskSuggestions.DEFAULT.filter { it.kind == com.hivecore.ui.vm.Suggestion.Kind.ASK }.map { it.request },
        )
    }

    private val held = object : LinkedHashMap<String, Held>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Held>?): Boolean = size > 32
    }

    override suspend fun plan(request: String): PlanOptions {
        val q = request.trim()
        recipeFor(q)?.let { r ->
            val planId = UUID.randomUUID().toString()
            synchronized(held) { held[planId] = Held(TieredPlan(com.hivecore.core.RouteDecision(false, emptyList(), emptyList(), "recipe"), com.hivecore.planner.PlanTier.T0), emptyList(), r) }
            val phones = profiles().count { it.eligible || it.nodeId == selfId }.coerceAtLeast(1)
            return PlanOptions.Ready(
                AskPlan(planId, "", q, listOf(PlanStepView(r.id, "RECIPE", "swarm", 0)), 1.0, "T0"),
                ModeEstimate(RunMode.LOCAL, 0, 0.0, "nothing leaves the phones", phones, "${r.title}: ${r.produces}"), null,
                cloudBlockedReason = "recipes run on the phones")
        }
        // a question about the swarm itself is answered from the catalog at once, with no plan (spec amendment "the guide")
        com.hivecore.engine.guide.SwarmGuide.answer(q, facts())?.let { return PlanOptions.Answered(com.hivecore.ui.ports.guideAnswer(it, selfName)) }
        val cats = catalogs()
        val tp = try { planner.plan(q, cats, caps()) } catch (e: Exception) { return PlanOptions.Refused(Refusal(e.message ?: "planning failed")) }
        val pq = tp.plan ?: run {
            // spec 17: one plain sentence, and for a question the planner could not place at all, what to ask instead
            val sentence = tp.refusal ?: "That isn't a question the swarm can plan."
            val hint = if (tp.tier == com.hivecore.planner.PlanTier.T0) " " + com.hivecore.engine.guide.SwarmGuide.hint(facts()) else ""
            return PlanOptions.Refused(Refusal(sentence + hint, detail = tp.note))
        }
        if (tp.clarification.isNotEmpty() || pq.confidence < Confidence.LOW) {
            val readings = tp.clarification.ifEmpty { listOf(q) }
            return PlanOptions.Clarify(q, readings, pq.confidence)
        }
        val compiled = tp.compiled ?: return PlanOptions.Refused(Refusal("the plan could not be compiled"))
        if (!compiled.ok) return PlanOptions.Refused(Refusal(compiled.check.errors.joinToString("; "), compiled.planHash))
        val choice = ExecutionOptions.compute(compiled, profiles(), catalogEntries(), prices(), policy().toPlanner(), online(), selfId)
        val planId = UUID.randomUUID().toString()
        synchronized(held) { held[planId] = Held(tp, cats, null, choice) }
        val lineage = Lineage.lineages(compiled.dag.tasks)
        val steps = compiled.dag.tasks.map { t ->
            val sv = choice.steps.firstOrNull { it.id == t.id }
            val cls = OpRegistry[t.op].cls
            val phone = when {
                cls == OpClass.OWNER -> t.owner?.let { if (it == selfId) selfName else nameOf(it) } ?: selfName
                cls == OpClass.REDUCE -> selfName
                else -> "swarm"
            }
            PlanStepView(t.id, t.op.name, phone, 0, cloud = choice.default == ExecutionMode.CLOUD && sv?.cloudEligible == true,
                verified = null, privateLineage = lineage[t.id] == Lineage.PRIVATE)
        }
        val local = choice.local.local?.let { l ->
            ModeEstimate(RunMode.LOCAL, l.ms, 0.0, "nothing leaves the phones", l.phones.size.coerceAtLeast(1),
                "${l.phones.size} phone${if (l.phones.size == 1) "" else "s"}" + l.loads.filter { it.swap }.takeIf { it.isNotEmpty() }?.let { " · ${it.size} model swap(s)" }.orEmpty())
        }
        val cloud = choice.cloud.cloud?.takeIf { choice.cloud.enabled }?.let { c ->
            ModeEstimate(RunMode.CLOUD, c.ms, c.costInr, "${c.bytesOut / 1024} KB of prompt text for ${c.steps.size} step${if (c.steps.size == 1) "" else "s"}",
                (choice.cloud.local?.phones?.size ?: 0).coerceAtLeast(1), "steps ${c.steps.joinToString()} run in the cloud from this phone; the rest stay on the phones")
        }
        return PlanOptions.Ready(
            AskPlan(planId, compiled.planHash, q, steps, pq.confidence, tp.tier.name), local, cloud,
            localBlockedReason = null, cloudBlockedReason = choice.cloud.reason,
            suggested = if (choice.default == ExecutionMode.CLOUD) RunMode.CLOUD else RunMode.LOCAL)
    }

    private fun recipeFor(q: String): RecipeDef? {
        val id = q.removePrefix("recipe:").trim()
        return recipes().firstOrNull { it.id.equals(id, ignoreCase = true) || it.title.equals(q, ignoreCase = true) }
    }

    override fun run(plan: AskPlan, mode: RunMode): Flow<AskEvent> = flow {
        val h = synchronized(held) { held[plan.planId] } ?: run { emit(AskEvent.Refused(Refusal("that plan expired; plan again", plan.planHash))); return@flow }
        h.recipe?.let { r -> runRecipe(plan, r); return@flow }
        val pq = h.plan.plan ?: run { emit(AskEvent.Refused(Refusal("that plan has no steps", plan.planHash))); return@flow }
        // spec 9.1: the CLOUD option sends the eligible LLM / VLM steps to the cloud from this phone; everything else stays local
        val cloudSteps = if (mode == RunMode.CLOUD) h.choice?.steps?.filter { it.cloudEligible }?.map { it.id }?.toSet().orEmpty() else emptySet()
        node.askComposite(plan.request, h.plan.route, h.catalogs, pq, cloudSteps).collect { p ->
            when (p) {
                is AskProgress.Status -> emit(AskEvent.Status(p.text))
                is AskProgress.Heard -> Unit
                is AskProgress.Done -> when (val o = p.outcome) {
                    is AskOutcome.Answer -> emit(AskEvent.Answered(answer(plan, mode, o)))
                    is AskOutcome.Refusal -> emit(AskEvent.Refused(Refusal(o.reasons.joinToString("; ") { (who, why) -> if (who == selfName) why else "$who: $why" }, plan.planHash)))
                    is AskOutcome.NoNode -> emit(AskEvent.Refused(Refusal("no phone holds the data this question needs", plan.planHash)))
                }
            }
        }
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<AskEvent>.runRecipe(plan: AskPlan, r: RecipeDef) {
        var last: JobProgress? = null
        jobs.run(JobRequest(r.title, recipeId = r.id)).collect { p ->
            last = p
            if (!p.done && p.error == null) emit(AskEvent.Status("${p.chunks.count { it.state == com.hivecore.core.ChunkState.DONE }} of ${p.chunks.size} chunks"))
        }
        val p = last ?: run { emit(AskEvent.Refused(Refusal("the job produced nothing", plan.planHash))); return }
        if (p.error != null) { emit(AskEvent.Refused(Refusal(p.error!!, plan.planHash, step = r.id))); return }
        val text = (p.resultSummary ?: "done") + (p.partialReason?.let { " ($it)" } ?: "")
        emit(AskEvent.Answered(AskAnswer(UUID.randomUUID().toString(), plan.planHash, text, emptyList(),
            if (p.cloud.isNotEmpty()) AnswerBadge.CLOUD else if (p.unverified == 0) AnswerBadge.VERIFIED else AnswerBadge.UNVERIFIED,
            plan.steps.map { it.copy(cloud = p.cloud.isNotEmpty()) }, RunMode.LOCAL, jobId = p.jobId, elapsedMs = p.elapsedMs)))
    }

    private fun answer(plan: AskPlan, mode: RunMode, a: AskOutcome.Answer): AskAnswer {
        val summary = a.plan
        val steps = plan.steps.mapIndexed { i, s ->
            val line = summary?.lines?.getOrNull(i).orEmpty()
            s.copy(phone = line.split(" · ").getOrNull(1) ?: s.phone, cloud = Commander.isCloud(line.split(" · ").getOrNull(1).orEmpty()), verified = line.endsWith("✓").takeIf { line.isNotEmpty() })
        }
        val badge = when {
            steps.any { it.cloud } -> AnswerBadge.CLOUD
            summary != null && summary.verified == summary.tasks -> AnswerBadge.VERIFIED
            else -> AnswerBadge.UNVERIFIED
        }
        return AskAnswer(UUID.randomUUID().toString(), summary?.planHash ?: plan.planHash, a.text, a.provenance, badge, steps, mode,
            notes = a.notes, elapsedMs = a.timing.totalMs ?: 0L, deck = com.hivecore.engine.deck.Deck.fromJson(a.artifact),
            picture = com.hivecore.engine.picture.Picture.fromJson(a.artifact))
    }

    override fun notAccurate(answer: AskAnswer, stepId: String?): Flow<AskEvent> = flow {
        val h = synchronized(held) { held.values.firstOrNull { it.plan.planHash == answer.planHash } }
        val dag = h?.plan?.compiled?.dag ?: run { emit(AskEvent.Refused(Refusal("that answer's plan is no longer held", answer.planHash))); return@flow }
        val step = dag.tasks.firstOrNull { it.id == stepId }
            ?: dag.tasks.lastOrNull { OpRegistry[it.op].cls == OpClass.LLM || OpRegistry[it.op].cls == OpClass.VLM }
            ?: run { emit(AskEvent.Refused(Refusal("this answer has no model step to re-run", answer.planHash))); return@flow }
        val lineage = Lineage.lineages(dag.tasks)[step.id] ?: Lineage.PUBLIC
        emit(AskEvent.Status("Asking the cloud to re-run ${step.op.name.lowercase()} '${step.id}'…"))
        val req = EscalationRequest(answer.planHash, answer.planHash, selfId, step, emptyMap(), lineage, EscalationTrigger.UserVerdict, 1)
        when (val g = try { escalator.tryEscalate(req) } catch (e: Exception) { GateOutcome.Failed(e.message ?: "escalation failed") }) {
            is GateOutcome.Ran -> emit(AskEvent.Answered(answer.copy(answerId = UUID.randomUUID().toString(),
                text = PlanRenderer.render(step, g.output, emptyList(), listOf("${step.op} ${step.id} re-run by ${g.slug}")),
                badge = AnswerBadge.CLOUD, steps = answer.steps.map { if (it.id == step.id) it.copy(cloud = true, verified = null) else it },
                provenance = listOf(com.hivecore.core.Provenance(Commander.cloudExecutor(g.slug), "${Commander.cloudExecutor(g.slug)}: ${step.op}")))))
            is GateOutcome.Refused -> emit(AskEvent.Refused(Refusal(g.reason, answer.planHash, step.id)))
            is GateOutcome.Failed -> emit(AskEvent.Refused(Refusal("the cloud re-run failed: ${g.reason}", answer.planHash, step.id)))
        }
    }

    private fun CloudPolicy.toPlanner(): com.hivecore.planner.CloudPolicy = when (this) {
        CloudPolicy.ASK -> com.hivecore.planner.CloudPolicy.ASK_EVERY_TIME
        CloudPolicy.LOCAL_UNLESS_IMPOSSIBLE -> com.hivecore.planner.CloudPolicy.LOCAL_UNLESS_IMPOSSIBLE
        CloudPolicy.FASTEST -> com.hivecore.planner.CloudPolicy.FASTEST
    }

    companion object {
        /** The ui policy from the Settings pref (spec 9.2). */
        fun policyOf(prefs: KeyValuePrefs): CloudPolicy = CloudPolicy.of(prefs.getString(SettingsKeys.CLOUD_POLICY, null))
    }
}

/** The beacon as a `() -> List<Profile>` for the ports; this phone's profile is kept (the Models screen lists it). */
fun ProfileBeacon.profilesNow(): () -> List<Profile> = { snapshot() }

internal fun JsonElement.asText(): String = (this as? JsonPrimitive)?.content ?: Canon.canonical(this)
