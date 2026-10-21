//! BIP-78 `PayJoin` v1 sender.
//!
//! Wire-level protocol implementation against the receiver endpoint
//! advertised by the BIP-21 URI's `pj` parameter. We deliberately avoid
//! the upstream `payjoin` crate so the workspace does not inherit its
//! API churn (the crate has been refactored multiple times across major
//! versions). The protocol surface BHODL needs is small enough to write
//! by hand and easy to audit.
//!
//! Roles in this module:
//! - [`PayjoinUri`] — strict BIP-21 parser for
//!   `bitcoin:addr?amount=X&pj=https://endpoint`. The canonical rules
//!   (duplicate keys rejected case-insensitively, unknown `req-*`
//!   rejected, exact decimal amount) are mirrored 1:1 by the Kotlin
//!   `parseBip21` so the UI and the signer can never disagree on what
//!   a URI means.
//! - [`vet_endpoint`] / [`post_payjoin`] — SSRF-hardened transport:
//!   https only (http allowed for `.onion` through Tor), WHATWG host
//!   parsing via the `url` crate (same parser ureq uses), every
//!   resolved IP vetted and pinned, no redirects.
//! - [`validate_proposal`] — the BIP-78 "Sender's checks" applied to
//!   the receiver's proposal (see the function docs for the list).
//! - A small in-process session store: `OnchainAccount::payjoin_negotiate`
//!   parks the validated proposal under a random id so the user can
//!   review it before `OnchainAccount::payjoin_sign_and_broadcast`
//!   signs it. Sessions never leave Rust, so the FFI caller cannot
//!   swap the PSBT between review and signature.
//!
//! The flow is split in two FFI calls on purpose: nothing is signed
//! for the proposal until the user saw every output it pays.

use std::collections::{HashMap, HashSet};
use std::io::Read;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, ToSocketAddrs};
use std::sync::Mutex;
use std::time::Duration;

use bitcoin::{Address, Amount, OutPoint, Script, Witness, address::NetworkUnchecked, psbt::Psbt};

use crate::OnchainError;

/// Virtual size (rounded up) of one BIP-86 key-path P2TR input:
/// 41 non-witness bytes (164 WU) + 66 WU witness (item count, length,
/// 64-byte Schnorr signature with `SIGHASH_DEFAULT`) = 230 WU = 57.5 vB.
/// BIP-78 sizes `maxadditionalfeecontribution` and the per-input fee
/// cap on this figure.
pub(crate) const P2TR_KEYPATH_INPUT_VSIZE: u64 = 58;

/// 21 M BTC in sat — the absolute supply ceiling for any amount we parse.
const MAX_MONEY_SAT: u64 = 21_000_000 * 100_000_000;

/// Hard cap on parked negotiation sessions. Older ones are evicted;
/// a session is consumed (removed) by the sign step.
const MAX_PENDING_SESSIONS: usize = 4;

fn rejected(msg: impl std::fmt::Display) -> OnchainError {
    OnchainError::TxBuild(format!("PayJoin proposal rejected: {msg}"))
}

fn invalid_uri(msg: impl std::fmt::Display) -> OnchainError {
    OnchainError::InvalidAddress(format!("PayJoin URI: {msg}"))
}

// -----------------------------------------------------------------------
// BIP-21 parsing (canonical rules shared with Kotlin `parseBip21`)
// -----------------------------------------------------------------------

/// Decoded BIP-21 `PayJoin` URI.
///
/// Constructed via [`PayjoinUri::parse`]; surfaces the destination
/// address (still network-unchecked — caller validates against the
/// account's network), the requested amount, and the receiver's
/// `pj` endpoint URL.
#[derive(Debug, Clone)]
pub(crate) struct PayjoinUri {
    pub(crate) address: Address<NetworkUnchecked>,
    pub(crate) amount: Amount,
    pub(crate) endpoint: String,
}

impl PayjoinUri {
    /// Parse a BIP-21 URI of the form
    /// `bitcoin:<addr>?amount=<btc>&pj=<endpoint>`.
    ///
    /// Canonical rules (identical in Kotlin `parseBip21`):
    /// - scheme `bitcoin:` is case-insensitive;
    /// - keys are percent-decoded then compared ASCII-case-insensitively;
    ///   a key seen twice rejects the whole URI (no "last wins");
    /// - any `req-*` key rejects the URI (we understand none);
    /// - malformed percent-encoding / non-UTF-8 rejects the URI;
    /// - `amount` is an exact decimal string — digits, at most one `.`,
    ///   at most 8 fractional digits, `0 < amount <= 21M BTC`; no sign,
    ///   no exponent, no float rounding.
    ///
    /// `PayJoin`-specific: `amount` and `pj` are mandatory and `pj`
    /// must be `https://` (or `http://` to a `.onion`).
    pub(crate) fn parse(uri: &str) -> Result<Self, OnchainError> {
        let uri = uri.trim();
        let rest = match uri.get(..8) {
            Some(scheme) if scheme.eq_ignore_ascii_case("bitcoin:") => &uri[8..],
            _ => return Err(invalid_uri("not a bitcoin: URI")),
        };
        let (addr_str, query_str) = rest.split_once('?').unwrap_or((rest, ""));
        if addr_str.is_empty() {
            return Err(invalid_uri("missing address"));
        }

        let addr: Address<NetworkUnchecked> =
            addr_str
                .parse()
                .map_err(|e: bitcoin::address::ParseError| {
                    OnchainError::InvalidAddress(format!("address {addr_str:?}: {e:?}"))
                })?;

        let mut amount: Option<Amount> = None;
        let mut endpoint: Option<String> = None;
        for (k, v) in parse_bip21_params(query_str)? {
            match k.as_str() {
                "amount" => amount = Some(Amount::from_sat(parse_btc_amount(&v)?)),
                "pj" => endpoint = Some(v),
                _ => {}
            }
        }

        let amount = amount.ok_or_else(|| invalid_uri("missing amount="))?;
        let endpoint = endpoint.ok_or_else(|| invalid_uri("missing pj= endpoint"))?;
        parse_endpoint(&endpoint)?;
        Ok(Self {
            address: addr,
            amount,
            endpoint,
        })
    }
}

/// Split a BIP-21 query into `(lowercased key, decoded value)` pairs,
/// enforcing the canonical rules documented on [`PayjoinUri::parse`].
fn parse_bip21_params(query: &str) -> Result<Vec<(String, String)>, OnchainError> {
    let mut seen: HashSet<String> = HashSet::new();
    let mut out = Vec::new();
    for pair in query.split('&').filter(|s| !s.is_empty()) {
        let (raw_k, raw_v) = pair.split_once('=').unwrap_or((pair, ""));
        let key = decode_percent(raw_k)
            .ok_or_else(|| invalid_uri(format!("malformed percent-encoding in key {raw_k:?}")))?
            .to_ascii_lowercase();
        if key.is_empty() {
            return Err(invalid_uri("empty parameter name"));
        }
        if !seen.insert(key.clone()) {
            return Err(invalid_uri(format!("duplicate parameter {key:?}")));
        }
        if key.starts_with("req-") {
            return Err(invalid_uri(format!(
                "unsupported required parameter {key:?}"
            )));
        }
        let value = decode_percent(raw_v)
            .ok_or_else(|| invalid_uri(format!("malformed percent-encoding in {key:?}")))?;
        out.push((key, value));
    }
    Ok(out)
}

/// Parse a BIP-21 BTC amount as an exact decimal into satoshis.
/// Grammar: `*DIGIT [ "." *DIGIT ]`, at least one digit, at most 8
/// fractional digits, result in `1..=21M BTC`.
pub(crate) fn parse_btc_amount(s: &str) -> Result<u64, OnchainError> {
    let bad = || invalid_uri(format!("amount {s:?} is not a valid BTC amount"));
    let (int_part, frac_part) = s.split_once('.').unwrap_or((s, ""));
    if int_part.is_empty() && frac_part.is_empty() {
        return Err(bad());
    }
    if !int_part.bytes().all(|b| b.is_ascii_digit())
        || !frac_part.bytes().all(|b| b.is_ascii_digit())
        || frac_part.len() > 8
    {
        return Err(bad());
    }
    let int_trimmed = int_part.trim_start_matches('0');
    // 9+ significant integer digits is already > 21M BTC; bail before
    // the u64 parse so "000…" or huge strings can't overflow.
    if int_trimmed.len() > 8 {
        return Err(bad());
    }
    let int_val: u64 = if int_trimmed.is_empty() {
        0
    } else {
        int_trimmed.parse().map_err(|_| bad())?
    };
    let mut frac_val: u64 = 0;
    for i in 0..8 {
        let digit = frac_part
            .as_bytes()
            .get(i)
            .map_or(0, |b| u64::from(b - b'0'));
        frac_val = frac_val * 10 + digit;
    }
    let sat = int_val
        .checked_mul(100_000_000)
        .and_then(|v| v.checked_add(frac_val))
        .ok_or_else(bad)?;
    if sat == 0 || sat > MAX_MONEY_SAT {
        return Err(bad());
    }
    Ok(sat)
}

