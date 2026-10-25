package io.bhodl.desktop.screens

import io.bhodl.desktop.ui.tableRowClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.data.TrackerCache
import io.bhodl.desktop.prices.PriceCache
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.ui.displaySignedAmount
import io.bhodl.desktop.ui.rememberAmountFormatter
import io.bhodl.desktop.ui.ColumnAmountFormat
import io.bhodl.desktop.ui.headerWithUnit
import io.bhodl.desktop.ui.rememberColumnAmountFormat
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.TrackingInvestmentFfi
import io.bhodl.ffi.TrackingInvestmentLotFfi
import io.bhodl.ffi.TrackingLiabilityFfi
import io.bhodl.ffi.TrackingManualAssetFfi
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.tracker_sheet_change_note
import io.bhodl.shared.resources.tracker_sheet_col_change
import io.bhodl.shared.resources.tracker_sheet_col_name
import io.bhodl.shared.resources.tracker_sheet_col_pct_nw
import io.bhodl.shared.resources.tracker_sheet_col_type
import io.bhodl.shared.resources.tracker_sheet_col_updated
import io.bhodl.shared.resources.tracker_sheet_col_value
import io.bhodl.shared.resources.tracker_sheet_empty_section
import io.bhodl.shared.resources.tracker_sheet_net_worth
import io.bhodl.shared.resources.tracker_sheet_section_investments
import io.bhodl.shared.resources.tracker_sheet_section_liabilities
import io.bhodl.shared.resources.tracker_sheet_section_manual
import io.bhodl.shared.resources.tracker_sheet_section_wallets
import io.bhodl.shared.resources.tracker_sheet_title
import io.bhodl.shared.resources.tracker_sheet_updated_days
import io.bhodl.shared.resources.tracker_sheet_updated_today
import io.bhodl.ui.theme.BhodlTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import io.bhodl.shared.resources.common_no_rate
import io.bhodl.shared.resources.trp_add
import io.bhodl.shared.resources.trp_badge_other
import io.bhodl.shared.resources.trp_ma_badge_business
import io.bhodl.shared.resources.trp_ma_badge_cash
import io.bhodl.shared.resources.trp_ma_badge_collectible
import io.bhodl.shared.resources.trp_ma_badge_loan_out
import io.bhodl.shared.resources.trp_ma_badge_vehicle
import io.bhodl.shared.resources.trp_sheet_add_investment
import io.bhodl.shared.resources.trp_sheet_add_liability
import io.bhodl.shared.resources.trp_sheet_add_manual
import io.bhodl.shared.resources.trp_sheet_badge_brokerage
import io.bhodl.shared.resources.trp_sheet_badge_commodity
import io.bhodl.shared.resources.trp_sheet_badge_credit
import io.bhodl.shared.resources.trp_sheet_badge_currency
import io.bhodl.shared.resources.trp_sheet_badge_etf
import io.bhodl.shared.resources.trp_sheet_badge_fund
import io.bhodl.shared.resources.trp_sheet_badge_loan
import io.bhodl.shared.resources.trp_sheet_badge_manual
import io.bhodl.shared.resources.trp_sheet_badge_mortgage
import io.bhodl.shared.resources.trp_sheet_badge_real_estate
import io.bhodl.shared.resources.trp_sheet_badge_stock
import io.bhodl.shared.resources.trp_sheet_badge_watch
import io.bhodl.shared.resources.trp_sheet_fetch_prices
import io.bhodl.shared.resources.pfc_view_cards
import io.bhodl.shared.resources.pfc_view_table
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics

internal object AssetPortfolioTags {
    const val CARD: String = "asset-portfolio.card"
    const val ADD_MENU: String = "asset-portfolio.add-menu"
    const val ADD_INVESTMENT: String = "asset-portfolio.add-investment"
    const val ADD_MANUAL_ASSET: String = "asset-portfolio.add-manual-asset"
    const val ADD_LIABILITY: String = "asset-portfolio.add-liability"
    const val NET_TOTAL: String = "asset-portfolio.net-total"
    fun section(s: SheetSection): String = "asset-portfolio.section.${s.name.lowercase()}"
    fun row(key: String): String = "asset-portfolio.row.$key"
    fun header(c: SheetColumn): String = "asset-portfolio.col.${c.name.lowercase()}"
    fun view(v: PortfolioView): String = "asset-portfolio.view.${v.name.lowercase()}"
}

