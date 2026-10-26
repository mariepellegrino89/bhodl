package io.bhodl.desktop.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.graphics.compositeOver
import io.bhodl.shared.resources.pfb_title
import io.bhodl.shared.resources.pfb_net
import io.bhodl.shared.resources.pfb_meta_wallets
import io.bhodl.shared.resources.pfb_meta_holdings
import io.bhodl.shared.resources.pfb_meta_items
import io.bhodl.shared.resources.pfb_meta_stale
import io.bhodl.shared.resources.pfb_meta_leverage
import io.bhodl.shared.resources.pfb_unrealized
import io.bhodl.shared.resources.pfb_repaid_short
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.contentDescription
import io.bhodl.shared.resources.pfb_in_period
import io.bhodl.shared.resources.pfb_add_investment
import io.bhodl.shared.resources.pfb_add_manual
import io.bhodl.shared.resources.pfb_add_liability
import io.bhodl.shared.resources.pfb_add_wallet
import io.bhodl.shared.resources.pfb_cost
import io.bhodl.shared.resources.pfb_vs_hodl
import io.bhodl.shared.resources.pfb_h_cost
import io.bhodl.shared.resources.pfb_h_vs_hodl
import io.bhodl.shared.resources.pfb_detail
import io.bhodl.shared.resources.pfb_rows_title
import io.bhodl.shared.resources.pfb_record_buy
import io.bhodl.shared.resources.pfb_record_sell
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.bhodl.desktop.prices.NetWorthHistoryClient
import io.bhodl.desktop.prices.NetWorthHistoryClient.NwSnapshot
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.ui.HelpTip
import io.bhodl.desktop.ui.SlideOverPanel
import io.bhodl.desktop.ui.amountSign
import io.bhodl.desktop.ui.money.amountsMasked
import io.bhodl.desktop.ui.signedAmountColor
import io.bhodl.desktop.ui.Sparkline
import io.bhodl.desktop.ui.bhodlCard
import io.bhodl.desktop.ui.displayAmount
import io.bhodl.desktop.ui.displaySignedAmount
import io.bhodl.desktop.ui.formatFiatWithCode
import io.bhodl.desktop.wizard.RegistryStore
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.TrackingInvestmentFfi
import io.bhodl.ffi.TrackingInvestmentLotFfi
import io.bhodl.ffi.TrackingInvestmentSellFfi
import io.bhodl.ffi.TrackingLiabilityFfi
import io.bhodl.ffi.TrackingLotDisposalFfi
import io.bhodl.ffi.TrackingManualAssetFfi
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.pfc_h_annual_cost
import io.bhodl.shared.resources.pfc_h_annual_interest
import io.bhodl.shared.resources.pfc_h_appraisal_age
import io.bhodl.shared.resources.pfc_h_avg_price
import io.bhodl.shared.resources.pfc_h_balance
import io.bhodl.shared.resources.pfc_h_change
import io.bhodl.shared.resources.pfc_h_class_bitcoin
import io.bhodl.shared.resources.pfc_h_class_investments
import io.bhodl.shared.resources.pfc_h_class_liabilities
import io.bhodl.shared.resources.pfc_h_class_manual
import io.bhodl.shared.resources.pfc_h_company_value
import io.bhodl.shared.resources.pfc_h_depreciation
import io.bhodl.shared.resources.pfc_h_equity
import io.bhodl.shared.resources.pfc_h_estimated_value
import io.bhodl.shared.resources.pfc_h_freshness
import io.bhodl.shared.resources.pfc_h_fx_effect
import io.bhodl.shared.resources.pfc_h_gross_yield
import io.bhodl.shared.resources.pfc_h_ltv
import io.bhodl.shared.resources.pfc_h_months_left
import io.bhodl.shared.resources.pfc_h_next_statement
import io.bhodl.shared.resources.pfc_h_price
import io.bhodl.shared.resources.pfc_h_purchase
import io.bhodl.shared.resources.pfc_h_quantity
import io.bhodl.shared.resources.pfc_h_realized
import io.bhodl.shared.resources.pfc_h_remaining
import io.bhodl.shared.resources.pfc_h_repaid
import io.bhodl.shared.resources.pfc_h_revaluation
import io.bhodl.shared.resources.pfc_h_stack_share
import io.bhodl.shared.resources.pfc_h_term_elapsed
import io.bhodl.shared.resources.pfc_h_to_receive
import io.bhodl.shared.resources.pfc_h_unrealized
import io.bhodl.shared.resources.pfc_h_updated
import io.bhodl.shared.resources.pfc_h_utilization
import io.bhodl.shared.resources.pfc_h_value
import io.bhodl.shared.resources.pfc_h_weight
import io.bhodl.shared.resources.pfc_k_annual_cost
import io.bhodl.shared.resources.pfc_k_annual_interest
import io.bhodl.shared.resources.pfc_k_appraisal_age
import io.bhodl.shared.resources.pfc_k_avg_price
import io.bhodl.shared.resources.pfc_k_balance
import io.bhodl.shared.resources.pfc_k_change
import io.bhodl.shared.resources.pfc_k_company_value
import io.bhodl.shared.resources.pfc_k_depreciation
import io.bhodl.shared.resources.pfc_k_equity
import io.bhodl.shared.resources.pfc_k_estimated_value
import io.bhodl.shared.resources.pfc_k_freshness
import io.bhodl.shared.resources.pfc_k_fx_effect
import io.bhodl.shared.resources.pfc_k_gross_yield
import io.bhodl.shared.resources.pfc_k_ltv
import io.bhodl.shared.resources.pfc_k_months_left
import io.bhodl.shared.resources.pfc_k_next_statement
import io.bhodl.shared.resources.pfc_k_price
import io.bhodl.shared.resources.pfc_k_purchase
import io.bhodl.shared.resources.pfc_k_quantity
import io.bhodl.shared.resources.pfc_k_realized
import io.bhodl.shared.resources.pfc_k_remaining
import io.bhodl.shared.resources.pfc_k_revaluation
import io.bhodl.shared.resources.pfc_k_stack_share
import io.bhodl.shared.resources.pfc_k_term_elapsed
import io.bhodl.shared.resources.pfc_k_to_receive
import io.bhodl.shared.resources.pfc_k_unrealized
import io.bhodl.shared.resources.pfc_k_updated
import io.bhodl.shared.resources.pfc_k_utilization
import io.bhodl.shared.resources.pfc_k_value
import io.bhodl.shared.resources.pfc_k_weight
import io.bhodl.shared.resources.common_cancel
import io.bhodl.shared.resources.common_close
import io.bhodl.shared.resources.pfc_add_lot
import io.bhodl.shared.resources.pfc_class_bitcoin
import io.bhodl.shared.resources.pfc_class_investments
import io.bhodl.shared.resources.pfc_class_liabilities
import io.bhodl.shared.resources.pfc_class_manual
import io.bhodl.shared.resources.pfc_details_edit
import io.bhodl.shared.resources.pfc_details_empty
import io.bhodl.shared.resources.pfc_details_hint
import io.bhodl.shared.resources.pfc_details_none
import io.bhodl.shared.resources.pfc_details_save
import io.bhodl.shared.resources.pfc_details_title
import io.bhodl.shared.resources.pfc_edit
import io.bhodl.shared.resources.pfc_empty_class
import io.bhodl.shared.resources.pfc_items
import io.bhodl.shared.resources.pfc_k_repaid
import io.bhodl.shared.resources.pfc_lots
import io.bhodl.shared.resources.pfc_lot_row
import io.bhodl.shared.resources.pfc_no_selection
import io.bhodl.shared.resources.pfc_opt_distribution_acc
import io.bhodl.shared.resources.pfc_opt_distribution_dist
import io.bhodl.shared.resources.pfc_opt_form_bar
import io.bhodl.shared.resources.pfc_opt_form_coin
import io.bhodl.shared.resources.pfc_opt_form_etc
import io.bhodl.shared.resources.pfc_opt_form_other
import io.bhodl.shared.resources.pfc_opt_rate_type_fixed
import io.bhodl.shared.resources.pfc_opt_rate_type_variable
import io.bhodl.shared.resources.pfc_opt_use_land
import io.bhodl.shared.resources.pfc_opt_use_primary
import io.bhodl.shared.resources.pfc_opt_use_rental
import io.bhodl.shared.resources.pfc_opt_use_second
import io.bhodl.shared.resources.pfc_sell
import io.bhodl.shared.resources.pfc_sells
import io.bhodl.shared.resources.pfc_sell_row
import io.bhodl.shared.resources.pfc_v_days_ago
import io.bhodl.shared.resources.pfc_v_in_days
import io.bhodl.shared.resources.pfc_v_months
import io.bhodl.shared.resources.pfc_v_no
import io.bhodl.shared.resources.pfc_v_today
import io.bhodl.shared.resources.pfc_v_yes
import io.bhodl.shared.resources.pfc_delete_sell
import io.bhodl.shared.resources.pfc_link_none
import io.bhodl.ui.theme.BhodlTheme
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal object PortfolioCardsTags {
    const val VIEW: String = "portfolio-cards.view"
    fun classCard(c: PortfolioClass): String = "portfolio-cards.class.${c.name.lowercase()}"
    fun classShare(c: PortfolioClass): String = "portfolio-cards.share.${c.name.lowercase()}"
    fun item(key: String): String = "portfolio-cards.item.$key"
    const val DETAIL: String = "portfolio-cards.detail"
    const val DETAIL_TITLE: String = "portfolio-cards.detail.title"
    fun kpi(i: Int): String = "portfolio-cards.detail.kpi.$i"
    const val METER: String = "portfolio-cards.detail.meter"
    const val ATTRS: String = "portfolio-cards.detail.attrs"
    const val EDIT: String = "portfolio-cards.detail.edit"
    const val EDIT_DETAILS: String = "portfolio-cards.detail.edit-details"
    const val ADD_LOT: String = "portfolio-cards.detail.add-lot"
    const val SELL: String = "portfolio-cards.detail.sell"
    const val EMPTY_CLASS: String = "portfolio-cards.empty-class"
    const val HERO: String = "portfolio-cards.hero"
    const val BACK: String = "portfolio-cards.back"
    const val PANEL_CLOSE: String = "portfolio-cards.panel.close"
    const val PANEL: String = "portfolio-cards.panel"
    const val PANEL_BUY: String = "portfolio-cards.panel.buy"
    const val PANEL_SELL: String = "portfolio-cards.panel.sell"
    const val ADD_INVESTMENT: String = "portfolio-cards.add.investment"
    const val ADD_MANUAL: String = "portfolio-cards.add.manual"
    const val ADD_LIABILITY: String = "portfolio-cards.add.liability"
    const val ADD_WALLET: String = "portfolio-cards.add.wallet"
    const val DETAILS_DIALOG: String = "portfolio-cards.details-dialog"
    fun detailsField(key: String): String = "portfolio-cards.details-dialog.field.$key"
    const val DETAILS_SAVE: String = "portfolio-cards.details-dialog.save"
}

