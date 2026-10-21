package io.bhodl.desktop.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.bhodl.desktop.ui.ContactPickerTags
import io.bhodl.desktop.wallet.FakeWalletPort
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.ffi.ContactRecord
import io.bhodl.ffi.FeeEstimates
import io.bhodl.ffi.UtxoRecord
import io.bhodl.ffi.WalletException
import io.bhodl.ui.theme.BhodlTheme
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compose UI tests for [`SendScreen`] driven by a [`FakeWalletPort`].
 *
 * The screen has two async fan-outs on first composition:
 * - `fetchFeeEstimates` for the three-tier chip selector
 * - `listUtxos` (only when the user toggles coin control)
 *
 * Both pass through `Dispatchers.IO` even with the fake, so each test
 * uses `waitUntilAtLeastOneExists` to settle the screen before
 * asserting.
 */
@OptIn(ExperimentalTestApi::class)
class SendScreenTest {

    @AfterTest
    fun teardownStore() {
        WalletStore.clear()
    }

    @Test
    fun `Review with valid input calls buildSendTx and onReady fires with the PSBT`() =
        runDesktopComposeUiTest {
            val fake = FakeWalletPort(
                feeEstimates = FeeEstimates(
                    economySatPerVb = 1u,
                    normalSatPerVb = 5u,
                    prioritySatPerVb = 12u,
                ),
                unsignedPsbtBuilder = { "psbt-from-fake" },
            )
            WalletStore.set(fake)

            var readyPsbt: String? = null
            var readyAddress: String? = null
            var readyAmount: Long? = null
            setContent {
                BhodlTheme {
                    SendScreen(
                        onReady = { psbt, address, amount, _ ->
                            readyPsbt = psbt
                            readyAddress = address
                            readyAmount = amount
                        },
                        onBack = {},
                    )
                }
            }

            waitUntilAtLeastOneExists(hasTestTag(SendTags.REVIEW))

            onNodeWithTag(SendTags.ADDRESS).performTextInput(TARGET_ADDRESS)
            onNodeWithTag(SendTags.AMOUNT).performTextInput("1000")
            onNodeWithTag(SendTags.REVIEW).performScrollTo().performClick()

            // The build is suspended on Dispatchers.IO; wait for the
            // call to be recorded.
            waitUntil(timeoutMillis = 2_000) { fake.buildSendTxCalls.isNotEmpty() }

            assertEquals(1, fake.buildSendTxCalls.size)
            val (toAddress, amountSat, feeRate) = fake.buildSendTxCalls.single()
            assertEquals(TARGET_ADDRESS, toAddress)
            assertEquals(1000UL, amountSat)
            assertEquals(5UL, feeRate, "default tier is Normal -> 5 sat/vB")

            // onReady is invoked from the UI coroutine after the build
            // returns; wait for it.
            waitUntil(timeoutMillis = 2_000) { readyPsbt != null }
            assertEquals("psbt-from-fake", readyPsbt)
            assertEquals(TARGET_ADDRESS, readyAddress)
            assertEquals(1000L, readyAmount)
        }

