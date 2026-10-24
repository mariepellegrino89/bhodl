//! BTC/fiat historical rate repository — Phase 8.1.
//!
//! Provides `BtcRateHistoryRepository`, a trait for querying the
//! BTC/fiat rate at any date since genesis. Backed by a local
//! SQLite/SQLCipher cache (`btc_rate_history` table in the registry
//! DB) with multi-source HTTP fallback:
//!
//! - **≤365d back**: CoinGecko `/coins/bitcoin/history` (direct BTC/X).
//! - **>365d back**: blockchain.info `/charts/market-price` (USD only)
//!   + Frankfurter for non-USD via triangulation.
//! - **Range queries**: CoinGecko `/market_chart/range` for the part of
//!   the window within the last 365 days; older days go day-by-day
//!   through the triangulation path.
//!
//! CoinGecko's keyless public API answers **HTTP 401 (error 10012) for
//! anything older than 365 days** on *both* endpoints (verified
//! 2026-10: a date 365 days back returns 200, 366 days back returns
//! 401). BHODL therefore never asks CoinGecko about older dates, so a
//! lookup does not burn a request and rate-limit budget on a
//! guaranteed failure.
//!
//! Rate-limit: token bucket 25 req/min for CoinGecko (free tier = 30).
//! blockchain.info and Frankfurter: max 10 req/s each (no documented
//! limit, conservative cap).
//!
//! Cache lifetime: infinite for historical dates (they never change).
//! `current()` delegates to the existing live-price layer (TODO: wire
//! to PriceCache in Phase 8.3; currently returns
//! `RateError::NetworkUnavailable`).

// Low-level date/time math and HTTP response parsing require casts and
// pedantic lint suppressions that are sound in context.
#![allow(clippy::cast_possible_wrap)]
#![allow(clippy::cast_possible_truncation)]
#![allow(clippy::cast_sign_loss)]
#![allow(clippy::doc_markdown)]

use std::sync::atomic::{AtomicI64, AtomicU32, Ordering};
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use rusqlite::{Connection, params};
use serde::Deserialize;

use crate::error::OnchainError;

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/// Whitelisted source identifiers saved in `btc_rate_history.source`.
pub const VALID_SOURCES: &[&str] = &[
    "coingecko-history", // /coins/bitcoin/history
    "coingecko-range",   // /coins/bitcoin/market_chart/range
    "blockchain-info",   // /charts/market-price (USD only)
    "ecb-triangulated",  // blockchain-info USD × Frankfurter X/USD
];

/// Supported ISO 4217 currency codes. Mirrors `FiatCurrency` on the Kotlin side.
pub const SUPPORTED_CURRENCIES: &[&str] = &["EUR", "USD", "GBP", "CHF", "JPY"];

/// Bitcoin genesis block — 2009-01-03 00:00:00 UTC.
pub(crate) const BITCOIN_GENESIS_UNIX: i64 = 1_230_940_800;

/// Seconds per day.
const SECS_PER_DAY: i64 = 86_400;

/// How far back CoinGecko's keyless public API serves history. Older
/// dates always fail with HTTP 401, so they are never requested.
const COINGECKO_KEYLESS_WINDOW_DAYS: i64 = 365;

// ---------------------------------------------------------------------------
// Shared HTTP agents (one per proxy setting, built once per process)
// ---------------------------------------------------------------------------

/// Connect timeout. Generous because a Tor circuit build can take a while.
const HTTP_CONNECT_TIMEOUT: Duration = Duration::from_secs(20);
/// Per-read timeout.
const HTTP_READ_TIMEOUT: Duration = Duration::from_secs(30);
/// Hard deadline for one whole request (connect + TLS + body).
const HTTP_OVERALL_TIMEOUT: Duration = Duration::from_secs(60);
/// Same UA the desktop price fetcher sends (Tor Browser's uniform UA),
/// so neither leaks "this is BHODL" to the rate providers.
const GENERIC_USER_AGENT: &str =
    "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0";

/// Process-wide `ureq::Agent` for the given proxy setting.
///
/// One agent per distinct proxy URL (in practice: "none" and the user's
/// Tor proxy), so the connection pool and TLS context are reused across
/// a backfill's many requests instead of rebuilt per call.
///
/// Audit 2026-10 (H9):
/// - `proxy_url` (`socks5h://` / `socks5://`) routes every request through
///   the SOCKS proxy; ureq's SOCKS5 connector sends host names unresolved
///   (remote DNS). If the proxy is unreachable the request FAILS — there
///   is no clearnet fallback, because the agent has no direct route.
/// - `https_only(true)` + `redirects(0)`: a source can never bounce us to
///   plaintext or to an unexpected host.
/// - Generic `User-Agent` ([`GENERIC_USER_AGENT`], Tor Browser's uniform
///   UA) instead of the old fixed `BHODL/8.1`, which fingerprinted the
///   app to every rate provider.
pub(crate) fn rate_agent(proxy_url: Option<&str>) -> Result<ureq::Agent, RateError> {
    static AGENTS: OnceLock<Mutex<std::collections::HashMap<String, ureq::Agent>>> =
        OnceLock::new();
    let key = proxy_url.map(str::trim).unwrap_or_default().to_string();
    let map = AGENTS.get_or_init(|| Mutex::new(std::collections::HashMap::new()));
    let mut map = map
        .lock()
        .map_err(|_| RateError::Storage("agent cache mutex poisoned".into()))?;
    if let Some(agent) = map.get(&key) {
        return Ok(agent.clone());
    }
    let mut builder = ureq::AgentBuilder::new()
        .user_agent(GENERIC_USER_AGENT)
        .https_only(true)
        .redirects(0)
        .timeout_connect(HTTP_CONNECT_TIMEOUT)
        .timeout_read(HTTP_READ_TIMEOUT)
        .timeout(HTTP_OVERALL_TIMEOUT);
    if !key.is_empty() {
        let proxy =
            crate::tor::ureq_socks_proxy(&key).map_err(|e| RateError::AllSourcesFailed {
                last_error: format!("proxy: {e}"),
            })?;
        builder = builder.proxy(proxy);
    }
    let agent = builder.build();
    map.insert(key, agent.clone());
    Ok(agent)
}

/// Process-wide CoinGecko token bucket (audit M3).
///
/// Previously every `BtcRateRepo` (i.e. every FFI call) built its own
/// limiter, so concurrent / back-to-back calls each got a fresh 25-token
/// budget and the free-tier limit was not actually enforced.
fn coingecko_limiter() -> &'static RateLimiter {
    static LIMITER: RateLimiter = RateLimiter::new(25, 60);
    &LIMITER
}

// ---------------------------------------------------------------------------
// Rate validation (audit M5)
// ---------------------------------------------------------------------------

/// Lowest fiat-per-BTC value accepted from any source. The first market
/// prices (mid-2010) were ~0.05 USD; anything below this is a feed bug
/// (blockchain.info reports 0 for pre-market days).
const MIN_BTC_FIAT_RATE: f64 = 1e-3;
/// Highest fiat-per-BTC value accepted. Generous enough for JPY at many
/// multiples of today's price; catches garbage like 1e300 or unit bugs.
const MAX_BTC_FIAT_RATE: f64 = 1e10;
/// Bounds on a USD→X ECB conversion factor (JPY ~150, CHF ~0.9).
const MIN_FX_RATE: f64 = 1e-3;
const MAX_FX_RATE: f64 = 1e4;
/// blockchain.info returns a weekly series; the point used for a date
/// must lie within this distance of it, else the date is not covered.
const MAX_POINT_DISTANCE_SECS: i64 = 2 * SECS_PER_DAY;

