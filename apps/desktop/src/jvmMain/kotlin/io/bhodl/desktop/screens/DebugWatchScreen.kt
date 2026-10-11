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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.settings.EsploraEndpoint
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.ui.BhodlTopBar
import io.bhodl.desktop.wizard.WalletPaths
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.ffi.Network
import io.bhodl.ffi.RegistryHandle
import io.bhodl.desktop.ui.userMessage
import io.bhodl.ffi.WalletException
import io.bhodl.ffi.WalletKindFfi
import io.bhodl.ffi.WalletRecordFfi
import io.bhodl.ffi.WatchBipFfi
import io.bhodl.ffi.WatchHandle
import io.bhodl.desktop.ui.displayAmount
import io.bhodl.ui.theme.BhodlTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phase 3.1 debug-only screen for smoke-testing watch wallets.
 *
 * Two states:
 *  - **Locked**: a single-field PIN prompt. On submit we call
 *    [`RegistryHandle.createOrLoad`] off the main thread; wrong PIN
 *    surfaces as `WalletException.Storage` → "Wrong device PIN.".
 *  - **Unlocked**: aggregate watch balance, a collapsible
 *    "Add watch wallet" form (label + xpub + BIP-86/84/49 chip +
 *    network chip), and the full registry list with per-row balance
 *    and "Sync" button.
 *
 * The screen creates a fresh `RegistryHandle` on unlock and disposes
 * it on exit (via [`DisposableEffect`]). The `Map<walletId, WatchHandle>`
 * is also disposed eagerly — file locks would otherwise block subsequent
 * registry opens. **No app-wide registry state** is introduced here;
 * Phase 3.2's proper wallet-picker rework will replace this screen with
 * a `RegistryStore` analogous to `WalletStore`.
 *
 * Entry point lives in `SettingsScreen` and only appears when
 * `BHODL_DEBUG=1` is set in the process environment.
 */
@Composable
public fun DebugWatchScreen(
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var registry by remember { mutableStateOf<RegistryHandle?>(null) }
    val watchHandles = remember { mutableStateMapOf<String, WatchHandle>() }

    // Release the FFI Arc + every opened watch handle when the screen
    // leaves composition. Without this, the next `createOrLoad` call
    // would fail to acquire the SQLCipher file lock (Phase 3 architecture
    // §"File-lock release on wipe").
    DisposableEffect(Unit) {
        onDispose {
            watchHandles.values.forEach { runCatching { it.destroy() } }
            watchHandles.clear()
            registry?.let { runCatching { it.destroy() } }
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
                .widthIn(max = 720.dp)
                .padding(horizontal = 32.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BhodlTopBar(title = "Debug: Watch wallets", onBack = onBack)

            val currentRegistry = registry
            if (currentRegistry == null) {
                LockedState(
                    onUnlocked = { handle -> registry = handle },
                    onBack = onBack,
                )
            } else {
                UnlockedState(
                    registry = currentRegistry,
                    watchHandles = watchHandles,
                    scope = scope,
                )
            }
        }
    }
}

@Composable
private fun LockedState(
    onUnlocked: (RegistryHandle) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var lastError: String? by remember { mutableStateOf(null) }
    val pinFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { pinFocus.requestFocus() }
    }

    Column(
        modifier = Modifier
            .widthIn(max = 480.dp)
            .padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "(only available with BHODL_DEBUG=1)",
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textTertiary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Enter your device PIN to open the registry:",
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )

        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it.filter { c -> !c.isWhitespace() } },
            label = { Text("Device PIN") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(pinFocus)
                .testTag(DebugWatchTags.DEVICE_PIN),
        )

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
                if (working || pin.isBlank()) return@Button
                // Audit 2026-10 — counted against the device-PIN lockout.
                io.bhodl.desktop.wizard.PinAttemptTracker
                    .lockoutMessage(io.bhodl.desktop.wizard.PinScopes.DEVICE)?.let {
                        lastError = it
                        return@Button
                    }
                working = true
                lastError = null
                scope.launch {
                    val dataDir = WalletPaths.defaultDataDir().absolutePath
                    val attemptedPin = pin
                    runCatching {
                        withContext(Dispatchers.IO) {
                            RegistryHandle.createOrLoad(
                                dataDir = dataDir,
                                devicePin = attemptedPin,
                            )
                        }
                    }.fold(
                        onSuccess = { handle ->
                            io.bhodl.desktop.wizard.PinAttemptTracker
                                .recordSuccess(io.bhodl.desktop.wizard.PinScopes.DEVICE)
                            working = false
                            onUnlocked(handle)
                        },
                        onFailure = { e ->
                            working = false
                            val kind = io.bhodl.desktop.wizard.PinErrors.classify(e)
                            lastError = when (kind) {
                                io.bhodl.desktop.wizard.PinFailureKind.WrongPin -> {
                                    io.bhodl.desktop.wizard.PinAttemptTracker
                                        .recordFailure(io.bhodl.desktop.wizard.PinScopes.DEVICE)
                                    io.bhodl.desktop.wizard.PinAttemptTracker
                                        .lockoutMessage(io.bhodl.desktop.wizard.PinScopes.DEVICE)
                                        ?: "Wrong device PIN."
                                }
                                io.bhodl.desktop.wizard.PinFailureKind.Other ->
                                    e.userMessage("Failed to open registry.")
                                else -> io.bhodl.desktop.wizard.PinErrors.messageFor(kind)
                            }
                        },
                    )
                }
            },
            enabled = pin.isNotBlank() && !working,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(DebugWatchTags.OPEN_REGISTRY),
        ) {
            Text(if (working) "Opening…" else "Open registry")
        }

        OutlinedButton(
            onClick = onBack,
            enabled = !working,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Back")
        }
    }
}

