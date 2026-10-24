# bhodl-wallet-onchain

Watch-only Bitcoin wallet primitives and the registry for BHODL.

BHODL never holds keys. This crate provides:

- `WatchAccount` (`src/watch.rs`): a BDK wallet built from public material only —
  an account xpub (BIP-86 / 84 / 49, SLIP-132 `zpub` / `ypub` accepted), an HD
  descriptor (fingerprint + path + xpub) or a list of addresses. Balance, history,
  Esplora sync (optionally over Tor), receive-address derivation with label-first
  receive, address list, read-only UTXO list, BIP-329 labels with JSONL import /
  export, a local address book and public descriptor export.
- `Registry` (`src/registry.rs`): the wallet list plus every tracker table
  (budgets, goals, investments and lots, manual assets, rules, ...).
- SQLCipher persistence: the caller passes the key derived from the device PIN;
  every database (`registry.sqlite`, `watch-state/<uuid>.sqlite`) is encrypted with it.
- BTC rate history and net-worth reconstruction for the tracker.

There is no signing, PSBT, fee estimation or seed handling. See
[`docs/handbook.md`](../../docs/handbook.md) §4.2 for the full surface.

Tests: `cargo test -p bhodl-wallet-onchain`. The regtest tests in `tests/regtest.rs` are
`#[ignore]`d and need `BITCOIND_EXE` / `ELECTRS_EXE` (see `scripts/regtest-binaries.sh`):
`cargo test -p bhodl-wallet-onchain --test regtest -- --ignored`.
