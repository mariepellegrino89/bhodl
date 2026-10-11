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
import io.bhodl.desktop.wallet.FeeBumpLog
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
 * - non-empty list → row renders; row click / Bump fee / Speed up fire.
 * - dense table (UX review 2026-10): status chips, signed amounts,
 *   filters, sorting and fee-bump grouping.
 * - BIP-329 label editing has moved to [TransactionDetailsScreen]:
 *   the history row now shows a read-only label preview when set, and
 *   the full editor is accessible via the "Details" tap. Label-save
 *   tests now live in [TransactionDetailsScreenTest].
 * - "Bump fee" only shows for outgoing pending transactions.
 * - "Speed up" only shows for incoming pending transactions.
 */
@OptIn(ExperimentalTestApi::class)
class HistoryScreenTest {

    @AfterTest
    fun teardownStore() {
        WalletStore.clear()
        FeeBumpLog.clear()
    }

    @Test
    fun `empty state when wallet has no transactions`() = runDesktopComposeUiTest {
        WalletStore.set(FakeWalletPort())
        setContent {
            BhodlTheme {
                HistoryScreen(onBack = {}, onBumpFee = {})
            }
        }
        waitUntilAtLeastOneExists(hasText("No transactions yet.", substring = true))
    }

    @Test
    fun `Bump fee is shown for pending outgoing tx and fires the callback with the txid`() =
        runDesktopComposeUiTest {
            val pendingTxid = "deadbeef".repeat(8)
            val fake = FakeWalletPort().apply {
                transactions += TransactionRecord(
                    txid = pendingTxid,
                    netSat = -1155L,
                    feeSat = 155UL,
                    confirmationHeight = null,
                    timestamp = 1_700_000_000UL,
                )
            }
            WalletStore.set(fake)

            var bumpedTxid: String? = null
            setContent {
                BhodlTheme {
                    HistoryScreen(
                        onBack = {},
                        onBumpFee = { bumpedTxid = it },
                    )
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(HistoryTags.bumpFor(pendingTxid)))

            onNodeWithTag(HistoryTags.bumpFor(pendingTxid)).performClick()
            assertEquals(pendingTxid, bumpedTxid)
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
                    onBumpFee = {},
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

    @Test
    fun `Bump fee is hidden for confirmed transactions`() = runDesktopComposeUiTest {
        val txid = "cafebabe".repeat(8)
        val fake = FakeWalletPort().apply {
            transactions += TransactionRecord(
                txid = txid,
                netSat = -1155L,
                feeSat = 155UL,
                confirmationHeight = 100u, // confirmed → no bump
                timestamp = 1_700_000_000UL,
            )
        }
        WalletStore.set(fake)

        setContent {
            BhodlTheme {
                HistoryScreen(onBack = {}, onBumpFee = {})
            }
        }
        // Sync point: wait for the row card to appear (always present once loaded).
        waitUntilAtLeastOneExists(hasTestTag(HistoryTags.row(txid)))

        // The bump button should NOT exist on a confirmed row.
        val bumpExists = runCatching {
            onNodeWithTag(HistoryTags.bumpFor(txid)).assertIsDisplayed()
        }.isSuccess
        assertTrue(!bumpExists, "Bump fee must not render on confirmed transactions")
    }

    // -----------------------------------------------------------------
    // CPFP Speed up — Iteration 2.6
    // -----------------------------------------------------------------

    @Test
    fun `Speed up shows on incoming pending regardless of feeSat`() = runDesktopComposeUiTest {
        val txid = "1234abcd".repeat(8)
        val fake = FakeWalletPort().apply {
            // The discriminator we depend on is `netSat > 0` (incoming),
            // not the absence of feeSat — Esplora populates feeSat on
            // every tx it indexes, including the receiver's view of a
            // faucet drop. The realistic shape for a faucet-style
            // incoming pending is: confirmationHeight=null, netSat>0,
            // feeSat=<some value>. Both feeSat=null (no Esplora info)
            // and feeSat=<value> (Esplora populated) MUST surface the
            // Speed up button.
            transactions += TransactionRecord(
                txid = txid,
                netSat = 50_000L,
                feeSat = 6_573UL, // realistic Esplora-supplied tx fee
                confirmationHeight = null,
                timestamp = 1_700_000_000UL,
            )
        }
        WalletStore.set(fake)

        var cpfpTxid: String? = null
        setContent {
            BhodlTheme {
                HistoryScreen(
                    onBack = {},
                    onBumpFee = {},
                    onCpfpIncoming = { cpfpTxid = it },
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(HistoryTags.cpfpFor(txid)))

        onNodeWithTag(HistoryTags.cpfpFor(txid)).performClick()
        assertEquals(txid, cpfpTxid)
    }

    @Test
    fun `Bump fee is hidden on incoming pending even when feeSat is populated`() =
        runDesktopComposeUiTest {
            // Regression test for a reported bug:
            // Esplora's view of an incoming faucet drop carries
            // feeSat ≠ null (the indexer computes the tx-wide miner
            // fee). The previous condition `feeSat != null` made
            // Bump fee leak onto incoming rows; the user could tap
            // it and the build would fail because BDK cannot sign
            // the sender's inputs.
            val txid = "aa56dec0".repeat(8)
            val fake = FakeWalletPort().apply {
                transactions += TransactionRecord(
                    txid = txid,
                    netSat = 519_462L,
                    feeSat = 6_573UL,
                    confirmationHeight = null,
                    timestamp = 1_700_000_000UL,
                )
            }
            WalletStore.set(fake)
            setContent {
                BhodlTheme {
                    HistoryScreen(onBack = {}, onBumpFee = {}, onCpfpIncoming = {})
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(HistoryTags.cpfpFor(txid)))
            // Speed up MUST render (it's incoming + pending).
            onNodeWithTag(HistoryTags.cpfpFor(txid)).assertIsDisplayed()
            // Bump fee MUST NOT render.
            val bumpExists = runCatching {
                onNodeWithTag(HistoryTags.bumpFor(txid)).assertIsDisplayed()
            }.isSuccess
            assertTrue(
                !bumpExists,
                "Bump fee must not render on incoming pending — BDK cannot bump a tx it did not sign",
            )
        }

    @Test
    fun `Speed up is hidden on outgoing pending (only Bump fee renders)`() =
        runDesktopComposeUiTest {
            val txid = "ddccbbaa".repeat(8)
            val fake = FakeWalletPort().apply {
                transactions += TransactionRecord(
                    txid = txid,
                    netSat = -1_000L,
                    feeSat = 200UL, // we sent it → fee known
                    confirmationHeight = null,
                    timestamp = 1_700_000_000UL,
                )
            }
            WalletStore.set(fake)

            setContent {
                BhodlTheme {
                    HistoryScreen(onBack = {}, onBumpFee = {}, onCpfpIncoming = {})
                }
            }
            // Sync point: wait for the row card to appear (always present once loaded).
            waitUntilAtLeastOneExists(hasTestTag(HistoryTags.row(txid)))

            // Bump fee shows; Speed up does NOT.
            onNodeWithTag(HistoryTags.bumpFor(txid)).assertIsDisplayed()
            val cpfpExists = runCatching {
                onNodeWithTag(HistoryTags.cpfpFor(txid)).assertIsDisplayed()
            }.isSuccess
            assertTrue(!cpfpExists, "Speed up must not render on outgoing pending")
        }

    @Test
    fun `Speed up is hidden on confirmed incoming`() = runDesktopComposeUiTest {
        val txid = "abcdef00".repeat(8)
        val fake = FakeWalletPort().apply {
            transactions += TransactionRecord(
                txid = txid,
                netSat = 50_000L,
                feeSat = null,
                confirmationHeight = 100u, // confirmed
                timestamp = 1_700_000_000UL,
            )
        }
        WalletStore.set(fake)

        setContent {
            BhodlTheme {
                HistoryScreen(onBack = {}, onBumpFee = {}, onCpfpIncoming = {})
            }
        }
        // Sync point: wait for the row card to appear.
        waitUntilAtLeastOneExists(hasTestTag(HistoryTags.row(txid)))

        val cpfpExists = runCatching {
            onNodeWithTag(HistoryTags.cpfpFor(txid)).assertIsDisplayed()
        }.isSuccess
        assertTrue(!cpfpExists, "Speed up must not render on confirmed incoming")
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

    @Test
    fun `a fee-bumped transaction groups its replaced original under a chip`() = runDesktopComposeUiTest {
        val original = record("3333aaaa", -10_000, null, ts = null)
        val replacement = record("3333bbbb", -10_400, null, ts = null)
        FeeBumpLog.record(original, original.txid, replacement.txid)
        // BDK dropped the original: only the replacement is listed.
        val fake = FakeWalletPort().apply { transactions += replacement }
        WalletStore.set(fake)
        setContent { BhodlTheme { HistoryScreen(syncOnEnter = false) } }
        waitUntilAtLeastOneExists(hasTestTag(HistoryTags.feeBumped(replacement.txid)))
        assertTrue(onAllNodesWithTag(HistoryTags.replacedRow(original.txid)).fetchSemanticsNodes().isEmpty())

        onNodeWithTag(HistoryTags.feeBumped(replacement.txid)).performClick()
        waitUntilAtLeastOneExists(hasTestTag(HistoryTags.replacedRow(original.txid)))
    }
}
