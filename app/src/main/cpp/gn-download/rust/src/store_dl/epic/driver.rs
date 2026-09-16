//! Plan → `FetchItem`s → `crate::fetch_core::run_fetch` with a sink that STREAMS chunks into the
//! final files (GOG-style): each verified, decompressed chunk is fanned out to its consumer
//! (file, offset) pairs with positioned writes into `<file>.eptmp`, and a file is renamed to its
//! final name the moment its last part lands. There is NO post-download assembly pass and no
//! chunk-cache round-trip — the resume unit is the file (Java's delta/verify excludes completed
//! files from `pending_file_indices`; unfinished `.eptmp` files are deleted on abort, GOG
//! parity). Parallelism is unchanged: the fetch core's process pool does the inflate + writes.
//!
//! This is the replacement for the body of the Java pool block in `EpicDownloadManager.install`
//! ("Download unique chunks — 8 parallel threads") AND for its assembly epilogue. Inputs are
//! exactly what that block sees: the parsed manifest (re-parsed here from the same bytes), the
//! pending file set (indices Java computed after its delta/verify pass), the CDN prefixes
//! (`baseUrl + cloudDir`, cloudflare already skipped). Output = completed game files.

use std::fs::{self, File};
use std::os::unix::fs::FileExt;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering};
use std::sync::Mutex;
use std::time::{Duration, Instant};

use crate::fetch_core::{run_fetch, FetchItem, FetchOptions, FetchSink, SinkError};

