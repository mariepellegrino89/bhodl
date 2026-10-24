package io.bhodl.desktop.screens

import io.bhodl.desktop.domain.CashFlowForecastEnd
import io.bhodl.desktop.domain.RecurringConfidence
import io.bhodl.desktop.domain.RecurringFrequency
import io.bhodl.desktop.domain.SEND_TO_SELF
import io.bhodl.desktop.domain.classifyFrequency
import io.bhodl.desktop.domain.detectRecurring
import io.bhodl.desktop.domain.forecastCashFlow
import io.bhodl.desktop.domain.scoreConfidence
import io.bhodl.desktop.domain.shouldShowMonthlyReviewBanner
import io.bhodl.ffi.PaymentDirection
import io.bhodl.ffi.PaymentDirection.INCOMING
import io.bhodl.ffi.PaymentDirection.OUTGOING
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.PaymentKind
import io.bhodl.ffi.TrackingBillFfi
import io.github.koalaplot.core.xygraph.DefaultPoint
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Area C — pure aggregations and helpers of the tracker dashboard,
 * forecast, recurring detection, bills and CSV export.
 */
class UcAggregationsTrackerTest {

    private val day = 86_400L

    private fun ev(
        txid: String,
        direction: PaymentDirection,
        amount: ULong,
        ts: Long?,
        fee: ULong? = null,
        category: String? = null,
        kind: String? = null,
    ) = PaymentEvent(
        walletId = "w",
        txidOrPaymentId = txid,
        kind = PaymentKind.ON_CHAIN,
        direction = direction,
        amountSat = amount,
        feeSat = fee,
        timestampUnix = ts?.toULong(),
        blockHeight = null,
        category = category,
        cryptoEventKind = kind,
    )

    private fun utc(y: Int, m: Int, d: Int, h: Int = 12) =
        LocalDate.of(y, m, d).atTime(h, 0).toEpochSecond(ZoneOffset.UTC)

    // ─── TRK-110 / TRK-111 — KPIs and balance split ─────────────────

    @Test
    fun `TRK-110 send_to_self contributes only its fee to the period KPIs`() {
        val k = computeKpis(
            listOf(
                ev("i", INCOMING, 1_000UL, 1),
                ev("o", OUTGOING, 300UL, 2),
                ev("s", OUTGOING, 50_500UL, 3, fee = 500UL, kind = SEND_TO_SELF),
                ev("c", INCOMING, 50_000UL, 4, kind = SEND_TO_SELF),
            ),
            currentBalanceSat = 9UL,
        )
        assertEquals(1_000UL, k.inflowSat)
        assertEquals(800UL, k.outflowSat)
        assertEquals(200L, k.netSat)
        assertEquals(4, k.txCount)
        assertEquals(9UL, k.currentBalanceSat)
    }

    @Test
    fun `TRK-111 pending net comes from mempool events and confirmed is floored at zero`() {
        val events = listOf(
            ev("c", INCOMING, 5_000UL, 100),
            ev("p1", INCOMING, 700UL, null),
            ev("p2", OUTGOING, 200UL, null),
        )
        val split = splitBalance(10_000UL, events)
        assertEquals(500L, split.pendingNetSat)
        assertEquals(9_500UL, split.confirmedSat)
        assertTrue(split.hasPending)

        val tooMuchPending = splitBalance(300UL, events)
        assertEquals(0UL, tooMuchPending.confirmedSat)

        val outOnly = splitBalance(1_000UL, listOf(ev("p", OUTGOING, 400UL, null)))
        assertEquals(-400L, outOnly.pendingNetSat)
        assertEquals(1_400UL, outOnly.confirmedSat)

        val none = splitBalance(1_000UL, listOf(ev("c", INCOMING, 1UL, 1)))
        assertFalse(none.hasPending)
        assertEquals(1_000UL, none.confirmedSat)
    }

    // ─── TRK-128 — previous period ──────────────────────────────────

    @Test
    fun `TRK-128 previous series covers the window one period back shifted forward`() {
        val now = 100 * day
        val events = listOf(
            ev("a", INCOMING, 100UL, now - 50 * day),
            ev("b", INCOMING, 10UL, now - 10 * day),
            ev("c", OUTGOING, 30UL, now - 5 * day),
        )
        val prev = balanceSeriesPrevious(events, TrackingPeriod.D7, now)
        // b at now-10d sits in [now-14d, now-7d]; anchored at the window start.
        assertEquals(BalancePoint(now - 7 * day, 100), prev.first())
        assertEquals(BalancePoint(now - 10 * day + 7 * day, 110), prev[1])
        assertEquals(BalancePoint(now, 110), prev.last())
        assertTrue(balanceSeriesPrevious(events, TrackingPeriod.ALL, now).isEmpty())
        assertTrue(balanceSeriesPrevious(emptyList(), TrackingPeriod.D7, now).isEmpty())
        // No event in the previous window → nothing to overlay.
        assertTrue(balanceSeriesPrevious(events, TrackingPeriod.D30, 300 * day).isEmpty())
    }

