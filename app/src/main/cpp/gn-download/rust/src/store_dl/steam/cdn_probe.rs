//! Background CDN server probing: rank the servers Steam ASSIGNED live, and promote
//! predicted foreign caches ONLY when they measurably beat the assigned set.
//!
//! Steam's Content Server Directory assigns ~16 servers by client-IP geolocation. Hostname
//! prediction (`cache<N>-<metro>.steamcontent.com` siblings + cross-region metros) is not a
//! contract and breaks in some regions, so predicted hosts are probed ONLY in the background
//! — an invalid guess simply fails its probe and is discarded. A probed foreign host is
//! promoted into the download's server pool only when its measured throughput beats the
//! assigned-set median: device evidence (DMC5 run) showed unconditional top-4 merging pulled
//! in 0.2 MB/s Sydney caches that each absorbed up to per-host-cap permits while the one host
//! that scales (alibaba) starved — promotion must be additive in VALUE, not just host count.
//!
//! Probe design (why throughput, not ping): latency measures distance, throughput measures how
//! much Valve lets a host give you — on-device, alibaba out-delivered geographically closer hkg
//! caches 13 MB/s to 1-2. Each assigned host gets a real 256 KiB range GET of an actual depot
//! chunk; we record connect+TTFB and body throughput.
//!
//! The probe runs in the BACKGROUND: the download starts immediately on the assigned set, the
//! scheduler picks up the ranking mid-download ([`ProbeHints`]), and a usable on-disk cache
//! (`<install>/.DepotDownloader/cdn_probe_cache.json`, keyed by the assigned-server-set hash —
//! a network change usually changes the assigned set, which re-keys the cache naturally) seeds
//! the ranking synchronously so even the first chunks start from the last known order. The
//! cache refreshes when ANY of these hold:
//!   1. age > [`PROBE_CACHE_TTL_SECS`],
//!   2. the assigned server set changed (key mismatch),
//!   3. a probed host was marked bad since — see [`mark_bad`]: a host that stalls/errors
//!      repeatedly during a download is recorded, and the NEXT download re-probes immediately
//!      instead of waiting out the TTL.

use std::fs;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use futures_util::stream::FuturesUnordered;
use futures_util::StreamExt;
use sha1::Digest;

use crate::store_dl::steam::cdn_client::{hex_encode, AsyncCdnClient};
use crate::store_dl::steam::content_manifest::ContentManifest;
use crate::store_dl::steam::pb::ccontentserverdirectory::CContentServerDirectoryServerInfo;

/// Probe result validity. 6 h balances "CDN conditions drift" against "probe before every depot".
pub const PROBE_CACHE_TTL_SECS: u64 = 6 * 3600;
/// Bytes fetched per probed host — big enough to measure throughput past slow-start, small enough
/// that probing the assigned set costs a couple of seconds and a few MB total.
pub const PROBE_RANGE_BYTES: u64 = 256 * 1024;
/// Whole-probe deadline per host (connect + TTFB + body). A host that can't move 256 KiB in this
/// time is never a useful download server.
pub const PROBE_HOST_TIMEOUT: Duration = Duration::from_secs(10);
/// Concurrent probes (they are tiny; the goal is a fast answer, not link saturation).
pub const PROBE_CONCURRENCY: usize = 16;
/// A host with this many CONSECUTIVE errors mid-download is marked bad in the cache (the early
/// refresh trigger). One error is CDN noise; two in a row is a pattern worth re-probing.
pub const PROBE_BAD_AFTER_CONSECUTIVE_ERRORS: u32 = 2;
/// How many probed-but-unassigned winners may be promoted into the download's server pool.
pub const PROBE_WINNER_COUNT: usize = 4;
/// For each metro seen in the assigned set, probe cache host IDs 1..=this (Steam assigns ~12 of a
/// metro's caches; there are usually more, and one unassigned sibling may be idle and fast).
pub const PROBE_METRO_HOST_MAX_ID: u32 = 24;
/// Cross-region metros to sample (a few host IDs each): if a neighbouring metro's caches are
/// faster from the user's real network, the background probe finds them.
pub const PROBE_CROSS_REGION_METROS: &[&str] = &[
    "nrt1", "sin1", "tpe1", "icn1", "hkg1", "lax1", "sea1", "fra1", "lhr1", "syd1", "bom1", "dxb1",
];
/// Host IDs probed per cross-region metro (wide metros are a lottery; a few tickets each).
pub const PROBE_CROSS_REGION_HOST_MAX_ID: u32 = 6;

