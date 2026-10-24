//! Watch-only wallet accounts — Phase 3.1.
//!
//! Encrypted with the device key (Argon2id-derived AES-256). Holds
//! **public** material only — descriptors are watch-only.
//!
//! Three construction modes mirror the three watch-only registry kinds:
//!
//! | Constructor | Registry kind | Backend |
//! |---|---|---|
//! | [`WatchAccount::create_from_xpub`] | `WatchXpub` | BDK wallet, `tr/wpkh/sh(wpkh)(<xpub>/0/*)` |
//! | [`WatchAccount::create_from_hd`] | `WatchHd` | same but with origin `[fingerprint/path]` |
//! | [`WatchAccount::create_from_addresses`] | `WatchAddresses` | script-pubkey tracker (no descriptor) |
//!
//! ## Address-based wallets — design note
//!
//! BDK 3.0 / miniscript 12 support neither the `addr()` nor the `rawtr()`
//! descriptor fragment, so no BDK descriptor can reproduce an arbitrary
//! address' script pubkey. (An earlier revision synthesised `tr(<output
//! key>)` from a P2TR address: BDK then applied the BIP-341 tweak a second
//! time, the watched script never matched the user's address, and the
//! balance stayed at 0 forever.)
//!
//! Address wallets therefore bypass `bdk_wallet::Wallet` and use the
//! lower-level `bdk_chain` primitives directly: an
//! [`IndexedTxGraph`] over a [`SpkTxOutIndex`] holding exactly the
//! `script_pubkey()` of every watched address, plus a [`LocalChain`].
//! Sync uses Esplora's spk-based `sync`, and both changesets are persisted
//! into the same encrypted DB through `bdk_chain`'s rusqlite store. This
//! supports **any** standard address type and **any number** of addresses
//! per watch wallet.
//!
//! The address list lives in the `bhodl_watch_address` table; its presence
//! is what marks a DB as an address wallet on [`WatchAccount::load`]. DBs
//! written by the old `tr()` code are migrated transparently: the stale
//! `bdk_wallet` descriptor row is simply ignored and the (shared-schema)
//! chain + tx-graph tables are reused.

use std::path::Path;
use std::str::FromStr;

use bdk_chain::{
    CanonicalizationParams, ConfirmationBlockTime, IndexedTxGraph, Merge,
    local_chain::{self, LocalChain},
    spk_client::{FullScanRequest, SyncRequest, SyncResponse},
    spk_txout::SpkTxOutIndex,
    tx_graph,
};
use bdk_esplora::EsploraExt;
use bdk_wallet::{KeychainKind, PersistedWallet, Wallet};
use bhodl_core::{Amount, Network as BhodlNetwork};
use bitcoin::{
    Address,
    bip32::{ChildNumber, DerivationPath, Xpub},
};
use rusqlite::Connection;

use std::collections::HashMap;

use crate::common::{
    DerivedAddress, DescriptorBackup, ENCRYPTION_KEY_LEN, ReceiveLabelCopy, TxRecord, UtxoInfo,
    ensure_contacts_table, ensure_labels_table, map_network, open_encrypted_connection,
    try_build_esplora_client_scoped, validated_contact_label, wallet_isolation_scope,
};
use crate::contacts::{self, Contact};
use crate::error::OnchainError;
use crate::labels::{self, Label, LabelKind};

// ---------------------------------------------------------------------------
// Public type: WatchBip
// ---------------------------------------------------------------------------

/// Script type / BIP path used for a simple xpub watch wallet.
///
/// Maps to the descriptor template (recv keychain // change keychain):
/// - `Taproot` (BIP-86): `tr(xpub/0/i)` // `tr(xpub/1/i)`
/// - `NativeSegwit` (BIP-84): `wpkh(xpub/0/i)` // `wpkh(xpub/1/i)`
/// - `NestedSegwit` (BIP-49): `sh(wpkh(xpub/0/i))` // `sh(wpkh(xpub/1/i))`
///
/// Where `i` is the address index in the keychain.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WatchBip {
    /// BIP-86 — Pay-to-Taproot single key path.
    Taproot,
    /// BIP-84 — Pay-to-Witness-Public-Key-Hash (native `SegWit`).
    NativeSegwit,
    /// BIP-49 — Pay-to-Script-Hash wrapping P2WPKH (nested `SegWit`).
    NestedSegwit,
}

// ---------------------------------------------------------------------------
// Lock-friendly sync phases (audit M5)
// ---------------------------------------------------------------------------

/// Scan request snapshotted by [`WatchAccount::start_esplora_scan`].
///
/// The `String` is the Tor stream-isolation scope (in-memory key only).
pub enum PendingWatchScan {
    /// HD (descriptor) watch wallet: full scan with stop-gap.
    Full(FullScanRequest<KeychainKind>, String),
    /// Address-list watch wallet: plain spk sync.
    Spks(SyncRequest<u32>, String),
}

impl std::fmt::Debug for PendingWatchScan {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Self::Full(..) => "PendingWatchScan::Full",
            Self::Spks(..) => "PendingWatchScan::Spks",
        })
    }
}

impl PendingWatchScan {
    /// Run the network phase with no wallet lock held.
    ///
    /// # Errors
    ///
    /// [`OnchainError::EsploraSync`] on transport / server failure,
    /// [`OnchainError::InvalidArgument`] on a malformed URL or proxy.
    pub fn fetch(
        self,
        esplora_url: &str,
        proxy_url: Option<&str>,
    ) -> Result<WatchScanUpdate, OnchainError> {
        let scope = match &self {
            Self::Full(_, s) | Self::Spks(_, s) => s.clone(),
        };
        let client = try_build_esplora_client_scoped(esplora_url, proxy_url, &scope)?;
        match self {
            Self::Full(request, _) => client
                .full_scan(request, 20, 4)
                .map(|r| WatchScanUpdate::Full(r.into()))
                .map_err(|e| OnchainError::EsploraSync(e.to_string())),
            Self::Spks(request, _) => client
                .sync(request, 4)
                .map(WatchScanUpdate::Spks)
                .map_err(|e| OnchainError::EsploraSync(e.to_string())),
        }
    }
}

/// Fetched update, applied with [`WatchAccount::apply_esplora_scan`].
pub enum WatchScanUpdate {
    /// Descriptor-wallet update.
    Full(bdk_wallet::Update),
    /// Address-list sync response.
    Spks(SyncResponse),
}

impl std::fmt::Debug for WatchScanUpdate {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Self::Full(_) => "WatchScanUpdate::Full",
            Self::Spks(_) => "WatchScanUpdate::Spks",
        })
    }
}

// ---------------------------------------------------------------------------
// Public type: WatchAccount
// ---------------------------------------------------------------------------

/// A read-only wallet.
///
/// Holds public material only — no mnemonic, no xpriv, no signing
/// capability. Backed by a `SQLCipher`-encrypted `SQLite` file keyed with
/// the Argon2id-derived device key.
///
/// HD watch wallets are BDK descriptor wallets; address-list watch wallets
/// are plain script-pubkey trackers (see the module docs).
pub struct WatchAccount {
    network: BhodlNetwork,
    backend: WatchBackend,
    db_conn: Connection,
}

/// Storage/indexing engine behind a [`WatchAccount`].
enum WatchBackend {
    /// xpub / HD watch wallet — full BDK descriptor wallet.
    Descriptor(Box<PersistedWallet<Connection>>),
    /// Address-list watch wallet — raw script-pubkey tracking.
    Scripts(Box<ScriptWatch>),
}

/// Script-pubkey tracker for address-list watch wallets.
///
/// Index `i` in the [`SpkTxOutIndex`] is the address' position in the
/// `bhodl_watch_address` table.
struct ScriptWatch {
    chain: LocalChain,
    graph: IndexedTxGraph<ConfirmationBlockTime, SpkTxOutIndex<u32>>,
}

impl std::fmt::Debug for WatchAccount {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("WatchAccount")
            .field("network", &self.network)
            .finish_non_exhaustive()
    }
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

/// BIP-32 version bytes of a mainnet `xpub`.
const XPUB_VERSION: [u8; 4] = [0x04, 0x88, 0xB2, 0x1E];
/// BIP-32 version bytes of a test-network `tpub`.
const TPUB_VERSION: [u8; 4] = [0x04, 0x35, 0x87, 0xCF];

/// Normalise a SLIP-132 extended public key to its BIP-32 form.
///
/// `zpub`/`vpub` (BIP-84) and `ypub`/`upub` (BIP-49) carry the script type
/// in their version bytes; rust-bitcoin only parses `xpub`/`tpub`. We swap
/// the 4 version bytes and return the implied [`WatchBip`] so the caller
/// can reject a contradictory script-type request. Multisig variants
/// (`Zpub`/`Ypub`/`Vpub`/`Upub`) are rejected — BHODL only builds
/// single-sig watch descriptors. Plain `xpub`/`tpub` pass through
/// untouched with no implied script type.
pub(crate) fn normalize_xpub(xpub: &str) -> Result<(String, Option<WatchBip>), OnchainError> {
    let xpub = xpub.trim();
    let (target, implied) = match xpub.get(..4).unwrap_or("") {
        "zpub" => (XPUB_VERSION, WatchBip::NativeSegwit),
        "ypub" => (XPUB_VERSION, WatchBip::NestedSegwit),
        "vpub" => (TPUB_VERSION, WatchBip::NativeSegwit),
        "upub" => (TPUB_VERSION, WatchBip::NestedSegwit),
        p @ ("Zpub" | "Ypub" | "Vpub" | "Upub") => {
            return Err(OnchainError::Descriptor(format!(
                "{p} is a SLIP-132 multisig key; only single-sig watch wallets are supported"
            )));
        }
        _ => return Ok((xpub.to_string(), None)),
    };
    let mut data = bitcoin::base58::decode_check(xpub)
        .map_err(|e| OnchainError::Descriptor(format!("invalid extended public key: {e}")))?;
    if data.len() != 78 {
        return Err(OnchainError::Descriptor(format!(
            "invalid extended public key: expected 78 bytes, got {}",
            data.len()
        )));
    }
    data[..4].copy_from_slice(&target);
    Ok((bitcoin::base58::encode_check(&data), Some(implied)))
}

/// Reject a SLIP-132 key whose embedded script type contradicts `requested`.
fn check_implied_bip(implied: Option<WatchBip>, requested: WatchBip) -> Result<(), OnchainError> {
    match implied {
        Some(b) if b != requested => Err(OnchainError::Descriptor(format!(
            "extended key prefix implies {b:?} but {requested:?} was requested"
        ))),
        _ => Ok(()),
    }
}

/// Build external + internal descriptor strings for a plain xpub watch wallet.
///
/// `xpub` must already be a BIP-32 `xpub`/`tpub` (see [`normalize_xpub`]).
pub(crate) fn xpub_descriptors(xpub: &str, bip: WatchBip) -> (String, String) {
    match bip {
        WatchBip::Taproot => (format!("tr({xpub}/0/*)"), format!("tr({xpub}/1/*)")),
        WatchBip::NativeSegwit => (format!("wpkh({xpub}/0/*)"), format!("wpkh({xpub}/1/*)")),
        WatchBip::NestedSegwit => (
            format!("sh(wpkh({xpub}/0/*))"),
            format!("sh(wpkh({xpub}/1/*))"),
        ),
    }
}