/**
 * Snapshot of a pending watch-wallet add waiting for user confirmation.
 *
 * Only used when the network is `MAINNET` and Tor is disabled — the
 * user must explicitly acknowledge the privacy leak before the
 * Esplora full-scan reveals the xpub's address set to a public server.
 */
private data class PendingAdd(
    val label: String,
    val xpub: String,
    val bip: WatchBipFfi,
    val network: Network,
)

/** Result bundle for the registry-read coroutine, kept as a single
 *  immutable value so the success-callback can pattern-destructure it
 *  without 4-arg `Tuple` gymnastics. */
private data class BalanceSnapshot(
    val list: List<WalletRecordFfi>,
    val watchBalance: ULong,
    val signingBalance: ULong?,
    /** `true` when the active signing wallet's account xpub matches at
     *  least one watch row's descriptor. The aggregate calculator drops
     *  the signing balance from the total in that case to avoid
     *  double-counting. */
    val signingMirroredAsWatch: Boolean,
    val perRow: Map<String, ULong>,
)

@Composable
private fun UnlockedState(
    registry: RegistryHandle,
    watchHandles: androidx.compose.runtime.snapshots.SnapshotStateMap<String, WatchHandle>,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    // `wallets` is the source of truth for the row list. `balances` is
    // a parallel map of computed sat balances; populated on first load
    // and on each "Refresh balances" / per-row sync.
    var wallets by remember { mutableStateOf<List<WalletRecordFfi>>(emptyList()) }
    val balances = remember { mutableStateMapOf<String, ULong>() }
    var watchBalance by remember { mutableStateOf<ULong>(0UL) }
    // Active signing wallet balance (from `WalletStore.current()`).
    // `null` when no wallet is unlocked; `0UL` when one is unlocked but
    // its balance is genuinely zero. The distinction matters for the
    // total: we omit the signing line entirely when null.
    var signingBalance by remember { mutableStateOf<ULong?>(null) }
    // `true` when the active signing wallet's xpub matches at least one
    // watch row in the registry. In that case the watch balance already
    // covers the signing UTXOs, so adding `signingBalance` again would
    // double-count. The aggregate card hides the "active signing" line
    // and the total uses watchBalance only.
    var signingMirroredAsWatch by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var loadError: String? by remember { mutableStateOf(null) }
    val rowErrors = remember { mutableStateMapOf<String, String>() }
    val syncing = remember { mutableStateMapOf<String, Boolean>() }
    val removing = remember { mutableStateMapOf<String, Boolean>() }
    // Pending mainnet-without-Tor add — set by `AddWatchSection` and
    // cleared after the user confirms or cancels via the dialog below.
    var pendingMainnetConfirm by remember { mutableStateOf<PendingAdd?>(null) }
    // Pending remove — set by per-row Remove tap, cleared via dialog
    // confirm / cancel. Removal is destructive (deletes the watch-state
    // .sqlite from disk) so we never proceed without an explicit
    // confirm.
    var pendingRemove by remember { mutableStateOf<WalletRecordFfi?>(null) }

    // Initial load on entry — bring up the wallet list + balances so the
    // user immediately sees what's in the registry without an extra click.
    LaunchedEffect(registry) {
        refreshing = true
        loadError = null
        runCatching {
            withContext(Dispatchers.IO) {
                loadSnapshot(registry, watchHandles)
            }
        }.fold(
            onSuccess = { snap ->
                wallets = snap.list
                watchBalance = snap.watchBalance
                signingBalance = snap.signingBalance
                signingMirroredAsWatch = snap.signingMirroredAsWatch
                balances.clear()
                balances.putAll(snap.perRow)
                refreshing = false
            },
            onFailure = { e ->
                refreshing = false
                loadError = e.userMessage("Failed to load wallets.")
            },
        )
    }

    /** Re-read registry + signing balance and refresh the local snapshot. */
    fun refreshSnapshot(syncedWalletId: String? = null) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    loadSnapshot(registry, watchHandles)
                }
            }.fold(
                onSuccess = { snap ->
                    wallets = snap.list
                    watchBalance = snap.watchBalance
                    signingBalance = snap.signingBalance
                    signingMirroredAsWatch = snap.signingMirroredAsWatch
                    balances.clear()
                    balances.putAll(snap.perRow)
                    refreshing = false
                    if (syncedWalletId != null) syncing.remove(syncedWalletId)
                },
                onFailure = { e ->
                    refreshing = false
                    if (syncedWalletId != null) syncing.remove(syncedWalletId)
                    loadError = e.userMessage("Refresh failed.")
                },
            )
        }
    }

    /** Tear down a watch wallet: dispose its cached `WatchHandle` to
     *  release the SQLCipher file lock, remove the registry row, then
     *  delete the on-disk watch-state file. Signing-wallet rows are
     *  never passed here (the per-row Remove button is hidden for
     *  them). */
    fun performRemove(record: WalletRecordFfi) {
        scope.launch {
            removing[record.id] = true
            rowErrors.remove(record.id)
            runCatching {
                withContext(Dispatchers.IO) {
                    // 1. Release the cached handle if any. Closing the
                    //    Connection frees the SQLCipher file lock on
                    //    Windows so the subsequent file delete is not
                    //    blocked.
                    watchHandles.remove(record.id)?.let { runCatching { it.destroy() } }
                    // 2. Drop the registry row.
                    registry.removeWallet(record.id)
                    // 3. Delete the encrypted state file from disk.
                    //    `Registry::remove_wallet` deliberately does not
                    //    touch the filesystem (single responsibility).
                    val watchStateFile = java.io.File(
                        java.io.File(WalletPaths.defaultDataDir(), "watch-state"),
                        "${record.id}.sqlite",
                    )
                    runCatching { watchStateFile.delete() }
                }
            }.fold(
                onSuccess = {
                    removing.remove(record.id)
                    io.bhodl.desktop.wizard.RegistryStore.bumpDataVersion()
                    refreshSnapshot()
                },
                onFailure = { e ->
                    removing.remove(record.id)
                    rowErrors[record.id] = e.userMessage("Remove failed.")
                },
            )
        }
    }

    /** Perform the add + auto-sync sequence. Called after the user
     *  bypasses (or doesn't need to see) the mainnet/Tor warning. */
    fun performAdd(pending: PendingAdd) {
        scope.launch {
            // Phase 1: add the registry row + create the watch-state file.
            val added: WalletRecordFfi? = runCatching {
                withContext(Dispatchers.IO) {
                    registry.addWatchXpub(
                        label = pending.label,
                        xpub = pending.xpub,
                        network = pending.network,
                        bip = pending.bip,
                    )
                }
            }.fold(
                onSuccess = { it },
                onFailure = { e ->
                    loadError = e.userMessage("Add failed.")
                    null
                },
            )
            // Refresh the row list immediately so the user sees the new
            // entry while sync runs in the background.
            refreshSnapshot()
            if (added == null) return@launch
            io.bhodl.desktop.wizard.RegistryStore.bumpDataVersion()

            // Phase 2: auto-sync. Mainnet has already been gated by the
            // Tor warning dialog at this point. Signet / regtest sync
            // unconditionally — no real privacy concern.
            syncing[added.id] = true
            runCatching {
                withContext(Dispatchers.IO) {
                    val handle = openWatchHandleCached(
                        registry = registry,
                        watchHandles = watchHandles,
                        walletId = added.id,
                    )
                    val network = EsploraEndpoint.networkOf(added.network)
                        ?: error("Unknown network '${added.network}'")
                    handle.syncWithEsplora(
                        EsploraEndpoint.current(network),
                        EsploraEndpoint.currentProxy(),
                    )
                }
            }.fold(
                onSuccess = { refreshSnapshot(syncedWalletId = added.id) },
                onFailure = { e ->
                    syncing.remove(added.id)
                    rowErrors[added.id] = e.userMessage("Auto-sync failed.")
                },
            )
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AggregateBalanceCard(
            signingBalance = signingBalance,
            watchBalance = watchBalance,
            signingMirroredAsWatch = signingMirroredAsWatch,
            refreshing = refreshing,
            onRefresh = {
                if (refreshing) return@AggregateBalanceCard
                refreshing = true
                loadError = null
                refreshSnapshot()
            },
        )

        if (loadError != null) {
            Text(
                text = loadError!!,
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.error,
            )
        }

        AddWatchSection(
            onAdd = { label, xpub, bip, network ->
                val pending = PendingAdd(label, xpub, bip, network)
                val mainnetNoTor = network == Network.MAINNET &&
                    !SettingsStore.torEnabled.value
                if (mainnetNoTor) {
                    pendingMainnetConfirm = pending
                } else {
                    performAdd(pending)
                }
            },
        )

        // Mainnet-without-Tor privacy warning. Blocks the add until the
        // user explicitly confirms — explains the leak and offers Tor
        // as the right answer. Cancel goes back to the form without
        // touching the registry.
        if (pendingMainnetConfirm != null) {
            val pending = pendingMainnetConfirm!!
            AlertDialog(
                onDismissRequest = { pendingMainnetConfirm = null },
                title = { Text("Mainnet sync without Tor") },
                text = {
                    Text(
                        "You're about to add a mainnet watch wallet and sync " +
                            "it against a public Esplora server WITHOUT Tor. " +
                            "The server will see every receive + change " +
                            "address derived from your xpub, and most " +
                            "public Esplora providers log queries. The link " +
                            "between your IP, this xpub, and your balance / " +
                            "activity is then permanently in their logs.\n\n" +
                            "Recommended: enable Tor in Settings → Tor before " +
                            "continuing. Otherwise tap \"Continue without Tor\" " +
                            "to proceed at your own discretion.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val toAdd = pendingMainnetConfirm!!
                        pendingMainnetConfirm = null
                        performAdd(toAdd)
                    }) { Text("Continue without Tor") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingMainnetConfirm = null }) {
                        Text("Cancel")
                    }
                },
            )
        }

        Divider(color = BhodlTheme.colors.outline)

        Text(
            text = "Registered wallets",
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textSecondary,
        )

        if (wallets.isEmpty()) {
            Text(
                text = if (refreshing) "Loading…" else "No wallets in the registry yet.",
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textTertiary,
            )
        } else {
            wallets.forEach { record ->
                WalletRow(
                    record = record,
                    balanceSat = balances[record.id],
                    syncing = syncing[record.id] == true,
                    removing = removing[record.id] == true,
                    error = rowErrors[record.id],
                    onSync = {
                        if (record.kind == WalletKindFfi.SIGNING) return@WalletRow
                        if (syncing[record.id] == true) return@WalletRow
                        if (removing[record.id] == true) return@WalletRow
                        syncing[record.id] = true
                        rowErrors.remove(record.id)
                        scope.launch {
                            // Mainnet-without-Tor doesn't show the dialog
                            // for manual sync — the user already opted in
                            // when they tapped the explicit Sync button on
                            // this specific row.
                            // The watch wallet's OWN network endpoint.
                            val esploraUrl = EsploraEndpoint.networkOf(record.network)
                                ?.let { EsploraEndpoint.current(it) }
                            val proxyUrl = EsploraEndpoint.currentProxy()
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    val handle = openWatchHandleCached(
                                        registry = registry,
                                        watchHandles = watchHandles,
                                        walletId = record.id,
                                    )
                                    handle.syncWithEsplora(
                                        esploraUrl ?: error("Unknown network '${record.network}'"),
                                        proxyUrl,
                                    )
                                }
                            }.fold(
                                onSuccess = { refreshSnapshot(syncedWalletId = record.id) },
                                onFailure = { e ->
                                    syncing[record.id] = false
                                    rowErrors[record.id] = e.userMessage("Sync failed.")
                                },
                            )
                        }
                    },
                    onRemove = {
                        if (record.kind == WalletKindFfi.SIGNING) return@WalletRow
                        if (removing[record.id] == true) return@WalletRow
                        pendingRemove = record
                    },
                )
            }
        }

        // Remove-confirmation dialog. Set by `WalletRow.onRemove`,
        // cleared by either Confirm (calls `performRemove`) or Cancel.
        if (pendingRemove != null) {
            val target = pendingRemove!!
            AlertDialog(
                onDismissRequest = { pendingRemove = null },
                title = { Text("Remove watch wallet?") },
                text = {
                    Text(
                        "This will permanently delete \"${target.label}\" from " +
                            "the registry and erase its encrypted chain-state " +
                            "file from disk. No signing keys are touched — " +
                            "you can re-add the same xpub later, but the cached " +
                            "transaction history will be re-fetched from scratch.",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val toRemove = pendingRemove!!
                            pendingRemove = null
                            performRemove(toRemove)
                        },
                        modifier = Modifier.testTag(DebugWatchTags.REMOVE_CONFIRM),
                    ) { Text("Remove") }
                },
                dismissButton = {
                    TextButton(
                        onClick = { pendingRemove = null },
                        modifier = Modifier.testTag(DebugWatchTags.REMOVE_CANCEL),
                    ) { Text("Cancel") }
                },
            )
        }
    }
}

