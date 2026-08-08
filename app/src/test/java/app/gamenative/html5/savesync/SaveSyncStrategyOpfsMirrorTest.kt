package app.gamenative.html5.savesync

import app.gamenative.html5.profile.EngineProfile
import app.gamenative.html5.profile.SaveSpec
import app.gamenative.html5.profile.SaveSyncSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

// pure-jvm. SaveSyncStrategy objects are plain Kotlin singletons; forProfile reads
// EngineProfile data class fields only. mirrors SaveSyncStrategyFsBridgeTest shape.
class SaveSyncStrategyOpfsMirrorTest {

    @Test fun mechanismIsOpfsMirror() {
        assertEquals("opfs-mirror", SaveSyncStrategy.OpfsMirror.mechanism)
    }

    @Test fun forProfile_dispatchesOpfsMirror() {
        val p = EngineProfile(
            engine = "pack:c3",
            workerShim = true,
            saves = SaveSpec(sync = SaveSyncSpec(mechanism = "opfs-mirror")),
        )
        val s = SaveSyncStrategy.forProfile(p)
        assertSame(SaveSyncStrategy.OpfsMirror, s)
    }

    @Test fun forProfile_otherMechanismsUnchanged() {
        val ldb = EngineProfile(saves = SaveSpec(sync = SaveSyncSpec(mechanism = "leveldb-origin-rewrite")))
        assertSame(SaveSyncStrategy.LevelDbOriginRewrite, SaveSyncStrategy.forProfile(ldb))

        val fb = EngineProfile(saves = SaveSpec(sync = SaveSyncSpec(mechanism = "fsbridge")))
        assertSame(SaveSyncStrategy.FsBridge, SaveSyncStrategy.forProfile(fb))

        val rmmv = EngineProfile(saves = SaveSpec(sync = SaveSyncSpec(mechanism = "rmmv-filesystem")))
        assertSame(SaveSyncStrategy.RmmvFilesystem, SaveSyncStrategy.forProfile(rmmv))
    }

    @Test fun syncOutboundIsNoOp_doesNotThrow() {
        // body is empty; pass permissive args. construction logic mirrors
        // SaveSyncStrategyFsBridgeTest.fsBridge_syncOutbound_doesNotThrow.
        val origins = Origins(
            webViewOriginUrl = "https://gamenative",
            webViewOriginFilename = "https_gamenative_0",
            pcOriginUrl = "file://",
            pcOriginFilename = "file__0",
        )
        val dummy = java.io.File("/tmp/opfs-mirror-test-dummy")
        val webView = SaveDirectoryResolver.WebViewPaths(
            localStorageLevelDb = dummy,
            indexedDbLevelDb = null,
            indexedDbBlob = null,
        )
        val wine = SaveDirectoryResolver.WinePaths(
            userDataRoot = dummy,
            localStorageLevelDb = dummy,
            indexedDbLevelDb = null,
            indexedDbBlob = null,
        )
        val paths = SaveDirectoryResolver.SavePathPair(
            webView = webView,
            wine = wine,
            syncMode = SyncMode.LOCAL_ONLY,
        )
        SaveSyncStrategy.OpfsMirror.syncOutbound(paths, origins)
        SaveSyncStrategy.OpfsMirror.syncInbound(paths, origins)
    }
}
