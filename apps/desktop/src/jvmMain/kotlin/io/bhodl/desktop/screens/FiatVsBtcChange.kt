package io.bhodl.desktop.screens

/**
 * Phase 8.3.2 — direction of the "fiat vs BTC" change chip shown next
 * to the net-worth hero delta row.
 *
 * The subject of the chip is the *fiat*: when the BTC/fiat rate rises,
 * fiat has lost purchasing power vs BTC ([FiatDown]); when it falls,
 * fiat has gained ([FiatUp]). [Flat] absorbs sub-percent oscillations
 * so the chip doesn't flicker on noise.
 */
public enum class Direction { FiatDown, FiatUp, Flat }

/**
 * Phase 8.3.2 — outcome of [computeFiatVsBtcChange]. [deltaPct] is the
 * signed % change of the rate (positive = BTC up, fiat down).
 */
public data class FiatVsBtcChange(
    val deltaPct: Double,
    val direction: Direction,
)

/**
 * Phase 8.3.2 — derive a "fiat lost X% vs BTC" reading from the
 * `(timestamp, fiat_value_of_nw_at_then)` series the net-worth hero
 * already consumes.
 *
 * Logic (plan §5.1):
 *  - Returns `null` when there are fewer than 2 points (no
 *    period-over-period change to report).
 *  - Returns `null` when [currentSat] is 0 (no BTC means the
 *    fiat-of-nw ratio is undefined and the chip is irrelevant).
 *  - Approximates the rate at any point `t` as
 *    `fiat_value_at_t / currentBtc` — exact when the NW is pure BTC,
 *    drift grows with non-BTC assets. Acceptable for 8.3.2; Phase 8.4
 *    will refine with a true historical NW reconstruction.
 *  - Direction is [Direction.FiatDown] when the rate rose by more
 *    than 0.5% (BTC up → fiat lost), [Direction.FiatUp] when the rate
 *    fell by more than 0.5%, [Direction.Flat] otherwise.
 *
 * @param historySatFiat  list of `(unix_seconds, fiat_value_of_nw)`
 *                        pairs over the selected period.
 * @param currentSat      the current NW in sat (held constant across
 *                        the period in this approximation).
 */
public fun computeFiatVsBtcChange(
    historySatFiat: List<Pair<Long, Double>>,
    currentSat: ULong,
): FiatVsBtcChange? {
    if (historySatFiat.size < 2) return null
    if (currentSat == 0UL) return null
    val currentBtc = currentSat.toDouble() / 100_000_000.0
    if (currentBtc <= 0.0) return null
    val rateStart = historySatFiat.first().second / currentBtc
    val rateEnd = historySatFiat.last().second / currentBtc
    if (rateStart <= 0.0) return null
    val deltaPct = (rateEnd / rateStart - 1.0) * 100.0
    val direction = when {
        // Rate rose → fiat lost vs BTC.
        deltaPct > 0.5 -> Direction.FiatDown
        // Rate fell → fiat gained vs BTC.
        deltaPct < -0.5 -> Direction.FiatUp
        else -> Direction.Flat
    }
    return FiatVsBtcChange(deltaPct = deltaPct, direction = direction)
}