/**
 * UX review 2026-10 (item 9) — the Portfolio balance sheet (Kubera:
 * sheet → section → row). The model is [buildPortfolioSheet]; this file
 * only lays it out:
 *  - dense rows at [BhodlTheme.density] height;
 *  - columns from [visibleSheetColumns] for the available width (type
 *    and last-updated give way first on narrow windows);
 *  - each section row carries its subtotal, the bottom row the net
 *    worth — both straight from the shared [TrackerNetWorth];
 *  - the change column follows the GLOBAL period selector [period].
 */
@Composable
internal fun AssetPortfolioCard(
    /** UX review 2026-10 (B1) — the shared net worth (same numbers as
     *  the HOME hero); see [computeTrackerNetWorth]. */
    netWorth: TrackerNetWorth,
    /** UX review 2026-10 (B8) — the tracked wallets, one per logical
     *  wallet ([trackedWalletGroups]), so the count matches HOME. */
    walletGroups: List<GroupedWallet>,
    investments: List<TrackingInvestmentFfi>,
    investmentLotsByInvestmentId: Map<Long, List<TrackingInvestmentLotFfi>> = emptyMap(),
    manualAssets: List<TrackingManualAssetFfi>,
    liabilities: List<TrackingLiabilityFfi>,
    onAddInvestment: () -> Unit,
    onEditInvestment: (TrackingInvestmentFfi) -> Unit,
    onAddManualAsset: () -> Unit,
    onEditManualAsset: (TrackingManualAssetFfi) -> Unit,
    onAddLiability: () -> Unit,
    onEditLiability: (TrackingLiabilityFfi) -> Unit,
    onRefreshPrices: () -> Unit = {},
    /** On-chain events, for the wallets' change over the period. */
    events: List<PaymentEvent> = emptyList(),
    /** The global period selector; drives the change column. */
    period: TrackingPeriod = TrackingPeriod.D30,
    modifier: Modifier = Modifier,
    /** Portfolio redesign 2026-10 — class cards or the balance-sheet table. */
    view: PortfolioView = PortfolioView.TABLE,
    onViewChange: (PortfolioView) -> Unit = {},
    sellsByInvestmentId: Map<Long, List<io.bhodl.ffi.TrackingInvestmentSellFfi>> = emptyMap(),
    disposalsByInvestmentId: Map<Long, List<io.bhodl.ffi.TrackingLotDisposalFfi>> = emptyMap(),
    details: PortfolioDetails = PortfolioDetails(),
    onEditDetails: (DetailsEditRequest) -> Unit = {},
    onAddLot: (Long) -> Unit = {},
    onEditLot: (TrackingInvestmentLotFfi) -> Unit = {},
    onSell: (Long) -> Unit = {},
    onDeleteSell: (io.bhodl.ffi.TrackingInvestmentSellFfi) -> Unit = {},
    bitcoinExtra: @Composable () -> Unit = {},
) {
    val hasStaleInvestments = remember(investments) {
        investments.any { !it.ticker.isNullOrBlank() && it.fetchedCurrentValue == null }
    }
    val liveBtcRate by PriceCache.price
    val currencyRates by PriceCache.currencyRates
    val dashboardFiat by SettingsStore.fiatCurrency
    val lastSyncUnix by TrackerCache.lastSyncUnix

    // Period start (null for "All") and the BTC/<ccy> rate on that day
    // for every currency the sheet converts.
    val periodStartUnix: Long? = remember(period) {
        period.days?.let { System.currentTimeMillis() / 1000L - it.toLong() * 86_400L }
    }
    val currencies = remember(investments, manualAssets) {
        (investments.map { it.costBasisCurrency } + manualAssets.map { it.currency })
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
            .toSet()
    }
    // Keyed by the day they were fetched for: a period change must not
    // price the new window with the previous window's rates.
    val startDay: Long? = periodStartUnix?.let(::sheetStartDay)
    var startRates by remember { mutableStateOf<SheetStartRates?>(null) }
    LaunchedEffect(startDay, currencies) {
        val day = startDay ?: return@LaunchedEffect
        val fetched = withContext(Dispatchers.IO) {
            currencies.mapNotNull { ccy ->
                runCatching { io.bhodl.desktop.prices.BtcRateHistoryClient.rateAt(day, ccy) }
                    .getOrNull()
                    ?.takeIf { it.isFinite() && it > 0.0 }
                    ?.let { ccy to it }
            }.toMap()
        }
        startRates = SheetStartRates(day, fetched)
    }

    val sheet = remember(
        netWorth, walletGroups, investments, investmentLotsByInvestmentId, manualAssets,
        liabilities, events, periodStartUnix, startDay, liveBtcRate, currencyRates,
        dashboardFiat, startRates, lastSyncUnix,
    ) {
        buildPortfolioSheet(
            netWorth = netWorth,
            walletGroups = walletGroups,
            investments = investments,
            lotsByInvestmentId = investmentLotsByInvestmentId,
            manualAssets = manualAssets,
            liabilities = liabilities,
            events = events,
            periodStartUnix = periodStartUnix,
            rateNow = { ccy -> resolveRate(ccy, liveBtcRate, dashboardFiat.code) },
            rateAtStart = { ccy -> startDay?.let { startRates?.rateFor(ccy, it) } },
            walletsUpdatedUnix = lastSyncUnix,
        )
    }

    if (view == PortfolioView.CARDS) {
        Column(
            modifier = modifier.fillMaxWidth().testTag(AssetPortfolioTags.CARD),
            verticalArrangement = Arrangement.spacedBy(BhodlTheme.spacing.md),
        ) {
            PortfolioHeaderBar(
                onAddInvestment = onAddInvestment,
                onAddManualAsset = onAddManualAsset,
                onAddLiability = onAddLiability,
                hasStaleInvestments = hasStaleInvestments,
                onRefreshPrices = onRefreshPrices,
                view = view,
                onViewChange = onViewChange,
                modifier = Modifier.clip(BhodlTheme.shapes.panel).border(1.dp, BhodlTheme.colors.outline, BhodlTheme.shapes.panel),
            )
            PortfolioClassView(
                sheet = sheet,
                walletGroups = walletGroups,
                investments = investments,
                lotsByInvestmentId = investmentLotsByInvestmentId,
                sellsByInvestmentId = sellsByInvestmentId,
                disposalsByInvestmentId = disposalsByInvestmentId,
                manualAssets = manualAssets,
                liabilities = liabilities,
                details = details,
                satToFiat = { sat, ccy ->
                    resolveRate(ccy, liveBtcRate, dashboardFiat.code)?.let { sat.toDouble() / 100_000_000.0 * it }
                },
                onEditInvestment = onEditInvestment,
                onEditManualAsset = onEditManualAsset,
                onEditLiability = onEditLiability,
                onEditDetails = onEditDetails,
                onAddLot = onAddLot,
                onEditLot = onEditLot,
                onSell = onSell,
                onDeleteSell = onDeleteSell,
                onAddInvestment = onAddInvestment,
                onAddManualAsset = onAddManualAsset,
                onAddLiability = onAddLiability,
                bitcoinExtra = bitcoinExtra,
                events = events,
                period = period,
            )
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(BhodlTheme.shapes.panel)
            .background(BhodlTheme.colors.surface)
            .border(1.dp, BhodlTheme.colors.outline, BhodlTheme.shapes.panel)
            .testTag(AssetPortfolioTags.CARD),
    ) {
        PortfolioHeaderBar(
            onAddInvestment = onAddInvestment,
            onAddManualAsset = onAddManualAsset,
            onAddLiability = onAddLiability,
            hasStaleInvestments = hasStaleInvestments,
            onRefreshPrices = onRefreshPrices,
            view = view,
            onViewChange = onViewChange,
        )
        // UX review 2026-10 (rec 2) — Value and Change share ONE unit (Auto never
        // mixes sats and BTC down a column); the headers carry it.
        val sheetAmounts = rememberColumnAmountFormat(sheetAmountValues(sheet))
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val hPad = BhodlTheme.spacing.md
            val columns = visibleSheetColumns((maxWidth - hPad * 2).value.toInt())
            CompositionLocalProvider(LocalSheetAmounts provides sheetAmounts) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SheetHeaderRow(columns, period)
                    for (s in sheet.sections) {
                        SectionRow(s, columns)
                        if (s.rows.isEmpty()) {
                            Text(
                                text = stringResource(Res.string.tracker_sheet_empty_section),
                                style = BhodlTheme.typography.labelMedium,
                                color = BhodlTheme.colors.textTertiary,
                                modifier = Modifier.padding(horizontal = hPad + 18.dp, vertical = 6.dp),
                            )
                        }
                        s.rows.forEachIndexed { idx, row ->
                            val onClick: (() -> Unit)? = when (row.section) {
                                SheetSection.WALLETS -> null
                                SheetSection.INVESTMENTS ->
                                    investments.firstOrNull { it.id == row.recordId }?.let { { onEditInvestment(it) } }
                                SheetSection.MANUAL_ASSETS ->
                                    manualAssets.firstOrNull { it.id == row.recordId }?.let { { onEditManualAsset(it) } }
                                SheetSection.LIABILITIES ->
                                    liabilities.firstOrNull { it.id == row.recordId }?.let { { onEditLiability(it) } }
                            }
                            SheetDataRow(row, columns, isZebra = idx % 2 == 1, onClick = onClick)
                        }
                    }
                    NetWorthRow(sheet, columns)
                }
            }
        }
        Text(
            text = stringResource(Res.string.tracker_sheet_change_note),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textTertiary,
            modifier = Modifier.padding(horizontal = BhodlTheme.spacing.md, vertical = 8.dp),
        )
    }
}

