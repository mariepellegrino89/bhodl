package io.bhodl.desktop.screens

import io.bhodl.ffi.AddressRecord
import io.bhodl.ffi.TransactionRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure rules behind the wallet's Transactions tab (UX review 2026-10,
 * §4 item 8): state mapping, replacement grouping, sort / filter, and
 * the label-first receive helpers.
 */
class WalletTxModelTest {

    private fun tx(
        id: String,
        net: Long = -1_000,
        height: UInt? = null,
        ts: ULong? = 1_700_000_000UL,
        fee: ULong? = 150UL,
    ) = TransactionRecord(
        txid = id.padEnd(64, '0'),
        netSat = net,
        feeSat = fee,
        confirmationHeight = height,
        timestamp = ts,
    )

    // ── states ──────────────────────────────────────────────────────────

    @Test
    fun `unconfirmed is Pending with zero confirmations`() {
        assertEquals(TxStatus.Pending, txStatusOf(null, 900u))
        assertEquals(0, confirmationsOf(null, 900u))
    }

    @Test
    fun `mined in the tip block counts one confirmation and is Confirming`() {
        assertEquals(1, confirmationsOf(900u, 900u))
        assertEquals(TxStatus.Confirming(1), txStatusOf(900u, 900u))
    }

    @Test
    fun `five confirmations still confirming, six is final with no badge`() {
        assertEquals(TxStatus.Confirming(5), txStatusOf(896u, 900u))
        assertEquals(TxStatus.Confirmed, txStatusOf(895u, 900u))
        assertEquals(TxStatus.Confirmed, txStatusOf(1u, 900u))
    }

    @Test
    fun `unknown or stale tip never yields a negative count nor a final state`() {
        assertNull(confirmationsOf(900u, null))
        assertNull(confirmationsOf(900u, 0u))
        assertNull(confirmationsOf(905u, 900u))
        // Mined but the count is unknown: "Confirming", never "Confirmed".
        assertEquals(TxStatus.Confirming(null), txStatusOf(905u, 900u))
        assertEquals(TxStatus.Confirming(null), txStatusOf(900u, null))
        assertEquals(TxStatus.Confirming(null), txStatusOf(900u, 0u))
    }

    @Test
    fun `speed up is RBF for outgoing pending, CPFP for incoming pending, none once mined`() {
        assertEquals(SpeedUpKind.Rbf, speedUpKindOf(tx("a", net = -5_000)))
        // Esplora fills feeSat on incoming txs too: never key on it.
        assertEquals(SpeedUpKind.CpfpIncoming, speedUpKindOf(tx("b", net = 5_000, fee = 900UL)))
        assertNull(speedUpKindOf(tx("c", net = -5_000, height = 10u)))
        assertNull(speedUpKindOf(tx("d", net = 0)))
    }

    // ── replacement grouping ───────────────────────────────────────────

    @Test
    fun `a replaced tx is grouped under its replacement`() {
        val original = tx("a1")
        val replacement = tx("b2")
        val other = tx("c3")
        val groups = groupReplacements(
            txs = listOf(original, replacement, other),
            replacedBy = mapOf(original.txid to replacement.txid),
        )
        assertEquals(listOf(replacement.txid, other.txid), groups.map { it.tx.txid })
        val bumped = groups.first()
        assertTrue(bumped.feeBumped)
        assertEquals(listOf(original.txid), bumped.replaced.map { it.txid })
        assertFalse(groups[1].feeBumped)
    }

    @Test
    fun `a chain of bumps collapses under the final listed replacement`() {
        val a = tx("aa", ts = 1UL)
        val b = tx("bb", ts = 2UL)
        val c = tx("cc", ts = 3UL)
        val groups = groupReplacements(
            txs = listOf(a, b, c),
            replacedBy = mapOf(a.txid to b.txid, b.txid to c.txid),
        )
        assertEquals(listOf(c.txid), groups.map { it.tx.txid })
        assertEquals(listOf(a.txid, b.txid), groups.single().replaced.map { it.txid })
    }

    @Test
    fun `an original BDK no longer lists comes from the bump-log snapshot`() {
        val snapshot = tx("dead")
        val replacement = tx("beef")
        val groups = groupReplacements(
            txs = listOf(replacement),
            replacedBy = mapOf(snapshot.txid to replacement.txid),
            snapshots = mapOf(snapshot.txid to snapshot),
        )
        assertEquals(listOf(snapshot.txid), groups.single().replaced.map { it.txid })
    }

