// Slice decoding and placement follow UplayApps' Downloader (https://github.com/UplayDB/UplayApps), MIT.
package app.gamenative.service.ubisoft

import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.Inflater

/** One stored piece of a file: where it goes and how to fetch it. [size] is -1 when the build does not list it. */
class UbisoftPiece(
    val id: String,
    val file: String,
    val offset: Long,
    val size: Long,
    val downloadSize: Long,
    val hash: String?,
)

/**
 * What an install or update has to do. [fetch] are the pieces to download, each once, written where
 * they first occur; [copy] are the other occurrences, filled from that first one. Files in
 * [sequential] list no piece sizes and are fetched in order, one after another.
 */
class UbisoftInstallPlan(
    val write: List<UbisoftManifest.File>,
    val keep: List<UbisoftManifest.File>,
    val remove: List<String>,
    val fetch: List<UbisoftPiece>,
    val copy: Map<String, List<Pair<UbisoftPiece, UbisoftPiece>>>,
    val sequential: Map<String, List<UbisoftPiece>>,
) {
    val downloadBytes: Long = fetch.sumOf { it.wireSize() } + sequential.values.sumOf { list -> list.sumOf { it.wireSize() } }
    val installBytes: Long = (write + keep).sumOf { it.size }

    companion object {
        /**
         * [installed] is the previous build's selection, [onDisk] tells whether a file is present with
         * the given size. Files that are unchanged and present are kept; the old build's other files
         * are removed.
         */
        fun build(
            selected: List<UbisoftManifest.File>,
            installed: List<UbisoftManifest.File> = emptyList(),
            onDisk: (String, Long) -> Boolean = { _, _ -> false },
        ): UbisoftInstallPlan {
            val old = installed.associateBy { it.name.lowercase() }
            val (keep, write) = selected.partition { f ->
                val before = old[f.name.lowercase()]
                before != null && !f.isDir && sameContent(before, f) && onDisk(f.name, f.size)
            }
            val owners = HashMap<String, UbisoftPiece>()
            for (f in keep) pieces(f)?.forEach { owners.putIfAbsent(it.id, it) }
            val fetch = ArrayList<UbisoftPiece>()
            val copy = LinkedHashMap<String, MutableList<Pair<UbisoftPiece, UbisoftPiece>>>()
            val sequential = LinkedHashMap<String, List<UbisoftPiece>>()
            for (f in write) {
                if (f.isDir || f.size == 0L) continue
                val list = pieces(f)
                if (list == null) {
                    sequential[f.name] = f.pieceIds.map { UbisoftPiece(it, f.name, -1, -1, 0, null) }
                    continue
                }
                for (piece in list) {
                    val owner = owners.putIfAbsent(piece.id, piece)
                    if (owner == null) fetch += piece else copy.getOrPut(f.name) { ArrayList() } += owner to piece
                }
            }
            val wanted = selected.mapTo(HashSet()) { it.name.lowercase() }
            val remove = installed.filter { it.name.lowercase() !in wanted && !it.isDir }.map { it.name }
            return UbisoftInstallPlan(write, keep, remove, fetch, copy, sequential)
        }

        private fun sameContent(a: UbisoftManifest.File, b: UbisoftManifest.File): Boolean =
            a.size == b.size && ids(a) == ids(b)

        private fun ids(f: UbisoftManifest.File): List<String> =
            if (f.slices.isNotEmpty()) f.slices.map { it.downloadSha1.orEmpty() } + f.pieceIds else f.pieceIds

        /** Pieces with offsets, or null when the build lists no sizes for this file. */
        internal fun pieces(f: UbisoftManifest.File): List<UbisoftPiece>? {
            if (f.slices.isEmpty()) return if (f.pieceIds.isEmpty()) emptyList() else null
            val hashes = f.pieceIds.takeIf { it.size == f.slices.size }
            var running = 0L
            return f.slices.mapIndexed { i, s ->
                val id = s.downloadSha1 ?: f.pieceIds.getOrNull(i) ?: throw IOException("${f.name}: piece $i has no id")
                val offset = s.fileOffset ?: running
                running = offset + s.size
                UbisoftPiece(id, f.name, offset, s.size, s.downloadSize, hashes?.get(i))
            }
        }

        private fun UbisoftPiece.wireSize(): Long = if (downloadSize > 0) downloadSize else maxOf(size, 0L)
    }
}

/** Unpacks a downloaded piece and checks it against the manifest. */
object UbisoftSliceCodec {
    private const val MAX_PIECE = 256L * 1024 * 1024

    /** Which manifest hash a piece matched; the build does not say which bytes each one covers. */
    enum class Match { STORED_BYTES, UNPACKED_BYTES, PIECE_HASH }

    class Decoded(val bytes: ByteArray, val match: Match)

    fun decode(manifest: UbisoftManifest, piece: UbisoftPiece, stored: ByteArray): Decoded {
        val storedSha = sha1(stored)
        val bytes = when {
            !manifest.compressed -> stored
            // A piece that did not shrink may be stored as is.
            piece.size >= 0 && stored.size.toLong() == piece.size ->
                runCatching { unpack(manifest.compression, stored, piece.size) }.getOrElse { if (it is UbisoftUnsupportedException) throw it else stored }
            else -> unpack(manifest.compression, stored, piece.size)
        }
        if (piece.size >= 0 && bytes.size.toLong() != piece.size) {
            throw IOException("piece ${piece.id.take(8)} of ${piece.file}: unpacked ${bytes.size} bytes, the manifest says ${piece.size}")
        }
        if (storedSha == piece.id) return Decoded(bytes, Match.STORED_BYTES)
        val unpackedSha = if (bytes === stored) storedSha else sha1(bytes)
        if (unpackedSha == piece.id) return Decoded(bytes, Match.UNPACKED_BYTES)
        if (piece.hash != null && unpackedSha == piece.hash) return Decoded(bytes, Match.PIECE_HASH)
        throw IOException("piece ${piece.id.take(8)} of ${piece.file}: matches no manifest hash (stored or unpacked)")
    }

    fun unpack(method: UbisoftManifest.Compression, data: ByteArray, size: Long): ByteArray = when (method) {
        UbisoftManifest.Compression.ZSTD -> if (size >= 0) {
            Zstd.decompress(data, size.toInt())
        } else {
            ZstdInputStream(data.inputStream()).use { bounded(it.readBytes()) }
        }
        UbisoftManifest.Compression.DEFLATE -> inflate(data, size)
        UbisoftManifest.Compression.LZHAM -> throw UbisoftUnsupportedException("LZHAM-compressed slices are not supported yet")
        UbisoftManifest.Compression.UNKNOWN -> throw UbisoftUnsupportedException("unknown slice compression")
    }

    private fun inflate(data: ByteArray, size: Long): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream(if (size in 1..MAX_PIECE) size.toInt() else data.size * 2)
            val buffer = ByteArray(64 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) throw IOException("truncated deflate piece")
                out.write(buffer, 0, n)
                if (out.size() > MAX_PIECE) throw IOException("piece exceeds size limit")
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    private fun bounded(bytes: ByteArray): ByteArray {
        if (bytes.size > MAX_PIECE) throw IOException("piece exceeds size limit")
        return bytes
    }

    fun sha1(bytes: ByteArray): String = UbisoftManifest.hex(MessageDigest.getInstance("SHA-1").digest(bytes))
}
