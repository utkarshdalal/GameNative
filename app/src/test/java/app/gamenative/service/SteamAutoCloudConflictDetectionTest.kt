package app.gamenative.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.ChangeNumbers
import app.gamenative.data.ConfigInfo
import app.gamenative.data.SaveFilePattern
import app.gamenative.data.SteamApp
import app.gamenative.data.UFS
import app.gamenative.data.UserFileInfo
import app.gamenative.db.PluviaDatabase
import app.gamenative.enums.AppType
import app.gamenative.enums.OS
import app.gamenative.enums.PathType
import app.gamenative.enums.ReleaseState
import app.gamenative.enums.SaveLocation
import app.gamenative.enums.SyncResult
import app.gamenative.utils.Net
import com.winlator.container.Container
import com.winlator.xenvironment.ImageFs
import `in`.dragonbra.javasteam.steam.handlers.steamcloud.AppFileChangeList
import `in`.dragonbra.javasteam.steam.handlers.steamcloud.AppFileInfo
import `in`.dragonbra.javasteam.steam.handlers.steamcloud.SteamCloud
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.File
import java.util.Date
import java.util.EnumSet
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

// regression lock — these 4 tests pin the SteamAutoCloud SHA cross-check on BOTH
// cache-absent and cache-present paths.
@RunWith(RobolectricTestRunner::class)
class SteamAutoCloudConflictDetectionTest {

    private lateinit var context: Context
    private lateinit var tempDir: File
    private lateinit var saveFilesDir: File
    private lateinit var db: PluviaDatabase
    private lateinit var mockSteamService: SteamService
    private lateinit var mockSteamCloud: SteamCloud
    private lateinit var mockParallelHttpClient: OkHttpClient
    private val testAppId = "STEAM_654321"
    private val steamAppId = 654321
    private val clientId = 1L

