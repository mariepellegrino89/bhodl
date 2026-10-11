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

/** How a pending transaction can be accelerated, if at all. */
public enum class SpeedUpKind {
    /** Outgoing tx the wallet signed: replace it with a higher fee. */
    Rbf,

    /** Incoming tx: spend its output in a child that pays for both. */
    CpfpIncoming,
}

/**
 * Speed-up affordance for [tx]. Keyed on the sign of `netSat`, never on
 * `feeSat` (Esplora fills `feeSat` for incoming txs too — see the
 * 2026-05-11 regression in HistoryScreenTest).
 */
public fun speedUpKindOf(tx: TransactionRecord): SpeedUpKind? = when {
    tx.confirmationHeight != null -> null
    tx.netSat < 0 -> SpeedUpKind.Rbf
    tx.netSat > 0 -> SpeedUpKind.CpfpIncoming
    else -> null
}

/**
 * One top-level row of the Transactions table: a transaction plus the
 * transactions it replaced through fee bumps (oldest first), which are
 * rendered collapsed under it with a "Fee bumped" chip.
 */
public data class TxGroup(
    val tx: TransactionRecord,
    val replaced: List<TransactionRecord> = emptyList(),
) {
    val feeBumped: Boolean get() = replaced.isNotEmpty()
}

/**
 * Group [txs] so every replaced transaction sits under the transaction
 * that finally replaced it.
 *
 * [replacedBy] maps an original txid to its replacement txid (a chain
 * A→B→C collapses A and B under C). [snapshots] are the originals as
 * they looked when they were bumped: BDK drops a replaced tx from its
 * canonical history, so the snapshot is the only record left of it.
 * A replacement that is no longer listed (itself dropped) leaves its
 * originals as ordinary rows.
 */
public fun groupReplacements(
    txs: List<TransactionRecord>,
    replacedBy: Map<String, String>,
    snapshots: Map<String, TransactionRecord> = emptyMap(),
): List<TxGroup> {
    val listed = txs.associateBy { it.txid }

    // Follow the chain to the last replacement that is still listed.
    fun finalReplacement(txid: String): String? {
        var current = txid
        var final: String? = null
        val seen = mutableSetOf(txid)
        while (true) {
            val next = replacedBy[current] ?: break
            if (!seen.add(next)) break // defensive: a cycle ends the walk
            if (next in listed) final = next
            current = next
        }
        return final
    }

    val children = mutableMapOf<String, MutableList<TransactionRecord>>()
    val topLevel = mutableListOf<TransactionRecord>()
    for (tx in txs) {
        val parent = finalReplacement(tx.txid)
        if (parent == null) topLevel += tx else children.getOrPut(parent) { mutableListOf() } += tx
    }
    // Originals BDK no longer lists, known only from the bump log.
    for ((original, snapshot) in snapshots) {
        if (original in listed) continue
        val parent = finalReplacement(original) ?: continue
        children.getOrPut(parent) { mutableListOf() } += snapshot
    }
    return topLevel.map { tx ->
        TxGroup(
            tx = tx,
            replaced = children[tx.txid].orEmpty()
                .distinctBy { it.txid }
                .sortedBy { it.timestamp ?: ULong.MAX_VALUE },
        )
    }
}

/** Sortable columns of the Transactions table. */
public enum class TxSortColumn { DATE, LABEL, AMOUNT, STATUS, CONFIRMATIONS }

/** Direction / state filter chips of the Transactions table. */
public enum class TxFilter { ALL, INCOMING, OUTGOING, PENDING }

/**
 * Sort [groups] by [column]. Pending transactions (no timestamp / no
 * height) count as the newest / least-confirmed so they surface first
 * in the default newest-first order. Ties fall back to the txid so the
 * order is stable across refreshes.
 */
public fun sortTxGroups(
    groups: List<TxGroup>,
    column: TxSortColumn,
    ascending: Boolean,
    labelOf: (String) -> String = { "" },
    tipHeight: UInt? = null,
): List<TxGroup> {
    val comparator: Comparator<TxGroup> = when (column) {
        TxSortColumn.DATE -> compareBy { it.tx.timestamp ?: ULong.MAX_VALUE }
        TxSortColumn.LABEL -> compareBy<TxGroup> { labelOf(it.tx.txid).isBlank() }
            .thenBy { labelOf(it.tx.txid).lowercase() }
        TxSortColumn.AMOUNT -> compareBy { it.tx.netSat }
        TxSortColumn.STATUS, TxSortColumn.CONFIRMATIONS -> compareBy {
            // Pending = 0, unknown tip = "very confirmed".
            confirmationsOf(it.tx.confirmationHeight, tipHeight) ?: Int.MAX_VALUE
        }
    }
    val directed = if (ascending) comparator else comparator.reversed()
    return groups.sortedWith(directed.thenBy { it.tx.txid })
}

/**
 * Apply the filter chip and the free-text [query] (matched against the
 * label, category and txid, case-insensitive) to [groups].
 */
public fun filterTxGroups(
    groups: List<TxGroup>,
    filter: TxFilter,
    query: String,
    labelOf: (String) -> String = { "" },
    categoryOf: (String) -> String? = { null },
): List<TxGroup> {
    val q = query.trim().lowercase()
    return groups.filter { g ->
        val tx = g.tx
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
