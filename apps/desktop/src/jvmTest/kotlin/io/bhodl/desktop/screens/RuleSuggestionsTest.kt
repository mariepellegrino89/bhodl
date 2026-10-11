package io.bhodl.desktop.screens

import io.bhodl.desktop.domain.RulePatternKind
import io.bhodl.desktop.domain.applyRules
import io.bhodl.desktop.domain.matchesRule
import io.bhodl.desktop.domain.moveRule
import io.bhodl.desktop.domain.prioritiesForOrder
import io.bhodl.desktop.domain.ruleDraftFromEdit
import io.bhodl.desktop.domain.ruleDraftMatches
import io.bhodl.desktop.domain.ruleDraftPreviewCount
import io.bhodl.desktop.domain.rulesInEvaluationOrder
import io.bhodl.desktop.domain.shouldOfferRuleFromEdit
import io.bhodl.desktop.domain.topRulePriority
import io.bhodl.ffi.PaymentDirection
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.PaymentKind
import io.bhodl.ffi.TrackingRuleFfi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * UX review 2026-10 (item 11) — "create rule from edit": the preview
 * count, the payee (wallet label) matching and the explicit ordering
 * semantics (first match wins, move up / down renumbers priorities).
 */
class RuleSuggestionsTest {

    private val labels = mapOf("w-daily" to "Daily spending", "w-save" to "Savings")
    private val payeeOf: (PaymentEvent) -> String? = { labels[it.walletId] }

    // ─── payee matching ───────────────────────────────────────────

    @Test
    fun `payee rules match the wallet label`() {
        val r = rule(id = 1, field = "payee", op = "equals", value = "daily spending")
        assertTrue(matchesRule(r, event("w-daily", "aa"), payee = "Daily spending"))
        assertFalse(matchesRule(r, event("w-save", "bb"), payee = "Savings"))
    }

    @Test
    fun `payee rules still match the txid for backward compatibility`() {
        val r = rule(id = 1, field = "payee", op = "contains", value = "amzn")
        assertTrue(matchesRule(r, event("w-save", "AMZN-1"), payee = "Savings"))
        assertEquals("Cat", applyRules(listOf(r), event("w-save", "AMZN-1")))
    }

    @Test
    fun `description rules ignore the wallet label`() {
        val r = rule(id = 1, field = "description", op = "contains", value = "daily")
        assertFalse(matchesRule(r, event("w-daily", "aa"), payee = "Daily spending"))
    }

    // ─── draft from edit ─────────────────────────────────────────

    @Test
    fun `wallet draft is an equals rule on the payee`() {
        val d = assertNotNull(
            ruleDraftFromEdit(event("w-daily", "aa"), " Groceries ", "Daily spending", RulePatternKind.WALLET),
        )
        assertEquals("payee", d.conditionField)
        assertEquals("equals", d.conditionOp)
        assertEquals("Daily spending", d.conditionValue)
        assertEquals("Groceries", d.category)
    }

    @Test
    fun `amount draft is an exact amount rule in the edited event's direction`() {
        val out = assertNotNull(
            ruleDraftFromEdit(event("w-daily", "aa", 21_000UL), "Netflix", null, RulePatternKind.AMOUNT),
        )
        assertEquals("amount_out" to "eq", out.conditionField to out.conditionOp)
        assertEquals("21000", out.conditionValue)
        val incoming = event("w-daily", "bb", 21_000UL, direction = PaymentDirection.INCOMING)
        assertEquals(
            "amount_in",
            ruleDraftFromEdit(incoming, "Refunds", null, RulePatternKind.AMOUNT)!!.conditionField,
        )
    }

    @Test
    fun `directional amount rules only match their direction`() {
        val r = rule(id = 1, field = "amount_out", op = "eq", value = "21000")
        assertTrue(matchesRule(r, event("w", "a", 21_000UL)))
        assertFalse(matchesRule(r, event("w", "b", 21_000UL, direction = PaymentDirection.INCOMING)))
        val rIn = r.copy(conditionField = "amount_in")
        assertTrue(matchesRule(rIn, event("w", "c", 21_000UL, direction = PaymentDirection.INCOMING)))
        assertFalse(matchesRule(rIn, event("w", "d", 21_000UL)))
    }

    @Test
    fun `no draft without a wallet label, amount or category`() {
        assertNull(ruleDraftFromEdit(event("w-x", "aa"), "Food", null, RulePatternKind.WALLET))
        assertNull(ruleDraftFromEdit(event("w-x", "aa", 0UL), "Food", "L", RulePatternKind.AMOUNT))
        assertNull(ruleDraftFromEdit(event("w-x", "aa"), "  ", "L", RulePatternKind.WALLET))
    }

