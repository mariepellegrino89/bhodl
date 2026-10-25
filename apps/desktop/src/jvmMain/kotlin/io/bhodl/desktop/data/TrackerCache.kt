package io.bhodl.desktop.data
import io.bhodl.desktop.ui.userMessage
import io.bhodl.desktop.domain.applyRules
import io.bhodl.desktop.domain.detectRecurring
import io.bhodl.desktop.domain.detectSendToSelf
import io.bhodl.desktop.domain.SEND_TO_SELF
import io.bhodl.desktop.domain.CRYPTO_KIND_REGULAR
import io.bhodl.desktop.domain.canonicalWalletIds
import io.bhodl.desktop.domain.dropAliasedEvents
import io.bhodl.desktop.domain.staleSendToSelfTags
import io.bhodl.desktop.domain.RecurringCandidate
import io.bhodl.desktop.domain.investmentsTotalSat
import io.bhodl.desktop.domain.manualAssetsTotalSat
import io.bhodl.desktop.domain.manualAssetsByKindSat

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import io.bhodl.desktop.settings.EsploraEndpoint
import io.bhodl.desktop.wizard.RegistryStore
import io.bhodl.desktop.wizard.localizedText
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.trk_cache_err_refresh
import io.bhodl.shared.resources.trk_cache_err_registry_closed
import io.bhodl.shared.resources.trk_cache_err_sync
import io.bhodl.shared.resources.trk_cache_err_sync_unknown
import io.bhodl.desktop.wizard.WalletPaths
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.SavedViewFfi
import io.bhodl.ffi.TrackingBillFfi
import io.bhodl.ffi.TrackingBudgetFfi
import io.bhodl.ffi.TrackingInvestmentFfi
import io.bhodl.ffi.TrackingInvestmentLotFfi
import io.bhodl.ffi.TrackingInvestmentSellFfi
import io.bhodl.ffi.TrackingLotDisposalFfi
import io.bhodl.ffi.TrackingLiabilityFfi
import io.bhodl.ffi.TrackingManualAssetFfi
import io.bhodl.ffi.TrackingRuleFfi
import io.bhodl.ffi.TrackingSavingsGoalFfi
import io.bhodl.ffi.TransactionRecord
import io.bhodl.ffi.WalletRecordFfi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 7.2.3 — process-lifetime cache for the tracker / dashboard
 * surface state.
 *
 * Before this store, every composable holding registry data
 * ([io.bhodl.desktop.screens.TrackerHomeScreen],
 * [io.bhodl.desktop.screens.TrackingScreen]) kept its snapshot in
 * `remember { mutableStateOf(...) }`. Switching tabs in
 * [io.bhodl.desktop.ui.TwoTabShell] unmounts the active content,
 * which blew the snapshot away — re-entering the tab re-ran the
 * loader from zero, flashing a "Loading…" line and stalling on the
 * per-watch Esplora sync. This was flagged during the first visual
 * test (2026-05-20).
 *
 * The cache here lives outside the composition, so:
 *  - Tab switches see the previously-loaded snapshot instantly.
 *  - Mutations bump [RegistryStore.dataVersion] → the cache observes
 *    that and re-pulls registry-local data (no network sync).
 *  - A background coroutine started by [start] runs every
 *    [BACKGROUND_REFRESH_INTERVAL_MS] (2 min) doing a full refresh
 *    *with* Esplora sync, so balances stay reasonably fresh without
 *    blocking any UI thread.
 *
 * The composables become dumb views: they read [State] off the
 * cache and call [refresh] on the explicit Refresh button. The
 * dialogs (filters, bill editor, etc.) and per-screen UI-only state
 * (selected tab, scroll position, period chips) stay in
 * `remember` — they're not registry data.
 */
public object TrackerCache {

    // ─── Reactive state — read-only views ─────────────────────────────

    private val _wallets = mutableStateOf<List<WalletRecordFfi>>(emptyList())
    public val wallets: State<List<WalletRecordFfi>> get() = _wallets

    private val _watchBalances = mutableStateOf<Map<String, ULong>>(emptyMap())
    public val watchBalances: State<Map<String, ULong>> get() = _watchBalances

    /**
     * Phase 7.3 — split of the legacy `_totalAssetSat`. The three
     * states map onto distinct semantics:
     *
     *  - [bitcoinAssetSat]: the aggregate watch-only balance. Used by
     *    the savings-goal `BITCOIN_CASH` bucket and by anything that
     *    means "how much BTC do I have right now".
     *  - [investmentsAssetSat]: Σ tracking_investments converted to
     *    sat via [io.bhodl.desktop.prices.PriceCache.fiatToSat]. Zero
     *    when no investments OR when PriceCache hasn't hydrated yet —
     *    [investmentsPricingAvailable] disambiguates.
     *  - [totalAssetSat]: GROSS assets (bitcoinAssetSat +
     *    investmentsAssetSat + manualAssetsSat), liabilities NOT
     *    subtracted. It is not the net worth: the hero, the Portfolio
     *    rollup and the "% of NW" goal targets ([targetSatFromPct]) all
     *    go through `screens.TrackerNetWorth` (assets − liabilities,
     *    live-priced). Kept for diagnostics / test seeding.
     */
    private val _bitcoinAssetSat = mutableStateOf<ULong>(0UL)
    public val bitcoinAssetSat: State<ULong> get() = _bitcoinAssetSat

    private val _investmentsAssetSat = mutableStateOf<ULong>(0UL)
    public val investmentsAssetSat: State<ULong> get() = _investmentsAssetSat

    /**
     * True after the first refresh tick where the PriceCache returned
     * a non-null BTC/fiat conversion. When false, the UI must render
     * the "Investments" row as "—" rather than 0 — distinguishes
     * "you have no investments" from "we don't know the price yet".
     */
    private val _investmentsPricingAvailable = mutableStateOf(false)
    public val investmentsPricingAvailable: State<Boolean> get() = _investmentsPricingAvailable

    private val _totalAssetSat = mutableStateOf<ULong>(0UL)
    public val totalAssetSat: State<ULong> get() = _totalAssetSat

    private val _staleWalletIds = mutableStateOf<Set<String>>(emptySet())
    public val staleWalletIds: State<Set<String>> get() = _staleWalletIds

    private val _events = mutableStateOf<List<PaymentEvent>>(emptyList())
    public val events: State<List<PaymentEvent>> get() = _events

