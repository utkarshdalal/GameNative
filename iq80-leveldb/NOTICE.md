# iq80-leveldb (vendored fork)

Vendored source of **iq80 leveldb 0.12** — a pure-Java port of LevelDB.

- Upstream: https://github.com/dain/leveldb (tag `0.12`, Jun 2019 — the final release)
- License: **Apache License 2.0** (http://www.apache.org/licenses/LICENSE-2.0.html)
- Copyright the original authors (Dain Sundstrom et al.)

## Why vendored (not a Maven dependency)

iq80 0.12 is the last release and is unmaintained; the bug below is unfixed upstream
(still present on `master`, and the one concurrency PR was closed unmerged). We need a
custom Java comparator (`idb_cmp1` for Chromium IndexedDB), which rules out native LevelDB
JNI ports — so a pure-Java fork is the only option. Vendoring the source (rather than a
prebuilt jar or a jitpack artifact) keeps the build reproducible from source.

## Patches vs upstream

### 1. Per-thread snappy scratch (the reason we forked)

`org.iq80.leveldb.table.Table.uncompressedScratch` was a single **static** direct
`ByteBuffer`, used as the snappy-decompression target in `MMapTable.readBlock` /
`FileChannelTable.readBlock`. Those two sites guard it with `synchronized(MMapTable.class)`
and `synchronized(FileChannelTable.class)` respectively — **different monitors over the same
shared buffer**. A reader thread and iq80's background-compaction thread therefore decompress
into it concurrently, clobbering each other's output → silently dropped/garbled records under
load.

Fix: make the scratch a `ThreadLocal<ByteBuffer>` (per-thread, grown per-thread) and drop the
now-pointless class locks, so concurrent reads are both correct and no longer serialized.

Patched files: `table/Table.java`, `table/MMapTable.java`, `table/FileChannelTable.java`.
Fork branch: `jb/threadlocal-scratch` in the local leveldb checkout.

### 2. Drop guava `Throwables.propagate`

Upstream used `com.google.common.base.Throwables.propagate(e)` on five error paths.
`propagate` was removed in guava 20+, so calling it against the app's guava 33.x is a latent
`NoSuchMethodError` (it only fires on IOException/cleaner-failure paths that don't occur in
normal reads). `propagate` always throws — for a checked `IOException` it wraps in a
`RuntimeException` — so the behaviour-preserving, guava-version-independent replacement is a
plain `throw new RuntimeException(e)`. (Guava's own recommended replacement, `throwIfUnchecked`,
is unavailable here — the module compiles `compileOnly guava:19.0`, predating it.)

Patched files: `impl/DbImpl.java`, `impl/DbLock.java`, `table/TableBuilder.java`,
`table/Table.java`, `util/ByteBufferSupport.java`.
