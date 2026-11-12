package app.gamenative.theme

import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ThemeZipTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun zip(vararg entries: Pair<String, String>): File {
        val f = tmp.newFile()
        ZipOutputStream(f.outputStream()).use { z ->
            for ((name, body) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(body.toByteArray())
                z.closeEntry()
            }
        }
        return f
    }

    private fun assertRejected(zip: File) {
        val dest = tmp.newFolder()
        try {
            ThemeZip.extract(zip, dest)
            fail("expected the zip to be rejected")
        } catch (e: IOException) {
            assertFalse(File(tmp.root, "evil.txt").exists())
        }
    }

    @Test
    fun entryPathsAreSanitised() {
        assertEquals("a/b.css", ThemeZip.safeEntryPath("a\\b.css"))
        assertEquals("a/b.css", ThemeZip.safeEntryPath("./a//b.css"))
        assertNull(ThemeZip.safeEntryPath("../evil.txt"))
        assertNull(ThemeZip.safeEntryPath("a/../../evil.txt"))
        assertNull(ThemeZip.safeEntryPath("/etc/passwd"))
        assertNull(ThemeZip.safeEntryPath("C:/evil.txt"))
    }

    @Test
    fun zipSlipIsRejected() {
        assertRejected(zip("theme.css" to "", "../evil.txt" to "x"))
        assertRejected(zip("/abs/evil.txt" to "x"))
    }

    @Test
    fun symlinkEntriesAreRejected() {
        val f = zip("theme.css" to "", "link" to "/etc/passwd")
        val bytes = f.readBytes()
        var i = bytes.size - 4
        var patched = 0
        while (i >= 0) {
            if (bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4b.toByte() && bytes[i + 2] == 0x01.toByte() && bytes[i + 3] == 0x02.toByte()) {
                val nameLen = (bytes[i + 28].toInt() and 0xff) or ((bytes[i + 29].toInt() and 0xff) shl 8)
                if (String(bytes, i + 46, nameLen) == "link") {
                    bytes[i + 5] = 3
                    val attr = 0xA1FF shl 16
                    for (k in 0 until 4) bytes[i + 38 + k] = ((attr ushr (8 * k)) and 0xff).toByte()
                    patched++
                }
            }
            i--
        }
        assertEquals(1, patched)
        f.writeBytes(bytes)
        assertEquals(setOf("link"), ThemeZip.symlinkEntries(f))
        assertRejected(f)
    }

    @Test
    fun capsAreEnforced() {
        val many = zip(*Array(5) { "f$it.css" to "" })
        try {
            ThemeZip.extract(many, tmp.newFolder(), maxEntries = 4)
            fail("entry cap")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("too many"))
        }
        val big = zip("theme.css" to "x".repeat(2000))
        try {
            ThemeZip.extract(big, tmp.newFolder(), maxBytes = 1000)
            fail("size cap")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("MB"))
        }
        ThemeZip.extract(big, tmp.newFolder(), maxBytes = 5000)
    }

    @Test
    fun themeAtRootOrInOneFolder() {
        val a = tmp.newFolder()
        ThemeZip.extract(zip("theme.json" to "{\"name\":\"A\"}"), a)
        assertEquals(a, ThemeZip.findThemeRoot(a))

        val b = tmp.newFolder()
        ThemeZip.extract(zip("My Theme/theme.css" to "", "My Theme/img/x.png" to "", "__MACOSX/My Theme/._theme.css" to ""), b)
        assertEquals(File(b, "My Theme"), ThemeZip.findThemeRoot(b))

        val c = tmp.newFolder()
        ThemeZip.extract(zip("One/theme.css" to "", "Two/theme.css" to ""), c)
        assertNull(ThemeZip.findThemeRoot(c))
        assertTrue(File(c, "Two/theme.css").isFile)
    }
}
