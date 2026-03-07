package app.gamenative.service.amazon

import org.tukaani.xz.LZMAInputStream
import org.tukaani.xz.XZInputStream
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Amazon game manifest parser. */
object AmazonManifest {

    // ── Public data model ────────────────────────────────────────────────────

    data class ManifestFile(
        val path: String,
        val size: Long,
        /** 0 = sha256, 1 = shake128. */
        val hashAlgorithm: Int,
        val hashBytes: ByteArray,
    ) {
        /** Returns the path with backslashes replaced. */
        val unixPath: String get() = path.replace('\\', '/')
    }

    data class ManifestPackage(
        val name: String,
        val files: List<ManifestFile>,
    )

    data class ParsedManifest(
        val packages: List<ManifestPackage>,
    ) {
        val allFiles: List<ManifestFile> get() = packages.flatMap { it.files }
        /** Sum of all file sizes. */
        val totalInstallSize: Long get() = allFiles.sumOf { it.size }
    }

    // ── Entry point ──────────────────────────────────────────────────────────

    /** Parse an Amazon manifest binary. */
    fun parse(content: ByteArray): ParsedManifest {
        require(content.size > 4) { "Manifest too short: ${content.size} bytes" }

        val buf = ByteBuffer.wrap(content).order(ByteOrder.BIG_ENDIAN)
        val headerSize = buf.int   // big-endian uint32

        require(headerSize >= 0 && headerSize < content.size) {
            "Invalid header size: $headerSize"
        }

        val headerBytes = ByteArray(headerSize)
        buf.get(headerBytes)

        val bodyBytes = ByteArray(buf.remaining())
        buf.get(bodyBytes)

        // Parse header just to learn the compression algorithm
        val compressionAlgorithm = parseCompressionAlgorithm(headerBytes)
        Timber.d("[Amazon] Manifest: compressionAlgorithm=$compressionAlgorithm headerSize=$headerSize bodySize=${bodyBytes.size}")

        val manifestBytes = when (compressionAlgorithm) {
            1 -> decompressLzma(bodyBytes)   // lzma
            else -> bodyBytes                // none
        }

        return parseManifest(manifestBytes)
    }

    // ── Protobuf binary helpers ──────────────────────────────────────────────

    /** Read a protobuf varint from [stream]. */
    private fun readVarint(stream: java.io.InputStream): Long {
        var result = 0L
        var shift = 0
        var isFirst = true
        while (true) {
            val b = stream.read()
            if (b == -1) return if (isFirst) -1L else error("Unexpected EOF in varint")
            isFirst = false
            result = result or ((b.toLong() and 0x7F) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 63) error("Varint too large")
        }
    }

    /** Read a length-delimited field. */
    private fun readLengthDelimited(stream: java.io.InputStream): ByteArray {
        val len = readVarint(stream).toInt()
        require(len >= 0) { "Negative length-delimited size" }
        val bytes = ByteArray(len)
        var offset = 0
        while (offset < len) {
            val read = stream.read(bytes, offset, len - offset)
            check(read != -1) { "EOF while reading length-delimited field (expected $len bytes, got $offset)" }
            offset += read
        }
        return bytes
    }

    /** Skip a field of the given wire type. */
    private fun skipField(stream: java.io.InputStream, wireType: Int) {
        when (wireType) {
            0 -> readVarint(stream)
            1 -> repeat(8) { stream.read() }  // 64-bit fixed
            2 -> readLengthDelimited(stream)
            5 -> repeat(4) { stream.read() }  // 32-bit fixed
            else -> error("Unknown wire type: $wireType")
        }
    }

    // ── ManifestHeader: extract compression algorithm ────────────────────────
    //
    // message ManifestHeader {
    //   required CompressionSettings compression = 1;  // field 1
    //   required Hash hash                        = 2;
    //   required Signature signature              = 3;
    // }
    // message CompressionSettings {
    //   required CompressionAlgorithm algorithm = 1;  // varint: 0=none, 1=lzma
    // }

    private fun parseCompressionAlgorithm(bytes: ByteArray): Int {
        val stream = ByteArrayInputStream(bytes)
        while (stream.available() > 0) {
            val tag = readVarint(stream)
            if (tag == -1L) break
            val fieldNumber = (tag ushr 3).toInt()
            val wireType = (tag and 0x7).toInt()
            if (fieldNumber == 1 && wireType == 2) {
                // CompressionSettings embedded message
                val inner = ByteArrayInputStream(readLengthDelimited(stream))
                val innerTag = readVarint(inner)
                if (innerTag != -1L && (innerTag ushr 3).toInt() == 1 && (innerTag and 0x7).toInt() == 0) {
                    return readVarint(inner).toInt()
                }
                return 0 // default: none
            } else {
                skipField(stream, wireType)
            }
        }
        return 0
    }

