# bhodl-core

Shared primitives used by every other BHODL crate: amounts, layer enum, network,
transaction direction, and the base error type.

This crate is **dependency-light on purpose** — it must compile for every target
(JVM/JNI on Android, Kotlin/Native on iOS, desktop, WASM if ever needed) without
pulling chain-specific or async runtime code.
