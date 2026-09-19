package app.gamenative.html5.savesync

import java.io.File
import org.iq80.leveldb.CompressionType
import org.iq80.leveldb.Options
import org.iq80.leveldb.impl.Iq80DBFactory
import org.xerial.snappy.Snappy
import timber.log.Timber

// copies a chromium leveldb src -> dst, rewriting the origin in every key; values stay byte-for-byte
// (except inbound IDB, see below). pure-java iq80 + snappy + idb_cmp1.
//
// blob files are named by chromium-internal ids, so they move with their leveldb as-is. outbound
// copies only blobs still referenced (copyLiveBlobs), skipping orphans chromium's lazy blob GC left.
// inbound inlines blob bytes into the leveldb values and leaves the webview blob dir empty.
object LevelDbRewriter {

    // swap scratch names. SyncFileFilter drops these so a crash-leftover never reaches cloud.
    internal const val SWAP_NEW_SUFFIX = ".gnnew"
    internal const val SWAP_OLD_SUFFIX = ".gnold"

    // returns src's live `(dbId, blobNumber)` refs for copyLiveBlobs (inbound ignores them).
    // null = src had nothing committed or no database for fromOriginFilename, and dst was left untouched --
    // outbound must then leave the Wine blob dir alone too, or the copy keeps refs to blobs that are gone.
    fun rewriteIdbOrigin(
        src: File,
        dst: File,
        fromOriginFilename: String,
        toOriginFilename: String,
        inlineBlobsFromDir: File? = null,
    ): Set<Pair<Int, Int>>? {
        if (!src.isDirectory) throw SaveSyncFailure.PathMissing(src.absolutePath)
        // titles that save through fs leave only chromium's "opened, never wrote" shell. opening it
        // would burn the 10s CURRENT poll, then throw Corruption -- all while blocking exit.
        if (isEmptyLeveldbShell(src)) {
            Timber.tag("LevelDbRewriter").i(
                "rewriteIdbOrigin: src has no committed leveldb state, skipping. src=%s",
                src.absolutePath,
            )
            return null
        }
        // rebuild in staging and swap in only on success: a failed rewrite must not leave dst empty, or
        // the exit upload deletes the game's cloud IDB.
        return withStagedDestination(dst, seedFromDst = false) { staging ->
            rewriteIdbOriginInto(src, staging, fromOriginFilename, toOriginFilename, inlineBlobsFromDir)
        }
    }

