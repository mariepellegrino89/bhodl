package io.bhodl.desktop.screens
import io.bhodl.desktop.domain.applyRules
import io.bhodl.desktop.domain.moveRule
import io.bhodl.desktop.domain.rulesInEvaluationOrder
import io.bhodl.desktop.domain.shouldOfferRuleFromEdit
import io.bhodl.desktop.domain.topRulePriority
import io.bhodl.desktop.domain.RecurringCandidate
import io.bhodl.desktop.domain.dayOfMonth
import io.bhodl.desktop.domain.manualAssetsByKindSat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.data.TrackerCache
import io.bhodl.desktop.navigation.TrackerSection
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.privacy_hide_amounts
import io.bhodl.shared.resources.privacy_show_amounts
import io.bhodl.shared.resources.security_hub_open
import org.jetbrains.compose.resources.stringResource
import io.bhodl.desktop.ui.userMessage
import io.bhodl.desktop.ui.bhodlCard
import io.bhodl.desktop.ui.displayAmount
import io.bhodl.desktop.wizard.RegistryStore
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.ui.theme.BhodlTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.bhodl.shared.resources.trk_err_accept_as_bill
import io.bhodl.shared.resources.trk_err_clear_budget
import io.bhodl.shared.resources.trk_err_create_rule
import io.bhodl.shared.resources.trk_err_delete_bill
import io.bhodl.shared.resources.trk_err_delete_goal
import io.bhodl.shared.resources.trk_err_delete_investment
import io.bhodl.shared.resources.trk_err_delete_liability
import io.bhodl.shared.resources.trk_err_delete_lot
import io.bhodl.shared.resources.trk_err_delete_manual_asset
import io.bhodl.shared.resources.trk_err_delete_rule
import io.bhodl.shared.resources.trk_err_delete_sell
import io.bhodl.shared.resources.trk_err_delete_view
import io.bhodl.shared.resources.trk_err_dismiss
import io.bhodl.shared.resources.trk_err_mark_bill
import io.bhodl.shared.resources.trk_err_reapply_rules
import io.bhodl.shared.resources.trk_err_record_sell
import io.bhodl.shared.resources.trk_err_registry_not_open
import io.bhodl.shared.resources.trk_err_reorder_rules
import io.bhodl.shared.resources.trk_err_save_bill
import io.bhodl.shared.resources.trk_err_save_budget
import io.bhodl.shared.resources.trk_err_save_category
import io.bhodl.shared.resources.trp_cb_err_save
import io.bhodl.shared.resources.trk_err_save_event_kind
import io.bhodl.shared.resources.trk_err_save_goal
import io.bhodl.shared.resources.trk_err_save_investment
import io.bhodl.shared.resources.trk_err_save_details
import io.bhodl.shared.resources.trk_err_save_liability
import io.bhodl.shared.resources.trk_err_save_lot
import io.bhodl.shared.resources.trk_err_save_manual_asset
import io.bhodl.shared.resources.trk_err_save_rule
import io.bhodl.shared.resources.trk_err_save_view
import io.bhodl.shared.resources.trk_export_csv_empty
import io.bhodl.shared.resources.trk_export_csv_n
import io.bhodl.shared.resources.trk_header_lock
import io.bhodl.shared.resources.trk_header_modules
import io.bhodl.shared.resources.trk_palette_clear_filters
import io.bhodl.shared.resources.trk_palette_export_csv
import io.bhodl.shared.resources.trk_palette_go_to
import io.bhodl.shared.resources.trk_palette_period
import io.bhodl.shared.resources.trk_palette_refresh
import io.bhodl.shared.resources.trk_palette_save_view
import org.jetbrains.compose.resources.getString

/**
 * Phase 9.0 (IA restructure) — the tracker surface, one navigable
 * section at a time.
 *
 * The Phase 3.2→4.0 internal tab row (Dashboard / History / Reports /
 * Analytics) and the 13-card dashboard scroll are gone. Sections are
 * first-class navigation destinations driven by the NavigationRail
 * (`TwoTabShell`): HOME (hero + KPI synthesis + wallets),
 * TRANSACTIONS (ledger + composable filters), BUDGET, GOALS,
 * PORTFOLIO, REPORTS. One page per domain — Kubera/Monarch
 * convention — instead of one scroll of stacked cards.
 *
 * Cross-section UI state (global period, Transactions filters set by
 * chart drill-downs) lives in [TrackerUiState] so it survives the
 * route-based section switches. Data state stays in
 * [io.bhodl.desktop.data.TrackerCache] (process lifetime).
 */
