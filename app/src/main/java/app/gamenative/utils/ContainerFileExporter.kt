package app.gamenative.utils

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber

object ContainerFileExporter {
    private const val COPY_BUFFER_SIZE = 256 * 1024
    private const val DEFAULT_MIME_TYPE = "application/octet-stream"
    private const val EXPORT_PARENT = "GameNative"

    data class ExportResult(val copied: Int, val failed: List<String>, val destinationLabel: String)

    fun destinationLabel(gameName: String): String =
        "${Environment.DIRECTORY_DOWNLOADS}/$EXPORT_PARENT/${ContainerFileImporter.sanitizeName(gameName)}"

    suspend fun export(
        context: Context,
        files: List<File>,
        gameName: String,
        onProgress: (index: Int, fileName: String, bytesCopied: Long, bytesTotal: Long) -> Unit,
    ): ExportResult = withContext(Dispatchers.IO) {
        val relativePath = destinationLabel(gameName)
        var copied = 0
        val failed = mutableListOf<String>()
        val scanned = mutableListOf<String>()
        try {
            files.forEachIndexed { index, file ->
                currentCoroutineContext().ensureActive()
                try {
                    val total = file.length()
                    onProgress(index, file.name, 0L, total)
                    val onBytes: (Long) -> Unit = { bytes -> onProgress(index, file.name, bytes, total) }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        exportToMediaStore(context, file, relativePath, total, onBytes)
                    } else {
                        scanned += exportToPublicDir(file, relativePath, total, onBytes).absolutePath
                    }
                    copied++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Failed to export %s", file.name)
                    failed.add(file.name)
                }
            }
        } finally {
            if (scanned.isNotEmpty()) {
                MediaScannerConnection.scanFile(context, scanned.toTypedArray(), null, null)
            }
        }
        ExportResult(copied, failed, relativePath)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun exportToMediaStore(
        context: Context,
        file: File,
        relativePath: String,
        total: Long,
        onBytes: (Long) -> Unit,
    ) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, mimeTypeFor(file))
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Unable to create download entry for ${file.name}")
        try {
            val output = resolver.openOutputStream(uri) ?: throw IOException("Unable to open $uri")
            val written = output.use { copyFrom(file, it, onBytes) }
            verifySize(file, total, written)
            val published = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(uri, published, null, null)
        } catch (e: Throwable) {
            deleteEntry(context, uri)
            throw e
        }
    }

    private suspend fun exportToPublicDir(
        file: File,
        relativePath: String,
        total: Long,
        onBytes: (Long) -> Unit,
    ): File {
        @Suppress("DEPRECATION")
        val destDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            relativePath.removePrefix(Environment.DIRECTORY_DOWNLOADS).trimStart('/'),
        )
        if (!destDir.isDirectory && !destDir.mkdirs()) {
            throw IOException("Unable to create ${destDir.absolutePath}")
        }
        val target = uniqueTarget(destDir, file.name)
        val partFile = File(destDir, "${target.name}.part")
        try {
            val written = FileOutputStream(partFile).use { copyFrom(file, it, onBytes) }
            verifySize(file, total, written)
            if (!partFile.renameTo(target)) {
                throw IOException("Unable to rename ${partFile.absolutePath}")
            }
        } catch (e: Throwable) {
            partFile.delete()
            throw e
        }
        return target
    }

    private fun uniqueTarget(dir: File, name: String): File {
        val candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while (true) {
            val next = File(dir, "$base ($n)$extension")
            if (!next.exists()) return next
            n++
        }
    }

    private fun verifySize(file: File, expected: Long, written: Long) {
        if (written != expected) {
            throw IOException("Size mismatch for ${file.name}: expected $expected, got $written")
        }
    }

    private fun mimeTypeFor(file: File): String {
        val extension = file.extension.lowercase()
        if (extension.isEmpty()) return DEFAULT_MIME_TYPE
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: DEFAULT_MIME_TYPE
    }

    private fun deleteEntry(context: Context, uri: Uri) {
        runCatching { context.contentResolver.delete(uri, null, null) }
            .onFailure { Timber.w(it, "Failed to delete %s", uri) }
    }

    private suspend fun copyFrom(file: File, output: OutputStream, onBytes: (Long) -> Unit): Long {
        var total = 0L
        FileInputStream(file).use { input ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                output.write(buffer, 0, read)
                total += read
                onBytes(total)
            }
        }
        return total
    }
}
