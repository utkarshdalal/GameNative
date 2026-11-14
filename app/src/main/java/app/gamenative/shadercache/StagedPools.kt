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

    /** Version of the complete staged pool of [key], or null if there is none. */
    fun stagedVersion(dir: File, key: ShaderCacheKey): Long? =
        readMeta(metaFile(dir, key))?.takeIf { poolFile(dir, key).isFile }?.optLong("version")

    /**
     * Moves the staged pool of [key] into [poolDir] as [poolFile] with [poolMetaFile] when it is newer than
     * [localVersion], replacing everything else in [poolDir]. Returns the adopted pool's metadata, or null when nothing
     * was adopted. A staged pool that is incomplete, no newer, or whose size does not match its metadata is deleted.
     */
    fun adopt(dir: File, key: ShaderCacheKey, poolDir: File, poolFile: File, poolMetaFile: File, localVersion: Long?): JSONObject? {
        val staged = poolFile(dir, key)
        val stagedMeta = metaFile(dir, key)
        if (!staged.exists() && !stagedMeta.exists()) return null
        val meta = readMeta(stagedMeta)
        val usable = meta != null && staged.isFile && staged.length() == meta.optLong("size", -1L) &&
            (localVersion == null || meta.optLong("version") > localVersion)
        if (!usable) {
            drop(dir, key)
            return null
        }
        poolDir.mkdirs()
        poolDir.listFiles()?.forEach { if (it.isFile) it.delete() }
        val moved = staged.renameTo(poolFile) || runCatching {
            staged.copyTo(poolFile, overwrite = true)
            staged.delete()
        }.isSuccess
        if (!moved) {
            poolFile.delete()
            drop(dir, key)
            return null
        }
        poolMetaFile.writeText(meta.toString())
        stagedMeta.delete()
        return meta
    }

    fun drop(dir: File, key: ShaderCacheKey) {
        poolFile(dir, key).delete()
        metaFile(dir, key).delete()
        File(dir, "${key.fileName}.foz.part").delete()
    }

    /** Deletes every staged file that does not belong to one of [keep]. */
    fun dropAllExcept(dir: File, keep: Collection<ShaderCacheKey>) {
        val names = keep.flatMap { listOf("${it.fileName}.foz", "${it.fileName}.json", "${it.fileName}.foz.part") }.toSet()
        dir.listFiles()?.forEach { if (it.isFile && it.name !in names) it.delete() }
    }
}
