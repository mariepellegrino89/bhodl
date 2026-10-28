package io.bhodl.desktop.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.bhodl.desktop.data.TrackerCache
import io.bhodl.desktop.navigation.TrackerSection
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.wizard.RegistryStore
import io.bhodl.ui.theme.BhodlTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** SEC-068 — "Lock device" in the tracker header runs the lock action. */
@OptIn(ExperimentalTestApi::class)
class UcTrackerHeaderSecurityTest {

    @BeforeTest
    fun setUp() {
        RegistryStore.clear()
        TrackerCache.reset()
        SettingsStore.reset()
    }

    @AfterTest
    fun tearDown() {
        TrackerCache.reset()
        SettingsStore.reset()
    }

    @Test
    fun `SEC-068 the tracker header Lock device fires onLockDevice`() = runDesktopComposeUiTest {
        var locks = 0
        var modules = 0
        setContent {
            BhodlTheme {
                TrackingScreen(
                    section = TrackerSection.HOME,
                    onLockDevice = { locks++ },
                    onOpenModules = { modules++ },
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(TrackingTags.HEADER_LOCK))
        onNodeWithTag(TrackingTags.HEADER_LOCK).performClick()
        assertEquals(1, locks)
        assertEquals(0, modules)
    }
}