/// `true` when `rate` is a believable fiat-per-BTC price.
pub(crate) fn is_valid_btc_fiat_rate(rate: f64) -> bool {
    rate.is_finite() && (MIN_BTC_FIAT_RATE..=MAX_BTC_FIAT_RATE).contains(&rate)
}

fn check_btc_fiat_rate(rate: f64, source: &str) -> Result<f64, RateError> {
    if is_valid_btc_fiat_rate(rate) {
        Ok(rate)
    } else {
        Err(RateError::AllSourcesFailed {
            last_error: format!("{source}: implausible rate {rate}"),
        })
    }
}

// ---------------------------------------------------------------------------
// Public types
// ---------------------------------------------------------------------------

/// One date→rate data point returned by range queries.
#[derive(Debug, Clone, PartialEq)]
pub struct RatePoint {
    /// UTC midnight Unix timestamp for the day.
    pub date_unix: i64,
    /// 1 BTC = `rate` units of the requested currency.
    pub rate: f64,
}

/// Summary returned by `BtcRateHistoryRepository::backfill`.
#[derive(Debug, Clone, Default)]
pub struct BackfillReport {
    pub requested: usize,
    pub cache_hits: usize,
    pub fetched: usize,
    /// Pairs that could not be fetched. (date_unix, currency).
    pub failed: Vec<(i64, String)>,
}

/// Errors specific to the rate history repository.
#[derive(Debug, thiserror::Error)]
pub enum RateError {
    #[error("currency not supported: {0}")]
    CurrencyNotSupported(String),
    #[error("network unavailable")]
    NetworkUnavailable,
    #[error("rate limit exceeded")]
    RateLimitExceeded,
    #[error("all sources failed: {last_error}")]
    AllSourcesFailed { last_error: String },
    #[error("invalid date range")]
    InvalidDateRange,
    #[error("storage error: {0}")]
    Storage(String),
}

impl From<OnchainError> for RateError {
    fn from(e: OnchainError) -> Self {
        Self::Storage(e.to_string())
    }
}

// ---------------------------------------------------------------------------
// Trait
// ---------------------------------------------------------------------------

/// Repository for BTC/fiat historical and current rates.
pub trait BtcRateHistoryRepository: Send + Sync {
    /// Rate at a specific date. Cache → primary → fallback.
    ///
    /// Returns `Ok(None)` only when the date is genuinely in the future or
    /// predates Bitcoin genesis (< 2009-01-03). Network/source failures
    /// surface as `RateError`.
    fn rate_at(&self, date_unix: i64, currency: &str) -> Result<Option<f64>, RateError>;

    /// Like [`rate_at`](Self::rate_at) but bypasses the cache: always asks
    /// the network and overwrites the cached value on success. Used by the
    /// "recompute all" repair so a bad cached rate can actually be fixed.
    ///
    /// The default delegates to `rate_at` (fine for in-memory mocks).
    fn fresh_rate_at(&self, date_unix: i64, currency: &str) -> Result<Option<f64>, RateError> {
        self.rate_at(date_unix, currency)
    }

    /// Cache-only rate lookup: never touches the network. `Ok(None)` when
    /// the day is not cached. Used by hot loops (e.g. the per-day net
    /// worth reconstruction) after a single bulk [`range`](Self::range)
    /// prefetch, so a cache miss cannot fan out into one HTTP round-trip
    /// per day.
    ///
    /// The default delegates to [`rate_at`](Self::rate_at), which is
    /// correct for purely in-memory implementations (test mocks).
    fn cached_rate_at(&self, date_unix: i64, currency: &str) -> Result<Option<f64>, RateError> {
        self.rate_at(date_unix, currency)
    }

    /// Range query, daily resolution, sorted by date ASC.
    ///
    /// Missing days within the range are returned as gaps (no interpolation).
    fn range(
        &self,
        from_unix: i64,
        to_unix: i64,
        currency: &str,
    ) -> Result<Vec<RatePoint>, RateError>;

    /// Current rate. Returns `RateError::NetworkUnavailable` until Phase 8.3
    /// wires in the live-price delegate.
    fn current(&self, currency: &str) -> Result<f64, RateError>;

    /// Bulk backfill for a list of `(date, currency)` pairs. Idempotent:
    /// pairs already cached are skipped. Returns a progress report.
    fn backfill(&self, requests: &[(i64, &str)]) -> Result<BackfillReport, RateError>;
}

// ---------------------------------------------------------------------------
// Date helpers
// ---------------------------------------------------------------------------

/// Truncate any Unix timestamp to UTC midnight of its day.
///
/// Never overflows: for timestamps within one day of `i64::MIN` (where
/// truncating down would underflow) the next midnight is returned instead.
pub fn normalize_to_utc_midnight(unix: i64) -> i64 {
    let rem = unix.rem_euclid(SECS_PER_DAY);
    unix.checked_sub(rem)
        .unwrap_or_else(|| unix.saturating_add(SECS_PER_DAY - rem))
}

/// Current Unix timestamp (seconds since epoch).
pub(crate) fn now_unix() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

/// Validate a currency string against the whitelist.
fn validate_currency(currency: &str) -> Result<&str, RateError> {
    if SUPPORTED_CURRENCIES.contains(&currency) {
        Ok(currency)
    } else {
        Err(RateError::CurrencyNotSupported(currency.to_string()))
    }
}

// ---------------------------------------------------------------------------
// Token bucket rate limiter for CoinGecko (25/min)
// ---------------------------------------------------------------------------

pub(crate) struct RateLimiter {
    /// Available tokens (0..=max_tokens).
    tokens: AtomicU32,
    /// Unix timestamp of last refill (seconds).
    last_refill_unix: AtomicI64,
    /// Maximum tokens (= refill amount).
    max_tokens: u32,
    /// Refill period in seconds.
    refill_period_secs: u64,
}

impl RateLimiter {
    pub(crate) const fn new(max_tokens: u32, refill_period_secs: u64) -> Self {
        Self {
            tokens: AtomicU32::new(max_tokens),
            last_refill_unix: AtomicI64::new(0),
            max_tokens,
            refill_period_secs,
        }
    }

    /// Acquire one token, blocking (sleeping) if needed until a refill.
    pub(crate) fn acquire(&self) {
        loop {
            self.maybe_refill();
            let current = self.tokens.load(Ordering::SeqCst);
            if current > 0 {
                // CAS: decrement if still non-zero.
                if self
                    .tokens
                    .compare_exchange(current, current - 1, Ordering::SeqCst, Ordering::SeqCst)
                    .is_ok()
                {
                    return;
                }
                // Lost the race — retry.
            } else {
                // No tokens: sleep until the next refill window.
                std::thread::sleep(std::time::Duration::from_secs(self.refill_period_secs));
            }
        }
    }

