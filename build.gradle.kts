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

// ---------------------------------------------------------------------------------------------------------------
// Invariant P1 and docs/plans/decision-chunking.md §1: no phone in any role calls a hosted model, and the only network
// code lives in :mesh. Every module below :app other than :mesh and :cloud must declare no network library and import
// no HTTP or socket API in its main sources. Pre-existing exceptions, outside the checked set or allowlisted by path:
//   - :cloud (OpenRouter over okhttp): the orchestrator-only escalation gate and T2 planner, bounded by P3 above;
//   - :app (firebase-messaging): the relay's wake push, no model traffic;
//   - runtime/.../ModelDownloader.kt (JDK HttpURLConnection): model bundle downloads from the catalog's pinned urls.
// Anything new fails the build.
val networkFree = listOf("core", "trust", "runtime", "engine", "planner", "node", "ledger", "ui")
val networkLibraries = listOf("okhttp", "ktor", "retrofit", "grpc", "volley", "webrtc", "openai", "anthropic", "langchain")
val networkImports = listOf("okhttp3.", "java.net.http.", "java.net.HttpURLConnection", "java.net.Socket", "java.net.ServerSocket",
    "java.net.DatagramSocket", "javax.net.ssl.", "io.ktor.", "retrofit2.", "io.grpc.")
val networkAllowlist = setOf("runtime/src/main/java/com/hivecore/runtime/models/ModelDownloader.kt")

val checkNetworkBoundary by tasks.registering {
    group = "verification"
    description = "Fails when a module other than :mesh (and the named exceptions) declares a network dependency or imports a network API"
    val roots = networkFree.map { layout.projectDirectory.dir("$it/src/main") }
    inputs.files(roots.map { fileTree(it) { include("**/*.kt", "**/*.java") } })
    doLast {
        val offenders = ArrayList<String>()
        for (m in networkFree) {
            val p = project(":$m")
            p.configurations.filter { it.name.endsWith("Implementation", true) || it.name.endsWith("Api", true) || it.name == "api" || it.name == "implementation" }
                .filter { !it.name.startsWith("test", true) && !it.name.contains("UnitTest", true) && !it.name.contains("AndroidTest", true) }
                .forEach { c -> c.dependencies.forEach { d ->
                    val name = "${d.group ?: ""}:${d.name}".lowercase()
                    if (d !is ProjectDependency && networkLibraries.any { name.contains(it) }) offenders += ":$m -> $name (configuration ${c.name})"
                } }
            val src = layout.projectDirectory.dir("$m/src/main").asFile
            if (src.exists()) src.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.forEach { f ->
                val rel = f.relativeTo(rootDir).invariantSeparatorsPath
                if (rel in networkAllowlist) return@forEach
                f.useLines { lines -> lines.forEachIndexed { i, l ->
                    val t = l.trim()
                    if (t.startsWith("import ") && networkImports.any { t.removePrefix("import ").startsWith(it) }) offenders += "$rel:${i + 1}: $t"
                } }
            }
        }
        if (offenders.isNotEmpty()) throw GradleException("P1 network boundary violated (only :mesh may hold network code):\n  " + offenders.joinToString("\n  "))
    }
}

subprojects {
    tasks.matching { it.name == "preBuild" || it.name == "check" }.configureEach { dependsOn(checkCloudBoundary, checkNetworkBoundary) }
}
