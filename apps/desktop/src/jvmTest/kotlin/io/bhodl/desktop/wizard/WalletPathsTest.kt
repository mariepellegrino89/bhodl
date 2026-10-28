package io.bhodl.desktop.wizard

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WalletPathsTest {

    private lateinit var tmp: File

    @BeforeTest
    fun setup() {
        tmp = Files.createTempDirectory("bhodl-test-").toFile()
    }

    @AfterTest
    fun cleanup() {
        tmp.deleteRecursively()
    }

    // --- Watch-only: detection of leftover signing data ----------------------

    @Test
    fun legacySigningDataExists_is_false_for_empty_dir() {
        assertFalse(WalletPaths.from(tmp).legacySigningDataExists())
    }

    @Test
    fun legacySigningDataExists_is_false_for_nonexistent_dir() {
        assertFalse(WalletPaths.from(File(tmp, "does-not-exist")).legacySigningDataExists())
    }

    @Test
    fun legacySigningDataExists_ignores_registry_and_watch_state() {
        File(tmp, WalletPaths.REGISTRY_FILE_NAME).writeBytes(ByteArray(10))
        File(tmp, WalletPaths.REGISTRY_KDF_FILE_NAME).writeText("{}")
        val watch = File(tmp, WalletPaths.WATCH_STATE_SUBDIR).apply { mkdirs() }
        File(watch, "abc.sqlite").writeBytes(ByteArray(10))
        File(tmp, WalletPaths.WALLETS_SUBDIR).mkdirs() // empty dir is not signing data
        assertFalse(WalletPaths.from(tmp).legacySigningDataExists())
    }

    @Test
    fun legacySigningDataExists_detects_each_root_artifact() {
        val names = listOf(
            WalletPaths.WALLET_DB_FILE_NAME,
            WalletPaths.SALT_FILE_NAME,
            WalletPaths.KDF_FILE_NAME,
            WalletPaths.NETWORK_FILE_NAME,
            WalletPaths.KDF_PENDING_FILE_NAME,
            WalletPaths.KDF_SEALED_MARKER_NAME,
            WalletPaths.MIGRATION_LOCK_NAME,
            "${WalletPaths.ORPHAN_DB_PREFIX}1700000000",
        )
        for (name in names) {
            val f = File(tmp, name).apply { writeText("x") }
            assertTrue(WalletPaths.from(tmp).legacySigningDataExists(), "$name must be detected")
            f.delete()
        }
        File(tmp, WalletPaths.MIGRATION_BACKUP_SUBDIR).mkdirs()
        assertTrue(WalletPaths.from(tmp).legacySigningDataExists(), "migration backup must be detected")
    }

    @Test
    fun legacySigningDataExists_detects_a_per_wallet_signing_dir() {
        val walletDir = File(tmp, "${WalletPaths.WALLETS_SUBDIR}/abc").apply { mkdirs() }
        File(walletDir, WalletPaths.WALLET_DB_FILE_NAME).writeBytes(ByteArray(100) { 0x55 })
        assertTrue(WalletPaths.from(tmp).legacySigningDataExists())
    }

    @Test
    fun saltFile_is_inside_the_data_dir() {
        val view = WalletPaths.from(tmp)
        assertEquals(tmp.absolutePath, view.saltFile.parentFile.absolutePath)
        assertEquals(WalletPaths.SALT_FILE_NAME, view.saltFile.name)
    }

    @Test
    fun dataDirAsString_returns_absolute_path() {
        val view = WalletPaths.from(tmp)
        assertEquals(tmp.absolutePath, view.dataDirAsString())
    }

    @Test
    fun reset_deletes_network_file_when_present() {
        // Iteration 2.5 added wallet.network alongside the existing
        // markers. A wipe must clear it so a subsequent "Create" can
        // pick a different network without inheriting the old one.
        File(tmp, WalletPaths.SALT_FILE_NAME).writeBytes(ByteArray(16) { 0x42 })
        File(tmp, WalletPaths.NETWORK_FILE_NAME).writeText("regtest")

        val view = WalletPaths.from(tmp)
        val deleted = view.reset()

        val deletedNames = deleted.map { it.name }.toSet()
        assertTrue(WalletPaths.SALT_FILE_NAME in deletedNames)
        assertTrue(WalletPaths.NETWORK_FILE_NAME in deletedNames)
        assertFalse(view.networkFile.exists())
    }

    @Test
    fun reset_deletes_sqlite_before_kdf_and_salt_so_a_locked_db_stays_decryptable() {
        // Regression for a partial-wipe incident: the previous
        // delete order (salt → sqlite → kdf → network) could leave
        // the directory in `{kdf, network}` after a partial wipe,
        // causing the boot guard to route to Unlock with a missing
        // SQLCipher DB.
        //
        // We simulate a full pre-wipe state (every file present),
        // run reset(), and assert two invariants:
        //   1. Both boot-guard markers (salt + kdf) end up gone.
        //   2. `wallet.sqlite` is deleted BEFORE the KDF descriptors
        //      (asserted through the deletion order in `deleted`).
        File(tmp, WalletPaths.SALT_FILE_NAME).writeBytes(ByteArray(16) { 0x42 })
        File(tmp, WalletPaths.KDF_FILE_NAME).writeText("""{"version":1}""")
        File(tmp, "wallet.sqlite").writeBytes(ByteArray(100) { 0x55 })
        File(tmp, WalletPaths.NETWORK_FILE_NAME).writeText("signet")

        val view = WalletPaths.from(tmp)
        val deleted = view.reset()
        val deletedNames = deleted.map { it.name }

        assertTrue(WalletPaths.SALT_FILE_NAME in deletedNames)
        assertTrue(WalletPaths.KDF_FILE_NAME in deletedNames)
        assertTrue("wallet.sqlite" in deletedNames)
        assertTrue(WalletPaths.NETWORK_FILE_NAME in deletedNames)

        // Audit 2026-10 (M8) — databases go FIRST: if a (locked)
        // sqlite cannot be deleted, the KDF descriptors must still be
        // there so the surviving DB remains decryptable.
        val sqliteIdx = deletedNames.indexOf("wallet.sqlite")
        val saltIdx = deletedNames.indexOf(WalletPaths.SALT_FILE_NAME)
        val kdfIdx = deletedNames.indexOf(WalletPaths.KDF_FILE_NAME)
        assertTrue(
            sqliteIdx < saltIdx,
            "sqlite must be deleted before salt (order was $deletedNames)",
        )
        assertTrue(
            sqliteIdx < kdfIdx,
            "sqlite must be deleted before kdf (order was $deletedNames)",
        )

        // Post-reset: no legacy key material, no orphan DB.
        assertFalse(view.legacySigningDataExists())
        assertFalse(File(tmp, "wallet.sqlite").exists())
    }

    @Test
    fun reset_only_sqlite_present_leaves_boot_in_clean_state() {
        // The state reported from a real install: `{sqlite}` only.
        File(tmp, "wallet.sqlite").writeBytes(ByteArray(100) { 0x55 })
        val view = WalletPaths.from(tmp)
        // A subsequent reset() picks up the orphan.
        val deleted = view.reset()
        assertEquals(listOf("wallet.sqlite"), deleted.map { it.name })
    }

    // --- Watch-only: purge of leftover signing data --------------------------

    @Test
    fun purgeLegacySigningData_removes_every_signing_artifact_and_keeps_watch_data() {
        val rootNames = listOf(
            WalletPaths.WALLET_DB_FILE_NAME,
            "${WalletPaths.WALLET_DB_FILE_NAME}-wal",
            WalletPaths.SALT_FILE_NAME,
            WalletPaths.KDF_FILE_NAME,
            WalletPaths.NETWORK_FILE_NAME,
            WalletPaths.KDF_PENDING_FILE_NAME,
            WalletPaths.KDF_SEALED_MARKER_NAME,
            WalletPaths.MIGRATION_LOCK_NAME,
            "${WalletPaths.ORPHAN_DB_PREFIX}1700000000",
        )
        rootNames.forEach { File(tmp, it).writeText("x") }
        val backup = File(tmp, WalletPaths.MIGRATION_BACKUP_SUBDIR).apply { mkdirs() }
        File(backup, WalletPaths.WALLET_DB_FILE_NAME).writeText("x")
        File(backup, WalletPaths.SALT_FILE_NAME).writeText("x")
        val walletDir = File(tmp, "${WalletPaths.WALLETS_SUBDIR}/abc").apply { mkdirs() }
        File(walletDir, WalletPaths.WALLET_DB_FILE_NAME).writeText("x")
        File(walletDir, WalletPaths.KDF_FILE_NAME).writeText("x")
        // Watch-only data that must survive.
        val registry = File(tmp, WalletPaths.REGISTRY_FILE_NAME).apply { writeText("r") }
        val registryKdf = File(tmp, WalletPaths.REGISTRY_KDF_FILE_NAME).apply { writeText("{}") }
        val watchState = File(tmp, "${WalletPaths.WATCH_STATE_SUBDIR}/abc.sqlite")
            .apply { parentFile.mkdirs(); writeText("w") }

        val view = WalletPaths.from(tmp)
        val result = view.purgeLegacySigningData()

        assertTrue(result.ok, "purge failed: $result")
        rootNames.forEach { assertFalse(File(tmp, it).exists(), "$it survived the purge") }
        assertFalse(backup.exists(), "migration backup survived the purge")
        assertFalse(File(tmp, WalletPaths.WALLETS_SUBDIR).exists())
        assertFalse(view.legacySigningDataExists())
        assertTrue(registry.exists() && registryKdf.exists() && watchState.exists())
        assertTrue(view.registryExists())
    }

    @Test
    fun purgeLegacySigningData_deletes_databases_before_kdf_descriptors() {
        File(tmp, WalletPaths.SALT_FILE_NAME).writeBytes(ByteArray(16) { 0x42 })
        File(tmp, WalletPaths.KDF_FILE_NAME).writeText("""{"version":1}""")
        File(tmp, WalletPaths.WALLET_DB_FILE_NAME).writeBytes(ByteArray(100) { 0x55 })

        val deletedNames = WalletPaths.from(tmp).purgeLegacySigningData().deleted.map { it.name }

        val sqliteIdx = deletedNames.indexOf(WalletPaths.WALLET_DB_FILE_NAME)
        assertTrue(sqliteIdx >= 0, "wallet.sqlite not deleted: $deletedNames")
        assertTrue(sqliteIdx < deletedNames.indexOf(WalletPaths.SALT_FILE_NAME), "order was $deletedNames")
        assertTrue(sqliteIdx < deletedNames.indexOf(WalletPaths.KDF_FILE_NAME), "order was $deletedNames")
    }

    @Test
    fun purgeLegacySigningData_on_clean_dir_is_a_successful_no_op() {
        File(tmp, WalletPaths.REGISTRY_FILE_NAME).writeText("r")
        val result = WalletPaths.from(tmp).purgeLegacySigningData()
        assertTrue(result.ok)
        assertEquals(emptyList(), result.deleted)
        assertEquals(listOf(WalletPaths.REGISTRY_FILE_NAME), tmp.listFiles()!!.map { it.name })
    }

    @Test
    fun reset_removes_sealed_markers_pending_descriptors_orphans_and_migration_backup() {
        val names = listOf(
            WalletPaths.KDF_FILE_NAME,
            WalletPaths.KDF_PENDING_FILE_NAME,
            WalletPaths.KDF_SEALED_MARKER_NAME,
            WalletPaths.REGISTRY_KDF_FILE_NAME,
            "${WalletPaths.REGISTRY_KDF_FILE_NAME}.next",
            "registry.kdf.sealed",
            "${WalletPaths.ORPHAN_DB_PREFIX}1700000000",
            "${WalletPaths.ORPHAN_DB_PREFIX}1700000000-wal",
        )
        names.forEach { File(tmp, it).writeText("x") }
        val backup = File(tmp, WalletPaths.MIGRATION_BACKUP_SUBDIR).apply { mkdirs() }
        File(backup, WalletPaths.WALLET_DB_FILE_NAME).writeText("x")
        File(backup, WalletPaths.SALT_FILE_NAME).writeText("x")
        File(backup, "wallet_id").writeText("abc")
        val walletDir = File(tmp, "${WalletPaths.WALLETS_SUBDIR}/abc").apply { mkdirs() }
        File(walletDir, WalletPaths.KDF_FILE_NAME).writeText("x")
        File(walletDir, "${WalletPaths.ORPHAN_DB_PREFIX}1").writeText("x")

        val result = WalletPaths.from(tmp).resetChecked()

        assertTrue(result.ok, "reset failed: $result")
        names.forEach { assertFalse(File(tmp, it).exists(), "$it survived the wipe") }
        assertFalse(backup.exists(), "migration backup survived the wipe")
        assertFalse(File(tmp, WalletPaths.WALLETS_SUBDIR).exists())
        assertEquals(emptyList(), tmp.listFiles()!!.map { it.name })
    }

    @Test
    fun defaultDataDir_lives_under_user_home() {
        val home = File(System.getProperty("user.home"))
        val dataDir = WalletPaths.defaultDataDir()
        assertEquals(home.absolutePath, dataDir.parentFile.absolutePath)
        assertEquals(WalletPaths.DEFAULT_DIR_NAME, dataDir.name)
    }
}
