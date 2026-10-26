package io.bhodl.desktop.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.data.TestMode
import io.bhodl.desktop.data.TestModeException
import io.bhodl.desktop.wizard.localizedText
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.tm_active_intro
import io.bhodl.shared.resources.tm_band
import io.bhodl.shared.resources.tm_band_exit
import io.bhodl.shared.resources.tm_cancel
import io.bhodl.shared.resources.tm_confirm
import io.bhodl.shared.resources.tm_done
import io.bhodl.shared.resources.tm_enter
import io.bhodl.shared.resources.tm_err_bad_file
import io.bhodl.shared.resources.tm_err_generic
import io.bhodl.shared.resources.tm_err_newer
import io.bhodl.shared.resources.tm_err_no_initial
import io.bhodl.shared.resources.tm_err_not_test
import io.bhodl.shared.resources.tm_err_too_large
import io.bhodl.shared.resources.tm_exit
import io.bhodl.shared.resources.tm_export
import io.bhodl.shared.resources.tm_export_dialog
import io.bhodl.shared.resources.tm_export_first
import io.bhodl.shared.resources.tm_exported
import io.bhodl.shared.resources.tm_file_filter
import io.bhodl.shared.resources.tm_import
import io.bhodl.shared.resources.tm_import_body
import io.bhodl.shared.resources.tm_import_dialog
import io.bhodl.shared.resources.tm_import_keep_dates
import io.bhodl.shared.resources.tm_import_title
import io.bhodl.shared.resources.tm_initial_state
import io.bhodl.shared.resources.tm_intro
import io.bhodl.shared.resources.tm_load_project
import io.bhodl.shared.resources.tm_mark_body
import io.bhodl.shared.resources.tm_mark_initial
import io.bhodl.shared.resources.tm_mark_title
import io.bhodl.shared.resources.tm_no_initial_state
import io.bhodl.shared.resources.tm_project_body
import io.bhodl.shared.resources.tm_project_title
import io.bhodl.shared.resources.tm_restore_body
import io.bhodl.shared.resources.tm_restore_initial
import io.bhodl.shared.resources.tm_restore_title
import io.bhodl.shared.resources.tm_working
import io.bhodl.ui.theme.BhodlTheme
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/** Test tags of the test-mode section, band and dialogs. */
public object TestModeTags {
    public const val ENTER: String = "test-mode.enter"
    public const val EXPORT: String = "test-mode.export"
    public const val IMPORT: String = "test-mode.import"
    public const val MARK_INITIAL: String = "test-mode.mark-initial"
    public const val RESTORE_INITIAL: String = "test-mode.restore-initial"
    public const val LOAD_PROJECT: String = "test-mode.load-project"
    public const val EXIT: String = "test-mode.exit"
    public const val STATUS: String = "test-mode.status"
    public const val INITIAL_STATE: String = "test-mode.initial-state"
    public const val CONFIRM: String = "test-mode.confirm"
    public const val CANCEL: String = "test-mode.cancel"
    public const val KEEP_DATES: String = "test-mode.keep-dates"
    public const val EXPORT_FIRST: String = "test-mode.export-first"
    public const val BAND: String = "test-mode.band"
    public const val BAND_EXIT: String = "test-mode.band-exit"
}

private enum class Pending { IMPORT, MARK, RESTORE, PROJECT }

/**
 * The section's state outlives the section: a command reopens the test
 * profile and the caller rebuilds the screens ([TestModeSection]'s
 * `onReloaded`), and the outcome — or a dialog left open by "export
 * mine first" — must still be there.
 */
private object TestModeUi {
    val working = mutableStateOf(false)
    val status = mutableStateOf<String?>(null)
    val statusIsError = mutableStateOf(false)
    val pending = mutableStateOf<Pending?>(null)
    val importFile = mutableStateOf<File?>(null)
    val keepDates = mutableStateOf(false)
}

/** Test seam: forget the section's last outcome and open dialog. */
internal fun resetTestModeUiForTest() {
    TestModeUi.working.value = false
    TestModeUi.status.value = null
    TestModeUi.statusIsError.value = false
    TestModeUi.pending.value = null
    TestModeUi.importFile.value = null
    TestModeUi.keepDates.value = false
}

