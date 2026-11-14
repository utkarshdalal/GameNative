package app.gamenative.shadercache

import java.io.File
import org.json.JSONObject

/**
 * Pools downloaded when a game finishes installing. A game has no container until its first launch, so the pool waits
 * in app storage and that launch moves it into the container's pool directory.
 */
internal object StagedPools {
    fun dir(filesDir: File) = File(filesDir, "shadercache/staged")
    fun poolFile(dir: File, key: ShaderCacheKey) = File(dir, "${key.fileName}.foz")
    fun metaFile(dir: File, key: ShaderCacheKey) = File(dir, "${key.fileName}.json")

    fun meta(version: Long, sha256: String, size: Long, entries: Long): JSONObject = JSONObject()
        .put("version", version)
        .put("sha256", sha256)
        .put("size", size)
        .put("entries", entries)

    fun readMeta(file: File): JSONObject? =
        if (file.isFile) runCatching { JSONObject(file.readText()) }.getOrNull() else null

    private fun partialFile(dir: File, key: ShaderCacheKey) = File(dir, "${key.fileName}.foz.part")

    private fun isComplete(staged: File, meta: JSONObject?): Boolean =
        meta != null && staged.isFile && staged.length() == meta.optLong("size", -1L)

    /** Version of the complete staged pool of [key], or null if there is none. */
    fun stagedVersion(dir: File, key: ShaderCacheKey): Long? =
        readMeta(metaFile(dir, key))?.takeIf { isComplete(poolFile(dir, key), it) }?.optLong("version")

    /**
     * Moves the staged pool of [key] into [poolDir] as [poolFile] with [poolMetaFile] when it is newer than
     * [localVersion], replacing everything else in [poolDir]. Returns the adopted pool's metadata, or null when nothing
     * was adopted. A staged pool that is incomplete, no newer, or whose size does not match its metadata is deleted.
     */
    fun adopt(dir: File, key: ShaderCacheKey, poolDir: File, poolFile: File, poolMetaFile: File, localVersion: Long?): JSONObject? {
        val staged = poolFile(dir, key)
        val stagedMeta = metaFile(dir, key)
        if (!staged.exists() && !stagedMeta.exists()) {
            // Left by an install that was stopped mid-download.
            partialFile(dir, key).delete()
            return null
        }
        val meta = readMeta(stagedMeta)
        if (!isComplete(staged, meta) || (localVersion != null && meta!!.optLong("version") <= localVersion)) {
            drop(dir, key)
            return null
        }
        // The container's current pool stays until the replacement and its metadata are written in full.
        poolDir.mkdirs()
        val newPool = File(poolDir, "${poolFile.name}.adopt")
        val newMeta = File(poolDir, "${poolMetaFile.name}.adopt")
        val written = (staged.renameTo(newPool) || runCatching { staged.copyTo(newPool, overwrite = true) }.isSuccess) &&
            runCatching { newMeta.writeText(meta.toString()) }.isSuccess
        if (!written) {
            newPool.delete()
            newMeta.delete()
            drop(dir, key)
            return null
        }
        poolMetaFile.delete()
        if (!newPool.renameTo(poolFile) || !newMeta.renameTo(poolMetaFile)) {
            newPool.delete()
            newMeta.delete()
            drop(dir, key)
            return null
        }
        poolDir.listFiles()?.forEach { if (it.isFile && it != poolFile && it != poolMetaFile) it.delete() }
        drop(dir, key)
        return meta
    }

    fun drop(dir: File, key: ShaderCacheKey) {
        poolFile(dir, key).delete()
        metaFile(dir, key).delete()
        partialFile(dir, key).delete()
    }

    /** Deletes every staged file that does not belong to one of [keep]. */
    fun dropAllExcept(dir: File, keep: Collection<ShaderCacheKey>) {
        val names = keep.flatMap { listOf("${it.fileName}.foz", "${it.fileName}.json", "${it.fileName}.foz.part") }.toSet()
        dir.listFiles()?.forEach { if (it.isFile && it.name !in names) it.delete() }
    }
}
