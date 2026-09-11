package app.gamenative.service.ea

import android.content.Context
import app.gamenative.service.SteamService
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream

/** Downloaded separately from Steam and installed after the Steam tree is refreshed. */
object EaHelperArchive {
    const val ARCHIVE = "eahost-20260911.tzst"
    internal const val ARCHIVE_SHA256 = "6a6e387d0b90afdbcb7b613583d145ab4ed8d39c817bb1452fc91aaa234a5236"
    internal const val BINARY_SHA256 = "a6b3fb1246a8d2b26b1f55f15e723104b6290096bb937a03ce9a12a291e00550"
    internal const val PREFIX_PATH = "Program Files (x86)/Steam/eastub.exe"

    internal fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun download(context: Context, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val archive = File(context.filesDir, ARCHIVE)
        if (!archive.isFile || sha256(archive) != ARCHIVE_SHA256) {
            SteamService.fetchFileWithFallback(ARCHIVE, archive, context, onProgress)
        }
        check(sha256(archive) == ARCHIVE_SHA256) { "EA helper download failed integrity verification" }
    }

    fun install(context: Context, prefixDriveC: File) = install(File(context.filesDir, ARCHIVE), prefixDriveC)

    internal fun install(
        archive: File,
        prefixDriveC: File,
        archiveHash: String = ARCHIVE_SHA256,
        binaryHash: String = BINARY_SHA256,
    ) {
        val target = File(prefixDriveC, PREFIX_PATH)
        if (target.isFile && sha256(target) == binaryHash) return
        check(archive.isFile && sha256(archive) == archiveHash) { "EA helper archive is missing or corrupt" }
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "Cannot create EA helper directory" }
        val stage = File.createTempFile(".eastub-", ".exe", target.parentFile)
        try {
            archive.inputStream().use { raw ->
                ZstdCompressorInputStream(raw).use { zstd ->
                    TarArchiveInputStream(zstd).use { tar ->
                        val entry = checkNotNull(tar.nextTarEntry) { "Empty EA helper archive" }
                        check(entry.name == "eastub.exe" && entry.isFile && !entry.isLink &&
                            !entry.isSymbolicLink && entry.size in 2..1024L * 1024) { "Invalid EA helper archive entry" }
                        stage.outputStream().use { check(tar.copyTo(it) == entry.size) { "Incomplete EA helper" } }
                        check(tar.nextTarEntry == null) { "Unexpected extra EA helper archive entry" }
                    }
                }
            }
            check(sha256(stage) == binaryHash) { "EA helper executable failed integrity verification" }
            check(stage.setExecutable(true, true)) { "Cannot set EA helper permissions" }
            check(stage.renameTo(target)) { "Cannot install EA helper" }
        } finally {
            stage.delete()
        }
    }
}
