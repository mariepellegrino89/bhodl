package io.bhodl.desktop.wallet

import androidx.compose.runtime.mutableStateMapOf
import io.bhodl.ffi.TransactionRecord

/**
 * UX review 2026-10 (§4 item 8) — session log of the RBF replacements
 * made from this device (original txid → replacement txid), filled by
 * the Bump fee flow on a successful broadcast. The Transactions tab
 * uses it to collapse a replaced transaction under its replacement
 * with a "Fee bumped" chip (see `groupReplacements`).
 *
 * In-memory only: txids are never written to the unencrypted settings
 * file, and the log is dropped with the wallet session
 * ([io.bhodl.desktop.wizard.WalletStore.clear]). After a restart BDK's
 * canonical history simply shows the replacement on its own.
 */
public object FeeBumpLog {
    private val replacedBy = mutableStateMapOf<String, String>()
    private val snapshots = mutableStateMapOf<String, TransactionRecord>()

    /**
     * Record that [originalTxid] was replaced by [replacementTxid].
     * [original] is the original as last listed — BDK drops a replaced
     * transaction from its history, so this snapshot is what the
     * collapsed row shows.
     */
    public fun record(original: TransactionRecord?, originalTxid: String, replacementTxid: String) {
        replacedBy[originalTxid] = replacementTxid
        if (original != null) snapshots[originalTxid] = original
    }

    public fun replacedBy(): Map<String, String> = replacedBy.toMap()

    public fun snapshots(): Map<String, TransactionRecord> = snapshots.toMap()

    public fun clear() {
        replacedBy.clear()
        snapshots.clear()
    }
}
