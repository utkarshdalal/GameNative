package app.gamenative.service.rockstar

import android.content.Context
import java.io.File
import timber.log.Timber

/**
 * The Social Club runtime the game's own library needs. Rockstar ships its installer with every
 * title as a 7z self-extractor under Redistributables; the x64 payload is unpacked into the prefix
 * by the bundled decoder, so nothing runs under Wine and nothing is downloaded.
 */
object RockstarRuntime {
    const val SOCIAL_CLUB_DIR = "Program Files/Rockstar Games/Social Club"
    const val SOCIAL_CLUB_X86_DIR = "Program Files (x86)/Rockstar Games/Social Club"
    private val installers = listOf("Redistributables/Social-Club-Setup.exe", "Installers/Social-Club-Setup.exe")
    private val signature = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)

    fun interface Extractor {
        /** Unpacks the payload at one archive offset into [stage]; false when no archive is there. */
        fun extract(installer: File, offset: Long, stage: File, onProgress: (Float) -> Unit): Boolean
    }

    fun socialClubDir(prefixDriveC: File) = File(prefixDriveC, SOCIAL_CLUB_DIR)

    fun socialClubX86Dir(prefixDriveC: File) = File(prefixDriveC, SOCIAL_CLUB_X86_DIR)

    fun isInstalled(prefixDriveC: File) = File(socialClubDir(prefixDriveC), "socialclub.dll").isFile

    fun installer(installDir: File): File? =
        listOf(installDir, RockstarHelperArchive.titleDir(installDir)).distinct()
            .flatMap { dir -> installers.map { File(dir, it) } }
            .firstOrNull { it.isFile }

    fun install(context: Context, installer: File, prefixDriveC: File, onProgress: (Float) -> Unit = {}) =
        install(installer, prefixDriveC, nativeExtractor(context, "x64/"), onProgress)

    fun ensureX86(context: Context, installer: File?, prefixDriveC: File, onProgress: (Float) -> Unit = {}) {
        if (installer == null || File(socialClubX86Dir(prefixDriveC), "socialclub.dll").isFile) return
        runCatching { unpack(installer, socialClubX86Dir(prefixDriveC), nativeExtractor(context, "x86/"), onProgress) }
            .onFailure { Timber.w(it, "Rockstar: x86 Social Club runtime not installed") }
    }

    internal fun install(installer: File, prefixDriveC: File, extractor: Extractor, onProgress: (Float) -> Unit = {}) =
        unpack(installer, socialClubDir(prefixDriveC), extractor, onProgress)

    private fun unpack(installer: File, target: File, extractor: Extractor, onProgress: (Float) -> Unit) {
        val offsets = signatureOffsets(installer)
        check(offsets.isNotEmpty()) { "${installer.name} is not a 7z self-extractor" }
        val stage = File(target.parentFile, "Social Club.installing")
        stage.deleteRecursively()
        check(stage.mkdirs()) { "Cannot create the Social Club directory" }
        val extracted = offsets.any { offset ->
            stage.listFiles()?.forEach { it.deleteRecursively() }
            extractor.extract(installer, offset, stage, onProgress)
        }
        check(extracted) { "No readable archive inside ${installer.name}" }
        check(File(stage, "socialclub.dll").isFile) { "The Social Club installer has no x64 runtime" }
        check(target.isDirectory || target.mkdirs()) { "Cannot create the Social Club directory" }
        stage.walkTopDown().filter { it.isFile }.forEach { file ->
            val dest = File(target, file.relativeTo(stage).path)
            dest.parentFile!!.mkdirs()
            file.copyTo(dest, overwrite = true)
        }
        stage.deleteRecursively()
        Timber.i("Rockstar: Social Club runtime installed from ${installer.name}")
    }

    private fun nativeExtractor(context: Context, payload: String) = Extractor { installer, offset, stage, onProgress ->
        val tool = File(context.applicationInfo.nativeLibraryDir, "lib7zx.so")
        check(tool.isFile) { "The archive decoder is missing from this build" }
        val process = ProcessBuilder(tool.path, installer.path, offset.toString(), stage.path, payload)
            .redirectErrorStream(true).start()
        val output = StringBuilder()
        process.inputStream.bufferedReader().forEachLine { line ->
            if (line.startsWith("P ")) {
                val (done, total) = line.substring(2).split(' ').map { it.toLongOrNull() ?: 0L }
                if (total > 0) onProgress(done.toFloat() / total)
            } else if (output.length < 4000) output.appendLine(line)
        }
        when (val code = process.waitFor()) {
            0 -> true
            4 -> false
            else -> error("Decoder failed ($code): ${output.toString().trim()}")
        }
    }

    internal fun signatureOffsets(file: File): List<Long> {
        val found = ArrayList<Long>()
        file.inputStream().buffered(1 shl 20).use { input ->
            val chunk = ByteArray(1 shl 20)
            var carry = ByteArray(0)
            var base = 0L
            while (true) {
                val n = input.read(chunk)
                if (n <= 0) break
                val buf = carry + chunk.copyOf(n)
                var i = indexOf(buf, signature, 0)
                while (i >= 0) {
                    found.add(base - carry.size + i)
                    i = indexOf(buf, signature, i + 1)
                }
                val keep = minOf(signature.size - 1, buf.size)
                carry = buf.copyOfRange(buf.size - keep, buf.size)
                base += n
            }
        }
        return found
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        var i = from
        while (i + needle.size <= haystack.size) {
            var k = 0
            while (k < needle.size && haystack[i + k] == needle[k]) k++
            if (k == needle.size) return i
            i++
        }
        return -1
    }
}
