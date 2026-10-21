import ch.ubique.uniffi.plugin.model.RustHost
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.FileInputStream
import java.util.Properties


// ----------------------------------------------------------------------------
// Android-aware plugin application
// ----------------------------------------------------------------------------
// The Android SDK is optional in Phase-0: the spike was developed on a
// Windows host without Android Studio. We only apply `com.android.library`
// (and the Android target) when ANDROID_HOME / sdk.dir is reachable. This
// keeps `./gradlew :apps:desktop:run` working on a vanilla machine while
// still letting CI / contributors with the SDK build the Android variant.
val hasAndroidSdk: Boolean = run {
    val fromEnv = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    val localProps = rootProject.file("local.properties")
    val fromLocalProps: String? = if (localProps.exists()) {
        val props = Properties()
        FileInputStream(localProps).use { props.load(it) }
        props.getProperty("sdk.dir")
    } else null
    !fromEnv.isNullOrBlank() || !fromLocalProps.isNullOrBlank()
}

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.uniffi)
    // Compose Multiplatform brings the `compose.resources` extension that
    // generates a typed `Res` accessor from `composeResources/values/*.xml`.
    // The compose-compiler plugin (Kotlin 2.0+ pattern) is also required so
    // any future @Composable functions in shared/ compile.
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    // JVM coverage; aggregated at the root.
    alias(libs.plugins.kover)
}

// NOTE: `com.android.library` is applied from settings.gradle.kts
// (gradle.beforeProject) so it precedes the uniffi plugin in plugin order.

// ----------------------------------------------------------------------------
// i18n / Compose resources
// ----------------------------------------------------------------------------
// Generates `io.bhodl.shared.resources.Res` at build time. The default values
// XML lives under `shared/src/commonMain/composeResources/values/strings.xml`
// (English baseline). Localisations land in `values-it/`, `values-es/`, etc.
//
// `generateResClass = Always` because in our setup `commonMain` does NOT
// depend on `compose.components.resources` (Compose is wired only in the
// `nonNativeMain` intermediate source set; see sourceSets block). Without
// `Always` the plugin's auto-detection skips Res generation and the JVM /
// Android / iOS targets cannot resolve `Res.string.*`.
compose.resources {
    publicResClass = true
    packageOfResClass = "io.bhodl.shared.resources"
    generateResClass = org.jetbrains.compose.resources.ResourcesExtension.ResourceClassGeneration.Always
}

// ----------------------------------------------------------------------------
// UniFFI binding generation
// ----------------------------------------------------------------------------
// Tells the ch.ubique.uniffi.plugin to:
//   1. Locate the Rust crate (`cargo` extension below points at it).
//   2. Build it with `cargo build` and read the cdylib's exported metadata
//      (the modern path; faster and more accurate than parsing a UDL).
//   3. Emit Kotlin bindings into commonMain at configure time.
//
// `package_name` is read from `crates/bhodl-uniffi/uniffi.toml` and resolves
// to `io.bhodl.ffi`.
uniffi {
    // Supply chain: the bindgen is built from the workspace crate
    // `crates/uniffi-bindgen-kmp` (see the `installBindgen` override below),
    // NOT installed by the plugin. The plugin's own install task runs
    // `cargo install --git` without `--locked`, so the bindgen's transitive
    // crates.io dependencies would re-resolve to latest on every clean build,
    // outside Cargo.lock / cargo-deny / cargo-audit. The crate pins the same
    // Ubique commit (3a6a106 = tag v1.0.14); bump it deliberately together
    // with the `uniffi-plugin` version in libs.versions.toml.
    bindgenFromPath(
        rootProject.layout.projectDirectory.dir("crates/uniffi-bindgen-kmp"),
        "uniffi-bindgen-kotlin-multiplatform",
        "bhodl-uniffi-bindgen-kmp",
    )
    generateFromLibrary {
        // The Rust crate produces multiple #[uniffi::export] symbols under
        // an inferred namespace; let the plugin compute it from the cdylib.
    }
}

