package io.bhodl.desktop.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import io.bhodl.desktop.wallet.FakeWalletPort
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.ffi.TransactionRecord
import io.bhodl.ffi.WalletException
import io.bhodl.ui.theme.BhodlTheme
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Compose UI tests for [`BumpFeeScreen`].
 *
 * Three scenarios:
 * - Confirm gates: empty fee field disables the button.
 * - Happy path: typing a fee + Confirm builds and opens the review;
 *   only the review's Confirm signs + broadcasts and fires `onSent`.
 * - Cancelling the review signs nothing; a fee above the safety
 *   limit needs two explicit confirmations.
 * - The fee rate the user typed reaches the FFI verbatim.
 */
@OptIn(ExperimentalTestApi::class)
class BumpFeeScreenTest {

    @AfterTest
    fun teardownStore() {
        WalletStore.clear()
    }

    @Test
    fun `Confirm disabled when fee rate is empty`() = runDesktopComposeUiTest {
        WalletStore.set(FakeWalletPort())
        setContent {
            BhodlTheme {
                BumpFeeScreen(
                    originalTxid = ORIGINAL_TXID,
                    onSent = {},
                    onBack = {},
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(BumpFeeTags.CONFIRM))
        onNodeWithTag(BumpFeeTags.CONFIRM).assertIsNotEnabled()
    }

    @Test
    fun `build opens a review and only Confirm there signs and broadcasts`() =
        runDesktopComposeUiTest {
            val newTxid = "feed".repeat(16)
            val fake = FakeWalletPort(
                broadcastTxidBuilder = { newTxid },
                unsignedPsbtBuilder = { "bump-psbt" },
                psbtFee = 900u,
            )
            fake.transactions += TransactionRecord(
                txid = ORIGINAL_TXID,
                netSat = -10_000L,
                feeSat = 300uL,
                confirmationHeight = null,
                timestamp = null,
            )
            WalletStore.set(fake)

            var sentTxid: String? = null
            setContent {
                BhodlTheme {
                    BumpFeeScreen(
                        originalTxid = ORIGINAL_TXID,
                        onSent = { sentTxid = it },
                        onBack = {},
                    )
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(BumpFeeTags.FEE_RATE))

            onNodeWithTag(BumpFeeTags.FEE_RATE).performTextInput("8")
            onNodeWithTag(BumpFeeTags.CONFIRM).performClick()

            // Step 2: review dialog driven by decodePsbt — nothing signed yet.
            waitUntilAtLeastOneExists(hasTestTag(PsbtReviewTags.DIALOG))
            assertEquals(listOf("bump-psbt"), fake.decodeCalls)
            assertTrue(fake.signCalls.isEmpty(), "must not sign before review confirm")
            assertTrue(fake.broadcastCalls.isEmpty())
            waitUntilAtLeastOneExists(hasText("+600 sat"))
            waitUntilAtLeastOneExists(hasText("300 sat"))

            onNodeWithTag(PsbtReviewTags.DIALOG_CONFIRM).performClick()
            waitUntil(timeoutMillis = 2_000) { sentTxid != null }
            assertEquals(newTxid, sentTxid)

            assertEquals(1, fake.buildBumpCalls.size)
            assertEquals(ORIGINAL_TXID, fake.buildBumpCalls.single().first)
            assertEquals(8UL, fake.buildBumpCalls.single().second)
            assertEquals(listOf(false), fake.allowHighFeeCalls)
            assertEquals(listOf("bump-psbt"), fake.signCalls)
            assertEquals(1, fake.broadcastCalls.size)
        }

    @Test
    fun `cancelling the review signs nothing`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort()
        WalletStore.set(fake)
        var sentTxid: String? = null
        setContent {
            BhodlTheme {
                BumpFeeScreen(originalTxid = ORIGINAL_TXID, onSent = { sentTxid = it }, onBack = {})
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(BumpFeeTags.FEE_RATE))
        onNodeWithTag(BumpFeeTags.FEE_RATE).performTextInput("8")
        onNodeWithTag(BumpFeeTags.CONFIRM).performClick()
        waitUntilAtLeastOneExists(hasTestTag(PsbtReviewTags.DIALOG))
        onNodeWithTag(PsbtReviewTags.DIALOG_CANCEL).performClick()
        waitForIdle()
        assertEquals(0, onAllNodesWithTag(PsbtReviewTags.DIALOG).fetchSemanticsNodes().size)
        assertTrue(fake.signCalls.isEmpty())
        assertTrue(fake.broadcastCalls.isEmpty())
        assertNull(sentTxid)
    }

    @Test
    fun `fee above the safety limit asks twice before signing`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort(psbtFee = 70_000u).apply {
            failBuildUnlessHighFeeAllowed =
                WalletException.FeeTooHigh(feeSat = 70_000uL, limitSat = 50_000uL)
        }
        WalletStore.set(fake)
        var sentTxid: String? = null
        setContent {
            BhodlTheme {
                BumpFeeScreen(originalTxid = ORIGINAL_TXID, onSent = { sentTxid = it }, onBack = {})
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(BumpFeeTags.FEE_RATE))
        onNodeWithTag(BumpFeeTags.FEE_RATE).performTextInput("900")
        onNodeWithTag(BumpFeeTags.CONFIRM).performClick()

        // 1st confirmation: the build was refused.
        waitUntilAtLeastOneExists(hasTestTag(PsbtReviewTags.HIGH_FEE_DIALOG))
        onNodeWithTag(PsbtReviewTags.HIGH_FEE_DIALOG_CONFIRM).performClick()
        // 2nd confirmation: the review requires an explicit acknowledgement.
        waitUntilAtLeastOneExists(hasTestTag(PsbtReviewTags.DIALOG))
        assertEquals(listOf(false, true), fake.allowHighFeeCalls)
        onNodeWithTag(PsbtReviewTags.DIALOG_CONFIRM).assertIsNotEnabled()
        onNodeWithTag(PsbtReviewTags.HIGH_FEE_ACK).performClick()
        onNodeWithTag(PsbtReviewTags.DIALOG_CONFIRM).assertIsEnabled().performClick()
        waitUntil(timeoutMillis = 2_000) { sentTxid != null }
        assertEquals(1, fake.signCalls.size)
    }

    @Test
    fun `typing a high fee rate surfaces the advisory inline`() = runDesktopComposeUiTest {
        WalletStore.set(FakeWalletPort())
        setContent {
            BhodlTheme {
                BumpFeeScreen(
                    originalTxid = ORIGINAL_TXID,
                    onSent = {},
                    onBack = {},
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(BumpFeeTags.FEE_RATE))
        // Below 100 sat/vB → no advisory rendered.
        onNodeWithTag(BumpFeeTags.FEE_RATE).performTextInput("50")
        waitForIdle()
        assertEquals(0, onAllNodesWithTag(BumpFeeTags.FEE_ADVISORY).fetchSemanticsNodes().size)

        // Above 100 → advisory appears.
        onNodeWithTag(BumpFeeTags.FEE_RATE).performTextInput("0") // now 500
        waitUntilAtLeastOneExists(hasTestTag(BumpFeeTags.FEE_ADVISORY))
        onNodeWithTag(BumpFeeTags.FEE_ADVISORY).assertIsDisplayed()
    }

    private companion object {
        // 32 bytes / 64 hex chars: deadbeef × 8.
        const val ORIGINAL_TXID: String =
            "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef"
    }
}