/// Validate an xpub (SLIP-132 accepted) against `network` and build the
/// watch descriptors for `bip`. Single entry point shared by
/// [`WatchAccount::create_from_xpub`] and the registry so both persist the
/// same normalised descriptor.
pub(crate) fn xpub_watch_descriptors(
    xpub: &str,
    network: BhodlNetwork,
    bip: WatchBip,
) -> Result<(String, String), OnchainError> {
    let (normalized, implied) = normalize_xpub(xpub)?;
    check_implied_bip(implied, bip)?;
    validate_xpub_network(&normalized, network)?;
    Ok(xpub_descriptors(&normalized, bip))
}

/// Build external + internal descriptor strings for a full HD wallet.
///
/// The template is selected from the **purpose** level — the *first*
/// component of `derivation_path`, which must be hardened (BIP-43). Path
/// `m/86'/coin'/account'` → purpose 86.
///
/// Validation:
/// - `master_fingerprint` must be exactly 8 hex characters.
/// - `derivation_path` must parse as a BIP-32 path (leading `m/` optional).
/// - The path depth must equal the xpub's encoded depth (catches an xpub
///   exported at a different level than the declared path).
/// - Purpose must be 86/84/49; a SLIP-132 `zpub`/`ypub` must agree with it.
///
/// Returns `Err(Descriptor)` on any violation.
pub(crate) fn hd_descriptors(
    master_fingerprint: &str,
    derivation_path: &str,
    xpub: &str,
) -> Result<(String, String), OnchainError> {
    let fp = master_fingerprint.trim();
    if fp.len() != 8 || !fp.chars().all(|c| c.is_ascii_hexdigit()) {
        return Err(OnchainError::Descriptor(format!(
            "master fingerprint must be 8 hex characters, got {fp:?}"
        )));
    }
    let fp = fp.to_ascii_lowercase();

    let path_str = derivation_path.trim();
    let parsed = DerivationPath::from_str(path_str).map_err(|e| {
        OnchainError::Descriptor(format!("invalid derivation path {path_str:?}: {e}"))
    })?;
    if parsed.is_empty() {
        return Err(OnchainError::Descriptor(
            "derivation path must not be the master (m) — expected m/<purpose>'/<coin>'/<account>'"
                .into(),
        ));
    }
    // Normalise: strip optional leading "m/" (keeps the user's ' vs h notation).
    let path = path_str.trim_start_matches("m/");

    let (normalized_xpub, implied) = normalize_xpub(xpub)?;
    let parsed_xpub = Xpub::from_str(&normalized_xpub)
        .map_err(|e| OnchainError::Descriptor(format!("invalid xpub: {e}")))?;
    if usize::from(parsed_xpub.depth) != parsed.len() {
        return Err(OnchainError::Descriptor(format!(
            "derivation path depth {} does not match xpub depth {}",
            parsed.len(),
            parsed_xpub.depth
        )));
    }

    // Purpose = FIRST component, and it must be hardened (BIP-43).
    let purpose = match parsed[0] {
        ChildNumber::Hardened { index } => index,
        ChildNumber::Normal { .. } => {
            return Err(OnchainError::Descriptor(
                "purpose (first path component) must be hardened".into(),
            ));
        }
    };

    let origin = format!("[{fp}/{path}]");
    let x = normalized_xpub;

    let bip = match purpose {
        86 => WatchBip::Taproot,
        84 => WatchBip::NativeSegwit,
        49 => WatchBip::NestedSegwit,
        other => {
            return Err(OnchainError::Descriptor(format!(
                "unsupported BIP path purpose {other}' — \
                 only BIP-86/84/49 are supported in Phase 3.1"
            )));
        }
    };
    check_implied_bip(implied, bip)?;

    Ok(match bip {
        WatchBip::Taproot => (
            format!("tr({origin}{x}/0/*)"),
            format!("tr({origin}{x}/1/*)"),
        ),
        WatchBip::NativeSegwit => (
            format!("wpkh({origin}{x}/0/*)"),
            format!("wpkh({origin}{x}/1/*)"),
        ),
        WatchBip::NestedSegwit => (
            format!("sh(wpkh({origin}{x}/0/*))"),
            format!("sh(wpkh({origin}{x}/1/*))"),
        ),
    })
}

/// Validate the xpub against `network` and build the HD watch descriptors.
pub(crate) fn hd_watch_descriptors(
    master_fingerprint: &str,
    derivation_path: &str,
    xpub: &str,
    network: BhodlNetwork,
) -> Result<(String, String), OnchainError> {
    let (normalized, _) = normalize_xpub(xpub)?;
    validate_xpub_network(&normalized, network)?;
    hd_descriptors(master_fingerprint, derivation_path, xpub)
}

/// Validate that the xpub's network byte is consistent with `network`.
///
/// Bitcoin mainnet uses a distinct version byte (`xpub`) while testnet,
/// signet, and regtest share `tpub`. We only check mainnet-vs-other.
/// SLIP-132 keys must be normalised first (see [`normalize_xpub`]).
pub(crate) fn validate_xpub_network(
    xpub_str: &str,
    network: BhodlNetwork,
) -> Result<Xpub, OnchainError> {
    let xpub = Xpub::from_str(xpub_str)
        .map_err(|e| OnchainError::Descriptor(format!("invalid xpub: {e}")))?;

    let is_mainnet_xpub = xpub.network == bitcoin::NetworkKind::Main;
    let is_mainnet_net = matches!(network, BhodlNetwork::Mainnet);

    if is_mainnet_xpub != is_mainnet_net {
        return Err(OnchainError::Descriptor(format!(
            "xpub network ({}) does not match wallet network ({network:?})",
            if is_mainnet_xpub {
                "mainnet"
            } else {
                "testnet/signet/regtest"
            }
        )));
    }
    Ok(xpub)
}

/// Parse and network-validate a watch address.
///
/// Any standard address type is accepted (P2PKH, P2SH, P2WPKH, P2WSH,
/// P2TR, future witness versions): address wallets track the raw
/// `script_pubkey()`, so no descriptor is needed.
///
/// Returns `Err(InvalidAddress)` for addresses that fail to parse or
/// whose network does not match `network`.
pub(crate) fn validate_watch_address(
    addr_str: &str,
    network: BhodlNetwork,
) -> Result<Address, OnchainError> {
    let btc_net = map_network(network);
    let parsed: Address<bitcoin::address::NetworkUnchecked> = addr_str
        .trim()
        .parse()
        .map_err(|e: bitcoin::address::ParseError| OnchainError::InvalidAddress(e.to_string()))?;
    parsed
        .require_network(btc_net)
        .map_err(|e| OnchainError::InvalidAddress(e.to_string()))
}

/// Create or load a `PersistedWallet` with two descriptor strings.
///
/// If the DB is empty, creates fresh. If a wallet already exists and the
/// descriptors match, loads it. Descriptor mismatch → `Storage` error.
fn open_or_create_wallet(
    conn: &mut Connection,
    desc_ext: String,
    desc_int: String,
    btc_net: bitcoin::Network,
) -> Result<PersistedWallet<Connection>, OnchainError> {
    let loaded = load_descriptor_wallet(conn, Some((desc_ext.clone(), desc_int.clone())), btc_net)?;

    match loaded {
        Some(w) => Ok(w),
        None => Wallet::create(desc_ext, desc_int)
            .network(btc_net)
            .create_wallet(conn)
            .map_err(|e| OnchainError::WalletCreate(e.to_string())),
    }
}

/// `true` when a BDK load failed only because the DB was written by the
/// pre-fix build that mapped testnet4 onto testnet3 parameters.
fn is_testnet3_db_on_testnet4<E>(e: &bdk_wallet::LoadWithPersistError<E>) -> bool {
    matches!(
        e,
        bdk_wallet::LoadWithPersistError::InvalidChangeSet(bdk_wallet::LoadError::Mismatch(
            bdk_wallet::LoadMismatch::Network {
                loaded: bitcoin::Network::Testnet,
                expected: bitcoin::Network::Testnet4,
            }
        ))
    )
}

/// Load a descriptor wallet, transparently rebuilding a legacy testnet4
/// DB that was persisted with testnet3 network/genesis.
///
/// Watch wallets hold no secrets: the stored public descriptors are read
/// back, every BDK table is dropped, and the wallet is re-created on the
/// correct network. The next sync repopulates chain state from scratch.
fn load_descriptor_wallet(
    conn: &mut Connection,
    expect: Option<(String, String)>,
    btc_net: bitcoin::Network,
) -> Result<Option<PersistedWallet<Connection>>, OnchainError> {
    let mut params = Wallet::load();
    if let Some((ext, int)) = expect {
        params = params
            .descriptor(KeychainKind::External, Some(ext))
            .descriptor(KeychainKind::Internal, Some(int));
    }
    match params.check_network(btc_net).load_wallet(conn) {
        Ok(w) => Ok(w),
        Err(e) if is_testnet3_db_on_testnet4(&e) => {
            rebuild_descriptor_wallet_on(conn, btc_net).map(Some)
        }
        Err(e) => Err(OnchainError::Storage(e.to_string())),
    }
}

/// Every table BDK's rusqlite store may have created in a wallet DB.
const BDK_TABLES: &[&str] = &[
    "bdk_wallet",
    "bdk_wallet_locked_outpoints",
    "bdk_blocks",
    "bdk_txs",
    "bdk_txouts",
    "bdk_anchors",
    "bdk_descriptor_last_revealed",
    "bdk_descriptor_derived_spks",
    "bdk_schemas",
];

fn rebuild_descriptor_wallet_on(
    conn: &mut Connection,
    btc_net: bitcoin::Network,
) -> Result<PersistedWallet<Connection>, OnchainError> {
    let (ext, int) = {
        let db_tx = conn
            .transaction()
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let cs = bdk_wallet::ChangeSet::from_sqlite(&db_tx)
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        db_tx
            .commit()
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let ext = cs.descriptor.ok_or_else(|| {
            OnchainError::Storage("legacy testnet DB has no stored descriptor".into())
        })?;
        (ext.to_string(), cs.change_descriptor.map(|d| d.to_string()))
    };
    drop_bdk_tables(conn)?;
    let created = match int {
        Some(int) => Wallet::create(ext, int)
            .network(btc_net)
            .create_wallet(conn),
        None => Wallet::create_single(ext)
            .network(btc_net)
            .create_wallet(conn),
    };
    created.map_err(|e| OnchainError::WalletCreate(e.to_string()))
}

