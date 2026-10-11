package io.bhodl.desktop.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.wallet.FakeWalletPort
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.ffi.FeeEstimates
import io.bhodl.ffi.TransactionRecord
import io.bhodl.ffi.UtxoRecord
import io.bhodl.ffi.WalletException
import io.bhodl.ui.theme.BhodlTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Test-book §6–§9, §10, §11, §13, §13c, §16c — the send / fee-bump
 * paths that are pure UI logic in front of the FFI, with a
 * [FakeWalletPort] (no network, no BDK):
 *  - form validation that must stop BEFORE anything is built;
 *  - fee tier selection reaching the builder;
 *  - BIP-21 paste edge cases (scheme case, sub-satoshi, clearnet pj);
 *  - context-aware copy for Bitcoin Core `-25` broadcast races.
 */
@OptIn(ExperimentalTestApi::class)
class SendFlowValidationTest {

    @BeforeTest
    fun setUp() {
        SettingsStore.reset()
    }

    @AfterTest
    fun tearDown() {
        WalletStore.clear()
        SettingsStore.reset()
    }

    private fun tieredFake(): FakeWalletPort = FakeWalletPort(
        feeEstimates = FeeEstimates(
            economySatPerVb = 2u,
            normalSatPerVb = 5u,
            prioritySatPerVb = 12u,
        ),
    )

    private fun FakeWalletPort.noBuilds(): Boolean =
        buildSendTxCalls.isEmpty() && buildBatchCalls.isEmpty() &&
            buildWithUtxosCalls.isEmpty() && buildBatchWithUtxosCalls.isEmpty() &&
            buildDrainCalls.isEmpty()

    // ---- §7 validation -------------------------------------------------------

    @Test
    fun `empty form asks for a recipient and builds nothing`() = runDesktopComposeUiTest {
        val fake = tieredFake()
        WalletStore.set(fake)
        setContent { BhodlTheme { SendScreen(onReady = { _, _, _, _ -> }, onBack = {}) } }
        waitUntilAtLeastOneExists(hasTestTag(SendTags.REVIEW))
        onNodeWithTag(SendTags.REVIEW).performScrollTo().performClick()
        waitUntilAtLeastOneExists(hasTestTag(SendTags.ERROR))
        onNodeWithTag(SendTags.ERROR).assertTextEquals("Enter a recipient address")
        assertTrue(fake.noBuilds())
    }

    @Test
    fun `zero amount is rejected locally`() = runDesktopComposeUiTest {
        val fake = tieredFake()
        WalletStore.set(fake)
        setContent { BhodlTheme { SendScreen(onReady = { _, _, _, _ -> }, onBack = {}) } }
        waitUntilAtLeastOneExists(hasTestTag(SendTags.REVIEW))
        onNodeWithTag(SendTags.ADDRESS).performTextInput(TARGET_ADDRESS)
        onNodeWithTag(SendTags.AMOUNT).performTextInput("0")
        onNodeWithTag(SendTags.REVIEW).performScrollTo().performClick()
        waitUntilAtLeastOneExists(hasTestTag(SendTags.ERROR))
        onNodeWithTag(SendTags.ERROR).assertTextEquals("Enter a positive amount in satoshis")
        assertTrue(fake.noBuilds())
    }

    @Test
    fun `insufficient funds from the builder is shown and nothing proceeds`() =
        runDesktopComposeUiTest {
            val fake = tieredFake().apply {
                failBuildSendTx = WalletException.TxBuild(
                    detail = "Insufficient funds: 10000 sat available of 999999999 sat needed",
                )
            }
            WalletStore.set(fake)
            var ready = 0
            setContent { BhodlTheme { SendScreen(onReady = { _, _, _, _ -> ready++ }, onBack = {}) } }
            waitUntilAtLeastOneExists(hasTestTag(SendTags.REVIEW))
            onNodeWithTag(SendTags.ADDRESS).performTextInput(TARGET_ADDRESS)
            onNodeWithTag(SendTags.AMOUNT).performTextInput("999999999")
            onNodeWithTag(SendTags.REVIEW).performScrollTo().performClick()
            waitUntilAtLeastOneExists(hasTestTag(SendTags.ERROR), timeoutMillis = 2_000)
            assertEquals(0, ready)
        }

