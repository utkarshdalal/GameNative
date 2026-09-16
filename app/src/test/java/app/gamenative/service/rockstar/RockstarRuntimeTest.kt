package app.gamenative.service.rockstar

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RockstarRuntimeTest {
    @get:Rule val temporary = TemporaryFolder()
    private val signature = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)

    private fun selfExtractor(): File = File(temporary.newFolder(), "Social-Club-Setup.exe").apply {
        writeBytes(ByteArray(1000) { 1 } + signature + ByteArray(50) + signature + ByteArray(20))
    }

    @Test fun findsEveryArchiveSignature() {
        assertEquals(listOf(1000L, 1056L), RockstarRuntime.signatureOffsets(selfExtractor()))
    }

    @Test fun installsFromTheFirstOffsetThatHoldsAnArchive() {
        val prefix = temporary.newFolder()
        val tried = ArrayList<Long>()
        val extractor = RockstarRuntime.Extractor { _, offset, stage, onProgress ->
            tried.add(offset)
            if (offset != 1056L) return@Extractor false
            File(stage, "socialclub.dll").writeText("MZ-sdk")
            File(stage, "locales").mkdirs()
            File(stage, "locales/en-US.pak").writeText("pak")
            onProgress(1f); true
        }
        RockstarRuntime.install(selfExtractor(), prefix, extractor)
        assertEquals(listOf(1000L, 1056L), tried)
        assertTrue(RockstarRuntime.isInstalled(prefix))
        assertEquals("pak", File(RockstarRuntime.socialClubDir(prefix), "locales/en-US.pak").readText())
        assertFalse(File(prefix, "Program Files/Rockstar Games/Social Club.installing").exists())
    }

    @Test fun refusesAnArchiveWithoutTheRuntime() {
        val prefix = temporary.newFolder()
        val extractor = RockstarRuntime.Extractor { _, _, stage, _ -> File(stage, "readme.txt").writeText("hi"); true }
        assertThrows(IllegalStateException::class.java) { RockstarRuntime.install(selfExtractor(), prefix, extractor) }
        assertFalse(RockstarRuntime.isInstalled(prefix))
        val game = temporary.newFolder()
        assertEquals(null, RockstarRuntime.installer(game))
    }
}
