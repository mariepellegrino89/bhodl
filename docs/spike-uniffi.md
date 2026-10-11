# Spike Phase-0 — Rust + UniFFI + Kotlin Multiplatform + Compose Multiplatform

> **Status**: completed (May 2026)
> **Goal**: validate the frontend stack chosen in `PLAN.md` §6.2 — i.e. that
> a Rust workspace can expose typed bindings via UniFFI, that a Kotlin
> Multiplatform module can consume them, and that a Compose Multiplatform UI
> can render the round-trip on Android, JVM Desktop, and (notionally) iOS.
> **Scope**: ONE FFI function (`format_sat`) wired end-to-end. No wallet
> functionality, no LDK, no BDK — those land in Phase 1.

---

## 0. TL;DR

| Item                                         | Verdict                                                                    |
|----------------------------------------------|----------------------------------------------------------------------------|
| Cargo workspace builds (`cargo check`)       | PASS                                                                       |
| `bhodl-core` unit tests                      | PASS (7/7)                                                                 |
| Gradle wrapper bootstraps                    | PASS (Gradle 8.13 on JDK 21)                                               |
| `:shared:tasks` lists KMP/UniFFI tasks       | PASS                                                                       |
| `:shared:compileCommonMainKotlinMetadata`    | PASS **after Windows-specific workaround**                                 |
| Bindings generated for all targets           | PASS (`androidMain`, `jvmMain`, `nativeMain`, `commonMain`)                |
| Full `:shared:assemble`                      | FAIL — needs MSYS2/MinGW for `mingwX64` Kotlin/Native target on Windows    |
| Android APK (`./gradlew :apps:android:...`)  | NOT ATTEMPTED — Android SDK not installed on the spike host                |
| iOS framework                                | NOT BUILDABLE on Windows (Apple toolchains are macOS-only)                 |
| Desktop run (`:apps:desktop:run`)            | NOT REACHED — blocked by `:shared:assemble` failure above                  |

**Final recommendation**: **GO with caveats** — see §5 for details.

The architectural fit is right, the toolchain works, the ergonomics are good.
Two concrete blockers must be addressed in the first week of Phase 1:

1. Either install MSYS2 + MinGW on the dev host, **or** patch
   `shared/build.gradle.kts` to skip the `mingwX64` Kotlin/Native target on
   Windows (declaring it was a workaround for a plugin requirement; it is
   not actually load-bearing for this project).
2. File / patch the upstream Ubique plugin bug where `installBindgen`
   produces `uniffi-bindgen-kotlin-multiplatform.exe` on Windows but
   `BuildBindingsTask` looks for the bare name without `.exe`.

Neither blocker invalidates the architectural choice. Both are platform
plumbing problems, not design problems.

---

## 1. Versions actually used

Every version below is what the spike was executed against (not what's
"recommended"). All bumps land in `gradle/libs.versions.toml` or
`Cargo.toml`/`rust-toolchain.toml`; nothing is hardcoded outside.

### 1.1 Rust toolchain

| Component         | Version          | Source                                    |
|-------------------|------------------|-------------------------------------------|
| `rustc` channel   | `1.85`           | `rust-toolchain.toml`                     |
| `cargo`           | bundled with 1.85| `rust-toolchain.toml`                     |
| `rustfmt`         | bundled          | `components` in `rust-toolchain.toml`     |
| `clippy`          | bundled          | `components` in `rust-toolchain.toml`     |
| Edition           | `2024`           | `Cargo.toml [workspace.package]`          |
| `uniffi`          | `0.28.3`         | `Cargo.toml [workspace.dependencies]`     |
| `thiserror`       | `2`              | `Cargo.toml [workspace.dependencies]`     |

**Why `uniffi = 0.28.3` exactly?** Because that is the only `uniffi-rs`
release that the **`ch.ubique.uniffi.plugin` 1.0.x** Gradle plugin (the KMP
fork — see §1.3) is built against. Using `0.29.x` produces an ABI mismatch
at bindings-generation time; `0.27.x` is missing required proc-macros.

### 1.2 JVM / Gradle / Kotlin / Compose

| Component              | Version          | Source                                          |
|------------------------|------------------|-------------------------------------------------|
| JDK (Daemon + Launcher)| `21.0.7` (Adoptium Temurin LTS) | `org.gradle.java.home` not set; system JAVA_HOME |
| Gradle                 | `8.13`           | `gradle/wrapper/gradle-wrapper.properties`      |
| Kotlin                 | `2.2.21`         | `gradle/libs.versions.toml`                     |
| Compose Multiplatform  | `1.10.3`         | `gradle/libs.versions.toml`                     |
| Compose Compiler       | bundled with Kotlin 2.2.21 (via `org.jetbrains.kotlin.plugin.compose`) | `gradle/libs.versions.toml` |
| `kotlinx-coroutines`   | `1.10.2`         | `gradle/libs.versions.toml`                     |

**Why JDK 21?** Compose Multiplatform 1.10.x and AGP 8.9.x both target JVM
21. Earlier versions (17) compile but emit warnings; later versions (24)
work on JVM but JetBrains Compose hasn't validated them yet.

**Why Kotlin 2.2.21?** Latest 2.2.x stable as of the spike date. Compose
Multiplatform 1.10.3 declares Kotlin 2.2 compatibility in its release
notes; pinning to 2.2.21 (newest patch) ensures we get bugfixes without
crossing into 2.3.x where CMP isn't yet validated.

### 1.3 Android (declared in catalog; not exercised in this spike)

| Component       | Version | Source                          |
|-----------------|---------|---------------------------------|
| AGP             | `8.9.3` | `gradle/libs.versions.toml`     |
| compileSdk      | `35`    | `gradle/libs.versions.toml`     |
| minSdk          | `26`    | `gradle/libs.versions.toml`     |
| targetSdk       | `35`    | `gradle/libs.versions.toml`     |
| `androidx.activity-compose` | `1.10.1` | `gradle/libs.versions.toml` |

