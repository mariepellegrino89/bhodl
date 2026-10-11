package io.bhodl.ffi

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Regression: a fallible constructor that returns an object must surface the
 * typed [WalletException], not a NullPointerException from the generated
 * bindings (see the `fallibleCallNpe` rewrite in shared/build.gradle.kts).
 * The desktop PIN lockout only counts `WalletException.Storage` as a wrong
 * PIN, so an NPE here silently disabled the lockout.
 */
class FallibleConstructorErrorTest {
    @Test
    fun `wrong pin on unlockPersistent throws WalletException Storage`() {
        val dir = Files.createTempDirectory("bhodl-wrong-pin").toFile()
        try {
            WalletHandle.createOrLoadPersistent(
                dbDir = dir.absolutePath,
                network = Network.REGTEST,
                mnemonic = MNEMONIC,
                passphrase = "",
                pin = "1234567",
            ).close()

            assertFailsWith<WalletException.Storage> {
                WalletHandle.unlockPersistent(dir.absolutePath, Network.REGTEST, "9999999")
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `missing data dir on unlockPersistent throws WalletException`() {
        val dir = Files.createTempDirectory("bhodl-empty").toFile()
        try {
            assertFailsWith<WalletException> {
                WalletHandle.unlockPersistent(dir.absolutePath, Network.REGTEST, "1234567")
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    private companion object {
        const val MNEMONIC =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    }
}
