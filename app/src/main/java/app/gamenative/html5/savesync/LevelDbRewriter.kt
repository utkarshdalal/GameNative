package app.gamenative.html5.savesync

import java.io.File
import org.iq80.leveldb.CompressionType
import org.iq80.leveldb.Options
import org.iq80.leveldb.WriteBatch
import org.iq80.leveldb.WriteOptions
import org.iq80.leveldb.impl.Iq80DBFactory
import org.xerial.snappy.Snappy
import timber.log.Timber

// origin-prefix strip/rewrite helper for chromium leveldb.

// strategy A: copies a leveldb from src → dst, rewriting every key that starts
// with fromPrefix to instead start with toPrefix. values byte-for-byte preserved.
// iq80+snappy+idb_cmp1 -- pure-java open, universal ABI.

// .blob/ handling -- INTERNAL_ID_COPY_AS_IS: blob filenames are chromium-
// internal sequential ids. rewrite moves leveldb + blob dir together per origin-prefix pair,
// ids stay internally consistent. OUTBOUND uses reference-aware copy (copyLiveBlobs) to skip
// orphan physical blobs left behind by chromium's lazy blob-journal GC (blob-
// churn). INBOUND inlines sidecar bytes into leveldb values and leaves webview blob dir empty.
object LevelDbRewriter {

    // returns the set of live `(dbId, blobNumber)` blob refs discovered in src's blob_info
    // records. OUTBOUND callers feed this to copyLiveBlobs to filter out orphan physical files.
    // INBOUND callers ignore the return value (blob bytes already inlined into leveldb values).
    fun rewriteIdbOrigin(
        src: File,
        dst: File,
        fromOriginFilename: String,
        toOriginFilename: String,
        inlineBlobsFromDir: File? = null,
    ): Set<Pair<Int, Int>> {
        if (!src.isDirectory) throw SaveSyncFailure.PathMissing(src.absolutePath)
        // FS-canonical titles (e.g. most Electron) never commit to chromium IDB -- the dir
        // exists only as chromium's "opened but never wrote" shell (LOG+LOCK+LOG.old). dumb
        // leveldb open would burn the 10s CURRENT-race poll and then throw Corruption, which
        // also blocks exitSteamApp via runBlocking. detect + no-op cheaply. matches the
        // "sync leveldb only if there IS leveldb content" rule -- no config/flag needed.
        if (isEmptyLeveldbShell(src)) {
            Timber.tag("LevelDbRewriter").i(
                "rewriteIdbOrigin: src has no committed leveldb state, skipping. src=%s",
                src.absolutePath,
            )
            return emptySet()
        }
        // MIRROR semantic: per-origin IDB leveldb is safe to wipe entirely. without this,
        // stale SST + log files from prior sessions remain on disk and the rebuilt
        // MANIFEST may either reference them (Frankenstein DB) or leave them as dead bytes
        // that chromium/iq80 may still touch during recovery. observed symptom: game opens
        // but can't load saves because new+old records overlap.
        wipeDirectoryContents(dst)
        dst.mkdirs()
        val fromBytes = OriginCodec.utf16BePrefixBytes(fromOriginFilename)
        val toBytes = OriginCodec.utf16BePrefixBytes(toOriginFilename)

        // SHADOW src into a temp dir -- iq80 mutates the leveldb dir on open (compaction can
        // write new MANIFEST/CURRENT files even with createIfMissing=false). without this,
        // SteamAutoCloud sees the mutated wine-side files as "local changes" and uploads
        // them, polluting cloud and breaking the canonical reference state.
        var liveRefs: Set<Pair<Int, Int>> = emptySet()
        // pre-open snapshot for rollback on failed open. dst was just wiped, so this is
        // empty -- but capturing makes the rollback symmetric with rewriteLsOrigin (where
        // dst contains other origins' data). on failure we delete any files iq80 created
        // during recovery so SteamAutoCloud doesn't sweep them into cloud.
        val dstPreOpenFiles = dst.listFiles()?.map { it.name }?.toSet().orEmpty()
        withShadowCopy(src) { srcShadow ->
            withLdbAsSst(srcShadow) {
                LeveldbManifestSynthesizer.synthesizeManifest(srcShadow, useIdb1 = true)
                withLdbAsSst(dst) {
                    try {
                        openDb(srcShadow, readOnly = true, useIdb1 = true).use { srcDb ->
                            openDb(dst, readOnly = false, useIdb1 = true).use { dstDb ->
                                // blob_info table drives inline on
                                // inbound (ff 11 01 → sidecar bytes). same
                                // table also serves outbound: caller filters copyLiveBlobs by
                                // this set to skip chromium blob-journal GC orphans. always
                                // build; cost is one parseIdbObjectStoreKey per key.
                                val blobRefMap = buildBlobRefMapFromDb(srcDb)
                                liveRefs = blobRefMap.values
                                    .map { it.dbId to it.blobNumber }
                                    .toSet()
                                val blobInfoKeysToSkip = blobRefMap.values
                                    .map { it.fullBlobInfoKey }
                                    .toSet()
                                var inlined = 0
                                srcDb.iterator().use { iter ->
                                    iter.seekToFirst()
                                    while (iter.hasNext()) {
                                        val entry = iter.next()
                                        // skip blob_info records -- their on-disk state
                                        // becomes stale once the main record is inlined.
                                        if (inlineBlobsFromDir != null && entry.key.asByteArrayWrapper() in blobInfoKeysToSkip) {
                                            continue
                                        }
                                        val newKey = rewriteIdbDatabaseNameKey(entry.key, fromBytes, toBytes)
                                        val outKey = newKey ?: entry.key
                                        var outValue = entry.value
                                        if (inlineBlobsFromDir != null) {
                                            // INLINE FIRST, DECOMPRESS AFTER -- chromium applies the
                                            // snappy wrapper AFTER blob externalization, so ff 11 01
                                            // sidecars themselves may contain ff 11 02 streams once
                                            // substituted. running decompress after inline covers:
                                            // (a) ff 11 02 main records (inline is a no-op, decompress runs)
                                            // (b) ff 11 01 refs (inline substitutes sidecar, decompress runs on result)
                                            // (c) native records (both no-ops)
                                            outValue = maybeInlineBlobValue(entry.key, outValue, blobRefMap, inlineBlobsFromDir)
                                            outValue = maybeDecompressSnappyValue(outValue)
                                        }
                                        if (outValue !== entry.value) inlined++
                                        dstDb.put(outKey, outValue)
                                    }
                                }
                                if (inlineBlobsFromDir != null) {
                                    Timber.tag("LevelDbRewriter").i(
                                        "rewriteIdbOrigin(inline): inlined=%d blobInfoSkipped=%d",
                                        inlined, blobInfoKeysToSkip.size,
                                    )
                                }
                                // DIAG: detect silent value-byte corruption between src and dst.
                                // gated -- the second full src rescan + dst rescan + SHA-256 over
                                // every value doubles per-sync IO; only pay it in debug builds.
                                if (app.gamenative.BuildConfig.DEBUG) {
                                    logIdbDiag(srcDb, dstDb)
                                }
                            }
                        }
                    } catch (f: SaveSyncFailure) {
                        rollbackNewFiles(dst, dstPreOpenFiles)
                        throw f
                    } catch (t: Throwable) {
                        rollbackNewFiles(dst, dstPreOpenFiles)
                        throw classifyFailure(t, src, dst)
                    } finally {
                        // iq80 leaves a 0-byte LOCK on close (success path) AND on failed open.
                        // run cleanup in finally so failures don't leak LOCK to cloud.
                        removeLeveldbLock(dst)
                    }
                }
            }
        }
        return liveRefs
    }

