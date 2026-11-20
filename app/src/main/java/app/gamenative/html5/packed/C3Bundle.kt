package app.gamenative.html5.packed

import app.gamenative.html5.fingerprint.DirectoryRef
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

// Construct 3 desktop-wrapper bundle (www/assets.dat, package.json "content-mode": "bundle"), read in place.
// layout, big-endian, reverse-engineered from a shipped windows-webview2 export:
//   "c3ab" + 12 header bytes
//   "fdir" + u64 dirSize, then dirSize bytes: u32 count, then per entry
//       u64 (zero) + u64 offset + u64 storedSize + u64 size + u8 flags + u32 nameLen + utf-8 name
//   "blob" + u64 blobLen + file bytes; entry offsets are relative to the blob start.
// only stored (flags 0, storedSize == size) entries are seen in the wild; anything else fails the open
// loudly rather than serving garbage.
class C3Bundle private constructor(
    private val channel: FileChannel,
    private val entries: Map<String, Entry>,
) : PackedArchive, DirectoryRef {

    private class Entry(val offset: Long, val size: Long)

    // every ancestor dir of every entry, for exists()/listFiles() on directories.
    private val dirs: Set<String> = buildSet {
        add("")
        for (name in entries.keys) {
            var i = name.lastIndexOf('/')
            while (i > 0) {
                add(name.substring(0, i))
                i = name.lastIndexOf('/', i - 1)
            }
        }
    }

    override fun size(name: String): Long? = entries[name]?.size

    override fun open(name: String): InputStream? {
        val e = entries[name] ?: return null
        return EntryStream(e.offset, e.offset + e.size)
    }

    override fun names(): Sequence<String> = entries.keys.asSequence()

    override fun exists(relPath: String): Boolean {
        val norm = relPath.trim('/')
        return norm in entries || norm in dirs
    }

    override fun listFiles(relPath: String): List<String> {
        val norm = relPath.trim('/').let { if (it == ".") "" else it }
        val prefix = if (norm.isEmpty()) "" else "$norm/"
        return (entries.keys.asSequence() + dirs.asSequence())
            .filter { it.length > prefix.length && it.startsWith(prefix) }
            .map { it.substring(prefix.length).substringBefore('/') }
            .distinct()
            .toList()
    }

    override fun readText(relPath: String): String? = runCatching {
        open(relPath.trim('/'))?.use { it.reader(Charsets.UTF_8).readText() }
    }.getOrNull()

    override fun close() = channel.close()

    // positional reads only, so concurrent streams (WebView IO threads + loopback server) never share a cursor.
    private inner class EntryStream(private var pos: Long, private val end: Long) : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (pos >= end) return -1
            val n = minOf(len.toLong(), end - pos).toInt()
            val r = channel.read(ByteBuffer.wrap(b, off, n), pos)
            if (r <= 0) return -1
            pos += r
            return r
        }

        override fun skip(n: Long): Long {
            val k = n.coerceIn(0, end - pos)
            pos += k
            return k
        }

        override fun available(): Int = (end - pos).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    }

    companion object {
        const val DEFAULT_PATH = "www/assets.dat"

        private val MAGIC = "c3ab".toByteArray(Charsets.US_ASCII)
        private val FDIR = "fdir".toByteArray(Charsets.US_ASCII)
        private val BLOB = "blob".toByteArray(Charsets.US_ASCII)
        private const val FDIR_AT = 16L
        private const val DIR_AT = 28L
        private const val ENTRY_FIXED_BYTES = 37

        // real bundles have ~1k entries in <100 KB; the cap only guards against a corrupt dirSize.
        private const val MAX_DIR_BYTES = 64L * 1024 * 1024

        fun isBundle(file: File): Boolean = file.isFile && runCatching {
            file.inputStream().use { input ->
                val head = ByteArray(MAGIC.size)
                input.read(head) == head.size && head.contentEquals(MAGIC)
            }
        }.getOrDefault(false)

        fun open(file: File): C3Bundle {
            val channel = RandomAccessFile(file, "r").channel
            return runCatching { parse(channel) }
                .onFailure { channel.close() }
                .getOrThrow()
        }

        private fun parse(channel: FileChannel): C3Bundle {
            val fileLen = channel.size()
            val head = readAt(channel, 0, DIR_AT.toInt())
            if (!head.startsWith(MAGIC, 0)) throw IOException("not a c3ab bundle")
            if (!head.startsWith(FDIR, FDIR_AT.toInt())) throw IOException("c3ab: missing fdir chunk")
            val dirSize = head.getLong(20)
            if (dirSize < 4 || dirSize > MAX_DIR_BYTES || DIR_AT + dirSize + 12 > fileLen) {
                throw IOException("c3ab: bad dir size $dirSize")
            }
            val dir = readAt(channel, DIR_AT, dirSize.toInt())
            val blobHeader = readAt(channel, DIR_AT + dirSize, 12)
            if (!blobHeader.startsWith(BLOB, 0)) throw IOException("c3ab: missing blob chunk")
            val blobLen = blobHeader.getLong(4)
            val blobStart = DIR_AT + dirSize + 12
            if (blobLen < 0 || blobStart + blobLen > fileLen) throw IOException("c3ab: blob overruns file")

            val count = dir.getInt(0)
            val entries = HashMap<String, Entry>()
            var p = 4
            repeat(count) {
                if (p + ENTRY_FIXED_BYTES > dir.size) throw IOException("c3ab: truncated dir")
                val offset = dir.getLong(p + 8)
                val stored = dir.getLong(p + 16)
                val size = dir.getLong(p + 24)
                val flags = dir[p + 32].toInt()
                val nameLen = dir.getInt(p + 33)
                p += ENTRY_FIXED_BYTES
                if (nameLen < 0 || p + nameLen > dir.size) throw IOException("c3ab: bad name length")
                val name = String(dir, p, nameLen, Charsets.UTF_8)
                p += nameLen
                if (flags != 0 || stored != size) throw IOException("c3ab: unsupported entry encoding for $name")
                if (offset < 0 || size < 0 || offset + size > blobLen) throw IOException("c3ab: entry overruns blob: $name")
                if (name.isEmpty() || name.startsWith("/") || name.split('/').any { it == ".." }) {
                    throw IOException("c3ab: unsafe entry name: $name")
                }
                entries[name] = Entry(blobStart + offset, size)
            }
            return C3Bundle(channel, entries)
        }

        private fun readAt(channel: FileChannel, pos: Long, len: Int): ByteArray {
            val buf = ByteBuffer.allocate(len)
            var at = pos
            while (buf.hasRemaining()) {
                val r = channel.read(buf, at)
                if (r <= 0) throw IOException("c3ab: unexpected EOF at $at")
                at += r
            }
            return buf.array()
        }

        private fun ByteArray.startsWith(prefix: ByteArray, at: Int): Boolean =
            size >= at + prefix.size && prefix.indices.all { this[at + it] == prefix[it] }

        private fun ByteArray.getLong(at: Int): Long = ByteBuffer.wrap(this, at, 8).long

        private fun ByteArray.getInt(at: Int): Int = ByteBuffer.wrap(this, at, 4).int
    }
}
