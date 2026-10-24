rootProject.name = "bhodl"

// Force kotlin.mpp.enableCInteropCommonization on the JVM system
// properties channel, BEFORE any plugin (including the Kotlin
// Multiplatform plugin and ch.ubique.uniffi.plugin) is applied.
// settings.gradle.kts runs first in the Gradle lifecycle.
System.setProperty("kotlin.mpp.enableCInteropCommonization", "true")

// Register an empty `commonize` task on :shared BEFORE its
// build.gradle.kts is evaluated. ch.ubique.uniffi.plugin 1.0.8
// registers an afterEvaluate guard that throws if
// `tasks.findByName("commonize") == null`, even when the property
// IS set — on a Linux Gradle 8.13 runner KMP does not create the
// real commonize task with our single-Native-target config.
//
// Registering via gradle.beforeProject{} ensures our afterEvaluate
// callback runs BEFORE the plugin's, since callbacks fire in
// registration order and beforeProject fires before the project's
// build script (and thus before the plugin's apply() registers
// its own afterEvaluate).
gradle.beforeProject {
    if (path == ":shared") {
        afterEvaluate {
            if (tasks.findByName("commonize") == null) {
                tasks.register("commonize") {
                    group = "ubique-uniffi-workaround"
                    description =
                        "Stub so ch.ubique.uniffi.plugin's afterEvaluate guard passes."
                }
            }
        }
    }
}

pluginManagement {
    repositories {
        google {
            // Android plugins live in this filtered group at google().
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Spike: PREFER_SETTINGS keeps repository declarations centralised here
    // instead of letting individual modules redeclare them. We will probably
    // switch to FAIL_ON_PROJECT_REPOS in Phase 1 once the build stabilises.
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        // Supply chain: every dependency (incl. stable Compose
        // Multiplatform) resolves from Google (filtered above) + Maven
        // Central only. The former JetBrains Space compose/dev repo was
        // removed — no stable artifact needs it, and an extra unfiltered
        // repository is dependency-confusion surface. If a Compose dev
        // build is ever needed, re-add it with a `content { }` filter.
        mavenCentral()
    }
}

include(":shared")
include(":apps:desktop")

// The Android host app is included only when an Android SDK is reachable.
// On a Windows / Linux dev box without Android Studio the build still works
// for `:apps:desktop:run`. CI runners and contributors with `ANDROID_HOME`
// (or `sdk.dir` in local.properties) get the full module set.
val hasAndroidSdk: Boolean = run {
    val fromEnv = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    val localProps = rootDir.resolve("local.properties")
    val fromLocalProps: String? = if (localProps.exists()) {
        val props = java.util.Properties()
        java.io.FileInputStream(localProps).use { props.load(it) }
        props.getProperty("sdk.dir")
    } else null
    !fromEnv.isNullOrBlank() || !fromLocalProps.isNullOrBlank()
}

if (hasAndroidSdk) {
    include(":apps:android")
} else {
    logger.lifecycle(
        "[bhodl] Android SDK not detected (set ANDROID_HOME or sdk.dir); " +
            "skipping :apps:android. Desktop and shared targets remain available."
    )
}

// Apply com.android.library to :shared BEFORE its build script runs, so it is
// ahead of ch.ubique.uniffi.plugin in plugin order. The uniffi plugin hooks
// afterEvaluate and resolves Android variants/tasks (e.g. packageDebugResources);
// if AGP is applied after it, those do not exist yet and configuration fails.
// This cannot live in the `plugins {}` block of shared/build.gradle.kts because
// that would make the Android SDK mandatory (AGP fails without one), breaking
// desktop-only checkouts. Same pattern as the `commonize` stub above.
if (hasAndroidSdk) {
    gradle.beforeProject {
        if (path == ":shared") {
            pluginManager.apply("com.android.library")
        }
    }
}

// Supply chain: force patched versions of vulnerable transitive libraries
// that reach ONLY build-tooling and test classpaths — none of them ships in
// the desktop / Android runtime classpath:
//   - io.netty 4.1.93.Final  <- grpc-netty 1.57 <- AGP 8.9.3 (UTP proto);
//     AGP 8.13.2 (latest 8.x) still pulls grpc-netty 1.69 -> netty 4.1.110.
//   - org.bouncycastle *-jdk18on 1.79 <- AGP 8.9.3 sdk-common (also 1.79 in
//     AGP 8.13.2); 1.80.2 <- kotlinBouncyCastleConfiguration (KGP);
//     1.82 <- docker-java-core 3.7.1 (desktop jvmTest, Testcontainers).
//   - org.freemarker 2.3.32  <- intellij coverage-report <- Kover 0.8.3
//     (Kover 0.9.11, the latest, still pulls 2.3.32).
// Bumping AGP / Kover does not remove any of them, so the floors below are
// applied instead, to every project's buildscript (plugin) classpath and to
// every project configuration. Rules only RAISE a version, never lower it;
// drop an entry once upstream requires the floor (or newer) itself.
// Verification hashes for the forced versions live in
// gradle/verification-metadata.xml like any other dependency.
// REVIEW BY 2026-12-31 (or on the next AGP / Kover / docker-java bump).
val securityFloors: List<Triple<String, (String) -> Boolean, String>> = listOf(
    // Whole netty 4.1 family, kept aligned (netty-tcnative versions differently).
    Triple("io.netty", { name: String -> name.startsWith("netty-") && "tcnative" !in name }, "4.1.139.Final"),
    // bcprov / bcpkix / bcutil / bcpg / bctls must stay on one version.
    Triple("org.bouncycastle", { name: String -> name.endsWith("-jdk18on") }, "1.86"),
    Triple("org.freemarker", { name: String -> name == "freemarker" }, "2.3.35"),
)

fun versionIsLower(candidate: String, floor: String): Boolean {
    val a = candidate.split('.', '-').map { it.toIntOrNull() ?: 0 }
    val b = floor.split('.', '-').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x < y
    }
    return false
}

val applySecurityFloors = Action<DependencyResolveDetails> {
    val requested = requested
    val version = requested.version ?: return@Action
    for ((group, nameMatches, floor) in securityFloors) {
        if (requested.group == group && nameMatches(requested.name) &&
            // Same major line only (e.g. never move netty 4.2 / 5.x).
            version.substringBefore('.') == floor.substringBefore('.') &&
            versionIsLower(version, floor)
        ) {
            useVersion(floor)
            because("security floor (settings.gradle.kts): vulnerable $version")
        }
    }
}

gradle.beforeProject {
    buildscript.configurations.configureEach { resolutionStrategy.eachDependency(applySecurityFloors) }
    configurations.configureEach { resolutionStrategy.eachDependency(applySecurityFloors) }
}
