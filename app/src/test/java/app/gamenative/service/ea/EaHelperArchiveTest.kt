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

    private fun binaryHash(): String = temporary.newFile().let {
        it.writeBytes(executable)
        EaHelperArchive.sha256(it)
    }

    @Test
    fun `installs helper onto disk after Steam cleanup and restores it after another cleanup`() {
        val archive = archive()
        val drive = temporary.newFolder()
        val hash = binaryHash()
        EaHelperArchive.install(archive, drive, EaHelperArchive.sha256(archive), hash)
        val target = File(drive, EaHelperArchive.PREFIX_PATH)
        assertArrayEquals(executable, target.readBytes())
        target.delete() // The Steam tree refresh removes old executables before every launch.
        EaHelperArchive.install(archive, drive, EaHelperArchive.sha256(archive), hash)
        assertArrayEquals(executable, target.readBytes())
    }

    @Test
    fun `corrupt download does not replace an installed helper`() {
        val archive = archive()
        val drive = temporary.newFolder()
        val target = File(drive, EaHelperArchive.PREFIX_PATH)
        target.parentFile!!.mkdirs()
        target.writeText("previous-helper")
        assertThrows(IllegalStateException::class.java) {
            EaHelperArchive.install(archive, drive, "wrong-hash", binaryHash())
        }
        assertArrayEquals("previous-helper".toByteArray(), target.readBytes())
    }

    @Test
    fun `unexpected archive paths cannot write outside the helper destination`() {
        val archive = archive("../escape.exe")
        val drive = temporary.newFolder()
        assertThrows(IllegalStateException::class.java) {
            EaHelperArchive.install(archive, drive, EaHelperArchive.sha256(archive), binaryHash())
        }
        assertFalse(File(drive, EaHelperArchive.PREFIX_PATH).exists())
        assertFalse(File(drive.parentFile, "escape.exe").exists())
    }
}
