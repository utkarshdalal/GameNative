//! Queueing-delay estimator (LEDBAT, RFC 6817) shared by both window controllers (Steam
//! `depot_writer` and the Epic/GOG/Amazon `fetch_core`).
//!
//! A background thread takes one TCP-connect round trip to the CDN hosts (round-robin, one every
//! [`PROBE_INTERVAL_MS`]). Each host keeps a **base delay** — the minimum RTT over
//! [`BASE_HISTORY_MS`] in [`BASE_BUCKET_MS`] buckets — and a sample's queueing delay is
//! `rtt − base`. The published value is the minimum of the last [`CURRENT_FILTER`] samples
//! (LEDBAT's current.filter). The window controllers poll it once per probe tick: queue above
//! [`TARGET_QUEUE_MS`] shrinks the window proportionally, otherwise the normal throughput-gated
//! growth logic runs — the window IS the congestion control, there is no byte-rate pacer.
//!
//! Why a TCP connect: it carries no body, so its RTT inflation is queueing at the bottleneck
//! link, not transfer time — the signal chunk-latency EWMA can only approximate (a chunk body
//! at full speed legitimately takes 300-600 ms). This is the congestion signal the error-only
//! window never had: device runs sat at window 112-120 with err_rate 0.0% and RTT 4-10 s of
//! bufferbloat.
//!
//! Deliberate scope cuts vs a full LEDBAT implementation: no loss response (a SYN retransmit
//! cannot be attributed to our traffic), no byte-rate cap (the in-flight window is the knob we
//! already have), and growth stays throughput-gated (delay only vetoes/shrinks).

use std::collections::{HashMap, VecDeque};
use std::net::{TcpStream, ToSocketAddrs};
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::Arc;
use std::thread;
use std::time::{Duration, Instant};

/// Target bottleneck queueing delay; above this the window yields. 40 ms is deliberately
/// below the RFC 6817 default of 100 ms: downloads are a background task on a shared phone
/// link, so the added bufferbloat for coexisting traffic is capped lower at the cost of
/// some throughput on jittery links. The proportional shrink scale follows the target
/// through [`delay_offset`].
pub const TARGET_QUEUE_MS: f64 = 40.0;
/// Proportional shrink gain per probe tick: `window × (1 + GAIN × off)`, so a queue at twice
/// the target cuts ~10% per tick (bounded, gentle — the 3 s cooldown lets the queue drain).
pub const QUEUE_GAIN: f64 = 0.10;
/// One connect per this interval, round-robin over the hosts.
const PROBE_INTERVAL_MS: u64 = 250;
/// A connect taking this long counts as no-sample (the host is effectively down, not queued).
const PROBE_TIMEOUT: Duration = Duration::from_secs(2);
/// Base-delay estimation: minimum RTT per 60 s bucket, kept for 10 minutes (RFC 6817).
const BASE_BUCKET_MS: u64 = 60_000;
const BASE_HISTORY_MS: u64 = 600_000;
/// LEDBAT current.filter: the published queue is the min of the last N samples.
const CURRENT_FILTER: usize = 4;

/// `off = (TARGET − queue) / TARGET` clamped to ±1: negative means the queue is over target.
pub fn delay_offset(queue_ms: f64) -> f64 {
    ((TARGET_QUEUE_MS - queue_ms) / TARGET_QUEUE_MS).clamp(-1.0, 1.0)
}

/// Parse a host string (`scheme://host[:port]/…`, bare `host[:port]`) into a connect target.
/// Default port 443 (80 for `http://`). IPv6 literals are not expected from CDN directories.
fn connect_target(host: &str) -> Option<(String, u16)> {
    let (scheme, rest) = match host.find("://") {
        Some(i) => (&host[..i], &host[i + 3..]),
        None => ("", host),
    };
    let authority = rest.split(['/', '?', '#']).next()?;
    if authority.is_empty() {
        return None;
    }
    if let Some((h, p)) = authority.rsplit_once(':') {
        if let Ok(port) = p.parse::<u16>() {
            return Some((h.to_string(), port));
        }
    }
    let port = if scheme.eq_ignore_ascii_case("http") { 80 } else { 443 };
    Some((authority.to_string(), port))
}

