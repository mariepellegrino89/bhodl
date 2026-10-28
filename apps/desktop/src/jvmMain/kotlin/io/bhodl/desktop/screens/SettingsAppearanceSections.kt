package io.bhodl.desktop.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import io.bhodl.shared.resources.privacy_hidden_now
import io.bhodl.shared.resources.privacy_section_body
import io.bhodl.shared.resources.privacy_section_title
import io.bhodl.shared.resources.privacy_start_hidden
import io.bhodl.shared.resources.settings_theme_dark
import io.bhodl.shared.resources.settings_theme_light
import io.bhodl.shared.resources.settings_theme_neon
import io.bhodl.shared.resources.settings_theme_section
import io.bhodl.shared.resources.settings_theme_system
import io.bhodl.desktop.prices.FiatCurrency
import io.bhodl.desktop.prices.PriceCache
import io.bhodl.desktop.ui.money.BitcoinAmountFormat
import io.bhodl.desktop.ui.money.UnitFormat
import io.bhodl.shared.resources.settings_bip177_description
import io.bhodl.shared.resources.settings_bip177_label
import io.bhodl.shared.resources.settings_density_compact
import io.bhodl.shared.resources.settings_density_compact_hint
import io.bhodl.shared.resources.settings_density_section
import io.bhodl.shared.resources.settings_density_ultra
import io.bhodl.shared.resources.settings_density_ultra_hint
import io.bhodl.shared.resources.settings_theme_system_hint
import io.bhodl.shared.resources.settings_unit_auto
import io.bhodl.shared.resources.settings_unit_auto_hint
import io.bhodl.shared.resources.settings_unit_btc
import io.bhodl.shared.resources.settings_unit_btc_hint
import io.bhodl.shared.resources.settings_unit_description
import io.bhodl.shared.resources.settings_unit_sats
import io.bhodl.shared.resources.settings_unit_sats_hint
import io.bhodl.shared.resources.settings_unit_section
import io.bhodl.ui.theme.BhodlDensity
import io.bhodl.ui.theme.BhodlTheme
import io.bhodl.ui.theme.ColorConvention
import org.jetbrains.compose.resources.stringResource
import io.bhodl.shared.resources.set_color_convention_hint
import io.bhodl.shared.resources.set_color_convention_title
import io.bhodl.shared.resources.set_color_eastern
import io.bhodl.shared.resources.set_color_western
import io.bhodl.shared.resources.set_fiat_error
import io.bhodl.shared.resources.set_fiat_fetching
import io.bhodl.shared.resources.set_fiat_hint
import io.bhodl.shared.resources.set_fiat_last
import io.bhodl.shared.resources.set_fiat_no_price
import io.bhodl.shared.resources.set_fiat_no_provider
import io.bhodl.shared.resources.set_fiat_off
import io.bhodl.shared.resources.set_fiat_title
import io.bhodl.shared.resources.set_lens_bank_csv
import io.bhodl.shared.resources.set_lens_current_month
import io.bhodl.shared.resources.set_lens_footer
import io.bhodl.shared.resources.set_lens_invoices
import io.bhodl.shared.resources.set_lens_nw_chart
import io.bhodl.shared.resources.set_lens_surfaces
import io.bhodl.shared.resources.set_lens_tax
import io.bhodl.shared.resources.set_lens_title

@Composable
internal fun ThemeSection(
    selected: ThemeMode,
    onSelected: (ThemeMode) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.settings_theme_section),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textSecondary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // UX review 2026-10 — System (the default) first.
            THEME_CHIP_ORDER.forEach { mode ->
                ThemeChip(
                    mode = mode,
                    selected = selected == mode,
                    onClick = { onSelected(mode) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Text(
            text = stringResource(Res.string.settings_theme_system_hint),
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textTertiary,
        )
    }
}

private val THEME_CHIP_ORDER: List<ThemeMode> =
    listOf(ThemeMode.System, ThemeMode.Light, ThemeMode.Dark, ThemeMode.Neon)

@Composable
private fun ThemeChip(
    mode: ThemeMode,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor =
        if (selected) BhodlTheme.colors.brandPrimary else BhodlTheme.colors.surface
    val containerColor = if (selected) {
        BhodlTheme.colors.brandPrimary.copy(alpha = 0.12f)
    } else {
        BhodlTheme.colors.surface
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(containerColor)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp)
            .testTag(SettingsTags.themeChip(mode)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(mode.displayNameRes()),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textPrimary,
        )
    }
}

