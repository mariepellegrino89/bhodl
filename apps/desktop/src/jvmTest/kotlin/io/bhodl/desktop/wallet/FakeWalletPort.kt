package io.bhodl.desktop.wallet

import io.bhodl.ffi.AddressRecord
import io.bhodl.ffi.ContactRecord
import io.bhodl.ffi.CpfpPlan
import io.bhodl.ffi.FeeEstimates
import io.bhodl.ffi.PayjoinOutput
import io.bhodl.ffi.PayjoinReview
import io.bhodl.ffi.PsbtOutput
import io.bhodl.ffi.PsbtSummary
import io.bhodl.ffi.RecipientEntry
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
 * Counters (`buildSendTxCalls`, `signCalls`, …) let tests assert
 * call patterns ("Confirm and send fired sign + broadcast in order")
 * without hooking onto Mockito.
 */
public class FakeWalletPort(
    public var address: String = "tb1pfakeaddressxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    public var balance: ULong = 0u,
    public var feeEstimates: FeeEstimates = FeeEstimates(
        economySatPerVb = 1u,
        normalSatPerVb = 1u,
        prioritySatPerVb = 1u,
    ),
    public var psbtFee: ULong = 155u,
    public var unsignedPsbtBuilder: () -> String = { "fake-unsigned-psbt" },
    public var signedPsbtBuilder: (String) -> String = { "fake-signed-psbt" },
    public var broadcastTxidBuilder: () -> String = {
        "deadbeef".repeat(8) // 64 hex chars
    },
) : WalletPort {

    public val syncCalls: MutableList<String> = mutableListOf()
    public val syncProxies: MutableList<String?> = mutableListOf()
    public val broadcastProxies: MutableList<String?> = mutableListOf()
    public val fetchFeesProxies: MutableList<String?> = mutableListOf()
    public val listUtxosCalls: MutableList<Unit> = mutableListOf()
    public val buildSendTxCalls: MutableList<Triple<String, ULong, ULong>> = mutableListOf()
    public val buildBatchCalls: MutableList<Pair<List<RecipientEntry>, ULong>> = mutableListOf()
    public val buildDrainCalls: MutableList<Pair<String, ULong>> = mutableListOf()
    public val buildWithUtxosCalls:
        MutableList<BuildWithUtxos> = mutableListOf()
    public val buildBatchWithUtxosCalls:
        MutableList<BuildBatchWithUtxos> = mutableListOf()
    public val buildBumpCalls: MutableList<Pair<String, ULong>> = mutableListOf()
    public val buildCpfpCalls: MutableList<Pair<String, ULong>> = mutableListOf()
    public val signCalls: MutableList<String> = mutableListOf()
    public val broadcastCalls: MutableList<Pair<String, String>> = mutableListOf()

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
    public var failBuildSendTx: WalletException? = null
    public var failSignPsbt: WalletException? = null
    public var failBroadcast: WalletException? = null
    public var failFetchFees: WalletException? = null
    public var failPsbtFee: WalletException? = null
    public var failSync: WalletException? = null

    /**
     * Thrown by every `build*` call made with `allowHighFee = false`
     * (e.g. `WalletException.FeeTooHigh`); a rebuild with
     * `allowHighFee = true` succeeds. Drives the second-confirmation UI.
     */
    public var failBuildUnlessHighFeeAllowed: WalletException? = null

    /** `allowHighFee` flag of every `build*` call, in call order. */
    public val allowHighFeeCalls: MutableList<Boolean> = mutableListOf()

    /** Every PSBT handed to [`decodePsbt`]. */
    public val decodeCalls: MutableList<String> = mutableListOf()
    public var failDecodePsbt: WalletException? = null

    /**
     * Summary returned by [`decodePsbt`]. Default: one external output
     * to [`summaryRecipient`] of [`summaryAmountSat`] plus change, fee
     * = [`psbtFee`].
     */
    public var summaryRecipient: String = DEFAULT_SUMMARY_RECIPIENT
    public var summaryAmountSat: ULong = 1_000u
    public var psbtSummaryBuilder: (String) -> PsbtSummary = { _ -> defaultSummary() }

    public fun defaultSummary(
        outputs: List<PsbtOutput> = listOf(
            PsbtOutput(summaryRecipient, summaryAmountSat, isMine = false, isChange = false),
            PsbtOutput("tb1pfakechange", 50_000u, isMine = true, isChange = true),
        ),
        feeSat: ULong = psbtFee,
        feeLimitSat: ULong = 50_000u,
        spendsUnconfirmedForeign: Boolean = false,
    ): PsbtSummary {
        val external = outputs.filter { !it.isMine }.sumOf { it.amountSat }
        val own = outputs.filter { it.isMine }.sumOf { it.amountSat }
        val inputs = external + own + feeSat
        return PsbtSummary(
            outputs = outputs,
            inputCount = 1u,
            foreignInputCount = 0u,
            inputsTotalSat = inputs,
            feeSat = feeSat,
            vsize = 150u,
            sentExternalSat = external,
            walletNetDebitSat = external + feeSat,
            feeLimitSat = feeLimitSat,
            feeExceedsLimit = feeSat > feeLimitSat,
            spendsUnconfirmedForeign = spendsUnconfirmedForeign,
        )
    }

    /** Plan returned by [`buildCpfpPsbt`] (psbt from [`unsignedPsbtBuilder`]). */
    public var cpfpPlanBuilder: (String, ULong, String) -> CpfpPlan = { parent, rate, psbt ->
        CpfpPlan(
            psbt = psbt,
            parentTxid = parent,
            parentFeeSat = 150u,
            parentVsize = 150u,
            ancestorCount = 0u,
            ancestorsFeeSat = 0u,
            ancestorsVsize = 0u,
            childFeeSat = psbtFee,
            childVsize = 110u,
            targetFeeRateSatVb = rate,
        )
    }

    private fun recordBuild(allowHighFee: Boolean) {
        failBuildSendTx?.let { throw it }
        allowHighFeeCalls += allowHighFee
        if (!allowHighFee) failBuildUnlessHighFeeAllowed?.let { throw it }
    }

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

    /** Every `(outpoint, frozen)` pair [setUtxoFrozen] was called with. */
    public val setFrozenCalls: MutableList<Pair<String, Boolean>> = mutableListOf()

    override fun setUtxoFrozen(outpoint: String, frozen: Boolean) {
        setFrozenCalls += outpoint to frozen
        val idx = utxos.indexOfFirst { it.outpoint == outpoint }
        if (idx >= 0) utxos[idx] = utxos[idx].copy(frozen = frozen)
    }

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

    override fun fetchFeeEstimates(esploraUrl: String, proxyUrl: String?): FeeEstimates {
        fetchFeesProxies += proxyUrl
        failFetchFees?.let { throw it }
        return feeEstimates
    }

    override fun psbtFeeSat(psbtB64: String): ULong {
        failPsbtFee?.let { throw it }
        return psbtFee
    }

    override fun decodePsbt(psbtB64: String): PsbtSummary {
        failDecodePsbt?.let { throw it }
        decodeCalls += psbtB64
        return psbtSummaryBuilder(psbtB64)
    }

    override fun buildSendTx(
        toAddress: String,
        amountSat: ULong,
        feeRateSatVb: ULong,
        allowHighFee: Boolean,
    ): String {
        recordBuild(allowHighFee)
        buildSendTxCalls += Triple(toAddress, amountSat, feeRateSatVb)
        return unsignedPsbtBuilder()
    }

    override fun buildSendTxBatch(
        recipients: List<RecipientEntry>,
        feeRateSatVb: ULong,
        allowHighFee: Boolean,
    ): String {
        recordBuild(allowHighFee)
        buildBatchCalls += recipients to feeRateSatVb
        return unsignedPsbtBuilder()
    }

    override fun buildDrainTx(toAddress: String, feeRateSatVb: ULong, allowHighFee: Boolean): String {
        recordBuild(allowHighFee)
        buildDrainCalls += toAddress to feeRateSatVb
        return unsignedPsbtBuilder()
    }

    override fun buildSendTxWithUtxos(
        toAddress: String,
        amountSat: ULong,
        feeRateSatVb: ULong,
        outpoints: List<String>,
        allowHighFee: Boolean,
    ): String {
        recordBuild(allowHighFee)
        buildWithUtxosCalls += BuildWithUtxos(toAddress, amountSat, feeRateSatVb, outpoints)
        return unsignedPsbtBuilder()
    }

    override fun buildSendTxBatchWithUtxos(
        recipients: List<RecipientEntry>,
        feeRateSatVb: ULong,
        outpoints: List<String>,
        allowHighFee: Boolean,
    ): String {
        recordBuild(allowHighFee)
        buildBatchWithUtxosCalls += BuildBatchWithUtxos(recipients, feeRateSatVb, outpoints)
        return unsignedPsbtBuilder()
    }

    override fun buildFeeBumpPsbt(txid: String, newFeeRateSatVb: ULong, allowHighFee: Boolean): String {
        recordBuild(allowHighFee)
        buildBumpCalls += txid to newFeeRateSatVb
        return unsignedPsbtBuilder()
    }

    override fun buildCpfpPsbt(parentTxid: String, feeRateSatVb: ULong, allowHighFee: Boolean): CpfpPlan {
        recordBuild(allowHighFee)
        buildCpfpCalls += parentTxid to feeRateSatVb
        return cpfpPlanBuilder(parentTxid, feeRateSatVb, unsignedPsbtBuilder())
    }

    public data class PayjoinNegotiateCall(
        val uri: String,
        val expectedAddress: String,
        val expectedAmountSat: ULong,
        val feeRateSatVb: ULong,
        val proxyUrl: String?,
        val allowHighFee: Boolean = false,
    )

    public data class PayjoinSignCall(
        val sessionId: String,
        val esploraUrl: String,
        val proxyUrl: String?,
    )

    public val payjoinNegotiateCalls: MutableList<PayjoinNegotiateCall> = mutableListOf()
    public val payjoinSignCalls: MutableList<PayjoinSignCall> = mutableListOf()
    public val payjoinDiscardCalls: MutableList<String> = mutableListOf()
    public var failPayjoinNegotiate: WalletException? = null
    public var failPayjoinSign: WalletException? = null

    /** Default: a successful negotiation paying the requested amount. */
    public var payjoinReviewBuilder: (PayjoinNegotiateCall) -> PayjoinReview = { call ->
        PayjoinReview(
            sessionId = "fake-session",
            failureReason = null,
            originalPsbt = "fake-original-psbt",
            originalFeeSat = 200u,
            proposalFeeSat = 300u,
            senderFeeContributionSat = 100u,
            vsize = 220u,
            receiverInputCount = 1u,
            senderNetSpendSat = call.expectedAmountSat + 300u,
            outputs = listOf(
                PayjoinOutput(
                    address = call.expectedAddress,
                    amountSat = call.expectedAmountSat + 50_000u,
                    isMine = false,
                    isPayment = true,
                ),
                PayjoinOutput(
                    address = "tb1pfakechange",
                    amountSat = 12_345u,
                    isMine = true,
                    isPayment = false,
                ),
            ),
        )
    }
    public var payjoinTxidBuilder: (PayjoinSignCall) -> String = { _ ->
        "feedface".repeat(8) // 64 hex chars
    }

    override fun payjoinNegotiate(
        uri: String,
        expectedAddress: String,
        expectedAmountSat: ULong,
        feeRateSatVb: ULong,
        proxyUrl: String?,
        allowHighFee: Boolean,
    ): PayjoinReview {
        val call = PayjoinNegotiateCall(uri, expectedAddress, expectedAmountSat, feeRateSatVb, proxyUrl, allowHighFee)
        payjoinNegotiateCalls += call
        failPayjoinNegotiate?.let { throw it }
        if (!allowHighFee) failBuildUnlessHighFeeAllowed?.let { throw it }
        return payjoinReviewBuilder(call)
    }

    override fun payjoinSignAndBroadcast(
        sessionId: String,
        esploraUrl: String,
        proxyUrl: String?,
    ): String {
        failPayjoinSign?.let { throw it }
        val call = PayjoinSignCall(sessionId, esploraUrl, proxyUrl)
        payjoinSignCalls += call
        return payjoinTxidBuilder(call)
    }

    override fun payjoinDiscard(sessionId: String): Boolean {
        payjoinDiscardCalls += sessionId
        return true
    }

    override fun signPsbt(psbtB64: String): String {
        failSignPsbt?.let { throw it }
        signCalls += psbtB64
        return signedPsbtBuilder(psbtB64)
    }

    override fun broadcastTx(esploraUrl: String, signedPsbtB64: String, proxyUrl: String?): String {
        failBroadcast?.let { throw it }
        broadcastCalls += esploraUrl to signedPsbtB64
        broadcastProxies += proxyUrl
        return broadcastTxidBuilder()
    }

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

    override fun generateRandomMnemonic(wordCount: UInt): String =
        List(wordCount.toInt()) { "abandon" }.joinToString(" ")

    /** Captures every (password) tuple [`exportEncryptedBackup`] was called with. */
    public val exportBackupCalls: MutableList<String> = mutableListOf()

    /**
     * Override returned by [`exportEncryptedBackup`]. Defaults to a
     * realistic-looking ASCII-armored age payload so `startsWith`
     * assertions in production code stay green.
     */
    public var exportBackupBlobBuilder: (String) -> String = { _ ->
        "-----BEGIN AGE ENCRYPTED FILE-----\nfake-blob\n-----END AGE ENCRYPTED FILE-----\n"
    }

    /** Optional failure injection for `exportEncryptedBackup`. */
    public var failExportBackup: WalletException? = null

    override fun exportEncryptedBackup(password: String): String {
        failExportBackup?.let { throw it }
        exportBackupCalls += password
        return exportBackupBlobBuilder(password)
    }

    /** Captures every call to [`peekMnemonic`]. */
    public val peekMnemonicCalls: MutableList<Unit> = mutableListOf()

    /**
     * Override of the (mnemonic, passphrase) pair returned by
     * [`peekMnemonic`]. Defaults to the BIP86 abandon-12 phrase
     * with an empty passphrase.
     */
    public var peekMnemonicResult: io.bhodl.ffi.MnemonicReveal = io.bhodl.ffi.MnemonicReveal(
        mnemonic = "abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon about",
        passphrase = "",
    )

    /** Optional failure injection for `peekMnemonic`. */
    public var failPeekMnemonic: WalletException? = null

    override fun peekMnemonic(): io.bhodl.ffi.MnemonicReveal {
        failPeekMnemonic?.let { throw it }
        peekMnemonicCalls += Unit
        return peekMnemonicResult
    }

    /** Captures every call to [`exportPublicDescriptor`]. */
    public val exportPublicDescriptorCalls: MutableList<Unit> = mutableListOf()

    /** Override of the bundle returned by [`exportPublicDescriptor`]. */
    public var exportPublicDescriptorResult: io.bhodl.ffi.PublicDescriptorBundle =
        io.bhodl.ffi.PublicDescriptorBundle(
            network = "signet",
            externalDescriptor = "tr([fake/86h/1h/0h]tpubFAKE/0/i)#aaaaaaaa",
            internalDescriptor = "tr([fake/86h/1h/0h]tpubFAKE/1/i)#bbbbbbbb",
            accountXpub = "tpubFAKE",
        )

    /** Optional failure injection for `exportPublicDescriptor`. */
    public var failExportPublicDescriptor: WalletException? = null

    override fun exportPublicDescriptor(): io.bhodl.ffi.PublicDescriptorBundle {
        failExportPublicDescriptor?.let { throw it }
        exportPublicDescriptorCalls += Unit
        return exportPublicDescriptorResult
    }

    /** Phase 6.0 — Tracker pairing descriptor, lifted from the
     *  same `exportPublicDescriptorResult` so tests can stage one
     *  fixture and read it back via either accessor. */
    override fun trackerDescriptorPub(): String {
        failExportPublicDescriptor?.let { throw it }
        return exportPublicDescriptorResult.externalDescriptor
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

    public data class BuildWithUtxos(
        val toAddress: String,
        val amountSat: ULong,
        val feeRateSatVb: ULong,
        val outpoints: List<String>,
    )

    public data class BuildBatchWithUtxos(
        val recipients: List<RecipientEntry>,
        val feeRateSatVb: ULong,
        val outpoints: List<String>,
    )

    public companion object {
        public const val DEFAULT_SUMMARY_RECIPIENT: String =
            "tb1ptarget00000000000000000000000000000000000000000000000000000000"
    }
}
