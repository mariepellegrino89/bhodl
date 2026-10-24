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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.settings.EsploraEndpoint
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.ui.BhodlTopBar
import io.bhodl.desktop.wizard.RegistryStore
import io.bhodl.desktop.wizard.WalletPaths
import io.bhodl.desktop.wizard.localizedText
import io.bhodl.ffi.Network
import io.bhodl.desktop.ui.userMessage
import io.bhodl.ffi.WatchBipFfi
import io.bhodl.ui.theme.BhodlTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.common_cancel
import io.bhodl.shared.resources.onb_add_failed
import io.bhodl.shared.resources.onb_add_watch_body
import io.bhodl.shared.resources.onb_add_watch_label
import io.bhodl.shared.resources.onb_add_watch_no_tor_body
import io.bhodl.shared.resources.onb_add_watch_submit
import io.bhodl.shared.resources.onb_add_watch_title
import io.bhodl.shared.resources.onb_add_watch_xpub
import io.bhodl.shared.resources.onb_adding
import io.bhodl.shared.resources.onb_continue_without_tor
import io.bhodl.shared.resources.onb_network
import io.bhodl.shared.resources.onb_no_tor_title
import io.bhodl.shared.resources.onb_registry_not_open
import io.bhodl.shared.resources.onb_script_native_segwit
import io.bhodl.shared.resources.onb_script_nested_segwit
import io.bhodl.shared.resources.onb_script_type
import io.bhodl.shared.resources.onb_wallet_name_taken
import io.bhodl.shared.resources.onb_xpub_dup_watch
import org.jetbrains.compose.resources.stringResource

/**
 * Phase 3.2 sub-E partial — production "+ Add watch-only" flow.
 *
 * Distinguished from `DebugWatchScreen` by:
 * - **No device PIN gate**: this route is only reachable from
 *   `WelcomeScreen` after `RegistryStore` is already populated.
 * - **Single-purpose**: only the Add form. Listing / syncing / total
 *   balance live elsewhere (Welcome picker handles listing).
 * - **Auto-sync + mainnet-without-Tor warning** kept identical to
 *   the debug screen so behavior is consistent.
 *
 * On successful add (with optional sync) the screen pops back to
 * `WelcomeScreen` so the picker re-reads the registry and the new
 * watch row appears immediately.
 *
 * The form intentionally keeps the same shape validated during manual
 * testing on the debug screen — label, xpub, BIP chip (Taproot / NativeSegwit /
 * NestedSegwit), network chip. HD-format and addresses-format adds
 * are deferred to a follow-up; the BIP-86/84/49 xpub path is the
 * common case for Sparrow / Specter / Coldcard / Trezor exports.
 */
