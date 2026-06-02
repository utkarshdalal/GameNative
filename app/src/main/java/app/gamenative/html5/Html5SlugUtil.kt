package app.gamenative.html5

import java.io.File
import timber.log.Timber

// dir name for html5-containers/<slug>/. it is only a label, NOT identity: every lookup scans the
// dirs and matches container.id, which is what makes canonicalize() safe.
object Html5SlugUtil {

    private const val MAX_JSON_DIR_NAME = 24

    // <install folder>-<store>-<id>. the container id is unique across stores, so the name is
    // collision-free; the folder prefix is only for readability in logs.
    fun slug(folderName: String, containerId: String): String {
        val base = normalize(folderName).take(MAX_JSON_DIR_NAME).trim('-').ifEmpty { "game" }
        val id = normalize(containerId).ifEmpty { "container" }
        return "$base-$id"
    }

    // lazy rename of a legacy dir. failure is harmless (lookups match container.id); never
    // clobbers an existing dir. returns the dir to use.
    fun canonicalize(dir: File, installPath: String, containerId: String): File {
        val folderName = File(installPath).name
        if (folderName.isEmpty() || containerId.isEmpty()) return dir
        val canonical = slug(folderName, containerId)
        if (dir.name == canonical) return dir
        val target = File(dir.parentFile, canonical)
        if (target.exists()) {
            Timber.tag(TAG).w("canonicalize: %s is taken, leaving %s alone", canonical, dir.name)
            return dir
        }
        return if (dir.renameTo(target)) {
            Timber.tag(TAG).i("canonicalize: %s -> %s", dir.name, canonical)
            target
        } else {
            Timber.tag(TAG).w("canonicalize: could not rename %s -> %s", dir.name, canonical)
            dir
        }
    }

    private fun normalize(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    private const val TAG = "Html5SlugUtil"
}
