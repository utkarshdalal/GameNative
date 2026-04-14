package app.gamenative.service.gog

import android.content.Context
import app.gamenative.data.DownloadInfo
import app.gamenative.data.GOGGame
import app.gamenative.service.gog.api.BuildsResponse
import app.gamenative.service.gog.api.Depot
import app.gamenative.service.gog.api.DepotFile
import app.gamenative.service.gog.api.DepotManifest
import app.gamenative.service.gog.api.GOGApiClient
import app.gamenative.service.gog.api.GOGBuild
import app.gamenative.service.gog.api.GOGManifestMeta
import app.gamenative.service.gog.api.GOGManifestParser
import app.gamenative.service.gog.api.Product
import app.gamenative.service.gog.api.SecureLinksResponse
import app.gamenative.service.gog.api.V1DepotFile
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = android.app.Application::class)
class GOGDownloadManagerTest {
    private lateinit var apiClient: GOGApiClient
    private lateinit var parser: GOGManifestParser
    private lateinit var gogManager: GOGManager
    private lateinit var context: Context
    private lateinit var manager: GOGDownloadManager

    @Before
    fun setUp() {
        apiClient = mock()
        parser = mock()
        gogManager = mock()
        context = mock()
        manager = GOGDownloadManager(apiClient, parser, gogManager, context)
    }

    // ===== Gen 2 =====

    @Test
    fun gen2_download_includes_game_and_support_files() = runTest {
        val gameId = "12345"
        val installPath = Files.createTempDirectory("gog-gen2-install").toFile()
        val downloadInfo = DownloadInfo(
            jobCount = 1,
            gameId = gameId.toInt(),
            downloadingAppIds = CopyOnWriteArrayList(),
        )

        val selectedBuild = GOGBuild(
            buildId = "build-1",
            productId = gameId,
            platform = "windows",
            generation = 2,
            versionName = "1.0.0",
            branch = "master",
            link = "https://manifest.test",
            legacyBuildId = null,
        )

        val depot = Depot(
            productId = gameId,
            languages = listOf("en-US"),
            manifest = "depot-manifest",
            compressedSize = 0L,
            size = 0L,
            osBitness = emptyList(),
        )

        val gameFile = depotFile(path = "bin/game.exe", support = false)
        val supportFile = depotFile(path = "app/support/vcredist.exe", support = true)

        val manifest = GOGManifestMeta(
            baseProductId = gameId,
            installDirectory = "Game",
            depots = listOf(depot),
            dependencies = emptyList(),
            products = listOf(Product(productId = gameId, name = "Game")),
            productTimestamp = null,
            scriptInterpreter = false,
        )

        whenever(gogManager.getGameFromDbById(gameId)).thenReturn(
            GOGGame(id = gameId, title = "Test Game"),
            GOGGame(id = gameId, title = "Test Game"),
        )
        whenever(gogManager.getAllGameIds()).thenReturn(setOf(gameId))
        whenever(apiClient.getBuildsForGame(gameId, "windows", 2)).thenReturn(
            Result.success(BuildsResponse(totalCount = 1, count = 1, items = listOf(selectedBuild))),
        )
        whenever(parser.selectBuild(any(), eq(2), eq("windows"))).thenReturn(selectedBuild)
        whenever(apiClient.fetchManifest(selectedBuild.link)).thenReturn(Result.success(manifest))
        whenever(parser.filterDepotsByLanguage(manifest, "english")).thenReturn(listOf(depot) to "en-US")
        whenever(parser.filterDepotsByOwnership(listOf(depot), setOf(gameId))).thenReturn(listOf(depot))
        whenever(apiClient.fetchDepotManifest(depot.manifest)).thenReturn(
            Result.success(DepotManifest(files = listOf(gameFile, supportFile), directories = emptyList(), links = emptyList())),
        )
        whenever(parser.separateBaseDLC(listOf(gameFile, supportFile), gameId)).thenReturn(
            listOf(gameFile, supportFile) to emptyList(),
        )
        whenever(parser.separateSupportFiles(listOf(gameFile, supportFile))).thenReturn(
            listOf(gameFile) to listOf(supportFile),
        )
        whenever(parser.calculateTotalSize(any())).thenReturn(0L)
        whenever(parser.extractChunkHashes(any())).thenReturn(emptyList())
        whenever(parser.buildChunkUrlMapWithProducts(any(), any(), any())).thenReturn(emptyMap())

        val result = manager.downloadGame(
            gameId = gameId,
            installPath = installPath,
            downloadInfo = downloadInfo,
            language = "english",
            withDlcs = false,
            supportDir = null,
        )

        assertTrue(result.isSuccess)

        val extractedHashesCaptor = argumentCaptor<List<DepotFile>>()
        verify(parser).extractChunkHashes(extractedHashesCaptor.capture())
        val hashedPaths = extractedHashesCaptor.firstValue.map { it.path }
        assertTrue(hashedPaths.contains("bin/game.exe"))
        assertTrue(hashedPaths.contains("app/support/vcredist.exe"))

        val totalSizeCaptor = argumentCaptor<List<DepotFile>>()
        verify(parser, atLeastOnce()).calculateTotalSize(totalSizeCaptor.capture())
        val capturedSizeInputs = totalSizeCaptor.allValues.map { files -> files.map { it.path } }
        assertTrue(capturedSizeInputs.any { it.contains("bin/game.exe") && it.contains("app/support/vcredist.exe") })

        assertTrue(File(installPath, "bin/game.exe").exists())
        assertTrue(File(installPath, "support/vcredist.exe").exists())

        installPath.deleteRecursively()
    }

