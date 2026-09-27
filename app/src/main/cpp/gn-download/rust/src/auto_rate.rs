//! Delay-based automatic download rate controller (RFC 6817 LEDBAT), shared by the Steam depot
//! writer and the Epic / GOG / Amazon fetch core.
//!
//! Each probe host keeps a base delay (minimum over [`BASE_HISTORY_MS`] in [`BASE_BUCKET_MS`]
//! buckets); a sample's queueing delay is `rtt − base`, and a tick's delay is the minimum of the
//! last [`CURRENT_FILTER`] samples. Every [`RATE_TICK_MS`] the byte-rate cap moves by
//! `off = (TARGET − queue) / TARGET` clamped to ±1: a decrease is `cap × (1 + GAIN × off)`, an
//! increase is the same step bounded by [`ADD_MAX_BPS`], and the cap never exceeds the delivered
//! rate plus [`ADD_MAX_BPS`] (flight-size bound). [`RatePacer`] enforces the cap on every response
//! body: each received piece is charged and the next socket read waits out the debt, so TCP
//! backpressure shapes the wire rate per connection.
//!
//! Deviations from RFC 6817:
//! - RTT is a TCP connect round trip to the busiest CDN host ([`RttProber`]) instead of one-way delay.
//! - TARGET is [`TARGET_QUEUE_MS`] (40 ms).
//! - Halving on loss is omitted: a SYN retransmit on the probe cannot be attributed to our traffic,
//!   so it is only a large delay sample.
//! - The cap never drops under [`FLOOR_BEST_SHARE`] × the best 10 s mean delivered rate.
//! - Causality guard: if deep cuts leave the queue where it was, the queue is not ours; the cap is
//!   released and stays off for [`RELEASE_SUPPRESS`].
//! - The cap starts off, engages at [`ENGAGE_SHARE`] × the 10 s mean delivered rate after
//!   [`ENGAGE_TICKS`] ticks above target, and disengages once it has sat far above the delivered
//!   rate for [`DISENGAGE_AFTER`].

use std::collections::{HashMap, VecDeque};
use std::net::{SocketAddr, TcpStream, ToSocketAddrs};
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

const RATE_TICK_MS: u64 = 500;
const STALLED_CALL_GAP_MS: u64 = 2 * RATE_TICK_MS;
const TARGET_QUEUE_MS: f64 = 40.0;
const GAIN: f64 = 0.10;
const ADD_MAX_BPS: f64 = 512.0 * 1024.0;
const CURRENT_FILTER: usize = 4;
const ENGAGE_TICKS: u32 = 2;
const ENGAGE_SHARE: f64 = 0.9;
const DISENGAGE_OVER_DELIVERED: f64 = 1.5;
const DISENGAGE_AFTER: Duration = Duration::from_secs(10);
const RATE_FLOOR_BPS: f64 = 1024.0 * 1024.0;
const FLOOR_BEST_SHARE: f64 = 0.35;
const SMOOTH_WINDOW: Duration = Duration::from_secs(10);
const RELEASE_CAP_SHARE: f64 = 0.6;
const RELEASE_HOLD: Duration = Duration::from_secs(15);
const RELEASE_QUEUE_DROP: f64 = 0.25;
const RELEASE_SUPPRESS: Duration = Duration::from_secs(60);
const QUEUE_HISTORY: Duration = Duration::from_secs(35);
const TICK_LINE_SAMPLES: usize = 6;
const RATE_LOG_TAG: &str = "GN_RATE";
const BASE_BUCKET_MS: u64 = 60_000;
const BASE_HISTORY_MS: u64 = 600_000;
const DELIVERED_WINDOW_MS: u64 = 2_000;
const BURST_S: f64 = 0.25;
const BURST_MIN_BYTES: f64 = 256.0 * 1024.0;
const CANCEL_POLL: Duration = Duration::from_millis(50);
const PROBE_INTERVAL_MS: u64 = RATE_TICK_MS / 4;
const PROBE_TIMEOUT: Duration = Duration::from_secs(2);
const TICK_DUR: Duration = Duration::from_millis(RATE_TICK_MS);

/// Per-host minimum RTT over a sliding history of fixed buckets.
#[derive(Clone, Debug, Default)]
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

/// The controller: per-probe-host bases, the tick's queueing samples, and the cap.
#[derive(Clone, Debug)]
pub struct AutoRate {
    cap_bps: f64,
    bases: HashMap<String, BaseHistory>,
    tick_samples: Vec<f64>,
    queue_max_ms: std::cell::Cell<f64>,
    start: Instant,
    best_bps: f64,
    last_tick: Instant,
    last_call: Instant,
    stalled: bool,
    last_bytes: u64,
    delivered_marks: VecDeque<(Instant, u64)>,
    delivered_bps: f64,
    delivered_10s_bps: f64,
    above_ticks: u32,
    recent_samples: VecDeque<f64>,
    queue_ms: f64,
    off: f64,
    over_since: Option<Instant>,
    smooth_marks: VecDeque<(Instant, u64)>,
    queue_history: VecDeque<(Instant, f64)>,
    engage_cap: f64,
    peak: (f64, Instant),
    deep_since: Option<(Instant, Option<f64>)>,
    suppress_until: Option<Instant>,
}

impl AutoRate {
    /// Starts off (non-binding) until queueing exceeds the target for [`ENGAGE_TICKS`] ticks.
    pub fn new(now: Instant) -> Self {
        Self {
            cap_bps: f64::INFINITY,
            bases: HashMap::new(),
            tick_samples: Vec::new(),
            queue_max_ms: std::cell::Cell::new(0.0),
            start: now,
            best_bps: 0.0,
            last_tick: now,
            last_call: now,
            stalled: false,
            last_bytes: 0,
            delivered_marks: VecDeque::from([(now, 0)]),
            delivered_bps: 0.0,
            delivered_10s_bps: 0.0,
            above_ticks: 0,
            recent_samples: VecDeque::with_capacity(CURRENT_FILTER),
            queue_ms: 0.0,
            off: 0.0,
            over_since: None,
            smooth_marks: VecDeque::from([(now, 0)]),
            queue_history: VecDeque::new(),
            engage_cap: f64::INFINITY,
            peak: (f64::INFINITY, now),
            deep_since: None,
            suppress_until: None,
        }
    }

    /// The cap in bytes/s (`INFINITY` while off).
    pub fn cap_bps(&self) -> f64 {
        self.cap_bps
    }

    #[cfg(test)]
    fn queue_ms(&self) -> f64 {
        self.queue_ms
    }

    fn base_ms(&self) -> f64 {
        self.bases
            .values()
            .filter_map(BaseHistory::base_ms)
            .reduce(f64::min)
            .unwrap_or(0.0)
    }

    /// One connect round trip to probe host `host`.
    pub fn record_rtt(&mut self, host: &str, rtt: Duration, now: Instant) {
        let ms = rtt.as_secs_f64() * 1000.0;
        let hist = self.bases.entry(host.to_string()).or_default();
        hist.record(ms, now);
        let queue = (ms - hist.base_ms().unwrap_or(ms)).max(0.0);
        self.tick_samples.push(queue);
        if self.recent_samples.len() == CURRENT_FILTER {
            self.recent_samples.pop_front();
        }
        self.recent_samples.push_back(queue);
        self.queue_max_ms.set(self.queue_max_ms.get().max(queue));
    }

    /// The lowest cap allowed: a share of the best 10 s mean delivered rate, at least the floor.
    fn floor_bps(&self) -> f64 {
        (self.best_bps * FLOOR_BEST_SHARE).max(RATE_FLOOR_BPS)
    }

    fn mean_queue(&self, from: Instant, to: Instant) -> Option<f64> {
        let v: Vec<f64> = self.queue_history.iter().filter(|(t, _)| *t >= from && *t <= to).map(|(_, q)| *q).collect();
        (!v.is_empty()).then(|| v.iter().sum::<f64>() / v.len() as f64)
    }