    private fun rewriteIdbOriginInto(
        src: File,
        dst: File,
        fromOriginFilename: String,
        toOriginFilename: String,
        inlineBlobsFromDir: File?,
    ): Set<Pair<Int, Int>>? {
        // dst is a FRESH staging dir: rebuilding over old files mixed stale SSTs/logs into the new MANIFEST,
        // and the game couldn't load saves from the overlapping records.
        dst.mkdirs()
        val fromBytes = OriginCodec.utf16BePrefixBytes(fromOriginFilename)
        val toBytes = OriginCodec.utf16BePrefixBytes(toOriginFilename)

        // iq80 mutates src on open, even read-only; cloud sync would upload that as local changes.
        var liveRefs: Set<Pair<Int, Int>> = emptySet()
        var srcHasFromOrigin = false
        withShadowCopy(src) { srcShadow ->
            withLdbAsSst(srcShadow) {
                LeveldbManifestSynthesizer.synthesizeManifest(srcShadow, useIdb1 = true)
                withLdbAsSst(dst) {
                    // rollback deletes what iq80 created during a failed open so cloud sync can't upload it.
                    // taken AFTER the .ldb -> .sst rename so names match what rollback sees.
                    val dstPreOpenFiles = dst.listFiles()?.map { it.name }?.toSet().orEmpty()
                    try {
                        openDb(srcShadow, readOnly = true, useIdb1 = true).use { srcDb ->
                            openDb(dst, readOnly = false, useIdb1 = true).use { dstDb ->
                                // drives inbound inlining (ff 11 01 -> sidecar bytes) and outbound's live-blob filter.
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
                                        // blob_info records go stale once the main record is inlined.
                                        if (inlineBlobsFromDir != null && entry.key.asByteArrayWrapper() in blobInfoKeysToSkip) {
                                            continue
                                        }
                                        val newKey = rewriteIdbDatabaseNameKey(entry.key, fromBytes, toBytes)
                                        if (newKey != null) srcHasFromOrigin = true
                                        val outKey = newKey ?: entry.key
                                        var outValue = entry.value
                                        if (inlineBlobsFromDir != null) {
                                            // INLINE FIRST, DECOMPRESS AFTER: chromium snappy-wraps AFTER blob
                                            // externalization, so an inlined sidecar may itself be ff 11 02.
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
                                // debug only: rehashing every value on both sides doubles sync IO.
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
                        // iq80 leaves LOCK behind on close AND on failed open; keep it out of cloud.
                        removeLeveldbLock(dst)
                    }
                }
            }
        }
        // empty-source guard, same as rewriteLsOrigin: a src with no database for this origin (store recreated
        // after a wipe, or a copy made under another origin) would replace dst with databases the game can't see.
        if (!srcHasFromOrigin) {
            Timber.tag("LevelDbRewriter").w(
                "rewriteIdbOrigin: src has no %s database -- keeping dst. src=%s",
                fromOriginFilename, src.absolutePath,
            )
            return null
        }
        return liveRefs
    }

    // LS key shapes (all ASCII):
    // "_<URL-origin>\0\x01<user-key>" (type byte 0x01 optional, tail preserved)
    // "METAACCESS:<URL-origin>" (per-origin GC timestamp)
    // "META:<URL-origin>" (per-origin quota metadata)
    //
    // only the from-origin's keys cross, rewritten; VERSION (the only origin-less key chromium writes)
    // copies as-is; every other origin and any unparseable key is SKIPPED. the WebView LS is one leveldb
    // shared by all games -- copying other origins leaked every game's state into each Wine copy (and
    // cloud), and inbound then rolled other games back to those stale copies.
    //
    // keepOnlyToOriginInDst: outbound only (dst = Wine copy), which then holds just this game's pc
    // origin, dropping keys older builds leaked in. NEVER on inbound -- dst is the shared live store.
    fun rewriteLsOrigin(
        src: File,
        dst: File,
        fromOriginUrl: String,
        toOriginUrl: String,
        activeContainerOriginUrl: String,
        keepOnlyToOriginInDst: Boolean = false,
        // outbound only: the page's own localStorage (LocalStorageSnapshot), already chromium-encoded.
        // replaces the from-origin keys read from src; META/VERSION still come from src. null = read src.
        fromOriginEntries: List<Pair<ByteArray, ByteArray>>? = null,
    ) {
        // outbound rewrites a staged copy and swaps it in only on success, so a failure after the purge
        // can't leave a half-rewritten copy for the exit upload. the in-place branch has no prod caller
        // since inbound LS moved page-side (ls-restore.js). NEVER point it at the WebView's live LS store:
        // once any WebView ran, chromium holds that leveldb open for the whole process and its LOCK
        // (per-process fcntl) doesn't stop iq80 in the same process -- two writers corrupt it.
        if (keepOnlyToOriginInDst) {
            withStagedDestination(dst, seedFromDst = true) { staging ->
                rewriteLsOriginInto(src, staging, fromOriginUrl, toOriginUrl, activeContainerOriginUrl, true, fromOriginEntries)
                // staging is seeded from dst (the empty-source guard may need its keys), and openDb runs
                // with compaction OFF (background compaction drops records -- do NOT turn it on), so
                // superseded tables pile up: the Wine copy and its cloud copy grow one table per launch.
                // rebuild only once bloated: a rebuild renames every file, and timestamp-based cloud
                // sync (GOG) would re-upload the whole store on every exit.
                compactIfBloated(staging)
            }
        } else {
            rewriteLsOriginInto(src, dst, fromOriginUrl, toOriginUrl, activeContainerOriginUrl, false, fromOriginEntries)
        }
    }

    private fun rewriteLsOriginInto(
        src: File,
        dst: File,
        fromOriginUrl: String,
        toOriginUrl: String,
        activeContainerOriginUrl: String,
        keepOnlyToOriginInDst: Boolean,
        fromOriginEntries: List<Pair<ByteArray, ByteArray>>?,
    ) {
        if (!src.isDirectory) throw SaveSyncFailure.PathMissing(src.absolutePath)
        // same empty-shell guard as rewriteIdbOrigin.
        if (isEmptyLeveldbShell(src)) {
            Timber.tag("LevelDbRewriter").i(
                "rewriteLsOrigin: src has no committed leveldb state, skipping (page entries dropped=%d). src=%s",
                fromOriginEntries?.size ?: 0, src.absolutePath,
            )
            return
        }
        dst.mkdirs()
        val fromAscii = OriginCodec.asciiKeyOriginFromUrl(fromOriginUrl)
        val toAscii = OriginCodec.asciiKeyOriginFromUrl(toOriginUrl)
        val activeAscii = OriginCodec.asciiKeyOriginFromUrl(activeContainerOriginUrl)

        // shadow src: iq80 mutates it on open, even read-only.
        withShadowCopy(src) { srcShadow ->
            withLdbAsSst(srcShadow) {
                LeveldbManifestSynthesizer.synthesizeManifest(srcShadow, useIdb1 = false)
                withLdbAsSst(dst) {
                    // on failure delete only files iq80 created -- dst may hold other data. taken AFTER the
                    // .ldb -> .sst rename: a snapshot with .ldb names made rollback delete every table.
                    val dstPreOpenFiles = dst.listFiles()?.map { it.name }?.toSet().orEmpty()
                    try {
                        openDb(srcShadow, readOnly = true, useIdb1 = false).use { srcDb ->
                            openDb(dst, readOnly = false, useIdb1 = false).use { dstDb ->
                                // mirror: purge dst's toOrigin keys BEFORE writing, so deleted keys stay deleted.
                                // empty-source guard: a src with no keys for this game (never wrote LS, or a
                                // copy made under another origin) must not wipe the game's keys already in dst.
                                val srcHasFromOrigin = fromOriginEntries?.isNotEmpty() ?: srcDb.iterator().use { iter ->
                                    iter.seekToFirst()
                                    var found = false
                                    while (!found && iter.hasNext()) found = lsKeyOrigin(iter.next().key) == fromOriginUrl
                                    found
                                }
                                if (!srcHasFromOrigin) {
                                    Timber.tag("LevelDbRewriter").w(
                                        "rewriteLsOrigin: src has no %s keys -- keeping dst's %s keys",
                                        fromOriginUrl, toOriginUrl,
                                    )
                                }
                                val purged = purgeKeys(dstDb) { origin ->
                                    if (origin == toOriginUrl) srcHasFromOrigin else keepOnlyToOriginInDst
                                }
                                var copied = 0
                                var skippedOtherOrigin = 0
                                var skippedUnparsed = 0
                                // logged: an unseeded staging dir has no other source for VERSION.
                                var versionCopied = false
                                srcDb.iterator().use { iter ->
                                    iter.seekToFirst()
                                    while (iter.hasNext()) {
                                        val e = iter.next()
                                        val newKey = rewriteLsKeyIfActive(e.key, fromAscii, toAscii, activeAscii)
                                        when {
                                            // page entries win over leveldb's user keys for this origin
                                            newKey != null && fromOriginEntries != null && e.key[0] == '_'.code.toByte() -> continue
                                            newKey != null -> dstDb.put(newKey, e.value)
                                            e.key.contentEquals(LS_VERSION_KEY) -> {
                                                dstDb.put(e.key, e.value)
                                                versionCopied = true
                                            }
                                            lsKeyOrigin(e.key) != null -> { skippedOtherOrigin++; continue }
                                            else -> { skippedUnparsed++; continue }
                                        }
                                        copied++
                                    }
                                }
                                fromOriginEntries?.forEach { (key, value) ->
                                    dstDb.put(byteArrayOf('_'.code.toByte()) + toAscii + byteArrayOf(0) + key, value)
                                    copied++
                                }
                                Timber.tag("LevelDbRewriter").i(
                                    "rewriteLsOrigin: from=%s to=%s purged=%d copied=%d skippedOtherOrigin=%d skippedUnparsed=%d pageEntries=%s version=%b",
                                    fromOriginUrl, toOriginUrl, purged, copied, skippedOtherOrigin, skippedUnparsed,
                                    fromOriginEntries?.size?.toString() ?: "none", versionCopied,
                                )
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

    // iq80 leaves its LOCK behind on close; Steam Cloud's recursive patterns would then track it and
    // keep flagging "Need to forget". chromium recreates its own LOCK on next open.
    private fun removeLeveldbLock(dir: File) {
        if (!dir.isDirectory) return
        val lock = File(dir, "LOCK")
        if (lock.isFile) {
            if (!lock.delete()) {
                Timber.tag("LevelDbRewriter").w("LOCK delete failed for %s", lock.absolutePath)
            }
        }
    }

    // iq80 mutates a leveldb on open (compaction, MANIFEST/CURRENT rewrites); doing that to the Wine
    // copy would make cloud sync upload it. copyRecursively returns FALSE on partial failure instead
    // of throwing, so CURRENT's presence is checked explicitly.
    //
    // stopgap: SteamAutoCloud's download can still be streaming into the prefix during inbound, so
    // wait up to STALE_WAIT_MS for CURRENT. the real fix is to await its completion.
    private inline fun <T> withShadowCopy(src: File, block: (File) -> T): T {
        val shadow = java.nio.file.Files.createTempDirectory("ldb-shadow-").toFile()
        return try {
            var ok = src.copyRecursively(shadow, overwrite = true)
            if (!File(shadow, "CURRENT").isFile && !File(src, "CURRENT").isFile) {
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

    // a cloud download can land a newer generation (CURRENT -> MANIFEST-N + live `<n>.log`) on top of an
    // older local one. more than one log makes iq80 compact during open, and the merged state then fails
    // with `Could not open table N`. so, without parsing the format:
    //   - keep only CURRENT's MANIFEST.
    //   - keep only the highest-numbered `<n>.log` (numbering is monotonic).
    //   - leave `.ldb`/`.sst` alone -- we don't parse which MANIFEST entries reference them.
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

    // origin-less keys (VERSION) are never touched. matching on origin alone is enough: each
    // container has its own origin URL, so other games' keys never equal the target.
    private fun purgeKeys(
        db: org.iq80.leveldb.DB,
        shouldPurge: (String) -> Boolean,
    ): Int {
        val toDelete = mutableListOf<ByteArray>()
        db.iterator().use { iter ->
            iter.seekToFirst()
            while (iter.hasNext()) {
                val k = iter.next().key
                val origin = lsKeyOrigin(k) ?: continue
                if (shouldPurge(origin)) toDelete.add(k)
            }
        }
        toDelete.forEach { db.delete(it) }
        return toDelete.size
    }

    // null for keys that carry no origin (VERSION).
    internal fun lsKeyOrigin(rawKey: ByteArray): String? {
        if (rawKey.isNotEmpty() && rawKey[0] == '_'.code.toByte()) {
            val nulSep = indexOfByte(rawKey, 0, fromIndex = 1)
            return if (nulSep < 0) null else String(rawKey, 1, nulSep - 1, Charsets.US_ASCII)
        }
        for (prefix in LS_META_PREFIXES) {
            if (rawKey.size >= prefix.size && regionEquals(rawKey, 0, prefix.size, prefix, 0, prefix.size)) {
                return String(rawKey, prefix.size, rawKey.size - prefix.size, Charsets.US_ASCII)
            }
        }
        return null
    }

    // null when there is nothing to restore -- the page must then leave its localStorage alone.
    fun readLsOriginEntries(lsDir: File, originUrl: String): List<Pair<ByteArray, ByteArray>>? {
        if (!lsDir.isDirectory || isEmptyLeveldbShell(lsDir)) return null
        val prefix = byteArrayOf('_'.code.toByte()) + OriginCodec.asciiKeyOriginFromUrl(originUrl) + byteArrayOf(0)
        val entries = withShadowCopy(lsDir) { shadow ->
            withLdbAsSst(shadow) {
                LeveldbManifestSynthesizer.synthesizeManifest(shadow, useIdb1 = false)
                openDb(shadow, readOnly = true, useIdb1 = false).use { db ->
                    val out = mutableListOf<Pair<ByteArray, ByteArray>>()
                    db.iterator().use { iter ->
                        iter.seekToFirst()
                        while (iter.hasNext()) {
                            val e = iter.next()
                            if (e.key.size > prefix.size && regionEquals(e.key, 0, prefix.size, prefix, 0, prefix.size)) {
                                out += e.key.copyOfRange(prefix.size, e.key.size) to e.value
                            }
                        }
                    }
                    out
                }
            }
        }
        return entries.takeIf { it.isNotEmpty() }
    }

    private val LS_META_PREFIXES = listOf("METAACCESS:", "META:").map { it.toByteArray(Charsets.US_ASCII) }
    private val LS_VERSION_KEY = "VERSION".toByteArray(Charsets.US_ASCII)

    // boot cleanup: purges the shared LS leveldb IN PLACE. ONLY before any WebView opens in this process:
    // chromium's LOCK is per-process and won't stop iq80, so a purge after that is a second writer.
    fun purgeLsOrigins(lsDir: File, shouldPurge: (String) -> Boolean): Int {
        if (!lsDir.isDirectory) {
            Timber.tag("LevelDbRewriter").i(
                "purgeLsOrigins: lsDir missing, nothing to purge. lsDir=%s",
                lsDir.absolutePath,
            )
            return 0
        }
        if (isEmptyLeveldbShell(lsDir)) {
            Timber.tag("LevelDbRewriter").i(
                "purgeLsOrigins: empty leveldb shell, nothing to purge. lsDir=%s",
                lsDir.absolutePath,
            )
            return 0
        }
        return withLdbAsSst(lsDir) {
            try {
                openDb(lsDir, readOnly = false, useIdb1 = false).use { db ->
                    purgeKeys(db, shouldPurge)
                }.also {
                    Timber.tag("LevelDbRewriter").d("purgeLsOrigins: deleted=%d lsDir=%s", it, lsDir.absolutePath)
                }
            } finally {
                removeLeveldbLock(lsDir)
            }
        }
    }

    // OPFS bucket dir (e.g. "002") chromium assigned to `originUrl`, from the shared `File System/Origins`
    // index ("ORIGIN:<scheme_host_port>" -> bucket). uninstall deletes that bucket: WebStorage.deleteOrigin
    // doesn't cover OPFS, and a surviving bucket would shadow the cloud-restored saves on reinstall.
    // read from a SHADOW copy: the index is shared by every origin and iq80 mutates on open.
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

    // 5-byte prefix `00 00 00 00 C9` (length-packed + type 0xC9).
    private fun isDbNameKey(k: ByteArray): Boolean =
        k.size >= 5 && k[0] == 0.toByte() && k[1] == 0.toByte() &&
            k[2] == 0.toByte() && k[3] == 0.toByte() &&
            (k[4].toInt() and 0xFF) == 0xC9

    // catches silent value-byte corruption between src and the rewritten dst.
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

    private fun wipeDirectoryContents(dir: File) {
        if (!dir.isDirectory) return
        dir.listFiles()?.forEach { it.deleteRecursively() }
    }

    // swaps block's staging dir into dst only when block returns non-null; a throw or null leaves dst as
    // it was. mtimes are preserved both ways so untouched tables don't look changed to timestamp-based
    // cloud sync (GOG).
    private inline fun <T> withStagedDestination(dst: File, seedFromDst: Boolean, block: (File) -> T): T {
        recoverInterruptedSwap(dst)
        // staging is a SIBLING of dst: only same-filesystem renames are atomic, and this saves a full
        // copy on the exit path.
        val parent = dst.parentFile?.also { it.mkdirs() }
        val staging = if (parent != null) {
            File(parent, dst.name + SWAP_NEW_SUFFIX).also {
                it.deleteRecursively()
                it.mkdirs()
            }
        } else {
            java.nio.file.Files.createTempDirectory("ldb-staging-").toFile()
        }
        try {
            if (seedFromDst && dst.isDirectory) copyTreePreservingMtime(dst, staging)
            val result = block(staging)
            if (result == null) return result
            swapIn(staging, dst)
            return result
        } finally {
            staging.deleteRecursively()
        }
    }

    // replaces dst by RENAME, never by wiping it first: android kills processes at teardown, when this
    // runs, and a kill mid-copy would leave the store cloud uploads at close empty or half-written.
    internal fun swapIn(staging: File, dst: File) {
        val parent = dst.parentFile ?: run {
            Timber.tag("LevelDbRewriter").w("swapIn: %s has no parent, copying in place", dst.absolutePath)
            dst.mkdirs()
            wipeDirectoryContents(dst)
            copyTreePreservingMtime(staging, dst)
            return
        }
        parent.mkdirs()
        val outgoing = File(parent, dst.name + SWAP_OLD_SUFFIX)
        outgoing.deleteRecursively()
        // staging elsewhere costs one copy onto dst's filesystem; a cross-filesystem rename is not atomic.
        val sameFilesystem = runCatching { staging.parentFile?.canonicalFile == parent.canonicalFile }.getOrDefault(false)
        val incoming = if (sameFilesystem) {
            staging
        } else {
            File(parent, dst.name + SWAP_NEW_SUFFIX).also {
                it.deleteRecursively()
                copyTreePreservingMtime(staging, it)
            }
        }
        if (!incoming.isDirectory) throw java.io.IOException("swapIn: nothing to swap in for ${dst.name}")
        val hadDst = dst.exists()
        if (hadDst && !dst.renameTo(outgoing)) {
            incoming.deleteRecursively()
            throw java.io.IOException("swapIn: could not move ${dst.name} aside")
        }
        if (!incoming.renameTo(dst)) {
            // put the original back rather than leave the game with no store at all
            if (hadDst) outgoing.renameTo(dst)
            incoming.deleteRecursively()
            throw java.io.IOException("swapIn: could not move the rebuilt store into place")
        }
        outgoing.deleteRecursively()
    }

    // a kill between swapIn's two renames leaves dst missing and <name>.gnold holding the only copy;
    // recover it before anything else looks at dst.
    internal fun recoverInterruptedSwap(dst: File) {
        val parent = dst.parentFile ?: return
        val outgoing = File(parent, dst.name + SWAP_OLD_SUFFIX)
        val incoming = File(parent, dst.name + SWAP_NEW_SUFFIX)
        if (!dst.exists() && outgoing.isDirectory) {
            if (outgoing.renameTo(dst)) {
                Timber.tag("LevelDbRewriter").w(
                    "recovered %s from an interrupted swap (%s)", dst.name, outgoing.name,
                )
            } else {
                Timber.tag("LevelDbRewriter").w("could not recover %s from %s", dst.name, outgoing.name)
            }
        }
        // a leftover .gnnew was never live; it is a partial copy by definition
        incoming.deleteRecursively()
        if (dst.exists()) outgoing.deleteRecursively()
    }

    // leveldb's own kL0_CompactionTrigger: past it, a store is carrying dead weight.
    private const val COMPACT_TABLE_THRESHOLD = 4

    private fun compactIfBloated(dir: File) {
        val tables = dir.listFiles().orEmpty().count { it.name.endsWith(".ldb") || it.name.endsWith(".sst") }
        if (tables > COMPACT_TABLE_THRESHOLD) compactInPlace(dir)
    }

    // rebuilds the live key set into a fresh leveldb. only put/iterate -- deliberately NOT compactRange,
    // whose machinery our iq80 fork disables. on failure the uncompacted dir stands: costs disk, not data.
    private fun compactInPlace(dir: File) {
        val fresh = java.nio.file.Files.createTempDirectory("ldb-compact-").toFile()
        runCatching {
            var entries = 0
            withLdbAsSst(dir) {
                withLdbAsSst(fresh) {
                    openDb(dir, readOnly = true, useIdb1 = false).use { src ->
                        openDb(fresh, readOnly = false, useIdb1 = false).use { out ->
                            src.iterator().use { iter ->
                                iter.seekToFirst()
                                while (iter.hasNext()) {
                                    val e = iter.next()
                                    out.put(e.key, e.value)
                                    entries++
                                }
                            }
                        }
                    }
                }
            }
            removeLeveldbLock(fresh)
            // an empty rebuild would mean the read found nothing; keep what we have instead.
            if (entries == 0) {
                Timber.tag("LevelDbRewriter").w("compactInPlace: rebuild came back empty, keeping %s as is", dir.name)
                return@runCatching
            }
            val before = dir.listFiles().orEmpty().count { it.name.endsWith(".ldb") || it.name.endsWith(".sst") }
            wipeDirectoryContents(dir)
            copyTreePreservingMtime(fresh, dir)
            val after = dir.listFiles().orEmpty().count { it.name.endsWith(".ldb") || it.name.endsWith(".sst") }
            Timber.tag("LevelDbRewriter").i(
                "compactInPlace: %d entries, tables %d -> %d", entries, before, after,
            )
        }.onFailure {
            Timber.tag("LevelDbRewriter").w(it, "compactInPlace failed for %s -- leaving it uncompacted", dir.absolutePath)
        }
        fresh.deleteRecursively()
    }

    private fun copyTreePreservingMtime(from: File, to: File) {
        from.walkTopDown().forEach { file ->
            val target = File(to, file.relativeTo(from).path)
            if (file.isDirectory) {
                target.mkdirs()
            } else {
                file.copyTo(target, overwrite = true)
                target.setLastModified(file.lastModified())
            }
        }
    }

    // chromium writes LOG / LOCK / LOG.old on open; CURRENT + MANIFEST-* only after the first commit.
    // a partial download (MANIFEST or .ldb present, CURRENT not yet) is NOT a shell -- withShadowCopy
    // waits for it.
    private fun isEmptyLeveldbShell(dir: File): Boolean {
        if (!dir.isDirectory) return true
        val files = dir.listFiles()?.filter { it.isFile } ?: return true
        if (files.isEmpty()) return true
        val runtimeOnly = setOf("LOG", "LOCK", "LOG.old")
        return files.all { it.name in runtimeOnly }
    }

    // null = not an origin-bearing key, or not the active origin.
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
            // exact-length compare, not startsWith: origin "..._3792100" must NOT match "..._379210"
            if (!regionEquals(rawKey, 1, originSliceLen, activeContainerOriginAscii, 0, activeContainerOriginAscii.size)) {
                return null
            }
            if (!regionEquals(rawKey, 1, originSliceLen, fromOriginAscii, 0, fromOriginAscii.size)) {
                return null
            }
            return byteArrayOf('_'.code.toByte()) + toOriginAscii + rawKey.copyOfRange(nullSep, rawKey.size)
        }
        // shape 3: "METAACCESS:<origin>". MUST be checked BEFORE "META:", which is its prefix -- else the
        // key passes through stale and desktop chromium may treat the origin as never accessed and GC it.
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

    // null = not a DatabaseNameKey for fromOriginUtf16Be; caller keeps the key verbatim.
    // storage partitioning (chromium 105+) makes the origin `file__0`, `file__0@1`, ...: match the BASE
    // origin as a byte prefix and copy the `@<n>` suffix verbatim, never parsing it.
    //
    // key byte layout (chromium leveldb_coding_scheme.md, confirmed on device):
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

    // the partition-suffix bytes (possibly empty) when the origin slice starts with fromBytes, else null.
    internal fun matchOriginWithPartition(
        rawKey: ByteArray,
        originStart: Int,
        originLen: Int,
        fromBytes: ByteArray,
    ): ByteArray? {
        if (originLen < fromBytes.size) return null
        if (!regionEquals(rawKey, originStart, fromBytes.size, fromBytes, 0, fromBytes.size)) {
            return null
        }
        // suffix bytes = `@<n>` UTF-16BE tail, or empty when origin == base
        return rawKey.copyOfRange(originStart + fromBytes.size, originStart + originLen)
    }

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

    // mirrors Idb1Comparator.decodeVarInt. returns (value, bytesConsumed).
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

    // chromium names SSTables `.ldb`, iq80 only opens `.sst` (same format) -- without the rename iq80
    // fails with "Could not open table N". renamed back in finally so chromium can read the dir.
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
            // includes .sst files iq80 wrote during the block.
            dir.listFiles { _, name -> name.endsWith(".sst") }.orEmpty().forEach { sst ->
                val ldb = File(sst.parentFile, sst.nameWithoutExtension + ".ldb")
                if (!sst.renameTo(ldb)) {
                    Timber.tag("LevelDbRewriter").w("sst→ldb rename failed for %s", sst.name)
                }
            }
        }
    }

    // blob inlining. WebView 109 yields cursor.value=null for records starting
    // `<version_varint> ff 11 01 <size_varint> <offset_varint>` (the "replace-with-blob" wrapper newer
    // chromium writes). so inbound swaps each such value for its sidecar blob's bytes, snappy-unwrapped.
    // it MUST happen in the same pass that fills the fresh dst: mutating an opened db did not persist
    // in a form chromium re-reads.
    //
    // a data record (KeyType=01) has its blob_info at the sibling key with KeyType=03; blob_info byte[1]
    // is the blob number naming the sidecar at <blobDir>/<db_id>/<bucket>/<blob_num>.

    // content equality, for use as a map/set key.
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

    // data-record key -> the BlobRef its blob_info (KeyType=03) points at.
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

    // unwraps blink's `kCompressedWithSnappy` (idb_value_wrapping.cc):
    // `<data_version_varint> ff 11 02 <snappy-raw-bytes>`; the decompressed stream is already native
    // `ff 15 fe` + V8 SSV. returns the original value when not wrapped or on failure.
    // decompresses into one pre-sized buffer: peak memory ~value + output, not ~3x -- multi-10MB
    // blobs exist and would OOM tight devices.
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

    // `<leading_version_varint> + <sidecar_bytes>`, or the SAME `value` reference when nothing was
    // swapped -- the caller counts swaps by reference equality.
    private fun maybeInlineBlobValue(
        key: ByteArray,
        value: ByteArray,
        blobRefMap: Map<ByteArrayWrapper, BlobRef>,
        blobDir: File,
    ): ByteArray {
        val ref = blobRefMap[ByteArrayWrapper(key)] ?: return value
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

    // ObjectStore key layout (1-byte db_id / os_id):
    // byte 0: length-packed prefix header = 0x00 (db_len=1, os_len=1, index_len=0)
    // byte 1: database_id (1 byte for our min case)
    // byte 2: object_store_id (1 byte)
    // byte 3: KeyType -- 0x01 ObjectStoreDataKey, 0x02 ExistsEntryKey, 0x03 BlobEntryKey
    // bytes 4+: encoded user key (type-prefixed + length-prefixed UTF-16LE for strings)
    // ONLY prefix 0x00: other packings mean multi-byte ids, which this doesn't handle.
    private data class ParsedIdbKey(val dbId: Int, val osId: Int, val keyType: Int, val suffix: ByteArray)
    private fun parseIdbObjectStoreKey(key: ByteArray): ParsedIdbKey? {
        if (key.size < 5) return null
        if (key[0] != 0.toByte()) return null
        val dbId = key[1].toInt() and 0xFF
        val osId = key[2].toInt() and 0xFF
        val keyType = key[3].toInt() and 0xFF
        // anything else is metadata, which must never be mistaken for a data record.
        if (keyType !in setOf(0x01, 0x02, 0x03)) return null
        val suffix = key.copyOfRange(4, key.size)
        return ParsedIdbKey(dbId, osId, keyType, suffix)
    }

    // `<blobDir>/<dbId>/<bucket>/<blobNumber>`; the bucket name varies, so walk it. chromium names blob
    // files in hex, which only differs from decimal from 10 up; accept both, since older builds wrote
    // decimal.
    private fun findBlobFile(blobDir: File, dbId: Int, blobNumber: Int): File? {
        // same for the db_id dir: unverified which base chromium uses there, and both are right below 10.
        val dbSubdir = listOf(dbId.toString(), dbId.toString(16))
            .distinct()
            .map { File(blobDir, it) }
            .firstOrNull { it.isDirectory }
            ?: return null
        val hexName = blobNumber.toString(16)
        val decName = blobNumber.toString()
        dbSubdir.walkTopDown().forEach { f ->
            if (f.isFile && (f.name == hexName || f.name == decName)) return f
        }
        return null
    }

    // copies only blobs a live leveldb record still references. chromium's blob GC is lazy, so a plain
    // tree copy would re-upload blobs desktop already deleted, every cycle. dst is per-origin, so
    // wiping it first is safe and keeps stale Wine-side blobs from lingering.
    fun copyLiveBlobs(src: File?, dst: File?, liveRefs: Set<Pair<Int, Int>>) {
        if (dst == null) return
        if (src == null || !src.isDirectory) {
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

    // paranoidChecks off so a slightly bruised manifest still opens.
    private fun openDb(dir: File, readOnly: Boolean, useIdb1: Boolean): org.iq80.leveldb.DB {
        val options = Options().apply {
            createIfMissing(!readOnly)
            errorIfExists(false)
            compressionType(CompressionType.SNAPPY)
            paranoidChecks(false)
            // background compaction races the iterator (retiring SSTs mid-read) and the writer (memtable
            // flush), intermittently DROPPING records. off => flushes happen on the writer thread.
            compactionEnabled(false)
            if (useIdb1) {
                comparator(Idb1Comparator())
            }
        }
        return Iq80DBFactory.factory.open(dir, options)
    }

    // a missing .sst/.ldb here means a synthesized MANIFEST references a missing table: Corruption.
    internal fun classifyFailure(t: Throwable, src: File, dst: File): SaveSyncFailure =
        LeveldbFailures.classify(t, src, dst, sstLdbAsCorruption = true)

    // undoes iq80's failed-open recovery writes (MANIFEST-*, *.log, LOCK) so cloud sync can't upload them.
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
