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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.settings.EsploraEndpoint
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.ui.BhodlTopBar
import io.bhodl.desktop.settings.ThemeMode
import io.bhodl.desktop.wizard.WalletPaths
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.ffi.Network
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
import io.bhodl.shared.resources.action_save
import io.bhodl.shared.resources.settings_esplora_active_default
import io.bhodl.shared.resources.settings_esplora_active_override
import io.bhodl.shared.resources.settings_esplora_description
import io.bhodl.shared.resources.settings_esplora_field_label_network
import io.bhodl.shared.resources.settings_esplora_network
import io.bhodl.shared.resources.settings_esplora_reset
import io.bhodl.shared.resources.settings_esplora_section
import io.bhodl.shared.resources.settings_language_english
import io.bhodl.shared.resources.settings_language_italian
import io.bhodl.shared.resources.settings_language_section
import io.bhodl.shared.resources.settings_language_system
import io.bhodl.desktop.prices.FiatCurrency
import io.bhodl.desktop.prices.PriceCache
import io.bhodl.desktop.ui.money.UnitFormat
import io.bhodl.shared.BtcUnit
import io.bhodl.ui.theme.BhodlTheme
import io.bhodl.ui.theme.ColorConvention
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import io.bhodl.shared.resources.set_esplora_invalid_url
import io.bhodl.shared.resources.set_esplora_plaintext_no_tor
import io.bhodl.shared.resources.set_esplora_plaintext_tor
import io.bhodl.shared.resources.set_invfetch_hint
import io.bhodl.shared.resources.set_invfetch_title
import io.bhodl.shared.resources.set_module_analytics_labs
import io.bhodl.shared.resources.set_module_budget_bills
import io.bhodl.shared.resources.set_module_investments
import io.bhodl.shared.resources.set_module_manual_assets
import io.bhodl.shared.resources.set_module_savings_goals
import io.bhodl.shared.resources.set_modules_hint
import io.bhodl.shared.resources.set_modules_title
import io.bhodl.shared.resources.set_network_hint
import io.bhodl.shared.resources.set_network_title
import io.bhodl.shared.resources.set_online_prices_hint
import io.bhodl.shared.resources.set_online_prices_title
import io.bhodl.shared.resources.set_recompute_button
import io.bhodl.shared.resources.set_recompute_done
import io.bhodl.shared.resources.set_recompute_done_failed
import io.bhodl.shared.resources.set_recompute_failed
import io.bhodl.shared.resources.set_recompute_hint
import io.bhodl.shared.resources.set_recompute_registry_locked
import io.bhodl.shared.resources.set_recompute_running
import io.bhodl.shared.resources.set_toggle_off
import io.bhodl.shared.resources.set_toggle_on
import io.bhodl.shared.resources.set_tor_proxy_hint
import io.bhodl.shared.resources.set_tor_proxy_invalid
import io.bhodl.shared.resources.set_tor_proxy_label
import io.bhodl.shared.resources.set_tor_route
import io.bhodl.shared.resources.set_tor_route_hint

/**
 * Online prices on / off: off, no price request leaves the device
 * (see [io.bhodl.desktop.settings.SettingsStore.onlinePrices]).
 */
@Composable
internal fun OnlinePricesSection(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.set_online_prices_title),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textSecondary,
        )
        Text(
            text = stringResource(Res.string.set_online_prices_hint),
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textTertiary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (enabled) stringResource(Res.string.set_toggle_on) else stringResource(Res.string.set_toggle_off),
                style = BhodlTheme.typography.bodyMedium,
                color = if (enabled) BhodlTheme.colors.financialPositive
                else BhodlTheme.colors.textTertiary,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.testTag("settings.online-prices"),
            )
        }
    }
}

/**
 * Yahoo Finance quotes for investments with a ticker, every 5 minutes.
 */
