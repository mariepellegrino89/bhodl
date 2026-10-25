package io.bhodl.desktop.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.bhodl.desktop.domain.RecurringCandidate
import io.bhodl.desktop.domain.RecurringConfidence
import io.bhodl.desktop.domain.RecurringFrequency
import io.bhodl.desktop.settings.SettingsPersistence
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.ffi.PaymentDirection
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.PaymentKind
import io.bhodl.ffi.TrackingBillFfi
import io.bhodl.ffi.TrackingBudgetFfi
import io.bhodl.ffi.TrackingRuleFfi
import io.bhodl.ui.theme.BhodlTheme
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Area C — Compose tests of the tracker cards and dialogs: category /
 * crypto-kind dialog, bills, budgets, rules, recurring, saved views,
 * data-state strip, KPI row, monthly review banner, forecast card.
 */
@OptIn(ExperimentalTestApi::class)
class UcCardsTrackerTest {

    private val now = System.currentTimeMillis() / 1000L

    @BeforeTest
    fun setUp() = SettingsStore.reset()

    @AfterTest
    fun tearDown() {
        SettingsStore.disablePersistence()
        SettingsStore.reset()
    }

    private fun SemanticsNodeInteractionsProvider.count(tag: String) =
        onAllNodes(hasTestTag(tag)).fetchSemanticsNodes(atLeastOneRootRequired = false).size

    private fun SemanticsNodeInteractionsProvider.countText(text: String) =
        onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes(atLeastOneRootRequired = false).size

    private fun ev(category: String? = null, kind: String? = null) = PaymentEvent(
        "w", "t", PaymentKind.ON_CHAIN, PaymentDirection.OUTGOING, 1_000UL, null, now.toULong(), null, category, kind,
    )

    // ─── TRK-033 / TRK-047 / TRK-048 — category + kind dialog ───────

    @Test
    fun `TRK-033 typing narrows the suggestions and a chip fills the input`() = runDesktopComposeUiTest {
        var saved: String? = null
        setContent {
            BhodlTheme {
                CategoryEditDialog(ev(), listOf("Food", "Fuel", "Rent"), onDismiss = {}, onSave = { saved = it })
            }
        }
        waitUntilAtLeastOneExists(hasText("Rent"))
        onNodeWithTag(TrackingTags.CATEGORY_INPUT).performTextInput("fo")
        waitForIdle()
        assertEquals(0, countText("Rent"))
        assertEquals(0, countText("Fuel"))
        onNodeWithText("Food").performClick()
        onNodeWithTag(TrackingTags.CATEGORY_SAVE).performClick()
        assertEquals("Food", saved)
        // No Clear button without an existing category.
        assertEquals(0, count(TrackingTags.CATEGORY_CLEAR))
    }

    @Test
    fun `TRK-033 suggestions are capped at eight and Clear saves an empty category`() = runDesktopComposeUiTest {
        var saved: String? = null
        val known = (1..10).map { "Cat$it" }
        setContent {
            BhodlTheme { CategoryEditDialog(ev(category = "Cat1"), known, onDismiss = {}, onSave = { saved = it }) }
        }
        onNodeWithTag(TrackingTags.CATEGORY_INPUT).performTextClearance()
        waitForIdle()
        assertEquals(1, countText("Cat8"))
        assertEquals(0, countText("Cat9"))
        onNodeWithTag(TrackingTags.CATEGORY_CLEAR).performClick()
        assertEquals("", saved)
    }

    @Test
    fun `TRK-047 each of the nine chips commits its kind immediately`() = runDesktopComposeUiTest {
        val picked = mutableListOf<CryptoEventKind>()
        var saved = false
        setContent {
            BhodlTheme {
                CategoryEditDialog(ev(), emptyList(), onDismiss = {}, onSave = { saved = true }, onCryptoEventKindChange = { picked += it })
            }
        }
        for (k in CryptoEventKind.entries) {
            onNodeWithTag(CryptoEventKindTags.chipTag(k.wireValue)).performClick()
        }
        assertEquals(CryptoEventKind.entries.toList(), picked)
        assertTrue(!saved, "a chip tap never presses Save")
    }

    @Test
    fun `TRK-048 gifts show the tax note`() = runDesktopComposeUiTest {
        setContent { BhodlTheme { CategoryEditDialog(ev(), emptyList(), onDismiss = {}, onSave = {}) } }
        assertEquals(0, countText("Tax treatment varies"))
        onNodeWithTag(CryptoEventKindTags.chipTag("gift_sent")).performClick()
        waitUntilAtLeastOneExists(hasText("Tax treatment varies", substring = true))
    }

