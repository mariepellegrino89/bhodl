package io.bhodl.desktop.settings

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import io.bhodl.desktop.prices.FiatCurrency
import io.bhodl.desktop.ui.money.UnitFormat
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.ffi.Network
import io.bhodl.shared.BtcUnit
import io.bhodl.ui.theme.BhodlDensity
import io.bhodl.ui.theme.ColorConvention
import java.io.File

/**
 * Process-lifetime store for app-wide preferences.
 *
 * Three slots today:
 * - [`themeMode`] — Dark / Light / System (default System).
 * - [`esploraUrls`] — per-network Esplora overrides; a missing entry
 *   means "use the built-in mempool.space default for that network"
 *   (see [EsploraEndpoint.current]).
 * - [`localeOverride`] — `null` means follow the OS, otherwise an
 *   explicit BCP-47 tag we plan to honour once i18n strings ship.
 *
 * Reactive Compose state via [`mutableStateOf`]: any composable that
 * reads `SettingsStore.themeMode.value` recomposes when the value
 * changes. The state lives outside the navigation graph because the
 * theme has to wrap [`io.bhodl.ui.theme.BhodlTheme`] at the very root
 * — not inside any single screen.
 *
 * Persistence: in-memory only for the alpha demo. The next iteration
 * will write through to a small JSON file under
 * `WalletPaths.defaultDataDir() / settings.json` and rehydrate on
 * launch. We deliberately keep this off-the-keyset DB so a wallet
 * lock does not also lock the user's theme preference.
 */
public object SettingsStore {

    // T2-11 — when set, every write-through setter persists the full
    // snapshot to this file. Null = in-memory only (default; tests
    // never opt in to avoid disk side-effects).
    private var persistFile: File? = null

    /**
     * Enable JSON persistence to [file]. Subsequent setter calls write
     * a snapshot of the entire store to disk atomically (`<file>.tmp`
     * + rename). Idempotent — calling twice with the same file is a
     * no-op for state.
     *
     * Does NOT call [SettingsPersistence.rehydrate] — the caller
     * (Main.kt) is responsible for that, so it can choose to load
     * BEFORE installing the persistence hook (rehydrate would otherwise
     * trigger N file writes during the apply pass).
     */
    public fun enablePersistence(file: File) {
        persistFile = file
    }

    /** Disable persistence. Used by tests after exercising the path. */
    public fun disablePersistence() {
        persistFile = null
    }

    // Audit 2026-10 (M9) — set by SettingsPersistence.rehydrate when
    // settings.json existed but could not be loaded (corrupt JSON,
    // future version, unreadable). The UI shows it as a banner so the
    // user knows their preferences were reset; `null` = all good.
    private val _persistenceWarning = mutableStateOf<String?>(null)
    public val persistenceWarning: State<String?> get() = _persistenceWarning

    public fun setPersistenceWarning(message: String?) {
        _persistenceWarning.value = message
    }

    private fun persistIfEnabled() {
        val f = persistFile ?: return
        // Failures are swallowed: a disk-full event must not crash a
        // wallet that was operating purely in memory. The next
        // successful write will resync.
        SettingsPersistence.save(f)
    }

    private val _themeMode = mutableStateOf(ThemeMode.System)

    /** Reactive Compose state; read `.value` inside a composable. */
    public val themeMode: State<ThemeMode> get() = _themeMode

