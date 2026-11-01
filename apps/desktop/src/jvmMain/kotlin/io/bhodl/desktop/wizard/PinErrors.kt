package io.bhodl.desktop.wizard

import io.bhodl.ffi.WalletException
import io.bhodl.shared.resources.Res
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
 * Since the 2026-11 error-typing change the Rust core reports the
 * decision-critical kinds as typed variants (`StorageWrongPin`,
 * `StorageNewerSchema`, `StorageIo`), so this classifier switches on
 * the exception type — no substring matching on `detail` remains here.
 *
 * Classification is fail-closed: only errors we positively recognise as
 * non-PIN problems are excluded from the lockout counter; an unknown
 * `Storage` / `KeyDerivation` detail is still treated as a wrong PIN,
 * exactly like an unrecognised detail was before.
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

    /** Anything else (non-FFI exception, programming error, …). */
    Other,
}

public object PinErrors {

    public fun classify(e: Throwable): PinFailureKind = when (e) {
        // Typed variants from the Rust core (decided there, by enum).
        is WalletException.StorageWrongPin -> PinFailureKind.WrongPin
        is WalletException.StorageNewerSchema -> PinFailureKind.NewerVersion
        is WalletException.StorageIo -> PinFailureKind.Storage
        // Fail-closed fallbacks: an unrecognised Storage or
        // KeyDerivation detail is treated as a wrong PIN, like before.
        is WalletException.Storage -> PinFailureKind.WrongPin
        is WalletException.KeyDerivation -> PinFailureKind.WrongPin
        is WalletException.Io -> PinFailureKind.Storage
        is WalletException.InvalidSalt -> PinFailureKind.Storage
        is WalletException -> PinFailureKind.Other
        else -> PinFailureKind.Other
    }

    /** User-facing message for the non-PIN failure kinds. */
    public fun messageFor(kind: PinFailureKind): String = when (kind) {
        PinFailureKind.WrongPin -> localizedText(Res.string.onb_pin_wrong)
        PinFailureKind.NetworkMismatch -> localizedText(Res.string.onb_pin_err_network)
        PinFailureKind.Storage -> localizedText(Res.string.onb_pin_err_storage)
        PinFailureKind.NewerVersion -> localizedText(Res.string.onb_pin_err_newer)
        PinFailureKind.Other -> localizedText(Res.string.onb_pin_err_unlock_failed)
    }
}
