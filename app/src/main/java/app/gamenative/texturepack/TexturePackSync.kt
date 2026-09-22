package app.gamenative.texturepack

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber

object TexturePackSync {

    const val KEY_SUFFIX = ".a4"
    const val SOURCE_SUFFIX = ".src"
    const val PENDING_CAP_BYTES = 2L * 1024L * 1024L * 1024L
    const val LOOKUP_BATCH_SIZE = 2000
    const val UPLOAD_PARALLELISM = 4
    const val DOWNLOAD_PARALLELISM = 6
    const val MAX_ATTEMPTS = 3
    const val RETRY_BACKOFF_MS = 2_000L

    fun sourceFile(cacheDir: File, key: String): File =
        File(cacheDir, key.removeSuffix(KEY_SUFFIX) + SOURCE_SUFFIX)

    fun keyOf(source: File): String = source.name.removeSuffix(SOURCE_SUFFIX) + KEY_SUFFIX

    fun sourceFiles(cacheDir: File): List<File> =
        cacheDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(SOURCE_SUFFIX) }
            ?.sortedBy { it.lastModified() }
            ?: emptyList()

    fun cachedKeys(cacheDir: File): List<String> =
        cacheDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(KEY_SUFFIX) }
            ?.map { it.name }
            ?: emptyList()

    fun packKeys(cacheDir: File): List<String> =
        (cachedKeys(cacheDir) + sourceFiles(cacheDir).map { keyOf(it) }).distinct().sorted()

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

    fun downloadKeys(cacheDir: File, entries: List<PackEntry>, pendingKeys: Set<String>): List<String> =
        entries.asSequence()
            .filterNot { it.pending || it.key in pendingKeys }
            .filter { !TextureCacheStore.hasEntry(cacheDir, it.key) || sourceFile(cacheDir, it.key).isFile }
            .map { it.key }
            .toList()

    fun settledKeys(response: LookupResponse, uploaded: Collection<String>): Set<String> =
        response.have.toSet() + uploaded

    suspend fun uploadSources(
        client: TexturePackClient,
        sources: List<File>,
        parallelism: Int = UPLOAD_PARALLELISM,
        beforeUpload: suspend (Long) -> Unit = {},
    ): List<String> {
        if (sources.isEmpty()) return emptyList()
        val gate = Semaphore(parallelism)
        return coroutineScope {
            sources.map { source ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val key = keyOf(source)
                        try {
                            val payload = source.readBytes()
                            beforeUpload(payload.size.toLong())
                            withRetry { client.putSource(key, payload) }
                            key
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Timber.w(e, "texture pack source upload for $key failed")
                            null
                        }
                    }
                }
            }.awaitAll().filterNotNull()
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
