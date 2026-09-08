//! Plan → `FetchItem`s → `crate::fetch_core::run_fetch` with a sink that fills the chunk cache.
//!
//! This is the replacement for the body of the Java pool block in `EpicDownloadManager.install`
//! ("Download unique chunks — 8 parallel threads"). Inputs are exactly what that block sees:
//! the parsed manifest (re-parsed here from the same bytes), the pending file set (indices Java
//! computed after its delta/verify pass), the CDN prefixes (`baseUrl + cloudDir`, cloudflare
//! already skipped) and the chunk cache dir. Output = `<installDir>/.chunks/<GUID>` for every
//! needed chunk; Java assembles the files afterwards exactly as before.

use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Mutex;
use std::time::{Duration, Instant};

use crate::fetch_core::{run_fetch, FetchItem, FetchOptions, FetchSink, SinkError};

use super::manifest::{parse_manifest, Manifest};
use super::plan::{
    cached_chunk_path, chunk_cache_dir, chunk_url, distinct_prefixes, per_host_cap,
    total_credit_bytes, unique_chunks_for_files,
};

/// Java `conn.setReadTimeout(60000)` — the longer of the two Java timeouts (connect was 30 s).
pub const REQUEST_TIMEOUT: Duration = Duration::from_secs(60);

/// Everything the JNI layer hands over for one run.
pub struct EpicRequest {
    pub manifest_bytes: Vec<u8>,
    pub install_dir: String,
    /// `cdn.baseUrl + cdn.cloudDir` per manifest-API CDN entry, in Java's order.
    pub cdn_prefixes: Vec<String>,
    /// Indices into `manifest.files` of Java's `pendingFiles` (post delta/verify), in order.
    pub pending_file_indices: Vec<usize>,
    /// Java's `neededChunks.size()` / `totalBytes` for the plan cross-check (`None` = skip).
    pub expected_chunks: Option<u64>,
    pub expected_bytes: Option<u64>,
    pub ca_bundle_path: String,
    /// Window ceiling: the Steam speed tier's network window (Fast = 32) since improvements
    /// round 1; the Java fallback pool keeps its fixed 8 (`super::JAVA_POOL_THREADS`).
    pub max_workers: usize,
    pub process_workers: usize,
    pub label: String,
}

/// The chunk plan for one run.
#[derive(Debug)]
pub struct EpicPlan {
    pub manifest: Manifest,
    pub cache_dir: PathBuf,
    /// Chunk indices (into `manifest.unique_chunks`) in Java's submission order.
    pub needed: Vec<usize>,
    /// `Σ max(fileSize, 1)` over `needed`.
    pub total_bytes: u64,
    /// Distinct CDN prefixes = fetch-core host keys.
    pub hosts: Vec<String>,
}

/// Terminal result of a run, in the shape Java's pool block needs to reproduce its own exit
/// paths (`CANCELLED during chunk download (n/total chunks)`, `N chunks failed`, `chunksOK=`).
#[derive(Clone, Debug, Default)]
pub struct EpicOutcome {
    pub success: bool,
    pub cancelled: bool,
    pub error: String,
    /// Credited (manifest `max(fileSize,1)`) bytes: skipped-cached + fetched.
    pub bytes_credited: u64,
    /// Chunks accounted for (skipped-cached + fetched), Java's `completedCount`.
    pub chunks_done: u64,
    pub chunks_total: u64,
    pub bytes_total: u64,
    /// Decompressed bytes actually written to the cache this run.
    pub decompressed_written: u64,
}

/// Parse + plan (no I/O beyond reading the cache dir). `Err` = "engine could not start"; Java
/// then runs its own pool, since nothing has been fetched yet.
pub fn build_plan(req: &EpicRequest) -> Result<EpicPlan, String> {
    let manifest = parse_manifest(&req.manifest_bytes).map_err(|e| format!("plan: {e}"))?;
    for &i in &req.pending_file_indices {
        if i >= manifest.files.len() {
            return Err(format!(
                "plan: pending file index {i} out of range ({} files)",
                manifest.files.len()
            ));
        }
    }
    let needed = unique_chunks_for_files(&manifest, &req.pending_file_indices);
    let total_bytes = total_credit_bytes(&manifest, &needed);
    if let Some(expected) = req.expected_chunks {
        if expected != needed.len() as u64 {
            return Err(format!(
                "plan: chunk count mismatch java={expected} rust={}",
                needed.len()
            ));
        }
    }
    if let Some(expected) = req.expected_bytes {
        if expected != total_bytes {
            return Err(format!(
                "plan: byte total mismatch java={expected} rust={total_bytes}"
            ));
        }
    }
    let hosts = distinct_prefixes(&req.cdn_prefixes);
    if hosts.is_empty() {
        return Err("plan: no CDN prefixes".to_string());
    }
    let cache_dir = chunk_cache_dir(&req.install_dir);
    std::fs::create_dir_all(&cache_dir)
        .map_err(|e| format!("plan: mkdirs {}: {e}", cache_dir.display()))?;
    Ok(EpicPlan {
        manifest,
        cache_dir,
        needed,
        total_bytes,
        hosts,
    })
}

