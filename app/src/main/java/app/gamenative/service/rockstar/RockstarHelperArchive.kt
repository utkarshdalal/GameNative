package app.gamenative.service.rockstar

import android.content.Context
import app.gamenative.service.SteamService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Properties

/** Versioned helper cache. Does not overwrite game executables, mods, INIs or credentials. */
object RockstarHelperArchive {
    const val VERSION = "20260912.1"
    const val ARCHIVE = "rgschost-$VERSION.tzst"
    internal const val ARCHIVE_SHA256 = "b5b043f5137736440d09e712d07193de0085a6c5e52f081619030fc51a692fd8"
    private const val MANIFEST = "manifest.properties"
    private val binaries = setOf(
        "rgscstub.exe", "scpatch.dll", "bink2w64.dll",
        "SocialClubD3D12Renderer.dll", "SocialClubVulkanLayer.dll",
    )
    private val entries = binaries + setOf(MANIFEST, "NOTICE.txt")

    fun directory(filesDir: File) = File(filesDir, "rockstar/rgschost-$VERSION")

    /** Detect the installed Rockstar SDK/metadata, not the publisher or a guessed app-ID list. */
    fun usesRockstar(gameDir: File): Boolean {
        if (!gameDir.isDirectory) return false
        val names = gameDir.listFiles().orEmpty().associateBy { it.name.lowercase() }
        val metadata = names["title.rgl"]
        if (metadata != null && runCatching {
                metadata.inputStream().use { input ->
                    val magic = ByteArray(4)
                    input.read(magic) == 4 && magic.contentEquals(byteArrayOf(82, 71, 76, 77))
                }
            }.getOrDefault(false)
        ) return true
        return listOf("socialclub.dll", "socialclub64.dll").any { names[it]?.isFile == true }
    }

    internal fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun isReady(filesDir: File): Boolean = validate(directory(filesDir))

    suspend fun downloadAndExtract(context: Context, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        if (isReady(context.filesDir)) return@withContext directory(context.filesDir)
        val archive = File(context.filesDir, ARCHIVE)
        if (!archive.isFile || sha256(archive) != ARCHIVE_SHA256) {
            SteamService.fetchFileWithFallback(ARCHIVE, archive, context, onProgress)
        }
        install(archive, context.filesDir)
    }

    internal fun install(archive: File, filesDir: File, archiveHash: String = ARCHIVE_SHA256): File {
        check(archive.isFile && sha256(archive) == archiveHash) { "Rockstar helper download failed integrity verification" }
        return ensureExtracted(filesDir) { archive.inputStream() }
    }

    private fun validate(dir: File): Boolean = runCatching {
        val manifest = Properties().apply { File(dir, MANIFEST).inputStream().use { load(it) } }
        manifest.getProperty("version") == VERSION && File(dir, "NOTICE.txt").isFile && binaries.all { name ->
            val file = File(dir, name)
            val hash = manifest.getProperty(name).orEmpty()
            file.isFile && file.length() in 2..16L * 1024 * 1024 &&
                hash.matches(Regex("[a-f0-9]{64}")) && sha256(file) == hash
        }
    }.getOrDefault(false)

    /** Extract on first relevant launch or after a version change/corrupt-cache repair. */
    @Synchronized
    fun ensureExtracted(filesDir: File, openArchive: () -> InputStream): File {
        val target = directory(filesDir)
        if (validate(target)) return target
        val parent = target.parentFile!!
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create Rockstar helper cache" }
        val stage = kotlin.io.path.createTempDirectory(parent.toPath(), "extract-").toFile()
        try {
            val seen = mutableSetOf<String>()
            openArchive().use { raw ->
                ZstdCompressorInputStream(raw).use { zstd ->
                    TarArchiveInputStream(zstd).use { tar ->
                        while (true) {
                            val entry = tar.nextTarEntry ?: break
                            val name = entry.name
                            check(name in entries && seen.add(name) && entry.isFile &&
                                !entry.isSymbolicLink && !entry.isLink && entry.size in 1..16L * 1024 * 1024
                            ) { "Invalid Rockstar helper archive entry" }
                            val output = File(stage, name)
                            output.outputStream().use { check(tar.copyTo(it) == entry.size) { "Incomplete Rockstar helper" } }
                        }
                    }
                }
            }
            check(seen == entries && validate(stage)) { "Rockstar helper archive failed integrity verification" }
            // Only our private, version-specific derived cache can be replaced here.
            if (target.exists()) check(target.deleteRecursively()) { "Cannot repair Rockstar helper cache" }
            check(stage.renameTo(target)) { "Cannot finish Rockstar helper extraction" }
            return target
        } finally {
            if (stage.exists()) stage.deleteRecursively()
        }
    }
}
