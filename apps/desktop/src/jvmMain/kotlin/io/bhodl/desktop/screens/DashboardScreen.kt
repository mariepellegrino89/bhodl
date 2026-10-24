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
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.wallet_header_watch
import io.bhodl.shared.resources.wview_unknown_error
import io.bhodl.shared.resources.wallet_action_contacts
import io.bhodl.shared.resources.wallet_action_lock
import io.bhodl.shared.resources.wallet_header_balance
import io.bhodl.shared.resources.wallet_not_synced
import io.bhodl.shared.resources.wallet_sync
import io.bhodl.shared.resources.wallet_sync_failed
import io.bhodl.shared.resources.wallet_synced_at
import io.bhodl.shared.resources.wallet_syncing
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
 * The wallet workspace (UX review 2026-10, §4 items 5–8) for the
 * watch-only wallet held by [WalletStore].
 *
 * The tracker's dense, full-width language with Sparrow's IA: a wallet
 * header (name, balance in the user's unit + operational fiat, sync
 * status) above sub-tabs **Transactions · Receive · Addresses · UTXOs ·
 * Wallet settings**. Address-list wallets cannot derive addresses, so
 * they have no Receive tab. "Contacts" and "Lock" are header actions.
 *
 * Sync model: one automatic background sync per entry when [SyncCache]
 * is stale, plus the header's manual Sync. Tabs never sync on their own
 * inside the workspace; they re-read local state when [dataVersion]
 * moves.
 */
@Composable
public fun DashboardScreen(
    initialTab: WalletTab = WalletTab.TRANSACTIONS,
    onAddressBookClicked: () -> Unit = {},
    onLockClicked: () -> Unit = {},
    /** The wallet was removed from Wallet settings. */
    onWalletRemoved: () -> Unit = {},
    /** UX review 2026-10 (item 14) — footer shield → Security Hub. */
    onOpenSecurityHub: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()

    val wallet = remember { WalletStore.require() }
    val addressList = remember(wallet) { runCatching { wallet.isAddressList() }.getOrDefault(false) }
    val tabs = remember(addressList) {
        WalletTab.entries.filter { !(addressList && it == WalletTab.RECEIVE) }
    }

    var tabWire by rememberSaveable { mutableStateOf(initialTab.wire) }
    val tab = WalletTab.fromWire(tabWire).takeIf { it in tabs } ?: WalletTab.TRANSACTIONS

    // Optimistic balance from the cache so re-entry never flashes "—".
    var balanceSat by remember { mutableStateOf(SyncCache.lastBalanceSat()) }
    var lastSyncAtMs by remember { mutableStateOf(SyncCache.lastSyncAtMs()) }
    var syncMode by remember { mutableStateOf(SyncMode.Idle) }
    var lastError: String? by remember { mutableStateOf(null) }
    var tipHeight by remember { mutableStateOf<UInt?>(null) }
    // Bumped after every sync / local mutation: tabs re-read on change.
    var dataVersion by remember { mutableIntStateOf(0) }
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

    // Background sync on entry when the cache is stale.
    LaunchedEffect(wallet) {
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
            walletName = walletName ?: WalletStore.activeWalletLabel()
                ?: stringResource(Res.string.wallet_header_watch),
            kindLabel = stringResource(Res.string.wallet_header_watch),
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

        if (lastError != null && syncMode != SyncMode.Foreground) {
            ErrorCard(
                title = stringResource(Res.string.wallet_sync_failed),
                message = prettifyNetworkError(lastError),
                details = lastError,
                onRetry = { launchSync(SyncMode.Foreground) },
            )
        }
        WalletTabStrip(selected = tab, onSelect = { tabWire = it.wire }, tabs = tabs)

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                WalletTab.TRANSACTIONS -> HistoryScreen(
                    onBack = null,
                    syncOnEnter = false,
                    refreshKey = dataVersion,
                    tipHeight = tipHeight,
                )
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
                WalletTab.UTXOS -> WalletUtxosTab(refreshKey = dataVersion)
                WalletTab.SETTINGS -> WalletSettingsTab(onWalletRemoved = onWalletRemoved)
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
 * Wallet header of the workspace: name + kind + network on the left, balance (unit-aware) with the
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
 * are mirrored into the tracker registry under this wallet's id, which
 * is what the tracker reads. Best-effort: a failure never fails the
 * sync. Runs on the caller's IO dispatcher.
 */
internal fun applyReceiveLabels(wallet: WalletPort) {
    val copies = runCatching { wallet.applyReceiveLabels() }.getOrDefault(emptyList())
    val registry = RegistryStore.current() ?: return
    val walletId = WalletStore.activeWalletId() ?: return
    var changed = false
    for (copy in copies) {
        val category = copy.category ?: continue
        changed = runCatching { registry.setEventCategory(walletId, copy.txid, category) }.isSuccess || changed
    }
    if (changed) RegistryStore.bumpDataVersion()
}

/**
 * The workspace's sync state: idle, a background (entry) sync that
 * leaves every control usable, or a user-requested foreground sync.
 */
internal enum class SyncMode { Idle, Background, Foreground }

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

    /** Sub-tab of the wallet workspace. */
    public fun tab(tab: WalletTab): String = "dashboard.tab.${tab.wire}"
}