    // ─── preview count ───────────────────────────────────────────

    @Test
    fun `preview counts uncategorised matching transactions except the edited one`() {
        val edited = event("w-daily", "e0")
        val events = listOf(
            edited,
            event("w-daily", "e1"),
            event("w-daily", "e2"),
            event("w-daily", "e3", category = "Dining"), // hand-set: never overwritten
            event("w-save", "e4"), // other wallet
        )
        val d = ruleDraftFromEdit(edited, "Groceries", "Daily spending", RulePatternKind.WALLET)!!
        val matches = ruleDraftMatches(d, events, payeeOf, edited)
        assertEquals(listOf("e1", "e2"), matches.map { it.txidOrPaymentId })
        assertEquals(2, ruleDraftPreviewCount(matches))
    }

    @Test
    fun `preview counts a txid seen by two wallets once but applies to both rows`() {
        // A signing wallet and its mirror both report the same tx.
        val labels2 = mapOf("a" to "Daily spending", "b" to "Daily spending")
        val events = listOf(event("a", "tx1", 5_000UL), event("b", "tx1", 5_000UL), event("a", "tx2", 5_000UL))
        val edited = event("a", "tx0", 5_000UL)
        val d = ruleDraftFromEdit(edited, "Rent", null, RulePatternKind.AMOUNT)!!
        val matches = ruleDraftMatches(d, events, { labels2[it.walletId] }, edited)
        assertEquals(3, matches.size)
        assertEquals(2, ruleDraftPreviewCount(matches))
    }

    @Test
    fun `preview excludes the mirror row of the edited transaction`() {
        // The edited tx is also reported by the watch mirror of the same
        // wallet: neither row may be counted or written by the rule.
        val labels2 = mapOf("sign" to "Daily spending", "mirror" to "Daily spending")
        val edited = event("sign", "tx0")
        val events = listOf(edited, event("mirror", "tx0"), event("mirror", "tx1"))
        val d = ruleDraftFromEdit(edited, "Groceries", "Daily spending", RulePatternKind.WALLET)!!
        val matches = ruleDraftMatches(d, events, { labels2[it.walletId] }, edited)
        assertEquals(listOf("tx1"), matches.map { it.txidOrPaymentId })
        assertEquals(1, ruleDraftPreviewCount(matches))
    }

    @Test
    fun `amount preview ignores events in the other direction`() {
        val edited = event("w-daily", "e0", 21_000UL)
        val events = listOf(
            event("w-save", "e1", 21_000UL),
            event("w-save", "e2", 21_000UL, direction = PaymentDirection.INCOMING), // a refund
        )
        val d = ruleDraftFromEdit(edited, "Netflix", "Daily spending", RulePatternKind.AMOUNT)!!
        assertEquals(listOf("e1"), ruleDraftMatches(d, events, payeeOf, edited).map { it.txidOrPaymentId })
    }

    @Test
    fun `amount preview only counts the exact amount`() {
        val edited = event("w-daily", "e0", 21_000UL)
        val events = listOf(event("w-save", "e1", 21_000UL), event("w-save", "e2", 21_001UL))
        val d = ruleDraftFromEdit(edited, "Netflix", "Daily spending", RulePatternKind.AMOUNT)!!
        assertEquals(1, ruleDraftPreviewCount(ruleDraftMatches(d, events, payeeOf, edited)))
    }

    // ─── when to offer ───────────────────────────────────────────

    @Test
    fun `offer only for a real change no enabled rule already covers`() {
        val ev = event("w-daily", "e0")
        assertTrue(shouldOfferRuleFromEdit(ev, null, "Groceries", emptyList(), "Daily spending"))
        assertFalse(shouldOfferRuleFromEdit(ev, "Groceries", "Groceries", emptyList(), "Daily spending"))
        assertFalse(shouldOfferRuleFromEdit(ev, null, "  ", emptyList(), "Daily spending"))
        val covering = rule(id = 1, field = "payee", op = "equals", value = "Daily spending", category = "Groceries")
        assertFalse(shouldOfferRuleFromEdit(ev, null, "Groceries", listOf(covering), "Daily spending"))
        // A disabled rule does not count.
        assertTrue(
            shouldOfferRuleFromEdit(ev, null, "Groceries", listOf(covering.copy(enabled = false)), "Daily spending"),
        )
    }

