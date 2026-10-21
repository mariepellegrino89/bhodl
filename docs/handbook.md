# BHODL — Developer Handbook

> Snapshot: 2026-05-09, Phase 2 (on-chain wallet) feature-complete on signet. L3 integration tests + hardening + disaster recovery landed.

This document is the single document a new contributor should read end
to end before touching any code. It collects the architecture, the
component reference, the build & run instructions, the security model,
and the active roadmap. Companion documents under `docs/` cover
specific spikes (`docs/spike-uniffi.md`, `docs/uniffi-runtime.md`,
`docs/research/*`) — link to them rather than repeat their content.

---

## 1. What BHODL is

BHODL is a self-custodial Bitcoin wallet written in Rust + Kotlin
Multiplatform + Compose Multiplatform. The current shipping target is
**desktop (JVM)** on signet. The codebase is laid out so the same
shared module can drive Android, iOS, and (eventually) Web (Wasm)
once each platform target is wired up.

The product roadmap, in order, is:

1. **On-chain wallet** (Phase 2, current). BIP-86 single-sig taproot,
   PSBT build / sign / broadcast, mempool / block sync via Esplora
   (Tor opt-in), RBF on outgoing pending tx, **CPFP on incoming
   pending (receiver speed-up) and on outgoing-pending change (sender
   rescue; the package fee rate counts every unconfirmed ancestor, and
   the build is refused if an ancestor's fee is unknown offline)**, **PayJoin BIP-78 sender** (auto-detect `pj=` in pasted
   URI; negotiate → user reviews every output → sign only our inputs
   → broadcast; full BIP-78 sender checks, https/.onion-only endpoints
   with SSRF guard and Tor routing, fallback to the original payment),
   coin control, batch send, BIP-329
   transaction labels with **JSONL export / import** in Settings,
   local address book with picker dialog on Send, BIP-21 URI parsing
   on Send + structured BIP-21 generator on Receive (fresh address per
   request, optional amount + label), Send MAX (drain), transaction
   details screen, per-wallet network selection at create / restore
   time (Signet, Testnet4, Regtest — Mainnet deferred to Phase 4 with
   hardware-wallet support). Disaster recovery: Settings export
   (age-encrypted seed), restore from backup blob + password (network
   auto-loaded from blob), explicit erase wallet (two-tap). UX:
   Settings (theme + language + Esplora endpoint override + read-only
   "Current network" + BIP-329 labels round-trip), i18n IT/EN,
   background auto-sync with 60s cache, "Restoring wallet" banner on
   first post-restore sync. Silent Payments (BIP-352) deferred to a
   standalone iteration.
2. **Lightning** (Phase 3, next). LDK / ldk-node as a second backend,
   first invoice / payment on signet (Mutinynet), LSP integration.
3. **Net-worth and spending tracking** (Phase 4). Historical balance
   graph, BIP-329 labels promoted to categories, fiat conversion,
   CSV export.
4. **Multi-wallet + L2** (Phase 5). Multiple wallets in one app
   instance, plus Liquid / Cashu / Fedimint / Ark backends.

The product name is BHODL (the binary `bhodl-uniffi`, the desktop app
title bar, the `~/.bhodl/` data dir).

---

## 2. Repository layout

```
.
├── Cargo.toml                       # Rust workspace root
├── settings.gradle.kts              # Gradle multi-module root
├── rust-toolchain.toml              # Pins Rust 1.88
├── .cargo/config.toml               # Forces +crt-static on Windows MSVC
├── crates/
│   ├── bhodl-core/                  # Crypto primitives (KDF, Network, Amount)
│   ├── bhodl-wallet-onchain/        # BDK-backed wallet + SQLCipher persistence
│   ├── bhodl-uniffi/                # FFI surface (Kotlin/Swift bindings)
│   └── uniffi-bindgen-kmp/          # Kotlin Multiplatform bindgen CLI (locked build)
├── shared/                          # KMP module — Compose theme, FFI consumer
│   └── src/jvmTest/                 # JVM smoke tests against the cdylib
├── apps/
│   └── desktop/                     # Compose Multiplatform JVM app (signet)
└── docs/
    ├── handbook.md                  # ← you are here
    ├── spike-uniffi.md              # Phase-0 KMP/UniFFI/Compose spike
    ├── uniffi-runtime.md            # Windows ABI hazard investigation
    └── research/                    # Frontend / UX expert reviews
```

The Rust workspace and the Kotlin Multiplatform / Gradle layer share a
single source tree. The bridge is **uniffi-rs 0.28.3** + the Ubique
**Kotlin Multiplatform fork** of the binding generator, with one
critical Windows ABI workaround documented in §7.

The bindgen CLI is built from the workspace crate `crates/uniffi-bindgen-kmp`
(a copy of the upstream `main.rs` plus a git dependency pinned to an exact
Ubique commit), not by the Gradle plugin's own `cargo install --git`, which
runs without `--locked`. `shared/build.gradle.kts` overrides `installBindgen`
to run `cargo install --locked --path crates/uniffi-bindgen-kmp`, so every
transitive crate is pinned in `Cargo.lock` and covered by `cargo deny` /
`cargo audit`. When bumping `uniffi-plugin` in `gradle/libs.versions.toml`,
bump the `rev` in that crate's `Cargo.toml` too.

---

## 3. Architecture, top down

