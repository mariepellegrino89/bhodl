use std::{collections::HashMap, path::Path, str::FromStr};

use bdk_esplora::EsploraExt;
use bdk_wallet::{KeychainKind, PersistedWallet, SignOptions, Wallet};
use bhodl_core::{Amount, Network as BhodlNetwork};
use bip39::Mnemonic;
use bitcoin::{
    Address, Network as BtcNetwork, OutPoint, Txid,
    bip32::{DerivationPath, Xpriv},
    key::Secp256k1,
    psbt::Psbt,
    secp256k1::{self, All},
};
use rusqlite::Connection;
use zeroize::Zeroizing;

use crate::contacts::{self, CREATE_CONTACTS_TABLE_SQL, Contact};
use crate::error::OnchainError;
use crate::labels::{self, CREATE_LABELS_TABLE_SQL, Label, LabelKind};
use crate::send_policy::{self, CpfpPlan, PsbtOutputInfo, PsbtSummary};

/// Length of the raw AES-256 key `SQLCipher` consumes via `PRAGMA key`.
pub const ENCRYPTION_KEY_LEN: usize = 32;

/// Schema for the in-DB seed table.
///
/// BDK 3.0 deliberately persists only the public projection of each
/// descriptor in its `ChangeSet`, so a `Wallet::load` that does not
/// receive the secret descriptors back from the caller produces a
/// watch-only wallet — it can sync and build PSBTs but cannot finalise
/// them. To support the desktop unlock flow (where the user types only
/// a PIN and the mnemonic lives on paper), we store the BIP39 mnemonic
/// and passphrase in this single-row table inside the same `SQLCipher`
/// database. The encryption key is the Argon2id-derived AES-256 key,
/// so the threat model is identical to "an attacker has the DB file":
/// they still need to brute-force the PIN through Argon2id (m = 64 MiB,
/// t = 3, p = 4 — roughly 0.5-0.8 s per attempt on a 2024 desktop) before
/// they can read the seed.
///
/// `id INTEGER PRIMARY KEY CHECK (id = 1)` enforces "one row only" so
/// every concurrent path either reads the same seed or fails fast.
const CREATE_SEED_TABLE_SQL: &str = "\
CREATE TABLE IF NOT EXISTS bhodl_seed (\
    id INTEGER PRIMARY KEY CHECK (id = 1),\
    mnemonic TEXT NOT NULL,\
    passphrase TEXT NOT NULL\
)";

fn ensure_seed_table(conn: &Connection) -> Result<(), OnchainError> {
    conn.execute(CREATE_SEED_TABLE_SQL, [])
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    Ok(())
}

/// Insert the seed if no row exists yet. Idempotent: a second call with
/// a different mnemonic is a silent no-op (the original seed wins). The
/// caller is responsible for ensuring the mnemonic passed at create
/// time is the one the user actually wrote down.
fn save_seed_if_absent(
    conn: &Connection,
    mnemonic: &str,
    passphrase: &str,
) -> Result<(), OnchainError> {
    conn.execute(
        "INSERT OR IGNORE INTO bhodl_seed (id, mnemonic, passphrase) VALUES (1, ?1, ?2)",
        [mnemonic, passphrase],
    )
    .map_err(|e| OnchainError::Storage(e.to_string()))?;
    Ok(())
}

/// Seed pair read back from `bhodl_seed`. Both halves are wrapped in
/// [`Zeroizing`] so every Rust-side copy is wiped on drop.
type SeedPair = (Zeroizing<String>, Zeroizing<String>);

fn read_seed(conn: &Connection) -> Result<Option<SeedPair>, OnchainError> {
    let result = conn.query_row(
        "SELECT mnemonic, passphrase FROM bhodl_seed WHERE id = 1",
        [],
        |row| {
            Ok((
                Zeroizing::new(row.get::<_, String>(0)?),
                Zeroizing::new(row.get::<_, String>(1)?),
            ))
        },
    );
    match result {
        Ok(pair) => Ok(Some(pair)),
        Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
        Err(e) => Err(OnchainError::Storage(e.to_string())),
    }
}

/// Open a `SQLCipher` database file and apply the encryption key.
///
/// `PRAGMA key = "x'<hex>'"` is the documented `SQLCipher` way to pass a
/// raw 256-bit key. The hex-blob form skips `SQLCipher`'s PBKDF2 derivation
/// (which is what we want — the caller is expected to have already
/// derived the key from a hardware-backed secret like Keystore/Keychain).
///
/// The PRAGMA must be the very first statement on the connection: any
/// other access before it triggers `SQLCipher`'s "file is not a database"
/// error path.
///
/// Also applied on every connection:
/// - `cipher_memory_security = ON` — `SQLCipher` wipes every buffer it
///   frees, so decrypted pages and key material do not linger in heap.
/// - `busy_timeout = 5000` — several connections (registry, rate repo,
///   watch state) may target the same file; wait up to 5 s for a lock
///   instead of failing immediately with `SQLITE_BUSY`. The journal mode
///   is left at the default rollback journal (no WAL).
///
/// The hex-encoded key literal built here is [`Zeroizing`]; rusqlite's
/// internal SQL buffer for the PRAGMA is outside our control.
pub(crate) fn open_encrypted_connection(
    db_path: &Path,
    key: &[u8; ENCRYPTION_KEY_LEN],
) -> Result<Connection, OnchainError> {
    let conn = Connection::open(db_path).map_err(|e| OnchainError::Storage(e.to_string()))?;
    let key_literal = key_pragma_literal(key);
    conn.pragma_update(None, "key", key_literal.as_str())
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    drop(key_literal);
    // Process-global in SQLCipher 4.x; re-applying it per connection is
    // idempotent and cheap.
    conn.pragma_update(None, "cipher_memory_security", "ON")
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    // Sanity probe: any read on the schema fails cheaply if the key was
    // wrong, before we hand the connection to BDK and waste work there.
    conn.query_row("SELECT count(*) FROM sqlite_master", [], |_| Ok(()))
        .map_err(|e| OnchainError::Storage(format!("encryption key rejected: {e}")))?;
    conn.busy_timeout(std::time::Duration::from_millis(5_000))
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    Ok(conn)
}

/// Build the `x'<hex>'` raw-key literal `SQLCipher` expects inside a
/// [`Zeroizing`] buffer, so the hex copy of the key is wiped on drop.
fn key_pragma_literal(key: &[u8; ENCRYPTION_KEY_LEN]) -> Zeroizing<String> {
    use std::fmt::Write as _;
    // 2 hex chars per byte + the `x''` wrapper; pre-sized so the buffer
    // never reallocates (a realloc would strand an un-wiped copy).
    let mut out = Zeroizing::new(String::with_capacity(ENCRYPTION_KEY_LEN * 2 + 3));
    out.push_str("x'");
    for b in key {
        let _ = write!(out, "{b:02x}");
    }
    out.push('\'');
    out
}

/// Output of [`derive_descriptor_parts`] — the deterministic pieces every
/// `OnchainAccount` constructor needs.
///
/// The descriptor strings (`desc_ext`, `desc_int`) carry the full xpriv
/// in `Base58Check` form (`tr([fp/86'/0'/0']xprv.../0/*)`). They are
/// consumed by `Wallet::create` / `Wallet::load`, which clones them
/// into BDK-managed buffers; once BDK owns the data we can no longer
/// reach it from this crate. We scrub both fields in our Drop impl.
///
/// `consume_descriptors` uses `std::mem::take` to move the strings out
/// (leaving an empty String in place) so the call site receives owned
/// values without cloning. When the struct drops, `desc_ext` and
/// `desc_int` are already empty — zeroize is a cheap no-op.
///
/// The `xprv_str` local in `derive_descriptor_parts` (the `Base58Check`
/// intermediate copy) is scrubbed inline before it falls out of scope.
///
/// **Documented limitation**: BDK stores the descriptor strings in its
/// own `ChangeSet` / descriptor store and does not expose a `Zeroize`
/// path. The material is scrubbed from the `DescriptorParts` side; the
/// BDK-side copy lives until BDK's own Drop.
struct DescriptorParts {
    xpriv: Xpriv,
    secp: Secp256k1<All>,
    btc_network: BtcNetwork,
    desc_ext: String,
    desc_int: String,
}

impl Drop for DescriptorParts {
    fn drop(&mut self) {
        use zeroize::Zeroize;
        // Scrub the Base58Check xpriv embedded in each descriptor string.
        self.desc_ext.zeroize();
        self.desc_int.zeroize();
        // Scrub the private-key scalar and the chain code of the master Xpriv.
        // `non_secure_erase` is compiler-fence-aware (secp256k1 crate).
        self.xpriv.private_key.non_secure_erase();
        // Overwrite the chain code with zeros. Combined with the private key
        // wipe this prevents reconstruction of any non-hardened child from a
        // partial memory dump. `ChainCode::from([0u8;32])` is a safe overwrite
        // on bitcoin 0.32 (no Drop-based invariant on the newtype).
        self.xpriv.chain_code = bitcoin::bip32::ChainCode::from([0u8; 32]);
    }
}

impl DescriptorParts {
    /// Move the descriptor strings out of `self`, leaving empty strings
    /// behind. The caller receives owned `String`s; this struct's `Drop`
    /// then zeroizes the (now-empty) fields — a cheap no-op — and still
    /// wipes `xpriv` regardless of whether `consume_descriptors` was
    /// called.
    fn consume_descriptors(&mut self) -> (String, String) {
        (
            std::mem::take(&mut self.desc_ext),
            std::mem::take(&mut self.desc_int),
        )
    }

    /// Take the `Secp256k1` context out of `self`, leaving a freshly
    /// constructed context in its place. `Secp256k1::new()` is cheap
    /// (no heap on stack-only build; one heap alloc on full build).
    /// This is needed because `DescriptorParts` implements `Drop` and
    /// therefore its fields cannot be moved out by field access.
    fn take_secp(&mut self) -> Secp256k1<All> {
        std::mem::replace(&mut self.secp, Secp256k1::new())
    }

    /// Take the `Xpriv` out of `self`, leaving a zeroed placeholder.
    /// Callers that want to move the `Xpriv` into `OnchainAccount` must
    /// use this instead of a direct field move.
    fn take_xpriv(&mut self) -> Xpriv {
        // Build a zeroed placeholder Xpriv to leave in `self.xpriv`.
        // `parts.drop` will wipe it (it's already zeros, but the
        // `non_secure_erase` + chain_code overwrite are idempotent).
        let placeholder = Xpriv {
            network: self.xpriv.network,
            depth: 0,
            parent_fingerprint: bitcoin::bip32::Fingerprint::from([0u8; 4]),
            child_number: bitcoin::bip32::ChildNumber::Normal { index: 0 },
            private_key: secp256k1::SecretKey::from_slice(&[1u8; 32])
                .expect("static non-zero seed"),
            chain_code: bitcoin::bip32::ChainCode::from([0u8; 32]),
        };
        std::mem::replace(&mut self.xpriv, placeholder)
    }
}

/// Derive every BIP86 ingredient deterministically from a BIP39 mnemonic.
///
/// Splitting this out lets `from_mnemonic` (in-memory) and
/// `create_or_load_persistent` (disk-backed) share one definition of
/// "what is this wallet" — the same mnemonic always produces the same
/// pair of descriptors, which is what `Wallet::load` needs to match.
fn derive_descriptor_parts(
    network: BhodlNetwork,
    mnemonic_phrase: &str,
    passphrase: &str,
) -> Result<DescriptorParts, OnchainError> {
    // `Mnemonic` is `ZeroizeOnDrop` (bip39 `zeroize` feature); the 64-byte
    // BIP39 seed is wrapped so it is wiped as soon as the master key exists.
    let mnemonic = Mnemonic::parse_normalized(mnemonic_phrase)?;
    let seed = Zeroizing::new(mnemonic.to_seed(passphrase));
    drop(mnemonic);
    let btc_network = map_network(network);
    let xpriv = Xpriv::new_master(btc_network, seed.as_slice())?;
    drop(seed);
    let secp = Secp256k1::new();

    let coin: u32 = u32::from(!matches!(network, BhodlNetwork::Mainnet));
    let fingerprint = xpriv.fingerprint(&secp);

    let account_path = DerivationPath::from_str(&format!("m/86'/{coin}'/0'"))
        .map_err(|e| OnchainError::DerivationPath(e.to_string()))?;
    let mut account_xpriv = xpriv
        .derive_priv(&secp, &account_path)
        .map_err(OnchainError::Bip32)?;

    let origin = format!("[{fingerprint}/86'/{coin}'/0']");
    let mut xprv_str = account_xpriv.to_string();
    let desc_ext = format!("tr({origin}{xprv_str}/0/*)");
    let desc_int = format!("tr({origin}{xprv_str}/1/*)");
    // Scrub the local Base58Check xpriv copy before it falls out of
    // scope. The two descriptor strings still hold the same bytes;
    // those are zeroized by `DescriptorParts::drop`.
    {
        use zeroize::Zeroize;
        xprv_str.zeroize();
    }
    // Same for the account-level Xpriv: only the descriptor strings and
    // the master `xpriv` (wiped by `DescriptorParts::drop`) survive.
    account_xpriv.private_key.non_secure_erase();
    account_xpriv.chain_code = bitcoin::bip32::ChainCode::from([0u8; 32]);

    Ok(DescriptorParts {
        xpriv,
        secp,
        btc_network,
        desc_ext,
        desc_int,
    })
}

/// Public-only descriptor backup for an [`OnchainAccount`].
///
/// Contains the external (receive) and internal (change) descriptors in
/// their xpub-only form — the master `xpriv` is **never** included.
/// Importing this backup into another descriptor wallet (Sparrow, Liana,
/// Specter Desktop, BDK CLI) yields a watch-only view of the same wallet:
/// addresses, balance, transaction history are all recoverable, but
/// signing remains impossible without the original mnemonic.
///
/// This is the "verify-my-receive-addresses" recovery path. The
/// signing-capable recovery path is the BIP39 mnemonic itself.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DescriptorBackup {
    /// Network this backup is bound to.
    pub network: BhodlNetwork,
    /// External (receive) descriptor in xpub form.
    /// Shape: `tr([fingerprint/86h/coin'/0h]xpub/0/i)#checksum`
    /// where `i` is the address index.
    pub external: String,
    /// Internal (change) descriptor in xpub form.
    /// Shape: `tr([fingerprint/86h/coin'/0h]xpub/1/i)#checksum`.
    pub internal: String,
}

impl DescriptorBackup {
    /// Extract the account-level xpub from the external descriptor.
    ///
    /// Strips the `tr(...)` wrapper, the optional `[origin]` prefix, and
    /// the trailing `/0/<idx>)#checksum`. Returns `None` if the descriptor
    /// shape does not match the BIP-86 single-key-path template this
    /// wallet always produces.
    #[must_use]
    pub fn account_xpub(&self) -> Option<String> {
        // Strip optional `#checksum`.
        let no_checksum = self.external.split('#').next()?;
        // Strip wrapper `tr(...)` / `wpkh(...)` / `sh(wpkh(...))`. We
        // look for the innermost paren-paren pair.
        let inside = no_checksum
            .strip_prefix("tr(")
            .or_else(|| no_checksum.strip_prefix("wpkh("))
            .or_else(|| {
                no_checksum
                    .strip_prefix("sh(wpkh(")
                    .map(|s| s.strip_suffix(")").unwrap_or(s))
            })?;
        let inside = inside.strip_suffix(')')?;
        // Strip optional `[origin]` prefix.
        let after_origin = inside.rsplit(']').next()?;
        // Strip the trailing `/0/*` or `/0/<idx>`.
        let xpub = after_origin
            .rsplit_once('/')
            .and_then(|(rest, _)| rest.rsplit_once('/').map(|(x, _)| x))
            .unwrap_or(after_origin);
        Some(xpub.to_string())
    }
}

/// Per-request socket timeout handed to the Esplora client (seconds).
/// Tor circuits are slow but a request stalled for longer than this is
/// treated as failed rather than hanging a sync forever.
const ESPLORA_TIMEOUT_SECS: u64 = 30;

/// Per-request timeout when an Esplora proxy (Tor) is configured. Live
/// tests showed mempool.space over Tor stalling 20-35 s *before* the
/// connection is even established, which made full scans time out under
/// the clearnet budget. Must stay above the bridge's SOCKS connect
/// timeout ([`crate::tor::SOCKS_CONNECT_TIMEOUT`]) so the bridge's own
/// 502 (not a client timeout) reports a dead proxy.
const ESPLORA_PROXY_TIMEOUT_SECS: u64 = 90;

/// Esplora request timeout (seconds): the larger Tor budget when a proxy
/// is configured, the clearnet one otherwise.
const fn esplora_timeout_secs(proxied: bool) -> u64 {
    if proxied {
        ESPLORA_PROXY_TIMEOUT_SECS
    } else {
        ESPLORA_TIMEOUT_SECS
    }
}

/// Retries on HTTP 429/500/503 (exponential backoff from 256 ms). The
/// library default of 6 can stall a single call for ~16 s; 3 keeps the
/// worst case under 2 s of back-off while still riding out a blip.
const ESPLORA_MAX_RETRIES: usize = 3;

/// Full-scan request snapshotted from an [`OnchainAccount`] by
/// [`OnchainAccount::start_esplora_scan`]. Owns everything the network
/// phase needs, so the account (and any mutex around it) is free while
/// [`Self::fetch`] runs.
pub struct PendingEsploraScan {
    request: bdk_chain::spk_client::FullScanRequest<KeychainKind>,
    /// Tor stream-isolation scope (in-memory only; see [`crate::tor`]).
    isolation_scope: String,
}

impl std::fmt::Debug for PendingEsploraScan {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("PendingEsploraScan").finish_non_exhaustive()
    }
}

impl PendingEsploraScan {
    /// Run the full scan against Esplora (stop-gap 20, 4 parallel requests).
    ///
    /// # Errors
    ///
    /// [`OnchainError::EsploraSync`] on transport / server failure,
    /// [`OnchainError::InvalidArgument`] on a malformed URL or proxy.
    pub fn fetch(
        self,
        esplora_url: &str,
        proxy_url: Option<&str>,
    ) -> Result<EsploraScanUpdate, OnchainError> {
        let client =
            try_build_esplora_client_scoped(esplora_url, proxy_url, &self.isolation_scope)?;
        let response = client
            .full_scan(self.request, 20, 4)
            .map_err(|e| OnchainError::EsploraSync(e.to_string()))?;
        Ok(EsploraScanUpdate {
            update: response.into(),
        })
    }
}

/// Result of [`PendingEsploraScan::fetch`], applied with
/// [`OnchainAccount::apply_esplora_scan`].
pub struct EsploraScanUpdate {
    update: bdk_wallet::Update,
}

impl std::fmt::Debug for EsploraScanUpdate {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("EsploraScanUpdate").finish_non_exhaustive()
    }
}

/// A blocking Esplora client plus, when Tor is on, the loopback
/// HTTP-CONNECT → SOCKS5 bridge its requests are tunnelled through.
///
/// Derefs to [`bdk_esplora::esplora_client::BlockingClient`], so
/// `client.full_scan(..)`, `client.broadcast(..)` etc. work unchanged.
/// Field order matters: the client is dropped before the bridge.
pub(crate) struct EsploraClient {
    client: bdk_esplora::esplora_client::BlockingClient,
    bridge: Option<crate::tor::ConnectBridge>,
}

impl std::fmt::Debug for EsploraClient {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("EsploraClient")
            .field("tor_bridge", &self.bridge.is_some())
            .finish_non_exhaustive()
    }
}

impl std::ops::Deref for EsploraClient {
    type Target = bdk_esplora::esplora_client::BlockingClient;
    fn deref(&self) -> &Self::Target {
        &self.client
    }
}

/// Build a blocking Esplora client for `esplora_url`, optionally routed
/// through the SOCKS5 proxy `proxy_url` (`socks5h://host:port`, Tor).
///
/// Audit 2026-10 (H8): the blocking transport (minreq) only supports
/// HTTP CONNECT proxies and rejects `socks5://` / `socks5h://` outright,
/// so the old "pass the SOCKS URL to `Builder::proxy`" approach made
/// every request fail with Tor on. With a proxy we now start a
/// [`crate::tor::ConnectBridge`] on 127.0.0.1 that accepts minreq's
/// `CONNECT host:port`, opens the tunnel through the SOCKS proxy with
/// the host name unresolved (remote DNS), and is restricted to the
/// Esplora `host:port`. TLS stays end-to-end. If the proxy is down the
/// bridge answers 502 — there is never a clearnet fallback.
///
/// # Errors
///
/// - [`OnchainError::InvalidArgument`] — malformed Esplora or proxy URL.
/// - [`OnchainError::EsploraSync`] — the loopback bridge could not start.
pub(crate) fn try_build_esplora_client(
    esplora_url: &str,
    proxy_url: Option<&str>,
) -> Result<EsploraClient, OnchainError> {
    try_build_esplora_client_scoped(esplora_url, proxy_url, ESPLORA_MISC_SCOPE)
}

/// Isolation scope of Esplora traffic that is not tied to one wallet
/// (fee estimates, connectivity checks).
pub(crate) const ESPLORA_MISC_SCOPE: &str = "bhodl:esplora-misc";

/// Isolation scope for one wallet's Esplora traffic: its public external
/// descriptor. Used only as an in-memory map key (hashed) — the SOCKS
/// credentials are an unrelated random token, see [`crate::tor`].
pub(crate) fn wallet_isolation_scope(descriptor: &str) -> String {
    format!("bhodl:wallet:{descriptor}")
}

/// [`try_build_esplora_client`] with an explicit Tor stream-isolation
/// `scope`: with a proxy, every tunnel authenticates to SOCKS5 with the
/// session token of that scope, so Tor puts different scopes (wallets)
/// on different circuits and the Esplora server cannot link them.
pub(crate) fn try_build_esplora_client_scoped(
    esplora_url: &str,
    proxy_url: Option<&str>,
    isolation_scope: &str,
) -> Result<EsploraClient, OnchainError> {
    let proxy_url = proxy_url.map(str::trim).filter(|p| !p.is_empty());
    let mut builder = bdk_esplora::esplora_client::Builder::new(esplora_url)
        .timeout(esplora_timeout_secs(proxy_url.is_some()))
        .max_retries(ESPLORA_MAX_RETRIES);
    let bridge = match proxy_url {
        Some(proxy) => {
            let socks = crate::tor::parse_socks_proxy(proxy)?;
            let parsed = url::Url::parse(esplora_url)
                .map_err(|e| OnchainError::InvalidArgument(format!("invalid Esplora URL: {e}")))?;
            let host = parsed
                .host_str()
                .ok_or_else(|| OnchainError::InvalidArgument("Esplora URL has no host".into()))?;
            let port = parsed
                .port_or_known_default()
                .ok_or_else(|| OnchainError::InvalidArgument("Esplora URL has no port".into()))?;
            let token = crate::tor::isolation_token(isolation_scope)?;
            let bridge = crate::tor::ConnectBridge::start(socks, host, port, Some(token))?;
            builder = builder.proxy(&bridge.proxy_url());
            Some(bridge)
        }
        None => None,
    };
    Ok(EsploraClient {
        client: builder.build_blocking(),
        bridge,
    })
}

/// Infallible variant of [`try_build_esplora_client`] for call sites that
/// surface errors only at request time. On a setup failure it returns a
/// client whose proxy URL is deliberately unparsable, so every request
/// fails with `BadProxy` **before** any socket is opened — fail-closed,
/// never a silent clearnet fallback.
pub(crate) fn build_esplora_client(
    esplora_url: &str,
    proxy_url: Option<&str>,
    isolation_scope: &str,
) -> EsploraClient {
    try_build_esplora_client_scoped(esplora_url, proxy_url, isolation_scope).unwrap_or_else(|_| {
        EsploraClient {
            client: bdk_esplora::esplora_client::Builder::new(esplora_url)
                .timeout(esplora_timeout_secs(true))
                .max_retries(0)
                .proxy("bhodl-tor-setup-failed://")
                .build_blocking(),
            bridge: None,
        }
    })
}

/// `GET /fee-estimates`, accepting any 2xx status.
///
/// `esplora-client` 0.12's `get_fee_estimates` treats every status other
/// than exactly `200` as an error, but mempool.space (BHODL's default
/// Esplora for mainnet and signet) has deprecated that endpoint and now
/// answers **`203 Non-Authoritative Information`** with a perfectly
/// valid body (plus an `x-warning-from-mempool` header). The strict
/// check made every fee lookup against the default server fail — with
/// or without Tor. Found by the live Tor tests (`tor_live_tests`).
///
/// Uses the same client (and so the same Tor bridge / proxy) as every
/// other Esplora call; 429/500/503 are retried like the library does.
fn get_fee_estimates_lenient(client: &EsploraClient) -> Result<HashMap<u16, f64>, OnchainError> {
    let mut delay = std::time::Duration::from_millis(256);
    let mut attempts = 0;
    loop {
        let resp = client
            .get_request("/fee-estimates")
            .and_then(|r| r.send().map_err(Into::into))
            .map_err(|e| OnchainError::EsploraSync(e.to_string()))?;
        let status = resp.status_code;
        if matches!(status, 429 | 500 | 503) && attempts < ESPLORA_MAX_RETRIES {
            attempts += 1;
            std::thread::sleep(delay);
            delay *= 2;
            continue;
        }
        if !(200..300).contains(&status) {
            return Err(OnchainError::EsploraSync(format!(
                "fee-estimates: HTTP {status}"
            )));
        }
        let body = resp
            .as_str()
            .map_err(|e| OnchainError::EsploraSync(format!("fee-estimates: {e}")))?;
        return serde_json::from_str(body)
            .map_err(|e| OnchainError::EsploraSync(format!("fee-estimates: bad JSON: {e}")));
    }
}

