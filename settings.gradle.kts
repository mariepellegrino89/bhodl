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
