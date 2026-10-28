# UniFFI runtime hazards on Windows MSVC — investigation log

> **Status**: workaround applied (May 2026). The upstream bugs are not
> fixed; if/when they are, see "Removing the workaround" below.
>
> **2026-10-23**: with the watch-only refactor `WalletHandle` was removed.
> `WatchHandle` is now the first `#[uniffi::Object]` in `lib.rs` and carries
> the `Result<String, _>` methods; the throwaway-`WalletHandle` mnemonic
> workaround described in the original log no longer exists.

## TL;DR

`uniffi 0.28.3` + the `ch.ubique.uniffi.plugin 1.0.x` Kotlin Multiplatform
fork has two distinct ABI hazards on Windows MSVC. Both surface as
`java.lang.Error: Invalid memory access` thrown from JNA's
`Native.invokeVoid`, with the stack always pointing at
`uniffi.runtime.RustBufferHelper.free` (called from
`uniffi.runtime.FfiConverterString.lift`).

| Code shape                                                | Status   |
|-----------------------------------------------------------|----------|
| Free function returning `String`                          | ✅ works |
| Method on the **first** `#[uniffi::Object]` (`WatchHandle`) returning `Result<String, _>` | ✅ works |
| **Free function** returning `Result<String, _>`           | ❌ crash |
| Method on **any other** `#[uniffi::Object]` returning `Result<String, _>` | ❌ crash |

## Root causes (best current understanding)

### Hazard 1 — CRT mismatch between cdylibs

`bhodl_uniffi.dll` (our cdylib) and `uniffi_runtime.dll` (shipped in the
`ch.ubique.uniffi:runtime-jvm` Maven artifact) end up with different
`malloc/free` implementations when Rust links the C runtime dynamically
on the consumer side and statically on the runtime side. A `RustBuffer`
allocated inside the consumer cdylib is then freed by the runtime DLL
against a heap it does not own — `EXCEPTION_ACCESS_VIOLATION` follows.

**Fix**: pin `+crt-static` for the `x86_64-pc-windows-msvc` target in
`.cargo/config.toml`. Both cdylibs end up routing their allocations
through the same UCRT-static path; cross-DLL frees stop crashing.

### Hazard 2 — Bindgen-side ABI bug for Object methods other than the first Object's

After Hazard 1 is fixed, free functions and methods on `#[uniffi::Object]`
types **other than** the first one in declaration order still crash. The
crash is in the same `RustBuffer.free` site, but the buffer arrives in a
shape the runtime cannot deallocate. We have not narrowed this further:
we tried adding non-ZST fields, mirroring `WalletHandle`'s (then the first
Object) `Mutex<...>`
layout, returning `Result<Arc<Self>, _>` from the constructor, adding
dummy method args — none of those moved the needle. The bug is reachable
from a minimal repro (a `MnemonicGenerator` Object with a
`Mutex<MnemonicGeneratorState>` field whose method returns a string
literal) so it is not specific to anything in our wallet code.

**Workaround**: expose any function that returns `Result<String, _>` as
a **method on the first `#[uniffi::Object]` declared in `lib.rs`**. Until
2026-10-23 that was `WalletHandle`, and `generate_random_mnemonic` lived
on it: the desktop wizard built a throwaway `WalletHandle` from a public
test-vector mnemonic just to call it. Both are gone with the watch-only
refactor. Today the first Object is `WatchHandle` (receive addresses,
labels JSONL export and other `Result<String, _>` methods live there), and
the crate docs say it must stay first.

## What is in the tree because of this

- `.cargo/config.toml` — `+crt-static` rustflags for the MSVC target.
- `crates/bhodl-uniffi/src/commonMain/rust/lib.rs` — `WatchHandle` is
  declared before `RegistryHandle`, with a comment on both the crate docs
  and the struct saying it must stay the first `#[uniffi::Object]`.
  Methods returning `Result<String, _>` (`peek_address_at`,
  `next_receive_address`, `next_unused_receive_address`,
  `export_labels_jsonl`) are on `WatchHandle`.
- `shared/src/jvmTest/kotlin/io/bhodl/ffi/UniffiSmokeTest.kt` — JVM smoke
  harness for the FFI shapes above. Run it via
  `./gradlew :shared:jvmTest --tests "io.bhodl.ffi.UniffiSmokeTest"`.

## Removing the workaround

When upstream uniffi-rs / Ubique fixes either hazard:

1. Re-run `UniffiSmokeTest` with a free function (or a method on
   `RegistryHandle`) returning `Result<String, _>`. If it passes, the
   declaration-order constraint on `WatchHandle` can be dropped.
2. Drop `+crt-static` from `.cargo/config.toml` only if Hazard 1 is fixed
   on the runtime side (likely needs a new Maven release). Keep it
   otherwise — static-CRT is a marginal hardening, not a regression.
