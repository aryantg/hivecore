package com.hivecore.app.demo

import android.content.Context
import com.hivecore.BuildConfig
import com.hivecore.app.CloudConsent
import com.hivecore.app.HiveCoreGraph
import com.hivecore.app.approval.ApprovalNotifier
import com.hivecore.core.ApprovalResult
import com.hivecore.core.FeedKind
import com.hivecore.core.KeyValuePrefs
import com.hivecore.core.LlmBackend
import com.hivecore.core.NodeRole
import com.hivecore.core.Profile
import com.hivecore.core.SLog
import com.hivecore.core.fakes.FakeDataAdmin
import com.hivecore.core.fakes.FakeModelPool
import com.hivecore.core.fakes.FakeModelRegistry
import com.hivecore.core.fakes.FakeSchemaSource
import com.hivecore.core.fakes.FakeWorkerPolicy
import com.hivecore.engine.workload.Recipes
import com.hivecore.trust.ApprovalKeys
import com.hivecore.trust.ApprovalStore
import com.hivecore.trust.KeystoreSigner
import com.hivecore.ui.demo.DemoAskPort
import com.hivecore.ui.demo.DemoJobRunner
import com.hivecore.ui.demo.DemoSwarm
import com.hivecore.ui.ports.DemoKeys
import com.hivecore.ui.ports.MemberView
import com.hivecore.ui.ports.fakes.FakeDataPort
import com.hivecore.ui.ports.fakes.FakeModelsPort
import com.hivecore.ui.ports.fakes.FakeSettingsPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Demo mode (Settings > Demo): the whole app over three scripted phones on this device, for rehearsals or when the
 * venue's Wi-Fi fails. No mesh, no router, no worker service. The approval screen and the consent sheet stay real so
 * the fingerprint moment can still be shown; the activity feed narrates the scripted work like the real one.
 */
object DemoWiring {
    fun build(app: Context, prefs: KeyValuePrefs, scope: CoroutineScope): HiveCoreGraph.Ports {
        val speed = { prefs.getInt(DemoKeys.DEMO_SPEED_X10, 10) / 10.0 }
        val now = System.currentTimeMillis()
        val selfId = "self"
        val keys = runCatching { ApprovalKeys(app) }.getOrNull()
        if (keys != null) scope.launch(Dispatchers.IO) { runCatching { keys.ensure() } }
        val approval = ApprovalStore(keys, runCatching { KeystoreSigner() }.getOrNull(), runCatching { ApprovalNotifier(app) }.getOrNull(),
            debugAutoApprove = { BuildConfig.DEBUG && prefs.getBoolean(DemoKeys.DEBUG_AUTO_APPROVE, false) })
        val ask = DemoAskPort(speed) {
            val r = approval.requestPlan("demo-plan-${System.currentTimeMillis()}", "Sales", DemoPortImpl.APPROVAL_DESCRIPTION, "7e1d0a90c44b2f81", listOf("h1", "h2", "h3"))
            r is ApprovalResult.Approved
        }
        val registry = FakeModelRegistry()
        val role = NodeRole.entries.firstOrNull { it.name == prefs.getString(DemoKeys.NODE_ROLE, null) } ?: NodeRole.SALES
        SLog.d("DemoWiring", "demo mode: scripted swarm, speed ${speed()}x")
        return HiveCoreGraph.Ports(
            selfId, ask, DemoJobRunner(speed), DemoSwarm.port(scope),
            FakeWorkerPolicy(Profile(selfId, now, LlmBackend.NPU, 17.0, idle = true, charging = true, batteryPct = 82, batteryTempC = 30f, thermal = 0,
                workerOptIn = true, eligible = true, heavyOk = true, big = "llm")),
            FakeModelsPort.sample(now), registry, FakeModelPool(registry), FakeSchemaSource(role), FakeDataAdmin(), FakeDataPort(),
            FakeSettingsPort(listOf(MemberView("sales", "Sales"), MemberView("accounts", "Accounts"))),
            consent = CloudConsent(),
            loadRecipes = { Recipes.fromAssets(app.assets::list) { app.assets.open(it) } },
            approval = approval, approvalKeys = keys,
            demoPort = DemoPortImpl(app, prefs, store = null, db = null, stateDb = null, debugBuild = BuildConfig.DEBUG, needsAllFiles = false) { HiveCoreGraph.get(app) },
        ).also { it.feed.post(FeedKind.DEMO, "Demo mode: three scripted phones on this device") }
    }
}
