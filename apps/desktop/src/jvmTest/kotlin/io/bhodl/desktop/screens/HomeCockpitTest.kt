package io.bhodl.desktop.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.data.TrackerCache
import io.bhodl.desktop.screens.PortfolioFixtures.NOW
import io.bhodl.desktop.screens.PortfolioFixtures.TODAY
import io.bhodl.desktop.screens.PortfolioFixtures.asset
import io.bhodl.desktop.screens.PortfolioFixtures.daysAgo
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.wizard.RegistryStore
import io.bhodl.ffi.PaymentDirection
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.PaymentKind
import io.bhodl.ffi.TrackingBillFfi
import io.bhodl.ui.theme.BhodlTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Home redesign 2026-10 (proposal A) — the "to do" list logic and a
 * dark / light render of the Home for the visual review.
 */
@OptIn(ExperimentalTestApi::class)
class HomeCockpitTest {

    @BeforeTest fun setUp() { SettingsStore.reset(); RegistryStore.clear(); TrackerCache.reset() }
    @AfterTest fun tearDown() { TrackerCache.reset(); RegistryStore.clear(); SettingsStore.reset() }

    private fun bill(id: Long, name: String, day: Int, paidDaysAgo: Long? = null) = TrackingBillFfi(
        id = id, name = name, amountSat = 100_000UL, dueDay = day.toUInt(),
        lastPaidUnix = paidDaysAgo?.let { daysAgo(it) }, displayLabel = null, createdAtUnix = 0,
    )

    private fun event(id: String, dir: PaymentDirection, sat: ULong, daysAgo: Long, category: String?) = PaymentEvent(
        walletId = "w1", txidOrPaymentId = id, kind = PaymentKind.ON_CHAIN, direction = dir, amountSat = sat,
        feeSat = null, timestampUnix = daysAgo(daysAgo).toULong(), blockHeight = null, category = category,
        cryptoEventKind = null,
    )

    @Test
    fun `to-dos come most urgent first and skip what is not due`() {
        // Today is 25 Oct: the 28th is in 3 days, the 15th next month is out of the window.
        val todos = homeTodos(
            nowUnix = NOW,
            reviewDue = true,
            bills = listOf(bill(1, "Luce", 28), bill(2, "Affitto", 15), bill(3, "Gas", 27, paidDaysAgo = 2)),
            recurringCount = 2,
            periodEvents = listOf(
                event("a", PaymentDirection.OUTGOING, 1_000UL, 1, null),
                event("a", PaymentDirection.OUTGOING, 1_000UL, 1, null),
                event("b", PaymentDirection.OUTGOING, 2_000UL, 2, "Spesa"),
            ),
            manualAssets = listOf(asset(7, "Casa", "real_estate", 300_000.0, updatedDaysAgo = 270)),
            limit = 10,
        )
        assertEquals(
            listOf(HomeTodoKind.REVIEW, HomeTodoKind.BILL_DUE, HomeTodoKind.RECURRING, HomeTodoKind.UNCATEGORIZED, HomeTodoKind.STALE_ASSET),
            todos.map { it.kind },
        )
        assertEquals(HomeTodo(HomeTodoKind.BILL_DUE, name = "Luce", days = 3), todos[1])
        assertEquals(1, todos[3].count, "the same txid counts once")
        assertEquals(HomeTodo(HomeTodoKind.STALE_ASSET, name = "Casa", days = 9), todos[4])
    }

    @Test
    fun `nothing pending gives an empty list and the limit caps it`() {
        assertTrue(homeTodos(NOW, false, emptyList(), 0, emptyList(), listOf(asset(1, "x", "cash", 1.0, updatedDaysAgo = 10))).isEmpty())
        val capped = homeTodos(NOW, true, listOf(bill(1, "Luce", 26)), 3, listOf(event("a", PaymentDirection.OUTGOING, 1UL, 1, null)), emptyList())
        assertEquals(4, capped.size)
        assertEquals(1L, capped[1].days)
        assertEquals(TODAY.dayOfMonth, 25)
    }

    @Test
    fun `period flows ignore transfers to self`() {
        val flows = periodFlows(
            listOf(
                event("in", PaymentDirection.INCOMING, 3_000UL, 1, "Stipendio"),
                event("out", PaymentDirection.OUTGOING, 1_000UL, 1, "Spesa"),
                event("self", PaymentDirection.SELF_TRANSFER, 5_000UL, 1, null),
            ),
        )
        assertEquals(PeriodFlows(3_000UL, 1_000UL, 1, 1), flows)
    }

    @Test
    fun `render the home for the visual review`() = runDesktopComposeUiTest(width = 1440, height = 1500) {
        val now = System.currentTimeMillis() / 1000L
        fun ev(id: String, dir: PaymentDirection, sat: ULong, d: Long, cat: String?) = PaymentEvent(
            walletId = "w1", txidOrPaymentId = id, kind = PaymentKind.ON_CHAIN, direction = dir, amountSat = sat,
            feeSat = null, timestampUnix = (now - d * 86_400L).toULong(), blockHeight = null, category = cat, cryptoEventKind = null,
        )
        val events = listOf(
            ev("s1", PaymentDirection.INCOMING, 3_400_000UL, 3, "Stipendio"),
            ev("o1", PaymentDirection.OUTGOING, 420_000UL, 1, "Spesa"),
            ev("o2", PaymentDirection.OUTGOING, 18_000UL, 0, null),
            ev("o3", PaymentDirection.OUTGOING, 15_000UL, 5, "Abbonamenti"),
            ev("s0", PaymentDirection.INCOMING, 3_400_000UL, 34, "Stipendio"),
            ev("o4", PaymentDirection.OUTGOING, 900_000UL, 40, "Casa"),
        )
        TrackerCache.seedForTest(
            lastSyncUnix = now,
            totalAssetSat = 1_260_654UL,
            events = events,
            bills = listOf(bill(1, "Luce", java.time.LocalDate.now().plusDays(3).dayOfMonth)),
        )
        for ((dark, name) in listOf(true to "home-dark", false to "home-light")) {
            setContent {
                BhodlTheme(useDarkTheme = dark) {
                    Box(Modifier.fillMaxSize().background(BhodlTheme.colors.background).verticalScroll(rememberScrollState()).padding(24.dp)) {
                        TrackerHomeSection(onOpenWallet = {}, onAddWatch = {})
                    }
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(HomeCockpitTags.TODO), timeoutMillis = 5_000)
            waitForIdle()
            shot(name)
        }
    }

    private fun SemanticsNodeInteractionsProvider.shot(name: String) {
        val dir = File(System.getProperty("bhodl.screenshots", "build/screenshots")).apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
    }
}
