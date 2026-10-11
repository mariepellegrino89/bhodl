package io.bhodl.desktop.screens

import io.bhodl.desktop.ui.money.RevealAmounts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.prices.FiatCurrency
import io.bhodl.desktop.prices.PriceCache
import io.bhodl.desktop.settings.EsploraEndpoint
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.ui.BhodlFooterStatusBar
import io.bhodl.desktop.ui.ErrorCard
import io.bhodl.desktop.ui.displayAmount
import io.bhodl.desktop.ui.formatFiatWithCode
import io.bhodl.desktop.ui.prettifyNetworkError
import io.bhodl.desktop.ui.userMessage
import io.bhodl.desktop.wallet.SyncCache
import io.bhodl.desktop.wallet.UniffiWalletPort
import io.bhodl.desktop.wallet.WalletPort
import io.bhodl.desktop.wizard.RegistryStore
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.ffi.Network
import io.bhodl.ffi.WalletHandle
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.wview_err_pairing
import io.bhodl.shared.resources.wview_unknown_error
import io.bhodl.shared.resources.wallet_action_contacts
import io.bhodl.shared.resources.wallet_action_lock
import io.bhodl.shared.resources.wallet_header_balance
import io.bhodl.shared.resources.wallet_header_signing
import io.bhodl.shared.resources.wallet_not_synced
import io.bhodl.shared.resources.wallet_restoring_body
import io.bhodl.shared.resources.wallet_restoring_title
import io.bhodl.shared.resources.wallet_sync
import io.bhodl.shared.resources.wallet_sync_failed
import io.bhodl.shared.resources.wallet_synced_at
import io.bhodl.shared.resources.wallet_syncing
import io.bhodl.shared.resources.wallet_tracker_banner_add
import io.bhodl.shared.resources.wallet_tracker_banner_adding
import io.bhodl.shared.resources.wallet_tracker_banner_body
import io.bhodl.shared.resources.wallet_tracker_banner_skip
import io.bhodl.shared.resources.wallet_tracker_banner_title
import io.bhodl.ui.theme.BhodlTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The signing-wallet workspace (UX review 2026-10, §4 items 5–8).
 *
 * Replaces the old centered mobile column with the tracker's dense,
 * full-width language and Sparrow's IA: a wallet header (name, balance
 * in the user's unit + operational fiat, sync status) above sub-tabs
 * **Transactions · Send · Receive · Addresses · UTXOs · Wallet
 * settings**. The old page buttons map as follows:
 *  - Receive / Send buttons → Receive / Send tabs;
 *  - History button → Transactions tab (list-detail, no separate page);
 *  - Address book button → "Contacts" header action;
 *  - Settings button → Wallet settings tab (wallet-scoped sections) —
 *    app-wide preferences stay on the rail's Settings entry;
 *  - Logout → "Lock" header action.
 *
 * Sync model (unchanged contract): one automatic background sync per
 * entry when [SyncCache] is stale (a broadcast marks it stale), plus the
 * header's manual Sync. Tabs never sync on their own inside the
 * workspace; they re-read local state when [dataVersion] moves.
 */
