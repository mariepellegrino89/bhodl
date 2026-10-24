package io.bhodl.desktop.screens

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.bhodl.desktop.data.TrackerCache
import io.bhodl.desktop.navigation.TrackerSection
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.wizard.RegistryStore
import io.bhodl.ffi.Network
import io.bhodl.ffi.PaymentDirection
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.PaymentKind
import io.bhodl.ffi.RegistryHandle
import io.bhodl.ffi.WalletRecordFfi
import io.bhodl.ui.theme.BhodlTheme
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Area C — the tracker sections mounted over a REAL registry: every
 * dialog / card action is checked on what it writes to the registry
 * (rules, bills, budgets, recurring, categories, rule-from-edit).
 */
@OptIn(ExperimentalTestApi::class)
class UcRegistryWiringTrackerTest {

    private lateinit var dir: File
    private lateinit var registry: RegistryHandle
    private lateinit var wallet: WalletRecordFfi
    private val defaultSource = TrackerCache.watchEventsSource
    private val defaultDataDir = TrackerCache.dataDirProvider
    private var legs: List<PaymentEvent> = emptyList()
    private val now = System.currentTimeMillis() / 1000L

    @BeforeTest
    fun setUp() {
        SettingsStore.reset()
        TrackerCache.reset()
        TrackerUiState.clearFilters()
        TrackerUiState.period.value = TrackingPeriod.Y1
        dir = kotlin.io.path.createTempDirectory("bhodl-uc-wiring-").toFile()
        registry = RegistryHandle.createOrLoad(dir.absolutePath, "482916")
        RegistryStore.set(registry)
        wallet = registry.addWatchAddresses("Exchange", listOf(SIGNET_A), Network.SIGNET)
        TrackerCache.dataDirProvider = { File(dir, "marker") }
        TrackerCache.watchEventsSource = { reg ->
            legs.map {
                it.copy(
                    category = reg.getEventCategory(it.walletId, it.txidOrPaymentId),
                    cryptoEventKind = reg.getEventCryptoKind(it.walletId, it.txidOrPaymentId),
                )
            }
        }
    }

    @AfterTest
    fun tearDown() {
        TrackerCache.watchEventsSource = defaultSource
        TrackerCache.dataDirProvider = defaultDataDir
        TrackerUiState.clearFilters()
        TrackerUiState.period.value = TrackingPeriod.D30
        RegistryStore.clear()
        SettingsStore.reset()
        dir.deleteRecursively()
    }

    private fun leg(txid: String, amount: ULong, ts: Long, direction: PaymentDirection = PaymentDirection.OUTGOING) =
        PaymentEvent(wallet.id, txid, PaymentKind.ON_CHAIN, direction, amount, null, ts.toULong(), null, null, null)

    private fun refresh() = runBlocking { TrackerCache.refresh(syncEsplora = false) }

    private fun ComposeUiTest.until(what: String, cond: () -> Boolean) =
        waitUntil(what, timeoutMillis = 10_000) { cond() }

    private fun ComposeUiTest.budget() {
        setContent { BhodlTheme { TrackingScreen(section = TrackerSection.BUDGET) } }
        waitForIdle()
    }

    // ─── TRK-105 — budget section composition ───────────────────────

