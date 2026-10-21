//! Selection of the OS keystore that holds device secrets.
//!
//! Production builds use [`bhodl_keystore::platform_keystore`] (DPAPI /
//! Keychain / Secret Service, or the in-memory test store when
//! `BHODL_KEYSTORE=ephemeral-test`). Unit tests of this crate default to
//! the in-memory [`bhodl_keystore::EphemeralKeystore`] so they never touch
//! the developer's real keyring, and can swap in any fake per test thread
//! with [`override_for_test`].

use std::sync::Arc;

use bhodl_keystore::DeviceKeystore;

#[cfg(test)]
thread_local! {
    static TEST_OVERRIDE: std::cell::RefCell<Option<Arc<dyn DeviceKeystore>>> =
        const { std::cell::RefCell::new(None) };
}

/// The keystore every unlock / create path uses.
pub(crate) fn active() -> Arc<dyn DeviceKeystore> {
    #[cfg(test)]
    {
        TEST_OVERRIDE
            .with(|o| o.borrow().clone())
            .unwrap_or_else(|| Arc::new(bhodl_keystore::EphemeralKeystore))
    }
    #[cfg(not(test))]
    {
        Arc::from(bhodl_keystore::platform_keystore())
    }
}

/// Replace the keystore for the current test thread until the returned
/// guard drops.
#[cfg(test)]
pub(crate) fn override_for_test(ks: Arc<dyn DeviceKeystore>) -> TestKeystoreGuard {
    TEST_OVERRIDE.with(|o| *o.borrow_mut() = Some(ks));
    TestKeystoreGuard
}

/// Restores the default test keystore on drop.
#[cfg(test)]
#[derive(Debug)]
pub(crate) struct TestKeystoreGuard;

#[cfg(test)]
impl Drop for TestKeystoreGuard {
    fn drop(&mut self) {
        TEST_OVERRIDE.with(|o| *o.borrow_mut() = None);
    }
}
