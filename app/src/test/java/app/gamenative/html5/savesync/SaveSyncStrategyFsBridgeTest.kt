package app.gamenative.html5.savesync

import app.gamenative.html5.profile.EngineProfile
import app.gamenative.html5.profile.SaveSpec
import app.gamenative.html5.profile.SaveSyncSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class SaveSyncStrategyFsBridgeTest {

    @Test
    fun fsBridge_mechanismIsFsbridgeLiteral() {
        assertEquals("fsbridge", SaveSyncStrategy.FsBridge.mechanism)
    }

    @Test
    fun fsBridge_isPeerOfLevelDbAndRmmv() {
        val strategies: List<SaveSyncStrategy> = listOf(
            SaveSyncStrategy.FsBridge,
            SaveSyncStrategy.LevelDbOriginRewrite,
            SaveSyncStrategy.RmmvFilesystem,
        )
        assertEquals(3, strategies.size)
    }

    @Test
    fun fsBridge_syncOutbound_doesNotThrow() {
        val paths = makeEmptyPaths()
        SaveSyncStrategy.FsBridge.syncOutbound(paths, makeAnyOrigins())
    }

    @Test
    fun fsBridge_syncInbound_doesNotThrow() {
        val paths = makeEmptyPaths()
        SaveSyncStrategy.FsBridge.syncInbound(paths, makeAnyOrigins())
    }

    @Test
    fun forProfile_savesNull_returnsFsBridge() {
        val profile = profile(saves = null)
        assertSame(SaveSyncStrategy.FsBridge, SaveSyncStrategy.forProfile(profile))
    }

    @Test
    fun forProfile_syncNull_returnsFsBridge() {
        val profile = profile(saves = SaveSpec(sync = null))
        assertSame(SaveSyncStrategy.FsBridge, SaveSyncStrategy.forProfile(profile))
    }

    @Test
    fun forProfile_mechanismEmpty_returnsFsBridge() {
        val profile = profile(saves = SaveSpec(sync = SaveSyncSpec(mechanism = "")))
        assertSame(SaveSyncStrategy.FsBridge, SaveSyncStrategy.forProfile(profile))
    }

    @Test
    fun forProfile_mechanismFsbridgeLiteral_returnsFsBridge() {
        val profile = profile(saves = SaveSpec(sync = SaveSyncSpec(mechanism = "fsbridge")))
        assertSame(SaveSyncStrategy.FsBridge, SaveSyncStrategy.forProfile(profile))
    }

    @Test
    fun forProfile_mechanismLeveldbOriginRewrite_stillRoutesToLevelDb() {
        val profile = profile(saves = SaveSpec(sync = SaveSyncSpec(mechanism = "leveldb-origin-rewrite")))
        assertSame(SaveSyncStrategy.LevelDbOriginRewrite, SaveSyncStrategy.forProfile(profile))
    }

    @Test
    fun forProfile_mechanismRmmvFilesystem_stillRoutesToRmmvFilesystem() {
        val profile = profile(saves = SaveSpec(sync = SaveSyncSpec(mechanism = "rmmv-filesystem")))
        assertSame(SaveSyncStrategy.RmmvFilesystem, SaveSyncStrategy.forProfile(profile))
    }

    @Test
    fun forProfile_unknownMechanism_throws() {
        val profile = profile(saves = SaveSpec(sync = SaveSyncSpec(mechanism = "garbage-123")))
        assertThrows(SaveSyncFailure.Other::class.java) {
            SaveSyncStrategy.forProfile(profile)
        }
    }

    private fun profile(saves: SaveSpec?): EngineProfile =
        EngineProfile(engine = "pack:rmmv", saves = saves)

    private fun makeAnyOrigins() = Origins(
        webViewOriginUrl = "https://gamenative",
        webViewOriginFilename = "https_gamenative_0",
        pcOriginUrl = "file://",
        pcOriginFilename = "file__0",
    )

    private fun makeEmptyPaths(): SaveDirectoryResolver.SavePathPair {
        // no-op strategies never dereference these
        val dummy = java.io.File("/tmp/fsbridge-test-dummy")
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
        return SaveDirectoryResolver.SavePathPair(
            webView = webView,
            wine = wine,
            syncMode = SyncMode.LOCAL_ONLY,
        )
    }
}
