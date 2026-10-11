package io.bhodl.desktop.domain

import io.bhodl.ffi.PaymentDirection
import io.bhodl.ffi.PaymentEvent
import kotlin.math.abs

/**
 * Phase 7.5 — Recurring transaction detector.
 *
 * Mines a list of [PaymentEvent]s for monthly or weekly recurring
 * outflows (subscriptions, bills, regular savings transfers seen from
 * the OUTGOING side). The output is a list of [RecurringCandidate]
 * objects that the UI surfaces via `RecurringCard` for "Accept as
 * Bill / Dismiss" actions.
 *
 * Algorithm:
 *  1. Filter to OUTGOING events with a non-null timestamp and amount > 0,
 *     excluding internal transfers (send_to_self / self-transfer).
 *  2. Group by a "counterparty" key. PaymentEvent carries no payee /
 *     BIP-329 label yet, so the key is the event's category (normalized:
 *     lowercase + trim + truncate to 30 chars, "uncategorized" when
 *     absent) plus a logarithmic amount bucket ([amountBucket], ~±10%
 *     wide), so a 12k-sat subscription and a 400k-sat rent in the same
 *     category don't merge. The v1 grouping by txid could never match
 *     (every on-chain tx has a unique txid) — the detector was inert.
 *     Known limitation: amounts straddling a bucket edge split into two
 *     groups; the key must stay per-event stable for the dismiss table.
 *  3. Skip groups with < [MIN_OCCURRENCES] events or keys in the
 *     dismissed set.
 *  4. Sort each group by timestamp ASC, compute inter-event intervals.
 *  5. Classify MONTHLY (28-32 days ±3) or WEEKLY (6-8 days ±1).
 *  6. Reject groups whose amount variance exceeds 10% — too noisy
 *     to be a real subscription.
 *  7. Estimate next occurrence at `last_ts + period`.
 *  8. Score confidence HIGH / MEDIUM / LOW based on occurrence count,
 *     interval stddev, and amount variance.
 */
internal const val MIN_OCCURRENCES: Int = 3

private const val DAY_SEC: Long = 86_400L
private const val MONTH_LO_SEC: Long = 28L * DAY_SEC
private const val MONTH_HI_SEC: Long = 32L * DAY_SEC
private const val WEEK_LO_SEC: Long = 6L * DAY_SEC
private const val WEEK_HI_SEC: Long = 8L * DAY_SEC
private const val MONTH_TOL_SEC: Long = 3L * DAY_SEC
private const val WEEK_TOL_SEC: Long = DAY_SEC
private const val AMOUNT_VARIANCE_THRESHOLD: Double = 0.10

public enum class RecurringFrequency(public val periodSec: Long) {
    MONTHLY(30L * DAY_SEC),
    WEEKLY(7L * DAY_SEC),
}

public enum class RecurringConfidence { HIGH, MEDIUM, LOW }

/**
 * Output candidate. `payeeKey` is the normalized string used by the
 * grouping and by the dismiss table — UI also surfaces it in the
 * accept flow so the user knows what's being matched.
 */
public data class RecurringCandidate(
    public val payeeKey: String,
    public val displayName: String,
    public val sampleEvents: List<PaymentEvent>,
    public val frequency: RecurringFrequency,
    public val avgAmountSat: ULong,
    public val nextEstimatedUnix: Long,
    public val confidence: RecurringConfidence,
)

internal fun detectRecurring(
    events: List<PaymentEvent>,
    nowUnix: Long,
    dismissedKeys: Set<String> = emptySet(),
): List<RecurringCandidate> {
    val candidates = mutableListOf<RecurringCandidate>()

    // 1. Filter to OUTGOING with timestamp and amount > 0; internal
    //    transfers are not spending.
    val outgoing = events.filter {
        it.direction == PaymentDirection.OUTGOING &&
            !it.isInternalTransfer() &&
            it.timestampUnix != null &&
            it.amountSat > 0UL
    }

    // 2. Group by counterparty key (category + amount bucket).
    val groups = outgoing.groupBy { recurringGroupKey(it) }

    for ((key, group) in groups) {
        if (key in dismissedKeys) continue
        if (group.size < MIN_OCCURRENCES) continue

        // 4. Sort by timestamp ASC.
        val sorted = group.sortedBy { it.timestampUnix!!.toLong() }
        val timestamps = sorted.map { it.timestampUnix!!.toLong() }

        // 5. Classify based on intervals.
        val intervals = (1 until timestamps.size).map { i -> timestamps[i] - timestamps[i - 1] }
        val frequency = classifyFrequency(intervals) ?: continue

        // 6. Amount variance check.
        val amounts = sorted.map { it.amountSat.toLong() }
        val avg = amounts.average()
        if (avg <= 0.0) continue
        val variance = amountVariance(amounts, avg)
        if (variance > AMOUNT_VARIANCE_THRESHOLD * 2) continue  // hard cap at 20%

        // 7. Estimate next.
        val lastTs = timestamps.last()
        val nextEstimated = lastTs + frequency.periodSec

        // 8. Score confidence.
        val intervalStddevSec = stddev(intervals.map { it.toDouble() })
        val confidence = scoreConfidence(
            occurrences = sorted.size,
            intervalStddevSec = intervalStddevSec,
            amountVariance = variance,
        )

        candidates += RecurringCandidate(
            payeeKey = key,
            displayName = recurringDisplayName(sorted.first()),
            sampleEvents = sorted,
            frequency = frequency,
            avgAmountSat = avg.toLong().coerceAtLeast(0L).toULong(),
            nextEstimatedUnix = nextEstimated,
            confidence = confidence,
        )
    }

    // Surface highest confidence first; tie-break by next-date (sooner first).
    return candidates.sortedWith(
        compareByDescending<RecurringCandidate> { it.confidence.ordinal == 0 }  // HIGH first
            .thenBy { it.nextEstimatedUnix },
    )
}