/// Three-bucket fee-rate snapshot computed from Esplora's
/// `/fee-estimates` endpoint. All values are in satoshis per virtual
/// byte, rounded up to the nearest integer (BDK builders take `u64`).
///
/// The buckets map to BIP-style confirmation targets:
/// - `priority_sat_per_vb`: 1 block (next block, ~10 min on mainnet)
/// - `normal_sat_per_vb`: 3 blocks (~30 min on mainnet)
/// - `economy_sat_per_vb`: 6 blocks (~1h on mainnet)
///
/// Buckets are at least 1 sat/vB, at most
/// [`crate::MAX_FEE_RATE_SAT_PER_VB`], and monotonic
/// (`economy <= normal <= priority`). A feed that cannot satisfy that
/// is reported as [`OnchainError::FeeEstimateUnavailable`] instead.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct FeeEstimateBuckets {
    pub economy_sat_per_vb: u64,
    pub normal_sat_per_vb: u64,
    pub priority_sat_per_vb: u64,
}

/// A canonical wallet transaction, flattened for the FFI.
///
/// Returned by [`OnchainAccount::list_transactions`]. Captures only the
/// fields the desktop history view needs today; richer detail (full
/// input/output set, raw bytes, RBF replaceability) lives behind
/// follow-up methods that can be added without changing this struct.
///
/// `net_sat` is `received - sent` from the wallet's perspective:
/// positive when the wallet's balance grew, negative when it shrank.
/// `fee_sat` is `Some` only when the wallet has every input — i.e. for
/// transactions it sent itself. For incoming transactions BDK cannot
/// compute the fee from local data alone and the field is `None`.
#[derive(Debug, Clone)]
pub struct TxRecord {
    pub txid: String,
    pub net_sat: i64,
    pub fee_sat: Option<u64>,
    /// `Some` once the transaction is anchored in the best chain.
    pub confirmation_height: Option<u32>,
    /// For confirmed transactions: the block's confirmation time.
    /// For unconfirmed transactions: when the wallet last saw it in
    /// the mempool, if known. Unix epoch seconds.
    pub timestamp: Option<u64>,
}

/// A wallet-owned unspent transaction output.
///
/// Returned by [`OnchainAccount::list_utxos`] for coin-control UIs and
/// for callers that want to drive PSBT construction with an explicit set
/// of inputs (airgap signers, privacy heuristics, BIP329 labelling).
#[derive(Debug, Clone)]
pub struct UtxoInfo {
    /// `txid:vout` of the UTXO.
    pub outpoint: OutPoint,
    /// Value in satoshis.
    pub value: Amount,
    /// Address that holds the UTXO. Reconstructed from the `script_pubkey`
    /// using the account's network.
    pub address: Address,
    /// `true` if the UTXO sits on the internal (change) keychain;
    /// `false` for the external (receive) keychain.
    pub is_change: bool,
    /// `true` once the UTXO has at least one confirmation; `false` while
    /// it is still in the mempool.
    pub confirmed: bool,
    /// `true` when the user froze this output (BIP-329 `output` label
    /// with `spendable: false`). Frozen outputs are skipped by automatic
    /// selection and refused in manual coin control.
    pub frozen: bool,
}

/// One revealed address of the wallet, as listed by
/// [`OnchainAccount::list_addresses`].
#[derive(Debug, Clone)]
pub struct DerivedAddress {
    /// Derivation index on its keychain.
    pub index: u32,
    pub address: Address,
    /// `true` for the internal (change) keychain.
    pub is_change: bool,
    /// The script has received at least one output (spent or not).
    pub used: bool,
    /// BIP-329 `addr` label text, if any.
    pub label: Option<String>,
    /// BHODL `category` extension on the `addr` label, if any.
    pub category: Option<String>,
}

/// One receive label copied onto a transaction by
/// [`OnchainAccount::apply_receive_labels`]. `label` / `category` are
/// what was actually written (`None` = the transaction already had one).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ReceiveLabelCopy {
    pub txid: String,
    pub address: String,
    pub label: Option<String>,
    pub category: Option<String>,
}

/// A BIP86 taproot single-sig account backed by BDK 3.x.
///
/// The struct holds:
/// - a BDK [`Wallet`] as the primary balance / address engine (in-memory,
///   no persistence in Phase 1),
/// - the master [`Xpriv`] and [`Secp256k1`] context retained for the
///   manual BIP32 path used by [`peek_taproot_address`], which must
///   produce bit-for-bit identical output to the BIP86 test vectors.
///
/// **Phase-1 trade-offs**: no zeroize on the in-memory key material, no
/// signing. The on-disk DB is `SQLCipher`-encrypted (AES-256, key from
/// caller); the master xpriv held in this struct is still plain memory.
///
/// Derivation path follows BIP86: `m/86'/coin'/0'/0/index` (external) and
/// `m/86'/coin'/0'/1/index` (internal / change), where `coin = 0` for
/// mainnet and `coin = 1` for testnet / signet / regtest (BIP44 convention).
pub struct OnchainAccount {
    network: BhodlNetwork,
    /// Master xpriv when this account was built from a mnemonic
    /// (`from_mnemonic` / `create_or_load_persistent`). `None` for accounts
    /// loaded from an existing encrypted DB via `load_persistent` — in that
    /// path the secret material is owned by BDK's descriptor store inside
    /// the `SQLCipher` database and accessed only when signing.
    xpriv: Option<Xpriv>,
    secp: Secp256k1<All>,
    /// Always a `PersistedWallet`. In-memory accounts back this with a
    /// `:memory:` `SQLite` handle so the type stays unified and `persist`
    /// is always callable (a no-op write to the in-memory DB).
    wallet: PersistedWallet<Connection>,
    db_conn: Connection,
}

impl std::fmt::Debug for OnchainAccount {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        // `rusqlite::Connection` is not `Debug`.
        f.debug_struct("OnchainAccount")
            .field("network", &self.network)
            .finish_non_exhaustive()
    }
}

impl Drop for OnchainAccount {
    /// Best-effort scrub of the master `Xpriv` key material when the
    /// account goes out of scope.
    ///
    /// Both the private key scalar and the chain code are wiped:
    ///
    /// - `private_key.non_secure_erase()` — compiler-fence-aware
    ///   memset-to-zero (secp256k1 crate). Not a hard guarantee (register
    ///   spills, optimiser copies), but it materially shrinks the window
    ///   during which the key sits in unused heap pages.
    ///
    /// - `chain_code` overwrite — the chain code is the right-half of the
    ///   BIP32 Xpriv. Combined with any non-hardened derived leaf key it
    ///   allows parent-key reconstruction, so it must be wiped alongside
    ///   the private key. We overwrite it with `[0u8; 32]` which is safe
    ///   on bitcoin 0.32 (`ChainCode` is a plain newtype over `[u8; 32]`
    ///   with no Drop-invariant). This is best-effort for the same reason
    ///   as `non_secure_erase`.
    ///
    /// BDK-side state (descriptor strings, change set) lives in `wallet`
    /// and is dropped through BDK's normal path; nothing there is a
    /// standalone private key.
    fn drop(&mut self) {
        if let Some(xpriv) = self.xpriv.as_mut() {
            xpriv.private_key.non_secure_erase();
            // Overwrite the chain code so parent-key reconstruction from
            // any leaked non-hardened child is no longer possible after drop.
            xpriv.chain_code = bitcoin::bip32::ChainCode::from([0u8; 32]);
        }
    }
}

impl OnchainAccount {
    /// Build an in-memory account from a BIP39 mnemonic phrase.
    ///
    /// `passphrase` is the optional BIP39 passphrase ("25th word"); pass `""`
    /// for none.
    ///
    /// The returned wallet is fully in-memory: every mutation (sync,
    /// address reveal) is lost when the value is dropped. Use
    /// [`create_or_load_persistent`] for a disk-backed wallet.
    pub fn from_mnemonic(
        network: BhodlNetwork,
        mnemonic_phrase: &str,
        passphrase: &str,
    ) -> Result<Self, OnchainError> {
        let mut parts = derive_descriptor_parts(network, mnemonic_phrase, passphrase)?;
        let mut conn =
            Connection::open_in_memory().map_err(|e| OnchainError::Storage(e.to_string()))?;
        // `consume_descriptors` moves the strings out of `parts`, leaving
        // empty strings behind. `parts` still drops normally and wipes xpriv
        // + the (now-empty) descriptor fields.
        let (desc_ext, desc_int) = parts.consume_descriptors();
        let wallet = Wallet::create(desc_ext, desc_int)
            .network(parts.btc_network)
            .create_wallet(&mut conn)
            .map_err(|e| OnchainError::WalletCreate(e.to_string()))?;
        ensure_labels_table(&conn)?;
        ensure_contacts_table(&conn)?;

        let xpriv = parts.take_xpriv();
        let secp = parts.take_secp();
        Ok(Self {
            network,
            xpriv: Some(xpriv),
            secp,
            wallet,
            db_conn: conn,
        })
    }

    /// Open or initialise a SQLCipher-encrypted account at `db_path`.
    ///
    /// `encryption_key` is a raw 256-bit AES key applied via `PRAGMA key`
    /// before any other SQL is executed. In production this key is derived
    /// from a hardware-backed secret (Keystore on Android, Secure Enclave
    /// on iOS, OS keyring on desktop) — never store it next to the DB file.
    ///
    /// Behaviour:
    /// - If `db_path` does not exist (or holds no wallet yet), a fresh
    ///   wallet is created with the supplied encryption key and the
    ///   initial state is persisted.
    /// - If `db_path` already holds a wallet, the encryption key must
    ///   match. Wrong key → [`OnchainError::Storage`] before BDK sees
    ///   the connection.
    /// - The descriptors derived from `mnemonic_phrase` / `passphrase` /
    ///   `network` must also match the stored ones; mismatch →
    ///   [`OnchainError::Storage`].
    ///
    /// All future state mutations (sync update, address reveal) are
    /// written through to the encrypted store automatically.
    pub fn create_or_load_persistent(
        network: BhodlNetwork,
        mnemonic_phrase: &str,
        passphrase: &str,
        db_path: &Path,
        encryption_key: &[u8; ENCRYPTION_KEY_LEN],
    ) -> Result<Self, OnchainError> {
        let mut parts = derive_descriptor_parts(network, mnemonic_phrase, passphrase)?;

        let mut conn = open_encrypted_connection(db_path, encryption_key)?;

        // Try to load an existing wallet keyed by the derived descriptors.
        // `Wallet::load()` returns `Ok(None)` if the DB is empty / new.
        // We clone the descriptor strings for the load attempt and move
        // the originals (via `consume_descriptors`) into the create
        // fallback, so the strings are never duplicated on heap longer
        // than necessary.
        let loaded = Wallet::load()
            .descriptor(KeychainKind::External, Some(parts.desc_ext.clone()))
            .descriptor(KeychainKind::Internal, Some(parts.desc_int.clone()))
            .extract_keys()
            .check_network(parts.btc_network)
            .load_wallet(&mut conn)
            .map_err(|e| map_wallet_load_error(&e))?;

        // Move the descriptor strings out now — `parts.drop` will wipe
        // the empty fields and xpriv regardless of which branch runs.
        let (desc_ext, desc_int) = parts.consume_descriptors();
        let wallet = match loaded {
            Some(w) => w,
            None => Wallet::create(desc_ext, desc_int)
                .network(parts.btc_network)
                .create_wallet(&mut conn)
                .map_err(|e| OnchainError::WalletCreate(e.to_string()))?,
        };
        ensure_labels_table(&conn)?;
        ensure_contacts_table(&conn)?;
        // Persist the seed so a future `load_persistent` (no mnemonic
        // from the user) can rebuild the SECRET descriptors and produce
        // a wallet capable of finalising PSBTs. INSERT OR IGNORE keeps
        // a previously-stored seed sticky on subsequent opens.
        ensure_seed_table(&conn)?;
        save_seed_if_absent(&conn, mnemonic_phrase, passphrase)?;

        let xpriv = parts.take_xpriv();
        let secp = parts.take_secp();
        Ok(Self {
            network,
            xpriv: Some(xpriv),
            secp,
            wallet,
            db_conn: conn,
        })
    }

    /// Open an existing SQLCipher-encrypted account at `db_path` WITHOUT
    /// requiring the user's mnemonic.
    ///
    /// BDK persists the descriptor strings — including the master xpriv
    /// embedded in them — inside the encrypted database. Once `SQLCipher`
    /// has decrypted the file with the supplied `encryption_key`, BDK's
    /// `Wallet::load` rebuilds a working wallet (capable of signing) from
    /// what is stored. The constructed `OnchainAccount` carries
    /// `xpriv: None`; address derivation routes through BDK rather than
    /// the manual BIP32 path.
    ///
    /// # Errors
    ///
    /// - [`OnchainError::Storage`] if `encryption_key` does not match,
    ///   the file is not a wallet DB, the DB is empty (no descriptor
    ///   to load), or the persisted network does not equal `network`.
    pub fn load_persistent(
        network: BhodlNetwork,
        db_path: &Path,
        encryption_key: &[u8; ENCRYPTION_KEY_LEN],
    ) -> Result<Self, OnchainError> {
        let mut conn = open_encrypted_connection(db_path, encryption_key)?;

        // Read the seed so we can pass the SECRET descriptors back into
        // `Wallet::load`. Without them BDK rebuilds a watch-only wallet
        // (public-only descriptor projection in the ChangeSet) and signing
        // fails with "PSBT signed but not fully finalised".
        ensure_seed_table(&conn)?;
        let (mnemonic, passphrase) = read_seed(&conn)?.ok_or_else(|| {
            OnchainError::Storage(
                "seed table is empty — wallet was not created via this app".into(),
            )
        })?;
        let mut parts = derive_descriptor_parts(network, &mnemonic, &passphrase)?;

        let loaded = Wallet::load()
            .descriptor(KeychainKind::External, Some(parts.desc_ext.clone()))
            .descriptor(KeychainKind::Internal, Some(parts.desc_int.clone()))
            .extract_keys()
            .check_network(parts.btc_network)
            .load_wallet(&mut conn)
            .map_err(|e| map_wallet_load_error(&e))?;

        // Consume + drop the descriptor strings from `parts` early.
        // The Xpriv and chain code will be wiped when `parts` drops.
        let _ = parts.consume_descriptors();
        // Take the secp context (needed below) before parts drops.
        // Note: we explicitly do NOT call take_xpriv() — we want the
        // xpriv to remain inside `parts` so its Drop wipes it, and
        // we do not expose it on the OnchainAccount struct (xpriv: None).
        let secp = parts.take_secp();

        let wallet = loaded.ok_or_else(|| {
            OnchainError::Storage("encrypted DB exists but contains no wallet".into())
        })?;
        ensure_labels_table(&conn)?;
        ensure_contacts_table(&conn)?;

        // Do NOT retain the derived xpriv on the struct after a
        // load_persistent call. BDK already holds the key material in its
        // descriptor store (inside the SQLCipher file). `peek_taproot_address`
        // falls back to `Wallet::peek_address` when `xpriv` is None —
        // that path produces the same addresses. Retaining `xpriv: Some(...)`
        // here would keep the master key in plain heap memory for the entire
        // session without any additional capability.
        Ok(Self {
            network,
            xpriv: None,
            secp,
            wallet,
            db_conn: conn,
        })
    }

    /// Flush any pending state mutations through to the backing store.
    /// On in-memory wallets this is a write to the `:memory:` `SQLite`
    /// handle (effectively a no-op for durability).
    fn persist(&mut self) -> Result<(), OnchainError> {
        self.wallet
            .persist(&mut self.db_conn)
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(())
    }

    /// Network this account is bound to.
    #[must_use]
    pub fn network(&self) -> BhodlNetwork {
        self.network
    }

    /// Derive the BIP86 taproot receive address at `index` on the external
    /// chain (`change = 0`) without advancing BDK's derivation counter.
    ///
    /// When this account was constructed with a known mnemonic
    /// (`from_mnemonic` / `create_or_load_persistent`) the result is
    /// computed from the master xpriv via the manual BIP32 path that the
    /// BIP86 test vectors lock down. When the account was loaded from an
    /// existing encrypted DB without a mnemonic (`load_persistent`) we
    /// fall back to BDK's `Wallet::peek_address`, which uses the same
    /// descriptor string and therefore lands on the same address.
    ///
    /// Use [`next_unused_address`] when you need to track which addresses
    /// have been revealed.
    pub fn peek_taproot_address(&self, index: u32) -> Result<Address, OnchainError> {
        if let Some(xpriv) = self.xpriv {
            let coin: u32 = u32::from(!matches!(self.network, BhodlNetwork::Mainnet));
            let path = DerivationPath::from_str(&format!("m/86'/{coin}'/0'/0/{index}"))
                .map_err(|e| OnchainError::DerivationPath(e.to_string()))?;
            let derived = xpriv.derive_priv(&self.secp, &path)?;
            let internal_pubkey = derived.private_key.x_only_public_key(&self.secp).0;
            Ok(Address::p2tr(
                &self.secp,
                internal_pubkey,
                None,
                map_network(self.network),
            ))
        } else {
            Ok(self
                .wallet
                .peek_address(KeychainKind::External, index)
                .address)
        }
    }

    /// Reveal the next external address to the caller and **advance BDK's
    /// derivation counter** so subsequent calls return the next index.
    ///
    /// On a freshly created wallet the first call returns index 0 (matching
    /// `peek_taproot_address(0)`); the second call returns index 1, and so
    /// on. Disk-backed wallets persist the reveal, so a process restart
    /// continues where the previous run left off.
    ///
    /// Use this when you are about to show an address to the user. Use
    /// [`peek_taproot_address`] for offline derivation (testing, debug,
    /// receipt verification) without consuming an index.
    ///
    /// # Errors
    ///
    /// Returns [`OnchainError::Storage`] if persisting the reveal fails.
    pub fn reveal_next_address(&mut self) -> Result<Address, OnchainError> {
        let address = self
            .wallet
            .reveal_next_address(KeychainKind::External)
            .address;
        self.persist()?;
        Ok(address)
    }

    /// Lowest revealed receive address that has not received funds yet,
    /// revealing (and persisting) a new one only when every revealed
    /// address is used. Lets the Receive screen hand out an address
    /// without burning one index per visit (gap limit on restore).
    ///
    /// # Errors
    ///
    /// Returns [`OnchainError::Storage`] if persisting a reveal fails.
    pub fn next_unused_receive_address(&mut self) -> Result<Address, OnchainError> {
        let address = self
            .wallet
            .next_unused_address(KeychainKind::External)
            .address;
        self.persist()?;
        Ok(address)
    }

    /// Total wallet balance (confirmed + all pending), expressed in satoshis.
    ///
    /// Returns [`Amount::ZERO`] until at least one successful
    /// [`sync_with_esplora`] call has been made.
    #[must_use]
    pub fn balance(&self) -> Amount {
        let b = self.wallet.balance();
        // total() sums confirmed + trusted_pending + untrusted_pending + immature.
        // bdk_wallet::Amount is bitcoin::Amount (u64 sats). We map it to
        // bhodl_core::Amount which is also sat-integer u64.
        Amount::from_sat(b.total().to_sat())
    }

    /// Synchronise the wallet against an Esplora HTTP API (blocking).
    ///
    /// Uses a `stop_gap` of 20 (BIP44 recommendation) and up to 4 parallel
    /// HTTP requests. No async runtime required — the underlying
    /// `esplora_client::BlockingClient` uses `ureq` with `rustls`.
    ///
    /// # Errors
    ///
    /// Returns [`OnchainError::EsploraSync`] if the HTTP request fails or the
    /// server returns an unexpected response.
    pub fn sync_with_esplora(
        &mut self,
        esplora_url: &str,
        proxy_url: Option<&str>,
    ) -> Result<(), OnchainError> {
        let pending = self.start_esplora_scan();
        let update = pending.fetch(esplora_url, proxy_url)?;
        self.apply_esplora_scan(update)
    }

    /// Phase 1 of a lock-friendly sync (audit M5): snapshot the full-scan
    /// request from the wallet. Cheap and offline — callers that guard
    /// the account with a mutex take it only for this call, run
    /// [`PendingEsploraScan::fetch`] (the network part) with the lock
    /// released, then re-lock for [`Self::apply_esplora_scan`].
    #[must_use]
    pub fn start_esplora_scan(&self) -> PendingEsploraScan {
        PendingEsploraScan {
            request: self.wallet.start_full_scan().build(),
            isolation_scope: self.isolation_scope(),
        }
    }

    /// Tor stream-isolation scope of this wallet (see [`crate::tor`]).
    fn isolation_scope(&self) -> String {
        wallet_isolation_scope(
            &self
                .wallet
                .public_descriptor(KeychainKind::External)
                .to_string(),
        )
    }

    /// Phase 3 of a lock-friendly sync: merge a fetched update into the
    /// wallet and persist it. BDK merges updates monotonically, so state
    /// changed between [`Self::start_esplora_scan`] and this call (e.g.
    /// a revealed address) is preserved.
    ///
    /// # Errors
    ///
    /// [`OnchainError::EsploraSync`] if the update does not connect to the
    /// local chain; [`OnchainError::Storage`] if persisting fails.
    pub fn apply_esplora_scan(&mut self, update: EsploraScanUpdate) -> Result<(), OnchainError> {
        self.wallet
            .apply_update(update.update)
            .map_err(|e| OnchainError::EsploraSync(e.to_string()))?;
        self.persist()?;
        Ok(())
    }

    /// Build an unsigned PSBT that pays `amount` to `to_address` at the
    /// requested fee rate.
    ///
    /// The PSBT is returned as the canonical base64 string form
    /// (`Display` of `bitcoin::psbt::Psbt`). Callers that want the raw
    /// bytes can re-parse via `Psbt::from_str` and serialise differently.
    ///
    /// Coin selection follows BDK's default policy, minus the coins in
    /// [`Self::auto_selection_exclusions`] (BIP-329 frozen outputs and
    /// unconfirmed outputs received from third parties). Change is sent
    /// to the internal keychain; the change reveal is persisted.
    ///
    /// `allow_high_fee` must only be `true` after the user confirmed a
    /// fee above the absolute-fee guard a second time (see
    /// [`OnchainError::FeeTooHigh`]).
    ///
    /// # Errors
    ///
    /// - [`OnchainError::InvalidAddress`] if the address fails to parse or
    ///   belongs to a different network than this account.
    /// - [`OnchainError::InvalidFeeRate`] if `fee_rate_sat_per_vb` is 0
    ///   (outside regtest) or above [`crate::MAX_FEE_RATE_SAT_PER_VB`].
    /// - [`OnchainError::TxBuild`] if BDK refuses to build (insufficient
    ///   funds, dust output, broken descriptor, ...).
    /// - [`OnchainError::FeeTooHigh`] if the fee exceeds the absolute
    ///   guard and `allow_high_fee` is `false`.
    pub fn build_send_tx(
        &mut self,
        to_address: &str,
        amount: Amount,
        fee_rate_sat_per_vb: u64,
        allow_high_fee: bool,
    ) -> Result<String, OnchainError> {
        let recipient = self.parse_recipient(to_address)?;
        let fee_rate = send_policy::validated_fee_rate(fee_rate_sat_per_vb, self.network)?;
        let excluded = self.auto_selection_exclusions()?;

        let mut builder = self.wallet.build_tx();
        builder
            .add_recipient(
                recipient.script_pubkey(),
                bitcoin::Amount::from_sat(amount.to_sat()),
            )
            .fee_rate(fee_rate)
            .unspendable(excluded);
        let psbt = builder
            .finish()
            .map_err(|e| OnchainError::TxBuild(e.to_string()))?;

        // BDK reveals the change address as part of build_tx; persist so a
        // subsequent reload does not re-issue the same change index.
        self.persist()?;

        self.finish_build(&psbt, allow_high_fee)
    }

    /// Sign and finalise a PSBT (base64) with this account's master xpriv.
    ///
    /// Returns the signed PSBT in base64 form. Use [`Self::extract_signed_tx`]
    /// or [`Self::broadcast_tx`] to convert to a network transaction.
    ///
    /// # Errors
    ///
    /// - [`OnchainError::InvalidPsbt`] if the input is not a valid PSBT.
    /// - [`OnchainError::Sign`] if signing or finalisation fails (missing
    ///   keys, wrong network, descriptor mismatch).
    pub fn sign_psbt(&self, psbt_b64: &str) -> Result<String, OnchainError> {
        let mut psbt: Psbt = psbt_b64
            .parse()
            .map_err(|e: bitcoin::psbt::PsbtParseError| OnchainError::InvalidPsbt(e.to_string()))?;

        // `trust_witness_utxo: true` lets BDK finalise SegWit/taproot
        // inputs that carry only `witness_utxo` (no `non_witness_utxo`
        // full previous transaction). Safe for taproot because BIP-341
        // commits to the spent amount in the signature digest, so the
        // historical "witness amount inflation" attack BDK guards against
        // by default does not apply.
        //
        // Default also sets `sign_with_tap_internal_key: true` and
        // `try_finalize: true`, which are exactly what we want for the
        // BIP86 single-key-path descriptor we ship.
        let options = SignOptions {
            trust_witness_utxo: true,
            ..SignOptions::default()
        };

        let finalised = self
            .wallet
            .sign(&mut psbt, options)
            .map_err(|e| OnchainError::Sign(e.to_string()))?;

        if !finalised {
            return Err(OnchainError::Sign(
                "PSBT signed but not fully finalised; more signatures required".into(),
            ));
        }

        Ok(psbt.to_string())
    }

    /// Extract the network transaction from a signed+finalised PSBT (base64).
    ///
    /// # Errors
    ///
    /// - [`OnchainError::InvalidPsbt`] if the input cannot be parsed or is
    ///   not yet finalised.
    pub fn extract_signed_tx(psbt_b64: &str) -> Result<bitcoin::Transaction, OnchainError> {
        let psbt: Psbt = psbt_b64
            .parse()
            .map_err(|e: bitcoin::psbt::PsbtParseError| OnchainError::InvalidPsbt(e.to_string()))?;
        psbt.extract_tx()
            .map_err(|e| OnchainError::InvalidPsbt(e.to_string()))
    }

