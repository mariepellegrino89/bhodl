package io.bhodl.desktop.screens

import io.bhodl.ffi.AddressRecord
import io.bhodl.ffi.TransactionRecord

/**
 * UX review 2026-10 (§4 item 8) — pure model behind the wallet's
 * Transactions tab: transaction lifecycle states, replacement (RBF)
 * grouping, sorting and filtering. No Compose, no FFI calls, so every
 * rule here is unit-tested in isolation.
 */

/**
 * Bitcoin Design Guide activity states. Completed transactions carry
 * **no** badge; only in-flight ones do.
 */
public sealed interface TxStatus {
    /** In the mempool, zero confirmations. */
    public data object Pending : TxStatus

    /**
     * Mined, 1 to [FINAL_CONFIRMATIONS] − 1 confirmations. `null` =
     * mined but the count is unknown (tip not synced / behind the
     * block): never claimed as final.
     */
    public data class Confirming(val confirmations: Int?) : TxStatus

    /** [FINAL_CONFIRMATIONS] or more, on a known tip: no badge. */
    public data object Confirmed : TxStatus
}

/** The BDG treats six confirmations as final. */
public const val FINAL_CONFIRMATIONS: Int = 6

/**
 * Confirmation count of a transaction mined at [confirmationHeight]
 * given the wallet's synced [tipHeight]: `0` while pending, `null`
 * when the tip is unknown (never synced) or lags the block the tx was
 * seen in (a stale tip must not produce a negative count).
 */
public fun confirmationsOf(confirmationHeight: UInt?, tipHeight: UInt?): Int? {
    if (confirmationHeight == null) return 0
    if (tipHeight == null || tipHeight == 0u || tipHeight < confirmationHeight) return null
    return (tipHeight - confirmationHeight + 1u).toInt()
}

/**
 * Pending → Confirming (1–5) → Confirmed. With an unknown tip a mined
 * transaction is "Confirming" without a count — never "Confirmed".
 */
public fun txStatusOf(confirmationHeight: UInt?, tipHeight: UInt?): TxStatus {
    if (confirmationHeight == null) return TxStatus.Pending
    val confs = confirmationsOf(confirmationHeight, tipHeight) ?: return TxStatus.Confirming(null)
    return if (confs >= FINAL_CONFIRMATIONS) TxStatus.Confirmed else TxStatus.Confirming(confs)
}

/** Sortable columns of the Transactions table. */
public enum class TxSortColumn { DATE, LABEL, AMOUNT, STATUS, CONFIRMATIONS }

/** Direction / state filter chips of the Transactions table. */
public enum class TxFilter { ALL, INCOMING, OUTGOING, PENDING }

/**
 * Sort [txs] by [column]. Pending transactions (no timestamp / no
 * height) count as the newest / least-confirmed so they surface first
 * in the default newest-first order. Ties fall back to the txid so the
 * order is stable across refreshes.
 */
public fun sortTransactions(
    txs: List<TransactionRecord>,
    column: TxSortColumn,
    ascending: Boolean,
    labelOf: (String) -> String = { "" },
    tipHeight: UInt? = null,
): List<TransactionRecord> {
    val comparator: Comparator<TransactionRecord> = when (column) {
        TxSortColumn.DATE -> compareBy { it.timestamp ?: ULong.MAX_VALUE }
        TxSortColumn.LABEL -> compareBy<TransactionRecord> { labelOf(it.txid).isBlank() }
            .thenBy { labelOf(it.txid).lowercase() }
        TxSortColumn.AMOUNT -> compareBy { it.netSat }
        TxSortColumn.STATUS, TxSortColumn.CONFIRMATIONS -> compareBy {
            // Pending = 0, unknown tip = "very confirmed".
            confirmationsOf(it.confirmationHeight, tipHeight) ?: Int.MAX_VALUE
        }
    }
    val directed = if (ascending) comparator else comparator.reversed()
    return txs.sortedWith(directed.thenBy { it.txid })
}

/**
 * Apply the filter chip and the free-text [query] (matched against the
 * label, category and txid, case-insensitive) to [txs].
 */
public fun filterTransactions(
    txs: List<TransactionRecord>,
    filter: TxFilter,
    query: String,
    labelOf: (String) -> String = { "" },
    categoryOf: (String) -> String? = { null },
): List<TransactionRecord> {
    val q = query.trim().lowercase()
    return txs.filter { tx ->
        val passesFilter = when (filter) {
            TxFilter.ALL -> true
            TxFilter.INCOMING -> tx.netSat > 0
            TxFilter.OUTGOING -> tx.netSat < 0
            TxFilter.PENDING -> tx.confirmationHeight == null
        }
        passesFilter && (
            q.isEmpty() ||
                tx.txid.lowercase().contains(q) ||
                labelOf(tx.txid).lowercase().contains(q) ||
                (categoryOf(tx.txid)?.lowercase()?.contains(q) ?: false)
            )
    }
}

// ─── Label-first receive ────────────────────────────────────────────────

/**
 * `true` when [address] has already received funds: showing it again
 * for a new payment links the two payers (BDG privacy guidance).
 */
public fun isReusedAddress(address: String, addresses: List<AddressRecord>): Boolean =
    addresses.any { it.address == address && it.used }
