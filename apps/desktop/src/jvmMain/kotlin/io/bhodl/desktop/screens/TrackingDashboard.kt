package io.bhodl.desktop.screens
import io.bhodl.shared.resources.bgm_month_left
import io.bhodl.desktop.domain.RecurringCandidate
import io.bhodl.desktop.domain.dayOfMonth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.ui.bhodlCard
import io.bhodl.desktop.ui.displayAmount
import io.bhodl.desktop.ui.rememberAmountFormatter
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.chart_name_activity
import io.bhodl.shared.resources.chart_name_sankey
import io.bhodl.shared.resources.chart_sankey_saved
import io.bhodl.shared.resources.chart_sankey_total
import org.jetbrains.compose.resources.stringResource
import io.bhodl.desktop.ui.displaySignedAmount
import io.bhodl.ffi.PaymentDirection
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ui.theme.BhodlTheme
import io.bhodl.shared.resources.trk_chart_activity_calendar
import io.bhodl.shared.resources.trk_chart_balance
import io.bhodl.shared.resources.trk_chart_by_category
import io.bhodl.shared.resources.trk_chart_expense_breakdown
import io.bhodl.shared.resources.trk_chart_expense_by_category
import io.bhodl.shared.resources.trk_chart_flow
import io.bhodl.shared.resources.trk_chart_in_vs_out
import io.bhodl.shared.resources.trk_chart_income_breakdown
import io.bhodl.shared.resources.trk_dashboard_empty
import io.github.koalaplot.core.ChartLayout
import io.github.koalaplot.core.bar.DefaultVerticalBar
import io.github.koalaplot.core.bar.GroupedVerticalBarPlot
import io.github.koalaplot.core.legend.FlowLegend
import io.github.koalaplot.core.legend.LegendLocation
import io.github.koalaplot.core.line.LinePlot
import io.github.koalaplot.core.pie.DefaultSlice
import io.github.koalaplot.core.pie.PieChart
import io.github.koalaplot.core.style.LineStyle
import io.github.koalaplot.core.util.ExperimentalKoalaPlotApi
import io.github.koalaplot.core.xygraph.CategoryAxisModel
import io.github.koalaplot.core.xygraph.DefaultPoint
import io.github.koalaplot.core.xygraph.FloatLinearAxisModel
import io.github.koalaplot.core.xygraph.LongLinearAxisModel
import io.github.koalaplot.core.xygraph.XYGraph
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import io.bhodl.shared.resources.trk_chart_activity_scatter

/**
 * Phase 3.5 — Tracking dashboard tab (period + KPI + charts) and the
 * pure aggregation helpers it consumes.
 *
 * The Composable entry point is [TrackingDashboardTab]; the supporting
 * pure aggregations live in `TrackingAggregations.kt`, the chart
 * composables in `TrackingCharts.kt`, and the KPI/period/saved-view
 * widgets in `TrackingKpi.kt` — all in the same `screens` package.
 */

/**
 * Entry point for the Dashboard tab. Owns nothing — period state
 * lives in [TrackingScreen] so switching tabs preserves the
 * selection. Charts and KPIs are populated in a follow-up slice;
 * this composable currently renders the period selector plus the
 * KPI cards driven by [computeKpis], so the dependency wiring (and
 * the test tags) ship before the visualisations.
 */
/**
 * Phase 4.8 → 9.0 — multi-purpose section content. One composable,
 * one mode per tracker section, sharing the period / event / registry
 * state-load plumbing:
 *
 *  - **HOME** — KPI strip + NW breakdown donut + inflow/outflow
 *    synthesis (the hero + wallet list render separately via
 *    [TrackerHomeSection])
 *  - **BUDGET** — income/expense breakdowns + recurring detection +
 *    bills + expense lollipop, with the rules engine demoted to an
 *    "Automation" block at the bottom (config, not monitoring)
 *  - **GOALS** — savings/sinking goals
 *  - **PORTFOLIO** — asset rollup + investments + manual assets +
 *    liabilities/net-worth summary
 *  - **ANALYTICS** — power-user charts (Sankey, scatter, heatmap,
 *    balance line, donut/treemap, YoY compare), rendered at the
 *    bottom of the REPORTS section for Analytics Labs users
 */