/// Strict RFC-3986 percent-decoder (`%xx` and `+`-as-space, matching
/// Java's `URLDecoder` used on the Kotlin side). Returns `None` on a
/// malformed escape or a non-UTF-8 result — the caller rejects the URI
/// rather than guessing.
fn decode_percent(input: &str) -> Option<String> {
    let mut out: Vec<u8> = Vec::with_capacity(input.len());
    let bytes = input.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        match bytes[i] {
            b'+' => {
                out.push(b' ');
                i += 1;
            }
            b'%' => {
                let h = hex_digit(*bytes.get(i + 1)?)?;
                let l = hex_digit(*bytes.get(i + 2)?)?;
                out.push((h << 4) | l);
                i += 3;
            }
            c => {
                out.push(c);
                i += 1;
            }
        }
    }
    String::from_utf8(out).ok()
}

fn hex_digit(b: u8) -> Option<u8> {
    match b {
        b'0'..=b'9' => Some(b - b'0'),
        b'a'..=b'f' => Some(10 + (b - b'a')),
        b'A'..=b'F' => Some(10 + (b - b'A')),
        _ => None,
    }
}

// -----------------------------------------------------------------------
// Endpoint vetting (SSRF guard) + transport
// -----------------------------------------------------------------------

/// How [`post_payjoin`] reaches the receiver.
#[derive(Debug, Clone)]
pub(crate) enum PayjoinRoute {
    /// Clearnet https; the socket is pinned to these pre-vetted addrs
    /// (ureq's resolver hook returns them verbatim, so a DNS answer
    /// that changes between the check and the connect is irrelevant).
    Direct {
        url: url::Url,
        addrs: Vec<SocketAddr>,
    },
    /// Through the user's SOCKS5 (Tor) proxy. Hostnames are resolved
    /// by the proxy, never locally.
    Proxied { url: url::Url, proxy: String },
}

/// Parse `endpoint` with the WHATWG parser and apply the scheme rules:
/// `https://` always; `http://` only for `.onion` hosts (BIP-78);
/// no userinfo.
fn parse_endpoint(endpoint: &str) -> Result<url::Url, OnchainError> {
    let url = url::Url::parse(endpoint)
        .map_err(|e| invalid_uri(format!("pj endpoint {endpoint:?}: {e}")))?;
    if !url.username().is_empty() || url.password().is_some() {
        return Err(invalid_uri("pj endpoint must not carry credentials"));
    }
    let onion = match url.host() {
        Some(url::Host::Domain(d)) => is_onion(d),
        Some(_) => false,
        None => return Err(invalid_uri(format!("pj endpoint {endpoint:?} has no host"))),
    };
    match url.scheme() {
        "https" => Ok(url),
        "http" if onion => Ok(url),
        "http" => Err(invalid_uri(
            "pj endpoint must use https:// (plain http:// is only allowed for .onion)",
        )),
        other => Err(invalid_uri(format!(
            "pj endpoint scheme {other:?} not supported"
        ))),
    }
}

fn is_onion(domain: &str) -> bool {
    // `url::Host::Domain` is already lowercased by the WHATWG parser.
    #[allow(clippy::case_sensitive_file_extension_comparisons)]
    domain
        .strip_suffix('.')
        .unwrap_or(domain)
        .ends_with(".onion")
}

fn is_local_name(domain: &str) -> bool {
    let d = domain.strip_suffix('.').unwrap_or(domain);
    d == "localhost"
        || d.ends_with(".localhost")
        || d == "localhost.localdomain"
        || d == "ip6-localhost"
        || d == "ip6-loopback"
}

/// SSRF guard + routing decision for a receiver endpoint.
///
/// - Host is taken from `url::Url::host()` (the same WHATWG parser ureq
///   uses), so `127.1`, `0x7f.1`, `2130706433`, `127.0.0.1\@evil` and
///   friends normalise to the IP ureq would actually connect to.
/// - Literal IPs: rejected when non-global (with or without proxy).
/// - `localhost`-style names: rejected.
/// - `.onion`: requires `proxy_url` (never resolved via system DNS).
/// - Other domains without a proxy: resolved here with `resolve`;
///   if ANY address is non-global the endpoint is refused, otherwise
///   the vetted addresses are pinned for the connection.
/// - Other domains with a proxy: DNS happens inside Tor; only the
///   literal-IP / localhost rules apply.
pub(crate) fn vet_endpoint<R>(
    endpoint: &str,
    proxy_url: Option<&str>,
    resolve: R,
) -> Result<PayjoinRoute, OnchainError>
where
    R: Fn(&str, u16) -> std::io::Result<Vec<SocketAddr>>,
{
    let url = parse_endpoint(endpoint)?;
    let port = url
        .port_or_known_default()
        .ok_or_else(|| invalid_uri("pj endpoint has no port"))?;
    let proxy = proxy_url
        .map(crate::tor::ureq_socks_proxy_url)
        .transpose()?;
    let blocked = |what: String| {
        OnchainError::InvalidAddress(format!(
            "pj endpoint {what} is a private/loopback/reserved address — blocked (SSRF guard)"
        ))
    };

    let literal: Option<IpAddr> = match url.host() {
        Some(url::Host::Ipv4(v4)) => Some(IpAddr::V4(v4)),
        Some(url::Host::Ipv6(v6)) => Some(IpAddr::V6(v6)),
        Some(url::Host::Domain(d)) => {
            if is_local_name(d) {
                return Err(blocked(format!("host {d:?}")));
            }
            if is_onion(d) && proxy.is_none() {
                return Err(OnchainError::InvalidAddress(
                    "pj endpoint is a .onion address — enable Tor to use it".into(),
                ));
            }
            None
        }
        None => return Err(invalid_uri("pj endpoint has no host")),
    };
    if let Some(ip) = literal {
        if is_non_global_ip(ip) {
            return Err(blocked(format!("host {ip}")));
        }
    }

    if let Some(proxy) = proxy {
        return Ok(PayjoinRoute::Proxied { url, proxy });
    }

    let addrs = if let Some(ip) = literal {
        vec![SocketAddr::new(ip, port)]
    } else {
        let host = url.host_str().unwrap_or_default();
        let addrs = resolve(host, port).map_err(|e| {
            OnchainError::Broadcast(format!("PayJoin transport: resolve {host}: {e}"))
        })?;
        if addrs.is_empty() {
            return Err(OnchainError::Broadcast(format!(
                "PayJoin transport: no address for {host}"
            )));
        }
        if let Some(bad) = addrs.iter().find(|a| is_non_global_ip(a.ip())) {
            return Err(blocked(format!("{host} (resolves to {})", bad.ip())));
        }
        addrs
    };
    Ok(PayjoinRoute::Direct { url, addrs })
}

/// System resolver used by production callers of [`vet_endpoint`].
pub(crate) fn system_resolve(host: &str, port: u16) -> std::io::Result<Vec<SocketAddr>> {
    (host, port).to_socket_addrs().map(Iterator::collect)
}

/// `true` when `ip` is not a globally routable unicast address.
/// IPv4-mapped / -compatible / NAT64 / 6to4 IPv6 forms are unwrapped
/// and re-checked as IPv4.
pub(crate) fn is_non_global_ip(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => is_non_global_v4(v4),
        IpAddr::V6(v6) => is_non_global_v6(v6),
    }
}

fn is_non_global_v4(v4: Ipv4Addr) -> bool {
    let o = v4.octets();
    o[0] == 0                                           // 0.0.0.0/8 "this network"
        || o[0] == 10                                   // 10/8 private
        || o[0] == 127                                  // 127/8 loopback
        || (o[0] == 100 && (o[1] & 0xc0) == 64)         // 100.64/10 CGNAT
        || (o[0] == 169 && o[1] == 254)                 // 169.254/16 link-local + metadata
        || (o[0] == 172 && (o[1] & 0xf0) == 16)         // 172.16/12 private
        || (o[0] == 192 && o[1] == 0 && o[2] == 0)      // 192.0.0/24 IETF
        || (o[0] == 192 && o[1] == 0 && o[2] == 2)      // TEST-NET-1
        || (o[0] == 192 && o[1] == 88 && o[2] == 99)    // 6to4 relay anycast
        || (o[0] == 192 && o[1] == 168)                 // 192.168/16 private
        || (o[0] == 198 && (o[1] & 0xfe) == 18)         // 198.18/15 benchmarking
        || (o[0] == 198 && o[1] == 51 && o[2] == 100)   // TEST-NET-2
        || (o[0] == 203 && o[1] == 0 && o[2] == 113)    // TEST-NET-3
        || o[0] >= 224 // multicast, 240/4, broadcast
}

