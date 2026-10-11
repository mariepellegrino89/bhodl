package io.bhodl.desktop.screens
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.bhodl.desktop.ui.ChartDataTable
import io.bhodl.desktop.ui.ChartTableToggleButton
import io.bhodl.desktop.ui.rememberAmountFormatter
import io.bhodl.desktop.domain.forecastCashFlow

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.ui.Sparkline
import io.bhodl.desktop.ui.bhodlCard
import io.bhodl.desktop.ui.displayAmount
import io.bhodl.ui.theme.BhodlTheme
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.trk_cashflow_by_date
import io.bhodl.shared.resources.trk_cashflow_in_90_days
import io.bhodl.shared.resources.trk_cashflow_title
import org.jetbrains.compose.resources.stringResource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * Phase 7.6 — Cash-flow forecast card.
 *
 * Renders the 90-day balance projection produced by [forecastCashFlow]
 * as a sparkline + a footer summary "In 90 days: ~ X sat (Δ +/-Y%)".
 *
 * The card hides itself when the series is empty (no inputs to project
 * with). When the series exists but contains a sub-zero final value
 * the footer color flips red so the user immediately notices the
 * runway problem.
 */
@Composable
internal fun CashFlowForecastCard(
    series: List<Pair<Long, Long>>,
    modifier: Modifier = Modifier,
) {
    if (series.size < 2) return

    val first = series.first()
    val last = series.last()
    val deltaPct = if (first.second != 0L) {
        ((last.second - first.second).toDouble() / abs(first.second).toDouble() * 100.0).toInt()
    } else 0
    val isFinalNegative = last.second < 0L
    // UX review 2026-10 (item 15) — sparkline ↔ table toggle.
    var showTable by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .bhodlCard()
            .padding(16.dp)
            .testTag(CashFlowForecastCardTags.CARD),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(Res.string.trk_cashflow_title),
                style = BhodlTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = BhodlTheme.colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            ChartTableToggleButton(showTable = showTable, onToggle = { showTable = !showTable })
        }
        // Sparkline expects List<Pair<Long, Double>>. Convert the
        // signed balance to Double; the chart auto-scales the range.
        if (showTable) {
            val titles = chartColumnTitles()
            ChartDataTable(seriesTable(series, titles.balance, titles, rememberAmountFormatter()))
        } else Sparkline(
            points = series.map { it.first to it.second.toDouble() },
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp),
            lineColor = if (isFinalNegative) BhodlTheme.colors.financialNegative
                else BhodlTheme.colors.accentNeon,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(Res.string.trk_cashflow_in_90_days, formatSignedSat(last.second)),
                style = BhodlTheme.typography.bodyMedium,
                color = if (isFinalNegative) BhodlTheme.colors.financialNegative
                    else BhodlTheme.colors.textPrimary,
            )
            Text(
                text = "Δ ${if (deltaPct >= 0) "+" else ""}${deltaPct}%",
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.textTertiary,
            )
        }
        Text(
            text = stringResource(Res.string.trk_cashflow_by_date, formatDate(last.first)),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textTertiary,
        )
    }
}

// App language (Main.kt sets the default locale): "12 Jan 2027" / "12 gen 2027".
private fun dateFormatter(): DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())

private fun formatDate(unix: Long): String =
    Instant.ofEpochSecond(unix)
        .atZone(ZoneId.systemDefault())
        .format(dateFormatter())

@Composable
private fun formatSignedSat(signed: Long): String {
    // Item 13 — no sign while the privacy mode masks the amount.
    if (io.bhodl.desktop.ui.money.amountsMasked()) return displayAmount(abs(signed).toULong())
    return if (signed >= 0) {
        "~ ${displayAmount(signed.toULong())}"
    } else {
        "${io.bhodl.desktop.ui.amountSign(signed)}${displayAmount(abs(signed).toULong())}"
    }
}

internal object CashFlowForecastCardTags {
    const val CARD: String = "cash-flow-forecast.card"
}
