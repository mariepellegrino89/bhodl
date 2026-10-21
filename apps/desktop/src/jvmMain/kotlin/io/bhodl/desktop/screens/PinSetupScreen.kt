package io.bhodl.desktop.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.ui.BhodlBackArrow
import io.bhodl.desktop.wallet.NetworkPersistence
import io.bhodl.desktop.wizard.PinPairValidation
import io.bhodl.desktop.wizard.PinStrength
import io.bhodl.desktop.wizard.PinValidation
import io.bhodl.desktop.wizard.PinValidator
import io.bhodl.desktop.wizard.DeviceKeystoreInfo
import io.bhodl.desktop.wizard.DevicePinSession
import io.bhodl.desktop.wizard.KeystoreAvailability
import io.bhodl.desktop.wizard.PinAttemptTracker
import io.bhodl.desktop.wizard.SecretMode
import io.bhodl.desktop.wizard.PinErrors
import io.bhodl.desktop.wizard.PinFailureKind
import io.bhodl.desktop.wizard.PinScopes
import io.bhodl.desktop.wizard.RegistryStore
import io.bhodl.desktop.wizard.WalletPaths
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.desktop.wizard.localizedText
import io.bhodl.ffi.Network
import io.bhodl.desktop.ui.userMessage
import io.bhodl.ffi.WalletException
import io.bhodl.ffi.WalletHandle
import io.bhodl.ui.theme.BhodlTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.action_back
import io.bhodl.shared.resources.common_cancel
import io.bhodl.shared.resources.onb_add_to_tracker
import io.bhodl.shared.resources.onb_add_to_tracker_hint
import io.bhodl.shared.resources.onb_adding
import io.bhodl.shared.resources.onb_advanced_passphrase
import io.bhodl.shared.resources.onb_confirm_pin
import io.bhodl.shared.resources.onb_continue_without_tor
import io.bhodl.shared.resources.onb_creating_wallet
import io.bhodl.shared.resources.onb_default_wallet_name
import io.bhodl.shared.resources.onb_device_pin
import io.bhodl.shared.resources.onb_device_pin_wrong
import io.bhodl.shared.resources.onb_finish_setup
import io.bhodl.shared.resources.onb_hide_advanced
import io.bhodl.shared.resources.onb_i_understand
import io.bhodl.shared.resources.onb_no_tor_body
import io.bhodl.shared.resources.onb_no_tor_title
import io.bhodl.shared.resources.onb_open_registry
import io.bhodl.shared.resources.onb_open_registry_first
import io.bhodl.shared.resources.onb_opening
import io.bhodl.shared.resources.onb_passphrase_confirm
import io.bhodl.shared.resources.onb_passphrase_hint
import io.bhodl.shared.resources.onb_passphrase_label
import io.bhodl.shared.resources.onb_passphrases_mismatch
import io.bhodl.shared.resources.onb_passphrases_mismatch_closed
import io.bhodl.shared.resources.onb_pin_body
import io.bhodl.shared.resources.onb_pin_empty
import io.bhodl.shared.resources.onb_pin_label
import io.bhodl.shared.resources.onb_pin_non_numeric
import io.bhodl.shared.resources.onb_pin_strength_moderate
import io.bhodl.shared.resources.onb_pin_title
import io.bhodl.shared.resources.onb_pin_too_long
import io.bhodl.shared.resources.onb_pin_too_short
import io.bhodl.shared.resources.onb_pins_mismatch
import io.bhodl.shared.resources.onb_registry_locked
import io.bhodl.shared.resources.onb_registry_open_failed
import io.bhodl.shared.resources.onb_same_pin_body
import io.bhodl.shared.resources.onb_same_pin_title
import io.bhodl.shared.resources.onb_wallet_create_failed
import io.bhodl.shared.resources.onb_wallet_name
import io.bhodl.shared.resources.onb_wallet_name_taken
import io.bhodl.shared.resources.onb_keystore_bound_hint
import io.bhodl.shared.resources.onb_keystore_unavailable
import io.bhodl.shared.resources.onb_passphrase_body
import io.bhodl.shared.resources.onb_passphrase_title
import io.bhodl.shared.resources.onb_secret_passphrase_confirm
import io.bhodl.shared.resources.onb_secret_passphrase_empty
import io.bhodl.shared.resources.onb_secret_passphrase_label
import io.bhodl.shared.resources.onb_secret_passphrase_too_long
import io.bhodl.shared.resources.onb_secret_passphrase_too_short
import io.bhodl.shared.resources.onb_secret_passphrase_whitespace
import io.bhodl.shared.resources.onb_use_passphrase
import io.bhodl.shared.resources.onb_use_pin
import org.jetbrains.compose.resources.stringResource

