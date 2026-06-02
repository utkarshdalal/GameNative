package app.gamenative.html5.savesync

import app.gamenative.html5.profile.EngineProfile
import app.gamenative.html5.savesync.SaveDirectoryResolver.SavePathPair
import timber.log.Timber

// strategies only move bytes between a resolved SavePathPair; CLOUD_ENABLED vs LOCAL_ONLY is
// decided resolver-side.
sealed class SaveSyncStrategy {

    abstract val mechanism: String

    // pageLocalStorage: the page's LS captured before teardown (LocalStorageSnapshot); only the
    // leveldb rewrite uses it.
    abstract fun syncOutbound(
        paths: SavePathPair,
        origins: Origins,
        pageLocalStorage: List<Pair<ByteArray, ByteArray>>? = null,
    )
    abstract fun syncInbound(paths: SavePathPair, origins: Origins)

    // covers LS plus IDB when the resolver found one.
    object LevelDbOriginRewrite : SaveSyncStrategy() {

        override val mechanism: String = "leveldb-origin-rewrite"

        override fun syncOutbound(paths: SavePathPair, origins: Origins, pageLocalStorage: List<Pair<ByteArray, ByteArray>>?) {
            if (paths.webView.localStorageLevelDb.isDirectory) {
                LevelDbRewriter.rewriteLsOrigin(
                    src = paths.webView.localStorageLevelDb,
                    dst = paths.wine.localStorageLevelDb,
                    fromOriginUrl = origins.webViewOriginUrl,
                    toOriginUrl = origins.pcOriginUrl,
                    activeContainerOriginUrl = origins.webViewOriginUrl,
                    keepOnlyToOriginInDst = true,
                    fromOriginEntries = pageLocalStorage,
                )
            }
            // IDB keys carry the origin in filename form, not URL form
            val webIdb = paths.webView.indexedDbLevelDb
            val wineIdb = paths.wine.indexedDbLevelDb
            if (webIdb != null && wineIdb != null && webIdb.isDirectory) {
                // live blob refs let copyLiveBlobs skip orphans chromium's lazy blob GC left behind;
                // otherwise we re-upload blobs desktop just deleted, and it deletes them again.
                val liveRefs = LevelDbRewriter.rewriteIdbOrigin(
                    src = webIdb,
                    dst = wineIdb,
                    fromOriginFilename = origins.webViewOriginFilename,
                    toOriginFilename = origins.pcOriginFilename,
                )
                // null = webview IDB never committed and the Wine leveldb was left as is; its blobs must
                // stay too or the copy references blobs that are gone.
                if (liveRefs != null) {
                    LevelDbRewriter.copyLiveBlobs(
                        src = paths.webView.indexedDbBlob,
                        dst = paths.wine.indexedDbBlob,
                        liveRefs = liveRefs,
                    )
                }
            }
        }

        override fun syncInbound(paths: SavePathPair, origins: Origins) {
            // envelope gate BEFORE the rewrite: importing blobs this WebView can't parse would crash
            // the game on slot load. the sniff is logged even when empty, to show it hit the right dir.
            // IDB missing while LS exists usually means the resolver picked the wrong origin filename
            // (file__0 vs chrome-extension_<hash>_0).
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
            // any unknown signature aborts, even alongside rewritable ones -- no half-normalized data.
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

            // LS is NOT written here: chromium keeps the WebView's LS leveldb open for the whole process once any
            // WebView used it, so the page applies it instead (Html5SaveSyncService.stageLsRestore -> ls-restore.js).
            // IDB is safe to write directly: chromium closes a game's IDB leveldb when its WebView is destroyed.
            val webIdb = paths.webView.indexedDbLevelDb
            val wineIdb = paths.wine.indexedDbLevelDb
            if (webIdb != null && wineIdb != null && wineIdb.isDirectory) {
                // blob sidecars are inlined (and snappy-unwrapped) into the records, so the webview
                // side gets no blob files at all.
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

    // DORMANT: our shims force RMMV's Utils.isNwjs()=true, so titles save through fsbridge, never LS.
    // kept as an escape hatch (saves.sync.mechanism="rmmv-filesystem") for a web-mode RMMV title.
    // before activating: inbound writes the live WebView LS store with iq80, unsafe once any WebView ran
    // in this process (see LevelDbRewriter.rewriteLsOrigin). route it through the page-side restore
    // (Html5SaveSyncService.stageLsRestore) the way LevelDbOriginRewrite does.
    object RmmvFilesystem : SaveSyncStrategy() {

        override val mechanism: String = "rmmv-filesystem"

        override fun syncOutbound(paths: SavePathPair, origins: Origins, pageLocalStorage: List<Pair<ByteArray, ByteArray>>?) {
            // paths.wine.userDataRoot is the rmmv save dir (e.g. <install>/www/save).
            RmmvSaveMapper.writeLocalStorageToFiles(
                localStorageDb = paths.webView.localStorageLevelDb,
                webViewOriginPrefix = origins.webViewOriginFilename,
                saveDir = paths.wine.userDataRoot,
            )
        }

        override fun syncInbound(paths: SavePathPair, origins: Origins) {
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

        override fun syncOutbound(paths: SavePathPair, origins: Origins, pageLocalStorage: List<Pair<ByteArray, ByteArray>>?) {
        }

        override fun syncInbound(paths: SavePathPair, origins: Origins) {
        }
    }

    // pack:c3 worker-shim saves live in OPFS. bytes move page-side through OpfsMirrorBridge, which needs
    // the WebView, so these hooks are no-ops: inbound runs from Html5SaveSyncService.pullInstallToOpfs,
    // outbound from C3WorkerShimSetup.kickOffExitFlush.
    object OpfsMirror : SaveSyncStrategy() {

        override val mechanism: String = "opfs-mirror"

        override fun syncOutbound(paths: SavePathPair, origins: Origins, pageLocalStorage: List<Pair<ByteArray, ByteArray>>?) {
        }

        override fun syncInbound(paths: SavePathPair, origins: Origins) {
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

// URL forms drive the LS rewrite ("_<url>\0<key>" / "META:<url>"); filename forms drive the IDB
// rewrite (DatabaseNameKey holds the UTF-16BE filename).
data class Origins(
    val webViewOriginUrl: String,       // e.g. "http://steam-2738490.localhost:5723"
    val webViewOriginFilename: String,  // e.g. "http_steam-2738490.localhost_5723"
    val pcOriginUrl: String,            // e.g. "file://" (from pack JSON pcOrigin)
    val pcOriginFilename: String,       // e.g. "file__0" (derived via OriginCodec.filenameFromUrl)
)
