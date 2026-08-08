package app.gamenative.html5.savesync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.SaveFilePattern
import app.gamenative.html5.host.WebViewOrigin
import app.gamenative.data.SteamApp
import app.gamenative.data.UFS
import app.gamenative.enums.PathType
import app.gamenative.html5.profile.EngineProfile
import app.gamenative.html5.profile.SaveSpec
import app.gamenative.html5.profile.SaveSyncSpec
import com.winlator.container.Container
import com.winlator.xenvironment.ImageFs
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// unit tests for SaveDirectoryResolver. robolectric because context.dataDir + ImageFs.find()
// trigger android <clinit>. all actual IO is mocked via TemporaryFolder stand-ins — resolver
// itself is pure path arithmetic.
@RunWith(RobolectricTestRunner::class)
class SaveDirectoryResolverTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // pin the loopback port so origin URLs are deterministic. resolver derives the
        // leveldb origin filename from WebViewOrigin.originUrl which calls
        // ensurePortAllocated — mock the latter so we don't depend on PrefManager init or
        // the filesystem sentinel.
        mockkObject(WebViewOrigin)
        every { WebViewOrigin.ensurePortAllocated() } returns 5723
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ---------------- fixtures ----------------

    private fun makeContainer(id: String = "STEAM_2738490", installPath: String = ""): Container {
        val container = Container(id)
        container.installPath = installPath
        // resolver falls back to imagefs/home/xuser-<id>/ when rootDir not set.
        return container
    }

    // set explicit rootDir so pre-seeded wine dirs live inside a predictable temp path.
    // used by IDB origin discovery tests that need to write files BEFORE resolve() runs.
    private fun makeContainerWithRoot(
        id: String = "STEAM_2738490",
        root: File,
        installPath: String = root.absolutePath,
    ): Container {
        val container = Container(id)
        container.rootDir = root
        container.installPath = installPath
        return container
    }

    private fun makeProfile(
        engine: String = "pack:c3",
        sync: SaveSyncSpec? = SaveSyncSpec(
            pcOrigin = "file://",
            pcPath = "%LOCALAPPDATA%/SolCesto/User Data/Default/",
            mechanism = "leveldb-origin-rewrite",
        ),
    ): EngineProfile = EngineProfile(
        engine = engine,
        entryPoint = "index.html",
        saves = SaveSpec(sync = sync),
    )

    private fun steamAppWithUfs(vararg patterns: SaveFilePattern): SteamApp {
        return SteamApp(id = 2738490, name = "SolCesto", ufs = UFS(saveFilePatterns = patterns.toList()))
    }

    // unblock: prior `appInfo = null` cases mapped to the LOCAL_ONLY branch.
    // CloudSource.SteamUfs with empty UFS keeps that semantic — isSupported=false, no patterns.
    private fun emptySteamSource(container: Container): CloudSource = CloudSource.SteamUfs(
        steamApp = SteamApp(id = 0, name = "x", ufs = UFS(saveFilePatterns = emptyList())),
        container = container,
    )

    // ---------------- tests ----------------

    // localStorage + IDB paths populated under shared Default/ profile dir; per-container
    // partition comes from origin (encoded in IDB filename), not profile.
    @Test
    fun resolve_returnsSharedDefaultProfileDir() {
        val container = makeContainer(id = "STEAM_2738490", installPath = "/tmp/some-install")
        val profile = makeProfile()
        val pattern = SaveFilePattern(
            root = PathType.WinAppDataLocal,
            path = "SolCesto/User Data/Default/",
            pattern = "*",
        )
        val app = steamAppWithUfs(pattern)

        val result = SaveDirectoryResolver.resolve(
            context = context,
            appId = "STEAM_2738490",
            container = container,
            profile = profile,
            source = CloudSource.SteamUfs(app, container),
        )

        assertTrue(
            "webview LS path must live under shared Default/. actual: ${result.webView.localStorageLevelDb.path}",
            result.webView.localStorageLevelDb.path.endsWith(
                "app_webview/Default/Local Storage/leveldb",
            ),
        )
        assertTrue(
            "Wine path must anchor inside Chromium user-data subtree",
            result.wine.userDataRoot.path.contains("SolCesto/User Data/Default"),
        )
    }

    // both stores — all 4 path fields non-null.
    @Test
    fun resolve_both_returnsAllPaths() {
        val container = makeContainer()
        val profile = makeProfile()
        val pattern = SaveFilePattern(
            root = PathType.WinAppDataLocal,
            path = "SolCesto/User Data/Default/",
            pattern = "*",
        )
        val app = steamAppWithUfs(pattern)

        val result = SaveDirectoryResolver.resolve(context, "STEAM_2738490", container, profile, CloudSource.SteamUfs(app, container))

        assertNotNull(result.webView.localStorageLevelDb)
        assertNotNull(result.webView.indexedDbLevelDb)
        assertNotNull(result.webView.indexedDbBlob)
        assertNotNull(result.wine.localStorageLevelDb)
        assertNotNull(result.wine.indexedDbLevelDb)
        assertNotNull(result.wine.indexedDbBlob)
    }

    // null sync spec → PathMissing.
    @Test(expected = SaveSyncFailure.PathMissing::class)
    fun resolve_nullSyncSpec_throwsPathMissing() {
        val container = makeContainer()
        val profile = makeProfile(sync = null)
        SaveDirectoryResolver.resolve(context, "STEAM_2738490", container, profile, source = emptySteamSource(container))
    }

    // .. in pcPath → Other (SECURITY guard).
    @Test(expected = SaveSyncFailure.Other::class)
    fun resolve_pcPathWithDotDot_throwsOther() {
        val container = makeContainer()
        val profile = makeProfile(
            sync = SaveSyncSpec(
                pcOrigin = "file://",
                pcPath = "%LOCALAPPDATA%/../../escape/",
                mechanism = "leveldb-origin-rewrite",
            ),
        )
        SaveDirectoryResolver.resolve(context, "STEAM_2738490", container, profile, source = emptySteamSource(container))
    }

    // container.id must appear in IMAGEFS_DIRECT wine path.
    @Test
    fun resolve_containerIdUsedInPath() {
        val distinctiveId = "STEAM_CONTAINER_12345"
        val container = makeContainer(id = distinctiveId)
        val profile = makeProfile()
        val pattern = SaveFilePattern(
            root = PathType.WinAppDataLocal,
            path = "SolCesto/User Data/Default/",
            pattern = "*",
        )
        val app = steamAppWithUfs(pattern)

        val result = SaveDirectoryResolver.resolve(context, distinctiveId, container, profile, CloudSource.SteamUfs(app, container))

        assertTrue(
            "IMAGEFS_DIRECT wine path must embed container id. actual: ${result.wine.userDataRoot.path}",
            result.wine.userDataRoot.path.contains("${ImageFs.USER}-$distinctiveId"),
        )
    }

    // ---------------- new cases ----------------

    // a: CLOUD_ENABLED via UFS saveFilePatterns.
    @Test
    fun resolve_cloudEnabled_usesUfsSaveFilePatterns() {
        val container = makeContainer(id = "STEAM_2738490", installPath = "/data/local/tmp/solcesto")
        // profile doesn't supply pcPath override — UFS drives resolution.
        val profile = makeProfile(
            sync = SaveSyncSpec(
                pcOrigin = "file://",
                mechanism = "leveldb-origin-rewrite",
            ),
        )
        val pattern = SaveFilePattern(
            root = PathType.GameInstall,
            path = "www/save",
            pattern = "*",
        )
        val app = steamAppWithUfs(pattern)

        val result = SaveDirectoryResolver.resolve(context, "STEAM_2738490", container, profile, CloudSource.SteamUfs(app, container))

        assertEquals(SyncMode.CLOUD_ENABLED, result.syncMode)
        assertTrue(
            "userDataRoot must end with www/save for %GameInstall%www/save pattern. actual: ${result.wine.userDataRoot.path}",
            result.wine.userDataRoot.path.replace("\\", "/").endsWith("www/save"),
        )
    }

    // b: LOCAL_ONLY + pack:rmmv default.
    @Test
    fun resolve_localOnly_usesPackDefault() {
        val install = tempFolder.newFolder("install").absolutePath
        val container = makeContainer(id = "CUSTOM_GAME_1846830703", installPath = install)
        val profile = EngineProfile(
            engine = "pack:rmmv",
            entryPoint = "index.html",
            saves = SaveSpec(
                sync = SaveSyncSpec(mechanism = "leveldb-origin-rewrite"),
            ),
        )

        val result = SaveDirectoryResolver.resolve(
            context = context,
            appId = "CUSTOM_GAME_1846830703",
            container = container,
            profile = profile,
            source = emptySteamSource(container),
        )

        assertEquals(SyncMode.LOCAL_ONLY, result.syncMode)
        val expectedSuffix = "$install${File.separator}www${File.separator}save"
        assertEquals(
            "LOCAL_ONLY resolves to <install>/www/save for pack:rmmv",
            expectedSuffix,
            result.wine.userDataRoot.path,
        )
    }

    // titles without IDB save state declare blank pcOrigin → resolvePcOriginFilename returns
    // null → wine IDB paths are null. webview-side paths remain populated unconditionally
    // (chromium lazy-creates the dirs; non-existence is handled downstream via runCatching).
    // replaces the deleted storage-list gating tests with the live "no-IDB" semantics.
    @Test
    fun resolve_blankPcOrigin_returnsNullWineIdbPaths_butWebViewIdbPathsResolved() {
        val container = makeContainer(id = "STEAM_2738490")
        val profile = makeProfile(
            sync = SaveSyncSpec(
                pcOrigin = "",
                mechanism = "leveldb-origin-rewrite",
            ),
        )
        val pattern = SaveFilePattern(
            root = PathType.WinAppDataLocal,
            path = "SolCesto/User Data/Default/",
            pattern = "*",
        )
        val app = steamAppWithUfs(pattern)

        val result = SaveDirectoryResolver.resolve(context, "STEAM_2738490", container, profile, CloudSource.SteamUfs(app, container))

        assertNull("wine IDB leveldb must be null when pcOrigin is blank", result.wine.indexedDbLevelDb)
        assertNull("wine IDB blob must be null when pcOrigin is blank", result.wine.indexedDbBlob)
        assertNotNull("webview IDB leveldb stays populated unconditionally", result.webView.indexedDbLevelDb)
        assertNotNull("webview IDB blob stays populated unconditionally", result.webView.indexedDbBlob)
        assertNotNull("wine LS path is independent of pcOrigin", result.wine.localStorageLevelDb)
    }

    // container.id (not appId) drives the synthetic origin used to name the IDB leveldb dir.
    // guards against future drift between appId and container.id (e.g. if container migration
    // ever renames the id field or if appId-based slug math sneaks back in).
    @Test
    fun resolveWebViewPaths_uses_container_id_not_appId() {
        val container = makeContainer(id = "STEAM_999")
        val profile = makeProfile()
        val pattern = SaveFilePattern(
            root = PathType.WinAppDataLocal,
            path = "SolCesto/User Data/Default/",
            pattern = "*",
        )
        val app = SteamApp(id = 999, name = "NineNineNine", ufs = UFS(saveFilePatterns = listOf(pattern)))

        val result = SaveDirectoryResolver.resolve(
            context = context,
            appId = "STEAM_999",
            container = container,
            profile = profile,
            source = CloudSource.SteamUfs(app, container),
        )

        assertTrue(
            "LS path must live in shared Default/. actual: ${result.webView.localStorageLevelDb.path}",
            result.webView.localStorageLevelDb.path.endsWith("app_webview/Default/Local Storage/leveldb"),
        )
        assertTrue(
            "IDB filename must encode the per-container origin (http_steam-999.localhost_5723). actual: ${result.webView.indexedDbLevelDb!!.path}",
            result.webView.indexedDbLevelDb!!.path.contains("Default/IndexedDB/http_steam-999.localhost_5723.indexeddb.leveldb"),
        )
    }

    // ---------------- 4 pcOrigin URL → filename derivation ----------------

    // pcOrigin URL → resolver derives filename via OriginCodec.filenameFromUrl.
    // "file://" → "file__0" (empty authority + default port 0 → two underscores).
    @Test
    fun resolveIdbPath_fromPcOriginUrl_derivesFilename() {
        val container = makeContainer(id = "STEAM_379210", installPath = "/tmp/wayward")
        val profile = makeProfile(
            sync = SaveSyncSpec(
                pcOrigin = "file://",
                mechanism = "leveldb-origin-rewrite",
            ),
        )
        val pattern = SaveFilePattern(
            root = PathType.WinAppDataLocal,
            path = "Wayward/User Data/Default/",
            pattern = "*",
        )
        val app = SteamApp(id = 379210, name = "Wayward", ufs = UFS(saveFilePatterns = listOf(pattern)))

        val result = SaveDirectoryResolver.resolve(context, "STEAM_379210", container, profile, CloudSource.SteamUfs(app, container))

        assertNotNull("wine IDB leveldb must be non-null when pcOrigin set", result.wine.indexedDbLevelDb)
        assertTrue(
            "wine IDB dir must derive filename 'file__0' from pcOrigin='file://'. actual: ${result.wine.indexedDbLevelDb!!.name}",
            result.wine.indexedDbLevelDb!!.name == "file__0.indexeddb.leveldb",
        )
    }

    // ----------------IDB origin auto-discovery ----------------

    // discovery overrides profile pcOrigin when on-disk origin differs — Sol Cesto case.
    // profile declares "file://" (common pack default) but the cloud-downloaded leveldb uses
    // chrome-extension_<hash>_0 (Electron's in-process origin for a bundled app).
    @Test
    fun resolve_discoveryOverridesProfilePcOrigin_whenWineSideHasRealOrigin() {
        val containerRoot = tempFolder.newFolder("container-solcesto")
        val container = makeContainerWithRoot(id = "STEAM_2738490", root = containerRoot)
        val profile = makeProfile(
            sync = SaveSyncSpec(
                pcOrigin = "file://",
                mechanism = "leveldb-origin-rewrite",
            ),
        )
        val pattern = SaveFilePattern(
            root = PathType.WinAppDataLocal,
            path = "SolCesto/User Data/Default/",
            pattern = "*",
        )
        val app = steamAppWithUfs(pattern)

        // pre-seed the wine IDB dir with the real (chrome-extension) origin + populated blob.
        val userDataRoot = File(
            containerRoot,
            ".wine/drive_c/users/xuser/AppData/Local/SolCesto/User Data/Default",
        )
        val realOrigin = "chrome-extension_anopiimlkmdoenonenclohfilpeenfmj_0"
        File(userDataRoot, "IndexedDB/$realOrigin.indexeddb.leveldb").apply { mkdirs() }
        val blobBucket = File(userDataRoot, "IndexedDB/$realOrigin.indexeddb.blob/1/00").apply { mkdirs() }
        File(blobBucket, "1").writeBytes(byteArrayOf(0xFF.toByte(), 0x11, 0x02))

        val result = SaveDirectoryResolver.resolve(context, "STEAM_2738490", container, profile, CloudSource.SteamUfs(app, container))

        assertEquals(
            "discovery must override profile-declared file__0 with on-disk chrome-extension origin",
            "$realOrigin.indexeddb.leveldb",
            result.wine.indexedDbLevelDb!!.name,
        )
        assertEquals(
            "$realOrigin.indexeddb.blob",
            result.wine.indexedDbBlob!!.name,
        )
    }

    // discovery returns null → falls back to profile. fresh install / pre-cloud-download.
    @Test
    fun resolve_discoveryFallsBackToProfile_whenNoCandidatesOnDisk() {
        val containerRoot = tempFolder.newFolder("container-fresh")
        val container = makeContainerWithRoot(id = "STEAM_379210", root = containerRoot)
        val profile = makeProfile(
            sync = SaveSyncSpec(
                pcOrigin = "file://",
                mechanism = "leveldb-origin-rewrite",
            ),
        )
        val pattern = SaveFilePattern(
            root = PathType.WinAppDataLocal,
            path = "Wayward/User Data/Default/",
            pattern = "*",
        )
        val app = SteamApp(id = 379210, name = "Wayward", ufs = UFS(saveFilePatterns = listOf(pattern)))

        val result = SaveDirectoryResolver.resolve(context, "STEAM_379210", container, profile, CloudSource.SteamUfs(app, container))

        assertEquals(
            "no on-disk dirs → profile pcOrigin drives filename",
            "file__0.indexeddb.leveldb",
            result.wine.indexedDbLevelDb!!.name,
        )
    }

    // two candidates: empty shell + real data. discovery prefers the one with populated blobs.
    @Test
    fun resolve_discoveryPrefersCandidateWithPopulatedBlobs() {
        val containerRoot = tempFolder.newFolder("container-multi")
        val container = makeContainerWithRoot(id = "STEAM_9999", root = containerRoot)
        val profile = makeProfile(
            sync = SaveSyncSpec(
                pcOrigin = "file://",
                mechanism = "leveldb-origin-rewrite",
            ),
        )
        val pattern = SaveFilePattern(
            root = PathType.WinAppDataLocal,
            path = "Game/User Data/Default/",
            pattern = "*",
        )
        val app = SteamApp(id = 9999, name = "Game", ufs = UFS(saveFilePatterns = listOf(pattern)))

        val userDataRoot = File(
            containerRoot,
            ".wine/drive_c/users/xuser/AppData/Local/Game/User Data/Default",
        )
        val idbDir = File(userDataRoot, "IndexedDB")
        File(idbDir, "file__0.indexeddb.leveldb").apply { mkdirs() } // stale empty shell
        File(idbDir, "chrome-extension_abc_0.indexeddb.leveldb").apply { mkdirs() }
        val blobBucket = File(idbDir, "chrome-extension_abc_0.indexeddb.blob/1/00").apply { mkdirs() }
        File(blobBucket, "1").writeBytes(byteArrayOf(0xFF.toByte(), 0x11, 0x02))

        val result = SaveDirectoryResolver.resolve(context, "STEAM_9999", container, profile, CloudSource.SteamUfs(app, container))

        assertEquals(
            "chrome-extension_abc_0.indexeddb.leveldb",
            result.wine.indexedDbLevelDb!!.name,
        )
    }

    // c: ufsPatternIndex pins Nth windows-rooted pattern.
    @Test
    fun resolve_ufsPatternIndexPinsNthPattern() {
        val container = makeContainer()
        val profile = makeProfile(
            sync = SaveSyncSpec(
                pcOrigin = "file://",
                mechanism = "leveldb-origin-rewrite",
                ufsPatternIndex = 1,
            ),
        )
        val app = steamAppWithUfs(
            SaveFilePattern(root = PathType.WinAppDataLocal, path = "logs", pattern = "*"),
            SaveFilePattern(root = PathType.WinAppDataLocal, path = "SolCesto/User Data/Default/", pattern = "*"),
            SaveFilePattern(root = PathType.WinAppDataLocal, path = "config", pattern = "*"),
        )

        val result = SaveDirectoryResolver.resolve(context, "STEAM_2738490", container, profile, CloudSource.SteamUfs(app, container))

        assertTrue(
            "ufsPatternIndex=1 must pick the SolCesto pattern. actual: ${result.wine.userDataRoot.path}",
            result.wine.userDataRoot.path.contains("SolCesto/User Data/Default"),
        )
    }
}
