//! Active CDN server probing: find faster steamcontent caches than the set Steam assigned.
//!
//! Steam's Content Server Directory assigns ~16 servers by client-IP geolocation; the in-download
//! scheduler can only rank WITHIN that set. But steamcontent cache hostnames are predictable
//! (`cache<N>-<metro>1.steamcontent.com`), the caches are interchangeable backends for depot chunk
//! GETs, and the TLS cert covers the shared domain — so we can probe caches Steam didn't assign,
//! on the user's real network, and merge the winners into the download's server pool.
//!
//! Probe design (why throughput, not ping): latency measures distance, throughput measures how
//! much Valve lets a host give you — on-device, alibaba out-delivered geographically closer hkg
//! caches 13 MB/s to 1-2. Each candidate gets a real 256 KiB range GET of an actual depot chunk;
//! we record connect+TTFB and body throughput.
//!
//! Results are cached on disk (`<install>/.DepotDownloader/cdn_probe_cache.json`) keyed by the
//! assigned-server-set hash (a network change usually changes the assigned set, which re-keys the
//! cache naturally). The cache refreshes when ANY of these hold:
//!   1. age > [`PROBE_CACHE_TTL_SECS`],
//!   2. the assigned server set changed (key mismatch),
//!   3. a cached winner was marked bad since — see [`mark_bad`]: a host that stalls/errors
//!      repeatedly during a download is recorded, and the NEXT download re-probes to replace it
//!      immediately instead of waiting out the TTL.

use std::fs;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use futures_util::stream::FuturesUnordered;
use futures_util::StreamExt;
use sha1::Digest;

use crate::cdn_client::{hex_encode, AsyncCdnClient};
use crate::content_manifest::ContentManifest;
use crate::depot_writer::DepotLogCallback;
use crate::pb::ccontentserverdirectory::CContentServerDirectoryServerInfo;

/// Probe result validity. 6 h balances "CDN conditions drift" against "probe before every depot".
pub const PROBE_CACHE_TTL_SECS: u64 = 6 * 3600;
/// Bytes fetched per probed host — big enough to measure throughput past slow-start, small enough
/// that probing ~40 hosts costs a few seconds and ~10 MB total.
pub const PROBE_RANGE_BYTES: u64 = 256 * 1024;
/// Whole-probe deadline per host (connect + TTFB + body). A host that can't move 256 KiB in this
/// time is never a useful download server.
pub const PROBE_HOST_TIMEOUT: Duration = Duration::from_secs(10);
/// Concurrent probes (they are tiny; the goal is a fast answer, not link saturation).
pub const PROBE_CONCURRENCY: usize = 16;
/// How many probed-but-unassigned winners get merged into the download's server pool.
pub const PROBE_WINNER_COUNT: usize = 4;
/// A host with this many CONSECUTIVE errors mid-download is marked bad in the cache (the early
/// refresh trigger). One error is CDN noise; two in a row is a pattern worth re-probing.
pub const PROBE_BAD_AFTER_CONSECUTIVE_ERRORS: u32 = 2;
/// For each metro seen in the assigned set, probe cache host IDs 1..=this (Steam assigns ~12 of a
/// metro's caches; there are usually more, and one unassigned sibling may be idle and fast).
pub const PROBE_METRO_HOST_MAX_ID: u32 = 24;
/// Cross-region metros to sample (a few host IDs each): the VPN-report fix — if a neighbouring
/// metro's caches are faster from the user's real network, we find them without a VPN.
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

fn now_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

