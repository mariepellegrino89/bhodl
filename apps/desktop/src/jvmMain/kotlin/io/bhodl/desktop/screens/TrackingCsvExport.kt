package io.bhodl.desktop.screens
import io.bhodl.desktop.domain.applyRules
import io.bhodl.desktop.domain.RecurringCandidate
import io.bhodl.desktop.domain.dayOfMonth
import io.bhodl.desktop.domain.manualAssetsByKindSat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.data.TrackerCache
import io.bhodl.desktop.navigation.TrackerSection
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.ui.bhodlCard
import io.bhodl.desktop.ui.displayAmount
import io.bhodl.desktop.wizard.RegistryStore
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.ffi.WalletKindFfi
import io.bhodl.ffi.WalletRecordFfi
import io.bhodl.ui.theme.BhodlTheme
import io.bhodl.shared.resources.privacy_export_body
import io.bhodl.shared.resources.privacy_export_title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.trk_csv_dialog_title
import io.bhodl.shared.resources.trk_csv_filter
import io.bhodl.shared.resources.trk_csv_overwrite_body
import io.bhodl.shared.resources.trk_csv_overwrite_title
import io.bhodl.shared.resources.trk_csv_save_failed
import org.jetbrains.compose.resources.getString

/**
 * Save the current event stream as a CSV file via a native Swing
 * file chooser. Returns `null` on success / cancel, or an error message
 * string on failure (which the caller surfaces in `lastError`).
 *
 * Columns: timestamp_iso, block_height, wallet_label, direction,
 * amount_sat, fee_sat, txid, kind, category, crypto_event_kind.
 *
 * Phase 3.4 — `category` column is sourced from `PaymentEvent.category`
 * (populated by the registry's `tracking_categories` table). Empty
 * string when no category is set.
 *
 * Hardening (audit M1):
 *  - the target always gets a `.csv` extension;
 *  - an existing file is only overwritten after explicit confirmation;
 *  - text cells are neutralised against spreadsheet formula injection
 *    and RFC-4180 quoted ([csvTextCell]);
 *  - the file is written on [Dispatchers.IO] to a temp sibling and
 *    atomically moved into place, so a crash never leaves a truncated
 *    export over the user's previous file.
 *
 * The chooser / confirm dialogs run on the calling (UI) thread.
 */
internal suspend fun exportEventsAsCsv(
    events: List<io.bhodl.ffi.PaymentEvent>,
    labelByWalletId: Map<String, String>,
): String? {
    if (events.isEmpty()) return null
    val dialogTitleText = getString(Res.string.trk_csv_dialog_title)
    val filterText = getString(Res.string.trk_csv_filter)
    val confirmTitle = getString(Res.string.trk_csv_overwrite_title)
    // UX review 2026-10 (item 13) — the CSV always carries real amounts
    // (a masked export would be useless); with the privacy mode on, ask
    // first so a shared screen / screen recording doesn't get a file
    // dialog full of numbers by surprise.
    if (!confirmExportWhilePrivate()) return null
    val chooser = javax.swing.JFileChooser().apply {
        dialogTitle = dialogTitleText
        selectedFile = java.io.File("bhodl-tracking.csv")
        fileFilter = javax.swing.filechooser.FileNameExtensionFilter(
            filterText,
            "csv",
        )
    }
    val result = chooser.showSaveDialog(null)
    if (result != javax.swing.JFileChooser.APPROVE_OPTION) return null
    val chosen = chooser.selectedFile ?: return null
    val target = withCsvExtension(chosen)
    if (target.exists()) {
        val answer = javax.swing.JOptionPane.showConfirmDialog(
            null,
            getString(Res.string.trk_csv_overwrite_body, target.name),
            confirmTitle,
            javax.swing.JOptionPane.YES_NO_OPTION,
            javax.swing.JOptionPane.WARNING_MESSAGE,
        )
        if (answer != javax.swing.JOptionPane.YES_OPTION) return null
    }
    val content = buildEventsCsv(events, labelByWalletId)
    return withContext(Dispatchers.IO) {
        runCatching {
            writeFileAtomically(target, content)
            null
        }.getOrElse { e ->
            getString(Res.string.trk_csv_save_failed, e.message ?: e.javaClass.simpleName)
        }
    }
}

/**
 * `true` when the export may proceed: privacy mode off, or the user
 * confirmed. [ask] is the test seam for the Swing dialog.
 */
