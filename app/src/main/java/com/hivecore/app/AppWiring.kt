package com.hivecore.app

import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import com.hivecore.BuildConfig
import com.hivecore.cloud.CloudFactory
import com.hivecore.cloud.CloudKeyStore
import com.hivecore.cloud.EncryptedCloudKeyStore
import com.hivecore.cloud.ModelsConfig
import com.hivecore.cloud.PrefsSpendStore
import com.hivecore.cloud.SpendCaps
import com.hivecore.cloud.TriggerSettings
import com.hivecore.core.AndroidSqlDb
import com.hivecore.core.Catalog
import com.hivecore.core.JobRunner
import com.hivecore.core.KeyValuePrefs
import com.hivecore.core.LlmBackend
import com.hivecore.core.LlmEngine
import com.hivecore.core.MergeKind
import com.hivecore.core.ModelPool
import com.hivecore.core.ModelRegistry
import com.hivecore.core.NodeRole
import com.hivecore.core.OpExecutor
import com.hivecore.core.PeerEndpoint
import com.hivecore.core.Profile
import com.hivecore.core.SLog
import com.hivecore.core.Signer
import com.hivecore.core.Transport
import com.hivecore.core.WorkerPolicy
import com.hivecore.core.fakes.FakeBus
import com.hivecore.core.fakes.FakeLlmEngine
import com.hivecore.core.fakes.FakeModelPool
import com.hivecore.core.fakes.FakeModelRegistry
import com.hivecore.core.fakes.FakeWorkerPolicy
import com.hivecore.engine.Escalator
import com.hivecore.engine.ProfileBeacon
import com.hivecore.engine.SwarmNode
import com.hivecore.engine.TaskRunnerImpl
import com.hivecore.engine.device.DeviceStateMonitor
import com.hivecore.engine.device.OwnerYield
import com.hivecore.engine.device.PresenceMonitor
import com.hivecore.engine.device.WorkerModeController
import com.hivecore.engine.device.WorkerPolicyImpl
import com.hivecore.engine.device.WorkerService
import com.hivecore.app.approval.ApprovalNotifier
import com.hivecore.core.Decision
import com.hivecore.engine.journal.ChunkMemo
import com.hivecore.engine.journal.DeputySelector
import com.hivecore.engine.journal.DeputyStore
import com.hivecore.engine.journal.JobJournal
import com.hivecore.engine.journal.JournalPull
import com.hivecore.engine.journal.MemoizingTaskRunner
import com.hivecore.engine.journal.ReplicatedJournal
import com.hivecore.engine.journal.SqliteChunkMemo
import com.hivecore.engine.journal.SqliteJobJournal
import com.hivecore.engine.journal.StreamingJournal
import com.hivecore.node.gen.ImageGenerator
import com.hivecore.node.gen.SdPipeline
import com.hivecore.runtime.models.ModelDownloader
import java.util.concurrent.atomic.AtomicReference
import com.hivecore.engine.placement.CooldownTable
import com.hivecore.engine.placement.ModelReqHandler
import com.hivecore.engine.placement.PlacementCoordinator
import com.hivecore.engine.split.ChunkPlanner
import com.hivecore.engine.split.SplitJob
import com.hivecore.engine.split.WorkQueue
import com.hivecore.engine.workload.RecipeDef
import com.hivecore.engine.workload.Recipes
import com.hivecore.ledger.Executor
import com.hivecore.ledger.Ledger
import com.hivecore.ledger.MergeOutcome
import com.hivecore.ledger.ReceiptExchange
import com.hivecore.ledger.TaskEntry
import com.hivecore.mesh.AndroidBtEndpoint
import com.hivecore.mesh.BtRfcommTransport
import com.hivecore.mesh.DataChannelTransport
import com.hivecore.mesh.HelloBody
import com.hivecore.mesh.LanTransport
import com.hivecore.mesh.LinkProbe
import com.hivecore.mesh.MeshJson
import com.hivecore.mesh.MeshRouter
import com.hivecore.mesh.MeshRouterAdapter
import com.hivecore.mesh.MsgType
import com.hivecore.mesh.NearbyTransport
import com.hivecore.mesh.NsdLanDiscovery
import com.hivecore.mesh.PeerStore
import com.hivecore.mesh.RelayPairingChannel
import com.hivecore.mesh.RelayPresence
import com.hivecore.mesh.RelayTransport
import com.hivecore.mesh.ReplayGuard
import com.hivecore.mesh.RosterStoreImpl
import com.hivecore.mesh.SecureTransport
import com.hivecore.mesh.SocketLanEndpoint
import com.hivecore.mesh.TurnConfig
import com.hivecore.mesh.WebRtcBlobTransport
import com.hivecore.node.data.AssetReader
import com.hivecore.node.data.DataStore
import com.hivecore.node.devai.Bench
import com.hivecore.node.docs.Bm25Index
import com.hivecore.node.docs.DocStore
import com.hivecore.node.docs.LlmDocAnswerer
import com.hivecore.node.media.AndroidAudioDecoder
import com.hivecore.node.media.AndroidFrameSource
import com.hivecore.node.textai.LlmMapExecutor
import com.hivecore.node.vision.ImageIo
import com.hivecore.planner.CapabilitySummary
import com.hivecore.planner.CloudPrices
import com.hivecore.planner.PlanCompilerImpl
import com.hivecore.planner.Patterns
import com.hivecore.planner.PlannerImpl
import com.hivecore.planner.Relevance
import com.hivecore.planner.SwarmRouterImpl
import com.hivecore.planner.T2Planner
import com.hivecore.planner.TieredPlannerImpl
import com.hivecore.runtime.bench.BenchTable
import com.hivecore.runtime.bench.CapabilityBench
import com.hivecore.runtime.bench.UnitWorkloads
import com.hivecore.runtime.catalog.ModelCatalog
import com.hivecore.runtime.catalog.ModelCatalogLoader
import com.hivecore.runtime.llm.ClosedChooserImpl
import com.hivecore.runtime.llm.FewShots
import com.hivecore.runtime.llm.GenieXEngine
import com.hivecore.runtime.llm.LlmIntentPlanner
import com.hivecore.runtime.llm.TemplateMatcher
import com.hivecore.runtime.models.CapabilityPool
import com.hivecore.runtime.models.GenieXVlmRuntime
import com.hivecore.runtime.models.LiteRtQnnRuntime
import com.hivecore.runtime.models.ModelPoolImpl
import com.hivecore.runtime.models.ModelRegistryImpl
import com.hivecore.runtime.models.OrtQnnRuntime
import com.hivecore.trust.ApprovalKeys
import com.hivecore.trust.ApprovalStore
import com.hivecore.trust.Invite
import com.hivecore.trust.KeystoreSigner
import com.hivecore.trust.PairingEvent
import com.hivecore.trust.PairingImpl
import com.hivecore.trust.PlanAuthoriser
import com.hivecore.trust.RosterAdmin
import com.hivecore.ui.ports.SettingsKeys
import com.hivecore.ui.ports.fakes.FakeAskPort
import com.hivecore.ui.ports.fakes.FakeDataPort
import com.hivecore.ui.ports.fakes.FakeModelsPort
import com.hivecore.ui.ports.fakes.FakeSettingsPort
import com.hivecore.ui.ports.fakes.FakeSwarmPort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The one wiring point on a phone (spec 18.1 `:app`). Every phase lands behind its flag ([Features]); a phase that is
 * off keeps the `:ui` fake of its port, so the app always runs. What is built:
 *
 *  - always: the Keystore identity, the databases (`hivecore.db` for data, peers and the ledger; `journal.db` for
 *    the journal and chunk memo), the data and document stores, the ledger;
 *  - `mesh`: LAN (NSD + TCP), Nearby and Bluetooth transports below the session layer, the router with the roster
 *    store, pairing (QR / link + SAS), the roster admin, `PlanAuthoriser` on the node's PLAN / TASK hooks (P10);
 *  - `runtime`: GenieX, the capability catalog, the model pool, BENCH, the worker policy and foreground service,
 *    owner yield, `MODEL_REQ` handling;
 *  - `split`: the swarm node over the executors ([SwarmExecutors] + `LLM_MAP`), the journal, memo, RECLAIM
 *    responder, resume coordinator, journal pull and deputy store, placement per job, receipts after each merge;
 *  - `graph`: the planner tiers, the compiler, the Ask and Data ports;
 *  - `cloud` (orchestrator factories only): `models.json`, the encrypted key store, spend caps, consent, the
 *    escalation gate as the orchestrator's escalator, the T2 planner;
 *  - `remote`: the relay and WebRTC transports always-on beside the selected one, presence and wake, link probes,
 *    the relay leg of pairing, FCM wake.
 *
 * Without `mesh` the node runs over an in-process loopback transport, so split jobs and plans still run on one phone.
 */