    // ---- §9 fee tiers --------------------------------------------------------

    @Test
    fun `Normal is preselected and the tapped tier's rate reaches the builder`() =
        runDesktopComposeUiTest {
            val fake = tieredFake()
            WalletStore.set(fake)
            var ready = 0
            setContent { BhodlTheme { SendScreen(onReady = { _, _, _, _ -> ready++ }, onBack = {}) } }
            waitUntilAtLeastOneExists(hasText("12 sat/vB"))
            onNodeWithTag(SendTags.feeTier("normal")).assertIsSelected()
            onNodeWithTag(SendTags.feeTier("economy")).assertIsNotSelected()

            onNodeWithTag(SendTags.feeTier("priority")).performScrollTo().performClick()
            onNodeWithTag(SendTags.feeTier("priority")).assertIsSelected()
            onNodeWithTag(SendTags.feeTier("normal")).assertIsNotSelected()

            onNodeWithTag(SendTags.ADDRESS).performTextInput(TARGET_ADDRESS)
            onNodeWithTag(SendTags.AMOUNT).performTextInput("1000")
            onNodeWithTag(SendTags.REVIEW).performScrollTo().performClick()
            waitUntil(timeoutMillis = 2_000) { ready == 1 }
            assertEquals(12UL, fake.buildSendTxCalls.single().third)

            onNodeWithTag(SendTags.feeTier("economy")).performScrollTo().performClick()
            onNodeWithTag(SendTags.REVIEW).performScrollTo().performClick()
            waitUntil(timeoutMillis = 2_000) { ready == 2 }
            assertEquals(2UL, fake.buildSendTxCalls.last().third)
        }

    @Test
    fun `fee estimate failure shows dashes, no rate, and blocks building without a manual rate`() =
        runDesktopComposeUiTest {
            val fake = FakeWalletPort().apply {
                failFetchFees = WalletException.EsploraSync(detail = "connection refused")
            }
            WalletStore.set(fake)
            setContent { BhodlTheme { SendScreen(onReady = { _, _, _, _ -> }, onBack = {}) } }
            waitUntilAtLeastOneExists(hasTestTag(SendTags.FEE_UNAVAILABLE))
            assertTrue(onAllNodesWithTag(SendTags.MANUAL_FEE).fetchSemanticsNodes().isNotEmpty())
            onNodeWithTag(SendTags.feeTier("normal")).assertTextContains("—", substring = true)
            onNodeWithTag(SendTags.ADDRESS).performTextInput(TARGET_ADDRESS)
            onNodeWithTag(SendTags.AMOUNT).performTextInput("1000")
            onNodeWithTag(SendTags.REVIEW).performScrollTo().performClick()
            waitUntilAtLeastOneExists(hasTestTag(SendTags.ERROR))
            assertTrue(fake.noBuilds(), "never falls back to a silent 1 sat/vB")
        }

