package io.bhodl.desktop.screens

import io.bhodl.desktop.ui.rowHeightFor
import io.bhodl.desktop.ui.tableRowClickable
import io.bhodl.desktop.ui.tableCell
import androidx.compose.foundation.layout.heightIn
import io.bhodl.desktop.ui.money.filterDecimalChars
import io.bhodl.desktop.ui.money.formatDecimalInput
import io.bhodl.desktop.ui.money.parseDecimalInput
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
import io.bhodl.desktop.prices.PriceCache
import io.bhodl.desktop.ui.ProvideColumnUnit
import io.bhodl.desktop.ui.SlideOverPanel
import io.bhodl.desktop.ui.formatFiat
import io.bhodl.desktop.ui.money.Money
import io.bhodl.desktop.ui.money.MoneyDensity
import io.bhodl.desktop.ui.money.MoneyPrimary
import io.bhodl.desktop.ui.money.MoneySemantic
import io.bhodl.desktop.ui.money.MoneyValue
import io.bhodl.ffi.TrackingManualAssetFfi
import io.bhodl.ui.theme.BhodlTheme
import io.bhodl.shared.resources.Res
import org.jetbrains.compose.resources.stringResource
import io.bhodl.shared.resources.action_save
import io.bhodl.shared.resources.common_cancel
import io.bhodl.shared.resources.common_delete
import io.bhodl.shared.resources.trp_add
import io.bhodl.shared.resources.trp_badge_other
import io.bhodl.shared.resources.trp_col_delta_btc
import io.bhodl.shared.resources.trp_col_name
import io.bhodl.shared.resources.trp_col_type
import io.bhodl.shared.resources.trp_field_currency
import io.bhodl.shared.resources.trp_field_name
import io.bhodl.shared.resources.trp_field_purchase_date
import io.bhodl.shared.resources.trp_field_type
import io.bhodl.shared.resources.trp_field_value
import io.bhodl.shared.resources.trp_ma_badge_brokerage
import io.bhodl.shared.resources.trp_ma_badge_business
import io.bhodl.shared.resources.trp_ma_badge_cash
import io.bhodl.shared.resources.trp_ma_badge_collectible
import io.bhodl.shared.resources.trp_ma_badge_loan_out
import io.bhodl.shared.resources.trp_ma_badge_real_estate
import io.bhodl.shared.resources.trp_ma_badge_vehicle
import io.bhodl.shared.resources.trp_ma_col_value_buy
import io.bhodl.shared.resources.trp_ma_col_value_now
import io.bhodl.shared.resources.trp_ma_edit
import io.bhodl.shared.resources.trp_ma_empty
import io.bhodl.shared.resources.trp_ma_kind_brokerage
import io.bhodl.shared.resources.trp_ma_kind_business
import io.bhodl.shared.resources.trp_ma_kind_cash
import io.bhodl.shared.resources.trp_ma_kind_collectible
import io.bhodl.shared.resources.trp_ma_kind_loans
import io.bhodl.shared.resources.trp_ma_kind_other
import io.bhodl.shared.resources.trp_ma_kind_real_estate
import io.bhodl.shared.resources.trp_ma_kind_vehicle
import io.bhodl.shared.resources.trp_ma_name_hint
import io.bhodl.shared.resources.trp_ma_new
import io.bhodl.shared.resources.trp_ma_notes_hint
import io.bhodl.shared.resources.trp_ma_purchase_header
import io.bhodl.shared.resources.trp_ma_purchase_invalid
import io.bhodl.shared.resources.trp_ma_purchase_value
import io.bhodl.shared.resources.trp_ma_purchase_value_hint
import io.bhodl.shared.resources.trp_ma_title
import io.bhodl.shared.resources.trp_ma_value_hint
import io.bhodl.shared.resources.trp_notes_optional
import org.jetbrains.compose.resources.StringResource

