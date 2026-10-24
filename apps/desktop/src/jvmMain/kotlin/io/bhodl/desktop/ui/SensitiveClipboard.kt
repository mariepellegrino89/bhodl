package io.bhodl.desktop.ui

import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.ClipboardOwner
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.SystemFlavorMap
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.ByteArrayInputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Audit 2026-10 (clipboard history) — single copy path for wallet data
 * (addresses, BIP21 URIs, txids, xpub / descriptors).
 *
 * A plain `setText` on Windows 10/11 lands in the clipboard history
 * (Win+V) and, with sync enabled, in the cloud clipboard — the 60 s
 * auto-clear only empties the *current* slot, the history keeps the
 * value. Windows honours three registered clipboard formats that a
 * well-behaved app publishes next to the text:
 *  - `ExcludeClipboardContentFromMonitorProcessing` (any data: history,
 *    cloud sync and clipboard monitors skip the entry);
 *  - `CanIncludeInClipboardHistory` = DWORD 0;
 *  - `CanUploadToCloudClipboard` = DWORD 0.
 *
 * AWT can publish them: a custom [DataFlavor] is mapped to the native
 * format NAME through [SystemFlavorMap.addUnencodedNativeForFlavor];
 * `WDataTransferer` resolves unknown names with `RegisterClipboardFormat`
 * and `WClipboard.setContentsNative` publishes every format eagerly. The
 * flavors use an `InputStream` representation class so the bytes are
 * copied verbatim (no text / charset conversion). On other platforms only
 * the plain string flavor is offered.
 */
public object SensitiveClipboard {

    /** Registered Windows clipboard format names (see type doc). */
    public val WINDOWS_PRIVACY_FORMATS: List<String> = listOf(
        "ExcludeClipboardContentFromMonitorProcessing",
        "CanIncludeInClipboardHistory",
        "CanUploadToCloudClipboard",
    )

    /** Little-endian DWORD 0 — the "no" value for the two `Can…` formats. */
    internal val DWORD_ZERO: ByteArray = ByteArray(4)

    /** Default auto-clear delay for high-sensitivity values (xpub, descriptors). */
    public const val AUTO_CLEAR_DELAY_MS: Long = 60_000L

    internal val isWindows: Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    /** One custom flavor per Windows format, registered once in the default flavor map. */
    internal val privacyFlavors: List<DataFlavor> by lazy {
        val map = runCatching { SystemFlavorMap.getDefaultFlavorMap() as? SystemFlavorMap }.getOrNull()
        WINDOWS_PRIVACY_FORMATS.map { native ->
            val flavor = DataFlavor(
                "application/x-bhodl-${native.lowercase()}; class=java.io.InputStream",
                native,
            )
            map?.let {
                runCatching {
                    it.addUnencodedNativeForFlavor(flavor, native)
                    it.addFlavorForUnencodedNative(native, flavor)
                }
            }
            flavor
        }
    }

    /**
     * Text payload that, on Windows, also carries the privacy formats.
     * Doubles as [ClipboardOwner] (nothing to do on ownership loss).
     */
    public class PrivateText(
        public val text: String,
        windows: Boolean = isWindows,
    ) : Transferable, ClipboardOwner {

        private val flavors: Array<DataFlavor> =
            if (windows) arrayOf(DataFlavor.stringFlavor) + privacyFlavors
            else arrayOf(DataFlavor.stringFlavor)

        override fun getTransferDataFlavors(): Array<DataFlavor> = flavors.copyOf()

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavors.any { it == flavor }

        override fun getTransferData(flavor: DataFlavor): Any = when {
            flavor == DataFlavor.stringFlavor -> text
            flavor in flavors -> ByteArrayInputStream(DWORD_ZERO)
            else -> throw UnsupportedFlavorException(flavor)
        }

        override fun lostOwnership(clipboard: Clipboard?, contents: Transferable?) {}
    }

    /** System clipboard, or `null` when headless / unavailable. */
    private fun system(): Clipboard? =
        runCatching { Toolkit.getDefaultToolkit().systemClipboard }.getOrNull()

    /**
     * Copy [value] (best-effort; silently no-op when no clipboard is
     * available). With [autoClearMs] the clipboard is emptied after that
     * delay **only if** it still holds exactly [value], so anything the
     * user copied in the meantime is never clobbered. The timer runs on
     * the app-lifetime [io.bhodl.desktop.AppScope] so it survives
     * navigation.
     */
    public fun copy(value: String, autoClearMs: Long? = null, clipboard: Clipboard? = system()) {
        val cb = clipboard ?: return
        val payload = PrivateText(value)
        runCatching { cb.setContents(payload, payload) }
        if (autoClearMs == null) return
        io.bhodl.desktop.AppScope.io.launch {
            delay(autoClearMs)
            clearIfStill(value, cb)
        }
    }

    /** Empty [clipboard] iff it still holds [value]. Returns `true` when cleared. */
    public fun clearIfStill(value: String, clipboard: Clipboard? = system()): Boolean {
        val cb = clipboard ?: return false
        return runCatching {
            val current = cb.getData(DataFlavor.stringFlavor) as? String
            if (current == value) {
                val empty = PrivateText("")
                cb.setContents(empty, empty)
                true
            } else {
                false
            }
        }.getOrDefault(false)
    }
}
