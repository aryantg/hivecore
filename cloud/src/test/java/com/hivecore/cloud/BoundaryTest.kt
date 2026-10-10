package com.hivecore.cloud

import com.hivecore.core.OpExecutor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.reflect.KVisibility
import kotlin.reflect.full.primaryConstructor

/**
 * Spec 18.2 build-enforced rules, the part a JVM test can see. The remaining rule, "the task runner's executor
 * registry contains no class from :cloud", belongs in `:app`, the only module that may depend on `:cloud`.
 */
class BoundaryTest {

    @Test fun openRouterClientConstructorIsInternal() {
        val ctor = OpenRouterClient::class.primaryConstructor!!
        assertEquals(KVisibility.INTERNAL, ctor.visibility)
        assertTrue(OpenRouterClient::class.constructors.all { it.visibility == KVisibility.INTERNAL })
    }

    @Test fun noClassInCloudImplementsOpExecutor() {
        val classes = cloudClasses()
        assertTrue("found no compiled :cloud classes", classes.size >= 10)
        val executors = classes.filter { OpExecutor::class.java.isAssignableFrom(it) }
        assertEquals("a cloud class must never run as a node executor: $executors", emptyList<Class<*>>(), executors)
    }

    @Test fun modelsJsonShipsOnlyFromCloud() {
        val here = File("src/main/assets/cloud/models.json")
        assertTrue(here.isFile)
        val others = File("..").listFiles()!!.filter { it.isDirectory && it.name != "cloud" && File(it, "src/main/assets").isDirectory }
            .flatMap { m -> File(m, "src/main/assets").walkTopDown().filter { it.isFile && it.name == "models.json" }.toList() }
        assertEquals(emptyList<File>(), others)
    }

    /** Every class of package `com.hivecore.cloud` on the test classpath (the module's compiled main classes). */
    private fun cloudClasses(): List<Class<*>> {
        val loader = OpExecutor::class.java.classLoader
        val pkg = "com/hivecore/cloud"
        val out = ArrayList<Class<*>>()
        fun add(name: String) {
            if (name.contains("Test") || name.contains("Fake")) return
            runCatching { Class.forName(name, false, loader) }.getOrNull()?.let { out += it }
        }
        for (url in loader.getResources(pkg).toList()) when (url.protocol) {
            "file" -> {
                val dir = File(url.toURI())
                dir.walkTopDown().filter { it.isFile && it.extension == "class" }.forEach { f ->
                    add("com.hivecore.cloud." + f.relativeTo(dir).invariantSeparatorsPath.removeSuffix(".class").replace('/', '.'))
                }
            }
            "jar" -> {
                val path = url.path.substringAfter("file:").substringBefore("!")
                java.util.jar.JarFile(File(java.net.URI("file:$path"))).use { jar ->
                    jar.entries().toList().filter { !it.isDirectory && it.name.startsWith("$pkg/") && it.name.endsWith(".class") }
                        .forEach { add(it.name.removeSuffix(".class").replace('/', '.')) }
                }
            }
        }
        return out
    }
}
