# BHODL Android release shrinking (R8 / ProGuard).
#
# Applied to the release build type only (see apps/android/build.gradle.kts).
# The app is still a UI placeholder over :shared; these rules keep exactly
# what it loads at runtime and nothing more. Revisit when the desktop UI
# lands on Android (Phase 2) and the loaded surface grows.

# UniFFI Kotlin bindings (generated from crates/bhodl-uniffi into
# shared/build/generated/uniffi, package io.bhodl.ffi — the only package
# the generator emits). The native library is loaded through JNA:
# Native.load("bhodl_uniffi", UniffiLib::class.java) creates a dynamic
# proxy of the UniffiLib interface at runtime, so every bindings class,
# interface and member must survive shrinking and obfuscation.
-keep class io.bhodl.ffi.** { *; }

# JNA (net.java.dev.jna:jna, injected with the UniFFI runtime for the
# android target): Native / Structure / Callback work through reflection
# and native registration over their own classes.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * implements com.sun.jna.Library { *; }
# JNA keeps desktop entry points that reference AWT classes absent on
# Android; those code paths are never reached here.
-dontwarn java.awt.**

# kotlinx-coroutines (pulled by :shared commonMain, and
# kotlinx-coroutines-android from its androidMain). Both artifacts ship
# their own consumer ProGuard rules; the keeps below mirror the
# service-loader pieces so a Main-dispatcher factory can never be
# stripped even if the bundled rules change.
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepnames class kotlinx.coroutines.android.AndroidDispatcherFactory {}
-keepnames class kotlinx.coroutines.android.AndroidExceptionPreHandler {}

# kotlinx-serialization: NOT kept on purpose — nothing on the Android
# classpath uses it today (it is a :apps:desktop dependency only). If
# :shared starts serializing on Android, the serialization artifacts ship
# their own consumer rules; add an explicit keep here only for BHODL
# @Serializable model classes.

# Compose (androidx.compose / org.jetbrains.compose) and the other
# injected UniFFI runtime deps (okio, atomicfu, kotlinx-datetime) ship
# their own consumer rules; no extra keeps are required for them.