private fun ThemeMode.displayNameRes(): org.jetbrains.compose.resources.StringResource = when (this) {
    ThemeMode.Dark -> Res.string.settings_theme_dark
    ThemeMode.Light -> Res.string.settings_theme_light
    ThemeMode.System -> Res.string.settings_theme_system
    ThemeMode.Neon -> Res.string.settings_theme_neon
}

/**
 * Phase 3.10 — pick a cultural color convention for financial data:
 *  - Western (default): green = positive (income), red = negative (expense)
 *  - Eastern: red = positive (income), green = negative (expense) — the
 *    convention used in CN/JP/KR equity markets and apps like Tiger Brokers.
 *
 * Only the semantic financial layer flips; brand orange and error red
 * stay fixed so error states remain unambiguous in both modes.
 */
@Composable
internal fun ColorConventionSection(
    selected: ColorConvention,
    onSelected: (ColorConvention) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.set_color_convention_title),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textSecondary,
        )
        Text(
            text = stringResource(Res.string.set_color_convention_hint),
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textTertiary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ColorConvention.entries.forEach { c ->
                ColorConventionChip(
                    convention = c,
                    selected = c == selected,
                    onClick = { onSelected(c) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ColorConventionChip(
    convention: ColorConvention,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor =
        if (selected) BhodlTheme.colors.brandPrimary else BhodlTheme.colors.surface
    val containerColor = if (selected) {
        BhodlTheme.colors.brandPrimary.copy(alpha = 0.12f)
    } else {
        BhodlTheme.colors.surface
    }
    // Preview swatches — flip per convention so the chip itself shows
    // which sign is which color. Reads PRIMITIVE green/red (not the
    // semantic positive/negative) so the preview is independent of the
    // currently-applied convention.
    val positiveColor = when (convention) {
        ColorConvention.WESTERN -> BhodlTheme.colors.financialGreen
        ColorConvention.EASTERN -> BhodlTheme.colors.financialRed
    }
    val negativeColor = when (convention) {
        ColorConvention.WESTERN -> BhodlTheme.colors.financialRed
        ColorConvention.EASTERN -> BhodlTheme.colors.financialGreen
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(containerColor)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp)
            .testTag(SettingsTags.colorConventionChip(convention)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = when (convention) {
                ColorConvention.WESTERN -> stringResource(Res.string.set_color_western)
                ColorConvention.EASTERN -> stringResource(Res.string.set_color_eastern)
            },
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textPrimary,
        )
        // Phase 7.0 — preview with real money examples instead of bare
        // signs, so the convention reads unambiguously.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "+€1,234",
                color = positiveColor,
                style = BhodlTheme.typography.amountSmall,
            )
            Text(
                text = "−€567",
                color = negativeColor,
                style = BhodlTheme.typography.amountSmall,
            )
        }
    }
}

/**
 * UX review 2026-10 — Settings › Appearance › Bitcoin unit.
 *
 * ONE unit preference for the whole app (it replaces both the Phase 3.11
 * sat/BTC toggle and the Bitcoin-Lens "unit format" chips):
 *  - Sats (default): `1,234,567 sat` regardless of magnitude.
 *  - BTC: always 8 decimals (`0.01234567 BTC`) so columns align.
 *  - Auto: sats below 1 BTC, BTC at or above.
 * Plus an independent opt-in BIP177 toggle: sats shown as `₿1,234,567`.
 *
 * The examples under the chips are produced by the real formatter, so
 * they always show the user's locale separators.
 */
@Composable
internal fun BitcoinUnitSection(
    selected: UnitFormat,
    onSelected: (UnitFormat) -> Unit,
    bip177: Boolean,
    onBip177Change: (Boolean) -> Unit,
) {
    val sampleSat = 4_230_118L
    val sampleBigSat = 246_188_819L
    // Fixed illustrative values: never masked by the privacy mode.
    fun sample(sat: Long, unit: UnitFormat) = BitcoinAmountFormat.format(sat, unit, bip177 = bip177, masked = false)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.settings_unit_section),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textSecondary,
        )
        Text(
            text = stringResource(Res.string.settings_unit_description),
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textTertiary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            UNIT_CHIP_ORDER.forEach { unit ->
                AppearanceChip(
                    label = stringResource(
                        when (unit) {
                            UnitFormat.ALWAYS_SAT -> Res.string.settings_unit_sats
                            UnitFormat.ALWAYS_BTC -> Res.string.settings_unit_btc
                            UnitFormat.AUTO_THRESHOLD -> Res.string.settings_unit_auto
                        },
                    ),
                    selected = unit == selected,
                    onClick = { onSelected(unit) },
                    testTag = SettingsTags.unitChip(unit),
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Text(
            text = when (selected) {
                UnitFormat.ALWAYS_SAT -> stringResource(
                    Res.string.settings_unit_sats_hint,
                    sample(sampleSat, selected),
                )
                UnitFormat.ALWAYS_BTC -> stringResource(
                    Res.string.settings_unit_btc_hint,
                    sample(sampleSat, selected),
                )
                UnitFormat.AUTO_THRESHOLD -> stringResource(
                    Res.string.settings_unit_auto_hint,
                    sample(sampleSat, selected),
                    sample(sampleBigSat, selected),
                )
            },
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textTertiary,
            modifier = Modifier.testTag(SettingsTags.UNIT_EXAMPLE),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.settings_bip177_label),
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textPrimary,
                )
                Text(
                    text = stringResource(
                        Res.string.settings_bip177_description,
                        BitcoinAmountFormat.format(sampleSat, UnitFormat.ALWAYS_SAT, bip177 = true, masked = false),
                    ),
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textTertiary,
                )
            }
            Switch(
                checked = bip177,
                onCheckedChange = onBip177Change,
                modifier = Modifier.testTag(SettingsTags.BIP177_TOGGLE),
            )
        }
    }
}