/// Sink: one downloaded body → verified cache file. `id` indexes `fetch_chunks`.
struct ChunkCacheSink<'a> {
    plan: &'a EpicPlan,
    /// Chunk indices (into `manifest.unique_chunks`) of the items actually being fetched.
    fetch_chunks: &'a [usize],
    decompressed: AtomicU64,
}

impl<'a> FetchSink for ChunkCacheSink<'a> {
    fn process(&self, item: &FetchItem, body: Vec<u8>) -> Result<u64, SinkError> {
        let Some(&ci) = self.fetch_chunks.get(item.id as usize) else {
            return Err(SinkError::Fatal(format!("item id {} out of range", item.id)));
        };
        let chunk = &self.plan.manifest.unique_chunks[ci];
        let final_path = cached_chunk_path(&self.plan.cache_dir, chunk);
        match super::chunk::write_verified_chunk(&body, chunk.verifiable_sha1(), &final_path) {
            Ok(written) => {
                self.decompressed.fetch_add(written, Ordering::Relaxed);
                Ok(chunk.credit_bytes())
            }
            // Every per-attempt failure in Java is "try the next CDN"; the core's Retry rotates
            // hosts and backs off the same way (bounded at its attempt cap).
            Err(reason) => Err(SinkError::Retry(format!("{} {reason}", chunk.guid_str()))),
        }
    }
}

/// Rolling speed sampler for the end-of-run summary: samples at ≥500 ms like the Java pool's
/// `lastSpeedMs` logic, keeps the peak.
struct SpeedMeter {
    start: Instant,
    last_at: Instant,
    last_bytes: u64,
    peak_bps: f64,
}

impl SpeedMeter {
    fn new() -> Self {
        let now = Instant::now();
        Self {
            start: now,
            last_at: now,
            last_bytes: 0,
            peak_bps: 0.0,
        }
    }

    fn sample(&mut self, bytes_now: u64) {
        let now = Instant::now();
        let dt = now.duration_since(self.last_at);
        if dt >= Duration::from_millis(500) {
            let delta = bytes_now.saturating_sub(self.last_bytes) as f64;
            let bps = delta / dt.as_secs_f64();
            if bps > self.peak_bps {
                self.peak_bps = bps;
            }
            self.last_at = now;
            self.last_bytes = bytes_now;
        }
    }

    fn summary(&self, bytes: u64, decompressed: u64, skipped: u64) -> String {
        let secs = self.start.elapsed().as_secs_f64().max(0.001);
        let avg_bps = bytes as f64 / secs;
        format!(
            "summary bytes={bytes} decompressed={decompressed} skipped_chunks={skipped} elapsed={secs:.1}s avg_mbps={:.2} peak_mbps={:.2} avg_MBps={:.2}",
            avg_bps * 8.0 / 1_000_000.0,
            self.peak_bps * 8.0 / 1_000_000.0,
            avg_bps / (1024.0 * 1024.0)
        )
    }
}

