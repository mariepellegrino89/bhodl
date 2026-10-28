# BHODL — Developer Handbook

> Snapshot: 2026-10-23, watch-only refactor. BHODL no longer holds keys or sends bitcoin; signing, seeds, PSBT, PayJoin and encrypted seed backups were removed. Earlier snapshots (Phase 2 on-chain signing wallet) are in git history.

This document is the single document a new contributor should read end
to end before touching any code. It collects the architecture, the
component reference, the build & run instructions, the security model,
and the active roadmap. Companion documents under `docs/` cover
specific spikes (`docs/spike-uniffi.md`, `docs/uniffi-runtime.md`,
`docs/research/*`) — link to them rather than repeat their content.

---

## 1. What BHODL is

BHODL is a **watch-only** Bitcoin wallet with a net-worth and spending
tracker built in, written in Rust + Kotlin Multiplatform + Compose
Multiplatform. It never holds private keys and cannot send bitcoin:
wallets are added from public material only, and spending stays in the
user's signing wallet (hardware wallet, Sparrow, phone…). The current
shipping target is **desktop (JVM)**. The codebase is laid out so the
same shared module can drive Android, iOS, and (eventually) Web (Wasm)
once each platform target is wired up.

What exists today:

1. **Watch-only wallets**, three kinds:
   - **xpub** — account-level xpub at BIP-86 / 84 / 49 (SLIP-132
     `zpub` / `ypub` accepted);
   - **HD descriptor** — master fingerprint + derivation path + xpub
     (Sparrow / Specter export);
   - **address list** — explicit addresses of any type, no derivation.

   (The desktop add form currently takes xpubs only; HD-descriptor and
   address-list wallets are reachable through the FFI.) All three work on Mainnet, Signet, Testnet4 and Regtest. Sync goes
   through Esplora, optionally over Tor (recommended on mainnet, or a
   self-hosted Esplora). Per wallet: balance and history, receive-address
   derivation with label-first receive (the label / category set on an
   address is copied onto the payment that arrives), BIP-21 URIs and QR,
   address list, read-only UTXO list, BIP-329 labels with JSONL import /
   export, local address book, public descriptor export. Address-list
   wallets cannot derive, so they have no Receive tab.
2. **Net-worth and spending tracker** across all watch wallets plus
   investments (tax lots, FIFO / HIFO / Specific-ID), manual assets and
   liabilities, budgets, bills, recurring detection, rules, goals,
   forecast, reports, CSV export. Bitcoin is the unit of account
   ("Bitcoin Lens").
3. **Next**: tracking of Lightning, Liquid, Cashu / Fedimint and Ark
   balances (see `PLAN.md`). Any signing or spending feature is out of
   scope for this app.

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
│   ├── bhodl-wallet-onchain/        # BDK watch-only wallets, SQLCipher persistence, registry, Tor
│   ├── bhodl-uniffi/                # FFI surface (Kotlin/Swift bindings)
│   └── uniffi-bindgen-kmp/          # Kotlin Multiplatform bindgen CLI (locked build)
├── shared/                          # KMP module — Compose theme, FFI consumer
│   └── src/jvmTest/                 # JVM smoke tests against the cdylib
├── apps/
│   └── desktop/                     # Compose Multiplatform JVM app
└── docs/
    ├── handbook.md                  # ← you are here
    ├── test-book.md                 # Manual QA scenarios
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
                 │  ── Screens, Routes (NavHost), RegistryStore,    │
                 │     WalletStore / WalletPort                     │
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
                 │  ── WatchHandle, RegistryHandle, Network,        │
                 │     WalletError, FFI Records                     │
                 └────────────────────────┬─────────────────────────┘
                                          │
                            ┌─────────────┴─────────────┐
                            ▼                           ▼
            ┌─────────────────────────┐   ┌───────────────────────────┐
            │ bhodl-wallet-onchain    │   │ bhodl-core                │
            │  WatchAccount, BDK,     │   │  Network, Amount,         │
            │  SQLCipher store,       │   │  Argon2id KDF, salt gen,  │
            │  Registry (wallets +    │   │  PIN policy               │
            │  tracker tables),       │   │                           │
            │  BIP-329 labels,        │   │                           │
            │  address book, Tor      │   │                           │
            └─────────────────────────┘   └───────────────────────────┘
```

### Data flow for "add an xpub and see its balance"

1. `AddWatchScreen` captures label, script type (Taproot / Native
   SegWit / Nested SegWit → BIP-86 / 84 / 49), network and the xpub.
   HD-descriptor and address-list wallets are supported by the core and
   the FFI (`addWatchHd`, `addWatchAddresses`) but the desktop form does
   not offer them yet.
2. `RegistryHandle.addWatchXpub(...)` (FFI) builds a `WatchAccount`
   from the public descriptors, writes its encrypted
   `watch-state/<uuid>.sqlite` first and the registry row last, so a
   failure never leaves a row without state.
3. The tracker's wallet list (Tracker HOME) shows the new row. Clicking
   it navigates to `Route.OpenWallet(id)`, which calls
   `RegistryHandle.openWatch(id)` (device key, no extra PIN), parks the
   `WatchHandle` in `WalletStore` and opens the wallet workspace.
4. `WatchHandle.syncWithEsplora(...)` → `WatchAccount::sync_with_esplora`
   runs a full scan (gap limit) against the configured Esplora endpoint,
   through the SOCKS5 proxy when Tor is on, and persists the BDK
   `ChangeSet` to the watch-state file.
5. Balance, history, addresses and UTXOs are read back from the
   in-memory BDK wallet. Incoming payments to an address that carries a
   receive label get that label / category copied onto the transaction
   (`apply_receive_labels`), which the tracker then picks up.

### Data flow for the boot guard

`apps/desktop/.../App.kt` computes the start destination once on first
composition:

```
start = if (WalletStore.current() != null && RegistryStore.current() != null) Route.Dashboard
        else if (RegistryStore.current() != null)  Route.Tracking()
        else bootGuardTarget()

