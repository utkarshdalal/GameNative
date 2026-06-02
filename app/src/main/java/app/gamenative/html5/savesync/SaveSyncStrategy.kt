package app.gamenative.html5.savesync

import app.gamenative.html5.profile.EngineProfile
import app.gamenative.html5.savesync.SaveDirectoryResolver.SavePathPair
import timber.log.Timber

// pluggable sync strategy. two variants ship:
// - LevelDbOriginRewrite: chromium LevelDB origin-prefix rewrite.
// - RmmvFilesystem: rmmv `.rpgsave` file ↔ localStorage KV passthrough.

// strategy doesn't know about CLOUD_ENABLED vs LOCAL_ONLY -- that branch is resolver-side.
// strategies consume a resolved SavePathPair and run IO in the correct direction.

// forProfile(p) dispatches on profile.saves.sync.mechanism. unknown values → SaveSyncFailure.Other.
sealed class SaveSyncStrategy {

    abstract val mechanism: String

    // 4-origin passdown. URL forms drive LS rewriter; filename forms drive
    // IDB rewriter. activeContainerOriginUrl locks cross-origin filter; always ==
    // webViewOriginUrl at call site, but kept explicit for clarity and test visibility.
    abstract fun syncOutbound(paths: SavePathPair, origins: Origins)
    abstract fun syncInbound(paths: SavePathPair, origins: Origins)

    // strategy A -- chromium LevelDB origin-prefix rewrite. fans out across localStorage +
    // (optionally) IndexedDB depending on which paths the resolver populated.
    object LevelDbOriginRewrite : SaveSyncStrategy() {

        override val mechanism: String = "leveldb-origin-rewrite"

        override fun syncOutbound(paths: SavePathPair, origins: Origins) {
            // LS: URL forms; active = webViewOriginUrl cross-origin filter)
            if (paths.webView.localStorageLevelDb.isDirectory) {
                LevelDbRewriter.rewriteLsOrigin(
                    src = paths.webView.localStorageLevelDb,
                    dst = paths.wine.localStorageLevelDb,
                    fromOriginUrl = origins.webViewOriginUrl,
                    toOriginUrl = origins.pcOriginUrl,
                    activeContainerOriginUrl = origins.webViewOriginUrl,
                )
            }
            // IDB: filename forms (DatabaseNameKey uses UTF-16BE encoded filename, not URL)
            val webIdb = paths.webView.indexedDbLevelDb
            val wineIdb = paths.wine.indexedDbLevelDb
            if (webIdb != null && wineIdb != null && webIdb.isDirectory) {
                // rewriteIdbOrigin returns the live blob ref set (dbId, blobNumber) parsed from
                // webview's blob_info records. copyLiveBlobs uses it to skip orphan physical files
                // left behind by chromium's lazy blob-journal GC. without filtering, Android
                // re-uploads blobs desktop just tombstoned, desktop re-deletes them next cycle --
                // bounded but wasteful churn.
                val liveRefs = LevelDbRewriter.rewriteIdbOrigin(
                    src = webIdb,
                    dst = wineIdb,
                    fromOriginFilename = origins.webViewOriginFilename,
                    toOriginFilename = origins.pcOriginFilename,
                )
                LevelDbRewriter.copyLiveBlobs(
                    src = paths.webView.indexedDbBlob,
                    dst = paths.wine.indexedDbBlob,
                    liveRefs = liveRefs,
                )
            }
        }

