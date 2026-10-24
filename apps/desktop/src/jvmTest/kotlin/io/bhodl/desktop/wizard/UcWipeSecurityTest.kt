package io.bhodl.desktop.wizard

import io.bhodl.desktop.settings.SettingsPersistence
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.settings.ThemeMode
import java.io.File
import java.io.FileInputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Local wipe and legacy purge outcomes (docs/use-cases.md SEC-013,
 * SEC-075, SEC-077, SEC-078, GAP-022, GAP-023). Every wipe targets a
 * temp dir.
 */
class UcWipeSecurityTest {

    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        dir = kotlin.io.path.createTempDirectory("bhodl-uc-wipe-").toFile()
        SettingsStore.reset()
        PinAttemptTracker.reset()
    }

    @AfterTest
    fun tearDown() {
        dir.walkTopDown().forEach { it.setWritable(true) }
        dir.deleteRecursively()
        SettingsStore.reset()
        PinAttemptTracker.reset()
    }

    private fun file(path: String, text: String = "x"): File =
        File(dir, path).apply { parentFile.mkdirs(); writeText(text) }

    private fun seedInstall() {
        file(WalletPaths.REGISTRY_FILE_NAME)
        file(WalletPaths.REGISTRY_FILE_NAME + "-journal")
        file(WalletPaths.REGISTRY_KDF_FILE_NAME, "{}")
        file("registry.kdf.sealed")
        file("${WalletPaths.WATCH_STATE_SUBDIR}/aaaa.sqlite")
        file("${WalletPaths.WATCH_STATE_SUBDIR}/aaaa.sqlite-journal")
        file("${WalletPaths.WATCH_STATE_SUBDIR}/bbbb.sqlite")
        file(PinAttemptTracker.PIN_FAILURES_FILE_NAME, """{"version":1,"entries":[]}""")
    }

    @Test
    fun `SEC-075 GAP-022 the wipe deletes the watch-state databases too`() {
        seedInstall()
        val result = LocalWipe.wipeAll(dir)
        assertTrue(result.ok, "failed=${result.failed}")
        assertFalse(File(dir, WalletPaths.WATCH_STATE_SUBDIR).exists())
        assertFalse(File(dir, WalletPaths.REGISTRY_FILE_NAME).exists())
        assertFalse(File(dir, WalletPaths.REGISTRY_KDF_FILE_NAME).exists())
        assertFalse(File(dir, "registry.kdf.sealed").exists())
        assertFalse(File(dir, PinAttemptTracker.PIN_FAILURES_FILE_NAME).exists())

        // Order: the registry DB goes before its KDF descriptor; the
        // watch-state files (keyed by that same registry) go last.
        val names = result.deleted.map { it.name }
        val registry = names.indexOf(WalletPaths.REGISTRY_FILE_NAME)
        val kdf = names.indexOf(WalletPaths.REGISTRY_KDF_FILE_NAME)
        val watch = names.indexOf("aaaa.sqlite")
        assertTrue(registry in 0 until kdf, "$names")
        assertTrue(kdf < watch, "$names")
        assertTrue("aaaa.sqlite-journal" in names && "bbbb.sqlite" in names)
    }

    @Test
    fun `GAP-022 a locked watch-state file is reported but does not abort the wipe`() {
        seedInstall()
        val locked = File(dir, "${WalletPaths.WATCH_STATE_SUBDIR}/aaaa.sqlite")
        val result = lock(locked).use { LocalWipe.wipeAll(dir) }
        assertFalse(result.aborted)
        assertFalse(result.ok)
        assertTrue(result.failed.isNotEmpty())
        assertFalse(File(dir, WalletPaths.REGISTRY_FILE_NAME).exists())
        assertFalse(File(dir, WalletPaths.REGISTRY_KDF_FILE_NAME).exists())
        assertTrue(LocalWipe.describe(result).startsWith("Wipe incomplete"), LocalWipe.describe(result))
    }

    @Test
    fun `SEC-077 a locked registry aborts the wipe and keeps the KDF descriptor`() {
        seedInstall()
        val registry = File(dir, WalletPaths.REGISTRY_FILE_NAME)
        val result = lock(registry).use { LocalWipe.wipeAll(dir) }
        assertTrue(result.aborted)
        assertTrue(registry in result.failed)
        assertTrue(File(dir, WalletPaths.REGISTRY_KDF_FILE_NAME).exists(), "the DB must stay decryptable")
        assertTrue(File(dir, PinAttemptTracker.PIN_FAILURES_FILE_NAME).exists(), "nothing else is removed")
        assertTrue(LocalWipe.describe(result).startsWith("Wipe stopped"), LocalWipe.describe(result))
    }

    @Test
    fun `SEC-077 SEC-078 describe covers every outcome`() {
        val a = File(dir, "a")
        val b = File(dir, "b")
        assertEquals(
            "Wipe stopped: 1 database file(s) are still in use and could not be deleted. Nothing else was removed, " +
                "so your wallets are still intact. Close other BHODL windows and retry.",
            LocalWipe.describe(WalletPathsView.ResetResult(deleted = listOf(b), failed = listOf(a), aborted = true)),
        )
        assertEquals(
            "Wipe incomplete: 2 item(s) could not be deleted (a, b).",
            LocalWipe.describe(WalletPathsView.ResetResult(deleted = emptyList(), failed = listOf(a, b), aborted = false)),
        )
        assertEquals(
            "Nothing to wipe.",
            LocalWipe.describe(WalletPathsView.ResetResult(emptyList(), emptyList(), aborted = false)),
        )
        assertEquals(
            "Wiped 2 items.",
            LocalWipe.describe(WalletPathsView.ResetResult(listOf(a, b), emptyList(), aborted = false)),
        )
    }

    @Test
    fun `SEC-078 wiping an empty data dir reports nothing to wipe`() {
        val result = LocalWipe.wipeAll(dir)
        assertTrue(result.ok)
        assertEquals("Nothing to wipe.", LocalWipe.describe(result))
    }

    @Test
    fun `GAP-023 settings json survives the wipe but forgets the wallets it described`() {
        seedInstall()
        val settings = File(dir, SettingsPersistence.FILE_NAME)
        SettingsStore.setThemeMode(ThemeMode.Dark)
        SettingsStore.setTorEnabled(true)
        SettingsStore.setWalletIncluded("aaaa", false)
        SettingsStore.markLabelsExported("aaaa", 1_700_000_000L)
        SettingsStore.enablePersistence(settings)

        val result = LocalWipe.wipeAll(dir)
        assertTrue(result.ok)

        assertTrue(settings.isFile, "preferences are kept on purpose")
        assertEquals(ThemeMode.Dark, SettingsStore.themeMode.value)
        assertTrue(SettingsStore.torEnabled.value)
        assertTrue(SettingsStore.excludedWalletIds.value.isEmpty(), "orphan wallet ids are dropped")
        assertTrue(SettingsStore.labelsExportedAt.value.isEmpty())
        val reloaded = SettingsPersistence.load(settings).getOrThrow()!!
        assertTrue(reloaded.excludedWalletIds.isEmpty())
        assertTrue(reloaded.torEnabled)
    }

    @Test
    fun `SEC-013 a locked legacy database aborts the purge and keeps the key files`() {
        val db = file(WalletPaths.WALLET_DB_FILE_NAME)
        file(WalletPaths.SALT_FILE_NAME)
        file(WalletPaths.KDF_FILE_NAME, "{}")
        val result = lock(db).use { WalletPaths.from(dir).purgeLegacySigningData() }
        assertTrue(result.aborted)
        assertFalse(result.ok)
        assertTrue(db in result.failed)
        assertTrue(File(dir, WalletPaths.SALT_FILE_NAME).exists())
        assertTrue(File(dir, WalletPaths.KDF_FILE_NAME).exists())
    }

    @Test
    fun `SEC-013 a locked legacy key directory is reported as a leftover`() {
        file("${WalletPaths.WALLETS_SUBDIR}/w1/wallet.kdf.json", "{}")
        val keyFile = File(dir, "${WalletPaths.WALLETS_SUBDIR}/w1/wallet.kdf.json")
        val result = lock(keyFile).use { WalletPaths.from(dir).purgeLegacySigningData() }
        assertFalse(result.aborted)
        assertFalse(result.ok)
        assertTrue(File(dir, WalletPaths.WALLETS_SUBDIR) in result.failed)
    }

    /**
     * Makes [f] undeletable until closed: an open handle on Windows
     * (no FILE_SHARE_DELETE), a read-only parent directory elsewhere.
     */
    private fun lock(f: File): AutoCloseable =
        if (System.getProperty("os.name").lowercase().contains("win")) {
            val stream = FileInputStream(f)
            AutoCloseable { stream.close() }
        } else {
            val parent = f.parentFile
            parent.setWritable(false)
            AutoCloseable { parent.setWritable(true) }
        }
}
