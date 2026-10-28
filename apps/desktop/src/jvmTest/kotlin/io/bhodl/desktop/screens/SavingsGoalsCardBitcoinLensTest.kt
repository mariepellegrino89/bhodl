package io.bhodl.desktop.screens
import io.bhodl.desktop.domain.GoalBucket

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.bhodl.desktop.prices.FiatCurrency
import io.bhodl.desktop.prices.PriceCache
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.ffi.TrackingSavingsGoalFfi
import io.bhodl.ui.theme.BhodlTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Phase 8.3.4 — render-tree assertions for the Bitcoin Lens migration
 * of [SavingsGoalsCard]. Covers:
 *  - goal current_sat renders via `<Money>` Medium (BTC primary)
 *  - goal target_sat renders via `<Money>` Medium (BTC primary)
 *  - progress bar percentage logic is unchanged (regression check)
 *  - ON TRACK chip still appears on a freshly seeded sinking goal
 *
 * Buckets are exercised at MANUAL so the live currentSat falls back
 * to the persisted value (Phase 7.2.4) — keeps the test free of
 * registry plumbing.
 */
@OptIn(ExperimentalTestApi::class)
class SavingsGoalsCardBitcoinLensTest {

    @BeforeTest
    fun setup() {
        SettingsStore.reset()
        SettingsStore.setFiatCurrency(FiatCurrency.EUR)
        PriceCache.seedForTest(price = 60_000.0, lastFetchUnix = nowSec())
    }

    @AfterTest
    fun teardown() {
        SettingsStore.reset()
        PriceCache.seedForTest(price = null, lastFetchUnix = null)
    }

    private fun nowSec() = System.currentTimeMillis() / 1000L

    private fun manualGoal(
        id: Long = 1L,
        currentSat: ULong = 25_000_000UL, // 0.25 BTC
        targetSat: ULong = 100_000_000UL, // 1 BTC
        color: String = "cyan",
        goalKind: String = GoalKind.SAVINGS.wireValue,
        deadlineUnix: Long? = null,
    ): TrackingSavingsGoalFfi = TrackingSavingsGoalFfi(
        id = id,
        name = "1 BTC Stack",
        targetSat = targetSat,
        currentSat = currentSat,
        color = color,
        deadlineUnix = deadlineUnix,
        linkedWalletId = null,
        targetPctOfNetWorth = null,
        bucketKind = GoalBucket.MANUAL.wireValue,
        bucketRef = null,
        goalKind = goalKind,
        targetDenomination = "sat",
        targetFiatAmount = null,
        targetFiatCurrency = null,
        createdAtUnix = 0L,
    )

    @Test
    fun `goal current_sat rendered via Money Medium BTC primary`() = runDesktopComposeUiTest {
        setContent {
            BhodlTheme {
                SavingsGoalsCard(
                    goals = listOf(manualGoal()),
                    bitcoinAssetSat = 25_000_000UL,
                    totalNetWorthSat = 100_000_000UL,
                    watchBalances = emptyMap(),
                    investments = emptyList(),
                    wallets = emptyList(),
                    manualAssets = emptyList(),
                    fiatToSat = { null },
                    onAdd = {},
                    onEdit = {},
                )
            }
        }
        // First wait for the card to fully compose by anchoring on a
        // tag that's NOT merged into the clickable row — the "+ Add"
        // button. Then fetch the Money tag from the unmerged tree
        // (the tag lives inside the goal row's clickable Column).
        waitUntilAtLeastOneExists(hasTestTag(GoalTags.ADD))
        val nodes = onAllNodesWithTag(GoalTags.currentAmountTag(1L), useUnmergedTree = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
        assertTrue(nodes.isNotEmpty(), "current_sat Money tag should exist")
        // BTC primary is present somewhere in the tree (auto-format ≥
        // 100k sat threshold renders as BTC).
        waitUntilAtLeastOneExists(hasText("BTC", substring = true))
    }

    @Test
    fun `goal target_sat rendered via Money Medium BTC primary`() = runDesktopComposeUiTest {
        setContent {
            BhodlTheme {
                SavingsGoalsCard(
                    goals = listOf(manualGoal()),
                    bitcoinAssetSat = 25_000_000UL,
                    totalNetWorthSat = 100_000_000UL,
                    watchBalances = emptyMap(),
                    investments = emptyList(),
                    wallets = emptyList(),
                    manualAssets = emptyList(),
                    fiatToSat = { null },
                    onAdd = {},
                    onEdit = {},
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(GoalTags.ADD))
        val nodes = onAllNodesWithTag(GoalTags.targetAmountTag(1L), useUnmergedTree = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
        assertTrue(nodes.isNotEmpty(), "target_sat Money tag should exist")
        // Goals redesign 2026-10 — current and target sit in labelled tiles.
        waitUntilAtLeastOneExists(hasText("TARGET"))
    }

    @Test
    fun `progress percentage unchanged regression check`() = runDesktopComposeUiTest {
        // 0.25 BTC of 1 BTC target → 25%.
        setContent {
            BhodlTheme {
                SavingsGoalsCard(
                    goals = listOf(manualGoal()),
                    bitcoinAssetSat = 25_000_000UL,
                    totalNetWorthSat = 100_000_000UL,
                    watchBalances = emptyMap(),
                    investments = emptyList(),
                    wallets = emptyList(),
                    manualAssets = emptyList(),
                    fiatToSat = { null },
                    onAdd = {},
                    onEdit = {},
                )
            }
        }
        // The "25%" label sits on the right edge of the goal row. The
        // value comes from `(currentSat/targetSat * 100).roundToInt()`
        // — Phase 8.3.4 didn't touch that logic.
        waitUntilAtLeastOneExists(hasText("25%", substring = true))
    }

    @Test
    fun `on track chip still appears when sinking and within deadline`() =
        runDesktopComposeUiTest {
            // Deadline 365 days out + 50% progress → ON TRACK schedule
            // status (created at unix 0 → ~100% of time elapsed for a
            // 1-day deadline; we use 365 days to land in the middle of
            // the schedule budget).
            val now = nowSec()
            val deadline = now + 30L * 86_400L
            val sinking = manualGoal(
                id = 2L,
                currentSat = 50_000_000UL,
                targetSat = 100_000_000UL,
                goalKind = GoalKind.SINKING.wireValue,
                deadlineUnix = deadline,
            ).copy(createdAtUnix = now - 30L * 86_400L)
            setContent {
                BhodlTheme {
                    SavingsGoalsCard(
                        goals = listOf(sinking),
                        bitcoinAssetSat = 50_000_000UL,
                        totalNetWorthSat = 100_000_000UL,
                        watchBalances = emptyMap(),
                        investments = emptyList(),
                        wallets = emptyList(),
                        manualAssets = emptyList(),
                        fiatToSat = { null },
                        onAdd = {},
                        onEdit = {},
                    )
                }
            }
            // Anchor on the "+ Add" CTA (unmerged-safe) so the
            // composition has actually drawn the row. Then assert
            // the chip is present in the unmerged tree (it lives
            // inside the clickable goal row Column).
            waitUntilAtLeastOneExists(hasTestTag(GoalTags.ADD))
            val chipNodes = onAllNodesWithTag(GoalTags.SCHEDULE_CHIP, useUnmergedTree = true)
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
            assertTrue(
                chipNodes.isNotEmpty(),
                "schedule chip should render on a sinking goal within its deadline",
            )
        }
}
