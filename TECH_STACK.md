# BHODL — Elenco Completo delle Tecnologie

> Wallet bitcoin self-custodial multi-layer con tracking nativo di net worth e spese.
> **Brand**: BHODL · **Licenza**: Open Source · **Lancio**: globale · **Stato**: pianificazione, maggio 2026.

Documento riepilogativo di **tutte le tecnologie, librerie, protocolli, standard e servizi** previsti nel progetto.

**Convenzioni**:
- Priorità: **P0** = MVP (mese 1-11), **P1** = post-MVP (12-15), **P2** = (16-22), **P3** = (23+).
- Licenze: tutte verificate per compatibilità con distribuzione open-source MIT/Apache-2.0 del progetto.

---

## 1. Linguaggi & runtime

| Tecnologia | Versione | Ruolo | Licenza | Priorità |
|---|---|---|---|---|
| **Rust** | 1.85+ stable | Wallet core, crypto, network, FFI exports | MIT/Apache-2.0 | P0 |
| **Kotlin** | 2.2+ | App layer (KMP common + JVM) | Apache-2.0 | P0 |
| **Kotlin/Native** | (con Kotlin 2.2+) | iOS native targets | Apache-2.0 | P0 |
| **Java Foreign Memory API** (jextract) | JDK 21+ | Desktop OS interop (keyring) | GPL+CE | P0 |
| **Swift** | 5.10+ | Interop iOS specifico (Keychain, Secure Enclave) via cinterop | Apache-2.0 | P0 |
| **C** (system glue) | C17 | Per binding nativi quando necessario | n/a | P0 |
| **Bash / PowerShell** | latest | Build script | n/a | P0 |
| **TypeScript** | 5.x | Tooling/scripts (eventuali strumenti dev) | Apache-2.0 | P1 |

---

## 2. Frontend / UI framework

| Tecnologia | Versione | Ruolo | Licenza | Priorità |
|---|---|---|---|---|
| **Kotlin Multiplatform (KMP)** | 2.2+ | Code sharing iOS/Android/desktop | Apache-2.0 | P0 |
| **Compose Multiplatform** | 1.8+ (iOS Stable) | UI declarativa cross-platform | Apache-2.0 | P0 |
| **Compose for Android** | 1.8+ | UI Android nativa | Apache-2.0 | P0 |
| **Compose for iOS** | 1.8+ | UI iOS via Skia + iOS-native gestures | Apache-2.0 | P0 |
| **Compose for Desktop** | 1.8+ | macOS/Windows/Linux desktop | Apache-2.0 | P0 |
| **AndroidX Activity/Lifecycle** | latest | Android lifecycle | Apache-2.0 | P0 |
| **Material 3 Design** | latest | Design system base (override custom) | Apache-2.0 | P0 |
| **Voyager** o **Decompose** | latest | Navigation cross-platform KMP | MIT/Apache-2.0 | P0 |
| **Koin** o **kotlin-inject-anvil** | latest | DI per KMP | Apache-2.0 | P0 |
| **Coil 3 KMP** | latest | Image loading cross-platform | Apache-2.0 | P1 |
| **Jetpack Compose Navigation** | latest | Fallback Android-only se KMP nav non basta | Apache-2.0 | P0 |

**Fallback** (se Compose Multiplatform iOS bloccante post-spike Phase 0): **Flutter 3.38+** con **flutter_rust_bridge v2** (Cake Wallet precedente).

---

## 3. FFI / bindings Rust → Kotlin/Swift

| Tecnologia | Versione | Ruolo | Licenza | Priorità |
|---|---|---|---|---|
| **uniffi-rs** | latest | FFI generation core | MPL-2.0 | P0 |
| **uniffi-kotlin-multiplatform-bindings** | UbiqueInnovation fork | Kotlin Multiplatform target | Apache-2.0 | P0 |
| **uniffi-bindgen-react-native** | (fallback only) | Se rollback a RN | Apache-2.0 | – |
| **flutter_rust_bridge** | v2.x | (fallback only) Se rollback a Flutter | MIT | – |
| **Kotlin/Native cinterop** | (con Kotlin 2.2+) | Bridging C headers Apple (Keychain, Secure Enclave) | Apache-2.0 | P0 |
| **JNI** (via UniFFI) | JDK 21+ | Bridging Android Keystore, BiometricPrompt, NFC | n/a | P0 |

