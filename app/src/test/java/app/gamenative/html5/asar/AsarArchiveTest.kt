package app.gamenative.html5.asar

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

// pure-JVM. no Robolectric. parser has no Android deps by design 
class AsarArchiveTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    // ---------- round-trip (happy path) ----------

    @Test
    fun open_readsValidSyntheticFixture() {
        val f = tempFolder.newFile("a.asar")
        AsarTestFixtures.writeFixture(f, linkedMapOf("package.json" to """{"name":"x"}""".toByteArray()))
        AsarArchive.open(f).use { archive ->
            assertNotNull(archive)
        }
    }

    @Test
    fun read_returnsByteIdenticalContentForSingleEntry() {
        val f = tempFolder.newFile("single.asar")
        val payload = "hello asar".toByteArray()
        AsarTestFixtures.writeFixture(f, linkedMapOf("only.txt" to payload))
        AsarArchive.open(f).use { archive ->
            assertArrayEquals(payload, archive.read("only.txt"))
        }
    }

    @Test
    fun read_returnsByteIdenticalContentForMultipleEntries() {
        val f = tempFolder.newFile("multi.asar")
        val entries = linkedMapOf(
            "package.json" to """{"name":"x"}""".toByteArray(),
            "main.js" to "console.log('hi')".toByteArray(),
            // binary 0..255
            "assets/img.bin" to ByteArray(256) { it.toByte() },
            "assets/empty" to ByteArray(0),
            "sub/nested.txt" to "nested".toByteArray(),
        )
        AsarTestFixtures.writeFixture(f, entries)
        AsarArchive.open(f).use { archive ->
            entries.forEach { (path, expected) ->
                assertArrayEquals("mismatch at $path", expected, archive.read(path))
            }
        }
    }

    @Test
    fun read_returnsNullForMissingEntry() {
        val f = tempFolder.newFile("miss.asar")
        AsarTestFixtures.writeFixture(f, linkedMapOf("a.txt" to "a".toByteArray()))
        AsarArchive.open(f).use { archive ->
            assertNull(archive.read("does/not/exist.txt"))
        }
    }

    // ---------- exists ----------

    @Test
    fun exists_trueForFileAndParentDirectory() {
        val f = tempFolder.newFile("exists.asar")
        AsarTestFixtures.writeFixture(
            f,
            linkedMapOf(
                "foo/bar.js" to "x".toByteArray(),
                "top.txt" to "y".toByteArray(),
            ),
        )
        AsarArchive.open(f).use { archive ->
            assertTrue(archive.exists("foo/bar.js"))
            assertTrue(archive.exists("foo"))
            assertTrue(archive.exists("top.txt"))
        }
    }

    @Test
    fun exists_falseForMissingEntry() {
        val f = tempFolder.newFile("miss2.asar")
        AsarTestFixtures.writeFixture(f, linkedMapOf("a.txt" to "a".toByteArray()))
        AsarArchive.open(f).use { archive ->
            assertFalse(archive.exists("nope"))
            assertFalse(archive.exists("foo/bar"))
        }
    }

    // ---------- listFiles ----------

    @Test
    fun listFiles_rootReturnsTopLevelEntries() {
        val f = tempFolder.newFile("list-root.asar")
        AsarTestFixtures.writeFixture(
            f,
            linkedMapOf(
                "package.json" to "{}".toByteArray(),
                "main.js" to "".toByteArray(),
                "assets/img.bin" to "x".toByteArray(),
                "sub/nested.txt" to "y".toByteArray(),
            ),
        )
        AsarArchive.open(f).use { archive ->
            val top = archive.listFiles("").toSet()
            assertEquals(setOf("package.json", "main.js", "assets", "sub"), top)
        }
    }

    @Test
    fun listFiles_subdirReturnsChildren() {
        val f = tempFolder.newFile("list-sub.asar")
        AsarTestFixtures.writeFixture(
            f,
            linkedMapOf(
                "foo/a.js" to "1".toByteArray(),
                "foo/b.js" to "2".toByteArray(),
                "foo/inner/c.js" to "3".toByteArray(),
            ),
        )
        AsarArchive.open(f).use { archive ->
            val kids = archive.listFiles("foo").toSet()
            assertEquals(setOf("a.js", "b.js", "inner"), kids)
        }
    }

    @Test
    fun listFiles_onMissingOrFile_returnsEmpty() {
        val f = tempFolder.newFile("list-empty.asar")
        AsarTestFixtures.writeFixture(
            f,
            linkedMapOf(
                "foo/file.js" to "x".toByteArray(),
            ),
        )
        AsarArchive.open(f).use { archive ->
            assertTrue(archive.listFiles("nope").isEmpty())
            assertTrue(archive.listFiles("foo/file.js").isEmpty())
        }
    }

    // ---------- packageJson ----------

    @Test
    fun packageJson_returnsParsedRootPackageJson() {
        val f = tempFolder.newFile("pkg.asar")
        val pkgBytes = """{"name":"alpha","productName":"Alpha","version":"1.2.3"}""".toByteArray()
        AsarTestFixtures.writeFixture(f, linkedMapOf("package.json" to pkgBytes))
        AsarArchive.open(f).use { archive ->
            val obj = archive.packageJson()
            assertNotNull(obj)
            assertEquals("alpha", obj!!["name"]?.jsonPrimitive?.content)
            assertEquals("Alpha", obj["productName"]?.jsonPrimitive?.content)
            assertEquals("1.2.3", obj["version"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun packageJson_returnsNullWhenAbsent() {
        val f = tempFolder.newFile("nopkg.asar")
        AsarTestFixtures.writeFixture(f, linkedMapOf("main.js" to "".toByteArray()))
        AsarArchive.open(f).use { archive ->
            assertNull(archive.packageJson())
        }
    }

    // ---------- close ----------

    @Test
    fun close_idempotent() {
        val f = tempFolder.newFile("close.asar")
        AsarTestFixtures.writeFixture(f, linkedMapOf("a" to "a".toByteArray()))
        val archive = AsarArchive.open(f)
        archive.close()
        archive.close() // must not throw
    }

    // ---------- real-world fixture ----------
    // src/test/resources/asar/realworld.asar was produced with `npx @electron/asar pack …`
    // against a four-entry tree. binds the parser against the CANONICAL upstream byte
    // layout, not just what AsarTestFixtures emits (guards against fixture+parser
    // co-evolving away from reality).

    @Test
    fun open_readsRealWorldAsarFromElectronAsarTool() {
        val target = tempFolder.newFile("realworld.asar")
        javaClass.getResourceAsStream("/asar/realworld.asar").use { input ->
            assertNotNull("realworld.asar fixture missing from test resources", input)
            target.outputStream().use { input!!.copyTo(it) }
        }
        AsarArchive.open(target).use { archive ->
            // package.json round-trip via the helper
            val pkg = archive.packageJson()
            assertNotNull("packageJson() returned null on real asar", pkg)
            assertEquals("realworld", pkg!!["name"]?.jsonPrimitive?.content)
            assertEquals("RealWorld", pkg["productName"]?.jsonPrimitive?.content)
            assertEquals("0.1.0", pkg["version"]?.jsonPrimitive?.content)

            // directory listing + nested read
            assertEquals(
                setOf("package.json", "main.js", "nested"),
                archive.listFiles("").toSet(),
            )
            assertEquals(
                setOf("readme.txt", "empty"),
                archive.listFiles("nested").toSet(),
            )

            // byte-level content parity
            assertArrayEquals(
                "line1\nline2\n".toByteArray(),
                archive.read("nested/readme.txt"),
            )
            assertArrayEquals(
                "console.log(\"hello from a real asar\")\n".toByteArray(),
                archive.read("main.js"),
            )
            assertArrayEquals(ByteArray(0), archive.read("nested/empty"))
        }
    }

    // ---------- malformed input ----------

    @Test
    fun open_rejectsOversizedHeader() {
        val f = tempFolder.newFile("huge.asar")
        // outer=4, headerPickleSize=200_000_008, innerPayloadSize=200_000_004,
        // headerJsonLen=200_000_000 — exceeds MAX_HEADER_SIZE.
        RandomAccessFile(f, "rw").use { raf ->
            writeLeU32(raf, 4)
            writeLeU32(raf, 200_000_008)
            writeLeU32(raf, 200_000_004)
            writeLeU32(raf, 200_000_000)
            // pad a few bytes so file isn't trivially truncated.
            raf.write(ByteArray(32))
        }
        assertThrowsIO { AsarArchive.open(f) }
    }

    @Test
    fun open_rejectsGarbageJsonHeader() {
        val f = tempFolder.newFile("garbage.asar")
        val garbage = "not-json{{{".toByteArray(Charsets.UTF_8)
        val headerSize = garbage.size
        val pad = ((4 - (headerSize % 4)) % 4)
        val innerPayloadSize = 4 + headerSize + pad
        val headerPickleSize = 4 + innerPayloadSize
        RandomAccessFile(f, "rw").use { raf ->
            writeLeU32(raf, 4)
            writeLeU32(raf, headerPickleSize)
            writeLeU32(raf, innerPayloadSize)
            writeLeU32(raf, headerSize)
            raf.write(garbage)
            if (pad > 0) raf.write(ByteArray(pad))
        }
        assertThrowsIO { AsarArchive.open(f) }
    }

    @Test
    fun open_rejectsTruncatedFile() {
        val f = tempFolder.newFile("trunc.asar")
        RandomAccessFile(f, "rw").use { raf ->
            raf.write(ByteArray(12)) // 12 bytes only — below 20-byte minimum
        }
        assertThrowsIO { AsarArchive.open(f) }
    }

    @Test
    fun read_rejectsEntryExceedingFileLength() {
        val f = tempFolder.newFile("oob.asar")
        // craft a valid pickle-wrapped JSON header that claims an entry at offset=999999 size=1
        // but give the file <100 bytes of body region.
        val headerJson = Json.parseToJsonElement(
            """{"files":{"x":{"size":1,"offset":"999999"}}}""",
        ).toString()
        val headerBytes = headerJson.toByteArray(Charsets.UTF_8)
        val headerSize = headerBytes.size
        val pad = ((4 - (headerSize % 4)) % 4)
        val innerPayloadSize = 4 + headerSize + pad
        val headerPickleSize = 4 + innerPayloadSize
        RandomAccessFile(f, "rw").use { raf ->
            writeLeU32(raf, 4)
            writeLeU32(raf, headerPickleSize)
            writeLeU32(raf, innerPayloadSize)
            writeLeU32(raf, headerSize)
            raf.write(headerBytes)
            if (pad > 0) raf.write(ByteArray(pad))
            // only 50 bytes of body — far below offset 999999.
            raf.write(ByteArray(50))
        }
        AsarArchive.open(f).use { archive ->
            // bounds check fails → null (not throw). read is soft on oob.
            assertNull(archive.read("x"))
        }
    }

    @Test
    fun read_rejectsDotDotTraversal() {
        val f = tempFolder.newFile("trav.asar")
        AsarTestFixtures.writeFixture(f, linkedMapOf("a" to "a".toByteArray()))
        AsarArchive.open(f).use { archive ->
            assertNull(archive.read("../etc/passwd"))
            assertNull(archive.read("foo/../a"))
        }
    }

    // ---------- helpers ----------

    private fun writeLeU32(raf: RandomAccessFile, value: Int) {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
        raf.write(buf.array())
    }

    private inline fun assertThrowsIO(block: () -> Unit) {
        try {
            block()
            fail("expected IOException")
        } catch (e: IOException) {
            // expected
        } catch (e: Throwable) {
            fail("expected IOException but got ${e::class.simpleName}: ${e.message}")
        }
    }
}