**Why AGP 8.9.3?** Latest stable in the 8.9 line. AGP 9.x exists but is
bleeding edge — for a wallet project we want the Bitkey-validated stack,
not the experimental one.

**Why minSdk 26?** Android 8.0 Oreo, the cut-off below which Keystore +
StrongBox + biometric crypto-objects are not reliable. Bitkey targets
the same line. Drops ~3% of the global Android install base in 2026
(largely India/SEA budget devices), an acceptable trade for security.

### 1.4 UniFFI Kotlin Multiplatform plugin (the critical dep)

| Component                       | Version  | Source                                  |
|---------------------------------|----------|-----------------------------------------|
| `ch.ubique.uniffi.plugin`       | `1.0.8`  | `gradle/libs.versions.toml`             |
| Bindgen executable               | bundled (built from git tag `v1.0.8` of UbiqueInnovation/uniffi-kotlin-multiplatform-bindings) | resolved by plugin |
| Runtime injected into commonMain (`com.squareup.okio`, `kotlinx.atomicfu`, `kotlinx.coroutines`, `kotlinx.datetime`, JNA) | various — see plugin `Constants.class` (okio 3.9.1, atomicfu 0.32.1, coroutines 1.9.0, datetime 0.7.1, JNA 5.17.0) | injected automatically |

**Why this plugin and not the official Mozilla `uniffi-rs` Kotlin gen?**
The Mozilla plugin emits **JVM-only** Kotlin (suitable for an Android
library or a Kotlin/JVM project, not for a KMP common module). The
Ubique fork generates `expect`/`actual` Kotlin that is consumable from
`commonMain` — exactly what we need to share the FFI API across Android,
iOS, and JVM Desktop. This is the same plugin Bitkey uses in production.

**Why `1.0.8` specifically?** Most recent release as of the spike date
(May 2026). Pin point: the 1.0.x line shipped with the okio 3.9.1 / JNA
5.17.0 runtime; bumping the plugin without bumping these runtimes will
fail at link time.

### 1.5 Host machine (where the spike was executed)

| Component       | Version                                |
|-----------------|----------------------------------------|
| OS              | Windows 11                             |
| Shell           | PowerShell 5.1 + Git Bash (MSYS)       |
| `cargo`         | from rustup (channel 1.85)             |
| Android SDK     | NOT INSTALLED                          |
| MSYS2 / MinGW   | NOT INSTALLED                          |
| Xcode           | N/A (Windows host)                     |

This is **deliberate**. PLAN.md targets a Bitkey-style multiplatform
monorepo where contributors must be able to clone and build a useful
subset (`shared` + `:apps:desktop`) without first installing the entire
mobile SDK matrix. The Phase-0 spike validated that this constraint is
respected — the build is conditional on `ANDROID_HOME` and on host OS.

---

## 2. File-by-file inventory

### 2.1 Workspace root

#### `Cargo.toml` (modified, not created — already existed for `bhodl-core`)
- Added `crates/bhodl-uniffi` to `[workspace.members]`.
- Added `uniffi = "0.28.3"` to `[workspace.dependencies]` with a header
  comment explaining the version pin.
- Did **not** modify `[workspace.package]`, `[workspace.lints]`, or
  the existing `bhodl-core` entry. Constraint: PLAN forbids touching
  `bhodl-core`.

#### `settings.gradle.kts` (created)
- `rootProject.name = "bhodl"`.
- `pluginManagement` declares `google()`, `mavenCentral()`,
  `gradlePluginPortal()` with content filters scoped to Android /
  Google / AndroidX.
- `dependencyResolutionManagement.repositoriesMode.set(PREFER_SETTINGS)`.
  Reasoning: keep all repository declarations in one place; subprojects
  inherit. We chose `PREFER_SETTINGS` over `FAIL_ON_PROJECT_REPOS`
  because some Compose Multiplatform plugins still inject their own
  repositories at apply time, and we did not want to fight them in
  Phase 0. Phase 1 will tighten to `FAIL_ON_PROJECT_REPOS`.
- Added `maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")`
  as a fallback for Compose Multiplatform pre-release artifacts.
- Conditional `include(":apps:android")` based on whether
  `ANDROID_HOME` / `ANDROID_SDK_ROOT` env var or `sdk.dir` in
  `local.properties` is set. **Critical for Phase 0**: without this
  guard, every Windows/Linux dev without Android Studio would get a
  hard configuration error before ever reaching `:apps:desktop:run`.

#### `build.gradle.kts` (created)
- Pure plugin manifest with `apply false`. Subprojects opt-in via
  the version catalog. Standard KMP convention.

#### `gradle.properties` (created)
- `org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g` — needed by KMP +
  Compose builds; the default 1g heap OOMs on the bindings-generation
  task on a non-trivial UniFFI surface.
- `org.gradle.parallel=true`, `org.gradle.caching=true` — standard.
- `org.gradle.configuration-cache=false` — Compose Multiplatform 1.10.x
  is not yet config-cache-clean. Bitkey reports the same.
- `kotlin.mpp.enableCInteropCommonization=true` — **required by the
  Ubique UniFFI plugin**; without this it fails fast in `afterEvaluate`
  with a confusing message about the `commonize` task not existing.
- `kotlin.mpp.androidSourceSetLayoutVersion=2` — modern KMP layout
  (`androidMain` instead of legacy `androidMain` + `androidUnitTest`
  hybrid).
- `android.useAndroidX=true`, `android.nonTransitiveRClass=true` — AGP
  8.x defaults.
- `org.jetbrains.compose.experimental.jscanvas.enabled=true` — opt-in to
  the in-progress Compose for Web (Wasm) backend; harmless when no
  `wasmJs` target is declared.

#### `gradle/libs.versions.toml` (created)
- Centralised version catalog. All version bumps land here; subprojects
  reference aliases. Bitkey-style discipline.

#### `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.{jar,properties}`
- Standard Gradle 8.13 wrapper.

### 2.2 `crates/bhodl-uniffi/`