    /// Read the miner fee, in satoshis, from a (possibly unsigned) PSBT.
    ///
    /// Used by the desktop review screen to display the fee the wallet
    /// will pay before the user authorises the broadcast. Pure decode —
    /// does not require network access or a live wallet.
    ///
    /// # Errors
    ///
    /// - [`OnchainError::InvalidPsbt`] if the input cannot be parsed or
    ///   does not carry the input UTXO data needed to compute a fee
    ///   (`Psbt::fee()` requires every input to declare its previous
    ///   output, which `build_send_tx` always populates).
    pub fn psbt_fee_sat(psbt_b64: &str) -> Result<u64, OnchainError> {
        let psbt: Psbt = psbt_b64
            .parse()
            .map_err(|e: bitcoin::psbt::PsbtParseError| OnchainError::InvalidPsbt(e.to_string()))?;
        let fee = psbt
            .fee()
            .map_err(|e| OnchainError::InvalidPsbt(format!("fee compute: {e}")))?;
        Ok(fee.to_sat())
    }

    /// Decode a PSBT into the [`PsbtSummary`] the review screens render:
    /// every output (with ownership / change detection from the wallet's
    /// script index), the fee, the estimated signed vsize, what goes to
    /// third parties and what the wallet balance really loses.
    ///
    /// The review UI must show ONLY this summary and then sign exactly
    /// the same PSBT string — never values carried in navigation
    /// arguments.
    ///
    /// For inputs the wallet knows, the PSBT's claimed previous output
    /// must match the wallet's copy (a PSBT lying about an input amount
    /// would otherwise misstate the fee).
    ///
    /// # Errors
    ///
    /// [`OnchainError::InvalidPsbt`] if the PSBT does not parse, lacks
    /// previous-output data for an input, contradicts the wallet's view
    /// of an input, or spends more than it consumes.
    #[allow(
        clippy::too_many_lines,
        reason = "one linear validation pass; splitting it would scatter the input/output checks"
    )]
    pub fn decode_psbt(&self, psbt_b64: &str) -> Result<PsbtSummary, OnchainError> {
        let psbt: Psbt = psbt_b64
            .parse()
            .map_err(|e: bitcoin::psbt::PsbtParseError| OnchainError::InvalidPsbt(e.to_string()))?;
        if psbt.inputs.len() != psbt.unsigned_tx.input.len()
            || psbt.outputs.len() != psbt.unsigned_tx.output.len()
        {
            return Err(OnchainError::InvalidPsbt("PSBT map count mismatch".into()));
        }
        let overflow = || OnchainError::InvalidPsbt("amount overflow".into());
        let untrusted: std::collections::HashSet<OutPoint> =
            self.untrusted_unconfirmed_outpoints().into_iter().collect();

        let mut inputs_total_sat = 0_u64;
        let mut own_in_sat = 0_u64;
        let mut foreign_input_count = 0_u32;
        let mut spends_unconfirmed_foreign = false;
        for (i, (txin, input)) in psbt.unsigned_tx.input.iter().zip(&psbt.inputs).enumerate() {
            let op = txin.previous_output;
            let claimed = match (&input.witness_utxo, &input.non_witness_utxo) {
                (Some(w), _) => w.clone(),
                (None, Some(prev)) => {
                    if prev.compute_txid() != op.txid {
                        return Err(OnchainError::InvalidPsbt(format!(
                            "input #{i}: non_witness_utxo does not match its outpoint"
                        )));
                    }
                    usize::try_from(op.vout)
                        .ok()
                        .and_then(|v| prev.output.get(v))
                        .cloned()
                        .ok_or_else(|| {
                            OnchainError::InvalidPsbt(format!("input #{i}: vout out of range"))
                        })?
                }
                (None, None) => {
                    return Err(OnchainError::InvalidPsbt(format!(
                        "input #{i} lacks its previous output"
                    )));
                }
            };
            if let Some(known) = self.wallet.tx_graph().get_txout(op) {
                if *known != claimed {
                    return Err(OnchainError::InvalidPsbt(format!(
                        "input #{i}: previous output differs from the wallet's record"
                    )));
                }
            }
            let value = claimed.value.to_sat();
            inputs_total_sat = inputs_total_sat.checked_add(value).ok_or_else(overflow)?;
            if self.wallet.is_mine(claimed.script_pubkey.clone()) {
                own_in_sat = own_in_sat.checked_add(value).ok_or_else(overflow)?;
            } else {
                foreign_input_count = foreign_input_count.saturating_add(1);
            }
            spends_unconfirmed_foreign |= untrusted.contains(&op);
        }

        let btc_network = map_network(self.network);
        let mut outputs = Vec::with_capacity(psbt.unsigned_tx.output.len());
        let mut outputs_total_sat = 0_u64;
        let mut own_out_sat = 0_u64;
        let mut sent_external_sat = 0_u64;
        for out in &psbt.unsigned_tx.output {
            let spk = &out.script_pubkey;
            let value = out.value.to_sat();
            let is_mine = self.wallet.is_mine(spk.clone());
            let is_change = self
                .wallet
                .derivation_of_spk(spk.clone())
                .is_some_and(|(k, _)| k == KeychainKind::Internal);
            outputs_total_sat = outputs_total_sat.checked_add(value).ok_or_else(overflow)?;
            if is_mine {
                own_out_sat = own_out_sat.checked_add(value).ok_or_else(overflow)?;
            } else {
                sent_external_sat = sent_external_sat.checked_add(value).ok_or_else(overflow)?;
            }
            let address = Address::from_script(spk, btc_network).map_or_else(
                |_| format!("script:{}", spk.to_hex_string()),
                |a| a.to_string(),
            );
            outputs.push(PsbtOutputInfo {
                address,
                amount_sat: value,
                is_mine,
                is_change,
            });
        }
        let fee_sat = inputs_total_sat
            .checked_sub(outputs_total_sat)
            .ok_or_else(|| OnchainError::InvalidPsbt("outputs exceed inputs".into()))?;
        let fee_limit_sat = send_policy::absolute_fee_limit_sat(sent_external_sat);
        Ok(PsbtSummary {
            outputs,
            input_count: u32::try_from(psbt.inputs.len()).unwrap_or(u32::MAX),
            foreign_input_count,
            inputs_total_sat,
            fee_sat,
            vsize: send_policy::estimate_signed_vsize(&psbt),
            sent_external_sat,
            wallet_net_debit_sat: own_in_sat.saturating_sub(own_out_sat),
            fee_limit_sat,
            fee_exceeds_limit: fee_sat > fee_limit_sat,
            spends_unconfirmed_foreign,
        })
    }

    /// Broadcast a signed+finalised PSBT through an Esplora HTTP endpoint.
    ///
    /// Returns the resulting `Txid`. On success the transaction is applied
    /// to the local wallet view as unconfirmed (and persisted), so the
    /// next build neither re-selects the coins it spent nor treats its
    /// change as missing — and an RBF replacement immediately supersedes
    /// the original locally. A full [`Self::sync_with_esplora`] is still
    /// the way to learn about confirmations.
    ///
    /// # Errors
    ///
    /// - [`OnchainError::InvalidPsbt`] if the PSBT is malformed or unsigned.
    /// - [`OnchainError::Broadcast`] if the Esplora endpoint rejects the tx.
    pub fn broadcast_tx(
        &mut self,
        esplora_url: &str,
        psbt_b64: &str,
        proxy_url: Option<&str>,
    ) -> Result<Txid, OnchainError> {
        let tx = Self::extract_signed_tx(psbt_b64)?;
        let txid = tx.compute_txid();

        let client = build_esplora_client(esplora_url, proxy_url, &self.isolation_scope());
        client
            .broadcast(&tx)
            .map_err(|e| OnchainError::Broadcast(e.to_string()))?;

        self.record_broadcast(tx);
        Ok(txid)
    }

    /// Pull a three-bucket fee-rate snapshot from Esplora's
    /// `/fee-estimates` endpoint.
    ///
    /// Esplora returns a `block_target -> sat_per_vB` map covering a
    /// range of confirmation horizons. We collapse it into the buckets
    /// the desktop UI exposes: 1-block (priority), 3-block (normal),
    /// 6-block (economy). When the requested target is missing from
    /// the response we fall back to the smallest target greater than
    /// or equal to it that the server did return. An empty map
    /// (signet / regtest with no recent fee data) or an implausible one
    /// is an [`OnchainError::FeeEstimateUnavailable`] error — never a
    /// silent 1 sat/vB (see `send_policy::fee_buckets_from_raw`).
    ///
    /// This call is read-only and does not require an unlocked wallet
    /// — the method takes `&self` only to keep the FFI surface
    /// uniform; in practice it ignores all instance state.
    ///
    /// # Errors
    ///
    /// - [`OnchainError::EsploraSync`] if the HTTP request to
    ///   `/fee-estimates` fails or returns malformed data.
    pub fn fetch_fee_estimates(
        &self,
        esplora_url: &str,
        proxy_url: Option<&str>,
    ) -> Result<FeeEstimateBuckets, OnchainError> {
        Self::fetch_fee_estimates_from(esplora_url, proxy_url)
    }

    /// Instance-free form of [`Self::fetch_fee_estimates`], so FFI callers
    /// can run the network request without holding the wallet lock.
    ///
    /// # Errors
    ///
    /// Same as [`Self::fetch_fee_estimates`].
    pub fn fetch_fee_estimates_from(
        esplora_url: &str,
        proxy_url: Option<&str>,
    ) -> Result<FeeEstimateBuckets, OnchainError> {
        let client = try_build_esplora_client(esplora_url, proxy_url)?;
        let raw = get_fee_estimates_lenient(&client)?;
        send_policy::fee_buckets_from_raw(&raw)
    }

    /// Enumerate every transaction the wallet currently knows about,
    /// flattened to [`TxRecord`].
    ///
    /// Order is the order BDK iterates internally — callers that want
    /// "newest first" should sort by `timestamp` descending. Both
    /// confirmed and mempool transactions are included; the
    /// `confirmation_height` field is the only way to tell them apart.
    #[must_use]
    #[allow(
        clippy::cast_possible_wrap,
        reason = "sat balance always fits in i64 (max supply is 2.1e15 sat)"
    )]
    pub fn list_transactions(&self) -> Vec<TxRecord> {
        self.wallet
            .transactions()
            .map(|wt| {
                let tx = &wt.tx_node.tx;
                let txid = wt.tx_node.txid.to_string();
                let (sent, received) = self.wallet.sent_and_received(tx);
                let net_sat = received.to_sat() as i64 - sent.to_sat() as i64;
                let fee_sat = self
                    .wallet
                    .calculate_fee(tx)
                    .ok()
                    .map(bitcoin::Amount::to_sat);
                let (confirmation_height, timestamp) = match wt.chain_position {
                    bdk_chain::ChainPosition::Confirmed { anchor, .. } => {
                        (Some(anchor.block_id.height), Some(anchor.confirmation_time))
                    }
                    bdk_chain::ChainPosition::Unconfirmed { last_seen, .. } => (None, last_seen),
                };
                TxRecord {
                    txid,
                    net_sat,
                    fee_sat,
                    confirmation_height,
                    timestamp,
                }
            })
            .collect()
    }

    /// List all wallet-owned UTXOs that are unspent in the wallet's
    /// current view (confirmed plus mempool).
    ///
    /// The list is the wallet's canonical unspent set. Building a PSBT
    /// does NOT lock its inputs: a UTXO used by a PSBT that has not been
    /// broadcast yet is still listed (and may be selected again). Once a
    /// transaction is broadcast through [`Self::broadcast_tx`] its inputs
    /// disappear from this list and its change outputs appear as
    /// unconfirmed. Unconfirmed outputs received from third parties are
    /// listed too (`confirmed == false`) but automatic coin selection
    /// skips them, as it skips BIP-329 frozen outputs.
    ///
    /// Order is the order BDK iterates internally and is not stable
    /// across BDK versions; callers that want deterministic ordering
    /// should sort by `outpoint`.
    ///
    /// # Errors
    ///
    /// Returns [`OnchainError::TxBuild`] if BDK reports a UTXO whose
    /// `script_pubkey` cannot be expressed as a `bitcoin::Address` for
    /// this network. In practice this indicates internal wallet state
    /// corruption (every BDK-owned script is produced by one of our
    /// descriptors and is guaranteed to be a valid taproot address);
    /// on error the outpoint is included in the message for diagnosis.
    pub fn list_utxos(&self) -> Result<Vec<UtxoInfo>, OnchainError> {
        let btc_network = map_network(self.network);
        let frozen: std::collections::HashSet<OutPoint> = labels::frozen_outpoints(&self.db_conn)?
            .into_iter()
            .collect();
        self.wallet
            .list_unspent()
            .map(|out| {
                let address =
                    Address::from_script(&out.txout.script_pubkey, btc_network).map_err(|e| {
                        OnchainError::TxBuild(format!(
                            "UTXO {} script_pubkey cannot be expressed as an address: {e}",
                            out.outpoint
                        ))
                    })?;
                Ok(UtxoInfo {
                    outpoint: out.outpoint,
                    value: Amount::from_sat(out.txout.value.to_sat()),
                    address,
                    is_change: out.keychain == KeychainKind::Internal,
                    confirmed: out.chain_position.is_confirmed(),
                    frozen: frozen.contains(&out.outpoint),
                })
            })
            .collect()
    }

    /// Height of the wallet's latest synced block (its local chain
    /// tip). `0` before the first successful sync. Used by the UI to
    /// turn a confirmation height into a confirmation count.
    #[must_use]
    pub fn chain_tip_height(&self) -> u32 {
        self.wallet.latest_checkpoint().height()
    }

    /// Every address revealed so far on one keychain (external =
    /// receive, internal = change), oldest first, with its usage state
    /// and BIP-329 `addr` label / category.
    ///
    /// Only revealed indices are listed (`0..=derivation_index`), so a
    /// fresh wallet returns an empty list. `used` is BDK's view: the
    /// script has received at least one output in the synced history
    /// (spent or not), so a spent-from address stays "used".
    ///
    /// # Errors
    ///
    /// Returns [`OnchainError::Storage`] if the label table cannot be read.
    pub fn list_addresses(&self, change: bool) -> Result<Vec<DerivedAddress>, OnchainError> {
        let keychain = if change {
            KeychainKind::Internal
        } else {
            KeychainKind::External
        };
        let Some(last) = self.wallet.derivation_index(keychain) else {
            return Ok(Vec::new());
        };
        let addr_labels: HashMap<String, Label> = labels::list_labels(&self.db_conn)?
            .into_iter()
            .filter(|l| l.kind == LabelKind::Addr)
            .map(|l| (l.target.clone(), l))
            .collect();
        Ok((0..=last)
            .map(|index| {
                let address = self.wallet.peek_address(keychain, index).address;
                let label = addr_labels.get(&address.to_string());
                DerivedAddress {
                    index,
                    is_change: change,
                    used: self.wallet.spk_index().is_used(keychain, index),
                    label: label.map(|l| l.text.clone()).filter(|t| !t.is_empty()),
                    category: label.and_then(|l| l.category.clone()),
                    address,
                }
            })
            .collect())
    }

    /// Set (or clear) the BIP-329 `addr` label and the BHODL `category`
    /// extension on one of this wallet's addresses. Used by the
    /// label-first Receive flow so funds arriving at the address can be
    /// labelled / categorised automatically. A blank `text` with no
    /// category removes the row.
    ///
    /// # Errors
    ///
    /// Returns [`OnchainError::Storage`] on a database failure.
    pub fn set_address_label(
        &self,
        address: &str,
        text: &str,
        category: Option<String>,
    ) -> Result<(), OnchainError> {
        let category = category.filter(|c| !c.trim().is_empty());
        if text.trim().is_empty() && category.is_none() {
            labels::remove_label(&self.db_conn, LabelKind::Addr, address)?;
            return Ok(());
        }
        labels::upsert_label(
            &self.db_conn,
            &Label {
                kind: LabelKind::Addr,
                target: address.to_string(),
                text: text.trim().to_string(),
                spendable: None,
                category,
            },
        )
    }

    /// Label-first receive, second half: copy the BIP-329 `addr` label
    /// and category of a receive address onto the transaction that paid
    /// it. Rules:
    ///
    /// - only transactions with a positive net amount (an outgoing
    ///   self-transfer to a labelled address is NOT relabelled);
    /// - outputs come from the transaction itself, so a coin received
    ///   and already spent still gets its label;
    /// - when one transaction pays several labelled addresses, the
    ///   lowest output index wins (deterministic);
    /// - an existing transaction label / category is never overwritten;
    /// - each `(txid, address)` copy happens once (recorded in the
    ///   encrypted DB), so a label the user clears stays cleared.
    ///
    /// Returns what was applied (the caller mirrors categories into the
    /// tracker registry).
    ///
    /// # Errors
    ///
    /// Returns [`OnchainError::Storage`] on a database failure.
    pub fn apply_receive_labels(&self) -> Result<Vec<ReceiveLabelCopy>, OnchainError> {
        let addr_labels: HashMap<String, Label> = labels::list_labels(&self.db_conn)?
            .into_iter()
            .filter(|l| {
                l.kind == LabelKind::Addr
                    && (!l.text.trim().is_empty()
                        || l.category.as_deref().is_some_and(|c| !c.trim().is_empty()))
            })
            .map(|l| (l.target.clone(), l))
            .collect();
        if addr_labels.is_empty() {
            return Ok(Vec::new());
        }
        let btc_network = map_network(self.network);
        let mut applied = Vec::new();
        for wt in self.wallet.transactions() {
            let tx = &wt.tx_node.tx;
            let (sent, received) = self.wallet.sent_and_received(tx);
            if received <= sent {
                continue;
            }
            let hit = tx.output.iter().find_map(|out| {
                if !self.wallet.is_mine(out.script_pubkey.clone()) {
                    return None;
                }
                let address = Address::from_script(&out.script_pubkey, btc_network)
                    .ok()?
                    .to_string();
                addr_labels.get(&address).map(|l| (address, l))
            });
            let Some((address, addr_label)) = hit else {
                continue;
            };
            let txid = wt.tx_node.txid.to_string();
            if labels::label_copy_done(&self.db_conn, &txid, &address)? {
                continue;
            }
            let existing = labels::get_label(&self.db_conn, LabelKind::Tx, &txid)?;
            let existing_text = existing
                .as_ref()
                .map(|l| l.text.clone())
                .unwrap_or_default();
            let existing_category = existing.as_ref().and_then(|l| l.category.clone());
            let new_text = (existing_text.trim().is_empty() && !addr_label.text.trim().is_empty())
                .then(|| addr_label.text.clone());
            let new_category = if existing_category.is_none() {
                addr_label.category.clone().filter(|c| !c.trim().is_empty())
            } else {
                None
            };
            if new_text.is_some() || new_category.is_some() {
                labels::upsert_label(
                    &self.db_conn,
                    &Label {
                        kind: LabelKind::Tx,
                        target: txid.clone(),
                        text: new_text.clone().unwrap_or(existing_text),
                        spendable: None,
                        category: new_category.clone().or(existing_category),
                    },
                )?;
            }
            labels::mark_label_copy(&self.db_conn, &txid, &address)?;
            applied.push(ReceiveLabelCopy {
                txid,
                address,
                label: new_text,
                category: new_category,
            });
        }
        Ok(applied)
    }

    /// Freeze (BIP-329 `output` label with `spendable: false`) or
    /// unfreeze one of the wallet's outputs. Any existing label text /
    /// category on the output is preserved.
    ///
    /// # Errors
    ///
    /// Returns [`OnchainError::TxBuild`] if `outpoint` is not a
    /// `txid:vout` string, or [`OnchainError::Storage`] on a database
    /// failure.
    pub fn set_output_frozen(&self, outpoint: &str, frozen: bool) -> Result<(), OnchainError> {
        let parsed = OutPoint::from_str(outpoint)
            .map_err(|e| OnchainError::TxBuild(format!("invalid outpoint {outpoint:?}: {e}")))?;
        let target = parsed.to_string();
        let existing = labels::get_label(&self.db_conn, LabelKind::Output, &target)?;
        labels::upsert_label(
            &self.db_conn,
            &Label {
                kind: LabelKind::Output,
                target,
                text: existing
                    .as_ref()
                    .map(|l| l.text.clone())
                    .unwrap_or_default(),
                spendable: Some(!frozen),
                category: existing.and_then(|l| l.category),
            },
        )
    }

    /// Export the wallet's public-only descriptors for backup / recovery.
    ///
    /// The returned [`DescriptorBackup`] contains the same xpub-rooted
    /// descriptors BDK uses internally, with the master `xpriv` removed.
    /// Suitable for:
    ///
    /// - importing into Sparrow / Liana / Specter Desktop as a watch-only
    ///   wallet to verify receive addresses or audit history;
    /// - storing with the BIP39 backup as a recovery aid (the descriptor
    ///   alone reveals the wallet's public surface but cannot spend);
    /// - migrating to a hardware-wallet coordinator that consumes
    ///   descriptor strings (Caravan, Nunchuk).
    ///
    /// The output is purely derived from the BDK wallet state and does
    /// not touch the on-disk DB.
    #[must_use]
    pub fn export_descriptors(&self) -> DescriptorBackup {
        DescriptorBackup {
            network: self.network,
            external: self
                .wallet
                .public_descriptor(KeychainKind::External)
                .to_string(),
            internal: self
                .wallet
                .public_descriptor(KeychainKind::Internal)
                .to_string(),
        }
    }

    // -----------------------------------------------------------------
    // BIP329 labels
    // -----------------------------------------------------------------

    /// Add (or overwrite) a BIP329 label.
    ///
    /// Labels are uniquely keyed by `(kind, target)`, so calling this
    /// twice with the same kind and target replaces the previous text /
    /// spendable flag.
    pub fn add_label(&self, label: &Label) -> Result<(), OnchainError> {
        labels::upsert_label(&self.db_conn, label)
    }

    /// Look up a single BIP329 label by `(kind, target)`. Returns `None`
    /// when no row matches.
    pub fn get_label(&self, kind: LabelKind, target: &str) -> Result<Option<Label>, OnchainError> {
        labels::get_label(&self.db_conn, kind, target)
    }

    /// Enumerate every BIP329 label stored on this account.
    pub fn list_labels(&self) -> Result<Vec<Label>, OnchainError> {
        labels::list_labels(&self.db_conn)
    }

    /// Delete a BIP329 label. Returns `true` if a row was actually removed.
    pub fn remove_label(&self, kind: LabelKind, target: &str) -> Result<bool, OnchainError> {
        labels::remove_label(&self.db_conn, kind, target)
    }

    /// Render every label as a BIP329 JSONL document (one record per line).
    /// Suitable for sharing with Sparrow / Liana / any BIP329 consumer.
    pub fn export_labels_jsonl(&self) -> Result<String, OnchainError> {
        labels::export_jsonl(&self.db_conn)
    }

    /// Import a BIP329 JSONL document produced by another wallet. Each
    /// record is upserted (replaces an existing `(kind, target)` row).
    /// Blank lines are tolerated; malformed records abort with
    /// [`OnchainError::Storage`] without partial commit guarantees.
    ///
    /// Returns the count of records imported.
    pub fn import_labels_jsonl(&self, jsonl: &str) -> Result<usize, OnchainError> {
        labels::import_jsonl(&self.db_conn, jsonl)
    }

    /// Add an address-book entry. Returns the SQL row id assigned to
    /// the new contact. The (`address`) column is unique — re-adding
    /// the same address fails with [`OnchainError::Storage`].
    ///
    /// The address must be a valid address for THIS account's network;
    /// it is stored in its canonical form so the Send screen never
    /// loads a malformed or wrong-network recipient from the book.
    pub fn add_contact(&self, label: &str, address: &str) -> Result<i64, OnchainError> {
        let label = validated_contact_label(label)?;
        let address = self.parse_recipient(address.trim())?.to_string();
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| i64::try_from(d.as_secs()).unwrap_or(i64::MAX))
            .unwrap_or(0);
        contacts::insert_contact(&self.db_conn, label, &address, now)
    }

    /// Return every saved contact in insertion order.
    pub fn list_contacts(&self) -> Result<Vec<Contact>, OnchainError> {
        contacts::list_contacts(&self.db_conn)
    }

    /// Update the label of an existing contact. Returns `true` if
    /// a row was updated, `false` if no row matched `id`.
    pub fn update_contact_label(&self, id: i64, new_label: &str) -> Result<bool, OnchainError> {
        contacts::update_contact_label(&self.db_conn, id, validated_contact_label(new_label)?)
    }

    /// Delete a contact by row id. Returns `true` if a row was
    /// deleted, `false` if no row matched `id`.
    pub fn remove_contact(&self, id: i64) -> Result<bool, OnchainError> {
        contacts::delete_contact(&self.db_conn, id)
    }

    /// Build an unsigned PSBT that drains the entire spendable
    /// balance to `to_address`.
    ///
    /// Uses BDK's `TxBuilder::drain_wallet()` + `drain_to(script)`
    /// pair: every spendable UTXO (confirmed, or unconfirmed change of
    /// our own transactions — never frozen coins or unconfirmed coins
    /// from third parties) becomes an input, and a single output sized
    /// `total_in - fee` ships them all to `to_address`. There is no
    /// change output by construction.
    ///
    /// # Errors
    ///
    /// - [`OnchainError::InvalidAddress`] when `to_address` is
    ///   malformed or for a different network.
    /// - [`OnchainError::InvalidFeeRate`] for out-of-range fee rates.
    /// - [`OnchainError::TxBuild`] when BDK refuses to build the
    ///   spend (insufficient funds for the fee, dust output, …).
    /// - [`OnchainError::FeeTooHigh`] see [`Self::build_send_tx`].
    pub fn build_drain_tx(
        &mut self,
        to_address: &str,
        fee_rate_sat_per_vb: u64,
        allow_high_fee: bool,
    ) -> Result<String, OnchainError> {
        let recipient = self.parse_recipient(to_address)?;
        let fee_rate = send_policy::validated_fee_rate(fee_rate_sat_per_vb, self.network)?;
        let excluded = self.auto_selection_exclusions()?;

        let mut builder = self.wallet.build_tx();
        builder
            .drain_wallet()
            .drain_to(recipient.script_pubkey())
            .fee_rate(fee_rate)
            .unspendable(excluded);
        let psbt = builder
            .finish()
            .map_err(|e| OnchainError::TxBuild(e.to_string()))?;

        self.persist()?;
        self.finish_build(&psbt, allow_high_fee)
    }

    /// Read the mnemonic + BIP-39 passphrase the wallet was created
    /// with. Available only on persistent wallets — in-memory wallets
    /// (`from_mnemonic`) have no `bhodl_seed` row to read from.
    ///
    /// The intended caller is Settings → "Show recovery phrase",
    /// where the user wants to re-display the seed for a paper
    /// backup. Production callers MUST gate this behind a PIN
    /// re-confirmation step — possession of an unlocked
    /// `WalletHandle` already implies the user authenticated, but a
    /// re-confirm matches the value of the operation (full plaintext
    /// seed).
    ///
    /// # Errors
    ///
    /// - [`OnchainError::Storage`] when no `bhodl_seed` row exists,
    ///   i.e. the wallet was built via `from_mnemonic` and never
    ///   persisted.
    pub fn peek_mnemonic(&self) -> Result<(String, String), OnchainError> {
        let (mnemonic, passphrase) = read_seed(&self.db_conn)?.ok_or_else(|| {
            OnchainError::Storage(
                "no seed persisted — peek_mnemonic is only available on \
                 create_or_load_persistent wallets"
                    .into(),
            )
        })?;
        // The FFI reveal screen needs plain Strings; the Zeroizing
        // originals are wiped when they drop here.
        Ok((mnemonic.as_str().to_owned(), passphrase.as_str().to_owned()))
    }

    /// Re-key the encrypted `SQLCipher` store.
    ///
    /// Used by the FFI migration path when the user opens a wallet
    /// whose persisted KDF parameters are below
    /// [`bhodl_core::KdfParams::CURRENT`]: we open the DB with the
    /// old key (already done by `create_or_load_persistent` /
    /// `load_persistent`), then call this with a freshly-derived key
    /// based on the current params + a new salt. `SQLCipher` rewraps
    /// the page-key under the new master key.
    ///
    /// # Errors
    ///
    /// - [`OnchainError::Storage`] when the underlying PRAGMA fails
    ///   (the connection is poisoned, the new key is malformed, etc.).
    pub fn rekey(&self, new_key: &[u8; ENCRYPTION_KEY_LEN]) -> Result<(), OnchainError> {
        let key_literal = key_pragma_literal(new_key);
        self.db_conn
            .pragma_update(None, "rekey", key_literal.as_str())
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(())
    }

    /// Build an encrypted [`crate::BackupBundle`] over the wallet's
    /// seed material and serialise it to age-armored ASCII.
    ///
    /// Reads the mnemonic + passphrase straight out of the encrypted
    /// `bhodl_seed` table, rewraps them under [`password`] (which is
    /// SEPARATE from the on-device PIN), and returns a paste-able
    /// blob the user can save to a paper backup, password manager,
    /// or printed sheet.
    ///
    /// # Errors
    ///
    /// - [`OnchainError::Storage`] when the seed table is empty (the
    ///   wallet was created via [`from_mnemonic`] only and has no
    ///   persisted seed) or when age encryption fails.
    pub fn export_encrypted_backup(&self, password: &str) -> Result<String, OnchainError> {
        let (mnemonic, passphrase) = read_seed(&self.db_conn)?.ok_or_else(|| {
            OnchainError::Storage(
                "no seed persisted — backup is only available for create_or_load_persistent wallets"
                    .into(),
            )
        })?;
        let bundle = crate::BackupBundle {
            version: crate::BackupBundle::CURRENT_VERSION,
            network: format!("{}", self.network),
            mnemonic: mnemonic.as_str().to_owned(),
            passphrase: passphrase.as_str().to_owned(),
        };
        crate::encrypt_bundle(&bundle, password)
    }

    /// Build an unsigned PSBT that spends **only** the UTXOs the caller
    /// explicitly enumerates.
    ///
    /// Compared to [`build_send_tx`], this variant disables BDK's automatic
    /// coin selection (`manually_selected_only`). It is the building block
    /// for coin-control UIs, privacy-aware spends ("never mix this UTXO
    /// with that one"), and airgap workflows where the offline signer
    /// already knows which inputs to expect.
    ///
    /// `utxos` must be a non-empty subset of the wallet's spendable set
    /// (see [`list_utxos`]). The total value of the selected inputs must
    /// cover `amount` plus the resulting fee, otherwise BDK reports
    /// [`OnchainError::TxBuild`]. BIP-329 frozen outputs are refused;
    /// unconfirmed third-party outputs are allowed (explicit user choice)
    /// and flagged by [`Self::decode_psbt`]
    /// (`spends_unconfirmed_foreign`) so the review can warn.
    ///
    /// # Errors
    ///
    /// Same failure modes as [`build_send_tx`] plus:
    /// - [`OnchainError::TxBuild`] when `utxos` is empty, references an
    ///   unknown or frozen outpoint, or the chosen set is short of funds.
    pub fn build_send_tx_with_utxos(
        &mut self,
        to_address: &str,
        amount: Amount,
        fee_rate_sat_per_vb: u64,
        utxos: &[OutPoint],
        allow_high_fee: bool,
    ) -> Result<String, OnchainError> {
        if utxos.is_empty() {
            return Err(OnchainError::TxBuild(
                "utxos selection is empty; use build_send_tx for automatic selection".into(),
            ));
        }

        let recipient = self.parse_recipient(to_address)?;
        let fee_rate = send_policy::validated_fee_rate(fee_rate_sat_per_vb, self.network)?;
        self.reject_frozen(utxos)?;

        let mut builder = self.wallet.build_tx();
        builder
            .add_recipient(
                recipient.script_pubkey(),
                bitcoin::Amount::from_sat(amount.to_sat()),
            )
            .fee_rate(fee_rate)
            .manually_selected_only()
            .add_utxos(utxos)
            .map_err(|e| OnchainError::TxBuild(e.to_string()))?;
        let psbt = builder
            .finish()
            .map_err(|e| OnchainError::TxBuild(e.to_string()))?;

        self.persist()?;
        self.finish_build(&psbt, allow_high_fee)
    }

    /// Build an unsigned PSBT spending to multiple recipients in one
    /// transaction.
    ///
    /// Each `(address, amount)` entry adds one output. BDK runs its
    /// usual coin selection across the wallet's spendable set (minus
    /// frozen / unconfirmed third-party coins); the total of all output
    /// amounts plus fees must fit. Empty recipient lists are rejected.
    ///
    /// # Errors
    ///
    /// - [`OnchainError::TxBuild`] when `recipients` is empty.
    /// - [`OnchainError::InvalidAddress`] if any recipient address is
    ///   malformed or belongs to a different network.
    /// - [`OnchainError::InvalidFeeRate`] / [`OnchainError::TxBuild`] /
    ///   [`OnchainError::FeeTooHigh`] in the same conditions as
    ///   [`build_send_tx`].
    pub fn build_send_tx_batch(
        &mut self,
        recipients: &[(String, Amount)],
        fee_rate_sat_per_vb: u64,
        allow_high_fee: bool,
    ) -> Result<String, OnchainError> {
        if recipients.is_empty() {
            return Err(OnchainError::TxBuild("recipients list is empty".into()));
        }
        let fee_rate = send_policy::validated_fee_rate(fee_rate_sat_per_vb, self.network)?;
        let outputs = self.parse_recipients(recipients)?;
        let excluded = self.auto_selection_exclusions()?;
        let mut builder = self.wallet.build_tx();
        builder
            .set_recipients(outputs)
            .fee_rate(fee_rate)
            .unspendable(excluded);
        let psbt = builder
            .finish()
            .map_err(|e| OnchainError::TxBuild(e.to_string()))?;
        self.persist()?;
        self.finish_build(&psbt, allow_high_fee)
    }

    /// `PayJoin` (BIP-78 v1) sender, step 1 of 2: negotiate.
    ///
    /// Steps:
    /// 1. Parse `uri` (strict BIP-21 rules, see
    ///    `crate::payjoin::PayjoinUri::parse`) and refuse unless its
    ///    address and amount equal `expected_address` /
    ///    `expected_amount_sat` — the values the UI displayed.
    /// 2. Vet the `pj` endpoint (SSRF guard, Tor routing via
    ///    `proxy_url`) BEFORE building anything.
    /// 3. Build the original PSBT and sign + finalize it: BIP-78 wants a
    ///    broadcastable original (the receiver may broadcast it itself),
    ///    and it doubles as our fallback.
    /// 4. POST it with `v=1`, `additionalfeeoutputindex`,
    ///    `maxadditionalfeecontribution` (one extra P2TR input at our
    ///    fee rate), `minfeerate` and `disableoutputsubstitution=true`.
    /// 5. Validate the proposal (`crate::payjoin::validate_proposal`)
    ///    and park it in the session store under a random id.
    ///
    /// The ORIGINAL is signed and finalized here (BIP-78 needs a
    /// broadcastable original and the receiver may publish it itself), so
    /// the absolute-fee guard runs first: a fee above
    /// [`send_policy::absolute_fee_limit_sat`] fails with
    /// [`OnchainError::FeeTooHigh`] unless `allow_high_fee` is `true`.
    /// Nothing is signed for the proposal — the user reviews the
    /// returned summary first, then calls
    /// [`Self::payjoin_sign_and_broadcast`].
    ///
    /// # Errors
    ///
    /// Failures in steps 1-3 are `Err` (URI / mismatch / endpoint →
    /// [`OnchainError::InvalidAddress`]; build → `TxBuild`; fee above the
    /// limit → `FeeTooHigh`; signing → `Sign`). A failure in steps 4-5 is returned as `Ok` with
    /// `failure_reason` set and no `session_id`, so the caller can offer
    /// to broadcast `original_psbt` as a regular payment.
    pub fn payjoin_negotiate(
        &mut self,
        uri: &str,
        expected_address: &str,
        expected_amount_sat: u64,
        fee_rate_sat_per_vb: u64,
        proxy_url: Option<&str>,
        allow_high_fee: bool,
    ) -> Result<crate::payjoin::PayjoinNegotiation, OnchainError> {
        self.payjoin_negotiate_with(
            uri,
            expected_address,
            expected_amount_sat,
            fee_rate_sat_per_vb,
            proxy_url,
            allow_high_fee,
            &crate::payjoin::PayjoinIo::PRODUCTION,
        )
    }

    /// Body of [`Self::payjoin_negotiate`] with the DNS + HTTP side
    /// effects injected. Private on purpose: the only non-test caller
    /// passes `PayjoinIo::PRODUCTION`, and every check (URI binding, SSRF
    /// vetting with the injected resolver, validation) runs regardless of
    /// the `io` in use.
    #[allow(clippy::too_many_lines)] // linear BIP-78 sender script
    #[allow(clippy::too_many_arguments)] // private test seam mirroring the public args + io
    fn payjoin_negotiate_with(
        &mut self,
        uri: &str,
        expected_address: &str,
        expected_amount_sat: u64,
        fee_rate_sat_per_vb: u64,
        proxy_url: Option<&str>,
        allow_high_fee: bool,
        io: &crate::payjoin::PayjoinIo<'_>,
    ) -> Result<crate::payjoin::PayjoinNegotiation, OnchainError> {
        use crate::payjoin as pj;

        // 1. Parse + bind to what the user saw.
        let network = map_network(self.network);
        let parsed = pj::PayjoinUri::parse(uri)?;
        let receiver = parsed
            .address
            .clone()
            .require_network(network)
            .map_err(|e| OnchainError::InvalidAddress(e.to_string()))?;
        let shown = expected_address
            .trim()
            .parse::<Address<bitcoin::address::NetworkUnchecked>>()
            .map_err(|e| OnchainError::InvalidAddress(e.to_string()))?
            .require_network(network)
            .map_err(|e| OnchainError::InvalidAddress(e.to_string()))?;
        if shown.script_pubkey() != receiver.script_pubkey() {
            return Err(OnchainError::InvalidAddress(
                "PayJoin URI address differs from the address shown in the form".into(),
            ));
        }
        if parsed.amount.to_sat() != expected_amount_sat {
            return Err(OnchainError::InvalidAddress(format!(
                "PayJoin URI amount {} sat differs from the {expected_amount_sat} sat shown \
                 in the form",
                parsed.amount.to_sat()
            )));
        }

        // 2. Endpoint vetting before any wallet state changes.
        let route = pj::vet_endpoint(&parsed.endpoint, proxy_url, io.resolve)?;

        // 3. Original PSBT, signed + finalized.
        // Same fee-rate cap and coin-selection exclusions (frozen coins,
        // unconfirmed third-party coins) as every other send path.
        let fee_rate = send_policy::validated_fee_rate(fee_rate_sat_per_vb, self.network)?;
        let excluded = self.auto_selection_exclusions()?;
        let payment_spk = receiver.script_pubkey();
        let mut builder = self.wallet.build_tx();
        builder
            .add_recipient(payment_spk.clone(), parsed.amount)
            .fee_rate(fee_rate)
            .unspendable(excluded);
        let original = builder
            .finish()
            .map_err(|e| OnchainError::TxBuild(e.to_string()))?;
        self.persist()?;

        // Same absolute-fee guard as every other send path, BEFORE the
        // original is signed: once signed + finalized it is a complete
        // broadcastable payment that the receiver may publish itself.
        self.check_absolute_fee(&original, allow_high_fee)?;

        let mut original_signed = original.clone();
        let finalized = self
            .wallet
            .sign(&mut original_signed, payjoin_sign_options())
            .map_err(|e| OnchainError::Sign(e.to_string()))?;
        if !finalized {
            return Err(OnchainError::Sign(
                "PayJoin original could not be finalized".into(),
            ));
        }
        let original_b64 = original_signed.to_string();

        let wallet = &self.wallet;
        let is_mine = |s: &bitcoin::Script| wallet.is_mine(s.to_owned());
        let outs = &original.unsigned_tx.output;
        let payment_index = outs
            .iter()
            .position(|o| o.script_pubkey == payment_spk)
            .ok_or_else(|| {
                OnchainError::TxBuild("original PSBT lacks the payment output".into())
            })?;
        let fee_output_index = outs
            .iter()
            .enumerate()
            .find(|(i, o)| *i != payment_index && is_mine(&o.script_pubkey))
            .map(|(i, _)| i);
        let max_additional_fee = if fee_output_index.is_some() {
            fee_rate_sat_per_vb
                .checked_mul(pj::P2TR_KEYPATH_INPUT_VSIZE)
                .ok_or_else(|| OnchainError::InvalidFeeRate("fee rate overflow".into()))?
        } else {
            0
        };
        let params = pj::PayjoinParams {
            payment_index,
            fee_output_index,
            max_additional_fee,
            fee_rate_sat_vb: fee_rate_sat_per_vb,
        };
        let original_fee = pj::psbt_fee_checked(&original)?;
        let sender_in = outs
            .iter()
            .try_fold(original_fee, |acc, o| acc.checked_add(o.value.to_sat()))
            .ok_or_else(|| OnchainError::TxBuild("original input sum overflow".into()))?;

        // 4 + 5. POST, parse, validate.
        let outcome = (io.post)(&route, &original_b64, &params)
            .and_then(|body| pj::parse_psbt_response(&body))
            .and_then(|proposal| pj::validate_proposal(&original, &proposal, &params, &is_mine));

        match outcome {
            Ok(validated) => {
                let outputs =
                    pj::summarize_outputs(&validated.psbt, &payment_spk, network, &is_mine);
                let summary = pj::PayjoinNegotiation {
                    session_id: None,
                    failure_reason: None,
                    original_psbt: original_b64,
                    original_fee_sat: validated.original_fee,
                    proposal_fee_sat: validated.proposal_fee,
                    sender_fee_contribution_sat: validated.contribution,
                    vsize: validated.estimated_vsize,
                    receiver_input_count: u32::try_from(validated.receiver_input_count)
                        .unwrap_or(u32::MAX),
                    sender_net_spend_sat: pj::sender_net_spend(sender_in, &outputs),
                    outputs,
                };
                let id = pj::store_session(pj::PayjoinSession {
                    owner: self.payjoin_owner_tag(),
                    proposal: validated,
                    fee_rate_sat_vb: fee_rate_sat_per_vb,
                    allow_high_fee,
                })?;
                Ok(pj::PayjoinNegotiation {
                    session_id: Some(id),
                    ..summary
                })
            }
            Err(e) => {
                let outputs = pj::summarize_outputs(&original, &payment_spk, network, &is_mine);
                Ok(pj::PayjoinNegotiation {
                    session_id: None,
                    failure_reason: Some(e.to_string()),
                    original_psbt: original_b64,
                    original_fee_sat: original_fee,
                    proposal_fee_sat: original_fee,
                    sender_fee_contribution_sat: 0,
                    vsize: original_signed.extract_tx_unchecked_fee_rate().vsize() as u64,
                    receiver_input_count: 0,
                    sender_net_spend_sat: pj::sender_net_spend(sender_in, &outputs),
                    outputs,
                })
            }
        }
    }

    /// `PayJoin` sender, step 2 of 2: sign the reviewed proposal parked
    /// under `session_id` and broadcast it through `esplora_url`
    /// (optionally via `proxy_url`). The session is consumed.
    ///
    /// Only the sender's original input indices are signed; receiver
    /// inputs must come back byte-for-byte untouched (see
    /// `sign_payjoin_proposal`). The real fee rate is re-checked
    /// against `minfeerate` before broadcast.
    ///
    /// # Errors
    ///
    /// - [`OnchainError::TxBuild`] for an unknown / used / foreign
    ///   session or a fee rate below the original's.
    /// - [`OnchainError::Sign`] if signing fails or touched a
    ///   receiver input.
    /// - [`OnchainError::Broadcast`] from Esplora.
    pub fn payjoin_sign_and_broadcast(
        &mut self,
        session_id: &str,
        esplora_url: &str,
        proxy_url: Option<&str>,
    ) -> Result<Txid, OnchainError> {
        let signed = self.payjoin_sign_checked(session_id)?;
        self.broadcast_tx(esplora_url, &signed.to_string(), proxy_url)
    }

    /// Everything in [`Self::payjoin_sign_and_broadcast`] before the
    /// network: consume the session, sign the sender inputs only and
    /// re-check the signed fee rate.
    fn payjoin_sign_checked(&mut self, session_id: &str) -> Result<Psbt, OnchainError> {
        let session = crate::payjoin::take_session(session_id, &self.payjoin_owner_tag())
            .ok_or_else(|| {
                OnchainError::TxBuild("PayJoin session unknown, expired or already used".into())
            })?;
        // Re-check the absolute limit on the proposal itself (its fee is
        // original + the sender's capped contribution).
        self.check_absolute_fee(&session.proposal.psbt, session.allow_high_fee)?;
        let signed = self.sign_payjoin_proposal(&session.proposal)?;
        let vsize = signed.clone().extract_tx_unchecked_fee_rate().vsize() as u64;
        let min_fee = session
            .fee_rate_sat_vb
            .checked_mul(vsize)
            .ok_or_else(|| OnchainError::TxBuild("fee rate overflow".into()))?;
        if session.proposal.proposal_fee < min_fee {
            return Err(OnchainError::TxBuild(format!(
                "PayJoin proposal rejected: signed fee rate below {} sat/vB",
                session.fee_rate_sat_vb
            )));
        }
        Ok(signed)
    }

    /// Drop a parked `PayJoin` session (user cancelled the review).
    /// Returns whether a session with that id existed.
    pub fn payjoin_discard(&self, session_id: &str) -> bool {
        crate::payjoin::discard_session(session_id)
    }

    /// Owner tag binding a parked session to this wallet.
    fn payjoin_owner_tag(&self) -> String {
        self.wallet
            .public_descriptor(KeychainKind::External)
            .to_string()
    }

    /// Sign ONLY the sender's inputs of a validated proposal.
    ///
    /// Receiver inputs are finalized (checked by `validate_proposal`),
    /// so BDK's signer skips them; as defence in depth we still require
    /// every non-sender input to be identical before and after signing.
    /// If BDK ever signed a coin the receiver slipped in (an extra UTXO
    /// of ours disguised as a receiver input), this aborts.
    fn sign_payjoin_proposal(
        &self,
        validated: &crate::payjoin::ValidatedProposal,
    ) -> Result<Psbt, OnchainError> {
        let mut psbt = validated.psbt.clone();
        self.wallet
            .sign(&mut psbt, payjoin_sign_options())
            .map_err(|e| OnchainError::Sign(e.to_string()))?;
        if psbt.unsigned_tx != validated.psbt.unsigned_tx {
            return Err(OnchainError::Sign(
                "PayJoin transaction changed while signing".into(),
            ));
        }
        for (i, (after, before)) in psbt.inputs.iter().zip(&validated.psbt.inputs).enumerate() {
            if validated.sender_input_indices.contains(&i) {
                if after
                    .final_script_witness
                    .as_ref()
                    .is_none_or(bitcoin::Witness::is_empty)
                {
                    return Err(OnchainError::Sign(format!(
                        "PayJoin sender input #{i} could not be signed"
                    )));
                }
            } else if after != before {
                return Err(OnchainError::Sign(format!(
                    "signer touched non-sender input #{i} — refusing to broadcast"
                )));
            }
        }
        Ok(psbt)
    }

    /// Build a Child-Pays-For-Parent (CPFP) child PSBT that consumes
    /// **only the wallet outputs of `parent_txid`** and drains them to
    /// a fresh **internal (change)** address, sized so that the PACKAGE
    /// (parent + child) reaches `fee_rate_sat_per_vb`:
    ///
    /// `child_fee = target * (parent_vsize + child_vsize) - parent_fee`
    /// (never below `child_vsize * 1 sat/vB`).
    ///
    /// Two practical use cases:
    /// - **Receiver speed-up**: a customer / faucet sent us coins and
    ///   their parent tx is stuck unconfirmed.
    /// - **Sender change rescue**: we sent a tx with a stale fee, the
    ///   change UTXO is stuck.
    ///
    /// The returned [`CpfpPlan`] carries the package numbers the review
    /// screen shows next to the decoded child PSBT. The package includes
    /// **every unconfirmed ancestor** of the parent (grandparents and
    /// beyond), so `parent_fee`/`parent_vsize` in the formula above are
    /// the sums over the parent plus all its unconfirmed ancestors. If
    /// any unconfirmed ancestor's fee cannot be computed from local data
    /// (or the ancestor is not in the wallet's tx graph, so its
    /// confirmation state is unknown) the build is **refused** rather
    /// than sized from a partial package: an offline wallet cannot fetch
    /// the missing data, and ignoring a low-fee ancestor would leave the
    /// package below the target rate.
    ///
    /// # Errors
    ///
    /// - [`OnchainError::TxBuild`] when `parent_txid` does not parse, is
    ///   unknown or **already confirmed**, its fee (or the fee of any
    ///   unconfirmed ancestor) cannot be computed from local data, no
    ///   wallet UTXOs originate from it, or BDK
    ///   refuses the build (outputs too small to pay the package fee).
    /// - [`OnchainError::InvalidFeeRate`] for out-of-range fee rates.
    /// - [`OnchainError::FeeTooHigh`] see [`Self::build_send_tx`].
    pub fn build_cpfp_psbt(
        &mut self,
        parent_txid: &str,
        fee_rate_sat_per_vb: u64,
        allow_high_fee: bool,
    ) -> Result<CpfpPlan, OnchainError> {
        let parent: Txid = parent_txid
            .parse()
            .map_err(|e: bitcoin::hex::HexToArrayError| {
                OnchainError::TxBuild(format!("invalid parent txid {parent_txid:?}: {e}"))
            })?;
        send_policy::validated_fee_rate(fee_rate_sat_per_vb, self.network)?;

        let (parent_fee_sat, parent_vsize) = {
            let wtx = self.wallet.get_tx(parent).ok_or_else(|| {
                OnchainError::TxBuild(format!("parent {parent_txid} is not a wallet transaction"))
            })?;
            if wtx.chain_position.is_confirmed() {
                return Err(OnchainError::TxBuild(format!(
                    "parent {parent_txid} is already confirmed; CPFP is not needed"
                )));
            }
            let tx = std::sync::Arc::clone(&wtx.tx_node.tx);
            let fee = self.wallet.calculate_fee(&tx).map_err(|e| {
                OnchainError::TxBuild(format!(
                    "parent fee unknown ({e}); cannot size the CPFP package"
                ))
            })?;
            (fee.to_sat(), tx.weight().to_vbytes_ceil())
        };
        let ancestors = self.unconfirmed_ancestors(parent)?;
        let ancestors_fee_sat = ancestors.fee_sat;
        let ancestors_vsize = ancestors.vsize;
        let package_fee_sat = parent_fee_sat.saturating_add(ancestors_fee_sat);
        let package_vsize = parent_vsize.saturating_add(ancestors_vsize);

        // Never spend a BIP-329 frozen output, even to rescue its parent:
        // a frozen coin may be an unsolicited dust deposit whose spend
        // would link it to the rest of the wallet.
        let frozen: std::collections::HashSet<OutPoint> = labels::frozen_outpoints(&self.db_conn)?
            .into_iter()
            .collect();
        let cpfp_outpoints: Vec<OutPoint> = self
            .wallet
            .list_unspent()
            .filter(|u| u.outpoint.txid == parent && !frozen.contains(&u.outpoint))
            .map(|u| u.outpoint)
            .collect();
        if cpfp_outpoints.is_empty() {
            return Err(OnchainError::TxBuild(format!(
                "no spendable (non-frozen) wallet outputs from parent {parent_txid}",
            )));
        }

        // `next_unused_address`, not `reveal_next_address`: a preview or a
        // retry whose child was never broadcast reuses the same change
        // address instead of burning one internal index per build (gap
        // limit on restore).
        let drain_spk = self
            .wallet
            .next_unused_address(KeychainKind::Internal)
            .address
            .script_pubkey();

        // Pass 1: zero-fee probe — the child's shape (inputs + one drain
        // output) and therefore its size do not depend on the fee.
        let probe = self.build_cpfp_child(&cpfp_outpoints, &drain_spk, bitcoin::Amount::ZERO)?;
        let child_vsize = send_policy::estimate_signed_vsize(&probe);
        let min_relay = u64::from(!matches!(self.network, BhodlNetwork::Regtest));
        let child_fee_sat = send_policy::cpfp_child_fee(
            fee_rate_sat_per_vb,
            package_fee_sat,
            package_vsize,
            child_vsize,
            min_relay,
        )?;
        // Pass 2: the real child at the package-derived absolute fee.
        let psbt = self.build_cpfp_child(
            &cpfp_outpoints,
            &drain_spk,
            bitcoin::Amount::from_sat(child_fee_sat),
        )?;

        self.persist()?;
        let psbt = self.finish_build(&psbt, allow_high_fee)?;
        Ok(CpfpPlan {
            psbt,
            parent_txid: parent.to_string(),
            parent_fee_sat,
            parent_vsize,
            ancestor_count: ancestors.count,
            ancestors_fee_sat,
            ancestors_vsize,
            child_fee_sat,
            child_vsize,
            target_fee_rate_sat_vb: fee_rate_sat_per_vb,
        })
    }

    /// Walk the unconfirmed ancestor chain of `txid` (excluding `txid`
    /// itself) through the wallet's tx graph and total their fees and
    /// vsizes. Confirmed ancestors end the walk on that branch.
    ///
    /// Fails closed: an ancestor that is not in the graph (unknown
    /// confirmation state) or whose fee cannot be computed makes the
    /// package size unknowable offline, so the CPFP is refused instead
    /// of under-paying.
    fn unconfirmed_ancestors(&self, txid: Txid) -> Result<AncestorTotals, OnchainError> {
        let mut seen: std::collections::HashSet<Txid> = std::collections::HashSet::from([txid]);
        let mut queue = vec![txid];
        let mut totals = AncestorTotals::default();
        while let Some(current) = queue.pop() {
            let tx = self
                .wallet
                .get_tx(current)
                .map(|w| std::sync::Arc::clone(&w.tx_node.tx))
                .ok_or_else(|| {
                    OnchainError::TxBuild(format!("transaction {current} is not in the wallet"))
                })?;
            for input in &tx.input {
                let prev = input.previous_output.txid;
                if !seen.insert(prev) {
                    continue;
                }
                let wtx = self.wallet.get_tx(prev).ok_or_else(|| {
                    OnchainError::TxBuild(format!(
                        "ancestor {prev} is not known to the wallet; its confirmation                          state and fee are unknown, cannot size the CPFP package"
                    ))
                })?;
                if wtx.chain_position.is_confirmed() {
                    continue;
                }
                let anc = std::sync::Arc::clone(&wtx.tx_node.tx);
                let fee = self.wallet.calculate_fee(&anc).map_err(|e| {
                    OnchainError::TxBuild(format!(
                        "ancestor {prev} fee unknown ({e}); cannot size the CPFP package"
                    ))
                })?;
                totals.count += 1;
                totals.fee_sat = totals.fee_sat.saturating_add(fee.to_sat());
                totals.vsize = totals.vsize.saturating_add(anc.weight().to_vbytes_ceil());
                queue.push(prev);
            }
        }
        Ok(totals)
    }

    /// Build the CPFP child spending exactly `outpoints` into `drain_spk`
    /// with an absolute `fee`.
    fn build_cpfp_child(
        &mut self,
        outpoints: &[OutPoint],
        drain_spk: &bitcoin::ScriptBuf,
        fee: bitcoin::Amount,
    ) -> Result<Psbt, OnchainError> {
        let mut builder = self.wallet.build_tx();
        builder
            .manually_selected_only()
            .add_utxos(outpoints)
            .map_err(|e| OnchainError::TxBuild(e.to_string()))?
            .drain_wallet()
            .drain_to(drain_spk.clone())
            .fee_absolute(fee);
        builder
            .finish()
            .map_err(|e| OnchainError::TxBuild(e.to_string()))
    }

    /// Build a replacement PSBT that pays the same outputs as `txid` but
    /// at a higher fee rate (BIP-125 RBF, "fee bump").
    ///
    /// `txid` must reference a transaction the wallet itself broadcast,
    /// is still in the mempool (no confirmations), and was built with at
    /// least one replaceable input (BDK 3.0 marks newly-built PSBTs as
    /// replaceable by default — `build_send_tx` and friends inherit
    /// that). `new_fee_rate_sat_per_vb` must strictly exceed the
    /// previous fee rate or BDK rejects the bump as a no-op. If BDK has
    /// to add inputs, only confirmed, non-frozen coins are considered.
    ///
    /// The result is NOT signed: the caller reviews it via
    /// [`Self::decode_psbt`] and then signs + broadcasts it like any
    /// other PSBT.
    ///
    /// # Errors
    ///
    /// - [`OnchainError::TxBuild`] if BDK refuses (already confirmed,
    ///   not RBF-eligible, fee rate too low, insufficient funds for
    ///   the higher fee, etc.).
    /// - [`OnchainError::InvalidFeeRate`] for out-of-range fee rates.
    /// - [`OnchainError::FeeTooHigh`] see [`Self::build_send_tx`].
    pub fn build_fee_bump_psbt(
        &mut self,
        txid: &str,
        new_fee_rate_sat_per_vb: u64,
        allow_high_fee: bool,
    ) -> Result<String, OnchainError> {
        let parsed_txid: Txid = txid
            .parse()
            .map_err(|e: bitcoin::hex::HexToArrayError| OnchainError::TxBuild(e.to_string()))?;

        let fee_rate = send_policy::validated_fee_rate(new_fee_rate_sat_per_vb, self.network)?;
        let frozen = labels::frozen_outpoints(&self.db_conn)?;

        let mut builder = self
            .wallet
            .build_fee_bump(parsed_txid)
            .map_err(|e| OnchainError::TxBuild(e.to_string()))?;
        builder.fee_rate(fee_rate).unspendable(frozen);
        let psbt = builder
            .finish()
            .map_err(|e| OnchainError::TxBuild(e.to_string()))?;

        self.persist()?;
        self.finish_build(&psbt, allow_high_fee)
    }

    /// Convenience wrapper that accepts the outpoints as `txid:vout`
    /// strings. Used by the FFI surface so callers do not have to drag
    /// `bitcoin::OutPoint` across the FFI boundary.
    ///
    /// # Errors
    ///
    /// Returns [`OnchainError::TxBuild`] if any string fails to parse
    /// as an outpoint, then defers to [`Self::build_send_tx_with_utxos`].
    pub fn build_send_tx_with_outpoint_strings(
        &mut self,
        to_address: &str,
        amount: Amount,
        fee_rate_sat_per_vb: u64,
        outpoints: &[&str],
        allow_high_fee: bool,
    ) -> Result<String, OnchainError> {
        let parsed = parse_outpoints(outpoints)?;
        self.build_send_tx_with_utxos(
            to_address,
            amount,
            fee_rate_sat_per_vb,
            &parsed,
            allow_high_fee,
        )
    }

    /// Build an unsigned PSBT spending **only** the explicitly-selected
    /// UTXOs to **multiple** recipients in one transaction.
    ///
    /// This is the union of [`Self::build_send_tx_batch`] (multi-recipient,
    /// auto-selected inputs) and [`Self::build_send_tx_with_utxos`]
    /// (single recipient, manually-selected inputs). Use it when both
    /// dimensions matter at once: e.g. paying three vendors out of a
    /// specific UTXO without leaking the rest of the wallet's coins,
    /// or implementing a privacy-preserving batched payout.
    ///
    /// `manually_selected_only` is set on the BDK builder, so the
    /// supplied `utxos` are the *exclusive* input set; if the total
    /// of all recipient outputs plus the resulting fee exceeds the
    /// sum of those UTXOs, BDK refuses with `InsufficientFunds`. No
    /// silent fallback to other coins — that would defeat the whole
    /// point of coin control. Frozen outputs are refused.
    ///
    /// # Errors
    ///
    /// - [`OnchainError::TxBuild`] when `recipients` or `utxos` is empty.
    /// - [`OnchainError::InvalidAddress`] if any recipient address is
    ///   malformed or belongs to a different network.
    /// - [`OnchainError::InvalidFeeRate`] for out-of-range fee rates.
    /// - [`OnchainError::TxBuild`] when BDK rejects the build (insufficient
    ///   funds in the selected set, dust output, unknown / frozen
    ///   outpoint, …).
    /// - [`OnchainError::FeeTooHigh`] see [`Self::build_send_tx`].
    pub fn build_send_tx_batch_with_utxos(
        &mut self,
        recipients: &[(String, Amount)],
        fee_rate_sat_per_vb: u64,
        utxos: &[OutPoint],
        allow_high_fee: bool,
    ) -> Result<String, OnchainError> {
        if recipients.is_empty() {
            return Err(OnchainError::TxBuild("recipients list is empty".into()));
        }
        if utxos.is_empty() {
            return Err(OnchainError::TxBuild(
                "utxos selection is empty; use build_send_tx_batch for automatic selection".into(),
            ));
        }
        let fee_rate = send_policy::validated_fee_rate(fee_rate_sat_per_vb, self.network)?;
        let outputs = self.parse_recipients(recipients)?;
        self.reject_frozen(utxos)?;

        let mut builder = self.wallet.build_tx();
        builder
            .set_recipients(outputs)
            .fee_rate(fee_rate)
            .manually_selected_only()
            .add_utxos(utxos)
            .map_err(|e| OnchainError::TxBuild(e.to_string()))?;
        let psbt = builder
            .finish()
            .map_err(|e| OnchainError::TxBuild(e.to_string()))?;
        self.persist()?;
        self.finish_build(&psbt, allow_high_fee)
    }

    /// Convenience wrapper for [`Self::build_send_tx_batch_with_utxos`]
    /// that takes outpoints as `txid:vout` strings, mirroring
    /// [`Self::build_send_tx_with_outpoint_strings`]. Used by the FFI
    /// so callers do not need to construct `bitcoin::OutPoint` values.
    ///
    /// # Errors
    ///
    /// Returns [`OnchainError::TxBuild`] if any string fails to parse
    /// as an outpoint, then defers to [`Self::build_send_tx_batch_with_utxos`].
    pub fn build_send_tx_batch_with_outpoint_strings(
        &mut self,
        recipients: &[(String, Amount)],
        fee_rate_sat_per_vb: u64,
        outpoints: &[&str],
        allow_high_fee: bool,
    ) -> Result<String, OnchainError> {
        let parsed = parse_outpoints(outpoints)?;
        self.build_send_tx_batch_with_utxos(
            recipients,
            fee_rate_sat_per_vb,
            &parsed,
            allow_high_fee,
        )
    }

    // -----------------------------------------------------------------
    // Shared send-path guard rails
    // -----------------------------------------------------------------

    /// Parse `address` and require it to belong to this account's network.
    fn parse_recipient(&self, address: &str) -> Result<Address, OnchainError> {
        let parsed: Address<bitcoin::address::NetworkUnchecked> =
            address.parse().map_err(|e: bitcoin::address::ParseError| {
                OnchainError::InvalidAddress(e.to_string())
            })?;
        parsed
            .require_network(map_network(self.network))
            .map_err(|e| OnchainError::InvalidAddress(e.to_string()))
    }

    /// Parse every `(address, amount)` pair into BDK recipients.
    fn parse_recipients(
        &self,
        recipients: &[(String, Amount)],
    ) -> Result<Vec<(bitcoin::ScriptBuf, bitcoin::Amount)>, OnchainError> {
        recipients
            .iter()
            .map(|(addr, amount)| {
                Ok((
                    self.parse_recipient(addr)?.script_pubkey(),
                    bitcoin::Amount::from_sat(amount.to_sat()),
                ))
            })
            .collect()
    }

    /// Post-build guard shared by every builder: refuse a fee above the
    /// absolute limit unless the user explicitly confirmed it, then
    /// serialise the PSBT.
    fn finish_build(&self, psbt: &Psbt, allow_high_fee: bool) -> Result<String, OnchainError> {
        self.check_absolute_fee(psbt, allow_high_fee)?;
        Ok(psbt.to_string())
    }

    /// Refuse `psbt` when its fee exceeds the absolute limit for the
    /// amount it sends to third parties, unless `allow_high_fee`.
    fn check_absolute_fee(&self, psbt: &Psbt, allow_high_fee: bool) -> Result<(), OnchainError> {
        let fee_sat = psbt
            .fee()
            .map_err(|e| OnchainError::TxBuild(format!("fee compute: {e}")))?
            .to_sat();
        let sent_external_sat = psbt
            .unsigned_tx
            .output
            .iter()
            .filter(|o| !self.wallet.is_mine(o.script_pubkey.clone()))
            .try_fold(0_u64, |acc, o| acc.checked_add(o.value.to_sat()))
            .ok_or_else(|| OnchainError::TxBuild("output sum overflow".into()))?;
        send_policy::enforce_absolute_fee_limit(fee_sat, sent_external_sat, allow_high_fee)
    }

    /// Outpoints automatic coin selection must never pick: BIP-329
    /// frozen outputs (`spendable: false`) and unconfirmed outputs whose
    /// ancestry is not entirely our own (a third party can double-spend
    /// them away, invalidating our payment).
    fn auto_selection_exclusions(&self) -> Result<Vec<OutPoint>, OnchainError> {
        let mut excluded = labels::frozen_outpoints(&self.db_conn)?;
        excluded.extend(self.untrusted_unconfirmed_outpoints());
        Ok(excluded)
    }

    /// Unspent unconfirmed outputs whose transaction is not "ours all the
    /// way down" (see [`Self::is_trusted_tx`]).
    fn untrusted_unconfirmed_outpoints(&self) -> Vec<OutPoint> {
        let mut memo = HashMap::new();
        self.wallet
            .list_unspent()
            .filter(|u| !u.chain_position.is_confirmed())
            .filter(|u| !self.is_trusted_tx(u.outpoint.txid, &mut memo, 0))
            .map(|u| u.outpoint)
            .collect()
    }

    /// A transaction is trusted when it is confirmed, or when every input
    /// spends one of our own outputs from a trusted transaction. Depth is
    /// bounded by the mempool ancestor limit (25); deeper chains are
    /// treated as untrusted.
    fn is_trusted_tx(&self, txid: Txid, memo: &mut HashMap<Txid, bool>, depth: u32) -> bool {
        const MAX_UNCONFIRMED_DEPTH: u32 = 25;
        if let Some(known) = memo.get(&txid) {
            return *known;
        }
        let Some(wtx) = self.wallet.get_tx(txid) else {
            return false;
        };
        if wtx.chain_position.is_confirmed() {
            memo.insert(txid, true);
            return true;
        }
        if depth >= MAX_UNCONFIRMED_DEPTH {
            return false;
        }
        let tx = std::sync::Arc::clone(&wtx.tx_node.tx);
        let trusted = !tx.input.is_empty()
            && tx.input.iter().all(|txin| {
                let prev = txin.previous_output;
                self.wallet
                    .tx_graph()
                    .get_txout(prev)
                    .is_some_and(|o| self.wallet.is_mine(o.script_pubkey.clone()))
                    && self.is_trusted_tx(prev.txid, memo, depth + 1)
            });
        memo.insert(txid, trusted);
        trusted
    }

    /// Manual coin control may pick unconfirmed coins (flagged in the
    /// review) but never a BIP-329 frozen one.
    fn reject_frozen(&self, utxos: &[OutPoint]) -> Result<(), OnchainError> {
        let frozen = labels::frozen_outpoints(&self.db_conn)?;
        if let Some(op) = utxos.iter().find(|op| frozen.contains(op)) {
            return Err(OnchainError::TxBuild(format!(
                "UTXO {op} is frozen (BIP-329 spendable:false); unfreeze it before spending"
            )));
        }
        Ok(())
    }

    /// Apply a just-broadcast transaction to the local view so the next
    /// build does not reuse its inputs (or RBF-replace it by accident).
    fn record_broadcast(&mut self, tx: bitcoin::Transaction) {
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map_or(0, |d| d.as_secs());
        self.wallet.apply_unconfirmed_txs([(tx, now)]);
        // Best effort: the transaction IS on the network. Reporting a
        // persist hiccup as a failed broadcast would invite a retry /
        // double payment; the next sync re-learns the tx anyway.
        let _ = self.persist();
    }
}

