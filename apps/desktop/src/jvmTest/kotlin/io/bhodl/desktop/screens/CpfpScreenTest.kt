package io.bhodl.desktop.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.bhodl.desktop.wallet.FakeWalletPort
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.ffi.CpfpPlan
import io.bhodl.ffi.WalletException
import io.bhodl.ui.theme.BhodlTheme
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compose UI tests for [`CpfpScreen`] — covers both kinds (incoming
 * speed-up vs change rescue) since they share the build / sign /
 * broadcast pipeline and only differ in copy.
 */
@OptIn(ExperimentalTestApi::class)
class CpfpScreenTest {

    @AfterTest
    fun teardownStore() {
        WalletStore.clear()
    }

    @Test
    fun `Confirm disabled when fee rate is empty`() = runDesktopComposeUiTest {
        WalletStore.set(FakeWalletPort())
        setContent {
            BhodlTheme {
                CpfpScreen(
                    parentTxid = PARENT_TXID,
                    kind = CpfpKind.IncomingSpeedUp,
                    onSent = {},
                    onBack = {},
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(CpfpTags.CONFIRM))
        onNodeWithTag(CpfpTags.CONFIRM).assertIsNotEnabled()
    }

    @Test
    fun `incoming speed-up builds, shows the package review, then signs on confirm`() =
        runDesktopComposeUiTest {
            val newTxid = "abcd".repeat(16)
            val fake = FakeWalletPort(
                broadcastTxidBuilder = { newTxid },
                unsignedPsbtBuilder = { "child-psbt" },
            )
            fake.cpfpPlanBuilder = { parent, rate, psbt ->
                CpfpPlan(
                    psbt = psbt,
                    parentTxid = parent,
                    parentFeeSat = 200uL,
                    parentVsize = 200uL,
                    ancestorCount = 0u,
                    ancestorsFeeSat = 0u,
                    ancestorsVsize = 0u,
                    childFeeSat = 3_640uL,
                    childVsize = 120uL,
                    targetFeeRateSatVb = rate,
                )
            }
            WalletStore.set(fake)

            var sentTxid: String? = null
            setContent {
                BhodlTheme {
                    CpfpScreen(
                        parentTxid = PARENT_TXID,
                        kind = CpfpKind.IncomingSpeedUp,
                        onSent = { sentTxid = it },
                        onBack = {},
                    )
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(CpfpTags.FEE_RATE))

            onNodeWithTag(CpfpTags.FEE_RATE).performTextInput("12")
            onNodeWithTag(CpfpTags.CONFIRM).performClick()

            waitUntilAtLeastOneExists(hasTestTag(PsbtReviewTags.DIALOG))
            assertTrue(fake.signCalls.isEmpty(), "must not sign before review confirm")
            assertEquals(listOf("child-psbt"), fake.decodeCalls)
            // Package math: (200 + 3640) / (200 + 120) = 12.0 sat/vB.
            waitUntilAtLeastOneExists(hasText("12.0 sat/vB (target 12 sat/vB)"))
            waitUntilAtLeastOneExists(hasText("3,640 sat · 120 vB"))

            onNodeWithTag(PsbtReviewTags.DIALOG_CONFIRM).performClick()
            waitUntil(timeoutMillis = 2_000) { sentTxid != null }
            assertEquals(newTxid, sentTxid)

            assertEquals(1, fake.buildCpfpCalls.size)
            val (parent, rate) = fake.buildCpfpCalls.single()
            assertEquals(PARENT_TXID, parent)
            assertEquals(12UL, rate)
            assertEquals(listOf("child-psbt"), fake.signCalls)
            assertEquals(1, fake.broadcastCalls.size)
        }

    @Test
    fun `build error such as a confirmed parent is shown and nothing is signed`() =
        runDesktopComposeUiTest {
            val fake = FakeWalletPort().apply {
                failBuildSendTx = WalletException.TxBuild(detail = "parent is already confirmed")
            }
            WalletStore.set(fake)
            setContent {
                BhodlTheme {
                    CpfpScreen(
                        parentTxid = PARENT_TXID,
                        kind = CpfpKind.ChangeRescue,
                        onSent = {},
                        onBack = {},
                    )
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(CpfpTags.FEE_RATE))
            onNodeWithTag(CpfpTags.FEE_RATE).performTextInput("12")
            onNodeWithTag(CpfpTags.CONFIRM).performClick()
            waitUntilAtLeastOneExists(hasTestTag(CpfpTags.ERROR))
            waitUntilAtLeastOneExists(hasText("already confirmed", substring = true))
            assertTrue(fake.signCalls.isEmpty())
        }

    @Test
    fun `cancelling the CPFP review signs nothing`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort()
        WalletStore.set(fake)
        setContent {
            BhodlTheme {
                CpfpScreen(
                    parentTxid = PARENT_TXID,
                    kind = CpfpKind.IncomingSpeedUp,
                    onSent = {},
                    onBack = {},
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(CpfpTags.FEE_RATE))
        onNodeWithTag(CpfpTags.FEE_RATE).performTextInput("12")
        onNodeWithTag(CpfpTags.CONFIRM).performClick()
        waitUntilAtLeastOneExists(hasTestTag(PsbtReviewTags.DIALOG))
        onNodeWithTag(PsbtReviewTags.DIALOG_CANCEL).performClick()
        waitForIdle()
        assertTrue(fake.signCalls.isEmpty())
        assertTrue(fake.broadcastCalls.isEmpty())
    }

    @Test
    fun `kind drives header copy — IncomingSpeedUp shows Speed up incoming`() =
        runDesktopComposeUiTest {
            WalletStore.set(FakeWalletPort())
            setContent {
                BhodlTheme {
                    CpfpScreen(
                        parentTxid = PARENT_TXID,
                        kind = CpfpKind.IncomingSpeedUp,
                        onSent = {},
                        onBack = {},
                    )
                }
            }
            waitUntilAtLeastOneExists(hasText("Speed up incoming"))
            onNodeWithText("Speed up incoming").assertIsDisplayed()
        }

    @Test
    fun `kind drives header copy — ChangeRescue shows Rescue stuck change`() =
        runDesktopComposeUiTest {
            WalletStore.set(FakeWalletPort())
            setContent {
                BhodlTheme {
                    CpfpScreen(
                        parentTxid = PARENT_TXID,
                        kind = CpfpKind.ChangeRescue,
                        onSent = {},
                        onBack = {},
                    )
                }
            }
            waitUntilAtLeastOneExists(hasText("Rescue stuck change"))
            onNodeWithText("Rescue stuck change").assertIsDisplayed()
        }

    @Test
    fun `Back fires onBack`() = runDesktopComposeUiTest {
        WalletStore.set(FakeWalletPort())
        var backCalled = 0
        setContent {
            BhodlTheme {
                CpfpScreen(
                    parentTxid = PARENT_TXID,
                    kind = CpfpKind.IncomingSpeedUp,
                    onSent = {},
                    onBack = { backCalled++ },
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(CpfpTags.BACK))
        onNodeWithTag(CpfpTags.BACK).performClick()
        waitUntil(timeoutMillis = 1_000) { backCalled == 1 }
        assertEquals(1, backCalled)
    }

    private companion object {
        const val PARENT_TXID: String =
            "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"
    }
}
