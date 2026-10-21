package io.bhodl.desktop.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.ui.BhodlTopBar
import io.bhodl.desktop.wizard.PinPairValidation
import io.bhodl.desktop.wizard.PinValidator
import io.bhodl.desktop.wizard.RegistryStore
import io.bhodl.desktop.wizard.WalletPaths
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.desktop.wizard.WatchStore
import io.bhodl.desktop.wizard.localizedText
import io.bhodl.ffi.Network
import io.bhodl.desktop.ui.userMessage
import io.bhodl.ffi.WalletException
import io.bhodl.ffi.WalletHandle
import io.bhodl.ffi.WalletKindFfi
import io.bhodl.ffi.WalletRecordFfi
import io.bhodl.ui.theme.BhodlTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.common_loading
import io.bhodl.shared.resources.onb_advanced_passphrase
import io.bhodl.shared.resources.onb_confirm_pin
import io.bhodl.shared.resources.onb_continue
import io.bhodl.shared.resources.onb_device_pin
import io.bhodl.shared.resources.onb_device_pin_wrong
import io.bhodl.shared.resources.onb_hide_advanced
import io.bhodl.shared.resources.onb_i_understand
import io.bhodl.shared.resources.onb_passphrase_label
import io.bhodl.shared.resources.onb_pin_invalid
import io.bhodl.shared.resources.onb_keystore_unavailable
import io.bhodl.shared.resources.onb_passphrase_body
import io.bhodl.shared.resources.onb_pin_label
import io.bhodl.shared.resources.onb_pins_mismatch
import io.bhodl.shared.resources.onb_promote_address
import io.bhodl.shared.resources.onb_promote_addresses_ok
import io.bhodl.shared.resources.onb_promote_addresses_wrong
import io.bhodl.shared.resources.onb_promote_failed
import io.bhodl.shared.resources.onb_promote_intro
import io.bhodl.shared.resources.onb_promote_load_failed
import io.bhodl.shared.resources.onb_promote_pin_body
import io.bhodl.shared.resources.onb_promote_pin_title
import io.bhodl.shared.resources.onb_promote_same_pin_body
import io.bhodl.shared.resources.onb_promote_seed_body
import io.bhodl.shared.resources.onb_promote_seed_mismatch
import io.bhodl.shared.resources.onb_promote_seed_title
import io.bhodl.shared.resources.onb_promote_seed_verify_failed
import io.bhodl.shared.resources.onb_promote_submit
import io.bhodl.shared.resources.onb_promote_title
import io.bhodl.shared.resources.onb_promote_verify_body
import io.bhodl.shared.resources.onb_promote_verify_seed
import io.bhodl.shared.resources.onb_promote_verify_title
import io.bhodl.shared.resources.onb_promote_wallet_pin_body
import io.bhodl.shared.resources.onb_promote_wallet_pin_title
import io.bhodl.shared.resources.onb_promoting
import io.bhodl.shared.resources.onb_restore_phrase_label
import io.bhodl.shared.resources.onb_same_pin_title
import io.bhodl.shared.resources.onb_verifying
import org.jetbrains.compose.resources.stringResource

/**
 * Phase 3.2-F — promote a watch-only wallet to a signing wallet by
 * pairing it with its original seed.
 *
 * Multi-step flow per the 2026-05-12 security review (§F2):
 *
 * 1. **Device PIN re-auth**: defends against the social-engineering
 *    scenario where an attacker with a momentarily-unlocked app
 *    triggers the highest-consequence operation in the flow.
 * 2. **Descriptor verification preview**: shows the first three
 *    receive addresses derived from the watch wallet's stored
 *    descriptor BEFORE the user types their seed, so they can
 *    refuse if anything looks wrong.
 * 3. **Seed entry**: BIP-39 mnemonic + optional passphrase. Derive
 *    the account xpub locally and compare against the registry's
 *    stored descriptor.
 * 4. **Wallet PIN**: pick the per-wallet PIN for the signing
 *    wallet. Same-PIN-vs-device warning surfaces here too.
 *
 * On final submit, the FFI sequence is:
 * - Drop the open `WatchHandle` (file lock release).
 * - Remove the registry row + delete the `watch-state/<uuid>.sqlite`.
 * - Create a `WalletHandle` under `wallets/<uuid>/` with the seed.
 * - Add a new registry row with the same UUID under
 *   `kind = SIGNING` and the freshly-derived public descriptor.
 * - Put the handle into `WalletStore` and navigate to Dashboard —
 *   the user is now signed in to the freshly-promoted wallet.
 *
 * UUID continuity is intentional: BIP-329 labels for the wallet
 * stay associated with the same identity, future PaymentEvent
 * history (Phase 3.3) preserves cross-references, and Tracking
 * does not see a "deleted then re-added" wallet.
 */
