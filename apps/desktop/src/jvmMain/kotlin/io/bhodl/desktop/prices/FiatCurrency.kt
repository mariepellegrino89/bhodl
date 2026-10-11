package io.bhodl.desktop.prices

/**
 * Phase 5.0 — fiat currency for the BHODL price overlay.
 *
 * `NONE` means the user has not enabled the fiat overlay at all —
 * amounts render only in sat / BTC. The other three are the currencies
 * we ship support for in Phase 5 (covers ~95% of European + US
 * users). More currencies arrive when we wire additional price
 * sources (e.g. CHF / GBP / JPY).
 */
public enum class FiatCurrency(public val code: String, public val symbol: String) {
    NONE("", ""),
    EUR("EUR", "€"),
    USD("USD", "$"),
    CHF("CHF", "CHF"),
}
