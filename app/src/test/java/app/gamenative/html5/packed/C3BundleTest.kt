package app.gamenative.html5.packed

import app.gamenative.html5.fingerprint.FingerprintResult
import app.gamenative.html5.fingerprint.fingerprint
import app.gamenative.html5.profile.EnginePackId
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class C3BundleTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    // mirrors the layout documented on C3Bundle.
    private fun writeBundle(target: File, files: Map<String, ByteArray>, flags: Int = 0): File {
        val dir = ByteArrayOutputStream()
        DataOutputStream(dir).use { out ->
            out.writeInt(files.size)
            var offset = 0L
            files.forEach { (name, bytes) ->
                val nameBytes = name.toByteArray(Charsets.UTF_8)
                out.writeLong(0)
                out.writeLong(offset)
                out.writeLong(bytes.size.toLong())
                out.writeLong(bytes.size.toLong())
                out.writeByte(flags)
                out.writeInt(nameBytes.size)
                out.write(nameBytes)
                offset += bytes.size
            }
        }
        target.parentFile?.mkdirs()
        DataOutputStream(target.outputStream()).use { out ->
            out.write("c3ab".toByteArray())
            out.writeInt(0)
            out.writeLong(16)
            out.write("fdir".toByteArray())
            out.writeLong(dir.size().toLong())
            out.write(dir.toByteArray())
            out.write("blob".toByteArray())
            out.writeLong(files.values.sumOf { it.size.toLong() })
            files.values.forEach { out.write(it) }
        }
        return target
    }

    private val sample = linkedMapOf(
        "scripts/c3runtime.js" to "// runtime".toByteArray(),
        "index.html" to "<html></html>".toByteArray(),
        "media/a.webm" to ByteArray(300) { it.toByte() },
    )

    @Test
    fun open_readsEntriesInPlace() {
        val file = writeBundle(tempFolder.newFile("assets.dat"), sample)
        C3Bundle.open(file).use { bundle ->
            assertEquals(3, bundle.names().count())
            assertEquals(300L, bundle.size("media/a.webm"))
            assertArrayEquals(sample["media/a.webm"], bundle.open("media/a.webm")!!.readBytes())
            assertEquals("<html></html>", bundle.readText("index.html"))
            assertNull(bundle.open("missing.js"))
            assertNull(bundle.size("media"))
        }
    }

    // the loopback server skips ahead for range requests.
    @Test
    fun entryStream_skipStaysInsideEntry() {
        val file = writeBundle(tempFolder.newFile("assets.dat"), sample)
        C3Bundle.open(file).use { bundle ->
            bundle.open("media/a.webm")!!.use { input ->
                assertEquals(100L, input.skip(100))
                assertEquals(100, input.read())
                assertEquals(199L, input.skip(1000))
                assertEquals(-1, input.read())
            }
        }
    }

    @Test
    fun directoryRef_listsAndResolvesDirs() {
        val file = writeBundle(tempFolder.newFile("assets.dat"), sample)
        C3Bundle.open(file).use { bundle ->
            assertTrue(bundle.exists("scripts"))
            assertTrue(bundle.exists("scripts/c3runtime.js"))
            assertFalse(bundle.exists("scrip"))
            assertEquals(setOf("scripts", "index.html", "media"), bundle.listFiles("").toSet())
            assertEquals(listOf("a.webm"), bundle.listFiles("media"))
        }
    }

    @Test
    fun open_rejectsUnsafeNamesAndUnknownEncodings() {
        val traversal = writeBundle(tempFolder.newFile("t.dat"), mapOf("../evil.js" to byteArrayOf(1)))
        assertThrows(IOException::class.java) { C3Bundle.open(traversal) }
        val flagged = writeBundle(tempFolder.newFile("f.dat"), mapOf("a.js" to byteArrayOf(1)), flags = 1)
        assertThrows(IOException::class.java) { C3Bundle.open(flagged) }
        assertFalse(C3Bundle.isBundle(tempFolder.newFile("empty.dat")))
    }

    // the root package.json must not win: the generic nw.js signature would claim it.
    @Test
    fun fingerprint_webView2BundleExport_matchesC3OverRootPackageJson() {
        val root = tempFolder.newFolder("SolCesto")
        File(root, "package.json").writeText("""{"name":"SolCesto","main":"index.html"}""")
        writeBundle(File(root, C3Bundle.DEFAULT_PATH), sample)
        val result = fingerprint(root)
        assertTrue("matched: $result", result is FingerprintResult.Matched)
        result as FingerprintResult.Matched
        assertEquals(EnginePackId.C3, result.engine)
        assertEquals("c3ab:www/assets.dat", result.webRoot)
    }
}