/**
 * Settings › Test mode. Outside test mode: what it is and "Enter".
 * Inside: export, import, initial state, project data, exit.
 *
 * [onEnter] closes the real session and opens the test profile;
 * [onExit] leaves to the real PIN gate; [onReloaded] runs after a
 * command reopened the test profile (the caller rebuilds the screens).
 */
@Composable
public fun TestModeSection(
    onEnter: suspend () -> Unit,
    onExit: () -> Unit,
    onReloaded: () -> Unit,
    // Test seams: the native file dialogs.
    pickExportFile: () -> File? = ::chooseExportFile,
    pickImportFile: () -> File? = ::chooseImportFile,
) {
    val active by TestMode.active
    var working by TestModeUi.working
    var status by TestModeUi.status
    var statusIsError by TestModeUi.statusIsError
    var pending by TestModeUi.pending
    var importFile by TestModeUi.importFile
    var keepDates by TestModeUi.keepDates

    fun run(reload: Boolean, action: suspend () -> String?) {
        if (working) return
        working = true
        status = null
        // App scope: the command outlives this screen (see [TestModeUi]).
        io.bhodl.desktop.AppScope.io.launch(kotlinx.coroutines.Dispatchers.Main) {
            val result = runCatching { action() }
            working = false
            result.fold(
                onSuccess = { message ->
                    statusIsError = false
                    status = message
                    if (reload) onReloaded()
                },
                onFailure = { e ->
                    statusIsError = true
                    status = testModeErrorText(e)
                    if (reload) onReloaded()
                },
            )
        }
    }

    fun export() {
        val target = pickExportFile() ?: return
        run(reload = true) {
            TestMode.exportTo(target)
            localizedText(Res.string.tm_exported, target.absolutePath)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(if (active) Res.string.tm_active_intro else Res.string.tm_intro),
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textSecondary,
        )
        if (!active) {
            Button(
                onClick = { run(reload = false) { onEnter(); null } },
                enabled = !working,
                modifier = Modifier.fillMaxWidth().testTag(TestModeTags.ENTER),
            ) { Text(stringResource(Res.string.tm_enter)) }
        } else {
            val day = remember(working, status) { TestMode.initialStateDay() }
            Text(
                text = day?.let { stringResource(Res.string.tm_initial_state, formatDay(it)) }
                    ?: stringResource(Res.string.tm_no_initial_state),
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.textSecondary,
                modifier = Modifier.testTag(TestModeTags.INITIAL_STATE),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(Res.string.tm_export, TestModeTags.EXPORT, !working, Modifier.weight(1f)) { export() }
                ActionButton(Res.string.tm_import, TestModeTags.IMPORT, !working, Modifier.weight(1f)) {
                    pickImportFile()?.let {
                        importFile = it
                        keepDates = false
                        pending = Pending.IMPORT
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(Res.string.tm_mark_initial, TestModeTags.MARK_INITIAL, !working, Modifier.weight(1f)) {
                    pending = Pending.MARK
                }
                ActionButton(Res.string.tm_restore_initial, TestModeTags.RESTORE_INITIAL, !working && day != null, Modifier.weight(1f)) {
                    pending = Pending.RESTORE
                }
            }
            ActionButton(Res.string.tm_load_project, TestModeTags.LOAD_PROJECT, !working, Modifier.fillMaxWidth()) {
                pending = Pending.PROJECT
            }
            Button(
                onClick = onExit,
                enabled = !working,
                modifier = Modifier.fillMaxWidth().testTag(TestModeTags.EXIT),
            ) { Text(stringResource(Res.string.tm_exit)) }
        }
        if (working || status != null) {
            Text(
                text = if (working) stringResource(Res.string.tm_working) else status.orEmpty(),
                style = BhodlTheme.typography.labelMedium,
                color = if (statusIsError && !working) BhodlTheme.colors.error else BhodlTheme.colors.textSecondary,
                modifier = Modifier.testTag(TestModeTags.STATUS),
            )
        }
    }

    pending?.let { what ->
        val (title, body) = when (what) {
            Pending.IMPORT -> Res.string.tm_import_title to Res.string.tm_import_body
            Pending.MARK -> Res.string.tm_mark_title to Res.string.tm_mark_body
            Pending.RESTORE -> Res.string.tm_restore_title to Res.string.tm_restore_body
            Pending.PROJECT -> Res.string.tm_project_title to Res.string.tm_project_body
        }
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(body), style = BhodlTheme.typography.bodyMedium, color = BhodlTheme.colors.textSecondary)
                    if (what == Pending.IMPORT) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { keepDates = !keepDates }.testTag(TestModeTags.KEEP_DATES),
                        ) {
                            Checkbox(checked = keepDates, onCheckedChange = { keepDates = it })
                            Text(stringResource(Res.string.tm_import_keep_dates), style = BhodlTheme.typography.bodyMedium)
                        }
                    }
                    if (what != Pending.MARK) {
                        TextButton(
                            onClick = { export() },
                            modifier = Modifier.testTag(TestModeTags.EXPORT_FIRST),
                        ) { Text(stringResource(Res.string.tm_export_first)) }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pending = null
                        val file = importFile
                        val keep = keepDates
                        val done = localizedText(Res.string.tm_done)
                        run(reload = true) {
                            when (what) {
                                Pending.IMPORT -> file?.let { TestMode.importFrom(it, keepDates = keep) }
                                Pending.MARK -> TestMode.markInitialState()
                                Pending.RESTORE -> TestMode.restoreInitialState()
                                Pending.PROJECT -> TestMode.loadProjectData()
                            }
                            done
                        }
                    },
                    modifier = Modifier.testTag(TestModeTags.CONFIRM),
                ) { Text(stringResource(Res.string.tm_confirm)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { pending = null },
                    modifier = Modifier.testTag(TestModeTags.CANCEL),
                ) { Text(stringResource(Res.string.tm_cancel)) }
            },
        )
    }
}

