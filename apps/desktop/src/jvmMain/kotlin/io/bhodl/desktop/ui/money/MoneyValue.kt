package io.bhodl.desktop.ui.money

/**
 * Phase 8.3.1 — single dual-currency representation passed into the
 * [Money] composable.
 *
 * The two magnitudes can each be `null`; the composable's resolution
 * logic (plan §3.2) decides which side is rendered and which derives
 * from the other via [rateBtcFiat] or the live [io.bhodl.desktop.prices.PriceCache].
 *
 * @property sat               signed BTC-side magnitude. Long so that
 *                             delta / P&L can carry a sign.
 * @property fiatAmount        fiat-side magnitude (positive or signed
 *                             depending on [semantic]).
 * @property fiatCurrency      ISO-4217 uppercase code; null when no
 *                             fiat side is meaningful.
 * @property semantic          drives both label suffix and color (see
 *                             [MoneySemantic]).
 * @property rateBtcFiat       optional snapshot of the BTC-in-fiat rate
 *                             relevant for this value (e.g. at-purchase
 *                             for a lot, at-snapshot for a manual
 *                             asset). null + AtNow means "use live
 *                             PriceCache"; null + AtCost means "BTC
 *                             equivalent unavailable" — the BTC line
 *                             renders an em-dash.
 */
public data class MoneyValue(
    val sat: Long? = null,
    val fiatAmount: Double? = null,
    val fiatCurrency: String? = null,
    val semantic: MoneySemantic = MoneySemantic.AtNow,
    val rateBtcFiat: Double? = null,
) {

    public companion object {

        /**
         * Build a [MoneyValue] from a sat magnitude. fiat side is left
         * unpopulated — the composable derives it from the live rate
         * (via PriceCache) when [semantic] is [MoneySemantic.AtNow].
         */
        public fun fromSat(
            sat: Long,
            currency: String? = null,
            semantic: MoneySemantic = MoneySemantic.AtNow,
        ): MoneyValue = MoneyValue(
            sat = sat,
            fiatAmount = null,
            fiatCurrency = currency,
            semantic = semantic,
            rateBtcFiat = null,
        )

        /**
         * Build a [MoneyValue] from a fiat magnitude + a rate snapshot.
         * The composable derives the sat side from `(amount / rate) *
         * 100_000_000`. Negative amounts are passed through with their
         * sign (used by the P&L cell on a sold lot).
         */
        public fun fromFiat(
            amount: Double,
            currency: String,
            rate: Double,
            semantic: MoneySemantic = MoneySemantic.AtCost,
        ): MoneyValue {
            val derivedSat: Long? = if (rate > 0.0) {
                ((amount / rate) * 100_000_000.0).toLong()
            } else {
                null
            }
            return MoneyValue(
                sat = derivedSat,
                fiatAmount = amount,
                fiatCurrency = currency,
                semantic = semantic,
                rateBtcFiat = rate,
            )
        }
    }
}