    @Test
    fun `TRK-048 no badge for regular, a badge for every other kind`() = runDesktopComposeUiTest {
        setContent {
            BhodlTheme {
                androidx.compose.foundation.layout.Column {
                    CryptoEventKind.entries.forEach { CryptoEventKindBadge(it) }
                }
            }
        }
        waitForIdle()
        assertEquals(0, count(CryptoEventKindTags.badgeTag("regular")))
        for (k in CryptoEventKind.entries.filter { it != CryptoEventKind.REGULAR }) {
            assertEquals(1, count(CryptoEventKindTags.badgeTag(k.wireValue)), k.name)
        }
    }

    // ─── TRK-092 / TRK-093 — bills ──────────────────────────────────

    @Test
    fun `TRK-092 bill dialog needs a name and amount and clamps the due day`() = runDesktopComposeUiTest {
        var saved: List<Any?>? = null
        setContent {
            BhodlTheme {
                BillEditDialog(null, onDismiss = {}, onSave = { n, a, d, l -> saved = listOf(n, a, d, l) }, onDelete = {})
            }
        }
        onNodeWithTag(BillTags.SAVE).assertIsNotEnabled()
        assertEquals(0, count(BillTags.DELETE))
        onNodeWithTag(BillTags.INPUT_AMOUNT).performTextInput("12abc000")
        onNodeWithTag(BillTags.SAVE).assertIsNotEnabled()
        onNodeWithTag(BillTags.INPUT_NAME).performTextInput("  Rent ")
        onNodeWithTag(BillTags.INPUT_DUEDAY).performTextClearance()
        onNodeWithTag(BillTags.INPUT_DUEDAY).performTextInput("456")
        onNodeWithTag(BillTags.SAVE).assertIsEnabled().performClick()
        assertEquals(listOf("Rent", 12_000UL, 31, null), saved)
    }

    @Test
    fun `TRK-092 an empty due day disables Save and edit mode offers Delete`() = runDesktopComposeUiTest {
        var deleted: Long? = null
        val bill = TrackingBillFfi(7, "Rent", 1_000UL, 5u, null, "flat", 0)
        setContent { BhodlTheme { BillEditDialog(bill, onDismiss = {}, onSave = { _, _, _, _ -> }, onDelete = { deleted = it }) } }
        onNodeWithTag(BillTags.INPUT_DUEDAY).performTextClearance()
        onNodeWithTag(BillTags.SAVE).assertIsNotEnabled()
        onNodeWithTag(BillTags.DELETE).performClick()
        assertEquals(7L, deleted)
    }

    @Test
    fun `TRK-099 budget dialog saves only a whole number of sats`() = runDesktopComposeUiTest {
        var saved: ULong? = null
        setContent {
            BhodlTheme { BudgetEditDialog("Food", null, onDismiss = {}, onSave = { saved = it }, onClear = {}) }
        }
        onNodeWithTag(BudgetTags.INPUT).performTextInput("abc")
        onNodeWithTag(BudgetTags.SAVE).assertIsNotEnabled()
        assertEquals(0, count(BudgetTags.CLEAR))
        onNodeWithTag(BudgetTags.INPUT).performTextClearance()
        onNodeWithTag(BudgetTags.INPUT).performTextInput("5000")
        onNodeWithTag(BudgetTags.SAVE).assertIsEnabled().performClick()
        assertEquals(5_000UL, saved)
    }

    @Test
    fun `TRK-099 clear is offered only for an existing budget`() = runDesktopComposeUiTest {
        var cleared = 0
        setContent {
            BhodlTheme { BudgetEditDialog("Food", 1_000UL, onDismiss = {}, onSave = {}, onClear = { cleared++ }) }
        }
        onNodeWithTag(BudgetTags.CLEAR).performClick()
        assertEquals(1, cleared)
    }

    @Test
    fun `TRK-104 the budget chip opens the editor with the stored budget`() = runDesktopComposeUiTest {
        val rows = computeBudgetRows(
            events = listOf(ev(category = "Food")),
            budgets = listOf(TrackingBudgetFfi("Food", 4_000UL, "monthly", 0), TrackingBudgetFfi("Rent", 9_000UL, "monthly", 0)),
            direction = TrackingDirection.EXPENSE,
        )
        val edits = mutableListOf<Pair<String, ULong?>>()
        setContent { BhodlTheme { BudgetBreakdownCard("Spending", rows, onEditBudget = { c, b -> edits += c to b }) } }
        onNodeWithTag(BudgetTags.editChip("Food")).performClick()
        onNodeWithTag(BudgetTags.editChip("Rent")).performClick()
        assertEquals(listOf<Pair<String, ULong?>>("Food" to 4_000UL, "Rent" to 9_000UL), edits)
    }