@Composable
public fun AddWatchScreen(
    onAdded: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val registry = RegistryStore.current()

    var label by remember { mutableStateOf("") }
    var xpub by remember { mutableStateOf("") }
    var bip by remember { mutableStateOf(WatchBipFfi.TAPROOT) }
    var network by remember { mutableStateOf(Network.SIGNET) }
    var working by remember { mutableStateOf(false) }
    var lastError: String? by remember { mutableStateOf(null) }
    var pendingMainnetConfirm by remember { mutableStateOf(false) }

    /** Run the registry add + auto-sync pipeline. Called once any
     *  mainnet/Tor warning has been bypassed. */
    fun doAdd() {
        if (registry == null) {
            lastError = localizedText(Res.string.onb_registry_not_open)
            return
        }
        working = true
        lastError = null
        scope.launch {
            // Pre-flight 1: reject duplicate labels case-insensitively.
            val duplicateLabel = runCatching {
                withContext(Dispatchers.IO) {
                    io.bhodl.desktop.wizard.WalletLabel
                        .isDuplicate(registry, label)
                }
            }.getOrDefault(false)
            if (duplicateLabel) {
                working = false
                lastError = localizedText(Res.string.onb_wallet_name_taken)
                return@launch
            }
            // Pre-flight 2: reject if this xpub is already in the
            // registry — signing or watch. Adding the same xpub as a
            // watch row would duplicate every UTXO under a different
            // label; the dedup at balance time would still produce
            // correct totals but the picker would be confusing.
            // Compare in the core's canonical form: a vpub/zpub of an
            // account already saved (stored as tpub/xpub) is the same key.
            val xpubTrimmed = io.bhodl.desktop.wallet.AccountKey.normalize(xpub)
            val duplicateXpub = runCatching {
                withContext(Dispatchers.IO) {
                    val match = registry.listWallets().firstOrNull { row ->
                        if (row.kind == io.bhodl.ffi.WalletKindFfi.WATCH_ADDRESSES) {
                            // address-list rows are not xpub-based.
                            false
                        } else {
                            io.bhodl.ffi.extractAccountXpub(row.descriptorPub) ==
                                xpubTrimmed
                        }
                    }
                    match
                }
            }.getOrNull()
            if (duplicateXpub != null) {
                working = false
                lastError = localizedText(Res.string.onb_xpub_dup_watch, duplicateXpub.label)
                return@launch
            }
            // Phase 1: add the registry row + create the watch-state
            // file under `watch-state/<uuid>.sqlite`.
            val addedId: String? = runCatching {
                withContext(Dispatchers.IO) {
                    registry.addWatchXpub(
                        label = label.trim(),
                        xpub = xpub.trim(),
                        network = network,
                        bip = bip,
                    ).id
                }
            }.fold(
                onSuccess = { it },
                onFailure = { e ->
                    working = false
                    lastError = e.userMessage(localizedText(Res.string.onb_add_failed))
                    null
                },
            )
            if (addedId == null) return@launch
            // Phase 7.1.1 — signal the dataVersion bus so the Tracker
            // landing page (and the Tracking dashboard, when open)
            // pick up the new watch row without the user having to
            // hit Refresh.
            io.bhodl.desktop.wizard.RegistryStore.bumpDataVersion()

            // Phase 2: best-effort auto-sync. Failure surfaces but
            // does not roll back the add — the user can hit Sync from
            // the picker / debug screen later if needed.
            runCatching {
                withContext(Dispatchers.IO) {
                    val handle = registry.openWatch(walletId = addedId)
                    // The watch wallet's OWN network, not the signing one's.
                    handle.syncWithEsplora(
                        EsploraEndpoint.current(network),
                        EsploraEndpoint.currentProxy(),
                    )
                    handle.destroy()
                }
            }
            working = false
            onAdded()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BhodlTheme.colors.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .widthIn(max = 560.dp)
                .padding(horizontal = 32.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BhodlTopBar(title = stringResource(Res.string.onb_add_watch_title), onBack = onBack)

            Text(
                text = stringResource(Res.string.onb_add_watch_body),
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )

            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text(stringResource(Res.string.onb_add_watch_label)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(AddWatchTags.LABEL),
            )
            OutlinedTextField(
                value = xpub,
                onValueChange = { xpub = it.trim() },
                label = { Text(stringResource(Res.string.onb_add_watch_xpub)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(AddWatchTags.XPUB),
            )

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(Res.string.onb_script_type),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textSecondary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BipChipSelectable(
                        text = "Taproot",
                        subtext = "BIP-86",
                        selected = bip == WatchBipFfi.TAPROOT,
                        onClick = { bip = WatchBipFfi.TAPROOT },
                        modifier = Modifier.weight(1f),
                    )
                    BipChipSelectable(
                        text = stringResource(Res.string.onb_script_native_segwit),
                        subtext = "BIP-84",
                        selected = bip == WatchBipFfi.NATIVE_SEGWIT,
                        onClick = { bip = WatchBipFfi.NATIVE_SEGWIT },
                        modifier = Modifier.weight(1f),
                    )
                    BipChipSelectable(
                        text = stringResource(Res.string.onb_script_nested_segwit),
                        subtext = "BIP-49",
                        selected = bip == WatchBipFfi.NESTED_SEGWIT,
                        onClick = { bip = WatchBipFfi.NESTED_SEGWIT },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(Res.string.onb_network),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textSecondary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NetworkChipSelectable(
                        text = "Signet",
                        selected = network == Network.SIGNET,
                        onClick = { network = Network.SIGNET },
                        modifier = Modifier.weight(1f),
                    )
                    NetworkChipSelectable(
                        text = "Mainnet",
                        selected = network == Network.MAINNET,
                        onClick = { network = Network.MAINNET },
                        modifier = Modifier.weight(1f),
                    )
                    NetworkChipSelectable(
                        text = "Testnet4",
                        selected = network == Network.TESTNET4,
                        onClick = { network = Network.TESTNET4 },
                        modifier = Modifier.weight(1f),
                    )
                    NetworkChipSelectable(
                        text = "Regtest",
                        selected = network == Network.REGTEST,
                        onClick = { network = Network.REGTEST },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (lastError != null) {
                Text(
                    text = lastError!!,
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.error,
                    textAlign = TextAlign.Center,
                )
            }

            Button(
                onClick = {
                    if (label.isBlank() || xpub.isBlank() || working) return@Button
                    val mainnetNoTor = network == Network.MAINNET &&
                        !SettingsStore.torEnabled.value
                    if (mainnetNoTor) {
                        pendingMainnetConfirm = true
                    } else {
                        doAdd()
                    }
                },
                enabled = label.isNotBlank() && xpub.isNotBlank() && !working,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(AddWatchTags.SUBMIT),
            ) {
                Text(
                    if (working) stringResource(Res.string.onb_adding)
                    else stringResource(Res.string.onb_add_watch_submit),
                )
            }

            // The Tor-warning dialog mirrors the debug-screen copy.
            // Same rationale: public Esplora servers log every queried
            // address; without Tor the link between user IP and xpub
            // is permanent in their records.
            if (pendingMainnetConfirm) {
                AlertDialog(
                    onDismissRequest = { pendingMainnetConfirm = false },
                    title = { Text(stringResource(Res.string.onb_no_tor_title)) },
                    text = {
                        Text(
                            stringResource(Res.string.onb_add_watch_no_tor_body),
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            pendingMainnetConfirm = false
                            doAdd()
                        }) { Text(stringResource(Res.string.onb_continue_without_tor)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingMainnetConfirm = false }) {
                            Text(stringResource(Res.string.common_cancel))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun BipChipSelectable(
    text: String,
    subtext: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (selected) BhodlTheme.colors.brandPrimary else BhodlTheme.colors.surface
    val containerColor = if (selected) {
        BhodlTheme.colors.brandPrimary.copy(alpha = 0.12f)
    } else {
        BhodlTheme.colors.surfaceVariant
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
            .padding(vertical = 10.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = text,
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textPrimary,
        )
        Text(
            text = subtext,
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textTertiary,
        )
    }
}

@Composable
private fun NetworkChipSelectable(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (selected) BhodlTheme.colors.brandPrimary else BhodlTheme.colors.surface
    val containerColor = if (selected) {
        BhodlTheme.colors.brandPrimary.copy(alpha = 0.12f)
    } else {
        BhodlTheme.colors.surfaceVariant
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(containerColor)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textPrimary,
        )
    }
}

public object AddWatchTags {
    public const val LABEL: String = "add-watch.label"
    public const val XPUB: String = "add-watch.xpub"
    public const val SUBMIT: String = "add-watch.submit"
}