/** What the detail-editor host needs to open [PortfolioDetailsDialog]. */
internal data class DetailsEditRequest(
    val category: DetailCategory,
    val recordId: Long,
    val name: String,
    val current: Map<String, String>,
)

/**
 * Portfolio redesign 2026-10 (canvas B + D + E) — proposal B: title,
 * net worth and class ring, 2×2 class cards and add shortcuts on the
 * left, the selected class in a side panel on the right. A click on an
 * item of the panel opens its page (proposal D, cards of E). Every number comes from [sheet] (the same model
 * as the balance-sheet table) or from the per-category builders in
 * `PortfolioDetails.kt`.
 */
@Composable
internal fun PortfolioClassView(
    sheet: PortfolioSheet,
    walletGroups: List<GroupedWallet>,
    investments: List<TrackingInvestmentFfi>,
    lotsByInvestmentId: Map<Long, List<TrackingInvestmentLotFfi>>,
    sellsByInvestmentId: Map<Long, List<TrackingInvestmentSellFfi>>,
    disposalsByInvestmentId: Map<Long, List<TrackingLotDisposalFfi>>,
    manualAssets: List<TrackingManualAssetFfi>,
    liabilities: List<TrackingLiabilityFfi>,
    details: PortfolioDetails,
    satToFiat: (sat: Long, currency: String) -> Double?,
    onEditInvestment: (TrackingInvestmentFfi) -> Unit,
    onEditManualAsset: (TrackingManualAssetFfi) -> Unit,
    onEditLiability: (TrackingLiabilityFfi) -> Unit,
    onEditDetails: (DetailsEditRequest) -> Unit,
    onAddLot: (Long) -> Unit,
    onEditLot: (TrackingInvestmentLotFfi) -> Unit,
    onSell: (Long) -> Unit,
    onDeleteSell: (TrackingInvestmentSellFfi) -> Unit,
    /** Proposal B — the add shortcuts under the class cards. */
    onAddInvestment: () -> Unit = {},
    onAddManualAsset: () -> Unit = {},
    onAddLiability: () -> Unit = {},
    /** Hidden when null. */
    onAddWallet: (() -> Unit)? = null,
    /** Proposal B — controls next to the title (Cards / Table, fetch prices). */
    heroActions: @Composable () -> Unit = {},
    /** Shown under the panes while the Bitcoin class is selected. */
    bitcoinExtra: @Composable () -> Unit = {},
    events: List<PaymentEvent> = emptyList(),
    period: TrackingPeriod = TrackingPeriod.D30,
    /** Test seam: the per-class history behind the sparklines. */
    history: List<NwSnapshot>? = null,
    nowUnix: Long = nowUnixUtc(),
) {
    val sections = sheet.sections.associateBy { it.section }
    var selectedClass by remember {
        mutableStateOf(
            PortfolioClass.entries.firstOrNull { sections[it.section]?.rows?.isNotEmpty() == true }
                ?: PortfolioClass.INVESTMENTS,
        )
    }
    val series = rememberClassHistory(history, period, events)
    val fiatCode = SettingsStore.fiatCurrency.value.code

    val rows = sections[selectedClass.section]?.rows.orEmpty()
        .sortedByDescending { kotlin.math.abs(it.valueSat ?: 0L) }
    val summary = remember(selectedClass, rows, investments, lotsByInvestmentId, manualAssets, liabilities, details) {
        classSummary(selectedClass, rows, investments, lotsByInvestmentId, manualAssets, liabilities, details)
    }
    // Proposal D — a click on an item of the panel opens its page.
    var openKey by remember { mutableStateOf<String?>(null) }
    val openRow = sheet.sections.flatMap { it.rows }.firstOrNull { it.key == openKey }
    io.bhodl.desktop.ui.BackHandlerEffect(enabled = openRow != null) { openKey = null }

    if (openRow != null) {
        val openClass = PortfolioClass.entries.first { it.section == openRow.section }
        Column(verticalArrangement = Arrangement.spacedBy(BhodlTheme.spacing.md), modifier = Modifier.testTag(PortfolioCardsTags.VIEW)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(Res.string.pfb_title),
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textSecondary,
                    modifier = Modifier.clickable(role = Role.Button) { openKey = null }.testTag(PortfolioCardsTags.BACK),
                )
                Text("›", style = BhodlTheme.typography.bodyMedium, color = BhodlTheme.colors.textTertiary)
                Text(
                    stringResource(classLabel(openClass)),
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textSecondary,
                    modifier = Modifier.clickable(role = Role.Button) { selectedClass = openClass; openKey = null },
                )
                Text("›", style = BhodlTheme.typography.bodyMedium, color = BhodlTheme.colors.textTertiary)
                Text(openRow.name, style = BhodlTheme.typography.bodyMedium, color = BhodlTheme.colors.textPrimary)
            }
            ItemDetail(
                row = openRow,
                sheet = sheet,
                walletGroups = walletGroups,
                investments = investments,
                lotsByInvestmentId = lotsByInvestmentId,
                sellsByInvestmentId = sellsByInvestmentId,
                disposalsByInvestmentId = disposalsByInvestmentId,
                manualAssets = manualAssets,
                liabilities = liabilities,
                details = details,
                satToFiat = satToFiat,
                fiatCode = fiatCode,
                nowUnix = nowUnix,
                onEditInvestment = onEditInvestment,
                onEditManualAsset = onEditManualAsset,
                onEditLiability = onEditLiability,
                onEditDetails = onEditDetails,
                onAddLot = onAddLot,
                onEditLot = onEditLot,
                onSell = onSell,
                onDeleteSell = onDeleteSell,
                modifier = Modifier.fillMaxWidth(),
            )
            if (openClass == PortfolioClass.BITCOIN) bitcoinExtra()
        }
        return
    }

    // Proposal B: hero, 2×2 class cards and add buttons on the left; the
    // selected class in a side panel on the right.
    var panelOpen by remember { mutableStateOf(true) }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().testTag(PortfolioCardsTags.VIEW)) {
        val wide = maxWidth >= 1100.dp
        val left: @Composable (Modifier) -> Unit = { m ->
            Column(m, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                PortfolioHero(sheet, period, heroActions)
                val shares = remember(sheet) { classShares(sheet) }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val columns = if (maxWidth < 660.dp) 1 else 2
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        PortfolioClass.entries.chunked(columns).forEach { rowClasses ->
                            // Equal heights: a class with fewer items keeps the row even.
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                modifier = Modifier.height(IntrinsicSize.Min),
                            ) {
                                rowClasses.forEach { cls ->
                                    ClassCard(
                                        cls = cls,
                                        section = sections[cls.section],
                                        share = shares[cls],
                                        meta = classMeta(cls, sheet, investments, lotsByInvestmentId, nowUnix),
                                        points = series[cls].orEmpty(),
                                        selected = panelOpen && cls == selectedClass,
                                        onClick = { selectedClass = cls; panelOpen = true },
                                        modifier = Modifier.weight(1f).fillMaxHeight(),
                                    )
                                }
                            }
                        }
                    }
                }
                AddButtonsRow(onAddInvestment, onAddManualAsset, onAddLiability, onAddWallet)
            }
        }
        val right: @Composable (Modifier) -> Unit = { m ->
            ClassDetailPanel(
                cls = selectedClass,
                section = sections[selectedClass.section],
                rows = rows,
                summary = summary,
                onOpen = { openKey = it },
                onClose = { panelOpen = false },
                investmentChoices = if (selectedClass == PortfolioClass.INVESTMENTS) rows.filter { it.recordId != null } else emptyList(),
                onRecordBuy = onAddLot,
                onRecordSell = onSell,
                modifier = m,
            )
        }
        if (!panelOpen) {
            left(Modifier.fillMaxWidth())
        } else if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                left(Modifier.weight(1.8f))
                right(Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                left(Modifier.fillMaxWidth())
                right(Modifier.fillMaxWidth())
            }
        }
    }
}

