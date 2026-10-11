# Phase 3 — Tracking + Multi-wallet (read-only registry)

> Architecture, scope, and execution plan. Authored 2026-05-12 after
> the Phase 2 on-chain feature freeze and the post-K.2 UX redesign.
> Living document — sub-phases tick to "done" as they land.

## Goals

1. **Differentiation**: BHODL "thinks like a bank account" requires
   spending insights, categorisation, and balance-over-time — features
   that on-chain-only wallets (Sparrow, Specter) don't ship and
   Lightning-first wallets (Phoenix, Mutiny) only see partially. This
   is the wedge.
2. **Multi-wallet asset visibility**: users hold sats across multiple
   wallets — hot mobile, cold hardware, exchange addresses they want
   to monitor. The tracker must aggregate them all, signing or not.
3. **Foundation for Lightning**: the `PaymentEvent` abstraction
   designed here accommodates LN payments / channel events in Phase 4
   without redesign. On-chain tx → `PaymentEvent { kind: OnChain }`,
   future LN payment → `PaymentEvent { kind: Lightning }`.
4. **Privacy-protective by default**: per-wallet isolation (separate
   PIN per signing wallet), encryption at rest, no telemetry, no
   plaintext xpubs on disk.

## Non-goals (Phase 3)

- Hardware wallet signing via HWI/PSBT (Phase 3.5+ — out of scope for
  3.0–3.6).
- Lightning support of any kind (Phase 4).
- Liquid / Cashu / Fedimint / Ark integration (Phase 5).
- Fiat conversion / pricing API integration (deferred — likely Phase 4
  or later; tracker initially shows sat-denominated insights only).
- Cross-device sync of registry (the registry lives on one machine).

---

## Architecture overview

### Two-tier security model

| Tier | What it protects | When prompted |
|---|---|---|
| **Device PIN** | `registry.sqlite` (wallets list, contacts, BIP-329 labels+categories, settings), all watch-only BDK state | Once at app boot |
| **Per-wallet PIN** | One per signing wallet: `wallets/<uuid>/wallet.sqlite` (BDK state, seed, passphrase, descriptor with secret material) | When activating that wallet for signing |

Watch-only wallets do not have a per-wallet PIN — their descriptor is
public data, stored in the registry, protected by the device PIN. The
BDK chain-history state of a watch wallet is privacy-sensitive (reveals
which addresses you watch and their balance / activity) but not key-
protective, so device-PIN encryption is sufficient.

**Why two-tier**: a single shared PIN means compromise of one wallet's
PIN reveals everything. Two-tier provides isolation: knowing the
device PIN gives an attacker the *list* of your wallets and their
public balances, but not the ability to spend any of them. Knowing a
specific wallet's PIN gives access to that wallet only.

**Bootstrap UX**: on a fresh install the user is asked to set the
device PIN *first* (a dedicated "Set device PIN" screen on Welcome
before "Create wallet"). Then for the first signing wallet the user
sets a wallet-specific PIN — can be the same string as the device PIN
or different, the user's choice. Two prompts at first install,
explicit conceptual separation.

### Storage layout

```
~/.bhodl/
├── registry.sqlite          ← device-PIN encrypted (SQLCipher AES-256, Argon2id key)
├── registry.kdf.json        ← KDF descriptor for the registry (salt + params)
├── wallets/
│   └── <uuid>/
│       ├── wallet.sqlite    ← wallet-PIN encrypted (signing material)
│       └── wallet.kdf.json
└── watch-state/
    └── <uuid>.sqlite        ← device-PIN encrypted (BDK chain state, no signing material)
```

- `<uuid>` is a UUIDv4 generated when the wallet is added to the
  registry. Same `<uuid>` is used for both `wallets/<uuid>/` and
  `watch-state/<uuid>.sqlite` so the registry has one identity per
  wallet regardless of kind.
- A wallet that gets promoted from `watch` to `signing` keeps its
  `<uuid>` — only the storage shape extends (a new `wallets/<uuid>/`
  directory is created next to the existing `watch-state/<uuid>.sqlite`,
  which is then deleted because the signing wallet's `wallet.sqlite`
  carries the full BDK state).