@Composable
public fun DashboardScreen(
    initialTab: WalletTab = WalletTab.TRANSACTIONS,
    /** Send tab built a PSBT → Review screen. */
    onReviewSend: (psbtBase64: String, toAddress: String, amountSat: Long, category: String?) -> Unit =
        { _, _, _, _ -> },
    onPayjoinSent: (txid: String) -> Unit = {},
    onBumpFee: (txid: String) -> Unit = {},
    onCpfp: (txid: String, kind: CpfpKind) -> Unit = { _, _ -> },
    onAddressBookClicked: () -> Unit = {},
    onLockClicked: () -> Unit = {},
    onWalletWiped: () -> Unit = {},
    onSwitchSigningWallet: (walletId: String) -> Unit = {},
    /** UX review 2026-10 (item 14) — footer shield / reminder → Security Hub. */
    onOpenSecurityHub: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()

    // Prefer the wallet that the wizard just unlocked. Without one (a
    // dev launch / deep link with an empty store) fall back to an
    // in-memory handle on the BIP86 test mnemonic so the screen stays
    // useful.
    val wallet = remember {
        WalletStore.current() ?: UniffiWalletPort(
            WalletHandle.fromMnemonic(
                network = Network.SIGNET,
                mnemonic = TEST_MNEMONIC,
                passphrase = "",
            ),
        )
    }

    var tabWire by rememberSaveable { mutableStateOf(initialTab.wire) }
    val tab = WalletTab.fromWire(tabWire)

    // Optimistic balance from the cache so re-entry never flashes "—".
    var balanceSat by remember { mutableStateOf(SyncCache.lastBalanceSat()) }
    var lastSyncAtMs by remember { mutableStateOf(SyncCache.lastSyncAtMs()) }
    var syncMode by remember { mutableStateOf(SyncMode.Idle) }
    var lastError: String? by remember { mutableStateOf(null) }
    var tipHeight by remember { mutableStateOf<UInt?>(null) }
    // Bumped after every sync / local mutation: tabs re-read on change.
    var dataVersion by remember { mutableIntStateOf(0) }
    // UTXOs tab "Send selected" → Send tab with coin control pinned.
    var pendingCoinSelection by remember { mutableStateOf<List<String>>(emptyList()) }
    // Addresses tab "Show" → Receive tab on that address.
    var receivePresetAddress by remember { mutableStateOf<String?>(null) }

    val walletId = WalletStore.activeWalletId()
    var walletName by remember { mutableStateOf<String?>(null) }
    var walletCount by remember { mutableIntStateOf(1) }
    LaunchedEffect(walletId) {
        val registry = RegistryStore.current() ?: return@LaunchedEffect
        runCatching {
            withContext(Dispatchers.IO) {
                val name = walletId?.let { registry.getWallet(it)?.label }
                name to registry.listWallets().size
            }
        }.onSuccess { (name, count) ->
            walletName = name
            walletCount = count.coerceAtLeast(1)
        }
    }

    val onSynced: (ULong) -> Unit = { sat ->
        balanceSat = sat
        SyncCache.record(sat)
        lastSyncAtMs = SyncCache.lastSyncAtMs()
        lastError = null
        // A finished first post-restore sync drops the banner.
        WalletStore.markRestoreComplete()
    }

    fun launchSync(mode: SyncMode) {
        if (syncMode != SyncMode.Idle) return
        scope.launch {
            syncMode = mode
            runCatching {
                withContext(Dispatchers.IO) {
                    wallet.syncWithEsplora(EsploraEndpoint.current(), EsploraEndpoint.currentProxy())
                    applyReceiveLabels(wallet)
                    wallet.balanceSat() to runCatching { wallet.chainTipHeight() }.getOrNull()
                }
            }.onSuccess { (sat, tip) ->
                onSynced(sat)
                tipHeight = tip
            }.onFailure { lastError = it.userMessage(getString(Res.string.wview_unknown_error)) }
            syncMode = SyncMode.Idle
            dataVersion++
        }
    }

    // Background sync on entry — and again whenever a broadcast elsewhere
    // advanced the epoch (that also marks the cache stale).
    val txEpoch by WalletStore.txEpoch
    LaunchedEffect(wallet, txEpoch) {
        tipHeight = runCatching {
            withContext(Dispatchers.IO) { wallet.chainTipHeight() }
        }.getOrNull()
        if (!SyncCache.isFresh()) launchSync(SyncMode.Background)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BhodlTheme.colors.background)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        WalletHeader(
            walletName = walletName ?: stringResource(Res.string.wallet_header_signing),
            kindLabel = stringResource(Res.string.wallet_header_signing),
            network = WalletStore.network(),
            balanceSat = balanceSat,
            syncMode = syncMode,
            lastSyncAtMs = lastSyncAtMs,
            syncFailed = lastError != null,
            onSync = { launchSync(SyncMode.Foreground) },
            actions = {
                TextButton(
                    onClick = onAddressBookClicked,
                    modifier = Modifier.testTag(DashboardTags.ADDRESS_BOOK),
                ) { Text(stringResource(Res.string.wallet_action_contacts)) }
                TextButton(
                    onClick = onLockClicked,
                    modifier = Modifier.testTag(DashboardTags.LOGOUT),
                ) {
                    Text(
                        text = stringResource(Res.string.wallet_action_lock),
                        color = BhodlTheme.colors.textSecondary,
                    )
                }
            },
        )

        val justRestored by WalletStore.justRestored
        if (justRestored) {
            ErrorCard(
                title = stringResource(Res.string.wallet_restoring_title),
                message = stringResource(Res.string.wallet_restoring_body),
                modifier = Modifier.testTag(DashboardTags.RESTORE_BANNER),
            )
        }
        if (lastError != null && syncMode != SyncMode.Foreground) {
            ErrorCard(
                title = stringResource(Res.string.wallet_sync_failed),
                message = prettifyNetworkError(lastError),
                details = lastError,
                onRetry = { launchSync(SyncMode.Foreground) },
            )
        }
        TrackerPairingBanner(wallet = wallet)
        // UX review 2026-10 (item 14) — spaced backup reminder; never
        // on the Send tab (the review / bump / CPFP routes don't host it).
        if (onOpenSecurityHub != null) {
            SecurityReminderBanner(inSendFlow = tab == WalletTab.SEND, onOpenHub = onOpenSecurityHub)
        }

        WalletTabStrip(selected = tab, onSelect = { tabWire = it.wire })

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                WalletTab.TRANSACTIONS -> HistoryScreen(
                    onBack = null,
                    syncOnEnter = false,
                    refreshKey = dataVersion,
                    tipHeight = tipHeight,
                    onBumpFee = onBumpFee,
                    onCpfpIncoming = { onCpfp(it, CpfpKind.IncomingSpeedUp) },
                    onCpfpChangeRescue = { onCpfp(it, CpfpKind.ChangeRescue) },
                )
                // Item 13 — a payment being built always shows real
                // amounts (fee totals, available balance), privacy mode or not.
                WalletTab.SEND -> RevealAmounts {
                    SendScreen(
                        onReady = onReviewSend,
                        onBack = null,
                        onPayjoinSent = onPayjoinSent,
                        preselectedOutpoints = pendingCoinSelection,
                        onPreselectionConsumed = { pendingCoinSelection = emptyList() },
                    )
                }
                WalletTab.RECEIVE -> ReceiveScreen(
                    onBack = null,
                    presetAddress = receivePresetAddress,
                    onPresetConsumed = { receivePresetAddress = null },
                )
                WalletTab.ADDRESSES -> WalletAddressesTab(
                    refreshKey = dataVersion,
                    onShowInReceive = { address ->
                        receivePresetAddress = address
                        tabWire = WalletTab.RECEIVE.wire
                    },
                )
                WalletTab.UTXOS -> WalletUtxosTab(
                    refreshKey = dataVersion,
                    onChanged = { dataVersion++ },
                    onSpendSelected = { outpoints ->
                        pendingCoinSelection = outpoints
                        tabWire = WalletTab.SEND.wire
                    },
                )
                WalletTab.SETTINGS -> WalletSettingsTab(
                    onWalletWiped = onWalletWiped,
                    onSwitchSigningWallet = onSwitchSigningWallet,
                )
            }
        }

        BhodlFooterStatusBar(
            network = WalletStore.network(),
            walletCount = walletCount,
            lastSyncUnix = lastSyncAtMs?.let { it / 1_000L },
            onOpenSecurityHub = onOpenSecurityHub,
        )
    }
}

