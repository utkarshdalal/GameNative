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
| `store_dl/ordered_drain.rs` | Shared per-file ordered writer used by GOG and Epic (the Steam model) |
| `store_dl/steam/depot_downloader.rs` | Steam orchestration: manifests, depot keys, DLC/redist depots, cache validation |
| `store_dl/steam/depot_writer.rs` | Steam depot pipeline: verify/resume, chunk dispatch, ordered writes, stall watchdog |
| `store_dl/steam/cdn_client.rs` / `cdn_probe.rs` | Steam CDN client (manifest/chunk GETs) and throughput-based server probing |
| `store_dl/steam/depot_chunk.rs` / `crypto.rs` | Steam chunk decrypt + decompress (`content_manifest.rs`, `proto_wire.rs` + `pb/` parse the manifests; `depot_config.rs` keeps depot metadata + the resume journal) |
| `store_dl/gog/` | GOG engine (gen1 range-GETs + gen2 chunked galaxy builds) |
| `store_dl/epic/` | Epic engine (per-(file, part) fetching, streamed writes, verified-prefix resume) |
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
- **GOG** (`store_dl/gog/engine.rs`): chunks inflate into an in-memory buffer first
  (MD5-verified), then only *verified* chunks enter the file's `OrderedDrain`
  (`store_dl/ordered_drain.rs`) — the shared component implementing the Steam model
  (pending BTreeMap + cursor + coalesced 16 MiB batches). The final file opens lazily on the
  first drained write. gen1 (range-GET) files stream sequentially by construction.
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

## 3. CDN server handling

- **Steam**: Steam assigns a set of content servers, but assignment ≠ quality. `cdn_probe.rs`
  measures **throughput, not ping** (on-device, geographically "wrong" alibaba out-delivered
  closer hkg caches 13 MB/s vs 1–2): each candidate gets a real 256 KiB range GET of an
  actual depot chunk, and we record connect+TTFB and body throughput. Results are cached on
  disk keyed by the assigned-server-set hash, refreshed when the cache is older than 6 h,
  the server set changes, or a cached winner was `mark_bad`'d (a host that stalls/errors
  repeatedly mid-download is recorded and the next run re-probes immediately instead of
  waiting out the TTL). Fetch failures rotate to the next probed host.
- **Epic**: the manifest API returns multiple CDN base URLs (`cdn_prefixes`); they become
  distinct fetch-core host keys with per-host caps and rotation on failure.
- **GOG**: one CDN host gets the whole worker ceiling (`per_host_cap_for` scales by host
  count).
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
  lets a resumed run trust already-written prefixes.
- **Manifest & key freshness**: cached manifests are validated against current metadata and
  self-healed (deleted + refetched) when stale or poisoned. Depot keys are requested via the
  depot's **owning app** (shared redist depots like 228990 belong to a different app); shared
  depots with no manifest gid for the branch are skipped by design.
- **GOG**: completed+MD5-verified files are skipped wholesale. Within a file, a **cancelled**
  run keeps its partial final file; the next run re-reads it chunk-by-chunk, hashing each
  chunk's on-disk bytes against the manifest's decompressed MD5 (`verified_prefix`), keeps
  the longest fully verified prefix, truncates any tail past it, and re-fetches only the
  remaining chunks. A file whose on-disk bytes verify *whole* counts as complete without any
  fetching. No trust is involved — every kept byte re-hashes against the manifest. An
  **error** run deletes the files it touched.
- **Epic**: completed files are skipped by the delta/verify pass (whole-file size + SHA-1).
  Within a file the same verified-prefix resume applies to the partial final file, with one
  caveat: a part can only re-verify when it covers its **whole chunk** (`offset == 0`),
  because the manifest's SHA-1 spans the entire decompressed chunk — a slice can never match
  it, so the prefix stops at the first slice part (interior parts of large files are almost
  always whole-chunk, so nearly all progress keeps).
- **Amazon**: resume-skip on `st_size == manifest size` (no hash on skip, matching the Java
  behaviour); a partial file deletes on any failed/cancelled attempt (no within-file
  resume).

Every verify path above reports the file it is re-hashing: Steam fires `DepotWriteOptions.status`
once per file in the verify-skip path, GOG fires `GogEvents::on_file_verify` per file in its
verify sweep, and Epic gets a `verify_status` closure in `run_plan`. All three surface to the
app screen's status row as "Verifying <path>" via the JNI listeners' `onVerifying(String)`
callback, cleared on the first real download progress.

## 5. Error handling

- **Rust side**: sink-level errors classify as `Retry` (network, decompress, hash mismatch,
  out-of-order arrival — the run retries/rotates) vs `Fatal` (disk/FS errors, corruption
  invariants — the run stops; a disk error is not CDN-curable). Run end always reports the
  first fatal error; an ERROR run deletes the unfinished files it touched, a CANCELLED run
  keeps them for the verified-prefix resume (see *Verify & update*).
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