/**
 * Phase 7.4 — Manual Asset Accounts card.
 * Phase 8.3.4 — Bitcoin Lens migration: 5-column dual-line layout.
 *
 * Asset side, conceptual mirror of [io.bhodl.desktop.screens.NetWorthCard]'s
 * liability list, but multi-currency and with a richer `kind`. Renders a dense
 * Bloomberg/Sparrow-style table — header bar, header row, data rows.
 * No totals row here; the NetWorthBreakdownTable already shows one row
 * per kind on the top dashboard.
 *
 * Columns (Phase 8.3.4):
 *  - **NAME** + currency subtitle (weight 1.8)
 *  - **TYPE** kind badge (weight 0.8)
 *  - **VALUE NOW** dual-line `<Money>` Medium AtNow, BTC primary +
 *    fiat secondary (weight 1.2)
 *  - **VALUE AT BUY** dual-line `<Money>` Medium AtCost, with the
 *    purchase year appended to the fiat secondary (weight 1.2)
 *  - **Δ BTC** percentage change of the BTC-equivalent value between
 *    purchase and now, colored by sign — the headline Bitcoin Lens
 *    story (weight 0.8). "—" when the asset is cross-currency or the
 *    purchase snapshot is incomplete.
 *
 * Tap a row to edit; "+ Add" opens [ManualAssetEditDialog].
 */
