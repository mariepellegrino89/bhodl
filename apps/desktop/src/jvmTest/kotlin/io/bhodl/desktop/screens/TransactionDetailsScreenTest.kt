package io.bhodl.desktop.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
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
import kotlin.test.assertTrue

/**
 * Compose UI tests for [`TransactionDetailsScreen`] driven by
 * [`FakeWalletPort`].
 *
 * The screen reads the wallet's transaction list off `Dispatchers.IO`
 * inside a `LaunchedEffect`, so each test settles via
 * `waitUntilAtLeastOneExists` before asserting.
 */
@OptIn(ExperimentalTestApi::class)
class TransactionDetailsScreenTest {

    @AfterTest
    fun teardownStore() {
        WalletStore.clear()
    }

    @Test
    fun `confirmed tx renders status, amount, fee, txid`() = runDesktopComposeUiTest {
        val txid = "abcdef12".repeat(8)
        val fake = FakeWalletPort().apply {
            transactions += TransactionRecord(
                txid = txid,
                netSat = -1155L,
                feeSat = 155UL,
                confirmationHeight = 12_345u,
                timestamp = 1_700_000_000UL,
            )
        }
        WalletStore.set(fake)

        setContent {
            BhodlTheme {
                TransactionDetailsScreen(
                    txid = txid,
                    onBack = {},
                    openExplorer = {},
                    copyToClipboard = {},
                )
            }
        }

        waitUntilAtLeastOneExists(hasTestTag(TransactionDetailsTags.STATUS))

        // Status card mentions the block height.
        waitUntilAtLeastOneExists(hasText("Confirmed in block 12345", substring = true))
        // Amount card: outgoing tx → "Sent 1,155 sat".
        waitUntilAtLeastOneExists(hasText("Sent 1,155 sat", substring = true))
        onNodeWithTag(TransactionDetailsTags.FEE).assertTextContains("155 sat", substring = true)
        // Full txid is displayed mono.
        onNodeWithTag(TransactionDetailsTags.TXID).assertTextContains(txid)
    }

    @Test
    fun `pending tx renders 'In mempool' status`() = runDesktopComposeUiTest {
        val txid = "deadbeef".repeat(8)
        val fake = FakeWalletPort().apply {
            transactions += TransactionRecord(
                txid = txid,
                netSat = 5_000L,
                feeSat = null,
                confirmationHeight = null,
                timestamp = null,
            )
        }
        WalletStore.set(fake)

        setContent {
            BhodlTheme {
                TransactionDetailsScreen(
                    txid = txid,
                    onBack = {},
                    openExplorer = {},
                    copyToClipboard = {},
                )
            }
        }

        waitUntilAtLeastOneExists(hasText("In mempool", substring = true))
        waitUntilAtLeastOneExists(hasText("Received 5,000 sat", substring = true))
    }