@Composable
private fun AggregateBalanceCard(
    signingBalance: ULong?,
    watchBalance: ULong,
    signingMirroredAsWatch: Boolean,
    refreshing: Boolean,
    onRefresh: () -> Unit,
) {
    // Dedup: when the active signing wallet is also tracked as a
    // watch-only entry, the watch balance already includes those UTXOs.
    // Adding signingBalance would double-count.
    val effectiveSigning = if (signingMirroredAsWatch) 0UL else (signingBalance ?: 0UL)
    val total = effectiveSigning + watchBalance
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BhodlTheme.colors.surface)
            .border(1.dp, BhodlTheme.colors.outline, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Total balance (signing + watch)",
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textSecondary,
        )
        Text(
            text = displayAmount(total),
            style = BhodlTheme.typography.titleMedium,
            color = BhodlTheme.colors.textPrimary,
        )
        if (signingBalance != null) {
            if (signingMirroredAsWatch) {
                Text(
                    text = "  active signing: ${displayAmount(signingBalance)}" +
                        " (already tracked as watch — counted once)",
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textTertiary,
                )
            } else {
                Text(
                    text = "  active signing: ${displayAmount(signingBalance)}",
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textTertiary,
                )
            }
        }
        Text(
            text = "  watch wallets: ${displayAmount(watchBalance)}",
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textTertiary,
        )
        OutlinedButton(
            onClick = onRefresh,
            enabled = !refreshing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (refreshing) "Refreshing…" else "Refresh balances")
        }
    }
}