---

## 4. Bitcoin core (on-chain)

| Tecnologia | Versione | Ruolo | Licenza | Priorità |
|---|---|---|---|---|
| **bdk_wallet** | 1.x stable | Wallet descriptor-based, coin selection, persistence | MIT/Apache-2.0 | P0 |
| **rust-bitcoin** | latest | Primitives (TX, script, PSBT, taproot) | CC0-1.0 / MIT | P0 |
| **rust-miniscript** | latest | Miniscript / policy compilation | CC0-1.0 | P0 |
| **libsecp256k1** | upstream Bitcoin Core | Schnorr, ECDSA, Taproot, MuSig2 primitives | MIT | P0 |
| **secp256k1 (rust binding)** | latest | Rust wrapper di libsecp256k1 | CC0-1.0 / MIT | P0 |
| **bitcoin_hashes** | (parte di rust-bitcoin) | Hash primitives | CC0-1.0 / MIT | P0 |
| **bdk_chain** | 1.x | Chain data structures | MIT/Apache-2.0 | P0 |
| **bdk_esplora** | 1.x | Esplora API client per chain sync | MIT/Apache-2.0 | P0 |
| **bdk_electrum** | 1.x | Electrum protocol client (alternativa) | MIT/Apache-2.0 | P0 |
| **bdk_bitcoind_rpc** | 1.x | RPC nodo personale | MIT/Apache-2.0 | P1 |
| **bdk_kyoto** | latest | BIP158 compact block filters (privacy-respecting) | MIT/Apache-2.0 | P1 |

---

## 5. Privacy stack

| Tecnologia | Versione | Ruolo | Licenza | Priorità |
|---|---|---|---|---|
| **rust-payjoin** (PDK / Payjoin Dev Kit) | latest | BIP78 + BIP77 v2 send/receive | MIT | P0 |
| **bdk-sp** | sperimentale | Silent Payments BIP352 send/receive | MIT/Apache-2.0 | P0 |
| **rust-bitcoin/bip324** | latest | V2 P2P encrypted transport | MIT/Apache-2.0 | P0 |
| **arti** (Tor in Rust) | latest stable | Tor client embedded | Apache-2.0/MIT | P1 |
| **i2p-rs** o equivalente | TBD | I2P client (opzionale advanced) | MIT/Apache-2.0 | P2 |
| **Frigate** (ephemeral SP scanner) | latest | Server SP scanning | MIT | P1 |
| **wabisabi-client** | (TBD) | WabiSabi coinjoin coordinator-agnostic | MIT | P2 |

---

## 6. Lightning Network — dual path

### Path A — Easy mode (default Beginner)

| Tecnologia | Versione | Ruolo | Licenza | Priorità |
|---|---|---|---|---|
| **breez-sdk-liquid** | v0.12+ | Nodeless Lightning + Liquid + Boltz integrato | MIT | P0 |
| **breez-sdk-liquid Kotlin bindings** | (UniFFI generated) | KMP/Android bindings | MIT | P0 |
| **breez-sdk-liquid Swift bindings** | (UniFFI generated) | iOS bindings | MIT | P0 |

### Path B — Full self-custody (tier 3 power user)

| Tecnologia | Versione | Ruolo | Licenza | Priorità |
|---|---|---|---|---|
| **lightning** (rust-lightning) | latest | LDK core | MIT/Apache-2.0 | P0 |
| **lightning-net-tokio** | latest | TCP transport per LDK | MIT/Apache-2.0 | P0 |
| **lightning-persister** | latest | Persistence | MIT/Apache-2.0 | P0 |
| **lightning-background-processor** | latest | Event loop | MIT/Apache-2.0 | P0 |
| **lightning-block-sync** | latest | Block sync | MIT/Apache-2.0 | P0 |
| **lightning-invoice** | latest | BOLT11 encoding | MIT/Apache-2.0 | P0 |
| **lightning-rapid-gossip-sync** | latest | Network graph rapid sync | MIT/Apache-2.0 | P0 |
| **ldk-node** | v0.7+ | High-level wrapper LDK | MIT/Apache-2.0 | P0 |
| **lightning-liquidity** | latest | LSPS0/LSPS1/LSPS2 client | MIT/Apache-2.0 | P0 |
| **lightning-types** | latest | Tipi condivisi LDK | MIT/Apache-2.0 | P0 |

