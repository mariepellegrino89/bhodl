package io.bhodl.desktop.screens

import io.bhodl.desktop.ui.money.filterDecimalChars
import io.bhodl.desktop.ui.money.formatDecimalInput
import io.bhodl.desktop.ui.money.parseDecimalInput
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import io.bhodl.desktop.ui.SlideOverPanel
import io.bhodl.desktop.ui.formatFiat
import io.bhodl.desktop.ui.money.Money
import io.bhodl.desktop.ui.money.MoneyDensity
import io.bhodl.desktop.ui.money.MoneyPrimary
import io.bhodl.desktop.ui.money.MoneySemantic
import io.bhodl.desktop.ui.money.MoneyValue
import io.bhodl.ffi.TrackingInvestmentFfi
import io.bhodl.ffi.TrackingInvestmentLotFfi
import io.bhodl.ui.theme.BhodlTheme
import kotlin.math.roundToInt
import kotlin.math.sign
import io.bhodl.shared.resources.Res
import org.jetbrains.compose.resources.stringResource
import io.bhodl.shared.resources.action_save
import io.bhodl.shared.resources.common_cancel
import io.bhodl.shared.resources.common_delete
import io.bhodl.shared.resources.trp_field_ccy
import io.bhodl.shared.resources.trp_field_name
import io.bhodl.shared.resources.trp_field_purchase_date
import io.bhodl.shared.resources.trp_inv_asset_type
import io.bhodl.shared.resources.trp_inv_cost_method
import io.bhodl.shared.resources.trp_inv_cost_method_hint
import io.bhodl.shared.resources.trp_inv_cost_total
import io.bhodl.shared.resources.trp_inv_current_value
import io.bhodl.shared.resources.trp_inv_current_value_hint
import io.bhodl.shared.resources.trp_inv_edit
import io.bhodl.shared.resources.trp_inv_lot_managed
import io.bhodl.shared.resources.trp_inv_method_specific
import io.bhodl.shared.resources.trp_inv_name_hint
import io.bhodl.shared.resources.trp_inv_new
import io.bhodl.shared.resources.trp_inv_quantity
import io.bhodl.shared.resources.trp_inv_ticker
import io.bhodl.shared.resources.trp_inv_unit
import io.bhodl.shared.resources.trp_inv_unit_hint
import io.bhodl.shared.resources.trp_lot_add
import io.bhodl.shared.resources.trp_lot_btc_at_purchase
import io.bhodl.shared.resources.trp_lot_fee
import io.bhodl.shared.resources.trp_lot_sell
import io.bhodl.shared.resources.trp_lots_empty
import io.bhodl.shared.resources.trp_sells