private val UNIT_CHIP_ORDER: List<UnitFormat> =
    listOf(UnitFormat.ALWAYS_SAT, UnitFormat.ALWAYS_BTC, UnitFormat.AUTO_THRESHOLD)

/**
 * UX review 2026-10 — Settings › Appearance › Density. Compact (32dp
 * rows, default) or Ultra-dense (24dp rows), applied to dense tables
 * through [BhodlTheme.density].
 */
@Composable
internal fun DensitySection(
    selected: BhodlDensity,
    onSelected: (BhodlDensity) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.settings_density_section),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textSecondary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BhodlDensity.entries.forEach { d ->
                AppearanceChip(
                    label = stringResource(
                        when (d) {
                            BhodlDensity.Compact -> Res.string.settings_density_compact
                            BhodlDensity.UltraDense -> Res.string.settings_density_ultra
                        },
                    ),
                    selected = d == selected,
                    onClick = { onSelected(d) },
                    testTag = SettingsTags.densityChip(d),
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Text(
            text = stringResource(
                when (selected) {
                    BhodlDensity.Compact -> Res.string.settings_density_compact_hint
                    BhodlDensity.UltraDense -> Res.string.settings_density_ultra_hint
                },
            ),
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textTertiary,
        )
    }
}

/** Selectable chip shared by the Appearance pickers (same look as ThemeChip). */
@Composable
private fun AppearanceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    val borderColor =
        if (selected) BhodlTheme.colors.brandPrimary else BhodlTheme.colors.surface
    val containerColor = if (selected) {
        BhodlTheme.colors.brandPrimary.copy(alpha = 0.12f)
    } else {
        BhodlTheme.colors.surface
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(containerColor)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp)
            .testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textPrimary,
        )
    }
}

