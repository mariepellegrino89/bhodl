package io.bhodl.desktop.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.tracker_add_wallet
import io.bhodl.shared.resources.tracker_refresh_balances
import io.bhodl.shared.resources.tracker_refreshing
import io.bhodl.shared.resources.tracker_wallets_header
import io.bhodl.shared.resources.trk_home_loading
import io.bhodl.shared.resources.trk_home_refreshing
import io.bhodl.shared.resources.trk_home_stale
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.data.TrackerCache
import io.bhodl.desktop.prices.NetWorthHistoryClient
import io.bhodl.desktop.prices.NetWorthHistoryClient.NwSnapshot
import io.bhodl.desktop.prices.PriceCache
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.wizard.RegistryStore
import io.bhodl.desktop.wizard.WalletPaths
import io.bhodl.desktop.domain.externalSignedNetSat
import io.bhodl.desktop.domain.computeFinanceKpis
import io.bhodl.desktop.domain.forecastCashFlow
import io.bhodl.desktop.domain.illiquidManualAssetsSat
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.PaymentEventLiteFfi
import io.bhodl.ui.theme.BhodlTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phase 6.0 → 9.0 — Tracker home content, shown right after the
 * device-PIN gate; its wallet table is the wallet picker.
 *
 * Phase 9.0 (IA restructure): no longer a standalone route. The old
 * `TrackerHomeScreen` (own header rail, "Open Dashboard" CTA, own
 * scroll) merged into the tracker's HOME section — this composable
 * renders the hero + wallet list + add-wallet CTAs inside
 * [TrackingScreen]'s scrollable body. The duplicated "three
 * dashboards" hierarchy (TrackerHome → Tracking tab → Dashboard) is
 * gone: one home, hero on top, Kubera/Copilot convention.
 *
 * The Tracker stays decoupled from signing: it only reads data
 * already encrypted under the device PIN — the wallets registry
 * (xpub-only metadata) and the watch-only chain state. A signing
 * wallet contributes to the net worth ONLY if it has been paired as
 * a watch-only entry (B1–B5 in Phase 6).
 *
 * Errors during the load are non-fatal: the section still renders
 * with whatever partial state arrived; a one-line banner explains
 * what failed.
 */