fn is_non_global_v6(v6: Ipv6Addr) -> bool {
    if let Some(v4) = v6.to_ipv4_mapped() {
        return is_non_global_v4(v4); // ::ffff:a.b.c.d
    }
    let s = v6.segments();
    let embedded = |hi: u16, lo: u16| {
        Ipv4Addr::new(
            (hi >> 8) as u8,
            (hi & 0xff) as u8,
            (lo >> 8) as u8,
            (lo & 0xff) as u8,
        )
    };
    if s[..6].iter().all(|&x| x == 0) {
        return true; // ::, ::1 and deprecated IPv4-compatible ::a.b.c.d
    }
    if s[0] == 0x0064 && s[1] == 0xff9b {
        // 64:ff9b::/96 (and /48 local-use) NAT64 — re-check the embedded v4.
        return s[1..6] != [0xff9b, 0, 0, 0, 0] || is_non_global_v4(embedded(s[6], s[7]));
    }
    if s[0] == 0x2002 {
        return is_non_global_v4(embedded(s[1], s[2])); // 6to4
    }
    (s[0] & 0xff00) == 0xff00                           // ff00::/8 multicast
        || (s[0] & 0xfe00) == 0xfc00                    // fc00::/7 ULA
        || (s[0] & 0xffc0) == 0xfe80                    // fe80::/10 link-local
        || (s[0] & 0xffc0) == 0xfec0                    // fec0::/10 site-local
        || (s[0] == 0x2001 && s[1] == 0x0db8)           // 2001:db8::/32 documentation
        || (s[0] == 0x2001 && s[1] < 0x0200)            // 2001::/23 IETF special-purpose
        || (s[0] == 0x0100 && s[1..4] == [0, 0, 0]) // 100::/64 discard-only
}

/// Parameters the sender commits to in the BIP-78 request and that the
/// proposal is checked against.
#[derive(Debug, Clone)]
pub(crate) struct PayjoinParams {
    /// Index of the payment output in the original.
    pub(crate) payment_index: usize,
    /// Index of the sender's change output in the original — the only
    /// output the receiver may reduce (`additionalfeeoutputindex`).
    pub(crate) fee_output_index: Option<usize>,
    /// `maxadditionalfeecontribution` (sat). 0 when there is no change.
    pub(crate) max_additional_fee: u64,
    /// Fee rate the original was built at (sat/vB); also `minfeerate`.
    pub(crate) fee_rate_sat_vb: u64,
}

/// The receiver URL with the BIP-78 sender parameters appended to
/// whatever query the `pj` endpoint already carried.
pub(crate) fn request_url(base: &url::Url, params: &PayjoinParams) -> url::Url {
    let mut url = base.clone();
    {
        let mut q = url.query_pairs_mut();
        q.append_pair("v", "1");
        if let Some(idx) = params.fee_output_index {
            q.append_pair("additionalfeeoutputindex", &idx.to_string());
            q.append_pair(
                "maxadditionalfeecontribution",
                &params.max_additional_fee.to_string(),
            );
        }
        q.append_pair("minfeerate", &params.fee_rate_sat_vb.to_string());
        q.append_pair("disableoutputsubstitution", "true");
    }
    url
}

/// The two side effects of a negotiation that touch the outside world:
/// DNS resolution (input to [`vet_endpoint`]) and the HTTP exchange
/// with the receiver.
///
/// Production always uses [`PayjoinIo::PRODUCTION`]. The struct exists so
/// the negotiation logic can be driven end-to-end by an in-process
/// receiver in tests. It is `pub(crate)` and only consumed by a private
/// method on `OnchainAccount`; it is not part of the FFI surface, and
/// endpoint vetting ([`vet_endpoint`]) still runs on every call with the
/// injected resolver — only the socket itself is replaced.
pub(crate) struct PayjoinIo<'a> {
    pub(crate) resolve: &'a dyn Fn(&str, u16) -> std::io::Result<Vec<SocketAddr>>,
    pub(crate) post:
        &'a dyn Fn(&PayjoinRoute, &str, &PayjoinParams) -> Result<String, OnchainError>,
}

impl PayjoinIo<'static> {
    /// System DNS + the ureq/rustls transport of [`post_payjoin`].
    pub(crate) const PRODUCTION: Self = Self {
        resolve: &system_resolve,
        post: &post_payjoin,
    };
}

/// POST the signed original `psbt_b64` to the vetted `route` and
/// return the receiver's response body (the proposal PSBT, base64).
///
/// BIP-78 v1 over HTTP(S): `Content-Type: text/plain`, body is the
/// base64 string. Query parameters `v=1`, `minfeerate`,
/// `disableoutputsubstitution=true` and — when there is a change
/// output — `additionalfeeoutputindex` + `maxadditionalfeecontribution`
/// are appended to whatever query the receiver's `pj` URL carried.
///
/// Redirects are refused (`redirects(0)` + explicit 200 check): ureq
/// would otherwise follow up to 5 of them, bypassing the host vetting
/// and downgrading the POST to a GET.
///
/// Non-200 responses surface as [`OnchainError::Broadcast`] with the
/// body folded into the message — the BIP-78 spec encodes errors as
/// JSON `{"errorCode","message"}` but we expose them verbatim so the
/// upper layer can surface what the receiver actually sent.
pub(crate) fn post_payjoin(
    route: &PayjoinRoute,
    psbt_b64: &str,
    params: &PayjoinParams,
) -> Result<String, OnchainError> {
    let mut builder = ureq::AgentBuilder::new()
        .timeout(Duration::from_secs(30))
        .redirects(0);
    let base_url = match route {
        PayjoinRoute::Direct { url, addrs } => {
            let expected = format!(
                "{}:{}",
                url.host_str().unwrap_or_default(),
                url.port_or_known_default().unwrap_or(443)
            );
            let pinned = addrs.clone();
            builder = builder.https_only(true).resolver(move |netloc: &str| {
                if netloc == expected {
                    Ok(pinned.clone())
                } else {
                    Err(std::io::Error::new(
                        std::io::ErrorKind::PermissionDenied,
                        format!("unexpected host {netloc} (pinned {expected})"),
                    ))
                }
            });
            url
        }
        PayjoinRoute::Proxied { url, proxy } => {
            // Fresh random isolation token per PayJoin request: it must
            // not share a Tor circuit with any wallet sync or rate fetch.
            let proxy = crate::tor::ureq_socks_proxy_url_isolated(
                proxy,
                &crate::tor::new_isolation_token()?,
            )?;
            let proxy = ureq::Proxy::new(proxy)
                .map_err(|e| OnchainError::InvalidArgument(format!("PayJoin proxy URL: {e}")))?;
            let onion = matches!(url.host(), Some(url::Host::Domain(d)) if is_onion(d));
            builder = builder.https_only(!onion).proxy(proxy);
            url
        }
    };
    let url = request_url(base_url, params);

    let agent = builder.build();
    let response = agent
        .post(url.as_str())
        .set("Content-Type", "text/plain")
        .send_string(psbt_b64);
    match response {
        Ok(resp) if resp.status() == 200 => {
            let mut buf = String::new();
            resp.into_reader()
                .take(1_000_000) // 1 MB ceiling on receiver responses
                .read_to_string(&mut buf)
                .map_err(|e| OnchainError::Broadcast(format!("PayJoin read body: {e}")))?;
            Ok(buf.trim().to_string())
        }
        Ok(resp) => Err(OnchainError::Broadcast(format!(
            "PayJoin endpoint returned HTTP {} (redirects and non-200 answers are refused)",
            resp.status()
        ))),
        Err(ureq::Error::Status(code, resp)) => {
            let mut body = String::new();
            let _ = resp.into_reader().take(4_096).read_to_string(&mut body);
            Err(OnchainError::Broadcast(format!(
                "PayJoin endpoint returned HTTP {code}: {body}"
            )))
        }
        Err(e) => Err(OnchainError::Broadcast(format!("PayJoin transport: {e}"))),
    }
}

/// Parse a base64 PSBT, distinguishing `PayJoin` protocol errors
/// (likely a JSON body the receiver returned instead of a PSBT)
/// from the bitcoin-crate decode path. The prefix is also a
/// receiver-versus-malformed signal — receivers that returned an
/// error JSON typically start with `{`.
pub(crate) fn parse_psbt_response(body: &str) -> Result<Psbt, OnchainError> {
    if body.starts_with('{') {
        return Err(OnchainError::Broadcast(format!(
            "PayJoin endpoint returned a JSON error: {body}"
        )));
    }
    body.trim()
        .parse::<Psbt>()
        .map_err(|e| OnchainError::InvalidPsbt(format!("PayJoin proposal not a PSBT: {e}")))
}

// -----------------------------------------------------------------------
// Proposal validation (BIP-78 "Sender's checks")
// -----------------------------------------------------------------------

/// A proposal that passed [`validate_proposal`], ready for review.
#[derive(Debug, Clone)]
pub(crate) struct ValidatedProposal {
    /// The proposal with every sender input's PSBT metadata replaced by
    /// the original's (the receiver cannot inject anything into the
    /// inputs we sign).
    pub(crate) psbt: Psbt,
    /// Proposal input indices that spend the sender's original inputs.
    /// The ONLY indices the sign step may touch.
    pub(crate) sender_input_indices: Vec<usize>,
    pub(crate) original_fee: u64,
    pub(crate) proposal_fee: u64,
    /// How much the sender's change was reduced to pay extra fee.
    pub(crate) contribution: u64,
    pub(crate) receiver_input_count: usize,
    /// Estimated vsize once our inputs carry their 64-byte signatures.
    pub(crate) estimated_vsize: u64,
}

fn sum_sat<I: IntoIterator<Item = u64>>(values: I, what: &str) -> Result<u64, OnchainError> {
    values
        .into_iter()
        .try_fold(0u64, u64::checked_add)
        .ok_or_else(|| rejected(format!("{what} overflow")))
}