/**
 * Wallet header shared by the signing workspace and the watch-only
 * view: name + kind + network on the left, balance (unit-aware) with the
 * operational fiat equivalent and the sync status on the right, then
 * the Sync button and the caller's [actions].
 */
@Composable
internal fun WalletHeader(
    walletName: String,
    kindLabel: String,
    network: Network,
    balanceSat: ULong?,
    syncMode: SyncMode,
    lastSyncAtMs: Long?,
    syncFailed: Boolean,
    onSync: () -> Unit,
    syncTestTag: String = DashboardTags.SYNC,
    actions: @Composable () -> Unit = {},
) {
    val currency by SettingsStore.fiatCurrency
    val price by PriceCache.price
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "BHODL",
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.brandPrimary,
            )
            Text(
                text = walletName,
                style = BhodlTheme.typography.headlineMedium,
                color = BhodlTheme.colors.textPrimary,
                maxLines = 1,
                modifier = Modifier.testTag(DashboardTags.WALLET_NAME),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WalletChip(
                    text = ExplorerLinks.networkLabel(network).lowercase(),
                    color = if (network == Network.MAINNET) BhodlTheme.colors.brandPrimary
                    else BhodlTheme.colors.financialNeutral,
                )
                Text(
                    text = kindLabel,
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textTertiary,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = stringResource(Res.string.wallet_header_balance),
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.textSecondary,
            )
            Text(
                // Unit / BIP177 / separators follow Settings.
                text = balanceSat?.let { displayAmount(it) } ?: "—",
                style = BhodlTheme.typography.amountMedium,
                color = BhodlTheme.colors.textPrimary,
                modifier = Modifier.testTag(DashboardTags.BALANCE),
            )
            val fiat = if (currency != FiatCurrency.NONE && price != null && balanceSat != null) {
                PriceCache.satToFiat(balanceSat)?.let { "≈ " + formatFiatWithCode(it, currency.code, masked = io.bhodl.desktop.ui.money.amountsMasked()) }
            } else {
                null
            }
            Text(
                text = listOfNotNull(fiat, syncStatusText(syncMode, lastSyncAtMs, syncFailed))
                    .joinToString(" · "),
                style = BhodlTheme.typography.labelMedium,
                color = if (syncFailed && syncMode == SyncMode.Idle) BhodlTheme.colors.error
                else BhodlTheme.colors.textTertiary,
                textAlign = TextAlign.End,
                modifier = Modifier.testTag(DashboardTags.SYNC_STATUS),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = onSync,
                enabled = syncMode != SyncMode.Foreground,
                modifier = Modifier.testTag(syncTestTag),
            ) {
                if (syncMode != SyncMode.Idle) {
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = if (syncMode == SyncMode.Foreground) stringResource(Res.string.wallet_syncing)
                    else stringResource(Res.string.wallet_sync),
                )
            }
            actions()
        }
    }
}

