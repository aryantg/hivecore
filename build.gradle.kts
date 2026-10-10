// Top-level build file. Plugins are declared here; each module configures itself.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// ---------------------------------------------------------------------------------------------------------------
// Spec section 18.2, invariant P3: nothing that can run on a node may reach the cloud module. Two checks, both part
// of every build and of `check`:
//   1. the :node project (and every module below :app) must not declare a dependency on :cloud;
//   2. no source file outside :cloud and :app may mention the package com.hivecore.cloud.
// `cloud/models.json` may only ship from :cloud's assets.
val cloudFree = listOf("core", "mesh", "trust", "runtime", "engine", "planner", "node", "ledger", "ui")

val checkCloudBoundary by tasks.registering {
    group = "verification"
    description = "Fails when :node (or any module other than :app) depends on :cloud or references com.hivecore.cloud"
    val roots = cloudFree.map { layout.projectDirectory.dir("$it/src") }
    inputs.files(roots.map { fileTree(it) { include("**/*.kt", "**/*.java") } })
    doLast {
        val offenders = ArrayList<String>()
        for (m in cloudFree) {
            val p = project(":$m")
            p.configurations.filter { it.name.endsWith("Implementation", true) || it.name.endsWith("Api", true) || it.name == "api" || it.name == "implementation" }
                .forEach { c -> c.dependencies.withType(ProjectDependency::class.java).forEach { d -> if (d.name == "cloud") offenders += ":$m -> :cloud (configuration ${c.name})" } }
            val src = layout.projectDirectory.dir("$m/src").asFile
            if (src.exists()) src.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.forEach { f ->
                f.useLines { lines -> lines.forEachIndexed { i, l -> if (l.contains("com.hivecore.cloud")) offenders += "${f.relativeTo(rootDir).invariantSeparatorsPath}:${i + 1}: $l" } }
            }
            val assets = layout.projectDirectory.dir("$m/src/main/assets").asFile
            if (assets.exists()) assets.walkTopDown().filter { it.isFile && it.name == "models.json" }.forEach { offenders += "asset ${it.relativeTo(rootDir)}: models.json outside :cloud" }
        }
        if (offenders.isNotEmpty()) throw GradleException("P3 cloud boundary violated:\n  " + offenders.joinToString("\n  "))
    }
}

subprojects {
    tasks.matching { it.name == "preBuild" || it.name == "check" }.configureEach { dependsOn(checkCloudBoundary) }
}