/// Run the plan. `progress(bytes_done, chunks_done)` fires once per accounted chunk (cached-skip
/// or fetched), exactly the cadence of the Java pool's per-task `progress(...)` call. `log`
/// receives engine lines (`fetch-window`, `summary`, failures).
pub fn run_plan(
    plan: &EpicPlan,
    req: &EpicRequest,
    cancel: &AtomicBool,
    progress: &(dyn Fn(u64, u64) + Sync),
    log: &(dyn Fn(&str) + Sync),
) -> EpicOutcome {
    let chunks_total = plan.needed.len() as u64;
    let mut outcome = EpicOutcome {
        chunks_total,
        bytes_total: plan.total_bytes,
        ..EpicOutcome::default()
    };
    let host_cap = per_host_cap(req.max_workers, plan.hosts.len());
    log(&format!(
        "plan chunk_dir={} files_pending={} chunks={} bytes={} hosts={} workers={} per_host_cap={host_cap} process_workers={}",
        plan.manifest.chunk_dir,
        req.pending_file_indices.len(),
        chunks_total,
        plan.total_bytes,
        plan.hosts.len(),
        req.max_workers,
        req.process_workers
    ));

    // Java: each pool task first checks the cancel flag, then `cachedFile.exists()` → credit
    // without fetching. Account for the cached chunks up front (same credit, same callback).
    let mut fetch_chunks: Vec<usize> = Vec::with_capacity(plan.needed.len());
    let mut pre_bytes: u64 = 0;
    let mut pre_chunks: u64 = 0;
    for &ci in &plan.needed {
        if cancel.load(Ordering::Relaxed) {
            outcome.cancelled = true;
            outcome.error = "cancelled".to_string();
            outcome.bytes_credited = pre_bytes;
            outcome.chunks_done = pre_chunks;
            log(&format!(
                "cancelled before fetch ({pre_chunks}/{chunks_total} chunks)"
            ));
            return outcome;
        }
        let chunk = &plan.manifest.unique_chunks[ci];
        if cached_chunk_path(&plan.cache_dir, chunk).exists() {
            pre_bytes += chunk.credit_bytes();
            pre_chunks += 1;
            progress(pre_bytes, pre_chunks);
        } else {
            fetch_chunks.push(ci);
        }
    }
    for (i, h) in plan.hosts.iter().enumerate() {
        log(&format!("host[{i}]={h}"));
    }
    log(&format!(
        "skip cached={pre_chunks} bytes={pre_bytes} to_fetch={}",
        fetch_chunks.len()
    ));

    if fetch_chunks.is_empty() {
        outcome.success = true;
        outcome.bytes_credited = pre_bytes;
        outcome.chunks_done = pre_chunks;
        log("summary bytes=0 decompressed=0 elapsed=0.0s avg_mbps=0.00 peak_mbps=0.00 (nothing to fetch)");
        return outcome;
    }

    let items: Vec<FetchItem> = fetch_chunks
        .iter()
        .enumerate()
        .map(|(id, &ci)| {
            let chunk = &plan.manifest.unique_chunks[ci];
            FetchItem {
                id: id as u64,
                urls: plan
                    .hosts
                    .iter()
                    .map(|h| chunk_url(h, &plan.manifest.chunk_dir, chunk))
                    .collect(),
                reserve: if chunk.file_size > 0 {
                    chunk.file_size as u64
                } else {
                    0
                },
                range: None,
            }
        })
        .collect();

    let opts = FetchOptions {
        max_workers: req.max_workers.max(1),
        // Improvements round 1: the tier ceiling split across the distinct CDNs (floor 6), so
        // the whole window is reachable on 1 host or on all 3.
        per_host_cap: host_cap,
        timeout: REQUEST_TIMEOUT,
        headers: vec![("User-Agent".to_string(), super::USER_AGENT.to_string())],
        ca_bundle_path: req.ca_bundle_path.clone(),
        process_workers: req.process_workers.max(1),
        label: req.label.clone(),
        // Whole-body mode (Epic chunks are ≤ ~1 MiB compressed; the core's byte budget scales
        // with the window); `stream` and any future field keep the core's defaults.
        ..FetchOptions::default()
    };

    let sink = ChunkCacheSink {
        plan,
        fetch_chunks: &fetch_chunks,
        decompressed: AtomicU64::new(0),
    };

    let meter = Mutex::new(SpeedMeter::new());
    let progress_adapter = |bytes: u64, items_ok: u64| {
        if let Ok(mut m) = meter.lock() {
            m.sample(bytes);
        }
        progress(pre_bytes + bytes, pre_chunks + items_ok);
    };

    let fetched = run_fetch(
        items,
        &plan.hosts,
        &opts,
        &sink,
        cancel,
        &progress_adapter,
        log,
    );

    let decompressed = sink.decompressed.load(Ordering::Relaxed);
    outcome.bytes_credited = pre_bytes + fetched.bytes_credited;
    outcome.chunks_done = pre_chunks + fetched.items_ok;
    outcome.decompressed_written = decompressed;
    if let Ok(m) = meter.lock() {
        log(&m.summary(fetched.bytes_credited, decompressed, pre_chunks));
    }

    if fetched.cancelled {
        outcome.cancelled = true;
        outcome.error = "cancelled".to_string();
        log(&format!(
            "cancelled during chunk download ({}/{chunks_total} chunks)",
            outcome.chunks_done
        ));
        return outcome;
    }
    if let Some(err) = fetched.error {
        outcome.error = err;
        log(&format!(
            "FAIL {} ({}/{chunks_total} chunks ok)",
            outcome.error, outcome.chunks_done
        ));
        return outcome;
    }
    if fetched.items_ok != fetch_chunks.len() as u64 {
        outcome.error = format!(
            "fetch ended with {}/{} chunks",
            fetched.items_ok,
            fetch_chunks.len()
        );
        log(&format!("FAIL {}", outcome.error));
        return outcome;
    }
    outcome.success = true;
    log(&format!("chunksOK={}", outcome.chunks_done));
    outcome
}

