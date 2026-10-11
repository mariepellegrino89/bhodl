package io.bhodl.desktop.screens

import io.bhodl.desktop.ui.userMessage
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.settings.EsploraEndpoint
import io.bhodl.desktop.wizard.RegistryStore
import io.bhodl.desktop.wizard.WalletPaths
import io.bhodl.desktop.wizard.localizedText
import io.bhodl.ffi.WalletKindFfi
import io.bhodl.ffi.WalletRecordFfi
import io.bhodl.desktop.ui.displayAmount
import io.bhodl.ui.theme.BhodlTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.common_cancel
import io.bhodl.shared.resources.onb_add_new_wallet
import io.bhodl.shared.resources.onb_add_watch
import io.bhodl.shared.resources.onb_create_wallet
import io.bhodl.shared.resources.onb_erase_all_local
import io.bhodl.shared.resources.onb_have_wallet
import io.bhodl.shared.resources.onb_hide_options
import io.bhodl.shared.resources.onb_kind_signing
import io.bhodl.shared.resources.onb_kind_signing_tracked
import io.bhodl.shared.resources.onb_kind_watch_addresses
import io.bhodl.shared.resources.onb_kind_watch_hd
import io.bhodl.shared.resources.onb_kind_watch_xpub
import io.bhodl.shared.resources.onb_loading_wallets
import io.bhodl.shared.resources.onb_open_tracker
import io.bhodl.shared.resources.onb_remove
import io.bhodl.shared.resources.onb_remove_tracker
import io.bhodl.shared.resources.onb_remove_watch
import io.bhodl.shared.resources.onb_remove_watch_body
import io.bhodl.shared.resources.onb_remove_watch_title
import io.bhodl.shared.resources.onb_restore_backup
import io.bhodl.shared.resources.onb_restore_phrase
import io.bhodl.shared.resources.onb_sync_failed
import io.bhodl.shared.resources.onb_sync_partial
import io.bhodl.shared.resources.onb_welcome_body
import io.bhodl.shared.resources.onb_welcome_title
import io.bhodl.shared.resources.onb_your_wallets
import org.jetbrains.compose.resources.stringResource

/**
 * Welcome / hub screen.
 *
 * Two visual modes:
 * - **Picker mode** (registry exists and contains at least one wallet):
 *   show the list of registered wallets, each tap-to-activate. An
 *   "Add new wallet" toggle reveals the create / restore / watch-only
 *   sub-actions when the user wants to extend the set.
 * - **Legacy mode** (no registry, or registry empty): the original
 *   four-button onboarding hub — Create, I already have a wallet,
 *   Restore from seed, Restore from backup.
 *
 * Sub-phase A wired the boot device-PIN gate so `RegistryStore` is
 * populated by the time the user lands here. Sub-phase B
 * (this commit) renders the picker. Sub-phase C will route signing
 * taps to a per-wallet PIN prompt that loads the right wallet by
 * UUID; sub-phase D will activate watch wallets read-only. Until C/D
 * land, signing rows route to the existing single-wallet `Unlock`
 * screen and watch rows are visually disabled with a tooltip.
 *
 * The reset button is intentionally tucked at the bottom and hidden
 * behind a two-tap confirmation. It is the only escape hatch for
 * users who lost their PIN.
 */