/// Absolute fee of a PSBT whose every input carries `witness_utxo`.
/// Errors (never saturates) when outputs exceed inputs.
pub(crate) fn psbt_fee_checked(psbt: &Psbt) -> Result<u64, OnchainError> {
    let ins = sum_sat(
        psbt.inputs
            .iter()
            .map(|i| i.witness_utxo.as_ref().map(|u| u.value.to_sat()))
            .collect::<Option<Vec<u64>>>()
            .ok_or_else(|| OnchainError::TxBuild("PSBT input missing witness_utxo".into()))?,
        "input sum",
    )?;
    let outs = sum_sat(
        psbt.unsigned_tx.output.iter().map(|o| o.value.to_sat()),
        "output sum",
    )?;
    ins.checked_sub(outs)
        .ok_or_else(|| OnchainError::TxBuild("PSBT outputs exceed inputs".into()))
}

/// Validate a receiver's proposal against the original PSBT, following
/// the BIP-78 "Sender's checks" list:
///
/// Transaction: `version` and `nLockTime` unchanged.
///
/// Inputs:
/// - no key paths (`bip32_derivation` / `tap_key_origins`) and no
///   partial / taproot signatures on any input;
/// - every original input present exactly once, same `nSequence`, not
///   finalized, `non_witness_utxo` / `witness_utxo` absent or equal
///   to ours, default sighash;
/// - every receiver input: `witness_utxo` present, finalized, P2TR
///   (no mixed input types), and **not owned by this wallet**
///   (`is_mine`) — otherwise our signer would sign it;
/// - all inputs share one `nSequence`.
///
/// Outputs (output substitution is disabled):
/// - no key paths on any output;
/// - every original output appears, same script, same relative order;
/// - the payment output and every sender output keep at least their
///   original value, except the fee output
///   (`additionalfeeoutputindex`), which may shrink by at most
///   `maxadditionalfeecontribution`;
/// - that contribution is fully absorbed by fee
///   (`contribution <= proposal_fee - original_fee`) and only pays for
///   receiver inputs (`<= fee_rate * vsize(P2TR) * added_inputs`);
/// - receiver-side gains (payment increase + new outputs) never exceed
///   the receiver's own input value.
///
/// Fee: arithmetic is checked (`outputs > inputs` or overflow →
/// error) and the estimated fee rate must be `>= minfeerate`.
#[allow(clippy::too_many_lines)] // one linear checklist, kept in BIP order
pub(crate) fn validate_proposal(
    original: &Psbt,
    proposal: &Psbt,
    params: &PayjoinParams,
    is_mine: &dyn Fn(&Script) -> bool,
) -> Result<ValidatedProposal, OnchainError> {
    let otx = &original.unsigned_tx;
    let ptx = &proposal.unsigned_tx;

    if ptx.version != otx.version {
        return Err(rejected("transaction version changed"));
    }
    if ptx.lock_time != otx.lock_time {
        return Err(rejected("nLockTime changed"));
    }

    // ---- inputs ----
    let original_by_outpoint: HashMap<OutPoint, usize> = otx
        .input
        .iter()
        .enumerate()
        .map(|(i, txin)| (txin.previous_output, i))
        .collect();
    let mut seen: HashSet<OutPoint> = HashSet::new();
    let mut sender_input_indices = Vec::new();
    let mut sender_in_total: u64 = 0;
    let mut receiver_in_total: u64 = 0;
    let mut receiver_input_count = 0usize;

    for (i, (txin, psbtin)) in ptx.input.iter().zip(proposal.inputs.iter()).enumerate() {
        if !seen.insert(txin.previous_output) {
            return Err(rejected(format!(
                "input {} spent twice",
                txin.previous_output
            )));
        }
        if !psbtin.bip32_derivation.is_empty() || !psbtin.tap_key_origins.is_empty() {
            return Err(rejected(format!("input #{i} carries key paths")));
        }
        if !psbtin.partial_sigs.is_empty()
            || psbtin.tap_key_sig.is_some()
            || !psbtin.tap_script_sigs.is_empty()
        {
            return Err(rejected(format!("input #{i} carries a partial signature")));
        }

        if let Some(&oi) = original_by_outpoint.get(&txin.previous_output) {
            // Sender input.
            if txin.sequence != otx.input[oi].sequence {
                return Err(rejected(format!("sender input #{i} nSequence changed")));
            }
            if psbtin.final_script_sig.is_some() || psbtin.final_script_witness.is_some() {
                return Err(rejected(format!("sender input #{i} is finalized")));
            }
            // BIP-78 says the receiver strips these; we tolerate an
            // untouched copy (it is overwritten from the original
            // below anyway) but never an altered one.
            if psbtin.non_witness_utxo.is_some()
                && psbtin.non_witness_utxo != original.inputs[oi].non_witness_utxo
            {
                return Err(rejected(format!(
                    "sender input #{i} non_witness_utxo altered"
                )));
            }
            let ours = original.inputs[oi]
                .witness_utxo
                .as_ref()
                .ok_or_else(|| rejected("original input missing witness_utxo"))?;
            if psbtin.witness_utxo.as_ref().is_some_and(|u| u != ours) {
                return Err(rejected(format!("sender input #{i} witness_utxo altered")));
            }
            if psbtin.sighash_type.is_some() {
                return Err(rejected(format!(
                    "sender input #{i} requests a sighash type"
                )));
            }
            sender_in_total = sender_in_total
                .checked_add(ours.value.to_sat())
                .ok_or_else(|| rejected("input sum overflow"))?;
            sender_input_indices.push(i);
        } else {
            // Receiver input.
            let utxo = psbtin
                .witness_utxo
                .as_ref()
                .ok_or_else(|| rejected(format!("receiver input #{i} missing witness_utxo")))?;
            if let Some(prev) = &psbtin.non_witness_utxo {
                let matches = prev.compute_txid() == txin.previous_output.txid
                    && prev.output.get(txin.previous_output.vout as usize) == Some(utxo);
                if !matches {
                    return Err(rejected(format!(
                        "receiver input #{i} utxo data inconsistent"
                    )));
                }
            }
            if psbtin
                .final_script_witness
                .as_ref()
                .is_none_or(Witness::is_empty)
            {
                return Err(rejected(format!("receiver input #{i} is not finalized")));
            }
            if psbtin
                .final_script_sig
                .as_ref()
                .is_some_and(|s| !s.is_empty())
            {
                return Err(rejected(format!("receiver input #{i} has a scriptSig")));
            }
            if !utxo.script_pubkey.is_p2tr() {
                return Err(rejected(format!(
                    "receiver input #{i} is not P2TR (mixed input types)"
                )));
            }
            if is_mine(&utxo.script_pubkey) {
                return Err(rejected(format!(
                    "receiver input #{i} spends a coin owned by this wallet"
                )));
            }
            receiver_in_total = receiver_in_total
                .checked_add(utxo.value.to_sat())
                .ok_or_else(|| rejected("input sum overflow"))?;
            receiver_input_count += 1;
        }
    }
    if sender_input_indices.len() != otx.input.len() {
        return Err(rejected("a sender input was dropped"));
    }
    let first_seq = ptx.input.first().map(|i| i.sequence);
    if ptx.input.iter().any(|i| Some(i.sequence) != first_seq) {
        return Err(rejected("inputs use mixed nSequence values"));
    }

    // ---- outputs ----
    let mut orig_ptr = 0usize;
    let mut contribution: u64 = 0;
    let mut receiver_gain: u64 = 0;
    for (j, (txout, psbtout)) in ptx.output.iter().zip(proposal.outputs.iter()).enumerate() {
        if !psbtout.bip32_derivation.is_empty() || !psbtout.tap_key_origins.is_empty() {
            return Err(rejected(format!("output #{j} carries key paths")));
        }
        let value = txout.value.to_sat();
        let matched = otx
            .output
            .get(orig_ptr)
            .is_some_and(|o| o.script_pubkey == txout.script_pubkey);
        if !matched {
            // Output added by the receiver (funded by its own inputs —
            // enforced by the gain check below).
            receiver_gain = receiver_gain
                .checked_add(value)
                .ok_or_else(|| rejected("output sum overflow"))?;
            continue;
        }
        let orig_value = otx.output[orig_ptr].value.to_sat();
        if orig_ptr == params.payment_index {
            let gain = value
                .checked_sub(orig_value)
                .ok_or_else(|| rejected("payment output decreased"))?;
            receiver_gain = receiver_gain
                .checked_add(gain)
                .ok_or_else(|| rejected("output sum overflow"))?;
        } else if Some(orig_ptr) == params.fee_output_index && value < orig_value {
            contribution = orig_value - value;
            if contribution > params.max_additional_fee {
                return Err(rejected(format!(
                    "change reduced by {contribution} sat, above \
                     maxadditionalfeecontribution {} sat",
                    params.max_additional_fee
                )));
            }
        } else if value < orig_value {
            return Err(rejected(format!("sender output #{orig_ptr} decreased")));
        }
        orig_ptr += 1;
    }
    if orig_ptr != otx.output.len() {
        return Err(rejected(
            "a sender output is missing, re-scripted or reordered",
        ));
    }

    // ---- fees ----
    let original_fee = psbt_fee_checked(original).map_err(rejected)?;
    let proposal_in_total = sender_in_total
        .checked_add(receiver_in_total)
        .ok_or_else(|| rejected("input sum overflow"))?;
    let proposal_out_total = sum_sat(ptx.output.iter().map(|o| o.value.to_sat()), "output sum")?;
    let proposal_fee = proposal_in_total
        .checked_sub(proposal_out_total)
        .ok_or_else(|| rejected("outputs exceed inputs"))?;

    if contribution > 0 {
        let fee_increase = proposal_fee.saturating_sub(original_fee);
        if contribution > fee_increase {
            return Err(rejected(format!(
                "change reduced by {contribution} sat but fee only grew by {fee_increase} sat"
            )));
        }
        let added_inputs = ptx.input.len().saturating_sub(otx.input.len()) as u64;
        let per_input_cap = params
            .fee_rate_sat_vb
            .checked_mul(P2TR_KEYPATH_INPUT_VSIZE)
            .and_then(|v| v.checked_mul(added_inputs))
            .ok_or_else(|| rejected("fee cap overflow"))?;
        if contribution > per_input_cap {
            return Err(rejected(format!(
                "change reduced by {contribution} sat, more than the {per_input_cap} sat \
                 the receiver's {added_inputs} extra input(s) cost"
            )));
        }
    }
    if receiver_gain > receiver_in_total {
        return Err(rejected(format!(
            "receiver gains {receiver_gain} sat but contributed only {receiver_in_total} sat"
        )));
    }

    // Restore our inputs from the original: the receiver stripped (or
    // could have tampered with) their metadata.
    let mut restored = proposal.clone();
    for &i in &sender_input_indices {
        let oi = original_by_outpoint[&ptx.input[i].previous_output];
        restored.inputs[i] = original.inputs[oi].clone();
    }

    let estimated_vsize = estimated_signed_vsize(&restored, &sender_input_indices);
    let min_fee = params
        .fee_rate_sat_vb
        .checked_mul(estimated_vsize)
        .ok_or_else(|| rejected("fee rate overflow"))?;
    if proposal_fee < min_fee {
        return Err(rejected(format!(
            "fee {proposal_fee} sat for ~{estimated_vsize} vB is below minfeerate {} sat/vB",
            params.fee_rate_sat_vb
        )));
    }

    Ok(ValidatedProposal {
        psbt: restored,
        sender_input_indices,
        original_fee,
        proposal_fee,
        contribution,
        receiver_input_count,
        estimated_vsize,
    })
}

