package app.gamenative.texturepack

import com.winlator.container.Container
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import timber.log.Timber

object TexturePackSync {

    const val SOURCE_SUFFIX = TexturePackKeys.SOURCE_SUFFIX
    const val PENDING_CAP_BYTES = 2L * 1024L * 1024L * 1024L
    const val LOOKUP_BATCH_SIZE = 2000
    const val DOWNLOAD_BATCH_SIZE = 200
    const val DOWNLOAD_BATCHES_IN_FLIGHT = 2
    const val UPLOAD_BATCH_RECORDS = 64
    const val UPLOAD_BATCH_BYTES = 32L * 1024L * 1024L
    const val UPLOAD_BATCHES_IN_FLIGHT = 2
    const val MAX_PACK_PAGES = 1000
    const val MAX_ATTEMPTS = 3
    const val RETRY_BACKOFF_MS = 2_000L

    fun sourceFile(cacheDir: File, key: String): File =
        File(cacheDir, TexturePackKeys.stem(key) + SOURCE_SUFFIX)

    fun keyOf(source: File, policy: PackPolicy? = null): String =
        TexturePackKeys.keyForStem(source.name.removeSuffix(SOURCE_SUFFIX), policy)

    fun sourceFiles(cacheDir: File): List<File> =
        cacheDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(SOURCE_SUFFIX) }
            ?.sortedBy { it.lastModified() }
            ?: emptyList()

    fun cachedKeys(cacheDir: File, policy: PackPolicy? = null): List<String> =
        cacheDir.listFiles()
            ?.filter { it.isFile }
            ?.mapNotNull { TexturePackKeys.canonicalKey(it.name, policy) }
            ?.distinct()
            ?: emptyList()

    fun packKeys(cacheDir: File, policy: PackPolicy? = null): List<String> {
        val firstSeen = HashMap<String, Long>()
        cacheDir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            val key = if (file.name.endsWith(SOURCE_SUFFIX)) keyOf(file, policy) else TexturePackKeys.canonicalKey(file.name, policy)
            if (key != null) firstSeen.merge(key, file.lastModified()) { a, b -> minOf(a, b) }
        }
        return firstSeen.entries
            .sortedWith(compareBy<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .map { it.key }
    }

    fun overCapSources(sources: List<File>, capBytes: Long = PENDING_CAP_BYTES): List<File> {
        var total = sources.sumOf { it.length() }
        if (total <= capBytes) return emptyList()
        val doomed = mutableListOf<File>()
        for (file in sources.sortedBy { it.lastModified() }) {
            if (total <= capBytes) break
            doomed += file
            total -= file.length()
        }
        return doomed
    }

    fun downloadKeys(
        cacheDir: File,
        entries: List<PackEntry>,
        pendingKeys: Set<String>,
    ): List<String> =
        entries.asSequence()
            .filterNot { it.pending || it.key in pendingKeys }
            .filterNot { TextureCacheStore.isServerEntry(cacheDir, it.key) }
            .map { it.key }
            .toList()

    fun readyServerEntries(cacheDir: File, entries: List<PackEntry>, pendingKeys: Set<String>): Int =
        entries.count { !it.pending && it.key !in pendingKeys && TextureCacheStore.isServerEntry(cacheDir, it.key) }

    suspend fun recordServerEntries(container: Container, count: Int) {
        val value = count.coerceAtLeast(0).toString()
        if (container.getExtra(TexturePackGate.CONTAINER_EXTRA_SERVER_ENTRIES, "0") == value) return
        container.putExtra(TexturePackGate.CONTAINER_EXTRA_SERVER_ENTRIES, value)
        withContext(Dispatchers.IO) { container.saveData() }
    }

    suspend fun recordPolicy(container: Container, policy: PackPolicy?) {
        val value = TexturePackGate.encodePolicy(policy)
        if (container.getExtra(TexturePackGate.CONTAINER_EXTRA_POLICY, "") == value) return
        container.putExtra(TexturePackGate.CONTAINER_EXTRA_POLICY, value.ifEmpty { null })
        withContext(Dispatchers.IO) { container.saveData() }
    }

    suspend fun register(client: TexturePackClient, container: Container, keys: List<String>): String {
        val platform = container.getExtra(TexturePackGate.CONTAINER_EXTRA_PLATFORM, "")
        val storeId = container.getExtra(TexturePackGate.CONTAINER_EXTRA_STORE_ID, "")
        val installDir = container.getExtra(TexturePackGate.CONTAINER_EXTRA_INSTALL_DIR, "")
        val title = TexturePackGate.cleanTitle(container.getExtra(TexturePackGate.CONTAINER_EXTRA_TITLE, ""))
        if (platform.isBlank() || installDir.isBlank()) return ""
        val files = withContext(Dispatchers.IO) { TexturePackPreparer.scanFiles(File(installDir)) }
        if (files.isEmpty()) return ""
        val needsFullRes = withContext(Dispatchers.IO) {
            TexturePackGate.needsFullRes(TexturePackPaths.cacheDir(container))
        }
        val response = withRetry {
            client.packRegister(PackRegisterRequest(platform, storeId, files, keys, title, needsFullRes))
        }
        recordPolicy(container, response.policy)
        return response.fingerprint
    }

    suspend fun ensureFingerprint(
        client: TexturePackClient,
        container: Container,
        keys: List<String> = emptyList(),
    ): String {
        val known = container.getExtra(TexturePackGate.CONTAINER_EXTRA_FINGERPRINT, "")
        if (known.isNotBlank() && keys.isEmpty()) return known
        val fingerprint = register(client, container, keys)
        if (fingerprint.isBlank()) return known
        if (fingerprint != known) {
            container.putExtra(TexturePackGate.CONTAINER_EXTRA_FINGERPRINT, fingerprint)
            container.putExtra(TexturePackGate.CONTAINER_EXTRA_SERVER_ENTRIES, "0")
            withContext(Dispatchers.IO) { container.saveData() }
        }
        return fingerprint
    }

    suspend fun fetchPack(client: TexturePackClient, container: Container, fingerprint: String): PackResponse {
        val first = withRetry { client.pack(fingerprint) }
        recordPolicy(container, first.policy)
        val entries = first.entries.toMutableList()
        var after = first.next
        var pages = 1
        while (after != null && pages < MAX_PACK_PAGES) {
            currentCoroutineContext().ensureActive()
            val cursor: Long = after
            val page = withRetry { client.pack(fingerprint, cursor) }
            entries += page.entries
            pages++
            after = page.next?.takeIf { it != cursor && page.entries.isNotEmpty() }
        }
        return first.copy(entries = entries, next = null)
    }

    suspend fun downloadEntries(
        client: TexturePackClient,
        cacheDir: File,
        keys: List<String>,
        batchSize: Int = DOWNLOAD_BATCH_SIZE,
        batchesInFlight: Int = DOWNLOAD_BATCHES_IN_FLIGHT,
        onEntry: (key: String, written: Boolean) -> Unit = { _, _ -> },
    ): Int {
        if (keys.isEmpty()) return 0
        val gate = Semaphore(batchesInFlight)
        val written = AtomicInteger(0)
        coroutineScope {
            keys.chunked(batchSize).map { batch ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val remaining = Collections.synchronizedSet(LinkedHashSet(batch))
                        try {
                            withRetry {
                                val request = synchronized(remaining) { remaining.toList() }
                                if (request.isNotEmpty()) {
                                    client.entries(request) { key, payload ->
                                        if (!remaining.remove(key)) return@entries
                                        val ok = TextureCacheStore.writeEntryAtomic(cacheDir, key, payload)
                                        if (ok) {
                                            TextureCacheStore.pruneSuperseded(cacheDir, key)
                                            written.incrementAndGet()
                                        }
                                        onEntry(key, ok)
                                    }
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Timber.w(e, "texture pack batch of ${batch.size} entries failed")
                        }
                        val missed = synchronized(remaining) { remaining.toList() }
                        missed.forEach { onEntry(it, false) }
                    }
                }
            }.awaitAll()
        }
        return written.get()
    }

    fun uploadBatches(
        sources: List<File>,
        maxRecords: Int = UPLOAD_BATCH_RECORDS,
        maxBytes: Long = UPLOAD_BATCH_BYTES,
    ): List<List<File>> {
        val batches = mutableListOf<List<File>>()
        var current = mutableListOf<File>()
        var bytes = 0L
        for (source in sources) {
            val size = source.length()
            if (current.isNotEmpty() && (current.size >= maxRecords || bytes + size > maxBytes)) {
                batches += current
                current = mutableListOf()
                bytes = 0L
            }
            current += source
            bytes += size
        }
        if (current.isNotEmpty()) batches += current
        return batches
    }

    suspend fun uploadSources(
        client: TexturePackClient,
        sources: List<File>,
        batchesInFlight: Int = UPLOAD_BATCHES_IN_FLIGHT,
        policy: PackPolicy? = null,
        onUploaded: (Long) -> Unit = {},
    ): List<String> {
        if (sources.isEmpty()) return emptyList()
        val batches = withContext(Dispatchers.IO) { uploadBatches(sources) }
        val gate = Semaphore(batchesInFlight)
        return coroutineScope {
            batches.map { batch ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        ensureActive()
                        val byKey = batch.associateBy { keyOf(it, policy) }
                        try {
                            val response = withRetry { client.putSources(byKey.map { (key, file) -> key to file }) }
                            response.results.mapNotNull { result ->
                                val file = byKey[result.key] ?: return@mapNotNull null
                                if (result.accepted) {
                                    val bytes = file.length()
                                    file.delete()
                                    onUploaded(bytes)
                                    result.key
                                } else {
                                    Timber.w("texture pack source ${result.key} ${result.status}: ${result.error.orEmpty()}")
                                    null
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Timber.w(e, "texture pack source batch of ${batch.size} failed")
                            emptyList()
                        }
                    }
                }
            }.awaitAll().flatten()
        }
    }

    suspend fun uploadPending(
        client: TexturePackClient,
        container: Container,
        cacheDir: File,
        onProgress: (Long) -> Unit = {},
    ) {
        val overCap = withContext(Dispatchers.IO) { overCapSources(sourceFiles(cacheDir)) }
        if (overCap.isNotEmpty()) {
            val bytes = overCap.sumOf { it.length() }
            Timber.i("texture pack: dropping ${overCap.size} pending sources ($bytes bytes) over the cap")
            withContext(Dispatchers.IO) { overCap.forEach { it.delete() } }
        }

        val policy = TexturePackGate.policyOf(container)
        val keys = withContext(Dispatchers.IO) { packKeys(cacheDir, policy) }
        ensureFingerprint(client, container, keys)

        val sources = withContext(Dispatchers.IO) { sourceFiles(cacheDir) }
        if (sources.isEmpty()) return
        val byKey = sources.associateBy { keyOf(it, policy) }
        for (batch in byKey.keys.chunked(LOOKUP_BATCH_SIZE)) {
            currentCoroutineContext().ensureActive()
            val response = withRetry { client.lookup(batch) }
            if (response.invalid.isNotEmpty()) {
                Timber.w("texture pack: server rejected ${response.invalid.size} keys")
            }
            withContext(Dispatchers.IO) {
                (response.have + response.pending).forEach { key ->
                    byKey[key]?.let { file ->
                        val bytes = file.length()
                        file.delete()
                        onProgress(bytes)
                    }
                }
            }
            val wanted = response.want.toSet()
            uploadSources(client, batch.filter { it in wanted }.mapNotNull { byKey[it] }, policy = policy, onUploaded = onProgress)
        }
    }

    suspend fun <T> withRetry(block: suspend () -> T): T {
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
                delay(RETRY_BACKOFF_MS * attempt)
            }
        }
    }
}
