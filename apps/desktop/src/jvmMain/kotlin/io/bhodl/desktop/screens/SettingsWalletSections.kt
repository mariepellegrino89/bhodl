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
import io.bhodl.ffi.Network
import io.bhodl.desktop.ui.userMessage
import io.bhodl.ffi.WalletException
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
import io.bhodl.shared.resources.action_back
import io.bhodl.shared.resources.action_save
import io.bhodl.shared.resources.settings_esplora_active_default
import io.bhodl.shared.resources.settings_esplora_active_override
import io.bhodl.shared.resources.settings_esplora_description
import io.bhodl.shared.resources.settings_esplora_field_label
import io.bhodl.shared.resources.settings_esplora_reset
import io.bhodl.shared.resources.settings_esplora_section
import io.bhodl.shared.resources.settings_language_english
import io.bhodl.shared.resources.settings_language_italian
import io.bhodl.shared.resources.settings_language_section
import io.bhodl.shared.resources.settings_language_system
import io.bhodl.shared.resources.settings_theme_dark
import io.bhodl.shared.resources.settings_theme_light
import io.bhodl.shared.resources.settings_theme_neon
import io.bhodl.shared.resources.settings_theme_section
import io.bhodl.shared.resources.settings_theme_system
import io.bhodl.shared.resources.settings_title
import io.bhodl.desktop.prices.FiatCurrency
import io.bhodl.desktop.prices.PriceCache
import io.bhodl.desktop.ui.money.UnitFormat
import io.bhodl.shared.BtcUnit
import io.bhodl.ui.theme.BhodlTheme
import io.bhodl.ui.theme.ColorConvention
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import io.bhodl.shared.resources.common_cancel
import io.bhodl.shared.resources.set_backup_export
import io.bhodl.shared.resources.set_backup_export_failed
import io.bhodl.shared.resources.set_backup_exporting
import io.bhodl.shared.resources.set_backup_file_filter
import io.bhodl.shared.resources.set_backup_hint
import io.bhodl.shared.resources.set_backup_password
import io.bhodl.shared.resources.set_backup_password_confirm
import io.bhodl.shared.resources.set_backup_password_hint
import io.bhodl.shared.resources.set_backup_password_mismatch
import io.bhodl.shared.resources.set_backup_save_dialog_title
import io.bhodl.shared.resources.set_backup_save_failed
import io.bhodl.shared.resources.set_backup_save_file
import io.bhodl.shared.resources.set_backup_save_prompt
import io.bhodl.shared.resources.set_backup_saved_to
import io.bhodl.shared.resources.set_backup_title
import io.bhodl.shared.resources.set_delete_backup_first
import io.bhodl.shared.resources.set_delete_confirm
import io.bhodl.shared.resources.set_delete_failed
import io.bhodl.shared.resources.set_delete_hint
import io.bhodl.shared.resources.set_delete_no_active
import io.bhodl.shared.resources.set_delete_tap_again
import io.bhodl.shared.resources.set_delete_tip
import io.bhodl.shared.resources.set_delete_title
import io.bhodl.shared.resources.set_delete_working
import io.bhodl.shared.resources.set_reauth_purpose_backup
import io.bhodl.shared.resources.set_reauth_purpose_delete
import io.bhodl.shared.resources.set_reauth_purpose_descriptor
import io.bhodl.shared.resources.set_reauth_purpose_reveal
import io.bhodl.shared.resources.set_recovery_confirm
import io.bhodl.shared.resources.set_recovery_hide
import io.bhodl.shared.resources.set_recovery_hint
import io.bhodl.shared.resources.set_recovery_passphrase
import io.bhodl.shared.resources.set_recovery_reveal_failed
import io.bhodl.shared.resources.set_recovery_tap_again
import io.bhodl.shared.resources.set_recovery_title
import io.bhodl.shared.resources.set_recovery_word_count
import io.bhodl.shared.resources.set_rename_duplicate
import io.bhodl.shared.resources.set_rename_empty
import io.bhodl.shared.resources.set_rename_field
import io.bhodl.shared.resources.set_rename_hint
import io.bhodl.shared.resources.set_rename_not_found
import io.bhodl.shared.resources.set_rename_read_failed
import io.bhodl.shared.resources.set_rename_save
import io.bhodl.shared.resources.set_rename_save_failed
import io.bhodl.shared.resources.set_rename_saved
import io.bhodl.shared.resources.set_rename_saving
import io.bhodl.shared.resources.set_rename_title
import io.bhodl.shared.resources.set_switch_hint
import io.bhodl.shared.resources.set_switch_title
import io.bhodl.shared.resources.set_switch_to
import io.bhodl.shared.resources.set_tracker_add
import io.bhodl.shared.resources.set_tracker_add_failed
import io.bhodl.shared.resources.set_tracker_added
import io.bhodl.shared.resources.set_tracker_adding
import io.bhodl.shared.resources.set_tracker_already
import io.bhodl.shared.resources.set_tracker_checking
import io.bhodl.shared.resources.set_tracker_hint
import io.bhodl.shared.resources.set_tracker_notor_body
import io.bhodl.shared.resources.set_tracker_notor_continue
import io.bhodl.shared.resources.set_tracker_notor_title
import io.bhodl.shared.resources.set_tracker_read_failed
import io.bhodl.shared.resources.set_tracker_title
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
 * Phase 3.2-G — switch active signing wallet.
 *
 * Lists every signing wallet from the registry except the
 * currently-active one. Tapping a row hands control to
 * `App.kt`'s `onSwitchSigningWallet` which clears `WalletStore` and
 * navigates to `Unlock(walletId)` for the chosen wallet. The
 * Compose Multiplatform navigation pop-stack rules handle the
 * back-stack cleanup.
 *
 * Hidden when only one signing wallet exists — there's nothing to
 * switch to. The "Logout" button in the Dashboard already covers
 * "leave this wallet without going to another"; this section is
 * specifically the multi-wallet UX.
 */
