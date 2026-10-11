package io.bhodl.desktop.wallet

import io.bhodl.ffi.Network
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * On-disk persistence of the per-wallet [`Network`] choice.
 *
 * The network is selected at wallet creation / restore time and is
 * **immutable** for that wallet's lifetime — BIP-86 derivation paths
 * (and therefore every cached descriptor inside the SQLCipher store)
 * encode `coin_type` differently for mainnet vs testnet/signet/regtest,
 * so swapping the network at runtime would either lock the user out
 * (BDK descriptor mismatch on load) or silently surface a different
 * wallet entirely.
 *
 * Layout: a one-line text file `wallet.network` next to
 * `wallet.sqlite`, holding the lower-case enum name (e.g. `signet`).
 * Plain text on purpose — not a secret, easy to inspect from a
 * terminal, no JSON ceremony for one keyword.
 *
 * Backwards compatibility: if the file is missing on a wallet that
 * pre-dates this iteration (i.e. created on Signet before 2.5 landed),
 * the loader returns [`Network.SIGNET`] — that was the only network
 * the desktop app supported, so the assumption is safe.
 */
public object NetworkPersistence {

    public const val FILE_NAME: String = "wallet.network"

    /**
     * Read the persisted network from `dir/wallet.network`. Falls
     * back to [`Network.SIGNET`] when:
     * - the file is missing (legacy wallet pre-2.5), or
     * - the file is empty / unparseable (best-effort recovery).
     */
    public fun read(dir: File): Network {
        val file = File(dir, FILE_NAME)
        if (!file.isFile) return Network.SIGNET
        val raw = runCatching { file.readText().trim().lowercase() }
            .getOrNull()
            ?: return Network.SIGNET
        return parse(raw) ?: Network.SIGNET
    }

    /**
     * Security audit 2026-10 (M4) — strict variant of [read]: returns
     * `null` instead of silently assuming SIGNET when the file is
     * missing, unreadable or unparseable, so the caller can cross-check
     * with the registry row (which records the wallet's network too)
     * before falling back.
     */
    public fun readOrNull(dir: File): Network? {
        val file = File(dir, FILE_NAME)
        if (!file.isFile) return null
        val raw = runCatching { file.readText().trim().lowercase() }.getOrNull() ?: return null
        return parse(raw)
    }

    /**
     * Atomically write `network` to `dir/wallet.network`.
     *
     * **Durability contract**: across every instant of the call's
     * execution, the on-disk state is either (a) the previous
     * `wallet.network` content untouched, or (b) the new content. There
     * is NEVER a window in which neither file exists — losing the
     * network file with the SQLCipher DB still present leaves the
     * boot guard in a confused state that cannot recover from PIN
     * alone (the user has to restore from seed).
     *
     * Mechanism:
     * 1. Write the new content to a sibling `.tmp` file.
     * 2. `fsync` the `.tmp` so its bytes hit the disk, not just the OS
     *    write cache — without this a power loss between write and
     *    rename could produce a zero-byte target.
     * 3. Atomic rename `.tmp` → `wallet.network` via
     *    [`Files.move(ATOMIC_MOVE + REPLACE_EXISTING)`]. The JVM's
     *    underlying call is `rename(2)` on POSIX and `MoveFileEx` with
     *    `MOVEFILE_REPLACE_EXISTING` on Windows; both are atomic
     *    for same-volume moves.
     * 4. If `ATOMIC_MOVE` is not supported by the filesystem (rare —
     *    typically tmpfs vs ext4 cross-mount), fall back to
     *    `REPLACE_EXISTING` alone. Still better than the previous
     *    `delete-then-rename` pattern because the rename and the
     *    overwrite are a single OS call.
     *
     * The orphan `.tmp` left by a crash mid-step-1 is harmless: the
     * next `read` ignores it (only `FILE_NAME` is considered) and the
     * next `write` will overwrite it.
     */
    public fun write(dir: File, network: Network) {
        if (!dir.exists()) dir.mkdirs()
        val target = File(dir, FILE_NAME)
        val tmp = File(dir, "$FILE_NAME.tmp")
        val bytes = serialise(network).toByteArray(Charsets.UTF_8)
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            // Force data + metadata to disk before the rename.
            out.fd.sync()
        }
        try {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            // Filesystem cannot guarantee atomicity (cross-mount, FAT,
            // some FUSE drivers). Fall back to a non-atomic replace.
            // Still single-call and never `delete-target-first`.
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    /** Lower-case enum name written to disk. */
    public fun serialise(network: Network): String = when (network) {
        Network.MAINNET -> "mainnet"
        Network.TESTNET4 -> "testnet4"
        Network.SIGNET -> "signet"
        Network.REGTEST -> "regtest"
    }

    /** Inverse of [`serialise`]. Returns `null` for unknown input. */
    public fun parse(raw: String): Network? = when (raw) {
        "mainnet" -> Network.MAINNET
        "testnet4" -> Network.TESTNET4
        "signet" -> Network.SIGNET
        "regtest" -> Network.REGTEST
        else -> null
    }
}