bootGuardTarget() = when {
    registry.sqlite exists            → Route.DevicePinUnlock
    only seed-era data on disk        → Route.LegacyCleanup
    else                              → Route.Welcome
}
```

1. **In-memory handles** win: when the user flips the language chip in
   Settings the inner `NavHost` is torn down and rebuilt (`Main.kt` →
   `App.kt` wraps it in `key(localeOverride)`) so every visible
   `stringResource` re-resolves against the new `Locale.getDefault()`.
   Without this branch the rebuild would re-evaluate the disk check
   and route the still-unlocked user back through the PIN gate. The
   `navController` is hoisted *outside* the `key(...)` block so its
   backstack — and therefore the user's current screen — survives the
   language flip.
2. **Registry on disk** is the returning-user path:
   `DevicePinUnlock` → (`LegacyCleanup` if seed-era data is still
   around, see §6.1) → `Tracking`.
3. **Only seed-era data** (a pre-watch-only install without a registry)
   → `LegacyCleanup`.
4. **Nothing** → first-time setup: `Welcome` → `DevicePinSetup` →
   `AddWatch` → `Tracking`.

`DevicePinUnlock` calls `RegistryHandle.createOrLoad(dataDir, pin)`,
which reads `registry.kdf.json` (salt + Argon2id params + MAC), derives
the SQLCipher key, opens `registry.sqlite` and keeps the derived key in
the handle (zeroized on drop) to open each `watch-state/<uuid>.sqlite`.

---

## 4. Crate-by-crate reference

### 4.1 `bhodl-core`

Pure-Rust crypto primitives, no I/O, no BDK. Re-exports:

| Item                              | Role                                              |
| --------------------------------- | ------------------------------------------------- |
| `Network` enum                    | Mainnet / Testnet4 / Signet / Regtest             |
| `Amount`, `AmountError`           | Sat-precise integer, conversion guards            |
| `KdfParams`, `CURRENT`, `LEGACY_OWASP_MIN` | Argon2id parameter versioning. CURRENT: m=64 MiB, t=3, p=4 (OWASP 2024 strong). `LEGACY_OWASP_MIN` (m=19 MiB, t=2, p=1) is the lower bound `validate_bounds` accepts. See key_derivation.rs. |
| `KDF_MAX_*_COST`                  | Upper bounds checked before deriving (a tampered descriptor cannot force a huge allocation) |
| `derive_key_from_pin`             | Argon2id with versioned params, 32-byte output    |
| `derive_key_from_pin_with_params` | Same, using explicit KdfParams (for migration)    |
| `generate_salt`                   | OS-RNG-backed 16-byte salt (uses `OsRng`, not     |
|                                   | `ThreadRng` — safer across JVM/JNI thread boundary)|
| `MIN_NEW_PIN_LEN`, `validate_new_pin` | New-PIN policy (≥ 6 digits) enforced on a fresh registry |
| `SALT_LEN`, `DERIVED_KEY_LEN`     | Layout constants, both consumed by the wallet     |

`#![deny(unsafe_code)]` workspace-wide; `bhodl-core` has zero `unsafe`
blocks.

**KDF policy**: only the device-PIN registry has a KDF descriptor
(`registry.kdf.json`, `crates/bhodl-uniffi/src/commonMain/rust/kdf.rs`).
A fresh registry is created with `CURRENT` parameters; an existing
descriptor is accepted as long as its parameters are within
`[LEGACY_OWASP_MIN, KDF_MAX_*]`. `kdf.rs` still honours a
`registry.kdf.json.next` descriptor left by an interrupted KDF migration
of an earlier build (written before `PRAGMA rekey`, promoted on the next
unlock). There is no per-wallet KDF any more (`wallet.kdf.json` / `wallet.salt`
belong to seed-era builds and are only ever deleted, §6.1).

### 4.2 `bhodl-wallet-onchain`

The watch-only engine and the registry. Key types:

| Type                       | What it owns                                          |
| -------------------------- | ----------------------------------------------------- |
| `WatchAccount`             | A `bdk_wallet::PersistedWallet<rusqlite::Connection>` |
|                            | built from public descriptors (xpub / HD) or a script |
|                            | list (address wallets). `src/watch.rs`.               |
| `WatchBip`                 | Descriptor template for xpub wallets: BIP-86 / 84 / 49.|
| `Registry`                 | `registry.sqlite`: wallet list (`WalletKind::WatchXpub`, |
|                            | `WatchHd`, `WatchAddresses`) + every tracker table.   |
| `DescriptorBackup`         | xpub-only descriptor pair (no secret) for export.     |
| `DerivedAddress`           | Address row for the Addresses tab (index, keychain,   |
|                            | used / unused, label).                                |
| `ReceiveLabelCopy`         | Result of copying a receive label onto a payment.     |
| `UtxoInfo`                 | (`outpoint`, `value`, `address`, `is_change`,         |
|                            | `confirmed`, …) — feeds the read-only UTXOs tab.      |
| `TxRecord`                 | (`txid`, `net_sat`, `fee_sat`, `confirmation_height`, |
|                            | `timestamp`) — feeds the Transactions tab.            |
| `Label`, `LabelKind`       | BIP-329 record. `Tx / Addr / Output / Input / Xpub`.  |
| `Contact`                  | Local address book entry: `(id, label, address,       |
|                            | created_at_unix)`.                                    |

Shared helpers (descriptor parsing, address / UTXO / tx mapping, the
`ENCRYPTION_KEY_LEN` constant) live in `src/common.rs`.

Public methods on `WatchAccount` (current surface, re-exposed via FFI):

```
create_from_xpub / create_from_hd / create_from_addresses / load
network / balance / list_transactions
sync_with_esplora / start_esplora_scan / apply_esplora_scan
peek_address / is_address_list
reveal_next_address / next_unused_receive_address / list_addresses
set_address_label / apply_receive_labels
list_utxos / chain_tip_height
add_label / get_label / list_labels / remove_label
export_labels_jsonl / import_labels_jsonl
add_contact / list_contacts / update_contact_label / remove_contact
export_descriptors
```

Address-list wallets return an error from the derivation methods
(`is_address_list()` tells the UI to hide Receive).

Key invariants:

- Everything on disk is SQLCipher, encrypted with the key derived from
  the device PIN: `registry.sqlite` (wallet list + tracker tables) and
  one `watch-state/<uuid>.sqlite` per wallet (BDK state, labels,
  contacts). Only `registry.kdf.json` (salt, params, MAC) is plaintext.
- No private key material is ever created, imported or stored.
  `bip39` is a dev-dependency only (test vectors).
- Legacy `signing` rows left by older builds are hidden from
  `list_wallets` and removed by `purge_legacy_signing_wallets`; watch
  rows that mirrored them stay as normal watch wallets.
- Returned `RustBuffer` strings are allocated by the cdylib and freed
  by JNA on the consumer side. The `+crt-static` flag in
  `.cargo/config.toml` keeps both ends of that handoff binary
  compatible — see `docs/uniffi-runtime.md` for the full incident
  log.

### 4.3 `bhodl-uniffi`

The FFI seam. Two `#[uniffi::Object]`s, each owning a `Mutex` around its
Rust value so the handle can be shared across JVM threads.

**`WatchHandle`** — an open watch wallet. It is deliberately the
**first** `#[uniffi::Object]` in `lib.rs`: only the first object's
methods can return `Result<String, _>` safely on Windows (see §7 and
`docs/uniffi-runtime.md`). Obtained from `RegistryHandle.openWatch(id)`
(after one of the `addWatch*` calls for a new wallet); it has no public
constructor.

| Group              | Methods                                                  |
| ------------------ | -------------------------------------------------------- |
| Sync / read state  | `balanceSat`, `listTransactions`, `syncWithEsplora`,     |
|                    | `listUtxos`, `chainTipHeight`                            |
| Addresses          | `peekAddressAt`, `isAddressList`, `nextReceiveAddress`,  |
|                    | `nextUnusedReceiveAddress`, `listAddresses`,             |
|                    | `setAddressLabel`, `applyReceiveLabels`                  |
| Labels (BIP-329)   | `setTxLabel`, `getTxLabel`, `setTxCategory`,             |
|                    | `getTxCategory`, `removeTxLabel`, `exportLabelsJsonl`,   |
|                    | `importLabelsJsonl`                                      |
| Address book       | `addContact`, `listContacts`, `updateContactLabel`,      |
|                    | `removeContact`                                          |
| Export             | `exportPublicDescriptor`                                 |

**`RegistryHandle`** — the device-PIN registry.

| Group              | Methods                                                  |
| ------------------ | -------------------------------------------------------- |
| Open               | `createOrLoad(dataDir, devicePin)` (constructor)         |
| Wallets            | `listWallets`, `getWallet`, `addWallet`, `renameWallet`, |
|                    | `removeWallet` (row + metadata in one transaction, then  |
|                    | the `watch-state/<uuid>.sqlite` file), `isDescriptorTracked` |
| Watch wallets      | `addWatchXpub`, `addWatchHd`, `addWatchAddresses`,       |
|                    | `openWatch`, `listWatchEvents`, `aggregateWatchBalance*` |
| Legacy cleanup     | `legacySigningWalletCount`, `purgeLegacySigningWallets`  |
|                    | (rows + `wallets/<uuid>/` key directories)               |
| Tracker            | categories, crypto-event kinds, saved views, budgets,    |
|                    | bills, goals, liabilities, manual assets, rules,         |
|                    | recurring, investments / lots / sells, BTC rates,        |
|                    | net-worth history                                        |

