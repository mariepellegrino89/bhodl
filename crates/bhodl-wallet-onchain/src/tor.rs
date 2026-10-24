//! Tor / SOCKS5 transport plumbing.
//!
//! # Why this module exists (audit 2026-10, H8)
//!
//! `bdk_esplora`'s blocking client is `esplora-client` 0.12 over
//! `minreq` 2.x, and minreq's `Proxy` only speaks **HTTP CONNECT**
//! (`minreq/src/proxy.rs`: "Only HTTP CONNECT proxies are supported (no
//! SOCKS or HTTPS)"). Handing it the user's `socks5h://127.0.0.1:9050`
//! made `Proxy::new` fail with `BadProxy`, so with Tor enabled *every*
//! Esplora sync / broadcast / fee-estimate call failed (and the desktop
//! swallowed the error).
//!
//! There is no newer `esplora-client` blocking transport compatible with
//! `bdk_wallet` 3.0 that speaks SOCKS, so instead of swapping the HTTP
//! stack we run a tiny **in-process HTTP-CONNECT → SOCKS5 bridge**
//! ([`ConnectBridge`]):
//!
//! ```text
//! minreq ──CONNECT host:443──▶ 127.0.0.1:<ephemeral>  (this bridge)
//!                                    │ SOCKS5, ATYP=DOMAINNAME (remote DNS)
//!                                    ▼
//!                               Tor SOCKS port ──▶ host:443
//! ```
//!
//! TLS stays end-to-end between minreq/rustls and the Esplora server:
//! the bridge only ever sees ciphertext after the `200` reply.
//!
//! Hardening:
//! - binds loopback only, on an ephemeral port, for the lifetime of one
//!   [`EsploraClient`] (dropped → listener closed);
//! - every CONNECT must carry a per-bridge random
//!   `Proxy-Authorization` token, so another local process cannot use
//!   the bridge as an open proxy into Tor;
//! - only the configured Esplora `host:port` may be tunnelled (a
//!   redirect to another host fails closed instead of leaving Tor);
//! - the destination host name is passed to the SOCKS proxy unresolved
//!   (no local DNS lookup — the classic Tor DNS leak);
//! - handshake / idle timeouts and a concurrent-connection cap.

use std::io::{self, Read, Write};
use std::net::{IpAddr, Shutdown, SocketAddr, TcpListener, TcpStream, ToSocketAddrs};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::thread::JoinHandle;
use std::time::Duration;

use crate::error::OnchainError;

/// Max time for the local client to deliver its CONNECT request.
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(30);
/// Max time to open the upstream SOCKS5 tunnel (circuit build + remote
/// connect). Tor can stall 20-35 s here on busy relays, so this is far
/// more generous than a clearnet connect.
pub(crate) const SOCKS_CONNECT_TIMEOUT: Duration = Duration::from_secs(75);
/// A tunnel with no traffic in either direction for this long is closed.
const IDLE_TIMEOUT: Duration = Duration::from_secs(90);
/// Upper bound on the CONNECT request head we are willing to buffer.
const MAX_HEAD_BYTES: usize = 8 * 1024;
/// Concurrent tunnels per bridge (the Esplora client uses ≤ 4 in parallel).
const MAX_CONCURRENT_TUNNELS: usize = 16;

/// Parsed `socks5://` / `socks5h://` proxy endpoint.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct SocksProxy {
    pub(crate) host: String,
    pub(crate) port: u16,
}

