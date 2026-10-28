package io.bhodl.desktop.data

import io.bhodl.desktop.domain.fiatToSatInCurrency
import io.bhodl.desktop.prices.FiatCurrency
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.ffi.LoanScheduleFfi
import io.bhodl.ffi.TrackingLiabilityFfi

/**
 * Schema v10 — mortgages and loans with an amortization plan. A debt
 * whose details carry its terms (original amount, start date, rate,
 * installment or end date) is worth what its plan says is left today,
 * adjusted by the installments and extra repayments recorded on it;
 * the registry computes the plan ([LoanScheduleFfi]), this file turns
 * it into the sat the net worth counts.
 */
internal object LoanPlans {
    /** How a manual entry pays a debt, as stored in the registry. */
    const val INSTALLMENT: String = "installment"
    const val EXTRA_SHORTEN_TERM: String = "extra_term"
    const val EXTRA_LOWER_PAYMENT: String = "extra_payment"

    /** Liability kinds that can carry a plan. */
    val PLANNED_KINDS: Set<String> = setOf("mortgage", "loan")

    /** Currency of a plan whose details name none: the dashboard's. */
    fun defaultCurrency(): String =
        SettingsStore.fiatCurrency.value.takeIf { it != FiatCurrency.NONE }?.code ?: FiatCurrency.EUR.code
}

/**
 * [liabilities] with each planned debt's balance replaced by what its
 * plan leaves today, at today's rate of its currency. Without a rate the
 * stored balance stays.
 */
internal fun withPlannedBalances(
    liabilities: List<TrackingLiabilityFfi>,
    schedules: Map<Long, LoanScheduleFfi>,
    rateFor: (currency: String) -> Double?,
): List<TrackingLiabilityFfi> = liabilities.map { l ->
    val plan = schedules[l.id] ?: return@map l
    val sat = if (plan.balanceNow <= 0.0) 0UL else fiatToSatInCurrency(plan.balanceNow, plan.currency, rateFor)
    if (sat == null) l else l.copy(amountSat = sat)
}