// ─── Header ────────────────────────────────────────────────────────

@Composable
private fun PortfolioHeaderBar(
    onAddInvestment: () -> Unit,
    onAddManualAsset: () -> Unit,
    onAddLiability: () -> Unit,
    hasStaleInvestments: Boolean,
    onRefreshPrices: () -> Unit,
    view: PortfolioView,
    onViewChange: (PortfolioView) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(BhodlTheme.colors.surfaceVariant)
            .padding(horizontal = BhodlTheme.spacing.md, vertical = BhodlTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.tracker_sheet_title),
            style = BhodlTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = BhodlTheme.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        PortfolioViewToggle(view, onViewChange)
        if (hasStaleInvestments) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(BhodlTheme.colors.financialPending.copy(alpha = 0.18f))
                    .clickable(onClick = onRefreshPrices)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(stringResource(Res.string.trp_sheet_fetch_prices), style = BhodlTheme.typography.labelMedium, color = BhodlTheme.colors.financialPending)
            }
        }
        Box {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(BhodlTheme.colors.accentNeon.copy(alpha = 0.18f))
                    .clickable { menuExpanded = true }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .testTag(AssetPortfolioTags.ADD_MENU),
            ) {
                Text(stringResource(Res.string.trp_add), style = BhodlTheme.typography.labelMedium, color = BhodlTheme.colors.accentNeon)
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.trp_sheet_add_investment)) },
                    onClick = { menuExpanded = false; onAddInvestment() },
                    modifier = Modifier.testTag(AssetPortfolioTags.ADD_INVESTMENT),
                )
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.trp_sheet_add_manual)) },
                    onClick = { menuExpanded = false; onAddManualAsset() },
                    modifier = Modifier.testTag(AssetPortfolioTags.ADD_MANUAL_ASSET),
                )
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.trp_sheet_add_liability)) },
                    onClick = { menuExpanded = false; onAddLiability() },
                    modifier = Modifier.testTag(AssetPortfolioTags.ADD_LIABILITY),
                )
            }
        }
    }
}