@Composable
private fun AddWatchSection(
    onAdd: (label: String, xpub: String, bip: WatchBipFfi, network: Network) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf("") }
    var xpub by remember { mutableStateOf("") }
    var bip by remember { mutableStateOf(WatchBipFfi.TAPROOT) }
    var network by remember { mutableStateOf(Network.SIGNET) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BhodlTheme.colors.surface)
            .border(1.dp, BhodlTheme.colors.outline, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Add watch wallet (xpub)",
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.textPrimary,
            )
            Text(
                text = if (expanded) "Hide" else "Show",
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.brandPrimary,
            )
        }

        if (expanded) {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Label") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(DebugWatchTags.ADD_LABEL),
            )
            OutlinedTextField(
                value = xpub,
                onValueChange = { xpub = it.trim() },
                label = { Text("Xpub / tpub / vpub / upub") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(DebugWatchTags.ADD_XPUB),
            )

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Script type",
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textSecondary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BipChip(
                        text = "Taproot",
                        subtext = "BIP-86",
                        selected = bip == WatchBipFfi.TAPROOT,
                        onClick = { bip = WatchBipFfi.TAPROOT },
                        modifier = Modifier.weight(1f),
                    )
                    BipChip(
                        text = "Native SegWit",
                        subtext = "BIP-84",
                        selected = bip == WatchBipFfi.NATIVE_SEGWIT,
                        onClick = { bip = WatchBipFfi.NATIVE_SEGWIT },
                        modifier = Modifier.weight(1f),
                    )
                    BipChip(
                        text = "Nested SegWit",
                        subtext = "BIP-49",
                        selected = bip == WatchBipFfi.NESTED_SEGWIT,
                        onClick = { bip = WatchBipFfi.NESTED_SEGWIT },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Network",
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textSecondary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NetworkChip(
                        text = "Signet",
                        selected = network == Network.SIGNET,
                        onClick = { network = Network.SIGNET },
                        modifier = Modifier.weight(1f),
                    )
                    NetworkChip(
                        text = "Mainnet",
                        selected = network == Network.MAINNET,
                        onClick = { network = Network.MAINNET },
                        modifier = Modifier.weight(1f),
                    )
                    NetworkChip(
                        text = "Testnet4",
                        selected = network == Network.TESTNET4,
                        onClick = { network = Network.TESTNET4 },
                        modifier = Modifier.weight(1f),
                    )
                    NetworkChip(
                        text = "Regtest",
                        selected = network == Network.REGTEST,
                        onClick = { network = Network.REGTEST },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Button(
                onClick = {
                    if (label.isBlank() || xpub.isBlank()) return@Button
                    onAdd(label.trim(), xpub.trim(), bip, network)
                    // Optimistically clear the form so the user can paste another
                    // one immediately. The async failure path will surface via
                    // the top-level `loadError`.
                    label = ""
                    xpub = ""
                },
                enabled = label.isNotBlank() && xpub.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(DebugWatchTags.ADD_SUBMIT),
            ) {
                Text("Add")
            }
        }
    }
}

