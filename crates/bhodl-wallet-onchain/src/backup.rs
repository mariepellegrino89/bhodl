//! Encrypted backup of the wallet seed.
//!
//! What this is for: a paper-only mnemonic disaster-recovery file.
//! The user picks a separate password (NOT the unlock PIN — that is
//! tied to the `SQLCipher` store on this device); we serialise the
//! seed material to JSON, encrypt it with [`age`] using that
//! password, and ASCII-armor the result so the user can save it as
//! plaintext, paste it into a password manager, or print it.
//!
//! What this is NOT: a hot backup of the live wallet state. We do
//! not export labels, contacts, or BDK's chain state — those can
//! be rebuilt from the seed by any BIP86-aware wallet (including
//! this one, on import). Keeping the bundle minimal also means a
//! shorter ASCII blob, which the user is more likely to handle
//! correctly.
//!
//! Threat model:
//! - The bundle leaks only what `age` allows to leak: the file is
//!   marked as a `passphrase`-encrypted age file, with one
//!   scrypt-protected stanza. An attacker who steals the file but
//!   does not know the password runs a full scrypt brute-force per
//!   guess. age's default scrypt parameters (work factor 18) cost
//!   roughly 1 second per guess on a modern CPU — the user is
//!   responsible for picking a password that is not in a dictionary.
//! - The bundle MUST be opened on the user's device only. Pasting it
//!   into a web form (e.g. a "decrypt my age file" service) is the
//!   same as handing the seed to whoever runs the form.
//!
//! `BackupBundle` is `serde::{Serialize, Deserialize}` so the format
//! evolution lives in one place — bumping the schema is a `version`
//! bump + a migration test.
use age::secrecy::SecretString;
use serde::{Deserialize, Serialize};
use std::io::Write;

use crate::error::OnchainError;

/// On-disk JSON shape for an exported seed bundle.
///
/// `version` is bumped on any breaking schema change; readers must
/// reject unknown versions to avoid silently dropping fields.
///
/// `ZeroizeOnDrop` wipes the `mnemonic` and `passphrase` String fields
/// when the struct is dropped. The `u32` fields (`version`) implement
/// `Zeroize` natively so no `#[zeroize(skip)]` is required. `network`
/// is non-secret but gets zeroed too — cheap and conservative.
/// `Serialize` / `Deserialize` are unaffected by the `ZeroizeOnDrop`
/// derive (it only adds a `Drop` impl).
///
/// `Debug` is implemented by hand and redacts the seed material so a
/// stray `{:?}` (panic message, test assertion, log line) can never
/// print the mnemonic or passphrase.
#[derive(Clone, Serialize, Deserialize, PartialEq, Eq, zeroize::ZeroizeOnDrop)]
pub struct BackupBundle {
    /// Bumped on every breaking schema change. Currently `1`.
    // u32 implements Zeroize; no skip needed.
    pub version: u32,
    /// Free-form descriptor — `mainnet`, `signet`, `testnet4`,
    /// `regtest`. Mirrors [`bhodl_core::Network`] but we keep it as
    /// a bare string so the bundle survives a network enum bump.
    pub network: String,
    /// BIP-39 mnemonic phrase (12 / 15 / 18 / 21 / 24 words).
    pub mnemonic: String,
    /// BIP-39 passphrase. Empty string when the wallet was created
    /// without one.
    pub passphrase: String,
}

impl BackupBundle {
    /// Schema version this build emits and refuses to import below.
    pub const CURRENT_VERSION: u32 = 1;
    /// Oldest schema version a reader accepts. `0` was never emitted.
    pub const MIN_VERSION: u32 = 1;
}

impl std::fmt::Debug for BackupBundle {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("BackupBundle")
            .field("version", &self.version)
            .field("network", &self.network)
            .field("mnemonic", &"<redacted>")
            .field("passphrase", &"<redacted>")
            .finish()
    }
}