```
                 ┌──────────────────────────────────────────────────┐
                 │ apps/desktop  (Kotlin / Compose Multiplatform)   │
                 │  ── Screens, Routes (NavHost), WalletStore       │
                 └────────────────────────┬─────────────────────────┘
                                          │
                                          ▼  io.bhodl.ffi (KMP module)
                 ┌──────────────────────────────────────────────────┐
                 │ shared/  (KMP)                                   │
                 │  ── BhodlTheme, generated UniFFI Kotlin bindings │
                 └────────────────────────┬─────────────────────────┘
                                          │ JNA / cdylib
                                          ▼
                 ┌──────────────────────────────────────────────────┐
                 │ crates/bhodl-uniffi  (cdylib, Rust)              │
                 │  ── WalletHandle, Network, errors, FFI Records   │
                 └────────────────────────┬─────────────────────────┘
                                          │
                            ┌─────────────┴─────────────┐
                            ▼                           ▼
            ┌─────────────────────────┐   ┌───────────────────────────┐
            │ bhodl-wallet-onchain    │   │ bhodl-core                │
            │  OnchainAccount, BDK,   │   │  Network, Amount,         │
            │  SQLCipher store,       │   │  Argon2id KDF, salt gen,  │
            │  BIP-329 labels,        │   │  zeroize-on-drop helpers  │
            │  address book table,    │   │                           │
            │  fee estimator, etc.    │   │                           │
            └─────────────────────────┘   └───────────────────────────┘
```

### Data flow for "send 1000 sat to tb1q…"

1. SendScreen captures address + amount + fee tier.
2. `WalletHandle.buildSendTx(...)` (FFI) → `OnchainAccount::build_send_tx`
   inside the locked `Mutex` → BDK builds an unsigned PSBT, persists
   the change-keychain index update through to SQLCipher, returns the
   PSBT base64.
3. Compose navigates to `ReviewSendScreen` carrying the PSBT.
4. On Confirm: `WalletHandle.signPsbt` → BDK fills in the witness
   data using the descriptor's xpriv (loaded from the seed table on
   `unlock_persistent` or held in memory after `from_mnemonic`); then
   `WalletHandle.broadcastTx` POSTs the transaction to the configured
   Esplora endpoint.
5. `SendSuccessScreen` shows the txid and offers "Open in
   mempool.space".

### Data flow for the boot guard

`apps/desktop/.../App.kt` runs the boot guard once on first composition.
Three-priority lookup:

```
start = if (WalletStore.current() != null) Route.Dashboard
        else if (walletExists()) Route.Unlock
        else Route.Welcome

walletExists() == File(~/.bhodl/wallet.salt).isFile
              || File(~/.bhodl/wallet.kdf.json).isFile
```

1. **In-memory port** wins: when the user flips the language chip in
   Settings the inner `NavHost` is torn down and rebuilt (`Main.kt` →
   `App.kt` wraps it in `key(localeOverride)`) so every visible
   `stringResource` re-resolves against the new `Locale.getDefault()`.
   Without this branch the rebuild would re-evaluate the disk check
   and route the still-logged-in user back through Unlock, forcing a
   PIN re-entry. The `navController` is hoisted *outside* the
   `key(...)` block so its backstack — and therefore the user's
   current screen — survives the language flip.
2. **Disk marker** is the returning-user path. Either `wallet.salt` or
   `wallet.kdf.json` is sufficient — pre-hardening installs only have
   `wallet.salt`; post-migration / freshly-created wallets only have
   `wallet.kdf.json`. Misclassifying the latter as "no wallet" sent
   the user to Welcome, where Create / Restore would attempt to
   overwrite the encrypted DB and surface as an opaque NPE through
   the UniFFI binding.
3. **Neither** → first-time setup via Welcome.

`Unlock` calls `WalletHandle.unlockPersistent(dbDir, network, pin)`
which reads the salt → derives the AES-256 key via Argon2id → opens
SQLCipher → reads the seed back from the `bhodl_seed` table → rebuilds
the secret descriptors → constructs an `OnchainAccount` capable of
signing.

---

## 4. Crate-by-crate reference

### 4.1 `bhodl-core`

Pure-Rust crypto primitives, no I/O, no BDK. Re-exports:

| Item                              | Role                                              |
| --------------------------------- | ------------------------------------------------- |
| `Network` enum                    | Mainnet / Testnet4 / Signet / Regtest             |
| `Amount`, `AmountError`           | Sat-precise integer, conversion guards            |
| `KdfParams`, `CURRENT`, `LEGACY_OWASP_MIN` | Argon2id parameter versioning. CURRENT: m=64 MiB, t=3, p=4 (OWASP 2024 strong). LEGACY: m=19 MiB, t=2, p=1 for wallets without `wallet.kdf.json`. See key_derivation.rs. |
| `derive_key_from_pin`             | Argon2id with versioned params, 32-byte output    |
| `derive_key_from_pin_with_params` | Same, using explicit KdfParams (for migration)    |
| `generate_salt`                   | OS-RNG-backed 16-byte salt (uses `OsRng`, not     |
|                                   | `ThreadRng` — safer across JVM/JNI thread boundary)|
| `SALT_LEN`, `DERIVED_KEY_LEN`     | Layout constants, both consumed by the wallet     |

`#![deny(unsafe_code)]` workspace-wide; `bhodl-core` has zero `unsafe`
blocks.

**KDF migration policy**: On `unlock_persistent`, if `wallet.kdf.json`
does not exist, the FFI layer reads the salt, re-derives the key using
`LEGACY_OWASP_MIN`, opens SQLCipher, then writes a fresh `wallet.kdf.json`
with `CURRENT` params and calls `PRAGMA rekey` to upgrade the encryption
key in-place. No data loss; wallets with old parameters become strong
automatically on next unlock.

### 4.2 `bhodl-wallet-onchain`