@Composable
private fun syncStatusText(syncMode: SyncMode, lastSyncAtMs: Long?, failed: Boolean): String = when {
    syncMode != SyncMode.Idle -> stringResource(Res.string.wallet_syncing)
    failed -> stringResource(Res.string.wallet_sync_failed)
    lastSyncAtMs != null -> stringResource(
        Res.string.wallet_synced_at,
        SYNC_TIME_FORMAT.format(Instant.ofEpochMilli(lastSyncAtMs)),
    )
    else -> stringResource(Res.string.wallet_not_synced)
}

private val SYNC_TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

/**
 * Label-first receive, second half — after a sync, the core copies the
 * label / category of a receive address onto the transaction that paid
 * it (net-incoming only, once per (txid, address), never over a label
 * the user set or cleared: `WalletPort.applyReceiveLabels`). Categories
 * are mirrored into the tracker under the rows the tracker actually
 * reads ([io.bhodl.desktop.data.writeTrackerCategory]). Best-effort: a
 * failure never fails the sync. Runs on the caller's IO dispatcher.
 */
internal fun applyReceiveLabels(wallet: WalletPort) {
    val copies = runCatching { wallet.applyReceiveLabels() }.getOrDefault(emptyList())
    val registry = RegistryStore.current() ?: return
    val walletId = WalletStore.activeWalletId() ?: return
    for (copy in copies) {
        val category = copy.category ?: continue
        io.bhodl.desktop.data.writeTrackerCategory(registry, wallet, walletId, copy.txid, category)
    }
}

/**
 * The workspace's sync state: idle, a background (entry) sync that
 * leaves every control usable, or a user-requested foreground sync.
 */
internal enum class SyncMode { Idle, Background, Foreground }

/**
 * Phase 6.0 — one-line, non-modal prompt to pair the active signing
 * wallet with the Tracker as a watch-only mirror. Hidden when there is
 * no registry / wallet id, when the wallet is already tracked, or when
 * the user skipped it (persisted in [SettingsStore.trackerPairingSkipped];
 * Wallet settings › Add to Tracker stays available).
 */