#[cfg(test)]
mod tests {
    use super::super::manifest::test_support::*;
    use super::*;

    fn request(dir: &str, pending: Vec<usize>) -> EpicRequest {
        EpicRequest {
            manifest_bytes: build_manifest(&sample_chunks(), &sample_files(), 21, true),
            install_dir: dir.to_string(),
            cdn_prefixes: vec![
                "https://fastly-download.epicgames.com/Builds/o/x/default".to_string(),
                "https://download.epicgames.com/Builds/o/x/default".to_string(),
                "https://fastly-download.epicgames.com/Builds/o/x/default".to_string(),
            ],
            pending_file_indices: pending,
            expected_chunks: None,
            expected_bytes: None,
            ca_bundle_path: String::new(),
            max_workers: super::super::JAVA_POOL_THREADS,
            process_workers: 2,
            label: "epic test".to_string(),
        }
    }

    #[test]
    fn plan_matches_java_for_the_pending_set() {
        let dir = super::super::chunk::test_support::temp_dir("plan");
        let req = request(dir.to_str().unwrap(), vec![0, 1]);
        let plan = build_plan(&req).unwrap();
        assert_eq!(plan.needed, vec![0, 1]);
        assert_eq!(plan.total_bytes, 700_000 + 1);
        assert_eq!(plan.hosts.len(), 2, "duplicate CDN prefix collapsed");
        assert!(plan.cache_dir.ends_with(".chunks"));
        assert!(plan.cache_dir.is_dir());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn plan_cross_check_rejects_mismatches() {
        let dir = super::super::chunk::test_support::temp_dir("xcheck");
        let mut req = request(dir.to_str().unwrap(), vec![0, 1]);
        req.expected_chunks = Some(3);
        assert!(build_plan(&req).unwrap_err().contains("chunk count mismatch"));
        req.expected_chunks = Some(2);
        req.expected_bytes = Some(5);
        assert!(build_plan(&req).unwrap_err().contains("byte total mismatch"));
        req.expected_bytes = Some(700_001);
        assert!(build_plan(&req).is_ok());
        req.pending_file_indices = vec![7];
        assert!(build_plan(&req).unwrap_err().contains("out of range"));
        req.pending_file_indices = vec![0];
        req.cdn_prefixes.clear();
        assert!(build_plan(&req).unwrap_err().contains("no CDN"));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn fully_cached_plan_completes_without_fetching() {
        let dir = super::super::chunk::test_support::temp_dir("cached");
        let req = request(dir.to_str().unwrap(), vec![0]);
        let plan = build_plan(&req).unwrap();
        for &ci in &plan.needed {
            std::fs::write(
                cached_chunk_path(&plan.cache_dir, &plan.manifest.unique_chunks[ci]),
                b"cached",
            )
            .unwrap();
        }
        let cancel = AtomicBool::new(false);
        let calls = Mutex::new(Vec::new());
        let progress = |b: u64, c: u64| calls.lock().unwrap().push((b, c));
        let log = |_: &str| {};
        let out = run_plan(&plan, &req, &cancel, &progress, &log);
        assert!(out.success);
        assert_eq!(out.chunks_done, 2);
        assert_eq!(out.bytes_credited, 700_001);
        assert_eq!(*calls.lock().unwrap(), vec![(700_000, 1), (700_001, 2)]);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn cancel_before_fetch_reports_cancelled() {
        let dir = super::super::chunk::test_support::temp_dir("cancel");
        let req = request(dir.to_str().unwrap(), vec![0]);
        let plan = build_plan(&req).unwrap();
        let cancel = AtomicBool::new(true);
        let progress = |_: u64, _: u64| {};
        let log = |_: &str| {};
        let out = run_plan(&plan, &req, &cancel, &progress, &log);
        assert!(out.cancelled);
        assert!(!out.success);
        let _ = std::fs::remove_dir_all(&dir);
    }
}
