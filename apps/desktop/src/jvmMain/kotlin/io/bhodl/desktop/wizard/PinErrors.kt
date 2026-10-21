package io.bhodl.desktop.wizard

import io.bhodl.ffi.WalletException
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.onb_pin_err_device_key_missing
import io.bhodl.shared.resources.onb_pin_err_keystore_unavailable
import io.bhodl.shared.resources.onb_pin_err_network
import io.bhodl.shared.resources.onb_pin_err_newer
import io.bhodl.shared.resources.onb_pin_err_storage
import io.bhodl.shared.resources.onb_pin_err_unlock_failed
import io.bhodl.shared.resources.onb_pin_wrong

/**
 * Security audit 2026-10 (M4) — classify a failed PIN-gated FFI open.
 *
 * The FFI reports a wrong PIN, a wallet/network mismatch and plain
 * storage problems all as `WalletException.Storage`. Treating every
 * Storage error as "Wrong PIN" mislabelled e.g. a mainnet wallet whose
 * `wallet.network` file went missing (fallback SIGNET → BDK
 * `check_network` failure) and COUNTED it against the PIN lockout.
 *
 * Classification is fail-closed: only errors we positively recognise as
 * non-PIN problems are excluded from the lockout counter; an unknown
 * Storage / KeyDerivation detail is still treated as a wrong PIN.
 */
public enum class PinFailureKind {
    /** The PIN was rejected (or we cannot prove otherwise). Counted. */
    WrongPin,

    /** The wallet's persisted network does not match what we asked for. */
    NetworkMismatch,

    /** Missing / unreadable / locked files, corrupt KDF descriptor, … */
    Storage,

    /**
     * The data on disk was written by a NEWER BHODL build (registry
     * schema version or KDF descriptor version this build does not
     * know). Downgrades are refused on purpose; the user must update.
     */
    NewerVersion,

    /**
     * The store is bound to an OS-keystore device secret that is gone or
     * cannot be decrypted by this OS account (OS reinstall, other user,
     * keyring reset, data dir copied from another machine). The PIN can
     * no longer open it here: restore from the recovery phrase or an
     * encrypted backup. Never counted against the lockout.
     */
    DeviceKeyMissing,

    /**
     * The OS keystore could not be reached (keyring locked / prompt
     * dismissed / Secret Service not running). The PIN was not checked.
     * Never counted.
     */
    KeystoreUnavailable,

    /** Anything else (non-FFI exception, programming error, …). */
    Other,
}

public object PinErrors {

    private val NETWORK_MARKERS = listOf("network")
    private val MISMATCH_MARKERS = listOf("mismatch", "loaded", "expected", "check_network")

    /**
     * Downgrade refusals emitted by the Rust core: the registry schema
     * gate (`registry.rs`, "… is newer than this app supports …") and
     * the KDF descriptor version check (`kdf.rs`, "unsupported kdf
     * descriptor version N").
     */
    private val NEWER_VERSION_MARKERS = listOf(
        "newer than this app supports",
        "newer than this build supports",
        "unsupported kdf descriptor version",
    )

    private val STORAGE_MARKERS = listOf(
        "no wallet.kdf.json",
        "invalid wallet.kdf.json",
        "re-parse kdf",
        "not valid hex",
        "seed table is empty",
        "contains no wallet",
        "permission denied",
        "access is denied",
        "being used by another process",
        "database is locked",
        "disk i/o error",
        "no such file",
        "cannot find the",
        "os error",
        "unable to open database",
    )

    public fun classify(e: Throwable): PinFailureKind {
        val detail = when (e) {
            // Typed by the core — checked first, never a PIN verdict.
            is WalletException.DeviceKeyMissing -> return PinFailureKind.DeviceKeyMissing
            is WalletException.DeviceKeystoreUnavailable -> return PinFailureKind.KeystoreUnavailable
            is WalletException.Storage -> e.detail
            is WalletException.KeyDerivation -> e.detail
            is WalletException.Io -> return PinFailureKind.Storage
            is WalletException.InvalidSalt -> return PinFailureKind.Storage
            is WalletException -> return PinFailureKind.Other
            else -> return PinFailureKind.Other
        }.lowercase()
        if (NEWER_VERSION_MARKERS.any { it in detail }) return PinFailureKind.NewerVersion
        if (NETWORK_MARKERS.any { it in detail } && MISMATCH_MARKERS.any { it in detail }) {
            return PinFailureKind.NetworkMismatch
        }
        if (STORAGE_MARKERS.any { it in detail }) return PinFailureKind.Storage
        return PinFailureKind.WrongPin
    }

    /** User-facing message for the non-PIN failure kinds. */
    public fun messageFor(kind: PinFailureKind): String = when (kind) {
        PinFailureKind.WrongPin -> localizedText(Res.string.onb_pin_wrong)
        PinFailureKind.NetworkMismatch -> localizedText(Res.string.onb_pin_err_network)
        PinFailureKind.Storage -> localizedText(Res.string.onb_pin_err_storage)
        PinFailureKind.NewerVersion -> localizedText(Res.string.onb_pin_err_newer)
        PinFailureKind.DeviceKeyMissing -> localizedText(Res.string.onb_pin_err_device_key_missing)
        PinFailureKind.KeystoreUnavailable -> localizedText(Res.string.onb_pin_err_keystore_unavailable)
        PinFailureKind.Other -> localizedText(Res.string.onb_pin_err_unlock_failed)
    }
}
