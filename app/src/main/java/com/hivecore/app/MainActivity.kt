package com.hivecore.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.hivecore.ui.HiveCoreShell
import com.hivecore.ui.ScreenRegistry

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = HiveCoreGraph.get(this)
        setContent { HiveCoreShell(registry = ScreenRegistry(Features.screens(graph))) }
    }
}