/**
 * Phase 5.0 — fiat overlay picker. Choosing a currency boots the
 * background BTC/fiat price cache and enables the "(~€X)" suffix on
 * every on-chain amount throughout the app via
 * [io.bhodl.desktop.ui.displayAmountWithFiat]. NONE disables the
 * overlay (no HTTP, pure sat/BTC display).
 *
 * The current cached price + last fetch time render below the chip
 * row so the user can see whether the overlay is live and how stale.
 */
@Composable
internal fun FiatCurrencySection(
    selected: FiatCurrency,
    onSelected: (FiatCurrency) -> Unit,
) {
    val price by PriceCache.price
    val lastFetch by PriceCache.lastFetchUnix
    val refreshing by PriceCache.refreshing
    val lastError by PriceCache.lastError
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.set_fiat_title),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textSecondary,
        )
        Text(
            text = stringResource(Res.string.set_fiat_hint),
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textTertiary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FiatCurrency.entries.forEach { c ->
                FiatCurrencyChip(
                    currency = c,
                    selected = c == selected,
                    onClick = { onSelected(c) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (selected != FiatCurrency.NONE) {
            val priceText = price?.let { "1 BTC = ${io.bhodl.desktop.ui.formatFiatWithCode(it, selected.code)}" }
                ?: if (refreshing) stringResource(Res.string.set_fiat_fetching) else stringResource(Res.string.set_fiat_no_price)
            val fetchText = lastFetch?.let { unix ->
                val fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
                    .withZone(java.time.ZoneId.systemDefault())
                stringResource(Res.string.set_fiat_last, fmt.format(java.time.Instant.ofEpochSecond(unix)))
            } ?: ""
            Text(
                text = listOf(priceText, fetchText).filter { it.isNotBlank() }.joinToString(" · "),
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.textTertiary,
            )
            if (lastError != null) {
                Text(
                    text = stringResource(
                        Res.string.set_fiat_error,
                        if (lastError == PriceCache.NO_PRICE_ERROR) stringResource(Res.string.set_fiat_no_provider) else lastError ?: "-",
                    ),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.error,
                )
            }
        }
    }
}

@Composable
private fun FiatCurrencyChip(
    currency: FiatCurrency,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor =
        if (selected) BhodlTheme.colors.brandPrimary else BhodlTheme.colors.surface
    val containerColor = if (selected) {
        BhodlTheme.colors.brandPrimary.copy(alpha = 0.12f)
    } else {
        BhodlTheme.colors.surface
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(containerColor)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp)
            .testTag(SettingsTags.fiatCurrencyChip(currency)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = when (currency) {
                FiatCurrency.NONE -> stringResource(Res.string.set_fiat_off)
                else -> "${currency.symbol} ${currency.code}"
            },
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textPrimary,
        )
    }
}

/**
 * Phase 5.2 — auto-fetch stock / ETF / forex / commodity quotes from
 * Yahoo Finance for every investment that has a ticker. When off, the
 * user maintains current values manually.
 *
 * Privacy: respects [SettingsStore.torEnabled] — when Tor is on, the
 * Yahoo HTTP call is routed through the SOCKS5 proxy. Unofficial API,
 * may break with site changes; defer to manual entry as fallback.
 */
/**
 * Phase 7.2 → UX review 2026-10 (item 13) — the app-wide privacy mode.
 *
 * Two switches: "Hide amounts now" drives the session flag
 * ([SettingsStore.balancesHidden], also Ctrl+Shift+H and the footer eye
 * icon) and "Start with balances hidden" is the persisted preference
 * that arms it at launch and after every lock.
 */
@Composable
internal fun PrivacySection(
    startHidden: Boolean,
    onStartHiddenChange: (Boolean) -> Unit,
    hiddenNow: Boolean,
    onHiddenNowChange: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.privacy_section_title),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textSecondary,
        )
        Text(
            text = stringResource(Res.string.privacy_section_body),
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textTertiary,
        )
        PrivacySwitchRow(
            label = stringResource(Res.string.privacy_hidden_now),
            checked = hiddenNow,
            onCheckedChange = onHiddenNowChange,
            tag = SettingsTags.PRIVACY_HIDDEN_NOW,
        )
        PrivacySwitchRow(
            label = stringResource(Res.string.privacy_start_hidden),
            checked = startHidden,
            onCheckedChange = onStartHiddenChange,
            tag = SettingsTags.PRIVACY_AUTO_HIDE,
        )
    }
}