const CACHE_FILE_NAME: &str = "cdn_probe_cache.json";

pub fn cache_path(install_dir: &str) -> PathBuf {
    Path::new(install_dir)
        .join(".DepotDownloader")
        .join(CACHE_FILE_NAME)
}

#[derive(Clone, Debug)]
pub struct ProbeResult {
    pub host: String,
    pub ttfb_ms: u64,
    pub bytes_per_sec: u64,
}

#[derive(Clone, Debug, Default)]
struct ProbeCache {
    key: String,
    probed_at: u64,
    results: Vec<ProbeResult>,
    bad: Vec<String>,
}

/// The server address a directory entry is dialed as (vhost overrides host when present).
fn server_addr(s: &CContentServerDirectoryServerInfo) -> &str {
    if s.vhost.is_empty() {
        s.host.as_str()
    } else {
        s.vhost.as_str()
    }
}

fn now_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

/// Cache key: hash of the sorted assigned host list — a new network (or VPN) yields a different
/// assigned set, which naturally invalidates the old probe.
fn cache_key(servers: &[CContentServerDirectoryServerInfo]) -> String {
    let mut hosts: Vec<&str> = servers.iter().map(server_addr).collect();
    hosts.sort_unstable();
    let joined = hosts.join("|");
    let digest = sha1::Sha1::digest(joined.as_bytes());
    hex_encode(&digest[..8])
}

fn load_cache(path: &Path) -> Option<ProbeCache> {
    let raw = fs::read_to_string(path).ok()?;
    let v: serde_json::Value = serde_json::from_str(&raw).ok()?;
    let results = v
        .get("results")?
        .as_array()?
        .iter()
        .filter_map(|r| {
            Some(ProbeResult {
                host: r.get("host")?.as_str()?.to_string(),
                ttfb_ms: r.get("ttfb_ms")?.as_u64()?,
                bytes_per_sec: r.get("bps")?.as_u64()?,
            })
        })
        .collect();
    let bad = v
        .get("bad")
        .and_then(|b| b.as_array())
        .map(|arr| {
            arr.iter()
                .filter_map(|h| h.as_str().map(str::to_string))
                .collect()
        })
        .unwrap_or_default();
    Some(ProbeCache {
        key: v.get("key")?.as_str()?.to_string(),
        probed_at: v.get("probed_at")?.as_u64()?,
        results,
        bad,
    })
}

fn save_cache(path: &Path, cache: &ProbeCache) {
    let results: Vec<serde_json::Value> = cache
        .results
        .iter()
        .map(|r| {
            serde_json::json!({
                "host": r.host,
                "ttfb_ms": r.ttfb_ms,
                "bps": r.bytes_per_sec,
            })
        })
        .collect();
    let v = serde_json::json!({
        "key": cache.key,
        "probed_at": cache.probed_at,
        "results": results,
        "bad": cache.bad,
    });
    if let Some(parent) = path.parent() {
        let _ = fs::create_dir_all(parent);
    }
    let _ = fs::write(path, v.to_string());
}

/// Whether the cache can serve this download as-is: fresh, same network key, has results, and
/// no probed host marked bad since (a bad mark means a re-probe is due BEFORE the TTL expires —
/// the whole point is re-ranking around the bad host immediately).
fn cache_usable(cache: &ProbeCache, key: &str) -> bool {
    if cache.key != key || cache.results.is_empty() {
        return false;
    }
    if now_secs().saturating_sub(cache.probed_at) > PROBE_CACHE_TTL_SECS {
        return false;
    }
    !cache
        .results
        .iter()
        .any(|r| cache.bad.iter().any(|b| b == &r.host))
}

