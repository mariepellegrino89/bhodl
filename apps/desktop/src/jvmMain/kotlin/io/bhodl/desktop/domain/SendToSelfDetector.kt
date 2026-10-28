package io.bhodl.desktop.domain

import io.bhodl.ffi.CryptoEventKindRow
import io.bhodl.ffi.PaymentDirection
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.PaymentKind
import io.bhodl.ffi.WalletKindFfi
import io.bhodl.ffi.WalletRecordFfi

/**
 * Phase 7.10 — auto-detect `send_to_self` transfers.
 *
 * A transaction is an internal transfer when it moves funds ONLY between
 * wallets the user owns: at least one registered wallet is debited and
 * every output lands in a registered wallet, so the only value leaving
 * custody is the mining fee. Classifying it keeps the KPI inflow/outflow
 * figures honest and drives the badge in the events list.
 *
 * ## Input contract
 *
 * Callers pass the PRE-dedup-by-txid stream (one event per wallet leg),
 * but AFTER collapsing wallet aliases with [dropAliasedEvents]: two
 * registry rows tracking the same account (e.g. a WatchHd mirror and a
 * WatchXpub of the same xpub) must count as ONE wallet, otherwise every
 * transaction of that account would look like a transfer between "two"
 * wallets. (That was audit finding H13: an older build merged a signing
 * wallet's events with its paired watch mirror, so every tx was
 * persisted as send_to_self.)
 *
 * ## Rule
 *
 * For a txid seen in ≥ 2 distinct wallets, with legs `net_i` (signed
 * per-wallet delta) and a known fee:
 *  - at least one leg is a debit (OUTGOING), and
 *  - `Σ net_i == −fee` — i.e. the external flow is exactly the fee: no
 *    output went to a third party (a payment with change to another own
 *    wallet fails this, as does a coinjoin / payjoin with outside
 *    inputs). An unknown fee means we can't prove it → not tagged.
 *
 * ## Idempotence / user overrides
 *
 * Only legs whose stored kind is null are returned, so any stored kind
 * (user-set or an earlier auto-tag) is never overwritten. Since registry
 * schema v3 a user's explicit "regular" is PERSISTED (`user_set = 1`)
 * instead of deleting the row: it arrives here as a non-null kind and is
 * skipped, and the registry's auto write path
 * (`setEventCryptoKindAuto`) refuses pinned rows as a second guard — a
 * user who resets an auto-detected transfer to "regular" is no longer
 * re-tagged on the next refresh.
 *
 * @return the subset of [events] to mark `send_to_self`, by reference
 *   (callers mutate them so the UI badge appears without a round-trip).
 */
internal fun detectSendToSelf(events: List<PaymentEvent>): List<PaymentEvent> {
    val legsByTxid = LinkedHashMap<String, MutableList<PaymentEvent>>()
    for (ev in events) {
        if (ev.kind != PaymentKind.ON_CHAIN) continue
        legsByTxid.getOrPut(ev.txidOrPaymentId) { mutableListOf() }.add(ev)
    }
    val qualifying = HashSet<String>()
    for ((txid, legs) in legsByTxid) {
        if (isInternalTransfer(legs)) qualifying += txid
    }
    return events.filter { ev ->
        ev.kind == PaymentKind.ON_CHAIN &&
            ev.txidOrPaymentId in qualifying &&
            ev.cryptoEventKind == null
    }
}

/**
 * True when the legs of ONE txid prove an internal transfer (see the
 * rule on [detectSendToSelf]). Duplicate legs for the same wallet id
 * are collapsed (first wins).
 */
internal fun isInternalTransfer(legs: List<PaymentEvent>): Boolean {
    val perWallet = legs.distinctBy { it.walletId }
    if (perWallet.size < 2) return false
    if (perWallet.none { it.direction == PaymentDirection.OUTGOING }) return false
    val fee = perWallet.mapNotNull { it.feeSat }.maxOrNull() ?: return false
    var sumNet = 0L
    for (leg in perWallet) {
        sumNet += when (leg.direction) {
            PaymentDirection.INCOMING -> leg.amountSat.toLong()
            PaymentDirection.OUTGOING -> -leg.amountSat.toLong()
            PaymentDirection.SELF_TRANSFER -> 0L
        }
    }
    // Exact sat arithmetic: external flow == 0 ⇔ Σnet == −fee.
    return sumNet == -fee.toLong()
}

/**
 * Map every wallet id to the CANONICAL id of the account it
 * tracks. Rows whose [accountKey] (the account xpub, or the descriptor
 * itself when no xpub can be extracted) match are aliases of the same
 * wallet; the oldest row (createdAt, then id) is canonical.
 */
internal fun canonicalWalletIds(
    wallets: List<WalletRecordFfi>,
    accountKey: (WalletRecordFfi) -> String?,
): Map<String, String> {
    val groups = wallets.groupBy { w ->
        if (w.kind == WalletKindFfi.WATCH_ADDRESSES) {
            "addr:" + w.descriptorPub.trim()
        } else {
            "acct:" + (accountKey(w) ?: w.descriptorPub.trim())
        }
    }
    val out = HashMap<String, String>()
    for (members in groups.values) {
        val canonical = members.minWith(compareBy<WalletRecordFfi>({ it.createdAtUnix }, { it.id }))
        for (m in members) out[m.id] = canonical.id
    }
    return out
}

/**
 * Drop events emitted by alias (non-canonical) wallet rows so the same
 * account is counted once. Events from wallet ids absent from
 * [canonical] are kept as-is.
 */
internal fun dropAliasedEvents(
    events: List<PaymentEvent>,
    canonical: Map<String, String>,
): List<PaymentEvent> = events.filter { ev ->
    val c = canonical[ev.walletId] ?: return@filter true
    c == ev.walletId
}

/**
 * One-time cleanup for the H13 over-tagging: `send_to_self` rows that the
 * stricter detector would never have produced. User-pinned rows
 * ([CryptoEventKindRow.userSet], registry schema v3) are never touched.
 * Among auto rows we only reset those that are certainly artefacts:
 *  - rows keyed by an alias (non-canonical) watch row,
 *  - rows whose txid, after alias dedup, appears in a single real wallet
 *    (a transfer between "two" wallets needs two wallets).
 *
 * @return the `(walletId, txid)` pairs to reset to "regular".
 */
internal fun staleSendToSelfTags(
    rows: List<CryptoEventKindRow>,
    canonical: Map<String, String>,
    dedupedEvents: List<PaymentEvent>,
): List<Pair<String, String>> {
    val walletsPerTxid = HashMap<String, MutableSet<String>>()
    for (ev in dedupedEvents) {
        if (ev.kind != PaymentKind.ON_CHAIN) continue
        walletsPerTxid.getOrPut(ev.txidOrPaymentId) { HashSet() }.add(ev.walletId)
    }
    return rows
        .filter { it.kind == SEND_TO_SELF && !it.userSet }
        .filter { row ->
            when {
                canonical[row.walletId]?.let { it != row.walletId } == true -> true
                canonical.containsKey(row.walletId) ->
                    (walletsPerTxid[row.txidOrPaymentId]?.size ?: 0) <= 1
                else -> false // unknown / removed wallet: leave untouched
            }
        }
        .map { it.walletId to it.txidOrPaymentId }
}

/** The `crypto_event_kind` value written for a detected internal transfer. */
internal const val SEND_TO_SELF: String = "send_to_self"

/**
 * The registry's implicit default kind. Via the user path it is stored
 * (pinned); via the auto path it deletes an unpinned row.
 */
internal const val CRYPTO_KIND_REGULAR: String = "regular"
