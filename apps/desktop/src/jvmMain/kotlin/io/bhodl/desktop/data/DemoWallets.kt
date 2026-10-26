package io.bhodl.desktop.data

import io.bhodl.desktop.wallet.WalletPort
import io.bhodl.ffi.DemoEventFfi
import io.bhodl.ffi.TransactionRecord
import io.bhodl.ffi.UtxoRecord

/**
 * Test mode — the watch wallets of a test profile never sync: their
 * balance, transactions and coins come from the profile's own wallet
 * transactions (registry `demo_events`, schema v9).
 */
internal data class DemoWalletFigures(
    val balanceSat: ULong,
    val transactions: List<TransactionRecord>,
    val utxos: List<UtxoRecord>,
)

/** Block height the test profile's chain is "at". */
internal const val DEMO_TIP_HEIGHT: UInt = 300_144u

/** Per wallet: balance (incoming minus outgoing), transactions newest first, coins. */
internal fun demoWalletFigures(events: List<DemoEventFfi>): Map<String, DemoWalletFigures> =
    events.groupBy { it.walletId }.mapValues { (_, list) ->
        fun net(e: DemoEventFfi): Long = if (e.direction == "in") e.amountSat.toLong() else -e.amountSat.toLong()
        val balance = list.sumOf(::net).coerceAtLeast(0L)
        val txs = list.sortedByDescending { it.occurredUnix }.map { e ->
            TransactionRecord(
                txid = e.eventId,
                netSat = net(e),
                feeSat = e.feeSat,
                confirmationHeight = e.blockHeight,
                timestamp = e.occurredUnix.toULong(),
            )
        }
        DemoWalletFigures(balance.toULong(), txs, demoUtxos(list, balance))
    }

/**
 * Coins adding up to [balance]: the latest receipts, newest first, the
 * oldest one cut to fit.
 */
private fun demoUtxos(events: List<DemoEventFfi>, balance: Long): List<UtxoRecord> {
    val out = mutableListOf<UtxoRecord>()
    var left = balance
    for (e in events.filter { it.direction == "in" }.sortedByDescending { it.occurredUnix }) {
        if (left <= 0L) break
        val value = minOf(left, e.amountSat.toLong())
        out += UtxoRecord(
            outpoint = "${e.eventId}:0",
            valueSat = value.toULong(),
            address = "",
            isChange = false,
            confirmed = e.blockHeight != null,
        )
        left -= value
    }
    return out
}

/**
 * A test profile's wallet in the workspace: the real watch handle for
 * addresses, labels and contacts, the test transactions for everything
 * the chain would say. Never touches the network.
 */
internal class DemoWalletPort(
    private val inner: WalletPort,
    private val figures: () -> DemoWalletFigures?,
) : WalletPort by inner {
    override fun balanceSat(): ULong = figures()?.balanceSat ?: 0uL
    override fun listTransactions(): List<TransactionRecord> = figures()?.transactions.orEmpty()
    override fun listUtxos(): List<UtxoRecord> = figures()?.utxos.orEmpty()
    override fun chainTipHeight(): UInt = DEMO_TIP_HEIGHT

    /** Test data: nothing to sync. */
    override fun syncWithEsplora(esploraUrl: String, proxyUrl: String?) = Unit
}