- The registry holds the canonical descriptor (public form) for each
  wallet. Signing wallets' `wallet.sqlite` holds the SECRET descriptor;
  the public descriptor in the registry MUST match the public projection.

### Why separate files per wallet (BDK 3.0 persistence model)

BDK 3.0 persists each wallet's chain state via `PersistedWallet<C>`
where `C` is a `Connection` (or any `bdk_chain::Store<ChangeSet>` impl).
Multiple wallets in one process means either:

- (a) Implement a custom `Store<ChangeSet>` that namespaces tables by
  wallet_id. ~1 week of BDK plumbing, non-trivial.
- (b) One file per wallet, one `Connection` per file, one
  `PersistedWallet<Connection>` per wallet. Standard BDK usage,
  zero new plumbing.

Phase 3 picks (b). From the user's perspective the workspace is a
single coherent entity ("my BHODL wallet"); from the implementation's
perspective the BDK changesets live in per-wallet `.sqlite` files
without fighting the framework.

### Watch-only wallet types

The registry supports three ways to identify a watch-only wallet:

| Kind | Input | Behaviour |
|---|---|---|
| `WatchXpubBip86` / `WatchXpubBip84` | xpub at a known BIP path | BDK derives addresses at `m/86'/coin'/0'/<keychain>/<index>` (or BIP-84 / BIP-49 variants). Standard. |
| `WatchAddresses` | List of explicit bech32 addresses | No derivation; tracks only the supplied addresses. Used for exchange addresses or `tb1q…` pasted from a third-party wallet. |
| `WatchHd` | Master fingerprint + derivation path + xpub | Full HD wallet from a Sparrow / Specter output descriptor file. Same as `WatchXpub*` but with the metadata needed to round-trip the descriptor. |

### Active signing slot

At any moment, **zero or one** wallet is "active for signing":

- App boot → device PIN → see registry → no active wallet → tracking
  is fully available (read-only view of all wallets), but Send /
  build-PSBT operations are unavailable.
- User selects a signing wallet from the registry → prompted for that
  wallet's PIN → `OnchainAccount` loaded → active slot populated.
- User selects another signing wallet → previous active slot is
  released (`destroy()` chain, SQLCipher file lock released), new
  wallet PIN prompted, new `OnchainAccount` loaded.
- User selects a watch wallet → no PIN prompt, the wallet becomes the
  "viewed wallet" but signing remains unavailable until the user
  picks a signing wallet.

### `PaymentEvent` abstraction (Phase 3.3)

Every on-chain transaction (and future Lightning payment, Liquid send,
etc.) maps to a `PaymentEvent`:

```rust
struct PaymentEvent {
    wallet_id: Uuid,
    txid_or_payment_id: String,   // for on-chain: txid; for LN: payment_hash
    kind: PaymentKind,            // OnChain | Lightning (Phase 4) | …
    direction: Direction,         // Incoming | Outgoing | SelfTransfer
    amount_sat: u64,              // absolute value
    fee_sat: Option<u64>,         // only set when the wallet paid the fee
    timestamp_unix: Option<u64>,  // None = still pending
    block_height: Option<u32>,    // None = mempool
    label: Option<String>,        // BIP-329 label.text
    category: Option<String>,     // BIP-329 extension: label.category
    counterparty: Option<String>, // address book label, derived if unknown
}
```

Queries (cross-wallet, aggregate or filtered):
- `list_events(filter: EventFilter) -> Vec<PaymentEvent>`
- `aggregate_balance() -> u64` (sum across all wallets)
- `balance_at(timestamp) -> Vec<(wallet_id, u64)>` (for charts)
- `spending_by_category(date_range) -> Vec<(category, u64)>`

---

## Sub-phases

### Phase 3.0 — Foundation: storage refactor + dual-PIN model

**Scope**: introduce the registry + per-wallet storage layout, the
device PIN concept, and the safe migration from the current
single-wallet `~/.bhodl/wallet.sqlite`. No UI changes beyond the
required migration modal.