use super::manifest::{parse_manifest, Manifest};
use super::plan::{
    chunk_cache_dir, chunk_url, distinct_prefixes, per_host_cap, total_credit_bytes,
    unique_chunks_for_files,
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

/// One consumer write of a chunk: a slice of the DECOMPRESSED chunk bytes destined for one
/// pending file at one offset (Epic chunks are shared across files, so a chunk has 1..N of
/// these — the fan-out the old cache+assembly pass existed for, done inline now).
#[derive(Clone, Copy, Debug)]
struct PartTarget {
    /// Ordinal into `EpicRequest.pending_file_indices` (NOT the manifest file index).
    file_ord: usize,
    src_off: u64,
    dst_off: u64,
    len: u64,
}

/// The chunk plan for one run.
#[derive(Debug)]
pub struct EpicPlan {
    pub manifest: Manifest,
    pub cache_dir: PathBuf,
    /// Chunk indices (into `manifest.unique_chunks`) in Java's submission order.
    pub needed: Vec<usize>,
    /// `Σ max(fileSize, 1)` over `needed` (compressed) — kept for the plan log and the Java
    /// `expected_bytes` cross-check.
    pub total_compressed: u64,
    /// `Σ FileInfo.file_size()` over `pending_file_indices` — the progress TOTAL (uncompressed
    /// installed bytes; the streamed part-writes credit exactly this by completion).
    pub total_bytes: u64,
    /// Distinct CDN prefixes = fetch-core host keys.
    pub hosts: Vec<String>,
    /// Consumer writes per unique-chunk index (empty for chunks no pending file references).
    consumers: Vec<Vec<PartTarget>>,
}

/// Terminal result of a run, in the shape Java's pool block needs to reproduce its own exit
/// paths (`CANCELLED during chunk download (n/total chunks)`, `N chunks failed`, `chunksOK=`).
#[derive(Clone, Debug, Default)]
pub struct EpicOutcome {
    pub success: bool,
    pub cancelled: bool,
    pub error: String,
    /// Credited DECOMPRESSED bytes: skipped-cached (cache-file length) + fetched (inflated size).
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
    let total_compressed = total_credit_bytes(&manifest, &needed);
    let total_bytes: u64 = req
        .pending_file_indices
        .iter()
        .map(|&i| manifest.files[i].file_size())
        .sum();
    if let Some(expected) = req.expected_chunks {
        if expected != needed.len() as u64 {
            return Err(format!(
                "plan: chunk count mismatch java={expected} rust={}",
                needed.len()
            ));
        }
    }
    if let Some(expected) = req.expected_bytes {
        if expected != total_compressed {
            return Err(format!(
                "plan: byte total mismatch java={expected} rust={total_compressed}"
            ));
        }
    }
    let hosts = distinct_prefixes(&req.cdn_prefixes);
    if hosts.is_empty() {
        return Err("plan: no CDN prefixes".to_string());
    }
    let cache_dir = chunk_cache_dir(&req.install_dir);
    // Consumer fan-out: for every pending file walk its parts in order (destination offset =
    // cumulative part sizes) and record which slices of which chunks land in it.
    let by_guid = manifest.chunk_index_by_guid();
    let mut consumers = vec![Vec::new(); manifest.unique_chunks.len()];
    for (ord, &fi) in req.pending_file_indices.iter().enumerate() {
        let mut dst = 0u64;
        for part in &manifest.files[fi].parts {
            let len = (part.size.max(0) as u32) as u64; // Java `size & 0xFFFFFFFFL`
            if let Some(&ci) = by_guid.get(&part.guid_str()) {
                consumers[ci].push(PartTarget {
                    file_ord: ord,
                    src_off: part.offset.max(0) as u64,
                    dst_off: dst,
                    len,
                });
            }
            dst += len;
        }
    }
    Ok(EpicPlan {
        manifest,
        cache_dir,
        needed,
        total_compressed,
        total_bytes,
        hosts,
        consumers,
    })
}

/// `<final>.eptmp` — the in-progress name a file keeps until its last part lands (GOG `.bhtmp`
/// parity): an interrupted run leaves only clearly-temporary files behind, and a stale `.eptmp`
/// from a crashed run is deleted when the file goes pending again.
fn tmp_path_for(out_path: &Path) -> PathBuf {
    let mut s = out_path.as_os_str().to_os_string();
    s.push(".eptmp");
    PathBuf::from(s)
}

/// One pending file's streamed-write state. Positioned `write_all_at` takes `&self`, so the
/// handle is shared by the whole process pool without a lock.
struct StreamFile {
    handle: File,
    tmp_path: PathBuf,
    out_path: PathBuf,
    parts_total: u32,
    parts_written: AtomicU32,
    renamed: AtomicBool,
}

impl StreamFile {
    /// Write one part and, when it was the last outstanding one, publish the file (delete any
    /// stale final, rename tmp → final). Returns true when THIS write completed the file.
    fn write_part(&self, src: &[u8], dst_off: u64) -> Result<bool, String> {
        self.handle
            .write_all_at(src, dst_off)
            .map_err(|e| format!("write {}: {e}", self.tmp_path.display()))?;
        if self.parts_written.fetch_add(1, Ordering::Relaxed) + 1 == self.parts_total {
            let _ = fs::remove_file(&self.out_path);
            fs::rename(&self.tmp_path, &self.out_path)
                .map_err(|e| format!("rename {}: {e}", self.out_path.display()))?;
            self.renamed.store(true, Ordering::Relaxed);
            return Ok(true);
        }
        Ok(false)
    }
}

/// Sink: one downloaded body → verified in-memory decode → positioned writes into every
/// consumer file (the GOG model: assemble during download, no assembly pass afterwards).
struct StreamSink<'a> {
    plan: &'a EpicPlan,
    /// Chunk indices (into `manifest.unique_chunks`) of the items actually being fetched.
    fetch_chunks: &'a [usize],
    files: &'a [StreamFile],
    /// Cumulative part bytes written — drives `assembly_progress` (which now fires DURING the
    /// fetch, so the UI's single credit budget sees steady movement end to end).
    assembled: AtomicU64,
    assembly_progress: &'a (dyn Fn(u64) + Sync),
}

