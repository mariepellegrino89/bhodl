package io.bhodl.desktop.wallet

import io.bhodl.ffi.AddressRecord
import io.bhodl.ffi.ContactRecord
import io.bhodl.ffi.CpfpPlan
import io.bhodl.ffi.FeeEstimates
import io.bhodl.ffi.PayjoinReview
import io.bhodl.ffi.PsbtSummary
import io.bhodl.ffi.ReceiveLabelCopyRecord
import io.bhodl.ffi.RecipientEntry
import io.bhodl.ffi.TransactionRecord
import io.bhodl.ffi.UtxoRecord
import io.bhodl.ffi.WalletException
import io.bhodl.ffi.WalletHandle

/**
 * Stable interface that the UI talks to instead of the concrete
 * `io.bhodl.ffi.WalletHandle` opaque object.
 *
 * The shape mirrors `WalletHandle` 1-to-1 for the methods the desktop
 * screens currently consume. Production wires this to
 * [`UniffiWalletPort`], which is a thin pass-through that delegates
 * straight to the FFI. Tests wire it to a fake in-memory
 * implementation so Compose UI tests can drive Send / Dashboard /
 * History / AddressBook / BumpFee without standing up a real
 * `WalletHandle` (which would mean cdylib + SQLCipher + a network
 * Esplora endpoint).
 *
 * Method signatures stay identical to `WalletHandle`'s so adopting
 * the port is a zero-behaviour-change diff for callers — they only
 * swap the type of the variable they store the handle in.
 *
 * Errors travel as `io.bhodl.ffi.WalletException` because every
 * subclass is a generated `data class` with public constructors. A
 * fake can throw the same typed exceptions production code throws,
 * which keeps the UI's `catch` arms valid in both worlds.
 */
public interface WalletPort {

    @Throws(WalletException::class)
    public fun peekAddressAt(index: UInt): String

    /**
     * Reveal the next external (receive) address and persist the
     * advance — every call returns a fresh index. Counterpart of
     * [`peekAddressAt`] which never advances. Used by the Receive
     * screen so each shared address is unique.
     */
    @Throws(WalletException::class)
    public fun nextReceiveAddress(): String

    /**
     * Lowest revealed receive address that has not received funds yet;
     * reveals a new index only when all are used. The label-first
     * Receive screen uses it so visits and label edits never burn
     * indices (gap limit); [`nextReceiveAddress`] is the explicit
     * "New address".
     */
    @Throws(WalletException::class)
    public fun nextUnusedReceiveAddress(): String

    @Throws(WalletException::class)
    public fun balanceSat(): ULong

    @Throws(WalletException::class)
    public fun syncWithEsplora(esploraUrl: String, proxyUrl: String?)

    @Throws(WalletException::class)
    public fun listUtxos(): List<UtxoRecord>

    @Throws(WalletException::class)
    public fun listTransactions(): List<TransactionRecord>

    /**
     * Freeze / unfreeze one output (`txid:vout`) via a BIP-329 `output`
     * label with `spendable: false`. Existing label text is kept.
     */
    @Throws(WalletException::class)
    public fun setUtxoFrozen(outpoint: String, frozen: Boolean)

    /**
     * Every revealed address of one keychain (`change = false`:
     * receive), oldest first, with used / unused state and the BIP-329
     * `addr` label + category. Backs the Addresses tab and the Receive
     * reuse warning.
     */
    @Throws(WalletException::class)
    public fun listAddresses(change: Boolean): List<AddressRecord>

    /**
     * Label-first Receive — attach a BIP-329 `addr` label and a tracker
     * category to an address before it is shared. Blank text + `null`
     * category clears the label.
     */
    @Throws(WalletException::class)
    public fun setAddressLabel(address: String, text: String, category: String?)

