package io.bhodl.desktop.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import io.bhodl.desktop.ui.bhodlCard
import io.bhodl.shared.resources.stg_general
import io.bhodl.shared.resources.stg_general_sub
import io.bhodl.shared.resources.stg_appearance_sub
import io.bhodl.shared.resources.stg_network
import io.bhodl.shared.resources.stg_network_sub
import io.bhodl.shared.resources.stg_lens
import io.bhodl.shared.resources.stg_lens_sub
import io.bhodl.shared.resources.stg_tracker
import io.bhodl.shared.resources.stg_tracker_sub
import io.bhodl.shared.resources.stg_security
import io.bhodl.shared.resources.stg_security_sub
import io.bhodl.shared.resources.stg_data
import io.bhodl.shared.resources.stg_data_sub
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.settings.EsploraEndpoint
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.ui.BhodlTopBar
import io.bhodl.desktop.settings.ThemeMode
import io.bhodl.desktop.wizard.WalletPaths
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.desktop.ui.userMessage
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.wallet_settings_moved
import io.bhodl.shared.resources.wallet_settings_open
import io.bhodl.shared.resources.action_back
import io.bhodl.shared.resources.settings_title
import io.bhodl.desktop.prices.FiatCurrency
import io.bhodl.desktop.prices.PriceCache
import io.bhodl.desktop.ui.money.UnitFormat
import io.bhodl.shared.resources.settings_appearance_section
import io.bhodl.shared.resources.security_hub_open
import io.bhodl.ui.theme.BhodlDensity
import io.bhodl.ui.theme.BhodlTheme
import io.bhodl.ui.theme.ColorConvention
import org.jetbrains.compose.resources.stringResource

/**
 * Application-wide preferences.
 *
 * - **Appearance** (UX review 2026-10): Theme (System default / Light /
 *   Dark / Neon), Bitcoin unit (Sats default / BTC / Auto + opt-in
 *   BIP177 notation) and row Density (Compact default / Ultra-dense).
 *   State lives in [`SettingsStore`]; `Main.kt` observes it and
 *   re-themes the whole tree on change.
 * - Esplora endpoint, Tor, language, fiat overlay, privacy, tracker
 *   modules, Bitcoin Lens surfaces and the full data wipe follow.
 *   Wallet-scoped operations (backup, labels, recovery phrase, …) live
 *   in the wallet workspace's "Wallet settings" tab.
 */
