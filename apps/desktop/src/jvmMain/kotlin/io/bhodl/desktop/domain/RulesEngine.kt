package io.bhodl.desktop.domain

import io.bhodl.ffi.PaymentDirection
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.TrackingRuleFfi

/**
 * Phase 7.5 — categorization rules engine, pure side.
 *
 * Applies a list of rules to a single [PaymentEvent] and returns the
 * matching category (action_value of the first matching rule), or
 * null if no rule matches.
 *
 * Conventions:
 *  - Rules are expected to come **already sorted** by `priority DESC`
 *    (the registry's [io.bhodl.ffi.RegistryHandle.listRules] does this).
 *    The matcher walks top-down and returns at the first hit.
 *  - Disabled rules are filtered out at the call site, NOT here — the
 *    callers can easily reuse the matcher to drive a "preview which
 *    rules would match this tx if I enabled them?" report later.
 *  - Text matching (`contains`, `equals`, `starts_with`, `ends_with`)
 *    is case-insensitive — users don't think about case.
 *
 * Field mapping:
 *  - `condition_field = "amount"` matches against
 *    [PaymentEvent.amountSat] (unsigned sat, direction-agnostic);
 *    `"amount_in"` / `"amount_out"` do the same for incoming /
 *    outgoing events only.
 *  - `condition_field = "description"` matches against
 *    [PaymentEvent.txidOrPaymentId]. BHODL doesn't have BIP-329
 *    labels surfaced into PaymentEvent yet (Phase 3.x parked that),
 *    so we degrade gracefully to the only text field we have.
 *  - `condition_field = "payee"` (UX review 2026-10, item 11) matches
 *    the event's payee text — the label of the wallet it belongs to,
 *    supplied by the caller through `payeeOf` (on-chain there is no
 *    counterparty; a purpose wallet like "Daily spending" is the
 *    closest thing). The txid still matches too, so a payee rule
 *    written against the old txid-only fallback keeps working.
 */
internal fun applyRules(
    rules: List<TrackingRuleFfi>,
    event: PaymentEvent,
    payeeOf: (PaymentEvent) -> String? = { null },
): String? {
    for (rule in rules) {
        if (!rule.enabled) continue
        if (rule.actionField != "category") continue
        if (matchesRule(rule, event, payeeOf(event))) {
            return rule.actionValue
        }
    }
    return null
}

/**
 * Phase 7.5 — pure match of a single rule against an event. Exposed
 * `internal` so tests can target it directly without going through
 * the iteration in [applyRules].
 */
internal fun matchesRule(
    rule: TrackingRuleFfi,
    event: PaymentEvent,
    payee: String? = null,
): Boolean {
    return when (rule.conditionField) {
        "amount" -> matchAmount(rule.conditionOp, rule.conditionValue, event.amountSat)
        // Directional amount (item 11): only events going that way.
        "amount_in" -> event.direction == PaymentDirection.INCOMING &&
            matchAmount(rule.conditionOp, rule.conditionValue, event.amountSat)
        "amount_out" -> event.direction == PaymentDirection.OUTGOING &&
            matchAmount(rule.conditionOp, rule.conditionValue, event.amountSat)
        "description" -> matchText(
            op = rule.conditionOp,
            target = rule.conditionValue,
            // V1 fallback: use the txid string as the only text we
            // have on a PaymentEvent. See file-level doc.
            haystack = event.txidOrPaymentId,
        )
        "payee" -> (payee != null && matchText(rule.conditionOp, rule.conditionValue, payee)) ||
            matchText(rule.conditionOp, rule.conditionValue, event.txidOrPaymentId)
        else -> false
    }
}

/** Numeric rule fields (number ops, sat value): `amount`, `amount_in`, `amount_out`. */
internal fun isAmountField(field: String): Boolean =
    field == "amount" || field == "amount_in" || field == "amount_out"

private fun matchAmount(op: String, value: String, amountSat: ULong): Boolean {
    val needle = value.toULongOrNull() ?: return false
    return when (op) {
        "gt" -> amountSat > needle
        "lt" -> amountSat < needle
        "eq" -> amountSat == needle
        else -> false
    }
}

private fun matchText(op: String, target: String, haystack: String): Boolean {
    val h = haystack.lowercase()
    val t = target.lowercase()
    return when (op) {
        "contains" -> h.contains(t)
        "equals" -> h == t
        "starts_with" -> h.startsWith(t)
        "ends_with" -> h.endsWith(t)
        else -> false
    }
}
