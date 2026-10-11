package io.bhodl.desktop.integration

import io.bhodl.desktop.wallet.NetworkPersistence
import io.bhodl.ffi.Network
import io.bhodl.ffi.WalletException
import io.bhodl.ffi.WalletHandle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * L3 test for the persistent SQLCipher-backed wallet flow plus the
 * 2.5 NetworkPersistence layer. Mirrors the returning-user life cycle:
 *
 * 1. `createOrLoadPersistent(dir, REGTEST, mnemonic, "", pin)` — first
 *    boot. The SQLCipher DB is initialised, descriptors persisted,
 *    KDF descriptor written next door.
 * 2. The Kotlin layer writes `wallet.network` via NetworkPersistence
 *    (production code path lives in PinSetupScreen.onSetupComplete;
 *    this test invokes NetworkPersistence directly so it exercises the
 *    same on-disk file boot guard reads).
 * 3. Wallet is funded, synced, and verified to hold the funded sat.
 * 4. The handle is dropped (simulating logout / process exit).
 * 5. NetworkPersistence.read recovers REGTEST from disk.
 * 6. `unlockPersistent(dir, REGTEST, pin)` — returning user. No
 *    mnemonic required; descriptors are reconstructed from the DB.
 * 7. The reloaded wallet re-derives the SAME address at index 0
 *    (proves descriptors round-tripped) and shows the SAME balance
 *    after sync.
 * 8. Wrong PIN against the same dir is rejected with WalletException.
 *
 * What this proves (in addition to the unit tests):
 * - SQLCipher key derivation (Argon2id + salt + PIN) is deterministic
 *   across handle drops.
 * - `unlock_persistent` reconstructs the BIP86 descriptors from the
 *   encrypted DB without seeing the mnemonic.
 * - NetworkPersistence's text-file format is round-trippable in a
 *   real filesystem (tempdir) and survives the wallet-create flow.
 */
class PersistentWalletIntegrationTest : RegtestFixture() {

    @Test
    fun `persistent wallet round-trip preserves balance and address across handle drops`(
        @TempDir tempDir: Path,
    ) {
        val dir = tempDir.toFile().apply { mkdirs() }
        val pin = "1234567"

        // --- First boot --------------------------------------------------
        val first = WalletHandle.createOrLoadPersistent(
            dbDir = dir.absolutePath,
            network = Network.REGTEST,
            mnemonic = BIP86_TEST_MNEMONIC,
            passphrase = "",
            pin = pin,
        )
        // Production code persists the network alongside the DB. We
        // call it explicitly here so the boot guard read below sees it.
        NetworkPersistence.write(dir, Network.REGTEST)

        val firstAddress = first.peekAddressAt(0u)
        assertTrue(
            firstAddress.startsWith("bcrt1p"),
            "regtest taproot address must start with bcrt1p; got $firstAddress",
        )
        // The KDF descriptor + SQLCipher DB MUST exist on disk now.
        assertTrue(File(dir, "wallet.kdf.json").isFile, "wallet.kdf.json missing")
        assertTrue(File(dir, "wallet.sqlite").isFile, "wallet.sqlite missing")
        assertTrue(File(dir, "wallet.network").isFile, "wallet.network missing")

        // Fund + verify.
        fundWallet(firstAddress, "0.005") // 500 000 sat
        syncUntilHandle(first) { it.balanceSat() >= 500_000u }
        val firstBalance = first.balanceSat()
        assertTrue(firstBalance >= 500_000u, "expected balance >= 500000, got $firstBalance")

        // Drop the handle like the Logout button does (WalletStore.clear()
        // closes it): SQLCipher close + non_secure_erase run now, and the
        // returning-user unlock below must work from disk alone.
        first.close()

        // --- Network round-trips through disk ----------------------------
        val recovered = NetworkPersistence.read(dir)
        assertEquals(
            Network.REGTEST,
            recovered,
            "boot guard MUST recover REGTEST from wallet.network on disk",
        )

        // --- Returning-user unlock --------------------------------------
        val second = WalletHandle.unlockPersistent(
            dbDir = dir.absolutePath,
            network = recovered,
            pin = pin,
        )

        // Same descriptors → same address at the same index, even
        // though the mnemonic was never re-supplied.
        val secondAddress = second.peekAddressAt(0u)
        assertEquals(
            firstAddress,
            secondAddress,
            "address at index 0 must match across handle drops " +
                "(descriptors did not round-trip through SQLCipher)",
        )

        // Balance survives the round-trip too. We re-sync (the BDK
        // store has the chain history but not necessarily the latest
        // mempool view after a re-open).
        syncUntilHandle(second) { it.balanceSat() == firstBalance }
        assertEquals(firstBalance, second.balanceSat())

        // --- Wrong PIN rejected ----------------------------------------
        assertThrows(WalletException::class.java) {
            WalletHandle.unlockPersistent(
                dbDir = dir.absolutePath,
                network = Network.REGTEST,
                pin = "9999999",
            )
        }

        // Release the SQLCipher file locks so @TempDir cleanup works on
        // Windows (an open file cannot be deleted there).
        second.close()
    }

