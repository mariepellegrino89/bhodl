package io.bhodl.desktop.wallet

import io.bhodl.ffi.AddressRecord
import io.bhodl.ffi.ContactRecord
import io.bhodl.ffi.PublicDescriptorBundle
import io.bhodl.ffi.TransactionRecord
import io.bhodl.ffi.UtxoRecord
import io.bhodl.ffi.WalletException

/**
 * Pure-Kotlin in-memory [`WalletPort`] for Compose UI tests.
 *
 * No SQLCipher, no Esplora, no cdylib. Methods either return the
 * stub values configured by the test or throw the typed
 * `WalletException` the test asks for. The implementation is
 * intentionally minimal — extend a field as needs grow rather than
 * try to model BDK's full state machine here.
 *
 * Counters (`syncCalls`, `nextReceiveCalls`, …) let tests assert
 * call patterns without hooking onto Mockito.
 */
public class FakeWalletPort(
    public var address: String = "tb1pfakeaddressxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    public var balance: ULong = 0u,
    /** `true` simulates an address-list watch wallet (Receive tab hidden). */
    public var addressList: Boolean = false,
) : WalletPort {

    public val syncCalls: MutableList<String> = mutableListOf()
    public val syncProxies: MutableList<String?> = mutableListOf()
    public val listUtxosCalls: MutableList<Unit> = mutableListOf()
    public val utxos: MutableList<UtxoRecord> = mutableListOf()
    public val transactions: MutableList<TransactionRecord> = mutableListOf()
    public val labels: MutableMap<String, String> = mutableMapOf()
    public val contacts: MutableList<ContactRecord> = mutableListOf()
    private var nextContactId: Long = 1

    /**
     * Drop-in bag of "this method should explode" overrides — when
     * non-null, the corresponding method throws instead of returning
     * its default. Lets tests exercise the screen's error rendering
     * without needing a real BDK failure.
     */
    public var failSync: WalletException? = null

    override fun isAddressList(): Boolean = addressList

    override fun peekAddressAt(index: UInt): String = address

    /**
     * Internal counter incremented by every [`nextReceiveAddress`]
     * call. Lets tests assert the screen is asking for fresh
     * indices instead of replaying the cached default.
     */
    public var revealCounter: Int = 0

    /** Captures every [`nextReceiveAddress`] invocation. */
    public val nextReceiveCalls: MutableList<Unit> = mutableListOf()

    /** Optional failure injection for [`nextReceiveAddress`]. */
    public var failNextReceive: WalletException? = null

    /**
     * Override of the address-generation strategy. Defaults to
     * `address + revealCounter` so tests get visibly distinct
     * strings without having to plug in a real BDK derivation.
     */
    public var nextReceiveBuilder: (Int) -> String = { idx ->
        // Bech32m taproot addresses are 62 chars on signet, so we
        // pad / truncate to the same shape the real wallet returns.
        ("tb1pfakeaddress$idx").padEnd(62, 'x').take(62)
    }

    override fun nextReceiveAddress(): String {
        failNextReceive?.let { throw it }
        nextReceiveCalls += Unit
        val out = nextReceiveBuilder(revealCounter)
        revealCounter++
        return out
    }

    /** Addresses the fake treats as "has received funds". */
    public val usedAddresses: MutableSet<String> = mutableSetOf()

    /** Captures every [`nextUnusedReceiveAddress`] invocation. */
    public val nextUnusedCalls: MutableList<Unit> = mutableListOf()

    /**
     * Mirrors BDK's `next_unused_address`: hands out the last revealed
     * address again while it is unused, reveals the next index otherwise.
     */
    override fun nextUnusedReceiveAddress(): String {
        failNextReceive?.let { throw it }
        nextUnusedCalls += Unit
        if (revealCounter > 0) {
            val last = nextReceiveBuilder(revealCounter - 1)
            if (last !in usedAddresses) return last
        }
        val out = nextReceiveBuilder(revealCounter)
        revealCounter++
        return out
    }

    override fun balanceSat(): ULong = balance

    override fun syncWithEsplora(esploraUrl: String, proxyUrl: String?) {
        syncCalls += esploraUrl
        syncProxies += proxyUrl
        failSync?.let { throw it }
    }

    override fun listUtxos(): List<UtxoRecord> {
        listUtxosCalls += Unit
        return utxos.toList()
    }

    override fun listTransactions(): List<TransactionRecord> = transactions.toList()

    /** Receive (`false`) / change (`true`) addresses for the Addresses tab. */
    public val receiveAddresses: MutableList<AddressRecord> = mutableListOf()
    public val changeAddresses: MutableList<AddressRecord> = mutableListOf()

    override fun listAddresses(change: Boolean): List<AddressRecord> =
        (if (change) changeAddresses else receiveAddresses).toList()

    /** Every `(address, text, category)` [setAddressLabel] was called with. */
    public val addressLabelCalls: MutableList<Triple<String, String, String?>> = mutableListOf()

    override fun setAddressLabel(address: String, text: String, category: String?) {
        addressLabelCalls += Triple(address, text, category)
        for (list in listOf(receiveAddresses, changeAddresses)) {
            val idx = list.indexOfFirst { it.address == address }
            if (idx >= 0) {
                list[idx] = list[idx].copy(
                    label = text.ifBlank { null },
                    category = category?.ifBlank { null },
                )
            }
        }
    }

    /** Returned by [applyReceiveLabels]; counts calls. */
    public var receiveLabelCopies: List<io.bhodl.ffi.ReceiveLabelCopyRecord> = emptyList()
    public var applyReceiveLabelsCalls: Int = 0

    override fun applyReceiveLabels(): List<io.bhodl.ffi.ReceiveLabelCopyRecord> {
        applyReceiveLabelsCalls++
        return receiveLabelCopies
    }

    public var tipHeight: UInt = 0u

    override fun chainTipHeight(): UInt = tipHeight

    /** Captures every (jsonl) tuple `importLabelsJsonl` was called with. */
    public val importJsonlCalls: MutableList<String> = mutableListOf()

    /** Optional injection: number of records `importLabelsJsonl` reports. */
    public var importJsonlCount: ULong = 0u

    /** Optional failure injection for `exportLabelsJsonl`. */
    public var failExportLabels: WalletException? = null

    /** Optional failure injection for `importLabelsJsonl`. */
    public var failImportLabels: WalletException? = null

    /**
     * Default JSONL serialiser for the fake: emits one BIP-329 record
     * per `(txid, text)` in [`labels`]. Realistic enough for UI tests
     * without depending on rusqlite.
     */
    override fun exportLabelsJsonl(): String {
        failExportLabels?.let { throw it }
        if (labels.isEmpty()) return ""
        return labels.entries.joinToString("\n") { (txid, text) ->
            // Order keys alphabetically so tests get a stable string.
            """{"label":"${escape(text)}","ref":"$txid","type":"tx"}"""
        }
    }

    override fun importLabelsJsonl(jsonl: String): ULong {
        failImportLabels?.let { throw it }
        importJsonlCalls += jsonl
        return importJsonlCount
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    override fun setTxLabel(txid: String, text: String) {
        labels[txid] = text
    }

    override fun getTxLabel(txid: String): String? = labels[txid]

    override fun removeTxLabel(txid: String): Boolean = labels.remove(txid) != null

    override fun addContact(label: String, address: String): Long {
        // Mirrors the real schema: `contacts.address` is UNIQUE.
        if (contacts.any { it.address == address }) {
            throw WalletException.Storage(detail = "UNIQUE constraint failed: contacts.address")
        }
        val id = nextContactId++
        contacts += ContactRecord(
            id = id,
            label = label,
            address = address,
            createdAtUnix = 0L,
        )
        return id
    }

    override fun listContacts(): List<ContactRecord> = contacts.toList()

    override fun updateContactLabel(id: Long, newLabel: String): Boolean {
        val idx = contacts.indexOfFirst { it.id == id }
        if (idx < 0) return false
        contacts[idx] = contacts[idx].copy(label = newLabel)
        return true
    }

    override fun removeContact(id: Long): Boolean =
        contacts.removeAll { it.id == id }

    /** Captures every call to [`exportPublicDescriptor`]. */
    public val exportPublicDescriptorCalls: MutableList<Unit> = mutableListOf()

    /** Override of the bundle returned by [`exportPublicDescriptor`]. */
    public var exportPublicDescriptorResult: PublicDescriptorBundle? =
        PublicDescriptorBundle(
            network = "signet",
            externalDescriptor = "tr([fake/86h/1h/0h]tpubFAKE/0/i)#aaaaaaaa",
            internalDescriptor = "tr([fake/86h/1h/0h]tpubFAKE/1/i)#bbbbbbbb",
            accountXpub = "tpubFAKE",
        )

    /** Optional failure injection for `exportPublicDescriptor`. */
    public var failExportPublicDescriptor: WalletException? = null

    override fun exportPublicDescriptor(): PublicDescriptorBundle? {
        failExportPublicDescriptor?.let { throw it }
        exportPublicDescriptorCalls += Unit
        return exportPublicDescriptorResult
    }

    /** In-memory category store for tests. */
    public val txCategories: MutableMap<String, String> = mutableMapOf()

    override fun setTxCategory(txid: String, category: String?) {
        if (category == null) txCategories.remove(txid)
        else txCategories[txid] = category
    }

    override fun getTxCategory(txid: String): String? = txCategories[txid]

    /**
     * Tracker for [`close`] — lets tests assert that the production
     * teardown path (e.g. `WalletStore.clear` before a wipe) hits the
     * port. Count rather than boolean so a "called twice" regression
     * is visible without extra state.
     */
    public var closeCalls: Int = 0

    override fun close() {
        closeCalls += 1
    }
}