    fn not_our_queue(&mut self, now: Instant) -> Option<String> {
        let deep = self.cap_bps <= self.engage_cap * RELEASE_CAP_SHARE || self.cap_bps <= self.floor_bps() + 1.0;
        if !deep {
            self.deep_since = None;
            return None;
        }
        let back = |d: Duration| now.checked_sub(d).unwrap_or(self.start);
        let (since, before) = match self.deep_since {
            Some(state) => state,
            None => {
                let from = back(RELEASE_HOLD).max(self.peak.1.checked_sub(TICK_DUR * ENGAGE_TICKS).unwrap_or(self.start));
                let state = (now, self.mean_queue(from, now));
                self.deep_since = Some(state);
                state
            }
        };
        if now.saturating_duration_since(since) < RELEASE_HOLD {
            return None;
        }
        let before = before?;
        let recent = self.mean_queue(back(RELEASE_HOLD), now)?;
        if recent <= TARGET_QUEUE_MS || recent < before * (1.0 - RELEASE_QUEUE_DROP) {
            return None;
        }
        Some(format!(
            "not our queue: cap {:.2}MB/s (engaged at {:.2}MB/s, floor {:.2}MB/s) for {}s, mean q {:.0}ms before cuts, {:.0}ms after",
            self.cap_bps / MIB_F, self.engage_cap / MIB_F, self.floor_bps() / MIB_F, RELEASE_HOLD.as_secs(), before, recent
        ))
    }

    /// Called every driver turn with the cumulative delivered byte count. Runs the controller
    /// when a tick has elapsed and returns its report; a tick without delivered bytes is deferred
    /// so the rate is measured over the whole gap, and a tick after a driver stall may not
    /// decrease the cap.
    pub fn maybe_tick(&mut self, now: Instant, total_bytes: u64) -> Option<RateTick> {
        if now.saturating_duration_since(self.last_call) > Duration::from_millis(STALLED_CALL_GAP_MS) {
            self.stalled = true;
        }
        self.last_call = now;
        let dt = now.saturating_duration_since(self.last_tick);
        if dt < Duration::from_millis(RATE_TICK_MS) {
            return None;
        }
        if total_bytes == self.last_bytes {
            if self.tick_samples.is_empty() {
                self.last_tick = now;
                self.stalled = false;
                self.delivered_marks.clear();
                self.delivered_marks.push_back((now, total_bytes));
            }
            return None;
        }
        self.last_tick = now;
        self.last_bytes = total_bytes;
        let window = Duration::from_millis(DELIVERED_WINDOW_MS);
        while self.delivered_marks.len() > 1
            && now.saturating_duration_since(self.delivered_marks[1].0) >= window
        {
            self.delivered_marks.pop_front();
        }
        if let Some(&(since, bytes)) = self.delivered_marks.front() {
            let secs = now.saturating_duration_since(since).as_secs_f64().max(0.001);
            self.delivered_bps = total_bytes.saturating_sub(bytes) as f64 / secs;
        }
        self.delivered_marks.push_back((now, total_bytes));
        while self.smooth_marks.len() > 1 && now.saturating_duration_since(self.smooth_marks[1].0) >= SMOOTH_WINDOW {
            self.smooth_marks.pop_front();
        }
        if let Some(&(since, bytes)) = self.smooth_marks.front() {
            let span = now.saturating_duration_since(since);
            if !span.is_zero() {
                self.delivered_10s_bps = total_bytes.saturating_sub(bytes) as f64 / span.as_secs_f64();
            }
            if span >= SMOOTH_WINDOW {
                self.best_bps = self.best_bps.max(self.delivered_10s_bps);
            }
        }
        self.smooth_marks.push_back((now, total_bytes));
        let trusted = !std::mem::take(&mut self.stalled);

        let samples = std::mem::take(&mut self.tick_samples);
        let measured = !samples.is_empty();
        let tick_median = if measured { median(&mut samples.clone()) } else { self.queue_ms };
        let queue = if measured {
            self.recent_samples.iter().copied().reduce(f64::min).unwrap_or(tick_median)
        } else {
            self.queue_ms
        };
        if measured {
            self.queue_ms = queue;
            self.queue_history.push_back((now, queue));
        }
        while self
            .queue_history
            .front()
            .is_some_and(|(t, _)| now.saturating_duration_since(*t) > QUEUE_HISTORY)
        {
            self.queue_history.pop_front();
        }
        let cap_before = self.cap_bps;
        let decision_q = queue;
        let mut reason = None;
        let rule = if !self.cap_bps.is_finite() {
            self.off = 0.0;
            let suppressed = self.suppress_until.is_some_and(|until| now < until);
            if measured {
                self.above_ticks = if queue > TARGET_QUEUE_MS && !suppressed { self.above_ticks + 1 } else { 0 };
            }
            if self.above_ticks >= ENGAGE_TICKS && self.delivered_10s_bps > 0.0 {
                self.above_ticks = 0;
                self.over_since = None;
                self.suppress_until = None;
                let share = self.delivered_10s_bps * ENGAGE_SHARE;
                self.cap_bps = share.max(self.floor_bps());
                self.engage_cap = self.cap_bps;
                self.peak = (self.cap_bps, now);
                self.deep_since = None;
                reason = Some(format!(
                    "q>{TARGET_QUEUE_MS:.0}ms for {ENGAGE_TICKS} ticks, {ENGAGE_SHARE}x delivered_10s={:.2}MB/s floor={:.2}MB/s",
                    self.delivered_10s_bps / MIB_F, self.floor_bps() / MIB_F
                ));
                "engage"
            } else {
                "off"
            }
        } else {
            let mut off = if measured { ((TARGET_QUEUE_MS - queue) / TARGET_QUEUE_MS).clamp(-1.0, 1.0) } else { 0.0 };
            if off < 0.0 && !trusted {
                off = 0.0;
            }
            self.off = off;
            let law = if off >= 0.0 {
                self.cap_bps + (GAIN * off * self.cap_bps).min(ADD_MAX_BPS)
            } else {
                self.cap_bps * (1.0 + GAIN * off)
            };
            let flight = if trusted { self.delivered_bps + ADD_MAX_BPS } else { (self.delivered_bps + ADD_MAX_BPS).max(self.cap_bps) };
            let bounded = law.min(flight);
            let floor = self.floor_bps();
            let rule = if bounded < floor {
                self.cap_bps = floor;
                reason = Some(format!(
                    "law {:.2}MB/s flight {:.2}MB/s under floor {:.2}MB/s ({FLOOR_BEST_SHARE}x best {:.2}MB/s)",
                    law / MIB_F, flight / MIB_F, floor / MIB_F, self.best_bps / MIB_F
                ));
                "floor"
            } else if law > flight {
                self.cap_bps = bounded;
                "flight"
            } else {
                self.cap_bps = law;
                "queue"
            };
            let rule = if self.cap_bps > self.delivered_bps * DISENGAGE_OVER_DELIVERED {
                let since = *self.over_since.get_or_insert(now);
                if now.saturating_duration_since(since) >= DISENGAGE_AFTER {
                    reason = Some(format!(
                        "cap {:.2}MB/s > {DISENGAGE_OVER_DELIVERED}x delivered {:.2}MB/s for {}s",
                        self.cap_bps / MIB_F, self.delivered_bps / MIB_F, DISENGAGE_AFTER.as_secs()
                    ));
                    self.cap_bps = f64::INFINITY;
                    self.over_since = None;
                    self.above_ticks = 0;
                    self.off = 0.0;
                    self.deep_since = None;
                    "disengage"
                } else {
                    rule
                }
            } else {
                self.over_since = None;
                rule
            };
            if self.cap_bps >= self.peak.0 {
                self.peak = (self.cap_bps, now);
            }
            if let Some(why) = self.cap_bps.is_finite().then(|| self.not_our_queue(now)).flatten() {
                reason = Some(why);
                self.cap_bps = f64::INFINITY;
                self.over_since = None;
                self.above_ticks = 0;
                self.off = 0.0;
                self.deep_since = None;
                self.suppress_until = Some(now + RELEASE_SUPPRESS);
                "release"
            } else {
                rule
            }
        };
        Some(RateTick {
            t_ms: now.saturating_duration_since(self.start).as_millis() as u64,
            q: decision_q,
            qmed: tick_median,
            samples,
            base: self.base_ms(),
            off: self.off,
            cap_before,
            cap_after: self.cap_bps,
            rule,
            reason,
            delivered: self.delivered_bps,
            best: self.best_bps,
        })
    }

