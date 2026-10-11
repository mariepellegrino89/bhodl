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

    @Test
    fun walletExists_returns_false_for_empty_dir() {
        val view = WalletPaths.from(tmp)
        assertFalse(view.walletExists())
    }

    @Test
    fun walletExists_returns_true_when_salt_file_present() {
        File(tmp, WalletPaths.SALT_FILE_NAME).writeBytes(ByteArray(16) { 0x42 })
        val view = WalletPaths.from(tmp)
        assertTrue(view.walletExists())
    }

    @Test
    fun walletExists_returns_false_when_salt_path_is_a_directory() {
        // Defensive: a directory named wallet.salt is not a salt file.
        File(tmp, WalletPaths.SALT_FILE_NAME).mkdir()
        val view = WalletPaths.from(tmp)
        assertFalse(view.walletExists())
    }

    @Test
    fun walletExists_returns_true_when_only_kdf_file_present() {
        // Wallets created via the KDF migration framework only have
        // wallet.kdf.json (no standalone wallet.salt) — the boot guard
        // must still recognise them as existing wallets.
        File(tmp, WalletPaths.KDF_FILE_NAME).writeText(
            """{"version":1,"salt_hex":"00112233445566778899aabbccddeeff","kdf":{"m_cost_kib":65536,"t_cost":3,"p_cost":4}}""",
        )
        val view = WalletPaths.from(tmp)
        assertTrue(view.walletExists())
    }

    @Test
    fun walletExists_returns_true_when_both_salt_and_kdf_present() {
        // Mid-migration state: legacy salt file still around, modern
        // kdf.json was just written. Either signal alone is sufficient.
        File(tmp, WalletPaths.SALT_FILE_NAME).writeBytes(ByteArray(16) { 0x42 })
        File(tmp, WalletPaths.KDF_FILE_NAME).writeText("""{"version":1}""")
        val view = WalletPaths.from(tmp)
        assertTrue(view.walletExists())
    }

    @Test
    fun walletExists_returns_false_for_nonexistent_dir() {
        val missing = File(tmp, "does-not-exist")
        val view = WalletPaths.from(missing)
        assertFalse(view.walletExists())
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
        //   2. At any earlier point in the deletion sequence,
        //      `walletExists()` would have flipped to `false` BEFORE
        //      `wallet.sqlite` was touched. (We can't observe the
        //      mid-flight state from outside `reset()`, but we can
        //      assert the contract by inspecting the deletion order
        //      in `deleted`.)
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

        // Post-reset: walletExists() must be false (boot routes to
        // Welcome), no orphan markers, no orphan DB.
        assertFalse(view.walletExists())
        assertFalse(File(tmp, "wallet.sqlite").exists())
    }

    @Test
    fun reset_only_sqlite_present_leaves_boot_in_clean_state() {
        // The state reported from a real install: `{sqlite}` only.
        // walletExists() must return false so the boot guard routes
        // to Welcome, never to Unlock-without-key-material.
        File(tmp, "wallet.sqlite").writeBytes(ByteArray(100) { 0x55 })
        val view = WalletPaths.from(tmp)
        assertFalse(view.walletExists())
        // A subsequent reset() picks up the orphan.
        val deleted = view.reset()
        assertEquals(listOf("wallet.sqlite"), deleted.map { it.name })
    }

    // --- Audit 2026-10 (M2 follow-up): legacy `wallet.salt` installs ---

    @Test
    fun legacy_salt_only_install_needs_migration_and_unlocks_from_root() {
        // Pre-descriptor single-wallet install: wallet.salt + wallet.sqlite,
        // no wallet.kdf.json. Must reach the Phase 3.0 migration (Rust
        // upgrades the salt) instead of being ignored.
        File(tmp, WalletPaths.SALT_FILE_NAME).writeBytes(ByteArray(16) { 0x42 })
        File(tmp, WalletPaths.WALLET_DB_FILE_NAME).writeBytes(ByteArray(100) { 0x55 })
        val view = WalletPaths.from(tmp)
        assertTrue(view.legacyRootWalletExists())
        assertTrue(view.migrationNeeded())
        assertEquals(tmp, view.activeWalletDir())
    }

    @Test
    fun legacy_pending_kdf_only_install_is_recognised() {
        // Crash between rekey and rename: only `wallet.kdf.json.next`.
        File(tmp, WalletPaths.KDF_PENDING_FILE_NAME).writeText("""{"version":1}""")
        File(tmp, WalletPaths.WALLET_DB_FILE_NAME).writeBytes(ByteArray(100) { 0x55 })
        val view = WalletPaths.from(tmp)
        assertTrue(view.migrationNeeded())
        assertEquals(tmp, view.activeWalletDir())
    }

    @Test
    fun legacy_salt_install_with_registry_does_not_need_migration() {
        File(tmp, WalletPaths.SALT_FILE_NAME).writeBytes(ByteArray(16) { 0x42 })
        File(tmp, WalletPaths.WALLET_DB_FILE_NAME).writeBytes(ByteArray(100) { 0x55 })
        File(tmp, WalletPaths.REGISTRY_FILE_NAME).writeBytes(ByteArray(10))
        assertFalse(WalletPaths.from(tmp).migrationNeeded())
    }

    @Test
    fun wallet_broken_by_old_migration_is_routable_for_rust_repair() {
        // Pre-fix migration moved wallet.sqlite into wallets/<uuid>/ but
        // left wallet.salt at the data-dir root. unlock_persistent copies
        // it back (repair_migrated_legacy_salt), so the boot guard must
        // pick that dir.
        File(tmp, WalletPaths.SALT_FILE_NAME).writeBytes(ByteArray(16) { 0x42 })
        File(tmp, WalletPaths.REGISTRY_FILE_NAME).writeBytes(ByteArray(10))
        val walletDir = File(tmp, "${WalletPaths.WALLETS_SUBDIR}/abc").apply { mkdirs() }
        File(walletDir, WalletPaths.WALLET_DB_FILE_NAME).writeBytes(ByteArray(100) { 0x55 })

        val view = WalletPaths.from(tmp)
        assertFalse(view.migrationNeeded())
        assertTrue(view.anyMigratedWalletExists())
        assertEquals(walletDir, view.activeWalletDir())
    }

    @Test
    fun migrated_wallet_with_own_salt_is_recognised() {
        val walletDir = File(tmp, "${WalletPaths.WALLETS_SUBDIR}/abc").apply { mkdirs() }
        File(walletDir, WalletPaths.SALT_FILE_NAME).writeBytes(ByteArray(16) { 0x42 })
        File(walletDir, WalletPaths.WALLET_DB_FILE_NAME).writeBytes(ByteArray(100) { 0x55 })
        val view = WalletPaths.from(tmp)
        assertTrue(view.anyMigratedWalletExists())
        assertEquals(walletDir, view.activeWalletDir())
    }

    @Test
    fun migrated_db_without_any_salt_is_not_routable() {
        // No KDF state anywhere: nothing can unlock it, don't route there.
        val walletDir = File(tmp, "${WalletPaths.WALLETS_SUBDIR}/abc").apply { mkdirs() }
        File(walletDir, WalletPaths.WALLET_DB_FILE_NAME).writeBytes(ByteArray(100) { 0x55 })
        val view = WalletPaths.from(tmp)
        assertFalse(view.anyMigratedWalletExists())
        assertEquals(null, view.activeWalletDir())
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