    fn maybe_refill(&self) {
        let now = now_unix();
        let last = self.last_refill_unix.load(Ordering::SeqCst);
        let elapsed = (now - last) as u64;
        if elapsed >= self.refill_period_secs {
            // Attempt to claim the refill slot.
            if self
                .last_refill_unix
                .compare_exchange(last, now, Ordering::SeqCst, Ordering::SeqCst)
                .is_ok()
            {
                self.tokens.store(self.max_tokens, Ordering::SeqCst);
            }
        }
    }
}

impl std::fmt::Debug for RateLimiter {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("RateLimiter")
            .field("tokens", &self.tokens.load(Ordering::Relaxed))
            .field(
                "last_refill_unix",
                &self.last_refill_unix.load(Ordering::Relaxed),
            )
            .field("max_tokens", &self.max_tokens)
            .field("refill_period_secs", &self.refill_period_secs)
            .finish()
    }
}

// ---------------------------------------------------------------------------
// HTTP source helpers
// ---------------------------------------------------------------------------

/// CoinGecko response for /coins/bitcoin/history
#[derive(Deserialize, Debug)]
struct CoinGeckoHistoryResponse {
    market_data: Option<CgMarketData>,
}

#[derive(Deserialize, Debug)]
struct CgMarketData {
    current_price: std::collections::HashMap<String, f64>,
}

/// CoinGecko response for /market_chart/range
#[derive(Deserialize, Debug)]
struct CoinGeckoRangeResponse {
    /// Array of [timestamp_ms, price] pairs.
    prices: Vec<[f64; 2]>,
}

/// blockchain.info /charts/market-price response.
#[derive(Deserialize, Debug)]
struct BlockchainInfoResponse {
    values: Vec<BlockchainInfoPoint>,
}

#[derive(Deserialize, Debug)]
struct BlockchainInfoPoint {
    x: i64, // Unix timestamp (seconds)
    y: f64, // USD price
}

/// Frankfurter response for /{date}?from=USD&to=X
#[derive(Deserialize, Debug)]
struct FrankfurterResponse {
    rates: std::collections::HashMap<String, f64>,
}

// ---------------------------------------------------------------------------
// Source fetch functions (standalone, no self)
// ---------------------------------------------------------------------------

/// GET `url` and decode a JSON body, capped at 4 MB (guards against a
/// runaway or hostile server). `label` prefixes error messages.
fn get_json<T: serde::de::DeserializeOwned>(
    agent: &ureq::Agent,
    url: &str,
    label: &str,
) -> Result<T, RateError> {
    use std::io::Read as _;
    let resp = agent
        .get(url)
        .call()
        .map_err(|e| RateError::AllSourcesFailed {
            last_error: format!("{label}: {e}"),
        })?;
    let mut buf = Vec::new();
    resp.into_reader()
        .take(4 * 1024 * 1024)
        .read_to_end(&mut buf)
        .map_err(|e| RateError::AllSourcesFailed {
            last_error: format!("{label} read: {e}"),
        })?;
    serde_json::from_slice(&buf).map_err(|e| RateError::AllSourcesFailed {
        last_error: format!("{label} parse: {e}"),
    })
}

/// Split missing days into `(older than the CoinGecko keyless window,
/// inside it)`. `window_start` is the earliest day CoinGecko serves.
fn split_missing_by_window(missing: &[i64], window_start: i64) -> (Vec<i64>, Vec<i64>) {
    missing.iter().partition(|d| **d < window_start)
}

/// Fetch BTC/{currency} from CoinGecko for a specific date.
///
/// Endpoint: `/coins/bitcoin/history?date=DD-MM-YYYY&localization=false`
fn fetch_coingecko_history(
    agent: &ureq::Agent,
    date_unix: i64,
    currency: &str,
    limiter: &RateLimiter,
) -> Result<f64, RateError> {
    limiter.acquire();

    // Format the date as DD-MM-YYYY (CoinGecko's expected format).
    let dt = unix_to_ymd(date_unix);
    let date_str = format!("{:02}-{:02}-{:04}", dt.2, dt.1, dt.0);

    let url = format!(
        "https://api.coingecko.com/api/v3/coins/bitcoin/history?date={date_str}&localization=false"
    );
    let body: CoinGeckoHistoryResponse = get_json(agent, &url, "coingecko history")?;

    let price = body
        .market_data
        .and_then(|md| md.current_price.get(&currency.to_lowercase()).copied())
        .ok_or_else(|| RateError::AllSourcesFailed {
            last_error: format!("coingecko history: no price for {currency}"),
        })?;

    check_btc_fiat_rate(price, "coingecko history")
}

/// Pick the blockchain.info point for `date_unix`: the closest one, which
/// must be a valid rate AND lie within [`MAX_POINT_DISTANCE_SECS`] of the
/// target day — otherwise the series does not cover the date and we must
/// not pass off a far-away price as the historical rate.
fn pick_blockchain_info_point(
    points: &[BlockchainInfoPoint],
    date_unix: i64,
) -> Result<f64, RateError> {
    let target = normalize_to_utc_midnight(date_unix);
    let point = points
        .iter()
        .min_by_key(|p| (p.x - target).abs())
        .ok_or_else(|| RateError::AllSourcesFailed {
            last_error: "blockchain.info: empty response".to_string(),
        })?;
    if (point.x - target).abs() > MAX_POINT_DISTANCE_SECS {
        return Err(RateError::AllSourcesFailed {
            last_error: format!(
                "blockchain.info: closest point is {}s from target",
                (point.x - target).abs()
            ),
        });
    }
    check_btc_fiat_rate(point.y, "blockchain.info")
}

/// Fetch BTC/USD from blockchain.info for a specific date.
///
/// Endpoint: `/charts/market-price?timespan=1weeks&start={ts}&format=json`
fn fetch_blockchain_info_usd(agent: &ureq::Agent, date_unix: i64) -> Result<f64, RateError> {
    // Fetch a small window around the target date.
    let url = format!(
        "https://api.blockchain.info/charts/market-price?timespan=1weeks&start={date_unix}&format=json"
    );
    let body: BlockchainInfoResponse = get_json(agent, &url, "blockchain.info")?;
    pick_blockchain_info_point(&body.values, date_unix)
}

/// Fetch USD→`currency` from Frankfurter (ECB rates) for a specific date.
///
/// Endpoint: `/{date}?from=USD&to={currency}`
fn fetch_frankfurter(
    agent: &ureq::Agent,
    date_unix: i64,
    currency: &str,
) -> Result<f64, RateError> {
    let dt = unix_to_ymd(date_unix);
    let date_str = format!("{:04}-{:02}-{:02}", dt.0, dt.1, dt.2);

    let url = format!("https://api.frankfurter.dev/v1/{date_str}?from=USD&to={currency}");
    let body: FrankfurterResponse = get_json(agent, &url, "frankfurter")?;

    let rate = body
        .rates
        .get(currency)
        .copied()
        .ok_or_else(|| RateError::AllSourcesFailed {
            last_error: format!("frankfurter: no rate for {currency}"),
        })?;

    if !rate.is_finite() || !(MIN_FX_RATE..=MAX_FX_RATE).contains(&rate) {
        return Err(RateError::AllSourcesFailed {
            last_error: format!("frankfurter: implausible USD/{currency} rate {rate}"),
        });
    }

    Ok(rate)
}

