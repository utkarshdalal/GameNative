package app.gamenative.html5.savesync

import app.gamenative.PrefManager
import app.gamenative.service.SteamService
import app.gamenative.utils.SteamUtils
import `in`.dragonbra.javasteam.enums.EOSType
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.protobufs.steamclient.Enums
import `in`.dragonbra.javasteam.steam.handlers.steamcloud.AppFileChangeList
import `in`.dragonbra.javasteam.steam.handlers.steamcloud.AppFileInfo
import `in`.dragonbra.javasteam.steam.handlers.steamcloud.AppUploadBatchResponse
import `in`.dragonbra.javasteam.steam.handlers.steamcloud.FileDownloadInfo
import `in`.dragonbra.javasteam.steam.handlers.steamcloud.FileUploadBlockDetails
import `in`.dragonbra.javasteam.steam.handlers.steamcloud.FileUploadInfo
import `in`.dragonbra.javasteam.steam.handlers.steamcloud.SteamCloud
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.steam.steamclient.configuration.SteamConfiguration
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
import java.util.Date
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// MockWebServer stands in for Steam's CDN; mockk stubs the SteamCloud handler.
// robolectric because getQuotaJson uses runBlocking + Android's PrefManager.

// CRITICAL: SteamCloud defaults its trailing params (incl. the CoroutineScope), but Kotlin call sites
// compile to the FULL N-arg method even with @JvmOverloads. stubs must match every arg with `any()`, and
// use `every`, not `coEvery` -- these return CompletableFuture, they aren't suspend funs.
@RunWith(RobolectricTestRunner::class)
class GreenworksCloudClientTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var steamCloud: SteamCloud
    private lateinit var steamInstance: SteamService

    private val sharedHttpClient = OkHttpClient()

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        // DataStore-backed, so mock the getter
        mockkObject(PrefManager)
        every { PrefManager.clientId } returns 1234567890L

        // explicit per-link stubs: chained `every` on relaxed mocks routes through OkHttpClient
        // sub-mocks that hang HTTP execute.
        mockkObject(SteamService.Companion)
        steamInstance = mockk(relaxed = true)
        val steamClient = mockk<SteamClient>(relaxed = true)
        val configuration = mockk<SteamConfiguration>(relaxed = true)
        every { SteamService.instance } returns steamInstance
        every { steamInstance.steamClient } returns steamClient
        every { steamClient.configuration } returns configuration
        every { configuration.httpClient } returns sharedHttpClient

        steamCloud = mockk(relaxed = true)
        every { steamInstance.steamCloudHandler() } returns steamCloud

        mockkObject(SteamUtils)
        every { SteamUtils.getMachineName(any()) } returns "test-device"
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        unmockkAll()
    }

    @Test
    fun upload_emptyList_returnsTrivialSuccess() {
        val result = runBlocking { GreenworksCloudClient.upload(1454400, emptyList()) }
        assertTrue("empty upload should be a trivial success", result.success)
        assertEquals(0, result.filesUploaded)
        assertEquals(0L, result.bytesUploaded)
        verify(exactly = 0) {
            steamCloud.signalAppLaunchIntent(any(), any(), any(), any(), any(), any(), any())
        }
        verify(exactly = 0) {
            steamCloud.beginAppUploadBatch(any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun upload_offlineSkipsGracefully() {
        every { SteamService.instance } returns null
        val result = runBlocking {
            GreenworksCloudClient.upload(1454400, listOf("save.txt" to "abc".toByteArray()))
        }
        assertFalse("offline upload should not claim success", result.success)
        assertEquals(0, result.filesUploaded)
        assertEquals(0L, result.bytesUploaded)
        verify(exactly = 0) {
            steamCloud.signalAppLaunchIntent(any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun upload_invalidFilenameAborts() {
        val result = runBlocking {
            GreenworksCloudClient.upload(
                1454400,
                listOf("../etc/passwd" to "abc".toByteArray()),
            )
        }
        assertFalse("invalid filename must abort the batch", result.success)
        assertEquals(0, result.filesUploaded)
        verify(exactly = 0) {
            steamCloud.signalAppLaunchIntent(any(), any(), any(), any(), any(), any(), any())
        }
        verify(exactly = 0) {
            steamCloud.beginAppUploadBatch(any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun upload_callsBatchProtocolInOrder() {
        val payload = ByteArray(100) { it.toByte() }

        // (appId, clientId, machineName, ignorePendingOps, osType, deviceType, parentScope)
        every {
            steamCloud.signalAppLaunchIntent(any(), any(), any(), any(), any(), any(), any())
        } returns CompletableFuture.completedFuture(emptyList())

        // (appId, machineName, filesToUpload, filesToDelete, clientId, appBuildId, parentScope)
        val batch = mockk<AppUploadBatchResponse> {
            every { batchID } returns 42L
        }
        every {
            steamCloud.beginAppUploadBatch(any(), any(), any(), any(), any(), any(), any())
        } returns CompletableFuture.completedFuture(batch)

        // (appId, fileSize, rawFileSize, fileSha, timestamp, filename, platformsToSync, cellId,
        // canEncrypt, isSharedFile, deprecatedRealm, uploadBatchId, parentScope)
        val block = mockk<FileUploadBlockDetails> {
            every { useHttps } returns false
            every { urlHost } returns "${mockWebServer.hostName}:${mockWebServer.port}"
            every { urlPath } returns "/upload/test"
            every { blockOffset } returns 0L
            every { blockLength } returns 100
            every { requestHeaders } returns emptyList()
        }
        val uploadInfo = mockk<FileUploadInfo> {
            every { blockRequests } returns listOf(block)
        }
        every {
            steamCloud.beginFileUpload(
                any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(),
                any(), any(), any(),
            )
        } returns CompletableFuture.completedFuture(uploadInfo)

        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        // (transferSucceeded, appId, fileSha, filename, parentScope)
        every {
            steamCloud.commitFileUpload(any(), any(), any(), any(), any())
        } returns CompletableFuture.completedFuture(true)
        // (appId, batchId, batchEResult, parentScope)
        every {
            steamCloud.completeAppUploadBatch(any(), any(), any(), any())
        } returns CompletableFuture.completedFuture(Unit)
        // signalAppExitSyncDone is void -- the relaxed mock no-ops it.

        val result = runBlocking {
            GreenworksCloudClient.upload(1454400, listOf("save.txt" to payload))
        }

        assertTrue("happy path should report success: $result", result.success)
        assertEquals(1, result.filesUploaded)
        assertEquals(100L, result.bytesUploaded)

        // the exact RPC sequence IS the upload protocol contract
        verifyOrder {
            steamCloud.signalAppLaunchIntent(any(), any(), any(), any(), any(), any(), any())
            steamCloud.beginAppUploadBatch(any(), any(), any(), any(), any(), any(), any())
            steamCloud.beginFileUpload(
                any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(),
                any(), any(), any(),
            )
            steamCloud.commitFileUpload(any(), any(), any(), any(), any())
            steamCloud.completeAppUploadBatch(any(), any(), any(), any())
            steamCloud.signalAppExitSyncDone(any(), any(), any(), any())
        }

        val recorded: RecordedRequest? = mockWebServer.takeRequest(2_000, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertNotNull("expected exactly one PUT to MockWebServer", recorded)
        assertEquals("PUT", recorded?.method)
        assertEquals("/upload/test", recorded?.path)
        assertEquals(100, recorded?.body?.size?.toInt())
    }

    @Test
    fun upload_shaDedup_skipsCommit() {
        // empty blockRequests = Steam already has these bytes
        every {
            steamCloud.signalAppLaunchIntent(any(), any(), any(), any(), any(), any(), any())
        } returns CompletableFuture.completedFuture(emptyList())
        val batch = mockk<AppUploadBatchResponse> { every { batchID } returns 7L }
        every {
            steamCloud.beginAppUploadBatch(any(), any(), any(), any(), any(), any(), any())
        } returns CompletableFuture.completedFuture(batch)
        val dedupedInfo = mockk<FileUploadInfo> { every { blockRequests } returns emptyList() }
        every {
            steamCloud.beginFileUpload(
                any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(),
                any(), any(), any(),
            )
        } returns CompletableFuture.completedFuture(dedupedInfo)
        every {
            steamCloud.completeAppUploadBatch(any(), any(), any(), any())
        } returns CompletableFuture.completedFuture(Unit)

        val result = runBlocking {
            GreenworksCloudClient.upload(1454400, listOf("save.txt" to "deduped".toByteArray()))
        }

        // deduped file still counts as uploaded
        assertTrue(result.success)
        assertEquals(1, result.filesUploaded)
        assertEquals(0, mockWebServer.requestCount)
        verify(exactly = 0) {
            steamCloud.commitFileUpload(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun download_emptyManifest_noHttpGets() {
        val emptyChangeList = mockk<AppFileChangeList> {
            every { files } returns emptyList()
        }
        // (appId, syncedChangeNumber, parentScope)
        every {
            steamCloud.getAppFileListChange(any(), any(), any())
        } returns CompletableFuture.completedFuture(emptyChangeList)

        val downloaded = runBlocking { GreenworksCloudClient.download(1454400) }
        assertTrue("empty manifest should produce empty result", downloaded.isEmpty())
        assertEquals(0, mockWebServer.requestCount)
        // (appId, fileName, realm, forceProxy, parentScope)
        verify(exactly = 0) {
            steamCloud.clientFileDownload(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun download_singleFile_returnsBytes() {
        val aliveEntry = mockk<AppFileInfo> {
            every { filename } returns "save.txt"
            every { persistState } returns Enums.ECloudStoragePersistState.k_ECloudStoragePersistStatePersisted
            every { rawFileSize } returns 7
        }
        val changeList = mockk<AppFileChangeList> {
            every { files } returns listOf(aliveEntry)
        }
        every {
            steamCloud.getAppFileListChange(any(), any(), any())
        } returns CompletableFuture.completedFuture(changeList)
        val dlInfo = mockk<FileDownloadInfo> {
            every { useHttps } returns false
            every { urlHost } returns "${mockWebServer.hostName}:${mockWebServer.port}"
            every { urlPath } returns "/dl/save.txt"
            every { requestHeaders } returns emptyList()
        }
        every {
            steamCloud.clientFileDownload(any(), any(), any(), any(), any())
        } returns CompletableFuture.completedFuture(dlInfo)
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("abc1234"))

        val downloaded = runBlocking { GreenworksCloudClient.download(1454400) }

        assertEquals(1, downloaded.size)
        assertEquals("save.txt", downloaded[0].first)
        assertEquals("abc1234", String(downloaded[0].second))
    }

    @Test
    fun download_handlesUnavailable_throws() {
        // must throw, not return empty: the caller's outbound then short-circuits instead of
        // overwriting cloud with empty WebView state.
        every { PrefManager.clientId } returns null
        try {
            runBlocking { GreenworksCloudClient.download(1454400) }
            fail("expected download to throw when handles unavailable")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("handles unavailable") == true)
        }
    }

    @Test
    fun download_httpFailure_throws() {
        val aliveEntry = mockk<AppFileInfo> {
            every { filename } returns "save.txt"
            every { persistState } returns Enums.ECloudStoragePersistState.k_ECloudStoragePersistStatePersisted
            every { rawFileSize } returns 7
        }
        every {
            steamCloud.getAppFileListChange(any(), any(), any())
        } returns CompletableFuture.completedFuture(
            mockk<AppFileChangeList> { every { files } returns listOf(aliveEntry) },
        )
        val dlInfo = mockk<FileDownloadInfo> {
            every { useHttps } returns false
            every { urlHost } returns "${mockWebServer.hostName}:${mockWebServer.port}"
            every { urlPath } returns "/dl/save.txt"
            every { requestHeaders } returns emptyList()
        }
        every {
            steamCloud.clientFileDownload(any(), any(), any(), any(), any())
        } returns CompletableFuture.completedFuture(dlInfo)
        mockWebServer.enqueue(MockResponse().setResponseCode(503))
        try {
            runBlocking { GreenworksCloudClient.download(1454400) }
            fail("expected download to throw on HTTP failure")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("HTTP 503") == true)
        }
    }

    @Test
    fun download_skipsTombstone() {
        val tombstone = mockk<AppFileInfo> {
            every { filename } returns "deleted.txt"
            every { persistState } returns Enums.ECloudStoragePersistState.k_ECloudStoragePersistStateForgotten
            every { rawFileSize } returns 0
        }
        val changeList = mockk<AppFileChangeList> {
            every { files } returns listOf(tombstone)
        }
        every {
            steamCloud.getAppFileListChange(any(), any(), any())
        } returns CompletableFuture.completedFuture(changeList)

        val downloaded = runBlocking { GreenworksCloudClient.download(1454400) }
        assertTrue("tombstone should be skipped", downloaded.isEmpty())
        verify(exactly = 0) {
            steamCloud.clientFileDownload(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun getQuotaJson_sumsRawFileSize() {
        val sizes = listOf(100, 200, 300)
        val entries = sizes.map { sz ->
            mockk<AppFileInfo> {
                every { filename } returns "f$sz.txt"
                every { persistState } returns Enums.ECloudStoragePersistState.k_ECloudStoragePersistStatePersisted
                every { rawFileSize } returns sz
            }
        }
        val changeList = mockk<AppFileChangeList> {
            every { files } returns entries
        }
        every {
            steamCloud.getAppFileListChange(any(), any(), any())
        } returns CompletableFuture.completedFuture(changeList)

        val raw = GreenworksCloudClient.getQuotaJson(1454400)
        val parsed = JSONObject(raw)
        assertEquals(104_857_600L, parsed.getLong("total"))
        assertEquals(104_857_600L - 600L, parsed.getLong("available"))
    }

    @Test
    fun getQuotaJson_offlineFallback() {
        every { SteamService.instance } returns null
        val raw = GreenworksCloudClient.getQuotaJson(1454400)
        val parsed = JSONObject(raw)
        assertEquals(104_857_600L, parsed.getLong("total"))
        // offline: no manifest to subtract from, so available == total
        assertEquals(104_857_600L, parsed.getLong("available"))
    }

    // keeps the imports that document the EOSType / EResult / Date shapes the SteamCloud signatures
    // use, even though `any()` matchers cover them.
    @Suppress("unused")
    private val unused = listOf(EOSType.AndroidUnknown, EResult.OK, Date())
}
