package io.bhodl.desktop.wallet

import io.bhodl.ffi.Network
import io.bhodl.ffi.WalletException

/**
 * In-memory test double for [`WalletFactory`].
 *
 * - `validateMnemonic` either returns silently (default) or throws
 *   the `failValidate` exception. Tests use this to exercise the
 *   "invalid checksum" branch of `RestoreMnemonicScreen` without
 *   hitting bip39 or BDK.
 * - `unlockPersistent` either returns the configured `port` (default:
 *   a fresh [`FakeWalletPort`]) or throws the `failUnlock` exception
 *   (used to drive the wrong-PIN branch of `UnlockScreen`).
 *
 * Counters mirror what the screens call so tests can assert which
 * code path ran.
 */
public class FakeWalletFactory(
    public var port: WalletPort = FakeWalletPort(),
    public var failValidate: WalletException? = null,
    public var failUnlock: WalletException? = null,
) : WalletFactory {

    public val validateCalls: MutableList<ValidateCall> = mutableListOf()
    public val unlockCalls: MutableList<UnlockCall> = mutableListOf()

    override fun validateMnemonic(network: Network, mnemonic: String, passphrase: String) {
        validateCalls += ValidateCall(network, mnemonic, passphrase)
        failValidate?.let { throw it }
    }

    public val generateCalls: MutableList<UInt> = mutableListOf()

    /** Deterministic phrase for tests — mirrors [`FakeWalletPort`]. */
    override fun generateMnemonic(wordCount: UInt): String {
        generateCalls += wordCount
        return List(wordCount.toInt()) { "abandon" }.joinToString(" ")
    }

    override fun unlockPersistent(dbDir: String, network: Network, pin: String): WalletPort {
        unlockCalls += UnlockCall(dbDir, network, pin)
        failUnlock?.let { throw it }
        return port
    }

    public data class ValidateCall(
        val network: Network,
        val mnemonic: String,
        val passphrase: String,
    )

    public data class UnlockCall(
        val dbDir: String,
        val network: Network,
        val pin: String,
    )
}