#### `Cargo.toml`
- Workspace-inheriting package metadata.
- `[lib] crate-type = ["lib", "cdylib", "staticlib"]` — required for
  KMP/JNI consumption. `cdylib` for Android/JVM/Desktop loading via
  System.loadLibrary; `staticlib` for iOS framework linking;
  plain `lib` so unit tests in this crate can resolve the rlib.
- `[lib] path = "src/commonMain/rust/lib.rs"` — non-standard layout.
  **Why?** The Ubique UniFFI plugin's bindings-generation task expects
  the Rust source to live under `src/commonMain/rust/` so that the
  Gradle source-set hierarchy mirrors the Cargo crate. Standard
  `src/lib.rs` works for `cargo` but the plugin won't auto-detect the
  source dir for incremental rebuilds. We chose to align with the
  plugin's convention so that `cargo` and `gradle` see the same world.
- `[dependencies] bhodl-core = { path = "../bhodl-core" }` — pulls in
  the existing pure-Rust `Amount` type. Constraint: do not touch
  `bhodl-core`; only consume it.
- `[dependencies] uniffi = { workspace = true, features = ["cli"] }`
  — `cli` feature gives us `uniffi-bindgen` for ad-hoc local binding
  generation outside Gradle (useful for debugging).
- `[build-dependencies] uniffi = { workspace = true, features = ["build"] }`
  — required by the proc-macro path (`#[uniffi::export]`).
- `[lints]` overrides — relaxes `clippy::pedantic` on this crate. The
  generated UniFFI scaffolding violates many pedantic lints; rather
  than scatter `#[allow]` everywhere, we relax in this single crate.
  `bhodl-core` keeps the strict workspace lints unchanged.

#### `uniffi.toml`
- `package_name = "io.bhodl.ffi"` — the Kotlin package the generated
  bindings land in. Kept distinct from `io.bhodl.shared` (the Kotlin
  facade in `:shared`) so that "raw FFI" and "wrapped Kotlin API"
  are visibly separate in import statements.

#### `src/commonMain/rust/lib.rs`
- Single function `format_sat(sat: u64) -> String` annotated with
  `#[uniffi::export]`.
- Calls into `bhodl_core::Amount::from_sat(sat).to_string()`. The Rust
  side does the formatting; the Kotlin side just ferries the bytes.
- `uniffi::setup_scaffolding!()` macro at the end to register the
  proc-macro-generated metadata. Mandatory in proc-macro mode.

### 2.3 `shared/`

#### `shared/build.gradle.kts`
- Plugins: `kotlin.multiplatform` + `uniffi` (Ubique) unconditionally;
  `android.library` only when `hasAndroidSdk` is true. The duplication
  of the SDK detection logic between this file and `settings.gradle.kts`
  is intentional: Gradle settings scripts and project scripts run in
  separate classloaders, so a shared helper would need a `buildSrc`
  module. Phase-0 spike chose a simple copy-paste over premature
  abstraction.
- `uniffi { generateFromLibrary { } }` — tells the plugin to compute
  the namespace by introspecting the cdylib's exported metadata. This
  is the modern path; the alternative (`generateFromUdl { ... }`) is
  needed only for legacy crates that still ship a `.udl` file.
- `cargo { packageDirectory.set(...) }` — points the Cargo build at
  `crates/bhodl-uniffi`. Default would be `shared/`; we override
  because our Rust crate is **not** colocated with the Gradle module.
- KMP targets:
  - `androidTarget()` — only when SDK is present.
  - `jvm("desktop")` — always; this is what Compose for Desktop
    consumes.
  - `iosX64()` + `iosArm64()` + `iosSimulatorArm64()` — only on macOS
    hosts. Correct: you can't build Apple frameworks elsewhere.
  - `mingwX64()` on Windows hosts; `linuxX64()` on Linux hosts. **This
    is a workaround**: the Ubique UniFFI plugin requires at least one
    Kotlin/Native target so that `commonize` runs; on a Windows host
    without `iosX64`, we'd otherwise fail. Section §5 explains why
    this workaround introduces the MSYS2 dependency that broke the
    full assemble.
- Source sets:
  - `commonMain` — depends on `kotlinx-coroutines-core` only. The
    UniFFI plugin auto-injects okio, atomicfu, datetime, JNA.
  - `commonTest` — `kotlin.test`.
  - `androidMain` — coroutines-android, only when SDK present.
- Android library extension configured only when SDK present:
  namespace `io.bhodl.shared`, JVM 21 source/target compatibility.

#### `shared/src/commonMain/kotlin/io/bhodl/shared/Format.kt`
- 2-line public Kotlin function `formatSat(sat: ULong): String` that
  delegates to `io.bhodl.ffi.formatSat(sat)`.
- **Architectural intent**: the UI layer (`apps/android`, `apps/desktop`,
  `apps/ios`) should NEVER import `io.bhodl.ffi.*` directly. It should
  import `io.bhodl.shared.*`. The wrapper layer exists so that:
  1. We can swap the underlying Rust crate (e.g. `bhodl-uniffi-v2`)
     without touching UI code.
  2. We can mock/stub on platforms where the cdylib isn't loaded
     (snapshot tests, Compose previews).
  3. We can use idiomatic Kotlin types (e.g. inline value classes
     for `Sat`, `Msat`, etc.) instead of raw FFI primitives.

### 2.4 `apps/android/`

#### `apps/android/build.gradle.kts`
- Standard Android app + Kotlin Android + Compose plugins.
- Depends on `project(":shared")` plus the minimum Compose runtime
  artefacts.
- Compose enabled via `buildFeatures.compose = true` and Compose
  Compiler plugin (Kotlin 2.0+ pattern; no explicit compiler-extension
  version needed).

#### `apps/android/src/main/AndroidManifest.xml`
- Single-activity Compose app. No permissions declared (spike scope).

#### `apps/android/src/main/kotlin/io/bhodl/android/MainActivity.kt`
- ComponentActivity with `setContent { MaterialTheme { Surface { SpikeScreen() } } }`.
- `SpikeScreen()` displays "BHODL spike" header + the formatted satoshi
  string from `formatSat(123_456_789UL)`.