    /// `rtt_base=…ms queue=…ms queue_max=…ms cap=…MB/s off=… rate_stalls=…` for the fetch-window
    /// lines; `queue_max` is the largest probe sample since the previous call.
    pub fn log_fields(&self, rate_stalls: u32) -> String {
        let cap = if self.cap_bps.is_finite() {
            format!("{:.2}MB/s", self.cap_bps / (1024.0 * 1024.0))
        } else {
            "none".to_string()
        };
        format!(
            "rtt_base={:.0}ms queue={:.0}ms queue_max={:.0}ms cap={cap} off={:.2} rate_stalls={rate_stalls}",
            self.base_ms(),
            self.queue_ms,
            self.queue_max_ms.replace(0.0),
            self.off
        )
    }
}

const MIB_F: f64 = 1024.0 * 1024.0;

/// One controller tick, for the `GN_RATE` diagnostics.
#[derive(Clone, Debug)]
pub struct RateTick {
    pub t_ms: u64,
    pub q: f64,
    pub qmed: f64,
    pub samples: Vec<f64>,
    pub base: f64,
    pub off: f64,
    pub cap_before: f64,
    pub cap_after: f64,
    pub rule: &'static str,
    pub reason: Option<String>,
    pub delivered: f64,
    pub best: f64,
}

fn mbps(bps: f64) -> String {
    if bps.is_finite() {
        format!("{:.2}", bps / MIB_F)
    } else {
        "none".to_string()
    }
}

impl RateTick {
    /// `rate-tick <scope> t=+…ms q=… qmed=… samples=[…] base=… off=… cap_before=… cap_after=…
    /// rule=… delivered=… best=… inflight=… stalls=…`.
    pub fn line(&self, scope: &str, inflight: usize, stalls: u32) -> String {
        let mut samples: Vec<String> = self.samples.iter().take(TICK_LINE_SAMPLES).map(|q| format!("{q:.0}")).collect();
        if self.samples.len() > TICK_LINE_SAMPLES {
            samples.push(format!("+{}", self.samples.len() - TICK_LINE_SAMPLES));
        }
        format!(
            "rate-tick {scope} t=+{}ms q={:.0} qmed={:.0} samples=[{}] base={:.0} off={:.2} cap_before={} cap_after={} rule={} delivered={} best={} inflight={inflight} stalls={stalls}",
            self.t_ms,
            self.q,
            self.qmed,
            samples.join(","),
            self.base,
            self.off,
            mbps(self.cap_before),
            mbps(self.cap_after),
            self.rule,
            mbps(self.delivered),
            mbps(self.best),
        )
    }

    /// `rate-event <scope> …` for a cap change that did not come from the queue law.
    pub fn event_line(&self, scope: &str) -> Option<String> {
        if self.rule == "queue" || self.rule == "flight" || self.cap_before == self.cap_after && self.cap_before.is_finite() == self.cap_after.is_finite() {
            return None;
        }
        Some(format!(
            "rate-event {scope} t=+{}ms rule={} cap {} -> {} reason={}",
            self.t_ms,
            self.rule,
            mbps(self.cap_before),
            mbps(self.cap_after),
            self.reason.as_deref().unwrap_or("-")
        ))
    }
}

#[cfg(target_os = "android")]
#[link(name = "log")]
unsafe extern "C" {
    fn __android_log_write(prio: i32, tag: *const i8, text: *const i8) -> i32;
}

/// Write one line to logcat at Info under [`RATE_LOG_TAG`].
pub fn rate_log(message: &str) {
    #[cfg(target_os = "android")]
    {
        use std::ffi::CString;
        let (Ok(tag), Ok(text)) = (CString::new(RATE_LOG_TAG), CString::new(message)) else {
            return;
        };
        unsafe { __android_log_write(4, tag.as_ptr().cast(), text.as_ptr().cast()) };
    }
    #[cfg(not(target_os = "android"))]
    let _ = (RATE_LOG_TAG, message);
}

fn median(samples: &mut [f64]) -> f64 {
    samples.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
    let n = samples.len();
    if n % 2 == 1 {
        samples[n / 2]
    } else {
        (samples[n / 2 - 1] + samples[n / 2]) / 2.0
    }
}

#[derive(Debug)]
struct Bucket {
    tokens: f64,
    last: Instant,
    rate_bps: f64,
}

impl Bucket {
    fn burst(&self) -> f64 {
        (self.rate_bps * BURST_S).max(BURST_MIN_BYTES)
    }

    fn refill(&mut self, now: Instant) {
        let dt = now.saturating_duration_since(self.last).as_secs_f64();
        self.last = self.last.max(now);
        if self.rate_bps.is_finite() {
            self.tokens = (self.tokens + dt * self.rate_bps).min(self.burst());
        }
    }
}

/// Token bucket enforcing the cap on received body bytes, shared by every in-flight response of
/// one download. Burst is a quarter second of cap (at least 256 KiB) and never depends on
/// request latency. A read may overdraw the bucket; the reader then waits out the debt.
#[derive(Debug)]
pub struct RatePacer {
    bucket: Mutex<Bucket>,
    stalls: AtomicU32,
}

impl RatePacer {
    pub fn new(now: Instant) -> Self {
        Self {
            bucket: Mutex::new(Bucket {
                tokens: 0.0,
                last: now,
                rate_bps: f64::INFINITY,
            }),
            stalls: AtomicU32::new(0),
        }
    }

    /// Follow the controller's cap.
    pub fn set_rate(&self, now: Instant, rate_bps: f64) {
        let mut b = self.bucket.lock().expect("pacer poisoned");
        b.refill(now);
        let was_unbounded = !b.rate_bps.is_finite();
        b.rate_bps = rate_bps;
        if was_unbounded && rate_bps.is_finite() {
            b.tokens = b.burst();
        }
    }

    /// Charge `bytes` received on a body; returns how long to wait before reading more.
    pub fn pace(&self, now: Instant, bytes: u64) -> Duration {
        let mut b = self.bucket.lock().expect("pacer poisoned");
        b.refill(now);
        if !b.rate_bps.is_finite() {
            return Duration::ZERO;
        }
        b.tokens -= bytes as f64;
        if b.tokens >= 0.0 {
            Duration::ZERO
        } else {
            self.stalls.fetch_add(1, Ordering::Relaxed);
            Duration::from_secs_f64(-b.tokens / b.rate_bps)
        }
    }

    /// Rate stalls since the last call.
    pub fn take_stalls(&self) -> u32 {
        self.stalls.swap(0, Ordering::Relaxed)
    }
}

/// A body read was abandoned because the download was cancelled.
#[derive(Debug, PartialEq, Eq)]
pub struct Cancelled;

fn clock_now() -> Instant {
    tokio::time::Instant::now().into_std()
}

/// Charge `bytes` just read from a body and wait out the pacer's delay before the next read,
/// re-checking `cancel` at least every [`CANCEL_POLL`].
pub async fn pace_read(pacer: &RatePacer, bytes: u64, cancel: Option<&AtomicBool>) -> Result<(), Cancelled> {
    let wait = pacer.pace(clock_now(), bytes);
    let deadline = tokio::time::Instant::now() + wait;
    loop {
        if cancel.is_some_and(|c| c.load(Ordering::Relaxed)) {
            return Err(Cancelled);
        }
        let now = tokio::time::Instant::now();
        if now >= deadline {
            return Ok(());
        }
        tokio::time::sleep((deadline - now).min(CANCEL_POLL)).await;
    }
}

#[derive(Default)]
struct ProbeShared {
    target: Mutex<Option<(String, u16)>>,
    results: Mutex<Vec<(String, Duration)>>,
    stop: AtomicBool,
}

/// Out-of-band RTT probe: a dedicated thread times a blocking TCP connect (no TLS, no request)
/// to the host with the most requests in flight every [`PROBE_INTERVAL_MS`], so driver stalls
/// cannot inflate a sample. The thread stops when this is dropped.
pub struct RttProber {
    usage: HashMap<usize, (String, u16, u32)>,
    shared: Arc<ProbeShared>,
    thread: Option<thread::JoinHandle<()>>,
}