    private val _categories = mutableStateOf<List<String>>(emptyList())
    public val categories: State<List<String>> get() = _categories

    private val _views = mutableStateOf<List<SavedViewFfi>>(emptyList())
    public val views: State<List<SavedViewFfi>> get() = _views

    private val _budgets = mutableStateOf<List<TrackingBudgetFfi>>(emptyList())
    public val budgets: State<List<TrackingBudgetFfi>> get() = _budgets

    private val _bills = mutableStateOf<List<TrackingBillFfi>>(emptyList())
    public val bills: State<List<TrackingBillFfi>> get() = _bills

    private val _goals = mutableStateOf<List<TrackingSavingsGoalFfi>>(emptyList())
    public val goals: State<List<TrackingSavingsGoalFfi>> get() = _goals

    private val _liabilities = mutableStateOf<List<TrackingLiabilityFfi>>(emptyList())
    public val liabilities: State<List<TrackingLiabilityFfi>> get() = _liabilities

    private val _investments = mutableStateOf<List<TrackingInvestmentFfi>>(emptyList())
    public val investments: State<List<TrackingInvestmentFfi>> get() = _investments

    /**
     * Phase 7.8 — investment lots grouped by parent investment id.
     * Empty when no investment has multi-lot model active (legacy
     * Phase 5.1 single-row aggregate still applies). Refreshed in
     * one round trip via `listAllInvestmentLots()`.
     */
    private val _investmentLotsByInvestmentId =
        mutableStateOf<Map<Long, List<TrackingInvestmentLotFfi>>>(emptyMap())
    public val investmentLotsByInvestmentId: State<Map<Long, List<TrackingInvestmentLotFfi>>>
        get() = _investmentLotsByInvestmentId

    /**
     * Phase 7.9 — sells grouped by parent investment id. Drives the
     * "Sells" sub-section + the realized P&L contribution.
     */
    private val _investmentSellsByInvestmentId =
        mutableStateOf<Map<Long, List<TrackingInvestmentSellFfi>>>(emptyMap())
    public val investmentSellsByInvestmentId: State<Map<Long, List<TrackingInvestmentSellFfi>>>
        get() = _investmentSellsByInvestmentId

    /**
     * Phase 7.9 — lot disposals grouped by *parent investment id*
     * (resolved through the sell's investment_id). Read by the UI
     * to attribute per-sell P&L and by [realizedPnl] for aggregates.
     */
    private val _lotDisposalsByInvestmentId =
        mutableStateOf<Map<Long, List<TrackingLotDisposalFfi>>>(emptyMap())
    public val lotDisposalsByInvestmentId: State<Map<Long, List<TrackingLotDisposalFfi>>>
        get() = _lotDisposalsByInvestmentId

    /**
     * Phase 7.4 — manual asset rows (cash / real_estate / vehicle /
     * collectible / brokerage / business_equity / loans_to_others /
     * other). Asset-side mirror of [liabilities], but multi-currency.
     * [manualAssetsSat] is the aggregate in sat, [manualAssetsByKindSat]
     * the per-kind breakdown for the NetWorthBreakdownTable rows.
     */
    private val _manualAssets = mutableStateOf<List<TrackingManualAssetFfi>>(emptyList())
    public val manualAssets: State<List<TrackingManualAssetFfi>> get() = _manualAssets

    /** Registry v6 — kind-specific details of investments, manual assets
     *  and liabilities (Portfolio detail cards). */
    private val _portfolioDetails = mutableStateOf(io.bhodl.desktop.screens.PortfolioDetails())
    internal val portfolioDetails: State<io.bhodl.desktop.screens.PortfolioDetails> get() = _portfolioDetails

    private val _manualAssetsSat = mutableStateOf<ULong>(0UL)
    public val manualAssetsSat: State<ULong> get() = _manualAssetsSat

    private val _manualAssetsByKindSat = mutableStateOf<Map<String, ULong>>(emptyMap())
    public val manualAssetsByKindSat: State<Map<String, ULong>> get() = _manualAssetsByKindSat

    /**
     * Phase 7.5 — categorization rules from the registry, sorted by
     * priority DESC, created_at ASC. UI binds to this list to drive
     * the RulesCard; the cache itself applies the enabled rules to
     * any uncategorized event during [refresh].
     */
    private val _rules = mutableStateOf<List<TrackingRuleFfi>>(emptyList())
    public val rules: State<List<TrackingRuleFfi>> get() = _rules

    /**
     * Phase 7.5 — Recurring suggestions detected from the merged
     * event stream. Lives in memory only: filtered by the dismiss
     * set from the registry, so once the user clicks Dismiss the
     * candidate stops surfacing on subsequent refreshes.
     */
    private val _recurringCandidates = mutableStateOf<List<
        RecurringCandidate
        >>(emptyList())
    public val recurringCandidates: State<List<
        RecurringCandidate
        >> get() = _recurringCandidates

    private val _perWalletTxs = mutableStateOf<Map<String, List<TransactionRecord>>>(emptyMap())
    public val perWalletTxs: State<Map<String, List<TransactionRecord>>> get() = _perWalletTxs

    /**
     * BTC average cost — the user's price / exclusion per transaction,
     * keyed by txid (the same key the event stream is deduped on, so a
     * row written against either leg of a transaction applies to it).
     */
    /**
     * Per-wallet legs of every transaction (wallet aliases collapsed,
     * NOT deduped by txid). The BTC average-cost view nets the legs of a
     * txid so a payment whose change lands in another own wallet counts
     * only what actually left.
     */
    private val _eventLegs = mutableStateOf<List<PaymentEvent>>(emptyList())
    public val eventLegs: State<List<PaymentEvent>> get() = _eventLegs

    private val _costBasisInputs = mutableStateOf<Map<String, io.bhodl.ffi.EventCostBasisFfi>>(emptyMap())
    public val costBasisInputs: State<Map<String, io.bhodl.ffi.EventCostBasisFfi>> get() = _costBasisInputs

    private val _refreshing = mutableStateOf(false)
    public val refreshing: State<Boolean> get() = _refreshing

    private val _lastSyncUnix = mutableStateOf<Long?>(null)
    public val lastSyncUnix: State<Long?> get() = _lastSyncUnix