/// Parse the user's Tor proxy setting.
///
/// Accepts `socks5h://host:port`, `socks5://host:port` and a bare
/// `host:port`. Both schemes are treated as *remote DNS* (`socks5h`
/// semantics): BHODL never resolves a destination locally when a proxy
/// is configured. Any other scheme (e.g. `http://`) is rejected so a
/// mis-typed setting fails closed instead of silently going clearnet.
pub(crate) fn parse_socks_proxy(proxy_url: &str) -> Result<SocksProxy, OnchainError> {
    let p = proxy_url.trim();
    let lower = p.to_ascii_lowercase();
    let rest = if lower.starts_with("socks5h://") {
        &p[10..]
    } else if lower.starts_with("socks5://") {
        &p[9..]
    } else if !p.contains("://") {
        p
    } else {
        return Err(OnchainError::InvalidArgument(format!(
            "Tor proxy must be a socks5:// or socks5h:// URL, got {proxy_url:?}"
        )));
    };
    let rest = rest.trim_end_matches('/');
    if rest.contains('@') {
        return Err(OnchainError::InvalidArgument(
            "SOCKS proxy credentials are not supported".into(),
        ));
    }
    let (host, port) = rest.rsplit_once(':').ok_or_else(|| {
        OnchainError::InvalidArgument(format!("proxy URL needs host:port, got {proxy_url:?}"))
    })?;
    let host = host.trim_start_matches('[').trim_end_matches(']');
    let port: u16 = port.parse().ok().filter(|p| *p != 0).ok_or_else(|| {
        OnchainError::InvalidArgument(format!("invalid proxy port in {proxy_url:?}"))
    })?;
    if host.is_empty() {
        return Err(OnchainError::InvalidArgument("empty proxy host".into()));
    }
    Ok(SocksProxy {
        host: host.to_string(),
        port,
    })
}

/// Canonical `socks5://host:port` form of the user's Tor proxy setting,
/// the only SOCKS flavour ureq 2 accepts.
///
/// ureq 2 only knows `socks5://`, but its SOCKS5 connector forwards host
/// names unresolved (ATYP=DOMAINNAME, see ureq `stream.rs::connect_socks`),
/// i.e. it already behaves like `socks5h://`. Single source of truth for
/// every ureq-based caller (rate history).
pub(crate) fn ureq_socks_proxy_url(proxy_url: &str) -> Result<String, OnchainError> {
    let p = parse_socks_proxy(proxy_url)?;
    let host = if p.host.contains(':') {
        format!("[{}]", p.host)
    } else {
        p.host
    };
    Ok(format!("socks5://{host}:{}", p.port))
}

/// Turn the user's Tor proxy setting into a `ureq::Proxy`.
pub(crate) fn ureq_socks_proxy(proxy_url: &str) -> Result<ureq::Proxy, OnchainError> {
    ureq::Proxy::new(ureq_socks_proxy_url(proxy_url)?)
        .map_err(|e| OnchainError::InvalidArgument(format!("invalid proxy: {e}")))
}

// ---------------------------------------------------------------------------
// HTTP-CONNECT → SOCKS5 bridge
// ---------------------------------------------------------------------------

/// Shared, immutable bridge configuration.
#[derive(Debug)]
struct BridgeConfig {
    socks: SocksProxy,
    /// The only `host` (lower-cased) the bridge will tunnel to.
    allowed_host: String,
    /// The only port the bridge will tunnel to.
    allowed_port: u16,
    /// Expected `Proxy-Authorization` header value (`Basic <b64>`).
    expected_auth: String,
    active: AtomicUsize,
}

/// A loopback HTTP-CONNECT proxy that forwards each tunnel through a
/// SOCKS5 proxy with remote DNS. See the module docs.
///
/// The listener thread stops when the bridge is dropped.
#[derive(Debug)]
pub(crate) struct ConnectBridge {
    addr: SocketAddr,
    token: String,
    shutdown: Arc<AtomicBool>,
    accept_thread: Option<JoinHandle<()>>,
}

