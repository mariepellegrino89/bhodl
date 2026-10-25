package io.bhodl.desktop.domain

import io.bhodl.ffi.PaymentDirection
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.TrackingRuleFfi

/**
 * UX review 2026-10 (item 11) — "create rule from edit" and explicit
 * rule ordering.
 *
 * When the user re-categorises a transaction, the tracker offers
 * "Always do this for <pattern>?" (Monarch's rule widget, Actual's
 * learned rules) with a preview of how many EXISTING transactions the
 * rule would categorise. The patterns BHODL can offer are the text the
 * tracker actually has for an on-chain event:
 *
 *  - [RulePatternKind.WALLET] — the wallet the transaction belongs to,
 *    stored as `payee equals <wallet label>` (the tracker has no
 *    counterparty on-chain; a purpose wallet such as "Daily spending"
 *    is the closest thing to a payee);
 *  - [RulePatternKind.AMOUNT] — the exact sat amount (`amount eq N`),
 *    which catches fixed sats-denominated subscriptions.
 *
 * Preview and apply follow the same policy as the import-time matcher:
 * a rule only fills UNCATEGORISED events; a category the user set by
 * hand is never overwritten.
 */
internal enum class RulePatternKind { WALLET, AMOUNT }

/** A rule not yet persisted (the fields `addRule` needs). */
internal data class RuleDraft(
    val name: String,
    val conditionField: String,
    val conditionOp: String,
    val conditionValue: String,
    val category: String,
) {
    /** In-memory rule for [matchesRule]; id / priority are irrelevant. */
    fun asRule(): TrackingRuleFfi = TrackingRuleFfi(
        id = -1,
        name = name,
        conditionField = conditionField,
        conditionOp = conditionOp,
        conditionValue = conditionValue,
        actionField = "category",
        actionValue = category,
        priority = 0,
        enabled = true,
        createdAtUnix = 0,
    )
}

/**
 * Build the rule the "Always do this?" prompt would create for [event]
 * re-categorised as [category]. Null when the pattern is not available
 * (no wallet label for [RulePatternKind.WALLET], zero amount for
 * [RulePatternKind.AMOUNT]) or the category is blank.
 */
internal fun ruleDraftFromEdit(
    event: PaymentEvent,
    category: String,
    walletLabel: String?,
    kind: RulePatternKind,
): RuleDraft? {
    val cat = category.trim().takeIf { it.isNotEmpty() } ?: return null
    return when (kind) {
        RulePatternKind.WALLET -> {
            val label = walletLabel?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            RuleDraft("$label → $cat", "payee", "equals", label, cat)
        }
        RulePatternKind.AMOUNT -> {
            if (event.amountSat == 0UL) return null
            // Same direction as the edited event: a 21,000 sat payment
            // rule must not catch a 21,000 sat incoming refund.
            val field = when (event.direction) {
                PaymentDirection.INCOMING -> "amount_in"
                PaymentDirection.OUTGOING -> "amount_out"
                else -> return null
            }
            // Security review (item 13) — the stored name is unit-neutral: a
            // raw "21000 sat → cat" name would print the amount in the rules
            // list even in privacy mode. The amount is the condition value,
            // rendered through the (masked) formatter.
            RuleDraft("$EXACT_AMOUNT_RULE_NAME → $cat", field, "eq", event.amountSat.toString(), cat)
        }
    }
}

/**
 * Existing events the draft would categorise: uncategorised (category
 * null or blank), matching the draft, excluding the [edited]
 * transaction. The exclusion is by txid — the same key the tracker
 * dedupes wallet rows sharing an account on — so a duplicate row of the
 * edited transaction is neither counted nor written. One entry
 * per `(wallet, txid)` row — the unit the registry stores a category
 * on — so applying writes each row exactly once.
 */