@Composable
public fun PromoteWatchScreen(
    walletId: String,
    onPromoted: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(PromoteStep.DevicePin) }

    // Loaded once at entry: the watch wallet's record (for label +
    // network + descriptor + kind) and the first three receive
    // addresses to show in the verification preview.
    var record by remember { mutableStateOf<WalletRecordFfi?>(null) }
    var previewAddresses by remember { mutableStateOf<List<String>>(emptyList()) }
    var bootstrapError: String? by remember { mutableStateOf(null) }

    LaunchedEffect(walletId) {
        runCatching {
            withContext(Dispatchers.IO) {
                val registry = RegistryStore.current()
                    ?: error("Registry not open")
                val row = registry.getWallet(walletId)
                    ?: error("Wallet $walletId not found")
                val handle = registry.openWatch(walletId)
                val addresses = (0u until 3u).map { i -> handle.peekAddressAt(i) }
                handle.destroy()
                row to addresses
            }
        }.fold(
            onSuccess = { (row, addrs) ->
                record = row
                previewAddresses = addrs
            },
            onFailure = { e -> bootstrapError = e.userMessage(localizedText(Res.string.onb_promote_load_failed)) },
        )
    }

    var devicePin by remember { mutableStateOf("") }
    var devicePinError: String? by remember { mutableStateOf(null) }
    var devicePinWorking by remember { mutableStateOf(false) }

    var mnemonic by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var showAdvanced by remember { mutableStateOf(false) }
    var seedError: String? by remember { mutableStateOf(null) }
    var seedWorking by remember { mutableStateOf(false) }

    var walletPin by remember { mutableStateOf("") }
    var walletPinConfirm by remember { mutableStateOf("") }
    // Audit 2026-10 — without an OS keystore the new signing wallet
    // needs a passphrase (12+ chars) instead of a 6-digit PIN.
    var keystoreAvailable: Boolean? by remember { mutableStateOf(null) }
    LaunchedEffect(Unit) {
        keystoreAvailable = withContext(Dispatchers.IO) {
            io.bhodl.desktop.wizard.DeviceKeystoreInfo.status().available
        }
    }
    val walletSecretMode = PinValidator.inferMode(walletPin, keystoreAvailable != false)
    var promoteError: String? by remember { mutableStateOf(null) }
    var promoteWorking by remember { mutableStateOf(false) }
    var pendingSamePinWarning by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BhodlTheme.colors.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .widthIn(max = 560.dp)
                .padding(horizontal = 32.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BhodlTopBar(
                title = stringResource(Res.string.onb_promote_title, record?.label ?: ""),
                onBack = onBack,
            )
            Text(
                text = stringResource(Res.string.onb_promote_intro),
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )
            if (bootstrapError != null) {
                Text(
                    text = bootstrapError!!,
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.error,
                    textAlign = TextAlign.Center,
                )
                return@Column
            }
            val loadedRecord = record ?: run {
                Text(
                    text = stringResource(Res.string.common_loading),
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textTertiary,
                )
                return@Column
            }

            when (step) {
                PromoteStep.DevicePin -> DevicePinStep(
                    pin = devicePin,
                    onPinChange = { devicePin = it.filter { c -> !c.isWhitespace() } },
                    error = devicePinError,
                    working = devicePinWorking,
                    onSubmit = {
                        if (devicePinWorking || devicePin.isBlank()) return@DevicePinStep
                        // Audit 2026-10 — re-auth goes through the shared
                        // device-PIN lockout (was an uncounted oracle).
                        io.bhodl.desktop.wizard.PinAttemptTracker
                            .lockoutMessage(io.bhodl.desktop.wizard.PinScopes.DEVICE)?.let {
                                devicePinError = it
                                return@DevicePinStep
                            }
                        devicePinWorking = true
                        devicePinError = null
                        val attempted = devicePin
                        scope.launch {
                            // Typed outcome: only a checked-and-rejected PIN
                            // counts towards the lockout; a storage / KDF
                            // failure shows its own message, uncounted.
                            val check = withContext(Dispatchers.IO) {
                                io.bhodl.desktop.wizard.DevicePinSession.verify(attempted) {
                                    RegistryStore.current()?.matchesDevicePin(it)
                                        ?: error("Registry not open")
                                }
                            }
                            devicePinWorking = false
                            when (check) {
                                io.bhodl.desktop.wizard.DevicePinCheck.Match -> {
                                    io.bhodl.desktop.wizard.PinAttemptTracker
                                        .recordSuccess(io.bhodl.desktop.wizard.PinScopes.DEVICE)
                                    devicePin = ""  // erase from memory
                                    step = PromoteStep.Verify
                                }
                                io.bhodl.desktop.wizard.DevicePinCheck.WrongPin -> {
                                    io.bhodl.desktop.wizard.PinAttemptTracker
                                        .recordFailure(io.bhodl.desktop.wizard.PinScopes.DEVICE)
                                    devicePinError = io.bhodl.desktop.wizard.PinAttemptTracker
                                        .lockoutMessage(io.bhodl.desktop.wizard.PinScopes.DEVICE)
                                        ?: localizedText(Res.string.onb_device_pin_wrong)
                                }
                                is io.bhodl.desktop.wizard.DevicePinCheck.StorageError -> {
                                    devicePinError = check.message
                                }
                            }
                        }
                    },
                )
                PromoteStep.Verify -> VerifyStep(
                    addresses = previewAddresses,
                    onConfirm = { step = PromoteStep.Seed },
                    onCancel = onBack,
                )
                PromoteStep.Seed -> SeedStep(
                    mnemonic = mnemonic,
                    onMnemonicChange = { mnemonic = it },
                    passphrase = passphrase,
                    onPassphraseChange = { passphrase = it },
                    showAdvanced = showAdvanced,
                    onToggleAdvanced = { showAdvanced = !showAdvanced },
                    error = seedError,
                    working = seedWorking,
                    onSubmit = {
                        if (seedWorking || mnemonic.isBlank()) return@SeedStep
                        seedWorking = true
                        seedError = null
                        scope.launch {
                            val match = runCatching {
                                withContext(Dispatchers.IO) {
                                    val network = parseNetwork(loadedRecord.network)
                                    val derivedXpub = WalletHandle
                                        .fromMnemonic(
                                            network = network,
                                            mnemonic = mnemonic.trim(),
                                            passphrase = passphrase,
                                        )
                                        .let { handle ->
                                            val descriptor = handle
                                                .exportPublicDescriptor()
                                            handle.destroy()
                                            descriptor.accountXpub
                                        }
                                    val storedXpub = io.bhodl.ffi
                                        .extractAccountXpub(loadedRecord.descriptorPub)
                                    derivedXpub == storedXpub
                                }
                            }.getOrElse { e ->
                                seedWorking = false
                                seedError = e.userMessage(localizedText(Res.string.onb_promote_seed_verify_failed))
                                return@launch
                            }
                            seedWorking = false
                            if (match) {
                                step = PromoteStep.WalletPin
                            } else {
                                seedError = localizedText(Res.string.onb_promote_seed_mismatch)
                            }
                        }
                    },
                )
                PromoteStep.WalletPin -> WalletPinStep(
                    pin = walletPin,
                    onPinChange = { walletPin = it.filter { c -> !c.isWhitespace() } },
                    confirm = walletPinConfirm,
                    onConfirmChange = { walletPinConfirm = it.filter { c -> !c.isWhitespace() } },
                    error = promoteError,
                    working = promoteWorking || keystoreAvailable == null,
                    mode = walletSecretMode,
                    keystoreAvailable = keystoreAvailable != false,
                    onSubmit = {
                        if (promoteWorking || keystoreAvailable == null) return@WalletPinStep
                        val validation =
                            PinValidator.validatePair(walletPin, walletPinConfirm, walletSecretMode)
                        if (validation !is PinPairValidation.Valid) return@WalletPinStep
                        promoteWorking = true
                        promoteError = null
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    performPromote(
                                        record = loadedRecord,
                                        mnemonic = mnemonic.trim(),
                                        passphrase = passphrase,
                                        walletPin = walletPin,
                                    )
                                }
                            }.fold(
                                onSuccess = { promotedHandle ->
                                    val network = parseNetwork(loadedRecord.network)
                                    WalletStore.set(
                                        promotedHandle,
                                        network,
                                        walletId = loadedRecord.id,
                                    )
                                    // Same-PIN check on the wallet PIN.
                                    val samePin = runCatching {
                                        withContext(Dispatchers.IO) {
                                            RegistryStore.current()
                                                ?.matchesDevicePin(walletPin) == true
                                        }
                                    }.getOrDefault(false)
                                    if (samePin) {
                                        runCatching {
                                            RegistryStore.current()
                                                ?.markPinsWarned(loadedRecord.id)
                                        }
                                        pendingSamePinWarning = true
                                    } else {
                                        promoteWorking = false
                                        onPromoted()
                                    }
                                },
                                onFailure = { e ->
                                    promoteWorking = false
                                    promoteError = e.userMessage(localizedText(Res.string.onb_promote_failed))
                                },
                            )
                        }
                    },
                )
            }
        }

        // Same-PIN warning interstitial (Phase 3.2-H semantics extended
        // to promote flow). Shown after a successful promote, then
        // proceeds to Dashboard.
        if (pendingSamePinWarning) {
            AlertDialog(
                onDismissRequest = {
                    pendingSamePinWarning = false
                    promoteWorking = false
                    onPromoted()
                },
                title = { Text(stringResource(Res.string.onb_same_pin_title)) },
                text = {
                    Text(
                        stringResource(Res.string.onb_promote_same_pin_body),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        pendingSamePinWarning = false
                        promoteWorking = false
                        onPromoted()
                    }) { Text(stringResource(Res.string.onb_i_understand)) }
                },
            )
        }
    }
}

