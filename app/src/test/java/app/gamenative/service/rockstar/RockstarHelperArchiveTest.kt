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

    @Test fun extractsArchiveOnceAndRepairsCorruptCache() {
        val files = temporary.newFolder()
        val bytes = rockstarTestArchive()
        var opens = 0
        val open = { opens++; bytes.inputStream() }
        val destination = RockstarHelperArchive.ensureExtracted(files, open)
        assertTrue(RockstarHelperArchive.isReady(files))
        assertEquals(7, destination.listFiles()!!.size)
        RockstarHelperArchive.ensureExtracted(files, open)
        assertEquals(1, opens)
        File(destination, "rgscstub.exe").writeText("damaged")
        assertFalse(RockstarHelperArchive.isReady(files))
        RockstarHelperArchive.ensureExtracted(files, open)
        assertEquals(2, opens)
        assertTrue(RockstarHelperArchive.isReady(files))
    }

    @Test fun installsDownloadedArchiveAndRejectsCorruptDownloads() {
        val files = temporary.newFolder()
        val archive = temporary.newFile().apply { writeBytes(rockstarTestArchive()) }
        val hash = RockstarHelperArchive.sha256(archive)
        RockstarHelperArchive.install(archive, files, hash)
        assertTrue(RockstarHelperArchive.isReady(files))
        archive.writeText("incomplete download")
        assertThrows(IllegalStateException::class.java) { RockstarHelperArchive.install(archive, files, hash) }
        assertTrue(RockstarHelperArchive.isReady(files))
    }

    @Test fun rejectsTraversalAndCleansIncompleteExtraction() {
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
        assertTrue(File(files, "rockstar").listFiles()!!.isEmpty())
        assertFalse(File(files, "outside").exists())
    }
}