@Composable
private fun BipChip(
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
private fun NetworkChip(
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
    ) {
        Text(
            text = text,
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textPrimary,
        )
    }
}

@Composable
private fun WalletRow(
    record: WalletRecordFfi,
    balanceSat: ULong?,
    syncing: Boolean,
    removing: Boolean,
    error: String?,
    onSync: () -> Unit,
    onRemove: () -> Unit,
) {
    val isSigning = record.kind == WalletKindFfi.SIGNING
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BhodlTheme.colors.surface)
            .border(1.dp, BhodlTheme.colors.outline, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.label,
                    style = BhodlTheme.typography.titleMedium,
                    color = BhodlTheme.colors.textPrimary,
                )
                Text(
                    text = "${kindIcon(record.kind)} ${kindLabel(record.kind)} · ${record.network}",
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textTertiary,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = balanceSat?.let { displayAmount(it) } ?: "—",
                    style = BhodlTheme.typography.titleMedium,
                    color = BhodlTheme.colors.textPrimary,
                )
                if (isSigning) {
                    Text(
                        text = "Signing wallet (active)",
                        style = BhodlTheme.typography.bodyMedium,
                        color = BhodlTheme.colors.brandPrimary,
                    )
                }
            }
        }
        Text(
            text = truncateDescriptor(record.descriptorPub),
            style = BhodlTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = BhodlTheme.colors.textTertiary,
        )
        if (error != null) {
            Text(
                text = error,
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.error,
            )
        }
        if (!isSigning) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onSync,
                    enabled = !syncing && !removing,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(DebugWatchTags.SYNC_PREFIX + record.id),
                ) {
                    Text(if (syncing) "Syncing…" else "Sync")
                }
                OutlinedButton(
                    onClick = onRemove,
                    enabled = !syncing && !removing,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(DebugWatchTags.REMOVE_PREFIX + record.id),
                ) {
                    Text(if (removing) "Removing…" else "Remove")
                }
            }
        }
    }
}