@Composable
private fun DevicePinStep(
    pin: String,
    onPinChange: (String) -> Unit,
    error: String?,
    working: Boolean,
    onSubmit: () -> Unit,
) {
    Text(
        text = stringResource(Res.string.onb_promote_pin_title),
        style = BhodlTheme.typography.titleMedium,
        color = BhodlTheme.colors.textPrimary,
    )
    Text(
        text = stringResource(Res.string.onb_promote_pin_body),
        style = BhodlTheme.typography.bodyMedium,
        color = BhodlTheme.colors.textSecondary,
        textAlign = TextAlign.Center,
    )
    OutlinedTextField(
        value = pin,
        onValueChange = onPinChange,
        label = { Text(stringResource(Res.string.onb_device_pin)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.NumberPassword,
            imeAction = ImeAction.Done,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(PromoteWatchTags.DEVICE_PIN),
    )
    if (error != null) {
        Text(
            text = error,
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.error,
        )
    }
    Button(
        onClick = onSubmit,
        enabled = pin.isNotBlank() && !working,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(PromoteWatchTags.DEVICE_PIN_SUBMIT),
    ) {
        Text(if (working) stringResource(Res.string.onb_verifying) else stringResource(Res.string.onb_continue))
    }
}

@Composable
private fun VerifyStep(
    addresses: List<String>,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Text(
        text = stringResource(Res.string.onb_promote_verify_title),
        style = BhodlTheme.typography.titleMedium,
        color = BhodlTheme.colors.textPrimary,
    )
    Text(
        text = stringResource(Res.string.onb_promote_verify_body),
        style = BhodlTheme.typography.bodyMedium,
        color = BhodlTheme.colors.textSecondary,
        textAlign = TextAlign.Center,
    )
    addresses.forEachIndexed { idx, addr ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(BhodlTheme.colors.surface)
                .border(1.dp, BhodlTheme.colors.outline, RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(Res.string.onb_promote_address, idx),
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.textSecondary,
            )
            Text(
                text = addr,
                style = BhodlTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = BhodlTheme.colors.textPrimary,
            )
        }
    }
    Button(
        onClick = onConfirm,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(PromoteWatchTags.VERIFY_OK),
    ) { Text(stringResource(Res.string.onb_promote_addresses_ok)) }
    OutlinedButton(
        onClick = onCancel,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(PromoteWatchTags.VERIFY_CANCEL),
    ) { Text(stringResource(Res.string.onb_promote_addresses_wrong)) }
}

@Composable
private fun SeedStep(
    mnemonic: String,
    onMnemonicChange: (String) -> Unit,
    passphrase: String,
    onPassphraseChange: (String) -> Unit,
    showAdvanced: Boolean,
    onToggleAdvanced: () -> Unit,
    error: String?,
    working: Boolean,
    onSubmit: () -> Unit,
) {
    Text(
        text = stringResource(Res.string.onb_promote_seed_title),
        style = BhodlTheme.typography.titleMedium,
        color = BhodlTheme.colors.textPrimary,
    )
    Text(
        text = stringResource(Res.string.onb_promote_seed_body),
        style = BhodlTheme.typography.bodyMedium,
        color = BhodlTheme.colors.textSecondary,
        textAlign = TextAlign.Center,
    )
    OutlinedTextField(
        value = mnemonic,
        onValueChange = onMnemonicChange,
        label = { Text(stringResource(Res.string.onb_restore_phrase_label)) },
        singleLine = false,
        minLines = 3,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(PromoteWatchTags.MNEMONIC),
    )
    TextButton(
        onClick = onToggleAdvanced,
        modifier = Modifier.testTag(PromoteWatchTags.ADVANCED_TOGGLE),
    ) {
        Text(
            if (showAdvanced) stringResource(Res.string.onb_hide_advanced)
            else stringResource(Res.string.onb_advanced_passphrase),
        )
    }
    if (showAdvanced) {
        OutlinedTextField(
            value = passphrase,
            onValueChange = onPassphraseChange,
            label = { Text(stringResource(Res.string.onb_passphrase_label)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(PromoteWatchTags.PASSPHRASE),
        )
    }
    if (error != null) {
        Text(
            text = error,
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.error,
        )
    }
    Button(
        onClick = onSubmit,
        enabled = mnemonic.isNotBlank() && !working,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(PromoteWatchTags.SEED_SUBMIT),
    ) {
        Text(if (working) stringResource(Res.string.onb_verifying) else stringResource(Res.string.onb_promote_verify_seed))
    }
}

@Composable
private fun WalletPinStep(
    pin: String,
    onPinChange: (String) -> Unit,
    confirm: String,
    onConfirmChange: (String) -> Unit,
    error: String?,
    working: Boolean,
    mode: io.bhodl.desktop.wizard.SecretMode,
    keystoreAvailable: Boolean,
    onSubmit: () -> Unit,
) {
    val validation = PinValidator.validatePair(pin, confirm, mode)
    val helper = when {
        confirm.isEmpty() -> null
        validation == PinPairValidation.Valid -> null
        validation == PinPairValidation.Mismatch -> stringResource(Res.string.onb_pins_mismatch)
        mode == io.bhodl.desktop.wizard.SecretMode.PASSPHRASE -> stringResource(
            Res.string.onb_passphrase_body,
            PinValidator.MIN_PASSPHRASE_LENGTH,
        )
        else -> stringResource(Res.string.onb_pin_invalid)
    }
    if (!keystoreAvailable) {
        Text(
            text = stringResource(Res.string.onb_keystore_unavailable, PinValidator.MIN_PASSPHRASE_LENGTH),
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.error,
            textAlign = TextAlign.Center,
        )
    }
    Text(
        text = stringResource(Res.string.onb_promote_wallet_pin_title),
        style = BhodlTheme.typography.titleMedium,
        color = BhodlTheme.colors.textPrimary,
    )
    Text(
        text = stringResource(Res.string.onb_promote_wallet_pin_body),
        style = BhodlTheme.typography.bodyMedium,
        color = BhodlTheme.colors.textSecondary,
        textAlign = TextAlign.Center,
    )
    OutlinedTextField(
        value = pin,
        onValueChange = onPinChange,
        label = { Text(stringResource(Res.string.onb_pin_label)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Next,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(PromoteWatchTags.WALLET_PIN),
    )
    OutlinedTextField(
        value = confirm,
        onValueChange = onConfirmChange,
        label = { Text(stringResource(Res.string.onb_confirm_pin)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(PromoteWatchTags.WALLET_PIN_CONFIRM),
    )
    if (helper != null) {
        Text(
            text = helper,
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textSecondary,
        )
    }
    if (error != null) {
        Text(
            text = error,
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.error,
        )
    }
    Button(
        onClick = onSubmit,
        enabled = validation == PinPairValidation.Valid && !working,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(PromoteWatchTags.WALLET_PIN_SUBMIT),
    ) {
        Text(if (working) stringResource(Res.string.onb_promoting) else stringResource(Res.string.onb_promote_submit))
    }
}

private enum class PromoteStep { DevicePin, Verify, Seed, WalletPin }

private fun parseNetwork(s: String): Network = when (s) {
    "mainnet" -> Network.MAINNET
    "signet" -> Network.SIGNET
    "testnet4" -> Network.TESTNET4
    "regtest" -> Network.REGTEST
    else -> Network.SIGNET
}

/**
 * Atomic-ish promote sequence. Runs on `Dispatchers.IO`.
 *
 * Failure modes documented inline. The watch entry is removed
 * BEFORE the signing wallet is created so a crash between steps
 * leaves a re-bootable state (the original watch can be re-added
 * via "Add watch-only" from Welcome; the seed has not been lost
 * because the user typed it locally).
 */
private suspend fun performPromote(
    record: WalletRecordFfi,
    mnemonic: String,
    passphrase: String,
    walletPin: String,
): WalletHandle {
    val registry = RegistryStore.current() ?: error("Registry not open")
    val dataDir = WalletPaths.defaultDataDir()

    // 1. Release any open WatchHandle for this wallet so the
    //    SQLCipher file lock on watch-state/<uuid>.sqlite drops.
    if (WatchStore.activeWatchId() == record.id) {
        WatchStore.clear()
    }

    // 2. Drop the watch entry from the registry + erase its state
    //    file from disk.
    registry.removeWallet(record.id)
    val watchStateFile = java.io.File(
        java.io.File(dataDir, "watch-state"),
        "${record.id}.sqlite",
    )
    runCatching { watchStateFile.delete() }

    // 3. Create the signing wallet under wallets/<existing-uuid>/.
    //    Keeping the UUID preserves BIP-329 label associations and
    //    PaymentEvent cross-references for Phase 3.3.
    val walletsRoot = java.io.File(dataDir, "wallets")
    walletsRoot.mkdirs()
    val walletDir = java.io.File(walletsRoot, record.id)
    walletDir.mkdirs()
    // Security review — owner-only perms (700), best-effort on POSIX.
    WalletPaths.restrictToOwner(walletsRoot)
    WalletPaths.restrictToOwner(walletDir)
    val handle = WalletHandle.createOrLoadPersistent(
        dbDir = walletDir.absolutePath,
        network = parseNetwork(record.network),
        mnemonic = mnemonic,
        passphrase = passphrase,
        pin = walletPin,
    )

    // 4. Re-register the wallet under the same UUID with SIGNING kind
    //    and the secret-rooted descriptor's public projection.
    val descriptor = handle.exportPublicDescriptor()
    registry.addWallet(
        WalletRecordFfi(
            id = record.id,
            label = record.label,
            kind = WalletKindFfi.SIGNING,
            descriptorPub = descriptor.externalDescriptor,
            network = record.network,
            createdAtUnix = System.currentTimeMillis() / 1000,
            pinsWarned = record.pinsWarned,
        ),
    )
    io.bhodl.desktop.wizard.RegistryStore.bumpDataVersion()

    return handle
}

public object PromoteWatchTags {
    public const val DEVICE_PIN: String = "promote-watch.device-pin"
    public const val DEVICE_PIN_SUBMIT: String = "promote-watch.device-pin-submit"
    public const val VERIFY_OK: String = "promote-watch.verify-ok"
    public const val VERIFY_CANCEL: String = "promote-watch.verify-cancel"
    public const val MNEMONIC: String = "promote-watch.mnemonic"
    public const val ADVANCED_TOGGLE: String = "promote-watch.advanced-toggle"
    public const val PASSPHRASE: String = "promote-watch.passphrase"
    public const val SEED_SUBMIT: String = "promote-watch.seed-submit"
    public const val WALLET_PIN: String = "promote-watch.wallet-pin"
    public const val WALLET_PIN_CONFIRM: String = "promote-watch.wallet-pin-confirm"
    public const val WALLET_PIN_SUBMIT: String = "promote-watch.wallet-pin-submit"
}
