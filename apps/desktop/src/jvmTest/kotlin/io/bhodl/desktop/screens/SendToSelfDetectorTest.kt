package io.bhodl.desktop.screens

import io.bhodl.desktop.domain.SEND_TO_SELF
import io.bhodl.desktop.domain.canonicalWalletIds
import io.bhodl.desktop.domain.detectSendToSelf
import io.bhodl.desktop.domain.dropAliasedEvents
import io.bhodl.desktop.domain.staleSendToSelfTags
import io.bhodl.ffi.CryptoEventKindRow
import io.bhodl.ffi.PaymentDirection
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.PaymentKind
import io.bhodl.ffi.WalletKindFfi
import io.bhodl.ffi.WalletRecordFfi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 7.10 — pure tests for [detectSendToSelf] and the wallet-alias
 * dedup / cleanup helpers.
 *
 * Regressions pinned here:
 *  - the detector once ran over the txid-deduplicated list, so it never
 *    fired;
 *  - audit H13: the signing wallet's events + its paired watch mirror
 *    made EVERY tx look like it was seen by two wallets, so every tx was
 *    persisted as send_to_self. The rule is now "debit in one of my
 *    wallets, credits only to my wallets (external flow == fee)", run
 *    after collapsing wallet aliases.
 */
class SendToSelfDetectorTest {

    private val now = 1_700_000_000UL

    // ─── Strict rule ────────────────────────────────────────────────

    @Test
    fun `transfer whose only external flow is the fee is internal`() {
        // w1 spends 100_000 + 500 fee, w2 receives 100_000.
        val events = listOf(
            leg("w1", "aabb", PaymentDirection.OUTGOING, 100_500UL, fee = 500UL),
            leg("w2", "aabb", PaymentDirection.INCOMING, 100_000UL),
        )
        val detected = detectSendToSelf(events)
        assertEquals(setOf("w1", "w2"), detected.map { it.walletId }.toSet())
    }

    @Test
    fun `payment with change to another own wallet is NOT internal`() {
        // w1 pays 60_000 to a third party, change 39_500 lands in w2.
        val events = listOf(
            leg("w1", "aabb", PaymentDirection.OUTGOING, 100_000UL, fee = 500UL),
            leg("w2", "aabb", PaymentDirection.INCOMING, 39_500UL),
        )
        assertTrue(detectSendToSelf(events).isEmpty())
    }

    @Test
    fun `unknown fee cannot prove an internal transfer`() {
        val events = listOf(
            leg("w1", "aabb", PaymentDirection.OUTGOING, 100_500UL, fee = null),
            leg("w2", "aabb", PaymentDirection.INCOMING, 100_000UL),
        )
        assertTrue(detectSendToSelf(events).isEmpty())
    }

    @Test
    fun `two incoming legs without a debit are not internal`() {
        // Someone else's tx paying two of my wallets.
        val events = listOf(
            leg("w1", "aabb", PaymentDirection.INCOMING, 10_000UL, fee = 300UL),
            leg("w2", "aabb", PaymentDirection.INCOMING, 20_000UL),
        )
        assertTrue(detectSendToSelf(events).isEmpty())
    }

    @Test
    fun `txid confined to a single wallet is left alone`() {
        val events = listOf(
            leg("w1", "aabb", PaymentDirection.OUTGOING, 1_000UL, fee = 1_000UL),
            leg("w1", "ccdd", PaymentDirection.INCOMING, 5_000UL),
        )
        assertTrue(detectSendToSelf(events).isEmpty())
    }

    @Test
    fun `fan-out to two other own wallets is internal`() {
        val events = listOf(
            leg("w1", "aabb", PaymentDirection.OUTGOING, 30_400UL, fee = 400UL),
            leg("w2", "aabb", PaymentDirection.INCOMING, 10_000UL),
            leg("w3", "aabb", PaymentDirection.INCOMING, 20_000UL),
        )
        assertEquals(3, detectSendToSelf(events).size)
    }

    // ─── Overrides ──────────────────────────────────────────────────

    @Test
    fun `a stored classification is never overwritten`() {
        val events = listOf(
            leg("w1", "aabb", PaymentDirection.OUTGOING, 100_500UL, fee = 500UL)
                .also { it.cryptoEventKind = "airdrop" },
            leg("w2", "aabb", PaymentDirection.INCOMING, 100_000UL),
        )
        assertEquals(listOf("w2"), detectSendToSelf(events).map { it.walletId })
    }

    @Test
    fun `regular is never overwritten`() {
        val events = listOf(
            leg("w1", "aabb", PaymentDirection.OUTGOING, 100_500UL, fee = 500UL)
                .also { it.cryptoEventKind = "regular" },
            leg("w2", "aabb", PaymentDirection.INCOMING, 100_000UL)
                .also { it.cryptoEventKind = SEND_TO_SELF },
        )
        assertTrue(detectSendToSelf(events).isEmpty())
    }

    @Test
    fun `lightning payments are ignored`() {
        val events = listOf(
            leg("w1", "hash", PaymentDirection.OUTGOING, 1_000UL, fee = 0UL, kind = PaymentKind.LIGHTNING),
            leg("w2", "hash", PaymentDirection.INCOMING, 1_000UL, kind = PaymentKind.LIGHTNING),
        )
        assertTrue(detectSendToSelf(events).isEmpty())
    }

