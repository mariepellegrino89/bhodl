package io.bhodl.desktop.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Test-book §12.4–12.10 (BIP-329 tx label save / clear) and §14.4
 * (duplicate address-book entry) through the UI.
 */
@OptIn(ExperimentalTestApi::class)
class LabelsAndContactsUiTest {

    @AfterTest
    fun tearDown() {
        WalletStore.clear()
    }

    @Test
    fun `tx label saves, survives a re-open, and Clear removes it`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort().apply {
            transactions += TransactionRecord(
                txid = TXID,
                netSat = 10_000L,
                feeSat = null,
                confirmationHeight = 100u,
                timestamp = 1_700_000_000uL,
            )
        }
        WalletStore.set(fake)
        val reopen = androidx.compose.runtime.mutableStateOf(0)
        setContent {
            BhodlTheme {
                androidx.compose.runtime.key(reopen.value) {
                    TransactionDetailsScreen(txid = TXID, onBack = {}, openExplorer = {}, copyToClipboard = {})
                }
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(TransactionDetailsTags.LABEL_FIELD))
        onNodeWithTag(TransactionDetailsTags.LABEL_SAVE).assertIsNotEnabled()
        onNodeWithTag(TransactionDetailsTags.LABEL_FIELD).performScrollTo().performTextInput("test label")
        onNodeWithTag(TransactionDetailsTags.LABEL_SAVE).assertIsEnabled().performClick()
        waitUntil(timeoutMillis = 2_000) { fake.labels[TXID] == "test label" }
        // Committed == edited → Save disabled again; Clear offered.
        waitUntilAtLeastOneExists(hasTestTag(TransactionDetailsTags.LABEL_CLEAR))
        onNodeWithTag(TransactionDetailsTags.LABEL_SAVE).assertIsNotEnabled()

        // Re-open the screen: the label is read back from the wallet.
        reopen.value++
        waitUntilAtLeastOneExists(hasText("test label"))
        waitUntilAtLeastOneExists(hasTestTag(TransactionDetailsTags.LABEL_CLEAR))

        onNodeWithTag(TransactionDetailsTags.LABEL_CLEAR).performScrollTo().performClick()
        waitUntil(timeoutMillis = 2_000) { fake.labels[TXID] == null }
        waitUntil(timeoutMillis = 1_000) {
            onAllNodesWithTag(TransactionDetailsTags.LABEL_CLEAR).fetchSemanticsNodes().isEmpty()
        }
        assertNull(fake.getTxLabel(TXID))
    }

    @Test
    fun `adding the same address twice is refused and the list keeps one entry`() =
        runDesktopComposeUiTest {
            val fake = FakeWalletPort()
            WalletStore.set(fake)
            setContent { BhodlTheme { AddressBookScreen(onBack = {}) } }
            waitUntilAtLeastOneExists(hasTestTag(AddressBookTags.SAVE))
            onNodeWithTag(AddressBookTags.LABEL).performTextInput("My faucet test")
            onNodeWithTag(AddressBookTags.ADDRESS).performTextInput(ADDRESS)
            onNodeWithTag(AddressBookTags.SAVE).performClick()
            waitUntil(timeoutMillis = 2_000) { fake.contacts.size == 1 }
            waitUntilAtLeastOneExists(hasText("My faucet test"))

            onNodeWithTag(AddressBookTags.LABEL).performTextInput("Duplicate")
            onNodeWithTag(AddressBookTags.ADDRESS).performTextInput(ADDRESS)
            onNodeWithTag(AddressBookTags.SAVE).performClick()
            waitUntilAtLeastOneExists(hasText("UNIQUE", substring = true), timeoutMillis = 2_000)
            assertEquals(1, fake.contacts.size)
            assertTrue(fake.contacts.none { it.label == "Duplicate" })
        }

    private companion object {
        const val TXID: String = "abcdef12abcdef12abcdef12abcdef12abcdef12abcdef12abcdef12abcdef12"
        const val ADDRESS: String = "tb1pfaucetxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
    }
}
