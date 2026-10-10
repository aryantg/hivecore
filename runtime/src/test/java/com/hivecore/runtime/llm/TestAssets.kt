package com.hivecore.runtime.llm

import com.hivecore.core.NodeRole
import java.io.File

/** Reads the real asset files from `src/main/assets` (Gradle runs unit tests with the module dir as cwd). */
object TestAssets {
    private val roots: List<File> = listOf(File("src/main/assets"), File("../node/src/main/assets"), File("../runtime/src/main/assets"), File("../planner/src/main/assets"), File("../engine/src/main/assets")).filter { it.isDirectory }
    private fun root(): File = roots.firstOrNull() ?: error("assets folder not found from ${File(".").absolutePath}")
    /** The first module asset root that holds [path]; the assets merge into one tree in the APK. */
    private fun find(path: String): File = roots.map { File(it, path) }.firstOrNull { it.exists() } ?: File(root(), path)

    fun read(path: String): String = find(path).readText()
    fun templates(): TemplateMatcher = TemplateMatcher.fromJson(read(TemplateMatcher.ASSET_PATH))
    fun fewShots(role: NodeRole): List<FewShot> = FewShots.parse(read(FewShots.assetPath(role)))
}
