#!/usr/bin/env bash
# Fetch the bitcoind + electrs (esplora) binaries used by the Rust regtest
# harness (crates/bhodl-wallet-onchain/tests/regtest.rs) and print the
# BITCOIND_EXE / ELECTRS_EXE exports that bdk_testenv reads.
#
#   eval "$(scripts/regtest-binaries.sh)"
#   cargo test -p bhodl-wallet-onchain --test regtest -- --ignored --test-threads=1
#
# Why a script: bdk_testenv's `download` feature unpacked these archives
# in a build script with `zip` 0.6 (RUSTSEC-2026-0367/0368/0369), so it is
# disabled in Cargo.toml. The artifacts and sha256 values below are the
# exact ones pinned by bitcoind 0.36.1 (sha256/bitcoin-core-25.0-SHA256SUMS)
# and electrsd 0.28.0 (sha256) — the versions that feature used to fetch.
# Every download is verified before it is unpacked; a mismatch aborts.
#
# Linux x86_64 and macOS (x86_64 / arm64) only: electrs has no Windows
# build. On Windows run it inside WSL2 or the Docker command documented in
# tests/regtest.rs.
#
# Environment:
#   REGTEST_BIN_DIR  where to cache the binaries (default target/regtest-bin)
set -euo pipefail

cd "$(dirname "$0")/.."

bin_dir="${REGTEST_BIN_DIR:-target/regtest-bin}"
mkdir -p "$bin_dir"
bin_dir="$(cd "$bin_dir" && pwd)"

core_url="https://bitcoincore.org/bin/bitcoin-core-25.0"
electrs_url="https://github.com/RCasatta/electrsd/releases/download/electrs_releases"
electrs_rev="esplora_a33e97e1a1fc63fa9c20a116bb92579bbf43b254"

case "$(uname -s)-$(uname -m)" in
  Linux-x86_64)
    core_file="bitcoin-25.0-x86_64-linux-gnu.tar.gz"
    core_sha="33930d432593e49d58a9bff4c30078823e9af5d98594d2935862788ce8a20aec"
    electrs_file="electrs_linux_${electrs_rev}.zip"
    electrs_sha="865e26a96e8df77df01d96f2f569dcf9622fc87a8d99a9b8fe30861a4db9ddf1"
    ;;
  Darwin-x86_64 | Darwin-arm64)
    if [[ "$(uname -m)" == arm64 ]]; then
      core_file="bitcoin-25.0-arm64-apple-darwin.tar.gz"
      core_sha="3b35075d6c1209743611c705a13575be2668bc069bc6301ce78a2e1e53ebe7cc"
    else
      core_file="bitcoin-25.0-x86_64-apple-darwin.tar.gz"
      core_sha="5708fc639cdfc27347cccfd50db9b73b53647b36fb5f3a4a93537cbe8828c27f"
    fi
    # Single x86_64 build; runs under Rosetta 2 on Apple Silicon.
    electrs_file="electrs_macos_${electrs_rev}.zip"
    electrs_sha="2d5ff149e8a2482d3658e9b386830dfc40c8fbd7c175ca7cbac58240a9505bcd"
    ;;
  *)
    echo "unsupported host $(uname -s)-$(uname -m): electrs ships Linux x86_64 / macOS builds only" >&2
    exit 1
    ;;
esac

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  else
    shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

# fetch <url> <file> <sha256>: download into $bin_dir and verify.
fetch() {
  local url="$1" file="$2" want="$3" got
  if [[ ! -f "$bin_dir/$file" ]] || [[ "$(sha256_of "$bin_dir/$file")" != "$want" ]]; then
    echo "downloading $file" >&2
    curl --fail --silent --show-error --location --proto '=https' --tlsv1.2 \
      --output "$bin_dir/$file.part" "$url/$file"
    mv "$bin_dir/$file.part" "$bin_dir/$file"
  fi
  got="$(sha256_of "$bin_dir/$file")"
  if [[ "$got" != "$want" ]]; then
    rm -f "$bin_dir/$file"
    echo "sha256 mismatch for $file: expected $want, got $got" >&2
    exit 1
  fi
}

bitcoind="$bin_dir/bitcoin-25.0/bin/bitcoind"
if [[ ! -x "$bitcoind" ]]; then
  fetch "$core_url" "$core_file" "$core_sha"
  tar -xzf "$bin_dir/$core_file" -C "$bin_dir" bitcoin-25.0/bin/bitcoind
fi

electrs="$bin_dir/$electrs_rev/electrs"
if [[ ! -x "$electrs" ]]; then
  fetch "$electrs_url" "$electrs_file" "$electrs_sha"
  mkdir -p "$bin_dir/$electrs_rev"
  # The archive holds a single file (the electrs executable).
  unzip -p "$bin_dir/$electrs_file" >"$electrs"
  chmod 0755 "$electrs"
fi

printf 'export BITCOIND_EXE=%q\n' "$bitcoind"
printf 'export ELECTRS_EXE=%q\n' "$electrs"
