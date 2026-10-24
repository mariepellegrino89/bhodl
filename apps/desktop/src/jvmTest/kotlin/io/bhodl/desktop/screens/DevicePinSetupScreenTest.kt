package io.bhodl.desktop.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.bhodl.desktop.wizard.RegistryStore
import io.bhodl.ui.theme.BhodlTheme
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Compose UI tests for [`DevicePinSetupScreen`]: an invalid PIN pair is
 * rejected inline ([DevicePinSetupTags.ERROR]) before anything touches
 * the disk — no registry is created and `onCreated` never fires. Every
 * mount targets a temp dir (never `~/.bhodl/`).
 */
@OptIn(ExperimentalTestApi::class)
class DevicePinSetupScreenTest {

    private lateinit var tmp: File
    private lateinit var dataDir: File

    @BeforeTest
    fun setUp() {
        RegistryStore.clear()
        tmp = kotlin.io.path.createTempDirectory("bhodl-pin-setup-").toFile()
        // Not created up-front: the screen must not even mkdir it on a rejected PIN.
        dataDir = File(tmp, "data")
    }

    @AfterTest
    fun tearDown() {
        RegistryStore.clear()
        tmp.deleteRecursively()
    }

    private fun assertNothingOnDisk(created: Int) {
        assertEquals(0, created, "onCreated must not fire")
        assertFalse(dataDir.exists(), "no data dir / registry may be written")
        assertNull(RegistryStore.current())
    }

    @Test
    fun `mismatched confirmation shows an error and touches no disk`() = runDesktopComposeUiTest {
        var created = 0
        setContent {
            BhodlTheme { DevicePinSetupScreen(onCreated = { created++ }, onBack = {}, dataDir = dataDir) }
        }
        waitUntilAtLeastOneExists(hasTestTag(DevicePinSetupTags.PIN))
        onNodeWithTag(DevicePinSetupTags.PIN).performTextInput("48291637")
        onNodeWithTag(DevicePinSetupTags.CONFIRM).performTextInput("48291630")
        onNodeWithTag(DevicePinSetupTags.CREATE).performClick()
        waitUntilAtLeastOneExists(hasTestTag(DevicePinSetupTags.ERROR))
        waitForIdle()
        assertNothingOnDisk(created)
    }

    @Test
    fun `too-short PIN shows an error and touches no disk`() = runDesktopComposeUiTest {
        var created = 0
        setContent {
            BhodlTheme { DevicePinSetupScreen(onCreated = { created++ }, onBack = {}, dataDir = dataDir) }
        }
        waitUntilAtLeastOneExists(hasTestTag(DevicePinSetupTags.PIN))
        onNodeWithTag(DevicePinSetupTags.PIN).performTextInput("1234")
        onNodeWithTag(DevicePinSetupTags.CONFIRM).performTextInput("1234")
        onNodeWithTag(DevicePinSetupTags.CREATE).performClick()
        waitUntilAtLeastOneExists(hasTestTag(DevicePinSetupTags.ERROR))
        waitForIdle()
        assertNothingOnDisk(created)
    }

    @Test
    fun `non-numeric PIN shows an error and touches no disk`() = runDesktopComposeUiTest {
        var created = 0
        setContent {
            BhodlTheme { DevicePinSetupScreen(onCreated = { created++ }, onBack = {}, dataDir = dataDir) }
        }
        waitUntilAtLeastOneExists(hasTestTag(DevicePinSetupTags.PIN))
        onNodeWithTag(DevicePinSetupTags.PIN).performTextInput("abcdefgh")
        onNodeWithTag(DevicePinSetupTags.CONFIRM).performTextInput("abcdefgh")
        onNodeWithTag(DevicePinSetupTags.CREATE).performClick()
        waitUntilAtLeastOneExists(hasTestTag(DevicePinSetupTags.ERROR))
        waitForIdle()
        assertNothingOnDisk(created)
    }

    @Test
    fun `create is disabled until both fields are filled`() = runDesktopComposeUiTest {
        var created = 0
        setContent {
            BhodlTheme { DevicePinSetupScreen(onCreated = { created++ }, onBack = {}, dataDir = dataDir) }
        }
        waitUntilAtLeastOneExists(hasTestTag(DevicePinSetupTags.PIN))
        onNodeWithTag(DevicePinSetupTags.CREATE).assertIsNotEnabled()
        onNodeWithTag(DevicePinSetupTags.PIN).performTextInput("48291637")
        onNodeWithTag(DevicePinSetupTags.CREATE).assertIsNotEnabled()
        onNodeWithTag(DevicePinSetupTags.PIN).performTextClearance()
        onNodeWithTag(DevicePinSetupTags.CONFIRM).performTextInput("48291637")
        onNodeWithTag(DevicePinSetupTags.CREATE).assertIsNotEnabled()
        assertNothingOnDisk(created)
    }
}
