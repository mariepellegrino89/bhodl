package io.bhodl.desktop.screens

import io.bhodl.desktop.ui.rowHeightFor
import io.bhodl.desktop.ui.tableRowClickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.prices.FiatCurrency
import io.bhodl.desktop.prices.PriceCache
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.ui.ProvideColumnUnit
import io.bhodl.desktop.ui.SlideOverPanel
import io.bhodl.desktop.ui.formatFiatWithCode
import io.bhodl.desktop.ui.tableCell
import io.bhodl.desktop.ui.money.Money
import io.bhodl.desktop.ui.money.MoneyDensity
import io.bhodl.desktop.ui.money.MoneyPrimary
import io.bhodl.desktop.ui.money.MoneySemantic
import io.bhodl.desktop.ui.money.MoneyValue
import io.bhodl.ffi.TrackingInvestmentFfi
import io.bhodl.ffi.TrackingInvestmentLotFfi
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.tracker_investments_fiat_rows_only
import io.bhodl.ui.theme.BhodlTheme
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import kotlin.math.sign
import io.bhodl.shared.resources.common_no_rate
import io.bhodl.shared.resources.trp_add
import io.bhodl.shared.resources.trp_col_name
import io.bhodl.shared.resources.trp_col_type
import io.bhodl.shared.resources.trp_col_value
import io.bhodl.shared.resources.trp_inv_col_cost
import io.bhodl.shared.resources.trp_inv_col_pnl_btc
import io.bhodl.shared.resources.trp_inv_col_pnl_fiat
import io.bhodl.shared.resources.trp_inv_col_qty
import io.bhodl.shared.resources.trp_inv_empty
import io.bhodl.shared.resources.trp_inv_lots_many
import io.bhodl.shared.resources.trp_inv_lots_one
import io.bhodl.shared.resources.trp_inv_title
import io.bhodl.shared.resources.trp_total

/**
 * Investments card — Phase 7.1.1 rewrite.
 *
 * Phase 5.1 introduced this surface as a per-row list. The "too flat"
 * feedback (manual testing, 2026-05-19) pushed the redesign forward from Phase
 * 7.3 — the new layout is a Kubera-style dense table with header /
 * data rows / totals row and right-aligned monospace amounts so columns
 * line up regardless of magnitude.
 *
 * Columns:
 *  - **NAME** + ticker subtitle (weight 2)
 *  - **TYPE** badge — small colored pill per asset class (weight 0.7)
 *  - **QTY** right-aligned (weight 0.7)
 *  - **COST** right-aligned, currency-prefixed (weight 1)
 *  - **VALUE** right-aligned, currency-prefixed (weight 1)
 *  - **P&L %** right-aligned, colored by sign (weight 0.7)
 *
 * Mixed currencies: the totals row aggregates only the rows whose
 * `cost_basis_currency` matches the dashboard fiat — mixing a USD
 * stock with an EUR ETF in a single net P&L would be misleading
 * without forex. Off-currency rows still render their own numbers
 * inline; they just don't contribute to the totals.
 *
 * Tap a row to edit; "+ Add" opens [InvestmentEditDialog].
 */