@Composable
internal fun TrackerHomeSection(
    onOpenWallet: (walletId: String) -> Unit,
    onAddWatch: () -> Unit,
    /** The monthly-review banner's "Review" action (opens Reports). */
    onOpenReports: () -> Unit = {},
    /** Home redesign 2026-10 — targets of the composition card and of
     *  the "to do" rows. */
    onOpenPortfolio: () -> Unit = {},
    onOpenBudget: () -> Unit = {},
    onOpenTransactions: () -> Unit = {},
    // Injection seam for Compose UI tests — lets a test point the
    // load path at a tempdir rather than the production data dir.
    // (No longer read since the NW history goes through the open
    // RegistryHandle; kept for call-site compatibility.)
    @Suppress("UNUSED_PARAMETER") dataDir: java.io.File = WalletPaths.defaultDataDir(),
) {
    val scope = rememberCoroutineScope()

    // Phase 7.2.3 — read snapshot from the process-lifetime TrackerCache
    // instead of loading into local remember{}. Tab switches no longer
    // discard the data; the cache keeps refreshing in the background
    // even when this composable is unmounted. Composables become pure
    // views over the cache state.
    val wallets by TrackerCache.wallets
    val watchBalances by TrackerCache.watchBalances
    // Phase 7.3 — asset layers from the cache. The hero's number is the
    // shared net worth built from them below ([rememberTrackerNetWorth]),
    // never the gross `TrackerCache.totalAssetSat`.
    val bitcoinAssetSat by TrackerCache.bitcoinAssetSat
    val investmentsAssetSat by TrackerCache.investmentsAssetSat
    val investmentsPricingAvailable by TrackerCache.investmentsPricingAvailable
    val investments by TrackerCache.investments
    val investmentLots by TrackerCache.investmentLotsByInvestmentId
    val manualAssets by TrackerCache.manualAssets
    val manualAssetsSat by TrackerCache.manualAssetsSat
    // Phase 7.6 — events / bills / recurring drive the KPI strip and
    // the cash-flow forecast card.
    val events by TrackerCache.events
    val bills by TrackerCache.bills
    val recurringCandidates by TrackerCache.recurringCandidates
    val liabilities by TrackerCache.liabilities
    val refreshing by TrackerCache.refreshing
    val lastSyncUnix by TrackerCache.lastSyncUnix
    val hasLoadedOnce by TrackerCache.hasLoadedOnce
    // "loading" is shown only on the very first cold load. Once the
    // cache has data, subsequent refreshes happen silently in place.
    val loading = !hasLoadedOnce && refreshing


    // UX review 2026-10 (B1/B8) — the hero shows the SAME net worth as
    // Portfolio: tracked wallets (exclusion-aware) + investments +
    // manual assets − liabilities, via the shared [computeTrackerNetWorth].
    val excludedWalletIds by SettingsStore.excludedWalletIds
    val liveBtcRate by PriceCache.price
    val walletSat = remember(wallets, watchBalances, excludedWalletIds) {
        trackedWalletSat(trackedWalletGroups(wallets, watchBalances, excludedWalletIds))
    }
    val netWorth = rememberTrackerNetWorth(
        walletSat = walletSat,
        investments = investments,
        lotsByInvestmentId = investmentLots,
        cachedInvestmentsSat = investmentsAssetSat,
        manualAssets = manualAssets,
        cachedManualAssetsSat = manualAssetsSat,
        liabilities = liabilities,
    )
    val netWorthSat = netWorth.netWorthSat

    // Phase 7.6 — KPI strip (savings rate, burn, FIRE, runway, opportunity
    // cost) and the 90-day cash-flow forecast, over the tracked wallets.
    val trackedEvents = remember(events, excludedWalletIds) {
        if (excludedWalletIds.isEmpty()) events else events.filter { it.walletId !in excludedWalletIds }
    }
    val kpis = remember(trackedEvents, netWorth, investments, investmentLots, manualAssets, liveBtcRate) {
        val dashboardCode = SettingsStore.fiatCurrency.value.code
        computeFinanceKpis(
            events = trackedEvents,
            totalAssetSat = netWorth.assetsSat,
            nowUnix = System.currentTimeMillis() / 1000L,
            investments = investments,
            lotsByInvestment = investmentLots,
            manualAssets = manualAssets,
            currentBtcPerUnit = { ccy -> PriceCache.rateFor(ccy) },
            illiquidAssetSat = illiquidManualAssetsSat(manualAssets) { a ->
                computeLiveManualAssetsSat(listOf(a), liveBtcRate, dashboardCode).takeIf { it > 0UL }
            },
        )
    }
    val forecast = remember(trackedEvents, bills, recurringCandidates, walletSat) {
        forecastCashFlow(
            events = trackedEvents,
            bills = bills,
            recurring = recurringCandidates,
            startBalanceSat = walletSat,
            nowUnix = System.currentTimeMillis() / 1000L,
        )
    }

    // Phase 7.1 → 9.0 — net-worth time series. Recomputed when the
    // user picks a different window OR the fiat preference changes
    // (NONE skips the fetch entirely). Phase 9.0: the window is the
    // GLOBAL tracker period from [TrackerUiState] — one selector for
    // hero, KPI strip and every other section; the hero's chips write
    // back to the same state.
    var globalPeriod by TrackerUiState.period
    val period = globalPeriod.toNetWorthPeriod()
    var historyFiat by remember { mutableStateOf<List<Pair<Long, Double>>>(emptyList()) }
    // Phase 8.4 — true sat-on-y NW history reconstructed by the Rust
    // FFI. Parallel to [historyFiat]: while the fiat series tells the
    // BTC-vs-fiat story, this one tells the "real movement of NW in
    // sat" story (deposits, lots, manual assets, liabilities).
    // Stays empty until the first successful FFI call; the hero falls
    // back to the Phase 8.3.2 flat-line placeholder until then.
    var nwHistoryReconstructed by remember { mutableStateOf<List<NwSnapshot>>(emptyList()) }
    val fiatCurrency by SettingsStore.fiatCurrency

    // Phase 7.2.3 — kick a cold-load refresh on first mount when the
    // cache is still empty. The background ticker (started in Main.kt)
    // covers all subsequent refreshes; this just handles the very
    // first paint after the device PIN unlock. Idempotent: the cache
    // refresh() serialises through a mutex so a parallel bump from
    // dataVersion can't race with the cold load.
    LaunchedEffect(Unit) {
        scope.launch { TrackerCache.coldLoad() }
    }

    // Phase 7.1 → UX review 2026-10 (B5) — fiat overlay series: today's
    // net worth valued at each historical BTC/fiat rate (see
    // [netWorthFiatSeries]). Recomputed when the period, the net worth
    // or the fiat currency changes.
    LaunchedEffect(period, netWorthSat, fiatCurrency) {
        if (fiatCurrency == io.bhodl.desktop.prices.FiatCurrency.NONE) {
            historyFiat = emptyList()
            return@LaunchedEffect
        }
        val priceHistory = withContext(Dispatchers.IO) {
            PriceCache.priceHistory(fiatCurrency, period.days)
        }
        if (priceHistory != null) historyFiat = netWorthFiatSeries(netWorthSat, priceHistory)
    }

    // Phase 8.4 — fetch the reconstructed NW-in-sat history in
    // parallel to the fiat series. Reruns when the period changes,
    // when the event snapshot changes (new tx in / out), or when the
    // fiat preference flips. Uses the same window bounds as the fiat
    // fetch.
    //
    // The events list is built from [TrackerCache.events] by signing
    // the amount according to the direction (incoming positive,
    // outgoing negative). The FFI uses this stream as the on-chain
    // signal so the sparkline steps up at deposits and down at
    // spends — independent of the network rate.
    //
    // Failures inside the client return an empty list; the hero then
    // falls back to the Phase 8.3.2 flat-line placeholder.
    val registryDataVersion by RegistryStore.dataVersion
    LaunchedEffect(period, events, fiatCurrency, excludedWalletIds, registryDataVersion) {
        if (fiatCurrency == io.bhodl.desktop.prices.FiatCurrency.NONE) {
            nwHistoryReconstructed = emptyList()
            return@LaunchedEffect
        }
        // Audit 2026-10 — no device PIN needed (the client uses the open
        // registry handle); the client dedupes in-flight calls per key,
        // so rapid period switches don't stack JNI calls.
        if (RegistryStore.current() == null) {
            nwHistoryReconstructed = emptyList()
            return@LaunchedEffect
        }
        val nowUnix = System.currentTimeMillis() / 1000L
        val fromUnix = nowUnix - period.days.toLong() * 86_400L
        // Excluded wallets stay out of the history like they stay out of the hero.
        val eventLite = netWorthHistoryEvents(events, excludedWalletIds)
        val result = NetWorthHistoryClient.netWorthHistory(
            fromUnix = fromUnix,
            toUnix = nowUnix,
            currency = fiatCurrency,
            events = eventLite,
            dataVersion = registryDataVersion,
        )
        nwHistoryReconstructed = result
    }

    Column(
        modifier = Modifier
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        run {
            if (loading) {
                Text(
                    text = stringResource(Res.string.trk_home_loading),
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textSecondary,
                )
            }
            // The sync / cache error is shown once, by the TrackingScreen
            // shell above every section; repeating it here doubled it.

            // Home redesign 2026-10 (proposal A) — the sync state is shown
            // once, by the shell's DataStateStrip; the review and stale
            // nudges live in the "to do" card next to the hero.
            val fiatFigures = heroFiat(
                netWorthSat = netWorthSat,
                spotRate = if (fiatCurrency == io.bhodl.desktop.prices.FiatCurrency.NONE) null else liveBtcRate,
                nwHistory = nwHistoryReconstructed,
            )
            val lastReviewDismissed by SettingsStore.lastMonthlyReviewDismissedUnix
            val nowUnix = System.currentTimeMillis() / 1000L
            val periodEvents = remember(trackedEvents, globalPeriod) { filterByPeriod(trackedEvents, globalPeriod, nowUnix) }
            val todos = remember(trackedEvents, bills, recurringCandidates, manualAssets, lastReviewDismissed, periodEvents, hasLoadedOnce) {
                homeTodos(
                    nowUnix = nowUnix,
                    reviewDue = hasLoadedOnce && trackedEvents.isNotEmpty() &&
                        io.bhodl.desktop.domain.shouldShowMonthlyReviewBanner(nowUnix, lastReviewDismissed),
                    bills = bills,
                    recurringCount = recurringCandidates.size,
                    periodEvents = periodEvents,
                    manualAssets = manualAssets,
                )
            }
            val hero: @Composable (Modifier) -> Unit = { m ->
                // B12 — no hero-local period chips: the global selector
                // above the section is the only one, and drives this chart.
                NetWorthHero(
                    currentSat = netWorthSat,
                    historySatFiat = historyFiat,
                    fiatCurrency = fiatCurrency,
                    period = period,
                    onPeriodChange = { globalPeriod = it.toTrackingPeriod() },
                    modifier = m,
                    nwHistory = nwHistoryReconstructed,
                    currentFiat = fiatFigures.nowFiat,
                    periodDeltaFiat = fiatFigures.periodDeltaFiat,
                )
            }
            val rail: @Composable (Modifier) -> Unit = { m ->
                Column(modifier = m, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    HomeCompositionCard(netWorth = netWorth, onOpenPortfolio = onOpenPortfolio, modifier = Modifier.fillMaxWidth())
                    HomeTodoCard(
                        todos = todos,
                        onAction = { t ->
                            when (t.kind) {
                                HomeTodoKind.REVIEW -> {
                                    SettingsStore.markMonthlyReviewDismissed(nowUnix)
                                    onOpenReports()
                                }
                                HomeTodoKind.BILL_DUE, HomeTodoKind.RECURRING -> onOpenBudget()
                                HomeTodoKind.UNCATEGORIZED -> onOpenTransactions()
                                HomeTodoKind.STALE_ASSET -> onOpenPortfolio()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                if (maxWidth < 1000.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        hero(Modifier.fillMaxWidth())
                        rail(Modifier.fillMaxWidth())
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        hero(Modifier.weight(0.64f))
                        rail(Modifier.weight(0.36f))
                    }
                }
            }

            if (hasLoadedOnce) {
                // One KPI strip: the period's income / expenses, then the
                // savings rate, runway, FIRE and opportunity cost.
                KpiStripV7(kpis = kpis, showBurn = false, period = periodFlows(periodEvents))
                androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    if (maxWidth < 1000.dp) {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            InflowOutflowChart(events = trackedEvents)
                            CashFlowForecastCard(series = forecast, modifier = Modifier.fillMaxWidth())
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Box(Modifier.weight(1f)) { InflowOutflowChart(events = trackedEvents) }
                            CashFlowForecastCard(series = forecast, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            // Wallets list. B13 — refresh and "add wallet" are header
            // actions of the table, not free text / full-width buttons
            // under it.
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WalletsHeader(
                    refreshing = refreshing,
                    onRefresh = { scope.launch { TrackerCache.refresh(syncEsplora = true) } },
                    onAddWatch = onAddWatch,
                )
                WalletListTable(
                    wallets = wallets,
                    watchBalances = watchBalances,
                    onOpenWallet = onOpenWallet,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

        }
    }
}

/**
 * UX review 2026-10 (B13) — header of the HOME wallets table: the
 * section label plus its actions (refresh balances as an icon button
 * with a tooltip, "+ Add" opening the add-wallet form). Replaces the free-text "Refresh balances" row and the
 * full-width "+ Add wallet to tracker" button under the table.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WalletsHeader(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onAddWatch: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BhodlTheme.spacing.sm),
    ) {
        Text(
            text = stringResource(Res.string.tracker_wallets_header),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        val refreshLabel = stringResource(
            if (refreshing) Res.string.tracker_refreshing else Res.string.tracker_refresh_balances,
        )
        TooltipArea(
            tooltip = { HomeTooltip(refreshLabel) },
            delayMillis = 300,
        ) {
            IconButton(
                onClick = onRefresh,
                enabled = !refreshing,
                modifier = Modifier
                    .size(28.dp)
                    .semantics { contentDescription = refreshLabel }
                    .testTag(TrackerHomeTags.REFRESH),
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                    tint = if (refreshing) BhodlTheme.colors.textTertiary
                    else BhodlTheme.colors.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(BhodlTheme.colors.accentNeon.copy(alpha = 0.18f))
                .clickable(onClick = onAddWatch)
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .testTag(TrackerHomeTags.ADD_WATCH),
        ) {
            Text(
                text = "+ " + stringResource(Res.string.tracker_add_wallet),
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.accentNeon,
            )
        }
    }
}

/** Small surface tooltip used by the HOME header actions. */
@Composable
internal fun HomeTooltip(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(BhodlTheme.colors.surfaceVariant)
            .border(1.dp, BhodlTheme.colors.outline, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(text = text, style = BhodlTheme.typography.labelMedium, color = BhodlTheme.colors.textPrimary)
    }
}

/**
 * Test tags for [TrackerHomeScreen]. Compose UI tests target these
 * instead of human-readable strings so the tests stay i18n-stable.
 */
public object TrackerHomeTags {
    public const val SETTINGS: String = "tracker-home.settings"
    public const val LOCK: String = "tracker-home.lock"
    public const val OPEN_TRACKING: String = "tracker-home.open-tracking"
    public const val ADD_WATCH: String = "tracker-home.add-watch"
    public const val REFRESH: String = "tracker-home.refresh"
    public const val MODULES: String = "tracker-home.modules"
    public const val MODULES_BANNER: String = "tracker-home.modules-banner"
}

/**
 * Phase 8.4 — convert the UI-side [PaymentEvent] stream into the
 * thin [PaymentEventLiteFfi] expected by the
 * `net_worth_history` FFI.
 *
 * Sign convention: incoming → positive, outgoing → negative,
 * self-transfer → 0 (cancels out and would double-count if kept).
 *
 * Only on-chain events carry a meaningful `blockTimeUnix`; off-chain
 * events (Lightning, internal transfers without confirmation) have
 * `timestampUnix == null`, which the Rust side filters out
 * (an event without a block time can't be placed on the historical
 * axis).
 *
 * Exposed `internal` for unit tests.
 */
/**
 * Phase 9.0 — bidirectional mapping between the tracker's global
 * [TrackingPeriod] and the hero's [NetWorthPeriod]. The two enums
 * have the same shape (7/30/90/365/All); the hero treats ALL as a
 * 3650-day fetch window.
 */
internal fun TrackingPeriod.toNetWorthPeriod(): NetWorthPeriod = when (this) {
    TrackingPeriod.D7 -> NetWorthPeriod.D7
    TrackingPeriod.D30 -> NetWorthPeriod.D30
    TrackingPeriod.D90 -> NetWorthPeriod.D90
    TrackingPeriod.Y1 -> NetWorthPeriod.Y1
    TrackingPeriod.ALL -> NetWorthPeriod.ALL
}

internal fun NetWorthPeriod.toTrackingPeriod(): TrackingPeriod = when (this) {
    NetWorthPeriod.D7 -> TrackingPeriod.D7
    NetWorthPeriod.D30 -> TrackingPeriod.D30
    NetWorthPeriod.D90 -> TrackingPeriod.D90
    NetWorthPeriod.Y1 -> TrackingPeriod.Y1
    NetWorthPeriod.ALL -> TrackingPeriod.ALL
}

internal fun buildPaymentEventLite(events: List<PaymentEvent>): List<PaymentEventLiteFfi> =
    events.mapNotNull { ev ->
        // send_to_self legs only move the fee out of custody.
        val signedAmount: Long = ev.externalSignedNetSat()
        if (signedAmount == 0L) return@mapNotNull null
        PaymentEventLiteFfi(
            amountSat = signedAmount,
            blockTimeUnix = ev.timestampUnix?.toLong(),
        )
    }
