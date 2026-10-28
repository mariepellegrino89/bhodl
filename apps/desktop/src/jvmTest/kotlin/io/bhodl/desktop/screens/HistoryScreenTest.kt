package io.bhodl.desktop.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.bhodl.desktop.wallet.FakeWalletPort
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.ffi.TransactionRecord
import io.bhodl.ui.theme.BhodlTheme
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compose UI tests for [`HistoryScreen`] driven by [`FakeWalletPort`].
 *
 * Covers:
 * - empty list → "No transactions yet" placeholder.
 * - non-empty list → row renders; row click opens the detail pane.
 * - dense table (UX review 2026-10): status chips, signed amounts,
 *   filters and search.
 * - BIP-329 label editing has moved to [TransactionDetailsScreen]:
 *   the history row now shows a read-only label preview when set, and
 *   the full editor is accessible via the "Details" tap. Label-save
 *   tests now live in [TransactionDetailsScreenTest].
 */
@OptIn(ExperimentalTestApi::class)
class HistoryScreenTest {

    @AfterTest
    fun teardownStore() {
        WalletStore.clear()
    }

    @Test
    fun `empty state when wallet has no transactions`() = runDesktopComposeUiTest {
        WalletStore.set(FakeWalletPort())
        setContent {
            BhodlTheme {
                HistoryScreen(onBack = {})
            }
        }
        waitUntilAtLeastOneExists(hasText("No transactions yet.", substring = true))
    }

    @Test
    fun `clicking a row fires onTxClicked and opens the detail pane`() = runDesktopComposeUiTest {
        val txid = "abcd1234".repeat(8)
        val fake = FakeWalletPort().apply {
            transactions += TransactionRecord(
                txid = txid,
                netSat = -1155L,
                feeSat = 155UL,
                confirmationHeight = 200u,
                timestamp = 1_700_000_000UL,
            )
        }
        WalletStore.set(fake)

        var clickedTxid: String? = null
        setContent {
            BhodlTheme {
                HistoryScreen(
                    onBack = {},
                    onTxClicked = { clickedTxid = it },
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(HistoryTags.row(txid)))

        onNodeWithTag(HistoryTags.row(txid)).performClick()
        assertEquals(txid, clickedTxid)
        // List-detail: the side pane opens instead of a separate page.
        waitUntilAtLeastOneExists(hasTestTag(TransactionDetailsTags.PANE))
        onNodeWithTag(TransactionDetailsTags.CLOSE).performClick()
        waitUntil(timeoutMillis = 2_000) {
            onAllNodesWithTag(TransactionDetailsTags.PANE).fetchSemanticsNodes().isEmpty()
        }
    }

    // -----------------------------------------------------------------
    // UX review 2026-10 — dense Transactions tab
    // -----------------------------------------------------------------

    private fun record(id: String, net: Long, height: UInt?, ts: ULong? = 1_700_000_000UL) =
        TransactionRecord(
            txid = id.repeat(8),
            netSat = net,
            feeSat = 200UL,
            confirmationHeight = height,
            timestamp = ts,
        )

    @Test
    fun `status chips follow Pending, Confirming n of 6 and no badge once final`() = runDesktopComposeUiTest {
        val pending = record("0000aaaa", -1_000, null)
        val confirming = record("0000bbbb", 5_000, 99u)
        val final = record("0000cccc", 7_000, 50u)
        val fake = FakeWalletPort().apply {
            tipHeight = 100u
            transactions += listOf(pending, confirming, final)
        }
        WalletStore.set(fake)
        setContent { BhodlTheme { HistoryScreen(syncOnEnter = false) } }
        waitUntilAtLeastOneExists(hasTestTag(HistoryTags.row(final.txid)))

        onNodeWithTag(HistoryTags.status(pending.txid), useUnmergedTree = true).assertTextEquals("Pending")
        onNodeWithTag(HistoryTags.status(confirming.txid), useUnmergedTree = true)
            .assertTextEquals("Confirming 2/6")
        assertTrue(
            onAllNodesWithTag(HistoryTags.status(final.txid), useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty(),
            "a final transaction carries no status badge",
        )
        onNodeWithTag(HistoryTags.confirmations(final.txid), useUnmergedTree = true).assertTextEquals("51")
    }

    @Test
    fun `a mined tx with an unknown tip reads Confirming, never final`() = runDesktopComposeUiTest {
        val mined = record("4444aaaa", 7_000, 50u)
        // tipHeight 0 = never synced: the count is unknown.
        val fake = FakeWalletPort().apply { transactions += mined }
        WalletStore.set(fake)
        setContent { BhodlTheme { HistoryScreen(syncOnEnter = false) } }
        waitUntilAtLeastOneExists(hasTestTag(HistoryTags.row(mined.txid)))
        onNodeWithTag(HistoryTags.status(mined.txid), useUnmergedTree = true).assertTextEquals("Confirming")
        onNodeWithTag(HistoryTags.confirmations(mined.txid), useUnmergedTree = true).assertTextEquals("—")
    }

    @Test
    fun `incoming amounts carry a plus sign, outgoing a minus`() = runDesktopComposeUiTest {
        val incoming = record("1111aaaa", 12_500, 10u)
        val outgoing = record("1111bbbb", -3_000, 11u)
        val fake = FakeWalletPort().apply { transactions += listOf(incoming, outgoing) }
        WalletStore.set(fake)
        setContent { BhodlTheme { HistoryScreen(syncOnEnter = false) } }
        waitUntilAtLeastOneExists(hasTestTag(HistoryTags.row(incoming.txid)))
        onNodeWithTag(HistoryTags.amount(incoming.txid), useUnmergedTree = true).assertTextEquals("+12,500")
        onNodeWithTag(HistoryTags.amount(outgoing.txid), useUnmergedTree = true).assertTextEquals("−3,000")
        // UX review 2026-10 (rec 2) — the unit sits in the column header.
        waitUntilAtLeastOneExists(hasText("(sat)", substring = true))
    }

    @Test
    fun `filter chips and search narrow the table`() = runDesktopComposeUiTest {
        val incoming = record("2222aaaa", 12_500, 10u)
        val outgoing = record("2222bbbb", -3_000, 11u)
        val fake = FakeWalletPort().apply {
            transactions += listOf(incoming, outgoing)
            labels[incoming.txid] = "Salary October"
        }
        WalletStore.set(fake)
        setContent { BhodlTheme { HistoryScreen(syncOnEnter = false) } }
        waitUntilAtLeastOneExists(hasTestTag(HistoryTags.row(outgoing.txid)))

        onNodeWithTag(HistoryTags.filter(TxFilter.INCOMING)).performClick()
        waitUntil(timeoutMillis = 2_000) {
            onAllNodesWithTag(HistoryTags.row(outgoing.txid)).fetchSemanticsNodes().isEmpty()
        }
        onNodeWithTag(HistoryTags.row(incoming.txid)).assertIsDisplayed()

        onNodeWithTag(HistoryTags.filter(TxFilter.ALL)).performClick()
        onNodeWithTag(HistoryTags.SEARCH).performTextInput("salary")
        waitUntil(timeoutMillis = 2_000) {
            onAllNodesWithTag(HistoryTags.row(outgoing.txid)).fetchSemanticsNodes().isEmpty()
        }
        onNodeWithTag(HistoryTags.row(incoming.txid)).assertIsDisplayed()
    }
}
