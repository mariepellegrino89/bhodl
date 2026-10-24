//! Live Tor tests (feature `tor-tests`, off by default).
//!
//! These exercise the real Tor routing end to end against a **running
//! Tor daemon** and the public internet — the automated replacement for
//! the old "manual test with a real Tor daemon" item in the test-book.
//!
//! ```text
//! BHODL_TOR_SOCKS=socks5h://127.0.0.1:9050   # default
//! BHODL_TOR_REQUIRED=1                        # fail (not skip) without Tor
//! cargo test -p bhodl-wallet-onchain --features tor-tests tor_live
//! ```
//!
//! Without a reachable SOCKS port every test that needs Tor prints a
//! `SKIP` line and passes, unless `BHODL_TOR_REQUIRED` is set (CI), in
//! which case it fails. The fail-closed tests need no Tor at all.
//!
//! Paths covered:
//! - Esplora (`esplora-client` / minreq) through the loopback
//!   HTTP-CONNECT → SOCKS5 bridge in [`crate::tor`]: tip height, a full
//!   BIP86 watch-wallet scan, a `.onion` Esplora.
//! - Rate fetches (ureq SOCKS agent, [`crate::btc_rate_history`]).
//! - Both paths are checked against `check.torproject.org/api/ip`
//!   (`"IsTor": true`), i.e. traffic really exits through Tor.

use std::io::Read as _;
use std::net::{TcpListener, TcpStream, ToSocketAddrs as _};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, OnceLock};
use std::time::Duration;

use bhodl_core::Network as BhodlNetwork;

use crate::btc_rate_history::{BtcRateHistoryRepository as _, BtcRateRepo, rate_agent};
use crate::common::try_build_esplora_client;
use crate::tor::parse_socks_proxy;
use crate::{WatchAccount, WatchBip};

const SIGNET_ESPLORA: &str = "https://mempool.space/signet/api";
/// Blockstream's Esplora onion service (signet).
const BLOCKSTREAM_SIGNET_ONION: &str =
    "http://explorerzydxu5ecjrkwceayqybizmpjjznk5izmitf2modhcusuqlid.onion/signet/api";
/// mempool.space's onion (the `Onion-Location` of mempool.space).
const MEMPOOL_SIGNET_ONION: &str =
    "http://mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion/signet/api";
const TOR_CHECK_API: &str = "https://check.torproject.org/api";
/// Signet was past this height long before these tests were written.
const MIN_SIGNET_HEIGHT: u32 = 200_000;

/// Signet watch wallet over the BIP-86 "abandon … about" test account,
/// in a temp dir (kept alive alongside the account).
fn bip86_signet_watch() -> (tempfile::TempDir, WatchAccount) {
    let dir = tempfile::TempDir::new().unwrap();
    let account = WatchAccount::create_from_xpub(
        BhodlNetwork::Signet,
        &dir.path().join("tor_live.sqlite"),
        &[0x42; crate::ENCRYPTION_KEY_LEN],
        &crate::test_support::bip86_xpub(BhodlNetwork::Signet),
        WatchBip::Taproot,
    )
    .unwrap();
    (dir, account)
}

/// The SOCKS proxy to use, or `None` (after printing why) when Tor is
/// not reachable and `BHODL_TOR_REQUIRED` is unset.
///
/// # Panics
///
/// When Tor is unreachable and `BHODL_TOR_REQUIRED` is set.
fn tor_proxy() -> Option<String> {
    static PROBE: OnceLock<Result<String, String>> = OnceLock::new();
    let probe = PROBE.get_or_init(|| {
        let url = std::env::var("BHODL_TOR_SOCKS")
            .ok()
            .filter(|v| !v.trim().is_empty())
            .unwrap_or_else(|| "socks5h://127.0.0.1:9050".to_string());
        probe_socks(&url)
            .map(|()| url.clone())
            .map_err(|e| format!("Tor SOCKS proxy {url} not reachable ({e}); set BHODL_TOR_SOCKS"))
    });
    match probe {
        Ok(url) => Some(url.clone()),
        Err(why) if std::env::var_os("BHODL_TOR_REQUIRED").is_some() => {
            panic!("BHODL_TOR_REQUIRED is set but {why}")
        }
        Err(why) => {
            eprintln!("SKIP (no Tor): {why}");
            None
        }
    }
}

/// TCP connect + SOCKS5 greeting: is a SOCKS5 server listening there?
fn probe_socks(url: &str) -> Result<(), String> {
    use std::io::Write as _;
    let p = parse_socks_proxy(url).map_err(|e| e.to_string())?;
    let addr = (p.host.as_str(), p.port)
        .to_socket_addrs()
        .map_err(|e| e.to_string())?
        .next()
        .ok_or("proxy host did not resolve")?;
    let mut s =
        TcpStream::connect_timeout(&addr, Duration::from_secs(5)).map_err(|e| e.to_string())?;
    s.set_read_timeout(Some(Duration::from_secs(5)))
        .map_err(|e| e.to_string())?;
    s.write_all(&[0x05, 0x01, 0x00])
        .map_err(|e| e.to_string())?;
    let mut reply = [0u8; 2];
    s.read_exact(&mut reply).map_err(|e| e.to_string())?;
    if reply == [0x05, 0x00] {
        Ok(())
    } else {
        Err(format!("not a no-auth SOCKS5 server (reply {reply:?})"))
    }
}