    /**
     * Label-first receive: copy receive-address labels / categories onto
     * the incoming transactions that paid them (rules in the core:
     * net-incoming only, once per (txid, address), never over an
     * existing label). Returns what was written.
     */
    @Throws(WalletException::class)
    public fun applyReceiveLabels(): List<ReceiveLabelCopyRecord>

    /** Height of the last synced block (`0` before the first sync). */
    @Throws(WalletException::class)
    public fun chainTipHeight(): UInt

    @Throws(WalletException::class)
    public fun fetchFeeEstimates(esploraUrl: String, proxyUrl: String?): FeeEstimates

    @Throws(WalletException::class)
    public fun psbtFeeSat(psbtB64: String): ULong

    /**
     * Decode a PSBT into the review summary (every output with
     * ownership / change flags, fee, vsize, external spend, fee-guard
     * verdict). Review screens render ONLY this and then sign exactly
     * the same PSBT string.
     */
    @Throws(WalletException::class)
    public fun decodePsbt(psbtB64: String): PsbtSummary

    // Every build* method: `allowHighFee` must be `false` unless the
    // user explicitly confirmed a fee the wallet flagged with
    // `WalletException.FeeTooHigh` (second confirmation).

    @Throws(WalletException::class)
    public fun buildSendTx(
        toAddress: String,
        amountSat: ULong,
        feeRateSatVb: ULong,
        allowHighFee: Boolean,
    ): String

    @Throws(WalletException::class)
    public fun buildSendTxBatch(
        recipients: List<RecipientEntry>,
        feeRateSatVb: ULong,
        allowHighFee: Boolean,
    ): String

    @Throws(WalletException::class)
    public fun buildDrainTx(toAddress: String, feeRateSatVb: ULong, allowHighFee: Boolean): String

    @Throws(WalletException::class)
    public fun buildSendTxWithUtxos(
        toAddress: String,
        amountSat: ULong,
        feeRateSatVb: ULong,
        outpoints: List<String>,
        allowHighFee: Boolean,
    ): String

    @Throws(WalletException::class)
    public fun buildSendTxBatchWithUtxos(
        recipients: List<RecipientEntry>,
        feeRateSatVb: ULong,
        outpoints: List<String>,
        allowHighFee: Boolean,
    ): String

    /** Build (NOT sign) an RBF replacement; review via [decodePsbt]. */
    @Throws(WalletException::class)
    public fun buildFeeBumpPsbt(txid: String, newFeeRateSatVb: ULong, allowHighFee: Boolean): String

    /**
     * Build (NOT sign) a CPFP child PSBT consuming **only** the
     * wallet's outputs of the unconfirmed `parentTxid`, drained to a
     * fresh change address, sized so the parent+child package reaches
     * `feeRateSatVb`. Used by:
     * - Receiver-side speed up of an incoming pending tx.
     * - Sender-side rescue of a stuck change UTXO.
     */
    @Throws(WalletException::class)
    public fun buildCpfpPsbt(parentTxid: String, feeRateSatVb: ULong, allowHighFee: Boolean): CpfpPlan

    /**
     * PayJoin (BIP-78) sender, step 1 of 2. The wallet parses `uri`,
     * refuses unless its address / amount equal `expectedAddress` /
     * `expectedAmountSat` (what the form displayed), builds + signs
     * the original at `feeRateSatVb`, POSTs it to the `pj=` endpoint
     * (via `proxyUrl` when Tor is on) and validates the proposal.
     * Nothing is signed for the proposal: show the returned
     * [`PayjoinReview`] and call [`payjoinSignAndBroadcast`] only after
     * the user confirmed. `sessionId == null` means negotiation failed;
     * `originalPsbt` can then be broadcast via [`broadcastTx`].
     */
    @Throws(WalletException::class)
    public fun payjoinNegotiate(
        uri: String,
        expectedAddress: String,
        expectedAmountSat: ULong,
        feeRateSatVb: ULong,
        proxyUrl: String?,
    ): PayjoinReview