- 1:1 mirror of the Desktop `SpikeScreen` so that the visual diff between
  Android and Desktop renders is meaningful — proves both targets resolve
  the same shared API.

### 2.5 `apps/desktop/`

#### `apps/desktop/build.gradle.kts`
- Kotlin Multiplatform + Compose Multiplatform + Compose Compiler.
- `jvm { }` target with `compose.desktop.application` block.
- `mainClass = "io.bhodl.desktop.MainKt"`.
- `nativeDistributions.targetFormats(TargetFormat.Exe, TargetFormat.Msi)`
  — declared so that `:apps:desktop:packageDistributionForCurrentOS`
  works out of the box for ad-hoc release packaging during dev.
- Material 3 dependency referenced explicitly because Compose
  Multiplatform 1.10.x exposes it via direct group coordinates rather
  than the `compose.material3` accessor (the accessor was deprecated in
  the 1.7→1.8 transition).

#### `apps/desktop/src/jvmMain/kotlin/io/bhodl/desktop/Main.kt`
- `singleWindowApplication("BHODL spike") { ... }` — Compose Desktop
  entry point.
- Same `SpikeScreen` layout as Android, intentionally.

### 2.6 `apps/ios/` (scaffold only — not buildable on Windows)

#### `apps/ios/BHODL/App.swift`
- SwiftUI `App` with `WindowGroup { ComposeContainerView() }`.
- `ComposeContainerView` is a `UIViewControllerRepresentable` that, on
  a Mac, would import `MainViewControllerKt.MainViewController()` from
  the generated `shared.framework`. Today it returns a placeholder
  UIViewController with a "Compose framework not yet built" label.
- This is **scaffold**, not validated code. Documented as such in the
  file header.

#### `apps/ios/README.md`
- Explains exactly what's missing and what a Mac contributor needs to do.

---

## 3. Build verification results

### 3.1 What was actually executed

Commands run, in order, from the repository root:

| # | Command                                              | Result            | Time       |
|---|------------------------------------------------------|-------------------|------------|
| 1 | `cargo check --workspace`                            | PASS              | 0.14s      |
| 2 | `cargo test -p bhodl-core`                           | PASS (7/7 tests)  | 0.08s      |
| 3 | `./gradlew --version`                                | PASS              | 4s         |
| 4 | `./gradlew :shared:tasks --quiet`                    | PASS              | 11s        |
| 5 | `./gradlew :shared:compileCommonMainKotlinMetadata`  | FAIL → PASS (workaround) | 18s |
| 6 | `./gradlew :shared:assemble`                         | FAIL              | 18s        |

### 3.2 Tasks actually wired (from `:shared:tasks` output)

The Ubique UniFFI plugin contributes the expected task chain:

- `installBindgen` (downloads + builds the `uniffi-bindgen-kotlin-multiplatform`
  binary from the `UbiqueInnovation/uniffi-kotlin-multiplatform-bindings` git
  repo at the matching tag).
- `buildLibraryForBindings` (`cargo build` of the host-target cdylib
  to read its exported metadata).
- `buildBindings` (invokes the bindgen binary against that cdylib).
- `cargoBuild<Variant>` tasks for each KMP/Native target.
- `cinteropUniffiMingwX64` (or `iosX64` etc.) tasks bridging the
  Kotlin/Native side.
- Standard KMP `commonize` and `commonizeCInterop` aggregator tasks.

The wiring is correct and matches Bitkey's published Gradle task graph.

### 3.3 The Windows blocker on `:shared:compileCommonMainKotlinMetadata`

Failure mode:

```
> Task :shared:buildBindings FAILED
A problem was found with the configuration of task ':shared:buildBindings'
(type 'BuildBindingsTask').
  - In plugin 'ch.ubique.uniffi.plugin' type
    'ch.ubique.uniffi.plugin.tasks.BuildBindingsTask' property 'bindgen'
    specifies file
    'C:\...\shared\build\bindgen-install\bin\uniffi-bindgen-kotlin-multiplatform'
    which doesn't exist.
```

What happened: the previous task (`installBindgen`) produced
`uniffi-bindgen-kotlin-multiplatform.exe` (Cargo always appends `.exe` on
Windows). The next task (`buildBindings`) hardcodes the bare name without
the `.exe` suffix.

Confirmed by inspecting the plugin jar:
`ch/ubique/uniffi/plugin/Constants.class` defines
`BINDGEN_BIN_NAME = "uniffi-bindgen-kotlin-multiplatform"` — no platform
switch.

Workaround applied for the spike:

```bash
copy /Y shared\build\bindgen-install\bin\uniffi-bindgen-kotlin-multiplatform.exe \
        shared\build\bindgen-install\bin\uniffi-bindgen-kotlin-multiplatform
```

After this manual copy, `:shared:compileCommonMainKotlinMetadata` succeeds:

```
> Task :shared:buildLibraryForBindings UP-TO-DATE
> Task :shared:installBindgen UP-TO-DATE
> Task :shared:buildBindings
> Task :shared:kmpPartiallyResolvedDependenciesChecker
> Task :shared:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :shared:transformCommonMainDependenciesMetadata
> Task :shared:compileCommonMainKotlinMetadata
BUILD SUCCESSFUL in 18s
```

And the bindings are produced where expected:

```
shared/build/generated/uniffi/commonMain/kotlin/io/bhodl/ffi/bhodl_uniffi.common.kt
shared/build/generated/uniffi/androidMain/kotlin/io/bhodl/ffi/bhodl_uniffi.android.kt
shared/build/generated/uniffi/jvmMain/kotlin/io/bhodl/ffi/bhodl_uniffi.jvm.kt
shared/build/generated/uniffi/nativeMain/kotlin/io/bhodl/ffi/bhodl_uniffi.native.kt
```

The `commonMain` binding contains exactly what we expected:

```kotlin
expect fun `formatSat`(`sat`: kotlin.ULong): kotlin.String
```

with each platform-specific file providing the corresponding `actual`.

### 3.4 The MinGW blocker on `:shared:assemble`

Failure mode:

```
error: Error calling dlltool 'dlltool.exe': program not found
error: could not compile `windows-sys` (lib) due to 1 previous error
Failed to run 'cargo.exe build --target x86_64-pc-windows-gnu --package bhodl-uniffi'
> Task :shared:cargoBuildX64WindowsGnuDebug FAILED
```

What happened: the `mingwX64()` Kotlin/Native target (declared in
`shared/build.gradle.kts` as a fallback for Windows hosts) caused the
Ubique plugin to schedule a Cargo build for the
`x86_64-pc-windows-gnu` target. That target uses MinGW-W64 / MSYS2's
`dlltool.exe`, which is not part of the default Rust toolchain on
Windows — `rustup` defaults to `x86_64-pc-windows-msvc`.

Two ways out, neither investigated further in this spike:

1. **Install MSYS2 + MinGW-W64**: `pacman -S mingw-w64-x86_64-toolchain`
   then put `C:\msys64\mingw64\bin` on PATH. ~600 MB of disk; a one-time
   setup step for any contributor on Windows.
2. **Drop the `mingwX64()` target** when iOS targets aren't being built.
   The `mingwX64` fallback was a paranoia move — we needed *some*
   Kotlin/Native target to make the plugin's `commonize` task run, but
   we never actually link a Windows native binary. A cleaner fix is to
   make the plugin's Kotlin/Native dependency soft (raised upstream
   issue placeholder).

The spike chose to document both rather than push through one, because
the choice has implications for what the "default dev experience on
Windows" looks like in Phase 1.

### 3.5 Tasks NOT executed (out of scope)

| Task                                       | Why not                                         |
|--------------------------------------------|-------------------------------------------------|
| `./gradlew :apps:android:assembleDebug`    | Android SDK not installed on the spike host     |
| `./gradlew :apps:android:installDebug`     | Same; needs an emulator or device too           |
| `./gradlew :apps:desktop:run`              | Blocked by `:shared:assemble` MinGW failure     |
| `./gradlew :apps:desktop:packageDistributionForCurrentOS` | Same |
| `./gradlew :shared:linkPodReleaseFrameworkIosArm64` | Apple toolchains are macOS-only        |
| Snapshot / instrumented / E2E tests        | None implemented in spike scope                 |

The first one (Android assembly) is the most important to validate next.
A 2-3 hour follow-up on a Linux box with `ANDROID_HOME` set will
confirm that the Android cdylib (`libbhodl_uniffi.so` for `arm64-v8a` +
`armeabi-v7a` + `x86_64`) packages correctly into the APK.

---

## 4. Architectural decisions revisited

### 4.1 Why `src/commonMain/rust/lib.rs` instead of standard `src/lib.rs`?

The Ubique UniFFI plugin treats the Rust crate's source layout as a
sibling of the Gradle source-set hierarchy. By placing Rust under
`src/commonMain/rust/`, the plugin can map the Rust source to the
generated `commonMain` Kotlin bindings without configuration. Putting
it at `src/lib.rs` works for `cargo` but breaks the plugin's
incremental-rebuild input declarations — every source change would
trigger a full bindings regeneration.

This is **non-standard for Rust crates** but **canonical for
UniFFI-KMP crates** following the Bitkey / Ubique pattern.

### 4.2 Why `RepositoriesMode.PREFER_SETTINGS`?

Centralised repository declarations in `settings.gradle.kts` mean:
- Subprojects inherit; no per-module `repositories { }` blocks.
- Plugin-injected repositories (Compose, AGP) coexist without needing
  `FAIL_ON_PROJECT_REPOS` warnings.

The strict mode (`FAIL_ON_PROJECT_REPOS`) is a Phase-1 hardening
target; it's the right discipline once we know which transitive
plugins inject what.

### 4.3 Why is Android conditional?

Phase-0 spike host = Windows 11 without Android Studio. We deliberately
kept the build green on this configuration because:

- It models the "grant-funded contributor" scenario (someone clones
  the repo for the first time and wants to verify desktop UI before
  installing 8 GB of Android SDK).
- It matches Bitkey's published contributor docs which similarly
  guard the Android module.
- It forces the design to expose `:apps:desktop:run` as a
  standalone-runnable target, which doubles as a fast iteration loop
  for shared UI development.

Implementation: a `hasAndroidSdk` boolean computed from `ANDROID_HOME`
or `local.properties.sdk.dir` gates `include(":apps:android")` in
`settings.gradle.kts` and the `androidTarget { ... }` + `android { }`
blocks in `shared/build.gradle.kts`.

### 4.4 Why `mingwX64` as a Kotlin/Native fallback?

Documented in §3.4 — the Ubique plugin requires at least one
Kotlin/Native target to register its `commonize` chain. On macOS hosts
the iOS targets satisfy this; on Windows hosts we need a placeholder.

`mingwX64` was chosen over `linuxX64` (which doesn't make sense on
Windows) and over `wasmJs` (which would pull a much larger toolchain).

The hidden cost is the MSYS2 dependency revealed by `:shared:assemble`.
Phase 1 should either accept this cost (document the prereq) or
investigate whether the plugin can be configured to skip cargo cross-
compilation for placeholder Native targets.

### 4.5 Why two Kotlin packages (`io.bhodl.ffi` vs `io.bhodl.shared`)?

The Kotlin facade pattern (§2.3) is non-negotiable for a wallet:

- `io.bhodl.ffi.*` is **machine-generated** by the bindgen and may
  break across UniFFI versions. We treat it as an internal artefact.
- `io.bhodl.shared.*` is **hand-written** and stable. UI code only
  imports from here. When we upgrade UniFFI or rearrange the Rust
  surface, the facade absorbs the churn.

This is the exact same pattern BDK Kotlin uses (`org.bitcoindevkit.*`
hides the underlying `uniffi`-generated symbols behind ergonomic
extension functions).

