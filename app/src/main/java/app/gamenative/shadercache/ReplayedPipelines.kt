package app.gamenative.shadercache

import java.io.File
import java.security.MessageDigest

/**
 * Which pipelines a container no longer needs replayed on a driver, so a pool that grew is replayed only for what it
 * gained. Kept as Fossilize databases of empty entries, the format of fossilize-replay's --replayer-cache:
 *
 *   replayed-<driver id>.<pipeline cache uuid>.foz       read by fossilize-replay, which skips the pipelines in it
 *   replayed-<driver id>.<pipeline cache uuid>.<n>.foz   written by each fossilize-replay process; [fold] merges them
 *   replayed-<driver id>.pending.foz                     marked by the app before the driver's uuid is known
 *
 * Callers serialize access to a directory.
 */
internal object ReplayedPipelines {
    private const val FLAG_RAW = 1
    private const val PENDING = "pending"

    // Graphics, compute and raytracing pipelines.
    private val PIPELINE_TAGS = listOf(6, 7, 9).map { "%0${FozArchive.TAG_CHARS}x".format(it) }.toSet()

    /** The path to give fossilize-replay as --replayer-cache for [driver]; it appends the uuid itself. */
    fun cachePath(dir: File, driver: String): File = File(dir, prefix(driver))

    /** The names (tag and hash) of the pipeline entries in the Fossilize database [file]; empty if it is not one. */
    fun pipelineNames(file: File): Set<String> =
        FozArchive.names(file).filterTo(HashSet()) { it.substring(0, FozArchive.TAG_CHARS) in PIPELINE_TAGS }

    /** Every pipeline replayed or marked for [driver]. */
    fun known(dir: File, driver: String): Set<String> =
        files(dir, driver).flatMapTo(HashSet()) { pipelineNames(it) }

    /**
     * Merges what fossilize-replay processes wrote for [driver] into the database the next one reads, and with it what
     * was marked before the driver's uuid was known.
     */
    fun fold(dir: File, driver: String) {
        val prefix = prefix(driver)
        val run = Regex("${Regex.escape(prefix)}\\.([0-9a-f]{32})\\.\\d+\\.foz")
        val runs = files(dir, driver).mapNotNull { file -> run.matchEntire(file.name)?.let { it.groupValues[1] to file } }
        for ((uuid, written) in runs.groupBy({ it.first }, { it.second })) {
            val base = File(dir, "$prefix.$uuid.foz")
            if (write(base, pipelineNames(base) + written.flatMap { pipelineNames(it) })) written.forEach { it.delete() }
        }
        val pending = File(dir, "$prefix.$PENDING.foz")
        val bases = bases(dir, driver)
        if (!pending.isFile || bases.isEmpty()) return
        val marked = pipelineNames(pending)
        if (bases.all { write(it, pipelineNames(it) + marked) }) pending.delete()
    }

    /** Records [names] as needing no replay on [driver]. */
    fun mark(dir: File, driver: String, names: Set<String>) {
        val new = names - known(dir, driver)
        if (new.isEmpty()) return
        val targets = bases(dir, driver).ifEmpty { listOf(File(dir, "${prefix(driver)}.$PENDING.foz")) }
        targets.forEach { write(it, pipelineNames(it) + new) }
    }

    private fun prefix(driver: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(driver.toByteArray())
        return "replayed-" + digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun files(dir: File, driver: String): List<File> {
        val start = prefix(driver) + "."
        return dir.listFiles()?.filter { it.isFile && it.name.startsWith(start) && it.name.endsWith(".foz") }.orEmpty()
    }

    private fun bases(dir: File, driver: String): List<File> {
        val base = Regex("${Regex.escape(prefix(driver))}\\.[0-9a-f]{32}\\.foz")
        return files(dir, driver).filter { base.matches(it.name) }
    }

    /** Replaces [file] with a database of one empty entry per name; a reader sees the old or the new file. */
    private fun write(file: File, names: Set<String>): Boolean {
        val next = File(file.parentFile, file.name + ".tmp")
        return try {
            file.parentFile?.mkdirs()
            next.outputStream().buffered().use { out ->
                out.write(FozArchive.HEADER)
                val entryHeader = ByteArray(16).also { it[4] = FLAG_RAW.toByte() }
                for (name in names) {
                    out.write(name.toByteArray(Charsets.US_ASCII))
                    out.write(entryHeader)
                }
            }
            next.renameTo(file)
        } catch (_: Exception) {
            false
        } finally {
            next.delete()
        }
    }
}
