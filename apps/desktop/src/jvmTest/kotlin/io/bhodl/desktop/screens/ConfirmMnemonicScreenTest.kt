package io.bhodl.desktop.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.bhodl.desktop.wizard.MnemonicChallenge
import io.bhodl.ui.theme.BhodlTheme
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Compose UI tests for [`ConfirmMnemonicScreen`].
 *
 * The challenge logic itself is unit-tested in `MnemonicChallengeTest`;
 * here we only exercise the UI wiring: the Confirm button stays
 * disabled until every required word is typed; wrong answers surface
 * the "Wrong: …" helper; the right answers fire `onConfirmed` with
 * the original mnemonic argument verbatim.
 *
 * To make tests deterministic we seed the challenge with a fixed
 * [`Random`], which forces a known set of `positions` (1, 5, 9 for
 * the BIP-86 test mnemonic + count = 3 + seed = 7).
 */
@OptIn(ExperimentalTestApi::class)
class ConfirmMnemonicScreenTest {

    private val mnemonic =
        "abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon about"
    private val words = mnemonic.split(" ")

    @Test
    fun `confirm is disabled until every required word is typed`() =
        runDesktopComposeUiTest {
            val challenge = MnemonicChallenge.create(
                mnemonic = mnemonic,
                count = 3,
                random = Random(7),
            )
            setContent {
                BhodlTheme {
                    ScreenWith(challenge)
                }
            }

            // Disabled before any input.
            onNodeWithText("Confirm").assertIsDisplayed().assertIsNotEnabled()

            // Fill the first n-1 fields; button still disabled.
            for ((i, pos) in challenge.positions.withIndex()) {
                if (i == challenge.positions.lastIndex) break
                onNodeWithTag(ConfirmMnemonicTags.wordField(pos))
                    .performTextInput(words[pos - 1])
            }
            onNodeWithText("Confirm").assertIsNotEnabled()

            // Fill the last field; button enables.
            val last = challenge.positions.last()
            onNodeWithTag(ConfirmMnemonicTags.wordField(last))
                .performTextInput(words[last - 1])
            onNodeWithText("Confirm").assertIsEnabled()
        }

    @Test
    fun `wrong answers surface an error and do not confirm`() =
        runDesktopComposeUiTest {
            val challenge = MnemonicChallenge.create(
                mnemonic = mnemonic,
                count = 3,
                random = Random(7),
            )
            var confirmedWith: String? = null
            setContent {
                BhodlTheme {
                    ConfirmMnemonicScreen(
                        mnemonic = mnemonic,
                        onConfirmed = { confirmedWith = it },
                        onBack = {},
                        challengeOverride = challenge,
                    )
                }
            }

            challenge.positions.forEach { pos ->
                onNodeWithTag(ConfirmMnemonicTags.wordField(pos))
                    .performTextInput("zzz")
            }
            onNodeWithTag(ConfirmMnemonicTags.CONFIRM).performClick()

            // Helper text appears, callback NOT invoked.
            onNodeWithText("Wrong: #${challenge.positions.first()}", substring = true)
                .assertIsDisplayed()
            assertEquals(null, confirmedWith)
        }

    @Test
    fun `correct answers fire onConfirmed with the original mnemonic`() =
        runDesktopComposeUiTest {
            val challenge = MnemonicChallenge.create(
                mnemonic = mnemonic,
                count = 3,
                random = Random(7),
            )
            var confirmedWith: String? = null
            setContent {
                BhodlTheme {
                    ConfirmMnemonicScreen(
                        mnemonic = mnemonic,
                        onConfirmed = { confirmedWith = it },
                        onBack = {},
                        challengeOverride = challenge,
                    )
                }
            }

            challenge.positions.forEach { pos ->
                onNodeWithTag(ConfirmMnemonicTags.wordField(pos))
                    .performTextInput(words[pos - 1])
            }
            onNodeWithTag(ConfirmMnemonicTags.CONFIRM).performClick()

            assertEquals(mnemonic, confirmedWith)
        }

    @androidx.compose.runtime.Composable
    private fun ScreenWith(challenge: MnemonicChallenge) {
        ConfirmMnemonicScreen(
            mnemonic = mnemonic,
            onConfirmed = {},
            onBack = {},
            challengeOverride = challenge,
        )
    }
}
