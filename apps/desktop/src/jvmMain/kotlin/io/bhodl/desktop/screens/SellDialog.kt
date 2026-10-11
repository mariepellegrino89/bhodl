package io.bhodl.desktop.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.ui.DatePickerField
import io.bhodl.desktop.ui.SlideOverPanel
import io.bhodl.desktop.ui.displaySignedAmount
import io.bhodl.desktop.ui.formatFiatWithCode
import io.bhodl.desktop.ui.formatFiat
import io.bhodl.ffi.LotSelectionFfi
import io.bhodl.ffi.TrackingInvestmentFfi
import io.bhodl.ffi.TrackingInvestmentLotFfi
import io.bhodl.ffi.TrackingLotDisposalFfi
import io.bhodl.desktop.ui.money.filterDecimalChars
import io.bhodl.desktop.ui.money.parseDecimalInput
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.tracker_sell_compare_hint
import io.bhodl.shared.resources.tracker_sell_compare_title
import io.bhodl.shared.resources.tracker_sell_configured
import io.bhodl.shared.resources.tracker_sell_method_fifo
import io.bhodl.shared.resources.tracker_sell_method_hifo
import io.bhodl.shared.resources.tracker_sell_method_specific
import io.bhodl.shared.resources.tracker_sell_not_enough_lots
import io.bhodl.shared.resources.tracker_sell_override_note
import io.bhodl.shared.resources.tracker_sell_pick_lots
import io.bhodl.shared.resources.tracker_sell_realized_btc
import io.bhodl.shared.resources.tracker_sell_realized_fiat
import io.bhodl.shared.resources.tracker_sell_selected
import io.bhodl.ui.theme.BhodlTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToLong
import io.bhodl.shared.resources.common_cancel
import io.bhodl.shared.resources.common_no_rate
import io.bhodl.shared.resources.trp_fees_invalid
import io.bhodl.shared.resources.trp_fees_optional
import io.bhodl.shared.resources.trp_lot_label
import io.bhodl.shared.resources.trp_notes_optional
import io.bhodl.shared.resources.trp_sell_available
import io.bhodl.shared.resources.trp_sell_date
import io.bhodl.shared.resources.trp_sell_no_open_lot
import io.bhodl.shared.resources.trp_sell_oversell
import io.bhodl.shared.resources.trp_sell_pick_hint
import io.bhodl.shared.resources.trp_sell_price
import io.bhodl.shared.resources.trp_sell_qty
import io.bhodl.shared.resources.trp_sell_qty_max
import io.bhodl.shared.resources.trp_sell_record
import io.bhodl.shared.resources.trp_sell_selected
import io.bhodl.shared.resources.trp_sell_title

/**
 * Phase 7.9 — record a sell against the parent investment's lots.
 *
 * Validation in v1:
 *  - quantity > 0 and finite
 *  - quantity ≤ available (Σ remaining lots) — else the Save button
 *    stays disabled with an explicit "over-sell" message
 *  - sale price > 0 and finite
 *  - fees ≥ 0 and finite (0 when blank; unparseable → error, not 0)
 *  - date picked via [DatePickerField] (always a valid epoch)
 *
 * UX review 2026-10 (item 10) — lot-selection view. Before confirming,
 * FIFO, HIFO and Specific-ID are shown side by side ([compareLotMethods]):
 * the lots each method consumes and the realized P&L it books, in fiat
 * (operational/tax surface) and in BTC where both rates exist. The user
 * picks one; the default is the investment's configured method.
 *
 *  - Configured FIFO / HIFO kept → [onSave] gets `selection = null` and
 *    the registry orders the lots itself (same result as the preview).
 *  - Any other choice → [onSave] gets the explicit lots of that method
 *    (`record_sell_with_lots`); for Specific-ID they are the user's picks
 *    from the lot picker.
 */