The on-chain engine. Key types:

| Type                       | What it owns                                          |
| -------------------------- | ----------------------------------------------------- |
| `OnchainAccount`           | A `bdk_wallet::PersistedWallet<rusqlite::Connection>` |
|                            | + an optional master `Xpriv` (for the manual BIP-86   |
|                            | derivation path) + the `secp256k1` context.           |
| `DescriptorBackup`         | xpub-only descriptor pair (no secret) for export.     |
| `UtxoInfo`                 | (`outpoint`, `value`, `address`, `is_change`,         |
|                            | `confirmed`, `frozen`) — feeds the SendScreen         |
|                            | coin-control panel. `frozen` = BIP-329 `output` label |
|                            | with `spendable: false` (badge, not selectable).      |
| `TxRecord`                 | (`txid`, `net_sat`, `fee_sat`, `confirmation_height`, |
|                            | `timestamp`) — feeds the HistoryScreen.               |
| `FeeEstimateBuckets`       | (`economy`, `normal`, `priority`) sat/vB, fed by      |
|                            | Esplora `/fee-estimates`. Outlier-clamped to 500.     |
| `Label`, `LabelKind`       | BIP-329 record. `Tx / Addr / Output / Input / Xpub`.  |
| `Contact`                  | Local address book entry: `(id, label, address,       |
|                            | created_at_unix)`.                                    |

Public methods on `OnchainAccount` (current surface, both used internally
and re-exposed via FFI):

```
from_mnemonic / create_or_load_persistent / load_persistent
peek_taproot_address / reveal_next_address / balance
sync_with_esplora / fetch_fee_estimates
build_send_tx / build_send_tx_batch
build_send_tx_with_utxos / build_send_tx_with_outpoint_strings
build_fee_bump_psbt
sign_psbt / extract_signed_tx / psbt_fee_sat / broadcast_tx
list_utxos / list_transactions
add_label / get_label / list_labels / remove_label
export_labels_jsonl / import_labels_jsonl
add_contact / list_contacts / update_contact_label / remove_contact
export_descriptors
```

Key invariants:

- The SQLCipher database is the only on-disk store. `wallet.salt` (16
  bytes, plaintext) lives next to it under `~/.bhodl/`. Nothing else
  is persisted by this crate.
- BDK 3.0 deliberately persists only the **public projection** of the
  descriptors. To keep the unlock flow mnemonic-less we store the
  seed inside `bhodl_seed`, AES-256 encrypted by SQLCipher with the
  PIN-derived key. See §6 for the threat model.
- Returned `RustBuffer` strings are allocated by the cdylib and freed
  by JNA on the consumer side. The `+crt-static` flag in
  `.cargo/config.toml` keeps both ends of that handoff binary
  compatible — see `docs/uniffi-runtime.md` for the full incident
  log.

### 4.3 `bhodl-uniffi`

The FFI seam. One `WalletHandle` UDL Object owns a `Mutex<OnchainAccount>`
so the same handle can be safely shared across JVM threads.

Constructors (`#[uniffi::constructor]`):

| Method                              | Surface                                       |
| ----------------------------------- | --------------------------------------------- |
| `fromMnemonic(network, mnemonic, passphrase)` | In-memory wallet, no SQLCipher.    |
| `createOrLoadPersistent(dbDir, network, mnemonic, passphrase, pin)` | Open or create the PIN-encrypted wallet. Persists the seed on first create. |
| `unlockPersistent(dbDir, network, pin)`        | Re-open an existing wallet using the seed already stored in the DB. |

Methods (categorised):

| Group              | Methods                                                  |
| ------------------ | -------------------------------------------------------- |
| Address derivation | `peekAddressAt(index)`, `generateRandomMnemonic(count)`  |
| Sync / read state  | `balanceSat`, `syncWithEsplora`, `listUtxos`,            |
|                    | `listTransactions`, `fetchFeeEstimates`, `psbtFeeSat`    |
| Build / sign / broadcast | `buildSendTx`, `buildSendTxBatch`,                  |
|                    | `buildSendTxWithUtxos`, `buildFeeBumpPsbt`,              |
|                    | `signPsbt`, `broadcastTx`                                |
| Labels             | `setTxLabel`, `getTxLabel`, `removeTxLabel`              |
| Address book       | `addContact`, `listContacts`, `updateContactLabel`,      |
|                    | `removeContact`                                          |

Records and enums emitted by uniffi: `Network`, `WalletError` (one
variant per `OnchainError`), `FeeEstimates`, `TransactionRecord`,
`UtxoRecord`, `RecipientEntry`, `ContactRecord`.

`generateRandomMnemonic` lives on `WalletHandle` because of the
Windows ABI hazard documented in `docs/uniffi-runtime.md`. The desktop
wizard constructs a throwaway `WalletHandle` from a public BIP-86
test-vector mnemonic, calls the method, then discards it.

**Orphan-sqlite recovery in `createOrLoadPersistent`**. If the boot
guard sees no markers (no `wallet.kdf.json`, no `wallet.salt`) but
`wallet.sqlite` is still on disk — typical of an interrupted wipe on
Windows where the file lock survived the user's Erase tap — the
constructor's first `OnchainAccount::create_or_load_persistent` call
fails because the new PIN-derived key cannot decrypt the orphan DB.
The constructor then deletes the orphan and retries the open exactly
once with the same new key. Strictly an improvement: the orphan was
already unreachable (no salt = no possible key), so the worst-case
"recovery deletes something the user wanted" is impossible.

**Releasing the SQLCipher file handle on wipe**. The desktop wipe
path goes:

```
WalletStore.clear()
   → port.close()
      → handle.destroy()                 // UniFFI Disposable
         → Arc<WalletHandle> strong count drops to 0
            → Rust Drop on WalletHandle
               → Mutex<OnchainAccount> drops
                  → OnchainAccount Drop
                     → Connection drops → SQLCipher PRAGMA finalises
                        → Windows file lock released  ← synchronous
WalletPaths.from(dataDir).reset()        // now safe to File.delete()
```

UniFFI's auto-generated `Disposable.close()` on the JVM side decrements
the FFI-side Arc strong count synchronously. Without that call,
`File.delete()` on `wallet.sqlite` silently returns `false` because
the lock is still held — leaving an orphan that breaks the next
Restore attempt. See foot-gun §7.

### 4.4 `shared/` (Kotlin Multiplatform module)

- `nonNativeMain/kotlin/io/bhodl/ui/theme/BhodlTheme.kt` — the brand
  colours, typography, and `LocalBhodlColors` / `LocalBhodlTypography`
  CompositionLocals consumed by every screen.
- `commonMain/composeResources/values/strings.xml` — i18n root
  (English baseline, `values-it/` for Italian).
- `jvmTest/kotlin/io/bhodl/ffi/UniffiSmokeTest.kt` — the regression
  harness for the FFI. New FFI methods land with a smoke test here so
  the bindings round-trip is verified in seconds, not via running the
  full app.
- `jvmTest/kotlin/io/bhodl/ffi/*PortTest.kt` — unit tests for
  `WalletPort` / `UniffiWalletPort` implementations, exercising unlock,
  send, restore flows in isolation.

### 4.5 `apps/desktop/`

Compose Multiplatform JVM app, signet only. Navigation graph
(`navigation/Routes.kt`):

```
   Welcome ─┬─→ GenerateMnemonic ─→ ConfirmMnemonic ─→ PinSetup ─→ Dashboard
            ├─→ RestoreMnemonic ────────────────────→ PinSetup ─→ Dashboard
            ├─→ RestoreBackup (encrypted blob) ──────→ PinSetup ─→ Dashboard
            └─→ Unlock ──────────────────────────────→ Dashboard

   Dashboard ─┬─→ Send ─→ ReviewSend ─→ SendSuccess
              ├─→ History ─→ TransactionDetails / BumpFee ─→ SendSuccess
              ├─→ AddressBook
              ├─→ Settings (theme / language / Esplora endpoint / Show recovery phrase / Erase wallet)
              └─→ (Logout) ─→ Unlock
```

The `WalletStore` singleton (`wizard/WalletStore.kt`) owns the
unlocked `WalletHandle`. Logout clears it. A future iteration replaces
this with a Compose `LocalWalletHandle` CompositionLocal or Koin DI
once a second target (Android/iOS) needs the same bridge.

**Port abstraction**: `WalletPort` is an interface abstracting wallet
operations (`UniffiWalletPort` wraps the actual FFI, `FakeWalletPort`
provides test stubs). Every screen accepts a `wallet: WalletPort` parameter.
UI tests inject `FakeWalletPort` for fast, isolated verification of Compose
logic without hitting Rust/SQLCipher. See `io.bhodl.desktop.wallet` module.

---

## 5. Build, run, test

### 5.1 Prerequisites

| Tool                  | Version   | Why                                            |
| --------------------- | --------- | ---------------------------------------------- |
| Rust toolchain        | 1.88      | Pinned by `rust-toolchain.toml`                |
| JDK                   | 21        | Compose Multiplatform 1.10 / AGP 8.9 target    |
| Strawberry Perl       | 5.x       | Windows-only, builds vendored OpenSSL for SQLCipher |
| Git, gh CLI           | recent    | Repo + CI inspection                           |
| (Optional) MSYS2/MinGW| latest    | Required only for the `mingwX64` Kotlin/Native target — see `docs/spike-uniffi.md` |

`cargo` and `gradlew` install everything else on first build.

### 5.2 Common commands

```sh
# Cargo workspace
cargo check --workspace --all-targets
cargo clippy --workspace --all-targets -- -D warnings
cargo fmt --all --check
cargo test -p bhodl-core            # KDF tests
cargo test -p bhodl-wallet-onchain  # BDK + SQLCipher tests
cargo deny check                    # license + advisory audit

# Gradle / Kotlin
./gradlew :shared:jvmTest --tests "io.bhodl.ffi.UniffiSmokeTest"
./gradlew :apps:desktop:jvmTest --tests "io.bhodl.desktop.wizard.*"
./gradlew :apps:desktop:run         # launches the windowed app
```