/// Triangulate: BTC/USD (blockchain.info) × USD/{currency} (Frankfurter) → BTC/{currency}.
fn fetch_triangulated(
    agent: &ureq::Agent,
    date_unix: i64,
    currency: &str,
) -> Result<f64, RateError> {
    let btc_usd = fetch_blockchain_info_usd(agent, date_unix)?;
    if currency == "USD" {
        return Ok(btc_usd);
    }
    let usd_to_ccy = fetch_frankfurter(agent, date_unix, currency)?;
    check_btc_fiat_rate(btc_usd * usd_to_ccy, "triangulated")
}

/// Fetch a range of BTC/{currency} prices via CoinGecko market_chart/range.
///
/// Returns a Vec of `(date_unix_midnight, rate)` pairs, sorted by date ASC.
/// Implausible points (non-finite, out of bounds) are dropped.
fn fetch_coingecko_range(
    agent: &ureq::Agent,
    from_unix: i64,
    to_unix: i64,
    currency: &str,
    limiter: &RateLimiter,
) -> Result<Vec<(i64, f64)>, RateError> {
    limiter.acquire();

    let url = format!(
        "https://api.coingecko.com/api/v3/coins/bitcoin/market_chart/range?vs_currency={}&from={}&to={}",
        currency.to_lowercase(),
        from_unix,
        to_unix
    );
    let body: CoinGeckoRangeResponse = get_json(agent, &url, "coingecko range")?;

    // CoinGecko returns timestamps in milliseconds.
    let mut points: Vec<(i64, f64)> = body
        .prices
        .iter()
        .filter(|p| p[0].is_finite() && is_valid_btc_fiat_rate(p[1]))
        .map(|p| {
            let ts_secs = (p[0] as i64) / 1000;
            (normalize_to_utc_midnight(ts_secs), p[1])
        })
        .collect();

    // Deduplicate by day (keep last value for each day).
    points.sort_by_key(|(ts, _)| *ts);
    points.dedup_by_key(|(ts, _)| *ts);

    Ok(points)
}

// ---------------------------------------------------------------------------
// SQLite cache helpers (operate on a raw Connection)
// ---------------------------------------------------------------------------

/// Look up a single (date, currency) from the cache. Returns `None` on miss.
fn cache_lookup(
    conn: &Connection,
    date_unix: i64,
    currency: &str,
) -> Result<Option<f64>, RateError> {
    let mut stmt = conn
        .prepare("SELECT rate FROM btc_rate_history WHERE date_unix = ?1 AND currency = ?2")
        .map_err(|e| RateError::Storage(e.to_string()))?;
    let mut rows = stmt
        .query(params![date_unix, currency])
        .map_err(|e| RateError::Storage(e.to_string()))?;
    match rows.next().map_err(|e| RateError::Storage(e.to_string()))? {
        None => Ok(None),
        // A cached value that fails validation (written before the
        // audit-M5 bounds existed) is treated as a miss so it gets
        // refetched and replaced via `cache_upsert`.
        Some(row) => Ok(Some(
            row.get::<_, f64>(0)
                .map_err(|e| RateError::Storage(e.to_string()))?,
        )
        .filter(|r| is_valid_btc_fiat_rate(*r))),
    }
}

/// Insert or overwrite a freshly fetched rate. Used after a cache miss and
/// by the cache-bypassing [`BtcRateHistoryRepository::fresh_rate_at`], so
/// a bad cached value can be replaced.
fn cache_upsert(
    conn: &Connection,
    date_unix: i64,
    currency: &str,
    rate: f64,
    source: &str,
) -> Result<(), RateError> {
    conn.execute(
        "INSERT OR REPLACE INTO btc_rate_history          (date_unix, currency, rate, source, fetched_at_unix)          VALUES (?1, ?2, ?3, ?4, ?5)",
        params![date_unix, currency, rate, source, now_unix()],
    )
    .map_err(|e| RateError::Storage(e.to_string()))?;
    Ok(())
}

/// Insert into cache. Uses INSERT OR IGNORE for idempotency.
fn cache_insert(
    conn: &Connection,
    date_unix: i64,
    currency: &str,
    rate: f64,
    source: &str,
) -> Result<(), RateError> {
    conn.execute(
        "INSERT OR IGNORE INTO btc_rate_history \
         (date_unix, currency, rate, source, fetched_at_unix) \
         VALUES (?1, ?2, ?3, ?4, ?5)",
        params![date_unix, currency, rate, source, now_unix()],
    )
    .map_err(|e| RateError::Storage(e.to_string()))?;
    Ok(())
}

/// Query a date range from cache. Returns sorted by date ASC.
fn cache_range(
    conn: &Connection,
    from_unix: i64,
    to_unix: i64,
    currency: &str,
) -> Result<Vec<RatePoint>, RateError> {
    let mut stmt = conn
        .prepare(
            "SELECT date_unix, rate FROM btc_rate_history \
             WHERE date_unix >= ?1 AND date_unix <= ?2 AND currency = ?3 \
             ORDER BY date_unix ASC",
        )
        .map_err(|e| RateError::Storage(e.to_string()))?;
    let rows = stmt
        .query_map(params![from_unix, to_unix, currency], |row| {
            Ok(RatePoint {
                date_unix: row.get(0)?,
                rate: row.get(1)?,
            })
        })
        .map_err(|e| RateError::Storage(e.to_string()))?;
    let mut out = Vec::new();
    for r in rows {
        let p = r.map_err(|e| RateError::Storage(e.to_string()))?;
        if is_valid_btc_fiat_rate(p.rate) {
            out.push(p);
        }
    }
    Ok(out)
}

// ---------------------------------------------------------------------------
// Concrete repository implementation
// ---------------------------------------------------------------------------

/// Concrete implementation of `BtcRateHistoryRepository`.
///
/// Holds an open SQLCipher connection (behind a `Mutex` so the impl is
/// `Send + Sync`) and the HTTP agent for the configured proxy. The
/// CoinGecko rate limiter is process-wide ([`coingecko_limiter`]).
///
/// Audit M4: the connection mutex is held only for cache reads/writes,
/// never across a network fetch.
pub struct BtcRateRepo {
    conn: Mutex<Connection>,
    agent: ureq::Agent,
}

impl std::fmt::Debug for BtcRateRepo {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("BtcRateRepo").finish_non_exhaustive()
    }
}