/** Portfolio redesign 2026-10 — class cards (default) or the balance-sheet table. */
internal enum class PortfolioView { CARDS, TABLE }

@Composable
private fun PortfolioViewToggle(view: PortfolioView, onViewChange: (PortfolioView) -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, BhodlTheme.colors.outline, RoundedCornerShape(6.dp)),
    ) {
        PortfolioView.entries.forEach { v ->
            val on = v == view
            Box(
                modifier = Modifier
                    .background(if (on) BhodlTheme.colors.surface else Color.Transparent)
                    .clickable(role = androidx.compose.ui.semantics.Role.Tab) { onViewChange(v) }
                    .semantics { selected = on }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .testTag(AssetPortfolioTags.view(v)),
            ) {
                Text(
                    stringResource(if (v == PortfolioView.CARDS) Res.string.pfc_view_cards else Res.string.pfc_view_table),
                    style = BhodlTheme.typography.labelMedium,
                    color = if (on) BhodlTheme.colors.textPrimary else BhodlTheme.colors.textSecondary,
                )
            }
        }
    }
}

// ─── Grid ──────────────────────────────────────────────────────────

/** One sheet line: cells for [columns], NAME flexible, others fixed. */
@Composable
private fun SheetLine(
    columns: List<SheetColumn>,
    modifier: Modifier = Modifier,
    background: Color = Color.Transparent,
    onClick: (() -> Unit)? = null,
    cell: @Composable RowScope.(SheetColumn) -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = BhodlTheme.density.rowHeight)
            .background(background)
            // Item 15 — keyboard focus ring + ↑/↓ row moves.
            .let { if (onClick != null) it.tableRowClickable(onClick = onClick) else it }
            .padding(horizontal = BhodlTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SHEET_COLUMN_GAP_DP.dp),
    ) {
        for (c in columns) cell(c)
    }
}