/// vsize of `psbt` once our inputs (`sender_input_indices`) carry a
/// 64-byte key-path signature; other inputs use their final witness.
fn estimated_signed_vsize(psbt: &Psbt, sender_input_indices: &[usize]) -> u64 {
    let mut tx = psbt.unsigned_tx.clone();
    for (i, txin) in tx.input.iter_mut().enumerate() {
        txin.witness = if sender_input_indices.contains(&i) {
            Witness::from_slice(&[[0u8; 64]])
        } else {
            psbt.inputs[i]
                .final_script_witness
                .clone()
                .unwrap_or_default()
        };
        if let Some(sig) = &psbt.inputs[i].final_script_sig {
            txin.script_sig = sig.clone();
        }
    }
    tx.vsize() as u64
}

// -----------------------------------------------------------------------
// Review summary + session store
// -----------------------------------------------------------------------

/// One output as shown on the review screen.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PayjoinOutputSummary {
    /// Address string, or `script:<hex>` for a non-standard script.
    pub address: String,
    pub amount_sat: u64,
    /// Pays back to this wallet (change).
    pub is_mine: bool,
    /// The payment output requested by the URI.
    pub is_payment: bool,
}

/// Result of `OnchainAccount::payjoin_negotiate`.
///
/// When `session_id` is `Some`, the receiver's proposal passed every
/// sender check and is parked in Rust; `outputs` / fees describe the
/// proposal. When it is `None`, `failure_reason` explains why and the
/// fields describe the original transaction — `original_psbt` (already
/// signed + finalized) can be broadcast as a regular payment.
#[derive(Debug, Clone)]
pub struct PayjoinNegotiation {
    pub session_id: Option<String>,
    pub failure_reason: Option<String>,
    /// Signed + finalized original (fallback). Per BIP-78 the receiver
    /// holds this too and may broadcast it itself.
    pub original_psbt: String,
    pub original_fee_sat: u64,
    pub proposal_fee_sat: u64,
    /// Sat taken from our change to pay for the receiver's inputs.
    pub sender_fee_contribution_sat: u64,
    pub vsize: u64,
    pub receiver_input_count: u32,
    /// What leaves this wallet in total (payment + our fee share).
    pub sender_net_spend_sat: u64,
    pub outputs: Vec<PayjoinOutputSummary>,
}

/// Build the per-output review rows for `psbt`.
pub(crate) fn summarize_outputs(
    psbt: &Psbt,
    payment_spk: &Script,
    network: bitcoin::Network,
    is_mine: &dyn Fn(&Script) -> bool,
) -> Vec<PayjoinOutputSummary> {
    let mut payment_seen = false;
    psbt.unsigned_tx
        .output
        .iter()
        .map(|o| {
            let is_payment = !payment_seen && o.script_pubkey.as_script() == payment_spk;
            payment_seen |= is_payment;
            PayjoinOutputSummary {
                address: Address::from_script(&o.script_pubkey, network).map_or_else(
                    |_| format!("script:{}", o.script_pubkey.to_hex_string()),
                    |a| a.to_string(),
                ),
                amount_sat: o.value.to_sat(),
                is_mine: is_mine(&o.script_pubkey),
                is_payment,
            }
        })
        .collect()
}

/// Sum of `inputs` minus outputs paying back to us (not the payment).
pub(crate) fn sender_net_spend(sender_inputs: u64, outputs: &[PayjoinOutputSummary]) -> u64 {
    let back: u64 = outputs
        .iter()
        .filter(|o| o.is_mine && !o.is_payment)
        .fold(0u64, |acc, o| acc.saturating_add(o.amount_sat));
    sender_inputs.saturating_sub(back)
}

/// A validated proposal waiting for the user's confirmation.
#[derive(Debug, Clone)]
pub(crate) struct PayjoinSession {
    /// Opaque owner tag (the wallet's public external descriptor) so a
    /// session can only be signed by the wallet that negotiated it.
    pub(crate) owner: String,
    pub(crate) proposal: ValidatedProposal,
    pub(crate) fee_rate_sat_vb: u64,
}

static SESSIONS: Mutex<Vec<(String, PayjoinSession)>> = Mutex::new(Vec::new());

/// Park `session` and return its random id.
pub(crate) fn store_session(session: PayjoinSession) -> Result<String, OnchainError> {
    use bitcoin::hex::DisplayHex;
    let mut id = [0u8; 16];
    rand::TryRngCore::try_fill_bytes(&mut rand::rngs::OsRng, &mut id)
        .map_err(|e| OnchainError::Rng(e.to_string()))?;
    let id = id.to_lower_hex_string();
    let mut sessions = SESSIONS
        .lock()
        .map_err(|_| OnchainError::Storage("PayJoin session store poisoned".into()))?;
    while sessions.len() >= MAX_PENDING_SESSIONS {
        sessions.remove(0);
    }
    sessions.push((id.clone(), session));
    Ok(id)
}

/// Remove and return the session `id` if it belongs to `owner`.
pub(crate) fn take_session(id: &str, owner: &str) -> Option<PayjoinSession> {
    let mut sessions = SESSIONS.lock().ok()?;
    let pos = sessions
        .iter()
        .position(|(sid, s)| sid == id && s.owner == owner)?;
    Some(sessions.remove(pos).1)
}

/// Drop session `id` (user cancelled). Returns whether it existed.
pub(crate) fn discard_session(id: &str) -> bool {
    let Ok(mut sessions) = SESSIONS.lock() else {
        return false;
    };
    let before = sessions.len();
    sessions.retain(|(sid, _)| sid != id);
    sessions.len() != before
}

// -----------------------------------------------------------------------
// Endpoint vetting / SSRF unit tests
// -----------------------------------------------------------------------
#[cfg(test)]
mod ssrf_tests {
    use super::*;

    fn no_dns(_: &str, _: u16) -> std::io::Result<Vec<SocketAddr>> {
        panic!("DNS must not be consulted on this path");
    }