@Composable
internal fun InvestmentEditDialog(
    editing: TrackingInvestmentFfi?,
    /**
     * Phase 9 — lots of the edited investment. The dialog pre-fills
     * QTY / COST from the effective (lot-derived) values the row shows,
     * never the legacy aggregate (which can drift out of sync). With
     * more than one lot those two fields go read-only — quantity and
     * cost are then managed per-lot (expand the row), and the single-lot
     * save-time sync can't disambiguate which lot to update.
     */
    lots: List<TrackingInvestmentLotFfi> = emptyList(),
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        assetType: String,
        ticker: String?,
        quantity: Double,
        unit: String,
        costBasisFiat: Double,
        costBasisCurrency: String,
        manualCurrentValue: Double?,
        /**
         * Phase 7.8.1 — wire value of the picked [CostBasisMethod].
         * For new positions defaults to `"fifo"`. Host writes it via
         * `set_investment_cost_basis_method` after the main update.
         */
        costBasisMethod: String,
        /**
         * Phase 9 (BTC P&L) — purchase date as epoch seconds. Drives the
         * seeded lot's `purchase_unix`, which in turn anchors the
         * historical BTC-rate resolution. Defaults to today for a new
         * position; editable for 0/1-lot, read-only for multi-lot.
         */
        purchaseUnix: Long,
    ) -> Unit,
    onDelete: (id: Long) -> Unit,
) {
    var name by remember(editing?.id) { mutableStateOf(editing?.name ?: "") }
    var assetType by remember(editing?.id) {
        mutableStateOf(editing?.assetType ?: "stock")
    }
    var ticker by remember(editing?.id) { mutableStateOf(editing?.ticker.orEmpty()) }
    // Phase 7.8.1 — cost basis method state. Initial from editing or
    // FIFO default. The picker chips are rendered before the Save row.
    var costBasisMethod by remember(editing?.id) {
        mutableStateOf(CostBasisMethod.fromWire(editing?.costBasisMethod))
    }
    // Phase 9 — pre-fill QTY / COST from the effective (lot-derived)
    // values shown in the row, so the dialog and the table never
    // disagree. Falls back to the aggregate when there are no lots.
    var quantityStr by remember(editing?.id) {
        mutableStateOf(editing?.let { formatDecimalInput(effectiveQuantity(it, lots)) } ?: "")
    }
    var unit by remember(editing?.id) { mutableStateOf(editing?.unit ?: "shares") }
    var costBasisStr by remember(editing?.id) {
        mutableStateOf(editing?.let { formatDecimalInput(effectiveCostBasis(it, lots), maxFractionDigits = 2) } ?: "")
    }
    // >1 lot → quantity & cost are managed per-lot; the dialog shows
    // them read-only and the save-time single-lot sync is skipped.
    val lotManaged = lots.size > 1
    var currency by remember(editing?.id) {
        mutableStateOf(editing?.costBasisCurrency ?: "EUR")
    }
    var currentValueStr by remember(editing?.id) {
        mutableStateOf(editing?.manualCurrentValue?.let { formatDecimalInput(it, maxFractionDigits = 2) } ?: "")
    }
    // Phase 9 (BTC P&L) — purchase date. New positions default to today;
    // editing pre-fills the stored purchase_unix. Read-only for multi-lot
    // (each lot carries its own date — edit them via the row expansion).
    var purchaseUnix by remember(editing?.id) {
        mutableStateOf(editing?.purchaseUnix ?: (System.currentTimeMillis() / 1000L))
    }
    // Locale-aware parse (it_IT "350.000" = 350000) — see parseDecimalInput.
    val quantity: Double? = parseDecimalInput(quantityStr)
    val costBasis: Double? = parseDecimalInput(costBasisStr)
    val currentValue: Double? = parseDecimalInput(currentValueStr)
    val canSave = name.isNotBlank() && quantity != null && quantity > 0 &&
        costBasis != null && costBasis >= 0
    SlideOverPanel(
        onDismissRequest = onDismiss,
        title = { Text(if (editing == null) stringResource(Res.string.trp_inv_new) else stringResource(Res.string.trp_inv_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(Res.string.trp_field_name)) },
                    placeholder = { Text(stringResource(Res.string.trp_inv_name_hint)) },
                    modifier = Modifier.fillMaxWidth().testTag(InvestmentTags.INPUT_NAME),
                )
                Text(
                    text = stringResource(Res.string.trp_inv_asset_type),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textSecondary,
                )
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf("stock", "etf", "fund", "currency", "commodity", "manual").forEach { k ->
                        val selected = assetType == k
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (selected) BhodlTheme.colors.accentNeon.copy(alpha = 0.18f)
                                    else BhodlTheme.colors.surface,
                                )
                                .clickable { assetType = k }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                                .testTag(InvestmentTags.assetTypeTag(k)),
                        ) {
                            Text(
                                text = investmentTypeLabel(k),
                                style = BhodlTheme.typography.labelMedium,
                                color = if (selected) BhodlTheme.colors.accentNeon
                                else BhodlTheme.colors.textSecondary,
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = ticker,
                    onValueChange = { ticker = it },
                    singleLine = true,
                    label = { Text(stringResource(Res.string.trp_inv_ticker)) },
                    modifier = Modifier.fillMaxWidth().testTag(InvestmentTags.INPUT_TICKER),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = quantityStr,
                        onValueChange = { quantityStr = filterDecimalChars(it) },
                        singleLine = true,
                        readOnly = lotManaged,
                        label = { Text(stringResource(Res.string.trp_inv_quantity)) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                        ),
                        modifier = Modifier.weight(1f).testTag(InvestmentTags.INPUT_QUANTITY),
                    )
                    OutlinedTextField(
                        value = unit,
                        onValueChange = { unit = it },
                        singleLine = true,
                        label = { Text(stringResource(Res.string.trp_inv_unit)) },
                        placeholder = { Text(stringResource(Res.string.trp_inv_unit_hint)) },
                        modifier = Modifier.weight(1f).testTag(InvestmentTags.INPUT_UNIT),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = costBasisStr,
                        onValueChange = { costBasisStr = filterDecimalChars(it) },
                        singleLine = true,
                        readOnly = lotManaged,
                        label = { Text(stringResource(Res.string.trp_inv_cost_total)) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                        ),
                        modifier = Modifier.weight(2f).testTag(InvestmentTags.INPUT_COST),
                    )
                    OutlinedTextField(
                        value = currency,
                        onValueChange = { currency = it.uppercase().take(3) },
                        singleLine = true,
                        label = { Text(stringResource(Res.string.trp_field_ccy)) },
                        modifier = Modifier.weight(1f).testTag(InvestmentTags.INPUT_CCY),
                    )
                }
                if (lotManaged) {
                    Text(
                        text = stringResource(Res.string.trp_inv_lot_managed, lots.size),
                        style = BhodlTheme.typography.labelMedium,
                        color = BhodlTheme.colors.textTertiary,
                    )
                }
                // Phase 9 (BTC P&L) — purchase date anchors the historical
                // BTC-rate lookup for the cost basis. Hidden for multi-lot
                // (per-lot dates are edited via the row expansion).
                if (!lotManaged) {
                    io.bhodl.desktop.ui.DatePickerField(
                        label = stringResource(Res.string.trp_field_purchase_date),
                        epochSeconds = purchaseUnix,
                        onPick = { purchaseUnix = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = currentValueStr,
                    onValueChange = { currentValueStr = filterDecimalChars(it) },
                    singleLine = true,
                    label = { Text(stringResource(Res.string.trp_inv_current_value)) },
                    placeholder = { Text(stringResource(Res.string.trp_inv_current_value_hint)) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                    ),
                    modifier = Modifier.fillMaxWidth().testTag(InvestmentTags.INPUT_CURRENT),
                )

                // Phase 7.8.1 — cost basis method picker. 3 chip
                // (FIFO / HIFO / Specific-ID). Drives the sell flow:
                // FIFO / HIFO order the lots automatically, Specific-ID
                // makes SellDialog show a per-lot picker.
                Text(
                    text = stringResource(Res.string.trp_inv_cost_method),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textSecondary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(
                        CostBasisMethod.FIFO to "FIFO",
                        CostBasisMethod.HIFO to "HIFO",
                        CostBasisMethod.SPECIFIC_ID to stringResource(Res.string.trp_inv_method_specific),
                    ).forEach { (method, label) ->
                        val selected = costBasisMethod == method
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (selected) BhodlTheme.colors.accentNeon.copy(alpha = 0.18f)
                                    else BhodlTheme.colors.surfaceVariant,
                                )
                                .clickable { costBasisMethod = method }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                .testTag(InvestmentTags.costBasisMethodTag(method.wireValue)),
                        ) {
                            Text(
                                text = label,
                                style = BhodlTheme.typography.labelMedium,
                                color = if (selected) BhodlTheme.colors.accentNeon
                                else BhodlTheme.colors.textSecondary,
                            )
                        }
                    }
                }
                Text(
                    text = stringResource(Res.string.trp_inv_cost_method_hint),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textTertiary,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (canSave) onSave(
                        name.trim(),
                        assetType,
                        ticker.trim().ifBlank { null },
                        quantity!!,
                        unit.trim(),
                        costBasis!!,
                        currency.uppercase(),
                        currentValue,
                        costBasisMethod.wireValue,
                        purchaseUnix,
                    )
                },
                enabled = canSave,
                modifier = Modifier.testTag(InvestmentTags.SAVE),
            ) { Text(stringResource(Res.string.action_save)) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (editing != null) {
                    TextButton(
                        onClick = { onDelete(editing.id) },
                        modifier = Modifier.testTag(InvestmentTags.DELETE),
                    ) { Text(stringResource(Res.string.common_delete)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
            }
        },
    )
}