/// Parse `txid:vout` strings into outpoints.
fn parse_outpoints(outpoints: &[&str]) -> Result<Vec<OutPoint>, OnchainError> {
    outpoints
        .iter()
        .map(|s| {
            s.parse::<OutPoint>()
                .map_err(|e| OnchainError::TxBuild(format!("invalid outpoint {s:?}: {e}")))
        })
        .collect()
}

/// Maximum byte length of an address-book label.
const MAX_CONTACT_LABEL_BYTES: usize = 256;

/// Trim + bound an address-book label.
fn validated_contact_label(label: &str) -> Result<&str, OnchainError> {
    let label = label.trim();
    if label.is_empty() || label.len() > MAX_CONTACT_LABEL_BYTES {
        return Err(OnchainError::InvalidArgument(format!(
            "contact label must be 1..={MAX_CONTACT_LABEL_BYTES} bytes"
        )));
    }
    Ok(label)
}

/// Create the BIP329 labels table on the supplied connection if it does
/// not yet exist. Called by every account constructor.
fn ensure_labels_table(conn: &Connection) -> Result<(), OnchainError> {
    conn.execute_batch(CREATE_LABELS_TABLE_SQL)
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    conn.execute_batch(labels::CREATE_LABEL_COPIES_TABLE_SQL)
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    // Phase 3.4 — add the BIP-329-extension `category` column on
    // existing v1 DBs. Idempotent: the pragma probe is cheap and
    // returns 0 only on the first run after the upgrade. BIP-329
    // §3 says implementations MUST ignore unknown fields, so the
    // round-trip through Sparrow / Liana is safe.
    let has_category: i64 = conn
        .query_row(
            "SELECT COUNT(*) FROM pragma_table_info('bhodl_labels') \
             WHERE name = 'category'",
            [],
            |r| r.get(0),
        )
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    if has_category == 0 {
        conn.execute("ALTER TABLE bhodl_labels ADD COLUMN category TEXT", [])
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
    }
    Ok(())
}

