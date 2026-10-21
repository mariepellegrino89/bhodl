package io.bhodl.desktop.wizard

import java.io.File
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Test-book §54.2–54.4 / audit 2026-10 — the pre-auth "forgot device PIN"
 * reset moves every wallet artefact into a recoverable trash folder
 * instead of deleting it.
 */
class LocalWipeTrashTest {

    private lateinit var tmp: File
    private lateinit var trash: File

    @BeforeTest
    fun setUp() {
        PinAttemptTracker.reset()
        tmp = kotlin.io.path.createTempDirectory("bhodl-trash-").toFile()
        trash = File(tmp.parentFile, tmp.name + "-trash-root")
    }

    @AfterTest
    fun tearDown() {
        PinAttemptTracker.reset()
        SetupSecrets.wipeAll()
        tmp.deleteRecursively()
        trash.deleteRecursively()
    }

    private fun seed(): List<File> {
        val w = File(tmp, "${WalletPaths.WALLETS_SUBDIR}/w1").apply { mkdirs() }
        val watch = File(tmp, WalletPaths.WATCH_STATE_SUBDIR).apply { mkdirs() }
        return listOf(
            File(tmp, WalletPaths.REGISTRY_FILE_NAME),
            File(tmp, WalletPaths.REGISTRY_FILE_NAME + "-wal"),
            File(tmp, WalletPaths.REGISTRY_KDF_FILE_NAME),
            File(w, WalletPaths.WALLET_DB_FILE_NAME),
            File(w, WalletPaths.KDF_FILE_NAME),
            File(w, WalletPaths.NETWORK_FILE_NAME),
            File(watch, "w2.json"),
        ).onEach { it.writeText(it.name) }
    }

    @Test
    fun `move to trash empties the data dir and keeps every file restorable`() {
        val files = seed()
        File(tmp, PinAttemptTracker.PIN_FAILURES_FILE_NAME).writeText("{}")
        SetupSecrets.put("abandon ".repeat(11) + "about")
        val now = Instant.parse("2026-10-21T08:09:10Z")

        val result = LocalWipe.moveAllToTrash(tmp, trash, now)

        assertTrue(result.ok, "failed: ${result.failed}")
        val target = assertNotNull(result.movedTo)
        assertEquals(File(trash, "20261021-080910"), target)
        files.forEach { f ->
            assertFalse(f.exists(), "${f.name} must leave the data dir")
            val moved = File(target, f.relativeTo(tmp).path)
            assertTrue(moved.isFile, "$moved missing")
            assertEquals(f.name, moved.readText(), "content preserved")
        }
        // Boot guard sees a fresh install; PIN history and parked seeds are gone.
        val view = WalletPaths.from(tmp)
        assertFalse(view.registryExists())
        assertFalse(view.walletExists())
        assertFalse(File(tmp, PinAttemptTracker.PIN_FAILURES_FILE_NAME).exists())
        assertEquals(0, SetupSecrets.size())
        // Databases are still moved before their KDF descriptors.
        val names = result.deleted.map { it.name }
        assertTrue(
            names.indexOf(WalletPaths.REGISTRY_FILE_NAME) < names.indexOf(WalletPaths.REGISTRY_KDF_FILE_NAME),
        )
    }

    @Test
    fun `a second reset in the same second gets its own folder`() {
        val now = Instant.parse("2026-10-21T08:09:10Z")
        seed()
        val first = LocalWipe.moveAllToTrash(tmp, trash, now).movedTo
        seed()
        val second = LocalWipe.moveAllToTrash(tmp, trash, now).movedTo
        assertNotEquals(first, second)
        assertTrue(File(second, WalletPaths.REGISTRY_FILE_NAME).isFile)
        assertTrue(File(first, WalletPaths.REGISTRY_FILE_NAME).isFile, "earlier trash is never overwritten")
    }

    @Test
    fun `default trash root is a sibling of the data dir`() {
        assertEquals(
            File(System.getProperty("user.home"), ".bhodl-trash"),
            LocalWipe.defaultTrashRoot(WalletPaths.defaultDataDir()),
        )
    }

    @Test
    fun `permanent wipe reports no trash folder`() {
        seed()
        val result = LocalWipe.wipeAll(tmp)
        assertTrue(result.ok, "failed: ${result.failed}")
        assertNull(result.movedTo)
    }
}
