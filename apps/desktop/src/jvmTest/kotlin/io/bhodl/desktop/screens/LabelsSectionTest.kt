package io.bhodl.desktop.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.bhodl.desktop.wallet.FakeWalletPort
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.ffi.WalletException
import io.bhodl.ui.theme.BhodlTheme
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compose UI tests for the BIP-329 labels section in Settings.
 *
 * The section bakes JFileChooser into its export / import buttons —
 * that dialog would block the headless Compose test thread, so the
 * tests inject `saveJsonl` / `loadJsonl` lambda stubs through the
 * `internal` overload to drive each path deterministically.
 */
@OptIn(ExperimentalTestApi::class)
class LabelsSectionTest {

    @AfterTest
    fun teardownStore() {
        WalletStore.clear()
    }

    @Test
    fun `Export with no wallet shows wallet-not-unlocked error`() = runDesktopComposeUiTest {
        // Do NOT set WalletStore.
        setContent {
            BhodlTheme {
                LabelsSection(
                    saveJsonl = { _ -> error("dialog must not open without wallet") },
                    loadJsonl = { error("dialog must not open without wallet") },
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(SettingsTags.LABELS_EXPORT))
        onNodeWithTag(SettingsTags.LABELS_EXPORT).performClick()
        waitUntilAtLeastOneExists(hasTestTag(SettingsTags.LABELS_ERROR))
        onNodeWithTag(SettingsTags.LABELS_ERROR).assertIsDisplayed()
    }

    @Test
    fun `Export happy path calls FFI then save dialog and surfaces success`() =
        runDesktopComposeUiTest {
            val fake = FakeWalletPort().apply {
                labels["abcd"] = "Faucet"
                labels["beef"] = "Coffee"
            }
            WalletStore.set(fake)

            var savedPayload: String? = null
            setContent {
                BhodlTheme {
                    LabelsSection(
                        saveJsonl = { jsonl ->
                            savedPayload = jsonl
                            FileSaveResult(path = "/tmp/labels.jsonl", error = null)
                        },
                        loadJsonl = { error("import path is not exercised here") },
                    )
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(SettingsTags.LABELS_EXPORT))
            onNodeWithTag(SettingsTags.LABELS_EXPORT).performClick()
            waitUntilAtLeastOneExists(hasTestTag(SettingsTags.LABELS_STATUS))

            // FFI was hit and produced a JSONL with both rows.
            val payload = assertNotNullValue(savedPayload)
            assertTrue(payload.contains("\"ref\":\"abcd\""))
            assertTrue(payload.contains("\"label\":\"Faucet\""))
            assertTrue(payload.contains("\"ref\":\"beef\""))
            // Status surfaces the path the dialog returned.
            waitUntilAtLeastOneExists(hasText("/tmp/labels.jsonl", substring = true))
        }

    @Test
    fun `Export cancel keeps the section idle (no status no error)`() =
        runDesktopComposeUiTest {
            val fake = FakeWalletPort().apply {
                labels["abcd"] = "Cafe"
            }
            WalletStore.set(fake)

            setContent {
                BhodlTheme {
                    LabelsSection(
                        // User cancelled the Save dialog → returns null.
                        saveJsonl = { null },
                        loadJsonl = { error("import path is not exercised here") },
                    )
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(SettingsTags.LABELS_EXPORT))
            onNodeWithTag(SettingsTags.LABELS_EXPORT).performClick()
            // Give the launched coroutine time to settle.
            waitForIdle()
            waitForIdle()
            // Neither status nor error: the section is back to idle.
            assertEquals(
                0,
                onNodeWithTag(SettingsTags.LABELS_STATUS, useUnmergedTree = true)
                    .let { runCatching { it.assertExists() }.getOrNull()?.let { 1 } ?: 0 },
            )
        }

    @Test
    fun `Import happy path forwards the JSONL to the FFI and reports the count`() =
        runDesktopComposeUiTest {
            val fake = FakeWalletPort().apply {
                importJsonlCount = 7u
            }
            WalletStore.set(fake)

            val jsonl = """
                {"label":"Coffee","ref":"abcd","type":"tx"}
                {"label":"Faucet","ref":"beef","type":"tx"}
            """.trimIndent()

            setContent {
                BhodlTheme {
                    LabelsSection(
                        saveJsonl = { error("export path is not exercised here") },
                        loadJsonl = { FileLoadResult(content = jsonl, error = null) },
                    )
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(SettingsTags.LABELS_IMPORT))
            onNodeWithTag(SettingsTags.LABELS_IMPORT).performClick()
            waitUntil(timeoutMillis = 2_000) { fake.importJsonlCalls.isNotEmpty() }

            assertEquals(jsonl, fake.importJsonlCalls.single())
            waitUntilAtLeastOneExists(hasText("Imported 7", substring = true))
        }

    @Test
    fun `Import surfaces the read error from the file dialog`() = runDesktopComposeUiTest {
        WalletStore.set(FakeWalletPort())
        setContent {
            BhodlTheme {
                LabelsSection(
                    saveJsonl = { error("export path is not exercised here") },
                    loadJsonl = { FileLoadResult(content = null, error = "Load failed: disk gone") },
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(SettingsTags.LABELS_IMPORT))
        onNodeWithTag(SettingsTags.LABELS_IMPORT).performClick()
        waitUntilAtLeastOneExists(hasText("disk gone", substring = true))
    }

    @Test
    fun `FFI export failure surfaces as error`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort().apply {
            failExportLabels = WalletException.Storage(detail = "DB locked")
        }
        WalletStore.set(fake)

        setContent {
            BhodlTheme {
                LabelsSection(
                    saveJsonl = { _ -> error("dialog should not open after FFI failure") },
                    loadJsonl = { error("import path is not exercised here") },
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(SettingsTags.LABELS_EXPORT))
        onNodeWithTag(SettingsTags.LABELS_EXPORT).performClick()
        waitUntilAtLeastOneExists(hasText("DB locked", substring = true))
    }

    private fun <T : Any> assertNotNullValue(v: T?): T {
        if (v == null) error("expected non-null value")
        return v
    }
}
