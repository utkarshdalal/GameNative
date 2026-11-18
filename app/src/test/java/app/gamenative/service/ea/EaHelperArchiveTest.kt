package app.gamenative.service.ea

import java.io.File
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EaHelperArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private val executable = "MZ-test-ea-helper".toByteArray()
    private val config = "ea.client.secret = test-secret\nea.license.key = 000102030405060708090a0b0c0d0e0f\n".toByteArray()

    private fun archive(entries: Map<String, ByteArray> = mapOf("NOTICE.txt" to "fixture".toByteArray(), "eastub.exe" to executable, "ea-helper.properties" to config)): File {
        val output = temporary.newFile()
        output.outputStream().use { raw ->
            ZstdCompressorOutputStream(raw).use { zstd ->
                TarArchiveOutputStream(zstd).use { tar ->
                    for ((name, bytes) in entries) {
                        tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
                        tar.write(bytes)
                        tar.closeArchiveEntry()
                    }
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
        val archive = archive(mapOf("../escape.exe" to executable))
        val drive = temporary.newFolder()
        assertThrows(IllegalStateException::class.java) { EaHelperArchive.install(archive, drive) }
        assertFalse(File(drive.parentFile, "escape.exe").exists())
    }

    @Test
    fun `the helper configuration comes from the archive`() {
        val files = temporary.newFolder()
        EaHelperConfig.reset()
        assertThrows(IllegalStateException::class.java) { EaHelperConfig.value(files, "ea.client.secret") }
        EaHelperArchive.extractConfig(archive(), files)
        assertEquals("test-secret", EaHelperConfig.value(files, "ea.client.secret"))
        assertArrayEquals(ByteArray(16) { it.toByte() }, EaHelperConfig.licenseKey(files))
        EaHelperConfig.reset()
    }
}