/** Per-class sparkline series from the net-worth history (sat). */
@Composable
private fun rememberClassHistory(
    injected: List<NwSnapshot>?,
    period: TrackingPeriod,
    events: List<PaymentEvent>,
): Map<PortfolioClass, List<Pair<Long, Double>>> {
    var loaded by remember { mutableStateOf<List<NwSnapshot>>(emptyList()) }
    val fiat by SettingsStore.fiatCurrency
    val dataVersion by RegistryStore.dataVersion
    LaunchedEffect(injected, period, fiat, dataVersion, events) {
        if (injected != null) return@LaunchedEffect
        if (fiat == io.bhodl.desktop.prices.FiatCurrency.NONE || RegistryStore.current() == null) {
            loaded = emptyList()
            return@LaunchedEffect
        }
        val now = System.currentTimeMillis() / 1000L
        val days = (period.days ?: 365).toLong()
        loaded = NetWorthHistoryClient.netWorthHistory(
            fromUnix = now - days * 86_400L,
            toUnix = now,
            currency = fiat,
            events = netWorthHistoryEvents(events, SettingsStore.excludedWalletIds.value),
            dataVersion = dataVersion,
        )
    }
    val snaps = injected ?: loaded
    return remember(snaps) {
        mapOf(
            PortfolioClass.BITCOIN to snaps.map { it.dateUnix to it.onchainSat.toDouble() },
            PortfolioClass.INVESTMENTS to snaps.map { it.dateUnix to it.investmentsSat.toDouble() },
            PortfolioClass.MANUAL_ASSETS to snaps.map { it.dateUnix to it.manualAssetsSat.toDouble() },
            PortfolioClass.LIABILITIES to snaps.map { it.dateUnix to it.liabilitiesSat.toDouble() },
        )
    }
}

@Composable
private fun classColor(cls: PortfolioClass): Color {
    val roles = nwRoleColors()
    return when (cls) {
        PortfolioClass.BITCOIN -> roles.btcWallets
        PortfolioClass.INVESTMENTS -> roles.investments
        PortfolioClass.MANUAL_ASSETS -> roles.manualAssets
        PortfolioClass.LIABILITIES -> roles.liabilities
    }
}

private fun classLabel(cls: PortfolioClass): StringResource = when (cls) {
    PortfolioClass.BITCOIN -> Res.string.pfc_class_bitcoin
    PortfolioClass.INVESTMENTS -> Res.string.pfc_class_investments
    PortfolioClass.MANUAL_ASSETS -> Res.string.pfc_class_manual
    PortfolioClass.LIABILITIES -> Res.string.pfc_class_liabilities
}

private fun classHelp(cls: PortfolioClass): StringResource = when (cls) {
    PortfolioClass.BITCOIN -> Res.string.pfc_h_class_bitcoin
    PortfolioClass.INVESTMENTS -> Res.string.pfc_h_class_investments
    PortfolioClass.MANUAL_ASSETS -> Res.string.pfc_h_class_manual
    PortfolioClass.LIABILITIES -> Res.string.pfc_h_class_liabilities
}

/** PLAN §14.9 — the "?" text of a card label; null when the label speaks for itself. */
private fun helpFor(label: StringResource): StringResource? = PFC_HELP[label]

private val PFC_HELP: Map<StringResource, StringResource> = mapOf(
    Res.string.pfc_k_value to Res.string.pfc_h_value,
    Res.string.pfc_k_balance to Res.string.pfc_h_balance,
    Res.string.pfc_k_quantity to Res.string.pfc_h_quantity,
    Res.string.pfc_k_avg_price to Res.string.pfc_h_avg_price,
    Res.string.pfc_k_price to Res.string.pfc_h_price,
    Res.string.pfc_k_unrealized to Res.string.pfc_h_unrealized,
    Res.string.pfc_k_realized to Res.string.pfc_h_realized,
    Res.string.pfc_k_fx_effect to Res.string.pfc_h_fx_effect,
    Res.string.pfc_k_annual_cost to Res.string.pfc_h_annual_cost,
    Res.string.pfc_k_annual_interest to Res.string.pfc_h_annual_interest,
    Res.string.pfc_k_weight to Res.string.pfc_h_weight,
    Res.string.pfc_k_stack_share to Res.string.pfc_h_stack_share,
    Res.string.pfc_k_change to Res.string.pfc_h_change,
    Res.string.pfc_k_purchase to Res.string.pfc_h_purchase,
    Res.string.pfc_k_revaluation to Res.string.pfc_h_revaluation,
    Res.string.pfc_k_updated to Res.string.pfc_h_updated,
    Res.string.pfc_k_equity to Res.string.pfc_h_equity,
    Res.string.pfc_k_ltv to Res.string.pfc_h_ltv,
    Res.string.pfc_k_gross_yield to Res.string.pfc_h_gross_yield,
    Res.string.pfc_k_depreciation to Res.string.pfc_h_depreciation,
    Res.string.pfc_k_estimated_value to Res.string.pfc_h_estimated_value,
    Res.string.pfc_k_remaining to Res.string.pfc_h_remaining,
    Res.string.pfc_k_appraisal_age to Res.string.pfc_h_appraisal_age,
    Res.string.pfc_k_company_value to Res.string.pfc_h_company_value,
    Res.string.pfc_k_to_receive to Res.string.pfc_h_to_receive,
    Res.string.pfc_k_repaid to Res.string.pfc_h_repaid,
    Res.string.pfc_k_months_left to Res.string.pfc_h_months_left,
    Res.string.pfc_k_utilization to Res.string.pfc_h_utilization,
    Res.string.pfc_k_next_statement to Res.string.pfc_h_next_statement,
    Res.string.pfc_k_term_elapsed to Res.string.pfc_h_term_elapsed,
    Res.string.pfc_k_freshness to Res.string.pfc_h_freshness,
    Res.string.pfb_cost to Res.string.pfb_h_cost,
    Res.string.pfb_unrealized to Res.string.pfc_h_unrealized,
    Res.string.pfb_vs_hodl to Res.string.pfb_h_vs_hodl,
)