@Composable
internal fun InvestmentsCard(
    investments: List<TrackingInvestmentFfi>,
    /**
     * Phase 7.8 — lots grouped by investment id. When a row has lots,
     * the aggregate cost basis + quantity come from these (legacy
     * single-row fields are ignored). Pass `emptyMap()` to disable
     * the multi-lot UI (useful for tests that still exercise the
     * Phase 5.1 view).
     */
    lotsByInvestmentId: Map<Long, List<TrackingInvestmentLotFfi>> = emptyMap(),
    /**
     * Phase 7.9 — sells grouped by parent investment id. Drives the
     * "Sells" sub-section inside the expansion AND the realized P&L
     * contribution.
     */
    sellsByInvestmentId: Map<Long, List<io.bhodl.ffi.TrackingInvestmentSellFfi>> = emptyMap(),
    /**
     * Phase 7.9 — disposals grouped by parent investment id (looked
     * up via sell_id). Used for the realized P&L total and for the
     * per-sell P&L when expanded.
     */
    disposalsByInvestmentId: Map<Long, List<io.bhodl.ffi.TrackingLotDisposalFfi>> = emptyMap(),
    onAdd: () -> Unit,
    onEdit: (TrackingInvestmentFfi) -> Unit,
    /**
     * Phase 7.8 — click "+ Add lot" inside an expanded row, OR click
     * an existing lot to edit it. The host opens [LotEditDialog]; the
     * `Pair<investmentId, lotOrNull>` carries the parent context.
     */
    onAddLot: (investmentId: Long) -> Unit = {},
    onEditLot: (TrackingInvestmentLotFfi) -> Unit = {},
    /** Phase 7.9 — "Sell" button in the expansion. */
    onSell: (investmentId: Long) -> Unit = {},
    /** Phase 7.9 — delete an existing sell from the expansion. */
    onDeleteSell: (io.bhodl.ffi.TrackingInvestmentSellFfi) -> Unit = {},
) {
    val dashboardFiat by SettingsStore.fiatCurrency
    // Item 17 — on a narrow window type, quantity, cost, then fiat P&L
    // give way ([INVESTMENT_TABLE]); the edit dialog and the lots
    // expansion still carry them.
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val cols = INVESTMENT_TABLE.visible(maxWidth.value.toInt())
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(BhodlTheme.shapes.panel)
                .background(BhodlTheme.colors.surface)
                .border(1.dp, BhodlTheme.colors.outline, BhodlTheme.shapes.panel),
        ) {
            // Section header bar — title + Add CTA, aligned with the
            // table's left/right padding so the column rhythm doesn't
            // break visually.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BhodlTheme.colors.surfaceVariant)
                    .padding(horizontal = BhodlTheme.spacing.md, vertical = BhodlTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.trp_inv_title),
                    style = BhodlTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = BhodlTheme.colors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(BhodlTheme.colors.accentNeon.copy(alpha = 0.18f))
                        .clickable(onClick = onAdd)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                        .testTag(InvestmentTags.ADD),
                ) {
                    Text(
                        text = stringResource(Res.string.trp_add),
                        style = BhodlTheme.typography.labelMedium,
                        color = BhodlTheme.colors.accentNeon,
                    )
                }
            }

            if (investments.isEmpty()) {
                // Empty-state row kept at the same 36dp rhythm as data rows
                // so the table doesn't visually collapse when nothing is in
                // it — looks like an empty grid instead of free-floating text.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .padding(horizontal = BhodlTheme.spacing.md),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = stringResource(Res.string.trp_inv_empty),
                        style = BhodlTheme.typography.bodyMedium,
                        color = BhodlTheme.colors.textTertiary,
                    )
                }
                return@Column
            }

            // Phase 7.8 — track which investment rows are currently
            // expanded showing their lots. State is local; tab switches
            // discard it on purpose (the TrackerCache snapshot survives,
            // but the UX choice "which row is open" doesn't need to).
            val expandedIds = androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateMapOf<Long, Boolean>()
            }
            // UX review 2026-10 (rec 2) — one unit for every BTC cell of the
            // table: Auto resolves on the totals (the largest magnitudes).
            val unitTotals = investmentsTotals(investments, lotsByInvestmentId, disposalsByInvestmentId, dashboardFiat)
            ProvideColumnUnit(
                listOfNotNull(unitTotals.totalCostBtc, unitTotals.totalValueBtc, unitTotals.totalPnlBtc)
                    .map { (it * 100_000_000.0).toLong() },
            ) {
                InvestmentsHeaderRow(cols)
                investments.forEachIndexed { idx, inv ->
                    val lots = lotsByInvestmentId[inv.id].orEmpty()
                    val isExpanded = expandedIds[inv.id] == true
                    InvestmentsDataRow(
                        inv = inv,
                        lots = lots,
                        cols = cols,
                        disposals = disposalsByInvestmentId[inv.id].orEmpty(),
                        isExpanded = isExpanded,
                        onToggleExpand = {
                            expandedIds[inv.id] = !isExpanded
                        },
                        isZebra = idx % 2 == 1,
                        onClick = { onEdit(inv) },
                    )
                    if (isExpanded) {
                        InvestmentLotsExpansion(
                            investmentId = inv.id,
                            lots = lots,
                            sells = sellsByInvestmentId[inv.id].orEmpty(),
                            disposalsByInvestment = disposalsByInvestmentId[inv.id].orEmpty(),
                            parentCurrencySymbol = symbolFor(inv.costBasisCurrency),
                            onAddLot = { onAddLot(inv.id) },
                            onEditLot = onEditLot,
                            onSell = { onSell(inv.id) },
                            onDeleteSell = onDeleteSell,
                        )
                    }
                }
                InvestmentsTotalsRow(
                    investments = investments,
                    lotsByInvestmentId = lotsByInvestmentId,
                    disposalsByInvestmentId = disposalsByInvestmentId,
                    dashboardFiat = dashboardFiat,
                    cols = cols,
                )
            }
        }
    }
}

