//! Plan → `FetchItem`s → `crate::fetch_core::run_fetch` with a sink that STREAMS parts into
//! the final files (GOG-style): the fetch unit is one (file, part) job — a chunk shared by
//! several files is fetched once PER CONSUMING FILE — and each verified, decompressed part
//! queues into its owning file's ordered drain (sequential appends DIRECTLY into the final
//! file — no temp files anywhere), complete the moment its last part lands. There is NO
//! post-download assembly pass and no cross-file coupling: every pending file is a
//! self-contained download unit, so the resume unit is exactly the file (Java's delta/verify
//! excludes completed files from `pending_file_indices`). Within a file, a CANCELLED run keeps its partial final file:
//! the next run re-hashes the on-disk bytes part-by-part against the manifest's chunk SHA-1s
//! ([`verified_prefix`]) and re-fetches only the unverified tail — so pausing loses nothing
//! already downloaded (an ERROR run deletes the files it touched).
//! Parallelism is unchanged: the fetch core's process pool does the inflate + writes.
//!
//! This is the replacement for the body of the Java pool block in `EpicDownloadManager.install`
//! ("Download unique chunks — 8 parallel threads") AND for its assembly epilogue. Inputs are
//! exactly what that block sees: the parsed manifest (re-parsed here from the same bytes), the
//! pending file set (indices Java computed after its delta/verify pass), the CDN prefixes
//! (`baseUrl + cloudDir`, cloudflare already skipped). Output = completed game files.

use std::fs::{self, File, OpenOptions};
use std::os::unix::fs::FileExt;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use sha1::{Digest, Sha1};

use crate::fetch_core::{run_fetch, FetchItem, FetchOptions, FetchSink, SinkError};
use crate::store_dl::ordered_drain::{OrderedDrain, DRAIN_COALESCE_BYTES};

use super::manifest::{parse_manifest, Manifest};
use super::plan::{chunk_url, distinct_prefixes, per_host_cap};

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

/// One file-part fetch job: one slice of one chunk destined for one pending file at one
/// offset. Chunks shared by several files produce one job PER CONSUMING FILE and are fetched
/// separately each time — no cross-file dedup — so every pending file is a fully
/// self-contained download unit (file-granular resume, GOG parity).
#[derive(Clone, Copy, Debug)]
struct PartJob {
    /// Index into `manifest.unique_chunks`.
    chunk: usize,
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
    /// One job per (file, part) in Java's submission order — duplicates included.
    jobs: Vec<PartJob>,
    /// `Σ max(fileSize, 1)` over the jobs' chunks (compressed) — the actual download volume
    /// (shared chunks counted once per consuming file).
    pub total_compressed: u64,
    /// `Σ FileInfo.file_size()` over `pending_file_indices` — the progress TOTAL (uncompressed
    /// installed bytes; the streamed part-writes credit exactly this by completion).
    pub total_bytes: u64,
    /// Distinct CDN prefixes = fetch-core host keys.
    pub hosts: Vec<String>,
}

impl EpicPlan {
    /// Total (file, part) jobs = the chunk progress TOTAL reported to Java.
    pub fn job_count(&self) -> usize {
        self.jobs.len()
    }
}

/// Terminal result of a run, in the shape Java's pool block needs to reproduce its own exit
/// paths (`CANCELLED during chunk download (n/total chunks)`, `N chunks failed`, `chunksOK=`).
#[derive(Clone, Debug, Default)]
pub struct EpicOutcome {
    pub success: bool,
    pub cancelled: bool,
    pub error: String,
    /// Credited DECOMPRESSED bytes (inflated size of every fetched chunk body).
    pub bytes_credited: u64,
    /// (file, part) jobs accounted for, Java's `completedCount`.
    pub chunks_done: u64,
    pub chunks_total: u64,
    pub bytes_total: u64,
    /// Decompressed bytes actually written to files this run.
    pub decompressed_written: u64,
}

