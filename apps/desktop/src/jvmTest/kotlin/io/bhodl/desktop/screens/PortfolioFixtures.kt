package io.bhodl.desktop.screens

import io.bhodl.ffi.TrackingInvestmentFfi
import io.bhodl.ffi.TrackingInvestmentLotFfi
import io.bhodl.ffi.TrackingInvestmentSellFfi
import io.bhodl.ffi.TrackingLiabilityFfi
import io.bhodl.ffi.TrackingLotDisposalFfi
import io.bhodl.ffi.TrackingManualAssetFfi
import java.time.LocalDate

/** Shared portfolio fixtures for the card-model, UI and screenshot tests. */
internal object PortfolioFixtures {
    val TODAY: LocalDate = LocalDate.of(2026, 10, 25)
    val NOW: Long = TODAY.toEpochDay() * 86_400L

    fun daysAgo(d: Long): Long = NOW - d * 86_400L

    fun investment(
        id: Long,
        name: String,
        assetType: String,
        qty: Double,
        cost: Double,
        price: Double?,
        unit: String = "shares",
        ccy: String = "EUR",
        ticker: String? = null,
    ) = TrackingInvestmentFfi(
        id = id, name = name, assetType = assetType, ticker = ticker, quantity = qty, unit = unit,
        costBasisFiat = cost, costBasisCurrency = ccy, purchaseUnix = daysAgo(400),
        manualCurrentValue = price, fetchedCurrentValue = null, lastPricedAtUnix = null,
        notes = null, costBasisMethod = "fifo", createdAtUnix = daysAgo(400),
    )

    fun lot(id: Long, invId: Long, qty: Double, price: Double, daysAgo: Long, original: Double = qty, fees: Double = 0.0) =
        TrackingInvestmentLotFfi(
            id = id, investmentId = invId, quantity = qty, pricePerUnit = price, feesFiat = fees,
            purchaseUnix = daysAgo(daysAgo), notes = null, createdAtUnix = daysAgo(daysAgo),
            btcPerUnitAtPurchase = null, originalQuantity = original,
        )

    fun sell(id: Long, invId: Long, qty: Double, price: Double, daysAgo: Long) = TrackingInvestmentSellFfi(
        id = id, investmentId = invId, saleUnix = daysAgo(daysAgo), salePricePerUnit = price, saleQty = qty,
        feesFiat = 0.0, notes = null, createdAtUnix = daysAgo(daysAgo), btcPerUnitAtSale = null,
    )

    fun disposal(id: Long, sellId: Long, lotId: Long, qty: Double, basis: Double, gain: Double) = TrackingLotDisposalFfi(
        id = id, sellId = sellId, lotId = lotId, qtyConsumed = qty, costBasisPerUnit = basis,
        gainLossFiat = gain, createdAtUnix = NOW, gainLossBtc = null,
    )

    fun asset(
        id: Long,
        name: String,
        kind: String,
        value: Double,
        purchase: Double? = null,
        purchasedDaysAgo: Long? = null,
        updatedDaysAgo: Long = 0,
        ccy: String = "EUR",
        label: String? = null,
    ) = TrackingManualAssetFfi(
        id = id, name = name, kind = kind, valueFiat = value, currency = ccy, displayLabel = label,
        notes = null, lastUpdatedUnix = daysAgo(updatedDaysAgo), createdAtUnix = daysAgo(purchasedDaysAgo ?: 30),
        purchaseUnix = purchasedDaysAgo?.let { daysAgo(it) }, purchaseValueFiat = purchase,
        purchaseCurrency = purchase?.let { ccy }, purchaseBtcAtTime = null,
    )

    fun liability(id: Long, name: String, kind: String, sat: Long, label: String? = null) = TrackingLiabilityFfi(
        id = id, name = name, amountSat = sat.toULong(), kind = kind, displayLabel = label, createdAtUnix = daysAgo(200),
    )

    fun row(section: SheetSection, id: Long?, name: String, kind: String, valueSat: Long?, pct: Double? = null, change: Long? = null) =
        SheetRow(
            key = "${section.name.lowercase()}:${id ?: name}", section = section, name = name, kind = kind,
            valueSat = valueSat, changeSat = change, pctOfNetWorth = pct, updatedUnix = NOW, recordId = id,
        )
}