Records and enums emitted by uniffi include `Network`, `WalletError`
(one variant per `OnchainError`), `TransactionRecord`, `UtxoRecord`,
`AddressRecord`, `ReceiveLabelCopyRecord`, `ContactRecord`,
`PublicDescriptorBundle`, `PaymentEvent`, `WalletRecordFfi` and the
`Tracking*Ffi` records.

Removed with the watch-only refactor: `WalletHandle` and everything on
it (seed constructors, PSBT build / sign / broadcast, fee estimates,
`generateRandomMnemonic`), `matches_device_pin`, `mark_pins_warned`,
`add_watch_xpub_mirror`, `run_migration_if_needed`, `decrypt_backup`.

**Releasing the SQLCipher file handle**. Before removing a wallet or
wiping the data dir, the desktop app closes the open `WatchHandle`:

```
WalletStore.clear()
   → port.close()
      → handle.destroy()                 // UniFFI Disposable
         → Arc<WatchHandle> strong count drops to 0
            → Rust Drop → Mutex<WatchAccount> drops
               → Connection drops → Windows file lock released  ← synchronous
RegistryHandle.removeWallet(id) / LocalWipe.wipeAll(dataDir)
```

UniFFI's auto-generated `Disposable.close()` on the JVM side decrements
the FFI-side Arc strong count synchronously. Without that call, deleting
the SQLCipher file on Windows silently fails because the lock is still
held until the JVM GC finalises the wrapper.

### 4.4 `shared/` (Kotlin Multiplatform module)

- `nonNativeMain/kotlin/io/bhodl/ui/theme/BhodlTheme.kt` — the brand
  colours, typography, and `LocalBhodlColors` / `LocalBhodlTypography`
  CompositionLocals consumed by every screen.
- `commonMain/composeResources/values/strings*.xml` — i18n root
  (English baseline, `values-it/` for Italian).
- `jvmTest/kotlin/io/bhodl/ffi/UniffiSmokeTest.kt` — the regression
  harness for the FFI. New FFI methods land with a smoke test here so
  the bindings round-trip is verified in seconds, not via running the
  full app.
- `jvmTest/kotlin/io/bhodl/ffi/FallibleConstructorErrorTest.kt` —
  checks that errors from fallible FFI constructors reach Kotlin as
  typed `WalletException`s.

### 4.5 `apps/desktop/`

Compose Multiplatform JVM app. Navigation graph (`navigation/Routes.kt`,
wired in `App.kt`):

```
   no registry ─→ Welcome ─→ DevicePinSetup ─→ AddWatch ─→ Tracking(home)
   registry    ─→ DevicePinUnlock ─→ (LegacyCleanup) ─→ Tracking(home)
   seed-era data only ─→ LegacyCleanup

   Tracking(section) ─┬─→ wallet row ─→ OpenWallet(id) ─→ Dashboard / WalletSection(tab)
                      │      tabs: Transactions · Receive · Addresses · UTXOs · Wallet settings
                      │      header actions: Contacts (AddressBook), Lock
                      ├─→ AddWatch
                      ├─→ Modules
                      ├─→ SecurityHub
                      └─→ Settings (theme / units / language / Esplora / Tor / factory reset)

   TransactionDetails(txid), Receive, History — kept for deep links
```

Tracker sections (`TrackerSection`): Home, Transactions, Budget, Goals,
Portfolio, Reports. The HOME wallet list is the wallet picker. Wallet
settings holds rename, BIP-329 labels import / export, public
descriptor export and "Remove this wallet" (two-tap). The Receive tab is
hidden for address-list wallets. Lock (header action or idle auto-lock)
closes both handles and returns to `DevicePinUnlock`.

`RegistryStore` (`wizard/RegistryStore.kt`) owns the open
`RegistryHandle`; `WalletStore` (`wizard/WalletStore.kt`) owns the
currently open watch wallet as a `WalletPort`.

**Port abstraction**: `WalletPort` is a read-only interface over
`WatchHandle` plus local annotations (`UniffiWalletPort` wraps the FFI,
`FakeWalletPort` provides test stubs). The wallet workspace reads it
from `WalletStore`; UI tests park a `FakeWalletPort` there for fast,
isolated verification of Compose logic without Rust / SQLCipher.

---

## 5. Build, run, test

### 5.1 Prerequisites

| Tool                  | Version   | Why                                            |
| --------------------- | --------- | ---------------------------------------------- |
| Rust toolchain        | 1.88      | Pinned by `rust-toolchain.toml`                |
| JDK                   | 21        | Compose Multiplatform 1.10 / AGP 8.9 target    |
| Strawberry Perl       | 5.x       | Windows-only, builds vendored OpenSSL for SQLCipher |
| Git, gh CLI           | recent    | Repo + CI inspection                           |
| Docker                | recent    | Only for `:apps:desktop:integrationTest`       |
| (Optional) MSYS2/MinGW| latest    | Required only for the `mingwX64` Kotlin/Native target — see `docs/spike-uniffi.md` |

`cargo` and `gradlew` install everything else on first build.

### 5.2 Common commands

