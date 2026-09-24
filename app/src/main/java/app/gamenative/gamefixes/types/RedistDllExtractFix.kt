package app.gamenative.gamefixes

import android.content.Context
import app.gamenative.data.GameSource
import com.winlator.container.Container
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import me.zhanghai.android.libarchive.ArchiveException
import timber.log.Timber

/**
 * Places a Microsoft runtime DLL next to the game executable, taken out of the redistributable
 * installer the game already ships under `_CommonRedist`.
 *
 * The installer is a bootstrapper with cabinets attached to it, and its payload cabinet holds a
 * second cabinet that carries the DLL, so both are sliced out by cabinet length and unpacked.
 */
class RedistDllExtractFix(
    private val installerRelativePath: String,
    private val cabinetEntryName: String,
    private val fileName: String,
    private val destinationRelativePath: String,
    private val extractor: CabEntryExtractor = LibarchiveCabEntryExtractor,
) : GameFix {
    override fun apply(
        context: Context,
        gameId: String,
        installPath: String,
        installPathWindows: String,
        container: Container,
    ): Boolean {
        val destination = File(installPath, destinationRelativePath)
        if (destination.exists()) return true

        val installer = File(installPath, installerRelativePath)
        if (!installer.isFile) return skipped(gameId, "'$installerRelativePath' is not in the game folder")

        val work = File(context.cacheDir, WORK_DIR_NAME)
        var partial: File? = null
        return try {
            work.deleteRecursively()
            if (!work.mkdirs()) return skipped(gameId, "cannot create '${work.absolutePath}'")

            val payload = CabArchive.locate(installer, cabinetEntryName)
                ?: return skipped(gameId, "no cabinet holding '$cabinetEntryName' inside '${installer.name}'")
            val payloadCabinet = File(work, PAYLOAD_CABINET_NAME)
            CabArchive.slice(installer, payload, payloadCabinet)

            val nested = File(work, "$cabinetEntryName.bin")
            if (!extractor.extract(payloadCabinet, cabinetEntryName, nested)) {
                return skipped(gameId, "'$cabinetEntryName' could not be unpacked from '${installer.name}'")
            }
            val nestedCabinet = CabArchive.locate(nested, fileName)
                ?: return skipped(gameId, "no cabinet holding '$fileName' inside '$cabinetEntryName'")
            val sliced = File(work, "$cabinetEntryName.cab")
            CabArchive.slice(nested, nestedCabinet, sliced)

            destination.parentFile?.mkdirs()
            partial = File.createTempFile(destination.name, ".part", destination.parentFile)
            if (!extractor.extract(sliced, fileName, partial)) {
                return skipped(gameId, "'$fileName' could not be unpacked from '$cabinetEntryName'")
            }
            val extractedBytes = partial.length()
            if (!partial.renameTo(destination)) {
                return skipped(gameId, "cannot move '$fileName' to '${destination.absolutePath}'")
            }
            Timber.tag("GameFixes").i("Extracted '$destinationRelativePath' ($extractedBytes bytes) for game $gameId")
            true
        } catch (e: Exception) {
            skipped(gameId, "unpacking '$fileName' from '$installerRelativePath' failed", e)
        } finally {
            work.deleteRecursively()
            partial?.delete()
        }
    }

    private fun skipped(gameId: String, reason: String, error: Throwable? = null): Boolean {
        val message = "Skipping '$destinationRelativePath' for game $gameId: $reason"
        if (error != null) {
            Timber.tag("GameFixes").w(error, message)
        } else {
            Timber.tag("GameFixes").w(message)
        }
        return false
    }

    private companion object {
        const val WORK_DIR_NAME = "gamefix-redist-dll"
        const val PAYLOAD_CABINET_NAME = "payload.cab"
    }
}

class KeyedRedistDllExtractFix(
    override val gameSource: GameSource,
    override val gameId: String,
    installerRelativePath: String,
    cabinetEntryName: String,
    fileName: String,
    destinationRelativePath: String,
) : KeyedGameFix,
    GameFix by RedistDllExtractFix(
        installerRelativePath,
        cabinetEntryName,
        fileName,
        destinationRelativePath,
    )

fun interface CabEntryExtractor {
    /** Writes the entry named [entryName] of [cabinet] to [destination]; false when the cabinet has no such entry. */
    fun extract(cabinet: File, entryName: String, destination: File): Boolean
}

internal object LibarchiveCabEntryExtractor : CabEntryExtractor {
    private const val BLOCK_BYTES = 1024 * 1024

    override fun extract(cabinet: File, entryName: String, destination: File): Boolean {
        val archive = try {
            Archive.readNew()
        } catch (e: LinkageError) {
            throw IOException("Cabinet extraction is not available on this device", e)
        }
        try {
            Archive.setCharset(archive, Charsets.UTF_8.name().toByteArray(Charsets.UTF_8))
            Archive.readSupportFilterAll(archive)
            Archive.readSupportFormatCab(archive)
            Archive.readOpenFileName(archive, cabinet.absolutePath.toByteArray(Charsets.UTF_8), BLOCK_BYTES.toLong())
            while (true) {
                val entry = try {
                    Archive.readNextHeader(archive)
                } catch (e: ArchiveException) {
                    if (e.code == Archive.ERRNO_EOF) break else throw e
                }
                if (entry == 0L) break
                if (!leafName(entry).equals(entryName, ignoreCase = true)) continue
                FileOutputStream(destination).use { output -> copyEntry(archive, output) }
                return true
            }
            return false
        } finally {
            runCatching { Archive.readClose(archive) }
            runCatching { Archive.readFree(archive) }
        }
    }

