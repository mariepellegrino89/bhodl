package io.bhodl.desktop.data

import io.bhodl.desktop.wallet.WalletPort
import io.bhodl.desktop.wizard.RegistryStore
import io.bhodl.ffi.RegistryHandle
import io.bhodl.ffi.WalletKindFfi
import io.bhodl.ffi.WalletRecordFfi

/**
 * Security review 2026-10 — where a tracker category set from the
 * signing wallet (Send form, PayJoin, label-first Receive) must land.
 *
 * The tracker never reads the signing wallet itself: it lists events of
 * the watch-only rows (`listWatchEvents`) and looks categories up by
 * `(watch wallet id, txid)`. A category stored under the signing
 * wallet's id is therefore invisible. The rows that represent the
 * signing wallet in the tracker are the watch rows of the same account
 * — the `mirror_of` mirror or an xpub imported by hand — matched by
 * account xpub, like [io.bhodl.desktop.screens.isAlreadyTrackedExcluding].
 *
 * When the signing wallet is not tracked at all the signing id is
 * returned, so the category is not lost (previous behaviour).
 */
internal fun trackerWalletIdsFor(
    rows: List<WalletRecordFfi>,
    signingId: String,
    signingXpub: String?,
    xpubOf: (descriptorPub: String) -> String?,
): List<String> {
    val mirrors = if (signingXpub == null) {
        emptyList()
    } else {
        rows.filter { row ->
            row.id != signingId &&
                row.kind != WalletKindFfi.SIGNING &&
                xpubOf(row.descriptorPub) == signingXpub
        }.map { it.id }
    }
    return mirrors.ifEmpty { listOf(signingId) }
}

/**
 * Write [category] for [txid] under every tracker row of the signing
 * wallet [signingId] (see [trackerWalletIdsFor]). Best-effort, blocking:
 * call it off the UI thread. Bumps the registry data version so the
 * tracker re-reads.
 */
internal fun writeTrackerCategory(
    registry: RegistryHandle,
    wallet: WalletPort,
    signingId: String,
    txid: String,
    category: String,
) {
    val signingXpub = runCatching {
        io.bhodl.ffi.extractAccountXpub(wallet.trackerDescriptorPub())
    }.getOrNull()
    val rows = runCatching { registry.listWallets() }.getOrDefault(emptyList())
    val targets = trackerWalletIdsFor(rows, signingId, signingXpub) { desc ->
        runCatching { io.bhodl.ffi.extractAccountXpub(desc) }.getOrNull()
    }
    for (id in targets) {
        runCatching { registry.setEventCategory(walletId = id, txidOrPaymentId = txid, category = category) }
    }
    RegistryStore.bumpDataVersion()
}
