package io.bhodl.desktop.i18n

import io.bhodl.desktop.data.TrackerCache
import io.bhodl.desktop.wizard.RegistryStore
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Text shown in the UI that the compile-time scan cannot see: messages
 * built at runtime and the mobile hosts (docs/use-cases.md SEC-099,
 * GAP-011, GAP-013).
 */
class UcRuntimeStringsSecurityTest {

    private val literalText = Regex("""\b(?:Text\(|text\s*=\s*)\s*"((?:[^"\\]|\\.)*)"""")

    /** Literal UI text in Kotlin sources under [root] with at least two words. */
    private fun hardcodedTexts(root: File): List<String> =
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                literalText.find(line)?.groupValues?.get(1)
                    ?.takeIf { Regex("""[A-Za-z]{2,}\s+[A-Za-z]{2,}""").containsMatchIn(it) }
                    ?.let { "${f.name}:${i + 1}: \"$it\"" }
            }
        }.toList()

    private fun androidSources(): File {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "apps/android").isDirectory }
        return File(assertNotNull(root, "project root"), "apps/android/src/main")
    }

    @Test
    fun `SEC-099 the scan helper finds literal UI text`() {
        val tmp = kotlin.io.path.createTempDirectory("bhodl-uc-scan-").toFile()
        try {
            File(tmp, "A.kt").writeText("Text(\n  text = \"Hello there friend\",\n)\nText(\"BTC\")\n")
            assertTrue(hardcodedTexts(tmp).single().contains("Hello there friend"))
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `GAP-013 the Android host has no hardcoded UI text`() {
        val offenders = hardcodedTexts(androidSources())
        assertTrue(offenders.isEmpty(), offenders.joinToString("\n"))
    }

    @Test
    fun `GAP-011 runtime tracker errors follow the current language`() {
        val previous = Locale.getDefault()
        RegistryStore.clear()
        try {
            Locale.setDefault(Locale.ITALIAN)
            runBlocking { TrackerCache.refresh(syncEsplora = false) }
            val message = assertNotNull(TrackerCache.lastError.value)
            assertNotEquals("Registry not open.", message)
        } finally {
            Locale.setDefault(previous)
            TrackerCache.reset()
        }
    }
}