@Composable
internal fun InvestmentPriceFetchSection(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.set_invfetch_title),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textSecondary,
        )
        Text(
            text = stringResource(Res.string.set_invfetch_hint),
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textTertiary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (enabled) stringResource(Res.string.set_toggle_on) else stringResource(Res.string.set_toggle_off),
                style = BhodlTheme.typography.bodyMedium,
                color = if (enabled) BhodlTheme.colors.financialPositive
                else BhodlTheme.colors.textTertiary,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.testTag("settings.auto-fetch-investments"),
            )
        }

        // Phase 9 (BTC P&L) — one-shot repair. Re-resolves every lot's
        // BTC cost basis from its purchase date (historical rate),
        // OVERWRITING values written before the historical-rate wiring
        // (e.g. positions priced at the rate-at-entry instead of the
        // rate-at-purchase). Best-effort: a lot whose rate can't be
        // fetched keeps its prior value.
        val scope = rememberCoroutineScope()
        var recomputing by remember { mutableStateOf(false) }
        var recomputeStatus by remember { mutableStateOf<String?>(null) }
        val registryLockedMsg = stringResource(Res.string.set_recompute_registry_locked)
        Text(
            text = stringResource(Res.string.set_recompute_hint),
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textTertiary,
        )
        TextButton(
            onClick = {
                val registry = io.bhodl.desktop.wizard.RegistryStore.current()
                if (registry == null) {
                    recomputeStatus = registryLockedMsg
                    return@TextButton
                }
                recomputing = true
                recomputeStatus = null
                scope.launch {
                    val stats = withContext(Dispatchers.IO) {
                        io.bhodl.desktop.prices.TrackerBtcRate.recomputeAllLots(registry)
                    }
                    recomputing = false
                    recomputeStatus = if (stats != null) {
                        if (stats.failed > 0u) {
                            getString(
                                Res.string.set_recompute_done_failed,
                                stats.updated.toString(),
                                stats.considered.toString(),
                                stats.failed.toString(),
                            )
                        } else {
                            getString(Res.string.set_recompute_done, stats.updated.toString(), stats.considered.toString())
                        }
                    } else {
                        getString(Res.string.set_recompute_failed)
                    }
                    io.bhodl.desktop.wizard.RegistryStore.bumpDataVersion()
                }
            },
            enabled = !recomputing,
            modifier = Modifier.testTag("settings.recompute-btc-basis"),
        ) { Text(if (recomputing) stringResource(Res.string.set_recompute_running) else stringResource(Res.string.set_recompute_button)) }
        recomputeStatus?.let {
            Text(
                text = it,
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.textTertiary,
            )
        }
    }
}

/**
 * Esplora override editor for ONE network.
 *
 * Overrides are stored per network ([SettingsStore.esploraUrlFor]); the
 * section names [network] in its label and helper text, and the caller
 * binds [onSave] / [onReset] to that same [network] — editing here never
 * touches another network's endpoint.
 */
