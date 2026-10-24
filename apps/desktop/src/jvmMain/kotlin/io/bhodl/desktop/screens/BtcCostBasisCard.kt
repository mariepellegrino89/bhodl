package io.bhodl.desktop.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.data.BtcCostBasisRates
import io.bhodl.desktop.data.TrackerCache
import io.bhodl.desktop.domain.BtcCostBasisRow
import io.bhodl.desktop.domain.BtcCostBasisSummary
import io.bhodl.desktop.domain.CostBasisPriceSource
import io.bhodl.desktop.domain.CostBasisStatus
import io.bhodl.desktop.domain.computeBtcCostBasis
import io.bhodl.desktop.domain.costBasisDays
import io.bhodl.desktop.domain.utcDay
import io.bhodl.desktop.prices.FiatCurrency
import io.bhodl.desktop.prices.PriceCache
import io.bhodl.desktop.settings.EsploraEndpoint
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.ui.amountSign
import io.bhodl.desktop.ui.displayAmount
import io.bhodl.desktop.ui.formatFiatWithCode
import io.bhodl.desktop.ui.money.amountsMasked
import io.bhodl.desktop.ui.signedAmountColor
import io.bhodl.ffi.Network
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.WalletRecordFfi
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.trp_cb_avg_price
import io.bhodl.shared.resources.trp_cb_col_price
import io.bhodl.shared.resources.trp_cb_cost
import io.bhodl.shared.resources.trp_cb_dev_test_networks
import io.bhodl.shared.resources.trp_cb_disclaimer
import io.bhodl.shared.resources.trp_cb_empty
import io.bhodl.shared.resources.trp_cb_excluded
import io.bhodl.shared.resources.trp_cb_held
import io.bhodl.shared.resources.trp_cb_hide_txs
import io.bhodl.shared.resources.trp_cb_in
import io.bhodl.shared.resources.trp_cb_loading_rates
import io.bhodl.shared.resources.trp_cb_missing_price
import io.bhodl.shared.resources.trp_cb_no_currency
import io.bhodl.shared.resources.trp_cb_out
import io.bhodl.shared.resources.trp_cb_price_market
import io.bhodl.shared.resources.trp_cb_price_user
import io.bhodl.shared.resources.trp_cb_realized
import io.bhodl.shared.resources.trp_cb_show_txs
import io.bhodl.shared.resources.trp_cb_status_excluded
import io.bhodl.shared.resources.trp_cb_status_missing
import io.bhodl.shared.resources.trp_cb_status_pending
import io.bhodl.shared.resources.trp_cb_status_self
import io.bhodl.shared.resources.trp_cb_test_networks_only
import io.bhodl.shared.resources.trp_cb_test_networks_skipped
import io.bhodl.shared.resources.trp_cb_title
import io.bhodl.shared.resources.trp_cb_uncovered
import io.bhodl.shared.resources.trp_cb_unrealized
import io.bhodl.shared.resources.trp_cb_value_now
import io.bhodl.ui.theme.BhodlTheme
import org.jetbrains.compose.resources.stringResource

/** Test tags for [BtcCostBasisCard]. */
internal object BtcCostBasisTags {
    const val CARD: String = "portfolio.costbasis.card"
    const val AVG_PRICE: String = "portfolio.costbasis.avg"
    const val UNREALIZED: String = "portfolio.costbasis.unrealized"
    const val TOGGLE_TXS: String = "portfolio.costbasis.toggle"
    const val ROW_PREFIX: String = "portfolio.costbasis.row."
}

/**
 * Dev-only switch (set by `./gradlew run`, see `apps/desktop/build.gradle.kts`):
 * count test-network wallets too, at the real BTC price, so the view can be
 * tried with signet funds. Never set in packaged builds.
 */
internal val costBasisIncludesTestNetworks: Boolean
    get() = System.getProperty("bhodl.costBasis.includeTestNetworks") == "true"