@Composable
internal fun SwitchWalletSection(
    onSwitch: (walletId: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var wallets by remember {
        mutableStateOf<List<io.bhodl.ffi.WalletRecordFfi>>(emptyList())
    }
    var loaded by remember { mutableStateOf(false) }
    val activeId = io.bhodl.desktop.wizard.WalletStore.activeWalletId()
    val registry = io.bhodl.desktop.wizard.RegistryStore.current()

    LaunchedEffect(activeId, registry) {
        if (registry == null) {
            loaded = true
            return@LaunchedEffect
        }
        runCatching {
            withContext(Dispatchers.IO) {
                registry.listWallets().filter {
                    it.kind == io.bhodl.ffi.WalletKindFfi.SIGNING &&
                        it.id != activeId
                }
            }
        }.fold(
            onSuccess = {
                wallets = it
                loaded = true
            },
            onFailure = { loaded = true },
        )
    }

    // No-op render when there are no other signing wallets to switch
    // to. The user sees one less section instead of an empty one.
    if (!loaded) return
    if (wallets.isEmpty()) return

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.set_switch_title),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textSecondary,
        )
        Text(
            text = stringResource(Res.string.set_switch_hint),
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textTertiary,
        )
        wallets.forEach { row ->
            OutlinedButton(
                onClick = { onSwitch(row.id) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SettingsTags.SWITCH_WALLET_PREFIX + row.id),
            ) {
                Text(stringResource(Res.string.set_switch_to, row.label))
            }
        }
        // Quiet `scope` unused-warning on builds where the lambda
        // is removed by inlining. The launch path is reserved for
        // future per-row async lookups (Phase 3.5+: balance preview).
        @Suppress("UNUSED_EXPRESSION")
        scope
    }
}

