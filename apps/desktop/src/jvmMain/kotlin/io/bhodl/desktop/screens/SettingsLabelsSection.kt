package io.bhodl.desktop.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.settings.EsploraEndpoint
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.ui.BhodlTopBar
import io.bhodl.desktop.settings.ThemeMode
import io.bhodl.desktop.wizard.WalletPaths
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.desktop.ui.userMessage
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import io.bhodl.shared.resources.Res
import io.bhodl.desktop.prices.FiatCurrency
import io.bhodl.desktop.prices.PriceCache
import io.bhodl.desktop.ui.money.UnitFormat
import io.bhodl.shared.BtcUnit
import io.bhodl.ui.theme.BhodlTheme
import io.bhodl.ui.theme.ColorConvention
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import io.bhodl.shared.resources.set_backup_save_failed
import io.bhodl.shared.resources.set_labels_export
import io.bhodl.shared.resources.set_labels_export_dialog
import io.bhodl.shared.resources.set_labels_export_failed
import io.bhodl.shared.resources.set_labels_exported
import io.bhodl.shared.resources.set_labels_filter_jsonl
import io.bhodl.shared.resources.set_labels_filter_jsonl_txt
import io.bhodl.shared.resources.set_labels_hint
import io.bhodl.shared.resources.set_labels_import
import io.bhodl.shared.resources.set_labels_import_dialog
import io.bhodl.shared.resources.set_labels_import_failed
import io.bhodl.shared.resources.set_labels_import_failed_note
import io.bhodl.shared.resources.set_labels_imported
import io.bhodl.shared.resources.set_labels_load_failed
import io.bhodl.shared.resources.set_labels_title
import io.bhodl.shared.resources.set_labels_working
import io.bhodl.shared.resources.set_wallet_not_unlocked

/**
 * BIP-329 labels round-trip.
 *
 * Two buttons, both file-dialog driven (no clipboard / blob field —
 * labels can pile up to thousands of records and a multi-line text
 * area would be a footgun):
 * - **Export labels…** dumps every wallet label as a JSONL file
 *   (the BIP-329 portable format). Sparrow / Liana / any other
 *   compliant wallet can ingest the result.
 * - **Import labels…** reads a JSONL file and upserts each record
 *   on `(kind, target)` so re-importing the same file is idempotent.
 *
 * Status line below the buttons surfaces the count of records on
 * success and the FFI error message on failure.
 */
/**
 * `internal` so the unit test in the same module can mount the
 * section directly with stubbed file-dialog lambdas — JFileChooser
 * would block the headless test thread.
 */
@Composable
internal fun LabelsSection(
    // Test seams: production callers omit and inherit the JFileChooser-
    // backed defaults. Tests pass canned lambdas so the dialog never
    // blocks the headless test thread.
    saveJsonl: (String) -> FileSaveResult? = ::saveLabelsJsonlToFile,
    loadJsonl: () -> FileLoadResult? = ::loadLabelsJsonlFromFile,
) {
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var status: String? by remember { mutableStateOf(null) }
    var error: String? by remember { mutableStateOf(null) }
    val msgNotUnlocked = stringResource(Res.string.set_wallet_not_unlocked)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.set_labels_title),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textSecondary,
        )
        Text(
            text = stringResource(Res.string.set_labels_hint),
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textTertiary,
        )
        OutlinedButton(
            onClick = {
                if (working) return@OutlinedButton
                val wallet = WalletStore.current()
                if (wallet == null) {
                    error = msgNotUnlocked
                    return@OutlinedButton
                }
                working = true
                error = null
                status = null
                scope.launch {
                    val outcome = runCatching {
                        withContext(Dispatchers.IO) { wallet.exportLabelsJsonl() }
                    }
                    outcome.fold(
                        onSuccess = { jsonl ->
                            val saved = saveJsonl(jsonl)
                            if (saved == null) {
                                // User cancelled the dialog — leave UI as-is.
                            } else if (saved.error != null) {
                                error = saved.error
                            } else {
                                val count = jsonl.count { it == '\n' } +
                                    if (jsonl.isBlank()) 0 else 1
                                status = getString(Res.string.set_labels_exported, count, saved.path ?: "")
                                // Item 14 — Security Hub: date only.
                                WalletStore.activeWalletId()?.let { SettingsStore.markLabelsExported(it) }
                            }
                        },
                        onFailure = { e ->
                            error = e.userMessage(getString(Res.string.set_labels_export_failed))
                        },
                    )
                    working = false
                }
            },
            enabled = !working,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SettingsTags.LABELS_EXPORT),
        ) { Text(if (working) stringResource(Res.string.set_labels_working) else stringResource(Res.string.set_labels_export)) }

        OutlinedButton(
            onClick = {
                if (working) return@OutlinedButton
                val wallet = WalletStore.current()
                if (wallet == null) {
                    error = msgNotUnlocked
                    return@OutlinedButton
                }
                val loaded = loadJsonl() ?: return@OutlinedButton
                if (loaded.error != null) {
                    error = loaded.error
                    return@OutlinedButton
                }
                val jsonl = loaded.content ?: ""
                working = true
                error = null
                status = null
                scope.launch {
                    val outcome = runCatching {
                        withContext(Dispatchers.IO) { wallet.importLabelsJsonl(jsonl) }
                    }
                    outcome.fold(
                        onSuccess = { count ->
                            status = getString(Res.string.set_labels_imported, count.toString())
                        },
                        onFailure = { e ->
                            // The Rust import is one transaction: a bad
                            // line rolls back every record of the file.
                            error = getString(Res.string.set_labels_import_failed_note, e.userMessage(getString(Res.string.set_labels_import_failed)))
                        },
                    )
                    working = false
                }
            },
            enabled = !working,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SettingsTags.LABELS_IMPORT),
        ) { Text(if (working) stringResource(Res.string.set_labels_working) else stringResource(Res.string.set_labels_import)) }

        if (status != null) {
            Text(
                text = status!!,
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textSecondary,
                modifier = Modifier.testTag(SettingsTags.LABELS_STATUS),
            )
        }
        if (error != null) {
            Text(
                text = error!!,
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textSecondary,
                modifier = Modifier.testTag(SettingsTags.LABELS_ERROR),
            )
        }
    }
}