/**
 * Wallets the average-cost view counts: mainnet only (test-network coins
 * have no market price, unless [includeTestNetworks]) and not excluded
 * from the tracker by the user.
 */
internal fun costBasisWalletIds(
    wallets: List<WalletRecordFfi>,
    excludedWalletIds: Set<String>,
    includeTestNetworks: Boolean = costBasisIncludesTestNetworks,
): Set<String> = wallets
    .filter { it.id !in excludedWalletIds }
    .filter { includeTestNetworks || EsploraEndpoint.networkOf(it.network) == Network.MAINNET }
    .mapTo(mutableSetOf()) { it.id }

/**
 * Portfolio — BTC average cost of the tracked wallets (see
 * [computeBtcCostBasis]). Reads the per-wallet legs and the user's
 * per-transaction prices from [TrackerCache], the daily market rates
 * from [BtcCostBasisRates] and the live price from [PriceCache]; a row
 * click opens the transaction editor, where the price can be changed.
 */
@Composable
internal fun BtcCostBasisCard(
    wallets: List<WalletRecordFfi>,
    excludedWalletIds: Set<String>,
    onEditEvent: (PaymentEvent) -> Unit,
) {
    val fiat by SettingsStore.fiatCurrency
    val allLegs by TrackerCache.eventLegs
    val inputs by TrackerCache.costBasisInputs
    val rates by BtcCostBasisRates.rates
    val loadingRates by BtcCostBasisRates.loading
    val livePrice by PriceCache.price
    BtcCostBasisCardContent(
        wallets = wallets,
        excludedWalletIds = excludedWalletIds,
        fiat = fiat,
        allLegs = allLegs,
        inputs = inputs,
        rates = rates,
        loadingRates = loadingRates,
        livePrice = livePrice,
        onEditEvent = onEditEvent,
    )
}

/** Stateless body of [BtcCostBasisCard] (PRT-147 — renderable in tests). */
@Composable
internal fun BtcCostBasisCardContent(
    wallets: List<WalletRecordFfi>,
    excludedWalletIds: Set<String>,
    fiat: FiatCurrency,
    allLegs: List<PaymentEvent>,
    inputs: Map<String, io.bhodl.ffi.EventCostBasisFfi>,
    rates: Map<Pair<Long, String>, Double>,
    loadingRates: Boolean,
    livePrice: Double?,
    onEditEvent: (PaymentEvent) -> Unit,
    ensureRates: suspend (days: Set<Long>, currency: String) -> Unit = BtcCostBasisRates::ensure,
) {
    val walletIds = remember(wallets, excludedWalletIds) { costBasisWalletIds(wallets, excludedWalletIds) }
    val legs = remember(allLegs, walletIds) { allLegs.filter { it.walletId in walletIds } }
    // Test-network wallets the user tracks: their coins have no market
    // price, so they are left out — say so instead of "no transactions".
    val testWalletCount = remember(wallets, excludedWalletIds, walletIds) {
        wallets.count { it.id !in excludedWalletIds && it.id !in walletIds }
    }
    val currency = fiat.code
    val days = remember(legs) { costBasisDays(legs) }
    LaunchedEffect(days, currency) {
        if (fiat != FiatCurrency.NONE) ensureRates(days, currency)
    }
    val summary = remember(legs, inputs, rates, currency, livePrice) {
        computeBtcCostBasis(
            legs = legs,
            inputs = inputs,
            currency = currency,
            marketPriceAt = { day -> rates[day to currency] },
            currentPrice = livePrice,
        )
    }

    CostBasisPanel {
        when {
            fiat == FiatCurrency.NONE -> CostBasisNote(stringResource(Res.string.trp_cb_no_currency))
            summary.rows.isEmpty() && testWalletCount > 0 ->
                CostBasisNote(stringResource(Res.string.trp_cb_test_networks_only, testWalletCount))
            summary.rows.isEmpty() -> CostBasisNote(stringResource(Res.string.trp_cb_empty))
            else -> CostBasisBody(
                summary = summary,
                currency = currency,
                loadingRates = loadingRates,
                testWalletCount = testWalletCount,
                onEditEvent = onEditEvent,
            )
        }
    }
}

