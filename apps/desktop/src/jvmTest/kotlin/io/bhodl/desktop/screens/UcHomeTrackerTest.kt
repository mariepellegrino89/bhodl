package io.bhodl.desktop.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.bhodl.desktop.data.TrackerCache
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.wizard.RegistryStore
import io.bhodl.ffi.RegistryHandle
import io.bhodl.ui.theme.BhodlTheme
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Area C (section S) — activity parts of the tracker HOME: cold load on
 * mount, stale banner, wallets header actions. Fiat stays NONE, so the
 * hero never reaches the price / net-worth history network paths.
 */
@OptIn(ExperimentalTestApi::class)
class UcHomeTrackerTest {

    private val now = System.currentTimeMillis() / 1000L
    private val defaultDataDir = TrackerCache.dataDirProvider

    @BeforeTest
    fun setUp() {
        SettingsStore.reset()
        RegistryStore.clear()
        TrackerCache.reset()
    }

    @AfterTest
    fun tearDown() {
        TrackerCache.dataDirProvider = defaultDataDir
        RegistryStore.clear()
        SettingsStore.reset()
    }

    private fun home(onAddWatch: () -> Unit = {}) =
        @androidx.compose.runtime.Composable { BhodlTheme { TrackerHomeSection(onOpenWallet = {}, onAddWatch = onAddWatch) } }

    @Test
    fun `TRK-167 mounting the home kicks the cold load`() = runDesktopComposeUiTest {
        val dir = kotlin.io.path.createTempDirectory("bhodl-uc-home-").toFile()
        try {
            TrackerCache.dataDirProvider = { File(dir, "marker") }
            val registry = RegistryHandle.createOrLoad(dir.absolutePath, "482916")
            registry.addBill("Rent", 1_000UL, 5u, null)
            RegistryStore.set(registry)
            setContent(home())
            waitUntil("cold load", timeoutMillis = 15_000) { TrackerCache.hasLoadedOnce.value }
            assertEquals(listOf("Rent"), TrackerCache.bills.value.map { it.name })
            assertEquals(0, onAllNodes(hasText("Loading", substring = true)).fetchSemanticsNodes().size)
        } finally {
            RegistryStore.clear()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `TRK-168 a stale snapshot shows the banner, and its refresh surfaces the cache error`() = runDesktopComposeUiTest {
        TrackerCache.seedForTest(lastSyncUnix = now - 3_600)
        setContent(home())
        waitUntilAtLeastOneExists(hasTestTag(TrackerHomeTags.STALE_BANNER))
        waitUntilAtLeastOneExists(hasText("tap to refresh", substring = true))
        onNodeWithTag(TrackerHomeTags.STALE_BANNER).performClick()
        // The error itself is rendered once by the TrackingScreen shell.
        waitUntil("refresh ran", timeoutMillis = 5_000) { TrackerCache.lastError.value == "Registry not open." }
        assertEquals(0, onAllNodes(hasText("Registry not open.")).fetchSemanticsNodes().size)
    }

    @Test
    fun `TRK-168 a fresh snapshot has no banner`() = runDesktopComposeUiTest {
        TrackerCache.seedForTest(lastSyncUnix = now - 30)
        setContent(home())
        waitForIdle()
        assertEquals(0, onAllNodes(hasTestTag(TrackerHomeTags.STALE_BANNER)).fetchSemanticsNodes().size)
    }

    @Test
    fun `TRK-170 header refresh is disabled while refreshing and Add opens the add-wallet flow`() = runDesktopComposeUiTest {
        var added = 0
        TrackerCache.seedForTest(lastSyncUnix = now, refreshing = true)
        setContent(home(onAddWatch = { added++ }))
        onNodeWithTag(TrackerHomeTags.REFRESH).assertIsNotEnabled()
        onNodeWithTag(TrackerHomeTags.ADD_WATCH).performClick()
        assertEquals(1, added)
        assertNull(TrackerCache.lastError.value)

        TrackerCache.seedForTest(lastSyncUnix = now, refreshing = false)
        waitForIdle()
        onNodeWithTag(TrackerHomeTags.REFRESH).performClick()
        waitUntil("refresh ran", timeoutMillis = 5_000) { TrackerCache.lastError.value == "Registry not open." }
        assertTrue(TrackerCache.lastError.value != null)
    }
}