@Composable
private fun InvestmentsHeaderRow(cols: List<InvestmentCol>) {
    // Phase 8.3.3 — header is now 7 columns wide to make room for the
    // dedicated `P&L BTC` column next to the existing `P&L EUR`. COST
    // and VALUE both grow a touch (1.0 → 1.2) because each cell now
    // stacks BTC over fiat (Medium density of `<Money>`), and the P&L
    // BTC column gets 0.8 to leave room for the divergence chip.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BhodlTheme.colors.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = BhodlTheme.spacing.md, vertical = BhodlTheme.spacing.sm)
            .testTag(InvestmentTags.HEADER),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (c in cols) {
            val label = when (c) {
                InvestmentCol.NAME -> Res.string.trp_col_name
                InvestmentCol.TYPE -> Res.string.trp_col_type
                InvestmentCol.QTY -> Res.string.trp_inv_col_qty
                InvestmentCol.COST -> Res.string.trp_inv_col_cost
                InvestmentCol.VALUE -> Res.string.trp_col_value
                InvestmentCol.PNL_FIAT -> Res.string.trp_inv_col_pnl_fiat
                InvestmentCol.PNL_BTC -> Res.string.trp_inv_col_pnl_btc
            }
            Text(
                stringResource(label),
                Modifier.tableCell(this, c).testTag(INVESTMENT_TABLE.headerTag(c)),
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.textSecondary,
                textAlign = if (c == InvestmentCol.NAME || c == InvestmentCol.TYPE) TextAlign.Start else TextAlign.End,
            )
        }
    }
}