/// Maximum age scrypt work factor (`N = 2^log_n`) accepted on import.
///
/// age calibrates the work factor at export time to ~1 s on the
/// exporting machine (typically 18-20 on a desktop). Without a cap a
/// malicious backup can request e.g. `log_n = 40` and turn "Restore from
/// backup" into a multi-hour hang or an OOM abort. 21 = 2 GiB of scrypt
/// memory, the most we tolerate; it still accepts every backup a real
/// machine produces under age's ~1 s calibration.
pub(crate) const BACKUP_MAX_SCRYPT_WORK_FACTOR: u8 = 21;

/// Upper bound on the decrypted JSON size. A real bundle is < 1 KiB;
/// anything bigger is not ours and is refused before it is buffered.
const MAX_BUNDLE_JSON_LEN: u64 = 64 * 1024;

/// Initial capacity of the plaintext JSON buffer in [`encrypt_bundle`];
/// sized so a normal bundle (mnemonic + passphrase + metadata) never
/// reallocates and leaves stray plaintext copies behind.
const JSON_BUF_CAPACITY: usize = 8 * 1024;

/// Encrypt a [`BackupBundle`] under `password` and return the
/// ASCII-armored age payload.
///
/// The ASCII armor (`-----BEGIN AGE ENCRYPTED FILE-----`) means the
/// caller can paste the result into any text-only medium (email,
/// password manager, paper) and recover it byte-perfect.
///
/// # Errors
///
/// - [`OnchainError::Storage`] when JSON serialisation or age
///   encryption fails. age's failure modes are unusual (OOM during
///   scrypt, a broken `Write` impl) — none should fire on the
///   in-memory pipeline we use here.
pub fn encrypt_bundle(bundle: &BackupBundle, password: &str) -> Result<String, OnchainError> {
    // Reject an empty backup password at export time. An empty password
    // would let anyone who obtains the file decrypt it without any
    // brute-force cost. The decrypt path does NOT enforce this so that
    // older exports (created before this check) remain recoverable.
    if password.is_empty() {
        return Err(OnchainError::Storage(
            "backup password must not be empty".into(),
        ));
    }

    // Serialise into a pre-sized, zeroize-on-drop buffer. `to_vec` would
    // grow by doubling and leave un-wiped partial copies on the heap; the
    // capacity here is generous so a typical bundle never reallocates.
    let mut json = zeroize::Zeroizing::new(Vec::<u8>::with_capacity(JSON_BUF_CAPACITY));
    serde_json::to_writer(&mut *json, bundle)
        .map_err(|e| OnchainError::Storage(format!("backup serialise: {e}")))?;

    let secret = SecretString::from(password);
    let encryptor = age::Encryptor::with_user_passphrase(secret);

    let mut armored = Vec::new();
    let result = (|| -> Result<(), OnchainError> {
        let armor =
            age::armor::ArmoredWriter::wrap_output(&mut armored, age::armor::Format::AsciiArmor)
                .map_err(|e| OnchainError::Storage(format!("backup armor: {e}")))?;
        let mut writer = encryptor
            .wrap_output(armor)
            .map_err(|e| OnchainError::Storage(format!("backup encrypt: {e}")))?;
        writer
            .write_all(&json)
            .map_err(|e| OnchainError::Storage(format!("backup write: {e}")))?;
        let armor = writer
            .finish()
            .map_err(|e| OnchainError::Storage(format!("backup finalise: {e}")))?;
        armor
            .finish()
            .map_err(|e| OnchainError::Storage(format!("backup armor finalise: {e}")))?;
        Ok(())
    })();
    // Scrub the intermediate JSON buffer that carried the mnemonic +
    // passphrase in plaintext UTF-8. `Zeroizing` wipes it on drop
    // whether or not encryption succeeded; dropping here makes the
    // wipe happen before the (potentially slow) error path unwinds.
    drop(json);
    result?;

    String::from_utf8(armored).map_err(|e| OnchainError::Storage(format!("backup utf8: {e}")))
}

