// Manifest layout and slice paths follow UplayKit and UplayApps (https://github.com/UplayDB), MIT.
package app.gamenative.service.ubisoft

import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.Inflater

class UbisoftUnsupportedException(message: String) : Exception(message)

/** A product build: its files, how their slices are stored, and the registry values the launcher writes. */
class UbisoftManifest(
    val version: Int,
    val compressed: Boolean,
    val compression: Compression,
    val chunks: List<Chunk>,
    val registry: List<RegistryValue>,
    val gameVersion: String,
    val languages: List<String>,
) {
    enum class Compression { DEFLATE, LZHAM, ZSTD, UNKNOWN }

    class Slice(val size: Long, val downloadSize: Long, val downloadSha1: String?, val fileOffset: Long?)

    /**
     * [slices] are the stored pieces in file order, with sizes. [pieceIds] is the older list: the
     * download ids when [slices] is empty, otherwise one more hash per piece.
     */
    class File(
        val name: String,
        val size: Long,
        val isDir: Boolean,
        val slices: List<Slice>,
        val pieceIds: List<String>,
    )

    class Chunk(val id: Int, val optional: Boolean, val language: String, val uplayIds: List<Long>, val files: List<File>)

    /** `HKEY_...\path\ValueName`; strings per language ("" = any), with `$INSTALL_DIR` for the game folder. */
    class RegistryValue(val key: String, val strings: Map<String, String>, val numbers: Map<String, Long>)

    /** The download path of a slice id; version 2 builds store slices in a layout nobody has described. */
    fun slicePath(id: String): String = when (version) {
        2 -> throw UbisoftUnsupportedException("this build uses manifest version 2, which is not supported yet")
        3 -> "slices_v3/${sliceFolder(id)}/$id"
        else -> "slices/$id"
    }

    fun requireSupported() {
        if (version == 2) throw UbisoftUnsupportedException("this build uses manifest version 2, which is not supported yet")
        if (compressed && compression == Compression.LZHAM) {
            throw UbisoftUnsupportedException("this build is LZHAM-compressed, which the app cannot unpack yet")
        }
        if (compressed && compression == Compression.UNKNOWN) throw UbisoftUnsupportedException("this build uses an unknown compression")
    }

    /** Base chunks plus the chunks of one language, limited to add-ons the account owns. */
    fun selectFiles(language: String?, ownedUplayIds: Set<Long>): List<File> {
        val lang = language?.lowercase(Locale.ROOT)
        val seen = HashSet<String>()
        return chunks
            .filter { c -> c.uplayIds.isEmpty() || c.uplayIds.any { it in ownedUplayIds } }
            .filter { c -> c.language.isEmpty() || c.language.lowercase(Locale.ROOT) == lang }
            .flatMap { it.files }
            .filter { seen.add(it.name.lowercase(Locale.ROOT)) }
    }

    /** The language to install: the device's, then its language family, then English, then the first listed. */
    fun pickLanguage(preferred: Locale): String? {
        val all = (languages + chunks.map { it.language }).filter { it.isNotEmpty() }.distinct()
        if (all.isEmpty()) return null
        val tag = preferred.toLanguageTag()
        return all.firstOrNull { it.equals(tag, true) }
            ?: all.firstOrNull { it.substringBefore('-').equals(preferred.language, true) }
            ?: all.firstOrNull { it.equals("en-US", true) }
            ?: all.firstOrNull { it.startsWith("en", true) }
            ?: all.first()
    }

    companion object {
        private val BASE32 = "0123456789abcdefghijklmnopqrstuv"

        /** The folder letter of a version 3 slice: from the first byte read with its nibbles swapped. */
        fun sliceFolder(id: String): Char {
            val low = Character.digit(id[0], 16)
            val high = Character.digit(id[1], 16)
            require(low >= 0 && high >= 0) { "bad slice id" }
            return BASE32[high + if (low % 2 == 1) 16 else 0]
        }

        /** The file is `int32 compression, int32 signature length, int32 body length`, the signature, then the zlib body. */
        fun parse(file: ByteArray): UbisoftManifest {
            require(file.size > 12) { "manifest file is too short" }
            val signatureLength = le32(file, 4)
            val start = 12 + signatureLength
            require(signatureLength in 0..(file.size - 12)) { "manifest signature length is out of range" }
            return parseBody(inflate(file, start))
        }

        internal fun parseBody(body: ByteArray): UbisoftManifest {
            val m = UbisoftDemuxProto.Message.parse(body)
            val compression = when (m.uint(21).toInt()) {
                1 -> Compression.DEFLATE
                2 -> Compression.LZHAM
                3 -> Compression.ZSTD
                else -> if (m.has(21)) Compression.UNKNOWN else Compression.DEFLATE
            }
            return UbisoftManifest(
                version = m.uint(22).toInt(),
                compressed = m.uint(17) != 0L,
                compression = compression,
                chunks = m.allMessages(4).map { chunk(it) },
                registry = m.allMessages(5).map { r ->
                    RegistryValue(
                        key = r.string(1).orEmpty(),
                        strings = r.allMessages(3).associate { (it.string(2).orEmpty()) to it.string(1).orEmpty() },
                        numbers = r.allMessages(4).associate { (it.string(2).orEmpty()) to it.uint(1) },
                    )
                },
                gameVersion = m.string(20).orEmpty(),
                languages = m.allMessages(23).mapNotNull { it.string(1) } + m.allBytes(13).map { String(it, Charsets.UTF_8) },
            )
        }

        private fun chunk(c: UbisoftDemuxProto.Message) = Chunk(
            id = c.uint(1).toInt(),
            optional = c.uint(2) == 1L,
            language = c.string(5).orEmpty(),
            uplayIds = (listOf(c.uint(4)) + c.uints(8)).filter { it != 0L }.distinct(),
            files = c.allMessages(3).map { f ->
                File(
                    name = normalizeName(f.string(1).orEmpty()),
                    size = f.uint(2),
                    isDir = f.uint(3) != 0L,
                    slices = f.allMessages(7).map { s ->
                        Slice(
                            size = s.uint(1),
                            downloadSize = s.uint(2),
                            downloadSha1 = s.bytes(3)?.let { hex(it) },
                            fileOffset = if (s.has(4)) s.uint(4) else null,
                        )
                    },
                    pieceIds = f.allBytes(4).map { hex(it) },
                )
            },
        )

        /** Forward slashes, no leading slash; absolute names and `..` are refused. */
        fun normalizeName(raw: String): String {
            val name = raw.replace('\\', '/').trimStart('/')
            require(name.isNotEmpty() && ':' !in name && name.split('/').none { it == ".." }) { "unsafe file name in manifest" }
            return name
        }

        fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
            for (b in bytes) {
                append(Character.forDigit((b.toInt() shr 4) and 0xf, 16).uppercaseChar())
                append(Character.forDigit(b.toInt() and 0xf, 16).uppercaseChar())
            }
        }

        private fun le32(b: ByteArray, at: Int): Int =
            (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8) or
                ((b[at + 2].toInt() and 0xff) shl 16) or ((b[at + 3].toInt() and 0xff) shl 24)

        private fun inflate(data: ByteArray, offset: Int): ByteArray {
            val inflater = Inflater()
            try {
                inflater.setInput(data, offset, data.size - offset)
                val out = ByteArrayOutputStream(data.size * 4)
                val buffer = ByteArray(64 * 1024)
                while (!inflater.finished()) {
                    val n = inflater.inflate(buffer)
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                    out.write(buffer, 0, n)
                    require(out.size() <= MAX_BODY) { "manifest body exceeds size limit" }
                }
                require(inflater.finished()) { "manifest body is truncated" }
                return out.toByteArray()
            } finally {
                inflater.end()
            }
        }

        private const val MAX_BODY = 512L * 1024 * 1024
    }
}
