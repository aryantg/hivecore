package com.hivecore.app

import android.app.Application
import com.hivecore.core.SLog

class HiveCoreApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SLog.init(this)
        SLog.d("App", "HiveCore starting; features: ${Features.enabled().joinToString().ifEmpty { "none" }}")
    }
}
