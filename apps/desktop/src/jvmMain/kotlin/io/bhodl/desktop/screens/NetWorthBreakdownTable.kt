package io.bhodl.desktop.screens
import androidx.compose.foundation.layout.heightIn
import io.bhodl.desktop.domain.manualAssetsByKindSat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.prices.FiatCurrency
import io.bhodl.desktop.prices.PriceCache
import io.bhodl.desktop.ui.ColumnAmountFormat
import io.bhodl.desktop.ui.rememberColumnAmountFormat
import io.bhodl.desktop.ui.formatFiatWithCurrency
import io.bhodl.desktop.ui.tableCell
import io.bhodl.ffi.TrackingLiabilityFfi
import io.bhodl.ui.theme.BhodlTheme
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.tracker_sheet_net_worth
import io.bhodl.shared.resources.trk_breakdown_col_category
import io.bhodl.shared.resources.trk_breakdown_col_value
import io.bhodl.shared.resources.trk_breakdown_col_weight
import io.bhodl.shared.resources.trk_breakdown_investments
import io.bhodl.shared.resources.trk_breakdown_kind_brokerage
import io.bhodl.shared.resources.trk_breakdown_kind_business
import io.bhodl.shared.resources.trk_breakdown_kind_cash
import io.bhodl.shared.resources.trk_breakdown_kind_collectible
import io.bhodl.shared.resources.trk_breakdown_kind_loans
import io.bhodl.shared.resources.trk_breakdown_kind_other
import io.bhodl.shared.resources.trk_breakdown_kind_real_estate
import io.bhodl.shared.resources.trk_breakdown_kind_vehicle
import io.bhodl.shared.resources.trk_breakdown_manual
import io.bhodl.shared.resources.trk_breakdown_wallets
import io.bhodl.shared.resources.trk_nw_slice_liabilities
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.absoluteValue

/**
 * Phase 7.1 — Kubera-style net-worth breakdown table.
 *
 * Dense panel-like table with header row + one row per asset / liability
 * category, terminating in a totals row. Numeric columns are
 * right-aligned and use the monospaced amount style so columns line up
 * regardless of value magnitude.
 *
 * Columns:
 *  - **Category** — left-aligned label.
 *  - **Value** — right-aligned amount in the user's fiat currency
 *    (with BTC fallback when fiat is disabled).
 *  - **Weight %** — right-aligned share of the gross asset side.
 *
 * Phase 7.3 — added the "Investments (fiat)" row. The row shows "—"
 * in the Value column when no BTC/fiat price has been cached yet
 * (`investmentsPricingAvailable = false`), so the user can tell the
 * difference between "you have no investments" and "investments
 * exist but the price oracle hasn't responded yet". Manual cash
 * categories still arrive later (Phase 7.5, per the
 * `docs/research-finance-tracking.md` roadmap).
 */