private fun Modifier.sheetCell(scope: RowScope, c: SheetColumn): Modifier = with(scope) {
    if (c == SheetColumn.NAME) this@sheetCell.weight(1f) else this@sheetCell.width(c.minWidthDp.dp)
}

@Composable
private fun SheetHeaderRow(columns: List<SheetColumn>, period: TrackingPeriod) {
    val unit = LocalSheetAmounts.current?.unitLabel.orEmpty()
    SheetLine(columns) { c ->
        val label = when (c) {
            SheetColumn.NAME -> stringResource(Res.string.tracker_sheet_col_name)
            SheetColumn.TYPE -> stringResource(Res.string.tracker_sheet_col_type)
            SheetColumn.VALUE -> stringResource(Res.string.tracker_sheet_col_value)
            SheetColumn.CHANGE -> stringResource(Res.string.tracker_sheet_col_change, period.label)
            SheetColumn.PCT_NW -> stringResource(Res.string.tracker_sheet_col_pct_nw)
            SheetColumn.UPDATED -> stringResource(Res.string.tracker_sheet_col_updated)
        }
        Text(
            // Unit appended after uppercasing: "VALUE (sat)", never "(SAT)". Change
            // shares the Value unit; its header ("Change · 90D") has no room for it.
            text = if (c == SheetColumn.VALUE) {
                headerWithUnit(label.uppercase(), unit)
            } else {
                label.uppercase()
            },
            style = BhodlTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
            color = BhodlTheme.colors.textTertiary,
            textAlign = if (c.isNumeric()) TextAlign.End else TextAlign.Start,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.sheetCell(this, c).testTag(AssetPortfolioTags.header(c)),
        )
    }
}

private fun SheetColumn.isNumeric(): Boolean =
    this == SheetColumn.VALUE || this == SheetColumn.CHANGE || this == SheetColumn.PCT_NW ||
        this == SheetColumn.UPDATED