object AppWiring {
    private const val TAG = "Wiring"
    const val PREF_NODE_NAME = "node_name"
    const val PREF_NODE_ROLE = "node_role"
    const val PREF_GROUP_ID = "group_id"
    const val PREF_TRANSPORT = "transport"
    const val PREF_T2_FIRST = "t2_first"
    const val PREF_TIME_BUDGET_TRIGGER = "trigger_time_budget"
    const val DEFAULT_GROUP = "home"

    fun build(context: Context, prefs: KeyValuePrefs, scope: CoroutineScope): HiveCoreGraph.Ports {
        val app = context.applicationContext
        val t0 = System.currentTimeMillis()
        val warnings = MutableStateFlow<List<String>>(emptyList())
        fun warn(line: String) { SLog.w(TAG, line); warnings.update { (listOf(line) + it).take(5) } }

        // ---- identity and storage (always) ----
        val signer: Signer = KeystoreSigner()
        val selfId = com.hivecore.mesh.Wire.nodeIdOf(signer.publicKeyB64) ?: error("the node key is not base64")
        val selfName = { prefs.getString(PREF_NODE_NAME, null)?.takeIf { it.isNotBlank() } ?: Build.MODEL.take(16) }
        val groupId = { prefs.getString(PREF_GROUP_ID, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_GROUP }
        val db = AndroidSqlDb.open(File(app.filesDir, "hivecore.db"))
        val roleOf = { NodeRole.entries.firstOrNull { it.name == prefs.getString(PREF_NODE_ROLE, null) } ?: NodeRole.SALES }
        val store = DataStore(db, AssetReader { app.assets.open(it) }, roleProvider = roleOf).also { it.migrate() }
        val docStore = DocStore(db)
        store.docLister = { docStore.docs() }
        val index = Bm25Index { docStore.chunks() }
        val ledger = Ledger(db, signer)
        val peerStore = PeerStore(db)
        val rosterStore = RosterStoreImpl(db, selfId, signer.publicKeyB64) { peerStore.get(it)?.pubkey }
        val recipes = { Recipes.fromAssets(app.assets::list) { app.assets.open(it) } }
        val mediaDir = File(app.filesDir, "media").also { it.mkdirs() }
        val dataPort = DataPortImpl(store, docStore, prefs, mediaDir)

        // ---- runtime (phase 2) ----
        val llm: LlmEngine
        val registry: ModelRegistry
        val pool: ModelPool
        val catalog: ModelCatalog
        val benchTable = BenchTable.file(File(app.filesDir, BenchTable.FILE_NAME))
        val cooldowns = CooldownTable()
        var capBench: CapabilityBench? = null
        var device: DeviceStateMonitor? = null
        var presence: PresenceMonitor? = null
        var litert: com.hivecore.core.ModelRuntime? = null
        var ort: com.hivecore.core.ModelRuntime? = null
        if (Features.runtime) {
            val engine = GenieXEngine(app)
            llm = engine
            catalog = ModelCatalogLoader.android(app).load()
            val reg = ModelRegistryImpl(ModelRegistryImpl.defaultRoots(app), File(app.filesDir, "hash_cache.json"), File(app.filesDir, "warm_cache.json"))
            registry = reg
            val lib = app.applicationInfo.nativeLibraryDir
            litert = LiteRtQnnRuntime(lib, app.cacheDir)
            ort = OrtQnnRuntime(lib)
            val runtimes = listOf(litert!!, ort!!, GenieXVlmRuntime(app))
            val p = ModelPoolImpl(reg, runtimes, engine, catalog = { catalog })
            pool = p
            capBench = CapabilityBench(catalog, p, UnitWorkloads.default(p, reg, p::runtimeFor, engine), benchTable,
                installed = { reg.installed().map { it.id }.toSet() + setOf(ModelPoolImpl.LLM_QAIRT_ID, ModelPoolImpl.LLM_GGUF_ID) })
            device = DeviceStateMonitor(app).also { it.start() }
            presence = PresenceMonitor(app).also { it.start() }
            scope.launch(Dispatchers.IO) {
                val avail = runCatching { engine.available() }.getOrDefault(emptySet())
                val pick = listOf(LlmBackend.NPU, LlmBackend.GPU, LlmBackend.CPU).firstOrNull { it in avail }
                if (pick == null) warn("no LLM bundle found under ${GenieXEngine.defaultRoots(app).joinToString { it.name }}")
                else engine.load(pick).onFailure { warn("LLM load failed: ${it.message}") }
            }
        } else {
            llm = FakeLlmEngine(null)
            registry = FakeModelRegistry()
            pool = FakeModelPool(registry)
            catalog = ModelCatalog.EMPTY
        }

        // ---- mesh (phase 1) and remote (phase 6) ----
        val transports = LinkedHashMap<String, Transport>()
        val alwaysOn = LinkedHashSet<String>()
        var lan: LanTransport? = null
        var bus: FakeBus? = null
        if (Features.meshTrust) {
            lan = LanTransport(SocketLanEndpoint(0), NsdLanDiscovery(app), scope).also { transports[LanTransport.NAME] = it }
            transports[NearbyTransport.NAME] = NearbyTransport(app, scope) { prefix -> peerStore.all().any { it.nodeId.startsWith(prefix) } }
            runCatching { app.getSystemService(BluetoothManager::class.java)?.adapter }.getOrNull()
                ?.let { transports[BtRfcommTransport.NAME] = BtRfcommTransport(AndroidBtEndpoint(it), scope) }
        } else {
            bus = FakeBus()
            transports["loop"] = bus.node(selfId.take(8), selfName())
        }
        val relayUrl = { prefs.getString(SettingsKeys.RELAY_ADDRESS, null)?.trim()?.takeIf { it.isNotBlank() } }
        var relay: RelayTransport? = null
        var relayPresence: RelayPresence? = null
        var webrtc: WebRtcBlobTransport? = null
        val fcmToken = { prefs.getString(FcmService.PREF_TOKEN, null) }
        if (Features.remote && Features.meshTrust) {
            val url = relayUrl()
            if (url == null) warn("remote mesh: set the relay address in Settings")
            else {
                val r = RelayTransport(url, signer, groupId(), { rosterStore.current(groupId())?.version ?: 0L }, scope,
                    pushToken = fcmToken, rosterUpload = { rosterStore.current(groupId()) })
                relay = r
                transports[RelayTransport.NAME] = r
                alwaysOn += RelayTransport.NAME
                relayPresence = RelayPresence(r, scope)
            }
        }
        val replayGuard = relay?.let { ReplayGuard(now = it.clock::now) }
        val clock: () -> Long = relay?.let { it.clock::now } ?: System::currentTimeMillis
        lateinit var router: MeshRouter
        val signalSend: suspend (String, com.hivecore.mesh.SignalBody) -> Result<Unit> = { to, body -> router.send(to, MsgType.SIGNAL, MeshJson.encode(body)) }
        relay?.let { r ->
            val w = WebRtcBlobTransport(app, selfId, signalSend, scope, turn = { r.turn.value?.let { TurnConfig.from(it) } },
                fallback = { to, bytes -> r.send(RelayTransport.endpointOf(to), bytes) })
            webrtc = w
            transports[DataChannelTransport.NAME] = w
            alwaysOn += DataChannelTransport.NAME
        }
        // plan consent (spec 7.6, 17): the biometric key is created once a fingerprint is enrolled; the notifier wakes the owner
        val approvalKeys = if (Features.meshTrust) ApprovalKeys(app) else null
        val approvalNotifier = if (Features.meshTrust) runCatching { ApprovalNotifier(app) }.getOrElse { warn("approval notifier: ${it.message}"); null } else null
        if (approvalKeys != null) scope.launch(Dispatchers.IO) { if (approvalKeys.ensure() == null) warn("plan approvals need a fingerprint enrolled on this phone") }
        val approval = ApprovalStore(approvalKeys, signer, approvalNotifier, debugAutoApprove = { BuildConfig.DEBUG && prefs.getBoolean("debug_auto_approve", false) }, clock = clock)
        val initial = prefs.getString(PREF_TRANSPORT, null)?.takeIf { it in transports } ?: transports.keys.first()
        val localHello = {
            val s = runCatching { store.schema() }.getOrNull()
            val caps = ArrayList<String>().apply { add("sql"); add("docs"); if (approval.publicKeyB64 != null) add("approval"); add(when (llm.backend.value) { LlmBackend.NPU -> "npu"; LlmBackend.GPU -> "gpu"; else -> "cpu" }) }
            HelloBody("", selfName(), "", approvalPubkey = approval.publicKeyB64, holds = s?.let { it.tables.map { t -> t.name } + it.docs.map { d -> d.name } } ?: emptyList(),
                caps = caps, vocab = s?.let { Relevance.vocabulary(it) }, catalog = s?.let { Catalog.of(selfId, selfName(), it, dataPort.hiddenTables() + store.privateTables()) })
        }
        router = MeshRouter(transports, signer, peerStore, localHello, scope, initialTransport = initial,
            debugAllowNodeSignedApprovals = BuildConfig.DEBUG, now = clock, replayGuard = replayGuard,
            rosterStore = if (Features.meshTrust) rosterStore else null,
            onAuthFailure = { name, reason -> warn("$name: $reason") }, alwaysOn = alwaysOn)
        val port = MeshRouterAdapter(router)
        scope.launch { router.transportName.collect { if (it in transports) prefs.putString(PREF_TRANSPORT, it) } }

        // link probes (spec 5.6): measured links per transport with the metered flag, read by the beacon's linkOf
        val cm = runCatching { app.getSystemService(ConnectivityManager::class.java) }.getOrNull()
        val metered = { runCatching { cm?.isActiveNetworkMetered == true }.getOrDefault(false) }
        val measured = ConcurrentHashMap<String, com.hivecore.core.Link>()
        val probes = transports.map { (name, t) ->
            LinkProbe(t, scope, metered, onLink = { ep, link -> measured["$name/${ep.endpointId}"] = link }).also { it.start() }
        }
        val linkOf: (String) -> com.hivecore.core.Link? = { id ->
            router.endpointOf(id)?.let { ep -> measured["${ep.transport}/${ep.endpointId}"] ?: router.linkTo(id) }
        }

        // ---- trust: pairing, roster admin, task authorisation (P10) ----
        val admin = RosterAdmin(signer, rosterStore, clock)
        val authoriser = if (Features.meshTrust) PlanAuthoriser(db, clock) else null
        val guestSecure = ConcurrentHashMap<String, SecureTransport>()
        val guestRelays = ConcurrentHashMap<String, RelayTransport>()
        lateinit var pairing: PairingImpl
        val remoteLeg: (suspend (Invite) -> PeerEndpoint?)? = if (relay != null || Features.remote) { invite ->
            val url = relayUrl()
            if (url == null) { warn("no relay address: the invite link needs one"); null } else {
                val guest = RelayPairingChannel.joiner(url, signer, invite.groupId, invite.pubkey, invite.token, scope)
                val secure = SecureTransport(guest, signer, { peerStore.get(it)?.pubkey }, clock, scope,
                    allowUnpinned = RelayPairingChannel.allowOnly(invite.pubkey))
                secure.addListener(pairing)
                val inviter = RelayPairingChannel.inviterNodeId(invite.pubkey)
                guestSecure[GUEST] = secure; guestRelays[inviter] = guest
                secure.start("${selfName()}|${selfId.take(8)}")
                val ok = withTimeoutOrNull(15_000) { guest.authenticated.first { it } } == true
                if (!ok) { warn("the relay did not accept the invite"); secure.stop(); guestSecure.remove(GUEST); null }
                else RelayPairingChannel.inviterEndpoint(invite.pubkey).copy(transport = GUEST)
            }
        } else null
        pairing = PairingImpl(router, signer, peerStore, selfName, locate = { inviter, _ ->
            val l = lan ?: return@PairingImpl null
            // Phone gate finding: a link left half-open by the unpaired churn swallows the handshake, so every link to
            // the inviter is dropped and a fresh one dialled before the INIT goes out (discovery and TCP take seconds).
            l.connected.value.filter { it.endpointId.startsWith(inviter.take(8)) }.forEach { l.disconnect(it) }
            l.dialNow(inviter)
            var ep: PeerEndpoint? = null
            for (i in 1..16) {
                delay(500)
                ep = l.connected.value.firstOrNull { it.endpointId.startsWith(inviter.take(8)) }
                if (ep != null) break
                if (i % 4 == 0) l.dialNow(inviter)
            }
            ep
        }, scope = scope, clock = clock, remote = remoteLeg, guestSecure = { guestSecure[it] })
        scope.launch {
            pairing.events.collect { e ->
                val peer = when (e) { is PairingEvent.Paired -> e.nodeId; is PairingEvent.Aborted -> e.peerNodeId }
                guestRelays.remove(peer)?.let { g -> guestSecure.remove(GUEST)?.let { runCatching { it.stop() } } ?: runCatching { g.stop() } }
                if (e is PairingEvent.Aborted) warn("pairing with ${e.peerNodeId?.take(8) ?: "a phone"} aborted: ${e.reason}")
            }
        }

        // ---- cloud (phase 8): the orchestrator's factories only ----
        var keyStore: CloudKeyStore? = null
        var escalator: Escalator = Escalator.Off
        var t2: T2Planner? = null
        var prices: () -> CloudPrices? = { null }
        var consent: CloudConsent? = null
        val stateDb = if (Features.split) AndroidSqlDb.open(File(app.filesDir, "journal.db")) else null
        val memo: ChunkMemo = stateDb?.let { SqliteChunkMemo(it, clock) } ?: ChunkMemo.None
        // spec 13.4 (state flag): the orchestrator streams every journal commit to a deputy (highest uptime, LAN
        // preferred; uptime = how long this phone has seen it) and the job row names it so a resume pulls from it first.
        val beaconRef = AtomicReference<ProfileBeacon?>(null)
        val firstSeen = ConcurrentHashMap<String, Long>()
        val deputyOf: () -> String? = {
            val ps = beaconRef.get()?.snapshot().orEmpty()
            val now = clock()
            ps.forEach { firstSeen.putIfAbsent(it.nodeId, now) }
            DeputySelector.pick(ps, ps.associate { it.nodeId to (now - (firstSeen[it.nodeId] ?: now)) }, selfId)
        }
        val baseJournal: ReplicatedJournal? = stateDb?.let { SqliteJobJournal(it) }
        val journal: JobJournal = when {
            baseJournal == null -> JobJournal.None
            Features.state -> StreamingJournal(baseJournal, selfId, deputyOf, { to, t, b -> port.send(to, t, b) }, scope)
            else -> baseJournal
        }
        val compiler = PlanCompilerImpl(ops = { registeredOps.value }, capabilities = { catalog.capabilities + runtimeCaps(router, selfId) }, selfId = selfId)
        if (Features.cloud) {
            runCatching {
                val config = ModelsConfig.load(app.assets)
                val keys = EncryptedCloudKeyStore(app)
                keyStore = keys
                val caps = SpendCaps(PrefsSpendStore(prefs), config, clock)
                val sheet = CloudConsent()
                consent = sheet
                escalator = CloudFactory.gate(selfId, keys, config, caps, sheet, CloudFactory.PrefsConsentStore(prefs), ledger, journal.takeIf { it !== JobJournal.None }, memo,
                    triggers = { TriggerSettings(timeBudget = prefs.getBoolean(PREF_TIME_BUDGET_TRIGGER, false)) }, clock = clock)
                t2 = CloudFactory.planner(keys, config, compiler, caps, selfId, schemaSharing = { prefs.getBoolean(SettingsKeys.SCHEMA_SHARING, false) }, clock = clock)
                prices = { if (keys.present) config.cloudPrices() else null }
            }.onFailure { warn("cloud: ${it.message}") }
        }

        // ---- the swarm node (phases 3-5, 7) ----
        val receipts = ReceiptExchange(router, ledger, clock)
        val coordinators = ConcurrentHashMap<String, PlacementCoordinator>()
        val templates = runCatching { TemplateMatcher.load(app) }.getOrElse { warn("templates: ${it.message}"); TemplateMatcher.empty() }
        val intentPlanner = LlmIntentPlanner(llm, templates, { role -> runCatching { FewShots.load(app, role) }.getOrDefault(emptyList()) }, clock = clock)
        val patterns = runCatching { Patterns.fromJson(app.assets.open(Patterns.ASSET_PATH).bufferedReader().readText()) }
            .getOrElse { warn("patterns: ${it.message}"); null }
        val chooser = ClosedChooserImpl(llm)
        // OCR (spec 4.3 `ocr`, catalog `mlkit_text_latin`): ML Kit's bundled Latin recogniser, the document executors' seam
        val ocr: com.hivecore.core.Ocr? = if (Features.runtime) runCatching { com.hivecore.node.docai.MlKitOcr(app) }.getOrElse { warn("ocr: ${it.message}"); null } else null
        lateinit var graph: SwarmGraph
        val policyFactory: (TaskRunnerImpl) -> WorkerPolicy = { runner ->
            val d = device; val pm = presence
            if (Features.runtime && d != null && pm != null) {
                WorkerPolicyImpl(selfId, d, pm.presence, llm, prefs, runner.queueDepth, scope, pool = pool, registry = registry,
                    qairt = "2.50.0", onTick = pm::refresh, clock = clock, catalog = { catalog }, cooldowns = cooldowns, bench = { benchTable.p50(catalog) },
                    extraInstalled = { if (llm.backend.value != null) setOf(ModelPoolImpl.LLM_QAIRT_ID) else emptySet() }).also { p ->
                    OwnerYield(runner::yieldAll, runner::resumeWorkers, clock).hook(pm, scope)
                    WorkerModeController(WorkerService.control(app), clock).bind(p.optIn, scope)
                }
            } else FakeWorkerPolicy(Profile(selfId, clock(), llm.backend.value, idle = true, charging = true, batteryPct = 100, eligible = false, big = "none"))
        }
        val deps = SwarmGraph.Deps(
            router = port, selfName = selfName(), scope = scope, db = db, schema = store, intentPlanner = intentPlanner, approval = approval,
            llm = llm, patterns = patterns ?: Patterns.fromJson("""{"version":"none","patterns":[]}"""), policy = policyFactory,
            executors = { env ->
                val p = pool as? ModelPoolImpl
                // VLM_ASK (spec 4.3 `vlm`): the catalog's preferred VLM build through the pool, a closed choice voted k times
                val vlmAsk: (suspend (String, String, List<String>) -> Decision<String>)? = p?.let { pl -> { image, question, options ->
                    val entry = catalog.preferred("vlm") ?: throw IllegalStateException("no vlm build in the catalog")
                    SwarmExecutors.vlmVote(question, options) { order ->
                        pl.with(entry.modelId) { s ->
                            val rt = pl.runtimeFor(s.manifest) ?: throw IllegalStateException("no runtime loads ${s.manifest.id}")
                            rt.run(s, mapOf("image" to image, "question" to question, "options" to order)).getOrThrow()["choice"]?.toString()?.takeIf { it.isNotEmpty() }
                        }.getOrThrow()
                    }
                } }
                // GEN_IMAGE (spec 4.3 `gen.image`): the SD 1.5 bundle on the ORT QNN runtime
                val sd = if (p != null && ort != null) SdPipeline(p, ort!!, SdPipeline.bpeFrom(registry)) else null
                val seams = SwarmExecutors.Seams(pool = pool, registry = registry, tensorRuntime = litert, ortRuntime = ort,
                    codec = ImageIo(), outDir = File(app.filesDir, "out"), vlmAsk = vlmAsk, ocr = ocr,
                    audioDecoder = AndroidAudioDecoder(), frames = AndroidFrameSource(),
                    imageGenerator = sd?.let { pipe -> ImageGenerator { params -> pipe.generate(params) } },
                    bench = p?.let { pl -> Bench(registry, pl, pl::runtimeFor, batteryTempC = { device?.batteryTempC?.value }) })
                SwarmExecutors.build(env, seams) + LlmMapExecutor(llm)
            },
            chooser = chooser, ledger = ledger, docIndex = index, docAnswerer = LlmDocAnswerer(llm),
            blobDir = File(app.filesDir, "blobs"), nameOf = { id -> router.peerName(id) ?: id.take(8) },
            p50 = { benchTable.p50(catalog) }, hidden = { dataPort.hiddenTables() },
            peerCatalogs = { router.pinnedNodeIds().mapNotNull { router.lastHello(it)?.catalog } },
            recipeDefs = recipes, clock = clock,
            privateTables = { store.privateTables() }, privateDocs = { store.privateDocs() },
            journal = journal, memo = memo, escalator = escalator,
            readyFor = { cap -> coordinators.values.flatMap { it.ready(cap) }.toSet().takeIf { it.isNotEmpty() } },
            unitMsOf = { node, cap -> catalog.preferred(cap)?.let { e -> graph.node.beacon?.profileOf(node)?.p50?.get(e.modelId) ?: e.unitMsP50.toDouble() } },
            allowMobileData = { prefs.getBoolean(SettingsKeys.MOBILE_DATA_BLOBS, false) },
            onCooldown = { n, c, u -> cooldowns.mark(n, c, u) }, linkOf = linkOf,
            journalPull = if (stateDb != null) JournalPull(port, journal as ReplicatedJournal).also { it.start(scope) } else null,
            deputy = if (Features.state) deputyOf else { { null } },
            ensureAwake = { id -> relayPresence?.let { p -> if (router.endpointOf(id)?.transport == RelayTransport.NAME) p.ensureAwake(id) else true } ?: true },
            wrapRunner = { r -> if (memo !== ChunkMemo.None) MemoizingTaskRunner(r, memo) else r },
        )
        graph = SwarmGraph.build(deps)
        val node = graph.node
        beaconRef.set(node.beacon)
        registeredOps.value = graph.executors.flatMap { it.ops }.toSet()
        if (authoriser != null) {
            node.onPlanSeen = authoriser::onPlanSeen
            node.taskAuthoriser = authoriser::authorise
            authoriser.onWarning = { peer, n -> warn("${router.peerName(peer) ?: peer.take(8)} sent $n unauthorised tasks in a minute") }
        }
        if (Features.runtime && pool is CapabilityPool) node.onModelReq = ModelReqHandler(selfId, { catalog }, pool, scope, cooldowns) { graph.policy.optIn.value }::handle
        node.onModelReply = { t, from, body -> coordinators.values.forEach { pc -> scope.launch { pc.onMessage(t, from, body) } } }
        if (Features.runtime && Features.split) node.placeFor = { job, planId -> placeJob(job, planId, selfId, catalog, cooldowns, node, journal, coordinators, scope) }
        node.onSignal = { body, _ -> webrtc?.let { w -> scope.launch { w.handleSignal(body) } } }
        relay?.onPresence { id, online, _ -> if (!online) { node.beacon?.forget(id); firstSeen.remove(id) } }
        if (stateDb != null) {
            val deputy = DeputyStore(stateDb, port, memo, clock).also { it.start(scope) }
            node.reclaim?.extra = deputy::holding
            (node.runner as? MemoizingTaskRunner)?.fallback = deputy::lookup
            receipts.start(scope)
            node.onSplitDone = { p ->
                coordinators.remove(p.jobId)
                if (p.done) scope.launch {
                    runCatching {
                        val snap = journal.load(p.jobId) ?: return@launch
                        val merge = p.resultHash?.let { h -> runCatching { MergeOutcome(MergeKind.valueOf(p.kind), h) }.getOrNull() }
                        receipts.afterSplitOrchestrator(snap, merge, clock())
                    }.onFailure { SLog.w(TAG, "receipt after ${p.jobId}: ${it.message}") }
                }
            }
            node.onTaskRan = { body, from, _, hash ->
                val chunkId = (body.task.params[ChunkPlanner.CHUNK_ID_PARAM] as? JsonPrimitive)?.content
                val parsed = WorkQueue.parseTaskId(body.task.id)
                if (chunkId != null && parsed != null && body.planId.contains('#')) scope.launch {
                    val jobId = body.planId.substringBeforeLast('#')
                    runCatching { receipts.afterSplitExecutor(jobId, from, listOf(TaskEntry(jobId, body.planId, chunkId, parsed.first,
                        executors = listOf(Executor(selfId, parsed.second, "ok", hash)), outputHash = hash, source = "node:$selfId", verifiedBy = emptyList()))) }
                        .onFailure { SLog.w(TAG, "task receipt: ${it.message}") }
                }
            }
        }
        pairing.onPaired = { id, pubkey, name, g ->
            swarmPortRef?.onPaired(id, pubkey, name, g)
            relay?.let { r -> rosterStore.current(g)?.let { body -> scope.launch { r.uploadRoster(body).onFailure { warn("roster upload: ${it.message}") } } } }
        }

        // ---- keepalive: the worker service holds the multicast lock (NSD) and keeps the router (and so the relay
        // socket, an always-on transport of the router) running while the owner opted in ----
        val wifi = runCatching { app.getSystemService(WifiManager::class.java) }.getOrNull()
        val routerUp = MutableStateFlow(false)
        // mDNS replies are multicast and the Wi-Fi driver drops them unless an app holds the multicast lock (phone
        // gate finding: without it a phone registers but never finds the others), so the lock is held from the
        // moment the LAN transport runs, not only while the worker service is on.
        var discoveryLock: WifiManager.MulticastLock? = null
        suspend fun startRouter() {
            if (routerUp.value) return
            if (Features.meshTrust && discoveryLock == null)
                discoveryLock = runCatching { wifi?.createMulticastLock("hivecore-discovery")?.apply { setReferenceCounted(false); acquire() } }.getOrNull()
            runCatching { router.start(); routerUp.value = true }.onFailure { warn("mesh start failed: ${it.message}") }
        }
        WorkerService.keepalive = object : WorkerService.Keepalive {
            private var lock: WifiManager.MulticastLock? = null
            override fun start() {
                lock = runCatching { wifi?.createMulticastLock("hivecore-nsd")?.apply { setReferenceCounted(false); acquire() } }.getOrNull()
                scope.launch { startRouter() }
            }
            override fun stop() { runCatching { lock?.release() }; lock = null }
        }
        scope.launch { startRouter() }

        // ---- the ports ----
        val profiles = { node.beacon?.snapshot() ?: emptyList() }
        val nameOf = { id: String -> if (id == selfId) selfName() else router.peerName(id) ?: id.take(8) }
        val swarmPort = if (Features.meshTrust) SwarmPortImpl(selfId, selfName, router, peerStore, rosterStore, admin, pairing, { profiles().filter { it.nodeId != selfId } },
            groupId, scope, warnings, onInviteOpened = { inv -> relay?.let { r -> RelayPairingChannel.host(r, inv.token).onFailure { warn("relay invite: ${it.message}") } } }, clock)
            .also { swarmPortRef = it } else FakeSwarmPort.sample()
        val modelsPort = if (Features.runtime && capBench != null && pool is CapabilityPool)
            ModelsPortImpl(selfId, selfName(), { catalog }, pool, registry, capBench, benchTable, profiles, nameOf, prefs, scope,
                downloader = ModelDownloader(ModelRegistryImpl.defaultRoots(app)) { (registry as? ModelRegistryImpl)?.refresh() }) else FakeModelsPort.sample()
        val tiered = if (Features.graph && patterns != null) TieredPlannerImpl(selfId, SwarmRouterImpl(), PlannerImpl(chooser, patterns, clock), compiler, t2,
            t2First = { prefs.getBoolean(PREF_T2_FIRST, false) }, clock = clock, patterns = patterns) else null
        val askPort = if (tiered != null) AskPortImpl(selfId, selfName(), tiered, node, node, { graph.catalogs() },
            { CapabilitySummary(catalog.capabilities + runtimeCaps(router, selfId), registeredOps.value) }, profiles, { catalog.entries }, prices,
            { AskPortImpl.policyOf(prefs) }, { runCatching { cm?.activeNetwork != null }.getOrDefault(false) }, recipes, escalator, nameOf) else FakeAskPort()
        val settingsPort = if (Features.meshTrust || Features.cloud) SettingsPortImpl(keyStore, peerStore, router, ledger, File(app.filesDir, "exports"))
            else FakeSettingsPort()
        val jobRunner: JobRunner = if (Features.split) node else com.hivecore.core.fakes.FakeJobRunner()
        SLog.d(TAG, "wired in ${System.currentTimeMillis() - t0} ms: node ${selfId.take(8)} (${selfName()}), transports ${transports.keys} (always-on $alwaysOn), features ${Features.enabled()}")
        return HiveCoreGraph.Ports(selfId, askPort, jobRunner, swarmPort, graph.policy, modelsPort, registry, pool, store, store,
            if (Features.graph) dataPort else FakeDataPort(), settingsPort, consent, loadRecipes = recipes,
            approval = approval.takeIf { Features.meshTrust }, approvalKeys = approvalKeys, rebroadcastHello = { router.rebroadcastHello() })
    }

    private const val GUEST = "relay-guest"
    private val registeredOps = MutableStateFlow(com.hivecore.core.OpRegistry.entries.keys)
    @Volatile private var swarmPortRef: SwarmPortImpl? = null

    /** Capabilities any member advertises (spec 7.2 "swarm capability summary"), from the last profiles the router saw. */
    private fun runtimeCaps(router: MeshRouter, selfId: String): Set<String> = emptySet()

    /**
     * Spec 8.3 per job: solve the demand of the job's capability, send MODEL_REQs and wait until a phone is ready (or
     * nothing can be placed). Later READYs reach the live run through the shared planId; the coordinator stays in
     * [coordinators] until the job's last emission.
     */
    private suspend fun placeJob(job: SplitJob, planId: String, selfId: String, catalog: ModelCatalog, cooldowns: CooldownTable, node: SwarmNode,
                                 journal: JobJournal, coordinators: ConcurrentHashMap<String, PlacementCoordinator>, scope: CoroutineScope): Set<String>? {
        val cap = job.cap ?: return null
        if (catalog.forCapability(cap).isEmpty()) return null
        val profiles = node.beacon?.snapshot() ?: return null
        val units = ChunkPlanner().plan(job, profiles, selfId).getOrNull()?.chunks?.sumOf { it.units }?.coerceAtLeast(1) ?: 1
        val pc = PlacementCoordinator(job.jobId, planId, selfId, catalog, cooldowns, { node.beacon?.snapshot() ?: emptyList() },
            { to, t, b -> node.sendTo(to, t, b) }, journal = journal)
        val opened = CompletableDeferred<Unit>()
        pc.onOpen = { _, _ -> opened.complete(Unit) }
        pc.onGap = { c, why -> SLog.w(TAG, "placement gap for $c: $why"); opened.complete(Unit) }
        coordinators[job.jobId] = pc
        val out = pc.request(mapOf(cap to units))
        if (out.assignments.isEmpty()) { coordinators.remove(job.jobId); return null }
        pc.watch(scope)
        withTimeoutOrNull(out.assignments.maxOf { a -> catalog.entry(a.modelId, cap)?.let { PlacementCoordinator.deadlineMs(it) } ?: PlacementCoordinator.MIN_DEADLINE_MS } + 1_000) { opened.await() }
        return pc.ready(cap).takeIf { it.isNotEmpty() }
    }
}