@Composable
private fun CostBasisPanel(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(BhodlTheme.shapes.panel)
            .background(BhodlTheme.colors.surface)
            .border(1.dp, BhodlTheme.colors.outline, BhodlTheme.shapes.panel)
            .testTag(BtcCostBasisTags.CARD),
    ) {
        Text(
            text = stringResource(Res.string.trp_cb_title),
            style = BhodlTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = BhodlTheme.colors.textSecondary,
            modifier = Modifier
                .fillMaxWidth()
                .background(BhodlTheme.colors.surfaceVariant)
                .padding(horizontal = BhodlTheme.spacing.md, vertical = BhodlTheme.spacing.sm),
        )
        content()
    }
}

@Composable
private fun CostBasisNote(text: String) {
    Text(
        text = text,
        style = BhodlTheme.typography.bodyMedium,
        color = BhodlTheme.colors.textTertiary,
        modifier = Modifier.fillMaxWidth().padding(BhodlTheme.spacing.md),
    )
}

@Composable
private fun CostBasisBody(
    summary: BtcCostBasisSummary,
    currency: String,
    loadingRates: Boolean,
    testWalletCount: Int,
    onEditEvent: (PaymentEvent) -> Unit,
) {
    var showTxs by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(BhodlTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(BhodlTheme.spacing.sm),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(BhodlTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(BhodlTheme.spacing.sm),
        ) {
            Kpi(stringResource(Res.string.trp_cb_held), displayAmount(summary.heldSat))
            Kpi(
                stringResource(Res.string.trp_cb_avg_price),
                summary.averagePrice?.let { formatFiatWithCode(it, currency) } ?: "—",
                tag = BtcCostBasisTags.AVG_PRICE,
            )
            Kpi(stringResource(Res.string.trp_cb_cost), formatFiatWithCode(summary.costFiat, currency))
            Kpi(
                stringResource(Res.string.trp_cb_value_now),
                summary.currentValueFiat?.let { formatFiatWithCode(it, currency) } ?: "—",
            )
            Kpi(
                stringResource(Res.string.trp_cb_unrealized),
                summary.unrealizedFiat?.let { pnl ->
                    signedFiat(pnl, currency) + signedPercentSuffix(summary.unrealizedRatio)
                } ?: "—",
                color = summary.unrealizedFiat?.let { pnlColor(it) },
                tag = BtcCostBasisTags.UNREALIZED,
            )
            Kpi(
                stringResource(Res.string.trp_cb_realized),
                signedFiat(summary.realizedFiat, currency),
                color = pnlColor(summary.realizedFiat),
            )
        }

        val notes = buildList {
            if (loadingRates) add(stringResource(Res.string.trp_cb_loading_rates))
            if (costBasisIncludesTestNetworks) add(stringResource(Res.string.trp_cb_dev_test_networks))
            if (testWalletCount > 0) add(stringResource(Res.string.trp_cb_test_networks_skipped, testWalletCount))
            if (summary.missingPriceCount > 0) {
                add(stringResource(Res.string.trp_cb_missing_price, summary.missingPriceCount))
            }
            if (summary.excludedCount > 0) {
                add(stringResource(Res.string.trp_cb_excluded, summary.excludedCount))
            }
            if (summary.uncoveredOutflowSat > 0) {
                add(stringResource(Res.string.trp_cb_uncovered, displayAmount(summary.uncoveredOutflowSat)))
            }
            add(stringResource(Res.string.trp_cb_disclaimer))
        }
        notes.forEach { note ->
            Text(
                text = note,
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.textTertiary,
            )
        }

        Text(
            text = stringResource(
                if (showTxs) Res.string.trp_cb_hide_txs else Res.string.trp_cb_show_txs,
                summary.rows.size,
            ),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.accentNeon,
            modifier = Modifier
                .clip(BhodlTheme.shapes.panel)
                .clickable { showTxs = !showTxs }
                .padding(vertical = 4.dp)
                .testTag(BtcCostBasisTags.TOGGLE_TXS),
        )
        if (showTxs) {
            summary.rows.forEach { row ->
                CostBasisTxRow(row = row, currency = currency, onClick = { onEditEvent(row.event) })
            }
        }
    }
}

