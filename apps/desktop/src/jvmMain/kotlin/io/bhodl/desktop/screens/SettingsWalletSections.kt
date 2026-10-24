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
import io.bhodl.shared.resources.set_watchdesc_unavailable
import io.bhodl.desktop.prices.FiatCurrency
import io.bhodl.desktop.prices.PriceCache
import io.bhodl.desktop.ui.money.UnitFormat
import io.bhodl.shared.BtcUnit
import io.bhodl.ui.theme.BhodlTheme
import io.bhodl.ui.theme.ColorConvention
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import io.bhodl.shared.resources.set_backup_export_failed
import io.bhodl.shared.resources.set_delete_confirm
import io.bhodl.shared.resources.set_delete_failed
import io.bhodl.shared.resources.set_delete_hint
import io.bhodl.shared.resources.set_delete_no_active
import io.bhodl.shared.resources.set_delete_tap_again
import io.bhodl.shared.resources.set_delete_title
import io.bhodl.shared.resources.set_delete_working
import io.bhodl.shared.resources.set_rename_duplicate
import io.bhodl.shared.resources.set_rename_empty
import io.bhodl.shared.resources.set_rename_field
import io.bhodl.shared.resources.set_rename_hint
import io.bhodl.shared.resources.set_rename_not_found
import io.bhodl.shared.resources.set_rename_read_failed
import io.bhodl.shared.resources.set_rename_default_label
import io.bhodl.shared.resources.set_rename_save
import io.bhodl.shared.resources.set_rename_save_failed
import io.bhodl.shared.resources.set_rename_saved
import io.bhodl.shared.resources.set_rename_saving
import io.bhodl.shared.resources.set_rename_title
import io.bhodl.shared.resources.set_wallet_not_unlocked
import io.bhodl.shared.resources.set_watchdesc_copy_external
import io.bhodl.shared.resources.set_watchdesc_copy_xpub
import io.bhodl.shared.resources.set_watchdesc_external_label
import io.bhodl.shared.resources.set_watchdesc_hide
import io.bhodl.shared.resources.set_watchdesc_hint
import io.bhodl.shared.resources.set_watchdesc_show
import io.bhodl.shared.resources.set_watchdesc_title
import io.bhodl.shared.resources.set_watchdesc_xpub_label
import io.bhodl.shared.resources.set_wipe_button
import io.bhodl.shared.resources.set_wipe_hint
import io.bhodl.shared.resources.set_wipe_title

/**
 * Rename the wallet open in the workspace.
 *
 * Reads the active wallet UUID from `WalletStore.activeWalletId()`,
 * loads the current label from the registry, shows it in a TextField,
 * and on Save calls `RegistryHandle.renameWallet(walletId, newLabel)`
 * then bumps the registry data version so the tracker re-reads.
 */
@Composable
internal fun RenameWalletSection() {
    val scope = rememberCoroutineScope()
    val walletId = io.bhodl.desktop.wizard.WalletStore.activeWalletId()
    val registry = io.bhodl.desktop.wizard.RegistryStore.current()
    var label by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var lastMessage: String? by remember { mutableStateOf(null) }
    val msgNameEmpty = stringResource(Res.string.set_rename_empty)
    val msgSaved = stringResource(Res.string.set_rename_saved)
    val msgNotFound = stringResource(Res.string.set_rename_not_found)
    val msgSaveFailed = stringResource(Res.string.set_rename_save_failed)

    // Load the current label once on entry.
    LaunchedEffect(walletId, registry) {
        if (walletId == null || registry == null) {
            loaded = true
            return@LaunchedEffect
        }
        runCatching {
            withContext(Dispatchers.IO) {
                registry.getWallet(walletId)?.label ?: "My Wallet"
            }
        }.fold(
            onSuccess = {
                label = it
                loaded = true
            },
            onFailure = { e ->
                label = getString(Res.string.set_rename_default_label)
                loaded = true
                lastMessage = e.userMessage(getString(Res.string.set_rename_read_failed))
            },
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.set_rename_title),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textSecondary,
        )
        Text(
            text = stringResource(Res.string.set_rename_hint),
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textTertiary,
        )
        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text(stringResource(Res.string.set_rename_field)) },
            singleLine = true,
            enabled = loaded && walletId != null && registry != null && !working,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SettingsTags.WALLET_NAME_FIELD),
        )
        if (lastMessage != null) {
            Text(
                text = lastMessage!!,
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textSecondary,
            )
        }
        Button(
            onClick = {
                if (working || walletId == null || registry == null) return@Button
                val newLabel = label.trim()
                if (newLabel.isBlank()) {
                    lastMessage = msgNameEmpty
                    return@Button
                }
                working = true
                lastMessage = null
                scope.launch {
                    // Pre-flight: reject if the new label collides with
                    // any OTHER wallet in the registry. Excluding the
                    // active id lets the user keep the same name (no-op
                    // save) or restore it after an in-progress edit
                    // without spurious rejection.
                    val duplicate = runCatching {
                        withContext(Dispatchers.IO) {
                            io.bhodl.desktop.wizard.WalletLabel.isDuplicate(
                                registry,
                                newLabel,
                                excludeWalletId = walletId,
                            )
                        }
                    }.getOrDefault(false)
                    if (duplicate) {
                        working = false
                        lastMessage = getString(Res.string.set_rename_duplicate)
                        return@launch
                    }
                    runCatching {
                        withContext(Dispatchers.IO) {
                            registry.renameWallet(walletId, newLabel)
                        }
                    }.fold(
                        onSuccess = { ok ->
                            working = false
                            lastMessage = if (ok) msgSaved else msgNotFound
                            if (ok) io.bhodl.desktop.wizard.RegistryStore.bumpDataVersion()
                        },
                        onFailure = { e ->
                            working = false
                            lastMessage = e.userMessage(msgSaveFailed)
                        },
                    )
                }
            },
            enabled = loaded && walletId != null && registry != null &&
                !working && label.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SettingsTags.WALLET_NAME_SAVE),
        ) {
            Text(if (working) stringResource(Res.string.set_rename_saving) else stringResource(Res.string.set_rename_save))
        }
    }
}

