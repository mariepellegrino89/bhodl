# Security Policy

BHODL is a self-custodial Bitcoin wallet. Issues affecting fund safety,
key material confidentiality, or privacy guarantees are handled with
priority over all other work.

## Reporting a Vulnerability

**Do not open a public GitHub issue for security reports.**

Report it privately through GitHub's **private vulnerability reporting**:

1. Go to the repository's **Security** tab.
2. Click **Report a vulnerability**
   (direct link: <https://github.com/mariepellegrino89/bhodl/security/advisories/new>).
3. Describe the issue, affected versions / commits, and a reproduction if you have one.

Only the maintainers can see the report. If that form is unavailable, open a public issue
that says only that you need a private security contact, with no details.

BHODL is alpha software and runs on test networks only, but treat key-handling,
encryption and signing bugs as real: they will reach mainnet if not fixed.

We will acknowledge receipt within 72 hours, and provide a triage
classification within 7 days. We commit to keeping you informed of
progress on the report at least every 14 days until resolution.

## Scope

In scope:

- The Rust core crates under `crates/`.
- The KMP shared module under `shared/` and the platform apps under
  `apps/`.
- Build artifacts (Android APK, iOS framework, desktop installers) once
  reproducible-build CI is published.
- The default endpoints BHODL talks to (the Esplora server, by default
  mempool.space, and the price APIs) only insofar as BHODL trusts them.

Out of scope:

- The user's own infrastructure (self-hosted nodes, hardware wallet
  vendors, the user's mobile OS).
- Third-party Bitcoin libraries we depend on (BDK, rust-bitcoin, SQLCipher,
  etc.) — we route reports to upstream maintainers.
- Theoretical attacks that require key material we never store
  (e.g. attacks on the BIP39 seed once it has been entered: the user is
  responsible for not exposing the seed phrase).

## What Counts as a Vulnerability

Any path that could lead to:

- Loss of funds (incorrect signatures, broken descriptors, fee
  miscalculation that prevents spending).
- Disclosure of seed material, descriptor, or private keys.
- Disclosure of metadata that breaks BHODL's privacy guarantees
  (Tor routing, PayJoin, privacy mode, no telemetry) when those features are enabled.
- Bypass of the on-disk encryption layer (SQLCipher AES-256).
- Bypass of the OS-keystore device binding on desktop (Windows DPAPI,
  macOS Keychain, Linux Secret Service), e.g. any way to test PIN guesses
  offline from a copy of the data directory alone, or platform secure
  storage once integrated on mobile (Android Keystore, iOS Secure Enclave).
- Downgrade of KDF parameters (e.g. OWASP 2024 strong → legacy weak)
  across versions.

## Disclosure Timeline

We follow coordinated disclosure:

1. Report received privately.
2. We confirm reproducibility and assess impact.
3. A patch is prepared, reviewed, and merged behind closed doors when
   the issue is critical.
4. A coordinated release is published with the patch.
5. A public advisory follows, crediting the reporter unless they
   request anonymity. The advisory is published in the GitHub
   Security Advisories tab.

Default embargo period: 90 days from the report date, extendable by
mutual agreement if the patch requires upstream coordination
(e.g. with rust-bitcoin or BDK).

## Bug Bounty

There is no bug bounty programme yet. Researchers are credited in
the advisory unless they prefer to stay anonymous.

## Encryption and Key Derivation

- **SQLCipher**: AES-256-CBC encryption at rest for the wallet DB, the
  device registry and every watch-only state file.
- **Key schedule**: `pin_key = Argon2id(PIN, salt)`. On a device with an
  OS keystore the database key is
  `HKDF-SHA256(pin_key || device_secret, salt, "bhodl/sqlcipher-key/v1 …")`,
  where `device_secret` is a random 256-bit value generated when the wallet
  or registry is created and held by the operating system:
  - Windows: DPAPI, `CurrentUser` scope (the DPAPI blob is stored in the
    KDF descriptor; only the same Windows account on the same machine can
    decrypt it).
  - macOS: a generic-password item in the login Keychain
    (service `io.bhodl.device-key`).
  - Linux: an item in the Secret Service `default` collection (GNOME
    Keyring, KWallet, KeePassXC).
- **Threat model**: someone who copies the data directory (`~/.bhodl`) —
  a backup, a synced folder, a disk image read from another account — gets
  the salt and the encrypted databases but not the device secret, so they
  cannot test PIN guesses offline. This is what makes a 6-digit PIN
  acceptable. An attacker who also controls the user's OS account (malware
  running as the user, or the full disk *plus* the login password) can
  recover the device secret; the PIN is then the only barrier
  (Argon2id, ~0.5–0.8 s and 64 MiB per guess), so 8+ digits or a passphrase
  remain the recommendation.
- **No keystore** (e.g. headless Linux without a Secret Service): new
  wallets and registries are not device-bound and **require a passphrase of
  at least 12 characters** instead of a PIN. The app explains why on the
  setup screen; it never silently falls back to a short PIN. An optional
  passphrase mode is also offered when a keystore is available.