    private fun leafName(entry: Long): String {
        val name = ArchiveEntry.pathnameUtf8(entry)
            ?: ArchiveEntry.pathname(entry)?.toString(Charsets.ISO_8859_1)
            ?: return ""
        return name.replace('\\', '/').substringAfterLast('/')
    }

    private fun copyEntry(archive: Long, output: FileOutputStream) {
        val buffer = ByteBuffer.allocateDirect(BLOCK_BYTES)
        while (true) {
            buffer.clear()
            try {
                Archive.readData(archive, buffer)
            } catch (e: ArchiveException) {
                if (e.code == Archive.ERRNO_EOF) break else throw e
            }
            if (buffer.position() <= 0) break
            buffer.flip()
            while (buffer.hasRemaining()) {
                output.channel.write(buffer)
            }
        }
    }
}

/**
 * Finds Microsoft cabinets embedded in an arbitrary file and copies one out whole.
 *
 * Only the CFHEADER and its file table are read: `cbCabinet` gives the length to slice, which also
 * drops whatever an installer appended after the cabinet stream.
 */
internal object CabArchive {
    private val SIGNATURE = "MSCF".toByteArray(Charsets.US_ASCII)
    private const val HEADER_BYTES = 36
    private const val ENTRY_HEADER_BYTES = 16
    private const val MAX_NAME_BYTES = 256
    private const val MAX_TABLE_BYTES = 256 * 1024
    private const val SCAN_CHUNK_BYTES = 1 shl 20
    private const val COPY_CHUNK_BYTES = 1 shl 16

    data class Location(val offset: Long, val length: Long, val entryNames: List<String>)

    fun locate(file: File, entryName: String): Location? = RandomAccessFile(file, "r").use { source ->
        val length = source.length()
        signatureOffsets(source, length)
            .asSequence()
            .mapNotNull { offset -> header(source, offset, length) }
            .firstOrNull { location -> location.entryNames.any { it.equals(entryName, ignoreCase = true) } }
    }

    fun slice(source: File, location: Location, destination: File) {
        RandomAccessFile(source, "r").use { input ->
            input.seek(location.offset)
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(COPY_CHUNK_BYTES)
                var remaining = location.length
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                    if (read <= 0) throw IOException("Cabinet at ${location.offset} in ${source.name} ends early")
                    output.write(buffer, 0, read)
                    remaining -= read
                }
            }
        }
    }

    private fun signatureOffsets(source: RandomAccessFile, length: Long): List<Long> {
        if (length < HEADER_BYTES) return emptyList()
        val offsets = ArrayList<Long>()
        val chunk = ByteArray(SCAN_CHUNK_BYTES)
        var carry = ByteArray(0)
        var base = 0L
        source.seek(0)
        while (true) {
            val read = source.read(chunk)
            if (read <= 0) break
            val buffer = carry + chunk.copyOf(read)
            var index = indexOf(buffer, SIGNATURE, 0)
            while (index >= 0) {
                offsets.add(base - carry.size + index)
                index = indexOf(buffer, SIGNATURE, index + 1)
            }
            val keep = minOf(SIGNATURE.size - 1, buffer.size)
            carry = buffer.copyOfRange(buffer.size - keep, buffer.size)
            base += read
        }
        return offsets
    }

    private fun header(source: RandomAccessFile, offset: Long, fileLength: Long): Location? {
        if (offset + HEADER_BYTES > fileLength) return null
        val header = ByteArray(HEADER_BYTES)
        source.seek(offset)
        source.readFully(header)

        val cabinetBytes = readU32(header, 8)
        val tableOffset = readU32(header, 16)
        val versionMinor = header[24].toInt() and 0xFF
        val versionMajor = header[25].toInt() and 0xFF
        val fileCount = readU16(header, 28)
        if (versionMajor != 1 || versionMinor != 3) return null
        if (cabinetBytes < HEADER_BYTES || offset + cabinetBytes > fileLength) return null
        if (fileCount < 1) return null
        if (tableOffset < HEADER_BYTES || tableOffset >= cabinetBytes) return null

        val tableBytes = minOf(
            cabinetBytes - tableOffset,
            fileCount.toLong() * (ENTRY_HEADER_BYTES + MAX_NAME_BYTES + 1),
            MAX_TABLE_BYTES.toLong(),
        ).toInt()
        val table = ByteArray(tableBytes)
        source.seek(offset + tableOffset)
        source.readFully(table)
        return Location(offset, cabinetBytes, entryNames(table, fileCount))
    }

    private fun entryNames(table: ByteArray, fileCount: Int): List<String> {
        val names = ArrayList<String>(minOf(fileCount, 64))
        var cursor = 0
        repeat(fileCount) {
            val start = cursor + ENTRY_HEADER_BYTES
            if (start >= table.size) return names
            var end = start
            while (end < table.size && table[end] != 0.toByte()) end++
            if (end >= table.size || end - start > MAX_NAME_BYTES) return names
            names.add(String(table, start, end - start, Charsets.ISO_8859_1))
            cursor = end + 1
        }
        return names
    }

    private fun readU32(bytes: ByteArray, index: Int): Long =
        (bytes[index].toLong() and 0xFF) or
            ((bytes[index + 1].toLong() and 0xFF) shl 8) or
            ((bytes[index + 2].toLong() and 0xFF) shl 16) or
            ((bytes[index + 3].toLong() and 0xFF) shl 24)

    private fun readU16(bytes: ByteArray, index: Int): Int =
        (bytes[index].toInt() and 0xFF) or ((bytes[index + 1].toInt() and 0xFF) shl 8)

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
