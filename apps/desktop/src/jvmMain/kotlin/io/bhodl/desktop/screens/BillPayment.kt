package io.bhodl.desktop.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.ui.SlideOverPanel
import io.bhodl.desktop.ui.categoryDisplayName
import io.bhodl.desktop.ui.displayAmount
import io.bhodl.ffi.PaymentDirection
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.TrackingBillFfi
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.bill_pay_body
import io.bhodl.shared.resources.bill_pay_body_category
import io.bhodl.shared.resources.bill_pay_likely
import io.bhodl.shared.resources.bill_pay_none
import io.bhodl.shared.resources.bill_pay_only_mark
import io.bhodl.shared.resources.bill_pay_outside
import io.bhodl.shared.resources.bill_pay_title
import io.bhodl.shared.resources.common_cancel
import io.bhodl.ui.theme.BhodlTheme
import org.jetbrains.compose.resources.stringResource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs

/** `<wallet_id>|<txid>` — how a bill points at the transaction that paid it (schema v8). */
internal fun billPaidRef(event: PaymentEvent): String = "${event.walletId}|${event.txidOrPaymentId}"

/** How close a payment must be to the bill's amount to be "likely" (sat move with the BTC price). */
private const val FIXED_TOLERANCE = 0.25
private const val VARIABLE_TOLERANCE = 0.6

/** Days before the 1st of the month a payment can still pay this month's bill. */
private const val EARLY_DAYS = 7L

private const val MAX_CANDIDATES = 8

/** One outgoing transaction that may have paid a bill. */
internal data class BillCandidate(val event: PaymentEvent, val likely: Boolean)

/**
 * Registry schema v8 — the transactions that may have paid [bill] this
 * month: outgoing, from a week before the 1st on, not already linked to
 * another bill. Best first: the bill's category, then uncategorised,
 * then the closest amount. "Likely" = the bill's category (or none) and
 * an amount within the tolerance (wider for variable bills).
 */
internal fun billPaymentCandidates(
    bill: TrackingBillFfi,
    events: List<PaymentEvent>,
    bills: List<TrackingBillFfi>,
    today: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): List<BillCandidate> {
    val from = today.withDayOfMonth(1).minusDays(EARLY_DAYS).atStartOfDay(zone).toEpochSecond()
    val linked = bills.filter { it.id != bill.id }.mapNotNull { it.paidRef }.toSet()
    val target = bill.amountSat.toDouble()
    val tolerance = if (bill.variable) VARIABLE_TOLERANCE else FIXED_TOLERANCE
    val category = bill.category?.trim()?.takeIf { it.isNotEmpty() }
    fun rank(e: PaymentEvent): Int = when {
        category != null && e.category.equals(category, ignoreCase = true) -> 0
        e.category.isNullOrBlank() -> 1
        else -> 2
    }
    fun distance(e: PaymentEvent): Double = if (target > 0) abs(e.amountSat.toDouble() - target) / target else 0.0
    return events.asSequence()
        .filter { it.direction == PaymentDirection.OUTGOING && it.cryptoEventKind != "send_to_self" }
        .filter { (it.timestampUnix?.toLong() ?: Long.MAX_VALUE) >= from }
        .filter { billPaidRef(it) !in linked }
        .sortedWith(compareBy<PaymentEvent>({ rank(it) }, { distance(it) }))
        .take(MAX_CANDIDATES)
        .map { BillCandidate(it, likely = rank(it) < 2 && distance(it) <= tolerance) }
        .toList()
}

internal object BillPayTags {
    const val OUTSIDE: String = "bill-pay.outside"
    const val ONLY_MARK: String = "bill-pay.only-mark"
    const val NONE: String = "bill-pay.none"
    fun candidate(index: Int): String = "bill-pay.candidate.$index"
}

/**
 * Registry schema v8 — "Mark paid" on a bill: pick the transaction that
 * paid it (it then counts in the bill's budget category), record a
 * payment made outside the wallets, or only tick it off.
 */
@Composable
internal fun BillPayDialog(
    bill: TrackingBillFfi,
    candidates: List<BillCandidate>,
    walletLabel: (String) -> String,
    onDismiss: () -> Unit,
    onLink: (PaymentEvent) -> Unit,
    onPayOutside: () -> Unit,
    onOnlyMark: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    SlideOverPanel(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.bill_pay_title, bill.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val category = bill.category?.takeIf { it.isNotBlank() }
                Text(
                    if (category != null) stringResource(Res.string.bill_pay_body_category, categoryDisplayName(category))
                    else stringResource(Res.string.bill_pay_body),
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textTertiary,
                )
                if (candidates.isEmpty()) {
                    Text(
                        stringResource(Res.string.bill_pay_none),
                        style = BhodlTheme.typography.bodyMedium,
                        color = BhodlTheme.colors.textSecondary,
                        modifier = Modifier.testTag(BillPayTags.NONE),
                    )
                }
                candidates.forEachIndexed { i, c ->
                    val e = c.event
                    val day = e.timestampUnix?.let { Instant.ofEpochSecond(it.toLong()).atZone(zone).toLocalDate().format(dateFormat) }.orEmpty()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(BhodlTheme.colors.surfaceVariant)
                            .clickable { onLink(e) }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                            .testTag(BillPayTags.candidate(i)),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                walletLabel(e.walletId),
                                style = BhodlTheme.typography.bodyMedium,
                                color = BhodlTheme.colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val meta = listOfNotNull(day, e.category?.let { categoryDisplayName(it) }).joinToString(" · ")
                            Text(meta, style = BhodlTheme.typography.labelMedium, color = BhodlTheme.colors.textTertiary)
                        }
                        if (c.likely) {
                            Text(stringResource(Res.string.bill_pay_likely), style = BhodlTheme.typography.labelMedium, color = BhodlTheme.colors.financialPositive)
                        }
                        Text(displayAmount(e.amountSat.toLong()), style = BhodlTheme.typography.mono, color = BhodlTheme.colors.textPrimary)
                    }
                }
                TextButton(onClick = onPayOutside, modifier = Modifier.testTag(BillPayTags.OUTSIDE)) {
                    Text(stringResource(Res.string.bill_pay_outside), color = BhodlTheme.colors.brandPrimary)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onOnlyMark, modifier = Modifier.testTag(BillPayTags.ONLY_MARK)) {
                Text(stringResource(Res.string.bill_pay_only_mark), color = BhodlTheme.colors.textSecondary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) } },
    )
}
