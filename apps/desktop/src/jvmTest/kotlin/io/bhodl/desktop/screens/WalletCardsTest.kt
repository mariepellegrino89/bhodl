package io.bhodl.desktop.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.bhodl.desktop.data.TrackerCache
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.wallet.FakeWalletPort
import io.bhodl.desktop.wallet.SyncCache
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.ffi.PaymentDirection
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.PaymentKind
import io.bhodl.ffi.UtxoRecord
import io.bhodl.ffi.WalletKindFfi
import io.bhodl.ffi.WalletRecordFfi
import io.bhodl.ui.theme.BhodlTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Wallet redesign 2026-10 (mix of proposals A and C) — account cards,
 * the coin map, the header "Receive" shortcut and a dark / light render.
 */
@OptIn(ExperimentalTestApi::class)
class WalletCardsTest {

    // Fixed "now" (ScreenshotCheck): the render shows the same dates every day.
    private val now = ScreenshotCheck.NOW_UNIX
    private val day = 86_400L

    private val wallets = listOf(
        WalletRecordFfi("w1", "Cold storage", WalletKindFfi.WATCH_XPUB, "wpkh([00]xpub)", "signet", 1),
        WalletRecordFfi("w2", "Spending", WalletKindFfi.WATCH_ADDRESSES, "[\"tb1qbbb\"]", "signet", 1),
    )
    private val events = listOf(
        PaymentEvent("w1", "a", PaymentKind.ON_CHAIN, PaymentDirection.INCOMING, 2_000_000UL, null, (now - 20 * day).toULong(), null, null, null),
        PaymentEvent("w1", "b", PaymentKind.ON_CHAIN, PaymentDirection.OUTGOING, 500_000UL, 300UL, (now - 5 * day).toULong(), null, null, null),
        PaymentEvent("w2", "c", PaymentKind.ON_CHAIN, PaymentDirection.INCOMING, 80_000UL, null, (now - 2 * day).toULong(), null, null, null),
    )
    private val utxos = listOf(
        UtxoRecord("aa:0", 5_200_000UL, "tb1qa", false, true),
        UtxoRecord("bb:1", 2_100_000UL, "tb1qb", true, true),
        UtxoRecord("cc:0", 900_000UL, "tb1qc", false, true),
        UtxoRecord("dd:2", 250_000UL, "tb1qd", false, false),
        UtxoRecord("ee:0", 40_000UL, "tb1qe", false, true),
    )

    @BeforeTest fun setUp() { SettingsStore.reset(); TrackerCache.reset(); SyncCache.clear(); ScreenshotCheck.pinClock() }
    @AfterTest fun tearDown() { ScreenshotCheck.releaseClock(); WalletStore.clear(); TrackerCache.reset(); SettingsStore.reset() }

    private fun seed() = TrackerCache.seedForTest(
        wallets = wallets,
        watchBalances = mapOf("w1" to 8_490_000UL, "w2" to 80_000UL),
        events = events,
        staleWalletIds = setOf("w2"),
    )

    @Test
    fun `balance series rebuilds the past from the payments`() {
        val s = walletBalanceSeries("w1", 1_500_000L, events, now)
        assertEquals(31, s.size)
        assertEquals(0L, s.first(), "before the first payment")
        assertEquals(1_500_000L, s.last())
        assertEquals(2_000_000L, s[30 - 10], "between the receive and the send")
    }

    @Test
    fun `cards show every wallet, highlight the open one and open another`() = runDesktopComposeUiTest(width = 1200, height = 400) {
        seed()
        var opened: String? = null
        setContent { BhodlTheme { WalletCardsStrip(currentId = "w1", onOpenWallet = { opened = it }, onAdd = {}) } }
        onNodeWithTag(WalletCardsTags.card("w1")).assertIsSelected()
        onNodeWithTag(WalletCardsTags.card("w2")).assertIsNotSelected().performClick()
        assertEquals("w2", opened)
        onNodeWithTag(WalletCardsTags.ADD).assertExists()
    }

    @Test
    fun `a wallet whose sync failed says stale and its question mark says what to do`() = runDesktopComposeUiTest(width = 1200, height = 400) {
        seed()
        setContent { BhodlTheme { WalletCardsStrip(currentId = "w1", onOpenWallet = {}, onAdd = {}) } }
        fun inCard(id: String, m: androidx.compose.ui.test.SemanticsMatcher) =
            onAllNodes(m and androidx.compose.ui.test.hasAnyAncestor(hasTestTag(WalletCardsTags.card(id))), useUnmergedTree = true)
        inCard("w2", androidx.compose.ui.test.hasText("stale")).assertCountEquals(1)
        inCard("w2", hasTestTag(io.bhodl.desktop.ui.HELP_TIP_TAG) and androidx.compose.ui.test.hasContentDescription("press Sync", substring = true))
            .assertCountEquals(1)
        inCard("w1", hasTestTag(io.bhodl.desktop.ui.HELP_TIP_TAG)).assertCountEquals(0)
    }

    @Test
    fun `the workspace shows the cards, the coin map and a receive shortcut`() = runDesktopComposeUiTest(width = 1440, height = 900) {
        seed()
        WalletStore.set(FakeWalletPort(balance = 8_490_000u).apply { this.utxos += this@WalletCardsTest.utxos }, walletId = "w1", label = "Cold storage")
        setContent { BhodlTheme { DashboardScreen(initialTab = WalletTab.UTXOS, onOpenWallet = {}) } }
        waitUntilAtLeastOneExists(hasTestTag(WalletCardsTags.COIN_MAP), timeoutMillis = 5_000)
        onNodeWithTag(WalletCardsTags.STRIP).assertExists()
        onNodeWithTag(DashboardTags.RECEIVE).performClick()
        waitUntil(timeoutMillis = 3_000) { onAllNodes(hasTestTag(WalletCardsTags.COIN_MAP)).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun `render the wallet workspace for the visual review`() = runDesktopComposeUiTest(width = 1440, height = 1000) {
        seed()
        WalletStore.set(FakeWalletPort(balance = 8_490_000u).apply { this.utxos += this@WalletCardsTest.utxos }, walletId = "w1", label = "Cold storage")
        for ((dark, name) in listOf(true to "wallet-dark", false to "wallet-light")) {
            setContent { BhodlTheme(useDarkTheme = dark) { DashboardScreen(initialTab = WalletTab.UTXOS, onOpenWallet = {}) } }
            waitUntilAtLeastOneExists(hasTestTag(WalletCardsTags.COIN_MAP), timeoutMillis = 5_000)
            waitForIdle()
            screenshot(name)
        }
    }
}