private fun classGlyph(cls: PortfolioClass): String = when (cls) {
    PortfolioClass.BITCOIN -> "₿"
    PortfolioClass.INVESTMENTS -> "▲"
    PortfolioClass.MANUAL_ASSETS -> "■"
    PortfolioClass.LIABILITIES -> "\u2212" // class mark, not an amount sign
}

@Composable
private fun GlyphTile(glyph: String, color: Color, size: Int = 32) {
    Box(
        modifier = Modifier.size(size.dp).clip(RoundedCornerShape(8.dp)).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = BhodlTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = BhodlTheme.colors.background)
    }
}

/** Proposal B — the second line of a class card ("4 holdings · 7 lots · FIFO"). */
@Composable
private fun classMeta(
    cls: PortfolioClass,
    sheet: PortfolioSheet,
    investments: List<TrackingInvestmentFfi>,
    lotsByInvestmentId: Map<Long, List<TrackingInvestmentLotFfi>>,
    nowUnix: Long,
): String {
    val rows = sheet.sections.firstOrNull { it.section == cls.section }?.rows.orEmpty()
    return when (cls) {
        PortfolioClass.BITCOIN -> stringResource(Res.string.pfb_meta_wallets, rows.size)
        PortfolioClass.INVESTMENTS -> {
            val ids = rows.mapNotNull { it.recordId }.toSet()
            val lots = ids.sumOf { id -> lotsByInvestmentId[id].orEmpty().count { it.quantity > 0.0 } }
            val method = investments.filter { it.id in ids }.map { it.costBasisMethod.uppercase() }.distinct().singleOrNull()
            listOfNotNull(stringResource(Res.string.pfb_meta_holdings, rows.size, lots), method).joinToString(" · ")
        }
        PortfolioClass.MANUAL_ASSETS -> {
            val stale = rows.count { r -> r.updatedUnix?.let { nowUnix - it > 90L * 86_400L } == true }
            val items = stringResource(Res.string.pfb_meta_items, rows.size)
            if (stale > 0) items + " · " + stringResource(Res.string.pfb_meta_stale, stale) else items
        }
        PortfolioClass.LIABILITIES -> {
            val gross = sheet.sections.filter { it.subtotalSat > 0L }.sumOf { it.subtotalSat }
            val debt = kotlin.math.abs(rows.sumOf { it.valueSat ?: 0L })
            val items = stringResource(Res.string.pfb_meta_items, rows.size)
            if (gross > 0L && debt > 0L) {
                items + " · " + stringResource(Res.string.pfb_meta_leverage, formatPct(debt * 100.0 / gross, signed = false))
            } else {
                items
            }
        }
    }
}

/** Top-three values in a class card: "120.0M" in sats, the full amount otherwise or when masked. */
@Composable
private fun compactAmount(sat: Long): String {
    if (amountsMasked() || SettingsStore.btcUnit.value != io.bhodl.shared.BtcUnit.SAT) return displayAmount(sat)
    val abs = kotlin.math.abs(sat)
    val body = when {
        abs >= 1_000_000L -> String.format(Locale.getDefault(), "%.1fM", abs / 1_000_000.0)
        abs >= 1_000L -> String.format(Locale.getDefault(), "%.1fk", abs / 1_000.0)
        else -> abs.toString()
    }
    return amountSign(sat, plusForPositive = false) + body
}

/**
 * The number on each class card: an asset class as a share of the total
 * assets (they add up to 100%, like the ring), the debts as a share of
 * the assets (leverage). Not of the net worth: with debts the asset
 * classes would add up to more than 100%. Null without assets.
 */
internal fun classShares(sheet: PortfolioSheet): Map<PortfolioClass, Double> {
    val subtotal = PortfolioClass.entries.associateWith { cls ->
        sheet.sections.firstOrNull { it.section == cls.section }?.subtotalSat ?: 0L
    }
    val assets = subtotal.filterKeys { it != PortfolioClass.LIABILITIES }.values.filter { it > 0L }.sum()
    if (assets <= 0L) return emptyMap()
    return subtotal.mapNotNull { (cls, v) ->
        val amount = if (cls == PortfolioClass.LIABILITIES) kotlin.math.abs(v) else v.coerceAtLeast(0L)
        cls to amount * 100.0 / assets
    }.toMap()
}

