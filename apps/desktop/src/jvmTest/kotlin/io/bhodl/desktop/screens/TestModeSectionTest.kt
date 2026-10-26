package io.bhodl.desktop.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.bhodl.desktop.data.TestMode
import io.bhodl.desktop.data.TrackerCache
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.wizard.RegistryStore
import io.bhodl.ui.theme.BhodlTheme
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Settings › Test mode and the band shown while it is on. */
@OptIn(ExperimentalTestApi::class)
class TestModeSectionTest {

    private lateinit var root: File
    private val savedRoot = TestMode.rootDirProvider
    private val savedProject = TestMode.projectData

    @BeforeTest
    fun setUp() {
        SettingsStore.reset()
        TrackerCache.reset()
        resetTestModeUiForTest()
        root = kotlin.io.path.createTempDirectory("bhodl-test-mode-ui-").toFile()
        TestMode.rootDirProvider = { root }
        TestMode.projectData = { null }
    }

    @AfterTest
    fun tearDown() {
        TestMode.exit()
        TestMode.resetForTest()
        RegistryStore.clear()
        resetTestModeUiForTest()
        TestMode.rootDirProvider = savedRoot
        TestMode.projectData = savedProject
        SettingsStore.reset()
        root.deleteRecursively()
    }

    @Test
    fun `outside test mode the section only offers to enter`() = runDesktopComposeUiTest(width = 900, height = 900) {
        var entered = 0
        setContent { BhodlTheme { TestModeSection(onEnter = { entered++ }, onExit = {}, onReloaded = {}) } }
        onNodeWithTag(TestModeTags.EXPORT).assertDoesNotExist()
        onNodeWithTag(TestModeTags.ENTER).performClick()
        waitUntil(timeoutMillis = 5_000) { entered == 1 }
    }

    @Test
    fun `in test mode the initial state is marked and restored through confirmations`() {
        runBlocking { TestMode.enter() }
        val registry = RegistryStore.require()
        val noon = LocalDate.now(ZoneOffset.UTC).atTime(12, 0).toEpochSecond(ZoneOffset.UTC)
        runDesktopComposeUiTest(width = 900, height = 900) {
            var reloads = 0
            setContent { BhodlTheme { TestModeSection(onEnter = {}, onExit = {}, onReloaded = { reloads++ }) } }
            onNodeWithTag(TestModeTags.ENTER).assertDoesNotExist()

            onNodeWithTag(TestModeTags.MARK_INITIAL).performClick()
            onNodeWithTag(TestModeTags.CONFIRM, useUnmergedTree = true).performClick()
            waitUntil(timeoutMillis = 30_000) { reloads == 1 }

            RegistryStore.require().addManualTxn("out", 5_000uL, null, null, noon, null, "after mark")
            onNodeWithTag(TestModeTags.RESTORE_INITIAL).performClick()
            onNodeWithTag(TestModeTags.CONFIRM, useUnmergedTree = true).performClick()
            waitUntil(timeoutMillis = 30_000) { reloads == 2 }
            onNodeWithTag(TestModeTags.STATUS).assertExists()
            assertFalse(RegistryStore.require().listManualTxns().any { it.note == "after mark" })
        }
        assertTrue(registry !== RegistryStore.require(), "the profile was reopened")
    }

    @Test
    fun `an import picked then cancelled changes nothing`() {
        runBlocking { TestMode.enter() }
        val before = RegistryStore.require().listDemoEvents().size
        runDesktopComposeUiTest(width = 900, height = 900) {
            setContent {
                BhodlTheme {
                    TestModeSection(
                        onEnter = {},
                        onExit = {},
                        onReloaded = {},
                        pickImportFile = { File(root, "missing.bhodl-test") },
                    )
                }
            }
            onNodeWithTag(TestModeTags.IMPORT).performClick()
            onNodeWithTag(TestModeTags.KEEP_DATES, useUnmergedTree = true).assertExists()
            onNodeWithTag(TestModeTags.EXPORT_FIRST, useUnmergedTree = true).assertExists()
            onNodeWithTag(TestModeTags.CANCEL, useUnmergedTree = true).performClick()
            onNodeWithTag(TestModeTags.CONFIRM, useUnmergedTree = true).assertDoesNotExist()
        }
        assertEquals(before, RegistryStore.require().listDemoEvents().size)
    }

    @Test
    fun `in test mode Settings offers no data wipe, outside it does`() {
        runBlocking { TestMode.enter() }
        runDesktopComposeUiTest(width = 1200, height = 1000) {
            setContent { BhodlTheme { SettingsScreen(onBack = {}) } }
            waitUntilAtLeastOneExists(hasTestTag(TestModeTags.EXPORT), timeoutMillis = 5_000)
            onNodeWithTag(SettingsTags.WIPE).assertDoesNotExist()
        }
        TestMode.exit()
        runDesktopComposeUiTest(width = 1200, height = 1000) {
            setContent { BhodlTheme { SettingsScreen(onBack = {}) } }
            waitUntilAtLeastOneExists(hasTestTag(SettingsTags.WIPE), timeoutMillis = 5_000)
        }
    }

    @Test
    fun `the band leads out of test mode`() = runDesktopComposeUiTest(width = 900, height = 200) {
        var exits = 0
        setContent { BhodlTheme { TestModeBand(onExit = { exits++ }) } }
        onNodeWithTag(TestModeTags.BAND).assertExists()
        onNodeWithTag(TestModeTags.BAND_EXIT).performClick()
        assertEquals(1, exits)
    }

    @Test
    fun `restore is off until an initial state exists`() {
        runBlocking { TestMode.enter() }
        // Drop the initial state a fresh profile starts with.
        File(root, "demo-baseline").deleteRecursively()
        runDesktopComposeUiTest(width = 900, height = 900) {
            setContent { BhodlTheme { TestModeSection(onEnter = {}, onExit = {}, onReloaded = {}) } }
            onNodeWithTag(TestModeTags.RESTORE_INITIAL).assertIsNotEnabled()
        }
    }
}
