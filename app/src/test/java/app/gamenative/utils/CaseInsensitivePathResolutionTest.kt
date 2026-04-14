package app.gamenative.utils

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CaseInsensitivePathResolutionTest {

    @get:Rule
    val tmpDir = TemporaryFolder()

    // -- resolveCaseInsensitive --

    @Test
    fun `exact casing returns exact path`() {
        val base = tmpDir.newFolder("game")
        File(base, "Data/Saves").mkdirs()

        val result = FileUtils.resolveCaseInsensitive(base, "Data/Saves")
        assertEquals(File(base, "Data/Saves").absolutePath, result.absolutePath)
    }

    @Test
    fun `wrong casing resolves to on-disk casing`() {
        val base = tmpDir.newFolder("game")
        File(base, "Data/Saves").mkdirs()

        val result = FileUtils.resolveCaseInsensitive(base, "data/saves")
        assertEquals(File(base, "Data/Saves").absolutePath, result.absolutePath)
    }

    @Test
    fun `mixed casing resolves each segment independently`() {
        val base = tmpDir.newFolder("game")
        File(base, "LocalLow/CompanyName").mkdirs()

        val result = FileUtils.resolveCaseInsensitive(base, "locallow/companyname")
        assertEquals(File(base, "LocalLow/CompanyName").absolutePath, result.absolutePath)
    }

    @Test
    fun `nonexistent segments appended with original casing`() {
        val base = tmpDir.newFolder("game")
        File(base, "Data").mkdirs()

        val result = FileUtils.resolveCaseInsensitive(base, "data/NewFolder/file.txt")
        // Data exists → resolved to on-disk "Data"
        // NewFolder and file.txt don't exist → appended verbatim
        assertEquals(File(base, "Data/NewFolder/file.txt").absolutePath, result.absolutePath)
    }

    @Test
    fun `completely nonexistent path appended verbatim`() {
        val base = tmpDir.newFolder("game")

        val result = FileUtils.resolveCaseInsensitive(base, "Foo/Bar/baz.txt")
        assertEquals(File(base, "Foo/Bar/baz.txt").absolutePath, result.absolutePath)
    }

    @Test
    fun `backslash separators normalized`() {
        val base = tmpDir.newFolder("game")
        File(base, "Data/Saves").mkdirs()

        val result = FileUtils.resolveCaseInsensitive(base, "data\\saves")
        assertEquals(File(base, "Data/Saves").absolutePath, result.absolutePath)
    }

    @Test
    fun `empty relative path returns base dir`() {
        val base = tmpDir.newFolder("game")
        val result = FileUtils.resolveCaseInsensitive(base, "")
        assertEquals(base.absolutePath, result.absolutePath)
    }

    // -- findFileCaseInsensitive --

    @Test
    fun `findFile exact casing returns file`() {
        val base = tmpDir.newFolder("game")
        val file = File(base, "CheckApplication.exe")
        file.createNewFile()

        val result = FileUtils.findFileCaseInsensitive(base, "CheckApplication.exe")
        assertNotNull(result)
        assertEquals(file.absolutePath, result!!.absolutePath)
    }

    @Test
    fun `findFile wrong casing resolves to existing file`() {
        val base = tmpDir.newFolder("game")
        val created = File(base, "CheckApplication.exe")
        created.createNewFile()

        val result = FileUtils.findFileCaseInsensitive(base, "checkapplication.exe")
        assertNotNull(result)
        // on case-insensitive FS (macOS) the fast path returns query casing;
        // on case-sensitive FS (Android/Linux) the slow path resolves to on-disk casing.
        // both point to the same file — assert via canonical path.
        assertEquals(created.canonicalPath, result!!.canonicalPath)
    }

    @Test
    fun `findFile nonexistent returns null`() {
        val base = tmpDir.newFolder("game")
        val result = FileUtils.findFileCaseInsensitive(base, "nope.exe")
        assertNull(result)
    }

    @Test
    fun `findFile nested wrong casing resolves to existing file`() {
        val base = tmpDir.newFolder("game")
        val dir = File(base, "Data/SaveFiles")
        dir.mkdirs()
        val created = File(dir, "slot1.sav")
        created.createNewFile()

        val result = FileUtils.findFileCaseInsensitive(base, "data/savefiles/slot1.sav")
        assertNotNull(result)
        assertEquals(created.canonicalPath, result!!.canonicalPath)
    }
}
