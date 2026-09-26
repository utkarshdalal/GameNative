package app.gamenative.service.rockstar

import java.io.ByteArrayOutputStream
import java.io.File
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream

/** Unit tests need no shipped binaries; a downloaded release can also be tested explicitly. */
internal fun rockstarTestArchive(): ByteArray {
    System.getenv("ROCKSTAR_TEST_ARCHIVE")?.let { return File(it).readBytes() }
    val binaries = listOf(
        "rgscstub.exe", "scpatch.dll", "bink2w64.dll", "rgscstub32.exe", "scpatch32.dll", "binkw32.dll",
        "SocialClubD3D12Renderer.dll", "SocialClubVulkanLayer.dll",
    )
        .associateWith { "MZ-test-$it".toByteArray() }
    val entries = binaries + mapOf(
        "NOTICE.txt" to "Test fixture".toByteArray(),
        RockstarHelperArchive.SIGNIN_SHIM to "bridge=@BRIDGE@ title=@TITLE@ fp=@FP@".toByteArray(),
    )
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
