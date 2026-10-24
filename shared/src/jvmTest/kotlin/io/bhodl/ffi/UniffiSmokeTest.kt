package io.bhodl.ffi

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cheap JVM smoke harness for the UniFFI bridge.
 *
 * Runs in seconds (no Compose, no boot guard) and exercises the FFI call
 * paths that have been fragile during the Phase-1 RustBuffer /
 * cross-DLL allocator investigation. If a future change to
 * `crates/bhodl-uniffi/`, `crates/bhodl-wallet-onchain/`, the cargo
 * profile, or the `:shared` UniFFI plugin wiring breaks the FFI ABI,
 * this is the file that should fail first — not the desktop app at
 * runtime.
 *
 * Windows ABI note (docs/uniffi-runtime.md): with uniffi 0.28.3 + the
 * Ubique KMP fork only the FIRST `#[uniffi::Object]` declared in the
 * Rust file can safely return `Result<String, _>`; on any other Object
 * the `RustBuffer.free` of the lifted string crashes. BHODL is
 * watch-only and `WatchHandle` is now declared first, so its
 * `Result<String, _>` methods (`peekAddressAt`, `nextUnusedReceiveAddress`,
 * `nextReceiveAddress`, `exportLabelsJsonl`) are exactly what this test
 * must keep proving.
 *
 * Run with `./gradlew :shared:jvmTest --tests "io.bhodl.ffi.UniffiSmokeTest"`.
 */
class UniffiSmokeTest {

