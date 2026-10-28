package io.bhodl.desktop.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.ui.theme.BhodlTheme
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/** Settings redesign 2026-10 (proposal A) — group index, cards, render. */
@OptIn(ExperimentalTestApi::class)
class SettingsGroupsTest {

    private lateinit var tmp: File

    @BeforeTest fun setUp() { SettingsStore.reset(); ScreenshotCheck.pinClock(); tmp = kotlin.io.path.createTempDirectory("bhodl-settings-groups-").toFile() }
    @AfterTest fun tearDown() { ScreenshotCheck.releaseClock(); SettingsStore.reset(); tmp.deleteRecursively() }

    @Test
    fun `every group has a card and the index scrolls to it`() = runDesktopComposeUiTest(width = 1440, height = 900) {
        setContent { BhodlTheme { SettingsScreen(onBack = {}, onOpenSecurityHub = {}, dataDir = tmp) } }
        waitUntilAtLeastOneExists(hasTestTag(SettingsTags.group(SettingsGroup.GENERAL)))
        SettingsGroup.entries.forEach { onNodeWithTag(SettingsTags.groupCard(it)).assertExists() }
        val before = onNodeWithTag(SettingsTags.groupCard(SettingsGroup.DATA)).fetchSemanticsNode().positionInRoot.y
        onNodeWithTag(SettingsTags.group(SettingsGroup.DATA)).performClick()
        waitUntil(timeoutMillis = 3_000) {
            onNodeWithTag(SettingsTags.groupCard(SettingsGroup.DATA)).fetchSemanticsNode().positionInRoot.y < before
        }
        assertTrue(onNodeWithTag(SettingsTags.groupCard(SettingsGroup.DATA)).fetchSemanticsNode().positionInRoot.y < before)
    }

    @Test
    fun `render settings for the visual review`() = runDesktopComposeUiTest(width = 1440, height = 1000) {
        for ((dark, name) in listOf(true to "settings-dark", false to "settings-light")) {
            setContent { BhodlTheme(useDarkTheme = dark) { SettingsScreen(onBack = {}, onOpenSecurityHub = {}, dataDir = tmp) } }
            waitUntilAtLeastOneExists(hasTestTag(SettingsTags.groupCard(SettingsGroup.APPEARANCE)))
            waitForIdle()
            screenshot(name)
        }
    }

    @Test
    fun `render settings in a narrow window`() = runDesktopComposeUiTest(width = 900, height = 1000) {
        setContent { BhodlTheme(useDarkTheme = true) { SettingsScreen(onBack = {}, onOpenSecurityHub = {}, dataDir = tmp) } }
        waitUntilAtLeastOneExists(hasTestTag(SettingsTags.groupCard(SettingsGroup.APPEARANCE)))
        waitForIdle()
        screenshot("settings-narrow-dark")
    }
}
