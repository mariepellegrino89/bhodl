//! Windows backend: DPAPI (`CryptProtectData`) in `CurrentUser` scope.
//!
//! The device secret is encrypted under the Windows account's DPAPI
//! master key (itself protected by the account's logon credentials and
//! stored under `%APPDATA%\Microsoft\Protect`, outside the BHODL data
//! dir). The resulting blob is the [`WrapRecord::handle`] and lives in
//! the KDF descriptor: copying the data dir to another machine or
//! account yields a blob nobody but the original account can decrypt.
//!
//! No `CRYPTPROTECT_LOCAL_MACHINE` flag (that would let every account on
//! the machine decrypt), and `CRYPTPROTECT_UI_FORBIDDEN` so a call can
//! never block on a prompt. A fixed application entropy value binds the
//! blob to BHODL: other software running as the same user cannot decrypt
//! it with a plain `CryptUnprotectData` call without knowing the label
//! (defence in depth only — same-user code can read this source).
#![allow(unsafe_code)]

use std::ptr;

use windows_sys::Win32::{
    Foundation::{GetLastError, LocalFree},
    Security::Cryptography::{
        CRYPT_INTEGER_BLOB, CRYPTPROTECT_UI_FORBIDDEN, CryptProtectData, CryptUnprotectData,
    },
};
use zeroize::Zeroize;

use crate::{
    DEVICE_SECRET_LEN, DeviceKeystore, DeviceSecret, KeystoreBackend, KeystoreError, WrapRecord,
    ensure_backend, generate_device_secret, secret_from_bytes,
};

/// Optional entropy mixed into every BHODL DPAPI blob.
const ENTROPY: &[u8] = b"bhodl-device-key-v1";

/// DPAPI-backed device keystore (Windows only).
#[derive(Debug, Clone, Copy, Default)]
pub(crate) struct DpapiKeystore;

fn blob(bytes: &[u8]) -> CRYPT_INTEGER_BLOB {
    CRYPT_INTEGER_BLOB {
        cbData: u32::try_from(bytes.len()).expect("DPAPI input fits in u32"),
        // DPAPI never writes through the input pointers.
        pbData: bytes.as_ptr().cast_mut(),
    }
}

/// Copy a DPAPI-allocated output buffer into a Vec and free it,
/// optionally wiping it first (plaintext outputs).
///
/// # Safety
/// `out` must be an output blob filled by a successful DPAPI call.
unsafe fn take_output(out: CRYPT_INTEGER_BLOB, wipe: bool) -> Vec<u8> {
    let len = out.cbData as usize;
    // SAFETY: on success DPAPI returns a LocalAlloc'd buffer of `cbData`
    // bytes at `pbData`.
    let slice = unsafe { std::slice::from_raw_parts_mut(out.pbData, len) };
    let copy = slice.to_vec();
    if wipe {
        slice.zeroize();
    }
    // SAFETY: the buffer was allocated by DPAPI with LocalAlloc and is
    // released exactly once here.
    unsafe {
        LocalFree(out.pbData.cast());
    }
    copy
}

fn protect(plaintext: &[u8]) -> Result<Vec<u8>, KeystoreError> {
    let input = blob(plaintext);
    let entropy = blob(ENTROPY);
    let mut out = CRYPT_INTEGER_BLOB {
        cbData: 0,
        pbData: ptr::null_mut(),
    };
    // SAFETY: all pointers reference live, correctly sized buffers for
    // the duration of the call; the description / reserved / prompt
    // arguments are documented as optional (null).
    let ok = unsafe {
        CryptProtectData(
            &raw const input,
            ptr::null(),
            &raw const entropy,
            ptr::null(),
            ptr::null(),
            CRYPTPROTECT_UI_FORBIDDEN,
            &raw mut out,
        )
    };
    if ok == 0 {
        // SAFETY: trivially safe Win32 call.
        let code = unsafe { GetLastError() };
        return Err(KeystoreError::Unavailable(format!(
            "CryptProtectData failed (Win32 error {code})"
        )));
    }
    // SAFETY: `out` was filled by the successful call above.
    Ok(unsafe { take_output(out, false) })
}