- **Recovery implications**: if the OS keystore entry is lost (OS
  reinstall, a different user account, a reset keyring, data dir restored
  on another machine), a device-bound store cannot be opened with the PIN
  any more. The unlock screen reports this distinctly from "wrong PIN" (and
  does not count it against the PIN lockout); the user restores from the
  BIP-39 recovery phrase or an encrypted backup. Copying `~/.bhodl` to a new
  machine is therefore **not** a backup.
- **Argon2id**: OWASP 2024 strong baseline (m=64 MiB, t=3, p=4).
  Previous wallets used legacy params (m=19 MiB, t=2, p=1).
- **KDF descriptor** (`wallet.kdf.json`, `registry.kdf.json`): versioned,
  HMAC-sealed after the first successful open. v3 records the key
  protection (`device_key` with the keystore backend + handle, or
  `secret_only`) and its MAC covers that record. Builds that predate v3
  refuse it instead of deriving a wrong key.
- **KDF / device-binding migration**: PIN-only wallets and registries
  (and wallets with legacy Argon2id params) are migrated transparently on
  the first unlock where a keystore is reachable. Crash-safe ordering: the
  device secret is stored in the keystore, the new descriptor is written to
  `<descriptor>.next` and fsynced, `PRAGMA rekey` re-encrypts the DB, and
  only then is `.next` renamed over the old descriptor. At every point
  exactly one descriptor opens the DB; the next unlock falls back to
  `.next` if needed and discards a stale one (and its keystore entry). The
  watch-only state files share the registry key and are re-encrypted
  right after the registry (resumable, idempotent). If no keystore is
  available the store keeps opening exactly as before. No downgrade vector.
- **BIP-39 passphrase** (optional): Entered on setup (Advanced toggle),
  stored encrypted at rest in the `bhodl_seed` table alongside the mnemonic.
  The threat model is identical to the mnemonic itself: an attacker with the
  SQLCipher file + the database key can read both. Incorrect passphrase
  silently derives a different wallet (standard BIP-39 behavior).
- **Backup export**: age encryption (age-cli compatible format). Encrypted
  blob + user password. Restore flow decrypts to plaintext mnemonic
  (Rust-side copies zeroized on drop; see the limitations below for the
  copies that cross the FFI boundary).

### Known memory-hygiene limitations

Secret material is wiped best-effort on the Rust side (`Zeroizing`,
`non_secure_erase`), but the recovery phrase and backup password cannot be
fully scrubbed once they cross the Kotlin/Rust FFI boundary:

- `WalletHandle.peek_mnemonic`, `WalletHandle.generate_random_mnemonic` and
  `decrypt_backup` return plain `String`s. uniffi lowers them into a
  `RustBuffer` that is freed but not zeroed, so one un-wiped copy remains
  in the native heap until reused.
- On the JVM the phrase lives in immutable `String` objects (and Compose
  text state) that cannot be wiped and persist until garbage collection.
- A Rust-owned `#[uniffi::Object]` holding a `Zeroizing<String>` with an
  explicit `wipe()` would shrink this, but is blocked today: the Windows
  KMP uniffi fork crashes on `String`-returning methods of any
  `#[uniffi::Object]` other than `WalletHandle`
  (`docs/uniffi-runtime.md`, Hazard 2), and the desktop screens consume
  the phrase as `String` throughout.

Mitigation: an attacker able to read process memory of an unlocked wallet
is out of scope (see Scope); keep the reveal and restore screens short
lived and use full-disk encryption with swap/hibernation protection.

## Supply Chain

Supply chain hardening currently in place:

- Workspace lints forbid `unsafe` in our crates. Audited exceptions: the
  UniFFI shim and the Windows DPAPI module of `bhodl-keystore`
  (`unsafe` denied crate-wide, allowed only in `dpapi.rs`).
- `cargo-deny` runs in CI: license allow-list, source allow-list, and
  yanked-crate detection.
- All workspace dependencies pinned to specific minor versions.
- `Cargo.lock` is committed.
- `cargo audit` advisory scan (ignores documented in `.cargo/audit.toml`).
- Gradle dependency verification (sha256) in `gradle/verification-metadata.xml`.
- All commits to `main` are merged via pull request.
- Integration tests with real Bitcoin Core (bitcoind regtest + electrs)
  verify against a live node (Testcontainers).

Planned:

- Sigstore / cosign signing of release artifacts.
- Reproducible builds verified by an independent reviewer.
- SBOM generated per release.

## Privacy & Network

- **No telemetry**: BHODL does not phone home.
- **Default endpoint**: `mempool.space/signet` for signet testing.
  Overridable in Settings.
- **Tor support**: Opt-in SOCKS5 proxy for all Esplora requests
  (sync, fee estimates, broadcast) and PayJoin endpoints. Requires a local
  Tor daemon.
- **Future mainnet**: Auto-sync will be gated behind explicit user consent,
  and self-hosted / Tor-only endpoints will be promoted before launch.