@Composable
private fun ClassCard(
    cls: PortfolioClass,
    section: SheetSectionModel?,
    /** Share of the total assets; for the debts, the leverage (see [classShares]). */
    share: Double?,
    meta: String,
    points: List<Pair<Long, Double>>,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = classColor(cls)
    val shape = RoundedCornerShape(14.dp)
    val rows = section?.rows.orEmpty()
    Column(
        modifier = modifier
            .clip(shape)
            .background(if (selected) color.copy(alpha = 0.05f).compositeOver(BhodlTheme.colors.surface) else BhodlTheme.colors.surface)
            .border(1.dp, if (selected) color else BhodlTheme.colors.outline, shape)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 18.dp, vertical = 16.dp)
            .testTag(PortfolioCardsTags.classCard(cls)),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlyphTile(classGlyph(cls), color, size = 34)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(classLabel(cls)), style = BhodlTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = BhodlTheme.colors.textPrimary)
                Text(meta, style = BhodlTheme.typography.labelMedium, color = BhodlTheme.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            share?.let {
                val pct = formatPct(it, signed = false)
                Text(
                    if (cls == PortfolioClass.LIABILITIES) stringResource(Res.string.pfb_meta_leverage, pct) else pct,
                    style = BhodlTheme.typography.mono,
                    color = BhodlTheme.colors.textSecondary,
                    modifier = Modifier.testTag(PortfolioCardsTags.classShare(cls)),
                )
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val subtotal = section?.subtotalSat ?: 0L
                Text(
                    displayAmount(subtotal),
                    style = BhodlTheme.typography.amountMedium.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
                    color = if (cls == PortfolioClass.LIABILITIES) BhodlTheme.colors.financialNegative else BhodlTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                section?.changeSat?.let { ch ->
                    val base = subtotal - ch
                    val pct = if (base != 0L) " · " + formatPct(ch * 100.0 / kotlin.math.abs(base), signed = true) else ""
                    Text(displaySignedAmount(ch) + pct, style = BhodlTheme.typography.mono, color = pnlColor(ch.toDouble()))
                }
            }
            if (points.size >= 2) {
                // Proposal B — a plain line, no area fill.
                androidx.compose.foundation.Canvas(Modifier.width(140.dp).height(44.dp)) {
                    val ys = points.map { it.second }
                    val min = ys.min()
                    val range = (ys.max() - min).takeIf { it > 0.0 } ?: 1.0
                    val path = androidx.compose.ui.graphics.Path()
                    points.forEachIndexed { i, (_, v) ->
                        val x = i * size.width / (points.size - 1)
                        val y = size.height - ((v - min) / range).toFloat() * (size.height - 4.dp.toPx()) - 2.dp.toPx()
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(BhodlTheme.colors.outline))
            Spacer(Modifier.height(4.dp))
            rows.sortedByDescending { kotlin.math.abs(it.valueSat ?: 0L) }.take(3).forEach { r ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(r.name, style = BhodlTheme.typography.labelMedium, color = BhodlTheme.colors.textSecondary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(r.valueSat?.let { compactAmount(it) } ?: "—", style = BhodlTheme.typography.mono, color = BhodlTheme.colors.textTertiary)
                }
            }
        }
    }
}

/** Proposal B — numbers of the class side panel. */
internal data class ClassSummary(
    /** Σ cost of the items still held; null when unknown or mixed currencies. */
    val costFiat: Double?,
    val costCurrency: String?,
    /** (value − cost) / cost over the items with both, in percent. */
    val unrealizedPct: Double?,
    /** Value now in sat vs the sat spent at purchase, in percent (investments only). */
    val vsHodlPct: Double?,
    /** Per row: unrealized % (investments, manual assets with a purchase
     *  value) or the change over the period (the rest). */
    val rowPl: Map<String, Double?>,
    /** Debts: share already repaid, in percent, when the original amount is known. */
    val rowRepaidPct: Map<String, Double> = emptyMap(),
)

internal fun classSummary(
    cls: PortfolioClass,
    rows: List<SheetRow>,
    investments: List<TrackingInvestmentFfi>,
    lotsByInvestmentId: Map<Long, List<TrackingInvestmentLotFfi>>,
    manualAssets: List<TrackingManualAssetFfi>,
    liabilities: List<TrackingLiabilityFfi> = emptyList(),
    details: PortfolioDetails = PortfolioDetails(),
): ClassSummary {
    fun periodPct(r: SheetRow): Double? {
        val v = r.valueSat ?: return null
        val ch = r.changeSat ?: return null
        val base = v - ch
        return if (base != 0L) ch * 100.0 / kotlin.math.abs(base) else null
    }
    return when (cls) {
        PortfolioClass.INVESTMENTS -> {
            val byId = investments.associateBy { it.id }
            var cost = 0.0
            var gain = 0.0
            var costWithPrice = 0.0
            var costSat = 0.0
            var valueSat = 0L
            val ccys = mutableSetOf<String>()
            val pl = rows.associate { r ->
                val inv = r.recordId?.let { byId[it] } ?: return@associate r.key to null
                val lots = lotsByInvestmentId[inv.id].orEmpty()
                val c = effectiveCostBasis(inv, lots)
                val price = inv.fetchedCurrentValue ?: inv.manualCurrentValue
                val valueFiat = price?.let { it * effectiveQuantity(inv, lots) }
                ccys += inv.costBasisCurrency.trim().uppercase()
                cost += c
                if (valueFiat != null && c > 0.0) {
                    gain += valueFiat - c
                    costWithPrice += c
                }
                // vs HODL: the sats each open lot cost on its purchase day.
                val open = lots.filter { it.quantity > 0.0 }
                val rated = open.all { (it.btcPerUnitAtPurchase ?: 0.0).let { x -> x > 0.0 && x.isFinite() } }
                if (open.isNotEmpty() && rated && r.valueSat != null) {
                    costSat += open.sumOf { it.quantity * it.pricePerUnit / it.btcPerUnitAtPurchase!! * 100_000_000.0 }
                    valueSat += r.valueSat
                }
                r.key to valueFiat?.takeIf { c > 0.0 }?.let { (it - c) / c * 100.0 }
            }
            val single = ccys.singleOrNull()
            ClassSummary(
                costFiat = cost.takeIf { single != null && it > 0.0 },
                costCurrency = single,
                unrealizedPct = if (costWithPrice > 0.0) gain / costWithPrice * 100.0 else null,
                vsHodlPct = if (costSat > 0.0) (valueSat - costSat) / costSat * 100.0 else null,
                rowPl = pl,
            )
        }
        PortfolioClass.MANUAL_ASSETS -> {
            val byId = manualAssets.associateBy { it.id }
            var cost = 0.0
            var value = 0.0
            val ccys = mutableSetOf<String>()
            val pl = rows.associate { r ->
                val a = r.recordId?.let { byId[it] }
                val p = a?.purchaseValueFiat?.takeIf { it > 0.0 }
                if (a != null && p != null) {
                    cost += p
                    value += a.valueFiat
                    ccys += a.currency.trim().uppercase()
                    r.key to (a.valueFiat - p) / p * 100.0
                } else {
                    r.key to periodPct(r)
                }
            }
            val single = ccys.singleOrNull()
            ClassSummary(
                costFiat = cost.takeIf { single != null && it > 0.0 },
                costCurrency = single,
                unrealizedPct = if (single != null && cost > 0.0) (value - cost) / cost * 100.0 else null,
                vsHodlPct = null,
                rowPl = pl,
            )
        }
        PortfolioClass.LIABILITIES -> {
            val byId = liabilities.associateBy { it.id }
            val repaid = rows.mapNotNull { r ->
                val l = r.recordId?.let { byId[it] } ?: return@mapNotNull null
                val original = details.liabilities[l.id]?.get("original_amount_sat")?.toLongOrNull()?.takeIf { it > 0L }
                    ?: return@mapNotNull null
                r.key to ((1.0 - l.amountSat.toDouble() / original) * 100.0).coerceIn(0.0, 100.0)
            }.toMap()
            ClassSummary(null, null, null, null, rows.associate { it.key to periodPct(it) }, repaid)
        }
        PortfolioClass.BITCOIN -> ClassSummary(null, null, null, null, rows.associate { it.key to periodPct(it) })
    }
}

/** Proposal B — title, net worth with its change and the class ring. */
@Composable
private fun PortfolioHero(sheet: PortfolioSheet, period: TrackingPeriod, actions: @Composable () -> Unit) {
    // The asset classes only: the debts show as leverage on their card.
    val shares = sheet.sections.mapNotNull { s ->
        PortfolioClass.entries.firstOrNull { it.section == s.section && it != PortfolioClass.LIABILITIES }
            ?.let { it to s.subtotalSat }
    }.filter { it.second > 0L }
    val total = shares.sumOf { it.second }.coerceAtLeast(1L)
    val colors = shares.map { classColor(it.first) }
    val track = BhodlTheme.colors.surfaceVariant
    val ringLabel = shares.map { stringResource(classLabel(it.first)) + " " + (it.second * 100 / total) + "%" }.joinToString()
    Row(
        Modifier.fillMaxWidth().testTag(PortfolioCardsTags.HERO),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(Res.string.pfb_title),
                    style = BhodlTheme.typography.titleMedium.copy(fontSize = 26.sp, fontWeight = FontWeight.Bold),
                    color = BhodlTheme.colors.textPrimary,
                )
                actions()
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    displayAmount(sheet.netWorthSat),
                    style = BhodlTheme.typography.amountMedium.copy(fontSize = 30.sp, fontWeight = FontWeight.SemiBold),
                    color = BhodlTheme.colors.textPrimary,
                )
                Text(
                    stringResource(Res.string.pfb_net),
                    style = BhodlTheme.typography.mono.copy(fontSize = 15.sp),
                    color = BhodlTheme.colors.textSecondary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            sheet.netWorthChangeSat?.let { ch ->
                val base = sheet.netWorthSat - ch
                val pct = if (base != 0L) " · " + formatPct(ch * 100.0 / kotlin.math.abs(base), signed = true) else ""
                Text(
                    displaySignedAmount(ch) + pct + " " + stringResource(Res.string.pfb_in_period, stringResource(period.labelRes)),
                    style = BhodlTheme.typography.mono,
                    color = pnlColor(ch.toDouble()),
                )
            }
        }
        if (shares.isNotEmpty()) {
            androidx.compose.foundation.Canvas(
                Modifier.size(132.dp).semantics { contentDescription = ringLabel },
            ) {
                val stroke = size.width * 5f / 42f
                val inset = size.width * (21f - 15.9f) / 42f
                val arcSize = androidx.compose.ui.geometry.Size(size.width - 2 * inset, size.height - 2 * inset)
                val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
                drawArc(track, 0f, 360f, false, topLeft, arcSize, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                var start = -90f
                shares.forEachIndexed { i, (_, v) ->
                    val sweep = v.toFloat() / total * 360f
                    drawArc(colors[i], start, sweep, false, topLeft, arcSize, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                    start += sweep
                }
            }
        }
    }
}

/** Proposal B — the "add" shortcuts under the class cards. */
@Composable
private fun AddButtonsRow(
    onAddInvestment: () -> Unit,
    onAddManualAsset: () -> Unit,
    onAddLiability: () -> Unit,
    onAddWallet: (() -> Unit)?,
) {
    val items = buildList {
        add(Triple(Res.string.pfb_add_investment, onAddInvestment, PortfolioCardsTags.ADD_INVESTMENT))
        add(Triple(Res.string.pfb_add_manual, onAddManualAsset, PortfolioCardsTags.ADD_MANUAL))
        add(Triple(Res.string.pfb_add_liability, onAddLiability, PortfolioCardsTags.ADD_LIABILITY))
        onAddWallet?.let { add(Triple(Res.string.pfb_add_wallet, it, PortfolioCardsTags.ADD_WALLET)) }
    }
    val dash = BhodlTheme.colors.outline
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items.forEach { (label, action, tag) ->
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .drawBehind {
                        val r = 10.dp.toPx()
                        drawRoundRect(
                            color = dash,
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = 1.dp.toPx(),
                                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())),
                            ),
                        )
                    }
                    .clickable(role = Role.Button, onClick = action)
                    .heightIn(min = 44.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .testTag(tag),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(label), style = BhodlTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = BhodlTheme.colors.textSecondary)
            }
        }
    }
}