@Composable
public fun SettingsScreen(
    onBack: () -> Unit,
    onWalletWiped: () -> Unit = {},
    /** Opens Wallet › Wallet settings; `null` hides the button. */
    onOpenWalletSettings: (() -> Unit)? = null,
    /** UX review 2026-10 (item 14) — opens the Security Hub; `null` hides the entry. */
    onOpenSecurityHub: (() -> Unit)? = null,
    // Injection seam so unit tests can target a tempdir instead of
    // the user's real `~/.bhodl/`. Before this parameter existed, the
    // `Wipe is two-tap…` Compose test in `SettingsScreenTest.kt`
    // clicked the WIPE button against the hard-coded
    // `WalletPaths.defaultDataDir()` and silently wiped the
    // real data directory on every test run (a signet wallet was
    // lost this way on 2026-05-12). The
    // default value preserves production behaviour; tests MUST pass
    // a `@TempDir` to override.
    dataDir: java.io.File = WalletPaths.defaultDataDir(),
) {
    val themeMode by SettingsStore.themeMode
    val esploraUrls by SettingsStore.esploraUrls
    val localeOverride by SettingsStore.localeOverride
    val torEnabled by SettingsStore.torEnabled
    val torProxyUrl by SettingsStore.torProxyUrl
    val colorConvention by SettingsStore.colorConvention
    val fiatCurrency by SettingsStore.fiatCurrency
    val autoFetchInvestments by SettingsStore.autoFetchInvestmentPrices
    val privacyAutoHide by SettingsStore.privacyAutoHide
    val balancesHidden by SettingsStore.balancesHidden
    // UX review 2026-10 — Appearance: unified bitcoin unit, BIP177, density.
    val unitFormat by SettingsStore.unitFormat
    val bip177Notation by SettingsStore.bip177Notation
    val density by SettingsStore.density
    // Phase 8.3.1 — Bitcoin Lens advanced preferences. Read here so a
    // recomposition triggered by setShowFiatInX re-paints the section
    // without rebuilding the whole screen.
    val showFiatBankCsv by SettingsStore.showFiatInBankCsv
    val showFiatCurrentMonth by SettingsStore.showFiatInCurrentMonthFiatTx
    val showFiatTaxExports by SettingsStore.showFiatInTaxExports
    val showFiatInvoices by SettingsStore.showFiatInInvoiceReceipts
    // Phase 8.3.2 — net-worth fiat overlay toggle. Read here so a flip
    // from the new Settings row drives an immediate recomposition of
    // BitcoinLensSection.
    val showFiatOverlayOnNwChart by SettingsStore.showFiatOverlayOnNwChart

    // Settings redesign 2026-10 (proposal A) — two columns: the group
    // index on the left, every group as a card on the right. Clicking a
    // group scrolls to it; nothing is hidden, so every control stays
    // reachable (and searchable by the tests) from one page.
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val groupY = remember { mutableStateMapOf<SettingsGroup, Int>() }
    var current by remember { mutableStateOf(SettingsGroup.GENERAL) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BhodlTheme.colors.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = 1180.dp)
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Column(
                modifier = Modifier.width(220.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                BhodlTopBar(title = stringResource(Res.string.settings_title), onBack = onBack)
                Spacer(Modifier.height(12.dp))
                SettingsGroup.entries.forEach { g ->
                    if (g == SettingsGroup.SECURITY && onOpenSecurityHub == null) return@forEach
                    val on = g == current
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (on) BhodlTheme.colors.brandPrimary.copy(alpha = 0.12f) else Color.Transparent)
                            .clickable(role = androidx.compose.ui.semantics.Role.Tab) {
                                current = g
                                groupY[g]?.let { y -> scope.launch { scroll.animateScrollTo(y) } }
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                            .testTag(SettingsTags.group(g)),
                    ) {
                        Text(
                            stringResource(g.title),
                            style = BhodlTheme.typography.bodyMedium,
                            color = if (on) BhodlTheme.colors.brandPrimary else BhodlTheme.colors.textPrimary,
                        )
                        Text(stringResource(g.subtitle), style = BhodlTheme.typography.labelMedium, color = BhodlTheme.colors.textTertiary)
                    }
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .widthIn(max = 760.dp)
                    .verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                SettingsGroupCard(SettingsGroup.GENERAL, groupY) {
                    LanguageSection(
                        currentTag = localeOverride,
                        onSelected = { SettingsStore.setLocaleOverride(it) },
                    )
                    FiatCurrencySection(
                        selected = fiatCurrency,
                        onSelected = { SettingsStore.setFiatCurrency(it) },
                    )
                }
                SettingsGroupCard(SettingsGroup.APPEARANCE, groupY) {
                    ThemeSection(
                        selected = themeMode,
                        onSelected = { SettingsStore.setThemeMode(it) },
                    )
                    BitcoinUnitSection(
                        selected = unitFormat,
                        onSelected = { SettingsStore.setUnitFormat(it) },
                        bip177 = bip177Notation,
                        onBip177Change = { SettingsStore.setBip177Notation(it) },
                    )
                    DensitySection(
                        selected = density,
                        onSelected = { SettingsStore.setDensity(it) },
                    )
                    ColorConventionSection(
                        selected = colorConvention,
                        onSelected = { SettingsStore.setColorConvention(it) },
                    )
                }
                SettingsGroupCard(SettingsGroup.NETWORK, groupY) {
                    NetworkSection()
                    // Per-network override: the section edits ONLY the loaded
                    // wallet's network, captured here so Save / Reset can never
                    // land on another network's entry.
                    val esploraNetwork = WalletStore.network()
                    EsploraEndpointSection(
                        network = esploraNetwork,
                        currentOverride = esploraUrls[esploraNetwork],
                        onSave = { SettingsStore.setEsploraUrl(esploraNetwork, it) },
                        onReset = { SettingsStore.setEsploraUrl(esploraNetwork, null) },
                    )
                    TorSection(
                        enabled = torEnabled,
                        proxyUrl = torProxyUrl,
                        onEnabledChange = { SettingsStore.setTorEnabled(it) },
                        onProxyUrlChange = { SettingsStore.setTorProxyUrl(it) },
                    )
                    InvestmentPriceFetchSection(
                        enabled = autoFetchInvestments,
                        onToggle = { SettingsStore.setAutoFetchInvestmentPrices(it) },
                    )
                    PrivacySection(
                        startHidden = privacyAutoHide,
                        onStartHiddenChange = { SettingsStore.setPrivacyAutoHide(it) },
                        hiddenNow = balancesHidden,
                        onHiddenNowChange = { SettingsStore.setBalancesHidden(it) },
                    )
                }
                SettingsGroupCard(SettingsGroup.LENS, groupY) {
                    BitcoinLensSection(
                        showFiatInBankCsv = showFiatBankCsv,
                        onShowFiatInBankCsvChange = { SettingsStore.setShowFiatInBankCsv(it) },
                        showFiatInCurrentMonthFiatTx = showFiatCurrentMonth,
                        onShowFiatInCurrentMonthFiatTxChange = {
                            SettingsStore.setShowFiatInCurrentMonthFiatTx(it)
                        },
                        showFiatInTaxExports = showFiatTaxExports,
                        onShowFiatInTaxExportsChange = {
                            SettingsStore.setShowFiatInTaxExports(it)
                        },
                        showFiatInInvoiceReceipts = showFiatInvoices,
                        onShowFiatInInvoiceReceiptsChange = {
                            SettingsStore.setShowFiatInInvoiceReceipts(it)
                        },
                        showFiatOverlayOnNwChart = showFiatOverlayOnNwChart,
                        onShowFiatOverlayOnNwChartChange = {
                            SettingsStore.setShowFiatOverlayOnNwChart(it)
                        },
                    )
                }
                SettingsGroupCard(SettingsGroup.TRACKER, groupY) {
                    TrackerModulesSection()
                }
                if (onOpenSecurityHub != null) {
                    SettingsGroupCard(SettingsGroup.SECURITY, groupY) {
                        OutlinedButton(
                            onClick = onOpenSecurityHub,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(SecurityHubTags.SETTINGS_ENTRY),
                        ) { Text(stringResource(Res.string.security_hub_open)) }
                    }
                }
                SettingsGroupCard(SettingsGroup.DATA, groupY) {
                    // UX review 2026-10 (§4 item 5) — the wallet-scoped
                    // sections live in the wallet workspace's "Wallet
                    // settings" tab; this page keeps the app-wide
                    // preferences and the full data wipe.
                    WalletSettingsPointer(onOpenWalletSettings = onOpenWalletSettings)
                    WipeSection(onWiped = onWalletWiped, dataDir = dataDir)
                }
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SettingsTags.BACK),
                ) {
                    Text(stringResource(Res.string.action_back))
                }
            }
        }
    }
}