@Composable
internal fun EsploraEndpointSection(
    network: Network,
    currentOverride: String?,
    onSave: (String) -> Unit,
    onReset: () -> Unit,
) {
    // Editable draft state — what the user is typing right now. Seeded
    // from the persisted override (if any), or left blank so the
    // placeholder shows the default mempool.space URL.
    var draft by remember(currentOverride) { mutableStateOf(currentOverride.orEmpty()) }
    // Security review — inline validation error. Set on Save when the
    // draft is not an http(s) URL with a host; cleared on the next
    // successful Save / Reset.
    var validationError by remember { mutableStateOf<String?>(null) }
    val invalidEsploraMsg = stringResource(Res.string.set_esplora_invalid_url)
    val torEnabled by SettingsStore.torEnabled
    // Network-aware default: a mainnet wallet without an override now
    // resolves to the mainnet endpoint (and so on per network).
    val networkDefault = EsploraEndpoint.defaultFor(network)
    val networkName = ExplorerLinks.networkLabel(network)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.settings_esplora_section),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textSecondary,
        )
        Text(
            text = stringResource(Res.string.settings_esplora_description),
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textTertiary,
        )
        Text(
            text = stringResource(Res.string.settings_esplora_network, networkName),
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textSecondary,
            modifier = Modifier.testTag(SettingsTags.ESPLORA_NETWORK),
        )
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = {
                Text(stringResource(Res.string.settings_esplora_field_label_network, networkName))
            },
            placeholder = { Text(networkDefault) },
            singleLine = true,
            isError = validationError != null,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SettingsTags.ESPLORA_URL_FIELD),
        )
        if (validationError != null) {
            Text(
                text = validationError!!,
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.error,
                modifier = Modifier.testTag(SettingsTags.ESPLORA_URL_ERROR),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    // Blank draft = "go back to the default" (the store
                    // maps blank to null); anything else must pass the
                    // http(s)-with-host validation before it is saved.
                    val candidate = draft.trim()
                    if (candidate.isEmpty() || SettingsStore.isValidEsploraUrl(candidate)) {
                        validationError = null
                        onSave(draft)
                    } else {
                        validationError = invalidEsploraMsg
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag(SettingsTags.ESPLORA_URL_SAVE),
            ) { Text(stringResource(Res.string.action_save)) }
            OutlinedButton(
                onClick = {
                    draft = ""
                    validationError = null
                    onReset()
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag(SettingsTags.ESPLORA_URL_RESET),
            ) { Text(stringResource(Res.string.settings_esplora_reset)) }
        }
        // Tiny status line so the user knows whether the active value
        // is the override or the built-in default.
        val activeText = currentOverride?.let {
            stringResource(Res.string.settings_esplora_active_override, it)
        } ?: stringResource(Res.string.settings_esplora_active_default, networkDefault)
        Text(
            text = activeText,
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textTertiary,
            modifier = Modifier.testTag(SettingsTags.ESPLORA_URL_ACTIVE),
        )
        // Security review — persistent plaintext warning. Shown while
        // the effective endpoint is plain `http://` against a clearnet
        // host (not .onion, not localhost): every sync leaks the wallet's
        // addresses in cleartext — to the network without Tor, to the
        // Tor exit relay with it (audit 2026-10, M7).
        val effectiveUrl = currentOverride ?: networkDefault
        if (isPlaintextHttpWithoutTor(effectiveUrl, torEnabled)) {
            Text(
                text = if (torEnabled) {
                    stringResource(Res.string.set_esplora_plaintext_tor)
                } else {
                    stringResource(Res.string.set_esplora_plaintext_no_tor)
                },
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.error,
                modifier = Modifier.testTag(SettingsTags.ESPLORA_PLAINTEXT_WARNING),
            )
        }
    }
}

@Composable
internal fun TorSection(
    enabled: Boolean,
    proxyUrl: String,
    onEnabledChange: (Boolean) -> Unit,
    onProxyUrlChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Tor",
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textSecondary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.set_tor_route),
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textPrimary,
                )
                Text(
                    text = stringResource(Res.string.set_tor_route_hint),
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textTertiary,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
                modifier = Modifier.testTag(SettingsTags.TOR_TOGGLE),
            )
        }
        if (enabled) {
            // Security review — the field edits a local draft and only
            // writes through to the store when the value matches the
            // socks5(h)://host:port shape (port 1–65535). While the
            // draft is invalid the previous valid proxy stays active
            // and an inline error explains what is expected.
            var proxyDraft by remember(proxyUrl) { mutableStateOf(proxyUrl) }
            var proxyError by remember { mutableStateOf<String?>(null) }
            val invalidProxyMsg = stringResource(Res.string.set_tor_proxy_invalid)
            OutlinedTextField(
                value = proxyDraft,
                onValueChange = { input ->
                    proxyDraft = input
                    if (SettingsStore.isValidTorProxyUrl(input) || input.isBlank()) {
                        proxyError = null
                        onProxyUrlChange(input)
                    } else {
                        proxyError = invalidProxyMsg
                    }
                },
                label = { Text(stringResource(Res.string.set_tor_proxy_label)) },
                // Audit 2026-10 — socks5:// is accepted but stored as
                // socks5h:// so DNS is resolved inside Tor.
                supportingText = {
                    Text(
                        stringResource(Res.string.set_tor_proxy_hint),
                    )
                },
                placeholder = { Text(io.bhodl.desktop.settings.SettingsStore.DEFAULT_TOR_PROXY_URL) },
                singleLine = true,
                isError = proxyError != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SettingsTags.TOR_PROXY_FIELD),
            )
            if (proxyError != null) {
                Text(
                    text = proxyError!!,
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.error,
                    modifier = Modifier.testTag(SettingsTags.TOR_PROXY_ERROR),
                )
            }
        }
    }
}

