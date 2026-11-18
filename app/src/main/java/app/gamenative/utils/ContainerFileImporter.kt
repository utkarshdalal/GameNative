package app.gamenative.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber

object ContainerFileImporter {
    private const val COPY_BUFFER_SIZE = 256 * 1024
    private val INVALID_NAME_CHARS = Regex("[<>:\"/\\\\|?*\\p{Cntrl}]")

    data class PendingFile(val uri: Uri, val name: String, val size: Long)

    data class ImportResult(val copied: Int, val skipped: Int, val failed: List<String>)

    fun describe(context: Context, uris: List<Uri>): List<PendingFile> = uris.map { uri ->
        var displayName: String? = null
        var size = -1L
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) displayName = cursor.getString(nameIndex)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }.onFailure { Timber.w(it, "Failed to query metadata for %s", uri) }
        PendingFile(uri, sanitizeName(displayName ?: uri.lastPathSegment), size)
    }

    fun sanitizeName(raw: String?): String {
        val base = raw.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.replace(INVALID_NAME_CHARS, "").trim()
        return if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") "file" else cleaned
    }

    fun ensureInside(root: File, dir: File): Boolean = runCatching {
        val rootPath = root.canonicalPath
        val dirPath = dir.canonicalPath
        dirPath == rootPath || dirPath.startsWith(rootPath.trimEnd(File.separatorChar) + File.separator)
    }.getOrDefault(false)

    suspend fun import(
        context: Context,
        files: List<PendingFile>,
        destDir: File,
        overwrite: Boolean,
        onProgress: (index: Int, fileName: String, bytesCopied: Long, bytesTotal: Long) -> Unit,
    ): ImportResult = withContext(Dispatchers.IO) {
        var copied = 0
        var skipped = 0
        val failed = mutableListOf<String>()
        files.forEachIndexed { index, file ->
            currentCoroutineContext().ensureActive()
            val target = File(destDir, file.name)
            if (target.exists() && !overwrite) {
                skipped++
                return@forEachIndexed
            }
            val partFile = File(destDir, "${file.name}.part")
            try {
                if (target.isDirectory) {
                    throw IOException("${target.absolutePath} is a directory")
                }
                onProgress(index, file.name, 0L, file.size)
                val written = copyToPart(context, file, partFile) { bytes ->
                    onProgress(index, file.name, bytes, file.size)
                }
                if (file.size >= 0 && written != file.size) {
                    throw IOException("Size mismatch for ${file.name}: expected ${file.size}, got $written")
                }
                if (!partFile.renameTo(target) &&
                    !(target.exists() && target.delete() && partFile.renameTo(target))
                ) {
                    throw IOException("Unable to rename ${partFile.absolutePath}")
                }
                copied++
            } catch (e: CancellationException) {
                partFile.delete()
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Failed to import %s", file.name)
                partFile.delete()
                failed.add(file.name)
            }
        }
        ImportResult(copied, skipped, failed)
    }

    private suspend fun copyToPart(
        context: Context,
        file: PendingFile,
        partFile: File,
        onBytes: (Long) -> Unit,
    ): Long {
        var total = 0L
        val input = context.contentResolver.openInputStream(file.uri)
            ?: throw IOException("Unable to open ${file.uri}")
        input.use { stream ->
            FileOutputStream(partFile).use { output ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = stream.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                    total += read
                    onBytes(total)
                }
            }
        }
        return total
    }
}