/** Settings redesign 2026-10 — the groups of the left index. */
public enum class SettingsGroup(
    internal val title: org.jetbrains.compose.resources.StringResource,
    internal val subtitle: org.jetbrains.compose.resources.StringResource,
) {
    GENERAL(Res.string.stg_general, Res.string.stg_general_sub),
    APPEARANCE(Res.string.settings_appearance_section, Res.string.stg_appearance_sub),
    NETWORK(Res.string.stg_network, Res.string.stg_network_sub),
    LENS(Res.string.stg_lens, Res.string.stg_lens_sub),
    TRACKER(Res.string.stg_tracker, Res.string.stg_tracker_sub),
    SECURITY(Res.string.stg_security, Res.string.stg_security_sub),
    DATA(Res.string.stg_data, Res.string.stg_data_sub),
}

@Composable
private fun SettingsGroupCard(
    group: SettingsGroup,
    groupY: MutableMap<SettingsGroup, Int>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            // Offset inside the scrolled column = the scroll target.
            .onGloballyPositioned { coords -> groupY[group] = coords.positionInParent().y.toInt() }
            .bhodlCard()
            .padding(20.dp)
            .testTag(SettingsTags.groupCard(group)),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(stringResource(group.title), style = BhodlTheme.typography.titleMedium, color = BhodlTheme.colors.textPrimary)
        Column(
            modifier = if (group == SettingsGroup.APPEARANCE) Modifier.testTag(SettingsTags.APPEARANCE_SECTION) else Modifier,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) { content() }
    }
}

