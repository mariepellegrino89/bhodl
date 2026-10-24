package io.bhodl.desktop.domain

import io.bhodl.ffi.EventCostBasisFfi
import io.bhodl.ffi.PaymentDirection
import io.bhodl.ffi.PaymentEvent

/**
 * BTC average cost (PLAN 4.5.8 "DCA performance") — what the bitcoin in
 * the tracked wallets cost, what it is worth now, and the gain/loss.
 *
 * ## Method: weighted average cost
 *
 * Walking confirmed transactions oldest first:
 *  - an **inflow** is a purchase: `held += sat`, `cost += sat × price`;
 *  - an **outflow** (a spend or a sale — economically the same thing) is
 *    a disposal at `price`: it removes `sat` at the current average cost,
 *    `realized += sat × price − sat × avg`, and leaves the average of
 *    what is still held unchanged.
 *
 * `price` defaults to the BTC market rate of the transaction's UTC day
 * and can be replaced per transaction by the user. A transaction can
 * also be excluded (a refund, a move to an exchange account, …);
 * internal transfers tagged `send_to_self` are excluded automatically.
 *
 * ## One flow per transaction
 *
 * The legs of a txid across the user's wallets are netted, so a payment
 * whose change lands in another own wallet counts only what actually
 * left, and a transfer between own wallets counts only its fee. Callers
 * pass the per-wallet legs with wallet aliases already collapsed
 * ([dropAliasedEvents]) — the same input [detectSendToSelf] takes.
 *
 * Unconfirmed transactions are left out until they confirm (no day to
 * price them at). An inflow whose price is unknown can't be costed and
 * is skipped; an outflow whose price is unknown still leaves the stack
 * at average cost, only its realized gain is missing. Both are counted
 * in [BtcCostBasisSummary.missingPriceCount] so the UI can say so.
 *
 * Not tax advice: jurisdictions differ on the method to use.
 */
internal data class BtcCostBasisSummary(
    /** Sats the calculation covers (inflows − outflows, never negative). */
    val heldSat: Long,
    /** Fiat cost of [heldSat] at average cost. */
    val costFiat: Double,
    /** Average fiat price per BTC of [heldSat]; null when nothing is held. */
    val averagePrice: Double?,
    /** Gain/loss already realized by outflows. */
    val realizedFiat: Double,
    /** [heldSat] at [currentPrice]; null without a current price. */
    val currentValueFiat: Double?,
    /** [currentValueFiat] − [costFiat]; null without a current price. */
    val unrealizedFiat: Double?,
    /** [unrealizedFiat] / [costFiat]; null when either is unavailable. */
    val unrealizedRatio: Double?,
    val acquiredSat: Long,
    val disposedSat: Long,
    /** Outflow sats beyond what the calculation held (bought before the
     *  first tracked tx, or in an excluded tx) — left out entirely. */
    val uncoveredOutflowSat: Long,
    val missingPriceCount: Int,
    val excludedCount: Int,
    val rows: List<BtcCostBasisRow>,
)

/** Why a transaction does or doesn't count. */
internal enum class CostBasisStatus { COUNTED, EXCLUDED, SEND_TO_SELF, MISSING_PRICE, PENDING }

/** Where a row's price came from. */
internal enum class CostBasisPriceSource { MARKET, USER }

/** One transaction as the calculation saw it (newest first in the summary). */
internal data class BtcCostBasisRow(
    /** A representative leg (the user-facing event to edit). */
    val event: PaymentEvent,
    val incoming: Boolean,
    /** Net sats of the transaction across the user's wallets. */
    val amountSat: Long,
    val timestampUnix: Long?,
    val price: Double?,
    val priceSource: CostBasisPriceSource?,
    val status: CostBasisStatus,
)

internal const val SATS_PER_BTC: Double = 100_000_000.0
private const val SECS_PER_DAY: Long = 86_400L

/** UTC midnight of [unix] — the key the rate history is stored under. */
internal fun utcDay(unix: Long): Long = unix - Math.floorMod(unix, SECS_PER_DAY)

/** Distinct UTC days of the confirmed transactions in [legs]. */
internal fun costBasisDays(legs: List<PaymentEvent>): Set<Long> =
    legs.mapNotNullTo(mutableSetOf()) { leg -> leg.timestampUnix?.let { utcDay(it.toLong()) } }

/**
 * Compute the average-cost view.
 *
 * @param legs per-wallet legs, aliases collapsed, already restricted to
 *   the wallets the caller wants counted.
 * @param inputs the user's price / exclusion per txid.
 * @param currency ISO code the result is expressed in; a user price
 *   stored in another currency is ignored (the market rate applies).
 * @param marketPriceAt BTC price in [currency] on a UTC day, or null.
 * @param currentPrice live BTC price in [currency], or null.
 */
