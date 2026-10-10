package com.hivecore.app

import android.content.Context
import com.hivecore.core.KeyValuePrefs
import com.hivecore.core.LlmBackend
import com.hivecore.core.MemoryPrefs
import com.hivecore.core.NodeRole
import com.hivecore.core.Profile
import com.hivecore.core.SLog
import com.hivecore.core.SharedPrefsKv
import com.hivecore.core.fakes.FakeDataAdmin
import com.hivecore.core.fakes.FakeJobRunner
import com.hivecore.core.fakes.FakeModelPool
import com.hivecore.core.fakes.FakeModelRegistry
import com.hivecore.core.fakes.FakeSchemaSource
import com.hivecore.core.fakes.FakeWorkerPolicy
import com.hivecore.engine.workload.RecipeDef
import com.hivecore.engine.workload.Recipes
import com.hivecore.ui.ports.MemberView
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
 * The app's object graph for the six screens (spec section 17): one view model per screen, built over the port
 * fakes so the app always runs. The integration wave swaps each fake for the real seam (`SwarmGraph`'s JobRunner,
 * the trust track's SwarmPort, the runtime track's ModelsPort, the state track's DataPort, the cloud track's
 * SettingsPort) without touching the screens. Nothing here starts the engine.
 */
class HiveCoreGraph(
    val prefs: KeyValuePrefs,
    private val loadRecipes: () -> List<RecipeDef>,
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    val selfId = "self"
    val askPort = FakeAskPort()
    val jobRunner = FakeJobRunner()
    val swarmPort = FakeSwarmPort.sample()
    val workerPolicy = FakeWorkerPolicy(Profile(selfId, System.currentTimeMillis(), LlmBackend.NPU, 17.0, idle = false, batteryPct = 80, batteryTempC = 30f, thermal = 0, big = "none"))
    val modelsPort = FakeModelsPort.sample()
    val modelRegistry = FakeModelRegistry()
    val modelPool = FakeModelPool(modelRegistry)
    val schema = FakeSchemaSource(NodeRole.SALES)
    val dataAdmin = FakeDataAdmin()
    val dataPort = FakeDataPort()
    val settingsPort = FakeSettingsPort(listOf(MemberView("sales", "Sales"), MemberView("accounts", "Accounts")))

    val ask: AskViewModel by lazy { AskViewModel(askPort, prefs, scope) }
    val jobs: JobsViewModel by lazy { JobsViewModel(jobRunner, swarmPort.peers, loadRecipes, scope) }
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
            SLog.d("Graph", "building the UI graph over the port fakes")
            return HiveCoreGraph(SharedPrefsKv(app), { Recipes.fromAssets(app.assets::list) { app.assets.open(it) } })
        }

        /** JVM tests and previews: in-memory prefs, no assets. */
        fun inMemory(scope: CoroutineScope): HiveCoreGraph = HiveCoreGraph(MemoryPrefs(), { emptyList() }, scope)
    }
}