    @Test
    fun `Copy fires copyToClipboard with the txid`() = runDesktopComposeUiTest {
        val txid = "feedface".repeat(8)
        val fake = FakeWalletPort().apply {
            transactions += TransactionRecord(
                txid = txid,
                netSat = -1L,
                feeSat = 1UL,
                confirmationHeight = 1u,
                timestamp = 1_700_000_000UL,
            )
        }
        WalletStore.set(fake)

        var copied: String? = null
        setContent {
            BhodlTheme {
                TransactionDetailsScreen(
                    txid = txid,
                    onBack = {},
                    openExplorer = {},
                    copyToClipboard = { copied = it },
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(TransactionDetailsTags.COPY_TXID))

        onNodeWithTag(TransactionDetailsTags.COPY_TXID).performClick()
        assertEquals(txid, copied)
    }

    @Test
    fun `Open in mempool space fires openExplorer with the full URL`() = runDesktopComposeUiTest {
        val txid = "cafebabe".repeat(8)
        val fake = FakeWalletPort().apply {
            transactions += TransactionRecord(
                txid = txid,
                netSat = -1L,
                feeSat = 1UL,
                confirmationHeight = 1u,
                timestamp = 1_700_000_000UL,
            )
        }
        WalletStore.set(fake)

        var openedUrl: String? = null
        setContent {
            BhodlTheme {
                TransactionDetailsScreen(
                    txid = txid,
                    onBack = {},
                    explorerBaseUrl = "https://example.test/tx",
                    openExplorer = { openedUrl = it },
                    copyToClipboard = {},
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(TransactionDetailsTags.OPEN_EXPLORER))

        onNodeWithTag(TransactionDetailsTags.OPEN_EXPLORER).performClick()
        assertEquals("https://example.test/tx/$txid", openedUrl)
    }

    @Test
    fun `unknown txid surfaces 'not found' error`() = runDesktopComposeUiTest {
        WalletStore.set(FakeWalletPort()) // empty transactions

        setContent {
            BhodlTheme {
                TransactionDetailsScreen(
                    txid = "00".repeat(32),
                    onBack = {},
                    openExplorer = {},
                    copyToClipboard = {},
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(TransactionDetailsTags.ERROR))

        // The error card must mention "not found"; the status / amount
        // cards must NOT have rendered.
        waitUntilAtLeastOneExists(hasText("not found", substring = true))
        val statusNodes = onAllNodesWithTag(TransactionDetailsTags.STATUS).fetchSemanticsNodes()
        assertTrue(statusNodes.isEmpty(), "Status card must not render when tx is missing")
    }

    @Test
    fun `Back fires the onBack callback`() = runDesktopComposeUiTest {
        val txid = "11".repeat(32)
        val fake = FakeWalletPort().apply {
            transactions += TransactionRecord(
                txid = txid,
                netSat = -1L,
                feeSat = 1UL,
                confirmationHeight = 1u,
                timestamp = 1_700_000_000UL,
            )
        }
        WalletStore.set(fake)

        var backFired = false
        setContent {
            BhodlTheme {
                TransactionDetailsScreen(
                    txid = txid,
                    onBack = { backFired = true },
                    openExplorer = {},
                    copyToClipboard = {},
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(TransactionDetailsTags.BACK))

        onNodeWithTag(TransactionDetailsTags.BACK).performClick()
        assertTrue(backFired)
    }

    // -----------------------------------------------------------------
    // CPFP via change — Iteration 2.6
    // -----------------------------------------------------------------

    @Test
    fun `Speed up via change shows on outgoing pending and forwards txid`() =
        runDesktopComposeUiTest {
            val txid = "feed1234".repeat(8)
            val fake = FakeWalletPort().apply {
                transactions += TransactionRecord(
                    txid = txid,
                    netSat = -2_000L,
                    feeSat = 200UL, // we sent it
                    confirmationHeight = null, // pending
                    timestamp = 1_700_000_000UL,
                )
            }
            WalletStore.set(fake)

            var rescued: String? = null
            setContent {
                BhodlTheme {
                    TransactionDetailsScreen(
                        txid = txid,
                        onBack = {},
                        onCpfpChangeRescue = { rescued = it },
                        openExplorer = {},
                        copyToClipboard = {},
                    )
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(TransactionDetailsTags.CPFP_CHANGE))

            onNodeWithTag(TransactionDetailsTags.CPFP_CHANGE).performClick()
            assertEquals(txid, rescued)
        }

    @Test
    fun `Speed up via change is hidden on confirmed outgoing`() =
        runDesktopComposeUiTest {
            val txid = "0042feed".repeat(8)
            val fake = FakeWalletPort().apply {
                transactions += TransactionRecord(
                    txid = txid,
                    netSat = -2_000L,
                    feeSat = 200UL,
                    confirmationHeight = 1234u, // confirmed → no CPFP needed
                    timestamp = 1_700_000_000UL,
                )
            }
            WalletStore.set(fake)

            setContent {
                BhodlTheme {
                    TransactionDetailsScreen(
                        txid = txid,
                        onBack = {},
                        openExplorer = {},
                        copyToClipboard = {},
                    )
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(TransactionDetailsTags.STATUS))
            assertTrue(
                onAllNodesWithTag(TransactionDetailsTags.CPFP_CHANGE)
                    .fetchSemanticsNodes().isEmpty(),
                "Speed up via change must not render on confirmed tx",
            )
        }

    @Test
    fun `Speed up via change is hidden on incoming pending even when feeSat is populated`() =
        runDesktopComposeUiTest {
            // Regression test: Esplora populates feeSat on every tx
            // it indexes, including incoming ones. The discriminator
            // for "Speed up via change (CPFP)" must therefore be
            // `netSat < 0`, NOT `feeSat != null`, otherwise a faucet
            // drop's TransactionDetails would expose a button that
            // routes to a wallet-side build path the wallet cannot
            // complete (its inputs are not ours).
            val txid = "incoming".repeat(8)
            val fake = FakeWalletPort().apply {
                transactions += TransactionRecord(
                    txid = txid,
                    netSat = 5_000L,
                    feeSat = 6_573UL, // realistic Esplora-supplied tx fee
                    confirmationHeight = null,
                    timestamp = 1_700_000_000UL,
                )
            }
            WalletStore.set(fake)

            setContent {
                BhodlTheme {
                    TransactionDetailsScreen(
                        txid = txid,
                        onBack = {},
                        openExplorer = {},
                        copyToClipboard = {},
                    )
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(TransactionDetailsTags.STATUS))
            assertTrue(
                onAllNodesWithTag(TransactionDetailsTags.CPFP_CHANGE)
                    .fetchSemanticsNodes().isEmpty(),
                "TxDetails CPFP is for change rescue only; incoming uses History Speed up",
            )
        }
}