    @Test
    fun `Review without amount surfaces validation error and never calls FFI`() =
        runDesktopComposeUiTest {
            val fake = FakeWalletPort()
            WalletStore.set(fake)

            setContent {
                BhodlTheme {
                    SendScreen(onReady = { _, _, _, _ -> }, onBack = {})
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(SendTags.REVIEW))

            onNodeWithTag(SendTags.ADDRESS).performTextInput(TARGET_ADDRESS)
            onNodeWithTag(SendTags.REVIEW).performScrollTo().performClick()

            onNodeWithText("Enter a positive amount in satoshis").assertIsDisplayed()
            assertTrue(fake.buildSendTxCalls.isEmpty(), "FFI must not be reached")
        }

    @Test
    fun `Review surfaces FFI error when buildSendTx fails`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort(
            unsignedPsbtBuilder = { "unused" },
        ).apply {
            failBuildSendTx = WalletException.InvalidAddress(
                detail = "address ... is bound to network bitcoin",
            )
        }
        WalletStore.set(fake)

        setContent {
            BhodlTheme {
                SendScreen(onReady = { _, _, _, _ -> }, onBack = {})
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(SendTags.REVIEW))

        onNodeWithTag(SendTags.ADDRESS).performTextInput(TARGET_ADDRESS)
        onNodeWithTag(SendTags.AMOUNT).performTextInput("1000")
        onNodeWithTag(SendTags.REVIEW).performScrollTo().performClick()

        // Build attempt was made and threw; failure text appears.
        waitUntilAtLeastOneExists(
            hasText("address ... is bound to network bitcoin", substring = true),
            timeoutMillis = 2_000,
        )
    }

    @Test
    fun `Review with multiple recipients and coin control calls buildSendTxBatchWithUtxos`() =
        runDesktopComposeUiTest {
            // Two UTXOs in the wallet so the user has something to
            // pin. Outpoint #1 is the only one we'll select.
            val utxoOne = UtxoRecord(
                outpoint = "$UTXO_TXID:0",
                valueSat = 50_000u,
                address = "tb1putxoone0000000000000000000000000000000000000000000000000000000",
                isChange = false,
                confirmed = true,
                frozen = false,
            )
            val utxoTwo = UtxoRecord(
                outpoint = "$UTXO_TXID:1",
                valueSat = 50_000u,
                address = "tb1putxotwo0000000000000000000000000000000000000000000000000000000",
                isChange = true,
                confirmed = true,
                frozen = false,
            )
            val fake = FakeWalletPort(
                feeEstimates = FeeEstimates(
                    economySatPerVb = 1u,
                    normalSatPerVb = 5u,
                    prioritySatPerVb = 12u,
                ),
                unsignedPsbtBuilder = { "psbt-batch-with-utxos" },
            )
            fake.utxos.add(utxoOne)
            fake.utxos.add(utxoTwo)
            WalletStore.set(fake)

            var readyPsbt: String? = null
            setContent {
                BhodlTheme {
                    SendScreen(
                        onReady = { psbt, _, _, _ -> readyPsbt = psbt },
                        onBack = {},
                    )
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(SendTags.REVIEW))

            // First recipient: in the always-visible address+amount fields.
            onNodeWithTag(SendTags.ADDRESS).performTextInput(TARGET_ADDRESS)
            waitForIdle()
            onNodeWithTag(SendTags.AMOUNT).performTextInput("1000")
            waitForIdle()

            // Second recipient: spawn a row, fill it.
            onNodeWithTag(SendTags.ADD_RECIPIENT).performClick()
            waitForIdle()
            waitUntilAtLeastOneExists(hasTestTag(SendTags.extraAddress(0)))
            onNodeWithTag(SendTags.extraAddress(0)).performTextInput(SECOND_ADDRESS)
            waitForIdle()
            onNodeWithTag(SendTags.extraAmount(0)).performTextInput("2000")
            waitForIdle()

            // Toggle coin control + select utxoOne only. The toggle
            // triggers an async listUtxos on Dispatchers.IO, so wait
            // until the row materialises. The screen has grown enough
            // (Send MAX, fee advisory, etc.) that the toggle sits
            // below the fold — scroll to it before clicking.
            onNodeWithTag(SendTags.COIN_CONTROL_TOGGLE).performScrollTo().performClick()
            waitForIdle()
            waitUntilAtLeastOneExists(
                hasTestTag(SendTags.utxoCheckbox(utxoOne.outpoint)),
                timeoutMillis = 3_000,
            )
            onNodeWithTag(SendTags.utxoCheckbox(utxoOne.outpoint)).performScrollTo().performClick()
            waitForIdle()
            // Sanity: the screen now must show the selection summary.
            waitUntilAtLeastOneExists(hasText("Selected: 1 of 2"))

            // The screen has grown enough to push Review below the
            // viewport — performClick on an off-screen node silently
            // no-ops, so scroll to it first.
            onNodeWithTag(SendTags.REVIEW).performScrollTo().performClick()

            waitUntil(timeoutMillis = 2_000) { fake.buildBatchWithUtxosCalls.isNotEmpty() }

            // Exactly one combined-flow call landed on the FFI; the
            // simpler buildSendTx / batch / utxos paths must not have
            // been touched.
            assertEquals(1, fake.buildBatchWithUtxosCalls.size)
            assertTrue(
                fake.buildSendTxCalls.isEmpty() &&
                    fake.buildBatchCalls.isEmpty() &&
                    fake.buildWithUtxosCalls.isEmpty(),
                "only the batch-with-utxos branch should have been called",
            )

            val call = fake.buildBatchWithUtxosCalls.single()
            // Two recipients, in the order they appear on the screen.
            assertEquals(2, call.recipients.size)
            assertEquals(TARGET_ADDRESS, call.recipients[0].address)
            assertEquals(1_000UL, call.recipients[0].amountSat)
            assertEquals(SECOND_ADDRESS, call.recipients[1].address)
            assertEquals(2_000UL, call.recipients[1].amountSat)
            // Default tier is Normal -> 5 sat/vB on the configured fees.
            assertEquals(5UL, call.feeRateSatVb)
            // Only utxoOne was selected; utxoTwo's outpoint must not be there.
            assertEquals(listOf(utxoOne.outpoint), call.outpoints)

            waitUntil(timeoutMillis = 2_000) { readyPsbt != null }
            assertEquals("psbt-batch-with-utxos", readyPsbt)
        }

    @Test
    fun `frozen UTXO shows a badge and cannot be selected in coin control`() =
        runDesktopComposeUiTest {
            val free = UtxoRecord(
                outpoint = "$UTXO_TXID:0",
                valueSat = 50_000u,
                address = "tb1pfree00000000000000000000000000000000000000000000000000000000",
                isChange = false,
                confirmed = true,
                frozen = false,
            )
            val frozen = free.copy(outpoint = "$UTXO_TXID:1", frozen = true)
            val fake = FakeWalletPort()
            fake.utxos.add(free)
            fake.utxos.add(frozen)
            WalletStore.set(fake)

            setContent { BhodlTheme { SendScreen(onReady = { _, _, _, _ -> }, onBack = {}) } }
            waitUntilAtLeastOneExists(hasTestTag(SendTags.REVIEW))
            onNodeWithTag(SendTags.COIN_CONTROL_TOGGLE).performScrollTo().performClick()
            // The row is a merged-semantics toggleable, so the badge is
            // found by its text; only the frozen row carries it.
            waitUntilAtLeastOneExists(hasText("FROZEN · not spendable"), timeoutMillis = 3_000)
            assertEquals(1, onAllNodesWithText("FROZEN · not spendable").fetchSemanticsNodes().size)
            // Nothing is preselected, and the frozen row ignores clicks.
            waitUntilAtLeastOneExists(hasText("Selected: 0 of 2"))
            onNodeWithTag(SendTags.utxoCheckbox(frozen.outpoint)).assertIsNotEnabled()
            onNodeWithTag(SendTags.utxoCheckbox(free.outpoint)).performScrollTo().performClick()
            waitUntilAtLeastOneExists(hasText("Selected: 1 of 2"))
        }

    @Test
    fun `Send MAX toggle disables amount and routes Review through buildDrainTx`() =
        runDesktopComposeUiTest {
            val fake = FakeWalletPort(
                feeEstimates = FeeEstimates(
                    economySatPerVb = 1u,
                    normalSatPerVb = 5u,
                    prioritySatPerVb = 12u,
                ),
                balance = 400_000UL,
                psbtFee = 250UL,
                unsignedPsbtBuilder = { "psbt-from-drain" },
            )
            WalletStore.set(fake)

            var readyPsbt: String? = null
            var readyAmount: Long? = null
            setContent {
                BhodlTheme {
                    SendScreen(
                        onReady = { psbt, _, amount, _ ->
                            readyPsbt = psbt
                            readyAmount = amount
                        },
                        onBack = {},
                    )
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(SendTags.SEND_MAX_TOGGLE))

            // Toggle Send MAX on. Address only — no amount needed.
            onNodeWithTag(SendTags.ADDRESS).performTextInput(TARGET_ADDRESS)
            onNodeWithTag(SendTags.SEND_MAX_TOGGLE).performScrollTo().performClick()
            waitForIdle()
            onNodeWithTag(SendTags.REVIEW).performScrollTo().performClick()

            waitUntil(timeoutMillis = 2_000) { fake.buildDrainCalls.isNotEmpty() }
            assertEquals(1, fake.buildDrainCalls.size)
            val (toAddress, feeRate) = fake.buildDrainCalls.single()
            assertEquals(TARGET_ADDRESS, toAddress)
            assertEquals(5UL, feeRate)
            assertTrue(
                fake.buildSendTxCalls.isEmpty(),
                "Send MAX must NOT route through buildSendTx",
            )

            waitUntil(timeoutMillis = 2_000) { readyPsbt != null }
            assertEquals("psbt-from-drain", readyPsbt)
            // Send MAX passes no amount: the Review screen renders the
            // payout from the Rust-decoded PSBT, never `balance - fee`
            // computed here (which was wrong with frozen / unconfirmed
            // coins and ran FFI on the UI thread).
            assertEquals(0L, readyAmount)
        }

    @Test
    fun `fee estimate unavailable asks for a manual rate instead of using 1 sat per vB`() =
        runDesktopComposeUiTest {
            val fake = FakeWalletPort().apply {
                failFetchFees = WalletException.FeeEstimateUnavailable(detail = "implausible feed")
            }
            WalletStore.set(fake)
            var readyPsbt: String? = null
            setContent {
                BhodlTheme {
                    SendScreen(onReady = { psbt, _, _, _ -> readyPsbt = psbt }, onBack = {})
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(SendTags.FEE_UNAVAILABLE))
            onNodeWithTag(SendTags.ADDRESS).performTextInput(TARGET_ADDRESS)
            onNodeWithTag(SendTags.AMOUNT).performTextInput("1000")
            onNodeWithTag(SendTags.REVIEW).performScrollTo().performClick()
            waitUntilAtLeastOneExists(
                hasText("Fee estimate unavailable — enter a fee rate", substring = true),
            )
            assertTrue(fake.buildSendTxCalls.isEmpty(), "must not build with a silent default")

            onNodeWithTag(SendTags.MANUAL_FEE).performScrollTo().performTextInput("7")
            onNodeWithTag(SendTags.REVIEW).performScrollTo().performClick()
            waitUntil(timeoutMillis = 2_000) { readyPsbt != null }
            assertEquals(7UL, fake.buildSendTxCalls.single().third)
        }

    @Test
    fun `FeeTooHigh asks for confirmation and rebuilds with allowHighFee`() =
        runDesktopComposeUiTest {
            val fake = FakeWalletPort().apply {
                failBuildUnlessHighFeeAllowed =
                    WalletException.FeeTooHigh(feeSat = 90_000uL, limitSat = 50_000uL)
            }
            WalletStore.set(fake)
            var readyPsbt: String? = null
            setContent {
                BhodlTheme {
                    SendScreen(onReady = { psbt, _, _, _ -> readyPsbt = psbt }, onBack = {})
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(SendTags.ADDRESS))
            onNodeWithTag(SendTags.ADDRESS).performTextInput(TARGET_ADDRESS)
            onNodeWithTag(SendTags.AMOUNT).performTextInput("1000")
            onNodeWithTag(SendTags.REVIEW).performScrollTo().performClick()

            waitUntilAtLeastOneExists(hasTestTag(PsbtReviewTags.HIGH_FEE_DIALOG))
            assertEquals(null, readyPsbt, "nothing reaches Review before the confirmation")
            onNodeWithTag(PsbtReviewTags.HIGH_FEE_DIALOG_CONFIRM).performClick()
            waitUntil(timeoutMillis = 2_000) { readyPsbt != null }
            assertEquals(listOf(false, true), fake.allowHighFeeCalls)
        }

    @Test
    fun `cancelling the FeeTooHigh dialog builds nothing further`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort().apply {
            failBuildUnlessHighFeeAllowed =
                WalletException.FeeTooHigh(feeSat = 90_000uL, limitSat = 50_000uL)
        }
        WalletStore.set(fake)
        var readyPsbt: String? = null
        setContent {
            BhodlTheme {
                SendScreen(onReady = { psbt, _, _, _ -> readyPsbt = psbt }, onBack = {})
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(SendTags.ADDRESS))
        onNodeWithTag(SendTags.ADDRESS).performTextInput(TARGET_ADDRESS)
        onNodeWithTag(SendTags.AMOUNT).performTextInput("1000")
        onNodeWithTag(SendTags.REVIEW).performScrollTo().performClick()
        waitUntilAtLeastOneExists(hasTestTag(PsbtReviewTags.HIGH_FEE_DIALOG))
        onNodeWithTag(PsbtReviewTags.HIGH_FEE_DIALOG_CANCEL).performClick()
        waitForIdle()
        assertEquals(listOf(false), fake.allowHighFeeCalls)
        assertEquals(null, readyPsbt)
    }

    @Test
    fun `recipient address is shown in full in blocks of four`() = runDesktopComposeUiTest {
        // UX review 2026-10 (B11) — the start of the address must stay
        // visible: the field wraps and a grouped copy sits under it.
        WalletStore.set(FakeWalletPort())
        setContent {
            BhodlTheme {
                SendScreen(onReady = { _, _, _, _ -> }, onBack = {})
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(SendTags.ADDRESS))
        onNodeWithTag(SendTags.ADDRESS).performTextInput(TARGET_ADDRESS + "\n")
        // The grouped copy is the field's supporting text, merged into
        // the text field node: look it up in the unmerged tree.
        waitUntil(timeoutMillis = 2_000) {
            onAllNodes(hasTestTag(SendTags.ADDRESS_GROUPED), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(SendTags.ADDRESS_GROUPED, useUnmergedTree = true)
            .assertTextEquals(TARGET_ADDRESS.chunked(4).joinToString(" "))
        // A pasted trailing newline never reaches the address.
        assertEquals(
            TARGET_ADDRESS,
            onNodeWithTag(SendTags.ADDRESS).fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text,
        )
    }

    // -----------------------------------------------------------------
    // Address-book picker (Iteration 2.4)
    // -----------------------------------------------------------------

    @Test
    fun `picker fills address and shows picked label helper`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort().apply {
            contacts += ContactRecord(
                id = 1L,
                label = "Faucet",
                address = TARGET_ADDRESS,
                createdAtUnix = 0L,
            )
        }
        WalletStore.set(fake)

        setContent {
            BhodlTheme {
                SendScreen(onReady = { _, _, _, _ -> }, onBack = {})
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(SendTags.REVIEW))

        // Open the picker.
        onNodeWithTag(SendTags.PICK_FROM_BOOK).performScrollTo().performClick()
        waitUntilAtLeastOneExists(hasTestTag(ContactPickerTags.ROOT))

        // Tap the only row.
        onNodeWithTag(ContactPickerTags.rowFor(1L)).performClick()

        // Picker dismisses; the address field is now bound to the
        // contact's address; the supporting line surfaces the label.
        // (`To: Faucet` lives in OutlinedTextField.supportingText —
        // merged semantics hide the inner tag, so we go via text.)
        waitUntilAtLeastOneExists(hasText(TARGET_ADDRESS, substring = true))
        waitUntilAtLeastOneExists(hasText("To: Faucet"))
        onNodeWithText("To: Faucet").assertIsDisplayed()
    }

    @Test
    fun `picker filter narrows the visible list by label`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort().apply {
            contacts += ContactRecord(1L, "Alice", TARGET_ADDRESS, 0L)
            contacts += ContactRecord(2L, "Bob", SECOND_ADDRESS, 0L)
        }
        WalletStore.set(fake)

        setContent {
            BhodlTheme {
                SendScreen(onReady = { _, _, _, _ -> }, onBack = {})
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(SendTags.REVIEW))

        onNodeWithTag(SendTags.PICK_FROM_BOOK).performScrollTo().performClick()
        waitUntilAtLeastOneExists(hasTestTag(ContactPickerTags.ROOT))

        // Both rows present pre-filter.
        onNodeWithTag(ContactPickerTags.rowFor(1L)).assertIsDisplayed()
        onNodeWithTag(ContactPickerTags.rowFor(2L)).assertIsDisplayed()

        onNodeWithTag(ContactPickerTags.SEARCH).performTextInput("bob")
        waitUntil(timeoutMillis = 1_000) {
            // Alice's row should disappear after the filter applies.
            onAllNodesWithTag(ContactPickerTags.rowFor(1L))
                .fetchSemanticsNodes().isEmpty()
        }
        onNodeWithTag(ContactPickerTags.rowFor(2L)).assertIsDisplayed()
    }

    @Test
    fun `picker filter does NOT match address fragments`() = runDesktopComposeUiTest {
        // Regression test: bech32m addresses contain alphanumeric noise
        // that easily matches arbitrary 2-3 char substrings. Filtering
        // on the address body produced spurious matches when users
        // typed an early letter of a label name. The picker MUST
        // filter on label only.
        val fake = FakeWalletPort().apply {
            // "Alice" + address starting with "tb1ptarget…"
            contacts += ContactRecord(1L, "Alice", TARGET_ADDRESS, 0L)
            // "Bob" + address starting with "tb1psecond…"
            contacts += ContactRecord(2L, "Bob", SECOND_ADDRESS, 0L)
        }
        WalletStore.set(fake)

        setContent {
            BhodlTheme {
                SendScreen(onReady = { _, _, _, _ -> }, onBack = {})
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(SendTags.REVIEW))
        onNodeWithTag(SendTags.PICK_FROM_BOOK).performScrollTo().performClick()
        waitUntilAtLeastOneExists(hasTestTag(ContactPickerTags.ROOT))

        // "target" appears inside TARGET_ADDRESS but not in any label
        // ("Alice" / "Bob"). Old buggy filter would have matched Alice's
        // row via the address substring; the fixed filter must NOT.
        onNodeWithTag(ContactPickerTags.SEARCH).performTextInput("target")
        waitUntilAtLeastOneExists(hasTestTag(ContactPickerTags.NO_MATCH))
        onNodeWithTag(ContactPickerTags.NO_MATCH).assertIsDisplayed()
    }

    @Test
    fun `picker with empty book renders the empty hint`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort()
        WalletStore.set(fake)

        setContent {
            BhodlTheme {
                SendScreen(onReady = { _, _, _, _ -> }, onBack = {})
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(SendTags.REVIEW))

        onNodeWithTag(SendTags.PICK_FROM_BOOK).performScrollTo().performClick()
        waitUntilAtLeastOneExists(hasTestTag(ContactPickerTags.ROOT))
        onNodeWithTag(ContactPickerTags.EMPTY).assertIsDisplayed()
    }

    @Test
    fun `editing the address manually clears the picked label`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort().apply {
            contacts += ContactRecord(1L, "Faucet", TARGET_ADDRESS, 0L)
        }
        WalletStore.set(fake)

        setContent {
            BhodlTheme {
                SendScreen(onReady = { _, _, _, _ -> }, onBack = {})
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(SendTags.REVIEW))

        onNodeWithTag(SendTags.PICK_FROM_BOOK).performScrollTo().performClick()
        waitUntilAtLeastOneExists(hasTestTag(ContactPickerTags.ROOT))
        onNodeWithTag(ContactPickerTags.rowFor(1L)).performClick()
        waitUntilAtLeastOneExists(hasText("To: Faucet"))

        // Type extra characters into the address field — the helper
        // line must drop because the address no longer corresponds
        // to the originally-picked contact.
        onNodeWithTag(SendTags.ADDRESS).performTextInput("x")
        waitUntil(timeoutMillis = 1_000) {
            onAllNodesWithText("To: Faucet").fetchSemanticsNodes().isEmpty()
        }
    }

    // -----------------------------------------------------------------
    // PayJoin (BIP-78) — Privacy iteration
    // -----------------------------------------------------------------

    @Test
    fun `pasting a pj URI surfaces the PayJoin badge`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort()
        WalletStore.set(fake)

        setContent {
            BhodlTheme {
                SendScreen(onReady = { _, _, _, _ -> }, onBack = {})
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(SendTags.ADDRESS))

        val uri = "bitcoin:$TARGET_ADDRESS?amount=0.001&pj=https://payjoin.example.com/btc"
        onNodeWithTag(SendTags.ADDRESS).performTextInput(uri)

        waitUntilAtLeastOneExists(hasTestTag(SendTags.PAYJOIN_BADGE))
        onNodeWithTag(SendTags.PAYJOIN_BADGE).assertIsDisplayed()
        // The amount field still gets pre-populated from the URI.
        waitUntilAtLeastOneExists(hasText("100000", substring = true))
    }

    @Test
    fun `Review CTA flips to Review PayJoin when pj is detected`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort()
        WalletStore.set(fake)

        setContent {
            BhodlTheme {
                SendScreen(onReady = { _, _, _, _ -> }, onBack = {})
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(SendTags.ADDRESS))
        // Without a pj URI the CTA reads "Review".
        waitUntilAtLeastOneExists(hasText("Review", substring = true))

        onNodeWithTag(SendTags.ADDRESS).performTextInput(
            "bitcoin:$TARGET_ADDRESS?amount=0.001&pj=https://x.example/y",
        )
        waitUntilAtLeastOneExists(hasText("Review PayJoin", substring = true))
    }

    @Test
    fun `PayJoin negotiates, shows the review, and signs only after confirm`() =
        runDesktopComposeUiTest {
            val newTxid = "feed".repeat(16)
            val fake = FakeWalletPort(
                feeEstimates = io.bhodl.ffi.FeeEstimates(
                    economySatPerVb = 1u,
                    normalSatPerVb = 5u,
                    prioritySatPerVb = 12u,
                ),
            ).apply {
                payjoinTxidBuilder = { newTxid }
            }
            WalletStore.set(fake)

            var pjTxid: String? = null
            var reviewFired = false
            setContent {
                BhodlTheme {
                    SendScreen(
                        onReady = { _, _, _, _ -> reviewFired = true },
                        onPayjoinSent = { pjTxid = it },
                        onBack = {},
                    )
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(SendTags.REVIEW))

            val uri = "bitcoin:$TARGET_ADDRESS?amount=0.001&pj=https://payjoin.example.com/btc"
            onNodeWithTag(SendTags.ADDRESS).performTextInput(uri)
            waitUntilAtLeastOneExists(hasTestTag(SendTags.PAYJOIN_BADGE))

            onNodeWithTag(SendTags.REVIEW).performScrollTo().performClick()
            waitUntil(timeoutMillis = 2_000) { fake.payjoinNegotiateCalls.isNotEmpty() }

            // Negotiate got the URI verbatim plus what the form shows.
            val call = fake.payjoinNegotiateCalls.single()
            assertEquals(uri, call.uri)
            assertEquals(TARGET_ADDRESS, call.expectedAddress)
            assertEquals(100_000UL, call.expectedAmountSat)
            assertEquals(5UL, call.feeRateSatVb, "default tier is Normal -> 5 sat/vB")

            // Review dialog lists every output; nothing signed yet.
            waitUntilAtLeastOneExists(hasTestTag(SendTags.PAYJOIN_REVIEW_DIALOG))
            waitUntilAtLeastOneExists(hasTestTag(SendTags.payjoinOutput(0)))
            waitUntilAtLeastOneExists(hasTestTag(SendTags.payjoinOutput(1)))
            waitUntilAtLeastOneExists(hasText("change · yours", substring = true))
            assertTrue(fake.payjoinSignCalls.isEmpty(), "must not sign before confirm")
            assertTrue(fake.broadcastCalls.isEmpty())

            onNodeWithTag(SendTags.PAYJOIN_CONFIRM).performClick()
            waitUntil(timeoutMillis = 2_000) { pjTxid != null }
            assertEquals("fake-session", fake.payjoinSignCalls.single().sessionId)
            assertEquals(newTxid, pjTxid)
            assertEquals(false, reviewFired, "PayJoin must NOT fire onReady")
        }

    @Test
    fun `cancelling the PayJoin review discards the session and signs nothing`() =
        runDesktopComposeUiTest {
            val fake = FakeWalletPort()
            WalletStore.set(fake)
            setContent {
                BhodlTheme {
                    SendScreen(onReady = { _, _, _, _ -> }, onBack = {})
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(SendTags.REVIEW))
            onNodeWithTag(SendTags.ADDRESS).performTextInput(
                "bitcoin:$TARGET_ADDRESS?amount=0.001&pj=https://x.example/y",
            )
            waitUntilAtLeastOneExists(hasTestTag(SendTags.PAYJOIN_BADGE))
            onNodeWithTag(SendTags.REVIEW).performScrollTo().performClick()
            waitUntilAtLeastOneExists(hasTestTag(SendTags.PAYJOIN_CANCEL))
            onNodeWithTag(SendTags.PAYJOIN_CANCEL).performClick()
            waitUntil(timeoutMillis = 2_000) { fake.payjoinDiscardCalls.isNotEmpty() }
            assertEquals("fake-session", fake.payjoinDiscardCalls.single())
            assertTrue(fake.payjoinSignCalls.isEmpty())
            assertTrue(fake.broadcastCalls.isEmpty())
        }

    @Test
    fun `failed negotiation offers to broadcast the original without PayJoin`() =
        runDesktopComposeUiTest {
            var sentTxid: String? = null
            val fake = FakeWalletPort().apply {
                payjoinReviewBuilder = { call ->
                    io.bhodl.ffi.PayjoinReview(
                        sessionId = null,
                        failureReason = "PayJoin proposal rejected: a sender output is missing",
                        originalPsbt = "signed-original",
                        originalFeeSat = 200u,
                        proposalFeeSat = 200u,
                        senderFeeContributionSat = 0u,
                        vsize = 154u,
                        receiverInputCount = 0u,
                        senderNetSpendSat = call.expectedAmountSat + 200u,
                        outputs = listOf(
                            io.bhodl.ffi.PayjoinOutput(
                                address = call.expectedAddress,
                                amountSat = call.expectedAmountSat,
                                isMine = false,
                                isPayment = true,
                            ),
                        ),
                    )
                }
            }
            WalletStore.set(fake)
            setContent {
                BhodlTheme {
                    SendScreen(
                        onReady = { _, _, _, _ -> },
                        onPayjoinSent = { sentTxid = it },
                        onBack = {},
                    )
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(SendTags.REVIEW))
            onNodeWithTag(SendTags.ADDRESS).performTextInput(
                "bitcoin:$TARGET_ADDRESS?amount=0.001&pj=https://x.example/y",
            )
            waitUntilAtLeastOneExists(hasTestTag(SendTags.PAYJOIN_BADGE))
            onNodeWithTag(SendTags.REVIEW).performScrollTo().performClick()
            waitUntilAtLeastOneExists(hasTestTag(SendTags.PAYJOIN_FAILURE))
            waitUntilAtLeastOneExists(hasText("Send without PayJoin"))
            onNodeWithTag(SendTags.PAYJOIN_CONFIRM).performClick()
            waitUntil(timeoutMillis = 2_000) { sentTxid != null }
            assertEquals("signed-original", fake.broadcastCalls.single().second)
            assertTrue(fake.payjoinSignCalls.isEmpty())
        }

    @Test
    fun `PayJoin failure renders the error message and keeps the screen usable`() =
        runDesktopComposeUiTest {
            val fake = FakeWalletPort().apply {
                failPayjoinNegotiate = WalletException.Broadcast(
                    detail = "PayJoin endpoint returned HTTP 500: server error",
                )
            }
            WalletStore.set(fake)

            setContent {
                BhodlTheme {
                    SendScreen(onReady = { _, _, _, _ -> }, onBack = {})
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(SendTags.REVIEW))
            onNodeWithTag(SendTags.ADDRESS).performTextInput(
                "bitcoin:$TARGET_ADDRESS?amount=0.001&pj=https://x.example/y",
            )
            waitUntilAtLeastOneExists(hasTestTag(SendTags.PAYJOIN_BADGE))
            onNodeWithTag(SendTags.REVIEW).performScrollTo().performClick()
            // The prettifier converts `PayJoin endpoint returned HTTP …`
            // into a user-readable line — assert against the prettified
            // text, not the raw HTTP code.
            waitUntilAtLeastOneExists(
                hasText("PayJoin receiver rejected the request", substring = true),
            )
        }

    @Test
    fun `PayJoin FeeTooHigh asks for confirmation and renegotiates with allowHighFee`() =
        runDesktopComposeUiTest {
            val fake = FakeWalletPort().apply {
                failBuildUnlessHighFeeAllowed =
                    WalletException.FeeTooHigh(feeSat = 90_000uL, limitSat = 50_000uL)
            }
            WalletStore.set(fake)
            setContent {
                BhodlTheme {
                    SendScreen(onReady = { _, _, _, _ -> }, onBack = {})
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(SendTags.REVIEW))
            onNodeWithTag(SendTags.ADDRESS).performTextInput(
                "bitcoin:$TARGET_ADDRESS?amount=0.001&pj=https://x.example/y",
            )
            waitUntilAtLeastOneExists(hasTestTag(SendTags.PAYJOIN_BADGE))
            onNodeWithTag(SendTags.REVIEW).performScrollTo().performClick()

            waitUntilAtLeastOneExists(hasTestTag(PsbtReviewTags.HIGH_FEE_DIALOG))
            assertEquals(
                listOf(false),
                fake.payjoinNegotiateCalls.map { it.allowHighFee },
                "first attempt never pre-confirms the fee",
            )
            assertTrue(fake.payjoinSignCalls.isEmpty())
            onNodeWithTag(PsbtReviewTags.HIGH_FEE_DIALOG_CONFIRM).performClick()
            waitUntilAtLeastOneExists(hasTestTag(SendTags.PAYJOIN_REVIEW_DIALOG))
            assertEquals(listOf(false, true), fake.payjoinNegotiateCalls.map { it.allowHighFee })
        }

    @Test
    fun `cancelling the PayJoin FeeTooHigh dialog negotiates nothing further`() =
        runDesktopComposeUiTest {
            val fake = FakeWalletPort().apply {
                failBuildUnlessHighFeeAllowed =
                    WalletException.FeeTooHigh(feeSat = 90_000uL, limitSat = 50_000uL)
            }
            WalletStore.set(fake)
            setContent {
                BhodlTheme {
                    SendScreen(onReady = { _, _, _, _ -> }, onBack = {})
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(SendTags.REVIEW))
            onNodeWithTag(SendTags.ADDRESS).performTextInput(
                "bitcoin:$TARGET_ADDRESS?amount=0.001&pj=https://x.example/y",
            )
            waitUntilAtLeastOneExists(hasTestTag(SendTags.PAYJOIN_BADGE))
            onNodeWithTag(SendTags.REVIEW).performScrollTo().performClick()
            waitUntilAtLeastOneExists(hasTestTag(PsbtReviewTags.HIGH_FEE_DIALOG))
            onNodeWithTag(PsbtReviewTags.HIGH_FEE_DIALOG_CANCEL).performClick()
            waitForIdle()
            assertEquals(listOf(false), fake.payjoinNegotiateCalls.map { it.allowHighFee })
            assertTrue(onAllNodesWithTag(SendTags.PAYJOIN_REVIEW_DIALOG).fetchSemanticsNodes().isEmpty())
        }

    // H7 — the form must never show one payment while PayJoin pays another.

    @Test
    fun `editing the amount disarms PayJoin`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort()
        WalletStore.set(fake)
        setContent {
            BhodlTheme {
                SendScreen(onReady = { _, _, _, _ -> }, onBack = {})
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(SendTags.ADDRESS))
        onNodeWithTag(SendTags.ADDRESS).performTextInput(
            "bitcoin:$TARGET_ADDRESS?amount=0.001&pj=https://x.example/y",
        )
        waitUntilAtLeastOneExists(hasTestTag(SendTags.PAYJOIN_BADGE))
        onNodeWithTag(SendTags.AMOUNT).performTextInput("5")
        waitUntil(timeoutMillis = 1_000) {
            onAllNodesWithTag(SendTags.PAYJOIN_BADGE).fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun `picking a contact disarms PayJoin`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort().apply {
            contacts += ContactRecord(2L, "Bob", SECOND_ADDRESS, 0L)
        }
        WalletStore.set(fake)
        setContent {
            BhodlTheme {
                SendScreen(onReady = { _, _, _, _ -> }, onBack = {})
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(SendTags.ADDRESS))
        onNodeWithTag(SendTags.ADDRESS).performTextInput(
            "bitcoin:$TARGET_ADDRESS?amount=0.001&pj=https://x.example/y",
        )
        waitUntilAtLeastOneExists(hasTestTag(SendTags.PAYJOIN_BADGE))
        onNodeWithTag(SendTags.PICK_FROM_BOOK).performScrollTo().performClick()
        waitUntilAtLeastOneExists(hasTestTag(ContactPickerTags.ROOT))
        onNodeWithTag(ContactPickerTags.rowFor(2L)).performClick()
        waitUntil(timeoutMillis = 1_000) {
            onAllNodesWithTag(SendTags.PAYJOIN_BADGE).fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun `Clear PayJoin button disarms PayJoin`() = runDesktopComposeUiTest {
        WalletStore.set(FakeWalletPort())
        setContent {
            BhodlTheme {
                SendScreen(onReady = { _, _, _, _ -> }, onBack = {})
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(SendTags.ADDRESS))
        onNodeWithTag(SendTags.ADDRESS).performTextInput(
            "bitcoin:$TARGET_ADDRESS?amount=0.001&pj=https://x.example/y",
        )
        waitUntilAtLeastOneExists(hasTestTag(SendTags.PAYJOIN_CLEAR))
        onNodeWithTag(SendTags.PAYJOIN_CLEAR).performScrollTo().performClick()
        waitUntil(timeoutMillis = 1_000) {
            onAllNodesWithTag(SendTags.PAYJOIN_BADGE).fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun `URI with duplicate amount keys is rejected and arms nothing`() =
        runDesktopComposeUiTest {
            WalletStore.set(FakeWalletPort())
            setContent {
                BhodlTheme {
                    SendScreen(onReady = { _, _, _, _ -> }, onBack = {})
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(SendTags.ADDRESS))
            // C3: Kotlin used to show 1000 sat (last wins) while Rust
            // paid 1 BTC (first wins).
            onNodeWithTag(SendTags.ADDRESS).performTextInput(
                "bitcoin:$TARGET_ADDRESS?amount=1&AMOUNT=0.00001&pj=https://x.example/y",
            )
            waitUntilAtLeastOneExists(hasText("Invalid bitcoin: URI", substring = true))
            assertTrue(onAllNodesWithTag(SendTags.PAYJOIN_BADGE).fetchSemanticsNodes().isEmpty())
            assertTrue(onAllNodesWithText("1000").fetchSemanticsNodes().isEmpty())
            assertTrue(onAllNodesWithText("100000000").fetchSemanticsNodes().isEmpty())
        }

    // -----------------------------------------------------------------
    // BIP-21 paste semantics — regression for the finding #9 fix.
    // -----------------------------------------------------------------

    @Test
    fun `pasting BIP-21 URI with new amount overrides previously typed amount`() =
        runDesktopComposeUiTest {
            val fake = FakeWalletPort()
            WalletStore.set(fake)
            setContent {
                BhodlTheme {
                    SendScreen(onReady = { _, _, _, _ -> }, onBack = {})
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(SendTags.ADDRESS))

            // User types an amount manually first.
            onNodeWithTag(SendTags.AMOUNT).performTextInput("999")
            waitUntilAtLeastOneExists(hasText("999"))

            // Then pastes a BIP-21 URI carrying a different amount.
            // Previous behaviour preserved "999" — confusing. New
            // behaviour: the URI's amount wins (0.0001 BTC = 10000 sat).
            onNodeWithTag(SendTags.ADDRESS).performTextInput(
                "bitcoin:$TARGET_ADDRESS?amount=0.0001",
            )
            waitUntilAtLeastOneExists(hasText("10000"))
        }

    @Test
    fun `pasting BIP-21 URI without amount leaves a previously typed amount alone`() =
        runDesktopComposeUiTest {
            val fake = FakeWalletPort()
            WalletStore.set(fake)
            setContent {
                BhodlTheme {
                    SendScreen(onReady = { _, _, _, _ -> }, onBack = {})
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(SendTags.ADDRESS))

            onNodeWithTag(SendTags.AMOUNT).performTextInput("4321")
            waitUntilAtLeastOneExists(hasText("4321"))

            // URI has no `amount=...`; the user's manual entry must
            // survive (URI is not authoritative for fields it does
            // not declare).
            onNodeWithTag(SendTags.ADDRESS).performTextInput(
                "bitcoin:$TARGET_ADDRESS",
            )
            // Tiny wait to make sure recomposition has settled.
            waitForIdle()
            waitForIdle()
            onNodeWithText("4321").assertIsDisplayed()
        }

    // ---- UX review 2026-10 (§4 item 6) — fee presets as total + ETA ----------

    @Test
    fun `fee presets show the estimated total in sats and the ETA`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort(
            feeEstimates = FeeEstimates(economySatPerVb = 2u, normalSatPerVb = 5u, prioritySatPerVb = 12u),
        )
        fake.utxos.add(
            UtxoRecord("$UTXO_TXID:0", 500_000u, "tb1pcoin", isChange = false, confirmed = true, frozen = false),
        )
        WalletStore.set(fake)
        setContent { BhodlTheme { SendScreen(onReady = { _, _, _, _ -> }) } }
        waitUntilAtLeastOneExists(hasText("12 sat/vB"))
        // 1 input, 1 recipient + change = 154 vB.
        onNodeWithTag(SendTags.feeTier("economy")).assertTextContains("≈ 308 sat", substring = true)
        onNodeWithTag(SendTags.feeTier("normal")).assertTextContains("≈ 770 sat", substring = true)
        onNodeWithTag(SendTags.feeTier("priority")).assertTextContains("≈ 1,848 sat", substring = true)
        onNodeWithTag(SendTags.feeTier("priority")).assertTextContains("~10 min", substring = true)
        onNodeWithTag(SendTags.feeTier("normal")).assertTextContains("~30 min", substring = true)
        onNodeWithTag(SendTags.feeTier("economy")).assertTextContains("~1 h", substring = true)

        // A second recipient adds an output: (42 + 230 + 3·172) / 4 = 197 vB.
        onNodeWithTag(SendTags.ADD_RECIPIENT).performScrollTo().performClick()
        waitUntil(timeoutMillis = 2_000) {
            runCatching {
                onNodeWithTag(SendTags.feeTier("normal")).assertTextContains("≈ 985 sat", substring = true)
            }.isSuccess
        }
    }

    @Test
    fun `custom fee rate is collapsed by default and overrides the preset once set`() =
        runDesktopComposeUiTest {
            val fake = tieredFakeForCustom()
            WalletStore.set(fake)
            var ready = 0
            setContent { BhodlTheme { SendScreen(onReady = { _, _, _, _ -> ready++ }) } }
            waitUntilAtLeastOneExists(hasText("12 sat/vB"))
            // Progressive disclosure: presets first, custom field hidden.
            assertTrue(onAllNodesWithTag(SendTags.MANUAL_FEE).fetchSemanticsNodes().isEmpty())
            onNodeWithTag(SendTags.CUSTOM_FEE_TOGGLE).performScrollTo().performClick()
            onNodeWithTag(SendTags.MANUAL_FEE).performScrollTo().performTextInput("9")
            // No preset highlighted while the custom rate is in use.
            onNodeWithTag(SendTags.feeTier("normal")).assertIsNotSelected()

            onNodeWithTag(SendTags.ADDRESS).performTextInput(TARGET_ADDRESS)
            onNodeWithTag(SendTags.AMOUNT).performTextInput("1000")
            onNodeWithTag(SendTags.REVIEW).performScrollTo().performClick()
            waitUntil(timeoutMillis = 2_000) { ready == 1 }
            assertEquals(9UL, fake.buildSendTxCalls.single().third)

            // Picking a preset again drops the custom rate.
            onNodeWithTag(SendTags.feeTier("priority")).performScrollTo().performClick()
            onNodeWithTag(SendTags.REVIEW).performScrollTo().performClick()
            waitUntil(timeoutMillis = 2_000) { ready == 2 }
            assertEquals(12UL, fake.buildSendTxCalls.last().third)
        }

    @Test
    fun `preselected outpoints open with coin control pinned to them`() = runDesktopComposeUiTest {
        val one = UtxoRecord("$UTXO_TXID:0", 50_000u, "tb1pone", isChange = false, confirmed = true, frozen = false)
        val two = one.copy(outpoint = "$UTXO_TXID:1", address = "tb1ptwo")
        // Frozen after being picked in the UTXOs tab: must be dropped.
        val frozen = one.copy(outpoint = "$UTXO_TXID:2", address = "tb1pfrozen", frozen = true)
        val fake = tieredFakeForCustom().apply { utxos += listOf(one, two, frozen) }
        WalletStore.set(fake)
        var ready = 0
        var consumed = 0
        setContent {
            BhodlTheme {
                SendScreen(
                    onReady = { _, _, _, _ -> ready++ },
                    preselectedOutpoints = listOf(two.outpoint, frozen.outpoint),
                    onPreselectionConsumed = { consumed++ },
                )
            }
        }
        waitUntilAtLeastOneExists(hasText("Selected: 1 of 3"), timeoutMillis = 3_000)
        assertEquals(1, consumed, "the owner is told to clear the one-shot selection")
        onNodeWithTag(SendTags.ADDRESS).performTextInput(TARGET_ADDRESS)
        onNodeWithTag(SendTags.AMOUNT).performTextInput("1000")
        onNodeWithTag(SendTags.REVIEW).performScrollTo().performClick()
        waitUntil(timeoutMillis = 2_000) { ready == 1 }
        assertEquals(listOf(two.outpoint), fake.buildWithUtxosCalls.single().outpoints)
    }

    private fun tieredFakeForCustom() = FakeWalletPort(
        feeEstimates = FeeEstimates(economySatPerVb = 2u, normalSatPerVb = 5u, prioritySatPerVb = 12u),
    )

    private companion object {
        const val TARGET_ADDRESS: String =
            "tb1ptarget00000000000000000000000000000000000000000000000000000000"
        const val SECOND_ADDRESS: String =
            "tb1psecond00000000000000000000000000000000000000000000000000000000"
        const val UTXO_TXID: String =
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
    }
}
