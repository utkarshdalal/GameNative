package app.gamenative.service.rockstar

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream

/** Unit tests need no shipped binaries; a downloaded release can also be tested explicitly. */
internal fun rockstarTestArchive(): ByteArray {
    System.getenv("ROCKSTAR_TEST_ARCHIVE")?.let { return File(it).readBytes() }
    val binaries = listOf("rgscstub.exe", "scpatch.dll", "bink2w64.dll", "SocialClubD3D12Renderer.dll", "SocialClubVulkanLayer.dll")
        .associateWith { "MZ-test-$it".toByteArray() }
    val manifest = "version=${RockstarHelperArchive.VERSION}\n" + binaries.entries.joinToString("") { (name, bytes) ->
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        "$name=$hash\n"
    }
    val entries = binaries + mapOf("manifest.properties" to manifest.toByteArray(), "NOTICE.txt" to "Test fixture".toByteArray())
    val output = ByteArrayOutputStream()
    ZstdCompressorOutputStream(output).use { zstd ->
        TarArchiveOutputStream(zstd).use { tar ->
            for ((name, bytes) in entries) {
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
    }
    return output.toByteArray()
}