@Composable
internal fun SellDialog(
    parentInvestment: TrackingInvestmentFfi,
    parentLots: List<TrackingInvestmentLotFfi>,
    parentCurrencySymbol: String,
    /** Past disposals of this investment — fallback only: the fee-per-unit
     *  basis uses each lot's registry `originalQuantity` when present. */
    parentDisposals: List<TrackingLotDisposalFfi> = emptyList(),
    /** `1 BTC = N <investment currency>` on a sale date (UTC day). The
     *  production default asks the historical rate repository; tests
     *  inject a constant. Null result → the BTC P&L line reads "no rate". */
    saleBtcRateAt: suspend (saleUnix: Long, currency: String) -> Double? = ::historicalBtcRate,
    onDismiss: () -> Unit,
    onSave: (
        salePricePerUnit: Double,
        saleQty: Double,
        fees: Double,
        saleUnix: Long,
        notes: String?,
        /** Explicit lot picks; null = let the registry apply the
         *  configured FIFO / HIFO ordering. */
        selection: List<LotSelectionFfi>?,
    ) -> Unit,
) {
    val configured = CostBasisMethod.fromWire(parentInvestment.costBasisMethod)
    var chosen by remember { mutableStateOf(configured) }
    val specificId = chosen == CostBasisMethod.SPECIFIC_ID
    var qtyStr by remember { mutableStateOf("") }
    // Specific-ID: raw text per lot id.
    var lotQtyStr by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var priceStr by remember { mutableStateOf("") }
    var feesStr by remember { mutableStateOf("") }
    var saleUnix by remember {
        mutableStateOf(System.currentTimeMillis() / 1000L)
    }
    var notesStr by remember { mutableStateOf("") }
    var saleBtcRate by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(saleUnix, parentInvestment.costBasisCurrency) {
        saleBtcRate = runCatching {
            saleBtcRateAt(saleUnix, parentInvestment.costBasisCurrency)
        }.getOrNull()
    }

    val selectionCheck: LotSelectionCheck? = if (specificId) {
        validateLotSelection(
            lots = parentLots,
            inputs = lotQtyStr.filterValues { it.isNotBlank() }
                .mapValues { (_, v) -> parseDecimalInput(v) },
            saleUnix = saleUnix,
        )
    } else null
    val picks: Map<Long, Double>? = (selectionCheck as? LotSelectionCheck.Valid)?.picks
    val qty: Double? = if (specificId) {
        (selectionCheck as? LotSelectionCheck.Valid)?.total
    } else {
        parseDecimalInput(qtyStr)
    }
    val price: Double? = parseDecimalInput(priceStr)
    // Blank fees = 0; anything typed must parse — an unparseable fee is
    // a validation error, never silently 0.
    val fees: Double? = if (feesStr.isBlank()) 0.0 else parseDecimalInput(feesStr)
    val feesInvalid = fees == null || !fees.isFinite() || fees < 0.0

    val available: Double = parentLots
        .filter { it.quantity > 0.0 && it.quantity.isFinite() }
        .sumOf { it.quantity }
    val overSell = qty != null && qty > available

    val comparison: Map<CostBasisMethod, LotMethodOutcome?>? =
        if (qty != null && qty > 0.0 && qty.isFinite() && price != null && fees != null && !feesInvalid) {
            compareLotMethods(
                lots = parentLots,
                saleQty = qty,
                salePricePerUnit = price,
                fees = fees,
                saleUnix = saleUnix,
                saleBtcRate = saleBtcRate,
                specificPicks = picks,
                disposals = parentDisposals,
            )
        } else null
    val chosenOutcome: LotMethodOutcome? = comparison?.get(chosen)

    val canSave = qty != null && qty > 0.0 && qty.isFinite() && !overSell &&
        price != null && price > 0.0 && price.isFinite() &&
        !feesInvalid && chosenOutcome != null

    SlideOverPanel(
        onDismissRequest = onDismiss,
        width = 680.dp,
        title = { Text(stringResource(Res.string.trp_sell_title, parentInvestment.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(Res.string.trp_sell_available, "${formatQuantity(available)} ${parentInvestment.unit}".trim()),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textSecondary,
                )
                if (specificId) {
                    LotPicker(
                        lots = lotsSortedForDisposal(openLotsAt(parentLots, saleUnix), chosen),
                        unit = parentInvestment.unit,
                        currencySymbol = parentCurrencySymbol,
                        values = lotQtyStr,
                        onValueChange = { id, v ->
                            lotQtyStr = lotQtyStr + (id to filterDecimalChars(v))
                        },
                    )
                    when (selectionCheck) {
                        is LotSelectionCheck.Invalid -> Text(
                            text = stringResource(selectionCheck.message, *selectionCheck.args.toTypedArray()),
                            style = BhodlTheme.typography.labelMedium,
                            color = BhodlTheme.colors.financialNegative,
                            modifier = Modifier.testTag(SellDialogTags.SELECTION_ERROR),
                        )
                        is LotSelectionCheck.Valid -> Text(
                            text = stringResource(Res.string.trp_sell_selected, "${formatQuantity(selectionCheck.total)} ${parentInvestment.unit}"),
                            style = BhodlTheme.typography.labelMedium,
                            color = BhodlTheme.colors.textSecondary,
                            modifier = Modifier.testTag(SellDialogTags.SELECTION_TOTAL),
                        )
                        else -> Text(
                            text = stringResource(Res.string.trp_sell_pick_hint),
                            style = BhodlTheme.typography.labelMedium,
                            color = BhodlTheme.colors.textTertiary,
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = qtyStr,
                        onValueChange = { qtyStr = filterDecimalChars(it) },
                        singleLine = true,
                        label = { Text(stringResource(Res.string.trp_sell_qty)) },
                        placeholder = { Text(stringResource(Res.string.trp_sell_qty_max, formatQuantity(available))) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                        ),
                        modifier = Modifier.fillMaxWidth().testTag(SellDialogTags.INPUT_QTY),
                    )
                }
                if (overSell) {
                    Text(
                        text = stringResource(Res.string.trp_sell_oversell, formatQuantity(available)),
                        style = BhodlTheme.typography.labelMedium,
                        color = BhodlTheme.colors.financialNegative,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = priceStr,
                        onValueChange = { priceStr = filterDecimalChars(it) },
                        singleLine = true,
                        label = { Text(stringResource(Res.string.trp_sell_price, parentCurrencySymbol)) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                        ),
                        modifier = Modifier.weight(1f).testTag(SellDialogTags.INPUT_PRICE),
                    )
                    OutlinedTextField(
                        value = feesStr,
                        onValueChange = { feesStr = filterDecimalChars(it) },
                        singleLine = true,
                        label = { Text(stringResource(Res.string.trp_fees_optional, parentCurrencySymbol)) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                        ),
                        modifier = Modifier.weight(1f).testTag(SellDialogTags.INPUT_FEES),
                    )
                }
                if (feesInvalid) {
                    Text(
                        text = stringResource(Res.string.trp_fees_invalid),
                        style = BhodlTheme.typography.labelMedium,
                        color = BhodlTheme.colors.financialNegative,
                    )
                }
                DatePickerField(
                    label = stringResource(Res.string.trp_sell_date),
                    epochSeconds = saleUnix,
                    onPick = { saleUnix = it },
                    modifier = Modifier.fillMaxWidth(),
                    testTagId = SellDialogTags.INPUT_DATE,
                )

                LotMethodComparisonView(
                    comparison = comparison,
                    configured = configured,
                    chosen = chosen,
                    onChoose = { chosen = it },
                    unit = parentInvestment.unit,
                    currency = parentInvestment.costBasisCurrency,
                )

                OutlinedTextField(
                    value = notesStr,
                    onValueChange = { notesStr = it },
                    label = { Text(stringResource(Res.string.trp_notes_optional)) },
                    modifier = Modifier.fillMaxWidth().testTag(SellDialogTags.INPUT_NOTES),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val outcome = chosenOutcome
                    if (canSave && outcome != null) {
                        val selection = sellSelectionFor(chosen, configured, outcome)
                        onSave(
                            price!!,
                            outcome.picks.values.sum(),
                            fees!!,
                            saleUnix,
                            notesStr.takeIf { it.isNotBlank() },
                            selection,
                        )
                    }
                },
                enabled = canSave,
                modifier = Modifier.testTag(SellDialogTags.SAVE),
            ) { Text(stringResource(Res.string.trp_sell_record)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
        },
    )
}

/**
 * What the dialog hands the registry for the [chosen] method: null when
 * the user keeps the investment's own FIFO / HIFO ordering (the registry
 * re-derives exactly the previewed lots), the explicit lots otherwise.
 * Specific-ID always needs the picks.
 */
internal fun sellSelectionFor(
    chosen: CostBasisMethod,
    configured: CostBasisMethod,
    outcome: LotMethodOutcome,
): List<LotSelectionFfi>? =
    if (chosen == configured && chosen != CostBasisMethod.SPECIFIC_ID) {
        null
    } else {
        outcome.consumptions.map { LotSelectionFfi(lotId = it.lot.id, qty = it.qty) }
    }

/** Production [SellDialog.saleBtcRateAt]: the historical BTC/<ccy> rate. */
private suspend fun historicalBtcRate(saleUnix: Long, currency: String): Double? =
    withContext(Dispatchers.IO) {
        runCatching {
            io.bhodl.desktop.prices.BtcRateHistoryClient.rateAt(
                saleUnix - Math.floorMod(saleUnix, 86_400L),
                currency.trim().uppercase(),
            )
        }.getOrNull()
    }

/**
 * The three methods side by side. Each column is selectable (radio
 * semantics); the chosen one is outlined and labelled "Selected" — the
 * state is never conveyed by colour alone.
 */
@Composable
private fun LotMethodComparisonView(
    comparison: Map<CostBasisMethod, LotMethodOutcome?>?,
    configured: CostBasisMethod,
    chosen: CostBasisMethod,
    onChoose: (CostBasisMethod) -> Unit,
    unit: String,
    currency: String,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.testTag(SellDialogTags.COMPARISON),
    ) {
        Text(
            text = stringResource(Res.string.tracker_sell_compare_title),
            style = BhodlTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = BhodlTheme.colors.textSecondary,
        )
        if (comparison == null) {
            Text(
                text = stringResource(Res.string.tracker_sell_compare_hint),
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.textTertiary,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (method in CostBasisMethod.entries) {
                MethodColumn(
                    method = method,
                    outcome = comparison?.get(method),
                    hasInputs = comparison != null,
                    isConfigured = method == configured,
                    isChosen = method == chosen,
                    onClick = { onChoose(method) },
                    unit = unit,
                    currency = currency,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
        if (chosen != configured) {
            Text(
                text = stringResource(Res.string.tracker_sell_override_note, methodLabel(configured)),
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.financialPending,
                modifier = Modifier.testTag(SellDialogTags.OVERRIDE_NOTE),
            )
        }
    }
}

@Composable
private fun methodLabel(method: CostBasisMethod): String = when (method) {
    CostBasisMethod.FIFO -> stringResource(Res.string.tracker_sell_method_fifo)
    CostBasisMethod.HIFO -> stringResource(Res.string.tracker_sell_method_hifo)
    CostBasisMethod.SPECIFIC_ID -> stringResource(Res.string.tracker_sell_method_specific)
}

@Composable
private fun MethodColumn(
    method: CostBasisMethod,
    outcome: LotMethodOutcome?,
    hasInputs: Boolean,
    isConfigured: Boolean,
    isChosen: Boolean,
    onClick: () -> Unit,
    unit: String,
    currency: String,
    modifier: Modifier = Modifier,
) {
    val shape = BhodlTheme.shapes.panel
    Column(
        modifier = modifier
            .border(
                width = if (isChosen) 2.dp else 1.dp,
                color = if (isChosen) BhodlTheme.colors.accentNeon else BhodlTheme.colors.outline,
                shape = shape,
            )
            .background(
                if (isChosen) BhodlTheme.colors.accentNeon.copy(alpha = 0.06f)
                else BhodlTheme.colors.surface,
                shape,
            )
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(10.dp)
            .testTag(SellDialogTags.method(method)),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = (if (isChosen) "◉ " else "○ ") + methodLabel(method),
                style = BhodlTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = BhodlTheme.colors.textPrimary,
            )
        }
        val tags = buildList {
            if (isChosen) add(stringResource(Res.string.tracker_sell_selected))
            if (isConfigured) add(stringResource(Res.string.tracker_sell_configured))
        }
        if (tags.isNotEmpty()) {
            Text(
                text = tags.joinToString(" · "),
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.textTertiary,
            )
        }
        when {
            outcome != null -> {
                for (c in outcome.consumptions) {
                    val bought = java.time.Instant.ofEpochSecond(c.lot.purchaseUnix)
                        .atZone(java.time.ZoneOffset.UTC).toLocalDate()
                    Text(
                        text = "#${c.lot.id} · $bought · ${formatQuantity(c.qty)} $unit".trim() +
                            " @ ${formatFiatWithCode(c.basisPerUnit, currency)}",
                        style = BhodlTheme.typography.labelMedium,
                        color = BhodlTheme.colors.textSecondary,
                        modifier = Modifier.testTag(SellDialogTags.consumed(method, c.lot.id)),
                    )
                }
                Text(
                    text = stringResource(Res.string.tracker_sell_realized_fiat),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textTertiary,
                    modifier = Modifier.padding(top = 4.dp),
                )
                val sign = io.bhodl.desktop.ui.amountSign(outcome.realizedFiat)
                Text(
                    text = "$sign${formatFiatWithCode(kotlin.math.abs(outcome.realizedFiat), currency)}",
                    style = BhodlTheme.typography.amountSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = pnlColor(outcome.realizedFiat),
                    modifier = Modifier.testTag(SellDialogTags.realizedFiat(method)),
                )
                Text(
                    text = stringResource(Res.string.tracker_sell_realized_btc),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textTertiary,
                )
                val btc = outcome.realizedBtc
                Text(
                    text = if (btc != null) displaySignedAmount((btc * 100_000_000.0).roundToLong()) else stringResource(Res.string.common_no_rate),
                    style = BhodlTheme.typography.amountSmall,
                    color = if (btc != null) pnlColor(btc) else BhodlTheme.colors.textTertiary,
                    modifier = Modifier.testTag(SellDialogTags.realizedBtc(method)),
                )
            }
            !hasInputs -> Text(
                text = "—",
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.textTertiary,
            )
            method == CostBasisMethod.SPECIFIC_ID -> Text(
                text = stringResource(Res.string.tracker_sell_pick_lots),
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.accentNeon,
            )
            else -> Text(
                text = stringResource(Res.string.tracker_sell_not_enough_lots),
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.financialNegative,
            )
        }
    }
}

@Composable
private fun pnlColor(v: Double) = io.bhodl.desktop.ui.signedAmountColor(
    value = v,
    positive = BhodlTheme.colors.financialPositive,
    negative = BhodlTheme.colors.financialNegative,
    neutral = BhodlTheme.colors.textSecondary,
)

/** Specific-ID lot picker: one quantity input per open lot. */
@Composable
private fun LotPicker(
    lots: List<TrackingInvestmentLotFfi>,
    unit: String,
    currencySymbol: String,
    values: Map<Long, String>,
    onValueChange: (Long, String) -> Unit,
) {
    if (lots.isEmpty()) {
        Text(
            text = stringResource(Res.string.trp_sell_no_open_lot),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.financialNegative,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (lot in lots) {
            val bought = java.time.Instant.ofEpochSecond(lot.purchaseUnix)
                .atZone(java.time.ZoneOffset.UTC).toLocalDate()
            OutlinedTextField(
                value = values[lot.id].orEmpty(),
                onValueChange = { onValueChange(lot.id, it) },
                singleLine = true,
                label = {
                    Text(
                        stringResource(Res.string.trp_lot_label, lot.id.toString()) +
                            " · $bought · ${formatQuantity(lot.quantity)} $unit " +
                            "@ ${formatFiat(lot.pricePerUnit)} $currencySymbol",
                    )
                },
                placeholder = { Text("0 – ${formatQuantity(lot.quantity)}") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                ),
                modifier = Modifier.fillMaxWidth().testTag(SellDialogTags.lotQty(lot.id)),
            )
        }
    }
}

internal object SellDialogTags {
    const val SAVE: String = "sell-dialog.save"
    const val SELECTION_ERROR: String = "sell-dialog.selection-error"
    const val SELECTION_TOTAL: String = "sell-dialog.selection-total"
    fun lotQty(lotId: Long): String = "sell-dialog.lot-qty.$lotId"
    const val INPUT_QTY: String = "sell-dialog.input-qty"
    const val INPUT_PRICE: String = "sell-dialog.input-price"
    const val INPUT_FEES: String = "sell-dialog.input-fees"
    const val INPUT_DATE: String = "sell-dialog.input-date"
    const val INPUT_NOTES: String = "sell-dialog.input-notes"
    const val COMPARISON: String = "sell-dialog.comparison"
    const val OVERRIDE_NOTE: String = "sell-dialog.override-note"
    fun method(m: CostBasisMethod): String = "sell-dialog.method.${m.wireValue}"
    fun consumed(m: CostBasisMethod, lotId: Long): String = "sell-dialog.consumed.${m.wireValue}.$lotId"
    fun realizedFiat(m: CostBasisMethod): String = "sell-dialog.realized-fiat.${m.wireValue}"
    fun realizedBtc(m: CostBasisMethod): String = "sell-dialog.realized-btc.${m.wireValue}"
}