@Composable
private fun SectionRow(s: SheetSectionModel, columns: List<SheetColumn>) {
    val roles = nwRoleColors()
    val (label, color) = when (s.section) {
        SheetSection.WALLETS -> stringResource(Res.string.tracker_sheet_section_wallets) to roles.btcWallets
        SheetSection.INVESTMENTS -> stringResource(Res.string.tracker_sheet_section_investments) to roles.investments
        SheetSection.MANUAL_ASSETS -> stringResource(Res.string.tracker_sheet_section_manual) to roles.manualAssets
        SheetSection.LIABILITIES -> stringResource(Res.string.tracker_sheet_section_liabilities) to roles.liabilities
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(BhodlTheme.colors.outline.copy(alpha = 0.5f)))
    SheetLine(
        columns,
        modifier = Modifier.testTag(AssetPortfolioTags.section(s.section)),
        background = BhodlTheme.colors.surfaceVariant.copy(alpha = 0.5f),
    ) { c ->
        when (c) {
            SheetColumn.NAME -> Row(
                modifier = Modifier.sheetCell(this, c),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "$label (${s.rows.size})",
                    style = BhodlTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = BhodlTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            SheetColumn.VALUE -> ValueCell(s.subtotalSat, bold = true, modifier = Modifier.sheetCell(this, c))
            SheetColumn.CHANGE -> ChangeCell(s.changeSat, Modifier.sheetCell(this, c))
            SheetColumn.PCT_NW -> PctCell(s.pctOfNetWorth, Modifier.sheetCell(this, c))
            else -> Spacer(Modifier.sheetCell(this, c))
        }
    }
}

@Composable
private fun SheetDataRow(row: SheetRow, columns: List<SheetColumn>, isZebra: Boolean, onClick: (() -> Unit)?) {
    SheetLine(
        columns,
        modifier = Modifier.testTag(AssetPortfolioTags.row(row.key)),
        background = if (isZebra) BhodlTheme.colors.rowZebra else Color.Transparent,
        onClick = onClick,
    ) { c ->
        when (c) {
            SheetColumn.NAME -> Text(
                text = row.name,
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.sheetCell(this, c).padding(start = 18.dp),
            )
            SheetColumn.TYPE -> Box(Modifier.sheetCell(this, c), contentAlignment = Alignment.CenterStart) {
                val (l, col) = kindBadge(row.section, row.kind)
                TypeBadge(l, col)
            }
            SheetColumn.VALUE -> ValueCell(row.valueSat, bold = false, modifier = Modifier.sheetCell(this, c))
            SheetColumn.CHANGE -> ChangeCell(row.changeSat, Modifier.sheetCell(this, c))
            SheetColumn.PCT_NW -> PctCell(row.pctOfNetWorth, Modifier.sheetCell(this, c))
            SheetColumn.UPDATED -> UpdatedCell(row.updatedUnix, Modifier.sheetCell(this, c))
        }
    }
}

@Composable
private fun NetWorthRow(sheet: PortfolioSheet, columns: List<SheetColumn>) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(BhodlTheme.colors.outline))
    SheetLine(
        columns,
        modifier = Modifier.testTag(AssetPortfolioTags.NET_TOTAL),
        background = BhodlTheme.colors.surfaceVariant,
    ) { c ->
        when (c) {
            SheetColumn.NAME -> Text(
                text = stringResource(Res.string.tracker_sheet_net_worth).uppercase(),
                style = BhodlTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = BhodlTheme.colors.textPrimary,
                modifier = Modifier.sheetCell(this, c),
            )
            SheetColumn.VALUE -> ValueCell(sheet.netWorthSat, bold = true, modifier = Modifier.sheetCell(this, c))
            SheetColumn.CHANGE -> ChangeCell(sheet.netWorthChangeSat, Modifier.sheetCell(this, c))
            SheetColumn.PCT_NW -> PctCell(if (sheet.netWorthSat > 0L) 100.0 else null, Modifier.sheetCell(this, c))
            else -> Spacer(Modifier.sheetCell(this, c))
        }
    }
}

// ─── Cells ─────────────────────────────────────────────────────────

/** Column format of the Value / Change columns (unit in the header). */
private val LocalSheetAmounts = compositionLocalOf<ColumnAmountFormat?> { null }

/** Every amount the Value / Change columns show, for the column unit. */
private fun sheetAmountValues(sheet: PortfolioSheet): List<Long> = buildList {
    add(sheet.netWorthSat)
    sheet.netWorthChangeSat?.let(::add)
    for (s in sheet.sections) {
        add(s.subtotalSat)
        s.changeSat?.let(::add)
        for (r in s.rows) {
            r.valueSat?.let(::add)
            r.changeSat?.let(::add)
        }
    }
}

/** Bare signed cell, same glyphs as `displaySignedAmount` ("+", "−"); no sign while masked. */
private fun signedBareCell(column: ColumnAmountFormat, sat: Long, masked: Boolean): String =
    io.bhodl.desktop.ui.amountSign(sat, masked = masked) + column.bare(kotlin.math.abs(sat))

@Composable
private fun ValueCell(sat: Long?, bold: Boolean, modifier: Modifier) {
    val column = LocalSheetAmounts.current
    val format = rememberAmountFormatter()
    Text(
        text = sat?.let { column?.bare(it) ?: format(it) } ?: stringResource(Res.string.common_no_rate),
        style = BhodlTheme.typography.amountSmall.copy(
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        ),
        color = when {
            sat == null -> BhodlTheme.colors.textTertiary
            // Item 13 — privacy mode: no liability red on a masked value.
            sat < 0L && !io.bhodl.desktop.ui.money.amountsMasked() -> nwRoleColors().liabilities
            else -> BhodlTheme.colors.textPrimary
        },
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier = modifier,
    )
}

/** Signed change: the explicit +/− carries the meaning, colour only reinforces. */
@Composable
private fun ChangeCell(sat: Long?, modifier: Modifier) {
    val masked = io.bhodl.desktop.ui.money.amountsMasked()
    Text(
        text = sat?.let { s -> LocalSheetAmounts.current?.let { signedBareCell(it, s, masked) } ?: displaySignedAmount(s) } ?: "—",
        style = BhodlTheme.typography.amountSmall,
        color = io.bhodl.desktop.ui.signedAmountColor(
            value = sat?.toDouble(),
            positive = BhodlTheme.colors.financialPositive,
            negative = BhodlTheme.colors.financialNegative,
            neutral = BhodlTheme.colors.textTertiary,
        ),
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier = modifier,
    )
}