/**
 * Rename the currently-active signing wallet.
 *
 * Reads the active wallet UUID from `WalletStore.activeWalletId()` —
 * `null` when no wallet is unlocked (defensive; Settings is only
 * reachable after Unlock). Loads the current label from the registry,
 * shows it in a TextField, and on Save calls
 * `RegistryHandle.renameWallet(walletId, newLabel)`. The picker
 * re-reads the registry every time it composes so the new label
 * appears on the next Welcome visit without further plumbing.
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
                label = "My Wallet"
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
 * Phase 6.0 — pair the currently-active signing wallet with the
 * Tracker by registering an xpub-only mirror in the registry.
 *
 * The section is a no-op when:
 *  - no signing wallet is unlocked (`WalletStore.current() == null`),
 *  - the active wallet already has a tracker counterpart (detected
 *    via the same [isAlreadyTrackedExcluding] helper used in
 *    [PinSetupScreen]).
 *
 * On tap: derives the xpub via [io.bhodl.ffi.WalletHandle.trackerDescriptorPub],
 * inserts a `WatchHd` row labelled `"<wallet> · tracker"`, and best-effort
 * runs the initial Esplora sync so the Tracker shows a real balance
 * immediately. Mainnet-without-Tor surfaces the same privacy warning
 * as the PinSetupScreen flow (via [pendingMainnetConfirm] reused
 * here as a local state).
 */
@Composable
internal fun AddToTrackerSection() {
    val scope = rememberCoroutineScope()
    val walletId = io.bhodl.desktop.wizard.WalletStore.activeWalletId()
    val activeNetwork = io.bhodl.desktop.wizard.WalletStore.network()
    val registry = io.bhodl.desktop.wizard.RegistryStore.current()
    val wallet = WalletStore.current()

    var loaded by remember { mutableStateOf(false) }
    var alreadyTracked by remember { mutableStateOf(false) }
    var descriptor by remember { mutableStateOf<String?>(null) }
    var walletLabel by remember { mutableStateOf("My Wallet") }
    var working by remember { mutableStateOf(false) }
    var lastMessage: String? by remember { mutableStateOf(null) }
    var pendingMainnetConfirm by remember { mutableStateOf(false) }
    val msgAdded = stringResource(Res.string.set_tracker_added)
    val msgAddFailed = stringResource(Res.string.set_tracker_add_failed)

    // Load once on entry: derive xpub + check duplicate.
    LaunchedEffect(walletId, registry, wallet) {
        if (walletId == null || registry == null || wallet == null) {
            loaded = true
            return@LaunchedEffect
        }
        runCatching {
            withContext(Dispatchers.IO) {
                val desc = wallet.trackerDescriptorPub()
                val tracked = isAlreadyTrackedExcluding(registry, desc, walletId)
                val label = runCatching {
                    registry.getWallet(walletId)?.label
                }.getOrNull() ?: "My Wallet"
                Triple(desc, tracked, label)
            }
        }.fold(
            onSuccess = { (desc, tracked, label) ->
                descriptor = desc
                alreadyTracked = tracked
                walletLabel = label
                loaded = true
            },
            onFailure = { e ->
                loaded = true
                lastMessage = e.userMessage(getString(Res.string.set_tracker_read_failed))
            },
        )
    }

    // Nothing to do when no signing wallet is active; we hide the
    // section entirely rather than render a stub.
    if (walletId == null || registry == null || wallet == null) return

    fun doAdd() {
        val desc = descriptor ?: return
        if (working) return
        val network = activeNetwork
        val mainnetNoTor = network == Network.MAINNET &&
            !io.bhodl.desktop.settings.SettingsStore.torEnabled.value
        if (mainnetNoTor && !pendingMainnetConfirm) {
            pendingMainnetConfirm = true
            return
        }
        pendingMainnetConfirm = false
        working = true
        lastMessage = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val xpub = io.bhodl.ffi.extractAccountXpub(desc)
                        ?: error("Could not extract account xpub from descriptor.")
                    // Linked mirror (registry v4 `mirror_of`): deleting
                    // this signing wallet removes exactly this row.
                    val added = registry.addWatchXpubMirror(
                        signingWalletId = walletId,
                        label = "$walletLabel · tracker",
                        xpub = xpub,
                        network = network,
                        bip = io.bhodl.ffi.WatchBipFfi.TAPROOT,
                    )
                    io.bhodl.desktop.wizard.RegistryStore.bumpDataVersion()
                    // Best-effort initial sync so the Tracker shows a
                    // real balance right away instead of 0.
                    runCatching {
                        val handle = registry.openWatch(added.id)
                        handle.syncWithEsplora(
                            io.bhodl.desktop.settings.EsploraEndpoint.current(network),
                            io.bhodl.desktop.settings.EsploraEndpoint.currentProxy(),
                        )
                        handle.destroy()
                    }
                }
            }.fold(
                onSuccess = {
                    alreadyTracked = true
                    working = false
                    lastMessage = msgAdded
                },
                onFailure = { e ->
                    working = false
                    lastMessage = e.userMessage(msgAddFailed)
                },
            )
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.set_tracker_title),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textSecondary,
        )
        Text(
            text = stringResource(Res.string.set_tracker_hint),
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textTertiary,
        )
        if (!loaded) {
            Text(
                text = stringResource(Res.string.set_tracker_checking),
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textTertiary,
            )
        } else if (alreadyTracked) {
            Text(
                text = stringResource(Res.string.set_tracker_already),
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.financialPositive,
                modifier = Modifier.testTag(SettingsTags.ADD_TO_TRACKER_STATUS),
            )
        } else {
            Button(
                onClick = { doAdd() },
                enabled = !working && descriptor != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SettingsTags.ADD_TO_TRACKER_BUTTON),
            ) {
                Text(if (working) stringResource(Res.string.set_tracker_adding) else stringResource(Res.string.set_tracker_add))
            }
        }
        if (lastMessage != null) {
            Text(
                text = lastMessage!!,
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textSecondary,
            )
        }
    }

    if (pendingMainnetConfirm) {
        AlertDialog(
            onDismissRequest = { pendingMainnetConfirm = false },
            title = { Text(stringResource(Res.string.set_tracker_notor_title)) },
            text = {
                Text(
                    stringResource(Res.string.set_tracker_notor_body),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingMainnetConfirm = false
                    doAdd()
                }) { Text(stringResource(Res.string.set_tracker_notor_continue)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingMainnetConfirm = false
                }) { Text(stringResource(Res.string.common_cancel)) }
            },
        )
    }
}