/// Retry a flaky network operation (a fresh Tor circuit per attempt is
/// up to Tor; we just give it a few chances).
fn with_retries<T, E: std::fmt::Display>(what: &str, mut f: impl FnMut() -> Result<T, E>) -> T {
    let mut last = String::new();
    for attempt in 1..=3 {
        match f() {
            Ok(v) => return v,
            Err(e) => {
                eprintln!("{what}: attempt {attempt} failed: {e}");
                last = e.to_string();
                std::thread::sleep(Duration::from_secs(3));
            }
        }
    }
    panic!("{what} failed after 3 attempts: {last}")
}

/// Parse `check.torproject.org/api/ip` and return `(IsTor, IP)`.
fn parse_tor_check(body: &str) -> (bool, String) {
    let v: serde_json::Value = serde_json::from_str(body)
        .unwrap_or_else(|e| panic!("bad check.torproject body {body:?}: {e}"));
    let is_tor = v
        .get("IsTor")
        .and_then(serde_json::Value::as_bool)
        .unwrap_or_else(|| panic!("no IsTor in {body:?}"));
    let ip = v
        .get("IP")
        .and_then(serde_json::Value::as_str)
        .unwrap_or_default()
        .to_string();
    (is_tor, ip)
}

/// A free loopback port with nothing listening on it.
fn dead_port() -> u16 {
    let l = TcpListener::bind("127.0.0.1:0").unwrap();
    l.local_addr().unwrap().port()
}

/// A loopback listener that records whether anyone ever connected —
/// stands in for "the destination", to prove nothing reached it
/// directly while the proxy was down.
fn canary_listener() -> (u16, Arc<AtomicBool>) {
    let l = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = l.local_addr().unwrap().port();
    let hit = Arc::new(AtomicBool::new(false));
    let flag = Arc::clone(&hit);
    std::thread::spawn(move || {
        if l.accept().is_ok() {
            flag.store(true, Ordering::SeqCst);
        }
    });
    (port, hit)
}

// ---------------------------------------------------------------------------
// (a) Esplora through the CONNECT → SOCKS5 bridge
// ---------------------------------------------------------------------------

#[test]
fn tor_live_esplora_tip_height_through_bridge() {
    let Some(proxy) = tor_proxy() else { return };
    let height = with_retries("signet tip height via Tor", || {
        try_build_esplora_client(SIGNET_ESPLORA, Some(&proxy))
            .map_err(|e| e.to_string())?
            .get_height()
            .map_err(|e| e.to_string())
    });
    assert!(
        height > MIN_SIGNET_HEIGHT,
        "implausible signet height {height}"
    );
}

#[test]
fn tor_live_esplora_full_wallet_sync_through_bridge() {
    let Some(proxy) = tor_proxy() else { return };
    let (_dir, mut account) = bip86_signet_watch();
    with_retries("signet full scan via Tor", || {
        account.sync_with_esplora(SIGNET_ESPLORA, Some(&proxy))
    });
    let txs = account.list_transactions();
    eprintln!(
        "signet scan via Tor: {} txs, balance {} sat",
        txs.len(),
        account.balance().to_sat()
    );
    // The BIP86 "abandon … about" test wallet has long had public signet
    // history, so a scan that found nothing means it did not really run.
    assert!(!txs.is_empty(), "full scan through Tor found no history");
}

// ---------------------------------------------------------------------------
// (b) Traffic really exits through Tor
// ---------------------------------------------------------------------------

#[test]
fn tor_live_bridge_path_exits_through_tor() {
    let Some(proxy) = tor_proxy() else { return };
    // Same builder (and therefore the same bridge + minreq stack) the
    // wallet uses for Esplora, pointed at the Tor Project's check API.
    let body = with_retries("check.torproject via bridge", || {
        let client =
            try_build_esplora_client(TOR_CHECK_API, Some(&proxy)).map_err(|e| e.to_string())?;
        let resp = client
            .get_request("/ip")
            .map_err(|e| e.to_string())?
            .send()
            .map_err(|e| e.to_string())?;
        if resp.status_code != 200 {
            return Err(format!("HTTP {}", resp.status_code));
        }
        resp.as_str().map(str::to_string).map_err(|e| e.to_string())
    });
    let (is_tor, ip) = parse_tor_check(&body);
    eprintln!("bridge path exit IP: {ip}");
    assert!(
        is_tor,
        "Esplora bridge path did NOT exit through Tor: {body}"
    );
}