### LSP partner (Path B, run-time configurable)

| Servizio | Ruolo | Tipo |
|---|---|---|
| **Voltage** | LSP commerciale | candidato default |
| **Olympus by Zeus** | LSP self-host friendly | candidato |
| **Megalith LSP** | LSP indipendente | candidato |
| **c=** (`c-lightning`-based) | LSP community | opzione |
| **LNbits LSP** | LSP self-host | opzione DIY |
| **Self-host** (lnd / cln) | Power user | opzione |

---

## 7. Liquid Network

| Tecnologia | Versione | Ruolo | Licenza | Priorità |
|---|---|---|---|---|
| **lwk** (Liquid Wallet Kit) | latest | Wallet Liquid (L-BTC + USDt + AMP assets) | MIT | P0 |
| **lwk_wollet** | latest | Wallet logic | MIT | P0 |
| **lwk_signer** | latest | Software signer Liquid | MIT | P0 |
| **lwk_jade** | latest | Jade hardware integration Liquid | MIT | P0 |
| **lwk_cli** | latest | (Dev tooling) | MIT | P0 |
| **elements-miniscript** | latest | Miniscript Liquid-flavor | CC0-1.0 | P1 |
| **rust-elements** | latest | Primitives Elements/Liquid | CC0-1.0 / MIT | P0 |

---

## 8. eCash

### Cashu (P1)

| Tecnologia | Versione | Ruolo | Licenza | Priorità |
|---|---|---|---|---|
| **cdk** (Cashu Development Kit) | v0.16+ | Cashu wallet primitive | MIT | P1 |
| **cdk-ffi** | latest | Mobile bindings Swift/Kotlin | MIT | P1 |
| **NUTs spec** v1+ | (protocol) | Protocollo Cashu | open spec | P1 |

### Fedimint (P2)

| Tecnologia | Versione | Ruolo | Licenza | Priorità |
|---|---|---|---|---|
| **fedimint-client** | v0.11+ | Federated mint client | MIT | P2 |
| **fedimint-core** | v0.11+ | Tipi core Fedimint | MIT | P2 |
| **fedimint-mint-client** | v0.11+ | Modulo mint | MIT | P2 |
| **fedimint-wallet-client** | v0.11+ | Modulo wallet | MIT | P2 |
| **fedimint-ln-client** | v0.11+ | Modulo Lightning gateway | MIT | P2 |

---

## 9. Swap & cross-L2

| Tecnologia | Versione | Ruolo | Licenza | Priorità |
|---|---|---|---|---|
| **boltz-rust** o **boltz-client** Rust SDK | v2.11+ | Submarine swap forward/reverse BTC↔LN↔Liquid | MIT | P0 |
| **arkade-os/boltz-swap** | latest | Ark↔LN swap (P2) | MIT | P2 |
| **mercury layer Lightning Latch** | (TBD) | Statechain ↔ LN swap (P3) | MIT | P3 |

---

## 10. Hardware wallet integration

| Tecnologia | Versione | Ruolo | Licenza | Priorità |
|---|---|---|---|---|
| **PSBT v2** (BIP370) | spec | Standard scambio firma | open spec | P0 |
| **BBQr** (Coinkite) | spec | QR multi-frame Coldcard | open spec | P0 |
| **UR (Uniform Resources)** | spec | QR multi-frame Keystone-ecosystem | open spec | P0 |
| **rust-ur** o **bitcoin-bbqr** Rust libs | latest | Encoding/decoding | MIT | P0 |
| **rust-hwi** (Hardware Wallet Interface) | latest | Wrapper unificato Coldcard/Trezor/Ledger/Jade | MIT | P0 |
| **rust-jade** | latest | Blockstream Jade native | MIT | P0 |
| **bitbox-api-rs** | latest | BitBox02 native | Apache-2.0 | P0 |
| **ledger-bitcoin-client-rs** | latest | Ledger via APDU | Apache-2.0 | P0 |
| **trezor-client** Rust | latest | Trezor protocol | Apache-2.0 | P0 |
| **specter-desktop hwi compat layer** | (eventually) | Compat con HWI Python ecosystem | MIT | P1 |
| **Foundation Passport** integration | via PSBT/SD | airgap puro | – | P0 |
| **SeedSigner** integration | via QR | DIY airgap | – | P1 |
| **COLDCARD Mk4/Q** | via USB+SD+NFC+BBQr | flagship hardware | – | P0 |