impl RttProber {
    /// Start the probe thread (probing begins once a request has been dispatched).
    pub fn start() -> Self {
        let shared = Arc::new(ProbeShared::default());
        let worker = shared.clone();
        let thread = thread::Builder::new()
            .name("gn-rtt-probe".to_string())
            .spawn(move || probe_loop(&worker))
            .ok();
        Self { usage: HashMap::new(), shared, thread }
    }

    /// A request to `url` was dispatched on server `server_idx`.
    pub fn note_dispatch(&mut self, server_idx: usize, url: &str) {
        let Ok(parsed) = reqwest::Url::parse(url) else {
            return;
        };
        let (Some(host), Some(port)) = (parsed.host_str(), parsed.port_or_known_default()) else {
            return;
        };
        let entry = self.usage.entry(server_idx).or_insert_with(|| (String::new(), 0, 0));
        if entry.0 != host || entry.1 != port {
            entry.0 = host.to_string();
            entry.1 = port;
        }
        entry.2 += 1;
        self.publish_target();
    }

    /// A request on server `server_idx` finished (either way).
    pub fn note_done(&mut self, server_idx: usize) {
        if let Some(entry) = self.usage.get_mut(&server_idx) {
            entry.2 = entry.2.saturating_sub(1);
        }
        self.publish_target();
    }

    fn target(&self) -> Option<(String, u16)> {
        let mut per_host: HashMap<(&str, u16), u32> = HashMap::new();
        for (host, port, n) in self.usage.values() {
            *per_host.entry((host.as_str(), *port)).or_default() += n;
        }
        per_host
            .into_iter()
            .filter(|(_, n)| *n > 0)
            .max_by(|a, b| a.1.cmp(&b.1).then_with(|| b.0.cmp(&a.0)))
            .map(|((h, p), _)| (h.to_string(), p))
    }

    fn publish_target(&self) {
        let target = self.target();
        let mut slot = self.shared.target.lock().expect("probe target poisoned");
        if *slot != target {
            *slot = target;
        }
    }

    /// Completed probe samples since the last call.
    pub fn drain(&self) -> Vec<(String, Duration)> {
        std::mem::take(&mut *self.shared.results.lock().expect("probe results poisoned"))
    }
}

impl Drop for RttProber {
    fn drop(&mut self) {
        self.shared.stop.store(true, Ordering::Relaxed);
        if let Some(handle) = &self.thread {
            handle.thread().unpark();
        }
    }
}

fn probe_loop(shared: &ProbeShared) {
    let mut addrs: HashMap<(String, u16), SocketAddr> = HashMap::new();
    while !shared.stop.load(Ordering::Relaxed) {
        let target = shared.target.lock().expect("probe target poisoned").clone();
        if let Some((host, port)) = target {
            let addr = match addrs.get(&(host.clone(), port)) {
                Some(addr) => Some(*addr),
                None => {
                    let resolved = (host.as_str(), port).to_socket_addrs().ok().and_then(|mut a| a.next());
                    if let Some(addr) = resolved {
                        addrs.insert((host.clone(), port), addr);
                    }
                    resolved
                }
            };
            if let Some(rtt) = addr.and_then(|a| probe_connect(a, PROBE_TIMEOUT)) {
                if shared.stop.load(Ordering::Relaxed) {
                    break;
                }
                shared.results.lock().expect("probe results poisoned").push((host, rtt));
            }
        }
        thread::park_timeout(Duration::from_millis(PROBE_INTERVAL_MS));
    }
}

/// Time one TCP connect; a timeout reads as `timeout`, any other failure as no sample.
fn probe_connect(addr: SocketAddr, timeout: Duration) -> Option<Duration> {
    let start = Instant::now();
    match TcpStream::connect_timeout(&addr, timeout) {
        Ok(stream) => {
            let rtt = start.elapsed();
            drop(stream);
            Some(rtt)
        }
        Err(e) if matches!(e.kind(), std::io::ErrorKind::TimedOut | std::io::ErrorKind::WouldBlock) => {
            Some(timeout)
        }
        Err(_) => None,
    }
}


#[cfg(test)]
mod tests {
    use super::*;

    const MIB: f64 = 1024.0 * 1024.0;
    const TICK: Duration = Duration::from_millis(RATE_TICK_MS);
    const STEP: Duration = Duration::from_millis(10);
    const STEPS_PER_TICK: u32 = (RATE_TICK_MS / 10) as u32;
    const REQ: f64 = MIB;
    const REQ_OVERHEAD_S: f64 = 0.05;
    const REQ_OVERHEAD_JITTER_S: f64 = 0.25;
    const FLOW_CWND: f64 = 64.0 * 1024.0;
    const GREEDY_WINDOW: usize = 24;
    const JITTER_MS: f64 = 2.0;

    /// Bottleneck link: an inelastic competitor takes its rate first; our window-limited flows
    /// share the rest and hold a standing queue of `flows × cwnd − share × base RTT` (capped by
    /// the buffer), so the queue grows with concurrent flows rather than with our rate. Paced
    /// body reads close the receive windows, so a binding cap feeds the link as a fluid: the queue
    /// integrates `(cap − share) / share`, plus a utilization jitter of `JITTER_MS × ρ / (1 − ρ)`,
    /// never above the standing queue.
    struct Link {
        capacity_bps: f64,
        buffer_ms: f64,
        base_ms: f64,
        queue_ms: f64,
        fluid_ms: f64,
    }

    impl Link {
        fn new(capacity_bps: f64) -> Self {
            Self { capacity_bps, buffer_ms: 300.0, base_ms: 7.0, queue_ms: 0.0, fluid_ms: 0.0 }
        }

        fn queue_ms(&self) -> f64 {
            self.queue_ms
        }

        fn step(&mut self, flows: f64, other_bps: f64, secs: f64, limit_bps: f64) -> f64 {
            let share = self.capacity_bps - other_bps;
            if share <= 0.0 {
                self.queue_ms = self.buffer_ms;
                return 0.0;
            }
            let unqueued = flows * FLOW_CWND / (self.base_ms / 1000.0);
            let standing = (flows * FLOW_CWND / share * 1000.0 - self.base_ms).max(0.0).min(self.buffer_ms);
            if unqueued > limit_bps {
                let excess_ms = (limit_bps - share) / share * secs * 1000.0;
                self.fluid_ms = (self.fluid_ms + excess_ms).clamp(0.0, standing);
                let rho = (limit_bps / share).min(0.95);
                self.queue_ms = (self.fluid_ms + JITTER_MS * rho / (1.0 - rho)).min(standing);
                return limit_bps.min(share) * secs;
            }
            let standing = (flows * FLOW_CWND / share * 1000.0 - self.base_ms).max(0.0);
            self.queue_ms = if other_bps > 0.0 { standing.max(0.0) } else { standing }.min(self.buffer_ms);
            self.fluid_ms = self.queue_ms;
            let rtt_s = (self.base_ms + self.queue_ms) / 1000.0;
            (flows * FLOW_CWND / rtt_s).min(share) * secs
        }
    }

    /// Request-level download: up to `window` concurrent 1 MiB requests; each waits a jittered
    /// request overhead, then runs as one window-limited flow whose received bytes are charged to
    /// the pacer, and every flow stops reading while the pacer holds a debt (as on device). Bytes are credited to the
    /// controller only on completion, as the engines do.
    struct Sim {
        link: Link,
        rate: AutoRate,
        pacer: RatePacer,
        now: Instant,
        window: usize,
        window_max: usize,
        seed: u64,
        reqs: Vec<(f64, Instant, Duration, f64)>,
        delivered: u64,
        spike_share: f64,
        external_ms: f64,
        bimodal_ms: f64,
        probes: u32,
        flight_worst: f64,
        events: Vec<(u32, String)>,
        steps: u32,
    }

    struct TickOut {
        cap: f64,
        queue_ms: f64,
        ours_bps: f64,
        delivered_bps: f64,
        binding: bool,
    }