impl ConnectBridge {
    /// Start a bridge that tunnels only to `allowed_host:allowed_port`
    /// through `socks`.
    pub(crate) fn start(
        socks: SocksProxy,
        allowed_host: &str,
        allowed_port: u16,
    ) -> Result<Self, OnchainError> {
        let mut raw = [0u8; 16];
        rand::TryRngCore::try_fill_bytes(&mut rand::rngs::OsRng, &mut raw)
            .map_err(|e| OnchainError::Rng(e.to_string()))?;
        let token: String = raw.iter().fold(String::with_capacity(32), |mut acc, b| {
            use std::fmt::Write as _;
            let _ = write!(acc, "{b:02x}");
            acc
        });
        // minreq sends `Proxy-Authorization: Basic base64(user)` when the
        // proxy URL carries a user and no password.
        let expected_auth = format!("Basic {}", base64_encode(token.as_bytes()));

        let listener = TcpListener::bind(("127.0.0.1", 0))
            .map_err(|e| OnchainError::EsploraSync(format!("Tor bridge bind failed: {e}")))?;
        let addr = listener
            .local_addr()
            .map_err(|e| OnchainError::EsploraSync(format!("Tor bridge bind failed: {e}")))?;

        let cfg = Arc::new(BridgeConfig {
            socks,
            allowed_host: allowed_host
                .trim_start_matches('[')
                .trim_end_matches(']')
                .to_ascii_lowercase(),
            allowed_port,
            expected_auth,
            active: AtomicUsize::new(0),
        });
        let shutdown = Arc::new(AtomicBool::new(false));
        let stop = Arc::clone(&shutdown);
        let accept_thread = std::thread::Builder::new()
            .name("bhodl-tor-bridge".into())
            .spawn(move || accept_loop(&listener, &cfg, &stop))
            .map_err(|e| OnchainError::EsploraSync(format!("Tor bridge thread: {e}")))?;

        Ok(Self {
            addr,
            token,
            shutdown,
            accept_thread: Some(accept_thread),
        })
    }

    /// The `http://` proxy URL to hand to minreq (`esplora_client::Builder::proxy`).
    pub(crate) fn proxy_url(&self) -> String {
        format!("http://{}@{}", self.token, self.addr)
    }

    #[cfg(test)]
    pub(crate) fn local_addr(&self) -> SocketAddr {
        self.addr
    }

    #[cfg(test)]
    pub(crate) fn auth_header(&self) -> String {
        format!("Basic {}", base64_encode(self.token.as_bytes()))
    }
}

impl Drop for ConnectBridge {
    fn drop(&mut self) {
        self.shutdown.store(true, Ordering::SeqCst);
        // Wake the blocking accept() so the thread observes the flag.
        let _ = TcpStream::connect_timeout(&self.addr, Duration::from_millis(500));
        if let Some(t) = self.accept_thread.take() {
            let _ = t.join();
        }
    }
}

fn accept_loop(listener: &TcpListener, cfg: &Arc<BridgeConfig>, stop: &AtomicBool) {
    for conn in listener.incoming() {
        if stop.load(Ordering::SeqCst) {
            break;
        }
        let Ok(stream) = conn else { continue };
        // Defence in depth: we bind 127.0.0.1, but never serve a
        // non-loopback peer even if that ever changes.
        if !stream.peer_addr().is_ok_and(|a| a.ip().is_loopback()) {
            continue;
        }
        if cfg.active.fetch_add(1, Ordering::SeqCst) >= MAX_CONCURRENT_TUNNELS {
            cfg.active.fetch_sub(1, Ordering::SeqCst);
            let mut s = stream;
            let _ = s.write_all(b"HTTP/1.1 503 Service Unavailable\r\n\r\n");
            continue;
        }
        let task_cfg = Arc::clone(cfg);
        let spawned = std::thread::Builder::new()
            .name("bhodl-tor-tunnel".into())
            .spawn(move || {
                let _ = handle_connection(stream, &task_cfg);
                task_cfg.active.fetch_sub(1, Ordering::SeqCst);
            });
        if spawned.is_err() {
            // Thread could not start — the closure (and its decrement)
            // was dropped with it.
            cfg.active.fetch_sub(1, Ordering::SeqCst);
        }
    }
}