@Composable
private fun InvestmentsDataRow(
    inv: TrackingInvestmentFfi,
    lots: List<TrackingInvestmentLotFfi>,
    /** Item 17 — the columns that fit the card's width. */
    cols: List<InvestmentCol>,
    /**
     * Phase 8.3.3 — disposals for this investment, used to compute the
     * realized side of the BTC P&L. Pass `emptyList()` when none.
     */
    disposals: List<io.bhodl.ffi.TrackingLotDisposalFfi> = emptyList(),
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    isZebra: Boolean,
    onClick: () -> Unit,
) {
    // Phase 7.8 — quantity and cost basis come from lots when present;
    // otherwise the legacy aggregate fields stick around (Phase 5.1).
    val effectiveQty = effectiveQuantity(inv, lots)
    val effectiveCost = effectiveCostBasis(inv, lots)
    // T2-10 — prefer the auto-fetched price when present, fall back
    // to the user's manual entry. The fetched value lives in its own
    // column so a Yahoo glitch (NaN, stale ticker, anti-bot capture)
    // never silently overwrites what the user typed.
    val effectivePerUnit: Double? = inv.fetchedCurrentValue ?: inv.manualCurrentValue
    val currentValueFiat: Double? = effectivePerUnit?.let { it * effectiveQty }
    val pnlFiat: Double? = currentValueFiat?.let { it - effectiveCost }
    val pnlPct: Int? = pnlFiat?.let {
        if (effectiveCost > 0.0) ((it / effectiveCost) * 100.0).roundToInt() else null
    }
    val pnlColor = pnlColorFor(pnlFiat)
    val currencySymbol = symbolFor(inv.costBasisCurrency)

    // Phase 9 — Bitcoin Lens P&L side. Resolve the BTC/<inv-currency>
    // rate via PriceCache.rateFor so a cross-currency position (e.g.
    // MSTR in USD on a EUR dashboard) gets a real BTC line instead of
    // "no rate": the auto-fetch loop already pulls BTC/<ccy> into the
    // per-currency rate map for every currency it knows (EUR/USD/CHF).
    // Returns null only when no rate for that currency is cached at all
    // (the cell then still says "no rate"). Supersedes the Phase 8.3.3
    // dashboard-fiat-must-match limitation.
    val currentBtcPerUnit: Double? = PriceCache.rateFor(inv.costBasisCurrency)
    val realizedBtc: Double? = realizedPnlBtc(disposals)
    val unrealizedBtc: Double? = unrealizedPnlBtc(
        remainingLots = lots,
        currentValuePerUnit = effectivePerUnit,
        currentBtcPerUnit = currentBtcPerUnit,
    )
    val totalBtc: Double? = totalPnlBtc(realizedBtc, unrealizedBtc)
    // BTC P&L percent: cost basis denominated in BTC = effectiveCost /
    // avgBtcRateAtPurchase. We approximate the denominator by summing
    // per-lot cost expressed in BTC. When any lot is missing a rate,
    // the BTC cost basis is unknown and we surface "—" via null pct.
    val costBasisBtc: Double? = lotsCostBtc(lots)
    val pnlBtcPct: Int? = if (totalBtc != null && costBasisBtc != null && costBasisBtc > 0.0) {
        ((totalBtc / costBasisBtc) * 100.0).roundToInt()
    } else {
        null
    }

    val bg = if (isZebra) BhodlTheme.colors.rowZebra else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Item 15 — dual-line Money cells, height from Settings › Density.
            .heightIn(min = BhodlTheme.density.rowHeightFor(2))
            .background(bg)
            .tableRowClickable(onClick = onClick)
            .padding(horizontal = BhodlTheme.spacing.md)
            .testTag(InvestmentTags.rowTag(inv.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Phase 7.8 — caret toggles the lots expansion. Clicking the
        // caret area doesn't fire the row's onEdit; click anywhere
        // else opens the edit dialog. Caret is hidden when the
        // legacy single-row model is in use and there are no lots,
        // because there's nothing to expand.
        val hasLotsCaret = lots.isNotEmpty() ||
            (inv.quantity == 0.0 && inv.costBasisFiat == 0.0)
        if (hasLotsCaret) {
            Box(
                modifier = Modifier
                    .clickable(onClick = onToggleExpand)
                    .padding(end = 6.dp)
                    .testTag(InvestmentTags.expandTag(inv.id)),
            ) {
                Text(
                    text = if (isExpanded) "▼" else "▶",
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textTertiary,
                )
            }
        }
        Column(modifier = Modifier.tableCell(this, InvestmentCol.NAME)) {
            Text(
                text = inv.name,
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textPrimary,
                maxLines = 1,
            )
            if (!inv.ticker.isNullOrBlank()) {
                Text(
                    text = inv.ticker!!,
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textTertiary,
                    maxLines = 1,
                )
            } else if (lots.isNotEmpty()) {
                Text(
                    text = if (lots.size > 1) stringResource(Res.string.trp_inv_lots_many, lots.size) else stringResource(Res.string.trp_inv_lots_one),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textTertiary,
                    maxLines = 1,
                )
            }
        }
        if (InvestmentCol.TYPE in cols) {
            Box(modifier = Modifier.tableCell(this, InvestmentCol.TYPE), contentAlignment = Alignment.CenterStart) {
                AssetTypeBadge(inv.assetType)
            }
        }
        if (InvestmentCol.QTY in cols) {
            Text(
                text = formatQuantity(effectiveQty),
                modifier = Modifier.tableCell(this, InvestmentCol.QTY),
                style = BhodlTheme.typography.amountSmall,
                color = BhodlTheme.colors.textSecondary,
                textAlign = TextAlign.End,
                maxLines = 1,
            )
        }
        // Phase 8.3.3 — COST cell: dual-line BTC primary + fiat
        // secondary via `<Money>` Medium. The BTC line is the per-lot
        // sum [lotsCostBtc] (each lot at its own purchase rate — the
        // same figure the P&L BTC % divides by); without it the cell
        // says "no rate".
        val costBtcSat: Long? = costBasisBtc?.let { kotlin.math.round(it * 100_000_000.0).toLong() }
        if (InvestmentCol.COST in cols) {
            Column(
                modifier = Modifier
                    .tableCell(this, InvestmentCol.COST)
                    .testTag(InvestmentTags.costTag(inv.id)),
                horizontalAlignment = Alignment.End,
            ) {
                Money(
                    value = MoneyValue(
                        sat = costBtcSat,
                        fiatAmount = effectiveCost,
                        fiatCurrency = inv.costBasisCurrency,
                        semantic = MoneySemantic.AtCost,
                    ),
                    density = MoneyDensity.Medium,
                    primary = MoneyPrimary.Btc,
                    showSecondary = true,
                )
            }
        }
        // Phase 8.3.3 — VALUE cell: dual-line, AtNow. The composable
        // resolves the sat side off the live PriceCache when the
        // investment's currency matches the dashboard fiat (otherwise
        // the BTC line renders "—"; cross-currency story matches the
        // P&L BTC behaviour below).
        Column(
            modifier = Modifier
                .tableCell(this, InvestmentCol.VALUE)
                .testTag(InvestmentTags.valueTag(inv.id)),
            horizontalAlignment = Alignment.End,
        ) {
            val valueRate: Double? = currentBtcPerUnit
            Money(
                value = MoneyValue(
                    fiatAmount = currentValueFiat,
                    fiatCurrency = inv.costBasisCurrency,
                    semantic = MoneySemantic.AtNow,
                    rateBtcFiat = valueRate,
                ),
                density = MoneyDensity.Medium,
                primary = MoneyPrimary.Btc,
                showSecondary = true,
            )
        }
        if (InvestmentCol.PNL_FIAT in cols) {
            Text(
                text = formatPnlPercent(pnlPct),
                modifier = Modifier.tableCell(this, InvestmentCol.PNL_FIAT),
                style = BhodlTheme.typography.amountSmall,
                color = pnlColor,
                textAlign = TextAlign.End,
                maxLines = 1,
            )
        }
        // Phase 8.3.3 — P&L BTC cell with optional ⚠ divergence chip.
        // Phase 8.6 — when the bare "—" would be caused by a missing
        // BTC rate (cross-currency investment or PriceCache not yet
        // populated, i.e. `currentBtcPerUnit == null`), surface an
        // explicit "no rate" label instead. Missing purchase-side data
        // (no lots / lots without a backfilled rate) keeps the em-dash,
        // and a cell that would carry the divergence chip is never
        // replaced.
        if (InvestmentCol.PNL_BTC in cols) {
            if (pnlBtcPct == null && currentBtcPerUnit == null &&
                !isPnlDivergent(pnlFiat, totalBtc)
            ) {
                NoRateCell(
                    modifier = Modifier.tableCell(this, InvestmentCol.PNL_BTC),
                    cellTag = InvestmentTags.pnlBtcTag(inv.id),
                )
            } else {
                DualPnlBtcCell(
                    pnlBtcPct = pnlBtcPct,
                    pnlFiat = pnlFiat,
                    pnlBtc = totalBtc,
                    modifier = Modifier.tableCell(this, InvestmentCol.PNL_BTC),
                    cellTag = InvestmentTags.pnlBtcTag(inv.id),
                    chipTag = InvestmentTags.pnlBtcChipTag(inv.id),
                )
            }
        }
    }
}

/**
 * Phase 8.3.3 — P&L BTC cell. Renders `formatPnlPercent(pnlBtcPct)`
 * colored by sign, plus an inline ⚠ chip when [pnlFiat] and [pnlBtc]
 * have opposite signs (the central "asset up in EUR but down in BTC"
 * Bitcoin Lens story).
 *
 * The chip uses the same muted-amber pattern as Phase 7.2's trust
 * signals (`financialPending` with 0.18 alpha background) so it
 * reads as informational rather than an error.
 */
@Composable
internal fun DualPnlBtcCell(
    pnlBtcPct: Int?,
    pnlFiat: Double?,
    pnlBtc: Double?,
    modifier: Modifier = Modifier,
    cellTag: String? = null,
    chipTag: String? = null,
) {
    val pnlBtcColor = pnlColorForInt(pnlBtcPct)
    val divergent = isPnlDivergent(pnlFiat, pnlBtc)
    val cellModifier = if (cellTag != null) {
        modifier.testTag(cellTag)
    } else {
        modifier
    }
    Row(
        modifier = cellModifier,
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatPnlPercent(pnlBtcPct),
            style = BhodlTheme.typography.amountSmall,
            color = pnlBtcColor,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
        if (divergent && pnlBtc != null) {
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(start = 4.dp))
            val chipText = if (pnlBtc < 0.0) "↓ vs ₿" else "↑ vs ₿"
            val chipModifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(BhodlTheme.colors.financialPending.copy(alpha = 0.18f))
                .padding(horizontal = 6.dp, vertical = 1.dp)
                .let { if (chipTag != null) it.testTag(chipTag) else it }
            Box(modifier = chipModifier) {
                Text(
                    text = chipText,
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.financialPending,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Phase 8.6 — explicit "no rate" cell. Replaces the bare "—" in the
 * BTC-side cells when the *cause* is a missing BTC/fiat rate (live
 * PriceCache not populated, dashboard fiat NONE, or cross-currency
 * asset), as opposed to "no data entered" — a missing purchase
 * snapshot keeps the em-dash. Right-aligned to match the numeric
 * cells it stands in for. Shared with [ManualAssetsCard]'s Δ BTC cell.
 */
@Composable
internal fun NoRateCell(
    modifier: Modifier = Modifier,
    cellTag: String? = null,
) {
    val cellModifier = if (cellTag != null) {
        modifier.testTag(cellTag)
    } else {
        modifier
    }
    Row(
        modifier = cellModifier,
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.common_no_rate),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textTertiary,
            maxLines = 1,
        )
    }
}

/**
 * [computeInvestmentsTotals] wired to the live price cache. Shared by
 * the totals row and the card (which resolves the table's unit from it).
 */
@Composable
private fun investmentsTotals(
    investments: List<TrackingInvestmentFfi>,
    lotsByInvestmentId: Map<Long, List<TrackingInvestmentLotFfi>>,
    disposalsByInvestmentId: Map<Long, List<io.bhodl.ffi.TrackingLotDisposalFfi>>,
    dashboardFiat: FiatCurrency,
): InvestmentsTotals {
    // UX review 2026-10 (B2) — BTC is currency-agnostic: the BTC side
    // of the totals sums EVERY row, each converted at its own
    // currency's rate. The old code summed only the dashboard-currency
    // rows (e.g. ₿0.10 for 2 EUR rows vs ₿0.25 for all 4). The fiat
    // side still aggregates only the dashboard-currency rows (no forex).
    val livePriceBtcInFiat by PriceCache.price
    return computeInvestmentsTotals(
        investments = investments,
        lotsByInvestmentId = lotsByInvestmentId,
        disposalsByInvestmentId = disposalsByInvestmentId,
        dashboardFiatCode = if (dashboardFiat == FiatCurrency.NONE) null else dashboardFiat.code,
        rateFor = { ccy ->
            if (dashboardFiat != FiatCurrency.NONE && ccy.equals(dashboardFiat.code, ignoreCase = true)) {
                livePriceBtcInFiat ?: PriceCache.rateFor(ccy)
            } else {
                PriceCache.rateFor(ccy)
            }
        },
    )
}

@Composable
private fun InvestmentsTotalsRow(
    investments: List<TrackingInvestmentFfi>,
    lotsByInvestmentId: Map<Long, List<TrackingInvestmentLotFfi>>,
    /**
     * Phase 8.3.3 — disposals per investment, used for the realized
     * side of the BTC totals. Same currency filter applies as for
     * the fiat aggregates (see open question #1 in the plan).
     */
    disposalsByInvestmentId: Map<Long, List<io.bhodl.ffi.TrackingLotDisposalFfi>>,
    dashboardFiat: FiatCurrency,
    cols: List<InvestmentCol>,
) {
    val totals = investmentsTotals(investments, lotsByInvestmentId, disposalsByInvestmentId, dashboardFiat)
    val matching = totals.fiatRows
    val totalCost = totals.totalCostFiat
    val totalValue = totals.totalValueFiat
    val totalPnl = totals.totalPnlFiat
    val totalPnlPct = totals.totalPnlPct
    val ccy = totals.fiatCurrency
    val sym = symbolFor(ccy)
    val pnlColor = pnlColorFor(totalPnl.takeIf { matching.isNotEmpty() })
    val totalCostBtc = totals.totalCostBtc
    val totalValueBtc = totals.totalValueBtc
    val totalPnlBtc = totals.totalPnlBtc
    val totalPnlBtcPct = totals.totalPnlBtcPct
    val valueRateMissing = totals.valueRateMissing

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Stacked BTC-over-fiat cells (spec §2), density-aware (item 15).
            .heightIn(min = BhodlTheme.density.rowHeightFor(3))
            .background(BhodlTheme.colors.surfaceVariant)
            .padding(horizontal = BhodlTheme.spacing.md)
            .testTag(InvestmentTags.TOTALS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.tableCell(this, InvestmentCol.NAME)) {
            Text(
                text = stringResource(Res.string.trp_total),
                style = BhodlTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = BhodlTheme.colors.textPrimary,
            )
            // Mixed currencies: say which rows the fiat sub-line covers
            // (the BTC line always covers all rows).
            if (matching.size != investments.size && ccy.isNotEmpty()) {
                Text(
                    text = stringResource(Res.string.tracker_investments_fiat_rows_only, ccy),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textTertiary,
                    maxLines = 1,
                )
            }
        }
        // type column intentionally blank on totals row
        if (InvestmentCol.TYPE in cols) Box(modifier = Modifier.tableCell(this, InvestmentCol.TYPE)) {}
        // B2 — QTY is meaningless across different instruments: blank.
        if (InvestmentCol.QTY in cols) {
            Box(modifier = Modifier.tableCell(this, InvestmentCol.QTY).testTag(InvestmentTags.TOTALS_QTY)) {}
        }
        // COST — stacked BTC over fiat per spec §4.3.
        if (InvestmentCol.COST in cols) {
            Column(
                modifier = Modifier.tableCell(this, InvestmentCol.COST).testTag(InvestmentTags.TOTALS_COST),
                horizontalAlignment = Alignment.End,
            ) {
                Text(
                    text = formatBtcAmount(totalCostBtc),
                    style = BhodlTheme.typography.amountSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = BhodlTheme.colors.textPrimary,
                    maxLines = 1,
                )
                Text(
                    text = formatFiatWithCode(totalCost, ccy),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
        // VALUE — stacked BTC over fiat.
        Column(
            modifier = Modifier.tableCell(this, InvestmentCol.VALUE).testTag(InvestmentTags.TOTALS_VALUE),
            horizontalAlignment = Alignment.End,
        ) {
            // Phase 8.6 — `totalValueBtc` is null *only* when the live
            // BTC rate is missing or invalid (see the run block above),
            // so the explicit "no rate" label is always the right story
            // here, never "no data entered".
            if (totalValueBtc == null && valueRateMissing) {
                Text(
                    text = stringResource(Res.string.common_no_rate),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textTertiary,
                    maxLines = 1,
                )
            } else {
                Text(
                    text = formatBtcAmount(totalValueBtc),
                    style = BhodlTheme.typography.amountSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = BhodlTheme.colors.textPrimary,
                    maxLines = 1,
                )
            }
            Text(
                text = formatFiatWithCode(totalValue, ccy),
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.textSecondary,
                maxLines = 1,
            )
        }
        if (InvestmentCol.PNL_FIAT in cols) {
            Text(
                text = formatPnlPercent(totalPnlPct),
                modifier = Modifier.tableCell(this, InvestmentCol.PNL_FIAT),
                style = BhodlTheme.typography.amountSmall,
                color = pnlColor,
                textAlign = TextAlign.End,
            )
        }
        // Phase 8.3.3 — totals row also surfaces the divergence chip
        // when the aggregate fiat / BTC signs disagree.
        // Phase 8.6 — same "no rate" policy as the data rows: when the
        // aggregate "—" is caused by the missing live BTC rate (and no
        // divergence chip would render), say so explicitly. Missing
        // lot/disposal backfill keeps the em-dash.
        if (InvestmentCol.PNL_BTC in cols) {
            if (totalPnlBtcPct == null && valueRateMissing &&
                !isPnlDivergent(totalPnl.takeIf { matching.isNotEmpty() }, totalPnlBtc)
            ) {
                NoRateCell(
                    modifier = Modifier.tableCell(this, InvestmentCol.PNL_BTC),
                    cellTag = InvestmentTags.TOTALS_PNL_BTC,
                )
            } else {
                DualPnlBtcCell(
                    pnlBtcPct = totalPnlBtcPct,
                    pnlFiat = totalPnl.takeIf { matching.isNotEmpty() },
                    pnlBtc = totalPnlBtc,
                    modifier = Modifier.tableCell(this, InvestmentCol.PNL_BTC),
                    cellTag = InvestmentTags.TOTALS_PNL_BTC,
                    chipTag = InvestmentTags.TOTALS_PNL_BTC_CHIP,
                )
            }
        }
    }
}

@Composable
private fun AssetTypeBadge(assetType: String) {
    val (bg, fg) = assetTypeColors(assetType)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = investmentTypeLabel(assetType).uppercase(),
            style = BhodlTheme.typography.labelMedium,
            color = fg,
        )
    }
}

internal object InvestmentTags {
    const val ADD: String = "investment.add"
    const val SAVE: String = "investment.save"
    const val DELETE: String = "investment.delete"
    const val INPUT_NAME: String = "investment.input-name"
    const val INPUT_TICKER: String = "investment.input-ticker"
    const val INPUT_QUANTITY: String = "investment.input-quantity"
    const val INPUT_UNIT: String = "investment.input-unit"
    const val INPUT_COST: String = "investment.input-cost"
    const val INPUT_CCY: String = "investment.input-ccy"
    const val INPUT_CURRENT: String = "investment.input-current"

    // ─── Phase 8.3.3 — Bitcoin Lens migration ─────────────────────
    /** Phase 8.3.3 — header row tag for column-count assertion. */
    const val HEADER: String = "investment.header"
    /** Phase 8.3.3 — totals row container. */
    const val TOTALS: String = "investment.totals"
    /** UX review 2026-10 (B2) — totals row QTY cell (always blank). */
    const val TOTALS_QTY: String = "investment.totals.qty"
    /** Phase 8.3.3 — totals row COST stacked cell. */
    const val TOTALS_COST: String = "investment.totals.cost"
    /** Phase 8.3.3 — totals row VALUE stacked cell. */
    const val TOTALS_VALUE: String = "investment.totals.value"
    /** Phase 8.3.3 — totals row P&L BTC cell. */
    const val TOTALS_PNL_BTC: String = "investment.totals.pnl-btc"
    /** Phase 8.3.3 — totals row divergence chip when fiat / BTC signs disagree. */
    const val TOTALS_PNL_BTC_CHIP: String = "investment.totals.pnl-btc.chip"

    /** Phase 7.8 — expansion caret per investment row. */
    fun expandTag(investmentId: Long): String = "investment.expand.$investmentId"
    /** Phase 7.8 — "+ Add lot" link inside the expanded sub-section. */
    fun addLotTag(investmentId: Long): String = "investment.add-lot.$investmentId"
    /** Phase 7.8 — per-lot sub-row tap target. */
    fun lotRowTag(lotId: Long): String = "investment.lot.$lotId"
    /** Phase 7.8 — cost basis method picker chip in InvestmentEditDialog. */
    fun costBasisMethodTag(method: String): String = "investment.cost-basis-method.$method"
    /** Phase 7.9 — "− Sell" link in the lot expansion. */
    fun sellTag(investmentId: Long): String = "investment.sell.$investmentId"
    /** Phase 7.9 — per-sell row tap target in the Sells sub-section. */
    fun sellRowTag(sellId: Long): String = "investment.sell-row.$sellId"

    /** Phase 8.3.3 — data row container per investment. */
    fun rowTag(investmentId: Long): String = "investment.row.$investmentId"
    /** Phase 8.3.3 — COST cell per row (wraps `<Money>` Medium). */
    fun costTag(investmentId: Long): String = "investment.cost.$investmentId"
    /** Phase 8.3.3 — VALUE cell per row (wraps `<Money>` Medium). */
    fun valueTag(investmentId: Long): String = "investment.value.$investmentId"
    /** Phase 8.3.3 — P&L BTC cell per row. */
    fun pnlBtcTag(investmentId: Long): String = "investment.pnl-btc.$investmentId"
    /** Phase 8.3.3 — divergence chip inside the P&L BTC cell. */
    fun pnlBtcChipTag(investmentId: Long): String = "investment.pnl-btc.$investmentId.chip"
}

/**
 * UX review 2026-10 (B2) — aggregates behind the investments "Total"
 * row. Pure so the arithmetic is unit-tested.
 *
 *  - Fiat side ([totalCostFiat], [totalValueFiat], P&L): only the rows
 *    in [dashboardFiatCode] ([fiatRows]); all rows when it is null.
 *    Summing EUR and USD without forex would be meaningless.
 *  - BTC side: EVERY row, each at its own currency's rate
 *    ([rateFor] = current BTC/<ccy>; lot / disposal rates for the
 *    historical side). Null when a contributing rate is missing;
 *    [valueRateMissing] says the null is due to a missing CURRENT rate
 *    (the UI then says "no rate").
 */
internal data class InvestmentsTotals(
    val fiatRows: List<TrackingInvestmentFfi>,
    val fiatCurrency: String,
    val totalCostFiat: Double,
    val totalValueFiat: Double,
    val totalPnlFiat: Double,
    val totalPnlPct: Int?,
    val totalCostBtc: Double?,
    val totalValueBtc: Double?,
    val totalPnlBtc: Double?,
    val totalPnlBtcPct: Int?,
    val valueRateMissing: Boolean,
)

internal fun computeInvestmentsTotals(
    investments: List<TrackingInvestmentFfi>,
    lotsByInvestmentId: Map<Long, List<TrackingInvestmentLotFfi>>,
    disposalsByInvestmentId: Map<Long, List<io.bhodl.ffi.TrackingLotDisposalFfi>>,
    dashboardFiatCode: String?,
    rateFor: (String) -> Double?,
): InvestmentsTotals {
    val fiatRows = if (dashboardFiatCode == null) {
        investments
    } else {
        investments.filter { it.costBasisCurrency.equals(dashboardFiatCode, ignoreCase = true) }
    }
    val totalCost = fiatRows.sumOf { effectiveCostBasis(it, lotsByInvestmentId[it.id].orEmpty()) }
    val totalValue = fiatRows.sumOf { inv ->
        val per = inv.fetchedCurrentValue ?: inv.manualCurrentValue ?: 0.0
        per * effectiveQuantity(inv, lotsByInvestmentId[inv.id].orEmpty())
    }
    val totalPnl = totalValue - totalCost
    val totalPnlPct: Int? = if (totalCost > 0.0) ((totalPnl / totalCost) * 100.0).roundToInt() else null

    fun validRate(ccy: String): Double? = rateFor(ccy)?.takeIf { it.isFinite() && it > 0.0 }

    // Cost basis in BTC: Σ per-lot (cost_in_fiat / lot_btc_rate) over all
    // rows. Null when even one lot is missing its historical rate.
    val totalCostBtc: Double? = run {
        var sum = 0.0
        var any = false
        for (inv in investments) {
            val lots = lotsByInvestmentId[inv.id].orEmpty()
            if (lots.isEmpty()) continue
            sum += lotsCostBtc(lots) ?: return@run null
            any = true
        }
        if (any) sum else null
    }
    // Current value in BTC: each row's fiat value at its own currency's
    // current rate. A priced row without a rate makes the sum unknown.
    var valueRateMissing = false
    val totalValueBtc: Double? = run valueSum@{
        var sum = 0.0
        var any = false
        for (inv in investments) {
            val per = inv.fetchedCurrentValue ?: inv.manualCurrentValue ?: continue
            val rate = validRate(inv.costBasisCurrency)
            if (rate == null) {
                valueRateMissing = true
                return@valueSum null
            }
            sum += per * effectiveQuantity(inv, lotsByInvestmentId[inv.id].orEmpty()) / rate
            any = true
        }
        if (any) sum else null
    }
    val totalRealizedBtc: Double? = run {
        var sum = 0.0
        for (inv in investments) {
            val d = disposalsByInvestmentId[inv.id].orEmpty()
            if (d.isEmpty()) continue
            sum += realizedPnlBtc(d) ?: return@run null
        }
        sum
    }
    val totalUnrealizedBtc: Double? = run {
        var sum = 0.0
        var any = false
        for (inv in investments) {
            val lots = lotsByInvestmentId[inv.id].orEmpty()
            if (lots.isEmpty()) continue
            val per = inv.fetchedCurrentValue ?: inv.manualCurrentValue ?: continue
            sum += unrealizedPnlBtc(lots, per, validRate(inv.costBasisCurrency)) ?: return@run null
            any = true
        }
        if (any) sum else null
    }
    val totalPnlBtc = totalPnlBtc(totalRealizedBtc, totalUnrealizedBtc)
    val totalPnlBtcPct: Int? = if (totalPnlBtc != null && totalCostBtc != null && totalCostBtc > 0.0) {
        ((totalPnlBtc / totalCostBtc) * 100.0).roundToInt()
    } else {
        null
    }
    return InvestmentsTotals(
        fiatRows = fiatRows,
        fiatCurrency = fiatRows.firstOrNull()?.costBasisCurrency ?: dashboardFiatCode.orEmpty(),
        totalCostFiat = totalCost,
        totalValueFiat = totalValue,
        totalPnlFiat = totalPnl,
        totalPnlPct = totalPnlPct,
        totalCostBtc = totalCostBtc,
        totalValueBtc = totalValueBtc,
        totalPnlBtc = totalPnlBtc,
        totalPnlBtcPct = totalPnlBtcPct,
        valueRateMissing = valueRateMissing,
    )
}
