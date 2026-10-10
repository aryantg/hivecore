package com.hivecore.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The network boundary of docs/plans/decision-chunking.md §1 as a unit test (the root build's `checkNetworkBoundary`
 * task is the gate; this makes the rule visible to `testDebugUnitTest`): no module other than :mesh (and the named
 * exceptions) imports an HTTP or socket API in its main sources.
 */
class NetworkBoundaryTest {
    private val modules = listOf("core", "trust", "runtime", "engine", "planner", "node", "ledger", "ui")
    private val imports = listOf("okhttp3.", "java.net.http.", "java.net.HttpURLConnection", "java.net.Socket", "java.net.ServerSocket",
        "java.net.DatagramSocket", "javax.net.ssl.", "io.ktor.", "retrofit2.", "io.grpc.")
    private val allowlist = setOf("runtime/src/main/java/com/hivecore/runtime/models/ModelDownloader.kt")

    @Test fun onlyMeshAndTheNamedExceptionsImportNetworkApis() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
        val offenders = ArrayList<String>()
        for (m in modules) {
            val src = File(root, "$m/src/main")
            if (!src.exists()) continue
            src.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.forEach { f ->
                val rel = f.relativeTo(root).invariantSeparatorsPath
                if (rel in allowlist) return@forEach
                f.useLines { lines -> lines.forEachIndexed { i, l ->
                    val t = l.trim()
                    if (t.startsWith("import ") && imports.any { t.removePrefix("import ").startsWith(it) }) offenders += "$rel:${i + 1}: $t"
                } }
            }
        }
        assertTrue("network APIs outside :mesh:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }
}
