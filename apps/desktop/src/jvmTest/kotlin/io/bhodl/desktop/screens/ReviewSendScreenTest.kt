package io.bhodl.desktop.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.bhodl.desktop.wallet.FakeWalletPort
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.ffi.PsbtOutput
import io.bhodl.ffi.WalletException
import io.bhodl.ui.theme.BhodlTheme
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Compose UI tests for [`ReviewSendScreen`] driven by [`FakeWalletPort`].
 *
 * The screen renders ONLY the Rust-decoded PSBT summary
 * (`decodePsbt`), cross-checks it against the form's recipient, then
 * on Confirm signs exactly the decoded PSBT and broadcasts it:
 * - happy path: decode → sign → broadcast → `onSent`, in order.
 * - batch: every output of the summary is rendered, not just #1.
 * - mismatch / decode failure / high fee: signing is gated.
 * - sign / broadcast failures surface with their stage prefix.
 */
@OptIn(ExperimentalTestApi::class)
class ReviewSendScreenTest {

    @AfterTest
    fun teardownStore() {
        WalletStore.clear()
    }

    @Test
    fun `Confirm decodes, signs the same PSBT, broadcasts and fires onSent`() =
        runDesktopComposeUiTest {
            val expectedTxid = "abcd".repeat(16)
            val fake = FakeWalletPort(
                psbtFee = 200u,
                signedPsbtBuilder = { "signed-psbt" },
                broadcastTxidBuilder = { expectedTxid },
            )
            WalletStore.set(fake)

            var sentTxid: String? = null
            setContent {
                BhodlTheme {
                    ReviewSendScreen(
                        unsignedPsbtBase64 = "unsigned-psbt",
                        toAddress = TARGET_ADDRESS,
                        amountSat = 1_000L,
                        onSent = { sentTxid = it },
                        onBack = {},
                    )
                }
            }

            waitUntilAtLeastOneExists(hasTestTag(PsbtReviewTags.CARD))
            assertEquals(listOf("unsigned-psbt"), fake.decodeCalls)
            // Fee + totals come from the decoded summary.
            waitUntilAtLeastOneExists(hasText("200 sat (≈ 1.3 sat/vB)"))
            waitUntilAtLeastOneExists(hasText("1,200 sat"))
            onNodeWithTag(ReviewSendTags.CONFIRM).performScrollTo().assertIsEnabled().performClick()

            waitUntil(timeoutMillis = 2_000) { sentTxid != null }
            assertEquals(expectedTxid, sentTxid)
            assertEquals(listOf("unsigned-psbt"), fake.signCalls, "signs exactly the decoded PSBT")
            assertEquals("signed-psbt", fake.broadcastCalls.single().second)
        }

