# Wallet vs. Tracking — Information Architecture and Layering Plan

> Supplement to `phase-3-architecture.md`. Authored 2026-05-14 after
> multi-wallet foundation (3.0 / 3.1 / 3.2-partial) landed on
> `phase-3`. This document answers: where does the Wallet/Tracking
> split live, how is data layered, and in what order do the remaining
> sub-phases ship?
>
> Scope: planning only. No Rust or Kotlin code. Implementation follows
> in subsequent iterations once this plan is validated.

---

## 1. Information Architecture

### The core UX question

The current flow is: unlock → Dashboard (one signing wallet). After
the split there are two kinds of surface with different security and
data requirements:

- **Wallet** — custody operations on one selected signing wallet:
  Send, Receive, History (per-wallet), Address book, RBF/CPFP, Labels.
  Requires the per-wallet PIN to be active. Watch wallets in "Wallet"
  mode are read-only (no Send, no Receive) but the UX chrome is the
  same.
- **Tracking** — read-only financial view across ALL wallets (signing
  + watch). Aggregate balance, spending by category, balance over time,
  cross-wallet history, CSV export. Requires only the device PIN
  (`RegistryStore` open). The currently-active signing wallet, if any,
  is included in aggregates — Tracking has read access to the
  `WalletStore` balance for the active wallet, but does NOT drive
  unlock/signing operations.

### Where the split lives: two-tab shell after unlock

After the device-PIN gate (and optionally a per-wallet PIN gate), the
user lands in a **two-tab shell**:

```
┌────────────────────────────────────┐
│  BHODL                     [Lock]  │
├──────────────┬─────────────────────┤
│  [Wallet]    │  [Tracking]         │  ← persistent bottom/top tab bar
├──────────────┴─────────────────────┤
│                                    │
│   (content of selected tab)        │
│                                    │
└────────────────────────────────────┘
```

**Why tabs, not a drawer or two entry points:**

- A drawer hides the split behind a gesture — the distinction between
  custody and tracking is conceptually important and should be
  immediately visible.
- Two completely separate entry points (e.g. two app windows, two
  launcher icons) would break the session model: the user would need
  to device-PIN-unlock twice for the same session, and the
  `RegistryStore` singleton would need cross-process sync.
- Tabs are the lightest pattern that makes both areas always reachable
  with one tap, without either area needing to "host" the other.

The tab bar renders as a **`NavigationRail`** on the desktop (left
side, vertical, two items). On a future KMP iOS/Android target this
maps naturally to a `NavigationBar` (bottom bar). This choice maps
1:1 to the Compose Multiplatform `NavigationRail` / `NavigationBar`
adaptive pattern — no redesign when the mobile targets land.

### Whose state is whose

| State | Owner | Visible from |
|---|---|---|
| `RegistryStore` (open `RegistryHandle`) | Session-level singleton, set at device-PIN unlock | Both tabs |
| `WalletStore` (active signing wallet `WalletPort`) | Session-level singleton, set at per-wallet PIN unlock | Wallet tab (write), Tracking tab (read for active wallet balance only) |
| `SyncCache` (last known balance + timestamp) | Wallet-tab-scoped | Wallet tab |
| Cross-wallet aggregate balance | Derived on demand via `RegistryHandle.aggregateWatchBalanceSat()` + `WalletStore.balanceSat()` | Tracking tab |
| `PaymentEvent` list (Phase 3.3) | Fetched via `RegistryHandle.listEvents(filter)` | Tracking tab (primary), Wallet tab History (per-wallet filter) |

The active signing wallet **belongs to the Wallet tab** in the sense
that Send/Receive/BumpFee only make sense there. But Tracking reads
the active wallet's balance via `WalletStore.current()?.balanceSat()`
— it does not re-open the wallet DB or prompt for a PIN. If no wallet
is active, the Tracking aggregate shows only the watch wallets'
balances (which are available without a wallet PIN).

### What the user sees immediately after activating a wallet

The **start destination after the device-PIN gate is the Wallet tab**,
not a neutral landing. Rationale: the user opened the app to do
something with Bitcoin — send or receive. Tracking is secondary.
If no signing wallet is active (first boot after migration, or user
logged out), the Wallet tab shows the Welcome picker inside its tab
area so the user can select or create a wallet without leaving the
two-tab shell.

Concretely:

```
Cold start (registry exists, no signing wallet active):
  DevicePinUnlock → two-tab shell, Wallet tab selected
    → Wallet tab content = WelcomeScreen (picker mode, registry populated)
    → user picks wallet → per-wallet PIN prompt → WalletStore.set → Dashboard

User taps "Tracking" tab (no signing wallet active):
  → TrackingScreen with aggregate of watch wallets only
  → "No signing wallet active — tap Wallet to activate one" banner
  → tapping the banner navigates to the Wallet tab, not a new unlock flow

User taps "Tracking" tab (signing wallet active):
  → TrackingScreen with aggregate including the active signing wallet
  → balance card shows: watch total + signing total = net total
```

### Route changes required

The `Route` sealed interface needs two additions and one clarification:

```
Route.Tracking          — new; the tracking root screen
Route.TrackingHistory   — new; cross-wallet history list (Phase 3.3)
```

`Route.Dashboard` stays as-is and becomes the **Wallet-tab home**
(replaces the current flat top-level home role). The NavHost's
`startDestination` remains `Route.Dashboard` (or `Route.Welcome` for
new users); the tab bar wraps the NavHost, not the other way around.

Practically: the two-tab shell is a new Composable that wraps the
existing `NavHost` for Wallet and a second `NavHost` for Tracking.
Each tab has its own `NavController` so the Wallet back stack is
independent of the Tracking back stack. This is the standard Compose
Multiplatform multi-tab pattern and avoids conflating the two navigation
graphs.

---

## 2. Data Layer

### PaymentEvent aggregation: free function on RegistryHandle, not a new Object

The `PaymentEvent` query runs in Rust, accessing per-wallet SQLite
files with the same `device_key` that the `RegistryHandle` already
holds. The cleanest placement is **additional methods on the existing
`RegistryHandle`** (which wraps `Registry` + `data_dir` + `device_key`):

```rust
// New method on RegistryHandle (Phase 3.3):
pub fn list_events(&self, filter: EventFilter) -> Result<Vec<PaymentEvent>, WalletError>
pub fn spending_by_category(
    &self, start_unix: u64, end_unix: u64
) -> Result<Vec<CategoryTotal>, WalletError>
pub fn balance_history(
    &self, wallet_ids: Vec<String>
) -> Result<Vec<BalancePoint>, WalletError>
```

**Why not a new `TrackingHandle` FFI Object:**

- Every `#[derive(uniffi::Object)]` adds a bindgen Object with its
  own Arc accounting, destructor hook, and Swift/Kotlin class. A new
  object purely for tracking queries is wasteful given the handle
  already has `data_dir` and `device_key`.
- A `TrackingHandle` would need to be created from a `RegistryHandle`,
  meaning two open handles sharing the same SQLCipher key — no
  correctness benefit over sharing via `RegistryHandle`.
- The constraint is: minimise FFI Objects. Add methods, not Objects.

**Why not Kotlin-side aggregation:**

The `PaymentEvent` list requires reading `list_transactions()` from
multiple wallet DBs (each behind a separate SQLCipher connection
with the device key), normalising BDK's `TxRecord` into a
`PaymentEvent`, and joining with per-wallet BIP-329 labels for the
`category` and `label` fields. This is 100 lines of Rust that would
be 400 lines of Kotlin re-implementing BDK transaction parsing through
the FFI boundary. Keep it in Rust.

### The "unlocked signing wallet" problem

Signing wallets are PIN-encrypted — their chain state lives in
`wallets/<uuid>/wallet.sqlite` behind the **wallet PIN**, not the
device PIN. `RegistryHandle` holds only the device key. Therefore
`list_events` CANNOT open signing wallet DBs.

Three options were considered:

| Option | Pros | Cons |
|---|---|---|
| **(A) Require unlock to include in aggregate** | Zero new storage; correct by construction | User must unlock every signing wallet to see full picture; bad UX for cold storage wallets |
| **(B) Cache last-known balance + events in registry** | Always-available aggregate; no new PIN prompts | Registry balloons; privacy: spending history in a device-PIN-only file; complexity of cache invalidation |
| **(C) Mirror signing wallet chain state into a device-PIN file** | Clean separation; scales to multiple signing wallets | Effectively duplicates BDK's `wallet.sqlite` changeset in a second file; sync coupling |

