package io.bhodl.desktop.wizard

import java.io.File
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.onb_wipe_aborted
import io.bhodl.shared.resources.onb_wipe_done
import io.bhodl.shared.resources.onb_wipe_incomplete
import io.bhodl.shared.resources.onb_wipe_nothing
import io.bhodl.shared.resources.onb_wipe_trashed

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

    public fun wipeAll(dataDir: File): WalletPathsView.ResetResult =
        dispose(dataDir) { WalletPaths.from(dataDir).resetChecked() }

    /**
     * Audit 2026-10 — pre-auth "forgot device PIN" reset (Welcome). Same
     * sequence as [wipeAll], but the wallet files are MOVED into a fresh
     * `<trashRoot>/<UTC timestamp>/` folder instead of being deleted, so
     * an accidental or malicious reset from the locked screen is
     * recoverable (move the files back into the data dir). The trash is
     * never emptied by the app.
     */
    public fun moveAllToTrash(
        dataDir: File,
        trashRoot: File = defaultTrashRoot(dataDir),
        now: java.time.Instant = java.time.Instant.now(),
    ): WalletPathsView.ResetResult {
        val stamp = TRASH_STAMP.format(now)
        var target = File(trashRoot, stamp)
        var n = 1
        while (target.exists()) target = File(trashRoot, "$stamp-${n++}")
        trashRoot.mkdirs()
        WalletPaths.restrictToOwner(trashRoot)
        return dispose(dataDir) { WalletPaths.from(dataDir).moveToTrashChecked(target) }
    }

    /** `~/.bhodl-trash` for the default `~/.bhodl` (sibling of [dataDir]). */
    public fun defaultTrashRoot(dataDir: File): File {
        val abs = dataDir.absoluteFile
        return File(abs.parentFile ?: abs, abs.name + TRASH_SUFFIX)
    }

    private const val TRASH_SUFFIX: String = "-trash"

    private val TRASH_STAMP: java.time.format.DateTimeFormatter =
        java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(java.time.ZoneOffset.UTC)

    private fun dispose(
        dataDir: File,
        removeFiles: () -> WalletPathsView.ResetResult,
    ): WalletPathsView.ResetResult {
        WalletStore.clear()
        RegistryStore.clear()
        DevicePinSession.clear()

        val result = removeFiles()
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
        result.movedTo != null -> localizedText(
            Res.string.onb_wipe_trashed,
            result.deleted.size,
            result.movedTo.absolutePath,
        )
        else -> localizedText(Res.string.onb_wipe_done, result.deleted.size)
    }
}