    public fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        persistIfEnabled()
    }

    // Audit 2026-10 (M5, KDF-audit info) — Esplora overrides are stored
    // PER NETWORK. A single global override used to be applied to every
    // wallet, so a custom signet server was also hit by mainnet wallets
    // (and watch wallets synced against the signing wallet's endpoint).
    private val _esploraUrls = mutableStateOf<Map<Network, String>>(emptyMap())

    /** Every per-network Esplora override (absent = built-in default). */
    public val esploraUrls: State<Map<Network, String>> get() = _esploraUrls

    /**
     * Override for the network of the currently loaded signing wallet
     * ([WalletStore.network]); `null` = built-in default. Reading `.value`
     * inside a composable tracks the override map.
     */
    public val esploraUrl: State<String?> = object : State<String?> {
        override val value: String?
            get() = _esploraUrls.value[WalletStore.network()]
    }

    /** Override for [network], or `null` for the built-in default. */
    public fun esploraUrlFor(network: Network): String? = _esploraUrls.value[network]

    /** Set / clear the override for the loaded wallet's network. */
    public fun setEsploraUrl(url: String?) {
        setEsploraUrl(WalletStore.network(), url)
    }

    /**
     * Set / clear the override for [network].
     *
     * Security review — only http(s) URLs with a host are accepted.
     * Invalid input is silently dropped (the previous value stays);
     * the Settings UI runs [isValidEsploraUrl] first so it can show
     * an inline error instead of relying on this guard.
     */
    public fun setEsploraUrl(network: Network, url: String?) {
        val cleaned = url?.trim()?.takeIf { it.isNotBlank() }
        if (cleaned != null && !isValidEsploraUrl(cleaned)) return
        _esploraUrls.value = if (cleaned == null) {
            _esploraUrls.value - network
        } else {
            _esploraUrls.value + (network to cleaned)
        }
        persistIfEnabled()
    }

    /**
     * `true` when [url] parses as a [java.net.URI] with an `http` /
     * `https` scheme and a non-blank host. Everything else (garbage,
     * `file://`, missing host, javascript:, …) is refused before it
     * can reach the sync stack.
     */
    public fun isValidEsploraUrl(url: String): Boolean {
        val uri = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        return !uri.host.isNullOrBlank()
    }

    /**
     * Returns a human-readable warning when [url] uses the http scheme
     * and the host is NOT a loopback address. Returns null when the URL
     * is safe (https, or http to localhost / 127.0.0.1 / ::1 / [::1]).
     *
     * Pure function with no side-effects; the UI layer can call it to
     * surface an inline banner without blocking the user from saving --
     * http to a local node is a legitimate configuration.
     *
     * Security note (A01/A02): prevents accidental cleartext transmission
     * of Esplora requests to a remote host, which would expose wallet
     * xpubs and queried address sets to a network observer.
     *
     * A UI follow-up will display this string as a yellow inline banner
     * beneath the Esplora URL input field in Settings.
     */
    public fun esploraUrlWarning(url: String): String? {
        val uri = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() != "http") return null
        // Strip IPv6 brackets so "[::1]" normalises to "::1" before the set lookup.
        val host = uri.host?.lowercase()?.trimStart('[')?.trimEnd(']') ?: return null
        if (host in LOCAL_ESPLORA_HOSTS) return null
        return "Using http:// with a remote host sends wallet data in cleartext. " +
            "Use https:// for remote Esplora endpoints."
    }

    /** Hosts considered local for the purpose of [esploraUrlWarning]. */
    private val LOCAL_ESPLORA_HOSTS: Set<String> = setOf(
        "localhost",
        "127.0.0.1",
        "::1",
    )

    private val _localeOverride = mutableStateOf<String?>(null)
    public val localeOverride: State<String?> get() = _localeOverride

    public fun setLocaleOverride(tag: String?) {
        _localeOverride.value = tag?.trim()?.takeIf { it.isNotBlank() }
        persistIfEnabled()
    }

    private val _torEnabled = mutableStateOf(false)
    public val torEnabled: State<Boolean> get() = _torEnabled

    public fun setTorEnabled(enabled: Boolean) {
        _torEnabled.value = enabled
        persistIfEnabled()
    }

    private val _torProxyUrl = mutableStateOf(DEFAULT_TOR_PROXY_URL)
    public val torProxyUrl: State<String> get() = _torProxyUrl

    /**
     * Security review — only `socks5://host:port` / `socks5h://host:port`
     * shapes (port 1–65535) are accepted; blank resets to the default.
     * Invalid input is dropped (previous value stays) — the Settings
     * UI runs [isValidTorProxyUrl] first for the inline error.
     *
     * Audit 2026-10 — `socks5://` input is still ACCEPTED but stored as
     * `socks5h://` (see [normalizeTorProxyUrl]): with plain socks5 some
     * clients resolve hostnames locally, leaking every Esplora / price
     * host to the system DNS resolver outside Tor.
     */
    public fun setTorProxyUrl(url: String) {
        val cleaned = url.trim().ifBlank { DEFAULT_TOR_PROXY_URL }
        if (!isValidTorProxyUrl(cleaned)) return
        _torProxyUrl.value = normalizeTorProxyUrl(cleaned)
        persistIfEnabled()
    }

    /** Rewrite a `socks5://` proxy URL to `socks5h://` (remote DNS). */
    public fun normalizeTorProxyUrl(url: String): String {
        val trimmed = url.trim()
        return if (trimmed.startsWith("socks5://", ignoreCase = true)) {
            "socks5h://" + trimmed.substring("socks5://".length)
        } else {
            trimmed
        }
    }

    /** `true` for `socks5://host:port` or `socks5h://host:port` with port 1–65535. */
    public fun isValidTorProxyUrl(url: String): Boolean {
        val match = TOR_PROXY_REGEX.matchEntire(url.trim()) ?: return false
        val port = match.groupValues[2].toIntOrNull() ?: return false
        return port in 1..65535
    }

    private val TOR_PROXY_REGEX = Regex("""socks5h?://([^\s:/]+):(\d{1,5})""")

    // Phase 3.10 — cultural color convention. Western (default) reads
    // green as positive, red as negative; Eastern flips them per the
    // CN/JP/KR market convention. Only swaps the semantic financial
    // colors — brand and error stay put so error states remain
    // recognisable across both modes.
    private val _colorConvention = mutableStateOf(ColorConvention.WESTERN)
    public val colorConvention: State<ColorConvention> get() = _colorConvention

    public fun setColorConvention(convention: ColorConvention) {
        _colorConvention.value = convention
        persistIfEnabled()
    }

    // Phase 3.11 — display unit for on-chain amounts.
    //
    // UX review 2026-10 — there used to be TWO unit settings: this
    // sat/BTC toggle (wallet screens) and the Bitcoin-Lens
    // [unitFormat] (tracker). They are now one preference,
    // [unitFormat]; [btcUnit] is a read-only projection kept for the
    // call sites that still format through `io.bhodl.shared.formatAmount`
    // (ALWAYS_BTC → BTC, everything else → SAT).
    public val btcUnit: State<BtcUnit> = object : State<BtcUnit> {
        override val value: BtcUnit
            get() = if (_unitFormat.value == UnitFormat.ALWAYS_BTC) BtcUnit.BTC else BtcUnit.SAT
    }

    /** Legacy sat/BTC setter — maps onto the unified [unitFormat]. */
    public fun setBtcUnit(unit: BtcUnit) {
        setUnitFormat(
            when (unit) {
                BtcUnit.SAT -> UnitFormat.ALWAYS_SAT
                BtcUnit.BTC -> UnitFormat.ALWAYS_BTC
            },
        )
    }

    // UX review 2026-10 — BIP177 notation, opt-in: sats amounts render
    // as `₿4,230,118` instead of `4,230,118 sat`.
    private val _bip177Notation = mutableStateOf(false)
    public val bip177Notation: State<Boolean> get() = _bip177Notation

    public fun setBip177Notation(enabled: Boolean) {
        _bip177Notation.value = enabled
        persistIfEnabled()
    }

    // UX review 2026-10 — table / list row density. Compact (32dp rows)
    // is the default; Ultra-dense (24dp) is opt-in. Provided to the UI
    // through `BhodlTheme(density = …)` at the composition root.
    private val _density = mutableStateOf(BhodlDensity.Compact)
    public val density: State<BhodlDensity> get() = _density

    public fun setDensity(density: BhodlDensity) {
        _density.value = density
        persistIfEnabled()
    }

    // Phase 5.0 — fiat overlay. NONE disables the overlay entirely
    // (amounts render only in sat/BTC). EUR/USD/CHF enable the
    // PriceCache to fetch BTC spot price and show "amount sat (~€X)"
    // via the displayAmountWithFiat() composable wrapper. The choice
    // also drives investments price conversion in Phase 5.1.
    private val _fiatCurrency = mutableStateOf(DEFAULT_FIAT_CURRENCY)
    public val fiatCurrency: State<FiatCurrency> get() = _fiatCurrency

    public fun setFiatCurrency(currency: FiatCurrency) {
        _fiatCurrency.value = currency
        persistIfEnabled()
    }

    // Phase 5.2 — auto-fetch stock/ETF/commodity prices for
    // investments with a ticker, via Yahoo Finance. When off, the
    // user maintains current values manually. When on, the
    // PriceCache loop calls Yahoo every refresh tick and writes the
    // fetched price into the registry (manual_current_value column).
    // Honours SettingsStore.torEnabled for routing.
    private val _autoFetchInvestmentPrices = mutableStateOf(false)
    public val autoFetchInvestmentPrices: State<Boolean> get() = _autoFetchInvestmentPrices

    public fun setAutoFetchInvestmentPrices(enabled: Boolean) {
        _autoFetchInvestmentPrices.value = enabled
        persistIfEnabled()
    }

    // Phase 3.14 — wallet IDs the user has chosen to EXCLUDE from
    // cross-wallet totals and from the Tracking dashboard
    // aggregations. The wallet still appears in the History → Wallets
    // list (so the user can re-include it) but doesn't contribute to
    // KPI / charts / Reports tables. In-memory only for now; persists
    // across the session but not across app restarts.
    private val _excludedWalletIds = mutableStateOf<Set<String>>(emptySet())
    public val excludedWalletIds: State<Set<String>> get() = _excludedWalletIds

    public fun setWalletIncluded(walletId: String, included: Boolean) {
        _excludedWalletIds.value = if (included) {
            _excludedWalletIds.value - walletId
        } else {
            _excludedWalletIds.value + walletId
        }
        // R4 — the exclusion policy is now durable across restarts.
        // Excluding an important wallet from the net-worth calculation
        // and having it silently re-included on the next launch was a
        // surprising behaviour in a Bitcoin-first app; the choice is
        // written through to settings.json like every other preference.
        persistIfEnabled()
    }

    /** Replace the excluded-wallet set without triggering a write-through,
     *  used by [SettingsPersistence.apply] during rehydrate (the caller
     *  installs the persistence hook only AFTER rehydrate). */
    internal fun replaceExcludedWalletIds(ids: Set<String>) {
        _excludedWalletIds.value = ids
    }

    // Phase 7.2 — privacy auto-hide; UX review 2026-10 (item 13) — now
    // the persisted "Start with balances hidden" preference of the
    // app-wide privacy mode: at launch (and after every lock) the
    // session [balancesHidden] flag starts from this value.
    private val _privacyAutoHide = mutableStateOf(false)
    public val privacyAutoHide: State<Boolean> get() = _privacyAutoHide

    public fun setPrivacyAutoHide(enabled: Boolean) {
        _privacyAutoHide.value = enabled
        persistIfEnabled()
    }

    // UX review 2026-10 (item 13) — the app-wide privacy mode itself.
    // Session state, NOT persisted (only [privacyAutoHide] is): while
    // true every amount rendered through the central formatters
    // (`BitcoinAmountFormat`, `Money`, the fiat helpers in
    // `ui/AmountFormat.kt`) is masked — see `ui/money/AmountMask.kt`.
    // Toggled with Ctrl+Shift+H or the eye icon in the footer.
    private val _balancesHidden = mutableStateOf(false)
    public val balancesHidden: State<Boolean> get() = _balancesHidden

    public fun setBalancesHidden(hidden: Boolean) {
        _balancesHidden.value = hidden
    }

    public fun toggleBalancesHidden() {
        _balancesHidden.value = !_balancesHidden.value
    }

    /**
     * Re-arm the session flag from "Start with balances hidden" — at
     * launch and on every lock. Only ever HIDES: a lock never reveals
     * amounts the user had hidden by hand.
     */
    public fun applyStartHidden() {
        if (_privacyAutoHide.value) _balancesHidden.value = true
    }

    // UX review 2026-10 (item 14) — Security Hub timestamps. Only
    // non-sensitive facts are kept: WHEN something happened, keyed by
    // wallet UUID (already present in this file via excludedWalletIds),
    // never what was exported or any secret material. Unix seconds.
    private val _backupVerifiedAt = mutableStateOf<Map<String, Long>>(emptyMap())
    /** Recovery phrase verified (wizard challenge, restore, or Security Hub check). */
    public val backupVerifiedAt: State<Map<String, Long>> get() = _backupVerifiedAt

    private val _backupExportedAt = mutableStateOf<Map<String, Long>>(emptyMap())
    /** Last encrypted (age) backup export. */
    public val backupExportedAt: State<Map<String, Long>> get() = _backupExportedAt

    private val _labelsExportedAt = mutableStateOf<Map<String, Long>>(emptyMap())
    /** Last BIP-329 labels export. */
    public val labelsExportedAt: State<Map<String, Long>> get() = _labelsExportedAt

    public fun markBackupVerified(walletId: String, unix: Long = System.currentTimeMillis() / 1000L) {
        _backupVerifiedAt.value = _backupVerifiedAt.value + (walletId to unix)
        persistIfEnabled()
    }

    public fun markBackupExported(walletId: String, unix: Long = System.currentTimeMillis() / 1000L) {
        _backupExportedAt.value = _backupExportedAt.value + (walletId to unix)
        persistIfEnabled()
    }

    public fun markLabelsExported(walletId: String, unix: Long = System.currentTimeMillis() / 1000L) {
        _labelsExportedAt.value = _labelsExportedAt.value + (walletId to unix)
        persistIfEnabled()
    }

    // Spaced security reminders: dismissing snoozes them for a growing
    // interval (see SecurityReminderPolicy) instead of nagging daily.
    // Keyed per wallet UUID (security review): snoozing one wallet's
    // reminder must not silence another wallet's.
    private val _securityReminderSnoozedUntil = mutableStateOf<Map<String, Long>>(emptyMap())
    public val securityReminderSnoozedUntil: State<Map<String, Long>> get() = _securityReminderSnoozedUntil
    private val _securityReminderDismissCount = mutableStateOf<Map<String, Int>>(emptyMap())
    public val securityReminderDismissCount: State<Map<String, Int>> get() = _securityReminderDismissCount

    public fun snoozeSecurityReminder(walletId: String, untilUnix: Long) {
        _securityReminderSnoozedUntil.value = _securityReminderSnoozedUntil.value + (walletId to untilUnix)
        val count = (_securityReminderDismissCount.value[walletId] ?: 0) + 1
        _securityReminderDismissCount.value = _securityReminderDismissCount.value + (walletId to count)
        persistIfEnabled()
    }

    /**
     * Security review — forget every Security Hub date and snooze, e.g.
     * after a local wipe (the wallets they describe are gone).
     */
    public fun clearSecurityState() {
        _backupVerifiedAt.value = emptyMap()
        _backupExportedAt.value = emptyMap()
        _labelsExportedAt.value = emptyMap()
        _securityReminderSnoozedUntil.value = emptyMap()
        _securityReminderDismissCount.value = emptyMap()
        persistIfEnabled()
    }

    // UX review 2026-10 (item 16) — the one-time "sats and BTC" units
    // explainer of the first-run onboarding has been acknowledged.
    private val _onboardingUnitsSeen = mutableStateOf(false)
    public val onboardingUnitsSeen: State<Boolean> get() = _onboardingUnitsSeen

    public fun markOnboardingUnitsSeen() {
        _onboardingUnitsSeen.value = true
        persistIfEnabled()
    }

    internal fun replaceOnboardingUnitsSeen(seen: Boolean) {
        _onboardingUnitsSeen.value = seen
    }

    /** Rehydrate the Security Hub fields without a write-through. */
    internal fun replaceSecurityTimestamps(
        backupVerifiedAt: Map<String, Long>,
        backupExportedAt: Map<String, Long>,
        labelsExportedAt: Map<String, Long>,
        reminderSnoozedUntil: Map<String, Long>,
        reminderDismissCount: Map<String, Int>,
    ) {
        _backupVerifiedAt.value = backupVerifiedAt
        _backupExportedAt.value = backupExportedAt
        _labelsExportedAt.value = labelsExportedAt
        _securityReminderSnoozedUntil.value = reminderSnoozedUntil
        _securityReminderDismissCount.value = reminderDismissCount
    }

    // Phase 6.0 — per-wallet "do not nag me again about Tracker pairing"
    // memory. The Dashboard surfaces a one-shot banner on every first
    // unlock of a signing wallet that is not yet paired as a watch-only
    // entry. The user can either accept ("Add to Tracker") or skip;
    // skipping persists the wallet's UUID here so the banner does not
    // re-appear on the next unlock. Stored in settings.json (persisted
    // alongside theme, fiat, etc.) so the choice survives app restarts.
    private val _trackerPairingSkipped = mutableStateOf<Set<String>>(emptySet())
    public val trackerPairingSkipped: State<Set<String>> get() = _trackerPairingSkipped

    public fun markTrackerPairingSkipped(walletId: String) {
        _trackerPairingSkipped.value = _trackerPairingSkipped.value + walletId
        persistIfEnabled()
    }

    public fun clearTrackerPairingSkipped(walletId: String) {
        _trackerPairingSkipped.value = _trackerPairingSkipped.value - walletId
        persistIfEnabled()
    }

    internal fun replaceTrackerPairingSkipped(ids: Set<String>) {
        _trackerPairingSkipped.value = ids
    }

    // Phase 7.6 — last Unix timestamp the user clicked "Dismiss" on
    // the monthly-review banner. The banner reappears the first day
    // of every month and stays visible for the first 3 days, unless
    // the user dismissed it at any point within the current month.
    // Persisted so the choice survives app restarts. 0 = never
    // dismissed (the initial state).
    private val _lastMonthlyReviewDismissedUnix = mutableStateOf(0L)
    public val lastMonthlyReviewDismissedUnix: State<Long>
        get() = _lastMonthlyReviewDismissedUnix

    public fun markMonthlyReviewDismissed(unix: Long = System.currentTimeMillis() / 1000L) {
        _lastMonthlyReviewDismissedUnix.value = unix
        persistIfEnabled()
    }

    internal fun replaceLastMonthlyReviewDismissedUnix(unix: Long) {
        _lastMonthlyReviewDismissedUnix.value = unix
    }

    // -- Phase 8.3.1 — Bitcoin Lens advanced display preferences --------
    //
    // Five slots: one chooses how the BTC magnitude is rendered
    // (sat / BTC / auto-by-threshold), the other four toggle whether
    // a fiat secondary line is rendered on the four "operational
    // surfaces" — bank-CSV import rows, current-month fiat-counterparty
    // transactions, tax exports, invoice / receipt attachments.
    // Everywhere ELSE in the app, fiat stays hidden by default so the
    // unit of account remains BTC. The toggles are not user-toggleable
    // per-view — they are advanced global preferences gating the
    // composer of each view.

    // UX review 2026-10 — the single "Bitcoin unit" preference for the
    // whole app (Settings › Appearance): Sats (default) / BTC / Auto.
    private val _unitFormat = mutableStateOf(DEFAULT_UNIT_FORMAT)
    public val unitFormat: State<UnitFormat> get() = _unitFormat

    public fun setUnitFormat(uf: UnitFormat) {
        _unitFormat.value = uf
        persistIfEnabled()
    }

    private val _showFiatInBankCsv = mutableStateOf(true)
    public val showFiatInBankCsv: State<Boolean> get() = _showFiatInBankCsv

    public fun setShowFiatInBankCsv(b: Boolean) {
        _showFiatInBankCsv.value = b
        persistIfEnabled()
    }

    private val _showFiatInCurrentMonthFiatTx = mutableStateOf(true)
    public val showFiatInCurrentMonthFiatTx: State<Boolean> get() = _showFiatInCurrentMonthFiatTx

    public fun setShowFiatInCurrentMonthFiatTx(b: Boolean) {
        _showFiatInCurrentMonthFiatTx.value = b
        persistIfEnabled()
    }

    private val _showFiatInTaxExports = mutableStateOf(true)
    public val showFiatInTaxExports: State<Boolean> get() = _showFiatInTaxExports

    public fun setShowFiatInTaxExports(b: Boolean) {
        _showFiatInTaxExports.value = b
        persistIfEnabled()
    }

    private val _showFiatInInvoiceReceipts = mutableStateOf(true)
    public val showFiatInInvoiceReceipts: State<Boolean> get() = _showFiatInInvoiceReceipts

    public fun setShowFiatInInvoiceReceipts(b: Boolean) {
        _showFiatInInvoiceReceipts.value = b
        persistIfEnabled()
    }

    // Phase 8.3.2 — Bitcoin Lens: show the fiat overlay (dashed line)
    // on the net-worth sparkline. When ON (default), the sat-on-y
    // primary line is accompanied by a tenuous dashed line of the
    // fiat value over the same period, illustrating the BTC-vs-fiat
    // divergence directly inside the hero chart. The toggle is in the
    // "Bitcoin Lens (advanced)" Settings section, so a user who wants
    // a sat-only chart can opt out without losing the operational
    // surfaces (bank CSV / current month / tax / invoices).
    private val _showFiatOverlayOnNwChart = mutableStateOf(true)
    public val showFiatOverlayOnNwChart: State<Boolean> get() = _showFiatOverlayOnNwChart

    public fun setShowFiatOverlayOnNwChart(b: Boolean) {
        _showFiatOverlayOnNwChart.value = b
        persistIfEnabled()
    }

    // -- Tracker Modules (progressive unlock) -------------------------------
    //
    // Five feature modules gated behind boolean flags. All default to
    // false so a fresh install shows only NW Hero + Wallets. The user
    // activates modules from the dedicated Modules page or from
    // Settings → Modules.

    private val _moduleInvestments = mutableStateOf(false)
    public val moduleInvestments: State<Boolean> get() = _moduleInvestments
    public fun setModuleInvestments(v: Boolean) { _moduleInvestments.value = v; persistIfEnabled() }

    private val _moduleBudgetBills = mutableStateOf(false)
    public val moduleBudgetBills: State<Boolean> get() = _moduleBudgetBills
    public fun setModuleBudgetBills(v: Boolean) { _moduleBudgetBills.value = v; persistIfEnabled() }

    private val _moduleSavingsGoals = mutableStateOf(false)
    public val moduleSavingsGoals: State<Boolean> get() = _moduleSavingsGoals
    public fun setModuleSavingsGoals(v: Boolean) { _moduleSavingsGoals.value = v; persistIfEnabled() }

    private val _moduleManualAssets = mutableStateOf(false)
    public val moduleManualAssets: State<Boolean> get() = _moduleManualAssets
    public fun setModuleManualAssets(v: Boolean) { _moduleManualAssets.value = v; persistIfEnabled() }

    private val _moduleAnalyticsLabs = mutableStateOf(false)
    public val moduleAnalyticsLabs: State<Boolean> get() = _moduleAnalyticsLabs
    public fun setModuleAnalyticsLabs(v: Boolean) { _moduleAnalyticsLabs.value = v; persistIfEnabled() }

    private val _modulesBannerDismissed = mutableStateOf(false)
    public val modulesBannerDismissed: State<Boolean> get() = _modulesBannerDismissed
    public fun setModulesBannerDismissed(v: Boolean) { _modulesBannerDismissed.value = v; persistIfEnabled() }

    public val anyModuleEnabled: Boolean
        get() = _moduleInvestments.value || _moduleBudgetBills.value ||
            _moduleSavingsGoals.value || _moduleManualAssets.value || _moduleAnalyticsLabs.value

    /**
     * Reset every preference to its default. Used by tests and (in the
     * future) by a "Reset to defaults" button in Settings.
     */
    public fun reset() {
        _themeMode.value = ThemeMode.System
        _esploraUrls.value = emptyMap()
        _localeOverride.value = null
        _torEnabled.value = false
        _torProxyUrl.value = DEFAULT_TOR_PROXY_URL
        _colorConvention.value = ColorConvention.WESTERN
        _bip177Notation.value = false
        _density.value = BhodlDensity.Compact
        _excludedWalletIds.value = emptySet()
        _fiatCurrency.value = DEFAULT_FIAT_CURRENCY
        _autoFetchInvestmentPrices.value = false
        _privacyAutoHide.value = false
        _balancesHidden.value = false
        _backupVerifiedAt.value = emptyMap()
        _backupExportedAt.value = emptyMap()
        _labelsExportedAt.value = emptyMap()
        _securityReminderSnoozedUntil.value = emptyMap()
        _securityReminderDismissCount.value = emptyMap()
        _onboardingUnitsSeen.value = false
        _trackerPairingSkipped.value = emptySet()
        _lastMonthlyReviewDismissedUnix.value = 0L
        // Phase 8.3.1 — reset the Bitcoin-Lens preferences too.
        _unitFormat.value = DEFAULT_UNIT_FORMAT
        _showFiatInBankCsv.value = true
        _showFiatInCurrentMonthFiatTx.value = true
        _showFiatInTaxExports.value = true
        _showFiatInInvoiceReceipts.value = true
        // Phase 8.3.2 — Bitcoin Lens net-worth fiat overlay toggle.
        _showFiatOverlayOnNwChart.value = true
        // Tracker modules — all off by default.
        _moduleInvestments.value = false
        _moduleBudgetBills.value = false
        _moduleSavingsGoals.value = false
        _moduleManualAssets.value = false
        _moduleAnalyticsLabs.value = false
        _modulesBannerDismissed.value = false
        _persistenceWarning.value = null
        // Also clears the persistence hook so tests that call reset()
        // expecting a clean slate don't inherit a file from a previous
        // test method that left enablePersistence() on.
        persistFile = null
    }

    /**
     * The Tor SOCKS5 endpoint a stock Tor Browser / standalone Tor
     * daemon listens on. `socks5h://` (vs `socks5://`) tells the
     * underlying transport to resolve hostnames inside the proxy,
     * avoiding a host DNS leak.
     */
    public const val DEFAULT_TOR_PROXY_URL: String = "socks5h://127.0.0.1:9050"

    /**
     * Audit 2026-10 — single source of truth for the fiat default. The
     * initial store value (EUR), [reset] (NONE) and the persisted
     * snapshot default (NONE) used to disagree, so an older snapshot
     * missing the field silently switched the user's currency.
     */
    public val DEFAULT_FIAT_CURRENCY: FiatCurrency get() = FiatCurrency.NONE

    /** UX review 2026-10 (user decision): amounts default to sats. */
    public val DEFAULT_UNIT_FORMAT: UnitFormat get() = UnitFormat.ALWAYS_SAT
}

/**
 * Theme selector exposed in Settings › Appearance. [System] is the
 * default (UX review 2026-10, user decision): it follows the OS
 * light/dark setting via `isSystemInDarkTheme()`. A theme the user
 * picked explicitly is persisted and wins over the default.
 */
public enum class ThemeMode {
    /** Always use the dark color palette. */
    Dark,

    /** Always use the light color palette. */
    Light,

    /** Follow `isSystemInDarkTheme()` — usually OS-driven. */
    System,

    /**
     * Phase 4.1 — Wealth Builder neon variant (navy background, cyan
     * accent, gradient cards, glow borders). Opt-in: stays disabled
     * unless the user picks it from Settings. Overrides the Dark /
     * Light / System pick and applies the neon palette app-wide.
     */
    Neon,
    ;

    /**
     * `true` when this mode renders with a dark palette. [System]
     * follows [systemDark] (`isSystemInDarkTheme()` at the root).
     */
    public fun usesDarkPalette(systemDark: Boolean): Boolean = when (this) {
        Dark, Neon -> true
        Light -> false
        System -> systemDark
    }
}