/**
 * Wizard step 4: collect the PIN, derive the SQLCipher key via
 * [`WalletHandle.createOrLoadPersistent`], stash the resulting handle
 * in [`WalletStore`], and hand off to the dashboard.
 *
 * `mnemonic` arrives via the route argument from `ConfirmMnemonic`. We
 * pin the network to Signet for the Phase-2 alpha — wiring the network
 * picker is a separate commit once we have a Settings screen.
 */
@Composable
public fun PinSetupScreen(
    mnemonic: String,
    onSetupComplete: () -> Unit,
    onBack: () -> Unit,
    initialPassphrase: String = "",
    isRestore: Boolean = false,
    network: Network = Network.SIGNET,
    /** OS-keystore probe; tests inject a fixed answer. */
    keystoreProbe: suspend () -> KeystoreAvailability = {
        withContext(Dispatchers.IO) { DeviceKeystoreInfo.status() }
    },
) {
    val scope = rememberCoroutineScope()

    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    // User-chosen wallet label — visible in the Welcome picker and
    // the Settings rename section. Default lifted into the input so
    // the field is never blank (the validator below also enforces
    // non-blank).
    val defaultWalletName = stringResource(Res.string.onb_default_wallet_name)
    var walletName by remember { mutableStateOf(defaultWalletName) }
    // The Restore-from-backup path forwards a recovered passphrase
    // here; it pre-populates the field and auto-opens Advanced so
    // the user does not have to remember to look for it.
    var passphrase by remember { mutableStateOf(initialPassphrase) }
    // Audit 2026-10 — the BIP-39 passphrase must be typed twice: a typo
    // here silently derives a different (empty) wallet that the user
    // cannot recover later. A passphrase recovered from a backup is
    // pre-confirmed.
    var passphraseConfirm by remember { mutableStateOf(initialPassphrase) }
    var showAdvanced by remember { mutableStateOf(initialPassphrase.isNotEmpty()) }
    // Audit 2026-10 (H3) — inline device-PIN gate for a closed registry.
    var registryGateVisible by remember { mutableStateOf(false) }
    var gateDevicePin by remember { mutableStateOf("") }
    var gateWorking by remember { mutableStateOf(false) }
    var gateError: String? by remember { mutableStateOf(null) }
    var working by remember { mutableStateOf(false) }
    var lastError: String? by remember { mutableStateOf(null) }

    // Phase 3.2-B+ — pending "mirror as watch?" prompt. Holds the
    // freshly-created handle + dir so the post-create dialog can call
    // `exportPublicDescriptor()` for the xpub without re-opening the
    // wallet. Set right before onSetupComplete; the dialog itself
    // navigates away (either Skip or Add-then-Skip).
    var pendingMirrorPrompt by remember {
        mutableStateOf<MirrorPromptState?>(null)
    }
    // Mainnet-without-Tor confirmation interstitial between
    // "Add to tracking" → actual `addWatchXpub`. Mirrors the
    // AddWatchScreen pattern.
    var pendingMainnetConfirm by remember { mutableStateOf(false) }
    // Phase 3.2-H — set when the wallet PIN matches the device PIN.
    // Renders a one-tap "use a different PIN per wallet" dialog
    // before the mirror prompt. `pins_warned` is flipped to `true`
    // in the registry as a side effect so the warning never repeats
    // for this wallet.
    var pendingSamePinWarning by remember { mutableStateOf(false) }
    // Phase 3.2-B+ — gates the mirror prompt's "Add to tracking"
    // button while the async work runs (open watch handle, register
    // row, initial Esplora sync). Without it the user could tap
    // multiple times during the 5–10s sync window and create
    // duplicate watch rows (found in manual testing, 2026-05-14).
    var mirrorAddWorking by remember { mutableStateOf(false) }
    // Phase 6.0 — checkbox controlling the auto-pairing flow. ON by
    // default for BOTH create and restore (design decision, 2026-05-18): the
    // expected behaviour is "I want the Tracker to see this wallet";
    // requiring an extra tap each time would be friction. Power
    // users can untick to set up the signing wallet without exposing
    // its xpub to the public Esplora server (privacy-first stance).
    // When checked, the post-create flow auto-adds the watch mirror
    // unless a mainnet-without-Tor warning intercepts.
    var addToTracker by remember { mutableStateOf(true) }

    // Security audit 2026-10 — OS-keystore device binding. `null` while
    // the (off-thread) probe runs; Finish stays disabled until it is
    // known, so a PIN is never submitted to a device without a keystore.
    var keystore: KeystoreAvailability? by remember { mutableStateOf(null) }
    // Opt-in alphanumeric passphrase instead of the 6-digit PIN. Forced
    // (and the toggle hidden) when no keystore is available.
    var preferPassphrase by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        keystore = keystoreProbe()
    }
    val secretMode = PinValidator.modeFor(
        keystoreAvailable = keystore?.available != false,
        preferPassphrase = preferPassphrase,
    )
    val isPassphrase = secretMode == SecretMode.PASSPHRASE

    // Auto-focus the first PIN field on screen entry — finding #12.
    val pinFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { pinFocus.requestFocus() }
    }

    val pairValidation = PinValidator.validatePair(pin, confirm, secretMode)
    val pairValidationVisible = confirm.isNotEmpty()

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
                text = "BHODL",
                style = BhodlTheme.typography.headlineMedium,
                color = BhodlTheme.colors.brandPrimary,
            )
            Text(
                text = if (isPassphrase) stringResource(Res.string.onb_passphrase_title)
                else stringResource(Res.string.onb_pin_title),
                style = BhodlTheme.typography.titleMedium,
                color = BhodlTheme.colors.textPrimary,
            )
            Text(
                text = if (isPassphrase) {
                    stringResource(Res.string.onb_passphrase_body, PinValidator.MIN_PASSPHRASE_LENGTH)
                } else {
                    stringResource(Res.string.onb_pin_body, PinValidator.MIN_LENGTH)
                },
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )
            // Why a passphrase is mandatory here / what the PIN relies on.
            when (val ks = keystore) {
                null -> {}
                else -> if (!ks.available) {
                    Text(
                        text = stringResource(
                            Res.string.onb_keystore_unavailable,
                            PinValidator.MIN_PASSPHRASE_LENGTH,
                        ),
                        style = BhodlTheme.typography.bodyMedium,
                        color = BhodlTheme.colors.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag(PinSetupTags.KEYSTORE_UNAVAILABLE),
                    )
                } else {
                    Text(
                        text = stringResource(Res.string.onb_keystore_bound_hint, ks.backend),
                        style = BhodlTheme.typography.labelMedium,
                        color = BhodlTheme.colors.textTertiary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag(PinSetupTags.KEYSTORE_HINT),
                    )
                }
            }

            OutlinedTextField(
                value = walletName,
                onValueChange = { walletName = it },
                label = { Text(stringResource(Res.string.onb_wallet_name)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(PinSetupTags.WALLET_NAME),
            )

            // Phase 6.0 — opt-out checkbox for the Tracker pairing.
            // Whole row is clickable so the touch target is generous
            // (Material 3 list-item convention).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { addToTracker = !addToTracker }
                    .padding(vertical = 4.dp)
                    .testTag(PinSetupTags.ADD_TO_TRACKER),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = addToTracker,
                    onCheckedChange = { addToTracker = it },
                )
                Column(modifier = Modifier.padding(start = 4.dp)) {
                    Text(
                        text = stringResource(Res.string.onb_add_to_tracker),
                        style = BhodlTheme.typography.bodyMedium,
                        color = BhodlTheme.colors.textPrimary,
                    )
                    Text(
                        text = stringResource(Res.string.onb_add_to_tracker_hint),
                        style = BhodlTheme.typography.labelMedium,
                        color = BhodlTheme.colors.textTertiary,
                    )
                }
            }

            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it.filter { c -> !c.isWhitespace() } },
                label = {
                    Text(
                        if (isPassphrase) stringResource(Res.string.onb_secret_passphrase_label)
                        else stringResource(Res.string.onb_pin_label),
                    )
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (isPassphrase) KeyboardType.Password else KeyboardType.NumberPassword,
                    imeAction = ImeAction.Next,
                ),
                // Security review — graded, non-blocking strength hint
                // (PIN mode only). The keystore binding already defeats
                // an attacker holding only the files; 8+ digits also hold
                // up against one who has the OS account too.
                supportingText = when (PinValidator.strength(pin, secretMode).takeIf { !isPassphrase }) {
                    PinStrength.Moderate -> {
                        {
                            Text(
                                text = stringResource(Res.string.onb_pin_strength_moderate, pin.length),
                                style = BhodlTheme.typography.labelMedium,
                                color = BhodlTheme.colors.textTertiary,
                                modifier = Modifier.testTag(PinSetupTags.PIN_STRENGTH_HINT),
                            )
                        }
                    }
                    else -> null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(pinFocus)
                    .testTag(PinSetupTags.PIN),
            )
            OutlinedTextField(
                value = confirm,
                onValueChange = { confirm = it.filter { c -> !c.isWhitespace() } },
                label = {
                    Text(
                        if (isPassphrase) stringResource(Res.string.onb_secret_passphrase_confirm)
                        else stringResource(Res.string.onb_confirm_pin),
                    )
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (isPassphrase) KeyboardType.Password else KeyboardType.NumberPassword,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(PinSetupTags.CONFIRM_PIN),
            )
            // PIN ⇄ passphrase switch. Hidden when no keystore exists:
            // then the passphrase is mandatory.
            if (keystore?.available == true) {
                TextButton(
                    onClick = {
                        preferPassphrase = !preferPassphrase
                        pin = ""
                        confirm = ""
                    },
                    modifier = Modifier.testTag(PinSetupTags.SECRET_MODE_TOGGLE),
                ) {
                    Text(
                        if (preferPassphrase) stringResource(Res.string.onb_use_pin)
                        else stringResource(Res.string.onb_use_passphrase),
                    )
                }
            }

            // Advanced section: BIP-39 passphrase ("13th word"). Hidden
            // behind a toggle so the typical user is not confronted with
            // a field they will not understand. Power users opt in
            // explicitly and the passphrase is sealed into the encrypted
            // SQLCipher store alongside the mnemonic, used at every
            // unlock to reconstruct the wallet.
            androidx.compose.material3.TextButton(
                onClick = { showAdvanced = !showAdvanced },
                modifier = Modifier.testTag(PinSetupTags.ADVANCED_TOGGLE),
            ) {
                Text(
                    if (showAdvanced) stringResource(Res.string.onb_hide_advanced)
                    else stringResource(Res.string.onb_advanced_passphrase),
                )
            }
            if (showAdvanced) {
                Text(
                    text = stringResource(Res.string.onb_passphrase_hint),
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textTertiary,
                    textAlign = TextAlign.Center,
                )
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text(stringResource(Res.string.onb_passphrase_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(PinSetupTags.PASSPHRASE),
                )
                OutlinedTextField(
                    value = passphraseConfirm,
                    onValueChange = { passphraseConfirm = it },
                    label = { Text(stringResource(Res.string.onb_passphrase_confirm)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    isError = passphrase != passphraseConfirm,
                    supportingText = if (passphrase != passphraseConfirm) {
                        { Text(stringResource(Res.string.onb_passphrases_mismatch)) }
                    } else {
                        null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(PinSetupTags.PASSPHRASE_CONFIRM),
                )
            } else if (passphrase != passphraseConfirm) {
                Text(
                    text = stringResource(Res.string.onb_passphrases_mismatch_closed),
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.error,
                    textAlign = TextAlign.Center,
                )
            }

            val helper = when {
                lastError != null -> lastError
                pairValidationVisible -> when (pairValidation) {
                    PinPairValidation.Valid -> null
                    PinPairValidation.Mismatch ->
                        if (isPassphrase) stringResource(Res.string.onb_passphrases_mismatch)
                        else stringResource(Res.string.onb_pins_mismatch)
                    is PinPairValidation.PinInvalid -> when (val r = pairValidation.reason) {
                        PinValidation.Valid -> null
                        PinValidation.Empty ->
                            if (isPassphrase) stringResource(Res.string.onb_secret_passphrase_empty)
                            else stringResource(Res.string.onb_pin_empty)
                        is PinValidation.TooShort ->
                            if (isPassphrase) {
                                stringResource(Res.string.onb_secret_passphrase_too_short, r.actual, r.minimum)
                            } else {
                                stringResource(Res.string.onb_pin_too_short, r.actual, r.minimum)
                            }
                        is PinValidation.TooLong ->
                            if (isPassphrase) {
                                stringResource(Res.string.onb_secret_passphrase_too_long, r.actual, r.maximum)
                            } else {
                                stringResource(Res.string.onb_pin_too_long, r.actual, r.maximum)
                            }
                        PinValidation.NonNumeric -> stringResource(Res.string.onb_pin_non_numeric)
                        PinValidation.ContainsWhitespace ->
                            stringResource(Res.string.onb_secret_passphrase_whitespace)
                    }
                }
                else -> null
            }
            if (helper != null) {
                Text(
                    text = helper,
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }

            // Audit 2026-10 (H3) — an existing registry on disk that is
            // merely CLOSED must be opened with the real device PIN
            // first. Previously we called createOrLoad(devicePin =
            // walletPin) against it: an uncounted device-PIN oracle,
            // and on a mismatch the new wallet was silently left out
            // of the registry.
            if (registryGateVisible) {
                Text(
                    text = stringResource(Res.string.onb_registry_locked),
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
                OutlinedTextField(
                    value = gateDevicePin,
                    onValueChange = { gateDevicePin = it.filter { c -> !c.isWhitespace() } },
                    label = { Text(stringResource(Res.string.onb_device_pin)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(PinSetupTags.DEVICE_PIN_GATE),
                )
                if (gateError != null) {
                    Text(
                        text = gateError!!,
                        style = BhodlTheme.typography.bodyMedium,
                        color = BhodlTheme.colors.error,
                        textAlign = TextAlign.Center,
                    )
                }
                OutlinedButton(
                    onClick = {
                        if (gateWorking || gateDevicePin.isBlank()) return@OutlinedButton
                        PinAttemptTracker.lockoutMessage(PinScopes.DEVICE)?.let {
                            gateError = it
                            return@OutlinedButton
                        }
                        gateWorking = true
                        gateError = null
                        val attempted = gateDevicePin
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    io.bhodl.ffi.RegistryHandle.createOrLoad(
                                        dataDir = WalletPaths.defaultDataDir().absolutePath,
                                        devicePin = attempted,
                                    )
                                }
                            }.fold(
                                onSuccess = { handle ->
                                    PinAttemptTracker.recordSuccess(PinScopes.DEVICE)
                                    RegistryStore.set(handle)
                                    DevicePinSession.markUnlocked()
                                    io.bhodl.desktop.data.BackfillRunner
                                        .runIfNeeded(io.bhodl.desktop.AppScope.io)
                                    gateDevicePin = ""
                                    gateWorking = false
                                    registryGateVisible = false
                                    lastError = null
                                },
                                onFailure = { e ->
                                    gateWorking = false
                                    val kind = PinErrors.classify(e)
                                    gateError = when (kind) {
                                        PinFailureKind.WrongPin -> {
                                            PinAttemptTracker.recordFailure(PinScopes.DEVICE)
                                            PinAttemptTracker.lockoutMessage(PinScopes.DEVICE)
                                                ?: localizedText(Res.string.onb_device_pin_wrong)
                                        }
                                        PinFailureKind.Other ->
                                            e.userMessage(localizedText(Res.string.onb_registry_open_failed))
                                        else -> PinErrors.messageFor(kind)
                                    }
                                },
                            )
                        }
                    },
                    enabled = gateDevicePin.isNotBlank() && !gateWorking,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(PinSetupTags.DEVICE_PIN_GATE_SUBMIT),
                ) {
                    Text(
                        if (gateWorking) stringResource(Res.string.onb_opening)
                        else stringResource(Res.string.onb_open_registry),
                    )
                }
            }

            Button(
                onClick = {
                    if (working || keystore == null) return@Button
                    val validation = PinValidator.validatePair(pin, confirm, secretMode)
                    if (validation !is PinPairValidation.Valid) return@Button
                    if (passphrase != passphraseConfirm) return@Button
                    val dataDirView = WalletPaths.from(WalletPaths.defaultDataDir())
                    if (RegistryStore.current() == null && dataDirView.registryExists()) {
                        registryGateVisible = true
                        lastError = localizedText(Res.string.onb_open_registry_first)
                        return@Button
                    }
                    working = true
                    lastError = null
                    val walletPin = pin
                    val seedPassphrase = passphrase
                    val label = walletName.trim().ifBlank { defaultWalletName }
                    scope.launch {
                        // Pre-flight: reject a name that's already in
                        // the registry. Cheap SQLite read; runs off the
                        // UI thread alongside the wallet create below.
                        val duplicate = runCatching {
                            withContext(Dispatchers.IO) {
                                val registry = RegistryStore.current()
                                registry != null &&
                                    io.bhodl.desktop.wizard.WalletLabel
                                        .isDuplicate(registry, walletName)
                            }
                        }.getOrDefault(false)
                        if (duplicate) {
                            working = false
                            lastError = localizedText(Res.string.onb_wallet_name_taken)
                            return@launch
                        }
                        // Audit 2026-10 — every blocking FFI call (Argon2id
                        // derive in createOrLoad / createOrLoadPersistent,
                        // addWallet, matchesDevicePin, descriptor checks)
                        // runs on Dispatchers.IO; `working` drives the
                        // "Creating wallet…" progress label meanwhile.
                        runCatching {
                            withContext(Dispatchers.IO) {
                                createAndRegisterWallet(
                                    mnemonic = mnemonic,
                                    passphrase = seedPassphrase,
                                    pin = walletPin,
                                    network = network,
                                    label = label,
                                )
                            }
                        }.fold(
                            onSuccess = { outcome ->
                                val walletDir = outcome.walletDir
                                WalletStore.set(outcome.handle, network, walletId = walletDir.name)
                                if (isRestore) {
                                    // The Dashboard reads this and renders a
                                    // "Restoring wallet…" banner over the first
                                    // sync, which is a full chain scan.
                                    WalletStore.markJustRestored()
                                }
                                working = false
                                // Phase 3.2-H — same-PIN warning (computed
                                // off the UI thread in createAndRegisterWallet).
                                if (outcome.samePin) {
                                    pendingSamePinWarning = true
                                }
                                // Phase 6.0 — checkbox-driven pairing
                                // flow:
                                //   - addToTracker == false  → skip.
                                //   - already tracked → skip silently.
                                //   - mainnet without Tor → Tor warning.
                                //   - otherwise → auto-add via doMirrorAdd.
                                val state = MirrorPromptState(
                                    walletId = walletDir.name,
                                    walletLabel = label,
                                    network = network,
                                )
                                if (!addToTracker || outcome.alreadyTracked) {
                                    onSetupComplete()
                                } else {
                                    val mainnetNoTor =
                                        network == Network.MAINNET &&
                                            !io.bhodl.desktop.settings
                                                .SettingsStore.torEnabled.value
                                    if (mainnetNoTor) {
                                        // Privacy warning interstitial.
                                        pendingMirrorPrompt = state
                                        pendingMainnetConfirm = true
                                    } else {
                                        // Auto-add, no dialog — the
                                        // checkbox was the consent step.
                                        pendingMirrorPrompt = state
                                        mirrorAddWorking = true
                                        doMirrorAdd(scope, state) {
                                            mirrorAddWorking = false
                                            pendingMirrorPrompt = null
                                            onSetupComplete()
                                        }
                                    }
                                }
                            },
                            onFailure = { e ->
                                working = false
                                lastError = e.userMessage(localizedText(Res.string.onb_wallet_create_failed))
                            },
                        )
                    }
                },
                enabled = pairValidation == PinPairValidation.Valid &&
                    keystore != null &&
                    passphrase == passphraseConfirm &&
                    walletName.isNotBlank() &&
                    !working,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(PinSetupTags.FINISH),
            ) {
                Text(
                    if (working) stringResource(Res.string.onb_creating_wallet)
                    else stringResource(Res.string.onb_finish_setup),
                )
            }
            OutlinedButton(
                onClick = onBack,
                enabled = !working,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(PinSetupTags.BACK),
            ) {
                Text(stringResource(Res.string.action_back))
            }
        }

        // Phase 3.2-H — same-PIN warning. Shown BEFORE the mirror
        // prompt so the user reads it without other context.
        // Acknowledged with a single tap; we never block the
        // creation that already succeeded, only inform.
        if (pendingSamePinWarning) {
            AlertDialog(
                onDismissRequest = { pendingSamePinWarning = false },
                title = { Text(stringResource(Res.string.onb_same_pin_title)) },
                text = {
                    Text(stringResource(Res.string.onb_same_pin_body))
                },
                confirmButton = {
                    TextButton(onClick = { pendingSamePinWarning = false }) {
                        Text(stringResource(Res.string.onb_i_understand))
                    }
                },
            )
        }

        // Phase 6.0 — the standalone "Track this wallet?" opt-in
        // dialog was removed: the checkbox above is now the consent
        // step, and the post-create flow either auto-adds, skips, or
        // (mainnet-no-Tor) jumps straight to the Tor privacy warning
        // below. Pre-6.0 the dialog appeared between create and the
        // Dashboard; users on signet flipped through it every time.
        val prompt = pendingMirrorPrompt

        if (prompt != null && pendingMainnetConfirm) {
            AlertDialog(
                onDismissRequest = { pendingMainnetConfirm = false },
                title = { Text(stringResource(Res.string.onb_no_tor_title)) },
                text = {
                    Text(stringResource(Res.string.onb_no_tor_body))
                },
                confirmButton = {
                    TextButton(
                        enabled = !mirrorAddWorking,
                        onClick = {
                            if (mirrorAddWorking) return@TextButton
                            pendingMainnetConfirm = false
                            mirrorAddWorking = true
                            doMirrorAdd(scope, prompt) {
                                mirrorAddWorking = false
                                pendingMirrorPrompt = null
                                onSetupComplete()
                            }
                        },
                    ) {
                        Text(
                            if (mirrorAddWorking) stringResource(Res.string.onb_adding)
                            else stringResource(Res.string.onb_continue_without_tor),
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        pendingMainnetConfirm = false
                        // Treat cancel-on-Tor-warning the same as Skip
                        // on the outer dialog: the user backed out of
                        // mirroring, proceed to Dashboard.
                        pendingMirrorPrompt = null
                        onSetupComplete()
                    }) { Text(stringResource(Res.string.common_cancel)) }
                },
            )
        }
    }
}

/** Snapshot used by the post-create "mirror as watch?" prompt. */
private data class MirrorPromptState(
    val walletId: String,
    val walletLabel: String,
    val network: Network,
)

/**
 * Add a watch-only mirror of the freshly-created signing wallet to the
 * registry. Pulls the bare xpub from the active `WalletPort` (no
 * re-prompt for any PIN), derives a default label `"<wallet> (tracker)"`,
 * registers it, and best-effort syncs once before navigating onward.
 *
 * Lives outside the composable so the dialog confirm-buttons can call
 * it without the call site dragging the entire compose context in.
 */
private fun doMirrorAdd(
    scope: kotlinx.coroutines.CoroutineScope,
    state: MirrorPromptState,
    onDone: () -> Unit,
) {
    scope.launch {
        runCatching {
            withContext(Dispatchers.IO) {
                val port = WalletStore.current()
                    ?: error("WalletStore empty post-create")
                val registry = io.bhodl.desktop.wizard.RegistryStore.current()
                    ?: error("RegistryStore empty post-create")
                val descriptor = port.exportPublicDescriptor()
                val mirrorLabel = uniqueMirrorLabel(registry, state.walletLabel)
                // Explicitly linked to the signing wallet (registry v4
                // `mirror_of`): deleting the signing wallet removes
                // exactly this row, whatever the user renames it to.
                val added = registry.addWatchXpubMirror(
                    signingWalletId = state.walletId,
                    label = mirrorLabel,
                    xpub = descriptor.accountXpub,
                    network = state.network,
                    bip = io.bhodl.ffi.WatchBipFfi.TAPROOT,
                )
                io.bhodl.desktop.wizard.RegistryStore.bumpDataVersion()
                // Best-effort initial sync so the tracking tab has a
                // real balance immediately.
                runCatching {
                    val handle = registry.openWatch(added.id)
                    handle.syncWithEsplora(
                        io.bhodl.desktop.settings.EsploraEndpoint.current(state.network),
                        io.bhodl.desktop.settings.EsploraEndpoint.currentProxy(),
                    )
                    handle.destroy()
                }
            }
        }
        // Whether the mirror add succeeded or not, navigate onward.
        // A failure here is non-fatal (the signing wallet itself is
        // already in the registry); user can retry from the Welcome
        // picker "+ Add new wallet" → "Add watch-only" if needed.
        onDone()
    }
}

/**
 * Pick a label for the watch mirror that doesn't collide with any
 * existing registry row. Tries `"<base> (tracker)"` first; if taken,
 * falls back to `"<base> (tracker N)"` with the smallest N that's
 * free.
 */
private fun uniqueMirrorLabel(
    registry: io.bhodl.ffi.RegistryHandle,
    baseLabel: String,
): String {
    val base = "$baseLabel (tracker)"
    val all = runCatching { registry.listWallets() }.getOrNull().orEmpty()
    val existing = all.map { it.label.trim().lowercase() }.toSet()
    if (base.lowercase() !in existing) return base
    var n = 2
    while ("$baseLabel (tracker $n)".lowercase() in existing) n++
    return "$baseLabel (tracker $n)"
}

/** Test tags exposed by [`PinSetupScreen`]. */
public object PinSetupTags {
    public const val WALLET_NAME: String = "pin-setup.wallet-name"
    public const val PIN: String = "pin-setup.pin"
    public const val CONFIRM_PIN: String = "pin-setup.confirm-pin"
    public const val FINISH: String = "pin-setup.finish"
    public const val BACK: String = "pin-setup.back"
    public const val ADVANCED_TOGGLE: String = "pin-setup.advanced-toggle"
    public const val PASSPHRASE: String = "pin-setup.passphrase"
    public const val PASSPHRASE_CONFIRM: String = "pin-setup.passphrase-confirm"
    /** Audit 2026-10 — inline device-PIN gate for a closed registry. */
    public const val DEVICE_PIN_GATE: String = "pin-setup.device-pin-gate"
    public const val DEVICE_PIN_GATE_SUBMIT: String = "pin-setup.device-pin-gate-submit"
    /** Phase 6.0 — opt-out checkbox for the Tracker pairing. */
    public const val ADD_TO_TRACKER: String = "pin-setup.add-to-tracker"
    /** Security review — caution hint under a 6–7 digit PIN. */
    public const val PIN_STRENGTH_HINT: String = "pin-setup.pin-strength-hint"
    /** Audit 2026-10 — PIN ⇄ passphrase switch (keystore available only). */
    public const val SECRET_MODE_TOGGLE: String = "pin-setup.secret-mode-toggle"
    /** Audit 2026-10 — "no keystore, passphrase required" explanation. */
    public const val KEYSTORE_UNAVAILABLE: String = "pin-setup.keystore-unavailable"
    /** Audit 2026-10 — "PIN bound to <backend>" hint. */
    public const val KEYSTORE_HINT: String = "pin-setup.keystore-hint"
}

/**
 * Phase 6.0 — duplicate detection for the Tracker pairing flow.
 *
 * Returns true when the registry already carries a watch row for
 * the same `descriptorPub` (or, by xpub normalisation, the same
 * account xpub) excluding the wallet identified by [excludeId].
 *
 * The `excludeId` parameter is what makes the check usable from the
 * post-create handler: we have JUST inserted the signing wallet
 * row with this descriptor, so a naïve [io.bhodl.ffi.RegistryHandle.isDescriptorTracked]
 * would self-match. Pass the signing wallet UUID here to ignore
 * that row and only detect a *separate* pre-existing watch entry.
 */
internal fun isAlreadyTrackedExcluding(
    registry: io.bhodl.ffi.RegistryHandle,
    descriptorPub: String,
    excludeId: String,
): Boolean {
    val rows = registry.listWallets()
    val targetXpub = io.bhodl.ffi.extractAccountXpub(descriptorPub)
    return rows.any { row ->
        if (row.id == excludeId) return@any false
        if (row.descriptorPub == descriptorPub) return@any true
        val rowXpub = io.bhodl.ffi.extractAccountXpub(row.descriptorPub)
        targetXpub != null && rowXpub != null && targetXpub == rowXpub
    }
}

/** Result of [createAndRegisterWallet], computed entirely off the UI thread. */
private class SetupOutcome(
    val handle: WalletHandle,
    val walletDir: java.io.File,
    val samePin: Boolean,
    val alreadyTracked: Boolean,
)

/**
 * Audit 2026-10 — the whole post-PIN pipeline, run on Dispatchers.IO:
 *
 *  1. create the encrypted wallet DB (Argon2id derive);
 *  2. persist `wallet.network` — a failure here FAILS the setup (the
 *     handle is closed and the half-created dir removed) instead of
 *     being swallowed: a wallet without its network file used to fall
 *     back to SIGNET on unlock, so a mainnet wallet looked like "Wrong
 *     PIN" forever;
 *  3. bootstrap the registry when none exists yet (fresh install,
 *     wallet PIN doubles as the initial device PIN) and open the
 *     [DevicePinSession] — without it the idle watcher treats the
 *     null session as expired and auto-locks the brand-new wallet
 *     within ~30 s (H3). An EXISTING-but-closed registry never reaches
 *     here: the caller routes through the device-PIN gate first;
 *  4. register the wallet row (best-effort, as before);
 *  5. compute the same-PIN hint and the already-tracked check.
 */
private fun createAndRegisterWallet(
    mnemonic: String,
    passphrase: String,
    pin: String,
    network: Network,
    label: String,
): SetupOutcome {
    val (handle, walletDir) = createPersistentWallet(
        mnemonic = mnemonic,
        passphrase = passphrase,
        pin = pin,
        network = network,
    )
    try {
        NetworkPersistence.write(walletDir, network)
        check(NetworkPersistence.readOrNull(walletDir) == network) {
            "wallet.network could not be verified after write"
        }
    } catch (t: Throwable) {
        runCatching { handle.destroy() }
        runCatching { walletDir.deleteRecursively() }
        throw IllegalStateException(
            "Could not save the wallet's network setting; setup was aborted.",
            t,
        )
    }

    val dataDirView = WalletPaths.from(WalletPaths.defaultDataDir())
    runCatching {
        var registry = RegistryStore.current()
        if (registry == null) {
            // Defence in depth: the UI already routes a closed-but-
            // existing registry through the device-PIN gate.
            check(!dataDirView.registryExists()) { "registry is locked" }
            registry = io.bhodl.ffi.RegistryHandle.createOrLoad(
                dataDir = dataDirView.dir.absolutePath,
                devicePin = pin,
            )
            RegistryStore.set(registry)
            DevicePinSession.markUnlocked()
        }
        val descriptor = handle.exportPublicDescriptor()
        registry.addWallet(
            io.bhodl.ffi.WalletRecordFfi(
                id = walletDir.name,
                label = label,
                kind = io.bhodl.ffi.WalletKindFfi.SIGNING,
                descriptorPub = descriptor.externalDescriptor,
                network = descriptor.network,
                createdAtUnix = System.currentTimeMillis() / 1000,
                pinsWarned = false,
            ),
        )
        RegistryStore.bumpDataVersion()
    }

    // Phase 3.2-H — same-PIN UX hint (Argon2id derive, best effort).
    val samePin = runCatching {
        RegistryStore.current()?.matchesDevicePin(pin) == true
    }.getOrDefault(false)
    if (samePin) {
        runCatching { RegistryStore.current()?.markPinsWarned(walletDir.name) }
    }
    val alreadyTracked = runCatching {
        val registry = RegistryStore.current()
        registry != null && isAlreadyTrackedExcluding(
            registry,
            handle.trackerDescriptorPub(),
            walletDir.name,
        )
    }.getOrDefault(false)
    return SetupOutcome(handle, walletDir, samePin, alreadyTracked)
}

/**
 * Build the desktop wallet path under `~/.bhodl/wallets/<uuid>/`,
 * ensure the dir exists, and call into UniFFI. Lives outside the
 * Composable so it can be swapped for a mock during future test
 * setups.
 *
 * Returns the freshly-created `WalletHandle` AND the per-wallet
 * directory the caller needs to write `wallet.network` into.
 *
 * Phase 3 — every new wallet (fresh install or restore) lands at
 * `wallets/<uuid>/` rather than the legacy `~/.bhodl/` root. Migration
 * code in Rust handles existing single-wallet installs.
 */
private fun createPersistentWallet(
    mnemonic: String,
    passphrase: String,
    pin: String,
    network: Network,
): Pair<WalletHandle, java.io.File> {
    val rootView = WalletPaths.from(WalletPaths.defaultDataDir())
    rootView.dir.mkdirs()
    // Security review — owner-only perms (700) on the data dir tree.
    // Best-effort; a no-op on non-POSIX filesystems (Windows).
    WalletPaths.restrictToOwner(rootView.dir)
    val walletId = io.bhodl.ffi.generateWalletId()
    val walletDir = java.io.File(rootView.walletsRoot, walletId)
    walletDir.mkdirs()
    WalletPaths.restrictToOwner(rootView.walletsRoot)
    WalletPaths.restrictToOwner(walletDir)
    val handle = WalletHandle.createOrLoadPersistent(
        dbDir = walletDir.absolutePath,
        network = network,
        mnemonic = mnemonic,
        passphrase = passphrase,
        pin = pin,
    )
    return handle to walletDir
}