/// Probe candidates: exactly the assigned set (deduped by dialed address). No hostname
/// prediction — what Steam assigned is what we rank.
pub fn probe_candidates(servers: &[CContentServerDirectoryServerInfo]) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for s in servers {
        let host = server_addr(s);
        if !host.is_empty() && !out.iter().any(|h| h == host) {
            out.push(host.to_string());
        }
    }
    out
}

/// `cache7-hkg1.steamcontent.com` → `Some("hkg1")`. None for non-pattern hosts (alibaba, fastly,
/// akamai edges) — their hostnames don't carry a metro we can enumerate siblings for.
fn metro_of(host: &str) -> Option<String> {
    let first = host.split('.').next()?;
    let (_, suffix) = first.split_once('-')?;
    if suffix.len() >= 3 && suffix.chars().all(|c| c.is_ascii_alphanumeric()) {
        Some(suffix.to_string())
    } else {
        None
    }
}

/// Predicted-but-unassigned probe candidates: same-metro siblings (`cache1..=24-<metro>` for
/// each metro present in the assigned set) plus a few host IDs per cross-region metro. Probed
/// ONLY in the background — a wrong guess just fails its probe and is discarded. Assigned
/// hosts are excluded (they are probed via [`probe_candidates`]).
pub fn predicted_candidates(servers: &[CContentServerDirectoryServerInfo]) -> Vec<String> {
    let assigned = probe_candidates(servers);
    let mut out: Vec<String> = Vec::new();
    let mut push = |host: String| {
        if !assigned.contains(&host) && !out.contains(&host) {
            out.push(host);
        }
    };
    let mut metros: Vec<String> = Vec::new();
    for host in &assigned {
        if let Some(metro) = metro_of(host) {
            if !metros.contains(&metro) {
                metros.push(metro);
            }
        }
    }
    for metro in &metros {
        for id in 1..=PROBE_METRO_HOST_MAX_ID {
            push(format!("cache{id}-{metro}.steamcontent.com"));
        }
    }
    for metro in PROBE_CROSS_REGION_METROS {
        for id in 1..=PROBE_CROSS_REGION_HOST_MAX_ID {
            push(format!("cache{id}-{metro}.steamcontent.com"));
        }
    }
    out
}

/// Median probe throughput of the ASSIGNED set: the bar a foreign cache must beat to earn a pool
/// slot. `None` when no assigned host produced a sample (then don't gate — the assigned set is
/// unreachable and any working foreign cache is an improvement).
fn assigned_median_bps(results: &[ProbeResult], assigned_hosts: &[String]) -> Option<u64> {
    let mut bps: Vec<u64> = results
        .iter()
        .filter(|r| assigned_hosts.contains(&r.host))
        .map(|r| r.bytes_per_sec)
        .filter(|&b| b > 0)
        .collect();
    if bps.is_empty() {
        return None;
    }
    bps.sort_unstable();
    Some(bps[bps.len() / 2])
}

/// Top `count` probed hosts by throughput, excluding bad hosts and anything already assigned.
fn pick_winners(
    results: &[ProbeResult],
    bad: &[String],
    assigned_hosts: &[String],
    count: usize,
) -> Vec<ProbeResult> {
    let mut ranked: Vec<&ProbeResult> = results
        .iter()
        .filter(|r| r.bytes_per_sec > 0)
        .filter(|r| !bad.contains(&r.host))
        .filter(|r| !assigned_hosts.contains(&r.host))
        .collect();
    ranked.sort_by(|a, b| b.bytes_per_sec.cmp(&a.bytes_per_sec));
    ranked.into_iter().take(count).cloned().collect()
}

