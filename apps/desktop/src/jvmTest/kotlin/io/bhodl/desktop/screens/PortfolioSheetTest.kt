package io.bhodl.desktop.screens

import io.bhodl.ffi.PaymentDirection
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.PaymentKind
import io.bhodl.ffi.TrackingInvestmentFfi
import io.bhodl.ffi.TrackingInvestmentLotFfi
import io.bhodl.ffi.TrackingLiabilityFfi
import io.bhodl.ffi.TrackingManualAssetFfi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * UX review 2026-10 (item 9) — the Kubera-style Portfolio sheet:
 * subtotals from [TrackerNetWorth], per-row change over the selected
 * period, % of net worth, and the column priority for narrow widths.
 */
class PortfolioSheetTest {

    private val day = 86_400L
    private val now = 1_800_000_000L
    private val start = now - 30 * day

    // 1 BTC = 50k EUR now, 25k EUR at the period start.
    private val rateNow = { ccy: String -> if (ccy == "EUR") 50_000.0 else null }
    private val rateStart = { ccy: String -> if (ccy == "EUR") 25_000.0 else null }

    private val wallet = GroupedWallet(
        displayLabel = "Cold", kindLabel = "Watch", network = "signet",
        balanceSat = 1_000_000UL, memberIds = setOf("w1", "w1m"),
    )

    // 10 shares × 50 € = 500 € = 1,000,000 sat now; all bought before the window.
    private val inv = investment(id = 7, qty = 10.0, perUnit = 50.0, purchaseUnix = start - day)

    // 5,000 € = 10,000,000 sat now, held since before the window.
    private val house = manual(id = 3, value = 5_000.0, purchaseUnix = start - 10 * day)

    private val loan = TrackingLiabilityFfi(
        id = 9, name = "Loan", amountSat = 2_000_000UL, kind = "loan", displayLabel = null, createdAtUnix = 1L,
    )

    private val nw = TrackerNetWorth(
        walletSat = 1_000_000UL,
        investmentsSat = 1_000_000UL,
        manualAssetsSat = 10_000_000UL,
        liabilitiesSat = 2_000_000UL,
    )

    private fun build(
        events: List<PaymentEvent> = emptyList(),
        periodStart: Long? = start,
        investments: List<TrackingInvestmentFfi> = listOf(inv),
        lots: Map<Long, List<TrackingInvestmentLotFfi>> = emptyMap(),
        manualAssets: List<TrackingManualAssetFfi> = listOf(house),
        rateAtStart: (String) -> Double? = rateStart,
        netWorth: TrackerNetWorth = nw,
    ) = buildPortfolioSheet(
        netWorth = netWorth,
        walletGroups = listOf(wallet),
        investments = investments,
        lotsByInvestmentId = lots,
        manualAssets = manualAssets,
        liabilities = listOf(loan),
        events = events,
        periodStartUnix = periodStart,
        rateNow = rateNow,
        rateAtStart = rateAtStart,
        walletsUpdatedUnix = now - 60,
    )

    @Test
    fun `subtotals and net worth come from TrackerNetWorth`() {
        val sheet = build()
        assertEquals(
            listOf(1_000_000L, 1_000_000L, 10_000_000L, -2_000_000L),
            sheet.sections.map { it.subtotalSat },
        )
        assertEquals(nw.netWorthSat, sheet.netWorthSat)
        assertEquals(
            listOf(SheetSection.WALLETS, SheetSection.INVESTMENTS, SheetSection.MANUAL_ASSETS, SheetSection.LIABILITIES),
            sheet.sections.map { it.section },
        )
    }

    @Test
    fun `rows add up to the live subtotals`() {
        val sheet = build()
        for (s in sheet.sections) {
            assertEquals(s.subtotalSat, s.rows.sumOf { it.valueSat!! }, "section ${s.section}")
        }
    }

    @Test
    fun `percent of net worth is signed and relative to the net worth`() {
        val sheet = build()
        val nwSat = nw.netWorthSat.toDouble() // 10,000,000
        val houseRow = sheet.sections[2].rows.single()
        assertEquals(10_000_000.0 * 100.0 / nwSat, houseRow.pctOfNetWorth!!, 1e-9)
        val loanRow = sheet.sections[3].rows.single()
        assertEquals(-20.0, loanRow.pctOfNetWorth!!, 1e-9)
    }

    @Test
    fun `no percent of a non-positive net worth`() {
        val broke = TrackerNetWorth(0UL, 0UL, 0UL, 5UL)
        val sheet = build(netWorth = broke)
        assertTrue(sheet.sections.flatMap { it.rows }.all { it.pctOfNetWorth == null })
    }

