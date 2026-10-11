package io.bhodl.desktop.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.bhodl.desktop.wallet.FakeWalletPort
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.ffi.AddressRecord
import io.bhodl.ffi.WalletException
import io.bhodl.ui.theme.BhodlTheme
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compose UI tests for the label-first [`ReceiveScreen`] (UX review
 * 2026-10, §4 item 7) driven by [`FakeWalletPort`].
 *
 * - Step 1 asks for label / tracker category / amount; nothing is
 *   revealed until the user continues (or skips).
 * - Continuing reveals one fresh index and stores the label + category
 *   on that address before it is shown.
 * - Amount + label reach the QR via the BIP-21 URI; the QR `testTag`
 *   doesn't carry the payload, so the URI preview Text is asserted.
 * - An address that already received funds shows a reuse warning.
 */
@OptIn(ExperimentalTestApi::class)
class ReceiveScreenTest {

    @AfterTest
    fun teardownStore() {
        WalletStore.clear()
    }

    @Test
    fun `opening the tab reveals no address until the user continues`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort()
        WalletStore.set(fake)
        setContent { BhodlTheme { ReceiveScreen() } }
        waitUntilAtLeastOneExists(hasTestTag(ReceiveTags.REQUEST_STEP))
        waitForIdle()
        assertTrue(fake.nextReceiveCalls.isEmpty(), "label-first: no index consumed on open")
        assertTrue(fake.nextUnusedCalls.isEmpty())