@Composable
internal fun ManualAssetsCard(
    assets: List<TrackingManualAssetFfi>,
    onAdd: () -> Unit,
    onEdit: (TrackingManualAssetFfi) -> Unit,
) {
    // Item 17 — on a narrow window type, value at buy, then Δ BTC give
    // way ([MANUAL_ASSET_TABLE]); the edit dialog still carries them.
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val cols = MANUAL_ASSET_TABLE.visible(maxWidth.value.toInt())
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(BhodlTheme.shapes.panel)
                .background(BhodlTheme.colors.surface)
                .border(1.dp, BhodlTheme.colors.outline, BhodlTheme.shapes.panel),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BhodlTheme.colors.surfaceVariant)
                    .padding(horizontal = BhodlTheme.spacing.md, vertical = BhodlTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.trp_ma_title),
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
                        .testTag(ManualAssetTags.ADD),
                ) {
                    Text(
                        text = stringResource(Res.string.trp_add),
                        style = BhodlTheme.typography.labelMedium,
                        color = BhodlTheme.colors.accentNeon,
                    )
                }
            }
            if (assets.isEmpty()) {
                // Empty state — copy nudges the user that this is where
                // non-BTC holdings live so the NetWorth becomes complete.
                Text(
                    text = stringResource(Res.string.trp_ma_empty),
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textTertiary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(BhodlTheme.spacing.md),
                )
                return@Column
            }
            // UX review 2026-10 (rec 2) — one unit for the whole table: Auto
            // resolves on every row's BTC equivalent (value or purchase).
            ProvideColumnUnit(
                assets.flatMap { a ->
                    listOfNotNull(a.valueFiat, a.purchaseValueFiat).mapNotNull { v ->
                        PriceCache.rateFor(a.currency)?.takeIf { it > 0.0 }?.let { r -> (v / r * 100_000_000.0).toLong() }
                    }
                },
            ) {
                ManualAssetsHeaderRow(cols)
                assets.forEachIndexed { idx, a ->
                    ManualAssetsDataRow(
                        asset = a,
                        cols = cols,
                        isZebra = idx % 2 == 1,
                        onClick = { onEdit(a) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ManualAssetsHeaderRow(cols: List<ManualAssetCol>) {
    // Phase 8.3.4 — 5 columns. Weights chosen to match the dual-line
    // VALUE NOW / VALUE AT BUY cells (Medium density `<Money>`) while
    // keeping the row visually balanced; Δ BTC is narrow because it
    // only carries "↑ +X%" / "↓ −X%" / "—".
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = BhodlTheme.spacing.md, vertical = BhodlTheme.spacing.sm)
            .testTag(ManualAssetTags.HEADER),
    ) {
        for (c in cols) {
            val label = when (c) {
                ManualAssetCol.NAME -> Res.string.trp_col_name
                ManualAssetCol.TYPE -> Res.string.trp_col_type
                ManualAssetCol.VALUE_NOW -> Res.string.trp_ma_col_value_now
                ManualAssetCol.VALUE_AT_BUY -> Res.string.trp_ma_col_value_buy
                ManualAssetCol.DELTA_BTC -> Res.string.trp_col_delta_btc
            }
            Text(
                stringResource(label),
                Modifier.tableCell(this, c).testTag(MANUAL_ASSET_TABLE.headerTag(c)),
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.textSecondary,
                textAlign = if (c == ManualAssetCol.NAME || c == ManualAssetCol.TYPE) TextAlign.Start else TextAlign.End,
            )
        }
    }
}

@Composable
private fun ManualAssetsDataRow(
    asset: TrackingManualAssetFfi,
    cols: List<ManualAssetCol>,
    isZebra: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (isZebra) BhodlTheme.colors.rowZebra else Color.Transparent
    // Phase 9 — same wiring as InvestmentsCard's BTC P&L cell: resolve
    // the BTC/<asset-currency> rate via PriceCache.rateFor so an asset
    // priced in a non-dashboard currency (e.g. a USD account on a EUR
    // dashboard) still gets a Δ BTC instead of "no rate". rateFor reads
    // the per-currency rate map the auto-fetch loop populates; null only
    // when no rate for that currency is cached at all.
    val currentBtcPerUnit: Double? = PriceCache.rateFor(asset.currency)
    val deltaBtcPct: Double? = computeManualAssetDeltaBtcPct(asset, currentBtcPerUnit)
    val purchaseYear: Int? = asset.purchaseUnix?.let { unix ->
        runCatching {
            java.time.Instant.ofEpochSecond(unix)
                .atZone(java.time.ZoneId.systemDefault())
                .year
        }.getOrNull()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Item 15 — dual-line Money cells (spec §2.2), density-aware.
            .heightIn(min = BhodlTheme.density.rowHeightFor(2))
            .background(bg)
            .tableRowClickable(onClick = onClick)
            .padding(horizontal = BhodlTheme.spacing.md)
            .testTag(ManualAssetTags.rowTag(asset.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.tableCell(this, ManualAssetCol.NAME)) {
            Text(
                text = asset.name,
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textPrimary,
                maxLines = 1,
            )
            if (!asset.displayLabel.isNullOrBlank()) {
                Text(
                    text = asset.displayLabel!!,
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textTertiary,
                    maxLines = 1,
                )
            }
        }
        if (ManualAssetCol.TYPE in cols) {
            Box(modifier = Modifier.tableCell(this, ManualAssetCol.TYPE), contentAlignment = Alignment.CenterStart) {
                ManualAssetKindBadge(asset.kind)
            }
        }
        // VALUE NOW — Money composable derives the BTC primary from the
        // live PriceCache when the asset currency matches the dashboard
        // fiat. Cross-currency assets fall back to a fiat-only secondary
        // (BTC line "—") via the same null-policy.
        Column(
            modifier = Modifier
                .tableCell(this, ManualAssetCol.VALUE_NOW)
                .testTag(ManualAssetTags.valueNowTag(asset.id)),
            horizontalAlignment = Alignment.End,
        ) {
            Money(
                value = MoneyValue(
                    fiatAmount = asset.valueFiat,
                    fiatCurrency = asset.currency,
                    semantic = MoneySemantic.AtNow,
                    rateBtcFiat = currentBtcPerUnit,
                ),
                density = MoneyDensity.Medium,
                primary = MoneyPrimary.Btc,
                showSecondary = true,
            )
        }
        // VALUE AT BUY — historical snapshot from Phase 8.2 backfill.
        // The `<Money>` composable handles the "purchase fields all
        // null" case by rendering "—" on both lines; in that case we
        // skip the purchase-year suffix to avoid an orphan "(2021)".
        val purchaseFiat = asset.purchaseValueFiat
        val purchaseCurrency = asset.purchaseCurrency ?: asset.currency
        if (ManualAssetCol.VALUE_AT_BUY in cols) {
            Column(
                modifier = Modifier
                    .tableCell(this, ManualAssetCol.VALUE_AT_BUY)
                    .testTag(ManualAssetTags.valueAtBuyTag(asset.id)),
                horizontalAlignment = Alignment.End,
            ) {
                Money(
                    value = MoneyValue(
                        fiatAmount = purchaseFiat,
                        fiatCurrency = purchaseCurrency,
                        semantic = MoneySemantic.AtCost,
                        rateBtcFiat = asset.purchaseBtcAtTime,
                    ),
                    density = MoneyDensity.Medium,
                    primary = MoneyPrimary.Btc,
                    showSecondary = true,
                )
                // Purchase-year hint: contextualises why "VALUE AT BUY in
                // BTC" can look so large relative to "VALUE NOW in BTC" —
                // a 2017 purchase happened pre-pump. Silent when the row
                // has no purchase snapshot at all.
                if (purchaseFiat != null && purchaseYear != null) {
                    Text(
                        text = "($purchaseYear)",
                        style = BhodlTheme.typography.labelMedium,
                        color = BhodlTheme.colors.textTertiary,
                        maxLines = 1,
                    )
                }
            }
        }
        // Δ BTC — the headline Bitcoin Lens insight. Same formatter as
        // the per-row P&L percent in InvestmentsCard so the visual
        // grammar matches across cards. Phase 8.6 — when the purchase
        // snapshot is present but the live BTC rate isn't (cross-currency
        // asset or PriceCache not populated yet), say "no rate" explicitly
        // instead of the bare "—"; a missing purchase snapshot keeps the
        // em-dash because that's "no data", not "no rate".
        if (ManualAssetCol.DELTA_BTC in cols) {
            if (deltaBtcPct == null && currentBtcPerUnit == null &&
                asset.purchaseBtcAtTime != null && asset.purchaseValueFiat != null
            ) {
                NoRateCell(
                    modifier = Modifier.tableCell(this, ManualAssetCol.DELTA_BTC),
                    cellTag = ManualAssetTags.deltaBtcTag(asset.id),
                )
            } else {
                Text(
                    text = formatPnlPercent(deltaBtcPct?.toInt()),
                    modifier = Modifier
                        .tableCell(this, ManualAssetCol.DELTA_BTC)
                        .testTag(ManualAssetTags.deltaBtcTag(asset.id)),
                    style = BhodlTheme.typography.amountSmall,
                    color = pnlColorFor(deltaBtcPct),
                    textAlign = TextAlign.End,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Small colored pill that mirrors the [AssetTypeBadge] used in
 * [InvestmentsCard]. Kept here so the styling stays local to manual
 * assets — the kind set is different (cash vs stock-class), and the
 * label localization may diverge in future.
 */
@Composable
private fun ManualAssetKindBadge(kind: String) {
    val (label, accent) = manualAssetKindStyle(kind)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(accent.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = BhodlTheme.typography.labelMedium,
            color = accent,
        )
    }
}

@Composable
private fun manualAssetKindStyle(kind: String): Pair<String, Color> = when (kind) {
    "cash" -> stringResource(Res.string.trp_ma_badge_cash) to BhodlTheme.colors.financialPositive
    "brokerage" -> stringResource(Res.string.trp_ma_badge_brokerage) to BhodlTheme.colors.accentNeon
    "real_estate" -> stringResource(Res.string.trp_ma_badge_real_estate) to BhodlTheme.colors.brandPrimary
    "vehicle" -> stringResource(Res.string.trp_ma_badge_vehicle) to BhodlTheme.colors.financialPending
    "collectible" -> stringResource(Res.string.trp_ma_badge_collectible) to BhodlTheme.colors.chartCategorical.getOrElse(2) {
        BhodlTheme.colors.accentNeon
    }
    "business_equity" -> stringResource(Res.string.trp_ma_badge_business) to BhodlTheme.colors.chartCategorical.getOrElse(3) {
        BhodlTheme.colors.brandPrimary
    }
    "loans_to_others" -> stringResource(Res.string.trp_ma_badge_loan_out) to BhodlTheme.colors.chartCategorical.getOrElse(4) {
        BhodlTheme.colors.accentNeon
    }
    else -> stringResource(Res.string.trp_badge_other) to BhodlTheme.colors.textTertiary
}

/**
 * Short fiat currency symbol; full ISO list kept tiny because the
 * picker only offers EUR/USD/CHF/GBP/JPY. Falls back to the code.
 */
internal fun manualAssetSymbolFor(currency: String): String = when (currency.uppercase()) {
    "EUR" -> "€"
    "USD" -> "$"
    "GBP" -> "£"
    "JPY" -> "¥"
    "CHF" -> "CHF "
    else -> "$currency "
}

@Composable
internal fun ManualAssetEditDialog(
    editing: TrackingManualAssetFfi?,
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        kind: String,
        value: Double,
        currency: String,
        notes: String?,
        /**
         * Phase 9 (BTC P&L) — optional purchase snapshot. When both date
         * and value are present, the host persists them and resolves the
         * historical BTC rate at that date so the Δ BTC column is real
         * (instead of "no rate"). Absent → no purchase snapshot, Δ BTC
         * stays "—".
         */
        purchaseUnix: Long?,
        purchaseValueFiat: Double?,
    ) -> Unit,
    onDelete: (id: Long) -> Unit,
) {
    var name by remember(editing?.id) { mutableStateOf(editing?.name ?: "") }
    var kind by remember(editing?.id) { mutableStateOf(editing?.kind ?: "cash") }
    var valueStr by remember(editing?.id) {
        mutableStateOf(editing?.valueFiat?.let { formatDecimalInput(it) } ?: "")
    }
    var currency by remember(editing?.id) { mutableStateOf(editing?.currency ?: "EUR") }
    var notesStr by remember(editing?.id) { mutableStateOf(editing?.notes ?: "") }
    // Phase 9 (BTC P&L) — optional purchase date + value.
    var purchaseUnix by remember(editing?.id) { mutableStateOf(editing?.purchaseUnix) }
    var purchaseValueStr by remember(editing?.id) {
        mutableStateOf(editing?.purchaseValueFiat?.let { formatDecimalInput(it) } ?: "")
    }

    // Locale-aware parse (it_IT "350.000" = 350000) — see parseDecimalInput.
    val value: Double? = parseDecimalInput(valueStr)
    val purchaseValue: Double? = parseDecimalInput(purchaseValueStr)
    // A typed-but-unparseable purchase value must block save rather than
    // being silently dropped.
    val purchaseValueInvalid = purchaseValueStr.isNotBlank() &&
        (purchaseValue == null || purchaseValue <= 0.0)
    val canSave = name.isNotBlank() && value != null && value > 0.0 && value.isFinite() &&
        !purchaseValueInvalid

    SlideOverPanel(
        onDismissRequest = onDismiss,
        title = { Text(if (editing == null) stringResource(Res.string.trp_ma_new) else stringResource(Res.string.trp_ma_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(Res.string.trp_field_name)) },
                    placeholder = { Text(stringResource(Res.string.trp_ma_name_hint)) },
                    modifier = Modifier.fillMaxWidth().testTag(ManualAssetTags.INPUT_NAME),
                )

                Text(
                    text = stringResource(Res.string.trp_field_type),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textSecondary,
                )
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    MANUAL_ASSET_KIND_LABELS.forEach { (k, label) ->
                        val selected = kind == k
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (selected) BhodlTheme.colors.accentNeon.copy(alpha = 0.18f)
                                    else BhodlTheme.colors.surfaceVariant,
                                )
                                .clickable { kind = k }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                .testTag(ManualAssetTags.kindTag(k)),
                        ) {
                            Text(
                                text = stringResource(label),
                                style = BhodlTheme.typography.labelMedium,
                                color = if (selected) BhodlTheme.colors.accentNeon
                                else BhodlTheme.colors.textSecondary,
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = valueStr,
                    onValueChange = { raw ->
                        // Accept digits, dot, comma. Same filter as
                        // InvestmentsCard's current_value input.
                        valueStr = filterDecimalChars(raw)
                    },
                    singleLine = true,
                    label = { Text(stringResource(Res.string.trp_field_value)) },
                    placeholder = { Text(stringResource(Res.string.trp_ma_value_hint)) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                    ),
                    modifier = Modifier.fillMaxWidth().testTag(ManualAssetTags.INPUT_VALUE),
                )

                Text(
                    text = stringResource(Res.string.trp_field_currency),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textSecondary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf("EUR", "USD", "CHF", "GBP", "JPY").forEach { c ->
                        val selected = currency == c
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (selected) BhodlTheme.colors.accentNeon.copy(alpha = 0.18f)
                                    else BhodlTheme.colors.surfaceVariant,
                                )
                                .clickable { currency = c }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                .testTag(ManualAssetTags.currencyTag(c)),
                        ) {
                            Text(
                                text = c,
                                style = BhodlTheme.typography.labelMedium,
                                color = if (selected) BhodlTheme.colors.accentNeon
                                else BhodlTheme.colors.textSecondary,
                            )
                        }
                    }
                }

                // Phase 9 (BTC P&L) — optional purchase snapshot. Filling
                // both date and value lights up the Δ BTC column ("up in
                // EUR, down in BTC"); leaving them blank keeps Δ BTC as "—".
                Text(
                    text = stringResource(Res.string.trp_ma_purchase_header),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textSecondary,
                )
                io.bhodl.desktop.ui.DatePickerField(
                    label = stringResource(Res.string.trp_field_purchase_date),
                    epochSeconds = purchaseUnix,
                    onPick = { purchaseUnix = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = purchaseValueStr,
                    onValueChange = { raw ->
                        purchaseValueStr = filterDecimalChars(raw)
                    },
                    singleLine = true,
                    label = { Text(stringResource(Res.string.trp_ma_purchase_value)) },
                    placeholder = { Text(stringResource(Res.string.trp_ma_purchase_value_hint)) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                    ),
                    modifier = Modifier.fillMaxWidth().testTag(ManualAssetTags.INPUT_PURCHASE_VALUE),
                )
                if (purchaseValueInvalid) {
                    Text(
                        text = stringResource(Res.string.trp_ma_purchase_invalid),
                        style = BhodlTheme.typography.labelMedium,
                        color = BhodlTheme.colors.financialNegative,
                    )
                }

                OutlinedTextField(
                    value = notesStr,
                    onValueChange = { notesStr = it },
                    label = { Text(stringResource(Res.string.trp_notes_optional)) },
                    placeholder = { Text(stringResource(Res.string.trp_ma_notes_hint)) },
                    modifier = Modifier.fillMaxWidth().testTag(ManualAssetTags.INPUT_NOTES),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (canSave) {
                        // Only carry a purchase snapshot when BOTH date and
                        // value are present; otherwise pass nulls (no snapshot).
                        val hasPurchase = purchaseUnix != null && purchaseValue != null && purchaseValue > 0.0
                        onSave(
                            name.trim(), kind, value!!, currency,
                            notesStr.takeIf { it.isNotBlank() },
                            if (hasPurchase) purchaseUnix else null,
                            if (hasPurchase) purchaseValue else null,
                        )
                    }
                },
                enabled = canSave,
                modifier = Modifier.testTag(ManualAssetTags.SAVE),
            ) { Text(stringResource(Res.string.action_save)) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (editing != null) {
                    TextButton(
                        onClick = { onDelete(editing.id) },
                        modifier = Modifier.testTag(ManualAssetTags.DELETE),
                    ) { Text(stringResource(Res.string.common_delete)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
            }
        },
    )
}

/**
 * Phase 7.4 — wire-string ↔ display-label map for the 8 supported kinds.
 * Kept here in display order so the picker chips render with a sensible
 * priority (cash first, "other" last). Labels are string resources
 * (UX review 2026-10, rec 3), resolved at render time.
 */
internal val MANUAL_ASSET_KIND_LABELS: List<Pair<String, StringResource>> = listOf(
    "cash" to Res.string.trp_ma_kind_cash,
    "brokerage" to Res.string.trp_ma_kind_brokerage,
    "real_estate" to Res.string.trp_ma_kind_real_estate,
    "vehicle" to Res.string.trp_ma_kind_vehicle,
    "collectible" to Res.string.trp_ma_kind_collectible,
    "business_equity" to Res.string.trp_ma_kind_business,
    "loans_to_others" to Res.string.trp_ma_kind_loans,
    "other" to Res.string.trp_ma_kind_other,
)

internal object ManualAssetTags {
    const val ADD: String = "manual-asset.add"
    const val SAVE: String = "manual-asset.save"
    const val DELETE: String = "manual-asset.delete"
    const val INPUT_NAME: String = "manual-asset.input-name"
    const val INPUT_VALUE: String = "manual-asset.input-value"
    const val INPUT_PURCHASE_VALUE: String = "manual-asset.input-purchase-value"
    const val INPUT_NOTES: String = "manual-asset.input-notes"
    fun kindTag(kind: String): String = "manual-asset.kind.$kind"
    fun currencyTag(currency: String): String = "manual-asset.currency.$currency"

    // ─── Phase 8.3.4 — Bitcoin Lens migration ─────────────────────
    /** Header row for the 5-column dual-line layout. */
    const val HEADER: String = "manual-asset.header"
    /** Per-row container tag — anchors VALUE NOW / VALUE AT BUY / Δ BTC. */
    fun rowTag(id: Long): String = "manual-asset.row.$id"
    /** Dual-line VALUE NOW cell (BTC primary + fiat secondary). */
    fun valueNowTag(id: Long): String = "manual-asset.value-now.$id"
    /** Dual-line VALUE AT BUY cell (Phase 8.2 snapshot + year hint). */
    fun valueAtBuyTag(id: Long): String = "manual-asset.value-at-buy.$id"
    /** Δ BTC percentage cell, colored by sign. */
    fun deltaBtcTag(id: Long): String = "manual-asset.delta-btc.$id"
}
