package io.bhodl.desktop.i18n

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.fail

/**
 * UX review 2026-10 (rec 3) — test helpers that read the compose string
 * resources straight from `shared/src/commonMain/composeResources`
 * (every `strings*.xml` of a `values` / `values-it` directory).
 */
internal object I18nResources {

    fun resourcesRoot(): File {
        val candidates = listOf(
            File("../../shared/src/commonMain/composeResources"),
            File("shared/src/commonMain/composeResources"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: fail("composeResources not found from ${File(".").absolutePath}")
    }

    fun sourceRoot(): File {
        val candidates = listOf(File("src/jvmMain/kotlin"), File("apps/desktop/src/jvmMain/kotlin"))
        return candidates.firstOrNull { it.isDirectory }
            ?: fail("jvmMain sources not found from ${File(".").absolutePath}")
    }

    /** One `<string>` entry with the file it came from. */
    data class Entry(val key: String, val value: String, val file: String)

    /** Every entry of [dir] (`values`, `values-it`), in file order. */
    fun entries(dir: String): List<Entry> {
        val folder = File(resourcesRoot(), dir)
        val files = folder.listFiles { f -> f.isFile && f.name.endsWith(".xml") }.orEmpty().sortedBy { it.name }
        if (files.isEmpty()) fail("no xml files in ${folder.absolutePath}")
        val factory = DocumentBuilderFactory.newInstance()
        return files.flatMap { f ->
            val doc = factory.newDocumentBuilder().parse(f)
            val nodes = doc.getElementsByTagName("string")
            (0 until nodes.length).map { i ->
                val n = nodes.item(i)
                Entry(
                    key = n.attributes.getNamedItem("name").nodeValue,
                    value = n.textContent,
                    file = "$dir/${f.name}",
                )
            }
        }
    }

    // No space flag: "% del" in Italian prose is not a placeholder.
    private val formatArg = Regex("""%(\d+\$)?[-#+0,(]*\d*(\.\d+)?[sdfxXc%]""")

    /** Format placeholders of a value, order-insensitive (`%1$s`, `%2$d`). */
    fun formatArgs(value: String): List<String> =
        formatArg.findAll(value).map { it.value }.filter { it != "%%" }.sorted().toList()
}
