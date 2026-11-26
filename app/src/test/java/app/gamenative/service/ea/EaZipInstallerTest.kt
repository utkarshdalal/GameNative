package app.gamenative.service.ea

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EaZipInstallerTest {
    private val big = Random(7).nextBytes(2 * 1024 * 1024)
    private val stored = Random(8).nextBytes(300 * 1024)
    private lateinit var zip: ByteArray
    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private val ranges: MutableList<String> = Collections.synchronizedList(mutableListOf())
    @Volatile private var throttled = false

    @Before
    fun setUp() {
        zip = buildZip()
        dir = Files.createTempDirectory("ea-zip-install").toFile()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "HEAD") return MockResponse().setResponseCode(200).addHeader("Content-Length", zip.size)
                val range = request.getHeader("Range") ?: return MockResponse().setResponseCode(200).setBody(Buffer().write(zip))
                ranges += range
                val (first, last) = range.removePrefix("bytes=").split("-").map { it.toLong() }
                val body = Buffer().write(zip, first.toInt(), (last - first + 1).toInt())
                val response = MockResponse().setResponseCode(206)
                    .addHeader("Content-Range", "bytes $first-$last/${zip.size}")
                    .setBody(body)
                if (throttled) response.throttleBody(32 * 1024, 20, TimeUnit.MILLISECONDS)
                return response
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun le(size: Int, value: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array().copyOf(size)

    private fun le(size: Int, value: Int): ByteArray = le(size, value.toLong())

    private fun rawDeflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return out.toByteArray()
    }

    /** A zip as EA's CDN serves it: sizes in the local headers, no data descriptors. */
    private fun buildZip(): ByteArray {
        val out = ByteArrayOutputStream()
        val central = ByteArrayOutputStream()
        val files = listOf(Triple("Game/big.bin", big, true), Triple("Game/stored.bin", stored, false))
        for ((name, data, deflate) in files) {
            val body = if (deflate) rawDeflate(data) else data
            val crc = CRC32().also { it.update(data) }.value
            val nameBytes = name.toByteArray()
            val method = if (deflate) 8 else 0
            val offset = out.size().toLong()
            out.write(le(4, 0x04034b50)); out.write(le(2, 20)); out.write(le(2, 0x800))
            out.write(le(2, method)); out.write(le(4, 0)); out.write(le(4, crc))
            out.write(le(4, body.size)); out.write(le(4, data.size))
            out.write(le(2, nameBytes.size)); out.write(le(2, 0)); out.write(nameBytes); out.write(body)
            central.write(le(4, 0x02014b50)); central.write(le(2, 20)); central.write(le(2, 20))
            central.write(le(2, 0x800)); central.write(le(2, method)); central.write(le(4, 0))
            central.write(le(4, crc)); central.write(le(4, body.size)); central.write(le(4, data.size))
            central.write(le(2, nameBytes.size)); central.write(le(2, 0)); central.write(le(2, 0))
            central.write(le(2, 0)); central.write(le(2, 0)); central.write(le(4, 0))
            central.write(le(4, offset)); central.write(nameBytes)
        }
        val cdOffset = out.size()
        out.write(central.toByteArray())
        out.write(le(4, 0x06054b50)); out.write(le(2, 0)); out.write(le(2, 0))
        out.write(le(2, files.size)); out.write(le(2, files.size))
        out.write(le(4, central.size())); out.write(le(4, cdOffset)); out.write(le(2, 0))
        return out.toByteArray()
    }

    private val url get() = server.url("/game.zip").toString()

    private fun entry(name: String): EaZip.Entry =
        (EaZip.parseTail(zip, zip.size.toLong()) as EaZip.TailResult.Entries).entries.first { it.name == name }

    private fun part(name: String) = File(dir, "$name.gnpart")

    private fun rawBytes(entry: EaZip.Entry): ByteArray = zip.copyOfRange(entry.dataOffset.toInt(), (entry.dataOffset + entry.compressedSize).toInt())

    private fun install(onBytes: (Long) -> Unit = {}) = runBlocking {
        val plan = EaZipInstaller.plan(url, dir)
        EaZipInstaller.install(plan, dir, url, { url }, onBytes)
        plan
    }

    private fun assertInstalled() {
        assertArrayEquals(big, File(dir, "Game/big.bin").readBytes())
        assertArrayEquals(stored, File(dir, "Game/stored.bin").readBytes())
        assertFalse(part("Game/big.bin").exists())
        assertFalse(part("Game/stored.bin").exists())
    }

    @Test
    fun install_freshDirectory_placesEveryEntry() {
        install()
        assertInstalled()
    }

    @Test
    fun install_afterAKillMidEntry_continuesFromThePartFile() {
        val entry = entry("Game/big.bin")
        val half = entry.compressedSize / 2
        part(entry.name).apply { parentFile.mkdirs() }.writeBytes(rawBytes(entry).copyOf(half.toInt()))

        val fetched = AtomicLong()
        val plan = install { fetched.addAndGet(it) }

        assertEquals(half, plan.partialBytes)
        assertTrue(ranges.contains("bytes=${entry.dataOffset + half}-${entry.dataOffset + entry.compressedSize - 1}"))
        assertEquals(entry("Game/big.bin").compressedSize - half + entry("Game/stored.bin").compressedSize, fetched.get())
        assertInstalled()
    }

    @Test
    fun install_corruptPartFile_isFetchedAgainOnce() {
        val entry = entry("Game/big.bin")
        val garbage = Random(9).nextBytes(entry.compressedSize.toInt())
        part(entry.name).apply { parentFile.mkdirs() }.writeBytes(garbage)

        install()

        assertTrue(ranges.contains("bytes=${entry.dataOffset}-${entry.dataOffset + entry.compressedSize - 1}"))
        assertInstalled()
    }

    @Test
    fun install_canceledMidEntry_keepsTheBytesForTheNextRun() = runBlocking {
        val entry = entry("Game/big.bin")
        throttled = true
        val plan = EaZipInstaller.plan(url, dir)
        val job = launch(Dispatchers.IO) { EaZipInstaller.install(plan, dir, url, { url }, {}) }
        while (part(entry.name).length() < 256 * 1024) delay(10)
        job.cancelAndJoin()

        val kept = part(entry.name).length()
        val keptStored = part("Game/stored.bin").length()
        assertTrue(kept in 1 until entry.compressedSize)
        assertFalse(File(dir, entry.name).exists())

        throttled = false
        ranges.clear()
        val resumed = install()
        assertEquals(kept + keptStored, resumed.partialBytes)
        assertTrue(ranges.contains("bytes=${entry.dataOffset + kept}-${entry.dataOffset + entry.compressedSize - 1}"))
        assertInstalled()
    }
}
