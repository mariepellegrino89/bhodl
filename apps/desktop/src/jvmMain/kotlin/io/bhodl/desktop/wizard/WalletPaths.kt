package io.bhodl.desktop.wizard

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * Where the desktop app stores its on-disk wallet data.
 *
 * Keeps the data outside the project tree, in the user's home
 * directory under `~/.bhodl/`:
 * - `registry.sqlite` + `registry.kdf.json` — the device-PIN registry
 *   (wallet list + tracker tables) and its Argon2id descriptor;
 * - `watch-state/<uuid>.sqlite` — each watch wallet's chain state,
 *   labels and address book, encrypted with the same device key.
 *
 * The `wallet.*` / `wallets/` names below belong to builds that could
 * hold seeds; they are only ever detected and deleted
 * ([WalletPathsView.purgeLegacySigningData]).
 *
 * Helper exists in pure Kotlin (no Compose) so the boot guard logic
 * inside `App()` and the unit tests can both reach it without touching
 * the Compose runtime. Android and iOS will get their own `WalletPaths`
 * implementations once those targets land in `shared/`.
 */
public object WalletPaths {

    /** Default sub-directory under `~/`. Tests override via [`from`]. */
    public const val DEFAULT_DIR_NAME: String = ".bhodl"

    /** Legacy (pre-watch-only) signing-wallet salt file. */
    public const val SALT_FILE_NAME: String = "wallet.salt"
    public const val KDF_FILE_NAME: String = "wallet.kdf.json"

    /**
     * `<descriptor>.next` — written by the Rust KDF migration before the
     * rekey commits (see `kdf.rs` `KdfScope::pending_path`). A crash
     * between rekey and rename leaves ONLY this file (+ possibly the
     * legacy salt) as the wallet's KDF state.
     */
    public const val KDF_PENDING_FILE_NAME: String = "wallet.kdf.json.next"

    /** Rust `kdf.rs` marker: descriptor sealed at least once (MAC required from then on). */
    public const val KDF_SEALED_MARKER_NAME: String = "wallet.kdf.sealed"

    /** SQLCipher wallet database file name (per wallet dir, or data-dir root pre-3.0). */
    public const val WALLET_DB_FILE_NAME: String = "wallet.sqlite"

    /** Prefix of an orphan DB renamed aside by Rust (`orphan_sqlite_path`, L5). */
    public const val ORPHAN_DB_PREFIX: String = "wallet.sqlite.orphan-"

    /** Legacy signing-wallet network choice (one line, e.g. `signet`). */
    public const val NETWORK_FILE_NAME: String = "wallet.network"

    // Phase 3 — multi-wallet layout. The legacy single-wallet files
    // (above) were migrated into a per-wallet directory at
    // `<dataDir>/wallets/<uuid>/`. The registry catalogues them.

    public const val REGISTRY_FILE_NAME: String = "registry.sqlite"
    public const val REGISTRY_KDF_FILE_NAME: String = "registry.kdf.json"
    /** PIN recovery: the data key wrapped by the recovery code (Rust `kdf.rs`). */
    public const val RECOVERY_FILE_NAME: String = "registry.recovery.json"
    public const val WALLETS_SUBDIR: String = "wallets"
    public const val WATCH_STATE_SUBDIR: String = "watch-state"
    public const val MIGRATION_LOCK_NAME: String = "migration.lock"
    public const val MIGRATION_BACKUP_SUBDIR: String = "migration.pre-3.0.bak"

    /** Returns the resolved data directory under `~/`, creating it lazily. */
    public fun defaultDataDir(): File =
        File(System.getProperty("user.home"), DEFAULT_DIR_NAME)

    /**
     * Security review — best-effort `chmod 700` on [dir]. The data dir
     * holds the SQLCipher DB, the Argon2id salt and the KDF descriptor;
     * other local users have no business listing it. Wrapped in
     * `runCatching` because non-POSIX filesystems (NTFS on Windows,
     * some FUSE mounts) throw `UnsupportedOperationException` — there
     * we silently fall back to the platform default ACLs.
     */
    public fun restrictToOwner(dir: File) {
        runCatching {
            Files.setPosixFilePermissions(
                dir.toPath(),
                PosixFilePermissions.fromString("rwx------"),
            )
        }
    }

    /**
     * Test seam — return a [`WalletPathsView`] rooted at `dir`. Production
     * callers use [`defaultDataDir`] directly; tests pass a `tempfile` path.
     */
    public fun from(dir: File): WalletPathsView = WalletPathsView(dir)
}

/**
 * Filesystem helper bound to a specific data directory. The setup wizard
 * uses one rooted at `~/.bhodl`; unit tests use a `tempfile` directory.
 */
public data class WalletPathsView(val dir: File) {

    public val saltFile: File get() = File(dir, WalletPaths.SALT_FILE_NAME)
    public val kdfFile: File get() = File(dir, WalletPaths.KDF_FILE_NAME)
    public val networkFile: File get() = File(dir, WalletPaths.NETWORK_FILE_NAME)

