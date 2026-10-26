package io.bhodl.desktop.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.bhodl.desktop.wizard.RegistryStore
import io.bhodl.ui.theme.BhodlTheme
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The smallest window the app allows (960×640): the last button of each
 * first-run screen is still reachable by scrolling.
 */
@OptIn(ExperimentalTestApi::class)
class SmallWindowTest {

    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        dir = kotlin.io.path.createTempDirectory("bhodl-small-window-").toFile()
        RegistryStore.clear()
    }

    @AfterTest
    fun tearDown() {
        RegistryStore.clear()
        dir.deleteRecursively()
    }

    @Test
    fun `welcome, PIN setup, add wallet and cleanup keep their last button reachable`() =
        runDesktopComposeUiTest(width = 960, height = 640) {
            val lastButtons = listOf(WelcomeTags.RESET, DevicePinSetupTags.CREATE, AddWatchTags.SUBMIT, LegacyCleanupTags.LATER)
            for ((i, lastButton) in lastButtons.withIndex()) {
                setContent {
                    BhodlTheme {
                        when (i) {
                            0 -> WelcomeScreen(onGetStarted = {}, onResetComplete = {}, onTryTestMode = {}, dataDir = dir)
                            1 -> DevicePinSetupScreen(onCreated = {}, onBack = {})
                            2 -> AddWatchScreen(onAdded = {}, onBack = {})
                            else -> LegacyCleanupScreen(onDone = {}, dataDir = dir)
                        }
                    }
                }
                waitForIdle()
                onNodeWithTag(lastButton).performScrollTo().assertIsDisplayed()
            }
        }
}
