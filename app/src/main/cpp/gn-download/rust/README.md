# gn-download

GameNative's native download engine (Rust, JNI, `libgndownload.so`). One crate serves all
four stores — Steam, GOG, Epic, Amazon — sharing a common fetch core (`fetch_core.rs`), a
memory-budgeted adaptive window, per-host concurrency caps, retry/rotation policy, and a
shared ordered-write model.

This README is the map of how the whole thing fits together.

## Layout

| Path | Role |
|---|---|
| `fetch_core.rs` | Shared HTTP engine: adaptive in-flight window, byte budget, per-host caps, retries, backoff, stall detection |
| `queue_delay.rs` | LEDBAT (RFC 6817) queueing-delay estimator: TCP-connect RTT prober + base-delay history, shared by both window controllers |
| `store_dl/ordered_drain.rs` | Shared per-file ordered writer used by GOG and Epic (the Steam model): pending BTreeMap + cursor + coalesced 16 MiB batches + **Verified markers** |
| `store_dl/steam/depot_downloader.rs` | Steam orchestration: manifests, depot keys, DLC/redist depots, cache validation |
| `store_dl/steam/depot_writer.rs` | Steam depot pipeline: verify/resume, chunk dispatch, ordered writes, stall watchdog |
| `store_dl/steam/cdn_client.rs` / `cdn_probe.rs` | Steam CDN client (manifest/chunk GETs) and throughput-based server probing (spawned lazily on the first downloaded byte) |
| `store_dl/steam/depot_chunk.rs` / `crypto.rs` | Steam chunk decrypt + decompress (`content_manifest.rs`, `proto_wire.rs` + `pb/` parse the manifests; `depot_config.rs` keeps depot metadata + the resume journal) |
| `store_dl/gog/` | GOG engine (gen1 range-GETs + gen2 chunked galaxy builds, multi-mirror CDN pool) |
| `store_dl/epic/` | Epic engine (per-(file, part) fetching, streamed writes, selective verified resume) |
| `store_dl/amazon.rs` | Amazon engine (whole-file streaming) |
| `store_dl/*/jni.rs` | JNI entry points called from Kotlin |

Every store lives under `store_dl/`; only genuinely shared plumbing (`fetch_core`, `md5_small`,
`tree_delete`) stays at the crate root. **No store uses temp files**: every byte is written
directly to the final target file (see §2) and verified in place.

## 1. Encryption & integrity per store

The stores differ fundamentally here; there is no shared crypto layer beyond Steam's.

- **Steam** — every depot chunk is **AES-256 encrypted**: the first 16 bytes are the
  AES-256-ECB(key)-wrapped IV, the rest is AES-256-CBC ciphertext (`crypto.rs`,
  `depot_chunk.rs::steam_symmetric_decrypt`). The depot key is a per-depot symmetric key
  obtained from Steam over the authenticated CM session (request via the owning app — see
  *Verify & update* below). After decryption the payload is **VZip/LZMA or plain-zip**
  compressed and decompressed to the manifest's expected size. Integrity comes from the
  manifest: each chunk has a SHA-1 the writer verifies after decompress.
- **GOG** — **no payload encryption**; integrity is **MD5**. gen2 chunks carry a compressed
  MD5 and an uncompressed MD5 (checked before and after zlib inflate); each completed file is
  re-hashed whole (MD5) in place before it counts as done.
- **Epic** — **no payload encryption**; integrity is **SHA-1** per chunk (from the binary
  manifest), checked after the 41-byte chunk header parse and optional zlib inflate.
- **Amazon** — **no payload encryption**; integrity is **SHA-256** per *whole file*, streamed
  while downloading; a mismatch deletes the file and is retryable.

In all cases transport security is HTTPS (rustls, device CA bundle passed in from Kotlin).

## 2. Download flow — sorting and sequential writes

Every store writes files **sequentially, append-only, never past the end of written data**.
This is a hard design rule born from a real device failure: positioned `pwrite` far past EOF
on exFAT/FUSE SD cards forces the filesystem to zero-fill the gap, which wedges all writers
(the "511 MB freeze"). No preallocation (`set_len`) is used anywhere for the same reason.