@Composable
public fun WelcomeScreen(
    onCreateWallet: () -> Unit,
    onUnlock: (walletId: String?) -> Unit,
    onActivateWatch: (walletId: String) -> Unit = {},
    onRestore: () -> Unit,
    onRestoreBackup: () -> Unit = {},
    onAddWatch: () -> Unit = {},
    onResetComplete: () -> Unit,
    // Phase 6.0 — jump to the Tracker landing without selecting a
    // signing wallet. Visible only when a registry is already open
    // (RegistryStore populated) AND at least one wallet exists. No-op
    // default so legacy callsites that don't wire this stay compiling.
    onOpenTracker: () -> Unit = {},
    // Injection seam so Compose UI tests can target a tempdir
    // instead of `~/.bhodl/`.
    dataDir: java.io.File = WalletPaths.defaultDataDir(),
) {
    val scope = rememberCoroutineScope()
    var resetDialogOpen by remember { mutableStateOf(false) }
    var resetMessage: String? by remember { mutableStateOf(null) }

    // Picker-mode state. `wallets` is `null` while the registry read
    // is in flight, an empty list after a successful read with no
    // wallets, or a populated list. The legacy mode renders when
    // either RegistryStore is unpopulated (no registry on disk) or
    // the registry read returned no rows.
    var wallets by remember { mutableStateOf<List<WalletRecordFfi>?>(null) }
    val watchBalances = remember { mutableStateMapOf<String, ULong>() }
    var loading by remember { mutableStateOf(true) }
    // Audit 2026-10 (H8) — watch-wallet sync failures (e.g. Tor proxy
    // down) are surfaced instead of silently showing cached balances.
    var syncWarning by remember { mutableStateOf<String?>(null) }
    var addExpanded by remember { mutableStateOf(false) }

    /** Re-read the registry + watch balances and refresh local state.
     *  Called on entry and after a successful Remove. */
    suspend fun loadWallets() {
        val registry = RegistryStore.current() ?: run {
            loading = false
            return
        }
        runCatching {
            withContext(Dispatchers.IO) {
                val list = registry.listWallets()
                // Sync each mirror against Esplora before reading its
                // balance — without this the picker shows the last
                // state persisted to the watch's SQLCipher file, which
                // for a fresh mirror is whatever was true at creation
                // time (often 0). The sync is nested in its own
                // runCatching so a single failing endpoint just leaves
                // that row with its cached balance instead of dropping
                // the whole picker. Same fix landed in TrackingScreen
                // on 2026-05-14.
                val proxy = EsploraEndpoint.currentProxy()
                val balances = mutableMapOf<String, ULong>()
                var failed = 0
                var firstError: String? = null
                for (record in list) {
                    if (record.kind == WalletKindFfi.SIGNING) continue
                    runCatching {
                        val handle = registry.openWatch(record.id)
                        try {
                            // Each watch wallet syncs against its OWN
                            // network's endpoint.
                            val network = EsploraEndpoint.networkOf(record.network)
                                ?: error("unknown network '${record.network}'")
                            handle.syncWithEsplora(EsploraEndpoint.current(network), proxy)
                        } catch (e: Exception) {
                            failed++
                            if (firstError == null) firstError = e.userMessage(localizedText(Res.string.onb_sync_failed))
                        }
                        balances[record.id] = handle.balanceSat()
                        handle.destroy()
                    }
                }
                val warning = firstError?.let {
                    localizedText(Res.string.onb_sync_partial, failed, it)
                }
                Triple(list, balances, warning)
            }
        }.fold(
            onSuccess = { (list, balances, warning) ->
                syncWarning = warning
                wallets = list
                watchBalances.clear()
                watchBalances.putAll(balances)
                loading = false
            },
            onFailure = {
                wallets = emptyList()
                loading = false
            },
        )
    }

    LaunchedEffect(Unit) { loadWallets() }

    var pendingRemove by remember { mutableStateOf<WalletRecordFfi?>(null) }

    val pickerMode = (wallets?.isNotEmpty() == true)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BhodlTheme.colors.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .widthIn(max = 560.dp)
                .padding(horizontal = 32.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "BHODL",
                style = BhodlTheme.typography.headlineMedium,
                color = BhodlTheme.colors.brandPrimary,
            )

            // UX review 2026-10 (item 16) — first run (no wallet yet):
            // one-time sats / BTC explainer.
            if (!pickerMode && !loading) {
                UnitsOnboardingCard()
            }

            if (loading && wallets == null && RegistryStore.current() != null) {
                Text(
                    text = stringResource(Res.string.onb_loading_wallets),
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textSecondary,
                )
            } else if (pickerMode) {
                // Phase 6.0 — explicit shortcut into the Tracker
                // landing for users who only want to glance at the
                // numbers (no signing PIN needed). Visible only here
                // (registry exists + at least one wallet) so the
                // legacy first-launch path stays uncluttered.
                OutlinedButton(
                    onClick = onOpenTracker,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(WelcomeTags.OPEN_TRACKER),
                ) {
                    Text(stringResource(Res.string.onb_open_tracker))
                }
                syncWarning?.let { warning ->
                    Text(
                        text = warning,
                        style = BhodlTheme.typography.labelMedium,
                        color = BhodlTheme.colors.error,
                        textAlign = TextAlign.Center,
                    )
                }
                PickerMode(
                    wallets = wallets!!,
                    watchBalances = watchBalances,
                    onSigningTap = { walletId -> onUnlock(walletId) },
                    onWatchTap = { walletId -> onActivateWatch(walletId) },
                    onRemoveWatch = { record -> pendingRemove = record },
                    addExpanded = addExpanded,
                    onToggleAdd = { addExpanded = !addExpanded },
                    onCreateWallet = onCreateWallet,
                    onRestore = onRestore,
                    onRestoreBackup = onRestoreBackup,
                    onAddWatch = onAddWatch,
                )
            } else {
                LegacyMode(
                    onCreateWallet = onCreateWallet,
                    onUnlock = { onUnlock(null) },
                    onRestore = onRestore,
                    onRestoreBackup = onRestoreBackup,
                )
            }

            // Reset zone — visually de-emphasised. Audit 2026-10 (M8):
            // typed-phrase confirmation, handles released BEFORE the
            // files are deleted, deletion verified (see LocalWipe).
            if (resetMessage != null) {
                Text(
                    text = resetMessage!!,
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
            TextButton(
                onClick = { resetDialogOpen = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(WelcomeTags.RESET),
            ) {
                Text(
                    text = stringResource(Res.string.onb_erase_all_local),
                    color = BhodlTheme.colors.textTertiary,
                )
            }
            if (resetDialogOpen) {
                ConfirmWipeDialog(
                    dataDir = dataDir,
                    onDismiss = { resetDialogOpen = false },
                    onFinished = { result ->
                        resetDialogOpen = false
                        resetMessage = io.bhodl.desktop.wizard.LocalWipe.describe(result)
                        if (!result.aborted) onResetComplete()
                    },
                )
            }
        }

        // Remove-confirmation dialog for watch wallets. Same teardown
        // sequence as the debug screen's Remove button: release any
        // cached handle, drop the registry row, delete the watch-state
        // file. RegistryStore stays alive so the picker refreshes
        // in place without re-prompting for the device PIN.
        val target = pendingRemove
        if (target != null) {
            AlertDialog(
                onDismissRequest = { pendingRemove = null },
                title = { Text(stringResource(Res.string.onb_remove_watch_title)) },
                text = {
                    Text(stringResource(Res.string.onb_remove_watch_body, target.label))
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val toRemove = target
                            pendingRemove = null
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        val registry = RegistryStore.current()
                                            ?: return@withContext
                                        registry.removeWallet(toRemove.id)
                                        val watchStateFile = java.io.File(
                                            java.io.File(dataDir, "watch-state"),
                                            "${toRemove.id}.sqlite",
                                        )
                                        runCatching { watchStateFile.delete() }
                                    }
                                }
                                RegistryStore.bumpDataVersion()
                                loadWallets()
                            }
                        },
                    ) { Text(stringResource(Res.string.onb_remove)) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingRemove = null }) {
                        Text(stringResource(Res.string.common_cancel))
                    }
                },
            )
        }
    }
}

