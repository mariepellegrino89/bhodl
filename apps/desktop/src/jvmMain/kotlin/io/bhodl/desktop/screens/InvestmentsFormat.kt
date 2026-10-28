package io.bhodl.desktop.screens

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
import io.bhodl.ffi.TrackingInvestmentLotFfi
import io.bhodl.ui.theme.BhodlTheme
import kotlin.math.roundToInt
import kotlin.math.sign
import io.bhodl.shared.resources.Res
import org.jetbrains.compose.resources.stringResource
import io.bhodl.shared.resources.trp_inv_type_commodity
import io.bhodl.shared.resources.trp_inv_type_currency
import io.bhodl.shared.resources.trp_inv_type_etf
import io.bhodl.shared.resources.trp_inv_type_fund
import io.bhodl.shared.resources.trp_inv_type_manual
import io.bhodl.shared.resources.trp_inv_type_stock

/**
 * Cost basis of the lots expressed in BTC: Σ qty × (price + fee/unit) / lot
 * rate, each lot at ITS OWN BTC rate at purchase.
 *
 * Code review 2026-10 — replaces `averageBtcRateAtPurchase`, which fed
 * the COST cell a cost-weighted ARITHMETIC mean of the rates. That is
 * wrong for BTC: two €100 lots bought at 10k and 50k cost
 * 0.01 + 0.002 = 0.012 BTC, while €200 / 30k = 0.00667. This sum is the
 * same one the P&L BTC % and the totals row use, so COST and P&L agree.
 *
 * Same lot set and fee handling as [effectiveCostBasis] (lots with a
 * non-finite qty/price are skipped). Returns null when there are no
 * lots, when any contributing lot has no usable rate (a partial sum
 * would understate the cost), or when the sum is not positive.
 */
internal fun lotsCostBtc(lots: List<TrackingInvestmentLotFfi>): Double? {
    if (lots.isEmpty()) return null
    var sum = 0.0
    for (l in lots) {
        if (!l.quantity.isFinite() || !l.pricePerUnit.isFinite()) continue
        val rate = l.btcPerUnitAtPurchase ?: return null
        if (!rate.isFinite() || rate <= 0.0) return null
        sum += l.quantity * (l.pricePerUnit + lotFeePerUnit(l)) / rate
    }
    return sum.takeIf { it > 0.0 }
}

/** Phase 8.3.3 — divergence detection: opposite signs, both non-zero,
 *  both non-null. Returns false on any null or zero side. Exposed
 *  internal so tests can pin the contract independent of Compose. */
internal fun isPnlDivergent(pnlFiat: Double?, pnlBtc: Double?): Boolean {
    if (pnlFiat == null || pnlBtc == null) return false
    if (pnlFiat == 0.0 || pnlBtc == 0.0) return false
    return sign(pnlFiat) != sign(pnlBtc)
}

/** Pure color picker driven off the percent integer. Mirror of
 *  [pnlColorFor] but reads the int input the BTC P&L cell already has. */
@Composable
internal fun pnlColorForInt(pct: Int?): Color = when {
    pct == null -> BhodlTheme.colors.financialNeutral
    pct > 0 -> BhodlTheme.colors.financialPositive
    pct < 0 -> BhodlTheme.colors.financialNegative
    else -> BhodlTheme.colors.financialNeutral
}

/**
 * Phase 8.3.3 — render a BTC amount inline (totals row only — data
 * rows use `<Money>` Medium). Picks between sat for sub-100k-sat
 * amounts and "₿0.0XXXX" otherwise, mirroring `UnitFormat.AUTO_THRESHOLD`
 * but inline rather than as a Compose `Money` (which would add an
 * unnecessary `Column` to the totals cell).
 *
 * Null → em-dash.
 *
 * UX review 2026-10 (rec 2) — goes through the shared formatter (the
 * user's unit, BIP177, locale separators) instead of its own
 * sat/₿ threshold, so the totals row matches the `<Money>` data rows.
 */