@Composable
internal fun BackupSection() {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    // Audit 2026-10 (H5) — confirmation field + minimum length: a typo
    // in a single unconfirmed field produced a backup nobody could
    // decrypt, and short passwords make the age blob brute-forceable.
    var passwordConfirm by remember { mutableStateOf("") }
    var blob by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var lastError: String? by remember { mutableStateOf(null) }
    // Audit 2026-10 (H5) — wallet PIN re-entry before the export.
    var reauthOpen by remember { mutableStateOf(false) }
    val passwordProblem = backupPasswordProblem(password, passwordConfirm)

    fun runExport() {
        working = true
        lastError = null
        blob = null
        val pw = password
        scope.launch {
            val wallet = WalletStore.current()
            if (wallet == null) {
                working = false
                lastError = getString(Res.string.set_wallet_not_unlocked)
                return@launch
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    wallet.exportEncryptedBackup(pw)
                }
            }.fold(
                onSuccess = {
                    blob = it
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
            text = stringResource(Res.string.set_backup_title),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textSecondary,
        )
        Text(
            text = stringResource(Res.string.set_backup_hint),
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textTertiary,
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(Res.string.set_backup_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            // Security review — explain WHY the export button below is
            // greyed out: an empty / short password would produce a blob
            // that is trivial to decrypt. (The Rust side also rejects empty.)
            supportingText = if (password.length < MIN_BACKUP_PASSWORD_LENGTH) {
                {
                    Text(
                        text = stringResource(Res.string.set_backup_password_hint, MIN_BACKUP_PASSWORD_LENGTH),
                        style = BhodlTheme.typography.labelMedium,
                        color = BhodlTheme.colors.textTertiary,
                        modifier = Modifier.testTag(SettingsTags.BACKUP_PASSWORD_HINT),
                    )
                }
            } else {
                null
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SettingsTags.BACKUP_PASSWORD),
        )
        OutlinedTextField(
            value = passwordConfirm,
            onValueChange = { passwordConfirm = it },
            label = { Text(stringResource(Res.string.set_backup_password_confirm)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            isError = passwordConfirm.isNotEmpty() && passwordConfirm != password,
            supportingText = if (passwordConfirm.isNotEmpty() && passwordConfirm != password) {
                { Text(stringResource(Res.string.set_backup_password_mismatch)) }
            } else {
                null
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SettingsTags.BACKUP_PASSWORD_CONFIRM),
        )
        Button(
            onClick = {
                if (working || passwordProblem != null) return@Button
                reauthOpen = true
            },
            enabled = !working && passwordProblem == null,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SettingsTags.BACKUP_EXPORT),
        ) {
            Text(if (working) stringResource(Res.string.set_backup_exporting) else stringResource(Res.string.set_backup_export))
        }
        if (lastError != null) {
            Text(
                text = lastError!!,
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textSecondary,
                modifier = Modifier.testTag(SettingsTags.BACKUP_ERROR),
            )
        }
        // Render the resulting age blob as monospaced read-only text
        // and offer a "Save to file…" button (native OS dialog via
        // Swing). The user picks the destination — we never silently
        // drop a key file on disk.
        if (blob != null) {
            Text(
                text = stringResource(Res.string.set_backup_save_prompt),
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textSecondary,
            )
            var saveMessage: String? by remember { mutableStateOf(null) }
            OutlinedButton(
                onClick = {
                    val b = blob!!
                    scope.launch { saveMessage = saveBackupBlobToFile(b) }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SettingsTags.BACKUP_SAVE_FILE),
            ) { Text(stringResource(Res.string.set_backup_save_file)) }
            if (saveMessage != null) {
                Text(
                    text = saveMessage!!,
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textSecondary,
                )
            }
            OutlinedTextField(
                value = blob!!,
                onValueChange = {},
                readOnly = true,
                singleLine = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SettingsTags.BACKUP_BLOB),
            )
            // Reset the password field once a blob has been produced
            // so it is not still in memory when the user navigates away.
            // (This is best-effort — Compose State holds the value
            // somewhere either way; a true zeroize lives in a follow-up.)
            LaunchedEffect(blob) {
                password = ""
                passwordConfirm = ""
            }
        }
    }
    if (reauthOpen) {
        WalletPinReauthDialog(
            purpose = stringResource(Res.string.set_reauth_purpose_backup),
            onVerified = {
                reauthOpen = false
                runExport()
            },
            onDismiss = { reauthOpen = false },
        )
    }
}

/** Audit 2026-10 — minimum length for the age backup password. */
internal const val MIN_BACKUP_PASSWORD_LENGTH: Int = 12

/** `null` when [password] is acceptable, otherwise a short reason. */
internal fun backupPasswordProblem(password: String, confirm: String): String? = when {
    password.isBlank() -> "Enter a backup password."
    password.length < MIN_BACKUP_PASSWORD_LENGTH ->
        "Use at least $MIN_BACKUP_PASSWORD_LENGTH characters."
    password != confirm -> "Passwords do not match."
    else -> null
}

/**
 * Show a native "Save as…" dialog (Swing JFileChooser, which renders
 * the platform-native chooser on Windows/macOS/Linux), write the
 * encrypted backup blob to the chosen path, and return a short
 * status string for the UI ("Saved to …" or "Save failed: …", or
 * `null` if the user cancelled).
 *
 * The default filename includes a UTC timestamp so two consecutive
 * exports do not collide. We default to `.txt` (the user can paste
 * it into any editor); the file content is the ASCII-armored age
 * payload, fully self-describing.
 */
private suspend fun saveBackupBlobToFile(blob: String): String? {
    val dialogTitleText = getString(Res.string.set_backup_save_dialog_title)
    val filterText = getString(Res.string.set_backup_file_filter)
    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val chooser = JFileChooser().apply {
        dialogTitle = dialogTitleText
        selectedFile = File("bhodl-backup-$timestamp.txt")
        fileFilter = FileNameExtensionFilter(filterText, "txt")
    }
    val result = chooser.showSaveDialog(null)
    if (result != JFileChooser.APPROVE_OPTION) return null
    val target = chooser.selectedFile ?: return null
    return writeBackupBlob(target, blob, WalletStore.activeWalletId()).map {
        getString(Res.string.set_backup_saved_to, target.absolutePath)
    }.getOrElse { e ->
        getString(Res.string.set_backup_save_failed, e.userMessage(e.javaClass.simpleName))
    }
}

/**
 * Write the encrypted backup to [target]; only once the file is really
 * on disk is the export date recorded for the Security Hub (security
 * review — producing the blob is not a backup until it is saved).
 */
internal fun writeBackupBlob(
    target: File,
    blob: String,
    walletId: String?,
    nowUnix: Long = System.currentTimeMillis() / 1000L,
): Result<Unit> = runCatching {
    target.writeText(blob, Charsets.UTF_8)
    walletId?.let { SettingsStore.markBackupExported(it, nowUnix) }
    Unit
}

@Composable
internal fun ShowRecoveryPhraseSection() {
    val scope = rememberCoroutineScope()
    var armed by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf<Pair<String, String>?>(null) }
    var working by remember { mutableStateOf(false) }
    var lastError: String? by remember { mutableStateOf(null) }
    // Audit 2026-10 (H5) — the reveal requires re-entering the wallet
    // PIN (counted by PinAttemptTracker); an unlocked-but-unattended
    // session is no longer enough to read the seed.
    var reauthOpen by remember { mutableStateOf(false) }
    val msgNotUnlocked = stringResource(Res.string.set_wallet_not_unlocked)
    val msgTapAgainReveal = stringResource(Res.string.set_recovery_tap_again)

    fun runReveal() {
        val wallet = WalletStore.current()
        if (wallet == null) {
            lastError = msgNotUnlocked
            armed = false
            return
        }
        working = true
        lastError = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { wallet.peekMnemonic() }
            }.fold(
                onSuccess = { reveal ->
                    revealed = reveal.mnemonic to reveal.passphrase
                    armed = false
                    working = false
                },
                onFailure = { e ->
                    lastError = e.userMessage(getString(Res.string.set_recovery_reveal_failed))
                    armed = false
                    working = false
                },
            )
        }
    }

    if (reauthOpen) {
        WalletPinReauthDialog(
            purpose = stringResource(Res.string.set_reauth_purpose_reveal),
            onVerified = {
                reauthOpen = false
                runReveal()
            },
            onDismiss = {
                reauthOpen = false
                armed = false
            },
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.set_recovery_title),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textSecondary,
        )
        Text(
            text = stringResource(Res.string.set_recovery_hint),
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textTertiary,
        )
        if (lastError != null) {
            Text(
                text = lastError!!,
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textSecondary,
                modifier = Modifier.testTag(SettingsTags.RECOVERY_ERROR),
            )
        }
        if (revealed == null) {
            OutlinedButton(
                onClick = {
                    if (working) return@OutlinedButton
                    if (!armed) {
                        armed = true
                        lastError = msgTapAgainReveal
                        return@OutlinedButton
                    }
                    if (WalletStore.current() == null) {
                        lastError = msgNotUnlocked
                        return@OutlinedButton
                    }
                    lastError = null
                    reauthOpen = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SettingsTags.RECOVERY_REVEAL),
            ) {
                Text(if (armed) stringResource(Res.string.set_recovery_confirm) else stringResource(Res.string.set_recovery_title))
            }
        } else {
            val (mnemonic, passphrase) = revealed!!
            val words = mnemonic.trim()
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
            Text(
                text = stringResource(Res.string.set_recovery_word_count, words.size),
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.textSecondary,
            )
            // Security review — the phrase is rendered as plain,
            // non-selectable Text (same grid as the setup wizard)
            // instead of a read-only OutlinedTextField: a text field
            // is selectable/copyable and routes its value through the
            // IME layer. DisableSelection is defence-in-depth in case
            // a future ancestor introduces a SelectionContainer.
            androidx.compose.foundation.text.selection.DisableSelection {
                RecoveryPhraseGrid(
                    words = words,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SettingsTags.RECOVERY_PHRASE),
                )
            }
            if (passphrase.isNotEmpty()) {
                // Audit 2026-10 — same treatment as the word grid: plain,
                // non-selectable Text (a read-only text field is still
                // selectable / copyable and routes through the IME).
                androidx.compose.foundation.text.selection.DisableSelection {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(BhodlTheme.colors.surface)
                            .padding(16.dp)
                            .testTag(SettingsTags.RECOVERY_PASSPHRASE),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(Res.string.set_recovery_passphrase),
                            style = BhodlTheme.typography.labelMedium,
                            color = BhodlTheme.colors.textSecondary,
                        )
                        Text(
                            text = passphrase,
                            style = BhodlTheme.typography.mono,
                            color = BhodlTheme.colors.textPrimary,
                        )
                    }
                }
            }
            OutlinedButton(
                onClick = { revealed = null },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SettingsTags.RECOVERY_HIDE),
            ) { Text(stringResource(Res.string.set_recovery_hide)) }
        }
    }
}