/// Foreign caches worth promoting into the server pool: probed winners that beat the
/// assigned-set median (the dilution guard — see the module doc). Empty when no assigned
/// host was probed AND no foreign host responded either.
pub fn gated_winners(
    results: &[ProbeResult],
    bad: &[String],
    assigned_hosts: &[String],
) -> Vec<ProbeResult> {
    let median = assigned_median_bps(results, assigned_hosts);
    pick_winners(results, bad, assigned_hosts, PROBE_WINNER_COUNT)
        .into_iter()
        .filter(|w| median.is_none_or(|m| w.bytes_per_sec > m))
        .collect()
}

/// Probe one host: range-GET a slice of a real depot chunk, returning TTFB and body throughput.
async fn probe_one(
    client: &reqwest::Client,
    host: &str,
    url_path: &str,
) -> Option<ProbeResult> {
    let url = format!("https://{host}:443{url_path}");
    let started = Instant::now();
    let response = client
        .get(&url)
        .header(reqwest::header::RANGE, format!("bytes=0-{}", PROBE_RANGE_BYTES - 1))
        .timeout(PROBE_HOST_TIMEOUT)
        .send()
        .await
        .ok()?;
    if !response.status().is_success() {
        return None;
    }
    let ttfb = started.elapsed();
    let body_started = Instant::now();
    let body = response.bytes().await.ok()?;
    let body_secs = body_started.elapsed().as_secs_f64().max(0.001);
    if body.is_empty() {
        return None;
    }
    Some(ProbeResult {
        host: host.to_string(),
        ttfb_ms: ttfb.as_millis() as u64,
        bytes_per_sec: (body.len() as f64 / body_secs) as u64,
    })
}

/// Run the full probe on a scratch runtime. Returns results for reachable hosts only, ranked
/// by throughput descending.
fn run_probe(
    ca_bundle_path: &str,
    candidates: &[String],
    url_path: &str,
    cancel: Option<&AtomicBool>,
) -> Vec<ProbeResult> {
    let rt = match tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
    {
        Ok(rt) => rt,
        Err(_) => return Vec::new(),
    };
    rt.block_on(async {
        let shared = match AsyncCdnClient::new(ca_bundle_path, PROBE_CONCURRENCY) {
            Ok(client) => client,
            Err(_) => return Vec::new(),
        };
        let client = shared.raw();
        let mut in_flight = FuturesUnordered::new();
        let mut pending = candidates.iter();
        let mut results = Vec::new();
        loop {
            if cancel.is_some_and(|c| c.load(Ordering::Relaxed)) {
                return Vec::new();
            }
            while in_flight.len() < PROBE_CONCURRENCY {
                let Some(host) = pending.next() else {
                    break;
                };
                in_flight.push(probe_one(client, host, url_path));
            }
            let Some(done) = in_flight.next().await else {
                break;
            };
            if let Some(result) = done {
                results.push(result);
            }
        }
        results.sort_by(|a, b| b.bytes_per_sec.cmp(&a.bytes_per_sec));
        results
    })
}

/// Find the first downloadable chunk of the first resolved manifest — the probe's sample payload
/// (a REAL chunk URL measures the exact path download traffic will take, TLS and auth included).
fn sample_chunk_path(manifests: &[&ContentManifest], cdn_auth_token: &str) -> Option<String> {
    for manifest in manifests {
        let depot_id = manifest.metadata.depot_id;
        for file in &manifest.files {
            if let Some(chunk) = file.chunks.first() {
                let mut path = format!("/depot/{depot_id}/chunk/{}", hex_encode(&chunk.sha));
                if !cdn_auth_token.is_empty() {
                    let sep = if cdn_auth_token.starts_with('?') {
                        ""
                    } else {
                        "?"
                    };
                    path = format!("{path}{sep}{cdn_auth_token}");
                }
                return Some(path);
            }
        }
    }
    None
}

#[derive(Debug, Default)]
struct ProbeState {
    /// Ranked best-first; empty until the first cache/probe lands.
    results: Vec<ProbeResult>,
    bad: Vec<String>,
    /// Foreign caches that beat the assigned-set median — promotable into the server pool.
    winners: Vec<ProbeResult>,
}