        override fun syncInbound(paths: SavePathPair, origins: Origins) {
            // envelope-skew gate: sniff cloud blob-wrapped IDB values BEFORE we rewrite.
            // if any blob's Blink envelope prefix is not on this device's compat list, abort
            // with IncompatibleEnvelope rather than silently importing bytes the WebView can't
            // parse (which would crash the game on slot load).

            // always log the sniff outcome (even examined=0) so session logs tell us whether the
            // sniffer actually hit the right dir. WARN when IDB leveldb is missing but other wine
            // save data is present -- that shape strongly suggests the resolver targeted the wrong
            // origin filename (file__0 vs chrome-extension_<hash>_0 mismatch is the common cause).
            val wineIdbLeveldb = paths.wine.indexedDbLevelDb
            val wineIdbBlob = paths.wine.indexedDbBlob
            val wineLsExists = paths.wine.localStorageLevelDb.isDirectory
            val wineIdbLeveldbExists = wineIdbLeveldb?.isDirectory == true
            val wineIdbBlobExists = wineIdbBlob?.isDirectory == true

            if (wineIdbLeveldb != null && !wineIdbLeveldbExists && wineLsExists) {
                Timber.tag("SaveSyncStrategy").w(
                    "wine IDB leveldb missing but LS present — likely resolver origin mis-target. " +
                        "idbExpected=%s lsPath=%s",
                    wineIdbLeveldb.absolutePath,
                    paths.wine.localStorageLevelDb.absolutePath,
                )
            }

            val report = BlobEnvelopeSniffer.inspect(
                blobDir = wineIdbBlob,
                compatibleSignatures = BlobEnvelopeSniffer.POC_COMPATIBLE_SIGNATURES,
            )
            val sigSummary = if (report.distinctSignatures.isEmpty()) {
                "<none>"
            } else {
                report.distinctSignatures.entries.joinToString(", ") { (sig, count) -> "${sig.hex()}×$count" }
            }
            Timber.tag("SaveSyncStrategy").i(
                "envelope sniff: examined=%d signatures=[%s] offender=%s blobDir=%s blobDirExists=%s",
                report.blobsExamined,
                sigSummary,
                report.firstOffender?.absolutePath ?: "<none>",
                wineIdbBlob?.absolutePath ?: "<null>",
                wineIdbBlobExists,
            )
            // classify offenders: rewritable (normalize during copy) vs unknown (abort).
            // mixed case = throw as unknown -- we don't silently pass half-normalized data.
            val offenderSigs = report.distinctSignatures.keys - BlobEnvelopeSniffer.POC_COMPATIBLE_SIGNATURES
            val unknownSigs = offenderSigs - BlobEnvelopeSniffer.REWRITABLE_SIGNATURES
            if (unknownSigs.isNotEmpty()) {
                throw SaveSyncFailure.IncompatibleEnvelope(
                    "cloud blob at ${report.firstOffender?.absolutePath} uses an envelope format " +
                        "this device's WebView cannot parse and we cannot rewrite. " +
                        "unknown signatures: [${unknownSigs.joinToString(", ") { it.hex() }}] " +
                        "all signatures found: [$sigSummary]",
                )
            }
            val shouldNormalizeBlobs = offenderSigs.isNotEmpty()
            if (shouldNormalizeBlobs) {
                Timber.tag("SaveSyncStrategy").i(
                    "envelope rewrite: will normalize %d offender blob(s) into WebView-native form (signatures=[%s])",
                    report.blobsExamined,
                    offenderSigs.joinToString(", ") { it.hex() },
                )
            }

            // LS: swap src↔dst + swap from↔to. activeContainerOriginUrl tracks the SRC's origin
            // (= fromOriginUrl) -- not the destination's. The filter inside rewriteLsKeyIfActive
            // requires `keyOrigin == activeContainerOriginAscii && keyOrigin == fromOriginAscii`,
            // which forces active = from. Setting it to webViewOriginUrl on INBOUND made every
            // src key (PC origin) fail the active check, so the rewriter wrote keys verbatim
            // under PC origin into the WebView's leveldb -- invisible to the game running at
            // webView origin (manifests as: cloud bytes restored, title screen still empty).
            if (paths.wine.localStorageLevelDb.isDirectory) {
                LevelDbRewriter.rewriteLsOrigin(
                    src = paths.wine.localStorageLevelDb,
                    dst = paths.webView.localStorageLevelDb,
                    fromOriginUrl = origins.pcOriginUrl,
                    toOriginUrl = origins.webViewOriginUrl,
                    activeContainerOriginUrl = origins.pcOriginUrl,
                )
            }
            // IDB: swap src↔dst + swap from↔to
            val webIdb = paths.webView.indexedDbLevelDb
            val wineIdb = paths.wine.indexedDbLevelDb
            if (webIdb != null && wineIdb != null && wineIdb.isDirectory) {
                // rewriteIdbOrigin inlines sidecar bytes AND snappy-decompresses in-flight via
                // maybeDecompressSnappyValue (LevelDbRewriter). read sidecars straight from wine
                // (unmodified). webview dir gets no blob files at all -- inlined records need none.
                LevelDbRewriter.rewriteIdbOrigin(
                    src = wineIdb,
                    dst = webIdb,
                    fromOriginFilename = origins.pcOriginFilename,
                    toOriginFilename = origins.webViewOriginFilename,
                    inlineBlobsFromDir = paths.wine.indexedDbBlob,
                )
            }
        }
    }

    // strategy B -- rmmv file↔localStorage passthrough. webViewOriginFilename drives the
    // LevelDB key prefix; Wine side is filesystem, not chromium.
    // DORMANT: our shims force RMMV's Utils.isNwjs()=true, so titles take the filesystem
    // (fsbridge) path and never localStorage. kept as a config-activatable escape hatch
    // (set saves.sync.mechanism="rmmv-filesystem") for a web-mode RMMV title that bypasses it.
    object RmmvFilesystem : SaveSyncStrategy() {