**Rust deliverables**:
- New crate / module `bhodl-registry` (or grow `bhodl-wallet-onchain`)
  exposing:
  - `RegistryHandle::create_or_load(db_dir, device_pin) -> Result<Arc<Self>, RegistryError>`
  - `RegistryHandle::list_wallets() -> Vec<WalletRecord>`
  - `RegistryHandle::add_wallet_record(label, kind, descriptor_pub, network)`
  - `RegistryHandle::remove_wallet(id)` (also drops the on-disk
    `wallets/<id>/` or `watch-state/<id>.sqlite`)
- Existing `WalletHandle` evolves to:
  - `WalletHandle::create_or_load(wallet_dir, wallet_pin, mnemonic?, passphrase?, network)` — takes a per-wallet directory under `wallets/<uuid>/` rather than the legacy `~/.bhodl/`.
  - Behaves identically to today; just stores under the new path.

**Migration — two-phase commit pattern (security review F1)**:

The naive "do all steps in sequence" migration is **not atomic** —
interruption (crash, power loss, kill signal) between steps can leave
the directory tree in a state where the app cannot boot at all. The
two-phase commit pattern bounds the damage: at every interruption
point the recovery action is unambiguous, and a `migration.lock`
sentinel file tells the next boot which phase to resume from.

**Phase A — Reversible setup** (creates new state, no destruction of
old):
1. Detect: `~/.bhodl/wallet.sqlite` exists AND `~/.bhodl/registry.sqlite`
   does not. If both exist, migration already done — skip.
2. **Atomic backup**: for each of `wallet.sqlite`, `wallet.kdf.json`,
   `wallet.network`, copy to `~/.bhodl/migration.pre-3.0.bak/<name>.tmp`,
   `fsync` the temp file, then rename to `~/.bhodl/migration.pre-3.0.bak/<name>`.
   A naive `Files.copy` is NOT atomic on Windows — a kill mid-copy
   produces a truncated `.bak`. The temp+rename pattern matches what
   `NetworkPersistence.write` already does for `wallet.network`.
3. Write sentinel: `~/.bhodl/migration.lock` containing the chosen
   UUID + a phase marker `"phase_a_complete"`. `fsync` it.
4. **Modal UI**: "Update needed — BHODL now supports multiple wallets.
   Before continuing, export an encrypted backup (recommended)." Two
   buttons: primary "Export `.age` backup first", secondary "Skip — I
   already have a backup". The primary path runs the existing Phase 2
   export flow before returning to migration. Do NOT block the user
   on this — some users will never have made a backup and will feel
   trapped — but make it the default action.
5. Modal UI step 2: "Set a device PIN. It can be the same string as
   your existing wallet PIN." Confirm.
6. Compute the wallet UUID (UUIDv4 from CSPRNG).
7. Create `registry.sqlite` cifrato col device PIN, insert one wallet
   row: `{id: <uuid>, label: "My Wallet", kind: Signing,
   descriptor_pub: <extracted-from-old-wallet>,
   network: <from-wallet.network>}`. The public descriptor is
   extracted by opening the old `wallet.sqlite` with the old PIN
   (passed in as the wallet PIN, since they are the same string for
   the pre-existing user).
8. Update `migration.lock` phase marker to `"phase_a_committed"`.
   This is the point-of-no-return — beyond this the old single-wallet
   state will be destructively moved. `fsync`.

**Phase B — Atomic move** (single filesystem-level commit):
9. `Files.move(REPLACE_EXISTING + ATOMIC_MOVE)`: `wallet.sqlite` →
   `wallets/<uuid>/wallet.sqlite`, then `wallet.kdf.json` →
   `wallets/<uuid>/wallet.kdf.json`. Same-directory moves are
   effectively atomic on both Windows (`MoveFileEx` with
   `MOVEFILE_REPLACE_EXISTING`) and POSIX (`rename(2)`).
10. Update `migration.lock` phase marker to `"phase_b_committed"`.

**Phase C — Cleanup** (cosmetic, recoverable):
11. Delete `~/.bhodl/wallet.network` (info now in registry).
12. Delete `~/.bhodl/migration.lock`.

**Verification**: re-unlock with device PIN + wallet PIN; balance +
first address + history MUST match the pre-migration backup. If
verification fails, abort with a clear message and instructions to
restore from `migration.pre-3.0.bak/`.