    /** True while [lastError] holds the verdict of a failed Esplora sync. */
    private var lastErrorIsSync: Boolean = false

    private val _lastError = mutableStateOf<String?>(null)
    public val lastError: State<String?> get() = _lastError

    /**
     * True once the first successful refresh has populated the cache.
     * Composables check this to decide whether to show "Loading…" on
     * cold start vs. silently rendering the cached snapshot.
     */
    private val _hasLoadedOnce = mutableStateOf(false)
    public val hasLoadedOnce: State<Boolean> get() = _hasLoadedOnce

    // ─── Internals ────────────────────────────────────────────────────

    /**
     * Background refresh cadence. 2 min balances "feels live" with
     * Esplora's politeness limit (~60 req/min on mempool.space's
     * public endpoint): even with 5 watch wallets a full refresh
     * issues ~10 HTTP requests, well below the budget.
     */
    public var backgroundRefreshIntervalMs: Long = 2 * 60 * 1000L

    private val refreshMutex = Mutex()
    private val scope = MainScope()
    private val started = AtomicBoolean(false)

    /**
     * Boot the background loops. Idempotent — calling twice is a
     * no-op. Two loops launch:
     *  1. The 2-min Esplora sync ticker.
     *  2. A [snapshotFlow] observer on [RegistryStore.dataVersion]
     *     that triggers a registry-only refresh after each mutation.
     *
     * Callers should invoke [start] from `Main.kt` (mirror of
     * [io.bhodl.desktop.prices.PriceCache.start]).
     */
    public fun start() {
        if (!started.compareAndSet(false, true)) return
        // 1) Background sync ticker.
        scope.launch {
            while (isActive) {
                delay(backgroundRefreshIntervalMs)
                // Only attempt when a registry is open — otherwise the
                // call would just fail with "registry not open" and
                // spam lastError on every tick.
                if (RegistryStore.current() != null) {
                    runCatching { refresh(syncEsplora = true) }
                }
            }
        }
        // 2) DataVersion observer — registry-only refresh on every
        //    mutation. snapshotFlow drains synchronously from Compose's
        //    Snapshot system, so we get one emission per bump.
        scope.launch {
            snapshotFlow { RegistryStore.dataVersion.value }
                .collect {
                    // Skip the very first emission (current value)
                    // because that's the initial state, not a mutation.
                    if (_hasLoadedOnce.value) {
                        runCatching { refresh(syncEsplora = false) }
                    }
                }
        }
    }

    /** Stop the background loops. Used by tests; production rarely needs it. */
    public fun stop() {
        scope.cancel()
        started.set(false)
    }