/** Proposal B — one of the four figures of the class panel. */
@Composable
private fun PanelKpi(k: CardKpi, modifier: Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(BhodlTheme.colors.background)
            .border(1.dp, BhodlTheme.colors.outline, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(k.label).uppercase(),
                style = BhodlTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = BhodlTheme.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            helpFor(k.label)?.let { HelpTip(stringResource(it)) }
        }
        Text(
            k.value?.let { formatCardValue(it) } ?: "—",
            style = BhodlTheme.typography.mono.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
            color = when (if (amountsMasked()) Tone.NEUTRAL else k.tone) {
                Tone.POSITIVE -> BhodlTheme.colors.financialPositive
                Tone.NEGATIVE -> BhodlTheme.colors.financialNegative
                Tone.ACCENT -> BhodlTheme.colors.brandPrimary
                Tone.NEUTRAL -> BhodlTheme.colors.textPrimary
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Proposal B — the selected class: four figures, its items by weight, actions. */
@Composable
private fun ClassDetailPanel(
    cls: PortfolioClass,
    section: SheetSectionModel?,
    rows: List<SheetRow>,
    summary: ClassSummary,
    onOpen: (String) -> Unit,
    onClose: () -> Unit,
    /** Investments only: pick the holding, then record a lot / a sale. */
    investmentChoices: List<SheetRow> = emptyList(),
    onRecordBuy: ((Long) -> Unit)?,
    onRecordSell: ((Long) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val color = classColor(cls)
    fun tone(v: Double?): Tone = when {
        v == null || v == 0.0 -> Tone.NEUTRAL
        v > 0.0 -> Tone.POSITIVE
        else -> Tone.NEGATIVE
    }
    val closeLabel = stringResource(Res.string.common_close)
    val subtotal = section?.subtotalSat ?: 0L
    val kpis = listOf(
        CardKpi(Res.string.pfc_k_value, CardValue.Sat(subtotal), if (cls == PortfolioClass.LIABILITIES) Tone.NEGATIVE else Tone.NEUTRAL),
        CardKpi(Res.string.pfb_cost, summary.costFiat?.let { CardValue.Fiat(it, summary.costCurrency.orEmpty()) }),
        CardKpi(Res.string.pfb_unrealized, summary.unrealizedPct?.let { CardValue.Pct(it, signed = true) }, tone(summary.unrealizedPct)),
        CardKpi(Res.string.pfb_vs_hodl, summary.vsHodlPct?.let { CardValue.Pct(it, signed = true) }, tone(summary.vsHodlPct)),
    )
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(BhodlTheme.colors.surface)
            .border(1.dp, BhodlTheme.colors.outline, RoundedCornerShape(14.dp))
            .padding(20.dp)
            .testTag(PortfolioCardsTags.PANEL),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(Res.string.pfb_detail).uppercase(), style = BhodlTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = color)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(classLabel(cls)),
                        style = BhodlTheme.typography.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                        color = BhodlTheme.colors.textPrimary,
                    )
                    HelpTip(stringResource(classHelp(cls)))
                }
            }
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, BhodlTheme.colors.outline, RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClick = onClose)
                    .semantics { contentDescription = closeLabel }
                    .testTag(PortfolioCardsTags.PANEL_CLOSE),
                contentAlignment = Alignment.Center,
            ) {
                Text("×", style = BhodlTheme.typography.titleMedium, color = BhodlTheme.colors.textSecondary)
            }
        }
        kpis.chunked(2).forEach { pair ->
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { PanelKpi(it, Modifier.weight(1f).fillMaxHeight()) }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.pfb_rows_title).uppercase(), style = BhodlTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = BhodlTheme.colors.textSecondary)
            if (rows.isEmpty()) {
                Text(
                    stringResource(Res.string.pfc_empty_class),
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textSecondary,
                    modifier = Modifier.testTag(PortfolioCardsTags.EMPTY_CLASS),
                )
            }
            val total = rows.sumOf { kotlin.math.abs(it.valueSat ?: 0L) }.coerceAtLeast(1L)
            val repaidLabel = stringResource(Res.string.pfb_repaid_short)
            rows.forEach { r ->
                val w = kotlin.math.abs(r.valueSat ?: 0L).toDouble() / total
                val repaid = summary.rowRepaidPct[r.key]
                val pl = summary.rowPl[r.key]
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, BhodlTheme.colors.outline, RoundedCornerShape(10.dp))
                        .clickable(role = Role.Button) { onOpen(r.key) }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .testTag(PortfolioCardsTags.item(r.key)),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(r.name, style = BhodlTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = BhodlTheme.colors.textPrimary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(r.valueSat?.let { displayAmount(it) } ?: "—", style = BhodlTheme.typography.mono, color = BhodlTheme.colors.textPrimary)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(BhodlTheme.colors.surfaceVariant)) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(w.toFloat()).background(color))
                        }
                        Text(
                            formatPct(w * 100.0, signed = false),
                            style = BhodlTheme.typography.labelMedium,
                            color = BhodlTheme.colors.textSecondary,
                            modifier = Modifier.width(52.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        )
                        Text(
                            when {
                                repaid != null -> formatPct(repaid, signed = false) + " " + repaidLabel
                                pl != null -> formatPct(pl, signed = true)
                                else -> "—"
                            },
                            style = BhodlTheme.typography.labelMedium,
                            color = when {
                                repaid != null -> BhodlTheme.colors.financialPositive
                                pl != null -> pnlColor(pl)
                                else -> BhodlTheme.colors.textTertiary
                            },
                            modifier = Modifier.width(96.dp),
                            maxLines = 1,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        )
                    }
                }
            }
        }
        if (onRecordBuy != null && onRecordSell != null && investmentChoices.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PickHoldingButton(Res.string.pfb_record_buy, investmentChoices, filled = true, color = color, onPick = onRecordBuy, tag = PortfolioCardsTags.PANEL_BUY, modifier = Modifier.weight(1f))
                PickHoldingButton(Res.string.pfb_record_sell, investmentChoices, filled = false, color = color, onPick = onRecordSell, tag = PortfolioCardsTags.PANEL_SELL, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** "Record purchase / sale": one holding opens it straight away, more ask which one. */
@Composable
private fun PickHoldingButton(
    label: StringResource,
    choices: List<SheetRow>,
    filled: Boolean,
    color: Color,
    onPick: (Long) -> Unit,
    tag: String,
    modifier: Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        val click = {
            val only = choices.singleOrNull()?.recordId
            if (only != null) onPick(only) else open = true
        }
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (filled) color else Color.Transparent)
                .border(1.dp, if (filled) color else BhodlTheme.colors.outline, RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClick = click)
                .heightIn(min = 44.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag(tag),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(label),
                style = BhodlTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = if (filled) BhodlTheme.colors.background else BhodlTheme.colors.textPrimary,
            )
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { r ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(r.name) },
                    onClick = { open = false; r.recordId?.let(onPick) },
                )
            }
        }
    }
}