    /** Phase 3 — registry DB + KDF descriptor at the root of [`dir`]. */
    public val registryFile: File get() = File(dir, WalletPaths.REGISTRY_FILE_NAME)
    public val registryKdfFile: File get() = File(dir, WalletPaths.REGISTRY_KDF_FILE_NAME)
    public val walletsRoot: File get() = File(dir, WalletPaths.WALLETS_SUBDIR)
    public val watchStateRoot: File get() = File(dir, WalletPaths.WATCH_STATE_SUBDIR)
    public val migrationLockFile: File get() = File(dir, WalletPaths.MIGRATION_LOCK_NAME)
    public val legacyDbFile: File get() = File(dir, WalletPaths.WALLET_DB_FILE_NAME)
    public val migrationBackupDir: File get() = File(dir, WalletPaths.MIGRATION_BACKUP_SUBDIR)

    /**
     * `true` when a Phase 3 registry exists on disk: the boot guard
     * routes to the device-PIN gate. False on a fresh install.
     */
    public fun registryExists(): Boolean = registryFile.isFile

    /**
     * `true` when key material left by a build that could hold seeds is
     * still on disk: a pre-3.0 single-wallet install at the root
     * (`wallet.sqlite`, `wallet.salt`, `wallet.kdf.json`, …), its
     * migration backup / sentinel, or any `wallets/<uuid>/` signing
     * wallet directory. BHODL is watch-only now: the boot guard sends the
     * user to the legacy-cleanup screen, which deletes it on confirmation.
     */
    public fun legacySigningDataExists(): Boolean =
        legacyRootArtifacts().any { it.exists() } ||
            (walletsRoot.listFiles()?.isNotEmpty() ?: false)

    /**
     * Delete every legacy signing artefact found by
     * [legacySigningDataExists]. Databases first, KDF descriptors last
     * (same rule as [resetChecked]): if a database cannot be deleted the
     * descriptors stay so it remains decryptable. Never touches the
     * registry or watch-wallet state. **Destructive** — gate behind an
     * explicit confirmation and close every handle first.
     */
    public fun purgeLegacySigningData(): ResetResult {
        val deleted = mutableListOf<File>()
        val databases = buildList {
            add(legacyDbFile)
            addAll(orphanDbs(dir))
            add(File(migrationBackupDir, WalletPaths.WALLET_DB_FILE_NAME))
            walletsRoot.listFiles()?.filter { it.isDirectory }?.forEach { sub ->
                add(File(sub, WalletPaths.WALLET_DB_FILE_NAME))
                addAll(orphanDbs(sub))
            }
        }
        val dbFailures = mutableListOf<File>()
        for (db in databases) {
            for (f in listOf(db) + SQLITE_SIDECARS.map { File(db.parentFile, db.name + it) }) {
                if (!f.exists()) continue
                if (f.delete()) deleted += f else dbFailures += f
            }
        }
        if (dbFailures.isNotEmpty()) {
            return ResetResult(deleted = deleted, failed = dbFailures, aborted = true)
        }
        for (f in legacyRootArtifacts()) {
            if (f.isDirectory) {
                if (f.deleteRecursively()) deleted += f
            } else if (f.isFile && f.delete()) {
                deleted += f
            }
        }
        if (walletsRoot.isDirectory && walletsRoot.deleteRecursively()) deleted += walletsRoot
        val leftovers = (legacyRootArtifacts() + walletsRoot).filter { it.exists() }
        return ResetResult(deleted = deleted, failed = leftovers, aborted = false)
    }

    /** Root-level files / dirs of a pre-watch-only signing install. */
    private fun legacyRootArtifacts(): List<File> = listOf(
        legacyDbFile,
        saltFile,
        kdfFile,
        networkFile,
        File(dir, WalletPaths.KDF_PENDING_FILE_NAME),
        File(dir, WalletPaths.KDF_SEALED_MARKER_NAME),
        migrationLockFile,
        migrationBackupDir,
    ) + orphanDbs(dir)

    /** String form of the directory, suitable for the UniFFI binding. */
    public fun dataDirAsString(): String = dir.absolutePath

    /**
     * Wipe the locally stored wallet artefacts so the boot guard
     * treats the next launch as a fresh install.
     *
     * **WARNING — destructive.** Removes the registry (wallet list and
     * every tracker table) and all watch-wallet state, plus any legacy
     * signing data. Callers MUST gate this behind an explicit
     * confirmation step AND release every open handle first (see
     * [LocalWipe]).
     *
     * Returns the files actually deleted (in deletion order). See
     * [resetChecked] for the variant that also reports what could NOT
     * be deleted.
     */
    public fun reset(): List<File> = resetChecked().deleted

    /** Outcome of [resetChecked]. */
    public data class ResetResult(
        val deleted: List<File>,
        /** Files / dirs still present after the attempt. Empty = success. */
        val failed: List<File>,
        /**
         * `true` when a database could not be deleted and the KDF
         * descriptors were therefore left in place on purpose.
         */
        val aborted: Boolean,
    ) {
        val ok: Boolean get() = failed.isEmpty() && !aborted
    }