/// Read until the end of the HTTP head (`\r\n\r\n`). Returns the head and
/// any bytes already read past it.
fn read_head(stream: &mut TcpStream) -> io::Result<(String, Vec<u8>)> {
    let mut buf = Vec::with_capacity(512);
    let mut chunk = [0u8; 512];
    loop {
        if let Some(pos) = buf.windows(4).position(|w| w == b"\r\n\r\n") {
            let rest = buf.split_off(pos + 4);
            let head = String::from_utf8(buf)
                .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "non-UTF-8 head"))?;
            return Ok((head, rest));
        }
        if buf.len() > MAX_HEAD_BYTES {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "request head too large",
            ));
        }
        let n = stream.read(&mut chunk)?;
        if n == 0 {
            return Err(io::Error::new(
                io::ErrorKind::UnexpectedEof,
                "client closed",
            ));
        }
        buf.extend_from_slice(&chunk[..n]);
    }
}

/// Validated CONNECT target.
#[derive(Debug, PartialEq, Eq)]
struct ConnectTarget {
    host: String,
    port: u16,
}

/// Parse and authorise a CONNECT request head. Returns the HTTP status
/// line to answer with on rejection.
fn check_connect(head: &str, cfg: &BridgeConfig) -> Result<ConnectTarget, &'static str> {
    let mut lines = head.split("\r\n");
    let request_line = lines.next().unwrap_or_default();
    let mut parts = request_line.split_whitespace();
    let (Some(method), Some(authority), Some(version)) = (parts.next(), parts.next(), parts.next())
    else {
        return Err("HTTP/1.1 400 Bad Request");
    };
    if method != "CONNECT" || !version.starts_with("HTTP/1.") {
        return Err("HTTP/1.1 405 Method Not Allowed");
    }

    let authorized = lines.any(|line| {
        line.split_once(':').is_some_and(|(name, value)| {
            name.trim().eq_ignore_ascii_case("proxy-authorization")
                && constant_time_eq(value.trim().as_bytes(), cfg.expected_auth.as_bytes())
        })
    });
    if !authorized {
        return Err("HTTP/1.1 407 Proxy Authentication Required");
    }

    let Some((host, port)) = authority.rsplit_once(':') else {
        return Err("HTTP/1.1 400 Bad Request");
    };
    let host = host
        .trim_start_matches('[')
        .trim_end_matches(']')
        .to_ascii_lowercase();
    let Ok(port) = port.parse::<u16>() else {
        return Err("HTTP/1.1 400 Bad Request");
    };
    if host != cfg.allowed_host || port != cfg.allowed_port {
        return Err("HTTP/1.1 403 Forbidden");
    }
    Ok(ConnectTarget { host, port })
}

fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    a.len() == b.len() && a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

fn handle_connection(mut client: TcpStream, cfg: &BridgeConfig) -> io::Result<()> {
    client.set_read_timeout(Some(HANDSHAKE_TIMEOUT))?;
    client.set_write_timeout(Some(HANDSHAKE_TIMEOUT))?;
    let (head, leftover) = read_head(&mut client)?;
    let target = match check_connect(&head, cfg) {
        Ok(t) => t,
        Err(status) => {
            let _ = client.write_all(format!("{status}\r\nContent-Length: 0\r\n\r\n").as_bytes());
            return Ok(());
        }
    };

    let Ok(mut upstream) =
        socks5_connect(&cfg.socks, &target.host, target.port, SOCKS_CONNECT_TIMEOUT)
    else {
        // Fail closed: never fall back to a direct connection.
        let _ = client.write_all(b"HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n");
        return Ok(());
    };
    if !leftover.is_empty() {
        upstream.write_all(&leftover)?;
    }
    client.write_all(b"HTTP/1.1 200 Connection established\r\n\r\n")?;
    client.flush()?;

    pipe(client, upstream)
}

