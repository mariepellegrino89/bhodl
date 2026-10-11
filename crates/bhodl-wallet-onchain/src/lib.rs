//! On-chain Bitcoin wallet primitives for BHODL.
//!
//! Phase-1 scope: BIP86 taproot single-sig account backed by BDK 3.x.
//! Provides:
//! - [`OnchainAccount::from_mnemonic`] — in-memory BIP39 → BIP86 setup.
//! - [`OnchainAccount::create_or_load_persistent`] — disk-backed wallet
//!   via `SQLCipher` (AES-256 encrypted at rest, key supplied by caller
//!   from a hardware-backed secret in production).
//! - [`OnchainAccount::peek_taproot_address`] — deterministic address
//!   derivation matching the BIP86 canonical test vectors.
//! - [`OnchainAccount::reveal_next_address`] — BDK-tracked next external
//!   address, advancing the derivation counter; auto-persisted on
//!   disk-backed wallets.
//! - [`OnchainAccount::balance`] — in-memory balance (sat, all categories).
//! - [`OnchainAccount::sync_with_esplora`] — blocking Esplora full-scan;
//!   auto-persisted on disk-backed wallets.
//! - [`OnchainAccount::build_send_tx`] — build an unsigned PSBT for a
//!   single-recipient outgoing payment.
//! - [`OnchainAccount::sign_psbt`] — sign and finalise a PSBT with the
//!   account's master xpriv.
//! - [`OnchainAccount::broadcast_tx`] — push a signed PSBT to an Esplora
//!   endpoint, returning the resulting Txid.
//! - [`OnchainAccount::list_utxos`] — enumerate the wallet's spendable
//!   UTXOs as [`UtxoInfo`] entries (outpoint, value, address, change flag,
//!   confirmed flag) for coin-control UIs.
//! - [`OnchainAccount::build_send_tx_with_utxos`] — like `build_send_tx`
//!   but with manual coin selection: only the supplied outpoints are
//!   considered as inputs.
//! - [`OnchainAccount::export_descriptors`] — public-only descriptor
//!   backup ([`DescriptorBackup`]) suitable for watch-only recovery in
//!   Sparrow / Liana / Specter / Caravan.
//! - BIP329 labels: [`OnchainAccount::add_label`],
//!   [`OnchainAccount::get_label`], [`OnchainAccount::list_labels`],
//!   [`OnchainAccount::remove_label`],
//!   [`OnchainAccount::export_labels_jsonl`],
//!   [`OnchainAccount::import_labels_jsonl`].
//!
//! No hardware-wallet signing, no PSBT v2 (BIP370) import/export yet —
//! those land in subsequent commits.

#![deny(unsafe_code)]

mod account;
mod backup;
mod btc_at_time_backfill;
mod btc_rate_history;
mod contacts;
mod error;
mod labels;
mod mnemonic;
pub mod net_worth_history;
mod payjoin;
mod registry;
mod send_policy;
mod tor;
#[cfg(all(test, feature = "tor-tests"))]
mod tor_live_tests;
mod watch;

pub use account::{
    DerivedAddress, DescriptorBackup, ENCRYPTION_KEY_LEN, EsploraScanUpdate, FeeEstimateBuckets,
    OnchainAccount, PendingEsploraScan, ReceiveLabelCopy, TxRecord, UtxoInfo,
};
pub use backup::{BackupBundle, decrypt_bundle, encrypt_bundle};
pub use btc_at_time_backfill::{
    ManualAssetRateQuery, MigrationReport, RecomputeStats, TableBackfillStats,
    apply_8_2_migrations, classify_rate, compute_gain_loss_btc, phase_8_2_backfill_btc_at_time,
    recompute_all_lot_btc_rates_impl, resolve_lot_btc_rate_impl,
    resolve_manual_asset_purchase_btc_impl, resolve_sell_btc_impl,
};
pub use btc_rate_history::{
    BackfillReport, BtcRateHistoryRepository, BtcRateRepo, RateError, RatePoint,
    SUPPORTED_CURRENCIES, VALID_SOURCES, normalize_to_utc_midnight,
};
pub use contacts::Contact;
pub use error::OnchainError;
pub use labels::{Label, LabelKind};
pub use mnemonic::{MnemonicWordCount, generate_mnemonic};
pub use net_worth_history::{
    NwSnapshot, PaymentEventLite, investments_value_btc_at, manual_assets_value_btc_at,
    onchain_balance_at, reconstruct_net_worth_history,
};
pub use payjoin::{PayjoinNegotiation, PayjoinOutputSummary};
pub use registry::{
    LotSelection, REGISTRY_FILE_NAME, REGISTRY_KDF_FILE_NAME, Registry, WalletKind, WalletRecord,
    open_registry, open_registry_in_memory,
};
pub use send_policy::{
    ABSOLUTE_FEE_FLOOR_SAT, CpfpPlan, MAX_FEE_RATE_SAT_PER_VB, PsbtOutputInfo, PsbtSummary,
    absolute_fee_limit_sat,
};
pub use watch::{PendingWatchScan, WatchAccount, WatchBip, WatchScanUpdate};