    @Before
    fun setUp() {
        mockkObject(Net)
        mockParallelHttpClient = mockk(relaxed = true)
        every { Net.httpForParallelDownloads(any()) } returns mockParallelHttpClient

        context = ApplicationProvider.getApplicationContext()
        tempDir = File.createTempFile("steam_autocloud_conflict_", null)
        tempDir.delete()
        tempDir.mkdirs()

        DownloadService.populateDownloadService(context)
        File(SteamService.internalAppInstallPath).mkdirs()
        SteamService.externalAppInstallPath.takeIf { it.isNotBlank() }?.let { File(it).mkdirs() }

        val imageFs = ImageFs.find(context)
        val homeDir = File(imageFs.rootDir, "home")
        homeDir.mkdirs()

        val containerDir = File(homeDir, "${ImageFs.USER}-$testAppId")
        containerDir.mkdirs()

        val container = Container(testAppId)
        container.setRootDir(containerDir)
        container.name = "Test Conflict Container"
        container.saveData()

        // build the windows save dir: %WinMyDocuments%/My Games/TestGame/Steam/<sid>/SaveGames
        val wineprefix = File(imageFs.wineprefix)
        wineprefix.mkdirs()
        val saveGames = File(wineprefix, "dosdevices/c:/users/xuser/Documents/My Games/TestGame/Steam/76561198025127569/SaveGames")
        saveGames.mkdirs()
        saveFilesDir = saveGames

        db = Room.inMemoryDatabaseBuilder(context, PluviaDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        // single UFS pattern keeps the test deterministic — only one prefixPath shape
        // to reason about when forging cache rows that drift vs. the live scan.
        val patterns = listOf(
            SaveFilePattern(
                root = PathType.WinMyDocuments,
                path = "My Games/TestGame/Steam/76561198025127569/SaveGames",
                pattern = "*.sav",
            ),
        )

        val testApp = SteamApp(
            id = steamAppId,
            name = "Test Conflict Game",
            config = ConfigInfo(installDir = "654321"),
            type = AppType.game,
            osList = EnumSet.of(OS.windows),
            releaseState = ReleaseState.released,
            ufs = UFS(saveFilePatterns = patterns),
        )

        runBlocking {
            db.steamAppDao().insert(testApp)
        }

        mockSteamService = mock<SteamService>()
        whenever(mockSteamService.appDao).thenReturn(db.steamAppDao())
        whenever(mockSteamService.fileChangeListsDao).thenReturn(db.appFileChangeListsDao())
        whenever(mockSteamService.changeNumbersDao).thenReturn(db.appChangeNumbersDao())
        whenever(mockSteamService.db).thenReturn(db)

        val mockSteamClient = mock<`in`.dragonbra.javasteam.steam.steamclient.SteamClient>()
        val mockSteamID = mock<`in`.dragonbra.javasteam.types.SteamID>()
        whenever(mockSteamService.steamClient).thenReturn(mockSteamClient)
        whenever(mockSteamClient.steamID).thenReturn(mockSteamID)

        try {
            val instanceField = SteamService::class.java.getDeclaredField("instance")
            instanceField.isAccessible = true
            instanceField.set(null, mockSteamService)
        } catch (e: Exception) {
            fail("Failed to set SteamService.instance: ${e.message}")
        }

        mockSteamCloud = mockk<SteamCloud>(relaxed = true)
    }

    @After
    fun tearDown() {
        unmockkObject(Net)

        try {
            val imageFs = ImageFs.find(context)
            val imageFsRoot = imageFs.rootDir
            if (imageFsRoot.exists()) {
                imageFsRoot.deleteRecursively()
            }

            val instanceField = ImageFs::class.java.getDeclaredField("INSTANCE")
            instanceField.isAccessible = true
            instanceField.set(null, null)
        } catch (e: Exception) {
            // ignore — robolectric handles temp cleanup
        }

        try {
            tempDir.deleteRecursively()
        } catch (e: Exception) {
            // ignore
        }

        db.close()
        Thread.sleep(50)
    }

    // ── helpers ──

    private fun makePrefixToPath(): (String) -> String = { prefix ->
        val stripped = prefix.removePrefix("%").removeSuffix("%")
        when (stripped) {
            "WinMyDocuments" -> {
                val imageFs = ImageFs.find(context)
                File(imageFs.wineprefix, "dosdevices/c:/users/xuser/Documents").absolutePath
            }
            else -> tempDir.absolutePath
        }
    }

    private fun sha1(content: ByteArray): ByteArray =
        java.security.MessageDigest.getInstance("SHA-1").digest(content)

    private fun makeCloudFileChangeList(
        cloudChangeNumber: Long,
        files: List<AppFileInfo> = emptyList(),
        pathPrefixes: List<String> = emptyList(),
    ): AppFileChangeList {
        val mock = mock<AppFileChangeList>()
        whenever(mock.currentChangeNumber).thenReturn(cloudChangeNumber)
        whenever(mock.isOnlyDelta).thenReturn(false)
        whenever(mock.appBuildIDHwm).thenReturn(0)
        whenever(mock.pathPrefixes).thenReturn(pathPrefixes)
        whenever(mock.machineNames).thenReturn(emptyList())
        whenever(mock.files).thenReturn(files)
        return mock
    }

    private fun makeCloudFile(name: String, sha: ByteArray, sizeBytes: Int, prefixIndex: Int = 0): AppFileInfo {
        val m = mock<AppFileInfo>()
        whenever(m.filename).thenReturn(name)
        whenever(m.shaFile).thenReturn(sha)
        whenever(m.pathPrefixIndex).thenReturn(prefixIndex)
        whenever(m.timestamp).thenReturn(Date())
        whenever(m.rawFileSize).thenReturn(sizeBytes)
        return m
    }

    /** writes a single save file to disk and returns its (content, sha). */
    private fun writeSave(filename: String, content: ByteArray): Pair<ByteArray, ByteArray> {
        File(saveFilesDir, filename).writeBytes(content)
        return content to sha1(content)
    }

    private fun runSync(): app.gamenative.data.PostSyncInfo? = runBlocking {
        val testApp = db.steamAppDao().findApp(steamAppId)!!
        SteamAutoCloud.syncUserFiles(
            appInfo = testApp,
            clientId = clientId,
            steamInstance = mockSteamService,
            steamCloud = mockSteamCloud,
            preferredSave = SaveLocation.None,
            prefixToPath = makePrefixToPath(),
        ).await()
    }

    /** seeds the cloud-side AppFileChangeList so the prefix maps to the SaveGames dir
     *  and the single file's absolute path is byte-identical to the local file written.
     *  cloud CN advances past local CN so the L944-993 branch fires. */
    private fun stubCloudWithFile(cloudChangeNumber: Long, filename: String, sha: ByteArray, sizeBytes: Int) {
        val cloudFile = makeCloudFile(filename, sha, sizeBytes)
        val cloudFileChangeList = makeCloudFileChangeList(
            cloudChangeNumber = cloudChangeNumber,
            files = listOf(cloudFile),
            pathPrefixes = listOf("%WinMyDocuments%/My Games/TestGame/Steam/76561198025127569/SaveGames"),
        )
        every { mockSteamCloud.getAppFileListChange(any(), any(), any()) } returns
            CompletableFuture.completedFuture(cloudFileChangeList)
    }

    // ── tests ──

    // T1 (regression lock): cache absent (no FCL row, no CN row) + local SHA matches
    // remote SHA → silent rehydration. exercises the existing L976-986 hasUncachedLocalFiles
    // branch. MUST stay green pre- AND post-fix.
    @Test
    fun cacheAbsent_localMatchesRemoteSha_silentRehydrates() {
        val (content, sha) = writeSave("save.sav", "save content".toByteArray())
        // db starts empty (no insert). cloud CN = 5, local CN defaults to -1.
        stubCloudWithFile(cloudChangeNumber = 5, filename = "save.sav", sha = sha, sizeBytes = content.size)

        val result = runSync()
        assertNotNull("Result should not be null", result)
        assertEquals(
            "Cache absent + local SHA matches remote → silent rehydration to UpToDate",
            SyncResult.UpToDate,
            result!!.syncResult,
        )
    }

    // T2 (regression lock): cache absent + local SHA DIVERGES from remote → conflict.
    // exercises L987-992 hasLocalChanges=true + L1022 SaveLocation.None branch.
    // MUST stay green pre- AND .
    @Test
    fun cacheAbsent_localDivergesFromRemoteSha_firesConflict() {
        // local file: bytes A
        val (localContent, _) = writeSave("save.sav", "local-version-A".toByteArray())
        // cloud advertises a DIFFERENT SHA for the same filename
        val remoteSha = sha1("remote-version-B".toByteArray())
        stubCloudWithFile(cloudChangeNumber = 5, filename = "save.sav", sha = remoteSha, sizeBytes = localContent.size)

        val result = runSync()
        assertNotNull("Result should not be null", result)
        assertEquals(
            "Cache absent + local diverges from remote → must show conflict, not silently overwrite",
            SyncResult.Conflict,
            result!!.syncResult,
        )
    }

    // proves the fix: cache PRESENT but stale (drifted cloudPath encoding) + local SHA matches
    // remote → silent rehydration. pre-fix this hit SaveLocation.None because hasLocalChanges=true
    // (path-encoding drift in getFilesDiff) and the SHA cross-check was gated behind
    // hasUncachedLocalFiles. post-fix the lifted helper short-circuits to UpToDate.
    @Test
    fun cachePresent_localMatchesRemoteSha_silentRehydrates() {
        val (content, sha) = writeSave("save.sav", "save content".toByteArray())

        // forge a cache row whose prefixPath drifts vs. the live scan: live scan uses
        // cloudPath = pattern.uploadPath (= "My Games/TestGame/Steam/76561198025127569/SaveGames"),
        // so we use a DIFFERENT cloudPath here. SHA stays identical, only the path key drifts.
        // this is the exact "APK rebuild changed prefix encoding" race per
        runBlocking {
            db.appChangeNumbersDao().insert(ChangeNumbers(steamAppId, 4L))
            db.appFileChangeListsDao().insert(
                steamAppId,
                listOf(
                    UserFileInfo(
                        root = PathType.WinMyDocuments,
                        path = "My Games/TestGame/Steam/76561198025127569/SaveGames",
                        filename = "save.sav",
                        timestamp = 0L,
                        sha = sha,
                        cloudRoot = PathType.WinMyDocuments,
                        // DRIFT: cached cloudPath != live scan's cloudPath, so prefixPath diverges
                        // and getFilesDiff flags the file as both new+deleted. SHA still matches
                        // remote, so the lifted helper must short-circuit to UpToDate.
                        cloudPath = "DRIFTED/My Games/TestGame/Steam/76561198025127569/SaveGames",
                    ),
                ),
            )
        }

        // cloud CN advances past local CN (4 → 5) — enters L944-993 branch
        stubCloudWithFile(cloudChangeNumber = 5, filename = "save.sav", sha = sha, sizeBytes = content.size)

        val result = runSync()
        assertNotNull("Result should not be null", result)
        assertEquals(
            "Cache present + path drift + local SHA matches remote → silent rehydration (P3 fix locus)",
            SyncResult.UpToDate,
            result!!.syncResult,
        )
    }

    // T4 (NEW — pins anti-over-suppression): cache PRESENT + local SHA DIVERGES from remote
    // → conflict. proves the lifted helper does NOT swallow real conflicts. mitigates
    //-P3-04 in the threat model.
    @Test
    fun cachePresent_localDivergesFromRemoteSha_firesConflict() {
        // local file: bytes A (different from cached SHA)
        val (localContent, localSha) = writeSave("save.sav", "local-modified-A".toByteArray())
        // cached row records a DIFFERENT sha than what's on disk now (simulates user
        // played offline and modified saves). cache row's path is fine — drift is only
        // sha-side here, so getFilesDiff fires on modifiedFiles.
        val cachedSha = sha1("cached-state-original".toByteArray())
        runBlocking {
            db.appChangeNumbersDao().insert(ChangeNumbers(steamAppId, 4L))
            db.appFileChangeListsDao().insert(
                steamAppId,
                listOf(
                    UserFileInfo(
                        root = PathType.WinMyDocuments,
                        path = "My Games/TestGame/Steam/76561198025127569/SaveGames",
                        filename = "save.sav",
                        timestamp = 0L,
                        sha = cachedSha,
                        cloudRoot = PathType.WinMyDocuments,
                        cloudPath = "My Games/TestGame/Steam/76561198025127569/SaveGames",
                    ),
                ),
            )
        }

        // cloud advertises yet a THIRD sha (different device wrote new save). local sha
        // != remote sha → real divergence. cloud CN > local CN → L944-993 branch.
        val remoteSha = sha1("remote-version-from-other-device".toByteArray())
        // sanity: ensure all three differ
        assertEquals(false, localSha.contentEquals(remoteSha))
        assertEquals(false, localSha.contentEquals(cachedSha))
        stubCloudWithFile(cloudChangeNumber = 5, filename = "save.sav", sha = remoteSha, sizeBytes = localContent.size)

        val result = runSync()
        assertNotNull("Result should not be null", result)
        assertEquals(
            "Cache present + real local-vs-remote divergence → MUST still fire conflict (no over-suppression)",
            SyncResult.Conflict,
            result!!.syncResult,
        )
    }
}
