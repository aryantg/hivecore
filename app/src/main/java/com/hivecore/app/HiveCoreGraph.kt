package com.hivecore.app

import android.content.Context
import com.hivecore.core.DataAdmin
import com.hivecore.core.JobRunner
import com.hivecore.core.KeyValuePrefs
import com.hivecore.core.LlmBackend
import com.hivecore.core.MemoryPrefs
import com.hivecore.core.ModelPool
import com.hivecore.core.ModelRegistry
import com.hivecore.core.NodeRole
import com.hivecore.core.Profile
import com.hivecore.core.SLog
import com.hivecore.core.SchemaSource
import com.hivecore.core.SharedPrefsKv
import com.hivecore.core.WorkerPolicy
import com.hivecore.core.fakes.FakeDataAdmin
import com.hivecore.core.fakes.FakeJobRunner
import com.hivecore.core.fakes.FakeModelPool
import com.hivecore.core.fakes.FakeModelRegistry
import com.hivecore.core.fakes.FakeSchemaSource
import com.hivecore.core.fakes.FakeWorkerPolicy
import com.hivecore.engine.workload.RecipeDef
import com.hivecore.ui.ports.AskPort
import com.hivecore.ui.ports.DataPort
import com.hivecore.ui.ports.MemberView
import com.hivecore.ui.ports.ModelsPort
import com.hivecore.ui.ports.SettingsPort
import com.hivecore.ui.ports.SwarmPort
import com.hivecore.ui.ports.fakes.FakeAskPort
import com.hivecore.ui.ports.fakes.FakeDataPort
import com.hivecore.ui.ports.fakes.FakeModelsPort
import com.hivecore.ui.ports.fakes.FakeSettingsPort
import com.hivecore.ui.ports.fakes.FakeSwarmPort
import com.hivecore.ui.vm.AskViewModel
import com.hivecore.ui.vm.DataViewModel
import com.hivecore.ui.vm.JobsViewModel
import com.hivecore.ui.vm.ModelsViewModel
import com.hivecore.ui.vm.SettingsViewModel
import com.hivecore.ui.vm.SwarmViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The app's object graph for the six screens (spec section 17): one view model per screen over the [Ports]. On a
 * phone [AppWiring] builds the ports behind the feature flags (a phase that is off keeps its `:ui` fake, so the app
 * always runs); [inMemory] builds the same shape over the fakes for the JVM.
 */
