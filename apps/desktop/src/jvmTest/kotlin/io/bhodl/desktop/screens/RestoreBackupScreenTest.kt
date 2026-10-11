package io.bhodl.desktop.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.bhodl.ffi.Network
import io.bhodl.ui.theme.BhodlTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Compose UI tests for [`RestoreBackupScreen`].
 *
 * The screen takes a `decrypt` lambda for testability — we inject a
 * stub that returns a canned (mnemonic, passphrase) pair without
 * touching the FFI / age scrypt KDF (which would cost ~1 s per call).
 */
@OptIn(ExperimentalTestApi::class)
class RestoreBackupScreenTest {

    @Test
    fun `Decrypt disabled when blob or password is empty`() = runDesktopComposeUiTest {
        setContent {
            BhodlTheme {
                RestoreBackupScreen(
                    onValidated = { _, _, _ -> },
                    onBack = {},
                    decrypt = { _, _ -> stubContents("ignored", "") },
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(RestoreBackupTags.DECRYPT))
        onNodeWithTag(RestoreBackupTags.DECRYPT).assertIsNotEnabled()
        // Blob alone is not enough.
        onNodeWithTag(RestoreBackupTags.BLOB).performTextInput(SAMPLE_BLOB)
        waitForIdle()
        onNodeWithTag(RestoreBackupTags.DECRYPT).assertIsNotEnabled()
    }

    @Test
    fun `valid blob and password forwards mnemonic, passphrase and network`() =
        runDesktopComposeUiTest {
            var observed: Triple<String, String, Network>? = null
            setContent {
                BhodlTheme {
                    RestoreBackupScreen(
                        onValidated = { mnemonic, passphrase, network ->
                            observed = Triple(mnemonic, passphrase, network)
                        },
                        onBack = {},
                        decrypt = { _, _ ->
                            stubContents(VALID_PHRASE, "the-13th-word", Network.REGTEST)
                        },
                    )
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(RestoreBackupTags.BLOB))
            onNodeWithTag(RestoreBackupTags.BLOB).performTextInput(SAMPLE_BLOB)
            onNodeWithTag(RestoreBackupTags.PASSWORD).performTextInput("backup-pw")
            onNodeWithTag(RestoreBackupTags.DECRYPT).performClick()
            waitUntil(timeoutMillis = 2_000) { observed != null }
            val (mnemonic, passphrase, network) = assertNotNull(observed)
            assertEquals(VALID_PHRASE, mnemonic)
            assertEquals("the-13th-word", passphrase)
            assertEquals(Network.REGTEST, network, "network from blob is the source of truth")
        }

    @Test
    fun `decryption failure renders an error and does not navigate`() = runDesktopComposeUiTest {
        var observed: Triple<String, String, Network>? = null
        setContent {
            BhodlTheme {
                RestoreBackupScreen(
                    onValidated = { mnemonic, passphrase, network ->
                        observed = Triple(mnemonic, passphrase, network)
                    },
                    onBack = {},
                    decrypt = { _, _ ->
                        throw RuntimeException("backup decrypt: stub failure")
                    },
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(RestoreBackupTags.BLOB))
        onNodeWithTag(RestoreBackupTags.BLOB).performTextInput(SAMPLE_BLOB)
        onNodeWithTag(RestoreBackupTags.PASSWORD).performTextInput("wrong-pw")
        onNodeWithTag(RestoreBackupTags.DECRYPT).performClick()
        waitUntilAtLeastOneExists(hasTestTag(RestoreBackupTags.ERROR))
        assertEquals(null, observed)
    }

    @Test
    fun `Back fires onBack`() = runDesktopComposeUiTest {
        var backCalled = 0
        setContent {
            BhodlTheme {
                RestoreBackupScreen(
                    onValidated = { _, _, _ -> },
                    onBack = { backCalled++ },
                    decrypt = { _, _ -> stubContents("ignored", "") },
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(RestoreBackupTags.BACK))
        onNodeWithTag(RestoreBackupTags.BACK).performClick()
        waitUntil(timeoutMillis = 1_000) { backCalled == 1 }
        assertEquals(1, backCalled)
    }

    private fun stubContents(
        mnemonic: String,
        passphrase: String,
        network: Network = Network.SIGNET,
    ): RestoreBackupContents = RestoreBackupContents(mnemonic, passphrase, network)

    private companion object {
        const val SAMPLE_BLOB: String =
            "-----BEGIN AGE ENCRYPTED FILE-----\n" +
                "fake-age-payload-doesnt-need-to-parse\n" +
                "-----END AGE ENCRYPTED FILE-----\n"
        const val VALID_PHRASE: String =
            "abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon about"
    }
}