    private lateinit var dir: File
    private var registry: RegistryHandle? = null
    private val opened = mutableListOf<WatchHandle>()

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("bhodl-smoke-").toFile()
    }

    @AfterTest
    fun tearDown() {
        // Release the SQLCipher file locks first (Windows cannot delete
        // an open file), then the temp dir.
        opened.forEach { runCatching { it.destroy() } }
        registry?.let { runCatching { it.destroy() } }
        dir.deleteRecursively()
    }

    private fun registry(): RegistryHandle =
        registry ?: RegistryHandle.createOrLoad(dir.absolutePath, DEVICE_PIN).also { registry = it }

    private fun openBip86Watch(label: String = "smoke"): WatchHandle {
        val record = registry().addWatchXpub(label, BIP86_SIGNET_TPUB, Network.SIGNET, WatchBipFfi.TAPROOT)
        return registry().openWatch(record.id).also { opened += it }
    }

    /**
     * `format_sat` was the original Phase-0 spike call that proved the FFI
     * end-to-end. Keep it here as a regression marker: if even this fails
     * the Kotlin <-> Rust bridge is wholly broken, not a String-encoding
     * issue.
     */
    @Test
    fun `format_sat round-trips a simple value`() {
        val rendered = formatSat(123_456UL)
        assertTrue(rendered.isNotEmpty(), "format_sat returned empty string")
    }

    @Test
    fun `watch xpub derives the BIP-86 signet receive addresses`() {
        val watch = openBip86Watch()
        assertFalse(watch.isAddressList())
        assertEquals(BIP86_SIGNET_ADDRESS_0, watch.peekAddressAt(0u))
        assertEquals(BIP86_SIGNET_ADDRESS_1, watch.peekAddressAt(1u))
    }

    /**
     * `Result<String, _>` method on `WatchHandle` (the Windows ABI case
     * above). On a fresh wallet the lowest unused receive address is
     * index 0, and asking again does not burn a new index.
     */
    @Test
    fun `next_unused_receive_address returns index 0 and is stable`() {
        val watch = openBip86Watch()
        assertEquals(BIP86_SIGNET_ADDRESS_0, watch.nextUnusedReceiveAddress())
        assertEquals(BIP86_SIGNET_ADDRESS_0, watch.nextUnusedReceiveAddress())
        // The explicit "New address" advances.
        assertEquals(BIP86_SIGNET_ADDRESS_1, watch.nextReceiveAddress())
    }

    /** `Result<String, _>` method on `WatchHandle`, empty and non-empty. */
    @Test
    fun `export_labels_jsonl round-trips a tx label`() {
        val watch = openBip86Watch()
        assertEquals("", watch.exportLabelsJsonl().trim())

        val txid = "ab".repeat(32)
        watch.setTxLabel(txid, "Salary")
        assertEquals("Salary", watch.getTxLabel(txid))
        val jsonl = watch.exportLabelsJsonl()
        assertTrue(txid in jsonl && "Salary" in jsonl, "unexpected export: $jsonl")

        // Import into a second wallet of the same registry.
        val other = registry().addWatchAddresses("import", listOf(BIP86_SIGNET_ADDRESS_0), Network.SIGNET)
        val otherWatch = registry().openWatch(other.id).also { opened += it }
        assertEquals(1UL, otherWatch.importLabelsJsonl(jsonl))
        assertEquals("Salary", otherWatch.getTxLabel(txid))
    }

    @Test
    fun `public descriptor carries the registered account xpub`() {
        val bundle = assertNotNull(openBip86Watch().exportPublicDescriptor())
        assertEquals(BIP86_SIGNET_TPUB, bundle.accountXpub)
        assertTrue(bundle.externalDescriptor.startsWith("tr("), bundle.externalDescriptor)
    }

    @Test
    fun `address-list wallet has no descriptor and is flagged`() {
        val record = registry().addWatchAddresses("list", listOf(BIP86_SIGNET_ADDRESS_0), Network.SIGNET)
        val watch = registry().openWatch(record.id).also { opened += it }
        assertTrue(watch.isAddressList())
        assertNull(watch.exportPublicDescriptor())
        assertEquals(0UL, watch.balanceSat())
        assertTrue(watch.listUtxos().isEmpty())
    }

    @Test
    fun `fresh registry has no legacy signing wallets`() {
        assertEquals(0u, registry().legacySigningWalletCount())
        assertEquals(0u, registry().purgeLegacySigningWallets())
    }

    @Test
    fun `invalid xpub is rejected with a typed WalletException`() {
        val ex = assertFailsWith<WalletException> {
            registry().addWatchXpub("bad", "tpub-not-a-key", Network.SIGNET, WatchBipFfi.TAPROOT)
        }
        assertTrue(!ex.message.isNullOrEmpty(), "expected populated error message, got: ${ex.message}")
    }

    /**
     * Round-trip: register a watch wallet, drop every handle, reopen the
     * registry with the device PIN and the wallet by id — the returning
     * user path of the desktop app.
     */
    @Test
    fun `registry reopens a watch wallet after every handle is dropped`() {
        val first = openBip86Watch()
        val id = registry().listWallets().single().id
        first.destroy()
        opened.clear()
        registry!!.destroy()
        registry = null

        val reopened = registry().openWatch(id).also { opened += it }
        assertEquals(BIP86_SIGNET_ADDRESS_0, reopened.peekAddressAt(0u))
    }

    private companion object {
        const val DEVICE_PIN: String = "482916"

        /**
         * Account tpub at `m/86'/1'/0'` of the BIP-86 test mnemonic
         * ("abandon" x 11 + "about"); derivation as in
         * `crates/bhodl-wallet-onchain/src/test_support.rs`.
         */
        const val BIP86_SIGNET_TPUB: String =
            "tpubDDfvzhdVV4unsoKt5aE6dcsNsfeWbTgmLZPi8LQDYU2xixrYemMfWJ3BaVneH3u7DBQePdTwhpybaKRU95pi6PMUtLPBJLVQRpzEnjfjZzX"

        /** `m/86'/1'/0'/0/0` and `/0/1` of that account on signet. */
        const val BIP86_SIGNET_ADDRESS_0: String =
            "tb1p8wpt9v4frpf3tkn0srd97pksgsxc5hs52lafxwru9kgeephvs7rqlqt9zj"
        const val BIP86_SIGNET_ADDRESS_1: String =
            "tb1p90h6z3p36n9hrzy7580h5l429uwchyg8uc9sz4jwzhdtuhqdl5eqmpwq6n"
    }
}