    // ─── TRK-065 / TRK-067 — rules ──────────────────────────────────

    @Test
    fun `TRK-065 rule dialog requires name, value and category and an integer amount`() = runDesktopComposeUiTest {
        var saved: List<Any?>? = null
        setContent {
            BhodlTheme {
                RuleEditDialog(
                    editing = null, knownCategories = listOf("Food"), onDismiss = {},
                    onSave = { n, f, o, v, c, p, e -> saved = listOf(n, f, o, v, c, p, e) },
                    onDelete = {}, newRulePriority = 42,
                )
            }
        }
        onNodeWithTag(RuleTags.SAVE).assertIsNotEnabled()
        onNodeWithTag(RuleTags.INPUT_NAME).performTextInput("Big")
        onNodeWithTag(RuleTags.INPUT_VALUE).performTextInput("abc")
        onNodeWithTag(RuleTags.INPUT_CATEGORY).performTextInput("Food")
        onNodeWithTag(RuleTags.SAVE).assertIsEnabled()
        // Switching to an amount field realigns the operator and needs a number.
        onNodeWithTag(RuleTags.fieldTag("amount")).performClick()
        onNodeWithTag(RuleTags.SAVE).assertIsNotEnabled()
        onNodeWithTag(RuleTags.INPUT_VALUE).performTextClearance()
        onNodeWithTag(RuleTags.INPUT_VALUE).performTextInput("500")
        onNodeWithTag(RuleTags.SAVE).assertIsEnabled().performClick()
        assertEquals(listOf("Big", "amount", "gt", "500", "Food", 42L, true), saved)
    }

    private fun rule(id: Long, priority: Long) =
        TrackingRuleFfi(id, "R$id", "description", "contains", "x$id", "category", "C", priority, true, id)

    @Test
    fun `TRK-067 move buttons are disabled at the edges and while a reorder is pending`() = runDesktopComposeUiTest {
        val rules = listOf(rule(1, 30), rule(2, 20), rule(3, 10))
        var moveEnabled by androidx.compose.runtime.mutableStateOf(true)
        val moves = mutableListOf<Pair<Long, Int>>()
        setContent {
            BhodlTheme {
                RulesCard(rules, onAdd = {}, onEdit = {}, onReapply = {}, onMove = { r, d -> moves += r.id to d }, moveEnabled = moveEnabled)
            }
        }
        onNodeWithTag(RuleTags.moveUp(1)).assertIsNotEnabled()
        onNodeWithTag(RuleTags.moveDown(3)).assertIsNotEnabled()
        onNodeWithTag(RuleTags.moveDown(1)).assertIsEnabled().performClick()
        onNodeWithTag(RuleTags.moveUp(3)).assertIsEnabled().performClick()
        assertEquals(listOf(1L to 1, 3L to -1), moves)
        moveEnabled = false
        waitForIdle()
        onNodeWithTag(RuleTags.moveUp(2)).assertIsNotEnabled()
        onNodeWithTag(RuleTags.moveDown(2)).assertIsNotEnabled()
    }

    // ─── TRK-081 — recurring card ───────────────────────────────────

    private fun candidate(i: Int, confidence: RecurringConfidence) = RecurringCandidate(
        payeeKey = "k$i", displayName = "Sub $i", sampleEvents = emptyList(),
        frequency = RecurringFrequency.MONTHLY, avgAmountSat = 10_000UL,
        nextEstimatedUnix = now + 86_400L, confidence = confidence,
    )

    @Test
    fun `TRK-081 at most five suggestions plus a counter, with confidence chips`() = runDesktopComposeUiTest {
        val confidences = listOf(RecurringConfidence.HIGH, RecurringConfidence.MEDIUM, RecurringConfidence.LOW)
        val candidates = (1..7).map { candidate(it, confidences[(it - 1) % 3]) }
        setContent { BhodlTheme { RecurringCard(candidates, onAccept = {}, onDismiss = {}) } }
        waitUntilAtLeastOneExists(hasText("+ 2 more", substring = true))
        for (i in 1..5) assertEquals(1, count(RecurringTags.acceptTag("k$i")))
        assertEquals(0, count(RecurringTags.acceptTag("k6")))
        assertEquals(2, countText("HIGH"))
        assertEquals(2, countText("MED"))
        assertEquals(1, countText("LOW"))
    }

    @Test
    fun `TRK-081 five or fewer suggestions show no counter`() = runDesktopComposeUiTest {
        setContent { BhodlTheme { RecurringCard((1..5).map { candidate(it, RecurringConfidence.HIGH) }, onAccept = {}, onDismiss = {}) } }
        waitUntilAtLeastOneExists(hasTestTag(RecurringTags.acceptTag("k5")))
        assertEquals(0, countText("more"))
    }