/// Cache key: hash of the sorted assigned host list — a new network (or VPN) yields a different
/// assigned set, which naturally invalidates the old probe.
fn cache_key(servers: &[CContentServerDirectoryServerInfo]) -> String {
    let mut hosts: Vec<&str> = servers
        .iter()
        .map(|s| {
            if s.vhost.is_empty() {
                s.host.as_str()
            } else {
                s.vhost.as_str()
            }
        })
        .collect();
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

/// Whether the cache can serve this download as-is: fresh, same network key, and none of its
/// winners marked bad since (a bad winner means a re-probe is due BEFORE the TTL expires).
fn cache_usable(cache: &ProbeCache, key: &str, winner_count: usize) -> bool {
    if cache.key != key {
        return false;
    }
    if now_secs().saturating_sub(cache.probed_at) > PROBE_CACHE_TTL_SECS {
        return false;
    }
    // Rank WITHOUT the bad filter: would any of the hosts we'd merge be ones since marked bad?
    // (Filtering bad out first would just promote the next host and never notice the re-probe
    // is due — the whole point is replacing the bad winner BEFORE the TTL expires.)
    let prospective = pick_winners(&cache.results, &[], &[], winner_count);
    !prospective
        .iter()
        .any(|w| cache.bad.iter().any(|b| b == &w.host))
}

/// Median probe throughput of the ASSIGNED set: the bar a foreign cache must beat to earn a pool
/// slot. Device evidence (DMC5 run): top-4-unconditional merging pulled in 0.2 MB/s Sydney caches
/// that each absorbed up to per-host-cap permits while the one host that scales (alibaba) starved
/// — probing must be additive in VALUE, not just in host count. `None` when no assigned host
/// produced a sample (shouldn't happen — assigned hosts are probed too; then don't gate).
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

/// Candidate probe set: every assigned host, same-metro siblings (`cache1..24-<metro>` for each
/// metro present in the assigned set), and a few host IDs per cross-region metro.
pub fn probe_candidates(servers: &[CContentServerDirectoryServerInfo]) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    let mut push = |host: String| {
        if !out.contains(&host) {
            out.push(host);
        }
    };
    let mut metros: Vec<String> = Vec::new();
    for s in servers {
        let host = if s.vhost.is_empty() { &s.host } else { &s.vhost };
        if host.is_empty() {
            continue;
        }
        push(host.to_string());
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

/// Maybe extend the download's server pool with probed winners. Honours the on-disk cache (TTL /
/// network-key / bad-winner rules above); runs a live probe only when the cache can't serve.
/// Always returns at least the assigned set — probing is strictly additive and best-effort: any
/// failure leaves the download exactly as it was.
pub fn maybe_extend_servers(
    install_dir: &str,
    ca_bundle_path: &str,
    servers: &[CContentServerDirectoryServerInfo],
    manifests: &[&ContentManifest],
    log: Option<DepotLogCallback<'_>>,
    cancel: Option<&AtomicBool>,
) -> Vec<CContentServerDirectoryServerInfo> {
    let mut out = servers.to_vec();
    let path = cache_path(install_dir);
    let key = cache_key(servers);
    let assigned_hosts: Vec<String> = servers
        .iter()
        .map(|s| {
            if s.vhost.is_empty() {
                s.host.clone()
            } else {
                s.vhost.clone()
            }
        })
        .collect();

    let cached = load_cache(&path).filter(|c| cache_usable(c, &key, PROBE_WINNER_COUNT));
    let (results, from_cache) = match cached {
        Some(cache) => (cache.results, true),
        None => {
            let Some(url_path) = sample_chunk_path(manifests, "") else {
                return out; // nothing downloadable yet — nothing to probe with
            };
            if cancel.is_some_and(|c| c.load(Ordering::Relaxed)) {
                return out;
            }
            let candidates = probe_candidates(servers);
            let results = run_probe(ca_bundle_path, &candidates, &url_path, cancel);
            if cancel.is_some_and(|c| c.load(Ordering::Relaxed)) || results.is_empty() {
                return out;
            }
            // Keep the old bad list: a host marked bad mid-download stays bad until it
            // re-proves itself (it's IN this fresh result set only if it served a chunk).
            let bad = load_cache(&path).map(|c| c.bad).unwrap_or_default();
            let bad: Vec<String> = bad
                .into_iter()
                .filter(|h| results.iter().any(|r| &r.host == h))
                .collect();
            save_cache(
                &path,
                &ProbeCache {
                    key: key.clone(),
                    probed_at: now_secs(),
                    results: results.clone(),
                    bad,
                },
            );
            (results, false)
        }
    };

    let median = assigned_median_bps(&results, &assigned_hosts);
    let winners: Vec<ProbeResult> = pick_winners(&results, &[], &assigned_hosts, PROBE_WINNER_COUNT)
        .into_iter()
        .filter(|w| median.is_none_or(|m| w.bytes_per_sec > m))
        .collect();
    if let Some(log) = log {
        let summary = winners
            .iter()
            .map(|w| format!("{} {:.1}MB/s ttfb={}ms", w.host, w.bytes_per_sec as f64 / 1_048_576.0, w.ttfb_ms))
            .collect::<Vec<_>>()
            .join(" ");
        log(&format!(
            "cdn-probe cached={from_cache} probed={} winners={} gate={:.1}MB/s: {summary}",
            results.len(),
            winners.len(),
            median.unwrap_or(0) as f64 / 1_048_576.0,
        ));
    }
    for winner in winners {
        out.push(CContentServerDirectoryServerInfo {
            host: winner.host,
            https_support: "mandatory".into(),
            ..Default::default()
        });
    }
    out
}

/// Record a host as bad (called from the fetch driver after repeated consecutive errors — the
/// hang signature). The next download re-probes early to replace it instead of trusting the
/// cache until TTL. Best-effort: never fails the download over a cache write.
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

    #[test]
    fn assigned_median_gates_below_par_winners() {
        // The DMC5 device run: assigned set medians ~1.5 MB/s, Sydney winners probed 0.2 MB/s →
        // gate must drop all four so the pool stays at the known-good assigned set.
        let results = vec![
            result("hkg-a", 2_000_000),
            result("hkg-b", 1_500_000),
            result("hkg-c", 1_000_000),
            result("syd-1", 200_000),
            result("syd-2", 190_000),
            result("nrt-1", 3_000_000), // genuinely faster foreign cache: must pass
        ];
        let assigned = vec!["hkg-a".into(), "hkg-b".into(), "hkg-c".into()];
        let median = assigned_median_bps(&results, &assigned).unwrap();
        assert_eq!(median, 1_500_000);
        let winners: Vec<_> = pick_winners(&results, &[], &assigned, 4)
            .into_iter()
            .filter(|w| w.bytes_per_sec > median)
            .collect();
        assert_eq!(winners.len(), 1);
        assert_eq!(winners[0].host, "nrt-1");
        // No assigned samples at all → no gate (None), winners unfiltered.
        assert!(assigned_median_bps(&[result("x", 5)], &["absent".into()]).is_none());
    }

    fn result(host: &str, bps: u64) -> ProbeResult {
        ProbeResult {
            host: host.into(),
            ttfb_ms: 50,
            bytes_per_sec: bps,
        }
    }

    #[test]
    fn metro_of_parses_cache_pattern_hosts_only() {
        assert_eq!(metro_of("cache7-hkg1.steamcontent.com"), Some("hkg1".into()));
        assert_eq!(metro_of("cache12-nrt1.steamcontent.com"), Some("nrt1".into()));
        assert_eq!(metro_of("alibaba.cdn.steampipe.steamcontent.com"), None);
        assert_eq!(metro_of("steampipe.akamaized.net"), None);
        assert_eq!(metro_of("cache7-hkg1"), Some("hkg1".into())); // suffix still parses
    }

    #[test]
    fn candidates_include_assigned_siblings_and_cross_region_without_dupes() {
        let servers = vec![
            srv("cache7-hkg1.steamcontent.com"),
            srv("alibaba.cdn.steampipe.steamcontent.com"),
        ];
        let candidates = probe_candidates(&servers);
        assert!(candidates.contains(&"cache7-hkg1.steamcontent.com".to_string()));
        assert!(candidates.contains(&"cache1-hkg1.steamcontent.com".to_string()));
        assert!(candidates.contains(&"cache24-hkg1.steamcontent.com".to_string()));
        assert!(candidates.contains(&"cache1-nrt1.steamcontent.com".to_string()));
        assert!(candidates.contains(&"cache6-dxb1.steamcontent.com".to_string()));
        // Assigned host appears exactly once; alibaba has no metro so spawns no siblings.
        assert_eq!(
            candidates
                .iter()
                .filter(|h| *h == "cache7-hkg1.steamcontent.com")
                .count(),
            1
        );
        assert!(!candidates
            .iter()
            .any(|h| h.starts_with("cache1-alibaba")));
    }

    #[test]
    fn winners_rank_by_throughput_and_exclude_bad_and_assigned() {
        let results = vec![
            result("slow", 1_000_000),
            result("fast", 20_000_000),
            result("mid", 10_000_000),
            result("badhost", 50_000_000),
            result("assigned", 60_000_000),
        ];
        let bad = vec!["badhost".to_string()];
        let assigned = vec!["assigned".to_string()];
        let winners = pick_winners(&results, &bad, &assigned, 2);
        assert_eq!(winners.len(), 2);
        assert_eq!(winners[0].host, "fast");
        assert_eq!(winners[1].host, "mid");
    }

    #[test]
    fn cache_usability_follows_ttl_key_and_bad_winner_rules() {
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
        assert!(cache_usable(&loaded, "k", 1));
        assert!(!cache_usable(&loaded, "other-key", 1), "network change re-probes");

        let stale = ProbeCache {
            probed_at: now_secs() - PROBE_CACHE_TTL_SECS - 60,
            ..load_cache(&path).unwrap()
        };
        assert!(!cache_usable(&stale, "k", 1), "TTL expiry re-probes");

        let bad_winner = ProbeCache {
            bad: vec!["b".into()],
            ..load_cache(&path).unwrap()
        };
        assert!(
            !cache_usable(&bad_winner, "k", 1),
            "a winner marked bad re-probes before TTL"
        );
        // ...but a bad NON-winner does not force a refresh.
        let bad_loser = ProbeCache {
            bad: vec!["a".into()], // "a" is not the top-1 winner ("b" is)
            ..load_cache(&path).unwrap()
        };
        assert!(cache_usable(&bad_loser, "k", 1));
        let _ = fs::remove_dir_all(&dir);
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