@Composable
private fun PickerMode(
    wallets: List<WalletRecordFfi>,
    watchBalances: Map<String, ULong>,
    onSigningTap: (walletId: String) -> Unit,
    onWatchTap: (walletId: String) -> Unit,
    onRemoveWatch: (WalletRecordFfi) -> Unit,
    addExpanded: Boolean,
    onToggleAdd: () -> Unit,
    onCreateWallet: () -> Unit,
    onRestore: () -> Unit,
    onRestoreBackup: () -> Unit,
    onAddWatch: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(Res.string.onb_your_wallets),
            style = BhodlTheme.typography.titleMedium,
            color = BhodlTheme.colors.textPrimary,
        )

        // Group registry rows by extracted account xpub so a signing
        // wallet and its watch mirror render as ONE row labelled
        // "Signing (tracked)" instead of two visually-distinct rows.
        // Tap → Unlock the signing (primary action). Remove button on
        // a tracked-signing group removes ONLY the watch mirror,
        // leaving the signing's seed-encrypted file untouched.
        val groups = groupPickerWalletsByXpub(wallets, watchBalances)
        groups.forEach { group ->
            WalletPickerRow(
                displayLabel = group.displayLabel,
                kindLabel = group.kindLabel,
                network = group.network,
                balanceSat = group.balanceSat,
                onTap = {
                    if (group.signingId != null) {
                        onSigningTap(group.signingId)
                    } else if (group.watchId != null) {
                        onWatchTap(group.watchId)
                    }
                },
                onRemove = group.removableTrackerRecord?.let { tracker ->
                    {
                        onRemoveWatch(tracker)
                    }
                },
                removeLabel = if (group.signingId != null) {
                    stringResource(Res.string.onb_remove_tracker)
                } else {
                    stringResource(Res.string.onb_remove_watch)
                },
                testTagId = group.signingId ?: group.watchId ?: "?",
            )
        }

        OutlinedButton(
            onClick = onToggleAdd,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(WelcomeTags.ADD_NEW_TOGGLE),
        ) {
            Text(
                if (addExpanded) stringResource(Res.string.onb_hide_options)
                else stringResource(Res.string.onb_add_new_wallet),
            )
        }

        if (addExpanded) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onCreateWallet,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(WelcomeTags.CREATE_WALLET),
                ) { Text(stringResource(Res.string.onb_create_wallet)) }

                OutlinedButton(
                    onClick = onRestore,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(WelcomeTags.RESTORE),
                ) { Text(stringResource(Res.string.onb_restore_phrase)) }

                OutlinedButton(
                    onClick = onRestoreBackup,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(WelcomeTags.RESTORE_BACKUP),
                ) { Text(stringResource(Res.string.onb_restore_backup)) }

                OutlinedButton(
                    onClick = onAddWatch,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(WelcomeTags.ADD_WATCH),
                ) { Text(stringResource(Res.string.onb_add_watch)) }
            }
        }
    }
}