    #[allow(
        clippy::unnecessary_wraps,
        reason = "must match the resolver signature `vet_endpoint` takes"
    )]
    fn public_dns(_: &str, port: u16) -> std::io::Result<Vec<SocketAddr>> {
        Ok(vec![SocketAddr::new(
            IpAddr::V4(Ipv4Addr::new(93, 184, 216, 34)),
            port,
        )])
    }

    fn assert_blocked(endpoint: &str) {
        let r = vet_endpoint(endpoint, None, public_dns);
        assert!(
            matches!(r, Err(OnchainError::InvalidAddress(_))),
            "{endpoint} must be blocked, got {r:?}"
        );
    }

    #[test]
    fn allows_normal_https_host_and_pins_resolved_addr() {
        match vet_endpoint("https://payjoin.example.com/v1", None, public_dns).unwrap() {
            PayjoinRoute::Direct { url, addrs } => {
                assert_eq!(url.host_str(), Some("payjoin.example.com"));
                assert_eq!(addrs, public_dns("", 443).unwrap());
            }
            other @ PayjoinRoute::Proxied { .. } => panic!("expected Direct, got {other:?}"),
        }
    }

    #[test]
    fn rejects_plain_http_clearnet() {
        assert!(vet_endpoint("http://payjoin.example.com/v1", None, public_dns).is_err());
        assert!(
            vet_endpoint(
                "http://payjoin.example.com/v1",
                Some("socks5h://127.0.0.1:9050"),
                no_dns
            )
            .is_err()
        );
    }

    #[test]
    fn onion_requires_proxy_and_skips_local_dns() {
        assert!(vet_endpoint("http://xxxxx.onion/pj", None, no_dns).is_err());
        match vet_endpoint(
            "http://xxxxx.onion/pj",
            Some("socks5h://127.0.0.1:9050"),
            no_dns,
        )
        .unwrap()
        {
            PayjoinRoute::Proxied { proxy, .. } => assert_eq!(proxy, "socks5://127.0.0.1:9050"),
            other @ PayjoinRoute::Direct { .. } => panic!("expected Proxied, got {other:?}"),
        }
    }

    #[test]
    fn proxied_clearnet_domain_resolves_remotely() {
        let r = vet_endpoint("https://pj.example.com/", Some("127.0.0.1:9050"), no_dns).unwrap();
        assert!(matches!(r, PayjoinRoute::Proxied { .. }));
    }

    #[test]
    fn rejects_non_socks_proxy() {
        assert!(
            vet_endpoint("https://pj.example.com/", Some("http://proxy:8080"), no_dns).is_err()
        );
    }

    #[test]
    fn blocks_localhost_names() {
        assert_blocked("https://localhost/pj");
        assert_blocked("https://LOCALHOST./pj");
        assert_blocked("https://api.localhost/pj");
    }

    #[test]
    fn blocks_classic_private_ranges() {
        for e in [
            "https://127.0.0.1/pj",
            "https://10.0.0.1/pj",
            "https://172.16.0.1/pj",
            "https://192.168.1.1/pj",
            "https://169.254.169.254/latest/meta-data",
            "https://[::1]/pj",
        ] {
            assert_blocked(e);
        }
    }

    // H11 bypasses of the old hand-rolled host extractor.
    #[test]
    fn blocks_backslash_userinfo_confusion() {
        // WHATWG treats `\` as `/` for special schemes: host is 127.0.0.1.
        assert_blocked("https://127.0.0.1\\@example.com/");
        assert!(vet_endpoint("http://127.0.0.1\\@example.com/", None, public_dns).is_err());
    }

    #[test]
    fn blocks_numeric_ipv4_forms() {
        assert_blocked("https://2130706433/");
        assert_blocked("https://0x7f.1/");
        assert_blocked("https://127.1/");
        assert_blocked("https://0177.0.0.1/");
    }

    #[test]
    fn blocks_ipv4_mapped_and_special_ipv6() {
        assert_blocked("https://[::ffff:127.0.0.1]/");
        assert_blocked("https://[::ffff:a9fe:a9fe]/");
        assert_blocked("https://[fc00::1]/");
        assert_blocked("https://[fd12:3456::1]/");
        assert_blocked("https://[fe80::1]/");
        assert_blocked("https://[fec0::1]/");
        assert_blocked("https://[::]/");
        assert_blocked("https://[64:ff9b::7f00:1]/");
        assert_blocked("https://[2002:7f00:1::]/");
        assert_blocked("https://[2001:db8::1]/");
    }

    #[test]
    fn blocks_cgnat_this_network_and_multicast() {
        assert_blocked("https://100.64.0.1/");
        assert_blocked("https://100.127.255.254/");
        assert_blocked("https://0.0.0.0/");
        assert_blocked("https://0.1.2.3/");
        assert_blocked("https://224.0.0.1/");
        assert_blocked("https://255.255.255.255/");
    }

    #[test]
    fn literal_private_ip_blocked_even_through_proxy() {
        let r = vet_endpoint(
            "https://10.0.0.1/",
            Some("socks5h://127.0.0.1:9050"),
            no_dns,
        );
        assert!(matches!(r, Err(OnchainError::InvalidAddress(_))));
    }

    #[test]
    fn blocks_domain_resolving_to_any_private_ip() {
        let mixed = |_: &str, port: u16| {
            Ok(vec![
                SocketAddr::new(IpAddr::V4(Ipv4Addr::new(93, 184, 216, 34)), port),
                SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 5)), port),
            ])
        };
        let r = vet_endpoint("https://rebind.example.com/", None, mixed);
        assert!(matches!(r, Err(OnchainError::InvalidAddress(_))), "{r:?}");
    }

    #[test]
    fn rejects_credentials_in_endpoint() {
        assert!(vet_endpoint("https://user:pw@pj.example.com/", None, public_dns).is_err());
    }

    #[test]
    fn allows_public_ip_literals() {
        assert!(vet_endpoint("https://[2606:4700:4700::1111]/pj", None, no_dns).is_ok());
        assert!(vet_endpoint("https://1.1.1.1/pj", None, no_dns).is_ok());
        assert!(vet_endpoint("https://100.128.0.1/pj", None, no_dns).is_ok());
    }
}

// -----------------------------------------------------------------------
// BIP-21 parser unit tests
// -----------------------------------------------------------------------
#[cfg(test)]
mod tests {
    use super::*;

    // BIP-86 mainnet test vector first address. The parser is
    // network-agnostic (returns `NetworkUnchecked`); we just need a
    // string that round-trips through `Address::<...>::parse`.
    const SIGNET_ADDR: &str = "bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr";

    #[test]
    fn parses_full_uri() {
        let uri = format!("bitcoin:{SIGNET_ADDR}?amount=0.001&pj=https://payjoin.example.com/btc");
        let parsed = PayjoinUri::parse(&uri).unwrap();
        assert_eq!(parsed.amount.to_sat(), 100_000);
        assert_eq!(parsed.endpoint, "https://payjoin.example.com/btc");
    }

    #[test]
    fn accepts_uppercase_scheme() {
        let uri = format!("BITCOIN:{SIGNET_ADDR}?amount=0.001&pj=https://x.example/y");
        assert_eq!(PayjoinUri::parse(&uri).unwrap().amount.to_sat(), 100_000);
    }

    #[test]
    fn rejects_uri_without_amount() {
        let uri = format!("bitcoin:{SIGNET_ADDR}?pj=https://x.example/y");
        assert!(matches!(
            PayjoinUri::parse(&uri),
            Err(OnchainError::InvalidAddress(_))
        ));
    }

    #[test]
    fn rejects_uri_without_pj() {
        let uri = format!("bitcoin:{SIGNET_ADDR}?amount=0.001");
        assert!(matches!(
            PayjoinUri::parse(&uri),
            Err(OnchainError::InvalidAddress(_))
        ));
    }

    #[test]
    fn rejects_non_http_endpoint() {
        let uri = format!("bitcoin:{SIGNET_ADDR}?amount=0.001&pj=ftp://x.example");
        assert!(matches!(
            PayjoinUri::parse(&uri),
            Err(OnchainError::InvalidAddress(_))
        ));
    }

    #[test]
    fn rejects_plain_http_clearnet_endpoint_but_accepts_onion() {
        let clear = format!("bitcoin:{SIGNET_ADDR}?amount=0.001&pj=http://x.example/y");
        assert!(PayjoinUri::parse(&clear).is_err());
        let onion = format!("bitcoin:{SIGNET_ADDR}?amount=0.001&pj=http://abc.onion/y");
        assert!(PayjoinUri::parse(&onion).is_ok());
    }

    #[test]
    fn rejects_zero_amount() {
        let uri = format!("bitcoin:{SIGNET_ADDR}?amount=0&pj=https://x.example");
        assert!(matches!(
            PayjoinUri::parse(&uri),
            Err(OnchainError::InvalidAddress(_))
        ));
    }

    // C3: `?amount=1&AMOUNT=0.00001` showed 1000 sat in the UI while
    // Rust paid 1 BTC. Duplicates (any case) now reject the URI.
    #[test]
    fn rejects_duplicate_keys_case_insensitively() {
        for q in [
            "amount=1&AMOUNT=0.00001",
            "amount=1&amount=1",
            "Amount=1&aMoUnT=1",
            "%61mount=1&amount=1",
            "amount=1&pj=https://a.example&PJ=https://b.example",
        ] {
            let uri = format!("bitcoin:{SIGNET_ADDR}?{q}&pj=https://x.example");
            assert!(PayjoinUri::parse(&uri).is_err(), "{q} must be rejected");
        }
    }

    #[test]
    fn rejects_unknown_required_params() {
        let uri = format!("bitcoin:{SIGNET_ADDR}?amount=1&req-foo=1&pj=https://x.example");
        assert!(PayjoinUri::parse(&uri).is_err());
        let uri = format!("bitcoin:{SIGNET_ADDR}?amount=1&REQ-bar=1&pj=https://x.example");
        assert!(PayjoinUri::parse(&uri).is_err());
    }