#[test]
fn tor_live_rate_path_exits_through_tor() {
    let Some(proxy) = tor_proxy() else { return };
    // The process-wide agent BtcRateRepo::with_proxy installs for this
    // proxy setting — the exact rate-fetch transport.
    let agent = rate_agent(Some(&proxy)).unwrap();
    let body = with_retries("check.torproject via rate agent", || {
        agent
            .get(&format!("{TOR_CHECK_API}/ip"))
            .call()
            .map_err(|e| e.to_string())?
            .into_string()
            .map_err(|e| e.to_string())
    });
    let (is_tor, ip) = parse_tor_check(&body);
    eprintln!("rate path exit IP: {ip}");
    assert!(is_tor, "rate path did NOT exit through Tor: {body}");
}

#[test]
fn tor_live_historical_rate_through_tor() {
    let Some(proxy) = tor_proxy() else { return };
    let repo = BtcRateRepo::in_memory()
        .unwrap()
        .with_proxy(Some(&proxy))
        .unwrap();
    // 2024-04-19 (halving day), ~63.8k USD.
    let rate = with_retries("BTC/USD 2024-04-19 via Tor", || {
        repo.rate_at(1_713_484_800, "USD")
            .map_err(|e| e.to_string())?
            .ok_or_else(|| "no rate".to_string())
    });
    assert!(
        (rate - 63_800.0).abs() <= 1_500.0,
        "BTC/USD on 2024-04-19 = {rate}"
    );
}

#[test]
fn tor_live_recent_rate_uses_coingecko_through_tor() {
    let Some(proxy) = tor_proxy() else { return };
    let repo = BtcRateRepo::in_memory()
        .unwrap()
        .with_proxy(Some(&proxy))
        .unwrap();
    // 30 days back: inside CoinGecko's keyless 365-day window, so the
    // direct (non-triangulated) history endpoint answers.
    let day = crate::btc_rate_history::now_unix() - 30 * 86_400;
    let rate = with_retries("BTC/EUR 30d back via Tor (CoinGecko)", || {
        repo.rate_at(day, "EUR")
            .map_err(|e| e.to_string())?
            .ok_or_else(|| "no rate".to_string())
    });
    assert!(
        (1_000.0..1_000_000.0).contains(&rate),
        "implausible BTC/EUR {rate}"
    );
}

// ---------------------------------------------------------------------------
// (c) Fail closed: dead proxy → error, and no direct connection
// ---------------------------------------------------------------------------

#[test]
fn tor_live_esplora_fails_closed_with_dead_proxy() {
    let (port, hit) = canary_listener();
    let proxy = format!("socks5h://127.0.0.1:{}", dead_port());
    let esplora = format!("http://127.0.0.1:{port}/api");
    let client = try_build_esplora_client(&esplora, Some(&proxy)).unwrap();
    assert!(
        client.get_height().is_err(),
        "must fail with the proxy down"
    );
    std::thread::sleep(Duration::from_millis(300));
    assert!(
        !hit.load(Ordering::SeqCst),
        "Esplora destination was contacted directly (clearnet fallback)"
    );
}

#[test]
fn tor_live_rate_agent_fails_closed_with_dead_proxy() {
    let (port, hit) = canary_listener();
    let agent = rate_agent(Some(&format!("socks5h://127.0.0.1:{}", dead_port()))).unwrap();
    assert!(
        agent
            .get(&format!("https://127.0.0.1:{port}/"))
            .call()
            .is_err(),
        "must fail with the proxy down"
    );
    std::thread::sleep(Duration::from_millis(300));
    assert!(
        !hit.load(Ordering::SeqCst),
        "rate destination was contacted directly (clearnet fallback)"
    );
}

#[test]
fn tor_live_wallet_sync_fails_closed_with_dead_proxy() {
    let (_dir, mut account) = bip86_signet_watch();
    let proxy = format!("socks5h://127.0.0.1:{}", dead_port());
    assert!(
        account
            .sync_with_esplora(SIGNET_ESPLORA, Some(&proxy))
            .is_err()
    );
}

// ---------------------------------------------------------------------------
// (d) .onion Esplora
// ---------------------------------------------------------------------------

fn onion_tip_height(esplora: &str) {
    let Some(proxy) = tor_proxy() else { return };
    let height = with_retries("onion tip height", || {
        try_build_esplora_client(esplora, Some(&proxy))
            .map_err(|e| e.to_string())?
            .get_height()
            .map_err(|e| e.to_string())
    });
    assert!(
        height > MIN_SIGNET_HEIGHT,
        "implausible signet height {height}"
    );
}

#[test]
fn tor_live_onion_esplora_blockstream() {
    onion_tip_height(BLOCKSTREAM_SIGNET_ONION);
}

#[test]
#[ignore = "mempool.space's onion service accepts the circuit but sends empty replies (seen 2026-10-10); run with --ignored"]
fn tor_live_onion_esplora_mempool() {
    onion_tip_height(MEMPOOL_SIGNET_ONION);
}