- **Steam** (`depot_writer.rs`): per file, chunk jobs are **sorted by offset before dispatch**
  (`by_offset.sort_by_key`). Arrivals park in a `BTreeMap<offset, entry>` per file; the
  writer drains the contiguous prefix from the cursor, coalescing up to 16 MiB per pwrite at
  the current EOF. File handles are opened lazily on first touch and closed on completion,
  so peak fd usage is bounded by the active window, not the file count. The layout pass
  creates only directories and symlinks — regular files are created on first write (and
  0-chunk files at finalize), so download start and later deletion stay cheap on
  FUSE/sdcardfs even for many-thousand-file depots.

**Pre-pass cost (verify/update start-up).** Before a depot's first chunk, Steam resolves every
manifest path to its on-disk spelling and stats every file — both before the first `onVerifying`
status can be reported, so they are the window in which a verify/update looks "stuck" before the
UI shows anything (the directory layout is created *after* that first `(0, N)` status). This is
metadata-bound work, so it is exactly what device storage punishes.

Two things keep it minimal. `DepotFiles::prepare` reuses the paths `plan_depot_write` already
resolved instead of walking the tree a second time, and path resolution itself is cached:
`CaseResolver` (`store_dl/mod.rs`) still checks each component with one `exists()` stat (the
exact-spelling fast path that hits for every already-correct path), but a component that does
**not** exist with the manifest's spelling is answered from a per-directory case-folded listing
cached once per pass — where the old code scanned the parent directory again for every such
component, i.e. once per new file of an update. Measured on a 40k-file tree, plan+prepare:

| case | before | after |
|---|---|---|
| verify (every file already on disk) | 296 ms | 182 ms |
| update (10% of the manifest is new) | 265 ms | 163 ms |

…against a 48 ms floor for the one stat per file that cannot be avoided. The verify counter is
published as soon as the candidate count is known — before the layout pass — as `(0, N)`.
- **GOG** (`store_dl/gog/engine.rs`): chunks inflate into an in-memory buffer first
  (MD5-verified), then only *verified* chunks enter the file's `OrderedDrain`
  (`store_dl/ordered_drain.rs`) — the shared component implementing the Steam model
  (pending BTreeMap + cursor + coalesced 16 MiB batches); on a resumed run, chunks that
  re-hashed intact enter as **Verified markers** instead of data (see §4). The final file
  opens lazily on the first drained write. gen1 (range-GET) files stream sequentially by
  construction.
- **Epic** (`store_dl/epic/driver.rs`): the fetch unit is one **(file, part) job** — a chunk
  shared by several files is fetched once *per consuming file*, never deduplicated and never
  cached, so every pending file is a fully self-contained download unit (file-granular
  resume, no cross-file coupling). Each verified part queues a slice of its decoded body into
  its owning file's `OrderedDrain`; a per-file mutex serializes insert→drain→append,
  so parts land in offset order regardless of arrival. Final files are created lazily on
  the first drained write (never up front — early versions exhausted fds on big first
  installs).
- **Amazon**: whole files streamed with plain sequential `write_all` into the destination;
  out-of-order pieces are rejected explicitly.

There are **no staging tmp files and no renames**: GOG/Epic/Amazon write the final path from
the first byte. Completion invariants (all loud failures, never silent): the drained cursor
must equal the expected file size before the file counts as done; at run end, every store
requires **all** files completed — an unfinished file fails the run, and an ERROR run deletes
the files it touched, so a partial file can never be reported as success.

Concurrency: an adaptive in-flight window ramps up while the link delivers; a global byte
budget caps buffered data; per-host connection caps (`PER_HOST_CAP = 8`, device-validated)
spread load, scaled by distinct CDN host count for GOG/Epic.

Congestion control is **LEDBAT** (RFC 6817, `queue_delay.rs`): a background thread takes one
TCP-connect round trip per 250 ms to the CDN hosts (round-robin) and publishes the queueing
delay — RTT minus a per-host base (minimum over a 10-min history). A connect carries no body,
so its RTT inflation IS queueing, not transfer time. On each 2 s probe tick both window
controllers (Steam's and the fetch core's) yield **proportionally** (`window × (1 − 0.1 × off)`,
at most ~10%/tick, floored at the window minimum) when the queue exceeds the 40 ms target,
and the throughput-gated growth logic only runs while the queue is under target — the window
is the congestion knob; there is no byte-rate pacer. While the prober has no sample yet, the
classic logic (error-rate shrink + the Steam 5×-latency/throughput-confirm congestion shrink)
runs untouched. The `fetch-window` log lines carry `queue=…ms rtt_base=…ms` (`-` until the
first sample).

## 3. CDN server handling

