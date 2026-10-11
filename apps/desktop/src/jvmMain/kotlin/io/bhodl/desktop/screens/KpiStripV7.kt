package io.bhodl.desktop.screens
import io.bhodl.desktop.domain.FinanceKpis

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.ui.bhodlCard
import io.bhodl.desktop.ui.rememberAmountFormatter
import io.bhodl.desktop.ui.money.Money
import io.bhodl.desktop.ui.money.MoneyDensity
import io.bhodl.desktop.ui.money.MoneyPrimary
import io.bhodl.desktop.ui.money.MoneySemantic
import io.bhodl.desktop.ui.money.MoneyValue
import io.bhodl.ui.theme.BhodlTheme
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.trk_kpi_burn
import io.bhodl.shared.resources.trk_kpi_burn_sub
import io.bhodl.shared.resources.trk_kpi_fire_sub
import io.bhodl.shared.resources.trk_kpi_months
import io.bhodl.shared.resources.trk_kpi_opp_cost
import io.bhodl.shared.resources.trk_kpi_opp_cost_sub
import io.bhodl.shared.resources.trk_kpi_runway
import io.bhodl.shared.resources.trk_kpi_runway_sub
import io.bhodl.shared.resources.trk_kpi_savings
import io.bhodl.shared.resources.trk_kpi_savings_sub
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

/**
 * Phase 7.6 — Wealth-builder KPI strip.
 * Phase 8.3.4 — Bitcoin Lens migration: BURN/FIRE switch to `<Money>`
 * Compact (BTC primary + fiat secondary inline) and a 5th OPP COST
 * placeholder is added (Phase 8.5 will compute the real value).
 *
 * 5 small cards in a row: Savings rate · Burn rate · FIRE number ·
 * Runway · Opportunity cost. Each card has a label (top), a big value
 * (middle), a subtitle hint (bottom). When the metric is null
 * (insufficient data) the value renders as "—" so the user sees the
 * card exists but understands data isn't there yet.
 *
 * Color semantics:
 *  - Savings rate: green ≥20%, yellow 0–19%, red negative.
 *  - Burn rate: neutral (informational).
 *  - FIRE: brand accent.
 *  - Runway: green ≥12 mo, yellow 6–11, red <6.
 *  - Opp cost: muted (Phase 8.5 not yet wired).
 */
@Composable
internal fun KpiStripV7(
    kpis: FinanceKpis,
    showSavings: Boolean = true,
    showBurn: Boolean = true,
    showFire: Boolean = true,
    showRunway: Boolean = true,
    showOppCost: Boolean = true,
) {
    val fmtAmount = rememberAmountFormatter()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(KpiStripV7Tags.ROW),
        horizontalArrangement = Arrangement.spacedBy(BhodlTheme.spacing.sm),
    ) {
        if (showSavings) {
            KpiCard(
                modifier = Modifier.weight(1f).testTag(KpiStripV7Tags.SAVINGS),
                label = stringResource(Res.string.trk_kpi_savings),
                value = kpis.savingsRatePct?.let { "${if (it >= 0) "+" else ""}$it%" } ?: "—",
                subtitle = stringResource(Res.string.trk_kpi_savings_sub),
                accent = savingsAccent(kpis.savingsRatePct),
            )
        }
        if (showBurn) {
            KpiCardMoney(
                modifier = Modifier.weight(1f).testTag(KpiStripV7Tags.BURN),
                label = stringResource(Res.string.trk_kpi_burn),
                value = kpis.burnRateSatPerMonth?.let {
                    MoneyValue.fromSat(it.toLong(), semantic = MoneySemantic.AtNow)
                },
                subtitle = stringResource(Res.string.trk_kpi_burn_sub),
                accent = BhodlTheme.colors.textSecondary,
            )
        }
        if (showFire) {
            KpiCardMoney(
                modifier = Modifier.weight(1f).testTag(KpiStripV7Tags.FIRE),
                label = "FIRE",
                value = kpis.fireNumberSat?.let {
                    MoneyValue.fromSat(it.toLong(), semantic = MoneySemantic.AtNow)
                },
                subtitle = stringResource(Res.string.trk_kpi_fire_sub),
                accent = BhodlTheme.colors.accentNeon,
            )
        }
        if (showRunway) {
            KpiCard(
                modifier = Modifier.weight(1f).testTag(KpiStripV7Tags.RUNWAY),
                label = stringResource(Res.string.trk_kpi_runway),
                value = kpis.runwayMonths?.let { formatMonths(it) } ?: "—",
                subtitle = stringResource(Res.string.trk_kpi_runway_sub),
                accent = runwayAccent(kpis.runwayMonths),
            )
        }
        if (showOppCost) {
            KpiCard(
                modifier = Modifier.weight(1f).testTag(KpiStripV7Tags.OPP_COST),
                label = stringResource(Res.string.trk_kpi_opp_cost),
                value = kpis.opportunityCostBtc?.let { formatOppCost(it, fmtAmount, masked = io.bhodl.desktop.ui.money.amountsMasked()) } ?: "—",
                subtitle = stringResource(Res.string.trk_kpi_opp_cost_sub),
                accent = oppCostAccent(kpis.opportunityCostBtc),
            )
        }
    }
}

