package io.bhodl.desktop.wizard

/**
 * Whether new wallets / registries on this machine can be bound to an OS
 * keystore (Windows DPAPI, macOS Keychain, Linux Secret Service).
 *
 * Bound stores mix a 256-bit OS-held device secret into the database
 * key, so a 6-digit PIN is safe against an attacker holding only a copy
 * of the data directory. Without a keystore the setup screens must ask
 * for a passphrase instead ([PinValidator.modeFor]); the Rust core
 * enforces the same rule at creation.
 */
public data class KeystoreAvailability(
    val available: Boolean,
    /** Backend display name ("Windows DPAPI", "macOS Keychain", …). */
    val backend: String,
)

public object DeviceKeystoreInfo {

    /**
     * Source of truth; production asks the Rust core. Tests replace it to
     * render either variant without touching the native library.
     */
    @Volatile
    public var provider: () -> KeystoreAvailability = {
        val s = io.bhodl.ffi.deviceKeystoreStatus()
        KeystoreAvailability(available = s.available, backend = s.backend)
    }

    @Volatile
    private var cached: KeystoreAvailability? = null

    /**
     * Blocking (D-Bus / Keychain probe): call from `Dispatchers.IO`. The
     * answer is cached for the process; a failing probe counts as "not
     * available" — never as available.
     */
    public fun status(): KeystoreAvailability =
        cached ?: runCatching { provider() }
            .getOrElse { KeystoreAvailability(available = false, backend = "none") }
            .also { cached = it }

    /** Forget the cached answer (tests, or after the user started a keyring). */
    public fun reset() {
        cached = null
    }
}