/// Shared, live-ranked view of the ASSIGNED CDN servers. Seeded synchronously from the on-disk
/// cache at download start ([`seed_from_cache`]), then refreshed by the background probe thread
/// ([`spawn_background_probe`]); the fetch scheduler and the single-connection path consume it
/// to prioritize the fastest assigned hosts. Consumers track a generation counter so each
/// update is applied exactly once.
#[derive(Debug)]
pub struct ProbeHints {
    state: Mutex<ProbeState>,
    generation: AtomicU64,
    /// True until a fresh ranking (cache or probe) has landed; the background probe only runs
    /// while this is set.
    needs_probe: AtomicBool,
}

impl Default for ProbeHints {
    fn default() -> Self {
        Self {
            state: Mutex::new(ProbeState::default()),
            generation: AtomicU64::new(0),
            needs_probe: AtomicBool::new(true),
        }
    }
}

impl ProbeHints {
    /// Newer state than `seen_generation` — `(generation, results, bad)`, exactly once per update.
    pub fn take_update(&self, seen_generation: u64) -> Option<(u64, Vec<ProbeResult>, Vec<String>)> {
        let generation = self.generation.load(Ordering::Relaxed);
        if generation == seen_generation {
            return None;
        }
        let state = self.state.lock().expect("probe hints poisoned");
        Some((generation, state.results.clone(), state.bad.clone()))
    }

    /// Index into `servers` of the best-ranked assigned host (the single-connection path's
    /// starting server). `None` until a ranking has landed.
    pub fn best_server_index(
        &self,
        servers: &[CContentServerDirectoryServerInfo],
    ) -> Option<usize> {
        if self.generation.load(Ordering::Relaxed) == 0 {
            return None;
        }
        let state = self.state.lock().expect("probe hints poisoned");
        state.results.iter().find_map(|r| {
            if state.bad.iter().any(|b| b == &r.host) {
                return None;
            }
            servers.iter().position(|s| server_addr(s) == r.host)
        })
    }

    fn publish(&self, results: Vec<ProbeResult>, bad: Vec<String>, winners: Vec<ProbeResult>) {
        {
            let mut state = self.state.lock().expect("probe hints poisoned");
            state.results = results;
            state.bad = bad;
            state.winners = winners;
        }
        self.generation.fetch_add(1, Ordering::Relaxed);
        self.needs_probe.store(false, Ordering::Relaxed);
    }

    /// `servers` plus synthetic directory entries for the currently promoted foreign caches
    /// (assigned hosts and bad-marked hosts excluded). The download calls this per depot, so a
    /// background probe that lands mid-run extends the pool from the NEXT depot onward — the
    /// in-flight depot's futures borrow a fixed server slice and can't grow it safely.
    /// Promoted hosts flow through the same EWMA seeding / live-sample / zombie-sweep paths as
    /// assigned ones, so reprioritization toward the fastest CDN is then automatic.
    pub fn extended_servers(
        &self,
        servers: &[CContentServerDirectoryServerInfo],
    ) -> Vec<CContentServerDirectoryServerInfo> {
        let mut out = servers.to_vec();
        if self.generation.load(Ordering::Relaxed) == 0 {
            return out;
        }
        let state = self.state.lock().expect("probe hints poisoned");
        for winner in &state.winners {
            if state.bad.iter().any(|b| b == &winner.host) {
                continue;
            }
            if out.iter().any(|s| server_addr(s) == winner.host) {
                continue;
            }
            out.push(CContentServerDirectoryServerInfo {
                host: winner.host.clone(),
                https_support: "mandatory".into(),
                ..Default::default()
            });
        }
        out
    }
}