/// Create the local-only contacts (address book) table. Called by every
/// account constructor; the table holds `(label, address)` pairs the
/// user has saved for re-use in the `SendScreen`.
fn ensure_contacts_table(conn: &Connection) -> Result<(), OnchainError> {
    conn.execute_batch(CREATE_CONTACTS_TABLE_SQL)
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    Ok(())
}

/// Map a BDK load failure to [`OnchainError`], turning the one mismatch
/// users can actually hit after the testnet4 fix into an actionable
/// message.
///
/// Builds before the fix mapped `Testnet4` onto rust-bitcoin's
/// `Network::Testnet` (testnet3 params + genesis), so a testnet4 DB written
/// by them is persisted as "testnet". BDK refuses it with a network
/// mismatch; the chain state in it is unusable on testnet4 anyway.
pub(crate) fn map_wallet_load_error<E: std::fmt::Display>(
    e: &bdk_wallet::LoadWithPersistError<E>,
) -> OnchainError {
    if matches!(
        e,
        bdk_wallet::LoadWithPersistError::InvalidChangeSet(bdk_wallet::LoadError::Mismatch(
            bdk_wallet::LoadMismatch::Network {
                loaded: BtcNetwork::Testnet,
                expected: BtcNetwork::Testnet4,
            }
        ))
    ) {
        return OnchainError::Storage(
            "this testnet4 wallet was created by an older BHODL build that stored it \
             with testnet3 parameters; it cannot be opened on testnet4. Restore the \
             wallet from its recovery phrase (a fresh re-sync recreates its history)"
                .into(),
        );
    }
    OnchainError::Storage(e.to_string())
}

/// Signing options for the `PayJoin` paths: same as [`OnchainAccount::sign_psbt`]
/// (`trust_witness_utxo` is safe for BIP-341 key-path spends, which
/// commit to every prevout amount). BDK's default refuses non-default
/// sighash types, and our inputs' `sighash_type` is reset from the
/// original by `validate_proposal`.
fn payjoin_sign_options() -> SignOptions {
    SignOptions {
        trust_witness_utxo: true,
        ..SignOptions::default()
    }
}