/// Shovel bytes both ways until either side closes or goes idle.
fn pipe(client: TcpStream, upstream: TcpStream) -> io::Result<()> {
    for s in [&client, &upstream] {
        s.set_read_timeout(Some(IDLE_TIMEOUT))?;
        s.set_write_timeout(Some(IDLE_TIMEOUT))?;
    }
    let mut client_r = client.try_clone()?;
    let mut upstream_w = upstream.try_clone()?;
    let up = std::thread::Builder::new()
        .name("bhodl-tor-pipe".into())
        .spawn(move || {
            let _ = io::copy(&mut client_r, &mut upstream_w);
            let _ = upstream_w.shutdown(Shutdown::Write);
        })?;
    let mut upstream_r = upstream;
    let mut client_w = client;
    let _ = io::copy(&mut upstream_r, &mut client_w);
    let _ = client_w.shutdown(Shutdown::Both);
    let _ = upstream_r.shutdown(Shutdown::Both);
    let _ = up.join();
    Ok(())
}

/// Open a SOCKS5 (RFC 1928) CONNECT to `host:port` through `proxy`.
///
/// Domain names are sent as ATYP=0x03 (DOMAINNAME) so the *proxy*
/// resolves them — no local DNS query for the destination. Only literal
/// IP addresses are sent as ATYP 0x01/0x04.
pub(crate) fn socks5_connect(
    proxy: &SocksProxy,
    host: &str,
    port: u16,
    timeout: Duration,
) -> io::Result<TcpStream> {
    // Resolving the *proxy's own* address locally is fine (it is
    // normally 127.0.0.1 / localhost).
    let mut last_err = io::Error::new(io::ErrorKind::NotFound, "proxy address did not resolve");
    let mut stream = None;
    for addr in (proxy.host.as_str(), proxy.port).to_socket_addrs()? {
        match TcpStream::connect_timeout(&addr, timeout) {
            Ok(s) => {
                stream = Some(s);
                break;
            }
            Err(e) => last_err = e,
        }
    }
    let mut s = stream.ok_or(last_err)?;
    s.set_read_timeout(Some(timeout))?;
    s.set_write_timeout(Some(timeout))?;
    s.set_nodelay(true)?;

    // Greeting: version 5, one method, "no authentication".
    s.write_all(&[0x05, 0x01, 0x00])?;
    let mut reply = [0u8; 2];
    s.read_exact(&mut reply)?;
    if reply != [0x05, 0x00] {
        return Err(io::Error::other("SOCKS5 proxy refused no-auth method"));
    }

    let mut req = vec![0x05, 0x01, 0x00];
    let bare = host.trim_start_matches('[').trim_end_matches(']');
    match bare.parse::<IpAddr>() {
        Ok(IpAddr::V4(v4)) => {
            req.push(0x01);
            req.extend_from_slice(&v4.octets());
        }
        Ok(IpAddr::V6(v6)) => {
            req.push(0x04);
            req.extend_from_slice(&v6.octets());
        }
        Err(_) => {
            let name = bare.as_bytes();
            let len = u8::try_from(name.len())
                .ok()
                .filter(|l| *l > 0)
                .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "bad host length"))?;
            req.push(0x03);
            req.push(len);
            req.extend_from_slice(name);
        }
    }
    req.extend_from_slice(&port.to_be_bytes());
    s.write_all(&req)?;

    let mut head = [0u8; 4];
    s.read_exact(&mut head)?;
    if head[0] != 0x05 {
        return Err(io::Error::other("bad SOCKS5 reply version"));
    }
    if head[1] != 0x00 {
        return Err(io::Error::other(format!(
            "SOCKS5 connect failed (reply {})",
            head[1]
        )));
    }
    let skip = match head[3] {
        0x01 => 4,
        0x04 => 16,
        0x03 => {
            let mut l = [0u8; 1];
            s.read_exact(&mut l)?;
            usize::from(l[0])
        }
        _ => return Err(io::Error::other("bad SOCKS5 bound address type")),
    };
    let mut discard = vec![0u8; skip + 2];
    s.read_exact(&mut discard)?;
    Ok(s)
}

