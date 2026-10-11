package io.bhodl.desktop.wizard

import io.bhodl.ffi.RegistryHandle

/**
 * Shared validation helpers for wallet labels (the human-readable
 * names shown in the Welcome picker, the Settings rename section,
 * the future tracking screens).
 *
 * Labels must be:
 * - non-blank after `trim()`
 * - unique across the registry, case-insensitive (`"My Wallet"`
 *   collides with `"my wallet"` — they look identical in the picker
 *   list, and forcing the user to disambiguate by capitalisation
 *   alone is a footgun).
 *
 * Lives in `wizard/` next to the stores it queries.
 */
public object WalletLabel {

    /**
     * `true` when `candidate.trim()` matches the label of any wallet
     * in the registry under case-insensitive comparison, excluding
     * the row identified by `excludeWalletId` (used by the rename
     * flow to allow keeping the same label or restoring the original
     * after an in-progress edit).
     *
     * Throws nothing: a failed `listWallets()` is propagated by
     * `RegistryHandle` — callers run this off the UI thread (it's
     * a SQLCipher read) and surface any exception as a generic
     * "validation failed" error.
     */
    public fun isDuplicate(
        registry: RegistryHandle,
        candidate: String,
        excludeWalletId: String? = null,
    ): Boolean {
        val target = candidate.trim()
        if (target.isEmpty()) return false
        return registry.listWallets().any { row ->
            row.id != excludeWalletId &&
                row.label.trim().equals(target, ignoreCase = true)
        }
    }
}
