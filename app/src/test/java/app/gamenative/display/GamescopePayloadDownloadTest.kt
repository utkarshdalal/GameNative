package app.gamenative.display

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gamenative.service.SteamService
import app.gamenative.utils.AssetUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Whether the gamescope payload the app fetches on its first gamescope launch really is on the
 * servers it fetches it from.
 *
 * The two hosts mirror `SteamService.fetchFileWithFallback`, and the file name comes from
 * [GamescopePayload.ARCHIVE] — so bumping [GamescopePayload.VERSION] without publishing that
 * archive fails here instead of on a user's first launch. A missing archive, a wrong body or a
 * host that stopped mirroring it is a failure; no network at all is a skip.
 */
@RunWith(RobolectricTestRunner::class)
class GamescopePayloadDownloadTest {

    @Test
    fun theArchiveIsPublishedOnBothHosts() {
        assumeNetwork()

        val responses = HOSTS.map { host -> host to head("$host/${GamescopePayload.ARCHIVE}") }

        responses.forEach { (host, rsp) ->
            assertEquals("$host did not serve ${GamescopePayload.ARCHIVE}", 200, rsp.code)
            assertTrue(
                "$host served a ${rsp.contentType} (${rsp.length} B), not the archive",
                rsp.length >= MIN_ARCHIVE_BYTES,
            )
        }
        assertEquals(
            "the two hosts serve different archives",
            responses[0].second.length,
            responses[1].second.length,
        )

        HOSTS.forEach { host ->
            val magic = firstBytes("$host/${GamescopePayload.ARCHIVE}", ZSTD_MAGIC.size)
            assertTrue(
                "$host did not serve a zstd frame (first bytes ${magic.toHex()})",
                magic.contentEquals(ZSTD_MAGIC),
            )
        }
    }

    @Test
    fun theArchiveDownloadsAndInstallsFromTheServer() {
        assumeNetwork()

        val context: Context = ApplicationProvider.getApplicationContext()
        val archive = File(context.filesDir, GamescopePayload.ARCHIVE)
        archive.delete()
        AssetUtils.deleteTree(GamescopeSettings.gamescopeDir(context.filesDir))

        // Downloaded through the app's own fetch, so a host that 404s, truncates or serves a page
        // instead of the archive fails here; not via the APK asset, which would hide that.
        val progress = mutableListOf<Float>()
        runBlocking {
            SteamService.fetchFile("${HOSTS[0]}/${GamescopePayload.ARCHIVE}", archive) { progress.add(it) }
        }

        val published = head("${HOSTS[0]}/${GamescopePayload.ARCHIVE}").length
        assertEquals("the download is not the published archive", published, archive.length())
        assertTrue("the download reported no progress", progress.isNotEmpty())
        assertTrue("the download never completed", progress.max() > 0.99f)

        val installed = GamescopePayload.install(archive, context.filesDir)

        assertEquals(GamescopeSettings.gamescopeDir(context.filesDir), installed)
        assertTrue(
            "the payload carries no bin/gamescope",
            File(installed, "bin/gamescope").isFile,
        )
        assertEquals(
            GamescopePayload.VERSION,
            File(installed, ".payload.version").readText().trim(),
        )
        assertTrue(GamescopePayload.isReady(context.filesDir))
        assertFalse(
            "the archive should not outlive the install",
            File(context.filesDir, GamescopePayload.ARCHIVE).exists(),
        )
    }

    @Test
    fun anInstalledPayloadIsNotDownloadedAgain() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val dir = GamescopeSettings.gamescopeDir(context.filesDir)
        AssetUtils.deleteTree(dir)
        // An installed payload: the stamp and the directory the readiness check reads.
        File(dir, "bin").mkdirs()
        File(dir, ".payload.version").writeText(GamescopePayload.VERSION)

        val progress = mutableListOf<Float>()
        val installed = runBlocking { GamescopePayload.ensure(context) { progress.add(it) } }

        assertEquals(dir, installed)
        assertTrue("a ready payload must not report download progress", progress.isEmpty())
        assertFalse(File(context.filesDir, GamescopePayload.ARCHIVE).exists())
    }

    /// The response of a `HEAD`, with the length the archive claims and the type it was served as.
    private data class Head(val code: Int, val length: Long, val contentType: String)

    private fun head(url: String): Head {
        val connection = open(url, "HEAD")
        try {
            return Head(
                code = connection.responseCode,
                length = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L,
                contentType = connection.contentType.orEmpty(),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun firstBytes(url: String, count: Int): ByteArray {
        val connection = open(url, "GET", ranged = true)
        try {
            assertEquals("$url did not serve a range", 206, connection.responseCode)
            return connection.inputStream.readNBytes(count)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, method: String, ranged: Boolean = false): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            if (ranged) setRequestProperty("Range", "bytes=0-${ZSTD_MAGIC.size - 1}")
            instanceFollowRedirects = true
        }

    /// Skips the test on a machine with no network, where the check would say nothing.
    private fun assumeNetwork() {
        val reachable = runCatching { head("${HOSTS[0]}/${GamescopePayload.ARCHIVE}").code }.isSuccess
        assumeTrue("No network: the published payload cannot be checked", reachable)
    }

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it) }

    private companion object {
        val HOSTS = listOf(
            "https://downloads.gamenative.app",
            "https://pub-9fcd5294bd0d4b85a9d73615bf98f3b5.r2.dev",
        )
        /// A zstd frame header: the archive is a `.tzst`, and an error page never starts with it.
        val ZSTD_MAGIC = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())
        /// The payload is tens of megabytes; anything far below that is a stub or a page.
        const val MIN_ARCHIVE_BYTES = 1L shl 20
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 20_000
    }
}
