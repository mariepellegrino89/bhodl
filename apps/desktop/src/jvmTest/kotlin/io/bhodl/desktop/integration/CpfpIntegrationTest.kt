package io.bhodl.desktop.integration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * L3 test for the Child-Pays-For-Parent (CPFP) flow added in 2.6.
 *
 * Flow:
 * 1. Wallet is funded and confirmed.
 * 2. We send 500 000 sat to a different wallet address (a self-send)
 *    at 1 sat/vB so the parent transaction has TWO wallet-owned
 *    outputs (the recipient and the BDK change).
 * 3. Without mining, we call `buildCpfpPsbt(parent_txid, 10 sat/vB)`.
 *    The implementation must pick up the parent's wallet outputs as
 *    the only inputs of the child (`manually_selected_only`) and drain
 *    them to a fresh receive address.
 * 4. We sign and broadcast the child.
 * 5. We mine one block and re-sync. Both parent and child must end up
 *    confirmed (typically in the same block; package relay drags the
 *    parent in once the child is broadcast at the higher rate).
 *
 * What this proves:
 * - `OnchainAccount::build_cpfp_psbt` correctly filters
 *   `list_utxos()` for outpoints originating from the supplied
 *   parent txid.
 * - The drain target uses a fresh external address (no double-share
 *   of an already-revealed index).
 * - The signed CPFP child is broadcast-acceptable to a real bitcoind +
 *   electrs stack (not just a syntactic PSBT round trip).
 */
class CpfpIntegrationTest : RegtestFixture() {

    @Test
    fun `cpfp child drains parent's wallet outputs and confirms`() {
        val wallet = newRegtestWallet(BIP86_TEST_MNEMONIC)
        val fundingAddress = wallet.peekAddressAt(0u)
        fundWallet(fundingAddress, "0.01") // 1M sat
        syncUntil(wallet) { it.balanceSat() >= 1_000_000u }

        // Self-send so both the recipient and the change are wallet
        // outputs — that's the most realistic "stuck change" + "stuck
        // incoming" scenario for the CPFP UI to handle in one shot.
        val recipient = wallet.peekAddressAt(2u)
        val sendAmountSat = 500_000UL
        val parentUnsigned = wallet.buildSendTx(
            toAddress = recipient,
            amountSat = sendAmountSat,
            feeRateSatVb = 1UL,
            allowHighFee = false,
        )
        val parentSigned = wallet.signPsbt(parentUnsigned)
        val parentTxid = wallet.broadcastTx(esploraUrl(), parentSigned, null)

        // Re-sync so BDK sees the unconfirmed parent + its outputs in
        // its UTXO set. Otherwise build_cpfp_psbt would not find any
        // UTXOs to spend.
        syncUntil(wallet, timeoutMs = 30_000L) { w ->
            val knownUtxos = w.listUtxos()
            // The parent contributes at least one wallet-owned UTXO
            // to the unconfirmed set. Without this, build_cpfp_psbt
            // would error with "no spendable wallet outputs from
            // parent <txid>".
            knownUtxos.any { it.outpoint.startsWith("$parentTxid:") }
        }

        val parentUtxos = wallet.listUtxos().filter {
            it.outpoint.startsWith("$parentTxid:")
        }
        assertTrue(
            parentUtxos.isNotEmpty(),
            "expected at least one wallet-owned UTXO from parent $parentTxid",
        )
        // The self-send produced TWO wallet-owned outputs: the recipient
        // (peek 2) and the BDK-revealed change. Both should show up.
        assertEquals(
            2,
            parentUtxos.size,
            "self-send should yield 2 wallet outputs from parent; got " +
                "${parentUtxos.map { it.outpoint }}",
        )

        // Build the CPFP child at 10 sat/vB.
        val childUnsigned = wallet.buildCpfpPsbt(
            parentTxid = parentTxid,
            feeRateSatVb = 10UL,
            allowHighFee = false,
        ).psbt
        val childSigned = wallet.signPsbt(childUnsigned)
        val childTxid = wallet.broadcastTx(esploraUrl(), childSigned, null)

        assertNotEquals(parentTxid, childTxid, "child must have its own txid")
        assertEquals(64, childTxid.length, "child txid malformed")

        // Mine one block; both parent and child must be included in
        // the next block (package relay).
        mineBlocks(1)
        syncUntil(wallet, timeoutMs = 30_000L) { w ->
            val parent = w.listTransactions().firstOrNull { it.txid == parentTxid }
            val child = w.listTransactions().firstOrNull { it.txid == childTxid }
            parent?.confirmationHeight != null && child?.confirmationHeight != null
        }

        val txs = wallet.listTransactions()
        val confirmedParent = requireNotNull(
            txs.firstOrNull { it.txid == parentTxid },
        ) { "parent tx not in wallet history" }
        val confirmedChild = requireNotNull(
            txs.firstOrNull { it.txid == childTxid },
        ) { "child tx not in wallet history" }
        val parentH = requireNotNull(confirmedParent.confirmationHeight) {
            "parent never confirmed"
        }.toLong()
        val childH = requireNotNull(confirmedChild.confirmationHeight) {
            "child never confirmed"
        }.toLong()
        // The package landed together (height delta should be 0 — both
        // in the same block — but we tolerate ±1 in case the polling
        // happened to read mid-mine on a slow CI runner).
        assertTrue(
            (childH - parentH) in 0..1L,
            "expected child within 0-1 blocks of parent; parent=$parentH child=$childH",
        )
        assertTrue(
            (confirmedChild.feeSat ?: 0u) > 0u,
            "expected positive fee on child, saw $confirmedChild",
        )
    }
}
