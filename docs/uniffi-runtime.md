# UniFFI runtime hazards on Windows MSVC — investigation log

> **Status**: workaround applied (May 2026). The upstream bugs are not
> fixed; if/when they are, see "Removing the workaround" below.

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
| Method on `WalletHandle` returning `Result<String, _>`    | ✅ works |
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

### Hazard 2 — Bindgen-side ABI bug for non-`WalletHandle` Object methods

After Hazard 1 is fixed, free functions and methods on `#[uniffi::Object]`
types **other than** the first one in declaration order still crash. The
crash is in the same `RustBuffer.free` site, but the buffer arrives in a
shape the runtime cannot deallocate. We have not narrowed this further:
we tried adding non-ZST fields, mirroring `WalletHandle`'s `Mutex<...>`
layout, returning `Result<Arc<Self>, _>` from the constructor, adding
dummy method args — none of those moved the needle. The bug is reachable
from a minimal repro (a `MnemonicGenerator` Object with a
`Mutex<MnemonicGeneratorState>` field whose method returns a string
literal) so it is not specific to anything in our wallet code.

**Workaround**: expose any function that returns `Result<String, _>` as
a **method on `WalletHandle`**. The call site can construct a throwaway
`WalletHandle` from a public placeholder mnemonic, invoke the method, and
discard the handle — see `apps/desktop/.../screens/GenerateMnemonicScreen.kt`
for the pattern.

## What is in the tree because of this

- `.cargo/config.toml` — `+crt-static` rustflags for the MSVC target.
- `crates/bhodl-uniffi/src/commonMain/rust/lib.rs` — `generate_random_mnemonic`
  is a method on `WalletHandle` rather than a free function.
- `apps/desktop/.../screens/GenerateMnemonicScreen.kt` — uses a throwaway
  `WalletHandle` to host the mnemonic-generation FFI call. The
  `PLACEHOLDER_MNEMONIC` is the canonical BIP86 test vector
  (`abandon abandon ... about`); it never reaches disk because the
  throwaway handle is built via in-memory `WalletHandle.fromMnemonic`,
  not the persistent `create_or_load_persistent` constructor.
- `shared/src/jvmTest/kotlin/io/bhodl/ffi/UniffiSmokeTest.kt` — JVM smoke
  harness that exercises the three FFI shapes above. Run it via
  `./gradlew :shared:jvmTest --tests "io.bhodl.ffi.UniffiSmokeTest"`.

## Removing the workaround

When upstream uniffi-rs / Ubique fixes either hazard:

1. Re-run `UniffiSmokeTest`. If it passes with `generate_mnemonic` reinstated
   as a free function, you can roll back the workaround.
2. Drop `+crt-static` from `.cargo/config.toml` only if Hazard 1 is fixed
   on the runtime side (likely needs a new Maven release). Keep it
   otherwise — static-CRT is a marginal hardening, not a regression.
3. Delete the throwaway `WalletHandle` block in `GenerateMnemonicScreen.kt`
   and replace with the direct free-function call.