internal suspend fun confirmExportWhilePrivate(
    privacyOn: Boolean = io.bhodl.desktop.ui.money.AmountMask.active,
    ask: suspend () -> Boolean = {
        val title = org.jetbrains.compose.resources.getString(
            io.bhodl.shared.resources.Res.string.privacy_export_title,
        )
        val body = org.jetbrains.compose.resources.getString(
            io.bhodl.shared.resources.Res.string.privacy_export_body,
        )
        javax.swing.JOptionPane.showConfirmDialog(
            null, body, title,
            javax.swing.JOptionPane.YES_NO_OPTION,
            javax.swing.JOptionPane.WARNING_MESSAGE,
        ) == javax.swing.JOptionPane.YES_OPTION
    },
): Boolean = !privacyOn || ask()

/** Append `.csv` unless the chosen name already ends with it (any case). */
internal fun withCsvExtension(file: java.io.File): java.io.File =
    if (file.name.lowercase().endsWith(".csv")) file
    else java.io.File(file.parentFile, file.name + ".csv")

/**
 * Write [content] to a temp file next to [target], then move it over
 * [target] (atomically when the filesystem supports it).
 */
internal fun writeFileAtomically(target: java.io.File, content: String) {
    val dir = target.absoluteFile.parentFile ?: java.io.File(".")
    val tmp = java.io.File.createTempFile(".${target.name}.", ".tmp", dir)
    try {
        tmp.writeText(content, Charsets.UTF_8)
        try {
            java.nio.file.Files.move(
                tmp.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(
                tmp.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }
    } finally {
        if (tmp.exists()) tmp.delete()
    }
}

/** Pure CSV body (header + one row per event). Exposed for tests. */
internal fun buildEventsCsv(
    events: List<io.bhodl.ffi.PaymentEvent>,
    labelByWalletId: Map<String, String>,
): String {
    val header = listOf(
        "timestamp_iso",
        "block_height",
        "wallet_label",
        "direction",
        "amount_sat",
        "fee_sat",
        "txid",
        "kind",
        "category",
        "crypto_event_kind",
    ).joinToString(",")
    val rows = events.map { e ->
        val ts = e.timestampUnix?.let {
            java.time.format.DateTimeFormatter.ISO_INSTANT
                .format(java.time.Instant.ofEpochSecond(it.toLong()))
        } ?: ""
        val wallet = labelByWalletId[e.walletId] ?: "(unknown)"
        val direction = when (e.direction) {
            io.bhodl.ffi.PaymentDirection.INCOMING -> "incoming"
            io.bhodl.ffi.PaymentDirection.OUTGOING -> "outgoing"
            io.bhodl.ffi.PaymentDirection.SELF_TRANSFER -> "self_transfer"
        }
        val kind = when (e.kind) {
            io.bhodl.ffi.PaymentKind.ON_CHAIN -> "on_chain"
            io.bhodl.ffi.PaymentKind.LIGHTNING -> "lightning"
        }
        listOf(
            // Generated, purely numeric / ISO / enum cells: no escaping
            // needed (amounts are unsigned, so never start with '-').
            ts,
            e.blockHeight?.toString() ?: "",
            csvTextCell(wallet),
            direction,
            e.amountSat.toString(),
            e.feeSat?.toString() ?: "",
            csvTextCell(e.txidOrPaymentId),
            kind,
            csvTextCell(e.category.orEmpty()),
            csvTextCell(e.cryptoEventKind ?: "regular"),
        ).joinToString(",")
    }
    return (listOf(header) + rows).joinToString("\n")
}

/** Leading characters spreadsheet apps interpret as a formula / command. */
private val FORMULA_TRIGGERS: Set<Char> = setOf('=', '+', '-', '@', '\t', '\r')

/**
 * Encode a user-controlled TEXT cell:
 *  1. formula-injection guard (OWASP CSV injection): a value starting
 *     with `=`, `+`, `-`, `@`, TAB or CR gets a leading `'` so Excel /
 *     LibreOffice / Sheets show it as text instead of evaluating it;
 *  2. RFC-4180 quoting when the value contains `,`, `"`, LF or CR.
 */
internal fun csvTextCell(raw: String): String {
    val neutral = if (raw.isNotEmpty() && raw[0] in FORMULA_TRIGGERS) "'$raw" else raw
    return if (neutral.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"" + neutral.replace("\"", "\"\"") + "\""
    } else {
        neutral
    }
}
