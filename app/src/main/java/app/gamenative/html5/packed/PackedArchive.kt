package app.gamenative.html5.packed

import java.io.Closeable
import java.io.InputStream

// read-only packed web root (`zip:` / `c3ab:` webRoots) served by ZipAssetInterceptor.
// names are archive-relative with '/' separators; callers MUST reject '..' before lookup.
interface PackedArchive : Closeable {
    // null for a missing entry or a directory.
    fun size(name: String): Long?

    fun open(name: String): InputStream?

    fun names(): Sequence<String>

    companion object {
        const val ZIP_PREFIX = "zip:"
        const val C3_BUNDLE_PREFIX = "c3ab:"

        fun isPackedWebRoot(webRoot: String): Boolean =
            webRoot.startsWith(ZIP_PREFIX) || webRoot.startsWith(C3_BUNDLE_PREFIX)
    }
}