@Composable
public fun NetWorthBreakdownTable(
    walletAssetSat: ULong,
    /**
     * Phase 7.3 — aggregate fiat value of `tracking_investments`
     * converted to sat at the current BTC/fiat price. Pass 0 when
     * the user has no investments OR when [investmentsPricingAvailable]
     * is false; the row is suppressed in the first case and shown as
     * "—" in the second.
     */
    investmentsAssetSat: ULong,
    investmentsPricingAvailable: Boolean,
    /**
     * Phase 7.3 — non-zero when the user has at least one investment
     * row in the registry, even if the price isn't cached. Lets the
     * table render the "Investments" row with "—" rather than dropping
     * it entirely on cold start.
     */
    hasInvestments: Boolean,
    /**
     * Phase 7.4 — manual asset rows aggregated by `kind`. Each non-
     * empty entry becomes a breakdown row labelled per kind
     * ("Cash (fiat)", "Real estate", ...). Map comes from
     * [io.bhodl.desktop.data.TrackerCache.manualAssetsByKindSat].
     * Pricing availability reuses [investmentsPricingAvailable] — same
     * PriceCache path.
     */
    manualAssetsByKindSat: Map<String, ULong>,
    /**
     * Phase 7.4 — true when the user has at least one manual asset row,
     * even with 0 priced kinds. Lets the table render the "Cash (fiat)"
     * etc. row with "—" rather than hiding everything on cold start.
     */
    hasManualAssets: Boolean,
    liabilities: List<TrackingLiabilityFfi>,
    fiatCurrency: FiatCurrency,
    modifier: Modifier = Modifier,
) {
    val rows = buildList {
        add(
            BreakdownRow(
                category = stringResource(Res.string.trk_breakdown_wallets),
                valueSat = walletAssetSat,
                signedSat = walletAssetSat.toLong(),
                kind = BreakdownKind.ASSET,
                pricingUnavailable = false,
            ),
        )
        // Phase 7.3 — investments row. Shown when the user has any
        // investment registered, even if priced is "—" (cold start).
        // The kind=ASSET so weight % still includes it in the gross.
        if (hasInvestments) {
            add(
                BreakdownRow(
                    category = stringResource(Res.string.trk_breakdown_investments),
                    valueSat = investmentsAssetSat,
                    signedSat = investmentsAssetSat.toLong(),
                    kind = BreakdownKind.ASSET,
                    pricingUnavailable = !investmentsPricingAvailable,
                ),
            )
        }
        // Phase 7.4 — manual asset rows. Two cases:
        //  (a) PriceCache is hot → one row per `kind` with non-zero
        //      priced value, in canonical liquidity order (cash first,
        //      other last). A Kubera-style "balance sheet" sequence.
        //  (b) PriceCache is cold but the user has manual assets → a
        //      single placeholder row "Manual assets" with "—". We
        //      don't enumerate per-kind here because we don't know
        //      yet which kinds will have priced values.
        if (hasManualAssets && investmentsPricingAvailable) {
            for ((kindKey, label) in MANUAL_ASSET_KIND_BREAKDOWN_LABELS) {
                val sat = manualAssetsByKindSat[kindKey] ?: 0UL
                if (sat == 0UL) continue
                add(
                    BreakdownRow(
                        category = stringResource(label),
                        valueSat = sat,
                        signedSat = sat.toLong(),
                        kind = BreakdownKind.ASSET,
                        pricingUnavailable = false,
                    ),
                )
            }
        } else if (hasManualAssets) {
            // Cold-start placeholder: the user has assets but we
            // don't have a BTC/fiat price yet. One row, "—".
            add(
                BreakdownRow(
                    category = stringResource(Res.string.trk_breakdown_manual),
                    valueSat = 0UL,
                    signedSat = 0L,
                    kind = BreakdownKind.ASSET,
                    pricingUnavailable = true,
                ),
            )
        }
        val totalLiabilitySat = liabilities.sumOf { it.amountSat.toLong() }
        if (totalLiabilitySat > 0L) {
            add(
                BreakdownRow(
                    category = stringResource(Res.string.trk_nw_slice_liabilities),
                    valueSat = totalLiabilitySat.toULong(),
                    signedSat = -totalLiabilitySat,
                    kind = BreakdownKind.LIABILITY,
                    pricingUnavailable = false,
                ),
            )
        }
    }
    // Phase 7.3 — rows whose pricing isn't available contribute 0
    // to the gross so their "—" cell doesn't skew the weight column.
    val grossAssetSat = rows.filter {
        it.kind == BreakdownKind.ASSET && !it.pricingUnavailable
    }
        .sumOf { it.valueSat.toLong() }
        .coerceAtLeast(1L)

    // Item 17 — the share column gives way on a narrow window.
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val cols = NET_WORTH_BREAKDOWN_TABLE.visible(maxWidth.value.toInt())
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(BhodlTheme.shapes.panel)
                .background(BhodlTheme.colors.surface)
                .border(1.dp, BhodlTheme.colors.outline, BhodlTheme.shapes.panel)
                .testTag(NetWorthBreakdownTags.TABLE),
        ) {
            // UX review 2026-10 (rec 2) — Auto resolves to ONE unit for the
            // whole Value column (totals included), never sats next to BTC.
            val amounts = rememberColumnAmountFormat(rows.map { it.signedSat })
            BreakdownHeaderRow(cols)
            rows.forEachIndexed { idx, row ->
                BreakdownDataRow(
                    row = row,
                    // Phase 7.3 — weight is 0 (rendered as "—") for rows
                    // we couldn't price; otherwise share of the gross.
                    weightPct = if (row.pricingUnavailable) 0.0
                        else (row.valueSat.toDouble() / grossAssetSat) * 100.0,
                    fiatCurrency = fiatCurrency,
                    amounts = amounts,
                    cols = cols,
                    isZebra = idx % 2 == 1,
                )
            }
            // Totals row. Pricing-unavailable rows contribute 0 to the
            // net so the totals row stays honest about what we actually
            // know — never shows a number that includes a phantom asset.
            val netSat = rows
                .filter { !it.pricingUnavailable }
                .sumOf { it.signedSat }
            BreakdownTotalsRow(
                netSat = netSat,
                fiatCurrency = fiatCurrency,
                amounts = amounts,
                cols = cols,
            )
        }
    }
}