/// Per-host minimum RTT over a sliding history of fixed buckets.
#[derive(Debug, Default)]
struct BaseHistory {
    buckets: VecDeque<(Instant, f64)>,
}

impl BaseHistory {
    fn record(&mut self, rtt_ms: f64, now: Instant) {
        let span = Duration::from_millis(BASE_HISTORY_MS);
        while self
            .buckets
            .front()
            .is_some_and(|(start, _)| now.saturating_duration_since(*start) >= span)
        {
            self.buckets.pop_front();
        }
        match self.buckets.back_mut() {
            Some((start, min))
                if now.saturating_duration_since(*start) < Duration::from_millis(BASE_BUCKET_MS) =>
            {
                *min = min.min(rtt_ms);
            }
            _ => self.buckets.push_back((now, rtt_ms)),
        }
    }

    fn base_ms(&self) -> Option<f64> {
        self.buckets.iter().map(|(_, m)| *m).reduce(f64::min)
    }
}

/// The latest queueing-delay sample, shared between the prober thread and a window controller.
/// `f32` bits in atomics; NaN = no sample yet (prober warming up, or every host unreachable —
/// the window then runs its classic throughput/error logic untouched).
#[derive(Debug)]
pub struct QueueDelay {
    queue_ms: AtomicU32,
    base_ms: AtomicU32,
}

impl QueueDelay {
    pub fn new() -> Self {
        Self {
            queue_ms: AtomicU32::new(f32::NAN.to_bits()),
            base_ms: AtomicU32::new(f32::NAN.to_bits()),
        }
    }

    /// `(queue_ms, base_ms)` once the prober has a sample, else `None`.
    pub fn sample(&self) -> Option<(f64, f64)> {
        let q = f32::from_bits(self.queue_ms.load(Ordering::Relaxed));
        let b = f32::from_bits(self.base_ms.load(Ordering::Relaxed));
        (q.is_finite() && b.is_finite()).then(|| (q as f64, b as f64))
    }

    pub(crate) fn publish(&self, queue_ms: f64, base_ms: f64) {
        self.queue_ms
            .store((queue_ms as f32).to_bits(), Ordering::Relaxed);
        self.base_ms
            .store((base_ms as f32).to_bits(), Ordering::Relaxed);
    }
}

/// Background TCP-connect prober. Stops on drop (the download run ending drops it).
pub struct RttProber {
    stop: Arc<AtomicBool>,
    handle: Option<thread::JoinHandle<()>>,
}

impl RttProber {
    /// `None` when no host parses into a connect target — the window runs without the signal.
    pub fn spawn(hosts: Vec<String>, out: Arc<QueueDelay>) -> Option<Self> {
        let mut targets: Vec<(String, u16)> = Vec::new();
        for h in &hosts {
            if let Some(t) = connect_target(h) {
                if !targets.contains(&t) {
                    targets.push(t);
                }
            }
        }
        if targets.is_empty() {
            return None;
        }
        let stop = Arc::new(AtomicBool::new(false));
        let stop_thread = Arc::clone(&stop);
        let handle = thread::Builder::new()
            .name("rtt-prober".into())
            .spawn(move || {
                let mut bases: HashMap<String, BaseHistory> = HashMap::new();
                let mut recent: VecDeque<f64> = VecDeque::with_capacity(CURRENT_FILTER);
                let mut i = 0usize;
                while !stop_thread.load(Ordering::Relaxed) {
                    let (host, port) = &targets[i % targets.len()];
                    i = i.wrapping_add(1);
                    let addr = (host.as_str(), *port)
                        .to_socket_addrs()
                        .ok()
                        .and_then(|mut it| it.next());
                    if let Some(addr) = addr {
                        let t0 = Instant::now();
                        if TcpStream::connect_timeout(&addr, PROBE_TIMEOUT).is_ok() {
                            let ms = t0.elapsed().as_secs_f64() * 1000.0;
                            let now = Instant::now();
                            let hist = bases.entry(host.clone()).or_default();
                            hist.record(ms, now);
                            let queue = (ms - hist.base_ms().unwrap_or(ms)).max(0.0);
                            if recent.len() == CURRENT_FILTER {
                                recent.pop_front();
                            }
                            recent.push_back(queue);
                            let q = recent.iter().copied().reduce(f64::min).unwrap_or(0.0);
                            let base = bases
                                .values()
                                .filter_map(BaseHistory::base_ms)
                                .reduce(f64::min)
                                .unwrap_or(0.0);
                            out.publish(q, base);
                        }
                    }
                    // Sleep in slices so a dropping run stops the thread promptly.
                    for _ in 0..(PROBE_INTERVAL_MS / 50) {
                        if stop_thread.load(Ordering::Relaxed) {
                            break;
                        }
                        thread::sleep(Duration::from_millis(50));
                    }
                }
            })
            .ok()?;
        Some(Self {
            stop,
            handle: Some(handle),
        })
    }
}