    // Local Storage origin rewrite. three key shapes per chromium
    // LS format (all ASCII):
    // "_<URL-origin>\0\x01<user-key>" offset 1 after the "_" (type byte 0x01 optional, tail preserved)
    // "METAACCESS:<URL-origin>" offset 11 after "METAACCESS:" (per-origin GC timestamp)
    // "META:<URL-origin>" offset 5 after "META:" (per-origin quota metadata)
    
    // shape order in rewriteLsKeyIfActive: underscore → METAACCESS → META.
    // METAACCESS BEFORE META because "METAACCESS:" starts with "META:" -- reversed order lets
    // shape-2 match the META: prefix, exact-length compare fails, key passes through stale.

    // activeContainerOriginUrl filters out keys whose origin slice doesn't exactly match --
    // co-resident origins in shared Default/ survive byte-for-byte.
    fun rewriteLsOrigin(
        src: File,
        dst: File,
        fromOriginUrl: String,
        toOriginUrl: String,
        activeContainerOriginUrl: String,
    ) {
        if (!src.isDirectory) throw SaveSyncFailure.PathMissing(src.absolutePath)
        // same uncommitted-shell guard as rewriteIdbOrigin -- fs-canonical titles leave the LS
        // leveldb dir as chromium's empty shell. skip cheaply; no 10s poll, no Corruption throw.
        if (isEmptyLeveldbShell(src)) {
            Timber.tag("LevelDbRewriter").i(
                "rewriteLsOrigin: src has no committed leveldb state, skipping. src=%s",
                src.absolutePath,
            )
            return
        }
        dst.mkdirs()
        val fromAscii = OriginCodec.asciiKeyOriginFromUrl(fromOriginUrl)
        val toAscii = OriginCodec.asciiKeyOriginFromUrl(toOriginUrl)
        val activeAscii = OriginCodec.asciiKeyOriginFromUrl(activeContainerOriginUrl)

        // pre-open snapshot for rollback on failed open. LS leveldb is shared across
        // origins so dst contains other games' data -- we can't wipe; instead we record
        // what was there and on failure delete only files iq80 created during recovery.
        val dstPreOpenFiles = dst.listFiles()?.map { it.name }?.toSet().orEmpty()
        // SHADOW src -- same rationale as rewriteIdbOrigin. iq80 mutates leveldb on open even
        // with readOnly=true, polluting the wine-side dir + triggering SteamAutoCloud uploads.
        withShadowCopy(src) { srcShadow ->
            withLdbAsSst(srcShadow) {
                LeveldbManifestSynthesizer.synthesizeManifest(srcShadow, useIdb1 = false)
                withLdbAsSst(dst) {
                    try {
                        openDb(srcShadow, readOnly = true, useIdb1 = false).use { srcDb ->
                            openDb(dst, readOnly = false, useIdb1 = false).use { dstDb ->
                                // MIRROR semantic: purge dst's existing keys for the destination
                                // origin BEFORE writing new ones. LS leveldb is shared across
                                // origins (other co-resident games' keys must be preserved), so
                                // we can't wipe the whole DB -- only delete keys belonging to
                                // THIS game's destination origin. each container has a unique
                                // origin URL (one localhost subdomain per containerId), so
                                // filtering by toAscii alone is sufficient -- co-resident
                                // containers' keys live at different slices and won't match.
                                purgeKeysForOrigin(dstDb, toAscii)
                                srcDb.iterator().use { iter ->
                                    iter.seekToFirst()
                                    while (iter.hasNext()) {
                                        val e = iter.next()
                                        val newKey = rewriteLsKeyIfActive(e.key, fromAscii, toAscii, activeAscii)
                                        dstDb.put(newKey ?: e.key, e.value)
                                    }
                                }
                            }
                        }
                    } catch (f: SaveSyncFailure) {
                        rollbackNewFiles(dst, dstPreOpenFiles)
                        throw f
                    } catch (t: Throwable) {
                        rollbackNewFiles(dst, dstPreOpenFiles)
                        throw classifyFailure(t, src, dst)
                    } finally {
                        removeLeveldbLock(dst)
                    }
                }
            }
        }
    }

    // iq80 creates a 0-byte LOCK on open and leaves it behind on close. Steam Cloud's
    // recursive patterns (e.g. save/IndexedDB/**, save/Local Storage/**) sweep LOCK into the
    // tracked-file set and persistently flag "Need to forget". clearing the file keeps cloud
    // free of runtime advisory locks. safe on WebView dst too -- chromium creates its own
    // LOCK on next open.
    private fun removeLeveldbLock(dir: File) {
        if (!dir.isDirectory) return
        val lock = File(dir, "LOCK")
        if (lock.isFile) {
            if (!lock.delete()) {
                Timber.tag("LevelDbRewriter").w("LOCK delete failed for %s", lock.absolutePath)
            }
        }
    }

    // shadow-copy the src dir into a temp dir so iq80 mutations on open (compaction,
    // MANIFEST/CURRENT rewrites) land in the temp instead of the real wine path.
    // without this, SteamAutoCloud detects mtime/SHA changes on wine files and uploads
    // them, polluting cloud state on every test cycle.
    
    // copyRecursively returns FALSE on partial failure (locked file, permission, etc.) --
    // it does NOT throw. Failure leaves shadow partially populated or empty and iq80's
    // subsequent open reports "Database X does not exist" because CURRENT is missing.
    // Check the return value + presence of CURRENT so any future breakage is loud.
    