**Boot-time recovery (if `migration.lock` exists)**:
- `phase_a_complete` or marker missing → Phase A was interrupted
  pre-commit. Delete any partial state in `registry.sqlite`, delete
  `wallets/<uuid>/` if it exists, delete `migration.lock`. The
  original `wallet.sqlite` is untouched; the next boot will retry
  migration from scratch.
- `phase_a_committed` → Phase A succeeded but Phase B was interrupted
  before the move. Re-run Phase B (the registry row is correct, just
  the files need moving).
- `phase_b_committed` → Phase B succeeded, Phase C was interrupted.
  Re-run Phase C (cosmetic cleanup only).

In all three recovery branches: show the user a brief "Recovering
from interrupted update — your wallet is safe" notification, do the
recovery silently, then continue to the normal boot flow.

**Test plan**:
- `MigrationIntegrationTest.kt` with a fixture pre-3.0 wallet; assert
  post-state matches.
- Property: migration deterministic + idempotent (re-running on
  already-migrated state is a no-op).
- **Recovery test matrix**: simulate interruption at each phase
  marker (`phase_a_complete`, `phase_a_committed`, `phase_b_committed`)
  by killing the process before the marker advances. Verify boot-time
  recovery completes the migration correctly and the final state
  matches the non-interrupted path.
- Manual test on a disposable wallet with an `.age` backup made
  beforehand.

**Risks**:
- Data loss — mitigated by atomic backup + integration test fixture
  + recovery test matrix + force-export prompt in step 4.
- BDK `descriptor.public()` API for watch-only — verify available
  before starting.
- Two `Connection`s open simultaneously (one for registry, one for active wallet), different SQLCipher keys — verify no cross-talk in rusqlite.

**Estimate**: 3–4 days.

### Phase 3.1 — Watch-only wallets

**Scope**: ability to register watch-only wallets in the registry and
sync their state. No UI for adding them yet (admin via FFI / temporary
debug screen).

**Deliverables**:
- BDK watch-only `Wallet::create_with_descriptors(public_descriptor, …)` wrapper. Sync via Esplora identical to signing wallet.
- `RegistryHandle::add_watch_wallet_xpub(label, xpub, network, bip = 86 | 84 | 49)`
- `RegistryHandle::add_watch_wallet_addresses(label, addresses, network)` — manual address list import; BDK supports this via "imported descriptors" or a wrapped descriptor with `addr()` parts.
- `RegistryHandle::add_watch_wallet_hd(label, fingerprint, path, xpub, network)` — same as xpub but stores the BIP-32 metadata for descriptor round-trip with Sparrow/Specter.
- Per-watch-wallet `watch-state/<uuid>.sqlite` encrypted with device PIN.
- Aggregate-balance API: `RegistryHandle::aggregate_balance_sat() -> u64`.

**Estimate**: 4–5 days.

### Phase 3.2 — Activation flow + Welcome redesign

**Scope**: UI changes so the user can see, manage, and switch between
wallets.

**Deliverables**:
- Welcome screen redesign: if registry is non-empty, shows a wallet picker — list of wallets with label + balance preview + kind icon. "Add new wallet" button + tap-to-activate per row.
- Activation flow: tap a signing wallet → prompt wallet PIN → load `OnchainAccount` → navigate to Dashboard.
- Activation flow watch-only: tap a watch wallet → no PIN prompt → Dashboard with Send disabled, Receive disabled (no signing keys), History + balance + tracking enabled.
- Add-wallet flow: "Add new wallet" expands into three options — Create new (generate mnemonic + set wallet PIN), Restore from seed (paste mnemonic + set wallet PIN), Add watch-only (xpub / addresses / HD descriptor input).
- **Same-PIN-warning UX (security review)**: when the user sets a wallet PIN at create / restore / promotion time, compare against the device PIN. If equal, render a one-time dismissible banner: "You used the same PIN as your device. For best protection, use a different PIN per wallet." Persist a `pins_warned` boolean per wallet in the registry so the warning shows once. Do NOT block — same-PIN is allowed, just discouraged.
- **Promote watch → signing flow (security review F2)** — multi-step to defend against the social-engineering scenario where an attacker with device PIN access injects a fake watch wallet and tricks the user into entering their mnemonic into it:
  1. User taps "Activate signing" on a watch wallet's detail screen.
  2. **Device PIN re-auth modal**: "Confirm with your device PIN to continue." User re-enters device PIN. This blocks attackers who walked up to an unlocked app from triggering the highest-consequence operation in the flow.
  3. **Descriptor verification preview**: the screen shows the wallet's descriptor fingerprint + the first 3 receive addresses derived from the registry's stored descriptor — BEFORE the seed entry. Caption: "Verify these addresses look right. If not, do NOT enter your seed below; close this screen and check the wallet entry in the registry." Pattern adopted from Sparrow.
  4. User enters BIP-39 mnemonic (and optional BIP-39 passphrase). Derive the BIP-86 xpub locally. Compare against the registry's stored descriptor.
  5. On match: prompt for wallet PIN, encrypt seed into new `wallets/<uuid>/wallet.sqlite`, upgrade `kind` from `Watch*` to `Signing`. Delete the old `watch-state/<uuid>.sqlite` (chain state will be rebuilt by BDK on first sync of the signing wallet).
  6. On mismatch: show "Seed does not match this wallet — check your BIP-39 passphrase if you used one." Do NOT leak the stored descriptor in the error.
