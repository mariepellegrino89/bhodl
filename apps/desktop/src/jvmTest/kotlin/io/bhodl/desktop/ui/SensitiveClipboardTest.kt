package io.bhodl.desktop.ui

import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.SystemFlavorMap
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Test-book §54.7–54.8 / audit 2026-10 — copies of wallet data carry the
 * Windows "exclude from clipboard history / cloud clipboard" formats and
 * the xpub auto-clear only clears its own value. Uses a private AWT
 * [Clipboard] (no system clipboard / display needed); the real Win+V
 * history check stays manual.
 */
class SensitiveClipboardTest {

    @Test
    fun `on Windows the payload offers text plus the three privacy formats`() {
        val t = SensitiveClipboard.PrivateText("bc1qexample", windows = true)
        val flavors = t.transferDataFlavors
        assertEquals(DataFlavor.stringFlavor, flavors.first())
        assertEquals(1 + SensitiveClipboard.WINDOWS_PRIVACY_FORMATS.size, flavors.size)
        assertEquals("bc1qexample", t.getTransferData(DataFlavor.stringFlavor))
        for (f in flavors.drop(1)) {
            assertTrue(f.isRepresentationClassInputStream)
            val bytes = (t.getTransferData(f) as InputStream).readBytes()
            assertContentEquals(ByteArray(4), bytes, "DWORD 0 for ${f.humanPresentableName}")
        }
        assertEquals(
            SensitiveClipboard.WINDOWS_PRIVACY_FORMATS,
            flavors.drop(1).map { it.humanPresentableName },
        )
    }

    @Test
    fun `privacy formats are mapped to their native clipboard format names`() {
        val map = SystemFlavorMap.getDefaultFlavorMap() as SystemFlavorMap
        SensitiveClipboard.privacyFlavors.zip(SensitiveClipboard.WINDOWS_PRIVACY_FORMATS).forEach { (flavor, native) ->
            assertTrue(native in map.getNativesForFlavor(flavor), "$native not mapped")
        }
    }

    @Test
    fun `elsewhere only plain text is offered`() {
        val t = SensitiveClipboard.PrivateText("txid", windows = false)
        assertEquals(listOf(DataFlavor.stringFlavor), t.transferDataFlavors.toList())
        assertFailsWith<UnsupportedFlavorException> {
            t.getTransferData(SensitiveClipboard.privacyFlavors.first())
        }
    }

    @Test
    fun `copy publishes the text and clearIfStill only clears its own value`() {
        val cb = Clipboard("test")
        SensitiveClipboard.copy("xpub-1", clipboard = cb)
        assertEquals("xpub-1", cb.getData(DataFlavor.stringFlavor))
        assertTrue(cb.getContents(null) is SensitiveClipboard.PrivateText)

        // The user copied something else meanwhile: never clobbered.
        SensitiveClipboard.copy("something else", clipboard = cb)
        assertFalse(SensitiveClipboard.clearIfStill("xpub-1", cb))
        assertEquals("something else", cb.getData(DataFlavor.stringFlavor))

        assertTrue(SensitiveClipboard.clearIfStill("something else", cb))
        assertEquals("", cb.getData(DataFlavor.stringFlavor))
    }

    @Test
    fun `auto clear empties the clipboard after the delay`() {
        val cb = Clipboard("test")
        SensitiveClipboard.copy("xpub-2", autoClearMs = 50, clipboard = cb)
        val deadline = System.currentTimeMillis() + 3_000
        while (cb.getData(DataFlavor.stringFlavor) != "" && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertEquals("", cb.getData(DataFlavor.stringFlavor))
    }
}
