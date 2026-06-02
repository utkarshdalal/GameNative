package app.gamenative.html5.savesync

import java.io.File
import timber.log.Timber

// rebuilds a leveldb MANIFEST + CURRENT from the .sst files on disk. used after the rewrite
// path renames .ldb -> .sst (withLdbAsSst) and the original MANIFEST no longer matches. encodes
// a VersionEdit (level-0 layout) wrapped in a leveldb log record, CRC32C-masked per leveldb's
// framing. extracted from LevelDbRewriter -- self-contained, separately unit-tested
// (LevelDbRewriterEncodingTest / LevelDbRewriterSynthesizeTest).
internal object LeveldbManifestSynthesizer {

    fun synthesizeManifest(dir: File, useIdb1: Boolean) {
        val sstFiles = dir.listFiles { _, name -> name.matches(Regex("\\d+\\.sst")) }
            ?.sortedBy { it.nameWithoutExtension.toLong() }
            .orEmpty()
        if (sstFiles.isEmpty()) return // empty leveldb -- leave the existing CURRENT/MANIFEST alone

        runCatching {
            val logFiles = dir.listFiles { _, name -> name.matches(Regex("\\d+\\.log")) }.orEmpty()
            val maxFileNumber = (sstFiles + logFiles).maxOf { it.nameWithoutExtension.toLong() }
            val newManifestNumber = maxFileNumber + 1
            val nextFileNumber = newManifestNumber + 1
            val logNumber = logFiles.maxOfOrNull { it.nameWithoutExtension.toLong() } ?: 0L

            val userCmp: org.iq80.leveldb.table.UserComparator = if (useIdb1) {
                org.iq80.leveldb.table.CustomUserComparator(Idb1Comparator())
            } else {
                org.iq80.leveldb.table.BytewiseComparator()
            }
            val internalCmp = org.iq80.leveldb.impl.InternalUserComparator(
                org.iq80.leveldb.impl.InternalKeyComparator(userCmp),
            )

            data class FileEntry(val number: Long, val size: Long, val smallest: ByteArray, val largest: ByteArray)
            val entries = mutableListOf<FileEntry>()
            for (sst in sstFiles) {
                runCatching {
                    java.io.RandomAccessFile(sst, "r").use { raf ->
                        // MMapTable, NOT FileChannelTable: xerial-snappy on Android rejects
                        // heap ByteBuffers (`NOT_A_DIRECT_BUFFER`) and FileChannelTable's block
                        // reader allocates a heap buffer. MMapTable hands snappy a slice of the
                        // underlying MappedByteBuffer, which is a direct buffer. iq80's DbImpl
                        // picks MMapTable internally on 64-bit platforms -- we mirror that here.
                        val table = org.iq80.leveldb.table.MMapTable(
                            sst.absolutePath, raf.channel, internalCmp, true,
                        )
                        val iter = table.iterator()
                        iter.seekToFirst()
                        if (!iter.hasNext()) {
                            Timber.tag("LevelDbRewriter").w(
                                "synthesizeManifest: skipping empty SST %s", sst.name,
                            )
                            return@use
                        }
                        val firstSlice = iter.next().key
                        val firstKey = firstSlice.getBytes(0, firstSlice.length())
                        var lastKey = firstKey
                        while (iter.hasNext()) {
                            val s = iter.next().key
                            lastKey = s.getBytes(0, s.length())
                        }
                        entries.add(FileEntry(sst.nameWithoutExtension.toLong(), sst.length(), firstKey, lastKey))
                    }
                }.onFailure {
                    Timber.tag("LevelDbRewriter").w(
                        it, "synthesizeManifest: failed to read %s — entry omitted", sst.name,
                    )
                }
            }
            if (entries.isEmpty()) {
                Timber.tag("LevelDbRewriter").w(
                    "synthesizeManifest: no readable SSTs in %s, leaving CURRENT alone", dir.absolutePath,
                )
                return@runCatching
            }

            val comparatorName = if (useIdb1) "idb_cmp1" else "leveldb.BytewiseComparator"
            val body = encodeVersionEdit(comparatorName, logNumber, nextFileNumber, entries.map {
                Triple(it.number, it.size, it.smallest to it.largest)
            })
            val record = wrapInLogRecord(body)

            val newManifestName = "MANIFEST-%06d".format(newManifestNumber)
            File(dir, newManifestName).writeBytes(record)
            File(dir, "CURRENT").writeText("$newManifestName\n", Charsets.UTF_8)
            dir.listFiles { _, name -> name.startsWith("MANIFEST-") && name != newManifestName }
                ?.forEach {
                    if (!it.delete()) {
                        Timber.tag("LevelDbRewriter").w("synthesizeManifest: prune %s failed", it.name)
                    }
                }
            Timber.tag("LevelDbRewriter").i(
                "synthesizeManifest: wrote %s with %d file(s) (logNumber=%d nextFileNumber=%d cmp=%s)",
                newManifestName, entries.size, logNumber, nextFileNumber, comparatorName,
            )
        }.onFailure {
            Timber.tag("LevelDbRewriter").w(it, "synthesizeManifest failed for %s — leaving CURRENT alone", dir.absolutePath)
        }
    }

