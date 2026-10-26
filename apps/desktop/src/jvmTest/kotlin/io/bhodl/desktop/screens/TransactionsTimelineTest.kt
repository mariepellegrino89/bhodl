package io.bhodl.desktop.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.bhodl.desktop.data.TrackerCache
import io.bhodl.desktop.navigation.TrackerSection
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.ffi.PaymentDirection
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.PaymentKind
import io.bhodl.ffi.WalletKindFfi
import io.bhodl.ffi.WalletRecordFfi
import io.bhodl.ui.theme.BhodlTheme
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Transactions redesign 2026-10 (proposal C) — timeline grouping, the
 * summaries, search, the "to review" lane and a dark / light render.
 */
@OptIn(ExperimentalTestApi::class)
class TransactionsTimelineTest {

    // Fixed "now" (ScreenshotCheck): the render shows the same dates every day.
    private val now = ScreenshotCheck.NOW_UNIX
    private val day = 86_400L

    private fun ev(
        id: String,
        dir: PaymentDirection,
        sat: ULong,
        ts: Long?,
        cat: String?,
        wallet: String = "w1",
    ) = PaymentEvent(wallet, id, PaymentKind.ON_CHAIN, dir, sat, null, ts?.toULong(), null, cat, null)

    @BeforeTest fun setUp() { SettingsStore.reset(); TrackerCache.reset(); TrackerUiState.clearFilters(); TrackerUiState.period.value = TrackingPeriod.D30; ScreenshotCheck.pinClock() }
    @AfterTest fun tearDown() { ScreenshotCheck.releaseClock(); TrackerUiState.clearFilters(); TrackerCache.reset(); SettingsStore.reset() }

    @Test
    fun `days come newest first, pending first, each with its net`() {
        val base = 1_790_000_000L - Math.floorMod(1_790_000_000L, day) + 12 * 3_600L
        val days = groupByDay(
            listOf(
                ev("a", PaymentDirection.OUTGOING, 100UL, base - day, "Spesa"),
                ev("b", PaymentDirection.INCOMING, 1_000UL, base, "Stipendio"),
                ev("c", PaymentDirection.OUTGOING, 50UL, base + 60, null),
                ev("p", PaymentDirection.OUTGOING, 10UL, null, null),
            ),
            ZoneOffset.UTC,
        )
        assertEquals(listOf(null, base / day, base / day - 1), days.map { it.epochDay })
        assertEquals(listOf(-10L, 950L, -100L), days.map { it.netSat })
        assertEquals(listOf("c", "b"), days[1].events.map { it.txidOrPaymentId }, "newest first within a day")
    }

    @Test
    fun `outflows by category, frequent categories and search`() {
        val evs = listOf(
            ev("1", PaymentDirection.OUTGOING, 300UL, now, "Spesa"),
            ev("2", PaymentDirection.OUTGOING, 200UL, now, "Spesa"),
            ev("3", PaymentDirection.OUTGOING, 400UL, now, "Casa"),
            ev("4", PaymentDirection.INCOMING, 9_000UL, now, "Stipendio"),
            ev("5", PaymentDirection.OUTGOING, 70UL, now, null, wallet = "w2"),
        )
        assertEquals(listOf<Pair<String?, Long>>("Spesa" to 500L, "Casa" to 400L, null to 70L), outflowByCategory(evs))
        assertEquals(listOf("Spesa", "Casa", "Stipendio"), frequentCategories(evs))
        val label: (String) -> String? = { if (it == "w2") "Hot" else "Cold" }
        assertEquals(listOf("3"), searchLedger(evs, "cas", label).map { it.txidOrPaymentId })
        assertEquals(listOf("5"), searchLedger(evs, "hot", label).map { it.txidOrPaymentId })
        assertEquals(listOf("4"), searchLedger(evs, "9000", label).map { it.txidOrPaymentId })
        assertEquals(5, searchLedger(evs, "  ", label).size)
    }

    private val wallets = listOf(
        WalletRecordFfi("w1", "Main", WalletKindFfi.WATCH_ADDRESSES, "[\"tb1qaaa\"]", "signet", 1),
        WalletRecordFfi("w2", "Wallet 2", WalletKindFfi.WATCH_ADDRESSES, "[\"tb1qbbb\"]", "signet", 1),
    )