/// One successful network lookup: the day it is for, the rate, the source tag.
type Fetched = (i64, f64, &'static str);

impl BtcRateRepo {
    /// Open the repository using an existing open (already-keyed) `Connection`.
    ///
    /// The caller is responsible for applying `PRAGMA key` before passing the
    /// connection here. `ensure_schema` is called internally to create/migrate
    /// the `btc_rate_history` table. Network fetches go direct (no proxy);
    /// use [`Self::with_proxy`] to route them through Tor.
    pub fn new(conn: Connection) -> Result<Self, OnchainError> {
        Self::ensure_schema(&conn)?;
        let agent = rate_agent(None).map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(Self {
            conn: Mutex::new(conn),
            agent,
        })
    }

    /// Route every rate fetch through the SOCKS5 proxy `proxy_url`
    /// (`socks5h://host:port`). `None` keeps direct connections.
    ///
    /// # Errors
    ///
    /// [`OnchainError::InvalidArgument`] if the proxy URL is malformed —
    /// the caller must not silently fall back to clearnet.
    pub fn with_proxy(mut self, proxy_url: Option<&str>) -> Result<Self, OnchainError> {
        if let Some(p) = proxy_url.map(str::trim).filter(|p| !p.is_empty()) {
            // Validate first so a bad setting is a typed argument error.
            crate::tor::ureq_socks_proxy(p)?;
            self.agent =
                rate_agent(Some(p)).map_err(|e| OnchainError::InvalidArgument(e.to_string()))?;
        }
        Ok(self)
    }

    /// Open the repository at the given SQLCipher-encrypted DB path.
    ///
    /// Uses the existing `open_encrypted_connection` pipeline. The schema is
    /// applied (idempotent) before returning. This is the entry point used by
    /// the FFI layer in `bhodl-uniffi`.
    pub fn open(
        db_path: &std::path::Path,
        encryption_key: &[u8; crate::common::ENCRYPTION_KEY_LEN],
    ) -> Result<Self, OnchainError> {
        let conn = crate::common::open_encrypted_connection(db_path, encryption_key)?;
        Self::new(conn)
    }

    /// Create a non-encrypted in-memory SQLite connection for tests.
    #[cfg(test)]
    pub fn in_memory() -> Result<Self, OnchainError> {
        let conn =
            Connection::open_in_memory().map_err(|e| OnchainError::Storage(e.to_string()))?;
        Self::new(conn)
    }

    fn ensure_schema(conn: &Connection) -> Result<(), OnchainError> {
        conn.execute(
            "CREATE TABLE IF NOT EXISTS btc_rate_history (\
                date_unix       INTEGER NOT NULL, \
                currency        TEXT NOT NULL, \
                rate            REAL NOT NULL, \
                source          TEXT NOT NULL, \
                fetched_at_unix INTEGER NOT NULL, \
                PRIMARY KEY (date_unix, currency)\
            )",
            [],
        )
        .map_err(|e| OnchainError::Storage(e.to_string()))?;

        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_btc_rate_history_date \
             ON btc_rate_history(date_unix)",
            [],
        )
        .map_err(|e| OnchainError::Storage(e.to_string()))?;

        Ok(())
    }

    fn lock_conn(&self) -> Result<std::sync::MutexGuard<'_, Connection>, RateError> {
        self.conn
            .lock()
            .map_err(|_| RateError::Storage("mutex poisoned".into()))
    }

    /// Internal: fetch a single (date, currency) from network sources.
    /// Pure network — touches no lock and no cache.
    fn fetch_remote(&self, norm: i64, currency: &str) -> Result<Fetched, RateError> {
        let agent = &self.agent;
        let limiter = coingecko_limiter();
        let days_back = (now_unix() - norm) / SECS_PER_DAY;

        if days_back <= COINGECKO_KEYLESS_WINDOW_DAYS {
            // Primary: CoinGecko history (has non-USD pairs directly).
            return match fetch_coingecko_history(agent, norm, currency, limiter) {
                Ok(rate) => Ok((norm, rate, "coingecko-history")),
                // Fallback: triangulation via blockchain.info + Frankfurter.
                Err(e) => match fetch_triangulated(agent, norm, currency) {
                    Ok(rate) => Ok((norm, rate, "ecb-triangulated")),
                    Err(e2) => Err(RateError::AllSourcesFailed {
                        last_error: format!("coingecko: {e}, triangulated: {e2}"),
                    }),
                },
            };
        }

        // days_back > 365: CoinGecko's keyless API refuses these (HTTP
        // 401), so blockchain.info (+ Frankfurter) is the only source.
        let rate = fetch_triangulated(agent, norm, currency)?;
        let source = if currency == "USD" {
            "blockchain-info"
        } else {
            "ecb-triangulated"
        };
        Ok((norm, rate, source))
    }

    /// Network fetch (no lock held) followed by a short locked cache write.
    fn fetch_and_store(&self, norm: i64, currency: &str) -> Result<Option<f64>, RateError> {
        let (ts, rate, source) = self.fetch_remote(norm, currency)?;
        let conn = self.lock_conn()?;
        cache_upsert(&conn, ts, currency, rate, source)?;
        Ok(Some(rate))
    }
}

impl BtcRateHistoryRepository for BtcRateRepo {
    fn cached_rate_at(&self, date_unix: i64, currency: &str) -> Result<Option<f64>, RateError> {
        let norm = normalize_to_utc_midnight(date_unix);
        validate_currency(currency)?;
        let conn = self.lock_conn()?;
        cache_lookup(&conn, norm, currency)
    }

    fn rate_at(&self, date_unix: i64, currency: &str) -> Result<Option<f64>, RateError> {
        let norm = normalize_to_utc_midnight(date_unix);
        validate_currency(currency)?;

        // Genuinely unanswerable dates.
        if norm < BITCOIN_GENESIS_UNIX || norm > now_unix() {
            return Ok(None);
        }

        // Cache check — lock released before any network I/O (audit M4).
        let cached = {
            let conn = self.lock_conn()?;
            cache_lookup(&conn, norm, currency)?
        };
        if let Some(r) = cached {
            return Ok(Some(r));
        }

        self.fetch_and_store(norm, currency)
    }

    fn fresh_rate_at(&self, date_unix: i64, currency: &str) -> Result<Option<f64>, RateError> {
        let norm = normalize_to_utc_midnight(date_unix);
        validate_currency(currency)?;
        if norm < BITCOIN_GENESIS_UNIX || norm > now_unix() {
            return Ok(None);
        }
        // Cache bypass: always refetch and overwrite (recompute path).
        self.fetch_and_store(norm, currency)
    }