    // ─── TRK-131 — Sankey ───────────────────────────────────────────

    @Test
    fun `TRK-131 sankey nets each category, drops balanced ones and computes savings`() {
        val flow = buildSankeyFlow(
            listOf(
                ev("1", INCOMING, 1_000UL, 1, category = "Salary"),
                ev("2", OUTGOING, 300UL, 2, category = "Food"),
                ev("3", INCOMING, 50UL, 3, category = "Food"),
                ev("4", INCOMING, 80UL, 4, category = "Swap"),
                ev("5", OUTGOING, 80UL, 5, category = "Swap"),
                ev("6", OUTGOING, 100UL, 6),
                ev("7", OUTGOING, 10_400UL, 7, fee = 400UL, category = "Move", kind = SEND_TO_SELF),
            ),
        )
        assertEquals(listOf(SankeySegment("Salary", 1_000UL)), flow.incomeCategories)
        assertEquals(
            listOf(SankeySegment("Food", 250UL), SankeySegment("Uncategorised", 100UL), SankeySegment("Move", 400UL))
                .sortedByDescending { it.amountSat },
            flow.expenseCategories,
        )
        assertEquals(1_000UL - 750UL, flow.savedSat)
    }

    @Test
    fun `TRK-131 savings are zero when spending exceeds income and empty input is empty`() {
        val flow = buildSankeyFlow(
            listOf(ev("1", INCOMING, 100UL, 1, category = "A"), ev("2", OUTGOING, 300UL, 2, category = "B")),
        )
        assertEquals(0UL, flow.savedSat)
        assertTrue(buildSankeyFlow(emptyList()).isEmpty)
    }

    // ─── TRK-132 — heatmap volumes ──────────────────────────────────

    @Test
    fun `TRK-132 daily volumes bucket by local day of the requested month`() {
        val events = listOf(
            ev("a", INCOMING, 100UL, utc(2026, 3, 1, 2)),
            ev("b", OUTGOING, 40UL, utc(2026, 3, 1, 20)),
            ev("c", INCOMING, 7UL, utc(2026, 4, 1)),
            ev("s", OUTGOING, 5_500UL, utc(2026, 3, 9), fee = 500UL, kind = SEND_TO_SELF),
            ev("m", INCOMING, 9UL, null),
        )
        assertEquals(mapOf(1 to 140UL, 9 to 500UL), dailyVolumes(events, YearMonth.of(2026, 3), ZoneOffset.UTC))
        // In Los Angeles, 2026-03-01 02:00Z is still February 28th.
        val la = dailyVolumes(events, YearMonth.of(2026, 3), ZoneId.of("America/Los_Angeles"))
        assertEquals(40UL, la[1])
        assertEquals(mapOf(28 to 100UL), dailyVolumes(events, YearMonth.of(2026, 2), ZoneId.of("America/Los_Angeles")))
    }

    // ─── TRK-133 / TRK-134 — chart helpers ──────────────────────────

    @Test
    fun `TRK-133 log ticks span the decades and are thinned to at most five`() {
        assertEquals(listOf(2, 3, 4), computeLogTicks(150.0, 25_000.0))
        assertEquals(listOf(3), computeLogTicks(1_500.0, 1_500.0))
        assertEquals(listOf(0, 1), computeLogTicks(0.0, 10.0))
        val wide = computeLogTicks(1.0, 1e11)
        assertTrue(wide.size <= 6)
        assertEquals(0, wide.first())
        assertEquals(11, wide.last())
    }

    @Test
    fun `TRK-134 stepped points insert the corner before each jump`() {
        val pts = listOf(DefaultPoint(0L, 10L), DefaultPoint(5L, 20L), DefaultPoint(9L, 15L))
        assertEquals(
            listOf(
                DefaultPoint(0L, 10L), DefaultPoint(5L, 10L), DefaultPoint(5L, 20L),
                DefaultPoint(9L, 20L), DefaultPoint(9L, 15L),
            ),
            steppedPoints(pts),
        )
        assertEquals(listOf(DefaultPoint(1L, 1L)), steppedPoints(listOf(DefaultPoint(1L, 1L))))
    }