```sh
# Cargo workspace
cargo check --workspace --all-targets
cargo clippy --workspace --all-targets -- -D warnings
cargo fmt --all --check
cargo test --workspace              # bhodl-core, bhodl-wallet-onchain (300+), bhodl-uniffi (46)
cargo test -p bhodl-core            # KDF tests
cargo test -p bhodl-wallet-onchain  # BDK watch-only + SQLCipher + registry tests
cargo deny check                    # license + advisory audit

# Regtest (ignored by default; needs BITCOIND_EXE / ELECTRS_EXE, see scripts/regtest-binaries.sh)
cargo test -p bhodl-wallet-onchain --test regtest -- --ignored

# Gradle / Kotlin
./gradlew :shared:jvmTest --tests "io.bhodl.ffi.UniffiSmokeTest"
./gradlew :apps:desktop:jvmTest --tests "io.bhodl.desktop.wizard.*"
./gradlew :apps:desktop:integrationTest   # bitcoind regtest + electrs (Docker)
./gradlew :apps:desktop:run         # launches the windowed app
```

`crates/bhodl-wallet-onchain/tests/regtest.rs` holds two `#[ignore]`d
watch-only tests: they fund the xpub's receive address via bitcoind,
sync, and check balance / UTXOs / the receive-label copy.

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

**Baseline (as of 2026-05-09, commit `3ea9f42`)** — historical: it
predates the watch-only refactor, and several of the screens it lists
(BumpFee, ReviewSend, RestoreBackup, the mnemonic wizard) no longer
exist. Re-measure before quoting numbers.

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

Realistic targets:

- `bhodl-core`, `bhodl-wallet-onchain` ≥ 85% line.
- `io.bhodl.desktop.wizard` ≥ 90%.
- `io.bhodl.desktop.screens` ≥ 85%.
- `io.bhodl.desktop.wallet` ≥ 90%.
- App entry / NavHost: stay at 0% intentionally; L3 integration tests cover end-to-end (bitcoind regtest + electrs).

Anything past those numbers is asymptotic — see "Cosa NON puoi
coprire al 100% con E2E" in the testing pyramid notes.

### 5.3 CI

`.github/workflows/ci.yml` runs:
- `cargo fmt --check`, `cargo clippy --all-targets -- -D warnings` (Linux)
- `cargo test --workspace` (Linux + macOS + Windows)
- `cargo-deny` (Linux; license + advisory + source allowlist audit)
- **`integration-test` job (Linux)**: Spins up Docker containers (bitcoind 28.0 regtest, electrs v3.2.0) via
  Testcontainers, runs `:apps:desktop:integrationTest` task (watch-only sync scenarios), uploads test reports on failure.
  Pre-pulls container images to stay within GitHub Action timeouts.
  Blanks `ANDROID_HOME` / `ANDROID_SDK_ROOT` to avoid AGP+uniffi conflict
  (integration tests are JVM-only, not Android). See commit `a3ddea6` (gradlew exec bit) and `8765b32` (gradle.properties commonize fix).
  The two images are pinned by digest (`name:tag@sha256:...`) in ci.yml and in the
  `IMAGE` constants of `BitcoindRegtestContainer` / `ElectrsContainer`. To bump one, resolve the
  new digest (`docker buildx imagetools inspect <name>:<tag>`) and update the tag and digest in
  all places together.

`.github/workflows/osv-scan.yml` (**OSV scan**, push/PR + weekly): runs `osv-scanner` over
`gradle/verification-metadata.xml` and fails on known vulnerabilities (the Gradle counterpart of
`cargo-deny` / `cargo-audit`). The file lists build tooling too, so findings that only affect the
Android/Kotlin Gradle plugin classpath are reviewed exceptions in `osv-scanner.toml`, pinned to
exact versions. When a dependency bump makes the job fail: if the vulnerable package ships in the
app, bump it; if it is build-tooling only, add a pinned override with a reason. Local run:
`osv-scanner scan --config=osv-scanner.toml -L gradle/verification-metadata.xml`.

Gradle dependency verification stays checksum-only (`verify-signatures=false`): enabling PGP
verification was tried and 418 signing keys could not be fetched from any key server, so the
result would have been mostly `ignored-key` entries. Revisit if the ecosystem's key coverage improves.

`.github/workflows/tor.yml` (**Tor (live)**, `workflow_dispatch` only — no schedule, never on PRs):
installs `tor` on the runner, waits until `check.torproject.org` reports `IsTor`, then runs
`cargo test -p bhodl-wallet-onchain --features tor-tests tor_live` and
`./gradlew :apps:desktop:torTest` with `BHODL_TOR_REQUIRED=1`. Locally:
`scripts/tor-tests.sh` (on Windows, Tor installed in WSL2 is reachable at `127.0.0.1:9050`
through WSL localhost forwarding). Test inventory: test-book §46.

Every commit on `main` must be CI-green across all jobs before the next change lands.

### 5.4 Smoke testing end to end on signet

1. `./gradlew :apps:desktop:run` with an empty `~/.bhodl/`.
2. Welcome (explains watch-only) → **Get started** → create a device
   PIN (at least 6 digits, typed twice). `~/.bhodl/` now contains
   `registry.sqlite` and `registry.kdf.json`.
3. AddWatch → label, script type matching the account (Taproot for
   BIP-86, Native SegWit for 84, Nested SegWit for 49), Signet, then
   paste the account xpub / `tpub` / `vpub` from a signing wallet you
   control (Sparrow, a hardware wallet on signet…). "Add watch wallet"
   → Tracker HOME shows the wallet; `~/.bhodl/watch-state/<uuid>.sqlite`
   exists.
