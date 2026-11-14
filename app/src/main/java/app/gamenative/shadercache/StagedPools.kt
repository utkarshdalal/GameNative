package app.gamenative.shadercache

import java.io.File
import org.json.JSONObject

/**
 * Pools downloaded when a game finishes installing. A game has no container until its first launch, so its pools wait
 * in app storage and that launch moves them into the container's pool directory.
 */
internal object StagedPools {
    fun dir(filesDir: File) = File(filesDir, "shadercache/staged")
    fun poolFile(dir: File, pool: ShaderPool) = File(dir, "${pool.fileName}.foz")
    fun metaFile(dir: File, pool: ShaderPool) = File(dir, "${pool.fileName}.json")

    fun meta(version: Long, sha256: String, size: Long, entries: Long): JSONObject = JSONObject()
        .put("version", version)
        .put("sha256", sha256)
        .put("size", size)
        .put("entries", entries)

    fun readMeta(file: File): JSONObject? =
        if (file.isFile) runCatching { JSONObject(file.readText()) }.getOrNull() else null

    private fun partialFile(dir: File, pool: ShaderPool) = File(dir, "${pool.fileName}.foz.part")

    private fun isComplete(staged: File, meta: JSONObject?): Boolean =
        meta != null && staged.isFile && staged.length() == meta.optLong("size", -1L)

    /** Version of the complete staged [pool], or null if there is none. */
    fun stagedVersion(dir: File, pool: ShaderPool): Long? =
        readMeta(metaFile(dir, pool))?.takeIf { isComplete(poolFile(dir, pool), it) }?.optLong("version")

    /**
     * Moves the staged [pool] into [poolDir] as [poolFile] with [poolMetaFile] when it is newer than [localVersion].
     * Returns the adopted pool's metadata, or null when nothing was adopted. A staged pool that is incomplete, no newer, or whose size does not match its metadata is deleted.
     */
    fun adopt(dir: File, pool: ShaderPool, poolDir: File, poolFile: File, poolMetaFile: File, localVersion: Long?): JSONObject? {
        val staged = poolFile(dir, pool)
        val stagedMeta = metaFile(dir, pool)
        if (!staged.exists() && !stagedMeta.exists()) {
            // Left by an install that was stopped mid-download.
            partialFile(dir, pool).delete()
            return null
        }
        val meta = readMeta(stagedMeta)
        if (!isComplete(staged, meta) || (localVersion != null && meta!!.optLong("version") <= localVersion)) {
            drop(dir, pool)
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
            drop(dir, pool)
            return null
        }
        poolMetaFile.delete()
        if (!newPool.renameTo(poolFile) || !newMeta.renameTo(poolMetaFile)) {
            newPool.delete()
            newMeta.delete()
            drop(dir, pool)
            return null
        }
        drop(dir, pool)
        return meta
    }

    fun drop(dir: File, pool: ShaderPool) {
        poolFile(dir, pool).delete()
        metaFile(dir, pool).delete()
        partialFile(dir, pool).delete()
    }

    /** Deletes every staged file that does not belong to one of [keep]. */
    fun dropAllExcept(dir: File, keep: Collection<ShaderPool>) {
        val names = fileNames(keep)
        dir.listFiles()?.forEach { if (it.isFile && it.name !in names) it.delete() }
    }

    /** Deletes the staged pools of [game] other than [keep]. */
    fun dropGame(dir: File, game: String, keep: Collection<ShaderPool> = emptyList()) {
        val names = fileNames(keep)
        dir.listFiles()?.forEach { if (it.isFile && it.name !in names && ShaderPool.isFileOf(game, it.name)) it.delete() }
    }

    private fun fileNames(pools: Collection<ShaderPool>) =
        pools.flatMap { listOf("${it.fileName}.foz", "${it.fileName}.json", "${it.fileName}.foz.part") }.toSet()
}