    #[test]
    fn amount_is_exact_decimal() {
        assert_eq!(parse_btc_amount("1").unwrap(), 100_000_000);
        assert_eq!(parse_btc_amount("0.00000001").unwrap(), 1);
        assert_eq!(parse_btc_amount(".5").unwrap(), 50_000_000);
        assert_eq!(parse_btc_amount("5.").unwrap(), 500_000_000);
        assert_eq!(parse_btc_amount("21000000").unwrap(), MAX_MONEY_SAT);
        assert_eq!(parse_btc_amount("000000000000001").unwrap(), 100_000_000);
        for bad in [
            "",
            ".",
            "1e-5",
            "1E2",
            "+1",
            "-1",
            "0x10",
            "1.000000001",
            "21000000.00000001",
            "99999999999999999999",
            "1,5",
            " 1",
            "NaN",
            "inf",
            "0",
            "0.0",
        ] {
            assert!(parse_btc_amount(bad).is_err(), "{bad:?} must be rejected");
        }
    }

    #[test]
    fn rejects_malformed_percent_encoding() {
        let uri = format!("bitcoin:{SIGNET_ADDR}?amount=1%zz&pj=https://x.example");
        assert!(PayjoinUri::parse(&uri).is_err());
        let uri = format!("bitcoin:{SIGNET_ADDR}?amount=1&label=%&pj=https://x.example");
        assert!(PayjoinUri::parse(&uri).is_err());
    }

    #[test]
    fn percent_decodes_pj() {
        let uri = format!(
            "bitcoin:{SIGNET_ADDR}?amount=0.001&label=Coffee%20with%20Alice\
             &pj=https%3A%2F%2Fx.example%2Fp%3Fid%3D1"
        );
        let parsed = PayjoinUri::parse(&uri).unwrap();
        assert_eq!(parsed.endpoint, "https://x.example/p?id=1");
    }

    #[test]
    fn request_url_appends_sender_params_to_existing_query() {
        let base = url::Url::parse("https://pj.example.com/x?foo=bar").unwrap();
        let with_change = PayjoinParams {
            payment_index: 0,
            fee_output_index: Some(1),
            max_additional_fee: 116,
            fee_rate_sat_vb: 2,
        };
        let q: Vec<(String, String)> = request_url(&base, &with_change)
            .query_pairs()
            .map(|(k, v)| (k.into_owned(), v.into_owned()))
            .collect();
        let expect = [
            ("foo", "bar"),
            ("v", "1"),
            ("additionalfeeoutputindex", "1"),
            ("maxadditionalfeecontribution", "116"),
            ("minfeerate", "2"),
            ("disableoutputsubstitution", "true"),
        ];
        assert_eq!(q.len(), expect.len());
        for ((k, v), (ek, ev)) in q.iter().zip(expect) {
            assert_eq!((k.as_str(), v.as_str()), (ek, ev));
        }
        let no_change = PayjoinParams {
            fee_output_index: None,
            max_additional_fee: 0,
            ..with_change
        };
        let url = request_url(&base, &no_change);
        assert!(!url.query().unwrap().contains("additionalfeeoutputindex"));
        assert!(
            !url.query()
                .unwrap()
                .contains("maxadditionalfeecontribution")
        );
    }

    #[test]
    fn parse_psbt_response_rejects_json_error_body() {
        let json = r#"{"errorCode":"original-psbt-rejected","message":"bad input"}"#;
        let err = parse_psbt_response(json).unwrap_err();
        assert!(matches!(err, OnchainError::Broadcast(_)));
    }
}

// -----------------------------------------------------------------------
// Adversarial proposal tests (BIP-78 sender checks)
// -----------------------------------------------------------------------
#[cfg(test)]
mod proposal_tests {
    use super::*;
    use bitcoin::{
        ScriptBuf, Sequence, Transaction, TxIn, TxOut, absolute::LockTime, hashes::Hash,
        transaction::Version,
    };

    fn p2tr(tag: u8) -> ScriptBuf {
        // OP_1 <32 bytes> — shape-valid P2TR script for these tests.
        let mut bytes = vec![0x51, 0x20];
        bytes.extend([tag; 32]);
        ScriptBuf::from_bytes(bytes)
    }

    fn outpoint(tag: u8) -> OutPoint {
        OutPoint::new(bitcoin::Txid::from_byte_array([tag; 32]), 0)
    }

    const SEQ: Sequence = Sequence::ENABLE_RBF_NO_LOCKTIME;
    const SENDER_IN: u64 = 100_000;
    const PAYMENT: u64 = 50_000;
    const CHANGE: u64 = 49_000;
    const RECV_IN: u64 = 30_000;

    fn sender_spk() -> ScriptBuf {
        p2tr(1)
    }
    fn change_spk() -> ScriptBuf {
        p2tr(2)
    }
    fn sender_other_spk() -> ScriptBuf {
        p2tr(3)
    }
    fn payment_spk() -> ScriptBuf {
        p2tr(9)
    }
    fn receiver_in_spk() -> ScriptBuf {
        p2tr(8)
    }
    fn attacker_spk() -> ScriptBuf {
        p2tr(7)
    }

    fn is_mine(s: &Script) -> bool {
        s == sender_spk().as_script()
            || s == change_spk().as_script()
            || s == sender_other_spk().as_script()
    }

    fn txin(op: OutPoint) -> TxIn {
        TxIn {
            previous_output: op,
            sequence: SEQ,
            ..TxIn::default()
        }
    }

    fn txout(spk: ScriptBuf, v: u64) -> TxOut {
        TxOut {
            value: Amount::from_sat(v),
            script_pubkey: spk,
        }
    }

    fn original() -> Psbt {
        let tx = Transaction {
            version: Version::TWO,
            lock_time: LockTime::from_height(100).unwrap(),
            input: vec![txin(outpoint(1))],
            output: vec![txout(payment_spk(), PAYMENT), txout(change_spk(), CHANGE)],
        };
        let mut psbt = Psbt::from_unsigned_tx(tx).unwrap();
        psbt.inputs[0].witness_utxo = Some(txout(sender_spk(), SENDER_IN));
        psbt
    }

    fn params() -> PayjoinParams {
        PayjoinParams {
            payment_index: 0,
            fee_output_index: Some(1),
            max_additional_fee: 2 * P2TR_KEYPATH_INPUT_VSIZE,
            fee_rate_sat_vb: 2,
        }
    }

    /// Benign proposal: receiver adds one P2TR input, folds it into the
    /// payment output, takes 100 sat of our change for its input fee.
    fn proposal_with(
        mut edit_tx: impl FnMut(&mut Transaction),
        mut edit_psbt: impl FnMut(&mut Psbt),
    ) -> Psbt {
        let mut tx = Transaction {
            version: Version::TWO,
            lock_time: LockTime::from_height(100).unwrap(),
            input: vec![txin(outpoint(1)), txin(outpoint(2))],
            output: vec![
                txout(payment_spk(), PAYMENT + RECV_IN),
                txout(change_spk(), CHANGE - 100),
            ],
        };
        edit_tx(&mut tx);
        let mut psbt = Psbt::from_unsigned_tx(tx).unwrap();
        for (i, txin) in psbt.unsigned_tx.input.clone().iter().enumerate() {
            if txin.previous_output != outpoint(1) {
                psbt.inputs[i].witness_utxo = Some(txout(receiver_in_spk(), RECV_IN));
                psbt.inputs[i].final_script_witness = Some(Witness::from_slice(&[[7u8; 64]]));
            }
        }
        edit_psbt(&mut psbt);
        psbt
    }

    fn check(proposal: &Psbt) -> Result<ValidatedProposal, OnchainError> {
        validate_proposal(&original(), proposal, &params(), &is_mine)
    }

    fn assert_rejected(proposal: &Psbt, needle: &str) {
        match check(proposal) {
            Err(OnchainError::TxBuild(msg)) => {
                assert!(msg.contains(needle), "expected {needle:?} in {msg:?}");
            }
            other => panic!("expected rejection containing {needle:?}, got {other:?}"),
        }
    }

    #[test]
    fn benign_proposal_passes_and_restores_sender_input() {
        let v = check(&proposal_with(|_| {}, |_| {})).unwrap();
        assert_eq!(v.sender_input_indices, vec![0]);
        assert_eq!(v.contribution, 100);
        assert_eq!(v.original_fee, 1_000);
        assert_eq!(v.proposal_fee, 1_100);
        assert_eq!(v.receiver_input_count, 1);
        assert_eq!(v.psbt.inputs[0], original().inputs[0]);
    }

    #[test]
    fn rejects_change_script_swapped() {
        let p = proposal_with(|tx| tx.output[1].script_pubkey = attacker_spk(), |_| {});
        assert_rejected(&p, "sender output is missing");
    }

    #[test]
    fn rejects_change_absorbed_into_receiver_output() {
        let p = proposal_with(
            |tx| {
                tx.output[0].value = Amount::from_sat(PAYMENT + RECV_IN + CHANGE - 100);
                tx.output.truncate(1);
            },
            |_| {},
        );
        assert!(check(&p).is_err());
        // Same theft keeping a dust-sized change output.
        let p = proposal_with(
            |tx| {
                tx.output[0].value = Amount::from_sat(PAYMENT + RECV_IN + CHANGE - 1_000);
                tx.output[1].value = Amount::from_sat(900);
            },
            |_| {},
        );
        assert_rejected(&p, "maxadditionalfeecontribution");
    }

    #[test]
    fn rejects_change_split_to_attacker_output() {
        let p = proposal_with(
            |tx| {
                tx.output[1].value = Amount::from_sat(CHANGE / 2);
                tx.output.push(txout(attacker_spk(), CHANGE / 2 - 100));
            },
            |_| {},
        );
        assert_rejected(&p, "maxadditionalfeecontribution");
    }