private fun kindIcon(kind: WalletKindFfi): String = when (kind) {
    WalletKindFfi.SIGNING -> "[S]"
    WalletKindFfi.WATCH_XPUB -> "[X]"
    WalletKindFfi.WATCH_ADDRESSES -> "[A]"
    WalletKindFfi.WATCH_HD -> "[HD]"
}

private fun kindLabel(kind: WalletKindFfi): String = when (kind) {
    WalletKindFfi.SIGNING -> "Signing"
    WalletKindFfi.WATCH_XPUB -> "Watch xpub"
    WalletKindFfi.WATCH_ADDRESSES -> "Watch addresses"
    WalletKindFfi.WATCH_HD -> "Watch HD"
}

private fun truncateDescriptor(d: String): String =
    if (d.length <= 30) d else d.take(30) + "…"

/**
 * Read per-row balances. For signing rows we can't open them via
 * `openWatch` (returns Err by design), so we skip them — the row
 * displays an em-dash. Watch rows that haven't been opened yet are
 * lazily wired into the [`watchHandles`] cache so the next sync reuses
 * the same `WatchHandle`.
 */
private fun computeBalances(
    registry: RegistryHandle,
    wallets: List<WalletRecordFfi>,
    watchHandles: androidx.compose.runtime.snapshots.SnapshotStateMap<String, WatchHandle>,
): Map<String, ULong> {
    val out = mutableMapOf<String, ULong>()
    for (record in wallets) {
        if (record.kind == WalletKindFfi.SIGNING) continue
        val handle = runCatching {
            openWatchHandleCached(registry, watchHandles, record.id)
        }.getOrNull() ?: continue
        runCatching { handle.balanceSat() }.getOrNull()?.let { out[record.id] = it }
    }
    return out
}

