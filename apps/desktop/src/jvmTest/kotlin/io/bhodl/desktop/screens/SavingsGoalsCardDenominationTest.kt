package io.bhodl.desktop.screens
import io.bhodl.desktop.domain.GoalBucket

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.bhodl.desktop.prices.FiatCurrency
import io.bhodl.desktop.prices.PriceCache
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.ffi.TrackingSavingsGoalFfi
import io.bhodl.shared.BtcUnit
import io.bhodl.ui.theme.BhodlTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 8.6 — Compose-level tests for the goal denomination picker
 * and the fiat-target rendering path.
 *
 * Two layers exercised here:
 *
 *  - The pure resolver [effectiveTargetSat] is pinned for the
 *    sat / fiat / missing-rate matrix without spinning up Compose
 *    (tests 5, 6, 7 of the spec). The UI-side renderings of those
 *    paths are already covered by the existing `<Money>` composable
 *    tests + the `BTC_EQUIV_UNAVAILABLE_CHIP` assertion below.
 *  - The `GoalEditDialog` denomination picker — chip presence, toggle
 *    behaviour, and onSave payload — is exercised via
 *    `runDesktopComposeUiTest` (tests 1–4 of the spec).
 */
@OptIn(ExperimentalTestApi::class)
class SavingsGoalsCardDenominationTest {

    @BeforeTest
    fun setup() {
        SettingsStore.reset()
        SettingsStore.setFiatCurrency(FiatCurrency.EUR)
        // Force BTC unit so the dialog's `previewLabel` / "Current
        // progress" line goes through the pure Kotlin `formatBtc`
        // formatter instead of the Rust `formatSat` FFI. The latter
        // crashes with `Invalid memory access` when called from a JVM
        // test context where the JNA `RustBuffer` lifecycle differs
        // from the main app — same workaround used implicitly by
        // every other JVM Compose test (none of them open the dialog).
        SettingsStore.setBtcUnit(BtcUnit.BTC)
        // Seed PriceCache with 1 BTC = 60_000 EUR so the FIAT goals
        // below can compute a deterministic effective sat target.
        PriceCache.seedForTest(price = 60_000.0, lastFetchUnix = nowSec())
    }

    @AfterTest
    fun teardown() {
        SettingsStore.reset()
        PriceCache.seedForTest(price = null, lastFetchUnix = null)
    }

    private fun nowSec() = System.currentTimeMillis() / 1000L

    // ─── Tests 1–4: dialog picker UI ─────────────────────────────────

    @Test
    fun `denomination picker visible in goal edit dialog with 2 chips`() =
        runDesktopComposeUiTest {
            setContent {
                BhodlTheme {
                    GoalEditDialog(
                        editing = null,
                        totalNetWorthSat = 100_000_000UL,
                        bitcoinAssetSat = 25_000_000UL,
                        wallets = emptyList(),
                        watchBalances = emptyMap(),
                        investments = emptyList(),
                        manualAssets = emptyList(),
                        fiatToSat = { null },
                        onDismiss = {},
                        onSave = { _, _, _, _, _, _, _, _, _, _, _, _ -> },
                        onDelete = {},
                    )
                }
            }
            // Both chips render — confirms the picker is in the tree.
            onNodeWithTag(GoalTags.DENOM_SAT).assertIsDisplayed()
            onNodeWithTag(GoalTags.DENOM_FIAT).assertIsDisplayed()
        }

    @Test
    fun `selecting fiat shows fiat amount + currency inputs, hides sat input`() =
        runDesktopComposeUiTest {
            setContent {
                BhodlTheme {
                    GoalEditDialog(
                        editing = null,
                        totalNetWorthSat = 100_000_000UL,
                        bitcoinAssetSat = 25_000_000UL,
                        wallets = emptyList(),
                        watchBalances = emptyMap(),
                        investments = emptyList(),
                        manualAssets = emptyList(),
                        fiatToSat = { null },
                        onDismiss = {},
                        onSave = { _, _, _, _, _, _, _, _, _, _, _, _ -> },
                        onDelete = {},
                    )
                }
            }
            // Click the FIAT chip then assert the fiat inputs appear
            // and the sat target field vanishes.
            onNodeWithTag(GoalTags.DENOM_FIAT).performClick()
            waitUntilAtLeastOneExists(hasTestTag(GoalTags.INPUT_FIAT_AMOUNT))
            onNodeWithTag(GoalTags.INPUT_FIAT_AMOUNT).assertIsDisplayed()
            onNodeWithTag(GoalTags.INPUT_FIAT_CURRENCY).assertIsDisplayed()
            // Sat input must no longer be present.
            val satInputs = onAllNodesWithTag(GoalTags.INPUT_TARGET)
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
            assertTrue(satInputs.isEmpty(), "sat target input should be hidden in FIAT mode")
        }