The first cargo build of `bhodl-uniffi` on Windows takes ~10 minutes
(OpenSSL + SQLCipher vendored). Subsequent builds reuse the
incremental cache. The desktop run task triggers the cdylib build if
needed; if a previous DLL is stale (e.g. after a Rust source change
that gradle didn't pick up) delete the four cached locations under
`shared/build/...` and `target/x86_64-pc-windows-msvc/debug/` and
re-run.

### 5.2.1 Coverage

JVM (Kotlin / Compose) — Kover, applied at the root and aggregated
across `:shared` + `:apps:desktop`:

```sh
./gradlew koverHtmlReport         # build/reports/kover/html/index.html
./gradlew koverXmlReport          # build/reports/kover/report.xml
```

Generated UniFFI bindings, generated Compose Resources, the route
markers and the `*Tags` declaration objects are excluded from the
metric (configured in the root `build.gradle.kts`) so the number
tracks code we actually authored.

Rust — `cargo-llvm-cov`:

```sh
rustup component add llvm-tools-preview                    # one-shot
cargo install cargo-llvm-cov --locked                      # one-shot
cargo llvm-cov --workspace --html --output-dir target/coverage \
  --ignore-filename-regex 'crates[\\/](uniffi-runtime|bhodl-uniffi)[\\/]'
cargo llvm-cov report \
  --ignore-filename-regex 'crates[\\/](uniffi-runtime|bhodl-uniffi)[\\/]'
```

The two FFI crates are excluded from the headline number because
their coverage tracks "what the Kotlin caller happens to exercise,"
not the contract; `bhodl-core` and `bhodl-wallet-onchain` are the
crates whose coverage matters.

**Baseline (as of 2026-05-09, commit `3ea9f42`)**:

| Layer                     | LINE   | BRANCH | Notes                                |
| ------------------------- | ------ | ------ | ------------------------------------ |
| JVM aggregate (Kover)     | 55.9%  |   —    | Dashboard, History, BumpFee, ReviewSend covered via FakeWalletPort; Settings, RestoreBackup pending |
| → `io.bhodl.ui.theme`     | 91.5%  |   —    | Theme + typography                   |
| → `io.bhodl.desktop.wizard` | 93.2% |   —    | Bip21, MnemonicChallenge, PinValidator, WalletPaths |
| → `io.bhodl.desktop.screens` | 68.5% | —    | Dashboard, History, BumpFee, ReviewSend, AddressBook via FakeWalletPort; Settings, RestoreBackup, SettingsScreen pending |
| → `io.bhodl.desktop.wallet` | 90%+ | —    | UniffiWalletPort + FakeWalletPort near-complete |
| → `io.bhodl.desktop` (App + Main) | 0% | — | NavHost wiring; L3 integration tests cover end-to-end |
| Rust core (`bhodl-core`)  | ~95%   |   —    | Argon2id KDF (including migration), Amount, Network — small surface, well covered |
| Rust on-chain (`bhodl-wallet-onchain`) | 77% | — | BDK happy paths via `bdk_testenv`; contacts.rs now covered |
| `bhodl-uniffi`            | 21.0%  |   —    | Excluded from headline; covered indirectly by `:shared:jvmTest` + L3 integration tests |

Realistic targets to reach (Phase-2 closure):

- `bhodl-core`, `bhodl-wallet-onchain` ≥ 85% line.
- `io.bhodl.desktop.wizard` ≥ 90% (already there).
- `io.bhodl.desktop.screens` ≥ 85% (currently 69% — Settings + RestoreBackup tests in progress).
- `io.bhodl.desktop.wallet` ≥ 90% (nearly there with FakeWalletPort exhaustion).
- App entry / NavHost: stay at 0% intentionally; L3 integration tests cover end-to-end (bitcoind regtest + electrs).

Anything past those numbers is asymptotic — see "Cosa NON puoi
coprire al 100% con E2E" in the testing pyramid notes.

### 5.3 CI

`.github/workflows/ci.yml` runs:
- `cargo fmt --check`, `cargo clippy --all-targets -- -D warnings` (Linux)
- `cargo test --workspace` (Linux + macOS + Windows)
- `cargo-deny` (Linux; license + advisory + source allowlist audit)
- **`integration-test` job (Linux)**: Spins up Docker containers (bitcoind 28.0 regtest, electrs v3.2.0) via
  Testcontainers, runs `:apps:desktop:integrationTest` task, uploads test reports on failure.
  Pre-pulls container images to stay within GitHub Action timeouts.
  Blanks `ANDROID_HOME` / `ANDROID_SDK_ROOT` to avoid AGP+uniffi conflict
  (integration tests are JVM-only, not Android). See commit `a3ddea6` (gradlew exec bit) and `8765b32` (gradle.properties commonize fix).

`.github/workflows/tor.yml` (**Tor (live)**, nightly + `workflow_dispatch`, never on PRs):
installs `tor` on the runner, waits until `check.torproject.org` reports `IsTor`, then runs
`cargo test -p bhodl-wallet-onchain --features tor-tests tor_live` and
`./gradlew :apps:desktop:torTest` with `BHODL_TOR_REQUIRED=1`. Locally:
`scripts/tor-tests.sh` (on Windows, Tor installed in WSL2 is reachable at `127.0.0.1:9050`
through WSL localhost forwarding). Test inventory: test-book §46.

Every commit on `main` must be CI-green across all jobs before the next change lands.

### 5.4 Smoke testing the wallet end to end on signet

1. `./gradlew :apps:desktop:run`.
2. Welcome → Create new wallet → at the top of GenerateMnemonic pick
   the network (Signet default; Testnet4 / Regtest also available;
   Mainnet absent from the UI in Phase 2). Write down the 12 words →
   enter the challenge words → set a PIN. On "Advanced" toggle,
   optionally set a BIP-39 passphrase. After PIN setup `~/.bhodl/`
   contains `wallet.sqlite`, `wallet.kdf.json` and `wallet.network`.
3. Dashboard → **Receive**. The screen consumes one derivation index and
   shows the fresh `tb1p…` address with a QR. Optionally type an amount
   (sat) and / or a label — both flip the QR + URI to a full BIP-21
   `bitcoin:addr?amount=…&label=…`. "Copy URI" copies the BIP-21 form;
   "Copy address" copies the bare address; "Generate new address"
   reveals + persists the next index.
4. Visit `https://signetfaucet.com/` and request 0.0001 BTC. Wait 1–3
   minutes — block times on signet are stochastic.
5. Back to Dashboard → tap Sync. Balance updates to ~10000 sat. Banner
   "Restoring wallet…" disappears after first post-create sync.
6. Send to yourself (Receive → Copy address, then Send → paste): 1000
   sat, Normal fee. Review → Confirm and send. SendSuccess shows the txid.
7. History → see the pending tx. Tap "Details" for transaction summary.
   Tap "Bump fee" → enter 5 sat/vB → Confirm and replace.
8. Sync again → balance reflects the spent fee. Background auto-sync
   (every 60s while app is focused) keeps balance fresh.
9. Settings → toggle Dark/Light/System theme, toggle language (IT/EN),
   override Esplora endpoint if desired, tap "Show recovery phrase" (two-tap
   reveal, blur on unfocus), or "Erase wallet" (two-tap wipe).

**L3 integration test** (regtest, no faucet needed):
```sh
./gradlew :apps:desktop:integrationTest
```
Runs `IntegrationTest.kt` scenarios: wallet create → sync on regtest bitcoind → send + mine block → verify balance. Testcontainers manages lifecycle.

---

## 6. Security model

This is a self-custodial wallet. The user's funds are protected by:

1. **Process-memory hygiene**: the master `Xpriv` lives behind
   `OnchainAccount.xpriv: Option<Xpriv>` and is `non_secure_erase()`d
   on `Drop`. `non_secure_erase` is the secp256k1 crate's compiler-
   fence-aware memset-to-zero — best effort, not a hard guarantee
   against memory dumps that catch it before drop.
2. **Encryption at rest** via SQLCipher (AES-256-CBC). The encryption key is the
   32-byte output of `derive_key_from_pin(pin, salt)` using **Argon2id with
   OWASP 2024 strong parameters** (m=64 MiB, t=3, p=4). This costs ~500–800 ms
   per unlock on 2024 desktop hardware and ~50–800 ms per brute-force guess
   (4-digit PIN: ~10 min single-threaded, 6-digit: ~14 hours). The salt is
   persisted next to the DB in plaintext; it is not a secret, but losing it
   locks the user out forever.
   
   **KDF migration**: Wallets created with older parameters (m=19 MiB, t=2, p=1)
   are detected by absence of `wallet.kdf.json` and automatically re-keyed to
   strong parameters on next unlock. The FFI layer calls `PRAGMA rekey` in-place;
   no data loss.
3. **No mnemonic on disk except inside the encrypted DB**. The
   `bhodl_seed` table holds `(mnemonic, passphrase)` so the
   mnemonic-less unlock path can rebuild signing-capable descriptors;
   the threat model is identical to "an attacker has the SQLCipher
   file and the salt": they still need to brute-force the PIN through
   Argon2id (~50–800 ms per attempt). **BIP-39 passphrases** (optional,
   behind Advanced toggle on first setup) are stored encrypted at rest in the
   `bhodl_seed` table alongside the mnemonic. The threat model is identical to
   the mnemonic itself: an attacker with the SQLCipher file + salt + PIN can read both.
   Re-entering the same passphrase on a fresh restore from mnemonic derives the same keys (BIP-39 standard);
   on unlock of an existing wallet, the passphrase is read out of the encrypted DB, not re-prompted.
4. **Backup & export** via age encryption (age-cli compatible). Users can
   export the seed backup from Settings, which outputs an encrypted blob
   (~3 KB) and a user-chosen password. Restore flow: Welcome → Restore from
   Backup → paste blob + password → auto-derive mnemonic → continue with PIN setup.
   Encrypted backup format allows disaster recovery without exposing plaintext seed
   on the recipient device.
5. **Network privacy** (opt-in). Desktop app defaults to `mempool.space/signet`
   for sync, fee estimates, and broadcast. Users can override the Esplora endpoint
   in Settings, or enable Tor opt-in (SOCKS5 proxy) to route all requests through
   a local Tor daemon. Network requests are not tracked by default on signet;
   mainnet launch will gate auto-sync behind user consent and promote self-hosted
   / Tor options. Tracked in source comments tagged `Privacy follow-up`.
   With a proxy configured the Esplora client uses a 90 s request timeout
   (clearnet: 30 s) because Tor circuits can stall 20-35 s before the
   connection is up.
   Historical BTC rates: CoinGecko's keyless API only serves the last 365
   days (older requests answer HTTP 401), so dates older than that are
   resolved through blockchain.info + Frankfurter and CoinGecko is never
   asked about them.

What the wallet does NOT defend against (Phase 2 acceptance):

- Process-memory dumps captured while the wallet is unlocked (the
  mnemonic is held in memory and zeroized on drop, but a determined
  attacker with memory access can extract it before drop).
- Hardware-backed key wraps (Keystore on Android, Secure Enclave on
  iOS, OS keyring on desktop). These land alongside the corresponding
  platform target (Phase 3.5+).
- Coin-join / privacy mixing. Coin control + descriptor backup are
  the building blocks; CoinJoin / PayJoin land in a later phase (Phase 4+).
- Hardware wallet signing (Trezor, Ledger, Coldcard). Architectural
  fit is fine — `OnchainAccount::sign_psbt` is already PSBT in / PSBT
  out — but the HWI integration is Phase 3.5+.
- Remote attacks on the user's own infrastructure (self-hosted nodes,
  the user's OS, etc.). The wallet trusts the configured Esplora endpoint
  and the user's local Tor daemon (if Tor opt-in is enabled).

The full Phase-2 threat model (what we accept, what we punt) is the
docstring on `bhodl-wallet-onchain/src/account.rs::OnchainAccount`.

### 6.1 Upgrading / downgrading

Upgrading is automatic; **downgrading is not supported**. The first
unlock with a build that contains the 2026-10 security-audit fixes
rewrites the on-disk data in formats older builds do not understand:

- the KDF descriptors (`wallet.kdf.json`, `registry.kdf.json`) are
  re-sealed as **v2** (HMAC over salt + params, keyed with the derived
  key) — `crates/bhodl-uniffi/src/commonMain/rust/kdf.rs`;
- the registry is migrated to **schema v4** (`PRAGMA user_version`;
  v2 lot `original_quantity`, v3 crypto-kind `user_set`, v4 wallet
  `mirror_of`) in one transaction — `registry.rs::ensure_schema`.

After that, an older build cannot open the data: it rejects the v2
descriptor (typically reported as a wrong PIN / integrity error — do not
keep retrying, it counts towards that build's lockout). Going back means
restoring from the seed or an encrypted backup into a fresh data dir with
the old build.

This build and later ones refuse data written by a **newer** build
instead of guessing: a registry stamped with a higher schema version
fails with "registry schema version N is newer than this app supports",
an unknown KDF descriptor version with "unsupported kdf descriptor
version N". The desktop app classifies both as
`PinFailureKind.NewerVersion` (`wizard/PinErrors.kt`): the unlock screens
show "This data was written by a newer version of BHODL … Update BHODL",
and the attempt is not counted as a wrong PIN.

---

## 7. Known foot-guns and their workarounds

These are the surprises someone changing this code today is most
likely to bump into. Each has its own incident report under `docs/`
linked from this list.

| Hazard                                                              | Where      | Workaround                              |
| ------------------------------------------------------------------- | ---------- | --------------------------------------- |
| `RustBuffer` cross-DLL free crashes on Windows (mismatched CRTs)    | `docs/uniffi-runtime.md` | `+crt-static` for MSVC in `.cargo/config.toml` |
| Free fns + `Result<String, _>` crash with "Invalid memory access"   | `docs/uniffi-runtime.md` | All such functions live as methods on `WalletHandle` |
| `installBindgen` on Windows produces `…multiplatform.exe` but the BuildBindingsTask looks for the bare name | `docs/spike-uniffi.md`   | Manually copy the `.exe` to a no-extension twin (PowerShell `Copy-Item -Force`) |
| `mempool.space` signet `/fee-estimates` occasionally reports 65k sat/vB | `crates/bhodl-wallet-onchain/src/account.rs::float_sat_to_u64` | Outlier clamp at 500 sat/vB; if the priority bucket lands on the cap we return 1 sat/vB across all buckets |
| `bdk_testenv`'s `electrsd` build script is Unix-only                | `Cargo.toml` | `[target.'cfg(unix)'.dev-dependencies]` |
| Wrong-PIN unlock surfaces as `NullPointerException` instead of `WalletException.Storage` | `shared/src/jvmTest/.../UniffiSmokeTest.kt` | `@Ignore`d test — pending a `catch_unwind` either upstream in uniffi or in our `load_persistent` wrapper |
| `integrationTest` job fails with "Task 'packageDebugResources' not found" on ubuntu-latest   | `.github/workflows/ci.yml` (integration-test env) | Pre-installed Android SDK on GitHub Actions conflicts with AGP 8.9 task names. Blank `ANDROID_HOME` + `ANDROID_SDK_ROOT` to disable Android detection. |
| `gradle commonize stub` fails during desktop build                  | `settings.gradle.kts` | `gradle.beforeProject { System.setProperty("kotlin.mpp.enableCInteropCommonization", "true") }` ensures the commonize stub is registered early (before the Ubique uniffi plugin runs). |
| Boot guard misclassifies KDF-migrated wallets as "no wallet" → opaque NPE on PinSetup | `apps/desktop/.../wizard/WalletPaths.kt::walletExists` | `walletExists()` accepts EITHER `wallet.salt` OR `wallet.kdf.json`. New wallets only have the latter; missing this branch routed users to Welcome → Create → BDK panic on existing DB. |
| Send MAX rendered "0 sat" in ReviewSend                             | `apps/desktop/.../screens/SendScreen.kt` | The drain PSBT is built server-side; the SendScreen passed `0` as the navigation argument because it had nothing to put there. Compute `balance - psbtFeeSat(psbt)` after build and forward that as the effective amount. |
| Compose UI test wiped the developer's real `~/.bhodl/` every `:jvmTest` run | `apps/desktop/.../screens/SettingsScreen.kt::WipeSection` | `WipeSection`'s onClick hard-coded `WalletPaths.defaultDataDir()`. The `Wipe is two-tap…` Compose test clicked WIPE on the real path, silently destroying the dev wallet every CI run. **Rule**: any destructive composable (wipe, reset, delete) MUST accept the target path / view as a parameter with a production-default; tests pass a tempdir. Applied defensively to `WelcomeScreen.dataDir` too. |
| `wallet.sqlite` survives Erase wallet as an orphan; next Restore fails with "Wallet creation failed" | `crates/bhodl-uniffi/src/.../lib.rs` (recovery branch) + `apps/desktop/.../wallet/WalletPort.kt::close` | Windows holds the SQLCipher file lock until the JVM GC finalises the wrapping `Arc<WalletHandle>` — undefined latency. `WalletStore.clear()` now calls `handle.destroy()` synchronously before `WalletPaths.reset()` runs, releasing the lock. Belt-and-braces: `createOrLoadPersistent` also auto-deletes the orphan and retries once if the markers were absent. |
| Recover NPE on wrong PIN surfaces as "Unlock failed." instead of "Wrong PIN." | `shared/build/generated/uniffi/...::unlockPersistent` | Ubique UniFFI binding generator emits `(...)!!` *inside* the `uniffiRustCallWithError` lambda, so an FFI null pointer (Rust error path) throws NPE before `uniffiCheckCallStatus` reads the error code. Wrong PIN → NPE bubbles up to `WalletException.Storage` matcher → mismatch → fallback "Unlock failed." string. Upstream fix needed; for now, the JVM side maps WalletException.Storage to "Wrong PIN." but cannot catch the NPE in the same way. |

When you add code, document new foot-guns inline (`# Why` comments)
or as a new entry in this table.

---

## 8. Roadmap pointers

The current sprint (Phase 2 on-chain) is feature-complete on signet.
Open items in priority order:

1. **Phase 2 hardening & test coverage** (in progress)
   - Remaining UI screen coverage (Settings, RestoreBackup via FakeWalletPort).
   - `bhodl-wallet-onchain` ≥ 85% line coverage.
   - E2E integration tests with regtest (Testcontainers) covering happy paths + error recovery.
2. Hardware wallet signing path (Trezor / Ledger / Coldcard via HWI)
   — completes the on-chain story for security-conscious users (Phase 3).
3. **Phase 3: Lightning**. LDK / ldk-node, channel state machine, signet
   first invoice. See `PLAN.md` for scope.
4. Phase 4: Tracking. Historical balance, BIP-329-driven categorisation,
   fiat conversion, CSV export.
5. Phase 5: Multi-wallet + L2 (Liquid, Cashu / Fedimint, Ark).

All roadmap decisions live in `PLAN.md`.

---

## 9. Where to look when something breaks

| Symptom                                            | First place to check                                |
| -------------------------------------------------- | --------------------------------------------------- |
| `cargo build` slow (~10 min) on a clean tree       | Normal — vendored OpenSSL + SQLCipher rebuild      |
| Kotlin says `unresolved reference 'uniffi.runtime'`| Stale generated bindings; delete `shared/build/generated/uniffi/` and rerun |
| `java.lang.Error: Invalid memory access` at startup | CRT mismatch — see §7. Re-check `.cargo/config.toml`|
| Wrong PIN says "Wrong PIN" but you're sure it's right | Likely DB created by a pre-seed-table version. From Welcome → "Reset local wallet" (two-tap) → Create new wallet |
| Signet faucet says "queued in 0 seconds" but no tx arrives | `signetfaucet.com` queue lag. Wait 2–5 min and check `mempool.space/signet/address/<addr>`; try `alt.signetfaucet.com` if needed |
| BDK `sign_psbt` returns "PSBT signed but not fully finalised" | `SignOptions::default().trust_witness_utxo = false`. We've explicitly set it to `true`; if you reverted that, taproot signing breaks |
| CI clippy fails on doc comments                    | The `clippy::doc_markdown` and `clippy::doc_lazy_continuation` lints; backtick technical names (`SQLCipher`, `BDK`, etc.) and avoid `+ word` paragraph leaders that look like Markdown lists |
| `~/.bhodl/` mysteriously empty after running `:jvmTest`, but no wipe was tapped | Compose UI test that exercises a destructive composable without an injection seam. Grep for `WalletPaths.defaultDataDir()` in `apps/desktop/.../screens/` — any destructive caller (wipe/reset) MUST forward an injected path so tests target a tempdir. See foot-gun §7 row "Compose UI test wiped the developer's real `~/.bhodl/`". |
| Wipe completed but Restore says "Wallet creation failed" / unlock NPEs | Orphan `wallet.sqlite` left over from a wipe that did not call `handle.destroy()` first. From PowerShell: `Get-ChildItem $env:USERPROFILE\.bhodl` — if only `wallet.sqlite` is present, manually `Remove-Item` it OR rely on the auto-recovery branch in `createOrLoadPersistent` to handle it on the next Restore attempt. |

---

## 10. Glossary

- **BIP-86**: single-key-path taproot, the descriptor shape this
  wallet uses. `m/86'/coin'/0'/keychain/index`.
- **BIP-125**: opt-in replace-by-fee. Every PSBT we build is RBF-able
  by default in BDK 3.0; the `BumpFeeScreen` rebuilds a higher-fee
  replacement for any pending tx.
- **BIP-329**: portable wallet labels. We store labels in a SQLCipher
  table `bhodl_labels` keyed by `(label_type, target)`.
- **BIP-21**: `bitcoin:` URI scheme. Recognised when pasted into the
  SendScreen address field; generated on the ReceiveScreen with
  optional `amount` (BTC, derived from sat) and `label` (UTF-8
  percent-encoded). Empty extras → bare `bitcoin:<address>` so the
  QR stays compact in the common case.
- **BDK**: bitcoindevkit. Provides the `Wallet` engine, descriptor
  store, coin selection, PSBT builder, and Esplora client we depend
  on.
- **UniFFI**: Mozilla's Rust-to-foreign-language bridge. We use
  `uniffi-rs 0.28.3` plus the Ubique KMP fork of the Kotlin binding
  generator.
- **Signet**: Bitcoin testnet variant with deterministic, signed
  blocks. Block times are typically 1–15 min.
- **SQLCipher**: AES-256-CBC encryption layer over SQLite. Key is
  passed via `PRAGMA key`; we feed it the Argon2id-derived 32-byte
  output.
- **`OsRng`** vs `ThreadRng`: we use `rand::rngs::OsRng` (stateless,
  goes straight to `getrandom`) instead of `rand::rng()`'s ThreadRng,
  because ThreadRng's thread-local state breaks across JNI thread
  boundaries.