    /**
     * PayJoin step 2: sign only this wallet's inputs of the reviewed
     * proposal `sessionId` and broadcast. Returns the txid hex.
     */
    @Throws(WalletException::class)
    public fun payjoinSignAndBroadcast(
        sessionId: String,
        esploraUrl: String,
        proxyUrl: String?,
    ): String

    /** Drop a negotiated PayJoin session the user cancelled. */
    @Throws(WalletException::class)
    public fun payjoinDiscard(sessionId: String): Boolean

    @Throws(WalletException::class)
    public fun signPsbt(psbtB64: String): String

    @Throws(WalletException::class)
    public fun broadcastTx(esploraUrl: String, signedPsbtB64: String, proxyUrl: String?): String

    /**
     * Render every wallet label as a BIP-329 JSONL document. One
     * record per line; safe to write straight to a `.jsonl` file.
     */
    @Throws(WalletException::class)
    public fun exportLabelsJsonl(): String

    /**
     * Import a BIP-329 JSONL document into the wallet. Each record
     * upserts (`(kind, target)` is the natural key). Returns the
     * number of imported records.
     */
    @Throws(WalletException::class)
    public fun importLabelsJsonl(jsonl: String): ULong

    @Throws(WalletException::class)
    public fun setTxLabel(txid: String, text: String)

    @Throws(WalletException::class)
    public fun getTxLabel(txid: String): String?

    @Throws(WalletException::class)
    public fun removeTxLabel(txid: String): Boolean

    /** Phase 3.4 — set the BIP-329 category extension. `null` clears. */
    @Throws(WalletException::class)
    public fun setTxCategory(txid: String, category: String?)

    /** Phase 3.4 — read the BIP-329 category extension, or `null`. */
    @Throws(WalletException::class)
    public fun getTxCategory(txid: String): String?

    @Throws(WalletException::class)
    public fun addContact(label: String, address: String): Long

    @Throws(WalletException::class)
    public fun listContacts(): List<ContactRecord>

    @Throws(WalletException::class)
    public fun updateContactLabel(id: Long, newLabel: String): Boolean

    @Throws(WalletException::class)
    public fun removeContact(id: Long): Boolean

    @Throws(WalletException::class)
    public fun generateRandomMnemonic(wordCount: UInt): String

    @Throws(WalletException::class)
    public fun exportEncryptedBackup(password: String): String

    @Throws(WalletException::class)
    public fun peekMnemonic(): io.bhodl.ffi.MnemonicReveal

    /**
     * Export the wallet's public-only descriptor surface.
     *
     * Contains no secret material — every field is derived from BDK's
     * public projection of the wallet state. The returned bundle is
     * usable for: (a) importing into Sparrow / Liana / Specter as a
     * watch-only wallet; (b) pasting `accountXpub` into the Phase 3.1
     * debug screen; (c) cross-device audit of receive addresses.
     */
    @Throws(WalletException::class)
    public fun exportPublicDescriptor(): io.bhodl.ffi.PublicDescriptorBundle

    /**
     * Phase 6.0 — convenience accessor that returns the external
     * (receive) descriptor in xpub-only form, suitable as input to
     * [io.bhodl.ffi.RegistryHandle.addWatchXpub] / `isDescriptorTracked`.
     * Equivalent to `exportPublicDescriptor().externalDescriptor`
     * but kept as a separate FFI so the Tracker pairing surfaces
     * (PinSetup checkbox, Settings "Add to Tracker") can call it in
     * one round-trip without pulling the whole bundle.
     */
    @Throws(WalletException::class)
    public fun trackerDescriptorPub(): String