**Decision (project decision, 2026-05-14): pure (A), no caching.** A signing
wallet that the user has not explicitly mirrored as watch-only does
NOT appear in Tracking when inactive. The mirror prompt at create
time (below) is the user-facing affordance for "I want this wallet
visible in Tracking even when locked"; if the user skips it, the
wallet's balance is simply not visible until they unlock again.

Rationale:
1. **One source of truth per wallet.** Caching introduces a second
   number (`last_known_balance_sat`) that drifts from reality every
   time the wallet receives a tx while locked. The UI would need a
   staleness indicator + "this is N days old" copy that erodes
   user trust faster than no number at all.
2. **Privacy.** The mirror-as-watch path puts the wallet's balance
   under the device-PIN-protected `watch-state/<uuid>.sqlite`, which
   is the existing privacy boundary. Caching scalars in the
   registry would have the same exposure but with no opt-in.
3. **Symmetry.** Watch wallets ARE the way to get cross-wallet
   tracking. Caching would create a second, weaker tracking mode
   alongside the watch mode — two paths to maintain, two failure
   modes to document.

Consequences for the cross-wallet PaymentEvent list (Phase 3.3):

```
events = watch_wallet_events (all, via device key)
       + active_signing_wallet_events (only if WalletStore.current() != null)
```

Inactive signing wallets contribute zero to the Tracking total and
do not appear in cross-wallet history. The picker still shows them
as registry rows so the user can switch into them; Tracking labels
them "Not active — mirror as watch-only to track when not active".