private enum class BreakdownKind { ASSET, LIABILITY }

private data class BreakdownRow(
    val category: String,
    val valueSat: ULong,
    val signedSat: Long,
    val kind: BreakdownKind,
    /**
     * Phase 7.3 — when true, the Value column renders "—" instead of
     * the formatted amount, and the row's contribution to the weight
     * % is 0 (so the rest of the breakdown isn't distorted by a row
     * whose value we don't know yet).
     */
    val pricingUnavailable: Boolean,
)

@Composable
private fun BreakdownHeaderRow(cols: List<NetWorthBreakdownCol>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BhodlTheme.colors.surfaceVariant)
            .padding(horizontal = BhodlTheme.spacing.md, vertical = BhodlTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (c in cols) {
            val label = when (c) {
                NetWorthBreakdownCol.CATEGORY -> Res.string.trk_breakdown_col_category
                NetWorthBreakdownCol.VALUE -> Res.string.trk_breakdown_col_value
                NetWorthBreakdownCol.SHARE -> Res.string.trk_breakdown_col_weight
            }
            Text(
                text = stringResource(label),
                modifier = Modifier.tableCell(this, c).testTag(NET_WORTH_BREAKDOWN_TABLE.headerTag(c)),
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.textSecondary,
                textAlign = if (c == NetWorthBreakdownCol.CATEGORY) TextAlign.Start else TextAlign.End,
            )
        }
    }
}

