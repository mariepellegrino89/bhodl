#!/usr/bin/env bash
# Run BHODL's live Tor tests against a REAL Tor daemon.
#
#   scripts/tor-tests.sh              # wait for Tor, then Rust + Kotlin suites
#   scripts/tor-tests.sh --rust       # Rust only  (cargo, feature tor-tests)
#   scripts/tor-tests.sh --kotlin     # Kotlin only (gradle :apps:desktop:torTest)
#   scripts/tor-tests.sh --wait-only  # just wait until Tor is bootstrapped
#
# Environment:
#   BHODL_TOR_SOCKS  SOCKS5 proxy of the Tor daemon (default socks5h://127.0.0.1:9050)
#   TOR_WAIT_SECS    how long to wait for Tor to bootstrap (default 300)
#
# Getting a Tor daemon:
#   Linux/macOS:  sudo apt-get install -y tor   /   brew install tor && tor &
#   Windows:      WSL2 —  wsl -d Ubuntu-22.04 -- sudo apt-get install -y tor
#                 (the tor service listens on 127.0.0.1:9050 inside WSL and
#                 WSL2 localhost forwarding exposes it to Windows at the same
#                 address; keep the distro running while the tests run, e.g.
#                 `wsl -d Ubuntu-22.04 -- sleep infinity` in another terminal)
#
# Once Tor answers, BHODL_TOR_REQUIRED=1 is exported, so a test that
# cannot reach Tor fails instead of silently skipping.
set -euo pipefail

cd "$(dirname "$0")/.."

export BHODL_TOR_SOCKS="${BHODL_TOR_SOCKS:-socks5h://127.0.0.1:9050}"
wait_secs="${TOR_WAIT_SECS:-300}"
mode="${1:-all}"

proxy="${BHODL_TOR_SOCKS#*://}"
proxy="${proxy%/}"

echo "Waiting up to ${wait_secs}s for Tor at ${proxy} ..."
deadline=$((SECONDS + wait_secs))
until body="$(curl --silent --max-time 30 --socks5-hostname "$proxy" https://check.torproject.org/api/ip)" \
    && grep -q '"IsTor":true' <<<"$body"; do
  if ((SECONDS >= deadline)); then
    echo "Tor not usable at ${proxy} after ${wait_secs}s (last answer: ${body:-none})" >&2
    exit 1
  fi
  sleep 5
done
echo "Tor is up: ${body}"
export BHODL_TOR_REQUIRED=1

case "$mode" in
  --wait-only) exit 0 ;;
  all | --rust | --kotlin) ;;
  *) echo "usage: $0 [--rust | --kotlin | --wait-only]" >&2; exit 2 ;;
esac

if [[ "$mode" == all || "$mode" == --rust ]]; then
  cargo test -p bhodl-wallet-onchain --features tor-tests tor_live -- --nocapture
fi
if [[ "$mode" == all || "$mode" == --kotlin ]]; then
  ./gradlew :apps:desktop:torTest --stacktrace
fi