/**
 * One logical wallet's representation on the Welcome picker. The
 * same xpub may live across multiple registry rows (signing + watch
 * mirror); from the picker's viewpoint they are ONE wallet.
 *
 * `signingId` points at the row to Unlock when the user taps. When
 * `signingId` is null this is a pure watch group; tap routes to
 * WatchDashboard for `watchId` (the first watch in the group).
 *
 * `removableTrackerRecord` is set when removing makes sense without
 * touching the signing: either there's a signing + mirror (remove
 * the mirror) OR there's no signing and the group has exactly one
 * watch row (remove that). Signing-only groups expose no remove
 * here — they go through Settings → "Delete this wallet" which
 * requires unlock for safety.
 */
internal data class GroupedPickerWallet(
    val displayLabel: String,
    val kindLabel: String,
    val network: String,
    val balanceSat: ULong?,
    val signingId: String?,
    val watchId: String?,
    val removableTrackerRecord: WalletRecordFfi?,
)

internal fun groupPickerWalletsByXpub(
    wallets: List<WalletRecordFfi>,
    watchBalances: Map<String, ULong>,
): List<GroupedPickerWallet> {
    val groups = wallets.groupBy { record ->
        if (record.kind == WalletKindFfi.WATCH_ADDRESSES) {
            record.id
        } else {
            io.bhodl.ffi.extractAccountXpub(record.descriptorPub) ?: record.id
        }
    }
    return groups.values.map { members ->
        val signing = members.firstOrNull { it.kind == WalletKindFfi.SIGNING }
        val watches = members.filter { it.kind != WalletKindFfi.SIGNING }
        if (signing != null) {
            val mirror = watches.firstOrNull()
            GroupedPickerWallet(
                displayLabel = signing.label,
                kindLabel = localizedText(
                    if (mirror != null) Res.string.onb_kind_signing_tracked else Res.string.onb_kind_signing,
                ),
                network = signing.network,
                balanceSat = mirror?.let { watchBalances[it.id] },
                signingId = signing.id,
                watchId = null,
                removableTrackerRecord = mirror,
            )
        } else {
            val w = members.first()
            GroupedPickerWallet(
                displayLabel = w.label,
                kindLabel = kindLabelShort(w.kind),
                network = w.network,
                balanceSat = watchBalances[w.id],
                signingId = null,
                watchId = w.id,
                removableTrackerRecord = w,
            )
        }
    }
}