internal fun ruleDraftMatches(
    draft: RuleDraft,
    events: List<PaymentEvent>,
    payeeOf: (PaymentEvent) -> String?,
    edited: PaymentEvent? = null,
): List<PaymentEvent> {
    val rule = draft.asRule()
    return events
        .asSequence()
        .filter { it.category.isNullOrBlank() }
        .filter { edited == null || it.txidOrPaymentId != edited.txidOrPaymentId }
        .filter { matchesRule(rule, it, payeeOf(it)) }
        .distinctBy { it.walletId to it.txidOrPaymentId }
        .toList()
}

/**
 * The count shown as "applies to N existing transactions": distinct
 * transactions (two rows tracking the same account see the same txid
 * twice; the user sees one transaction).
 */
internal fun ruleDraftPreviewCount(matches: List<PaymentEvent>): Int =
    matches.map { it.txidOrPaymentId }.distinct().size

// ─── Explicit ordering ────────────────────────────────────────────────

/**
 * The order the matcher walks: priority DESC, then created ASC, then id
 * — the registry's `list_rules` ORDER BY, made total.
 */
internal fun rulesInEvaluationOrder(rules: List<TrackingRuleFfi>): List<TrackingRuleFfi> =
    rules.sortedWith(
        compareByDescending<TrackingRuleFfi> { it.priority }
            .thenBy { it.createdAtUnix }
            .thenBy { it.id },
    )

/** Priority that puts a new rule at the top of the list (evaluated first). */
internal fun topRulePriority(rules: List<TrackingRuleFfi>): Long =
    (rules.maxOfOrNull { it.priority } ?: -1L) + 1L

/**
 * Move [ruleId] one step up ([delta] = −1) or down (+1) in evaluation
 * order. Returns the COMPLETE new order of rule ids (first evaluated
 * first) for the registry's `reorderRules`, which renumbers every
 * priority to `n−1 … 0` in one transaction — so the list order and the
 * stored order can never disagree (ties made the old 0–10 slider
 * ambiguous). Null when the move is a no-op (top / bottom edge,
 * unknown id).
 */
internal fun moveRule(
    rules: List<TrackingRuleFfi>,
    ruleId: Long,
    delta: Int,
): List<Long>? {
    val ordered = rulesInEvaluationOrder(rules).map { it.id }.toMutableList()
    val from = ordered.indexOf(ruleId)
    if (from < 0) return null
    val to = from + delta
    if (to !in ordered.indices || to == from) return null
    ordered.add(to, ordered.removeAt(from))
    return ordered
}

/** The priorities `reorderRules` stores for [orderedIds]: `n−1 … 0`. */
internal fun prioritiesForOrder(orderedIds: List<Long>): Map<Long, Long> =
    orderedIds.withIndex().associate { (idx, id) -> id to (orderedIds.size - 1 - idx).toLong() }

/** Persisted name prefix of a suggested exact-amount rule (stored data, not UI copy). */
internal const val EXACT_AMOUNT_RULE_NAME: String = "Exact amount"

/** Legacy suggested-rule names: "21000 sat → Groceries". */
private val LEGACY_AMOUNT_RULE_NAME = Regex("""^(\d+) sat → (.*)$""")

/**
 * Rule name for display: a legacy amount-rule name has its raw sat
 * count re-rendered through [formatSat] (the user's unit and the
 * privacy mask); every other name is returned unchanged.
 */
internal fun displayRuleName(name: String, formatSat: (Long) -> String): String {
    val m = LEGACY_AMOUNT_RULE_NAME.matchEntire(name) ?: return name
    val sat = m.groupValues[1].toLongOrNull() ?: return name
    return "${formatSat(sat)} → ${m.groupValues[2]}"
}

/** Condition value for display: amount fields go through [formatSat]. */
internal fun displayRuleConditionValue(field: String, value: String, formatSat: (Long) -> String): String =
    if (isAmountField(field)) value.toLongOrNull()?.let(formatSat) ?: value else value
