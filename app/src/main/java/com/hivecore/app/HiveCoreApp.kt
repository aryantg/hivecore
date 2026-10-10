package com.hivecore.app

import android.app.Application
import com.hivecore.core.SLog

class HiveCoreApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SLog.init(this)
        SLog.d("App", "HiveCore starting; features: ${Features.enabled().joinToString().ifEmpty { "none" }}")
        if (com.hivecore.BuildConfig.DEMO_THERMAL) {
            com.hivecore.engine.device.Eligibility.relaxForDemo()
            SLog.w("App", "demo build: relaxed thermal limits (thermal 3 or less, battery under 45 °C)")
        }
        // Phone gate finding: the system killed the app with its screen off and START_STICKY brought back only the
        // worker service; the graph (and the mesh) was built by the activity alone, so the phone stayed offline.
        if (Features.meshTrust) HiveCoreGraph.get(this)
    }
}