/**
 * Watch-only descriptor export.
 *
 * Pulls the public descriptor + account xpub of the open wallet via
 * `exportPublicDescriptor()` — for import into Sparrow / Liana /
 * Specter or a cross-device audit. The xpub exposes the whole wallet
 * history, so it is shown only on request and copied with auto-clear.
 * Address-list wallets have no descriptor.
 */
@Composable
internal fun ShowWatchOnlyDescriptorSection(
    // Test seam: the protected, auto-clearing clipboard copy.
    copyValue: (String) -> Unit = ::copyToClipboardWithAutoClear,
) {
    val scope = rememberCoroutineScope()
    var revealed by remember {
        mutableStateOf<io.bhodl.ffi.PublicDescriptorBundle?>(null)
    }
    var working by remember { mutableStateOf(false) }
    var lastError: String? by remember { mutableStateOf(null) }
    var unavailable by remember { mutableStateOf(false) }
    val msgNotUnlocked = stringResource(Res.string.set_wallet_not_unlocked)

    fun runExport() {
        val wallet = WalletStore.current()
        if (wallet == null) {
            lastError = msgNotUnlocked
            return
        }
        working = true
        lastError = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    wallet.exportPublicDescriptor()
                }
            }.fold(
                onSuccess = { bundle ->
                    revealed = bundle
                    unavailable = bundle == null
                    working = false
                },
                onFailure = { e ->
                    lastError = e.userMessage(getString(Res.string.set_backup_export_failed))
                    working = false
                },
            )
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.set_watchdesc_title),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textSecondary,
        )
        Text(
            text = stringResource(Res.string.set_watchdesc_hint),
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textTertiary,
        )
        if (lastError != null) {
            Text(
                text = lastError!!,
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textSecondary,
                modifier = Modifier.testTag(SettingsTags.WATCH_DESC_ERROR),
            )
        }
        if (unavailable) {
            Text(
                text = stringResource(Res.string.set_watchdesc_unavailable),
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textSecondary,
            )
        }
        val current = revealed
        if (current == null) {
            OutlinedButton(
                onClick = {
                    if (working) return@OutlinedButton
                    runExport()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SettingsTags.WATCH_DESC_REVEAL),
            ) {
                Text(stringResource(Res.string.set_watchdesc_show))
            }
        } else {
            OutlinedTextField(
                value = current.accountXpub,
                onValueChange = {},
                readOnly = true,
                singleLine = false,
                label = { Text(stringResource(Res.string.set_watchdesc_xpub_label)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SettingsTags.WATCH_DESC_XPUB),
            )
            OutlinedButton(
                onClick = {
                    copyValue(current.accountXpub)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SettingsTags.WATCH_DESC_COPY_XPUB),
            ) { Text(stringResource(Res.string.set_watchdesc_copy_xpub)) }
            OutlinedTextField(
                value = current.externalDescriptor,
                onValueChange = {},
                readOnly = true,
                singleLine = false,
                label = { Text(stringResource(Res.string.set_watchdesc_external_label)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SettingsTags.WATCH_DESC_EXTERNAL),
            )
            OutlinedButton(
                onClick = {
                    copyValue(current.externalDescriptor)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SettingsTags.WATCH_DESC_COPY_DESC),
            ) { Text(stringResource(Res.string.set_watchdesc_copy_external)) }
            OutlinedButton(
                onClick = { revealed = null },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SettingsTags.WATCH_DESC_HIDE),
            ) { Text(stringResource(Res.string.set_watchdesc_hide)) }
        }
    }
}

/**
 * Security review — copy [value] to the clipboard, then clear it after
 * [io.bhodl.desktop.ui.SensitiveClipboard.AUTO_CLEAR_DELAY_MS] **only if**
 * the clipboard still holds that exact value (so unrelated content the
 * user copied in the meantime is never clobbered). An xpub left in the
 * clipboard indefinitely is a privacy leak — any later paste (chat,
 * search bar) exposes the whole wallet history.
 *
 * Audit 2026-10 — the timer runs on the app-lifetime
 * [io.bhodl.desktop.AppScope]; on Windows the value is also excluded
 * from clipboard history / cloud clipboard (see
 * [io.bhodl.desktop.ui.SensitiveClipboard]).
 */