    /**
     * Wipe cached state. Called on logout / wipe / registry close so a
     * fresh login doesn't see the previous session's snapshot for a
     * frame before the first refresh.
     */
    public fun reset() {
        // Atomic wipe — same rationale as the refresh publish: a logout
        // clearing 28 states one-by-one could otherwise flash a
        // partially-cleared snapshot for a frame.
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
            _wallets.value = emptyList()
            _watchBalances.value = emptyMap()
            _bitcoinAssetSat.value = 0UL
            _investmentsAssetSat.value = 0UL
            _investmentsPricingAvailable.value = false
            _totalAssetSat.value = 0UL
            _staleWalletIds.value = emptySet()
            _events.value = emptyList()
            _categories.value = emptyList()
            _views.value = emptyList()
            _budgets.value = emptyList()
            _bills.value = emptyList()
            _goals.value = emptyList()
            _liabilities.value = emptyList()
            _portfolioDetails.value = io.bhodl.desktop.screens.PortfolioDetails()
            _investments.value = emptyList()
            _investmentLotsByInvestmentId.value = emptyMap()
            _investmentSellsByInvestmentId.value = emptyMap()
            _lotDisposalsByInvestmentId.value = emptyMap()
            _manualAssets.value = emptyList()
            _manualAssetsSat.value = 0UL
            _manualAssetsByKindSat.value = emptyMap()
            _rules.value = emptyList()
            _recurringCandidates.value = emptyList()
            _perWalletTxs.value = emptyMap()
            _costBasisInputs.value = emptyMap()
            _eventLegs.value = emptyList()
            _refreshing.value = false
            _lastSyncUnix.value = null
            _lastError.value = null
            lastErrorIsSync = false
            _hasLoadedOnce.value = false
        }
        rateMisses.clear()
        knownRates.clear()
        coldLoadStarted.set(false)
        BtcCostBasisRates.clear()
    }

    private val coldLoadStarted = AtomicBoolean(false)

    /**
     * First paint after the device-PIN unlock. Publishes the cached
     * SQLCipher state straight away (a registry-only pass is local and
     * fast), then runs the Esplora sync in the background. Syncing first
     * kept every screen on a spinner until each wallet's full scan
     * finished — slow with several wallets, slower over Tor. Only the
     * first caller does the work: the home and tracking screens both
     * kick it on mount.
     */
    public suspend fun coldLoad() {
        if (_hasLoadedOnce.value || !coldLoadStarted.compareAndSet(false, true)) return
        refresh(syncEsplora = false)
        refresh(syncEsplora = true)
    }

    // ─── B9 — historical BTC rate healing (off the critical path) ────

    /**
     * B9 — historical rates found by [healMissingRates], keyed by (UTC
     * day, currency). [refresh] re-applies them to freshly loaded rows
     * with a pure in-memory lookup, so a healed row stays healed even
     * if persisting it through the registry failed. No network here.
     */
    private val knownRates = java.util.concurrent.ConcurrentHashMap<Pair<Long, String>, Double>()

    /**
     * B9 — negative cache for lookups that found nothing, keyed by (UTC
     * day, currency) → time of the miss. A miss is retried after
     * [RATE_MISS_RETRY_MS] instead of on every 2-min refresh, so an
     * unresolvable row (pre-genesis date, unsupported currency,
     * provider down) doesn't hit the network each tick.
     */
    private val rateMisses = java.util.concurrent.ConcurrentHashMap<Pair<Long, String>, Long>()
    private const val RATE_MISS_RETRY_MS: Long = 30 * 60 * 1000L

    /** Max network rate lookups per healing pass; the rest wait for the next pass. */
    internal const val MAX_RATE_LOOKUPS_PER_PASS: Int = 12

    /** Per-lookup timeout; a hung provider / Tor circuit can't stall a pass. */
    internal var rateLookupTimeoutMs: Long = 8_000L

    /** Source of historical rates. Test seam (production: the rate history FFI). */
    internal var rateLookup: suspend (dayUnix: Long, currency: String) -> Double? =
        { day, ccy -> io.bhodl.desktop.prices.BtcRateHistoryClient.rateAt(day, ccy) }

    /** Persists healed rows through the registry resolvers. Test seam. */
    internal var persistHealedRates: (lotIds: List<Long>, sellIds: List<Long>) -> Unit = { lots, sells ->
        RegistryStore.current()?.let { registry ->
            for (id in lots) io.bhodl.desktop.prices.TrackerBtcRate.resolveLot(registry, id)
            for (id in sells) io.bhodl.desktop.prices.TrackerBtcRate.resolveSell(registry, id)
        }
    }

    private val healing = AtomicBoolean(false)

    /** Detached scope for the blocking rate lookups (never cancelled by [stop]). */
    private val rateScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO,
    )

    private suspend fun historicalRateAt(dayUnix: Long, currency: String): Double? {
        val key = dayUnix to currency
        val now = System.currentTimeMillis()
        rateMisses[key]?.let { missedAt -> if (now - missedAt < RATE_MISS_RETRY_MS) return null }
        // The FFI call blocks and can't be interrupted: run it detached
        // and stop WAITING after the timeout (the call may finish later
        // and fill the registry's rate cache for the next pass).
        val call = rateScope.async { rateLookup(dayUnix, currency) }
        val r = kotlinx.coroutines.withTimeoutOrNull(rateLookupTimeoutMs) { call.await() }
        if (r == null) rateMisses[key] = now else rateMisses.remove(key)
        return r
    }

    /** Re-apply already-known historical rates to rows (pure, no I/O). */
    private suspend fun applyKnownRates(
        investments: List<TrackingInvestmentFfi>,
        lots: Map<Long, List<TrackingInvestmentLotFfi>>,
        sells: Map<Long, List<TrackingInvestmentSellFfi>>,
        disposals: Map<Long, List<TrackingLotDisposalFfi>>,
    ): HealedInvestmentRates = fillMissingBtcRates(
        investments = investments,
        lotsByInvestmentId = lots,
        sellsByInvestmentId = sells,
        disposalsByInvestmentId = disposals,
        rateAt = { day, ccy -> knownRates[day to ccy] },
    )

    /**
     * B9 — look up the historical BTC rates the published rows are still
     * missing, then republish. Runs AFTER a network refresh has
     * published its snapshot and OUTSIDE [refreshMutex] (see [refresh]),
     * so an offline / Tor-down provider can never hold "Loading
     * tracker…" or block other refreshes. Bounded: at most
     * [MAX_RATE_LOOKUPS_PER_PASS] lookups, each capped at
     * [rateLookupTimeoutMs]. Found rates are persisted best-effort
     * through the registry resolvers.
     */
    internal suspend fun healMissingRates() {
        val investments = _investments.value
        if (investments.isEmpty()) return
        val healed = fillMissingBtcRates(
            investments = investments,
            lotsByInvestmentId = _investmentLotsByInvestmentId.value,
            sellsByInvestmentId = _investmentSellsByInvestmentId.value,
            disposalsByInvestmentId = _lotDisposalsByInvestmentId.value,
            rateAt = ::historicalRateAt,
            maxLookups = MAX_RATE_LOOKUPS_PER_PASS,
        )
        if (healed.resolvedRates.isEmpty()) return
        knownRates.putAll(healed.resolvedRates)
        // Re-apply to whatever is published NOW: a refresh may have
        // landed while the lookups were running.
        val current = applyKnownRates(
            _investments.value,
            _investmentLotsByInvestmentId.value,
            _investmentSellsByInvestmentId.value,
            _lotDisposalsByInvestmentId.value,
        )
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
            _investmentLotsByInvestmentId.value = current.lotsByInvestmentId
            _investmentSellsByInvestmentId.value = current.sellsByInvestmentId
            _lotDisposalsByInvestmentId.value = current.disposalsByInvestmentId
        }
        runCatching {
            withContext(Dispatchers.IO) { persistHealedRates(healed.healedLotIds, healed.healedSellIds) }
        }
    }

    /** Fire-and-forget [healMissingRates]; at most one pass at a time. */
    private fun launchRateHealing() {
        if (!healing.compareAndSet(false, true)) return
        rateScope.launch {
            try {
                healMissingRates()
            } finally {
                healing.set(false)
            }
        }
    }

    /**
     * Pull registry truth into the cache. When [syncEsplora] is true,
     * every watch wallet is synced with the configured Esplora endpoint
     * before its balance + tx history is read. When false, only the
     * cached SQLCipher state is read — used after a registry-local
     * mutation (addBill, addInvestment, etc.) where there's no chain
     * payload to discover.
     *
     * Concurrent calls serialise via [refreshMutex] so the background
     * tick + a user-triggered Refresh + a mutation bump can't interleave
     * and corrupt the snapshot mid-pass.
     *
     * Errors during refresh are captured in [lastError] but do not
     * throw — a single failing Esplora endpoint shouldn't poison the
     * cache.
     */
    /** Diagnostics on stderr, only when the app runs with `BHODL_DEBUG=1`. */
    private fun debugLog(message: String) {
        if (System.getenv("BHODL_DEBUG") == "1") System.err.println("[bhodl] $message")
    }

    public suspend fun refresh(syncEsplora: Boolean = true) {
        refreshMutex.withLock {
            val registry = RegistryStore.current() ?: run {
                _lastError.value = localizedText(Res.string.trk_cache_err_registry_closed)
                lastErrorIsSync = false
                return@withLock
            }
            _refreshing.value = true
            runCatching {
                withContext(Dispatchers.IO) {
                    val list = registry.listWallets()
                    val proxy = EsploraEndpoint.currentProxy()
                    // Audit 2026-10 (H8) — first sync failure of this pass,
                    // surfaced through [lastError] instead of being dropped.
                    var syncFailure: String? = null
                    var syncFailures = 0
                    val perRow = mutableMapOf<String, ULong>()
                    val perRowTxs = mutableMapOf<String, List<TransactionRecord>>()
                    val stale = mutableSetOf<String>()

                    var anySyncAttempted = false
                    var anySyncOk = false
                    for (row in list) {
                        val opened = runCatching {
                            val handle = registry.openWatch(row.id)
                            try {
                                val syncOk = if (syncEsplora) {
                                    anySyncAttempted = true
                                    // Each watch wallet syncs against ITS OWN
                                    // network's endpoint (not the open
                                    // wallet's).
                                    val network = EsploraEndpoint.networkOf(row.network)
                                    val result = if (network == null) {
                                        Result.failure(
                                            IllegalStateException("unknown network '${row.network}'"),
                                        )
                                    } else {
                                        runCatching {
                                            handle.syncWithEsplora(EsploraEndpoint.current(network), proxy)
                                        }
                                    }
                                    result.exceptionOrNull()?.let { e ->
                                        syncFailures++
                                        debugLog("sync of '${row.label}' (${row.network}) failed: ${e::class.simpleName}: ${e.message}")
                                        if (syncFailure == null) syncFailure = e.userMessage(localizedText(Res.string.trk_cache_err_sync_unknown))
                                    }
                                    result.isSuccess
                                } else true
                                if (syncEsplora && syncOk) anySyncOk = true
                                perRow[row.id] = handle.balanceSat()
                                perRowTxs[row.id] = runCatching { handle.listTransactions() }
                                    .getOrNull()
                                    .orEmpty()
                                syncOk
                            } finally {
                                // Always release the native handle, even
                                // when balanceSat() throws.
                                runCatching { handle.destroy() }
                            }
                        }
                        opened.exceptionOrNull()?.let { e ->
                            debugLog("opening '${row.label}' (${row.network}) failed: ${e::class.simpleName}: ${e.message}")
                        }
                        if (opened.isFailure || opened.getOrDefault(false).not()) {
                            stale += row.id
                        }
                    }
                    // A registry-only refresh doesn't sync, so it can't
                    // clear "stale": keep the previous verdict for wallets
                    // that still exist and add any that failed to open.
                    val walletIds = list.map { it.id }.toSet()
                    val staleOut: Set<String> = if (syncEsplora) {
                        stale
                    } else {
                        (_staleWalletIds.value intersect walletIds) + stale
                    }
                    // Only a real network sync stamps lastSyncUnix (no
                    // watch wallets at all counts as trivially synced).
                    val networkSynced = syncEsplora && (anySyncOk || !anySyncAttempted)

                    // Collapse registry rows that track the same account
                    // (e.g. a WatchHd mirror + a WatchXpub of the same
                    // xpub) so one account is counted once, both for the
                    // event stream and for send_to_self detection.
                    val canonical = canonicalWalletIds(list) { w ->
                        runCatching { io.bhodl.ffi.extractAccountXpub(w.descriptorPub) }.getOrNull()
                    }
                    val watchEvents = runCatching { watchEventsSource(registry) }
                        .getOrDefault(emptyList())
                    // Pre-dedup-by-txid stream (one event per wallet leg),
                    // aliases removed. send_to_self detection needs the
                    // per-wallet legs of each txid.
                    val allEvents = dropAliasedEvents(watchEvents, canonical)
                    // One event per txid for the UI / aggregates. Prefer
                    // the leg carrying a user category, then the one that
                    // knows the fee (the debit leg: it lets an internal
                    // transfer count its fee as the only outflow).
                    val merged = mergeEventLegs(allEvents)

                    // Phase 7.5 — apply categorization rules to events
                    // that don't already have a category. The user's
                    // manual category always wins (we only fill the
                    // gap). Rules come back from list_rules() already
                    // sorted priority DESC; the matcher walks in that
                    // order and stops at the first hit.
                    //
                    // Known limitation (documented, intentionally not
                    // changed): the rule's category is persisted with the
                    // same set_event_category call a manual edit uses, so
                    // afterwards a rule-assigned category is
                    // indistinguishable from a hand-set one. Disabling or
                    // editing a rule does not un-categorize events it
                    // already matched.
                    val rulesList = runCatching { registry.listRules() }
                        .getOrDefault(emptyList())
                    val enabledRules = rulesList.filter { it.enabled }
                    if (enabledRules.isNotEmpty()) {
                        // Payee rules match the wallet label (item 11).
                        val labelById = list.associate { it.id to it.label }
                        for (ev in merged) {
                            if (ev.category != null) continue
                            val match = applyRules(enabledRules, ev) { labelById[it.walletId] }
                                ?: continue
                            runCatching {
                                registry.setEventCategory(ev.walletId, ev.txidOrPaymentId, match)
                            }
                            // Reflect on the in-memory event so the UI
                            // shows the category without waiting for
                            // the next refresh round-trip.
                            ev.category = match
                        }
                    }

                    // H13 one-time cleanup: reset the send_to_self tags
                    // the old signing+mirror merge produced wholesale.
                    // Runs once per data dir, before detection, so a txid
                    // that does qualify is re-tagged right away.
                    runSendToSelfCleanupOnce(registry, list, canonical, allEvents)

                    // Phase 7.10 — auto-detect `send_to_self` (strict
                    // rule, see detectSendToSelf). Only legs with no
                    // stored kind are written, and through the AUTO
                    // entry point, which the registry refuses for
                    // user-pinned rows (schema v3 `user_set`) — so a
                    // user's explicit "regular" is never re-tagged.
                    // Mutating the returned legs also updates `merged`
                    // (same instances).
                    for (ev in detectSendToSelf(allEvents)) {
                        val written = runCatching {
                            registry.setEventCryptoKindAuto(
                                ev.walletId, ev.txidOrPaymentId, SEND_TO_SELF,
                            )
                        }.getOrDefault(false)
                        if (written) ev.cryptoEventKind = SEND_TO_SELF
                    }
                    // The merged representative may be a different leg of
                    // a tagged txid (e.g. it carried the category): give
                    // it the kind in memory so aggregates exclude it.
                    val taggedTxids = allEvents
                        .filter { it.cryptoEventKind == SEND_TO_SELF }
                        .map { it.txidOrPaymentId }
                        .toSet()
                    for (ev in merged) {
                        if (ev.cryptoEventKind == null && ev.txidOrPaymentId in taggedTxids) {
                            ev.cryptoEventKind = SEND_TO_SELF
                        }
                    }

                    // Phase 7.5 — recurring detection runs over the
                    // merged event stream, filtered by registry's
                    // dismissed-key set. Output is in-memory only.
                    val dismissedKeys = runCatching { registry.listDismissedRecurring() }
                        .getOrDefault(emptyList())
                        .toSet()
                    val recurring = detectRecurring(
                        events = merged,
                        nowUnix = System.currentTimeMillis() / 1000L,
                        dismissedKeys = dismissedKeys,
                    )

                    val bitcoinAsset = registry.aggregateWatchBalanceSat(io.bhodl.desktop.wizard.WalletStore.network())
                    // Phase 7.3 — aggregate investments and probe
                    // PriceCache once. Both `pricingAvailable=false ⇒
                    // investmentsAsset=0` and `pricingAvailable=true ⇒
                    // investmentsAsset=Σ` are consistent; the boolean
                    // exists to tell the UI "0 means nothing yet" vs
                    // "0 means truly empty".
                    val investmentsList = runCatching { registry.listInvestments() }
                        .getOrDefault(emptyList())
                    // Phase 7.8 — one round-trip for all lots; the
                    // Kotlin side groups by investment_id at render.
                    val investmentLots = runCatching { registry.listAllInvestmentLots() }
                        .getOrDefault(emptyList())
                        .groupBy { it.investmentId }
                    // Phase 7.9 — sells + disposals. Disposals are
                    // keyed in the registry by sell_id; we re-key
                    // by investment_id at cache-load time via the
                    // sells map so the UI doesn't redo this lookup
                    // on every render.
                    val sellsByInvestment = runCatching { registry.listAllInvestmentSells() }
                        .getOrDefault(emptyList())
                        .groupBy { it.investmentId }
                    val allDisposals = runCatching { registry.listAllLotDisposals() }
                        .getOrDefault(emptyList())
                    val sellIdToInvestmentId: Map<Long, Long> = sellsByInvestment
                        .flatMap { (invId, sells) -> sells.map { it.id to invId } }
                        .toMap()
                    val disposalsByInvestment: Map<Long, List<TrackingLotDisposalFfi>> = allDisposals
                        .groupBy { sellIdToInvestmentId[it.sellId] ?: -1L }
                        .filterKeys { it != -1L }
                    // UX review 2026-10 (B9) — re-apply historical rates that
                    // an earlier healing pass already found (in-memory, no
                    // network). New lookups happen in [healMissingRates],
                    // after this snapshot is published.
                    val healed = applyKnownRates(
                        investmentsList, investmentLots, sellsByInvestment, disposalsByInvestment,
                    )
                    val pricingAvailable: Boolean =
                        io.bhodl.desktop.prices.PriceCache.fiatToSat(1.0) != null
                    val investmentsAsset: ULong = if (investmentsList.isEmpty()) {
                        0UL
                    } else {
                        // H14 / M7 — lot-managed positions are valued on
                        // their effective quantity, and every row converts
                        // at its own currency's BTC rate.
                        investmentsTotalSat(
                            investments = investmentsList,
                            fiatToSat = io.bhodl.desktop.prices.PriceCache::fiatToSat,
                            lotsByInvestmentId = investmentLots,
                            rateFor = io.bhodl.desktop.prices.PriceCache::rateFor,
                        )
                    }
                    // Phase 7.4 — same compute for manual assets, plus
                    // a per-kind breakdown for the NetWorthBreakdownTable.
                    val manualList = runCatching { registry.listManualAssets() }
                        .getOrDefault(emptyList())
                    val manualAssetsAgg: ULong = if (manualList.isEmpty()) {
                        0UL
                    } else {
                        manualAssetsTotalSat(
                            assets = manualList,
                            fiatToSat = io.bhodl.desktop.prices.PriceCache::fiatToSat,
                            rateFor = io.bhodl.desktop.prices.PriceCache::rateFor,
                        )
                    }
                    val manualAssetsByKind: Map<String, ULong> = if (manualList.isEmpty()) {
                        emptyMap()
                    } else {
                        manualAssetsByKindSat(
                            assets = manualList,
                            fiatToSat = io.bhodl.desktop.prices.PriceCache::fiatToSat,
                            rateFor = io.bhodl.desktop.prices.PriceCache::rateFor,
                        )
                    }
                    Snapshot(
                        wallets = list,
                        watchBalances = perRow,
                        bitcoinAssetSat = bitcoinAsset,
                        investmentsAssetSat = investmentsAsset,
                        investmentsPricingAvailable = pricingAvailable,
                        totalAssetSat = bitcoinAsset + investmentsAsset + manualAssetsAgg,
                        staleWalletIds = staleOut,
                        networkSynced = networkSynced,
                        syncError = syncFailure?.let { msg ->
                            localizedText(Res.string.trk_cache_err_sync, syncFailures, msg)
                        },
                        events = merged,
                        categories = runCatching { registry.listCategories() }
                            .getOrDefault(emptyList()),
                        views = runCatching { registry.listViews() }
                            .getOrDefault(emptyList()),
                        budgets = runCatching { registry.listBudgets() }
                            .getOrDefault(emptyList()),
                        bills = runCatching { registry.listBills() }
                            .getOrDefault(emptyList()),
                        goals = runCatching { registry.listSavingsGoals() }
                            .getOrDefault(emptyList()),
                        liabilities = runCatching { registry.listLiabilities() }
                            .getOrDefault(emptyList()),
                        portfolioDetails = loadPortfolioDetails(registry),
                        investments = investmentsList,
                        investmentLotsByInvestmentId = healed.lotsByInvestmentId,
                        investmentSellsByInvestmentId = healed.sellsByInvestmentId,
                        lotDisposalsByInvestmentId = healed.disposalsByInvestmentId,
                        manualAssets = manualList,
                        manualAssetsSat = manualAssetsAgg,
                        manualAssetsByKindSat = manualAssetsByKind,
                        rules = rulesList,
                        recurringCandidates = recurring,
                        perWalletTxs = perRowTxs,
                        eventLegs = allEvents,
                        costBasisInputs = runCatching { registry.listEventCostBasis() }
                            .getOrDefault(emptyList())
                            .associateBy { it.txidOrPaymentId },
                    )
                }
            }.fold(
                // GAP-019 — a lock / wipe (or a new unlock) landed while this
                // pass was running: never publish the closed session's data
                // over the reset cache.
                onSuccess = { snap ->
                    if (RegistryStore.current() !== registry) return@fold
                    // Atomic publish — all 24 backing states flip in one
                    // Compose snapshot so no composable can observe a
                    // half-applied refresh (e.g. new `events` against the
                    // old `totalAssetSat`). Without this batch, a
                    // recomposition landing between two `.value =` writes
                    // would render an inconsistent frame.
                    androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                        _wallets.value = snap.wallets
                        _watchBalances.value = snap.watchBalances
                        _bitcoinAssetSat.value = snap.bitcoinAssetSat
                        _investmentsAssetSat.value = snap.investmentsAssetSat
                        _investmentsPricingAvailable.value = snap.investmentsPricingAvailable
                        _totalAssetSat.value = snap.totalAssetSat
                        _staleWalletIds.value = snap.staleWalletIds
                        _events.value = snap.events
                        _categories.value = snap.categories
                        _views.value = snap.views
                        _budgets.value = snap.budgets
                        _bills.value = snap.bills
                        _goals.value = snap.goals
                        _liabilities.value = snap.liabilities
                        _portfolioDetails.value = snap.portfolioDetails
                        _investments.value = snap.investments
                        _investmentLotsByInvestmentId.value = snap.investmentLotsByInvestmentId
                        _investmentSellsByInvestmentId.value = snap.investmentSellsByInvestmentId
                        _lotDisposalsByInvestmentId.value = snap.lotDisposalsByInvestmentId
                        _manualAssets.value = snap.manualAssets
                        _manualAssetsSat.value = snap.manualAssetsSat
                        _manualAssetsByKindSat.value = snap.manualAssetsByKindSat
                        _rules.value = snap.rules
                        _recurringCandidates.value = snap.recurringCandidates
                        _perWalletTxs.value = snap.perWalletTxs
                        _costBasisInputs.value = snap.costBasisInputs
                        _eventLegs.value = snap.eventLegs
                        if (snap.networkSynced) {
                            _lastSyncUnix.value = System.currentTimeMillis() / 1000L
                        }
                        // Audit 2026-10 (H8) — a failed Esplora sync (e.g.
                        // Tor proxy down) is no longer silent.
                        // A registry-only pass did not sync, so it keeps
                        // the previous pass's sync verdict.
                        if (syncEsplora) {
                            _lastError.value = snap.syncError
                            lastErrorIsSync = snap.syncError != null
                        } else if (!lastErrorIsSync) {
                            _lastError.value = null
                        }
                        _hasLoadedOnce.value = true
                    }
                },
                onFailure = { e ->
                    if (RegistryStore.current() !== registry) return@fold
                    // A cancelled pass (its caller's scope went away, e.g. the
                    // screen that started it closed) is not an error to show.
                    if (e is kotlinx.coroutines.CancellationException) return@fold
                    _lastError.value = e.userMessage(localizedText(Res.string.trk_cache_err_refresh))
                    lastErrorIsSync = false
                },
            )
            _refreshing.value = false
        }
        // B9 — historical-rate healing only on network passes, after the
        // snapshot is published and outside the mutex.
        if (syncEsplora) launchRateHealing()
    }

    /** Source of the per-wallet event legs. Test seam (production: the FFI). */
    internal var watchEventsSource: (io.bhodl.ffi.RegistryHandle) -> List<PaymentEvent> =
        { it.listWatchEvents() }

    /**
     * Data dir holding the one-time cleanup marker. Test seam.
     */
    internal var dataDirProvider: () -> java.io.File = { WalletPaths.defaultDataDir() }

    /** Marker file: the H13 send_to_self cleanup already ran. */
    internal const val SEND_TO_SELF_CLEANUP_MARKER: String = "tracker-send-to-self-cleanup-v1.done"

    /**
     * H13 — one-time reset of `send_to_self` rows that an older build's
     * merge of a signing wallet + its watch mirror wrote for EVERY tx.
     * See [staleSendToSelfTags] for which rows qualify (the registry does
     * not record whether a kind was auto- or user-set, so only certain
     * artefacts are reset). The marker is written only after the dump
     * succeeds, so a failure retries on the next refresh.
     */
    private fun runSendToSelfCleanupOnce(
        registry: io.bhodl.ffi.RegistryHandle,
        wallets: List<WalletRecordFfi>,
        canonical: Map<String, String>,
        dedupedEvents: List<PaymentEvent>,
    ) {
        val marker = runCatching { java.io.File(dataDirProvider(), SEND_TO_SELF_CLEANUP_MARKER) }
            .getOrNull() ?: return
        if (marker.exists()) return
        val rows = runCatching { registry.dumpCryptoEventKinds() }.getOrNull() ?: return
        val toReset = staleSendToSelfTags(rows, canonical, dedupedEvents)
        val resetKeys = HashSet<Pair<String, String>>()
        for ((walletId, txid) in toReset) {
            // AUTO path: never clears a user-pinned row (and
            // staleSendToSelfTags already skips them).
            val ok = runCatching {
                registry.setEventCryptoKindAuto(walletId, txid, CRYPTO_KIND_REGULAR)
            }.isSuccess
            if (ok) resetKeys += walletId to txid
        }
        // Mirror the reset on the in-memory legs (listWatchEvents was
        // read before the reset) so detection sees them as unclassified.
        for (ev in dedupedEvents) {
            if ((ev.walletId to ev.txidOrPaymentId) in resetKeys) ev.cryptoEventKind = null
        }
        if (resetKeys.size == toReset.size) {
            runCatching {
                marker.parentFile?.mkdirs()
                marker.writeText("done ${System.currentTimeMillis() / 1000L}\n")
            }
        }
    }

    /** Whole snapshot bundle — kept internal so the cache fields are
     *  the only public surface. */
    private data class Snapshot(
        val wallets: List<WalletRecordFfi>,
        val watchBalances: Map<String, ULong>,
        val bitcoinAssetSat: ULong,
        val investmentsAssetSat: ULong,
        val investmentsPricingAvailable: Boolean,
        val totalAssetSat: ULong,
        val staleWalletIds: Set<String>,
        /** True only when this pass actually synced with Esplora. */
        val networkSynced: Boolean,
        /** User-facing summary of this pass's sync failures, if any. */
        val syncError: String? = null,
        val events: List<PaymentEvent>,
        val categories: List<String>,
        val views: List<SavedViewFfi>,
        val budgets: List<TrackingBudgetFfi>,
        val bills: List<TrackingBillFfi>,
        val goals: List<TrackingSavingsGoalFfi>,
        val liabilities: List<TrackingLiabilityFfi>,
        val portfolioDetails: io.bhodl.desktop.screens.PortfolioDetails = io.bhodl.desktop.screens.PortfolioDetails(),
        val investments: List<TrackingInvestmentFfi>,
        val investmentLotsByInvestmentId: Map<Long, List<TrackingInvestmentLotFfi>>,
        val investmentSellsByInvestmentId: Map<Long, List<TrackingInvestmentSellFfi>>,
        val lotDisposalsByInvestmentId: Map<Long, List<TrackingLotDisposalFfi>>,
        val manualAssets: List<TrackingManualAssetFfi>,
        val manualAssetsSat: ULong,
        val manualAssetsByKindSat: Map<String, ULong>,
        val rules: List<TrackingRuleFfi>,
        val recurringCandidates: List<RecurringCandidate>,
        val perWalletTxs: Map<String, List<TransactionRecord>>,
        val eventLegs: List<PaymentEvent> = emptyList(),
        val costBasisInputs: Map<String, io.bhodl.ffi.EventCostBasisFfi> = emptyMap(),
    )

    /** Registry v6 — the three `details_json` tables, parsed. A failing
     *  table reads as empty (the cards then show no details). */
    private fun loadPortfolioDetails(registry: io.bhodl.ffi.RegistryHandle): io.bhodl.desktop.screens.PortfolioDetails {
        fun load(table: String): Map<Long, Map<String, String>> =
            runCatching { registry.listPortfolioDetails(table) }
                .getOrDefault(emptyList())
                .associate { it.id to io.bhodl.desktop.screens.parseDetailsJson(it.detailsJson) }
        return io.bhodl.desktop.screens.PortfolioDetails(
            investments = load("investment"),
            manualAssets = load("manual_asset"),
            liabilities = load("liability"),
        )
    }

    /** Test-only seam: prime the cache so a Compose UI test can render
     *  TrackerHome / TrackingScreen without a real registry. */
    internal fun seedForTest(
        wallets: List<WalletRecordFfi> = emptyList(),
        watchBalances: Map<String, ULong> = emptyMap(),
        // Phase 7.3 — tests can seed each layer independently. For
        // back-compat the legacy single `totalAssetSat` is interpreted
        // as `bitcoinAssetSat` when the new params are left at their
        // defaults — existing tests keep passing without an update.
        totalAssetSat: ULong = 0UL,
        bitcoinAssetSat: ULong = totalAssetSat,
        investmentsAssetSat: ULong = 0UL,
        investmentsPricingAvailable: Boolean = false,
        liabilities: List<TrackingLiabilityFfi> = emptyList(),
        portfolioDetails: io.bhodl.desktop.screens.PortfolioDetails = io.bhodl.desktop.screens.PortfolioDetails(),
        investments: List<TrackingInvestmentFfi> = emptyList(),
        investmentLotsByInvestmentId: Map<Long, List<TrackingInvestmentLotFfi>> = emptyMap(),
        investmentSellsByInvestmentId: Map<Long, List<TrackingInvestmentSellFfi>> = emptyMap(),
        lotDisposalsByInvestmentId: Map<Long, List<TrackingLotDisposalFfi>> = emptyMap(),
        manualAssets: List<TrackingManualAssetFfi> = emptyList(),
        manualAssetsSat: ULong = 0UL,
        manualAssetsByKindSat: Map<String, ULong> = emptyMap(),
        bills: List<TrackingBillFfi> = emptyList(),
        budgets: List<TrackingBudgetFfi> = emptyList(),
        goals: List<TrackingSavingsGoalFfi> = emptyList(),
        events: List<PaymentEvent> = emptyList(),
        categories: List<String> = emptyList(),
        views: List<SavedViewFfi> = emptyList(),
        perWalletTxs: Map<String, List<TransactionRecord>> = emptyMap(),
        staleWalletIds: Set<String> = emptySet(),
        lastSyncUnix: Long? = System.currentTimeMillis() / 1000L,
        lastError: String? = null,
        eventLegs: List<PaymentEvent> = emptyList(),
        refreshing: Boolean = false,
    ) {
        _lastError.value = lastError
        lastErrorIsSync = lastError != null
        _eventLegs.value = eventLegs
        _refreshing.value = refreshing
        _wallets.value = wallets
        _watchBalances.value = watchBalances
        _bitcoinAssetSat.value = bitcoinAssetSat
        _investmentsAssetSat.value = investmentsAssetSat
        _investmentsPricingAvailable.value = investmentsPricingAvailable
        _totalAssetSat.value = bitcoinAssetSat + investmentsAssetSat + manualAssetsSat
        _liabilities.value = liabilities
        _portfolioDetails.value = portfolioDetails
        _investments.value = investments
        _investmentLotsByInvestmentId.value = investmentLotsByInvestmentId
        _investmentSellsByInvestmentId.value = investmentSellsByInvestmentId
        _lotDisposalsByInvestmentId.value = lotDisposalsByInvestmentId
        _manualAssets.value = manualAssets
        _manualAssetsSat.value = manualAssetsSat
        _manualAssetsByKindSat.value = manualAssetsByKindSat
        _bills.value = bills
        _budgets.value = budgets
        _goals.value = goals
        _events.value = events
        _categories.value = categories
        _views.value = views
        _perWalletTxs.value = perWalletTxs
        _staleWalletIds.value = staleWalletIds
        _lastSyncUnix.value = lastSyncUnix
        _hasLoadedOnce.value = true
    }
}

/**
 * One event per txid out of the per-wallet legs: prefer the leg carrying
 * a user category, then the one that knows the fee (the debit leg, so an
 * internal transfer can count its fee as the only outflow), else the
 * first. Newest first, mempool (no timestamp) on top.
 */
internal fun mergeEventLegs(legs: List<PaymentEvent>): List<PaymentEvent> = legs
    .groupBy { it.txidOrPaymentId }
    .values
    .map { group ->
        group.firstOrNull { it.category != null }
            ?: group.firstOrNull { it.feeSat != null }
            ?: group.first()
    }
    .sortedWith(compareByDescending<PaymentEvent> { it.timestampUnix ?: ULong.MAX_VALUE })
