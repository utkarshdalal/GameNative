package app.gamenative.utils

import android.os.StatFs
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileVisitResult
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import timber.log.Timber
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

object StorageUtils {

    fun getAvailableSpace(path: String): Long {
        val file = File(path)
        if (!file.exists()) {
            throw IllegalArgumentException("Invalid path: $path")
        }
        val stat = StatFs(path)
        return stat.blockSizeLong * stat.availableBlocksLong
    }

    suspend fun getFolderSize(folderPath: String): Long {
        val folder = File(folderPath)
        if (folder.exists()) {
            var bytes = 0L
            val tree = folder.walk()
            tree.forEach {
                bytes += it.length()
                // allow interruption if run as coroutine
                yield()
            }
            return bytes
        }
        return 0L
    }

    fun formatBinarySize(bytes: Long, decimalPlaces: Int = 2): String {
        require(bytes > Long.MIN_VALUE) { "Out of range" }
        require(decimalPlaces >= 0) { "Negative decimal places unsupported" }

        val isNegative = bytes < 0
        val absBytes = kotlin.math.abs(bytes)

        if (absBytes < 1024) {
            return "$bytes B"
        }

        val units = arrayOf("KiB", "MiB", "GiB", "TiB", "PiB")
        val digitGroups = (63 - absBytes.countLeadingZeroBits()) / 10
        val value = absBytes.toDouble() / (1L shl (digitGroups * 10))

        val result = "%.${decimalPlaces}f %s".format(
            if (isNegative) -value else value,
            units[digitGroups - 1],
        )

        return result
    }

    /**
     * Move games from internal only storage to user storage.
     * This should be removed after a few versions and just
     * remove the old path to free up space.
     */
    suspend fun moveGamesFromOldPath(
        sourceDir: String,
        targetDir: String,
        onProgressUpdate: (currentFile: String, fileProgress: Float, movedFiles: Int, totalFiles: Int) -> Unit,
        onComplete: () -> Unit,
    ) = withContext(Dispatchers.IO) {
        try {
            val sourcePath = Paths.get(sourceDir)
            val targetPath = Paths.get(targetDir)

            if (!Files.exists(targetPath)) {
                Files.createDirectories(targetPath)
            }

            val allFiles = mutableListOf<Path>()
            Files.walkFileTree(
                sourcePath,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (Files.isRegularFile(file)) {
                            allFiles.add(file)
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                        Timber.e(exc, "Failed to visit file: $file")
                        return FileVisitResult.CONTINUE
                    }
                },
            )

            val totalFiles = allFiles.size
            var filesMoved = 0

            for (sourcePath in allFiles) {
                val relativePath = sourcePath.subpath(Paths.get(sourceDir).nameCount, sourcePath.nameCount)
                val targetFilePath = Paths.get(targetDir, relativePath.toString())

                Files.createDirectories(targetFilePath.parent)

                // val fileName = sourcePath.fileName.toString()

                try {
                    Files.move(sourcePath, targetFilePath, StandardCopyOption.ATOMIC_MOVE)

                    withContext(Dispatchers.Main) {
                        onProgressUpdate(relativePath.toString(), 1f, filesMoved++, totalFiles)
                    }
                } catch (e: Exception) {
                    val fileSize = Files.size(sourcePath)
                    var bytesCopied = 0L

                    FileChannel.open(sourcePath, StandardOpenOption.READ).use { sourceChannel ->
                        FileChannel.open(
                            targetFilePath,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                        ).use { targetChannel ->
                            val buffer = ByteBuffer.allocateDirect(8 * 1024 * 1024)
                            var bytesRead: Int

                            while (sourceChannel.read(buffer).also { bytesRead = it } > 0) {
                                buffer.flip()
                                targetChannel.write(buffer)
                                buffer.compact()

                                bytesCopied += bytesRead

                                val fileProgress = if (fileSize > 0) {
                                    bytesCopied.toFloat() / fileSize
                                } else {
                                    1f
                                }

                                withContext(Dispatchers.Main) {
                                    onProgressUpdate(relativePath.toString(), fileProgress, filesMoved, totalFiles)
                                }
                            }

                            targetChannel.force(true)
                        }
                    }

                    Files.delete(sourcePath)
                    withContext(Dispatchers.Main) {
                        onProgressUpdate(relativePath.toString(), 1f, filesMoved++, totalFiles)
                    }
                }
            }

            Files.walkFileTree(
                sourcePath,
                object : SimpleFileVisitor<Path>() {
                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        if (exc == null) {
                            try {
                                var isEmpty = true
                                Files.newDirectoryStream(dir).use { stream ->
                                    if (stream.iterator().hasNext()) {
                                        isEmpty = false
                                    }
                                }

                                if (isEmpty && (dir != sourcePath)) {
                                    Files.delete(dir)
                                }
                            } catch (e: Exception) {
                                Timber.e(e, "Failed to delete directory: $dir")
                            }
                        }
                        return FileVisitResult.CONTINUE
                    }
                },
            )

            try {
                var isEmpty = true
                Files.newDirectoryStream(sourcePath).use { stream ->
                    if (stream.iterator().hasNext()) {
                        isEmpty = false
                    }
                }

                if (isEmpty) {
                    Files.delete(sourcePath)
                }
            } catch (e: Exception) {
                Timber.e(e)
            }

            withContext(Dispatchers.Main) {
                onComplete()
            }
        } catch (e: Exception) {
            Timber.e(e)
            withContext(Dispatchers.Main) {
                onComplete()
            }
        }
    }
}
