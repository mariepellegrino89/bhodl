package io.bhodl.desktop.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.waitUntilDoesNotExist
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
 * mount, the to-do card (it replaced the stale banner), wallets header
 * actions. Fiat stays NONE, so the
 * hero never reaches the price / net-worth history network paths.
 */
@OptIn(ExperimentalTestApi::class)
class UcHomeTrackerTest {

    private val now = System.currentTimeMillis() / 1000L
    private val defaultDataDir = TrackerCache.dataDirProvider
    private val defaultClock = homeNowUnix

    @BeforeTest
    fun setUp() {
        SettingsStore.reset()
        RegistryStore.clear()
        TrackerCache.reset()
    }

    @AfterTest
    fun tearDown() {
        homeNowUnix = defaultClock
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
    fun `TRK-168 the home shows no stale banner, the to-do card says all is in order`() = runDesktopComposeUiTest {
        // Home redesign 2026-10 — the sync state lives once in the shell's
        // DataStateStrip; an old snapshot adds no banner here.
        TrackerCache.seedForTest(lastSyncUnix = now - 3_600)
        setContent(home())
        waitUntilAtLeastOneExists(hasTestTag(HomeCockpitTags.TODO))
        assertEquals(0, onAllNodes(hasText("tap to refresh", substring = true)).fetchSemanticsNodes().size)
        onNodeWithTag(HomeCockpitTags.TODO_NONE).assertExists()
        onNodeWithTag(HomeCockpitTags.COMPOSITION).assertExists()
    }

    @Test
    fun `TRK-168 to-do rows open the section that fixes them`() = runDesktopComposeUiTest {
        val event = io.bhodl.ffi.PaymentEvent(
            walletId = "w1", txidOrPaymentId = "t1", kind = io.bhodl.ffi.PaymentKind.ON_CHAIN,
            direction = io.bhodl.ffi.PaymentDirection.OUTGOING, amountSat = 1_000UL, feeSat = null,
            timestampUnix = (now - 3_600).toULong(), blockHeight = null, category = null, cryptoEventKind = null,
        )
        TrackerCache.seedForTest(lastSyncUnix = now, events = listOf(event))
        var tx = 0
        setContent { BhodlTheme { TrackerHomeSection(onOpenWallet = {}, onAddWatch = {}, onOpenTransactions = { tx++ }) } }
        waitUntilAtLeastOneExists(hasTestTag(HomeCockpitTags.todo(HomeTodoKind.UNCATEGORIZED)))
        onNodeWithTag(HomeCockpitTags.todo(HomeTodoKind.UNCATEGORIZED)).performClick()
        assertEquals(1, tx)
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

    private fun spending(at: Long, category: String? = "Spesa") = io.bhodl.ffi.PaymentEvent(
        walletId = "w1", txidOrPaymentId = "t$at", kind = io.bhodl.ffi.PaymentKind.ON_CHAIN,
        direction = io.bhodl.ffi.PaymentDirection.OUTGOING, amountSat = 1_000UL, feeSat = null,
        timestampUnix = at.toULong(), blockHeight = null, category = category, cryptoEventKind = null,
    )

    @Test
    fun `TRK-172 in the first days of the month the review row opens Reports and goes away`() = runDesktopComposeUiTest {
        // 2 October 2026, local noon: inside the review window whatever today is.
        val second = java.time.LocalDate.of(2026, 10, 2).atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toEpochSecond()
        homeNowUnix = { second }
        TrackerCache.seedForTest(lastSyncUnix = second, events = listOf(spending(second - 86_400L)))
        var reports = 0
        setContent { BhodlTheme { TrackerHomeSection(onOpenWallet = {}, onAddWatch = {}, onOpenReports = { reports++ }) } }
        waitUntilAtLeastOneExists(hasTestTag(HomeCockpitTags.todo(HomeTodoKind.REVIEW)), timeoutMillis = 5_000)
        onNodeWithTag(HomeCockpitTags.todo(HomeTodoKind.REVIEW)).performClick()
        assertEquals(1, reports)
        assertEquals(second, SettingsStore.lastMonthlyReviewDismissedUnix.value)
        waitUntilDoesNotExist(hasTestTag(HomeCockpitTags.todo(HomeTodoKind.REVIEW)), timeoutMillis = 3_000)
    }

    @Test
    fun `TRK-173 after the third day there is no review row`() = runDesktopComposeUiTest {
        val tenth = java.time.LocalDate.of(2026, 10, 10).atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toEpochSecond()
        homeNowUnix = { tenth }
        TrackerCache.seedForTest(lastSyncUnix = tenth, events = listOf(spending(tenth - 86_400L)))
        setContent(home())
        waitUntilAtLeastOneExists(hasTestTag(HomeCockpitTags.TODO), timeoutMillis = 5_000)
        onNodeWithTag(HomeCockpitTags.todo(HomeTodoKind.REVIEW)).assertDoesNotExist()
    }

    @Test
    fun `TRK-174 every help mark explains itself, on hover and on keyboard focus`() = runDesktopComposeUiTest(width = 1440, height = 1500) {
        TrackerCache.seedForTest(lastSyncUnix = now, events = listOf(spending(now - 3_600L)))
        setContent(home())
        waitUntilAtLeastOneExists(hasTestTag(HomeCockpitTags.COMPOSITION), timeoutMillis = 5_000)
        val tips = onAllNodesWithTag(io.bhodl.desktop.ui.HELP_TIP_TAG).fetchSemanticsNodes()
        assertTrue(tips.size >= 5, "composition, to-do and the indicators each have a ?")
        val texts = tips.map { it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription) { emptyList() }.joinToString() }
        assertTrue(texts.all { it.length >= 20 }, "each ? says what it measures: $texts")

        // Keyboard: focus moves onto a ? and its explanation shows.
        onAllNodesWithTag(io.bhodl.desktop.ui.HELP_TIP_TAG)[0].requestFocus()
        waitUntilAtLeastOneExists(hasText(texts[0]), timeoutMillis = 3_000)

        // Mouse: hovering another ? shows its own explanation.
        onAllNodesWithTag(io.bhodl.desktop.ui.HELP_TIP_TAG)[1].performMouseInput { moveTo(center) }
        waitUntilAtLeastOneExists(hasText(texts[1]), timeoutMillis = 3_000)
    }
}
