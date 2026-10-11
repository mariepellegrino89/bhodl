# BHODL iOS host app — scaffold only

This folder is a placeholder for the iOS host application. It is **not**
buildable from the Phase-0 spike because the project was developed on Windows
and Apple toolchains are macOS-only.

## What lives here

- `BHODL/App.swift` — SwiftUI entry point that, on a Mac, will host the
  Compose Multiplatform `UIViewController` produced by the `:shared` module.
- This README.

## What's missing (Phase 1, on a Mac)

1. An actual `.xcodeproj` (or SwiftPM `Package.swift`) generated either by
   hand in Xcode or by `cocoapods` integration on the `:shared` module.
2. A Compose Multiplatform `iosMain` source set in `:shared` that defines:
   ```kotlin
   fun MainViewController() = ComposeUIViewController { App() }
   ```
   plus a tiny shared `App()` Composable extracted from
   `apps/desktop/Main.kt` and `apps/android/MainActivity.kt`.
3. The Gradle task `embedAndSignAppleFrameworkForXcode` wired up so Xcode
   can pull `shared.framework` automatically.
4. The `bhodl-uniffi` cdylib cross-compiled for `aarch64-apple-ios` and
   `aarch64-apple-ios-sim` and packaged into the framework. The
   `ch.ubique.uniffi.plugin` does this when it detects a macOS host;
   when run on Windows it skips the iOS targets entirely.

## Why the spike still includes this folder

To prove the directory layout matches Bitkey / Block-style KMP repos and to
make the eventual macOS-side work obvious to whoever picks it up.