@Composable
private fun PrivacySwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            // The visible label is the switch's accessible name.
            modifier = Modifier
                .testTag(tag)
                .semantics { contentDescription = label },
        )
    }
}

/**
 * Phase 8.3.1 — Bitcoin Lens (advanced).
 *
 * The "Bitcoin Lens" is the BHODL philosophy that BTC is the unit of
 * account everywhere, with a fiat secondary line shown ONLY on the
 * four operational surfaces where fiat is unavoidable (bank-CSV
 * import rows, current-month fiat-counterparty transactions, tax
 * exports, invoice / receipt attachments).
 *
 * This section exposes:
 *  - Four boolean toggles, one per operational surface, deciding
 *    whether the fiat secondary line is shown there. Defaults to ON
 *    for all four — the user opts OUT to drop fiat further into the
 *    background.
 *
 * The section is rendered open (no collapsible affordance) because
 * each control is a single line; collapsing would only save vertical
 * space on the rarer screens where the user comes to fiddle here.
 */
@Composable
internal fun BitcoinLensSection(
    showFiatInBankCsv: Boolean,
    onShowFiatInBankCsvChange: (Boolean) -> Unit,
    showFiatInCurrentMonthFiatTx: Boolean,
    onShowFiatInCurrentMonthFiatTxChange: (Boolean) -> Unit,
    showFiatInTaxExports: Boolean,
    onShowFiatInTaxExportsChange: (Boolean) -> Unit,
    showFiatInInvoiceReceipts: Boolean,
    onShowFiatInInvoiceReceiptsChange: (Boolean) -> Unit,
    showFiatOverlayOnNwChart: Boolean,
    onShowFiatOverlayOnNwChartChange: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SettingsTags.BITCOIN_LENS_SECTION),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(Res.string.set_lens_title),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textSecondary,
        )

        // Fiat-visible operational surfaces.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(Res.string.set_lens_surfaces),
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textPrimary,
            )
            BitcoinLensFiatToggleRow(
                label = stringResource(Res.string.set_lens_bank_csv),
                checked = showFiatInBankCsv,
                onCheckedChange = onShowFiatInBankCsvChange,
                testTag = SettingsTags.BITCOIN_LENS_FIAT_BANK_CSV,
            )
            BitcoinLensFiatToggleRow(
                label = stringResource(Res.string.set_lens_current_month),
                checked = showFiatInCurrentMonthFiatTx,
                onCheckedChange = onShowFiatInCurrentMonthFiatTxChange,
                testTag = SettingsTags.BITCOIN_LENS_FIAT_CURRENT_MONTH,
            )
            BitcoinLensFiatToggleRow(
                label = stringResource(Res.string.set_lens_tax),
                checked = showFiatInTaxExports,
                onCheckedChange = onShowFiatInTaxExportsChange,
                testTag = SettingsTags.BITCOIN_LENS_FIAT_TAX,
            )
            BitcoinLensFiatToggleRow(
                label = stringResource(Res.string.set_lens_invoices),
                checked = showFiatInInvoiceReceipts,
                onCheckedChange = onShowFiatInInvoiceReceiptsChange,
                testTag = SettingsTags.BITCOIN_LENS_FIAT_INVOICES,
            )
            // Phase 8.3.2 — net-worth chart fiat overlay. Visually
            // grouped with the four operational-surface toggles since
            // it shares the "where do we still surface fiat?" theme,
            // even though the chart is a viewing surface, not an
            // operational one.
            BitcoinLensFiatToggleRow(
                label = stringResource(Res.string.set_lens_nw_chart),
                checked = showFiatOverlayOnNwChart,
                onCheckedChange = onShowFiatOverlayOnNwChartChange,
                testTag = SettingsTags.BITCOIN_LENS_FIAT_NW_CHART,
            )
        }

        Text(
            text = stringResource(Res.string.set_lens_footer),
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textTertiary,
        )
    }
}

@Composable
private fun BitcoinLensFiatToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag),
        )
    }
}