**Schema implications:** the `last_known_balance_sat` /
`last_synced_unix` columns originally proposed in Milestone C are
NOT added. The schema migration step from the milestone table below
is dropped; Milestone D's PaymentEvent FFI no longer needs to read
or write a balance cache. Tradeoff T1 (Kotlin-side merge for active
signing wallet's events) still stands — the merge is conditional on
`WalletStore.current() != null`.

### Mirror prompt at signing-wallet create (project decision, 2026-05-14)

Per the no-cache decision above, **mirroring is the ONLY way** a
signing wallet stays visible in Tracking when locked. The signing-
wallet creation flow (`PinSetupScreen`) therefore gains a post-create
`AlertDialog` that makes the choice explicit:

> **Track this wallet?**
> Add this wallet to the Tracking view as a watch-only entry. With
> tracking enabled, the wallet's balance and history are visible in
> the Tracking tab even when the wallet is not active (unlocked).
>
> If you skip, this wallet will not appear in the Tracking total
> when locked. You can always come back and add it later from the
> wallet picker.
>
> _[Mainnet only:] Note: this is a mainnet wallet. Tracking syncs
> against a public Esplora server, which logs every address it sees.
> Enabling Tor in Settings before continuing protects your IP._
>
> [Skip] [Add to tracking]

If the user picks **Add to tracking** and the wallet is on `mainnet`
without Tor enabled, a second confirm interstitial mirrors the
`AddWatchScreen` Tor warning ("Continue without Tor" / "Cancel").

**Implementation** (already shipped in commit before Milestone A):
- After `WalletHandle.createOrLoadPersistent` + `addWallet`, the
  screen pulls the public descriptor (`exportPublicDescriptor()`) and
  calls `registry.addWatchXpub(...)` with a default label
  `"<wallet name> (tracker)"` (uniquified with `(tracker N)` if it
  collides). One best-effort initial sync follows so the Tracking
  tab has a real balance immediately.
- Skip is silent — the user can still mirror later via Welcome
  picker → "+ Add new wallet" → "Add watch-only" → paste the xpub
  from Settings → "Show watch-only descriptor".

With the no-cache decision, a signing wallet the user has NOT
mirrored simply shows "Not active — mirror as watch-only to track
when not active" in the Tracking list and contributes zero to the
total. No staleness indicator, no silent drift — the only number
visible is one the user opted into.

### Categories: per-wallet BIP-329 stays correct

The existing design (categories as a column on `bhodl_labels` per
wallet DB) is correct for Phase 3 and should not be hoisted to the
registry. Reasons:

1. **BIP-329 export integrity**: the spec expects labels to travel
   with the wallet. A Sparrow/Liana import of a wallet's JSONL should
   bring its categories too. Registry-level categories would create a
   split between "what Sparrow sees" and "what BHODL tracks".
2. **Security model consistency**: financial annotations (category =
   "Medical payment", label = "Dr. Smith") belong in the per-wallet
   encrypted file. The registry is protected only by the device PIN;
   if an attacker learns the device PIN, they should NOT also get
   a full annotated spending history. The per-wallet PIN gates that.
3. **Watch wallets are device-PIN-protected anyway**: watch wallet chain
   state already lives in `watch-state/<uuid>.sqlite` (device key).
   Their labels and categories live there too — no migration needed.

The one complication: cross-wallet category aggregation in the
Tracking screen requires opening each watch wallet's labels table
(device key, already available) and reading the active signing wallet's
labels table (wallet key, handle already open). This is exactly the
same access model as the PaymentEvent aggregation above — consistent.

**Schema change for categories (Phase 3.4):**

The `bhodl_labels` table gains a `category TEXT` column (nullable,
NULL = uncategorised):

```sql
ALTER TABLE bhodl_labels ADD COLUMN category TEXT;
```

This ALTER is forward-compatible: any tool that reads the JSONL export
ignores unknown fields (BIP-329 §3: "implementations MUST ignore
unknown fields"). Existing rows default to NULL. The `Label` Rust
struct gains `category: Option<String>`.

---

## 3. Migration / Refactor Order

### Current state (branch `phase-3`, 18 commits ahead of `main`)

- 3.0 migration: **done** (atomic two-phase commit, migration.lock recovery)
- 3.1 watch wallets: **done** (WatchHandle, aggregate_watch_balance_sat,
  WatchBip variants, registry convenience constructors)
- 3.2 activation flow:
  - Boot guard → DevicePinUnlock → Welcome (picker) → Unlock: **done**
  - AddWatch flow: **done**
  - Per-wallet switch from Settings: **pending** (3.2-C)
  - Same-PIN warning banner: **pending** (3.2-D)
  - Promote watch → signing flow: **pending** (3.2-E)
  - Add new signing wallet from picker: **pending** (3.2-F)
  - DebugWatch removal: **pending** (3.2-G, blocked by 3.2-F)
  - Per-wallet colour: deferred to 3.6
- 3.3 PaymentEvent + unified history: **not started**
- 3.4 Categorisation: **not started**
- 3.5 Tracking dashboard: **not started**
- 3.6 Polish: **not started**

### Proposed re-ordering

The two-tab shell is the "container" that frames both areas. The
minimum viable version of the container is cheap to ship because
Tracking tab initially just shows the aggregate balance (already
computable via `aggregate_watch_balance_sat` + `WalletStore`). The
full tracking content fills in later.

The goal: the Wallet/Tracking split is visible in the UI as early as
possible, with Wallet fully working and Tracking showing real data
progressively.

#### Milestone A — Two-tab shell (insert before 3.2-C, 1–2 days)

**What ships:**

- `NavigationRail` wrapper composable around the existing `NavHost`
  (Wallet tab) + a new minimal `NavHost` (Tracking tab).
- `Route.Tracking` — initially renders `TrackingStubScreen`:
  aggregate balance from `aggregate_watch_balance_sat()` +
  `WalletStore.current()?.balanceSat()`, wallet count from
  `RegistryStore`, "More insights coming soon" placeholder.
- The stub screen does NOT require `PaymentEvent` — it uses the
  APIs that already exist today.
- Tab bar is the only visible UI change for Wallet users — existing
  Dashboard / History / Send / Receive flows are untouched.

**What this unblocks:**

- The Wallet/Tracking split concept can be validated with real data
  (real wallet balances show in Tracking) before the heavy 3.3 work.
- The route infrastructure is in place so 3.3 adds screen content
  without touching navigation plumbing.

**Blocks:** nothing in 3.2 depends on this. Insert after 3.2-B
(boot guard done), before 3.2-C (per-wallet switch from Settings).

#### Milestone B — Complete 3.2 activation flows (3.2-C through 3.2-G)

In this order, no re-ordering vs the existing plan:

- **3.2-C**: Per-wallet switch from Settings (`destroy()` → new PIN
  prompt → `WalletStore.set`). Updates `WalletStore.activeWalletId`.
- **3.2-D**: Same-PIN warning banner (one-time, dismissible, persisted
  via `registry.mark_pins_warned`). Defensive UX, no data model change.
- **3.2-E**: Promote watch → signing (device PIN re-auth, descriptor
  verification preview, mnemonic entry, match check). The most complex
  flow in 3.2; do it last in this milestone.
- **3.2-F**: "Create new / Restore" from the Welcome picker (these flows
  already exist as screens; this is routing + registry row creation).
- **3.2-G**: Remove `Route.DebugWatch` and `DebugWatchScreen`; its
  functionality is now covered by the Welcome picker + AddWatch.

Estimated: 4–5 days (same as original 3.2 estimate, no re-ordering
needed within the milestone itself).

#### Milestone C — DROPPED (project decision, 2026-05-14)

Originally proposed a balance cache (`last_known_balance_sat`,
`last_synced_unix`) on the registry `wallets` table so inactive
signing wallets could show a stale-but-visible balance in Tracking.

**Rejected.** A signing wallet that the user has not mirrored as
watch-only does not appear in Tracking when locked. The mirror-as-
watch path is the single, opt-in mechanism for cross-wallet
tracking; caching would have been a second, weaker path. See
§"The 'unlocked signing wallet' problem" above for the full
rationale and consequences. The schema stays at v1; no migration
runs before Milestone D.

#### Milestone D — Phase 3.3: PaymentEvent + unified history (3–4 days)

No re-ordering vs the original plan. Two parts:

**Part D1 — Rust `PaymentEvent` + `RegistryHandle.list_events`**

- `PaymentEvent` Record (FFI `#[derive(uniffi::Record)]`; not an
  Object — it is data, not a handle with lifetime). The struct
  mirrors the spec in `phase-3-architecture.md` §PaymentEvent.
- `EventFilter` Record: `{ wallet_ids: Option<Vec<String>>,
  direction: Option<Direction>, from_unix: Option<u64>,
  to_unix: Option<u64>, category: Option<String> }`.
- `RegistryHandle.list_events(filter)`: reads all watch wallet
  `TxRecord`s via device key; merges with active signing wallet
  events via `WalletStore`-compatible pattern (the Rust function
  receives an optional pre-opened `WalletHandle` reference, or
  the Kotlin side calls it separately and merges in Kotlin — see
  trade-off [T1] below).
- `RegistryHandle.list_events` sorts by `timestamp_unix DESC`,
  applies the filter, returns `Vec<PaymentEvent>`.

**Trade-off [T1] — where to merge signing wallet events:**

Option 1 (Rust merges): `list_events` opens a **read-only** copy of
the active wallet DB using the wallet key, which the `RegistryHandle`
does NOT hold. Passing the wallet key into `list_events` crosses a
security boundary — the registry layer would be holding both keys.

Option 2 (Kotlin merges): `list_events` returns watch-wallet events.
Kotlin calls `WalletHandle.list_transactions()` separately for the
active wallet, converts them to `PaymentEvent` on the Kotlin side
(simple struct mapping, no BDK parsing), merges and sorts. This is
~50 lines of Kotlin mapping, acceptable.

**Recommendation: Option 2.** The registry layer and the wallet layer
keep their key isolation. The Kotlin `TrackingViewModel` merges the
two lists before handing them to the composable. The merge is O(n log n)
on the Kotlin side; for typical wallet sizes (< 10 000 events) this
is imperceptible.

**Part D2 — UI changes**

- `TrackingScreen` gains a scrollable cross-wallet history list
  (replaces the stub). Filter chip: "All wallets" (default) / per
  wallet. This is the unified history surface from Phase 3.3's
  original scope.
- `HistoryScreen` (Wallet tab) stays per-wallet — it shows
  `WalletHandle.list_transactions()` for the active wallet. No
  change needed.
- `TransactionDetailsScreen` gains a "Wallet: {label}" row when
  reached from the Tracking tab (the `wallet_id` is carried in
  the `PaymentEvent`; looked up in the registry for its label).
  When reached from the Wallet tab the existing behaviour is
  unchanged.

#### Milestone E — Phase 3.4: Categorisation (3 days)

No re-ordering. Schema ALTER + `Label.category` field + UI:
- `TransactionDetailsScreen` category picker (dropdown + "Create new").
- Seed categories stored in `SettingsStore` (editable list).
- Export UX: encrypted `.age` primary, plaintext opt-in with warning.

One clarification vs the original plan: the `category` column is
added in **both** watch wallet `watch-state/<uuid>.sqlite` files and
signing wallet `wallets/<uuid>/wallet.sqlite` files (same `ALTER`
migration in `OnchainAccount::ensure_schema` and `WatchAccount::ensure_schema`).
The `ALTER TABLE` is idempotent on repeated opens (wrap in
`IF NOT EXISTS` or catch `duplicate column` error in Rust).

#### Milestone F — Phase 3.5: Tracking dashboard charts (1 week)

No re-ordering. Adds to `TrackingScreen`:
- `RegistryHandle.spending_by_category(start, end)` — new method,
  aggregates watch wallet categories. Kotlin adds active signing
  wallet categories from `WalletHandle.list_labels()` and merges.
- Pie chart (spending by category), line chart (balance over time).
- Top counterparties list.
- CSV export.

Chart library decision deferred to start of this milestone (half-day
spike on `koalaplot-core` vs hand-rolled Canvas).

#### Milestone G — Phase 3.6: Polish

As specified. No re-ordering.

### Summary table

| Milestone | Scope | Days | Blocks |
|---|---|---|---|
| **A** (new) | Two-tab shell + Tracking stub | 1–2 | Tracking UX is visible early |
| **B** | 3.2-C through 3.2-G (complete activation flows) | 4–5 | Full wallet management |
| **C** | ~~Balance cache columns~~ DROPPED (no-cache decision) | 0 | — |
| **D** | 3.3 PaymentEvent + unified history | 3–4 | Cross-wallet history |
| **E** | 3.4 Categorisation | 3 | Spending breakdown |
| **F** | 3.5 Charts + export | ~7 | Full tracking dashboard |
| **G** | 3.6 Polish | 3 | Phase 3 done |

Total: ~21.5–24.5 days. Slightly under the original aggregate now that
Milestone C is dropped; Milestone A still gives the project visible progress
on the split after 1–2 days rather than waiting for 3.3 to ship.

---

## 4. Risks and Trade-offs

### Risk 1 — Two NavControllers, shared RegistryStore: state desync

**What can go wrong:** the two-tab NavHost pattern (Wallet NavController
+ Tracking NavController) means screen-level state can diverge. If the
user activates a new signing wallet from the Wallet tab, the Tracking
tab's aggregate balance composable must recompose to include the new
wallet. Today `WalletStore` and `RegistryStore` are `@Volatile`
singletons, not Compose `State` — a Tracking composable observing
`RegistryStore.current()` will not automatically recompose when
`WalletStore.set(...)` is called from the Wallet tab.

**Severity:** medium. Incorrect balance shown in Tracking until the
user navigates away and back.

**Likelihood:** high — this will happen on the first real multi-wallet
session.

**Mitigation:** wrap the parts of `WalletStore` and `RegistryStore` that
the Tracking screen observes in `MutableStateFlow` (or Compose
`mutableStateOf` at the singleton level). `WalletStore.txEpoch` already
uses `mutableStateOf` — extend this pattern to `activeWalletId` and
expose a `trackingEpoch` that the Wallet tab bumps after any balance-
changing operation. The Tracking composable observes `trackingEpoch`
via `LaunchedEffect` and re-fetches.

**Address now or defer:** address during Milestone A (two-tab shell),
before the first real tracking data renders. The fix is 10–15 lines
of `mutableStateOf` changes to the two singletons.

---

### Risk 2 — Future schema migrations (deferred, still relevant)

**What can go wrong:** the original Milestone C cache columns are
dropped (project decision, 2026-05-14), but the schema migration risk remains
for **future** changes — most imminently the Phase 3.4 `category`
column on `bhodl_labels`, and any later additions to the registry
`wallets` table.

SQLite `ALTER TABLE … ADD COLUMN` without a guard throws "duplicate
column name" on a re-run, which would abort `ensure_schema()` and
make the registry unopenable for any user who has already opened
the DB once on the new build.

**Severity:** high — unbootable app for existing users.

**Likelihood:** medium — first surfaces at Milestone E (Phase 3.4
category column).

**Mitigation:** add a `user_version` pragma guard pattern to
`ensure_schema()` as soon as the first migration is needed:

```rust
let version: i32 = conn.pragma_query_value(None, "user_version", |r| r.get(0))?;
if version < 2 {
    conn.execute_batch("
        ALTER TABLE bhodl_labels ADD COLUMN category TEXT;
        PRAGMA user_version = 2;
    ")?;
}
```

This is the standard SQLite migration approach and handles both
fresh installs (DDL constants include all columns from day one)
and upgrades (ALTER path). Document this once in the registry /
wallet schema files so every contributor adopts the same pattern.

**Address now or defer:** defer to Milestone E when the first
migration becomes necessary. No code changes required at
Milestone A.

---

### Risk 3 — PaymentEvent Kotlin-side merge: sort order and gap limit edge case

**What can go wrong:** the Kotlin merge of watch wallet events
(from `RegistryHandle.list_events`) + active signing wallet events
(from `WalletHandle.list_transactions`) produces an interleaved
list sorted by `timestamp_unix DESC`. Pending transactions have
`timestamp_unix = None` (not yet confirmed). If both the watch wallet
and the signing wallet have unconfirmed transactions, the sort order
for those transactions is undefined — they all sort as "pending" but
the relative order within "pending" varies between runs.

A subtler case: the `direction` field of a transaction between the
active signing wallet and one of the watch wallets will be
`SelfTransfer` from one perspective and `Incoming`/`Outgoing` from
the other. Cross-wallet self-transfers (e.g. user consolidates from
cold storage into hot wallet) will appear **twice** in the unified
history unless de-duplicated by `txid`.

**Severity:** medium. Cosmetic bug (duplicate rows) that undermines
trust in the history view.

**Likelihood:** medium — this scenario is explicit in Phase 3's goals
("users hold sats across multiple wallets"). This will happen in
manual testing.

**Mitigation:**

- De-duplicate by `txid_or_payment_id` in the Kotlin merge step.
  When the same `txid` appears in two wallets' event lists, keep the
  one where `direction == SelfTransfer` if either wallet tagged it as
  such; otherwise keep the `Outgoing` perspective (the wallet that
  paid the fee) and annotate `counterparty` with the receiving wallet
  label.
- For pending sort order: assign a secondary sort key of
  `(timestamp_unix ?? Long.MAX_VALUE, txid_lexicographic)` so
  pending transactions sort consistently across re-renders.
- Document the cross-wallet txid de-dup logic in
  `TrackingViewModel` with a comment referencing this risk.

**Address now or defer:** design decision now (documented here);
implementation during Milestone D when the merge code is written.
The de-dup logic is 20–30 lines and should be covered by a unit test
with a fixture two-wallet scenario.

---

## Appendix: Phase 3.2 sub-phases unchanged by this plan

For completeness, the sub-phases that were already specified in
`phase-3-architecture.md` and are NOT re-ordered by this document:

- **3.2-D (same-PIN warning)**: no change. Still fires at wallet
  activation time, reads `WalletRecord.pins_warned` from registry.
- **3.2-E (promote watch → signing)**: no change. Device PIN re-auth,
  descriptor verification, mnemonic entry. The flow lives in the
  Wallet tab (it is a custody operation), not Tracking.
- **BIP-329 export UX (3.4-F3)**: the `category` field privacy concern
  (financial PII in plaintext JSONL) still applies. The encrypted `.age`
  primary / plaintext opt-in secondary pattern in the original spec
  is correct and unchanged.
- **A1 (UUIDv4 for wallet identity)**: still deferred to Phase 4
  hardening.
- **A2 (KDF params)**: unchanged.
- **A3 (WatchAddresses privacy, Tor recommendation)**: unchanged.

---

## Open questions — new, raised by this plan

**Q1**: Should the `NavigationRail` tab labels be "Wallet" and
"Tracking" or something else? "Finance" or "Portfolio" are common
alternatives in the industry. "Tracking" is honest about what the
feature does; it may feel less compelling as a product term. The project to
decide; the code uses `TabDestination.Wallet` / `TabDestination.Tracking`
enum names regardless.

**Q2**: On cold start with no signing wallet and multiple watch wallets,
what is the "Wallet" tab's content? Today this would be the Welcome
picker. Is it acceptable for the picker to live inside the Wallet tab
area? The alternative is a modal overlay before the tabs mount. The
tab-area approach is simpler (no new route) and consistent with how
Settings and History already live inside the tab. Recommendation: yes,
keep the picker inside the Wallet tab.

**Q3**: Does the `last_known_balance_sat` in the registry need a
per-wallet `network` qualifier for the display? A user with a mainnet
signing wallet + a signet signing wallet will see a combined sat figure
that mixes networks. The `WalletRecord.network` column already exists
and the Tracking aggregate balance card should group by network (same
issue noted in `phase-3-architecture.md` §Open questions). The project to
confirm whether the Phase 3.5 balance card should show a network
breakdown or defer that to Phase 3.6 polish.