    @Test
    fun `wallet change is the signed flow inside the window, deduped by txid`() {
        val events = listOf(
            event("w1", "a", PaymentDirection.INCOMING, 300_000UL, now - 5 * day),
            event("w1m", "a", PaymentDirection.INCOMING, 300_000UL, now - 5 * day), // mirror copy
            event("w1", "b", PaymentDirection.OUTGOING, 50_000UL, now - 2 * day),
            event("w1", "old", PaymentDirection.INCOMING, 999_999UL, start - day), // before window
            event("other", "c", PaymentDirection.INCOMING, 777UL, now - day), // other wallet
            event("w1", "pending", PaymentDirection.INCOMING, 1_000UL, null), // unconfirmed: now
        )
        val row = build(events = events).sections[0].rows.single()
        assertEquals(300_000L - 50_000L + 1_000L, row.changeSat)
    }

    @Test
    fun `investment change uses today's fiat value at the start-of-period BTC rate`() {
        // Start value: 500 € at 25k = 2,000,000 sat; now 1,000,000 sat.
        val row = build().sections[1].rows.single()
        assertEquals(1_000_000L, row.valueSat)
        assertEquals(-1_000_000L, row.changeSat)
    }

    @Test
    fun `lots bought inside the window count from zero`() {
        val lots = mapOf(
            7L to listOf(
                lot(id = 1, qty = 4.0, purchaseUnix = start - day),
                lot(id = 2, qty = 6.0, purchaseUnix = now - day), // new in the window
            ),
        )
        val invWithLots = inv.copy(quantity = 0.0)
        val row = build(investments = listOf(invWithLots), lots = lots).sections[1].rows.single()
        // Now: 10 × 50 € = 500 € → 1,000,000 sat. Start: 4 × 50 € = 200 € at 25k → 800,000 sat.
        assertEquals(1_000_000L, row.valueSat)
        assertEquals(200_000L, row.changeSat)
    }

    @Test
    fun `manual asset bought inside the window changes by its whole value`() {
        val fresh = manual(id = 4, value = 500.0, purchaseUnix = now - day)
        val row = build(manualAssets = listOf(fresh)).sections[2].rows.single()
        assertEquals(1_000_000L, row.changeSat)
    }

    @Test
    fun `liabilities are static like the net-worth history`() {
        assertEquals(0L, build().sections[3].rows.single().changeSat)
    }

    @Test
    fun `missing start rate leaves the change unknown, never guessed`() {
        val sheet = build(rateAtStart = { null })
        assertNull(sheet.sections[1].rows.single().changeSat)
        assertNull(sheet.sections[1].changeSat)
        assertNull(sheet.netWorthChangeSat)
        // Wallets don't need a rate.
        assertEquals(0L, sheet.sections[0].changeSat)
    }

    @Test
    fun `all-time period has no change values`() {
        val sheet = build(periodStart = null)
        assertTrue(sheet.sections.flatMap { it.rows }.all { it.changeSat == null })
        assertNull(sheet.netWorthChangeSat)
    }

    @Test
    fun `section and net worth change are the sums of the rows`() {
        val events = listOf(event("w1", "a", PaymentDirection.INCOMING, 100_000UL, now - day))
        val sheet = build(events = events)
        // wallets +100k, investments −1M, manual: 5,000 € at 25k = 20M → 10M now = −10M, liabilities 0.
        assertEquals(listOf(100_000L, -1_000_000L, -10_000_000L, 0L), sheet.sections.map { it.changeSat })
        assertEquals(100_000L - 1_000_000L - 10_000_000L, sheet.netWorthChangeSat)
    }

    @Test
    fun `no-rate rows have no value`() {
        val usd = investment(id = 8, qty = 1.0, perUnit = 10.0, purchaseUnix = 0, ccy = "USD")
        val row = build(investments = listOf(usd)).sections[1].rows.single()
        assertNull(row.valueSat)
        assertNull(row.changeSat)
    }

    // ─── start-of-period rates after a period change ─────────────

    @Test
    fun `start rates answer only for the day they were fetched for`() {
        val day90 = sheetStartDay(now - 90 * day)
        val day7 = sheetStartDay(now - 7 * day)
        val held = SheetStartRates(day90, mapOf("EUR" to 25_000.0))
        assertEquals(25_000.0, held.rateFor("EUR", day90))
        // Period switched to 7g, the new fetch has not landed yet.
        assertNull(held.rateFor("EUR", day7))
        assertNull(held.rateFor("USD", day90))
    }

    @Test
    fun `stale rates from the previous period leave the change unknown`() {
        val held = SheetStartRates(sheetStartDay(now - 90 * day), mapOf("EUR" to 25_000.0))
        val wanted = sheetStartDay(start)
        val sheet = build(rateAtStart = { ccy -> held.rateFor(ccy, wanted) })
        assertNull(sheet.sections[1].rows.single().changeSat)
        assertNull(sheet.sections[2].rows.single().changeSat)
        assertNull(sheet.netWorthChangeSat)
    }