impl<'a> FetchSink for StreamSink<'a> {
    fn process(&self, item: &FetchItem, body: Vec<u8>) -> Result<u64, SinkError> {
        let Some(&ci) = self.fetch_chunks.get(item.id as usize) else {
            return Err(SinkError::Fatal(format!("item id {} out of range", item.id)));
        };
        let chunk = &self.plan.manifest.unique_chunks[ci];
        let data = match super::chunk::decode_verified_chunk(
            &body,
            chunk.verifiable_sha1(),
            if chunk.window_size > 0 {
                Some(chunk.window_size as u64)
            } else {
                None
            },
        ) {
            Ok(data) => data,
            // Every per-attempt failure in Java is "try the next CDN"; the core's Retry rotates
            // hosts and backs off the same way (bounded at its attempt cap).
            Err(reason) => return Err(SinkError::Retry(format!("{} {reason}", chunk.guid_str()))),
        };
        let decompressed_len = data.len() as u64;
        for t in &self.plan.consumers[ci] {
            let file = &self.files[t.file_ord];
            let end = (t.src_off + t.len) as usize;
            let src = data.get(t.src_off as usize..end).ok_or_else(|| {
                SinkError::Fatal(format!(
                    "part slice {end} beyond chunk {} ({} bytes)",
                    chunk.guid_str(),
                    data.len()
                ))
            })?;
            // A disk/FS error is not CDN-curable — fail the run instead of rotating hosts.
            file.write_part(src, t.dst_off).map_err(SinkError::Fatal)?;
            let done = self.assembled.fetch_add(t.len, Ordering::Relaxed) + t.len;
            (self.assembly_progress)(done);
        }
        // Credit the DECOMPRESSED bytes once per chunk (the fetch-side progress contract);
        // the per-part credit above covers the shared-chunk top-up assembly used to add.
        Ok(decompressed_len)
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

/// Run the plan. `progress(bytes_done, chunks_done)` fires once per fetched chunk, exactly the
/// cadence of the Java pool's per-task `progress(...)` call. `assembly_progress(assembled_bytes)`
/// now fires per written file part DURING the fetch (streamed writes, GOG-style) — there is no
/// assembly epilogue. `log` receives engine lines (`fetch-window`, `summary`, failures).
pub fn run_plan(
    plan: &EpicPlan,
    req: &EpicRequest,
    cancel: &AtomicBool,
    progress: &(dyn Fn(u64, u64) + Sync),
    assembly_progress: &(dyn Fn(u64) + Sync),
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
        "plan chunk_dir={} version={} files_pending={} chunks={} bytes_uncompressed={} bytes_compressed={} hosts={} workers={} per_host_cap={host_cap} process_workers={} mode=streamed",
        plan.manifest.chunk_dir,
        plan.manifest.version,
        req.pending_file_indices.len(),
        chunks_total,
        plan.total_bytes,
        plan.total_compressed,
        plan.hosts.len(),
        req.max_workers,
        req.process_workers
    ));

    // Pre-streaming builds left a chunk cache behind; it is dead weight now (the resume unit is
    // the FILE, Java's delta/verify already excludes completed files from the pending set).
    if plan.cache_dir.exists() {
        log("sweeping stale chunk cache from a pre-streaming build");
        let _ = fs::remove_dir_all(&plan.cache_dir);
    }

    if cancel.load(Ordering::Relaxed) {
        outcome.cancelled = true;
        outcome.error = "cancelled".to_string();
        log("cancelled before fetch (0 chunks)");
        return outcome;
    }

    // Stream targets: one `<final>.eptmp` per pending file. A partless file (size 0) is just an
    // empty final file — created here, nothing to fetch or write.
    let install_dir = Path::new(&req.install_dir);
    let mut files: Vec<StreamFile> = Vec::with_capacity(req.pending_file_indices.len());
    for &fi in &req.pending_file_indices {
        let file = &plan.manifest.files[fi];
        let out_path = install_dir.join(&file.filename);
        if let Some(parent) = out_path.parent() {
            if let Err(e) = fs::create_dir_all(parent) {
                outcome.error = format!("mkdirs {}: {e}", parent.display());
                log(&format!("FAIL {}", outcome.error));
                return outcome;
            }
        }
        if file.parts.is_empty() {
            // Partless file (size 0): an empty final file, complete on creation. Pushed as an
            // already-renamed StreamFile so `files` stays 1:1 with `pending_file_indices` (the
            // plan's consumer ordinals index it directly); nothing ever writes to it.
            match File::create(&out_path) {
                Ok(handle) => {
                    log(&format!("empty file {}", file.filename));
                    files.push(StreamFile {
                        handle,
                        tmp_path: out_path.clone(),
                        out_path,
                        parts_total: 0,
                        parts_written: AtomicU32::new(0),
                        renamed: AtomicBool::new(true),
                    });
                }
                Err(e) => {
                    outcome.error = format!("create {}: {e}", out_path.display());
                    log(&format!("FAIL {}", outcome.error));
                    return outcome;
                }
            }
            continue;
        }
        let tmp_path = tmp_path_for(&out_path);
        // A stale tmp from a crashed run must not survive: File::create truncates it anyway.
        match File::create(&tmp_path) {
            Ok(handle) => files.push(StreamFile {
                handle,
                tmp_path,
                out_path,
                parts_total: file.parts.len() as u32,
                parts_written: AtomicU32::new(0),
                renamed: AtomicBool::new(false),
            }),
            Err(e) => {
                outcome.error = format!("create {}: {e}", tmp_path.display());
                log(&format!("FAIL {}", outcome.error));
                return outcome;
            }
        }
    }