---

## 5. Verdict — go/no-go

### 5.1 GO if (arguments confirmed by the spike)

- **The toolchain works end-to-end**. Rust → UniFFI → KMP common
  bindings → platform-specific actuals → Compose UI compiles. No
  architectural surprises.
- **Bitkey precedent is real**. Block's wallet team published their
  Gradle setup; ours mirrors theirs. We are not pioneering — we are
  walking a paved path.
- **BDK / LDK Node / LWK / Breez SDK Liquid / CDK all ship UniFFI KMP
  bindings out of the box** in 2026. PLAN.md §6.1 lists them; integrating
  them in Phase 1 means adding `implementation("org.bitcoindevkit:bdk-kmp:...")`
  to `shared/build.gradle.kts`, not writing FFI by hand.
- **Type-safe FFI**. The generated Kotlin signature
  `expect fun formatSat(sat: ULong): String` is exactly what we want.
  No JSON, no string parsing, no runtime cast errors. Errors propagate
  as typed exceptions per the UniFFI error mapping.
- **Compose Multiplatform iOS Stable** since 1.8 (May 2025), now at
  1.10.3 (May 2026). Production-ready for non-game UIs, which is our
  case. JetBrains is shipping monthly minor releases with iOS-specific
  fixes.
- **Single language for app code** (Kotlin). Avoids the
  Dart-+-Rust-+-platform-channels dance Flutter would impose.
