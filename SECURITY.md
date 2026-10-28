# Security Policy

BHODL is a watch-only Bitcoin wallet and finance tracker: it never holds
private keys and cannot send bitcoin. Issues affecting the confidentiality
of the data it stores (xpubs, addresses, balances, labels, tracker data),
its privacy guarantees, or the correctness of what it shows are handled
with priority over all other work.

## Reporting a Vulnerability

**Do not open a public GitHub issue for security reports.**

Report it privately through GitHub's **private vulnerability reporting**:

1. Go to the repository's **Security** tab.
2. Click **Report a vulnerability**
   (direct link: <https://github.com/mariepellegrino89/bhodl/security/advisories/new>).
3. Describe the issue, affected versions / commits, and a reproduction if you have one.

Only the maintainers can see the report. If that form is unavailable, open a public issue
that says only that you need a private security contact, with no details.

BHODL is alpha software and has not been audited. Watch-only wallets work on
mainnet, so treat encryption, key-derivation and privacy bugs as real.

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
  vendors, the user's mobile OS) and the user's signing wallet, which
  BHODL never talks to.
- Third-party Bitcoin libraries we depend on (BDK, rust-bitcoin, SQLCipher,
  etc.) — we route reports to upstream maintainers.
- Attacks on private keys or seed phrases: BHODL never asks for, stores
  or derives them.

## What Counts as a Vulnerability

Any path that could lead to:

- Disclosure of the stored xpubs / descriptors, addresses, labels,
  contacts or tracker data without the device PIN.
- Wrong data that could mislead the user into losing funds: a receive
  address that does not belong to the watched wallet (wrong derivation,
  wrong network, broken descriptor parsing), or incorrect balances.
- Disclosure of metadata that breaks BHODL's privacy guarantees
  (Tor routing, privacy mode, no telemetry) when those features are enabled.
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

- **No private keys**: wallets are added from xpubs, output descriptors or
  address lists. There is no seed generation, import, storage or backup.
  Builds before 2026-10-23 could hold seeds; the current build lists any
  such wallet left on disk and deletes it (keys included) only after the
  user confirms they have its recovery phrase.
- **Device PIN**: the only secret you type. A 32-byte data key is the
  SQLCipher key for `registry.sqlite` (wallet list + tracker tables) and
  every `watch-state/<uuid>.sqlite` (wallet state, labels, contacts). The
  data key is stored only wrapped (XChaCha20-Poly1305) by
  `Argon2id(pin, salt)` in `registry.kdf.json` (v3). Stores created
  before v3 keep their existing key (the old `Argon2id(pin, salt)` output)
  and are re-wrapped on the first unlock, without re-encrypting any file.
- **Recovery code (optional)**: 24 Crockford base32 symbols (~120 bits)
  from the OS CSPRNG, shown once and never stored. It wraps the same data
  key in `registry.recovery.json` under its own Argon2id salt. The AEAD
  binds salt, parameters and purpose (PIN vs code) as associated data, so
  one file cannot stand in for the other. With the code a forgotten PIN is
  reset; without PIN and code the tracker data cannot be decrypted. Wrong
  codes and wrong current PINs (change-PIN) share the persistent lockout.
- **SQLCipher**: AES-256-CBC encryption at rest with the data key.
- **Argon2id**: OWASP 2024 strong baseline (m=64 MiB, t=3, p=4) for new
  data. A descriptor with parameters below the legacy floor (m=19 MiB, t=2,
  p=1) or above the built-in caps is rejected before deriving.
- **KDF descriptor**: `registry.kdf.json` (salt + params + wrapped key)
  is authenticated by the AEAD tag (v3; v1/v2 used an HMAC) and rewritten
  only after the database has accepted the key; a descriptor written by a
  newer build is refused rather than guessed. No downgrade vector.

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
- **Default endpoint**: mempool.space's public Esplora for the wallet's
  network. Overridable per network in Settings (e.g. a self-hosted Esplora).
- **Tor support**: Opt-in SOCKS5 proxy for all Esplora requests (sync) and
  price / historical-rate requests. Requires a local Tor daemon. Adding a
  mainnet wallet without Tor shows a warning first: without Tor or a
  self-hosted endpoint, the Esplora server can link every address of the
  xpub to the user's IP address. With Tor, each wallet, the price traffic
  and any other Esplora call authenticate to SOCKS5 with their own random
  in-memory token, so Tor's `IsolateSOCKSAuth` keeps them on separate
  circuits and one Esplora server cannot link the wallets by circuit.
- **Reset from the locked screen**: with a registry, a permanent wipe needs
  the device PIN (counted in the lockout). Without it ("I forgot the device
  PIN", or "I don't have a recovery code" in the forgot-PIN flow) nothing is
  deleted: the files move to `~/.bhodl-trash/<UTC timestamp>/`, which the
  app never empties.
- **Clipboard**: addresses, txids, xpubs and descriptors are copied with the
  Windows formats that keep them out of clipboard history and cloud
  clipboard; xpubs and descriptors are cleared after 60 s if still there.