    @Test
    fun `TRK-105 budget section shows budgets, recurring, bills, lollipop and rules`() = runDesktopComposeUiTest {
        legs = (1..4).map { leg("gym$it", 20_000UL, now - (5 - it) * 30L * 86_400L) }
        for (i in 1..4) registry.setEventCategory(wallet.id, "gym$i", "Gym")
        registry.setBudget("Gym", 30_000UL, "monthly")
        registry.addBill("Rent", 1_000UL, 5u, null)
        refresh()
        budget()
        waitUntilAtLeastOneExists(hasText("Gym", substring = true))
        assertTrue(onAllNodesWithTag(BudgetTags.editChip("Gym")).fetchSemanticsNodes().isNotEmpty())
        assertTrue(onAllNodesWithTag(RecurringTags.acceptTag(TrackerCache.recurringCandidates.value.single().payeeKey)).fetchSemanticsNodes().isNotEmpty())
        assertTrue(onAllNodesWithTag(BillTags.ADD).fetchSemanticsNodes().isNotEmpty())
        assertTrue(onAllNodesWithTag(RuleTags.ADD).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `TRK-105 without events the budget tables are hidden but bills and rules stay`() = runDesktopComposeUiTest {
        refresh()
        budget()
        waitUntilAtLeastOneExists(hasTestTag(BillTags.ADD))
        assertTrue(onAllNodesWithTag(RuleTags.ADD).fetchSemanticsNodes().isNotEmpty())
        assertEquals(0, onAllNodesWithTag(BudgetTags.SATS_NOTE).fetchSemanticsNodes().size)
        assertTrue(TrackerCache.recurringCandidates.value.isEmpty())
    }

    // ─── TRK-066 / TRK-068 / TRK-064 — rules ────────────────────────

    @Test
    fun `TRK-066 a rule saved from the dialog goes on top and can be deleted`() = runDesktopComposeUiTest {
        registry.addRule("Old", "description", "contains", "x", "category", "C", 4, true)
        refresh()
        budget()
        onNodeWithTag(RuleTags.ADD).performScrollTo().performClick()
        onNodeWithTag(RuleTags.INPUT_NAME).performTextInput("Exchange buys")
        onNodeWithTag(RuleTags.fieldTag("payee")).performClick()
        onNodeWithTag(RuleTags.INPUT_VALUE).performTextInput("Exchange")
        onNodeWithTag(RuleTags.INPUT_CATEGORY).performTextInput("DCA")
        onNodeWithTag(RuleTags.SAVE).performClick()
        until("rule written") { registry.listRules().size == 2 }
        val added = registry.listRules().first { it.name == "Exchange buys" }
        assertEquals(5L, added.priority)
        assertEquals("payee" to "DCA", added.conditionField to added.actionValue)

        refresh()
        onNodeWithTag(RuleTags.row(added.id)).performScrollTo().performClick()
        onNodeWithTag(RuleTags.DELETE).performClick()
        until("rule deleted") { registry.listRules().none { it.id == added.id } }
    }

    @Test
    fun `TRK-068 a move locks the controls until the cache shows the new order`() = runDesktopComposeUiTest {
        val a = registry.addRule("A", "description", "contains", "a", "category", "C", 2, true)
        val b = registry.addRule("B", "description", "contains", "b", "category", "C", 1, true)
        refresh()
        budget()
        onNodeWithTag(RuleTags.moveDown(a)).performScrollTo().performClick()
        until("reordered") { registry.listRules().first().id == b }
        waitForIdle()
        onNodeWithTag(RuleTags.moveUp(a)).assertIsNotEnabled()
        refresh()
        waitForIdle()
        onNodeWithTag(RuleTags.moveUp(a)).assertIsEnabled()
        onNodeWithTag(RuleTags.moveDown(b)).assertIsEnabled()
    }

    @Test
    fun `TRK-064 Reapply categorises the uncategorised history`() = runDesktopComposeUiTest {
        legs = listOf(leg("buy", 5_000UL, now - 86_400, PaymentDirection.INCOMING))
        registry.addRule("R", "payee", "equals", "Exchange", "category", "DCA", 0, true)
        refresh()
        // Forget what the import pass wrote: the history is uncategorised again.
        registry.setEventCategory(wallet.id, "buy", null)
        TrackerCache.seedForTest(
            wallets = TrackerCache.wallets.value,
            events = TrackerCache.events.value.map { it.copy(category = null) },
        )
        budget()
        onNodeWithTag(RuleTags.REAPPLY).performScrollTo().performClick()
        until("category written") { registry.getEventCategory(wallet.id, "buy") == "DCA" }
    }

    // ─── TRK-094 — bills ────────────────────────────────────────────

    @Test
    fun `TRK-094 bills are added, marked paid and deleted from the UI`() = runDesktopComposeUiTest {
        refresh()
        budget()
        onNodeWithTag(BillTags.ADD).performScrollTo().performClick()
        onNodeWithTag(BillTags.INPUT_NAME).performTextInput("Rent")
        onNodeWithTag(BillTags.INPUT_AMOUNT).performTextInput("90000")
        onNodeWithTag(BillTags.INPUT_DUEDAY).performTextClearance()
        onNodeWithTag(BillTags.INPUT_DUEDAY).performTextInput("5")
        onNodeWithTag(BillTags.SAVE).performClick()
        until("bill written") { registry.listBills().size == 1 }
        val bill = registry.listBills().single()
        assertEquals(90_000UL to 5u, bill.amountSat to bill.dueDay)

        refresh()
        onNodeWithTag(BillTags.paidChip(bill.id)).performScrollTo().performClick()
        until("bill paid") { registry.listBills().single().lastPaidUnix != null }

        refresh()
        onNodeWithTag(BillTags.paidChip(bill.id)).performScrollTo().performClick()
        until("paid mark cleared") { registry.listBills().single().lastPaidUnix == null }
    }

    // ─── TRK-099 — budget save / clear ──────────────────────────────

    @Test
    fun `TRK-099 the budget dialog writes a monthly budget and clears it`() = runDesktopComposeUiTest {
        registry.setBudget("Food", 1_000UL, "monthly")
        legs = listOf(leg("f", 300UL, now - 86_400))
        registry.setEventCategory(wallet.id, "f", "Food")
        refresh()
        budget()
        onAllNodesWithTag(BudgetTags.editChip("Food"))[0].performScrollTo().performClick()
        onNodeWithTag(BudgetTags.INPUT).performTextClearance()
        onNodeWithTag(BudgetTags.INPUT).performTextInput("7000")
        onNodeWithTag(BudgetTags.SAVE).performClick()
        until("budget written") { registry.listBudgets().single().budgetedSat == 7_000UL }
        assertEquals("monthly", registry.listBudgets().single().period)

        refresh()
        onAllNodesWithTag(BudgetTags.editChip("Food"))[0].performScrollTo().performClick()
        onNodeWithTag(BudgetTags.CLEAR).performClick()
        until("budget cleared") { registry.listBudgets().isEmpty() }
    }

    // ─── TRK-082 / TRK-083 — recurring actions ──────────────────────

    private fun gym() {
        legs = (1..4).map { leg("gym$it", 20_000UL, now - (5 - it) * 30L * 86_400L) }
        for (i in 1..4) registry.setEventCategory(wallet.id, "gym$i", "Gym")
        refresh()
    }

    @Test
    fun `TRK-082 dismissing a suggestion writes its key`() = runDesktopComposeUiTest {
        gym()
        val key = TrackerCache.recurringCandidates.value.single().payeeKey
        budget()
        onNodeWithTag(RecurringTags.dismissTag(key)).performScrollTo().performClick()
        until("dismissed") { registry.listDismissedRecurring() == listOf(key) }
    }

    @Test
    fun `TRK-083 accepting a suggestion creates the bill and dismisses it`() = runDesktopComposeUiTest {
        gym()
        val key = TrackerCache.recurringCandidates.value.single().payeeKey
        budget()
        onNodeWithTag(RecurringTags.acceptTag(key)).performScrollTo().performClick()
        until("bill created") { registry.listBills().size == 1 }
        assertEquals("Gym", registry.listBills().single().name)
        until("dismissed") { registry.listDismissedRecurring() == listOf(key) }
    }

    // ─── TRK-034 / TRK-072 — category save and rule from edit ───────

    @Test
    fun `TRK-034 TRK-072 a re-categorisation offers a rule that also fills matching history`() = runDesktopComposeUiTest {
        legs = listOf(
            leg("a", 5_000UL, now - 3_600, PaymentDirection.INCOMING),
            leg("b", 6_000UL, now - 7_200, PaymentDirection.INCOMING),
        )
        refresh()
        setContent { BhodlTheme { TrackingScreen(section = TrackerSection.TRANSACTIONS) } }
        waitForIdle()
        onAllNodesWithTag(TrackingTags.CATEGORY_CHIP)[0].performScrollTo().performClick()
        onNodeWithTag(TrackingTags.CATEGORY_INPUT).performTextInput("  DCA ")
        onNodeWithTag(TrackingTags.CATEGORY_SAVE).performClick()
        until("category saved") { registry.getEventCategory(wallet.id, "a") == "DCA" }

        waitUntilAtLeastOneExists(hasText("Always do this?", substring = true), timeoutMillis = 5_000)
        // The edited row is excluded from the preview: one other match.
        waitUntilAtLeastOneExists(hasText("applies to: 1", substring = true))
        onNodeWithTag(RuleTags.FROM_EDIT_CREATE).performClick()
        until("rule created") { registry.listRules().size == 1 }
        val rule = registry.listRules().single()
        assertEquals("payee" to "Exchange", rule.conditionField to rule.conditionValue)
        assertEquals("DCA", rule.actionValue)
        until("matching history categorised") { registry.getEventCategory(wallet.id, "b") == "DCA" }
        assertNotNull(rule)
    }

    private companion object {
        const val SIGNET_A = "tb1p8wpt9v4frpf3tkn0srd97pksgsxc5hs52lafxwru9kgeephvs7rqlqt9zj"
    }
}