    impl Sim {
        fn new(capacity_bps: f64, window: usize) -> Self {
            let now = Instant::now();
            let link = Link::new(capacity_bps);
            let mut rate = AutoRate::new(now);
            rate.record_rtt("cdn", Duration::from_secs_f64(link.base_ms / 1000.0), now);
            Self {
                link,
                rate,
                pacer: RatePacer::new(now),
                now,
                window: window.min(crate::store_dl::steam::depot_writer::BOOTSTRAP_WINDOW),
                window_max: window,
                seed: 0x9E37_79B9_7F4A_7C15,
                reqs: Vec::new(),
                delivered: 0,
                spike_share: 0.0,
                external_ms: 0.0,
                bimodal_ms: 0.0,
                probes: 0,
                flight_worst: f64::NEG_INFINITY,
                events: Vec::new(),
                steps: 0,
            }
        }

        fn jitter(&mut self) -> f64 {
            self.seed = self.seed.wrapping_mul(6_364_136_223_846_793_005).wrapping_add(1_442_695_040_888_963_407);
            (self.seed >> 11) as f64 / (1u64 << 53) as f64
        }

        fn step(&mut self, other_bps: f64) -> (f64, u32) {
            while self.reqs.len() < self.window {
                let overhead = Duration::from_secs_f64(REQ_OVERHEAD_S + REQ_OVERHEAD_JITTER_S * self.jitter());
                let size = REQ * (0.1 + 0.9 * self.jitter());
                self.reqs.push((size, self.now, overhead, size));
            }
            let active = self.reqs.iter().filter(|r| self.now >= r.1 + r.2).count() as f64;
            let served = self.link.step(active, other_bps, STEP.as_secs_f64(), self.rate.cap_bps());
            let per = if active > 0.0 { served / active } else { 0.0 };
            let started_before = self.now;
            self.now += STEP;
            if served > 0.0 {
                let _ = self.pacer.pace(self.now, served as u64);
                if served >= self.rate.cap_bps() * STEP.as_secs_f64() * 0.999 {
                    self.pacer.stalls.fetch_add(1, Ordering::Relaxed);
                }
            }
            self.steps += 1;
            if self.steps % (STEPS_PER_TICK * 4) == 0 {
                self.window = (self.window * 2).min(self.window_max);
            }
            let mut i = 0;
            while i < self.reqs.len() {
                if started_before < self.reqs[i].1 + self.reqs[i].2 {
                    i += 1;
                    continue;
                }
                self.reqs[i].0 -= per;
                if self.reqs[i].0 <= 1e-6 {
                    self.delivered += self.reqs[i].3 as u64;
                    self.reqs.swap_remove(i);
                } else {
                    i += 1;
                }
            }
            if self.steps % (STEPS_PER_TICK / 4) == 0 {
                let mut rtt = self.link.base_ms + self.link.queue_ms() + self.external_ms;
                self.probes += 1;
                if self.probes % 2 == 0 {
                    rtt += self.bimodal_ms;
                }
                if self.jitter() < self.spike_share {
                    rtt += 4.0 * TARGET_QUEUE_MS * self.jitter();
                }
                self.rate.record_rtt("cdn", Duration::from_secs_f64(rtt / 1000.0), self.now);
            }
            let stalls = self.pacer.take_stalls();
            if let Some(tick) = self.rate.maybe_tick(self.now, self.delivered) {
                if tick.cap_after.is_finite() && tick.rule != "floor" {
                    self.flight_worst = self.flight_worst.max(tick.cap_after - tick.delivered - ADD_MAX_BPS);
                }
                if let Some(event) = tick.event_line("sim") {
                    self.events.push((self.steps / STEPS_PER_TICK, event));
                }
            }
            self.pacer.set_rate(self.now, self.rate.cap_bps());
            (served, stalls)
        }

        fn tick(&mut self, other_bps: f64) -> TickOut {
            let before = self.delivered;
            let mut served = 0.0;
            let mut stalls = 0;
            for _ in 0..STEPS_PER_TICK {
                let (s, st) = self.step(other_bps);
                served += s;
                stalls += st;
            }
            let secs = TICK.as_secs_f64();
            TickOut {
                cap: self.rate.cap_bps(),
                queue_ms: self.link.queue_ms(),
                ours_bps: served / secs,
                delivered_bps: (self.delivered - before) as f64 / secs,
                binding: stalls > 0,
            }
        }
    }

    fn mean(v: impl Iterator<Item = f64>) -> f64 {
        let v: Vec<f64> = v.collect();
        v.iter().sum::<f64>() / v.len() as f64
    }

    #[test]
    fn no_competition_leaves_the_cap_off() {
        let c = 10.0 * MIB;
        let mut sim = Sim::new(c, 2);
        for _ in 0..120 {
            let out = sim.tick(0.0);
            assert!(out.cap.is_infinite());
            assert!(out.queue_ms < 10.0, "queue {}", out.queue_ms);
        }
    }

    #[test]
    fn lone_download_settles_near_the_link_under_jitter_spikes() {
        let c = 10.0 * MIB;
        let mut sim = Sim::new(c, GREEDY_WINDOW);
        sim.spike_share = 0.2;
        let outs: Vec<TickOut> = (0..360).map(|_| sim.tick(0.0)).collect();
        let first_cap = outs.iter().position(|o| o.cap.is_finite()).expect("cap never engaged");
        assert!(first_cap < 30, "engaged at tick {first_cap}");
        let tail = &outs[60..];
        let ours = mean(tail.iter().map(|o| o.ours_bps));
        let q = mean(tail.iter().map(|o| o.queue_ms));
        assert!(ours >= 0.85 * c && ours <= 1.15 * c, "link use {}", ours / c);
        assert!(q <= 1.25 * TARGET_QUEUE_MS, "mean queue {q}");
        let early = mean(outs[60..160].iter().map(|o| o.cap.min(2.0 * c)));
        let late = mean(outs[260..360].iter().map(|o| o.cap.min(2.0 * c)));
        assert!(late >= 0.9 * early, "cap declined {} -> {}", early / MIB, late / MIB);
        for (i, o) in tail.iter().enumerate() {
            assert!(o.cap >= 0.6 * c, "tick {}: cap {}", i + 60, o.cap / MIB);
        }
    }

    #[test]
    fn a_competitor_above_the_floor_takes_its_share_within_ten_ticks() {
        let c = 10.0 * MIB;
        let mut sim = Sim::new(c, GREEDY_WINDOW);
        for _ in 0..120 {
            sim.tick(0.0);
        }
        let outs: Vec<TickOut> = (0..200).map(|_| sim.tick(0.3 * c)).collect();
        let yielded = outs.iter().position(|o| o.cap <= 0.75 * c).expect("cap never yielded");
        assert!(yielded <= 10, "yielded after {yielded} ticks");
        let q = mean(outs[20..].iter().map(|o| o.queue_ms));
        assert!(q < 1.5 * TARGET_QUEUE_MS, "mean queue {q}");
        let bound: Vec<&TickOut> = outs[20..].iter().filter(|o| o.binding).collect();
        assert!(!bound.is_empty());
        let delivered = mean(bound.iter().map(|o| o.delivered_bps));
        let cap = mean(bound.iter().map(|o| o.cap));
        assert!(delivered >= 0.85 * cap, "delivered {} of cap {}", delivered / MIB, cap / MIB);
    }

    #[test]
    fn a_competitor_below_the_floor_stops_the_cap_at_the_floor() {
        let c = 10.0 * MIB;
        let mut sim = Sim::new(c, GREEDY_WINDOW);
        for _ in 0..120 {
            sim.tick(0.0);
        }
        let outs: Vec<TickOut> = (0..40).map(|_| sim.tick(0.75 * c)).collect();
        let floor = sim.rate.floor_bps();
        assert!((floor - FLOOR_BEST_SHARE * sim.rate.best_bps).abs() < 1.0);
        assert!(floor > 0.25 * c, "floor {}", floor / MIB);
        let pinned = outs.iter().position(|o| (o.cap - floor).abs() < 1.0).expect("never reached the floor");
        assert!(pinned <= 20, "reached the floor after {pinned} ticks");
        assert!(outs.iter().filter(|o| o.cap.is_finite()).all(|o| o.cap >= floor - 1.0));
    }