- **Steam**: Steam assigns a set of content servers, but assignment ≠ quality. `cdn_probe.rs`
  measures **throughput, not ping** (on-device, geographically "wrong" alibaba out-delivered
  closer hkg caches 13 MB/s vs 1–2): each candidate gets a real 256 KiB range GET of an
  actual depot chunk, and we record connect+TTFB and body throughput. Results are cached on
  disk keyed by the assigned-server-set hash, refreshed when the cache is older than 6 h,
  the server set changes, or a cached winner was `mark_bad`'d (a host that stalls/errors
  repeatedly mid-download is recorded and the next run re-probes immediately instead of
  waiting out the TTL). Fetch failures rotate to the next probed host. The probe spawns
  lazily on the **first downloaded (non-verifying) byte** — never during prep or the verify
  sweep, which can take minutes before any fetching starts and must not burn the probe
  deadline — and is congestion-guarded (skipped while measured throughput is already
  healthy).
- **Epic**: the manifest API returns multiple CDN base URLs (`cdn_prefixes`); they become
  distinct fetch-core host keys with per-host caps and rotation on failure.
- **GOG**: the ranked mirror list from the Kotlin secure_link pass (`cdnBases`) becomes the
  fetch pool: `mirror_bases_and_hosts` dedupes by host key and keeps bases/hosts
  index-aligned — the fetch-core contract is `urls[host_idx] ↔ hosts[host_idx]`, so a
  misaligned or joined host string silently collapses the pool to one fake host.
  `per_host_cap_for` scales caps by distinct host count.
- **Amazon**: signed per-file download URLs from the Amazon API.

## 4. Verify & update flow

`isUpdateOrVerify` reuses what's already on disk instead of redownloading.

- **Steam**: existing files are verified chunk-by-chunk against the manifest (SHA-1 after
  decrypt+decompress of what would be written — i.e. the *existing bytes* are hashed).
  Chunks that match are "verify-skip": rather than moving the write cursor directly (which
  could jump a gap after a mid-file mismatch and strand later arrivals — the cursor-jump bug
  fixed in `838ed77ee`), a **Verified marker is parked in the file's pending queue at that
  offset**, and the normal ordered drain walks over markers without writing. Before
  finalize, `assert_pipeline_drained` requires every file's cursor == size and pending empty;
  a violation fails the run as *not* resume-trust-safe, so the retry re-verifies everything.
  Resume across runs uses persisted per-depot journal/progress state; a clean pause marker
  lets a resumed run trust already-written prefixes. **GOG and Epic share this exact
  Verified-marker model** via the shared `OrderedDrain` (below).
- **Manifest & key freshness**: cached manifests are validated against current metadata and
  self-healed (deleted + refetched) when stale or poisoned. Depot keys are requested via the
  depot's **owning app** (shared redist depots like 228990 belong to a different app); shared
  depots with no manifest gid for the branch are skipped by design.
