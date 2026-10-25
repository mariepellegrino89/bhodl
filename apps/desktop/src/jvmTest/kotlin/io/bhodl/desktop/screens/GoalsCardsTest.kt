package io.bhodl.desktop.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.ffi.TrackingSavingsGoalFfi
import io.bhodl.ui.theme.BhodlTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Goals redesign 2026-10 (proposal A) — counts, the type filter and a
 * dark / light render of the goal cards.
 */
@OptIn(ExperimentalTestApi::class)
class GoalsCardsTest {

    @BeforeTest fun setUp() = SettingsStore.reset()
    @AfterTest fun tearDown() = SettingsStore.reset()

    private val now = System.currentTimeMillis() / 1000L

    private fun goal(
        id: Long,
        name: String,
        target: ULong,
        current: ULong,
        kind: String = "savings",
        deadline: Long? = null,
        pct: UInt? = null,
        color: String = "cyan",
    ) = TrackingSavingsGoalFfi(
        id = id, name = name, targetSat = target, currentSat = current, color = color, deadlineUnix = deadline,
        linkedWalletId = null, targetPctOfNetWorth = pct, bucketKind = "manual", bucketRef = null, goalKind = kind,
        targetDenomination = "sat", targetFiatAmount = null, targetFiatCurrency = null, createdAtUnix = now - 200 * 86_400L,
    )

    private val goals = listOf(
        goal(1, "Fondo emergenza", 30_000_000UL, 21_600_000UL, color = "green"),
        goal(2, "1 BTC", 100_000_000UL, 41_000_000UL, color = "orange"),
        goal(3, "Vacanza Giappone", 6_000_000UL, 3_300_000UL, kind = "sinking", deadline = now + 240 * 86_400L, color = "cyan"),
        goal(4, "Auto nuova", 20_000_000UL, 1_000_000UL, kind = "sinking", deadline = now + 30 * 86_400L, color = "magenta"),
        goal(5, "Moto", 5_000_000UL, 5_000_000UL, color = "purple"),
    )

    @androidx.compose.runtime.Composable
    private fun Card() = SavingsGoalsCard(
        goals = goals, bitcoinAssetSat = 0UL, totalNetWorthSat = 488_000_000UL, watchBalances = emptyMap(),
        investments = emptyList(), wallets = emptyList(), manualAssets = emptyList(), fiatToSat = { null },
        onAdd = {}, onEdit = {},
    )

    @Test
    fun `counts split on track, behind and reached`() {
        val views = goals.map { g ->
            goalView(g, g.currentSat, effectiveTargetSat(g, 488_000_000UL) { null }, 488_000_000UL, now)
        }
        val c = goalCounts(views)
        assertEquals(5, c.total)
        assertEquals(1, c.reached)
        assertEquals(1, c.behind, "the car is far behind its deadline")
        assertEquals(listOf(GoalFilter.SAVINGS, GoalFilter.SAVINGS, GoalFilter.SINKING, GoalFilter.SINKING, GoalFilter.SAVINGS), views.map { it.kind })
    }

    @Test
    fun `the type filter narrows the cards`() = runDesktopComposeUiTest {
        setContent { BhodlTheme { Card() } }
        waitUntilAtLeastOneExists(hasText("5 goals", substring = true))
        assertEquals(5, onAllNodesWithTag(GoalTags.card(1)).fetchSemanticsNodes().size + 4)
        onNodeWithTag(GoalTags.filter(GoalFilter.SINKING)).performClick()
        waitForIdle()
        assertEquals(0, onAllNodesWithTag(GoalTags.card(1)).fetchSemanticsNodes().size)
        assertEquals(1, onAllNodesWithTag(GoalTags.card(3)).fetchSemanticsNodes().size)
    }

    @Test
    fun `render the goals for the visual review`() = runDesktopComposeUiTest(width = 1440, height = 900) {
        for ((dark, name) in listOf(true to "goals-dark", false to "goals-light")) {
            setContent {
                BhodlTheme(useDarkTheme = dark) {
                    Box(Modifier.fillMaxSize().background(BhodlTheme.colors.background).padding(24.dp)) { Card() }
                }
            }
            waitForIdle()
            shot(name)
        }
    }

    private fun SemanticsNodeInteractionsProvider.shot(name: String) {
        val dir = File(System.getProperty("bhodl.screenshots", "build/screenshots")).apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
    }
}