pub(crate) fn map_network(n: BhodlNetwork) -> BtcNetwork {
    match n {
        BhodlNetwork::Mainnet => BtcNetwork::Bitcoin,
        // Must be the real testnet4 params (genesis, magic). Mapping to
        // `Testnet` (testnet3) made BDK anchor chain state to the wrong
        // genesis; legacy DBs are handled by `map_wallet_load_error` /
        // the watch-wallet auto-rebuild.
        BhodlNetwork::Testnet4 => BtcNetwork::Testnet4,
        BhodlNetwork::Signet => BtcNetwork::Signet,
        BhodlNetwork::Regtest => BtcNetwork::Regtest,
        _ => unreachable!(
            "bhodl-core gained a Network variant without updating bhodl-wallet-onchain"
        ),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn esplora_timeout_is_larger_only_when_a_proxy_is_configured() {
        assert_eq!(esplora_timeout_secs(false), ESPLORA_TIMEOUT_SECS);
        assert_eq!(esplora_timeout_secs(true), ESPLORA_PROXY_TIMEOUT_SECS);
        // Tor budget: at least 60 s, and it must outlast the bridge's
        // SOCKS connect so a dead proxy surfaces as the bridge's 502.
        const {
            assert!(ESPLORA_PROXY_TIMEOUT_SECS >= 60);
            assert!(ESPLORA_PROXY_TIMEOUT_SECS > crate::tor::SOCKS_CONNECT_TIMEOUT.as_secs());
        }
    }

    /// Canonical BIP86 mainnet test vector.
    /// Source: <https://github.com/bitcoin/bips/blob/master/bip-0086.mediawiki>
    const BIP86_MNEMONIC: &str = "abandon abandon abandon abandon abandon abandon \
                                  abandon abandon abandon abandon abandon about";

    #[test]
    fn bip86_mainnet_first_address_matches_test_vector() {
        let account =
            OnchainAccount::from_mnemonic(BhodlNetwork::Mainnet, BIP86_MNEMONIC, "").unwrap();
        let address = account.peek_taproot_address(0).unwrap();
        assert_eq!(
            address.to_string(),
            "bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr"
        );
    }

    #[test]
    fn bip86_mainnet_second_address_matches_test_vector() {
        let account =
            OnchainAccount::from_mnemonic(BhodlNetwork::Mainnet, BIP86_MNEMONIC, "").unwrap();
        let address = account.peek_taproot_address(1).unwrap();
        assert_eq!(
            address.to_string(),
            "bc1p4qhjn9zdvkux4e44uhx8tc55attvtyu358kutcqkudyccelu0was9fqzwh"
        );
    }

    #[test]
    fn bip86_mainnet_first_change_path_does_not_collide_with_external() {
        // Sanity: the external chain (change=0) and the internal chain
        // (change=1) must produce different addresses for the same index.
        // We access the private `xpriv` and `secp` fields directly because
        // this test lives inside the same module.
        let account =
            OnchainAccount::from_mnemonic(BhodlNetwork::Mainnet, BIP86_MNEMONIC, "").unwrap();
        let external = account.peek_taproot_address(0).unwrap();

        let internal_path = DerivationPath::from_str("m/86'/0'/0'/1/0").unwrap();
        let xpriv = account.xpriv.expect("from_mnemonic always sets xpriv");
        let derived = xpriv.derive_priv(&account.secp, &internal_path).unwrap();
        let internal_key = derived.private_key.x_only_public_key(&account.secp).0;
        let internal_addr = Address::p2tr(&account.secp, internal_key, None, BtcNetwork::Bitcoin);

        assert_ne!(external, internal_addr);
    }

    #[test]
    fn build_send_tx_rejects_wrong_network_address() {
        // Build a Mainnet account, but pass a Signet address: should fail
        // before any tx-build work happens.
        let mut account =
            OnchainAccount::from_mnemonic(BhodlNetwork::Mainnet, BIP86_MNEMONIC, "").unwrap();
        // Valid signet bech32m address (well-known faucet style).
        let signet_addr = "tb1pqqqqp6kmt2cgnfxgcrr3hr0gd2zhh84sjxa9z2v07c0eh4mchssqgazm6t";
        let result = account.build_send_tx(signet_addr, Amount::from_sat(10_000), 1, false);
        assert!(
            matches!(result, Err(OnchainError::InvalidAddress(_))),
            "wrong-network address must be rejected, got {result:?}"
        );
    }

    #[test]
    fn build_send_tx_rejects_garbage_address() {
        let mut account =
            OnchainAccount::from_mnemonic(BhodlNetwork::Mainnet, BIP86_MNEMONIC, "").unwrap();
        let result =
            account.build_send_tx("not-a-bitcoin-address", Amount::from_sat(10_000), 1, false);
        assert!(matches!(result, Err(OnchainError::InvalidAddress(_))));
    }

    #[test]
    fn label_round_trip_through_account_api() {
        let account =
            OnchainAccount::from_mnemonic(BhodlNetwork::Mainnet, BIP86_MNEMONIC, "").unwrap();

        // Empty initially.
        assert!(account.list_labels().unwrap().is_empty());

        // Add two labels.
        account
            .add_label(&Label {
                kind: LabelKind::Tx,
                target: "abcd".into(),
                text: "Salary".into(),
                spendable: None,
                category: None,
            })
            .unwrap();
        account
            .add_label(&Label {
                kind: LabelKind::Output,
                target: "abcd:0".into(),
                text: "DCA".into(),
                spendable: Some(true),
                category: None,
            })
            .unwrap();
        assert_eq!(account.list_labels().unwrap().len(), 2);

        // Lookup by key.
        let txl = account.get_label(LabelKind::Tx, "abcd").unwrap().unwrap();
        assert_eq!(txl.text, "Salary");

        // Overwrite.
        account
            .add_label(&Label {
                kind: LabelKind::Tx,
                target: "abcd".into(),
                text: "Bonus".into(),
                spendable: None,
                category: None,
            })
            .unwrap();
        assert_eq!(
            account
                .get_label(LabelKind::Tx, "abcd")
                .unwrap()
                .unwrap()
                .text,
            "Bonus"
        );
        assert_eq!(account.list_labels().unwrap().len(), 2);

        // JSONL round-trip into a fresh account.
        let exported = account.export_labels_jsonl().unwrap();
        let other =
            OnchainAccount::from_mnemonic(BhodlNetwork::Mainnet, BIP86_MNEMONIC, "").unwrap();
        let imported = other.import_labels_jsonl(&exported).unwrap();
        assert_eq!(imported, 2);
        assert_eq!(other.list_labels().unwrap().len(), 2);

        // Remove.
        assert!(account.remove_label(LabelKind::Tx, "abcd").unwrap());
        assert!(!account.remove_label(LabelKind::Tx, "abcd").unwrap());
        assert_eq!(account.list_labels().unwrap().len(), 1);
    }

    #[test]
    fn labels_persist_across_close_and_reopen() {
        let tmp = tempfile::tempdir().unwrap();
        let db = tmp.path().join("wallet.sqlite");

        {
            let acc = OnchainAccount::create_or_load_persistent(
                BhodlNetwork::Mainnet,
                BIP86_MNEMONIC,
                "",
                &db,
                &TEST_KEY,
            )
            .unwrap();
            acc.add_label(&Label {
                kind: LabelKind::Addr,
                target: "bc1pxxx".into(),
                text: "Cold storage".into(),
                spendable: None,
                category: None,
            })
            .unwrap();
        }

        let acc2 = OnchainAccount::create_or_load_persistent(
            BhodlNetwork::Mainnet,
            BIP86_MNEMONIC,
            "",
            &db,
            &TEST_KEY,
        )
        .unwrap();
        let stored = acc2.get_label(LabelKind::Addr, "bc1pxxx").unwrap().unwrap();
        assert_eq!(stored.text, "Cold storage");
    }

    #[test]
    fn export_descriptors_redacts_xpriv() {
        let account =
            OnchainAccount::from_mnemonic(BhodlNetwork::Mainnet, BIP86_MNEMONIC, "").unwrap();
        let backup = account.export_descriptors();

        // The mainnet master xprv prefix is `xprv`. The backup must not
        // contain anything starting with that — neither in the external
        // nor in the internal descriptor.
        assert!(
            !backup.external.contains("xprv"),
            "external descriptor leaks xprv: {}",
            backup.external
        );
        assert!(
            !backup.internal.contains("xprv"),
            "internal descriptor leaks xprv: {}",
            backup.internal
        );
        // And it must contain the corresponding public key form.
        assert!(
            backup.external.contains("xpub"),
            "external descriptor missing xpub: {}",
            backup.external
        );
        assert!(
            backup.internal.contains("xpub"),
            "internal descriptor missing xpub: {}",
            backup.internal
        );
    }

    #[test]
    fn export_descriptors_external_and_internal_differ() {
        let account =
            OnchainAccount::from_mnemonic(BhodlNetwork::Mainnet, BIP86_MNEMONIC, "").unwrap();
        let backup = account.export_descriptors();
        assert_ne!(
            backup.external, backup.internal,
            "external and internal descriptors must differ — different keychains"
        );
        // Both must share the same key-origin prefix `[fp/86'/0'/0']` and
        // the same xpub; they only differ on the trailing chain index
        // (`/0/*` vs `/1/*`) and the per-descriptor checksum suffix.
        assert!(backup.external.contains("/86'/0'/0']"));
        assert!(backup.internal.contains("/86'/0'/0']"));
        assert!(backup.external.contains("/0/*"));
        assert!(backup.internal.contains("/1/*"));
    }

    #[test]
    fn export_descriptors_carries_account_network() {
        let mainnet =
            OnchainAccount::from_mnemonic(BhodlNetwork::Mainnet, BIP86_MNEMONIC, "").unwrap();
        let signet =
            OnchainAccount::from_mnemonic(BhodlNetwork::Signet, BIP86_MNEMONIC, "").unwrap();
        assert_eq!(mainnet.export_descriptors().network, BhodlNetwork::Mainnet);
        assert_eq!(signet.export_descriptors().network, BhodlNetwork::Signet);
    }

    #[test]
    fn export_descriptors_external_matches_bip86_account_path() {
        // Sanity-check the descriptor shape: it should be a `tr(...)`
        // taproot descriptor whose key origin starts with `[<fp>/86'/0'/0']`
        // for mainnet and ends with `/0/*` (external chain).
        let account =
            OnchainAccount::from_mnemonic(BhodlNetwork::Mainnet, BIP86_MNEMONIC, "").unwrap();
        let backup = account.export_descriptors();
        assert!(backup.external.starts_with("tr("));
        assert!(backup.external.contains("/86'/0'/0']"));
        // Allow for a checksum suffix `#xxxxxxxx` after `/0/*`.
        assert!(
            backup.external.contains("/0/*"),
            "expected external chain `/0/*` in {}",
            backup.external
        );
    }

    #[test]
    fn account_xpub_extracts_bare_key_from_external_descriptor() {
        let account =
            OnchainAccount::from_mnemonic(BhodlNetwork::Mainnet, BIP86_MNEMONIC, "").unwrap();
        let backup = account.export_descriptors();
        let xpub = backup.account_xpub().expect("xpub extraction must succeed");
        // The extracted xpub must start with `xpub` (mainnet) and lack
        // the wrapping descriptor / origin / keychain path.
        assert!(
            xpub.starts_with("xpub"),
            "expected xpub-prefixed key, got: {xpub}"
        );
        assert!(!xpub.contains("tr("));
        assert!(!xpub.contains("/0/*"));
        assert!(!xpub.contains('['));
        assert!(!xpub.contains(']'));
        assert!(!xpub.contains('#'));
    }

    #[test]
    fn account_xpub_signet_uses_tpub_prefix() {
        let account =
            OnchainAccount::from_mnemonic(BhodlNetwork::Signet, BIP86_MNEMONIC, "").unwrap();
        let backup = account.export_descriptors();
        let xpub = backup.account_xpub().expect("xpub extraction must succeed");
        assert!(
            xpub.starts_with("tpub"),
            "signet wallet must yield tpub-prefixed key, got: {xpub}"
        );
    }

    #[test]
    fn list_utxos_is_empty_on_fresh_wallet() {
        let account =
            OnchainAccount::from_mnemonic(BhodlNetwork::Mainnet, BIP86_MNEMONIC, "").unwrap();
        let utxos = account
            .list_utxos()
            .expect("list_utxos must not fail on fresh wallet");
        assert!(utxos.is_empty(), "fresh wallet must have zero utxos");
    }

    #[test]
    fn build_send_tx_with_utxos_rejects_empty_selection() {
        let mut account =
            OnchainAccount::from_mnemonic(BhodlNetwork::Mainnet, BIP86_MNEMONIC, "").unwrap();
        let target = account.peek_taproot_address(2).unwrap().to_string();
        let result =
            account.build_send_tx_with_utxos(&target, Amount::from_sat(10_000), 1, &[], false);
        assert!(
            matches!(result, Err(OnchainError::TxBuild(_))),
            "empty utxo set must be rejected with TxBuild error, got {result:?}"
        );
    }

    #[test]
    fn build_send_tx_on_empty_wallet_reports_insufficient_funds() {
        // No sync done; balance is zero. BDK's tx builder must refuse.
        let mut account =
            OnchainAccount::from_mnemonic(BhodlNetwork::Mainnet, BIP86_MNEMONIC, "").unwrap();
        // A valid mainnet bech32m address derived from the same mnemonic at
        // a non-conflicting index — fine for "send-to-self" shape, but the
        // wallet has no UTXO so this still must error in TxBuild.
        let target = account.peek_taproot_address(2).unwrap().to_string();
        let result = account.build_send_tx(&target, Amount::from_sat(50_000), 2, false);
        assert!(
            matches!(result, Err(OnchainError::TxBuild(_))),
            "empty wallet must report TxBuild error, got {result:?}"
        );
    }

    #[test]
    fn drop_runs_without_panicking() {
        // The Drop impl scrubs the master SecretKey via secp256k1's
        // `non_secure_erase`. We cannot observe the post-drop bytes
        // without `unsafe { drop_in_place }` (forbidden by the workspace
        // lints), so this is a smoke test only — confirming the Drop path
        // does not panic on the secp context teardown is the most we
        // can verify within safe Rust.
        let account =
            OnchainAccount::from_mnemonic(BhodlNetwork::Mainnet, BIP86_MNEMONIC, "").unwrap();
        drop(account);
    }

    #[test]
    fn bip39_passphrase_changes_the_derived_address() {
        // Defense-in-depth contract: same mnemonic + different
        // passphrases must derive completely independent wallets.
        // BIP39 mixes the passphrase into the seed via PBKDF2, so
        // any non-empty passphrase changes the master xpriv and
        // every downstream BIP86 address.
        let plain =
            OnchainAccount::from_mnemonic(BhodlNetwork::Mainnet, BIP86_MNEMONIC, "").unwrap();
        let with_passphrase =
            OnchainAccount::from_mnemonic(BhodlNetwork::Mainnet, BIP86_MNEMONIC, "TREZOR").unwrap();
        let plain_addr = plain.peek_taproot_address(0).unwrap().to_string();
        let pp_addr = with_passphrase.peek_taproot_address(0).unwrap().to_string();
        assert_ne!(
            plain_addr, pp_addr,
            "passphrase MUST change the derived address — proves the seed mix worked",
        );
    }

    #[test]
    fn empty_and_omitted_passphrase_produce_the_same_wallet() {
        // BIP39 passphrase=="" is equivalent to no passphrase at all.
        // We rely on this so users who never opt into the advanced
        // section keep getting the same wallet they had before.
        let a = OnchainAccount::from_mnemonic(BhodlNetwork::Mainnet, BIP86_MNEMONIC, "").unwrap();
        let b = OnchainAccount::from_mnemonic(BhodlNetwork::Mainnet, BIP86_MNEMONIC, "").unwrap();
        assert_eq!(
            a.peek_taproot_address(0).unwrap().to_string(),
            b.peek_taproot_address(0).unwrap().to_string(),
        );
    }

    #[test]
    fn invalid_mnemonic_is_rejected() {
        let result =
            OnchainAccount::from_mnemonic(BhodlNetwork::Mainnet, "not a valid mnemonic", "");
        assert!(matches!(result, Err(OnchainError::Mnemonic(_))));
    }

    #[test]
    fn signet_address_is_bech32m_with_tb1p_prefix() {
        let account =
            OnchainAccount::from_mnemonic(BhodlNetwork::Signet, BIP86_MNEMONIC, "").unwrap();
        let address = account.peek_taproot_address(0).unwrap();
        assert!(
            address.to_string().starts_with("tb1p"),
            "expected signet bech32m taproot address, got {address}"
        );
    }

    // --- New tests for BDK-backed methods ---

    #[test]
    fn balance_is_zero_on_fresh_wallet() {
        let account =
            OnchainAccount::from_mnemonic(BhodlNetwork::Mainnet, BIP86_MNEMONIC, "").unwrap();
        assert_eq!(account.balance(), Amount::ZERO);
    }

    #[test]
    fn reveal_next_address_first_call_matches_peek_at_index_zero() {
        let mut account =
            OnchainAccount::from_mnemonic(BhodlNetwork::Mainnet, BIP86_MNEMONIC, "").unwrap();
        let revealed = account.reveal_next_address().unwrap();
        let peeked = account.peek_taproot_address(0).unwrap();
        assert_eq!(revealed, peeked);
    }

    #[test]
    fn reveal_next_address_advances_within_one_session() {
        let mut account =
            OnchainAccount::from_mnemonic(BhodlNetwork::Mainnet, BIP86_MNEMONIC, "").unwrap();
        let first = account.reveal_next_address().unwrap();
        let second = account.reveal_next_address().unwrap();
        let index_1 = account.peek_taproot_address(1).unwrap();
        assert_ne!(first, second);
        assert_eq!(second, index_1);
    }

    // --- Persistence tests ---

    /// Test-only fixed encryption key. Must NEVER appear in production paths.
    const TEST_KEY: [u8; ENCRYPTION_KEY_LEN] = [0x42; ENCRYPTION_KEY_LEN];

    #[test]
    fn persistent_wallet_address_survives_close_and_reopen() {
        let tmp = tempfile::tempdir().unwrap();
        let db = tmp.path().join("wallet.sqlite");

        // First session: create, derive an address.
        let first_address = {
            let account = OnchainAccount::create_or_load_persistent(
                BhodlNetwork::Mainnet,
                BIP86_MNEMONIC,
                "",
                &db,
                &TEST_KEY,
            )
            .unwrap();
            account.peek_taproot_address(0).unwrap()
        };
        // (account dropped here — connection closes)

        // Second session: reopen the same DB, same mnemonic, same key.
        let reopened_address = {
            let account = OnchainAccount::create_or_load_persistent(
                BhodlNetwork::Mainnet,
                BIP86_MNEMONIC,
                "",
                &db,
                &TEST_KEY,
            )
            .unwrap();
            account.peek_taproot_address(0).unwrap()
        };

        assert_eq!(first_address, reopened_address);
        assert_eq!(
            reopened_address.to_string(),
            "bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr",
            "persistent wallet must still match the BIP86 vector after reload"
        );
    }

    #[test]
    fn persistent_wallet_remembers_revealed_addresses() {
        let tmp = tempfile::tempdir().unwrap();
        let db = tmp.path().join("wallet.sqlite");

        // First session: reveal index 0 (advances BDK's "next unused" counter).
        let revealed_index_0 = {
            let mut account = OnchainAccount::create_or_load_persistent(
                BhodlNetwork::Mainnet,
                BIP86_MNEMONIC,
                "",
                &db,
                &TEST_KEY,
            )
            .unwrap();
            account.reveal_next_address().unwrap()
        };

        // Second session: BDK should remember index 0 was revealed and
        // hand out index 1 next.
        let next_after_reload = {
            let mut account = OnchainAccount::create_or_load_persistent(
                BhodlNetwork::Mainnet,
                BIP86_MNEMONIC,
                "",
                &db,
                &TEST_KEY,
            )
            .unwrap();
            account.reveal_next_address().unwrap()
        };

        let index_1 = OnchainAccount::from_mnemonic(BhodlNetwork::Mainnet, BIP86_MNEMONIC, "")
            .unwrap()
            .peek_taproot_address(1)
            .unwrap();

        assert_ne!(revealed_index_0, next_after_reload);
        assert_eq!(next_after_reload, index_1);
    }

    #[test]
    fn encrypted_wallet_rejects_wrong_key_on_reopen() {
        let tmp = tempfile::tempdir().unwrap();
        let db = tmp.path().join("wallet.sqlite");

        // Create with key A.
        {
            let _account = OnchainAccount::create_or_load_persistent(
                BhodlNetwork::Mainnet,
                BIP86_MNEMONIC,
                "",
                &db,
                &TEST_KEY,
            )
            .unwrap();
        }

        // Try to reopen with key B.
        let wrong_key = [0xAB; ENCRYPTION_KEY_LEN];
        let result = OnchainAccount::create_or_load_persistent(
            BhodlNetwork::Mainnet,
            BIP86_MNEMONIC,
            "",
            &db,
            &wrong_key,
        );
        assert!(
            matches!(result, Err(OnchainError::Storage(_))),
            "wrong encryption key must be rejected as Storage error, got {result:?}"
        );
    }

    #[test]
    fn encrypted_db_file_is_not_readable_as_plain_sqlite() {
        // SQLCipher writes a fully encrypted page 0; opening the file as
        // plain SQLite should fail to read the schema. This guards against
        // accidentally bypassing PRAGMA key in the future.
        let tmp = tempfile::tempdir().unwrap();
        let db = tmp.path().join("wallet.sqlite");

        {
            let _account = OnchainAccount::create_or_load_persistent(
                BhodlNetwork::Mainnet,
                BIP86_MNEMONIC,
                "",
                &db,
                &TEST_KEY,
            )
            .unwrap();
        }

        // No PRAGMA key applied — should fail when probing the schema.
        let plain = Connection::open(&db).unwrap();
        let probe: rusqlite::Result<i64> =
            plain.query_row("SELECT count(*) FROM sqlite_master", [], |r| r.get(0));
        assert!(
            probe.is_err(),
            "encrypted DB unexpectedly readable without PRAGMA key: {probe:?}"
        );
    }

    /// Optional integration test against mempool.space signet Esplora.
    ///
    /// Marked `#[ignore]` so CI stays green without network access.
    /// Run manually with:
    ///   cargo test -p bhodl-wallet-onchain -- --ignored `esplora_signet_sync`
    #[test]
    #[ignore = "hits live signet Esplora; run with `cargo test -- --ignored`"]
    fn esplora_signet_sync_does_not_error() {
        let mut account =
            OnchainAccount::from_mnemonic(BhodlNetwork::Signet, BIP86_MNEMONIC, "").unwrap();
        account
            .sync_with_esplora("https://mempool.space/signet/api", None)
            .expect("Esplora signet sync should succeed");
        // Balance will be 0 for the well-known test mnemonic on signet; we
        // only assert the sync itself did not return an error.
    }

    /// One-shot local HTTP server answering every request with `status`
    /// and `body`; returns the base Esplora URL.
    fn one_shot_http(status: &'static str, body: &'static str) -> String {
        use std::io::{Read as _, Write as _};
        let l = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let port = l.local_addr().unwrap().port();
        std::thread::spawn(move || {
            let (mut s, _) = l.accept().unwrap();
            let mut buf = [0u8; 4096];
            let _ = s.read(&mut buf);
            let _ = write!(
                s,
                "HTTP/1.1 {status}\r\nContent-Type: application/json\r\n\
                 Content-Length: {}\r\nConnection: close\r\n\r\n{body}",
                body.len()
            );
        });
        format!("http://127.0.0.1:{port}/api")
    }

    #[test]
    fn fee_estimates_accept_203_non_authoritative() {
        // mempool.space answers its deprecated /fee-estimates with 203;
        // esplora-client's strict `== 200` check used to reject it.
        let url = one_shot_http(
            "203 Non-Authoritative Information",
            r#"{"1":12.5,"3":8,"6":4.2,"144":1}"#,
        );
        let b = OnchainAccount::fetch_fee_estimates_from(&url, None).unwrap();
        assert_eq!(b.priority_sat_per_vb, 13);
        assert_eq!(b.normal_sat_per_vb, 8);
        assert_eq!(b.economy_sat_per_vb, 5);
    }

    #[test]
    fn fee_estimates_reject_non_2xx() {
        let url = one_shot_http("404 Not Found", "nope");
        assert!(matches!(
            OnchainAccount::fetch_fee_estimates_from(&url, None),
            Err(OnchainError::EsploraSync(_))
        ));
    }

    // -------------------------------------------------------------------
    // Pure helpers — no network, no BDK testenv. Run on every OS.
    // -------------------------------------------------------------------

    #[test]
    fn build_send_tx_with_outpoint_strings_rejects_garbage() {
        let mut account =
            OnchainAccount::from_mnemonic(BhodlNetwork::Signet, BIP86_MNEMONIC, "").unwrap();
        let target = account.peek_taproot_address(0).unwrap().to_string();
        let result = account.build_send_tx_with_outpoint_strings(
            &target,
            Amount::from_sat(1_000),
            1,
            &["this-is-not-an-outpoint"],
            false,
        );
        assert!(matches!(result, Err(OnchainError::TxBuild(_))));
    }

    #[test]
    fn build_send_tx_batch_rejects_empty_recipients() {
        let mut account =
            OnchainAccount::from_mnemonic(BhodlNetwork::Mainnet, BIP86_MNEMONIC, "").unwrap();
        let result = account.build_send_tx_batch(&[], 1, false);
        assert!(matches!(result, Err(OnchainError::TxBuild(_))));
    }

    #[test]
    fn build_send_tx_batch_rejects_address_for_other_network() {
        let mut account =
            OnchainAccount::from_mnemonic(BhodlNetwork::Mainnet, BIP86_MNEMONIC, "").unwrap();
        // Signet bech32m on a Mainnet account → InvalidAddress.
        let result = account.build_send_tx_batch(
            &[(
                "tb1q44zwzaur5kss5q40fzwj6lgcuxcm0sgvxs7yz3".to_string(),
                Amount::from_sat(1_000),
            )],
            1,
            false,
        );
        assert!(matches!(result, Err(OnchainError::InvalidAddress(_))));
    }

    #[test]
    fn build_fee_bump_psbt_rejects_invalid_txid() {
        let mut account =
            OnchainAccount::from_mnemonic(BhodlNetwork::Mainnet, BIP86_MNEMONIC, "").unwrap();
        let result = account.build_fee_bump_psbt("not-a-txid", 5, false);
        assert!(matches!(result, Err(OnchainError::TxBuild(_))));
    }

    #[test]
    fn build_fee_bump_psbt_rejects_invalid_fee_rate() {
        let mut account =
            OnchainAccount::from_mnemonic(BhodlNetwork::Mainnet, BIP86_MNEMONIC, "").unwrap();
        // 0 sat/vB is below the rust-bitcoin minimum.
        let txid = "0".repeat(64);
        let result = account.build_fee_bump_psbt(&txid, 0, false);
        // Either invalid fee rate, or unknown txid in the wallet — both are
        // legitimate errors and both come back as TxBuild / InvalidFeeRate.
        assert!(matches!(
            result,
            Err(OnchainError::InvalidFeeRate(_) | OnchainError::TxBuild(_))
        ));
    }

    #[test]
    fn psbt_fee_sat_rejects_garbage_input() {
        let result = OnchainAccount::psbt_fee_sat("not-base64-not-a-psbt");
        assert!(matches!(result, Err(OnchainError::InvalidPsbt(_))));
    }

    // -------------------------------------------------------------------
    // Direct error-path tests for sign / extract / list_transactions and
    // the persistent unlock + account-level contact API. These all run on
    // pure `:memory:` SQLCipher or a tempfile DB, no network required.
    // -------------------------------------------------------------------

    #[test]
    fn sign_psbt_rejects_garbage_input() {
        let account =
            OnchainAccount::from_mnemonic(BhodlNetwork::Mainnet, BIP86_MNEMONIC, "").unwrap();
        let result = account.sign_psbt("not-a-psbt");
        assert!(matches!(result, Err(OnchainError::InvalidPsbt(_))));
    }

    #[test]
    fn extract_signed_tx_rejects_garbage_input() {
        // Static method on the type — does not need a wallet instance.
        let result = OnchainAccount::extract_signed_tx("definitely-not-base64");
        assert!(matches!(result, Err(OnchainError::InvalidPsbt(_))));
    }

    #[test]
    fn list_transactions_is_empty_on_fresh_wallet() {
        let account =
            OnchainAccount::from_mnemonic(BhodlNetwork::Mainnet, BIP86_MNEMONIC, "").unwrap();
        assert!(account.list_transactions().is_empty());
    }

    #[test]
    fn load_persistent_reopens_with_only_pin_after_create() {
        // create_or_load_persistent stores the mnemonic encrypted under
        // the wallet key the first time; load_persistent reopens the
        // same DB on second run without taking a mnemonic argument.
        // This is the exact path the desktop UnlockScreen drives at
        // every app launch, so it deserves a unit test that does not
        // require the FFI.
        let tmp = tempfile::tempdir().unwrap();
        let db = tmp.path().join("wallet.sqlite");

        let first_address = {
            let account = OnchainAccount::create_or_load_persistent(
                BhodlNetwork::Mainnet,
                BIP86_MNEMONIC,
                "",
                &db,
                &TEST_KEY,
            )
            .unwrap();
            account.peek_taproot_address(0).unwrap()
        };

        let reopened_address = {
            let account =
                OnchainAccount::load_persistent(BhodlNetwork::Mainnet, &db, &TEST_KEY).unwrap();
            account.peek_taproot_address(0).unwrap()
        };

        assert_eq!(first_address, reopened_address);
    }

    #[test]
    fn load_persistent_rejects_wrong_encryption_key() {
        let tmp = tempfile::tempdir().unwrap();
        let db = tmp.path().join("wallet.sqlite");

        // Create with TEST_KEY.
        {
            let _account = OnchainAccount::create_or_load_persistent(
                BhodlNetwork::Mainnet,
                BIP86_MNEMONIC,
                "",
                &db,
                &TEST_KEY,
            )
            .unwrap();
        }

        // Reopen with the wrong key.
        let wrong_key = [0xCD; ENCRYPTION_KEY_LEN];
        let result = OnchainAccount::load_persistent(BhodlNetwork::Mainnet, &db, &wrong_key);
        assert!(matches!(result, Err(OnchainError::Storage(_))));
    }

    #[test]
    fn build_drain_tx_rejects_garbage_address() {
        let mut account =
            OnchainAccount::from_mnemonic(BhodlNetwork::Mainnet, BIP86_MNEMONIC, "").unwrap();
        let result = account.build_drain_tx("not-a-bitcoin-address", 1, false);
        assert!(matches!(result, Err(OnchainError::InvalidAddress(_))));
    }

    #[test]
    fn build_drain_tx_rejects_address_for_other_network() {
        let mut account =
            OnchainAccount::from_mnemonic(BhodlNetwork::Mainnet, BIP86_MNEMONIC, "").unwrap();
        // Signet bech32m on a Mainnet account → InvalidAddress.
        let result = account.build_drain_tx("tb1q44zwzaur5kss5q40fzwj6lgcuxcm0sgvxs7yz3", 1, false);
        assert!(matches!(result, Err(OnchainError::InvalidAddress(_))));
    }

    #[test]
    fn build_drain_tx_on_empty_wallet_reports_insufficient_funds() {
        let mut account =
            OnchainAccount::from_mnemonic(BhodlNetwork::Signet, BIP86_MNEMONIC, "").unwrap();
        let target = account.peek_taproot_address(0).unwrap().to_string();
        let result = account.build_drain_tx(&target, 1, false);
        assert!(matches!(result, Err(OnchainError::TxBuild(_))));
    }

    #[test]
    fn build_send_tx_batch_with_utxos_rejects_empty_recipients() {
        let mut account =
            OnchainAccount::from_mnemonic(BhodlNetwork::Signet, BIP86_MNEMONIC, "").unwrap();
        // Bogus but parseable outpoint — error must come from the
        // empty-recipients check, NOT from the outpoint parse.
        let dummy: OutPoint = "0000000000000000000000000000000000000000000000000000000000000000:0"
            .parse()
            .unwrap();
        let result = account.build_send_tx_batch_with_utxos(&[], 1, &[dummy], false);
        assert!(matches!(result, Err(OnchainError::TxBuild(_))));
    }

    #[test]
    fn build_send_tx_batch_with_utxos_rejects_empty_utxos() {
        let mut account =
            OnchainAccount::from_mnemonic(BhodlNetwork::Signet, BIP86_MNEMONIC, "").unwrap();
        let to = account.peek_taproot_address(0).unwrap().to_string();
        let result =
            account.build_send_tx_batch_with_utxos(&[(to, Amount::from_sat(1_000))], 1, &[], false);
        assert!(matches!(result, Err(OnchainError::TxBuild(_))));
    }

    #[test]
    fn build_send_tx_batch_with_utxos_rejects_address_for_other_network() {
        let mut account =
            OnchainAccount::from_mnemonic(BhodlNetwork::Mainnet, BIP86_MNEMONIC, "").unwrap();
        // Signet bech32m on a Mainnet account → InvalidAddress.
        let dummy: OutPoint = "0000000000000000000000000000000000000000000000000000000000000000:0"
            .parse()
            .unwrap();
        let result = account.build_send_tx_batch_with_utxos(
            &[(
                "tb1q44zwzaur5kss5q40fzwj6lgcuxcm0sgvxs7yz3".to_string(),
                Amount::from_sat(1_000),
            )],
            1,
            &[dummy],
            false,
        );
        assert!(matches!(result, Err(OnchainError::InvalidAddress(_))));
    }

    #[test]
    fn build_send_tx_batch_with_outpoint_strings_rejects_garbage_outpoint() {
        let mut account =
            OnchainAccount::from_mnemonic(BhodlNetwork::Signet, BIP86_MNEMONIC, "").unwrap();
        let to = account.peek_taproot_address(0).unwrap().to_string();
        let result = account.build_send_tx_batch_with_outpoint_strings(
            &[(to, Amount::from_sat(1_000))],
            1,
            &["this-is-not-an-outpoint"],
            false,
        );
        assert!(matches!(result, Err(OnchainError::TxBuild(_))));
    }

    #[test]
    fn build_send_tx_batch_with_utxos_rejects_unknown_outpoint() {
        // Empty wallet + a syntactically-valid but unknown outpoint:
        // BDK refuses with TxBuild because the UTXO is not in the
        // wallet's spendable set. Proves we are NOT silently falling
        // back to the wallet's other coins (there are none anyway).
        let mut account =
            OnchainAccount::from_mnemonic(BhodlNetwork::Signet, BIP86_MNEMONIC, "").unwrap();
        let to = account.peek_taproot_address(0).unwrap().to_string();
        let result = account.build_send_tx_batch_with_outpoint_strings(
            &[(to, Amount::from_sat(1_000))],
            1,
            &["0000000000000000000000000000000000000000000000000000000000000000:0"],
            false,
        );
        assert!(matches!(result, Err(OnchainError::TxBuild(_))));
    }

    #[test]
    fn export_encrypted_backup_round_trips_through_decrypt_bundle() {
        let tmp = tempfile::tempdir().unwrap();
        let db = tmp.path().join("wallet.sqlite");
        let account = OnchainAccount::create_or_load_persistent(
            BhodlNetwork::Signet,
            BIP86_MNEMONIC,
            "my-13th-word",
            &db,
            &TEST_KEY,
        )
        .unwrap();

        let blob = account.export_encrypted_backup("backup-pw").unwrap();
        assert!(blob.starts_with("-----BEGIN AGE ENCRYPTED FILE-----"));

        let restored = crate::decrypt_bundle(&blob, "backup-pw").unwrap();
        assert_eq!(restored.version, crate::BackupBundle::CURRENT_VERSION);
        assert_eq!(restored.network, "signet");
        assert_eq!(restored.mnemonic, BIP86_MNEMONIC);
        assert_eq!(restored.passphrase, "my-13th-word");
    }

    #[test]
    fn export_encrypted_backup_fails_on_in_memory_wallet() {
        // No persisted seed table -> cannot export.
        let in_memory =
            OnchainAccount::from_mnemonic(BhodlNetwork::Mainnet, BIP86_MNEMONIC, "").unwrap();
        let result = in_memory.export_encrypted_backup("any");
        assert!(matches!(result, Err(OnchainError::Storage(_))));
    }

    #[test]
    fn account_contact_api_round_trips() {
        // Drive every method on the account-level contact API once.
        // The underlying contacts.rs module already has its own unit
        // tests; this test guards the thin account adapter (now/UNIX
        // timestamp + connection wiring).
        let account =
            OnchainAccount::from_mnemonic(BhodlNetwork::Mainnet, BIP86_MNEMONIC, "").unwrap();

        assert!(account.list_contacts().unwrap().is_empty());

        let id_alice = account
            .add_contact(
                "Alice",
                "bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr",
            )
            .unwrap();
        let id_bob = account
            .add_contact(
                "Bob",
                "bc1p4qhjn9zdvkux4e44uhx8tc55attvtyu358kutcqkudyccelu0was9fqzwh",
            )
            .unwrap();
        assert!(id_bob > id_alice);

        let listed = account.list_contacts().unwrap();
        assert_eq!(listed.len(), 2);
        assert_eq!(listed[0].label, "Alice");
        assert_eq!(listed[1].label, "Bob");

        // Update Alice → Carol.
        assert!(account.update_contact_label(id_alice, "Carol").unwrap());
        let carol = account
            .list_contacts()
            .unwrap()
            .into_iter()
            .find(|c| c.id == id_alice)
            .unwrap();
        assert_eq!(carol.label, "Carol");

        // Update unknown id is a no-op (returns false).
        assert!(!account.update_contact_label(9_999, "ghost").unwrap());

        // Remove Bob.
        assert!(account.remove_contact(id_bob).unwrap());
        assert_eq!(account.list_contacts().unwrap().len(), 1);

        // Removing again returns false.
        assert!(!account.remove_contact(id_bob).unwrap());
    }
}

// -----------------------------------------------------------------------
// PayJoin sender: UI binding + "sign only our original inputs" guard.
// -----------------------------------------------------------------------
#[cfg(test)]
mod payjoin_tests {
    use super::*;
    use bitcoin::{
        Amount as BtcAmount, ScriptBuf, Sequence, Transaction, TxIn, TxOut, Witness,
        absolute::LockTime, hashes::Hash, transaction::Version,
    };

    const MNEMONIC: &str = "abandon abandon abandon abandon abandon abandon \
                            abandon abandon abandon abandon abandon about";

    fn signet_account() -> OnchainAccount {
        OnchainAccount::from_mnemonic(BhodlNetwork::Signet, MNEMONIC, "").unwrap()
    }

    fn other_signet_address() -> String {
        // Account index 1 of a different mnemonic — not ours.
        OnchainAccount::from_mnemonic(
            BhodlNetwork::Signet,
            "legal winner thank year wave sausage worth useful legal winner thank yellow",
            "",
        )
        .unwrap()
        .peek_taproot_address(0)
        .unwrap()
        .to_string()
    }

    #[test]
    fn negotiate_refuses_address_shown_in_ui_that_differs_from_uri() {
        let mut acct = signet_account();
        let uri_addr = other_signet_address();
        let shown = acct.peek_taproot_address(7).unwrap().to_string();
        let uri = format!("bitcoin:{uri_addr}?amount=0.001&pj=https://pj.example.com/x");
        let err = acct
            .payjoin_negotiate(&uri, &shown, 100_000, 2, None, false)
            .unwrap_err();
        assert!(
            matches!(err, OnchainError::InvalidAddress(ref m) if m.contains("differs")),
            "{err:?}"
        );
    }

    #[test]
    fn negotiate_refuses_amount_shown_in_ui_that_differs_from_uri() {
        let mut acct = signet_account();
        let addr = other_signet_address();
        // C3 shape: the UI showed 1000 sat, the URI asks for 1 BTC.
        let uri = format!("bitcoin:{addr}?amount=1&pj=https://pj.example.com/x");
        let err = acct
            .payjoin_negotiate(&uri, &addr, 1_000, 2, None, false)
            .unwrap_err();
        assert!(
            matches!(err, OnchainError::InvalidAddress(ref m) if m.contains("differs")),
            "{err:?}"
        );
        let dup = format!("bitcoin:{addr}?amount=1&AMOUNT=0.00001&pj=https://pj.example.com/x");
        assert!(
            acct.payjoin_negotiate(&dup, &addr, 1_000, 2, None, false)
                .is_err()
        );
    }

    #[test]
    fn negotiate_refuses_onion_without_tor_before_building() {
        let mut acct = signet_account();
        let addr = other_signet_address();
        let uri = format!("bitcoin:{addr}?amount=0.001&pj=http://abcdef.onion/pj");
        let err = acct
            .payjoin_negotiate(&uri, &addr, 100_000, 2, None, false)
            .unwrap_err();
        assert!(
            matches!(err, OnchainError::InvalidAddress(ref m) if m.contains("Tor")),
            "{err:?}"
        );
    }

    #[test]
    fn sign_and_broadcast_rejects_unknown_session() {
        let mut acct = signet_account();
        let err = acct
            .payjoin_sign_and_broadcast("00", "https://127.0.0.1:1", None)
            .unwrap_err();
        assert!(matches!(err, OnchainError::TxBuild(_)));
    }

    /// Fund `acct` with two UTXOs on its first two receive addresses.
    fn fund(acct: &mut OnchainAccount) -> (OutPoint, TxOut, OutPoint, TxOut) {
        let a0 = acct.reveal_next_address().unwrap();
        let a1 = acct.reveal_next_address().unwrap();
        let out0 = TxOut {
            value: BtcAmount::from_sat(50_000),
            script_pubkey: a0.script_pubkey(),
        };
        let out1 = TxOut {
            value: BtcAmount::from_sat(60_000),
            script_pubkey: a1.script_pubkey(),
        };
        let funding = Transaction {
            version: Version::TWO,
            lock_time: LockTime::ZERO,
            input: vec![TxIn {
                previous_output: OutPoint::new(bitcoin::Txid::from_byte_array([42; 32]), 0),
                ..TxIn::default()
            }],
            output: vec![out0.clone(), out1.clone()],
        };
        let txid = funding.compute_txid();
        acct.wallet.apply_unconfirmed_txs([(funding, 0)]);
        (OutPoint::new(txid, 0), out0, OutPoint::new(txid, 1), out1)
    }

    fn proposal(
        sender: (OutPoint, TxOut),
        second: (OutPoint, TxOut),
        second_finalized: bool,
    ) -> crate::payjoin::ValidatedProposal {
        let pay_to = other_signet_address()
            .parse::<Address<bitcoin::address::NetworkUnchecked>>()
            .unwrap()
            .assume_checked()
            .script_pubkey();
        let tx = Transaction {
            version: Version::TWO,
            lock_time: LockTime::ZERO,
            input: [sender.0, second.0]
                .into_iter()
                .map(|op| TxIn {
                    previous_output: op,
                    sequence: Sequence::ENABLE_RBF_NO_LOCKTIME,
                    ..TxIn::default()
                })
                .collect(),
            output: vec![TxOut {
                value: BtcAmount::from_sat(100_000),
                script_pubkey: pay_to,
            }],
        };
        let mut psbt = Psbt::from_unsigned_tx(tx).unwrap();
        psbt.inputs[0].witness_utxo = Some(sender.1);
        psbt.inputs[1].witness_utxo = Some(second.1);
        if second_finalized {
            psbt.inputs[1].final_script_witness = Some(Witness::from_slice(&[[9u8; 64]]));
        }
        crate::payjoin::ValidatedProposal {
            psbt,
            sender_input_indices: vec![0],
            original_fee: 0,
            proposal_fee: 10_000,
            contribution: 0,
            receiver_input_count: 1,
            estimated_vsize: 0,
        }
    }

    #[test]
    fn signer_never_signs_a_wallet_coin_smuggled_in_as_receiver_input() {
        let mut acct = signet_account();
        let (op0, out0, op1, out1) = fund(&mut acct);
        // Receiver "contributes" our own second UTXO, left unsigned so
        // BDK would happily sign it. validate_proposal rejects this
        // (is_mine), and the sign step independently refuses.
        let p = proposal((op0, out0), (op1, out1), false);
        let err = acct.sign_payjoin_proposal(&p).unwrap_err();
        assert!(
            matches!(err, OnchainError::Sign(ref m) if m.contains("non-sender input #1")),
            "{err:?}"
        );
    }

    #[test]
    fn signer_signs_only_sender_inputs_and_leaves_receiver_input_intact() {
        let mut acct = signet_account();
        let (op0, out0, _, _) = fund(&mut acct);
        let foreign_spk = ScriptBuf::from_bytes([&[0x51, 0x20][..], &[5u8; 32]].concat());
        let foreign = (
            OutPoint::new(bitcoin::Txid::from_byte_array([7; 32]), 3),
            TxOut {
                value: BtcAmount::from_sat(60_000),
                script_pubkey: foreign_spk,
            },
        );
        let p = proposal((op0, out0), foreign, true);
        let signed = acct.sign_payjoin_proposal(&p).unwrap();
        assert!(signed.inputs[0].final_script_witness.is_some());
        assert_eq!(signed.inputs[1], p.psbt.inputs[1]);
    }
}

// End-to-end BIP-78 sender tests against an in-process receiver wallet.
// A child module of `account` so it can reach the private seam
// (`payjoin_negotiate_with`, `payjoin_sign_checked`) and the BDK wallet.
#[cfg(test)]
#[path = "payjoin_e2e_tests.rs"]
mod payjoin_e2e_tests;

/// Totals over the unconfirmed ancestors of a CPFP parent.
#[derive(Default)]
struct AncestorTotals {
    count: u32,
    fee_sat: u64,
    vsize: u64,
}

// -----------------------------------------------------------------------
// Funds-safety guard rails on the send path (fee caps, absolute-fee
// guard, review decode, CPFP package math, post-broadcast bookkeeping,
// unconfirmed / frozen coin exclusion). Regtest-free: funding and
// confirmations are injected straight into the BDK tx graph.
// -----------------------------------------------------------------------
#[cfg(test)]
mod send_safety_tests {
    use super::*;
    use bdk_chain::{BlockId, ConfirmationBlockTime, TxUpdate};
    use bitcoin::{
        BlockHash, Transaction, TxIn, TxOut, absolute::LockTime, hashes::Hash, transaction::Version,
    };
    use std::sync::Arc;

    const MNEMONIC: &str = "abandon abandon abandon abandon abandon abandon \
                            abandon abandon abandon abandon abandon about";

    fn signet_account() -> OnchainAccount {
        OnchainAccount::from_mnemonic(BhodlNetwork::Signet, MNEMONIC, "").unwrap()
    }

    /// Address of a different wallet (third party).
    fn foreign_addr(index: u32) -> String {
        OnchainAccount::from_mnemonic(
            BhodlNetwork::Signet,
            "legal winner thank year wave sausage worth useful legal winner thank yellow",
            "",
        )
        .unwrap()
        .peek_taproot_address(index)
        .unwrap()
        .to_string()
    }

    /// A transaction from a third party paying `values` to fresh
    /// receive addresses of `acct`.
    fn incoming_tx(acct: &mut OnchainAccount, values: &[u64], salt: u8) -> Transaction {
        let output = values
            .iter()
            .map(|v| TxOut {
                value: bitcoin::Amount::from_sat(*v),
                script_pubkey: acct.reveal_next_address().unwrap().script_pubkey(),
            })
            .collect();
        Transaction {
            version: Version::TWO,
            lock_time: LockTime::ZERO,
            input: vec![TxIn {
                previous_output: OutPoint::new(bitcoin::Txid::from_byte_array([salt; 32]), 0),
                ..TxIn::default()
            }],
            output,
        }
    }

    /// Insert `tx` confirmed in a block at `height`.
    fn confirm(acct: &mut OnchainAccount, tx: Transaction, height: u32) {
        let txid = tx.compute_txid();
        let block_id = BlockId {
            height,
            hash: BlockHash::from_byte_array([u8::try_from(height).unwrap(); 32]),
        };
        let chain = acct.wallet.latest_checkpoint().insert(block_id);
        let mut tx_update = TxUpdate::default();
        tx_update.txs = vec![Arc::new(tx)];
        tx_update.anchors = [(
            ConfirmationBlockTime {
                block_id,
                confirmation_time: 1_700_000_000,
            },
            txid,
        )]
        .into();
        acct.wallet
            .apply_update(bdk_wallet::Update {
                chain: Some(chain),
                tx_update,
                ..Default::default()
            })
            .unwrap();
    }

    fn fund_confirmed(acct: &mut OnchainAccount, values: &[u64], salt: u8) -> Vec<OutPoint> {
        let tx = incoming_tx(acct, values, salt);
        let txid = tx.compute_txid();
        confirm(acct, tx, 1);
        (0..values.len())
            .map(|i| OutPoint::new(txid, u32::try_from(i).unwrap()))
            .collect()
    }

    fn fund_unconfirmed_foreign(acct: &mut OnchainAccount, value: u64, salt: u8) -> OutPoint {
        let tx = incoming_tx(acct, &[value], salt);
        let txid = tx.compute_txid();
        acct.wallet.apply_unconfirmed_txs([(tx, 1)]);
        OutPoint::new(txid, 0)
    }

    fn inputs_of(psbt_b64: &str) -> Vec<OutPoint> {
        let psbt: Psbt = psbt_b64.parse().unwrap();
        psbt.unsigned_tx
            .input
            .iter()
            .map(|i| i.previous_output)
            .collect()
    }

    /// Sign, extract and record as broadcast (no network).
    fn sign_and_record(acct: &mut OnchainAccount, psbt: &str) -> Transaction {
        let signed = acct.sign_psbt(psbt).unwrap();
        let tx = OnchainAccount::extract_signed_tx(&signed).unwrap();
        acct.record_broadcast(tx.clone());
        tx
    }

    // ------------------------------------------------------------ fee caps

    #[test]
    fn every_builder_rejects_zero_and_absurd_fee_rates_without_panicking() {
        let mut acct = signet_account();
        fund_confirmed(&mut acct, &[1_000_000], 1);
        let to = foreign_addr(0);
        for rate in [0, MAX_FEE_RATE + 1, 70_000_000_000_000_000, u64::MAX] {
            let r = acct.build_send_tx(&to, Amount::from_sat(10_000), rate, true);
            assert!(
                matches!(r, Err(OnchainError::InvalidFeeRate(_))),
                "{rate}: {r:?}"
            );
            let r = acct.build_drain_tx(&to, rate, true);
            assert!(
                matches!(r, Err(OnchainError::InvalidFeeRate(_))),
                "{rate}: {r:?}"
            );
            let r = acct.build_send_tx_batch(&[(to.clone(), Amount::from_sat(10_000))], rate, true);
            assert!(
                matches!(r, Err(OnchainError::InvalidFeeRate(_))),
                "{rate}: {r:?}"
            );
            let r = acct.build_fee_bump_psbt(&"0".repeat(64), rate, true);
            assert!(
                matches!(r, Err(OnchainError::InvalidFeeRate(_))),
                "{rate}: {r:?}"
            );
            let r = acct.build_cpfp_psbt(&"0".repeat(64), rate, true);
            assert!(
                matches!(r, Err(OnchainError::InvalidFeeRate(_))),
                "{rate}: {r:?}"
            );
        }
        // The cap itself is accepted.
        assert!(
            acct.build_send_tx(&to, Amount::from_sat(10_000), MAX_FEE_RATE, true)
                .is_ok()
        );
    }

    const MAX_FEE_RATE: u64 = crate::MAX_FEE_RATE_SAT_PER_VB;

    #[test]
    fn zero_fee_rate_is_allowed_on_regtest_only() {
        let mut acct = OnchainAccount::from_mnemonic(BhodlNetwork::Regtest, MNEMONIC, "").unwrap();
        fund_confirmed(&mut acct, &[100_000], 1);
        let to = acct.peek_taproot_address(50).unwrap().to_string();
        assert!(
            acct.build_send_tx(&to, Amount::from_sat(10_000), 0, false)
                .is_ok()
        );
    }

    // ----------------------------------------------------- absolute guard

    #[test]
    fn absolute_fee_guard_requires_explicit_confirmation() {
        let mut acct = signet_account();
        fund_confirmed(&mut acct, &[10_000_000], 1);
        let to = foreign_addr(0);
        // ~150 vB at 1000 sat/vB ≈ 150k sat > max(50k, 5% of 100k).
        let err = acct
            .build_send_tx(&to, Amount::from_sat(100_000), 1_000, false)
            .unwrap_err();
        match err {
            OnchainError::FeeTooHigh { fee_sat, limit_sat } => {
                assert_eq!(limit_sat, 50_000);
                assert!(fee_sat > limit_sat);
            }
            other => panic!("expected FeeTooHigh, got {other:?}"),
        }
        let psbt = acct
            .build_send_tx(&to, Amount::from_sat(100_000), 1_000, true)
            .unwrap();
        assert!(acct.decode_psbt(&psbt).unwrap().fee_exceeds_limit);
        // A normal fee passes without the override.
        let psbt = acct
            .build_send_tx(&to, Amount::from_sat(100_000), 2, false)
            .unwrap();
        assert!(!acct.decode_psbt(&psbt).unwrap().fee_exceeds_limit);
    }

    #[test]
    fn absolute_fee_guard_scales_with_amount_sent() {
        let mut acct = signet_account();
        fund_confirmed(&mut acct, &[50_000_000], 1);
        // 5% of 0.2 BTC = 1M sat: a ~150k fee is fine without override.
        let psbt = acct
            .build_send_tx(&foreign_addr(0), Amount::from_sat(20_000_000), 1_000, false)
            .unwrap();
        let s = acct.decode_psbt(&psbt).unwrap();
        assert_eq!(s.fee_limit_sat, 1_000_000);
        assert!(!s.fee_exceeds_limit);
    }

    // --------------------------------------------------------- decode_psbt

    #[test]
    fn decode_psbt_lists_every_batch_output_with_change_detection() {
        let mut acct = signet_account();
        let funded = fund_confirmed(&mut acct, &[500_000], 1);
        let (a, b) = (foreign_addr(0), foreign_addr(1));
        let psbt = acct
            .build_send_tx_batch(
                &[
                    (a.clone(), Amount::from_sat(30_000)),
                    (b.clone(), Amount::from_sat(70_000)),
                ],
                3,
                false,
            )
            .unwrap();
        let s = acct.decode_psbt(&psbt).unwrap();

        assert_eq!(s.input_count, 1);
        assert_eq!(s.foreign_input_count, 0);
        assert_eq!(s.inputs_total_sat, 500_000);
        assert_eq!(inputs_of(&psbt), funded);
        let ext: Vec<_> = s.outputs.iter().filter(|o| !o.is_mine).collect();
        assert_eq!(ext.len(), 2, "both batch recipients are shown: {s:?}");
        assert!(ext.iter().any(|o| o.address == a && o.amount_sat == 30_000));
        assert!(ext.iter().any(|o| o.address == b && o.amount_sat == 70_000));
        let change: Vec<_> = s.outputs.iter().filter(|o| o.is_mine).collect();
        assert_eq!(change.len(), 1);
        assert!(change[0].is_change);
        let outs: u64 = s.outputs.iter().map(|o| o.amount_sat).sum();
        assert_eq!(s.fee_sat, 500_000 - outs);
        assert_eq!(s.sent_external_sat, 100_000);
        assert_eq!(s.wallet_net_debit_sat, 100_000 + s.fee_sat);
        assert!(!s.spends_unconfirmed_foreign);

        // The vsize estimate is exact for BIP-86 key-path spends.
        let signed = acct.sign_psbt(&psbt).unwrap();
        let tx = OnchainAccount::extract_signed_tx(&signed).unwrap();
        assert_eq!(s.vsize, tx.weight().to_vbytes_ceil());
        assert_eq!(acct.decode_psbt(&signed).unwrap().vsize, s.vsize);
        // The fee the builder targeted is honoured (3 sat/vB, rounding).
        assert!(
            s.fee_sat >= 3 * (s.vsize - 1) && s.fee_sat <= 3 * (s.vsize + 3),
            "{s:?}"
        );
    }

    #[test]
    fn decode_psbt_rejects_an_input_amount_that_contradicts_the_wallet() {
        let mut acct = signet_account();
        fund_confirmed(&mut acct, &[500_000], 1);
        let psbt_b64 = acct
            .build_send_tx(&foreign_addr(0), Amount::from_sat(10_000), 2, false)
            .unwrap();
        let mut psbt: Psbt = psbt_b64.parse().unwrap();
        // Lie: the input claims to be worth 10x more (fee would look tiny).
        let w = psbt.inputs[0].witness_utxo.as_mut().unwrap();
        w.value = bitcoin::Amount::from_sat(5_000_000);
        let err = acct.decode_psbt(&psbt.to_string()).unwrap_err();
        assert!(matches!(err, OnchainError::InvalidPsbt(_)), "{err:?}");
    }

    #[test]
    fn decode_psbt_marks_self_send_to_receive_address_as_mine_not_change() {
        let mut acct = signet_account();
        fund_confirmed(&mut acct, &[200_000], 1);
        // Within BDK's lookahead window, so the spk index knows it.
        let own = acct.peek_taproot_address(5).unwrap().to_string();
        let psbt = acct.build_drain_tx(&own, 2, false).unwrap();
        let s = acct.decode_psbt(&psbt).unwrap();
        assert_eq!(s.outputs.len(), 1);
        assert!(s.outputs[0].is_mine && !s.outputs[0].is_change);
        assert_eq!(s.sent_external_sat, 0);
        assert_eq!(s.wallet_net_debit_sat, s.fee_sat);
    }

    // ------------------------------------------------- broadcast bookkeeping

    #[test]
    fn recorded_broadcast_prevents_reusing_its_inputs() {
        let mut acct = signet_account();
        fund_confirmed(&mut acct, &[50_000, 60_000, 70_000], 1);
        let to = foreign_addr(0);
        let a = acct
            .build_send_tx(&to, Amount::from_sat(40_000), 2, false)
            .unwrap();
        let a_inputs = inputs_of(&a);
        let a_tx = sign_and_record(&mut acct, &a);

        let listed: Vec<OutPoint> = acct
            .list_utxos()
            .unwrap()
            .into_iter()
            .map(|u| u.outpoint)
            .collect();
        for op in &a_inputs {
            assert!(!listed.contains(op), "spent input {op} still listed");
        }
        // A's change is ours and spendable.
        assert!(listed.iter().any(|op| op.txid == a_tx.compute_txid()));

        for _ in 0..8 {
            let b = acct
                .build_send_tx(&to, Amount::from_sat(40_000), 2, false)
                .unwrap();
            for op in inputs_of(&b) {
                assert!(!a_inputs.contains(&op), "B reused A's input {op}");
            }
        }
    }

    #[test]
    fn own_unconfirmed_change_stays_spendable_after_broadcast() {
        let mut acct = signet_account();
        fund_confirmed(&mut acct, &[100_000], 1);
        let a = acct
            .build_send_tx(&foreign_addr(0), Amount::from_sat(10_000), 2, false)
            .unwrap();
        let a_tx = sign_and_record(&mut acct, &a);
        // Only A's (trusted, unconfirmed) change is left: B must use it.
        let b = acct
            .build_send_tx(&foreign_addr(1), Amount::from_sat(10_000), 2, false)
            .unwrap();
        assert!(
            inputs_of(&b)
                .iter()
                .all(|op| op.txid == a_tx.compute_txid())
        );
    }

    // ------------------------------------------- unconfirmed foreign coins

    #[test]
    fn auto_selection_skips_unconfirmed_coins_from_third_parties() {
        let mut acct = signet_account();
        let foreign = fund_unconfirmed_foreign(&mut acct, 1_000_000, 9);
        let to = foreign_addr(0);
        // Only an unconfirmed third-party coin: nothing is spendable.
        assert!(matches!(
            acct.build_send_tx(&to, Amount::from_sat(10_000), 2, false),
            Err(OnchainError::TxBuild(_))
        ));
        assert!(matches!(
            acct.build_drain_tx(&to, 2, false),
            Err(OnchainError::TxBuild(_))
        ));

        let confirmed = fund_confirmed(&mut acct, &[30_000], 1);
        for _ in 0..8 {
            let psbt = acct
                .build_send_tx(&to, Amount::from_sat(20_000), 2, false)
                .unwrap();
            assert_eq!(inputs_of(&psbt), confirmed);
        }
        let drain = acct.build_drain_tx(&to, 2, false).unwrap();
        assert_eq!(inputs_of(&drain), confirmed);

        // Manual coin control may pick it, but the review is warned.
        let manual = acct
            .build_send_tx_with_utxos(&to, Amount::from_sat(10_000), 2, &[foreign], false)
            .unwrap();
        assert!(
            acct.decode_psbt(&manual)
                .unwrap()
                .spends_unconfirmed_foreign
        );
    }

    // --------------------------------------------------- frozen (BIP-329)

    #[test]
    fn list_utxos_reports_the_frozen_flag_from_bip329_labels() {
        let mut acct = signet_account();
        let ops = fund_confirmed(&mut acct, &[80_000, 90_000], 1);
        acct.add_label(&Label {
            kind: LabelKind::Output,
            target: ops[1].to_string(),
            text: "cold".into(),
            spendable: Some(false),
            category: None,
        })
        .unwrap();
        let utxos = acct.list_utxos().unwrap();
        let frozen_of = |op: OutPoint| utxos.iter().find(|u| u.outpoint == op).unwrap().frozen;
        assert!(!frozen_of(ops[0]));
        assert!(frozen_of(ops[1]));
        // Unfreezing clears the flag.
        acct.add_label(&Label {
            kind: LabelKind::Output,
            target: ops[1].to_string(),
            text: "cold".into(),
            spendable: Some(true),
            category: None,
        })
        .unwrap();
        assert!(acct.list_utxos().unwrap().iter().all(|u| !u.frozen));
    }

    #[test]
    fn frozen_outputs_are_excluded_and_refused_in_manual_selection() {
        let mut acct = signet_account();
        let ops = fund_confirmed(&mut acct, &[80_000, 90_000], 1);
        acct.add_label(&Label {
            kind: LabelKind::Output,
            target: ops[1].to_string(),
            text: "cold".into(),
            spendable: Some(false),
            category: None,
        })
        .unwrap();
        let to = foreign_addr(0);
        for _ in 0..8 {
            let psbt = acct
                .build_send_tx(&to, Amount::from_sat(20_000), 2, false)
                .unwrap();
            assert_eq!(inputs_of(&psbt), vec![ops[0]]);
        }
        assert_eq!(
            inputs_of(&acct.build_drain_tx(&to, 2, false).unwrap()),
            vec![ops[0]]
        );
        let err = acct
            .build_send_tx_with_utxos(&to, Amount::from_sat(20_000), 2, &[ops[1]], false)
            .unwrap_err();
        assert!(
            matches!(err, OnchainError::TxBuild(ref m) if m.contains("frozen")),
            "{err:?}"
        );
    }

    #[test]
    fn set_output_frozen_toggles_spendable_and_keeps_label_text() {
        let mut acct = signet_account();
        let ops = fund_confirmed(&mut acct, &[80_000, 90_000], 1);
        acct.add_label(&Label {
            kind: LabelKind::Output,
            target: ops[0].to_string(),
            text: "exchange withdrawal".into(),
            spendable: None,
            category: Some("Savings".into()),
        })
        .unwrap();
        acct.set_output_frozen(&ops[0].to_string(), true).unwrap();
        let frozen_of = |a: &OnchainAccount, op: OutPoint| {
            a.list_utxos()
                .unwrap()
                .iter()
                .any(|u| u.outpoint == op && u.frozen)
        };
        assert!(frozen_of(&acct, ops[0]));
        assert!(!frozen_of(&acct, ops[1]));
        let label = acct
            .get_label(LabelKind::Output, &ops[0].to_string())
            .unwrap()
            .unwrap();
        assert_eq!(label.text, "exchange withdrawal");
        assert_eq!(label.category.as_deref(), Some("Savings"));
        acct.set_output_frozen(&ops[0].to_string(), false).unwrap();
        assert!(!frozen_of(&acct, ops[0]));
        assert!(acct.set_output_frozen("not-an-outpoint", true).is_err());
    }

    // ------------------------------------------------------------ addresses

    #[test]
    fn list_addresses_reports_revealed_indices_usage_and_labels() {
        let mut acct = signet_account();
        assert!(acct.list_addresses(false).unwrap().is_empty());
        // Index 0 receives funds; index 1 is revealed but never used.
        fund_confirmed(&mut acct, &[50_000], 7);
        let unused = acct.reveal_next_address().unwrap();
        acct.set_address_label(&unused.to_string(), "Invoice 42", Some("Freelance".into()))
            .unwrap();

        let receive = acct.list_addresses(false).unwrap();
        assert_eq!(receive.len(), 2);
        assert_eq!(receive[0].index, 0);
        assert!(receive[0].used);
        assert!(!receive[0].is_change);
        assert_eq!(receive[1].address, unused);
        assert!(!receive[1].used);
        assert_eq!(receive[1].label.as_deref(), Some("Invoice 42"));
        assert_eq!(receive[1].category.as_deref(), Some("Freelance"));
        // Matches the manual BIP86 derivation used everywhere else.
        assert_eq!(receive[0].address, acct.peek_taproot_address(0).unwrap());
        assert!(acct.list_addresses(true).unwrap().is_empty());

        // Clearing both fields removes the label row.
        acct.set_address_label(&unused.to_string(), " ", None)
            .unwrap();
        assert_eq!(acct.list_addresses(false).unwrap()[1].label, None);
    }

    #[test]
    fn next_unused_receive_address_does_not_burn_indices() {
        let mut acct = signet_account();
        let a = acct.next_unused_receive_address().unwrap();
        let b = acct.next_unused_receive_address().unwrap();
        assert_eq!(a, b, "an unused address is handed out again");
        assert_eq!(
            acct.wallet.derivation_index(KeychainKind::External),
            Some(0)
        );
        // Once it receives funds the next call moves on.
        let tx = Transaction {
            version: bitcoin::transaction::Version::TWO,
            lock_time: bitcoin::absolute::LockTime::ZERO,
            input: vec![TxIn::default()],
            output: vec![TxOut {
                value: bitcoin::Amount::from_sat(10_000),
                script_pubkey: a.script_pubkey(),
            }],
        };
        confirm(&mut acct, tx, 5);
        let c = acct.next_unused_receive_address().unwrap();
        assert_ne!(a, c);
    }

    #[test]
    fn receive_labels_are_copied_once_onto_incoming_txs_only() {
        let mut acct = signet_account();
        fund_confirmed(&mut acct, &[300_000], 21);
        // Label-first receive: label + category on a fresh address.
        let labelled = acct.reveal_next_address().unwrap();
        acct.set_address_label(
            &labelled.to_string(),
            "Invoice 42",
            Some("Freelance".into()),
        )
        .unwrap();
        // 1. Outgoing self-transfer to the labelled address: net < 0.
        let psbt = acct
            .build_send_tx(&labelled.to_string(), Amount::from_sat(10_000), 2, false)
            .unwrap();
        let self_tx = sign_and_record(&mut acct, &psbt).compute_txid().to_string();
        // 2. Third-party payment to the labelled address (+ a second,
        //    unlabelled output to us).
        let other = acct.reveal_next_address().unwrap();
        let incoming = Transaction {
            version: Version::TWO,
            lock_time: LockTime::ZERO,
            input: vec![TxIn {
                previous_output: OutPoint::new(bitcoin::Txid::from_byte_array([77; 32]), 0),
                ..TxIn::default()
            }],
            output: vec![
                TxOut {
                    value: bitcoin::Amount::from_sat(5_000),
                    script_pubkey: other.script_pubkey(),
                },
                TxOut {
                    value: bitcoin::Amount::from_sat(25_000),
                    script_pubkey: labelled.script_pubkey(),
                },
            ],
        };
        let in_txid = incoming.compute_txid().to_string();
        confirm(&mut acct, incoming, 3);

        let applied = acct.apply_receive_labels().unwrap();
        assert_eq!(
            applied,
            vec![ReceiveLabelCopy {
                txid: in_txid.clone(),
                address: labelled.to_string(),
                label: Some("Invoice 42".into()),
                category: Some("Freelance".into()),
            }]
        );
        let tx_label = acct.get_label(LabelKind::Tx, &in_txid).unwrap().unwrap();
        assert_eq!(tx_label.text, "Invoice 42");
        assert_eq!(tx_label.category.as_deref(), Some("Freelance"));
        assert!(
            acct.get_label(LabelKind::Tx, &self_tx).unwrap().is_none(),
            "an outgoing self-transfer is not relabelled"
        );

        // The user clears the label: it is never re-applied.
        acct.remove_label(LabelKind::Tx, &in_txid).unwrap();
        assert!(acct.apply_receive_labels().unwrap().is_empty());
        assert!(acct.get_label(LabelKind::Tx, &in_txid).unwrap().is_none());
    }

    #[test]
    fn receive_label_copy_keeps_an_existing_tx_label_and_picks_the_lowest_output() {
        let mut acct = signet_account();
        let a = acct.reveal_next_address().unwrap();
        let b = acct.reveal_next_address().unwrap();
        acct.set_address_label(&a.to_string(), "A", Some("Cat A".into()))
            .unwrap();
        acct.set_address_label(&b.to_string(), "B", Some("Cat B".into()))
            .unwrap();
        let tx = Transaction {
            version: Version::TWO,
            lock_time: LockTime::ZERO,
            input: vec![TxIn {
                previous_output: OutPoint::new(bitcoin::Txid::from_byte_array([78; 32]), 0),
                ..TxIn::default()
            }],
            output: vec![
                TxOut {
                    value: bitcoin::Amount::from_sat(1_000),
                    script_pubkey: b.script_pubkey(),
                },
                TxOut {
                    value: bitcoin::Amount::from_sat(2_000),
                    script_pubkey: a.script_pubkey(),
                },
            ],
        };
        let txid = tx.compute_txid().to_string();
        confirm(&mut acct, tx, 4);
        acct.add_label(&Label {
            kind: LabelKind::Tx,
            target: txid.clone(),
            text: "Manual".into(),
            spendable: None,
            category: None,
        })
        .unwrap();
        let applied = acct.apply_receive_labels().unwrap();
        // Output 0 pays `b`: deterministic choice; manual text kept.
        assert_eq!(applied.len(), 1);
        assert_eq!(applied[0].address, b.to_string());
        assert_eq!(applied[0].label, None);
        assert_eq!(applied[0].category.as_deref(), Some("Cat B"));
        let label = acct.get_label(LabelKind::Tx, &txid).unwrap().unwrap();
        assert_eq!(label.text, "Manual");
        assert_eq!(label.category.as_deref(), Some("Cat B"));
    }

    #[test]
    fn chain_tip_height_follows_the_synced_checkpoint() {
        let mut acct = signet_account();
        assert_eq!(acct.chain_tip_height(), 0);
        let tx = incoming_tx(&mut acct, &[10_000], 3);
        confirm(&mut acct, tx, 42);
        assert_eq!(acct.chain_tip_height(), 42);
    }

    // ----------------------------------------------------------------- CPFP

    /// Own unconfirmed send with change AND a second output paid to
    /// one of our own receive addresses; returns (parent txid, own
    /// receive outpoint, change outpoint).
    fn parent_with_two_own_outputs(acct: &mut OnchainAccount) -> (Txid, OutPoint, OutPoint) {
        fund_confirmed(acct, &[300_000], 1);
        let own = acct.reveal_next_address().unwrap().to_string();
        let psbt = acct
            .build_send_tx_batch(
                &[
                    (foreign_addr(0), Amount::from_sat(50_000)),
                    (own.clone(), Amount::from_sat(20_000)),
                ],
                1,
                false,
            )
            .unwrap();
        let parent = sign_and_record(acct, &psbt);
        let txid = parent.compute_txid();
        let mut own_op = None;
        let mut change_op = None;
        for u in acct
            .wallet
            .list_unspent()
            .filter(|u| u.outpoint.txid == txid)
        {
            if u.keychain == KeychainKind::Internal {
                change_op = Some(u.outpoint);
            } else {
                own_op = Some(u.outpoint);
            }
        }
        (txid, own_op.unwrap(), change_op.unwrap())
    }

    #[test]
    fn cpfp_never_spends_a_frozen_parent_output() {
        let mut acct = signet_account();
        let (parent, own, change) = parent_with_two_own_outputs(&mut acct);
        // Unfrozen: both wallet outputs of the parent are swept.
        let plan = acct
            .build_cpfp_psbt(&parent.to_string(), 10, false)
            .unwrap();
        assert_eq!(inputs_of(&plan.psbt).len(), 2);

        acct.set_output_frozen(&own.to_string(), true).unwrap();
        let plan = acct
            .build_cpfp_psbt(&parent.to_string(), 10, false)
            .unwrap();
        assert_eq!(
            inputs_of(&plan.psbt),
            vec![change],
            "frozen output must stay put"
        );

        acct.set_output_frozen(&change.to_string(), true).unwrap();
        let err = acct
            .build_cpfp_psbt(&parent.to_string(), 10, false)
            .unwrap_err();
        assert!(
            matches!(err, OnchainError::TxBuild(ref m) if m.contains("non-frozen")),
            "{err:?}"
        );
    }

    #[test]
    fn repeated_cpfp_builds_do_not_burn_internal_indices() {
        let mut acct = signet_account();
        let (parent, _, _) = parent_with_two_own_outputs(&mut acct);
        let first = acct
            .build_cpfp_psbt(&parent.to_string(), 10, false)
            .unwrap();
        let index_after_first = acct.wallet.derivation_index(KeychainKind::Internal);
        for rate in [12, 15, 20] {
            acct.build_cpfp_psbt(&parent.to_string(), rate, false)
                .unwrap();
        }
        assert_eq!(
            acct.wallet.derivation_index(KeychainKind::Internal),
            index_after_first
        );
        // Same drain address every time while the child is not broadcast.
        let out = |psbt: &str| -> bitcoin::ScriptBuf {
            let p: Psbt = psbt.parse().unwrap();
            p.unsigned_tx.output[0].script_pubkey.clone()
        };
        let again = acct
            .build_cpfp_psbt(&parent.to_string(), 30, false)
            .unwrap();
        assert_eq!(out(&first.psbt), out(&again.psbt));
    }

    #[test]
    fn cpfp_rejects_a_confirmed_parent() {
        let mut acct = signet_account();
        let ops = fund_confirmed(&mut acct, &[80_000], 1);
        let err = acct
            .build_cpfp_psbt(&ops[0].txid.to_string(), 10, false)
            .unwrap_err();
        assert!(
            matches!(err, OnchainError::TxBuild(ref m) if m.contains("already confirmed")),
            "{err:?}"
        );
    }

    #[test]
    fn cpfp_child_brings_the_package_to_the_target_rate_and_pays_change() {
        let mut acct = signet_account();
        fund_confirmed(&mut acct, &[300_000], 1);
        let parent_psbt = acct
            .build_send_tx(&foreign_addr(0), Amount::from_sat(50_000), 1, false)
            .unwrap();
        let parent = sign_and_record(&mut acct, &parent_psbt);
        let parent_txid = parent.compute_txid();
        let parent_fee = acct.wallet.calculate_fee(&parent).unwrap().to_sat();
        let parent_vsize = parent.weight().to_vbytes_ceil();

        let target = 20;
        let plan = acct
            .build_cpfp_psbt(&parent_txid.to_string(), target, false)
            .unwrap();
        assert_eq!(plan.parent_txid, parent_txid.to_string());
        assert_eq!(plan.parent_fee_sat, parent_fee);
        assert_eq!(plan.parent_vsize, parent_vsize);
        assert_eq!(plan.ancestor_count, 0);
        assert_eq!(
            plan.child_fee_sat,
            target * (parent_vsize + plan.child_vsize) - parent_fee
        );

        let s = acct.decode_psbt(&plan.psbt).unwrap();
        assert_eq!(s.fee_sat, plan.child_fee_sat);
        assert_eq!(s.vsize, plan.child_vsize);
        assert!(
            inputs_of(&plan.psbt)
                .iter()
                .all(|op| op.txid == parent_txid)
        );
        assert_eq!(s.outputs.len(), 1);
        assert!(
            s.outputs[0].is_mine && s.outputs[0].is_change,
            "child drains to change: {s:?}"
        );
        // Package rate reaches the target.
        let package_fee = parent_fee + s.fee_sat;
        let package_vsize = parent_vsize + s.vsize;
        assert!(package_fee >= target * package_vsize);
    }

    /// Send `amount` to a third party, spending exactly `inputs`
    /// (unconfirmed change allowed via manual selection); returns the
    /// signed, recorded tx.
    fn chain_send(acct: &mut OnchainAccount, inputs: &[OutPoint], rate: u64) -> Transaction {
        let psbt = acct
            .build_send_tx_with_utxos(
                &foreign_addr(0),
                Amount::from_sat(20_000),
                rate,
                inputs,
                false,
            )
            .unwrap();
        sign_and_record(acct, &psbt)
    }

    fn change_of(acct: &OnchainAccount, tx: &Transaction) -> OutPoint {
        let txid = tx.compute_txid();
        acct.wallet
            .list_unspent()
            .find(|u| u.outpoint.txid == txid)
            .unwrap()
            .outpoint
    }

    #[test]
    fn cpfp_counts_every_unconfirmed_ancestor_in_a_multi_level_chain() {
        let mut acct = signet_account();
        let funding = fund_confirmed(&mut acct, &[400_000], 1);
        // grandparent (low fee) -> parent -> child-of-parent chain:
        // confirmed funding -> A (1 sat/vB) -> B (2 sat/vB) -> C (3 sat/vB).
        let a = chain_send(&mut acct, &funding, 1);
        let a_change = change_of(&acct, &a);
        let b = chain_send(&mut acct, &[a_change], 2);
        let b_change = change_of(&acct, &b);
        let c = chain_send(&mut acct, &[b_change], 3);
        let fee = |t: &Transaction| acct.wallet.calculate_fee(t).unwrap().to_sat();
        let vs = |t: &Transaction| t.weight().to_vbytes_ceil();
        let (fa, fb, fc) = (fee(&a), fee(&b), fee(&c));
        let (va, vb, vc) = (vs(&a), vs(&b), vs(&c));

        let target = 25;
        let plan = acct
            .build_cpfp_psbt(&c.compute_txid().to_string(), target, false)
            .unwrap();
        assert_eq!(plan.parent_fee_sat, fc);
        assert_eq!(plan.parent_vsize, vc);
        assert_eq!(plan.ancestor_count, 2);
        assert_eq!(plan.ancestors_fee_sat, fa + fb);
        assert_eq!(plan.ancestors_vsize, va + vb);
        assert_eq!(
            plan.child_fee_sat,
            target * (va + vb + vc + plan.child_vsize) - (fa + fb + fc)
        );
        let s = acct.decode_psbt(&plan.psbt).unwrap();
        assert!((fa + fb + fc + s.fee_sat) >= target * (va + vb + vc + s.vsize));

        // Direct-parent-only math would have under-paid the real package.
        let naive = target * (vc + plan.child_vsize) - fc;
        assert!(plan.child_fee_sat > naive);
    }

    #[test]
    fn cpfp_stops_walking_at_a_confirmed_ancestor() {
        let mut acct = signet_account();
        let funding = fund_confirmed(&mut acct, &[400_000], 1);
        let a = chain_send(&mut acct, &funding, 1);
        let plan = acct
            .build_cpfp_psbt(&a.compute_txid().to_string(), 10, false)
            .unwrap();
        assert_eq!(plan.ancestor_count, 0);
        assert_eq!((plan.ancestors_fee_sat, plan.ancestors_vsize), (0, 0));
    }

    #[test]
    fn cpfp_is_refused_when_an_unconfirmed_ancestor_fee_is_unknown() {
        let mut acct = signet_account();
        // Unconfirmed incoming whose own inputs we cannot see -> its fee
        // is unknowable; the parent spending it must not be sized.
        let foreign = fund_unconfirmed_foreign(&mut acct, 100_000, 3);
        let parent = chain_send(&mut acct, &[foreign], 2);
        let err = acct
            .build_cpfp_psbt(&parent.compute_txid().to_string(), 10, false)
            .unwrap_err();
        assert!(
            matches!(err, OnchainError::TxBuild(ref m) if m.contains("ancestor") && m.contains("fee unknown")),
            "{err:?}"
        );
    }

    #[test]
    fn cpfp_on_a_parent_with_unknown_fee_is_refused() {
        let mut acct = signet_account();
        // Incoming from a third party whose inputs we do not know.
        let op = fund_unconfirmed_foreign(&mut acct, 100_000, 3);
        let err = acct
            .build_cpfp_psbt(&op.txid.to_string(), 10, false)
            .unwrap_err();
        assert!(
            matches!(err, OnchainError::TxBuild(ref m) if m.contains("fee unknown")),
            "{err:?}"
        );
    }

    // ------------------------------------------------------------- contacts

    #[test]
    fn add_contact_validates_address_and_network() {
        let acct = signet_account();
        assert!(matches!(
            acct.add_contact("x", "not-an-address"),
            Err(OnchainError::InvalidAddress(_))
        ));
        // Mainnet address on a signet wallet.
        assert!(matches!(
            acct.add_contact(
                "x",
                "bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr"
            ),
            Err(OnchainError::InvalidAddress(_))
        ));
        assert!(matches!(
            acct.add_contact("   ", &foreign_addr(0)),
            Err(OnchainError::InvalidArgument(_))
        ));
        let id = acct
            .add_contact("  Carol ", &format!(" {} ", foreign_addr(0)))
            .unwrap();
        let c = acct
            .list_contacts()
            .unwrap()
            .into_iter()
            .find(|c| c.id == id)
            .unwrap();
        assert_eq!(c.label, "Carol");
        assert_eq!(c.address, foreign_addr(0));
    }
}