    // ── Manifest ─────────────────────────────────────────────────────────────
    //
    // message Manifest {
    //   repeated Package packages = 1;
    // }

    private fun parseManifest(bytes: ByteArray): ParsedManifest {
        val stream = ByteArrayInputStream(bytes)
        val packages = mutableListOf<ManifestPackage>()
        while (stream.available() > 0) {
            val tag = readVarint(stream)
            if (tag == -1L) break
            val fieldNumber = (tag ushr 3).toInt()
            val wireType = (tag and 0x7).toInt()
            if (fieldNumber == 1 && wireType == 2) {
                packages.add(parsePackage(readLengthDelimited(stream)))
            } else {
                skipField(stream, wireType)
            }
        }
        Timber.d("[Amazon] Manifest: ${packages.size} packages, ${packages.sumOf { it.files.size }} files")
        return ParsedManifest(packages)
    }

    // ── Package ───────────────────────────────────────────────────────────────
    //
    // message Package {
    //   required string name        = 1;
    //   repeated File   files       = 2;
    //   repeated Dir    dirs        = 3;  // ignored (we only care about files)
    // }

    private fun parsePackage(bytes: ByteArray): ManifestPackage {
        val stream = ByteArrayInputStream(bytes)
        var name = ""
        val files = mutableListOf<ManifestFile>()
        while (stream.available() > 0) {
            val tag = readVarint(stream)
            if (tag == -1L) break
            val fieldNumber = (tag ushr 3).toInt()
            val wireType = (tag and 0x7).toInt()
            when {
                fieldNumber == 1 && wireType == 2 ->
                    name = String(readLengthDelimited(stream), Charsets.UTF_8)
                fieldNumber == 2 && wireType == 2 ->
                    files.add(parseFile(readLengthDelimited(stream)))
                else -> skipField(stream, wireType)
            }
        }
        return ManifestPackage(name, files)
    }

    // ── File ──────────────────────────────────────────────────────────────────
    //
    // message File {
    //   required string path    = 1;
    //   required uint32 mode    = 2;
    //   required int64  size    = 3;
    //   required string created = 4;
    //   required Hash   hash    = 5;
    //   optional bool   hidden  = 6;
    //   optional bool   system  = 7;
    // }

    private fun parseFile(bytes: ByteArray): ManifestFile {
        val stream = ByteArrayInputStream(bytes)
        var path = ""
        var size = 0L
        var hashAlgo = 0
        var hashValue = ByteArray(0)
        while (stream.available() > 0) {
            val tag = readVarint(stream)
            if (tag == -1L) break
            val fieldNumber = (tag ushr 3).toInt()
            val wireType = (tag and 0x7).toInt()
            when {
                fieldNumber == 1 && wireType == 2 ->
                    path = String(readLengthDelimited(stream), Charsets.UTF_8)
                fieldNumber == 3 && wireType == 0 ->
                    size = readVarint(stream)
                fieldNumber == 5 && wireType == 2 -> {
                    val (algo, value) = parseHash(readLengthDelimited(stream))
                    hashAlgo = algo
                    hashValue = value
                }
                else -> skipField(stream, wireType)
            }
        }
        return ManifestFile(path, size, hashAlgo, hashValue)
    }

    // ── Hash ─────────────────────────────────────────────────────────────────
    //
    // message Hash {
    //   required HashAlgorithm algorithm = 1;  // 0=sha256, 1=shake128
    //   required bytes         value     = 2;
    // }

    private fun parseHash(bytes: ByteArray): Pair<Int, ByteArray> {
        val stream = ByteArrayInputStream(bytes)
        var algorithm = 0
        var value = ByteArray(0)
        while (stream.available() > 0) {
            val tag = readVarint(stream)
            if (tag == -1L) break
            val fieldNumber = (tag ushr 3).toInt()
            val wireType = (tag and 0x7).toInt()
            when {
                fieldNumber == 1 && wireType == 0 -> algorithm = readVarint(stream).toInt()
                fieldNumber == 2 && wireType == 2 -> value = readLengthDelimited(stream)
                else -> skipField(stream, wireType)
            }
        }
        return algorithm to value
    }

    // ── LZMA decompression ────────────────────────────────────────────────────

    private fun decompressLzma(bytes: ByteArray): ByteArray {
        Timber.d("[Amazon] Decompressing manifest (${bytes.size} bytes compressed)")
        // Python's lzma.decompress() uses the XZ container format by default.
        // Try XZ first; fall back to raw legacy LZMA if the XZ magic bytes are absent.
        return if (bytes.size >= 6 && bytes[0] == 0xFD.toByte() && bytes[1] == '7'.code.toByte()) {
            Timber.d("[Amazon] Using XZ decompression")
            XZInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        } else {
            Timber.d("[Amazon] Using raw LZMA decompression")
            LZMAInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        }
    }
}
