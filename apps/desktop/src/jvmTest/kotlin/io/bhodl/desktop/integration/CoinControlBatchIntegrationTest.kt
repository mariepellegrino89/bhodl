package io.bhodl.desktop.integration

import io.bhodl.ffi.RecipientEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * L3 test for `buildSendTxBatchWithUtxos` against real UTXOs.
 *
 * The Rust unit tests for this method only cover error branches —
 * empty inputs, wrong-network address, garbage outpoint, unknown
 * outpoint. They do NOT prove the happy path actually pins the
 * supplied input set without falling back to the wallet's other
 * coins. This integration test does.
 *
 * Flow:
 * 1. Fund the wallet with three independent confirmed UTXOs (three
 *    successive `sendtoaddress` + mine-1 cycles to three sequential
 *    receive addresses). Each UTXO is 0.005 BTC.
 * 2. Sync the wallet, snapshot `listUtxos`. Pick exactly ONE of the
 *    three (the first) as the pinned input set.
 * 3. Call `buildSendTxBatchWithUtxos` with that one outpoint and
 *    TWO recipient entries (one external + one self).
 * 4. Sign + broadcast + mine + re-sync.
 * 5. Assert: the tx confirmed, the pinned UTXO is no longer in
 *    `listUtxos`, the OTHER two UTXOs are still there.
 *
 * What this proves:
 * - Coin control with `manually_selected_only` truly excludes the
 *   non-pinned UTXOs: BDK does not silently grab them as additional
 *   inputs when the pinned set is large enough.
 * - The two-recipient batch builds against a single input.
 */
class CoinControlBatchIntegrationTest : RegtestFixture() {

    @Test
    fun `pinned utxo is the only one spent in a batch send`() {
        val wallet = newRegtestWallet(BIP86_TEST_MNEMONIC)

        // Fund three different addresses with 0.005 BTC each. Three
        // confirmed UTXOs in the wallet, large enough that the test
        // does not rely on coin selection picking any one of them.
        for (i in 0u..2u) {
            val addr = wallet.peekAddressAt(i)
            fundWallet(addr, "0.005")
        }

        // Wait until all three confirmed UTXOs are visible.
        syncUntil(wallet) { it.listUtxos().size >= 3 }
        val initialUtxos = wallet.listUtxos()
        assertEquals(3, initialUtxos.size, "expected 3 UTXOs after funding, saw $initialUtxos")

        val pinned = initialUtxos.first()
        val others = initialUtxos.drop(1)

        // Build a 2-recipient batch using only the pinned UTXO. The
        // pinned UTXO has 500_000 sat — comfortable for two outputs
        // of 100_000 each + change + fee.
        val externalRecipient = wallet.peekAddressAt(50u)
        val selfRecipient = wallet.peekAddressAt(51u)
        val recipients = listOf(
            RecipientEntry(address = externalRecipient, amountSat = 100_000UL),
            RecipientEntry(address = selfRecipient, amountSat = 100_000UL),
        )

        val unsigned = wallet.buildSendTxBatchWithUtxos(
            recipients = recipients,
            feeRateSatVb = 1UL,
            outpoints = listOf(pinned.outpoint),
            allowHighFee = false,
        )
        val signed = wallet.signPsbt(unsigned)
        val txid = wallet.broadcastTx(esploraUrl(), signed, null)
        assertEquals(64, txid.length, "txid malformed: '$txid'")

        mineBlocks(1)
        syncUntil(wallet, timeoutMs = 15_000L) { wallet ->
            val seen = wallet.listTransactions().firstOrNull { it.txid == txid }
            seen != null && seen.confirmationHeight != null
        }

        // Assertions:
        // - the pinned UTXO is gone (it was consumed)
        // - the other two UTXOs are still there
        val finalUtxos = wallet.listUtxos()
        assertTrue(
            finalUtxos.none { it.outpoint == pinned.outpoint },
            "pinned UTXO ${pinned.outpoint} should have been spent, still present: $finalUtxos",
        )
        for (kept in others) {
            assertNotNull(
                finalUtxos.firstOrNull { it.outpoint == kept.outpoint },
                "non-pinned UTXO ${kept.outpoint} got spent — coin control failed!",
            )
        }
    }
}
