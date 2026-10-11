# bhodl-wallet-onchain

On-chain Bitcoin wallet primitives for BHODL.

**Phase 1 scope (this crate today)**: BIP86 taproot single-sig address derivation
from a BIP39 mnemonic. Pure offline computation — no network, no persistence,
no PSBT signing.

**Phase 1 next steps**: persistence (SQLCipher), Esplora sync, balance, PSBT v2
build/sign. Each step lands as its own commit so the surface stays auditable.