    fn range(
        &self,
        from_unix: i64,
        to_unix: i64,
        currency: &str,
    ) -> Result<Vec<RatePoint>, RateError> {
        validate_currency(currency)?;
        let from = normalize_to_utc_midnight(from_unix);
        let to = normalize_to_utc_midnight(to_unix);

        if from > to {
            return Err(RateError::InvalidDateRange);
        }

        // Try to fill from cache first; lock released before network I/O.
        let cached = {
            let conn = self.lock_conn()?;
            cache_range(&conn, from, to, currency)?
        };

        // Build the set of days already covered.
        let cached_days: std::collections::HashSet<i64> =
            cached.iter().map(|p| p.date_unix).collect();

        // Determine which days are missing (bounded to today).
        let today = normalize_to_utc_midnight(now_unix());
        let mut missing: Vec<i64> = Vec::new();
        let mut day = from;
        while day <= to.min(today) {
            if !cached_days.contains(&day) {
                missing.push(day);
            }
            day += SECS_PER_DAY;
        }

        // CoinGecko (keyless) only serves the last 365 days; older
        // days go straight to the per-day triangulation path.
        let window_start = today - COINGECKO_KEYLESS_WINDOW_DAYS * SECS_PER_DAY;
        let (old_missing, recent_missing) = split_missing_by_window(&missing, window_start);

        if let (Some(&range_start), Some(&last_missing)) =
            (recent_missing.first(), recent_missing.last())
        {
            let range_end = last_missing + SECS_PER_DAY;
            // Attempt CoinGecko range fetch (network, unlocked).
            match fetch_coingecko_range(
                &self.agent,
                range_start,
                range_end,
                currency,
                coingecko_limiter(),
            ) {
                Ok(points) => {
                    let conn = self.lock_conn()?;
                    for (ts, rate) in &points {
                        // Days we found missing (or holding an implausible
                        // value) are overwritten; cached good days are kept.
                        let _ = if recent_missing.contains(ts) {
                            cache_upsert(&conn, *ts, currency, *rate, "coingecko-range")
                        } else {
                            cache_insert(&conn, *ts, currency, *rate, "coingecko-range")
                        };
                    }
                }
                Err(_) => {
                    // Fallback: fetch each missing day individually.
                    for day_ts in &recent_missing {
                        if *day_ts >= BITCOIN_GENESIS_UNIX {
                            let _ = self.fetch_and_store(*day_ts, currency);
                        }
                    }
                }
            }
        }

        for day_ts in &old_missing {
            if *day_ts >= BITCOIN_GENESIS_UNIX {
                let _ = self.fetch_and_store(*day_ts, currency);
            }
        }

        // Re-query the cache for the full range.
        let conn = self.lock_conn()?;
        cache_range(&conn, from, to, currency)
    }

    fn current(&self, currency: &str) -> Result<f64, RateError> {
        validate_currency(currency)?;
        // TODO Phase 8.3: delegate to existing PriceCache live-price layer.
        // For Phase 8.1 this is intentionally not implemented — callers on the
        // Kotlin side should use PriceCache directly until Phase 8.3 wires the
        // two together.
        Err(RateError::NetworkUnavailable)
    }

    fn backfill(&self, requests: &[(i64, &str)]) -> Result<BackfillReport, RateError> {
        let mut report = BackfillReport {
            requested: requests.len(),
            ..BackfillReport::default()
        };

        // Dedup + sort before processing.
        let mut deduped: Vec<(i64, &str)> = requests.to_vec();
        deduped.sort_by(|a, b| a.0.cmp(&b.0).then(a.1.cmp(b.1)));
        deduped.dedup();

        for (date, ccy) in deduped {
            // Validate currency — skip silently with failure if unsupported.
            if validate_currency(ccy).is_err() {
                let norm = normalize_to_utc_midnight(date);
                report.failed.push((norm, ccy.to_string()));
                continue;
            }

            let norm = normalize_to_utc_midnight(date);

            // Check cache first.
            let cached = {
                let conn = self.lock_conn()?;
                cache_lookup(&conn, norm, ccy)?
            };

            if cached.is_some() {
                report.cache_hits += 1;
                continue;
            }

            // Fetch via rate_at (handles source selection and caching).
            match self.rate_at(norm, ccy) {
                Ok(Some(_)) => report.fetched += 1,
                Ok(None) | Err(_) => report.failed.push((norm, ccy.to_string())),
            }
        }

        Ok(report)
    }
}

// ---------------------------------------------------------------------------
// Date decomposition helper
// ---------------------------------------------------------------------------

/// Decompose a Unix timestamp (assumed UTC midnight) to (year, month, day).
#[allow(clippy::similar_names)] // calendar variables (doe/doy/yoe/era) follow the algorithm's naming
fn unix_to_ymd(unix: i64) -> (i32, u32, u32) {
    // Days since 1970-01-01
    let days = unix / SECS_PER_DAY;
    // Gregorian calendar calculation (Fliegel-Van Flandern algorithm).
    let z = days + 719_468;
    let era = (if z >= 0 { z } else { z - 146_096 }) / 146_097;
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = if m <= 2 { y + 1 } else { y };
    (y as i32, m as u32, d as u32)
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
#[allow(
    clippy::float_cmp,
    reason = "tests assert exact, deterministically computed f64 values"
)]
mod tests {
    use super::*;

    // -----------------------------------------------------------------------
    // Unit tests that run offline (no network)
    // -----------------------------------------------------------------------

    #[test]
    fn normalize_to_utc_midnight_noon_same_as_midnight() {
        // 2024-04-19 00:00 UTC = 1713484800
        let midnight = 1_713_484_800_i64;
        let noon = midnight + 12 * 3600;
        let evening = midnight + 23 * 3600 + 59 * 60 + 59;
        assert_eq!(normalize_to_utc_midnight(midnight), midnight);
        assert_eq!(normalize_to_utc_midnight(noon), midnight);
        assert_eq!(normalize_to_utc_midnight(evening), midnight);
    }

    #[test]
    fn rate_at_normalizes_to_utc_midnight() {
        let repo = BtcRateRepo::in_memory().unwrap();
        let midnight = 1_713_484_800_i64; // 2024-04-19
        let noon = midnight + 12 * 3600;

        // Seed the cache at midnight.
        {
            let conn = repo.conn.lock().unwrap();
            cache_insert(&conn, midnight, "USD", 63_000.0, "coingecko-history").unwrap();
        }

        // Query at noon — should hit the midnight cache entry.
        let result = repo.rate_at(noon, "USD");
        // Won't hit network because we go cache-first; and network would fail offline.
        // Accept either a cache hit (Ok(Some)) or a network-sourced Ok(Some).
        // In offline test we just verify it doesn't crash and normalisation works.
        match result {
            Ok(Some(r)) => assert!((r - 63_000.0).abs() < f64::EPSILON),
            Ok(None) => panic!("expected Some rate, got None"),
            Err(_) => {
                // Offline environment — the important thing is that
                // cache_lookup with the noon ts was normalised to midnight.
                // Verify directly:
                let conn = repo.conn.lock().unwrap();
                let cached = cache_lookup(&conn, normalize_to_utc_midnight(noon), "USD").unwrap();
                assert!(
                    cached.is_some(),
                    "normalised noon should hit midnight cache"
                );
            }
        }
    }

    #[test]
    fn rate_at_before_bitcoin_returns_none() {
        let repo = BtcRateRepo::in_memory().unwrap();
        // 2008-01-01 00:00 UTC
        let pre_genesis = 1_199_145_600_i64;
        assert!(pre_genesis < BITCOIN_GENESIS_UNIX);
        let result = repo.rate_at(pre_genesis, "USD").unwrap();
        assert!(result.is_none(), "pre-genesis date must return None");
    }

    #[test]
    fn rate_at_future_returns_none() {
        let repo = BtcRateRepo::in_memory().unwrap();
        let future = now_unix() + 2 * SECS_PER_DAY;
        let result = repo.rate_at(future, "USD").unwrap();
        assert!(result.is_none(), "future date must return None");
    }

    #[test]
    fn rate_at_unsupported_currency_returns_error() {
        let repo = BtcRateRepo::in_memory().unwrap();
        let result = repo.rate_at(1_713_484_800, "XYZ");
        assert!(
            matches!(result, Err(RateError::CurrencyNotSupported(_))),
            "unsupported currency must return CurrencyNotSupported error"
        );
    }

