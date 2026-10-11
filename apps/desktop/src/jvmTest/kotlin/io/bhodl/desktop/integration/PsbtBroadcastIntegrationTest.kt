package io.bhodl.desktop.integration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * L3 happy-path test for the build → sign → broadcast pipeline.
 *
 * The flow mirrors what a user does on `SendScreen`:
 * 1. Wallet is funded by an external party (we mine the funding
 *    block via the on-node miner wallet).
 * 2. Wallet syncs with electrs and sees the new UTXO.
 * 3. Wallet builds a send-back PSBT (sends to one of its own change
 *    addresses to keep the regtest stack closed-loop).
 * 4. Wallet signs, then broadcasts via the same Esplora endpoint
 *    `syncWithEsplora` uses.
 * 5. We mine one block and re-sync; the wallet must observe its own
 *    transaction as confirmed and the original UTXO as spent.
 *
 * What this proves:
 * - `WalletHandle.buildSendTx` produces a PSBT BDK can finalise
 *   against the wallet's own descriptors.
 * - `WalletHandle.signPsbt` against the BIP86 single-key path leaves
 *   the PSBT fully finalised (ready for broadcast).
 * - `WalletHandle.broadcastTx` against the Esplora REST endpoint is
 *   accepted by Bitcoin Core's mempool — proof the serialisation is
 *   wire-compatible end-to-end.
 */
class PsbtBroadcastIntegrationTest : RegtestFixture() {

    @Test
    fun `build sign broadcast produces a confirmed self-spend`() {
        val wallet = newRegtestWallet(BIP86_TEST_MNEMONIC)
        val receiveAddress = wallet.peekAddressAt(0u)

        // Fund the wallet from the miner with 0.01 BTC = 1_000_000 sat
        // — comfortable headroom for a 1 sat/vB taproot send + change.
        fundWallet(receiveAddress, "0.01")
        syncUntil(wallet) { it.balanceSat() >= 1_000_000u }

        // Send-back to the next external address. Using a fresh
        // address keeps the test independent of the wallet's change
        // index and matches the typical "spend to a friend" UX.
        val sendTo = wallet.peekAddressAt(1u)
        val sendAmountSat = 500_000UL
        val unsigned = wallet.buildSendTx(
            toAddress = sendTo,
            amountSat = sendAmountSat,
            feeRateSatVb = 1UL,
            allowHighFee = false,
        )
        assertTrue(unsigned.isNotBlank(), "expected non-empty unsigned PSBT")

        val signed = wallet.signPsbt(unsigned)
        assertNotEquals(unsigned, signed, "signing must mutate the PSBT")

        val txid = wallet.broadcastTx(esploraUrl(), signed, null)
        assertEquals(64, txid.length, "txid must be a 32-byte hex string, got '$txid'")

        // Mine one block to confirm; re-sync until electrs has indexed
        // the new block AND BDK's view of our tx carries a non-null
        // confirmation height. Polling on balance alone is not enough
        // — the balance may drop the moment the unconfirmed tx is
        // observed in the mempool, before the block lands at electrs.
        mineBlocks(1)
        syncUntil(wallet, timeoutMs = 30_000L) { wallet ->
            val seen = wallet.listTransactions().firstOrNull { it.txid == txid }
            seen?.confirmationHeight != null
        }

        val finalBalance = wallet.balanceSat()
        assertTrue(
            finalBalance < 1_000_000u && finalBalance >= 1_000_000u - sendAmountSat - 10_000u,
            "expected ~500_000 sat left (minus fee), saw $finalBalance",
        )

        // The transaction we just broadcast should be visible in the
        // wallet's history list with a non-null block_height (confirmed).
        val txs = wallet.listTransactions()
        val seen = requireNotNull(txs.firstOrNull { it.txid == txid }) {
            "broadcast txid $txid not found in wallet history (saw: $txs)"
        }
        assertNotNull(
            seen.confirmationHeight,
            "expected confirmation_height after mining one block, history entry: $seen",
        )
    }
}