    #[test]
    fn an_external_queue_that_ignores_our_cuts_releases_the_cap() {
        let c = 10.0 * MIB;
        let mut sim = Sim::new(c, GREEDY_WINDOW);
        for _ in 0..120 {
            sim.tick(0.0);
        }
        assert!(sim.rate.cap_bps().is_finite());
        sim.external_ms = 3.0 * TARGET_QUEUE_MS;
        let outs: Vec<TickOut> = (0..240).map(|_| sim.tick(0.0)).collect();
        let released = outs.iter().position(|o| o.cap.is_infinite()).expect("cap never released");
        assert!(released <= 60, "released after {released} ticks");
        let (_, event) = sim.events.iter().find(|(_, e)| e.contains("rule=release")).expect("release event");
        assert!(event.contains("before cuts") && event.contains("after"), "{event}");
        let after = &outs[released + 10..released + 110];
        assert!(after.iter().all(|o| o.cap.is_infinite()), "re-engaged inside the suppression");
        let ours = mean(after.iter().map(|o| o.ours_bps));
        assert!(ours >= 0.9 * c, "link use after release {}", ours / c);
    }

    #[test]
    fn a_queue_we_cause_is_never_released() {
        let c = 10.0 * MIB;
        let mut sim = Sim::new(c, GREEDY_WINDOW);
        sim.spike_share = 0.2;
        for _ in 0..120 {
            sim.tick(0.0);
        }
        sim.rate.engage_cap = sim.rate.cap_bps();
        sim.rate.peak = (sim.rate.cap_bps(), sim.now);
        let engaged = sim.rate.engage_cap;
        assert!(engaged >= 0.8 * c, "cap {}", engaged / MIB);
        let outs: Vec<TickOut> = (0..240).map(|_| sim.tick(0.5 * c)).collect();
        let deep = outs[30..].iter().filter(|o| o.cap <= RELEASE_CAP_SHARE * engaged).count();
        assert!(deep >= 180, "deep for {deep} ticks");
        assert!(outs.iter().all(|o| o.cap.is_finite()), "cap released");
        assert!(!sim.events.iter().any(|(_, e)| e.contains("rule=release")), "{:?}", sim.events);
    }

    #[test]
    fn bimodal_clean_and_one_second_samples_never_lift_the_cap_past_the_link() {
        let c = 10.0 * MIB;
        let mut sim = Sim::new(c, GREEDY_WINDOW);
        sim.bimodal_ms = 1000.0;
        let outs: Vec<TickOut> = (0..300).map(|_| sim.tick(0.0)).collect();
        assert!(outs.iter().any(|o| o.cap.is_finite()), "cap never engaged");
        for (i, o) in outs.iter().enumerate().skip(40) {
            assert!(o.cap.is_infinite() || o.cap <= 1.2 * c, "tick {i}: cap {}", o.cap / MIB);
        }
        let late = mean(outs[200..].iter().map(|o| o.cap.min(2.0 * c)));
        assert!(late <= 1.2 * c, "late cap {}", late / MIB);
        let ours = mean(outs[40..].iter().map(|o| o.ours_bps));
        assert!(ours >= 0.8 * c, "link use {}", ours / c);
    }

    #[test]
    fn cap_never_exceeds_delivered_plus_one_step_through_a_five_times_link_phase() {
        let c = 10.0 * MIB;
        let mut sim = Sim::new(c, GREEDY_WINDOW);
        for _ in 0..120 {
            sim.tick(0.0);
        }
        sim.link.capacity_bps = 5.0 * c;
        let fast: Vec<TickOut> = (0..120).map(|_| sim.tick(0.0)).collect();
        sim.link.capacity_bps = c;
        for _ in 0..120 {
            sim.tick(0.0);
        }
        assert!(fast.iter().all(|o| o.cap.is_infinite() || o.cap <= 5.0 * c));
        assert!(sim.flight_worst <= 1.0, "cap above delivered + step by {} B/s", sim.flight_worst);
    }

    #[test]
    fn base_survives_a_three_minute_standing_queue() {
        let t0 = Instant::now();
        let mut rate = AutoRate::new(t0);
        let (mut now, mut bytes) = (t0, 0u64);
        steady(&mut rate, &mut now, &mut bytes, Duration::from_millis(10), TICK);
        for _ in 0..360 {
            steady(&mut rate, &mut now, &mut bytes, Duration::from_millis(10 + 100), TICK);
        }
        assert_eq!(rate.base_ms(), 10.0);
        assert!((rate.queue_ms() - 100.0).abs() < 1e-6, "queue {}", rate.queue_ms());
    }

    #[test]
    fn a_five_second_gap_does_not_engage_at_the_floor() {
        let t0 = Instant::now();
        let mut rate = AutoRate::new(t0);
        let (mut now, mut bytes) = (t0, 0u64);
        for _ in 0..40 {
            steady(&mut rate, &mut now, &mut bytes, Duration::from_millis(10), TICK);
        }
        for _ in 0..10 {
            now += TICK;
            rate.record_rtt("cdn", Duration::from_millis(10), now);
            rate.maybe_tick(now, bytes);
        }
        for _ in 0..2 {
            now += TICK;
            bytes += 64 * 1024;
            for _ in 0..CURRENT_FILTER {
                rate.record_rtt("cdn", Duration::from_millis(10 + 80), now);
            }
            rate.maybe_tick(now, bytes);
        }
        assert!(rate.cap_bps().is_finite(), "never engaged");
        assert!(rate.delivered_bps < rate.floor_bps(), "2 s window {}", rate.delivered_bps / MIB);
        assert!(rate.cap_bps() > rate.floor_bps() + 1.0, "engaged at the floor {}", rate.cap_bps() / MIB);
        assert!((rate.cap_bps() - ENGAGE_SHARE * rate.delivered_10s_bps).abs() < 1.0, "cap {}", rate.cap_bps() / MIB);
    }

    #[test]
    fn cap_recovers_to_the_link_within_sixty_ticks_once_competition_leaves() {
        let c = 10.0 * MIB;
        let mut sim = Sim::new(c, GREEDY_WINDOW);
        for _ in 0..120 {
            sim.tick(0.0);
        }
        for _ in 0..120 {
            sim.tick(0.5 * c);
        }
        let outs: Vec<TickOut> = (0..240).map(|_| sim.tick(0.0)).collect();
        let back = outs.iter().position(|o| o.cap >= 0.85 * c).expect("cap never recovered");
        assert!(back <= 60, "recovered after {back} ticks");
        let ours = mean(outs[120..].iter().map(|o| o.ours_bps));
        assert!(ours >= 0.85 * c, "link use {}", ours / c);
    }

    fn steady(rate: &mut AutoRate, now: &mut Instant, bytes: &mut u64, rtt: Duration, gap: Duration) {
        *now += gap;
        *bytes += 8 * MIB as u64;
        for _ in 0..CURRENT_FILTER {
            rate.record_rtt("cdn", rtt, *now);
        }
        rate.maybe_tick(*now, *bytes);
    }

    fn engaged(cap: f64) -> (AutoRate, Instant, u64) {
        let t0 = Instant::now();
        let mut rate = AutoRate::new(t0);
        let (mut now, mut bytes) = (t0, 0u64);
        steady(&mut rate, &mut now, &mut bytes, Duration::from_millis(10), TICK);
        rate.cap_bps = cap;
        rate.engage_cap = cap;
        rate.peak = (cap, now);
        (rate, now, bytes)
    }

    #[test]
    fn engages_at_a_share_of_delivered_after_two_ticks_above_target() {
        let t0 = Instant::now();
        let mut rate = AutoRate::new(t0);
        let (mut now, mut bytes) = (t0, 0u64);
        steady(&mut rate, &mut now, &mut bytes, Duration::from_millis(10), TICK);
        steady(&mut rate, &mut now, &mut bytes, Duration::from_millis(80), TICK);
        assert!(rate.cap_bps().is_infinite());
        steady(&mut rate, &mut now, &mut bytes, Duration::from_millis(80), TICK);
        assert!((rate.cap_bps() - rate.delivered_bps * ENGAGE_SHARE).abs() < 1.0, "cap {}", rate.cap_bps() / MIB);
    }

