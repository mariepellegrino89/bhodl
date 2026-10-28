package io.bhodl.desktop.data

import io.bhodl.desktop.prices.BtcRateHistoryClient
import io.bhodl.desktop.prices.PriceCache
import io.bhodl.ffi.ManualTxnFfi
import io.bhodl.ffi.PaymentDirection
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.PaymentKind
import io.bhodl.ffi.RegistryHandle
import io.bhodl.ffi.TrackingLiabilityFfi
import io.bhodl.ffi.TrackingManualAssetFfi
import io.bhodl.ffi.isManualWalletId
import io.bhodl.ffi.manualTxnEventId
import io.bhodl.ffi.manualTxnWalletId
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Registry schema v8 — income and spending outside the wallets (cash,
 * bank account, card). Most people don't live in sat: what they pay in
 * euro still belongs in a budget kept in sat, at the day's BTC rate.
 *
 * Each row becomes a [PaymentEvent] on a synthetic wallet id, one per
 * account ([manualTxnWalletId]), so it flows through rules, categories,
 * budgets and reports like any wallet transaction.
 */
internal object ManualTxns {

    /** A confirmed-looking height: manual rows are never "pending". */
    private const val SETTLED_HEIGHT = 1u

    /** The manual transactions as events, with the category the registry holds for each. */
    fun events(registry: RegistryHandle): List<PaymentEvent> =
        runCatching { registry.listManualTxns() }.getOrDefault(emptyList()).map { t -> toEvent(t, registry) }

    fun toEvent(t: ManualTxnFfi, registry: RegistryHandle? = null): PaymentEvent {
        val walletId = manualTxnWalletId(t.accountRef)
        val eventId = manualTxnEventId(t.id)
        return PaymentEvent(
            walletId = walletId,
            txidOrPaymentId = eventId,
            kind = PaymentKind.ON_CHAIN,
            direction = if (t.direction == DIRECTION_IN) PaymentDirection.INCOMING else PaymentDirection.OUTGOING,
            amountSat = t.amountSat,
            feeSat = null,
            timestampUnix = t.occurredUnix.coerceAtLeast(0L).toULong(),
            blockHeight = SETTLED_HEIGHT,
            category = registry?.let { runCatching { it.getEventCategory(walletId, eventId) }.getOrNull() },
            cryptoEventKind = null,
        )
    }

    /** Whether [walletId] belongs to a manual account rather than a wallet. */
    fun isManual(walletId: String): Boolean = isManualWalletId(walletId)

    /** The id of the manual transaction behind [event], or null for a wallet transaction. */
    fun idOf(event: PaymentEvent): Long? =
        if (isManual(event.walletId)) event.txidOrPaymentId.removePrefix("manual-").toLongOrNull() else null

    const val DIRECTION_IN: String = "in"
    const val DIRECTION_OUT: String = "out"

    /** The `account_ref` of the "other" channel (PayPal, vouchers…), as in the registry. */
    const val ACCOUNT_OTHER: String = "other"
}

/** What kind of channel a manual transaction moved through. */
internal enum class ManualAccountKind {
    /** Cash: no balance. */
    CASH,

    /** A manual "cash" asset (bank, savings): its balance moves, in its currency. */
    ACCOUNT,

    /** A credit card: spending raises its debt. */
    CARD,

    /** PayPal, meal vouchers…: no balance. */
    OTHER,
}

/** The channel a manual transaction's money moved through. */
internal data class ManualAccount(
    /** `asset:<id>`, `liability:<id>`, [ManualTxns.ACCOUNT_OTHER] or null for cash. */
    val ref: String?,
    val walletId: String,
    val label: String,
    /** The account's own currency, when it has one. */
    val currency: String?,
    val kind: ManualAccountKind = ManualAccountKind.CASH,
)

/** A manual "cash" asset as a channel. */
internal fun TrackingManualAssetFfi.asManualAccount(): ManualAccount {
    val ref = "asset:$id"
    return ManualAccount(ref, manualTxnWalletId(ref), name, currency, ManualAccountKind.ACCOUNT)
}

/**
 * The channels a manual transaction can use: cash, the manual "cash"
 * accounts (bank, savings), the credit cards, then "other".
 */
internal fun manualAccounts(
    assets: List<TrackingManualAssetFfi>,
    liabilities: List<TrackingLiabilityFfi>,
    cashLabel: String,
    otherLabel: String = cashLabel,
): List<ManualAccount> =
    listOf(ManualAccount(null, manualTxnWalletId(null), cashLabel, null, ManualAccountKind.CASH)) +
        assets.filter { it.kind == "cash" }.map { it.asManualAccount() } +
        liabilities.filter { it.kind == "credit-card" }.map {
            val ref = "liability:${it.id}"
            ManualAccount(ref, manualTxnWalletId(ref), it.name, null, ManualAccountKind.CARD)
        } +
        ManualAccount(
            ManualTxns.ACCOUNT_OTHER, manualTxnWalletId(ManualTxns.ACCOUNT_OTHER), otherLabel, null, ManualAccountKind.OTHER,
        )

/** The channel filter of the Transactions list: everything, the wallets only, or one channel. */
internal sealed interface ChannelFilter {
    data object All : ChannelFilter
    data object Wallets : ChannelFilter
    data class Channel(val walletId: String) : ChannelFilter
}

/** [events] through [filter]. */
internal fun filterByChannel(events: List<PaymentEvent>, filter: ChannelFilter): List<PaymentEvent> = when (filter) {
    ChannelFilter.All -> events
    ChannelFilter.Wallets -> events.filterNot { ManualTxns.isManual(it.walletId) }
    is ChannelFilter.Channel -> events.filter { it.walletId == filter.walletId }
}

/**
 * BTC/[currency] on [dateUnix]: today's spot price for today (or a
 * later date), the day's historical rate otherwise, falling back on
 * the spot price when the history can't answer. Null when nothing can.
 */
internal suspend fun btcRateOn(dateUnix: Long, currency: String, today: LocalDate = LocalDate.now(ZoneOffset.UTC)): Double? {
    val day = Instant.ofEpochSecond(dateUnix).atZone(ZoneOffset.UTC).toLocalDate()
    val spot = PriceCache.rateFor(currency)
    if (!day.isBefore(today) && spot != null) return spot
    val historical = runCatching { BtcRateHistoryClient.rateAt(dateUnix, currency) }.getOrNull()
    return historical ?: spot
}

/**
 * [fiat] as sat at [rate] (1 BTC = rate), rounded to the nearest sat
 * (30 € at 50,000 €/BTC is 60,000 sat, not 59,999). Null without a
 * usable rate or amount.
 */
internal fun fiatToSatAtRate(fiat: Double, rate: Double?): ULong? {
    if (!fiat.isFinite() || fiat <= 0.0 || rate == null || !rate.isFinite() || rate <= 0.0) return null
    val sat = kotlin.math.round(fiat / rate * 100_000_000.0)
    return if (sat.isFinite() && sat >= 1.0) sat.toLong().toULong() else null
}
