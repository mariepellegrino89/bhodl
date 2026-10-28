package io.bhodl.desktop.screens
import io.bhodl.desktop.domain.applyRules
import io.bhodl.desktop.domain.RecurringCandidate
import io.bhodl.desktop.domain.dayOfMonth
import io.bhodl.desktop.domain.manualAssetsByKindSat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.data.TrackerCache
import io.bhodl.desktop.navigation.TrackerSection
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.ui.bhodlCard
import io.bhodl.desktop.ui.displayAmount
import io.bhodl.desktop.wizard.RegistryStore
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.ffi.WalletKindFfi
import io.bhodl.ffi.WalletRecordFfi
import io.bhodl.ui.theme.BhodlTheme
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.tracker_refresh_balances
import io.bhodl.shared.resources.tracker_refreshing
import io.bhodl.shared.resources.trk_wallet_excluded
import io.bhodl.shared.resources.trk_wallet_included
import io.bhodl.shared.resources.trk_wallet_kind_watch_addresses
import io.bhodl.shared.resources.trk_wallet_kind_watch_hd
import io.bhodl.shared.resources.trk_wallet_kind_watch_xpub
import io.bhodl.shared.resources.trk_wallets_across_many
import io.bhodl.shared.resources.trk_wallets_across_one
import io.bhodl.shared.resources.trk_wallets_total_nw
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun TotalBalanceCard(
    total: ULong,
    walletCount: Int,
    refreshing: Boolean,
    onRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .bhodlCard()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.trk_wallets_total_nw),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textSecondary,
        )
        Text(
            text = displayAmount(total),
            style = BhodlTheme.typography.headlineMedium,
            color = BhodlTheme.colors.textPrimary,
        )
        Text(
            text = if (walletCount == 1) {
                stringResource(Res.string.trk_wallets_across_one)
            } else {
                stringResource(Res.string.trk_wallets_across_many, walletCount)
            },
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textTertiary,
        )
        OutlinedButton(
            onClick = onRefresh,
            enabled = !refreshing,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TrackingTags.REFRESH),
        ) {
            Text(stringResource(if (refreshing) Res.string.tracker_refreshing else Res.string.tracker_refresh_balances))
        }
    }
}

/**
 * One logical wallet of the tracker. The same xpub may live across
 * several registry rows (e.g. a former signing wallet's mirror plus a
 * later manual add); from the tracker's viewpoint they are ONE wallet.
 */
internal data class GroupedWallet(
    val displayLabel: String,
    val kindLabel: String,
    val network: String,
    val balanceSat: ULong?,
    /** UUIDs of every registry row that collapsed into this logical
     *  wallet, so tabular views aggregate `PaymentEvent`s by group
     *  instead of by raw registry row (no duplicate rows). */
    val memberIds: Set<String>,
    /** UX review 2026-10 (rec 3) — localized form of [kindLabel] for display
     *  ([kindDisplay]); [kindLabel] stays the stable English fallback. */
    val kindLabelRes: StringResource? = null,
)

/**
 * Group registry rows by their account xpub so duplicates render as a
 * single tracker row; the first row's label, kind and balance win.
 * Address-list watch wallets dedup by id only (they have no xpub).
 */
internal fun groupWalletsByXpub(
    wallets: List<WalletRecordFfi>,
    watchBalances: Map<String, ULong>,
): List<GroupedWallet> {
    val groups = wallets.groupBy { record ->
        if (record.kind == WalletKindFfi.WATCH_ADDRESSES) {
            record.id
        } else {
            io.bhodl.ffi.extractAccountXpub(record.descriptorPub) ?: record.id
        }
    }
    return groups.values.map { members ->
        val w = members.first()
        GroupedWallet(
            displayLabel = w.label,
            kindLabel = watchKindLabel(w.kind),
            kindLabelRes = watchKindLabelRes(w.kind),
            network = w.network,
            balanceSat = members.firstNotNullOfOrNull { watchBalances[it.id] },
            memberIds = members.map { it.id }.toSet(),
        )
    }
}

private fun watchKindLabelRes(kind: WalletKindFfi): StringResource = when (kind) {
    WalletKindFfi.WATCH_XPUB -> Res.string.trk_wallet_kind_watch_xpub
    WalletKindFfi.WATCH_ADDRESSES -> Res.string.trk_wallet_kind_watch_addresses
    WalletKindFfi.WATCH_HD -> Res.string.trk_wallet_kind_watch_hd
}

/** Localized wallet kind for display, falling back to [GroupedWallet.kindLabel]. */
internal val GroupedWallet.kindDisplay: String
    @Composable get() = kindLabelRes?.let { stringResource(it) } ?: kindLabel

private fun watchKindLabel(kind: WalletKindFfi): String = when (kind) {
    WalletKindFfi.WATCH_XPUB -> "Watch (xpub)"
    WalletKindFfi.WATCH_ADDRESSES -> "Watch (addresses)"
    WalletKindFfi.WATCH_HD -> "Watch (HD)"
}

@Composable
internal fun GroupedWalletRow(
    group: GroupedWallet,
    included: Boolean = true,
    onToggleInclude: () -> Unit = {},
) {
    // Phase 3.14 — deterministic wallet color from the Okabe-Ito
    // categorical palette. Hash the sorted member-id list so the
    // same wallet keeps the same colour across recompositions and
    // across app restarts. Not persisted in the registry — pure
    // function of the wallet's identity.
    val palette = BhodlTheme.colors.chartCategorical
    val walletColor = palette[
        (kotlin.math.abs(group.memberIds.sorted().joinToString("").hashCode())) % palette.size,
    ]
    val backgroundAlpha = if (included) 1f else 0.4f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(BhodlTheme.shapes.hero)
            .background(BhodlTheme.colors.surface.copy(alpha = backgroundAlpha))
            .border(1.dp, BhodlTheme.colors.outline, BhodlTheme.shapes.hero)
            .padding(end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Phase 3.14.b — vertical color band that grows with the row.
        // `IntrinsicSize.Min` on the Row + `fillMaxHeight()` on the
        // band makes the stripe match whatever the tallest child is
        // (text, balance). Replaces the fixed 40dp from 3.14.
        Box(
            modifier = Modifier
                .background(walletColor)
                .width(4.dp)
                .fillMaxHeight(),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = group.displayLabel,
                style = BhodlTheme.typography.titleMedium,
                color = BhodlTheme.colors.textPrimary,
            )
            Text(
                text = "${group.kindDisplay} · ${group.network}",
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textTertiary,
            )
        }
        Text(
            text = group.balanceSat?.let { displayAmount(it) } ?: "—",
            style = BhodlTheme.typography.titleMedium,
            color = if (included) BhodlTheme.colors.textPrimary
            else BhodlTheme.colors.textTertiary,
        )
        // Include / exclude toggle — small eye-style chip on the
        // trailing edge. Tap to flip the wallet's contribution to
        // cross-wallet totals (KPI / charts / Reports), and to flip
        // the row's de-emphasised appearance.
        Box(
            modifier = Modifier
                .padding(start = 8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (included) BhodlTheme.colors.surfaceVariant
                    else BhodlTheme.colors.financialPending.copy(alpha = 0.18f),
                )
                .clickable(onClick = onToggleInclude)
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .testTag(TrackingTags.WALLET_INCLUDE_TOGGLE),
        ) {
            Text(
                text = stringResource(if (included) Res.string.trk_wallet_included else Res.string.trk_wallet_excluded),
                style = BhodlTheme.typography.labelMedium,
                color = if (included) BhodlTheme.colors.textSecondary
                else BhodlTheme.colors.financialPending,
            )
        }
    }
}
