# Casi d'uso di BHODL — inventario e copertura test

Inventario completo dei casi d'uso dell'app (working tree del branch
`refactor/watch-only-wallets`, 2026-10-24), ricavato dalla lettura del
codice Rust (`crates/`) e Kotlin (`apps/desktop`), con la copertura dei
test automatici di ogni caso.

Stati: **COVERED** = un test automatico verifica il comportamento;
**PARTIAL** = solo una parte (es. funzione pura testata, wiring/UI no);
**UNCOVERED** = nessun test automatico.

Aree:
- [A. Sicurezza, impostazioni, navigazione](#a) — `SEC-*`
- [B. Wallet watch-only](#b) — `WAL-*`
- [C. Tracker: attività, cash flow, budget](#c) — `TRK-*`
- [D. Portafoglio e patrimonio](#d) — `PRT-*`
- [E. Revisione: casi aggiunti](#e) — `GAP-*`
- [F. Test aggiunti per area](#f)

## Stato dopo la campagna di test (2026-10-24)

| Area | Casi | COVERED | PARTIAL | UNCOVERED |
|---|---:|---:|---:|---:|
| A. Sicurezza, impostazioni | 172 | 162 | 9 | 1 |
| B. Wallet watch-only | 130 | 125 | 1 | 4 |
| C. Tracker | 171 | 150 | 15 | 6 |
| D. Portafoglio | 154 | 145 | 9 | 0 |
| E. Casi aggiunti in revisione | 38 | 29 | 6 | 3 |

I PARTIAL/UNCOVERED rimasti hanno una motivazione nella sezione F
(wiring in `Main.kt`, card non montate, funzioni mancanti, scenari che
richiedono Docker). Esecuzione: `cargo test --workspace` (439 test) e
`./gradlew :apps:desktop:jvmTest :shared:jvmTest` (1435 test, 5 saltati:
3 bug aperti marcati `@Ignore("BUG …")`, 2 solo POSIX). I test Docker
(`*IntegrationTest`) girano con `./gradlew :apps:desktop:integrationTest`.


<a id="a"></a>

## Inventario casi d'uso — Area A: onboarding, sicurezza, settings, navigazione, app shell

Branch `refactor/watch-only-wallets`, snapshot 2026-10-24.

**Legenda percorsi**
- `D/` = `apps/desktop/src/jvmMain/kotlin/io/bhodl/desktop/`
- `T/` = `apps/desktop/src/jvmTest/kotlin/io/bhodl/desktop/`
- `S/` = `shared/src/jvmTest/kotlin/io/bhodl/ffi/`
- `U/` = `crates/bhodl-uniffi/src/commonMain/rust/` (test nel `mod tests` di `lib.rs`)
- `O/` = `crates/bhodl-wallet-onchain/src/`, `C/` = `crates/bhodl-core/src/`

**Stato**: COVERED = un test verifica il comportamento · PARTIAL = solo una parte è testata · UNCOVERED = nessun test.

---

### 1. Boot e routing iniziale

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|----|------|------|------|------|
| SEC-001 | Primo avvio senza dati su disco → atterra su Welcome | D/App.kt:bootGuardTarget | — | **COVERED** (test in §F; prima: UNCOVERED) |
| SEC-002 | Avvio con `registry.sqlite` presente → DevicePinUnlock | D/App.kt:bootGuardTarget, D/wizard/WalletPaths.kt:registryExists | — | **COVERED** (test in §F; prima: UNCOVERED) |
| SEC-003 | Avvio con solo dati seed-era (pre-3.0 alla radice, nessun registry) → LegacyCleanup senza PIN | D/App.kt:bootGuardTarget, D/wizard/WalletPaths.kt:legacySigningDataExists | T/wizard/WalletPathsTest::legacySigningDataExists_detects_each_root_artifact, ::legacySigningDataExists_is_false_for_empty_dir, ::legacySigningDataExists_ignores_registry_and_watch_state | **COVERED** (test in §F; prima: PARTIAL) |
| SEC-004 | Cambio lingua con sessione già sbloccata: la destinazione iniziale è Dashboard/Tracking, non il gate PIN | D/App.kt:App (startDestination) | — | **COVERED** (test in §F; prima: UNCOVERED) |
| SEC-005 | Welcome spiega che BHODL è watch-only; "Get started" → creazione PIN, senza altri effetti | D/screens/WelcomeScreen.kt:WelcomeScreen | T/screens/WelcomeScreenTest::tap get started invokes the callback and nothing else | COVERED |
| SEC-006 | Welcome mostra la card unità "Sats e bitcoin" (esempi in sats, tooltip con BTC, mai mascherati) | D/screens/UnitsOnboarding.kt:UnitsOnboardingCard, :dualUnitTexts | T/screens/UnitsOnboardingTest::examples are sats with the BTC equivalent, never masked, ::card shows sats by default and disappears for good after Got it | COVERED |
| SEC-007 | "Got it" nasconde la card per sempre (flag `onboardingUnitsSeen` salvato in settings.json) | D/settings/SettingsStore.kt:markOnboardingUnitsSeen, D/settings/SettingsPersistence.kt:snapshot/apply | T/screens/UnitsOnboardingTest::card shows sats by default and disappears for good after Got it | **COVERED** (test in §F; prima: PARTIAL) |
| SEC-008 | Welcome → "Erase ALL local wallets" chiede di digitare DELETE, poi cancella e riporta l'esito | D/screens/WelcomeScreen.kt, D/wizard/LocalWipe.kt:wipeAll/describe | T/screens/WelcomeScreenTest::reset entry is offered but does not wipe without confirmation, T/screens/SecretActionsUiTest::Welcome reset needs DELETE and then reports the wipe | COVERED |
| SEC-009 | Dopo l'unlock, se restano signing wallet (righe nel registry o file su disco) → LegacyCleanup invece del Tracker | D/App.kt:legacyCleanupNeeded, U/lib.rs:legacy_signing_wallet_count | T/wizard/WalletPathsTest::legacySigningDataExists_detects_a_per_wallet_signing_dir, S/UniffiSmokeTest::fresh registry has no legacy signing wallets | **COVERED** (test in §F; prima: PARTIAL) |
| SEC-010 | LegacyCleanup: "Delete old wallets" resta disabilitato finché la checkbox non è spuntata | D/screens/LegacyCleanupScreen.kt:LegacyCleanupScreen | — | **COVERED** (test in §F; prima: UNCOVERED) |
| SEC-011 | LegacyCleanup confermato: cancella righe signing + directory chiave (DB prima dei KDF), tiene i mirror come watch normali, poi Tracker | D/screens/LegacyCleanupScreen.kt, D/wizard/WalletPaths.kt:purgeLegacySigningData, U/lib.rs:purge_legacy_signing_wallets | O/registry.rs::purge_legacy_signing_wallets_keeps_mirrors_as_plain_watch_wallets, U/lib.rs::purge_legacy_signing_wallets_removes_rows_and_key_dirs, T/wizard/WalletPathsTest::purgeLegacySigningData_removes_every_signing_artifact_and_keeps_watch_data, ::purgeLegacySigningData_deletes_databases_before_kdf_descriptors, ::purgeLegacySigningData_on_clean_dir_is_a_successful_no_op | **COVERED** (test in §F; prima: PARTIAL) |
| SEC-012 | LegacyCleanup "Not now": va al Tracker senza cancellare; la schermata torna al prossimo unlock | D/screens/LegacyCleanupScreen.kt, D/App.kt | — | **COVERED** (test in §F; prima: UNCOVERED) |
| SEC-013 | LegacyCleanup con un file bloccato (Windows): messaggio "some files could not be deleted", nessuna navigazione | D/screens/LegacyCleanupScreen.kt, D/wizard/WalletPaths.kt:purgeLegacySigningData (leftovers) | — | **COVERED** (test in §F; prima: UNCOVERED) |
| SEC-014 | LegacyCleanup su install pre-3.0 senza registry → dopo la pulizia va a Welcome | D/App.kt (composable LegacyCleanup → onDone) | — | **COVERED** (test in §F; prima: UNCOVERED) |

### 2. Creazione del device PIN

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|----|------|------|------|------|
| SEC-015 | Policy PIN: da 6 a 32 cifre; vuoto / troppo corto / troppo lungo / con caratteri non numerici rifiutati | D/wizard/PinValidator.kt:validate | T/wizard/PinValidatorTest::empty_pin_is_Empty, ::pin_below_minimum_is_TooShort, ::pin_at_minimum_is_Valid, ::pin_one_above_minimum_is_Valid, ::pin_at_maximum_is_Valid, ::pin_above_maximum_is_TooLong, ::pin_with_alpha_char_is_NonNumeric, ::pin_with_space_is_NonNumeric, ::pin_with_dash_is_NonNumeric, ::pin_with_unicode_digit_is_NonNumeric_or_valid_consistently | COVERED |
| SEC-016 | Validazione di PIN + conferma: prima il PIN, poi la corrispondenza | D/wizard/PinValidator.kt:validatePair | T/wizard/PinValidatorTest::pair_both_valid_and_matching_is_Valid, ::pair_with_mismatch_is_Mismatch, ::pair_with_short_pin_returns_PinInvalid_with_reason, ::pair_with_empty_pin_returns_PinInvalid_with_Empty, ::pair_with_non_numeric_pin_returns_PinInvalid_with_NonNumeric, ::pair_with_too_long_pin_returns_PinInvalid_with_TooLong, ::pair_validates_pin_first_then_checks_match | COVERED |
| SEC-017 | Hint di robustezza: 6–7 cifre "moderate", 8+ nessun hint; il PIN da 6 resta accettato | D/wizard/PinValidator.kt:strength, D/screens/DevicePinSetupScreen.kt | T/wizard/PinValidatorTest::strength_grades_6_and_7_digit_pins_as_moderate, ::strength_grades_8_plus_digit_pins_as_strong, ::strength_does_not_rate_invalid_pins | **COVERED** (test in §F; prima: PARTIAL) |
| SEC-018 | Setup: conferma diversa → errore, nessun file scritto | D/screens/DevicePinSetupScreen.kt:pinPairProblem | T/screens/DevicePinSetupScreenTest::mismatched confirmation shows an error and touches no disk | COVERED |
| SEC-019 | Setup: PIN troppo corto o non numerico → errore, nessun file scritto | D/screens/DevicePinSetupScreen.kt:pinPairProblem | T/screens/DevicePinSetupScreenTest::too-short PIN shows an error and touches no disk, ::non-numeric PIN shows an error and touches no disk | COVERED |
| SEC-020 | Setup: "Create device PIN" disabilitato finché entrambi i campi non sono pieni | D/screens/DevicePinSetupScreen.kt | T/screens/DevicePinSetupScreenTest::create is disabled until both fields are filled | COVERED (il test-book §1.7 la segna ancora manuale: va aggiornato) |
| SEC-021 | Setup e unlock: gli spazi digitati vengono rimossi dal campo PIN | D/screens/DevicePinSetupScreen.kt (onValueChange filter), D/screens/DevicePinUnlockScreen.kt | — | **COVERED** (test in §F; prima: UNCOVERED) |
| SEC-022 | Setup: PIN oltre 32 cifre → errore "too long" in UI | D/screens/DevicePinSetupScreen.kt:pinPairProblem | T/wizard/PinValidatorTest::pin_above_maximum_is_TooLong | **COVERED** (test in §F; prima: PARTIAL) |
| SEC-023 | Setup riuscito: createOrLoad crea registry + KDF, apre la sessione, naviga ad AddWatch svuotando il back stack | D/screens/DevicePinSetupScreen.kt, D/App.kt (DevicePinSetup → AddWatch), U/lib.rs:create_or_load | U/lib.rs::fresh_dir_creates_kdf_json_with_current_params, S/UniffiSmokeTest (setUp createOrLoad) | **COVERED** (test in §F; prima: PARTIAL) |
| SEC-024 | Setup: errore FFI in creazione (permessi, disco) → messaggio ripulito dai path, il bottone torna attivo | D/screens/DevicePinSetupScreen.kt (onFailure → userMessage) | — | **COVERED** (test in §F; prima: UNCOVERED) |
| SEC-025 | Rust: un registry nuovo impone la policy (6 cifre ASCII) anche se la UI venisse aggirata; al rifiuto non resta nessun file KDF | U/kdf.rs:unlock_with (create_if_missing), C/key_derivation.rs | U/lib.rs::l6_new_registry_requires_policy_pin_but_unlock_does_not, C/key_derivation.rs::new_pin_policy_requires_six_ascii_digits | COVERED |
| SEC-026 | Setup: "Back" torna a Welcome; Back disabilitato mentre la creazione è in corso | D/screens/DevicePinSetupScreen.kt | — | **COVERED** (test in §F; prima: UNCOVERED) |

### 3. Unlock con device PIN, tentativi e lockout

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|----|------|------|------|------|
| SEC-027 | PIN corretto: apre il registry, azzera i tentativi, apre la sessione, avvia il backfill, va al Tracker | D/screens/DevicePinUnlockScreen.kt (onSuccess), D/App.kt | S/UniffiSmokeTest::registry reopens a watch wallet after every handle is dropped | **COVERED** (test in §F; prima: PARTIAL) |
| SEC-028 | PIN sbagliato → "Wrong device PIN.", tentativo contato | D/screens/DevicePinUnlockScreen.kt (onFailure), D/wizard/PinErrors.kt:classify | S/FallibleConstructorErrorTest::wrong device pin on createOrLoad throws WalletException Storage, T/wizard/SecurityAuditPinTest::pin error classification separates network and storage from wrong pin | **COVERED** (test in §F; prima: PARTIAL) |
| SEC-029 | Escalation del lockout: 0–2 errori nessuna attesa, poi 5 s / 30 s / 2 min / 10 min | D/wizard/PinAttemptTracker.kt:cooldownSecondsFor, :recordFailure | T/wizard/PinAttemptTrackerTest::first two failures incur no cooldown, ::third and fourth failures trigger 5s cooldown, ::fifth through ninth failures trigger 30s cooldown, ::tenth through fourteenth failures trigger 2 minute cooldown, ::fifteenth and beyond failures trigger 10 minute cooldown, ::third failure locks out for 5 seconds, ::fifth failure escalates to 30 second cooldown, ::tenth failure escalates to 2 minute cooldown, ::fifteenth failure escalates to 10 minute cooldown, ::lockout deadline decays as wall clock advances | COVERED |
| SEC-030 | Lockout attivo: il PIN viene rifiutato PRIMA di chiamare l'FFI, con "Wait Ns" | D/screens/DevicePinUnlockScreen.kt, D/wizard/PinAttemptTracker.kt:lockoutMessage | T/wizard/DevicePinSessionTest::only WrongPin outcomes count towards the device lockout (solo il caso null) | **COVERED** (test in §F; prima: PARTIAL) |
| SEC-031 | PIN sbagliato che fa scattare il cooldown → messaggio con i secondi da attendere | D/screens/DevicePinUnlockScreen.kt | T/wizard/PinAttemptTrackerTest (deadline) | **COVERED** (test in §F; prima: PARTIAL) |
| SEC-032 | Unlock riuscito azzera il contatore del proprio scope (anche su disco) | D/wizard/PinAttemptTracker.kt:recordSuccess | T/wizard/PinAttemptTrackerTest::recordSuccess clears the counter and lifts lockout, T/wizard/PinAttemptTrackerPersistenceTest::success wipes the persisted counter | COVERED |
| SEC-033 | Scope isolati: un successo su un altro scope non sblocca il lockout del device PIN | D/wizard/PinAttemptTracker.kt (per-scope deadlines) | T/wizard/PinAttemptTrackerTest::failures in one scope do not affect another scope, ::lockedUntilUnix is the max across all scopes, T/wizard/SecurityAuditPinTest::success on another scope does not lift a lockout, T/wizard/PinAttemptTrackerPersistenceTest::multiple scopes are persisted and reloaded independently | COVERED |
| SEC-034 | Il lockout sopravvive al riavvio del processo (pin_failures.json) | D/wizard/PinAttemptTracker.kt:enablePersistence, D/Main.kt | T/wizard/PinAttemptTrackerPersistenceTest::failures persist across tracker recreation (process restart simulation), ::lockout deadline is honoured after restart, ::reset() writes an empty snapshot to disk | COVERED (il collegamento in `Main.kt` è manuale) |
| SEC-035 | File dei tentativi mancante = primo avvio; file corrotto = lockout di penalità, non azzeramento | D/wizard/PinAttemptTracker.kt:loadFromDisk | T/wizard/PinAttemptTrackerPersistenceTest::missing file is silently ignored on load (first launch), ::corrupted file does not crash the tracker, T/wizard/SecurityAuditPinTest::corrupt failures file applies a lockout instead of resetting | COVERED |
| SEC-036 | Manomissione dell'orologio: all'indietro non allunga l'attesa oltre la policy, in avanti non salta il lockout | D/wizard/PinAttemptTracker.kt (monotonic + clamp) | T/wizard/SecurityAuditPinTest::clock moved backwards cannot stretch the wait beyond policy, ::monotonic deadline survives a forward clock jump in-process, ::future timestamps on disk are clamped to now | COVERED |
| SEC-037 | Errori che non dipendono dal PIN (rete del wallet diversa, storage, dati di una versione più nuova) hanno un messaggio dedicato e NON contano per il lockout | D/wizard/PinErrors.kt:classify/messageFor, D/screens/DevicePinUnlockScreen.kt | T/wizard/SecurityAuditPinTest::pin error classification separates network and storage from wrong pin, ::downgrade refusals are classified as NewerVersion, not as a wrong PIN | **COVERED** (test in §F; prima: PARTIAL) |
| SEC-038 | Errore sconosciuto (non FFI) → messaggio generico ripulito, non contato | D/wizard/PinErrors.kt (Other), D/screens/DevicePinUnlockScreen.kt | T/wizard/SecurityAuditPinTest::pin error classification separates network and storage from wrong pin | **COVERED** (test in §F; prima: PARTIAL) |
| SEC-039 | Unlock: bottone disabilitato con campo vuoto o durante l'apertura; cursore già nel campo PIN | D/screens/DevicePinUnlockScreen.kt | — | **COVERED** (test in §F; prima: UNCOVERED) |
| SEC-040 | Rust: un PIN sbagliato su un descriptor non ancora sigillato non lega il MAC alla chiave sbagliata (nessun blocco permanente) | U/kdf.rs:unlock_with, :finalize_after_open | U/lib.rs::h1_wrong_pin_on_unsealed_descriptor_does_not_lock_out | COVERED |
| SEC-041 | Registry scritto da una build più nuova (schema) → rifiutato con "update BHODL" | O/registry.rs (schema gate), D/wizard/PinErrors.kt | O/registry.rs::opening_a_registry_from_a_newer_schema_is_refused, ::downgrade_refusal_keeps_working_with_schema_v4, ::schema_version_is_stamped_and_gate_is_idempotent, T/wizard/SecurityAuditPinTest::downgrade refusals are classified as NewerVersion, not as a wrong PIN | COVERED |
| SEC-042 | Rust: unlock con PIN vuoto rifiutato (EmptyPin) | U/kdf.rs:unlock_with, C/key_derivation.rs | C/key_derivation.rs::empty_pin_is_rejected | **COVERED** (test in §F; prima: PARTIAL) |
| SEC-043 | Un registry creato con la vecchia policy (PIN corto) si apre ancora | U/kdf.rs:unlock_with | U/lib.rs::l6_new_registry_requires_policy_pin_but_unlock_does_not | COVERED |
| SEC-044 | PIN dimenticato: da Unlock "Hai dimenticato il PIN?" porta al codice di recupero oppure, senza codice, a una cancellazione locale spiegata e confermata | D/screens/DevicePinUnlockScreen.kt, D/screens/PinRecovery.kt:ForgotPinPanel | T/screens/UcPinScreensSecurityTest::SEC-044 a forgotten PIN without a code reaches the explained local reset, ::SEC-044 a refused wipe is reported and does not leave the screen; U/uc_pin_recovery_tests::sec_044_* | **COVERED** (corretto 2026-10-24; prima: UNCOVERED, bug) |
| SEC-140 | Codice di recupero: 24 simboli Crockford, gruppi di 4, digitazione tollerante (maiuscole, spazi, O/0, I-L/1) | U/kdf.rs:generate_recovery_code/normalize_recovery_code, D/screens/PinRecovery.kt:RecoveryCodes | U/uc_pin_recovery_tests::recovery_codes_are_24_crockford_symbols_in_groups_of_four, ::typed_codes_are_normalized_for_look_alikes_and_separators; T/screens/PinRecoveryScreensTest::SEC-140 | COVERED |
| SEC-141 | Creazione / rigenerazione del codice: mostrato una volta, chiusura solo riscrivendo un gruppo; il nuovo codice revoca il vecchio | U/lib.rs:create_recovery_code, D/screens/PinRecovery.kt:RecoveryCodePanel | U/uc_pin_recovery_tests::sec_044_regenerating_the_code_revokes_the_previous_one; T/screens/PinRecoveryScreensTest::SEC-141 (2 test) | COVERED |
| SEC-142 | Codice errato contato nel lockout (scope dedicato), codice malformato e PIN non coincidenti rifiutati prima del core | D/screens/PinRecovery.kt:recoveryFailureMessage, U/lib.rs:unlock_with_recovery_code | T/screens/PinRecoveryScreensTest::SEC-142 (2 test); U/uc_pin_recovery_tests::sec_044_a_wrong_code_changes_nothing_and_is_reported_as_rejected, ::sec_044_malformed_code_weak_pin_and_missing_file_fail_before_any_change | COVERED |
| SEC-143 | Recupero end-to-end: il codice imposta il nuovo PIN, i dati del tracker e i file watch-state restano leggibili, il vecchio PIN non apre più | U/lib.rs:unlock_with_recovery_code, U/kdf.rs:write_wrapped_file | U/uc_pin_recovery_tests::sec_044_recovery_code_resets_the_pin_and_keeps_every_file_readable; T/screens/PinRecoveryScreensTest::SEC-143 | COVERED |
| SEC-144 | Cambia PIN: PIN attuale errato contato, nessun file ricifrato (solo la chiave dati riavvolta) | U/lib.rs:change_device_pin, D/screens/PinRecovery.kt:ChangePinPanel | U/uc_pin_recovery_tests::change_pin_rewraps_only_and_refuses_a_wrong_old_pin; T/screens/PinRecoveryScreensTest::SEC-144 (2 test) | COVERED |
| SEC-145 | Hub sicurezza: riga "Codice di recupero" (Attenzione se manca, OK se creato, INFO senza PIN) con azione Crea/Gestisci; azione "Cambia" sulla riga PIN | D/screens/SecurityHub.kt:securityChecks/rowContent | T/screens/PinRecoveryScreensTest::SEC-145 (2 test), T/screens/SecurityHubTest | COVERED |
| SEC-146 | Descrittore v3 (chiave dati avvolta, AEAD) scritto a ogni sblocco, migrazione da v1/v2 senza ricifrare; il file di recupero non può sostituire il descrittore; il reset di fabbrica lo cancella | U/kdf.rs:unlock_with/finalize_after_open, D/wizard/WalletPaths.kt:rootKdfArtifacts | U/uc_pin_recovery_tests::every_unlock_leaves_a_v3_descriptor, ::sec_044_the_recovery_file_cannot_stand_in_for_the_pin_descriptor; U/lib.rs::h1_legacy_v1_mac_is_accepted_once_then_rewrapped_as_v3; T/screens/PinRecoveryScreensTest::SEC-146 | COVERED |

### 4. KDF, SQLCipher e materiale di chiave (Rust)

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|----|------|------|------|------|
| SEC-045 | Store nuovo: `registry.kdf.json` con parametri CURRENT e salt nuovo, scritto in modo atomico | U/kdf.rs:read_or_create_registry_kdf, :write_kdf_file_for | U/lib.rs::fresh_dir_creates_kdf_json_with_current_params, ::write_kdf_file_atomic_temp_no_residue | COVERED |
| SEC-046 | Argon2id: parametri allineati a OWASP 2024, derivazione deterministica, chiave diversa se cambia PIN o salt | C/key_derivation.rs | C/key_derivation.rs::derive_is_deterministic_for_same_inputs, ::derive_differs_when_pin_differs, ::derive_differs_when_salt_differs, ::derived_key_is_not_all_zeros, ::salt_generator_does_not_emit_all_zeros, ::argon2_parameters_match_owasp_2024_strong_baseline, ::derive_key_from_pin_matches_current_params, ::legacy_and_current_params_derive_different_keys, ::legacy_params_match_pre_hardening_constants, ::long_unicode_pin_works | COVERED |
| SEC-047 | MAC del descriptor: verifica un file intatto, rifiuta parametri o salt manomessi | U/kdf.rs:verify_mac, :constant_time_eq | U/lib.rs::kdf_mac_round_trip_verifies_unchanged_file, ::kdf_mac_rejects_tampered_kdf_params, ::kdf_mac_rejects_tampered_salt | COVERED |
| SEC-048 | Descriptor legacy senza MAC accettato prima del sigillo; MAC v1 accettato una volta e risigillato come v2 | U/kdf.rs:unlock_with, :seal_kdf_file_with_mac | U/lib.rs::kdf_mac_accepts_legacy_file_without_mac_field, ::h1_legacy_v1_mac_is_accepted_once_then_resealed_as_v2 | COVERED |
| SEC-049 | Dopo il sigillo (marker), un descriptor a cui è stato tolto il MAC viene rifiutato | U/kdf.rs:read_descriptor (seal_required) | U/lib.rs::m1_stripping_the_mac_from_a_sealed_descriptor_is_rejected | COVERED |
| SEC-050 | Parametri Argon2 fuori range rifiutati prima della derivazione (niente DoS da file manomesso) | U/kdf.rs, C/key_derivation.rs:validate_bounds | U/lib.rs::m1_out_of_bounds_params_are_rejected_before_derivation, C/key_derivation.rs::validate_bounds_rejects_dos_and_downgrade_params, ::validate_bounds_accepts_shipped_param_sets | COVERED |
| SEC-051 | Versione del descriptor KDF sconosciuta → "unsupported kdf descriptor version" → NewerVersion in UI | U/kdf.rs:read_descriptor, D/wizard/PinErrors.kt | T/wizard/SecurityAuditPinTest::downgrade refusals are classified as NewerVersion, not as a wrong PIN | **COVERED** (test in §F; prima: PARTIAL) |
| SEC-052 | Migrazione KDF interrotta: se il descriptor principale non apre il DB si prova `.next` e lo si promuove; un `.next` obsoleto viene rimosso | U/kdf.rs:unlock_with, :finalize_after_open, :promote_pending | — | **COVERED** (test in §F; prima: UNCOVERED) |
| SEC-053 | Marker di sigillo senza descriptor (residuo di un wipe) gestito senza bloccare la creazione | U/kdf.rs:primary_candidate | — | **COVERED** (test in §F; prima: UNCOVERED) |
| SEC-054 | Rename del descriptor riprovato su violazione di condivisione (Windows AV/indexer) | U/kdf.rs:rename_with_retry | — | **COVERED** (test in §F; prima: UNCOVERED) |
| SEC-055 | Anche le letture del registry verificano il MAC del descriptor | U/lib.rs (read paths) | U/lib.rs::m1_registry_read_paths_verify_the_descriptor_mac | COVERED |
| SEC-056 | La chiave del device viene azzerata al drop dell'handle; il PIN lato Rust è `Zeroizing` | U/lib.rs:RegistryHandle::drop, :create_or_load | U/lib.rs::registry_handle_drop_zeroizes_device_key | COVERED (la String del PIN lato JVM resta un limite documentato) |
| SEC-057 | Le FFI dei tassi lavorano con la chiave dell'handle: il PIN non resta in memoria JVM | U/lib.rs (metodi rate su RegistryHandle), D/wizard/DevicePinSession.kt | U/lib.rs::rate_methods_run_on_the_handle_key_without_a_pin | COVERED |
| SEC-058 | Gli errori FFI non espongono path assoluti (vengono ridotti al basename) | U/lib.rs:strip_filesystem_paths, From<io::Error> | U/lib.rs::strip_paths_collapses_unix_absolute_path_to_basename, ::strip_paths_collapses_windows_absolute_path_to_basename, ::strip_paths_handles_windows_forward_slash_paths, ::strip_paths_leaves_non_path_text_unchanged, ::strip_paths_keeps_relative_paths_alone, ::strip_paths_handles_multiple_paths_in_one_message, ::strip_paths_does_not_collapse_a_lone_slash, ::strip_paths_handles_path_followed_by_punctuation, ::io_error_from_strips_path_in_detail | COVERED |

### 5. Sessione, blocco per inattività e blocco manuale

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|----|------|------|------|------|
| SEC-059 | Sessione device PIN: apertura, chiusura, scadenza dopo 15 min, `touch` la tiene viva, `touch` senza sessione non la apre | D/wizard/DevicePinSession.kt | T/wizard/DevicePinSessionTest::markUnlocked opens the session, ::clear closes the session, ::not expired within the idle timeout, ::expired once the idle timeout has elapsed, ::isExpired returns true when no session is open, ::touch keeps the session alive beyond the original timeout, ::touch without an open session does not open one | COVERED |
| SEC-060 | Leggere lo stato della sessione (job in background) non rinfresca il timer di inattività | D/wizard/DevicePinSession.kt:isExpired/isUnlocked | T/wizard/DevicePinSessionTest::reading the session state does not refresh the idle clock | COVERED |
| SEC-061 | Blocco dopo 15 minuti senza input | D/IdleLock.kt:shouldLock | T/IdleLockTest::idle just under the timeout stays open, at the timeout it locks | COVERED |
| SEC-062 | Finestra minimizzata: blocco dopo 60 s; più eventi di minimize tengono il primo timestamp | D/IdleLock.kt:setMinimized/shouldLock | T/IdleLockTest::minimised within the grace period stays open, ::repeated minimise events keep the first timestamp | COVERED |
| SEC-063 | Sessione device PIN scaduta → blocco immediato anche se l'utente è attivo | D/IdleLock.kt:shouldLock | T/IdleLockTest::an expired device PIN session locks an open registry even while active | COVERED |
| SEC-064 | Niente aperto → mai blocco; un wallet aperto senza registry arma comunque il blocco | D/IdleLock.kt:shouldLock | T/IdleLockTest::nothing open never locks, T/wizard/SecurityAuditPinTest::idle lock arms for a wallet without registry and on minimise | COVERED |
| SEC-065 | Ogni evento di mouse o tastiera alla radice rinfresca il timer (anche chi scrive solo da tastiera) | D/App.kt (pointerInput / onPreviewKeyEvent → IdleLock.touch) | T/IdleLockTest::activity and restore reset the stateful clocks | **COVERED** (test in §F; prima: PARTIAL) |
| SEC-066 | Il watcher (poll ogni 10 s) blocca e porta al gate; `Main.kt` passa lo stato minimizzato | D/App.kt (LaunchedEffect watcher), D/Main.kt (snapshotFlow isMinimized) | — | **PARTIAL** (test in §F; prima: UNCOVERED) |
| SEC-067 | Blocco manuale dall'header del wallet ("Lock") | D/screens/DashboardScreen.kt, D/App.kt:WalletWorkspaceRoute | T/screens/DashboardScreenTest::Lock header action fires onLockClicked | COVERED (verifica la callback) |
| SEC-068 | Blocco manuale dall'header del tracker ("Lock device") | D/screens/TrackingScreen.kt (HEADER_LOCK → onLockDevice) | — | **COVERED** (test in §F; prima: UNCOVERED) |
| SEC-069 | La routine di blocco rilascia wallet, registry e sessione, svuota il back stack e riapplica "start hidden" | D/App.kt:lockAndRouteToGate | T/wizard/WalletStoreTest::clear closes the port and resets state, T/wizard/RegistryStoreDataVersionTest::clear resets the counter, T/ui/money/AmountMaskTest::start hidden arms the session flag but a lock never reveals | **COVERED** (test in §F; prima: PARTIAL) |
| SEC-070 | `RegistryStore.clear` chiude l'handle, azzera la sessione e annulla il backfill | D/wizard/RegistryStore.kt:clear | T/wizard/RegistryStoreDataVersionTest::clear resets the counter | **COVERED** (test in §F; prima: PARTIAL) |
| SEC-071 | Deep link a una route del wallet dopo un blocco (nessun wallet aperto) → torna alla HOME del tracker | D/App.kt:WalletWorkspaceRoute | — | **COVERED** (test in §F; prima: UNCOVERED) |
| SEC-072 | Ri-autenticazione con esito tipizzato (Match / WrongPin / StorageError); solo WrongPin conta | D/wizard/DevicePinSession.kt:verify | T/wizard/DevicePinSessionTest::verify maps true to Match and false to WrongPin, ::verify turns a storage failure into StorageError with a proper message, ::verify never reports an unrecognised error as a wrong PIN, ::only WrongPin outcomes count towards the device lockout | COVERED — nota: `verify` non ha più chiamanti in produzione (`matchesDevicePin` non esiste più nell'FFI): codice morto dopo il passaggio a watch-only. |

### 6. Reset locale (wipe / factory reset)

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|----|------|------|------|------|
| SEC-073 | La frase di conferma è `DELETE` (spazi ai lati ignorati, maiuscole obbligatorie) | D/wizard/LocalWipe.kt:isConfirmed | T/screens/SecretActionsUiTest::wipe dialog stays disabled for anything but DELETE, T/wizard/SecurityAuditPinTest::local wipe deletes databases before kdf and removes pin history | COVERED |
| SEC-074 | Cancel dopo aver digitato DELETE: nessun file toccato, wallet ancora aperto | D/screens/SettingsWalletSections.kt:WipeSection | T/screens/SecretActionsUiTest::wipe Cancel keeps every file and the unlocked wallet | COVERED |
| SEC-075 | Wipe confermato: prima rilascia gli handle, cancella i DB prima dei KDF, rimuove lo storico PIN e le date del Security Hub | D/wizard/LocalWipe.kt:wipeAll | T/screens/SecretActionsUiTest::confirmed wipe empties the data dir and drops PIN history, T/wizard/SecurityAuditPinTest::local wipe deletes databases before kdf and removes pin history, T/screens/SecurityHubTest::local wipe forgets the Security Hub dates | **COVERED** (test in §F; prima: COVERED) |
| SEC-076 | Settings → Factory reset in due tap: svuota WalletStore e torna a Welcome | D/screens/SettingsWalletSections.kt:WipeSection, D/App.kt:restartAtBootGuard | T/screens/SettingsScreenTest::Wipe is two-tap and clears the WalletStore on confirm | **COVERED** (test in §F; prima: PARTIAL) |
| SEC-077 | Un DB non cancellabile (bloccato) interrompe il wipe e tiene i KDF; messaggio "aborted" | D/wizard/WalletPaths.kt:resetChecked, D/wizard/LocalWipe.kt:describe | T/wizard/WalletPathsTest::reset_deletes_sqlite_before_kdf_and_salt_so_a_locked_db_stays_decryptable, ::reset_only_sqlite_present_leaves_boot_in_clean_state | **COVERED** (test in §F; prima: PARTIAL) |
| SEC-078 | Wipe su una directory vuota → "nothing to wipe" | D/wizard/LocalWipe.kt:describe | — | **COVERED** (test in §F; prima: UNCOVERED) |

### 7. Settings — persistenza

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|----|------|------|------|------|
| SEC-079 | All'avvio settings.json viene letto prima del primo frame; poi ogni setter salva subito | D/Main.kt, D/settings/SettingsPersistence.kt:rehydrate, D/settings/SettingsStore.kt:enablePersistence | T/settings/SettingsPersistenceTest::enablePersistence writes the file on the next setter, ::every persisted setter triggers a re-save, ::reset clears the persistence hook, ::rehydrate returns false on missing file and leaves state untouched | **PARTIAL (saltato)** (test in §F; prima: PARTIAL) |
| SEC-080 | Round-trip dei campi, default, campi mancanti o sconosciuti, enum non riconosciuto | D/settings/SettingsPersistence.kt:snapshot/apply/load | T/settings/SettingsPersistenceTest::snapshot defaults match SettingsStore defaults on a freshly reset store, ::snapshot reflects mutated state, ::save then load round-trips every field, ::load tolerates unknown extra fields (forward compatibility), ::load tolerates missing fields (uses defaults), ::apply with an unrecognised enum value falls back silently, ::load returns null on missing file (first launch) | COVERED |
| SEC-081 | Salvataggio atomico (rename) resistente a un crash a metà; file con permessi owner-only (POSIX) | D/settings/SettingsPersistence.kt:save, D/wizard/WalletPaths.kt:restrictToOwner | T/settings/SettingsPersistenceTest::save survives a partial-write crash via atomic rename, ::save writes a UTF-8 JSON file and creates parent dirs | **COVERED (CI Linux)** (test in §F; prima: PARTIAL) |
| SEC-082 | File corrotto o di versione futura: backup `.corrupt-<ts>`, Tor resta acceso se lo era, banner chiudibile; senza backup non si installa la scrittura | D/settings/SettingsPersistence.kt:recoverFromCorrupt, :backupCorrupt, :unsafeToPersist, D/ui/SettingsWarningBanner.kt | T/settings/SettingsPersistenceTest::corrupt file is backed up, Tor fails closed and a warning is raised, ::future version is treated as corrupt and keeps Tor off when it was off, ::load fails on a future version snapshot, T/screens/SettingsNetworkSecurityTest::corrupt settings file raises a dismissable banner and keeps Tor on, ::no banner when settings loaded fine | **COVERED** (test in §F; prima: PARTIAL) |
| SEC-083 | I campi delle funzioni di firma rimosse vengono ignorati in lettura e mai scritti | D/settings/SettingsPersistence.kt | T/settings/SettingsPersistenceTest::load ignores fields of removed signing features in older snapshots, ::saved snapshot carries no field of removed signing features | COVERED |
| SEC-084 | Migrazione delle vecchie impostazioni di unità (btcUnit / unit format) all'unità unica | D/settings/SettingsPersistence.kt:resolveUnitFormat | T/settings/SettingsPersistenceTest::legacy btcUnit BTC migrates to the unified BTC unit, ::legacy non-default Bitcoin Lens unit format wins over btcUnit, ::legacy untouched unit settings land on sats, ::bitcoinUnit key wins over the legacy pair and unknown density falls back | COVERED |
| SEC-085 | Reset dello store ai valori di default | D/settings/SettingsStore.kt:reset | T/settings/SettingsStoreTest::reset puts everything back to defaults, ::reset clears fiat currency and disables auto fetch investments, ::reset puts Bitcoin Lens preferences back to defaults | COVERED |

### 8. Settings — aspetto e preferenze

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|----|------|------|------|------|
| SEC-086 | Tema System (default, segue l'OS) / Light / Dark / Neon, applicato a tutta l'app | D/screens/SettingsAppearanceSections.kt:ThemeSection, D/settings/SettingsStore.kt:ThemeMode.usesDarkPalette, D/Main.kt | T/screens/SettingsScreenTest::the three theme chips render, ::tapping Dark writes ThemeMode_Dark to SettingsStore, ::tapping Light writes ThemeMode_Light, ::appearance shows System theme selected and sats unit by default, T/settings/SettingsStoreTest::default theme mode is System, ::setThemeMode updates the reactive state, ::default theme is System and follows the OS light-dark setting | **COVERED** (test in §F; prima: PARTIAL) |
| SEC-087 | Un tema scelto esplicitamente resta dopo il riavvio e dopo l'aggiornamento | D/settings/SettingsPersistence.kt | T/settings/SettingsPersistenceTest::existing explicit theme is kept when upgrading, ::display preferences round-trip through settings json, ::snapshot defaults for display preferences | COVERED |
| SEC-088 | Unità Sats (default) / BTC / Auto + notazione BIP177, con esempio aggiornato al volo | D/screens/SettingsAppearanceSections.kt:BitcoinUnitSection, D/settings/SettingsStore.kt:setUnitFormat/setBip177Notation | T/screens/SettingsScreenTest::unit chips and BIP177 toggle write SettingsStore and update the example, T/settings/SettingsStoreTest::default btc unit is SAT, ::setBtcUnit toggles between SAT and BTC, ::btcUnit is a projection of the unified unit format, ::BIP177 notation is off by default and toggles, ::default unit format is always sats, ::setUnitFormat persists the choice in the reactive state | COVERED |
| SEC-089 | Densità Compact (32dp, default) / Ultra-dense | D/screens/SettingsAppearanceSections.kt:DensitySection | T/screens/SettingsScreenTest::density chips write SettingsStore, T/settings/SettingsStoreTest::default density is Compact with 32dp rows, ::density is selectable and reset restores Compact, T/ui/ThemeTokensTest::density defaults to Compact 32dp rows, ::density override reaches the tree | COVERED |
| SEC-090 | Convenzione colori Western / Eastern (verde e rosso invertiti) | D/screens/SettingsAppearanceSections.kt:ColorConventionSection, D/settings/SettingsStore.kt:setColorConvention | T/settings/SettingsStoreTest::default color convention is Western, ::setColorConvention flips between Western and Eastern | **COVERED** (test in §F; prima: PARTIAL) |
| SEC-091 | Valuta fiat (NONE di default = overlay spento); il cambio aggiorna subito i prezzi | D/screens/SettingsAppearanceSections.kt:FiatCurrencySection, D/Main.kt (refreshNow) | T/settings/SettingsStoreTest::default fiat currency is NONE (overlay disabled out of the box), ::setFiatCurrency updates the reactive state, T/prices/FiatCurrencyTest | **PARTIAL** (test in §F; prima: PARTIAL) |
| SEC-092 | Auto-fetch dei prezzi investimenti (spento di default); all'accensione scarica subito | D/screens/SettingsSystemSections.kt:InvestmentPriceFetchSection, D/Main.kt | T/settings/SettingsStoreTest::default autoFetchInvestmentPrices is false (off by default), ::setAutoFetchInvestmentPrices flips the reactive state | **PARTIAL** (test in §F; prima: PARTIAL) |
| SEC-093 | Bitcoin Lens: interruttori delle superfici fiat (CSV banca, tx del mese, export fiscali, ricevute, overlay NW) | D/screens/SettingsAppearanceSections.kt:BitcoinLensSection | T/settings/SettingsStoreTest::default Bitcoin Lens fiat surfaces are all ON, ::Bitcoin Lens fiat surface toggles flip independently, T/settings/SettingsPersistenceTest::save then rehydrate restores the 5 Bitcoin Lens preferences, ::older JSON without Bitcoin Lens fields rehydrates with safe defaults | **COVERED** (test in §F; prima: PARTIAL) |
| SEC-094 | Moduli tracker (Investments, Budget & Bills, Goals, Manual assets, Analytics Labs) attivabili da Settings | D/screens/SettingsSystemSections.kt:TrackerModulesSection, D/settings/SettingsStore.kt:setModule* | — | **COVERED** (test in §F; prima: UNCOVERED) |
| SEC-095 | Privacy: interruttore "Start with balances hidden" in Settings, salvato | D/screens/SettingsAppearanceSections.kt:PrivacySection | T/settings/SettingsPersistenceTest::privacyAutoHide setter is captured in the snapshot, ::privacyAutoHide round-trips through save and rehydrate, ::missing privacyAutoHide field in older JSON defaults to false | **COVERED** (test in §F; prima: PARTIAL) |

### 9. Settings — lingua e i18n

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|----|------|------|------|------|
| SEC-096 | Lingua System / English / Italiano: salva il tag, applica `Locale.setDefault`, l'utente resta sulla schermata | D/screens/SettingsSystemSections.kt:LanguageSection, D/Main.kt (LaunchedEffect localeOverride) | T/screens/SettingsScreenTest::language picker writes the right tag to SettingsStore, T/settings/SettingsStoreTest::setLocaleOverride trims and treats blank as null, ::default esplora url and locale override are null | **PARTIAL (saltato)** (test in §F; prima: PARTIAL) |
| SEC-097 | EN e IT hanno le stesse chiavi, nessun duplicato, nessun valore vuoto, segnaposto uguali e posizionali | composeResources strings*.xml | T/i18n/StringsParityTest::EN and IT have exactly the same keys, ::no key is defined twice, ::no empty values, ::format placeholders match between EN and IT, ::multiple placeholders are positional | COVERED |
| SEC-098 | Nessuna parola italiana in EN e viceversa; i valori IT lunghi sono tradotti | composeResources | T/i18n/StringsLanguageTest::long IT values are translated, not copied from EN, ::EN values contain no Italian words, ::IT values contain no English words | COVERED |
| SEC-099 | Nessun testo hardcoded nella UI (Text/label/placeholder/contentDescription/title) | tutta la UI | T/i18n/HardcodedUiStringScanTest::no hardcoded user-facing literals in UI code, ::the scan flags prose and passes technical tokens | **PARTIAL** (test in §F; prima: COVERED) |
| SEC-100 | Messaggi fuori composizione (errori PIN, wipe) localizzati con la lingua corrente | D/wizard/LocalizedText.kt:localizedText | T/wizard/SecurityAuditPinTest::downgrade refusals are classified as NewerVersion, not as a wrong PIN (solo EN) | **COVERED** (test in §F; prima: PARTIAL) |

### 10. Esplora endpoint e rete

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|----|------|------|------|------|
| SEC-101 | Endpoint di default per rete (mainnet / testnet4 / signet su mempool.space, regtest su localhost) | D/settings/EsploraEndpoint.kt:defaultFor/current | T/settings/EsploraEndpointTest::current returns the mempool_space signet default when no override is set, ::current(network) returns each network's own default, T/screens/SettingsNetworkSecurityTest::a mainnet wallet shows the mainnet default and its own override only | COVERED |
| SEC-102 | Override per rete: Save e Reset in Settings valgono solo per la rete del wallet attivo | D/screens/SettingsSystemSections.kt:EsploraEndpointSection, D/settings/SettingsStore.kt:setEsploraUrl(network) | T/screens/SettingsScreenTest::Esplora field shows the default URL in the active line when no override is set, ::typing a URL and tapping Save persists it as override, ::Reset wipes the override back to null, ::Esplora section names its network and edits only that network, T/settings/EsploraEndpointTest::an override applies only to its own network, ::current returns the override when SettingsStore has one, ::clearing the override falls back to the default | COVERED |
| SEC-103 | URL non valido (non http/https, senza host, garbage) rifiutato inline e mai salvato | D/settings/SettingsStore.kt:isValidEsploraUrl | T/screens/SettingsNetworkSecurityTest::invalid Esplora URLs are rejected inline and never saved | COVERED (manca un unit test diretto su `isValidEsploraUrl` per `javascript:` / `file://`) |
| SEC-104 | URL con spazi viene ripulito; un URL vuoto equivale al default | D/settings/SettingsStore.kt:setEsploraUrl | T/settings/SettingsStoreTest::setEsploraUrl trims whitespace and treats blank as null, T/settings/EsploraEndpointTest::blank override is treated as default (SettingsStore strips blank) | COVERED |
| SEC-105 | Avviso "http in chiaro" solo per host remoti (localhost, 127.0.0.1, ::1 e .onion esenti), anche con Tor acceso | D/screens/SettingsScreen.kt:isPlaintextRemoteHttp, D/settings/SettingsStore.kt:esploraUrlWarning | T/settings/EsploraUrlWarningTest (11 test: http remote host returns a warning, … ftp scheme returns null), T/screens/SettingsNetworkSecurityTest::plaintext http warning appears for remote hosts only, T/screens/ExplorerLinkTest::plaintext http warning applies with Tor on too | COVERED — nota: due implementazioni divergenti. `esploraUrlWarning` (usata solo nel log di `SettingsPersistence`) NON esenta `.onion`, `isPlaintextRemoteHttp` (UI) sì. Conviene unificarle. |
| SEC-106 | Riconoscimento della rete dal record del registry (stringhe sconosciute rifiutate); migrazione del vecchio override globale alla rete del suo path | D/settings/EsploraEndpoint.kt:networkOf, D/settings/SettingsPersistence.kt:legacyEsploraNetwork | T/settings/EsploraEndpointTest::networkOf parses registry network strings and refuses unknown ones, ::legacy global override migrates to the network its path names | COVERED |
| SEC-107 | Settings mostra la rete attiva con un suggerimento su come cambiarla | D/screens/SettingsSystemSections.kt:NetworkSection | — | **COVERED** (test in §F; prima: UNCOVERED) |
| SEC-108 | Apertura di un wallet con rete sconosciuta o id assente → messaggio di errore + Back | D/App.kt:OpenWalletRoute | — | **PARTIAL** (test in §F; prima: UNCOVERED) |

### 11. Tor

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|----|------|------|------|------|
| SEC-109 | Tor spento di default; proxy di default `socks5h://127.0.0.1:9050` | D/settings/SettingsStore.kt | T/settings/SettingsStoreTest::tor is disabled by default and proxy points at the standard SOCKS port | COVERED |
| SEC-110 | Accendere Tor mostra il campo proxy; spegnerlo lo nasconde e `currentProxy()` torna null | D/screens/SettingsSystemSections.kt:TorSection, D/settings/EsploraEndpoint.kt:currentProxy | T/screens/SettingsScreenTest::Tor toggle reveals the proxy field and writes torEnabled=true, T/screens/SettingsNetworkSecurityTest::turning Tor off hides the proxy field and drops the proxy, T/settings/EsploraEndpointTest::currentProxy is null when Tor is disabled, ::currentProxy returns the configured Tor proxy when Tor is on, ::currentProxy returns the user-customised proxy URL, T/settings/SettingsStoreTest::setTorEnabled flips the reactive state | COVERED |
| SEC-111 | Proxy valido solo nella forma socks5/socks5h://host:porta (1–65535); l'input non valido viene scartato; socks5 salvato come socks5h | D/settings/SettingsStore.kt:isValidTorProxyUrl/normalizeTorProxyUrl/setTorProxyUrl | T/screens/SettingsNetworkSecurityTest::Tor proxy rejects invalid URLs and stores socks5 as socks5h, T/settings/SettingsStoreTest::setTorProxyUrl trims and falls back to default when blank, T/settings/SettingsPersistenceTest::socks5 proxy is normalised to socks5h | COVERED |
| SEC-112 | La sync passa il proxy all'FFI quando Tor è acceso, null quando è spento | D/screens/DashboardScreen.kt (auto-sync) | T/screens/DashboardScreenTest::auto-sync passes the SettingsStore Tor proxy to the FFI, ::auto-sync passes a null proxy when Tor is disabled | COVERED |
| SEC-113 | Rust: parsing del proxy SOCKS (socks5h, socks5, senza schema) e URL canonico per ureq | O/tor.rs:parse_socks_proxy | O/tor.rs::parse_socks_proxy_accepts_socks5h_socks5_and_bare, ::ureq_proxy_url_is_canonical_socks5, ::ureq_proxy_maps_socks5h_to_socks5, ::base64_matches_rfc4648_vectors | COVERED |
| SEC-114 | Bridge CONNECT→SOCKS5: DNS risolto da Tor, token obbligatorio, solo l'host Esplora configurato, fail-closed se il proxy è giù | O/tor.rs:ConnectBridge | O/tor.rs::socks5_connect_sends_domain_unresolved, ::bridge_tunnels_allowed_target_with_remote_dns, ::bridge_rejects_other_targets_and_missing_auth, ::bridge_fails_closed_when_socks_proxy_is_down, ::esplora_client_through_bridge_end_to_end, ::esplora_client_fails_closed_without_reachable_proxy | COVERED |
| SEC-115 | Tor reale (nightly): l'uscita passa da Tor, Esplora .onion, fail-closed con proxy morto senza connessioni dirette | O/tor_live_tests.rs | O/tor_live_tests.rs::tor_live_esplora_tip_height_through_bridge, ::tor_live_esplora_full_wallet_sync_through_bridge, ::tor_live_bridge_path_exits_through_tor, ::tor_live_esplora_fails_closed_with_dead_proxy, ::tor_live_wallet_sync_fails_closed_with_dead_proxy, ::tor_live_onion_esplora_blockstream | COVERED (solo nightly, feature `tor-tests`) |
| SEC-116 | Timeout Esplora più lungo con proxy (90 s contro 30 s in clearnet) | O/common.rs:esplora_timeout_secs | — | **COVERED** (test in §F; prima: UNCOVERED) |
| SEC-117 | Prezzi e tassi via Tor con DNS remoto; fail-closed se il proxy è giù | D/prices/PriceFetcher*, O/btc_rate_history.rs:rate_agent/with_proxy | T/prices/PriceFetcherTorTest, T/prices/PriceFetcherLiveTorTest (torTest), O/btc_rate_history.rs::with_proxy_rejects_non_socks_proxy, ::rate_fetch_fails_closed_when_proxy_is_down, O/tor_live_tests.rs::tor_live_rate_path_exits_through_tor, ::tor_live_rate_agent_fails_closed_with_dead_proxy | **COVERED** (test in §F; prima: COVERED) |
| SEC-118 | Aggiunta di un wallet mainnet con Tor spento → dialog di avviso privacy (Cancel / Continue without Tor) | D/screens/AddWatchScreen.kt | — | **COVERED da area B** (test in §F; prima: UNCOVERED) |

### 12. Modalità privacy (mascheramento importi)

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|----|------|------|------|------|
| SEC-119 | Ctrl/Cmd+Shift+H attiva e disattiva la privacy da ogni schermata, anche con il focus in un campo; tenendolo premuto cambia stato una volta sola | D/ui/PrivacyModeUi.kt:PrivacyShortcut, D/Main.kt (onPreviewKeyEvent) | T/screens/PrivacyModeTest::ctrl shift H is the privacy shortcut, ::holding the shortcut toggles once, not on every key repeat | COVERED |
| SEC-120 | L'icona occhio nella barra di stato attiva e disattiva la privacy | D/ui/PrivacyModeUi.kt:PrivacyToggleButton | T/screens/PrivacyModeTest::footer eye icon toggles the privacy mode | COVERED |
| SEC-121 | Importi mascherati a larghezza fissa, senza segno, colori neutri; Auto non passa mai a BTC mentre è mascherato | D/ui/money/AmountMask.kt, D/ui/money/BitcoinAmountFormat.kt | T/ui/money/AmountMaskTest::bitcoin formatter masks every unit with a fixed width and no sign, ::privacy off leaves the formatters untouched, ::explicit masked false wins over the global flag, ::fiat helpers keep the currency prefix and hide the value, ::amount signs disappear while masked, ::masked Auto never switches a column, axis or header to BTC, T/screens/PrivacyModeTest::masked columns keep sats in the header and neutral sign colours, T/ui/money/SignedAmountScanTest | COVERED |
| SEC-122 | Grafici, tooltip e viste tabella mascherati | D/ui/money/*, D/ui/ChartTable.kt | T/screens/PrivacyModeTest::privacy mode hides every amount in representative composables, ::the same composables show amounts when privacy mode is off, ::chart table view is masked too, T/ui/money/AmountMaskTest::chart and legacy helpers are masked too | COVERED |
| SEC-123 | "Start with balances hidden" maschera all'avvio e dopo ogni blocco; un blocco non rivela mai importi nascosti a mano | D/settings/SettingsStore.kt:applyStartHidden | T/ui/money/AmountMaskTest::start hidden arms the session flag but a lock never reveals | COVERED |
| SEC-124 | settings.json illeggibile → si parte con gli importi nascosti | D/settings/SettingsPersistence.kt:recoverFromCorrupt | T/screens/PrivacyModeTest::an unreadable settings file starts with amounts hidden | COVERED |
| SEC-125 | Export CSV con privacy attiva chiede conferma (contiene i valori reali) | D/screens/TrackingCsvExport.kt | T/screens/PrivacyModeTest::export asks for confirmation only while privacy mode is on | COVERED |
| SEC-126 | Le superfici di conferma mostrano comunque gli importi (RevealAmounts) | D/ui/money/AmountMask.kt:RevealAmounts | T/screens/PrivacyModeTest::payment confirmation surfaces reveal amounts | COVERED |
| SEC-127 | Le schermate non formattano importi aggirando i formatter mascherati (scansione del sorgente) | tutte le schermate | T/screens/PrivacyModeTest::screens never format amounts with the unmasked shared formatters, T/ui/money/AmountStringGuardTest | COVERED |

### 13. Security Hub

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|----|------|------|------|------|
| SEC-128 | Stato puro di ogni riga: PIN (OK, o INFO se manca), Auto-lock OK, Connessione (OK con Tor, ATTENTION su mainnet in chiaro, INFO altrove), Wallets INFO, Labels | D/screens/SecurityHub.kt:securityChecks | T/screens/SecurityHubTest::clearnet mainnet needs attention, ::exported labels and Tor make every counted check OK, ::clearnet on signet is only informative and no wallet makes labels informative, ::missing device PIN is informative, not counted, ::there is one row per check, in display order | COVERED |
| SEC-129 | Punteggio "N di M controlli OK" (le righe INFO non contano) | D/screens/SecurityHub.kt:securityScore | T/screens/SecurityHubTest::exported labels and Tor make every counted check OK, ::missing device PIN is informative, not counted | COVERED |
| SEC-130 | Lo Hub mostra le righe e ogni azione porta alla schermata giusta (wallet, Settings, Wallet settings) | D/screens/SecurityHub.kt:SecurityHubScreen/rowContent | T/screens/SecurityHubTest::hub renders rows and routes the actions, ::labels row has no action without an open wallet | COVERED |
| SEC-131 | Lo Hub si apre dallo scudo nella barra di stato, da Settings e dalla palette | D/ui/FooterStatusBar.kt:SecurityHubShieldButton, D/screens/SettingsScreen.kt, D/App.kt | — | **PARTIAL** (test in §F; prima: UNCOVERED) |
| SEC-132 | Data dell'ultimo export delle label salvata (solo la data) e cancellata dal wipe | D/settings/SettingsStore.kt:markLabelsExported/clearSecurityState | T/screens/SecurityHubTest::labels export timestamps round-trip through settings json, ::local wipe forgets the Security Hub dates | COVERED |
| SEC-133 | Riga Wallets: "N watch-only (BHODL never holds private keys)" | D/screens/SecurityHub.kt:rowContent | — | **COVERED** (test in §F; prima: UNCOVERED) |
| SEC-134 | Lo stato è comunicato con icona e testo, mai solo con il colore | D/screens/SecurityHub.kt:statusVisual | T/screens/SecurityHubTest::hub renders rows and routes the actions | **COVERED** (test in §F; prima: PARTIAL) |

### 14. Link all'explorer

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|----|------|------|------|------|
| SEC-135 | URL di mempool.space in base alla rete; su regtest il bottone è nascosto; host dell'endpoint mostrato | D/screens/ExplorerLink.kt:ExplorerLinks | T/screens/ExplorerLinkTest::explorer base url follows the network | COVERED |
| SEC-136 | Con Tor spento il link apre subito il browser | D/screens/ExplorerLink.kt:ExplorerLinkButton | T/screens/ExplorerLinkTest::without Tor the link opens directly | COVERED |
| SEC-137 | Con Tor acceso un dialog avverte che il browser è fuori da Tor e offre "Copy txid" / "Open anyway" | D/screens/ExplorerLink.kt:ExplorerLinkButton | T/screens/ExplorerLinkTest::with Tor the link asks first and offers copying the txid | COVERED |

### 15. Messaggi di errore

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|----|------|------|------|------|
| SEC-138 | Messaggi di errore in UI senza path assoluti, troncati, con fallback se vuoti | D/ui/ErrorMessages.kt:userMessage/sanitizeErrorDetail | T/ui/ErrorMessagesTest::windows and unix absolute paths are redacted, ::URLs are not mistaken for paths, ::long messages are truncated and blank ones fall back, ::an FFI error with a path reaches the wallet settings redacted | COVERED |
| SEC-139 | ErrorCard: titolo e messaggio, Retry facoltativo, dettagli nascosti finché non si aprono | D/ui/ErrorCard.kt:ErrorCard | T/ui/ErrorCardTest::title and message render, ::retry button hidden when onRetry is null, ::clicking Retry fires the callback, ::details are hidden by default and revealed by the toggle | COVERED |
| SEC-140 | Errori di rete tradotti in suggerimenti leggibili | D/ui/ErrorCard.kt:prettifyNetworkError | T/ui/ErrorCardTest::prettifyNetworkError maps known patterns to user-readable hints, ::prettifyNetworkError handles null and blank input | COVERED |
| SEC-141 | Mappatura degli errori di broadcast / PayJoin | D/ui/ErrorCard.kt:prettifyBroadcastError | T/ui/ErrorCardTest (7 test prettifyBroadcastError …) | COVERED — nota: codice morto, nessun chiamante dopo il passaggio a watch-only. Va rimosso insieme ai test. |
| SEC-142 | Un messaggio localizzato per ogni tipo di errore PIN (wrong / network / storage / newer / other) | D/wizard/PinErrors.kt:messageFor | T/wizard/SecurityAuditPinTest::downgrade refusals are classified as NewerVersion, not as a wrong PIN | **COVERED** (test in §F; prima: PARTIAL) |

### 16. Navigazione e app shell

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|----|------|------|------|------|
| SEC-143 | Le voci della rail dipendono dai moduli (Budget, Goals, Portfolio, Reports); Home, Activity, Wallet e Settings (in fondo) sempre presenti | D/ui/TwoTabShell.kt:TwoTabShell | — | **COVERED** (test in §F; prima: UNCOVERED) |
| SEC-144 | Un click su una sezione sostituisce la voce del tracker nel back stack (nessun accumulo) | D/App.kt:navigateToSection | — | **COVERED** (test in §F; prima: UNCOVERED) |
| SEC-145 | Rail → Wallet senza wallet aperto porta alla HOME del tracker; con un wallet aperto al workspace | D/App.kt:handleRailTab | — | **COVERED** (test in §F; prima: UNCOVERED) |
| SEC-146 | Settings si apre a schermo intero e Back torna alla sezione di partenza | D/App.kt (Route.Settings), D/screens/SettingsScreen.kt | T/screens/SettingsScreenTest::back button fires onBack | **COVERED** (test in §F; prima: PARTIAL) |
| SEC-147 | Settings rimanda a Wallet settings (le sezioni del wallet sono lì) e allo Hub | D/screens/SettingsScreen.kt:WalletSettingsPointer | T/screens/SettingsScreenTest::wallet-scoped sections moved to Wallet settings, with a pointer here | COVERED |
| SEC-148 | Mappatura sezione ↔ voce della rail; una sezione sconosciuta nel back stack ricade su HOME | D/navigation/Routes.kt:TrackerSection.fromWire, D/ui/TwoTabShell.kt:Tab.fromSection/toSection | — | **COVERED** (test in §F; prima: UNCOVERED) |
| SEC-149 | Sotto-tab del workspace wallet raggiungibili via `WalletSection(tab)` | D/navigation/Routes.kt, D/screens/DashboardScreen.kt | T/screens/DashboardScreenTest (sub-tabs switch …) | COVERED (sovrapposizione con l'area wallet) |
| SEC-150 | Scorciatoie del tracker: 1–6 per le sezioni, R refresh, ←/→ periodo, Esc azzera i filtri | D/screens/TrackingScreen.kt (onKey) | — | **UNCOVERED (saltato)** (test in §F; prima: UNCOVERED) |
| SEC-151 | Palette comandi Ctrl/Cmd+K: filtro su etichetta e keyword, messaggio "nessun risultato", esecuzione + chiusura, Esc | D/screens/CommandPalette.kt:CommandPalette | — | **PARTIAL** (test in §F; prima: UNCOVERED) |
| SEC-152 | Navigazione adattiva: rail su desktop, bottom bar solo su host compatti; finestra minima 960×640, apertura 1280×800 | D/ui/AdaptiveNavigation.kt:navigationLayoutFor, D/WindowSizing.kt | T/screens/AccessibilityTablesTest::navigation maps the rail to a bottom bar only on compact hosts | COVERED (il `minimumSize` AWT in Main.kt è manuale) |
| SEC-153 | Freccia indietro (`bhodl.backarrow`) su Add watch, Contacts, Settings, Security Hub, Transaction details | D/ui/BhodlBackArrow.kt, D/ui/BhodlTopBar.kt | T/screens/SettingsScreenTest::back button fires onBack | **COVERED** (test in §F; prima: PARTIAL) |
| SEC-154 | Barra di stato: rete (pill colorata), Tor o clearnet, host Esplora, numero wallet, ultima sync | D/ui/FooterStatusBar.kt:BhodlFooterStatusBar | T/screens/PrivacyModeTest::footer eye icon toggles the privacy mode (solo rendering) | **COVERED** (test in §F; prima: PARTIAL) |

### 17. Accessibilità

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|----|------|------|------|------|
| SEC-155 | Ogni grafico ha una vista tabella con gli stessi dati | D/ui/ChartTable.kt | T/screens/AccessibilityTablesTest::table builders mirror the chart data, ::chart card toggle swaps the chart for its table | COVERED |
| SEC-156 | Righe di tabella raggiungibili da tastiera (frecce), contorno di focus | D/ui/TableRowFocus.kt:tableRowFocusDirection | T/screens/AccessibilityTablesTest::arrow keys move row focus, other keys do not | COVERED |
| SEC-157 | L'altezza delle righe segue la densità ovunque | D/ui/DensityRows.kt, D/ui/ChartTable.kt | T/screens/AccessibilityTablesTest::chart table rows follow the density setting, ::multi-line rows scale with density | COVERED |
| SEC-158 | Colonne nascoste per priorità su schermi stretti; il rango 0 resta sempre | D/ui/ColumnPriority.kt:visibleColumns | T/screens/TableColumnHidingTest (24 test), T/screens/AccessibilityTablesTest::every table keeps at most three columns on a 360dp phone, ::transactions table drops confirmations then status then date | COVERED |
| SEC-159 | Contrasto ≥ 3:1 per i bordi dei controlli in ogni tema; dialog e container usano le superfici dell'app | shared theme tokens | T/ui/ThemeTokensTest::control boundaries reach 3 to 1 contrast in every theme, ::dialog and container roles use app surfaces | COVERED |
| SEC-160 | Descrizioni accessibili per lettori di schermo (occhio, scudo, grafici "switch to table view") | D/ui/ChartTable.kt:chartSemantics, D/ui/PrivacyModeUi.kt, D/ui/FooterStatusBar.kt | — | **COVERED** (test in §F; prima: UNCOVERED) |

### 18. Wallet settings (sovrapposizione con l'area wallet)

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|----|------|------|------|------|
| SEC-161 | Rinomina del wallet: nome vuoto o già usato rifiutato, il nome nuovo appare in header e Tracker | D/screens/SettingsWalletSections.kt:RenameWalletSection, U/lib.rs:rename_wallet | — | **COVERED** (test in §F; prima: UNCOVERED) |
| SEC-162 | Descriptor watch-only mostrato solo su richiesta; un wallet a lista di indirizzi dice che non ne ha | D/screens/SettingsWalletSections.kt:ShowWatchOnlyDescriptorSection | T/screens/SecretActionsUiTest::watch-only descriptor is exported only on request, ::address-list wallet reports that it has no descriptor, S/UniffiSmokeTest::public descriptor carries the registered account xpub, ::address-list wallet has no descriptor and is flagged | COVERED |
| SEC-163 | La copia di xpub/descriptor si cancella dalla clipboard dopo 60 s, solo se contiene ancora quel valore | D/screens/SettingsWalletSections.kt:copyToClipboardWithAutoClear | — | **COVERED** (test in §F; prima: UNCOVERED) |
| SEC-164 | "Remove this wallet" richiede un secondo tap; poi cancella la riga e il file watch-state | D/screens/SettingsWalletSections.kt:RemoveThisWalletSection, U/lib.rs:remove_wallet | T/screens/SecretActionsUiTest::remove this wallet needs a second tap, U/lib.rs::remove_wallet_deletes_the_watch_state_file | **COVERED** (test in §F; prima: PARTIAL) |
| SEC-165 | Export / import delle label BIP-329 dalla UI (gestione di errori e annullamento) | D/screens/SettingsLabelsSection.kt:LabelsSection | T/screens/LabelsSectionTest::Export with no wallet shows wallet-not-unlocked error, ::Export happy path calls FFI then save dialog and surfaces success, ::Export cancel keeps the section idle (no status no error), ::Import happy path forwards the JSONL to the FFI and reports the count, ::Import surfaces the read error from the file dialog, ::FFI export failure surfaces as error | COVERED |

---

### Riepilogo

| Stato | N |
|------|---:|
| COVERED | 93 |
| PARTIAL | 37 |
| UNCOVERED | 35 |
| **Totale** | **165** |

### Osservazioni emerse durante l'inventario

1. **Codice morto dopo il passaggio a watch-only**: `DevicePinSession.verify` / `DevicePinCheck` (nessun chiamante: `matchesDevicePin` non esiste più nell'FFI) e `prettifyBroadcastError` (nessun chiamante). Sono testati, ma andrebbero rimossi insieme ai loro test.
2. **Test-book non allineato**: §46.1b cita `esplora_timeout_is_larger_only_when_a_proxy_is_configured`, che non esiste più nel workspace. §1.7 segna come manuale "Create disabilitato finché…", che invece è coperto da `DevicePinSetupScreenTest::create is disabled until both fields are filled`.
3. **Due funzioni per l'avviso http**: `SettingsStore.esploraUrlWarning` non esenta `.onion`, `isPlaintextRemoteHttp` sì.
4. **Lingua "System"**: scegliere System dopo EN/IT non ripristina il Locale JVM originale, perché `Locale.setDefault` gira solo con un tag non null; l'effetto si vede fino al riavvio.
5. **Seam mancanti, che sono il motivo principale degli UNCOVERED**: `App.kt` (boot guard, `lockAndRouteToGate`, `handleRailTab`, watcher idle, `OpenWalletRoute`) e le schermate PIN/LegacyCleanup aprono il registry SQLCipher reale e leggono `defaultDataDir()`. Con lambda iniettabili (`openRegistry`, `createRegistry`, `dataDir`) e qualche predicato puro estratto si potrebbe testare la maggior parte dei casi senza Argon2 né la home reale.
6. **KDF H2** (fallback e promozione del `.next` dopo una migrazione interrotta) non ha test Rust, pur essendo un percorso di recupero critico.

<a id="b"></a>

## Inventario casi d'uso — Area B: wallet watch-only

Branch `refactor/watch-only-wallets`, snapshot 2026-10-24.

Abbreviazioni dei percorsi:
- `watch.rs`, `labels.rs`, `contacts.rs`, `common.rs`, `tor.rs`, `registry.rs`, `test_support.rs` → `crates/bhodl-wallet-onchain/src/`
- `regtest.rs` → `crates/bhodl-wallet-onchain/tests/regtest.rs` (`#[ignore]` + `cfg(unix)`: **non gira in CI**)
- `tor_live_tests.rs` → feature `tor-tests`, solo nightly
- `ffi lib.rs` → `crates/bhodl-uniffi/src/commonMain/rust/lib.rs`
- Kotlin `jvmMain` → `apps/desktop/src/jvmMain/kotlin/io/bhodl/desktop/`; test Kotlin = nome classe in `apps/desktop/src/jvmTest/...`
- `UniffiSmokeTest` / `FallibleConstructorErrorTest` → `shared/src/jvmTest/kotlin/io/bhodl/ffi/`
- `(IT)` = `jvmTest/integration`, Testcontainers bitcoind+electrs (serve Docker; job CI dedicato)

Legenda stato: **COVERED** = happy path e varianti rilevanti testati in automatico; **PARTIAL** = qualcosa è testato, mancano varianti o il wiring; **UNCOVERED** = nessun test automatico.

Fatti strutturali emersi durante l'analisi:
1. **La UI aggiunge solo wallet xpub** (`AddWatchScreen` chiama solo `addWatchXpub`). `add_watch_hd` e `add_watch_addresses` esistono solo nel core/FFI: nessuna UI li espone.
2. **Non c'è una sync incrementale vera**: i wallet HD fanno sempre `full_scan` (stop_gap 20, parallelismo 4), i wallet address-list fanno `sync` spk. La "cache" è solo lo stato BDK persistito nel DB SQLCipher + `SyncCache` (60 s, solo in memoria).
3. **Codice morto**: `ui/NetworkSelector.kt` e `ui/ReceiveAddressCard.kt` non vengono mai chiamati. `RegistryHandle.is_descriptor_tracked` e `WatchHandle.update_contact_label` non sono usati dalla UI (AddWatch fa un suo confronto xpub; la rubrica non ha la rinomina).
4. **Il test regtest Rust non gira mai in CI** (`#[ignore]`, solo unix). La verifica on-chain automatica in CI viene solo dai 3 test Kotlin `(IT)`.

---

### A. Aggiunta di un wallet watch-only — core / registry / FFI

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|---|---|---|---|---|
| WAL-001 | Aggiungere un wallet da xpub BIP-86 (Taproot): il descriptor `tr(xpub/0/*)` e `tr(xpub/1/*)`, poi riaprirlo | watch.rs:`create_from_xpub`, `xpub_descriptors`; registry.rs:`add_watch_xpub`/`build_watch_xpub_record` | watch.rs::`create_from_xpub_then_load_round_trip`, `xpub_wallet_hands_out_bip86_receive_addresses_and_persists_reveals`; registry.rs::`add_watch_xpub_inserts_row_with_correct_kind_and_descriptor`; ffi lib.rs::`registry_add_watch_xpub_creates_row_and_watch_state_file`; UniffiSmokeTest::`watch xpub derives the BIP-86 signet receive addresses` | COVERED |
| WAL-002 | Aggiungere un wallet da xpub BIP-84 (`wpkh`) e BIP-49 (`sh(wpkh)`) | watch.rs:`xpub_descriptors`; registry.rs:`build_watch_xpub_record` | watch.rs::`create_from_xpub_bip84_descriptor_shape`, `create_from_xpub_bip49_descriptor_shape`; registry.rs::`add_watch_xpub_bip84_descriptor_starts_with_wpkh`, `add_watch_xpub_bip49_descriptor_starts_with_sh_wpkh` | COVERED |
| WAL-003 | Accettare chiavi SLIP-132 `zpub`/`ypub`/`vpub`/`upub`, normalizzarle a xpub/tpub e far rispettare il tipo di script che implicano | watch.rs:`normalize_xpub`, `check_implied_bip` | watch.rs::`slip132_keys_are_normalised_and_script_type_enforced` | COVERED |
| WAL-004 | Rifiutare le chiavi SLIP-132 multisig (`Zpub`/`Ypub`/`Vpub`/`Upub`) | watch.rs:`normalize_xpub` | watch.rs::`slip132_keys_are_normalised_and_script_type_enforced` (caso `Zpub`) | **COVERED** (test in §F; prima: PARTIAL) |
| WAL-005 | Rifiutare un xpub della rete sbagliata (xpub mainnet su signet e il contrario) | watch.rs:`validate_xpub_network` | watch.rs::`create_from_xpub_rejects_mainnet_xpub_when_network_is_signet`; registry.rs::`add_watch_xpub_rejects_mainnet_xpub_when_network_is_signet` | **COVERED** (test in §F; prima: PARTIAL) |
| WAL-006 | Rifiutare un xpub malformato o non valido con un errore tipizzato (e senza lasciare la riga nel registry né il file) | watch.rs:`validate_xpub_network`; ffi lib.rs:`materialise_then_register` | ffi lib.rs::`registry_add_watch_xpub_rolls_back_on_invalid_xpub`, `add_watch_failure_leaves_no_row_and_no_file`; UniffiSmokeTest::`invalid xpub is rejected with a typed WalletException` | COVERED |
| WAL-007 | Rifiutare una label vuota quando si aggiunge un wallet (xpub/HD/indirizzi) | registry.rs:`build_watch_*_record` | — | **COVERED** (test in §F; prima: UNCOVERED) |
| WAL-008 | Aggiungere un wallet HD con fingerprint + derivation path + xpub (formato Sparrow/Specter) con origin `[fp/path]` | watch.rs:`create_from_hd`, `hd_descriptors`; registry.rs:`add_watch_hd`; ffi lib.rs:`add_watch_hd` | watch.rs::`create_from_hd_round_trip`; registry.rs::`add_watch_hd_round_trip`; ffi lib.rs::`add_watch_hd_with_origin_metadata_round_trips` | COVERED |
| WAL-009 | Validare l'input HD: fingerprint ≠ 8 caratteri esadecimali, path non valido o `m`, profondità path ≠ profondità xpub, purpose non hardened | watch.rs:`hd_descriptors` | watch.rs::`hd_validation_rejects_bad_fingerprint_path_and_depth` | **COVERED** (test in §F; prima: PARTIAL) |
| WAL-010 | Rifiutare i purpose HD non supportati (44', 48' ecc.) e una SLIP-132 in contraddizione col purpose | watch.rs:`hd_descriptors` | watch.rs::`create_from_hd_unsupported_bip_path_returns_err`; registry.rs::`add_watch_hd_rejects_legacy_path` | **COVERED** (test in §F; prima: PARTIAL) |
| WAL-011 | Rifiutare un xpub HD della rete sbagliata | watch.rs:`hd_watch_descriptors` | — | **COVERED** (test in §F; prima: UNCOVERED) |
| WAL-012 | Aggiungere un wallet su una lista di indirizzi di qualsiasi tipo standard (P2PKH/P2SH/P2WPKH/P2WSH/P2TR), tracciando lo script_pubkey esatto | watch.rs:`create_from_addresses`, `ScriptWatch::open`; registry.rs:`add_watch_addresses`; ffi lib.rs:`add_watch_addresses` | watch.rs::`create_from_addresses_single_signet_address_round_trip`, `address_watch_tracks_exact_script_pubkey_of_p2tr_address`, `address_watch_supports_multiple_addresses_of_any_type`; registry.rs::`add_watch_addresses_accepts_any_standard_type_now`, `add_watch_addresses_single_signet_tr_address_round_trips`; ffi lib.rs::`add_watch_addresses_with_p2tr_round_trips` | COVERED |
| WAL-013 | Wallet a indirizzi: rifiutare lista vuota, indirizzo illeggibile o di un'altra rete | watch.rs:`validate_watch_address`, `create_from_addresses`; registry.rs:`build_watch_addresses_record` | watch.rs::`create_from_addresses_rejects_wrong_network_address`; registry.rs::`add_watch_addresses_rejects_garbage`, `add_watch_addresses_rejects_empty_list`, `add_watch_addresses_rejects_mainnet_address_when_network_is_signet` | COVERED |
| WAL-014 | Wallet a indirizzi: deduplicare preservando l'ordine e salvare la forma canonica; rilanciare su un DB esistente aggiunge solo gli indirizzi nuovi | watch.rs:`create_from_addresses`, `save_watch_address_if_absent`; registry.rs:`build_watch_addresses_record` | registry.rs::`add_watch_addresses_dedupes` | **COVERED** (test in §F; prima: PARTIAL) |
| WAL-015 | Reti supportate: mainnet, testnet4, signet, regtest; testnet4 usa i parametri testnet4 veri | registry.rs:`network_as_db_str`; common.rs:`map_network`; ffi lib.rs:`parse_network_str`; bhodl-core network.rs | watch.rs::`map_network_testnet4_uses_real_testnet4_params`; network.rs::`as_str_matches_canonical_lowercase_names`; regtest via (IT) `RegtestSyncIntegrationTest` | **COVERED** (test in §F; prima: PARTIAL) |
| WAL-016 | Creazione atomica: prima il file `watch-state/<uuid>.sqlite`, poi la riga nel registry; se un passo fallisce si rimuovono i file parziali | ffi lib.rs:`materialise_then_register`, `remove_sqlite_files` | ffi lib.rs::`add_watch_failure_leaves_no_row_and_no_file`, `registry_add_watch_xpub_rolls_back_on_invalid_xpub` | **COVERED** (test in §F; prima: PARTIAL) |
| WAL-017 | Rilevare i duplicati via descriptor esatto o xpub di account normalizzato (`is_descriptor_tracked`) | ffi lib.rs:`is_descriptor_tracked`, `extract_account_xpub`; common.rs:`DescriptorBackup::account_xpub` | ffi lib.rs::`registry_is_descriptor_tracked_*` (4 test), `extract_account_xpub_handles_*` (3 test) | COVERED (nota: la UI non usa questo metodo, vedi WAL-020) |

### B. Aggiunta di un wallet — UI (`AddWatchScreen`)

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|---|---|---|---|---|
| WAL-018 | Form "Add watch-only": label, xpub, chip di script (Taproot/NativeSegwit/NestedSegwit), chip di rete (Signet di default); il pulsante è disabilitato finché label o xpub sono vuoti | screens/AddWatchScreen.kt:`AddWatchScreen` | — | **COVERED** (test in §F; prima: UNCOVERED) |
| WAL-019 | Rifiutare una label già usata, senza distinzione maiuscole/minuscole e dopo trim | wizard/WalletLabel.kt:`isDuplicate`; AddWatchScreen.kt:`doAdd` | — | **COVERED** (test in §F; prima: UNCOVERED) |
| WAL-020 | Rifiutare un xpub già tracciato (confronto con `extractAccountXpub` sulle righe non address-list) e mostrare il wallet esistente | AddWatchScreen.kt:`doAdd` (pre-flight 2) | — | **COVERED** (test in §F; prima: UNCOVERED) |
| WAL-021 | Mainnet con Tor spento: dialog di avviso privacy; Cancel non aggiunge nulla, "Continue without Tor" procede | AddWatchScreen.kt (`pendingMainnetConfirm`) | — | **COVERED** (test in §F; prima: UNCOVERED) |
| WAL-022 | Dopo l'add: bump del `dataVersion` del registry, sync automatica best-effort sull'endpoint della rete del wallet, ritorno al tracker anche se la sync fallisce | AddWatchScreen.kt:`doAdd` (fase 2) | — | **COVERED** (test in §F; prima: UNCOVERED) |
| WAL-023 | Errore dal core (xpub non valido, rete sbagliata) mostrato come messaggio leggibile e con i path oscurati | AddWatchScreen.kt; ui/ErrorMessages.kt:`userMessage` | ErrorMessagesTest::`windows and unix absolute paths are redacted` (solo l'helper) | **COVERED** (test in §F; prima: PARTIAL) |
| WAL-024 | Aggiungere da UI un wallet HD (fingerprint+path) o una lista di indirizzi | (non implementato in UI) | — | **UNCOVERED (saltato)** (test in §F; prima: UNCOVERED) |

### C. Registry dei wallet — elenco, lettura, rinomina, rimozione, legacy

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|---|---|---|---|---|
| WAL-025 | Elencare i wallet in ordine di inserimento, tutti e tre i kind, nascondendo le righe legacy `signing` | registry.rs:`list_wallets`; ffi lib.rs:`list_wallets` | registry.rs::`add_then_list_round_trips`, `all_three_wallet_kinds_round_trip`, `add_watch_xpub_then_list_wallets_returns_it`, `legacy_signing_rows_are_hidden_from_listings`, `open_creates_empty_table` | COVERED |
| WAL-026 | Leggere un wallet per UUID (`None` se non esiste, errore se l'UUID non è valido) | registry.rs:`get_wallet`; ffi lib.rs:`get_wallet` | registry.rs::`get_wallet_returns_none_for_unknown`, `get_wallet_returns_inserted_row` | **COVERED** (test in §F; prima: PARTIAL) |
| WAL-027 | Errore controllato per kind sconosciuto o UUID corrotto in una riga del registry (DB modificato a mano) | registry.rs:`list_wallets` (`from_db_str`, `Uuid::parse_str`) | — | **COVERED** (test in §F; prima: UNCOVERED) |
| WAL-028 | Rinominare un wallet (core: `true` se esiste, `false` se no) | registry.rs:`rename_wallet`; ffi lib.rs:`rename_wallet` | registry.rs::`rename_wallet_updates_label` | **COVERED** (test in §F; prima: PARTIAL) |
| WAL-029 | Rinominare dalla UI (Wallet settings): rifiutare nome vuoto e duplicato (case-insensitive, escluso il wallet stesso), salvare, bump del dataVersion, mostrare "not found" o errore | screens/SettingsWalletSections.kt:`RenameWalletSection`; wizard/WalletLabel.kt:`isDuplicate` | — (test-book 14b.8 manuale) | **COVERED** (test in §F; prima: UNCOVERED) |
| WAL-030 | Rimuovere un wallet: riga + categorie + crypto-event kind + cost basis + link dei goal (fallback a manual) in un'unica transazione | registry.rs:`remove_wallet_with_metadata`, `purge_wallet_rows` | registry.rs::`remove_wallet_with_metadata_purges_dependents`, `removing_a_wallet_drops_its_cost_basis_rows`, `remove_wallet_returns_true_when_row_existed`, `purge_categories_for_wallet_only_touches_that_wallet`, `crypto_event_kind_purge_for_wallet` | COVERED |
| WAL-031 | La rimozione FFI cancella anche `watch-state/<id>.sqlite` e i sidecar; un secondo remove restituisce `false` | ffi lib.rs:`remove_wallet`, `remove_sqlite_files` | ffi lib.rs::`remove_wallet_deletes_the_watch_state_file`; (IT) PersistentWalletIntegrationTest::`removing a wallet and wiping leave no wallet artifact behind` | COVERED |
| WAL-032 | Rimuovere dalla UI: due tap; al secondo chiude l'handle (lock file Windows) prima di cancellare, poi torna al tracker | SettingsWalletSections.kt:`RemoveThisWalletSection` | SecretActionsUiTest::`remove this wallet needs a second tap` (solo il primo tap) | **COVERED** (test in §F; prima: PARTIAL) |
| WAL-033 | Contare ed eliminare i wallet legacy `signing` (righe + `wallets/<uuid>/`), lasciando i mirror come watch normali | registry.rs:`legacy_signing_wallet_ids`, `purge_legacy_signing_wallets`; ffi lib.rs:`legacy_signing_wallet_count`, `purge_legacy_signing_wallets` | registry.rs::`purge_legacy_signing_wallets_keeps_mirrors_as_plain_watch_wallets`; ffi lib.rs::`purge_legacy_signing_wallets_removes_rows_and_key_dirs`; UniffiSmokeTest::`fresh registry has no legacy signing wallets`; WalletPathsTest::`purgeLegacySigningData_*` | COVERED |
| WAL-034 | Schermata LegacyCleanup: checkbox obbligatoria, "Delete old wallets" / "Not now", errore se dei file restano bloccati | screens/LegacyCleanupScreen.kt | — (test-book 17b.2–17b.8 manuali) | **PARTIAL** (test in §F; prima: UNCOVERED) |
| WAL-035 | Elenco wallet nel tracker: righe con lo stesso xpub raggruppate in un solo wallet logico, wallet a indirizzi mai uniti, totale senza doppio conteggio | screens/TrackingWalletsSection.kt:`groupWalletsByXpub`; `trackedWalletGroups` | TrackerNetWorthTest::`B8 two registry rows on the same account xpub count as ONE tracked wallet`, `B8 address-list wallets never merge, even with equal descriptors`, `B8 excluded wallets drop out of the tracked count and total` | COVERED |
| WAL-036 | Tabella dei wallet (WalletListTable): colonne responsive, click sulla riga apre il wallet | screens/WalletListTable.kt | TableColumnHidingTest::`wallet list shows every column when wide`, `wallet list hides network then type when narrow`, `wallet list drops network then type` | **COVERED** (test in §F; prima: PARTIAL) |

### D. Apertura del wallet e wallet attivo

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|---|---|---|---|---|
| WAL-037 | Aprire un watch wallet per UUID con la chiave del device (nessun PIN per wallet) e ottenere un `WatchHandle` | ffi lib.rs:`open_watch`; watch.rs:`load` | ffi lib.rs::`open_watch_after_add_xpub_returns_handle_with_zero_balance`; UniffiSmokeTest::`registry reopens a watch wallet after every handle is dropped`; (IT) PersistentWalletIntegrationTest::`watch wallet round-trip preserves balance and address across handle drops` | COVERED |
| WAL-038 | Aprire un wallet inesistente o con UUID non valido → errore tipizzato | ffi lib.rs:`open_watch` | ffi lib.rs::`open_watch_with_unknown_id_returns_error`; FallibleConstructorErrorTest::`openWatch of an unknown wallet id throws WalletException` | **COVERED** (test in §F; prima: PARTIAL) |
| WAL-039 | Aprire con file di stato mancante/corrotto, chiave sbagliata o DB vuoto → `Storage` | watch.rs:`load`, `open_watch_db` | watch.rs::`wrong_encryption_key_fails_to_load` | **COVERED** (test in §F; prima: PARTIAL) |
| WAL-040 | Riaprire più volte lo stesso DB in modo idempotente (create su DB esistente = load) | watch.rs:`open_or_create_wallet` | watch.rs::`idempotent_reopen` | COVERED |
| WAL-041 | `create_from_xpub` su un DB che contiene un descriptor diverso → errore `Storage` (mismatch), nessuna sovrascrittura | watch.rs:`open_or_create_wallet`/`load_descriptor_wallet` | — | **COVERED** (test in §F; prima: UNCOVERED) |
| WAL-042 | Migrazione trasparente dei vecchi wallet a indirizzi `tr(<output key>)` verso il tracking per script | watch.rs:`load` + `ScriptWatch::open` | watch.rs::`legacy_tr_address_wallet_is_migrated_to_script_tracking` | COVERED |
| WAL-043 | Ricostruire un DB testnet4 salvato con parametri testnet3 partendo dai descriptor pubblici | watch.rs:`is_testnet3_db_on_testnet4`, `rebuild_descriptor_wallet_on`, `drop_bdk_tables` | watch.rs::`testnet4_watch_db_written_with_testnet3_params_is_rebuilt` | COVERED |
| WAL-044 | Rotta OpenWallet: se il wallet è già attivo prosegue subito, altrimenti apre e lo mette in `WalletStore`; con un errore mostra il messaggio e Back | App.kt:`OpenWalletRoute` | — (test-book 2.3 manuale) | **UNCOVERED (saltato)** (test in §F; prima: UNCOVERED) |
| WAL-045 | `WalletStore`: set/clear con chiusura dell'handle precedente (una sola volta), rete/id/label registrati, `require` lancia se vuoto | wizard/WalletStore.kt | WalletStoreTest::`clear closes the port and resets state`, `set records network, id and label`, `set without network defaults to Signet`, `replacing the port closes the previous one exactly once`, `require throws when no wallet is open` | COVERED |
| WAL-046 | Cambiando wallet (id diverso) o con clear/lock si svuota `SyncCache`, così il saldo di un wallet non finisce nell'altro | WalletStore.kt:`set`/`clear` | — | **COVERED** (test in §F; prima: UNCOVERED) |
| WAL-047 | `UniffiWalletPort.close()` è idempotente e ignora gli errori di `destroy()` | wallet/WalletPort.kt:`UniffiWalletPort.close` | (IT) indirettamente (`destroy` prima di remove) | **COVERED** (test in §F; prima: PARTIAL) |

### E. Sync Esplora, Tor, cache

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|---|---|---|---|---|
| WAL-048 | Sync di un wallet HD (full scan, stop-gap 20): rileva fondi in arrivo, conferme, UTXO | watch.rs:`sync_with_esplora`, `start_esplora_scan`, `PendingWatchScan::fetch`, `apply_esplora_scan` | (IT) RegtestSyncIntegrationTest::`watch-only wallet picks up an inbound payment after esplora sync`; (IT) PersistentWalletIntegrationTest::`watch wallet round-trip …`; regtest.rs::`regtest_watch_wallet_sees_incoming_funds` (ignorato in CI) | COVERED (solo via Docker IT) |
| WAL-049 | Sync di un wallet a indirizzi (spk sync) contro un vero Esplora | watch.rs:`ScriptWatch::sync_request`, `apply_sync_response` | watch.rs::`address_watch_counts_funds_sent_to_the_address` (risposta simulata, senza rete) | **COVERED (solo Docker IT)** (test in §F; prima: PARTIAL) |
| WAL-050 | Sync di un wallet con indirizzi oltre il gap (stop-gap 20) e uso dei change address | watch.rs:`fetch` (`full_scan(request, 20, 4)`) | — | **COVERED (solo Docker IT)** (test in §F; prima: UNCOVERED) |
| WAL-051 | Lock-friendly: la fase di rete gira senza il mutex del wallet, così le letture UI restano reattive | ffi lib.rs:`WatchHandle::sync_with_esplora` | — | **COVERED** (test in §F; prima: UNCOVERED) |
| WAL-052 | Un update del tipo sbagliato per il backend (Full su un wallet a indirizzi e viceversa) → `EsploraSync` | watch.rs:`apply_esplora_scan` | — | **COVERED** (test in §F; prima: UNCOVERED) |
| WAL-053 | Esplora irraggiungibile o con errore HTTP → `EsploraSync`; timeout 30 s in chiaro / 90 s con Tor, 3 retry | common.rs:`try_build_esplora_client`, `esplora_timeout_secs` | tor.rs::`esplora_client_fails_closed_without_reachable_proxy` (solo via proxy) | **COVERED** (test in §F; prima: PARTIAL) |
| WAL-054 | URL Esplora malformato o proxy non valido → `InvalidArgument` prima di aprire qualsiasi socket | common.rs:`try_build_esplora_client`; tor.rs:`parse_socks_proxy` | tor.rs::`parse_socks_proxy_accepts_socks5h_socks5_and_bare`, `esplora_client_fails_closed_without_reachable_proxy` (proxy `http://`) | **COVERED** (test in §F; prima: PARTIAL) |
| WAL-055 | Sync via Tor: bridge CONNECT su loopback → SOCKS5 con DNS remoto, limitato all'host Esplora, con autenticazione | tor.rs:`ConnectBridge`, `socks5_connect`, `check_connect` | tor.rs::`bridge_tunnels_allowed_target_with_remote_dns`, `bridge_rejects_other_targets_and_missing_auth`, `socks5_connect_sends_domain_unresolved`, `esplora_client_through_bridge_end_to_end`, `ureq_proxy_url_is_canonical_socks5`, `base64_matches_rfc4648_vectors`; tor_live_tests.rs::`tor_live_esplora_full_wallet_sync_through_bridge`, `tor_live_bridge_path_exits_through_tor`, `tor_live_onion_esplora_*` (nightly) | COVERED |
| WAL-056 | Fail-closed: con il proxy Tor giù la sync fallisce (502), mai un fallback in chiaro | tor.rs:`handle_connection`; common.rs:`build_esplora_client` | tor.rs::`bridge_fails_closed_when_socks_proxy_is_down`, `esplora_client_fails_closed_without_reachable_proxy`; tor_live_tests.rs::`tor_live_wallet_sync_fails_closed_with_dead_proxy`, `tor_live_esplora_fails_closed_with_dead_proxy` | COVERED |
| WAL-057 | Lo stato sincronizzato persiste: riaprendo l'app si vedono storico e saldo senza aspettare una nuova scansione | watch.rs:`apply_esplora_scan` (persist) + `load` | (IT) PersistentWalletIntegrationTest::`watch wallet round-trip …` (ma rifà `syncUntil` dopo la riapertura) | **COVERED** (test in §F; prima: PARTIAL) |
| WAL-058 | Auto-sync all'ingresso nel workspace se la cache è vecchia (background, controlli attivi); saltata se la cache ha meno di 60 s | screens/DashboardScreen.kt:`launchSync`, `LaunchedEffect(wallet)` | DashboardScreenTest::`auto-sync fires on first composition and balance lands`, `auto-sync is skipped when SyncCache is still fresh`, `cached balance is displayed before any sync happens` | COVERED |
| WAL-059 | Sync manuale (foreground, pulsante disabilitato durante la sync) che ignora la cache | DashboardScreen.kt:`WalletHeader`, `launchSync(Foreground)` | DashboardScreenTest::`manual Sync also calls the FFI` | **COVERED** (test in §F; prima: PARTIAL) |
| WAL-060 | Sync fallita: ErrorCard con messaggio leggibile, dettagli e Retry; stato header "Sync failed" | DashboardScreen.kt; ui/ErrorCard.kt:`prettifyNetworkError` | DashboardScreenTest::`sync failure renders an ErrorCard, retry triggers another sync`; ErrorCardTest::`prettifyNetworkError maps known patterns to user-readable hints` | COVERED |
| WAL-061 | La sync usa l'endpoint Esplora della rete del wallet e il proxy Tor se attivo (null se spento) | settings EsploraEndpoint:`current`, `currentProxy`, `networkOf` | DashboardScreenTest::`auto-sync passes the SettingsStore Tor proxy to the FFI`, `auto-sync passes a null proxy when Tor is disabled`; EsploraEndpointTest::`current(network) returns each network's own default`, `an override applies only to its own network`, `networkOf parses registry network strings and refuses unknown ones` | **COVERED** (test in §F; prima: PARTIAL) |
| WAL-062 | `SyncCache`: record, freshness, cooldown personalizzato, clear | wallet/SyncCache.kt | SyncCacheTest (9 test) | COVERED |
| WAL-063 | Sync di tutti i wallet nel tracker: ognuno sul proprio endpoint, handle sempre chiuso, errori contati, wallet illeggibili segnati stale | data/TrackerCache.kt (loop `registry.openWatch(row.id)`) | — | **UNCOVERED (saltato)** (test in §F; prima: UNCOVERED) |
| WAL-064 | Mostrare lo stato della sync nell'header: "Syncing…", "Synced at HH:mm", "Not synced", "Sync failed" | DashboardScreen.kt:`syncStatusText` | parziale tramite DashboardScreenTest (tag `SYNC_STATUS` non verificato) | **COVERED** (test in §F; prima: PARTIAL) |

### F. Saldo e aggregati

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|---|---|---|---|---|
| WAL-065 | Saldo totale (confermato + pending) di un wallet; 0 prima della prima sync | watch.rs:`balance`; ffi lib.rs:`balance_sat` | watch.rs::`balance_is_zero_on_fresh_wallet`, `address_watch_counts_funds_sent_to_the_address`; (IT) RegtestSyncIntegrationTest | COVERED |
| WAL-066 | Il saldo diminuisce dopo una spesa fatta da un wallet di firma esterno (UTXO spesi, change riconosciuto) | watch.rs:`balance`, `list_unspent` | — (test-book 4.8, 5.5 manuali) | **UNCOVERED (saltato)** (test in §F; prima: UNCOVERED) |
| WAL-067 | Saldo aggregato per rete: somma solo i wallet di quella rete, quelli illeggibili finiscono in `stale_wallet_ids` | ffi lib.rs:`aggregate_watch_balance`, `aggregate_watch_balance_sat` | ffi lib.rs::`aggregate_watch_balance_zero_on_empty_registry`, `aggregate_watch_balance_sums_fresh_watch_wallets_at_zero`, `aggregate_watch_balance_filters_network_and_reports_stale` | COVERED |
| WAL-068 | Saldo nell'header nell'unità scelta, con l'equivalente fiat se c'è un prezzo; "—" prima di qualsiasi dato | DashboardScreen.kt:`WalletHeader` | DashboardScreenTest::`auto-sync fires on first composition and balance lands`; PrivacyModeTest (mascheramento) | **COVERED** (test in §F; prima: PARTIAL) |

### G. Storico transazioni e dettaglio

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|---|---|---|---|---|
| WAL-069 | Elencare le transazioni (confermate + mempool) con net firmato, fee (solo se tutti gli input sono nostri), altezza, timestamp | watch.rs:`list_transactions`, `ScriptWatch::list_transactions`; ffi lib.rs:`list_transactions` | watch.rs::`list_transactions_is_empty_on_fresh_wallet`, `address_watch_counts_funds_sent_to_the_address`; regtest.rs::`regtest_watch_wallet_sees_incoming_funds` (ignorato) | **COVERED** (test in §F; prima: PARTIAL) |
| WAL-070 | Tab Transactions: tabella vuota → stato vuoto; righe con importo firmato (+/−) | screens/HistoryScreen.kt | HistoryScreenTest::`empty state when wallet has no transactions`, `incoming amounts carry a plus sign, outgoing a minus` | COVERED |
| WAL-071 | Stato di conferma: Pending → Confirming n/6 → definitiva; tip sconosciuto non dà mai conteggi negativi né stato finale | screens/WalletTxModel.kt:`confirmationsOf`, `txStatusOf` | WalletTxModelTest::`unconfirmed is Pending with zero confirmations`, `mined in the tip block counts one confirmation and is Confirming`, `five confirmations still confirming, six is final with no badge`, `unknown or stale tip never yields a negative count nor a final state`; HistoryScreenTest::`status chips follow …`, `a mined tx with an unknown tip reads Confirming, never final` | COVERED |
| WAL-072 | Ordinamento per data (pending prima), label, importo, stato, conferme | WalletTxModel.kt:`sortTransactions`; HistoryScreen.kt (header cliccabili) | WalletTxModelTest::`default date sort puts pending first, newest next`, `amount sort is by signed net, and confirmations by depth`, `label sort puts labelled rows first, alphabetically` | **COVERED** (test in §F; prima: PARTIAL) |
| WAL-073 | Filtri (All/Incoming/Outgoing/Pending) e ricerca testuale su label, categoria e txid | WalletTxModel.kt:`filterTransactions` | WalletTxModelTest::`filters by direction, pending and free text over label category and txid`; HistoryScreenTest::`filter chips and search narrow the table` | COVERED |
| WAL-074 | Categoria in tabella: prima quella BIP-329 del wallet, se manca quella del registry del tracker | HistoryScreen.kt (loader `categoryPairs`) | — | **COVERED** (test in §F; prima: UNCOVERED) |
| WAL-075 | History stand-alone (`syncOnEnter=true`) sincronizza all'ingresso | HistoryScreen.kt | — | **COVERED** (test in §F; prima: UNCOVERED) |
| WAL-076 | Click su una riga: si apre il pannello di dettaglio (txid, importo, fee, stato, conferme, label) | HistoryScreen.kt; screens/TransactionDetailsScreen.kt | HistoryScreenTest::`clicking a row fires onTxClicked and opens the detail pane`; TransactionDetailsScreenTest::`confirmed tx renders status, amount, fee, txid`, `pending tx renders 'In mempool' status` | COVERED |
| WAL-077 | Dettaglio: numero di conferme calcolato dal tip; fee nascosta se sconosciuta (tx in entrata) | TransactionDetailsScreen.kt (`KeyValueRow` fee/conferme) | TransactionDetailsScreenTest::`confirmed tx renders status, amount, fee, txid` | **COVERED** (test in §F; prima: PARTIAL) |
| WAL-078 | Copia del txid negli appunti | TransactionDetailsScreen.kt (`COPY_TXID`) | TransactionDetailsScreenTest::`Copy fires copyToClipboard with the txid` | COVERED |
| WAL-079 | Txid sconosciuto (deep link) → errore "not found"; Back funziona | TransactionDetailsScreen.kt | TransactionDetailsScreenTest::`unknown txid surfaces 'not found' error`, `Back fires the onBack callback` | COVERED |

### H. Indirizzi di ricezione, schermata Receive, BIP-21

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|---|---|---|---|---|
| WAL-080 | Peek di un indirizzo a un indice senza avanzare (HD: derivazione; lista: posizione, errore se fuori range) | watch.rs:`peek_address`; ffi lib.rs:`peek_address_at` | watch.rs::`create_from_addresses_single_signet_address_round_trip`, `address_watch_supports_multiple_addresses_of_any_type` (fuori range); UniffiSmokeTest::`watch xpub derives the BIP-86 signet receive addresses`; test_support.rs::`bip86_fixture_matches_the_spec_vectors` | COVERED |
| WAL-081 | Prossimo indirizzo non usato: stabile tra chiamate, ne rivela uno nuovo solo se tutti sono usati (nessun buco nel gap limit) | watch.rs:`next_unused_receive_address`; ffi lib.rs | watch.rs::`xpub_wallet_hands_out_bip86_receive_addresses_and_persists_reveals`; UniffiSmokeTest::`next_unused_receive_address returns index 0 and is stable`; ffi lib.rs::`watch_handle_exposes_addresses_labels_and_contacts` | **COVERED** (test in §F; prima: PARTIAL) |
| WAL-082 | "New address": rivela l'indice successivo e persiste l'avanzamento anche dopo un riavvio | watch.rs:`reveal_next_address`; ffi lib.rs:`next_receive_address` | watch.rs::`xpub_wallet_hands_out_bip86_receive_addresses_and_persists_reveals`; ffi lib.rs::`watch_handle_exposes_addresses_labels_and_contacts` | COVERED |
| WAL-083 | Wallet a indirizzi: nessuna derivazione (next/reveal → `InvalidArgument`), tab Receive nascosto | watch.rs:`is_address_list`, `no_derivation_error`; DashboardScreen.kt (`tabs`) | watch.rs::`address_list_wallet_lists_its_addresses_but_cannot_derive`; ffi lib.rs::`address_list_watch_handle_reports_no_derivation`; UniffiSmokeTest::`address-list wallet has no descriptor and is flagged`; DashboardScreenTest::`address-list wallet hides the Receive tab`, `xpub wallet shows the Receive tab` | COVERED |
| WAL-084 | Receive label-first: nessun indirizzo finché l'utente non sceglie label/categoria o "Skip" | screens/ReceiveScreen.kt | ReceiveScreenTest::`opening the tab reveals no address until the user continues` | COVERED |
| WAL-085 | Label e categoria vengono scritte sull'indirizzo prima di mostrarlo; "Edit" rietichetta lo stesso indirizzo | ReceiveScreen.kt; watch.rs:`set_address_label` | ReceiveScreenTest::`label and category are attached to the address before it is shown`, `editing the request relabels the same address without revealing another` | COVERED |
| WAL-086 | Rientrare nel tab dà lo stesso indirizzo non usato; "New address" passa all'indice successivo mantenendo la label | ReceiveScreen.kt | ReceiveScreenTest::`re-opening the tab hands out the same unused address`, `New address advances to the next index and keeps the label` | COVERED |
| WAL-087 | Avviso di riuso se l'indirizzo mostrato ha già ricevuto fondi | ReceiveScreen.kt; WalletTxModel.kt:`isReusedAddress` | WalletTxModelTest::`reuse is flagged only for an address that already received funds`; ReceiveScreenTest::`a previously used address shows the reuse warning`, `a fresh address shows no reuse warning` | COVERED |
| WAL-088 | QR + indirizzo raggruppato + URI; "Copy address" / "Copy URI" con feedback "Copied" | ReceiveScreen.kt (step 2, `COPY_ADDRESS`, `COPY_URI`, `QR`) | — | **COVERED** (test in §F; prima: UNCOVERED) |
| WAL-089 | Importo solo cifre (sats) → URI BIP-21 con amount in BTC; solo il messaggio esplicito per il pagatore entra nell'URI | ReceiveScreen.kt; ui/Bip21.kt:`build`, `formatBtc` | ReceiveScreenTest::`amount produces a BIP-21 URI with the amount in BTC`, `only the explicit payer message reaches the URI, percent-encoded`, `digits-only filter rejects letters and decimals in amount`; Bip21Test (10 test) | COVERED |
| WAL-090 | Errori della Receive: derivazione fallita o wallet non aperto → ErrorCard | ReceiveScreen.kt | ReceiveScreenTest::`derive failure renders the error card`, `wallet not unlocked renders the error card` | COVERED |
| WAL-091 | Back in modalità stand-alone | ReceiveScreen.kt | ReceiveScreenTest::`Back fires onBack when shown stand-alone` | COVERED |
| WAL-092 | Copia delle label di ricezione sulla tx in arrivo alla sync: solo net positivo, vince l'output con indice più basso, mai sopra una label esistente, una volta per (txid, indirizzo), una label cancellata resta cancellata | watch.rs:`apply_receive_labels`, `incoming_txs`, `is_mine`; labels.rs:`label_copy_done`, `mark_label_copy` | watch.rs::`receive_label_is_copied_once_onto_the_paying_transaction`; regtest.rs::`regtest_receive_label_lands_on_the_incoming_transaction` (ignorato) | **COVERED** (test in §F; prima: PARTIAL) |
| WAL-093 | Dopo la copia le categorie finiscono nel registry del tracker sotto l'id del wallet, con bump del dataVersion; un errore non fa fallire la sync | DashboardScreen.kt:`applyReceiveLabels` | DashboardScreenTest::`every sync copies receive labels through the core` | **COVERED** (test in §F; prima: PARTIAL) |

### I. Tab Addresses e UTXO

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|---|---|---|---|---|
| WAL-094 | Elencare gli indirizzi rivelati (receive o change) con indice, usato/non usato, label e categoria BIP-329 | watch.rs:`list_addresses`; ffi lib.rs:`list_addresses` | watch.rs::`xpub_wallet_hands_out_bip86_receive_addresses_and_persists_reveals`, `address_labels_show_up_in_the_address_list`, `address_list_wallet_lists_its_addresses_but_cannot_derive`; ffi lib.rs::`watch_handle_exposes_addresses_labels_and_contacts` | **COVERED** (test in §F; prima: PARTIAL) |
| WAL-095 | Tab Addresses: toggle Receive/Change, tabella, "Show" apre Receive su quell'indirizzo; errore di caricamento | screens/WalletTabs.kt:`WalletAddressesTab`, `AddressTable` | DashboardScreenTest::`Addresses tab lists used and unused, Show opens Receive with a reuse warning`; TableColumnHidingTest::`addresses table shows every column when wide`, `addresses table hides index and label when narrow`, `addresses drop index then label` | **COVERED** (test in §F; prima: PARTIAL) |
| WAL-096 | Elencare gli UTXO (confermati + mempool) con outpoint, valore, indirizzo, receive/change | watch.rs:`list_utxos`; ffi lib.rs:`list_utxos` | watch.rs::`address_list_wallet_lists_its_addresses_but_cannot_derive` (UTXO non confermato); regtest.rs (confermato, ignorato) | **COVERED** (test in §F; prima: PARTIAL) |
| WAL-097 | Tab UTXO in sola lettura con conteggio e totale; tabella responsive; errore di caricamento | WalletTabs.kt:`WalletUtxosTab`, `UtxoTable` | DashboardScreenTest::`UTXOs tab lists coins read-only with a count and total`; TableColumnHidingTest::`utxo table shows every column when wide`, `utxo table keeps status and amount when narrow`, `utxos drop type, address, then outpoint` | **COVERED** (test in §F; prima: PARTIAL) |
| WAL-098 | Altezza del tip della catena (0 prima della sync) usata per le conferme | watch.rs:`chain_tip_height`; ffi lib.rs | watch.rs::`xpub_wallet_hands_out_bip86_receive_addresses_and_persists_reveals` (0) | **COVERED** (test in §F; prima: PARTIAL) |

### J. Label BIP-329

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|---|---|---|---|---|
| WAL-099 | CRUD delle label per (kind, target): upsert, get, list, remove (`true` solo se la riga esisteva) | labels.rs:`upsert_label`, `get_label`, `list_labels`, `remove_label`; watch.rs:`add_label`… | labels.rs::`upsert_then_get_round_trips`, `upsert_replaces_existing_pair`, `remove_returns_true_only_when_row_existed` | COVERED |
| WAL-100 | Label di una tx via FFI: set conserva la categoria esistente, set_category conserva testo e spendable, get, remove | ffi lib.rs:`set_tx_label`, `get_tx_label`, `set_tx_category`, `get_tx_category`, `remove_tx_label` | ffi lib.rs::`watch_handle_exposes_addresses_labels_and_contacts`; UniffiSmokeTest::`export_labels_jsonl round-trips a tx label` | **COVERED** (test in §F; prima: PARTIAL) |
| WAL-101 | Label dell'indirizzo + categoria; testo vuoto senza categoria rimuove la riga; il testo viene trimmato | watch.rs:`set_address_label` | watch.rs::`address_labels_show_up_in_the_address_list` | COVERED |
| WAL-102 | Export JSONL BIP-329 (una riga per label, `spendable` omesso se None, `category` come estensione) | labels.rs:`export_jsonl` | labels.rs::`jsonl_round_trip_preserves_labels`, `jsonl_omits_spendable_when_none`; watch.rs::`labels_round_trip_through_bip329_jsonl` | COVERED |
| WAL-103 | Import JSONL: righe vuote tollerate, upsert, conteggio restituito, round-trip tra wallet | labels.rs:`import_jsonl` | labels.rs::`jsonl_import_tolerates_blank_lines`, `jsonl_round_trip_preserves_labels`; watch.rs::`labels_round_trip_through_bip329_jsonl` | COVERED |
| WAL-104 | Import che conserva la categoria e il flag spendable locali quando il record li omette (file di Sparrow/Liana) | labels.rs:`merge_imported_label` | labels.rs::`import_preserves_existing_category_and_frozen_flag_when_omitted` | COVERED |
| WAL-105 | Import atomico: una riga malformata annulla tutto il file | labels.rs:`import_jsonl` (transazione) | labels.rs::`import_is_atomic_on_a_bad_line`, `jsonl_import_rejects_garbage` | COVERED |
| WAL-106 | Limiti dell'import: max 100 000 righe, riga ≤ 64 KiB, ref ≤ 1 KiB, testo ≤ 16 KiB, categoria ≤ 256 B | labels.rs:`import_jsonl`, `check_imported_field` | labels.rs::`import_jsonl_rejects_input_over_line_cap`, `import_jsonl_accepts_input_at_line_cap`, `import_rejects_oversized_line_and_fields` | COVERED |
| WAL-107 | Tipo di label sconosciuto nel DB → errore `Storage` controllato | labels.rs:`parse_db_str`, `row_to_label` | — | **COVERED** (test in §F; prima: UNCOVERED) |
| WAL-108 | Label della tx nel dettaglio: Save, persistenza alla riapertura, Clear | TransactionDetailsScreen.kt (`LABEL_FIELD/SAVE/CLEAR`) | LabelsAndContactsUiTest::`tx label saves, survives a re-open, and Clear removes it` (con `FakeWalletPort`) | **COVERED** (test in §F; prima: PARTIAL) |
| WAL-109 | UI Export label: wallet non aperto → errore; happy path → FFI + dialog di salvataggio + "Exported N"; annulla → nessun messaggio; errore FFI | screens/SettingsLabelsSection.kt:`LabelsSection` | LabelsSectionTest::`Export with no wallet shows wallet-not-unlocked error`, `Export happy path calls FFI then save dialog and surfaces success`, `Export cancel keeps the section idle (no status no error)`, `FFI export failure surfaces as error` | COVERED |
| WAL-110 | UI Import label: happy path con conteggio; errore di lettura del file; errore FFI (rollback) mostrato con la nota | SettingsLabelsSection.kt | LabelsSectionTest::`Import happy path forwards the JSONL to the FFI and reports the count`, `Import surfaces the read error from the file dialog` | **COVERED** (test in §F; prima: PARTIAL) |
| WAL-111 | Il file di import viene letto con un limite di 50 MiB (un file enorme non esaurisce l'heap) | data/BoundedIo.kt:`readTextCapped`; SettingsLabelsSection.kt:`loadLabelsJsonlFromFile` | — | **COVERED** (test in §F; prima: UNCOVERED) |
| WAL-112 | Export con privacy mode attivo chiede conferma | PrivacyMode / SettingsLabelsSection | PrivacyModeTest::`export asks for confirmation only while privacy mode is on` | **COVERED (comportamento documentato)** (test in §F; prima: COVERED) |

### K. Rubrica contatti (address book)

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|---|---|---|---|---|
| WAL-113 | Aggiungere un contatto: indirizzo validato sulla rete del wallet e salvato in forma canonica, label trimmata 1..=256 byte | watch.rs:`add_contact`; common.rs:`validated_contact_label`; contacts.rs:`insert_contact` | watch.rs::`address_book_validates_network_and_label`; contacts.rs::`insert_returns_monotonic_ids`, `record_round_trips_label_address_timestamp` | **COVERED** (test in §F; prima: PARTIAL) |
| WAL-114 | Rifiutare un indirizzo duplicato (UNIQUE) | contacts.rs:`insert_contact` | contacts.rs::`duplicate_address_is_rejected_by_unique_constraint`; LabelsAndContactsUiTest::`adding the same address twice is refused and the list keeps one entry` (fake) | COVERED |
| WAL-115 | Elencare i contatti in ordine di inserimento (vuoto se non ce ne sono) | contacts.rs:`list_contacts` | contacts.rs::`list_returns_insertion_order`, `list_on_empty_table_returns_empty_vec` | COVERED |
| WAL-116 | Rinominare un contatto (`false` se l'id non esiste, label validata) | watch.rs:`update_contact_label`; contacts.rs:`update_contact_label`; ffi lib.rs | contacts.rs::`update_label_changes_label_and_returns_true`, `update_unknown_id_returns_false`; watch.rs::`address_book_validates_network_and_label` | COVERED (core/FFI; non esposto in UI) |
| WAL-117 | Eliminare un contatto (`false` se l'id non esiste) | contacts.rs:`delete_contact` | contacts.rs::`delete_existing_returns_true_and_drops_row`, `delete_unknown_id_returns_false`; watch.rs::`address_book_validates_network_and_label` | COVERED |
| WAL-118 | Schermata Contacts: stato vuoto, form con campi obbligatori, aggiunta, lista, eliminazione | screens/AddressBookScreen.kt | AddressBookScreenTest::`empty state then add then list then delete`, `empty form rejects with required fields` | **COVERED** (test in §F; prima: PARTIAL) |
| WAL-119 | I contatti persistono nel watch-state cifrato del wallet dopo un lock e la riapertura | watch.rs (tabella `contacts` nello stesso DB) | — (test-book 14.6 manuale) | **COVERED** (test in §F; prima: UNCOVERED) |

### L. Export del descriptor pubblico

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|---|---|---|---|---|
| WAL-120 | Esportare i descriptor pubblici receive/change + xpub di account + rete (per Sparrow/Liana/Specter); `None` per i wallet a indirizzi | watch.rs:`export_descriptors`; common.rs:`account_xpub`; ffi lib.rs:`export_public_descriptor` | watch.rs::`xpub_wallet_exports_its_public_descriptors`, `address_list_wallet_lists_its_addresses_but_cannot_derive`; ffi lib.rs::`watch_handle_exposes_addresses_labels_and_contacts`, `address_list_watch_handle_reports_no_derivation`; UniffiSmokeTest::`public descriptor carries the registered account xpub` | **COVERED** (test in §F; prima: PARTIAL) |
| WAL-121 | UI: il descriptor si mostra solo su richiesta; i wallet a indirizzi dicono "no descriptor"; errori con path oscurati; "Hide" | SettingsWalletSections.kt:`ShowWatchOnlyDescriptorSection` | SecretActionsUiTest::`watch-only descriptor is exported only on request`, `address-list wallet reports that it has no descriptor`; ErrorMessagesTest::`an FFI error with a path reaches the wallet settings redacted` | **COVERED** (test in §F; prima: PARTIAL) |
| WAL-122 | Copiare xpub/descriptor negli appunti con pulizia automatica solo se gli appunti contengono ancora quel valore | SettingsWalletSections.kt:`copyToClipboardWithAutoClear` | — | **COVERED** (test in §F; prima: UNCOVERED) |

### M. Workspace del wallet, explorer, formattazione, errori

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|---|---|---|---|---|
| WAL-123 | Workspace: tab Transactions · Receive · Addresses · UTXOs · Wallet settings, `initialTab`, azioni Contacts e Lock nell'header | DashboardScreen.kt; WalletWorkspaceUi.kt:`WalletTab`, `WalletTabStrip`; WalletTabs.kt:`WalletSettingsTab` | DashboardScreenTest::`sub-tabs switch between …`, `initialTab opens the workspace on the requested tab`, `Contacts header action fires onAddressBookClicked`, `Lock header action fires onLockClicked` | COVERED |
| WAL-124 | Header: nome del wallet dal registry (fallback alla label del WalletStore), chip della rete, numero di wallet nel footer | DashboardScreen.kt (`LaunchedEffect(walletId)`), `WalletHeader`, `BhodlFooterStatusBar` | — | **COVERED** (test in §F; prima: UNCOVERED) |
| WAL-125 | Link all'explorer: URL mempool.space per mainnet/testnet4/signet, nessun link su regtest; con Tor attivo chiede conferma e propone di copiare il txid | screens/ExplorerLink.kt:`ExplorerLinks.txBaseUrl`, `ExplorerLinkButton` | ExplorerLinkTest::`explorer base url follows the network`, `without Tor the link opens directly`, `with Tor the link asks first and offers copying the txid`, `plaintext http warning applies with Tor on too`; TransactionDetailsScreenTest::`Open in mempool space fires openExplorer with the full URL` | COVERED |
| WAL-126 | Indirizzi raggruppati a blocchi di 4, mai troncati, trim; input non da indirizzo lasciato invariato | ui/AddressFormat.kt:`groupAddressInFours`; WalletWorkspaceUi.kt:`GroupedAddressText` | AddressFormatTest::`groups a full address in blocks of four keeping both ends`, `trims surrounding whitespace`, `non-address input is not grouped` | COVERED |
| WAL-127 | Formattazione degli importi in sat dell'FFI (`format_sat`) | ffi lib.rs:`format_sat` | UniffiSmokeTest::`format_sat round-trips a simple value`; bhodl-core amount.rs::`display_uses_sat_suffix` | COVERED |
| WAL-128 | Mappatura `OnchainError` → `WalletError` tipizzato (Descriptor→WalletCreate, InvalidAddress, EsploraSync, Storage…) e path oscurati nei dettagli | ffi lib.rs:`From<OnchainError> for WalletError`, `strip_paths` | ffi lib.rs::`strip_paths_*` (8 test), `io_error_from_strips_path_in_detail`; UniffiSmokeTest::`invalid xpub is rejected with a typed WalletException` | **COVERED** (test in §F; prima: PARTIAL) |
| WAL-129 | Mutex del watch handle avvelenato → errore `Storage` invece di un panic | ffi lib.rs (tutti i metodi di `WatchHandle`) | — | **COVERED** (test in §F; prima: UNCOVERED) |
| WAL-130 | Codice morto: `NetworkSelector` (senza mainnet) e `ReceiveAddressCard` non sono usati | ui/NetworkSelector.kt, ui/ReceiveAddressCard.kt | — | **COVERED (codice morto)** (test in §F; prima: UNCOVERED) |

---

### Conteggio

| Stato | N. |
|---|---:|
| COVERED | 59 |
| PARTIAL | 42 |
| UNCOVERED | 29 |
| **Totale** | **130** |

Priorità suggerite (rapporto rischio/sforzo):
1. **WAL-018–WAL-022** (AddWatchScreen senza alcun test, incluso il bug latente sul confronto `vpub`/`tpub`) → Compose test con un registry reale in tmp, sul modello di `SecretActionsUiTest`.
2. **WAL-032 / WAL-029** (secondo tap della rimozione, rinomina) → stesso pattern.
3. **WAL-066 / WAL-069 / WAL-096** (spesa in uscita, fee, UTXO confermati/change) → estendere `RegtestSyncIntegrationTest`: è l'unica copertura on-chain in CI.
4. **WAL-041, WAL-052, WAL-107, WAL-027** → unit test Rust economici sui rami di errore.

<a id="c"></a>

## Inventario casi d'uso — Area C: Tracker activity, cash flow e budgeting

Branch `refactor/watch-only-wallets` (working tree al 2026-10-24). Percorsi abbreviati:
- `TC` = `apps/desktop/src/jvmMain/kotlin/io/bhodl/desktop/data/TrackerCache.kt`
- `TS` = `apps/desktop/src/jvmMain/kotlin/io/bhodl/desktop/screens/TrackingScreen.kt`
- `reg` = `crates/bhodl-wallet-onchain/src/registry.rs` (test in `mod tests`, eseguiti con `cargo test -p bhodl-wallet-onchain --lib`)
- `ffi` = `crates/bhodl-uniffi/src/commonMain/rust/lib.rs`
- i test Kotlin stanno in `apps/desktop/src/jvmTest/kotlin/io/bhodl/desktop/{screens,data,settings,ui,wizard}/`

Legenda stato: **COVERED** = happy path + varianti principali verificate da test automatici; **PARTIAL** = solo una parte (es. funzione pura testata, wiring/UI no); **UNCOVERED** = nessun test automatico.

### Problemi trovati durante l'inventario (da leggere prima)

1. **KpiStripV7, CashFlowForecastCard e MonthlyReviewBanner non sono montati da nessuna parte** (né su HEAD né nel working tree): `computeFinanceKpis`, `forecastCashFlow` e il banner hanno test unitari, ma nessuno schermo di produzione li usa. Il test-book §25.1/25.5/25.8 li descrive come visibili sulla Tracker home: oggi non è vero. Bisogna decidere se rimontarli o eliminarli (codice morto).
2. **L'export CSV ignora i filtri**: `TS` passa `events` (tutti gli eventi, wallet esclusi compresi) a `exportEventsAsCsv`, anche se il commento dice che la lista filtrata pilota "History list and the CSV export".
3. **La CategoryBreakdownCard della tab Transactions** (`TS` blocco `breakdown`) somma `direction` grezza: un `send_to_self` OUTGOING finisce nelle uscite, a differenza di tutti gli altri aggregati (che usano `ExternalFlow`).
4. **L'esclusione dei wallet filtra per `walletId` sull'evento già deduplicato**: se la gamba scelta come rappresentante del txid appartiene al wallet escluso, la tx sparisce anche per l'altro wallet incluso (o viceversa resta). Non c'è nessun test.
5. Il test-book cita test che non esistono: `list_watch_events_includes_crypto_kind` (§29.7) e `crypto_event_kind_set_regular_clears_row` (§29.4).
6. Il registry non valida la categoria vuota di una regola (`action_value`), né categoria/periodo di un budget, né il nome vuoto di un bill: lo fa solo la UI.

---

### A. TrackerCache — refresh, cold load, reset, pubblicazione snapshot

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|---|---|---|---|---|
| TRK-001 | Refresh completo con sync Esplora per ogni wallet watch (endpoint della rete del wallet, proxy Tor), saldo e lista tx per wallet | TC:refresh(syncEsplora=true) | — | **PARTIAL** (test in §F; prima: UNCOVERED) |
| TRK-002 | Refresh solo registry (dopo una modifica): niente rete, `lastSyncUnix` invariato | TC:refresh(syncEsplora=false) | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-003 | Errore: registry non aperto → `lastError = "Registry not open."`, nessuno snapshot | TC:refresh | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-004 | Sync fallita su N wallet → messaggio "Sync failed for N wallet(s) — showing cached data…" e wallet marcati stale; dati in cache restano | TC:refresh (syncFailure, SYNC_ERROR_PREFIX) | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-005 | Rete sconosciuta nel record wallet → sync fallisce per quel wallet ("unknown network") | TC:refresh (EsploraEndpoint.networkOf == null) | — | **UNCOVERED (saltato)** (test in §F; prima: UNCOVERED) |
| TRK-006 | Wallet che non si apre (stato corrotto/mancante) → stale, il resto continua | TC:refresh (opened.isFailure) | ffi::aggregate_watch_balance_filters_network_and_reports_stale (solo lato aggregato FFI) | **COVERED** (test in §F; prima: PARTIAL) |
| TRK-007 | Refresh solo registry mantiene il verdetto stale precedente (solo per wallet ancora esistenti) e il messaggio di sync precedente | TC:refresh (staleOut, lastError takeIf SYNC_ERROR_PREFIX) | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-008 | `lastSyncUnix` aggiornato solo da sync di rete riuscita (o senza wallet = sincronizzato) | TC:refresh (networkSynced) | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-009 | Pubblicazione atomica di tutti gli stati in un solo Snapshot Compose; `hasLoadedOnce = true` | TC:refresh (withMutableSnapshot) | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-010 | Eccezione imprevista nel refresh → `lastError` = messaggio, `refreshing` torna false | TC:refresh (onFailure) | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-011 | Cold load: pubblica subito la cache SQLCipher (registry-only) e poi sincronizza; idempotente (una sola volta anche se lo chiamano home e tracking) | TC:coldLoad | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-012 | Reset al logout/wipe: svuota tutti gli stati, la cache dei tassi e il flag cold load | TC:reset | data/TrackerCacheRateHealingTest (usa `reset()` nel setup, non verifica lo svuotamento) | **COVERED** (test in §F; prima: PARTIAL) |
| TRK-013 | Avvio loop di background: ticker ogni 2 min (solo con registry aperto) + osservatore `dataVersion` che fa refresh solo registry dopo ogni modifica; `start` idempotente, `stop` | TC:start / stop | wizard/RegistryStoreDataVersionTest::bump increments the counter (solo il contatore) | **PARTIAL** (test in §F; prima: PARTIAL) |
| TRK-014 | Calcolo totali asset nello snapshot (BTC aggregato sulla rete del wallet aperto, investimenti, manual asset) | TC:refresh | (area net worth, altro agente) | **PARTIAL (saltato)** (test in §F; prima: PARTIAL) |
| TRK-015 | Recupero tassi storici mancanti dopo la pubblicazione (solo nei refresh di rete, max 12 lookup, timeout) | TC:healMissingRates / launchRateHealing | data/TrackerCacheRateHealingTest::healing republishes found rates and persists the healed rows; ::a hung rate provider cannot stall the pass beyond the timeout; ::lookups per pass are capped | COVERED |
| TRK-016 | Errore di modifica mostrato a schermo: l'errore della cache ha la precedenza su quello locale | TS:TrackingScreen (displayError) | — | **COVERED** (test in §F; prima: UNCOVERED) |

### B. Aggregazione eventi di pagamento tra wallet

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|---|---|---|---|---|
| TRK-017 | FFI elenca gli eventi di tutti i wallet watch con direzione dal net (in/out/self), importo assoluto, fee, timestamp, altezza | ffi:RegistryHandle::list_watch_events | — | UNCOVERED — test Rust in `ffi` mod tests: wallet a indirizzi + stato watch con tx finte, oppure test L3 regtest (`RegtestSyncIntegrationTest`) che chiama `listWatchEvents()` dopo un pagamento |
| TRK-018 | FFI unisce categoria e crypto kind per (wallet, txid) con un dump unico | ffi:list_watch_events (category_index, crypto_kind_index) | — | UNCOVERED — test Rust: `set_event_category` + `set_event_crypto_kind` poi `list_watch_events` → campi valorizzati (il test-book §29.7 cita un test che non esiste) |
| TRK-019 | FFI salta i wallet con rete non valida o stato illeggibile (risultato parziale invece di errore) | ffi:list_watch_events (continue) | — | UNCOVERED — test Rust: cancella il file watch-state di un wallet su due |
| TRK-020 | Righe del registry sullo stesso account xpub (mirror) ridotte a un id canonico | domain/SendToSelfDetector.kt:canonicalWalletIds | screens/SendToSelfDetectorTest::two rows of the same account collapse to one canonical id | COVERED |
| TRK-021 | Eventi dei wallet alias scartati (un account contato una volta sola) | domain/SendToSelfDetector.kt:dropAliasedEvents | screens/SendToSelfDetectorTest::two rows of the same account collapse to one canonical id | COVERED |
| TRK-022 | Deduplica per txid: si preferisce la gamba con categoria, poi quella con fee nota; ordine per timestamp decrescente con mempool in cima | TC:refresh (merged) | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-023 | Gambe per wallet (non deduplicate) esposte per il costo medio BTC | TC:refresh (eventLegs) | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-024 | Escludi/includi un wallet dai totali (persistito nelle impostazioni) | settings/SettingsStore.kt:setWalletIncluded | settings/SettingsPersistenceTest::excludedWalletIds survive a save - reset - rehydrate round-trip (+ test toggle subito dopo) | COVERED |
| TRK-025 | Wallet esclusi spariscono da conteggio wallet e totale BTC | screens/TrackerNetWorth.kt:trackedWalletGroups | screens/TrackerNetWorthTest::B8 excluded wallets drop out of the tracked count and total | COVERED |
| TRK-026 | Eventi dei wallet esclusi tolti da KPI, grafici, Reports e budget (la tab Transactions li mostra comunque) | TS:TrackingScreen (includedEvents) | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-027 | Toggle includi/escludi dalla riga wallet raggruppata nella tab Transactions (tutti i membri del gruppo insieme) | screens/TrackingHistoryTab.kt:HistoryTabBody / TrackingWalletsSection.kt:GroupedWalletRow | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-028 | Raggruppamento wallet per xpub per la lista e il totale | screens/TrackingWalletsSection.kt:groupWalletsByXpub | screens/TrackerNetWorthTest::B8 two registry rows on the same account xpub count as ONE tracked wallet; ::B8 address-list wallets never merge, even with equal descriptors (via trackedWalletGroups) | COVERED |

### C. Categorie

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|---|---|---|---|---|
| TRK-029 | Assegna una categoria a una tx (upsert per wallet+txid) | reg:set_event_category | reg::category_set_get_round_trip; reg::category_replace_overwrites_existing | COVERED |
| TRK-030 | Cancella la categoria (None o stringa vuota/spazi → riga eliminata) | reg:set_event_category | reg::category_set_none_clears_row; reg::category_empty_string_treated_as_clear | COVERED |
| TRK-031 | Elenco categorie note, distinte e in ordine alfabetico case-insensitive (autocomplete) | reg:list_categories | reg::list_categories_returns_distinct_alpha_sorted | COVERED |
| TRK-032 | Pulizia categorie e crypto kind alla rimozione di un wallet | reg:purge_categories_for_wallet / remove_wallet_with_metadata | reg::purge_categories_for_wallet_only_touches_that_wallet; reg::remove_wallet_with_metadata_purges_dependents | COVERED |
| TRK-033 | Dialog modifica categoria: suggerimenti dalle categorie note (prime 8, filtro contains), Save, pulsante Clear | screens/TrackingHistoryTab.kt:CategoryEditDialog | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-034 | Salvataggio categoria dal dialog: trim, vuoto → null, errore → messaggio, poi bump `dataVersion` | TS:TrackingScreen (onSave del CategoryEditDialog) | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-035 | La categoria è scritta sul `walletId` della gamba rappresentante: con la tx su due wallet l'altra gamba resta senza categoria | TS:TrackingScreen / TC:refresh (merge preferisce la gamba categorizzata) | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-036 | Categoria visibile nella tabella tx del wallet (lettura per riga dal registry) | screens/HistoryScreen.kt:readHistory (getEventCategory) | screens/WalletTxModelTest::filters by direction, pending and free text over label category and txid (solo filtro) | **PARTIAL** (test in §F; prima: PARTIAL) |
| TRK-037 | Chip categoria sulla riga evento apre il dialog | screens/TrackingHistoryTab.kt:PaymentEventRow / CategoryChip | — | **COVERED** (test in §F; prima: UNCOVERED) |

### D. Tassonomia crypto event (9 tipi) e send_to_self

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|---|---|---|---|---|
| TRK-038 | Imposta un tipo dall'utente (fissato, `user_set=1`), anche "regular" | reg:set_event_crypto_kind | reg::crypto_event_kind_set_then_get; reg::crypto_event_kind_user_regular_is_persisted_and_pinned; reg::crypto_event_kind_set_overwrites_existing | COVERED |
| TRK-039 | Rifiuto di un tipo fuori whitelist; accettati tutti i 9 valori | reg:validate_crypto_event_kind | reg::crypto_event_kind_reject_unknown_kind; reg::crypto_event_kind_accepts_each_whitelisted_value | COVERED |
| TRK-040 | Scrittura automatica: non tocca mai righe fissate dall'utente; "regular" cancella solo righe auto | reg:set_event_crypto_kind_auto | reg::crypto_event_kind_auto_writes_and_clears_only_unpinned_rows | COVERED |
| TRK-041 | Nessuna riga = regular implicito; isolamento per wallet; dump; purge; migrazione idempotente | reg:get_event_crypto_kind / dump / purge | reg::crypto_event_kind_default_returns_none; reg::crypto_event_kind_per_wallet_isolation; reg::crypto_event_kind_dump_returns_all_rows; reg::crypto_event_kind_purge_for_wallet; reg::crypto_event_kind_migration_idempotent_across_opens | COVERED |
| TRK-042 | Rilevamento send_to_self: unica uscita esterna = fee → interno; fan-out verso più wallet propri | domain/SendToSelfDetector.kt:detectSendToSelf / isInternalTransfer | screens/SendToSelfDetectorTest::transfer whose only external flow is the fee is internal; ::fan-out to two other own wallets is internal | COVERED |
| TRK-043 | send_to_self: varianti negative (pagamento con resto su altro wallet, fee ignota, due entrate senza addebito, txid su un solo wallet, Lightning, input vuoto) | domain/SendToSelfDetector.kt:detectSendToSelf | screens/SendToSelfDetectorTest::payment with change to another own wallet is NOT internal; ::unknown fee cannot prove an internal transfer; ::two incoming legs without a debit are not internal; ::txid confined to a single wallet is left alone; ::lightning payments are ignored; ::empty input returns empty | COVERED |
| TRK-044 | send_to_self non sovrascrive una classificazione salvata né un "regular" | domain/SendToSelfDetector.kt:detectSendToSelf | screens/SendToSelfDetectorTest::a stored classification is never overwritten; ::regular is never overwritten; ::returned events are the same instances, not copies | COVERED |
| TRK-045 | Il refresh scrive send_to_self tramite l'entry point AUTO e propaga il tipo al rappresentante deduplicato | TC:refresh (setEventCryptoKindAuto, taggedTxids) | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-046 | Pulizia una tantum dei tag send_to_self artefatti (marker file, ritenta se fallisce) | TC:runSendToSelfCleanupOnce / domain:staleSendToSelfTags | screens/SendToSelfDetectorTest::cleanup resets only certain artefacts (solo la funzione pura) | **PARTIAL** (test in §F; prima: PARTIAL) |
| TRK-047 | Picker dei 9 chip nel dialog: il tap salva subito (indipendente dal Save della categoria) | screens/CryptoEventKind.kt:CryptoEventKindPicker; TS (onCryptoEventKindChange) | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-048 | Badge colorato sulla riga (nessun badge per regular), etichette e colori per tipo; nota fiscale sui gift | screens/CryptoEventKind.kt:CryptoEventKindBadge / badgeLabel / accentColor | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-049 | Conversione wire ↔ enum, valore sconosciuto o null → REGULAR | screens/CryptoEventKind.kt:CryptoEventKind.fromWire | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-050 | Flussi esterni: trasferimenti interni (SELF_TRANSFER o send_to_self) non sono entrate/uscite; per send_to_self conta solo la fee | domain/ExternalFlow.kt:isInternalTransfer / externalInflowSat / externalOutflowSat / externalSignedNetSat | screens/FinanceKpisTest::send_to_self counts only its fee as outflow; screens/BudgetBreakdownCardTest::B3 internal transfers are neither income nor spending; screens/CashFlowForecastTest::recurring samples and send_to_self are not double counted | **COVERED** (test in §F; prima: PARTIAL) |

### E. Motore regole (auto-categorizzazione)

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|---|---|---|---|---|
| TRK-051 | Crea una regola (campo, operatore, valore, categoria, priorità, abilitata) | reg:add_rule | reg::rules_add_then_list_round_trips_all_fields | COVERED |
| TRK-052 | Validazione regola: campo/azione sconosciuti, operatore di testo su importo e viceversa, importo non numerico; campi direzionali solo con operatori numerici | reg:validate_rule | reg::rules_accept_each_text_op_on_description; reg::rules_accept_each_number_op_on_amount; reg::rules_reject_invalid_field; reg::rules_reject_text_op_on_amount; reg::rules_accept_directional_amount_fields_with_number_ops_only; reg::rules_reject_number_op_on_description; reg::rules_reject_unparseable_amount_value | COVERED |
| TRK-053 | Modifica regola (incluso toggle abilitata); id inesistente → false | reg:update_rule | reg::rules_update_persists_enabled_toggle | **COVERED** (test in §F; prima: PARTIAL) |
| TRK-054 | Elimina regola (true poi false) | reg:delete_rule | reg::rules_delete_returns_true_then_false | COVERED |
| TRK-055 | Elenco regole per priorità decrescente poi data di creazione | reg:list_rules | reg::rules_list_orders_by_priority_desc_then_created_asc | COVERED |
| TRK-056 | Riordino atomico di tutte le regole; ordine obsoleto (id mancanti/duplicati/sconosciuti) rifiutato senza scrivere nulla | reg:reorder_rules | reg::reorder_rules_renumbers_every_priority_in_the_given_order; reg::reorder_rules_refuses_a_stale_order_and_writes_nothing | COVERED |
| TRK-057 | Valore categoria vuoto accettato dal registry (solo la UI lo blocca) | reg:add_rule (nessun controllo su action_value) | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-058 | Matching: prima regola abilitata che corrisponde vince; ignora disabilitate e azioni non "category"; nessuna regola → null | domain/RulesEngine.kt:applyRules | screens/RulesEngineTest::applyRules returns null when no rules; ::applyRules returns category of first matching rule; ::applyRules skips disabled rules; ::applyRules skips rules with non-category action | COVERED |
| TRK-059 | Operatori testo case-insensitive (contains/equals/starts_with/ends_with), operatore sconosciuto → false | domain/RulesEngine.kt:matchText | screens/RulesEngineTest::text op contains is case insensitive; ::text op equals is case insensitive; ::text op starts_with matches prefix; ::text op ends_with matches suffix; ::text op with unknown op returns false | COVERED |
| TRK-060 | Operatori importo (gt/lt stretti, eq), valore non parsabile → false, campo sconosciuto → false | domain/RulesEngine.kt:matchAmount / matchesRule | screens/RulesEngineTest::amount gt strict; ::amount lt strict; ::amount eq exact; ::amount op with unparseable value returns false; ::unknown condition field returns false | COVERED |
| TRK-061 | Regole payee confrontano l'etichetta del wallet (fallback txid); description ignora l'etichetta; amount_in/amount_out solo nella loro direzione | domain/RulesEngine.kt:matchesRule | screens/RuleSuggestionsTest::payee rules match the wallet label; ::payee rules still match the txid for backward compatibility; ::description rules ignore the wallet label; ::directional amount rules only match their direction | COVERED |
| TRK-062 | Auto-categorizzazione all'import: nel refresh le regole abilitate riempiono solo gli eventi senza categoria; la categoria manuale vince | TC:refresh (applyRules + setEventCategory) | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-063 | Limite noto: disattivare/modificare una regola non toglie le categorie già assegnate | TC:refresh (commento "Known limitation") | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-064 | "Reapply" sulla storia: applica le regole abilitate a tutti gli eventi senza categoria, errore → messaggio | TS:TrackingScreen (onReapplyRules) | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-065 | Dialog regola: Save abilitato solo con nome, valore e categoria non vuoti e importo intero per campi importo; cambio campo riallinea l'operatore valido | screens/RulesCard.kt:RuleEditDialog (canSave, ensureValidOp) | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-066 | Salva/elimina regola dal dialog (nuova regola in cima con `topRulePriority`), errore → messaggio | TS:TrackingScreen (RuleEditDialog onSave/onDelete) | screens/RuleSuggestionsTest::a new rule goes on top (solo priorità) | **COVERED** (test in §F; prima: PARTIAL) |
| TRK-067 | Card regole: righe numerate in ordine di valutazione, ▲/▼ disabilitati ai bordi e durante il salvataggio | screens/RulesCard.kt:RulesCard / MoveButton | screens/TableColumnHidingTest::rules show the position when wide; ::rules hide only the position when narrow; ::rules drop only the position number | **COVERED** (test in §F; prima: PARTIAL) |
| TRK-068 | Spostamento di una regola: un solo riordino alla volta, ordine pendente sbloccato quando la cache mostra il nuovo ordine o se la scrittura fallisce | TS:TrackingScreen (onMoveRule, pendingRuleOrder); domain/RuleSuggestions.kt:moveRule | screens/RuleSuggestionsTest::move returns the full new order for one atomic reorder; ::repeating a move on the stale list writes the same order, never an undo; ::moving past the edges or an unknown id is a no-op; ::evaluation order is priority desc then created asc; ::first match in evaluation order wins | **PARTIAL** (test in §F; prima: PARTIAL) |

### F. Suggerimento regola dopo una modifica ("Always do this?") e privacy degli importi nelle regole

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|---|---|---|---|---|
| TRK-069 | Bozza regola dalla modifica: Wallet = payee equals etichetta; Importo = importo esatto nella direzione della tx; niente bozza senza etichetta/importo/categoria | domain/RuleSuggestions.kt:ruleDraftFromEdit | screens/RuleSuggestionsTest::wallet draft is an equals rule on the payee; ::amount draft is an exact amount rule in the edited event's direction; ::no draft without a wallet label, amount or category | COVERED |
| TRK-070 | Offri il pannello solo per un cambio reale non già coperto da una regola attiva | domain/RuleSuggestions.kt:shouldOfferRuleFromEdit | screens/RuleSuggestionsTest::offer only for a real change no enabled rule already covers | COVERED |
| TRK-071 | Anteprima: conta le tx senza categoria che corrispondono (esclusa quella modificata e la sua gamba mirror, txid su due wallet contato una volta, solo nella direzione, solo importo esatto) | domain/RuleSuggestions.kt:ruleDraftMatches / ruleDraftPreviewCount | screens/RuleSuggestionsTest::preview counts uncategorised matching transactions except the edited one; ::preview counts a txid seen by two wallets once but applies to both rows; ::preview excludes the mirror row of the edited transaction; ::amount preview ignores events in the other direction; ::amount preview only counts the exact amount | COVERED |
| TRK-072 | Crea la regola dal pannello e categorizza subito le tx corrispondenti (le categorie manuali non cambiano); senza spunta vale solo per le prossime | TS:TrackingScreen (RuleFromEditPanel onCreate); screens/RulesCard.kt:RuleFromEditPanel | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-073 | Il nome della regola "importo esatto" non salva l'importo; importi di condizioni e nomi legacy passano dal formatter mascherato | domain/RuleSuggestions.kt:displayRuleName / displayRuleConditionValue | screens/RuleAmountPrivacyTest::a suggested exact-amount rule stores no amount in its name; ::amount conditions and legacy names render through the formatter | COVERED |

### G. Rilevamento ricorrenze

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|---|---|---|---|---|
| TRK-074 | Rileva una spesa mensile (es. Netflix) e una settimanale | domain/RecurringDetector.kt:detectRecurring | screens/RecurringDetectorTest::monthly netflix happy path is detected; ::weekly grocery happy path is detected | COVERED |
| TRK-075 | Input da scartare: lista vuota, eventi in entrata, senza timestamp, gruppi sotto 3 occorrenze, chiavi già respinte, send_to_self | domain/RecurringDetector.kt:detectRecurring | screens/RecurringDetectorTest::empty events return empty; ::incoming events are ignored; ::events without timestamp are ignored; ::groups under min occurrences are dropped; ::dismissed keys are filtered out; ::send_to_self transfers are never recurring spending | COVERED |
| TRK-076 | Classificazione frequenza: intervalli irregolari rifiutati, tolleranza ±3 giorni sul mensile | domain/RecurringDetector.kt:classifyFrequency | screens/RecurringDetectorTest::irregular intervals reject the group; ::monthly tolerance accepts plus minus 3 days drift | **COVERED** (test in §F; prima: PARTIAL) |
| TRK-077 | Confidenza: varianza importo >20% rifiuta, <5% → HIGH, 3 occorrenze → MEDIUM; LOW scartata dal forecast | domain/RecurringDetector.kt:scoreConfidence | screens/RecurringDetectorTest::amount variance over 20 percent rejects the group; ::amount variance under 5 percent yields HIGH confidence; ::confidence drops to MEDIUM at 3 occurrences | **COVERED** (test in §F; prima: PARTIAL) |
| TRK-078 | Raggruppamento per categoria + fascia d'importo; senza categoria raggruppa per importo; prossima data = ultimo + periodo; più gruppi separati | domain/RecurringDetector.kt:recurringGroupKey / amountBucket / normalizePayeeKey | screens/RecurringDetectorTest::different amounts in one category form separate groups; ::uncategorized events still group by amount; ::next estimated is last timestamp plus period; ::multiple groups appear separately; ::normalizePayeeKey lowercases trims and truncates | COVERED |
| TRK-079 | Ricorrenze calcolate a ogni refresh sugli eventi deduplicati, filtrate con le chiavi respinte del registry | TC:refresh (listDismissedRecurring + detectRecurring) | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-080 | Respingi un suggerimento (idempotente), elenco e annullamento del rifiuto nel registry | reg:dismiss_recurring / list_dismissed_recurring / undismiss_recurring | reg::recurring_dismiss_persists_and_is_idempotent; reg::recurring_undismiss_removes_row | COVERED |
| TRK-081 | Card ricorrenze: massimo 5 righe + "altri N", chip di confidenza, importo in Money Compact, pulsanti "+ Bill" e "Dismiss" | screens/RecurringCard.kt:RecurringCard | screens/RecurringCardBitcoinLensTest::candidate amount renders Money Compact BTC primary; ::accept and dismiss actions remain wired; ::empty candidates renders nothing | **COVERED** (test in §F; prima: PARTIAL) |
| TRK-082 | Dismiss dalla card: scrive nel registry, errore → messaggio, bump dataVersion | TS:TrackingScreen (onDismissRecurring) | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-083 | Accetta come bill: crea il bill (nome, importo medio, giorno = giorno della prossima scadenza stimata) e respinge il suggerimento | TS:TrackingScreen (billPrefillFromRecurring) | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-084 | "Mostra di nuovo i respinti" (undismiss) non ha un'azione in UI | reg:undismiss_recurring (nessun chiamante Kotlin) | — | **UNCOVERED (gap funzionale)** (test in §F; prima: UNCOVERED) |

### H. Bill e abbonamenti

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|---|---|---|---|---|
| TRK-085 | Aggiungi un bill (id autoincrementale, nota opzionale) | reg:add_bill | reg::bills_add_assigns_autoincrement_id; reg::bills_display_label_round_trips_when_present_and_null | COVERED |
| TRK-086 | Giorno di scadenza validato 1..31 in add e update | reg:validate_due_day | reg::bill_due_day_zero_rejected; reg::bill_due_day_above_31_rejected; reg::bill_due_day_1_and_31_accepted; reg::bill_update_rejects_invalid_due_day | COVERED |
| TRK-087 | Importo bill oltre 21M BTC rifiutato, al limite accettato | reg:add_bill (sat_to_i64) | reg::sat_amounts_above_21m_btc_are_rejected | COVERED |
| TRK-088 | Modifica bill; id inesistente → false | reg:update_bill | reg::bills_update_changes_fields_in_place; reg::bills_update_returns_false_for_missing_id | COVERED |
| TRK-089 | Segna pagato / togli il segno; id inesistente → false | reg:mark_bill_paid | reg::bills_mark_paid_sets_and_clears_timestamp; reg::bills_mark_paid_returns_false_for_missing_id | COVERED |
| TRK-090 | Elenco per giorno di scadenza poi data di creazione; elimina solo la riga scelta | reg:list_bills / delete_bill | reg::bills_list_orders_by_due_day_then_created; reg::bills_delete_only_target_row | COVERED |
| TRK-091 | Nome bill vuoto accettato dal registry (solo la UI lo blocca) | reg:add_bill | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-092 | Dialog bill: Save solo con nome, importo valido e giorno (cifre, max 2, limitato 1..31); Delete in modifica | screens/BillsTrackerCard.kt:BillEditDialog | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-093 | Card bill: stato vuoto, chip "Pagato" solo se pagato nel mese corrente, toggle pagato, nota sotto il nome | screens/BillsTrackerCard.kt:BillsTrackerCard / BillRow / billSubtitle | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-094 | Salva/elimina/segna pagato dalla UI con messaggio d'errore e bump dataVersion | TS:TrackingScreen (BillEditDialog, onToggleBillPaid) | — | **PARTIAL** (test in §F; prima: UNCOVERED) |

### I. Budget e ripartizione

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|---|---|---|---|---|
| TRK-095 | Imposta/sovrascrivi un budget per categoria; periodo salvato così com'è; elenco vuoto iniziale; persiste alla riapertura | reg:set_budget / list_budgets | reg::budgets_list_is_empty_on_fresh_db; reg::budgets_set_then_list_round_trips; reg::budgets_set_overwrites_existing_category; reg::budgets_period_is_preserved_verbatim; reg::budgets_survive_reopen | COVERED |
| TRK-096 | Elimina budget (true solo se esisteva) | reg:delete_budget | reg::budgets_delete_returns_true_only_when_row_existed | COVERED |
| TRK-097 | Budget oltre 21M BTC rifiutato | reg:set_budget | reg::sat_amounts_above_21m_btc_are_rejected | COVERED |
| TRK-098 | Categoria vuota o periodo arbitrario accettati dal registry | reg:set_budget | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-099 | Dialog budget: salva solo un intero di sat valido; Clear elimina; dalla UI il periodo è sempre "monthly" | screens/BudgetBreakdownCard.kt:BudgetEditDialog; TS (onSave/onClear) | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-100 | Righe budget: spese/entrate per direzione, senza categoria → "Uncategorised", budget senza eventi con actual 0, eventi senza budget, unione, ordinamento per actual | screens/BudgetBreakdownCard.kt:computeBudgetRows | screens/BudgetBreakdownCardTest::empty inputs yield empty rows; ::expense direction sums only outgoing events; ::income direction sums only incoming events; ::null and blank categories collapse into Uncategorised; ::budget without matching event still surfaces with zero actual; ::event without matching budget surfaces with null budgetedSat; ::event and budget on same category merge into one row; ::rows sort by actual descending; ::multiple distinct categories aggregate independently; ::category present only in budgets and category present only in events both appear; ::events with the wrong direction do not contribute to actuals; ::direction field on each row matches the requested direction | COVERED |
| TRK-101 | Direzione della categoria dalla storia completa: budget di spesa non nella tabella entrate; trasferimenti interni esclusi | screens/BudgetBreakdownCard.kt:computeBudgetRows (directionOf) | screens/BudgetBreakdownCardTest::B3 spending budgets do not appear in the income table; ::B3 an income-side budget stays in the income table; ::B3 category direction comes from the full history, not just the window; ::B3 internal transfers are neither income nor spending | COVERED |
| TRK-102 | Budget riproporzionato alla finestra (mensile/settimanale/annuale; "All" = dal primo evento) | screens/BudgetBreakdownCard.kt:prorateBudget / budgetPeriodDays / budgetWindowDays | screens/BudgetBreakdownCardTest::B4 a monthly budget is prorated to a 90 day window; ::B4 prorating handles weekly and yearly budgets and no window; ::B4 window days use the period length, or the event span for All | COVERED |
| TRK-103 | Varianza (freccia e segno) rispetto al budget | screens/BudgetBreakdownCard.kt:budgetVarianceLabel | screens/BudgetBreakdownCardTest::variance arrow and sign follow actual vs budget | COVERED |
| TRK-104 | Card budget: colonne che si nascondono su finestre strette, chip di modifica | screens/BudgetBreakdownCard.kt:BudgetBreakdownCard | screens/TableColumnHidingTest::budget shows every column when wide; ::budget keeps category, budget and actual when narrow; ::budget keeps category, budget and actual | **PARTIAL** (test in §F; prima: PARTIAL) |
| TRK-105 | Sezione Budget mostra card entrate/spese solo se ci sono righe, più Recurring, Bills, lollipop spese (solo con uscite nel periodo) e regole | screens/TrackingDashboard.kt:TrackingDashboardTab (mode BUDGET) | — | **COVERED** (test in §F; prima: UNCOVERED) |

### J. Insight sul budget

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|---|---|---|---|---|
| TRK-106 | Stato budget di spesa (Under / At risk / Over) e di entrata (Reached / At risk / Behind) rispetto al budget riproporzionato | screens/BudgetInsights.kt:budgetStatus | screens/BudgetInsightsTest::expense status thresholds; ::income status thresholds; ::status compares against the prorated budget, not the monthly one; ::no budget, no status; ::zero budget is over as soon as anything is spent | COVERED |
| TRK-107 | Spiegazione del movimento in sats: effetto prezzo vs effetto spesa, somma sempre coerente, ogni evento al prezzo del suo giorno | screens/BudgetInsights.kt:explainBudgetMove | screens/BudgetInsightsTest::same fiat spending after a BTC drop is all price effect; ::more fiat spending at a flat price is all spending effect; ::mixed move splits and always adds up; ::each event is valued at its own day, never an average; ::only the category and direction count, internal transfers excluded | COVERED |
| TRK-108 | Nessuna spiegazione senza finestra precedente o con tasso mancante; lookup = ultimo prezzo prima del timestamp | screens/BudgetInsights.kt:explainBudgetMove / rateLookup | screens/BudgetInsightsTest::no split without a previous window or with a missing rate; ::rate lookup takes the last price at or before the timestamp | COVERED |
| TRK-109 | Nota per riga mostrata solo con valuta fiat attiva, periodo ≠ All e quota prezzo ≥ 1% | screens/TrackingDashboard.kt:movesFor; BudgetBreakdownCard (moveNote) | — | **UNCOVERED (saltato)** (test in §F; prima: UNCOVERED) |

### K. KPI della dashboard (sezione HOME)

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|---|---|---|---|---|
| TRK-110 | KPI di periodo: entrate, uscite, netto, numero tx, saldo; trasferimenti interni esclusi | screens/TrackingAggregations.kt:computeKpis | screens/TrackingDashboardAggregationsTest::computeKpis splits inflow and outflow ignoring self-transfer | **COVERED** (test in §F; prima: PARTIAL) |
| TRK-111 | Saldo confermato vs in attesa (somma netta degli eventi in mempool, limitato a 0) | screens/TrackingAggregations.kt:splitBalance | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-112 | Riga KPI: savings rate inline ("—" senza entrate), sottotitolo "X confermato · Y in attesa", confronto col periodo precedente | screens/TrackingKpi.kt:KpiRow | — | RIMOSSO (redesign Home 2026-10: una sola striscia KPI e la card Composizione) |
| TRK-113 | Etichetta delta rispetto al periodo precedente | screens/TrackingAggregations.kt:deltaLabel | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-114 | Filtro per periodo (D7/D30/D90/Y1/ALL), eventi in mempool sempre inclusi | screens/TrackingAggregations.kt:periodLowerBound / filterByPeriod | screens/TrackingDashboardAggregationsTest::periodLowerBound — ALL returns null; ::periodLowerBound — D7 is now minus 7 days; ::filterByPeriod keeps mempool events regardless of period | COVERED |

### L. KPI finanziari (savings rate, burn, FIRE, runway)

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|---|---|---|---|---|
| TRK-115 | Savings rate su 30 giorni (50%, negativo, null senza entrate, solo finestra) | domain/FinanceKpis.kt:computeFinanceKpis | screens/FinanceKpisTest::savings rate 50 percent when expense is half of income; ::savings rate negative when expense exceeds income; ::savings rate null when income is zero; ::savings rate uses only last 30 days window | **COVERED (logica testata; card rimontate nella Home del tracker il 2026-10-24)** |
| TRK-116 | Burn mensile = uscita giornaliera × 30 su 90 giorni; null senza uscite; esclude uscite oltre 90 giorni | domain/FinanceKpis.kt:computeFinanceKpis | screens/FinanceKpisTest::burn rate is daily outflow times 30 over 90d window; ::burn rate excludes outflows older than 90 days; ::burn rate null when zero outflow in 90 days | **COVERED (logica testata; card rimontate nella Home del tracker il 2026-10-24)** |
| TRK-117 | FIRE = 300 × burn; null senza burn | domain/FinanceKpis.kt:computeFinanceKpis | screens/FinanceKpisTest::FIRE number is 300 times burn rate; ::FIRE number null when burn rate null | **COVERED (logica testata; card rimontate nella Home del tracker il 2026-10-24)** |
| TRK-118 | Runway = asset liquidi / burn (frazionario), esclusi asset manuali illiquidi (cash e brokerage restano) | domain/FinanceKpis.kt:computeFinanceKpis / illiquidManualAssetsSat | screens/FinanceKpisTest::runway is total asset over burn rate; ::runway null when burn rate null; ::runway can be fractional; ::runway excludes illiquid assets; ::illiquidManualAssetsSat skips cash and brokerage | **COVERED (logica testata; card rimontate nella Home del tracker il 2026-10-24)** |
| TRK-119 | Input da ignorare: lista vuota, eventi senza timestamp o futuri, trasferimenti interni (send_to_self conta solo la fee) | domain/FinanceKpis.kt:computeFinanceKpis | screens/FinanceKpisTest::empty events yields all null KPIs; ::events without timestamp are ignored; ::future-dated events are ignored; ::self transfer events are ignored; ::send_to_self counts only its fee as outflow | **COVERED (logica testata; card rimontate nella Home del tracker il 2026-10-24)** |
| TRK-120 | Striscia KPI: 5 card (SAVINGS, BURN, FIRE, RUNWAY, OPP_COST), "—" quando null, colori per soglia | screens/KpiStripV7.kt:KpiStripV7 | screens/KpiStripV7BitcoinLensTest::five KPI cards rendered including OPP_COST; ::OPP_COST shows em-dash placeholder with vs HODL subtitle; ::BURN and FIRE use Money Compact BTC primary; ::SAVINGS and RUNWAY render unchanged percent and months strings; ::null BURN and FIRE render em-dash placeholders; screens/KpiStripV7OppCostTest (5 test) | **PARTIAL (non montato)** (test in §F; prima: PARTIAL) |

### M. Previsione di cassa e revisione mensile

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|---|---|---|---|---|
| TRK-121 | Previsione 90 giorni: entrata giornaliera media degli ultimi 90 giorni, bill nel loro giorno ogni mese, ricorrenze settimanali, uscite non ricorrenti medie | domain/CashFlowForecast.kt:forecastCashFlow | screens/CashFlowForecastTest::daily inflow averages last 90 days of incoming; ::inflows outside 90 day window are ignored for the rate; ::bill due on day 15 produces an outflow on day 15 of every future month; ::weekly recurring drops once per week; ::average non-recurring outflow is subtracted daily | COVERED |
| TRK-122 | Varianti previsione: input vuoti → linea piatta, orizzonte 0/negativo → vuota, bill già pagato nel mese saltato, ricorrenze LOW ignorate, saldo negativo ammesso, niente doppio conteggio | domain/CashFlowForecast.kt:forecastCashFlow | screens/CashFlowForecastTest::empty inputs return horizon plus one flat points; ::zero horizon returns empty; ::negative horizon returns empty; ::bill already paid this month is skipped for the current cycle; ::LOW confidence recurring is ignored; ::balance can go negative when outflow exceeds inflow; ::recurring samples and send_to_self are not double counted | COVERED |
| TRK-123 | Bill con giorno 31 in un mese più corto (giorno limitato alla fine mese) | domain/CashFlowForecast.kt:forecastCashFlow | — | **COVERED (logica)** (test in §F; prima: UNCOVERED) |
| TRK-124 | Card previsione: valore finale, delta %, data, rossa se finisce in negativo | screens/CashFlowForecastCard.kt:CashFlowForecastCard / CashFlowForecastEnd | — | **PARTIAL (non montato)** (test in §F; prima: UNCOVERED) |
| TRK-125 | Banner revisione mensile visibile dal 1 al 3 del mese se non respinto nel mese; ricompare il mese dopo | domain/CashFlowForecast.kt:shouldShowMonthlyReviewBanner / startOfMonthUnix | screens/CashFlowForecastTest::banner shows on day 1 when never dismissed; ::banner hidden after day 3; ::banner hidden if dismissed this month; ::banner shows again next month after dismiss; ::startOfMonthUnix returns midnight UTC of day 1 | **COVERED (logica testata; card rimontate nella Home del tracker il 2026-10-24)** |
| TRK-126 | Banner: Review naviga, Dismiss salva la data nelle impostazioni (persistita) | screens/MonthlyReviewBanner.kt:MonthlyReviewBanner; settings/SettingsStore.kt:lastMonthlyReviewDismissedUnix | — | **PARTIAL (non montato)** (test in §F; prima: UNCOVERED) |

### N. Grafici e aggregazioni della sezione Analytics/Reports

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|---|---|---|---|---|
| TRK-127 | Serie del saldo cumulato fino a oggi (vuota, piatta, baseline) | screens/TrackingAggregations.kt:balanceSeries | screens/TrackingDashboardAggregationsTest::balanceSeries empty input returns empty; ::balanceSeries cumulates and extends to now; ::balanceSeries flat window still draws a baseline; ::balanceSeries handles flat series across the whole history | COVERED |
| TRK-128 | Confronto con il periodo precedente (serie e KPI precedenti; nascosto con ALL) | screens/TrackingAggregations.kt:balanceSeriesPrevious; TrackingDashboard (prevKpis, CompareToggle) | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-129 | Barre entrate/uscite con unità adattiva (giorno/settimana/mese) e totali coerenti | screens/TrackingAggregations.kt:flowBars / pickBucketUnit | screens/TrackingDashboardAggregationsTest::flowBars D7 buckets daily and totals match raw events; ::flowBars empty input returns empty; ::flowBars D90 buckets weekly; ::pickBucketUnit ALL adapts to event span; ::flowBars ALL with concentrated events produces readable buckets | COVERED |
| TRK-130 | Fette per categoria ordinate per volume | screens/TrackingAggregations.kt:categorySlices | screens/TrackingDashboardAggregationsTest::categorySlices buckets by category and ranks by volume; ::categorySlices empty input returns empty | COVERED |
| TRK-131 | Diagramma Sankey: entrate → totale → spese + risparmio, categorie in pareggio scartate, send_to_self solo fee | screens/SankeyDiagram.kt:buildSankeyFlow / computeNodeHeights | screens/AccessibilityTablesTest::table builders mirror the chart data (solo la tabella da un SankeyFlow già costruito) | **COVERED** (test in §F; prima: PARTIAL) |
| TRK-132 | Heatmap calendario dei volumi giornalieri, navigazione mese | screens/TrackingAggregations.kt:dailyVolumes; TrackingCharts.kt:CalendarHeatmap | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-133 | Scatter attività (età × importo log), tick logaritmici; mostrato solo con eventi confermati | screens/ActivityScatter.kt:ActivityScatter / computeLogTicks | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-134 | Grafico a linee liscio/a gradini, unità barre dedotta, etichette dei tick | screens/TrackingAggregations.kt:steppedPoints / formatTimestampTick; TrackingCharts.kt:deriveBarUnit | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-135 | Ciambella/treemap categorie, click su fetta → Transactions filtrata per categoria; click su barra → filtro intervallo | TS (onCategoryDrillDown, onBucketDrillDown); TrackingCharts.kt:CategoryDonut / CategoryTreemap; TrackingKpi.kt:BucketDrillRow | screens/PrivacyModeTest::privacy mode hides every amount in representative composables (rende CategoryDonut, senza click) | **PARTIAL** (test in §F; prima: PARTIAL) |
| TRK-136 | Card grafico con alternanza grafico/tabella accessibile | screens/TrackingCharts.kt:ChartCard; ui ChartDataTable | screens/AccessibilityTablesTest::chart card toggle swaps the chart for its table; ::table builders mirror the chart data; ::chart table rows follow the density setting | COVERED |
| TRK-137 | Stato vuoto/skeleton della dashboard (skeleton se refresh senza eventi, testo vuoto in Analytics) | screens/TrackingDashboard.kt:TrackingDashboardTab | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-138 | Reports: tabella per categoria ordinabile (categoria, n, entrate, uscite, netto, %, ultimo) e per wallet (etichetta, n, saldo, entrate, uscite, ultimo), stato vuoto | screens/TrackingReports.kt:ReportsTabBody / CategoryReportTable / WalletReportTable | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-139 | Sezione Reports con eventi filtrati per periodo e solo wallet inclusi; grafici power-user solo col modulo Analytics Labs | TS:TrackingScreen (REPORTS) | — | **COVERED** (test in §F; prima: UNCOVERED) |

### O. Tab Transactions del tracker: filtri, viste salvate, navigazione

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|---|---|---|---|---|
| TRK-140 | Filtro categoria a tre stati (tutte / solo senza categoria / categoria esatta) combinato in AND con l'intervallo temporale | TS:TrackingScreen (filteredEvents) | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-141 | Chip dei filtri categoria (con "Uncategorised" solo se esistono eventi senza categoria) e chip intervallo attivo con X | screens/TrackingHistoryTab.kt:CategoryFilterRow / ActiveRangeFilterChip | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-142 | Lista eventi: prime 50 righe + "mostrate 50 di N", stato vuoto, "nessuna corrispondenza", skeleton durante il refresh | screens/TrackingHistoryTab.kt:HistoryTabBody | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-143 | Saldo progressivo per riga (eventi confermati in ordine, mempool con l'ultimo saldo), con flussi esterni | screens/TrackingHistoryTab.kt:HistoryTabBody (runningBalance) | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-144 | Card ripartizione per categoria (entrate/uscite/conteggio, ordinata per volume) | TS:TrackingScreen (breakdown); TrackingHistoryTab.kt:CategoryBreakdownCard | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-145 | Salva la vista corrente (nome, periodo, filtro categoria, intervallo); stesso nome sovrascrive; nome vuoto rifiutato | reg:save_view; TrackingKpi.kt:SaveViewDialog | reg::save_and_list_views_round_trip; reg::save_view_replaces_by_name; reg::save_view_rejects_blank_name | **COVERED** (test in §F; prima: PARTIAL) |
| TRK-146 | Applica una vista salvata (periodo sconosciuto → D30) ed elimina una vista | TS:TrackingScreen (SavedViewsBar onApply/onDelete); reg:delete_view / list_views | reg::delete_view_removes_only_target_row | **COVERED** (test in §F; prima: PARTIAL) |
| TRK-147 | Filtri e periodo condivisi tra sezioni (un drill-down da Budget arriva su Transactions con il filtro); Esc/"Clear filters" li azzera | screens/TrackerUiState.kt:TrackerUiState / clearFilters; TS (onKey Escape) | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-148 | Selettore periodo globale (nascosto in Transactions), frecce ←/→ cambiano periodo ai bordi senza uscire | screens/TrackingKpi.kt:PeriodSelector; TS (onKey) | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-149 | Scorciatoie: R refresh (non durante un refresh), 1–6 sezioni, Ctrl/Cmd+K palette; ignorate mentre si scrive in un campo | TS:TrackingScreen (onKey) | — | **PARTIAL** (test in §F; prima: UNCOVERED) |
| TRK-150 | Command palette del tracker: vai alla sezione, privacy, Security Hub, refresh, clear filters, save view, export CSV, periodo | TS:TrackingScreen (CommandPalette actions) | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-151 | Striscia stato dati: ultima sync, chip "N stale su M", refresh disabilitato durante il refresh | screens/TrackingKpi.kt:DataStateStrip / StaleChip | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-152 | Orologio che si aggiorna a mezzanotte locale per ricalcolare le finestre | screens/TrackingClock.kt:rememberTickingNowUnix / msUntilNextTick | — | **COVERED** (test in §F; prima: UNCOVERED) |

### P. Tabella tx del wallet (workspace): filtri, ricerca, ordinamento

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|---|---|---|---|---|
| TRK-153 | Filtri ALL / INCOMING / OUTGOING / PENDING e ricerca libera su etichetta, categoria e txid | screens/WalletTxModel.kt:filterTransactions; HistoryScreen.kt:TransactionsToolbar | screens/WalletTxModelTest::filters by direction, pending and free text over label category and txid; screens/HistoryScreenTest::filter chips and search narrow the table | COVERED |
| TRK-154 | Ordinamento per data (pending in cima), etichetta (con etichetta prima), importo con segno, stato, conferme; click intestazione inverte | screens/WalletTxModel.kt:sortTransactions; HistoryScreen.kt:TxTableHeader | screens/WalletTxModelTest::default date sort puts pending first, newest next; ::amount sort is by signed net, and confirmations by depth; ::label sort puts labelled rows first, alphabetically | **COVERED** (test in §F; prima: PARTIAL) |
| TRK-155 | Stato vuoto, chip di stato (Pending, Confirming n/6, finale), segno +/−, click riga apre il dettaglio | screens/HistoryScreen.kt:HistoryScreen / TxStatusChip | screens/HistoryScreenTest::empty state when wallet has no transactions; ::clicking a row fires onTxClicked and opens the detail pane; ::status chips follow Pending, Confirming n of 6 and no badge once final; ::a mined tx with an unknown tip reads Confirming, never final; ::incoming amounts carry a plus sign, outgoing a minus; screens/WalletTxModelTest (4 test di stato/conferme) | COVERED |

### Q. Export CSV degli eventi

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|---|---|---|---|---|
| TRK-156 | Corpo CSV: intestazione con colonna crypto_event_kind, una riga per evento, celle di testo protette da formula injection e quotate | screens/TrackingCsvExport.kt:buildEventsCsv / csvTextCell | screens/TrackingCsvExportTest::formula triggers are neutralised and quoted when needed; ::csv has crypto kind column and escaped text cells | COVERED |
| TRK-157 | Estensione .csv forzata | screens/TrackingCsvExport.kt:withCsvExtension | screens/TrackingCsvExportTest::csv extension is forced | COVERED |
| TRK-158 | Conferma prima dell'export solo con privacy mode attiva | screens/TrackingCsvExport.kt:confirmExportWhilePrivate | screens/PrivacyModeTest::export asks for confirmation only while privacy mode is on | COVERED |
| TRK-159 | Scrittura atomica (file temporaneo + move, fallback senza ATOMIC_MOVE, nessun residuo) | screens/TrackingCsvExport.kt:writeFileAtomically | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-160 | Flusso export: nessun evento → niente; annullato nel file chooser o rifiutata la sovrascrittura → niente; errore di scrittura → messaggio; pulsante disabilitato senza eventi o durante il refresh | screens/TrackingCsvExport.kt:exportEventsAsCsv; TS (EXPORT_CSV) | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-161 | L'export usa tutti gli eventi, non quelli filtrati né solo i wallet inclusi | TS:TrackingScreen (exportEventsAsCsv(events, …)) | — | **COVERED** (test in §F; prima: UNCOVERED) |

### R. Privacy: mascheramento importi nelle schermate del tracker

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|---|---|---|---|---|
| TRK-162 | Privacy mode nasconde gli importi nei composable rappresentativi (Money, ciambella NW, grafico entrate/uscite, ciambella categorie) e nella vista tabella dei grafici | ui/money/AmountMask; screens/TrackingCharts.kt | screens/PrivacyModeTest::privacy mode hides every amount in representative composables; ::the same composables show amounts when privacy mode is off; ::chart table view is masked too; ::masked columns keep sats in the header and neutral sign colours | COVERED |
| TRK-163 | Nessuno schermo formatta importi con i formatter non mascherati; importi con segno passano dagli helper privacy-aware; niente stringhe BTC costruite a mano | screens/* | screens/PrivacyModeTest::screens never format amounts with the unmasked shared formatters; ui/money/SignedAmountScanTest::signed amounts take their sign and colour from the privacy-aware helpers; ui/money/AmountStringGuardTest::no hand-built bitcoin amount strings outside the formatter | COVERED |
| TRK-164 | Formatter mascherati: larghezza fissa senza segno, fiat con prefisso, grafici e helper legacy, Auto non passa a BTC | ui/money/AmountMask | ui/money/AmountMaskTest (8 test, es. ::bitcoin formatter masks every unit with a fixed width and no sign; ::amount signs disappear while masked) | COVERED |
| TRK-165 | Toggle privacy: Ctrl+Shift+H (una volta anche tenendo premuto), icona nel footer, voce nella command palette del tracker | settings/SettingsStore.kt:toggleBalancesHidden; TS (palette) | screens/PrivacyModeTest::ctrl shift H is the privacy shortcut; ::holding the shortcut toggles once, not on every key repeat; ::footer eye icon toggles the privacy mode | **COVERED** (test in §F; prima: PARTIAL) |
| TRK-166 | Mascheramento nelle card specifiche: righe budget, bill, ricorrenze, regole (condizioni importo), Reports, KPI, Sankey/scatter, riga evento e saldo progressivo | screens/{BudgetBreakdownCard,BillsTrackerCard,RecurringCard,RulesCard,TrackingReports,TrackingKpi,SankeyDiagram,ActivityScatter,TrackingHistoryTab}.kt | screens/RuleAmountPrivacyTest::amount conditions and legacy names render through the formatter (solo regole); scan statici di TRK-163 | **PARTIAL** (test in §F; prima: PARTIAL) |

### S. Tracker home (parti di attività)

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|---|---|---|---|---|
| TRK-167 | Al primo montaggio la home avvia il cold load; "Loading…" solo prima del primo caricamento durante il refresh | screens/TrackerHomeScreen.kt:TrackerHomeSection | — | **PARTIAL** (test in §F; prima: UNCOVERED) |
| TRK-168 | Banner wallet stale con pulsante refresh e messaggio d'errore della cache | screens/TrackerHomeScreen.kt:TrackerStaleBanner | — | COVERED (redesign Home 2026-10: niente banner, la sync sta nella DataStateStrip; card "Da fare") |
| TRK-169 | Eventi ridotti a PaymentEventLite per la ricostruzione storica del net worth | screens/TrackerHomeScreen.kt:buildPaymentEventLite | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-170 | Intestazione WALLETS: refresh con tooltip (disabilitato durante il refresh), "+ Add" | screens/TrackerHomeScreen.kt:WalletsHeader | — | **COVERED** (test in §F; prima: UNCOVERED) |
| TRK-171 | Conversione periodo tracker ↔ periodo net worth | screens/TrackerHomeScreen.kt:toNetWorthPeriod / toTrackingPeriod | — | **COVERED** (test in §F; prima: UNCOVERED) |

---

### Riepilogo

| Stato | Numero |
|---|---|
| COVERED | 71 |
| PARTIAL | 24 |
| UNCOVERED | 76 |
| **Totale** | **171** |

Priorità consigliate per nuovi test:
1. **Test Kotlin con registry reale per `TrackerCache.refresh(syncEsplora=false)`** (stile PortfolioScenarioTest + `RegistryStore.set`): coprirebbe insieme TRK-002/003/007/008/012/022/045/062/063/079.
2. **Estrarre le funzioni pure oggi dentro i composable** (`mergeLegs`, `filterLedger`, `includedEvents`, `reapplyRules`, `acceptRecurringAsBill`, `isPaidThisMonth`, running balance, breakdown) e testarle: TRK-022/026/064/083/093/140/143/144.
3. **Test Rust per `list_watch_events`** (categoria + kind uniti, wallet illeggibile saltato): TRK-017/018/019 (il test-book ne cita uno che non esiste).
4. **Decidere sui composable non montati** (KpiStripV7, CashFlowForecastCard, MonthlyReviewBanner) e sull'export CSV non filtrato prima di scrivere altri test.

<a id="d"></a>

## Inventario casi d'uso — Area D: Portfolio e patrimonio

Abbreviazioni percorsi:
- `K/` = `apps/desktop/src/jvmMain/kotlin/io/bhodl/desktop/`
- `T/` = `apps/desktop/src/jvmTest/kotlin/io/bhodl/desktop/`
- `R` = `crates/bhodl-wallet-onchain/src/registry.rs` (test in `registry::tests`)
- `BF` = `crates/bhodl-wallet-onchain/src/btc_at_time_backfill.rs`, `RH` = `btc_rate_history.rs`, `NWH` = `net_worth_history.rs`
- FFI: ogni metodo registry è esposto 1:1 in `crates/bhodl-uniffi/src/commonMain/rust/lib.rs` (`RegistryHandle`); i test uniffi non coprono quest'area (solo `rate_methods_run_on_the_handle_key_without_a_pin`, `wallet_error_rate_*`).
- Il wiring UI (save/delete) dei dialog è in `K/screens/TrackingScreen.kt` (handler `onSave`/`onDelete` dei dialog).

Stato: COVERED = logica + persistenza testate in automatico; PARTIAL = parte testata, buchi indicati; UNCOVERED = nessun test automatico.

### Investimenti — anagrafica

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|---|---|---|---|---|
| PRT-001 | Aggiungere un investimento (nome, tipo, ticker, qty, unità, costo, valuta, data, valore manuale) | R:add_investment; K/screens/InvestmentEditDialog.kt:InvestmentEditDialog; TrackingScreen.kt:InvestmentEditDialog.onSave | R::investments_add_round_trips_all_fields; T/screens/PortfolioScenarioTest.kt (helper `etf`) | **COVERED** (test in §F; prima: PARTIAL) |
| PRT-002 | Alla creazione da dialog (qty>0 e costo>0) viene seminato un lot dall'aggregato | TrackingScreen.kt:onSave → R:seed_investment_lot_from_aggregate | R::investment_seed_lot_from_aggregate_atomic | **COVERED (helper)** (test in §F; prima: PARTIAL) |
| PRT-003 | Investimento senza ticker (posizione solo manuale) | R:add_investment | R::investments_accepts_null_ticker_for_manual_position | COVERED |
| PRT-004 | Quantità frazionaria | R:add_investment | R::investments_accepts_fractional_quantity | COVERED |
| PRT-005 | Tipo asset nella whitelist (6 classi), tipo sconosciuto rifiutato in add/update; colore/slot per tipo | R:validate_asset_type; K/screens/InvestmentsFormat.kt:assetTypeIndex, investmentTypeLabel | R::investment_asset_type_outside_whitelist_rejected, R::investment_asset_type_whitelist_all_accepted, R::investment_update_rejects_invalid_asset_type, R::investments_round_trips_each_asset_type; T/screens/InvestmentsCardTest.kt::assetTypeIndex resolves the 6 known asset classes…, ::assetTypeIndex is case insensitive, ::assetTypeIndex falls back to neutral slot… | COVERED |
| PRT-006 | Rifiuto NaN/Inf/negativi su qty, costo, valore manuale; zero accettato | R:add_investment/update_investment (validazioni) | R::investment_rejects_nan_quantity, R::investment_rejects_infinite_cost_basis, R::investment_rejects_nan_manual_current_value, R::investment_accepts_zero_but_rejects_negative_amounts, R::negative_or_nan_amounts_are_rejected | COVERED |
| PRT-007 | Validazione dialog: nome vuoto, qty≤0, costo<0, parsing locale ("350.000" it_IT) disabilitano Save | InvestmentEditDialog.kt:canSave + ui/money parseDecimalInput | T/ui/money/DecimalInputTest.kt (solo parsing) | **COVERED** (test in §F; prima: PARTIAL) |
| PRT-008 | Modificare metadati di un investimento (nome/ticker/tipo/valuta/valore per unità) | R:update_investment; TrackingScreen.kt:onSave (ramo edit) | R::investments_update_changes_fields_in_place, R::investments_update_returns_false_for_missing_id | **PARTIAL** (test in §F; prima: PARTIAL) |
| PRT-009 | Edit qty/costo con esattamente 1 lot → il lot viene risincronizzato (prezzo = costo/qty − fee/unità, considerando la quota già venduta) | TrackingScreen.kt:InvestmentEditDialog.onSave (blocco `lots.size == 1`) | — | **COVERED (helper)** (test in §F; prima: UNCOVERED) |
| PRT-010 | Investimento multi-lot: qty/costo read-only nel dialog, pre-fill con la somma dei lot | InvestmentEditDialog.kt:InvestmentEditDialog (`lotManaged`, readOnly) | — | **COVERED** (test in §F; prima: UNCOVERED) |
| PRT-011 | Eliminare un investimento con cascata su lot, vendite e disposals | R:delete_investment; InvestmentEditDialog onDelete | R::investments_delete_returns_true_then_false, R::investment_lots_cascade_delete_on_investment_delete, R::sells_cascade_delete_on_investment_delete | COVERED |
| PRT-012 | Lista investimenti ordinata per data di creazione | R:list_investments | R::investments_list_ordered_by_created_at_asc | COVERED |
| PRT-013 | Persistenza e migrazioni schema idempotenti alla riapertura | R (schema/migrazioni) | R::all_tracking_tables_survive_reopen_with_correct_key, R::schema_migration_is_idempotent_across_opens | COVERED |
| PRT-014 | Scegliere il metodo cost basis per investimento (FIFO/HIFO/Specific-ID), default FIFO, valore sconosciuto rifiutato/tollerato | R:set_investment_cost_basis_method, get_investment_cost_basis_method; K/screens/InvestmentLots.kt:CostBasisMethod.fromWire | R::investment_cost_basis_method_defaults_to_fifo, R::investment_cost_basis_method_set_persists, R::investment_cost_basis_method_rejects_unknown; T/screens/InvestmentLotsTest.kt::fromWire maps each known method, ::fromWire falls back to FIFO for null and unknown | COVERED |

### Investimenti — prezzi (manuale vs fetchato)

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|---|---|---|---|---|
| PRT-015 | Valore corrente manuale per unità usato quando non c'è prezzo fetchato | K/domain/GoalBuckets.kt:investmentsTotalSat; K/screens/PortfolioSheet.kt:investmentValueSat | T/screens/GoalBucketsTest.kt::investmentsTotalSat falls back to manual when no fetched value, ::investments bucket falls back to manual when no fetched value | COVERED |
| PRT-016 | Prezzo fetchato scritto con timestamp, preferito al manuale senza sovrascriverlo | R:set_investment_fetched_price; K/prices/PriceCache.kt:refreshInvestmentPricesLocked | R::investments_set_fetched_price_writes_value_and_timestamp, R::investments_set_fetched_price_preserves_previous_manual_value, R::set_fetched_price_does_not_overwrite_manual_value, R::fetched_value_is_independent_across_multiple_calls, R::investments_set_fetched_price_returns_false_for_missing_id, R::set_investment_fetched_price_rejects_nan, R::set_investment_fetched_price_rejects_infinity; GoalBucketsTest::investmentsTotalSat prefers fetched over manual | **COVERED** (test in §F; prima: PARTIAL) |
| PRT-017 | Quote Yahoo v8: prezzo + valuta, fallback USD/simbolo, ticker inesistente → null | K/prices/PriceFetcher.kt:fetchYahooQuote, parseYahooBody | T/prices/PriceFetcherTest.kt::parseYahooBody extracts the regular market price, ::…falls back to USD when currency field absent, ::…uses the fallback symbol…, ::…returns null on empty result list, ::…returns null when result is null (unknown ticker), ::…returns null when price is missing, ::…ignores extra unknown fields… | COVERED |
| PRT-018 | URL Yahoo sicuro (trim, encoding path-safe, ticker vuoto → null, mai eccezioni) | PriceFetcher.kt:buildYahooUrl | PriceFetcherTest::buildYahooUrl returns null for blank ticker, ::…trims whitespace…, ::…preserves common Yahoo ticker characters, ::…escapes ampersand and slash…, ::…escapes hash and plus and percent, ::…escapes whitespace…, ::…never throws for any input | COVERED |
| PRT-019 | Quota in valuta diversa convertita via BTC; scrittura saltata se manca un tasso | PriceCache.kt:convertViaBtc | T/prices/PriceCacheTest.kt::convertViaBtc returns price unchanged when currencies match, ::…bridges USD quote to EUR via BTC, ::…returns null when a rate is missing, ::…returns null on non-positive or non-finite rates, ::…match check is case-insensitive | COVERED |
| PRT-020 | Auto-fetch prezzi investimenti: OFF di default (nessuna chiamata), ON → loop 5 min, attivazione del toggle avvia subito un fetch | PriceCache.kt:refreshNow (flag `autoFetchInvestmentPrices`); Main.kt LaunchedEffect | — | **PARTIAL** (test in §F; prima: UNCOVERED) |
| PRT-021 | Refresh manuale (chip prezzo hero / chip "stale") forza le quote anche con auto-fetch off, serializzato col loop | PriceCache.kt:refreshNow(forceInvestmentPrices=true), refreshInvestmentPrices | PriceCacheTest::refreshNow serializes concurrent calls via mutex | **PARTIAL** (test in §F; prima: PARTIAL) |
| PRT-022 | Limite di ticker quotati per passata (MAX_QUOTED_TICKERS) | PriceCache.kt:refreshInvestmentPricesLocked | — | **COVERED** (test in §F; prima: UNCOVERED) |

### Lotti

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|---|---|---|---|---|
| PRT-023 | Aggiungere un lot (qty, prezzo/unità, fee, data, note) | R:add_investment_lot; K/screens/LotEditDialog.kt:LotEditDialog; TrackingScreen.kt:LotEditDialog.onSave | R::investment_lots_add_then_list_round_trips; PortfolioScenarioTest (tutti gli scenari) | **COVERED** (test in §F; prima: PARTIAL) |
| PRT-024 | Primo lot su investimento legacy con aggregato → seed automatico prima del nuovo lot (no doppio conteggio) | TrackingScreen.kt:LotEditDialog.onSave → seed_investment_lot_from_aggregate + add_investment_lot | R::investment_seed_lot_from_aggregate_atomic | **COVERED (sequenza registry)** (test in §F; prima: PARTIAL) |
| PRT-025 | Seed rifiutato con aggregato zero o se esistono già lot | R:seed_investment_lot_from_aggregate | R::investment_seed_lot_rejects_zero_aggregate, R::seed_lot_refuses_when_lots_exist | COVERED |
| PRT-026 | Validazione lot: qty e prezzo >0 finiti, fee vuota = 0, fee non parsabile/negativa blocca il Save | LotEditDialog.kt:canSave/feesInvalid; R:add/update_investment_lot | R::investment_lots_reject_non_finite_quantity, R::negative_or_nan_amounts_are_rejected | **COVERED** (test in §F; prima: PARTIAL) |
| PRT-027 | Modificare un lot (anche la data) e ri-risolvere il tasso BTC storico | R:update_investment_lot; TrackingScreen.kt → K/prices/TrackerBtcRate.kt:resolveLot | R::investment_lots_update_persists_changes; BF::resolve_lot_btc_rate_writes_historical_rate_not_current | **PARTIAL (skip)** (test in §F; prima: PARTIAL) |
| PRT-028 | Eliminare un lot; rifiutato se ha disposals | R:delete_investment_lot | R::investment_lots_delete_returns_true_then_false, R::delete_lot_with_disposals_is_refused | COVERED |
| PRT-029 | Lot ordinati per data di acquisto | R:list_investment_lots | R::investment_lots_list_ordered_by_purchase_asc | COVERED |
| PRT-030 | Qty/costo effettivi: fallback all'aggregato legacy senza lot, altrimenti somma lot con la quota di fee non venduta | K/screens/InvestmentLots.kt:effectiveQuantity, effectiveCostBasis | InvestmentLotsTest::effectiveCostBasis empty lots falls back to legacy aggregate, ::effectiveCostBasis sums quantity times price plus fees, ::effectiveCostBasis skips lots with non-finite numbers, ::effectiveQuantity empty lots falls back…, ::effectiveQuantity sums lot quantities; PortfolioScenarioTest::after a partial sell the remaining cost basis carries only the unsold share of fees | COVERED |
| PRT-031 | Fee per unità spalmata sulla quantità originale (original_quantity, backfill dalla migrazione) | InvestmentLots.kt:lotFeePerUnit; R (original_quantity) | InvestmentLotsTest::lotFeePerUnit spreads fees over the original quantity, ::…prefers the registry originalQuantity…, ::…is zero for non-finite or zero fees; R::realized_pnl_includes_lot_purchase_fees_over_original_quantity, R::original_quantity_migration_backfills_from_disposals | COVERED |
| PRT-032 | Migrazione tabella lot idempotente | R (schema lots) | R::investment_lots_migration_idempotent_across_opens | COVERED |
| PRT-033 | Espansione riga (caret) con sotto-righe lot e vendite, stato vuoto "No lots yet", link "+ Add lot", caret per posizioni seed-ready | K/screens/InvestmentsCard.kt:InvestmentsCard; InvestmentEditDialog.kt:InvestmentLotsExpansion | T/screens/InvestmentsCardBitcoinLensTest.kt::lot row shows BTC at purchase secondary when backfilled, ::sell row shows dual signed pnl when btc backfilled | **COVERED** (test in §F; prima: PARTIAL) |
| PRT-034 | Scrittura diretta del tasso BTC di un lot (API legacy) | R:set_investment_lot_btc_rate (FFI) | — | **COVERED** (test in §F; prima: UNCOVERED) |

### Vendite

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|---|---|---|---|---|
| PRT-035 | Vendita FIFO: consuma prima i lot più vecchi, realizzato corretto | R:record_sell; K/screens/SellDialog.kt; TrackingScreen.kt:SellDialog.onSave | R::sells_record_consumes_lots_in_fifo_order; PortfolioScenarioTest::buy two lots then a partial FIFO sell books realized and leaves the right remainder; InvestmentLotsTest::FIFO sorts by purchase unix ascending; LotMethodComparisonTest::FIFO consumes the oldest lots first | COVERED |
| PRT-036 | Vendita HIFO: consuma prima il lot con prezzo + fee/unità più alti | R:record_sell; InvestmentLots.kt:lotsSortedForDisposal | R::sells_record_consumes_lots_in_hifo_order; PortfolioScenarioTest::HIFO sells the most expensive lot first; InvestmentLotsTest::HIFO sorts by price per unit descending; LotMethodComparisonTest::HIFO consumes the highest unit cost first, ::HIFO ranks by price plus the purchase fee per unit, like the registry | COVERED |
| PRT-037 | Vendita Specific-ID: picker per lot, consuma esattamente i lot scelti (anche svuotandone uno) | R:record_sell_with_lots; SellDialog.kt (LotPicker); InvestmentLots.kt:validateLotSelection, openLotsAt | R::specific_id_sell_consumes_exactly_the_picked_lots, R::specific_id_sell_can_empty_a_lot_exactly; PortfolioScenarioTest::specific-id sell consumes exactly the picked lots; T/screens/SellDialogTest.kt::specific-ID valid picks enable Save and pass the selection; InvestmentLotsTest::SPECIFIC_ID lists lots oldest first in the picker | COVERED |
| PRT-038 | Specific-ID senza selezione o con selezione invalida rifiutato atomicamente (mai fallback FIFO) | R:record_sell / record_sell_with_lots; InvestmentLots.kt:validateLotSelection | R::specific_id_sell_without_selection_is_refused, R::specific_id_sell_is_rejected_not_silently_fifo, R::specific_id_sell_validates_the_selection_atomically; InvestmentLotsTest::validateLotSelection accepts picks…, ::…is Empty when nothing is picked, ::…rejects over-picks, bad numbers, unknown and future lots; SellDialogTest::specific-ID shows a lot picker and refuses over-picks | COVERED |
| PRT-039 | Vendita parziale lascia il residuo sul lot | R:record_sell | R::sells_partial_consumption_leaves_residual; PortfolioScenarioTest::buy two lots then a partial FIFO sell… | COVERED |
| PRT-040 | Over-sell rifiutato (registry) e Save disabilitato nel dialog | R:record_sell; SellDialog.kt:overSell/canSave; LotMethodComparison.kt | R::sells_reject_over_sell; PortfolioScenarioTest::overselling and selling before the purchase date are refused; LotMethodComparisonTest::an over-sell leaves FIFO and HIFO without an outcome | **COVERED** (test in §F; prima: PARTIAL) |
| PRT-041 | Vendita con data anteriore all'acquisto rifiutata; lot comprati dopo la data esclusi | R:record_sell; InvestmentLots.kt:openLotsAt | R::sell_cannot_consume_lots_bought_after_sale_date; PortfolioScenarioTest::overselling and selling before the purchase date are refused; LotMethodComparisonTest::lots bought after the sale date are not eligible | COVERED |
| PRT-042 | Rifiuto qty zero, NaN/Inf, prezzo o fee negativi | R:record_sell | R::sells_reject_zero_qty, R::sells_reject_non_finite, R::negative_or_nan_amounts_are_rejected | COVERED |
| PRT-043 | Tolleranza relativa sulle quantità e residui di polvere portati a zero | R:record_sell | R::sell_uses_relative_tolerance_and_snaps_dust_to_zero | COVERED |
| PRT-044 | Fee di vendita ripartite pro-rata sulle disposals | R:record_sell; LotMethodComparison.kt:planOrderedConsumption | R::disposals_gain_loss_includes_pro_rata_fee; LotMethodComparisonTest::sale fees are allocated pro-rata to the consumed quantities | COVERED |
| PRT-045 | Eliminare una vendita: ripristina i lot atomicamente, rimuove disposals e P&L realizzato | R:delete_investment_sell; TrackingScreen.kt:onDeleteSell | R::delete_sell_restores_lot_quantities_atomically, R::sells_cascade_delete_disposals_on_sell_delete; PortfolioScenarioTest::deleting a sell restores the lots and removes the realized P&L | COVERED |
| PRT-046 | Anteprima P&L nel dialog identica a quanto registrato dal registry | InvestmentLots.kt:previewSellPnl, previewSelectionPnl | InvestmentLotsTest::previewSellPnl mirrors backend FIFO with lot and sale fees, ::previewSelectionPnl books exactly the picked lots with fees; PortfolioScenarioTest (preview == realized); LotMethodComparisonTest::the comparison agrees with the single-method preview | COVERED |
| PRT-047 | Anteprima rifiuta input invalidi (qty/prezzo non validi, lista vuota) | InvestmentLots.kt:previewSellPnl | InvestmentLotsTest::previewSellPnl rejects invalid inputs, ::lotsSortedForDisposal empty input returns empty for all methods | COVERED |
| PRT-048 | Confronto FIFO/HIFO/Specific-ID affiancato con lotti consumati e P&L fiat+BTC | K/screens/LotMethodComparison.kt:compareLotMethods; SellDialog.kt:LotMethodComparisonView | LotMethodComparisonTest (tutti); SellDialogTest::comparison shows the three methods and picking HIFO books its lots | COVERED |
| PRT-049 | Scegliere un metodo diverso da quello configurato → selezione esplicita inviata, metodo dell'investimento invariato | SellDialog.kt:sellSelectionFor; R:record_sell_with_lots | LotMethodComparisonTest::keeping the configured FIFO or HIFO sends no explicit selection, ::overriding the configured method sends that method's lots; R::lot_selection_overrides_the_method_of_a_fifo_investment; SellDialogTest::FIFO keeps the quantity field and passes no selection | COVERED |
| PRT-050 | P&L BTC dell'anteprima al tasso storico della data di vendita ("no rate" se manca) | SellDialog.kt:historicalBtcRate (saleBtcRateAt) | LotMethodComparisonTest::BTC P&L follows the backfill formula and needs both rates | **COVERED** (test in §F; prima: PARTIAL) |
| PRT-051 | Validazione dialog vendita: prezzo ≤0, fee non parsabili/negative bloccano il Save | SellDialog.kt:canSave/feesInvalid | — (SellDialogTest copre solo i pick) | **COVERED** (test in §F; prima: PARTIAL) |
| PRT-052 | Sotto-sezione "Sells" con P&L firmato fiat+BTC per vendita | InvestmentEditDialog.kt:InvestmentLotsExpansion | InvestmentsCardBitcoinLensTest::sell row shows dual signed pnl when btc backfilled | COVERED |
| PRT-053 | Migrazione tabelle sells/disposals idempotente | R (schema sells) | R::sells_migration_idempotent_across_opens | COVERED |

### P&L realizzato / non realizzato (fiat e Bitcoin Lens)

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|---|---|---|---|---|
| PRT-054 | P&L realizzato fiat = somma delle disposals (NaN saltati) | InvestmentLots.kt:realizedPnl | InvestmentLotsTest::realizedPnl empty disposals returns zero, ::realizedPnl sums gain_loss correctly, ::realizedPnl skips NaN gain_loss | COVERED |
| PRT-055 | P&L non realizzato fiat (prezzo corrente − costo con fee; null senza prezzo; lot a qty zero saltati) | InvestmentLots.kt:unrealizedPnl | InvestmentLotsTest::unrealizedPnl null when no current price, ::…not finite, ::…computes price minus cost times qty, ::…skips lots with zero quantity, ::unrealizedPnl includes purchase fees in the basis | COVERED |
| PRT-056 | P&L totale fiat (realizzato + non realizzato, o solo realizzato) | InvestmentLots.kt:totalPnl | InvestmentLotsTest::totalPnl is realized plus unrealized, ::totalPnl returns realized when unrealized is null | COVERED |
| PRT-057 | P&L realizzato in BTC (gain_loss_btc; null se una disposal non ha tasso) | InvestmentLots.kt:realizedPnlBtc | T/screens/InvestmentLotsBtcPnlTest.kt::realizedPnlBtc empty disposals returns zero, ::…sums all disposals, ::…with any null gainLossBtc returns null, ::…with non-finite gainLossBtc returns null | COVERED |
| PRT-058 | P&L non realizzato in BTC (tasso del lot vs tasso corrente) | InvestmentLots.kt:unrealizedPnlBtc | InvestmentLotsBtcPnlTest::unrealizedPnlBtc apple example matches storyboard, ::…null current rate…, ::…null lot btc rate…, ::…skips lots with non-positive quantity, ::…non-finite current price… | COVERED |
| PRT-059 | P&L totale in BTC | InvestmentLots.kt:totalPnlBtc | InvestmentLotsBtcPnlTest::totalPnlBtc with both null returns null, ::…one null returns the other, ::…both available sums them | COVERED |
| PRT-060 | Chip di avviso quando il segno fiat e BTC divergono | InvestmentsFormat.kt:isPnlDivergent; InvestmentsCard.kt:DualPnlBtcCell | InvestmentsCardBitcoinLensTest::divergent signs fiat positive btc negative renders warning chip, ::non-divergent both positive does NOT render the warning chip, ::null pnlBtc renders em-dash without chip | COVERED |
| PRT-061 | Totali investimenti (valore/costo fiat e BTC, ogni riga al tasso della propria valuta, costo BTC per lot al tasso d'acquisto, motivo se sconosciuto) | InvestmentsCard.kt:computeInvestmentsTotals; InvestmentsFormat.kt:lotsCostBtc | T/screens/InvestmentsTotalsTest.kt (tutti gli 8 test B2/BTC cost); PortfolioScenarioTest::after a partial sell… | COVERED |
| PRT-062 | Formattazione % P&L (↑/↓/=, "—") e quantità | InvestmentsFormat.kt:formatPnlPercent, formatQuantity | InvestmentsCardTest::formatPnlPercent * (4), ::formatQuantity * (3) | COVERED |
| PRT-063 | InvestmentsCard in Bitcoin Lens: 7 colonne, costo/valore su due righe BTC+fiat, legacy senza lot "—", totali impilati | InvestmentsCard.kt:InvestmentsCard | InvestmentsCardBitcoinLensTest::header row shows 7 columns…, ::data row dual-line cost…, ::data row dual-line value…, ::totals row stacked…, ::legacy investment without lots renders pnlBtc as em-dash, ::cost cell BTC line is the per-lot sum… | COVERED |
| PRT-064 | Colonne che spariscono su finestra stretta (investimenti, manual asset, breakdown) | K/ui table column hiding | T/screens/TableColumnHidingTest.kt::investments keep name, value and BTC P&L (+ when narrow/wide), ::manual assets keep name, value now and delta BTC (+ narrow/wide), ::net worth breakdown drops the share last | COVERED |

### Tassi BTC per evento (rate-at-time, backfill, healing)

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|---|---|---|---|---|
| PRT-065 | Lot retrodatato → tasso BTC storico alla data d'acquisto, nella valuta del lot | R:resolve_lot_btc_rate; K/prices/TrackerBtcRate.kt:resolveLot | BF::resolve_lot_btc_rate_writes_historical_rate_not_current, BF::resolve_lot_btc_rate_uses_own_currency_not_dashboard_currency, BF::resolve_lot_btc_rate_idempotent_on_second_call, BF::resolve_lot_btc_rate_returns_false_for_unknown_lot | COVERED |
| PRT-066 | Tasso non disponibile → colonna NULL / "no rate", il save non si blocca | R:resolve_* ; InvestmentsCard.kt:NoRateCell | BF::resolve_lot_btc_rate_leaves_null_when_rate_unavailable, BF::resolve_sell_btc_leaves_null_when_rate_unavailable; InvestmentsCardBitcoinLensTest::null pnlBtc renders em-dash without chip | COVERED |
| PRT-067 | Vendita → tasso alla data di vendita e gain_loss_btc delle disposals | R:resolve_sell_btc; TrackerBtcRate.kt:resolveSell | BF::resolve_sell_btc_writes_sale_rate_and_gain_loss_for_disposal, BF::gain_loss_btc_formula_matches_storyboard_example | COVERED |
| PRT-068 | Manual asset con data acquisto → purchase_btc_at_time (niente senza data; fallback valuta) | R:resolve_manual_asset_purchase_btc; TrackerBtcRate.kt:resolveManualAsset | BF::resolve_manual_asset_purchase_btc_writes_rate_for_explicit_purchase_unix, BF::…does_nothing_when_no_purchase_unix, BF::…falls_back_to_currency_when_purchase_currency_null | COVERED |
| PRT-069 | Ricalcolo one-shot dei tassi lot da Settings (sovrascrive valori errati, preserva su fallimento, messaggio N/M) | R:recompute_all_lot_btc_rates; TrackerBtcRate.kt:recomputeAllLots; K/screens/SettingsSystemSections.kt | BF::recompute_all_lot_btc_rates_overwrites_wrong_seeded_value, BF::recompute_preserves_existing_when_resolution_fails, BF::recompute_idempotent_on_two_calls | **PARTIAL (skip)** (test in §F; prima: PARTIAL) |
| PRT-070 | Backfill Phase 8.2 al primo unlock: Idle→Done, idempotente, Failed su eccezione, no-op senza registry, cancel | K/data/BackfillRunner.kt:runIfNeeded, cancel; DevicePinSetupScreen; RegistryStore | T/data/BackfillRunnerTest.kt (7 test) | COVERED |
| PRT-071 | Backfill Rust di lot, manual asset e disposals: popola, NULL+failed se irrecuperabile, fallback created_at, dedupe fetch, idempotente, migrazioni colonne | BF:run backfill; R:run_8_2_backfill | BF::backfill_lots_with_known_rate_populates_btc_at_purchase, ::backfill_lots_with_unrecoverable_rate_leaves_null_and_reports_failed, ::backfill_manual_asset_legacy_falls_back_to_created_at_unix, ::backfill_disposals_derives_gain_loss_btc_from_parent_snapshots, ::backfill_disposal_with_null_parent_btc_leaves_gain_loss_btc_null, ::backfill_idempotent_across_runs, ::backfill_dedupes_rate_fetches_for_same_date_currency_pair, ::backfill_migration_on_existing_db_without_new_columns_then_backfill, ::migration_adds_btc_at_purchase_column_idempotent, ::migration_adds_4_manual_asset_columns_idempotent | COVERED |
| PRT-072 | Healing dei tassi mancanti in TrackerCache: tasso dalla data d'acquisto, righe con tasso intatte, una lookup per giorno+valuta, timeout, cap per passata, persistenza | K/data/InvestmentRateHealing.kt:fillMissingBtcRates, gainLossBtc; K/data/TrackerCache.kt:healMissingRates | T/data/InvestmentRateHealingTest.kt (5 test B9); T/data/TrackerCacheRateHealingTest.kt::healing republishes found rates and persists the healed rows, ::a hung rate provider cannot stall the pass beyond the timeout, ::lookups per pass are capped | **PARTIAL (skip)** (test in §F; prima: PARTIAL) |

### Prezzo BTC: fetch, cache, storico, Tor

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|---|---|---|---|---|
| PRT-073 | Tasso storico rateAt: mezzanotte UTC, pre-genesis/futuro → None, valuta non supportata → errore, cache, tasso implausibile = miss, date non risolvibili senza rete | RH:rate_at, fresh_rate_at | RH::normalize_to_utc_midnight_noon_same_as_midnight, ::rate_at_normalizes_to_utc_midnight, ::rate_at_before_bitcoin_returns_none, ::rate_at_future_returns_none, ::rate_at_unsupported_currency_returns_error, ::rate_at_caches_after_first_fetch_via_seed, ::implausible_cached_rate_is_treated_as_a_miss, ::fresh_rate_at_rejects_unanswerable_dates_without_network, ::btc_fiat_rate_bounds | COVERED |
| PRT-074 | Range storico ordinato con buchi, backfill idempotente e deduplicato | RH:range, backfill | RH::range_returns_sorted_ascending, ::range_handles_gap_days, ::backfill_idempotent_across_runs, ::backfill_dedupes_identical_requests, ::migration_creates_table_idempotent | COVERED |
| PRT-075 | Sorgenti e limiti: limiter CoinGecko process-wide, skip CoinGecko oltre la finestra keyless, punto blockchain.info vicino al target, triangolazione EUR | RH (provider) | RH::rate_limiter_tokens_decrement, ::coingecko_limiter_is_process_wide, ::missing_days_older_than_the_keyless_window_skip_coingecko, ::blockchain_info_point_must_be_close_to_target, ::triangulated_eur_within_5pct_of_coingecko_eur_when_both_available (online), ::rate_at_known_dates_matches_expected_within_tolerance (online) | COVERED |
| PRT-076 | Tassi storici via Tor: SOCKS con DNS remoto, fail-closed se proxy giù, proxy non-SOCKS rifiutato | RH:with_proxy | RH::rate_fetch_goes_through_socks_with_remote_dns, ::rate_fetch_fails_closed_when_proxy_is_down, ::with_proxy_rejects_non_socks_proxy | COVERED |
| PRT-077 | Client Kotlin BtcRateHistoryClient: mapping errori tipizzati (valuta non supportata → IAE; rete/tutte le sorgenti → null), range | K/prices/BtcRateHistoryClient.kt:rateAt, range | T/prices/BtcRateHistoryClientTest.kt (4 test); T/prices/BtcRateHistoryClientErrorHardeningTest.kt (3 test) | COVERED |
| PRT-078 | Prezzo spot BTC: CoinGecko con fallback mempool.space, valuta NONE → null | PriceFetcher.kt:fetchBtcPrice, parseCoinGeckoBody, parseMempoolBody | PriceFetcherTest::parseCoinGeckoBody * (6), ::parseMempoolBody * (4); T/prices/PriceFetcherLiveTorTest.kt::mempool space fallback provider answers through real Tor (live, opt-in) | **COVERED** (test in §F; prima: PARTIAL) |
| PRT-079 | Scarto di prezzi spot implausibili (fuori range) | PriceFetcher.kt:saneSpotPrice | PriceFetcherLiveTorTest::fetchBtcPrice returns a plausible price through real Tor (live) | **COVERED** (test in §F; prima: PARTIAL) |
| PRT-080 | Prezzi via Tor: socks5h senza DNS locale, chunked, fail su non-2xx senza fallback diretto, body limitato, verifica hostname TLS, parsing host/porta | PriceFetcher.kt:torHttpGet, buildProxy, parseProxyHostPort, readHttpResponse | T/prices/PriceFetcherTorTest.kt (7 test); PriceFetcherTest::buildProxy * (8); PriceFetcherLiveTorTest::price SOCKS path really exits through Tor, ::dead proxy fails closed and never connects directly | COVERED |
| PRT-081 | Conversioni PriceCache satToFiat/fiatToSat (null a cache vuota, lineari, clamp negativi, inverso) | K/prices/PriceCache.kt:satToFiat, fiatToSat | PriceCacheTest::satToFiat * (5), ::fiatToSat * (4) | COVERED |
| PRT-082 | Avvio PriceCache idempotente e refresh serializzati | PriceCache.kt:start, refreshNow | PriceCacheTest::start is idempotent under concurrent invocation, ::refreshNow serializes concurrent calls via mutex | COVERED |
| PRT-083 | refreshNow: fallimento/nessun prezzo registrato in lastError, valuta NONE azzera il prezzo, lastFetchUnix aggiornato | PriceCache.kt:refreshNow | PriceCacheTest::seedForTest exposes lastFetchUnix | **COVERED** (test in §F; prima: UNCOVERED) |
| PRT-084 | Tassi per valuta (_currencyRates) con TTL: rateFor restituisce solo tassi freschi, i scaduti vengono rinfrescati | PriceCache.kt:rateFor, refreshExpiredCurrencyRates | usato via seedCurrencyRatesForTest in ManualAssetsCardBitcoinLensTest | **COVERED** (test in §F; prima: PARTIAL) |
| PRT-085 | Storico prezzi BTC per sparkline (market_chart CoinGecko, cache) | PriceCache.kt:priceHistory; PriceFetcher.kt:fetchBtcPriceHistory, parseCoinGeckoMarketChartBody | PriceFetcherTest::parseCoinGeckoMarketChartBody * (3) | **COVERED** (test in §F; prima: PARTIAL) |
| PRT-086 | Valute fiat supportate (EUR/USD/CHF, NONE) | K/prices/FiatCurrency.kt | T/prices/FiatCurrencyTest.kt (7 test) | COVERED |

### Manual assets

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|---|---|---|---|---|
| PRT-087 | Aggiungere un manual asset (nome, kind, valore, valuta, note, acquisto opzionale) | R:add_manual_asset; K/screens/ManualAssetsCard.kt:ManualAssetEditDialog; TrackingScreen.kt:onSave | R::manual_assets_add_then_list_round_trips_all_fields; PortfolioScenarioTest::net worth adds wallet BTC, investments and manual assets… | **COVERED** (test in §F; prima: PARTIAL) |
| PRT-088 | 8 kind accettati, kind sconosciuto rifiutato | R:validate_manual_asset_kind | R::manual_assets_accept_each_valid_kind, R::manual_assets_reject_unknown_kind | COVERED |
| PRT-089 | Validazione: valore non finito rifiutato; dialog blocca nome vuoto, valore ≤0, valore d'acquisto non parsabile | R:add/update_manual_asset; ManualAssetsCard.kt:canSave/purchaseValueInvalid | R::manual_assets_reject_non_finite_value | **COVERED** (test in §F; prima: PARTIAL) |
| PRT-090 | Multi-valuta (EUR/USD/CHF/GBP/JPY) convertita al tasso della propria valuta; senza tasso → "no rate", esclusa dai totali | K/screens/AssetPortfolioCard.kt:computeLiveManualAssetsSat; GoalBuckets.kt:manualAssetsTotalSat | GoalBucketsTest::MANUAL_ASSET bucket converts at the asset currency, ::manualAssetsTotalSat returns zero when price not cached; ManualAssetsCardBitcoinLensTest::delta BTC renders explicit no-rate for cross-currency asset, ::…percentage for cross-currency asset when rate is cached | **COVERED** (test in §F; prima: PARTIAL) |
| PRT-091 | Modificare un manual asset (note preservate) / id inesistente | R:update_manual_asset | R::manual_assets_update_persists_field_changes, R::manual_assets_update_returns_false_for_missing_id | COVERED |
| PRT-092 | Eliminare un manual asset; il goal collegato mostra "Asset (missing)" | R:delete_manual_asset; GoalBuckets.kt:goalBucketBadgeLabel | R::manual_assets_delete_returns_true_then_false; GoalBucketsTest::manual_asset badge label is missing marker when asset is gone | COVERED |
| PRT-093 | Δ BTC% tra acquisto e oggi (null se mancano dati, cross-currency, segno segue il tasso) | K/screens/ManualAssetsBtcDelta.kt:computeManualAssetDeltaBtcPct | T/screens/ManualAssetsBtcDeltaTest.kt (7 test) | COVERED |
| PRT-094 | ManualAssetsCard Bitcoin Lens: 5 colonne, VALUE NOW / AT BUY su due righe con anno, stato vuoto | ManualAssetsCard.kt:ManualAssetsCard | ManualAssetsCardBitcoinLensTest (8 test) | COVERED |
| PRT-095 | Raggruppamento per kind (kind a zero esclusi) e righe per kind nella breakdown NW | GoalBuckets.kt:manualAssetsByKindSat; K/screens/NetWorthBreakdownTable.kt | GoalBucketsTest::manualAssetsByKindSat groups by kind and converts each, ::…drops kinds with zero sat; TableColumnHidingTest::net worth breakdown * | **COVERED** (test in §F; prima: PARTIAL) |

### Passività

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|---|---|---|---|---|
| PRT-096 | Aggiungere una passività (nome, importo in sat, kind, etichetta) | R:add_liability; K/screens/NetWorthCard.kt:LiabilityEditDialog; TrackingScreen.kt:onSave | R::liabilities_add_and_list_round_trips_each_kind; PortfolioScenarioTest::net worth adds … and subtracts liabilities | **COVERED** (test in §F; prima: PARTIAL) |
| PRT-097 | Modificare una passività (cambio kind) / id inesistente | R:update_liability | R::liabilities_update_persists_kind_change, R::liabilities_update_returns_false_for_missing_id | COVERED |
| PRT-098 | Eliminare una passività | R:delete_liability | R::liabilities_delete_returns_true_then_false | COVERED |
| PRT-099 | Importo > 21M BTC rifiutato; dialog blocca importo non intero/negativo/vuoto | R:add_liability (cap); NetWorthCard.kt:canSave | R::sat_amounts_above_21m_btc_are_rejected | **COVERED** (test in §F; prima: PARTIAL) |
| PRT-100 | Kind passività (mortgage/loan/credit-card/other) con badge e sottotitolo; kind arbitrario non validato lato registry | NetWorthCard.kt:liabilitySubtitle; AssetPortfolioCard.kt (badge) | — | **COVERED** (test in §F; prima: UNCOVERED) |

### Net worth (aggregato, hero, storico, stato patrimoniale)

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|---|---|---|---|---|
| PRT-101 | NW = wallet + investimenti + manual asset − passività; può essere negativo | K/screens/TrackerNetWorth.kt:computeTrackerNetWorth | T/screens/TrackerNetWorthTest.kt::B1 net worth subtracts liabilities from assets, ::B1 net worth can go negative; PortfolioScenarioTest::net worth adds wallet BTC, investments and manual assets and subtracts liabilities | COVERED |
| PRT-102 | Ricalcolo live con tasso live (anche se più basso); aggregati in cache solo senza tasso | TrackerNetWorth.kt:computeTrackerNetWorth | TrackerNetWorthTest::live recompute is used whenever a live rate exists, even when lower, ::cached aggregates are used only without a live rate | COVERED |
| PRT-103 | Wallet tracciati: stessa xpub contata una volta, address-list mai fusi, esclusi fuori dal totale | TrackerNetWorth.kt:trackedWalletGroups, trackedWalletSat | TrackerNetWorthTest::B8 two registry rows on the same account xpub count as ONE…, ::B8 address-list wallets never merge…, ::B8 excluded wallets drop out… | COVERED |
| PRT-104 | Vendere tutto azzera il bucket investimenti (proventi non registrati automaticamente) | computeTrackerNetWorth + R:record_sell | PortfolioScenarioTest::net worth adds … (seconda parte) | COVERED |
| PRT-105 | Valore investimenti in sat: fetched > manuale, qty dai lot, non finiti saltati, 0 senza prezzo, ogni riga alla sua valuta | GoalBuckets.kt:investmentsTotalSat, fiatToSatInCurrency | GoalBucketsTest::investmentsTotalSat * (6), ::investmentsTotalSat values lot-managed positions on lot quantity, ::rateFor converts each row at its own currency, ::rows without a rate are left out | COVERED |
| PRT-106 | Valore live investimenti nel NW con fallback al cost basis quando manca un prezzo e policy "no rate" (mai tasso d'acquisto) | AssetPortfolioCard.kt:computeLiveInvestmentsSat | indiretto via PortfolioScenarioTest (solo EUR con prezzo manuale) | **COVERED** (test in §F; prima: PARTIAL) |
| PRT-107 | Hero NW: BTC primario + fiat secondario, il tap non cambia unità | K/screens/NetWorthHero.kt:NetWorthHero | T/screens/NetWorthHeroBitcoinLensTest.kt::hero renders primary BTC line and secondary fiat line, ::tap on the hero does NOT cycle units | COVERED |
| PRT-108 | Fiat dell'hero = NW × spot; niente senza tasso; negativo se NW negativo; coda fiat nascosta senza storico | NetWorthHero.kt:heroFiat | T/screens/TrackerHomeAggregationsTest.kt::B5 * (5) | COVERED |
| PRT-109 | Privacy mode sull'hero: bullets che mantengono simbolo e separatori, "Hold to reveal", delta nascosto | NetWorthHero.kt:bulletsHeroFor | T/screens/NetWorthHeroBulletsTest.kt (5 test); NetWorthHeroBitcoinLensTest::privacy mode on shows Hold to reveal hint, ::delta row hidden when privacy mode is on…; T/screens/PrivacyModeTest.kt::privacy mode hides every amount in representative composables | COVERED |
| PRT-110 | Delta BTC del periodo dal primo/ultimo snapshot ricostruito | NetWorthHero.kt:computeNwBtcDelta | T/screens/NetWorthHeroNwHistoryTest.kt::delta BTC primary computed from first and last snapshot… | COVERED |
| PRT-111 | Sparkline NW da storico ricostruito, fallback linea piatta, overlay on/off | NetWorthHero.kt | NetWorthHeroNwHistoryTest::sparkline primary uses reconstructed sat history…, ::falls back to flat line…; NetWorthHeroBitcoinLensTest::sparkline renders when overlay toggle is on/off | COVERED |
| PRT-112 | Chip "EUR vs BTC" (fiat su/giù/piatto con soglia 0,5%, nascosto con storico vuoto o a un punto) | K/screens/FiatVsBtcChange.kt:computeFiatVsBtcChange | T/screens/FiatVsBtcChangeTest.kt (8 test); NetWorthHeroBitcoinLensTest::chip * (4) | COVERED |
| PRT-113 | Serie fiat del NW (NW di oggi ai tassi storici) e scrub del grafico | NetWorthHero.kt:netWorthFiatSeries, scrubFiatAt | TrackerHomeAggregationsTest::net worth fiat series values today's NW at each historical rate, ::…feeds the fiat vs BTC chip consistently, ::empty price history yields empty series, ::scrubbing the sat line reports the snapshot's fiat value…, ::scrubbing falls back to the nearest fiat series point | COVERED |
| PRT-114 | Selettore periodo globale (7g/30g/90g/All) che guida hero, delta e foglio patrimoniale | K/screens/TrackingKpi.kt:PeriodSelector; NetWorthHero.kt:periodChip | PortfolioSheetTest::all-time period has no change values (solo modello) | **PARTIAL (skip)** (test in §F; prima: PARTIAL) |
| PRT-115 | Ricostruzione storica NW (Rust): DB vuoto, step on-chain, lot/asset futuri esclusi, lot con tasso, passività costanti, UTC, range invalido, tassi parziali → zero, un prefetch per valuta, clamp finestra, saturazione | NWH:reconstruct | NWH (tutti i 13 test) | COVERED |
| PRT-116 | NetWorthHistoryClient: cache per range+valuta con TTL, errore di rete → lista vuota, finestra allineata al giorno, richieste concorrenti deduplicate, cache limitata | K/prices/NetWorthHistoryClient.kt:netWorthHistory | T/prices/NetWorthHistoryClientTest.kt (7 test) | COVERED |
| PRT-117 | Stato patrimoniale: sezioni wallet/investimenti/manual/passività, subtotali e NW da TrackerNetWorth, righe che sommano | K/screens/PortfolioSheet.kt:buildPortfolioSheet | T/screens/PortfolioSheetTest.kt::subtotals and net worth come from TrackerNetWorth, ::rows add up to the live subtotals, ::section and net worth change are the sums of the rows, ::no-rate rows have no value | COVERED |
| PRT-118 | % del NW firmata, "—" con NW ≤ 0 | PortfolioSheet.kt:buildPortfolioSheet (pct) | PortfolioSheetTest::percent of net worth is signed…, ::no percent of a non-positive net worth | COVERED |
| PRT-119 | Variazione per riga sul periodo: flussi wallet dedup per txid, investimenti al tasso BTC d'inizio periodo, lot/asset nuovi da zero, passività statiche, tasso mancante o stale → "—" | PortfolioSheet.kt:walletFlowSince, investmentValueSatAt, sheetStartDay, SheetStartRates | PortfolioSheetTest::wallet change is the signed flow inside the window…, ::investment change uses today's fiat value at the start-of-period BTC rate, ::lots bought inside the window count from zero, ::manual asset bought inside the window changes by its whole value, ::liabilities are static…, ::missing start rate leaves the change unknown…, ::start rates answer only for the day…, ::stale rates from the previous period…, ::start day is the UTC midnight… | COVERED |
| PRT-120 | Colonne del foglio responsive (Updated → Type → %NW → Change; Nome e Valore sempre) | PortfolioSheet.kt:visibleSheetColumns | PortfolioSheetTest::wide sheets show every column, ::a 1024 px window keeps the essentials, ::columns give way in priority order | COVERED |
| PRT-121 | AssetPortfolioCard: menu Add (investimento/manual/passività), righe che aprono l'edit, badge per tipo, "Updated N days", pulsante fetch prezzi | K/screens/AssetPortfolioCard.kt:AssetPortfolioCard | — | **COVERED** (test in §F; prima: UNCOVERED) |
| PRT-122 | Donut / tabella di breakdown NW (mascherati in privacy) | K/screens (NwBreakdownDonut), NetWorthBreakdownTable.kt | PrivacyModeTest::privacy mode hides every amount… (donut); TableColumnHidingTest::net worth breakdown * | RIMOSSO (redesign Home 2026-10: una sola striscia KPI e la card Composizione) |

### Savings goals

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|---|---|---|---|---|
| PRT-123 | Creare un goal (nome, target sat assoluto o % NW, bucket, deadline opzionale) | R:add_savings_goal; K/screens/SavingsGoalEditDialog.kt:GoalEditDialog; TrackingScreen.kt:onSave | R::savings_add_then_list_round_trips_all_fields, R::savings_add_accepts_null_deadline_and_wallet; SavingsGoalsCardDenominationTest (apre il dialog) | **COVERED** (test in §F; prima: PARTIAL) |
| PRT-124 | Modificare (anche togliere la % target) ed eliminare un goal | R:update_savings_goal, delete_savings_goal | R::savings_update_mutates_existing_row, R::savings_update_returns_false_for_missing_id, R::savings_update_can_clear_pct_back_to_none, R::savings_delete_returns_true_then_false | COVERED |
| PRT-125 | Bucket semantici (manual/investments/bitcoin_cash/wallet/manual_asset) con current_sat auto-calcolato; bucket sconosciuto rifiutato; cambio bucket | K/domain/GoalBuckets.kt:computeGoalCurrentSat, GoalBucket.fromWire; R:validate_bucket_kind | GoalBucketsTest (bucket *, fromWire *, bitcoin_cash bucket does NOT include investments…); R::savings_add_with_investments_bucket_persists_kind, ::savings_add_with_wallet_bucket_persists_ref, ::savings_add_with_manual_asset_bucket_persists, ::savings_add_rejects_unknown_bucket_kind, ::savings_update_can_switch_bucket_kind | COVERED |
| PRT-126 | Badge del bucket (nome wallet/asset, marcatore "missing") | GoalBuckets.kt:goalBucketBadgeLabel, rememberGoalBucketBadgeLabels | GoalBucketsTest::badge label * (4), ::manual_asset badge label * (2) | COVERED |
| PRT-127 | Target % NW dinamico (targetSatFromPct/pctFromTargetSat, base = NW − passività con floor 0) | K/screens/SavingsGoalsMath.kt:targetSatFromPct, pctFromTargetSat | T/screens/SavingsGoalsTargetMathTest.kt (10 test target/pct); TrackerNetWorthTest::percent-of-net-worth goal base subtracts liabilities and floors at zero | COVERED |
| PRT-128 | Drift chip allocazione ±5pp | SavingsGoalsMath.kt:goalAllocationDriftPp; SavingsGoalsCard.kt | SavingsGoalsTargetMathTest::drift * (7) | COVERED |
| PRT-129 | Sinking fund: deadline obbligatoria (registry + dialog), giorni rimanenti, rata mensile suggerita | R:validate_sinking_has_deadline; K/screens/SinkingGoal.kt:sinkingDaysRemaining, sinkingMonthlyRateSat | R::savings_add_sinking_requires_deadline, R::savings_update_to_sinking_requires_deadline; T/screens/SinkingGoalTest.kt::daysRemaining * (4), ::monthlyRate * (5) | **COVERED** (test in §F; prima: PARTIAL) |
| PRT-130 | Chip ON TRACK / AHEAD / BEHIND / OVERDUE (tolleranza ±5%, completato oltre deadline = AHEAD) | SinkingGoal.kt:sinkingScheduleStatus | SinkingGoalTest::scheduleStatus * (8), ::completed goal past its deadline is AHEAD not OVERDUE, ::effective target overrides the persisted one; SavingsGoalsCardBitcoinLensTest::on track chip still appears… | COVERED |
| PRT-131 | Tipo goal savings/sinking: sconosciuto rifiutato, savings senza deadline ok | R:validate_goal_kind; SinkingGoal.kt:GoalKind.fromWire | R::savings_reject_unknown_goal_kind, R::savings_add_savings_kind_works_without_deadline; SinkingGoalTest::fromWire * (2) | COVERED |
| PRT-132 | Denominazione sat/fiat: picker, round-trip, target via PriceCache, "—" senza tasso, progresso dal target effettivo, importo fiat validato | R:set_savings_goal_denomination; SavingsGoalsMath.kt:effectiveTargetSat, GoalDenomination.fromWire | T/screens/SavingsGoalsCardDenominationTest.kt (10 test); R::set_savings_goal_denomination_sat_clears_fiat_fields, ::…fiat_persists_amount_and_currency, ::…rejects_unknown_value, ::list_savings_goals_returns_new_fields, ::savings_goal_fiat_amount_is_validated | COVERED |
| PRT-133 | Goal in Bitcoin Lens: current/target in BTC, % progresso invariata | K/screens/SavingsGoalsCard.kt:SavingsGoalsCard | T/screens/SavingsGoalsCardBitcoinLensTest.kt (4 test) | COVERED |
| PRT-134 | Migrazioni goal (goal_kind, colonne denominazione) idempotenti | R (schema goals) | R::savings_migration_adds_goal_kind_column_idempotent, R::migration_adds_3_goal_denomination_columns_idempotent, R::savings_migration_is_idempotent_across_opens | COVERED |
| PRT-135 | Target sat > 21M BTC rifiutato | R:add_savings_goal (cap) | R::sat_amounts_above_21m_btc_are_rejected | COVERED |

### Opportunity cost (vs HODL)

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|---|---|---|---|---|
| PRT-136 | Costo opportunità in BTC su lot e manual asset (formula, esclusioni senza tasso/qty/acquisto, contatori, totale negativo se la diversificazione batte BTC) | K/domain/OpportunityCost.kt:computeOpportunityCostBtc | T/screens/OpportunityCostTest.kt (11 test) | **COVERED (logica testata; card rimontate nella Home del tracker il 2026-10-24)** |
| PRT-137 | KPI OPP_COST nella strip: valore BTC, segno, unità scelta, "—" se null | K/screens/KpiStripV7.kt:KpiStripV7, formatOppCost | T/screens/KpiStripV7OppCostTest.kt (5 test); T/screens/KpiStripV7BitcoinLensTest.kt::five KPI cards rendered including OPP_COST, ::OPP_COST shows em-dash placeholder… | **COVERED (logica testata; card rimontate nella Home del tracker il 2026-10-24)** |

### Costo medio BTC

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|---|---|---|---|---|
| PRT-138 | Entrate come acquisti al prezzo del giorno UTC, media ponderata | K/domain/BtcCostBasis.kt:computeBtcCostBasis, utcDay, costBasisDays | T/screens/BtcCostBasisTest.kt::inflows average at the market price of their day, ::days are UTC midnights of confirmed txs; PortfolioScenarioTest::BTC bought, partly spent and sold flows into average cost and net worth | COVERED |
| PRT-139 | Uscite come cessioni al costo medio con realizzato | BtcCostBasis.kt:computeBtcCostBasis | BtcCostBasisTest::an outflow is a disposal at average cost and keeps the average; PortfolioScenarioTest::BTC bought, partly spent and sold… | COVERED |
| PRT-140 | Prezzo personalizzato ed esclusione per transazione nel calcolo; prezzo in altra valuta ignorato | BtcCostBasis.kt:computeBtcCostBasis | BtcCostBasisTest::user price replaces the market price and exclusion drops the tx, ::a user price in another currency is ignored | COVERED |
| PRT-141 | Salvare/azzerare prezzo ed esclusione dal dialog della transazione (registry v5) | R:set_event_cost_basis, list_event_cost_basis; TrackingScreen.kt:CategoryEditDialog.onCostBasisSave (CostBasisEditState) | R::event_cost_basis_round_trip_and_reset, R::event_cost_basis_rejects_bad_input, R::removing_a_wallet_drops_its_cost_basis_rows | **PARTIAL (skip)** (test in §F; prima: PARTIAL) |
| PRT-142 | Lati della stessa txid nettati (resto verso proprio wallet non è cessione); send_to_self esclusi | BtcCostBasis.kt:computeBtcCostBasis | BtcCostBasisTest::legs of one txid are netted…, ::send_to_self transfers are excluded automatically | COVERED |
| PRT-143 | Uscite oltre lo stack conteggiato segnalate senza valori negativi | BtcCostBasis.kt:computeBtcCostBasis | BtcCostBasisTest::outflows beyond the counted stack are reported, not costed | COVERED |
| PRT-144 | Prezzi mancanti saltano le entrate, pending in attesa, righe dalla più recente; senza prezzo corrente il non realizzato è vuoto | BtcCostBasis.kt:computeBtcCostBasis | BtcCostBasisTest::missing prices skip inflows, pending txs wait, rows are newest first, ::no current price leaves the unrealized figures empty | COVERED |
| PRT-145 | Solo wallet mainnet (test network solo con dev switch) | K/screens/BtcCostBasisCard.kt:costBasisWalletIds | BtcCostBasisTest::only mainnet wallets count unless the dev switch includes test networks | COVERED |
| PRT-146 | Caricamento dei prezzi storici per i giorni necessari (cache, clear al cambio) | K/data/BtcCostBasisRates.kt:ensure, clear | — | **COVERED** (test in §F; prima: UNCOVERED) |
| PRT-147 | Scheda Costo medio BTC: metriche, nota metodo, elenco transazioni con stati, messaggio senza valuta fiat, messaggio solo signet | BtcCostBasisCard.kt:BtcCostBasisCard | T/ui/money/SignedAmountScanTest.kt (solo scansione statica privacy) | **COVERED** (test in §F; prima: UNCOVERED) |

### Conversione tra valute

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti (file::nome test) | Stato |
|---|---|---|---|---|
| PRT-148 | Conversione fiat→sat nella valuta propria di ogni riga (rateFor), righe senza tasso escluse | GoalBuckets.kt:fiatToSatInCurrency; PriceCache.kt:rateFor | GoalBucketsTest::rateFor converts each row at its own currency, ::rows without a rate are left out, ::MANUAL_ASSET bucket converts at the asset currency | COVERED |
| PRT-149 | Totali investimenti cross-currency: valore BTC sconosciuto con motivo se manca un tasso corrente | InvestmentsCard.kt:computeInvestmentsTotals | InvestmentsTotalsTest::B2 a missing current rate makes the BTC value unknown and says why, ::B2 BTC value total covers every row at its own currency rate | COVERED |
| PRT-150 | Riga BTC di investimenti/manual asset esteri tramite i tassi per valuta di PriceCache (AAPL/USD su dashboard EUR) | AssetPortfolioCard.kt:resolveRate; ManualAssetsCard/InvestmentsCard | ManualAssetsCardBitcoinLensTest::delta BTC renders percentage for cross-currency asset when rate is cached | **PARTIAL + BUG** (test in §F; prima: PARTIAL) |
| PRT-151 | Vista Schede del Portafoglio: quattro card di classe (valore, peso, variazione nel periodo, andamento, prime tre voci), elenco della classe e card della voce scelta | K/screens/PortfolioClassView.kt:PortfolioClassView | K/screens/PortfolioClassViewTest::the first class with items is open…, ::render the portfolio cards… | COVERED |
| PRT-152 | Card di dettaglio per categoria (19 categorie: wallet, azione, ETF, fondo, valuta, materia prima, liquidità, conto titoli, immobile, veicolo, collezione, partecipazione, prestito a terzi, mutuo, prestito, carta…) con numeri derivati (costo TER, interesse, LTV ed equity, rimborsato, utilizzo plafond, valore stimato…) | K/screens/PortfolioDetails.kt:investmentCardModel/manualAssetCardModel/liabilityCardModel/walletCardModel | K/screens/PortfolioDetailsTest (16 test); K/screens/PortfolioClassViewTest::an ETF card…, ::a mortgage card… | COVERED |
| PRT-153 | Dettagli facoltativi per tipo (registry v6 `details_json`): editor, validazione, collegamento debito → bene | R:set_details/list_details/validate_details_json; U/lib.rs:set_portfolio_details/list_portfolio_details; K/screens/PortfolioClassView.kt:PortfolioDetailsDialog | R::details_round_trip_on_every_portfolio_table, R::details_reject_unknown_tables_and_malformed_objects; U/uc_pin_recovery_tests::portfolio_details_survive_a_reopen_and_reject_bad_objects; K::the details editor saves typed values…, ::Details opens the editor… | COVERED |
| PRT-154 | Interruttore Schede ⇄ Tabella (la Tabella mantiene bilancio, costo medio BTC, investimenti e asset manuali) | K/screens/AssetPortfolioCard.kt:PortfolioViewToggle; TrackingDashboard.kt (PORTFOLIO) | K::the Cards and Table toggle switches the portfolio view | COVERED |

### Riepilogo

| Stato | Conteggio |
|---|---|
| COVERED | 107 |
| PARTIAL | 33 |
| UNCOVERED | 10 |
| Totale | 150 |

<a id="e"></a>

## E. Revisione: casi d'uso mancanti, classificazioni corrette, osservazioni


Seconda passata trasversale su `docs/use-cases.md` (616 righe SEC/WAL/TRK/PRT), branch `refactor/watch-only-wallets`, working tree al 2026-10-24.

Metodo:
- confronto di ogni `pub fn` esportata da `crates/bhodl-uniffi/src/commonMain/rust/lib.rs` con le righe dell'inventario;
- conteggio delle citazioni nell'inventario per ogni file `jvmMain`/`shared`/`apps/android`/`apps/ios` e per ogni file di test;
- verifica meccanica che ogni nome di test citato esista nei sorgenti: nessun test inventato, salvo citazioni abbreviate con `…` o `*`;
- lettura mirata di 32 righe COVERED.

Percorsi abbreviati: `D/` = `apps/desktop/src/jvmMain/kotlin/io/bhodl/desktop/`, `T/` = `apps/desktop/src/jvmTest/kotlin/io/bhodl/desktop/`, `O/` = `crates/bhodl-wallet-onchain/src/`, `U/` = `crates/bhodl-uniffi/src/commonMain/rust/`, `C/` = `crates/bhodl-core/src/`.

### Casi d'uso mancanti

| ID | Caso d'uso (italiano, una riga) | Codice (file:funzione) | Test esistenti | Stato |
|----|------|------|------|------|
| GAP-001 | Formattazione fiat in base alla lingua: in EN il simbolo va prima del numero, in IT dopo (it_IT); arrotondamento half-up; niente "-0,00"; CHF e i codici ISO non diventano mai un simbolo; il segno precede il prefisso | D/ui/AmountFormat.kt:formatFiat, formatFiatWithCurrency | T/ui/AmountFormatTest (22 test, mai citato nell'inventario) | COVERED |
| GAP-002 | Formatter bitcoin: sats con il separatore della lingua, BIP177 (prefisso ₿ solo sui sats), BTC sempre a 8 decimali, Auto (<1 BTC in sats, ≥1 BTC in BTC, sul valore assoluto), unità scelta una volta per colonna senza mescolare sats e BTC, forma compatta per i grafici, celle "bare" senza unità | D/ui/money/BitcoinAmountFormat.kt, D/ui/money/UnitFormat.kt | T/ui/money/BitcoinAmountFormatTest (16), T/ui/money/UnitFormatTest (5) | COVERED |
| GAP-003 | Parsing degli importi digitati in base alla lingua ("350.000" in it_IT contro en_US), separatori ambigui o ripetuti, input malformato rifiutato, filtro dei caratteri, round-trip parse↔format | D/ui/money/DecimalInput.kt:parseDecimalInput, formatDecimalInput, filterDecimalChars | T/ui/money/DecimalInputTest (9) | COVERED |
| GAP-004 | Componente Money: densità Hero/Medium/Compact, BTC primario con fiat secondario, MoneyPrimary.Fiat che inverte l'ordine, "no rate" esplicito sul costo, segno P&L, rispetto di unità e BIP177; MoneyValue.fromFiat con tasso 0 → sat null | D/ui/money/MoneyValue.kt, MoneyDensity.kt, MoneyPrimary.kt, MoneySemantic.kt | T/ui/money/MoneyRenderTest (9), MoneyValueTest (4) | COVERED |
| GAP-005 | Tempo relativo ("just now", Ns/Nm/Nh/Nd ago, i timestamp futuri restano "just now") e soglia stale di 10 minuti per i chip di prezzo e di sync | D/ui/RelativeTime.kt:relativeTimeAgo, minutesAgo, isStale | T/ui/RelativeTimeTest (11) | COVERED |
| GAP-006 | Sparkline: lo scrub sceglie il punto più vicino (proporzionale al tempo, limitato ai bordi); overlay tratteggiato/punteggiato/continuo; un overlay con troppo pochi punti viene ignorato | D/ui/Sparkline.kt:nearestPoint, Sparkline | T/ui/SparklineTest (5), SparklineOverlayTest (7) | COVERED |
| GAP-007 | Tema Neon su tutte le card: `bhodlCard` passa da flat (bordo 1dp, raggio 8dp, accento ignorato) a neon (raggio 16dp, gradiente, glow da `neonBorder`) | D/ui/BhodlCard.kt:bhodlCard, D/ui/NeonBorder.kt | T/ui/ThemeTokensTest (solo contrasto/superfici della palette neon) | PARTIAL: il rendering della card nei due temi non è testato. Compose test con `SettingsStore.setThemeMode(Neon)` / `Dark` su un Box `.bhodlCard(accent)` e asserzione su shape/bordo (o screenshot test). |
| GAP-008 | Schermata Modules dedicata, aperta dall'header del tracker ("Modules" → `Route.Modules`): 5 interruttori (Investments, Budget & Bills, Goals, Manual assets, Analytics Labs) e Back | D/screens/ModulesScreen.kt:ModulesScreen, D/App.kt (composable<Route.Modules>), D/screens/TrackingScreen.kt (HEADER_MODULES) | — | UNCOVERED: Compose test: tap su ogni switch → `SettingsStore.module*` cambia; Back → onBack. SEC-094 copre solo la sezione dentro Settings. |
| GAP-009 | Contenitori condivisi di tutti i dialog di modifica: `SlideOverPanel` (apertura/chiusura, scrim, Esc) e `DatePickerField` (data mostrata e scelta in UTC, campo vuoto = null) | D/ui/SlideOverPanel.kt, D/ui/DatePickerField.kt | — | UNCOVERED: unit test sulla conversione epoch↔LocalDate UTC del DatePickerField (fuso positivo e negativo); Compose test: Esc/scrim chiudono il pannello. |
| GAP-010 | Grafico entrate/uscite mensili della HOME del tracker (`InflowOutflowChart`): aggregazione per mese di calendario | D/screens/CoreCharts.kt:computeMonthlyFlows, InflowOutflowChart; D/screens/TrackingDashboard.kt (HOME) | — | **COVERED** (test in §F; prima: UNCOVERED:) |
| GAP-011 | Messaggi d'errore generati a runtime e mostrati in UI non localizzati: "Registry not open.", "Sync failed for N wallet(s) — showing cached data…", "Failed to refresh tracker." (TrackerCache), `PriceCache.lastError` | D/data/TrackerCache.kt:refresh (SYNC_ERROR_PREFIX), D/prices/PriceCache.kt, D/screens/TrackingScreen.kt (displayError), D/screens/SettingsAppearanceSections.kt (riga ~563) | — (HardcodedUiStringScanTest intercetta solo i letterali passati a Text/label) | **COVERED (UcRuntimeStringsSecurityTest, errori del tracker tradotti)** |
| GAP-012 | L'errore di PriceCache viene mostrato in Settings così com'è (`e.message`, può contenere host/URL), senza `userMessage`/`sanitizeErrorDetail` | D/screens/SettingsAppearanceSections.kt:FiatCurrencySection, D/prices/PriceCache.kt (refresh onFailure) | — | **COVERED (fix)** (test in §F; prima: UNCOVERED:) |
| GAP-013 | Host mobili: Android mostra un placeholder con testo inglese hardcoded (fuori dalla scansione i18n); iOS è uno scaffold non compilato | apps/android/.../MainActivity.kt:PlaceholderScreen, apps/ios/BHODL/App.swift | — | **COVERED (UcRuntimeStringsSecurityTest, placeholder Android tradotto)** |
| GAP-014 | Il proxy Tor arriva a ogni chiamata di rete: storico tassi (rateAt/range/backfill), storico NW, resolver dei tassi di lot/vendite/manual asset, ricalcolo, backfill 8.2, sync di tutti i wallet nel tracker, sync automatica dopo AddWatch e della History stand-alone | D/prices/BtcRateHistoryClient.kt, D/prices/NetWorthHistoryClient.kt:FfiNwHistorySource, D/prices/TrackerBtcRate.kt, D/data/BackfillRunner.kt (proxyProvider), D/data/TrackerCache.kt:refresh, D/screens/AddWatchScreen.kt, D/screens/HistoryScreen.kt | Solo T/screens/DashboardScreenTest (SEC-112) per la sync del workspace; i test dei client usano source finte che ignorano il proxy | **COVERED** (test in §F; prima: PARTIAL:) |
| GAP-015 | Il blocco e il wipe non svuotano le cache in memoria di NetWorthHistoryClient (fino a 6 h, chiave senza identità del registry) né di PriceCache (storico prezzi, tassi per valuta): dopo lock → wipe → nuovo registry lo storico NW del vecchio registry può riapparire, a parità di chiave | D/wizard/RegistryStore.kt:clear, D/data/TrackerCache.kt:reset, D/prices/NetWorthHistoryClient.kt (cache) | — (TRK-012 copre solo TrackerCache + BtcCostBasisRates) | **PARTIAL (fix pronto)** (test in §F; prima: UNCOVERED:) |
| GAP-016 | Lo storico NW non si aggiorna dopo aver aggiunto o modificato investimenti, manual asset o passività: l'effetto è legato solo a periodo, eventi e valuta, e la chiave di cache non include `dataVersion` | D/screens/TrackerHomeScreen.kt (LaunchedEffect(period, events, fiatCurrency)), D/prices/NetWorthHistoryClient.kt:CacheKey | — | **PARTIAL (fix pronto)** (test in §F; prima: UNCOVERED:) |
| GAP-017 | Lo storico NW conta anche gli eventi dei wallet esclusi dai totali (`TrackerCache.events` non filtrato), mentre l'hero e il conteggio li escludono (TRK-025): la sparkline e il delta non tornano col valore dell'hero | D/screens/TrackerHomeScreen.kt:buildPaymentEventLite(events) | — | **PARTIAL (fix pronto)** (test in §F; prima: UNCOVERED:) |
| GAP-018 | Limiti della ricostruzione dello storico NW: investimenti legacy senza lot assenti; vendite non ripercorse (la quantità residua del lot vale per tutto lo storico, quindi un lot venduto ieri sparisce anche dai giorni precedenti); prezzo corrente costante; eventi in mempool esclusi | O/net_worth_history.rs:investments_value_btc_at, reconstruct_net_worth_history | NWH (13 test): nessuno con vendite o investimenti senza lot | **COVERED (fix)** (test in §F; prima: UNCOVERED:) |
| GAP-019 | Race tra lock/wipe e refresh: un `refresh()` in corso (sync via Tor) pubblica lo snapshot DOPO `TrackerCache.reset()`. I dati della sessione chiusa tornano in memoria, `hasLoadedOnce = true` e il `coldLoad()` della sessione successiva viene saltato | D/data/TrackerCache.kt:refresh, reset, coldLoad; D/wizard/RegistryStore.kt:clear | — | **COVERED** (test in §F; prima: UNCOVERED:) |
| GAP-020 | Due handle sullo stesso file watch-state: wallet aperto nel workspace (WalletStore) e refresh del tracker o sync dopo AddWatch che fa `openWatch` della stessa riga. Valgono busy_timeout 5 s e il journal rollback; l'handle del workspace non vede la sync dell'altro finché non viene riaperto | O/common.rs:open_encrypted_connection, D/data/TrackerCache.kt:refresh, D/wizard/WalletStore.kt | — (UniffiSmokeTest riapre solo dopo aver droppato l'handle) | **COVERED** (test in §F; prima: UNCOVERED:) |
| GAP-021 | Nessun lock di istanza singola: due processi BHODL sulla stessa `~/.bhodl` (settings.json last-writer-wins, pin_failures.json sovrascritto, registry/watch-state in scrittura concorrente) | D/Main.kt:main | — | **COVERED (SingleInstanceLockTest, blocco su `app.lock`)** |
| GAP-022 | Wipe dei watch-state: i file `watch-state/<uuid>.sqlite` (+sidecar) vengono cancellati nella fase 3, DOPO i KDF, e un watch-state bloccato non interrompe il wipe (viene solo riportato tra i falliti) | D/wizard/WalletPaths.kt:resetChecked | — (`seedDataDir` di SecretActionsUiTest e i test `reset_*` di WalletPathsTest non creano watch-state) | **COVERED** (test in §F; prima: UNCOVERED:) |
| GAP-023 | Il factory reset lascia `settings.json`: preferenze, override Esplora per rete, Tor, `excludedWalletIds` con gli UUID dei wallet cancellati, data di dismiss della review. Vengono azzerate solo le date di export delle label | D/wizard/LocalWipe.kt:wipeAll, D/settings/SettingsStore.kt:clearSecurityState | — | **COVERED** (test in §F; prima: UNCOVERED:) |
| GAP-024 | La rimozione di un wallet lascia il suo id in `excludedWalletIds` (settings.json non cifrato) | D/screens/SettingsWalletSections.kt:RemoveThisWalletSection, D/settings/SettingsStore.kt | — | **COVERED** (test in §F; prima: UNCOVERED:) |
| GAP-025 | Migrazione registry v3→v4: backfill di `wallets.mirror_of` solo quando è univoco (label generata dall'app + stessa xpub e rete + candidato unico nei due sensi) | O/registry.rs:backfill_mirror_links, is_pairing_mirror_label | O/registry.rs::pairing_mirror_label_matching (solo le label) | **COVERED** (test in §F; prima: PARTIAL:) |
| GAP-026 | Migrazione v4→v5: creazione di `tracking_event_cost_basis` su un registry v4 esistente, con i dati delle altre tabelle intatti | O/registry.rs:ensure_schema (step `< 5`) | — (`event_cost_basis_round_trip_and_reset` gira solo su un DB nuovo) | **COVERED** (test in §F; prima: UNCOVERED:) |
| GAP-038 | Migrazione v5→v6: colonna `details_json` sulle tre tabelle del portafoglio, dati intatti | O/registry.rs:ensure_schema (step `< 6`) | R::v5_registry_gains_details_columns_and_keeps_its_rows | COVERED |
| GAP-027 | Atomicità della migrazione: se un passo fallisce, il DB resta alla versione precedente (una sola transazione per migrazioni e stamp) | O/registry.rs:ensure_schema | — | **COVERED** (test in §F; prima: UNCOVERED:) |
| GAP-028 | Apertura di un registry storico reale (schema pre-versioning con colonne mancanti) fino alla v5 in un colpo solo | O/registry.rs:ensure_schema, apply_schema_v1_baseline | O/registry.rs::schema_version_reconciles_a_legacy_unstamped_db (DB nuovo con l'header riportato a 0, non uno schema vecchio) | **COVERED** (test in §F; prima: PARTIAL:) |
| GAP-029 | Layout della data dir fisso in `~/.bhodl`, senza override: registry.sqlite, registry.kdf.json (+.sealed/.next), watch-state/, settings.json, pin_failures.json. `chmod 700` solo alla creazione del PIN (settings.json crea la dir prima; watch-state/ non viene ristretta) | D/wizard/WalletPaths.kt:defaultDataDir, restrictToOwner; D/screens/DevicePinSetupScreen.kt | T/wizard/WalletPathsTest::defaultDataDir_lives_under_user_home, ::saltFile_is_inside_the_data_dir | **COVERED (CI Linux)** (test in §F; prima: PARTIAL:) |
| GAP-030 | Dev flag `-Dbhodl.costBasis.includeTestNetworks=true` (System property) che include signet/testnet nel costo medio BTC | D/screens/BtcCostBasisCard.kt:costBasisIncludesTestNetworks | T/screens/BtcCostBasisTest::only mainnet wallets count unless the dev switch includes test networks (passa il booleano a mano) | PARTIAL: la lettura della property non è testata (impostare/ripulire la property in un test). |
| GAP-031 | FFI `generate_wallet_id()` (UUIDv4) | U/lib.rs:generate_wallet_id | — | **COVERED (codice morto)** (test in §F; prima: UNCOVERED:) |
| GAP-032 | FFI per singolo investimento `list_investment_lots(investment_id)` e `list_investment_sells(investment_id)` | U/lib.rs:list_investment_lots, list_investment_sells | Test del registry su `Registry::list_investment_*` (PRT-029/045) | **COVERED** (test in §F; prima: PARTIAL:) |
| GAP-033 | FFI `aggregate_watch_balance` (struct con gli stale ids) e `aggregate_watch_balance_sat`, usato dal TrackerCache sulla rete del wallet aperto (`WalletStore.network()`, SIGNET di default se nessun wallet è aperto) per `bitcoinAssetSat`/`totalAssetSat` | U/lib.rs:aggregate_watch_balance*, D/data/TrackerCache.kt:refresh | U/lib.rs::aggregate_watch_balance_* (WAL-067) | **COVERED** (test in §F; prima: PARTIAL:) |
| GAP-034 | FFI dei tassi storici sul `RegistryHandle` (`btc_rate_at`, `btc_rate_range`, `btc_rate_backfill`) con la chiave dell'handle e il proxy | U/lib.rs (impl RegistryHandle, blocco tassi), D/prices/BtcRateHistoryClient.kt | U/lib.rs::rate_methods_run_on_the_handle_key_without_a_pin; O/btc_rate_history.rs (PRT-073/074) | COVERED (nessuna riga cita i nomi FFI) |
| GAP-035 | Snapshot unico del TrackerCache con `list_all_investment_lots/sells/lot_disposals`, `list_liabilities`, `list_manual_assets`, `dump_crypto_event_kinds`, `list_event_cost_basis` | D/data/TrackerCache.kt:refresh | T/screens/PortfolioScenarioTest (registry reale, chiama lots/disposals/liabilities/manual assets direttamente, non TrackerCache) | PARTIAL: rientra in TRK-001/002 (refresh con registry reale); sells e dump non hanno test Kotlin. |
| GAP-036 | bhodl-core `Amount`: `checked_from_sat` / `checked_add` / `checked_sub` con tetto a 21M BTC, ordinamento numerico | C/amount.rs | C/amount.rs::rejects_above_max_money, ::checked_add_caps_at_max_money, ::checked_add_overflow, ::checked_sub_underflow, ::ordering_is_numeric | COVERED |
| GAP-037 | All'unlock il `coldLoad` (registry-only + sync) e il ticker di 2 minuti possono lanciare due sync di rete di fila (serializzate dal mutex, ma doppie su Tor) | D/data/TrackerCache.kt:start, coldLoad | — | UNCOVERED: test con un clock/ticker iniettabile: dopo `coldLoad` il primo tick non risincronizza entro N secondi dall'ultimo sync. |

Riepilogo GAP: COVERED 8 · PARTIAL 9 · UNCOVERED 20 · Totale 37.

### Classificazioni errate

| Riga | Stato dichiarato | Stato reale | Motivo |
|------|------|------|------|
| WAL-112 | COVERED | UNCOVERED | **COVERED (comportamento documentato)** (test in §F; prima: L'export) |
| PRT-137 | COVERED | PARTIAL (funzione non raggiungibile) | **COVERED (logica testata; card rimontate nella Home del tracker il 2026-10-24)** |
| PRT-136 | COVERED | PARTIAL (funzione non raggiungibile) | **COVERED (logica testata; card rimontate nella Home del tracker il 2026-10-24)** |
| TRK-115…TRK-119 | COVERED | PARTIAL (funzione non raggiungibile) | Savings rate, burn, FIRE e runway (`computeFinanceKpis`) hanno test, ma nessuna schermata li usa. L'area C lo segnala nelle note ma lascia le righe COVERED. |
| TRK-125 | COVERED | PARTIAL | **COVERED (logica testata; card rimontate nella Home del tracker il 2026-10-24)** |
| SEC-075 | COVERED | PARTIAL | **COVERED** (test in §F; prima: I) |
| SEC-099 | COVERED | PARTIAL | **PARTIAL** (test in §F; prima: La) |
| SEC-117 | COVERED | PARTIAL | **COVERED** (test in §F; prima: Lato) |

Righe verificate e confermate corrette (test esistenti e pertinenti): SEC-034, SEC-056, SEC-087, SEC-088, SEC-089, SEC-112, SEC-123, SEC-127, SEC-130, SEC-159, WAL-017, WAL-035, WAL-058, WAL-067, WAL-087, TRK-015, TRK-024, TRK-025, TRK-028, TRK-156, PRT-013, PRT-070, PRT-082, PRT-116, PRT-145 (25, più le 7 righe/gruppi sopra = 32 verificate).

La verifica meccanica di tutti i nomi di test citati nelle 616 righe non ha trovato test inesistenti: i 99 "non trovati" sono tutti citazioni abbreviate (`…nome`, `nome *`, `registry_is_descriptor_tracked_*`).

### Osservazioni

1. **Probabile bug (race lock/refresh)**: `TrackerCache.refresh` cattura `registry` all'inizio e pubblica senza ricontrollare che sia ancora `RegistryStore.current()`. Un lock o un wipe durante una sync lunga (Tor) ripopola la cache con i dati della sessione chiusa e lascia `hasLoadedOnce = true`, quindi il `coldLoad` dopo il successivo unlock torna subito (GAP-019).
2. **Lo storico NW si scosta dall'hero** in tre casi: wallet esclusi conteggiati (GAP-017), cache che non si invalida dopo modifiche a investimenti/manual asset/passività (GAP-016), vendite e investimenti legacy non ripercorsi nella ricostruzione Rust (GAP-018). In più la cache non viene svuotata al lock/wipe (GAP-015).
3. **Lavoro sprecato / stato morto**: a ogni refresh `TrackerCache` chiama `aggregateWatchBalanceSat(WalletStore.network())`, che riapre ogni file watch-state, sulla rete del wallet aperto (SIGNET se non ce n'è uno). `bitcoinAssetSat`/`totalAssetSat` però non sono letti da nessuna UI: TrackerHomeScreen dichiara `bitcoinAssetSat` e non lo usa.
4. **Codice morto aggiuntivo** (oltre a quello già segnalato dalle aree A–C):
   - `AmountDisplayMode` (`ui/AmountFormat.kt`): testato, senza chiamanti;
   - `io.bhodl.shared.formatAmount` / `formatBtc`: nessun chiamante, e il loro import è vietato dalla scansione;
   - `bhodl-core` `Direction` e `Layer`: mai usati fuori dal crate;
   - FFI `generate_wallet_id`, `list_investment_lots(id)`, `list_investment_sells(id)`, `aggregate_watch_balance` (struct): nessun chiamante Kotlin;
   - la catena `computeFinanceKpis` + `computeOpportunityCostBtc`: raggiungibile solo dal `KpiStripV7` non montato.
5. **`computeMonthlyFlows`** (grafico entrate/uscite della HOME):
   - somma la `direction` grezza, quindi un send_to_self OUTGOING diventa una spesa, lo stesso difetto della CategoryBreakdownCard segnalata dall'area C;
   - raggruppa per mese nel fuso di sistema (`ZoneId.systemDefault()`), mentre il resto delle aggregazioni lavora in UTC.
6. **Wipe**: i DB `watch-state/` non sono nella fase 1 ("databases"): se un handle del tracker è aperto, i KDF vengono cancellati e poi il watch-state resta come file fallito. `settings.json` sopravvive al factory reset con gli UUID dei wallet esclusi, che restano orfani anche dopo una semplice rimozione del wallet (GAP-022/023/024).
7. **Allowlist obsoleta**: `PrivacyModeTest::screens never format amounts with the unmasked shared formatters` esenta ancora `DebugWatchScreen.kt`, che nel working tree è stato cancellato.
8. **Errori non ripuliti**: `PriceCache.lastError` (`e.message` grezzo) arriva in Settings senza `userMessage`, a differenza di tutti gli altri errori di rete (GAP-012).


<a id="f"></a>

## F. Test aggiunti per area

### A. Sicurezza, impostazioni

Percorsi: `T/` = `apps/desktop/src/jvmTest/kotlin/io/bhodl/desktop/`; Rust: `crates/bhodl-uniffi/src/commonMain/rust/uc_security_tests.rs` (= `U/uc`), `crates/bhodl-wallet-onchain/tests/uc_security.rs` (= `O/uc`), `crates/bhodl-wallet-onchain/src/common.rs::uc_security_tests` (= `O/common`).

Esito finale: Kotlin 105 test in 16 classi `Uc*SecurityTest`, tutti verdi (5 saltati: 3 `@Ignore` per bug, 2 solo POSIX che girano sulla CI Linux). Rust 14 test, tutti verdi.

| ID | Test aggiunto (file::nome) | Nuovo stato | Note |
|----|----|----|----|
| SEC-001 | T/UcBootRoutingSecurityTest::SEC-001 empty data dir boots to Welcome | COVERED | seam `bootGuardTarget(view)` in App.kt |
| SEC-002 | T/UcBootRoutingSecurityTest::SEC-002 a registry on disk boots to the device PIN gate | COVERED | |
| SEC-003 | T/UcBootRoutingSecurityTest::SEC-003 only seed-era data boots to LegacyCleanup…, ::SEC-003 watch state alone is not seed-era data | COVERED | |
| SEC-004 | T/UcBootRoutingSecurityTest::SEC-004 a re-mount with an unlocked session never bounces to the PIN gate | COVERED | seam `startRoute(...)` |
| SEC-007 | T/settings/UcSettingsPersistenceSecurityTest::SEC-007 the units onboarding flag survives save and rehydrate | COVERED | |
| SEC-009 | T/UcBootRoutingSecurityTest::SEC-009 unlock goes to LegacyCleanup…, ::SEC-009 a registry error while counting… | COVERED | seam `legacyCleanupNeeded(view, count)` + `postUnlockTarget` |
| SEC-010 | T/screens/UcLegacyCleanupSecurityTest::SEC-010 delete stays disabled until the recovery checkbox is ticked | COVERED | `dataDir` già iniettabile |
| SEC-011 | T/screens/UcLegacyCleanupSecurityTest::SEC-011 confirmed cleanup deletes the signing data, keeps watch state and continues | COVERED | |
| SEC-012 | T/screens/UcLegacyCleanupSecurityTest::SEC-012 Not now continues…; T/UcBootRoutingSecurityTest::SEC-012 SEC-014 … | COVERED | |
| SEC-013 | T/screens/UcLegacyCleanupSecurityTest::SEC-013 a locked file shows the failure and does not navigate; T/wizard/UcWipeSecurityTest::SEC-013 (2 test) | COVERED | lock = handle aperto su Windows, dir read-only su POSIX |
| SEC-014 | T/UcBootRoutingSecurityTest::SEC-012 SEC-014 LegacyCleanup continues to the tracker or, without registry, to Welcome | COVERED | seam `legacyCleanupDoneTarget` |
| SEC-017 | T/screens/UcPinScreensSecurityTest::SEC-017 the strength hint shows for 6 and 7 digits and disappears from 8 | COVERED | |
| SEC-021 | T/screens/UcPinScreensSecurityTest::SEC-021 spaces typed in the setup fields are dropped, ::SEC-021 … unlock field … | COVERED | |
| SEC-022 | T/screens/UcPinScreensSecurityTest::SEC-022 a PIN over 32 digits is rejected in the UI… | COVERED | |
| SEC-023 | T/screens/UcPinScreensSecurityTest::SEC-023 a successful setup parks the registry, opens the session and continues | COVERED | seam `createRegistry` in DevicePinSetupScreen |
| SEC-024 | T/screens/UcPinScreensSecurityTest::SEC-024 an FFI failure shows a path-free message and re-enables the button | COVERED | |
| SEC-026 | T/screens/UcPinScreensSecurityTest::SEC-026 Back returns to Welcome and is disabled while creating | COVERED | nuovo tag `DevicePinSetupTags.BACK` |
| SEC-027 | T/screens/UcPinScreensSecurityTest::SEC-027 the right PIN opens the registry, clears the attempts and continues | COVERED | seam `dataDir` + `openRegistry` in DevicePinUnlockScreen |
| SEC-028 | T/screens/UcPinScreensSecurityTest::SEC-028 a wrong PIN shows Wrong device PIN and counts one attempt | COVERED | |
| SEC-030 | T/screens/UcPinScreensSecurityTest::SEC-030 an active lockout refuses the PIN before calling the FFI; T/wizard/UcPinMessagesSecurityTest::SEC-030 lockoutMessage… | COVERED | |
| SEC-031 | T/screens/UcPinScreensSecurityTest::SEC-031 the wrong PIN that triggers the cooldown names the wait | COVERED | |
| SEC-037 | T/screens/UcPinScreensSecurityTest::SEC-037 non-PIN failures…, ::SEC-037 a registry from a newer build is not a wrong PIN | COVERED | |
| SEC-038 | T/screens/UcPinScreensSecurityTest::SEC-038 an unknown error…; T/wizard/UcPinMessagesSecurityTest::SEC-038 … | COVERED | |
| SEC-039 | T/screens/UcPinScreensSecurityTest::SEC-039 unlock is disabled while empty or opening and the PIN field has focus | COVERED | |
| SEC-042 | U/uc::empty_pin_is_refused_before_any_file_is_written | COVERED | |
| SEC-044 | T/screens/UcPinScreensSecurityTest::SEC-044 (2 test); U/uc_pin_recovery_tests (9 test); T/screens/PinRecoveryScreensTest (11 test) | COVERED | corretto: recupero PIN, vedi bug 1 |
| SEC-051 | U/uc::unknown_kdf_descriptor_version_is_refused_as_newer | COVERED | |
| SEC-052 | U/uc::interrupted_migration_falls_back_to_next_and_promotes_it, ::stale_next_from_an_uncommitted_rekey_is_removed, ::wrong_pin_with_a_pending_descriptor_keeps_both_files | COVERED | `unlock_with` con `open` finto (niente SQLCipher) |
| SEC-053 | U/uc::seal_marker_left_by_a_wipe_does_not_block_a_new_registry | COVERED | |
| SEC-054 | U/uc::rename_with_retry_gives_up_at_once_on_a_missing_source, ::rename_with_retry_survives_a_transient_sharing_violation, ::rename_with_retry_fails_when_the_target_stays_locked | COVERED | gli ultimi due solo `cfg(windows)` (share_mode(0)) |
| SEC-065 | T/UcAppActivitySecurityTest::SEC-065 pointer and key events at the root refresh the idle clock | COVERED | `App()` reale con `user.home` su temp dir; accessor `IdleLock.lastActivityNanosForTest` |
| SEC-066 | T/UcBootRoutingSecurityTest::SEC-066 the watcher sleeps one interval…, ::SEC-066 nothing open never makes the watcher lock | PARTIAL | loop estratto (`runIdleLockWatcher`); il passaggio dello stato minimizzato in Main.kt resta manuale (Main.kt fuori perimetro) |
| SEC-068 | T/screens/UcTrackerHeaderSecurityTest::SEC-068 the tracker header Lock device fires onLockDevice | COVERED | |
| SEC-069 | T/UcBootRoutingSecurityTest::SEC-069 lockSession… (2 test); T/UcNavigationSecurityTest::SEC-069 lockAndRouteToGate clears the stack… | COVERED | `lockSession()` estratto, target iniettabile |
| SEC-070 | T/wizard/UcRegistryStoreSecurityTest::SEC-070 clear destroys the handle…, ::SEC-070 clear cancels an in-flight backfill… | COVERED | |
| SEC-071 | T/UcBootRoutingSecurityTest::SEC-071 a wallet route without an open wallet falls back to the tracker HOME | COVERED | predicato `walletWorkspaceRedirect` |
| SEC-075 | T/wizard/UcWipeSecurityTest::SEC-075 GAP-022 the wipe deletes the watch-state databases too | COVERED | ordine reale: registry DB → KDF → watch-state (innocuo: i watch-state dipendono dalla chiave del registry già cancellato) |
| SEC-076 | T/UcNavigationSecurityTest::SEC-076 restartAtBootGuard after a wipe lands on Welcome with a single entry | COVERED | |
| SEC-077 | T/wizard/UcWipeSecurityTest::SEC-077 a locked registry aborts the wipe…, ::SEC-077 SEC-078 describe covers every outcome | COVERED | |
| SEC-078 | T/wizard/UcWipeSecurityTest::SEC-078 wiping an empty data dir reports nothing to wipe | COVERED | |
| SEC-079 | — | PARTIAL (saltato) | collegamento in Main.kt: Main.kt non è nel mio perimetro |
| SEC-081 | T/settings/UcSettingsPersistenceSecurityTest::SEC-081 settings json is written owner-only | COVERED (CI Linux) | `assumeTrue(posix)`: saltato su Windows |
| SEC-082 | T/settings/UcSettingsPersistenceSecurityTest::SEC-082 a failed backup copy…, ::no warning…, ::an unreadable settings file fails closed… | COVERED | il terzo solo Windows (lock mandatorio) |
| SEC-086 | T/screens/UcSettingsUiSecurityTest::SEC-086 the Neon chip…, ::SEC-086 every theme mode maps to the right palette | COVERED | l'applicazione alla radice è in Main.kt |
| SEC-090 | T/screens/UcSettingsUiSecurityTest::SEC-090 the colour convention chips…, ::SEC-090 Eastern swaps the positive and negative colours | COVERED | |
| SEC-091 | T/screens/UcSettingsUiSecurityTest::SEC-091 the fiat chips switch the overlay currency | PARTIAL | `refreshNow` al cambio è in Main.kt |
| SEC-092 | T/screens/UcSettingsUiSecurityTest::SEC-092 the investment auto-fetch switch… | PARTIAL | trigger off→on in Main.kt |
| SEC-093 | T/screens/UcSettingsUiSecurityTest::SEC-093 each Bitcoin Lens fiat switch flips only its own surface | COVERED | |
| SEC-094 | T/screens/UcSettingsUiSecurityTest::SEC-094 the module switches…; T/settings/UcSettingsPersistenceSecurityTest::SEC-094 (2 test) | COVERED | |
| SEC-095 | T/screens/UcSettingsUiSecurityTest::SEC-095 the start-hidden switch is saved as a preference | COVERED | |
| SEC-096 | — | PARTIAL (saltato) | `Locale.setDefault` in Main.kt (fuori perimetro); resta il difetto "System non ripristina il Locale" (osservazione 4) |
| SEC-099 | T/i18n/UcRuntimeStringsSecurityTest::SEC-099 the scan helper…, ::GAP-013 … (`@Ignore`) | PARTIAL | scansione estesa ad Android, fallisce → bug GAP-013 |
| SEC-100 | T/wizard/UcPinMessagesSecurityTest::SEC-100 messages outside composition follow the current language | COVERED | |
| SEC-107 | T/screens/UcSettingsUiSecurityTest::SEC-107 (2 test) | COVERED | |
| SEC-108 | T/screens/UcHubPaletteSecurityTest::SEC-108 opening an unknown wallet…, ::SEC-108 … without an unlocked registry … | PARTIAL | `OpenWalletRoute` reso internal; rete sconosciuta non riproducibile (l'FFI non accetta righe con rete ignota) |
| SEC-116 | O/common::esplora_timeout_is_larger_only_when_a_proxy_is_configured | COVERED | ripristina il test citato dal test-book §46.1b |
| SEC-117 | T/prices/UcTorProxyWiringSecurityTest (3 test) | COVERED | vedi GAP-014 |
| SEC-118 | — | COVERED da area B | `UcAddWatchWalletTest::WAL-021 …` |
| SEC-131 | T/ui/UcShellSecurityTest::SEC-131 SEC-160 the footer shield and eye…; T/screens/UcSettingsUiSecurityTest::SEC-131 … | PARTIAL | apertura dalla palette non testata |
| SEC-133 | T/screens/UcHubPaletteSecurityTest::SEC-133 the wallets row says watch-only… | COVERED | |
| SEC-134 | T/screens/UcHubPaletteSecurityTest::SEC-134 every status is spelled out next to its icon | COVERED | |
| SEC-142 | T/wizard/UcPinMessagesSecurityTest::SEC-142 each failure kind has its own message | COVERED | |
| SEC-143 | T/ui/UcShellSecurityTest::SEC-143 (2 test) | COVERED | |
| SEC-144 | T/UcNavigationSecurityTest::SEC-144 section clicks replace the tracker entry… | COVERED | NavHost reale con destinazioni segnaposto |
| SEC-145 | T/UcNavigationSecurityTest::SEC-145 (4 test) | COVERED | |
| SEC-146 | T/UcNavigationSecurityTest::SEC-146 Settings opens full screen on top and Back returns to the section | COVERED | |
| SEC-148 | T/ui/UcShellSecurityTest::SEC-148 sections and rail entries map both ways… | COVERED | |
| SEC-150 | — | UNCOVERED (saltato) | `onKey` è dentro TrackingScreen.kt (non modificabile): serve estrarre `trackerShortcut(key, mod)` |
| SEC-151 | T/screens/UcHubPaletteSecurityTest::SEC-151 (2 test) | PARTIAL | Esc non riproducibile nel test (il Dialog desktop gestisce Esc a livello finestra) |
| SEC-153 | T/ui/UcShellSecurityTest::SEC-153 …; T/screens/UcHubPaletteSecurityTest::SEC-153 the Security Hub and Modules pages have the back arrow | COVERED | |
| SEC-154 | T/ui/UcShellSecurityTest::SEC-154 (2 test) | COVERED | |
| SEC-160 | T/ui/UcShellSecurityTest::SEC-131 SEC-160 … | COVERED | grafici già coperti da AccessibilityTablesTest |
| SEC-161 | T/screens/UcWalletSettingsSecurityTest::SEC-161 (3 test) | COVERED | |
| SEC-163 | T/screens/UcWalletSettingsSecurityTest::SEC-163 the clipboard is cleared after the delay only if it still holds the value | COVERED | seam scope/ritardo/contesto UI |
| SEC-164 | T/screens/UcWalletSettingsSecurityTest::SEC-164 GAP-024 the second tap removes the wallet and forgets its exclusion | COVERED | |
| GAP-011 | T/i18n/UcRuntimeStringsSecurityTest::GAP-011 runtime tracker errors follow the current language (`@Ignore`) | UNCOVERED (bug) | **COVERED (UcRuntimeStringsSecurityTest, errori del tracker tradotti)** |
| GAP-012 | T/screens/UcSettingsUiSecurityTest::GAP-012 a failing price fetch never shows the raw error in Settings | COVERED | fix in PriceCache fatto da un altro agente |
| GAP-013 | T/i18n/UcRuntimeStringsSecurityTest::GAP-013 the Android host has no hardcoded UI text (`@Ignore`) | UNCOVERED (bug) | **COVERED (UcRuntimeStringsSecurityTest, placeholder Android tradotto)** |
| GAP-014 | T/prices/UcTorProxyWiringSecurityTest::every proxy-taking FFI call passes the Tor proxy, ::the backfill pass-through is fed by the Tor proxy provider, ::the scan recognises an unwired call | COVERED | scansione: funzioni con proxy lette da lib.rs, call site Kotlin verificati |
| GAP-015 | T/wizard/UcRegistryStoreSecurityTest::GAP-015 clear forgets the net-worth history and per-currency rate caches | COVERED | fix: `RegistryStore.clear()` ora svuota NetWorthHistoryClient e PriceCache |
| GAP-022 | T/wizard/UcWipeSecurityTest::SEC-075 GAP-022 …, ::GAP-022 a locked watch-state file is reported but does not abort the wipe | COVERED | |
| GAP-023 | T/wizard/UcWipeSecurityTest::GAP-023 settings json survives the wipe but forgets the wallets it described | COVERED | fix: `clearSecurityState()` azzera anche `excludedWalletIds` |
| GAP-024 | T/screens/UcWalletSettingsSecurityTest::SEC-164 GAP-024 … | COVERED | fix in `RemoveThisWalletSection` |
| GAP-025 | O/uc::v3_to_v4_links_only_unambiguous_app_generated_mirrors | COVERED | |
| GAP-026 | O/uc::v4_to_v5_creates_the_cost_basis_table_and_keeps_existing_data | COVERED | |
| GAP-027 | O/uc::a_failing_migration_step_rolls_back_to_the_previous_version | COVERED | |
| GAP-028 | O/uc::a_pre_versioning_registry_migrates_to_the_current_schema_in_one_open | COVERED | |
| GAP-029 | T/settings/UcSettingsPersistenceSecurityTest::GAP-029 (2 test) | COVERED (CI Linux) | il test dei permessi gira solo su POSIX |

#### Bug trovati

1. **SEC-044 (corretto)**: con un PIN dimenticato non c'era nessuna via nell'app verso il reset locale. Ora la schermata di sblocco ha "Hai dimenticato il PIN?": con il codice di recupero si imposta un nuovo PIN, senza codice si arriva a una cancellazione locale spiegata (docs/pin-recovery.md).
2. **GAP-023 (corretto)**: dopo il factory reset `excludedWalletIds` teneva in settings.json gli UUID dei wallet cancellati. Ora `SettingsStore.clearSecurityState()` li azzera; le altre preferenze sopravvivono per scelta, e il test lo documenta.
3. **GAP-024 (corretto)**: la rimozione di un wallet lasciava il suo id in `excludedWalletIds`. Ora `RemoveThisWalletSection` lo rimuove quando la rimozione riesce.
4. **GAP-015 (corretto, wiring)**: un altro agente ha aggiunto `NetWorthHistoryClient.clearCache()` e `PriceCache.clearSessionCaches()`, ma nessuno li chiamava. Ora li chiama `RegistryStore.clear()`, quindi a ogni lock e wipe.
5. **GAP-011 (corretto)**: i messaggi runtime di TrackerCache erano solo in inglese. Ora sono risorse EN/IT.
6. **GAP-013 (corretto)**: il placeholder Android aveva testo inglese hardcoded. Ora è una risorsa EN/IT.
7. Osservazione: in `BackfillRunner`, un'eccezione non di cancellazione lanciata dall'FFI DOPO `cancel()` pubblica comunque `Failed`. La guardia R5 copre solo il ramo di successo. Non l'ho testato come bug, segnalato e basta.

#### File di produzione modificati

- `apps/desktop/src/jvmMain/kotlin/io/bhodl/desktop/App.kt`: seam puri estratti senza cambiare comportamento: `bootGuardTarget(view)`, `startRoute`, `postUnlockTarget`, `legacyCleanupDoneTarget`, `legacyCleanupNeeded(view, count)`, `runIdleLockWatcher`/`idleLockDue`, `lockSession`, `walletWorkspaceRedirect`. Resi `internal`: `navigateToSection`, `handleRailTab` (con `walletOpen`), `restartAtBootGuard`/`lockAndRouteToGate` (con target iniettabile), `OpenWalletRoute`.
- `apps/desktop/src/jvmMain/kotlin/io/bhodl/desktop/IdleLock.kt`: aggiunto `lastActivityNanosForTest()`.
- `apps/desktop/src/jvmMain/kotlin/io/bhodl/desktop/screens/DevicePinSetupScreen.kt`: seam `createRegistry`, tag `BACK`.
- `apps/desktop/src/jvmMain/kotlin/io/bhodl/desktop/screens/DevicePinUnlockScreen.kt`: seam `dataDir` e `openRegistry`, tag `ERROR`.
- `apps/desktop/src/jvmMain/kotlin/io/bhodl/desktop/screens/SettingsWalletSections.kt`: fix GAP-024; `copyToClipboardWithAutoClear` reso internal con seam scope/ritardo/contesto UI.
- `apps/desktop/src/jvmMain/kotlin/io/bhodl/desktop/settings/SettingsStore.kt`: fix GAP-023.
- `apps/desktop/src/jvmMain/kotlin/io/bhodl/desktop/wizard/RegistryStore.kt`: wiring GAP-015.
- `crates/bhodl-uniffi/src/commonMain/rust/lib.rs`: aggiunta solo la riga `#[cfg(test)] mod uc_security_tests;`.
- `crates/bhodl-wallet-onchain/src/common.rs`: aggiunto in coda il modulo test `uc_security_tests` (SEC-116).

### B. Wallet watch-only

Esito: 37 test Rust (25 onchain + 12 FFI) e 49 test Kotlin jvmTest, tutti verdi; 2 test IT Docker scritti (compilano, non eseguiti qui). Righe in tabella: 77 (71 di B + 6 di E). Nuovo stato: 71 COVERED (WAL-049/050 solo via IT Docker), 1 PARTIAL (WAL-034), 5 UNCOVERED motivate (WAL-024, WAL-044, WAL-063, WAL-066, GAP-021).

File di test (tutti nuovi):
- R1 = `crates/bhodl-wallet-onchain/src/uc_wallet_tests.rs` (modulo figlio di `watch.rs`, 25 test)
- R2 = `crates/bhodl-uniffi/src/commonMain/rust/uc_wallet_tests.rs` (modulo figlio di `lib.rs`, 12 test)
- K1 = `apps/desktop/src/jvmTest/kotlin/io/bhodl/desktop/screens/UcAddWatchWalletTest.kt`
- K2 = `.../screens/UcWalletWorkspaceTest.kt`
- K3 = `.../screens/UcWalletHistoryTest.kt`
- K4 = `.../screens/UcWalletSettingsTest.kt`
- IT = `.../integration/UcWalletRegtestIntegrationTest.kt` (Docker, escluso da jvmTest: compila ma non gira qui)

| ID | Test aggiunto (file::nome) | Nuovo stato | Note |
|---|---|---|---|
| WAL-004 | R1::wal_004_every_multisig_slip132_prefix_and_broken_keys_are_rejected | COVERED | Ypub/Vpub/Upub/Zpub, checksum rotto, lunghezza ≠ 78 |
| WAL-005 | R1::wal_005_tpub_is_rejected_on_mainnet | COVERED | core + registry |
| WAL-007 | R1::wal_007_empty_label_is_rejected_for_every_kind_but_blank_is_not_trimmed | COVERED | documenta che `"   "` passa nel core (trim solo in UI) |
| WAL-009 | R1::wal_009_unhardened_purpose_and_master_path_are_rejected | COVERED | |
| WAL-010 | R1::wal_010_slip132_contradicting_the_hd_purpose_is_rejected | COVERED | vpub + 86' rifiutato, vpub + 84' normalizzato a tpub |
| WAL-011 | R1::wal_011_hd_xpub_of_the_wrong_network_is_rejected | COVERED | |
| WAL-014 | R1::wal_014_address_list_appends_idempotently_and_stores_canonical_form | COVERED | append idempotente + bech32 maiuscolo salvato minuscolo |
| WAL-015 | R2::wal_015_add_watch_xpub_end_to_end_on_testnet4_mainnet_and_regtest | COVERED | stringa di rete salvata + open_watch + primo indirizzo (vettore BIP-86 mainnet) |
| WAL-016 | R2::wal_016_registry_insert_failure_after_the_file_exists_removes_the_file | COVERED | INSERT fallito via mutex del registry avvelenato → file rimosso |
| WAL-018 | K1::`WAL-018 WAL-022 submit is gated…`, K1::`WAL-018 defaults are Taproot on Signet` | COVERED | registry reale, endpoint Esplora puntato a 127.0.0.1:1 |
| WAL-019 | K1::`WAL-019 a label already used…`, K1::`WAL-019 isDuplicate trims…` | COVERED | |
| WAL-020 | K1::`WAL-020 the same tpub twice…`, K1::`WAL-020 a vpub of an account saved as tpub…`, K1::`WAL-020 AccountKey normalises…` | COVERED | **BUG confermato e corretto** (vedi sotto) |
| WAL-021 | K1::`WAL-021 mainnet without Tor warns…`, K1::`WAL-021 mainnet with Tor on…` | COVERED | |
| WAL-022 | K1::`WAL-018 WAL-022 …` | COVERED | sync fallita (porta chiusa) → onAdded, riga, file, dataVersion incrementato |
| WAL-023 | K1::`WAL-023 core errors are shown…`, K1::`WAL-023 garbage xpub…` | COVERED | nessuna riga né file |
| WAL-024 | — | UNCOVERED (saltato) | funzionalità assente in UI (solo FFI): gap di prodotto, niente da testare |
| WAL-026 | R2::wal_026_wal_038_non_uuid_ids_are_storage_errors | COVERED | anche rename/remove |
| WAL-027 | R1::wal_027_unknown_kind_or_corrupt_uuid_in_a_row_is_a_storage_error | COVERED | |
| WAL-028 | R1::wal_028_renaming_an_unknown_wallet_returns_false_and_core_accepts_any_label | COVERED | |
| WAL-029 | K4::`WAL-029 rename refuses blank and duplicate…`, K4::`WAL-029 renaming a wallet missing…` | COVERED | nome vuoto: il pulsante è disabilitato (il messaggio `set_rename_empty` è irraggiungibile da click) |
| WAL-032 | K4::`WAL-032 GAP-024 second tap closes the handle…` | COVERED | handle reale chiuso, riga+categorie+file rimossi |
| WAL-034 | K4::`WAL-034 delete stays disabled…`, `WAL-034 Not now…`, `WAL-034 a locked legacy file…` (solo Windows) | PARTIAL | il conteggio delle righe `signing` non è testabile da Kotlin (nessun kind FFI per inserirle); file legacy e flusso coperti |
| WAL-036 | K4::`WAL-036 clicking a wallet row opens that wallet` | COVERED | |
| WAL-038 | R2::wal_026_wal_038_non_uuid_ids_are_storage_errors | COVERED | |
| WAL-039 | R1::wal_039_loading_an_empty_or_missing_db_is_a_storage_error, R2::wal_039_registry_row_with_a_deleted_state_file_fails_to_open | COVERED | nota: `load` su un path mancante crea un file vuoto come effetto collaterale |
| WAL-041 | R1::wal_041_creating_a_different_xpub_on_an_existing_db_is_refused | COVERED | anche stesso xpub con script diverso |
| WAL-044 | — | UNCOVERED (saltato) | `OpenWalletRoute` è privata in App.kt (file fuori dal mio perimetro) |
| WAL-046 | K4::`WAL-046 switching wallet drops the sync cache…` | COVERED | |
| WAL-047 | K4::`WAL-047 closing a real wallet port twice…` | COVERED | |
| WAL-049 | IT::`WAL-049 an address-list wallet picks up a payment…` | COVERED (solo Docker IT) | non eseguito qui |
| WAL-050 | IT::`WAL-050 WAL-057 stop-gap 20…` | COVERED (solo Docker IT) | change address dal wallet di firma non coperto |
| WAL-051 | R2::wal_051_network_phase_of_a_sync_does_not_hold_the_wallet_mutex | COVERED | server TCP che non risponde |
| WAL-052 | R1::wal_052_scan_update_of_the_wrong_kind_is_refused | COVERED | |
| WAL-053 | R1::wal_053_clearnet_sync_to_a_closed_port…, R1::wal_053_http_500_is_retried… | COVERED | |
| WAL-054 | R1::wal_054_esplora_url_without_host_is_invalid_before_any_socket_with_tor | COVERED | |
| WAL-057 | R1::wal_057_synced_state_is_visible_after_reopen_without_a_new_scan; IT | COVERED | update sintetico persistito, nessuna sync dopo la riapertura |
| WAL-059 | K2::`WAL-059 manual sync bypasses a fresh cache…` | COVERED | |
| WAL-061 | K2::`WAL-061 a mainnet wallet syncs against the mainnet endpoint…`, `WAL-061 a per-network override…` | COVERED | |
| WAL-063 | — | UNCOVERED (saltato) | loop in TrackerCache.kt (vietato modificarlo); serve un seam |
| WAL-064 | K2::`WAL-064 header sync status covers…` | COVERED | |
| WAL-066 | — | UNCOVERED (saltato) | serve spendere dal descriptor privato in bitcoind (Docker); non scritto |
| WAL-068 | K2::`WAL-068 balance shows a dash…` | COVERED | |
| WAL-069 | R1::wal_069_wal_096_wal_098_confirmed_incoming_and_outgoing_with_change | COVERED | catena sintetica: altezza, timestamp, fee solo in uscita |
| WAL-072 | K3::`WAL-072 status sort…`, K3::`WAL-072 clicking a header…` | COVERED | |
| WAL-074 | K3::`WAL-074 a tx without a BIP-329 category…` | COVERED | |
| WAL-075 | K3::`WAL-075 stand-alone History syncs on entry…`, `WAL-075 a failed entry sync…` | COVERED | |
| WAL-077 | K3::`WAL-077 incoming tx hides the fee…` | COVERED | |
| WAL-081 | R1::wal_081_next_unused_skips_an_address_that_received_funds | COVERED | |
| WAL-088 | K4::`WAL-088 copy address and copy URI…` | COVERED | clipboard iniettato via `LocalClipboardManager`, nessun seam necessario |
| WAL-092 | R1::wal_092_receive_label_keeps_existing_text_and_lowest_output_wins, R1::wal_092_receive_label_copy_works_on_hd_wallets_and_ignores_outgoing | COVERED | |
| WAL-093 | K2::`WAL-093 receive-label categories land in the tracker registry…` | COVERED | |
| WAL-094 | R1::wal_094_change_addresses_are_listed_once_revealed | COVERED | |
| WAL-095 | K2::`WAL-095 the Change chip…`, K2::`WAL-095 WAL-097 load failures…` | COVERED | |
| WAL-096 | R1::wal_069_wal_096_wal_098_… | COVERED | UTXO confermato di change su wallet HD |
| WAL-097 | K2::`WAL-095 WAL-097 load failures…` | COVERED | |
| WAL-098 | R1::wal_069_wal_096_wal_098_…, R1::wal_057_… | COVERED | |
| WAL-100 | R2::wal_100_clearing_a_tx_category_keeps_the_label_text | COVERED | |
| WAL-107 | R1::wal_107_unknown_label_type_in_the_db_is_a_storage_error | COVERED | |
| WAL-108 | K3::`WAL-108 a failing label save…`, R2::wal_108_wal_119_labels_and_contacts_survive… | COVERED | |
| WAL-110 | K4::`WAL-110 a failing import shows the rollback note…` | COVERED | |
| WAL-111 | K4::`WAL-111 readTextCapped refuses files above the cap…` | COVERED | |
| WAL-112 | K4::`WAL-112 label export runs without a privacy prompt…` | COVERED (comportamento documentato) | classificazione corretta: nessuna conferma in privacy mode; il JSONL BIP-329 non contiene importi. Se si vuole la conferma è una feature da decidere |
| WAL-113 | R1::wal_113_contact_label_is_bounded_and_address_is_canonicalised | COVERED | 257 byte, 258 byte multibyte, maiuscolo → minuscolo, duplicato sulla forma canonica |
| WAL-118 | K4::`WAL-118 a core refusal of the address is shown…` (core reale), K4::`WAL-118 a failing contact list…` | COVERED | |
| WAL-119 | R2::wal_108_wal_119_labels_and_contacts_survive_closing_and_reopening_the_wallet | COVERED | nuovo RegistryHandle + nuovo WatchHandle |
| WAL-120 | R1::wal_120_account_xpub_handles_nested_segwit_origins_and_unknown_shapes | COVERED | il ramo FFI `None → Storage` è banale e non raggiungibile da wallet creati da BHODL |
| WAL-121 | K4::`WAL-121 Hide puts the descriptor away again`, K4::`WAL-121 the copy buttons…` | COVERED | |
| WAL-122 | K4::`WAL-122 the clipboard is cleared after the delay only if…` | COVERED | usa i seam già presenti (timerScope/delayMs/uiContext) |
| WAL-124 | K2::`WAL-124 header name comes from the registry…`, K2::`WAL-124 without a registry…` | COVERED | |
| WAL-128 | R2::wal_128_every_onchain_error_maps_to_its_typed_ffi_variant, R2::wal_128_core_errors_reach_kotlin_typed_through_real_calls | COVERED | nota: `InvalidArgument` non ha variante FFI e finisce in `Storage` |
| WAL-129 | R2::wal_129_poisoned_watch_mutex_returns_storage_instead_of_panicking | COVERED | |
| WAL-130 | K4::`WAL-130 NetworkSelector offers no mainnet…`, K4::`WAL-130 ReceiveAddressCard…` | COVERED (codice morto) | testata la logica, non rimosso |
| GAP-020 | R1::gap_020_two_handles_on_one_file_neither_blocks_nor_loses_changes | COVERED | nessun SQLITE_BUSY, nessun changeset perso; il secondo handle non vede la sync finché non riapre |
| GAP-021 | — | UNCOVERED (saltato) | **COVERED (SingleInstanceLockTest, blocco su `app.lock`)** |
| GAP-024 | K4::`WAL-032 GAP-024 second tap…` | COVERED | il fix (`setWalletIncluded(id, true)` dopo la rimozione) era già presente nel working tree |
| GAP-031 | R2::gap_031_generate_wallet_id_returns_distinct_hyphenated_uuid_v4 | COVERED (codice morto) | |
| GAP-033 | R2::gap_033_aggregate_struct_matches_the_sat_wrapper…, K4::`GAP-033 aggregate balance per network…` | COVERED | valore letto anche da Kotlin; resta il lavoro sprecato in TrackerCache (non toccato) |

#### Bug trovati

1. **WAL-020 (corretto)** — `AddWatchScreen` confrontava l'xpub grezzo digitato con l'xpub normalizzato salvato nel descriptor: un `vpub`/`zpub` dello stesso account di un `tpub`/`xpub` già tracciato non veniva riconosciuto come duplicato. Fix: nuovo `wallet/AccountKey.normalize` (base58check, swap dei version byte SLIP-132 come `normalize_xpub` del core) usato prima del confronto.

Nessun altro bug di produzione trovato; comportamenti da segnalare:
- `WatchAccount::load` su un path inesistente crea un file SQLCipher vuoto prima di fallire (WAL-039).
- `OnchainError::InvalidArgument` arriva a Kotlin come `WalletError::Storage` (WAL-128).
- In `RenameWalletSection` il messaggio "Name cannot be empty." non è raggiungibile (pulsante disabilitato).

#### File di produzione modificati

- `apps/desktop/src/jvmMain/kotlin/io/bhodl/desktop/wallet/AccountKey.kt` (nuovo, fix WAL-020)
- `apps/desktop/src/jvmMain/kotlin/io/bhodl/desktop/screens/AddWatchScreen.kt` (usa `AccountKey.normalize` nel controllo duplicati)
- `crates/bhodl-wallet-onchain/src/watch.rs` (solo `#[cfg(test)] #[path = "uc_wallet_tests.rs"] mod uc_wallet_tests;`)
- `crates/bhodl-uniffi/src/commonMain/rust/lib.rs` (solo `#[cfg(test)] mod uc_wallet_tests;`)

### C. Tracker

Percorsi: `T/` = `apps/desktop/src/jvmTest/kotlin/io/bhodl/desktop/`, `R/` = `crates/bhodl-wallet-onchain/src/registry/uc_tracker_tests.rs`.
File di test (tutti nuovi): `T/data/UcCacheTrackerTest.kt` (25), `T/screens/UcLedgerTrackerTest.kt` (21), `UcAggregationsTrackerTest.kt` (27), `UcTransactionsTrackerTest.kt` (13), `UcCardsTrackerTest.kt` (24), `UcRegistryWiringTrackerTest.kt` (10), `UcWalletTxTrackerTest.kt` (3), `UcReportsTrackerTest.kt` (7), `UcHomeTrackerTest.kt` (4), `UcDrillDownTrackerTest.kt` (2), `R/` (8 test Rust).
Totale: 136 test Kotlin + 8 test Rust, tutti verdi (`cargo test -p bhodl-wallet-onchain --lib` 337/337 e `cargo test -p bhodl-uniffi` 67/67 verdi dopo le modifiche al registry). Ho rieseguito 22 classi di test esistenti dell'area (CSV, Privacy, Budget, Rules, SendToSelf, RateHealing, StringsParity, HardcodedUiStringScan…): tutte verdi.

| ID | Test aggiunto (file::nome) | Nuovo stato | Note |
|---|---|---|---|
| TRK-001 | UcCacheTrackerTest::`TRK-001 TRK-004 a failed Esplora sync marks the wallet stale and keeps cached data` | PARTIAL | Registry reale con un wallet address-list ed Esplora irraggiungibile (127.0.0.1:1). Il ramo con sync riuscita richiederebbe un Esplora finto o regtest. |
| TRK-002 | UcCacheTrackerTest::`TRK-002 TRK-009 registry-only refresh publishes registry data without stamping a sync` | COVERED | |
| TRK-003 | UcCacheTrackerTest::`TRK-003 refresh without an open registry reports it and publishes nothing` | COVERED | |
| TRK-004 | UcCacheTrackerTest::`TRK-001 TRK-004 …`, `TRK-004 the message counts every failed wallet` | COVERED | |
| TRK-005 | — | UNCOVERED (saltato) | Ramo irraggiungibile: `openWatch` fallisce già su una rete non valida (il wallet diventa stale) prima di arrivare a `networkOf == null`. |
| TRK-006 | UcCacheTrackerTest::`TRK-006 a wallet whose watch state cannot be opened is stale and the rest continues` | COVERED | |
| TRK-007 | UcCacheTrackerTest::`TRK-007 a registry-only refresh keeps the stale verdict and sync message of existing wallets` | COVERED | Coperta anche la rimozione del wallet (l'id esce dal set). |
| TRK-008 | UcCacheTrackerTest::`TRK-008 a network refresh with no watch wallets counts as synced` (+ TRK-001: niente stamp se la sync fallisce) | COVERED | |
| TRK-009 | UcCacheTrackerTest::`TRK-002 TRK-009 …` | COVERED | Verificato `hasLoadedOnce`; l'atomicità dello snapshot non è testabile in modo deterministico. |
| TRK-010 | UcCacheTrackerTest::`TRK-010 an unexpected failure surfaces in lastError and clears refreshing` | COVERED | Handle distrutto. |
| TRK-011 | UcCacheTrackerTest::`TRK-011 cold load runs once even when several screens kick it`, `TRK-011 cold load is skipped once the cache already loaded` | COVERED | |
| TRK-012 | UcCacheTrackerTest::`TRK-012 reset empties every state and re-arms the cold load` | COVERED | |
| TRK-013 | UcCacheTrackerTest::`TRK-013 after start a data version bump triggers a registry-only refresh` | PARTIAL | Coperti l'osservatore di `dataVersion` e `start` idempotente. Il ticker non è testato: `stop()` cancella lo scope per sempre, quindi non si può riavviare con un intervallo diverso. |
| TRK-014 | — | PARTIAL (saltato) | Fuori area (net worth). |
| TRK-016 | UcTransactionsTrackerTest::`TRK-016 the cache error wins over a local mutation error`, `TRK-016 a local mutation error shows when the cache has none` | COVERED | |
| TRK-017 / 018 / 019 | — | UNCOVERED (saltati) | Servono transazioni nel file watch-state. `WatchAccount.backend` è `pub(crate)` nel crate onchain e il crate uniffi non può iniettarle: serve un seam Rust (helper di test pubblico) nel codice watch, che non è mio. Lato Kotlin il merge categoria/kind è simulato dal seam `watchEventsSource`. |
| TRK-022 | UcLedgerTrackerTest::`TRK-022 …` (2 test); UcCacheTrackerTest::`TRK-022 TRK-023 refresh dedupes by txid but exposes every leg` | COVERED | `mergeEventLegs` estratta come funzione pura. |
| TRK-023 | UcCacheTrackerTest::`TRK-022 TRK-023 …` | COVERED | |
| TRK-026 | UcLedgerTrackerTest::`TRK-026 …` (5 test) | COVERED | Fix del problema 4 (vedi bug). |
| TRK-027 | UcTransactionsTrackerTest::`TRK-027 the wallet row toggles inclusion in the totals` | COVERED | |
| TRK-033 | UcCardsTrackerTest::`TRK-033 typing narrows the suggestions…`, `TRK-033 suggestions are capped at eight and Clear saves an empty category` | COVERED | |
| TRK-034 | UcCacheTrackerTest::`TRK-034 saving a blank category clears the row`, `TRK-035 …` (trim); UcRegistryWiringTrackerTest::`TRK-034 TRK-072 …` | COVERED | `saveEventCategory` estratta. |
| TRK-035 | UcCacheTrackerTest::`TRK-035 a category is written on the representative leg only` | COVERED | |
| TRK-036 | UcWalletTxTrackerTest::`TRK-036 the saved category appears on the transaction row` | PARTIAL | Coperta la categoria del wallet (FakeWalletPort). Il fallback su `registry.getEventCategory` non è testato. |
| TRK-037 | UcTransactionsTrackerTest::`TRK-037 the category chip opens the category dialog` | COVERED | |
| TRK-045 | UcCacheTrackerTest::`TRK-045 refresh tags an internal transfer…`, `TRK-045 a user-pinned regular is never re-tagged` | COVERED | |
| TRK-046 | UcCacheTrackerTest::`TRK-046 the one-time cleanup resets artefact tags, writes its marker and never runs again` | PARTIAL | Il caso "un reset fallisce → nessun marker" non è testato (bisognerebbe far fallire una singola scrittura del registry). |
| TRK-047 | UcCardsTrackerTest::`TRK-047 each of the nine chips commits its kind immediately` | COVERED | |
| TRK-048 | UcCardsTrackerTest::`TRK-048 no badge for regular, a badge for every other kind`, `TRK-048 gifts show the tax note` | COVERED | I colori non sono verificati. |
| TRK-049 | UcAggregationsTrackerTest::`TRK-049 wire values map to kinds and anything else is REGULAR` | COVERED | |
| TRK-050 | UcLedgerTrackerTest::`TRK-050 …` (3 test) | COVERED | |
| TRK-053 | R/::rules_update_of_a_missing_id_returns_false, rules_update_runs_the_same_validation_as_add | COVERED | |
| TRK-057 | R/::rules_reject_blank_category_on_add_and_write_nothing, rules_reject_blank_category_on_update_and_keep_the_row | COVERED | Fix: validazione nel registry. |
| TRK-062 | UcCacheTrackerTest::`TRK-062 enabled rules fill only uncategorised events and the manual category wins` | COVERED | |
| TRK-063 | UcCacheTrackerTest::`TRK-063 disabling a rule keeps the categories it already assigned` | COVERED | Fissa il limite documentato. |
| TRK-064 | UcCacheTrackerTest::`TRK-064 reapply…`; UcRegistryWiringTrackerTest::`TRK-064 Reapply categorises the uncategorised history` | COVERED | `reapplyRules` estratta. |
| TRK-065 | UcCardsTrackerTest::`TRK-065 rule dialog requires name, value and category and an integer amount` | COVERED | |
| TRK-066 | UcRegistryWiringTrackerTest::`TRK-066 a rule saved from the dialog goes on top and can be deleted` | COVERED | |
| TRK-067 | UcCardsTrackerTest::`TRK-067 move buttons are disabled at the edges and while a reorder is pending` | COVERED | |
| TRK-068 | UcRegistryWiringTrackerTest::`TRK-068 a move locks the controls until the cache shows the new order` | PARTIAL | Coperto lo sblocco quando la cache mostra il nuovo ordine. Lo sblocco su scrittura fallita non è testato. |
| TRK-072 | UcRegistryWiringTrackerTest::`TRK-034 TRK-072 a re-categorisation offers a rule that also fills matching history` | COVERED | Pannello, anteprima N=1, regola creata, storico categorizzato. |
| TRK-076 | UcAggregationsTrackerTest::`TRK-076 weekly tolerance is plus minus one day…` | COVERED | |
| TRK-077 | UcAggregationsTrackerTest::`TRK-077 confidence bands`, `TRK-077 amounts 10 to 20 percent apart are still surfaced, but as LOW` | COVERED | |
| TRK-079 | UcCacheTrackerTest::`TRK-079 TRK-082 recurring candidates come from the merged stream minus dismissed keys` | COVERED | |
| TRK-081 | UcCardsTrackerTest::`TRK-081 at most five suggestions plus a counter…`, `TRK-081 five or fewer…` | COVERED | Coperto il testo dei chip, non il colore. |
| TRK-082 | UcRegistryWiringTrackerTest::`TRK-082 dismissing a suggestion writes its key` | COVERED | |
| TRK-083 | UcCacheTrackerTest::`TRK-083 accepting a suggestion…`; UcRegistryWiringTrackerTest::`TRK-083 …` | COVERED | `acceptRecurringAsBill` estratta, con il fuso come parametro. |
| TRK-084 | — | UNCOVERED (gap funzionale) | `undismiss_recurring` non ha nessuna azione in UI: non c'è niente da testare lato app. |
| TRK-091 | R/::bills_reject_a_blank_name_on_add, bills_reject_a_blank_name_on_update_and_keep_the_row | COVERED | Fix: validazione nel registry. |
| TRK-092 | UcCardsTrackerTest::`TRK-092 bill dialog needs a name and amount and clamps the due day`, `TRK-092 an empty due day disables Save…` | COVERED | |
| TRK-093 | UcAggregationsTrackerTest::`TRK-093 …` (`isPaidInMonth` estratta); UcCardsTrackerTest::`TRK-093 bills card…` (2 test) | COVERED | |
| TRK-094 | UcRegistryWiringTrackerTest::`TRK-094 bills are added, marked paid and deleted from the UI` | PARTIAL | Coperti aggiunta e segna/togli pagato. Il Delete dal dialog e il messaggio d'errore non sono testati. |
| TRK-098 | R/::budgets_reject_a_blank_category, budgets_accept_only_the_periods_the_ui_can_prorate | COVERED | Fix: categoria non vuota e periodo tra weekly, monthly, yearly. |
| TRK-099 | UcCardsTrackerTest::`TRK-099 …` (2 test); UcRegistryWiringTrackerTest::`TRK-099 the budget dialog writes a monthly budget and clears it` | COVERED | |
| TRK-104 | UcCardsTrackerTest::`TRK-104 the budget chip opens the editor with the stored budget` | PARTIAL | La barra di progresso non è verificata. |
| TRK-105 | UcRegistryWiringTrackerTest::`TRK-105 …` (2 test) | COVERED | |
| TRK-109 | — | UNCOVERED (saltato) | Servono una valuta fiat e uno storico prezzi in PriceCache (nessun seed per `priceHistory`). |
| TRK-110 | UcAggregationsTrackerTest::`TRK-110 send_to_self contributes only its fee to the period KPIs` | COVERED | |
| TRK-111 | UcAggregationsTrackerTest::`TRK-111 pending net comes from mempool events and confirmed is floored at zero` | COVERED | |
| TRK-112 | UcCardsTrackerTest::`TRK-112 …` (2 test) | COVERED | |
| TRK-113 | UcCardsTrackerTest::`TRK-113 delta labels compare with the previous period` | COVERED | |
| TRK-115…119 | (test esistenti in FinanceKpisTest) | PARTIAL (non raggiungibile) | Classificazione corretta come chiede la sezione E: la logica è testata, ma KpiStripV7 non è montato. Non l'ho rimontato. |
| TRK-120 | — | PARTIAL (non montato) | Le soglie di colore non sono verificate (colori non asseribili via semantics). Componente non montato. |
| TRK-121 / 122 | — | COVERED (invariate) | |
| TRK-123 | UcAggregationsTrackerTest::`TRK-123 a day-31 bill lands on the last day of shorter months` | COVERED (logica) | |
| TRK-124 | UcAggregationsTrackerTest::`TRK-124 forecast end…`; UcCardsTrackerTest::`TRK-124 forecast card shows the final balance…` | PARTIAL (non montato) | Card testata da sola. |
| TRK-125 | UcAggregationsTrackerTest::`TRK-125 banner window follows the given zone, not UTC` | PARTIAL (non raggiungibile) | **COVERED (logica testata; card rimontate nella Home del tracker il 2026-10-24)** |
| TRK-126 | UcCardsTrackerTest::`TRK-126 dismissing the review banner…`, `TRK-126 the dismiss date survives a save - reset - rehydrate round-trip` | PARTIAL (non montato) | Banner testato da solo, round-trip delle impostazioni coperto. |
| TRK-128 | UcAggregationsTrackerTest::`TRK-128 previous series covers the window one period back shifted forward` | COVERED | |
| TRK-131 | UcAggregationsTrackerTest::`TRK-131 …` (2 test) | COVERED | |
| TRK-132 | UcAggregationsTrackerTest::`TRK-132 daily volumes bucket by local day of the requested month` | COVERED | |
| TRK-133 | UcAggregationsTrackerTest::`TRK-133 log ticks…` | COVERED | |
| TRK-134 | UcAggregationsTrackerTest::`TRK-134 stepped points…`, `TRK-134 bar unit is derived…` | COVERED | |
| TRK-135 | UcDrillDownTrackerTest::`TRK-135 …` (treemap + bucket chip) | PARTIAL | Il click sulle fette della ciambella (canvas Koalaplot) non è testabile senza coordinate. |
| TRK-137 | UcReportsTrackerTest::`TRK-137 analytics shows the empty text, or skeletons while refreshing` | COVERED | |
| TRK-138 | UcReportsTrackerTest::`TRK-138 …` (4 test: righe, ordinamento, stati vuoti) | COVERED | Righe estratte (`categoryReportRows`, `walletReportRows`) e corretto il bug sul send_to_self. |
| TRK-139 | UcReportsTrackerTest::`TRK-139 reports use only the period and included wallets, labs charts only with the module` | COVERED | |
| TRK-140 | UcLedgerTrackerTest::`TRK-140 …` (2 test) | COVERED | `filterLedger` estratta. |
| TRK-141 | UcTransactionsTrackerTest::`TRK-141 …` (2 test) | COVERED | |
| TRK-142 | UcTransactionsTrackerTest::`TRK-142 …` (2 test) | COVERED | Lo skeleton durante il refresh non è verificato. |
| TRK-143 | UcLedgerTrackerTest::`TRK-143 running balance…` | COVERED | `runningBalances` estratta. |
| TRK-144 | UcLedgerTrackerTest::`TRK-144 …` (2 test) | COVERED | Fix del problema 3. |
| TRK-145 | UcCardsTrackerTest::`TRK-145 a view needs a non-blank name and is saved trimmed` | COVERED | Il wiring di `saveView` non ha un test UI dedicato (il registry è già coperto). |
| TRK-146 | UcLedgerTrackerTest::`TRK-146 …` (2 test) | COVERED | `applySavedView` estratta. Il Delete del chip non è testato in UI. |
| TRK-147 | UcAggregationsTrackerTest::`TRK-147 clearFilters…`; UcTransactionsTrackerTest::`TRK-147 Esc clears the shared filters` | COVERED | |
| TRK-148 | UcTransactionsTrackerTest::`TRK-148 arrows change the period and stop at the edges` | COVERED | |
| TRK-149 | UcTransactionsTrackerTest::`TRK-149 digit shortcuts jump to a section` | PARTIAL | Il tasto R e il caso "ignorati mentre si scrive in un campo" non sono testati. |
| TRK-150 | UcTransactionsTrackerTest::`TRK-150 TRK-165 Ctrl+K opens the palette and its actions run` | COVERED | Testate le azioni Export CSV, Privacy e Clear filters. |
| TRK-151 | UcCardsTrackerTest::`TRK-151 stale chip and refresh disabled while refreshing` | COVERED | |
| TRK-152 | UcAggregationsTrackerTest::`TRK-152 next tick is local midnight plus a second, capped at one hour` | COVERED | Incluso il cambio d'ora legale. |
| TRK-154 | UcWalletTxTrackerTest::`TRK-154 status sort…`, `TRK-154 clicking a header sorts by it…` | COVERED | |
| TRK-159 | UcAggregationsTrackerTest::`TRK-159 …` (2 test) | COVERED | |
| TRK-160 | UcAggregationsTrackerTest::`TRK-160 …` (4 test) | COVERED | Seam aggiunti per chooser, conferma di sovrascrittura e privacy. |
| TRK-161 | UcTransactionsTrackerTest::`TRK-161 the CSV export follows the active filters` | COVERED | Fix del problema 2. |
| TRK-165 | UcTransactionsTrackerTest::`TRK-150 TRK-165 …` | COVERED | |
| TRK-166 | UcReportsTrackerTest::`TRK-166 budget, bills, recurring, rules and ledger amounts are masked` | PARTIAL | Coperti budget, bill, ricorrenze, regole e ledger (righe e saldo progressivo). Restano fuori Reports, KPI, Sankey e scatter. |
| TRK-167 | UcHomeTrackerTest::`TRK-167 mounting the home kicks the cold load` | PARTIAL | Il testo "Loading…" non è testabile: `seedForTest` forza `hasLoadedOnce`. |
| TRK-168 | UcHomeTrackerTest::`TRK-168 …` (2 test) | COVERED | |
| TRK-169 | UcAggregationsTrackerTest::`TRK-169 events become signed lite events…` | COVERED | |
| TRK-170 | UcHomeTrackerTest::`TRK-170 header refresh is disabled while refreshing…` | COVERED | Il tooltip non è verificato. |
| TRK-171 | UcAggregationsTrackerTest::`TRK-171 tracker and net worth periods round-trip` | COVERED | |
| GAP-010 | UcLedgerTrackerTest::`GAP-010 …` (4 test) | COVERED | Fix: send_to_self non conta più come spesa e i mesi sono raggruppati in UTC. |
| GAP-019 | UcCacheTrackerTest::`GAP-019 a lock during a refresh does not repopulate the cache…`, `GAP-019 the next unlock still runs its cold load on the new registry` | COVERED | Fix: controllo di generazione prima della pubblicazione. |

#### Bug trovati e corretti
1. **Problema 2 / TRK-161: l'export CSV ignorava i filtri.** Ora il pulsante e la palette esportano `filteredEvents`, e anche il contatore del pulsante usa quella lista (`TrackingScreen.kt`).
2. **Problema 3 / TRK-144: la CategoryBreakdownCard sommava la `direction` grezza.** Ora usa `ExternalFlow` (`categoryBreakdown` in `TrackingLedger.kt`).
3. **Lo stesso difetto nei Reports (non segnalato dall'inventario).** `CategoryReportTable` e `WalletReportTable` contavano un send_to_self OUTGOING come uscita. Corretto con le righe estratte che usano `ExternalFlow` (`TrackingReports.kt`).
4. **Problema 4 / TRK-026: l'esclusione dei wallet filtrava il rappresentante già deduplicato.** `includedEvents(events, eventLegs, excluded)` filtra le gambe prima della dedupe e mantiene categoria e kind della tx.
5. **Problema 6 / TRK-057, 091, 098: il registry accettava valori vuoti.** Ora restituisce `InvalidArgument` per una categoria vuota in una regola (add/update), un nome di bill vuoto (add/update), una categoria di budget vuota o un periodo diverso da weekly, monthly, yearly (`registry.rs`).
6. **GAP-010: `computeMonthlyFlows`.** send_to_self contava come spesa e i mesi erano raggruppati nel fuso di sistema. Ora usa `ExternalFlow` e UTC, con il parametro `zone` (`CoreCharts.kt`, solo la funzione).
7. **GAP-019: race tra lock/wipe e refresh.** `refresh` non pubblica più (né su successo né su errore) se `RegistryStore.current() !== registry`.

Bug reali non correggibili in piccolo: nessuno da marcare con `@Ignore`.

#### File di produzione modificati
- `apps/desktop/src/jvmMain/kotlin/io/bhodl/desktop/data/TrackerCache.kt`: seam `watchEventsSource`, `mergeEventLegs` estratta, controllo di generazione (GAP-019), `seedForTest` con i nuovi parametri `lastError`, `eventLegs`, `refreshing`.
- `apps/desktop/src/jvmMain/kotlin/io/bhodl/desktop/screens/TrackingLedger.kt` (nuovo): includedEvents, filterLedger, categoryBreakdown, runningBalances, applySavedView, saveEventCategory, reapplyRules, acceptRecurringAsBill.
- `apps/desktop/src/jvmMain/kotlin/io/bhodl/desktop/screens/TrackingScreen.kt`: usa gli helper, export filtrato, tag `TrackingTags.ROOT`.
- `apps/desktop/src/jvmMain/kotlin/io/bhodl/desktop/screens/TrackingHistoryTab.kt`: usa `runningBalances`.
- `apps/desktop/src/jvmMain/kotlin/io/bhodl/desktop/screens/TrackingCsvExport.kt`: seam per chooser, conferma di sovrascrittura e privacy; `csvExportAction`.
- `apps/desktop/src/jvmMain/kotlin/io/bhodl/desktop/screens/TrackingReports.kt`: righe estratte con `ExternalFlow`.
- `apps/desktop/src/jvmMain/kotlin/io/bhodl/desktop/screens/BillsTrackerCard.kt`: `isPaidInMonth` estratta.
- `apps/desktop/src/jvmMain/kotlin/io/bhodl/desktop/screens/CoreCharts.kt`: solo `computeMonthlyFlows`.
- `crates/bhodl-wallet-onchain/src/registry.rs`: validate_non_blank e validate_budget_period, chiamate in set_budget, add/update_bill e add/update_rule; una riga `mod uc_tracker_tests;`.
- Nessuna stringa visibile all'utente cambiata (StringsParityTest verde).

### D. Portafoglio

File di test (tutti nuovi):
- K1 = apps/desktop/src/jvmTest/kotlin/io/bhodl/desktop/screens/UcPortfolioDialogsTest.kt
- K2 = apps/desktop/src/jvmTest/kotlin/io/bhodl/desktop/screens/UcPortfolioRegistryTest.kt
- K3 = apps/desktop/src/jvmTest/kotlin/io/bhodl/desktop/screens/UcPortfolioNetWorthTest.kt
- K4 = apps/desktop/src/jvmTest/kotlin/io/bhodl/desktop/screens/UcPortfolioCostBasisCardTest.kt
- K5 = apps/desktop/src/jvmTest/kotlin/io/bhodl/desktop/prices/UcPortfolioPriceCacheTest.kt
- K6 = apps/desktop/src/jvmTest/kotlin/io/bhodl/desktop/prices/UcPortfolioPriceFetcherTest.kt
- K7 = apps/desktop/src/jvmTest/kotlin/io/bhodl/desktop/data/UcPortfolioCostBasisRatesTest.kt
- R1 = crates/bhodl-wallet-onchain/tests/uc_portfolio.rs

| ID | Test aggiunto (file::nome) | Nuovo stato | Note |
|----|----------------------------|-------------|------|
| PRT-001 | K1::`PRT-001 a filled investment dialog saves every field`, `PRT-001 blank ticker and blank current value save as null` | COVERED | Compose: argomenti di onSave (trim, uppercase valuta, tipo, metodo) |
| PRT-002 | K2::`PRT-002 seed decision needs both a quantity and a cost`, `…creating from the dialog seeds one lot…`, `…a zero-cost position is created without a lot` | COVERED (helper) | Nuovo helper `shouldSeedLotOnCreate` in InvestmentLots.kt; TrackingScreen.kt:840 va sostituito con `if (editingNow == null && shouldSeedLotOnCreate(quantity, costBasis))` (1 riga, non fatto: file di C) |
| PRT-007 | K1::`PRT-007 blank name, zero or missing quantity and missing cost disable Save`, `PRT-007 Italian grouping parses 350 dot 000…` | COVERED | |
| PRT-008 | K2::`PRT-008 metadata edits are visible on the next read` | PARTIAL | Il refresh della riga via bumpDataVersion vive in TrackingScreen (C) |
| PRT-009 | K2::`PRT-009 single-lot resync returns null…`, `…rewrites the single lot so the row cost matches`, `…partly sold lot only the unsold share of fees…`, `…cost below the fees clamps…` | COVERED (helper) | Nuovo helper `singleLotResync(lots, qty, cost)` in InvestmentLots.kt, aritmetica identica (fee sulla quota invenduta, coerente con effectiveCostBasis). Seam richiesto in TrackingScreen.kt:813-829: sostituire il blocco con `singleLotResync(lots, quantity, costBasis)?.let { r -> val lot = lots.first(); registry.updateInvestmentLot(lot.id, r.quantity, r.pricePerUnit, lot.feesFiat, purchaseUnix, lot.notes); lotToResolve = lot.id }` |
| PRT-010 | K1::`PRT-010 with two lots quantity and cost are the lot sums and read-only`, `PRT-010 with one lot the fields stay editable…` | COVERED | |
| PRT-016 | K5::`PRT-020 auto-fetch on quotes and writes…`, `PRT-016 a quote in another currency is converted through BTC`, `…unpriceable quote currency skips the write`, `…missing quotes and manual positions are skipped…`, `…no open registry means no quote at all` | COVERED | Seam `PriceCache.btcPriceSource/quoteSource` + registry reale |
| PRT-020 | K5::`PRT-020 auto-fetch off asks no quote`, `PRT-020 auto-fetch on quotes and writes the fetched price` | PARTIAL | Il LaunchedEffect del loop 5 min / toggle in Main.kt non testato (Main non mio) |
| PRT-021 | K5::`PRT-021 a forced refresh quotes even with auto-fetch off`, `…standalone investment refresh…` | PARTIAL | flag force coperto; click sul chip dell'hero non testato |
| PRT-022 | K5::`PRT-022 at most MAX_QUOTED_TICKERS quotes per pass` | COVERED | |
| PRT-023 | K1::`PRT-023 adding a lot passes…`, `PRT-023 editing a lot pre-fills it and keeps its date` | COVERED | |
| PRT-024 | K2::`PRT-024 first lot on a legacy aggregate seeds it first…` | COVERED (sequenza registry) | La sequenza seed+add è riprodotta; l'handler vive in TrackingScreen (C) |
| PRT-026 | K1::`PRT-026 lot Save needs positive quantity and price…`, `PRT-026 an unparseable fee blocks Save…` | COVERED | |
| PRT-027 | — | PARTIAL (skip) | Trigger resolveLot dopo l'edit in TrackingScreen (C) |
| PRT-033 | K3::`PRT-033 caret shows for positions with lots or an empty seed-ready row, not for legacy aggregates` | COVERED | Comportamento attuale: riga legacy con qty>0 e senza lot non ha caret (nessun "+ Add lot" raggiungibile da lì) — da confermare se voluto |
| PRT-034 | R1::`prt_034_lot_btc_rate_round_trips_and_rejects_bad_input`; K2::`PRT-034 the FFI writes a lot BTC rate…` | COVERED | API senza chiamanti Kotlin: valutare rimozione |
| PRT-040 | K1::`PRT-040 FIFO quantity above the available units disables Save` | COVERED | |
| PRT-050 | K1::`PRT-050 without a historical rate the BTC P&L reads no rate`, `…with the sale-date rate…signed amount` | COVERED | |
| PRT-051 | K1::`PRT-051 zero price or a bad fee disables Save` | COVERED | |
| PRT-069 | — | PARTIAL (skip) | Pulsante in SettingsSystemSections (A) |
| PRT-072 | — | PARTIAL (skip) | Negative cache in TrackerCache.healMissingRates (C) |
| PRT-078 | K6::`PRT-078 CoinGecko answers…`, `…failure falls back to mempool space`, `…body without the currency falls back too`, `…both providers down gives null, NONE asks nobody` | COVERED | Seam `PriceFetcher.httpGetOverride` |
| PRT-079 | K6::`PRT-079 saneSpotPrice keeps the window…`, `…implausible CoinGecko price triggers the fallback…` | COVERED | |
| PRT-083 | K5::`PRT-083 a successful refresh…`, `…keeps the previous price and flags the error`, `…currency NONE clears the price…` | COVERED | |
| PRT-084 | K5::`PRT-084 rateFor serves fresh…`, `PRT-084 refresh re-fetches only the expired rates`; K3::`PRT-090 expired cross-currency rates stop pricing the asset` | COVERED | |
| PRT-085 | K5::`PRT-085 price history is fetched once per TTL…`, `…NONE, non-positive days and failures…`, `…1-day window expires after 5 minutes…`; K6::`PRT-085 market chart parses points…` | COVERED | priceHistory ora usa il clock `nowMs` |
| PRT-087 | K1::`PRT-087 a new manual asset saves kind, currency and notes…`, `…purchase value without a date…`, `…editing keeps the stored purchase date and value` | COVERED | Il DatePicker non è pilotabile; snapshot coperto via editing |
| PRT-089 | K1::`PRT-089 blank name, non-positive value and invalid purchase value disable Save` | COVERED | |
| PRT-090 | K3::`PRT-090 GBP and JPY assets without a cached rate are excluded…`, `…non-positive or non-finite values are skipped…`, `…expired cross-currency rates…` | COVERED | |
| PRT-095 | K3::`PRT-095 breakdown lists priced kinds with their weight…`, `…unpriced investments show a dash…` | COVERED | |
| PRT-096 | K1::`PRT-096 a liability saves name, sat amount, kind and trimmed label`, `…defaults to kind other…` | COVERED | |
| PRT-099 | K1::`PRT-099 amount must be a whole non-negative sat count` | COVERED | |
| PRT-100 | K3::`PRT-100 liability subtitle shows the kind and the optional note`, `PRT-121 …type badges` (MORTG./CREDIT/OTHER) | COVERED | Osservazione: liabilitySubtitle mostra il valore wire non localizzato ("Credit-card"); kind arbitrario accettato dal registry (non validato) |
| PRT-106 | K3::`PRT-106 fetched price wins…`, `…without a price the cost basis stands in`, `…no price and no cost…`, `…foreign-currency row without its rate is left out…`, `…no live dashboard rate…` | COVERED | |
| PRT-114 | — | PARTIAL (skip) | PeriodSelector in TrackingKpi.kt / hero (C) |
| PRT-121 | K3::`PRT-121 the sheet opens the add menu, edits rows and shows the type badges`, `PRT-121 a ticker without a fetched price shows the fetch chip…` | COVERED | |
| PRT-122 | K3::`PRT-122 sheet rows carry their share of net worth and no-rate rows none`; PRT-095 tests | COVERED | |
| PRT-123 | K1::`PRT-123 absolute target…`, `…percent target…`, `…wallet and manual-asset buckets need a reference`, `…wallet bucket binds the first wallet…`, `…manual-asset bucket binds the first asset…`, `…zero net worth the percent mode cannot be saved` | COVERED | |
| PRT-129 | K1::`PRT-129 a sinking fund without a deadline cannot be saved`, `…existing sinking fund with its deadline saves it` | COVERED | |
| PRT-136 / PRT-137 | — | PARTIAL (non raggiungibile) | Confermato: KpiStripV7 / computeFinanceKpis(opp) senza chiamanti di produzione; nessun test aggiunto (serve decidere se montarli o rimuoverli) |
| PRT-141 | — | PARTIAL (skip) | Sezione del CategoryEditDialog in TrackingScreen (C) |
| PRT-146 | K7::`PRT-146 ensure looks each day up once…`, `…another currency…`, `…misses are not retried until clear…`, `…failing, non-positive or hung lookups…`, `…blank currency asks nothing` | COVERED | |
| PRT-147 | K4::`PRT-147 metrics, notes and transaction rows with their status`, `…day without market rate is a missing price`, `…without a fiat currency…`, `…only test-network wallets…`, `…no transactions shows the empty note` | COVERED | Seam: BtcCostBasisCard → `BtcCostBasisCardContent` stateless |
| PRT-150 | K3::`PRT-150 a USD position on a EUR dashboard is valued in BTC at the cached USD rate`; `PRT-150 without the USD rate…` (@Ignore BUG) | PARTIAL + BUG | vedi bug 1 |
| GAP-012 | K5::`GAP-012 a thrown fetch error never reaches the UI raw` | COVERED (fix) | PriceCache: su eccezione log sanitizzato + lastError = NO_PRICE_ERROR (Settings mostra la stringa localizzata) |
| GAP-015 | K5::`GAP-015 clearSessionCaches drops history and cross-currency rates`; K6::`GAP-015 clearCache forgets every window` | PARTIAL (fix pronto) | Nuove API `PriceCache.clearSessionCaches()` e `NetWorthHistoryClient.clearCache()`; manca la chiamata in RegistryStore.clear() (wizard, non mio): aggiungere `io.bhodl.desktop.prices.PriceCache.clearSessionCaches()` e `kotlinx.coroutines.runBlocking { io.bhodl.desktop.prices.NetWorthHistoryClient.clearCache() }` (o AppScope.io.launch) |
| GAP-016 | K6::`GAP-016 a new data version refetches the same window` | PARTIAL (fix pronto) | CacheKey include `dataVersion` (default `RegistryStore.dataVersion.value`); TrackerHomeScreen.kt:204 deve aggiungere `dataVersion` alle chiavi del LaunchedEffect (1 riga, file non mio) |
| GAP-017 | K3::`GAP-017 events of excluded wallets do not reach the net-worth history` | PARTIAL (fix pronto) | Nuovo `netWorthHistoryEvents(events, excludedWalletIds)` in screens/NetWorthHistoryEvents.kt; TrackerHomeScreen.kt:218 → `val eventLite = netWorthHistoryEvents(events, excludedWalletIds)` + `excludedWalletIds` nelle chiavi dell'effetto |
| GAP-018 | R1::`gap_018_a_partial_sell_only_shrinks_the_history_from_the_sale_date`, `gap_018_a_fully_sold_lot_still_counts_before_its_sale`, `gap_018_deleting_the_sell_restores_the_full_history`, `gap_018_a_legacy_aggregate_investment_appears_from_its_purchase_date`, `gap_018_a_legacy_aggregate_uses_the_current_price_when_set`, `gap_018_seeding_a_lot_does_not_double_count_the_aggregate` | COVERED (fix) | net_worth_history.rs: le vendite vengono ripercorse (qty venduta dopo t riaggiunta al lot), investimenti legacy senza lot = lot sintetico all'acquisto. Restano: prezzo corrente costante, mempool esclusa (per design) |
| GAP-032 | R1::`gap_032_per_investment_lists_only_return_that_investment`; K2::`GAP-032 per-investment lot and sell lists through the FFI` | COVERED | Nessun chiamante Kotlin: valutare rimozione |

#### Bug trovati
1. **PRT-150 (non fixato, file non mio)** — `ui/money/Money.kt` (riga ~74): con `semantic = AtNow` e `rateBtcFiat == null` converte l'importo al prezzo spot della valuta dashboard anche se `value.fiatCurrency` è un'altra valuta. Un investimento in USD senza tasso USD in cache appare come 4,000,000 sat al tasso EUR (viola la policy "no rate"). Fix di 1 riga: aggiungere alla condizione `(value.fiatCurrency == null || value.fiatCurrency.equals(activeFiatCurrency.code, ignoreCase = true))`. Test: K3 `PRT-150 without the USD rate…` @Ignore("BUG PRT-150 …").
2. **GAP-018 (fixato)** — lo storico NW non ripercorreva le vendite e ignorava gli investimenti legacy senza lot.
3. **GAP-012 (fixato)** — errore grezzo di PriceCache verso la UI.
4. GAP-015/016/017 — fix pronti lato client/helper, mancano le chiamate nei file di altri (vedi tabella).

#### File di produzione modificati
- apps/desktop/src/jvmMain/kotlin/io/bhodl/desktop/prices/PriceFetcher.kt — seam `httpGetOverride`
- apps/desktop/src/jvmMain/kotlin/io/bhodl/desktop/prices/PriceCache.kt — seam `btcPriceSource/quoteSource/resetSourcesForTest`, clock `nowMs` anche in priceHistory, fix GAP-012, `clearSessionCaches()`
- apps/desktop/src/jvmMain/kotlin/io/bhodl/desktop/prices/NetWorthHistoryClient.kt — `dataVersion` nella chiave cache, `clearCache()`
- apps/desktop/src/jvmMain/kotlin/io/bhodl/desktop/screens/NetWorthHistoryEvents.kt — NUOVO, `netWorthHistoryEvents`
- apps/desktop/src/jvmMain/kotlin/io/bhodl/desktop/screens/InvestmentLots.kt — `shouldSeedLotOnCreate`, `singleLotResync`
- apps/desktop/src/jvmMain/kotlin/io/bhodl/desktop/screens/BtcCostBasisCard.kt — estratto `BtcCostBasisCardContent` stateless
- apps/desktop/src/jvmMain/kotlin/io/bhodl/desktop/screens/InvestmentsCard.kt / InvestmentEditDialog.kt — testTag chip tipo asset
- apps/desktop/src/jvmMain/kotlin/io/bhodl/desktop/screens/NetWorthCard.kt — testTag chip kind passività
- crates/bhodl-wallet-onchain/src/net_worth_history.rs — fix GAP-018
Nessuna stringa visibile cambiata (nessuna modifica alle risorse EN/IT).
