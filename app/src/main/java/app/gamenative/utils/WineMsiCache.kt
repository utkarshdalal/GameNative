package app.gamenative.utils

import com.winlator.xenvironment.ImageFs
import java.io.File
import java.io.RandomAccessFile
import timber.log.Timber

object WineMsiCache {
    private const val SAMPLE_BYTES = 65536

    fun deleteCachedCopies(imageFs: ImageFs, source: File): Int =
        deleteCachedCopies(File(imageFs.wineprefix, "drive_c/windows/Installer"), source)

    fun deleteCachedCopies(installerDir: File, source: File): Int {
        if (!source.isFile) return 0
        val cached = installerDir.listFiles() ?: return 0
        val sourceLength = source.length()
        var removed = 0
        for (file in cached) {
            if (!file.isFile || !file.name.endsWith(".msi", ignoreCase = true)) continue
            if (file.length() != sourceLength) continue
            if (!sameEnds(source, file, sourceLength)) continue
            if (file.delete()) removed++ else Timber.w("Could not delete cached MSI ${file.absolutePath}")
        }
        if (removed > 0) Timber.i("Removed $removed cached copies of ${source.name} from ${installerDir.absolutePath}")
        return removed
    }

    private fun sameEnds(a: File, b: File, length: Long): Boolean {
        val sample = minOf(SAMPLE_BYTES.toLong(), length).toInt()
        if (sample == 0) return true
        return try {
            RandomAccessFile(a, "r").use { ra ->
                RandomAccessFile(b, "r").use { rb ->
                    val bufA = ByteArray(sample)
                    val bufB = ByteArray(sample)
                    ra.readFully(bufA); rb.readFully(bufB)
                    if (!bufA.contentEquals(bufB)) return false
                    ra.seek(length - sample); rb.seek(length - sample)
                    ra.readFully(bufA); rb.readFully(bufB)
                    bufA.contentEquals(bufB)
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Could not compare ${a.name} with ${b.name}")
            false
        }
    }
}