- Switch active from Settings: list of signing wallets, tap to switch. Same destroy-chain pattern as the wipe path (finding #14): old active `WalletHandle.destroy()` → file lock released → new wallet PIN prompted → new `WalletHandle::create_or_load` opens the new active. The Kotlin side must `suspend` on `destroy()` so the second open does not race the first close.

**Estimate**: 4–5 days.

### Phase 3.3 — `PaymentEvent` abstraction + unified history

**Scope**: cross-wallet history view + the underlying abstraction.

**Deliverables**:
- `PaymentEvent` struct and FFI Record.
- `RegistryHandle::list_events(filter) -> Vec<PaymentEvent>` — joins per-wallet `list_transactions()` results, normalises into `PaymentEvent`.
- `HistoryScreen` becomes cross-wallet by default; per-wallet filter chip at the top.
- Dashboard balance: aggregate (sum across all wallets) by default; toggle for "Active wallet only".
- `TransactionDetails` shows which wallet the tx belongs to.

**Estimate**: 3–4 days.

### Phase 3.4 — Categorisation (BIP-329 extension)

**Scope**: assign categories to transactions and labels.

**Deliverables**:
- BIP-329 record extension: `category` field (string, optional, ignored by other consumers). Round-trip-safe. The BIP-329 spec mandates "implementations MUST ignore unknown fields"; Sparrow 1.8.x and Liana already follow this rule, so the `category` survives a round-trip through their import/export pipelines without being stripped.
- **Import merge semantics**: when an external tool (Sparrow, Liana) re-exports labels back to BHODL after editing them, BHODL must preserve any `category` field already in its DB rather than overwrite to `null` on rows where the incoming JSONL lacks a category. Implement as a merge: update `label.text` for matching `(kind, target)`; preserve `label.category` if the incoming row does not carry one.
- `TransactionDetailsScreen`: category picker dropdown + "Create new category" inline.
- Predefined seed categories: Food, Bills, Transport, Income, Transfer, Savings, Investment, Gift, Other. Editable list in Settings.
- Category storage: column on the existing BIP-329 labels table (per-wallet), exported in JSONL alongside label.
- **Export UX (security review F3)** — the `category` field may contain financial PII ("Rent — John Doe", "Medical — Dr. Smith") which is sensitive metadata. The default export MUST be encrypted; plaintext export is allowed but explicitly opt-in.
  - Settings → Export labels offers two buttons:
    - **Primary**: "Export encrypted (.age)" — prompts for a user-chosen password (separate from device/wallet PINs, same UX as the existing wallet `.age` backup flow). Output is an ASCII-armoured age payload containing the JSONL.
    - **Secondary**: "Export plaintext JSONL (for Sparrow/Liana import)" — shows a warning dialog: "This file contains label and category metadata in plaintext, including any financial notes you have written. Only export plaintext if you are about to import into another wallet you control." Confirmation tap required.
  - Both flows write to the user's chosen path via the existing JFileChooser pattern.
  - On import: detect file format (age-armoured starts with `-----BEGIN AGE ENCRYPTED FILE-----`, plaintext starts with `{`). For age, prompt for password and decrypt before parsing.

**Estimate**: 3 days.

### Phase 3.5 — Tracking dashboard

**Scope**: the visualisations that turn raw events into insights.

**Deliverables**:
- New screen `TrackingScreen` (entry from Dashboard).
- Spending by category — pie chart, configurable date range (this month / last 3 months / year / custom).
- Balance over time — line chart, per-wallet and aggregate views.
- Top counterparties — list with avatars (initials), amounts, % of period.
- CSV export — events + balances per period.
- Chart library decision: needs Compose Multiplatform compatible. Candidates: `koalaplot-core` (open-source, KMP-friendly), hand-rolled `Canvas`-based charts (heavier work but zero deps). Pick during this phase based on dep audit.

**Estimate**: ~1 week.

### Phase 3.6 — Polish + multi-wallet UX details

**Scope**: round out the multi-wallet experience.

**Deliverables**:
- Rename / edit wallet label.
- Per-wallet colour assignment (for visual identification in lists / charts).
- "Delete wallet" — requires re-entering the device PIN; for signing wallets also requires the wallet PIN; offers an .age backup export option before delete.
- CSV import (for legacy tracker users migrating in).

**Estimate**: 3 days.

---

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| Data loss during Phase 3.0 migration | Auto-backup before any mutation; integration test against a real pre-migration `.sqlite`; manual test on a disposable wallet with an `.age` backup pre-made. |
| BDK 3.0 watch-only descriptor support unclear | Verify API in 3.0 spike before starting Phase 3.1; if missing, file upstream issue and consider alternatives (manual address tracking via custom indexer). |
| Two SQLCipher connections in one process (different keys) cross-talk | Verify in 3.0 — rusqlite uses one PRAGMA key per `Connection`; should be isolated. Add an integration test that opens both and writes/reads in parallel. |
| Chart library dep audit fails (no good KMP option) | Hand-roll with `Canvas` — adds ~1 week to Phase 3.5 but no third-party risk. Decide after a half-day spike. |
| Multi-wallet UI becomes complex (Welcome picker, switch flows, etc.) | Keep wizard screens (Generate / Restore / PinSetup) reusable; the picker is a thin wrapper around them. Defer per-wallet colour customisation to 3.6 to avoid scope creep in 3.2. |

---

## Open questions to resolve during implementation

- **Address book scope**: today contacts are per-wallet (table inside
  `wallet.sqlite`). Move to registry as a cross-wallet shared book? My
  recommendation: yes, move to registry — "people I send to" is
  identity-level, not wallet-level. Confirm during 3.0.
- **BIP-329 import vs export across wallets**: do we import labels
  scoped to one wallet or globally? Recommend per-wallet (the BIP-329
  spec is per-wallet by convention).
- **Watch wallet sync cadence**: same 60s auto-sync as the active
  wallet, or only on user request? Recommend on-demand + manual sync
  for watch wallets in 3.1 — auto-sync only for the active wallet to
  keep network use predictable.
- **Network mismatch**: can a registry hold wallets across multiple
  networks (one signet wallet + one mainnet wallet)? Architecturally
  yes, but UX-wise: do we let the user mix? Recommend yes — the
  network is per-wallet, and tracking can aggregate across networks
  with the unit being "sat" (mainnet sat ≠ signet sat in value but
  both are valid numbers). The aggregate balance display will need a
  network breakdown.

---

## Accepted trade-offs (from 2026-05-12 security review)

These are decisions where the security expert flagged a potential
improvement, the team evaluated the cost/benefit, and the decision
is to ship as-designed in Phase 3 with the documented caveats. Each
entry below names the trade-off, why it is acceptable today, and
the conditions under which it should be revisited.

**A1 — UUIDv4 for wallet filesystem identity (Area 8)**. We use a
random UUIDv4 to name each wallet's directory (`wallets/<uuid>/`)
and watch-state file (`watch-state/<uuid>.sqlite`). A descriptor-
seeded UUID (`BLAKE3(public_descriptor || per_registry_salt)`)
would give a useful integrity property — you could verify a wallet
file matches its registry entry without decrypting either — but
the security delta is marginal (UUIDv4 from CSPRNG is unguessable
and not linkable to the xpub without reading the encrypted
registry). **Revisit**: backlog item for Phase 4 hardening pass.

**A2 — Same KDF params for registry and per-wallet (Area 3)**.
Both derivations use `KdfParams::CURRENT` (m=64 MiB, t=3, p=4 —
OWASP 2024 strong). Stronger params for the wallet (signing
material) would push a brute-force attempt to ~2–3s on commodity
hardware, but a weak 6-digit PIN is still weak regardless of KDF
cost; the correct mitigation is encouraging strong passphrases.
The activation flow's 500–800ms double-unlock latency (device PIN
+ wallet PIN) is intentional and **must not be eliminated by
caching the derived key in process memory** — the cache window
becomes an attacker's read target. **Revisit**: only if KDF
benchmarks on a target mobile platform make activation feel
painful.

