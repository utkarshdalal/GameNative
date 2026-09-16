package app.gamenative.service.rockstar

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.StandardOpenOption
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import timber.log.Timber

/**
 * The Social Club runtime the game's own library needs. Rockstar ships its installer with every
 * title as a 7z self-extractor under Redistributables; the x64 payload goes into the prefix.
 */
object RockstarRuntime {
    const val SOCIAL_CLUB_DIR = "Program Files/Rockstar Games/Social Club"
    private const val PAYLOAD = "x64/"
    private val installers = listOf("Redistributables/Social-Club-Setup.exe", "Installers/Social-Club-Setup.exe")
    private val signature = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)

    fun socialClubDir(prefixDriveC: File) = File(prefixDriveC, SOCIAL_CLUB_DIR)

    fun isInstalled(prefixDriveC: File) = File(socialClubDir(prefixDriveC), "socialclub.dll").isFile

    fun installer(gameDir: File): File? = installers.map { File(gameDir, it) }.firstOrNull { it.isFile }

    fun install(installer: File, prefixDriveC: File, onProgress: (Float) -> Unit = {}) {
        val target = socialClubDir(prefixDriveC)
        val stage = File(target.parentFile, "Social Club.installing")
        stage.deleteRecursively()
        check(stage.mkdirs()) { "Cannot create the Social Club directory" }
        openArchive(installer).use { archive ->
            val total = archive.entries.filter { !it.isDirectory && it.name.startsWith(PAYLOAD) }.sumOf { it.size }.coerceAtLeast(1)
            var done = 0L
            while (true) {
                val entry = archive.nextEntry ?: break
                if (entry.isDirectory || !entry.name.startsWith(PAYLOAD)) continue
                val relative = entry.name.removePrefix(PAYLOAD)
                check(relative.isNotEmpty() && relative.split('/').none { it == ".." || it.isEmpty() }) { "Unexpected installer entry ${entry.name}" }
                val out = File(stage, relative)
                check(out.parentFile!!.isDirectory || out.parentFile!!.mkdirs()) { "Cannot create ${out.parent}" }
                archive.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
                done += entry.size
                onProgress(done.toFloat() / total)
            }
        }
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

    /** The self-extractor is a PE stub followed by a plain 7z archive; find where the archive starts. */
    internal fun openArchive(file: File): SevenZFile {
        val offsets = signatureOffsets(file)
        check(offsets.isNotEmpty()) { "${file.name} is not a 7z self-extractor" }
        var last: Exception? = null
        for (offset in offsets) {
            val channel = FileChannel.open(file.toPath(), StandardOpenOption.READ)
            try {
                return SevenZFile.builder().setSeekableByteChannel(OffsetChannel(channel, offset)).get()
            } catch (e: Exception) {
                channel.close()
                last = e
            }
        }
        throw IllegalStateException("No readable 7z archive inside ${file.name}", last)
    }

    private fun signatureOffsets(file: File): List<Long> {
        val found = ArrayList<Long>()
        file.inputStream().buffered(1 shl 20).use { input ->
            val chunk = ByteArray(1 shl 20)
            var carry = ByteArray(0)
            var base = 0L
            while (true) {
                val n = input.read(chunk)
                if (n <= 0) break
                val buf = carry + chunk.copyOf(n)
                var i = 0
                while (true) {
                    i = indexOf(buf, signature, i)
                    if (i < 0) break
                    found.add(base - carry.size + i)
                    i++
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

    private class OffsetChannel(private val inner: FileChannel, private val offset: Long) : SeekableByteChannel {
        init { inner.position(offset) }
        override fun read(dst: ByteBuffer): Int = inner.read(dst)
        override fun write(src: ByteBuffer): Int = throw UnsupportedOperationException()
        override fun position(): Long = inner.position() - offset
        override fun position(newPosition: Long): SeekableByteChannel = apply { inner.position(offset + newPosition) }
        override fun size(): Long = inner.size() - offset
        override fun truncate(size: Long): SeekableByteChannel = throw UnsupportedOperationException()
        override fun isOpen(): Boolean = inner.isOpen
        override fun close() = inner.close()
    }
}
