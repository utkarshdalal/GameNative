package app.gamenative.service.ea

import java.io.File
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EaHelperArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private val executable = "MZ-test-ea-helper".toByteArray()

    private fun archive(name: String = "eastub.exe"): File {
        val output = temporary.newFile()
        output.outputStream().use { raw ->
            ZstdCompressorOutputStream(raw).use { zstd ->
                TarArchiveOutputStream(zstd).use { tar ->
                    tar.putArchiveEntry(TarArchiveEntry(name).apply { size = executable.size.toLong() })
                    tar.write(executable)
                    tar.closeArchiveEntry()
                }
            }
        }
        return output
    }

    @Test
    fun `installs helper onto disk and overwrites whatever is there on the next launch`() {
        val archive = archive()
        val drive = temporary.newFolder()
        EaHelperArchive.install(archive, drive)
        val target = File(drive, EaHelperArchive.PREFIX_PATH)
        assertArrayEquals(executable, target.readBytes())
        target.writeText("stale helper")
        EaHelperArchive.install(archive, drive)
        assertArrayEquals(executable, target.readBytes())
    }

    @Test
    fun `an archive without eastub is rejected`() {
        val archive = archive("../escape.exe")
        val drive = temporary.newFolder()
        assertThrows(IllegalStateException::class.java) { EaHelperArchive.install(archive, drive) }
        assertFalse(File(drive.parentFile, "escape.exe").exists())
    }
}