    /**
     * Deterministically release any underlying native resources
     * (SQLCipher file handles, in-memory secrets). Idempotent: a
     * second call after a successful close is a silent no-op.
     *
     * After [`close`] returns, every other method on this port may
     * throw [`WalletException.Storage`] with a "wallet handle is
     * closed" detail. Callers MUST drop their reference to the port
     * once they've called this — typically by nulling the field in
     * [`io.bhodl.desktop.wizard.WalletStore`] right after.
     *
     * The boot path uses this from [`WalletStore.clear`] so a
     * subsequent wipe of `wallet.sqlite` is not blocked by a Windows
     * file lock the JVM GC hasn't gotten around to releasing yet —
     * see finding #14 in `SMOKE_TEST_FINDINGS.md`.
     */
    public fun close()
}

/**
 * Production implementation of [`WalletPort`]: wraps a real
 * `WalletHandle` and forwards every call. Created at unlock time
 * (`UnlockScreen`) and at wallet-creation time (`PinSetupScreen`).
 */
public class UniffiWalletPort(
    private val handle: WalletHandle,
) : WalletPort {

    override fun peekAddressAt(index: UInt): String =
        handle.peekAddressAt(index)

    override fun nextReceiveAddress(): String =
        handle.nextReceiveAddress()

    override fun nextUnusedReceiveAddress(): String =
        handle.nextUnusedReceiveAddress()

    override fun balanceSat(): ULong =
        handle.balanceSat()

    override fun syncWithEsplora(esploraUrl: String, proxyUrl: String?) {
        handle.syncWithEsplora(esploraUrl, proxyUrl)
    }

    override fun listUtxos(): List<UtxoRecord> =
        handle.listUtxos()

    override fun listTransactions(): List<TransactionRecord> =
        handle.listTransactions()

    override fun setUtxoFrozen(outpoint: String, frozen: Boolean) {
        handle.setUtxoFrozen(outpoint, frozen)
    }

    override fun listAddresses(change: Boolean): List<AddressRecord> =
        handle.listAddresses(change)

    override fun setAddressLabel(address: String, text: String, category: String?) {
        handle.setAddressLabel(address, text, category)
    }

    override fun applyReceiveLabels(): List<ReceiveLabelCopyRecord> =
        handle.applyReceiveLabels()

    override fun chainTipHeight(): UInt =
        handle.chainTipHeight()

    override fun fetchFeeEstimates(esploraUrl: String, proxyUrl: String?): FeeEstimates =
        handle.fetchFeeEstimates(esploraUrl, proxyUrl)

    override fun psbtFeeSat(psbtB64: String): ULong =
        handle.psbtFeeSat(psbtB64)

    override fun decodePsbt(psbtB64: String): PsbtSummary =
        handle.decodePsbt(psbtB64)

    override fun buildSendTx(
        toAddress: String,
        amountSat: ULong,
        feeRateSatVb: ULong,
        allowHighFee: Boolean,
    ): String = handle.buildSendTx(toAddress, amountSat, feeRateSatVb, allowHighFee)

    override fun buildSendTxBatch(
        recipients: List<RecipientEntry>,
        feeRateSatVb: ULong,
        allowHighFee: Boolean,
    ): String = handle.buildSendTxBatch(recipients, feeRateSatVb, allowHighFee)

    override fun buildDrainTx(toAddress: String, feeRateSatVb: ULong, allowHighFee: Boolean): String =
        handle.buildDrainTx(toAddress, feeRateSatVb, allowHighFee)

    override fun buildSendTxWithUtxos(
        toAddress: String,
        amountSat: ULong,
        feeRateSatVb: ULong,
        outpoints: List<String>,
        allowHighFee: Boolean,
    ): String = handle.buildSendTxWithUtxos(toAddress, amountSat, feeRateSatVb, outpoints, allowHighFee)

    override fun buildSendTxBatchWithUtxos(
        recipients: List<RecipientEntry>,
        feeRateSatVb: ULong,
        outpoints: List<String>,
        allowHighFee: Boolean,
    ): String = handle.buildSendTxBatchWithUtxos(recipients, feeRateSatVb, outpoints, allowHighFee)

    override fun buildFeeBumpPsbt(txid: String, newFeeRateSatVb: ULong, allowHighFee: Boolean): String =
        handle.buildFeeBumpPsbt(txid, newFeeRateSatVb, allowHighFee)

    override fun buildCpfpPsbt(parentTxid: String, feeRateSatVb: ULong, allowHighFee: Boolean): CpfpPlan =
        handle.buildCpfpPsbt(parentTxid, feeRateSatVb, allowHighFee)

    override fun payjoinNegotiate(
        uri: String,
        expectedAddress: String,
        expectedAmountSat: ULong,
        feeRateSatVb: ULong,
        proxyUrl: String?,
    ): PayjoinReview =
        handle.payjoinNegotiate(uri, expectedAddress, expectedAmountSat, feeRateSatVb, proxyUrl)

    override fun payjoinSignAndBroadcast(
        sessionId: String,
        esploraUrl: String,
        proxyUrl: String?,
    ): String = handle.payjoinSignAndBroadcast(sessionId, esploraUrl, proxyUrl)

    override fun payjoinDiscard(sessionId: String): Boolean =
        handle.payjoinDiscard(sessionId)

    override fun signPsbt(psbtB64: String): String =
        handle.signPsbt(psbtB64)

    override fun broadcastTx(esploraUrl: String, signedPsbtB64: String, proxyUrl: String?): String =
        handle.broadcastTx(esploraUrl, signedPsbtB64, proxyUrl)

    override fun exportLabelsJsonl(): String =
        handle.exportLabelsJsonl()

    override fun importLabelsJsonl(jsonl: String): ULong =
        handle.importLabelsJsonl(jsonl)

    override fun setTxLabel(txid: String, text: String) {
        handle.setTxLabel(txid, text)
    }

    override fun getTxLabel(txid: String): String? =
        handle.getTxLabel(txid)

    override fun removeTxLabel(txid: String): Boolean =
        handle.removeTxLabel(txid)

    override fun setTxCategory(txid: String, category: String?) {
        handle.setTxCategory(txid, category)
    }

    override fun getTxCategory(txid: String): String? =
        handle.getTxCategory(txid)

    override fun addContact(label: String, address: String): Long =
        handle.addContact(label, address)

    override fun listContacts(): List<ContactRecord> =
        handle.listContacts()

    override fun updateContactLabel(id: Long, newLabel: String): Boolean =
        handle.updateContactLabel(id, newLabel)

    override fun removeContact(id: Long): Boolean =
        handle.removeContact(id)

    override fun generateRandomMnemonic(wordCount: UInt): String =
        handle.generateRandomMnemonic(wordCount)

    override fun exportEncryptedBackup(password: String): String =
        handle.exportEncryptedBackup(password)

    override fun peekMnemonic(): io.bhodl.ffi.MnemonicReveal =
        handle.peekMnemonic()

    override fun exportPublicDescriptor(): io.bhodl.ffi.PublicDescriptorBundle =
        handle.exportPublicDescriptor()

    override fun trackerDescriptorPub(): String =
        handle.trackerDescriptorPub()

    /**
     * Call UniFFI's auto-generated `Disposable.destroy()` to
     * decrement the FFI-side Arc strong count and trigger Rust's
     * `Drop` on `WalletHandle`. That cascades through the
     * `Mutex<OnchainAccount>` → `OnchainAccount` → `Connection`
     * chain, releasing the SQLCipher file handle synchronously
     * before `destroy()` returns. The desktop wipe path relies on
     * this so a subsequent `wallet.sqlite` delete is not blocked by
     * a Windows file lock (finding #14 in
     * `SMOKE_TEST_FINDINGS.md`).
     *
     * Errors are swallowed — `destroy()` is idempotent on the
     * UniFFI side and we don't want a teardown hiccup to abort the
     * rest of the wipe flow.
     */
    override fun close() {
        runCatching { handle.destroy() }
    }
}