    private fun sample() = listOf(
        ev("s1", PaymentDirection.INCOMING, 3_400_000UL, now - 3 * day, "Stipendio", "w2"),
        ev("o1", PaymentDirection.OUTGOING, 420_000UL, now - day, "Spesa"),
        ev("o2", PaymentDirection.OUTGOING, 18_000UL, null, null),
        ev("o3", PaymentDirection.OUTGOING, 15_000UL, now - 5 * day, "Abbonamenti"),
        ev("o4", PaymentDirection.OUTGOING, 80_000UL, now - 5 * day - 3_600, null),
        ev("o5", PaymentDirection.OUTGOING, 410_000UL, now - 8 * day, "Ristoranti"),
        ev("o6", PaymentDirection.OUTGOING, 120_000UL, now - 9 * day, "Spesa"),
    )

    private fun seed(events: List<PaymentEvent> = sample()) = TrackerCache.seedForTest(
        wallets = wallets,
        events = events,
        categories = events.mapNotNull { it.category }.distinct().sorted(),
    )

    @Test
    fun `summaries, review lane and search work on the real section`() = runDesktopComposeUiTest {
        seed()
        var quick: Pair<String, String>? = null
        setContent {
            BhodlTheme {
                HistoryTabBody(
                    wallets = wallets, refreshing = false, groupedWallets = emptyList(), excludedWalletIds = emptySet(),
                    onToggleInclude = { _, _ -> }, events = sample(), filteredEvents = sample(), categories = listOf("Spesa"),
                    categoryFilter = null, timeRangeFilter = null, onClearTimeRange = {}, onSelectCategory = {}, onEditCategory = {},
                    periodEvents = sample(), onQuickCategorize = { e, c -> quick = e.txidOrPaymentId to c },
                )
            }
        }
        onNodeWithTag(TxTimelineTags.IN_OUT).assertExists()
        onNodeWithTag(TxTimelineTags.WHERE).assertExists()
        waitUntilAtLeastOneExists(hasText("TO REVIEW · 2"))
        onAllNodesWithTag(TxTimelineTags.QUICK)[0].performClick()
        assertEquals("o2" to "Spesa", quick)
        assertEquals(7, onAllNodesWithTag(TxTimelineTags.ROW).fetchSemanticsNodes().size)
    }

    @Test
    fun `the ledger follows the period, search narrows it and show more extends it`() = runDesktopComposeUiTest {
        val many = (1..60).map { ev("t$it", PaymentDirection.OUTGOING, 1_000UL, now - it * 600L, "Spesa") } +
            ev("old", PaymentDirection.OUTGOING, 5_000UL, now - 60 * day, "Casa")
        seed(many)
        setContent { BhodlTheme { TrackingScreen(section = TrackerSection.TRANSACTIONS) } }
        waitUntilAtLeastOneExists(hasText("Showing 50 of 60", substring = true))
        onNodeWithTag(TxTimelineTags.SHOW_MORE).performScrollTo().performClick()
        waitUntil(timeoutMillis = 3_000) { onAllNodesWithTag(TxTimelineTags.ROW).fetchSemanticsNodes().size == 60 }
        onNodeWithTag(TxTimelineTags.SEARCH).performScrollTo().performTextInput("t59")
        waitUntil(timeoutMillis = 3_000) { onAllNodesWithTag(TxTimelineTags.ROW).fetchSemanticsNodes().size == 1 }
    }

    @Test
    fun `render the transactions for the visual review`() = runDesktopComposeUiTest(width = 1440, height = 1300) {
        seed()
        for ((dark, name) in listOf(true to "transactions-dark", false to "transactions-light")) {
            setContent { BhodlTheme(useDarkTheme = dark) { TrackingScreen(section = TrackerSection.TRANSACTIONS) } }
            waitUntilAtLeastOneExists(hasTestTag(TxTimelineTags.REVIEW), timeoutMillis = 5_000)
            waitForIdle()
            screenshot(name)
        }
    }
}