@Composable
internal fun formatBtcAmount(btc: Double?): String {
    val fmt = io.bhodl.desktop.ui.rememberAmountFormatter()
    if (btc == null || !btc.isFinite()) return "—"
    return fmt(kotlin.math.round(btc * 100_000_000.0).toLong())
}

/**
 * Asset-type → (background, foreground) color pair. Picked from the
 * Okabe-Ito categorical chart palette so the badges visually align
 * with the rest of the dashboard's chart legend. Unknown types fall
 * back to a neutral grey.
 */
@Composable
internal fun assetTypeColors(assetType: String): Pair<Color, Color> {
    val palette = BhodlTheme.colors.chartCategorical
    val (idx, _) = assetTypeIndex(assetType)
    val accent = palette.getOrNull(idx) ?: BhodlTheme.colors.textTertiary
    return accent.copy(alpha = 0.18f) to accent
}

/**
 * UX review 2026-10 (rec 3) — localized label of an investment type.
 * The wire value (`stock`, `etf`, …) stays the stored key; unknown
 * types show as stored.
 */
@Composable
internal fun investmentTypeLabel(assetType: String): String = when (assetType.lowercase()) {
    "stock" -> stringResource(Res.string.trp_inv_type_stock)
    "etf" -> stringResource(Res.string.trp_inv_type_etf)
    "fund" -> stringResource(Res.string.trp_inv_type_fund)
    "currency" -> stringResource(Res.string.trp_inv_type_currency)
    "commodity" -> stringResource(Res.string.trp_inv_type_commodity)
    "manual" -> stringResource(Res.string.trp_inv_type_manual)
    else -> assetType
}

/** Pure index lookup — exposed for tests so the colour mapping is
 *  stable across refactors. Returns (palette index, isKnown). */
internal fun assetTypeIndex(assetType: String): Pair<Int, Boolean> = when (assetType.lowercase()) {
    "stock" -> 0 to true        // sky blue
    "etf" -> 1 to true          // bluish green
    "fund" -> 3 to true         // blue
    "currency" -> 4 to true     // orange-adjacent (cash)
    "commodity" -> 5 to true    // vermillion (resources)
    "manual" -> 7 to true       // neutral grey
    else -> 7 to false
}

/** Pure formatter for the per-row P&L percent cell. Exposed internal
 *  so tests can pin the string contract independent of Compose. */
internal fun formatPnlPercent(pct: Int?): String = when {
    pct == null -> "—"
    pct == 0 -> "= 0%"
    pct > 0 -> "↑ +$pct%"
    else -> "↓ $pct%"
}

/** Pure color picker for the P&L cell. Wraps the convention-aware
 *  semantic colors so callers don't repeat the when/null logic. */
@Composable
internal fun pnlColorFor(pnl: Double?): Color = io.bhodl.desktop.ui.signedAmountColor(
    value = pnl,
    positive = BhodlTheme.colors.financialPositive,
    negative = BhodlTheme.colors.financialNegative,
    neutral = BhodlTheme.colors.financialNeutral,
)

/** ISO-4217 → display prefix; see [io.bhodl.desktop.ui.fiatPrefix]. */
internal fun symbolFor(code: String): String = io.bhodl.desktop.ui.fiatPrefix(code)

/** Format a quantity dropping trailing zeros, e.g. 100.0 → "100", 0.5 → "0.5".
 *  Uses Locale.ROOT so the decimal separator is always `.` regardless
 *  of OS locale — keeps the displayed quantity consistent with the rest
 *  of the BHODL formatters (formatFiat etc. all assume `.` decimals). */
internal fun formatQuantity(q: Double): String {
    if (q == q.toLong().toDouble()) return q.toLong().toString()
    return "%.4f".format(java.util.Locale.ROOT, q).trimEnd('0').trimEnd('.')
}