fn unprotect(ciphertext: &[u8]) -> Result<Vec<u8>, KeystoreError> {
    let input = blob(ciphertext);
    let entropy = blob(ENTROPY);
    let mut out = CRYPT_INTEGER_BLOB {
        cbData: 0,
        pbData: ptr::null_mut(),
    };
    // SAFETY: as in `protect`; the data-description out-pointer is null
    // (not requested).
    let ok = unsafe {
        CryptUnprotectData(
            &raw const input,
            ptr::null_mut(),
            &raw const entropy,
            ptr::null(),
            ptr::null(),
            CRYPTPROTECT_UI_FORBIDDEN,
            &raw mut out,
        )
    };
    if ok == 0 {
        // SAFETY: trivially safe Win32 call.
        let code = unsafe { GetLastError() };
        // DPAPI is local and always present: a failure means the blob
        // belongs to another account / machine (or was corrupted), i.e.
        // this account can never decrypt it.
        return Err(KeystoreError::NotFound(format!(
            "this Windows account cannot decrypt the device key (Win32 error {code}); \
             it was created by another user, on another machine, or before an OS reinstall"
        )));
    }
    // SAFETY: `out` was filled by the successful call above.
    Ok(unsafe { take_output(out, true) })
}

impl DeviceKeystore for DpapiKeystore {
    fn name(&self) -> &'static str {
        KeystoreBackend::WindowsDpapi.display_name()
    }

    fn probe(&self) -> Result<(), KeystoreError> {
        let probe = generate_device_secret()?;
        let wrapped = protect(probe.as_ref())?;
        let back = secret_from_bytes(unprotect(&wrapped)?)?;
        if *back == *probe {
            Ok(())
        } else {
            Err(KeystoreError::Unavailable(
                "DPAPI round trip returned different bytes".into(),
            ))
        }
    }

    fn store(&self, secret: &[u8; DEVICE_SECRET_LEN]) -> Result<WrapRecord, KeystoreError> {
        let wrapped = protect(secret)?;
        Ok(WrapRecord {
            backend: KeystoreBackend::WindowsDpapi,
            handle: hex::encode(wrapped),
        })
    }

    fn load(&self, record: &WrapRecord) -> Result<DeviceSecret, KeystoreError> {
        ensure_backend(record, KeystoreBackend::WindowsDpapi)?;
        let wrapped = hex::decode(&record.handle)
            .map_err(|_| KeystoreError::NotFound("DPAPI blob is not valid hex".into()))?;
        secret_from_bytes(unprotect(&wrapped)?)
    }

    fn delete(&self, _record: &WrapRecord) -> Result<(), KeystoreError> {
        // The wrapped secret lives in the descriptor itself.
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dpapi_round_trip_current_user() {
        let ks = DpapiKeystore;
        ks.probe().unwrap();
        let secret = generate_device_secret().unwrap();
        let rec = ks.store(&secret).unwrap();
        assert_eq!(rec.backend, KeystoreBackend::WindowsDpapi);
        // The blob is not the plaintext.
        assert!(!rec.handle.contains(&hex::encode(secret.as_ref())));
        assert_eq!(*ks.load(&rec).unwrap(), *secret);
    }

    #[test]
    fn tampered_blob_is_not_found() {
        let ks = DpapiKeystore;
        let secret = generate_device_secret().unwrap();
        let mut rec = ks.store(&secret).unwrap();
        // Flip a byte near the end (inside the ciphertext / MAC).
        let mut raw = hex::decode(&rec.handle).unwrap();
        let n = raw.len();
        raw[n - 5] ^= 0xff;
        rec.handle = hex::encode(raw);
        assert!(matches!(ks.load(&rec), Err(KeystoreError::NotFound(_))));
    }

    #[test]
    fn blob_without_bhodl_entropy_does_not_decrypt() {
        // A blob protected WITHOUT our entropy must not unwrap through
        // our path (entropy mismatch).
        let input = [7u8; 32];
        let in_blob = blob(&input);
        let mut out = CRYPT_INTEGER_BLOB {
            cbData: 0,
            pbData: ptr::null_mut(),
        };
        // SAFETY: as in `protect`.
        let ok = unsafe {
            CryptProtectData(
                &raw const in_blob,
                ptr::null(),
                ptr::null(),
                ptr::null(),
                ptr::null(),
                CRYPTPROTECT_UI_FORBIDDEN,
                &raw mut out,
            )
        };
        assert_ne!(ok, 0);
        // SAFETY: filled by the successful call above.
        let wrapped = unsafe { take_output(out, false) };
        assert!(unprotect(&wrapped).is_err());
    }
}
