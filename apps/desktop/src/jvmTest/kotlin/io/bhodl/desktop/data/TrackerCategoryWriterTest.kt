package io.bhodl.desktop.data

import io.bhodl.ffi.WalletKindFfi
import io.bhodl.ffi.WalletRecordFfi
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Security review 2026-10 — a category set from the signing wallet must
 * land under the watch-only rows the tracker reads, not under the
 * signing wallet's id (which `listWatchEvents` never lists).
 */
class TrackerCategoryWriterTest {

    private fun row(id: String, kind: WalletKindFfi, desc: String) =
        WalletRecordFfi(id, "w-$id", kind, desc, "signet", 0L, false)

    private val xpubs = mapOf(
        "desc-signing" to "xpubA",
        "desc-mirror" to "xpubA",
        "desc-manual" to "xpubA",
        "desc-other" to "xpubB",
    )

    @Test
    fun `category goes to every watch row of the same account, never the signing id`() {
        val rows = listOf(
            row("signing", WalletKindFfi.SIGNING, "desc-signing"),
            row("mirror", WalletKindFfi.WATCH_XPUB, "desc-mirror"),
            row("manual", WalletKindFfi.WATCH_HD, "desc-manual"),
            row("other", WalletKindFfi.WATCH_XPUB, "desc-other"),
        )
        assertEquals(
            listOf("mirror", "manual"),
            trackerWalletIdsFor(rows, "signing", "xpubA") { xpubs[it] },
        )
    }

    @Test
    fun `an untracked signing wallet keeps the category under its own id`() {
        val rows = listOf(
            row("signing", WalletKindFfi.SIGNING, "desc-signing"),
            row("other", WalletKindFfi.WATCH_XPUB, "desc-other"),
        )
        assertEquals(listOf("signing"), trackerWalletIdsFor(rows, "signing", "xpubA") { xpubs[it] })
        assertEquals(listOf("signing"), trackerWalletIdsFor(rows, "signing", null) { xpubs[it] })
    }
}