**A3 — `WatchAddresses` (explicit address list) has lower network
privacy than HD watch wallets (Area 10)**. When BHODL syncs a
`WatchAddresses` wallet against a public Esplora endpoint, each
address in the list is queried individually (or in batch — same
information leak). A logging Esplora can correlate that the set
of addresses belongs to one user. HD watch wallets reveal an xpub
or a bloom filter, which is less specific. **Mitigation**: when
the user adds a `WatchAddresses` wallet, surface a prominent "Tor
recommended for this wallet type" prompt in the add-wallet UI.
**Revisit**: BIP-157 compact block filter support is the
production-grade fix — defer to Phase 4+ when mainnet launch is
in scope.

---

## Phase 3.5+ hardening backlog

Items that are real security improvements but do not block Phase 3
shipping. Tracked here so they are not lost.

- **`Zeroizing<String>` on the `format!("x'{hex_key}'")` buffer in
  `open_encrypted_connection`**. Currently the temporary hex-key
  string sits on the Rust heap until the allocator overwrites it.
  Wrap in `Zeroizing<String>` to zero on drop. Trivial change,
  measurable improvement. Slated for end of Phase 3.0 if there is
  capacity, otherwise Phase 3.5.
- **BDK descriptor-string zeroization (xprv strings)**. BDK 3.0's
  `Wallet` state holds the secret descriptor as a `String`
  containing the xprv. These are dropped without zeroing. Check
  whether BDK supports a custom descriptor type implementing
  `ZeroizeOnDrop`; if not, file upstream issue.