@Composable
private fun PctCell(pct: Double?, modifier: Modifier) {
    Text(
        text = pct?.let { "%.1f%%".format(it) } ?: "—",
        style = BhodlTheme.typography.amountSmall,
        color = BhodlTheme.colors.textSecondary,
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier = modifier,
    )
}

@Composable
private fun UpdatedCell(unix: Long?, modifier: Modifier) {
    val text = if (unix == null) {
        "—"
    } else {
        val days = ((System.currentTimeMillis() / 1000L - unix) / 86_400L).coerceAtLeast(0L)
        if (days == 0L) {
            stringResource(Res.string.tracker_sheet_updated_today)
        } else {
            stringResource(Res.string.tracker_sheet_updated_days, days.toInt())
        }
    }
    Text(
        text = text,
        style = BhodlTheme.typography.labelMedium,
        color = BhodlTheme.colors.textTertiary,
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier = modifier,
    )
}

@Composable
private fun kindBadge(section: SheetSection, kind: String): Pair<String, Color> = when (section) {
    // Short badge: the full kind ("Watch (xpub)") does not fit the
    // dense TYPE column.
    SheetSection.WALLETS -> stringResource(Res.string.trp_sheet_badge_watch) to nwRoleColors().btcWallets
    SheetSection.INVESTMENTS -> investmentTypeLabelAndColor(kind)
    SheetSection.MANUAL_ASSETS -> manualAssetKindLabelAndColor(kind)
    SheetSection.LIABILITIES -> liabilityKindLabelAndColor(kind)
}

@Composable
private fun TypeBadge(label: String, accent: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(accent.copy(alpha = 0.18f))
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Text(text = label, style = BhodlTheme.typography.labelMedium, color = accent, maxLines = 1)
    }
}

// Phase 9.1 — every badge color comes from the Okabe-Ito categorical
// palette (kind ≠ verdict, so no semantic green/red) except liability
// kinds, which ARE a verdict and take the financial layer.
@Composable
private fun investmentTypeLabelAndColor(assetType: String): Pair<String, Color> {
    val cat = BhodlTheme.colors.chartCategorical
    return when (assetType.lowercase()) {
        "stock" -> stringResource(Res.string.trp_sheet_badge_stock) to cat.getOrElse(1) { BhodlTheme.colors.textSecondary }
        "etf" -> stringResource(Res.string.trp_sheet_badge_etf) to cat.getOrElse(0) { BhodlTheme.colors.textSecondary }
        "fund" -> stringResource(Res.string.trp_sheet_badge_fund) to cat.getOrElse(2) { BhodlTheme.colors.textSecondary }
        "currency" -> stringResource(Res.string.trp_sheet_badge_currency) to cat.getOrElse(3) { BhodlTheme.colors.textSecondary }
        "commodity" -> stringResource(Res.string.trp_sheet_badge_commodity) to cat.getOrElse(4) { BhodlTheme.colors.textSecondary }
        "manual" -> stringResource(Res.string.trp_sheet_badge_manual) to BhodlTheme.colors.textTertiary
        else -> investmentTypeLabel(assetType).uppercase() to BhodlTheme.colors.textTertiary
    }
}

@Composable
private fun manualAssetKindLabelAndColor(kind: String): Pair<String, Color> {
    val cat = BhodlTheme.colors.chartCategorical
    return when (kind.lowercase()) {
        "cash" -> stringResource(Res.string.trp_ma_badge_cash) to cat.getOrElse(1) { BhodlTheme.colors.textSecondary }
        "brokerage" -> stringResource(Res.string.trp_sheet_badge_brokerage) to cat.getOrElse(3) { BhodlTheme.colors.textSecondary }
        "real_estate" -> stringResource(Res.string.trp_sheet_badge_real_estate) to cat.getOrElse(0) { BhodlTheme.colors.textSecondary }
        "vehicle" -> stringResource(Res.string.trp_ma_badge_vehicle) to cat.getOrElse(4) { BhodlTheme.colors.textSecondary }
        "collectible" -> stringResource(Res.string.trp_ma_badge_collectible) to cat.getOrElse(2) { BhodlTheme.colors.textSecondary }
        "business_equity" -> stringResource(Res.string.trp_ma_badge_business) to cat.getOrElse(5) { BhodlTheme.colors.textSecondary }
        "loans_to_others" -> stringResource(Res.string.trp_ma_badge_loan_out) to cat.getOrElse(6) { BhodlTheme.colors.textSecondary }
        else -> stringResource(Res.string.trp_badge_other) to BhodlTheme.colors.textTertiary
    }
}

