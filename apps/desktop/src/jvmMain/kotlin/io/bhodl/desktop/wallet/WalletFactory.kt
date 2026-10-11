package io.bhodl.desktop.wallet

import io.bhodl.ffi.Network
import io.bhodl.ffi.WalletException
import io.bhodl.ffi.WalletHandle

/**
 * Factory abstraction that hides `WalletHandle.fromMnemonic` and
 * `WalletHandle.unlockPersistent` behind a port the Compose UI tests
 * can swap out.
 *
 * Production wires this to [`UniffiWalletFactory`] which delegates to
 * the FFI exactly as before. Tests inject a `FakeWalletFactory` so
 * `RestoreMnemonicScreen` and `UnlockScreen` — the two flows that
 * have to *create* a wallet — can be exercised without standing up
 * a cdylib + SQLCipher + filesystem.
 *
 * Why a factory and not extending [`WalletPort`]: the existing
 * [`WalletPort`] models an *unlocked* wallet. Restore + Unlock are
 * about producing one; conflating the two interfaces would force
 * [`WalletPort`] to grow no-op stubs in every fake.
 */
public interface WalletFactory {

    /**
     * Validate a BIP39 phrase by attempting to construct a throwaway
     * in-memory wallet. Returns nothing on success and throws
     * [`WalletException`] if the phrase is malformed (wrong word
     * count, non-BIP39 word, bad checksum). The Restore flow uses
     * this purely as a checksum gate before navigating to PinSetup.
     */
    @Throws(WalletException::class)
    public fun validateMnemonic(network: Network, mnemonic: String, passphrase: String)

    /**
     * Generate a fresh BIP39 mnemonic of [wordCount] words.
     *
     * R7 — this lives on the factory so the Compose layer no longer
     * carries the uniffi ABI workaround it used to inline. The entropy
     * is produced by the audited Rust core, NOT the JVM. See the
     * production override for the workaround details.
     */
    @Throws(WalletException::class)
    public fun generateMnemonic(wordCount: UInt): String

    /**
     * Open the SQLCipher DB at `dbDir` with `pin`-derived key,
     * reconstruct the BDK wallet, and return a [`WalletPort`] ready
     * to use. Throws on wrong PIN / disk corruption /
     * descriptor-version mismatch — see [`WalletException.Storage`]
     * for the canonical "wrong PIN" path.
     */
    @Throws(WalletException::class)
    public fun unlockPersistent(dbDir: String, network: Network, pin: String): WalletPort
}

/**
 * Production [`WalletFactory`] — straight pass-through to the FFI.
 * The `unlockPersistent` overload wraps the resulting `WalletHandle`
 * in [`UniffiWalletPort`] so callers can store it in
 * [`io.bhodl.desktop.wizard.WalletStore`] alongside the same type
 * the wizard already produces.
 */
public class UniffiWalletFactory : WalletFactory {

    override fun validateMnemonic(network: Network, mnemonic: String, passphrase: String) {
        // Audit 2026-10 — destroy the throw-away handle right away (it
        // holds the derived xpriv in Rust memory until finalised).
        WalletHandle.fromMnemonic(
            network = network,
            mnemonic = mnemonic,
            passphrase = passphrase,
        ).use { }
    }

    /**
     * R7 — uniffi ABI workaround, encapsulated here so the UI layer
     * stays ignorant of it.
     *
     * uniffi 0.28.3 + the Ubique KMP fork has a Windows ABI bug where
     * method calls on any `#[uniffi::Object]` type other than
     * `WalletHandle` (and free functions) crash on `RustBuffer.free`
     * ("Invalid memory access" via JNA). Until that is fixed upstream
     * (uniffi is pinned to 0.28.3 in Cargo.toml — the only version the
     * KMP fork supports), `generateRandomMnemonic` must be reached
     * through a `WalletHandle` instance.
     *
     * We construct a throwaway handle from a well-known placeholder
     * phrase, call the method, then discard it. The placeholder never
     * leaves this function and derives no real key material; the actual
     * entropy comes from the audited Rust core (`core_generate_mnemonic`
     * → `bip39`), NOT the JVM — deliberately, so seed generation keeps
     * the assurance of the vetted crypto path. See `docs/uniffi-runtime.md`.
     */
    override fun generateMnemonic(wordCount: UInt): String {
        // Audit 2026-10 — `use {}` destroys the throw-away handle.
        return WalletHandle.fromMnemonic(
            network = Network.SIGNET,
            mnemonic = PLACEHOLDER_MNEMONIC,
            passphrase = "",
        ).use { throwaway -> throwaway.generateRandomMnemonic(wordCount) }
    }

    override fun unlockPersistent(
        dbDir: String,
        network: Network,
        pin: String,
    ): WalletPort = UniffiWalletPort(
        WalletHandle.unlockPersistent(
            dbDir = dbDir,
            network = network,
            pin = pin,
        ),
    )

    private companion object {
        // Throwaway BIP86 test vector. Seeds only the transient
        // `WalletHandle` that hosts the `generateRandomMnemonic` call
        // (see [generateMnemonic]); never used to derive real keys.
        const val PLACEHOLDER_MNEMONIC: String =
            "abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon about"
    }
}
