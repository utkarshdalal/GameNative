package app.gamenative.utils

import com.winlator.container.Container
import com.winlator.core.OnExtractFileListener
import java.io.File
import timber.log.Timber

/**
 * Lets a launch skip re-extracting a component (DXVK/VKD3D DLLs, the graphics wrapper) when every
 * file the previous extraction wrote is still exactly as it was left (same length and mtime).
 *
 * Launches used to re-extract unconditionally to recover from containers whose DLLs got clobbered.
 * A clobbered, deleted or replaced file changes its length or mtime, so it still triggers a full
 * re-extract; an untouched container no longer pays the decompress + write on every boot.
 */
object ExtractionFingerprint {
    /** Wraps [delegate] (which may redirect or drop entries) and records the files actually written. */
    class Recorder(private val delegate: OnExtractFileListener?) : OnExtractFileListener {
        val files = LinkedHashSet<File>()

        /** Set by callers when any extraction through this recorder failed; nothing is saved then. */
        var failed = false

        override fun onExtractFile(destination: File?, size: Long): File? {
            val target = if (delegate != null) delegate.onExtractFile(destination, size) else destination
            if (target != null) files += target
            return target
        }
    }

    private fun recordFile(container: Container, key: String) = File(container.rootDir, ".extract-$key")

    private fun stateOf(file: File): String =
        if (file.isFile) "${file.length()}:${file.lastModified()}" else "missing"

    /**
     * True when the record for [key] was written for [signature] and none of its files changed since.
     */
    fun isIntact(container: Container, key: String, signature: String): Boolean {
        val record = recordFile(container, key)
        if (!record.isFile) return false
        return try {
            val lines = record.readLines()
            if (lines.size < 2 || lines[0] != signature) return false
            for (line in lines.drop(1)) {
                if (line.isEmpty()) continue
                val tab = line.lastIndexOf('\t')
                if (tab <= 0) return false
                if (stateOf(File(line.substring(0, tab))) != line.substring(tab + 1)) {
                    Timber.i("Extraction '$key' changed on disk (${line.substring(0, tab)}), re-extracting")
                    return false
                }
            }
            true
        } catch (e: Exception) {
            Timber.w(e, "Unreadable extraction record for '$key'")
            false
        }
    }

    /** Stores the current state of [files] for [key]; an empty set clears the record instead. */
    fun save(container: Container, key: String, signature: String, files: Collection<File>) {
        val record = recordFile(container, key)
        if (files.isEmpty()) {
            record.delete()
            return
        }
        try {
            val body = files.joinToString("\n") { "${it.absolutePath}\t${stateOf(it)}" }
            record.writeText("$signature\n$body\n")
        } catch (e: Exception) {
            Timber.w(e, "Could not write extraction record for '$key'")
            record.delete()
        }
    }

    fun clear(container: Container, key: String) {
        recordFile(container, key).delete()
    }
}