/// Decrypt an ASCII-armored age payload and parse the contained
/// [`BackupBundle`] JSON.
///
/// # Errors
///
/// - [`OnchainError::Storage`] when armor decoding, age decryption,
///   or JSON parsing fails. A wrong password manifests as a
///   "decryption error" inside age and surfaces here with a
///   `decrypt:` prefix.
pub fn decrypt_bundle(blob: &str, password: &str) -> Result<BackupBundle, OnchainError> {
    let armored = age::armor::ArmoredReader::new(blob.as_bytes());
    let decryptor = age::Decryptor::new(armored)
        .map_err(|e| OnchainError::Storage(format!("backup parse: {e}")))?;

    let secret = SecretString::from(password);
    let mut identity = age::scrypt::Identity::new(secret);
    identity.set_max_work_factor(BACKUP_MAX_SCRYPT_WORK_FACTOR);
    let reader = decryptor
        .decrypt(std::iter::once(&identity as &dyn age::Identity))
        .map_err(|e| OnchainError::Storage(format!("backup decrypt: {e}")))?;

    // Decrypted plaintext lives only in this pre-sized Zeroizing buffer
    // (no reallocation → no stranded un-wiped copies). One byte over the
    // cap is read so an oversized payload is detected, not truncated.
    let mut json = zeroize::Zeroizing::new(Vec::with_capacity(4096));
    let mut limited = std::io::Read::take(reader, MAX_BUNDLE_JSON_LEN + 1);
    std::io::Read::read_to_end(&mut limited, &mut json)
        .map_err(|e| OnchainError::Storage(format!("backup read: {e}")))?;
    if json.len() as u64 > MAX_BUNDLE_JSON_LEN {
        return Err(OnchainError::Storage("backup payload too large".into()));
    }
    let bundle = serde_json::from_slice::<BackupBundle>(&json)
        .map_err(|e| OnchainError::Storage(format!("backup deserialise: {e}")))?;
    drop(json);

    if bundle.version < BackupBundle::MIN_VERSION {
        return Err(OnchainError::Storage(format!(
            "backup version {} is not a valid BHODL backup version",
            bundle.version,
        )));
    }
    if bundle.version > BackupBundle::CURRENT_VERSION {
        return Err(OnchainError::Storage(format!(
            "backup version {} is newer than this build supports ({})",
            bundle.version,
            BackupBundle::CURRENT_VERSION,
        )));
    }
    Ok(bundle)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample_bundle() -> BackupBundle {
        BackupBundle {
            version: BackupBundle::CURRENT_VERSION,
            network: "signet".into(),
            mnemonic: "abandon abandon abandon abandon abandon abandon \
                       abandon abandon abandon abandon abandon about"
                .into(),
            passphrase: String::new(),
        }
    }

    #[test]
    fn round_trip_preserves_every_field() {
        let original = sample_bundle();
        let blob = encrypt_bundle(&original, "correct horse battery staple").unwrap();
        // Sanity: the ASCII armor header is what the user would
        // paste into a backup file.
        assert!(blob.starts_with("-----BEGIN AGE ENCRYPTED FILE-----"));
        let decrypted = decrypt_bundle(&blob, "correct horse battery staple").unwrap();
        assert_eq!(original, decrypted);
    }

    #[test]
    fn wrong_password_fails_to_decrypt() {
        let blob = encrypt_bundle(&sample_bundle(), "right-password").unwrap();
        let result = decrypt_bundle(&blob, "wrong-password");
        assert!(matches!(result, Err(OnchainError::Storage(_))));
    }

    #[test]
    fn passphrase_field_round_trips_when_non_empty() {
        let mut original = sample_bundle();
        original.passphrase = "my-13th-word".into();
        let blob = encrypt_bundle(&original, "pw").unwrap();
        let decrypted = decrypt_bundle(&blob, "pw").unwrap();
        assert_eq!(original.passphrase, decrypted.passphrase);
    }

    #[test]
    fn newer_schema_version_is_rejected() {
        // Forge a bundle that claims to be a future version.
        let mut forged = sample_bundle();
        forged.version = BackupBundle::CURRENT_VERSION + 7;
        let blob = encrypt_bundle(&forged, "pw").unwrap();
        let result = decrypt_bundle(&blob, "pw");
        assert!(matches!(result, Err(OnchainError::Storage(_))));
    }

    #[test]
    fn version_zero_is_rejected() {
        let mut forged = sample_bundle();
        forged.version = 0;
        let blob = encrypt_bundle(&forged, "pw").unwrap();
        let result = decrypt_bundle(&blob, "pw");
        assert!(matches!(result, Err(OnchainError::Storage(_))));
    }

    #[test]
    fn debug_output_redacts_seed_material() {
        let mut bundle = sample_bundle();
        bundle.passphrase = "super-secret-25th-word".into();
        let dbg = format!("{bundle:?}");
        assert!(!dbg.contains("abandon"), "mnemonic leaked via Debug: {dbg}");
        assert!(
            !dbg.contains("super-secret"),
            "passphrase leaked via Debug: {dbg}"
        );
        assert!(dbg.contains("<redacted>"));
    }

    #[test]
    fn excessive_scrypt_work_factor_is_refused() {
        // Forge the scrypt stanza of a real backup to ask for
        // log_n = 22 (4 GiB of scrypt memory). The decryptor must
        // refuse it up front instead of attempting the derivation.
        let blob = encrypt_bundle(&sample_bundle(), "pw").unwrap();
        let mut raw = Vec::new();
        std::io::Read::read_to_end(
            &mut age::armor::ArmoredReader::new(blob.as_bytes()),
            &mut raw,
        )
        .unwrap();
        let header_end = raw
            .windows(4)
            .position(|w| w == b"\n---")
            .expect("age header terminator");
        let header = std::str::from_utf8(&raw[..header_end]).unwrap();
        let stanza = header
            .lines()
            .find(|l| l.starts_with("-> scrypt "))
            .expect("scrypt stanza");
        let (prefix, _log_n) = stanza.rsplit_once(' ').unwrap();
        let forged_n = BACKUP_MAX_SCRYPT_WORK_FACTOR + 1;
        let mut forged = header
            .replacen(stanza, &format!("{prefix} {forged_n}"), 1)
            .into_bytes();
        forged.extend_from_slice(&raw[header_end..]);
        let mut armored = Vec::new();
        let mut writer =
            age::armor::ArmoredWriter::wrap_output(&mut armored, age::armor::Format::AsciiArmor)
                .unwrap();
        writer.write_all(&forged).unwrap();
        writer.finish().unwrap();
        let forged_blob = String::from_utf8(armored).unwrap();

        let start = std::time::Instant::now();
        let result = decrypt_bundle(&forged_blob, "pw");
        assert!(matches!(result, Err(OnchainError::Storage(_))));
        assert!(start.elapsed() < std::time::Duration::from_secs(30));
    }

    #[test]
    fn truncated_blob_fails_loudly() {
        let blob = encrypt_bundle(&sample_bundle(), "pw").unwrap();
        // Drop the last 10 chars — should break either armor or
        // age framing depending on what we cut.
        let truncated = &blob[..blob.len().saturating_sub(20)];
        let result = decrypt_bundle(truncated, "pw");
        assert!(matches!(result, Err(OnchainError::Storage(_))));
    }

    // ---------------------------------------------------------------
    // T1-8 — zeroize intermediate JSON buffer
    // ---------------------------------------------------------------

    #[test]
    fn encrypt_bundle_round_trip_still_works_after_zeroize() {
        // Regression: the zeroize call on the intermediate `json`
        // buffer must run AFTER the age writer has consumed all the
        // bytes, otherwise we'd encrypt zeros.
        let original = sample_bundle();
        let blob = encrypt_bundle(&original, "pw-with-some-entropy").unwrap();
        let decrypted = decrypt_bundle(&blob, "pw-with-some-entropy").unwrap();
        assert_eq!(original.mnemonic, decrypted.mnemonic);
    }

    #[test]
    fn encrypt_bundle_rejects_empty_password() {
        // Security fix: an empty backup password must be refused at
        // export time. An empty password provides zero brute-force
        // resistance to anyone who obtains the file.
        let result = encrypt_bundle(&sample_bundle(), "");
        assert!(
            matches!(result, Err(OnchainError::Storage(_))),
            "encrypt_bundle must reject an empty password; got {result:?}"
        );
        // Verify the error message is informative.
        if let Err(OnchainError::Storage(msg)) = result {
            assert!(
                msg.contains("password must not be empty"),
                "error should mention empty password, got: {msg}"
            );
        }
    }
}
