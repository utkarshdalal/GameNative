package app.gamenative.html5.packed

import java.io.InputStream
import org.apache.commons.compress.archivers.zip.ZipFile

// commons-compress, NOT java.util.zip: old NW.js single-exe builds prepend nw.exe to the zip, and
// java.util.zip treats entry offsets as absolute and lands in the exe.
class ZipPackedArchive(private val zip: ZipFile) : PackedArchive {
    override fun size(name: String): Long? =
        zip.getEntry(name)?.takeUnless { it.isDirectory }?.size

    override fun open(name: String): InputStream? {
        val entry = zip.getEntry(name)?.takeUnless { it.isDirectory } ?: return null
        return zip.getInputStream(entry)
    }

    override fun names(): Sequence<String> = zip.entries.asSequence().map { it.name }

    override fun close() = zip.close()
}