fn drop_bdk_tables(conn: &Connection) -> Result<(), OnchainError> {
    for t in BDK_TABLES {
        conn.execute(&format!("DROP TABLE IF EXISTS {t}"), [])
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// Address table — used by create_from_addresses / peek_address
// ---------------------------------------------------------------------------

const CREATE_WATCH_ADDRESS_TABLE_SQL: &str = "\
CREATE TABLE IF NOT EXISTS bhodl_watch_address (\
    idx  INTEGER PRIMARY KEY,\
    address TEXT NOT NULL\
)";

/// Open a watch DB and make sure the label / address-book tables exist.
fn open_watch_db(
    db_path: &Path,
    encryption_key: &[u8; ENCRYPTION_KEY_LEN],
) -> Result<Connection, OnchainError> {
    let conn = open_encrypted_connection(db_path, encryption_key)?;
    ensure_labels_table(&conn)?;
    ensure_contacts_table(&conn)?;
    Ok(conn)
}

/// Error for derivation requests on an address-list watch wallet.
fn no_derivation_error() -> OnchainError {
    OnchainError::InvalidArgument("address-list watch wallets cannot derive new addresses".into())
}

fn ensure_watch_address_table(conn: &Connection) -> Result<(), OnchainError> {
    conn.execute(CREATE_WATCH_ADDRESS_TABLE_SQL, [])
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    Ok(())
}

/// Persist address at `idx` if no row exists for that index. Idempotent.
fn save_watch_address_if_absent(
    conn: &Connection,
    idx: u32,
    address: &str,
) -> Result<(), OnchainError> {
    conn.execute(
        "INSERT OR IGNORE INTO bhodl_watch_address (idx, address) VALUES (?1, ?2)",
        rusqlite::params![idx, address],
    )
    .map_err(|e| OnchainError::Storage(e.to_string()))?;
    Ok(())
}

fn read_watch_address(conn: &Connection, idx: u32) -> Result<Option<String>, OnchainError> {
    match conn.query_row(
        "SELECT address FROM bhodl_watch_address WHERE idx = ?1",
        rusqlite::params![idx],
        |row| row.get::<_, String>(0),
    ) {
        Ok(addr) => Ok(Some(addr)),
        Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
        Err(e) => Err(OnchainError::Storage(e.to_string())),
    }
}

/// Every stored watch address, ordered by index.
fn read_all_watch_addresses(conn: &Connection) -> Result<Vec<(u32, String)>, OnchainError> {
    let mut stmt = conn
        .prepare("SELECT idx, address FROM bhodl_watch_address ORDER BY idx ASC")
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    let rows = stmt
        .query_map([], |row| {
            Ok((row.get::<_, u32>(0)?, row.get::<_, String>(1)?))
        })
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    rows.collect::<Result<Vec<_>, _>>()
        .map_err(|e| OnchainError::Storage(e.to_string()))
}

// ---------------------------------------------------------------------------
// Script-pubkey backend (address wallets)
// ---------------------------------------------------------------------------

/// Persist chain + tx-graph deltas atomically through `bdk_chain`'s
/// rusqlite store. Empty changesets are a no-op.
fn persist_script_changes(
    conn: &mut Connection,
    chain_cs: &local_chain::ChangeSet,
    graph_cs: &tx_graph::ChangeSet<ConfirmationBlockTime>,
) -> Result<(), OnchainError> {
    if chain_cs.is_empty() && graph_cs.is_empty() {
        return Ok(());
    }
    let db_tx = conn
        .transaction()
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    local_chain::ChangeSet::init_sqlite_tables(&db_tx)
        .and_then(|()| tx_graph::ChangeSet::<ConfirmationBlockTime>::init_sqlite_tables(&db_tx))
        .and_then(|()| chain_cs.persist_to_sqlite(&db_tx))
        .and_then(|()| graph_cs.persist_to_sqlite(&db_tx))
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    db_tx
        .commit()
        .map_err(|e| OnchainError::Storage(e.to_string()))
}

impl ScriptWatch {
    /// Open the script tracker stored in `conn` for `addresses`.
    ///
    /// Reads any persisted chain/tx-graph state (including the one left by
    /// a legacy `tr()`-descriptor address wallet). If no chain exists yet,
    /// or the stored genesis belongs to another network (e.g. a testnet4
    /// wallet written with testnet3 parameters), chain state is reset —
    /// address wallets hold no secrets, the next sync rebuilds it.
    fn open(
        conn: &mut Connection,
        addresses: &[(u32, Address)],
        btc_net: bitcoin::Network,
    ) -> Result<Self, OnchainError> {
        let genesis = bitcoin::constants::genesis_block(btc_net).block_hash();

        let (chain_cs, graph_cs) = {
            let db_tx = conn
                .transaction()
                .map_err(|e| OnchainError::Storage(e.to_string()))?;
            local_chain::ChangeSet::init_sqlite_tables(&db_tx)
                .and_then(|()| {
                    tx_graph::ChangeSet::<ConfirmationBlockTime>::init_sqlite_tables(&db_tx)
                })
                .map_err(|e| OnchainError::Storage(e.to_string()))?;
            let chain_cs = local_chain::ChangeSet::from_sqlite(&db_tx)
                .map_err(|e| OnchainError::Storage(e.to_string()))?;
            let graph_cs = tx_graph::ChangeSet::<ConfirmationBlockTime>::from_sqlite(&db_tx)
                .map_err(|e| OnchainError::Storage(e.to_string()))?;
            db_tx
                .commit()
                .map_err(|e| OnchainError::Storage(e.to_string()))?;
            (chain_cs, graph_cs)
        };

        let stored_genesis = chain_cs.blocks.get(&0).copied().flatten();
        let (chain, graph_cs) = match stored_genesis {
            Some(h) if h == genesis => {
                let chain = LocalChain::from_changeset(chain_cs)
                    .map_err(|e| OnchainError::Storage(e.to_string()))?;
                (chain, graph_cs)
            }
            other => {
                if other.is_some() {
                    // Wrong-network chain state: wipe it (and the tx graph
                    // anchored to it) and start over on the right chain.
                    for t in ["bdk_blocks", "bdk_txs", "bdk_txouts", "bdk_anchors"] {
                        conn.execute(&format!("DELETE FROM {t}"), [])
                            .map_err(|e| OnchainError::Storage(e.to_string()))?;
                    }
                }
                let (chain, init_cs) = LocalChain::from_genesis_hash(genesis);
                persist_script_changes(conn, &init_cs, &tx_graph::ChangeSet::default())?;
                let graph_cs = if other.is_some() {
                    tx_graph::ChangeSet::default()
                } else {
                    graph_cs
                };
                (chain, graph_cs)
            }
        };

        let mut index = SpkTxOutIndex::<u32>::default();
        for (idx, addr) in addresses {
            index.insert_spk(*idx, addr.script_pubkey());
        }
        let mut graph = IndexedTxGraph::new(index);
        graph.apply_changeset(graph_cs.into());

        Ok(Self { chain, graph })
    }

    fn balance(&self) -> u64 {
        self.graph
            .graph()
            .balance(
                &self.chain,
                self.chain.tip().block_id(),
                CanonicalizationParams::default(),
                self.graph.index.outpoints().iter().copied(),
                |_, _| false,
            )
            .total()
            .to_sat()
    }

    #[allow(
        clippy::cast_possible_wrap,
        reason = "sat amounts always fit i64 (max supply is 2.1e15 sat)"
    )]
    fn list_transactions(&self) -> Vec<TxRecord> {
        let graph = self.graph.graph();
        graph
            .list_canonical_txs(
                &self.chain,
                self.chain.tip().block_id(),
                CanonicalizationParams::default(),
            )
            .filter(|c| self.graph.index.is_relevant(&c.tx_node.tx))
            .map(|c| {
                let tx = &c.tx_node.tx;
                let (sent, received) = self.graph.index.sent_and_received(tx, ..);
                let net_sat = received.to_sat() as i64 - sent.to_sat() as i64;
                let fee_sat = graph.calculate_fee(tx).ok().map(bitcoin::Amount::to_sat);
                let (confirmation_height, timestamp) = match c.chain_position {
                    bdk_chain::ChainPosition::Confirmed { anchor, .. } => {
                        (Some(anchor.block_id.height), Some(anchor.confirmation_time))
                    }
                    bdk_chain::ChainPosition::Unconfirmed { last_seen, .. } => (None, last_seen),
                };
                TxRecord {
                    txid: c.tx_node.txid.to_string(),
                    net_sat,
                    fee_sat,
                    confirmation_height,
                    timestamp,
                }
            })
            .collect()
    }

    /// Offline: snapshot the spk sync request (audit M5 — lock-friendly).
    fn sync_request(&self) -> SyncRequest<u32> {
        let tip = self.chain.tip();
        let spks: Vec<(u32, bitcoin::ScriptBuf)> = self
            .graph
            .index
            .all_spks()
            .iter()
            .map(|(i, s)| (*i, s.clone()))
            .collect();
        let expected: Vec<(bitcoin::ScriptBuf, bitcoin::Txid)> = self
            .graph
            .list_expected_spk_txids(&self.chain, tip.block_id(), ..)
            .collect();
        SyncRequest::builder()
            .chain_tip(tip)
            .spks_with_indexes(spks)
            .expected_spk_txids(expected)
            .build()
    }

    /// Merge a fetched spk sync response and persist it.
    fn apply_sync_response(
        &mut self,
        conn: &mut Connection,
        response: SyncResponse,
    ) -> Result<(), OnchainError> {
        let chain_cs = match response.chain_update {
            Some(cp) => self
                .chain
                .apply_update(cp)
                .map_err(|e| OnchainError::EsploraSync(e.to_string()))?,
            None => local_chain::ChangeSet::default(),
        };
        let graph_cs = self.graph.apply_update(response.tx_update).tx_graph;
        persist_script_changes(conn, &chain_cs, &graph_cs)
    }
}

// ---------------------------------------------------------------------------
// WatchAccount implementation
// ---------------------------------------------------------------------------

impl WatchAccount {
    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /// BIP-86 / 84 / 49 HD watch wallet from an account-level extended public key.
    ///
    /// `xpub` must already be derived to `m/<bip>'/coin'/0'`. SLIP-132
    /// `zpub`/`vpub` (BIP-84) and `ypub`/`upub` (BIP-49) are accepted and
    /// normalised; their embedded script type must agree with `bip`. The
    /// descriptor string is built as (recv keychain // change keychain):
    /// - BIP-86 (`Taproot`): `tr(xpub/0/i)` // `tr(xpub/1/i)`
    /// - BIP-84 (`NativeSegwit`): `wpkh(xpub/0/i)` // `wpkh(xpub/1/i)`
    /// - BIP-49 (`NestedSegwit`): `sh(wpkh(xpub/0/i))` // `sh(wpkh(xpub/1/i))`
    ///
    /// Validates that the xpub parses and its network byte matches `network`
    /// (mainnet `xpub` vs testnet/signet/regtest `tpub`).
    ///
    /// # Errors
    ///
    /// - [`OnchainError::Descriptor`] — invalid xpub, network mismatch, or
    ///   SLIP-132 script type contradicting `bip`.
    /// - [`OnchainError::Storage`] — `SQLCipher` open or probe failed.
    /// - [`OnchainError::WalletCreate`] — BDK rejected the descriptor.
    pub fn create_from_xpub(
        network: BhodlNetwork,
        db_path: &Path,
        encryption_key: &[u8; ENCRYPTION_KEY_LEN],
        xpub: &str,
        bip: WatchBip,
    ) -> Result<Self, OnchainError> {
        let (desc_ext, desc_int) = xpub_watch_descriptors(xpub, network, bip)?;
        let btc_net = map_network(network);

        let mut conn = open_watch_db(db_path, encryption_key)?;
        let mut wallet = open_or_create_wallet(&mut conn, desc_ext, desc_int, btc_net)?;
        wallet
            .persist(&mut conn)
            .map_err(|e| OnchainError::Storage(e.to_string()))?;

        Ok(Self {
            network,
            backend: WatchBackend::Descriptor(Box::new(wallet)),
            db_conn: conn,
        })
    }

