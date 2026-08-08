package app.gamenative.html5.fingerprint

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// pure-jvm unit tests (no Robolectric, no Android imports). locks ZipDirectoryRef semantics
// against InMemoryDirectoryRef — signatures must behave identically on-zip vs. in-memory.
class ZipDirectoryRefTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    @Test
    fun exists_returnsTrueForTopLevelFile() {
        val fixture = writeZip(
            tempFolder.newFile("top.zip"),
            mapOf("a.txt" to "hi".toByteArray()),
        )
        java.util.zip.ZipFile(fixture).use { zf ->
            val ref = ZipDirectoryRef(zf)
            assertTrue(ref.exists("a.txt"))
        }
    }

    @Test
    fun exists_returnsTrueForNestedFile() {
        val fixture = writeZip(
            tempFolder.newFile("nested.zip"),
            mapOf("www/js/rpg_core.js" to ByteArray(0)),
        )
        java.util.zip.ZipFile(fixture).use { zf ->
            val ref = ZipDirectoryRef(zf)
            assertTrue(ref.exists("www/js/rpg_core.js"))
        }
    }

    @Test
    fun exists_returnsTrueForDirectoryInferredFromChildren() {
        val fixture = writeZip(
            tempFolder.newFile("inferred.zip"),
            mapOf("www/js/rpg_core.js" to ByteArray(0)),
        )
        java.util.zip.ZipFile(fixture).use { zf ->
            val ref = ZipDirectoryRef(zf)
            // NO explicit "www/" or "www/js/" entries — must be inferred from child prefix.
            assertTrue(ref.exists("www"))
            assertTrue(ref.exists("www/js"))
        }
    }

    @Test
    fun exists_returnsTrueForExplicitDirectoryEntry() {
        val fixture = writeZip(
            tempFolder.newFile("explicit.zip"),
            mapOf("data/" to ByteArray(0)),
        )
        java.util.zip.ZipFile(fixture).use { zf ->
            val ref = ZipDirectoryRef(zf)
            // explicit zero-byte "data/" entry — must resolve as existing dir without trailing slash.
            assertTrue(ref.exists("data"))
        }
    }

    @Test
    fun exists_returnsFalseForMissing() {
        val fixture = writeZip(
            tempFolder.newFile("missing.zip"),
            mapOf("a.txt" to "hi".toByteArray()),
        )
        java.util.zip.ZipFile(fixture).use { zf ->
            val ref = ZipDirectoryRef(zf)
            assertFalse(ref.exists("nope.txt"))
        }
    }

    @Test
    fun listFiles_returnsImmediateChildrenOnly() {
        val fixture = writeZip(
            tempFolder.newFile("children.zip"),
            mapOf(
                "www/js/rpg_core.js" to ByteArray(0),
                "www/js/plugins.js" to ByteArray(0),
                "www/data/System.json" to "{}".toByteArray(),
            ),
        )
        java.util.zip.ZipFile(fixture).use { zf ->
            val ref = ZipDirectoryRef(zf)
            assertEquals(
                setOf("rpg_core.js", "plugins.js"),
                ref.listFiles("www/js").toSet(),
            )
            assertEquals(
                setOf("js", "data"),
                ref.listFiles("www").toSet(),
            )
        }
    }

    @Test
    fun listFiles_emptyForMissingPath() {
        val fixture = writeZip(
            tempFolder.newFile("bogus.zip"),
            mapOf("a.txt" to "hi".toByteArray()),
        )
        java.util.zip.ZipFile(fixture).use { zf ->
            val ref = ZipDirectoryRef(zf)
            assertEquals(emptyList<String>(), ref.listFiles("bogus"))
        }
    }

    @Test
    fun listFiles_rootListing() {
        val fixture = writeZip(
            tempFolder.newFile("root.zip"),
            mapOf(
                "index.html" to "<html/>".toByteArray(),
                "scripts/c3runtime.js" to ByteArray(0),
                "media/splash.png" to ByteArray(0),
            ),
        )
        java.util.zip.ZipFile(fixture).use { zf ->
            val ref = ZipDirectoryRef(zf)
            val expected = setOf("index.html", "scripts", "media")
            assertEquals(expected, ref.listFiles("").toSet())
            assertEquals(expected, ref.listFiles(".").toSet())
        }
    }

    private fun writeZip(target: File, entries: Map<String, ByteArray>): File {
        java.util.zip.ZipOutputStream(target.outputStream()).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(java.util.zip.ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return target
    }
}