/// Create the shared hints, seeded synchronously from a usable on-disk cache (one small file
/// read) so the download's first chunks already follow the last known ranking. When the cache
/// can't serve, the hints stay empty and `needs_probe` is left set for the background probe.
pub fn seed_from_cache(
    install_dir: &str,
    servers: &[CContentServerDirectoryServerInfo],
) -> Arc<ProbeHints> {
    let hints = Arc::new(ProbeHints {
        needs_probe: AtomicBool::new(true),
        ..Default::default()
    });
    let path = cache_path(install_dir);
    let key = cache_key(servers);
    if let Some(cache) = load_cache(&path).filter(|c| cache_usable(c, &key)) {
        let assigned = probe_candidates(servers);
        let winners = gated_winners(&cache.results, &cache.bad, &assigned);
        hints.publish(cache.results, cache.bad, winners);
    }
    hints
}

/// Spawn the background probe thread (no-op when the cache already served a fresh ranking, or
/// when there is no downloadable chunk to sample). Probes the assigned servers AND predicted
/// foreign caches (same-metro siblings + cross-region metros — prediction failures simply
/// don't respond), saves the cache, then publishes the ranking plus the median-gated winners
/// — the running download picks both up via [`ProbeHints`].
/// Deliberately not wired to the download's cancel flag: the probe is tiny (≤ ~10 s worst case)
/// and its result is simply unused when the download has already ended.
pub fn spawn_background_probe(
    hints: &Arc<ProbeHints>,
    install_dir: &str,
    ca_bundle_path: &str,
    servers: &[CContentServerDirectoryServerInfo],
    manifests: &[&ContentManifest],
) {
    if !hints.needs_probe.load(Ordering::Relaxed) {
        return;
    }
    let Some(url_path) = sample_chunk_path(manifests, "") else {
        return; // nothing downloadable yet — nothing to probe with
    };
    let hints = Arc::clone(hints);
    let install_dir = install_dir.to_string();
    let ca_bundle_path = ca_bundle_path.to_string();
    let candidates = probe_candidates(servers);
    let mut all_candidates = candidates.clone();
    all_candidates.extend(predicted_candidates(servers));
    let key = cache_key(servers);
    std::thread::spawn(move || {
        let results = run_probe(&ca_bundle_path, &all_candidates, &url_path, None);
        if results.is_empty() {
            return; // keep needs_probe set: the NEXT download tries again
        }
        // Keep the old bad list: a host marked bad mid-download stays bad until it re-proves
        // itself (it's IN this fresh result set only if it served a chunk).
        let path = cache_path(&install_dir);
        let bad = load_cache(&path).map(|c| c.bad).unwrap_or_default();
        let bad: Vec<String> = bad
            .into_iter()
            .filter(|h| results.iter().any(|r| &r.host == h))
            .collect();
        save_cache(
            &path,
            &ProbeCache {
                key,
                probed_at: now_secs(),
                results: results.clone(),
                bad: bad.clone(),
            },
        );
        let winners = gated_winners(&results, &bad, &candidates);
        hints.publish(results, bad, winners);
    });
}

/// Record a host as bad (called from the fetch driver after repeated consecutive errors — the
/// hang signature). The next download re-probes early to re-rank around it instead of trusting
/// the cache until TTL. Best-effort: never fails the download over a cache write.
pub fn mark_bad(install_dir: &str, host: &str) {
    let path = cache_path(install_dir);
    let mut cache = load_cache(&path).unwrap_or_default();
    if !cache.bad.iter().any(|h| h == host) {
        cache.bad.push(host.to_string());
        save_cache(&path, &cache);
    }
}

/// Whether this host's error streak warrants a mark (exported so the driver holds the threshold).
pub fn should_mark_bad(consecutive_errors: u32) -> bool {
    consecutive_errors == PROBE_BAD_AFTER_CONSECUTIVE_ERRORS
}

#[cfg(test)]
mod tests {
    use super::*;

    fn srv(host: &str) -> CContentServerDirectoryServerInfo {
        CContentServerDirectoryServerInfo {
            host: host.into(),
            ..Default::default()
        }
    }