        override val mechanism: String = "rmmv-filesystem"

        override fun syncOutbound(paths: SavePathPair, origins: Origins) {
            // WebView localStorage → Wine save dir (.rpgsave files).
            // wine-side paths.wine.userDataRoot is the rmmv save directory (e.g. <install>/www/save).
            RmmvSaveMapper.writeLocalStorageToFiles(
                localStorageDb = paths.webView.localStorageLevelDb,
                webViewOriginPrefix = origins.webViewOriginFilename,
                saveDir = paths.wine.userDataRoot,
            )
        }

        override fun syncInbound(paths: SavePathPair, origins: Origins) {
            // Wine save dir (.rpgsave files) → WebView localStorage.
            RmmvSaveMapper.readFilesToLocalStorage(
                saveDir = paths.wine.userDataRoot,
                localStorageDb = paths.webView.localStorageLevelDb,
                webViewOriginPrefix = origins.webViewOriginFilename,
            )
        }
    }

    // strategy C -- fsbridge. the new universal default.
    // bytes already sit on disk at <container.installPath>/<game-relative-path> via Html5FsBridge
    // boundaries are no-ops: no KV translation, no format rewrite. Steam
    // Cloud UFS + Wine-side NW.js read the same bytes the WebView fsBridge wrote. confirms
    // no mirror-sync needed on variant flip since Wine sees the exact on-disk paths.
    object FsBridge : SaveSyncStrategy() {

        override val mechanism: String = "fsbridge"

        override fun syncOutbound(paths: SavePathPair, origins: Origins) {
            // no-op -- bytes on disk already.
        }

        override fun syncInbound(paths: SavePathPair, origins: Origins) {
            // no-op -- bytes on disk already.
        }
    }

    // opfs-backed worker save mirror for pack:c3+workerShim cohort.
    // boundary semantic: inbound = pull install dir → OPFS (always); outbound = flush
    // OPFS → install dir. actual byte movement happens via OpfsMirrorBridge from WebViewScreen
    // JS context (evaluateJavascript driven); the strategy's sync*() methods are no-ops because
    // SaveSyncStrategy doesn't have access to webView.evaluateJavascript. Html5SaveSyncService
    // dispatches the JS-side calls directly through pullInstallToOpfs / flushOpfsToInstall.
    object OpfsMirror : SaveSyncStrategy() {

        override val mechanism: String = "opfs-mirror"

        override fun syncOutbound(paths: SavePathPair, origins: Origins) {
            // dispatched via OpfsMirrorBridge from Html5SaveSyncService.flushOpfsToInstall.
        }

        override fun syncInbound(paths: SavePathPair, origins: Origins) {
            // dispatched via OpfsMirrorBridge from Html5SaveSyncService.pullInstallToOpfs.
        }
    }

    companion object {
        // profile dispatch. universal default is FsBridge (null, empty,
        // missing saves block, or explicit "fsbridge" all resolve to it). explicit LevelDb or
        // Rmmv values still route to their dormant strategies as an escape hatch. ONLY
        // truly unrecognized non-empty values throw -- those indicate a typo in a profile.
        fun forProfile(profile: EngineProfile): SaveSyncStrategy {
            val m = profile.saves?.sync?.mechanism
            return when {
                m == null || m.isBlank() || m == "fsbridge" -> FsBridge
                m == "leveldb-origin-rewrite" -> LevelDbOriginRewrite
                m == "rmmv-filesystem" -> RmmvFilesystem
                m == "opfs-mirror" -> OpfsMirror 
                else -> throw SaveSyncFailure.Other("unknown saves.sync.mechanism in profile: $m")
            }
        }
    }
}

// 4-origin bundle passed to every strategy call. URL forms drive LS rewriter
// (chromium LS key format: "_<full-URL>\0<key>" / "META:<full-URL>"); filename forms drive IDB
// rewriter (chromium IDB DatabaseNameKey: varint + UTF-16BE encoded filename).
data class Origins(
    val webViewOriginUrl: String,       // e.g. "http://steam-2738490.localhost:5723"
    val webViewOriginFilename: String,  // e.g. "http_steam-2738490.localhost_5723"
    val pcOriginUrl: String,            // e.g. "file://" (from pack JSON pcOrigin)
    val pcOriginFilename: String,       // e.g. "file__0" (derived via OriginCodec.filenameFromUrl)
)