---

## 11. Backup & recovery

| Tecnologia | Versione | Ruolo | Licenza | Priorità |
|---|---|---|---|---|
| **BIP39** | spec | Mnemonic seed (legacy) | open spec | P0 |
| **BIP93** (Codex32) | spec | Backup error-correcting + Shamir | open spec | P1 |
| **SeedQR / CompactSeedQR** | spec | QR-encoded seed | open spec | P0 |
| **bip39 Rust crate** | latest | BIP39 primitive | MIT/Apache-2.0 | P0 |
| **codex32 Rust crate** | latest | BIP93 primitive | CC0-1.0 / MIT | P1 |
| **Output Descriptors** (BIP380-385) | spec | Wallet structure descriptor | open spec | P0 |
| **BIP388** (Wallet Policies) | spec | Multisig policy descriptor for HW | open spec | P0 |
| **SLIP39** (Trezor Shamir) | spec | Compatibility import Trezor | open spec | P2 |
| **LDK SCB** (Static Channel Backup) | LDK built-in | LN channel state backup | MIT/Apache-2.0 | P0 |

---

## 12. Storage / database

| Tecnologia | Versione | Ruolo | Licenza | Priorità |
|---|---|---|---|---|
| **SQLite** | 3.45+ | Storage primario | Public Domain | P0 |
| **SQLCipher** | 4.6+ | SQLite encrypted at rest | BSD-Style | P0 |
| **rusqlite** o **sqlx** | latest | Rust SQLite client | MIT | P0 |
| **sled** | (alternativa valutata) | Rust embedded KV | Apache-2.0/MIT | – |
| **redb** | (alternativa) | Rust embedded ACID KV | MIT/Apache-2.0 | – |
| **sqlite-vec** | latest | Vector extension (per ML categorization) | Apache-2.0 | P2 |
| **libsodium** | 1.0.20+ | Crypto for at-rest encryption layer | ISC | P0 |
| **age** (Rust impl `age-rs`) | latest | File encryption per export | MIT/Apache-2.0 | P0 |

---

## 13. Network / connectivity

| Tecnologia | Versione | Ruolo | Licenza | Priorità |
|---|---|---|---|---|
| **tokio** | 1.x latest | Async runtime Rust | MIT | P0 |
| **reqwest** o **hyper** | latest | HTTP client | MIT/Apache-2.0 | P0 |
| **rustls** | latest | TLS pure-Rust | Apache-2.0/ISC/MIT | P0 |
| **webpki-roots** | latest | Root CAs | MPL-2.0 | P0 |
| **arti** | latest stable | Tor (toggleable) | Apache-2.0/MIT | P1 |
| **rust-bitcoin/bip324** | latest | V2 encrypted P2P transport | MIT/Apache-2.0 | P0 |
| **electrs / mempool/electrs** | latest | Backend self-host (opzionale) | MIT | P1 |
| **esplora** (Blockstream) | latest | Backend public/self-host | MIT | P0 |

---

## 14. Sync, messaging, NWC

| Tecnologia | Versione | Ruolo | Licenza | Priorità |
|---|---|---|---|---|
| **nostr-sdk** (Rust) | latest | Nostr client core | MIT | P1 |
| **nostr** | latest | Nostr primitives | MIT | P1 |
| **NIP-04 / NIP-44** | spec | Encrypted DM | open spec | P1 |
| **NIP-47 (NWC)** | spec | Nostr Wallet Connect | open spec | P1 |
| **NIP-57 (Zaps)** | spec | Lightning Zaps via Nostr | open spec | P2 |
| **alby/nwc-rs** o equivalente | latest | NWC implementation | MIT | P1 |

---

## 15. Price oracle & exchange data

| Tecnologia | Versione | Ruolo | Licenza | Priorità |
|---|---|---|---|---|
| **mempool.space REST API** | v1 | Prezzi live + storici BTC multi-fiat | open API | P0 |
| **mempool/mempool** (self-host) | latest | Self-host opzionale | MIT | P0 |
| **Kraken public API** | – | Fallback prezzi | open API | P0 |
| **Coinbase public API** | – | Fallback prezzi | open API | P0 |
| **CoinGecko API** | (free tier) | Multi-currency expansion | open API | P1 |