// Replace the plugin's install step with a locked build of the workspace
// bindgen crate: same output layout (`<bindgenPath>/bin/<name>`), but resolved
// strictly from the repo's Cargo.lock.
tasks.named<ch.ubique.uniffi.plugin.tasks.InstallBindgenTask>("installBindgen") {
    setActions(emptyList())
    val workspaceDir = rootProject.layout.projectDirectory.asFile
    val targetDir = rootProject.layout.buildDirectory.dir("bindgen-target").get().asFile
    doLast {
        providers.exec {
            workingDir = workspaceDir
            commandLine(
                "cargo", "install", "--locked", "--force",
                "--path", "crates/uniffi-bindgen-kmp",
                "--root", bindgenPath.get().asFile.path,
                "--target-dir", targetDir.path,
            )
        }.result.get().assertNormalExitValue()
    }
}

// The CargoExtension is a sibling extension created by the same plugin. It is
// where we point to the Rust crate that is NOT colocated with the Gradle
// module. (Default would be project.layout.projectDirectory which here is
// `shared/`, but our Cargo.toml lives at `crates/bhodl-uniffi/`.)
cargo {
    packageDirectory.set(rootProject.layout.projectDirectory.dir("crates/bhodl-uniffi"))
}

// ----------------------------------------------------------------------------
// Kotlin Multiplatform target setup
// ----------------------------------------------------------------------------
kotlin {
    jvmToolchain(21)

    if (hasAndroidSdk) {
        androidTarget {
            @OptIn(ExperimentalKotlinGradlePluginApi::class)
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_21)
            }
            publishLibraryVariants("release")
        }
    }

    // We deliberately do NOT rename to `jvm("desktop")`: the Ubique UniFFI
    // plugin emits actuals into `build/generated/uniffi/jvmMain/`, hardcoded
    // on the canonical name. Rename the target and the JVM `actual` for
    // `formatSat` ends up orphaned (compile fails with "expect ... has no
    // actual declaration").
    jvm {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    // The Ubique uniffi plugin requires at least one Kotlin/Native target so
    // the `commonize` task is created and bindings generation hooks into it.
    // We dispatch by host:
    //   macOS  -> iOS targets (real production targets)
    //   Linux  -> linuxX64    (used by Linux-based CI)
    //   Windows -> mingwX64   (placeholder; cargo build for it requires MSYS2,
    //                          tracked as a Phase-1 W2 follow-up to either
    //                          install MSYS2 in CI or file an upstream fix
    //                          that lets the plugin tolerate zero Native targets)
    //
    // Compose resources do NOT ship mingwX64/linuxX64 artifacts, so Compose
    // dependencies are confined to the `nonNativeMain` intermediate source
    // set declared below — never reach the Native compilations.
    if (RustHost.Platform.MacOS.isCurrent) {
        iosX64()
        iosArm64()
        iosSimulatorArm64()
    } else if (RustHost.Platform.Windows.isCurrent) {
        mingwX64()
    } else if (RustHost.Platform.Linux.isCurrent) {
        linuxX64()
    }

    // Hierarchical source sets. We add an intermediate `nonNativeMain` parent
    // for source sets that *do* have Compose Multiplatform Resources support
    // (JVM, Android, iOS). The placeholder Native targets (mingwX64, linuxX64)
    // depend only on `commonMain` and never see the Compose dependencies.
    //
    //                  commonMain
    //                  /        \
    //         nativeMain (KMP)   nonNativeMain (ours)
    //          /     \              /    |    \
    //   mingwX64 linuxX64    desktop android iosXxx
    applyDefaultHierarchyTemplate()

    sourceSets {
        val nonNativeMain by creating {
            dependsOn(commonMain.get())
        }

        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            // The uniffi plugin auto-injects okio/atomicfu/coroutines/datetime
            // and the runtime; do not duplicate them here.
        }

        // Compose runtime + resources accessor (`stringResource`, `Res.string.*`).
        // Only the targets that have published artifacts: JVM desktop, Android,
        // iOS. mingwX64 and linuxX64 stay Compose-free.
        nonNativeMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        // Wire JVM and Apple targets to nonNativeMain.
        val jvmMain by getting { dependsOn(nonNativeMain) }
        if (RustHost.Platform.MacOS.isCurrent) {
            val iosMain by getting { dependsOn(nonNativeMain) }
        }

        if (hasAndroidSdk) {
            val androidMain by getting {
                dependsOn(nonNativeMain)
                dependencies {
                    implementation(libs.kotlinx.coroutines.android)
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------
// Android library configuration (only when the SDK is available)
// ----------------------------------------------------------------------------
if (hasAndroidSdk) {
    extensions.configure<com.android.build.gradle.LibraryExtension>("android") {
        namespace = "io.bhodl.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        defaultConfig {
            minSdk = libs.versions.android.minSdk.get().toInt()
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }
    }
}

// The `commonize` placeholder workaround for ch.ubique.uniffi.plugin
// is registered from settings.gradle.kts via gradle.beforeProject so
// that it lands BEFORE the plugin's own afterEvaluate guard runs.

// ----------------------------------------------------------------------------
// Fallible object-returning calls: move the generated `!!` past the status check
// ----------------------------------------------------------------------------
// bindgen v1.0.14 emits, for every `Result<Arc<Object>, E>` export,
//
//     uniffiRustCallWithError(Handler) { _status ->
//         UniffiLib.INSTANCE.uniffi_..._fn(args, _status)!!
//     }
//
// On an `Err` the native call returns a null pointer, so the `!!` throws a
// NullPointerException INSIDE the lambda, before the helper reads the call
// status. The typed `WalletException` is lost: a wrong PIN on
// `unlockPersistent` surfaced as an NPE, was classified as "other" and was
// never counted by the PIN lockout. Rewriting to `{ ... }!!` lets the helper
// throw the real exception first; on success the pointer is non-null as
// before. Newer bindgen releases need rustc >= 1.91 (we pin 1.88).
val fallibleCallNpe = Regex(
    """(uniffiRustCallWithError\(\w+\) \{ _status ->\n[^\n]*\n[^\n]*_status\))!!\n\}""",
)
tasks.named("buildBindings") {
    doLast {
        val generated = layout.buildDirectory.dir("generated/uniffi").get().asFile
        generated.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val src = file.readText()
            val patched = src.replace(fallibleCallNpe, "$1\n}!!")
            if (patched != src) file.writeText(patched)
        }
    }
}

// ----------------------------------------------------------------------------
// Windows .exe shim for the bindgen binary
// ----------------------------------------------------------------------------
// The `ch.ubique.uniffi.plugin` `BuildBindingsTask` reads the bindgen
// path WITHOUT the `.exe` suffix. `cargo install` on Windows produces
// `<name>.exe` but no extensionless alias. Without a shim Gradle fails
// with "specifies file ... which doesn't exist" the first time it runs
// `:shared:buildBindings` on a fresh Windows checkout.
//
// The fix: after `installBindgen` finishes, hardlink the `.exe` to its
// extensionless twin. On non-Windows hosts this block is a no-op.
if (RustHost.Platform.Windows.isCurrent) {
    tasks.named("installBindgen") {
        doLast {
            val binDir = layout.buildDirectory.dir("bindgen-install/bin").get().asFile
            val exe = File(binDir, "uniffi-bindgen-kotlin-multiplatform.exe")
            val alias = File(binDir, "uniffi-bindgen-kotlin-multiplatform")
            if (exe.exists()) {
                exe.copyTo(alias, overwrite = true)
            }
        }
    }
}