    // ─── TRK-145 — save view dialog ─────────────────────────────────

    @Test
    fun `TRK-145 a view needs a non-blank name and is saved trimmed`() = runDesktopComposeUiTest {
        var saved: String? = null
        setContent { BhodlTheme { SaveViewDialog(onSave = { saved = it }, onDismiss = {}) } }
        onNodeWithTag(TrackingTags.VIEW_NAME_INPUT).performTextInput("   ")
        waitForIdle()
        assertNull(saved)
        onNode(hasText("Save") and hasClickActionOnly()).assertIsNotEnabled()
        onNodeWithTag(TrackingTags.VIEW_NAME_INPUT).performTextInput("Food 90d ")
        onNode(hasText("Save") and hasClickActionOnly()).assertIsEnabled().performClick()
        assertEquals("Food 90d", saved)
    }

    private fun hasClickActionOnly() = androidx.compose.ui.test.hasClickAction()

    // ─── TRK-151 — data state strip ─────────────────────────────────

    @Test
    fun `TRK-151 stale chip and refresh disabled while refreshing`() = runDesktopComposeUiTest {
        var refreshing by androidx.compose.runtime.mutableStateOf(false)
        var stale by androidx.compose.runtime.mutableStateOf(2)
        var refreshes = 0
        setContent {
            BhodlTheme { DataStateStrip(lastSyncUnix = now - 60, staleCount = stale, totalWatchCount = 5, onRefresh = { refreshes++ }, refreshing = refreshing) }
        }
        waitUntilAtLeastOneExists(hasText("2/5 stale"))
        onNodeWithTag(TrackingTags.REFRESH).performClick()
        assertEquals(1, refreshes)
        refreshing = true
        stale = 0
        waitForIdle()
        assertEquals(0, count(TrackingTags.STALE_CHIP))
        onNodeWithTag(TrackingTags.REFRESH).performClick()
        assertEquals(1, refreshes)
    }

    // ─── TRK-112 / TRK-113 — KPI row and delta labels ───────────────

    private fun kpis(inflow: ULong, outflow: ULong) =
        DashboardKpis(10_000UL, inflow, outflow, inflow.toLong() - outflow.toLong(), 3)

    @Test
    fun `TRK-113 delta labels compare with the previous period`() = runDesktopComposeUiTest {
        val labels = mutableMapOf<String, String>()
        setContent {
            BhodlTheme {
                labels["flat0"] = deltaLabel(0, 0)
                labels["from0"] = deltaLabel(50, 0)
                labels["up"] = deltaLabel(150, 100)
                labels["down"] = deltaLabel(50, 100)
                labels["same"] = deltaLabel(1001, 1000)
                labels["negPrev"] = deltaLabel(-50, -100)
            }
        }
        waitForIdle()
        assertEquals("= vs prev", labels["flat0"])
        assertEquals("↑ vs 0", labels["from0"])
        assertEquals("↑ 50% vs prev", labels["up"])
        assertEquals("↓ 50% vs prev", labels["down"])
        assertEquals("= 0% vs prev", labels["same"])
        assertEquals("↑ 50% vs prev", labels["negPrev"])
    }

    // ─── TRK-126 / TRK-124 — unmounted cards (logic only) ───────────

    @Test
    fun `TRK-126 the dismiss date survives a save - reset - rehydrate round-trip`() {
        val file = File(kotlin.io.path.createTempDirectory("bhodl-uc-settings-").toFile(), "settings.json")
        try {
            SettingsStore.markMonthlyReviewDismissed(1_780_000_000L)
            SettingsPersistence.save(file).getOrThrow()
            SettingsStore.reset()
            assertEquals(0L, SettingsStore.lastMonthlyReviewDismissedUnix.value)
            SettingsPersistence.rehydrate(file).getOrThrow()
            assertEquals(1_780_000_000L, SettingsStore.lastMonthlyReviewDismissedUnix.value)
        } finally {
            file.parentFile.deleteRecursively()
        }
    }

    @Test
    fun `TRK-124 forecast card shows the final balance and nothing for a single point`() = runDesktopComposeUiTest {
        var series by androidx.compose.runtime.mutableStateOf(listOf(now to 1_000L))
        setContent { BhodlTheme { CashFlowForecastCard(series) } }
        waitForIdle()
        assertEquals(0, count(CashFlowForecastCardTags.CARD))
        series = listOf(now to 1_000L, now + 90 * 86_400L to -500L)
        waitUntilAtLeastOneExists(hasTestTag(CashFlowForecastCardTags.CARD))
        assertEquals(1, countText("In 90 days:"))
        assertEquals(1, countText("Δ -150%"))
    }
}
