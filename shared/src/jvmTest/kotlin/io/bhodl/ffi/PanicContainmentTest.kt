package io.bhodl.ffi

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Regression for the release-profile panic policy: the shipped cdylib is
 * built with `panic = "unwind"` so UniFFI's catch_unwind containment turns
 * a Rust panic into a recoverable [InternalException] at the FFI boundary
 * instead of aborting the whole JVM process. The Rust side of the probe is
 * `probe_panic` in crates/bhodl-uniffi (test-only, deliberately panics).
 *
 * If this test ever "passes" by crashing the test JVM, the release profile
 * regressed back to `panic = "abort"` (see the `[profile.release]`
 * comment in the workspace Cargo.toml and docs/handbook.md §4.3).
 */
class PanicContainmentTest {

    /**
     * A Rust panic inside the cdylib must surface as a typed, catchable
     * exception. Reaching the asserts below at all proves the JVM
     * survived — under `panic = "abort"` the process would be dead here.
     */
    @Test
    fun `probePanic raises a recoverable InternalException and the JVM survives`() {
        val ex = assertFailsWith<InternalException> {
            probePanic()
        }
        assertTrue(
            "panic-probe" in (ex.message ?: ""),
            "expected the probe panic message to cross the FFI, got: ${ex.message}",
        )
    }
}