/// Minimal standard base64 (with padding) — only used for the bridge's
/// own `Proxy-Authorization` token, so no extra dependency.
fn base64_encode(input: &[u8]) -> String {
    const ALPHABET: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::with_capacity(input.len().div_ceil(3) * 4);
    for chunk in input.chunks(3) {
        let b = [
            chunk[0],
            *chunk.get(1).unwrap_or(&0),
            *chunk.get(2).unwrap_or(&0),
        ];
        let n = (u32::from(b[0]) << 16) | (u32::from(b[1]) << 8) | u32::from(b[2]);
        for i in 0..4 {
            if i <= chunk.len() {
                out.push(char::from(ALPHABET[((n >> (18 - 6 * i)) & 0x3f) as usize]));
            } else {
                out.push('=');
            }
        }
    }
    out
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use std::sync::mpsc;

    /// What the fake SOCKS5 server observed for one CONNECT request.
    #[derive(Debug, Clone)]
    pub(crate) struct SocksSeen {
        pub(crate) atyp: u8,
        pub(crate) host: String,
        pub(crate) port: u16,
    }

    /// One-shot fake SOCKS5 server. After the handshake it hands the
    /// stream to `after` (e.g. to play an HTTP server or echo).
    pub(crate) fn fake_socks_server(
        after: impl FnOnce(TcpStream) + Send + 'static,
    ) -> (SocksProxy, mpsc::Receiver<SocksSeen>) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let (tx, rx) = mpsc::channel();
        std::thread::spawn(move || {
            let (mut s, _) = listener.accept().unwrap();
            let mut greet = [0u8; 3];
            s.read_exact(&mut greet).unwrap();
            assert_eq!(greet, [5, 1, 0]);
            s.write_all(&[5, 0]).unwrap();
            let mut h = [0u8; 4];
            s.read_exact(&mut h).unwrap();
            let host = match h[3] {
                3 => {
                    let mut l = [0u8; 1];
                    s.read_exact(&mut l).unwrap();
                    let mut name = vec![0u8; usize::from(l[0])];
                    s.read_exact(&mut name).unwrap();
                    String::from_utf8(name).unwrap()
                }
                1 => {
                    let mut a = [0u8; 4];
                    s.read_exact(&mut a).unwrap();
                    std::net::Ipv4Addr::from(a).to_string()
                }
                other => panic!("unexpected atyp {other}"),
            };
            let mut p = [0u8; 2];
            s.read_exact(&mut p).unwrap();
            tx.send(SocksSeen {
                atyp: h[3],
                host,
                port: u16::from_be_bytes(p),
            })
            .unwrap();
            s.write_all(&[5, 0, 0, 1, 0, 0, 0, 0, 0, 0]).unwrap();
            after(s);
        });
        (
            SocksProxy {
                host: "127.0.0.1".into(),
                port,
            },
            rx,
        )
    }

    fn read_until_blank_line(s: &mut TcpStream) -> String {
        let mut out = Vec::new();
        let mut b = [0u8; 1];
        while !out.ends_with(b"\r\n\r\n") {
            if s.read(&mut b).unwrap() == 0 {
                break;
            }
            out.push(b[0]);
        }
        String::from_utf8(out).unwrap()
    }

    #[test]
    fn parse_socks_proxy_accepts_socks5h_socks5_and_bare() {
        for url in [
            "socks5h://127.0.0.1:9050",
            "socks5://127.0.0.1:9050",
            "127.0.0.1:9050",
        ] {
            assert_eq!(
                parse_socks_proxy(url).unwrap(),
                SocksProxy {
                    host: "127.0.0.1".into(),
                    port: 9050
                }
            );
        }
        assert!(parse_socks_proxy("http://127.0.0.1:8080").is_err());
        assert!(parse_socks_proxy("socks5h://127.0.0.1").is_err());
        assert!(parse_socks_proxy("socks5h://127.0.0.1:0").is_err());
        assert!(parse_socks_proxy("socks5h://u:p@127.0.0.1:9050").is_err());
    }

    #[test]
    fn ureq_proxy_url_is_canonical_socks5() {
        for url in [
            "socks5h://127.0.0.1:9050",
            "SOCKS5://127.0.0.1:9050",
            "127.0.0.1:9050",
        ] {
            assert_eq!(
                ureq_socks_proxy_url(url).unwrap(),
                "socks5://127.0.0.1:9050"
            );
        }
        assert_eq!(
            ureq_socks_proxy_url("socks5h://[::1]:9050").unwrap(),
            "socks5://[::1]:9050"
        );
        assert!(ureq_socks_proxy_url("http://127.0.0.1:9050").is_err());
    }

    #[test]
    fn ureq_proxy_maps_socks5h_to_socks5() {
        assert!(ureq_socks_proxy("socks5h://127.0.0.1:9050").is_ok());
        assert!(ureq_socks_proxy("http://127.0.0.1:9050").is_err());
    }

    #[test]
    fn base64_matches_rfc4648_vectors() {
        assert_eq!(base64_encode(b""), "");
        assert_eq!(base64_encode(b"f"), "Zg==");
        assert_eq!(base64_encode(b"fo"), "Zm8=");
        assert_eq!(base64_encode(b"foo"), "Zm9v");
        assert_eq!(base64_encode(b"foobar"), "Zm9vYmFy");
    }

    #[test]
    fn socks5_connect_sends_domain_unresolved() {
        let (proxy, seen) = fake_socks_server(|_| {});
        let _s =
            socks5_connect(&proxy, "esplora.example.onion", 80, Duration::from_secs(5)).unwrap();
        let seen = seen.recv_timeout(Duration::from_secs(5)).unwrap();
        assert_eq!(seen.atyp, 0x03, "domain must be sent as ATYP=DOMAINNAME");
        assert_eq!(seen.host, "esplora.example.onion");
        assert_eq!(seen.port, 80);
    }

    #[test]
    fn bridge_tunnels_allowed_target_with_remote_dns() {
        let (proxy, seen) = fake_socks_server(|mut s| {
            // Echo one message back.
            let mut buf = [0u8; 4];
            s.read_exact(&mut buf).unwrap();
            s.write_all(&buf).unwrap();
        });
        let bridge = ConnectBridge::start(proxy, "mempool.example", 443).unwrap();
        let mut c = TcpStream::connect(bridge.local_addr()).unwrap();
        c.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
        write!(
            c,
            "CONNECT mempool.example:443 HTTP/1.1\r\nProxy-Authorization: {}\r\n\r\n",
            bridge.auth_header()
        )
        .unwrap();
        let head = read_until_blank_line(&mut c);
        assert!(head.starts_with("HTTP/1.1 200"), "got {head:?}");
        c.write_all(b"ping").unwrap();
        let mut back = [0u8; 4];
        c.read_exact(&mut back).unwrap();
        assert_eq!(&back, b"ping");

        let seen = seen.recv_timeout(Duration::from_secs(5)).unwrap();
        assert_eq!(seen.atyp, 0x03);
        assert_eq!(seen.host, "mempool.example");
        assert_eq!(seen.port, 443);
    }

    #[test]
    fn bridge_rejects_other_targets_and_missing_auth() {
        // The fake SOCKS server must never be contacted.
        let (proxy, seen) = fake_socks_server(|_| {});
        let bridge = ConnectBridge::start(proxy, "mempool.example", 443).unwrap();

        let mut c = TcpStream::connect(bridge.local_addr()).unwrap();
        c.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
        write!(
            c,
            "CONNECT evil.example:443 HTTP/1.1\r\nProxy-Authorization: {}\r\n\r\n",
            bridge.auth_header()
        )
        .unwrap();
        assert!(read_until_blank_line(&mut c).starts_with("HTTP/1.1 403"));

        let mut c = TcpStream::connect(bridge.local_addr()).unwrap();
        c.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
        c.write_all(b"CONNECT mempool.example:443 HTTP/1.1\r\n\r\n")
            .unwrap();
        assert!(read_until_blank_line(&mut c).starts_with("HTTP/1.1 407"));

        let mut c = TcpStream::connect(bridge.local_addr()).unwrap();
        c.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
        write!(
            c,
            "GET http://mempool.example/ HTTP/1.1\r\nProxy-Authorization: {}\r\n\r\n",
            bridge.auth_header()
        )
        .unwrap();
        assert!(read_until_blank_line(&mut c).starts_with("HTTP/1.1 405"));

        assert!(seen.recv_timeout(Duration::from_millis(300)).is_err());
    }

    #[test]
    fn bridge_fails_closed_when_socks_proxy_is_down() {
        // Grab a free port and close it so nothing listens there.
        let dead_port = {
            let l = TcpListener::bind("127.0.0.1:0").unwrap();
            l.local_addr().unwrap().port()
        };
        let proxy = SocksProxy {
            host: "127.0.0.1".into(),
            port: dead_port,
        };
        let bridge = ConnectBridge::start(proxy, "mempool.example", 443).unwrap();
        let mut c = TcpStream::connect(bridge.local_addr()).unwrap();
        c.set_read_timeout(Some(Duration::from_secs(10))).unwrap();
        write!(
            c,
            "CONNECT mempool.example:443 HTTP/1.1\r\nProxy-Authorization: {}\r\n\r\n",
            bridge.auth_header()
        )
        .unwrap();
        assert!(read_until_blank_line(&mut c).starts_with("HTTP/1.1 502"));
    }

    #[test]
    fn esplora_client_through_bridge_end_to_end() {
        // minreq (esplora-client) -> loopback CONNECT bridge -> fake SOCKS
        // proxy that then plays a plain-HTTP Esplora server.
        let (proxy, seen) = fake_socks_server(|mut s| {
            let req = read_until_blank_line(&mut s);
            assert!(
                req.starts_with("GET /api/blocks/tip/height "),
                "got {req:?}"
            );
            s.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 6\r\n\r\n123456")
                .unwrap();
        });
        let proxy_url = format!("socks5h://{}:{}", proxy.host, proxy.port);
        let client =
            crate::common::try_build_esplora_client("http://esplora.example/api", Some(&proxy_url))
                .unwrap();
        assert_eq!(client.get_height().unwrap(), 123_456);
        let seen = seen.recv_timeout(Duration::from_secs(5)).unwrap();
        assert_eq!(
            seen.atyp, 0x03,
            "Esplora host must reach the proxy unresolved"
        );
        assert_eq!(seen.host, "esplora.example");
        assert_eq!(seen.port, 80);
    }

    #[test]
    fn esplora_client_fails_closed_without_reachable_proxy() {
        let dead_port = {
            let l = TcpListener::bind("127.0.0.1:0").unwrap();
            l.local_addr().unwrap().port()
        };
        let client = crate::common::try_build_esplora_client(
            "http://esplora.example/api",
            Some(&format!("socks5h://127.0.0.1:{dead_port}")),
        )
        .unwrap();
        assert!(client.get_height().is_err());
        // A malformed proxy setting is refused up-front …
        assert!(
            crate::common::try_build_esplora_client("https://x.example/api", Some("http://p:1"))
                .is_err()
        );
        // … and the infallible builder degrades to a client that cannot
        // reach anything (BadProxy), never to a direct connection.
        let c = crate::common::build_esplora_client("https://x.example/api", Some("http://p:1"));
        assert!(c.get_height().is_err());
    }
}
