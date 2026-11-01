package io.bhodl.desktop.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.bhodl.desktop.data.TestMode
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.wizard.localizedText
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.tm_enter_body
import io.bhodl.shared.resources.tm_enter_title
import io.bhodl.ui.theme.BhodlTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Review finding M3 — entering test mode marks a profile as a demo
 * profile irreversibly and a demo profile mixes fabricated balances,
 * events and rates into what it shows. While the open session holds
 * real wallets, "Enter" must stop at the warning dialog first; a
 * wallet-less/demo session (and the Welcome "try" path) enters
 * directly.
 */
@OptIn(ExperimentalTestApi::class)
class TestModeEnterGuardTest {

    @BeforeTest
    fun setUp() {
        SettingsStore.reset()
        resetTestModeUiForTest()
    }

    @AfterTest
    fun tearDown() {
        TestMode.resetForTest()
        resetTestModeUiForTest()
        SettingsStore.reset()
    }

    @Test
    fun `entering from a real profile asks first with the irreversibility warning`() =
        runDesktopComposeUiTest(width = 900, height = 900) {
            var entered = 0
            setContent {
                BhodlTheme {
                    TestModeSection(
                        onEnter = { entered++ },
                        onExit = {},
                        onReloaded = {},
                        hasRealWallets = { true },
                    )
                }
            }
            onNodeWithTag(TestModeTags.ENTER).performClick()
            onNodeWithTag(TestModeTags.ENTER_CONFIRM).assertTextEquals(localizedText(Res.string.tm_enter_body))
            onNodeWithTag(TestModeTags.CONFIRM, useUnmergedTree = true).assertExists()
            assertEquals(0, entered, "the warning must come before entering")
        }

    @Test
    fun `confirming the warning enters, cancelling or dismissing does not`() =
        runDesktopComposeUiTest(width = 900, height = 900) {
            var entered = 0
            setContent {
                BhodlTheme {
                    TestModeSection(
                        onEnter = { entered++ },
                        onExit = {},
                        onReloaded = {},
                        hasRealWallets = { true },
                    )
                }
            }
            onNodeWithTag(TestModeTags.ENTER).performClick()
            onNodeWithTag(TestModeTags.CANCEL, useUnmergedTree = true).performClick()
            onNodeWithTag(TestModeTags.ENTER_CONFIRM).assertDoesNotExist()
            assertEquals(0, entered)

            onNodeWithTag(TestModeTags.ENTER).performClick()
            onNodeWithTag(TestModeTags.CONFIRM, useUnmergedTree = true).performClick()
            waitUntil(timeoutMillis = 5_000) { entered == 1 }
        }

    @Test
    fun `a session without real wallets enters with no dialog`() =
        runDesktopComposeUiTest(width = 900, height = 900) {
            var entered = 0
            setContent {
                BhodlTheme {
                    TestModeSection(
                        onEnter = { entered++ },
                        onExit = {},
                        onReloaded = {},
                        hasRealWallets = { false },
                    )
                }
            }
            onNodeWithTag(TestModeTags.ENTER).performClick()
            waitUntil(timeoutMillis = 5_000) { entered == 1 }
            onNodeWithTag(TestModeTags.ENTER_CONFIRM).assertDoesNotExist()
        }

    @Test
    fun `the warning strings state irreversibility and demo data mixing in both languages`() {
        fun entry(dir: String, key: String) = io.bhodl.desktop.i18n.I18nResources.entries(dir)
            .single { it.key == key }
        val en = entry("values", "tm_enter_body").value
        val itBody = entry("values-it", "tm_enter_body").value
        listOf(en to "IRREVERSIBLE", itBody to "IRREVERSIBILE").forEach { (text, marker) ->
            assertTrue(
                text.contains(marker),
                "tm_enter_body must state that entering is irreversible ($marker): $text",
            )
            assertTrue(
                text.contains("test", ignoreCase = true),
                "tm_enter_body must mention the test data being mixed in: $text",
            )
        }
        assertEquals(
            io.bhodl.desktop.i18n.I18nResources.formatArgs(en),
            io.bhodl.desktop.i18n.I18nResources.formatArgs(itBody),
            "en/it placeholders of tm_enter_body must match",
        )
    }

    @Test
    fun `the warning title matches the resource`() =
        runDesktopComposeUiTest(width = 900, height = 900) {
            setContent {
                BhodlTheme {
                    TestModeSection(
                        onEnter = {},
                        onExit = {},
                        onReloaded = {},
                        hasRealWallets = { true },
                    )
                }
            }
            onNodeWithTag(TestModeTags.ENTER).performClick()
            onNodeWithTag(TestModeTags.ENTER_CONFIRM).assertExists()
            onNodeWithText(localizedText(Res.string.tm_enter_title), useUnmergedTree = true).assertExists()
        }
}
