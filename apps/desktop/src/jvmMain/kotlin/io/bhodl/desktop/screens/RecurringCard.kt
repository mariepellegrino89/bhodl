package io.bhodl.desktop.screens
import androidx.compose.foundation.layout.heightIn
import io.bhodl.desktop.ui.rowHeightFor
import io.bhodl.desktop.domain.detectRecurring
import io.bhodl.desktop.domain.RecurringCandidate
import io.bhodl.desktop.domain.RecurringFrequency
import io.bhodl.desktop.domain.RecurringConfidence

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import io.bhodl.desktop.ui.money.Money
import io.bhodl.desktop.ui.money.MoneyDensity
import io.bhodl.desktop.ui.money.MoneyPrimary
import io.bhodl.desktop.ui.money.MoneySemantic
import io.bhodl.desktop.ui.money.MoneyValue
import io.bhodl.ui.theme.BhodlTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import io.bhodl.shared.resources.Res
import org.jetbrains.compose.resources.stringResource
import io.bhodl.shared.resources.trp_rec_accept
import io.bhodl.shared.resources.trp_rec_conf_high
import io.bhodl.shared.resources.trp_rec_conf_low
import io.bhodl.shared.resources.trp_rec_conf_med
import io.bhodl.shared.resources.trp_rec_dismiss
import io.bhodl.shared.resources.trp_rec_empty
import io.bhodl.shared.resources.trp_rec_monthly
import io.bhodl.shared.resources.trp_rec_more
import io.bhodl.shared.resources.trp_rec_next
import io.bhodl.shared.resources.trp_rec_title
import io.bhodl.shared.resources.trp_rec_weekly
import io.bhodl.desktop.ui.categoryDisplayName

/**
 * Phase 7.5 — Recurring detection card.
 *
 * Surfaces [RecurringCandidate]s detected by [detectRecurring] above
 * the Bills card. Each suggestion has two actions: "+ Bill" (accept
 * the candidate as a tracking_bills row) or "Dismiss" (write to
 * tracking_recurring_dismissed so the same suggestion stops showing).
 *
 * Phase 9.3 — when [candidates] is empty the card renders a one-line
 * zero-state instead of disappearing: a card that vanishes reads as
 * "the feature is gone", not "nothing detected yet" (finance-tracker
 * convention: empty states explain what will appear and when).
 */
@Composable
internal fun RecurringCard(
    candidates: List<RecurringCandidate>,
    onAccept: (RecurringCandidate) -> Unit,
    onDismiss: (RecurringCandidate) -> Unit,
) {
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
                text = stringResource(Res.string.trp_rec_title),
                style = BhodlTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = BhodlTheme.colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            if (candidates.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(BhodlTheme.colors.accentNeon.copy(alpha = 0.18f))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = candidates.size.toString(),
                        style = BhodlTheme.typography.labelMedium,
                        color = BhodlTheme.colors.accentNeon,
                    )
                }
            }
        }
        if (candidates.isEmpty()) {
            Text(
                text = stringResource(Res.string.trp_rec_empty),
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textTertiary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(BhodlTheme.spacing.md),
            )
            return@Column
        }
        // Limit visible count so a noisy detector doesn't take over
        // the dashboard scroll. The user can dismiss to reveal more.
        candidates.take(5).forEachIndexed { idx, candidate ->
            RecurringRow(
                candidate = candidate,
                isZebra = idx % 2 == 1,
                onAccept = { onAccept(candidate) },
                onDismiss = { onDismiss(candidate) },
            )
        }
        if (candidates.size > 5) {
            Text(
                text = stringResource(Res.string.trp_rec_more, candidates.size - 5),
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.textTertiary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(BhodlTheme.spacing.md),
            )
        }
    }
}

@Composable
private fun RecurringRow(
    candidate: RecurringCandidate,
    isZebra: Boolean,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    val bg = if (isZebra) BhodlTheme.colors.rowZebra else androidx.compose.ui.graphics.Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Item 15 — two text lines, density-aware minimum.
            .heightIn(min = BhodlTheme.density.rowHeightFor(2))
            .background(bg)
            .padding(horizontal = BhodlTheme.spacing.md, vertical = BhodlTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1.6f)) {
            Text(
                text = categoryDisplayName(candidate.displayName),
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textPrimary,
                maxLines = 1,
            )
            val freqLabel = when (candidate.frequency) {
                RecurringFrequency.MONTHLY -> stringResource(Res.string.trp_rec_monthly)
                RecurringFrequency.WEEKLY -> stringResource(Res.string.trp_rec_weekly)
            }
            // Phase 8.3.4 — average amount now goes through the
            // canonical `<Money>` composable in Compact density so the
            // BTC primary + fiat secondary stay consistent with the
            // rest of the dashboard. The frequency / next-date suffix
            // remains plain text on the same line.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Money(
                    value = MoneyValue.fromSat(
                        candidate.avgAmountSat.toLong(),
                        semantic = MoneySemantic.AtNow,
                    ),
                    density = MoneyDensity.Compact,
                    primary = MoneyPrimary.Btc,
                    showSecondary = true,
                    testTag = RecurringTags.amountTag(candidate.payeeKey),
                )
                Text(
                    text = stringResource(Res.string.trp_rec_next, freqLabel, formatDate(candidate.nextEstimatedUnix)),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textTertiary,
                    maxLines = 1,
                )
            }
        }
        ConfidenceChip(candidate.confidence)
        Row(
            modifier = Modifier.padding(start = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(BhodlTheme.colors.accentNeon.copy(alpha = 0.18f))
                    .clickable(onClick = onAccept)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .testTag(RecurringTags.acceptTag(candidate.payeeKey)),
            ) {
                Text(
                    text = stringResource(Res.string.trp_rec_accept),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.accentNeon,
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(BhodlTheme.colors.surfaceVariant)
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .testTag(RecurringTags.dismissTag(candidate.payeeKey)),
            ) {
                Text(
                    text = stringResource(Res.string.trp_rec_dismiss),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun ConfidenceChip(confidence: RecurringConfidence) {
    val (label, color) = when (confidence) {
        RecurringConfidence.HIGH -> stringResource(Res.string.trp_rec_conf_high) to BhodlTheme.colors.financialPositive
        RecurringConfidence.MEDIUM -> stringResource(Res.string.trp_rec_conf_med) to BhodlTheme.colors.financialPending
        RecurringConfidence.LOW -> stringResource(Res.string.trp_rec_conf_low) to BhodlTheme.colors.textTertiary
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = BhodlTheme.typography.labelMedium,
            color = color,
        )
    }
}

// UX review 2026-10 (rec 3) — month names in the app language.
private fun formatDate(unix: Long): String =
    Instant.ofEpochSecond(unix)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))

internal object RecurringTags {
    const val CARD: String = "recurring.card"
    fun acceptTag(payeeKey: String): String = "recurring.accept.$payeeKey"
    fun dismissTag(payeeKey: String): String = "recurring.dismiss.$payeeKey"
    /** Phase 8.3.4 — `<Money>` Compact carrying the candidate's avg amount. */
    fun amountTag(payeeKey: String): String = "recurring.amount.$payeeKey"
}
