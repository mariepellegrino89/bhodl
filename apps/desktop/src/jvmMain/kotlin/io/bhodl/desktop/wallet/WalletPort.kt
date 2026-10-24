package io.bhodl.desktop.wallet

import io.bhodl.ffi.AddressRecord
import io.bhodl.ffi.ContactRecord
import io.bhodl.ffi.PublicDescriptorBundle
import io.bhodl.ffi.ReceiveLabelCopyRecord
import io.bhodl.ffi.TransactionRecord
import io.bhodl.ffi.UtxoRecord
import io.bhodl.ffi.WalletException
import io.bhodl.ffi.WatchHandle

/**
 * Stable interface that the UI talks to instead of the concrete
 * `io.bhodl.ffi.WatchHandle` opaque object.
 *
 * BHODL is watch-only: every wallet is opened from public material
 * (xpub, HD descriptor or address list), so the port is read-only plus
 * local annotations (BIP-329 labels, address book). Production wires it
 * to [`UniffiWalletPort`], a thin pass-through to the FFI; tests wire it
 * to an in-memory fake so Compose UI tests can drive the wallet
 * workspace without a cdylib, SQLCipher or an Esplora endpoint.
 *
 * Errors travel as `io.bhodl.ffi.WalletException` because every
 * subclass is a generated `data class` with public constructors. A
 * fake can throw the same typed exceptions production code throws,
 * which keeps the UI's `catch` arms valid in both worlds.
 */
public interface WalletPort {

    /**
     * `true` for address-list watch wallets: no receive-address
     * derivation and no change keychain. The Receive tab is hidden.
     */
    @Throws(WalletException::class)
    public fun isAddressList(): Boolean

    @Throws(WalletException::class)
    public fun peekAddressAt(index: UInt): String

    /**
     * Reveal the next external (receive) address and persist the
     * advance — every call returns a fresh index. Counterpart of
     * [`peekAddressAt`] which never advances.
     */
    @Throws(WalletException::class)
    public fun nextReceiveAddress(): String

    /**
     * Lowest revealed receive address that has not received funds yet;
     * reveals a new index only when all are used. The label-first
     * Receive tab uses it so visits and label edits never burn indices
     * (gap limit); [`nextReceiveAddress`] is the explicit "New address".
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
     * Every revealed address on one keychain (`change = false`:
     * receive), oldest first, with used / unused state and the BIP-329
     * label + category. Backs the Addresses tab and the Receive reuse
     * warning.
     */
    @Throws(WalletException::class)
    public fun listAddresses(change: Boolean): List<AddressRecord>

    /**
     * Set (or clear, when both blank) the BIP-329 `addr` label and
     * category of one address — the label-first Receive flow.
     */
    @Throws(WalletException::class)
    public fun setAddressLabel(address: String, text: String, category: String?)

    /**
     * Copy receive-address labels / categories onto the incoming
     * transactions that paid them (once each, never over an existing
     * label). Returns what was written.
     */
    @Throws(WalletException::class)
    public fun applyReceiveLabels(): List<ReceiveLabelCopyRecord>

    /** Last synced block height (`0` before the first sync). */
    @Throws(WalletException::class)
    public fun chainTipHeight(): UInt

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

    /**
     * Public descriptors + bare account xpub, for import into Sparrow /
     * Liana / Specter. `null` for address-list wallets.
     */
    @Throws(WalletException::class)
    public fun exportPublicDescriptor(): PublicDescriptorBundle?

    /**
     * Deterministically release the native handle (SQLCipher file
     * lock). Idempotent. Callers must drop their reference afterwards —
     * typically [`io.bhodl.desktop.wizard.WalletStore.clear`] does both,
     * so a later delete of `watch-state/<id>.sqlite` is not blocked by a
     * Windows file lock the JVM GC hasn't released yet.
     */
    public fun close()
}

/**
 * Production implementation of [`WalletPort`]: wraps a real
 * `WatchHandle` (opened via `RegistryHandle.openWatch`) and forwards
 * every call.
 */
public class UniffiWalletPort(
    private val handle: WatchHandle,
) : WalletPort {

    override fun isAddressList(): Boolean = handle.isAddressList()

    override fun peekAddressAt(index: UInt): String = handle.peekAddressAt(index)

    override fun nextReceiveAddress(): String = handle.nextReceiveAddress()

    override fun nextUnusedReceiveAddress(): String = handle.nextUnusedReceiveAddress()

    override fun balanceSat(): ULong = handle.balanceSat()

    override fun syncWithEsplora(esploraUrl: String, proxyUrl: String?) {
        handle.syncWithEsplora(esploraUrl, proxyUrl)
    }

    override fun listUtxos(): List<UtxoRecord> = handle.listUtxos()

    override fun listTransactions(): List<TransactionRecord> = handle.listTransactions()

    override fun listAddresses(change: Boolean): List<AddressRecord> =
        handle.listAddresses(change)

    override fun setAddressLabel(address: String, text: String, category: String?) {
        handle.setAddressLabel(address, text, category)
    }

    override fun applyReceiveLabels(): List<ReceiveLabelCopyRecord> =
        handle.applyReceiveLabels()

    override fun chainTipHeight(): UInt = handle.chainTipHeight()

    override fun exportLabelsJsonl(): String = handle.exportLabelsJsonl()

    override fun importLabelsJsonl(jsonl: String): ULong = handle.importLabelsJsonl(jsonl)

    override fun setTxLabel(txid: String, text: String) {
        handle.setTxLabel(txid, text)
    }

    override fun getTxLabel(txid: String): String? = handle.getTxLabel(txid)

    override fun removeTxLabel(txid: String): Boolean = handle.removeTxLabel(txid)

    override fun setTxCategory(txid: String, category: String?) {
        handle.setTxCategory(txid, category)
    }

    override fun getTxCategory(txid: String): String? = handle.getTxCategory(txid)

    override fun addContact(label: String, address: String): Long =
        handle.addContact(label, address)

    override fun listContacts(): List<ContactRecord> = handle.listContacts()

    override fun updateContactLabel(id: Long, newLabel: String): Boolean =
        handle.updateContactLabel(id, newLabel)

    override fun removeContact(id: Long): Boolean = handle.removeContact(id)

    override fun exportPublicDescriptor(): PublicDescriptorBundle? =
        handle.exportPublicDescriptor()

    /**
     * Call UniFFI's generated `Disposable.destroy()` so Rust drops the
     * `WatchAccount` and its SQLCipher connection synchronously. Errors
     * are swallowed: `destroy()` is idempotent and a teardown hiccup
     * must not abort a wipe / remove flow.
     */
    override fun close() {
        runCatching { handle.destroy() }
    }
}