    fn result(host: &str, bps: u64) -> ProbeResult {
        ProbeResult {
            host: host.into(),
            ttfb_ms: 50,
            bytes_per_sec: bps,
        }
    }

    #[test]
    fn candidates_are_exactly_the_assigned_set_deduped() {
        let servers = vec![
            srv("cache7-hkg1.steamcontent.com"),
            srv("alibaba.cdn.steampipe.steamcontent.com"),
            srv("cache7-hkg1.steamcontent.com"),
        ];
        let candidates = probe_candidates(&servers);
        assert_eq!(
            candidates,
            vec![
                "cache7-hkg1.steamcontent.com".to_string(),
                "alibaba.cdn.steampipe.steamcontent.com".to_string(),
            ]
        );
        // vhost wins over host when present.
        let mut vhosted = srv("cache9-hkg1.steamcontent.com");
        vhosted.vhost = "edge.example.net".into();
        assert_eq!(probe_candidates(&[vhosted]), vec!["edge.example.net"]);
    }

    #[test]
    fn cache_usability_follows_ttl_key_results_and_bad_rules() {
        let dir = std::env::temp_dir().join(format!(
            "cdn-probe-test-{}-{}",
            std::process::id(),
            "ttl"
        ));
        let _ = fs::remove_dir_all(&dir);
        let path = dir.join("probe.json");
        let cache = ProbeCache {
            key: "k".into(),
            probed_at: now_secs(),
            results: vec![result("a", 10), result("b", 20)],
            bad: Vec::new(),
        };
        save_cache(&path, &cache);
        let loaded = load_cache(&path).expect("roundtrip");
        assert!(cache_usable(&loaded, "k"));
        assert!(!cache_usable(&loaded, "other-key"), "network change re-probes");

        let empty = ProbeCache {
            results: Vec::new(),
            ..load_cache(&path).unwrap()
        };
        assert!(!cache_usable(&empty, "k"), "no ranking = nothing to seed");

        let stale = ProbeCache {
            probed_at: now_secs() - PROBE_CACHE_TTL_SECS - 60,
            ..load_cache(&path).unwrap()
        };
        assert!(!cache_usable(&stale, "k"), "TTL expiry re-probes");

        let bad_host = ProbeCache {
            bad: vec!["a".into()],
            ..load_cache(&path).unwrap()
        };
        assert!(
            !cache_usable(&bad_host, "k"),
            "a probed host marked bad re-probes before TTL"
        );
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn hints_publish_once_per_generation_and_rank_best_server() {
        let hints = ProbeHints::default();
        assert!(hints.take_update(0).is_none());
        assert!(hints.needs_probe.load(Ordering::Relaxed));

        hints.publish(
            vec![result("fast", 20_000_000), result("slow", 1_000_000)],
            Vec::new(),
            Vec::new(),
        );
        assert!(!hints.needs_probe.load(Ordering::Relaxed));
        let (gen, results, bad) = hints.take_update(0).expect("first update");
        assert_eq!(gen, 1);
        assert_eq!(results[0].host, "fast");
        assert!(bad.is_empty());
        assert!(hints.take_update(1).is_none(), "same generation not re-applied");

        let servers = vec![srv("slow"), srv("fast")];
        assert_eq!(hints.best_server_index(&servers), Some(1));

        // A bad best host falls through to the next ranked one.
        hints.publish(
            vec![result("fast", 20_000_000), result("slow", 1_000_000)],
            vec!["fast".into()],
            Vec::new(),
        );
        let (gen, _, bad) = hints.take_update(1).expect("second update");
        assert_eq!(gen, 2);
        assert_eq!(bad, vec!["fast".to_string()]);
        assert_eq!(hints.best_server_index(&servers), Some(0));
    }

    #[test]
    fn predicted_candidates_siblings_and_cross_region_exclude_assigned() {
        let servers = vec![
            srv("cache7-hkg1.steamcontent.com"),
            srv("alibaba.cdn.steampipe.steamcontent.com"),
        ];
        let predicted = predicted_candidates(&servers);
        // Same-metro siblings for hkg1 (1..=24), minus the assigned cache7.
        assert!(predicted.contains(&"cache1-hkg1.steamcontent.com".to_string()));
        assert!(predicted.contains(&"cache24-hkg1.steamcontent.com".to_string()));
        assert!(!predicted.contains(&"cache7-hkg1.steamcontent.com".to_string()));
        // Cross-region metros, a few IDs each.
        assert!(predicted.contains(&"cache1-nrt1.steamcontent.com".to_string()));
        assert!(predicted.contains(&"cache6-dxb1.steamcontent.com".to_string()));
        assert!(!predicted.contains(&"cache7-nrt1.steamcontent.com".to_string()));
        // Non-pattern assigned hosts are not "predicted", and nothing is duplicated.
        assert!(!predicted.contains(&"alibaba.cdn.steampipe.steamcontent.com".to_string()));
        let mut dedup = predicted.clone();
        dedup.sort_unstable();
        dedup.dedup();
        assert_eq!(dedup.len(), predicted.len());
    }

    #[test]
    fn gated_winners_require_beating_the_assigned_median() {
        let assigned = vec!["a1".to_string(), "a2".to_string()];
        let results = vec![
            result("a1", 10_000_000),
            result("a2", 20_000_000),
            result("fast-foreign", 30_000_000),
            result("slow-foreign", 5_000_000),
        ];
        // Median of {10, 20} = index 1 of sorted = 20 MB/s.
        let winners = gated_winners(&results, &[], &assigned);
        assert_eq!(winners.len(), 1);
        assert_eq!(winners[0].host, "fast-foreign");

        // Bad-marked winners are excluded even when fast enough.
        let winners = gated_winners(&results, &["fast-foreign".into()], &assigned);
        assert!(winners.is_empty());

        // No assigned samples → no gate: any responsive foreign cache wins.
        let foreign_only = vec![result("fast-foreign", 30_000_000)];
        let winners = gated_winners(&foreign_only, &[], &assigned);
        assert_eq!(winners.len(), 1);
    }

    #[test]
    fn extended_servers_appends_gated_winners_as_synthetic_entries() {
        let hints = ProbeHints::default();
        let servers = vec![srv("cache7-hkg1.steamcontent.com")];
        // No ranking yet → pool unchanged.
        assert_eq!(hints.extended_servers(&servers).len(), 1);

        hints.publish(
            vec![
                result("cache7-hkg1.steamcontent.com", 10_000_000),
                result("cache3-sin1.steamcontent.com", 30_000_000),
            ],
            Vec::new(),
            vec![result("cache3-sin1.steamcontent.com", 30_000_000)],
        );
        let extended = hints.extended_servers(&servers);
        assert_eq!(extended.len(), 2);
        assert_eq!(extended[1].host, "cache3-sin1.steamcontent.com");
        assert_eq!(extended[1].https_support, "mandatory");
        assert!(extended[1].vhost.is_empty());

        // A bad-marked winner is not promoted.
        hints.publish(
            vec![result("cache3-sin1.steamcontent.com", 30_000_000)],
            vec!["cache3-sin1.steamcontent.com".into()],
            vec![result("cache3-sin1.steamcontent.com", 30_000_000)],
        );
        assert_eq!(hints.extended_servers(&servers).len(), 1);
    }

    #[test]
    fn mark_bad_appends_once() {
        let dir = std::env::temp_dir().join(format!(
            "cdn-probe-test-{}-{}",
            std::process::id(),
            "bad"
        ));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        let install = dir.to_string_lossy().into_owned();
        mark_bad(&install, "cache9-hkg1.steamcontent.com");
        mark_bad(&install, "cache9-hkg1.steamcontent.com");
        let cache = load_cache(&cache_path(&install)).expect("cache written");
        assert_eq!(cache.bad.len(), 1);
        let _ = fs::remove_dir_all(&dir);
    }
}