internal fun computeBtcCostBasis(
    legs: List<PaymentEvent>,
    inputs: Map<String, EventCostBasisFfi>,
    currency: String,
    marketPriceAt: (dayUnix: Long) -> Double?,
    currentPrice: Double?,
): BtcCostBasisSummary {
    val transactions = legs.groupBy { it.txidOrPaymentId }.mapNotNull { (txid, txLegs) ->
        val net = txLegs.sumOf { leg ->
            val amount = leg.amountSat.toLong()
            when (leg.direction) {
                PaymentDirection.INCOMING -> amount
                PaymentDirection.OUTGOING -> -amount
                PaymentDirection.SELF_TRANSFER -> 0L
            }
        }
        if (net == 0L) return@mapNotNull null
        val representative = txLegs.firstOrNull { leg ->
            (net > 0) == (leg.direction == PaymentDirection.INCOMING)
        } ?: txLegs.first()
        PendingTx(
            txid = txid,
            event = representative,
            net = net,
            timestampUnix = txLegs.firstNotNullOfOrNull { it.timestampUnix }?.toLong(),
            sendToSelf = txLegs.any { it.cryptoEventKind == SEND_TO_SELF },
        )
    }

    var held = 0L
    var cost = 0.0
    var realized = 0.0
    var acquired = 0L
    var disposed = 0L
    var uncovered = 0L
    var missingPrice = 0
    var excluded = 0
    val rows = ArrayList<BtcCostBasisRow>(transactions.size)

    val ordered = transactions.sortedWith(
        compareBy<PendingTx>({ it.timestampUnix == null }, { it.timestampUnix ?: 0L }, { it.txid }),
    )
    for (tx in ordered) {
        val incoming = tx.net > 0
        val sat = kotlin.math.abs(tx.net)
        val input = inputs[tx.txid]
        val userPrice = input?.pricePerBtc?.takeIf { input.currency.equals(currency, ignoreCase = true) }
        val price = userPrice ?: tx.timestampUnix?.let { marketPriceAt(utcDay(it)) }
        val source = when {
            userPrice != null -> CostBasisPriceSource.USER
            price != null -> CostBasisPriceSource.MARKET
            else -> null
        }
        val status = when {
            tx.timestampUnix == null -> CostBasisStatus.PENDING
            input?.excluded == true -> CostBasisStatus.EXCLUDED
            tx.sendToSelf -> CostBasisStatus.SEND_TO_SELF
            price == null -> CostBasisStatus.MISSING_PRICE
            else -> CostBasisStatus.COUNTED
        }
        when (status) {
            CostBasisStatus.EXCLUDED, CostBasisStatus.SEND_TO_SELF -> excluded++
            CostBasisStatus.PENDING -> Unit
            CostBasisStatus.MISSING_PRICE -> {
                missingPrice++
                // The stack still shrinks; only the realized part is unknown.
                if (!incoming) {
                    val out = minOf(sat, held)
                    if (held > 0) cost -= cost * out / held
                    held -= out
                    disposed += out
                    uncovered += sat - out
                }
            }
            CostBasisStatus.COUNTED -> {
                val p = price!!
                if (incoming) {
                    held += sat
                    cost += sat / SATS_PER_BTC * p
                    acquired += sat
                } else {
                    val out = minOf(sat, held)
                    if (out > 0) {
                        val outCost = cost * out / held
                        realized += out / SATS_PER_BTC * p - outCost
                        cost -= outCost
                        held -= out
                        disposed += out
                    }
                    uncovered += sat - out
                }
            }
        }
        rows += BtcCostBasisRow(
            event = tx.event,
            incoming = incoming,
            amountSat = sat,
            timestampUnix = tx.timestampUnix,
            price = price,
            priceSource = source,
            status = status,
        )
    }
    if (held == 0L) cost = 0.0

    val currentValue = currentPrice?.let { held / SATS_PER_BTC * it }
    val unrealized = currentValue?.let { it - cost }
    return BtcCostBasisSummary(
        heldSat = held,
        costFiat = cost,
        averagePrice = if (held > 0) cost / (held / SATS_PER_BTC) else null,
        realizedFiat = realized,
        currentValueFiat = currentValue,
        unrealizedFiat = unrealized,
        unrealizedRatio = unrealized?.takeIf { cost > 0.0 }?.let { it / cost },
        acquiredSat = acquired,
        disposedSat = disposed,
        uncoveredOutflowSat = uncovered,
        missingPriceCount = missingPrice,
        excludedCount = excluded,
        rows = rows.asReversed().toList(),
    )
}

private class PendingTx(
    val txid: String,
    val event: PaymentEvent,
    val net: Long,
    val timestampUnix: Long?,
    val sendToSelf: Boolean,
)
