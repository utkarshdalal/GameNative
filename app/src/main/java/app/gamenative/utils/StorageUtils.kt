package app.gamenative.utils

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
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

    /**
     * Free space on the volume that would contain [path], even if [path] doesn't exist yet
     * (e.g. a game's install directory before the first download has created it). Walks up to
     * the nearest existing ancestor, which is on the same volume, since [StatFs] needs an
     * existing path. Throws only if no ancestor exists (e.g. a blank/invalid path).
     */
    fun getAvailableSpaceForUncreatedPath(path: String): Long {
        var file: File? = File(path)
        while (file != null && !file.exists()) {
            file = file.parentFile
        }
        if (file == null) {
            throw IllegalArgumentException("Invalid path: $path")
        }
        val stat = StatFs(file.path)
        return stat.blockSizeLong * stat.availableBlocksLong
    }

    fun getTotalSpace(path: String): Long {
        val file = File(path)
        if (!file.exists()) {
            throw IllegalArgumentException("Invalid path: $path")
        }
        val stat = StatFs(path)
        return stat.blockSizeLong * stat.blockCountLong
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
     * Gets all app-specific external files directories, using StorageManager as a fallback
     * for cases where context.getExternalFilesDirs(null) might return null or incomplete results
     * (e.g. USB OTG drives, which most devices omit from getExternalFilesDirs).
     */
    fun getAllExternalFilesDirs(context: Context): List<File> {
        val result = mutableSetOf<File>()

        // 1. Primary source: Standard Android API
        try {
            context.getExternalFilesDirs(null)?.filterNotNull()?.let {
                result.addAll(it)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error calling getExternalFilesDirs")
        }

        // 2. Fallback: Iterate through all storage volumes using StorageManager
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        if (sm != null) {
            try {
                for (volume in sm.storageVolumes) {
                    if (volume.state != Environment.MEDIA_MOUNTED) continue

                    val volumeDir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        volume.directory
                    } else {
                        // Use reflection for older APIs (26-29) if getExternalFilesDirs missed it
                        try {
                            val getPath = volume.javaClass.getMethod("getPath")
                            (getPath.invoke(volume) as? String)?.let { File(it) }
                        } catch (re: Exception) {
                            null
                        }
                    } ?: continue

                    // The app-specific dedicated directory is /Android/data/<package_name>/files
                    val appFilesDir = File(volumeDir, "Android/data/${context.packageName}/files")
                    if (!result.contains(appFilesDir) && (appFilesDir.exists() || appFilesDir.mkdirs())) {
                        result.add(appFilesDir)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error iterating storage volumes in fallback")
            }
        }

        return result.toList()
    }

    suspend fun moveDirectory(
        sourceDir: String,
        targetDir: String,
        onProgressUpdate: (currentFile: String, fileProgress: Float, movedFiles: Int, totalFiles: Int) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val sourceRootPath = Paths.get(sourceDir)
            val targetRootPath = Paths.get(targetDir)

            if (!Files.exists(sourceRootPath) || !Files.isDirectory(sourceRootPath)) {
                return@withContext Result.failure(IllegalArgumentException("Invalid source directory: $sourceDir"))
            }

            if (!Files.exists(targetRootPath)) {
                Files.createDirectories(targetRootPath)
            }

            val allFiles = mutableListOf<Path>()
            Files.walkFileTree(
                sourceRootPath,
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

            for (sourceFilePath in allFiles) {
                val relativePath = sourceRootPath.relativize(sourceFilePath)
                val targetFilePath = targetRootPath.resolve(relativePath)

                Files.createDirectories(targetFilePath.parent)

                try {
                    Files.move(sourceFilePath, targetFilePath, StandardCopyOption.ATOMIC_MOVE)

                    withContext(Dispatchers.Main) {
                        onProgressUpdate(relativePath.toString(), 1f, filesMoved++, totalFiles)
                    }
                } catch (e: Exception) {
                    val fileSize = Files.size(sourceFilePath)
                    var bytesCopied = 0L

                    FileChannel.open(sourceFilePath, StandardOpenOption.READ).use { sourceChannel ->
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

                    Files.delete(sourceFilePath)
                    withContext(Dispatchers.Main) {
                        onProgressUpdate(relativePath.toString(), 1f, filesMoved++, totalFiles)
                    }
                }
            }

            Files.walkFileTree(
                sourceRootPath,
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

                                if (isEmpty && dir != sourceRootPath) {
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
                Files.newDirectoryStream(sourceRootPath).use { stream ->
                    if (stream.iterator().hasNext()) {
                        isEmpty = false
                    }
                }

                if (isEmpty) {
                    Files.delete(sourceRootPath)
                }
            } catch (e: Exception) {
                Timber.e(e)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e)
            Result.failure(e)
        }
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
        moveDirectory(
            sourceDir = sourceDir,
            targetDir = targetDir,
            onProgressUpdate = onProgressUpdate,
        )

        withContext(Dispatchers.Main) {
            onComplete()
        }
    }
}