    /// HD watch wallet with full descriptor metadata (Sparrow / Specter format).
    ///
    /// `master_fingerprint` is the 4-byte fingerprint of the master xprv in
    /// hex (exactly 8 chars, e.g. `"73c5da0a"`).
    ///
    /// `derivation_path` is the BIP-32 path from the master to the account key,
    /// e.g. `"m/86'/0'/0'"` (the leading `m/` is optional). Its depth must
    /// match the xpub's encoded depth.
    ///
    /// The descriptor template is inferred from the **purpose** level — the
    /// **first** (hardened) component of `derivation_path` (recv keychain //
    /// change keychain):
    /// - `86'` → `tr([fp/path]xpub/0/i)` // `tr([fp/path]xpub/1/i)`
    /// - `84'` → `wpkh([fp/path]xpub/0/i)` // `wpkh([fp/path]xpub/1/i)`
    /// - `49'` → `sh(wpkh([fp/path]xpub/0/i))` // `sh(wpkh([fp/path]xpub/1/i))`
    /// - any other → [`OnchainError::Descriptor`] (only BIP-86/84/49 in Phase 3.1)
    ///
    /// # Errors
    ///
    /// Same as [`create_from_xpub`](WatchAccount::create_from_xpub), plus
    /// [`OnchainError::Descriptor`] for a malformed fingerprint/path, a
    /// path/xpub depth mismatch, or an unsupported BIP path purpose.
    pub fn create_from_hd(
        network: BhodlNetwork,
        db_path: &Path,
        encryption_key: &[u8; ENCRYPTION_KEY_LEN],
        master_fingerprint: &str,
        derivation_path: &str,
        xpub: &str,
    ) -> Result<Self, OnchainError> {
        let (desc_ext, desc_int) =
            hd_watch_descriptors(master_fingerprint, derivation_path, xpub, network)?;
        let btc_net = map_network(network);

        let mut conn = open_watch_db(db_path, encryption_key)?;
        let mut wallet = open_or_create_wallet(&mut conn, desc_ext, desc_int, btc_net)?;
        wallet
            .persist(&mut conn)
            .map_err(|e| OnchainError::Storage(e.to_string()))?;

        Ok(Self {
            network,
            backend: WatchBackend::Descriptor(Box::new(wallet)),
            db_conn: conn,
        })
    }

    /// Watch wallet over an explicit list of addresses.
    ///
    /// Every address is validated against `network` (any standard address
    /// type is accepted) and the list is de-duplicated preserving order.
    /// Addresses are persisted in the `bhodl_watch_address` table and their
    /// `script_pubkey()`s are tracked directly — see the module docs for why
    /// no BDK descriptor is involved.
    ///
    /// Re-running on an existing DB is idempotent for the addresses already
    /// stored; new addresses are appended.
    ///
    /// # Errors
    ///
    /// - [`OnchainError::InvalidAddress`] — empty list, parse failure, or
    ///   wrong network.
    /// - [`OnchainError::Storage`] — DB failure.
    pub fn create_from_addresses(
        network: BhodlNetwork,
        db_path: &Path,
        encryption_key: &[u8; ENCRYPTION_KEY_LEN],
        addresses: &[String],
    ) -> Result<Self, OnchainError> {
        if addresses.is_empty() {
            return Err(OnchainError::InvalidAddress("address list is empty".into()));
        }
        let mut parsed: Vec<Address> = Vec::with_capacity(addresses.len());
        for raw in addresses {
            let addr = validate_watch_address(raw, network)?;
            if !parsed.contains(&addr) {
                parsed.push(addr);
            }
        }
        let btc_net = map_network(network);

        let mut conn = open_watch_db(db_path, encryption_key)?;
        ensure_watch_address_table(&conn)?;
        let mut stored = Self::stored_addresses(&conn, network)?;
        let mut next_idx = stored.last().map_or(0, |(i, _)| i.saturating_add(1));
        for addr in parsed {
            if stored.iter().any(|(_, a)| *a == addr) {
                continue;
            }
            save_watch_address_if_absent(&conn, next_idx, &addr.to_string())?;
            stored.push((next_idx, addr));
            next_idx = next_idx.saturating_add(1);
        }

        let scripts = ScriptWatch::open(&mut conn, &stored, btc_net)?;
        Ok(Self {
            network,
            backend: WatchBackend::Scripts(Box::new(scripts)),
            db_conn: conn,
        })
    }

    /// Parse every stored watch address for `network`.
    fn stored_addresses(
        conn: &Connection,
        network: BhodlNetwork,
    ) -> Result<Vec<(u32, Address)>, OnchainError> {
        read_all_watch_addresses(conn)?
            .into_iter()
            .map(|(idx, raw)| {
                validate_watch_address(&raw, network)
                    .map(|a| (idx, a))
                    .map_err(|e| {
                        OnchainError::InvalidAddress(format!("stored address is invalid: {e}"))
                    })
            })
            .collect()
    }