/**
 * Numbered word grid for the recovery-phrase reveal — same layout as
 * the setup wizard's `MnemonicGrid` (GenerateMnemonicScreen): rows of
 * three plain `Text` cells on a surface card. Non-editable and
 * non-selectable by construction.
 */
@Composable
private fun RecoveryPhraseGrid(
    words: List<String>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(BhodlTheme.colors.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        words.chunked(RECOVERY_GRID_COLUMNS).forEachIndexed { rowIndex, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                row.forEachIndexed { colIndex, word ->
                    val index = rowIndex * RECOVERY_GRID_COLUMNS + colIndex + 1
                    Text(
                        text = "$index. $word",
                        style = BhodlTheme.typography.mono,
                        color = BhodlTheme.colors.textPrimary,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }
}

private const val RECOVERY_GRID_COLUMNS: Int = 3

/**
 * Watch-only descriptor export.
 *
 * Pulls the public-only descriptor + account xpub from the active
 * `WalletHandle` via `exportPublicDescriptor()`. No secrets leave the
 * device. Useful for:
 * - pasting the bare xpub into the Phase 3.1 debug screen (own wallet
 *   appears as a watch-only entry alongside future imported wallets),
 * - importing the full external descriptor into Sparrow / Liana /
 *   Specter as a watch-only wallet to verify receive addresses,
 * - cross-device audit (read-only inspection from a second machine).
 */
@Composable
internal fun ShowWatchOnlyDescriptorSection() {
    val scope = rememberCoroutineScope()
    var revealed by remember {
        mutableStateOf<io.bhodl.ffi.PublicDescriptorBundle?>(null)
    }
    var working by remember { mutableStateOf(false) }
    var lastError: String? by remember { mutableStateOf(null) }
    var reauthOpen by remember { mutableStateOf(false) }
    val msgNotUnlocked = stringResource(Res.string.set_wallet_not_unlocked)

    // Security review 2026-10 — the xpub exposes the whole wallet
    // history: same wallet-PIN re-auth as the recovery phrase / backup.
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
                    working = false
                },
                onFailure = { e ->
                    lastError = e.userMessage(getString(Res.string.set_backup_export_failed))
                    working = false
                },
            )
        }
    }

    if (reauthOpen) {
        WalletPinReauthDialog(
            purpose = stringResource(Res.string.set_reauth_purpose_descriptor),
            onVerified = {
                reauthOpen = false
                runExport()
            },
            onDismiss = { reauthOpen = false },
        )
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
        val current = revealed
        if (current == null) {
            OutlinedButton(
                onClick = {
                    if (working) return@OutlinedButton
                    if (WalletStore.current() == null) {
                        lastError = msgNotUnlocked
                        return@OutlinedButton
                    }
                    reauthOpen = true
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
                    copyToClipboardWithAutoClear(current.accountXpub)
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
                    copyToClipboardWithAutoClear(current.externalDescriptor)
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
 * Delete the active signing wallet — registry row + `wallets/<uuid>/`
 * directory only. The other wallets in the registry (watch-only,
 * other signing slots) are untouched.
 *
 * Flow:
 *  1. `WalletStore.clear()` — releases the SQLCipher file lock on
 *     the wallet's `wallet.sqlite` so the subsequent rm-rf is not
 *     blocked on Windows.
 *  2. `RegistryHandle.removeWallet(id)` — drops the row.
 *  3. Delete `wallets/<uuid>/` recursively from disk.
 *  4. Navigate away (`onDeleted` → App.kt routes to Welcome, which
 *     enters picker mode if any wallets remain, legacy mode if zero).
 *
 * The `RegistryStore` stays alive so the Welcome picker (or, in the
 * zero-wallets case, the legacy Welcome) renders without a re-prompt
 * for the device PIN.
 */
@Composable
internal fun DeleteThisWalletSection(
    onDeleted: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var armed by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var lastMessage: String? by remember { mutableStateOf(null) }
    var reauthOpen by remember { mutableStateOf(false) }
    val msgBackupFirst = stringResource(Res.string.set_delete_backup_first)
    val msgNoActive = stringResource(Res.string.set_delete_no_active)
    val msgTapAgainDelete = stringResource(Res.string.set_delete_tap_again)

    val walletId = io.bhodl.desktop.wizard.WalletStore.activeWalletId()
    val registry = io.bhodl.desktop.wizard.RegistryStore.current()

    fun runDelete() {
        if (walletId == null || registry == null) return
        working = true
        lastMessage = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    // 1. Release the signing handle so the
                    //    SQLCipher file lock drops BEFORE the
                    //    file-tree delete (Windows finding #14).
                    WalletStore.clear()
                    // 2. Drop the registry row first so a
                    //    crash between steps does not leave a
                    //    dangling row pointing at a gone dir.
                    registry.removeWallet(walletId)
                    io.bhodl.desktop.wizard.RegistryStore.bumpDataVersion()
                    // 3. rm -rf wallets/<uuid>/ from disk.
                    val rootView = WalletPaths
                        .from(WalletPaths.defaultDataDir())
                    val walletDir = java.io.File(
                        rootView.walletsRoot,
                        walletId,
                    )
                    walletDir.deleteRecursively()
                }
            }.fold(
                onSuccess = {
                    working = false
                    armed = false
                    onDeleted()
                },
                onFailure = { e ->
                    working = false
                    armed = false
                    lastMessage = e.userMessage(getString(Res.string.set_delete_failed))
                },
            )
        }
    }

    // Security review 2026-10 — irreversible: the same wallet-PIN
    // re-auth as the recovery phrase / backup export, after the
    // two-tap arm.
    if (reauthOpen) {
        WalletPinReauthDialog(
            purpose = stringResource(Res.string.set_reauth_purpose_delete),
            onVerified = {
                reauthOpen = false
                runDelete()
            },
            onDismiss = {
                reauthOpen = false
                armed = false
            },
        )
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
        // Phase 3.6 polish — offer an encrypted backup export shortcut
        // BEFORE the destructive action, so a user who taps Delete
        // without first having a backup is reminded the encrypted
        // .age export still works and takes ~2 seconds.
        TextButton(
            onClick = {
                lastMessage = msgBackupFirst
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(Res.string.set_delete_tip),
                color = BhodlTheme.colors.textTertiary,
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
                    lastMessage = msgTapAgainDelete
                    return@OutlinedButton
                }
                if (working) return@OutlinedButton
                reauthOpen = true
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