    @Test
    fun `selecting sat shows sat input, hides fiat inputs`() = runDesktopComposeUiTest {
        setContent {
            BhodlTheme {
                GoalEditDialog(
                    editing = null,
                    totalNetWorthSat = 100_000_000UL,
                    bitcoinAssetSat = 25_000_000UL,
                    wallets = emptyList(),
                    watchBalances = emptyMap(),
                    investments = emptyList(),
                    manualAssets = emptyList(),
                    fiatToSat = { null },
                    onDismiss = {},
                    onSave = { _, _, _, _, _, _, _, _, _, _, _, _ -> },
                    onDelete = {},
                )
            }
        }
        // First flip to FIAT then back to SAT to exercise the toggle's
        // "clear opposite side" branch. Default state is already SAT
        // (new goal); we still need to verify the toggle round-trip.
        onNodeWithTag(GoalTags.DENOM_FIAT).performClick()
        waitUntilAtLeastOneExists(hasTestTag(GoalTags.INPUT_FIAT_AMOUNT))
        onNodeWithTag(GoalTags.DENOM_SAT).performClick()
        waitUntilAtLeastOneExists(hasTestTag(GoalTags.INPUT_TARGET))
        onNodeWithTag(GoalTags.INPUT_TARGET).assertIsDisplayed()
        val fiatInputs = onAllNodesWithTag(GoalTags.INPUT_FIAT_AMOUNT)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
        assertTrue(fiatInputs.isEmpty(), "fiat amount input should be hidden in SAT mode")
    }

    @Test
    fun `goal saved with fiat denomination round-trips through DTO`() =
        runDesktopComposeUiTest {
            var capturedDenom: String? = null
            var capturedFiatAmount: Double? = null
            var capturedFiatCurrency: String? = null
            var capturedTargetSat: ULong? = null
            setContent {
                BhodlTheme {
                    GoalEditDialog(
                        editing = null,
                        totalNetWorthSat = 100_000_000UL,
                        bitcoinAssetSat = 25_000_000UL,
                        wallets = emptyList(),
                        watchBalances = emptyMap(),
                        investments = emptyList(),
                        manualAssets = emptyList(),
                        fiatToSat = { null },
                        onDismiss = {},
                        onSave = { _, targetSat, _, _, _, _, _, _, _,
                                   denomination, fiatAmount, fiatCurrency ->
                            capturedDenom = denomination
                            capturedFiatAmount = fiatAmount
                            capturedFiatCurrency = fiatCurrency
                            capturedTargetSat = targetSat
                        },
                        onDelete = {},
                    )
                }
            }
            // Fill the minimum required fields for a fiat goal: name,
            // denomination=fiat, fiat amount, fiat currency.
            onNodeWithTag(GoalTags.INPUT_NAME).performTextInput("House")
            onNodeWithTag(GoalTags.DENOM_FIAT).performClick()
            waitUntilAtLeastOneExists(hasTestTag(GoalTags.INPUT_FIAT_AMOUNT))
            onNodeWithTag(GoalTags.INPUT_FIAT_AMOUNT).performTextInput("1000")
            // The currency field is prefilled to the active fiat
            // (EUR), so we can save without retyping it.
            onNodeWithTag(GoalTags.SAVE).performClick()
            // Round-trip assertions: denomination is "fiat", amount
            // is 1000.0, currency is "EUR" (uppercased), and the
            // sat-side `target_sat` was zeroed (since the effective
            // target is recomputed live for fiat goals).
            assertEquals("fiat", capturedDenom)
            assertEquals(1000.0, capturedFiatAmount)
            assertEquals("EUR", capturedFiatCurrency)
            assertEquals(0UL, capturedTargetSat)
        }

    // ─── Tests 5, 7: rate-driven effective target (pure) ──────────────

    @Test
    fun `fiat goal renders target via PriceCache rate when available`() {
        // 1 BTC = 60_000 EUR → goal of 1000 EUR → 0.01666… BTC →
        // 1_666_666 sat (truncated, matches the toLong cast in the
        // resolver). Pinning this number here so a refactor to the
        // rate math has to update the test deliberately.
        val goal = fiatGoal(amount = 1000.0, currency = "EUR")
        val eff = effectiveTargetSat(
            goal = goal,
            totalNetWorthSat = 100_000_000UL,
            rateFor = { ccy -> if (ccy.equals("EUR", ignoreCase = true)) 60_000.0 else null },
        )
        assertTrue(eff.available, "EUR rate is cached, target must be available")
        assertEquals(1_666_666UL, eff.satOrZero)
    }

