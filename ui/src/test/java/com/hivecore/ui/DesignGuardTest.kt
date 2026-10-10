package com.hivecore.ui

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The fixed template (docs/ui-template.md), enforced. Every UI source file in `:ui` and `:app` reads colours, text
 * sizes, shapes, sizes and icons from `com.hivecore.ui.design`; every screen uses `HiveScreen`, carries light and dark
 * previews, and every page but the chat Home opens with a capability header. Only the design package holds literals.
 */
class DesignGuardTest {
    private data class Rule(val regex: Regex, val message: String, val appOnly: Boolean = false)

    private val rules = listOf(
        Rule(Regex("""Color\(0x[0-9A-Fa-f]+\)"""), "raw colour; add a token to HivePalette / HiveColors"),
        Rule(Regex("""(?<![\w.])\d+(\.\d+)?\.sp\b"""), "raw sp; use MaterialTheme.typography or HiveTextStyles"),
        Rule(Regex("""RoundedCornerShape\(|CutCornerShape\(|\bCircleShape\b"""), "raw shape; use HiveShape"),
        Rule(Regex("""(?<![\w.])(?!0\.dp|1\.dp|2\.dp)\d+(\.\d+)?\.dp\b"""), "raw dp; use HiveSpacing"),
        Rule(Regex("""\bIcons\.(Filled|Outlined|Rounded|Sharp|TwoTone|Default)\."""), "library icon; use HiveIcons"),
        Rule(Regex("""\b(fontSize|fontFamily|letterSpacing)\s*="""), "ad-hoc text styling; use a HiveTextStyles style"),
        Rule(Regex("""(?<![\w.])(Card|ElevatedCard|OutlinedCard)\("""), "use HiveCard"),
        Rule(Regex("""lightColorScheme\(|darkColorScheme\(|(?<![\w.])MaterialTheme\("""), "only HiveCoreTheme builds a colour scheme"),
        Rule(Regex("""android\.R\.drawable\."""), "use the app's drawables", appOnly = true),
    )

    private val uiRoot = File("src/main/java")
    private val appRoot = File("../app/src/main/java")

    private fun sources(root: File): List<File> = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    private fun rel(f: File): String = f.invariantSeparatorsPath.substringAfter("/java/")
    private fun inDesign(f: File) = rel(f).startsWith("com/hivecore/ui/design/")

    /** Code lines with comments removed (a `//` only counts after whitespace, so `swarm://` in strings survives). */
    private fun codeLines(f: File): List<Pair<Int, String>> = f.readLines().mapIndexedNotNull { i, raw ->
        val t = raw.trim()
        if (t.startsWith("*") || t.startsWith("/*") || t.startsWith("//")) null
        else (i + 1) to raw.replace(Regex("""(^|\s)//.*$"""), "")
    }

    @Test fun bothSourceRootsExist() {
        assertTrue("ui sources at ${uiRoot.absolutePath}", uiRoot.isDirectory)
        assertTrue("app sources at ${appRoot.absolutePath}", appRoot.isDirectory)
    }

    @Test fun onlyTheDesignPackageHoldsLiterals() {
        val hits = mutableListOf<String>()
        for ((root, isApp) in listOf(uiRoot to false, appRoot to true)) for (f in sources(root)) {
            if (inDesign(f)) continue
            for ((n, line) in codeLines(f)) for (r in rules) {
                if (r.appOnly && !isApp) continue
                if (r.regex.containsMatchIn(line)) hits += "${rel(f)}:$n: ${r.message}\n    ${line.trim()}"
            }
        }
        if (hits.isNotEmpty()) fail("Design guard (docs/ui-template.md):\n" + hits.joinToString("\n"))
    }

    @Test fun everyScreenUsesTheTemplate() {
        val screens = sources(uiRoot).filter { rel(it).startsWith("com/hivecore/ui/screens/") && it.name.endsWith("Screen.kt") }
        assertTrue("found the screens", screens.size >= 6)
        val problems = screens.flatMap { f ->
            val text = f.readText()
            listOfNotNull(
                "${rel(f)}: does not use HiveScreen(".takeIf { "HiveScreen(" !in text },
                "${rel(f)}: has no @HivePreviews preview".takeIf { "@HivePreviews" !in text },
                "${rel(f)}: a capability page must pass capability = Capability(...)".takeIf { f.name != "AskScreen.kt" && "capability = Capability(" !in text },
            )
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    @Test fun oneThemeRootPerActivity() {
        val hits = (sources(uiRoot) + sources(appRoot)).filter { f ->
            !inDesign(f) && !f.name.endsWith("Activity.kt") && "@HivePreviews" !in f.readText() && codeLines(f).any { (_, l) -> Regex("""\bHiveCoreTheme\s*[({]""").containsMatchIn(l) }
        }
        if (hits.isNotEmpty()) fail("HiveCoreTheme is applied only by the activities (and previews): " + hits.joinToString { rel(it) })
    }
}