---

## 16. Tracking / finance / categorization

| Tecnologia | Versione | Ruolo | Licenza | Priorità |
|---|---|---|---|---|
| **BIP329** | spec | Wallet labels export format (esteso con campi custom) | open spec | P0 |
| **BIP329 Rust crate** | latest (es. `labelbase` o custom) | Implementazione | MIT | P0 |
| **ISO 18245 MCC mapping** | (ref. proposta) | Merchant category code (proposta nostra) | open spec | P1 |
| **rust_decimal** | latest | Decimal math sat↔fiat senza floating | MIT | P0 |
| **chrono** o **time** | latest | Date/time | MIT/Apache-2.0 | P0 |
| **schemars** + **serde_json** | latest | Schemi JSON per export | MIT | P0 |
| **csv** Rust | latest | CSV export tax | Unlicense/MIT | P1 |
| **calamine** | latest | XLSX export | Apache-2.0/MIT | P2 |
| **JSON-LD schema.org/Invoice** | spec | Standardizzazione export merchant | open spec | P2 |

---

## 17. Open banking (P2, opzionale fiat side)

| Tecnologia | Ruolo | Licenza |
|---|---|---|
| **GoCardless Bank Account Data** (ex-Nordigen) — UK/EU | Open banking aggregator (free tier) | API commerciale |
| **Plaid** — US/EU | Aggregator commerciale (paid) | API commerciale |
| **TrueLayer** — UK/EU | Aggregator + PIS | API commerciale |
| **Tink** — EU | Aggregator EU PSD2 | API commerciale |

---

## 18. Standards & protocols (BIPs / BOLTs / LUDs / NIPs)

### Bitcoin BIPs

| BIP | Titolo | Priorità |
|---|---|---|
| BIP21 | URI scheme | P0 |
| BIP32 | HD wallets | P0 |
| BIP39 | Mnemonic | P0 |
| BIP47 | Reusable payment codes (legacy, considerare deprecation) | P3 |
| BIP78 | Payjoin v1 | P1 (receive merchant-mode) |
| BIP84 | SegWit derivation | P0 |
| BIP86 | Taproot single-sig derivation | P0 |
| BIP93 | Codex32 | P1 |
| BIP119 | OP_CHECKTEMPLATEVERIFY (track) | watch |
| BIP125 | RBF | P0 |
| BIP158 | Compact block filters | P1 |
| BIP174 | PSBT v0 (legacy compat) | P0 |
| BIP321 | URI scheme v2 | P0 |
| BIP322 | Generic message signing | P1 |
| BIP324 | V2 P2P transport | P0 |
| BIP327 | MuSig2 | P1 |
| BIP329 | Wallet labels export | P0 |
| BIP352 | Silent Payments | P0 |
| BIP353 | DNS payment instructions | P1 |
| BIP370 | PSBT v2 | P0 |
| BIP380-385 | Output descriptors | P0 |
| BIP388 | Wallet policies for HW | P0 |
| BIP392 | SP output descriptors | P1 |

### Lightning BOLTs / BLIPs / LSPS

| Spec | Titolo | Priorità |
|---|---|---|
| BOLT 1-10 | Core LN spec | P0 |
| BOLT 11 | Invoice encoding | P0 |
| BOLT 12 | Offers | P0 |
| BLIP-3 | Keysend | P1 |
| BLIP-10 | Podcasting metadata | P1 |
| BLIP-11 | NameDesc | P1 |
| BLIP-32 | DNS resolution | P1 |
| LSPS0 / bLIP-50 | Transport | P0 |
| LSPS1 / bLIP-51 | Channel acquisto on-demand | P0 |
| LSPS2 / bLIP-52 | JIT channel | P0 |
| LSPS4 | Continuous JIT | P2 |
| TRUC v3 / pkg-relay | Mempool policy aware | P1 |

### LNURL LUDs

| LUD | Titolo | Priorità |
|---|---|---|
| LUD-01 | bech32 LNURL | P0 |
| LUD-06 | payRequest base + metadata | P0 |
| LUD-09 | successAction | P1 |
| LUD-10 | aes successAction | P1 |
| LUD-12 | commentAllowed | P0 |
| LUD-16 | Lightning Address | P0 |
| LUD-17 | Scheme prefixes | P0 |
| LUD-18 | payerData | P1 |
| LUD-20 | text/long-desc | P1 |
| LUD-21 | verify URL | P1 |

