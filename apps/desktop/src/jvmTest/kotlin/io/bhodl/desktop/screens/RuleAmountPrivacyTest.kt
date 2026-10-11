package io.bhodl.desktop.screens

import io.bhodl.desktop.domain.EXACT_AMOUNT_RULE_NAME
import io.bhodl.desktop.domain.RulePatternKind
import io.bhodl.desktop.domain.displayRuleConditionValue
import io.bhodl.desktop.domain.displayRuleName
import io.bhodl.desktop.domain.ruleDraftFromEdit
import io.bhodl.ffi.PaymentDirection
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.PaymentKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * Security review (item 13) — a rule must not print an amount the
 * privacy mode hides: suggested names are unit-neutral, amount
 * conditions and legacy "21000 sat → cat" names render through the
 * (masked) formatter.
 */
class RuleAmountPrivacyTest {

    private val masked: (Long) -> String = { "••••• sat" }

    @Test
    fun `a suggested exact-amount rule stores no amount in its name`() {
        val ev = PaymentEvent(
            walletId = "w", txidOrPaymentId = "t", kind = PaymentKind.ON_CHAIN,
            direction = PaymentDirection.OUTGOING, amountSat = 21_000UL, feeSat = null,
            timestampUnix = 1UL, blockHeight = null, category = null,
        )
        val draft = assertNotNull(ruleDraftFromEdit(ev, "Groceries", null, RulePatternKind.AMOUNT))
        assertEquals("$EXACT_AMOUNT_RULE_NAME → Groceries", draft.name)
        assertFalse(draft.name.any { it.isDigit() })
        assertEquals("21000", draft.conditionValue)
    }

    @Test
    fun `amount conditions and legacy names render through the formatter`() {
        assertEquals("••••• sat", displayRuleConditionValue("amount_out", "21000", masked))
        assertEquals("Coffee", displayRuleConditionValue("payee", "Coffee", masked))
        assertEquals("••••• sat → Groceries", displayRuleName("21000 sat → Groceries", masked))
        // Ordinary names are left alone.
        assertEquals("Salary rule", displayRuleName("Salary rule", masked))
        assertEquals("Exact amount → Rent", displayRuleName("Exact amount → Rent", masked))
    }
}
