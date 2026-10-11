package io.bhodl.desktop.screens
import io.bhodl.desktop.domain.applyRules
import io.bhodl.desktop.domain.matchesRule

import io.bhodl.ffi.PaymentDirection
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.PaymentKind
import io.bhodl.ffi.TrackingRuleFfi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 7.5 — pure tests for [applyRules] / [matchesRule].
 *
 * The engine is the load-bearing piece of the auto-categorization
 * pipeline (gap #2 of the monthly loop). Pinning behaviour here keeps
 * the TrackerCache integration thin and obvious.
 */
class RulesEngineTest {

    // ─── applyRules: zero/one/many rules ────────────────────────────

    @Test
    fun `applyRules returns null when no rules`() {
        assertNull(applyRules(emptyList(), sampleEvent()))
    }

    @Test
    fun `applyRules returns category of first matching rule`() {
        // First-match-wins in input order — the registry sorts by
        // priority DESC for us; this test pins the iteration policy.
        val rules = listOf(
            rule("Amazon", field = "description", op = "contains", value = "amzn", category = "Shopping"),
            rule("Generic", field = "amount", op = "gt", value = "0", category = "Other"),
        )
        val cat = applyRules(rules, sampleEvent(txid = "AMZN-tx-1"))
        assertEquals("Shopping", cat)
    }

    @Test
    fun `applyRules skips disabled rules`() {
        val rules = listOf(
            rule("Amazon", enabled = false, value = "amzn", category = "Shopping"),
            rule("Fallback", value = "amzn", category = "Generic"),
        )
        assertEquals("Generic", applyRules(rules, sampleEvent(txid = "amzn-1")))
    }

    @Test
    fun `applyRules skips rules with non-category action`() {
        // Forward-compat guard: a future "tag" action shouldn't return
        // a fake category. v1 only honors action_field == "category".
        val rules = listOf(
            rule("Tag rule", value = "amzn", actionField = "tag", category = "shopping_tag"),
            rule("Cat rule", value = "amzn", category = "Shopping"),
        )
        assertEquals("Shopping", applyRules(rules, sampleEvent(txid = "amzn-1")))
    }

    // ─── matchesRule: text ops ──────────────────────────────────────

    @Test
    fun `text op contains is case insensitive`() {
        val r = rule(value = "AMZN", op = "contains")
        assertTrue(matchesRule(r, sampleEvent(txid = "Amzn-Tx-1")))
        assertTrue(matchesRule(r, sampleEvent(txid = "prefix-amzn-suffix")))
        assertFalse(matchesRule(r, sampleEvent(txid = "amazon")))
    }

    @Test
    fun `text op equals is case insensitive`() {
        val r = rule(value = "Netflix-monthly", op = "equals")
        assertTrue(matchesRule(r, sampleEvent(txid = "netflix-monthly")))
        assertFalse(matchesRule(r, sampleEvent(txid = "netflix")))
    }

    @Test
    fun `text op starts_with matches prefix`() {
        val r = rule(value = "spotify", op = "starts_with")
        assertTrue(matchesRule(r, sampleEvent(txid = "Spotify-2026-05")))
        assertFalse(matchesRule(r, sampleEvent(txid = "x-spotify")))
    }

    @Test
    fun `text op ends_with matches suffix`() {
        val r = rule(value = "-monthly", op = "ends_with")
        assertTrue(matchesRule(r, sampleEvent(txid = "Netflix-Monthly")))
        assertFalse(matchesRule(r, sampleEvent(txid = "monthly-fee")))
    }

    @Test
    fun `text op with unknown op returns false`() {
        val r = rule(value = "x", op = "regex")  // not supported in v1
        assertFalse(matchesRule(r, sampleEvent(txid = "anything")))
    }

    // ─── matchesRule: amount ops ────────────────────────────────────

    @Test
    fun `amount gt strict`() {
        val r = rule(field = "amount", op = "gt", value = "1000")
        assertTrue(matchesRule(r, sampleEvent(amountSat = 1001UL)))
        assertFalse(matchesRule(r, sampleEvent(amountSat = 1000UL)))  // strict
        assertFalse(matchesRule(r, sampleEvent(amountSat = 999UL)))
    }

    @Test
    fun `amount lt strict`() {
        val r = rule(field = "amount", op = "lt", value = "1000")
        assertTrue(matchesRule(r, sampleEvent(amountSat = 999UL)))
        assertFalse(matchesRule(r, sampleEvent(amountSat = 1000UL)))
        assertFalse(matchesRule(r, sampleEvent(amountSat = 1001UL)))
    }

    @Test
    fun `amount eq exact`() {
        val r = rule(field = "amount", op = "eq", value = "11990000")
        assertTrue(matchesRule(r, sampleEvent(amountSat = 11_990_000UL)))
        assertFalse(matchesRule(r, sampleEvent(amountSat = 11_990_001UL)))
    }

    @Test
    fun `amount op with unparseable value returns false`() {
        val r = rule(field = "amount", op = "gt", value = "not-a-number")
        assertFalse(matchesRule(r, sampleEvent(amountSat = 999_999UL)))
    }

    @Test
    fun `unknown condition field returns false`() {
        // Future-proofing: a downgrade (UI older than schema) writing
        // an unknown field shouldn't crash the matcher.
        val r = rule(field = "future_field", op = "contains", value = "x")
        assertFalse(matchesRule(r, sampleEvent()))
    }

    // ─── Test fixtures ──────────────────────────────────────────────

    private fun rule(
        name: String = "test",
        field: String = "description",
        op: String = "contains",
        value: String = "x",
        actionField: String = "category",
        category: String = "Cat",
        priority: Long = 0,
        enabled: Boolean = true,
    ): TrackingRuleFfi = TrackingRuleFfi(
        id = 1,
        name = name,
        conditionField = field,
        conditionOp = op,
        conditionValue = value,
        actionField = actionField,
        actionValue = category,
        priority = priority,
        enabled = enabled,
        createdAtUnix = 0,
    )

    private fun sampleEvent(
        walletId: String = "w1",
        txid: String = "tx1",
        amountSat: ULong = 1_000UL,
    ): PaymentEvent = PaymentEvent(
        walletId = walletId,
        txidOrPaymentId = txid,
        kind = PaymentKind.ON_CHAIN,
        direction = PaymentDirection.OUTGOING,
        amountSat = amountSat,
        feeSat = null,
        timestampUnix = 1_700_000_000UL,
        blockHeight = null,
        category = null,
    )
}