@Composable
private fun ActionButton(label: StringResource, tag: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.testTag(tag)) {
        Text(stringResource(label))
    }
}

/**
 * The band on top of every screen while test mode is on: real data
 * is closed, and the way out.
 */
@Composable
public fun TestModeBand(onExit: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BhodlTheme.colors.brandPrimary)
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag(TestModeTags.BAND),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(Res.string.tm_band),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.brandOnPrimary,
        )
        TextButton(onClick = onExit, modifier = Modifier.testTag(TestModeTags.BAND_EXIT)) {
            Text(
                text = stringResource(Res.string.tm_band_exit),
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.brandOnPrimary,
            )
        }
    }
}

private fun formatDay(day: LocalDate): String =
    day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

/** The localized message for a failed test-mode command. */
internal fun testModeErrorText(e: Throwable): String = when ((e as? TestModeException)?.reason) {
    TestModeException.Reason.NOT_A_TEST_PROFILE -> localizedText(Res.string.tm_err_not_test)
    TestModeException.Reason.BAD_FILE -> localizedText(Res.string.tm_err_bad_file)
    TestModeException.Reason.NEWER_FORMAT -> localizedText(Res.string.tm_err_newer)
    TestModeException.Reason.TOO_LARGE -> localizedText(Res.string.tm_err_too_large)
    TestModeException.Reason.NO_INITIAL_STATE -> localizedText(Res.string.tm_err_no_initial)
    null -> localizedText(
        Res.string.tm_err_generic,
        io.bhodl.desktop.ui.sanitizeErrorDetail(e.message, e.javaClass.simpleName),
    )
}

private fun chooseExportFile(): File? {
    val chooser = JFileChooser().apply {
        dialogTitle = localizedText(Res.string.tm_export_dialog)
        selectedFile = File("bhodl-test-${LocalDate.now()}.${TestMode.FILE_EXTENSION}")
        fileFilter = FileNameExtensionFilter(localizedText(Res.string.tm_file_filter), TestMode.FILE_EXTENSION)
    }
    if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return null
    val file = chooser.selectedFile ?: return null
    return if (file.name.endsWith(".${TestMode.FILE_EXTENSION}")) file else File(file.path + ".${TestMode.FILE_EXTENSION}")
}

private fun chooseImportFile(): File? {
    val chooser = JFileChooser().apply {
        dialogTitle = localizedText(Res.string.tm_import_dialog)
        fileFilter = FileNameExtensionFilter(localizedText(Res.string.tm_file_filter), TestMode.FILE_EXTENSION)
    }
    if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return null
    return chooser.selectedFile
}
