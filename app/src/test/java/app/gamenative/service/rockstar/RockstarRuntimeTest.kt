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
            File(stage, "x64/locales").mkdirs()
            File(stage, "x64/socialclub.dll").writeText("MZ-sdk64")
            File(stage, "x64/locales/en-US.pak").writeText("pak")
            File(stage, "x86").mkdirs()
            File(stage, "x86/socialclub.dll").writeText("MZ-sdk32")
            onProgress(1f); true
        }
        RockstarRuntime.install(selfExtractor(), prefix, extractor)
        assertEquals(listOf(1000L, 1056L), tried)
        assertTrue(RockstarRuntime.isInstalled(prefix))
        assertEquals("pak", File(RockstarRuntime.socialClubDir(prefix), "locales/en-US.pak").readText())
        assertEquals("MZ-sdk64", File(RockstarRuntime.socialClubDir(prefix), "socialclub.dll").readText())
        assertEquals("MZ-sdk32", File(RockstarRuntime.socialClubX86Dir(prefix), "socialclub.dll").readText())
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

    @Test fun refusesAnArchiveWithoutTheX86Runtime() {
        val prefix = temporary.newFolder()
        val extractor = RockstarRuntime.Extractor { _, _, stage, _ ->
            File(stage, "x64").mkdirs()
            File(stage, "x64/socialclub.dll").writeText("MZ-sdk64"); true
        }
        assertThrows(IllegalStateException::class.java) { RockstarRuntime.install(selfExtractor(), prefix, extractor) }
        assertFalse(RockstarRuntime.isInstalled(prefix))
    }

    @Test fun prefersTheInstallRootInstallerOverTheTitleFolder() {
        val game = temporary.newFolder()
        val title = File(game, "GTAIV").apply { mkdir() }
        File(title, "title.rgl").writeText("RGLM")
        val titleInstaller = File(title, "Redistributables/Social-Club-Setup.exe").apply { parentFile!!.mkdirs(); writeText("sfx") }
        assertEquals(titleInstaller, RockstarRuntime.installer(game))
        val rootInstaller = File(game, "Redistributables/Social-Club-Setup.exe").apply { parentFile!!.mkdirs(); writeText("sfx") }
        assertEquals(rootInstaller, RockstarRuntime.installer(game))
    }
}