/**
 * Read the registry + signing wallet state in one shot, and decide
 * whether the active signing wallet's account xpub is already covered
 * by a watch row (in which case its balance must NOT be added to the
 * total — the watch balance already includes the same UTXOs).
 *
 * Lives outside the screen composable so coroutines run it on
 * `Dispatchers.IO` cleanly.
 */
private fun loadSnapshot(
    registry: RegistryHandle,
    watchHandles: androidx.compose.runtime.snapshots.SnapshotStateMap<String, WatchHandle>,
): BalanceSnapshot {
    val list = registry.listWallets()
    val watchAggregate = registry.aggregateWatchBalanceSat(WalletStore.network())
    val signingPort = WalletStore.current()
    val signingBalance = signingPort?.balanceSat()
    val signingXpub = signingPort
        ?.runCatching { exportPublicDescriptor().accountXpub }
        ?.getOrNull()
    val mirrored = if (signingXpub != null) {
        list.any { record ->
            if (record.kind == WalletKindFfi.SIGNING) return@any false
            io.bhodl.ffi.extractAccountXpub(record.descriptorPub) == signingXpub
        }
    } else {
        false
    }
    return BalanceSnapshot(
        list = list,
        watchBalance = watchAggregate,
        signingBalance = signingBalance,
        signingMirroredAsWatch = mirrored,
        perRow = computeBalances(registry, list, watchHandles),
    )
}

/**
 * Reuse an opened [`WatchHandle`] across syncs / balance reads. The
 * underlying `WatchAccount` takes `&self` for every read, so a single
 * handle per wallet is safe and cheaper than open/close per call.
 *
 * Handles are released by the parent screen's `DisposableEffect`
 * when [`DebugWatchScreen`] leaves composition.
 */
private fun openWatchHandleCached(
    registry: RegistryHandle,
    watchHandles: androidx.compose.runtime.snapshots.SnapshotStateMap<String, WatchHandle>,
    walletId: String,
): WatchHandle {
    val existing = watchHandles[walletId]
    if (existing != null) return existing
    val fresh = registry.openWatch(walletId = walletId)
    watchHandles[walletId] = fresh
    return fresh
}

/** Test tags exposed by [`DebugWatchScreen`]. */
public object DebugWatchTags {
    public const val DEVICE_PIN: String = "debug-watch.device-pin"
    public const val OPEN_REGISTRY: String = "debug-watch.open-registry"
    public const val ADD_LABEL: String = "debug-watch.add-label"
    public const val ADD_XPUB: String = "debug-watch.add-xpub"
    public const val ADD_SUBMIT: String = "debug-watch.add-submit"
    public const val SYNC_PREFIX: String = "debug-watch.sync." // append wallet id
    public const val REMOVE_PREFIX: String = "debug-watch.remove." // append wallet id
    public const val REMOVE_CONFIRM: String = "debug-watch.remove.confirm"
    public const val REMOVE_CANCEL: String = "debug-watch.remove.cancel"
}