    #[test]
    fn rejects_new_output_not_funded_by_receiver() {
        // Change untouched, but an attacker output appears that the
        // receiver's input cannot cover → sender pays for it.
        let p = proposal_with(
            |tx| {
                tx.output[1].value = Amount::from_sat(CHANGE);
                tx.output.push(txout(attacker_spk(), RECV_IN));
                tx.output[0].value = Amount::from_sat(PAYMENT + RECV_IN);
            },
            |_| {},
        );
        assert!(check(&p).is_err());
    }

    #[test]
    fn rejects_receiver_adding_sender_owned_input() {
        let p = proposal_with(
            |_| {},
            |psbt| psbt.inputs[1].witness_utxo = Some(txout(sender_other_spk(), RECV_IN)),
        );
        assert_rejected(&p, "owned by this wallet");
    }

    #[test]
    fn rejects_unfinalized_or_non_taproot_receiver_input() {
        let p = proposal_with(|_| {}, |psbt| psbt.inputs[1].final_script_witness = None);
        assert_rejected(&p, "not finalized");
        let p2wpkh = ScriptBuf::from_bytes([&[0x00, 0x14][..], &[5u8; 20]].concat());
        let p = proposal_with(
            |_| {},
            |psbt| psbt.inputs[1].witness_utxo = Some(txout(p2wpkh.clone(), RECV_IN)),
        );
        assert_rejected(&p, "not P2TR");
    }

    #[test]
    fn rejects_sequence_change() {
        let p = proposal_with(
            |tx| {
                tx.input[0].sequence = Sequence::MAX;
                tx.input[1].sequence = Sequence::MAX;
            },
            |_| {},
        );
        assert_rejected(&p, "nSequence changed");
        let p = proposal_with(|tx| tx.input[1].sequence = Sequence::MAX, |_| {});
        assert_rejected(&p, "mixed nSequence");
    }

    #[test]
    fn rejects_locktime_or_version_change() {
        let p = proposal_with(
            |tx| tx.lock_time = LockTime::from_height(101).unwrap(),
            |_| {},
        );
        assert_rejected(&p, "nLockTime changed");
        let p = proposal_with(|tx| tx.version = Version::ONE, |_| {});
        assert_rejected(&p, "version changed");
    }

    #[test]
    fn rejects_fee_above_max_additional_contribution() {
        let p = proposal_with(
            |tx| tx.output[1].value = Amount::from_sat(CHANGE - 500),
            |_| {},
        );
        assert_rejected(&p, "maxadditionalfeecontribution");
    }

    #[test]
    fn rejects_contribution_not_absorbed_by_fee() {
        // Change shrinks by 100 but the receiver pockets it.
        let p = proposal_with(
            |tx| tx.output[0].value = Amount::from_sat(PAYMENT + RECV_IN + 100),
            |_| {},
        );
        assert!(check(&p).is_err());
    }

    #[test]
    fn rejects_fee_rate_below_minfeerate() {
        let mut params = params();
        params.fee_rate_sat_vb = 50;
        params.max_additional_fee = 50 * P2TR_KEYPATH_INPUT_VSIZE;
        let p = proposal_with(|_| {}, |_| {});
        let r = validate_proposal(&original(), &p, &params, &is_mine);
        assert!(
            matches!(r, Err(OnchainError::TxBuild(ref m)) if m.contains("minfeerate")),
            "{r:?}"
        );
    }

    #[test]
    fn rejects_outputs_exceeding_inputs_without_panicking() {
        let p = proposal_with(
            |tx| tx.output[0].value = Amount::from_sat(10_000_000),
            |_| {},
        );
        assert!(check(&p).is_err());
        let p = proposal_with(
            |_| {},
            |psbt| psbt.inputs[1].witness_utxo = Some(txout(receiver_in_spk(), u64::MAX)),
        );
        assert!(check(&p).is_err());
    }

    #[test]
    fn rejects_payment_decrease_and_dropped_input() {
        let p = proposal_with(
            |tx| tx.output[0].value = Amount::from_sat(PAYMENT - 1),
            |_| {},
        );
        assert_rejected(&p, "payment output decreased");
        let p = proposal_with(
            |tx| {
                tx.input.remove(0);
            },
            |_| {},
        );
        assert_rejected(&p, "dropped");
    }

    #[test]
    fn rejects_tampered_sender_input_metadata() {
        let p = proposal_with(
            |_| {},
            |psbt| psbt.inputs[0].witness_utxo = Some(txout(sender_spk(), SENDER_IN + 1)),
        );
        assert_rejected(&p, "witness_utxo altered");
        let p = proposal_with(
            |_| {},
            |psbt| psbt.inputs[0].final_script_witness = Some(Witness::from_slice(&[[1u8; 64]])),
        );
        assert_rejected(&p, "finalized");
        let p = proposal_with(
            |_| {},
            |psbt| {
                psbt.inputs[0].sighash_type =
                    Some(bitcoin::TapSighashType::SinglePlusAnyoneCanPay.into());
            },
        );
        assert_rejected(&p, "sighash");
    }
}

// -----------------------------------------------------------------------
// Property-based tests (proptest). Generative coverage over the BIP-21
// PayJoin URI parser and the percent-decoder; the specific seeds that
// trip a regression are printed by proptest's shrinking output.
// -----------------------------------------------------------------------
#[cfg(test)]
mod proptests {
    use super::*;
    use proptest::prelude::*;

    const VALID_ADDR: &str = "bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr";

    /// Strategy: random label / message contents that avoid the BIP-21
    /// reserved chars (`?`, `&`, `=`, `#`). We don't try to test
    /// percent-encoding here — that's handled by the dedicated decoder
    /// proptests below.
    fn safe_query_value() -> impl Strategy<Value = String> {
        // Printable ASCII without BIP-21 separators.
        proptest::collection::vec(
            proptest::char::range(' ', '~').prop_filter("BIP-21 separators", |c| {
                !matches!(*c, '?' | '&' | '=' | '#' | '%' | '+')
            }),
            0..32,
        )
        .prop_map(|chars| chars.into_iter().collect::<String>())
    }

    proptest! {
        #![proptest_config(ProptestConfig {
            cases: 256,
            ..ProptestConfig::default()
        })]

        /// Any positive sat value up to 21M BTC roundtrips EXACTLY
        /// through the decimal `amount=` parser (no float).
        #[test]
        fn amount_roundtrip(sats in 1u64..=21_000_000_u64 * 100_000_000_u64) {
            let btc_str = format!("{}.{:08}", sats / 100_000_000, sats % 100_000_000);
            let uri = format!(
                "bitcoin:{VALID_ADDR}?amount={btc_str}&pj=https://x.example/y"
            );
            let parsed = PayjoinUri::parse(&uri).expect("parser must accept generated URI");
            prop_assert_eq!(parsed.amount.to_sat(), sats);
        }

        /// The parser MUST accept any URI of the form
        /// `bitcoin:<addr>?amount=X&pj=<url>` with arbitrary safe label
        /// / message values appended. Order of params should not matter.
        #[test]
        fn extra_params_dont_break_parsing(
            label in safe_query_value(),
            message in safe_query_value(),
        ) {
            let uri = format!(
                "bitcoin:{VALID_ADDR}?amount=0.001&label={label}&message={message}\
                 &pj=https://x.example/y"
            );
            let parsed = PayjoinUri::parse(&uri).expect("parser must tolerate extra params");
            prop_assert_eq!(parsed.amount.to_sat(), 100_000);
            prop_assert_eq!(parsed.endpoint, "https://x.example/y");
        }

        /// Endpoints with non-`http(s)://` schemes MUST be rejected
        /// regardless of the rest of the query.
        #[test]
        fn rejects_arbitrary_non_http_scheme(
            scheme in "[a-z][a-z0-9+.-]{0,8}",
        ) {
            // Skip http and https — those are valid.
            prop_assume!(scheme != "http" && scheme != "https");
            let uri = format!(
                "bitcoin:{VALID_ADDR}?amount=0.001&pj={scheme}://endpoint.example/y"
            );
            let result = PayjoinUri::parse(&uri);
            prop_assert!(
                matches!(result, Err(OnchainError::InvalidAddress(_))),
                "scheme {scheme:?} must be rejected; got {result:?}",
            );
        }

        /// `decode_percent` must never panic on arbitrary ASCII input
        /// — malformed `%xx` sequences return `None`.
        #[test]
        fn decode_percent_never_panics(s in "[ -~]{0,128}") {
            let _ = decode_percent(&s);
        }

        /// Round-trip: a label percent-encoded with the standard
        /// scheme, then run through `decode_percent`, MUST recover the
        /// original.
        #[test]
        fn percent_roundtrip(input in "[a-zA-Z0-9 _.,!@#$\\^&*()=+'\"<>:;\\[\\]{}|\\\\/?-]{0,32}") {
            let encoded = input
                .bytes()
                .map(|b| {
                    if b.is_ascii_alphanumeric() || matches!(b, b'-' | b'.' | b'_' | b'~') {
                        (b as char).to_string()
                    } else {
                        format!("%{b:02X}")
                    }
                })
                .collect::<String>();
            let decoded = decode_percent(&encoded).expect("well-formed encoding");
            prop_assert_eq!(decoded, input);
        }
    }
}