### Nostr NIPs

| NIP | Titolo | Priorità |
|---|---|---|
| NIP-01 | Basic protocol | P1 |
| NIP-04 | Encrypted DM (legacy) | P1 |
| NIP-44 | Encrypted DM v2 | P1 |
| NIP-47 | Wallet Connect (NWC) | P1 |
| NIP-57 | Zaps | P2 |

### Tax / fiscal standards

| Spec | Note |
|---|---|
| **IRS Form 1099-DA** | Export US tax (broker-equivalent format) |
| **OECD CARF** | Reporting framework — track-only |
| **MiCA Title V** (EU) | Travel rule e wallet categorization (info-only, BHODL non è VASP) |
| **FATF Travel Rule** | Track per future evoluzioni |

---

## 19. Frontier / roadmap protocols

| Tecnologia | Ruolo | Status mag 2026 | Priorità |
|---|---|---|---|
| **Ark / Arkade** (`arkd`, `@arkade-os/wallet`) | L2 rounds + VTXOs | Mainnet beta (alpha lib) | P2 |
| **Taproot Assets v0.7+** (`tapd`) | Stablecoin LN-native (USDT) | Production | P2 |
| **RGB v0.12** (`rgb-core`, `rgb-lib`) | Client-side validation, asset issuance | RC, non stable | P3 |
| **Mercury Layer** (`mercurylayer`) | Statechain blinded | Beta | P3 |
| **BitVM2** | Bridge L2 trustless | Sperimentale | watch only |
| **BLS12-381** / **FROST** (`secp256kfun`, `frost-secp256k1`) | Multisig aggregato | FROST: Frostsnap production | P3 |
| **ChillDKG** (BIP draft) | DKG standardizzato per FROST | Draft | watch |
| **Spark (Breez SDK Spark)** | L2 alternativa | Beta 2026 | P2 (re-eval) |
| **DLC** (Discreet Log Contracts) | Oracle-based contracts | Beta | P3 |

---

## 20. Build & tooling

| Tecnologia | Ruolo | Licenza |
|---|---|---|
| **Cargo** | Rust build/dep | MIT/Apache-2.0 |
| **Cargo workspaces** | Monorepo Rust crates | MIT/Apache-2.0 |
| **Gradle** 8.x + Kotlin DSL | Kotlin build | Apache-2.0 |
| **KMP Gradle plugin** | Multiplatform targets | Apache-2.0 |
| **Compose Multiplatform Gradle plugin** | UI build | Apache-2.0 |
| **Xcode** 15+ + xcodebuild | iOS bundling | proprietario Apple |
| **Android SDK / NDK** | Android targets | Apache-2.0 (mostly) |
| **rustup** | Toolchain mgmt | MIT/Apache-2.0 |
| **cargo-ndk** | Android NDK Rust cross-compile | MIT/Apache-2.0 |
| **cross** | Cross-compile Rust | MIT/Apache-2.0 |
| **cargo-deny** | License + security audit | MIT/Apache-2.0 |
| **cargo-audit** | RustSec advisories | MIT/Apache-2.0 |
| **cargo-nextest** | Test runner veloce | Apache-2.0/MIT |
| **detekt** | Kotlin static analysis | Apache-2.0 |
| **ktlint** | Kotlin formatter | MIT |
| **rustfmt + clippy** | Rust format/lint | MIT/Apache-2.0 |

---

## 21. Test & QA

| Tecnologia | Ruolo | Licenza |
|---|---|---|
| **cargo test** | Rust unit/integration | MIT/Apache-2.0 |
| **proptest** o **quickcheck** | Property-based testing | MIT/Apache-2.0 |
| **bdk testenv** o **electrsd** | Regtest harness | MIT/Apache-2.0 |
| **bitcoind** + **bitcoind-rs** | Regtest wrapper | MIT |
| **lnd-rpc-test** / **clnrpc** | LN regtest | MIT |
| **arkd regtest** | Ark regtest | MIT |
| **Kotest** | Kotlin testing framework | Apache-2.0 |
| **Turbine** | Flow test for Compose | Apache-2.0 |
| **Paparazzi** (Square) | Compose snapshot test JVM | Apache-2.0 |
| **Roborazzi** | Compose Android snapshot | Apache-2.0 |
| **Maestro** | E2E mobile UI test | Apache-2.0 |
| **Jacoco** | Coverage Kotlin | EPL-2.0 |
| **tarpaulin** o **llvm-cov** | Coverage Rust | MIT/Apache-2.0 |

