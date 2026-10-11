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
- Bypass of platform secure storage when integrated (Keystore on
  Android, Secure Enclave on iOS, OS keyring on desktop).
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

- **SQLCipher**: AES-256-CBC encryption at rest. Encryption key is
  the 32-byte output of `Argon2id(pin, salt)`.
- **Argon2id**: OWASP 2024 strong baseline (m=64 MiB, t=3, p=4).
  Previous wallets used legacy params (m=19 MiB, t=2, p=1).
- **KDF migration**: Wallets without `wallet.kdf.json` (created pre-hardening)
  are auto-upgraded on next unlock. FFI reads salt, derives key with legacy
  params, opens DB, writes `wallet.kdf.json` with current params, calls
  `PRAGMA rekey` to re-encrypt the DB in-place. No downgrade vector.
- **BIP-39 passphrase** (optional): Entered on setup (Advanced toggle),
  stored encrypted at rest in the `bhodl_seed` table alongside the mnemonic.
  The threat model is identical to the mnemonic itself: an attacker with the
  SQLCipher file + salt + PIN can read both. Incorrect passphrase silently
  derives a different wallet (standard BIP-39 behavior).
- **Backup export**: age encryption (age-cli compatible format). Encrypted
  blob + user password. Restore flow decrypts to plaintext mnemonic
  (held in memory, zeroized on drop).

## Supply Chain

Supply chain hardening currently in place:

- Workspace lints forbid `unsafe` in our crates (the FFI shim is the
  only audited exception).
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
