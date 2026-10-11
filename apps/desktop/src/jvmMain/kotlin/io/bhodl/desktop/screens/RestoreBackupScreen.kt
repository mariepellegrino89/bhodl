package io.bhodl.desktop.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.ui.BhodlBackArrow
import io.bhodl.desktop.wallet.NetworkPersistence
import io.bhodl.ffi.Network
import io.bhodl.desktop.ui.userMessage
import io.bhodl.desktop.wizard.localizedText
import io.bhodl.ffi.WalletException
import io.bhodl.ffi.decryptBackup
import io.bhodl.ui.theme.BhodlTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.action_back
import io.bhodl.shared.resources.onb_backup_blob
import io.bhodl.shared.resources.onb_backup_dialog_title
import io.bhodl.shared.resources.onb_backup_file_filter
import io.bhodl.shared.resources.onb_backup_password
import io.bhodl.shared.resources.onb_backup_paste_first
import io.bhodl.shared.resources.onb_backup_type_password
import io.bhodl.shared.resources.onb_decrypt_continue
import io.bhodl.shared.resources.onb_decrypting
import io.bhodl.shared.resources.onb_decryption_failed
import io.bhodl.shared.resources.onb_load_failed
import io.bhodl.shared.resources.onb_load_from_file
import io.bhodl.shared.resources.onb_restore_backup_body
import io.bhodl.shared.resources.onb_restore_backup_title
import org.jetbrains.compose.resources.stringResource

/**
 * Restore an existing wallet from an age-encrypted backup blob
 * exported via Settings → Encrypted backup.
 *
 * The user pastes the ASCII-armored blob into a multi-line text
 * field, types the backup password (separate from the PIN), and we
 * call into the FFI to decrypt + parse. On success we forward to
 * [`io.bhodl.desktop.navigation.Route.PinSetup`] with the recovered
 * mnemonic AND passphrase — so the user only has to pick a fresh
 * PIN, the Advanced section of PinSetup opens automatically with
 * the recovered 13th word pre-filled.
 *
 * The decrypt happens on `Dispatchers.IO` because age's scrypt KDF
 * costs ~1 s and would freeze the UI thread otherwise.
 */
@Composable
public fun RestoreBackupScreen(
    onValidated: (mnemonic: String, passphrase: String, network: Network) -> Unit,
    onBack: () -> Unit,
    decrypt: (String, String) -> RestoreBackupContents = { blob, pw ->
        // Default production wiring: call into the FFI top-level
        // decryptBackup function. Tests inject a stub that returns a
        // canned (mnemonic, passphrase, network) record without
        // touching age. The network from the blob is the **source of
        // truth** for the restored wallet — the user is never asked to
        // pick it manually since BIP-86 descriptors only validate
        // against the original network.
        val contents = decryptBackup(blob = blob, password = pw)
        RestoreBackupContents(
            mnemonic = contents.mnemonic,
            passphrase = contents.passphrase,
            network = NetworkPersistence.parse(contents.network) ?: Network.SIGNET,
        )
    },
) {
    val scope = rememberCoroutineScope()

    var blob by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var lastError: String? by remember { mutableStateOf(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BhodlTheme.colors.background),
        contentAlignment = Alignment.Center,
    ) {
        BhodlBackArrow(
            onBack = onBack,
            modifier = Modifier.align(Alignment.TopStart),
        )
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .widthIn(max = 480.dp)
                .padding(horizontal = 32.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(Res.string.onb_restore_backup_title),
                style = BhodlTheme.typography.headlineMedium,
                color = BhodlTheme.colors.brandPrimary,
            )
            Text(
                text = stringResource(Res.string.onb_restore_backup_body),
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )

            OutlinedButton(
                onClick = {
                    loadBackupBlobFromFile()?.let { (loaded, err) ->
                        if (err != null) {
                            lastError = err
                        } else {
                            blob = loaded ?: ""
                            lastError = null
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(RestoreBackupTags.LOAD_FILE),
            ) { Text(stringResource(Res.string.onb_load_from_file)) }

            OutlinedTextField(
                value = blob,
                onValueChange = { blob = it },
                label = { Text(stringResource(Res.string.onb_backup_blob)) },
                singleLine = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .testTag(RestoreBackupTags.BLOB),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(Res.string.onb_backup_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(RestoreBackupTags.PASSWORD),
            )

            if (lastError != null) {
                Text(
                    text = lastError!!,
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag(RestoreBackupTags.ERROR),
                )
            }

            Button(
                onClick = onClick@{
                    if (working) return@onClick
                    if (blob.isBlank()) {
                        lastError = localizedText(Res.string.onb_backup_paste_first)
                        return@onClick
                    }
                    if (password.isEmpty()) {
                        lastError = localizedText(Res.string.onb_backup_type_password)
                        return@onClick
                    }
                    working = true
                    lastError = null
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                decrypt(blob, password)
                            }
                        }.fold(
                            onSuccess = { contents ->
                                working = false
                                onValidated(contents.mnemonic, contents.passphrase, contents.network)
                            },
                            onFailure = { e ->
                                working = false
                                lastError = e.userMessage(localizedText(Res.string.onb_decryption_failed))
                            },
                        )
                    }
                },
                enabled = !working && blob.isNotBlank() && password.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(RestoreBackupTags.DECRYPT),
            ) {
                Text(
                    if (working) stringResource(Res.string.onb_decrypting)
                    else stringResource(Res.string.onb_decrypt_continue),
                )
            }
            OutlinedButton(
                onClick = onBack,
                enabled = !working,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(RestoreBackupTags.BACK),
            ) { Text(stringResource(Res.string.action_back)) }
        }
    }
}