@Composable
private fun Kpi(label: String, value: String, color: Color? = null, tag: String? = null) {
    Column(modifier = Modifier.widthIn(min = 140.dp)) {
        Text(
            text = label,
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textTertiary,
        )
        Text(
            text = value,
            style = BhodlTheme.typography.titleMedium,
            color = color ?: BhodlTheme.colors.textPrimary,
            modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
        )
    }
}

@Composable
private fun CostBasisTxRow(row: BtcCostBasisRow, currency: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(BhodlTheme.shapes.panel)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 4.dp)
            .testTag(BtcCostBasisTags.ROW_PREFIX + row.event.txidOrPaymentId),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BhodlTheme.spacing.md),
    ) {
        Text(
            text = row.timestampUnix?.let { formatDay(it) } ?: "—",
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textSecondary,
            modifier = Modifier.widthIn(min = 90.dp),
        )
        Text(
            text = stringResource(if (row.incoming) Res.string.trp_cb_in else Res.string.trp_cb_out),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textSecondary,
            modifier = Modifier.widthIn(min = 60.dp),
        )
        Text(
            text = displayAmount(row.amountSat),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = row.price?.let {
                stringResource(Res.string.trp_cb_col_price, formatFiatWithCode(it, currency))
            } ?: "—",
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textPrimary,
        )
        Text(
            text = statusLabel(row),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textTertiary,
            modifier = Modifier.widthIn(min = 110.dp),
        )
    }
}

@Composable
private fun statusLabel(row: BtcCostBasisRow): String = when (row.status) {
    CostBasisStatus.COUNTED -> stringResource(
        if (row.priceSource == CostBasisPriceSource.USER) Res.string.trp_cb_price_user else Res.string.trp_cb_price_market,
    )
    CostBasisStatus.EXCLUDED -> stringResource(Res.string.trp_cb_status_excluded)
    CostBasisStatus.SEND_TO_SELF -> stringResource(Res.string.trp_cb_status_self)
    CostBasisStatus.MISSING_PRICE -> stringResource(Res.string.trp_cb_status_missing)
    CostBasisStatus.PENDING -> stringResource(Res.string.trp_cb_status_pending)
}

/** Gain / loss colour; neutral in privacy mode (see [signedAmountColor]). */
@Composable
private fun pnlColor(value: Double): Color = signedAmountColor(
    value = toCents(value),
    positive = BhodlTheme.colors.financialPositive,
    negative = BhodlTheme.colors.financialNegative,
    neutral = BhodlTheme.colors.textPrimary,
)

/** Signed fiat amount; no sign in privacy mode (see [amountSign]). */
@Composable
private fun signedFiat(value: Double, currency: String): String {
    val rounded = toCents(value)
    return amountSign(rounded, masked = amountsMasked()) + formatFiatWithCode(kotlin.math.abs(rounded), currency)
}

/** " (+12.3%)" after the unrealized figure; hidden in privacy mode. */
@Composable
private fun signedPercentSuffix(ratio: Double?): String {
    if (ratio == null || amountsMasked()) return ""
    val pct = kotlin.math.round(ratio * 1000.0) / 10.0
    val number = String.format(java.util.Locale.getDefault(), "%.1f%%", kotlin.math.abs(pct))
    return " (${amountSign(pct, masked = false)}$number)"
}

private fun toCents(value: Double): Double = kotlin.math.round(value * 100.0) / 100.0

private fun formatDay(unix: Long): String =
    java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
        .withZone(java.time.ZoneOffset.UTC)
        .format(java.time.Instant.ofEpochSecond(utcDay(unix)))