/**
 * `true` when [url] is plain `http://` against a clearnet host.
 * `.onion` hosts (Tor encrypts end-to-end to the hidden service) and
 * loopback (never leaves the machine) are exempt.
 *
 * Audit 2026-10 (M7) — this used to return `false` whenever Tor was
 * on, but Tor does NOT encrypt the last hop to a clearnet host: the exit
 * relay (and anyone between it and the server) reads the plaintext
 * requests, i.e. the wallet's addresses. So the warning now applies
 * regardless of Tor; [torEnabled] is kept for call-site compatibility.
 */
internal fun isPlaintextHttpWithoutTor(url: String, @Suppress("UNUSED_PARAMETER") torEnabled: Boolean): Boolean =
    isPlaintextRemoteHttp(url)

/** See [isPlaintextHttpWithoutTor]. */
internal fun isPlaintextRemoteHttp(url: String): Boolean {
    if (!url.trim().startsWith("http://", ignoreCase = true)) return false
    val host = runCatching { java.net.URI(url.trim()).host }.getOrNull()
        ?.lowercase()?.trimStart('[')?.trimEnd(']')
        ?: return true
    val isLocal = host == "localhost" || host == "127.0.0.1" || host == "::1"
    return !host.endsWith(".onion") && !isLocal
}

public object SettingsTags {
    public const val BACK: String = "settings.back"
    /** Settings redesign 2026-10 — group index entries and group cards. */
    public fun group(g: SettingsGroup): String = "settings.group.${g.name.lowercase()}"
    public fun groupCard(g: SettingsGroup): String = "settings.group-card.${g.name.lowercase()}"
    public const val NETWORK_VALUE: String = "settings.network.value"
    public fun themeChip(mode: ThemeMode): String = "settings.theme.${mode.name.lowercase()}"
    /** Phase 3.10 — color convention chip test tag. */
    public fun colorConventionChip(convention: ColorConvention): String =
        "settings.color-convention.${convention.name.lowercase()}"
    /** Phase 5.0 — fiat currency chip test tag. */
    public fun fiatCurrencyChip(currency: FiatCurrency): String =
        "settings.fiat-currency.${currency.name.lowercase()}"

    /** UX review 2026-10 — Settings › Appearance group + controls. */
    public const val APPEARANCE_SECTION: String = "settings.appearance.section"
    public fun unitChip(unit: UnitFormat): String =
        "settings.unit.${unit.name.lowercase()}"
    public const val UNIT_EXAMPLE: String = "settings.unit.example"
    public const val BIP177_TOGGLE: String = "settings.unit.bip177"
    public fun densityChip(density: BhodlDensity): String =
        "settings.density.${density.name.lowercase()}"

    public const val ESPLORA_URL_FIELD: String = "settings.esplora.field"
    public const val ESPLORA_URL_SAVE: String = "settings.esplora.save"
    public const val ESPLORA_URL_RESET: String = "settings.esplora.reset"
    public const val ESPLORA_URL_ACTIVE: String = "settings.esplora.active"
    public const val ESPLORA_NETWORK: String = "settings.esplora.network"
    /** Security review — inline URL-validation error line. */
    public const val ESPLORA_URL_ERROR: String = "settings.esplora.error"
    /** Security review — persistent plaintext-HTTP-without-Tor warning. */
    public const val ESPLORA_PLAINTEXT_WARNING: String = "settings.esplora.plaintext-warning"

    public const val LANGUAGE_SYSTEM: String = "settings.language.system"
    public const val LANGUAGE_EN: String = "settings.language.en"
    public const val LANGUAGE_IT: String = "settings.language.it"