/**
 * Decrypted contents of a [`RestoreBackupScreen`] decrypt call.
 * Mirrors `io.bhodl.ffi.BackupContents` but with the network already
 * parsed from string into the typed enum. Lifted to a top-level
 * record so the test seam (`decrypt:` parameter) can return it
 * without depending on the FFI type.
 */
public data class RestoreBackupContents(
    val mnemonic: String,
    val passphrase: String,
    val network: Network,
) {
    /** Audit 2026-10 — never print seed material (logs, crash dumps). */
    override fun toString(): String =
        "RestoreBackupContents(mnemonic=<redacted>, passphrase=<redacted>, network=$network)"
}

/** Test tags exposed by [`RestoreBackupScreen`]. */
public object RestoreBackupTags {
    public const val BLOB: String = "restore-backup.blob"
    public const val LOAD_FILE: String = "restore-backup.load-file"
    public const val PASSWORD: String = "restore-backup.password"
    public const val DECRYPT: String = "restore-backup.decrypt"
    public const val ERROR: String = "restore-backup.error"
    public const val BACK: String = "restore-backup.back"
}

/**
 * Show a native "Open…" dialog and read the selected file as a UTF-8
 * string. Returns `null` if the user cancels, or a `(content, error)`
 * pair where exactly one of the two is non-null.
 */
/** Upper bound for an encrypted backup file (1 MiB). */
private const val MAX_BACKUP_FILE_BYTES: Int = 1024 * 1024

private fun loadBackupBlobFromFile(): Pair<String?, String?>? {
    val chooser = JFileChooser().apply {
        dialogTitle = localizedText(Res.string.onb_backup_dialog_title)
        fileFilter = FileNameExtensionFilter(localizedText(Res.string.onb_backup_file_filter), "txt", "age")
    }
    val result = chooser.showOpenDialog(null)
    if (result != JFileChooser.APPROVE_OPTION) return null
    val source = chooser.selectedFile ?: return null
    return runCatching {
        // An encrypted backup blob is a few KiB; cap the read so a wrong
        // (huge) file can't exhaust the heap.
        io.bhodl.desktop.data.readTextCapped(source, MAX_BACKUP_FILE_BYTES) to null
    }.getOrElse { e ->
        null to localizedText(Res.string.onb_load_failed, e.userMessage(e.javaClass.simpleName))
    }
}