---

## 22. CI/CD, security, supply chain

| Tecnologia | Ruolo | Licenza |
|---|---|---|
| **GitHub Actions** (or alternatives like Forgejo Actions, Woodpecker) | CI | proprietario / open |
| **reproducible-builds.org guix-style** | Reproducible builds Bitcoin-style | open |
| **sigstore / cosign** | Artifact signing | Apache-2.0 |
| **GPG** + WoT | Release signing | GPL |
| **Dependabot** + **renovate** | Dep update automation | MIT |
| **OSV-Scanner** | Vuln scanning | Apache-2.0 |
| **ScoutSuite** o equivalente | n/a (no cloud infra) | – |
| **HackerOne** o **Bugcrowd** (post-launch) | Bug bounty | proprietario |

---

## 23. Observability & errors

> Filosofia: **zero telemetry remoto by default**. Crash report opzionale opt-in encrypted via Tor.

| Tecnologia | Ruolo | Licenza |
|---|---|---|
| **tracing** (Rust) | Structured logging local-only | MIT |
| **tracing-subscriber** | Log filtering local | MIT |
| **slf4j-api** + **logback** (KMP common) | Kotlin logging | EPL/Apache-2.0 |
| **OSLog** (iOS native) | Apple system log | proprietario |
| **android.util.Log** | Android system log | Apache-2.0 |
| **Sentry self-hosted** (opt-in only) | Crash report (utente esplicito) | BSL → Apache-2.0 |

---

## 24. Documentation & dev experience

| Tecnologia | Ruolo | Licenza |
|---|---|---|
| **rustdoc** | Rust API docs | MIT/Apache-2.0 |
| **mdBook** | Public docs + Bitcoin Core style | MPL-2.0 |
| **Dokka** | Kotlin API docs | Apache-2.0 |
| **Storybook** o **Showkase** (Compose) | Design system component browser | MIT |
| **Figma** | Design files mirror | proprietario |
| **GitHub Pages** o **Netlify** | Public docs hosting | – |

---

## 25. Servizi / endpoint pubblici di default (configurabili)

| Servizio | Ruolo |
|---|---|
| **mempool.space** (clearnet + onion) | Esplora + price oracle (default) |
| **blockstream.info** | Esplora fallback |
| **electrum.blockstream.info** | Electrum fallback |
| **breez.technology** | Breez SDK Liquid endpoint (Path A LN) |
| **boltz.exchange** + clones | Submarine swap |
| **damus.io / nos.lol / relay.snort.social** | Nostr relay default per NWC e sync labels |
| **\_bitcoin-payment.\<domain\>** | BIP353 DNS resolution |
| **DNSSEC validating resolver** | Cloudflare 1.1.1.1 / Quad9 9.9.9.9 (configurabile) |
| **LSP partner endpoints** | Voltage / Olympus / Megalith / c= per Path B LN |

---

## 26. Architettura monorepo (vista d'insieme)