    #[test]
    fn a_decrease_is_proportional_and_an_increase_is_bounded() {
        for (cap, queue_ms, expected) in [
            (10.0, 0.0, 10.5),
            (10.0, 20.0, 10.5),
            (2.0, 0.0, 2.2),
            (2.0, 20.0, 2.1),
            (10.0, 40.0, 10.0),
            (10.0, 60.0, 9.5),
            (10.0, 80.0, 9.0),
            (10.0, 400.0, 9.0),
        ] {
            let (mut rate, mut now, mut bytes) = engaged(cap * MIB);
            steady(&mut rate, &mut now, &mut bytes, Duration::from_secs_f64((10.0 + queue_ms) / 1000.0), TICK);
            assert!((rate.cap_bps() - expected * MIB).abs() < 1.0, "cap {cap} queue {queue_ms}: {}", rate.cap_bps() / MIB);
        }
    }

    #[test]
    fn a_decrease_needs_the_last_four_samples_above_target() {
        let (mut rate, mut now, mut bytes) = engaged(10.0 * MIB);
        now += TICK;
        bytes += 4 * MIB as u64;
        for _ in 0..CURRENT_FILTER - 1 {
            rate.record_rtt("cdn", Duration::from_millis(10 + 200), now);
        }
        rate.maybe_tick(now, bytes);
        assert!(rate.cap_bps() >= 10.0 * MIB, "a spike under four samples decreased: {}", rate.cap_bps() / MIB);
        let before = rate.cap_bps();
        now += TICK;
        bytes += 4 * MIB as u64;
        rate.record_rtt("cdn", Duration::from_millis(10 + 60), now);
        rate.maybe_tick(now, bytes);
        assert!((rate.cap_bps() - before * 0.95).abs() < 1.0, "cap {}", rate.cap_bps() / MIB);
    }

    #[test]
    fn a_stalled_tick_never_decreases() {
        let (mut rate, mut now, mut bytes) = engaged(10.0 * MIB);
        steady(&mut rate, &mut now, &mut bytes, Duration::from_millis(10 + 200), Duration::from_millis(1_500));
        assert_eq!(rate.cap_bps(), 10.0 * MIB);
        steady(&mut rate, &mut now, &mut bytes, Duration::from_millis(10 + 200), TICK);
        assert!(rate.cap_bps() <= 9.0 * MIB + 1.0, "cap {}", rate.cap_bps() / MIB);
    }

    #[test]
    fn a_syn_retransmit_is_only_a_large_queue_sample() {
        let (mut rate, mut now, mut bytes) = engaged(10.0 * MIB);
        for _ in 0..2 {
            now += TICK;
            bytes += 8 * MIB as u64;
            for ms in [1_050, 1_080, 10, 10] {
                rate.record_rtt("cdn", Duration::from_millis(ms), now);
            }
            rate.maybe_tick(now, bytes);
        }
        assert_eq!(rate.base_ms(), 10.0);
        let tick = {
            now += TICK;
            bytes += 8 * MIB as u64;
            for ms in [1_050, 1_080, 1_090, 10] {
                rate.record_rtt("cdn", Duration::from_millis(ms), now);
            }
            rate.maybe_tick(now, bytes).expect("tick")
        };
        assert_eq!(tick.rule, "queue");
        assert!(tick.cap_after >= tick.cap_before * (1.0 - GAIN) - 1.0, "at most one step down: {tick:?}");
    }

    #[test]
    fn disengages_after_ten_seconds_far_above_delivered() {
        let (mut rate, mut now, mut bytes) = engaged(10.0 * MIB);
        rate.best_bps = 20.0 * MIB;
        let mut ticks = 0;
        while rate.cap_bps().is_finite() {
            now += TICK;
            bytes += 256 * 1024;
            for _ in 0..CURRENT_FILTER {
                rate.record_rtt("cdn", Duration::from_millis(10 + 40), now);
            }
            rate.maybe_tick(now, bytes);
            assert!(rate.cap_bps().is_infinite() || rate.cap_bps() >= rate.floor_bps());
            ticks += 1;
            assert!(ticks <= 26, "still engaged after {ticks} ticks");
        }
        assert!(ticks >= 20, "disengaged after {ticks} ticks");
    }

    #[test]
    fn cap_never_below_a_share_of_the_best_ten_second_mean() {
        let (mut rate, mut now, mut bytes) = engaged(10.0 * MIB);
        let mut last = None;
        for _ in 0..30 {
            now += TICK;
            bytes += 8 * MIB as u64;
            rate.record_rtt("cdn", Duration::from_millis(10 + 300), now);
            last = rate.maybe_tick(now, bytes);
        }
        let best = rate.best_bps;
        assert!((best - 16.0 * MIB).abs() < 1.0, "best {}", best / MIB);
        assert_eq!(rate.cap_bps(), FLOOR_BEST_SHARE * best);
        let tick = last.expect("tick");
        assert_eq!(tick.rule, "floor");
        assert!(tick.line("depot=1", 3, 2).contains(" rule=floor "));
    }

    #[test]
    fn cap_never_below_one_mib() {
        let t0 = Instant::now();
        let mut rate = AutoRate::new(t0);
        let (mut now, mut bytes) = (t0, 0u64);
        let mut caps = Vec::new();
        rate.record_rtt("cdn", Duration::from_millis(10), t0);
        for _ in 0..40 {
            now += TICK;
            bytes += 256 * 1024;
            rate.record_rtt("cdn", Duration::from_millis(10 + 300), now);
            rate.maybe_tick(now, bytes);
            caps.push(rate.cap_bps());
        }
        assert!(caps.contains(&RATE_FLOOR_BPS));
        assert!(caps.iter().all(|c| *c >= RATE_FLOOR_BPS));
    }

    #[test]
    fn tick_reports_render_short_lines_and_events_for_non_queue_changes() {
        let t0 = Instant::now();
        let mut rate = AutoRate::new(t0);
        let (mut now, mut bytes) = (t0, 0u64);
        steady(&mut rate, &mut now, &mut bytes, Duration::from_millis(10), TICK);
        steady(&mut rate, &mut now, &mut bytes, Duration::from_millis(80), TICK);
        now += TICK;
        bytes += 4 * MIB as u64;
        for ms in [80, 90, 1_050, 85, 70, 95, 100, 120] {
            rate.record_rtt("cdn", Duration::from_millis(ms), now);
        }
        let tick = rate.maybe_tick(now, bytes).expect("tick");
        assert_eq!(tick.rule, "engage");
        let line = tick.line("depot=1145352", 32, 1234);
        assert!(line.len() <= 200, "{} chars: {line}", line.len());
        assert!(line.starts_with("rate-tick depot=1145352 t=+1500ms q=") && line.contains("samples=[70,80,1040,75,60,85,+2]") && line.contains("cap_before=none"), "{line}");
        let event = tick.event_line("depot=1145352").expect("engage event");
        assert!(event.starts_with("rate-event depot=1145352 t=+1500ms rule=engage cap none -> "), "{event}");
        let (mut rate, mut now, mut bytes) = engaged(10.0 * MIB);
        now += TICK;
        bytes += 4 * MIB as u64;
        rate.record_rtt("cdn", Duration::from_millis(12), now);
        let tick = rate.maybe_tick(now, bytes).expect("tick");
        assert_eq!(tick.rule, "queue");
        assert!(tick.event_line("depot=1").is_none());
    }

    #[test]
    fn a_tick_without_delivered_bytes_defers_instead_of_crushing_the_cap() {
        let t0 = Instant::now();
        let mut rate = AutoRate::new(t0);
        rate.record_rtt("cdn", Duration::from_millis(30), t0 + Duration::from_millis(100));
        rate.maybe_tick(t0 + TICK, 0);
        assert_eq!(rate.last_tick, t0);
        rate.maybe_tick(t0 + TICK * 2, 8 * MIB as u64);
        assert_eq!(rate.last_tick, t0 + TICK * 2);
        assert!(rate.cap_bps().is_infinite());
    }

