package io.bhodl.desktop.domain

import io.bhodl.ffi.PaymentDirection
import io.bhodl.ffi.PaymentEvent

/**
 * Shared "what actually left / entered the user's custody" view of a
 * [PaymentEvent], used by every tracker aggregate (KPIs, balance
 * series, category slices, flow bars, finance KPIs, forecast).
 *
 * Two kinds of event move funds between the user's own wallets and
 * must NOT count as income or spending:
 *  - `direction == SELF_TRANSFER` (net zero inside one wallet);
 *  - `cryptoEventKind == "send_to_self"` (a transfer between two
 *    registered wallets — the merged stream keeps only one leg, whose
 *    `amountSat` is the transferred amount, not an external flow).
 *
 * For a send_to_self the only value that really leaves custody is the
 * mining fee, so it counts as an outflow when known ([PaymentEvent.feeSat]).
 */
internal fun PaymentEvent.isInternalTransfer(): Boolean =
    direction == PaymentDirection.SELF_TRANSFER || cryptoEventKind == SEND_TO_SELF

/** Amount that entered the user's custody from outside (0 for internal transfers). */
internal fun PaymentEvent.externalInflowSat(): ULong = when {
    isInternalTransfer() -> 0UL
    direction == PaymentDirection.INCOMING -> amountSat
    else -> 0UL
}

/**
 * Amount that left the user's custody. For an internal transfer this
 * is just the fee (0 when unknown); for a regular OUTGOING event it is
 * the full amount (which already includes the fee — `amountSat` is the
 * wallet's net delta).
 */
internal fun PaymentEvent.externalOutflowSat(): ULong = when {
    cryptoEventKind == SEND_TO_SELF -> feeSat ?: 0UL
    direction == PaymentDirection.SELF_TRANSFER -> 0UL
    direction == PaymentDirection.OUTGOING -> amountSat
    else -> 0UL
}

/** Signed external net (inflow − outflow) in sat. */
internal fun PaymentEvent.externalSignedNetSat(): Long =
    externalInflowSat().toLong() - externalOutflowSat().toLong()
