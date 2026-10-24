package io.bhodl.desktop.wizard

import java.io.File
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.onb_wipe_aborted
import io.bhodl.shared.resources.onb_wipe_done
import io.bhodl.shared.resources.onb_wipe_incomplete
import io.bhodl.shared.resources.onb_wipe_nothing

/**
 * Security audit 2026-10 (M8) — the single "erase everything on this
 * device" routine, shared by Welcome → "Reset all local wallets" and
 * Settings → "Factory reset".
 *
 * Order matters on Windows, where an open SQLCipher connection makes
 * its file undeletable:
 *  1. release EVERY in-memory handle first — the open wallet port and
 *     the registry handle (its clear() also wipes the device-PIN
 *     session and cancels the backfill);
 *  2. [WalletPathsView.resetChecked] deletes databases BEFORE their KDF
 *     descriptors and aborts if a database survives;
 *  3. the PIN-failure history file is removed and the in-memory
 *     tracker reset (it belongs to wallets that no longer exist).
 *
 * Blocking — call from Dispatchers.IO.
 */
public object LocalWipe {

    /** Phrase the user must type to confirm a full wipe. */
    public const val CONFIRM_PHRASE: String = "DELETE"

    /** `true` iff [typed] matches [CONFIRM_PHRASE] (trimmed, case-sensitive). */
    public fun isConfirmed(typed: String): Boolean = typed.trim() == CONFIRM_PHRASE

    public fun wipeAll(dataDir: File): WalletPathsView.ResetResult {
        WalletStore.clear()
        RegistryStore.clear()
        DevicePinSession.clear()

        val result = WalletPaths.from(dataDir).resetChecked()
        if (!result.aborted) {
            val failures = File(dataDir, PinAttemptTracker.PIN_FAILURES_FILE_NAME)
            if (failures.exists() && !failures.delete()) {
                return result.copy(failed = result.failed + failures)
            }
            PinAttemptTracker.reset()
            // reset() re-persists an empty snapshot when persistence is
            // on; drop that file too so nothing about the old install
            // remains.
            failures.delete()
            // Security review (item 14) — the Security Hub dates and
            // reminder snoozes describe wallets that no longer exist.
            io.bhodl.desktop.settings.SettingsStore.clearSecurityState()
        }
        return result
    }

    /** Short user-facing summary of a [wipeAll] outcome. */
    public fun describe(result: WalletPathsView.ResetResult): String = when {
        result.aborted -> localizedText(Res.string.onb_wipe_aborted, result.failed.size)
        result.failed.isNotEmpty() -> localizedText(
            Res.string.onb_wipe_incomplete,
            result.failed.size,
            result.failed.joinToString { it.name },
        )
        result.deleted.isEmpty() -> localizedText(Res.string.onb_wipe_nothing)
        else -> localizedText(Res.string.onb_wipe_done, result.deleted.size)
    }
}