private fun copyToClipboardWithAutoClear(value: String) {
    io.bhodl.desktop.ui.SensitiveClipboard.copy(
        value,
        autoClearMs = io.bhodl.desktop.ui.SensitiveClipboard.AUTO_CLEAR_DELAY_MS,
    )
}

/**
 * Remove the wallet open in the workspace from BHODL.
 *
 * Flow (two-tap confirm):
 *  1. `WalletStore.clear()` — releases the SQLCipher file lock on the
 *     wallet's `watch-state/<uuid>.sqlite` (Windows).
 *  2. `RegistryHandle.removeWallet(id)` — drops the row and everything
 *     that references it in one transaction, then deletes the state file.
 *  3. `onRemoved` — App.kt routes back to the tracker wallet list.
 *
 * Watch-only: no funds are at stake, the wallet can be re-added from its
 * xpub at any time.
 */
@Composable
internal fun RemoveThisWalletSection(
    onRemoved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var armed by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var lastMessage: String? by remember { mutableStateOf(null) }
    val msgNoActive = stringResource(Res.string.set_delete_no_active)
    val msgTapAgain = stringResource(Res.string.set_delete_tap_again)

    val walletId = WalletStore.activeWalletId()
    val registry = io.bhodl.desktop.wizard.RegistryStore.current()

    fun runRemove() {
        if (walletId == null || registry == null) return
        working = true
        lastMessage = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    WalletStore.clear()
                    registry.removeWallet(walletId)
                    io.bhodl.desktop.wizard.RegistryStore.bumpDataVersion()
                }
            }.fold(
                onSuccess = {
                    // GAP-024 — the UUID of a removed wallet must not
                    // linger in settings.json.
                    SettingsStore.setWalletIncluded(walletId, true)
                    working = false
                    armed = false
                    onRemoved()
                },
                onFailure = { e ->
                    working = false
                    armed = false
                    lastMessage = e.userMessage(getString(Res.string.set_delete_failed))
                },
            )
        }
    }

    androidx.compose.material3.Divider(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        color = BhodlTheme.colors.outline,
    )
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.set_delete_title),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.error,
        )
        Text(
            text = stringResource(Res.string.set_delete_hint),
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textTertiary,
        )
        if (lastMessage != null) {
            Text(
                text = lastMessage!!,
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textSecondary,
            )
        }
        OutlinedButton(
            onClick = {
                if (walletId == null || registry == null) {
                    lastMessage = msgNoActive
                    return@OutlinedButton
                }
                if (!armed) {
                    armed = true
                    lastMessage = msgTapAgain
                    return@OutlinedButton
                }
                if (working) return@OutlinedButton
                runRemove()
            },
            enabled = walletId != null && registry != null && !working,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SettingsTags.DELETE_THIS_WALLET),
        ) {
            Text(
                text = when {
                    working -> stringResource(Res.string.set_delete_working)
                    armed -> stringResource(Res.string.set_delete_confirm)
                    else -> stringResource(Res.string.set_delete_title)
                },
            )
        }
    }
}

@Composable
internal fun WipeSection(
    onWiped: () -> Unit,
    dataDir: java.io.File,
) {
    var dialogOpen by remember { mutableStateOf(false) }
    var lastMessage: String? by remember { mutableStateOf(null) }

    // Visual separator: erase is a destructive, irreversible action.
    // A Divider + vertical spacing creates white space that prevents
    // the user from reaching the wipe button in a careless scroll.
    androidx.compose.material3.Divider(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        color = BhodlTheme.colors.outline,
    )
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // error color (red-700 light / red-400 dark) clearly separates
        // this section label from every other setting label (textSecondary).
        Text(
            text = stringResource(Res.string.set_wipe_title),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.error,
        )
        Text(
            text = stringResource(Res.string.set_wipe_hint, io.bhodl.desktop.wizard.LocalWipe.CONFIRM_PHRASE),
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textTertiary,
        )
        if (lastMessage != null) {
            Text(
                text = lastMessage!!,
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textSecondary,
            )
        }
        // Audit 2026-10 (M8) — typed-phrase confirmation; LocalWipe
        // releases every handle (signing, watch, registry, device PIN)
        // BEFORE deleting, deletes databases before their KDF files,
        // and reports a verified outcome.
        OutlinedButton(
            onClick = { dialogOpen = true },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SettingsTags.WIPE),
        ) {
            Text(text = stringResource(Res.string.set_wipe_button))
        }
        if (dialogOpen) {
            ConfirmWipeDialog(
                dataDir = dataDir,
                onDismiss = { dialogOpen = false },
                onFinished = { result ->
                    dialogOpen = false
                    lastMessage = io.bhodl.desktop.wizard.LocalWipe.describe(result)
                    if (!result.aborted) onWiped()
                },
            )
        }
    }
}