        onNodeWithTag(ReceiveTags.SKIP).performClick()
        waitUntil(timeoutMillis = 2_000) { fake.nextUnusedCalls.size == 1 }
        // Continue hands out the lowest UNUSED address, never a new index.
        assertTrue(fake.nextReceiveCalls.isEmpty())
        // The fake's builder yields tb1pfakeaddress0xxx…
        waitUntilAtLeastOneExists(hasText("bitcoin:tb1pfakeaddress0", substring = true))
        onNodeWithTag(ReceiveTags.URI).assertIsDisplayed()
        // Skipping stores no label.
        assertTrue(fake.addressLabelCalls.isEmpty())
    }

    @Test
    fun `label and category are attached to the address before it is shown`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort()
        WalletStore.set(fake)
        setContent { BhodlTheme { ReceiveScreen() } }
        waitUntilAtLeastOneExists(hasTestTag(ReceiveTags.REQUEST_STEP))
        onNodeWithTag(ReceiveTags.LABEL).performTextInput("Invoice 42")
        onNodeWithTag(ReceiveTags.CATEGORY).performTextInput("Freelance")
        // With details typed, only "Show address" remains (no Skip).
        assertTrue(onAllNodesWithTag(ReceiveTags.SKIP).fetchSemanticsNodes().isEmpty())
        onNodeWithTag(ReceiveTags.CONTINUE).performClick()

        waitUntil(timeoutMillis = 2_000) { fake.addressLabelCalls.isNotEmpty() }
        val (address, label, category) = fake.addressLabelCalls.single()
        assertTrue(address.startsWith("tb1pfakeaddress0"))
        assertEquals("Invoice 42", label)
        assertEquals("Freelance", category)
        // The label is private bookkeeping: it never reaches the URI.
        waitUntilAtLeastOneExists(hasTestTag(ReceiveTags.URI))
        onNodeWithTag(ReceiveTags.URI).assertTextEquals("bitcoin:$address")
    }

    @Test
    fun `New address advances to the next index and keeps the label`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort()
        WalletStore.set(fake)
        setContent { BhodlTheme { ReceiveScreen() } }
        waitUntilAtLeastOneExists(hasTestTag(ReceiveTags.REQUEST_STEP))
        onNodeWithTag(ReceiveTags.LABEL).performTextInput("Rent")
        onNodeWithTag(ReceiveTags.CONTINUE).performClick()
        waitUntil(timeoutMillis = 2_000) { fake.nextUnusedCalls.size == 1 }
        waitUntilAtLeastOneExists(hasTestTag(ReceiveTags.NEW_ADDRESS))
        onNodeWithTag(ReceiveTags.NEW_ADDRESS).performScrollTo().performClick()
        // Only the explicit "New address" reveals a new index.
        waitUntil(timeoutMillis = 2_000) { fake.nextReceiveCalls.size == 1 }
        waitUntilAtLeastOneExists(hasText("tb1pfakeaddress1", substring = true))
        waitUntil(timeoutMillis = 2_000) { fake.addressLabelCalls.size == 2 }
        assertEquals("Rent", fake.addressLabelCalls.last().second)
    }

    @Test
    fun `amount produces a BIP-21 URI with the amount in BTC`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort()
        WalletStore.set(fake)
        setContent { BhodlTheme { ReceiveScreen() } }
        waitUntilAtLeastOneExists(hasTestTag(ReceiveTags.REQUEST_STEP))
        // 100_000 sat = 0.001 BTC.
        onNodeWithTag(ReceiveTags.AMOUNT).performTextInput("100000")
        onNodeWithTag(ReceiveTags.CONTINUE).performClick()
        waitUntilAtLeastOneExists(hasText("amount=0.001", substring = true))
        onNodeWithTag(ReceiveTags.URI).assertIsDisplayed()
    }

    @Test
    fun `only the explicit payer message reaches the URI, percent-encoded`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort()
        WalletStore.set(fake)
        setContent { BhodlTheme { ReceiveScreen() } }
        waitUntilAtLeastOneExists(hasTestTag(ReceiveTags.REQUEST_STEP))
        onNodeWithTag(ReceiveTags.LABEL).performTextInput("Private bookkeeping")
        onNodeWithTag(ReceiveTags.PAYER_MESSAGE).performTextInput("Coffee with Alice")
        onNodeWithTag(ReceiveTags.CONTINUE).performClick()
        waitUntilAtLeastOneExists(hasText("message=Coffee%20with%20Alice", substring = true))
        onNodeWithTag(ReceiveTags.URI).assertTextEquals(
            "bitcoin:${fake.nextReceiveBuilder(0)}?message=Coffee%20with%20Alice",
        )
    }

    @Test
    fun `editing the request relabels the same address without revealing another`() =
        runDesktopComposeUiTest {
            val fake = FakeWalletPort()
            WalletStore.set(fake)
            setContent { BhodlTheme { ReceiveScreen() } }
            waitUntilAtLeastOneExists(hasTestTag(ReceiveTags.REQUEST_STEP))
            onNodeWithTag(ReceiveTags.LABEL).performTextInput("Rent")
            onNodeWithTag(ReceiveTags.CONTINUE).performClick()
            waitUntilAtLeastOneExists(hasTestTag(ReceiveTags.NEW_REQUEST))
            onNodeWithTag(ReceiveTags.NEW_REQUEST).performClick()
            waitUntilAtLeastOneExists(hasTestTag(ReceiveTags.REQUEST_STEP))
            onNodeWithTag(ReceiveTags.LABEL).performTextReplacement("Rent May")
            onNodeWithTag(ReceiveTags.CONTINUE).performClick()
            waitUntil(timeoutMillis = 2_000) { fake.addressLabelCalls.size == 2 }
            val (first, second) = fake.addressLabelCalls
            assertEquals(first.first, second.first, "same address relabelled")
            assertEquals("Rent May", second.second)
            assertEquals(0, fake.revealCounter - 1, "still index 0")
            assertTrue(fake.nextReceiveCalls.isEmpty())
        }

    @Test
    fun `re-opening the tab hands out the same unused address`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort()
        WalletStore.set(fake)
        var visit by mutableStateOf(0)
        setContent { BhodlTheme { key(visit) { ReceiveScreen() } } }
        repeat(3) {
            waitUntilAtLeastOneExists(hasTestTag(ReceiveTags.SKIP))
            onNodeWithTag(ReceiveTags.SKIP).performClick()
            waitUntilAtLeastOneExists(hasTestTag(ReceiveTags.URI))
            onNodeWithTag(ReceiveTags.URI).assertTextEquals("bitcoin:${fake.nextReceiveBuilder(0)}")
            visit++
            waitForIdle()
        }
        assertEquals(1, fake.revealCounter, "one index for three visits")
    }

    @Test
    fun `digits-only filter rejects letters and decimals in amount`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort()
        WalletStore.set(fake)
        setContent { BhodlTheme { ReceiveScreen() } }
        waitUntilAtLeastOneExists(hasTestTag(ReceiveTags.REQUEST_STEP))
        // Mixed input: only digits remain. 0.001abc42 → 000142 → 142 sat.
        onNodeWithTag(ReceiveTags.AMOUNT).performTextInput("0.001abc42")
        onNodeWithTag(ReceiveTags.CONTINUE).performClick()
        // BIP-21 amount = 142 sat / 1e8 = 0.00000142 BTC.
        waitUntilAtLeastOneExists(hasText("amount=0.00000142", substring = true))
    }

    @Test
    fun `a previously used address shows the reuse warning`() = runDesktopComposeUiTest {
        val used = "tb1pusedaddress".padEnd(62, 'u')
        val fake = FakeWalletPort().apply {
            receiveAddresses += AddressRecord(0u, used, false, true, "Salary", null)
        }
        WalletStore.set(fake)
        setContent { BhodlTheme { ReceiveScreen(presetAddress = used) } }
        waitUntilAtLeastOneExists(hasTestTag(ReceiveTags.REUSE_WARNING))
        assertTrue(fake.nextReceiveCalls.isEmpty())
    }

    @Test
    fun `a fresh address shows no reuse warning`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort()
        WalletStore.set(fake)
        setContent { BhodlTheme { ReceiveScreen() } }
        waitUntilAtLeastOneExists(hasTestTag(ReceiveTags.REQUEST_STEP))
        onNodeWithTag(ReceiveTags.SKIP).performClick()
        waitUntilAtLeastOneExists(hasTestTag(ReceiveTags.URI))
        assertTrue(onAllNodesWithTag(ReceiveTags.REUSE_WARNING).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `Back fires onBack when shown stand-alone`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort()
        WalletStore.set(fake)
        var backCalled = 0
        setContent { BhodlTheme { ReceiveScreen(onBack = { backCalled++ }) } }
        onNodeWithTag(ReceiveTags.BACK).performScrollTo().performClick()
        waitUntil(timeoutMillis = 1_000) { backCalled == 1 }
        assertEquals(1, backCalled)
    }

    @Test
    fun `derive failure renders the error card`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort().apply {
            failNextReceive = WalletException.Storage(detail = "boom")
        }
        WalletStore.set(fake)
        setContent { BhodlTheme { ReceiveScreen() } }
        waitUntilAtLeastOneExists(hasTestTag(ReceiveTags.SKIP))
        onNodeWithTag(ReceiveTags.SKIP).performClick()
        waitUntilAtLeastOneExists(hasTestTag(ReceiveTags.ERROR))
        onNodeWithTag(ReceiveTags.ERROR).assertIsDisplayed()
        assertEquals(0, fake.nextReceiveCalls.size)
    }

    @Test
    fun `wallet not unlocked renders the error card`() = runDesktopComposeUiTest {
        WalletStore.clear()
        setContent { BhodlTheme { ReceiveScreen() } }
        waitUntilAtLeastOneExists(hasTestTag(ReceiveTags.ERROR))
        onNodeWithTag(ReceiveTags.ERROR).assertIsDisplayed()
        assertTrue(WalletStore.current() == null)
    }
}
