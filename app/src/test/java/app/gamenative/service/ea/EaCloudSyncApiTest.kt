package app.gamenative.service.ea

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class EaCloudSyncApiTest {
    private lateinit var server: MockWebServer
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        server = MockWebServer()
        server.start()
        EaCloudSyncApi.baseUrl = server.url("/").toString().trimEnd('/')
        mockkObject(EaAuthManager)
        coEvery { EaAuthManager.accessToken(any()) } returns "test-token"
        every { EaAuthManager.credentials(any()) } returns EaCredentials("test-token", "refresh", Long.MAX_VALUE, "1000123", "55", "Tester")
    }

    @After
    fun tearDown() {
        unmockkObject(EaAuthManager)
        EaCloudSyncApi.baseUrl = EaCloudSyncApi.BASE_URL
        server.shutdown()
    }

    private fun failure(block: suspend () -> Unit): EaCloudSyncException {
        try {
            runBlocking { block() }
        } catch (e: EaCloudSyncException) {
            return e
        }
        fail("expected EaCloudSyncException")
        throw AssertionError()
    }

    private fun lock(mode: EaCloudLockMode = EaCloudLockMode.READ, manifestPath: String = "/s3/manifest.xml?sig=a&exp=1") =
        EaCloudLock(mode, "1000123", "194908_sims4", "lock-value", server.url(manifestPath).toString())

    private val sampleManifest = """
        <?xml version="1.0" encoding="UTF-8"?>
        <manifest xmlns="http://origin.com/cloudsaves/manifest">
          <file href="1024-0cc175b9c0f1b6a831c399e269772661" size="1024" md5="abc123==">
            <localName>%Documents%\Electronic Arts\The Sims 4\saves\Slot_00000001.save</localName>
          </file>
          <file href="7-plain" size="7">
            <localName>%SavedGames%\Game\options.ini</localName>
          </file>
        </manifest>
    """.trimIndent()

    @Test
    fun `acquire reads lock header and manifest url`() = runBlocking {
        val manifestUrl = server.url("/s3/manifest.xml").toString() + "?X-Amz-Signature=abc&X-Amz-Expires=600"
        server.enqueue(
            MockResponse()
                .setHeader("X-Origin-Sync-Lock", "lock-abc")
                .setBody("<sync><host>h</host><root>r</root><manifest>${manifestUrl.replace("&", "&amp;")}</manifest></sync>"),
        )

        val lock = EaCloudSyncApi.acquire(context, "194908_sims4", EaCloudLockMode.WRITE)

        assertEquals(EaCloudLockMode.WRITE, lock.mode)
        assertEquals("1000123", lock.userId)
        assertEquals("194908_sims4", lock.cloudId)
        assertEquals("lock-abc", lock.lock)
        assertEquals(manifestUrl, lock.manifestUrl)
        assertFalse(lock.toString().contains("lock-abc"))
        assertFalse(lock.toString().contains("X-Amz-Signature"))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/lock/write/1000123/194908_sims4", request.path)
        assertEquals("test-token", request.getHeader("X-Origin-AuthToken"))
        assertEquals("0", request.getHeader("Content-Length"))
        assertEquals(0L, request.bodySize)
    }

    @Test
    fun `acquire uses the read path for read locks`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("x-origin-sync-lock", "l")
                .setBody("<sync><host/><root/><manifest>${server.url("/m")}</manifest></sync>"),
        )
        EaCloudSyncApi.acquire(context, "id", EaCloudLockMode.READ)
        assertEquals("/lock/read/1000123/id", server.takeRequest().path)
    }

    @Test
    fun `acquire without lock header throws`() {
        server.enqueue(MockResponse().setBody("<sync><host/><root/><manifest>${server.url("/m")}</manifest></sync>"))
        failure { EaCloudSyncApi.acquire(context, "id", EaCloudLockMode.READ) }
    }

    @Test
    fun `acquire http error throws with code`() {
        server.enqueue(MockResponse().setResponseCode(409).setBody("<error/>"))
        val e = failure { EaCloudSyncApi.acquire(context, "id", EaCloudLockMode.WRITE) }
        assertTrue(e.message!!.contains("409"))
    }

    @Test
    fun `acquire without manifest url throws`() {
        server.enqueue(MockResponse().setHeader("X-Origin-Sync-Lock", "l").setBody("<sync><host/><root/></sync>"))
        server.enqueue(MockResponse().setResponseCode(200))
        failure { EaCloudSyncApi.acquire(context, "id", EaCloudLockMode.READ) }
        server.takeRequest()
        val release = server.takeRequest()
        assertEquals("DELETE", release.method)
        assertEquals("/lock/delete/1000123", release.path)
        assertEquals("l", release.getHeader("X-Origin-Sync-Lock"))
    }

    @Test
    fun `acquire with garbage body releases the acquired lock`() {
        server.enqueue(MockResponse().setHeader("X-Origin-Sync-Lock", "held-lock").setBody("not <xml"))
        server.enqueue(MockResponse().setResponseCode(200))

        failure { EaCloudSyncApi.acquire(context, "id", EaCloudLockMode.WRITE) }

        assertEquals("/lock/write/1000123/id", server.takeRequest().path)
        val release = server.takeRequest()
        assertEquals("DELETE", release.method)
        assertEquals("/lock/delete/1000123", release.path)
        assertEquals("test-token", release.getHeader("X-Origin-AuthToken"))
        assertEquals("held-lock", release.getHeader("X-Origin-Sync-Lock"))
    }

    @Test
    fun `acquire still throws the original error when the release fails`() {
        server.enqueue(MockResponse().setHeader("X-Origin-Sync-Lock", "l").setBody("<sync/>"))
        server.enqueue(MockResponse().setResponseCode(500))
        val e = failure { EaCloudSyncApi.acquire(context, "id", EaCloudLockMode.READ) }
        assertTrue(e.message!!.contains("no manifest URL"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `acquire when signed out throws`() {
        every { EaAuthManager.credentials(any()) } returns null
        failure { EaCloudSyncApi.acquire(context, "id", EaCloudLockMode.READ) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `fetchManifest parses files and last modified`() = runBlocking {
        server.enqueue(MockResponse().setHeader("Last-Modified", "Wed, 21 Oct 2015 07:28:00 GMT").setBody(sampleManifest))

        val manifest = EaCloudSyncApi.fetchManifest(lock())

        assertTrue(manifest.exists)
        assertEquals(1445412480000L, manifest.lastModifiedMillis)
        assertEquals(
            listOf(
                EaCloudFile("1024-0cc175b9c0f1b6a831c399e269772661", 1024, "abc123==", "%Documents%\\Electronic Arts\\The Sims 4\\saves\\Slot_00000001.save"),
                EaCloudFile("7-plain", 7, null, "%SavedGames%\\Game\\options.ini"),
            ),
            manifest.files,
        )
        val request = server.takeRequest()
        assertEquals("/s3/manifest.xml?sig=a&exp=1", request.path)
        assertNull(request.getHeader("X-Origin-AuthToken"))
    }

    @Test
    fun `fetchManifest 404 means no manifest`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("<Error><Code>NoSuchKey</Code></Error>"))
        val manifest = EaCloudSyncApi.fetchManifest(lock())
        assertFalse(manifest.exists)
        assertTrue(manifest.files.isEmpty())
    }

    @Test
    fun `fetchManifest 403 NoSuchKey means no manifest`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("<Error><Code>NoSuchKey</Code></Error>"))
        assertFalse(EaCloudSyncApi.fetchManifest(lock()).exists)
    }

    @Test
    fun `fetchManifest 403 AccessDenied throws`() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("<Error><Code>AccessDenied</Code></Error>"))
        val e = failure { EaCloudSyncApi.fetchManifest(lock()) }
        assertTrue(e.message!!.contains("403"))
        assertTrue(e.message!!.contains("AccessDenied"))
    }

    @Test
    fun `fetchManifest empty body means no manifest`() = runBlocking {
        server.enqueue(MockResponse().setBody(""))
        assertFalse(EaCloudSyncApi.fetchManifest(lock()).exists)
    }

    @Test
    fun `fetchManifest 403 without S3 error body throws`() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden by proxy"))
        val e = failure { EaCloudSyncApi.fetchManifest(lock()) }
        assertTrue(e.message!!.contains("403"))
    }

    @Test
    fun `fetchManifest garbage body throws`() {
        server.enqueue(MockResponse().setBody("<html><body>captive portal</body></html>"))
        failure { EaCloudSyncApi.fetchManifest(lock()) }
        server.enqueue(MockResponse().setBody("not xml at all"))
        failure { EaCloudSyncApi.fetchManifest(lock()) }
        server.enqueue(MockResponse().setBody("<manifest><file href=\"a\" size=\"1\"><localName>x</localName>"))
        failure { EaCloudSyncApi.fetchManifest(lock()) }
    }

    @Test
    fun `fetchManifest 500 throws`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("<Error><Code>InternalError</Code></Error>"))
        val e = failure { EaCloudSyncApi.fetchManifest(lock()) }
        assertTrue(e.message!!.contains("500"))
    }

    @Test
    fun `fetchManifest network error throws`() {
        val lock = lock()
        server.shutdown()
        failure { EaCloudSyncApi.fetchManifest(lock) }
    }

    @Test
    fun `authorizeDownloads sends requests and maps by id`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                <authorizations>
                  <request id="1"><url>https://s3.example.com/b?sig=two&amp;x=1</url><headers/></request>
                  <request id="0"><url>https://s3.example.com/a?sig=one</url><headers><header key="x-amz-meta" value="v"/></headers></request>
                </authorizations>
                """.trimIndent(),
            ),
        )

        val result = EaCloudSyncApi.authorizeDownloads(context, lock(), listOf("href-a", "href-b"))

        assertEquals("https://s3.example.com/a?sig=one", result.getValue("href-a").url)
        assertEquals(listOf("x-amz-meta" to "v"), result.getValue("href-a").headers)
        assertEquals("https://s3.example.com/b?sig=two&x=1", result.getValue("href-b").url)
        assertTrue(result.getValue("href-b").headers.isEmpty())
        assertFalse(result.getValue("href-a").toString().contains("sig=one"))
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/lock/authorize/1000123", request.path)
        assertEquals("test-token", request.getHeader("X-Origin-AuthToken"))
        assertEquals("lock-value", request.getHeader("X-Origin-Sync-Lock"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/xml"))
        assertEquals(
            "<requests>" +
                "<request id=\"0\"><verb>GET</verb><resource>href-a</resource></request>" +
                "<request id=\"1\"><verb>GET</verb><resource>href-b</resource></request>" +
                "</requests>",
            request.body.readUtf8(),
        )
    }

    @Test
    fun `authorizeUploads sends md5 and content type`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                "<requests>" +
                    "<request id=\"0\"><url>https://s3.example.com/manifest?sig=m</url><headers><header key=\"Content-Type\" value=\"text/xml\"/></headers></request>" +
                    "<request id=\"1\"><url>https://s3.example.com/file?sig=f</url><headers><header key=\"Content-MD5\" value=\"bWQ1\"/></headers></request>" +
                    "</requests>",
            ),
        )

        val result = EaCloudSyncApi.authorizeUploads(
            context,
            lock(EaCloudLockMode.WRITE),
            listOf(EaCloudUpload("manifest.xml", null, "text/xml"), EaCloudUpload("12-abcdef", "bWQ1", null)),
        )

        assertEquals("https://s3.example.com/manifest?sig=m", result.getValue("manifest.xml").url)
        assertEquals(listOf("Content-MD5" to "bWQ1"), result.getValue("12-abcdef").headers)
        assertEquals(
            "<requests>" +
                "<request id=\"0\"><verb>PUT</verb><resource>manifest.xml</resource><content-type>text/xml</content-type></request>" +
                "<request id=\"1\"><verb>PUT</verb><resource>12-abcdef</resource><md5>bWQ1</md5></request>" +
                "</requests>",
            server.takeRequest().body.readUtf8(),
        )
    }

    @Test
    fun `authorize throws when an item has no url`() {
        server.enqueue(MockResponse().setBody("<requests><request id=\"0\"><url>https://s3.example.com/a</url><headers/></request></requests>"))
        failure { EaCloudSyncApi.authorizeDownloads(context, lock(), listOf("href-a", "href-b")) }
    }

    @Test
    fun `authorize http error throws with code`() {
        server.enqueue(MockResponse().setResponseCode(401))
        val e = failure { EaCloudSyncApi.authorizeDownloads(context, lock(), listOf("href-a")) }
        assertTrue(e.message!!.contains("401"))
    }

    @Test
    fun `authorize with nothing to authorize makes no request`() = runBlocking {
        assertTrue(EaCloudSyncApi.authorizeDownloads(context, lock(), emptyList()).isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `download writes temp file and returns size and md5`() = runBlocking {
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        server.enqueue(MockResponse().setBody(okio.Buffer().write(payload)))
        val temp = File(context.cacheDir, "ea-cloud-dl.tmp")

        val (size, md5) = EaCloudSyncApi.download(EaCloudAuthorizedUrl(server.url("/s3/file?sig=x").toString(), emptyList()), temp)

        assertEquals(payload.size.toLong(), size)
        assertArrayEquals(MessageDigest.getInstance("MD5").digest(payload), md5)
        assertArrayEquals(payload, temp.readBytes())
    }

    @Test
    fun `download non-2xx throws and leaves no temp file`() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("<Error><Code>SignatureDoesNotMatch</Code></Error>"))
        val temp = File(context.cacheDir, "ea-cloud-dl-fail.tmp")
        temp.writeText("stale")

        val e = failure { EaCloudSyncApi.download(EaCloudAuthorizedUrl(server.url("/s3/file?sig=secret").toString(), emptyList()), temp) }

        assertTrue(e.message!!.contains("403"))
        assertFalse(e.message!!.contains("secret"))
        assertFalse(temp.exists())
    }

    @Test
    fun `uploadBytes sends authorized headers and body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        val body = "<manifest/>".toByteArray()

        EaCloudSyncApi.uploadBytes(
            EaCloudAuthorizedUrl(server.url("/s3/manifest.xml?sig=x").toString(), listOf("Content-Type" to "text/xml", "x-amz-acl" to "private")),
            body,
        )

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("text/xml", request.getHeader("Content-Type"))
        assertEquals("private", request.getHeader("x-amz-acl"))
        assertEquals(body.size.toString(), request.getHeader("Content-Length"))
        assertArrayEquals(body, request.body.readByteArray())
    }

    @Test
    fun `uploadFile sends file content and non-2xx throws`() = runBlocking {
        val file = File(context.cacheDir, "ea-cloud-up.bin").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val target = EaCloudAuthorizedUrl(server.url("/s3/4-abc?sig=x").toString(), listOf("Content-MD5" to "bWQ1"))
        server.enqueue(MockResponse().setResponseCode(200))

        EaCloudSyncApi.uploadFile(target, file)

        val request = server.takeRequest()
        assertEquals("bWQ1", request.getHeader("Content-MD5"))
        assertEquals("4", request.getHeader("Content-Length"))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), request.body.readByteArray())

        server.enqueue(MockResponse().setResponseCode(400).setBody("<Error><Code>BadDigest</Code></Error>"))
        val e = failure { EaCloudSyncApi.uploadFile(target, file) }
        assertTrue(e.message!!.contains("400"))
    }

    @Test
    fun `manifestXml round-trips through parseManifest`() {
        val files = listOf(
            EaCloudFile("10-aa\"bb", 10, "md5<&>'\"", "%Documents%\\Tom & Jerry's <Saves>\\セーブ données №1.sav"),
            EaCloudFile("0-empty", 0, null, "%SavedGames%\\Ünïcödé\\slot \"2\".dat"),
        )

        val xml = EaCloudSyncApi.manifestXml(files)

        assertTrue(xml.startsWith("<manifest xmlns=\"http://origin.com/cloudsaves/manifest\">"))
        assertTrue(xml.contains("Tom &amp; Jerry&apos;s &lt;Saves&gt;"))
        assertFalse(xml.contains("<file href=\"0-empty\" size=\"0\" md5"))
        assertEquals(files, EaCloudSyncApi.parseManifest(xml))
        assertTrue(EaCloudSyncApi.parseManifest(EaCloudSyncApi.manifestXml(emptyList())).isEmpty())
    }

    @Test
    fun `parseManifest rejects a non-manifest root`() {
        try {
            EaCloudSyncApi.parseManifest("<Error><Code>NoSuchKey</Code></Error>")
            fail("expected EaCloudSyncException")
        } catch (_: EaCloudSyncException) {
        }
    }

    @Test
    fun `parseManifest rejects a DOCTYPE with an external entity`() {
        val secret = File.createTempFile("ea-xxe", ".txt").apply { writeText("SECRET") }
        val xml = """
            <?xml version="1.0"?>
            <!DOCTYPE manifest [<!ENTITY xxe SYSTEM "${secret.toURI()}">]>
            <manifest xmlns="http://origin.com/cloudsaves/manifest">
              <file href="1-a" size="1"><localName>&xxe;</localName></file>
            </manifest>
        """.trimIndent()
        try {
            EaCloudSyncApi.parseManifest(xml)
            fail("expected EaCloudSyncException")
        } catch (e: EaCloudSyncException) {
            assertFalse(e.message!!.contains("SECRET"))
        } finally {
            secret.delete()
        }
        assertEquals(2, EaCloudSyncApi.parseManifest(sampleManifest).size)
    }

    @Test
    fun `release sends both headers`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))

        assertTrue(EaCloudSyncApi.release(context, lock()))

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/lock/delete/1000123", request.path)
        assertEquals("test-token", request.getHeader("X-Origin-AuthToken"))
        assertEquals("lock-value", request.getHeader("X-Origin-Sync-Lock"))
    }

    @Test
    fun `release swallows errors`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        assertFalse(EaCloudSyncApi.release(context, lock()))

        coEvery { EaAuthManager.accessToken(any()) } throws IllegalStateException("Not signed in to EA")
        assertFalse(EaCloudSyncApi.release(context, lock()))

        coEvery { EaAuthManager.accessToken(any()) } returns "test-token"
        val lock = lock()
        server.shutdown()
        assertFalse(EaCloudSyncApi.release(context, lock))
    }
}