@Composable
private fun ItemDetail(
    row: SheetRow,
    sheet: PortfolioSheet,
    walletGroups: List<GroupedWallet>,
    investments: List<TrackingInvestmentFfi>,
    lotsByInvestmentId: Map<Long, List<TrackingInvestmentLotFfi>>,
    sellsByInvestmentId: Map<Long, List<TrackingInvestmentSellFfi>>,
    disposalsByInvestmentId: Map<Long, List<TrackingLotDisposalFfi>>,
    manualAssets: List<TrackingManualAssetFfi>,
    liabilities: List<TrackingLiabilityFfi>,
    details: PortfolioDetails,
    satToFiat: (Long, String) -> Double?,
    fiatCode: String,
    nowUnix: Long,
    onEditInvestment: (TrackingInvestmentFfi) -> Unit,
    onEditManualAsset: (TrackingManualAssetFfi) -> Unit,
    onEditLiability: (TrackingLiabilityFfi) -> Unit,
    onEditDetails: (DetailsEditRequest) -> Unit,
    onAddLot: (Long) -> Unit,
    onEditLot: (TrackingInvestmentLotFfi) -> Unit,
    onSell: (Long) -> Unit,
    onDeleteSell: (TrackingInvestmentSellFfi) -> Unit,
    modifier: Modifier,
) {
    val rowDetails = details.forRow(row.section, row.recordId)
    val assetName: (String) -> String? = { id -> manualAssets.firstOrNull { it.id.toString() == id }?.name }
    var subtitle: String? = null
    var onEdit: (() -> Unit)? = null
    var investment: TrackingInvestmentFfi? = null
    val model: DetailCardModel = when (row.section) {
        SheetSection.WALLETS -> {
            val stack = sheet.sections.first { it.section == SheetSection.WALLETS }.subtotalSat
            subtitle = row.kind
            walletCardModel(row, stack)
        }
        SheetSection.INVESTMENTS -> {
            val inv = investments.first { it.id == row.recordId }
            investment = inv
            subtitle = listOfNotNull(inv.ticker, inv.costBasisCurrency).joinToString(" · ")
            onEdit = { onEditInvestment(inv) }
            investmentCardModel(
                inv,
                lotsByInvestmentId[inv.id].orEmpty(),
                disposalsByInvestmentId[inv.id].orEmpty(),
                rowDetails,
                row,
            )
        }
        SheetSection.MANUAL_ASSETS -> {
            val a = manualAssets.first { it.id == row.recordId }
            subtitle = a.displayLabel
            onEdit = { onEditManualAsset(a) }
            manualAssetCardModel(
                a, rowDetails, row, nowUnix,
                linkedDebtFiat = linkedDebtFiat(a.id, liabilities, details) { sat -> satToFiat(sat, a.currency) },
            )
        }
        SheetSection.LIABILITIES -> {
            val l = liabilities.first { it.id == row.recordId }
            subtitle = l.displayLabel
            onEdit = { onEditLiability(l) }
            liabilityCardModel(l, rowDetails, nowUnix, fiatCode, assetName)
        }
    }
    val cls = model.category.cls
    val color = classColor(cls)
    Column(
        modifier = modifier.bhodlCard().padding(BhodlTheme.spacing.md).testTag(PortfolioCardsTags.DETAIL),
        verticalArrangement = Arrangement.spacedBy(BhodlTheme.spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlyphTile(model.category.glyph, color, size = 40)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    row.name,
                    style = BhodlTheme.typography.headlineMedium,
                    color = BhodlTheme.colors.textPrimary,
                    modifier = Modifier.testTag(PortfolioCardsTags.DETAIL_TITLE),
                )
                Text(
                    listOfNotNull(stringResource(model.category.label), subtitle?.takeIf { it.isNotBlank() }).joinToString(" · "),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textSecondary,
                )
            }
            onEdit?.let { edit ->
                TextButton(onClick = edit, modifier = Modifier.testTag(PortfolioCardsTags.EDIT)) {
                    Text(stringResource(Res.string.pfc_edit))
                }
            }
            if (model.category.fields.isNotEmpty() && row.recordId != null) {
                TextButton(
                    onClick = {
                        onEditDetails(DetailsEditRequest(model.category, row.recordId, row.name, rowDetails))
                    },
                    modifier = Modifier.testTag(PortfolioCardsTags.EDIT_DETAILS),
                ) { Text(stringResource(Res.string.pfc_details_edit)) }
            }
        }
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val perRow = if (maxWidth < 520.dp) 2 else 4
            Column(verticalArrangement = Arrangement.spacedBy(BhodlTheme.spacing.sm)) {
                model.kpis.withIndex().chunked(perRow).forEach { chunk ->
                    Row(horizontalArrangement = Arrangement.spacedBy(BhodlTheme.spacing.sm)) {
                        chunk.forEach { (i, k) -> KpiTile(k, Modifier.weight(1f).testTag(PortfolioCardsTags.kpi(i))) }
                    }
                }
            }
        }
        model.meter?.let { m ->
            Column(modifier = Modifier.testTag(PortfolioCardsTags.METER), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row {
                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(m.label), style = BhodlTheme.typography.labelMedium, color = BhodlTheme.colors.textSecondary)
                        helpFor(m.label)?.let { HelpTip(stringResource(it)) }
                    }
                    Text(formatCardValue(m.value), style = BhodlTheme.typography.mono, color = BhodlTheme.colors.textSecondary)
                }
                Box(
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                        .background(BhodlTheme.colors.surfaceVariant),
                ) {
                    // Repaid is good news even on a debt.
                    val fill = if (m.label == Res.string.pfc_k_repaid) BhodlTheme.colors.financialPositive else color
                    Box(Modifier.fillMaxHeight().fillMaxWidth(m.fraction.toFloat()).background(fill))
                }
            }
        }
        Column(modifier = Modifier.testTag(PortfolioCardsTags.ATTRS)) {
            if (model.attrs.isEmpty()) {
                Text(
                    stringResource(if (model.category.fields.isEmpty()) Res.string.pfc_details_none else Res.string.pfc_details_empty),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textTertiary,
                )
            }
            model.attrs.forEach { a ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(stringResource(a.label), style = BhodlTheme.typography.bodyMedium, color = BhodlTheme.colors.textSecondary, modifier = Modifier.weight(1f))
                    Text(formatCardValue(a.value), style = BhodlTheme.typography.bodyMedium, color = BhodlTheme.colors.textPrimary)
                }
            }
        }
        investment?.let { inv ->
            InvestmentActivity(
                inv = inv,
                lots = lotsByInvestmentId[inv.id].orEmpty(),
                sells = sellsByInvestmentId[inv.id].orEmpty(),
                disposals = disposalsByInvestmentId[inv.id].orEmpty(),
                onAddLot = { onAddLot(inv.id) },
                onEditLot = onEditLot,
                onSell = { onSell(inv.id) },
                onDeleteSell = onDeleteSell,
            )
        }
    }
}

@Composable
private fun KpiTile(k: CardKpi, modifier: Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(BhodlTheme.colors.background)
            .border(1.dp, BhodlTheme.colors.outline, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(k.label).uppercase(),
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            helpFor(k.label)?.let { HelpTip(stringResource(it)) }
        }
        Text(
            k.value?.let { formatCardValue(it) } ?: "—",
            style = BhodlTheme.typography.amountSmall,
            color = when (if (amountsMasked() && k.tone != Tone.ACCENT) Tone.NEUTRAL else k.tone) {
                Tone.POSITIVE -> BhodlTheme.colors.financialPositive
                Tone.NEGATIVE -> BhodlTheme.colors.financialNegative
                Tone.ACCENT -> BhodlTheme.colors.brandPrimary
                Tone.NEUTRAL -> BhodlTheme.colors.textPrimary
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Lots (open first) and sells of one investment, with their actions. */
@Composable
private fun InvestmentActivity(
    inv: TrackingInvestmentFfi,
    lots: List<TrackingInvestmentLotFfi>,
    sells: List<TrackingInvestmentSellFfi>,
    disposals: List<TrackingLotDisposalFfi>,
    onAddLot: () -> Unit,
    onEditLot: (TrackingInvestmentLotFfi) -> Unit,
    onSell: () -> Unit,
    onDeleteSell: (TrackingInvestmentSellFfi) -> Unit,
) {
    val ccy = inv.costBasisCurrency
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.pfc_lots), style = BhodlTheme.typography.titleMedium, color = BhodlTheme.colors.textPrimary, modifier = Modifier.weight(1f))
            TextButton(onClick = onAddLot, modifier = Modifier.testTag(PortfolioCardsTags.ADD_LOT)) { Text(stringResource(Res.string.pfc_add_lot)) }
            TextButton(onClick = onSell, enabled = lots.any { it.quantity > 0.0 }, modifier = Modifier.testTag(PortfolioCardsTags.SELL)) {
                Text(stringResource(Res.string.pfc_sell))
            }
        }
        lots.sortedBy { it.purchaseUnix }.forEach { lot ->
            val original = lot.originalQuantity ?: lot.quantity
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onEditLot(lot) }.padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(formatDay(lot.purchaseUnix), style = BhodlTheme.typography.mono, color = BhodlTheme.colors.textSecondary, modifier = Modifier.widthIn(min = 96.dp))
                Text(
                    stringResource(Res.string.pfc_lot_row, formatQuantity(lot.quantity), formatQuantity(original), formatFiatWithCode(lot.pricePerUnit, ccy)),
                    style = BhodlTheme.typography.mono,
                    color = if (lot.quantity > 0.0) BhodlTheme.colors.textPrimary else BhodlTheme.colors.textTertiary,
                    modifier = Modifier.weight(1f),
                )
                val price = inv.fetchedCurrentValue ?: inv.manualCurrentValue
                val pnl = price?.let { unrealizedPnl(listOf(lot), it, disposals) }
                if (pnl != null && lot.quantity > 0.0) {
                    Text(
                        signedFiat(pnl, ccy),
                        style = BhodlTheme.typography.mono,
                        color = pnlColor(pnl),
                    )
                }
            }
        }
        if (sells.isNotEmpty()) {
            Text(stringResource(Res.string.pfc_sells), style = BhodlTheme.typography.titleMedium, color = BhodlTheme.colors.textPrimary)
            sells.sortedByDescending { it.saleUnix }.forEach { s ->
                val gain = disposals.filter { it.sellId == s.id }.sumOf { it.gainLossFiat }
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(Res.string.pfc_sell_row, formatDay(s.saleUnix), formatQuantity(s.saleQty), formatFiatWithCode(s.salePricePerUnit, ccy)),
                        style = BhodlTheme.typography.mono,
                        color = BhodlTheme.colors.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        signedFiat(gain, ccy),
                        style = BhodlTheme.typography.mono,
                        color = pnlColor(gain),
                    )
                    TextButton(onClick = { onDeleteSell(s) }) { Text(stringResource(Res.string.pfc_delete_sell)) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Formatting
// ---------------------------------------------------------------------------

private fun formatPct(pct: Double, signed: Boolean): String {
    // Up to two decimals (TER 0.22 %, rate 3.25 %), trailing zeros dropped.
    val sep = java.text.DecimalFormatSymbols.getInstance(Locale.getDefault()).decimalSeparator
    val body = String.format(Locale.getDefault(), "%.2f", kotlin.math.abs(pct))
        .trimEnd('0').trimEnd(sep).ifEmpty { "0" } + "%"
    return amountSign(pct, plusForPositive = signed) + body
}

private fun signedFiat(amount: Double, ccy: String): String =
    amountSign(amount) + formatFiatWithCode(kotlin.math.abs(amount), ccy)

@Composable
private fun pnlColor(v: Double): Color = signedAmountColor(
    v,
    positive = BhodlTheme.colors.financialPositive,
    negative = BhodlTheme.colors.financialNegative,
    neutral = BhodlTheme.colors.textSecondary,
)

private fun formatDay(unix: Long): String =
    java.time.LocalDate.ofEpochDay(Math.floorDiv(unix, 86_400L))
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()))

