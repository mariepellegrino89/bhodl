package io.bhodl.desktop.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.bhodl.ui.theme.BhodlTheme
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compose UI tests for [`WelcomeScreen`].
 *
 * The screen is intentionally state-light: it owns no FFI dependency,
 * so we can mount it directly in `runDesktopComposeUiTest` without
 * faking `WalletStore`. The typed-DELETE reset path is covered by
 * `SecretActionsUiTest`; here every mount still targets a temp dir so a
 * stray click can never touch `~/.bhodl/`.
 */
@OptIn(ExperimentalTestApi::class)
class WelcomeScreenTest {

    private lateinit var tmp: File

    @BeforeTest
    fun setUp() {
        tmp = kotlin.io.path.createTempDirectory("bhodl-welcome-").toFile()
    }

    @AfterTest
    fun tearDown() {
        tmp.deleteRecursively()
    }

    @Test
    fun `tap get started invokes the callback and nothing else`() = runDesktopComposeUiTest {
        var started = 0
        var resetComplete = 0
        setContent {
            BhodlTheme {
                WelcomeScreen(
                    onGetStarted = { started++ },
                    onResetComplete = { resetComplete++ },
                    dataDir = tmp,
                )
            }
        }

        onNodeWithTag(WelcomeTags.GET_STARTED).assertIsDisplayed().performClick()
        waitForIdle()

        assertEquals(1, started, "Get started should fire exactly once")
        assertEquals(0, resetComplete, "Reset callback must not be triggered")
    }

    @Test
    fun `reset entry is offered but does not wipe without confirmation`() = runDesktopComposeUiTest {
        val marker = File(tmp, "registry.sqlite").apply { writeText("x") }
        var resetComplete = 0
        setContent {
            BhodlTheme {
                WelcomeScreen(onGetStarted = {}, onResetComplete = { resetComplete++ }, dataDir = tmp)
            }
        }
        onNodeWithTag(WelcomeTags.RESET).assertExists()
        assertEquals(0, onAllNodes(hasTestTag(ConfirmWipeTags.FIELD)).fetchSemanticsNodes().size)
        assertEquals(0, resetComplete)
        assertTrue(marker.exists())
    }
}