    @Test
    fun `TRK-134 bar unit is derived from the bucket spacing and ticks follow it`() {
        fun bars(step: Long) = listOf(FlowBar(0, 0UL, 0UL), FlowBar(step, 0UL, 0UL))
        assertEquals(FlowBucketUnit.DAY, deriveBarUnit(bars(day)))
        assertEquals(FlowBucketUnit.WEEK, deriveBarUnit(bars(7 * day)))
        assertEquals(FlowBucketUnit.MONTH, deriveBarUnit(bars(30 * day)))
        assertEquals(FlowBucketUnit.QUARTER, deriveBarUnit(bars(90 * day)))
        assertEquals(FlowBucketUnit.DAY, deriveBarUnit(listOf(FlowBar(0, 0UL, 0UL))))

        val saved = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
            val ts = utc(2026, 3, 7)
            assertEquals("07/03", formatTimestampTick(ts, FlowBucketUnit.DAY))
            assertEquals("07/03", formatTimestampTick(ts, FlowBucketUnit.WEEK))
            assertEquals("03/26", formatTimestampTick(ts, FlowBucketUnit.MONTH))
            assertEquals("03/26", formatTimestampTick(ts, FlowBucketUnit.QUARTER))
        } finally {
            java.util.TimeZone.setDefault(saved)
        }
    }

    // ─── TRK-152 — clock tick ───────────────────────────────────────

    @Test
    fun `TRK-152 next tick is local midnight plus a second, capped at one hour`() {
        val zone = ZoneId.of("Europe/Rome")
        val fiveToMidnight = LocalDate.of(2026, 5, 10).atTime(23, 55).atZone(zone).toInstant().toEpochMilli()
        assertEquals(5 * 60_000L + 1_000L, msUntilNextTick(fiveToMidnight, zone))
        val morning = LocalDate.of(2026, 5, 10).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(60 * 60_000L, msUntilNextTick(morning, zone))
        // DST night (2026-03-29 in Rome): 23:30 → midnight still 30 min away.
        val dstEve = LocalDate.of(2026, 3, 28).atTime(23, 30).atZone(zone).toInstant().toEpochMilli()
        assertEquals(30 * 60_000L + 1_000L, msUntilNextTick(dstEve, zone))
        // Half a second before midnight: the second of slack still applies.
        val atMidnight = LocalDate.of(2026, 5, 11).atStartOfDay(zone).toInstant().toEpochMilli() - 500
        assertEquals(1_500L, msUntilNextTick(atMidnight, zone))
    }

    // ─── TRK-147 — shared filters ───────────────────────────────────

    @Test
    fun `TRK-147 clearFilters resets category and range but keeps the period`() {
        TrackerUiState.period.value = TrackingPeriod.Y1
        TrackerUiState.categoryFilter.value = "Food"
        TrackerUiState.timeRangeFilter.value = 1L to 2L
        try {
            TrackerUiState.clearFilters()
            assertNull(TrackerUiState.categoryFilter.value)
            assertNull(TrackerUiState.timeRangeFilter.value)
            assertEquals(TrackingPeriod.Y1, TrackerUiState.period.value)
        } finally {
            TrackerUiState.period.value = TrackingPeriod.D30
        }
    }

    // ─── TRK-169 / TRK-171 — tracker home ───────────────────────────

    @Test
    fun `TRK-169 events become signed lite events, internal zero flows dropped`() {
        val lite = buildPaymentEventLite(
            listOf(
                ev("i", INCOMING, 100UL, 10),
                ev("o", OUTGOING, 40UL, null),
                ev("s", OUTGOING, 5_200UL, 20, fee = 200UL, kind = SEND_TO_SELF),
                ev("c", INCOMING, 5_000UL, 20, kind = SEND_TO_SELF),
            ),
        )
        assertEquals(listOf(100L to 10L, -40L to null, -200L to 20L), lite.map { it.amountSat to it.blockTimeUnix })
    }

    @Test
    fun `TRK-171 tracker and net worth periods round-trip`() {
        for (p in TrackingPeriod.entries) assertEquals(p, p.toNetWorthPeriod().toTrackingPeriod())
        for (p in NetWorthPeriod.entries) assertEquals(p, p.toTrackingPeriod().toNetWorthPeriod())
    }

    // ─── TRK-049 — crypto kind wire values ──────────────────────────

    @Test
    fun `TRK-049 wire values map to kinds and anything else is REGULAR`() {
        for (k in CryptoEventKind.entries) assertEquals(k, CryptoEventKind.fromWire(k.wireValue))
        assertEquals(CryptoEventKind.REGULAR, CryptoEventKind.fromWire(null))
        assertEquals(CryptoEventKind.REGULAR, CryptoEventKind.fromWire("bogus"))
        assertEquals(CryptoEventKind.REGULAR, CryptoEventKind.fromWire("AIRDROP"))
        assertEquals(9, CryptoEventKind.entries.map { it.wireValue }.toSet().size)
    }

    // ─── TRK-076 / TRK-077 — recurring classification ───────────────

    @Test
    fun `TRK-076 weekly tolerance is plus minus one day and other intervals are rejected`() {
        assertEquals(RecurringFrequency.WEEKLY, classifyFrequency(listOf(5 * day, 9 * day)))
        assertNull(classifyFrequency(listOf(7 * day, 10 * day)))
        assertNull(classifyFrequency(listOf(4 * day)))
        assertNull(classifyFrequency(listOf(15 * day, 15 * day)))
        assertEquals(RecurringFrequency.MONTHLY, classifyFrequency(listOf(25 * day, 35 * day)))
        assertNull(classifyFrequency(listOf(36 * day)))
        assertNull(classifyFrequency(emptyList()))
    }

    @Test
    fun `TRK-077 confidence bands`() {
        assertEquals(RecurringConfidence.HIGH, scoreConfidence(4, day.toDouble(), 0.05))
        assertEquals(RecurringConfidence.MEDIUM, scoreConfidence(4, day.toDouble(), 0.08))
        assertEquals(RecurringConfidence.MEDIUM, scoreConfidence(5, 3.0 * day, 0.10))
        assertEquals(RecurringConfidence.LOW, scoreConfidence(5, day.toDouble(), 0.15))
        assertEquals(RecurringConfidence.LOW, scoreConfidence(4, 4.0 * day, 0.01))
        assertEquals(RecurringConfidence.LOW, scoreConfidence(2, 0.0, 0.0))
    }

    @Test
    fun `TRK-077 amounts 10 to 20 percent apart are still surfaced, but as LOW`() {
        val now = 400 * day
        val events = listOf(1_015UL, 860UL, 1_015UL, 1_015UL).mapIndexed { i, a ->
            ev("t$i", OUTGOING, a, now - (4 - i) * 30 * day, category = "Gym")
        }
        val c = detectRecurring(events, now).single()
        assertEquals(RecurringConfidence.LOW, c.confidence)
    }

    // ─── TRK-123 / TRK-124 / TRK-125 — forecast and banner ──────────

    private fun bill(due: UInt, amount: ULong = 1_000UL, lastPaid: Long? = null) =
        TrackingBillFfi(1, "Rent", amount, due, lastPaid, null, 0)

    @Test
    fun `TRK-123 a day-31 bill lands on the last day of shorter months`() {
        val zone = ZoneId.systemDefault()
        val now = LocalDate.of(2026, 1, 20).atTime(12, 0).atZone(zone).toEpochSecond()
        val series = forecastCashFlow(emptyList(), listOf(bill(31u)), emptyList(), 10_000UL, now, horizonDays = 45)
        val drops = series.zipWithNext().filter { (a, b) -> b.second < a.second }.map { (_, b) ->
            java.time.Instant.ofEpochSecond(b.first).atZone(zone).toLocalDate()
        }
        assertEquals(listOf(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28)), drops)
        assertEquals(8_000L, series.last().second)
    }

    @Test
    fun `TRK-124 forecast end is the last projected balance, null without a series`() {
        assertEquals(-5L, CashFlowForecastEnd(listOf(1L to 10L, 2L to -5L)))
        assertNull(CashFlowForecastEnd(emptyList()))
        val now = 1_800_000_000L
        val end = CashFlowForecastEnd(forecastCashFlow(emptyList(), listOf(bill(15u, 5_000UL)), emptyList(), 1_000UL, now))
        assertNotNull(end)
        assertTrue(end < 0, "a forecast that ends below zero must be negative (red card)")
    }

    @Test
    fun `TRK-125 banner window follows the given zone, not UTC`() {
        val rome = ZoneId.of("Europe/Rome")
        // 2026-04-30 23:30 UTC is already May 1st in Rome.
        val t = utc(2026, 4, 30, 23) + 30 * 60
        assertTrue(shouldShowMonthlyReviewBanner(t, 0L, rome))
        assertFalse(shouldShowMonthlyReviewBanner(t, 0L, ZoneOffset.UTC))
        val day3Late = LocalDate.of(2026, 5, 3).atTime(23, 59).atZone(rome).toEpochSecond()
        assertTrue(shouldShowMonthlyReviewBanner(day3Late, 0L, rome))
        assertFalse(shouldShowMonthlyReviewBanner(day3Late + 120, 0L, rome))
    }

    // ─── TRK-093 — bill paid this month ─────────────────────────────

    @Test
    fun `TRK-093 a bill is paid only when its last payment falls in the current month`() {
        val utc = ZoneOffset.UTC
        val may = YearMonth.of(2026, 5)
        assertTrue(isPaidInMonth(utc(2026, 5, 1, 0), may, utc))
        assertTrue(isPaidInMonth(utc(2026, 5, 31, 23), may, utc))
        assertFalse(isPaidInMonth(utc(2026, 4, 30, 23), may, utc))
        assertFalse(isPaidInMonth(utc(2025, 5, 10), may, utc))
        assertFalse(isPaidInMonth(null, may, utc))
        // Zone matters at the month edge.
        assertTrue(isPaidInMonth(utc(2026, 4, 30, 23), may, ZoneId.of("Europe/Rome")))
    }

    // ─── TRK-159 / TRK-160 — CSV write + export flow ────────────────

    private fun tempDir(): File = kotlin.io.path.createTempDirectory("bhodl-csv-").toFile()

    @Test
    fun `TRK-159 atomic write replaces the target and leaves no temp file`() {
        val dir = tempDir()
        try {
            val target = File(dir, "out.csv").apply { writeText("old") }
            writeFileAtomically(target, "new,content\n")
            assertEquals("new,content\n", target.readText())
            assertEquals(listOf("out.csv"), dir.list()!!.toList())
            val fresh = File(dir, "fresh.csv")
            writeFileAtomically(fresh, "x")
            assertEquals("x", fresh.readText())
            assertEquals(setOf("out.csv", "fresh.csv"), dir.list()!!.toSet())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `TRK-159 a failed write keeps the previous file intact`() {
        val dir = tempDir()
        try {
            val missingDir = File(dir, "nope/out.csv")
            assertTrue(runCatching { writeFileAtomically(missingDir, "x") }.isFailure)
            assertFalse(missingDir.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    private val events = listOf(ev("aa", INCOMING, 100UL, 1, category = "Food"))

    @Test
    fun `TRK-160 nothing happens without events, when privacy is refused or the chooser is cancelled`() = runBlocking {
        var asked = 0
        assertNull(exportEventsAsCsv(emptyList(), emptyMap(), confirmPrivate = { asked++; true }, chooseTarget = { error("no") }))
        assertEquals(0, asked)
        assertNull(exportEventsAsCsv(events, emptyMap(), confirmPrivate = { false }, chooseTarget = { error("no") }))
        assertNull(exportEventsAsCsv(events, emptyMap(), confirmPrivate = { true }, chooseTarget = { null }))
    }

    @Test
    fun `TRK-160 export writes a csv file, forcing the extension`() = runBlocking {
        val dir = tempDir()
        try {
            val result = exportEventsAsCsv(
                events, mapOf("w" to "Cold"),
                confirmPrivate = { true },
                chooseTarget = { File(dir, "report") },
                confirmOverwrite = { error("no existing file") },
            )
            assertNull(result)
            val text = File(dir, "report.csv").readText()
            assertTrue(text.lines().first().startsWith("timestamp_iso,"))
            assertTrue(text.contains("Cold") && text.contains("Food"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `TRK-160 an existing file is overwritten only after confirmation`() = runBlocking {
        val dir = tempDir()
        try {
            val target = File(dir, "a.csv").apply { writeText("keep") }
            assertNull(exportEventsAsCsv(events, emptyMap(), { true }, { target }, { false }))
            assertEquals("keep", target.readText())
            assertNull(exportEventsAsCsv(events, emptyMap(), { true }, { target }, { true }))
            assertTrue(target.readText().startsWith("timestamp_iso"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `TRK-160 a write error is returned as a message`() = runBlocking {
        val dir = tempDir()
        try {
            val msg = exportEventsAsCsv(events, emptyMap(), { true }, { File(dir, "missing/x.csv") }, { true })
            assertNotNull(msg)
            assertTrue(msg.isNotBlank())
        } finally {
            dir.deleteRecursively()
        }
    }
}