    #[test]
    fn rate_at_caches_after_first_fetch_via_seed() {
        // Verify that a seeded cache entry is returned on subsequent calls
        // without hitting the network.
        let repo = BtcRateRepo::in_memory().unwrap();
        let date = 1_713_484_800_i64; // 2024-04-19

        // Manually seed the cache.
        {
            let conn = repo.conn.lock().unwrap();
            cache_insert(&conn, date, "USD", 63_500.0, "coingecko-history").unwrap();
        }

        // First call — should hit cache.
        let r1 = repo.rate_at(date, "USD").unwrap();
        assert_eq!(r1, Some(63_500.0));

        // Second call — same result.
        let r2 = repo.rate_at(date, "USD").unwrap();
        assert_eq!(r2, Some(63_500.0));
    }

    #[test]
    fn range_returns_sorted_ascending() {
        let repo = BtcRateRepo::in_memory().unwrap();
        let base = 1_704_067_200_i64; // 2024-01-01

        // Seed 5 days in reverse order.
        {
            let conn = repo.conn.lock().unwrap();
            for i in (0..5_u8).rev() {
                let date = base + i64::from(i) * SECS_PER_DAY;
                cache_insert(
                    &conn,
                    date,
                    "EUR",
                    40_000.0 + f64::from(i) * 500.0,
                    "coingecko-history",
                )
                .unwrap();
            }
        }

        let result = repo.range(base, base + 4 * SECS_PER_DAY, "EUR").unwrap();
        assert_eq!(result.len(), 5);
        for w in result.windows(2) {
            assert!(w[0].date_unix < w[1].date_unix, "must be sorted ASC");
        }
    }

    #[test]
    fn range_handles_gap_days() {
        let repo = BtcRateRepo::in_memory().unwrap();
        let base = 1_704_067_200_i64; // 2024-01-01

        // Seed day 0 and day 2 but NOT day 1.
        {
            let conn = repo.conn.lock().unwrap();
            cache_insert(&conn, base, "USD", 40_000.0, "coingecko-history").unwrap();
            cache_insert(
                &conn,
                base + 2 * SECS_PER_DAY,
                "USD",
                42_000.0,
                "coingecko-history",
            )
            .unwrap();
        }

        // Query the full 3-day range. Day 1 is missing — the call may try
        // network (which will fail offline), but the cached days should still
        // appear in the result.
        let result = repo.range(base, base + 2 * SECS_PER_DAY, "USD");
        if let Ok(points) = result {
            // At minimum the 2 seeded days should be present.
            assert!(points.len() >= 2);
            // Must be sorted.
            for w in points.windows(2) {
                assert!(w[0].date_unix < w[1].date_unix);
            }
        } else {
            // Offline — range re-query after failed bulk fetch still
            // returns cached entries.
            let conn = repo.conn.lock().unwrap();
            let cached = cache_range(&conn, base, base + 2 * SECS_PER_DAY, "USD").unwrap();
            assert_eq!(cached.len(), 2);
        }
    }

    #[test]
    fn backfill_idempotent_across_runs() {
        let repo = BtcRateRepo::in_memory().unwrap();
        let date = 1_713_484_800_i64; // 2024-04-19

        // Pre-seed.
        {
            let conn = repo.conn.lock().unwrap();
            cache_insert(&conn, date, "USD", 63_800.0, "coingecko-history").unwrap();
        }

        let report1 = repo.backfill(&[(date, "USD")]).unwrap();
        let report2 = repo.backfill(&[(date, "USD")]).unwrap();

        assert_eq!(report1.cache_hits, 1);
        assert_eq!(report2.cache_hits, 1);
        assert_eq!(report1.fetched, 0);
        assert_eq!(report2.fetched, 0);
    }

    #[test]
    fn backfill_dedupes_identical_requests() {
        let repo = BtcRateRepo::in_memory().unwrap();
        let date = 1_713_484_800_i64;

        // Seed so it doesn't go to network.
        {
            let conn = repo.conn.lock().unwrap();
            cache_insert(&conn, date, "USD", 63_800.0, "coingecko-history").unwrap();
        }

        // Pass the same request 5 times.
        let requests = vec![(date, "USD"); 5];
        let report = repo.backfill(&requests).unwrap();

        // requested = 5, but cache_hits should count deduped = 1 hit.
        assert_eq!(report.requested, 5);
        assert_eq!(report.cache_hits, 1); // deduped to 1 unique entry
        assert_eq!(report.fetched, 0);
        assert!(report.failed.is_empty());
    }

    #[test]
    fn rate_limiter_tokens_decrement() {
        let limiter = RateLimiter::new(5, 60);
        // Drain 5 tokens quickly.
        for _ in 0..5 {
            limiter.acquire();
        }
        assert_eq!(limiter.tokens.load(Ordering::SeqCst), 0);
    }

    #[test]
    fn migration_creates_table_idempotent() {
        let conn = Connection::open_in_memory().unwrap();
        // First call.
        BtcRateRepo::ensure_schema(&conn).unwrap();
        // Second call — must not error (CREATE TABLE IF NOT EXISTS).
        BtcRateRepo::ensure_schema(&conn).unwrap();

        // Verify the table exists by querying it.
        let count: i64 = conn
            .query_row("SELECT COUNT(*) FROM btc_rate_history", [], |r| r.get(0))
            .unwrap();
        assert_eq!(count, 0);
    }

    #[test]
    fn unix_to_ymd_known_dates() {
        assert_eq!(unix_to_ymd(0), (1970, 1, 1));
        assert_eq!(unix_to_ymd(1_713_484_800), (2024, 4, 19)); // halving
        assert_eq!(unix_to_ymd(1_513_468_800), (2017, 12, 17)); // ATH cycle 2
    }

    // -----------------------------------------------------------------------
    // Online tests — feature-gated so `cargo test` doesn't hit the network
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // Audit 2026-10 — rate validation, cache bypass, Tor routing
    // -----------------------------------------------------------------------

    #[test]
    fn btc_fiat_rate_bounds() {
        assert!(is_valid_btc_fiat_rate(63_800.0));
        assert!(is_valid_btc_fiat_rate(0.06)); // mid-2010 market price
        assert!(!is_valid_btc_fiat_rate(0.0));
        assert!(!is_valid_btc_fiat_rate(-1.0));
        assert!(!is_valid_btc_fiat_rate(f64::NAN));
        assert!(!is_valid_btc_fiat_rate(f64::INFINITY));
        assert!(!is_valid_btc_fiat_rate(1e300));
    }

    #[test]
    fn blockchain_info_point_must_be_close_to_target() {
        let target = 1_600_000_000 - 1_600_000_000 % SECS_PER_DAY;
        let near = [BlockchainInfoPoint {
            x: target + SECS_PER_DAY,
            y: 10_000.0,
        }];
        assert_eq!(pick_blockchain_info_point(&near, target).unwrap(), 10_000.0);

        let far = [BlockchainInfoPoint {
            x: target + 5 * SECS_PER_DAY,
            y: 10_000.0,
        }];
        assert!(pick_blockchain_info_point(&far, target).is_err());

        let zero = [BlockchainInfoPoint { x: target, y: 0.0 }];
        assert!(pick_blockchain_info_point(&zero, target).is_err());

        assert!(pick_blockchain_info_point(&[], target).is_err());
    }

