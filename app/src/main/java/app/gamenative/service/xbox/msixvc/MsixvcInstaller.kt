// Based on xodus (https://github.com/xodus-gaming/xodus), GPL-3.0.
package app.gamenative.service.xbox.msixvc

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

enum class InstallResult { COMPLETED, CANCELED }

/**
 * Streams an MSIXVC package into plain files: only the file data pages (plus the matching level 0
 * hash tree entries and the package metadata) are fetched, each page is verified and decrypted in
 * memory, and written to "<name>.msixvc-part", renamed when the file is complete. Every file,
 * including KEEP_ENCRYPTED_ON_DISK ones, is written decrypted (xodus's decrypt_all path).
 *
 * Work is split into chunks of [chunkSize] bytes of a file, run by [concurrency] workers in package
 * order; each chunk is one ranged request, the same per-range streaming xodus does per file.
 * Completed chunks and files are appended to [stateFile], so a canceled install resumes where it
 * stopped. [metadataFile] caches the parsed file list during the install and holds the package
 * metadata (content id, version, files) afterwards.
 */
class MsixvcInstaller(
    private val source: RangeSource,
    private val keys: Map<UUID, ByteArray>,
    private val targetDir: File,
    private val stateFile: File,
    private val metadataFile: File = File(stateFile.absoluteFile.parentFile, stateFile.name + ".package.json"),
    private val concurrency: Int = 4,
    private val verifyHashes: Boolean = true,
    private val chunkSize: Long = 32L shl 20,
) {
    init {
        require(chunkSize > 0 && chunkSize % PAGE_SIZE == 0L) { "chunk size must be a multiple of the page size" }
        require(concurrency >= 1)
    }

    private class FileJob(val file: PackageFile, val out: File, val part: File, val remaining: AtomicInteger)
    private class ChunkTask(val job: FileJob, val index: Int, val packageOrder: Long)
    private class Canceled : RuntimeException()

    private lateinit var pkg: XvdPackage
    private lateinit var state: StateLog
    private val done = AtomicLong()
    private var total = 0L
    private val progressLock = Any()
    private var progress: (Long, Long) -> Unit = { _, _ -> }

    private fun reportProgress() = synchronized(progressLock) { progress(done.get(), total) }

    /** Callbacks run on worker threads. */
    fun install(onProgress: (doneBytes: Long, totalBytes: Long) -> Unit, isCancelled: () -> Boolean): InstallResult {
        progress = onProgress
        pkg = XvdPackage.open(source)
        val cached = loadCachedFiles()
        val files = cached?.first ?: pkg.files.also { saveMetadata(it, complete = false) }
        if (cached?.second == true && !stateFile.exists() &&
            files.all { f -> PackagePaths.resolve(targetDir, f.path).let { it.isFile && it.length() == f.size } }
        ) {
            val size = files.sumOf { it.size }
            onProgress(size, size)
            return InstallResult.COMPLETED
        }
        targetDir.mkdirs()
        state = StateLog.open(stateFile, "${pkg.vduid}\t${source.length}")

        val seen = HashSet<String>()
        val tasks = ArrayList<ChunkTask>()
        total = files.sumOf { it.size }
        done.set(0)
        for (f in files) {
            val out = PackagePaths.resolve(targetDir, f.path)
            if (!seen.add(out.path.lowercase(Locale.ROOT))) continue
            if (state.isFileDone(f.path) && out.isFile && out.length() == f.size) {
                done.addAndGet(f.size)
                continue
            }
            out.parentFile?.mkdirs()
            val part = File(out.parentFile, out.name + PART_SUFFIX)
            if (f.size == 0L || f.residentData != null) {
                writeAtomically(part, out, f.residentData ?: ByteArray(0))
                state.markFile(f.path)
                done.addAndGet(f.size)
                continue
            }
            val chunkCount = ceilDiv(f.size, chunkSize).toInt()
            var doneChunks = state.doneChunks(f.path)
            if (!part.isFile && doneChunks.isNotEmpty()) {
                doneChunks = emptySet()
                state.resetFile(f.path)
            }
            val pending = (0 until chunkCount).filter { it !in doneChunks }
            for (c in doneChunks) if (c < chunkCount) done.addAndGet(chunkLength(f, c))
            val job = FileJob(f, out, part, AtomicInteger(pending.size))
            if (pending.isEmpty()) {
                finishFile(job)
                continue
            }
            for (c in pending) tasks.add(ChunkTask(job, c, packageOffsetOf(f, c * chunkSize)))
        }
        tasks.sortBy { it.packageOrder }
        onProgress(done.get(), total)

        val queue = ConcurrentLinkedQueue(tasks)
        val stop = AtomicBoolean(false)
        val canceled = AtomicBoolean(false)
        val error = AtomicReference<Throwable>()
        val workers = (0 until minOf(concurrency, maxOf(tasks.size, 1))).map { n ->
            Thread({
                val ciphers = HashMap<Int, XtsPageCipher>()
                val sha = MessageDigest.getInstance("SHA-256")
                while (!stop.get()) {
                    val task = queue.poll() ?: break
                    try {
                        runChunk(task, ciphers, sha) {
                            if (stop.get()) throw Canceled()
                            if (isCancelled()) {
                                canceled.set(true)
                                stop.set(true)
                                throw Canceled()
                            }
                        }
                    } catch (_: Canceled) {
                        break
                    } catch (t: Throwable) {
                        error.compareAndSet(null, t)
                        stop.set(true)
                        break
                    }
                }
            }, "msixvc-install-$n").apply { start() }
        }
        try {
            workers.forEach { it.join() }
        } catch (e: InterruptedException) {
            stop.set(true)
            workers.forEach { it.join() }
            throw e
        } finally {
            state.close()
        }
        error.get()?.let { throw it }
        if (canceled.get() || queue.isNotEmpty()) return InstallResult.CANCELED

        saveMetadata(files, complete = true)
        stateFile.delete()
        onProgress(total, total)
        return InstallResult.COMPLETED
    }

    private fun chunkLength(f: PackageFile, c: Int) = minOf(chunkSize, f.size - c * chunkSize)

    private fun packageOffsetOf(f: PackageFile, filePos: Long): Long {
        var pos = 0L
        for (e in f.extents) {
            if (filePos < pos + e.length) return if (e.offset < 0) -1 else e.offset + (filePos - pos)
            pos += e.length
        }
        return -1
    }

    private fun keyFor(section: EncryptedSection): ByteArray {
        keys[section.keyId]?.let { return it }
        // xodus uses the single content key of the license regardless of its id.
        val packageKeys = pkg.encryptedSections.map { it.keyId }.toSet()
        if (keys.size == 1 && packageKeys.size == 1) return keys.values.first()
        throw MsixvcException.MissingContentKey(section.keyId)
    }

    private fun runChunk(task: ChunkTask, ciphers: HashMap<Int, XtsPageCipher>, sha: MessageDigest, checkCancel: () -> Unit) {
        val f = task.job.file
        val start = task.index * chunkSize
        val end = minOf(f.size, start + chunkSize)
        RandomAccessFile(task.job.part, "rw").use { raf ->
            var filePos = 0L
            for (ext in f.extents) {
                val es = filePos
                val ee = filePos + ext.length
                filePos = ee
                val s = maxOf(es, start)
                val e = minOf(ee, end)
                if (s >= e) continue
                if (ext.offset < 0) {
                    writeZeros(raf, s, e - s, checkCancel)
                } else {
                    copyRange(ext.offset + (s - es), e - s, raf, s, ciphers, sha, checkCancel)
                }
            }
            if (filePos < end) throw MsixvcException.InvalidPackage("${f.path}: extents shorter than file size")
        }
        state.markChunk(f.path, task.index)
        if (task.job.remaining.decrementAndGet() == 0) finishFile(task.job)
    }

    private fun writeZeros(raf: RandomAccessFile, at: Long, len: Long, checkCancel: () -> Unit) {
        val zeros = ByteArray(BATCH_PAGES * PAGE_SIZE.toInt())
        var p = 0L
        while (p < len) {
            checkCancel()
            val n = minOf(zeros.size.toLong(), len - p).toInt()
            raf.seek(at + p)
            raf.write(zeros, 0, n)
            p += n
            done.addAndGet(n.toLong())
            reportProgress()
        }
    }

    private fun copyRange(
        pkgOff: Long,
        len: Long,
        raf: RandomAccessFile,
        fileOff: Long,
        ciphers: HashMap<Int, XtsPageCipher>,
        sha: MessageDigest,
        checkCancel: () -> Unit,
    ) {
        val firstPage = pkgOff / PAGE_SIZE
        val pageCount = ((pkgOff + len - 1) / PAGE_SIZE - firstPage + 1).toInt()
        val sections = Array(pageCount) { pkg.sectionForPage(firstPage + it) }
        val needEntries = verifyHashes || sections.any { it != null }
        val entries = if (needEntries) pkg.readLevel0Entries(firstPage, pageCount) else null
        val streamStart = firstPage * PAGE_SIZE
        val streamLen = minOf(pageCount * PAGE_SIZE, source.length - streamStart)
        val buf = ByteArray(BATCH_PAGES * PAGE_SIZE.toInt())
        val units = IntArray(BATCH_PAGES)
        source.openStream(streamStart, streamLen).use { input ->
            var b = 0
            while (b < pageCount) {
                checkCancel()
                val n = minOf(BATCH_PAGES, pageCount - b)
                val bytes = n * PAGE_SIZE.toInt()
                val got = readFully(input, buf, bytes)
                if (got < bytes) {
                    if (streamStart + b * PAGE_SIZE + got < source.length) {
                        throw MsixvcException.RangeRequestFailed("short read at ${streamStart + b * PAGE_SIZE + got}")
                    }
                    buf.fill(0, got, bytes)
                }
                if (entries != null) {
                    for (i in 0 until n) {
                        val e = (b + i) * HASH_ENTRY_LENGTH
                        units[i] = entries.i32(e + 20)
                        if (verifyHashes) {
                            sha.update(buf, i * PAGE_SIZE.toInt(), PAGE_SIZE.toInt())
                            val digest = sha.digest()
                            val cmp = pkg.level0HashLength
                            for (k in 0 until cmp) {
                                if (digest[k] != entries[e + k]) throw MsixvcException.IntegrityMismatch(firstPage + b + i)
                            }
                        }
                    }
                }
                var i = 0
                while (i < n) {
                    val section = sections[b + i]
                    var j = i + 1
                    while (j < n && sections[b + j] === section) j++
                    if (section != null) {
                        val cipher = ciphers.getOrPut(section.keyIndex) { XtsPageCipher(keyFor(section)) }
                        cipher.decryptPages(buf, i * PAGE_SIZE.toInt(), j - i, section.regionId, pkg.header.vduidRaw, units, i)
                    }
                    i = j
                }
                val batchStart = streamStart + b * PAGE_SIZE
                val sliceStart = maxOf(batchStart, pkgOff)
                val sliceEnd = minOf(batchStart + bytes, pkgOff + len)
                raf.seek(fileOff + (sliceStart - pkgOff))
                raf.write(buf, (sliceStart - batchStart).toInt(), (sliceEnd - sliceStart).toInt())
                done.addAndGet(sliceEnd - sliceStart)
                reportProgress()
                b += n
            }
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray, len: Int): Int {
        var p = 0
        while (p < len) {
            val n = input.read(buf, p, len - p)
            if (n < 0) break
            p += n
        }
        return p
    }

    private fun finishFile(job: FileJob) {
        RandomAccessFile(job.part, "rw").use { it.setLength(job.file.size) }
        rename(job.part, job.out)
        state.markFile(job.file.path)
    }

    private fun writeAtomically(part: File, out: File, data: ByteArray) {
        FileOutputStream(part).use { it.write(data) }
        rename(part, out)
    }

    private fun rename(from: File, to: File) {
        if (from.renameTo(to)) return
        to.delete()
        if (!from.renameTo(to)) throw java.io.IOException("cannot rename $from to $to")
    }

    /** Cached file list and whether the install it belongs to completed. */
    private fun loadCachedFiles(): Pair<List<PackageFile>, Boolean>? {
        if (!metadataFile.isFile) return null
        return runCatching {
            val o = Json.parseToJsonElement(metadataFile.readText()).jsonObject
            if (o["format"]?.jsonPrimitive?.long != 1L) return null
            if (o["vduid"]?.jsonPrimitive?.content != pkg.vduid.toString()) return null
            if (o["packageSize"]?.jsonPrimitive?.long != source.length) return null
            val complete = o["complete"]?.jsonPrimitive?.boolean == true
            o["files"]!!.jsonArray.map { el ->
                val f = el.jsonObject
                PackageFile(
                    path = f["path"]!!.jsonPrimitive.content,
                    size = f["size"]!!.jsonPrimitive.long,
                    keepEncrypted = f["keepEncrypted"]!!.jsonPrimitive.boolean,
                    extents = f["extents"]!!.jsonArray.map {
                        val a = it.jsonArray
                        PackageExtent(a[0].jsonPrimitive.long, a[1].jsonPrimitive.long)
                    },
                    residentData = f["resident"]?.jsonPrimitive?.content?.let { Base64.getDecoder().decode(it) },
                )
            } to complete
        }.getOrNull()
    }

    private fun saveMetadata(files: List<PackageFile>, complete: Boolean) {
        val json = buildJsonObject {
            put("format", 1)
            put("complete", complete)
            put("contentId", pkg.contentId.toString())
            put("vduid", pkg.vduid.toString())
            pkg.xvcContentId?.let { put("xvcContentId", it.toString()) }
            put("productId", pkg.header.productId.toString())
            put("version", pkg.version.toString())
            put("packageSize", source.length)
            put("totalInstallSize", files.sumOf { it.size })
            put(
                "files",
                JsonArray(
                    files.map { f ->
                        buildJsonObject {
                            put("path", f.path)
                            put("size", f.size)
                            put("keepEncrypted", f.keepEncrypted)
                            put("extents", buildJsonArray { f.extents.forEach { add(JsonArray(listOf(JsonPrimitive(it.offset), JsonPrimitive(it.length)))) } })
                            f.residentData?.let { put("resident", Base64.getEncoder().encodeToString(it)) }
                        }
                    },
                ),
            )
        }
        metadataFile.absoluteFile.parentFile?.mkdirs()
        val tmp = File(metadataFile.path + ".tmp")
        tmp.writeText(json.toString())
        rename(tmp, metadataFile)
    }

    /** Append-only resume log after an identity header: "C\t<path>\t<chunk>", "F\t<path>", "R\t<path>" (reset). */
    private class StateLog private constructor(private val out: FileOutputStream, private val chunks: Map<String, Set<Int>>, private val files: Set<String>) {
        fun isFileDone(path: String) = path in files
        fun doneChunks(path: String): Set<Int> = chunks[path] ?: emptySet()

        @Synchronized fun markChunk(path: String, index: Int) = write("C\t$path\t$index\n")

        @Synchronized fun markFile(path: String) = write("F\t$path\n")

        @Synchronized fun resetFile(path: String) = write("R\t$path\n")

        private fun write(line: String) {
            out.write(line.toByteArray(Charsets.UTF_8))
            out.flush()
        }

        fun close() = out.close()

        companion object {
            private const val MAGIC = "msixvc-state 1"

            fun open(file: File, identity: String): StateLog {
                val header = "$MAGIC\t$identity"
                val chunks = HashMap<String, MutableSet<Int>>()
                val files = HashSet<String>()
                var valid = false
                if (file.isFile) {
                    val lines = file.readLines(Charsets.UTF_8)
                    if (lines.firstOrNull() == header) {
                        valid = true
                        for (l in lines.drop(1)) {
                            val p = l.split('\t')
                            when {
                                p.size == 3 && p[0] == "C" -> p[2].toIntOrNull()?.let { chunks.getOrPut(p[1]) { HashSet() }.add(it) }
                                p.size == 2 && p[0] == "F" -> files.add(p[1])
                                p.size == 2 && p[0] == "R" -> {
                                    chunks.remove(p[1])
                                    files.remove(p[1])
                                }
                            }
                        }
                    }
                }
                file.absoluteFile.parentFile?.mkdirs()
                val out = FileOutputStream(file, valid)
                if (!valid) out.write("$header\n".toByteArray(Charsets.UTF_8))
                return StateLog(out, chunks, files)
            }
        }
    }

    companion object {
        const val PART_SUFFIX = ".msixvc-part"
        private const val BATCH_PAGES = 64
    }
}