/// Parse + plan (pure computation, no I/O). `Err` = "engine could not start"; Java
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
    // One fetch job per (file, part): for every pending file walk its parts in order
    // (destination offset = cumulative part sizes). NO dedup — a chunk shared by N files is
    // fetched N times, once per consuming file, so each file downloads independently and the
    // resume unit is exactly the file. Parts referencing a GUID absent from the chunk list
    // get no job; the file then can never complete and the run fails loudly at the
    // unfinalized-files check instead of silently skipping the part.
    let by_guid = manifest.chunk_index_by_guid();
    let mut jobs = Vec::new();
    for (ord, &fi) in req.pending_file_indices.iter().enumerate() {
        let mut dst = 0u64;
        for part in &manifest.files[fi].parts {
            let len = (part.size.max(0) as u32) as u64; // Java `size & 0xFFFFFFFFL`
            if let Some(&ci) = by_guid.get(&part.guid_str()) {
                jobs.push(PartJob {
                    chunk: ci,
                    file_ord: ord,
                    src_off: part.offset.max(0) as u64,
                    dst_off: dst,
                    len,
                });
            }
            dst += len;
        }
    }
    let total_compressed: u64 = jobs
        .iter()
        .map(|j| manifest.unique_chunks[j.chunk].credit_bytes())
        .sum();
    let total_bytes: u64 = req
        .pending_file_indices
        .iter()
        .map(|&i| manifest.files[i].file_size())
        .sum();
    if let Some(expected) = req.expected_chunks {
        if expected != jobs.len() as u64 {
            return Err(format!(
                "plan: chunk count mismatch java={expected} rust={}",
                jobs.len()
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
    Ok(EpicPlan {
        manifest,
        jobs,
        total_compressed,
        total_bytes,
        hosts,
    })
}

/// Cancelled-run resume: read back an unfinished file AT ITS FINAL PATH and find its longest
/// PART-VERIFIED prefix. The file is written as a strictly contiguous prefix (the ordered drain
/// forbids holes), so walking the file's parts in order and re-hashing the on-disk bytes of
/// each proves which parts are already downloaded and intact. A part can only re-verify when
/// it covers its WHOLE chunk (`offset == 0`): the manifest's SHA-1 spans the entire
/// decompressed chunk, so a slice can never match it — the SHA-1 comparison itself also
/// settles the length (a proper prefix of the chunk hashes differently). Stops at the first
/// part that is unverifiable (chunk unknown or hash-less, a slice, truncated, or mismatched);
/// everything past the returned prefix is re-fetched. Zero-length parts contribute no bytes
/// and verify trivially. No trust is involved: every kept byte re-hashes against the manifest.
/// Returns (verified part count, verified byte length).
fn verified_prefix(
    path: &Path,
    file: &super::manifest::FileInfo,
    manifest: &Manifest,
    by_guid: &std::collections::HashMap<String, usize>,
) -> (usize, u64) {
    let Ok(handle) = File::open(path) else {
        return (0, 0);
    };
    let mut cursor = 0u64;
    for (n, part) in file.parts.iter().enumerate() {
        let len = (part.size.max(0) as u32) as u64;
        if len == 0 {
            continue; // no bytes: nothing to verify, nothing to re-fetch
        }
        let verifiable = by_guid
            .get(&part.guid_str())
            .and_then(|&ci| manifest.unique_chunks[ci].verifiable_sha1());
        let Some(expected) = verifiable else {
            return (n, cursor);
        };
        if part.offset != 0 || len > 64 * 1024 * 1024 {
            return (n, cursor);
        }
        let mut data = vec![0u8; len as usize];
        if handle.read_exact_at(&mut data, cursor).is_err() {
            return (n, cursor);
        }
        let mut sha = Sha1::new();
        sha.update(&data);
        if sha.finalize().as_slice() != &expected[..] {
            return (n, cursor);
        }
        cursor += len;
    }
    (file.parts.len(), cursor)
}

/// One pending file's streamed-write state. Parts park in the ordered drain and append to the
/// FINAL file strictly in offset order — pure sequential appends, never a positioned write past
/// EOF (the exFAT/FUSE zero-fill risk). The handle opens LAZILY on the first drained write, so
/// the run's peak open-fd count tracks files with active writes, not the whole pending set (a
/// many-thousand-file title cannot exhaust the fd limit at setup).
struct StreamFile {
    handle: Mutex<Option<File>>,
    drain: Mutex<OrderedDrain>,
    out_path: PathBuf,
    size: u64,
    parts_total: u32,
    parts_written: AtomicU32,
    /// Every part landed and the handle is closed — the file is complete in place.
    completed: AtomicBool,
    /// The file was opened for writing this run — error cleanup deletes only touched files.
    touched: AtomicBool,
    /// Byte length of the part-verified prefix already on disk (resume): the first open keeps
    /// the file and truncates at this cursor instead of starting empty (0 = fresh).
    resume_from: u64,
}

impl StreamFile {
    /// Queue one part's slice of a decoded chunk body (held as `Arc<[u8]>` so the drain can
    /// park it without copying), append every part now contiguous at the cursor as coalesced
    /// sequential writes, and when the last outstanding part lands complete the file in place
    /// (close the handle, mark it done). Returns true when THIS call completed the file.
    fn enqueue_part(
        &self,
        data: &Arc<[u8]>,
        src_off: u64,
        dst_off: u64,
        len: u64,
    ) -> Result<bool, String> {
        // The drain mutex serializes this file's insert → drain → append → publish sequence;
        // different files proceed fully in parallel.
        let mut drain = self.drain.lock().map_err(|_| "drain poisoned".to_string())?;
        drain.insert(dst_off, len, Arc::clone(data), src_off as usize, len as usize);
        let batches = drain.drain(DRAIN_COALESCE_BYTES);
        for batch in &batches {
            {
                let mut guard = self
                    .handle
                    .lock()
                    .map_err(|_| "file handle poisoned".to_string())?;
                if guard.is_none() {
                    let opened = if self.resume_from > 0 {
                        // Resume: the file already holds a part-verified prefix — keep it,
                        // drop any unverified tail past the cursor, continue appending.
                        OpenOptions::new()
                            .read(true)
                            .write(true)
                            .open(&self.out_path)
                            .and_then(|f| {
                                f.set_len(self.resume_from)?;
                                Ok(f)
                            })
                    } else {
                        // Lazy open: File::create also truncates a stale partial from a
                        // crashed run.
                        File::create(&self.out_path)
                    };
                    *guard = Some(
                        opened.map_err(|e| format!("create {}: {e}", self.out_path.display()))?,
                    );
                    self.touched.store(true, Ordering::Relaxed);
                }
                guard
                    .as_ref()
                    .expect("file handle")
                    .write_all_at(batch.bytes(), batch.offset)
                    .map_err(|e| format!("write {}: {e}", self.out_path.display()))?;
            }
            self.parts_written
                .fetch_add(batch.piece_lens.len() as u32, Ordering::Relaxed);
        }
        if self.parts_written.load(Ordering::Relaxed) != self.parts_total {
            return Ok(false);
        }
        // Loud invariant: a fully-parted file must have drained exactly to its size — never
        // publish over a gap (a part referencing a chunk that never arrived strands here).
        if self.size > 0 && drain.cursor() != self.size {
            return Err(format!(
                "{}: drained {}/{} bytes with all parts counted",
                self.out_path.display(),
                drain.cursor(),
                self.size
            ));
        }
        drop(drain);
        // Complete in place: close the handle and mark the file done.
        let _ = self
            .handle
            .lock()
            .map_err(|_| "file handle poisoned".to_string())?
            .take();
        self.completed.store(true, Ordering::Relaxed);
        Ok(true)
    }
}

/// Sink: one downloaded body → verified in-memory decode → the part's slice is queued into
/// its owning file's ordered drain, appending sequentially in offset order (the Steam/GOG
/// model: assemble during download, no assembly pass afterwards, no positioned writes past
/// EOF, no cross-file fan-out).
struct StreamSink<'a> {
    plan: &'a EpicPlan,
    /// The (file, part) jobs actually being fetched, indexed by `FetchItem.id`.
    jobs: &'a [PartJob],
    files: &'a [StreamFile],
    /// Cumulative part bytes written — drives `assembly_progress` (which now fires DURING the
    /// fetch, so the UI's single credit budget sees steady movement end to end).
    assembled: AtomicU64,
    assembly_progress: &'a (dyn Fn(u64) + Sync),
}

impl<'a> FetchSink for StreamSink<'a> {
    fn process(&self, item: &FetchItem, body: Vec<u8>) -> Result<u64, SinkError> {
        let Some(job) = self.jobs.get(item.id as usize) else {
            return Err(SinkError::Fatal(format!("item id {} out of range", item.id)));
        };
        let chunk = &self.plan.manifest.unique_chunks[job.chunk];
        let data: Arc<[u8]> = match super::chunk::decode_verified_chunk(
            &body,
            chunk.verifiable_sha1(),
            if chunk.window_size > 0 {
                Some(chunk.window_size as u64)
            } else {
                None
            },
        ) {
            Ok(data) => Arc::from(data),
            // Every per-attempt failure in Java is "try the next CDN"; the core's Retry rotates
            // hosts and backs off the same way (bounded at its attempt cap).
            Err(reason) => return Err(SinkError::Retry(format!("{} {reason}", chunk.guid_str()))),
        };
        let decompressed_len = data.len() as u64;
        let end = (job.src_off + job.len) as usize;
        if end > data.len() {
            return Err(SinkError::Fatal(format!(
                "part slice {end} beyond chunk {} ({} bytes)",
                chunk.guid_str(),
                data.len()
            )));
        }
        // A disk/FS error is not CDN-curable — fail the run instead of rotating hosts.
        self.files[job.file_ord]
            .enqueue_part(&data, job.src_off, job.dst_off, job.len)
            .map_err(SinkError::Fatal)?;
        let done = self.assembled.fetch_add(job.len, Ordering::Relaxed) + job.len;
        (self.assembly_progress)(done);
        // Credit the DECOMPRESSED bytes once per fetched part (the fetch-side progress
        // contract); the per-part credit above covers the assembly side.
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
    let chunks_total = plan.jobs.len() as u64;
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

    if cancel.load(Ordering::Relaxed) {
        outcome.cancelled = true;
        outcome.error = "cancelled".to_string();
        log("cancelled before fetch (0 chunks)");
        return outcome;
    }

    // Cancelled-run resume pass: re-hash partial files at their final paths part-by-part
    // against the manifest's chunk SHA-1s ([`verified_prefix`]) on the process-pool shape, so
    // the fetch below skips each interrupted file's verified prefix. No trust: every kept byte
    // re-hashes against the manifest.
    let install_dir = Path::new(&req.install_dir);
    let by_guid = plan.manifest.chunk_index_by_guid();
    let resume: Vec<(AtomicUsize, AtomicU64)> = req
        .pending_file_indices
        .iter()
        .map(|_| (AtomicUsize::new(0), AtomicU64::new(0)))
        .collect();
    let resume_next = AtomicUsize::new(0);
    let verify_threads = req
        .process_workers
        .max(1)
        .min(req.pending_file_indices.len().max(1));
    std::thread::scope(|scope| {
        for _ in 0..verify_threads {
            scope.spawn(|| loop {
                if cancel.load(Ordering::Relaxed) {
                    break;
                }
                let ord = resume_next.fetch_add(1, Ordering::Relaxed);
                if ord >= req.pending_file_indices.len() {
                    break;
                }
                let file = &plan.manifest.files[req.pending_file_indices[ord]];
                let (n, bytes) =
                    verified_prefix(&install_dir.join(&file.filename), file, &plan.manifest, &by_guid);
                resume[ord].0.store(n, Ordering::Relaxed);
                resume[ord].1.store(bytes, Ordering::Relaxed);
            });
        }
    });
    if cancel.load(Ordering::Relaxed) {
        outcome.cancelled = true;
        outcome.error = "cancelled".to_string();
        log("cancelled during resume verify (0 chunks)");
        return outcome;
    }

    // Stream targets: one final file per pending file, written directly. A partless file
    // (size 0) is just an empty final file — created here, nothing to fetch or write.
    let mut files: Vec<StreamFile> = Vec::with_capacity(req.pending_file_indices.len());
    let mut resumed_parts = 0u64;
    let mut resumed_bytes = 0u64;
    for (ord, &fi) in req.pending_file_indices.iter().enumerate() {
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
            // already-completed StreamFile so `files` stays 1:1 with `pending_file_indices` (the
            // plan's consumer ordinals index it directly); nothing ever writes to it.
            match File::create(&out_path) {
                Ok(_handle) => {
                    log(&format!("empty file {}", file.filename));
                    files.push(StreamFile {
                        handle: Mutex::new(None),
                        drain: Mutex::new(OrderedDrain::default()),
                        out_path,
                        size: 0,
                        parts_total: 0,
                        parts_written: AtomicU32::new(0),
                        completed: AtomicBool::new(true),
                        touched: AtomicBool::new(false),
                        resume_from: 0,
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
        let verified_parts = resume[ord].0.load(Ordering::Relaxed);
        let verified_bytes = resume[ord].1.load(Ordering::Relaxed);
        if verified_parts == file.parts.len() {
            // Every part of the file re-hashed against the manifest's chunk SHA-1s from the
            // bytes already at the final path: the file is complete in place, no fetching.
            log(&format!("resume-complete {}", file.filename));
            files.push(StreamFile {
                handle: Mutex::new(None),
                drain: Mutex::new(OrderedDrain::default()),
                out_path,
                size: file.file_size(),
                parts_total: 0,
                parts_written: AtomicU32::new(0),
                completed: AtomicBool::new(true),
                touched: AtomicBool::new(false),
                resume_from: 0,
            });
            continue;
        }
        resumed_parts += verified_parts as u64;
        resumed_bytes += verified_bytes;
        // The file is NOT created here: it opens lazily on the file's first drained write
        // (a fresh open truncates a stale partial; a resumed open keeps the verified prefix),
        // so a big pending set cannot exhaust the fd limit at setup.
        files.push(StreamFile {
            handle: Mutex::new(None),
            drain: Mutex::new(OrderedDrain::with_cursor(verified_bytes)),
            out_path,
            size: file.file_size(),
            parts_total: (file.parts.len() - verified_parts) as u32,
            parts_written: AtomicU32::new(0),
            completed: AtomicBool::new(false),
            touched: AtomicBool::new(false),
            resume_from: verified_bytes,
        });
    }
    log(&format!(
        "resume verified_parts={resumed_parts} verified_bytes={resumed_bytes}"
    ));

    for (i, h) in plan.hosts.iter().enumerate() {
        log(&format!("host[{i}]={h}"));
    }

    // Delete every file this run TOUCHED on the way out after an ERROR (a cancelled run keeps
    // its partials for the part-verified resume; an untouched pre-existing partial keeps its
    // prefix too — the next run's zero-trust re-hash rewinds past any torn bytes anyway).
    let cleanup_unfinished = |files: &[StreamFile]| {
        for f in files {
            if !f.completed.load(Ordering::Relaxed) && f.touched.load(Ordering::Relaxed) {
                let _ = fs::remove_file(&f.out_path);
            }
        }
    };

    // The fetch set: plan.jobs minus each file's verified-prefix parts. Jobs are built in
    // (file, part) order, so dropping the first `verified` jobs of each file removes exactly
    // the prefix — shared-chunk duplicates past the prefix are still fetched per consumer.
    let mut seen: Vec<usize> = vec![0; files.len()];
    let jobs: Vec<PartJob> = plan
        .jobs
        .iter()
        .copied()
        .filter(|j| {
            let s = &mut seen[j.file_ord];
            let skip = *s < resume[j.file_ord].0.load(Ordering::Relaxed);
            *s += 1;
            !skip
        })
        .collect();
    let chunks_total = jobs.len() as u64;
    outcome.chunks_total = chunks_total;

    if jobs.is_empty() {
        // Nothing to fetch: every pending file is partless or verified whole on disk.
        // Success still requires every file complete (loud invariant).
        let unfinalized = files
            .iter()
            .filter(|f| !f.completed.load(Ordering::Relaxed))
            .count();
        if unfinalized > 0 {
            outcome.error = format!("nothing to fetch but {unfinalized} file(s) unfinished");
            log(&format!("FAIL {}", outcome.error));
            cleanup_unfinished(&files);
            return outcome;
        }
        outcome.success = true;
        log("summary bytes=0 elapsed=0.0s (nothing to fetch)");
        return outcome;
    }

    let items: Vec<FetchItem> = jobs
        .iter()
        .enumerate()
        .map(|(id, job)| {
            let chunk = &plan.manifest.unique_chunks[job.chunk];
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
        jobs: &jobs,
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
        // A CANCELLED run keeps the unfinished files: the next run re-hashes each one
        // part-by-part and resumes past the verified prefix.
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
    if fetched.items_ok != jobs.len() as u64 {
        outcome.error = format!(
            "fetch ended with {}/{} chunks",
            fetched.items_ok,
            jobs.len()
        );
        log(&format!("FAIL {}", outcome.error));
        cleanup_unfinished(&files);
        return outcome;
    }
    let completed = files
        .iter()
        .filter(|f| f.completed.load(Ordering::Relaxed))
        .count();
    // GOG `unfinalized_pending()` parity: success requires EVERY file completed. Anything left
    // (e.g. a part referencing a chunk that never arrived) is a loud failure with the touched
    // files cleaned up — never a silent partial-forever success.
    let unfinalized = files.len() - completed;
    if unfinalized > 0 {
        outcome.error = format!("engine finished with {unfinalized} unfinalized file(s)");
        log(&format!("FAIL {}", outcome.error));
        cleanup_unfinished(&files);
        return outcome;
    }
    outcome.success = true;
    log(&format!(
        "chunksOK={} streamOK files={completed} bytes={assembled}",
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
        // One job per (file, part): file0 = chunk0 whole + 4000 of chunk1; file1 = 4096 of
        // chunk1 — chunk1 is SHARED, so it gets TWO jobs and is fetched twice.
        assert_eq!(plan.jobs.len(), 3, "no cross-file dedup");
        assert_eq!(plan.jobs[0].chunk, 0);
        assert_eq!(plan.jobs[0].file_ord, 0);
        assert_eq!(plan.jobs[0].dst_off, 0);
        assert_eq!(plan.jobs[0].len, 1_048_576);
        assert_eq!(plan.jobs[1].chunk, 1);
        assert_eq!(plan.jobs[1].file_ord, 0);
        assert_eq!(plan.jobs[1].dst_off, 1_048_576);
        assert_eq!(plan.jobs[1].len, 4000);
        assert_eq!(plan.jobs[2].chunk, 1, "shared chunk re-fetched for the second file");
        assert_eq!(plan.jobs[2].file_ord, 1);
        assert_eq!(plan.jobs[2].dst_off, 0);
        assert_eq!(plan.jobs[2].len, 4096);
        // Progress total = uncompressed installed bytes of the pending files:
        // file0 = 1_048_576 + 4000, file1 = 4096.
        assert_eq!(plan.total_bytes, 1_056_672);
        assert_eq!(plan.total_compressed, 700_000 + 1 + 1, "shared chunk credited per job");
        assert_eq!(plan.hosts.len(), 2, "duplicate CDN prefix collapsed");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn plan_cross_check_rejects_mismatches() {
        let dir = super::super::chunk::test_support::temp_dir("xcheck");
        let mut req = request(dir.to_str().unwrap(), vec![0, 1]);
        req.expected_chunks = Some(2);
        assert!(build_plan(&req).unwrap_err().contains("chunk count mismatch"));
        req.expected_chunks = Some(3);
        req.expected_bytes = Some(5);
        assert!(build_plan(&req).unwrap_err().contains("byte total mismatch"));
        req.expected_bytes = Some(700_002);
        assert!(build_plan(&req).is_ok());
        req.pending_file_indices = vec![7];
        assert!(build_plan(&req).unwrap_err().contains("out of range"));
        req.pending_file_indices = vec![0];
        // Skip the count/byte cross-checks so the CDN validation is what fires.
        req.expected_chunks = None;
        req.expected_bytes = None;
        req.cdn_prefixes.clear();
        assert!(build_plan(&req).unwrap_err().contains("no CDN"));
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The streamed model end to end at sink level: chunk bodies in, final files out — shared
    /// chunks fanned out, files completed on their last part, progress cumulative per part.
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
        // 3 jobs: a's two parts + b's one part (chunk 2 fetched once per file).
        assert_eq!(plan.jobs.len(), 3);

        // Mirror run_plan's StreamFile setup (lazy handle + ordered drain).
        let mk = |name: &str, size: u64, parts: u32| {
            let out_path = dir.join(name);
            std::fs::create_dir_all(out_path.parent().unwrap()).unwrap();
            StreamFile {
                handle: Mutex::new(None),
                drain: Mutex::new(OrderedDrain::default()),
                out_path,
                size,
                parts_total: parts,
                parts_written: AtomicU32::new(0),
                completed: AtomicBool::new(false),
                touched: AtomicBool::new(false),
                resume_from: 0,
            }
        };
        let stream_files = vec![mk("Game/a.bin", 6000, 2), mk("Game/b.bin", 500, 1)];
        let asm = Mutex::new(Vec::new());
        let assembly_progress = |b: u64| asm.lock().unwrap().push(b);
        let sink = StreamSink {
            plan: &plan,
            jobs: &plan.jobs,
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
        // Job 0 → file a's first part (4000); job 1 → a's second part (2000, completes a);
        // job 2 → b's only part (500, completes b). The shared chunk's body is fetched and
        // decoded once per consuming file — no fan-out.
        assert_eq!(sink.process(&item(0), b1).unwrap(), 4000);
        assert_eq!(sink.process(&item(1), b2.clone()).unwrap(), 3000);
        assert_eq!(sink.process(&item(2), b2).unwrap(), 3000);

        let a = std::fs::read(dir.join("Game/a.bin")).unwrap();
        assert_eq!(&a[..4000], &d1[..]);
        assert_eq!(&a[4000..], &d2[..2000]);
        assert_eq!(std::fs::read(dir.join("Game/b.bin")).unwrap(), &d2[100..600]);
        assert!(stream_files.iter().all(|f| f.completed.load(Ordering::Relaxed)));
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
        let stream_files = vec![StreamFile {
            handle: Mutex::new(None),
            drain: Mutex::new(OrderedDrain::default()),
            out_path,
            size: 500,
            parts_total: 1,
            parts_written: AtomicU32::new(0),
            completed: AtomicBool::new(false),
            touched: AtomicBool::new(false),
            resume_from: 0,
        }];
        let sink = StreamSink {
            plan: &plan,
            jobs: &plan.jobs,
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
    fn sink_out_of_order_parts_drain_in_offset_order() {
        use super::super::chunk::test_support::build_chunk_body;
        // Same manifest shape as the streaming test, but a's SECOND part arrives first:
        // it must park in the drain — not write past the gap — while b's job drains and
        // completes immediately (independent per-file units).
        let d1 = vec![1u8; 4000];
        let d2 = vec![2u8; 3000];
        let b1 = build_chunk_body(&d1, true, 0, None);
        let b2 = build_chunk_body(&d2, true, 0, None);
        let chunks = vec![
            TestChunk {
                guid: [1, 2, 3, 4],
                hash: 0,
                sha1: [0; 20],
                group: 0,
                window: 4000,
                file_size: b1.len() as u64,
            },
            TestChunk {
                guid: [5, 6, 7, 8],
                hash: 0,
                sha1: [0; 20],
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
        let dir = super::super::chunk::test_support::temp_dir("ooo");
        let mut req = request(dir.to_str().unwrap(), vec![0, 1]);
        req.manifest_bytes = manifest_bytes;
        let plan = build_plan(&req).unwrap();
        let mk = |name: &str, size: u64, parts: u32| {
            let out_path = dir.join(name);
            std::fs::create_dir_all(out_path.parent().unwrap()).unwrap();
            StreamFile {
                handle: Mutex::new(None),
                drain: Mutex::new(OrderedDrain::default()),
                out_path,
                size,
                parts_total: parts,
                parts_written: AtomicU32::new(0),
                completed: AtomicBool::new(false),
                touched: AtomicBool::new(false),
                resume_from: 0,
            }
        };
        let stream_files = vec![mk("Game/a.bin", 6000, 2), mk("Game/b.bin", 500, 1)];
        let sink = StreamSink {
            plan: &plan,
            jobs: &plan.jobs,
            files: &stream_files,
            assembled: AtomicU64::new(0),
            assembly_progress: &|_| {},
        };
        let item = |id: u64| FetchItem {
            id,
            urls: vec![],
            reserve: 0,
            range: None,
        };
        // Job 1 first (a's second part, dst 4000): parks — no write past the gap.
        assert_eq!(sink.process(&item(1), b2.clone()).unwrap(), 3000);
        assert!(!dir.join("Game/a.bin").exists(), "parked part wrote nothing");
        // Job 2 (b's only part): b completes on its own schedule.
        assert_eq!(sink.process(&item(2), b2).unwrap(), 3000);
        assert!(dir.join("Game/b.bin").exists(), "b completed from dst 0");
        // Job 0 lands: its part appends at 0, then the parked part drains behind it.
        assert_eq!(sink.process(&item(0), b1).unwrap(), 4000);
        let a = std::fs::read(dir.join("Game/a.bin")).unwrap();
        assert_eq!(&a[..4000], &d1[..]);
        assert_eq!(&a[4000..], &d2[..2000]);
        assert!(stream_files.iter().all(|f| f.completed.load(Ordering::Relaxed)));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn failed_run_deletes_touched_files() {
        // Unreachable CDN: the fetch fails, and any file the run touched must be removed
        // so the next run starts clean instead of trusting a partial. (With lazy creation
        // nothing is written here at all — the invariant is what matters: no partial final.)
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
        assert!(!dir.join(file0).exists(), "no partial final left behind");
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
        assert!(plan.jobs.is_empty(), "a partless file has no part jobs");
        let cancel = AtomicBool::new(false);
        let progress = |_: u64, _: u64| {};
        let assembly_progress = |_: u64| {};
        let log = |_: &str| {};
        let out = run_plan(&plan, &req, &cancel, &progress, &assembly_progress, &log);
        assert!(out.success);
        assert_eq!(std::fs::metadata(dir.join("Game/empty.bin")).unwrap().len(), 0);
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Two whole-chunk parts with real SHA-1s: the on-disk read-back must keep exactly the
    /// verified prefix.
    #[test]
    fn prefix_verify_keeps_only_verified_whole_chunk_parts() {
        use super::super::chunk::test_support::sha1_of;
        let d1: Vec<u8> = (0..4000u32).map(|i| (i % 251) as u8).collect();
        let d2: Vec<u8> = (0..3000u32).map(|i| (i % 241) as u8).collect();
        let chunks = vec![
            TestChunk {
                guid: [1, 2, 3, 4],
                hash: 0,
                sha1: sha1_of(&d1),
                group: 0,
                window: 4000,
                file_size: d1.len() as u64,
            },
            TestChunk {
                guid: [5, 6, 7, 8],
                hash: 0,
                sha1: sha1_of(&d2),
                group: 0,
                window: 3000,
                file_size: d2.len() as u64,
            },
        ];
        let files = vec![TestFile {
            name: "Game/a.bin".to_string(),
            sha1: [0; 20],
            tags: vec![],
            parts: vec![(chunks[0].guid, 0, 4000), (chunks[1].guid, 0, 3000)],
        }];
        let manifest_bytes = build_manifest(&chunks, &files, 21, true);
        let manifest = parse_manifest(&manifest_bytes).unwrap();
        let by_guid = manifest.chunk_index_by_guid();
        let file = &manifest.files[0];
        let dir = super::super::chunk::test_support::temp_dir("prefix");
        let path = dir.join("a.bin");
        // No file → nothing verified.
        assert_eq!(verified_prefix(&path, file, &manifest, &by_guid), (0, 0));
        // First part intact + garbage tail → exactly the first part keeps.
        let mut body = d1.clone();
        body.extend_from_slice(&[0xEE; 1500]);
        std::fs::write(&path, &body).unwrap();
        assert_eq!(verified_prefix(&path, file, &manifest, &by_guid), (1, 4000));
        // Whole file → both parts.
        let mut whole = d1.clone();
        whole.extend_from_slice(&d2);
        std::fs::write(&path, &whole).unwrap();
        assert_eq!(verified_prefix(&path, file, &manifest, &by_guid), (2, 7000));
        // Corruption inside part 0 rewinds to nothing.
        let mut corrupt = whole.clone();
        corrupt[10] ^= 0xFF;
        std::fs::write(&path, &corrupt).unwrap();
        assert_eq!(verified_prefix(&path, file, &manifest, &by_guid), (0, 0));
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// A part that is a SLICE of a chunk can never re-verify (the SHA-1 spans the whole
    /// decompressed chunk): the prefix stops before it even when the bytes on disk are intact.
    #[test]
    fn prefix_verify_cannot_trust_a_chunk_slice() {
        use super::super::chunk::test_support::sha1_of;
        let d1: Vec<u8> = (0..4000u32).map(|i| (i % 251) as u8).collect();
        let d2: Vec<u8> = (0..3000u32).map(|i| (i % 241) as u8).collect();
        let chunks = vec![
            TestChunk {
                guid: [1, 2, 3, 4],
                hash: 0,
                sha1: sha1_of(&d1),
                group: 0,
                window: 4000,
                file_size: d1.len() as u64,
            },
            TestChunk {
                guid: [5, 6, 7, 8],
                hash: 0,
                sha1: sha1_of(&d2),
                group: 0,
                window: 3000,
                file_size: d2.len() as u64,
            },
        ];
        let files = vec![TestFile {
            name: "Game/a.bin".to_string(),
            sha1: [0; 20],
            tags: vec![],
            parts: vec![(chunks[0].guid, 0, 4000), (chunks[1].guid, 100, 500)],
        }];
        let manifest_bytes = build_manifest(&chunks, &files, 21, true);
        let manifest = parse_manifest(&manifest_bytes).unwrap();
        let by_guid = manifest.chunk_index_by_guid();
        let file = &manifest.files[0];
        let dir = super::super::chunk::test_support::temp_dir("slicepfx");
        let path = dir.join("a.bin");
        // The on-disk bytes are exactly right — but part 1 is a slice, so the prefix stops.
        let mut body = d1.clone();
        body.extend_from_slice(&d2[100..600]);
        std::fs::write(&path, &body).unwrap();
        assert_eq!(verified_prefix(&path, file, &manifest, &by_guid), (1, 4000));
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// A cancelled run left the WHOLE file at its final path: the resume verify takes it as
    /// complete without touching the network.
    #[test]
    fn run_plan_publishes_a_fully_resumed_file_without_network() {
        use super::super::chunk::test_support::sha1_of;
        let d1: Vec<u8> = (0..4000u32).map(|i| (i % 251) as u8).collect();
        let d2: Vec<u8> = (0..3000u32).map(|i| (i % 241) as u8).collect();
        let whole: Vec<u8> = d1.iter().chain(d2.iter()).copied().collect();
        let chunks = vec![
            TestChunk {
                guid: [1, 2, 3, 4],
                hash: 0,
                sha1: sha1_of(&d1),
                group: 0,
                window: 4000,
                file_size: d1.len() as u64,
            },
            TestChunk {
                guid: [5, 6, 7, 8],
                hash: 0,
                sha1: sha1_of(&d2),
                group: 0,
                window: 3000,
                file_size: d2.len() as u64,
            },
        ];
        let files = vec![TestFile {
            name: "Game/a.bin".to_string(),
            sha1: [0; 20],
            tags: vec![],
            parts: vec![(chunks[0].guid, 0, 4000), (chunks[1].guid, 0, 3000)],
        }];
        let manifest_bytes = build_manifest(&chunks, &files, 21, true);
        let dir = super::super::chunk::test_support::temp_dir("fullresume");
        let mut req = request(dir.to_str().unwrap(), vec![0]);
        req.manifest_bytes = manifest_bytes;
        let plan = build_plan(&req).unwrap();
        let out_path = dir.join("Game/a.bin");
        std::fs::create_dir_all(out_path.parent().unwrap()).unwrap();
        std::fs::write(&out_path, &whole).unwrap();
        let cancel = AtomicBool::new(false);
        let progress = |_: u64, _: u64| {};
        let assembly_progress = |_: u64| {};
        let log = |_: &str| {};
        let out = run_plan(&plan, &req, &cancel, &progress, &assembly_progress, &log);
        assert!(out.success, "resume-complete must succeed with no CDN: {out:?}");
        assert_eq!(out.chunks_done, 0, "nothing fetched");
        assert_eq!(std::fs::read(&out_path).unwrap(), whole);
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Sink-level resume: the file is seeded with a part-verified prefix (drain cursor,
    /// resume_from) and the final file already holds part 0 — landing part 1 must append
    /// after the prefix and complete the whole file in place.
    #[test]
    fn sink_appends_after_a_resumed_prefix() {
        use super::super::chunk::test_support::{build_chunk_body, sha1_of};
        let d1: Vec<u8> = (0..4000u32).map(|i| (i % 251) as u8).collect();
        let d2: Vec<u8> = (0..3000u32).map(|i| (i % 241) as u8).collect();
        let whole: Vec<u8> = d1.iter().chain(d2.iter()).copied().collect();
        let chunks = vec![
            TestChunk {
                guid: [1, 2, 3, 4],
                hash: 0,
                sha1: sha1_of(&d1),
                group: 0,
                window: 4000,
                file_size: d1.len() as u64,
            },
            TestChunk {
                guid: [5, 6, 7, 8],
                hash: 0,
                sha1: sha1_of(&d2),
                group: 0,
                window: 3000,
                file_size: d2.len() as u64,
            },
        ];
        let files = vec![TestFile {
            name: "Game/a.bin".to_string(),
            sha1: [0; 20],
            tags: vec![],
            parts: vec![(chunks[0].guid, 0, 4000), (chunks[1].guid, 0, 3000)],
        }];
        let manifest_bytes = build_manifest(&chunks, &files, 21, true);
        let dir = super::super::chunk::test_support::temp_dir("sinkresume");
        let mut req = request(dir.to_str().unwrap(), vec![0]);
        req.manifest_bytes = manifest_bytes;
        let plan = build_plan(&req).unwrap();
        let out_path = dir.join("Game/a.bin");
        std::fs::create_dir_all(out_path.parent().unwrap()).unwrap();
        // The cancelled previous run wrote part 0 and kept the partial file.
        std::fs::write(&out_path, &d1).unwrap();
        let stream_files = vec![StreamFile {
            handle: Mutex::new(None),
            drain: Mutex::new(OrderedDrain::with_cursor(d1.len() as u64)),
            out_path: out_path.clone(),
            size: whole.len() as u64,
            parts_total: 1, // only part 1 is outstanding
            parts_written: AtomicU32::new(0),
            completed: AtomicBool::new(false),
            touched: AtomicBool::new(false),
            resume_from: d1.len() as u64,
        }];
        let jobs: Vec<PartJob> = plan.jobs[1..].to_vec(); // only part 1 is re-fetched
        let sink = StreamSink {
            plan: &plan,
            jobs: &jobs,
            files: &stream_files,
            assembled: AtomicU64::new(0),
            assembly_progress: &|_| {},
        };
        let body = build_chunk_body(&d2, false, 0, Some(d2.len() as i32));
        let item = FetchItem {
            id: 0,
            urls: vec![],
            reserve: 0,
            range: None,
        };
        assert_eq!(sink.process(&item, body).unwrap(), d2.len() as u64);
        assert!(out_path.exists(), "completed in place on the last part");
        assert_eq!(std::fs::read(&out_path).unwrap(), whole);
        let _ = std::fs::remove_dir_all(&dir);
    }
}
