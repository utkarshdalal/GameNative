package app.gamenative.service.rockstar

import java.io.File
import java.util.Random
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RockstarRuntimeTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun selfExtractor(entries: Map<String, ByteArray>): File {
        val archive = temporary.newFile()
        SevenZOutputFile(archive).use { out ->
            for ((name, bytes) in entries) {
                out.putArchiveEntry(out.createArchiveEntry(temporary.newFile(), name))
                out.write(bytes)
                out.closeArchiveEntry()
            }
        }
        val stub = ByteArray(4096).also { Random(7).nextBytes(it) }
        val sfx = File(temporary.newFolder(), "Social-Club-Setup.exe")
        sfx.writeBytes(stub + archive.readBytes())
        return sfx
    }

    @Test fun installsTheX64PayloadIntoThePrefix() {
        val game = temporary.newFolder().also { File(it, "Redistributables").mkdirs() }
        val installer = selfExtractor(mapOf(
            "x64/socialclub.dll" to "MZ-sdk".toByteArray(),
            "x64/locales/en-US.pak" to "pak".toByteArray(),
            "x86/socialclub.dll" to "MZ-32".toByteArray(),
        )).also { it.renameTo(File(game, "Redistributables/Social-Club-Setup.exe")) }
        val prefix = temporary.newFolder()
        assertFalse(RockstarRuntime.isInstalled(prefix))
        assertEquals(File(game, "Redistributables/Social-Club-Setup.exe"), RockstarRuntime.installer(game))
        var last = 0f
        RockstarRuntime.install(RockstarRuntime.installer(game)!!, prefix) { last = it }
        assertTrue(RockstarRuntime.isInstalled(prefix))
        assertEquals("MZ-sdk", File(RockstarRuntime.socialClubDir(prefix), "socialclub.dll").readText())
        assertEquals("pak", File(RockstarRuntime.socialClubDir(prefix), "locales/en-US.pak").readText())
        assertFalse(File(prefix, "Program Files/Rockstar Games/x86").exists())
        assertFalse(File(prefix, "Program Files/Rockstar Games/Social Club.installing").exists())
        assertEquals(1f, last, 0f)
    }

    @Test fun refusesAnInstallerWithoutTheRuntimeAndEscapingEntries() {
        val prefix = temporary.newFolder()
        val noRuntime = selfExtractor(mapOf("x64/readme.txt" to "hi".toByteArray()))
        assertThrows(IllegalStateException::class.java) { RockstarRuntime.install(noRuntime, prefix) }
        val escaping = selfExtractor(mapOf("x64/../evil.dll" to "MZ".toByteArray()))
        assertThrows(IllegalStateException::class.java) { RockstarRuntime.install(escaping, prefix) }
        assertFalse(File(prefix, "Program Files/Rockstar Games/evil.dll").exists())
        assertFalse(RockstarRuntime.isInstalled(prefix))
    }
}
