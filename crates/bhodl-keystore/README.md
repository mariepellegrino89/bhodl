# bhodl-keystore

Keeps BHODL's 256-bit **device secret** in the operating system's secret store:

| OS      | Backend                                           |
|---------|---------------------------------------------------|
| Windows | DPAPI, `CurrentUser` scope                        |
| macOS   | login Keychain (generic password)                 |
| Linux   | freedesktop Secret Service (`default` collection) |

The database key of every encrypted store is
`HKDF-SHA256(argon2id(PIN) || device_secret)` (see `bhodl-core::key_derivation`),
so a copy of the data directory alone is not enough to brute-force the PIN offline.

Linux builds link the system `libdbus-1` dynamically (`libdbus-1-dev` and
`pkg-config` are needed at build time). `BHODL_KEYSTORE=ephemeral-test` selects an
in-memory store for test suites only: stores created under it cannot be opened
after the process exits.