    @Test
    fun `fiat goal renders em-dash when PriceCache missing rate for currency`() =
        runDesktopComposeUiTest {
            // The pure resolver reports available=false when the
            // rateFor callback returns null. We assert both the pure
            // path and the UI chip below to keep the contract end-to-end.
            val goal = fiatGoal(amount = 1000.0, currency = "USD")
            val eff = effectiveTargetSat(
                goal = goal,
                totalNetWorthSat = 100_000_000UL,
                rateFor = { _ -> null }, // dashboard is EUR, cache holds no USD
            )
            assertFalse(eff.available, "USD rate missing → effective target unavailable")
            assertEquals(0UL, eff.satOrZero)

            // UI side: PriceCache.rateFor("USD") returns null because
            // the active fiat is EUR. The row must render the chip.
            setContent {
                BhodlTheme {
                    SavingsGoalsCard(
                        goals = listOf(goal.copy(id = 42L)),
                        bitcoinAssetSat = 0UL,
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
            val chip = onAllNodesWithTag(
                GoalTags.BTC_EQUIV_UNAVAILABLE_CHIP,
                useUnmergedTree = true,
            ).fetchSemanticsNodes(atLeastOneRootRequired = false)
            assertTrue(
                chip.isNotEmpty(),
                "BTC equiv chip should render when fiat-goal currency has no cached rate",
            )
        }

    @Test
    fun `progress bar percentage derives from effective_target_sat for fiat goal`() {
        // 1 BTC = 60_000 EUR, goal target 1000 EUR (≈ 1_666_666 sat),
        // current_sat = 833_333 (≈ €500). 833_333 / 1_666_666 ≈ 50%.
        //
        // The dialog's Compose layer derives the ratio inline from
        // `liveTargetSat = effectiveTargetSat(...).satOrZero` so we
        // assert the underlying numbers — anything else would
        // require pulling apart the rendered text of `"50%"` from
        // the row, which is brittle across rendering tweaks.
        val goal = fiatGoal(amount = 1000.0, currency = "EUR").copy(currentSat = 833_333UL)
        val eff = effectiveTargetSat(
            goal = goal,
            totalNetWorthSat = 100_000_000UL,
            rateFor = { _ -> 60_000.0 },
        )
        val expectedRatio = goal.currentSat.toDouble() / eff.satOrZero.toDouble()
        assertEquals(0.5, expectedRatio, absoluteTolerance = 0.001)
    }

    // ─── Fixtures ────────────────────────────────────────────────────

    private fun fiatGoal(
        amount: Double,
        currency: String,
    ): TrackingSavingsGoalFfi = TrackingSavingsGoalFfi(
        id = 1L,
        name = "House",
        targetSat = 0UL, // ignored for fiat goals
        currentSat = 0UL,
        color = "cyan",
        deadlineUnix = null,
        linkedWalletId = null,
        targetPctOfNetWorth = null,
        bucketKind = GoalBucket.MANUAL.wireValue,
        bucketRef = null,
        goalKind = GoalKind.SAVINGS.wireValue,
        targetDenomination = "fiat",
        targetFiatAmount = amount,
        targetFiatCurrency = currency,
        createdAtUnix = 0L,
    )

    // ─── Misc ────────────────────────────────────────────────────────

    @Test
    fun `effectiveTargetSat falls back to sat target for sat-denominated goal`() {
        // Regression: the new resolver must not silently change
        // behaviour for the existing sat path. Goal with `target_sat
        // = 1 BTC` resolves to 1 BTC regardless of the rate.
        val goal = TrackingSavingsGoalFfi(
            id = 1L, name = "test",
            targetSat = 100_000_000UL,
            currentSat = 0UL, color = "cyan", deadlineUnix = null, linkedWalletId = null,
            targetPctOfNetWorth = null,
            bucketKind = GoalBucket.MANUAL.wireValue, bucketRef = null,
            goalKind = GoalKind.SAVINGS.wireValue,
            targetDenomination = "sat",
            targetFiatAmount = null, targetFiatCurrency = null,
            createdAtUnix = 0L,
        )
        val eff = effectiveTargetSat(
            goal = goal,
            totalNetWorthSat = 200_000_000UL,
            rateFor = { _ -> 60_000.0 },
        )
        assertTrue(eff.available)
        assertEquals(100_000_000UL, eff.satOrZero)
    }

    @Test
    fun `GoalDenomination fromWire is tolerant of null and unknown`() {
        // Downgrade-safety: a UI older than the schema must collapse
        // an unrecognised denomination string to SAT rather than crash.
        assertEquals(GoalDenomination.SAT, GoalDenomination.fromWire(null))
        assertEquals(GoalDenomination.SAT, GoalDenomination.fromWire(""))
        assertEquals(GoalDenomination.SAT, GoalDenomination.fromWire("sat"))
        assertEquals(GoalDenomination.FIAT, GoalDenomination.fromWire("fiat"))
        assertEquals(GoalDenomination.SAT, GoalDenomination.fromWire("future_kind"))
    }

    @Test
    fun `effectiveTargetSat returns unavailable when fiat amount missing`() {
        // Fiat denomination but no amount typed — must not produce a
        // garbage sat figure. The dialog won't allow saving such a
        // record, but a hand-edited DB might.
        val goal = fiatGoal(amount = 0.0, currency = "EUR")
        val eff = effectiveTargetSat(
            goal = goal,
            totalNetWorthSat = 100_000_000UL,
            rateFor = { _ -> 60_000.0 },
        )
        assertFalse(eff.available)
        assertEquals(0UL, eff.satOrZero)
        // Silence the kotlinc compiler about the unused import while
        // we keep assertNull in scope for future tests.
        assertNull(null as? String)
    }
}
