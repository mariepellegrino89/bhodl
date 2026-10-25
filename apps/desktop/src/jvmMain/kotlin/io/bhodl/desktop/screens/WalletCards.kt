package io.bhodl.desktop.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.data.TrackerCache
import io.bhodl.desktop.ui.HelpTip
import io.bhodl.desktop.ui.bhodlCard
import io.bhodl.desktop.ui.displayAmount
import io.bhodl.ffi.PaymentDirection
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.UtxoRecord
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.wlc_add
import io.bhodl.shared.resources.wlc_coin_confirmed
import io.bhodl.shared.resources.wlc_coin_map
import io.bhodl.shared.resources.wlc_coin_map_help
import io.bhodl.shared.resources.wlc_coin_pending
import io.bhodl.shared.resources.wlc_kind_addresses
import io.bhodl.shared.resources.wlc_kind_xpub
import io.bhodl.shared.resources.wlc_stale
import io.bhodl.shared.resources.wlc_stale_help
import io.bhodl.shared.resources.wlc_synced
import io.bhodl.ui.theme.BhodlTheme
import org.jetbrains.compose.resources.stringResource

/*
 * Wallet redesign 2026-10 (mix of proposals A and C) — the wallets as
 * account cards above the workspace (always visible, one click to
 * switch) and the UTXO coin map.
 */

internal object WalletCardsTags {
    const val STRIP: String = "wallet-cards.strip"
    fun card(id: String): String = "wallet-cards.card.$id"
    const val ADD: String = "wallet-cards.add"
    const val COIN_MAP: String = "wallet-cards.coin-map"
}

/**
 * Daily balance of one wallet over the last [days] days, rebuilt
 * backwards from [balanceSat] and the wallet's own payments (unconfirmed
 * ones count as today).
 */
internal fun walletBalanceSeries(
    walletId: String,
    balanceSat: Long,
    events: List<PaymentEvent>,
    nowUnix: Long,
    days: Int = 30,
): List<Long> {
    val own = events.filter { it.walletId == walletId }.distinctBy { it.txidOrPaymentId }
    fun signed(e: PaymentEvent): Long = when (e.direction) {
        PaymentDirection.INCOMING -> e.amountSat.toLong()
        PaymentDirection.OUTGOING -> -e.amountSat.toLong()
        PaymentDirection.SELF_TRANSFER -> -(e.feeSat?.toLong() ?: 0L)
    }
    return (days downTo 0).map { d ->
        val t = nowUnix - d * 86_400L
        val after = own.filter { (it.timestampUnix?.toLong() ?: Long.MAX_VALUE) > t }.sumOf { signed(it) }
        balanceSat - after
    }
}

/** The tracked wallets as cards; [currentId] is highlighted, a click opens another. */
@Composable
internal fun WalletCardsStrip(
    currentId: String?,
    onOpenWallet: (String) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
    nowUnix: Long = System.currentTimeMillis() / 1000L,
) {
    val wallets by TrackerCache.wallets
    val balances by TrackerCache.watchBalances
    val stale by TrackerCache.staleWalletIds
    val events by TrackerCache.events
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag(WalletCardsTags.STRIP),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        wallets.forEach { w ->
            val balance = balances[w.id]?.toLong() ?: 0L
            val on = w.id == currentId
            val isStale = w.id in stale
            val series = walletBalanceSeries(w.id, balance, events, nowUnix)
            Column(
                modifier = Modifier
                    .width(280.dp)
                    .bhodlCard()
                    .then(if (on) Modifier.border(1.5.dp, BhodlTheme.colors.brandPrimary, RoundedCornerShape(16.dp)) else Modifier)
                    .clickable(role = Role.Tab) { if (!on) onOpenWallet(w.id) }
                    .semantics { selected = on }
                    .padding(14.dp)
                    .testTag(WalletCardsTags.card(w.id)),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(w.label, style = BhodlTheme.typography.titleMedium, color = BhodlTheme.colors.textPrimary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(if (isStale) Res.string.wlc_stale else Res.string.wlc_synced),
                        style = BhodlTheme.typography.labelMedium,
                        color = if (isStale) BhodlTheme.colors.financialPending else BhodlTheme.colors.financialPositive,
                    )
                    if (isStale) HelpTip(stringResource(Res.string.wlc_stale_help))
                }
                Text("${walletKindLabel(w.kind)} · ${w.network}", style = BhodlTheme.typography.labelMedium, color = BhodlTheme.colors.textTertiary)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(displayAmount(balance), style = BhodlTheme.typography.amountMedium, color = BhodlTheme.colors.textPrimary, modifier = Modifier.weight(1f))
                    if (series.distinct().size > 1) {
                        Sparkline(values = series.map { it.toFloat() }, color = BhodlTheme.colors.brandPrimary, modifier = Modifier.width(90.dp).height(32.dp))
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .width(160.dp)
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, BhodlTheme.colors.outline, RoundedCornerShape(16.dp))
                .clickable(role = Role.Button, onClick = onAdd)
                .padding(14.dp)
                .testTag(WalletCardsTags.ADD),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(Res.string.wlc_add), style = BhodlTheme.typography.bodyMedium, color = BhodlTheme.colors.brandPrimary)
        }
    }
}

@Composable
private fun walletKindLabel(kind: io.bhodl.ffi.WalletKindFfi): String = when (kind) {
    io.bhodl.ffi.WalletKindFfi.WATCH_ADDRESSES -> stringResource(Res.string.wlc_kind_addresses)
    else -> stringResource(Res.string.wlc_kind_xpub)
}

/**
 * The UTXOs as blocks sized by amount (largest first), confirmed vs
 * pending told apart by colour and a legend.
 */
@Composable
internal fun CoinMap(utxos: List<UtxoRecord>, modifier: Modifier = Modifier) {
    if (utxos.isEmpty()) return
    val total = utxos.sumOf { it.valueSat.toLong() }.coerceAtLeast(1L)
    val confirmedColor = BhodlTheme.colors.brandPrimary
    // Neutral, not amber: amber is too close to the brand orange.
    val pendingColor = BhodlTheme.colors.textTertiary
    Column(modifier.bhodlCard().padding(14.dp).testTag(WalletCardsTags.COIN_MAP), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(Res.string.wlc_coin_map).uppercase(), style = BhodlTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = BhodlTheme.colors.textSecondary)
            HelpTip(stringResource(Res.string.wlc_coin_map_help))
        }
        // One proportional row; tiny coins keep a minimum share so they stay visible.
        Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            utxos.sortedByDescending { it.valueSat }.forEach { u ->
                val share = (u.valueSat.toLong().toFloat() / total).coerceAtLeast(0.02f)
                val c: Color = if (u.confirmed) confirmedColor else pendingColor
                val amount = displayAmount(u.valueSat.toLong())
                Box(
                    Modifier
                        .weight(share)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(6.dp))
                        .background(c.copy(alpha = 0.25f))
                        .border(1.dp, c, RoundedCornerShape(6.dp))
                        .semantics { contentDescription = "$amount ${u.outpoint}" }
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (share >= 0.1f) Text(amount, style = BhodlTheme.typography.labelMedium, color = BhodlTheme.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Clip)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend(confirmedColor, stringResource(Res.string.wlc_coin_confirmed))
            Legend(pendingColor, stringResource(Res.string.wlc_coin_pending))
        }
    }
}

@Composable
private fun Legend(c: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(c))
        Text(label, style = BhodlTheme.typography.labelMedium, color = BhodlTheme.colors.textSecondary)
    }
}