- **GOG**: completed+MD5-verified files are skipped wholesale — `file_verified` **requires**
  a manifest MD5 (deliberately stricter than Java's size-only fallback): a size-only pass
  combined with the old Kotlin `setLength` pre-allocation let zero-filled files pass verify
  forever (the pre-allocation corruption hole). MD5-less manifest files fall through to the
  chunk sweep. Within a file, a **cancelled** run keeps its partial final file; the next run
  hashes **every** chunk's on-disk bytes against the manifest's decompressed MD5 into a
  per-chunk bitmap (`verified_chunks`), queues verified chunks as drain markers, and
  re-fetches **only mismatched/missing chunks** — a single corrupt mid-file chunk costs one
  chunk, not the whole suffix. The partial file is kept **whole** during the run (no prefix
  truncation at open, which would destroy verified chunks past a gap); garbage past the
  manifest size is truncated at finalize. A file whose on-disk bytes verify *whole* counts
  as complete without any fetching. No trust is involved — every kept byte re-hashes. An
  **error** run deletes the files it touched.
- **Epic**: completed files are skipped by the delta/verify pass (whole-file size + SHA-1).
  Within a file the same selective model applies per **part** (`verified_parts` bitmap +
  drain markers on the kept whole file, tail truncated at completion): a part can only
  re-verify when it covers its **whole chunk** (`offset == 0`, ≤ 64 MiB), because the
  manifest's SHA-1 spans the entire decompressed chunk — a **slice part stays unverifiable**
  and is re-fetched, but no longer forfeits verification of later parts (interior parts of
  large files are almost always whole-chunk, so nearly all progress keeps). Zero-length
  parts verify trivially.
- **Amazon**: resume-skip on `st_size == manifest size` (no hash on skip, matching the Java
  behaviour); a partial file deletes on any failed/cancelled attempt (no within-file
  resume).

Every verify path above reports the file it is re-hashing: Steam fires `DepotWriteOptions.status`
once per file in the verify-skip path, GOG fires `GogEvents::on_file_verify` per file in its
verify sweep, and Epic gets a `verify_status` closure in `run_plan`. All three surface to the
app screen's status row as "Verifying Files (k/N)" via the JNI listeners'
`onVerifying(String, int, int)` callback, cleared on the first real download progress.

## 5. Error handling

- **Rust side**: sink-level errors classify as `Retry` (network, decompress, hash mismatch,
  out-of-order arrival — the run retries/rotates) vs `Fatal` (disk/FS errors, corruption
  invariants — the run stops; a disk error is not CDN-curable). Run end always reports the
  first fatal error; an ERROR run deletes the unfinished files it touched, a CANCELLED run
  keeps them for the selective verified resume (see *Verify & update*).
- **Dispatch head-of-line rescue** (Steam): the in-flight byte budget is a hard memory cap on
  fetched-but-unwritten data, and its gate admits a chunk that sits **exactly at its file's copy
  cursor** even over budget — that chunk frees itself on arrival and drains the data parked behind
  its gap. Because a queue-front job is not necessarily that chunk (a mid-file chunk that failed
  during an error burst goes to the back of the retry FIFO while chunks fetched behind it fill the
  budget), the gate substitutes the head-of-line job whenever it refuses a non-head-of-line one.
  Without that substitution the run deadlocks — nothing in flight, budget full, and the frontier
  chunk never re-dispatched — until the watchdog below aborts the depot.
- **Stall watchdog** (Steam): if `bytes_written` stops advancing while the download isn't
  done, the engine dumps a `write-stall depot=… lock=HELD` diagnostic line and aborts the
  run with a deliberate **timeout** failure — designed to be classified transient so the
  store layer auto-retries instead of hanging forever.
- **Pool-verdict watchdog** (fetch core): if every item is dispatched and no process-pool
  verdict arrives for 60s, the driver aborts with a *no process-pool verdict* error naming
  the stuck `item:attempt` ids — the pool might be dead. The error is a suspicion, not a
  verdict of its own: `run_fetch` joins the pool threads before building the outcome, and
  if every item has an Ok verdict by then the pool merely stalled (seen on-device: two
  exFAT/FUSE writes wedged ~60 s at the tail of a 62 GB Epic run, then completed) — the
  stale watchdog error is downgraded to success with a log line.
- **Kotlin side** (`GameDownloadService.isTransientFailure`): permanent markers are checked
  first (missing depot key/manifest, no space, decrypt/parse failures, cancel); transient
  markers (timeout, connection resets, EOF, DNS, stalls, 429/5xx, no CDN servers) trigger
  auto-retry with backoff (`reportFailure`, capped attempts). HTTP status codes match on
  **digit boundaries** so a number inside a message (e.g. "idle timeout at offset 404128")
  can never be misread as a status code. The Steam download catch routes every native
  failure through this classifier — transient failures keep the queue slot and show as
  Queued; permanent ones fail visibly.
- **Progress snapshots** are persisted on failure so retries resume instead of restarting.

## 6. Building & testing

The crate is plain Rust with no Android-only test dependencies — the full suite runs on the
host:

```sh
cd app/src/main/cpp/gn-download/rust
cargo test --release
```

(`cargo build --release` must be warning-free — that is a project rule.)

Android binaries are built with the repo script, which cross-compiles both ABIs and installs
them into `app/src/main/jniLibs/`:

```sh
./tools/build-gn-download.sh          # aarch64-linux-android + armv7-linux-androideabi
```

The app packages the prebuilt `.so` directly (no Gradle native build); a normal
`./gradlew :app:assembleModernDebug` picks them up.

The test suite is behaviour-heavy on purpose: the ordered-drain component, both store
engines' sink logic (out-of-order arrival, gap parking, drain invariants, unfinalized
detection), Steam chunk crypto/verify paths, resume/journal handling, and CDN probe caching
are all covered by host tests. Anything that touches the write pipeline should add a test
here first — device time is for validating throughput and FUSE/SD behaviour, not logic.
