package io.bhodl.desktop.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A shared test profile: one `.bhodl-test` file (a zip) holding
 *
 * - `manifest.json` — format version and the day its dates refer to;
 * - `current/…` — the test profile's files (registry, watch state);
 * - `baseline/…` — its marked initial state, when there is one.
 *
 * Files are kept as relative path → bytes. Reading is bounded (entry
 * count, total size) and rejects paths leaving the package.
 */
internal class TestModePackage(
    val manifest: Manifest,
    val current: Map<String, ByteArray>,
    val baseline: Map<String, ByteArray>?,
) {
    @Serializable
    data class Manifest(
        val format: Int = FORMAT,
        val app: String = "BHODL",
        /** ISO day the dates of `current/` refer to (the export day). */
        val anchorDay: String,
        /** ISO day the dates of `baseline/` refer to. */
        val baselineAnchorDay: String? = null,
    )

    fun write(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            put(MANIFEST, json.encodeToString(Manifest.serializer(), manifest).toByteArray())
            current.toSortedMap().forEach { (path, bytes) -> put("$CURRENT/$path", bytes) }
            baseline?.toSortedMap()?.forEach { (path, bytes) -> put("$BASELINE/$path", bytes) }
        }
        return out.toByteArray()
    }

    companion object {
        const val FORMAT: Int = 1
        private const val MANIFEST = "manifest.json"
        private const val CURRENT = "current"
        private const val BASELINE = "baseline"
        private const val MAX_ENTRIES = 5_000
        private const val MAX_TOTAL_BYTES = 500L * 1024 * 1024

        private val json = Json { ignoreUnknownKeys = true }

        /** Files never worth sharing (locks, leftovers of a swap). */
        private fun skipped(path: String): Boolean =
            path.endsWith(".lock") || path.endsWith(".tmp") || path.contains(".old/") || path.contains(".new/")

        /**
         * Parse a package.
         *
         * @throws TestModeException BAD_FILE (not a package, unsafe
         *   path, too big), NEWER_FORMAT (made by a newer app).
         */
        fun read(bytes: ByteArray): TestModePackage {
            var manifest: Manifest? = null
            val current = mutableMapOf<String, ByteArray>()
            val baseline = mutableMapOf<String, ByteArray>()
            var entries = 0
            var total = 0L
            try {
                ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (++entries > MAX_ENTRIES) bad()
                        if (entry.isDirectory) continue
                        val name = entry.name.replace('\\', '/')
                        if (name.startsWith("/") || name.split('/').any { it == ".." } || ':' in name) bad()
                        val data = ByteArrayOutputStream()
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = zip.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_TOTAL_BYTES) bad()
                            data.write(buf, 0, n)
                        }
                        when {
                            name == MANIFEST -> manifest = json.decodeFromString(Manifest.serializer(), data.toString(Charsets.UTF_8))
                            name.startsWith("$CURRENT/") -> current[name.removePrefix("$CURRENT/")] = data.toByteArray()
                            name.startsWith("$BASELINE/") -> baseline[name.removePrefix("$BASELINE/")] = data.toByteArray()
                        }
                    }
                }
            } catch (e: TestModeException) {
                throw e
            } catch (e: Exception) {
                throw TestModeException(TestModeException.Reason.BAD_FILE, e)
            }
            val m = manifest ?: bad()
            if (m.format > FORMAT) throw TestModeException(TestModeException.Reason.NEWER_FORMAT)
            if (current.isEmpty()) bad()
            return TestModePackage(m, current, baseline.takeIf { it.isNotEmpty() })
        }

        private fun bad(): Nothing = throw TestModeException(TestModeException.Reason.BAD_FILE)

        /** Every file under [dir], by path relative to it. */
        fun readDir(dir: File): Map<String, ByteArray> =
            dir.walkTopDown().filter { it.isFile }
                .associate { it.relativeTo(dir).invariantSeparatorsPath to it.readBytes() }
                .filterKeys { !skipped(it) }

        /** Write [files] under [dir] (created). */
        fun writeDir(files: Map<String, ByteArray>, dir: File) {
            dir.mkdirs()
            val root = dir.canonicalFile
            files.forEach { (path, bytes) ->
                val target = File(root, path).canonicalFile
                // Belt and braces: read() already refused such paths.
                if (!target.path.startsWith(root.path + File.separator)) bad()
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
            }
        }
    }
}