impl Drop for RttProber {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        if let Some(h) = self.handle.take() {
            let _ = h.join();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::TcpListener;

    #[test]
    fn base_history_keeps_the_bucket_minimums() {
        let t = Instant::now();
        let mut h = BaseHistory::default();
        h.record(50.0, t);
        h.record(30.0, t + Duration::from_secs(1));
        assert_eq!(h.base_ms(), Some(30.0));
        // Next bucket: the min is across buckets.
        h.record(40.0, t + Duration::from_secs(61));
        assert_eq!(h.base_ms(), Some(30.0));
        // History expiry: everything older than 10 min drops.
        h.record(70.0, t + Duration::from_secs(601));
        assert_eq!(h.base_ms(), Some(40.0));
    }

    #[test]
    fn queue_delay_slot_is_none_until_published() {
        let q = QueueDelay::new();
        assert!(q.sample().is_none());
        q.publish(12.5, 30.0);
        assert_eq!(q.sample(), Some((12.5, 30.0)));
    }

    #[test]
    fn delay_offset_is_clamped_and_signed() {
        assert_eq!(delay_offset(0.0), 1.0);
        assert_eq!(delay_offset(TARGET_QUEUE_MS), 0.0);
        assert_eq!(delay_offset(2.0 * TARGET_QUEUE_MS), -1.0);
        assert_eq!(delay_offset(1000.0), -1.0);
    }

    #[test]
    fn connect_target_parses_urls_and_bare_hosts() {
        assert_eq!(
            connect_target("https://cdn.example.com/a/b?c=1"),
            Some(("cdn.example.com".to_string(), 443))
        );
        assert_eq!(
            connect_target("http://host:8080/x"),
            Some(("host".to_string(), 8080))
        );
        assert_eq!(
            connect_target("bare.example.com"),
            Some(("bare.example.com".to_string(), 443))
        );
        assert_eq!(connect_target(""), None);
    }

    #[test]
    fn prober_measures_near_zero_queue_on_loopback() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let accept_stop = Arc::new(AtomicBool::new(false));
        let accept_stop_thread = Arc::clone(&accept_stop);
        let acceptor = thread::spawn(move || {
            for stream in listener.incoming() {
                if accept_stop_thread.load(Ordering::Relaxed) {
                    break;
                }
                drop(stream);
            }
        });
        let out = Arc::new(QueueDelay::new());
        let prober = RttProber::spawn(vec![format!("127.0.0.1:{port}")], Arc::clone(&out))
            .expect("a parseable target");
        let deadline = Instant::now() + Duration::from_secs(5);
        let sample = loop {
            if let Some(s) = out.sample() {
                break s;
            }
            assert!(Instant::now() < deadline, "prober produced no sample");
            thread::sleep(Duration::from_millis(50));
        };
        assert!(sample.1 < 500.0, "loopback base RTT: {sample:?}");
        assert!(sample.0 < 500.0, "loopback queue delay: {sample:?}");
        drop(prober);
        accept_stop.store(true, Ordering::Relaxed);
        // Unblock the accept loop so the test exits cleanly.
        let _ = TcpStream::connect(("127.0.0.1", port));
        let _ = acceptor.join();
    }
}