@Composable
private fun KpiCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    subtitle: String,
    accent: Color,
) {
    Column(
        modifier = modifier
            .bhodlCard()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            style = BhodlTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = BhodlTheme.colors.textSecondary,
        )
        Text(
            text = value,
            style = BhodlTheme.typography.amountMedium.copy(fontWeight = FontWeight.SemiBold),
            color = accent,
        )
        Text(
            text = subtitle,
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textTertiary,
        )
    }
}

/**
 * Phase 8.3.4 — money-aware variant of [KpiCard]. When [value] is null
 * we mirror the original em-dash policy ("—" rendered as a plain Text
 * so the slot looks identical to the other KPI cards). When non-null
 * we delegate to the canonical [Money] composable with Compact
 * density: a single line "₿X.XX (€YY)" that fits the same vertical
 * rhythm as the string-valued KPI cards.
 *
 * The accent color carries through to the BTC primary line via the
 * Money composable's default `textPrimary` only when [value] is
 * AtNow — the wrapper passes the color through a Text override is
 * unnecessary because the BURN/FIRE cards already used `textSecondary`
 * / `accentNeon`. Keeping the Money composable's default keeps the
 * call site identical to the rest of the codebase; the visual delta
 * is the BTC primary + fiat secondary in one line instead of a
 * single-currency string.
 */
@Composable
private fun KpiCardMoney(
    modifier: Modifier = Modifier,
    label: String,
    value: MoneyValue?,
    subtitle: String,
    accent: Color,
) {
    Column(
        modifier = modifier
            .bhodlCard()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            style = BhodlTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = BhodlTheme.colors.textSecondary,
        )
        if (value == null) {
            // Em-dash placeholder identical in weight to the other
            // KpiCard's "—" so the visual rhythm doesn't break.
            Text(
                text = "—",
                style = BhodlTheme.typography.amountMedium.copy(fontWeight = FontWeight.SemiBold),
                color = accent,
            )
        } else {
            Money(
                value = value,
                density = MoneyDensity.Compact,
                primary = MoneyPrimary.Btc,
                showSecondary = true,
            )
        }
        Text(
            text = subtitle,
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textTertiary,
        )
    }
}

@Composable
private fun savingsAccent(pct: Int?): Color = when {
    pct == null -> BhodlTheme.colors.textTertiary
    pct >= 20 -> BhodlTheme.colors.financialPositive
    pct >= 0 -> BhodlTheme.colors.financialPending
    else -> BhodlTheme.colors.financialNegative
}

@Composable
private fun runwayAccent(months: Double?): Color = when {
    months == null -> BhodlTheme.colors.textTertiary
    months >= 12.0 -> BhodlTheme.colors.financialPositive
    months >= 6.0 -> BhodlTheme.colors.financialPending
    else -> BhodlTheme.colors.financialNegative
}

@Composable
private fun formatMonths(m: Double): String = stringResource(
    Res.string.trk_kpi_months,
    when {
        m >= 100.0 -> "99+"
        m >= 10.0 -> m.toInt().toString()
        else -> "%.1f".format(m)
    },
)

/**
 * Phase 8.5 — render-side accent for the OPP COST card.
 *  - positive value (BTC outperformed) → financialNegative (the user
 *    "lost" BTC by diversifying)
 *  - negative value (diversification beat BTC) → financialPositive
 *  - 0 or null → textTertiary (neutral / no signal)
 */
@Composable
private fun oppCostAccent(oppBtc: Double?): Color = io.bhodl.desktop.ui.signedAmountColor(
    // BTC outperformed (positive) reads as a loss: the colours are swapped.
    value = oppBtc,
    positive = BhodlTheme.colors.financialNegative,
    negative = BhodlTheme.colors.financialPositive,
    neutral = BhodlTheme.colors.textTertiary,
)

/**
 * Phase 8.5 — display the opportunity cost as a signed BTC string.
 * The displayed sign is the OPPOSITE of the [oppBtc] sign (a positive
 * opp cost means the user "lost" that many BTC, so we render "−X
 * BTC"; a negative one means they gained, rendered as "+X BTC"). The
 * absolute value goes through [fmt] (the user's Bitcoin unit, see
 * `rememberAmountFormatter`) so the sign glyph stays under our control.
 */
internal fun formatOppCost(
    oppBtc: Double,
    fmt: (Long) -> String,
    masked: Boolean = io.bhodl.desktop.ui.money.AmountMask.active,
): String {
    val absSat = (abs(oppBtc) * 100_000_000.0).toLong().coerceAtLeast(0L)
    // Opposite sign: BTC won (positive) → the user "lost" this much BTC.
    return "${io.bhodl.desktop.ui.amountSign(-oppBtc, masked = masked)}${fmt(absSat)}"
}

internal object KpiStripV7Tags {
    const val ROW: String = "kpi-strip-v7.row"
    const val SAVINGS: String = "kpi-strip-v7.savings"
    const val BURN: String = "kpi-strip-v7.burn"
    const val FIRE: String = "kpi-strip-v7.fire"
    const val RUNWAY: String = "kpi-strip-v7.runway"
    /** Phase 8.3.4 — opportunity cost placeholder (real compute in Phase 8.5). */
    const val OPP_COST: String = "kpi-strip-v7.opp-cost"
}