    /// Re-open an existing watch wallet from disk.
    ///
    /// HD wallets: BDK 3.0 persists the descriptor inside the encrypted
    /// `ChangeSet`, so only the device key is needed. Address wallets are
    /// recognised by a non-empty `bhodl_watch_address` table. Used on every
    /// app boot when the registry says "this wallet exists".
    ///
    /// Legacy DBs are migrated in place: address wallets created with the
    /// broken `tr(<output key>)` descriptor switch to script tracking, and
    /// testnet4 DBs persisted with testnet3 parameters are rebuilt from
    /// their public descriptors (watch wallets hold no secrets).
    ///
    /// # Errors
    ///
    /// - [`OnchainError::Storage`] — wrong encryption key, not a wallet DB,
    ///   or DB is empty (nothing to load).
    pub fn load(
        network: BhodlNetwork,
        db_path: &Path,
        encryption_key: &[u8; ENCRYPTION_KEY_LEN],
    ) -> Result<Self, OnchainError> {
        let btc_net = map_network(network);
        // Opening a missing path would create an empty encrypted DB as a
        // side effect before failing below; refuse up front instead.
        if !db_path.exists() {
            return Err(OnchainError::Storage(
                "no watch wallet state file at this path; \
                 create it first via create_from_xpub / create_from_hd / \
                 create_from_addresses"
                    .into(),
            ));
        }
        let mut conn = open_watch_db(db_path, encryption_key)?;

        // Ensure address table exists (HD wallets will have an empty table; that is fine).
        ensure_watch_address_table(&conn)?;

        let addresses = Self::stored_addresses(&conn, network)?;
        if !addresses.is_empty() {
            let scripts = ScriptWatch::open(&mut conn, &addresses, btc_net)?;
            return Ok(Self {
                network,
                backend: WatchBackend::Scripts(Box::new(scripts)),
                db_conn: conn,
            });
        }

        let loaded = load_descriptor_wallet(&mut conn, None, btc_net)?.ok_or_else(|| {
            OnchainError::Storage(
                "encrypted DB exists but contains no watch wallet; \
                 create it first via create_from_xpub / create_from_hd / \
                 create_from_addresses"
                    .into(),
            )
        })?;

        Ok(Self {
            network,
            backend: WatchBackend::Descriptor(Box::new(loaded)),
            db_conn: conn,
        })
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    /// Network this watch wallet is bound to.
    #[must_use]
    pub fn network(&self) -> BhodlNetwork {
        self.network
    }

    /// Total balance in satoshis (confirmed + all pending categories).
    ///
    /// Returns [`Amount::ZERO`] until at least one successful
    /// [`sync_with_esplora`](WatchAccount::sync_with_esplora) call.
    #[must_use]
    pub fn balance(&self) -> Amount {
        match &self.backend {
            WatchBackend::Descriptor(w) => Amount::from_sat(w.balance().total().to_sat()),
            WatchBackend::Scripts(s) => Amount::from_sat(s.balance()),
        }
    }

    /// All transactions the wallet currently knows about, flattened to [`TxRecord`].
    ///
    /// Both confirmed and mempool transactions are included. Order is
    /// BDK-internal; sort by `timestamp` descending for a "newest first" view.
    #[must_use]
    #[allow(
        clippy::cast_possible_wrap,
        reason = "sat balance always fits i64 (max supply is 2.1e15 sat)"
    )]
    pub fn list_transactions(&self) -> Vec<TxRecord> {
        let wallet = match &self.backend {
            WatchBackend::Descriptor(w) => w,
            WatchBackend::Scripts(s) => return s.list_transactions(),
        };
        wallet
            .transactions()
            .map(|wt| {
                let tx = &wt.tx_node.tx;
                let txid = wt.tx_node.txid.to_string();
                let (sent, received) = wallet.sent_and_received(tx);
                let net_sat = received.to_sat() as i64 - sent.to_sat() as i64;
                let fee_sat = wallet.calculate_fee(tx).ok().map(bitcoin::Amount::to_sat);
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

    /// Synchronise the wallet against an Esplora HTTP API (blocking).
    ///
    /// HD wallets: full-scan with a `stop_gap` of 20 (BIP-44
    /// recommendation). Address wallets: spk sync of every watched
    /// script. Up to 4 parallel HTTP requests; no async runtime required.
    ///
    /// # Errors
    ///
    /// - [`OnchainError::EsploraSync`] — HTTP failure or malformed response.
    /// - [`OnchainError::Storage`] — persisting the updated state failed.
    pub fn sync_with_esplora(
        &mut self,
        esplora_url: &str,
        proxy_url: Option<&str>,
    ) -> Result<(), OnchainError> {
        let pending = self.start_esplora_scan();
        let update = pending.fetch(esplora_url, proxy_url)?;
        self.apply_esplora_scan(update)
    }

    /// Phase 1 of a lock-friendly sync (audit M5): snapshot the scan
    /// request. Offline: callers guarding the account with a mutex take it
    /// only for this call, run [`PendingWatchScan::fetch`] unlocked, then
    /// re-lock for [`Self::apply_esplora_scan`].
    #[must_use]
    pub fn start_esplora_scan(&self) -> PendingWatchScan {
        match &self.backend {
            WatchBackend::Descriptor(w) => {
                let desc = w.public_descriptor(KeychainKind::External).to_string();
                PendingWatchScan::Full(w.start_full_scan().build(), wallet_isolation_scope(&desc))
            }
            WatchBackend::Scripts(s) => {
                // Address-list wallets have no descriptor: key on the
                // lowest-index script (in-memory, hashed).
                let first = s
                    .graph
                    .index
                    .all_spks()
                    .iter()
                    .next()
                    .map(|(_, spk)| spk.to_hex_string())
                    .unwrap_or_default();
                PendingWatchScan::Spks(s.sync_request(), wallet_isolation_scope(&first))
            }
        }
    }

    /// Phase 3 of a lock-friendly sync: apply a fetched update and persist.
    ///
    /// # Errors
    ///
    /// - [`OnchainError::EsploraSync`] — update does not connect / wrong
    ///   kind for this wallet's backend.
    /// - [`OnchainError::Storage`] — persisting the updated state failed.
    pub fn apply_esplora_scan(&mut self, update: WatchScanUpdate) -> Result<(), OnchainError> {
        match (&mut self.backend, update) {
            (WatchBackend::Descriptor(wallet), WatchScanUpdate::Full(update)) => {
                wallet
                    .apply_update(update)
                    .map_err(|e| OnchainError::EsploraSync(e.to_string()))?;
                wallet
                    .persist(&mut self.db_conn)
                    .map_err(|e| OnchainError::Storage(e.to_string()))?;
                Ok(())
            }
            (WatchBackend::Scripts(s), WatchScanUpdate::Spks(response)) => {
                s.apply_sync_response(&mut self.db_conn, response)
            }
            _ => Err(OnchainError::EsploraSync(
                "scan update does not match the watch wallet kind".into(),
            )),
        }
    }

    /// Peek the receive address at `index` without advancing the derivation counter.
    ///
    /// For HD wallets (`create_from_xpub` / `create_from_hd`) delegates to BDK's
    /// `peek_address` on the external keychain — equivalent to deriving
    /// `m/.../0/<index>` using the account xpub.
    ///
    /// For address-list wallets (`create_from_addresses`) the address at
    /// position `index` of the stored list is returned; an out-of-range
    /// index returns [`OnchainError::InvalidAddress`].
    ///
    /// # Errors
    ///
    /// - [`OnchainError::InvalidAddress`] — index out of range (address wallets)
    ///   or stored address is malformed.
    /// - [`OnchainError::Storage`] — DB read failure.
    pub fn peek_address(&self, index: u32) -> Result<Address, OnchainError> {
        match &self.backend {
            WatchBackend::Descriptor(w) => {
                Ok(w.peek_address(KeychainKind::External, index).address)
            }
            WatchBackend::Scripts(_) => {
                let stored = read_watch_address(&self.db_conn, index)?.ok_or_else(|| {
                    OnchainError::InvalidAddress(format!(
                        "address index {index} out of range for this watch wallet"
                    ))
                })?;
                validate_watch_address(&stored, self.network).map_err(|e| {
                    OnchainError::InvalidAddress(format!("stored address is invalid: {e}"))
                })
            }
        }
    }

    // -----------------------------------------------------------------------
    // Addresses
    // -----------------------------------------------------------------------

    /// `true` for address-list watch wallets (no BIP-32 derivation: no
    /// new receive addresses, no change keychain).
    #[must_use]
    pub fn is_address_list(&self) -> bool {
        matches!(self.backend, WatchBackend::Scripts(_))
    }

    /// Reveal the next external (receive) address and persist the advance.
    ///
    /// # Errors
    ///
    /// - [`OnchainError::InvalidArgument`] — address-list wallet.
    /// - [`OnchainError::Storage`] — persisting the reveal failed.
    pub fn reveal_next_address(&mut self) -> Result<Address, OnchainError> {
        let WatchBackend::Descriptor(wallet) = &mut self.backend else {
            return Err(no_derivation_error());
        };
        let address = wallet.reveal_next_address(KeychainKind::External).address;
        wallet
            .persist(&mut self.db_conn)
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(address)
    }

    /// Lowest revealed receive address that has not received funds yet,
    /// revealing (and persisting) a new one only when every revealed
    /// address is used, so visiting the Receive tab never burns indices.
    ///
    /// # Errors
    ///
    /// Same as [`Self::reveal_next_address`].
    pub fn next_unused_receive_address(&mut self) -> Result<Address, OnchainError> {
        let WatchBackend::Descriptor(wallet) = &mut self.backend else {
            return Err(no_derivation_error());
        };
        let address = wallet.next_unused_address(KeychainKind::External).address;
        wallet
            .persist(&mut self.db_conn)
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(address)
    }

    /// Every address revealed so far on one keychain (external = receive,
    /// internal = change), oldest first, with its usage state and BIP-329
    /// `addr` label / category.
    ///
    /// HD wallets list `0..=derivation_index`, so a fresh wallet returns an
    /// empty list. Address-list wallets list the watched addresses on the
    /// external side and nothing on the change side. `used` means the
    /// script has received at least one output in the synced history.
    ///
    /// # Errors
    ///
    /// [`OnchainError::Storage`] if the label table cannot be read.
    pub fn list_addresses(&self, change: bool) -> Result<Vec<DerivedAddress>, OnchainError> {
        let addr_labels: HashMap<String, Label> = labels::list_labels(&self.db_conn)?
            .into_iter()
            .filter(|l| l.kind == LabelKind::Addr)
            .map(|l| (l.target.clone(), l))
            .collect();
        let entry = |index: u32, address: Address, used: bool| {
            let label = addr_labels.get(&address.to_string());
            DerivedAddress {
                index,
                is_change: change,
                used,
                label: label.map(|l| l.text.clone()).filter(|t| !t.is_empty()),
                category: label.and_then(|l| l.category.clone()),
                address,
            }
        };
        match &self.backend {
            WatchBackend::Descriptor(wallet) => {
                let keychain = if change {
                    KeychainKind::Internal
                } else {
                    KeychainKind::External
                };
                let Some(last) = wallet.derivation_index(keychain) else {
                    return Ok(Vec::new());
                };
                Ok((0..=last)
                    .map(|index| {
                        entry(
                            index,
                            wallet.peek_address(keychain, index).address,
                            wallet.spk_index().is_used(keychain, index),
                        )
                    })
                    .collect())
            }
            WatchBackend::Scripts(s) => {
                if change {
                    return Ok(Vec::new());
                }
                Ok(Self::stored_addresses(&self.db_conn, self.network)?
                    .into_iter()
                    .map(|(index, address)| entry(index, address, s.graph.index.is_used(&index)))
                    .collect())
            }
        }
    }

    /// Set (or clear) the BIP-329 `addr` label and the BHODL `category`
    /// extension on one of this wallet's addresses. Funds that later
    /// arrive at the address inherit them via
    /// [`Self::apply_receive_labels`]. A blank `text` with no category
    /// removes the row.
    ///
    /// # Errors
    ///
    /// [`OnchainError::Storage`] on a database failure.
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

    /// Copy the BIP-329 `addr` label and category of a receive address onto
    /// the transaction that paid it. Rules:
    ///
    /// - only transactions with a positive net amount;
    /// - when one transaction pays several labelled addresses, the lowest
    ///   output index wins (deterministic);
    /// - an existing transaction label / category is never overwritten;
    /// - each `(txid, address)` copy happens once (recorded in the
    ///   encrypted DB), so a label the user clears stays cleared.
    ///
    /// Returns what was applied (the caller mirrors categories into the
    /// tracker registry).
    ///
    /// # Errors
    ///
    /// [`OnchainError::Storage`] on a database failure.
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
        for (txid, tx) in self.incoming_txs() {
            let hit = tx.output.iter().find_map(|out| {
                if !self.is_mine(&out.script_pubkey) {
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
            let txid = txid.to_string();
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

    /// Canonical transactions whose net effect on this wallet is positive.
    fn incoming_txs(&self) -> Vec<(bitcoin::Txid, std::sync::Arc<bitcoin::Transaction>)> {
        match &self.backend {
            WatchBackend::Descriptor(wallet) => wallet
                .transactions()
                .filter(|wt| {
                    let (sent, received) = wallet.sent_and_received(&wt.tx_node.tx);
                    received > sent
                })
                .map(|wt| (wt.tx_node.txid, wt.tx_node.tx.clone()))
                .collect(),
            WatchBackend::Scripts(s) => s
                .graph
                .graph()
                .list_canonical_txs(
                    &s.chain,
                    s.chain.tip().block_id(),
                    CanonicalizationParams::default(),
                )
                .filter(|c| {
                    let (sent, received) = s.graph.index.sent_and_received(&c.tx_node.tx, ..);
                    received > sent
                })
                .map(|c| (c.tx_node.txid, c.tx_node.tx.clone()))
                .collect(),
        }
    }

    /// Whether `script` belongs to this wallet.
    fn is_mine(&self, script: &bitcoin::Script) -> bool {
        match &self.backend {
            WatchBackend::Descriptor(wallet) => wallet.is_mine(script.to_owned()),
            WatchBackend::Scripts(s) => s.graph.index.index_of_spk(script.to_owned()).is_some(),
        }
    }

    // -----------------------------------------------------------------------
    // UTXOs / chain
    // -----------------------------------------------------------------------

    /// Unspent outputs held by the watched scripts (confirmed plus mempool).
    ///
    /// # Errors
    ///
    /// [`OnchainError::Storage`] if a UTXO's `script_pubkey` cannot be
    /// expressed as an address for this network (corrupted state).
    pub fn list_utxos(&self) -> Result<Vec<UtxoInfo>, OnchainError> {
        let btc_network = map_network(self.network);
        let to_info = |outpoint: bitcoin::OutPoint,
                       txout: &bitcoin::TxOut,
                       is_change: bool,
                       confirmed: bool| {
            let address = Address::from_script(&txout.script_pubkey, btc_network).map_err(|e| {
                OnchainError::Storage(format!(
                    "UTXO {outpoint} script_pubkey cannot be expressed as an address: {e}"
                ))
            })?;
            Ok(UtxoInfo {
                outpoint,
                value: Amount::from_sat(txout.value.to_sat()),
                address,
                is_change,
                confirmed,
            })
        };
        match &self.backend {
            WatchBackend::Descriptor(wallet) => wallet
                .list_unspent()
                .map(|out| {
                    to_info(
                        out.outpoint,
                        &out.txout,
                        out.keychain == KeychainKind::Internal,
                        out.chain_position.is_confirmed(),
                    )
                })
                .collect(),
            WatchBackend::Scripts(s) => s
                .graph
                .graph()
                .filter_chain_unspents(
                    &s.chain,
                    s.chain.tip().block_id(),
                    CanonicalizationParams::default(),
                    s.graph.index.outpoints().iter().copied(),
                )
                .map(|(_, out)| {
                    to_info(
                        out.outpoint,
                        &out.txout,
                        false,
                        out.chain_position.is_confirmed(),
                    )
                })
                .collect(),
        }
    }

    /// Height of the wallet's latest synced block (its local chain tip).
    /// `0` before the first successful sync.
    #[must_use]
    pub fn chain_tip_height(&self) -> u32 {
        match &self.backend {
            WatchBackend::Descriptor(wallet) => wallet.latest_checkpoint().height(),
            WatchBackend::Scripts(s) => s.chain.tip().height(),
        }
    }

    /// Public receive + change descriptors of an HD watch wallet, for
    /// import into Sparrow / Liana / Specter. `None` for address-list
    /// wallets, which have no descriptor.
    #[must_use]
    pub fn export_descriptors(&self) -> Option<DescriptorBackup> {
        let WatchBackend::Descriptor(wallet) = &self.backend else {
            return None;
        };
        Some(DescriptorBackup {
            network: self.network,
            external: wallet.public_descriptor(KeychainKind::External).to_string(),
            internal: wallet.public_descriptor(KeychainKind::Internal).to_string(),
        })
    }

    // -----------------------------------------------------------------------
    // BIP-329 labels
    // -----------------------------------------------------------------------

    /// Add (or overwrite) a BIP-329 label, keyed by `(kind, target)`.
    ///
    /// # Errors
    ///
    /// [`OnchainError::Storage`] on a database failure.
    pub fn add_label(&self, label: &Label) -> Result<(), OnchainError> {
        labels::upsert_label(&self.db_conn, label)
    }

    /// Look up a single BIP-329 label by `(kind, target)`.
    ///
    /// # Errors
    ///
    /// [`OnchainError::Storage`] on a database failure.
    pub fn get_label(&self, kind: LabelKind, target: &str) -> Result<Option<Label>, OnchainError> {
        labels::get_label(&self.db_conn, kind, target)
    }

    /// Every BIP-329 label stored on this wallet.
    ///
    /// # Errors
    ///
    /// [`OnchainError::Storage`] on a database failure.
    pub fn list_labels(&self) -> Result<Vec<Label>, OnchainError> {
        labels::list_labels(&self.db_conn)
    }

    /// Delete a BIP-329 label. Returns `true` if a row was removed.
    ///
    /// # Errors
    ///
    /// [`OnchainError::Storage`] on a database failure.
    pub fn remove_label(&self, kind: LabelKind, target: &str) -> Result<bool, OnchainError> {
        labels::remove_label(&self.db_conn, kind, target)
    }

    /// Render every label as a BIP-329 JSONL document.
    ///
    /// # Errors
    ///
    /// [`OnchainError::Storage`] on a database failure.
    pub fn export_labels_jsonl(&self) -> Result<String, OnchainError> {
        labels::export_jsonl(&self.db_conn)
    }

    /// Import a BIP-329 JSONL document produced by another wallet. Returns
    /// the count of records imported.
    ///
    /// # Errors
    ///
    /// [`OnchainError::Storage`] on a malformed record or database failure.
    pub fn import_labels_jsonl(&self, jsonl: &str) -> Result<usize, OnchainError> {
        labels::import_jsonl(&self.db_conn, jsonl)
    }

    // -----------------------------------------------------------------------
    // Address book
    // -----------------------------------------------------------------------

    /// Add an address-book entry; returns its row id. The address must be
    /// valid for this wallet's network and is stored in canonical form.
    ///
    /// # Errors
    ///
    /// - [`OnchainError::InvalidArgument`] — empty / oversized label.
    /// - [`OnchainError::InvalidAddress`] — bad or wrong-network address.
    /// - [`OnchainError::Storage`] — duplicate address or database failure.
    pub fn add_contact(&self, label: &str, address: &str) -> Result<i64, OnchainError> {
        let label = validated_contact_label(label)?;
        let address = validate_watch_address(address, self.network)?.to_string();
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| i64::try_from(d.as_secs()).unwrap_or(i64::MAX))
            .unwrap_or(0);
        contacts::insert_contact(&self.db_conn, label, &address, now)
    }

    /// Every saved contact in insertion order.
    ///
    /// # Errors
    ///
    /// [`OnchainError::Storage`] on a database failure.
    pub fn list_contacts(&self) -> Result<Vec<Contact>, OnchainError> {
        contacts::list_contacts(&self.db_conn)
    }

    /// Rename a contact. Returns `false` if no row matched `id`.
    ///
    /// # Errors
    ///
    /// [`OnchainError::InvalidArgument`] for a bad label,
    /// [`OnchainError::Storage`] on a database failure.
    pub fn update_contact_label(&self, id: i64, new_label: &str) -> Result<bool, OnchainError> {
        contacts::update_contact_label(&self.db_conn, id, validated_contact_label(new_label)?)
    }

    /// Delete a contact. Returns `false` if no row matched `id`.
    ///
    /// # Errors
    ///
    /// [`OnchainError::Storage`] on a database failure.
    pub fn remove_contact(&self, id: i64) -> Result<bool, OnchainError> {
        contacts::delete_contact(&self.db_conn, id)
    }

    /// Script pubkeys this wallet tracks for address wallets (index order).
    /// `None` for HD wallets.
    #[cfg(test)]
    fn watched_spks(&self) -> Option<Vec<bitcoin::ScriptBuf>> {
        match &self.backend {
            WatchBackend::Descriptor(_) => None,
            WatchBackend::Scripts(s) => Some(s.graph.index.all_spks().values().cloned().collect()),
        }
    }

    /// Underlying BDK wallet for HD watch wallets (tests only).
    #[cfg(test)]
    fn descriptor_wallet(&self) -> &PersistedWallet<Connection> {
        match &self.backend {
            WatchBackend::Descriptor(w) => w,
            WatchBackend::Scripts(_) => panic!("not a descriptor watch wallet"),
        }
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
#[path = "uc_wallet_tests.rs"]
mod uc_wallet_tests;

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    fn fixture_key() -> [u8; ENCRYPTION_KEY_LEN] {
        [0x42u8; ENCRYPTION_KEY_LEN]
    }

    fn wrong_key() -> [u8; ENCRYPTION_KEY_LEN] {
        [0x99u8; ENCRYPTION_KEY_LEN]
    }

    fn xpub_from_mnemonic(network: BhodlNetwork) -> String {
        crate::test_support::bip86_xpub(network)
    }

    fn signet_address_at(index: u32) -> String {
        crate::test_support::bip86_address(BhodlNetwork::Signet, index)
    }

    fn mainnet_address_at(index: u32) -> String {
        crate::test_support::bip86_address(BhodlNetwork::Mainnet, index)
    }

    // ------------------------------------------------------------------
    // 1. BIP-86 happy path: create then load.
    // ------------------------------------------------------------------

    #[test]
    fn create_from_xpub_then_load_round_trip() {
        let dir = TempDir::new().unwrap();
        let db = dir.path().join("watch_bip86.sqlite");
        let xpub = xpub_from_mnemonic(BhodlNetwork::Signet);

        {
            let acct = WatchAccount::create_from_xpub(
                BhodlNetwork::Signet,
                &db,
                &fixture_key(),
                &xpub,
                WatchBip::Taproot,
            )
            .unwrap();
            assert_eq!(acct.network(), BhodlNetwork::Signet);
            assert_eq!(acct.balance().to_sat(), 0);
        }

        let acct2 = WatchAccount::load(BhodlNetwork::Signet, &db, &fixture_key()).unwrap();
        assert_eq!(acct2.network(), BhodlNetwork::Signet);
        assert_eq!(acct2.balance().to_sat(), 0);
    }

    // ------------------------------------------------------------------
    // 2. BIP-84 descriptor shape contains wpkh(.
    // ------------------------------------------------------------------

    #[test]
    fn create_from_xpub_bip84_descriptor_shape() {
        let dir = TempDir::new().unwrap();
        let db = dir.path().join("watch_bip84.sqlite");
        let xpub = xpub_from_mnemonic(BhodlNetwork::Signet);

        let acct = WatchAccount::create_from_xpub(
            BhodlNetwork::Signet,
            &db,
            &fixture_key(),
            &xpub,
            WatchBip::NativeSegwit,
        )
        .unwrap();

        let desc = acct
            .descriptor_wallet()
            .public_descriptor(KeychainKind::External)
            .to_string();
        assert!(
            desc.contains("wpkh("),
            "expected wpkh( in descriptor, got: {desc}"
        );
        assert!(!desc.contains("tr("), "should NOT contain tr(, got: {desc}");
    }

    // ------------------------------------------------------------------
    // 3. BIP-49 descriptor shape contains sh(wpkh(.
    // ------------------------------------------------------------------

    #[test]
    fn create_from_xpub_bip49_descriptor_shape() {
        let dir = TempDir::new().unwrap();
        let db = dir.path().join("watch_bip49.sqlite");
        let xpub = xpub_from_mnemonic(BhodlNetwork::Signet);

        let acct = WatchAccount::create_from_xpub(
            BhodlNetwork::Signet,
            &db,
            &fixture_key(),
            &xpub,
            WatchBip::NestedSegwit,
        )
        .unwrap();

        let desc = acct
            .descriptor_wallet()
            .public_descriptor(KeychainKind::External)
            .to_string();
        assert!(
            desc.contains("sh(wpkh("),
            "expected sh(wpkh( in descriptor, got: {desc}"
        );
    }

    // ------------------------------------------------------------------
    // 4. Mainnet xpub on signet → Err(Descriptor).
    // ------------------------------------------------------------------

    #[test]
    fn create_from_xpub_rejects_mainnet_xpub_when_network_is_signet() {
        let dir = TempDir::new().unwrap();
        let db = dir.path().join("watch_net_mismatch.sqlite");
        let mainnet_xpub = xpub_from_mnemonic(BhodlNetwork::Mainnet);

        let result = WatchAccount::create_from_xpub(
            BhodlNetwork::Signet,
            &db,
            &fixture_key(),
            &mainnet_xpub,
            WatchBip::Taproot,
        );
        assert!(
            matches!(result, Err(OnchainError::Descriptor(_))),
            "mainnet xpub against signet must fail with Descriptor, got: {result:?}"
        );
    }

    // ------------------------------------------------------------------
    // 5. Full HD descriptor round-trip (fingerprint + path + tpub).
    // ------------------------------------------------------------------

    #[test]
    fn create_from_hd_round_trip() {
        let dir = TempDir::new().unwrap();
        let db = dir.path().join("watch_hd.sqlite");
        let xpub = xpub_from_mnemonic(BhodlNetwork::Signet);

        {
            WatchAccount::create_from_hd(
                BhodlNetwork::Signet,
                &db,
                &fixture_key(),
                "73c5da0a",
                "m/86'/1'/0'",
                &xpub,
            )
            .unwrap();
        }

        let acct = WatchAccount::load(BhodlNetwork::Signet, &db, &fixture_key()).unwrap();
        assert_eq!(acct.network(), BhodlNetwork::Signet);
        assert_eq!(acct.balance().to_sat(), 0);
    }

    // ------------------------------------------------------------------
    // 6. BIP-44 (legacy) path → Err(Descriptor).
    // ------------------------------------------------------------------

    #[test]
    fn create_from_hd_unsupported_bip_path_returns_err() {
        let dir = TempDir::new().unwrap();
        let db = dir.path().join("watch_hd_legacy.sqlite");
        let xpub = xpub_from_mnemonic(BhodlNetwork::Signet);

        let result = WatchAccount::create_from_hd(
            BhodlNetwork::Signet,
            &db,
            &fixture_key(),
            "73c5da0a",
            "m/44'/1'/0'", // BIP-44 legacy — unsupported in Phase 3.1
            &xpub,
        );
        assert!(
            matches!(result, Err(OnchainError::Descriptor(_))),
            "BIP-44 path must fail with Descriptor, got: {result:?}"
        );
    }

    // ------------------------------------------------------------------
    // 7. Single signet P2TR address — create then load then peek.
    // ------------------------------------------------------------------

    #[test]
    fn create_from_addresses_single_signet_address_round_trip() {
        let dir = TempDir::new().unwrap();
        let db = dir.path().join("watch_addr.sqlite");
        let addr = signet_address_at(0);

        {
            WatchAccount::create_from_addresses(
                BhodlNetwork::Signet,
                &db,
                &fixture_key(),
                &[addr.clone()],
            )
            .unwrap();
        }

        let acct = WatchAccount::load(BhodlNetwork::Signet, &db, &fixture_key()).unwrap();
        let peeked = acct.peek_address(0).unwrap();
        assert_eq!(peeked.to_string(), addr, "peek_address(0) must round-trip");
    }

    // ------------------------------------------------------------------
    // 8. Mainnet P2TR address against signet wallet → Err(InvalidAddress).
    // ------------------------------------------------------------------

    #[test]
    fn create_from_addresses_rejects_wrong_network_address() {
        let dir = TempDir::new().unwrap();
        let db = dir.path().join("watch_addr_wrong_net.sqlite");
        let mainnet_addr = mainnet_address_at(0);

        let result = WatchAccount::create_from_addresses(
            BhodlNetwork::Signet,
            &db,
            &fixture_key(),
            &[mainnet_addr],
        );
        assert!(
            matches!(result, Err(OnchainError::InvalidAddress(_))),
            "mainnet address on signet must fail with InvalidAddress, got: {result:?}"
        );
    }

    // ------------------------------------------------------------------
    // 9. Wrong encryption key → Err(Storage).
    // ------------------------------------------------------------------

    #[test]
    fn wrong_encryption_key_fails_to_load() {
        let dir = TempDir::new().unwrap();
        let db = dir.path().join("watch_wrong_key.sqlite");
        let xpub = xpub_from_mnemonic(BhodlNetwork::Signet);

        WatchAccount::create_from_xpub(
            BhodlNetwork::Signet,
            &db,
            &fixture_key(),
            &xpub,
            WatchBip::Taproot,
        )
        .unwrap();

        let result = WatchAccount::load(BhodlNetwork::Signet, &db, &wrong_key());
        assert!(
            matches!(result, Err(OnchainError::Storage(_))),
            "wrong key must fail with Storage, got: {result:?}"
        );
    }

    // ------------------------------------------------------------------
    // 10. Fresh wallet has no transactions.
    // ------------------------------------------------------------------

    #[test]
    fn list_transactions_is_empty_on_fresh_wallet() {
        let dir = TempDir::new().unwrap();
        let db = dir.path().join("watch_fresh_txs.sqlite");
        let xpub = xpub_from_mnemonic(BhodlNetwork::Signet);

        let acct = WatchAccount::create_from_xpub(
            BhodlNetwork::Signet,
            &db,
            &fixture_key(),
            &xpub,
            WatchBip::Taproot,
        )
        .unwrap();

        assert!(
            acct.list_transactions().is_empty(),
            "fresh wallet must have zero transactions"
        );
    }

    // ------------------------------------------------------------------
    // 11. Fresh wallet balance is zero.
    // ------------------------------------------------------------------

    #[test]
    fn balance_is_zero_on_fresh_wallet() {
        let dir = TempDir::new().unwrap();
        let db = dir.path().join("watch_fresh_bal.sqlite");
        let xpub = xpub_from_mnemonic(BhodlNetwork::Signet);

        let acct = WatchAccount::create_from_xpub(
            BhodlNetwork::Signet,
            &db,
            &fixture_key(),
            &xpub,
            WatchBip::Taproot,
        )
        .unwrap();

        assert_eq!(acct.balance().to_sat(), 0);
    }

    // ------------------------------------------------------------------
    // 12. Idempotent reopen: create, drop, load, drop, load — no crash.
    // ------------------------------------------------------------------

    #[test]
    fn idempotent_reopen() {
        let dir = TempDir::new().unwrap();
        let db = dir.path().join("watch_reopen.sqlite");
        let xpub = xpub_from_mnemonic(BhodlNetwork::Signet);

        WatchAccount::create_from_xpub(
            BhodlNetwork::Signet,
            &db,
            &fixture_key(),
            &xpub,
            WatchBip::Taproot,
        )
        .unwrap();

        let a1 = WatchAccount::load(BhodlNetwork::Signet, &db, &fixture_key()).unwrap();
        assert_eq!(a1.balance().to_sat(), 0);
        drop(a1);

        let a2 = WatchAccount::load(BhodlNetwork::Signet, &db, &fixture_key()).unwrap();
        assert_eq!(a2.balance().to_sat(), 0);
        // No crash, no schema corruption.
    }

    // ------------------------------------------------------------------
    // Security audit 2026-10 — H12 address tracking, L4 HD validation,
    // SLIP-132, testnet4 legacy rebuild.
    // ------------------------------------------------------------------

    /// A signet BIP-84 (P2WPKH) address from the test mnemonic's xpub.
    fn signet_p2wpkh_address() -> String {
        let dir = TempDir::new().unwrap();
        let acct = WatchAccount::create_from_xpub(
            BhodlNetwork::Signet,
            &dir.path().join("w.sqlite"),
            &fixture_key(),
            &xpub_from_mnemonic(BhodlNetwork::Signet),
            WatchBip::NativeSegwit,
        )
        .unwrap();
        acct.peek_address(0).unwrap().to_string()
    }

    fn spk_of(addr: &str) -> bitcoin::ScriptBuf {
        addr.parse::<Address<bitcoin::address::NetworkUnchecked>>()
            .unwrap()
            .assume_checked()
            .script_pubkey()
    }

    #[test]
    fn address_watch_tracks_exact_script_pubkey_of_p2tr_address() {
        let dir = TempDir::new().unwrap();
        let db = dir.path().join("watch_addr_spk.sqlite");
        let addr = signet_address_at(0);
        let acct = WatchAccount::create_from_addresses(
            BhodlNetwork::Signet,
            &db,
            &fixture_key(),
            &[addr.clone()],
        )
        .unwrap();
        // The regression: `tr(<output key>)` re-tweaked the key, so the
        // watched spk never matched the user's address.
        assert_eq!(acct.watched_spks().unwrap(), vec![spk_of(&addr)]);
        drop(acct);
        let reloaded = WatchAccount::load(BhodlNetwork::Signet, &db, &fixture_key()).unwrap();
        assert_eq!(reloaded.watched_spks().unwrap(), vec![spk_of(&addr)]);
    }

    #[test]
    fn address_watch_supports_multiple_addresses_of_any_type() {
        let dir = TempDir::new().unwrap();
        let db = dir.path().join("watch_multi.sqlite");
        let a0 = signet_address_at(0);
        let a1 = signet_address_at(1);
        let w = signet_p2wpkh_address();
        let list = vec![a0.clone(), a1.clone(), w.clone(), a0.clone()]; // dup dropped
        WatchAccount::create_from_addresses(BhodlNetwork::Signet, &db, &fixture_key(), &list)
            .unwrap();
        let acct = WatchAccount::load(BhodlNetwork::Signet, &db, &fixture_key()).unwrap();
        assert_eq!(
            acct.watched_spks().unwrap(),
            vec![spk_of(&a0), spk_of(&a1), spk_of(&w)]
        );
        assert_eq!(acct.peek_address(1).unwrap().to_string(), a1);
        assert_eq!(acct.peek_address(2).unwrap().to_string(), w);
        assert!(matches!(
            acct.peek_address(3),
            Err(OnchainError::InvalidAddress(_))
        ));
    }

    #[test]
    fn address_watch_counts_funds_sent_to_the_address() {
        use bitcoin::{
            OutPoint, Sequence, Transaction, TxIn, TxOut, Witness, absolute, transaction,
        };
        let dir = TempDir::new().unwrap();
        let db = dir.path().join("watch_funds.sqlite");
        let addr = signet_address_at(0);
        let mut acct = WatchAccount::create_from_addresses(
            BhodlNetwork::Signet,
            &db,
            &fixture_key(),
            &[addr.clone()],
        )
        .unwrap();
        let tx = Transaction {
            version: transaction::Version::TWO,
            lock_time: absolute::LockTime::ZERO,
            input: vec![TxIn {
                previous_output: OutPoint::new(
                    <bitcoin::Txid as bitcoin::hashes::Hash>::from_byte_array([7; 32]),
                    0,
                ),
                script_sig: bitcoin::ScriptBuf::new(),
                sequence: Sequence::MAX,
                witness: Witness::new(),
            }],
            output: vec![TxOut {
                value: bitcoin::Amount::from_sat(50_000),
                script_pubkey: spk_of(&addr),
            }],
        };
        let txid = tx.compute_txid();
        match &mut acct.backend {
            WatchBackend::Scripts(s) => {
                let _ = s.graph.insert_tx(tx);
                let _ = s.graph.insert_seen_at(txid, 1_700_000_000);
            }
            WatchBackend::Descriptor(_) => panic!("address wallet must use the script backend"),
        }
        assert_eq!(acct.balance().to_sat(), 50_000);
        let txs = acct.list_transactions();
        assert_eq!(txs.len(), 1);
        assert_eq!(txs[0].net_sat, 50_000);
    }

    #[test]
    fn legacy_tr_address_wallet_is_migrated_to_script_tracking() {
        let dir = TempDir::new().unwrap();
        let db = dir.path().join("watch_legacy.sqlite");
        let addr = signet_address_at(0);
        // Recreate what the old code wrote: tr(<output key>) + address row.
        {
            let parsed = validate_watch_address(&addr, BhodlNetwork::Signet).unwrap();
            let key_hex = bitcoin::hex::DisplayHex::to_lower_hex_string(
                parsed.witness_program().unwrap().program().as_bytes(),
            );
            let mut conn = open_encrypted_connection(&db, &fixture_key()).unwrap();
            ensure_watch_address_table(&conn).unwrap();
            save_watch_address_if_absent(&conn, 0, &addr).unwrap();
            let mut w = Wallet::create_single(format!("tr({key_hex})"))
                .network(bitcoin::Network::Signet)
                .create_wallet(&mut conn)
                .unwrap();
            // Old wallet watched the double-tweaked script, NOT the address.
            assert_ne!(
                w.peek_address(KeychainKind::External, 0)
                    .address
                    .to_string(),
                addr
            );
            w.persist(&mut conn).unwrap();
        }
        let acct = WatchAccount::load(BhodlNetwork::Signet, &db, &fixture_key()).unwrap();
        assert_eq!(acct.watched_spks().unwrap(), vec![spk_of(&addr)]);
        assert_eq!(acct.peek_address(0).unwrap().to_string(), addr);
    }

    #[test]
    fn testnet4_watch_db_written_with_testnet3_params_is_rebuilt() {
        let dir = TempDir::new().unwrap();
        let db = dir.path().join("watch_t4_legacy.sqlite");
        let xpub = xpub_from_mnemonic(BhodlNetwork::Signet); // tpub — valid on testnet4
        let (ext, int) = xpub_descriptors(&xpub, WatchBip::Taproot);
        {
            let mut conn = open_encrypted_connection(&db, &fixture_key()).unwrap();
            let mut w = Wallet::create(ext.clone(), int)
                .network(bitcoin::Network::Testnet) // the old, wrong mapping
                .create_wallet(&mut conn)
                .unwrap();
            w.persist(&mut conn).unwrap();
        }
        let acct = WatchAccount::load(BhodlNetwork::Testnet4, &db, &fixture_key()).unwrap();
        let w = acct.descriptor_wallet();
        assert_eq!(w.network(), bitcoin::Network::Testnet4);
        assert_eq!(
            w.local_chain().genesis_hash(),
            bitcoin::constants::genesis_block(bitcoin::Network::Testnet4).block_hash()
        );
        // Same descriptor survived the rebuild; reopening is stable.
        drop(acct);
        WatchAccount::load(BhodlNetwork::Testnet4, &db, &fixture_key()).unwrap();
    }

    #[test]
    fn map_network_testnet4_uses_real_testnet4_params() {
        assert_eq!(
            map_network(BhodlNetwork::Testnet4),
            bitcoin::Network::Testnet4
        );
    }

    #[test]
    fn hd_validation_rejects_bad_fingerprint_path_and_depth() {
        let xpub = xpub_from_mnemonic(BhodlNetwork::Signet); // depth 3
        for fp in ["73c5da0", "73c5da0a0", "zzzzzzzz", ""] {
            assert!(
                matches!(
                    hd_descriptors(fp, "m/86'/1'/0'", &xpub),
                    Err(OnchainError::Descriptor(_))
                ),
                "fingerprint {fp:?} must be rejected"
            );
        }
        // Unparseable path.
        assert!(matches!(
            hd_descriptors("73c5da0a", "m/86'/x/0'", &xpub),
            Err(OnchainError::Descriptor(_))
        ));
        // Depth mismatch (xpub is depth 3).
        assert!(matches!(
            hd_descriptors("73c5da0a", "m/86'/1'", &xpub),
            Err(OnchainError::Descriptor(_))
        ));
        // Purpose must be the FIRST component and hardened.
        assert!(matches!(
            hd_descriptors("73c5da0a", "m/0/86'/0'", &xpub),
            Err(OnchainError::Descriptor(_))
        ));
        // Valid; uppercase fingerprint normalised.
        let (ext, _) = hd_descriptors("73C5DA0A", "m/86'/1'/0'", &xpub).unwrap();
        assert!(ext.starts_with("tr([73c5da0a/86'/1'/0']"), "{ext}");
    }

    /// Re-encode a BIP-32 key with different SLIP-132 version bytes.
    fn with_version(key: &str, version: [u8; 4]) -> String {
        let mut data = bitcoin::base58::decode_check(key).unwrap();
        data[..4].copy_from_slice(&version);
        bitcoin::base58::encode_check(&data)
    }

    #[test]
    fn slip132_keys_are_normalised_and_script_type_enforced() {
        let tpub = xpub_from_mnemonic(BhodlNetwork::Signet);
        let vpub = with_version(&tpub, [0x04, 0x5F, 0x1C, 0xF6]);
        assert!(vpub.starts_with("vpub"));
        let (norm, implied) = normalize_xpub(&vpub).unwrap();
        assert_eq!(norm, tpub);
        assert_eq!(implied, Some(WatchBip::NativeSegwit));

        // vpub + BIP-84 works and yields the same addresses as the tpub.
        let dir = TempDir::new().unwrap();
        let a = WatchAccount::create_from_xpub(
            BhodlNetwork::Signet,
            &dir.path().join("a.sqlite"),
            &fixture_key(),
            &vpub,
            WatchBip::NativeSegwit,
        )
        .unwrap();
        let b = WatchAccount::create_from_xpub(
            BhodlNetwork::Signet,
            &dir.path().join("b.sqlite"),
            &fixture_key(),
            &tpub,
            WatchBip::NativeSegwit,
        )
        .unwrap();
        assert_eq!(a.peek_address(0).unwrap(), b.peek_address(0).unwrap());

        // vpub + Taproot contradicts the key's script type.
        assert!(matches!(
            xpub_watch_descriptors(&vpub, BhodlNetwork::Signet, WatchBip::Taproot),
            Err(OnchainError::Descriptor(_))
        ));
        // Mainnet zpub/ypub map to xpub.
        let xpub = xpub_from_mnemonic(BhodlNetwork::Mainnet);
        let zpub = with_version(&xpub, [0x04, 0xB2, 0x47, 0x46]);
        assert!(zpub.starts_with("zpub"));
        assert_eq!(
            normalize_xpub(&zpub).unwrap(),
            (xpub.clone(), Some(WatchBip::NativeSegwit))
        );
        let ypub = with_version(&xpub, [0x04, 0x9D, 0x7C, 0xB2]);
        assert_eq!(
            normalize_xpub(&ypub).unwrap(),
            (xpub, Some(WatchBip::NestedSegwit))
        );
        // Multisig variants rejected.
        let big_z = with_version(&tpub, [0x02, 0xAA, 0x7E, 0xD3]);
        assert!(big_z.starts_with("Zpub"));
        assert!(matches!(
            normalize_xpub(&big_z),
            Err(OnchainError::Descriptor(_))
        ));
    }

    // ------------------------------------------------------------------
    // Receive addresses, labels, address book, UTXOs
    // ------------------------------------------------------------------

    fn signet_xpub_wallet(dir: &TempDir) -> WatchAccount {
        WatchAccount::create_from_xpub(
            BhodlNetwork::Signet,
            &dir.path().join("watch_rx.sqlite"),
            &fixture_key(),
            &xpub_from_mnemonic(BhodlNetwork::Signet),
            WatchBip::Taproot,
        )
        .unwrap()
    }

    /// Insert an unconfirmed transaction paying `sat` to `addr` into an
    /// address-list wallet's graph.
    fn fund_address_wallet(acct: &mut WatchAccount, addr: &str, sat: u64) -> bitcoin::Txid {
        use bitcoin::{
            OutPoint, Sequence, Transaction, TxIn, TxOut, Witness, absolute, transaction,
        };
        let tx = Transaction {
            version: transaction::Version::TWO,
            lock_time: absolute::LockTime::ZERO,
            input: vec![TxIn {
                previous_output: OutPoint::new(
                    <bitcoin::Txid as bitcoin::hashes::Hash>::from_byte_array([9; 32]),
                    0,
                ),
                script_sig: bitcoin::ScriptBuf::new(),
                sequence: Sequence::MAX,
                witness: Witness::new(),
            }],
            output: vec![TxOut {
                value: bitcoin::Amount::from_sat(sat),
                script_pubkey: spk_of(addr),
            }],
        };
        let txid = tx.compute_txid();
        match &mut acct.backend {
            WatchBackend::Scripts(s) => {
                let _ = s.graph.insert_tx(tx);
                let _ = s.graph.insert_seen_at(txid, 1_700_000_000);
            }
            WatchBackend::Descriptor(_) => panic!("address wallet must use the script backend"),
        }
        txid
    }

    #[test]
    fn xpub_wallet_hands_out_bip86_receive_addresses_and_persists_reveals() {
        let dir = TempDir::new().unwrap();
        {
            let mut acct = signet_xpub_wallet(&dir);
            assert!(!acct.is_address_list());
            assert!(acct.list_addresses(false).unwrap().is_empty());
            // Unused address is stable across calls (no index burnt).
            let first = acct.next_unused_receive_address().unwrap().to_string();
            assert_eq!(first, signet_address_at(0));
            assert_eq!(
                acct.next_unused_receive_address().unwrap().to_string(),
                first
            );
            // Explicit "new address" advances.
            assert_eq!(
                acct.reveal_next_address().unwrap().to_string(),
                signet_address_at(1)
            );
        }
        let acct = WatchAccount::load(
            BhodlNetwork::Signet,
            &dir.path().join("watch_rx.sqlite"),
            &fixture_key(),
        )
        .unwrap();
        let listed: Vec<String> = acct
            .list_addresses(false)
            .unwrap()
            .iter()
            .map(|a| a.address.to_string())
            .collect();
        assert_eq!(listed, vec![signet_address_at(0), signet_address_at(1)]);
        assert!(acct.list_addresses(true).unwrap().is_empty());
        assert!(acct.list_utxos().unwrap().is_empty());
        assert_eq!(acct.chain_tip_height(), 0);
    }

    #[test]
    fn address_labels_show_up_in_the_address_list() {
        let dir = TempDir::new().unwrap();
        let mut acct = signet_xpub_wallet(&dir);
        let addr = acct.next_unused_receive_address().unwrap().to_string();
        acct.set_address_label(&addr, " Salary ", Some("income".into()))
            .unwrap();
        let row = &acct.list_addresses(false).unwrap()[0];
        assert_eq!(row.label.as_deref(), Some("Salary"));
        assert_eq!(row.category.as_deref(), Some("income"));
        // Blank text + no category clears the label.
        acct.set_address_label(&addr, "  ", None).unwrap();
        let row = &acct.list_addresses(false).unwrap()[0];
        assert_eq!(row.label, None);
        assert_eq!(row.category, None);
    }

    #[test]
    fn address_list_wallet_lists_its_addresses_but_cannot_derive() {
        let dir = TempDir::new().unwrap();
        let addr = signet_address_at(3);
        let mut acct = WatchAccount::create_from_addresses(
            BhodlNetwork::Signet,
            &dir.path().join("watch_list.sqlite"),
            &fixture_key(),
            &[addr.clone()],
        )
        .unwrap();
        assert!(acct.is_address_list());
        assert!(matches!(
            acct.reveal_next_address(),
            Err(OnchainError::InvalidArgument(_))
        ));
        assert!(matches!(
            acct.next_unused_receive_address(),
            Err(OnchainError::InvalidArgument(_))
        ));
        assert!(acct.export_descriptors().is_none());
        let rows = acct.list_addresses(false).unwrap();
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].address.to_string(), addr);
        assert!(!rows[0].used);
        assert!(acct.list_addresses(true).unwrap().is_empty());

        fund_address_wallet(&mut acct, &addr, 21_000);
        assert!(acct.list_addresses(false).unwrap()[0].used);
        let utxos = acct.list_utxos().unwrap();
        assert_eq!(utxos.len(), 1);
        assert_eq!(utxos[0].value.to_sat(), 21_000);
        assert_eq!(utxos[0].address.to_string(), addr);
        assert!(!utxos[0].is_change);
        assert!(!utxos[0].confirmed);
    }

    #[test]
    fn receive_label_is_copied_once_onto_the_paying_transaction() {
        let dir = TempDir::new().unwrap();
        let addr = signet_address_at(0);
        let mut acct = WatchAccount::create_from_addresses(
            BhodlNetwork::Signet,
            &dir.path().join("watch_copy.sqlite"),
            &fixture_key(),
            &[addr.clone()],
        )
        .unwrap();
        acct.set_address_label(&addr, "Invoice 42", Some("income".into()))
            .unwrap();
        let txid = fund_address_wallet(&mut acct, &addr, 10_000).to_string();

        let applied = acct.apply_receive_labels().unwrap();
        assert_eq!(
            applied,
            vec![ReceiveLabelCopy {
                txid: txid.clone(),
                address: addr.clone(),
                label: Some("Invoice 42".into()),
                category: Some("income".into()),
            }]
        );
        let tx_label = acct.get_label(LabelKind::Tx, &txid).unwrap().unwrap();
        assert_eq!(tx_label.text, "Invoice 42");

        // A label the user clears afterwards stays cleared.
        acct.remove_label(LabelKind::Tx, &txid).unwrap();
        assert!(acct.apply_receive_labels().unwrap().is_empty());
        assert!(acct.get_label(LabelKind::Tx, &txid).unwrap().is_none());
    }

    #[test]
    fn labels_round_trip_through_bip329_jsonl() {
        let dir = TempDir::new().unwrap();
        let acct = signet_xpub_wallet(&dir);
        acct.add_label(&Label {
            kind: LabelKind::Tx,
            target: "ab".repeat(32),
            text: "Rent".into(),
            spendable: None,
            category: Some("housing".into()),
        })
        .unwrap();
        let jsonl = acct.export_labels_jsonl().unwrap();

        let other_dir = TempDir::new().unwrap();
        let other = signet_xpub_wallet(&other_dir);
        assert_eq!(other.import_labels_jsonl(&jsonl).unwrap(), 1);
        assert_eq!(other.list_labels().unwrap(), acct.list_labels().unwrap());
    }

    #[test]
    fn address_book_validates_network_and_label() {
        let dir = TempDir::new().unwrap();
        let acct = signet_xpub_wallet(&dir);
        let id = acct.add_contact(" Alice ", &signet_address_at(5)).unwrap();
        let contacts = acct.list_contacts().unwrap();
        assert_eq!(contacts.len(), 1);
        assert_eq!(contacts[0].label, "Alice");
        assert_eq!(contacts[0].address, signet_address_at(5));

        assert!(acct.add_contact("Bob", &mainnet_address_at(0)).is_err());
        assert!(acct.add_contact("   ", &signet_address_at(6)).is_err());

        assert!(acct.update_contact_label(id, "Alice B").unwrap());
        assert_eq!(acct.list_contacts().unwrap()[0].label, "Alice B");
        assert!(acct.remove_contact(id).unwrap());
        assert!(!acct.remove_contact(id).unwrap());
        assert!(acct.list_contacts().unwrap().is_empty());
    }

    #[test]
    fn xpub_wallet_exports_its_public_descriptors() {
        let dir = TempDir::new().unwrap();
        let acct = signet_xpub_wallet(&dir);
        let backup = acct
            .export_descriptors()
            .expect("HD wallet has descriptors");
        let xpub = xpub_from_mnemonic(BhodlNetwork::Signet);
        assert!(backup.external.starts_with(&format!("tr({xpub}/0/*)")));
        assert!(backup.internal.starts_with(&format!("tr({xpub}/1/*)")));
        assert_eq!(backup.account_xpub().as_deref(), Some(xpub.as_str()));
    }
}