/**
 * Phase 7.8 — sub-section rendered under an expanded investment row.
 * Lists each lot (qty / price / date / notes) + a "+ Add lot" link
 * in the footer. Indented 32dp on the left to make the parent-child
 * relationship obvious.
 */
@Composable
internal fun InvestmentLotsExpansion(
    investmentId: Long,
    lots: List<TrackingInvestmentLotFfi>,
    sells: List<io.bhodl.ffi.TrackingInvestmentSellFfi> = emptyList(),
    disposalsByInvestment: List<io.bhodl.ffi.TrackingLotDisposalFfi> = emptyList(),
    parentCurrencySymbol: String = "",
    onAddLot: () -> Unit,
    onEditLot: (TrackingInvestmentLotFfi) -> Unit,
    onSell: () -> Unit = {},
    onDeleteSell: (io.bhodl.ffi.TrackingInvestmentSellFfi) -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BhodlTheme.colors.surfaceVariant.copy(alpha = 0.35f))
            .padding(start = 32.dp, end = BhodlTheme.spacing.md, top = 4.dp, bottom = 4.dp),
    ) {
        if (lots.isEmpty()) {
            Text(
                text = stringResource(Res.string.trp_lots_empty),
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.textTertiary,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        } else {
            lots.forEach { lot ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEditLot(lot) }
                        .testTag(InvestmentTags.lotRowTag(lot.id)),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(28.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${formatQuantity(lot.quantity)} @ ${formatFiat(lot.pricePerUnit)}",
                            modifier = Modifier.weight(1.5f),
                            style = BhodlTheme.typography.labelMedium,
                            color = BhodlTheme.colors.textPrimary,
                            maxLines = 1,
                        )
                        Text(
                            text = formatPurchaseDate(lot.purchaseUnix),
                            modifier = Modifier.weight(1f),
                            style = BhodlTheme.typography.labelMedium,
                            color = BhodlTheme.colors.textTertiary,
                            maxLines = 1,
                        )
                        if (lot.feesFiat > 0.0) {
                            Text(
                                text = stringResource(Res.string.trp_lot_fee, formatFiat(lot.feesFiat)),
                                modifier = Modifier.weight(0.8f),
                                style = BhodlTheme.typography.labelMedium,
                                color = BhodlTheme.colors.textTertiary,
                                textAlign = TextAlign.End,
                                maxLines = 1,
                            )
                        } else {
                            androidx.compose.foundation.layout.Spacer(
                                modifier = Modifier.weight(0.8f),
                            )
                        }
                    }
                    // Phase 8.3.3 — secondary "≈ ₿0.0XXXX at purchase"
                    // line for backfilled lots. Silent when the rate
                    // is missing (legacy/pre-8.2). The lot's BTC-cost
                    // equivalent is `pricePerUnit / btcPerUnitAtPurchase`
                    // expressed as a per-unit value, so we render the
                    // *unit cost* in BTC rather than the rate itself —
                    // it's what the user wants to compare to "current
                    // BTC equivalent per unit" of the same asset.
                    val rate = lot.btcPerUnitAtPurchase
                    if (rate != null && rate.isFinite() && rate > 0.0 && lot.pricePerUnit.isFinite()) {
                        val perUnitBtc = lot.pricePerUnit / rate
                        Text(
                            text = "  " + stringResource(Res.string.trp_lot_btc_at_purchase, formatBtcAmount(perUnitBtc)),
                            style = BhodlTheme.typography.labelMedium,
                            color = BhodlTheme.colors.textTertiary,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
                                .testTag(InvestmentTags.lotRowTag(lot.id) + ".btc"),
                        )
                    }
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(vertical = 4.dp),
        ) {
            Box(
                modifier = Modifier
                    .clickable(onClick = onAddLot)
                    .testTag(InvestmentTags.addLotTag(investmentId)),
            ) {
                Text(
                    text = stringResource(Res.string.trp_lot_add),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.accentNeon,
                )
            }
            // Phase 7.9 — Sell link visible only when there is at
            // least one lot with remaining quantity. Otherwise the
            // sell would be a guaranteed over-sell.
            val hasAvailable = lots.any { it.quantity > 0.0 }
            if (hasAvailable) {
                Box(
                    modifier = Modifier
                        .clickable(onClick = onSell)
                        .testTag(InvestmentTags.sellTag(investmentId)),
                ) {
                    Text(
                        text = stringResource(Res.string.trp_lot_sell),
                        style = BhodlTheme.typography.labelMedium,
                        color = BhodlTheme.colors.financialNegative,
                    )
                }
            }
        }
        // Phase 7.9 — Sells sub-section. One row per sell, with the
        // realized gain computed from the matching disposals.
        if (sells.isNotEmpty()) {
            Text(
                text = stringResource(Res.string.trp_sells),
                style = BhodlTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = BhodlTheme.colors.textSecondary,
                modifier = Modifier.padding(top = 6.dp),
            )
            sells.forEach { sell ->
                val sellDisposals = disposalsByInvestment.filter { it.sellId == sell.id }
                val realizedForSell = sellDisposals.sumOf { it.gainLossFiat }
                // Phase 8.3.3 — BTC-side realized for this sell.
                // Null when ANY disposal under this sell has no
                // gainLossBtc (partial Phase 8.2 backfill) — better
                // to drop the dual line than show a partial number.
                val realizedForSellBtc: Double? = run {
                    if (sellDisposals.isEmpty()) return@run null
                    var sum = 0.0
                    for (d in sellDisposals) {
                        val v = d.gainLossBtc ?: return@run null
                        if (!v.isFinite()) return@run null
                        sum += v
                    }
                    sum
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .clickable { onDeleteSell(sell) }
                        .testTag(InvestmentTags.sellRowTag(sell.id)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${formatQuantity(sell.saleQty)} @ ${formatFiat(sell.salePricePerUnit)}",
                        modifier = Modifier.weight(1.5f),
                        style = BhodlTheme.typography.labelMedium,
                        color = BhodlTheme.colors.textPrimary,
                        maxLines = 1,
                    )
                    Text(
                        text = formatPurchaseDate(sell.saleUnix),
                        modifier = Modifier.weight(1f),
                        style = BhodlTheme.typography.labelMedium,
                        color = BhodlTheme.colors.textTertiary,
                        maxLines = 1,
                    )
                    // formatFiat keeps the minus; amountSign adds "+" (none while masked).
                    val sign = io.bhodl.desktop.ui.amountSign(realizedForSell, minus = "")
                    val pnlColor = io.bhodl.desktop.ui.signedAmountColor(
                        value = realizedForSell,
                        positive = BhodlTheme.colors.financialPositive,
                        negative = BhodlTheme.colors.financialNegative,
                        neutral = BhodlTheme.colors.textSecondary,
                    )
                    // Phase 8.3.3 — when the BTC realized is known too,
                    // render the dual signed P&L "realized: +$150 · −₿0.00083".
                    // Otherwise fall back to the original fiat-only line.
                    val realizedLabel = if (realizedForSellBtc != null) {
                        val btcSign = io.bhodl.desktop.ui.amountSign(realizedForSellBtc, minus = "")
                        "$sign${formatFiat(realizedForSell)} $parentCurrencySymbol · " +
                            "$btcSign${formatBtcAmount(realizedForSellBtc)}"
                    } else {
                        "$sign${formatFiat(realizedForSell)} $parentCurrencySymbol"
                    }
                    Text(
                        text = realizedLabel,
                        modifier = Modifier.weight(0.8f)
                            .testTag(InvestmentTags.sellRowTag(sell.id) + ".pnl"),
                        style = BhodlTheme.typography.labelMedium,
                        color = pnlColor,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun formatPurchaseDate(unix: Long): String =
    java.time.Instant.ofEpochSecond(unix)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
