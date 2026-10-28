package io.bhodl.desktop.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
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
import io.bhodl.ui.theme.BhodlTheme
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compose UI tests for [`AddressBookScreen`] driven by a
 * [`FakeWalletPort`] injected through `WalletStore`.
 *
 * The screen reads / writes contacts via `WalletPort` only; with the
 * fake we exercise add → list → delete without standing up SQLCipher.
 *
 * `WalletStore` is a process singleton, so each test cleans up via
 * `WalletStore.clear()` in `@AfterTest`.
 */
@OptIn(ExperimentalTestApi::class)
class AddressBookScreenTest {

    @AfterTest
    fun teardownStore() {
        WalletStore.clear()
    }

    @Test
    fun `the back arrow at the top leaves the contacts`() = runDesktopComposeUiTest {
        WalletStore.set(FakeWalletPort())
        var back = 0
        setContent { BhodlTheme { AddressBookScreen(onBack = { back++ }) } }
        onNodeWithTag(io.bhodl.desktop.ui.BhodlBackArrowTags.ROOT).performClick()
        assertEquals(1, back)
    }

    @Test
    fun `empty state then add then list then delete`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort()
        WalletStore.set(fake)

        setContent {
            BhodlTheme {
                AddressBookScreen(onBack = {})
            }
        }

        // Wait for the empty-state placeholder to settle (the screen
        // launches a `listContacts()` coroutine on first composition).
        waitUntilAtLeastOneExists(hasText("No contacts yet.", substring = true))

        // Add a contact.
        onNodeWithTag(AddressBookTags.LABEL).performTextInput("Alice")
        onNodeWithTag(AddressBookTags.ADDRESS)
            .performTextInput("tb1palicexxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx")
        onNodeWithTag(AddressBookTags.SAVE).performClick()

        // The reload should pick the new contact up.
        waitUntilAtLeastOneExists(hasText("Alice"))
        onNodeWithText("Alice").assertIsDisplayed()
        assertEquals(1, fake.contacts.size)
        val aliceId = fake.contacts.single().id

        // Delete it; "No contacts yet." re-appears.
        onNodeWithTag(AddressBookTags.deleteFor(aliceId)).performClick()
        waitUntilAtLeastOneExists(hasText("No contacts yet.", substring = true))
        assertTrue(fake.contacts.isEmpty())
    }

    @Test
    fun `empty form rejects with required fields`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort()
        WalletStore.set(fake)

        setContent {
            BhodlTheme {
                AddressBookScreen(onBack = {})
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(AddressBookTags.SAVE))

        // Tap Save without filling either field.
        onNodeWithTag(AddressBookTags.SAVE).performClick()

        onNodeWithText("Both fields are required.").assertIsDisplayed()
        assertTrue(fake.contacts.isEmpty())
    }
}
