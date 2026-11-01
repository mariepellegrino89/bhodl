plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "io.bhodl.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.bhodl.android"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0-alpha.0"
    }

    // Release APK signing (review finding M6). The keystore location and
    // passwords come from gradle properties (-PBHODL_...) or environment
    // variables, never from the repo, and are never printed. Setup and
    // release process: docs/release-signing.md.
    val bhodlKeystorePath = providers.gradleProperty("BHODL_KEYSTORE_PATH")
        .orElse(providers.environmentVariable("BHODL_KEYSTORE_PATH"))
    val bhodlKeystorePassword = providers.gradleProperty("BHODL_KEYSTORE_PASSWORD")
        .orElse(providers.environmentVariable("BHODL_KEYSTORE_PASSWORD"))
    val bhodlKeyAlias = providers.gradleProperty("BHODL_KEY_ALIAS")
        .orElse(providers.environmentVariable("BHODL_KEY_ALIAS"))
    val bhodlKeyPassword = providers.gradleProperty("BHODL_KEY_PASSWORD")
        .orElse(providers.environmentVariable("BHODL_KEY_PASSWORD"))

    signingConfigs {
        // Registered only when the keystore is fully configured: with any
        // value missing there is no "release" signing config and the
        // release build type builds unsigned, exactly as local builds and
        // CI do today.
        val path = bhodlKeystorePath.orNull
        val storePass = bhodlKeystorePassword.orNull
        val alias = bhodlKeyAlias.orNull
        if (path != null && storePass != null && alias != null) {
            create("release") {
                storeFile = file(path)
                storePassword = storePass
                keyAlias = alias
                keyPassword = bhodlKeyPassword.orNull ?: storePass
            }
        }
    }

    buildTypes {
        getByName("release") {
            // Finding M6: ship shrunk, obfuscated release builds. The
            // rules in proguard-rules.pro keep the UniFFI bindings + JNA,
            // coroutines dispatcher factories; everything else ships its
            // own consumer rules.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Signed only when every BHODL_KEYSTORE_* value is set; with
            // no signing config this is a no-op and the APK stays unsigned.
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.ui)
    // `stringResource` / `StringResource` (used with :shared's generated `Res`)
    // live in the compose resources runtime, which :shared only exposes as
    // `implementation`.
    implementation(compose.components.resources)
}
