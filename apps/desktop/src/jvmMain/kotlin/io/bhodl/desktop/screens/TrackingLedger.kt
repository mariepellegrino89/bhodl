package io.bhodl.desktop.screens

import io.bhodl.desktop.data.mergeEventLegs
import io.bhodl.desktop.domain.RecurringCandidate
import io.bhodl.desktop.domain.applyRules
import io.bhodl.desktop.domain.externalInflowSat
import io.bhodl.desktop.domain.externalOutflowSat
import io.bhodl.desktop.domain.externalSignedNetSat
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.RegistryHandle
import io.bhodl.ffi.SavedViewFfi
import io.bhodl.ffi.TrackingRuleFfi

/**
 * Pure helpers behind the tracker's Transactions section and the
 * registry writes its dialogs trigger (extracted from [TrackingScreen]).
 */

/**
 * The txid-deduped [events] restricted to wallets NOT in [excluded].
 * Exclusion is applied to the per-wallet [legs] before dedupe: when the
 * representative leg of a txid sits on an excluded wallet, another
 * included leg takes its place (keeping the category / kind the user
 * set on the transaction) instead of the whole tx disappearing.
 */
internal fun includedEvents(
    events: List<PaymentEvent>,
    legs: List<PaymentEvent>,
    excluded: Set<String>,
): List<PaymentEvent> {
    if (excluded.isEmpty()) return events
    val legsByTxid = legs.groupBy { it.txidOrPaymentId }
    return events.mapNotNull { ev ->
        if (ev.walletId !in excluded) return@mapNotNull ev
        val others = legsByTxid[ev.txidOrPaymentId].orEmpty().filter { it.walletId !in excluded }
        if (others.isEmpty()) return@mapNotNull null
        val rep = mergeEventLegs(others).first()
        if (rep.category == null && ev.category != null || rep.cryptoEventKind == null && ev.cryptoEventKind != null) {
            rep.copy(
                category = rep.category ?: ev.category,
                cryptoEventKind = rep.cryptoEventKind ?: ev.cryptoEventKind,
            )
        } else {
            rep
        }
    }
}

/**
 * Transactions ledger filter: category tri-state (`null` = all, `""` =
 * uncategorised only, other = exact match) AND the optional inclusive
 * time range (mempool events have no timestamp, so a range drops them).
 */
internal fun filterLedger(
    events: List<PaymentEvent>,
    categoryFilter: String?,
    range: Pair<Long, Long>?,
): List<PaymentEvent> {
    val byCategory = when (categoryFilter) {
        null -> events
        "" -> events.filter { it.category.isNullOrBlank() }
        else -> events.filter { it.category == categoryFilter }
    }
    if (range == null) return byCategory
    return byCategory.filter { ev ->
        val ts = ev.timestampUnix?.toLong() ?: return@filter false
        ts in range.first..range.second
    }
}

/**
 * Per-category totals for the Transactions breakdown card, ranked by
 * volume. Null / blank categories form the "Uncategorised" bucket (key
 * `null`). Amounts are EXTERNAL flows: an internal transfer is neither
 * income nor spending (a send_to_self counts only its fee).
 */
internal fun categoryBreakdown(events: List<PaymentEvent>): List<Pair<String?, CategoryTotals>> =
    events.groupBy { it.category?.takeIf(String::isNotBlank) }
        .mapValues { (_, evs) ->
            CategoryTotals(
                incomingSat = evs.sumOf { it.externalInflowSat() },
                outgoingSat = evs.sumOf { it.externalOutflowSat() },
                count = evs.size,
            )
        }
        .toList()
        .sortedWith(
            compareByDescending<Pair<String?, CategoryTotals>> {
                it.second.incomingSat + it.second.outgoingSat
            },
        )

/**
 * Running balance per txid: confirmed events accumulate their external
 * signed net oldest-first; mempool events carry the latest balance.
 */
internal fun runningBalances(events: List<PaymentEvent>): Map<String, Long> {
    val confirmed = events.filter { it.timestampUnix != null }.sortedBy { it.timestampUnix!! }
    var running = 0L
    val map = HashMap<String, Long>()
    for (e in confirmed) {
        running += e.externalSignedNetSat()
        map[e.txidOrPaymentId] = running
    }
    for (e in events) if (e.timestampUnix == null) map[e.txidOrPaymentId] = running
    return map
}

/** What applying a saved view sets on the shared tracker UI state. */
internal data class SavedViewSelection(
    val period: TrackingPeriod,
    val categoryFilter: String?,
    val timeRange: Pair<Long, Long>?,
)

/** Unknown period wire values fall back to D30; a half-open range is dropped. */
internal fun applySavedView(view: SavedViewFfi): SavedViewSelection {
    val start = view.timeRangeStart
    val end = view.timeRangeEnd
    return SavedViewSelection(
        period = TrackingPeriod.entries.firstOrNull { it.name == view.period } ?: TrackingPeriod.D30,
        categoryFilter = view.categoryFilter,
        timeRange = if (start != null && end != null) start to end else null,
    )
}

/** Category dialog save: trimmed, blank clears the row. Throws on registry errors. */
internal fun saveEventCategory(registry: RegistryHandle, event: PaymentEvent, newCategory: String) {
    registry.setEventCategory(
        walletId = event.walletId,
        txidOrPaymentId = event.txidOrPaymentId,
        category = newCategory.trim().ifBlank { null },
    )
}

/**
 * "Reapply rules": every enabled rule runs over the uncategorised
 * [events]; matches are persisted. Returns how many events were written.
 */
internal fun reapplyRules(
    registry: RegistryHandle,
    events: List<PaymentEvent>,
    rules: List<TrackingRuleFfi>,
    labelByWalletId: Map<String, String>,
): Int {
    val enabled = rules.filter { it.enabled }
    var written = 0
    for (ev in events) {
        if (ev.category != null) continue
        val match = applyRules(enabled, ev) { labelByWalletId[it.walletId] } ?: continue
        registry.setEventCategory(ev.walletId, ev.txidOrPaymentId, match)
        written++
    }
    return written
}

/**
 * Accept a recurring suggestion as a bill: name, average amount and the
 * due day of the next estimated occurrence (in [zone]); then dismiss the
 * suggestion. Returns the new bill id.
 */
internal fun acceptRecurringAsBill(
    registry: RegistryHandle,
    candidate: RecurringCandidate,
    zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
): Long {
    val day = java.time.Instant.ofEpochSecond(candidate.nextEstimatedUnix).atZone(zone).dayOfMonth
    val id = registry.addBill(candidate.displayName, candidate.avgAmountSat, day.toUInt(), null)
    // Schema v8 — the bill counts against the group's category.
    candidate.sampleEvents.firstNotNullOfOrNull { it.category?.takeIf { c -> c.isNotBlank() } }
        ?.let { registry.setBillCategory(id, it) }
    registry.dismissRecurring(candidate.payeeKey)
    return id
}