internal data class FileSaveResult(val path: String?, val error: String?)
internal data class FileLoadResult(val content: String?, val error: String?)

/**
 * Native "Save as" dialog for the BIP-329 JSONL export. Returns
 * `null` if the user cancelled. On approval, returns either a
 * `path` (success) or an `error` string (write failure).
 */
private fun saveLabelsJsonlToFile(jsonl: String): FileSaveResult? {
    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val chooser = JFileChooser().apply {
        dialogTitle = resText(Res.string.set_labels_export_dialog)
        selectedFile = File("bhodl-labels-$timestamp.jsonl")
        fileFilter = FileNameExtensionFilter(resText(Res.string.set_labels_filter_jsonl), "jsonl")
    }
    val result = chooser.showSaveDialog(null)
    if (result != JFileChooser.APPROVE_OPTION) return null
    val target = chooser.selectedFile ?: return null
    return runCatching {
        target.writeText(jsonl, Charsets.UTF_8)
        FileSaveResult(path = target.absolutePath, error = null)
    }.getOrElse { e ->
        FileSaveResult(path = null, error = resText(Res.string.set_backup_save_failed, e.message ?: e.javaClass.simpleName))
    }
}

/**
 * Native "Open" dialog for the BIP-329 JSONL import. Returns `null`
 * if the user cancelled. Otherwise either `content` (success) or
 * `error` (read failure) is non-null.
 */
/** Upper bound for a BIP-329 JSONL import (50 MiB). */
private const val MAX_LABELS_IMPORT_BYTES: Int = 50 * 1024 * 1024

private fun loadLabelsJsonlFromFile(): FileLoadResult? {
    val chooser = JFileChooser().apply {
        dialogTitle = resText(Res.string.set_labels_import_dialog)
        fileFilter = FileNameExtensionFilter(resText(Res.string.set_labels_filter_jsonl_txt), "jsonl", "txt")
    }
    val result = chooser.showOpenDialog(null)
    if (result != JFileChooser.APPROVE_OPTION) return null
    val source = chooser.selectedFile ?: return null
    return runCatching {
        // Cap the read: an arbitrary user-picked file must not be able
        // to exhaust the heap.
        FileLoadResult(
            content = io.bhodl.desktop.data.readTextCapped(source, MAX_LABELS_IMPORT_BYTES),
            error = null,
        )
    }.getOrElse { e ->
        FileLoadResult(content = null, error = resText(Res.string.set_labels_load_failed, e.message ?: e.javaClass.simpleName))
    }
}

/**
 * Localized text for the Swing file dialogs above, which run outside
 * composition (blocking read of the compose resource; the modal dialog
 * blocks the UI thread anyway).
 */
private fun resText(res: org.jetbrains.compose.resources.StringResource, vararg args: Any): String =
    kotlinx.coroutines.runBlocking { getString(res, *args) }