    #[test]
    fn implausible_cached_rate_is_treated_as_a_miss() {
        let repo = BtcRateRepo::in_memory().unwrap();
        let day = 1_713_484_800;
        {
            let conn = repo.conn.lock().unwrap();
            cache_insert(&conn, day, "USD", 1e300, "coingecko-history").unwrap();
            cache_insert(
                &conn,
                day + SECS_PER_DAY,
                "USD",
                64_000.0,
                "coingecko-history",
            )
            .unwrap();
        }
        assert_eq!(repo.cached_rate_at(day, "USD").unwrap(), None);
        // Cache-only range read (no network): the garbage day is dropped.
        let points = {
            let conn = repo.conn.lock().unwrap();
            cache_range(&conn, day, day + SECS_PER_DAY, "USD").unwrap()
        };
        assert_eq!(points.len(), 1);
        assert_eq!(points[0].rate, 64_000.0);
        // cache_upsert replaces the bad value.
        {
            let conn = repo.conn.lock().unwrap();
            cache_upsert(&conn, day, "USD", 63_000.0, "coingecko-history").unwrap();
        }
        assert_eq!(repo.cached_rate_at(day, "USD").unwrap(), Some(63_000.0));
    }

    #[test]
    fn fresh_rate_at_rejects_unanswerable_dates_without_network() {
        let repo = BtcRateRepo::in_memory().unwrap();
        assert_eq!(repo.fresh_rate_at(0, "USD").unwrap(), None);
        assert_eq!(
            repo.fresh_rate_at(now_unix() + 10 * SECS_PER_DAY, "USD")
                .unwrap(),
            None
        );
        assert!(matches!(
            repo.fresh_rate_at(1_713_484_800, "XYZ"),
            Err(RateError::CurrencyNotSupported(_))
        ));
    }

    #[test]
    fn with_proxy_rejects_non_socks_proxy() {
        let repo = BtcRateRepo::in_memory().unwrap();
        assert!(repo.with_proxy(Some("http://127.0.0.1:8080")).is_err());
        let repo = BtcRateRepo::in_memory().unwrap();
        assert!(repo.with_proxy(None).is_ok());
    }

    #[test]
    fn rate_fetch_goes_through_socks_with_remote_dns() {
        // The fake proxy accepts the SOCKS handshake, records the target
        // and hangs up: the TLS handshake then fails, so the fetch errors
        // out — but the destination must have reached the proxy as an
        // unresolved DOMAINNAME, never via local DNS / a direct socket.
        let (proxy, seen) = crate::tor::tests::fake_socks_server(|_| {});
        let url = format!("socks5h://{}:{}", proxy.host, proxy.port);
        let repo = BtcRateRepo::in_memory()
            .unwrap()
            .with_proxy(Some(&url))
            .unwrap();
        let res = fetch_frankfurter(&repo.agent, 1_713_484_800, "EUR");
        assert!(res.is_err());
        let seen = seen
            .recv_timeout(std::time::Duration::from_secs(10))
            .unwrap();
        assert_eq!(
            seen.atyp, 0x03,
            "host must be sent unresolved (ATYP=DOMAINNAME)"
        );
        assert_eq!(seen.host, "api.frankfurter.dev");
        assert_eq!(seen.port, 443);
    }

    #[test]
    fn rate_fetch_fails_closed_when_proxy_is_down() {
        let dead_port = {
            let l = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
            l.local_addr().unwrap().port()
        };
        let repo = BtcRateRepo::in_memory()
            .unwrap()
            .with_proxy(Some(&format!("socks5h://127.0.0.1:{dead_port}")))
            .unwrap();
        assert!(fetch_frankfurter(&repo.agent, 1_713_484_800, "EUR").is_err());
    }

    #[test]
    fn missing_days_older_than_the_keyless_window_skip_coingecko() {
        let today = normalize_to_utc_midnight(now_unix());
        let window_start = today - COINGECKO_KEYLESS_WINDOW_DAYS * SECS_PER_DAY;
        let missing = [
            window_start - 2 * SECS_PER_DAY,
            window_start - SECS_PER_DAY,
            window_start,
            today,
        ];
        let (old, recent) = split_missing_by_window(&missing, window_start);
        assert_eq!(old, vec![missing[0], missing[1]]);
        assert_eq!(recent, vec![window_start, today]);
    }

    #[test]
    fn coingecko_limiter_is_process_wide() {
        assert!(std::ptr::eq(coingecko_limiter(), coingecko_limiter()));
    }

    #[cfg(feature = "online-tests")]
    mod online {
        use super::*;

        /// Known BTC rates with tolerances.
        const KNOWN_RATES: &[(i64, &str, f64, f64)] = &[
            // (date_unix, currency, expected_rate, tolerance)
            (1_713_484_800, "USD", 63_800.0, 1_500.0), // 2024-04-19 halving
            (1_513_468_800, "USD", 19_500.0, 1_000.0), // 2017-12-17 ATH cycle 2
            (1_605_225_600, "EUR", 13_800.0, 800.0),   // 2020-11-13
        ];

        #[test]
        fn rate_at_known_dates_matches_expected_within_tolerance() {
            let repo = BtcRateRepo::in_memory().unwrap();
            for (date_unix, ccy, expected, tolerance) in KNOWN_RATES {
                let rate = repo
                    .rate_at(*date_unix, ccy)
                    .unwrap()
                    .expect("should have a rate");
                let diff = (rate - expected).abs();
                assert!(
                    diff <= *tolerance,
                    "date_unix={date_unix} ccy={ccy}: got {rate}, expected {expected}±{tolerance}, diff={diff}"
                );
            }
        }

        #[test]
        fn range_returns_sorted_ascending_online() {
            let repo = BtcRateRepo::in_memory().unwrap();
            // A week ending 10 days ago: inside CoinGecko's keyless
            // 365-day window, so the range endpoint is exercised.
            let from = normalize_to_utc_midnight(now_unix()) - 17 * SECS_PER_DAY;
            let to = from + 6 * SECS_PER_DAY;
            let result = repo.range(from, to, "EUR").unwrap();
            assert!(!result.is_empty());
            for w in result.windows(2) {
                assert!(w[0].date_unix < w[1].date_unix);
            }
        }

        #[test]
        fn triangulated_eur_within_5pct_of_coingecko_eur_when_both_available() {
            // Use a date well inside the 365d keyless window so both
            // CoinGecko direct and triangulation are available. Accept
            // ±5% tolerance (ECB rate vs realtime).
            let repo = BtcRateRepo::in_memory().unwrap();
            let date = normalize_to_utc_midnight(now_unix()) - 30 * SECS_PER_DAY;

            let cg_eur = fetch_coingecko_history(&repo.agent, date, "EUR", coingecko_limiter())
                .expect("coingecko EUR");
            let tri_eur = fetch_triangulated(&repo.agent, date, "EUR").expect("triangulated EUR");

            let diff_pct = (cg_eur - tri_eur).abs() / cg_eur * 100.0;
            assert!(
                diff_pct <= 5.0,
                "CoinGecko EUR={cg_eur}, triangulated EUR={tri_eur}, diff={diff_pct:.2}%"
            );
        }
    }
}
