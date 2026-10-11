package io.bhodl.desktop.ui.money

/**
 * Phase 8.3.1 — which side of the dual representation is the headline.
 *
 * Defaults to [Btc] everywhere — BHODL is a Bitcoin app, the unit of
 * account is BTC/sat, fiat is the conversion. The exception is the
 * four "operational surfaces" listed in the Phase 8.3 plan (bank CSV
 * import, current-month fiat-counterparty transactions, tax exports,
 * invoices) where the caller may pass [Fiat] to put the fiat number
 * on top. The user does not toggle this directly — it is a property
 * of the view, gated by the Settings advanced flags.
 */
public enum class MoneyPrimary {
    Btc,
    Fiat,
}