    @Test
    fun `originals stay ordinary rows when the replacement itself is gone`() {
        val original = tx("a1")
        val groups = groupReplacements(
            txs = listOf(original),
            replacedBy = mapOf(original.txid to "f".repeat(64)),
        )
        assertEquals(listOf(original.txid), groups.map { it.tx.txid })
        assertFalse(groups.single().feeBumped)
    }

    @Test
    fun `a replacement cycle does not loop forever`() {
        val a = tx("aa")
        val b = tx("bb")
        val groups = groupReplacements(listOf(a, b), mapOf(a.txid to b.txid, b.txid to a.txid))
        // Each is "replaced" by the other: neither can be top level, but
        // the walk terminates.
        assertTrue(groups.size <= 2)
    }

    // ── sort / filter ──────────────────────────────────────────────────

    @Test
    fun `default date sort puts pending first, newest next`() {
        val old = tx("01", height = 10u, ts = 100UL)
        val new = tx("02", height = 20u, ts = 200UL)
        val pending = tx("03", ts = null)
        val sorted = sortTxGroups(listOf(old, new, pending).map { TxGroup(it) }, TxSortColumn.DATE, ascending = false)
        assertEquals(listOf(pending.txid, new.txid, old.txid), sorted.map { it.tx.txid })
    }

    @Test
    fun `amount sort is by signed net, and confirmations by depth`() {
        val out = tx("01", net = -9_000, height = 10u)
        val inSmall = tx("02", net = 1_000, height = 15u)
        val inBig = tx("03", net = 50_000, height = null)
        val groups = listOf(out, inSmall, inBig).map { TxGroup(it) }
        assertEquals(
            listOf(inBig.txid, inSmall.txid, out.txid),
            sortTxGroups(groups, TxSortColumn.AMOUNT, ascending = false).map { it.tx.txid },
        )
        assertEquals(
            listOf(inBig.txid, inSmall.txid, out.txid),
            sortTxGroups(groups, TxSortColumn.CONFIRMATIONS, ascending = true, tipHeight = 20u).map { it.tx.txid },
        )
    }

    @Test
    fun `label sort puts labelled rows first, alphabetically`() {
        val a = tx("01")
        val b = tx("02")
        val c = tx("03")
        val labels = mapOf(a.txid to "zeta", c.txid to "Alpha")
        val sorted = sortTxGroups(
            listOf(a, b, c).map { TxGroup(it) },
            TxSortColumn.LABEL,
            ascending = true,
            labelOf = { labels[it].orEmpty() },
        )
        assertEquals(listOf(c.txid, a.txid, b.txid), sorted.map { it.tx.txid })
    }

    @Test
    fun `filters by direction, pending and free text over label category and txid`() {
        val incoming = tx("aa", net = 10_000, height = 5u)
        val outgoing = tx("bb", net = -10_000, height = 6u)
        val pending = tx("cc", net = -1_000)
        val groups = listOf(incoming, outgoing, pending).map { TxGroup(it) }
        val labels = mapOf(incoming.txid to "Salary October")
        val categories = mapOf(outgoing.txid to "Groceries")
        fun ids(f: TxFilter, q: String = "") =
            filterTxGroups(groups, f, q, { labels[it].orEmpty() }, { categories[it] }).map { it.tx.txid }
        assertEquals(listOf(incoming.txid), ids(TxFilter.INCOMING))
        assertEquals(listOf(outgoing.txid, pending.txid), ids(TxFilter.OUTGOING))
        assertEquals(listOf(pending.txid), ids(TxFilter.PENDING))
        assertEquals(listOf(incoming.txid), ids(TxFilter.ALL, "salary"))
        assertEquals(listOf(outgoing.txid), ids(TxFilter.ALL, "GROCER"))
        assertEquals(listOf(pending.txid), ids(TxFilter.ALL, "cc"))
    }

    // ── label-first receive ─────────────────────────────────────────────

    private fun addr(a: String, used: Boolean, label: String? = null, category: String? = null) =
        AddressRecord(index = 0u, address = a, isChange = false, used = used, label = label, category = category)

    @Test
    fun `reuse is flagged only for an address that already received funds`() {
        val list = listOf(addr("tb1used", used = true), addr("tb1fresh", used = false))
        assertTrue(isReusedAddress("tb1used", list))
        assertFalse(isReusedAddress("tb1fresh", list))
        assertFalse(isReusedAddress("tb1unknown", list))
    }
}