@Composable
private fun liabilityKindLabelAndColor(kind: String): Pair<String, Color> = when (kind.lowercase()) {
    "mortgage" -> stringResource(Res.string.trp_sheet_badge_mortgage) to BhodlTheme.colors.financialNegative
    "loan" -> stringResource(Res.string.trp_sheet_badge_loan) to BhodlTheme.colors.financialNegativeMuted
    "credit-card" -> stringResource(Res.string.trp_sheet_badge_credit) to BhodlTheme.colors.financialPending
    else -> stringResource(Res.string.trp_badge_other) to BhodlTheme.colors.textTertiary
}

// ─── Conversion helpers ───────────────────────────────────────────

internal fun computeLiveInvestmentsSat(
    investments: List<TrackingInvestmentFfi>,
    lotsByInvId: Map<Long, List<TrackingInvestmentLotFfi>>,
    liveBtcRate: Double?,
    dashboardFiatCode: String,
): ULong {
    var total = 0UL
    for (inv in investments) {
        val lots = lotsByInvId[inv.id] ?: emptyList()
        val effectivePerUnit = inv.fetchedCurrentValue ?: inv.manualCurrentValue
        val effectiveQty = effectiveQuantity(inv, lots)
        val effectiveCost = effectiveCostBasis(inv, lots)
        val bestFiat = effectivePerUnit?.let { it * effectiveQty }
            ?: effectiveCost.takeIf { it > 0.0 && it.isFinite() }
            ?: continue
        if (bestFiat <= 0.0 || !bestFiat.isFinite()) continue
        val sat: ULong = fiatToSatBestEffort(bestFiat, inv.costBasisCurrency, liveBtcRate, dashboardFiatCode)
            ?: continue
        total += sat
    }
    return total
}

internal fun computeLiveManualAssetsSat(
    assets: List<TrackingManualAssetFfi>,
    liveBtcRate: Double?,
    dashboardFiatCode: String,
): ULong {
    var total = 0UL
    for (a in assets) {
        if (!a.valueFiat.isFinite() || a.valueFiat <= 0.0) continue
        val sat: ULong = fiatToSatBestEffortManual(
            a.valueFiat, a.currency, liveBtcRate, dashboardFiatCode,
        ) ?: continue
        total += sat
    }
    return total
}

private fun fiatToSatBestEffort(
    fiatAmount: Double, currency: String, liveBtcRate: Double?,
    dashboardFiatCode: String,
): ULong? {
    if (fiatAmount <= 0.0 || !fiatAmount.isFinite()) return null
    // "no rate" policy: only a CURRENT BTC/<ccy> rate may price the row.
    // The old fallback to the lots' purchase-time BTC rate showed a
    // historical conversion as if it were the current value.
    val rate = resolveRate(currency, liveBtcRate, dashboardFiatCode)
    if (rate != null) return ((fiatAmount / rate) * 100_000_000.0).toLong().coerceAtLeast(0L).toULong()
    return null
}

private fun fiatToSatBestEffortManual(
    fiatAmount: Double, currency: String, liveBtcRate: Double?,
    dashboardFiatCode: String,
): ULong? {
    if (fiatAmount <= 0.0 || !fiatAmount.isFinite()) return null
    // "no rate" policy: never price a current value at the purchase-time
    // BTC rate — the row renders "no rate" instead.
    val rate = resolveRate(currency, liveBtcRate, dashboardFiatCode)
    if (rate != null) return ((fiatAmount / rate) * 100_000_000.0).toLong().coerceAtLeast(0L).toULong()
    return null
}

private fun resolveRate(currency: String, liveBtcRate: Double?, dashboardFiatCode: String): Double? {
    val ccy = currency.trim().uppercase()
    if (ccy.equals(dashboardFiatCode, ignoreCase = true) && liveBtcRate != null && liveBtcRate > 0.0) return liveBtcRate
    return PriceCache.rateFor(ccy)
}