/** Normalize a payee/description string into a stable grouping key. */
internal fun normalizePayeeKey(raw: String): String {
    return raw.trim().lowercase().take(30)
}

/** Label used for events without a category in the grouping key. */
private const val UNCATEGORIZED_KEY: String = "uncategorized"

/** Log base of the amount buckets: consecutive buckets differ by ~20%. */
private val BUCKET_LOG_BASE: Double = kotlin.math.ln(1.2)

/**
 * Logarithmic amount bucket: `floor(ln(amount) / ln(1.2))`. Amounts
 * within ~±10% of each other usually share a bucket. Stable per event,
 * which the dismiss key requires.
 */
internal fun amountBucket(amountSat: ULong): Int {
    if (amountSat == 0UL) return 0
    return kotlin.math.floor(kotlin.math.ln(amountSat.toDouble()) / BUCKET_LOG_BASE).toInt()
}

/**
 * Grouping (and dismiss) key: `<normalized category>~<amount bucket>`.
 * See the algorithm notes at the top of the file.
 */
internal fun recurringGroupKey(ev: PaymentEvent): String {
    val cat = ev.category?.takeIf { it.isNotBlank() }?.let { normalizePayeeKey(it) }
        ?: UNCATEGORIZED_KEY
    return "$cat~${amountBucket(ev.amountSat)}"
}

/** Human label for a candidate: the category (or "Uncategorized"). */
private fun recurringDisplayName(ev: PaymentEvent): String =
    ev.category?.takeIf { it.isNotBlank() }?.trim()?.take(30) ?: "Uncategorized"

/**
 * Classify a sequence of inter-event intervals into a [RecurringFrequency].
 * Returns null when the intervals don't cleanly fit either a monthly
 * or weekly pattern within the per-frequency tolerance.
 */
internal fun classifyFrequency(intervalsSec: List<Long>): RecurringFrequency? {
    if (intervalsSec.isEmpty()) return null

    val monthlyOk = intervalsSec.all { interval ->
        interval in (MONTH_LO_SEC - MONTH_TOL_SEC)..(MONTH_HI_SEC + MONTH_TOL_SEC)
    }
    if (monthlyOk) return RecurringFrequency.MONTHLY

    val weeklyOk = intervalsSec.all { interval ->
        interval in (WEEK_LO_SEC - WEEK_TOL_SEC)..(WEEK_HI_SEC + WEEK_TOL_SEC)
    }
    if (weeklyOk) return RecurringFrequency.WEEKLY

    return null
}

private fun amountVariance(amounts: List<Long>, avg: Double): Double {
    if (amounts.isEmpty() || avg <= 0.0) return Double.MAX_VALUE
    val maxDev = amounts.maxOf { abs(it - avg) }
    return maxDev / avg
}

private fun stddev(xs: List<Double>): Double {
    if (xs.size < 2) return 0.0
    val mean = xs.average()
    val variance = xs.sumOf { (it - mean) * (it - mean) } / xs.size
    return kotlin.math.sqrt(variance)
}

/**
 * Phase 7.5 confidence rubric:
 *  - HIGH:   ≥4 occurrences AND intervals stddev ≤ 1 day AND amount variance ≤ 5%
 *  - MEDIUM: ≥3 occurrences AND intervals stddev ≤ 3 days AND amount variance ≤ 10%
 *  - LOW:    everything else (still surfaced, lets the user judge)
 */
internal fun scoreConfidence(
    occurrences: Int,
    intervalStddevSec: Double,
    amountVariance: Double,
): RecurringConfidence {
    val high = occurrences >= 4 &&
        intervalStddevSec <= DAY_SEC.toDouble() &&
        amountVariance <= 0.05
    if (high) return RecurringConfidence.HIGH

    val medium = occurrences >= 3 &&
        intervalStddevSec <= 3.0 * DAY_SEC.toDouble() &&
        amountVariance <= 0.10
    if (medium) return RecurringConfidence.MEDIUM

    return RecurringConfidence.LOW
}
