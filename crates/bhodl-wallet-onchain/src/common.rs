//! Building blocks shared by the watch-only account and the registry:
//! the `SQLCipher` connection opener, the (optionally Tor-routed) Esplora
//! client, and the plain data types the FFI flattens.

use std::path::Path;

use bhodl_core::{Amount, Network as BhodlNetwork};
use bitcoin::{Address, Network as BtcNetwork, OutPoint};
use rusqlite::Connection;
use zeroize::Zeroizing;

use crate::contacts::CREATE_CONTACTS_TABLE_SQL;
use crate::error::OnchainError;
use crate::labels::{self, CREATE_LABELS_TABLE_SQL};

/// Length of the raw AES-256 key `SQLCipher` consumes via `PRAGMA key`.
pub const ENCRYPTION_KEY_LEN: usize = 32;

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

/// Public descriptors of an HD watch wallet (receive + change keychains),
/// as exported by [`crate::WatchAccount::export_descriptors`]. Importing
/// them into another descriptor wallet (Sparrow, Liana, Specter Desktop,
/// BDK CLI) yields the same watch-only view.
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
#[cfg(test)]
pub(crate) fn try_build_esplora_client(
    esplora_url: &str,
    proxy_url: Option<&str>,
) -> Result<EsploraClient, OnchainError> {
    try_build_esplora_client_scoped(esplora_url, proxy_url, ESPLORA_MISC_SCOPE)
}

/// Isolation scope of Esplora traffic that is not tied to one wallet
/// (fee estimates, connectivity checks).
#[cfg(test)]
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
#[cfg(test)]
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

/// A canonical wallet transaction, flattened for the FFI.
///
/// Returned by [`crate::WatchAccount::list_transactions`]. Captures only the
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

/// An unspent output held by a watched script, as listed by
/// [`crate::WatchAccount::list_utxos`].
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
    /// `false` for the external (receive) keychain and for address-list
    /// watch wallets.
    pub is_change: bool,
    /// `true` once the UTXO has at least one confirmation; `false` while
    /// it is still in the mempool.
    pub confirmed: bool,
}

/// One revealed address of the wallet, as listed by
/// [`crate::WatchAccount::list_addresses`].
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
/// [`crate::WatchAccount::apply_receive_labels`]. `label` / `category` are
/// what was actually written (`None` = the transaction already had one).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ReceiveLabelCopy {
    pub txid: String,
    pub address: String,
    pub label: Option<String>,
    pub category: Option<String>,
}

/// Maximum byte length of an address-book label.
const MAX_CONTACT_LABEL_BYTES: usize = 256;

/// Trim + bound an address-book label.
pub(crate) fn validated_contact_label(label: &str) -> Result<&str, OnchainError> {
    let label = label.trim();
    if label.is_empty() || label.len() > MAX_CONTACT_LABEL_BYTES {
        return Err(OnchainError::InvalidArgument(format!(
            "contact label must be 1..={MAX_CONTACT_LABEL_BYTES} bytes"
        )));
    }
    Ok(label)
}

/// Create the BIP329 labels table on the supplied connection if it does
/// not yet exist. Called on every watch-wallet open.
pub(crate) fn ensure_labels_table(conn: &Connection) -> Result<(), OnchainError> {
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
/// user has saved (address book).
pub(crate) fn ensure_contacts_table(conn: &Connection) -> Result<(), OnchainError> {
    conn.execute_batch(CREATE_CONTACTS_TABLE_SQL)
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    Ok(())
}

pub(crate) fn map_network(n: BhodlNetwork) -> BtcNetwork {
    match n {
        BhodlNetwork::Mainnet => BtcNetwork::Bitcoin,
        // Must be the real testnet4 params (genesis, magic). Mapping to
        // `Testnet` (testnet3) made BDK anchor chain state to the wrong
        // genesis; legacy DBs are handled by the watch-wallet auto-rebuild.
        BhodlNetwork::Testnet4 => BtcNetwork::Testnet4,
        BhodlNetwork::Signet => BtcNetwork::Signet,
        BhodlNetwork::Regtest => BtcNetwork::Regtest,
        _ => unreachable!(
            "bhodl-core gained a Network variant without updating bhodl-wallet-onchain"
        ),
    }
}

#[cfg(test)]
mod uc_security_tests {
    use super::*;

    // SEC-116
    #[test]
    fn esplora_timeout_is_larger_only_when_a_proxy_is_configured() {
        assert_eq!(esplora_timeout_secs(false), 30);
        assert_eq!(esplora_timeout_secs(true), 90);
        assert!(
            esplora_timeout_secs(true) > crate::tor::SOCKS_CONNECT_TIMEOUT.as_secs(),
            "the client must outlive the bridge's own SOCKS connect timeout",
        );
    }
}