4. Click the wallet row → wallet workspace on **Transactions**. Sync
   runs; balance and history match what the signing wallet shows.
5. **Receive** tab: set a label and category ("Label this payment
   first"), then **Show address**. The address is derived from the xpub
   and matches the signing wallet's next receive address; QR + BIP-21
   URI below. Pay it from the signing wallet or
   `https://signetfaucet.com/`, wait 1–3 minutes, sync: the incoming
   payment carries the label and category, and appears in the tracker.
6. **Addresses** and **UTXOs** tabs: the address is marked used, the
   new UTXO is listed (read-only).
7. **Wallet settings**: rename, export BIP-329 labels, show the public
   descriptor, then "Remove this wallet" (two-tap) — the row and its
   watch-state file are gone; the bitcoin are not affected.
8. Settings → toggle theme / language / units, override the Esplora
   endpoint, enable Tor. Security Hub lists device PIN, auto-lock,
   connection, wallets and labels export.

**L3 integration test** (regtest, no faucet needed):
```sh
./gradlew :apps:desktop:integrationTest
```
Runs the watch-only sync scenarios against bitcoind regtest + electrs:
fund an address of the watched xpub, sync, verify balance. Testcontainers
manages lifecycle.

---

## 6. Security model

BHODL is watch-only: it holds no private keys and cannot move funds. A
full compromise of the device or of the data dir leaks **privacy** (the
xpubs, therefore every past and future address and balance, plus labels,
contacts and tracker data), not funds. The protections are:

1. **No key material**. Wallets are created from xpubs, descriptors or
   addresses only. There is no seed generation, import, storage or
   backup; spending happens in the user's signing wallet. Process memory
   holds the device-PIN-derived key (zeroized when `RegistryHandle`
   drops) and public data only.
2. **Encryption at rest** via SQLCipher (AES-256-CBC). The only secret
   is the **device PIN**: `derive_key_from_pin(pin, salt)` using
   **Argon2id with OWASP 2024 strong parameters** (m=64 MiB, t=3, p=4)
   gives the 32-byte key that encrypts `registry.sqlite` (wallet list +
   all tracker tables) and every `watch-state/<uuid>.sqlite` (BDK state,
   labels, contacts). This costs ~500–800 ms per unlock on 2024 desktop
   hardware and ~50–800 ms per brute-force guess (6-digit PIN, the
   minimum: ~14 hours single-threaded). The salt and params live in
   `registry.kdf.json` next to the DB; they are not secret, but losing
   the file locks the data forever (the wallets can always be re-added
   from their xpubs).

   **Descriptor integrity**: `registry.kdf.json` is sealed with an
   HMAC (v2, key domain-separated from the SQLCipher key) only after
   the database accepted the key; out-of-bounds parameters are rejected
   before deriving. Wrong-PIN attempts are rate-limited in the desktop
   app (`PinAttemptTracker`), and the app locks after idle time.
3. **Network privacy** (opt-in). The desktop app defaults to
   mempool.space's public Esplora for the wallet's network. Users can
   override the endpoint per network in Settings (e.g. a self-hosted
   Esplora), or enable Tor (SOCKS5 proxy) to route all Esplora and price
   requests through a local Tor daemon. Without either, the Esplora
   server sees which addresses are queried from which IP — on mainnet
   that links the xpub to the user. With a proxy configured the Esplora
   client uses a 90 s request timeout (clearnet: 30 s) because Tor
   circuits can stall 20-35 s before the connection is up.
   **Tor stream isolation.** Every SOCKS5 connection offers no-auth (`0x00`)
   and username/password (RFC 1929, `0x02`); when the proxy picks `0x02`
   (Tor does) BHODL authenticates with a random per-scope token, so Tor's
   default `IsolateSOCKSAuth` puts each scope on its own circuit: one scope
   per wallet (sync; keyed in memory by the wallet's public descriptor),
   one for rate / price fetches (Rust `BtcRateRepo` and Kotlin
   `PriceFetcher`) and one for any other Esplora call. Without this, a
   single Esplora server could link all of a user's wallets by circuit. Tokens are 128 random bits from the OS RNG, kept only in
   memory, stable for the session and rotated on restart; nothing sent is
   derived from a wallet id or key. A proxy that picks `0x00` is still used
   (no isolation possible there, the connection proceeds). The Kotlin side
   speaks SOCKS5 itself because the JDK client would answer with the OS
   `user.name`. Stream isolation unlinks wallets per circuit, not per user:
   the same exit IP can still be shared over time, and refreshing many wallets
   back to back remains a timing correlation.
   Historical BTC rates: CoinGecko's keyless API only serves the last 365
   days (older requests answer HTTP 401), so dates older than that are
   resolved through blockchain.info + Frankfurter and CoinGecko is never
   asked about them.

What BHODL does NOT defend against:

- Process-memory dumps captured while the app is unlocked (the derived
  key and decrypted wallet data are in memory).
- Hardware-backed key wraps (Keystore on Android, Secure Enclave on
  iOS, OS keyring on desktop). These land alongside the corresponding
  platform target.
- A malicious or logging Esplora server when neither Tor nor a
  self-hosted endpoint is used, and remote attacks on the user's own
  infrastructure (self-hosted nodes, the user's OS, etc.). The app
  trusts the configured Esplora endpoint for chain data and the user's
  local Tor daemon (if enabled).
- Anything about the user's signing wallet: BHODL never sees it.

### 6.1 Upgrading / downgrading

Upgrading is automatic; **downgrading is not supported**. The first
unlock with a build that contains the 2026-10 security-audit fixes
rewrites the on-disk data in formats older builds do not understand:

- the KDF descriptor (`registry.kdf.json`) is re-sealed as **v2** (HMAC
  over salt + params, keyed with the derived key) —
  `crates/bhodl-uniffi/src/commonMain/rust/kdf.rs`;
- the registry is migrated to **schema v4** (`PRAGMA user_version`;
  v2 lot `original_quantity`, v3 crypto-kind `user_set`, v4 wallet
  `mirror_of`) in one transaction — `registry.rs::ensure_schema`.

After that, an older build cannot open the data: it rejects the v2
descriptor (typically reported as a wrong PIN / integrity error — do not
keep retrying, it counts towards that build's lockout).

This build and later ones refuse data written by a **newer** build
instead of guessing: a registry stamped with a higher schema version
fails with "registry schema version N is newer than this app supports",
an unknown KDF descriptor version with "unsupported kdf descriptor
version N". The desktop app classifies both as
`PinFailureKind.NewerVersion` (`wizard/PinErrors.kt`): the unlock screen
shows "This data was written by a newer version of BHODL … Update BHODL",
and the attempt is not counted as a wrong PIN.

**Seed-era data (builds before 2026-10-23).** Earlier builds could
create or restore signing wallets from a recovery phrase: registry rows
of kind `signing` with their keys under `wallets/<uuid>/`, or a pre-3.0
single-wallet install (`wallet.sqlite`, `wallet.kdf.json`, `wallet.salt`,
`wallet.network`) at the data-dir root. The watch-only build cannot open
them. After `DevicePinUnlock` (or directly at boot when only such data
exists) the app shows **LegacyCleanup**: it explains that the old
spending wallets will be permanently deleted, keys included, and enables
"Delete old wallets" only after the user ticks "I have the recovery
phrases of these wallets". Deletion calls
`RegistryHandle.purgeLegacySigningWallets()` (rows + key directories)
and `WalletPathsView.purgeLegacySigningData()` (root-level legacy
files). "Not now" keeps everything; the screen comes back on the next
unlock. Watch wallets that mirrored a signing wallet stay as ordinary
watch wallets, so tracker history is kept. To keep tracking a deleted
wallet, add it again by its xpub.

---

## 7. Known foot-guns and their workarounds

These are the surprises someone changing this code today is most
likely to bump into. Each has its own incident report under `docs/`
linked from this list.

| Hazard                                                              | Where      | Workaround                              |
| ------------------------------------------------------------------- | ---------- | --------------------------------------- |
| `RustBuffer` cross-DLL free crashes on Windows (mismatched CRTs)    | `docs/uniffi-runtime.md` | `+crt-static` for MSVC in `.cargo/config.toml` |
| Free fns + `Result<String, _>` crash with "Invalid memory access"   | `docs/uniffi-runtime.md` | Such functions live as methods on `WatchHandle`, which must stay the **first** `#[uniffi::Object]` in `crates/bhodl-uniffi/src/commonMain/rust/lib.rs` |
| `installBindgen` on Windows produces `…multiplatform.exe` but the BuildBindingsTask looks for the bare name | `docs/spike-uniffi.md`   | Manually copy the `.exe` to a no-extension twin (PowerShell `Copy-Item -Force`) |
| `bdk_testenv`'s `download` feature pulls `zip` 0.6 (RUSTSEC-2026-0367/0368/0369); electrs has no Windows build | `crates/bhodl-wallet-onchain/Cargo.toml` | `download` off, binaries via `BITCOIND_EXE`/`ELECTRS_EXE` from `scripts/regtest-binaries.sh` (sha256-pinned); `[target.'cfg(unix)'.dev-dependencies]` |
| `integrationTest` job fails with "Task 'packageDebugResources' not found" on ubuntu-latest   | `.github/workflows/ci.yml` (integration-test env) | Pre-installed Android SDK on GitHub Actions conflicts with AGP 8.9 task names. Blank `ANDROID_HOME` + `ANDROID_SDK_ROOT` to disable Android detection. |
| `gradle commonize stub` fails during desktop build                  | `settings.gradle.kts` | `gradle.beforeProject { System.setProperty("kotlin.mpp.enableCInteropCommonization", "true") }` ensures the commonize stub is registered early (before the Ubique uniffi plugin runs). |
| Compose UI test wiped the developer's real `~/.bhodl/` every `:jvmTest` run | `apps/desktop/.../screens/SettingsWalletSections.kt::WipeSection` | The wipe onClick once hard-coded `WalletPaths.defaultDataDir()`, so a Compose test clicking it destroyed the dev data every CI run. **Rule**: any destructive composable (wipe, reset, delete, legacy cleanup) MUST accept the target path / view as a parameter with a production-default; tests pass a tempdir. Applied to `SettingsScreen`, `WelcomeScreen`, `LegacyCleanupScreen` and `DevicePinSetupScreen` (`dataDir`). |
| SQLCipher file survives wallet removal / wipe on Windows             | `apps/desktop/.../wallet/WalletPort.kt::close`, `RegistryHandle::remove_wallet` | Windows holds the file lock until the JVM GC finalises the wrapping `Arc<WatchHandle>` — undefined latency. `WalletStore.clear()` calls `handle.destroy()` synchronously before any file is deleted (§4.3). |

When you add code, document new foot-guns inline (`# Why` comments)
or as a new entry in this table.

---

## 8. Roadmap pointers

Since 2026-10-23 BHODL is watch-only; signing and sending are out of
scope (hardware-wallet signing, PayJoin, CoinJoin and Lightning payments
belong to the user's signing wallet). Open items in priority order:

1. **Watch-only hardening & test coverage**
   - Manual test-book pass on the new flows (first launch, wallet
     workspace, LegacyCleanup).
   - `bhodl-wallet-onchain` ≥ 85% line coverage.
   - Regtest coverage of more watch-only cases (HD descriptor and
     address-list wallets, gap limit, reorgs).
2. **Releases**: packaged desktop builds, Android UI, iOS build.
3. **More balances to track**: Lightning, Liquid, Cashu / Fedimint, Ark
   — read-only, through whatever public / view-only interface each
   offers. See `PLAN.md` for scope.

All roadmap decisions live in `PLAN.md`.

---

## 9. Where to look when something breaks

| Symptom                                            | First place to check                                |
| -------------------------------------------------- | --------------------------------------------------- |
| `cargo build` slow (~10 min) on a clean tree       | Normal — vendored OpenSSL + SQLCipher rebuild      |
| Kotlin says `unresolved reference 'uniffi.runtime'`| Stale generated bindings; delete `shared/build/generated/uniffi/` and rerun |
| `java.lang.Error: Invalid memory access` at startup | CRT mismatch — see §7. Re-check `.cargo/config.toml`. Also check that `WatchHandle` is still the first `#[uniffi::Object]` in `lib.rs` |
| Wrong device PIN but you're sure it's right        | Data written by a newer or older build (§6.1). If the PIN is really lost, the only way out is Welcome → "Erase ALL local wallets…" and re-adding the wallets by xpub |
| LegacyCleanup appears after every unlock           | Seed-era data still on disk ("Not now" was chosen, or some files could not be deleted — another program holds the BHODL folder). Delete from the screen once you have the recovery phrases |
| Balance is 0 / history empty for a wallet you know is funded | Wrong network for the xpub, wrong BIP template (an 84 xpub added as 86), or the funds sit beyond the gap limit. Compare the first receive address with the signing wallet |
| Signet faucet says "queued in 0 seconds" but no tx arrives | `signetfaucet.com` queue lag. Wait 2–5 min and check `mempool.space/signet/address/<addr>`; try `alt.signetfaucet.com` if needed |
| CI clippy fails on doc comments                    | The `clippy::doc_markdown` and `clippy::doc_lazy_continuation` lints; backtick technical names (`SQLCipher`, `BDK`, etc.) and avoid `+ word` paragraph leaders that look like Markdown lists |
| `~/.bhodl/` mysteriously empty after running `:jvmTest`, but no wipe was tapped | Compose UI test that exercises a destructive composable without an injection seam. Grep for `WalletPaths.defaultDataDir()` in `apps/desktop/.../screens/` — any destructive caller MUST forward an injected path so tests target a tempdir. See foot-gun §7 row "Compose UI test wiped the developer's real `~/.bhodl/`". |
| Removed a wallet but `watch-state/<uuid>.sqlite` is still there | The `WatchHandle` was still open when `removeWallet` ran (Windows lock). Removal of the file is best-effort; close the wallet (`WalletStore.clear()`) first. The orphan file is harmless and can be deleted by hand |

---

## 10. Glossary

- **Watch-only wallet**: a wallet built from public material only
  (xpub, descriptor or addresses). It can show balances and derive
  receive addresses but cannot sign.
- **xpub / SLIP-132**: account-level extended public key. `zpub` /
  `ypub` (SLIP-132 prefixes for BIP-84 / BIP-49) are accepted and
  normalised.
- **BIP-86 / 84 / 49**: taproot, native segwit and nested segwit
  single-key derivation paths, `m/purpose'/coin'/account'/keychain/index`.
  The descriptor template of an xpub wallet is chosen from these.
- **HD descriptor**: output descriptor with key origin
  (`[fingerprint/path]xpub`), as exported by Sparrow / Specter.
- **Gap limit**: how many consecutive unused addresses a scan checks
  before stopping.
- **BIP-329**: portable wallet labels. Stored in a SQLCipher table in
  the wallet's watch-state file, keyed by `(label_type, target)`;
  JSONL import / export in Wallet settings.
- **BIP-21**: `bitcoin:` URI scheme. Generated on the Receive tab with
  optional `amount` (BTC, derived from sat) and `label` (UTF-8
  percent-encoded). Empty extras → bare `bitcoin:<address>` so the
  QR stays compact in the common case.
- **BDK**: bitcoindevkit. Provides the `Wallet` engine, descriptor
  store and Esplora client we depend on.
- **Device PIN**: the only secret in BHODL; Argon2id turns it into the
  SQLCipher key for the registry and every watch-state file.
- **Registry**: `registry.sqlite`, the wallet list plus every tracker
  table.
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