    /**
     * Security audit 2026-10 (M8) — deletion order is now
     * **databases first, KDF descriptors last**, and every step is
     * verified.
     *
     * The old order (KDF markers first, then `*.sqlite`) was designed
     * for a boot guard keyed on the markers, but on Windows an open
     * SQLCipher handle makes `registry.sqlite` undeletable: the wipe
     * then removed `registry.kdf.json` (salt + Argon2 params) and left
     * the encrypted DB behind — permanently unopenable, and it still
     * made `registryExists()` true so the app routed to a device-PIN
     * gate that could never succeed.
     *
     * Now:
     *  1. every SQLite database (+ `-wal` / `-shm` / `-journal`
     *     sidecars) is deleted — root legacy wallet, registry, and each
     *     `wallets/<uuid>/wallet.sqlite`;
     *  2. if ANY database survives, we STOP: the KDF descriptors are
     *     left in place so the surviving data stays decryptable, and the
     *     caller reports the failure;
     *  3. otherwise KDF descriptors, network files, wallet dirs, watch
     *     state and the migration sentinel are removed;
     *  4. anything still on disk is returned in [ResetResult.failed].
     */
    public fun resetChecked(): ResetResult {
        val deleted = mutableListOf<File>()

        // Phase 1 — databases (most likely to be locked).
        // Orphans renamed aside by Rust (L5) and the pre-3.0 migration
        // backup (a weak-KDF copy of the seed) are databases too.
        val databases = buildList {
            add(legacyDbFile)
            add(registryFile)
            addAll(orphanDbs(dir))
            add(File(migrationBackupDir, WalletPaths.WALLET_DB_FILE_NAME))
            walletsRoot.listFiles()?.filter { it.isDirectory }?.forEach { sub ->
                add(File(sub, WalletPaths.WALLET_DB_FILE_NAME))
                addAll(orphanDbs(sub))
            }
        }
        val dbFailures = mutableListOf<File>()
        for (db in databases) {
            for (f in listOf(db) + SQLITE_SIDECARS.map { File(db.parentFile, db.name + it) }) {
                if (!f.exists()) continue
                if (f.delete()) deleted += f else dbFailures += f
            }
        }
        if (dbFailures.isNotEmpty()) {
            // Keep the KDF descriptors so the surviving DB(s) remain
            // openable with the right PIN; the caller reports failure.
            return ResetResult(deleted = deleted, failed = dbFailures, aborted = true)
        }

        // Phase 2 — KDF descriptors / boot-guard markers.
        // `*.kdf.json.next` (interrupted KDF migration) and `*.kdf.sealed`
        // markers must go too: a stale `.sealed` would make a fresh
        // install refuse its own first descriptor.
        rootKdfArtifacts().forEach { f ->
            if (f.isFile && f.delete()) deleted += f
        }
        if (networkFile.isFile && networkFile.delete()) deleted += networkFile

        // Phase 3 — per-wallet dirs, watch state, migration sentinel.
        if (walletsRoot.isDirectory) {
            walletsRoot.listFiles()?.forEach { sub ->
                if (sub.isDirectory && sub.deleteRecursively()) deleted += sub
            }
            walletsRoot.delete()
        }
        if (watchStateRoot.isDirectory) {
            watchStateRoot.listFiles()?.forEach { f ->
                if (f.isFile && f.delete()) deleted += f
            }
            watchStateRoot.delete()
        }
        if (migrationLockFile.isFile && migrationLockFile.delete()) deleted += migrationLockFile
        if (migrationBackupDir.isDirectory && migrationBackupDir.deleteRecursively()) {
            deleted += migrationBackupDir
        }

        // Phase 4 — verify.
        val leftovers = (
            rootKdfArtifacts() +
                listOf(
                    registryFile, networkFile, legacyDbFile, walletsRoot, watchStateRoot,
                    migrationLockFile, migrationBackupDir,
                ) +
                orphanDbs(dir)
            ).filter { it.exists() }
        return ResetResult(deleted = deleted, failed = leftovers, aborted = false)
    }

    /** Every KDF descriptor / marker that can live at the data-dir root. */
    private fun rootKdfArtifacts(): List<File> = listOf(
        kdfFile,
        saltFile,
        registryKdfFile,
        File(dir, WalletPaths.KDF_PENDING_FILE_NAME),
        File(dir, WalletPaths.REGISTRY_KDF_FILE_NAME + ".next"),
        // PIN recovery: the data key wrapped by the recovery code.
        File(dir, WalletPaths.RECOVERY_FILE_NAME),
    ) + (dir.listFiles { f -> f.isFile && f.name.endsWith(".kdf.sealed") }?.toList() ?: emptyList())

    private companion object {
        val SQLITE_SIDECARS = listOf("-wal", "-shm", "-journal")

        /** `wallet.sqlite.orphan-<ts>` files in [d] (sidecars are handled per DB). */
        fun orphanDbs(d: File): List<File> =
            d.listFiles { f ->
                f.isFile &&
                    f.name.startsWith(WalletPaths.ORPHAN_DB_PREFIX) &&
                    SQLITE_SIDECARS.none { f.name.endsWith(it) }
            }?.toList() ?: emptyList()
    }
}
