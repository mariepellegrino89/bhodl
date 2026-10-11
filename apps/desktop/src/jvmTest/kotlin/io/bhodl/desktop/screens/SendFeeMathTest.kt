package io.bhodl.desktop.screens

import io.bhodl.ffi.FeeEstimates
import io.bhodl.ffi.UtxoRecord
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Fee preset totals (UX review 2026-10, §4 item 6): estimated vsize of
 * a BIP86 key-path transaction, input-count estimate, ETA per preset.
 */
class SendFeeMathTest {

    @Test
    fun `taproot vsize matches the well-known sizes`() {
        // 1-in / 2-out key-path spend: 154 vB.
        assertEquals(154L, TaprootTxSize.vsize(inputs = 1, outputs = 2))
        // 1-in / 1-out (send max to one recipient): 111 vB.
        assertEquals(111L, TaprootTxSize.vsize(inputs = 1, outputs = 1))
        // 2-in / 2-out: 211.5 → rounded up to 212 vB.
        assertEquals(212L, TaprootTxSize.vsize(inputs = 2, outputs = 2))
    }

    @Test
    fun `preset total is rate times estimated size, with ETA from the block target`() {
        val quote = feeQuote(
            rateSatVb = 5,
            etaMinutes = FeeTier.Normal.etaMinutes,
            recipientCount = 1,
            amountSat = 10_000,
            sendMax = false,
            pinnedInputCount = 0,
            spendableValuesSat = listOf(50_000),
        )
        assertEquals(154L, quote.vsize)
        assertEquals(770L, quote.feeSat)
        assertEquals(30, quote.etaMinutes)
        assertEquals(10, FeeTier.Priority.etaMinutes)
        assertEquals(60, FeeTier.Economy.etaMinutes)
    }

    @Test
    fun `tiers read the matching estimate bucket`() {
        val est = FeeEstimates(economySatPerVb = 2u, normalSatPerVb = 6u, prioritySatPerVb = 14u)
        assertEquals(2L, FeeTier.Economy.rateOf(est))
        assertEquals(6L, FeeTier.Normal.rateOf(est))
        assertEquals(14L, FeeTier.Priority.rateOf(est))
    }

    @Test
    fun `auto selection takes the largest coins until amount plus fee is covered`() {
        // 60k alone cannot pay 100k; 60k + 50k can, fee included.
        assertEquals(2, estimateInputCount(listOf(20_000, 60_000, 50_000), 100_000, 5, outputs = 2))
        // One coin covers it.
        assertEquals(1, estimateInputCount(listOf(20_000, 600_000), 100_000, 5, outputs = 2))
        // Amount + fee just above one coin pulls in a second one.
        assertEquals(2, estimateInputCount(listOf(100_000, 10_000), 100_000, 5, outputs = 2))
        // Not enough funds / no coins: fall back to the coins there are.
        assertEquals(3, estimateInputCount(listOf(1, 2, 3), 100_000, 5, outputs = 2))
        assertEquals(1, estimateInputCount(emptyList(), 100_000, 5, outputs = 2))
    }

    @Test
    fun `batch, coin control and send max change the estimated shape`() {
        fun fee(recipients: Int, sendMax: Boolean, pinned: Int, coins: List<Long>) = feeQuote(
            rateSatVb = 10,
            etaMinutes = null,
            recipientCount = recipients,
            amountSat = 1_000,
            sendMax = sendMax,
            pinnedInputCount = pinned,
            spendableValuesSat = coins,
        ).feeSat
        // 3 recipients + change, 1 input: (42 + 230 + 4*172)/4 = 240 vB.
        assertEquals(2_400L, fee(3, sendMax = false, pinned = 0, coins = listOf(1_000_000)))
        // Coin control pins 3 inputs: (42 + 690 + 344)/4 = 269 vB.
        assertEquals(2_690L, fee(1, sendMax = false, pinned = 3, coins = listOf(1_000_000)))
        // Send max drains both coins into one output, no change: (42+460+172)/4 → 169 vB.
        assertEquals(1_690L, fee(1, sendMax = true, pinned = 0, coins = listOf(5_000, 6_000)))
    }

    @Test
    fun `only confirmed unfrozen coins feed the estimate`() {
        fun u(v: Long, confirmed: Boolean, frozen: Boolean) = UtxoRecord(
            outpoint = "x:$v",
            valueSat = v.toULong(),
            address = "tb1p",
            isChange = false,
            confirmed = confirmed,
            frozen = frozen,
        )
        assertEquals(
            listOf(3L),
            spendableValues(listOf(u(1, true, true), u(2, false, false), u(3, true, false))),
        )
    }
}
