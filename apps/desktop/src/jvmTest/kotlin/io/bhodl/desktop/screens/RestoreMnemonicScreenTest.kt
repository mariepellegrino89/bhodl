package io.bhodl.desktop.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.bhodl.desktop.wallet.FakeWalletFactory
import io.bhodl.ffi.Network
import io.bhodl.ffi.WalletException
import io.bhodl.ui.theme.BhodlTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compose UI tests for [`RestoreMnemonicScreen`].
 *
 * The screen has three navigation outcomes worth covering:
 * - empty input → Continue button is disabled.
 * - input with the wrong word count → screen rejects locally
 *   without ever calling the factory.
 * - valid 12-word input → factory.validateMnemonic gets called and
 *   onValidated fires with the normalised phrase.
 * - valid word count but factory rejects (bad checksum) →
 *   onValidated does NOT fire and the screen stays.
 */
@OptIn(ExperimentalTestApi::class)
class RestoreMnemonicScreenTest {

    @Test
    fun `Continue is disabled when phrase is empty`() = runDesktopComposeUiTest {
        setContent {
            BhodlTheme {
                RestoreMnemonicScreen(
                    onValidated = { _, _ -> },
                    onBack = {},
                    factory = FakeWalletFactory(),
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(RestoreMnemonicTags.CONTINUE))
        onNodeWithTag(RestoreMnemonicTags.CONTINUE).assertIsNotEnabled()
    }

    @Test
    fun `wrong word count is rejected locally without calling factory`() = runDesktopComposeUiTest {
        val factory = FakeWalletFactory()
        var validatedCalled = 0
        setContent {
            BhodlTheme {
                RestoreMnemonicScreen(
                    onValidated = { _, _ -> validatedCalled++ },
                    onBack = {},
                    factory = factory,
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(RestoreMnemonicTags.PHRASE))

        // 5 words is not in {12,15,18,21,24} — the screen rejects
        // immediately without ever reaching the factory.
        onNodeWithTag(RestoreMnemonicTags.PHRASE).performTextInput("one two three four five")
        onNodeWithTag(RestoreMnemonicTags.CONTINUE).performClick()
        waitForIdle()

        assertEquals(0, validatedCalled)
        assertTrue(factory.validateCalls.isEmpty(), "factory must not have been called")
    }

    @Test
    fun `valid 12-word phrase calls factory and onValidated`() = runDesktopComposeUiTest {
        val factory = FakeWalletFactory()
        var observedPhrase: String? = null
        setContent {
            BhodlTheme {
                RestoreMnemonicScreen(
                    onValidated = { phrase, _ -> observedPhrase = phrase },
                    onBack = {},
                    factory = factory,
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(RestoreMnemonicTags.PHRASE))

        // BIP39 test vector — 12 words, valid checksum (the "all
        // zeros" entropy).
        onNodeWithTag(RestoreMnemonicTags.PHRASE).performTextInput(VALID_PHRASE)
        onNodeWithTag(RestoreMnemonicTags.CONTINUE).performClick()

        waitUntil(timeoutMillis = 2_000) { observedPhrase != null }
        assertEquals(VALID_PHRASE, observedPhrase)

        assertEquals(1, factory.validateCalls.size)
        assertEquals(Network.SIGNET, factory.validateCalls.single().network)
        assertEquals(VALID_PHRASE, factory.validateCalls.single().mnemonic)
        assertEquals("", factory.validateCalls.single().passphrase)
    }

    @Test
    fun `factory rejection blocks onValidated`() = runDesktopComposeUiTest {
        val factory = FakeWalletFactory(
            failValidate = WalletException.InvalidMnemonic("bad checksum"),
        )
        var validatedCalled = 0
        setContent {
            BhodlTheme {
                RestoreMnemonicScreen(
                    onValidated = { _, _ -> validatedCalled++ },
                    onBack = {},
                    factory = factory,
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(RestoreMnemonicTags.PHRASE))

        onNodeWithTag(RestoreMnemonicTags.PHRASE).performTextInput(VALID_PHRASE)
        onNodeWithTag(RestoreMnemonicTags.CONTINUE).performClick()

        // Wait for the factory call to land, then a couple of frames
        // for the UI to settle. onValidated must NOT have fired.
        waitUntil(timeoutMillis = 2_000) { factory.validateCalls.isNotEmpty() }
        waitForIdle()
        waitForIdle()
        assertEquals(0, validatedCalled)
    }

    @Test
    fun `typing dom shows the BIP-39 suggestion chip for domain`() = runDesktopComposeUiTest {
        setContent {
            BhodlTheme {
                RestoreMnemonicScreen(
                    onValidated = { _, _ -> },
                    onBack = {},
                    factory = FakeWalletFactory(),
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(RestoreMnemonicTags.PHRASE))

        onNodeWithTag(RestoreMnemonicTags.PHRASE).performTextInput("dom")
        // The wordlist loads from a Compose resource asynchronously
        // — give it a generous window before asserting.
        waitUntilAtLeastOneExists(
            hasTestTag(RestoreMnemonicTags.suggestionChip("domain")),
            timeoutMillis = 3_000,
        )
    }

    @Test
    fun `tapping a suggestion chip replaces the in-flight word`() = runDesktopComposeUiTest {
        setContent {
            BhodlTheme {
                RestoreMnemonicScreen(
                    onValidated = { _, _ -> },
                    onBack = {},
                    factory = FakeWalletFactory(),
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(RestoreMnemonicTags.PHRASE))
        onNodeWithTag(RestoreMnemonicTags.PHRASE).performTextInput("dom")
        waitUntilAtLeastOneExists(
            hasTestTag(RestoreMnemonicTags.suggestionChip("domain")),
            timeoutMillis = 3_000,
        )
        onNodeWithTag(RestoreMnemonicTags.suggestionChip("domain")).performClick()
        // The suggestion expands "dom" to "domain " (with trailing
        // space). The word-count text in the screen reads "1 word"
        // at that point, since the trailing space ends the word.
        waitForIdle()
        // Sanity: the chip for "dom" itself is gone (the row is now
        // hidden because the trailing space means no in-flight word).
        waitUntil(timeoutMillis = 1_000) {
            onAllNodesWithTag(RestoreMnemonicTags.suggestionChip("domain"))
                .fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun `Back fires onBack`() = runDesktopComposeUiTest {
        val factory = FakeWalletFactory()
        var backCalled = 0
        setContent {
            BhodlTheme {
                RestoreMnemonicScreen(
                    onValidated = { _, _ -> },
                    onBack = { backCalled++ },
                    factory = factory,
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(RestoreMnemonicTags.BACK))
        onNodeWithTag(RestoreMnemonicTags.BACK).performClick()
        waitUntil(timeoutMillis = 1_000) { backCalled == 1 }
        assertEquals(1, backCalled)
    }

    private companion object {
        const val VALID_PHRASE: String =
            "abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon about"
    }
}