private fun choiceLabel(field: String, option: String): StringResource? = when (choiceLabelKey(field, option)) {
    "pfc_opt_distribution_acc" -> Res.string.pfc_opt_distribution_acc
    "pfc_opt_distribution_dist" -> Res.string.pfc_opt_distribution_dist
    "pfc_opt_form_bar" -> Res.string.pfc_opt_form_bar
    "pfc_opt_form_coin" -> Res.string.pfc_opt_form_coin
    "pfc_opt_form_etc" -> Res.string.pfc_opt_form_etc
    "pfc_opt_form_other" -> Res.string.pfc_opt_form_other
    "pfc_opt_use_primary" -> Res.string.pfc_opt_use_primary
    "pfc_opt_use_rental" -> Res.string.pfc_opt_use_rental
    "pfc_opt_use_second" -> Res.string.pfc_opt_use_second
    "pfc_opt_use_land" -> Res.string.pfc_opt_use_land
    "pfc_opt_rate_type_fixed" -> Res.string.pfc_opt_rate_type_fixed
    "pfc_opt_rate_type_variable" -> Res.string.pfc_opt_rate_type_variable
    else -> null
}

@Composable
internal fun formatCardValue(v: CardValue): String = when (v) {
    is CardValue.Sat -> if (v.signed) displaySignedAmount(v.sat) else displayAmount(v.sat)
    is CardValue.Fiat -> if (v.signed) signedFiat(v.amount, v.currency) else formatFiatWithCode(v.amount, v.currency)
    is CardValue.Pct -> formatPct(v.pct, v.signed)
    is CardValue.Qty -> listOf(formatQuantity(v.qty), v.unit).filter { it.isNotBlank() }.joinToString(" ")
    is CardValue.Date -> v.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()))
    is CardValue.DaysAgo -> if (v.days == 0L) stringResource(Res.string.pfc_v_today) else stringResource(Res.string.pfc_v_days_ago, v.days)
    is CardValue.Months -> stringResource(Res.string.pfc_v_months, v.months)
    is CardValue.InDays -> if (v.days == 0L) stringResource(Res.string.pfc_v_today) else stringResource(Res.string.pfc_v_in_days, v.days)
    is CardValue.Text -> v.text
    is CardValue.Choice -> choiceLabel(v.field, v.option)?.let { stringResource(it) } ?: v.option
    is CardValue.YesNo -> stringResource(if (v.yes) Res.string.pfc_v_yes else Res.string.pfc_v_no)
}

// ---------------------------------------------------------------------------
// Details editor
// ---------------------------------------------------------------------------

/**
 * Editor of a row's kind-specific details ([DetailCategory.fields]).
 * Every field is optional; [onSave] receives the canonical JSON built by
 * [detailsToJson]. [linkableAssets] feeds the "linked asset" picker.
 */
@Composable
internal fun PortfolioDetailsDialog(
    request: DetailsEditRequest,
    linkableAssets: List<TrackingManualAssetFfi>,
    onDismiss: () -> Unit,
    onSave: (json: String) -> Unit,
) {
    val values = remember(request) { mutableStateMapOf<String, String>().apply { putAll(request.current) } }
    SlideOverPanel(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.pfc_details_title, request.name)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.testTag(PortfolioCardsTags.DETAILS_DIALOG),
            ) {
                Text(
                    stringResource(Res.string.pfc_details_hint, stringResource(request.category.label)),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textSecondary,
                )
                request.category.fields.forEach { f ->
                    val label = stringResource(f.label)
                    val tag = Modifier.testTag(PortfolioCardsTags.detailsField(f.key))
                    when (f.type) {
                        FieldType.BOOL -> Row(verticalAlignment = Alignment.CenterVertically, modifier = tag) {
                            Checkbox(
                                checked = values[f.key] == "true",
                                onCheckedChange = { values[f.key] = if (it) "true" else "" },
                            )
                            Text(label, style = BhodlTheme.typography.bodyMedium, color = BhodlTheme.colors.textPrimary)
                        }
                        FieldType.CHOICE -> ChoiceChips(
                            label = label,
                            options = f.options.map { it to (choiceLabel(f.key, it)?.let { r -> stringResource(r) } ?: it) },
                            selected = values[f.key],
                            onSelect = { values[f.key] = if (values[f.key] == it) "" else it },
                            modifier = tag,
                        )
                        FieldType.LINK_ASSET -> ChoiceChips(
                            label = label,
                            options = listOf("" to stringResource(Res.string.pfc_link_none)) +
                                linkableAssets.map { it.id.toString() to it.name },
                            selected = values[f.key].orEmpty(),
                            onSelect = { values[f.key] = it },
                            modifier = tag,
                        )
                        else -> OutlinedTextField(
                            value = values[f.key].orEmpty(),
                            onValueChange = { values[f.key] = it },
                            singleLine = true,
                            label = { Text(label) },
                            placeholder = { fieldPlaceholder(f.type)?.let { Text(it) } },
                            modifier = Modifier.fillMaxWidth().then(tag),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(detailsToJson(request.category, values)) },
                modifier = Modifier.testTag(PortfolioCardsTags.DETAILS_SAVE),
            ) { Text(stringResource(Res.string.pfc_details_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) } },
    )
}

private fun fieldPlaceholder(type: FieldType): String? = when (type) {
    FieldType.DATE -> "2027-03-14"
    FieldType.PERCENT -> "2,5"
    FieldType.DAY_OF_MONTH -> "15"
    else -> null
}

@Composable
private fun ChoiceChips(
    label: String,
    options: List<Pair<String, String>>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = BhodlTheme.typography.labelMedium, color = BhodlTheme.colors.textSecondary)
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { (value, text) ->
                val on = selected == value
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (on) BhodlTheme.colors.accentNeon.copy(alpha = 0.18f) else BhodlTheme.colors.surface)
                        .border(1.dp, if (on) BhodlTheme.colors.accentNeon else BhodlTheme.colors.outline, RoundedCornerShape(8.dp))
                        .clickable(role = Role.RadioButton) { onSelect(value) }
                        .semantics { this.selected = on }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(text, style = BhodlTheme.typography.labelMedium, color = if (on) BhodlTheme.colors.accentNeon else BhodlTheme.colors.textSecondary)
                }
            }
        }
    }
}