    @Test
    fun `a high manual fee rate shows the advisory`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort().apply {
            failFetchFees = WalletException.FeeEstimateUnavailable(detail = "feed down")
        }
        WalletStore.set(fake)
        setContent { BhodlTheme { SendScreen(onReady = { _, _, _, _ -> }, onBack = {}) } }
        waitUntilAtLeastOneExists(hasTestTag(SendTags.MANUAL_FEE))
        assertTrue(onAllNodesWithTag(SendTags.FEE_ADVISORY).fetchSemanticsNodes().isEmpty())
        onNodeWithTag(SendTags.MANUAL_FEE).performScrollTo().performTextInput("600")
        waitUntilAtLeastOneExists(hasTestTag(SendTags.FEE_ADVISORY))
        onNodeWithTag(SendTags.FEE_ADVISORY).assertTextContains("600", substring = true)
    }

    // ---- §10 coin control / §11 batch -----------------------------------------

    @Test
    fun `coin control with nothing selected refuses, one selected pins that input`() =
        runDesktopComposeUiTest {
            val utxo = UtxoRecord(
                outpoint = "${"ab".repeat(32)}:0",
                valueSat = 50_000u,
                address = "tb1putxo",
                isChange = false,
                confirmed = true,
                frozen = false,
            )
            val fake = tieredFake().apply { utxos += utxo }
            WalletStore.set(fake)
            var ready = 0
            setContent { BhodlTheme { SendScreen(onReady = { _, _, _, _ -> ready++ }, onBack = {}) } }
            waitUntilAtLeastOneExists(hasTestTag(SendTags.REVIEW))
            onNodeWithTag(SendTags.ADDRESS).performTextInput(TARGET_ADDRESS)
            onNodeWithTag(SendTags.AMOUNT).performTextInput("1000")
            onNodeWithTag(SendTags.COIN_CONTROL_TOGGLE).performScrollTo().performClick()
            waitUntilAtLeastOneExists(hasTestTag(SendTags.utxoCheckbox(utxo.outpoint)), timeoutMillis = 2_000)

            onNodeWithTag(SendTags.REVIEW).performScrollTo().performClick()
            waitUntilAtLeastOneExists(hasTestTag(SendTags.ERROR))
            onNodeWithTag(SendTags.ERROR)
                .assertTextEquals("Coin control is on but no UTXO is selected.")
            assertTrue(fake.noBuilds())

            onNodeWithTag(SendTags.utxoCheckbox(utxo.outpoint)).performScrollTo().performClick()
            onNodeWithTag(SendTags.REVIEW).performScrollTo().performClick()
            waitUntil(timeoutMillis = 2_000) { ready == 1 }
            assertEquals(listOf(utxo.outpoint), fake.buildWithUtxosCalls.single().outpoints)
            assertTrue(fake.buildSendTxCalls.isEmpty(), "automatic selection must not run")
        }

    @Test
    fun `an incomplete extra recipient blocks the batch build`() = runDesktopComposeUiTest {
        val fake = tieredFake()
        WalletStore.set(fake)
        setContent { BhodlTheme { SendScreen(onReady = { _, _, _, _ -> }, onBack = {}) } }
        waitUntilAtLeastOneExists(hasTestTag(SendTags.REVIEW))
        onNodeWithTag(SendTags.ADDRESS).performTextInput(TARGET_ADDRESS)
        onNodeWithTag(SendTags.AMOUNT).performTextInput("500")
        onNodeWithTag(SendTags.ADD_RECIPIENT).performScrollTo().performClick()
        waitUntilAtLeastOneExists(hasTestTag(SendTags.extraAddress(0)))
        onNodeWithTag(SendTags.extraAddress(0)).performTextInput(TARGET_ADDRESS)
        onNodeWithTag(SendTags.REVIEW).performScrollTo().performClick()
        waitUntilAtLeastOneExists(hasTestTag(SendTags.ERROR))
        onNodeWithTag(SendTags.ERROR)
            .assertTextEquals("Recipient #2 is missing an address or amount.")
        assertTrue(fake.noBuilds())
    }

    // ---- §8 / §13c BIP-21 paste ---------------------------------------------------

    @Test
    fun `uppercase scheme URI fills address and converts the BTC amount`() =
        runDesktopComposeUiTest {
            WalletStore.set(tieredFake())
            setContent { BhodlTheme { SendScreen(onReady = { _, _, _, _ -> }, onBack = {}) } }
            waitUntilAtLeastOneExists(hasTestTag(SendTags.ADDRESS))
            onNodeWithTag(SendTags.ADDRESS).performTextInput("BITCOIN:$TARGET_ADDRESS?amount=0.5")
            assertEquals(TARGET_ADDRESS, editableText(SendTags.ADDRESS))
            assertEquals("50000000", editableText(SendTags.AMOUNT))
            // No pj= → plain send: no PayJoin badge, classic Review CTA.
            assertTrue(onAllNodesWithTag(SendTags.PAYJOIN_BADGE).fetchSemanticsNodes().isEmpty())
            onNodeWithTag(SendTags.REVIEW).assertTextEquals("Review")
        }

    @Test
    fun `sub-satoshi amount invalidates the URI and prefills nothing`() =
        runDesktopComposeUiTest {
            val fake = tieredFake()
            WalletStore.set(fake)
            setContent { BhodlTheme { SendScreen(onReady = { _, _, _, _ -> }, onBack = {}) } }
            waitUntilAtLeastOneExists(hasTestTag(SendTags.ADDRESS))
            onNodeWithTag(SendTags.ADDRESS)
                .performTextInput("bitcoin:$TARGET_ADDRESS?amount=0.123456789")
            waitUntilAtLeastOneExists(hasTestTag(SendTags.ERROR))
            onNodeWithTag(SendTags.ERROR).assertTextContains("Invalid bitcoin: URI", substring = true)
            assertEquals("", editableText(SendTags.AMOUNT), "no amount prefilled")
            assertTrue(fake.noBuilds())
        }

    @Test
    fun `clearnet http pj endpoint is ignored and the payment stays a normal send`() =
        runDesktopComposeUiTest {
            WalletStore.set(tieredFake())
            setContent { BhodlTheme { SendScreen(onReady = { _, _, _, _ -> }, onBack = {}) } }
            waitUntilAtLeastOneExists(hasTestTag(SendTags.ADDRESS))
            onNodeWithTag(SendTags.ADDRESS).performTextInput(
                "bitcoin:$TARGET_ADDRESS?amount=0.0001&pj=http://pj.example.com/pj",
            )
            waitForIdle()
            assertTrue(onAllNodesWithTag(SendTags.PAYJOIN_BADGE).fetchSemanticsNodes().isEmpty())
            onNodeWithTag(SendTags.REVIEW).assertTextContains("Review")
            assertEquals("10000", editableText(SendTags.AMOUNT))
        }

    @Test
    fun `onion pj endpoint warns while Tor is off and not once Tor is on`() =
        runDesktopComposeUiTest {
            WalletStore.set(tieredFake())
            setContent { BhodlTheme { SendScreen(onReady = { _, _, _, _ -> }, onBack = {}) } }
            waitUntilAtLeastOneExists(hasTestTag(SendTags.ADDRESS))
            onNodeWithTag(SendTags.ADDRESS).performTextInput(
                "bitcoin:$TARGET_ADDRESS?amount=0.0001&pj=http://pjexampleonionaddr.onion/pj",
            )
            waitUntilAtLeastOneExists(hasTestTag(SendTags.PAYJOIN_BADGE))
            waitUntilAtLeastOneExists(hasTestTag(SendTags.PAYJOIN_TOR_REQUIRED))
            SettingsStore.setTorEnabled(true)
            waitForIdle()
            assertTrue(onAllNodesWithTag(SendTags.PAYJOIN_TOR_REQUIRED).fetchSemanticsNodes().isEmpty())
            onNodeWithTag(SendTags.PAYJOIN_BADGE).assertExists()
        }

    // ---- §16c -25 broadcast races ------------------------------------------------

    @Test
    fun `send broadcast race shows the already-spent copy and stays on Review`() =
        runDesktopComposeUiTest {
            val fake = FakeWalletPort().apply {
                failBroadcast = WalletException.Broadcast(
                    detail = "sendrawtransaction RPC error: {\"code\":-25,\"message\":\"bad-txns-inputs-missingorspent\"}",
                )
            }
            WalletStore.set(fake)
            var sent: String? = null
            setContent {
                BhodlTheme {
                    ReviewSendScreen(
                        unsignedPsbtBase64 = "unsigned",
                        toAddress = FakeWalletPort.DEFAULT_SUMMARY_RECIPIENT,
                        amountSat = 1_000L,
                        onSent = { sent = it },
                        onBack = {},
                    )
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(PsbtReviewTags.CARD))
            onNodeWithTag(ReviewSendTags.CONFIRM).performScrollTo().performClick()
            waitUntilAtLeastOneExists(
                hasText("One of the selected coins was already spent", substring = true),
                timeoutMillis = 2_000,
            )
            assertNull(sent)
            onNodeWithTag(ReviewSendTags.CONFIRM).assertExists()
        }

    @Test
    fun `fee bump rejected by the builder shows the error and signs nothing`() =
        runDesktopComposeUiTest {
            val fake = FakeWalletPort().apply {
                transactions += pendingOutgoing()
                failBuildSendTx = WalletException.TxBuild(
                    detail = "fee rate is too low to be a valid replacement",
                )
            }
            WalletStore.set(fake)
            setContent {
                BhodlTheme { BumpFeeScreen(originalTxid = PARENT_TXID, onSent = {}, onBack = {}) }
            }
            waitUntilAtLeastOneExists(hasTestTag(BumpFeeTags.FEE_RATE))
            onNodeWithTag(BumpFeeTags.FEE_RATE).performTextInput("1")
            onNodeWithTag(BumpFeeTags.CONFIRM).performClick()
            waitUntilAtLeastOneExists(hasText("too low", substring = true), timeoutMillis = 2_000)
            assertTrue(fake.signCalls.isEmpty())
            assertTrue(fake.broadcastCalls.isEmpty())
            assertTrue(onAllNodesWithTag(PsbtReviewTags.DIALOG).fetchSemanticsNodes().isEmpty())
        }

    @Test
    fun `fee bump broadcast race shows the RBF-specific copy`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort().apply {
            transactions += pendingOutgoing()
            failBroadcast = WalletException.Broadcast(detail = "bad-txns-inputs-missingorspent")
        }
        WalletStore.set(fake)
        var sent: String? = null
        setContent {
            BhodlTheme { BumpFeeScreen(originalTxid = PARENT_TXID, onSent = { sent = it }, onBack = {}) }
        }
        waitUntilAtLeastOneExists(hasTestTag(BumpFeeTags.FEE_RATE))
        onNodeWithTag(BumpFeeTags.FEE_RATE).performTextInput("8")
        onNodeWithTag(BumpFeeTags.CONFIRM).performClick()
        waitUntilAtLeastOneExists(hasTestTag(PsbtReviewTags.DIALOG))
        onNodeWithTag(PsbtReviewTags.DIALOG_CONFIRM).performClick()
        waitUntilAtLeastOneExists(
            hasText("Fee bump is no longer possible", substring = true),
            timeoutMillis = 2_000,
        )
        assertNull(sent)
    }

    @Test
    fun `CPFP broadcast race shows the CPFP-specific copy`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort().apply {
            failBroadcast = WalletException.Broadcast(detail = "bad-txns-inputs-missingorspent")
        }
        WalletStore.set(fake)
        var sent: String? = null
        setContent {
            BhodlTheme {
                CpfpScreen(
                    parentTxid = PARENT_TXID,
                    kind = CpfpKind.IncomingSpeedUp,
                    onSent = { sent = it },
                    onBack = {},
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(CpfpTags.FEE_RATE))
        onNodeWithTag(CpfpTags.FEE_RATE).performTextInput("15")
        onNodeWithTag(CpfpTags.CONFIRM).performClick()
        waitUntilAtLeastOneExists(hasTestTag(PsbtReviewTags.DIALOG))
        onNodeWithTag(PsbtReviewTags.DIALOG_CONFIRM).performClick()
        waitUntilAtLeastOneExists(
            hasText("Speed up is no longer needed", substring = true),
            timeoutMillis = 2_000,
        )
        assertNull(sent)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionsProvider.editableText(tag: String): String =
        onNodeWithTag(tag).fetchSemanticsNode().config
            .getOrElse(androidx.compose.ui.semantics.SemanticsProperties.EditableText) {
                androidx.compose.ui.text.AnnotatedString("")
            }.text

    private fun pendingOutgoing() = TransactionRecord(
        txid = PARENT_TXID,
        netSat = -10_000L,
        feeSat = 300uL,
        confirmationHeight = null,
        timestamp = null,
    )

    private companion object {
        const val TARGET_ADDRESS: String =
            "tb1pqqqqp399et2xygdj5xreqhjjvcmzhxw4aywxecjdzew6hylgvsesf3hn0c"
        const val PARENT_TXID: String =
            "1111111111111111111111111111111111111111111111111111111111111111"
    }
}