    public const val TOR_TOGGLE: String = "settings.tor.toggle"
    public const val TOR_PROXY_FIELD: String = "settings.tor.proxy-field"
    /** Security review — inline proxy-validation error line. */
    public const val TOR_PROXY_ERROR: String = "settings.tor.proxy-error"

    /** Phase 7.2 — "Start with balances hidden" switch (privacy section). */
    public const val PRIVACY_AUTO_HIDE: String = "settings.privacy.auto-hide"
    /** UX review 2026-10 (item 13) — "Hide amounts now" switch. */
    public const val PRIVACY_HIDDEN_NOW: String = "settings.privacy.hidden-now"

    /** Phase 8.3.1 — Bitcoin Lens advanced section + controls. */
    public const val BITCOIN_LENS_SECTION: String = "settings.bitcoin-lens.section"
    public const val BITCOIN_LENS_FIAT_BANK_CSV: String =
        "settings.bitcoin-lens.fiat.bank-csv"
    public const val BITCOIN_LENS_FIAT_CURRENT_MONTH: String =
        "settings.bitcoin-lens.fiat.current-month"
    public const val BITCOIN_LENS_FIAT_TAX: String =
        "settings.bitcoin-lens.fiat.tax"
    public const val BITCOIN_LENS_FIAT_INVOICES: String =
        "settings.bitcoin-lens.fiat.invoices"

    /** Phase 8.3.2 — fiat overlay toggle on the net-worth chart. */
    public const val BITCOIN_LENS_FIAT_NW_CHART: String =
        "settings.bitcoin-lens.fiat.nw-chart"

    public const val WIPE: String = "settings.wipe"

    /** Note + button pointing to Wallet › Wallet settings. */
    public const val WALLET_SETTINGS_POINTER: String = "settings.wallet-settings.pointer"
    public const val OPEN_WALLET_SETTINGS: String = "settings.wallet-settings.open"


    public const val MODULE_INVESTMENTS: String = "settings.module.investments"
    public const val MODULE_BUDGET_BILLS: String = "settings.module.budget-bills"
    public const val MODULE_SAVINGS_GOALS: String = "settings.module.savings-goals"
    public const val MODULE_MANUAL_ASSETS: String = "settings.module.manual-assets"
    public const val MODULE_ANALYTICS_LABS: String = "settings.module.analytics-labs"

    public const val LABELS_EXPORT: String = "settings.labels.export"
    public const val LABELS_IMPORT: String = "settings.labels.import"
    public const val LABELS_STATUS: String = "settings.labels.status"
    public const val LABELS_ERROR: String = "settings.labels.error"

    public const val WALLET_NAME_FIELD: String = "settings.wallet_name.field"
    public const val WALLET_NAME_SAVE: String = "settings.wallet_name.save"

    public const val DELETE_THIS_WALLET: String = "settings.delete_this_wallet"

    public const val WATCH_DESC_REVEAL: String = "settings.watch_descriptor.reveal"
    public const val WATCH_DESC_HIDE: String = "settings.watch_descriptor.hide"
    public const val WATCH_DESC_XPUB: String = "settings.watch_descriptor.xpub"
    public const val WATCH_DESC_EXTERNAL: String = "settings.watch_descriptor.external"
    public const val WATCH_DESC_COPY_XPUB: String = "settings.watch_descriptor.copy_xpub"
    public const val WATCH_DESC_COPY_DESC: String = "settings.watch_descriptor.copy_descriptor"
    public const val WATCH_DESC_ERROR: String = "settings.watch_descriptor.error"
}

/** Where the wallet-scoped settings went (UX review 2026-10, item 5). */
@Composable
private fun WalletSettingsPointer(onOpenWalletSettings: (() -> Unit)?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SettingsTags.WALLET_SETTINGS_POINTER),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.wallet_settings_moved),
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textSecondary,
        )
        if (onOpenWalletSettings != null && WalletStore.current() != null) {
            OutlinedButton(
                onClick = onOpenWalletSettings,
                modifier = Modifier.fillMaxWidth().testTag(SettingsTags.OPEN_WALLET_SETTINGS),
            ) {
                Text(stringResource(Res.string.wallet_settings_open))
            }
        }
    }
}