    @Test
    fun `start day is the UTC midnight of the period start`() {
        assertEquals(0L, sheetStartDay(86_399L))
        assertEquals(86_400L, sheetStartDay(86_400L))
    }

    // ─── column priority ─────────────────────────────────────────

    @Test
    fun `wide sheets show every column`() {
        assertEquals(SheetColumn.entries, visibleSheetColumns(1200))
    }

    @Test
    fun `a 1024 px window keeps the essentials`() {
        // 1024 window − 80 rail − page and card padding ≈ 840 dp of sheet.
        val cols = visibleSheetColumns(840)
        assertTrue(
            cols.containsAll(listOf(SheetColumn.NAME, SheetColumn.VALUE, SheetColumn.CHANGE, SheetColumn.PCT_NW)),
        )
    }

    @Test
    fun `columns give way in priority order`() {
        val all = SheetColumn.entries.sumOf { it.minWidthDp } + SHEET_COLUMN_GAP_DP * (SheetColumn.entries.size - 1)
        // Just too narrow: last-updated goes first.
        assertEquals(SheetColumn.entries - SheetColumn.UPDATED, visibleSheetColumns(all - 1))
        // Then the type badge, then % of NW, then change.
        val noUpdated = all - SheetColumn.UPDATED.minWidthDp - SHEET_COLUMN_GAP_DP
        assertEquals(
            SheetColumn.entries - SheetColumn.UPDATED - SheetColumn.TYPE,
            visibleSheetColumns(noUpdated - 1),
        )
        assertEquals(listOf(SheetColumn.NAME, SheetColumn.VALUE, SheetColumn.CHANGE), visibleSheetColumns(470))
        // Name and value never hide, however narrow.
        assertEquals(listOf(SheetColumn.NAME, SheetColumn.VALUE), visibleSheetColumns(100))
    }

    // ─── fixtures ────────────────────────────────────────────────

    private fun investment(id: Long, qty: Double, perUnit: Double, purchaseUnix: Long, ccy: String = "EUR") =
        TrackingInvestmentFfi(
            id = id, name = "Inv$id", assetType = "etf", ticker = null, quantity = qty, unit = "",
            costBasisFiat = qty * perUnit, costBasisCurrency = ccy, purchaseUnix = purchaseUnix,
            manualCurrentValue = perUnit, fetchedCurrentValue = null, lastPricedAtUnix = null,
            notes = null, costBasisMethod = "fifo", createdAtUnix = 1L,
        )

    private fun lot(id: Long, qty: Double, purchaseUnix: Long) = TrackingInvestmentLotFfi(
        id = id, investmentId = 7, quantity = qty, pricePerUnit = 40.0, feesFiat = 0.0,
        purchaseUnix = purchaseUnix, notes = null, createdAtUnix = 1L,
    )

    private fun manual(id: Long, value: Double, purchaseUnix: Long?) = TrackingManualAssetFfi(
        id = id, name = "Asset$id", kind = "real_estate", valueFiat = value, currency = "EUR",
        displayLabel = null, notes = null, lastUpdatedUnix = now - 3 * day, createdAtUnix = 1L,
        purchaseUnix = purchaseUnix,
    )

    private fun event(wallet: String, txid: String, dir: PaymentDirection, sat: ULong, ts: Long?) = PaymentEvent(
        walletId = wallet,
        txidOrPaymentId = txid,
        kind = PaymentKind.ON_CHAIN,
        direction = dir,
        amountSat = sat,
        feeSat = null,
        timestampUnix = ts?.toULong(),
        blockHeight = null,
        category = null,
    )

    @Test
    fun `class shares are of the total assets and the debts are leverage`() {
        fun section(s: SheetSection, v: Long) = SheetSectionModel(s, emptyList(), v, null, null)
        // Assets 400 (bitcoin 100, manual 300), debts 250: net worth 150.
        val sheet = PortfolioSheet(
            sections = listOf(
                section(SheetSection.WALLETS, 100),
                section(SheetSection.INVESTMENTS, 0),
                section(SheetSection.MANUAL_ASSETS, 300),
                section(SheetSection.LIABILITIES, -250),
            ),
            netWorthSat = 150,
            netWorthChangeSat = null,
        )
        val shares = classShares(sheet)
        assertEquals(25.0, shares[PortfolioClass.BITCOIN])
        assertEquals(75.0, shares[PortfolioClass.MANUAL_ASSETS], "not 200% of the net worth")
        assertEquals(0.0, shares[PortfolioClass.INVESTMENTS])
        assertEquals(62.5, shares[PortfolioClass.LIABILITIES])
        assertEquals(
            100.0,
            shares.filterKeys { it != PortfolioClass.LIABILITIES }.values.sum(),
            "asset classes add up to 100%",
        )
        assertTrue(classShares(sheet.copy(sections = listOf(section(SheetSection.LIABILITIES, -5)))).isEmpty())
    }
}