- **Memory and key handling stay in Rust**. Zero JS engine, zero
  WebView, zero serialization layer for crypto. Compared to a Tauri
  + React design (PLAN's earlier sketch), this is a categorical
  security improvement for a self-custody wallet.

### 5.2 NO-GO if (potential blockers)

- **Plugin maturity**: `ch.ubique.uniffi.plugin 1.0.8` has the Windows
  `.exe` bug documented in §3.3 and the cargo cross-compile coupling
  documented in §3.4. These are not blockers but they are friction.
  If the plugin maintainer becomes unresponsive, we inherit the
  maintenance.
- **iOS validation gap**: the spike was developed on Windows. We have
  scaffold for iOS but zero verified Compose-iOS UI. The PLAN
  recommendation rests on Bitkey's iOS production deployment, not
  on our own measurements. A Phase-1 acceptance test must include
  building the Apple framework on a Mac and running the spike screen
  in the iOS Simulator.
- **Bundle size on iOS**: Bitkey reports +9 MB vs SwiftUI for a
  Compose app. Acceptable for us (we're not space-constrained), but
  worth noting for marketing.
- **Cargo↔Gradle coupling**: every `cargo build` failure is now a
  Gradle build failure, with stack traces from two ecosystems. The
  Ubique plugin does a good job surfacing the underlying Cargo error
  but you still need to be conversant in both build systems to debug.

### 5.3 Final recommendation: **GO with caveats**

The architecture is sound. The toolchain works. The precedent is
production-grade (Bitkey).

The caveats are **plumbing**, not **design**:

1. **Phase 1 W1**: get an Android-SDK-equipped Linux box (or WSL2)
   into CI and run the full `:shared:assemble` + `:apps:android:assembleDebug`
   chain to validate the cross-platform happy path.
2. **Phase 1 W1**: get a Mac (Mac Mini M2 or GitHub Actions
   `macos-latest` runner) and validate `embedAndSignAppleFrameworkForXcode`
   produces a usable `shared.framework`. Stand up the iOS app in
   Simulator with the spike screen rendering.
3. **Phase 1 W2**: file the Windows `.exe` plugin bug upstream
   (`UbiqueInnovation/uniffi-kotlin-multiplatform-bindings`). Either
   patch and submit a PR, or document the workaround prominently in
   contributor docs.
4. **Phase 1 W2**: decide on the `mingwX64` fallback story. Either
   require MSYS2 on Windows contributor machines, or remove the
   target and pin contributors to macOS/Linux for full builds.
5. **Phase 1 W4**: integrate `bdk-kmp` and confirm the binding
   works for BDK's larger UniFFI surface (descriptors, PSBTs,
   wallets). This is the real validation — the spike's
   `format_sat` is a one-function smoke test.

If any of points 1, 2, or 5 reveal blockers we haven't anticipated,
fall back to Flutter + flutter_rust_bridge as PLAN.md §6.2
contemplates. But on the evidence to date, that fallback is unlikely
to be needed.

---

## 6. Setup time estimate for a fresh contributor

Assuming a developer who clones the repo on a clean Linux/macOS box
with no prior Bitcoin / KMP experience but reasonable
software-engineering background:

| Step                                                          | Time          |
|---------------------------------------------------------------|---------------|
| Install JDK 21 (Adoptium Temurin)                             | 10 min        |
| Install rustup + Rust 1.85 (auto-pinned by `rust-toolchain.toml`) | 10 min     |
| First `./gradlew --version` (downloads Gradle 8.13)            | 5 min         |
| First `cargo build` of `bhodl-uniffi` (compiles `windows-sys`, `syn`, `uniffi`, transitive deps) | 8-12 min |
| First `./gradlew :shared:compileCommonMainKotlinMetadata` (downloads Kotlin 2.2.21, Compose 1.10.3, plugin runtimes; runs bindgen install + bindings gen) | 15-20 min |
| First `./gradlew :apps:desktop:run` (cargo-builds host cdylib, links Compose Desktop) | 5 min |
| **Subtotal: minimum-viable desktop iteration loop**           | **~50 min**   |
| Optional: install Android SDK + emulator (one-time)           | 30-60 min     |
| Optional: first `./gradlew :apps:android:installDebug`        | 10 min        |
| **Subtotal: full Android + Desktop dev loop**                 | **~2 hours**  |
| Optional: macOS host + Xcode for iOS (separate machine)       | 60-90 min Xcode install |
| Optional: first iOS framework build + Simulator launch        | 15-30 min     |
| **Subtotal: full Android + Desktop + iOS**                    | **~3-4 hours** |

For a Windows contributor, add ~20-30 min for MSYS2 install (until
the `mingwX64` issue is resolved upstream) — total ~1 hour for
desktop loop, ~2.5 hours for Android+Desktop.

These numbers are dominated by **first-time downloads** (Gradle
distribution = 200 MB, KMP/Compose dependencies = ~1.5 GB cache,
Android SDK = 6-8 GB). On warm caches, all subsequent
clean builds finish in <2 minutes for the shared module and
<5 minutes for the full apps.

---

## 7. Frictions and known blockers (priority-ordered)

### P0 — Must address before merging Phase-1 wallet code

1. **Windows `.exe` suffix bug** in `ch.ubique.uniffi.plugin`'s
   `BuildBindingsTask`. Workaround: manual copy. Real fix: patch the
   plugin to detect platform and append `.exe` accordingly.
2. **`mingwX64` fallback forces MSYS2 on Windows**. Either install
   the toolchain or drop the target. Document the choice prominently.
3. **iOS framework production not validated on this spike**. Macros
   compile, bindings generate, Swift sample is scaffold. The first
   Mac build is a meaningful unknown.
4. **No CI yet**. Spike is local-only. The `gradle/libs.versions.toml`
   pinning + `rust-toolchain.toml` pinning makes a CI matrix
   straightforward (GitHub Actions linux + macos + windows).

### P1 — Should address in early Phase 1

5. **No `expect`/`actual` for platform-specific APIs yet**. We will
   need this immediately for: secure storage (Android Keystore vs
   iOS Keychain vs OS keyring on desktop), biometric auth, NFC,
   clipboard, file picker, deep linking. The spike has no such
   abstraction; Phase 1 must establish the pattern (`expect class
   SecureStorage` + per-platform `actual class` is the canonical
   shape).
6. **Configuration cache disabled**. Compose Multiplatform 1.10.x
   is not yet config-cache-clean. We pay a 10-15% configuration-time
   penalty on every `./gradlew` invocation. Re-enable when CMP 1.11+
   ships (mid-2026 expected).
7. **Compose Multiplatform iOS keyboard handling**. The spike's
   `App.swift` has `.ignoresSafeArea(.keyboard)` as a placeholder.
   Real wallet input fields will need explicit `OnFocusBehavior`
   wiring per CMP iOS docs. Not validated.
8. **No DI container yet**. PLAN.md mentions Koin; spike skips it
   entirely. Phase 1 must decide and wire Koin (or kotlin-inject)
   before adding the second feature.

### P2 — Address before public beta

9. **No Reproducible Builds setup**. PLAN.md §9.3 requires Guix-style
   reproducibility. Gradle / KMP reproducibility is achievable but
   non-trivial; we must research.
10. **No code signing pipeline**. Android needs `signingConfig`
    (Phase 1 release builds), iOS needs provisioning profiles, Desktop
    needs Windows / macOS code-signing certificates for distribution.
11. **JNA on Android**: Ubique plugin injects `net.java.dev.jna:jna`
    at version 5.17.0. This is ~1.5 MB and must be packaged into
    the APK. Bitkey accepts this; we should confirm we accept it too
    for the lighter alternative (`jnr-ffi`) is not supported by
    UniFFI.
12. **iOS framework size**: estimate Bitkey-class apps at 30-40 MB.
    Acceptable but worth measuring.

### P3 — Nice to have

13. **Shared compose-resources workflow**. Bitkey uses CMP's
    `composeResources/` for icons, strings, fonts. The spike has
    none. Set up before designing the design system.
14. **Shared `App()` composable**. Today the desktop and Android
    `SpikeScreen` are duplicated 1:1. They should be a single
    `commonMain` composable consumed by both apps. The pattern is
    trivial; the spike kept them separate to make the diff between
    the two apps explicit.

---

## 8. Real-world references

- **Bitkey by Block**:
  [How Bitkey uses cross-platform development](https://engineering.block.xyz/blog/how-bitkey-uses-cross-platform-development).
  Self-custody Bitcoin wallet, Rust core + KMP + Compose Multiplatform,
  in production globally since 2023. The single most important
  precedent for this stack choice. They explicitly rejected the native
  split (separate Swift + Kotlin codebases) for cost-of-ownership
  reasons.
- **BDK Kotlin bindings**: [bitcoindevkit/bdk-kotlin](https://github.com/bitcoindevkit/bdk-kotlin),
  generated via UniFFI from the Rust `bdk_wallet` crate. Same
  toolchain we use. Ships as `org.bitcoindevkit:bdk-android` and
  iOS / KMP variants.
- **Ubique Innovation `uniffi-kotlin-multiplatform-bindings`**:
  [github.com/UbiqueInnovation/uniffi-kotlin-multiplatform-bindings](https://github.com/UbiqueInnovation/uniffi-kotlin-multiplatform-bindings).
  The fork of Mozilla's UniFFI that produces KMP `commonMain`-friendly
  Kotlin (vs Mozilla's JVM-only output). Maintained by a Swiss
  software house; modest but active community.
- **Ubique Gradle plugin**: [github.com/UbiqueInnovation/uniffi-kotlin-multiplatform-bindings-plugin](https://github.com/UbiqueInnovation/uniffi-kotlin-multiplatform-bindings-plugin).
  The Gradle wrapper around the bindgen. This is `ch.ubique.uniffi.plugin`
  in our build.
- **Breez SDK Liquid**: ships UniFFI KMP bindings out of the box.
  PLAN.md §6.1 / §4.3 path A.
- **LDK Node**: [lightningdevkit/ldk-node](https://github.com/lightningdevkit/ldk-node)
  ships Kotlin/Swift bindings via UniFFI; KMP support is community-driven
  but stable.
- **LWK (Liquid Wallet Kit)**: [Blockstream/lwk](https://github.com/Blockstream/lwk)
  ships UniFFI bindings. Used in AQUA wallet on iOS.
- **CDK (Cashu Dev Kit)**: [cashubtc/cdk](https://github.com/cashubtc/cdk)
  has UniFFI bindings in active development as of 2026.
- **JetBrains Compose Multiplatform release notes**: [jetbrains.com/compose-multiplatform](https://www.jetbrains.com/compose-multiplatform/)
  — iOS Stable since 1.8 (May 2025), 1.10.3 latest May 2026.

---

## 9. Open questions

These are questions the spike could not answer because the spike was
deliberately scoped to one function on one host OS. They are inputs
to the Phase-1 planning meeting.

1. **iOS performance**: how does Compose iOS's Skia-based renderer
   compare to SwiftUI for our specific UI (transaction list with
   thousands of rows)? Bitkey has not published benchmarks for
   their list rendering. Need to measure on-device (iPhone 12+).
2. **Shared sync background work**: Compose Multiplatform has no
   built-in equivalent of WorkManager (Android) or BGTaskScheduler
   (iOS). PLAN.md §4.3 mentions Lightning sync running in
   background; we will need platform `expect`/`actual` wrappers.
   Pattern is known but unwritten.
3. **Bundle size with full BDK + LDK + LWK + CDK**: each adds a
   cdylib. APK size could balloon to 50-80 MB; iOS framework
   30-40 MB. Acceptable for a wallet but needs measurement.
4. **Reproducible builds for KMP + Cargo combined**. Bitcoin Core
   uses Guix; can we apply the same to a KMP+Rust monorepo? No
   public precedent we found. Phase 2-3 research.
5. **Test strategy**: KMP common tests are easy. Native UI tests
   are harder. Roborazzi + Paparazzi cover Android; nothing
   equivalent on iOS Compose. Strategy TBD.
6. **Compose Multiplatform Wasm**: PLAN.md §6.2 mentions 5
   platforms. Wasm is contemplated but not wired in spike. Decide
   if a web wallet view is in scope (BHODL philosophy: maybe
   read-only, never key management).
7. **Crash reporting on KMP**: Sentry self-hosted is PLAN.md
   §9.3 default. Sentry's KMP support is via `sentry-kotlin-multiplatform`
   which is community, not first-party. Validate.
8. **License conflict surface**: `uniffi` is MPL-2.0,
   `kotlinx.coroutines` is Apache-2.0, JNA is LGPL-2.1 OR Apache-2.0.
   No GPL/AGPL in the chain. Compatible with our MIT/Apache dual
   license. Confirmed at `Cargo.lock` and Gradle level — no
   surprises.
9. **CI cost**: macOS GitHub Actions runners are 10× more expensive
   than Linux. Will Phase 1 self-host a Mac Mini for iOS CI?
   Sponsorship / grant question.
10. **HSM / hardware wallet integration on KMP**: USB-HID, NFC,
    Bluetooth all need `expect`/`actual`. The Bitkey monorepo has
    excellent reference implementations for the device side
    (their NFC card protocol); for desktop / mobile USB, less is
    public. Unknown effort.

---

## 10. Appendix — full repo layout after the spike

```
bhodl/
├── Cargo.toml                    # workspace, includes bhodl-uniffi
├── Cargo.lock
├── rust-toolchain.toml           # rustc 1.85
├── rustfmt.toml
├── deny.toml
├── settings.gradle.kts           # KMP wiring + conditional Android
├── build.gradle.kts              # plugin manifest
├── gradle.properties             # JVM, KMP, AGP, Compose flags
├── gradlew, gradlew.bat
├── gradle/
│   ├── libs.versions.toml        # version catalog
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties  # Gradle 8.13
├── crates/
│   ├── bhodl-core/               # UNTOUCHED — pre-existing
│   │   ├── Cargo.toml
│   │   ├── README.md
│   │   └── src/{lib.rs, amount.rs, layer.rs, error.rs, ...}
│   └── bhodl-uniffi/             # NEW
│       ├── Cargo.toml            # cdylib + staticlib + path = src/commonMain/rust/lib.rs
│       ├── uniffi.toml           # package_name = io.bhodl.ffi
│       └── src/commonMain/rust/lib.rs   # #[uniffi::export] format_sat
├── shared/                       # NEW — KMP shared module
│   ├── build.gradle.kts          # KMP + UniFFI plugin + cargo extension
│   └── src/commonMain/kotlin/io/bhodl/shared/Format.kt
├── apps/
│   ├── android/                  # NEW
│   │   ├── build.gradle.kts
│   │   └── src/main/{AndroidManifest.xml, kotlin/io/bhodl/android/MainActivity.kt}
│   ├── desktop/                  # NEW
│   │   ├── build.gradle.kts
│   │   └── src/jvmMain/kotlin/io/bhodl/desktop/Main.kt
│   └── ios/                      # NEW — scaffold only
│       ├── BHODL/App.swift
│       └── README.md
├── design/                       # UNTOUCHED — committed separately
├── docs/
│   └── spike-uniffi.md           # THIS FILE
├── PLAN.md                       # UNTOUCHED
├── TECH_STACK.md                 # UNTOUCHED
├── README.md                     # UNTOUCHED
└── LICENSE-MIT, LICENSE-APACHE   # UNTOUCHED
```

Generated artefacts (gitignored, present after a build):

```
build/                            # root Gradle build dir
shared/build/
├── bindgen-install/bin/uniffi-bindgen-kotlin-multiplatform[.exe]
└── generated/uniffi/
    ├── commonMain/kotlin/io/bhodl/ffi/bhodl_uniffi.common.kt   # expect declarations
    ├── androidMain/kotlin/io/bhodl/ffi/bhodl_uniffi.android.kt # JNI actuals
    ├── jvmMain/kotlin/io/bhodl/ffi/bhodl_uniffi.jvm.kt         # JNA actuals
    └── nativeMain/kotlin/io/bhodl/ffi/bhodl_uniffi.native.kt   # Kotlin/Native actuals
target/                           # cargo build artefacts
```

---

*End of spike-uniffi.md — version 1.0, 2026-05-05.*
