package io.bhodl.desktop.screens

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.bhodl.desktop.navigation.Route
import io.bhodl.desktop.wizard.MnemonicChallenge
import io.bhodl.desktop.wizard.SetupSecrets
import io.bhodl.ui.theme.BhodlTheme
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Test-book §1 / §2 / §40.5–40.6 / audit M10 — wizard rules that guard
 * the seed and the PIN:
 *  - the confirmation challenge asks for 4 words by default;
 *  - PIN setup: non-numeric / short / mismatch messages, the 6–7 digit
 *    strength hint, and the BIP-39 passphrase double entry;
 *  - wizard routes carry an opaque id, never the mnemonic, and the
 *    parked secret is zeroed on wipe.
 */
@OptIn(ExperimentalTestApi::class)
class WizardSecurityTest {

    private val mnemonic =
        "abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon about"

    @AfterTest
    fun tearDown() {
        SetupSecrets.wipeAll()
    }

    // ---- ConfirmMnemonic -------------------------------------------------------

    @Test
    fun `default challenge asks for four distinct words`() = runDesktopComposeUiTest {
        setContent {
            BhodlTheme {
                ConfirmMnemonicScreen(mnemonic = mnemonic, onConfirmed = {}, onBack = {})
            }
        }
        val wordFields = SemanticsMatcher("challenge word field") { node ->
            node.config.getOrElseNullable(SemanticsProperties.TestTag) { null }
                ?.startsWith("confirm-mnemonic.word.") == true
        }
        waitUntilAtLeastOneExists(wordFields)
        val tags = onAllNodes(wordFields).fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.TestTag] }
        assertEquals(MnemonicChallenge.DEFAULT_CHALLENGE_COUNT, tags.size)
        assertEquals(4, tags.toSet().size, "four distinct positions")
        onNodeWithTag(ConfirmMnemonicTags.CONFIRM).assertIsNotEnabled()
    }

    // ---- PinSetup ----------------------------------------------------------------

    private fun androidx.compose.ui.test.ComposeUiTest.showPinSetup() {
        setContent {
            BhodlTheme {
                PinSetupScreen(mnemonic = mnemonic, onSetupComplete = {}, onBack = {})
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(PinSetupTags.PIN))
    }

    @Test
    fun `non numeric PIN is refused with an explicit message`() = runDesktopComposeUiTest {
        showPinSetup()
        onNodeWithTag(PinSetupTags.PIN).performTextInput("abcdef")
        onNodeWithTag(PinSetupTags.CONFIRM_PIN).performTextInput("abcdef")
        onNodeWithText("PIN must contain only digits.").assertExists()
        onNodeWithTag(PinSetupTags.FINISH).assertIsNotEnabled()
    }

    @Test
    fun `six digit PIN is accepted with a strength hint that disappears at eight`() =
        runDesktopComposeUiTest {
            showPinSetup()
            onNodeWithTag(PinSetupTags.PIN).performTextInput("123456")
            onNodeWithTag(PinSetupTags.CONFIRM_PIN).performTextInput("123456")
            waitUntil(timeoutMillis = 1_000) {
                onAllNodesWithTag(PinSetupTags.PIN_STRENGTH_HINT, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithTag(PinSetupTags.FINISH).assertIsEnabled()

            onNodeWithTag(PinSetupTags.PIN).performTextInput("78")
            waitForIdle()
            assertTrue(
                onAllNodesWithTag(PinSetupTags.PIN_STRENGTH_HINT, useUnmergedTree = true)
                    .fetchSemanticsNodes().isEmpty(),
                "8+ digits: no hint",
            )
            // Confirm no longer matches → mismatch, disabled.
            onNodeWithText("PINs do not match.").assertExists()
            onNodeWithTag(PinSetupTags.FINISH).assertIsNotEnabled()
            onNodeWithTag(PinSetupTags.CONFIRM_PIN).performTextInput("78")
            onNodeWithTag(PinSetupTags.FINISH).assertIsEnabled()
        }

    @Test
    fun `mismatched BIP-39 passphrase blocks Finish until both entries agree`() =
        runDesktopComposeUiTest {
            showPinSetup()
            onNodeWithTag(PinSetupTags.PIN).performTextInput("12345678")
            onNodeWithTag(PinSetupTags.CONFIRM_PIN).performTextInput("12345678")
            onNodeWithTag(PinSetupTags.FINISH).assertIsEnabled()

            onNodeWithTag(PinSetupTags.ADVANCED_TOGGLE).performClick()
            waitUntilAtLeastOneExists(hasTestTag(PinSetupTags.PASSPHRASE))
            onNodeWithTag(PinSetupTags.PASSPHRASE).performTextInput("correct horse")
            onNodeWithTag(PinSetupTags.PASSPHRASE_CONFIRM).performTextInput("correct hose")
            onNodeWithTag(PinSetupTags.FINISH).assertIsNotEnabled()

            // Collapsing Advanced must not hide the problem.
            onNodeWithTag(PinSetupTags.ADVANCED_TOGGLE).performClick()
            waitUntilAtLeastOneExists(hasText("passphrases do not match", substring = true))
            onNodeWithTag(PinSetupTags.FINISH).assertIsNotEnabled()

            onNodeWithTag(PinSetupTags.ADVANCED_TOGGLE).performClick()
            waitUntilAtLeastOneExists(hasTestTag(PinSetupTags.PASSPHRASE_CONFIRM))
            onNodeWithTag(PinSetupTags.PASSPHRASE_CONFIRM).performTextClearance()
            onNodeWithTag(PinSetupTags.PASSPHRASE_CONFIRM).performTextInput("correct horse")
            onNodeWithTag(PinSetupTags.FINISH).assertIsEnabled()
        }

    @Test
    fun `a passphrase recovered from a backup arrives pre-confirmed with Advanced open`() =
        runDesktopComposeUiTest {
            setContent {
                BhodlTheme {
                    PinSetupScreen(
                        mnemonic = mnemonic,
                        onSetupComplete = {},
                        onBack = {},
                        initialPassphrase = "from-backup",
                        isRestore = true,
                    )
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(PinSetupTags.PASSPHRASE))
            onNodeWithTag(PinSetupTags.PIN).performTextInput("12345678")
            onNodeWithTag(PinSetupTags.CONFIRM_PIN).performTextInput("12345678")
            onNodeWithTag(PinSetupTags.FINISH).assertIsEnabled()
        }

    @Test
    fun `PIN fields are focused on entry so typing starts immediately`() = runDesktopComposeUiTest {
        setContent {
            BhodlTheme {
                androidx.compose.foundation.layout.Column {
                    PinSetupScreen(mnemonic = mnemonic, onSetupComplete = {}, onBack = {})
                }
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(PinSetupTags.PIN))
        waitForIdle()
        onNodeWithTag(PinSetupTags.PIN).assertIsFocused()
    }

    @Test
    fun `Unlock PIN field is focused on entry`() = runDesktopComposeUiTest {
        setContent {
            BhodlTheme {
                UnlockScreen(
                    onUnlocked = {},
                    onBack = {},
                    factory = io.bhodl.desktop.wallet.FakeWalletFactory(),
                    dbDirOverride = "/tmp/bhodl-focus-test",
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(UnlockTags.PIN))
        waitForIdle()
        onNodeWithTag(UnlockTags.PIN).assertIsFocused()
    }

    // ---- Seed never in navigation arguments ----------------------------------------

    @Test
    fun `wizard routes serialise only an opaque id, never the mnemonic`() {
        val id = SetupSecrets.put(mnemonic, "secret-passphrase")
        val encoded = listOf(
            Json.encodeToString(Route.ConfirmMnemonic.serializer(), Route.ConfirmMnemonic(secretId = id)),
            Json.encodeToString(Route.PinSetup.serializer(), Route.PinSetup(secretId = id, isRestore = true)),
        )
        for (json in encoded) {
            assertFalse("abandon" in json, "mnemonic leaked into route: $json")
            assertFalse("secret-passphrase" in json, "passphrase leaked into route: $json")
            assertTrue(id in json)
        }
        // No route class declares a field that could carry the seed.
        for (cls in listOf(Route.ConfirmMnemonic::class.java, Route.PinSetup::class.java)) {
            val names = cls.declaredFields.map { it.name.lowercase() }
            assertTrue(names.none { "mnemonic" in it || "passphrase" in it || "seed" in it }, "$cls: $names")
        }
        assertEquals(mnemonic, SetupSecrets.mnemonic(id))
        assertEquals("secret-passphrase", SetupSecrets.passphrase(id))

        SetupSecrets.wipe(id)
        assertNull(SetupSecrets.mnemonic(id))
        assertEquals("", SetupSecrets.passphrase(id))
        assertEquals(0, SetupSecrets.size())
    }

    @Test
    fun `secret ids are random and unguessable`() {
        val ids = List(50) { SetupSecrets.put(mnemonic) }
        assertEquals(50, ids.toSet().size)
        ids.forEach { assertEquals(32, it.length, "128-bit hex id") }
        SetupSecrets.wipeAll()
        ids.forEach { assertNull(SetupSecrets.mnemonic(it)) }
    }
}
