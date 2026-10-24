package io.bhodl.desktop.screens

import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.PaymentEventLiteFfi

/**
 * GAP-017 — on-chain events fed to the net-worth history: only those of
 * wallets counted in the totals, so the sparkline and its delta agree
 * with the hero (which already drops [excludedWalletIds]).
 */
internal fun netWorthHistoryEvents(
    events: List<PaymentEvent>,
    excludedWalletIds: Set<String>,
): List<PaymentEventLiteFfi> =
    buildPaymentEventLite(
        if (excludedWalletIds.isEmpty()) events else events.filter { it.walletId !in excludedWalletIds },
    )
