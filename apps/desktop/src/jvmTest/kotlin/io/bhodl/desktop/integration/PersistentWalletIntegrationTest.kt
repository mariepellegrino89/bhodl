package io.bhodl.desktop.integration

import io.bhodl.desktop.wizard.WalletPaths
import io.bhodl.ffi.RegistryHandle
import io.bhodl.ffi.WalletException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * L3 test for the persistent, device-PIN-encrypted watch-only flow.
 * Mirrors the returning-user life cycle:
 *
 * 1. `RegistryHandle.createOrLoad(dir, pin)` — first boot: the
 *    SQLCipher registry and its KDF descriptor are created.
 * 2. The BIP-86 test tpub is registered (`addWatchXpub`) and opened
 *    (`openWatch`); its chain state lives in `watch-state/<uuid>.sqlite`.
 * 3. The wallet is funded, synced, and verified to hold the funded sat.
 * 4. Every handle is dropped (simulating Lock / process exit).
 * 5. `createOrLoad(dir, pin)` again — returning user — and the same
 *    wallet id re-opens at the SAME address with the SAME balance.
 * 6. A wrong device PIN against the same dir is rejected.
 */
class PersistentWalletIntegrationTest : RegtestFixture() {

    @Test
    fun `watch wallet round-trip preserves balance and address across handle drops`(
        @TempDir tempDir: Path,
    ) {
        val dir = tempDir.toFile().apply { mkdirs() }
        val paths = WalletPaths.from(dir)

        // --- First boot --------------------------------------------------
        val registry = RegistryHandle.createOrLoad(dir.absolutePath, TEST_DEVICE_PIN)
        val first = openRegtestWatch(registry, label = "Persistent")
        val walletId = registry.listWallets().single().id
        val firstAddress = first.peekAddressAt(0u)
        assertTrue(
            firstAddress.startsWith("bcrt1p"),
            "regtest taproot address must start with bcrt1p; got $firstAddress",
        )
        assertTrue(paths.registryExists(), "registry.sqlite missing")
        assertTrue(paths.registryKdfFile.isFile, "registry.kdf.json missing")
        assertFalse(paths.legacySigningDataExists(), "a watch-only install leaves no signing data")

        // Fund + verify.
        fundWallet(firstAddress, "0.005") // 500 000 sat
        syncUntil(first) { it.balanceSat() >= 500_000u }
        val firstBalance = first.balanceSat()
        assertTrue(firstBalance >= 500_000u, "expected balance >= 500000, got $firstBalance")
        assertTrue(
            File(paths.watchStateRoot, "$walletId.sqlite").isFile,
            "watch-state/<uuid>.sqlite missing",
        )

        // Drop every handle like Lock does (WalletStore.clear() +
        // RegistryStore.clear()): the returning-user open below must
        // work from disk alone.
        first.destroy()
        registry.destroy()

        // --- Returning user ----------------------------------------------
        val reopened = RegistryHandle.createOrLoad(dir.absolutePath, TEST_DEVICE_PIN)
        val second = reopened.openWatch(walletId)
        try {
            assertEquals(
                firstAddress,
                second.peekAddressAt(0u),
                "address at index 0 must match across handle drops",
            )
            syncUntil(second) { it.balanceSat() == firstBalance }
            assertEquals(firstBalance, second.balanceSat())
        } finally {
            // Release the SQLCipher file locks so @TempDir cleanup works
            // on Windows (an open file cannot be deleted there).
            runCatching { second.destroy() }
            runCatching { reopened.destroy() }
        }

        // --- Wrong device PIN rejected ---------------------------------
        assertThrows(WalletException::class.java) {
            RegistryHandle.createOrLoad(dir.absolutePath, "999999").destroy()
        }
    }

    @Test
    fun `removing a wallet and wiping leave no wallet artifact behind`(
        @TempDir tempDir: Path,
    ) {
        val dir = tempDir.toFile().apply { mkdirs() }
        val registry = RegistryHandle.createOrLoad(dir.absolutePath, TEST_DEVICE_PIN)
        val handle = openRegtestWatch(registry)
        val walletId = registry.listWallets().single().id
        // Like RemoveThisWalletSection: close the handle first, Windows
        // cannot delete a SQLCipher file that is still open.
        handle.destroy()
        assertTrue(registry.removeWallet(walletId))
        assertTrue(registry.listWallets().isEmpty())
        assertFalse(File(WalletPaths.from(dir).watchStateRoot, "$walletId.sqlite").exists())
        registry.destroy()

        val result = WalletPaths.from(dir).resetChecked()
        assertTrue(result.ok, "wipe failed: $result")
        assertFalse(WalletPaths.from(dir).registryExists())
    }
}