@Composable
public fun TrackingScreen(
    /** Which section to render — mapped from `Route.Tracking.section`. */
    section: TrackerSection = TrackerSection.HOME,
    /** Navigate to another tracker section (rail/keyboard/palette/drill-down). */
    onSectionChange: (TrackerSection) -> Unit = {},
    /** A wallet row was clicked: open its workspace. */
    onOpenWallet: (walletId: String) -> Unit = {},
    onAddWatch: () -> Unit = {},
    onOpenModules: () -> Unit = {},
    /** UX review 2026-10 (item 14) — footer shield → Security Hub. */
    onOpenSecurityHub: (() -> Unit)? = null,
    onLockDevice: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    // Phase 7.2.3 — registry truth comes from TrackerCache (process
    // lifetime). The composable becomes a view: it reads State<>
    // straight from the cache, so a tab switch / re-mount doesn't
    // discard the snapshot and the background poll keeps these
    // values fresh regardless of which tab is showing.
    val wallets by TrackerCache.wallets
    val watchBalances by TrackerCache.watchBalances
    val refreshing by TrackerCache.refreshing
    val cacheError by TrackerCache.lastError
    val events by TrackerCache.events
    val categories by TrackerCache.categories
    val views by TrackerCache.views
    val budgets by TrackerCache.budgets
    val bills by TrackerCache.bills
    val goals by TrackerCache.goals
    val liabilities by TrackerCache.liabilities
    val investments by TrackerCache.investments
    // Phase 7.8 — lots grouped by parent investment id.
    val investmentLotsByInvestmentId by TrackerCache.investmentLotsByInvestmentId
    // Phase 7.9 — sells + disposals grouped by parent investment id.
    val investmentSellsByInvestmentId by TrackerCache.investmentSellsByInvestmentId
    val lotDisposalsByInvestmentId by TrackerCache.lotDisposalsByInvestmentId
    // Phase 7.3 — investment value in sat is read straight from the
    // cache rather than recomputed here, so the dashboard sees the
    // same number as the Tracker home NetWorthBreakdownTable.
    val investmentsAssetSat by TrackerCache.investmentsAssetSat
    // Phase 7.4 — manual assets feed both the new ManualAssetsCard
    // and the NW computation. `manualAssetsSat` is the aggregate in
    // sat (already converted via PriceCache by the cache layer).
    val manualAssets by TrackerCache.manualAssets
    val manualAssetsSat by TrackerCache.manualAssetsSat
    val manualAssetsByKindSat by TrackerCache.manualAssetsByKindSat
    // Phase 7.5 — rules + recurring candidates from the cache.
    val rules by TrackerCache.rules
    // Payee text for rules (item 11): the label of the event's wallet.
    val walletLabelById = remember(wallets) { wallets.associate { it.id to it.label } }
    // Item 11 — the rule order being written; the move controls are
    // disabled until the reloaded cache shows it (or the write failed).
    var pendingRuleOrder by remember { mutableStateOf<List<Long>?>(null) }
    LaunchedEffect(rules) {
        val pending = pendingRuleOrder ?: return@LaunchedEffect
        val now = rulesInEvaluationOrder(rules).map { it.id }
        // Written order visible, or the rule set itself changed meanwhile.
        if (now == pending || now.toSet() != pending.toSet()) pendingRuleOrder = null
    }
    val recurringCandidates by TrackerCache.recurringCandidates
    val staleWalletIds by TrackerCache.staleWalletIds
    val lastSyncUnix by TrackerCache.lastSyncUnix

    // Screen-local error string for mutation failures ("Failed to save
    // bill", etc.). The displayed error is the cache error when
    // present, otherwise this local one — cache errors are about the
    // shared state and usually wider, so they win.
    var lastError: String? by remember { mutableStateOf(null) }
    val displayError: String? = cacheError ?: lastError
    val registryNotOpen = stringResource(Res.string.trk_err_registry_not_open)

    // Phase 3.4 → 9.0 — categories surface state.
    //
    //  * `categories` (from cache) is the alpha-sorted distinct list.
    //  * `categoryFilter` is a tri-state: `null` shows everything,
    //    `""` shows only events without a category, any other string
    //    shows only events whose `category` matches exactly.
    //  * `editing` holds the event being annotated by the inline
    //    picker dialog; `null` when the dialog is closed.
    //
    // Phase 9.0: period + filters delegate to [TrackerUiState] so
    // they survive section navigation (a lollipop drill-down from
    // BUDGET must land on TRANSACTIONS with the filter applied).
    // The two filters compose (AND): category + time range together.
    var categoryFilter by TrackerUiState.categoryFilter
    var editing by remember {
        mutableStateOf<io.bhodl.ffi.PaymentEvent?>(null)
    }
    // UX review 2026-10 (item 11) — the re-categorised event + its new
    // category while the "Always do this?" prompt is open.
    var ruleFromEdit by remember {
        mutableStateOf<Pair<io.bhodl.ffi.PaymentEvent, String>?>(null)
    }
    var period by TrackerUiState.period
    var timeRangeFilter by TrackerUiState.timeRangeFilter
    // Saved-view inline-name dialog state. The view LIST itself
    // comes from TrackerCache.views (Phase 7.2.3).
    var showSaveDialog by remember { mutableStateOf(false) }
    // Per-row dialog state. The LISTS (budgets, bills, goals,
    // liabilities, investments) come from TrackerCache; only the
    // dialog open/close state stays in-composable.
    var budgetEdit by remember { mutableStateOf<Pair<String, ULong?>?>(null) }
    var billDialog by remember { mutableStateOf<io.bhodl.ffi.TrackingBillFfi?>(null) }
    var showNewBill by remember { mutableStateOf(false) }
    var goalDialog by remember { mutableStateOf<io.bhodl.ffi.TrackingSavingsGoalFfi?>(null) }
    var showNewGoal by remember { mutableStateOf(false) }
    var liabilityDialog by remember { mutableStateOf<io.bhodl.ffi.TrackingLiabilityFfi?>(null) }
    // Portfolio redesign 2026-10 — kind-specific details editor.
    var detailsRequest by remember { mutableStateOf<DetailsEditRequest?>(null) }
    var showNewLiability by remember { mutableStateOf(false) }
    // Phase 7.4 — same Add/Edit pair for manual assets.
    var manualAssetDialog by remember { mutableStateOf<io.bhodl.ffi.TrackingManualAssetFfi?>(null) }
    var showNewManualAsset by remember { mutableStateOf(false) }
    // Phase 7.5 — rule edit dialog state, plus a flag that opens a
    // bill dialog pre-populated from a recurring candidate.
    var ruleDialog by remember { mutableStateOf<io.bhodl.ffi.TrackingRuleFfi?>(null) }
    var showNewRule by remember { mutableStateOf(false) }
    var billPrefillFromRecurring by remember {
        mutableStateOf<RecurringCandidate?>(null)
    }
    // Phase 7.8 — lot dialog state. `addLotForInvestmentId` carries
    // the parent id for "+ Add lot" clicks; `editingLot` carries the
    // row to edit. Mutually exclusive — only one dialog at a time.
    var addLotForInvestmentId by remember { mutableStateOf<Long?>(null) }
    var editingLot by remember { mutableStateOf<io.bhodl.ffi.TrackingInvestmentLotFfi?>(null) }
    // Phase 7.9 — sell dialog state. `sellForInvestmentId` opens the
    // SellDialog scoped to that investment's lots.
    var sellForInvestmentId by remember { mutableStateOf<Long?>(null) }
    var investmentDialog by remember { mutableStateOf<io.bhodl.ffi.TrackingInvestmentFfi?>(null) }
    var showNewInvestment by remember { mutableStateOf(false) }
    // Phase 3.13 — Cmd+K command palette overlay state. Triggered by
    // the keyboard shortcut or by the host UI; auto-dismisses on
    // action select.
    var showCommandPalette by remember { mutableStateOf(false) }
    val rootFocusRequester = remember { FocusRequester() }

    // Phase 7.2.3 — initial cold-load kicker. The cache's background
    // poll and dataVersion observer handle every subsequent refresh.
    // This composable just guarantees that, if the user lands here
    // BEFORE the cache has been populated (rare: TrackerHome usually
    // populates it first), we trigger a refresh ourselves.
    LaunchedEffect(Unit) {
        TrackerCache.coldLoad()
    }
    // Phase 3.13 — request initial focus on the screen root so
    // `onPreviewKeyEvent` fires without the user clicking somewhere
    // first. The focusable root is a Box wrapper added below.
    LaunchedEffect(Unit) {
        runCatching { rootFocusRequester.requestFocus() }
    }

    // Group registry rows once (rows sharing an xpub are one wallet)
    // and reuse the result for both the Total card and the per-row list.
    val groupedWallets = groupWalletsByXpub(
        wallets = wallets,
        watchBalances = watchBalances,
    )
    // Phase 3.14 — drop groups whose ALL member IDs the user has
    // excluded from totals. The full list still renders in the
    // Wallets section so the user can re-include them. KPI / charts /
    // Reports tables consume only the included subset, so excluded
    // wallets visually disappear from aggregations.
    val excludedWalletIds by SettingsStore.excludedWalletIds
    // UX review 2026-10 (B1/B8) — same tracked-wallet list and BTC
    // total as the HOME hero (see TrackerNetWorth.kt).
    val includedGroupedWallets = trackedWalletGroups(wallets, watchBalances, excludedWalletIds)
    val total = trackedWalletSat(includedGroupedWallets)
    // Phase 7.3 / 7.4 → code review 2026-10 — the savings-goal dialog's
    // "% of NW" base is the shared TRUE net worth (BTC + investments +
    // manual assets − liabilities), the same number as the HOME hero.
    // The legacy `total` (BTC only, exclusion-aware) stays in use for
    // the BTC-only BITCOIN_CASH bucket.
    val trackerNetWorth = rememberTrackerNetWorth(
        walletSat = total,
        investments = investments,
        lotsByInvestmentId = investmentLotsByInvestmentId,
        cachedInvestmentsSat = investmentsAssetSat,
        manualAssets = manualAssets,
        cachedManualAssetsSat = manualAssetsSat,
        liabilities = liabilities,
    )
    val totalNetWorthAssetSat: ULong = trackerNetWorth.goalBaseSat
    // Same filter on the event stream so KPIs / sparklines / Sankey
    // see only the included wallets' activity. The History tab below
    // intentionally still receives the full event list because the
    // user is reading them as a ledger, not as an aggregation.
    // Exclusion runs on the per-wallet legs, BEFORE the txid dedupe:
    // a tx shared with an excluded wallet stays for the included one.
    val eventLegs by TrackerCache.eventLegs
    val includedEvents = remember(events, eventLegs, excludedWalletIds) {
        io.bhodl.desktop.screens.includedEvents(events, eventLegs, excludedWalletIds)
    }

    // Phase 3.4 — apply the active category filter to the events
    // that drive the History list and the CSV export. The Total
    // card and per-wallet rows above are intentionally NOT filtered:
    // they describe wallets, not transactions, and a category filter
    // there would be misleading.
    val filteredEvents = remember(events, categoryFilter, timeRangeFilter) {
        filterLedger(events, categoryFilter, timeRangeFilter)
    }
    // Per-category breakdown card. Treats `null`/blank as the implicit
    // "Uncategorised" bucket so the user sees what hasn't been tagged
    // yet. Internal transfers (SELF_TRANSFER, send_to_self) are not
    // income or spending: only a send_to_self's fee counts as outflow.
    val breakdown = remember(events) { categoryBreakdown(events) }

    // Phase 3.13 — keyboard shortcut handler. Uses `onKeyEvent` (post-
    // handler) rather than `onPreviewKeyEvent` so any focused text
    // field (CategoryEditDialog input, SaveViewDialog name, Command
    // palette search) consumes the keystroke FIRST and the event only
    // bubbles to the root when nothing else wanted it. This is the fix
    // for the Phase 3.13.b bug where typing "Rent" into a dialog
    // triggered Refresh on the first `R`.
    //
    // Modifier+key combos:
    //   Cmd/Ctrl + K   → command palette (handled even with focus —
    //                    the modifier guards against accidental fire)
    //   R              → refresh
    //   1 … 6          → jump to section (Home / Transactions / …)
    //   ← / →          → cycle to previous / next period
    //   Esc            → clear active filters
    val onKey: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = handler@{ event ->
        if (event.type != KeyEventType.KeyDown) return@handler false
        val mod = event.isCtrlPressed || event.isMetaPressed
        when {
            mod && event.key == Key.K -> {
                showCommandPalette = true
                true
            }
            !mod && event.key == Key.R -> {
                if (!refreshing) {
                    scope.launch { TrackerCache.refresh(syncEsplora = true) }
                }
                true
            }
            !mod && event.key == Key.One -> { onSectionChange(TrackerSection.HOME); true }
            !mod && event.key == Key.Two -> { onSectionChange(TrackerSection.TRANSACTIONS); true }
            !mod && event.key == Key.Three -> { onSectionChange(TrackerSection.BUDGET); true }
            !mod && event.key == Key.Four -> { onSectionChange(TrackerSection.GOALS); true }
            !mod && event.key == Key.Five -> { onSectionChange(TrackerSection.PORTFOLIO); true }
            !mod && event.key == Key.Six -> { onSectionChange(TrackerSection.REPORTS); true }
            !mod && event.key == Key.DirectionLeft -> {
                val all = TrackingPeriod.entries
                val idx = all.indexOf(period)
                if (idx > 0) period = all[idx - 1]
                true
            }
            !mod && event.key == Key.DirectionRight -> {
                val all = TrackingPeriod.entries
                val idx = all.indexOf(period)
                if (idx < all.lastIndex) period = all[idx + 1]
                true
            }
            !mod && event.key == Key.Escape -> {
                categoryFilter = null
                timeRangeFilter = null
                true
            }
            else -> false
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BhodlTheme.colors.background)
            .focusRequester(rootFocusRequester)
            .focusable()
            .onKeyEvent(onKey)
            .testTag(TrackingTags.ROOT),
        contentAlignment = Alignment.TopCenter,
    ) {
        // Phase 3.6 — split the layout into a fixed header (title +
        // CSV + DataStateStrip + tabs + period selector when on
        // Dashboard) and a scrollable body. This makes the period
        // selector behave as a sticky header without having to
        // refactor everything into a LazyColumn (Compose Material 3
        // doesn't support stickyHeader on a plain scrollable Column).
        //
        // Phase 3.7 — `widthIn(max = 720.dp)` was lifted; the
        // TrackingDashboardTab now picks its own max width (up to
        // ~1400dp on big monitors) so the 2-column chart layout has
        // room to breathe. HistoryTabBody re-applies the 720dp
        // ceiling internally for reading comfort.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 1400.dp)
                .padding(horizontal = 32.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Phase 9.0 — compact header bar: brand + section title on
            // the left, Modules / Lock affordances (merged from the old
            // TrackerHome header rail) on the right. Settings lives in
            // the NavigationRail now.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "BHODL",
                        style = BhodlTheme.typography.labelMedium,
                        color = BhodlTheme.colors.brandPrimary,
                    )
                    Text(
                        text = section.label,
                        style = BhodlTheme.typography.headlineMedium,
                        color = BhodlTheme.colors.textPrimary,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    androidx.compose.material3.TextButton(
                        onClick = onOpenModules,
                        modifier = Modifier.testTag(TrackingTags.HEADER_MODULES),
                    ) { Text(stringResource(Res.string.trk_header_modules)) }
                    androidx.compose.material3.TextButton(
                        onClick = onLockDevice,
                        modifier = Modifier.testTag(TrackingTags.HEADER_LOCK),
                    ) {
                        Text(stringResource(Res.string.trk_header_lock), color = BhodlTheme.colors.textTertiary)
                    }
                }
            }

            val labelByWalletIdForExport = wallets.associate { it.id to it.label }
            if (section == TrackerSection.TRANSACTIONS) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val saved = csvExportAction(filteredEvents, labelByWalletIdForExport)
                            if (saved != null) lastError = saved
                        }
                    },
                    enabled = !refreshing && filteredEvents.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TrackingTags.EXPORT_CSV),
                ) {
                    Text(
                        if (filteredEvents.isEmpty()) stringResource(Res.string.trk_export_csv_empty)
                        else stringResource(Res.string.trk_export_csv_n, filteredEvents.size),
                    )
                }

                // Phase 3.8 — saved views chip strip. Above the data
                // state strip so it reads as a navigation aid (pick a
                // saved bookmark) before the user touches the per-event
                // surface.
                SavedViewsBar(
                    views = views,
                    onApply = { v ->
                        val sel = applySavedView(v)
                        period = sel.period
                        categoryFilter = sel.categoryFilter
                        timeRangeFilter = sel.timeRange
                        onSectionChange(TrackerSection.TRANSACTIONS)
                    },
                    onSave = { showSaveDialog = true },
                    onDelete = { id ->
                        val registry = RegistryStore.current() ?: return@SavedViewsBar
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) { registry.deleteView(id) }
                            }.onFailure { e ->
                                lastError = e.message ?: getString(Res.string.trk_err_delete_view)
                            }
                            RegistryStore.bumpDataVersion()
                        }
                    },
                )
            }

            val totalWatchCount = wallets.size
            DataStateStrip(
                lastSyncUnix = lastSyncUnix,
                staleCount = staleWalletIds.size,
                totalWatchCount = totalWatchCount,
                refreshing = refreshing,
                onRefresh = {
                    if (refreshing) return@DataStateStrip
                    scope.launch { TrackerCache.refresh(syncEsplora = true) }
                },
            )

            if (displayError != null) {
                Text(
                    text = displayError,
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.error,
                    textAlign = TextAlign.Center,
                )
            }

            val modAnalyticsLabs by SettingsStore.moduleAnalyticsLabs

            // Phase 9.0 — ONE persistent period selector across every
            // aggregating section (finance-tracker convention: the
            // window is a global mode, not a per-chart toggle). The
            // Transactions ledger keeps its explicit composable
            // filters (category AND time range) instead.
            if (section != TrackerSection.TRANSACTIONS) {
                PeriodSelector(
                    selected = period,
                    onSelect = { period = it },
                )
            }

            // Scrollable body — everything below the header scrolls
            // as one block. weight(1f) gives it the remaining height.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Phase 9.0 — one fully-wired entry point for the
                // section bodies that render TrackingDashboardTab
                // content; the mode selects which cards appear.
                val dashboardTab: @Composable (TrackingTabMode) -> Unit = { tabMode ->
                    TrackingDashboardTab(
                        onEditEvent = { event -> editing = event },
                        events = includedEvents,
                        period = period,
                        onPeriodChange = { period = it },
                        currentBalanceSat = total,
                        refreshing = refreshing,
                        mode = tabMode,
                        budgets = budgets,
                        onEditBudget = { cat, current ->
                            budgetEdit = cat to current
                        },
                        bills = bills,
                        onAddBill = { showNewBill = true },
                        onEditBill = { b -> billDialog = b },
                        onToggleBillPaid = { b, paid ->
                            val registry = RegistryStore.current() ?: return@TrackingDashboardTab
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        registry.markBillPaid(
                                            b.id,
                                            if (paid) System.currentTimeMillis() / 1000L else null,
                                        )
                                    }
                                }.onFailure { e ->
                                    lastError = e.message ?: getString(Res.string.trk_err_mark_bill)
                                }
                                RegistryStore.bumpDataVersion()
                            }
                        },
                        goals = goals,
                        onAddGoal = { showNewGoal = true },
                        onEditGoal = { g -> goalDialog = g },
                        liabilities = liabilities,
                        onAddLiability = { showNewLiability = true },
                        onEditLiability = { l -> liabilityDialog = l },
                        investments = investments,
                        onAddInvestment = { showNewInvestment = true },
                        onEditInvestment = { inv -> investmentDialog = inv },
                        // Phase 7.8 — lots from the cache + callbacks
                        // for the add/edit dialogs.
                        investmentLotsByInvestmentId = investmentLotsByInvestmentId,
                        onAddLot = { invId -> addLotForInvestmentId = invId },
                        onEditLot = { lot -> editingLot = lot },
                        // Phase 7.9 — sells + disposals + dialog.
                        investmentSellsByInvestmentId = investmentSellsByInvestmentId,
                        lotDisposalsByInvestmentId = lotDisposalsByInvestmentId,
                        onSell = { invId -> sellForInvestmentId = invId },
                        onDeleteSell = { sell ->
                            val registry = RegistryStore.current() ?: return@TrackingDashboardTab
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        registry.deleteInvestmentSell(sell.id)
                                    }
                                }.onFailure { e ->
                                    lastError = e.message ?: getString(Res.string.trk_err_delete_sell)
                                }
                                RegistryStore.bumpDataVersion()
                            }
                        },
                        onEditDetails = { req -> detailsRequest = req },
                        // Phase 7.2.4 — feed the SavingsGoalsCard the
                        // NW context it needs to derive `current_sat`
                        // for non-manual buckets and render the live
                        // % NW target. PriceCache::fiatToSat is the
                        // production binding for the investments
                        // bucket; null returns degrade to a 0-sat row.
                        wallets = wallets,
                        watchBalances = watchBalances,
                        fiatToSat = io.bhodl.desktop.prices.PriceCache::fiatToSat,
                        // Each investment / manual asset converts at
                        // its own currency's BTC rate.
                        rateFor = io.bhodl.desktop.prices.PriceCache::rateFor,
                        totalInvestmentsSat = investmentsAssetSat,
                        totalManualAssetsSat = manualAssetsSat,
                        manualAssets = manualAssets,
                        onAddManualAsset = { showNewManualAsset = true },
                        onEditManualAsset = { a -> manualAssetDialog = a },
                        rules = rules,
                        onAddRule = { showNewRule = true },
                        onEditRule = { r -> ruleDialog = r },
                        onReapplyRules = {
                            val registry = RegistryStore.current() ?: return@TrackingDashboardTab
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        reapplyRules(registry, events, rules, walletLabelById)
                                    }
                                }.onFailure { e ->
                                    lastError = e.message ?: getString(Res.string.trk_err_reapply_rules)
                                }
                                RegistryStore.bumpDataVersion()
                            }
                        },
                        reorderingRules = pendingRuleOrder != null,
                        onMoveRule = { rule, delta ->
                            // One move at a time: the controls stay disabled
                            // until the cache shows the written order, so a
                            // double click can't act on a stale list.
                            if (pendingRuleOrder != null) return@TrackingDashboardTab
                            val registry = RegistryStore.current() ?: return@TrackingDashboardTab
                            val order = moveRule(rules, rule.id, delta) ?: return@TrackingDashboardTab
                            pendingRuleOrder = order
                            scope.launch {
                                runCatching {
                                    // Every priority rewritten in ONE registry
                                    // transaction (a stale order is refused).
                                    withContext(Dispatchers.IO) { registry.reorderRules(order) }
                                }.onFailure { e ->
                                    lastError = e.message ?: getString(Res.string.trk_err_reorder_rules)
                                    pendingRuleOrder = null
                                }
                                RegistryStore.bumpDataVersion()
                            }
                        },
                        recurringCandidates = recurringCandidates,
                        onAcceptRecurring = { c -> billPrefillFromRecurring = c },
                        onDismissRecurring = { c ->
                            val registry = RegistryStore.current() ?: return@TrackingDashboardTab
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        registry.dismissRecurring(c.payeeKey)
                                    }
                                }.onFailure { e ->
                                    lastError = e.message ?: getString(Res.string.trk_err_dismiss)
                                }
                                RegistryStore.bumpDataVersion()
                            }
                        },
                        onRefreshPrices = {
                            scope.launch {
                                io.bhodl.desktop.prices.PriceCache.refreshInvestmentPrices()
                                TrackerCache.refresh(syncEsplora = false)
                            }
                        },
                        // Phase 9.0 — drill-downs COMPOSE with the
                        // existing filters (AND): clicking a category
                        // no longer wipes an active time range, so
                        // "Food in the last 90 days" is expressible.
                        onCategoryDrillDown = { category ->
                            categoryFilter = category
                            onSectionChange(TrackerSection.TRANSACTIONS)
                        },
                        onBucketDrillDown = { range ->
                            timeRangeFilter = range
                            onSectionChange(TrackerSection.TRANSACTIONS)
                        },
                    )
                }

                when (section) {
                    TrackerSection.HOME -> {
                        // Hero + wallet list (merged TrackerHome) +
                        // KPI strip / NW donut / inflow-outflow
                        // synthesis. Everything else lives on its own
                        // section page now.
                        TrackerHomeSection(
                            onOpenReports = { onSectionChange(TrackerSection.REPORTS) },
                            onOpenPortfolio = { onSectionChange(TrackerSection.PORTFOLIO) },
                            onOpenBudget = { onSectionChange(TrackerSection.BUDGET) },
                            onOpenTransactions = { onSectionChange(TrackerSection.TRANSACTIONS) },
                            onOpenWallet = onOpenWallet,
                            onAddWatch = onAddWatch,
                        )
                    }
                    TrackerSection.TRANSACTIONS -> HistoryTabBody(
                        wallets = wallets,
                        refreshing = refreshing,
                        groupedWallets = groupedWallets,
                        excludedWalletIds = excludedWalletIds,
                        onToggleInclude = { walletId, included ->
                            SettingsStore.setWalletIncluded(walletId, included)
                        },
                        events = events,
                        filteredEvents = filteredEvents,
                        breakdown = breakdown,
                        categories = categories,
                        categoryFilter = categoryFilter,
                        timeRangeFilter = timeRangeFilter,
                        onClearTimeRange = { timeRangeFilter = null },
                        onSelectCategory = { categoryFilter = it },
                        onEditCategory = { event -> editing = event },
                    )
                    TrackerSection.BUDGET -> dashboardTab(TrackingTabMode.BUDGET)
                    TrackerSection.GOALS -> dashboardTab(TrackingTabMode.GOALS)
                    TrackerSection.PORTFOLIO -> dashboardTab(TrackingTabMode.PORTFOLIO)
                    TrackerSection.REPORTS -> {
                        // Tables first (the section's namesake), then
                        // the power-user charts for Analytics Labs
                        // users. Both consume the global period.
                        ReportsTabBody(
                            events = filterByPeriod(
                                includedEvents,
                                period,
                                System.currentTimeMillis() / 1000L,
                            ),
                            groupedWallets = includedGroupedWallets,
                            periodLabel = period.label,
                        )
                        if (modAnalyticsLabs) {
                            dashboardTab(TrackingTabMode.ANALYTICS)
                        }
                    }
                }
            }

            // Phase 3.11 — footer status bar (Sparrow / Bitcoin Core
            // convention). Pinned at the bottom of the Tracking screen
            // (outside the scrollable body) so the user always sees
            // network / Tor / sync state regardless of how far they
            // scrolled inside a tab.
            io.bhodl.desktop.ui.BhodlFooterStatusBar(
                network = WalletStore.network(),
                walletCount = includedGroupedWallets.size,
                lastSyncUnix = lastSyncUnix,
                onOpenSecurityHub = onOpenSecurityHub,
            )
        }
    }

    // Phase 5.1 — investment create/edit dialog.
    if (showNewInvestment || investmentDialog != null) {
        InvestmentEditDialog(
            editing = investmentDialog,
            lots = investmentDialog?.let { investmentLotsByInvestmentId[it.id].orEmpty() }.orEmpty(),
            onDismiss = {
                showNewInvestment = false
                investmentDialog = null
            },
            onSave = { name, assetType, ticker, quantity, unit, costBasis, ccy, currentValue, costBasisMethod, purchaseUnix ->
                val registry = RegistryStore.current()
                if (registry == null) {
                    lastError = registryNotOpen
                    showNewInvestment = false
                    investmentDialog = null
                    return@InvestmentEditDialog
                }
                val editingNow = investmentDialog
                scope.launch {
                    // Lot whose BTC rate to resolve from its purchase date,
                    // off the save's critical path (see below).
                    var lotToResolve: Long? = null
                    runCatching {
                        withContext(Dispatchers.IO) {
                            val targetId = if (editingNow != null) {
                                registry.updateInvestment(
                                    editingNow.id, name, assetType, ticker,
                                    quantity, unit, costBasis, ccy,
                                    purchaseUnix, currentValue, editingNow.notes,
                                )
                                // Phase 9 — the row shows lot-derived qty/cost
                                // whenever a lot exists (effectiveQuantity /
                                // effectiveCostBasis ignore the aggregate once
                                // lots are present), and every dialog-created
                                // position carries one auto-seeded lot. Without
                                // syncing it, the edited qty/cost would be a
                                // silent no-op on screen. Auto-sync only the
                                // single-lot case; multi-lot positions are
                                // managed via the lots UI, where the aggregate
                                // is genuinely derived.
                                val lots = investmentLotsByInvestmentId[editingNow.id].orEmpty()
                                singleLotResync(lots, quantity, costBasis)?.let { resync ->
                                    val lot = lots.first()
                                    registry.updateInvestmentLot(
                                        lot.id, resync.quantity, resync.pricePerUnit,
                                        lot.feesFiat, purchaseUnix, lot.notes,
                                    )
                                    lotToResolve = lot.id
                                }
                                editingNow.id
                            } else {
                                registry.addInvestment(
                                    name, assetType, ticker, quantity, unit,
                                    costBasis, ccy, purchaseUnix, currentValue, null,
                                )
                            }
                            runCatching {
                                registry.setInvestmentCostBasisMethod(targetId, costBasisMethod)
                            }
                            if (editingNow == null && shouldSeedLotOnCreate(quantity, costBasis)) {
                                runCatching {
                                    lotToResolve = registry.seedInvestmentLotFromAggregate(targetId)
                                }
                            }
                        }
                    }.onFailure { e ->
                        lastError = e.message ?: getString(Res.string.trk_err_save_investment)
                    }
                    showNewInvestment = false
                    investmentDialog = null
                    RegistryStore.bumpDataVersion()
                    // Phase 9 (BTC P&L) — resolve the HISTORICAL BTC rate at
                    // the purchase date (not the current rate) off the save's
                    // critical path: the row is already visible, the rate
                    // fills in on the second bump. Best-effort — stays NULL
                    // ("no rate") on network failure / locked session.
                    lotToResolve?.let { id ->
                        withContext(Dispatchers.IO) {
                            io.bhodl.desktop.prices.TrackerBtcRate.resolveLot(registry, id)
                        }
                        RegistryStore.bumpDataVersion()
                    }
                }
            },
            onDelete = { id ->
                val registry = RegistryStore.current()
                if (registry == null) {
                    lastError = registryNotOpen
                    showNewInvestment = false
                    investmentDialog = null
                    return@InvestmentEditDialog
                }
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) { registry.deleteInvestment(id) }
                    }.onFailure { e ->
                        lastError = e.message ?: getString(Res.string.trk_err_delete_investment)
                    }
                    showNewInvestment = false
                    investmentDialog = null
                    RegistryStore.bumpDataVersion()
                }
            },
        )
    }

    // Portfolio redesign 2026-10 — details of one investment / manual
    // asset / liability (registry v6 `details_json`).
    detailsRequest?.let { req ->
        PortfolioDetailsDialog(
            request = req,
            linkableAssets = manualAssets,
            onDismiss = { detailsRequest = null },
            onSave = { json ->
                val registry = RegistryStore.current()
                val table = req.category.detailsTable
                if (registry == null || table == null) {
                    lastError = registryNotOpen
                    detailsRequest = null
                    return@PortfolioDetailsDialog
                }
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) { registry.setPortfolioDetails(table, req.recordId, json) }
                    }.onFailure { e ->
                        lastError = e.userMessage(getString(Res.string.trk_err_save_details))
                    }
                    detailsRequest = null
                    RegistryStore.bumpDataVersion()
                }
            },
        )
    }

    // Phase 4.6 — liability create/edit dialog. Same pattern.
    if (showNewLiability || liabilityDialog != null) {
        LiabilityEditDialog(
            editing = liabilityDialog,
            onDismiss = {
                showNewLiability = false
                liabilityDialog = null
            },
            onSave = { name, amountSat, kind, displayLabel ->
                val registry = RegistryStore.current()
                if (registry == null) {
                    lastError = registryNotOpen
                    showNewLiability = false
                    liabilityDialog = null
                    return@LiabilityEditDialog
                }
                val editingNow = liabilityDialog
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            if (editingNow != null) {
                                registry.updateLiability(
                                    editingNow.id, name, amountSat, kind, displayLabel,
                                )
                            } else {
                                registry.addLiability(name, amountSat, kind, displayLabel)
                            }
                        }
                    }.onFailure { e ->
                        lastError = e.message ?: getString(Res.string.trk_err_save_liability)
                    }
                    showNewLiability = false
                    liabilityDialog = null
                    RegistryStore.bumpDataVersion()
                }
            },
            onDelete = { id ->
                val registry = RegistryStore.current()
                if (registry == null) {
                    lastError = registryNotOpen
                    showNewLiability = false
                    liabilityDialog = null
                    return@LiabilityEditDialog
                }
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) { registry.deleteLiability(id) }
                    }.onFailure { e ->
                        lastError = e.message ?: getString(Res.string.trk_err_delete_liability)
                    }
                    showNewLiability = false
                    liabilityDialog = null
                    RegistryStore.bumpDataVersion()
                }
            },
        )
    }

    // Phase 7.4 — manual asset create/edit dialog. Same shape as the
    // Liability dialog above but with kind picker (8 chip), currency
    // picker (5 fiat), and optional notes.
    if (showNewManualAsset || manualAssetDialog != null) {
        ManualAssetEditDialog(
            editing = manualAssetDialog,
            onDismiss = {
                showNewManualAsset = false
                manualAssetDialog = null
            },
            onSave = { name, kind, value, currency, notes, purchaseUnix, purchaseValueFiat ->
                val registry = RegistryStore.current()
                if (registry == null) {
                    lastError = registryNotOpen
                    showNewManualAsset = false
                    manualAssetDialog = null
                    return@ManualAssetEditDialog
                }
                val editingNow = manualAssetDialog
                // Purchase is made in the asset's own currency; null when no
                // purchase snapshot (the Rust resolver falls back to currency).
                val purchaseCurrency = purchaseUnix?.let { currency }
                scope.launch {
                    var assetId: Long? = null
                    runCatching {
                        withContext(Dispatchers.IO) {
                            if (editingNow != null) {
                                registry.updateManualAsset(
                                    editingNow.id, name, kind, value, currency, null, notes,
                                    purchaseUnix, purchaseValueFiat, purchaseCurrency,
                                )
                                assetId = editingNow.id
                            } else {
                                assetId = registry.addManualAsset(
                                    name, kind, value, currency, null, notes,
                                    purchaseUnix, purchaseValueFiat, purchaseCurrency,
                                )
                            }
                        }
                    }.onFailure { e ->
                        lastError = e.message ?: getString(Res.string.trk_err_save_manual_asset)
                    }
                    showNewManualAsset = false
                    manualAssetDialog = null
                    RegistryStore.bumpDataVersion()
                    // Phase 9 (BTC P&L) — resolve purchase_btc_at_time at the
                    // HISTORICAL rate on the purchase date, off the critical
                    // path. Only when a purchase snapshot was provided.
                    if (purchaseUnix != null && purchaseValueFiat != null) {
                        assetId?.let { id ->
                            withContext(Dispatchers.IO) {
                                io.bhodl.desktop.prices.TrackerBtcRate.resolveManualAsset(registry, id)
                            }
                            RegistryStore.bumpDataVersion()
                        }
                    }
                }
            },
            onDelete = { id ->
                val registry = RegistryStore.current()
                if (registry == null) {
                    lastError = registryNotOpen
                    showNewManualAsset = false
                    manualAssetDialog = null
                    return@ManualAssetEditDialog
                }
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) { registry.deleteManualAsset(id) }
                    }.onFailure { e ->
                        lastError = e.message ?: getString(Res.string.trk_err_delete_manual_asset)
                    }
                    showNewManualAsset = false
                    manualAssetDialog = null
                    RegistryStore.bumpDataVersion()
                }
            },
        )
    }

    // Phase 7.8 — lot Add/Edit dialog. Two entry points:
    //   * "+ Add lot" inside an expanded investment row → opens the
    //     dialog with `editing = null` and remembers the parent
    //     investment id. If the parent still has the Phase 5.1
    //     aggregate (cost_basis_fiat > 0, no lots yet), we seed a
    //     lot from the aggregate first so the new lot doesn't
    //     double-count the original position.
    //   * Tap on an existing lot sub-row → opens the dialog with
    //     `editing = <that lot>`.
    if (addLotForInvestmentId != null || editingLot != null) {
        val parentInvId = addLotForInvestmentId ?: editingLot?.investmentId
        val parentInv = investments.firstOrNull { it.id == parentInvId }
        if (parentInv != null) {
            LotEditDialog(
                editing = editingLot,
                parentInvestmentName = parentInv.name,
                parentCurrencySymbol = io.bhodl.desktop.screens.manualAssetSymbolFor(
                    parentInv.costBasisCurrency,
                ),
                onDismiss = {
                    addLotForInvestmentId = null
                    editingLot = null
                },
                onSave = { qty, price, fees, purchaseUnix, notes ->
                    val registry = RegistryStore.current()
                    if (registry == null) {
                        lastError = registryNotOpen
                        addLotForInvestmentId = null
                        editingLot = null
                        return@LotEditDialog
                    }
                    val editingNow = editingLot
                    val addParentId = addLotForInvestmentId
                    scope.launch {
                        // Lots whose historical BTC rate to resolve from
                        // their purchase date, off the save's critical path.
                        val lotsToResolve = mutableListOf<Long>()
                        runCatching {
                            withContext(Dispatchers.IO) {
                                if (editingNow != null) {
                                    registry.updateInvestmentLot(
                                        editingNow.id, qty, price, fees, purchaseUnix, notes,
                                    )
                                    // Re-resolve: the purchase date (hence the
                                    // historical rate) may have changed.
                                    lotsToResolve += editingNow.id
                                } else if (addParentId != null) {
                                    val existingLots = investmentLotsByInvestmentId[addParentId].orEmpty()
                                    if (existingLots.isEmpty() && parentInv.costBasisFiat > 0.0) {
                                        runCatching {
                                            lotsToResolve += registry.seedInvestmentLotFromAggregate(addParentId)
                                        }
                                    }
                                    lotsToResolve += registry.addInvestmentLot(
                                        addParentId, qty, price, fees, purchaseUnix, notes,
                                    )
                                }
                            }
                        }.onFailure { e ->
                            lastError = e.message ?: getString(Res.string.trk_err_save_lot)
                        }
                        addLotForInvestmentId = null
                        editingLot = null
                        RegistryStore.bumpDataVersion()
                        // Phase 9 (BTC P&L) — resolve each lot's HISTORICAL
                        // rate at its purchase date (replaces the old
                        // current-rate write), off the critical path.
                        if (lotsToResolve.isNotEmpty()) {
                            withContext(Dispatchers.IO) {
                                lotsToResolve.forEach {
                                    io.bhodl.desktop.prices.TrackerBtcRate.resolveLot(registry, it)
                                }
                            }
                            RegistryStore.bumpDataVersion()
                        }
                    }
                },
                onDelete = { id ->
                    val registry = RegistryStore.current() ?: return@LotEditDialog
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) { registry.deleteInvestmentLot(id) }
                        }.onFailure { e ->
                            lastError = e.message ?: getString(Res.string.trk_err_delete_lot)
                        }
                        addLotForInvestmentId = null
                        editingLot = null
                        RegistryStore.bumpDataVersion()
                    }
                },
            )
        }
    }

    // Phase 7.9 — sell dialog. Scoped to the lots of a single
    // investment (sellForInvestmentId). Records the sale via
    // record_sell, which atomically consumes lots and writes
    // disposals.
    sellForInvestmentId?.let { invId ->
        val parentInv = investments.firstOrNull { it.id == invId }
        val parentLots = investmentLotsByInvestmentId[invId].orEmpty()
        if (parentInv != null) {
            SellDialog(
                parentInvestment = parentInv,
                parentLots = parentLots,
                parentCurrencySymbol = io.bhodl.desktop.screens.manualAssetSymbolFor(
                    parentInv.costBasisCurrency,
                ),
                parentDisposals = lotDisposalsByInvestmentId[invId].orEmpty(),
                onDismiss = { sellForInvestmentId = null },
                onSave = { salePrice, saleQty, fees, saleUnix, notes, selection ->
                    val registry = RegistryStore.current()
                    if (registry == null) {
                        lastError = registryNotOpen
                        sellForInvestmentId = null
                        return@SellDialog
                    }
                    scope.launch {
                        var sellId: Long? = null
                        runCatching {
                            withContext(Dispatchers.IO) {
                                // Specific-ID: book exactly the picked lots
                                // (validated again, atomically, in Rust).
                                sellId = if (selection != null) {
                                    registry.recordSellWithLots(
                                        invId, saleUnix, salePrice, saleQty, fees, notes,
                                        selection,
                                    )
                                } else {
                                    registry.recordSell(
                                        invId, saleUnix, salePrice, saleQty, fees, notes,
                                    )
                                }
                            }
                        }.onFailure { e ->
                            lastError = e.message ?: getString(Res.string.trk_err_record_sell)
                        }
                        sellForInvestmentId = null
                        RegistryStore.bumpDataVersion()
                        // Phase 9 (BTC P&L) — resolve realized BTC
                        // (btc_per_unit_at_sale + disposals' gain_loss_btc)
                        // at the HISTORICAL rate on the sale date, off the
                        // critical path.
                        sellId?.let { id ->
                            withContext(Dispatchers.IO) {
                                io.bhodl.desktop.prices.TrackerBtcRate.resolveSell(registry, id)
                            }
                            RegistryStore.bumpDataVersion()
                        }
                    }
                },
            )
        }
    }

    // Phase 7.5 — rule create/edit dialog. Categories are pulled
    // from the registry so the action picker offers what the user
    // already has — they can still type a new one via the freeform
    // input.
    if (showNewRule || ruleDialog != null) {
        RuleEditDialog(
            editing = ruleDialog,
            knownCategories = categories,
            newRulePriority = topRulePriority(rules),
            onDismiss = {
                showNewRule = false
                ruleDialog = null
            },
            onSave = { name, field, op, value, category, priority, enabled ->
                val registry = RegistryStore.current()
                if (registry == null) {
                    lastError = registryNotOpen
                    showNewRule = false
                    ruleDialog = null
                    return@RuleEditDialog
                }
                val editingNow = ruleDialog
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            if (editingNow != null) {
                                registry.updateRule(
                                    editingNow.id, name, field, op, value,
                                    "category", category, priority, enabled,
                                )
                            } else {
                                registry.addRule(
                                    name, field, op, value, "category", category,
                                    priority, enabled,
                                )
                            }
                        }
                    }.onFailure { e ->
                        lastError = e.message ?: getString(Res.string.trk_err_save_rule)
                    }
                    showNewRule = false
                    ruleDialog = null
                    RegistryStore.bumpDataVersion()
                }
            },
            onDelete = { id ->
                val registry = RegistryStore.current()
                if (registry == null) {
                    lastError = registryNotOpen
                    showNewRule = false
                    ruleDialog = null
                    return@RuleEditDialog
                }
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) { registry.deleteRule(id) }
                    }.onFailure { e ->
                        lastError = e.message ?: getString(Res.string.trk_err_delete_rule)
                    }
                    showNewRule = false
                    ruleDialog = null
                    RegistryStore.bumpDataVersion()
                }
            },
        )
    }

    // Phase 7.5 — Accept-as-Bill flow. When the user clicks "+ Bill"
    // on a RecurringCard suggestion we build a Bill row directly from
    // the candidate's stats — no extra dialog: the user already
    // confirmed by clicking accept, and the bill itself remains
    // editable from the Bills card.
    billPrefillFromRecurring?.let { candidate ->
        val registry = RegistryStore.current()
        if (registry == null) {
            lastError = registryNotOpen
            billPrefillFromRecurring = null
        } else {
            scope.launch {
                runCatching {
                    // The bill is the authoritative record now: the
                    // suggestion is dismissed in the same step.
                    withContext(Dispatchers.IO) { acceptRecurringAsBill(registry, candidate) }
                }.onFailure { e ->
                    lastError = e.message ?: getString(Res.string.trk_err_accept_as_bill)
                }
                billPrefillFromRecurring = null
                RegistryStore.bumpDataVersion()
            }
        }
    }

    // Phase 4.5 — savings goal create/edit dialog. Same pattern as
    // Bills: `showNewGoal` opens in create mode; `goalDialog` carries
    // the row to edit. Sat-only in Phase 4 (fiat-denominated targets
    // land with Phase 5 fiat overlay).
    if (showNewGoal || goalDialog != null) {
        GoalEditDialog(
            editing = goalDialog,
            // Phase 7.2.2 / 7.3 — net worth feed for the % target
            // conversion. `totalNetWorthAssetSat` = the true net worth,
            // so "30% of NW" sat conversion matches the dashboard
            // hero. `bitcoinAssetSat` powers the "Bitcoin cash"
            // bucket preview (BTC-only by definition).
            totalNetWorthSat = totalNetWorthAssetSat,
            bitcoinAssetSat = total,
            // Phase 7.2.4 — context for the bucket picker preview.
            wallets = wallets,
            watchBalances = watchBalances,
            investments = investments,
            // Phase 7.4 — manual assets feed the MANUAL_ASSET sub-picker.
            manualAssets = manualAssets,
            fiatToSat = io.bhodl.desktop.prices.PriceCache::fiatToSat,
            lotsByInvestmentId = investmentLotsByInvestmentId,
            rateFor = io.bhodl.desktop.prices.PriceCache::rateFor,
            onDismiss = {
                showNewGoal = false
                goalDialog = null
            },
            onSave = { name, targetSat, currentSat, color, pctOfNw, bucketKind, bucketRef,
                       goalKind, deadlineUnix, denomination, fiatAmount, fiatCurrency ->
                val registry = RegistryStore.current()
                if (registry == null) {
                    lastError = registryNotOpen
                    showNewGoal = false
                    goalDialog = null
                    return@GoalEditDialog
                }
                val editingNow = goalDialog
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            // Phase 8.6 — upsert the core row first, then
                            // patch the denomination via the dedicated
                            // setter so a single registry call can't
                            // forget either side. The setter is idempotent
                            // (whitelist-validated denomination + null
                            // fiat fields for `sat`), so re-running this
                            // pair after a partial failure is safe.
                            val targetId: Long = if (editingNow != null) {
                                registry.updateSavingsGoal(
                                    editingNow.id, name, targetSat, currentSat, color,
                                    deadlineUnix, null, pctOfNw, bucketKind, bucketRef,
                                    goalKind,
                                )
                                editingNow.id
                            } else {
                                registry.addSavingsGoal(
                                    name, targetSat, currentSat, color, deadlineUnix, null,
                                    pctOfNw, bucketKind, bucketRef, goalKind,
                                )
                            }
                            registry.setSavingsGoalDenomination(
                                targetId,
                                denomination,
                                fiatAmount,
                                fiatCurrency,
                            )
                        }
                    }.onFailure { e ->
                        lastError = e.message ?: getString(Res.string.trk_err_save_goal)
                    }
                    showNewGoal = false
                    goalDialog = null
                    RegistryStore.bumpDataVersion()
                }
            },
            onDelete = { id ->
                val registry = RegistryStore.current()
                if (registry == null) {
                    lastError = registryNotOpen
                    showNewGoal = false
                    goalDialog = null
                    return@GoalEditDialog
                }
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) { registry.deleteSavingsGoal(id) }
                    }.onFailure { e ->
                        lastError = e.message ?: getString(Res.string.trk_err_delete_goal)
                    }
                    showNewGoal = false
                    goalDialog = null
                    RegistryStore.bumpDataVersion()
                }
            },
        )
    }

    // Phase 4.4 — bill create/edit dialog. `showNewBill` opens it in
    // create mode (`editing = null`); `billDialog` carries the row to
    // edit. Both share a single render path so the dialog state is
    // exclusive (you can't open both at once).
    if (showNewBill || billDialog != null) {
        BillEditDialog(
            editing = billDialog,
            onDismiss = {
                showNewBill = false
                billDialog = null
            },
            onSave = { name, amountSat, dueDay, displayLabel ->
                val registry = RegistryStore.current()
                if (registry == null) {
                    lastError = registryNotOpen
                    showNewBill = false
                    billDialog = null
                    return@BillEditDialog
                }
                val editingNow = billDialog
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            if (editingNow != null) {
                                registry.updateBill(
                                    editingNow.id, name, amountSat, dueDay.toUInt(),
                                    displayLabel,
                                )
                            } else {
                                registry.addBill(name, amountSat, dueDay.toUInt(), displayLabel)
                            }
                        }
                    }.onFailure { e ->
                        lastError = e.message ?: getString(Res.string.trk_err_save_bill)
                    }
                    showNewBill = false
                    billDialog = null
                    RegistryStore.bumpDataVersion()
                }
            },
            onDelete = { id ->
                val registry = RegistryStore.current()
                if (registry == null) {
                    lastError = registryNotOpen
                    showNewBill = false
                    billDialog = null
                    return@BillEditDialog
                }
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) { registry.deleteBill(id) }
                    }.onFailure { e ->
                        lastError = e.message ?: getString(Res.string.trk_err_delete_bill)
                    }
                    showNewBill = false
                    billDialog = null
                    RegistryStore.bumpDataVersion()
                }
            },
        )
    }

    // Phase 4.3 — inline budget edit dialog. Opens when the user
    // taps the budgeted-amount chip on a BudgetBreakdownCard row.
    // Save / clear go through the registry FFI; reload pulls the
    // fresh list back into the cached `budgets` state.
    val activeBudgetEdit = budgetEdit
    if (activeBudgetEdit != null) {
        BudgetEditDialog(
            category = activeBudgetEdit.first,
            currentBudgetSat = activeBudgetEdit.second,
            onDismiss = { budgetEdit = null },
            onSave = { sat ->
                val registry = RegistryStore.current()
                if (registry == null) {
                    lastError = registryNotOpen
                    budgetEdit = null
                    return@BudgetEditDialog
                }
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            registry.setBudget(activeBudgetEdit.first, sat, "monthly")
                        }
                    }.onFailure { e ->
                        lastError = e.message ?: getString(Res.string.trk_err_save_budget)
                    }
                    budgetEdit = null
                    RegistryStore.bumpDataVersion()
                }
            },
            onClear = {
                val registry = RegistryStore.current()
                if (registry == null) {
                    lastError = registryNotOpen
                    budgetEdit = null
                    return@BudgetEditDialog
                }
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            registry.deleteBudget(activeBudgetEdit.first)
                        }
                    }.onFailure { e ->
                        lastError = e.message ?: getString(Res.string.trk_err_clear_budget)
                    }
                    budgetEdit = null
                    RegistryStore.bumpDataVersion()
                }
            },
        )
    }

    if (showCommandPalette) {
        // Phase 3.13 — bound action list for the Tracking surface.
        // Actions are evaluated by id so the palette can render them
        // even when the underlying state changes between recompositions.
        val labelByWalletIdForExport = wallets.associate { it.id to it.label }
        val sectionActions = TrackerSection.entries.mapIndexed { idx, s ->
            CommandAction(
                id = "section-${s.wire}",
                label = stringResource(Res.string.trk_palette_go_to, s.label),
                glyph = "${idx + 1}",
                shortcutHint = "${idx + 1}",
                keywords = listOf(s.wire, s.label.lowercase(), "vai", "go"),
                invoke = { onSectionChange(s) },
            )
        }
        // UX review 2026-10 (items 13/14) — privacy toggle + Security Hub.
        val privacyHidden by SettingsStore.balancesHidden
        val privacyLabel = stringResource(
            if (privacyHidden) Res.string.privacy_show_amounts else Res.string.privacy_hide_amounts,
        )
        val hubLabel = stringResource(Res.string.security_hub_open)
        val privacyActions = listOfNotNull(
            CommandAction(
                id = "privacy-toggle",
                label = privacyLabel,
                glyph = "◐",
                shortcutHint = "Ctrl+Shift+H",
                keywords = listOf("privacy", "hide", "amounts", "nascondi", "importi"),
                invoke = { SettingsStore.toggleBalancesHidden() },
            ),
            onOpenSecurityHub?.let { open ->
                CommandAction(
                    id = "security-hub",
                    label = hubLabel,
                    glyph = "⛨",
                    keywords = listOf("security", "backup", "sicurezza", "pin", "tor"),
                    invoke = open,
                )
            },
        )
        val actions = sectionActions + privacyActions + listOf(
            CommandAction(
                id = "refresh",
                label = stringResource(Res.string.trk_palette_refresh),
                glyph = "↻",
                shortcutHint = "R",
                keywords = listOf("refresh", "sync", "aggiorna"),
                invoke = {
                    if (!refreshing) {
                        scope.launch { TrackerCache.refresh(syncEsplora = true) }
                    }
                },
            ),
            CommandAction(
                id = "clear-filters",
                label = stringResource(Res.string.trk_palette_clear_filters),
                glyph = "✕",
                shortcutHint = "Esc",
                keywords = listOf("clear", "reset", "filters", "filtri"),
                invoke = {
                    categoryFilter = null
                    timeRangeFilter = null
                },
            ),
            CommandAction(
                id = "save-view",
                label = stringResource(Res.string.trk_palette_save_view),
                glyph = "★",
                keywords = listOf("save", "salva", "view", "vista", "bookmark"),
                invoke = { showSaveDialog = true },
            ),
            CommandAction(
                id = "export-csv",
                label = stringResource(Res.string.trk_palette_export_csv),
                glyph = "↓",
                keywords = listOf("export", "csv", "esporta", "download"),
                invoke = {
                    scope.launch {
                        val saved = csvExportAction(filteredEvents, labelByWalletIdForExport)
                        if (saved != null) lastError = saved
                    }
                },
            ),
        ) + TrackingPeriod.entries.map { p ->
            CommandAction(
                id = "period-${p.name}",
                label = stringResource(Res.string.trk_palette_period, p.label),
                glyph = p.label,
                keywords = listOf("period", "periodo", p.label, p.name),
                invoke = { period = p },
            )
        }
        CommandPalette(
            actions = actions,
            onDismiss = { showCommandPalette = false },
        )
    }

    if (showSaveDialog) {
        SaveViewDialog(
            onDismiss = { showSaveDialog = false },
            onSave = { name ->
                val registry = RegistryStore.current()
                if (registry == null) {
                    lastError = registryNotOpen
                    showSaveDialog = false
                    return@SaveViewDialog
                }
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            registry.saveView(
                                name = name,
                                period = period.name,
                                categoryFilter = categoryFilter,
                                timeRangeStart = timeRangeFilter?.first,
                                timeRangeEnd = timeRangeFilter?.second,
                            )
                        }
                    }.onFailure { e ->
                        lastError = e.message ?: getString(Res.string.trk_err_save_view)
                    }
                    showSaveDialog = false
                    RegistryStore.bumpDataVersion()
                }
            },
        )
    }

    val editingEvent = editing
    if (editingEvent != null) {
        // BTC average cost — price / exclusion of this transaction, for
        // mainnet wallets once a fiat currency is chosen.
        val fiatNow by SettingsStore.fiatCurrency
        val costBasisInputs by TrackerCache.costBasisInputs
        val costBasisRates by io.bhodl.desktop.data.BtcCostBasisRates.rates
        val costBasisInput = costBasisInputs[editingEvent.txidOrPaymentId]
        val editingDay = editingEvent.timestampUnix?.let { io.bhodl.desktop.domain.utcDay(it.toLong()) }
        val costBasisEnabled = fiatNow != io.bhodl.desktop.prices.FiatCurrency.NONE &&
            editingEvent.walletId in costBasisWalletIds(wallets, emptySet())
        LaunchedEffect(editingEvent.txidOrPaymentId, fiatNow) {
            if (costBasisEnabled && editingDay != null) {
                io.bhodl.desktop.data.BtcCostBasisRates.ensure(setOf(editingDay), fiatNow.code)
            }
        }
        CategoryEditDialog(
            event = editingEvent,
            knownCategories = categories,
            costBasis = if (costBasisEnabled) {
                CostBasisEditState(
                    currency = fiatNow.code,
                    userPrice = costBasisInput?.pricePerBtc
                        ?.takeIf { costBasisInput.currency.equals(fiatNow.code, ignoreCase = true) },
                    excluded = costBasisInput?.excluded ?: false,
                    marketPrice = editingDay?.let { costBasisRates[it to fiatNow.code] },
                )
            } else {
                null
            },
            onCostBasisSave = { price, excludedTx ->
                val registry = RegistryStore.current() ?: return@CategoryEditDialog
                // Keep writing the row the cache already has for this txid
                // (it may sit on the other leg of the transaction).
                val walletId = costBasisInput?.walletId ?: editingEvent.walletId
                val currency = fiatNow.code
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            registry.setEventCostBasis(
                                walletId = walletId,
                                txidOrPaymentId = editingEvent.txidOrPaymentId,
                                pricePerBtc = price,
                                currency = price?.let { currency },
                                excluded = excludedTx,
                            )
                        }
                    }.onFailure { e ->
                        lastError = e.message ?: getString(Res.string.trp_cb_err_save)
                    }
                    RegistryStore.bumpDataVersion()
                }
            },
            onDismiss = { editing = null },
            onSave = { newCategory ->
                val registry = RegistryStore.current()
                if (registry == null) {
                    lastError = registryNotOpen
                    editing = null
                    return@CategoryEditDialog
                }
                // UX review 2026-10 (item 11) — offer "Always do this?"
                // when the edit is a real re-categorisation no rule
                // already covers.
                val offerRule = shouldOfferRuleFromEdit(
                    event = editingEvent,
                    oldCategory = editingEvent.category,
                    newCategory = newCategory,
                    rules = rules,
                    payee = walletLabelById[editingEvent.walletId],
                )
                scope.launch {
                    val saved = runCatching {
                        withContext(Dispatchers.IO) {
                            saveEventCategory(registry, editingEvent, newCategory)
                        }
                    }.onFailure { e ->
                        lastError = e.message ?: getString(Res.string.trk_err_save_category)
                    }.isSuccess
                    editing = null
                    if (saved && offerRule) {
                        ruleFromEdit = editingEvent to newCategory.trim()
                    }
                    RegistryStore.bumpDataVersion()
                }
            },
            // Phase 7.10 — every chip tap commits immediately. The
            // separate flow keeps the Save button semantics scoped
            // to the category input only.
            onCryptoEventKindChange = { kind ->
                val registry = RegistryStore.current() ?: return@CategoryEditDialog
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            registry.setEventCryptoKind(
                                walletId = editingEvent.walletId,
                                txidOrPaymentId = editingEvent.txidOrPaymentId,
                                kind = kind.wireValue,
                            )
                        }
                    }.onFailure { e ->
                        lastError = e.message ?: getString(Res.string.trk_err_save_event_kind)
                    }
                    RegistryStore.bumpDataVersion()
                }
            },
        )
    }

    val pendingRule = ruleFromEdit
    if (pendingRule != null) {
        val (ruleEvent, ruleCategory) = pendingRule
        RuleFromEditPanel(
            event = ruleEvent,
            category = ruleCategory,
            walletLabel = walletLabelById[ruleEvent.walletId],
            events = events,
            payeeOf = { walletLabelById[it.walletId] },
            onDismiss = { ruleFromEdit = null },
            onCreate = { draft, applyTo ->
                val registry = RegistryStore.current()
                ruleFromEdit = null
                if (registry == null) {
                    lastError = registryNotOpen
                    return@RuleFromEditPanel
                }
                val priority = topRulePriority(rules)
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            registry.addRule(
                                draft.name, draft.conditionField, draft.conditionOp,
                                draft.conditionValue, "category", draft.category,
                                priority, true,
                            )
                            // Only uncategorised rows (see ruleDraftMatches):
                            // a hand-set category is never overwritten.
                            for (ev in applyTo) {
                                registry.setEventCategory(
                                    ev.walletId, ev.txidOrPaymentId, draft.category,
                                )
                            }
                        }
                    }.onFailure { e ->
                        lastError = e.message ?: getString(Res.string.trk_err_create_rule)
                    }
                    RegistryStore.bumpDataVersion()
                }
            },
        )
    }
}

