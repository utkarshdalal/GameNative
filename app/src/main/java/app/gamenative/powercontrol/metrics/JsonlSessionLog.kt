package app.gamenative.powercontrol.metrics

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import timber.log.Timber

/**
 * Append-only JSONL session log with a byte cap and retention of the most recent
 * sessions. Files are named `<prefix><sessionStartMillis>.jsonl`.
 */
class JsonlSessionLog(
    private val tag: String,
    private val filePrefix: String,
    private val maxLogBytes: Long = DEFAULT_MAX_LOG_BYTES,
    private val maxSessionFiles: Int = DEFAULT_MAX_SESSION_FILES,
) {
    companion object {
        const val DEFAULT_MAX_LOG_BYTES = 20L * 1024L * 1024L
        const val DEFAULT_MAX_SESSION_FILES = 5
    }

    private var writer: BufferedWriter? = null
    private var file: File? = null
    private var bytes = 0L
    private var capReached = false

    val path: String?
        get() = file?.absolutePath

    fun open(directory: File, sessionStartMillis: Long) {
        try {
            directory.mkdirs()
            pruneOldSessions(directory)

            val target = File(directory, "$filePrefix$sessionStartMillis.jsonl")
            file = target
            bytes = target.length()
            capReached = false
            writer = BufferedWriter(FileWriter(target, true))
        } catch (e: Exception) {
            Timber.tag(tag).e(e, "Failed to open %s log", filePrefix)
            writer = null
            file = null
        }
    }

    fun append(line: String) {
        val target = writer ?: return
        if (capReached) return

        if (bytes >= maxLogBytes) {
            capReached = true
            Timber.tag(tag).w(
                "Log reached %d MB, no longer appending to %s",
                maxLogBytes / (1024L * 1024L),
                file?.absolutePath,
            )
            return
        }

        try {
            target.write(line)
            target.newLine()
            target.flush()
            bytes += line.length + 1
        } catch (e: Exception) {
            Timber.tag(tag).e(e, "Failed to append log, disabling file output")
            close()
        }
    }

    fun close() {
        try {
            writer?.flush()
            writer?.close()
        } catch (_: Exception) {
        }
        writer = null
        file = null
        bytes = 0L
        capReached = false
    }

    private fun pruneOldSessions(directory: File) {
        val sessions = directory
            .listFiles { candidate ->
                candidate.isFile && candidate.name.startsWith(filePrefix) && candidate.name.endsWith(".jsonl")
            }
            ?.sortedByDescending { it.lastModified() }
            ?: return

        sessions.drop(maxSessionFiles - 1).forEach { stale ->
            if (stale.delete()) {
                Timber.tag(tag).d("Deleted old log %s", stale.name)
            }
        }
    }
}