    @Test
    fun `empty input returns empty`() {
        assertEquals(emptyList(), detectSendToSelf(emptyList()))
    }

    @Test
    fun `returned events are the same instances, not copies`() {
        val l1 = leg("w1", "aabb", PaymentDirection.OUTGOING, 100_500UL, fee = 500UL)
        val events = listOf(l1, leg("w2", "aabb", PaymentDirection.INCOMING, 100_000UL))
        detectSendToSelf(events).forEach { it.cryptoEventKind = SEND_TO_SELF }
        assertEquals(SEND_TO_SELF, l1.cryptoEventKind)
    }

    // ─── Alias dedup (H13) ──────────────────────────────────────────

    @Test
    fun `two rows of the same account collapse to one canonical id`() {
        val wallets = listOf(
            wallet("sign", WalletKindFfi.SIGNING, "xpubA", createdAt = 1),
            wallet("hd", WalletKindFfi.WATCH_HD, "wpkh(xpubA/0/*)", createdAt = 2),
            wallet("x", WalletKindFfi.WATCH_XPUB, "wpkh(xpubA/<0;1>/*)", createdAt = 3),
            wallet("other", WalletKindFfi.WATCH_XPUB, "wpkh(xpubB/0/*)", createdAt = 4),
        )
        val canonical = canonicalWalletIds(wallets) { w ->
            Regex("xpub[A-Z]").find(w.descriptorPub)?.value
        }
        assertEquals(mapOf("hd" to "hd", "x" to "hd", "other" to "other"), canonical)

        // Every tx of account A reported by both rows: after the alias
        // drop there is one wallet per txid → nothing is send_to_self.
        val events = listOf(
            leg("hd", "t1", PaymentDirection.OUTGOING, 10_500UL, fee = 500UL),
            leg("x", "t1", PaymentDirection.OUTGOING, 10_500UL, fee = 500UL),
            leg("hd", "t2", PaymentDirection.INCOMING, 7_000UL),
            leg("x", "t2", PaymentDirection.INCOMING, 7_000UL),
        )
        val deduped = dropAliasedEvents(events, canonical)
        assertEquals(setOf("hd"), deduped.map { it.walletId }.toSet())
        assertTrue(detectSendToSelf(deduped).isEmpty())
    }

    @Test
    fun `cleanup resets only certain artefacts`() {
        val wallets = listOf(
            wallet("sign", WalletKindFfi.SIGNING, "xpubA", createdAt = 1),
            wallet("hd", WalletKindFfi.WATCH_HD, "xpubA", createdAt = 2),
            wallet("x", WalletKindFfi.WATCH_XPUB, "xpubA", createdAt = 3),
            wallet("b", WalletKindFfi.WATCH_XPUB, "xpubB", createdAt = 4),
        )
        val canonical = canonicalWalletIds(wallets) { it.descriptorPub }
        val events = dropAliasedEvents(
            listOf(
                leg("hd", "single", PaymentDirection.OUTGOING, 1_500UL, fee = 500UL),
                leg("hd", "real", PaymentDirection.OUTGOING, 10_500UL, fee = 500UL),
                leg("b", "real", PaymentDirection.INCOMING, 10_000UL),
            ),
            canonical,
        )
        val rows = listOf(
            CryptoEventKindRow("sign", "single", SEND_TO_SELF), // signing artefact
            CryptoEventKindRow("x", "single", SEND_TO_SELF),    // alias artefact
            CryptoEventKindRow("hd", "single", SEND_TO_SELF),   // single real wallet
            CryptoEventKindRow("hd", "real", SEND_TO_SELF),     // genuine → keep
            CryptoEventKindRow("hd", "single2", "airdrop"),     // other kind → keep
            CryptoEventKindRow("gone", "single", SEND_TO_SELF), // unknown wallet → keep
            // user-pinned (schema v3) — never reset, even if it looks stale
            CryptoEventKindRow("sign", "pinned", SEND_TO_SELF, userSet = true),
        )
        val reset = staleSendToSelfTags(rows, wallets, canonical, events).toSet()
        assertEquals(
            setOf("sign" to "single", "x" to "single", "hd" to "single"),
            reset,
        )
    }

    // ─── Fixtures ───────────────────────────────────────────────────

    private fun leg(
        wallet: String,
        txid: String,
        direction: PaymentDirection,
        amount: ULong,
        fee: ULong? = null,
        kind: PaymentKind = PaymentKind.ON_CHAIN,
    ): PaymentEvent = PaymentEvent(
        walletId = wallet,
        txidOrPaymentId = txid,
        kind = kind,
        direction = direction,
        amountSat = amount,
        feeSat = fee,
        timestampUnix = now,
        blockHeight = null,
        category = null,
    )

    private fun wallet(
        id: String,
        kind: WalletKindFfi,
        descriptor: String,
        createdAt: Long,
    ): WalletRecordFfi = WalletRecordFfi(
        id = id,
        label = id,
        kind = kind,
        descriptorPub = descriptor,
        network = "bitcoin",
        createdAtUnix = createdAt,
        pinsWarned = false,
    )
}
