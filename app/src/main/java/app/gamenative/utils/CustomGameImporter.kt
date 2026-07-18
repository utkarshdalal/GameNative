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
 * Copies a user-picked folder (a SAF tree URI from OpenDocumentTree) into the app's own
 * CustomGames sandbox. The picker grants a one-shot, per-folder read grant, so this needs
 * no MANAGE_EXTERNAL_STORAGE / READ_EXTERNAL_STORAGE permission — which is what lets custom
 * games work on the modern (scoped-storage) build. Once copied, the game lives on fast,
 * app-owned storage and is discovered by [CustomGameScanner.scanAsLibraryItems].
 */
object CustomGameImporter {

    data class Progress(val copiedBytes: Long, val currentFile: String)

    /**
     * @return the absolute path of the imported folder on success.
     */
    suspend fun importFromTreeUri(
        context: Context,
        treeUri: Uri,
        onProgress: (Progress) -> Unit = {},
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val src = DocumentFile.fromTreeUri(context, treeUri)
            if (src == null || !src.isDirectory) {
                return@withContext Result.failure(IllegalArgumentException("Selected item is not a folder"))
            }

            val name = sanitizeName(src.name)
            var dest = File(CustomGameScanner.defaultRootPath, name)
            var suffix = 1
            while (dest.exists()) {
                dest = File(CustomGameScanner.defaultRootPath, "$name ($suffix)")
                suffix++
            }
            dest.mkdirs()

            var copied = 0L
            copyTree(context, src, dest) { bytes, fileName ->
                copied += bytes
                onProgress(Progress(copied, fileName))
            }

            Timber.tag("CustomGameImporter").d("Imported ${src.name} to ${dest.path} ($copied bytes)")
            Result.success(dest.absolutePath)
        } catch (e: Exception) {
            Timber.tag("CustomGameImporter").e(e, "Import failed")
            Result.failure(e)
        }
    }

    private suspend fun copyTree(
        context: Context,
        src: DocumentFile,
        dest: File,
        onBytes: (Long, String) -> Unit,
    ) {
        for (child in src.listFiles()) {
            coroutineContext.ensureActive()
            val childName = sanitizeName(child.name)
            if (child.isDirectory) {
                val subDir = File(dest, childName)
                subDir.mkdirs()
                copyTree(context, child, subDir, onBytes)
            } else {
                val outFile = File(dest, childName)
                context.contentResolver.openInputStream(child.uri)?.use { input ->
                    outFile.outputStream().use { output ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            onBytes(n.toLong(), childName)
                        }
                    }
                } ?: Timber.tag("CustomGameImporter").w("Could not open ${child.uri}")
            }
        }
    }

    /** Strips path separators and blank names so a document name can't escape the sandbox. */
    private fun sanitizeName(raw: String?): String {
        val cleaned = raw?.replace('/', '_')?.replace('\\', '_')?.trim()
        return if (cleaned.isNullOrEmpty() || cleaned == "." || cleaned == "..") "ImportedGame" else cleaned
    }
}
