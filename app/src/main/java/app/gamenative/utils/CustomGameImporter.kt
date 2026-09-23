package app.gamenative.utils

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Imports a user-picked folder (a SAF tree URI from OpenDocumentTree) into
 * [CustomGameScanner.importRootPath], needing only the picker grant. The bytes must pass
 * through this app for wine to be able to read them under scoped storage, so a "move" is
 * per-file copy + verify + delete-source rather than a rename.
 */
object CustomGameImporter {

    data class Progress(val copiedBytes: Long, val currentFile: String)

    /**
     * @param deleteSource delete each source file once its copy verifies (move semantics)
     * @return the absolute path of the imported folder on success.
     */
    suspend fun importFromTreeUri(
        context: Context,
        treeUri: Uri,
        deleteSource: Boolean,
        onProgress: (Progress) -> Unit = {},
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val src = DocumentFile.fromTreeUri(context, treeUri)
            if (src == null || !src.isDirectory) {
                return@withContext Result.failure(IllegalArgumentException("Selected item is not a folder"))
            }

            val importRoot = CustomGameScanner.importRootPath
            val name = sanitizeName(src.name)
            var dest = File(importRoot, name)
            var suffix = 1
            while (dest.exists()) {
                dest = File(importRoot, "$name ($suffix)")
                suffix++
            }
            dest.mkdirs()

            var copied = 0L
            moveTree(context, src, dest, deleteSource) { bytes, fileName ->
                copied += bytes
                onProgress(Progress(copied, fileName))
            }
            if (deleteSource) {
                if (!src.delete()) {
                    Timber.tag("CustomGameImporter").w("Could not remove source root ${src.uri}")
                }
            }

            Timber.tag("CustomGameImporter").d("Imported ${src.name} to ${dest.path} ($copied bytes, move=$deleteSource)")
            Result.success(dest.absolutePath)
        } catch (e: Exception) {
            // Keep the destination: with deleteSource, already-moved files exist only there
            Timber.tag("CustomGameImporter").e(e, "Import failed")
            Result.failure(e)
        }
    }

    private suspend fun moveTree(
        context: Context,
        src: DocumentFile,
        dest: File,
        deleteSource: Boolean,
        onBytes: (Long, String) -> Unit,
    ) {
        for (child in src.listFiles()) {
            coroutineContext.ensureActive()
            val childName = sanitizeName(child.name)
            if (child.isDirectory) {
                val subDir = File(dest, childName)
                subDir.mkdirs()
                moveTree(context, child, subDir, deleteSource, onBytes)
                if (deleteSource && !child.delete()) {
                    Timber.tag("CustomGameImporter").w("Could not remove source folder ${child.uri}")
                }
            } else {
                val expected = child.length()
                val outFile = File(dest, childName)
                var written = 0L
                val input = context.contentResolver.openInputStream(child.uri)
                if (input == null) {
                    throw java.io.IOException("Could not open ${child.uri}")
                }
                input.use { ins ->
                    outFile.outputStream().use { output ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = ins.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            written += n
                            onBytes(n.toLong(), childName)
                        }
                    }
                }
                if (expected > 0 && written != expected) {
                    outFile.delete()
                    throw java.io.IOException("Size mismatch for $childName: $written of $expected bytes")
                }
                if (deleteSource && !child.delete()) {
                    Timber.tag("CustomGameImporter").w("Could not delete source file ${child.uri}")
                }
            }
        }
    }

    /** Strips path separators and blank names so a document name can't escape the sandbox. */
    private fun sanitizeName(raw: String?): String {
        val cleaned = raw?.replace('/', '_')?.replace('\\', '_')?.trim()
        return if (cleaned.isNullOrEmpty() || cleaned == "." || cleaned == "..") "ImportedGame" else cleaned
    }
}