internal enum class TrackingTabMode { HOME, BUDGET, GOALS, PORTFOLIO, ANALYTICS }

@Composable
internal fun TrackingDashboardTab(
    events: List<PaymentEvent>,
    /** Open the transaction editor (category, kind, BTC cost price). */
    onEditEvent: (PaymentEvent) -> Unit = {},
    period: TrackingPeriod,
    onPeriodChange: (TrackingPeriod) -> Unit,
    currentBalanceSat: ULong,
    /** Phase 9.0 — selects which section's cards render; see
     *  [TrackingTabMode]. All modes share the same compute. */
    mode: TrackingTabMode = TrackingTabMode.HOME,
    /** Portfolio cards view — "+ Wallet (xpub)". */
    onAddWallet: (() -> Unit)? = null,
    /** Phase 3.6 — invoked when the user clicks a donut slice. The
     *  parameter is the category label ("" for the Uncategorised
     *  bucket); the host switches to the History tab and applies
     *  it as the active filter. */
    onCategoryDrillDown: (String) -> Unit = {},
    /** Phase 3.6 — invoked when the user clicks a bar bucket chip.
     *  Receives the inclusive (startUnix, endUnix) range; the host
     *  switches to the History tab and applies it as a time-range
     *  filter. */
    onBucketDrillDown: (Pair<Long, Long>) -> Unit = {},
    /** Phase 3.6 — when the registry reload is in flight AND there
     *  are no cached events to display, render skeleton placeholders
     *  in place of the empty-state message so the layout doesn't
     *  jump on first paint. */
    refreshing: Boolean = false,
    /** Phase 4.3 — per-category budgets pulled from registry. Empty
     *  list when the user hasn't set any budget yet. */
    budgets: List<io.bhodl.ffi.TrackingBudgetFfi> = emptyList(),
    /** Phase 4.3 — open the inline budget edit dialog for a category.
     *  The host owns the dialog state. */
    onEditBudget: (category: String, currentBudgetSat: ULong?) -> Unit = { _, _ -> },
    /** Phase 4.4 — recurring bills snapshot. */
    bills: List<io.bhodl.ffi.TrackingBillFfi> = emptyList(),
    /** Phase 4.4 — open the new-bill dialog. */
    onAddBill: () -> Unit = {},
    /** Phase 4.4 — open the edit-bill dialog for an existing row. */
    onEditBill: (io.bhodl.ffi.TrackingBillFfi) -> Unit = {},
    /** Phase 4.4 — toggle paid status; host updates registry. */
    onToggleBillPaid: (io.bhodl.ffi.TrackingBillFfi, Boolean) -> Unit = { _, _ -> },
    /** Phase 4.5 — savings goals snapshot. */
    goals: List<io.bhodl.ffi.TrackingSavingsGoalFfi> = emptyList(),
    onAddGoal: () -> Unit = {},
    onEditGoal: (io.bhodl.ffi.TrackingSavingsGoalFfi) -> Unit = {},
    /** Phase 4.6 — liabilities snapshot. */
    liabilities: List<io.bhodl.ffi.TrackingLiabilityFfi> = emptyList(),
    onAddLiability: () -> Unit = {},
    onEditLiability: (io.bhodl.ffi.TrackingLiabilityFfi) -> Unit = {},
    /** Phase 5.1 — investments snapshot. */
    investments: List<io.bhodl.ffi.TrackingInvestmentFfi> = emptyList(),
    onAddInvestment: () -> Unit = {},
    onEditInvestment: (io.bhodl.ffi.TrackingInvestmentFfi) -> Unit = {},
    /** Phase 7.8 — lots grouped by parent investment id. Empty when
     *  no investment is on the multi-lot model yet. */
    investmentLotsByInvestmentId: Map<Long, List<io.bhodl.ffi.TrackingInvestmentLotFfi>> = emptyMap(),
    onAddLot: (investmentId: Long) -> Unit = {},
    onEditLot: (io.bhodl.ffi.TrackingInvestmentLotFfi) -> Unit = {},
    /** Phase 7.9 — sells + disposals grouped by parent investment id. */
    investmentSellsByInvestmentId: Map<Long, List<io.bhodl.ffi.TrackingInvestmentSellFfi>> = emptyMap(),
    lotDisposalsByInvestmentId: Map<Long, List<io.bhodl.ffi.TrackingLotDisposalFfi>> = emptyMap(),
    onSell: (investmentId: Long) -> Unit = {},
    onDeleteSell: (io.bhodl.ffi.TrackingInvestmentSellFfi) -> Unit = {},
    /** Portfolio redesign 2026-10 — open the kind-specific details editor. */
    onEditDetails: (DetailsEditRequest) -> Unit = {},
    /** Portfolio redesign 2026-10 — initial view of the Portfolio page. */
    initialPortfolioView: PortfolioView = PortfolioView.CARDS,
    /** Phase 7.4 — manual asset snapshot, used by both the
     *  [ManualAssetsCard] under the dashboard and the savings-goal
     *  MANUAL_ASSET bucket sub-picker. */
    manualAssets: List<io.bhodl.ffi.TrackingManualAssetFfi> = emptyList(),
    onAddManualAsset: () -> Unit = {},
    onEditManualAsset: (io.bhodl.ffi.TrackingManualAssetFfi) -> Unit = {},
    /** Phase 7.2.4 — registry snapshot of wallets, used by the savings-
     *  goals card to (a) render the AUTO badge with the wallet name
     *  when a goal binds to a specific wallet and (b) drive the WALLET
     *  bucket sub-picker in the edit dialog. */
    wallets: List<io.bhodl.ffi.WalletRecordFfi> = emptyList(),
    /** Phase 7.2.4 — per-wallet balance map, also from the TrackerCache.
     *  Used to derive `current_sat` for `bucket_kind = 'wallet'`. */
    watchBalances: Map<String, ULong> = emptyMap(),
    /** Phase 7.2.4 — fiat → sat conversion used by the `investments`
     *  bucket. Pluggable for tests; in production this is
     *  `PriceCache::fiatToSat`. */
    fiatToSat: (Double) -> ULong? = { null },
    /** Per-currency `1 BTC = N <ccy>` lookup (production:
     *  `PriceCache::rateFor`) so goal buckets convert each investment /
     *  manual asset at its own currency's rate. Null in tests keeps the
     *  legacy dashboard-currency [fiatToSat] conversion. */
    rateFor: ((String) -> Double?)? = null,
    /** Phase 7.3 — aggregate investments value in sat, used so that
     *  the savings-goal card can compute "30% of NW" against the full
     *  net worth (BTC + investments) rather than BTC-only. Sourced
     *  from `TrackerCache.investmentsAssetSat`. Defaults to 0 in tests
     *  where investments aren't being modeled. */
    totalInvestmentsSat: ULong = 0UL,
    /** Phase 7.4 — aggregate manual asset value in sat, used by the
     *  same "30% of NW" math. Sourced from
     *  `TrackerCache.manualAssetsSat`. Defaults to 0. */
    totalManualAssetsSat: ULong = 0UL,
    /** Phase 7.5 — rules snapshot powering the RulesCard. */
    rules: List<io.bhodl.ffi.TrackingRuleFfi> = emptyList(),
    onAddRule: () -> Unit = {},
    onEditRule: (io.bhodl.ffi.TrackingRuleFfi) -> Unit = {},
    onReapplyRules: () -> Unit = {},
    /** UX review 2026-10 (item 11) — move a rule up (−1) / down (+1). */
    onMoveRule: (io.bhodl.ffi.TrackingRuleFfi, Int) -> Unit = { _, _ -> },
    /** True while a reorder is being written: move controls disabled. */
    reorderingRules: Boolean = false,
    /** Phase 7.5 — recurring candidates from the detector. */
    recurringCandidates: List<RecurringCandidate> = emptyList(),
    onAcceptRecurring: (RecurringCandidate) -> Unit = {},
    onDismissRecurring: (RecurringCandidate) -> Unit = {},
    onRefreshPrices: () -> Unit = {},
) {
    // Snapshot in seconds so the same `now` drives both the period
    // filter and the synthetic anchor inside `balanceSeries`.
    val nowUnix = System.currentTimeMillis() / 1000L
    val inPeriod = filterByPeriod(events, period, nowUnix)
    val kpis = computeKpis(inPeriod, currentBalanceSat)
    // Phase 3.11 — confirmed vs pending split derived from the full
    // event stream (not period-scoped: a 7g view shouldn't hide a
    // mempool tx that landed in the wallet 10 days ago).
    val balanceSplit = splitBalance(currentBalanceSat, events)
    val series = balanceSeries(events, period, nowUnix)
    val slices = categorySlices(inPeriod)
    val bars = flowBars(inPeriod, period, nowUnix)
    // Phase 3.8 — YoY overlay toggle. Defaults off because the
    // comparison only makes sense when there's enough history.
    var compareYoY by remember { mutableStateOf(false) }
    // Phase 3.9 — donut vs treemap toggle.
    var categoryView by remember { mutableStateOf(CategoryViewMode.DONUT) }
    // Phase 3.12 — stepped vs smoothed line variant for the Balance
    // chart. Stepped is more honest for ledger-based data (a wallet
    // balance changes in jumps, not linearly between sample points),
    // but smoothed is easier to read at a glance for slow periods.
    var balanceLineMode by remember { mutableStateOf(BalanceLineMode.SMOOTHED) }
    val previousSeries = remember(events, period, compareYoY) {
        if (compareYoY) balanceSeriesPrevious(events, period, nowUnix)
        else emptyList()
    }
    val prevKpis = remember(events, period, compareYoY) {
        if (!compareYoY) null
        else {
            val days = period.days ?: return@remember null
            val span = days.toLong() * 86_400L
            val prevLower = nowUnix - 2L * span
            val prevUpper = nowUnix - span
            val prevEvents = events.filter { e ->
                val ts = e.timestampUnix?.toLong() ?: return@filter false
                ts in prevLower..prevUpper
            }
            computeKpis(prevEvents, currentBalanceSat)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Phase 9.0 — KPI strip leads the HOME synthesis; the other
        // sections lead with their own cards and Reports/Analytics
        // leads with the YoY toggle + charts.

        if (mode == TrackingTabMode.ANALYTICS) {
            // Phase 3.8 — YoY toggle row. Hidden on ALL (no "previous"
            // period to compare against — span is the whole history).
            if (period != TrackingPeriod.ALL) {
                CompareToggle(
                    checked = compareYoY,
                    onCheckedChange = { compareYoY = it },
                )
            }
        }

        if (events.isEmpty()) {
            if (refreshing) {
                // Skeleton mimics the populated layout: a tall card
                // for the line chart and two shorter ones for bars
                // and donut. Only relevant on the Analytics tab —
                // Dashboard has its own empty-state copy below.
                if (mode == TrackingTabMode.ANALYTICS) {
                    ChartCard(title = stringResource(Res.string.trk_chart_balance, period.label)) {
                        SkeletonBlock(modifier = Modifier.fillMaxWidth(), height = 200.dp)
                    }
                    ChartCard(title = stringResource(Res.string.trk_chart_in_vs_out, period.label)) {
                        SkeletonBlock(modifier = Modifier.fillMaxWidth(), height = 160.dp)
                    }
                    ChartCard(title = stringResource(Res.string.trk_chart_by_category, period.label)) {
                        SkeletonBlock(modifier = Modifier.fillMaxWidth(), height = 160.dp)
                    }
                }
            } else if (mode == TrackingTabMode.ANALYTICS) {
                Text(
                    text = stringResource(Res.string.trk_dashboard_empty),
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textTertiary,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
            // Dashboard mode keeps rendering Wealth Builder sections
            // (Bills / Goals / Net Worth) even with zero events so the
            // user can add bills / goals / liabilities before any
            // transaction lands.
            if (mode == TrackingTabMode.ANALYTICS) return@Column
        }

        // UX review 2026-10 (B4) — budgets are prorated to the length
        // of the selected window (a monthly budget over 90g is ~2.96×),
        // and (B3) each budget lives in the direction its category's
        // full history belongs to.
        // Budget redesign 2026-10 — the Budget section is monthly: the
        // calendar month so far against the full monthly budget, whatever
        // the period chosen elsewhere.
        val budgetMonth = mode == TrackingTabMode.BUDGET
        val budgetToday = todayLocal()
        val budgetEvents = remember(budgetMonth, inPeriod, events, budgetToday) {
            if (budgetMonth) eventsThisMonth(events, budgetToday) else inPeriod
        }
        val budgetDays = remember(period, budgetEvents, budgetMonth) {
            if (budgetMonth) DAYS_PER_MONTH else budgetWindowDays(period, budgetEvents, nowUnix)
        }
        val incomeRows = remember(budgetEvents, events, budgets, budgetDays) {
            computeBudgetRows(budgetEvents, budgets, TrackingDirection.INCOME, budgetDays, events)
        }
        val expenseRows = remember(budgetEvents, events, budgets, budgetDays) {
            computeBudgetRows(budgetEvents, budgets, TrackingDirection.EXPENSE, budgetDays, events)
        }
        // Phase 9.0 — module gating moved up to the NavigationRail
        // (a disabled module's section simply has no rail entry), so
        // the per-card `if (modX)` checks are gone. Shared rollups
        // computed once for the HOME donut and the PORTFOLIO cards.
        // UX review 2026-10 (B1) — one shared net-worth computation for
        // the HOME hero, the HOME donut and the PORTFOLIO cards.
        val netWorth = rememberTrackerNetWorth(
            walletSat = currentBalanceSat,
            investments = investments,
            lotsByInvestmentId = investmentLotsByInvestmentId,
            cachedInvestmentsSat = totalInvestmentsSat,
            manualAssets = manualAssets,
            cachedManualAssetsSat = totalManualAssetsSat,
            liabilities = liabilities,
        )
        val excludedWalletIds by SettingsStore.excludedWalletIds

        if (mode == TrackingTabMode.HOME) {
            // Home redesign 2026-10 — the whole Home (hero, composition,
            // to-do, KPIs, flows, forecast, wallets) is TrackerHomeSection.
            return@Column
        }

        if (mode == TrackingTabMode.BUDGET) {
            // UX review 2026-10 (item 12) — daily BTC prices over the
            // current + previous window, to split each budget's move
            // into price vs spending. Fixed windows only (ALL has no
            // "previous" window); no fiat currency → no split.
            val fiatCurrency by SettingsStore.fiatCurrency
            var priceHistory by remember { mutableStateOf<List<io.bhodl.desktop.prices.HistoricalPricePoint>>(emptyList()) }
            // The month so far against the same stretch of the month before.
            val monthDays = budgetToday.dayOfMonth
            LaunchedEffect(monthDays, fiatCurrency) {
                val days: Int? = monthDays
                priceHistory = if (days == null) {
                    emptyList()
                } else {
                    io.bhodl.desktop.prices.PriceCache.priceHistory(fiatCurrency, days * 2).orEmpty()
                }
            }
            fun movesFor(rows: List<BudgetRow>, direction: TrackingDirection): Map<String, BudgetMove> {
                val days = monthDays
                if (priceHistory.isEmpty()) return emptyMap()
                val rateAt = rateLookup(priceHistory)
                return rows.filter { it.budgetForPeriodSat != null }.mapNotNull { r ->
                    explainBudgetMove(r.category, direction, events, nowUnix, days, rateAt)
                        ?.let { r.category to it }
                }.toMap()
            }
            val incomeMoves = remember(incomeRows, priceHistory, events) {
                movesFor(incomeRows, TrackingDirection.INCOME)
            }
            val expenseMoves = remember(expenseRows, priceHistory, events) {
                movesFor(expenseRows, TrackingDirection.EXPENSE)
            }
            // Budget redesign 2026-10 (proposal C): summary rings, one card
            // per category, the 30-day bills strip, then recurring and rules.
            val summary = remember(expenseRows, expenseMoves) { budgetSummary(expenseRows, expenseMoves) }
            val incomeBudget = incomeRows.filter { it.budgetForPeriodSat != null }
            val incomeFraction = incomeBudget.sumOf { it.budgetForPeriodSat!!.toLong() }.takeIf { it > 0L }
                ?.let { incomeBudget.sumOf { r -> r.actualSat.toLong() }.toFloat() / it }
            val monthLabel = budgetToday.month.getDisplayName(java.time.format.TextStyle.FULL_STANDALONE, java.util.Locale.getDefault())
                .replaceFirstChar { it.titlecase(java.util.Locale.getDefault()) } + " " + budgetToday.year
            val monthCaption = stringResource(Res.string.bgm_month_left, monthLabel, daysLeftInMonth(budgetToday))
            BudgetSummaryCard(summary = summary, incomeFraction = incomeFraction, periodLabel = monthCaption, modifier = Modifier.fillMaxWidth())
            if (expenseRows.isNotEmpty()) {
                BudgetCategoryGrid(
                    rows = expenseRows,
                    moves = expenseMoves,
                    onEditBudget = onEditBudget,
                    onViewTransactions = onCategoryDrillDown,
                )
            }
            BillsCalendarStrip(
                bills = bills,
                today = todayLocal(),
                onToggleBillPaid = onToggleBillPaid,
                onEditBill = onEditBill,
                onAddBill = onAddBill,
                modifier = Modifier.fillMaxWidth(),
            )
            if (incomeRows.any { it.budgetForPeriodSat != null }) {
                BudgetBreakdownCard(
                    title = stringResource(Res.string.trk_chart_income_breakdown, monthLabel),
                    rows = incomeRows,
                    onEditBudget = onEditBudget,
                    proratedToLabel = monthLabel,
                    moves = incomeMoves,
                )
            }

            RecurringCard(
                candidates = recurringCandidates,
                onAccept = onAcceptRecurring,
                onDismiss = onDismissRecurring,
            )

            // Automation block — rules are configuration, not
            // monitoring; they close the page instead of crowding it.
            RulesCard(
                rules = rules,
                onAdd = onAddRule,
                onEdit = onEditRule,
                onReapply = onReapplyRules,
                onMove = onMoveRule,
                moveEnabled = !reorderingRules,
            )

            return@Column
        }

        if (mode == TrackingTabMode.GOALS) {
            SavingsGoalsCard(
                goals = goals,
                bitcoinAssetSat = currentBalanceSat,
                // Code review 2026-10 — "% of NW" targets use the TRUE
                // net worth (liabilities subtracted), same as the hero.
                totalNetWorthSat = netWorth.goalBaseSat,
                watchBalances = watchBalances,
                investments = investments,
                wallets = wallets,
                manualAssets = manualAssets,
                fiatToSat = fiatToSat,
                lotsByInvestmentId = investmentLotsByInvestmentId,
                rateFor = rateFor,
                onAdd = onAddGoal,
                onEdit = onEditGoal,
            )
            return@Column
        }

        if (mode == TrackingTabMode.PORTFOLIO) {
            var portfolioView by remember { mutableStateOf(initialPortfolioView) }
            val portfolioDetails by io.bhodl.desktop.data.TrackerCache.portfolioDetails
            val costBasis: @Composable () -> Unit = {
                // BTC average cost of the tracked wallets — the bitcoin
                // counterpart of the investments P&L.
                BtcCostBasisCard(
                    wallets = wallets,
                    excludedWalletIds = excludedWalletIds,
                    onEditEvent = onEditEvent,
                )
            }
            AssetPortfolioCard(
                netWorth = netWorth,
                walletGroups = trackedWalletGroups(wallets, watchBalances, excludedWalletIds),
                investments = investments,
                investmentLotsByInvestmentId = investmentLotsByInvestmentId,
                manualAssets = manualAssets,
                liabilities = liabilities,
                onAddInvestment = onAddInvestment,
                onEditInvestment = onEditInvestment,
                onAddManualAsset = onAddManualAsset,
                onEditManualAsset = onEditManualAsset,
                onAddLiability = onAddLiability,
                onEditLiability = onEditLiability,
                onAddWallet = onAddWallet,
                onRefreshPrices = onRefreshPrices,
                // Item 9 — the change column follows the global period.
                events = events,
                period = period,
                view = portfolioView,
                onViewChange = { portfolioView = it },
                sellsByInvestmentId = investmentSellsByInvestmentId,
                disposalsByInvestmentId = lotDisposalsByInvestmentId,
                details = portfolioDetails,
                onEditDetails = onEditDetails,
                onAddLot = onAddLot,
                onEditLot = onEditLot,
                onSell = onSell,
                onDeleteSell = onDeleteSell,
                // Cards view: the cost-basis card belongs to the Bitcoin class.
                bitcoinExtra = costBasis,
            )

            if (portfolioView == PortfolioView.TABLE) {
                costBasis()
                // Table view: the detail cards below the sheet (lots /
                // sells / P&L and the purchase-time view of manual
                // assets). The cards view carries all of this per item.
                InvestmentsCard(
                    investments = investments,
                    lotsByInvestmentId = investmentLotsByInvestmentId,
                    sellsByInvestmentId = investmentSellsByInvestmentId,
                    disposalsByInvestmentId = lotDisposalsByInvestmentId,
                    onAdd = onAddInvestment,
                    onEdit = onEditInvestment,
                    onAddLot = onAddLot,
                    onEditLot = onEditLot,
                    onSell = onSell,
                    onDeleteSell = onDeleteSell,
                )

                ManualAssetsCard(
                    assets = manualAssets,
                    onAdd = onAddManualAsset,
                    onEdit = onEditManualAsset,
                )
            }

            return@Column
        }

        // Phase 3.8 — calendar heatmap card. Comes after the KPI
        // strip and before the main charts so the user gets an
        // at-a-glance "when did stuff happen this month" before
        // diving into trends.
        ChartCard(title = stringResource(Res.string.trk_chart_activity_calendar), height = null) {
            CalendarHeatmap(
                events = events,
                onDayClick = onBucketDrillDown,
            )
        }

        // Phase 3.12 — Sankey diagram for income → category cash flow.
        // The Monarch-style "where did your money come from / go" chart;
        // absent from every other Bitcoin wallet inspected during UX
        // research (2026-05-16). Only renders when the period has at
        // least one categorised positive and one categorised negative
        // flow — otherwise it would be a single rectangle, which the
        // donut already shows better.
        val sankeyFlow = remember(inPeriod) { buildSankeyFlow(inPeriod) }
        // UX review 2026-10 (item 15) — table view of every chart below,
        // formatted like the rest of the UI (unit, BIP177, privacy mask).
        val chartFmt = rememberAmountFormatter()
        val chartTitles = chartColumnTitles()
        val sankeyTotal = stringResource(Res.string.chart_sankey_total)
        val sankeySaved = stringResource(Res.string.chart_sankey_saved)
        val balanceTable = seriesTable(
            series.map { it.tsUnix to it.balanceSat }, chartTitles.balance, chartTitles, chartFmt,
        )
        val flowsTable = inflowOutflowTable(
            bars.map { b ->
                Triple(
                    formatTimestampTick(b.bucketStartUnix, deriveBarUnit(bars)),
                    b.inflowSat.toLong(),
                    b.outflowSat.toLong(),
                )
            },
            chartTitles,
            chartFmt,
        )
        val categoriesTable = shareTable(
            slices.filter { it.volumeSat > 0u }.map { it.category.ifBlank { "—" } to it.volumeSat.toLong() },
            chartTitles,
            chartFmt,
        )
        if (!sankeyFlow.isEmpty) {
            ChartCard(
                title = stringResource(Res.string.trk_chart_flow, period.label),
                height = null,
                table = sankeyTable(sankeyFlow, sankeyTotal, sankeySaved, chartTitles, chartFmt),
                chartName = stringResource(Res.string.chart_name_sankey),
            ) {
                SankeyDiagram(flow = sankeyFlow)
            }
        }

        // Phase 3.15 — activity scatter (age × amount, log Y). One dot
        // per confirmed event, color-coded by direction. Useful for
        // spotting outlier amounts and activity clusters without
        // scrolling History. Hidden when the period has no confirmed
        // events (mempool-only or fresh wallet) — the empty chart
        // would just be the axes.
        val hasConfirmed = remember(events) { events.any { it.timestampUnix != null } }
        if (hasConfirmed) {
            ChartCard(
                title = stringResource(Res.string.trk_chart_activity_scatter),
                height = null,
                table = activityTable(events, chartTitles, chartFmt),
                chartName = stringResource(Res.string.chart_name_activity),
            ) {
                ActivityScatter(events = events, nowUnix = nowUnix)
            }
        }

        // Phase 3.7 — adaptive 2-column layout. On wide windows
        // (≥ 1100dp) the line chart and the donut share a row;
        // below that, fall back to single-column stacking.
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val twoCols = maxWidth >= 1100.dp
            if (twoCols) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Box(modifier = Modifier.weight(2f)) {
                            ChartCard(
                                title = stringResource(Res.string.trk_chart_balance, period.label),
                                table = balanceTable,
                                headerTrailing = {
                                    BalanceLineModeToggle(
                                        mode = balanceLineMode,
                                        onChange = { balanceLineMode = it },
                                    )
                                },
                            ) {
                                BalanceLineChart(
                                    series = series,
                                    period = period,
                                    previousSeries = previousSeries,
                                    mode = balanceLineMode,
                                )
                            }
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            ChartCard(
                                title = stringResource(Res.string.trk_chart_by_category, period.label),
                                table = categoriesTable,
                                headerTrailing = {
                                    CategoryViewToggle(
                                        mode = categoryView,
                                        onChange = { categoryView = it },
                                    )
                                },
                            ) {
                                if (categoryView == CategoryViewMode.DONUT) {
                                    CategoryDonut(
                                        slices = slices,
                                        onSliceClick = onCategoryDrillDown,
                                    )
                                } else {
                                    CategoryTreemap(
                                        slices = slices,
                                        onTileClick = onCategoryDrillDown,
                                    )
                                }
                            }
                        }
                    }
                    ChartCard(title = stringResource(Res.string.trk_chart_in_vs_out, period.label), table = flowsTable) {
                        FlowsBarChart(bars = bars, period = period)
                    }
                    if (bars.isNotEmpty()) {
                        BucketDrillRow(
                            bars = bars,
                            onSelect = onBucketDrillDown,
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    ChartCard(
                        title = stringResource(Res.string.trk_chart_balance, period.label),
                        table = balanceTable,
                        headerTrailing = {
                            BalanceLineModeToggle(
                                mode = balanceLineMode,
                                onChange = { balanceLineMode = it },
                            )
                        },
                    ) {
                        BalanceLineChart(
                            series = series,
                            period = period,
                            previousSeries = previousSeries,
                            mode = balanceLineMode,
                        )
                    }
                    ChartCard(title = stringResource(Res.string.trk_chart_in_vs_out, period.label), table = flowsTable) {
                        FlowsBarChart(bars = bars, period = period)
                    }
                    if (bars.isNotEmpty()) {
                        BucketDrillRow(
                            bars = bars,
                            onSelect = onBucketDrillDown,
                        )
                    }
                    ChartCard(
                        title = stringResource(Res.string.trk_chart_by_category, period.label),
                        table = categoriesTable,
                        headerTrailing = {
                            CategoryViewToggle(
                                mode = categoryView,
                                onChange = { categoryView = it },
                            )
                        },
                    ) {
                        if (categoryView == CategoryViewMode.DONUT) {
                            CategoryDonut(
                                slices = slices,
                                onSliceClick = onCategoryDrillDown,
                            )
                        } else {
                            CategoryTreemap(
                                slices = slices,
                                onTileClick = onCategoryDrillDown,
                            )
                        }
                    }
                }
            }
        }
    }
}