    fun encodeVersionEdit(
        comparatorName: String,
        logNumber: Long,
        nextFileNumber: Long,
        newFiles: List<Triple<Long, Long, Pair<ByteArray, ByteArray>>>,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        // tag 1: kComparator (string)
        writeVarint(out, 1)
        writeLengthPrefixed(out, comparatorName.toByteArray(Charsets.UTF_8))
        // tag 2: kLogNumber
        writeVarint(out, 2)
        writeVarint(out, logNumber)
        // tag 3: kNextFileNumber
        writeVarint(out, 3)
        writeVarint(out, nextFileNumber)
        // tag 4: kLastSequence -- we don't know the precise upper bound from disk, but iq80 only
        // uses this to seed VersionSet.lastSequence; a conservatively-high value avoids
        // assigning sequences that overlap with what's already in the SSTs.
        writeVarint(out, 4)
        writeVarint(out, 1_000_000_000L)
        // tag 7: kNewFile per file (level=0 → simplest level-0 layout, allows overlap)
        for ((number, size, keys) in newFiles) {
            writeVarint(out, 7)
            writeVarint(out, 0) // level
            writeVarint(out, number)
            writeVarint(out, size)
            writeLengthPrefixed(out, keys.first)
            writeLengthPrefixed(out, keys.second)
        }
        return out.toByteArray()
    }

    fun wrapInLogRecord(payload: ByteArray): ByteArray {
        // leveldb log record framing: [4 CRC32C-masked][2 LE length][1 type][payload]
        // type 1 = FULL (single-block record). VersionEdit fits well under the 32KB block size.
        // iq80's PureJavaCrc32C, NOT java.util.zip.CRC32C -- the latter is API 34+ and
        // ClassNotFounds on older runtimes (repro: Android 13 / API 33), which silently fails
        // manifest synthesis so the source SST never imports and the save loads empty. same
        // standard CRC-32C value (parity-tested vs java.util.zip.CRC32C in LeveldbCrc32cParityTest);
        // it's also the exact CRC iq80 writes its own leveldb records with.
        val crc32c = org.iq80.leveldb.util.PureJavaCrc32C()
        crc32c.update(1) // type byte feeds CRC first
        crc32c.update(payload, 0, payload.size)
        val crc = crc32c.value
        // leveldb's mask: ((crc >> 15) | (crc << 17)) + 0xa282ead8
        val masked = (((crc ushr 15) or (crc shl 17)) + 0xa282ead8L) and 0xFFFFFFFFL
        val out = java.io.ByteArrayOutputStream()
        out.write((masked and 0xFF).toInt())
        out.write(((masked ushr 8) and 0xFF).toInt())
        out.write(((masked ushr 16) and 0xFF).toInt())
        out.write(((masked ushr 24) and 0xFF).toInt())
        out.write(payload.size and 0xFF)
        out.write((payload.size ushr 8) and 0xFF)
        out.write(1) // type = FULL
        out.write(payload)
        return out.toByteArray()
    }

    fun writeVarint(out: java.io.ByteArrayOutputStream, value: Long) {
        var v = value
        while ((v and 0x7FL.inv()) != 0L) {
            out.write(((v and 0x7FL) or 0x80L).toInt())
            v = v ushr 7
        }
        out.write((v and 0x7FL).toInt())
    }

    fun writeLengthPrefixed(out: java.io.ByteArrayOutputStream, bytes: ByteArray) {
        writeVarint(out, bytes.size.toLong())
        out.write(bytes)
    }
}