```
bhodl/                                          # Cargo + Gradle root
├── Cargo.toml                                  # workspace
├── settings.gradle.kts                         # KMP root
├── crates/                                     # Rust core
│   ├── bhodl-core/                             # tipi condivisi, error
│   ├── bhodl-wallet-onchain/                   # BDK wrapper
│   ├── bhodl-wallet-lightning-easy/            # Breez SDK Liquid Path A
│   ├── bhodl-wallet-lightning-pure/            # LDK Node Path B
│   ├── bhodl-wallet-liquid/                    # LWK wrapper
│   ├── bhodl-wallet-cashu/                     # CDK wrapper
│   ├── bhodl-wallet-fedimint/                  # fedimint-client wrapper
│   ├── bhodl-wallet-ark/                       # P2 Arkade
│   ├── bhodl-privacy/                          # SP + Payjoin + BIP324
│   ├── bhodl-tracker/                          # cost basis, categorize, budget
│   ├── bhodl-labels/                           # BIP329 esteso, sync, encryption
│   ├── bhodl-price-oracle/                     # Mempool/Kraken/Coinbase chain
│   ├── bhodl-hardware/                         # PSBT v2, BBQr, UR, drivers
│   ├── bhodl-backup/                           # SCB, Codex32, descriptor, SeedQR
│   ├── bhodl-nwc/                              # NIP-47 server + client
│   ├── bhodl-swap/                             # Boltz integration
│   ├── bhodl-tax/                              # 1099-DA, lot tracking, JSON-LD
│   ├── bhodl-sync/                             # Nostr E2E label sync
│   ├── bhodl-store/                            # SQLCipher abstraction
│   └── bhodl-uniffi/                           # UniFFI exports + UDL
├── shared/                                     # KMP common module
│   ├── commonMain/kotlin/                      # business logic Kotlin
│   ├── androidMain/kotlin/                     # Android-specific (Keystore, Biometric)
│   ├── iosMain/kotlin/                         # iOS-specific (Keychain, SE)
│   ├── desktopMain/kotlin/                     # JVM-specific (OS keyring)
│   └── build.gradle.kts                        # KMP targets + UniFFI bindings
├── apps/
│   ├── android/                                # Android app entry
│   ├── ios/                                    # iOS app entry (Xcode project)
│   └── desktop/                                # Compose-desktop entry
├── design/                                     # Figma exports + token JSON
├── docs/                                       # mdBook + rustdoc + Dokka
├── PLAN.md
├── TECH_STACK.md                               # questo file
└── .github/workflows/                          # CI
```

---

## 27. Riepilogo decisioni stack — versione brevissima

- **Linguaggi**: Rust + Kotlin (con Swift/C interop dove necessario).
- **Frontend**: Kotlin Multiplatform + Compose Multiplatform + UniFFI.
- **Wallet on-chain**: BDK 1.x.
- **Lightning**: Breez SDK Liquid (Path A) + LDK Node (Path B), entrambi in MVP.
- **Liquid**: LWK.
- **eCash**: Cashu via CDK (P1), Fedimint (P2).
- **Privacy**: rust-payjoin + bdk-sp + bip324 + arti.
- **Swap**: boltz-rust.
- **Hardware**: HWI + PSBT v2 + BBQr/UR.
- **Storage**: SQLCipher.
- **Sync/messaging**: Nostr (NIP-47 NWC).
- **Price**: mempool.space self-host + fallback Kraken/Coinbase.
- **Build**: Cargo workspaces + Gradle KMP.
- **CI**: GitHub Actions + sigstore + reproducible builds.
- **License**: MIT (preferita) per tutto il core; eventuali moduli con dep AGPL isolati.

---

## 28. Domande residue / aree da validare

1. **Scelta DB**: SQLCipher è AGPL-incompatibile? Verificare se dual-license commerciale è ok per progetto open-source o se servirebbe alternativa (es. SQLite + custom encryption layer libsodium).
2. **Compose Multiplatform iOS**: è davvero pronto per produzione consumer-facing in 2026? Bitkey è il precedente — quali altri casi reali esistono? Gap concreti rispetto a SwiftUI puro?
3. **uniffi-kotlin-multiplatform-bindings**: maturità del fork UbiqueInnovation. Esistono alternative? Trixnity è una?
4. **Path A vs Path B Lightning**: la migrazione A↔B via swap è realistica? Costi UX + fee per l'utente?
5. **Silent Payments scanning**: quale infra serve per scanning su mobile? Servizio dedicato (Frigate-style) o full node?
6. **BIP324 transport**: quanti nodi pubblici lo supportano nel 2026? Eventuali problemi di compatibilità durante early adoption.
7. **Hardware coverage MVP**: quali 3 vendor su 7 prioritizzare? Coldcard + BitBox02 + Jade è la baseline più solida? Ledger nasce con dipendenza Apple-Store-friendly?
8. **Licenze librerie**: cargo-deny check su tutte le dep transitive — c'è qualche GPL/AGPL pericolosa?
9. **Reproducible builds**: realisticamente fattibile su KMP+Gradle+Compose oltre a Cargo? Esempi noti?
10. **Bundle size totale**: stima realistica per Android APK + iOS IPA con tutto il core Rust + LDK + LWK + CDK incluso?

---

*BHODL — TECH_STACK.md v1.0 — maggio 2026.*