@Composable
private fun TrackerPairingBanner(wallet: WalletPort) {
    val scope = rememberCoroutineScope()
    val walletId = WalletStore.activeWalletId()
    val activeNetwork = WalletStore.network()
    val registry = RegistryStore.current()
    val skipped by SettingsStore.trackerPairingSkipped

    var loaded by remember { mutableStateOf(false) }
    var alreadyTracked by remember { mutableStateOf(false) }
    var descriptor: String? by remember { mutableStateOf(null) }
    var walletLabel by remember { mutableStateOf("My Wallet") }
    var working by remember { mutableStateOf(false) }
    var lastError: String? by remember { mutableStateOf(null) }

    LaunchedEffect(walletId, registry) {
        if (walletId == null || registry == null) {
            loaded = true
            return@LaunchedEffect
        }
        runCatching {
            withContext(Dispatchers.IO) {
                val desc = wallet.trackerDescriptorPub()
                val tracked = isAlreadyTrackedExcluding(registry, desc, walletId)
                val label = runCatching { registry.getWallet(walletId)?.label }.getOrNull() ?: "My Wallet"
                Triple(desc, tracked, label)
            }
        }.fold(
            onSuccess = { (desc, tracked, label) ->
                descriptor = desc
                alreadyTracked = tracked
                walletLabel = label
                loaded = true
            },
            onFailure = {
                // Silent on errors rather than a confusing red box.
                alreadyTracked = true
                loaded = true
            },
        )
    }

    if (!loaded || walletId == null || registry == null || alreadyTracked || walletId in skipped) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BhodlTheme.colors.surface, BhodlTheme.shapes.panel)
            .border(1.dp, BhodlTheme.colors.outline, BhodlTheme.shapes.panel)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(DashboardTags.TRACKER_BANNER),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(Res.string.wallet_tracker_banner_title),
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textPrimary,
            )
            Text(
                text = lastError ?: stringResource(Res.string.wallet_tracker_banner_body),
                style = BhodlTheme.typography.labelMedium,
                color = if (lastError != null) BhodlTheme.colors.error else BhodlTheme.colors.textSecondary,
            )
        }
        Button(
            onClick = {
                val desc = descriptor ?: return@Button
                if (working) return@Button
                working = true
                lastError = null
                val mainnetNoTor = activeNetwork == Network.MAINNET && !SettingsStore.torEnabled.value
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            val xpub = io.bhodl.ffi.extractAccountXpub(desc)
                                ?: error("Could not extract account xpub.")
                            // Linked mirror (registry v4 `mirror_of`).
                            val added = registry.addWatchXpubMirror(
                                signingWalletId = walletId,
                                label = "$walletLabel · tracker",
                                xpub = xpub,
                                network = activeNetwork,
                                bip = io.bhodl.ffi.WatchBipFfi.TAPROOT,
                            )
                            // Best-effort initial sync, skipped on
                            // mainnet without Tor (privacy hint).
                            if (!mainnetNoTor) {
                                runCatching {
                                    val h = registry.openWatch(added.id)
                                    h.syncWithEsplora(
                                        EsploraEndpoint.current(activeNetwork),
                                        EsploraEndpoint.currentProxy(),
                                    )
                                    h.destroy()
                                }
                            }
                        }
                    }.fold(
                        onSuccess = {
                            alreadyTracked = true
                            working = false
                            RegistryStore.bumpDataVersion()
                        },
                        onFailure = { e ->
                            working = false
                            lastError = e.userMessage(getString(Res.string.wview_err_pairing))
                        },
                    )
                }
            },
            enabled = !working && descriptor != null,
            modifier = Modifier.testTag(DashboardTags.TRACKER_BANNER_ADD),
        ) {
            Text(
                if (working) stringResource(Res.string.wallet_tracker_banner_adding)
                else stringResource(Res.string.wallet_tracker_banner_add),
            )
        }
        OutlinedButton(
            onClick = { SettingsStore.markTrackerPairingSkipped(walletId) },
            enabled = !working,
            modifier = Modifier.testTag(DashboardTags.TRACKER_BANNER_SKIP),
        ) { Text(stringResource(Res.string.wallet_tracker_banner_skip)) }
    }
}

private const val TEST_MNEMONIC: String =
    "abandon abandon abandon abandon abandon abandon " +
        "abandon abandon abandon abandon abandon about"

/** Test tags exposed by [`DashboardScreen`] (the wallet workspace). */
public object DashboardTags {
    public const val SYNC: String = "dashboard.sync"
    /** Header line with the fiat equivalent and the sync status. */
    public const val SYNC_STATUS: String = "dashboard.sync-status"
    public const val BALANCE: String = "dashboard.balance"
    public const val WALLET_NAME: String = "dashboard.wallet-name"
    /** "Contacts" header action (was the Address book page button). */
    public const val ADDRESS_BOOK: String = "dashboard.address-book"
    /** "Lock" header action (was Logout). */
    public const val LOGOUT: String = "dashboard.logout"
    public const val RESTORE_BANNER: String = "dashboard.restore-banner"
    /** Phase 6.0 — one-shot Tracker pairing banner on signing dashboards. */
    public const val TRACKER_BANNER: String = "dashboard.tracker-banner"
    public const val TRACKER_BANNER_ADD: String = "dashboard.tracker-banner.add"
    public const val TRACKER_BANNER_SKIP: String = "dashboard.tracker-banner.skip"

    /** Sub-tab of the wallet workspace. */
    public fun tab(tab: WalletTab): String = "dashboard.tab.${tab.wire}"
}
