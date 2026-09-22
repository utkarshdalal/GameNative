package app.gamenative.texturepack

import java.io.File

object TexturePackSync {

    const val KEY_SUFFIX = ".a4"
    const val SOURCE_SUFFIX = ".src"
    const val PENDING_CAP_BYTES = 2L * 1024L * 1024L * 1024L
    const val LOOKUP_BATCH_SIZE = 2000
    const val UPLOAD_PARALLELISM = 4
    const val DOWNLOAD_PARALLELISM = 6

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
}