class HiveCoreGraph(
    val prefs: KeyValuePrefs,
    val ports: Ports,
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    /** Everything the screens code against, real or fake per flag. */
    class Ports(
        val selfId: String,
        val askPort: AskPort,
        val jobRunner: JobRunner,
        val swarmPort: SwarmPort,
        val workerPolicy: WorkerPolicy?,
        val modelsPort: ModelsPort,
        val modelRegistry: ModelRegistry,
        val modelPool: ModelPool,
        val schema: SchemaSource,
        val dataAdmin: DataAdmin,
        val dataPort: DataPort,
        val settingsPort: SettingsPort,
        /** the cloud consent sheet's state (cloud flag), for the activity to host */
        val consent: CloudConsent? = null,
        val loadRecipes: () -> List<RecipeDef> = { emptyList() },
        /** the owner's plan consent gate (mesh flag): the approval activity and the in-app banner read its pending list */
        val approval: com.hivecore.trust.ApprovalStore? = null,
        /** the biometric approval key (mesh flag) the approval activity signs with */
        val approvalKeys: com.hivecore.trust.ApprovalKeys? = null,
        /** re-announces this phone's HELLO (a re-created approval key travels in it) */
        val rebroadcastHello: (suspend () -> Unit)? = null,
        /** the worker timeline (split flag): this phone's chunks for other phones, and its workers' mirrored for its own jobs */
        val workerActivity: com.hivecore.core.WorkerActivity? = null,
        /** the live activity feed (one per process); the shell's activity sheet reads it */
        val feed: com.hivecore.core.ActivityFeed = com.hivecore.core.ActivityFeed(),
    )

    val selfId: String get() = ports.selfId
    val askPort: AskPort get() = ports.askPort
    val jobRunner: JobRunner get() = ports.jobRunner
    val swarmPort: SwarmPort get() = ports.swarmPort
    val workerPolicy: WorkerPolicy? get() = ports.workerPolicy
    val modelsPort: ModelsPort get() = ports.modelsPort
    val modelRegistry: ModelRegistry get() = ports.modelRegistry
    val modelPool: ModelPool get() = ports.modelPool
    val schema: SchemaSource get() = ports.schema
    val dataAdmin: DataAdmin get() = ports.dataAdmin
    val dataPort: DataPort get() = ports.dataPort
    val settingsPort: SettingsPort get() = ports.settingsPort
    val consent: CloudConsent? get() = ports.consent
    val feed: com.hivecore.core.ActivityFeed get() = ports.feed

    val ask: AskViewModel by lazy { AskViewModel(askPort, prefs, scope) }
    val jobs: JobsViewModel by lazy { JobsViewModel(jobRunner, swarmPort.peers, ports.loadRecipes, scope, resultHash = { p -> p.resultRef?.removePrefix("split:") },
        activity = ports.workerActivity, selfId = selfId, nameOf = { id -> swarmPort.roster.value.nameOf(id) }) }
    val swarm: SwarmViewModel by lazy { SwarmViewModel(swarmPort, workerPolicy, scope) }
    val models: ModelsViewModel by lazy { ModelsViewModel(modelsPort, modelRegistry, modelPool, selfId, scope) }
    val data: DataViewModel by lazy { DataViewModel(schema, dataAdmin, dataPort, scope) }
    val settings: SettingsViewModel by lazy { SettingsViewModel(prefs, settingsPort, scope) }

    companion object {
        @Volatile private var instance: HiveCoreGraph? = null

        /** The process-wide graph (built on first use; prefs come from SharedPreferences "hivecore"). */
        fun get(context: Context): HiveCoreGraph = instance ?: synchronized(this) {
            instance ?: build(context).also { instance = it }
        }

        private fun build(context: Context): HiveCoreGraph {
            val app = context.applicationContext
            val prefs = SharedPrefsKv(app)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            SLog.d("Graph", "building the graph; features: ${Features.enabled().ifEmpty { listOf("none") }}")
            val ports = try { AppWiring.build(app, prefs, scope) } catch (e: Exception) {
                SLog.e("Graph", "wiring failed; the screens run over the fakes", e)
                fakePorts(Features.enabled())
            }
            return HiveCoreGraph(prefs, ports, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)).also { g ->
                com.hivecore.core.Feed.current = g.feed
                g.feed.attachWarnings()
                runCatching { com.hivecore.app.demo.FeedBridges.start(g, g.feed, scope) }.onFailure { SLog.e("Graph", "feed bridges failed", it) }
            }
        }

        /** The fake ports, the shape every flag set keeps on the JVM (and the phone's fallback when wiring fails). */
        fun fakePorts(flags: Collection<String> = emptyList()): Ports {
            val selfId = "self"
            val registry = FakeModelRegistry()
            return Ports(selfId, FakeAskPort(), FakeJobRunner(), FakeSwarmPort.sample(),
                FakeWorkerPolicy(Profile(selfId, System.currentTimeMillis(), LlmBackend.NPU, 17.0, idle = false, batteryPct = 80, batteryTempC = 30f, thermal = 0, big = "none")),
                FakeModelsPort.sample(), registry, FakeModelPool(registry), FakeSchemaSource(NodeRole.SALES), FakeDataAdmin(), FakeDataPort(),
                FakeSettingsPort(listOf(MemberView("sales", "Sales"), MemberView("accounts", "Accounts"))),
                consent = if ("cloud" in flags) CloudConsent() else null)
        }

        /** JVM tests and previews: in-memory prefs, the fake ports for [flags], no assets. */
        fun inMemory(scope: CoroutineScope, flags: Collection<String> = emptyList()): HiveCoreGraph = HiveCoreGraph(MemoryPrefs(), fakePorts(flags), scope)
    }
}