@Composable
internal fun LanguageSection(
    currentTag: String?,
    onSelected: (String?) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.settings_language_section),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textSecondary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LanguageChip(
                label = stringResource(Res.string.settings_language_system),
                selected = currentTag == null,
                onClick = { onSelected(null) },
                tag = SettingsTags.LANGUAGE_SYSTEM,
                modifier = Modifier.weight(1f),
            )
            LanguageChip(
                label = stringResource(Res.string.settings_language_english),
                selected = currentTag == "en",
                onClick = { onSelected("en") },
                tag = SettingsTags.LANGUAGE_EN,
                modifier = Modifier.weight(1f),
            )
            LanguageChip(
                label = stringResource(Res.string.settings_language_italian),
                selected = currentTag == "it",
                onClick = { onSelected("it") },
                tag = SettingsTags.LANGUAGE_IT,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun LanguageChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    tag: String,
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
            .testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textPrimary,
        )
    }
}

/**
 * Read-only "Current network" indicator. The network is fixed at
 * wallet creation (BIP-86 derivation paths bake the `coin_type` into
 * every persisted descriptor) so this surfaces the
 * [`WalletStore`]-resolved value plus a hint on how to switch
 * (wipe + create / restore on a different network).
 */
@Composable
internal fun NetworkSection() {
    val displayName = when (WalletStore.network()) {
        Network.MAINNET -> "Mainnet"
        Network.TESTNET4 -> "Testnet4"
        Network.SIGNET -> "Signet"
        Network.REGTEST -> "Regtest"
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(Res.string.set_network_title),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textSecondary,
        )
        Text(
            text = displayName,
            style = BhodlTheme.typography.titleMedium,
            color = BhodlTheme.colors.textPrimary,
            modifier = Modifier.testTag(SettingsTags.NETWORK_VALUE),
        )
        Text(
            text = stringResource(Res.string.set_network_hint),
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textTertiary,
        )
    }
}

/** Test tags exposed by [`SettingsScreen`]. */
@Composable
internal fun TrackerModulesSection() {
    val investments by SettingsStore.moduleInvestments
    val budgetBills by SettingsStore.moduleBudgetBills
    val savingsGoals by SettingsStore.moduleSavingsGoals
    val manualAssets by SettingsStore.moduleManualAssets
    val analyticsLabs by SettingsStore.moduleAnalyticsLabs

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.set_modules_title),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textSecondary,
        )
        Text(
            text = stringResource(Res.string.set_modules_hint),
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textTertiary,
        )
        ModuleToggle(stringResource(Res.string.set_module_investments), investments, SettingsTags.MODULE_INVESTMENTS) {
            SettingsStore.setModuleInvestments(it)
        }
        ModuleToggle(stringResource(Res.string.set_module_budget_bills), budgetBills, SettingsTags.MODULE_BUDGET_BILLS) {
            SettingsStore.setModuleBudgetBills(it)
        }
        ModuleToggle(stringResource(Res.string.set_module_savings_goals), savingsGoals, SettingsTags.MODULE_SAVINGS_GOALS) {
            SettingsStore.setModuleSavingsGoals(it)
        }
        ModuleToggle(stringResource(Res.string.set_module_manual_assets), manualAssets, SettingsTags.MODULE_MANUAL_ASSETS) {
            SettingsStore.setModuleManualAssets(it)
        }
        ModuleToggle(stringResource(Res.string.set_module_analytics_labs), analyticsLabs, SettingsTags.MODULE_ANALYTICS_LABS) {
            SettingsStore.setModuleAnalyticsLabs(it)
        }
    }
}

@Composable
private fun ModuleToggle(
    label: String,
    checked: Boolean,
    tag: String,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = BhodlTheme.typography.bodyMedium,
            color = if (checked) BhodlTheme.colors.textPrimary else BhodlTheme.colors.textTertiary,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
            modifier = Modifier.testTag(tag),
        )
    }
}