- **`PRAGMA shrink_memory` after reads that surface the
  mnemonic**. The promotion flow (watch → signing) reads the
  mnemonic from the wallet DB to verify it against the registry
  descriptor. After this read, SQLCipher's decrypted page cache
  may still hold the plaintext mnemonic page until evicted. Call
  `PRAGMA shrink_memory` (or `sqlite3_release_memory`)
  immediately after to minimize the unlock window.
- **Descriptor-seeded wallet UUID** (A1 above).
- **Compact block filter (BIP-157) sync for `WatchAddresses`**
  (A3 above).
- **PIN strength meter at PIN-setting time**. Currently the only
  PIN policy is `PinValidator.MIN_LENGTH`. Add an entropy-aware
  meter (zxcvbn-style) for users who pick a longer alphanumeric
  passphrase. Encourage 8+ character passphrases for the device
  PIN.

---

## Definition of done — Phase 3 as a whole

- 3.0–3.6 sub-phases all merged to `main`.
- Existing single-wallet users migrate transparently with no data loss (verified by integration test + a manual smoke test).
- The Phase 2 smoke procedures (`docs/test-book.md` §0–§16) still pass after migration.
- New §17a (multi-wallet) added to test-book covering: add watch wallet (all three kinds), switch active, promote watch → signing, aggregate balance, categorisation export/import.
- `:apps:desktop:jvmTest` and `cargo test --workspace` both green.
- The `PaymentEvent` abstraction is documented and references in `handbook.md` §3.6+ (new section) explain how Phase 4 Lightning will slot in.

When all of the above is true, Phase 3 is ✅ and Phase 4 (Lightning) can start.