    @Test
    fun `wallet on a fresh dir without wallet_network persists Signet by default`(
        @TempDir tempDir: Path,
    ) {
        val dir = tempDir.toFile().apply { mkdirs() }

        // Pre-2.5 wallets had no wallet.network file; the boot guard
        // MUST treat that as Signet so an upgrade does not lock them
        // out. We do not actually create the SQLCipher DB here — the
        // NetworkPersistence read is independent of the wallet load
        // and we want to exercise just that contract from the L3 side.
        assertEquals(Network.SIGNET, NetworkPersistence.read(dir))
        // Writing the modern marker upgrades the boot guard's view
        // for the next boot.
        NetworkPersistence.write(dir, Network.REGTEST)
        assertEquals(Network.REGTEST, NetworkPersistence.read(dir))
        assertNotEquals(
            "regtest",
            NetworkPersistence.serialise(Network.SIGNET),
            "sanity: REGTEST and SIGNET must serialise to distinct strings",
        )
    }

    @Test
    fun `wipe-style cleanup deletes every wallet artifact`(
        @TempDir tempDir: Path,
    ) {
        val dir = tempDir.toFile().apply { mkdirs() }
        val handle = WalletHandle.createOrLoadPersistent(
            dbDir = dir.absolutePath,
            network = Network.REGTEST,
            mnemonic = BIP86_TEST_MNEMONIC,
            passphrase = "",
            pin = "9876543",
        )
        NetworkPersistence.write(dir, Network.REGTEST)
        assertNotNull(handle.peekAddressAt(0u))
        // Like LocalWipe (WalletStore.clear()), close the handle first:
        // Windows cannot delete a SQLCipher file that is still open.
        handle.close()

        // Replicate WalletPathsView.reset(): delete the four marker
        // files. After this, the boot guard MUST treat the dir as
        // empty (i.e. NetworkPersistence falls back to Signet, no
        // wallet.kdf.json / wallet.sqlite, etc.).
        listOf(
            "wallet.salt",
            "wallet.sqlite",
            "wallet.kdf.json",
            "wallet.network",
        ).forEach { name ->
            File(dir, name).delete()
        }
        assertEquals(Network.SIGNET, NetworkPersistence.read(dir))
        assertEquals(false, File(dir, "wallet.kdf.json").exists())
        assertEquals(false, File(dir, "wallet.sqlite").exists())
        assertEquals(false, File(dir, "wallet.network").exists())
    }

    /**
     * Local copy of the shared `syncUntil` that operates on a freshly
     * unlocked handle (the shared one is type-bound to the originally
     * built wallet — both work the same way against the FFI but Kotlin
     * cannot generalise without an interface).
     */
    private fun syncUntilHandle(
        wallet: WalletHandle,
        timeoutMs: Long = 30_000L,
        intervalMs: Long = 500L,
        predicate: (WalletHandle) -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastSyncFailure: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                wallet.syncWithEsplora(esploraUrl(), null)
                lastSyncFailure = null
                if (predicate(wallet)) return
            } catch (e: WalletException) {
                lastSyncFailure = e
            }
            Thread.sleep(intervalMs)
        }
        throw AssertionError(
            "syncUntilHandle predicate never returned true within ${timeoutMs}ms" +
                (lastSyncFailure?.let { " (last sync error: ${it.message})" } ?: ""),
            lastSyncFailure,
        )
    }
}
