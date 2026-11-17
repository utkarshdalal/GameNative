package app.gamenative.shadercache

import java.io.File
import java.io.RandomAccessFile

/**
 * What a container has uploaded and no downloaded pool holds yet, as one Fossilize database. The capture layer reads
 * it next to the pools, so a later session does not record, and upload, the same entries again: before the server
 * has merged them into a pool, or at all for a game whose recordings the server does not pool.
 *
 * Callers serialize access to a file.
 */
internal object UploadedEntries {

    /**
     * Adds the entries of the uploaded [session] database to [uploaded], unless that would take it past [maxBytes].
     */
    fun add(uploaded: File, session: File, maxBytes: Long) {
        val have = FozArchive.names(uploaded)
        val kept = ArrayList<Pair<Long, Long>>()
        val new = ArrayList<Pair<Long, Long>>()
        val seen = HashSet<String>()
        FozArchive.forEachEntry(uploaded) { _, offset, length -> kept.add(offset to length) }
        FozArchive.forEachEntry(session) { name, offset, length -> if (name !in have && seen.add(name)) new.add(offset to length) }
        if (new.isEmpty()) return
        val size = FozArchive.HEADER_BYTES + kept.sumOf { it.second } + new.sumOf { it.second }
        if (size > maxBytes) return
        write(uploaded, listOf(uploaded to kept, session to new))
    }

    /** Removes from [uploaded] the entries that [pools] hold; deletes it once none are left. */
    fun prune(uploaded: File, pools: List<File>) {
        if (!uploaded.isFile) return
        val pooled = pools.flatMapTo(HashSet()) { FozArchive.names(it) }
        val kept = ArrayList<Pair<Long, Long>>()
        var dropped = 0
        FozArchive.forEachEntry(uploaded) { name, offset, length -> if (name in pooled) dropped++ else kept.add(offset to length) }
        when {
            kept.isEmpty() -> uploaded.delete()
            dropped > 0 -> write(uploaded, listOf(uploaded to kept))
        }
    }

    /** Replaces [target] with the given byte ranges of the source files; a reader sees the old or the new file. */
    private fun write(target: File, parts: List<Pair<File, List<Pair<Long, Long>>>>) {
        val next = File(target.parentFile, target.name + ".tmp")
        try {
            target.parentFile?.mkdirs()
            next.outputStream().use { out ->
                out.write(FozArchive.HEADER)
                val buffer = ByteArray(64 * 1024)
                for ((source, ranges) in parts) {
                    if (ranges.isEmpty()) continue
                    RandomAccessFile(source, "r").use { raf ->
                        for ((offset, length) in ranges) {
                            raf.seek(offset)
                            var left = length
                            while (left > 0) {
                                val read = raf.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                                if (read < 0) throw java.io.EOFException(source.name)
                                out.write(buffer, 0, read)
                                left -= read
                            }
                        }
                    }
                }
            }
            if (!next.renameTo(target)) next.delete()
        } catch (e: Exception) {
            next.delete()
            throw e
        }
    }
}
