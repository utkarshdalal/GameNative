# iq80-leveldb (fork, consumed as a submodule)

Pure-Java port of LevelDB.

- **Source:** `upstream/` git submodule → https://github.com/jeremybernstein/leveldb, branch `gamenative`
- **Based on:** upstream https://github.com/dain/leveldb tag `0.12` (Jun 2019 — the final release)
- **License:** Apache License 2.0, unchanged. Copyright the original authors (Dain Sundstrom et al.)

Fresh clones need `git submodule update --init --recursive`; the build fails loudly if the
submodule is empty rather than producing an empty jar.

## Why a fork rather than the maven artifact

iq80 0.12 is the last release and the project is unmaintained — the concurrency bugs below are
still present on upstream `master`, and the one concurrency PR was closed unmerged. We also need
a custom Java comparator (`idb_cmp1`, for Chromium IndexedDB), which rules out every native
LevelDB JNI binding, so a pure-Java fork is the only option.

## Fork patches

Each is a separate commit on `gamenative` so it can be reviewed or reverted individually. Full
rationale is in each commit message and in the fork's `GAMENATIVE-FORK.md`.

1. **Guava `Throwables.propagate` → `RuntimeException`** (5 files). Cosmetic; that method was
   removed in Guava 33. `DbImpl` still uses `ThreadFactoryBuilder`, so Guava remains a dependency.
2. **Per-thread snappy scratch** (`Table`, `MMapTable`, `FileChannelTable`). Upstream shared one
   static `ByteBuffer` across readers, guarded by two *different* class monitors — a reader and the
   background compaction thread could decompress into it concurrently and corrupt blocks.
3. **`Options.compactionEnabled`** (default `true`). Background compaction unmaps L0 SSTs while an
   iterator is still reading them, dropping records. Also gates both L0 write throttles: with
   compaction off nothing reduces the L0 count, so throttling there would deadlock.
4. **Synchronous memtable flush when compaction is disabled.** Pairs with 3 — without it a frozen
   memtable is never flushed. Also avoids a race on the background path, where `writeLevel0Table`
   releases the mutex mid-write and loses records.

3 and 4 are a matched pair; do not carry one without the other.

2, 3 and 4 are all concurrency correctness fixes and all present the same way: a key that was
definitely written is missing after reopen.

## What stays in GameNative

This directory keeps only the build wrapper (`build.gradle.kts`) and this file. The `--release 8`
compile constraint lives here, not in the fork: it exists because the JDK-17 covariant
`ByteBuffer`/`MappedByteBuffer` overrides only exist on Android 14+ (API 34), so compiling at
release 8 binds to the Java-8 `Buffer` signatures present on every Android level. Without it,
leveldb mmap reads throw `NoSuchMethodError` on API < 34 (repro: Android 13 / WebView 109).
That is an Android concern, not a leveldb one.