@Composable
private fun BreakdownDataRow(
    row: BreakdownRow,
    weightPct: Double,
    fiatCurrency: FiatCurrency,
    amounts: ColumnAmountFormat,
    cols: List<NetWorthBreakdownCol>,
    isZebra: Boolean,
) {
    val bg = if (isZebra) BhodlTheme.colors.rowZebra else androidx.compose.ui.graphics.Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Item 15 — Settings › Density row height.
            .heightIn(min = BhodlTheme.density.rowHeight)
            .background(bg)
            .padding(horizontal = BhodlTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.category,
            modifier = Modifier.tableCell(this, NetWorthBreakdownCol.CATEGORY),
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textPrimary,
        )
        Text(
            // Phase 7.3 — "—" placeholder when we couldn't price the
            // row yet (e.g. PriceCache hasn't returned a quote on
            // cold start). Distinguishes "value = 0" from "unknown".
            text = if (row.pricingUnavailable) "—"
                else formatAmount(row.signedSat, fiatCurrency, amounts),
            modifier = Modifier.tableCell(this, NetWorthBreakdownCol.VALUE),
            style = BhodlTheme.typography.amountSmall,
            color = when {
                row.pricingUnavailable -> BhodlTheme.colors.textTertiary
                row.kind == BreakdownKind.ASSET -> BhodlTheme.colors.textPrimary
                else -> BhodlTheme.colors.financialNegative
            },
            textAlign = TextAlign.End,
        )
        if (NetWorthBreakdownCol.SHARE in cols) {
            Text(
                text = if (row.pricingUnavailable) "—" else "%.1f%%".format(weightPct),
                modifier = Modifier.tableCell(this, NetWorthBreakdownCol.SHARE),
                style = BhodlTheme.typography.amountSmall,
                color = BhodlTheme.colors.textSecondary,
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
private fun BreakdownTotalsRow(
    netSat: Long,
    fiatCurrency: FiatCurrency,
    amounts: ColumnAmountFormat,
    cols: List<NetWorthBreakdownCol>,
) {
    val color = io.bhodl.desktop.ui.signedAmountColor(
        value = netSat.toDouble(),
        positive = BhodlTheme.colors.financialPositive,
        negative = BhodlTheme.colors.financialNegative,
        neutral = BhodlTheme.colors.textPrimary,
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(BhodlTheme.colors.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .padding(horizontal = BhodlTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.tracker_sheet_net_worth),
                modifier = Modifier.tableCell(this, NetWorthBreakdownCol.CATEGORY),
                style = BhodlTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = BhodlTheme.colors.textPrimary,
            )
            Text(
                text = formatAmount(netSat, fiatCurrency, amounts),
                modifier = Modifier.tableCell(this, NetWorthBreakdownCol.VALUE),
                style = BhodlTheme.typography.amountMedium,
                color = color,
                textAlign = TextAlign.End,
            )
            if (NetWorthBreakdownCol.SHARE in cols) {
                Text(
                    text = "100.0%",
                    modifier = Modifier.tableCell(this, NetWorthBreakdownCol.SHARE),
                    style = BhodlTheme.typography.amountSmall,
                    color = BhodlTheme.colors.textSecondary,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

/**
 * Pure formatter for a signed amount: fiat if a price is cached and
 * the currency is non-NONE, otherwise BTC fallback. Prefix `−` /
 * `+` makes the sign explicit (the colour also encodes it, but text
 * helps screen readers and copy-paste).
 */
private fun formatAmount(signedSat: Long, fiatCurrency: FiatCurrency, amounts: ColumnAmountFormat): String {
    val abs = signedSat.absoluteValue.toULong()
    val sign = io.bhodl.desktop.ui.amountSign(signedSat, plusForPositive = false)
    val fiatValue = PriceCache.satToFiat(abs)
    return if (fiatValue != null && fiatCurrency != FiatCurrency.NONE) {
        "$sign${formatFiatWithCurrency(fiatValue, fiatCurrency)}"
    } else {
        "$sign${amounts.full(abs.toLong())}"
    }
}

public object NetWorthBreakdownTags {
    public const val TABLE: String = "net-worth-breakdown.table"
}

/**
 * Phase 7.4 — canonical row labels for manual asset kinds, in
 * liquidity order (cash first, illiquid last). Kept in sync with the
 * `MANUAL_ASSET_KIND_LABELS` picker order in [ManualAssetsCard] but
 * uses longer labels ("Cash (fiat)", not "Cash") to disambiguate
 * from the BTC-only "Wallets (BTC)" row sitting above.
 */
private val MANUAL_ASSET_KIND_BREAKDOWN_LABELS: List<Pair<String, StringResource>> = listOf(
    "cash" to Res.string.trk_breakdown_kind_cash,
    "brokerage" to Res.string.trk_breakdown_kind_brokerage,
    "real_estate" to Res.string.trk_breakdown_kind_real_estate,
    "vehicle" to Res.string.trk_breakdown_kind_vehicle,
    "collectible" to Res.string.trk_breakdown_kind_collectible,
    "business_equity" to Res.string.trk_breakdown_kind_business,
    "loans_to_others" to Res.string.trk_breakdown_kind_loans,
    "other" to Res.string.trk_breakdown_kind_other,
)