    @Test
    fun `batch review renders every output from the decoded PSBT`() = runDesktopComposeUiTest {
        val second = "tb1psecondrecipient000000000000000000000000000000000000000000000"
        val fake = FakeWalletPort()
        fake.psbtSummaryBuilder = {
            fake.defaultSummary(
                outputs = listOf(
                    PsbtOutput(TARGET_ADDRESS, 1_000u, isMine = false, isChange = false),
                    PsbtOutput(second, 2_500u, isMine = false, isChange = false),
                    PsbtOutput("tb1pchange", 40_000u, isMine = true, isChange = true),
                ),
            )
        }
        WalletStore.set(fake)
        setContent {
            BhodlTheme {
                ReviewSendScreen(
                    unsignedPsbtBase64 = "batch-psbt",
                    toAddress = TARGET_ADDRESS,
                    amountSat = 1_000L,
                    onSent = {},
                    onBack = {},
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(PsbtReviewTags.output(2)))
        // Addresses render in blocks of four, never truncated.
        waitUntilAtLeastOneExists(hasText(io.bhodl.desktop.ui.groupAddressInFours(second) ?: second))
        waitUntilAtLeastOneExists(hasText("2,500 sat · recipient"))
        waitUntilAtLeastOneExists(hasText("40,000 sat · change · yours"))
        // Sent to others = both recipients.
        waitUntilAtLeastOneExists(hasText("3,500 sat"))
    }

    @Test
    fun `PSBT not paying the entered recipient blocks signing`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort().apply {
            summaryRecipient = "tb1pattacker0000000000000000000000000000000000000000000000000000"
        }
        WalletStore.set(fake)
        setContent {
            BhodlTheme {
                ReviewSendScreen(
                    unsignedPsbtBase64 = "swapped",
                    toAddress = TARGET_ADDRESS,
                    amountSat = 1_000L,
                    onSent = {},
                    onBack = {},
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(ReviewSendTags.MISMATCH))
        onNodeWithTag(ReviewSendTags.CONFIRM).performScrollTo().assertIsNotEnabled()
        assertTrue(fake.signCalls.isEmpty())
    }

    @Test
    fun `decode failure blocks signing`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort().apply {
            failDecodePsbt = WalletException.InvalidPsbt(detail = "garbage")
        }
        WalletStore.set(fake)
        setContent {
            BhodlTheme {
                ReviewSendScreen(
                    unsignedPsbtBase64 = "garbage",
                    toAddress = TARGET_ADDRESS,
                    amountSat = 1_000L,
                    onSent = {},
                    onBack = {},
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(ReviewSendTags.DECODE_ERROR))
        onNodeWithTag(ReviewSendTags.CONFIRM).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `fee above the safety limit needs an explicit acknowledgement`() =
        runDesktopComposeUiTest {
            val fake = FakeWalletPort(psbtFee = 80_000u)
            WalletStore.set(fake)
            var sentTxid: String? = null
            setContent {
                BhodlTheme {
                    ReviewSendScreen(
                        unsignedPsbtBase64 = "pricey",
                        toAddress = TARGET_ADDRESS,
                        amountSat = 1_000L,
                        onSent = { sentTxid = it },
                        onBack = {},
                    )
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(PsbtReviewTags.HIGH_FEE_WARNING))
            onNodeWithTag(ReviewSendTags.CONFIRM).performScrollTo().assertIsNotEnabled()
            onNodeWithTag(PsbtReviewTags.HIGH_FEE_ACK).performScrollTo().performClick()
            onNodeWithTag(ReviewSendTags.CONFIRM).performScrollTo().assertIsEnabled().performClick()
            waitUntil(timeoutMillis = 2_000) { sentTxid != null }
            assertEquals(1, fake.signCalls.size)
        }

    @Test
    fun `Sign failure surfaces with Sign prefix and skips broadcast`() =
        runDesktopComposeUiTest {
            val fake = FakeWalletPort().apply {
                summaryAmountSat = 500u
                failSignPsbt = WalletException.Sign(detail = "key not derivable")
            }
            WalletStore.set(fake)

            var sentTxid: String? = null
            setContent {
                BhodlTheme {
                    ReviewSendScreen(
                        unsignedPsbtBase64 = "unsigned",
                        toAddress = TARGET_ADDRESS,
                        amountSat = 500L,
                        onSent = { sentTxid = it },
                        onBack = {},
                    )
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(PsbtReviewTags.CARD))
            onNodeWithTag(ReviewSendTags.CONFIRM).performScrollTo().performClick()

            waitUntilAtLeastOneExists(hasText("Sign:", substring = true), timeoutMillis = 2_000)
            assertNull(sentTxid, "broadcast must not have fired")
            assertEquals(0, fake.broadcastCalls.size)
        }

    @Test
    fun `Broadcast failure surfaces with Broadcast prefix`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort().apply {
            summaryAmountSat = 500u
            failBroadcast = WalletException.Broadcast(detail = "esplora 502")
        }
        WalletStore.set(fake)

        var sentTxid: String? = null
        setContent {
            BhodlTheme {
                ReviewSendScreen(
                    unsignedPsbtBase64 = "unsigned",
                    toAddress = TARGET_ADDRESS,
                    amountSat = 500L,
                    onSent = { sentTxid = it },
                    onBack = {},
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(PsbtReviewTags.CARD))
        onNodeWithTag(ReviewSendTags.CONFIRM).performScrollTo().performClick()

        waitUntilAtLeastOneExists(hasText("Broadcast:", substring = true), timeoutMillis = 2_000)
        assertNull(sentTxid)
        assertEquals(1, fake.signCalls.size, "sign DID fire before broadcast")
    }

    @Test
    fun `review shows a collapsible input to output diagram with a text alternative`() =
        runDesktopComposeUiTest {
            val fake = FakeWalletPort(psbtFee = 200u)
            WalletStore.set(fake)
            setContent {
                BhodlTheme {
                    ReviewSendScreen(
                        unsignedPsbtBase64 = "unsigned-psbt",
                        toAddress = TARGET_ADDRESS,
                        amountSat = 1_000L,
                        onSent = {},
                        onBack = {},
                    )
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(TransactionDiagramTags.DIAGRAM))
            // Screen readers get the whole flow as one sentence.
            onNodeWithTag(TransactionDiagramTags.DIAGRAM).assertContentDescriptionContains(
                "inputs (1) totalling 51,200 sat flow to outputs (2)",
                substring = true,
            )
            onNodeWithTag(TransactionDiagramTags.DIAGRAM).assertContentDescriptionContains(
                "1,000 sat to $TARGET_ADDRESS (recipient)",
                substring = true,
            )
            onNodeWithTag(TransactionDiagramTags.DIAGRAM).assertContentDescriptionContains(
                "50,000 sat to tb1pfakechange (change · yours)",
                substring = true,
            )
            onNodeWithTag(TransactionDiagramTags.DIAGRAM).assertContentDescriptionContains(
                "Network fee 200 sat",
                substring = true,
            )
            // Progressive disclosure: the diagram collapses.
            onNodeWithTag(TransactionDiagramTags.TOGGLE).performClick()
            waitUntil(timeoutMillis = 2_000) {
                onAllNodesWithTag(TransactionDiagramTags.DIAGRAM).fetchSemanticsNodes().isEmpty()
            }
        }

    private companion object {
        const val TARGET_ADDRESS: String = FakeWalletPort.DEFAULT_SUMMARY_RECIPIENT
    }
}
