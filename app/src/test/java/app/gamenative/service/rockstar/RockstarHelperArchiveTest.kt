package app.gamenative.service.rockstar

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class RockstarHelperArchiveTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun detectsInstalledRockstarMetadataButNotUnrelatedFiles() {
        val game = temporary.newFolder()
        assertFalse(RockstarHelperArchive.usesRockstar(game))
        File(game, "title.rgl").writeText("wrong")
        assertFalse(RockstarHelperArchive.usesRockstar(game))
        File(game, "title.rgl").writeText("RGLM")
        assertTrue(RockstarHelperArchive.usesRockstar(game))
        File(game, "title.rgl").delete()
        File(game, "SocialClub.dll").writeBytes(byteArrayOf(77, 90))
        assertTrue(RockstarHelperArchive.usesRockstar(game))
    }

    @Test fun findsTheTitleInTheRootOrOneSubdirectoryDown() {
        val game = temporary.newFolder()
        File(game, "PlayLAN.exe").writeText("MZ")
        File(game, "title.rgl").writeText("RGLM")
        assertEquals(game, RockstarHelperArchive.titleDir(game))
        File(game, "title.rgl").delete()
        assertNull(RockstarHelperArchive.titleDir(game))
        File(game, ".gamenative-rockstar").mkdir()
        File(game, ".gamenative-rockstar/title.rgl").writeText("RGLM")
        File(game, "Redistributables").mkdir()
        assertNull(RockstarHelperArchive.titleDir(game))
        val title = File(game, "GTAIV").apply { mkdir() }
        File(title, "title.rgl").writeText("RGLM")
        assertEquals(title, RockstarHelperArchive.titleDir(game))
        assertTrue(RockstarHelperArchive.usesRockstar(game))
        val nested = File(game, "Deep/Deeper").apply { mkdirs() }
        File(title, "title.rgl").delete()
        File(nested, "title.rgl").writeText("RGLM")
        assertNull(RockstarHelperArchive.titleDir(game))
    }

    @Test fun extractsArchiveOnceAndAgainWhenAFileIsMissing() {
        val files = temporary.newFolder()
        val bytes = rockstarTestArchive()
        var opens = 0
        val open = { opens++; bytes.inputStream() }
        val destination = RockstarHelperArchive.ensureExtracted(files, open)
        assertTrue(RockstarHelperArchive.isReady(files))
        assertEquals(10, destination.listFiles()!!.size)
        RockstarHelperArchive.ensureExtracted(files, open)
        assertEquals(1, opens)
        File(destination, "rgscstub.exe").writeText("edited by hand")
        RockstarHelperArchive.ensureExtracted(files, open)
        assertEquals(1, opens)
        assertEquals("edited by hand", File(destination, "rgscstub.exe").readText())
        File(destination, "rgscstub.exe").delete()
        assertFalse(RockstarHelperArchive.isReady(files))
        RockstarHelperArchive.ensureExtracted(files, open)
        assertEquals(2, opens)
        assertTrue(RockstarHelperArchive.isReady(files))
    }

    @Test fun signInScriptIsFilledFromTheArchiveTemplate() {
        val files = temporary.newFolder()
        assertThrows(IllegalStateException::class.java) { RockstarSignInShim.script(files, "rdr2", "gn") }
        RockstarHelperArchive.ensureExtracted(files) { rockstarTestArchive().inputStream() }
        val script = RockstarSignInShim.script(files, "rdr2", "gn", "DEVICE")
        assertTrue(script.startsWith("bridge=gn title=rdr2 fp={"))
        assertTrue(script.contains("\"device_name\":\"DEVICE\""))
    }

    @Test fun installsDownloadedArchive() {
        val files = temporary.newFolder()
        val archive = temporary.newFile().apply { writeBytes(rockstarTestArchive()) }
        RockstarHelperArchive.install(archive, files)
        assertTrue(RockstarHelperArchive.isReady(files))
    }

    @Test fun writesEntriesByNameOnlyAndFailsOnAnIncompleteArchive() {
        val bytes = ByteArrayOutputStream()
        ZstdCompressorOutputStream(bytes).use { zstd ->
            TarArchiveOutputStream(zstd).use { tar ->
                tar.putArchiveEntry(TarArchiveEntry("../outside").apply { size = 1 })
                tar.write(byteArrayOf(1))
                tar.closeArchiveEntry()
            }
        }
        val files = temporary.newFolder()
        assertThrows(IllegalStateException::class.java) {
            RockstarHelperArchive.ensureExtracted(files) { ByteArrayInputStream(bytes.toByteArray()) }
        }
        assertFalse(RockstarHelperArchive.isReady(files))
        assertFalse(File(files, "outside").exists())
    }
}