    // Race-stopgap: SteamAutoCloud's per-file download into the wine-prefix mirror is
    // concurrent with `Html5SaveSyncService.syncInbound`. If we run before CURRENT has
    // streamed in, src is partial. Poll src for CURRENT up to STALE_WAIT_MS before giving
    // up; on appearance, re-copy. Proper fix is to await SteamAutoCloud completion in the
    // service orchestrator -- TODO captured separately.
    private inline fun <T> withShadowCopy(src: File, block: (File) -> T): T {
        val shadow = java.nio.file.Files.createTempDirectory("ldb-shadow-").toFile()
        return try {
            var ok = src.copyRecursively(shadow, overwrite = true)
            if (!File(shadow, "CURRENT").isFile && !File(src, "CURRENT").isFile) {
                // src missing CURRENT -- likely SteamAutoCloud download still in flight. wait + retry.
                val deadline = System.currentTimeMillis() + STALE_WAIT_MS
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(STALE_POLL_MS)
                    if (File(src, "CURRENT").isFile) {
                        Timber.tag("LevelDbRewriter").i(
                            "withShadowCopy: src CURRENT appeared after wait; re-copying. src=%s",
                            src.absolutePath,
                        )
                        shadow.deleteRecursively()
                        shadow.mkdirs()
                        ok = src.copyRecursively(shadow, overwrite = true)
                        break
                    }
                }
            }
            if (!ok) {
                val srcFiles = src.listFiles()?.joinToString(", ") { it.name } ?: "<null>"
                val shadowFiles = shadow.listFiles()?.joinToString(", ") { it.name } ?: "<null>"
                Timber.tag("LevelDbRewriter").w(
                    "withShadowCopy: copyRecursively returned false. src=%s [%s] shadow=%s [%s]",
                    src.absolutePath, srcFiles, shadow.absolutePath, shadowFiles,
                )
            }
            val current = File(shadow, "CURRENT")
            if (!current.isFile) {
                val srcHasCurrent = File(src, "CURRENT").isFile
                val shadowFiles = shadow.listFiles()?.joinToString(", ") { it.name } ?: "<null>"
                throw SaveSyncFailure.Corruption(
                    src.absolutePath,
                    IllegalStateException(
                        "withShadowCopy: CURRENT missing in shadow after copy " +
                            "(srcHasCurrent=$srcHasCurrent copyOk=$ok shadowContents=[$shadowFiles])",
                    ),
                )
            }
            pruneStaleArtifacts(shadow)
            block(shadow)
        } finally {
            shadow.deleteRecursively()
        }
    }

    // remove obsolete logs and manifests from the shadow before iq80 opens. cross-device
    // restore can leave a leveldb dir with multiple generations colliding: SteamCloud download
    // brings the live state (`CURRENT` → MANIFEST-N + a single live `<n>.log`) on top of an
    // older local generation (older MANIFEST + older `<m>.log`). iq80's `needsCompaction()`
    // returns true if `numLogFiles() > 1`, which triggers background compaction during open;
    // any inconsistency in the merged state then surfaces as `Could not open table N`.
    //
    // pruning rules (no leveldb-format parsing required):
    //   - keep `CURRENT`'s referenced MANIFEST. delete every other `MANIFEST-*`.
    //   - keep ONLY the highest-numbered `<n>.log` (chromium leveldb's monotonic numbering
    //     guarantees the live log has the highest number when we trust CURRENT's manifest).
    //   - leave `.ldb`/`.sst` files alone -- they're referenced by potentially-historical
    //     MANIFEST entries and we don't parse to know which.
    //   - leave `LOG`/`LOG.old` alone -- runtime telemetry, no correctness role.
    internal fun pruneStaleArtifacts(dir: File) {
        runCatching {
            val current = File(dir, "CURRENT")
            val liveManifest = current.takeIf { it.isFile }?.readText(Charsets.UTF_8)?.trim()
                ?.takeIf { it.startsWith("MANIFEST-") }
            if (liveManifest != null) {
                dir.listFiles { _, name -> name.startsWith("MANIFEST-") && name != liveManifest }
                    ?.forEach { stale ->
                        if (stale.delete()) {
                            Timber.tag("LevelDbRewriter").i("pruned stale manifest %s", stale.name)
                        } else {
                            Timber.tag("LevelDbRewriter").w("prune failed for %s", stale.name)
                        }
                    }
            }
            val logs = dir.listFiles { _, name -> name.matches(Regex("\\d+\\.log")) }.orEmpty()
            if (logs.size > 1) {
                val keep = logs.maxByOrNull { it.nameWithoutExtension.toLongOrNull() ?: 0L }
                logs.filter { it != keep }.forEach { stale ->
                    if (stale.delete()) {
                        Timber.tag("LevelDbRewriter").i(
                            "pruned stale log %s (kept %s)", stale.name, keep?.name,
                        )
                    } else {
                        Timber.tag("LevelDbRewriter").w("prune failed for %s", stale.name)
                    }
                }
            }
        }.onFailure {
            Timber.tag("LevelDbRewriter").w(it, "pruneStaleArtifacts failed for %s", dir.absolutePath)
        }
    }

    private const val STALE_WAIT_MS = 10_000L
    private const val STALE_POLL_MS = 250L

    // delete all LS keys whose origin slice matches originAscii. covers all three
    // shapes: `_<origin>\x00...`, `META:<origin>`, `METAACCESS:<origin>`.
    //
    // no AND-gate against a "second origin" -- each container has a unique origin URL
    // (one localhost subdomain per containerId), so filtering by originAscii alone is
    // already safe on the shared LS leveldb: co-resident containers' keys live at
    // different origin slices and won't match. the prior AND-gate against the active
    // container's slice was unsatisfiable on inbound (active=`file://`, target=WebView
    // origin) and silently produced deleted=0, leaving stale state untouched.
    // defensive: empty originAscii → NO purge (avoids wiping everything on caller bug).
    private fun purgeKeysForOrigin(
        db: org.iq80.leveldb.DB,
        originAscii: ByteArray,
    ) {
        if (originAscii.isEmpty()) {
            Timber.tag("LevelDbRewriter").w(
                "purgeKeysForOrigin: empty originAscii → no purge defensive",
            )
            return
        }
        val toDelete = mutableListOf<ByteArray>()
        db.iterator().use { iter ->
            iter.seekToFirst()
            while (iter.hasNext()) {
                val k = iter.next().key
                // shape 1: `_<origin>\x00...` -- origin slice from offset 1 to first NUL
                if (k.isNotEmpty() && k[0] == '_'.code.toByte()) {
                    val nulSep = indexOfByte(k, 0, fromIndex = 1)
                    if (nulSep > 1 &&
                        regionEquals(k, 1, nulSep - 1, originAscii, 0, originAscii.size)
                    ) {
                        toDelete.add(k); continue
                    }
                }
                // shape 3: `METAACCESS:<origin>` (must check BEFORE META: since prefix overlaps)
                val metaAccessPrefix = "METAACCESS:".toByteArray(Charsets.US_ASCII)
                if (k.size >= metaAccessPrefix.size &&
                    regionEquals(k, 0, metaAccessPrefix.size, metaAccessPrefix, 0, metaAccessPrefix.size)
                ) {
                    val originLen = k.size - metaAccessPrefix.size
                    if (regionEquals(k, metaAccessPrefix.size, originLen, originAscii, 0, originAscii.size)) {
                        toDelete.add(k); continue
                    }
                }
                // shape 2: `META:<origin>`
                val metaPrefix = "META:".toByteArray(Charsets.US_ASCII)
                if (k.size >= metaPrefix.size &&
                    regionEquals(k, 0, metaPrefix.size, metaPrefix, 0, metaPrefix.size)
                ) {
                    val originLen = k.size - metaPrefix.size
                    if (regionEquals(k, metaPrefix.size, originLen, originAscii, 0, originAscii.size)) {
                        toDelete.add(k)
                    }
                }
            }
        }
        toDelete.forEach { db.delete(it) }
        Timber.tag("LevelDbRewriter").d(
            "purgeKeysForOrigin: deleted=%d targetOrigin=%s",
            toDelete.size,
            String(originAscii, Charsets.US_ASCII),
        )
    }

    // public LS-purge entry point for callers outside the rewrite pipeline (e.g. container
    // uninstall cleanup). opens the shared LS leveldb, deletes every key for `originUrl`,
    // and closes cleanly. best-effort: if chromium currently holds the dir's LOCK (live
    // WebView elsewhere on a co-resident origin), iq80 open fails -- we surface the error
    // so the caller's runCatching can log + fall through.
    //
    // wraps in withLdbAsSst because chromium names SSTables `.ldb` (post-2014) while iq80
    // still expects `.sst` -- without the rename, iq80 throws "Could not open table N" on
    // iterator construction. wipes LOCK on exit for parity with the rewrite pipeline.
    fun purgeLsOrigin(lsDir: File, originUrl: String) {
        if (!lsDir.isDirectory) {
            Timber.tag("LevelDbRewriter").i(
                "purgeLsOrigin: lsDir missing, nothing to purge. lsDir=%s",
                lsDir.absolutePath,
            )
            return
        }
        if (isEmptyLeveldbShell(lsDir)) {
            Timber.tag("LevelDbRewriter").i(
                "purgeLsOrigin: empty leveldb shell, nothing to purge. lsDir=%s",
                lsDir.absolutePath,
            )
            return
        }
        val originAscii = OriginCodec.asciiKeyOriginFromUrl(originUrl)
        withLdbAsSst(lsDir) {
            try {
                openDb(lsDir, readOnly = false, useIdb1 = false).use { db ->
                    purgeKeysForOrigin(db, originAscii)
                }
            } finally {
                removeLeveldbLock(lsDir)
            }
        }
    }

    // resolve the OPFS bucket directory name (e.g. "002") chromium assigned to `originUrl`,
    // from the shared `File System/Origins` index (chromium SandboxOriginDatabase: key
    // "ORIGIN:<scheme_host_port>", value = bucket dir name). caller deletes File System/<bucket>
    // to wipe an origin's OPFS on uninstall -- WebStorage.deleteOrigin + the LS/IDB deletes do
    // NOT cover OPFS, so a stale bucket otherwise survives and a reinstall reads it instead of
    // the cloud-restored saves.
    //
    // read via shadow-copy (NOT in place): this index is shared by EVERY html5 origin, so an
    // in-place iq80 open -- which mutates on recovery -- could corrupt other games' OPFS
    // mappings. returns null when the index is absent/empty or the origin never opened OPFS.
    fun resolveOpfsBucketDir(originsDir: File, originUrl: String): String? {
        if (!originsDir.isDirectory || isEmptyLeveldbShell(originsDir)) return null
        val key = ("ORIGIN:" + OriginCodec.filenameFromUrl(originUrl)).toByteArray(Charsets.US_ASCII)
        return runCatching {
            withShadowCopy(originsDir) { shadow ->
                withLdbAsSst(shadow) {
                    openDb(shadow, readOnly = true, useIdb1 = false).use { db ->
                        db.get(key)?.let { String(it, Charsets.US_ASCII).trim() }?.takeIf { it.isNotBlank() }
                    }
                }
            }
        }.onFailure {
            Timber.tag("LevelDbRewriter").w(it, "resolveOpfsBucketDir failed origin=%s", originUrl)
        }.getOrNull()
    }

    // chromium IDB DatabaseNameKey: 5-byte prefix `00 00 00 00 C9` (length-packed + type 0xC9).
    private fun isDbNameKey(k: ByteArray): Boolean =
        k.size >= 5 && k[0] == 0.toByte() && k[1] == 0.toByte() &&
            k[2] == 0.toByte() && k[3] == 0.toByte() &&
            (k[4].toInt() and 0xFF) == 0xC9

    // DEBUG-only integrity diagnostic: re-walks src + dst, tallying entry counts, value byte
    // totals, DBKey counts, and SHA-256 over all values to detect silent value-byte corruption
    // between the source and rewritten leveldb. costs a second full src scan + a full dst scan
    // so it's gated to debug builds; output format preserved verbatim for log-grep continuity.
    private fun logIdbDiag(srcDb: org.iq80.leveldb.DB, dstDb: org.iq80.leveldb.DB) {
        var srcCount = 0
        var srcValueBytesTotal = 0L
        var srcDbKeys = 0
        val srcDigest = java.security.MessageDigest.getInstance("SHA-256")
        srcDb.iterator().use { iter ->
            iter.seekToFirst()
            while (iter.hasNext()) {
                val entry = iter.next()
                srcCount++
                srcValueBytesTotal += entry.value.size
                srcDigest.update(entry.value)
                if (isDbNameKey(entry.key)) srcDbKeys++
            }
        }
        var dstCount = 0
        var dstValueBytesTotal = 0L
        var dstDbKeys = 0
        val dstDigest = java.security.MessageDigest.getInstance("SHA-256")
        dstDb.iterator().use { iter ->
            iter.seekToFirst()
            while (iter.hasNext()) {
                val entry = iter.next()
                dstCount++
                dstValueBytesTotal += entry.value.size
                dstDigest.update(entry.value)
                if (isDbNameKey(entry.key)) dstDbKeys++
            }
        }
        val srcSha = srcDigest.digest().joinToString("") { "%02x".format(it) }
        val dstSha = dstDigest.digest().joinToString("") { "%02x".format(it) }
        Timber.tag("DiagIDB").i(
            "src=%d dst=%d srcBytes=%d dstBytes=%d srcDBKeys=%d dstDBKeys=%d srcSha=%s dstSha=%s match=%s",
            srcCount, dstCount, srcValueBytesTotal, dstValueBytesTotal,
            srcDbKeys, dstDbKeys, srcSha.take(16), dstSha.take(16),
            srcSha == dstSha && srcValueBytesTotal == dstValueBytesTotal,
        )
    }

    // recursive wipe of dir contents (keeps the dir itself). used by mirror-semantic
    // rewrites where dst is per-origin (IDB leveldb dir, IDB blob dir).
    private fun wipeDirectoryContents(dir: File) {
        if (!dir.isDirectory) return
        dir.listFiles()?.forEach { it.deleteRecursively() }
    }

    // chromium creates LOG / LOCK / LOG.old as soon as a leveldb is opened, even before any
    // writes land. CURRENT + MANIFEST-* only appear after the first commit. a dir with only
    // those runtime-state files (or empty) means "leveldb was opened but never wrote" -- there's
    // nothing to sync. distinct from a partial-download race (where MANIFEST-* or .ldb fragments
    // are present without CURRENT yet) -- that case still goes through withShadowCopy's polling.
    private fun isEmptyLeveldbShell(dir: File): Boolean {
        if (!dir.isDirectory) return true
        val files = dir.listFiles()?.filter { it.isFile } ?: return true
        if (files.isEmpty()) return true
        val runtimeOnly = setOf("LOG", "LOCK", "LOG.old")
        return files.all { it.name in runtimeOnly }
    }

    // returns rewritten key or null (null = not an origin-bearing key OR active filter rejected).
    internal fun rewriteLsKeyIfActive(
        rawKey: ByteArray,
        fromOriginAscii: ByteArray,
        toOriginAscii: ByteArray,
        activeContainerOriginAscii: ByteArray,
    ): ByteArray? {
        // shape 1: "_<origin>\0<user-key>"
        if (rawKey.isNotEmpty() && rawKey[0] == '_'.code.toByte()) {
            val nullSep = indexOfByte(rawKey, 0, fromIndex = 1)
            if (nullSep < 0) return null
            val originSliceLen = nullSep - 1
            // active-origin filter: regionEquals (not startsWith) guards against superstring
            // decoys (e.g. "https://game-steam_3792100" must NOT match "https://game-steam_379210")
            if (!regionEquals(rawKey, 1, originSliceLen, activeContainerOriginAscii, 0, activeContainerOriginAscii.size)) {
                return null
            }
            if (!regionEquals(rawKey, 1, originSliceLen, fromOriginAscii, 0, fromOriginAscii.size)) {
                return null
            }
            return byteArrayOf('_'.code.toByte()) + toOriginAscii + rawKey.copyOfRange(nullSep, rawKey.size)
        }
        // shape 3: "METAACCESS:<origin>" -- per-origin last-accessed metadata (Chromium LS GC).
        // MUST be checked BEFORE shape 2 "META:" -- "METAACCESS:" starts with "META:" so naive
        // ordering matches META first, exact-length compare fails, key passes through stale.
        // Gap C fix -- without this, stale origin leaks to PC side and
        // desktop Chromium may treat origin as "never accessed" and GC the data.
        val metaAccessPrefix = "METAACCESS:".toByteArray(Charsets.US_ASCII)
        if (rawKey.size >= metaAccessPrefix.size &&
            regionEquals(rawKey, 0, metaAccessPrefix.size, metaAccessPrefix, 0, metaAccessPrefix.size)
        ) {
            val originSliceLen = rawKey.size - metaAccessPrefix.size
            if (!regionEquals(rawKey, metaAccessPrefix.size, originSliceLen, activeContainerOriginAscii, 0, activeContainerOriginAscii.size)) {
                return null
            }
            if (!regionEquals(rawKey, metaAccessPrefix.size, originSliceLen, fromOriginAscii, 0, fromOriginAscii.size)) {
                return null
            }
            return metaAccessPrefix + toOriginAscii
        }
        // shape 2: "META:<origin>"
        val metaPrefix = "META:".toByteArray(Charsets.US_ASCII)
        if (rawKey.size >= metaPrefix.size &&
            regionEquals(rawKey, 0, metaPrefix.size, metaPrefix, 0, metaPrefix.size)
        ) {
            val originSliceLen = rawKey.size - metaPrefix.size
            if (!regionEquals(rawKey, metaPrefix.size, originSliceLen, activeContainerOriginAscii, 0, activeContainerOriginAscii.size)) {
                return null
            }
            if (!regionEquals(rawKey, metaPrefix.size, originSliceLen, fromOriginAscii, 0, fromOriginAscii.size)) {
                return null
            }
            return metaPrefix + toOriginAscii
        }
        return null
    }

    private fun indexOfByte(data: ByteArray, target: Int, fromIndex: Int): Int {
        for (i in fromIndex until data.size) {
            if ((data[i].toInt() and 0xFF) == target) return i
        }
        return -1
    }

    // returns rewritten key or null (null = not a DatabaseNameKey matching fromOriginUtf16Be;
    // caller passes through verbatim).
    
    // / -- partition-agnostic match. chromium 105+ ships storage partitioning:
    // origin in DatabaseNameKey may be `file__0`, `file__0@1`, `file__0@2`, ... we match the BASE
    // `fromOriginUtf16Be` as a BYTE PREFIX of the origin slice and copy the `@<n>` suffix
    // verbatim into the rewritten key. DECOUPLES from future chromium partition-id changes --
    // we never parse or validate the suffix, just copy its bytes.
    
    // key byte layout (chromium leveldb_coding_scheme.md + on-device probe ground truth):
    // bytes 0-4: 5-byte KeyPrefix `00 00 00 00 C9` (length-packed + 3 varints + type 0xC9)
    // varint at offset 5: origin code-unit count (LEB128)
    // UTF-16BE origin slice: 2 * code-unit bytes
    // varint: dbname code-unit count
    // UTF-16BE dbname slice
    internal fun rewriteIdbDatabaseNameKey(
        rawKey: ByteArray,
        fromOriginUtf16Be: ByteArray,
        toOriginUtf16Be: ByteArray,
    ): ByteArray? {
        // 5-byte header + min 1-byte varint = 6 bytes minimum
        if (rawKey.size < 6) return null
        if (!isDbNameKey(rawKey)) return null
        val decoded = decodeLeb128At(rawKey, offset = 5) ?: return null
        val originCodeUnits = decoded.first.toInt()
        val originByteStart = 5 + decoded.second
        val originByteLen = originCodeUnits * 2
        if (originByteStart + originByteLen > rawKey.size) return null

        val partitionSuffixBytes = matchOriginWithPartition(
            rawKey,
            originByteStart,
            originByteLen,
            fromOriginUtf16Be,
        ) ?: return null

        val newOriginByteLen = toOriginUtf16Be.size + partitionSuffixBytes.size
        val newCodeUnits = (newOriginByteLen / 2).toLong()
        val newVarint = encodeLeb128(newCodeUnits)
        val trailing = rawKey.copyOfRange(originByteStart + originByteLen, rawKey.size)
        return byteArrayOf(0, 0, 0, 0, 0xC9.toByte()) +
            newVarint +
            toOriginUtf16Be +
            partitionSuffixBytes +
            trailing
    }

    // partition-agnostic match helper. returns the partition-suffix bytes (possibly
    // empty) when the origin slice BYTES START WITH fromBytes; null if no match.
    // extracted from rewriteIdbDatabaseNameKey so the match semantics are a single-purpose
    // unit and tests can exercise directly without constructing full DatabaseNameKeys.
    internal fun matchOriginWithPartition(
        rawKey: ByteArray,
        originStart: Int,
        originLen: Int,
        fromBytes: ByteArray,
    ): ByteArray? {
        // origin slice must be at least as long as base, and prefix-match fromBytes
        if (originLen < fromBytes.size) return null
        if (!regionEquals(rawKey, originStart, fromBytes.size, fromBytes, 0, fromBytes.size)) {
            return null
        }
        // suffix bytes = `@<n>` UTF-16BE tail, or empty when origin == base
        return rawKey.copyOfRange(originStart + fromBytes.size, originStart + originLen)
    }

    // canonical LEB128 encoder; pairs with decodeLeb128At. internal visibility for test.
    internal fun encodeLeb128(value: Long): ByteArray {
        require(value >= 0) { "LEB128 negative unsupported" }
        val out = mutableListOf<Byte>()
        var v = value
        while ((v and 0x7fL.inv()) != 0L) {
            out += ((v and 0x7fL) or 0x80L).toByte()
            v = v ushr 7
        }
        out += (v and 0x7fL).toByte()
        return out.toByteArray()
    }

    // LEB128 decoder mirroring Idb1Comparator.decodeVarInt semantics.
    // returns (value, bytesConsumed) or null on decode failure.
    internal fun decodeLeb128At(data: ByteArray, offset: Int): Pair<Long, Int>? {
        var shift = 0
        var ret = 0L
        var pos = offset
        while (true) {
            if (pos >= data.size || shift >= 64) return null
            val c = data[pos].toInt() and 0xFF
            if (shift != 0 && c == 0) return null
            val preShift = (c and 0x7f).toLong()
            val shifted = preShift shl shift
            if ((shifted ushr shift) != preShift) return null
            ret = ret or shifted
            shift += 7
            pos++
            if ((c and 0x80) == 0) break
        }
        return ret to (pos - offset)
    }

    private fun regionEquals(
        a: ByteArray, aOff: Int, aLen: Int,
        b: ByteArray, bOff: Int, bLen: Int,
    ): Boolean {
        if (aLen != bLen) return false
        if (aOff + aLen > a.size || bOff + bLen > b.size) return false
        for (i in 0 until aLen) if (a[aOff + i] != b[bOff + i]) return false
        return true
    }

    // historical note: snapshotDbNameKeys + restoreMissingDbNameKeys + isDbNameKey lived here as
    // a backstop against webview 109 silently dropping chromium-native DatabaseNameKey (type 0xC9)
    // records on first open. root cause was an iq80 ordering mismatch for GLOBAL_METADATA -- fixed
    // by Idb1Comparator semantic u16 compare. backstop never fired in repro testing and was
    // removed. if DBKeys ever drop again, that's a NEW underlying bug -- fix the rewrite path
    // rather than resurrect snapshot+restore.

    // .ldb files → .sst before block, restore after. chromium names SSTables `.ldb` (post-2014);
    // iq80 still uses `.sst`. same binary format, different filename. rename in place so iq80 can
    // open; restore so chromium can re-read the dir later. renames survive block throws via finally.
    private inline fun <R> withLdbAsSst(dir: File, block: () -> R): R {
        if (!dir.isDirectory) return block()
        val renames = dir.listFiles { _, name -> name.endsWith(".ldb") }.orEmpty()
            .map { from -> from to File(from.parentFile, from.nameWithoutExtension + ".sst") }
        renames.forEach { (from, to) ->
            if (!from.renameTo(to)) {
                Timber.tag("LevelDbRewriter").w("ldb→sst rename failed for %s", from.name)
            }
        }
        try {
            return block()
        } finally {
            // restore original extension. if iq80 produced new .sst files during the block
            // (write path), rename THOSE back to .ldb so chromium can read them.
            dir.listFiles { _, name -> name.endsWith(".sst") }.orEmpty().forEach { sst ->
                val ldb = File(sst.parentFile, sst.nameWithoutExtension + ".ldb")
                if (!sst.renameTo(ldb)) {
                    Timber.tag("LevelDbRewriter").w("sst→ldb rename failed for %s", sst.name)
                }
            }
        }
    }

    // blob-wrapped IDB record substitution helpers.
    //
    // CONTEXT: WebView 109 returns cursor.value=null for records whose value starts with
    // `<version_varint> ff 11 01 <size_varint> <offset_varint>` (IDB "replace-with-blob"
    // wrapper from Chromium ~140). The native blob-dir resolution path in 109 doesn't honor
    // these. All other records (incl. Uint8Array-carrying ones) deserialize fine, only
    // `ff 11 01` form returns null.
    //
    // FIX: during rewriteIdbOrigin's src→dst copy, swap each `ff 11 01` value for the
    // referenced sidecar blob's raw bytes. Sidecars may be in legacy-compat snappy-wrapped
    // form (`ff 11 02 …`) -- `maybeDecompressSnappyValue` (below) decompresses inline and
    // emits native `ff 15 fe`-prefixed V8 SSV before the swapped value reaches the dst DB.
    // This works BECAUSE it's the same pass that populates a fresh-empty dst DB; iq80's
    // post-open mutation path turned out not to persist to disk in a form chromium re-reads
    // consistently (writes survived db.get round-trip but chromium re-open saw pre-inline bytes).
    //
    // MATCHING: for each data record (KeyType=01), its blob metadata lives at the sibling
    // key with KeyType=03. Blob_info value byte[1] is the sequential blob_number used as
    // the sidecar filename. Path: <blobDir>/<db_id>/<bucket>/<blob_num>. Bucket subdir name
    // varies (e.g. "00") so findBlobFile walks the db_id subdir.

    // wraps a ByteArray so it can live in a HashSet (ByteArray identity equality is unhelpful).
    private class ByteArrayWrapper(val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean = other is ByteArrayWrapper && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
    }
    private fun ByteArray.asByteArrayWrapper(): ByteArrayWrapper = ByteArrayWrapper(this)

    private data class BlobRef(
        val dbId: Int,
        val blobNumber: Int,
        val fullBlobInfoKey: ByteArrayWrapper,
    )

    // scans src for blob_info records (KeyType=03). returns map from the data-record's
    // full key to the BlobRef describing the sidecar blob that value references.
    private fun buildBlobRefMapFromDb(srcDb: org.iq80.leveldb.DB): Map<ByteArrayWrapper, BlobRef> {
        val map = mutableMapOf<ByteArrayWrapper, BlobRef>()
        srcDb.iterator().use { iter ->
            iter.seekToFirst()
            while (iter.hasNext()) {
                val e = iter.next()
                val parsed = parseIdbObjectStoreKey(e.key) ?: continue
                if (parsed.keyType != 0x03) continue
                val v = e.value
                if (v.size < 2) continue
                val blobNum = decodeLeb128At(v, offset = 1)?.first?.toInt() ?: continue
                // reconstruct the matching data record's full key: same prefix, byte[3]=0x01.
                val dataKey = e.key.copyOf()
                dataKey[3] = 0x01
                map[ByteArrayWrapper(dataKey)] = BlobRef(
                    dbId = parsed.dbId,
                    blobNumber = blobNum,
                    fullBlobInfoKey = ByteArrayWrapper(e.key),
                )
            }
        }
        return map
    }

    // decompress chromium IDB's `kCompressedWithSnappy` wrapper. wire format 
    // third_party/blink/renderer/modules/indexeddb/idb_value_wrapping.cc:
    // `<data_version_varint> ff 11 02 <snappy-raw-bytes>`
    // decompressed stream already starts with the native `ff 15 fe` wrapper + V8 SSV,
    // so we just re-emit the leading varint and splice in the decompressed bytes.
    // returns the original value when the wrapper isn't present or decompress fails.
    
    // uses snappy-java's zero-copy API (rawUncompress into a single pre-sized output) so
    // peak memory stays at ~(value.size + decompressed.size) instead of ~3x that. matters
    // for the 36 MB wayward cache blob (~51 MB decompressed) which would OOM on tight devices.
    internal fun maybeDecompressSnappyValue(value: ByteArray): ByteArray {
        val (_, after) = decodeLeb128At(value, offset = 0) ?: return value
        if (value.size < after + 3) return value
        if ((value[after].toInt() and 0xFF) != 0xFF) return value
        if ((value[after + 1].toInt() and 0xFF) != 0x11) return value
        if ((value[after + 2].toInt() and 0xFF) != 0x02) return value
        val compressedOffset = after + 3
        val compressedLen = value.size - compressedOffset
        return try {
            val uncompressedLen = Snappy.uncompressedLength(value, compressedOffset, compressedLen)
            val out = ByteArray(after + uncompressedLen)
            System.arraycopy(value, 0, out, 0, after)
            Snappy.rawUncompress(value, compressedOffset, compressedLen, out, after)
            out
        } catch (t: Throwable) {
            Timber.tag("LevelDbRewriter").e(
                t, "snappy uncompress failed (compressedSize=%d valueSize=%d)",
                compressedLen, value.size,
            )
            value
        }
    }

    // returns the value to write to dst -- either the original bytes, or (for
    // blob-wrapped data records with a matching blob_info entry + on-disk sidecar file)
    // `<leading_version_varint> + <sidecar_bytes>`. pure -- no DB mutations.
    
    // reference-equality (`outValue !== entry.value`) in the caller detects "did we swap",
    // so we return the original `value` reference when no swap happens.
    private fun maybeInlineBlobValue(
        key: ByteArray,
        value: ByteArray,
        blobRefMap: Map<ByteArrayWrapper, BlobRef>,
        blobDir: File,
    ): ByteArray {
        val ref = blobRefMap[ByteArrayWrapper(key)] ?: return value
        // peek leading varint + next 3 bytes for ff 11 01 marker
        val (_, after) = decodeLeb128At(value, offset = 0) ?: return value
        if (value.size < after + 3) return value
        if ((value[after].toInt() and 0xFF) != 0xFF) return value
        if ((value[after + 1].toInt() and 0xFF) != 0x11) return value
        if ((value[after + 2].toInt() and 0xFF) != 0x01) return value
        val blobFile = findBlobFile(blobDir, ref.dbId, ref.blobNumber)
        if (blobFile == null) {
            Timber.tag("LevelDbRewriter").w(
                "maybeInlineBlobValue: blob file not found: dbId=%d blobNum=%d blobDir=%s",
                ref.dbId, ref.blobNumber, blobDir.absolutePath,
            )
            return value
        }
        val blobBytes = blobFile.readBytes()
        val leadingVarint = value.copyOfRange(0, after)
        return leadingVarint + blobBytes
    }

    // chromium IDB ObjectStore key layout (db_id_len=1 / os_id_len=1 case):
    // byte 0: length-packed prefix header = 0x00 (db_len=1, os_len=1, index_len=0)
    // byte 1: database_id (1 byte for our min case)
    // byte 2: object_store_id (1 byte)
    // byte 3: KeyType -- 0x01 ObjectStoreDataKey, 0x02 ExistsEntryKey, 0x03 BlobEntryKey
    // bytes 4+: encoded user key (type-prefixed + length-prefixed UTF-16LE for strings)
    
    // data records keyed at byte[3]==0x01; their blob_info twins sit at byte[3]==0x03
    // with identical trailing bytes. we ONLY accept length-packed prefix 0x00 because
    // other packings imply multi-byte ids which this helper doesn't handle.
    private data class ParsedIdbKey(val dbId: Int, val osId: Int, val keyType: Int, val suffix: ByteArray)
    private fun parseIdbObjectStoreKey(key: ByteArray): ParsedIdbKey? {
        if (key.size < 5) return null
        if (key[0] != 0.toByte()) return null
        val dbId = key[1].toInt() and 0xFF
        val osId = key[2].toInt() and 0xFF
        val keyType = key[3].toInt() and 0xFF
        // only accept the three real ObjectStore KeyTypes. anything else (metadata
        // records with byte[3]==0xC9, global version records with byte[3]==0x00, etc.)
        // returns null so the caller skips -- avoids mistakenly deleting metadata.
        if (keyType !in setOf(0x01, 0x02, 0x03)) return null
        val suffix = key.copyOfRange(4, key.size)
        return ParsedIdbKey(dbId, osId, keyType, suffix)
    }

    // locate `<blobDir>/<dbId>/*/<blobNumber>`. bucket subdir name varies (e.g. "00"); walk it
    // to keep the matcher resilient.
    
    // chromium's IndexedDBBlobFilePath uses hex (base 16) for both the bucket subdir AND the
    // blob filename. blobs 0-9 happen to have identical decimal and hex representations which
    // hid the bug until a save reached blob_number >= 10. match either representation so a
    // stale decimal on-disk name (e.g. from a prior build) still resolves.
    private fun findBlobFile(blobDir: File, dbId: Int, blobNumber: Int): File? {
        val dbSubdir = File(blobDir, dbId.toString())
        if (!dbSubdir.isDirectory) return null
        val hexName = blobNumber.toString(16)
        val decName = blobNumber.toString()
        dbSubdir.walkTopDown().forEach { f ->
            if (f.isFile && (f.name == hexName || f.name == decName)) return f
        }
        return null
    }

    // reference-aware copy of `.indexeddb.blob/` alongside
    // leveldb. iterates liveRefs (from rewriteIdbOrigin's blob_info scan) and copies only the
    // physical files backing them. MIRROR semantic: wipe dst first -- blob dir is per-origin
    // so wiping is safe and prevents stale wine-side blobs from surviving as orphans.
    
    // WHY NOT recursive tree copy: chromium's IDB blob-journal GC is lazy. physical blob files
    // persist on disk after the leveldb record referencing them is tombstoned by a desktop
    // session. dumb recursive copy would re-upload those orphans to cloud every round-trip;
    // desktop would re-delete them next cycle. bounded but wasteful churn per cycle.
    // reference-aware copy skips files with no surviving leveldb ref.
    
    // `missing` = live ref with no physical file (upstream anomaly, shouldn't happen).
    // `orphans` = physical files on disk with no live ref (the churn bug we're filtering).
    fun copyLiveBlobs(src: File?, dst: File?, liveRefs: Set<Pair<Int, Int>>) {
        if (dst == null) return
        if (src == null || !src.isDirectory) {
            // still wipe dst so stale wine-side blobs from prior sessions don't persist.
            wipeDirectoryContents(dst)
            return
        }
        wipeDirectoryContents(dst)
        dst.mkdirs()
        var copied = 0
        var missing = 0
        for ((dbId, blobNumber) in liveRefs) {
            val srcFile = findBlobFile(src, dbId, blobNumber)
            if (srcFile == null) {
                missing++
                continue
            }
            val relative = srcFile.relativeTo(src)
            val dstFile = File(dst, relative.path)
            dstFile.parentFile?.mkdirs()
            srcFile.copyTo(dstFile, overwrite = true)
            copied++
        }
        val physicalSrc = countBlobFiles(src)
        Timber.tag("LevelDbRewriter").i(
            "copyLiveBlobs: liveRefs=%d copied=%d missing=%d physicalSrc=%d orphansSkipped=%d",
            liveRefs.size, copied, missing, physicalSrc, physicalSrc - copied,
        )
    }

    private fun countBlobFiles(dir: File): Int {
        if (!dir.isDirectory) return 0
        return dir.walkTopDown().count { it.isFile }
    }

    // --- impl ---

    // iq80 factory open. createIfMissing=true when writable, paranoidChecks=false so a slightly
    // bruised manifest still opens (chromium writes a clean DB; bruising is user-corruption).
    // compressionType pinned to SNAPPY because chromium's leveldb default is snappy.
    private fun openDb(dir: File, readOnly: Boolean, useIdb1: Boolean): org.iq80.leveldb.DB {
        val options = Options().apply {
            createIfMissing(!readOnly)
            errorIfExists(false)
            compressionType(CompressionType.SNAPPY)
            paranoidChecks(false)
            // these are transient read/rewrite dbs -- we never want a background compaction thread.
            // on reads it would race the iterator (retiring SSTs mid-read); on writes it flushes the
            // memtable on a background thread that races the writer, both intermittently dropping
            // records. disabled => memtable flushes happen synchronously on the writer thread.
            compactionEnabled(false)
            if (useIdb1) {
                comparator(Idb1Comparator())
            }
        }
        return Iq80DBFactory.factory.open(dir, options)
    }

    // translate lib-level exceptions into the SaveSyncFailure hierarchy so Html5SaveSyncService
    // can surface user-appropriate snackbar copy. delegates to the shared LeveldbFailures; the
    // sstLdbAsCorruption=true flag preserves the `.sst`/`.ldb` FNE→Corruption branch (MANIFEST
    // references a missing table -- internally-inconsistent snapshot, actionable, and gates
    // follow-up cloud writes). visibility = internal for same-module test access.
    internal fun classifyFailure(t: Throwable, src: File, dst: File): SaveSyncFailure =
        LeveldbFailures.classify(t, src, dst, sstLdbAsCorruption = true)

    // delete files that appeared in `dir` after the recorded pre-open snapshot. used in
    // catch blocks of rewriteIdbOrigin / rewriteLsOrigin to roll back iq80's failed-open
    // recovery writes (new MANIFEST-*, *.log, LOCK) so SteamAutoCloud's recursive UFS scan
    // doesn't sweep them into cloud, polluting the snapshot for both Android + desktop.
    private fun rollbackNewFiles(dir: File, preOpenFiles: Set<String>) {
        if (!dir.isDirectory) return
        val current = dir.listFiles() ?: return
        var deleted = 0
        for (f in current) {
            if (f.isFile && f.name !in preOpenFiles) {
                if (f.delete()) deleted++
            }
        }
        if (deleted > 0) {
            Timber.tag("LevelDbRewriter").i(
                "rollback: deleted %d file(s) iq80 created during failed open in %s",
                deleted, dir.absolutePath,
            )
        }
    }

}
