package io.bhodl.desktop.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.bhodl.desktop.wallet.FakeWalletPort
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.ffi.TransactionRecord
import io.bhodl.ui.theme.BhodlTheme
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Area C (section P) — the workspace transactions table: category shown
 * on the row, STATUS sort and header clicks.
 */
@OptIn(ExperimentalTestApi::class)
class UcWalletTxTrackerTest {

    @AfterTest
    fun tearDown() = WalletStore.clear()

    private fun tx(id: String, net: Long, height: UInt?, ts: ULong? = 1_700_000_000UL) =
        TransactionRecord(txid = id.padEnd(64, '0'), netSat = net, feeSat = 100UL, confirmationHeight = height, timestamp = ts)

    @Test
    fun `TRK-154 status sort puts pending first ascending and unknown depth last`() {
        val pending = tx("a", -1, null, ts = null)
        val deep = tx("b", 1, 10u)
        val shallow = tx("c", 1, 99u)
        val asc = sortTransactions(listOf(deep, pending, shallow), TxSortColumn.STATUS, ascending = true, tipHeight = 100u)
        assertEquals(listOf(pending, shallow, deep), asc)
        val desc = sortTransactions(listOf(deep, pending, shallow), TxSortColumn.STATUS, ascending = false, tipHeight = 100u)
        assertEquals(listOf(deep, shallow, pending), desc)
        // Unknown tip: mined rows count as "very confirmed".
        assertEquals(
            listOf(pending, deep, shallow).map { it.txid },
            sortTransactions(listOf(shallow, deep, pending), TxSortColumn.STATUS, true, tipHeight = null).map { it.txid },
        )
    }

    private fun SemanticsNodeInteractionsProvider.topOf(txid: String) =
        onNodeWithTag(HistoryTags.row(txid)).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun `TRK-154 clicking a header sorts by it and a second click flips the order`() = runDesktopComposeUiTest {
        val small = tx("1111", 1_000, 10u, ts = 1_700_000_300UL)
        val big = tx("2222", 90_000, 11u, ts = 1_700_000_100UL)
        val mid = tx("3333", -5_000, 12u, ts = 1_700_000_200UL)
        WalletStore.set(FakeWalletPort().apply { tipHeight = 20u; transactions += listOf(small, big, mid) })
        setContent { BhodlTheme { HistoryScreen(syncOnEnter = false) } }
        waitUntilAtLeastOneExists(hasTestTag(HistoryTags.row(big.txid)))

        onNodeWithTag(HistoryTags.sortHeader(TxSortColumn.AMOUNT)).performClick()
        waitForIdle()
        assertEquals(listOf(big, small, mid).map { it.txid }, listOf(small, big, mid).sortedBy { topOf(it.txid) }.map { it.txid })

        onNodeWithTag(HistoryTags.sortHeader(TxSortColumn.AMOUNT)).performClick()
        waitForIdle()
        assertEquals(listOf(mid, small, big).map { it.txid }, listOf(small, big, mid).sortedBy { topOf(it.txid) }.map { it.txid })
    }

    @Test
    fun `TRK-036 the saved category appears on the transaction row`() = runDesktopComposeUiTest {
        val t = tx("4444", -2_000, 5u)
        WalletStore.set(FakeWalletPort().apply { transactions += t; txCategories[t.txid] = "Groceries" })
        setContent { BhodlTheme { HistoryScreen(syncOnEnter = false) } }
        waitUntilAtLeastOneExists(hasText("Groceries", substring = true))
    }
}