@Composable
internal fun WalletPickerRow(
    displayLabel: String,
    kindLabel: String,
    network: String,
    balanceSat: ULong?,
    onTap: () -> Unit,
    onRemove: (() -> Unit)? = null,
    removeLabel: String? = null,
    testTagId: String = "?",
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BhodlTheme.colors.surface)
            .border(1.dp, BhodlTheme.colors.outline, RoundedCornerShape(12.dp))
            // Phase 3.2 sub-D — both signing and watch rows are now
            // clickable. The parent decides the destination via
            // `onTap`; signing → Unlock screen, watch → WatchDashboard.
            .clickable(onClick = onTap)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayLabel,
                    style = BhodlTheme.typography.titleMedium,
                    color = BhodlTheme.colors.textPrimary,
                )
                Text(
                    text = "$kindLabel · $network",
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textTertiary,
                )
            }
            Text(
                text = balanceSat?.let { displayAmount(it) } ?: "—",
                style = BhodlTheme.typography.titleMedium,
                color = BhodlTheme.colors.textPrimary,
            )
        }
        // Remove action — surfaced when the group has a watch entry
        // that can be safely removed without touching signing keys.
        if (onRemove != null) {
            TextButton(
                onClick = onRemove,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(WelcomeTags.REMOVE_WATCH_PREFIX + testTagId),
            ) {
                Text(
                    text = removeLabel ?: stringResource(Res.string.onb_remove_watch),
                    color = BhodlTheme.colors.error,
                )
            }
        }
    }
}

internal fun kindLabelShort(kind: WalletKindFfi): String = when (kind) {
    WalletKindFfi.SIGNING -> localizedText(Res.string.onb_kind_signing)
    WalletKindFfi.WATCH_XPUB -> localizedText(Res.string.onb_kind_watch_xpub)
    WalletKindFfi.WATCH_ADDRESSES -> localizedText(Res.string.onb_kind_watch_addresses)
    WalletKindFfi.WATCH_HD -> localizedText(Res.string.onb_kind_watch_hd)
}

@Composable
private fun LegacyMode(
    onCreateWallet: () -> Unit,
    onUnlock: () -> Unit,
    onRestore: () -> Unit,
    onRestoreBackup: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(Res.string.onb_welcome_title),
            style = BhodlTheme.typography.titleMedium,
            color = BhodlTheme.colors.textPrimary,
        )
        Text(
            text = stringResource(Res.string.onb_welcome_body),
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )

        Button(
            onClick = onCreateWallet,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(WelcomeTags.CREATE_WALLET),
        ) { Text(stringResource(Res.string.onb_create_wallet)) }

        OutlinedButton(
            onClick = onUnlock,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(WelcomeTags.UNLOCK_EXISTING),
        ) { Text(stringResource(Res.string.onb_have_wallet)) }

        OutlinedButton(
            onClick = onRestore,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(WelcomeTags.RESTORE),
        ) { Text(stringResource(Res.string.onb_restore_phrase)) }

        OutlinedButton(
            onClick = onRestoreBackup,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(WelcomeTags.RESTORE_BACKUP),
        ) { Text(stringResource(Res.string.onb_restore_backup)) }
    }
}

/**
 * Test tags exposed by [`WelcomeScreen`]. Compose UI tests reference
 * these instead of human-readable strings so the tests stay stable
 * across i18n / copy changes.
 */
public object WelcomeTags {
    public const val CREATE_WALLET: String = "welcome.create-wallet"
    public const val UNLOCK_EXISTING: String = "welcome.unlock-existing"
    public const val RESTORE: String = "welcome.restore"
    public const val RESTORE_BACKUP: String = "welcome.restore-backup"
    public const val RESET: String = "welcome.reset"
    public const val ADD_NEW_TOGGLE: String = "welcome.add-new-toggle"
    public const val ADD_WATCH: String = "welcome.add-watch"
    public const val REMOVE_WATCH_PREFIX: String = "welcome.remove-watch."
    /** Phase 6.0 — "Open Tracker" CTA visible in picker mode. */
    public const val OPEN_TRACKER: String = "welcome.open-tracker"
}
