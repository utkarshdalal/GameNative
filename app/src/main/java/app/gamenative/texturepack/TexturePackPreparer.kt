package app.gamenative.texturepack

import android.content.Context
import app.gamenative.utils.ContainerUtils
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException

enum class TexturePackPhase { SCANNING, HASHING, UPLOADING, WAITING, DOWNLOADING, DONE }

data class TexturePackProgress(
    val phase: TexturePackPhase,
    val current: Long = 0L,
    val total: Long = 0L,
    val archives: Int = 0,
)

sealed interface TexturePackEstimate {
    object Unsupported : TexturePackEstimate
    data class Ready(val downloadBytes: Long) : TexturePackEstimate
    data class Work(val uploadBytes: Long, val downloadBytes: Long) : TexturePackEstimate
}

class TexturePackPreparer(
    private val context: Context,
    private val appId: String,
    private val platform: String,
    private val storeId: String,
    private val gameDir: File,
    private val client: TexturePackClient = TexturePackClient(context),
    private val title: String? = null,
) {

    private val cacheDir = TexturePackPaths.cacheDirForApp(context, appId)
    private var fingerprint: String = ""
    private var mipsByKey: Map<String, PlanMip> = emptyMap()
    private var missingKeys: List<String> = emptyList()

    suspend fun estimate(onProgress: (TexturePackProgress) -> Unit = {}): TexturePackEstimate {
        onProgress(TexturePackProgress(TexturePackPhase.SCANNING))
        val files = withContext(Dispatchers.IO) { scanFiles(gameDir) }
        withContext(Dispatchers.IO) { TexturePackGate.setPendingSignature(context, appId, TexturePackGate.installSignature(gameDir.absolutePath)) }
        val start = client.prepareStart(PrepareStartRequest(platform, storeId, files, TexturePackGate.cleanTitle(title)))
        fingerprint = start.fingerprint
        when (start.status) {
            PrepareStartResponse.STATUS_UNSUPPORTED -> return TexturePackEstimate.Unsupported
            PrepareStartResponse.STATUS_READY -> return TexturePackEstimate.Ready(start.packBytes ?: 0L)
        }
        val plan = runIoLoop(start.session, start.sourceFiles ?: 0, onProgress) ?: return TexturePackEstimate.Unsupported
        if (plan.mips.isEmpty()) return TexturePackEstimate.Unsupported

        onProgress(TexturePackProgress(TexturePackPhase.HASHING, 0, plan.mips.size.toLong()))
        val byKey = LinkedHashMap<String, PlanMip>()
        withContext(Dispatchers.IO) {
            val policy = TexturePackGate.policyFor(context, appId)
            MipReader(gameDir).use { reader ->
                plan.mips.forEachIndexed { index, mip ->
                    currentCoroutineContext().ensureActive()
                    val bytes = reader.read(mip.file, mip.offset, mip.length, mip.codec, mip.innerOffset, mip.innerLength)
                    byKey[TextureCacheStore.contentKey(mip.src, mip.w, mip.h, bytes, policy)] = mip
                    onProgress(TexturePackProgress(TexturePackPhase.HASHING, index + 1L, plan.mips.size.toLong()))
                }
            }
        }
        mipsByKey = byKey

        val lookup = client.lookup(byKey.keys.toList())
        missingKeys = lookup.want.filter { byKey.containsKey(it) }

        val uploadBytes = missingKeys.sumOf { key -> mipBytes(byKey.getValue(key)).toLong() }
        val downloadBytes = byKey.keys.sumOf { TextureCacheStore.expectedAstcSizeForKey(it) ?: 0L }
        return TexturePackEstimate.Work(uploadBytes, downloadBytes)
    }

    /** Returns true when every pack entry landed in the cache directory. */
    suspend fun prepare(onProgress: (TexturePackProgress) -> Unit = {}): Boolean {
        if (fingerprint.isEmpty() || cacheDir == null) {
            onProgress(TexturePackProgress(TexturePackPhase.DONE))
            return false
        }
        uploadMissing(onProgress)
        storeFingerprint()
        val complete = downloadPack(cacheDir, onProgress)
        if (complete) {
            TexturePackGate.markDone(context, appId)
        } else {
            TexturePackSyncWorker.enqueueDownloadSync(context, appId)
        }
        onProgress(TexturePackProgress(TexturePackPhase.DONE))
        return complete
    }

    private fun storeFingerprint() {
        if (fingerprint.isBlank()) return
        try {
            val container = ContainerUtils.getContainer(context, appId)
            container.putExtra(TexturePackGate.CONTAINER_EXTRA_FINGERPRINT, fingerprint)
            container.saveData()
        } catch (e: Exception) {
            Timber.w(e, "could not persist texture pack fingerprint for $appId")
        }
    }

    private suspend fun runIoLoop(session: String, archives: Int, onProgress: (TexturePackProgress) -> Unit): PreparePlan? {
        var served = 0L
        var idleSince = 0L
        MipReader(gameDir).use { reader ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val batch = client.prepareNext(session)
                if (batch.done) return batch.plan
                if (batch.requests.isEmpty()) {
                    val now = System.currentTimeMillis()
                    if (idleSince == 0L) idleSince = now
                    if (now - idleSince > PLANNER_IDLE_LIMIT_MS) throw IOException("planner produced no work for ${PLANNER_IDLE_LIMIT_MS / 1000}s")
                    delay(EMPTY_BATCH_DELAY_MS)
                    continue
                }
                idleSince = 0L
                val payloads = withContext(Dispatchers.IO) {
                    batch.requests.map { request ->
                        currentCoroutineContext().ensureActive()
                        request.id to reader.read(
                            request.file,
                            request.offset,
                            request.length,
                            request.codec,
                            request.innerOffset,
                            request.innerLength,
                        )
                    }
                }
                val gate = Semaphore(IO_ANSWER_PARALLELISM)
                coroutineScope {
                    payloads.map { (id, payload) ->
                        async(Dispatchers.IO) {
                            gate.withPermit { client.putIoResult(session, id, payload) }
                            served++
                            onProgress(TexturePackProgress(TexturePackPhase.SCANNING, served, 0L, archives))
                        }
                    }.awaitAll()
                }
            }
        }
    }

    private suspend fun uploadMissing(onProgress: (TexturePackProgress) -> Unit) {
        val keys = missingKeys
        if (keys.isEmpty()) return
        val total = keys.sumOf { mipBytes(mipsByKey.getValue(it)).toLong() }
        val sent = AtomicLong(0)
        onProgress(TexturePackProgress(TexturePackPhase.UPLOADING, 0, total))
        val gate = Semaphore(UPLOAD_PARALLELISM)
        coroutineScope {
            keys.map { key ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val mip = mipsByKey.getValue(key)
                        val bytes = MipReader(gameDir).use {
                            it.read(mip.file, mip.offset, mip.length, mip.codec, mip.innerOffset, mip.innerLength)
                        }
                        withRetry { client.putSource(key, bytes) }
                        onProgress(
                            TexturePackProgress(
                                TexturePackPhase.UPLOADING,
                                sent.addAndGet(bytes.size.toLong()),
                                total,
                            ),
                        )
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun downloadPack(cacheDir: File, onProgress: (TexturePackProgress) -> Unit): Boolean {
        val deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS
        var backoff = INITIAL_BACKOFF_MS
        while (true) {
            currentCoroutineContext().ensureActive()
            val entries = client.pack(fingerprint).entries
            val pending = entries.filterNot { TextureCacheStore.hasEntry(cacheDir, it.key) }
            if (pending.isEmpty() && entries.isNotEmpty()) return true

            val total = pending.sumOf { it.size }
            val done = AtomicLong(0)
            onProgress(TexturePackProgress(TexturePackPhase.DOWNLOADING, 0, total))
            val stillMissing = downloadEntries(cacheDir, pending.map { it.key }) { delta ->
                onProgress(TexturePackProgress(TexturePackPhase.DOWNLOADING, done.addAndGet(delta), total))
            }
            if (stillMissing == 0) return true
            if (System.currentTimeMillis() >= deadline) return false
            onProgress(TexturePackProgress(TexturePackPhase.WAITING, stillMissing.toLong(), entries.size.toLong()))
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    private suspend fun downloadEntries(cacheDir: File, keys: List<String>, onBytes: (Long) -> Unit): Int {
        if (keys.isEmpty()) return 0
        val gate = Semaphore(DOWNLOAD_PARALLELISM)
        val notReady = AtomicLong(0)
        coroutineScope {
            keys.map { key ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        try {
                            val payload = withRetry { client.entry(key) }
                            if (payload == null) {
                                notReady.incrementAndGet()
                            } else if (TextureCacheStore.writeEntryAtomic(cacheDir, key, payload)) {
                                onBytes(payload.size.toLong())
                            } else {
                                notReady.incrementAndGet()
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Timber.w(e, "texture pack entry $key failed")
                            notReady.incrementAndGet()
                        }
                    }
                }
            }.awaitAll()
        }
        return notReady.get().toInt()
    }

    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: TexturePackNetworkUnavailable) {
                throw e
            } catch (e: Exception) {
                attempt++
                if (attempt >= MAX_ATTEMPTS) throw e
                delay(INITIAL_BACKOFF_MS * attempt)
            }
        }
    }

    private fun mipBytes(mip: PlanMip): Int = mip.innerLength ?: mip.length

    companion object {
        private const val UPLOAD_PARALLELISM = 4
        private const val DOWNLOAD_PARALLELISM = 6
        private const val MAX_ATTEMPTS = 3
        private const val IO_ANSWER_PARALLELISM = 8
        private const val PLANNER_IDLE_LIMIT_MS = 10 * 60_000L
        private const val EMPTY_BATCH_DELAY_MS = 1_000L
        private const val INITIAL_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val WAIT_TIMEOUT_MS = 10 * 60 * 1000L

        fun scanFiles(root: File): List<PrepareFileEntry> {
            if (!root.isDirectory) return emptyList()
            val prefix = root.absolutePath.length + 1
            return root.walkTopDown()
                .filter { it.isFile }
                .map { PrepareFileEntry(it.absolutePath.substring(prefix).replace(File.separatorChar, '/'), it.length()) }
                .toList()
        }
    }
}