    // ─── ordering ────────────────────────────────────────────────

    @Test
    fun `evaluation order is priority desc then created asc`() {
        val a = rule(id = 1, priority = 0, created = 10)
        val b = rule(id = 2, priority = 5, created = 20)
        val c = rule(id = 3, priority = 0, created = 5)
        assertEquals(listOf(2L, 3L, 1L), rulesInEvaluationOrder(listOf(a, b, c)).map { it.id })
    }

    @Test
    fun `first match in evaluation order wins`() {
        val general = rule(id = 1, field = "amount", op = "gt", value = "0", category = "Other", priority = 1)
        val specific = rule(id = 2, field = "payee", op = "equals", value = "Daily spending", category = "Groceries", priority = 0)
        val ev = event("w-daily", "e1")
        assertEquals("Other", applyRules(rulesInEvaluationOrder(listOf(general, specific)), ev, payeeOf))
        // Move the specific rule up: it now wins.
        val order = moveRule(listOf(general, specific), ruleId = 2, delta = -1)!!
        val reordered = applyOrder(listOf(general, specific), order)
        assertEquals("Groceries", applyRules(rulesInEvaluationOrder(reordered), ev, payeeOf))
    }

    @Test
    fun `move returns the full new order for one atomic reorder`() {
        val rules = listOf(
            rule(id = 1, priority = 10, created = 1),
            rule(id = 2, priority = 10, created = 2), // tie, older first
            rule(id = 3, priority = 3, created = 3),
        )
        // Order: 1, 2, 3. Move 3 up → 1, 3, 2, stored as priorities 2, 1, 0.
        val order = moveRule(rules, ruleId = 3, delta = -1)
        assertEquals(listOf(1L, 3L, 2L), order)
        assertEquals(mapOf(1L to 2L, 3L to 1L, 2L to 0L), prioritiesForOrder(order!!))
        assertEquals(listOf(1L, 3L, 2L), rulesInEvaluationOrder(applyOrder(rules, order)).map { it.id })
    }

    @Test
    fun `repeating a move on the stale list writes the same order, never an undo`() {
        val rules = listOf(rule(id = 1, priority = 2), rule(id = 2, priority = 1), rule(id = 3, priority = 0))
        val first = moveRule(rules, ruleId = 2, delta = -1)
        // A second click computed from the not-yet-reloaded list.
        val second = moveRule(rules, ruleId = 2, delta = -1)
        assertEquals(listOf(2L, 1L, 3L), first)
        assertEquals(first, second)
    }

    @Test
    fun `moving past the edges or an unknown id is a no-op`() {
        val rules = listOf(rule(id = 1, priority = 1), rule(id = 2, priority = 0))
        assertNull(moveRule(rules, ruleId = 1, delta = -1))
        assertNull(moveRule(rules, ruleId = 2, delta = +1))
        assertNull(moveRule(rules, ruleId = 99, delta = -1))
    }

    /** What the registry's reorderRules stores, applied in memory. */
    private fun applyOrder(rules: List<TrackingRuleFfi>, order: List<Long>): List<TrackingRuleFfi> {
        val p = prioritiesForOrder(order)
        return rules.map { it.copy(priority = p.getValue(it.id)) }
    }

    @Test
    fun `a new rule goes on top`() {
        assertEquals(0L, topRulePriority(emptyList()))
        assertEquals(8L, topRulePriority(listOf(rule(id = 1, priority = 7), rule(id = 2, priority = 3))))
    }

    // ─── fixtures ────────────────────────────────────────────────

    private fun rule(
        id: Long,
        field: String = "description",
        op: String = "contains",
        value: String = "x",
        category: String = "Cat",
        priority: Long = 0,
        enabled: Boolean = true,
        created: Long = 0,
    ) = TrackingRuleFfi(
        id = id,
        name = "r$id",
        conditionField = field,
        conditionOp = op,
        conditionValue = value,
        actionField = "category",
        actionValue = category,
        priority = priority,
        enabled = enabled,
        createdAtUnix = created,
    )

    private fun event(
        walletId: String,
        txid: String,
        amountSat: ULong = 1_000UL,
        category: String? = null,
        direction: PaymentDirection = PaymentDirection.OUTGOING,
    ) = PaymentEvent(
        walletId = walletId,
        txidOrPaymentId = txid,
        kind = PaymentKind.ON_CHAIN,
        direction = direction,
        amountSat = amountSat,
        feeSat = null,
        timestampUnix = 1_700_000_000UL,
        blockHeight = null,
        category = category,
    )
}