    for (i, h) in plan.hosts.iter().enumerate() {
        log(&format!("host[{i}]={h}"));
    }

    // Delete every unfinished tmp on the way out after an error/cancel (GOG `.bhtmp` parity).
    let cleanup_unfinished = |files: &[StreamFile]| {
        for f in files {
            if !f.renamed.load(Ordering::Relaxed) {
                let _ = fs::remove_file(&f.tmp_path);
            }
        }
    };

    if plan.needed.is_empty() {
        outcome.success = true;
        log("summary bytes=0 elapsed=0.0s (nothing to fetch)");
        return outcome;
    }

    let fetch_chunks: &[usize] = &plan.needed;
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

    let sink = StreamSink {
        plan,
        fetch_chunks,
        files: &files,
        assembled: AtomicU64::new(0),
        assembly_progress,
    };

    let meter = Mutex::new(SpeedMeter::new());
    let progress_adapter = |bytes: u64, items_ok: u64| {
        if let Ok(mut m) = meter.lock() {
            m.sample(bytes);
        }
        progress(bytes, items_ok);
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

    let assembled = sink.assembled.load(Ordering::Relaxed);
    outcome.bytes_credited = fetched.bytes_credited;
    outcome.chunks_done = fetched.items_ok;
    outcome.decompressed_written = assembled;
    if let Ok(m) = meter.lock() {
        log(&m.summary(fetched.bytes_credited, assembled, 0));
    }

    if fetched.cancelled {
        outcome.cancelled = true;
        outcome.error = "cancelled".to_string();
        log(&format!(
            "cancelled during chunk download ({}/{chunks_total} chunks)",
            outcome.chunks_done
        ));
        cleanup_unfinished(&files);
        return outcome;
    }
    if let Some(err) = fetched.error {
        outcome.error = err;
        log(&format!(
            "FAIL {} ({}/{chunks_total} chunks ok)",
            outcome.error, outcome.chunks_done
        ));
        cleanup_unfinished(&files);
        return outcome;
    }
    if fetched.items_ok != fetch_chunks.len() as u64 {
        outcome.error = format!(
            "fetch ended with {}/{} chunks",
            fetched.items_ok,
            fetch_chunks.len()
        );
        log(&format!("FAIL {}", outcome.error));
        cleanup_unfinished(&files);
        return outcome;
    }
    let renamed = files
        .iter()
        .filter(|f| f.renamed.load(Ordering::Relaxed))
        .count();
    outcome.success = true;
    log(&format!(
        "chunksOK={} streamOK files={renamed} bytes={assembled}",
        outcome.chunks_done
    ));
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
        // Progress total = uncompressed installed bytes of the pending files:
        // file0 = 1_048_576 + 4000, file1 = 4096.
        assert_eq!(plan.total_bytes, 1_056_672);
        assert_eq!(plan.total_compressed, 700_000 + 1, "Java credit total kept for cross-check");
        assert_eq!(plan.hosts.len(), 2, "duplicate CDN prefix collapsed");
        assert!(plan.cache_dir.ends_with(".chunks"));
        // Consumer fan-out: chunk 0 lands whole in file 0 at offset 0; chunk 1 is SHARED —
        // 4000 bytes at file 0 offset 1_048_576 and 4096 bytes at file 1 offset 0.
        assert_eq!(plan.consumers.len(), plan.manifest.unique_chunks.len());
        assert_eq!(plan.consumers[0].len(), 1);
        assert_eq!(plan.consumers[0][0].file_ord, 0);
        assert_eq!(plan.consumers[0][0].dst_off, 0);
        assert_eq!(plan.consumers[0][0].len, 1_048_576);
        assert_eq!(plan.consumers[1].len(), 2, "shared chunk has two consumers");
        assert_eq!(plan.consumers[1][0].file_ord, 0);
        assert_eq!(plan.consumers[1][0].dst_off, 1_048_576);
        assert_eq!(plan.consumers[1][0].len, 4000);
        assert_eq!(plan.consumers[1][1].file_ord, 1);
        assert_eq!(plan.consumers[1][1].dst_off, 0);
        assert_eq!(plan.consumers[1][1].len, 4096);
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

    /// The streamed model end to end at sink level: chunk bodies in, final files out — shared
    /// chunks fanned out, files renamed on their last part, progress cumulative per part.
    #[test]
    fn sink_streams_chunks_into_final_files() {
        use super::super::chunk::test_support::{build_chunk_body, sha1_of};

        // file a = chunk1[0..4000] + chunk2[0..2000]; file b = chunk2[100..600] (shared chunk).
        let d1 = vec![1u8; 4000];
        let d2: Vec<u8> = (0..3000).map(|i| (i % 251) as u8).collect();
        let b1 = build_chunk_body(&d1, true, 0, None);
        let b2 = build_chunk_body(&d2, true, 0, None);
        let chunks = vec![
            TestChunk {
                guid: [1, 2, 3, 4],
                hash: 0,
                sha1: sha1_of(&d1),
                group: 0,
                window: 4000,
                file_size: b1.len() as u64,
            },
            TestChunk {
                guid: [5, 6, 7, 8],
                hash: 0,
                sha1: sha1_of(&d2),
                group: 0,
                window: 3000,
                file_size: b2.len() as u64,
            },
        ];
        let files = vec![
            TestFile {
                name: "Game/a.bin".to_string(),
                sha1: [0; 20],
                tags: vec![],
                parts: vec![(chunks[0].guid, 0, 4000), (chunks[1].guid, 0, 2000)],
            },
            TestFile {
                name: "Game/b.bin".to_string(),
                sha1: [0; 20],
                tags: vec![],
                parts: vec![(chunks[1].guid, 100, 500)],
            },
        ];
        let manifest_bytes = build_manifest(&chunks, &files, 21, true);
        let dir = super::super::chunk::test_support::temp_dir("stream");
        let mut req = request(dir.to_str().unwrap(), vec![0, 1]);
        req.manifest_bytes = manifest_bytes;
        let plan = build_plan(&req).unwrap();
        assert_eq!(plan.needed, vec![0, 1]);

        // Mirror run_plan's StreamFile setup.
        let mk = |name: &str, parts: u32| {
            let out_path = dir.join(name);
            std::fs::create_dir_all(out_path.parent().unwrap()).unwrap();
            let tmp_path = tmp_path_for(&out_path);
            StreamFile {
                handle: File::create(&tmp_path).unwrap(),
                tmp_path,
                out_path,
                parts_total: parts,
                parts_written: AtomicU32::new(0),
                renamed: AtomicBool::new(false),
            }
        };
        let stream_files = vec![mk("Game/a.bin", 2), mk("Game/b.bin", 1)];
        let asm = Mutex::new(Vec::new());
        let assembly_progress = |b: u64| asm.lock().unwrap().push(b);
        let sink = StreamSink {
            plan: &plan,
            fetch_chunks: &plan.needed,
            files: &stream_files,
            assembled: AtomicU64::new(0),
            assembly_progress: &assembly_progress,
        };
        let item = |id: u64| FetchItem {
            id,
            urls: vec![],
            reserve: 0,
            range: None,
        };
        // Chunk 1 → file a's first part (4000); chunk 2 → a's second part (2000, completes a)
        // and b's only part (500, completes b). Shared-chunk fan-out in action.
        assert_eq!(sink.process(&item(0), b1).unwrap(), 4000);
        assert_eq!(sink.process(&item(1), b2).unwrap(), 3000);

        let a = std::fs::read(dir.join("Game/a.bin")).unwrap();
        assert_eq!(&a[..4000], &d1[..]);
        assert_eq!(&a[4000..], &d2[..2000]);
        assert_eq!(std::fs::read(dir.join("Game/b.bin")).unwrap(), &d2[100..600]);
        assert!(!dir.join("Game/a.bin.eptmp").exists(), "tmp renamed away on last part");
        assert!(!dir.join("Game/b.bin.eptmp").exists());
        assert!(stream_files.iter().all(|f| f.renamed.load(Ordering::Relaxed)));
        assert_eq!(*asm.lock().unwrap(), vec![4000, 6000, 6500], "cumulative per-part progress");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn sink_slice_beyond_chunk_is_a_fatal_plan_error() {
        use super::super::chunk::test_support::build_chunk_body;
        let d = vec![9u8; 100];
        let body = build_chunk_body(&d, true, 0, None);
        let chunks = vec![TestChunk {
            guid: [9, 9, 9, 9],
            hash: 0,
            sha1: [0; 20],
            group: 0,
            window: 100,
            file_size: body.len() as u64,
        }];
        let files = vec![TestFile {
            name: "Game/x.bin".to_string(),
            sha1: [0; 20],
            tags: vec![],
            parts: vec![(chunks[0].guid, 50, 500)], // 50+500 > 100 — manifest/bytes disagree
        }];
        let manifest_bytes = build_manifest(&chunks, &files, 21, true);
        let dir = super::super::chunk::test_support::temp_dir("slice");
        let mut req = request(dir.to_str().unwrap(), vec![0]);
        req.manifest_bytes = manifest_bytes;
        let plan = build_plan(&req).unwrap();
        let out_path = dir.join("Game/x.bin");
        std::fs::create_dir_all(out_path.parent().unwrap()).unwrap();
        let tmp_path = tmp_path_for(&out_path);
        let stream_files = vec![StreamFile {
            handle: File::create(&tmp_path).unwrap(),
            tmp_path,
            out_path,
            parts_total: 1,
            parts_written: AtomicU32::new(0),
            renamed: AtomicBool::new(false),
        }];
        let sink = StreamSink {
            plan: &plan,
            fetch_chunks: &plan.needed,
            files: &stream_files,
            assembled: AtomicU64::new(0),
            assembly_progress: &|_| {},
        };
        let item = FetchItem {
            id: 0,
            urls: vec![],
            reserve: 0,
            range: None,
        };
        match sink.process(&item, body) {
            Err(SinkError::Fatal(msg)) => assert!(msg.contains("beyond chunk"), "{msg}"),
            other => panic!("expected Fatal slice error, got {other:?}"),
        }
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn failed_run_deletes_unfinished_tmp_files() {
        // Unreachable CDN: the fetch fails, and the `.eptmp` created at setup must be removed
        // (GOG `.bhtmp` parity) so the next run starts clean instead of trusting a partial.
        let dir = super::super::chunk::test_support::temp_dir("fail");
        let mut req = request(dir.to_str().unwrap(), vec![0]);
        req.max_workers = 1;
        let plan = build_plan(&req).unwrap();
        let cancel = AtomicBool::new(false);
        let progress = |_: u64, _: u64| {};
        let assembly_progress = |_: u64| {};
        let log = |_: &str| {};
        let out = run_plan(&plan, &req, &cancel, &progress, &assembly_progress, &log);
        assert!(!out.success, "fetch must fail without a reachable CDN");
        assert!(!out.cancelled);
        let file0 = &plan.manifest.files[0].filename;
        assert!(
            !dir.join(format!("{file0}.eptmp")).exists(),
            "unfinished tmp deleted on failure"
        );
        assert!(!dir.join(file0).exists(), "no partial final published");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn cancel_before_fetch_reports_cancelled() {
        let dir = super::super::chunk::test_support::temp_dir("cancel");
        let req = request(dir.to_str().unwrap(), vec![0]);
        let plan = build_plan(&req).unwrap();
        let cancel = AtomicBool::new(true);
        let progress = |_: u64, _: u64| {};
        let assembly_progress = |_: u64| {};
        let log = |_: &str| {};
        let out = run_plan(&plan, &req, &cancel, &progress, &assembly_progress, &log);
        assert!(out.cancelled);
        assert!(!out.success);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn partless_pending_file_is_created_empty_without_fetch() {
        let files = vec![TestFile {
            name: "Game/empty.bin".to_string(),
            sha1: [0; 20],
            tags: vec![],
            parts: vec![],
        }];
        let manifest_bytes = build_manifest(&sample_chunks(), &files, 21, true);
        let dir = super::super::chunk::test_support::temp_dir("empty");
        let mut req = request(dir.to_str().unwrap(), vec![0]);
        req.manifest_bytes = manifest_bytes;
        let plan = build_plan(&req).unwrap();
        assert!(plan.needed.is_empty(), "a partless file needs no chunks");
        let cancel = AtomicBool::new(false);
        let progress = |_: u64, _: u64| {};
        let assembly_progress = |_: u64| {};
        let log = |_: &str| {};
        let out = run_plan(&plan, &req, &cancel, &progress, &assembly_progress, &log);
        assert!(out.success);
        assert_eq!(std::fs::metadata(dir.join("Game/empty.bin")).unwrap().len(), 0);
        let _ = std::fs::remove_dir_all(&dir);
    }
}