    #[test]
    fn stale_low_base_ages_out() {
        let t0 = Instant::now();
        let mut rate = AutoRate::new(t0);
        let mut now = t0;
        for _ in 0..20 {
            now += TICK;
            rate.record_rtt("cdn", Duration::from_millis(10), now);
        }
        let low_until = now;
        let mut bytes = 0u64;
        while now.duration_since(low_until) < Duration::from_millis(BASE_HISTORY_MS / 2) {
            steady(&mut rate, &mut now, &mut bytes, Duration::from_millis(50), TICK);
        }
        assert_eq!(rate.base_ms(), 10.0);
        assert!((rate.queue_ms() - 40.0).abs() < 1e-6);
        while now.duration_since(low_until) < Duration::from_millis(BASE_HISTORY_MS + BASE_BUCKET_MS) {
            steady(&mut rate, &mut now, &mut bytes, Duration::from_millis(50), TICK);
        }
        assert_eq!(rate.base_ms(), 50.0);
        assert!(rate.queue_ms() < 1e-6);
    }

    #[test]
    fn bases_are_per_probe_host() {
        let now = Instant::now();
        let mut rate = AutoRate::new(now);
        rate.record_rtt("near", Duration::from_millis(15), now);
        rate.record_rtt("far", Duration::from_millis(90), now);
        rate.maybe_tick(now + TICK, 1);
        assert!(rate.queue_ms() < 1e-6, "a farther host's normal RTT is not queueing");
    }

    #[test]
    fn tick_sample_is_the_minimum_of_the_last_four_probes() {
        let now = Instant::now();
        let mut rate = AutoRate::new(now);
        rate.record_rtt("cdn", Duration::from_millis(20), now);
        rate.maybe_tick(now + TICK, 1);
        for ms in [45, 30, 400, 35] {
            rate.record_rtt("cdn", Duration::from_millis(ms), now + TICK);
        }
        rate.maybe_tick(now + TICK * 2, 2);
        assert!((rate.queue_ms() - 10.0).abs() < 1e-6, "queue {}", rate.queue_ms());
        rate.record_rtt("cdn", Duration::from_millis(500), now + TICK * 2);
        rate.maybe_tick(now + TICK * 3, 3);
        assert!((rate.queue_ms() - 10.0).abs() < 1e-6, "across ticks: queue {}", rate.queue_ms());
        for ms in [300, 200] {
            rate.record_rtt("cdn", Duration::from_millis(ms), now + TICK * 3);
        }
        rate.maybe_tick(now + TICK * 4, 4);
        assert!((rate.queue_ms() - 15.0).abs() < 1e-6, "queue {}", rate.queue_ms());
    }

    #[test]
    fn queue_max_reports_the_peak_probe_since_the_last_line() {
        let now = Instant::now();
        let mut rate = AutoRate::new(now);
        for ms in [7, 9, 68, 12] {
            rate.record_rtt("cdn", Duration::from_millis(ms), now);
        }
        let line = rate.log_fields(0);
        assert!(line.contains(" queue_max=61ms ") && line.contains(" off=0.00 "), "{line}");
        assert!(rate.log_fields(0).contains(" queue_max=0ms "));
    }

    #[test]
    fn pacer_holds_parallel_streams_to_the_cap() {
        let t0 = Instant::now();
        let pacer = RatePacer::new(t0);
        let rate = 4.0 * MIB;
        pacer.set_rate(t0, rate);
        let piece = 16 * 1024u64;
        let mut ready = [t0; 6];
        let mut received = 0u64;
        let mut now = t0;
        let run = Duration::from_secs(5);
        while now.duration_since(t0) < run {
            for r in ready.iter_mut() {
                if now >= *r {
                    received += piece;
                    *r = now + pacer.pace(now, piece);
                }
            }
            now += Duration::from_millis(1);
        }
        let expected = rate * run.as_secs_f64() + rate * BURST_S;
        let got = received as f64;
        assert!((got - expected).abs() <= expected * 0.02, "got {} expected {}", got / MIB, expected / MIB);
    }

    #[test]
    fn unbounded_pacer_admits_everything() {
        let now = Instant::now();
        let pacer = RatePacer::new(now);
        for _ in 0..100 {
            assert_eq!(pacer.pace(now, 8 * MIB as u64), Duration::ZERO);
        }
        assert_eq!(pacer.take_stalls(), 0);
        assert!(AutoRate::new(now).log_fields(0).contains("cap=none"));
    }

    #[test]
    fn pacer_burst_is_a_quarter_second_of_cap_with_a_256k_floor() {
        for (cap, burst) in [(8.0 * MIB, 2.0 * MIB), (0.5 * MIB, 256.0 * 1024.0)] {
            let t0 = Instant::now();
            let pacer = RatePacer::new(t0);
            pacer.set_rate(t0, cap);
            assert_eq!(pacer.pace(t0, burst as u64), Duration::ZERO, "cap {}", cap / MIB);
            let wait = pacer.pace(t0, 1024);
            assert!((wait.as_secs_f64() - 1024.0 / cap).abs() < 1e-6, "cap {}: wait {wait:?}", cap / MIB);
        }
    }

    fn paused_runtime() -> tokio::runtime::Runtime {
        tokio::runtime::Builder::new_current_thread()
            .enable_time()
            .start_paused(true)
            .build()
            .expect("runtime")
    }

    #[test]
    fn paced_body_read_takes_its_bytes_over_the_cap() {
        paused_runtime().block_on(async {
            let cap = 2.0 * MIB;
            let piece = 64 * 1024u64;
            let n = 160u64;
            let pacer = RatePacer::new(clock_now());
            pacer.set_rate(clock_now(), cap);
            let start = tokio::time::Instant::now();
            for _ in 0..n {
                pace_read(&pacer, piece, None).await.expect("not cancelled");
            }
            let took = start.elapsed().as_secs_f64();
            let expect = (n * piece) as f64 / cap;
            assert!(took >= expect * 0.9 && took <= expect * 1.02, "took {took}s expected {expect}s");
            assert!(pacer.take_stalls() > 0);
        });
    }

    #[test]
    fn a_cancel_mid_wait_ends_the_paced_read_promptly() {
        paused_runtime().block_on(async {
            let pacer = RatePacer::new(clock_now());
            pacer.set_rate(clock_now(), MIB);
            let cancel = Arc::new(AtomicBool::new(false));
            let flag = cancel.clone();
            tokio::spawn(async move {
                tokio::time::sleep(Duration::from_millis(200)).await;
                flag.store(true, Ordering::Relaxed);
            });
            let start = tokio::time::Instant::now();
            let res = pace_read(&pacer, 10 * MIB as u64, Some(&cancel)).await;
            assert_eq!(res, Err(Cancelled));
            let took = start.elapsed();
            assert!(took >= Duration::from_millis(200) && took <= Duration::from_millis(200) + CANCEL_POLL, "took {took:?}");
        });
    }

    #[test]
    fn probe_connect_times_a_connect_and_skips_a_refusal() {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        assert!(probe_connect(addr, PROBE_TIMEOUT).is_some_and(|rtt| rtt < PROBE_TIMEOUT));
        drop(listener);
        assert_eq!(probe_connect(addr, PROBE_TIMEOUT), None);
    }

    #[test]
    fn prober_thread_targets_the_busiest_host() {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let mut prober = RttProber::start();
        assert!(prober.target().is_none());
        prober.note_dispatch(0, "https://quiet.example/a");
        prober.note_dispatch(1, &format!("http://127.0.0.1:{port}/a"));
        prober.note_dispatch(2, &format!("http://127.0.0.1:{port}/b"));
        assert_eq!(prober.target(), Some(("127.0.0.1".to_string(), port)));
        let deadline = Instant::now() + Duration::from_secs(2);
        let mut samples = Vec::new();
        while samples.is_empty() && Instant::now() < deadline {
            thread::sleep(Duration::from_millis(10));
            samples = prober.drain();
        }
        assert!(!samples.is_empty());
        assert!(samples.iter().all(|(h, rtt)| h == "127.0.0.1" && *rtt < PROBE_TIMEOUT));
        prober.note_done(1);
        prober.note_done(2);
        prober.note_done(2);
        assert_eq!(prober.target(), Some(("quiet.example".to_string(), 443)));
        prober.note_done(0);
        assert!(prober.target().is_none());
        let shared = prober.shared.clone();
        drop(prober);
        assert!(shared.stop.load(Ordering::Relaxed));
    }
}
