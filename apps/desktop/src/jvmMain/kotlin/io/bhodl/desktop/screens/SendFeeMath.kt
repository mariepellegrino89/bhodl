package io.bhodl.desktop.screens

import io.bhodl.ffi.FeeEstimates
import io.bhodl.ffi.UtxoRecord

/**
 * UX review 2026-10 (§4 item 6) — fee presets framed as *total cost +
 * time* (Bitcoin Design Guide "send fees"), computed before any PSBT
 * exists. Pure integer arithmetic, unit-tested in `SendFeeMathTest`.
 *
 * The total is an **estimate** for the preset chips only ("≈"): the
 * Review screen always shows the exact fee Rust decodes from the PSBT.
 */

/**
 * Fee urgency presets. [blockTarget] mirrors the Esplora buckets the
 * core collapses estimates into (`send_policy::fee_buckets_from_raw`:
 * 1 / 3 / 6 blocks); ~10 minutes per block gives the ETA.
 */
internal enum class FeeTier(val blockTarget: Int) {
    Economy(6),
    Normal(3),
    Priority(1),
    ;

    val etaMinutes: Int get() = blockTarget * MINUTES_PER_BLOCK

    fun rateOf(estimates: FeeEstimates): Long = when (this) {
        Economy -> estimates.economySatPerVb
        Normal -> estimates.normalSatPerVb
        Priority -> estimates.prioritySatPerVb
    }.toLong()
}

internal const val MINUTES_PER_BLOCK: Int = 10

/**
 * Virtual size of a BIP86 (P2TR key-path) single-sig transaction in
 * weight units → vbytes, rounded up:
 *  - 42 WU fixed: version, locktime, in/out counts (40) + segwit
 *    marker & flag (2);
 *  - 230 WU per key-path input: 41-byte non-witness part ×4 plus a
 *    66-byte witness (item count, length, 64-byte Schnorr signature);
 *  - 172 WU per P2TR output: 43 bytes ×4.
 * 1 input + 2 outputs → 154 vB, the familiar taproot payment size.
 */
internal object TaprootTxSize {
    const val OVERHEAD_WU: Long = 42
    const val INPUT_WU: Long = 230
    const val OUTPUT_WU: Long = 172

    fun vsize(inputs: Int, outputs: Int): Long {
        val weight = OVERHEAD_WU + INPUT_WU * inputs.coerceAtLeast(1) + OUTPUT_WU * outputs.coerceAtLeast(1)
        return (weight + 3) / 4
    }
}

/** One preset chip: rate, estimated size / total fee and ETA. */
internal data class FeeQuote(
    val rateSatVb: Long,
    val vsize: Long,
    val feeSat: Long,
    val etaMinutes: Int?,
)

/**
 * How many inputs auto coin selection will roughly need to pay
 * [amountSat] at [rateSatVb]: largest spendable coins first (frozen and
 * unconfirmed coins are excluded, as the core's selection does) until
 * amount + fee are covered. Falls back to 1 when the wallet has no
 * coins or cannot cover the amount (the build will then explain why).
 */
internal fun estimateInputCount(
    spendableValuesSat: List<Long>,
    amountSat: Long,
    rateSatVb: Long,
    outputs: Int,
): Int {
    if (spendableValuesSat.isEmpty() || amountSat <= 0) return 1
    var total = 0L
    var count = 0
    for (value in spendableValuesSat.sortedDescending()) {
        total += value
        count++
        val fee = rateSatVb * TaprootTxSize.vsize(count, outputs)
        if (total >= amountSat + fee) return count
    }
    return count.coerceAtLeast(1)
}

/** Coins auto selection may use: confirmed and not frozen. */
internal fun spendableValues(utxos: List<UtxoRecord>): List<Long> =
    utxos.filter { it.confirmed && !it.frozen }.map { it.valueSat.toLong() }

/**
 * Shape of the transaction the form describes, for the fee preview:
 *  - Send MAX drains every spendable coin into the recipients (no change);
 *  - coin control pins exactly the selected inputs;
 *  - otherwise auto selection ([estimateInputCount]) plus one change output.
 */
internal fun feeQuote(
    rateSatVb: Long,
    etaMinutes: Int?,
    recipientCount: Int,
    amountSat: Long,
    sendMax: Boolean,
    pinnedInputCount: Int,
    spendableValuesSat: List<Long>,
): FeeQuote {
    val recipients = recipientCount.coerceAtLeast(1)
    val (inputs, outputs) = when {
        sendMax -> spendableValuesSat.size.coerceAtLeast(1) to recipients
        pinnedInputCount > 0 -> pinnedInputCount to recipients + 1
        else -> estimateInputCount(spendableValuesSat, amountSat, rateSatVb, recipients + 1) to recipients + 1
    }
    val vsize = TaprootTxSize.vsize(inputs, outputs)
    return FeeQuote(
        rateSatVb = rateSatVb,
        vsize = vsize,
        feeSat = rateSatVb * vsize,
        etaMinutes = etaMinutes,
    )
}