    // ===== Gen 1 =====

    @Test
    fun gen1_download_includes_game_and_support_files() = runTest {
        val gameId = "12345"
        val installPath = Files.createTempDirectory("gog-gen1-install").toFile()
        val supportDir = Files.createTempDirectory("gog-gen1-support").toFile()
        val downloadInfo = DownloadInfo(
            jobCount = 1,
            gameId = gameId.toInt(),
            downloadingAppIds = CopyOnWriteArrayList(),
        )

        val gen1Build = GOGBuild(
            buildId = "legacy-build",
            productId = gameId,
            platform = "windows",
            generation = 1,
            versionName = "1.0.0",
            branch = "master",
            link = "https://manifest.test",
            legacyBuildId = null,
        )

        val depot = Depot(
            productId = gameId,
            languages = listOf("en-US"),
            manifest = "legacy-manifest",
            compressedSize = 0L,
            size = 0L,
            osBitness = emptyList(),
        )

        val manifest = GOGManifestMeta(
            baseProductId = gameId,
            installDirectory = "Game",
            depots = listOf(depot),
            dependencies = emptyList(),
            products = listOf(Product(productId = gameId, name = "Game")),
            productTimestamp = "111111",
            scriptInterpreter = false,
        )

        val gameV1File = V1DepotFile(
            path = "bin/game.exe",
            size = 0L,
            hash = "",
            url = null,
            offset = null,
            isSupport = false,
        )
        val supportV1File = V1DepotFile(
            path = "__redist/vcredist.exe",
            size = 0L,
            hash = "",
            url = null,
            offset = null,
            isSupport = true,
        )

        whenever(gogManager.getGameFromDbById(gameId)).thenReturn(
            GOGGame(id = gameId, title = "Test Game"),
            GOGGame(id = gameId, title = "Test Game"),
        )
        whenever(gogManager.getAllGameIds()).thenReturn(setOf(gameId))
        whenever(apiClient.getBuildsForGame(gameId, "windows", 2)).thenReturn(
            Result.success(BuildsResponse(totalCount = 0, count = 0, items = emptyList())),
        )
        whenever(apiClient.getBuildsForGame(gameId, "windows", 1)).thenReturn(
            Result.success(BuildsResponse(totalCount = 1, count = 1, items = listOf(gen1Build))),
        )
        whenever(parser.selectBuild(any(), eq(2), eq("windows"))).thenReturn(null)
        whenever(parser.selectBuild(any(), eq(1), eq("windows"))).thenReturn(gen1Build)
        whenever(apiClient.fetchManifest(gen1Build.link)).thenReturn(Result.success(manifest))
        whenever(parser.filterDepotsByLanguage(manifest, "english")).thenReturn(listOf(depot) to "en-US")
        whenever(parser.filterDepotsByOwnership(listOf(depot), setOf(gameId))).thenReturn(listOf(depot))
        whenever(
            apiClient.getSecureLink(
                productId = gameId,
                path = "/windows/111111/",
                generation = 1,
            ),
        ).thenReturn(Result.success(SecureLinksResponse(urls = listOf("https://cdn.example.com"))))
        whenever(
            apiClient.fetchDepotManifestV1(
                productId = gameId,
                platform = "windows",
                timestamp = "111111",
                manifestHash = "legacy-manifest",
            ),
        ).thenReturn(Result.success("{}"))
        whenever(parser.parseV1DepotManifest("{}")).thenReturn(listOf(gameV1File, supportV1File))

        val result = manager.downloadGame(
            gameId = gameId,
            installPath = installPath,
            downloadInfo = downloadInfo,
            language = "english",
            withDlcs = false,
            supportDir = supportDir,
        )

        assertTrue(result.isSuccess)
        assertTrue(File(installPath, "bin/game.exe").exists())
        assertTrue(File(supportDir, "__redist/vcredist.exe").exists())

        installPath.deleteRecursively()
        supportDir.deleteRecursively()
    }

    @Test
    fun buildGen1MainBinUrl_insertsPathBeforeQueryToken() {
        val baseUrl = "https://cdn.gog.com/store/1451150270?__token__=exp=123~acl=*"

        val result = manager.buildGen1MainBinUrl(baseUrl)

        assertEquals(
            "https://cdn.gog.com/store/1451150270/main.bin?__token__=exp=123~acl=*",
            result,
        )
    }

    @Test
    fun buildGen1MainBinUrl_appendsPathWithoutQuery() {
        val result = manager.buildGen1MainBinUrl("https://cdn.gog.com/store/1451150270/")

        assertEquals("https://cdn.gog.com/store/1451150270/main.bin", result)
    }

    @Test
    fun buildGen1MainBinUrl_throwsWhenBaseUrlIsNull() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            manager.buildGen1MainBinUrl(null)
        }

        assertTrue(exception.message?.contains("Missing Gen 1 secure link URL") == true)
    }

    @Test
    fun buildGen1MainBinUrl_throwsWhenBaseUrlIsBlank() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            manager.buildGen1MainBinUrl("   ")
        }

        assertTrue(exception.message?.contains("Missing Gen 1 secure link URL") == true)
    }

    private fun depotFile(path: String, support: Boolean): DepotFile {
        val flags = if (support) listOf("support") else emptyList()
        return DepotFile(
            path = path,
            chunks = emptyList(),
            md5 = null,
            sha256 = null,
            flags = flags,
            productId = null,
        )
    }
}