public object TrackingTags {
    public const val REFRESH: String = "tracking.refresh"
    public const val EXPORT_CSV: String = "tracking.export-csv"
    /** Phase 3.4 — chip on each PaymentEventRow. */
    public const val CATEGORY_CHIP: String = "tracking.category-chip"
    /** Inline category editor dialog (text field). */
    public const val CATEGORY_INPUT: String = "tracking.category-input"
    /** Inline category editor dialog (save button). */
    public const val CATEGORY_SAVE: String = "tracking.category-save"
    /** Inline editor — BTC average-cost price field. */
    public const val COST_BASIS_PRICE: String = "tracking.cost-basis-price"
    /** Inline editor — "exclude from BTC average cost" toggle. */
    public const val COST_BASIS_EXCLUDE: String = "tracking.cost-basis-exclude"
    /** Inline category editor dialog (clear button). */
    public const val CATEGORY_CLEAR: String = "tracking.category-clear"
    /** Filter chip — "Tutte" entry above History. */
    public const val FILTER_ALL: String = "tracking.filter-all"
    /** Filter chip — "Senza categoria" entry above History. */
    public const val FILTER_NONE: String = "tracking.filter-none"
    /** Phase 3.5 — tab switch test tags. */
    public const val TAB_DASHBOARD: String = "tracking.tab-dashboard"
    public const val TAB_HISTORY: String = "tracking.tab-history"
    /** Period chip prefix — full tag is `tracking.period-<label>`. */
    public const val PERIOD_PREFIX: String = "tracking.period-"
    /** Phase 3.6 — data-state strip warning chip. */
    public const val STALE_CHIP: String = "tracking.stale-chip"
    /** Phase 3.6 — clear-time-range chip on the History tab. */
    public const val RANGE_CLEAR: String = "tracking.range-clear"
    /** Phase 3.7 — Reports tab switch. */
    public const val TAB_REPORTS: String = "tracking.tab-reports"
    /** Phase 4.0 — Analytics tab switch (charts moved from Dashboard). */
    public const val TAB_ANALYTICS: String = "tracking.tab-analytics"
    /** Phase 3.8 — YoY compare toggle. */
    public const val COMPARE_TOGGLE: String = "tracking.compare-toggle"
    /** Phase 3.8 — saved-view chip strip. */
    public const val VIEW_SAVE: String = "tracking.view-save"
    public const val VIEW_NAME_INPUT: String = "tracking.view-name-input"
    /** Phase 3.14 — include / exclude wallet from cross-wallet totals. */
    public const val WALLET_INCLUDE_TOGGLE: String = "tracking.wallet-include"

    /** Phase 9.0 — header bar affordances (merged TrackerHome rail). */
    public const val HEADER_MODULES: String = "tracking.header-modules"
    public const val HEADER_LOCK: String = "tracking.header-lock"
    /** Focusable root receiving the keyboard shortcuts. */
    public const val ROOT: String = "tracking.root"
}
